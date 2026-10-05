package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssignmentCommit {
    private final JdbcTemplate jdbc;
    private final AssignmentProperties config;

    AssignmentCommit(JdbcTemplate jdbc, AssignmentProperties config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Transactional
    public AssignmentService.OfferReceipt offer(UUID orderId, UUID attemptId, UUID driverId,
                                                  UUID dispatcherId, Instant checkedAt) {
        lockTimeout();
        var orders = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString("status"), orderId);
        if (orders.isEmpty() || !"DISPATCHING".equals(orders.getFirst())) throw AssignmentService.conflict();
        if (jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, orderId) != 0) throw AssignmentService.conflict();
        var candidates = jdbc.query("""
                select c.id from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.id = ? and a.order_id = ? and a.outcome = 'RANKED'
                  and a.sequence = (select max(sequence) from dispatch_attempts where order_id = ?)
                  and c.driver_id = ? and c.reason = 'ELIGIBLE' and c.rank is not null
                """, (rs, row) -> (UUID) rs.getObject("id"), attemptId, orderId, orderId, driverId);
        if (candidates.isEmpty()) throw AssignmentService.conflict();
        if (dbTime().isAfter(checkedAt.plus(config.maxPreflightAge()))) {
            throw AssignmentService.conflict();
        }

        var changed = jdbc.query("""
                update drivers d set status = 'RESERVED', version = version + 1, updated_at = clock_timestamp()
                where d.id = ? and d.status = 'AVAILABLE' and d.active and not d.suspended
                  and exists (select 1 from actors a where a.id = d.actor_id and a.active
                    and exists (select 1 from actor_roles r where r.actor_id = a.id and r.role = 'DRIVER'))
                  and exists (select 1 from vehicles v where v.driver_id = d.id and v.active and v.capacity > 0)
                returning d.updated_at
                """, (rs, row) -> rs.getTimestamp("updated_at").toInstant(), driverId);
        if (changed.size() != 1) throw AssignmentService.conflict();
        Instant changedAt = changed.getFirst();
        Instant offeredAt = dbTime();
        if (offeredAt.isAfter(checkedAt.plus(config.maxPreflightAge()))) {
            throw AssignmentService.conflict();
        }
        UUID offerId = UUID.randomUUID();
        Instant expiresAt = offeredAt.plus(config.offerTtl());
        jdbc.update("""
                insert into driver_assignments (id, order_id, driver_id, attempt_id, candidate_id,
                  offered_by_actor_id, status, offered_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'OFFERED', ?, ?)
                """, offerId, orderId, driverId, attemptId, candidates.getFirst(), dispatcherId,
                Timestamp.from(offeredAt), Timestamp.from(expiresAt));
        jdbc.update("""
                insert into deliveries (id, order_id, sequence, status, created_at, updated_at)
                values (?, ?, 1, 'PENDING', ?, ?)
                """, UUID.randomUUID(), orderId, Timestamp.from(offeredAt), Timestamp.from(offeredAt));
        driverHistory(driverId, dispatcherId, "AVAILABLE", "RESERVED", changedAt);
        return new AssignmentService.OfferReceipt(offerId, "OFFERED", expiresAt);
    }

    @Transactional
    public AssignmentService.OfferReceipt respond(UUID offerId, UUID actorId, boolean accept) {
        lockTimeout();
        var offers = jdbc.query("""
                select a.order_id, a.driver_id, a.status, a.expires_at
                from driver_assignments a join drivers d on d.id = a.driver_id
                where a.id = ? and d.actor_id = ? for update of a
                """, (rs, row) -> new Offer((UUID) rs.getObject("order_id"),
                (UUID) rs.getObject("driver_id"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant()), offerId, actorId);
        if (offers.isEmpty()) throw AssignmentService.missing();
        Offer offer = offers.getFirst();
        if (!"OFFERED".equals(offer.status())) throw AssignmentService.conflict();
        if (!dbTime().isBefore(offer.expiresAt())) throw AssignmentService.expired();

        if (accept) {
            Instant driverAt = driverChange(offer.driverId(), "RESERVED", "DELIVERING");
            Instant orderAt = orderChange(offer.orderId(), "DISPATCHING", "DRIVER_ASSIGNED");
            int delivery = jdbc.update("""
                    update deliveries set status = 'ASSIGNED', accepted_assignment_id = ?,
                      updated_at = clock_timestamp()
                    where order_id = ? and status = 'PENDING' and accepted_assignment_id is null
                    """, offerId, offer.orderId());
            if (delivery != 1) throw AssignmentService.conflict();
            respondRow(offerId, actorId, "ACCEPTED");
            driverHistory(offer.driverId(), actorId, "RESERVED", "DELIVERING", driverAt);
            orderHistory(offer.orderId(), actorId, "DISPATCHING", "DRIVER_ASSIGNED", orderAt);
            return new AssignmentService.OfferReceipt(offerId, "ACCEPTED", offer.expiresAt());
        }
        respondRow(offerId, actorId, "REJECTED");
        Instant driverAt = driverChange(offer.driverId(), "RESERVED", "AVAILABLE");
        driverHistory(offer.driverId(), actorId, "RESERVED", "AVAILABLE", driverAt);
        return new AssignmentService.OfferReceipt(offerId, "REJECTED", offer.expiresAt());
    }

    private void respondRow(UUID offerId, UUID actorId, String status) {
        int count = jdbc.update("""
                update driver_assignments set status = ?, responded_at = clock_timestamp(),
                  responded_by_actor_id = ?
                where id = ? and status = 'OFFERED' and clock_timestamp() < expires_at
                """, status, actorId, offerId);
        if (count != 1) throw AssignmentService.expired();
    }
    private Instant driverChange(UUID driverId, String from, String to) {
        var rows = jdbc.query("""
                update drivers set status = ?, version = version + 1, updated_at = clock_timestamp()
                where id = ? and status = ? and active and not suspended
                returning updated_at
                """, (rs, row) -> rs.getTimestamp("updated_at").toInstant(), to, driverId, from);
        if (rows.size() != 1) throw AssignmentService.conflict();
        return rows.getFirst();
    }
    private Instant orderChange(UUID orderId, String from, String to) {
        var rows = jdbc.query("""
                update orders set status = ?, updated_at = clock_timestamp()
                where id = ? and status = ? returning updated_at
                """, (rs, row) -> rs.getTimestamp("updated_at").toInstant(), to, orderId, from);
        if (rows.size() != 1) throw AssignmentService.conflict();
        return rows.getFirst();
    }
    private void driverHistory(UUID driverId, UUID actorId, String from, String to, Instant at) {
        jdbc.update("""
                insert into driver_status_history (id, driver_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), driverId, actorId, from, to, Timestamp.from(at));
    }
    private void orderHistory(UUID orderId, UUID actorId, String from, String to, Instant at) {
        jdbc.update("""
                insert into order_status_history (id, order_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, actorId, from, to, Timestamp.from(at));
    }
    private Instant dbTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }
    private void lockTimeout() { jdbc.execute("set local lock_timeout = '2s'"); }
    private record Offer(UUID orderId, UUID driverId, String status, Instant expiresAt) { }
}
