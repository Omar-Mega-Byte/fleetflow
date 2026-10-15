package com.fleetflow.events;

import java.time.Instant;
import java.util.List;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/operations/events", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Event recovery", description = "Bounded inspection of quarantined event metadata.")
class QuarantineInspectionController {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;

    QuarantineInspectionController(JdbcTemplate jdbc, ActorAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/quarantined")
    @Operation(summary = "Inspect bounded quarantine metadata without exposing event payloads")
    @ApiResponse(responseCode = "200", description = "At most 100 quarantine records; payloads are not retained.")
    @Transactional(readOnly = true)
    QuarantinePage quarantined(@RequestParam(defaultValue = "50") int limit) {
        access.require(Role.ADMIN);
        if (limit < 1 || limit > 100) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Limit must be between 1 and 100");
        }
        return new QuarantinePage(jdbc.query("""
                select consumer_name, topic, partition_number, record_offset, error_code, quarantined_at
                from quarantined_events
                order by quarantined_at desc, topic, partition_number, record_offset desc
                limit ?
                """, (rs, row) -> new QuarantinedEvent(rs.getString("consumer_name"), rs.getString("topic"),
                rs.getInt("partition_number"), rs.getLong("record_offset"), rs.getString("error_code"),
                rs.getTimestamp("quarantined_at").toInstant()), limit));
    }

    record QuarantinedEvent(String consumer, String topic, int partition, long offset,
                            String errorCode, Instant quarantinedAt) { }
    record QuarantinePage(List<QuarantinedEvent> content) { }
}
