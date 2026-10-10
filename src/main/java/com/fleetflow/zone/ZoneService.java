package com.fleetflow.zone;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fleetflow.api.ApiFailure;
import com.fleetflow.merchant.MerchantService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {
    private final JdbcTemplate jdbc;
    private final MerchantService merchants;
    private final ObjectMapper json;

    ZoneService(JdbcTemplate jdbc, MerchantService merchants, ObjectMapper json) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.json = json;
    }

    /** Caller owns the order row lock. This method then takes the branch lock. */
    public Decision forLockedOrder(UUID orderId) {
        var orders = jdbc.query("""
                select merchant_branch_id, delivery_latitude, delivery_longitude
                from orders where id = ?
                """, (rs, row) -> new Destination((UUID) rs.getObject(1),
                rs.getBigDecimal(2), rs.getBigDecimal(3)), orderId);
        if (orders.isEmpty()) throw new IllegalStateException("Locked order disappeared");
        Destination order = orders.getFirst();
        return evaluate(order.branchId(), order.latitude(), order.longitude(), true);
    }

    /** Called inside the order-create transaction before any order is inserted. */
    public Decision forNewOrder(UUID branchId, BigDecimal latitude, BigDecimal longitude) {
        Decision decision = evaluate(branchId, latitude, longitude, true);
        if (!decision.supported()) throw unsupported();
        return decision;
    }

    @Transactional
    public Preview preview(UUID branchId, BigDecimal latitude, BigDecimal longitude) {
        merchants.requireActiveBranchScope(branchId);
        Decision decision = evaluate(branchId, latitude, longitude, true);
        return new Preview(decision.supported(), decision.mode(), decision.policyVersion(), decision.zoneId());
    }

    private Decision evaluate(UUID branchId, BigDecimal latitude, BigDecimal longitude, boolean lock) {
        var branches = jdbc.query("select serviceability_mode, zone_policy_version "
                        + "from merchant_branches where id = ?" + (lock ? " for share" : ""),
                (rs, row) -> new BranchPolicy(rs.getString(1), rs.getLong(2)), branchId);
        if (branches.isEmpty()) throw new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
        BranchPolicy policy = branches.getFirst();
        if ("UNRESTRICTED".equals(policy.mode())) {
            return new Decision(true, policy.mode(), policy.version(), null);
        }
        List<UUID> zones = jdbc.query("""
                with destination as (select ST_SetSRID(ST_MakePoint(?, ?), 4326) as point)
                select z.id from delivery_zones z cross join destination d
                where z.branch_id = ? and z.active and z.boundary && d.point
                  and ST_Covers(z.boundary, d.point)
                order by z.id limit 1
                """, (rs, row) -> (UUID) rs.getObject(1), longitude, latitude, branchId);
        return new Decision(!zones.isEmpty(), policy.mode(), policy.version(),
                zones.isEmpty() ? null : zones.getFirst());
    }

    @Transactional(readOnly = true)
    public List<ZoneView> list(UUID branchId, int limit) {
        merchants.requireActiveBranchScope(branchId);
        if (limit < 1 || limit > 100) throw invalid("Limit must be between 1 and 100");
        return jdbc.query("""
                select id, branch_id, name, ST_AsGeoJSON(boundary) as geojson, active, version,
                       created_at, updated_at from delivery_zones
                where branch_id = ? order by created_at desc, id desc limit ?
                """, (rs, row) -> new ZoneView((UUID) rs.getObject("id"),
                (UUID) rs.getObject("branch_id"), rs.getString("name"), parse(rs.getString("geojson")),
                rs.getBoolean("active"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                branchId, limit);
    }

    @Transactional
    public ZoneView create(UUID branchId, String name, JsonNode geometry, boolean active) {
        UUID actorId = merchants.requireActiveBranchScope(branchId);
        String cleanName = name(name);
        String geojson = geometry(geometry);
        lockBranch(branchId);
        if (jdbc.queryForObject("select count(*) from delivery_zones where branch_id = ?",
                Integer.class, branchId) >= 100) {
            throw new ApiFailure(HttpStatus.CONFLICT, "ZONE_LIMIT", "Branch zone limit reached");
        }
        validateGeometry(geojson);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into delivery_zones
                  (id, branch_id, name, boundary, active, version, created_at, updated_at)
                values (?, ?, ?, ST_Multi(ST_GeomFromGeoJSON(?))::geometry(MultiPolygon, 4326),
                        ?, 0, clock_timestamp(), clock_timestamp())
                """, id, branchId, cleanName, geojson, active);
        policyChanged(branchId, id, actorId, "ZONE_CREATED");
        return one(branchId, id);
    }

    @Transactional
    public ZoneView replace(UUID branchId, UUID zoneId, String name, JsonNode geometry,
                            boolean active, long expectedVersion) {
        UUID actorId = merchants.requireActiveBranchScope(branchId);
        String cleanName = name(name);
        String geojson = geometry(geometry);
        lockBranch(branchId);
        validateGeometry(geojson);
        int changed = jdbc.update("""
                update delivery_zones set name = ?,
                    boundary = ST_Multi(ST_GeomFromGeoJSON(?))::geometry(MultiPolygon, 4326),
                    active = ?, version = version + 1, updated_at = clock_timestamp()
                where id = ? and branch_id = ? and version = ?
                """, cleanName, geojson, active, zoneId, branchId, expectedVersion);
        if (changed != 1) {
            if (!exists(branchId, zoneId)) throw missing();
            throw new ApiFailure(HttpStatus.CONFLICT, "ZONE_VERSION_CONFLICT", "Zone version changed");
        }
        policyChanged(branchId, zoneId, actorId, "ZONE_UPDATED");
        return one(branchId, zoneId);
    }

    @Transactional(readOnly = true)
    public Policy policy(UUID branchId) {
        merchants.requireActiveBranchScope(branchId);
        var result = jdbc.query("select serviceability_mode, zone_policy_version "
                        + "from merchant_branches where id = ?",
                (rs, row) -> new Policy(rs.getString(1), rs.getLong(2)), branchId);
        if (result.isEmpty()) throw missing();
        return result.getFirst();
    }

    @Transactional
    public Policy changeMode(UUID branchId, String mode, long expectedVersion) {
        UUID actorId = merchants.requireActiveBranchScope(branchId);
        if (!"UNRESTRICTED".equals(mode) && !"ENFORCED".equals(mode)) throw invalid("Invalid mode");
        Policy current = lockBranch(branchId);
        if (current.version() != expectedVersion) {
            throw new ApiFailure(HttpStatus.CONFLICT, "ZONE_POLICY_VERSION_CONFLICT", "Policy version changed");
        }
        if (current.mode().equals(mode)) return current;
        if ("ENFORCED".equals(mode) && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from delivery_zones where branch_id = ? and active)",
                Boolean.class, branchId))) {
            throw new ApiFailure(HttpStatus.CONFLICT, "ZONE_EMPTY", "Activate a zone before enforcing coverage");
        }
        jdbc.update("update merchant_branches set serviceability_mode = ? where id = ?", mode, branchId);
        long version = policyChanged(branchId, null, actorId, "MODE_CHANGED");
        return new Policy(mode, version);
    }

    private Policy lockBranch(UUID branchId) {
        var rows = jdbc.query("select serviceability_mode, zone_policy_version "
                        + "from merchant_branches where id = ? for update",
                (rs, row) -> new Policy(rs.getString(1), rs.getLong(2)), branchId);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private long policyChanged(UUID branchId, UUID zoneId, UUID actorId, String action) {
        long version = jdbc.queryForObject("""
                update merchant_branches set zone_policy_version = zone_policy_version + 1,
                    updated_at = clock_timestamp() where id = ? returning zone_policy_version
                """, Long.class, branchId);
        jdbc.update("""
                insert into zone_policy_history
                  (id, branch_id, zone_id, actor_id, action, policy_version, mode_after,
                   zone_name_after, zone_active_after, zone_boundary_after, recorded_at)
                select ?, b.id, z.id, ?, ?, ?, b.serviceability_mode,
                       z.name, z.active, z.boundary, clock_timestamp()
                from merchant_branches b left join delivery_zones z on z.id = ? and z.branch_id = b.id
                where b.id = ?
                """, UUID.randomUUID(), actorId, action, version, zoneId, branchId);
        return version;
    }

    private ZoneView one(UUID branchId, UUID zoneId) {
        var zones = jdbc.query("""
                select id, branch_id, name, ST_AsGeoJSON(boundary) as geojson, active, version,
                       created_at, updated_at from delivery_zones where id = ? and branch_id = ?
                """, (rs, row) -> new ZoneView((UUID) rs.getObject("id"),
                (UUID) rs.getObject("branch_id"), rs.getString("name"), parse(rs.getString("geojson")),
                rs.getBoolean("active"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                zoneId, branchId);
        if (zones.isEmpty()) throw missing();
        return zones.getFirst();
    }

    private boolean exists(UUID branchId, UUID zoneId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from delivery_zones where id = ? and branch_id = ?)",
                Boolean.class, zoneId, branchId));
    }

    private void validateGeometry(String geojson) {
        try {
            Boolean valid = jdbc.queryForObject("""
                    with g as (select ST_GeomFromGeoJSON(?) as boundary)
                    select ST_GeometryType(boundary) in ('ST_Polygon', 'ST_MultiPolygon')
                      and ST_SRID(boundary) = 4326 and ST_NDims(boundary) = 2
                      and not ST_IsEmpty(boundary)
                      and ST_IsValid(boundary) and ST_NPoints(boundary) between 4 and 2048
                      and ST_XMin(boundary) >= -180 and ST_XMax(boundary) <= 180
                      and ST_YMin(boundary) >= -90 and ST_YMax(boundary) <= 90 from g
                    """, Boolean.class, geojson);
            if (!Boolean.TRUE.equals(valid)) throw invalid("Invalid delivery zone geometry");
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && ("XX000".equals(sql.getSQLState())
                        || sql.getSQLState() != null && sql.getSQLState().startsWith("22"))) {
                    throw invalid("Invalid delivery zone geometry");
                }
            }
            throw failure;
        }
    }

    private String geometry(JsonNode geometry) {
        if (geometry == null || !geometry.isObject() || geometry.has("crs")
                || !("Polygon".equals(geometry.path("type").asText())
                    || "MultiPolygon".equals(geometry.path("type").asText()))
                || !geometry.path("coordinates").isArray()) {
            throw invalid("Expected a GeoJSON Polygon or MultiPolygon in WGS84");
        }
        String value = geometry.toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw invalid("Zone geometry exceeds 64 KiB");
        }
        return value;
    }

    private String name(String name) {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 120) {
            throw invalid("Zone name must have 1 to 120 characters");
        }
        return name.strip();
    }

    private JsonNode parse(String source) {
        try { return json.readTree(source); }
        catch (JacksonException failure) { throw new IllegalStateException("Stored zone geometry is invalid", failure); }
    }

    private ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    private ApiFailure invalid(String message) {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_ZONE", message);
    }

    public static ApiFailure unsupported() {
        return new ApiFailure(HttpStatus.CONFLICT, "ZONE_UNSERVICEABLE", "Destination is outside branch service area");
    }

    private record Destination(UUID branchId, BigDecimal latitude, BigDecimal longitude) { }
    private record BranchPolicy(String mode, long version) { }
    public record Decision(boolean supported, String mode, long policyVersion, UUID zoneId) { }
    public record Policy(String mode, long version) { }
    public record Preview(boolean supported, String mode, long policyVersion, UUID zoneId) { }
    public record ZoneView(UUID id, UUID branchId, String name, JsonNode geometry,
                           boolean active, long version, Instant createdAt, Instant updatedAt) { }
}
