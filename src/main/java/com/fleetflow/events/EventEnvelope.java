package com.fleetflow.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Version 1 contains identifiers and state codes only; no names, addresses, or reasons. */
public record EventEnvelope(UUID eventId, int schemaVersion, String eventType,
                            String aggregateType, UUID aggregateId, Instant occurredAt,
                            Map<String, String> payload) { }
