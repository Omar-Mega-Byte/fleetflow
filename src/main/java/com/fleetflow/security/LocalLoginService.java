package com.fleetflow.security;

import com.fleetflow.identity.ActorResolver;
import com.fleetflow.identity.AuthenticatedActor;
import com.fleetflow.api.ApiFailure;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class LocalLoginService {
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(90);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    // Always evaluate a password hash, including for unknown accounts.
    private static final String DUMMY_HASH = new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ActorResolver actors;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();

    LocalLoginService(JdbcTemplate jdbc, StringRedisTemplate redis, ActorResolver actors) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.actors = actors;
    }

    public LoginResult login(String email, String password, String remoteAddress) {
        String normalized = email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
        String source = remoteAddress == null ? "unknown" : remoteAddress;
        if (normalized.length() > 320 || password == null || password.length() > 1024) {
            throw invalidCredentials();
        }
        // Both dimensions are bounded. The account counter also covers distributed guessing.
        if (attempts("ip", source) > 30 || attempts("account", normalized) > 8) {
            throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later", 900L);
        }
        var matches = jdbc.query("""
                select c.actor_id, c.password_hash, c.enabled, a.active
                from local_login_credentials c join actors a on a.id = c.actor_id
                where c.email = ?
                """, (rs, row) -> new Credential(
                rs.getObject("actor_id", UUID.class), rs.getString("password_hash"),
                rs.getBoolean("enabled"), rs.getBoolean("active")), normalized);
        Credential credential = matches.isEmpty() ? null : matches.getFirst();
        boolean valid = passwords.matches(password, credential == null ? DUMMY_HASH : credential.hash());
        if (!valid || credential == null || !credential.enabled() || !credential.actorActive()) {
            throw invalidCredentials();
        }
        byte[] value = new byte[32];
        random.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        redis.opsForValue().set(sessionKey(token),
                credential.actorId() + "|" + hash(credential.hash()), SESSION_LIFETIME);
        return new LoginResult(token, Instant.now().plus(SESSION_LIFETIME));
    }

    public LocalSession authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        String key = sessionKey(token);
        String value = redis.opsForValue().get(key);
        if (value == null) return null;
        Long seconds = redis.getExpire(key);
        if (seconds == null || seconds <= 0) return null;
        try {
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) return null;
            UUID actorId = UUID.fromString(parts[0]);
            Boolean current = jdbc.query("select enabled, password_hash from local_login_credentials where actor_id = ?",
                    rs -> rs.next() && rs.getBoolean(1) && hash(rs.getString(2)).equals(parts[1]), actorId);
            if (!Boolean.TRUE.equals(current)) return null;
            return new LocalSession(actors.resolveById(actorId), Instant.now().plusSeconds(seconds));
        }
        catch (ActorResolver.ActorRejectedException | IllegalArgumentException rejected) { return null; }
    }

    public void logout(String token) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) redis.delete(sessionKey(token));
    }

    private long attempts(String dimension, String value) {
        String key = "ff:login:attempts:" + dimension + ":" + hash(value);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, ATTEMPT_WINDOW);
        return count == null ? Long.MAX_VALUE : count;
    }

    private String sessionKey(String token) { return "ff:login:session:" + hash(token); }
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private ApiFailure invalidCredentials() {
        return new ApiFailure(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    private record Credential(UUID actorId, String hash, boolean enabled, boolean actorActive) { }
    public record LoginResult(String token, Instant expiresAt) { }
    public record LocalSession(AuthenticatedActor actor, Instant expiresAt) { }
}
