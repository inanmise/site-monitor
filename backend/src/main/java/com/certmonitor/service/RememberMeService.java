package com.certmonitor.service;

import com.certmonitor.model.RememberMeToken;
import com.certmonitor.repository.RememberMeTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RememberMeService {

    public static final String COOKIE_NAME = "cert-monitor-remember";
    private static final long TTL_SECONDS = 7L * 24 * 3600; // 7 gün

    private final RememberMeTokenRepository repo;

    public String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        RememberMeToken entity = new RememberMeToken();
        entity.setToken(token);
        entity.setUsername(username);
        entity.setExpiresAt(Instant.now().getEpochSecond() + TTL_SECONDS);
        repo.save(entity);
        log.debug("Remember-me token oluşturuldu: user={}", username);
        return token;
    }

    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findByToken(token)
                .filter(t -> Instant.now().getEpochSecond() < t.getExpiresAt())
                .map(RememberMeToken::getUsername);
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            repo.deleteByToken(token);
        }
    }

    // Her saat başı süresi dolmuş token'ları temizle
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanExpired() {
        long now = Instant.now().getEpochSecond();
        repo.deleteExpired(now);
        log.debug("Süresi dolmuş remember-me token'ları temizlendi");
    }
}
