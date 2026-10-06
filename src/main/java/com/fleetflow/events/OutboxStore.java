package com.fleetflow.events;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxStore {
    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public List<Claimed> claim(int limit, Duration lease) {
        var rows = jdbc.query("""
                select e.id, e.aggregate_id, e.envelope::text as envelope
                from outbox_events e
                where e.status = 'PENDING' and e.next_attempt_at <= clock_timestamp()
                  and (e.lease_until is null or e.lease_until <= clock_timestamp())
                  and not exists (
                    select 1 from outbox_events earlier
                    where earlier.aggregate_id = e.aggregate_id and earlier.sequence < e.sequence
                      and earlier.status <> 'PUBLISHED'
                  )
                order by e.next_attempt_at, e.sequence limit ? for update of e skip locked
                """, (rs, row) -> new Row((UUID) rs.getObject("id"),
                (UUID) rs.getObject("aggregate_id"), rs.getString("envelope")), limit);
        List<Claimed> claims = new ArrayList<>(rows.size());
        for (Row row : rows) {
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    update outbox_events set lease_token = ?,
                      lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                      attempts = attempts + 1 where id = ?
                    """, token, lease.toMillis(), row.id());
            claims.add(new Claimed(row.id(), row.aggregateId(), row.envelope(), token));
        }
        return claims;
    }

    @Transactional
    public boolean published(Claimed claim) {
        return jdbc.update("""
                update outbox_events set status = 'PUBLISHED', published_at = clock_timestamp(),
                  lease_token = null, lease_until = null, last_error = null
                where id = ? and lease_token = ? and status = 'PENDING'
                """, claim.id(), claim.token()) == 1;
    }

    @Transactional
    public void failed(Claimed claim, String errorCode, int maxAttempts) {
        jdbc.update("""
                update outbox_events set
                  status = case when attempts >= ? then 'FAILED' else 'PENDING' end,
                  next_attempt_at = clock_timestamp() +
                    (least(300, 5 * power(2, least(attempts - 1, 6))) * interval '1 second'),
                  lease_token = null, lease_until = null, last_error = ?
                where id = ? and lease_token = ? and status = 'PENDING'
                """, maxAttempts, errorCode, claim.id(), claim.token());
    }

    public Backlog backlog() {
        return jdbc.queryForObject("""
                select count(*) filter (where status = 'PENDING') as pending,
                  count(*) filter (where status = 'FAILED') as failed,
                  coalesce(extract(epoch from clock_timestamp() - min(occurred_at)
                    filter (where status = 'PENDING')), 0) as oldest_seconds
                from outbox_events
                """, (rs, row) -> new Backlog(rs.getLong("pending"), rs.getLong("failed"),
                rs.getDouble("oldest_seconds")));
    }

    /** Only fully projected, old published rows may leave the replay ledger. */
    @Transactional
    public int prunePublished(Duration retention, int limit) {
        List<UUID> ids = jdbc.query("""
                select e.id from outbox_events e
                where e.status = 'PUBLISHED'
                  and e.published_at < clock_timestamp() - (? * interval '1 millisecond')
                  and exists (select 1 from processed_events p
                    where p.event_id = e.id and p.consumer_name = 'notification')
                  and exists (select 1 from processed_events p
                    where p.event_id = e.id and p.consumer_name = 'analytics')
                order by e.published_at limit ? for update of e skip locked
                """, (rs, row) -> (UUID) rs.getObject("id"), retention.toMillis(), limit);
        for (UUID id : ids) {
            jdbc.update("delete from processed_events where event_id = ?", id);
            jdbc.update("delete from outbox_events where id = ? and status = 'PUBLISHED'", id);
        }
        return ids.size();
    }

    private record Row(UUID id, UUID aggregateId, String envelope) { }
    public record Claimed(UUID id, UUID aggregateId, String envelope, UUID token) { }
    public record Backlog(long pending, long failed, double oldestSeconds) { }
}
