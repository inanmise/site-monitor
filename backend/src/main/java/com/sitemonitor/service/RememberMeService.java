package com.sitemonitor.service;

import com.sitemonitor.model.RememberMeToken;
import com.sitemonitor.repository.RememberMeTokenRepository;
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

    public static final String COOKIE_NAME = "site-monitor-remember";
    /** Rename öncesi verilmiş cookie'ler — okuma/silmede tanınır ki mevcut "beni hatırla"
     *  oturumları rename ile düşmesin; yeni cookie her zaman yeni adla yazılır. */
    public static final String LEGACY_COOKIE_NAME = "cert-monitor-remember";   // geriye-uyum: eski cookie adı

    @Value("${site.monitor.remember.ttl-seconds:604800}")
    private long ttlSeconds;

    private final RememberMeTokenRepository repo;

    /** Meta'sız üretim — geriye uyum (testler ve eski çağıranlar). */
    public String generateToken(String username) {
        return generateToken(username, null, null);
    }

    /**
     * Token üretir ve cihaz meta'sını yazar (Cihaz Geçmişi ekranı).
     *
     * <p>Meta KİMLİK DOĞRULAMA verisi DEĞİL — eşleştirme hâlâ yalnız hash üzerinden yapılır;
     * bunlar kullanıcının kendi cihazını listede tanıyabilmesi içindir. Ham UA SAKLANMAZ,
     * yalnız {@link UserAgentSummary} özeti tutulur.
     *
     * @param username CANONICAL kullanıcı adı (yazılan case DEĞİL) — iptal yolu da bununla arar
     */
    public String generateToken(String username, String ip, String userAgent) {
        String token = UUID.randomUUID().toString();   // ham token yalnız cookie'de; DB'de SHA-256 hash'i saklanır
        RememberMeToken entity = new RememberMeToken();
        entity.setToken(sha256(token));
        entity.setUsername(username);
        entity.setExpiresAt(Instant.now().getEpochSecond() + ttlSeconds);
        entity.setCreatedAt(nowIso());
        entity.setIpAddress(ip);
        entity.setUaSummary(UserAgentSummary.labelOf(userAgent));
        repo.save(entity);
        log.debug("Remember-me token oluşturuldu: user={}", username);
        return token;
    }

    public Optional<String> validate(String token) {
        return validate(token, null);
    }

    /**
     * Token'ı doğrular ve BAŞARILIYSA "son kullanım" izini günceller — Cihaz Geçmişi ekranında
     * "bu cihaz en son ne zaman otomatik girdi" sorusunun cevabı budur; onsuz kullanılmayan bir
     * cihaz hâlâ taze görünürdü.
     *
     * <p>İz yazımı BEST-EFFORT: hata yutulur, çünkü bir güncelleme hatası GİRİŞİ engellememeli.
     */
    public Optional<String> validate(String token, String ip) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<RememberMeToken> row = repo.findByToken(sha256(token))
                .filter(t -> Instant.now().getEpochSecond() < t.getExpiresAt());
        row.ifPresent(t -> {
            try {
                t.setLastUsedAt(nowIso());
                if (ip != null && !ip.isBlank()) t.setIpAddress(ip);
                repo.save(t);
            } catch (Exception e) {
                log.debug("Remember-me son-kullanım izi yazılamadı: {}", e.getMessage());
            }
        });
        return row.map(RememberMeToken::getUsername);
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            repo.deleteByToken(sha256(token));
        }
    }

    private static String nowIso() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
    }

    /**
     * Bir cookie token'ının DB'deki karşılığı olan hash — "bu cihaz mı" karşılaştırması için.
     * Değerin KENDİSİ hiçbir yanıta yazılmaz; yalnız satır eşleştirmede kullanılır.
     */
    public String hashOf(String rawToken) {
        return (rawToken == null || rawToken.isBlank()) ? null : sha256(rawToken);
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

    @Scheduled(fixedDelayString = "${site.monitor.remember.cleanup-interval-ms:3600000}")
    public void cleanExpired() {
        long now = Instant.now().getEpochSecond();
        repo.deleteExpired(now);
        log.debug("Süresi dolmuş remember-me token'ları temizlendi");
    }
}
