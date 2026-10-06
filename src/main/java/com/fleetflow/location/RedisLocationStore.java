package com.fleetflow.location;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Repository;

@Repository
public class RedisLocationStore {
    static final String GEO = "fleetflow:geo:available";
    private static final double GEO_MAX_LATITUDE = 85.05112878;
    private final StringRedisTemplate redis;
    private final LocationProperties settings;
    private final DefaultRedisScript<Long> locationScript = script("redis/accept-location.lua");
    private final DefaultRedisScript<Long> heartbeatScript = script("redis/heartbeat.lua");
    private final DefaultRedisScript<Long> orphanScript = script("redis/remove-orphan.lua");

    RedisLocationStore(StringRedisTemplate redis, LocationProperties settings) {
        this.redis = redis;
        this.settings = settings;
    }

    public long accept(LocationFix fix) {
        UUID id = fix.driverId();
        Long result = redis.execute(locationScript,
                List.of(locationKey(id), heartbeatKey(id), GEO, "fleetflow:rate:location:" + id),
                Long.toString(fix.reportedAt().getEpochSecond()), Integer.toString(fix.reportedAt().getNano()),
                Long.toString(fix.receivedAt().toEpochMilli()), Double.toString(fix.latitude()),
                Double.toString(fix.longitude()), optional(fix.heading()), optional(fix.speed()),
                Long.toString(settings.locationTtl().toMillis()), Long.toString(settings.heartbeatTtl().toMillis()),
                id.toString(), Integer.toString(settings.updateLimit()), Long.toString(settings.updateWindow().toMillis()),
                Double.toString(geoLatitude(fix.latitude())));
        return result == null ? -1 : result;
    }

    public long heartbeat(UUID id, Instant receivedAt) {
        Long result = redis.execute(heartbeatScript,
                List.of(heartbeatKey(id), "fleetflow:rate:heartbeat:" + id),
                Long.toString(receivedAt.toEpochMilli()), Long.toString(settings.heartbeatTtl().toMillis()),
                Integer.toString(settings.heartbeatLimit()), Long.toString(settings.heartbeatWindow().toMillis()));
        return result == null ? -1 : result;
    }

    public LocationFix latest(UUID id) {
        Map<Object, Object> data = redis.opsForHash().entries(locationKey(id));
        if (data.isEmpty()) return null;
        try {
            return new LocationFix(id, Double.parseDouble((String) data.get("latitude")),
                    Double.parseDouble((String) data.get("longitude")),
                    nullable((String) data.get("heading")), nullable((String) data.get("speed")),
                    Instant.ofEpochSecond(Long.parseLong((String) data.get("reportedSeconds")),
                            Long.parseLong((String) data.get("reportedNanos"))),
                    Instant.ofEpochMilli(Long.parseLong((String) data.get("receivedAtMillis"))));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public Instant lastHeartbeat(UUID id) {
        String value = redis.opsForValue().get(heartbeatKey(id));
        if (value == null) return null;
        try { return Instant.ofEpochMilli(Long.parseLong(value)); }
        catch (NumberFormatException malformed) { return null; }
    }

    public List<GeoHit> nearby(double latitude, double longitude, int radiusMeters, int cap) {
        // Redis GEO uses longitude, latitude. Pole values are clamped for indexing only.
        var results = redis.opsForGeo().search(GEO,
                GeoReference.<String>fromCoordinate(longitude, geoLatitude(latitude)),
                new Distance(radiusMeters / 1000.0, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().sortAscending().limit(cap).includeDistance());
        if (results == null) return List.of();
        List<GeoHit> hits = new ArrayList<>();
        for (var result : results) {
            try {
                hits.add(new GeoHit(UUID.fromString(result.getContent().getName()),
                        result.getDistance().in(Metrics.KILOMETERS).getValue() * 1000));
            } catch (RuntimeException malformed) {
                // Ignore invalid/orphan GEO members; they are not dispatch candidates.
            }
        }
        return hits;
    }

    public void removeOrphan(UUID id) {
        redis.execute(orphanScript, List.of(locationKey(id), GEO), id.toString());
    }

    public void removeFromGeo(UUID id) {
        redis.opsForZSet().remove(GEO, id.toString());
    }

    static String locationKey(UUID id) { return "fleetflow:driver:location:" + id; }
    static String heartbeatKey(UUID id) { return "fleetflow:driver:heartbeat:" + id; }

    private static String optional(Double value) { return value == null ? "" : Double.toString(value); }
    private static double geoLatitude(double latitude) {
        return Math.max(-GEO_MAX_LATITUDE, Math.min(GEO_MAX_LATITUDE, latitude));
    }
    private static Double nullable(String value) { return value == null || value.isEmpty() ? null : Double.valueOf(value); }
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public record GeoHit(UUID driverId, double distanceMeters) { }
}
