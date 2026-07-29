package com.certmonitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kalıcı gözlemlenebilirlik — büyüyen tabloların SATIR SAYISI ve BOYUTUNU Micrometer gauge'larıyla
 * {@code /metrics} (Prometheus) üzerine yayınlar: {@code db_table_rows{table=...}},
 * {@code db_table_bytes{table=...}}. Prometheus'ta zamanla trend + eşik uyarısı kurulabilir.
 * Değerler periyodik örneklenir (pg_stat_user_tables — ucuz, tablo-tarama YOK) ve bir tablo
 * yapılandırılabilir satır eşiğini aşarsa log WARN üretilir. Postgres-only; hata sessiz yutulur.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbGrowthMetrics {

    /** İzlenen (büyüyen) tablolar — gauge'lar bunlar için kaydedilir. */
    private static final List<String> TABLES = List.of(
            "activity_log", "audit_log", "port_checks", "ping_checks", "keyword_results", "http_checks",
            "uptime_checks", "dns_records", "certificate_checks", "notification_logs", "alert_events",
            "monitor_check_daily", "system_heartbeat");

    private final MeterRegistry registry;
    private final JdbcTemplate jdbcTemplate;
    private final AppSettingsService appSettings;

    private final Map<String, Long> rowCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> byteSizes = new ConcurrentHashMap<>();

    @PostConstruct
    void registerGauges() {
        for (String t : TABLES) {
            rowCounts.put(t, 0L);
            byteSizes.put(t, 0L);
            Gauge.builder("db.table.rows", rowCounts, m -> m.getOrDefault(t, 0L))
                    .tag("table", t).description("Estimated live row count").register(registry);
            Gauge.builder("db.table.bytes", byteSizes, m -> m.getOrDefault(t, 0L))
                    .tag("table", t).description("Total relation size (table+index+toast) in bytes").register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${cert.monitor.db.metrics-refresh-ms:300000}", initialDelayString = "60000")
    public void sample() {
        try {
            long warnRows = appSettings.getInt("cert.monitor.db.growth-warn-rows", 5_000_000);
            jdbcTemplate.query(
                    "SELECT relname, n_live_tup, pg_total_relation_size(relid) FROM pg_stat_user_tables",
                    rs -> {
                        String t = rs.getString(1);
                        if (!rowCounts.containsKey(t)) return;             // yalnız izlenen tablolar
                        long rows = rs.getLong(2);
                        rowCounts.put(t, rows);
                        byteSizes.put(t, rs.getLong(3));
                        if (warnRows > 0 && rows > warnRows) {
                            log.warn("DB büyüme eşiği: '{}' ~{} satır (> {} eşik) — retention/rollup gözden geçirin", t, rows, warnRows);
                        }
                    });
        } catch (Exception e) {
            log.debug("DB growth metrics sample atlandı (Postgres-only): {}", e.getMessage());
        }
    }

    // Test erişimi için okuma yardımcıları.
    long rows(String table) { return rowCounts.getOrDefault(table, -1L); }
    long bytes(String table) { return byteSizes.getOrDefault(table, -1L); }
}
