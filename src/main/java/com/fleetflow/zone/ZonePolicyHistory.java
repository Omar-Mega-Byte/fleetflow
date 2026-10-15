package com.fleetflow.zone;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared policy-version increment and immutable snapshot for merchant commands and synthetic reset. */
@Component
public class ZonePolicyHistory {
    private final JdbcTemplate jdbc;
    public ZonePolicyHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(UUID branchId, UUID zoneId, UUID actorId, String action) {
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
}
