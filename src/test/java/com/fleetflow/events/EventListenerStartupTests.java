package com.fleetflow.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class EventListenerStartupTests {
    @Test
    void failedConstructionBacksOffThenStartsOnceAndStopsRetryingOnShutdown() {
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var container = mock(MessageListenerContainer.class);
        var clock = mock(Clock.class);
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(clock.millis()).thenReturn(0L);
        doThrow(new IllegalStateException("synthetic sensitive failure text"))
                .doNothing().when(container).start();
        var metrics = new SimpleMeterRegistry();
        var starter = new EventListenerStartup(registry, metrics, clock);
        starter.startStoppedConsumers();
        starter.startStoppedConsumers();
        verify(container).start();
        verify(container).stop();
        when(clock.millis()).thenReturn(1000L);
        starter.startStoppedConsumers();
        verify(container, times(2)).start();
        when(container.isRunning()).thenReturn(true);
        when(clock.millis()).thenReturn(10_000L);
        starter.startStoppedConsumers();
        starter.close();
        when(container.isRunning()).thenReturn(false);
        starter.startStoppedConsumers();
        verify(container, times(2)).start();
        assertThat(metrics.get("fleetflow.events.listener.start").tag("outcome", "unavailable").counter().count()).isEqualTo(1);
        assertThat(metrics.get("fleetflow.events.listener.start").tag("outcome", "started").counter().count()).isEqualTo(1);
    }
}
