package com.fleetflow.location;

import java.time.Instant;
import java.util.UUID;

public record LocationFix(UUID driverId, double latitude, double longitude, Double heading,
                          Double speed, Instant reportedAt, Instant receivedAt) { }
