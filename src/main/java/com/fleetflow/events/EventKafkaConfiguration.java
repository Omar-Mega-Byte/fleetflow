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
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class EventKafkaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(EventKafkaConfiguration.class);

    @Bean
    NewTopic domainTopic(@Value("${fleetflow.events.topic:fleetflow.domain-events.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    DefaultErrorHandler retryDatabaseFailures() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
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
