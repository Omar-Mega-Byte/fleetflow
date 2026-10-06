package com.fleetflow.lifecycle;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Guarded state writes plus append-only history. Call inside the owning command transaction. */
@Component
public class RecoveryTransitions {
    private final JdbcTemplate jdbc;
    private final LifecycleHistory history;

    public RecoveryTransitions(JdbcTemplate jdbc, LifecycleHistory history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    public Change driver(UUID id, String from, String to, UUID actor, String action) {
        var rows = jdbc.query("""
                update drivers set operational_status = ?, version = version + 1,
                    updated_at = clock_timestamp()
                where id = ? and operational_status = ?
                returning version, updated_at
                """, (rs, row) -> change(rs.getLong("version"), rs.getTimestamp("updated_at")), to, id, from);
        if (rows.size() != 1) throw conflict();
        Change change = rows.getFirst();
        jdbc.update("""
                insert into driver_status_history
                (id, driver_id, actor_id, source, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, actor, source(actor), from, to, Timestamp.from(change.at()));
        if (actor == null) history.driverSystem(id, change.version(), from, to, action, change.at());
        else history.driver(id, change.version(), from, to, action, change.at(), actor);
        return change;
    }

    public Change order(UUID id, String from, String to, UUID actor, String action, String reason) {
        var rows = jdbc.query("""
                update orders set status = ?, version = version + 1, updated_at = clock_timestamp(),
                    cancelled_at = case when ? = 'CANCELLED' then clock_timestamp() else cancelled_at end
                where id = ? and status = ? returning version, updated_at
                """, (rs, row) -> change(rs.getLong("version"), rs.getTimestamp("updated_at")),
                to, to, id, from);
        if (rows.size() != 1) throw conflict();
        Change change = rows.getFirst();
        jdbc.update("""
                insert into order_status_history
                (id, order_id, actor_id, source, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, actor, source(actor), from, to, Timestamp.from(change.at()));
        if (actor == null) history.orderSystem(id, change.version(), from, to, action, change.at(), reason);
        else history.order(id, change.version(), from, to, action, change.at(), actor, reason);
        return change;
    }

    public Change delivery(UUID id, String from, String to, UUID actor, String action, String failureReason) {
        var rows = jdbc.query("""
                update deliveries set status = ?, version = version + 1,
                    updated_at = clock_timestamp(), failure_reason = ?
                where id = ? and status = ? returning version, updated_at
                """, (rs, row) -> change(rs.getLong("version"), rs.getTimestamp("updated_at")),
                to, failureReason, id, from);
        if (rows.size() != 1) throw conflict();
        Change change = rows.getFirst();
        if (actor == null) history.deliverySystem(id, change.version(), from, to, action, change.at());
        else history.delivery(id, change.version(), from, to, action, change.at(), actor);
        return change;
    }

    private static Change change(long version, Timestamp at) { return new Change(version, at.toInstant()); }
    private static String source(UUID actor) { return actor == null ? "SYSTEM" : "ACTOR"; }
    private static ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "RECOVERY_CONFLICT", "State changed during recovery action");
    }
    public record Change(long version, Instant at) { }
}
