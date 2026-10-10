package com.fleetflow.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fleetflow.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
class OfferExpiryWorker {
    private final OfferExpiryService expiry;
    private final RecoveryService recovery;
    private final MeterRegistry metrics;

    OfferExpiryWorker(OfferExpiryService expiry, RecoveryService recovery, MeterRegistry metrics) {
        this.expiry = expiry;
        this.recovery = recovery;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "${fleetflow.recovery.sweep-interval-ms:5000}",
            fixedDelayString = "${fleetflow.recovery.sweep-interval-ms:5000}")
    void sweep() {
        int expired = expiry.sweep();
        metrics.counter("fleetflow.offers.expired").increment(expired);
        int claimed = recovery.sweep();
        metrics.counter("fleetflow.recovery.claimed").increment(claimed);
    }
}
