package com.bench;

import org.HdrHistogram.Recorder;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {

    static final short BROKER_COUNT = 1;

    // ---

    static final short TOPICS_COUNT = 10;
    static final short PARTITIONS_PER_TOPIC = 10;
    static final int MSGS_PER_PARTITION = 50000 * BROKER_COUNT;
    static final short MESSAGE_SIZE_BYTES = 1024;
    static final String MAX_PULL_BATCH_SIZE = "500";
    static final short POLL_MAX_WAIT_MS = 1000;
    static final String PRODUCER_BATCH_SIZE = "16384";
    static final String LINGER = "0";
    static final short REPLICAS = BROKER_COUNT;
    static final String ISR = REPLICAS > 2 ? "2" : "1";

    static final String bootstrapServers = IntStream.range(0, BROKER_COUNT)
        .mapToObj(i -> "localhost:" + (19092 + i))
        .collect(Collectors.joining(","));

    static final List<String> topicsList = IntStream.range(0, TOPICS_COUNT)
        .mapToObj(i -> "benchtopic" + i)
        .collect(Collectors.toList());

    static final List<Integer> partitionsList = IntStream.range(0, PARTITIONS_PER_TOPIC).boxed().collect(Collectors.toList());

    static final Properties prodProps = new Properties();
    static final Properties consProps = new Properties();
    static final Properties adminProps = new Properties();
    static {
        prodProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        prodProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        prodProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        prodProps.put(ProducerConfig.ACKS_CONFIG, "all");
        prodProps.put(ProducerConfig.BATCH_SIZE_CONFIG, PRODUCER_BATCH_SIZE);
        prodProps.put(ProducerConfig.LINGER_MS_CONFIG, LINGER);

        consProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_PULL_BATCH_SIZE);

        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    }

    static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    static final int totalClients = PARTITIONS_PER_TOPIC * TOPICS_COUNT * 2;
    static final CountDownLatch latch = new CountDownLatch(totalClients);
    static final byte[] messagePayload = new byte[MESSAGE_SIZE_BYTES];
    static {
        new SecureRandom().nextBytes(messagePayload);
    }
    static final Recorder recorder = new Recorder(1, 60_000_000_000L, 2);
    static final int totalMessages = MSGS_PER_PARTITION * PARTITIONS_PER_TOPIC * TOPICS_COUNT;
    static final AtomicLong throughputStart = new AtomicLong(0);

    public static void main(String[] args) {
        System.out.println("Creating topics...");

        try (AdminClient admin = AdminClient.create(adminProps)) {
            List<NewTopic> newTopics = topicsList.stream()
                .map(t -> new NewTopic(t, PARTITIONS_PER_TOPIC, REPLICAS)
                .configs(Map.of(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, ISR)))
                .collect(Collectors.toList());
            admin.createTopics(newTopics).all().get();
        } catch (Exception e) {
            System.err.println("Topic creation error: " + e.getMessage());
        }

        topicsList.stream().forEach(topic -> {
            Properties p = new Properties();
            p.putAll(consProps);
            p.put(ConsumerConfig.GROUP_ID_CONFIG, topic + "-group");
            partitionsList.forEach(partition -> {
                executor.submit(() -> {
                    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p)) {
                        consumer.assign(List.of(new TopicPartition(topic, partition)));
                        int polled = 0;
                        while (polled < MSGS_PER_PARTITION) {
                            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(POLL_MAX_WAIT_MS));
                            polled += records.count();
                            for (ConsumerRecord<byte[], byte[]> rec : records) {
                                var tsHeader = rec.headers().lastHeader("ts");
                                recorder.recordValue(System.nanoTime() - Long.parseLong(new String(tsHeader.value())));
                            }
                            consumer.commitAsync();
                        }
                    } catch (Exception e) {
                        System.err.println("Consumer error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            });
        });

        topicsList.stream().forEach(topic -> {
            partitionsList.forEach(partition -> {
                executor.submit(() -> {
                    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(prodProps)) {
                        throughputStart.compareAndSet(0, System.currentTimeMillis());
                        for (int i = 0; i < MSGS_PER_PARTITION; i++) {
                            RecordHeaders headers = new RecordHeaders();
                            headers.add("ts", String.valueOf(System.nanoTime()).getBytes());
                            ProducerRecord<byte[], byte[]> record = new ProducerRecord<byte[], byte[]>(topic, partition, null, messagePayload, headers);
                            producer.send(record);
                        }
                        producer.close();
                    } catch (Exception e) {
                        System.err.println("Producer error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            });
        });

        System.out.println("Running...");

        try {
            latch.await();
        } catch (Exception e) {
            System.err.println("Error awaiting latch...");
            System.exit(1);
        }

        double elapsedSeconds = (System.currentTimeMillis() - throughputStart.get()) / 1000.0;
        executor.shutdown();

        var hist = recorder.getIntervalHistogram();
        System.out.println("MEDIAN LATENCY (ms): " + hist.getValueAtPercentile(50) / 1_000_000.0);
        System.out.println("P95 LATENCY (ms): " + hist.getValueAtPercentile(95) / 1_000_000.0);
        System.out.println("P99 LATENCY (ms): " + hist.getValueAtPercentile(99) / 1_000_000.0);
        System.out.println("TOTAL MESSAGES PROCESSED: " + hist.getTotalCount() + " / " + totalMessages);

        double throughput = totalMessages / elapsedSeconds;
        System.out.println("Throughput: " + throughput + " messages/sec");
    }
}
