package com.moveinsync.rostersync.roster.security;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Demo users from configuration; plain-text passwords are BCrypt-hashed once at startup. */
@Component
public class UserDirectory {

    private record Entry(AuthProperties.User user, String hash) {
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Entry> users = new HashMap<>();

    public UserDirectory(AuthProperties properties) {
        for (AuthProperties.User user : properties.users()) {
            users.put(user.username(), new Entry(user, encoder.encode(user.password())));
        }
    }

    public Optional<AuthProperties.User> authenticate(String username, String password) {
        Entry entry = username == null ? null : users.get(username);
        if (entry == null || password == null || !encoder.matches(password, entry.hash())) {
            return Optional.empty();
        }
        return Optional.of(entry.user());
    }
}
