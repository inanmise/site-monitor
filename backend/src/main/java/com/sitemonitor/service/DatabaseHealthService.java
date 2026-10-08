package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaPatchRunner;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Dışarıdan çağrılan VERİTABANI sağlık kontrolü (2026-10-08, kullanıcı isteği: "Site Monitor'e dışarıdan bir URL ile
 * çağrı yapacağım; veritabanında her şeyin yolunda olduğunu gösteren, dışarıdan çağrılabilen bir healthcheck olmalı").
 *
 * <p>{@code /health} (Actuator) oturumsuz çağrıda yalnız {@code {status}} döner ve veritabanını uygulamanın geri kalanıyla
 * tek sonuçta birleştirir; ayrıntısı yetkisiz isteğe kapalıdır. Bu servis yalnız veritabanına bakar ve her kontrolün
 * sonucunu GİZLİ BİLGİ İÇERMEDEN söyler (sunucu adı, veritabanı adı, kullanıcı, sürüm, hata metni YOK — yalnız durum,
 * süre ve sayı):
 * <ul>
 *   <li><b>connection</b> — havuzdan bağlantı alınabiliyor mu (süre);</li>
 *   <li><b>query</b> — {@code SELECT 1} süresi; {@link #slowMs} üstü DEGRADED;</li>
 *   <li><b>writable</b> — PostgreSQL salt-okunur / yedek (recovery) sunucuda DEĞİL mi (yazamayan uygulama bozuktur → DOWN);</li>
 *   <li><b>pool</b> — bağlantı bekleyen iş parçacığı varsa ya da havuz tamamen doluysa DEGRADED;</li>
 *   <li><b>schema</b> — açılıştaki şema yamalarında başarısız olan varsa DEGRADED.</li>
 * </ul>
 * Genel durum: herhangi biri DOWN → DOWN (HTTP 503); herhangi biri DEGRADED → DEGRADED (HTTP 200); aksi UP.
 *
 * <p><b>Yük ve süre sınırı.</b> Sonuç pod başına {@link #cacheMs} (5 sn) bellekte tutulur — dış izleyici saniyede kaç kez
 * çağırırsa çağırsın veritabanına en fazla 5 sn'de bir sorgu gider. Kontrolün tamamı {@link #timeoutMs} (4 sn) ile
 * sınırlı ayrı bir iş parçacığında koşar; aşılırsa DOWN + {@code TIMEOUT} (asılı bir veritabanı çağıranı bekletmez).
 */
@Slf4j
@Service
public class DatabaseHealthService {

    public static final String UP = "UP";
    public static final String DEGRADED = "DEGRADED";
    public static final String DOWN = "DOWN";

    private final DataSource dataSource;
    private final long cacheMs;
    private final long timeoutMs;
    private final long slowMs;
    private final Supplier<SchemaPatchRunner.Summary> schemaSummary;
    private final ExecutorService probes = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "db-health-probe");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot last;

    /** Kontrol sonucu: gövde + genel durum + hesaplandığı an. */
    public record Snapshot(Map<String, Object> body, String status, long atMs) { }

    @org.springframework.beans.factory.annotation.Autowired
    public DatabaseHealthService(DataSource dataSource,
                                 @Value("${site.monitor.health.db.cache-ms:5000}") long cacheMs,
                                 @Value("${site.monitor.health.db.timeout-ms:4000}") long timeoutMs,
                                 @Value("${site.monitor.health.db.slow-ms:1000}") long slowMs) {
        this(dataSource, cacheMs, timeoutMs, slowMs, SchemaPatchRunner::last);
    }

    DatabaseHealthService(DataSource dataSource, long cacheMs, long timeoutMs, long slowMs,
                          Supplier<SchemaPatchRunner.Summary> schemaSummary) {
        this.dataSource = dataSource;
        this.cacheMs = Math.max(0, cacheMs);
        this.timeoutMs = Math.max(500, timeoutMs);
        this.slowMs = Math.max(1, slowMs);
        this.schemaSummary = schemaSummary;
    }

    /** Önbellekteki (≤ cacheMs) ya da yeni hesaplanan sonuç. Aynı anda gelen istekler tek hesaplamayı paylaşır. */
    public synchronized Snapshot current() {
        long now = System.currentTimeMillis();
        Snapshot s = last;
        if (s != null && now - s.atMs() < cacheMs) {
            Map<String, Object> body = new LinkedHashMap<>(s.body());
            body.put("cached", true);
            return new Snapshot(body, s.status(), s.atMs());
        }
        last = compute();
        return last;
    }

    Snapshot compute() {
        long started = System.nanoTime();
        Map<String, Object> checks;
        Future<Map<String, Object>> f = probes.submit(this::runChecks);
        try {
            checks = f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            checks = new LinkedHashMap<>();
            checks.put("connection", check(DOWN, "error", "TIMEOUT"));
            log.warn("Veritabanı sağlık kontrolü {} ms içinde bitmedi", timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checks = new LinkedHashMap<>();
            checks.put("connection", check(DOWN, "error", "INTERRUPTED"));
        } catch (Exception e) {
            checks = new LinkedHashMap<>();
            checks.put("connection", check(DOWN, "error", "CONNECTION_FAILED"));
            log.warn("Veritabanı sağlık kontrolü başarısız: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
        }
        checks.put("pool", poolCheck());
        checks.put("schema", schemaCheck());

        String overall = UP;
        for (Object v : checks.values()) {
            String st = v instanceof Map<?, ?> m ? String.valueOf(m.get("status")) : UP;
            if (DOWN.equals(st)) { overall = DOWN; break; }
            if (DEGRADED.equals(st)) overall = DEGRADED;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("component", "database");
        body.put("checked_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        body.put("cached", false);
        body.put("checks", checks);
        return new Snapshot(body, overall, System.currentTimeMillis());
    }

    /** Bağlantı + sorgu + yazılabilirlik — tek bağlantıyla, sorgu başına 3 sn sınır. */
    Map<String, Object> runChecks() throws Exception {
        Map<String, Object> checks = new LinkedHashMap<>();
        long c0 = System.nanoTime();
        try (Connection con = dataSource.getConnection()) {
            checks.put("connection", check(UP, "acquire_ms", ms(c0)));

            long q0 = System.nanoTime();
            try (Statement st = con.createStatement()) {
                st.setQueryTimeout(3);
                try (ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                }
            }
            long q = ms(q0);
            checks.put("query", check(q > slowMs ? DEGRADED : UP, "latency_ms", q));

            boolean readOnly = con.isReadOnly();
            String product = String.valueOf(con.getMetaData().getDatabaseProductName()).toLowerCase(Locale.ROOT);
            if (product.contains("postgres")) {
                try (Statement st = con.createStatement()) {
                    st.setQueryTimeout(3);
                    try (ResultSet rs = st.executeQuery(
                            "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')")) {
                        if (rs.next()) {
                            readOnly = readOnly || rs.getBoolean(1) || "on".equalsIgnoreCase(rs.getString(2));
                        }
                    }
                }
            }
            checks.put("writable", check(readOnly ? DOWN : UP, readOnly ? "error" : null, readOnly ? "READ_ONLY" : null));
        }
        return checks;
    }

    /** Hikari havuzu: bekleyen iş parçacığı ya da tamamen dolu havuz → DEGRADED. Havuz bilgisi yoksa UP (bilinmiyor). */
    Map<String, Object> poolCheck() {
        if (!(dataSource instanceof HikariDataSource hds) || hds.getHikariPoolMXBean() == null) {
            return check(UP, null, null);
        }
        var mx = hds.getHikariPoolMXBean();
        int active = mx.getActiveConnections();
        int idle = mx.getIdleConnections();
        int total = mx.getTotalConnections();
        int waiting = mx.getThreadsAwaitingConnection();
        int max = hds.getMaximumPoolSize();
        String st = waiting > 0 || (max > 0 && active >= max) ? DEGRADED : UP;
        Map<String, Object> m = check(st, null, null);
        m.put("active", active);
        m.put("idle", idle);
        m.put("total", total);
        m.put("max", max);
        m.put("waiting", waiting);
        return m;
    }

    /** Açılıştaki şema yamaları: başarısız varsa DEGRADED; henüz koşmadıysa UP + {@code pending}. */
    Map<String, Object> schemaCheck() {
        SchemaPatchRunner.Summary s;
        try {
            s = schemaSummary.get();
        } catch (Exception e) {
            s = null;
        }
        if (s == null) {
            Map<String, Object> m = check(UP, null, null);
            m.put("pending", true);
            return m;
        }
        Map<String, Object> m = check(s.failed() > 0 ? DEGRADED : UP, null, null);
        m.put("failed_patches", s.failed());
        return m;
    }

    private static Map<String, Object> check(String status, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (key != null) m.put(key, value);
        return m;
    }

    private static long ms(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
