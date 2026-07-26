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
        String token = UUID.randomUUID().toString();   // ham token yalnız cookie'de; DB'de SHA-256 hash'i saklanır
        RememberMeToken entity = new RememberMeToken();
        entity.setToken(sha256(token));
        entity.setUsername(username);
        entity.setExpiresAt(Instant.now().getEpochSecond() + ttlSeconds);
        repo.save(entity);
        log.debug("Remember-me token oluşturuldu: user={}", username);
        return token;
    }

    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findByToken(sha256(token))
                .filter(t -> Instant.now().getEpochSecond() < t.getExpiresAt())
                .map(RememberMeToken::getUsername);
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            repo.deleteByToken(sha256(token));
        }
    }

    /** Bearer token'ı DB'de düz saklamamak için SHA-256 (Base64). DB-read/backup ile cookie replay'i engeller (CWE-522). */
    static String sha256(String token) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("remember-me hash failed", e);
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
