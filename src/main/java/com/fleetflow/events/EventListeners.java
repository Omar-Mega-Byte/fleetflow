package com.fleetflow.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class EventListeners {
    private final EventProjector projector;

    EventListeners(EventProjector projector) { this.projector = projector; }

    @KafkaListener(topics = "${fleetflow.events.topic:fleetflow.domain-events.v1}",
            groupId = "fleetflow-notification-v1")
    void notification(ConsumerRecord<String, String> record) {
        try {
            projector.notification(record.value());
        } catch (InvalidEventException invalid) {
            projector.quarantine("notification", record.topic(), record.partition(), record.offset(),
                    invalid.code());
        }
    }

    @KafkaListener(topics = "${fleetflow.events.topic:fleetflow.domain-events.v1}",
            groupId = "fleetflow-analytics-v1")
    void analytics(ConsumerRecord<String, String> record) {
        try {
            projector.analytics(record.value());
        } catch (InvalidEventException invalid) {
            projector.quarantine("analytics", record.topic(), record.partition(), record.offset(),
                    invalid.code());
        }
    }
}
