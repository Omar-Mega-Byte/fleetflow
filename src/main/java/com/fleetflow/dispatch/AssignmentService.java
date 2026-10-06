package com.fleetflow.dispatch;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.location.LocationService;
import com.fleetflow.security.ActorAccess;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final LocationService locations;
    private final AssignmentCommit commit;

    AssignmentService(JdbcTemplate jdbc, ActorAccess access, LocationService locations, AssignmentCommit commit) {
        this.jdbc = jdbc;
        this.access = access;
        this.locations = locations;
        this.commit = commit;
    }

    public OfferReceipt offer(UUID orderId, UUID attemptId, UUID driverId) {
        UUID dispatcherId = access.require(Role.DISPATCHER).id();
        return offerChecked(orderId, attemptId, driverId, dispatcherId);
    }

    OfferReceipt offerSystem(UUID orderId, UUID attemptId, UUID driverId) {
        return offerChecked(orderId, attemptId, driverId, null);
    }

    private OfferReceipt offerChecked(UUID orderId, UUID attemptId, UUID driverId, UUID dispatcherId) {
        if (attemptId == null || driverId == null) throw invalid();
        var pickup = jdbc.query("""
                select o.status, o.pickup_latitude, o.pickup_longitude, a.radius_meters
                from orders o join dispatch_attempts a on a.order_id = o.id
                  join dispatch_candidates c on c.attempt_id = a.id
                where o.id = ? and a.id = ? and c.driver_id = ? and c.reason = 'ELIGIBLE'
                  and c.rank is not null and a.outcome = 'RANKED'
                  and a.sequence = (select max(sequence) from dispatch_attempts where order_id = o.id)
                """, (rs, row) -> new Pickup(rs.getString("status"),
                rs.getBigDecimal("pickup_latitude").doubleValue(),
                rs.getBigDecimal("pickup_longitude").doubleValue(), rs.getInt("radius_meters")),
                orderId, attemptId, driverId);
        if (pickup.isEmpty() || !"DISPATCHING".equals(pickup.getFirst().status())) throw conflict();
        Pickup point = pickup.getFirst();
        Instant checkedAt = locations.requireOfferCandidate(driverId, point.latitude(), point.longitude(),
                point.radiusMeters());
        try {
            return commit.offer(orderId, attemptId, driverId, dispatcherId, checkedAt);
        } catch (DataAccessException failure) {
            if (contention(failure)) throw conflict();
            throw failure;
        }
    }

    public OfferReceipt respond(UUID offerId, boolean accept) {
        UUID actorId = access.require(Role.DRIVER).id();
        try {
            return commit.respond(offerId, actorId, accept);
        } catch (DataAccessException failure) {
            if (contention(failure)) throw conflict();
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public List<DriverOffer> myOffers(int limit) {
        UUID actorId = access.require(Role.DRIVER).id();
        requireLimit(limit);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from drivers where actor_id = ?)", Boolean.class, actorId))) {
            throw missing();
        }
        return jdbc.query("""
                select a.id, a.order_id, a.status, a.offered_at, a.expires_at,
                       o.pickup_line1, o.pickup_city, o.delivery_line1, o.delivery_city
                from driver_assignments a join drivers d on d.id = a.driver_id
                  join orders o on o.id = a.order_id
                where d.actor_id = ?
                order by a.offered_at desc, a.id desc limit ?
                """, (rs, row) -> new DriverOffer((UUID) rs.getObject("id"),
                (UUID) rs.getObject("order_id"), rs.getString("status"),
                rs.getTimestamp("offered_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("pickup_line1"), rs.getString("pickup_city"),
                rs.getString("delivery_line1"), rs.getString("delivery_city")), actorId, limit);
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> assignments(UUID orderId, int limit) {
        access.require(Role.DISPATCHER);
        requireLimit(limit);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from orders where id = ?)", Boolean.class, orderId))) {
            throw missing();
        }
        return jdbc.query("""
                select a.id, a.driver_id, a.attempt_id, c.rank, a.status,
                       a.offered_at, a.expires_at, a.responded_at
                from driver_assignments a join dispatch_candidates c on c.id = a.candidate_id
                where a.order_id = ? order by a.offered_at desc, a.id desc limit ?
                """, (rs, row) -> new AssignmentView((UUID) rs.getObject("id"),
                (UUID) rs.getObject("driver_id"), (UUID) rs.getObject("attempt_id"),
                rs.getInt("rank"), rs.getString("status"), rs.getTimestamp("offered_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), instant(rs.getTimestamp("responded_at"))),
                orderId, limit);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 20) throw invalid();
    }
    static ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "ASSIGNMENT_CONFLICT", "Assignment state conflicts with this action");
    }
    static ApiFailure expired() {
        return new ApiFailure(HttpStatus.CONFLICT, "OFFER_EXPIRED", "Offer deadline has passed");
    }
    static ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }
    private static ApiFailure invalid() {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid assignment request");
    }
    private static boolean contention(DataAccessException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sql && ("23505".equals(sql.getSQLState())
                    || "55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState())
                    || "40001".equals(sql.getSQLState()))) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private record Pickup(String status, double latitude, double longitude, int radiusMeters) { }
    public record OfferReceipt(UUID offerId, String status, Instant expiresAt) { }
    public record DriverOffer(UUID offerId, UUID orderId, String status, Instant offeredAt, Instant expiresAt,
                              String pickupLine1, String pickupCity, String deliveryLine1, String deliveryCity) { }
    public record AssignmentView(UUID offerId, UUID driverId, UUID attemptId, int rank, String status,
                                 Instant offeredAt, Instant expiresAt, Instant respondedAt) { }
    public record DriverOfferPage(List<DriverOffer> content) { }
    public record AssignmentPage(List<AssignmentView> content) { }
}
