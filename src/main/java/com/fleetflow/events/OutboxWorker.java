package com.fleetflow.events;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fleetflow.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
class OutboxWorker {
    private final OutboxPublisher publisher;
    private final OutboxStore store;
    private final Duration publishedRetention;
    private final int pruneBatchSize;

    OutboxWorker(OutboxPublisher publisher, OutboxStore store,
                 @Value("${fleetflow.outbox.published-retention:30d}") Duration publishedRetention,
                 @Value("${fleetflow.outbox.prune-batch-size:500}") int pruneBatchSize) {
        if (publishedRetention.compareTo(Duration.ofDays(14)) < 0 || pruneBatchSize < 1) {
            throw new IllegalArgumentException("Outbox retention must be at least 14 days and batch positive");
        }
        this.publisher = publisher;
        this.store = store;
        this.publishedRetention = publishedRetention;
        this.pruneBatchSize = pruneBatchSize;
    }

    @Scheduled(initialDelayString = "${fleetflow.outbox.sweep-interval-ms:5000}",
            fixedDelayString = "${fleetflow.outbox.sweep-interval-ms:5000}")
    void sweep() { publisher.sweep(); }

    @Scheduled(initialDelayString = "${fleetflow.outbox.prune-interval-ms:3600000}",
            fixedDelayString = "${fleetflow.outbox.prune-interval-ms:3600000}")
    void prune() { store.prunePublished(publishedRetention, pruneBatchSize); }
}
