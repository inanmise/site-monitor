package com.sitemonitor.controller;

import com.sitemonitor.service.StormService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Login sayfası hero istatistikleri — PUBLIC (auth YOK; AuthInterceptor.PUBLIC + 60 sn HTTP cache).
 * Yalnız iki toplam sayı döner (izlenen hedef adedi + 7 günlük erişilebilirlik yüzdesi) — domain
 * adı/detay sızmaz. Sunucu tarafında 5 dk in-memory cache: login sayfası açılışları DB'ye binmez.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicStatsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Sunucu tarafı cache penceresi (login açılışları DB'ye binmesin); testte 0'lanır. */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.public-stats.cache-ms:300000}")
    private long cacheMs;

    private final StormService stormService;
    private final JdbcTemplate jdbcTemplate;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    @GetMapping("/api/public-stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long now = System.currentTimeMillis();
        Map<String, Object> data = cached;
        if (data == null || now - cachedAt >= cacheMs) {
            data = compute();
            cached = data;
            cachedAt = now;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> compute() {
        Map<String, Object> data = new LinkedHashMap<>();
        // İzlenen hedef adedi — StormService sayacı (tüm monitör tipleri + envanter, çift sayma yok, 60 sn cache'li).
        data.put("monitored_targets", stormService.totalActiveMonitors());
        // 7 günlük erişilebilirlik: uptime_checks up oranı (bakım pencereleri hariç). Veri yoksa null → UI '—'.
        Double pct = null;
        try {
            String cutoff = ISO.format(Instant.now().minus(7, ChronoUnit.DAYS));
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) FILTER (WHERE status = 'up') AS ups, COUNT(*) AS total "
                            + "FROM uptime_checks WHERE checked_at >= ? AND maintenance IS NOT TRUE", cutoff);
            long total = row.get("total") instanceof Number n ? n.longValue() : 0;
            long ups   = row.get("ups")   instanceof Number n ? n.longValue() : 0;
            if (total > 0) pct = Math.round(ups * 1000.0 / total) / 10.0;   // 1 ondalık
        } catch (Exception e) {
            log.debug("Public stats erişilebilirlik hesaplanamadı: {}", e.getMessage());
        }
        data.put("availability_pct", pct);
        return data;
    }
}
