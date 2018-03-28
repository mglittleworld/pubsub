// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.kafka.sink;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.pubsub.kafka.common.ConnectorUtils;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SinkTask} used by a {@link CloudPubSubSinkConnector} to write messages to <a
 * href="https://cloud.google.com/pubsub">Google Cloud Pub/Sub</a>.
 */
public class CloudPubSubSinkTask extends SinkTask {

  private static final Logger log = LoggerFactory.getLogger(CloudPubSubSinkTask.class);
  private static final int NUM_CPS_PUBLISHERS = 5; // Marium : changed 10 to 2 to check the timeouts
  private static final int CPS_MAX_REQUEST_SIZE = (10 << 20) - 1024; // Leave room for overhead.
  private static final int CPS_MAX_MESSAGES_PER_REQUEST = 1000;
  private static final int CPS_MESSAGE_KEY_ATTRIBUTE_SIZE =
      ConnectorUtils.CPS_MESSAGE_KEY_ATTRIBUTE.length();

  // Maps a topic to another map which contains the outstanding futures per partition
  private Map<String, Map<Integer, OutstandingFuturesForPartition>> allOutstandingFutures =
      new HashMap<>();
  // Maps a topic to another map which contains the unpublished messages per partition
  private Map<String, Map<Integer, UnpublishedMessagesForPartition>> allUnpublishedMessages =
      new HashMap<>();
  private String cpsTopic;
  private String messageBodyName;
  private int maxBufferSize;
  private CloudPubSubPublisher publisher;

  /** Holds a list of the publishing futures that have not been processed for a single partition. */
  private class OutstandingFuturesForPartition {
    public List<ListenableFuture<PublishResponse>> futures = new ArrayList<>();
  }

  /**
   * Holds a list of the unpublished messages for a single partition and the total size in bytes of
   * the messages in the list.
   */
  private class UnpublishedMessagesForPartition {
    public List<PubsubMessage> messages = new ArrayList<>();
    public int size = 0;
  }

  public CloudPubSubSinkTask() {}

  @VisibleForTesting
  public CloudPubSubSinkTask(CloudPubSubPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public String version() {
    return new CloudPubSubSinkConnector().version();
  }

  @Override
  public void start(Map<String, String> props) {
    Map<String, Object> validatedProps = new CloudPubSubSinkConnector().config().parse(props);
    cpsTopic =
        String.format(
            ConnectorUtils.CPS_TOPIC_FORMAT,
            validatedProps.get(ConnectorUtils.CPS_PROJECT_CONFIG),
            validatedProps.get(ConnectorUtils.CPS_TOPIC_CONFIG));
    maxBufferSize = (Integer) validatedProps.get(CloudPubSubSinkConnector.MAX_BUFFER_SIZE_CONFIG);
    messageBodyName = (String) validatedProps.get(CloudPubSubSinkConnector.CPS_MESSAGE_BODY_NAME);
    if (publisher == null) {
      // Only do this if we did not use the constructor.
      publisher = new CloudPubSubRoundRobinPublisher(NUM_CPS_PUBLISHERS);
    }
    log.info("Start CloudPubSubSinkTask");
  }

  @Override
  public void put(Collection<SinkRecord> sinkRecords) {
    log.debug("Received " + sinkRecords.size() + " messages to send to CPS.");
    PubsubMessage.Builder builder = PubsubMessage.newBuilder();
    for (SinkRecord record : sinkRecords) {
      log.trace("Received record: " + record.toString());
      Map<String, String> attributes = new HashMap<>();
      ByteString value = handleValue(record.valueSchema(), record.value(), attributes);
      // Get the total number of bytes in this message.
      int messageSize = value.size(); // Assumes the topic name is in ASCII.
      if (record.key() != null) {
        attributes.put(ConnectorUtils.CPS_MESSAGE_KEY_ATTRIBUTE, record.key().toString());
      }
      for (String key : attributes.keySet()) {
        messageSize+= key.getBytes().length + attributes.get(key).getBytes().length;
      }
      PubsubMessage message = builder.setData(value).putAllAttributes(attributes).build();
      // Get a map containing all the unpublished messages per partition for this topic.
      Map<Integer, UnpublishedMessagesForPartition> unpublishedMessagesForTopic =
          allUnpublishedMessages.get(record.topic());
      if (unpublishedMessagesForTopic == null) {
        unpublishedMessagesForTopic = new HashMap<>();
        allUnpublishedMessages.put(record.topic(), unpublishedMessagesForTopic);
      }
      // Get the object containing the unpublished messages for the
      // specific topic and partition this SinkRecord is associated with.
      UnpublishedMessagesForPartition unpublishedMessages =
          unpublishedMessagesForTopic.get(record.kafkaPartition());
      if (unpublishedMessages == null) {
        unpublishedMessages = new UnpublishedMessagesForPartition();
        unpublishedMessagesForTopic.put(record.kafkaPartition(), unpublishedMessages);
      }
      int newUnpublishedSize = unpublishedMessages.size + messageSize;
      // Publish messages in this partition if the total number of bytes goes over limit.
      if (newUnpublishedSize > CPS_MAX_REQUEST_SIZE) {
        publishMessagesForPartition(
            record.topic(), record.kafkaPartition(), unpublishedMessages.messages);
        newUnpublishedSize = messageSize;
      }
      unpublishedMessages.size = newUnpublishedSize;
      unpublishedMessages.messages.add(message);
      // If the number of messages in this partition is greater than the batch size, then publish.
      if (unpublishedMessages.messages.size() >= maxBufferSize) {
        publishMessagesForPartition(
            record.topic(), record.kafkaPartition(), unpublishedMessages.messages);
      }
    }
  }

  private ByteString handleValue(Schema schema, Object value,  Map<String, String> attributes) {
    if (schema == null) {
        String str = value.toString();
        return ByteString.copyFromUtf8(str);
    }
    Schema.Type t = schema.type();
    switch (t) {
      case INT8:
        byte b = (Byte) value;
        byte[] arr = {b};
        return ByteString.copyFrom(arr);
      case INT16:
        ByteBuffer shortBuf = ByteBuffer.allocate(2);
        shortBuf.putShort((Short) value);
        return ByteString.copyFrom(shortBuf);
      case INT32:
        ByteBuffer intBuf = ByteBuffer.allocate(4);
        intBuf.putInt((Integer) value);
        return ByteString.copyFrom(intBuf);
      case INT64:
        ByteBuffer longBuf = ByteBuffer.allocate(8);
        longBuf.putLong((Long) value);
        return ByteString.copyFrom(longBuf);
      case FLOAT32:
        ByteBuffer floatBuf = ByteBuffer.allocate(4);
        floatBuf.putFloat((Float) value);
        return ByteString.copyFrom(floatBuf);
      case FLOAT64:
        ByteBuffer doubleBuf = ByteBuffer.allocate(8);
        doubleBuf.putDouble((Double) value);
        return ByteString.copyFrom(doubleBuf);
      case BOOLEAN:
        byte bool = (byte)((Boolean) value?1:0);
        byte[] boolArr = {bool};
        return ByteString.copyFrom(boolArr);
      case STRING:
        String str = (String) value;
        return ByteString.copyFromUtf8(str);
      case BYTES:
        if (value instanceof ByteString) {
          return (ByteString) value;
        } else if (value instanceof byte[]) {
          return ByteString.copyFrom((byte[]) value);
        } else if (value instanceof ByteBuffer) {
          return ByteString.copyFrom((ByteBuffer) value);
        } else {
          throw new DataException("Unexpected value class with BYTES schema type.");
        }
      case STRUCT:
        Struct struct = (Struct) value;
        ByteString msgBody = null;
        for (Field f : schema.fields()) {
          Object val = struct.get(f);
          if (val == null) {
            throw new DataException("Struct message body does not support Map or Struct types.");
          }
          if (f.name().equals(messageBodyName)) {
            Schema bodySchema = f.schema();
            msgBody = handleValue(bodySchema, val, null);
          } else {
            f.name();
            attributes.put(f.name(), val.toString());
          }
        }
        if (msgBody != null) {
          return msgBody;
        } else {
          return ByteString.EMPTY;
        }
      case MAP:
        Map<Object, Object> map = (Map<Object, Object>) value;
        Set<Object> keys = map.keySet();
        ByteString mapBody = null;
        for (Object key : keys) {
          if (key.equals(messageBodyName)) {
            mapBody = ByteString.copyFromUtf8(map.get(key).toString());
          } else {
            attributes.put(key.toString(), map.get(key).toString());
          }
        }
        if (mapBody != null) {
          return mapBody;
        } else {
          return ByteString.EMPTY;
        }
      case ARRAY:
        Schema.Type arrType = schema.valueSchema().type();
        if (arrType == Type.MAP || arrType == Type.STRUCT) {
          throw new DataException("Array type does not support Map or Struct types.");
        }
        ByteString out = ByteString.EMPTY;
        Object[] objArr = (Object[]) value;
        for (Object o : objArr) {
          out = out.concat(handleValue(schema.valueSchema(), o, null));
        }
        return out;
    }
    return ByteString.EMPTY;
  }


  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> partitionOffsets) {
    try {
      log.debug("Flushing...");
      // Publish all messages that have not been published yet.
      for (Map.Entry<String, Map<Integer, UnpublishedMessagesForPartition>> entry :
              allUnpublishedMessages.entrySet()) {
        for (Map.Entry<Integer, UnpublishedMessagesForPartition> innerEntry :
                entry.getValue().entrySet()) {
          publishMessagesForPartition(
                  entry.getKey(), innerEntry.getKey(), innerEntry.getValue().messages);
        }
      }
      allUnpublishedMessages.clear();
      // Process results of all the outstanding futures specified by each TopicPartition.
      for (Map.Entry<TopicPartition, OffsetAndMetadata> partitionOffset :
              partitionOffsets.entrySet()) {
        log.trace("Received flush for partition " + partitionOffset.getKey().toString());
        Map<Integer, OutstandingFuturesForPartition> outstandingFuturesForTopic =
                allOutstandingFutures.get(partitionOffset.getKey().topic());
        if (outstandingFuturesForTopic == null) {
          continue;
        }
        OutstandingFuturesForPartition outstandingFutures =
                outstandingFuturesForTopic.get(partitionOffset.getKey().partition());
        if (outstandingFutures == null) {
          continue;
        }
        try {
          for (ListenableFuture<PublishResponse> publishFuture : outstandingFutures.futures) {
            publishFuture.get();
          }
        } catch (Exception e) {
          log.error("Cleared outstanding");
          // Marium : Done to avoid multiple retries in case of Goaway.
       //  System.out.println(  allOutstandingFutures.keySet().);
          allOutstandingFutures.clear();
          log.error("Exception", e);
         // throw new RuntimeException(e);
        }
      }
      allOutstandingFutures.clear();
    } catch (Exception e) {
      log.error(" Exception in flush", e);
      throw e;
    }
  }


  /** Publish all the messages in a partition and store the Future's for each publish request. */
  private void publishMessagesForPartition(
      String topic, Integer partition, List<PubsubMessage> messages) {
    try {
      // Get a map containing all futures per partition for the passed in topic.
      Map<Integer, OutstandingFuturesForPartition> outstandingFuturesForTopic =
              allOutstandingFutures.get(topic);
      if (outstandingFuturesForTopic == null) {
        outstandingFuturesForTopic = new HashMap<>();
        allOutstandingFutures.put(topic, outstandingFuturesForTopic);
      }
      // Get the object containing the outstanding futures for this topic and partition..
      OutstandingFuturesForPartition outstandingFutures = outstandingFuturesForTopic.get(partition);
      if (outstandingFutures == null) {
        outstandingFutures = new OutstandingFuturesForPartition();
        outstandingFuturesForTopic.put(partition, outstandingFutures);
      }
      int startIndex = 0;
      int endIndex = Math.min(CPS_MAX_MESSAGES_PER_REQUEST, messages.size());
      PublishRequest.Builder builder = PublishRequest.newBuilder();
      // Publish all the messages for this partition in batches.
      while (startIndex < messages.size()) {
        PublishRequest request =
                builder.setTopic(cpsTopic).addAllMessages(messages.subList(startIndex, endIndex)).build();
        builder.clear();
        log.debug("Publishing: " + (endIndex - startIndex) + " messages");
        outstandingFutures.futures.add(publisher.publish(request));
        startIndex = endIndex;
        endIndex = Math.min(endIndex + CPS_MAX_MESSAGES_PER_REQUEST, messages.size());
      }
      messages.clear();
      log.debug("After clear:");
    } catch ( Exception e) {
      log.error(" Exception in publishMessagesForPartition ", e);
      throw e;
    }
  }

  @Override
  public void stop() {}
}
