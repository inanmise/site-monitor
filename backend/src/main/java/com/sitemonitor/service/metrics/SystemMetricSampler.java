package com.sitemonitor.service.metrics;

import com.sitemonitor.config.WebConfig;
import com.sitemonitor.model.SystemMetricSample;
import com.sitemonitor.repository.SystemMetricSampleRepository;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.SchedulerService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * SiteMonitor'ün kendi metriklerini POD BAŞINA dakikada bir kalıcı örnek olarak yazar (2026-10-13, Sistem Sağlığı →
 * Metrikler). Kilit YOK: her pod yalnız kendi satırını yazar ({@code instance_id}). Sayaçlar (GC, tamamlanan görev, taşan görev,
 * günlük olayı, önbellek) önceki örnekten bu yana ARTIŞ olarak saklanır; ilk örnek taban kurar (delta null — "JVM ömrü boyu"
 * birikmiş değer tek dakikaya yazılmaz). Okunamayan kaynak null kalır (0 uydurulmaz). Yazım hatası akışı kırmaz, saatte en
 * çok bir WARN.
 */
@Slf4j
@Service
public class SystemMetricSampler {

    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long WARN_INTERVAL_MS = 3_600_000L;
    /** Örnekleyici takılırsa tek örnekte en çok bu kadar biten HTTP dakikası toplanır. */
    static final int HTTP_MAX_MINUTES = 10;

    private final SystemMetricSampleRepository repo;
    private final DataSource dataSource;
    private final ThreadPoolTaskExecutor certCheckExecutor;
    private final HttpMetricsService httpMetrics;

    @Autowired(required = false) MeterRegistry registry;
    @Autowired(required = false) CacheManager cacheManager;
    /** Saat farkı ölçümü (MP-37c) — yoksa (birim testleri) ölçülmez, kolon null kalır. */
    @Autowired(required = false) JdbcTemplate jdbc;
    /** Pod saati (testler kaymış saat verir). */
    LongSupplier wallClock = System::currentTimeMillis;

    @Value("${site.monitor.metrics.system.enabled:true}")
    boolean enabled = true;

    // Önceki sayaç değerleri (delta tabanı) — yalnız örnekleyici iş parçacığı yazar
    private Long prevGcMs, prevGcCount, prevExecCompleted, prevCallerRuns, prevWarn, prevError, prevHits, prevMisses;
    private String lastMinute;
    /** Önceki örneğin dakikası — artışın kaç dakikayı kapsadığı (geciken / atlanan tick). */
    private LocalDateTime prevSampleMinute;
    /** Tüketilen son HTTP dakikası ({@code ts}) — aynı dakika iki kez sayılmaz. */
    private String prevHttpTs;
    private volatile long lastWarnMs = Long.MIN_VALUE / 2;
    /** Son ölçülen saat farkı (ms; null = ölçülemedi / henüz yok) — {@code sitemonitor_clock_skew_seconds} POD ölçümü okur. */
    private volatile Long lastClockSkewMs;

    /** Veritabanı saati — tek ucuz sorgu (PostgreSQL ve H2). */
    static final String SQL_DB_NOW = "SELECT CURRENT_TIMESTAMP";
    /** Gidiş-dönüş bundan uzunsa ölçüm güvenilmez (belirsizlik gidiş-dönüşün yarısı) → null yazılır. */
    static final long MAX_SKEW_RTT_MS = 2_000L;

    public SystemMetricSampler(SystemMetricSampleRepository repo, DataSource dataSource,
                               @Qualifier("certCheckExecutor") ThreadPoolTaskExecutor certCheckExecutor,
                               HttpMetricsService httpMetrics) {
        this.repo = repo;
        this.dataSource = dataSource;
        this.certCheckExecutor = certCheckExecutor;
        this.httpMetrics = httpMetrics;
    }

    /** Bu sınıfın bağladığı Logback sayacı (Boot'un otomatik yapılandırması devreye girmediyse) — kapanışta çözülür. */
    private LogbackMetrics ownLogbackMetrics;

    /**
     * WARN / ERROR sayıları {@code logback.events} sayacından okunur. Boot 4'ün LogbackMetrics otomatik yapılandırması bu
     * uygulamada devreye girmiyor (2026-10-13: /metrics'te logback_events yoktu) — sayaç YOKSA burada bir kez bağlanır;
     * varsa dokunulmaz (iki filtre aynı olayı iki kez saymaz).
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void bindLogEvents() {
        if (registry == null || ownLogbackMetrics != null) return;
        try {
            if (registry.find("logback.events").meter() != null) return;   // sayaç türü fark etmez (FunctionCounter)
            if (!(LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext)) return;
            LogbackMetrics lm = new LogbackMetrics();
            lm.bindTo(registry);
            ownLogbackMetrics = lm;
        } catch (Exception e) {
            log.debug("[SYSTEM-METRICS] logback sayacı bağlanamadı: {}", e.toString());
        }
    }

    /**
     * Kapanış: düzgün kapanan pod son satır olarak bir DURDU işareti yazar (dağıtım / ölçekleme pod'u "sessiz" uyarısına
     * düşmesin; işaretsiz susan pod — çöküş, takılma — sessiz kalır). Ardından kendi bağladığı Logback sayacını çözer.
     */
    @PreDestroy
    public void shutdown() {
        if (enabled) {
            try {
                repo.save(shutdownMarker(Instant.now()));
            } catch (Exception e) {
                log.debug("[SYSTEM-METRICS] kapanış işareti yazılamadı: {}", e.toString());
            }
        }
        unbindLogEvents();
    }

    /** Kapanış işareti satırı: yalnız kimlik + dakika + işaret (ölçüm alanları boş — toplamlara karışmaz). */
    SystemMetricSample shutdownMarker(Instant now) {
        SystemMetricSample s = new SystemMetricSample();
        s.setInstanceId(SchedulerService.instanceId());
        s.setPodName(podName());
        s.setSampledAt(MINUTE.format(now.truncatedTo(ChronoUnit.MINUTES)));
        s.setShutdownMarker(Boolean.TRUE);
        return s;
    }

    public synchronized void unbindLogEvents() {
        LogbackMetrics lm = ownLogbackMetrics;
        ownLogbackMetrics = null;
        if (lm != null) {
            try { lm.close(); } catch (Exception ignored) { /* kapanış */ }
        }
    }

    /**
     * Her dakikanın 7. saniyesinde (cron — sabit gecikme kayar ve ara sıra aynı dakikaya iki kez düşüp bir dakikayı
     * atlatırdı). HTTP dakikası ayrı zamanlayıcıyla kapanır; kaçan dakika bir sonraki örneğe eklenir (bkz. {@link #http}).
     */
    @Scheduled(cron = "7 * * * * *")
    public void tick() {
        if (!enabled) return;
        try {
            SystemMetricSample s = capture(Instant.now());
            if (s != null) repo.save(s);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastWarnMs >= WARN_INTERVAL_MS) {
                lastWarnMs = now;
                log.warn("[SYSTEM-METRICS] dakikalık örnek yazılamadı (atlandı; sonraki uyarı 1 saat sonra): {}", e.toString());
            }
        }
    }

    /** Bir örnek kurar (aynı dakika ikinci kez → null). Paket içi: birim testi okur. */
    synchronized SystemMetricSample capture(Instant now) {
        String minute = MINUTE.format(now.truncatedTo(ChronoUnit.MINUTES));
        if (minute.equals(lastMinute)) return null;
        lastMinute = minute;
        LocalDateTime current = LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MINUTES), ZoneOffset.UTC);
        Integer deltaMinutes = prevSampleMinute == null ? null
                : Integer.valueOf((int) Math.max(1, Math.min(Integer.MAX_VALUE, Duration.between(prevSampleMinute, current).toMinutes())));
        prevSampleMinute = current;

        SystemMetricSample s = new SystemMetricSample();
        s.setInstanceId(SchedulerService.instanceId());
        s.setPodName(podName());
        s.setSampledAt(minute);
        s.setDeltaMinutes(deltaMinutes);
        try { s.setUptimeS(ManagementFactory.getRuntimeMXBean().getUptime() / 1000); } catch (Exception ignored) { /* bilinmiyor */ }

        jvm(s);
        pool(s);
        executor(s);
        http(s);
        logEvents(s);
        caches(s);
        clockSkew(s);
        return s;
    }

    /**
     * Saat farkı (2026-10-23, çok pod R2b, MP-37c): pod saati − veritabanı saati. Kira ve kilit SQL'i pod saatiyle
     * karşılaştırır ({@code locked_until < ?}, {@code next_due_at < ?}); 30 sn'yi aşan fark R1'in güvenli kira payını
     * ({@code LEASE_SAFETY_MARGIN_MS}) aşındırır. Her pod kendi farkını kendi satırına yazar — kendi kendini izleme turunu
     * hangi pod kazanırsa kazansın HER canlı pod'un farkını okur (yalnız turu kazanan pod'u ölçen bir yoklama diğerlerini
     * göremezdi).
     */
    private void clockSkew(SystemMetricSample s) {
        Long skew = measureClockSkew();
        s.setClockSkewMs(skew);
        lastClockSkewMs = skew;
        lastClockSkewAtMs = System.currentTimeMillis();
    }

    /** Pod − DB (ms), gidiş-dönüşün ortası; ölçülemedi (bağlantı, sorgu, uzun gidiş-dönüş, tanınmayan tür) → null. */
    Long measureClockSkew() {
        JdbcTemplate j = jdbc;
        if (j == null) return null;
        try {
            long t0 = wallClock.getAsLong();
            Long dbMs = j.query(SQL_DB_NOW, rs -> rs.next() ? epochMs(rs) : null);
            long t1 = wallClock.getAsLong();
            if (dbMs == null || t1 < t0 || t1 - t0 > MAX_SKEW_RTT_MS) return null;
            return (t0 + t1) / 2 - dbMs;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sürücünün döndürdüğü zaman damgası → epoch ms (PostgreSQL: Timestamp, H2: OffsetDateTime). */
    private static Long epochMs(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object o = rs.getObject(1);
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant().toEpochMilli();
        if (o instanceof java.time.ZonedDateTime zdt) return zdt.toInstant().toEpochMilli();
        if (o instanceof Instant i) return i.toEpochMilli();
        if (o instanceof java.sql.Timestamp ts) return ts.getTime();
        java.sql.Timestamp ts = rs.getTimestamp(1);
        return ts == null ? null : ts.getTime();
    }

    /**
     * Son ölçülen saat farkı (ms) — yoksa null (örnekleyici kapalı, ölçülemedi, henüz ilk örnek yok, son ölçüm
     * {@link #SKEW_FRESH_MS}'den eski — durmuş örnekleyicinin eski değeri "güncel" yayılmasın).
     */
    public Long lastClockSkewMs() {
        if (!enabled) return null;
        Long v = lastClockSkewMs;
        return v != null && System.currentTimeMillis() - lastClockSkewAtMs <= SKEW_FRESH_MS ? v : null;
    }

    /** Son ölçümün bu süreden eski olması = bayat (dakikalık örnekleyici en az iki turu kaçırdı). */
    static final long SKEW_FRESH_MS = 3 * 60_000L;
    private volatile long lastClockSkewAtMs;

    /** Bu podun gösterim adı (POD_NAME ya da makine adı) — kendi kendini izleme olgularında "değerlendiren pod". */
    public static String currentPodName() {
        try {
            return podName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void jvm(SystemMetricSample s) {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                double p = os.getProcessCpuLoad(), c = os.getCpuLoad();
                s.setCpuProcess(p >= 0 ? round1(p * 100) : null);
                s.setCpuSystem(c >= 0 ? round1(c * 100) : null);
            }
        } catch (Exception ignored) { /* CPU okunamadı — null */ }
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            s.setHeapUsedMb((int) (heap.getUsed() / (1024 * 1024)));
            s.setHeapMaxMb(heap.getMax() > 0 ? (int) (heap.getMax() / (1024 * 1024)) : null);
            s.setNonHeapMb((int) (ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() / (1024 * 1024)));
            s.setThreads(ManagementFactory.getThreadMXBean().getThreadCount());
        } catch (Exception ignored) { /* bellek okunamadı */ }
        try {
            long ms = 0, count = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                ms += Math.max(0, gc.getCollectionTime());
                count += Math.max(0, gc.getCollectionCount());
            }
            s.setGcPauseMsDelta(delta(ms, prevGcMs));
            s.setGcCountDelta(delta(count, prevGcCount));
            prevGcMs = ms;
            prevGcCount = count;
        } catch (Exception ignored) { /* GC okunamadı */ }
    }

    private void pool(SystemMetricSample s) {
        try {
            if (dataSource instanceof HikariDataSource hds && hds.getHikariPoolMXBean() != null) {
                var p = hds.getHikariPoolMXBean();
                s.setDbActive(p.getActiveConnections());
                s.setDbIdle(p.getIdleConnections());
                s.setDbPending(p.getThreadsAwaitingConnection());
                s.setDbMax(hds.getMaximumPoolSize());
            }
        } catch (Exception ignored) { /* havuz okunamadı — null */ }
    }

    private void executor(SystemMetricSample s) {
        try {
            s.setExecActive(certCheckExecutor.getActiveCount());
            s.setExecPool(certCheckExecutor.getPoolSize());
            s.setExecMax(certCheckExecutor.getMaxPoolSize());
            s.setExecQueue(certCheckExecutor.getQueueSize());
            s.setExecQueueCap(certCheckExecutor.getQueueCapacity());
            long completed = certCheckExecutor.getThreadPoolExecutor().getCompletedTaskCount();
            s.setExecCompletedDelta(delta(completed, prevExecCompleted));
            prevExecCompleted = completed;
        } catch (Exception ignored) { /* havuz henüz başlamadı */ }
        long callerRuns = WebConfig.CALLER_RUNS.get();
        s.setExecCallerRunsDelta(delta(callerRuns, prevCallerRuns));
        prevCallerRuns = callerRuns;
    }

    /**
     * Önceki örnekten bu yana BİTEN dakikaların toplamı: istek / hata / süre toplanır, p95 ve en uzun için dakikaların en
     * kötüsü alınır. Yeni biten dakika yoksa alanlar null (bilinmiyor) — sonraki örnek o dakikayı taşır, toplam kaybolmaz.
     */
    private void http(SystemMetricSample s) {
        // Tavanın bir fazlası istenir: tam tavan kadar dakika gerçekten kırpılma mı, değil mi ayırt edilsin
        List<Map<String, Object>> mins = httpMetrics.completedMinutesAfter(prevHttpTs, HTTP_MAX_MINUTES + 1);
        if (mins == null || mins.isEmpty()) return;
        boolean truncated = mins.size() > HTTP_MAX_MINUTES;
        if (truncated) mins = mins.subList(mins.size() - HTTP_MAX_MINUTES, mins.size());
        long count = 0, errors = 0, sumMs = 0;
        Long p95 = null, maxMs = null;
        for (Map<String, Object> m : mins) {
            count += orZero(num(m.get("count")));
            errors += orZero(num(m.get("errors")));
            sumMs += orZero(num(m.get("sum_ms")));
            p95 = maxOf(p95, num(m.get("p95_ms")));
            maxMs = maxOf(maxMs, num(m.get("max_ms")));
        }
        String first = String.valueOf(mins.get(0).get("ts"));
        String newest = String.valueOf(mins.get(mins.size() - 1).get("ts"));
        // Kapsanan dakika: önceki tüketilenden en yeniye (sessiz dakikanın kovası hiç oluşmamış olabilir — o dakika 0 istektir);
        // tavana takıldıysa (eski dakikalar alınmadı) yalnız alınan aralık
        long span = prevHttpTs != null && !truncated
                ? minutesBetween(prevHttpTs, newest) : minutesBetween(first, newest) + 1;
        prevHttpTs = newest;
        s.setHttpRequests(count);
        s.setHttpErrors(errors);
        s.setHttpSumMs(sumMs);
        s.setHttpP95Ms(p95);
        s.setHttpMaxMs(maxMs);
        s.setHttpMinutes((int) Math.max(1, Math.min(10_000, span)));
    }

    /** İki dakika damgası arasındaki dakika (≥ 0); okunamazsa 0 — alt sınırı çağıran uygular (en az 1 dakika). */
    static long minutesBetween(String from, String to) {
        try {
            return Math.max(0, Duration.between(LocalDateTime.parse(from), LocalDateTime.parse(to)).toMinutes());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long orZero(Long v) { return v == null ? 0L : v; }

    private static Long maxOf(Long a, Long b) { return a == null ? b : b == null ? a : Long.valueOf(Math.max(a, b)); }

    private void logEvents(SystemMetricSample s) {
        if (registry == null) return;
        Long warn = counterTotal("warn"), error = counterTotal("error");
        if (warn != null) { s.setLogWarnDelta(delta(warn, prevWarn)); prevWarn = warn; }
        if (error != null) { s.setLogErrorDelta(delta(error, prevError)); prevError = error; }
    }

    /**
     * {@code logback.events} sayacının toplamı. Micrometer'ın {@code LogbackMetrics}'i onu {@link FunctionCounter} olarak
     * kaydeder ({@code find().counter()} null döner); düz {@link Counter} de kabul edilir. Yok / okunamaz → null.
     */
    private Long counterTotal(String level) {
        try {
            Meter m = registry.find("logback.events").tag("level", level).meter();
            if (m instanceof FunctionCounter fc) return Long.valueOf((long) fc.count());
            if (m instanceof Counter c) return Long.valueOf((long) c.count());
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void caches(SystemMetricSample s) {
        if (cacheManager == null) return;
        try {
            long hits = 0, misses = 0;
            for (String name : cacheManager.getCacheNames()) {
                if (cacheManager.getCache(name) instanceof CaffeineCache cc) {
                    var st = cc.getNativeCache().stats();
                    hits += st.hitCount();
                    misses += st.missCount();
                }
            }
            s.setCacheHitsDelta(delta(hits, prevHits));
            s.setCacheMissesDelta(delta(misses, prevMisses));
            prevHits = hits;
            prevMisses = misses;
        } catch (Exception ignored) { /* önbellek istatistiği yok */ }
    }

    /** Sayaç artışı: taban yoksa null (ilk örnek); sayaç geri sardıysa (yeniden başlatma) null — negatif artış yazılmaz. */
    static Long delta(long now, Long prev) {
        if (prev == null || now < prev) return null;
        return now - prev;
    }

    private static Long num(Object v) { return v instanceof Number n ? Long.valueOf(n.longValue()) : null; }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static String podName() {
        String pod = System.getenv("POD_NAME");
        if (pod != null && !pod.isBlank()) return pod.trim();
        String id = SchedulerService.instanceId();
        int dash = id == null ? -1 : id.lastIndexOf('-');
        return dash > 0 ? id.substring(0, dash) : id;
    }
}
