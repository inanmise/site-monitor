package com.sitemonitor.service.metrics;

import com.sitemonitor.model.SystemMetricSample;
import com.sitemonitor.repository.SystemMetricSampleRepository;
import com.sitemonitor.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sistem Sağlığı → Metrikler okumaları (2026-10-13). Seri: aralığa göre kova (1 sa → dakika, 6 / 24 sa → 10 dakika,
 * 7 gün → saat) — toplama SQL'de, sabit sayıda sorgu; aralığın başı kova sınırına hizalanır (ilk kova yarım kalmaz).
 * Sayaçlar (GC, tamamlanan görev, taşan görev, günlük satırı) kovada DAKİKA BAŞINA oran olarak da verilir (toplam ÷ örnek
 * sayısı DEĞİL, artışların kapsadığı dakika: {@code delta_minutes}): bitmemiş son kova yarım toplamı, yeniden başlatma sonrası
 * taban satırı ya da geciken tick oranı bozmaz. HTTP istek/dk = istek ÷ örneklere katlanan HTTP dakikası. Özet: her podun SON
 * örneği + son 60 dakikanın toplamları. Pod durumu: düzgün kapanışta yazılan işaret → durdu (dağıtım / ölçekleme — uyarı değil);
 * işaret yoksa son örneği {@value #LIVE_MINUTES} dakikadan yeni → canlı, daha eski → sessiz (çöküş / takılma — uyarı, ne kadar
 * eski olursa olsun). Veri yoksa alanlar null — arayüz "bilinmiyor" der (asla yeşil değil).
 */
@Service
@RequiredArgsConstructor
public class SystemMetricsQueryService {

    /** Bir pod son örneği bu kadar dakikadan yeniyse canlı. */
    static final int LIVE_MINUTES = 3;
    /** Pod adları bu pencerede ayrıştırılır (saklama süresi) — her ekranda aynı pod aynı adla görünür. */
    static final int NAME_WINDOW_DAYS = 7;

    /** Aralık → (dakika, kova önek uzunluğu, kova adı, kova saniyesi). */
    record Range(String key, int minutes, int prefixLen, String bucket, long bucketSeconds) {}

    static final Map<String, Range> RANGES = Map.of(
            "1h", new Range("1h", 60, 16, "1m", 60),
            "6h", new Range("6h", 360, 15, "10m", 600),
            "24h", new Range("24h", 1440, 15, "10m", 600),
            "7d", new Range("7d", 10080, 13, "1h", 3600));

    private final SystemMetricSampleRepository repo;

    /** Örnekleyici anahtarı — kapalıyken ekran "toplanıyor" değil "kapalı" der. */
    @Value("${site.monitor.metrics.system.enabled:true}")
    boolean enabled = true;

    public static Range range(String key) {
        return RANGES.getOrDefault(key == null ? "" : key.trim(), RANGES.get("6h"));
    }

    /** Pod × kova serisi. {@code instance} null / boş = tüm podlar. */
    public Map<String, Object> series(String rangeKey, String instance, Instant now) {
        Range r = range(rangeKey);
        long start = now.minus(r.minutes(), ChronoUnit.MINUTES).getEpochSecond();
        String to = SystemMetricSampler.MINUTE.format(now);
        String from = SystemMetricSampler.MINUTE.format(Instant.ofEpochSecond(start - Math.floorMod(start, r.bucketSeconds())));
        String inst = instance == null || instance.isBlank() ? null : instance.trim();
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] row : repo.aggregate(from, to, inst, r.prefixLen())) points.add(point(row, r.prefixLen()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r.key());
        out.put("bucket", r.bucket());
        out.put("from", from);
        out.put("to", to);
        out.put("instance", inst);
        out.put("pods", pods(now, from));
        out.put("points", points);
        return out;
    }

    /** Podlar + her podun son örneği + son 60 dakikanın küme toplamları. */
    public Map<String, Object> overview(Instant now) {
        String hourAgo = SystemMetricSampler.MINUTE.format(now.minus(60, ChronoUnit.MINUTES));
        String recent = SystemMetricSampler.MINUTE.format(now.minus(LIVE_MINUTES, ChronoUnit.MINUTES));
        List<Map<String, Object>> pods = pods(now, SystemMetricSampler.MINUTE.format(now.minus(24, ChronoUnit.HOURS)));

        // Her podun EN YENİ örneği (son birkaç dakikada) — küçük küme
        Map<String, SystemMetricSample> latest = new LinkedHashMap<>();
        for (SystemMetricSample s : repo.findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc(recent)) latest.putIfAbsent(s.getInstanceId(), s);
        for (Map<String, Object> p : pods) {
            SystemMetricSample s = Boolean.TRUE.equals(p.get("live")) ? latest.get(String.valueOf(p.get("id"))) : null;
            p.put("latest", s == null ? null : latestMap(s));
        }

        // Son 60 dakika: dakika kovalarının toplamı (tüm podlar). Örnek yoksa her alan null (0 uydurulmaz).
        long req = 0, err = 0, sumMs = 0, callerRuns = 0, warn = 0, error = 0, gcMs = 0;
        boolean anySample = false, anyHttp = false, anyLog = false, anyCaller = false, anyGc = false;
        Double p95 = null;
        for (Object[] row : repo.aggregate(hourAgo, SystemMetricSampler.MINUTE.format(now), null, 16)) {
            Map<String, Object> pt = point(row, 16);
            anySample = true;
            if (pt.get("http_requests") instanceof Number n) { req += n.longValue(); anyHttp = true; }
            if (pt.get("http_errors") instanceof Number n) err += n.longValue();
            if (pt.get("http_sum_ms") instanceof Number n) sumMs += n.longValue();
            if (pt.get("http_p95_ms") instanceof Number n) p95 = p95 == null ? n.doubleValue() : Math.max(p95, n.doubleValue());
            if (pt.get("caller_runs") instanceof Number n) { callerRuns += n.longValue(); anyCaller = true; }
            if (pt.get("log_warn") instanceof Number n) { warn += n.longValue(); anyLog = true; }
            if (pt.get("log_error") instanceof Number n) { error += n.longValue(); anyLog = true; }
            if (pt.get("gc_pause_ms") instanceof Number n) { gcMs += n.longValue(); anyGc = true; }
        }
        Map<String, Object> hour = new LinkedHashMap<>();
        hour.put("samples", anySample);
        hour.put("http_requests", anyHttp ? req : null);
        hour.put("http_errors", anyHttp ? err : null);
        hour.put("http_error_pct", anyHttp && req > 0 ? Double.valueOf(round1(err * 100.0 / req)) : anyHttp ? Double.valueOf(0.0) : null);
        hour.put("http_avg_ms", anyHttp && req > 0 ? Long.valueOf(Math.round((double) sumMs / req)) : null);
        hour.put("http_p95_ms", p95 == null ? null : Long.valueOf(Math.round(p95)));
        hour.put("caller_runs", anyCaller ? Long.valueOf(callerRuns) : null);
        hour.put("log_warn", anyLog ? warn : null);
        hour.put("log_error", anyLog ? error : null);
        hour.put("gc_pause_ms", anyGc ? Long.valueOf(gcMs) : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", SystemMetricSampler.MINUTE.format(now));
        out.put("enabled", enabled);
        out.put("served_by", SchedulerService.instanceId());
        // Sunan podun çalışma süresi: hiç örnek yokken "yeni açıldı, toplanıyor" ile "açık ama yazamıyor" ayrımı
        Long uptime = null;
        try { uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000; } catch (Exception ignored) { /* bilinmiyor */ }
        out.put("served_uptime_s", uptime);
        out.put("pods", pods);
        out.put("pods_live", pods.stream().filter((p) -> "live".equals(p.get("state"))).count());
        out.put("last_hour", hour);
        return out;
    }

    /**
     * {@code since}'ten sonra örnek yazmış podlar. Adlar {@value #NAME_WINDOW_DAYS} günlük pencerede ayrıştırılır (tek sorgu):
     * aynı pod adında birden çok süreç (konteyner yeniden başladı → yeni örnek kimliği) kimliğin son ekiyle ayrılır ve bu ad
     * her aralıkta / ekranda aynıdır.
     */
    private List<Map<String, Object>> pods(Instant now, String since) {
        String liveSince = SystemMetricSampler.MINUTE.format(now.minus(LIVE_MINUTES, ChronoUnit.MINUTES));
        String nameSince = SystemMetricSampler.MINUTE.format(now.minus(NAME_WINDOW_DAYS, ChronoUnit.DAYS));
        String windowStart = since.compareTo(nameSince) < 0 ? since : nameSince;
        List<Map<String, Object>> all = new ArrayList<>();
        for (Object[] r : repo.podsSince(windowStart)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("name", r[1] != null ? r[1] : r[0]);
            m.put("last_seen", r[2]);
            m.put("marker", r.length > 3 && r[3] instanceof Number n && n.intValue() > 0);
            all.add(m);
        }
        Map<String, Integer> sameName = new LinkedHashMap<>();
        for (Map<String, Object> m : all) sameName.merge(String.valueOf(m.get("name")), 1, Integer::sum);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : all) {
            String name = String.valueOf(m.get("name"));
            if (sameName.get(name) > 1) m.put("name", name + " · " + suffix(String.valueOf(m.get("id"))));
            String seen = m.get("last_seen") == null ? null : String.valueOf(m.get("last_seen"));
            if (seen == null || seen.compareTo(since) < 0) continue;
            String state = Boolean.TRUE.equals(m.remove("marker")) ? "stopped" : seen.compareTo(liveSince) >= 0 ? "live" : "silent";
            m.put("state", state);
            m.put("live", "live".equals(state));
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    /** Örnek kimliğinin son eki ({@code host-1a2b3c4d} → {@code 1a2b3c4d}); ek yoksa kimliğin tamamı. */
    static String suffix(String id) {
        int dash = id.lastIndexOf('-');
        return dash >= 0 && dash < id.length() - 1 ? id.substring(dash + 1) : id;
    }

    private static Map<String, Object> latestMap(SystemMetricSample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sampled_at", s.getSampledAt());
        m.put("uptime_s", s.getUptimeS());
        m.put("cpu_process", s.getCpuProcess());
        m.put("cpu_system", s.getCpuSystem());
        m.put("heap_used_mb", s.getHeapUsedMb());
        m.put("heap_max_mb", s.getHeapMaxMb());
        m.put("heap_pct", pct(s.getHeapUsedMb(), s.getHeapMaxMb()));
        m.put("non_heap_mb", s.getNonHeapMb());
        m.put("threads", s.getThreads());
        m.put("db_active", s.getDbActive());
        m.put("db_idle", s.getDbIdle());
        m.put("db_pending", s.getDbPending());
        m.put("db_max", s.getDbMax());
        m.put("db_usage_pct", pct(s.getDbActive(), s.getDbMax()));
        m.put("exec_active", s.getExecActive());
        m.put("exec_max", s.getExecMax());
        m.put("exec_queue", s.getExecQueue());
        m.put("exec_queue_cap", s.getExecQueueCap());
        m.put("exec_queue_pct", pct(s.getExecQueue(), s.getExecQueueCap()));
        m.put("http_requests", s.getHttpRequests());
        m.put("http_errors", s.getHttpErrors());
        m.put("http_p95_ms", s.getHttpP95Ms());
        return m;
    }

    /** Toplam satırı → nokta (sütun sırası repository sorgusuyla aynı; 37 = HTTP dakikası, 38 = artış dakikası, sona eklendi). */
    static Map<String, Object> point(Object[] r, int prefixLen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pod", r[0]);
        m.put("t", bucketStart(String.valueOf(r[1]), prefixLen));
        long samples = r[2] instanceof Number n ? n.longValue() : 0L;
        m.put("samples", samples);
        // Sayaç oranının paydası: artışların kapsadığı dakika (taban satırı 0, geciken tick 2–3 dakika sayar)
        Long deltaMinutesBox = r.length > 38 ? lng(r[38]) : null;
        long deltaMinutes = deltaMinutesBox == null ? 0L : deltaMinutesBox.longValue();
        m.put("delta_minutes", deltaMinutesBox);
        m.put("cpu_process_avg", d1(r[3]));
        m.put("cpu_process_max", d1(r[4]));
        m.put("cpu_system_avg", d1(r[5]));
        Double heapAvg = d1(r[6]), heapMax = d1(r[7]), heapCap = d1(r[8]);
        m.put("heap_used_avg", heapAvg);
        m.put("heap_used_max", heapMax);
        m.put("heap_max", heapCap);
        m.put("heap_pct_max", heapMax != null && heapCap != null && heapCap > 0 ? Double.valueOf(round1(heapMax * 100 / heapCap)) : null);
        m.put("non_heap_avg", d1(r[9]));
        m.put("threads_avg", d1(r[10]));
        m.put("threads_max", d1(r[11]));
        Long gc = lng(r[12]);
        m.put("gc_pause_ms", gc);
        m.put("gc_pause_ms_pm", perMin(gc, deltaMinutes));
        m.put("gc_count", lng(r[13]));
        Double dbActMax = d1(r[15]), dbMax = d1(r[18]);
        m.put("db_active_avg", d1(r[14]));
        m.put("db_active_max", dbActMax);
        m.put("db_idle_avg", d1(r[16]));
        m.put("db_pending_max", d1(r[17]));
        m.put("db_max", dbMax);
        m.put("db_usage_pct_max", dbActMax != null && dbMax != null && dbMax > 0 ? Double.valueOf(round1(dbActMax * 100 / dbMax)) : null);
        m.put("exec_active_avg", d1(r[19]));
        m.put("exec_active_max", d1(r[20]));
        m.put("exec_max", d1(r[21]));
        m.put("exec_queue_avg", d1(r[22]));
        m.put("exec_queue_max", d1(r[23]));
        m.put("exec_queue_cap", d1(r[24]));
        Long done = lng(r[25]), caller = lng(r[26]);
        m.put("exec_completed", done);
        m.put("exec_completed_pm", perMin(done, deltaMinutes));
        m.put("caller_runs", caller);
        m.put("caller_runs_pm", perMin(caller, deltaMinutes));
        Long req = lng(r[27]), err = lng(r[28]), sum = lng(r[29]);
        Long httpMinutes = r.length > 37 ? lng(r[37]) : null;
        m.put("http_requests", req);
        m.put("http_errors", err);
        m.put("http_sum_ms", sum);
        m.put("http_minutes", httpMinutes);
        // İstek/dk: örneklere katlanan HTTP dakikasına bölünür (dakika kayması bir örneğe iki dakika yazabilir)
        m.put("http_rpm", req != null && httpMinutes != null && httpMinutes > 0 ? Double.valueOf(round3((double) req / httpMinutes)) : null);
        // Her iki dal BOXED (iç içe üçlüde ilkel çift + null kutusundan çıkarma NPE verirdi)
        m.put("http_error_pct", req != null && req > 0 && err != null ? Double.valueOf(round1(err * 100.0 / req)) : req != null ? Double.valueOf(0.0) : null);
        m.put("http_avg_ms", req != null && req > 0 && sum != null ? Long.valueOf(Math.round((double) sum / req)) : null);
        m.put("http_p95_ms", lng(r[30]));
        m.put("http_max_ms", lng(r[31]));
        Long warn = lng(r[32]), error = lng(r[33]);
        m.put("log_warn", warn);
        m.put("log_warn_pm", perMin(warn, deltaMinutes));
        m.put("log_error", error);
        m.put("log_error_pm", perMin(error, deltaMinutes));
        Long hits = lng(r[34]), misses = lng(r[35]);
        m.put("cache_hits", hits);
        m.put("cache_misses", misses);
        m.put("cache_hit_pct", hits != null && misses != null && hits + misses > 0 ? Double.valueOf(round1(hits * 100.0 / (hits + misses))) : null);
        m.put("uptime_s", lng(r[36]));
        return m;
    }

    /**
     * Kovadaki toplam → dakika başına (artışların kapsadığı dakikaya bölünür); toplam ya da dakika yoksa null. ÜÇ ondalık:
     * saatlik kovada 2 ERROR = 0,033/dk — bir ondalığa yuvarlamak onu 0 gösterirdi (sahte sıfır, inceleme turu 4).
     */
    private static Double perMin(Long total, long minutes) {
        return total == null || minutes <= 0 ? null : Double.valueOf(round3((double) total / minutes));
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Kova öneki → kovanın başlangıç zamanı (ISO, saniye dahil). */
    static String bucketStart(String prefix, int len) {
        if (prefix == null) return null;
        return switch (len) {
            case 16 -> prefix + ":00";        // yyyy-MM-ddTHH:mm
            case 15 -> prefix + "0:00";       // yyyy-MM-ddTHH:m  → 10 dakikalık
            case 13 -> prefix + ":00:00";     // yyyy-MM-ddTHH
            default -> prefix;
        };
    }

    private static Integer pct(Integer used, Integer max) {
        return used != null && max != null && max > 0 ? Integer.valueOf((int) Math.round(used * 100.0 / max)) : null;
    }

    private static Double d1(Object v) { return v instanceof Number n ? Double.valueOf(round1(n.doubleValue())) : null; }

    private static Long lng(Object v) { return v instanceof Number n ? Long.valueOf(n.longValue()) : null; }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
