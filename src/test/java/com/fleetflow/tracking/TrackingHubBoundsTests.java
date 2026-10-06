package com.fleetflow.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TrackingHubBoundsTests {
    @Test
    void stalledClientWorkIsEvictedWithoutGrowingPerClientBacklog() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
        TrackingSnapshotReader reader = mock(TrackingSnapshotReader.class);
        UUID order = UUID.randomUUID(), actor = UUID.randomUUID();
        TrackingSnapshotReader.Snapshot snapshot = new TrackingSnapshotReader.Snapshot(
                order, "DRIVER_ASSIGNED", 1, "ASSIGNED", clock.instant(), null, false);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(reader.read(any(), any())).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) return snapshot;
            entered.countDown();
            release.await();
            return snapshot;
        });
        TrackingHub hub = new TrackingHub(reader, clock);
        try {
            hub.open(order, actor, clock.instant().plusSeconds(60));
            for (int i = 0; i < 20; i++) hub.refreshOrder(order);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(reads).hasValue(2);
            assertThat(hub.activeStreams()).isEqualTo(1);
            clock.advanceSeconds(11);
            hub.tick();
            assertThat(hub.activeStreams()).isZero();
        } finally {
            release.countDown();
            hub.stop();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advanceSeconds(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
