package com.fleetflow.security;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** One-time operator tool: read a password from stdin and print only its BCrypt hash. */
public final class LocalPasswordHashTool {
    private LocalPasswordHashTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Password must come from stdin");
        String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (password == null || password.length() < 16 || password.length() > 1024) {
            throw new IllegalArgumentException("Password length must be between 16 and 1024 characters");
        }
        System.out.println(new BCryptPasswordEncoder(12).encode(password));
    }
}
