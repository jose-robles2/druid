/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.pravega.client.segment.impl.Segment;
import io.pravega.client.stream.EventRead;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.ReinitializationRequiredException;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.client.stream.impl.EventPointerImpl;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.data.input.impl.ByteEntity;
import org.apache.druid.data.input.pravega.PravegaEventEntity;
import org.apache.druid.indexing.kafka.supervisor.PravegaSupervisorIOConfig;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.indexing.seekablestream.common.OrderedSequenceNumber;
import org.apache.druid.indexing.seekablestream.common.RecordSupplier;
import org.apache.druid.indexing.seekablestream.common.StreamException;
import org.apache.druid.indexing.seekablestream.common.StreamPartition;
import org.apache.druid.indexing.seekablestream.extension.KafkaConfigOverrides;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.metadata.DynamicConfigProvider;
import org.apache.druid.metadata.PasswordProvider;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PravegaEventSupplier implements RecordSupplier<String, ByteBuffer, ByteEntity>
{
  private final EventStreamReader<byte[]> consumer;
  private boolean closed;

  public PravegaEventSupplier(
      Map<String, Object> consumerProperties,
      ObjectMapper sortingMapper,
      KafkaConfigOverrides configOverrides
  )
  {
    //pulsar plugin would call getClient() to setup their pulsar client
    this(getKafkaConsumer(sortingMapper, consumerProperties, configOverrides));
  }

  @VisibleForTesting
  public PravegaEventSupplier(
      //KafkaConsumer<byte[], byte[]> consumer
      EventStreamReader<byte[]> consumer
  )
  {
    this.consumer = consumer;
  }

  // assign a set of stream partitions to the consumer, contains the partition IDs
  // we'd get all the segments from a reader group
  // also called from seekablestreamindextaskrunner.java
  // also called from seeakablestreamsupervisor -> assignRecordSupplierToPartitionIDs()
    // supervisor can grab latest and earliest offsets to be stored in druid metadata (as well as partitions)
  @Override
  public void assign(Set<StreamPartition<String>> streamPartitions)
  {
    wrapExceptions(() -> consumer.assign(streamPartitions
                                             .stream()
                                             .map(x -> new TopicPartition(x.getStream(), x.getPartitionId()))
                                             .collect(Collectors.toSet())));
  }

  // called from seekablestreamindextaskrunner.java
  @Override
  public void seek(StreamPartition<String> partition, String sequenceNumber) //was a long, now a string
  {
    wrapExceptions(() -> consumer.seek(
        new TopicPartition(partition.getStream(), partition.getPartitionId()),
        sequenceNumber
    ));
  }

  // called from RecordSupplierInputSource.java
  // called from Seekablestreamsupervisor
  @Override
  public void seekToEarliest(Set<StreamPartition<String>> partitions)
  {
    wrapExceptions(() -> consumer.seekToBeginning(partitions
                                                      .stream()
                                                      .map(e -> new TopicPartition(e.getStream(), e.getPartitionId()))
                                                      .collect(Collectors.toList())));
  }

  // called from RecordSupplierInputSource.java
  @Override
  public void seekToLatest(Set<StreamPartition<String>> partitions)
  {
    wrapExceptions(() -> consumer.seekToEnd(partitions
                                                .stream()
                                                .map(e -> new TopicPartition(e.getStream(), e.getPartitionId()))
                                                .collect(Collectors.toList())));
  }

  // druid might use this function to recognize which partitions exist?
  // for us: how do we get all segment names for a reader group OR a single stream(can be multiple)
    // for a reader group we want to grab stream(s), then associated segments, grab events, get partition IDs
  // called from Seekablestreamsupervisor
  @Override
  public Set<StreamPartition<String>> getAssignment()
  {
    return wrapExceptions(() -> consumer.assignment()
                                        .stream()
                                        .map(e -> new StreamPartition<>(e.topic(), e.partition()))
                                        .collect(Collectors.toSet()));
  }

  // called from recordsupplierinputsource -> return value is turned into an iterator
  @Nonnull
  @Override
  public List<OrderedPartitionableRecord<String, ByteBuffer, ByteEntity>> poll(long timeout)
  {
    // do we need a start() or init() to initialize our EventReader to be connected to pravega?
    List<OrderedPartitionableRecord<String, ByteBuffer, ByteEntity>> polledEvents = new ArrayList<>();

    try {
      // Think we need a for loop to read until no more events cuz if we call poll() outside in a for loop, we'd be returning different lists each time
      // do while event.getEvent() != null? or loop for x amount of time?
      // introduce a while with timeout 0 after the line below is executed so we can return a list of many
      EventRead<byte[]> event =  consumer.readNextEvent(timeout);

      if (event.getEvent() != null) {
        // Make private method accessible from package with reflection
        EventPointerImpl ptr = (EventPointerImpl) event.getEventPointer().asImpl();
        Method getSegmentMethod = EventPointerImpl.class.getDeclaredMethod("getSegment");
        getSegmentMethod.setAccessible(true);
        Segment segment = (Segment)getSegmentMethod.invoke(ptr);

        // Calling the constructor
        polledEvents.add(new OrderedPartitionableRecord<>(
                event.getEventPointer().getStream().getStreamName(),   //stream is our topic name
                segment.getScopedName(),                              // partition id [use reader group IDs instead]
                event.getEventPointer().toBytes(),                    // serialized offset rep. as bytes that contain partit id, offset , and length combinded cause we'll need length later
                ImmutableList.of(new ByteEntity((event.getEvent())))
        ));
      }
    } catch (ReinitializationRequiredException | NoSuchMethodException e) { //added exception for the getDeclaredmethod()
      //There are certain circumstances where the reader needs to be reinitialized
      //Not sure what to do yet, we dont want to ignore this
      e.printStackTrace();
    } catch (TruncatedDataException e) { //We'd want to skip to the next event for this exception
      e.printStackTrace();
    } catch (InvocationTargetException | IllegalAccessException e ) {
      throw new RuntimeException(e);
    }
    return polledEvents;
  }

  // could be equivalent to ReaderGroup.Config -> get ending stream cuts. patitionID should be string
  // called from Seekablestreamsupervisor
  @Override
  public Long getLatestSequenceNumber(StreamPartition<String> partition)
  {
    Long currPos = getPosition(partition);
    seekToLatest(Collections.singleton(partition));
    Long nextPos = getPosition(partition);
    seek(partition, currPos);
    return nextPos;
  }

  // could be equivalent to ReaderGroup.Config -> get starting stream cuts
  // called from Seekablestreamsupervisor
  @Override
  public Long getEarliestSequenceNumber(StreamPartition<String> partition)
  {
    Long currPos = getPosition(partition);
    seekToEarliest(Collections.singleton(partition));
    Long nextPos = getPosition(partition);
    seek(partition, currPos);
    return nextPos;
  }

  @Override
  public boolean isOffsetAvailable(StreamPartition<String> partition, OrderedSequenceNumber<Long> offset)
  {
    final Long earliestOffset = getEarliestSequenceNumber(partition);
    return earliestOffset != null
           && offset.isAvailableWithEarliest(KafkaSequenceNumber.of(earliestOffset));
  }

  // not called anywhere else except in the kafka/pravega indexing service
  @Override
  public Long getPosition(StreamPartition<String> partition)
  {
    return wrapExceptions(() -> consumer.position(new TopicPartition(
        partition.getStream(),
        partition.getPartitionId()
    )));
  }

  // returns set of unique stream partition ids
  // "how do we get all segment names from a single stream"
  // "druid calls getPartitionIDs" to create stream and partitionId key pairs to assign them to the record supplier
  // druid does this in prep. for multi threads
  // called from Seekablestreamsupervisor
  @Override
  public Set<String> getPartitionIds(String stream)
  {
    return wrapExceptions(() -> {
      List<PartitionInfo> partitions = consumer.partitionsFor(stream);
      if (partitions == null) {
        throw new ISE("Topic [%s] is not found in KafkaConsumer's list of topics", stream);
      }
      return partitions.stream().map(PartitionInfo::partition).collect(Collectors.toSet());
    });
  }

  // recordsupplierinputsource and seekablestreamsupervisor call close()
  @Override
  public void close()
  {
    if (closed) {
      return;
    }
    closed = true;
    consumer.close();
  }

  public static void addConsumerPropertiesFromConfig(
      Properties properties,
      ObjectMapper configMapper,
      Map<String, Object> consumerProperties
  )
  {
    // Extract passwords before SSL connection to Kafka
    for (Map.Entry<String, Object> entry : consumerProperties.entrySet()) {
      String propertyKey = entry.getKey();

      if (!PravegaSupervisorIOConfig.DRUID_DYNAMIC_CONFIG_PROVIDER_KEY.equals(propertyKey)) {
        if (propertyKey.equals(PravegaSupervisorIOConfig.TRUST_STORE_PASSWORD_KEY)
            || propertyKey.equals(PravegaSupervisorIOConfig.KEY_STORE_PASSWORD_KEY)
            || propertyKey.equals(PravegaSupervisorIOConfig.KEY_PASSWORD_KEY)) {
          PasswordProvider configPasswordProvider = configMapper.convertValue(
              entry.getValue(),
              PasswordProvider.class
          );
          properties.setProperty(propertyKey, configPasswordProvider.getPassword());
        } else {
          properties.setProperty(propertyKey, String.valueOf(entry.getValue()));
        }
      }
    }

    // Additional DynamicConfigProvider based extensible support for all consumer properties
    Object dynamicConfigProviderJson = consumerProperties.get(PravegaSupervisorIOConfig.DRUID_DYNAMIC_CONFIG_PROVIDER_KEY);
    if (dynamicConfigProviderJson != null) {
      DynamicConfigProvider dynamicConfigProvider = configMapper.convertValue(dynamicConfigProviderJson, DynamicConfigProvider.class);
      Map<String, String> dynamicConfig = dynamicConfigProvider.getConfig();
      for (Map.Entry<String, String> e : dynamicConfig.entrySet()) {
        properties.setProperty(e.getKey(), e.getValue());
      }
    }
  }

  private static Deserializer getKafkaDeserializer(Properties properties, String kafkaConfigKey, boolean isKey)
  {
    Deserializer deserializerObject;
    try {
      Class deserializerClass = Class.forName(properties.getProperty(
          kafkaConfigKey,
          ByteArrayDeserializer.class.getTypeName()
      ));
      Method deserializerMethod = deserializerClass.getMethod("deserialize", String.class, byte[].class);

      Type deserializerReturnType = deserializerMethod.getGenericReturnType();

      if (deserializerReturnType == byte[].class) {
        deserializerObject = (Deserializer) deserializerClass.getConstructor().newInstance();
      } else {
        throw new IllegalArgumentException("Kafka deserializers must return a byte array (byte[]), " +
                                           deserializerClass.getName() + " returns " +
                                           deserializerReturnType.getTypeName());
      }
    }
    catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new StreamException(e);
    }

    Map<String, Object> configs = new HashMap<>();
    for (String key : properties.stringPropertyNames()) {
      configs.put(key, properties.get(key));
    }

    deserializerObject.configure(configs, isKey);
    return deserializerObject;
  }

  public static KafkaConsumer<byte[], byte[]> getKafkaConsumer(
      ObjectMapper sortingMapper,
      Map<String, Object> consumerProperties,
      KafkaConfigOverrides configOverrides
  )
  {
    final Map<String, Object> consumerConfigs = KafkaConsumerConfigs.getConsumerProperties();
    final Properties props = new Properties();
    Map<String, Object> effectiveConsumerProperties;
    if (configOverrides != null) {
      effectiveConsumerProperties = configOverrides.overrideConfigs(consumerProperties);
    } else {
      effectiveConsumerProperties = consumerProperties;
    }
    addConsumerPropertiesFromConfig(
        props,
        sortingMapper,
        effectiveConsumerProperties
    );
    props.putIfAbsent("isolation.level", "read_committed");
    props.putIfAbsent("group.id", StringUtils.format("kafka-supervisor-%s", IdUtils.getRandomId()));
    props.putAll(consumerConfigs);

    ClassLoader currCtxCl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(PravegaEventSupplier.class.getClassLoader());
      Deserializer keyDeserializerObject = getKafkaDeserializer(props, "key.deserializer", true);
      Deserializer valueDeserializerObject = getKafkaDeserializer(props, "value.deserializer", false);

      return new KafkaConsumer<>(props, keyDeserializerObject, valueDeserializerObject);
    }
    finally {
      Thread.currentThread().setContextClassLoader(currCtxCl);
    }
  }

  private static <T> T wrapExceptions(Callable<T> callable)
  {
    try {
      return callable.call();
    }
    catch (Exception e) {
      throw new StreamException(e);
    }
  }

  private static void wrapExceptions(Runnable runnable)
  {
    wrapExceptions(() -> {
      runnable.run();
      return null;
    });
  }
}
