package com.fleetflow.events;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final Duration lease;
    private final Duration sendTimeout;
    private final int maxAttempts;
    private final MeterRegistry metrics;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public OutboxPublisher(OutboxStore store, KafkaTemplate<String, String> kafka,
                           MeterRegistry metrics,
                           @Value("${fleetflow.events.topic:fleetflow.domain-events.v1}") String topic,
                           @Value("${fleetflow.outbox.batch-size:50}") int batchSize,
                           @Value("${fleetflow.outbox.lease-duration:30s}") Duration lease,
                           @Value("${fleetflow.outbox.send-timeout:5s}") Duration sendTimeout,
                           @Value("${fleetflow.outbox.max-attempts:20}") int maxAttempts) {
        this.store = store;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.lease = lease;
        this.sendTimeout = sendTimeout;
        this.maxAttempts = maxAttempts;
        this.metrics = metrics;
        if (batchSize < 1 || maxAttempts < 1 || sendTimeout.isNegative() || sendTimeout.isZero()
                || lease.compareTo(sendTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("Outbox batch and retry limits must be positive; lease must cover send timeout");
        }
        Gauge.builder("fleetflow.outbox.pending", pending, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.outbox.failed", failed, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.outbox.oldest.seconds", oldestSeconds, AtomicLong::get).register(metrics);
    }

    /** One sweep uses no database locks during the broker call. */
    public int sweep() {
        int sent = 0;
        for (int i = 0; i < batchSize; i++) {
            var claims = store.claim(1, lease);
            if (claims.isEmpty()) break;
            OutboxStore.Claimed claim = claims.getFirst();
            try {
                kafka.send(topic, claim.aggregateId().toString(), claim.envelope())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (store.published(claim)) {
                    sent++;
                    metrics.counter("fleetflow.outbox.publish", "outcome", "published").increment();
                    log.atInfo().addKeyValue("event", "outbox.published")
                            .addKeyValue("eventId", claim.id())
                            .addKeyValue("orderId", claim.aggregateId())
                            .log("Outbox event published");
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                store.failed(claim, failure.getClass().getSimpleName(), maxAttempts);
                metrics.counter("fleetflow.outbox.publish", "outcome", "failed").increment();
                log.atWarn().addKeyValue("event", "outbox.publish_failed")
                        .addKeyValue("eventId", claim.id())
                        .addKeyValue("orderId", claim.aggregateId())
                        .addKeyValue("failureType", failure.getClass().getSimpleName())
                        .log("Outbox publish failed");
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        OutboxStore.Backlog backlog = store.backlog();
        pending.set(backlog.pending());
        failed.set(backlog.failed());
        oldestSeconds.set((long) backlog.oldestSeconds());
        return sent;
    }
}
