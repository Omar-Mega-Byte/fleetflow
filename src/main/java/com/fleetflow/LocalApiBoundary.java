package com.fleetflow;

import java.net.InetAddress;
import java.util.Set;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
class LocalApiBoundary {

    LocalApiBoundary(Environment environment) {
        String address = environment.getProperty("server.address");
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        if (profiles.size() != 1 || !(profiles.contains("local") || profiles.contains("test"))) {
            throw new IllegalStateException("Phase 1 API requires only the local or test profile");
        }
        try {
            if (address == null || !InetAddress.getByName(address).isLoopbackAddress()) {
                throw new IllegalStateException("Phase 1 local/test API requires a loopback server.address");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("Phase 1 local/test API requires a loopback server.address", exception);
        }
    }
}
