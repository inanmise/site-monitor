package com.sitemonitor.service.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * İş ölçümleri ucunun ({@code /metrics/sitemonitor}) taşıyıcı belirteci (2026-10-18, bağımsız inceleme: "izleme adları, hedefler,
 * takımlar ve alarmlar kimliksiz {@code /metrics}'te kurum ağına açıktı").
 *
 * <ul>
 *   <li><b>Kaynak:</b> YALNIZ ortam / yapılandırma — {@code site.monitor.metrics.export.token} = {@code METRICS_EXPORT_TOKEN}
 *       (Helm {@code secret.metricsExportToken}). {@code app_settings}'e yazılmaz (katalogda yok — Ayarlar onu yazamaz,
 *       açılış özeti listelemez), hiçbir API değerini döndürmez (yalnız "tanımlı mı"); istek günlüğü {@code Authorization}
 *       başlığını maskeler ve {@code /metrics*} yollarını hiç yazmaz.</li>
 *   <li><b>Yok ya da kısa ({@value #MIN_LENGTH} karakterden az):</b> uç 404 döner, aktarıcı hiç koşmaz (veritabanı yükü yok).</li>
 *   <li><b>Karşılaştırma:</b> iki tarafın SHA-256 özeti {@link MessageDigest#isEqual} ile (sabit süre; uzunluk sızmaz). Başlık
 *       uzunluğu düzenli ifadeden ÖNCE sınırlanır (zaten düzenli ifade yok).</li>
 * </ul>
 */
@Slf4j
@Component
public class MetricsExportToken {

    public static final String PROPERTY = "site.monitor.metrics.export.token";
    public static final String ENV = "METRICS_EXPORT_TOKEN";
    /** Tahmin edilemezlik için alt sınır (önerilen: 32+ rastgele karakter). */
    public static final int MIN_LENGTH = 16;
    /** Authorization başlığı üst sınırı — daha uzunu okunmadan reddedilir. */
    static final int MAX_HEADER = 1024;

    private final byte[] digest;
    private final boolean configured;
    private final boolean tooShort;

    public MetricsExportToken(@Value("${" + PROPERTY + ":}") String raw) {
        String t = raw == null ? "" : raw.trim();
        this.tooShort = !t.isEmpty() && t.length() < MIN_LENGTH;
        this.configured = t.length() >= MIN_LENGTH;
        this.digest = configured ? sha256(t) : null;
        if (tooShort) {
            log.warn("{} {} karakterden kısa — iş ölçümleri ucu (/metrics/sitemonitor) KAPALI. En az 32 rastgele karakter verin.",
                    ENV, MIN_LENGTH);
        }
    }

    /** Testler ve yardımcılar: ham değerden. */
    public static MetricsExportToken of(String raw) {
        return new MetricsExportToken(raw);
    }

    /** Belirteç tanımlı ve yeterince uzun mu. */
    public boolean configured() {
        return configured;
    }

    /** Değer verilmiş ama {@value #MIN_LENGTH} karakterden kısa (uç kapalı; ayar sayfası "tanımlı değil" der). */
    public boolean tooShort() {
        return tooShort;
    }

    /**
     * {@code Authorization: Bearer <belirteç>} başlığı tanımlı belirteçle eşleşiyor mu. Belirteç yoksa, başlık yoksa / çok
     * uzunsa / Bearer değilse false. Eşleşme sabit sürede (özet karşılaştırması).
     */
    public boolean matches(String authorization) {
        if (!configured || authorization == null || authorization.length() > MAX_HEADER) return false;
        String h = authorization.trim();
        if (h.length() <= 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String presented = h.substring(7).trim();
        if (presented.isEmpty()) return false;
        return MessageDigest.isEqual(digest, sha256(presented));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }

    @Override
    public String toString() {
        return "MetricsExportToken[configured=" + configured + "]";
    }
}
