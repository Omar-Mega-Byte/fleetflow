package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.lifecycle.LifecycleHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferExpiryCommit {
    private final JdbcTemplate jdbc;
    private final LifecycleHistory history;
    private final RecoveryQueue recovery;

    OfferExpiryCommit(JdbcTemplate jdbc, LifecycleHistory history, RecoveryQueue recovery) {
        this.jdbc = jdbc;
        this.history = history;
        this.recovery = recovery;
    }

    @Transactional
    public boolean expire(UUID offerId) {
        jdbc.execute("set local lock_timeout = '2s'");
        var offers = jdbc.query("""
                select driver_id, status, expires_at from driver_assignments
                where id = ? for update skip locked
                """, (rs, row) -> new Offer((UUID) rs.getObject("driver_id"),
                rs.getString("status"), rs.getTimestamp("expires_at").toInstant()), offerId);
        if (offers.isEmpty()) return false;
        Offer offer = offers.getFirst();
        if (!"OFFERED".equals(offer.status()) || dbTime().isBefore(offer.expiresAt())) return false;

        var ended = jdbc.query("""
                update driver_assignments set status = 'TIMED_OUT', responded_at = clock_timestamp(),
                    expiry_retry_at = NULL
                where id = ? and status = 'OFFERED' and expires_at <= clock_timestamp()
                returning responded_at
                """, (rs, row) -> rs.getTimestamp("responded_at").toInstant(), offerId);
        if (ended.size() != 1) throw new IllegalStateException("Locked offer changed during expiry");
        var released = jdbc.query("""
                update drivers set operational_status = 'AVAILABLE', version = version + 1,
                    updated_at = clock_timestamp()
                where id = ? and operational_status = 'RESERVED'
                returning version, updated_at
                """, (rs, row) -> new DriverChange(rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant()), offer.driverId());
        if (released.size() != 1) throw new IllegalStateException("Expired offer reservation is inconsistent");
        DriverChange change = released.getFirst();
        jdbc.update("""
                insert into driver_status_history
                (id, driver_id, actor_id, source, from_status, to_status, changed_at)
                values (?, ?, NULL, 'SYSTEM', 'RESERVED', 'AVAILABLE', ?)
                """, UUID.randomUUID(), offer.driverId(), Timestamp.from(change.at()));
        history.driverSystem(offer.driverId(), change.version(), "RESERVED", "AVAILABLE",
                "OFFER_TIMED_OUT", change.at());
        UUID orderId = jdbc.queryForObject("select order_id from driver_assignments where id = ?",
                UUID.class, offerId);
        recovery.schedule(orderId, "TIMED_OUT", 0);
        return true;
    }

    private Instant dbTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }
    private record Offer(UUID driverId, String status, Instant expiresAt) { }
    private record DriverChange(long version, Instant at) { }
}
