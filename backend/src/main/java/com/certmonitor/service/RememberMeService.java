package com.certmonitor.service;

import com.certmonitor.model.RememberMeToken;
import com.certmonitor.repository.RememberMeTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${cert.monitor.remember.ttl-seconds:604800}")
    private long ttlSeconds;

    private final RememberMeTokenRepository repo;

    public String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        RememberMeToken entity = new RememberMeToken();
        entity.setToken(token);
        entity.setUsername(username);
        entity.setExpiresAt(Instant.now().getEpochSecond() + ttlSeconds);
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

    /** Kullanıcının tüm remember-me token'larını iptal eder (tek aktif oturum — yeni login eski
     *  tarayıcıların sessizce geri dönmesini engeller). */
    public void invalidateAllForUser(String username) {
        if (username != null && !username.isBlank()) {
            repo.deleteByUsername(username);
        }
    }

    @Scheduled(fixedDelayString = "${cert.monitor.remember.cleanup-interval-ms:3600000}")
    public void cleanExpired() {
        long now = Instant.now().getEpochSecond();
        repo.deleteExpired(now);
        log.debug("Süresi dolmuş remember-me token'ları temizlendi");
    }
}
