package com.fleetflow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

final class TestOidc {
    static final String ISSUER = "http://fleetflow-test-issuer";
    static final String AUDIENCE = "fleetflow-api";
    private static final KeyPair KEY;
    private static final HttpServer SERVER;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY = generator.generateKeyPair();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RSAKey publicKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) KEY.getPublic())
                    .keyID("test-key").build();
            byte[] jwks = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
            SERVER.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var body = exchange.getResponseBody()) { body.write(jwks); }
            });
            SERVER.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static String jwksUri() { return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks"; }

    static String token(String subject) {
        return token(subject, ISSUER, AUDIENCE, Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), KEY);
    }

    static String token(String subject, String issuer, String audience, Instant notBefore, Instant expires,
                        KeyPair signingKey) {
        return token(subject, issuer, audience, notBefore, expires, signingKey, "test-key");
    }

    static String token(String subject, String issuer, String audience, Instant notBefore, Instant expires,
                        KeyPair signingKey, String keyId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                    .audience(List.of(audience)).issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .notBeforeTime(Date.from(notBefore)).expirationTime(Date.from(expires)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
            jwt.sign(new RSASSASigner(signingKey.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static KeyPair untrustedKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static KeyPair trustedKey() { return KEY; }

    static String claimedAdminToken(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject)
                    .audience(List.of(AUDIENCE)).issueTime(Date.from(Instant.now().minusSeconds(10)))
                    .notBeforeTime(Date.from(Instant.now().minusSeconds(10)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                    .claim("email", "admin@example.invalid").build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(KEY.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private TestOidc() { }
}
