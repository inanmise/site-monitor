package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RememberMeService {

    public static final String COOKIE_NAME = "cert-monitor-remember";
    private static final Duration TTL = Duration.ofDays(7);

    private record Entry(String username, Instant expiry) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        store.put(token, new Entry(username, Instant.now().plus(TTL)));
        log.debug("Remember-me token created for user: {}", username);
        return token;
    }

    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Entry entry = store.get(token);
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            store.remove(token);
            return Optional.empty();
        }
        return Optional.of(entry.username());
    }

    public void invalidate(String token) {
        if (token != null) store.remove(token);
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().expiry()));
        int removed = before - store.size();
        if (removed > 0) log.debug("Cleaned {} expired remember-me tokens", removed);
    }
}
