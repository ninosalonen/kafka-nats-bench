package com.bench;

import io.nats.client.*;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.HdrHistogram.Recorder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {

    static final String CONSUME_MODE = "pull";
    static final short SERVER_COUNT = 1;

    // ---

    static final short STREAMS_COUNT = 10;
    static final short SUBJECTS_PER_STREAM = 10;
    static final int MSGS_PER_SUBJECT = 50000 * SERVER_COUNT;
    static final short MESSAGE_SIZE_BYTES = 1024;
    static final short MAX_PULL_BATCH_SIZE = 500;
    static final short PULL_MAX_WAIT_MS = 1000;
    static final short REPLICAS = SERVER_COUNT;
    static final short MAX_ACK_PENDING = MAX_PULL_BATCH_SIZE * SUBJECTS_PER_STREAM;

    static final String serverUrls = IntStream.range(0, SERVER_COUNT)
        .mapToObj(i -> "nats://localhost:" + (4222 + i))
        .collect(Collectors.joining(","));

    static final List<String> streamsList = IntStream.range(0, STREAMS_COUNT)
        .mapToObj(i -> "benchstream" + i)
        .collect(Collectors.toList());

    static final Map<String, List<String>> streamSubjectMap = streamsList.stream()
        .collect(Collectors.toMap(
            stream -> stream,
            stream -> IntStream.range(0, SUBJECTS_PER_STREAM)
                .mapToObj(i -> stream + "." + i)
                .collect(Collectors.toList())
        ));

    static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    static final int totalClients = SUBJECTS_PER_STREAM * streamsList.size() * 2;
    static final CountDownLatch pullLatch = new CountDownLatch(totalClients);
    static final byte[] messagePayload = new byte[MESSAGE_SIZE_BYTES];
    static {
        new SecureRandom().nextBytes(messagePayload);
    }
    static final Recorder recorder = new Recorder(1, 60_000_000_000L, 2);
    static final int totalMessages = MSGS_PER_SUBJECT * SUBJECTS_PER_STREAM * STREAMS_COUNT;
    static final AtomicLong throughputStart = new AtomicLong(0);
    static final CountDownLatch pushLatch = new CountDownLatch(1);
    static final AtomicLong pushProcessed = new AtomicLong(totalMessages);

    static boolean isPull() {
        return CONSUME_MODE.equals("pull");
    }

    public static void main(String[] args) throws Exception {
        Map<String, JetStreamSubscription> pullSubPerStream = new HashMap<>();
        Connection nc = Nats.connect(serverUrls);
        JetStreamManagement jsm = nc.jetStreamManagement();
        JetStream js = nc.jetStream();

        System.out.println("Creating streams and consumers...");

        streamSubjectMap.entrySet().parallelStream().forEach(entry -> {
            String stream = entry.getKey();
            try {
                jsm.addStream(StreamConfiguration.builder()
                    .name(stream)
                    .storageType(StorageType.File)
                    .replicas(REPLICAS)
                    .subjects(entry.getValue())
                    .duplicateWindow(Duration.ofSeconds(0))
                    .build());

                if(isPull()) {
                    var opts = PullSubscribeOptions.builder()
                        .durable(stream + "-durable")
                        .configuration(ConsumerConfiguration.builder()
                            .maxAckPending(MAX_ACK_PENDING)
                            .ackPolicy(AckPolicy.Explicit)
                            .build())
                        .stream(stream)
                        .build();

                    JetStreamSubscription jsSub = js.subscribe(null, opts);
                    synchronized (pullSubPerStream) {
                        pullSubPerStream.put(stream, jsSub);
                    }
                } else {
                    ConsumerConfiguration cc = ConsumerConfiguration.builder()
                        .durable(stream + "-durable")
                        .deliverSubject(stream + "-deliver")
                        .deliverGroup(stream + "-group")
                        .ackPolicy(AckPolicy.Explicit)
                        .build();

                    jsm.addOrUpdateConsumer(stream, cc);
                    
                    Dispatcher dispatcher = nc.createDispatcher();
                    for (String subject : entry.getValue()) {
                        executor.submit(() -> {
                            try {
                                dispatcher.subscribe(cc.getDeliverSubject(), cc.getDeliverGroup(), msg -> {
                                    recorder.recordValue(System.nanoTime() - Long.parseLong(msg.getHeaders().getFirst("ts")));
                                    msg.ack();
                        
                                    if(pushProcessed.decrementAndGet() == 0) {
                                        pushLatch.countDown();
                                    }
                                });
                            } catch (Exception e) {
                                System.err.println("Subscription error for subject " + subject + ": " + e.getMessage());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                System.err.println("Stream creation or stream subscription error:" + e.getMessage());
            }
        });

        if(isPull()) {
            streamSubjectMap.forEach((stream, subjects) -> {
                JetStreamSubscription sub = pullSubPerStream.get(stream);
                for (int i = 0; i < subjects.size(); i++) {
                    executor.submit(() -> {
                        while (true) {
                            List<Message> messages = sub.fetch(MAX_PULL_BATCH_SIZE, Duration.ofMillis(PULL_MAX_WAIT_MS));
                            if (messages.isEmpty()) break;
            
                            for (Message m : messages) {
                                recorder.recordValue(System.nanoTime() - Long.parseLong(m.getHeaders().getFirst("ts")));
                                m.ack();
                            }
                        }
                        pullLatch.countDown();
                    });
                }
            });
        }

        streamSubjectMap.values().forEach(subjects -> {
            subjects.forEach(subject -> {
                executor.submit(() -> {
                    throughputStart.compareAndSet(0, System.currentTimeMillis());
                    for (int j = 0; j < MSGS_PER_SUBJECT; j++) {
                        Headers headers = new Headers()
                            .add("ts", String.valueOf(System.nanoTime()));

                        Message msg = NatsMessage.builder()
                            .subject(subject)
                            .data(messagePayload)
                            .headers(headers)
                            .build();

                        try {
                            js.publish(msg);
                        } catch (Exception e ){
                            System.err.println("Publish failed: " + e.getMessage());
                        }
                    }
                    pullLatch.countDown();
                });
            });
        });

        System.out.println("Running...");

        if(isPull()) {
            pullLatch.await();
        } else {
            pushLatch.await();
        }
        double elapsedSeconds = (System.currentTimeMillis() - throughputStart.get()) / 1000.0;
        executor.shutdown();
        nc.close();

        var hist = recorder.getIntervalHistogram();
        System.out.println("MEDIAN LATENCY (ms): " + hist.getValueAtPercentile(50) / 1_000_000.0);
        System.out.println("P95 LATENCY (ms): " + hist.getValueAtPercentile(95) / 1_000_000.0);
        System.out.println("P99 LATENCY (ms): " + hist.getValueAtPercentile(99) / 1_000_000.0);
        System.out.println("TOTAL MESSAGES PROCESSED: " + hist.getTotalCount() + " / " + totalMessages);

        double throughput = totalMessages / elapsedSeconds;
        System.out.println("Throughput: " + throughput + " messages/sec");
    }
}
