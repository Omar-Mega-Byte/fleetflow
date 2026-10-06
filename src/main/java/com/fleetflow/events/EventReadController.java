package com.fleetflow.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Async views")
class EventReadController {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;

    EventReadController(JdbcTemplate jdbc, ActorAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping(value = "/notifications/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read my newest in-app notifications")
    NotificationPage notifications(@RequestParam(defaultValue = "20") int limit) {
        UUID actor = access.requireAny(Role.CUSTOMER, Role.DRIVER).id();
        if (limit < 1 || limit > 20) throw invalid();
        List<NotificationView> content = jdbc.query("""
                select id, event_id, order_id, assignment_id, kind, state, created_at
                from notifications where recipient_actor_id = ?
                order by created_at desc, id desc limit ?
                """, (rs, row) -> new NotificationView((UUID) rs.getObject("id"),
                (UUID) rs.getObject("event_id"), (UUID) rs.getObject("order_id"),
                (UUID) rs.getObject("assignment_id"), rs.getString("kind"),
                rs.getString("state"), rs.getTimestamp("created_at").toInstant()), actor, limit);
        return new NotificationPage(content);
    }

    @GetMapping(value = "/dispatch/analytics/daily", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read UTC daily order transition counts")
    AnalyticsPage daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.require(Role.DISPATCHER);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 30) throw invalid();
        List<DailyCount> content = jdbc.query("""
                select day, status, total from analytics_daily_order_transitions
                where day between ? and ? order by day, status
                """, (rs, row) -> new DailyCount(rs.getDate("day").toLocalDate(),
                rs.getString("status"), rs.getLong("total")), from, to);
        return new AnalyticsPage(content);
    }

    private static ApiFailure invalid() {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid range or limit");
    }

    public record NotificationView(UUID id, UUID eventId, UUID orderId, UUID assignmentId,
                            String kind, String state, Instant createdAt) { }
    public record NotificationPage(List<NotificationView> content) { }
    public record DailyCount(LocalDate day, String status, long total) { }
    public record AnalyticsPage(List<DailyCount> content) { }
}
