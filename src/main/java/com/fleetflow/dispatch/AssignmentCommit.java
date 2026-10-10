package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.events.OutboxEvents;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.zone.ZoneService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssignmentCommit {
    private final JdbcTemplate jdbc;
    private final AssignmentProperties config;
    private final LifecycleHistory lifecycle;
    private final RecoveryQueue recovery;
    private final OutboxEvents outbox;
    private final ZoneService zones;

    AssignmentCommit(JdbcTemplate jdbc, AssignmentProperties config, LifecycleHistory lifecycle,
                     RecoveryQueue recovery, OutboxEvents outbox, ZoneService zones) {
        this.jdbc = jdbc;
        this.config = config;
        this.lifecycle = lifecycle;
        this.recovery = recovery;
        this.outbox = outbox;
        this.zones = zones;
    }

    @Transactional
    public AssignmentService.OfferReceipt offer(UUID orderId, UUID attemptId, UUID driverId,
                                                  UUID dispatcherId, Instant checkedAt) {
        lockTimeout();
        var orders = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString("status"), orderId);
        if (orders.isEmpty() || !"DISPATCHING".equals(orders.getFirst())) throw AssignmentService.conflict();
        ZoneService.Decision coverage = zones.forLockedOrder(orderId);
        if (!coverage.supported()) throw ZoneService.unsupported();
        if (jdbc.queryForObject("select count(*) from driver_assignments where order_id = ? "
                        + "and status in ('OFFERED', 'ACCEPTED')",
                Integer.class, orderId) != 0) throw AssignmentService.conflict();
        var candidates = jdbc.query("""
                select c.id from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.id = ? and a.order_id = ? and a.outcome = 'RANKED'
                  and a.sequence = (select max(sequence) from dispatch_attempts where order_id = ?)
                  and c.driver_id = ? and c.reason = 'ELIGIBLE' and c.rank is not null
                """, (rs, row) -> (UUID) rs.getObject("id"), attemptId, orderId, orderId, driverId);
        if (candidates.isEmpty()) throw AssignmentService.conflict();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from driver_assignments where candidate_id = ?)",
                Boolean.class, candidates.getFirst()))) throw AssignmentService.conflict();
        if (dbTime().isAfter(checkedAt.plus(config.maxPreflightAge()))) {
            throw AssignmentService.conflict();
        }

        var changed = jdbc.query("""
                update drivers d set operational_status = 'RESERVED', version = version + 1, updated_at = clock_timestamp()
                where d.id = ? and d.operational_status = 'AVAILABLE' and d.active and not d.suspended
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
                  offered_by_actor_id, offered_source, status, offered_at, expires_at,
                  zone_mode_at_offer, zone_policy_version_at_offer, zone_id_at_offer)
                values (?, ?, ?, ?, ?, ?, ?, 'OFFERED', ?, ?, ?, ?, ?)
                """, offerId, orderId, driverId, attemptId, candidates.getFirst(), dispatcherId,
                dispatcherId == null ? "SYSTEM" : "ACTOR", Timestamp.from(offeredAt), Timestamp.from(expiresAt),
                coverage.mode(), coverage.policyVersion(), coverage.zoneId());
        var pending = jdbc.query("select id from deliveries where order_id = ? and status = 'PENDING'",
                (rs, row) -> (UUID) rs.getObject("id"), orderId);
        if (pending.isEmpty()) {
            UUID deliveryId = UUID.randomUUID();
            int sequence = jdbc.queryForObject(
                    "select coalesce(max(sequence), 0) + 1 from deliveries where order_id = ?",
                    Integer.class, orderId);
            jdbc.update("""
                    insert into deliveries (id, order_id, sequence, status, created_at, updated_at)
                    values (?, ?, ?, 'PENDING', ?, ?)
                    """, deliveryId, orderId, sequence, Timestamp.from(offeredAt), Timestamp.from(offeredAt));
            if (dispatcherId == null) lifecycle.deliverySystem(deliveryId, 0, null, "PENDING",
                    "ASSIGNMENT_OFFER", offeredAt);
            else lifecycle.delivery(deliveryId, 0, null, "PENDING", "ASSIGNMENT_OFFER", offeredAt, dispatcherId);
        }
        driverHistory(driverId, dispatcherId, "AVAILABLE", "RESERVED", changedAt);
        outbox.driverOfferCreated(orderId, offerId, offeredAt);
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
            var deliveries = jdbc.query("""
                    update deliveries set status = 'ASSIGNED', accepted_assignment_id = ?,
                      version = version + 1, updated_at = clock_timestamp()
                    where order_id = ? and status = 'PENDING' and accepted_assignment_id is null
                    returning id, version, updated_at
                    """, (rs, row) -> new DeliveryChange((UUID) rs.getObject("id"),
                    rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()),
                    offerId, offer.orderId());
            if (deliveries.size() != 1) throw AssignmentService.conflict();
            respondRow(offerId, actorId, "ACCEPTED");
            DeliveryChange delivery = deliveries.getFirst();
            lifecycle.delivery(delivery.id(), delivery.version(), "PENDING", "ASSIGNED",
                    "ASSIGNMENT_ACCEPT", delivery.at(), actorId);
            driverHistory(offer.driverId(), actorId, "RESERVED", "DELIVERING", driverAt);
            orderHistory(offer.orderId(), actorId, "DISPATCHING", "DRIVER_ASSIGNED", orderAt);
            recovery.resolve(offer.orderId(), "ACCEPTED");
            return new AssignmentService.OfferReceipt(offerId, "ACCEPTED", offer.expiresAt());
        }
        respondRow(offerId, actorId, "REJECTED");
        Instant driverAt = driverChange(offer.driverId(), "RESERVED", "AVAILABLE");
        driverHistory(offer.driverId(), actorId, "RESERVED", "AVAILABLE", driverAt);
        recovery.schedule(offer.orderId(), "REJECTED", 0);
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
                update drivers set operational_status = ?, version = version + 1, updated_at = clock_timestamp()
                where id = ? and operational_status = ? and active and not suspended
                returning updated_at
                """, (rs, row) -> rs.getTimestamp("updated_at").toInstant(), to, driverId, from);
        if (rows.size() != 1) throw AssignmentService.conflict();
        return rows.getFirst();
    }
    private Instant orderChange(UUID orderId, String from, String to) {
        var rows = jdbc.query("""
                update orders set status = ?, version = version + 1, updated_at = clock_timestamp()
                where id = ? and status = ? returning updated_at
                """, (rs, row) -> rs.getTimestamp("updated_at").toInstant(), to, orderId, from);
        if (rows.size() != 1) throw AssignmentService.conflict();
        return rows.getFirst();
    }
    private void driverHistory(UUID driverId, UUID actorId, String from, String to, Instant at) {
        jdbc.update("""
                insert into driver_status_history (id, driver_id, actor_id, source,
                    from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), driverId, actorId, actorId == null ? "SYSTEM" : "ACTOR",
                from, to, Timestamp.from(at));
        long version = jdbc.queryForObject("select version from drivers where id = ?", Long.class, driverId);
        if (actorId == null) lifecycle.driverSystem(driverId, version, from, to,
                "ASSIGNMENT_" + to, at);
        else lifecycle.driver(driverId, version, from, to, "ASSIGNMENT_" + to, at, actorId);
    }
    private void orderHistory(UUID orderId, UUID actorId, String from, String to, Instant at) {
        jdbc.update("""
                insert into order_status_history (id, order_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, actorId, from, to, Timestamp.from(at));
        long version = jdbc.queryForObject("select version from orders where id = ?", Long.class, orderId);
        lifecycle.order(orderId, version, from, to, "ASSIGNMENT_ACCEPT", at, actorId, null);
    }
    private Instant dbTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }
    private void lockTimeout() { jdbc.execute("set local lock_timeout = '2s'"); }
    private record Offer(UUID orderId, UUID driverId, String status, Instant expiresAt) { }
    private record DeliveryChange(UUID id, long version, Instant at) { }
}
