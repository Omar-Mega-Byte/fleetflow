package com.fleetflow.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final Clock clock;

    OrderLifecycleService(JdbcTemplate jdbc, ActorAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public void confirm(UUID id) { change(id, "CREATED", "CONFIRMED"); }

    @Transactional
    public void prepare(UUID id) { change(id, "CONFIRMED", "PREPARING"); }

    @Transactional
    public void ready(UUID id) { change(id, "PREPARING", "READY_FOR_PICKUP"); }

    private void change(UUID id, String from, String to) {
        UUID actor = access.require(Role.MERCHANT).id();
        Instant now = clock.instant();
        int updated = jdbc.update("""
                update orders o set status = ?, updated_at = ?
                where o.id = ? and o.status = ? and exists (
                  select 1 from merchant_staff_memberships ms
                  join merchant_branches b on b.id = ms.branch_id
                  join merchants m on m.id = b.merchant_id
                  where ms.actor_id = ? and ms.branch_id = o.merchant_branch_id
                    and ms.active and b.active and m.active)
                """, to, Timestamp.from(now), id, from, actor);
        if (updated == 0) {
            Boolean scoped = jdbc.queryForObject("""
                    select exists(select 1 from orders o
                      join merchant_staff_memberships ms on ms.branch_id = o.merchant_branch_id
                      join merchant_branches b on b.id = ms.branch_id
                      join merchants m on m.id = b.merchant_id
                      where o.id = ? and ms.actor_id = ? and ms.active and b.active and m.active)
                    """, Boolean.class, id, actor);
            if (!Boolean.TRUE.equals(scoped)) {
                throw new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
            }
            throw new ApiFailure(HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT", "Order state does not allow this action");
        }
        jdbc.update("""
                insert into order_status_history (id, order_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, actor, from, to, Timestamp.from(now));
    }
}
