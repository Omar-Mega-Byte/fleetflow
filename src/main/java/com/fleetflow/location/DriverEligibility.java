package com.fleetflow.location;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverEligibility {
    private final ActorAccess access;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    DriverEligibility(ActorAccess access, JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.access = access;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Transactional(readOnly = true)
    public UUID requireSelfOperational() {
        UUID actorId = access.require(Role.DRIVER).id();
        var facts = jdbc.query("select id, active, suspended, status from drivers where actor_id = ?",
                (rs, row) -> new Facts((UUID) rs.getObject("id"), rs.getBoolean("active"),
                        rs.getBoolean("suspended"), rs.getString("status")), actorId);
        if (facts.isEmpty()) {
            throw new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
        }
        Facts driver = facts.getFirst();
        if (!driver.active || driver.suspended ||
                !(driver.status.equals("ONLINE") || driver.status.equals("AVAILABLE")
                    || driver.status.equals("RESERVED") || driver.status.equals("DELIVERING"))) {
            throw new ApiFailure(HttpStatus.CONFLICT, "DRIVER_STATE_CONFLICT",
                    "Driver state does not allow this action");
        }
        return driver.id;
    }

    @Transactional(readOnly = true)
    public Set<UUID> eligibleIds(Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) return Set.of();
        var ids = namedJdbc.queryForList("""
                select d.id from drivers d join actors a on a.id = d.actor_id
                where d.id in (:ids) and d.active and not d.suspended and d.status = 'AVAILABLE'
                  and a.active
                  and exists (select 1 from actor_roles r where r.actor_id = a.id and r.role = 'DRIVER')
                  and exists (select 1 from vehicles v where v.driver_id = d.id and v.active and v.capacity > 0)
                """, Map.of("ids", candidateIds), UUID.class);
        return new HashSet<>(ids);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> matchingReasons(Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) return Map.of();
        Map<UUID, String> reasons = new HashMap<>();
        namedJdbc.query("""
                select d.id, d.active, d.suspended, d.status, a.active as actor_active,
                  exists(select 1 from actor_roles r where r.actor_id = a.id and r.role = 'DRIVER') as has_role,
                  exists(select 1 from vehicles v where v.driver_id = d.id and v.active and v.capacity > 0) as has_vehicle
                from drivers d join actors a on a.id = d.actor_id where d.id in (:ids)
                """, Map.of("ids", candidateIds), rs -> {
            UUID id = (UUID) rs.getObject("id");
            String reason = !rs.getBoolean("active") || rs.getBoolean("suspended")
                    || !"AVAILABLE".equals(rs.getString("status")) ? "DRIVER_NOT_AVAILABLE"
                    : !rs.getBoolean("actor_active") ? "ACTOR_INACTIVE"
                    : !rs.getBoolean("has_role") ? "ROLE_MISSING"
                    : !rs.getBoolean("has_vehicle") ? "VEHICLE_INACTIVE" : "ELIGIBLE";
            reasons.put(id, reason);
        });
        return reasons;
    }

    private record Facts(UUID id, boolean active, boolean suspended, String status) { }
}
