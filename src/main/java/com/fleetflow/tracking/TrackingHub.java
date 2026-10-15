package com.fleetflow.tracking;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fleetflow.api.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Single-process, bounded SSE fan-out. Periodic refresh repairs missed in-process signals. */
@Component
public class TrackingHub {
    private static final int MAX_STREAMS = 100;
    private static final int MAX_PER_ACTOR = 3;
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    private static final Duration SLOW_LIMIT = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final TrackingSnapshotReader reader;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_STREAMS), task -> {
                Thread thread = new Thread(task, "fleetflow-tracking-send");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    TrackingHub(TrackingSnapshotReader reader, Clock clock, MeterRegistry metrics) {
        this.reader = reader;
        this.clock = clock;
        this.metrics = metrics;
    }

    public SseEmitter open(UUID orderId, UUID actorId, Instant tokenExpiresAt) {
        Instant now = clock.instant();
        if (tokenExpiresAt == null || !tokenExpiresAt.isAfter(now)) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        long lifeMillis = Math.min(MAX_LIFETIME.toMillis(), Duration.between(now, tokenExpiresAt).toMillis());
        if (lifeMillis < 1) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        Session session = new Session(orderId, actorId, now.plusMillis(lifeMillis),
                new SseEmitter(lifeMillis));
        synchronized (this) {
            if (sessions.size() >= MAX_STREAMS || sessions.values().stream()
                    .filter(existing -> existing.actorId.equals(actorId)).count() >= MAX_PER_ACTOR) {
                connection("rejected");
                throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "TRACKING_LIMIT", "Too many tracking streams", 15L);
            }
            sessions.put(session.id, session);
        }
        connection("opened");
        session.emitter.onCompletion(() -> forget(session, "client_closed"));
        session.emitter.onTimeout(() -> close(session, "stream_timeout"));
        session.emitter.onError(failure -> forget(session, "send_failure"));
        try {
            synchronized (session) {
                TrackingSnapshotReader.Snapshot snapshot = reader.read(orderId, actorId);
                send(session, "snapshot", snapshot);
                session.last = snapshot;
                if (snapshot.terminal()) close(session, "terminal");
            }
            return session.emitter;
        } catch (IOException | RuntimeException failure) {
            close(session, "send_failure");
            throw failure instanceof RuntimeException runtime ? runtime
                    : new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "TRACKING_UNAVAILABLE",
                            "Tracking stream unavailable");
        }
    }

    public void driverMoved(UUID driverId) {
        if (sessions.isEmpty()) return;
        try {
            List<UUID> orders = reader.activeOrdersForDriver(driverId);
            orders.forEach(this::refreshOrder);
        } catch (DataAccessException unavailable) {
            // Tracking fan-out cannot undo an accepted location update.
        }
    }

    public void orderChangedAfterCommit(UUID orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { refreshOrder(orderId); }
            });
        } else {
            refreshOrder(orderId);
        }
    }

    public void refreshOrder(UUID orderId) {
        for (Session session : sessions.values()) {
            if (session.orderId.equals(orderId)) enqueue(session);
        }
    }

    @Scheduled(fixedDelay = 5000)
    void tick() {
        Instant now = clock.instant();
        for (Session session : sessions.values()) {
            if (!now.isBefore(session.endsAt)
                    || (session.pending.get() && session.pendingSince != null
                    && session.pendingSince.isBefore(now.minus(SLOW_LIMIT)))) {
                close(session, now.isBefore(session.endsAt) ? "slow_client_evicted" : "stream_expired");
            } else {
                enqueue(session);
            }
        }
    }

    public int activeStreams() { return sessions.size(); }

    private void enqueue(Session session) {
        if (session.closed.get() || !session.pending.compareAndSet(false, true)) return;
        session.pendingSince = clock.instant();
        try {
            sender.execute(() -> {
                try {
                    synchronized (session) {
                        if (session.closed.get()) return;
                        Instant now = clock.instant();
                        if (!now.isBefore(session.endsAt)) {
                            close(session, "stream_expired");
                            return;
                        }
                        TrackingSnapshotReader.Snapshot current = reader.read(session.orderId, session.actorId);
                        if (session.closed.get()) return;
                        if (!current.sameContent(session.last)) {
                            send(session, "tracking", current);
                            session.last = current;
                            if (current.terminal()) close(session, "terminal");
                        } else if (!session.lastSent.plus(HEARTBEAT).isAfter(now)) {
                            session.emitter.send(SseEmitter.event().comment("keepalive"));
                            session.lastSent = now;
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    close(session, "send_failure");
                } finally {
                    session.pendingSince = null;
                    session.pending.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            session.pending.set(false);
            close(session, "executor_rejected");
        }
    }

    private void send(Session session, String event, TrackingSnapshotReader.Snapshot snapshot) throws IOException {
        session.emitter.send(SseEmitter.event().id(Long.toString(++session.sequence))
                .name(event).data(snapshot));
        session.lastSent = clock.instant();
    }

    private void close(Session session, String outcome) {
        if (!forget(session, outcome)) return;
        session.emitter.complete();
    }

    private boolean forget(Session session, String outcome) {
        if (!session.closed.compareAndSet(false, true)) return false;
        sessions.remove(session.id, session);
        connection(outcome);
        return true;
    }

    private void connection(String outcome) {
        metrics.counter("fleetflow.tracking.connections", "outcome", outcome).increment();
    }

    @PreDestroy
    void stop() {
        sessions.values().forEach(session -> close(session, "shutdown"));
        sender.shutdownNow();
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final UUID orderId;
        final UUID actorId;
        final Instant endsAt;
        final SseEmitter emitter;
        final AtomicBoolean pending = new AtomicBoolean();
        volatile Instant pendingSince;
        final AtomicBoolean closed = new AtomicBoolean();
        TrackingSnapshotReader.Snapshot last;
        Instant lastSent = Instant.EPOCH;
        long sequence;

        Session(UUID orderId, UUID actorId, Instant endsAt, SseEmitter emitter) {
            this.orderId = orderId;
            this.actorId = actorId;
            this.endsAt = endsAt;
            this.emitter = emitter;
        }
    }
}
