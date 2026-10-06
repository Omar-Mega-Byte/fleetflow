package com.fleetflow.location;

import com.fleetflow.driver.DriverLifecycleService.DriverBecameIneligible;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class DriverGeoHygiene {
    private final RedisLocationStore redis;
    private final MeterRegistry metrics;

    DriverGeoHygiene(RedisLocationStore redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void remove(DriverBecameIneligible event) {
        try {
            redis.removeFromGeo(event.driverId());
        } catch (DataAccessException unavailable) {
            // The PostgreSQL transition remains valid. Nearby always checks durable state.
            metrics.counter("fleetflow.location.operations", "operation", "redis", "outcome", "failure").increment();
        }
    }
}
