package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.metrics.BusinessMetricsRegistry;
import com.sitemonitor.service.metrics.MetricsExportToken;
import com.sitemonitor.service.metrics.MonitoringMetricsExporter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * İş ölçümleri ucu {@code GET /metrics/sitemonitor} (2026-10-18, bağımsız inceleme — kimliksiz {@code /metrics}'te izleme adları,
 * hedefler, takımlar, sertifika alan adları ve alarm kimlikleri kurum ağına açıktı).
 *
 * <ul>
 *   <li><b>Yalnız taşıyıcı belirteçle:</b> {@code Authorization: Bearer <METRICS_EXPORT_TOKEN>} sabit sürede karşılaştırılır
 *       ({@link MetricsExportToken}). Belirteç tanımlı değilse uç YOK (404, gövdesiz) ve aktarıcı hiç koşmaz.</li>
 *   <li><b>Yanlış / eksik belirteç:</b> 401 (gövde yok, {@code WWW-Authenticate: Bearer}). IP başına dakikada
 *       {@value #MAX_FAILURES_PER_MINUTE} başarısız denemeden sonra 429 + {@code Retry-After} (belirteç denenmeden). Güvenlik
 *       olayı {@code METRICS_EXPORT_ACCESS_DENIED} IP başına {@value #AUDIT_WINDOW_MINUTES} dakikada BİR kez yazılır (Prometheus
 *       yanlış yapılandırıldığında her kazıma bir denetim satırı olmasın).</li>
 *   <li><b>{@code /api/**} dışında:</b> oturum, CSRF ve kullanıcı kapsamı uygulanmaz — Prometheus bir kullanıcı değildir. Başarılı
 *       kazıma denetlenmez (her 15–60 sn'de bir; {@code AuditCoverageTest} muafiyeti).</li>
 *   <li><b>Tazelik:</b> aktarıcı bayatsa yalnız kendi tazelik ölçümleri yayınlanır ({@link MonitoringMetricsExporter#visibleNames()}).</li>
 *   <li>Yanıt {@code no-store}; istek günlüğü bu yolu hiç yazmaz ({@code RequestLoggingFilter}).</li>
 * </ul>
 */
@Slf4j
@RestController
public class MetricsExportController {

    public static final String PATH = "/metrics/sitemonitor";
    static final int MAX_FAILURES_PER_MINUTE = 10;
    static final int AUDIT_WINDOW_MINUTES = 10;
    /** Sayaç haritası üst sınırı — aşılırsa temizlenir (bellek sınırlı; kötü niyetli IP çeşitliliğine karşı). */
    static final int MAX_IPS = 2000;

    private final MetricsExportToken token;
    private final BusinessMetricsRegistry business;
    private final MonitoringMetricsExporter exporter;
    private final AuditService auditService;

    /** IP → {dakika, başarısız sayısı}. */
    private final ConcurrentHashMap<String, long[]> failures = new ConcurrentHashMap<>();
    /** IP → son denetim yazılan dakika. */
    private final ConcurrentHashMap<String, Long> audited = new ConcurrentHashMap<>();
    private LongSupplier clockMs = System::currentTimeMillis;

    public MetricsExportController(MetricsExportToken token, BusinessMetricsRegistry business, MonitoringMetricsExporter exporter,
                                   AuditService auditService) {
        this.token = token;
        this.business = business;
        this.exporter = exporter;
        this.auditService = auditService;
    }

    /** Testler: saat. */
    void setClockMs(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    @GetMapping(PATH)
    public ResponseEntity<String> scrape(HttpServletRequest request) {
        HttpHeaders h = new HttpHeaders();
        h.setCacheControl(CacheControl.noStore());
        if (!token.configured()) return ResponseEntity.status(404).headers(h).build();
        String ip = ipOf(request);
        long minute = clockMs.getAsLong() / 60_000L;
        if (blocked(ip, minute)) {
            h.set(HttpHeaders.RETRY_AFTER, "60");
            return ResponseEntity.status(429).headers(h).build();
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!token.matches(auth)) {
            countFailure(ip, minute);
            auditOnce(request, ip, minute, auth == null || auth.isBlank() ? "missing" : "invalid");
            h.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return ResponseEntity.status(401).headers(h).build();
        }
        String body = business.scrape(exporter.visibleNames());
        return ResponseEntity.ok().headers(h).contentType(MediaType.parseMediaType(BusinessMetricsRegistry.CONTENT_TYPE)).body(body);
    }

    private static String ipOf(HttpServletRequest request) {
        String ip = request == null ? null : request.getRemoteAddr();
        return ip == null || ip.isBlank() ? "?" : ip;
    }

    private boolean blocked(String ip, long minute) {
        long[] slot = failures.get(ip);
        return slot != null && slot[0] == minute && slot[1] >= MAX_FAILURES_PER_MINUTE;
    }

    private void countFailure(String ip, long minute) {
        if (failures.size() > MAX_IPS) failures.clear();
        failures.compute(ip, (k, v) -> {
            if (v == null || v[0] != minute) return new long[] { minute, 1 };
            v[1]++;
            return v;
        });
    }

    private void auditOnce(HttpServletRequest request, String ip, long minute, String reason) {
        if (audited.size() > MAX_IPS) audited.clear();
        Long last = audited.get(ip);
        if (last != null && minute - last < AUDIT_WINDOW_MINUTES) return;
        audited.put(ip, minute);
        try {
            auditService.recordSecurityEvent("METRICS_EXPORT_ACCESS_DENIED", request, null, "METRICS", PATH, reason);
        } catch (RuntimeException e) {
            log.debug("İş ölçümleri ucu denetimi yazılamadı: {}", e.toString());
        }
    }
}
