package com.fleetflow.events;

import java.time.Duration;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.KafkaException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class EventKafkaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(EventKafkaConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = {"fleetflow.events.deferred-start", "fleetflow.outbox.enabled"}, havingValue = "true")
    ThreadPoolTaskScheduler eventListenerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("event-listener-start-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = {"fleetflow.events.deferred-start", "fleetflow.outbox.enabled"}, havingValue = "true")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("fleetflow-worker-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    NewTopic domainTopic(@Value("${fleetflow.events.topic:fleetflow.domain-events.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    DefaultErrorHandler retryDatabaseFailures(EventProjector projector, MeterRegistry metrics) {
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            String group = consumer.groupMetadata().groupId();
            String name = switch (group) {
                case "fleetflow-notification-v1" -> "notification";
                case "fleetflow-analytics-v1" -> "analytics";
                default -> throw new IllegalStateException("Unknown event consumer");
            };
            projector.quarantineProjection(name, record.topic(), record.partition(), record.offset(),
                    (String) record.value());
        };
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.setLogLevel(KafkaException.Level.DEBUG);
        handler.setRetryListeners(new RetryListener() {
            @Override public void failedDelivery(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                                                  Exception failure, int attempt) {
                metrics.counter("fleetflow.events.delivery", "outcome", "failed_attempt").increment();
            }
            @Override public void recoveryFailed(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                                                  Exception original, Exception failure) {
                metrics.counter("fleetflow.events.delivery", "outcome", "recovery_failed").increment();
                log.atWarn().addKeyValue("event", "event.recovery_unavailable")
                        .addKeyValue("failureType", failure.getClass().getSimpleName())
                        .log("Event recovery did not commit; the offset remains retryable");
            }
        });
        return handler;
    }

    @Bean
    ProducerListener<Object, Object> safeProducerErrors() {
        return new ProducerListener<>() {
            @Override
            public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata,
                                Exception failure) {
                log.warn("Kafka publish failed for topic {}: {}", record.topic(),
                        failure.getClass().getSimpleName());
            }
        };
    }
}
