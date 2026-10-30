package com.moveinsync.rostersync.roster.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.auth.*: the dev issuer switch and its demo users (passwords BCrypt-hashed at startup). */
@ConfigurationProperties("app.auth")
public record AuthProperties(DevIssuer devIssuer, List<User> users) {

    public record DevIssuer(boolean enabled) {
    }

    public record User(String username, String password, String tenant, List<String> roles, String employeeId) {
    }

    public List<User> users() {
        return users == null ? List.of() : users;
    }
}
