package com.fleetflow.events;

import java.time.Clock;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Broker DNS may disappear during an outage. Consumer construction must not kill the application. */
@Component
@ConditionalOnProperty(name = {"fleetflow.events.deferred-start", "fleetflow.outbox.enabled"}, havingValue = "true")
class EventListenerStartup {
    private static final Logger log = LoggerFactory.getLogger(EventListenerStartup.class);
    private final KafkaListenerEndpointRegistry registry;
    private final MeterRegistry metrics;
    private final Clock clock;
    private long nextAttempt;
    private long backoff = 1000;
    private volatile boolean closing;

    EventListenerStartup(KafkaListenerEndpointRegistry registry, MeterRegistry metrics, Clock clock) {
        this.registry = registry; this.metrics = metrics; this.clock = clock;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000, scheduler = "eventListenerScheduler")
    void startStoppedConsumers() {
        if (closing || clock.millis() < nextAttempt) return;
        boolean failed = false;
        for (var container : registry.getListenerContainers()) {
            if (closing || container.isRunning()) continue;
            try {
                container.start();
                metrics.counter("fleetflow.events.listener.start", "outcome", "started").increment();
            } catch (RuntimeException unavailable) {
                failed = true;
                metrics.counter("fleetflow.events.listener.start", "outcome", "unavailable").increment();
                // Reset a partially started container before a later attempt.
                try { container.stop(); }
                catch (RuntimeException stopFailure) {
                    metrics.counter("fleetflow.events.listener.start", "outcome", "stop_failed").increment();
                }
                log.atDebug().addKeyValue("failureType", unavailable.getClass().getSimpleName())
                        .log("Event consumer startup deferred; durable work remains in PostgreSQL");
            }
        }
        nextAttempt = clock.millis() + backoff;
        backoff = failed ? Math.min(60_000, backoff * 2) : 1000;
    }

    @PreDestroy
    void close() { closing = true; }
}
