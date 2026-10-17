package com.fleetflow.demo;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.lifecycle.RecoveryTransitions;
import com.fleetflow.zone.ZonePolicyHistory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
class DemoSeedService {
    static final String RESET_CONFIRMATION = "RESET FLEETFLOW DEMO";
    private static final String ID_NAMESPACE = "fleetflow-public-demo-day1-v1:";
    private static final String CUSTOMER_SUBJECT_DEFAULT = "fleetflow-demo-customer";
    private static final String MERCHANT_SUBJECT_DEFAULT = "fleetflow-demo-merchant";
    private static final String DRIVER_SUBJECT_DEFAULT = "fleetflow-demo-driver";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String issuer;
    private final String customerSubject;
    private final String merchantSubject;
    private final String driverSubject;
    private final String dispatcherSubject;
    private final String operatorSubject;
    private final RecoveryTransitions transitions;
    private final ZonePolicyHistory zoneHistory;
    private static final List<Persona> PERSONAS = List.of(
            new Persona("customer", "CUSTOMER", "Demo Customer"),
            new Persona("merchant", "MERCHANT", "Cairo Parcel Kitchen"),
            new Persona("driver", "DRIVER", "Demo Courier"),
            new Persona("dispatcher", "DISPATCHER", "Demo Dispatcher"),
            new Persona("operator", "ADMIN", "Demo Operator"));

    DemoSeedService(JdbcTemplate jdbc, Clock clock, RecoveryTransitions transitions, ZonePolicyHistory zoneHistory,
                    @Value("${fleetflow.demo.oidc-issuer:${spring.security.oauth2.resourceserver.jwt.issuer-uri:}}") String issuer,
                    @Value("${fleetflow.demo.customer-subject:" + CUSTOMER_SUBJECT_DEFAULT + "}") String customerSubject,
                    @Value("${fleetflow.demo.merchant-subject:" + MERCHANT_SUBJECT_DEFAULT + "}") String merchantSubject,
                    @Value("${fleetflow.demo.driver-subject:" + DRIVER_SUBJECT_DEFAULT + "}") String driverSubject,
                    @Value("${fleetflow.demo.dispatcher-subject:fleetflow-demo-dispatcher}") String dispatcherSubject,
                    @Value("${fleetflow.demo.operator-subject:fleetflow-demo-operator}") String operatorSubject) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.issuer = issuer;
        this.customerSubject = customerSubject;
        this.merchantSubject = merchantSubject;
        this.driverSubject = driverSubject;
        this.dispatcherSubject = dispatcherSubject;
        this.operatorSubject = operatorSubject;
        this.transitions = transitions;
        this.zoneHistory = zoneHistory;
    }

    @Transactional(readOnly = true)
    DemoView state() {
        long generation = generation();
        return view(generation, generation > 0 ? "READY" : "NOT_SEEDED");
    }

    @Transactional(readOnly = true)
    DriverRoute driverRoute(UUID actorId) {
        if (!id("actor:driver").equals(actorId) || !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from synthetic_demo_ownership
                  where resource_type = 'ACTOR' and resource_id = ?)
                """, Boolean.class, actorId))) {
            throw new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Synthetic movement is limited to the seeded demo driver");
        }
        long current = generation();
        var orders = jdbc.query("""
                select pickup_latitude, pickup_longitude, delivery_latitude, delivery_longitude
                from orders where id = ? and demo_generation = ?
                """, (rs, row) -> new RouteEndpoints(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)),
                id("order:" + current), current);
        if (orders.isEmpty()) {
            throw new ApiFailure(HttpStatus.CONFLICT, "DEMO_NOT_READY", "Prepare the synthetic dataset before using fixture positions");
        }
        RouteEndpoints route = orders.getFirst();
        var points = java.util.stream.IntStream.rangeClosed(0, 4).mapToObj(step -> {
            double fraction = step / 4.0;
            return new FixturePosition(BigDecimal.valueOf(route.pickupLat() + fraction * (route.deliveryLat() - route.pickupLat())).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(route.pickupLon() + fraction * (route.deliveryLon() - route.pickupLon())).setScale(6, RoundingMode.HALF_UP));
        }).toList();
        return new DriverRoute(current, points);
    }

    @Transactional
    DemoView seed(UUID actorId, String key, String reason) {
        validate(key, reason);
        long current = lockGeneration();
        String requestHash = hash("SEED\n" + reason.strip());
        DemoView replay = prior(actorId, "SEED", key, requestHash);
        if (replay != null) return replay;

        String result;
        if (current == 0) {
            long next = 1;
            ensureCommonResources(actorId);
            createBaselineOrder(next);
            updateGeneration(next, actorId);
            current = next;
            result = "SEEDED";
        } else {
            if (!baselineExists(current)) {
                throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_BASELINE_INCOMPLETE",
                        "Synthetic demo baseline is incomplete");
            }
            result = "ALREADY_SEEDED";
        }
        audit(actorId, "SEED", key, requestHash, reason.strip(), result, current);
        return view(current, result);
    }

    @Transactional
    DemoView reset(UUID actorId, String key, String confirmation, String reason) {
        validate(key, reason);
        if (!RESET_CONFIRMATION.equals(confirmation)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "DEMO_RESET_CONFIRMATION_REQUIRED",
                    "Reset confirmation does not match");
        }
        long previous = lockGeneration();
        String requestHash = hash("RESET\n" + confirmation + "\n" + reason.strip());
        DemoView replay = prior(actorId, "RESET", key, requestHash);
        if (replay != null) return replay;

        if (previous > 0) prepareReset(actorId, reason.strip());
        ensureCommonResources(actorId);
        long next = Math.max(1, previous + 1);
        createBaselineOrder(next);
        updateGeneration(next, actorId);
        audit(actorId, "RESET", key, requestHash, reason.strip(), "RESET", next);
        return view(next, "RESET");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(UUID actorId, String action, String key, String reason, String resultCode) {
        String cleanKey = key != null && key.matches("[A-Za-z0-9._~-]{1,128}") ? key : "invalid-key";
        String cleanReason = reason == null || reason.isBlank() ? "Unspecified operator action" : reason.strip();
        if (cleanReason.length() > 500) cleanReason = cleanReason.substring(0, 500);
        jdbc.update("""
                insert into synthetic_demo_failure_audit
                  (id, actor_id, action, idempotency_key, reason, result_code, generation, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actorId, action, cleanKey, cleanReason,
                safeFailureCode(resultCode), generation(), Timestamp.from(clock.instant()));
    }

    private DemoView prior(UUID actorId, String action, String key, String requestHash) {
        var rows = jdbc.query("""
                select request_hash, result_code, generation from synthetic_demo_audit
                where actor_id = ? and action = ? and idempotency_key = ?
                """, (rs, row) -> new Prior(rs.getString(1), rs.getString(2), rs.getLong(3)), actorId, action, key);
        if (rows.isEmpty()) return null;
        Prior existing = rows.getFirst();
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request");
        }
        return view(existing.generation(), existing.resultCode());
    }

    private void validate(String key, String reason) {
        if (issuer == null || issuer.isBlank()) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_ISSUER_UNCONFIGURED",
                    "Synthetic demo identity issuer is not configured");
        }
        if (key == null || !key.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid Idempotency-Key");
        }
        if (reason == null || reason.strip().isEmpty() || reason.strip().length() > 500) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason must have 1 to 500 characters");
        }
    }

    private long lockGeneration() {
        jdbc.execute("set local lock_timeout = '2s'");
        return jdbc.queryForObject("select generation from synthetic_demo_state where id = true for update",
                Long.class);
    }

    private long generation() {
        return jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class);
    }

    private void updateGeneration(long value, UUID actorId) {
        jdbc.update("update synthetic_demo_state set generation = ?, updated_at = ?, updated_by_actor_id = ? where id = true",
                value, Timestamp.from(clock.instant()), actorId);
    }

    private void ensureCommonResources(UUID actorId) {
        UUID customerActor = id("actor:customer");
        UUID merchantActor = id("actor:merchant");
        UUID driverActor = id("actor:driver");
        UUID customer = id("customer");
        UUID merchant = id("merchant");
        UUID branch = id("branch");
        UUID membership = id("membership");
        UUID driver = id("driver");
        UUID vehicle = id("vehicle");
        UUID zone = id("zone");
        var now = Timestamp.from(clock.instant());

        ensureActor(customerActor, customerSubject, "CUSTOMER", now);
        ensureActor(merchantActor, merchantSubject, "MERCHANT", now);
        ensureActor(driverActor, driverSubject, "DRIVER", now);
        ensureActor(id("actor:dispatcher"), dispatcherSubject, "DISPATCHER", now);
        ensureActor(id("actor:operator"), operatorSubject, "ADMIN", now);

        ensure("CUSTOMER", customer, "customers");
        jdbc.update("""
                insert into customers(id, actor_id, display_name, created_at, updated_at)
                values (?, ?, 'Demo Customer', ?, ?)
                on conflict(id) do update set display_name = excluded.display_name, updated_at = excluded.updated_at
                """, customer, customerActor, now, now);
        ensure("MERCHANT", merchant, "merchants");
        jdbc.update("""
                insert into merchants(id, name, active, created_at, updated_at)
                values (?, 'Cairo Parcel Kitchen', true, ?, ?)
                on conflict(id) do update set name = excluded.name, active = true, updated_at = excluded.updated_at
                """, merchant, now, now);
        ensure("BRANCH", branch, "merchant_branches");
        jdbc.update("""
                insert into merchant_branches(id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Downtown Branch', '48 Demo Avenue', 'Cairo', 30.044400, 31.235700, true, ?, ?)
                on conflict(id) do update set name = excluded.name, pickup_line1 = excluded.pickup_line1,
                  pickup_city = excluded.pickup_city, pickup_latitude = excluded.pickup_latitude,
                  pickup_longitude = excluded.pickup_longitude, active = true, updated_at = excluded.updated_at
                """, branch, merchant, now, now);
        ensure("MEMBERSHIP", membership, "merchant_staff_memberships");
        jdbc.update("""
                insert into merchant_staff_memberships(id, actor_id, branch_id, active, created_at)
                values (?, ?, ?, true, ?) on conflict(id) do update set active = true
                """, membership, merchantActor, branch, now);
        ensure("DRIVER", driver, "drivers");
        jdbc.update("""
                insert into drivers(id, actor_id, active, suspended, operational_status, version, created_at, updated_at)
                values (?, ?, true, false, 'OFFLINE', 0, ?, ?)
                on conflict(id) do update set active = true, suspended = false, updated_at = excluded.updated_at
                """, driver, driverActor, now, now);
        ensure("VEHICLE", vehicle, "vehicles");
        jdbc.update("""
                insert into vehicles(id, driver_id, type, capacity, active, created_at, updated_at)
                values (?, ?, 'BICYCLE', 10, true, ?, ?)
                on conflict(id) do update set active = true, updated_at = excluded.updated_at
                """, vehicle, driver, now, now);
        ensure("ZONE", zone, "delivery_zones");
        boolean zoneExisted = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from delivery_zones where id = ?)",
                Boolean.class, zone));
        int changedZone = jdbc.update("""
                insert into delivery_zones(id, branch_id, name, boundary, active, version, created_at, updated_at)
                values (?, ?, 'Central Demo Zone',
                  ST_Multi(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[31.2000,30.0000],[31.2800,30.0000],[31.2800,30.0800],[31.2000,30.0800],[31.2000,30.0000]]]}'))::geometry(MultiPolygon,4326),
                  true, 0, ?, ?)
                on conflict(id) do update set name = excluded.name, boundary = excluded.boundary,
                  active = true, version = delivery_zones.version + 1, updated_at = excluded.updated_at
                where delivery_zones.name is distinct from excluded.name or not delivery_zones.active
                  or not ST_Equals(delivery_zones.boundary, excluded.boundary)
                """, zone, branch, now, now);
        if (changedZone > 0) zoneHistory.record(branch, zone, actorId, zoneExisted ? "ZONE_UPDATED" : "ZONE_CREATED");
        if (jdbc.update("update merchant_branches set serviceability_mode = 'UNRESTRICTED' "
                + "where id = ? and serviceability_mode <> 'UNRESTRICTED'", branch) > 0)
            zoneHistory.record(branch, null, actorId, "MODE_CHANGED");
    }

    private void upsertActor(UUID actor, String subject, Timestamp now) {
        UUID linked = jdbc.query("select id from actors where issuer = ? and subject = ?",
                (rs, row) -> (UUID) rs.getObject(1), issuer, subject).stream().findFirst().orElse(null);
        if (linked != null && !linked.equals(actor)) collision();
        int changed = jdbc.update("""
                insert into actors(id, issuer, subject, active, created_at, updated_at)
                values (?, ?, ?, true, ?, ?)
                on conflict(id) do nothing
                """, actor, issuer, subject, now, now);
        if (changed == 0) {
            var rows = jdbc.query("select issuer, subject, active from actors where id = ?",
                    (rs, row) -> new ActorIdentity(rs.getString(1), rs.getString(2), rs.getBoolean(3)), actor);
            if (rows.isEmpty() || !issuer.equals(rows.getFirst().issuer())
                    || !subject.equals(rows.getFirst().subject()) || !rows.getFirst().active()) collision();
        }
    }

    private void ensureActor(UUID actor, String subject, String role, Timestamp now) {
        ensure("ACTOR", actor, "actors");
        upsertActor(actor, subject, now);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, ?) on conflict do nothing", actor, role);
    }

    /** Archive only idle synthetic work. Live assignments must be finished or cancelled normally. */
    private void prepareReset(UUID actor, String reason) {
        jdbc.execute("set local lock_timeout = '2s'");
        var orders = jdbc.query("""
                select id, customer_id, merchant_branch_id, status from orders
                where demo_generation is not null and status not in ('CANCELLED', 'DELIVERED', 'DELIVERY_FAILED')
                order by id for update
                """, (rs, row) -> new ResetOrder((UUID) rs.getObject("id"),
                (UUID) rs.getObject("customer_id"), (UUID) rs.getObject("merchant_branch_id"),
                rs.getString("status")));
        for (ResetOrder order : orders) {
            if (!id("customer").equals(order.customer()) || !id("branch").equals(order.branch())
                    || !Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from synthetic_demo_ownership
                        where resource_type = 'ORDER' and resource_id = ?)
                        """, Boolean.class, order.id()))) collision();
            if (!List.of("CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "DISPATCHING",
                    "DISPATCH_FAILED").contains(order.status()) || Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from driver_assignments where order_id = ?
                      and status in ('OFFERED', 'ACCEPTED'))
                    """, Boolean.class, order.id()))) resetBusy();
        }
        String driverState = jdbc.queryForObject("select operational_status from drivers where id = ? for update",
                String.class, id("driver"));
        if (List.of("RESERVED", "DELIVERING").contains(driverState)
                || Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from driver_assignments where driver_id = ?
                  and status in ('OFFERED', 'ACCEPTED'))
                """, Boolean.class, id("driver")))) resetBusy();
        for (ResetOrder order : orders) {
            var pending = jdbc.query("select id from deliveries where order_id = ? and status = 'PENDING'",
                    (rs, row) -> (UUID) rs.getObject(1), order.id());
            pending.forEach(delivery -> transitions.delivery(delivery, "PENDING", "CANCELLED", actor,
                    "DEMO_RESET", null));
            transitions.order(order.id(), order.status(), "CANCELLED", actor, "DEMO_RESET", reason);
            jdbc.update("""
                    update dispatch_recovery set status = 'RESOLVED', last_reason = 'DEMO_RESET',
                      lease_token = null, lease_until = null, updated_at = clock_timestamp()
                    where order_id = ?
                    """, order.id());
        }
        if (!"OFFLINE".equals(driverState))
            transitions.driver(id("driver"), driverState, "OFFLINE", actor, "DEMO_RESET");
    }

    private void resetBusy() {
        throw new ApiFailure(HttpStatus.CONFLICT, "DEMO_WORK_ACTIVE",
                "Finish or cancel active synthetic deliveries before resetting the demo");
    }

    private void ensure(String type, UUID resource, String table) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from " + table + " where id = ?)", Boolean.class, resource));
        boolean registered = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from synthetic_demo_ownership where resource_type = ? and resource_id = ?)
                """, Boolean.class, type, resource));
        if (exists && !registered) collision();
        if (!registered) jdbc.update("""
                insert into synthetic_demo_ownership(resource_type, resource_id) values (?, ?)
                """, type, resource);
    }

    private void createBaselineOrder(long generation) {
        UUID order = id("order:" + generation);
        UUID orderItem = id("order-item:" + generation + ":1");
        UUID customer = id("customer");
        UUID branch = id("branch");
        ensure("ORDER", order, "orders");
        ensure("ORDER_ITEM", orderItem, "order_items");
        var now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into orders
                  (id, customer_id, merchant_branch_id, status, version,
                   customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot,
                   pickup_line1, pickup_city, pickup_latitude, pickup_longitude,
                   delivery_line1, delivery_city, delivery_latitude, delivery_longitude,
                   currency, total_amount, created_at, updated_at,
                   zone_mode_at_create, zone_policy_version_at_create, zone_id_at_create, demo_generation)
                values (?, ?, ?, 'CREATED', 0, 'Demo Customer', 'Cairo Parcel Kitchen', 'Downtown Branch',
                   '48 Demo Avenue', 'Cairo', 30.044400, 31.235700,
                   '12 Example Street', 'Cairo', 30.052100, 31.242200,
                   'EGP', 245.00, ?, ?, 'UNRESTRICTED',
                   (select zone_policy_version from merchant_branches where id = ?), null, ?)
                """, order, customer, branch, now, now, branch, generation);
        jdbc.update("""
                insert into order_items(id, order_id, line_number, description_snapshot, quantity,
                  unit_amount, line_amount) values (?, ?, 1, 'Synthetic parcel delivery', 1, 245.00, 245.00)
                """, orderItem, order);
        jdbc.update("""
                insert into order_lifecycle_history
                  (order_id, version, previous_state, new_state, action, occurred_at,
                   actor_id, source, reason, correlation_id)
                values (?, 0, null, 'CREATED', 'DEMO_SEED', ?, null, 'SYSTEM', null, ?)
                """, order, now, "demo-generation-" + generation);
    }

    private boolean baselineExists(long generation) {
        UUID order = id("order:" + generation);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from orders o join customers c on c.id = o.customer_id
                  where o.id = ? and o.demo_generation = ? and c.actor_id = ?)
                """, Boolean.class, order, generation, id("actor:customer")));
    }

    private void audit(UUID actor, String action, String key, String requestHash,
                       String reason, String result, long generation) {
        jdbc.update("""
                insert into synthetic_demo_audit
                  (id, actor_id, action, idempotency_key, request_hash, reason, result_code, generation, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor, action, key, requestHash, reason, result, generation,
                Timestamp.from(clock.instant()));
    }

    private DemoView view(long generation, String result) {
        List<UUID> orders = generation == 0 ? List.of() : jdbc.query(
                "select id from orders where id = ? and demo_generation = ?",
                (rs, row) -> (UUID) rs.getObject(1), id("order:" + generation), generation);
        return new DemoView(generation, result, orders, PERSONAS, "Central Demo Zone", "NOT_ACTIVE_YET");
    }

    private UUID id(String name) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + name).getBytes(StandardCharsets.UTF_8));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint demo action", failure);
        }
    }

    private String safeFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) return "ACTION_FAILED";
        return value;
    }

    private void collision() {
        throw new ApiFailure(HttpStatus.CONFLICT, "DEMO_RESOURCE_COLLISION",
                "Synthetic demo resource conflicts with an existing record");
    }

    record Persona(String key, String role, String displayName) { }
    record FixturePosition(BigDecimal latitude, BigDecimal longitude) { }
    record DriverRoute(long generation, List<FixturePosition> points) { }
    private record RouteEndpoints(double pickupLat, double pickupLon, double deliveryLat, double deliveryLon) { }
    record DemoView(long generation, String result, List<UUID> orderIds, List<Persona> personas,
                    String zoneName, String trackingBaseline) { }
    private record ActorIdentity(String issuer, String subject, boolean active) { }
    private record Prior(String requestHash, String resultCode, long generation) { }
    private record ResetOrder(UUID id, UUID customer, UUID branch, String status) { }
}
