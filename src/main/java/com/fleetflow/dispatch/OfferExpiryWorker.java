package com.fleetflow.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fleetflow.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
class OfferExpiryWorker {
    private final OfferExpiryService expiry;
    private final RecoveryService recovery;

    OfferExpiryWorker(OfferExpiryService expiry, RecoveryService recovery) {
        this.expiry = expiry;
        this.recovery = recovery;
    }

    @Scheduled(initialDelayString = "${fleetflow.recovery.sweep-interval-ms:5000}",
            fixedDelayString = "${fleetflow.recovery.sweep-interval-ms:5000}")
    void sweep() {
        expiry.sweep();
        recovery.sweep();
    }
}
