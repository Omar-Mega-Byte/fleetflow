package com.fleetflow.security;

import com.fleetflow.api.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerSignupService {
    private static final Duration SIGNUP_WINDOW = Duration.ofHours(1);
    private static final Set<String> BLOCKED_PASSWORDS = Set.of(
            "passwordpassword", "password123456", "123456789012345", "qwerty123456789",
            "fleetflow123456", "letmein123456789", "adminadminadmin");
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    CustomerSignupService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Transactional
    public void register(String displayName, String email, String password, String remoteAddress) {
        String name = displayName == null ? "" : displayName.strip();
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (name.length() < 2 || name.length() > 120 || normalizedEmail.length() > 320
                || !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || password == null || password.length() < 15
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || BLOCKED_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SIGNUP", "Check your name, email, and password");
        }
        String source = remoteAddress == null ? "unknown" : remoteAddress;
        if (attempts("source", source) > 100 || attempts("email", normalizedEmail) > 5) {
            throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later", 3600L);
        }
        Integer existing = jdbc.queryForObject("select count(*) from local_login_credentials where email = ?",
                Integer.class, normalizedEmail);
        if (existing != null && existing > 0) {
            throw new ApiFailure(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email already has an account");
        }
        UUID actorId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, 'urn:fleetflow:local', ?, true, now(), now())",
                actorId, actorId.toString());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actorId);
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, ?, now(), now())", customerId, actorId, name);
        jdbc.update("insert into local_login_credentials (actor_id, email, password_hash) values (?, ?, ?)",
                actorId, normalizedEmail, passwords.encode(password));
    }

    private long attempts(String dimension, String value) {
        String key = "ff:signup:attempts:" + dimension + ":" + hash(value);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, SIGNUP_WINDOW);
        return count == null ? Long.MAX_VALUE : count;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
