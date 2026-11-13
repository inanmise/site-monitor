package com.sitemonitor.service.dbgrowth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.Finding;
import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.TableFacts;
import com.sitemonitor.service.metrics.SystemMetricSampler;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * İstek üzerine veritabanı sağlık analizi (2026-11-13). SALT OKUNUR — hiçbir şey silmez, değiştirmez, DDL koşmaz. Her deyim
 * {@link PgDbSizeSource} zaman aşımıyla (5 sn); koşunun toplamı {@value #TOTAL_BUDGET_MS} ms — bütçe biterse kalan bölümler
 * "atlandı" olur (asla "sorun yok" değil). Pod başına aynı anda bir koşu ({@link #run} boş döner → 409); kişi başına sınır
 * denetleyicide.
 *
 * <p>Bölümler: kurallar (her yaş kuralı için en eski kayıt yaşı + önerilen kısaltmanın histogram tahmini), ölü satır / vacuum,
 * son 24 saatte en çok satır üreten izlemeler (seri tablosu başına tek GROUP BY), dizin şişmesi (gece bakımının tahmini — salt
 * okuma yolu), hiç kullanılmayan büyük dizinler. Bulgular {@link DbGrowthAdvisor} kurallarıyla; sonuç
 * {@code db_health_analysis_runs}'a yazılır (en yeni {@value #KEEP_RUNS} ve {@value #KEEP_DAYS} gün).
 */
@Slf4j
@Service
public class DbHealthAnalysisService {

    static final long TOTAL_BUDGET_MS = 60_000;
    static final int KEEP_RUNS = 50;
    static final int KEEP_DAYS = 180;
    static final long UNUSED_INDEX_MIN_BYTES = 50L << 20;
    static final int HOT_LIMIT = 5;
    /** Seri tabloları → izleme türü (bağlantı {@code ?tab=<tür>&monitor=<id>}). */
    static final List<String[]> HOT_TABLES = List.of(
            new String[]{"port_checks", "port"}, new String[]{"ping_checks", "ping"}, new String[]{"http_checks", "http"},
            new String[]{"keyword_results", "keyword"}, new String[]{"page_checks", "page"},
            new String[]{"pagespeed_checks", "pagespeed"}, new String[]{"scripted_checks", "scripted"},
            new String[]{"dns_records", "dns"}, new String[]{"domain_checks", "domain"}, new String[]{"dbquery_checks", "dbquery"});
    public static final List<String> SECTIONS = List.of("policies", "vacuum", "hot_monitors", "index_bloat", "unused_indexes");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final String SQL_INSERT = "INSERT INTO db_health_analysis_runs(started_at, finished_at, duration_ms, actor, pod, status, "
            + "result_json) VALUES (?, ?, ?, ?, ?, ?, ?)";
    static final String SQL_LATEST = "SELECT id, started_at, finished_at, duration_ms, actor, pod, status, result_json "
            + "FROM db_health_analysis_runs ORDER BY id DESC LIMIT 1";
    static final String SQL_LATEST_SUMMARY = "SELECT id, started_at, actor, status FROM db_health_analysis_runs ORDER BY id DESC LIMIT 1";
    static final String SQL_NTH_ID = "SELECT id FROM db_health_analysis_runs ORDER BY id DESC LIMIT 1 OFFSET " + (KEEP_RUNS - 1);

    private final JdbcTemplate jdbc;
    private final DbSizeSource source;
    private final DbGrowthService growth;
    private final RetentionService retention;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private Supplier<Instant> clock = Instant::now;
    private Supplier<Long> nanos = System::nanoTime;

    public DbHealthAnalysisService(JdbcTemplate jdbc, DbSizeSource source, DbGrowthService growth, RetentionService retention) {
        this.jdbc = jdbc;
        this.source = source;
        this.growth = growth;
        this.retention = retention;
    }

    void setClock(Supplier<Instant> c, Supplier<Long> n) { this.clock = c; this.nanos = n; }

    /** Pod'da bir analiz sürüyor mu. */
    public boolean busy() {
        return running.get();
    }

    /** Analizi koşar ve saklar; pod'da başka analiz sürüyorsa boş. */
    public Optional<Map<String, Object>> run(String actor) {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            return Optional.of(doRun(actor));
        } finally {
            running.set(false);
        }
    }

    Map<String, Object> doRun(String actor) {
        Instant start = clock.get();
        long t0 = nanos.get();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> status = new LinkedHashMap<>();
        for (String s : SECTIONS) status.put(s, "skipped");
        String runStatus;
        List<Finding> findings = new ArrayList<>();
        if (!Boolean.TRUE.equals(source.supported())) {
            runStatus = "UNSUPPORTED";
            result.put("supported", false);
        } else {
            result.put("supported", true);
            Budget budget = new Budget(t0);
            DbGrowthService.Computed base = null;
            try {
                base = growth.adviceInputs();
            } catch (RuntimeException e) {
                log.debug("Analiz: büyüme görünümü okunamadı: {}", e.toString());
            }
            Map<String, Double> purge = new HashMap<>();
            Map<String, String> purgeMethod = new HashMap<>();
            result.put("policies", policies(budget, status, purge, purgeMethod, start));
            List<Map<String, Object>> vac = vacuum(budget, status, findings);
            result.put("vacuum", vac);
            result.put("hot_monitors", hotMonitors(budget, status, findings, start));
            result.put("index_bloat", indexBloat(budget, status, findings));
            result.put("unused_indexes", unusedIndexes(budget, status, findings));
            // Genel bulgular: büyüme görünümünün tablo olguları + bu analizin kesin "kısaltırsam ne silinir" oranı
            if (base != null && base.ctx() != null) {
                List<TableFacts> facts = new ArrayList<>();
                for (TableFacts f : base.tableFacts()) {
                    String pid = f.primary() == null ? null : f.primary().id();
                    Double frac = pid == null ? null : purge.get(pid);
                    facts.add(frac == null ? f : new TableFacts(f.name(), f.totalBytes(), f.tableBytes(), f.indexBytes(), f.liveRows(),
                            f.deadRows(), f.growth30Bytes(), f.primary(), f.policies(), f.effectiveDays(), f.oldestAgeDays(),
                            f.equilibriumBytes(), f.lastRunFailed(), frac, purgeMethod.get(pid)));
                }
                for (Finding f : DbGrowthAdvisor.advise(facts, base.ctx())) {
                    // Ölü satır bulgusunu vacuum bölümü (son otomatik vacuum anıyla) yazdıysa görüntüdeki kopyası atlanır
                    if ("DEAD_TUPLES".equals(f.code()) && "ok".equals(status.get("vacuum"))) continue;
                    findings.add(f);
                }
            } else {
                status.put("growth", "failed");
            }
            boolean allOk = status.values().stream().allMatch("ok"::equals);
            runStatus = allOk ? "OK" : "PARTIAL";
        }
        DbGrowthAdvisor.sort(findings);
        List<Map<String, Object>> fv = new ArrayList<>();
        for (Finding f : findings) fv.add(f.toMap());
        result.put("findings", fv);
        result.put("sections", status);
        Instant end = clock.get();
        long ms = Math.max(0, (nanos.get() - t0) / 1_000_000L);
        result.put("budget_ms", TOTAL_BUDGET_MS);
        String pod = SystemMetricSampler.currentPodName();
        Long id = store(start, end, ms, actor, pod, runStatus, result);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", id);
        out.put("started_at", ISO.format(start));
        out.put("finished_at", ISO.format(end));
        out.put("duration_ms", ms);
        out.put("actor", actor);
        out.put("pod", pod);
        out.put("status", runStatus);
        out.putAll(result);
        return out;
    }

    /** Toplam süre bütçesi. */
    final class Budget {
        private final long t0;
        Budget(long t0) { this.t0 = t0; }
        boolean left() { return (nanos.get() - t0) / 1_000_000L < TOTAL_BUDGET_MS; }
    }

    private List<Map<String, Object>> policies(Budget budget, Map<String, String> status, Map<String, Double> purge,
                                               Map<String, String> purgeMethod, Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<RetentionPolicy> list = new ArrayList<>();
        for (RetentionPolicy p : RetentionCatalog.ALL) {
            if (p.mode() == RetentionPolicy.Mode.AGE && p.timeColumn() != null) list.add(p);
        }
        Map<String, List<String>> hist = Map.of();
        if (budget.left()) {
            try {
                List<String[]> pairs = new ArrayList<>();
                for (RetentionPolicy p : list) pairs.add(new String[]{p.table(), p.timeColumn()});
                hist = source.histograms(pairs);
            } catch (RuntimeException e) {
                log.debug("Analiz: histogram okunamadı: {}", e.toString());
            }
        }
        boolean complete = true;
        for (RetentionPolicy p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            int days = retention.effectiveDays(p);
            m.put("policy_id", p.id());
            m.put("table", p.table());
            m.put("days", days);
            m.put("min_days", p.minDays());
            m.put("configurable", p.configurable());
            m.put("data_class", p.dataClass().name());
            String oldest = null;
            if (budget.left()) {
                oldest = source.oldest(p);
                if (oldest == null) m.put("oldest_unknown", true);
            } else {
                complete = false;
                m.put("oldest_unknown", true);
                m.put("skipped", true);
            }
            m.put("oldest_at", oldest);
            Double age = DbGrowthService.ageDaysOf(oldest, now);
            m.put("oldest_age_days", age == null ? null : Math.round(age * 10.0) / 10.0);
            boolean plain = "{t}".equals(p.where());
            m.put("behind", age != null && plain && days > 0 && age > days + DbGrowthAdvisor.BEHIND_SLACK_DAYS);
            Integer suggested = p.configurable() && days > p.minDays() ? DbGrowthAdvisor.suggestDays(days, p.minDays()) : null;
            m.put("suggested_days", suggested);
            List<String> bounds = hist.get(p.table() + "." + p.timeColumn());
            if (suggested != null && bounds != null) {
                Instant cutoff = DbGrowthService.cutoffInstant(DbGrowthService.cutoffFor(p, suggested, now));
                Double frac = DbGrowthForecast.histogramFractionBefore(bounds, cutoff);
                if (frac != null) {
                    purge.put(p.id(), frac);
                    purgeMethod.put(p.id(), "histogram");
                    m.put("purge_fraction", Math.round(frac * 1000.0) / 1000.0);
                }
            }
            out.add(m);
        }
        status.put("policies", complete ? "ok" : "skipped");
        return out;
    }

    private List<Map<String, Object>> vacuum(Budget budget, Map<String, String> status, List<Finding> findings) {
        if (!budget.left()) return null;
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            List<DbSizeSource.VacuumStat> stats = new ArrayList<>(source.vacuumStats());
            stats.sort(Comparator.comparingLong((DbSizeSource.VacuumStat v) -> -v.deadRows()));
            for (DbSizeSource.VacuumStat v : stats) {
                Finding f = DbGrowthAdvisor.deadTuples(v.table(), v.liveRows(), v.deadRows(), v.lastAutovacuum(), v.totalBytes());
                if (f != null) findings.add(f);
                if (out.size() < 20 && v.deadRows() > 0) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table", v.table());
                    m.put("live_rows", v.liveRows());
                    m.put("dead_rows", v.deadRows());
                    m.put("dead_pct", Math.round(1000.0 * v.deadRows() / Math.max(1, v.deadRows() + v.liveRows())) / 10.0);
                    m.put("last_autovacuum", v.lastAutovacuum());
                    m.put("last_vacuum", v.lastVacuum());
                    m.put("last_autoanalyze", v.lastAutoanalyze());
                    out.add(m);
                }
            }
            status.put("vacuum", "ok");
            return out;
        } catch (RuntimeException e) {
            status.put("vacuum", "failed");
            log.debug("Analiz: vacuum istatistikleri okunamadı: {}", e.toString());
            return null;
        }
    }

    private List<Map<String, Object>> hotMonitors(Budget budget, Map<String, String> status, List<Finding> findings, Instant now) {
        String since = ISO.format(now.minus(Duration.ofHours(24)));
        List<Object[]> all = new ArrayList<>();
        boolean failed = false, skipped = false;
        for (String[] t : HOT_TABLES) {
            if (!budget.left()) { skipped = true; break; }
            try {
                for (long[] r : source.hotMonitors(t[0], since, HOT_LIMIT)) all.add(new Object[]{t[0], t[1], r[0], r[1]});
            } catch (RuntimeException e) {
                failed = true;
                log.debug("Analiz: {} son 24 saat sayımı okunamadı: {}", t[0], e.toString());
            }
        }
        all.sort(Comparator.comparingLong((Object[] r) -> -(Long) r[3]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : all) {
            if (out.size() >= HOT_LIMIT) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r[0]);
            m.put("type", r[1]);
            m.put("monitor_id", r[2]);
            m.put("rows_24h", r[3]);
            out.add(m);
            findings.add(DbGrowthAdvisor.hotMonitor((String) r[0], (String) r[1], (Long) r[2], (Long) r[3]));
        }
        status.put("hot_monitors", failed ? "failed" : skipped ? "skipped" : "ok");
        return out;
    }

    private Map<String, Object> indexBloat(Budget budget, Map<String, String> status, List<Finding> findings) {
        if (!budget.left()) return null;
        try {
            List<String> tables = new ArrayList<>();
            for (DbSizeSource.VacuumStat v : source.vacuumStats()) tables.add(v.table());
            List<DbSizeSource.BloatInfo> all = source.bloat(tables);
            int unknown = 0;
            List<Map<String, Object>> bloated = new ArrayList<>();
            List<DbSizeSource.BloatInfo> sorted = new ArrayList<>(all);
            sorted.sort(Comparator.comparingLong((DbSizeSource.BloatInfo b) -> -(b.bytes() - Math.max(0, b.expectedBytes()))));
            for (DbSizeSource.BloatInfo b : sorted) {
                if (b.expectedBytes() < 0) { unknown++; continue; }
                if (!b.bloated()) continue;
                boolean nightly = com.sitemonitor.service.schema.IndexBloatReadPath.maintainedNightly(b.table());
                findings.add(DbGrowthAdvisor.indexBloat(b.table(), b.index(), b.bytes(), b.expectedBytes(), nightly));
                if (bloated.size() < 30) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table", b.table());
                    m.put("index", b.index());
                    m.put("bytes", b.bytes());
                    m.put("expected_bytes", b.expectedBytes());
                    m.put("maintained", nightly);
                    bloated.add(m);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("checked", all.size());
            out.put("unknown", unknown);
            out.put("bloated", bloated);
            status.put("index_bloat", "ok");
            return out;
        } catch (RuntimeException e) {
            status.put("index_bloat", "failed");
            log.debug("Analiz: dizin şişmesi okunamadı: {}", e.toString());
            return null;
        }
    }

    private List<Map<String, Object>> unusedIndexes(Budget budget, Map<String, String> status, List<Finding> findings) {
        if (!budget.left()) return null;
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (DbSizeSource.IndexInfo i : source.unusedIndexes(UNUSED_INDEX_MIN_BYTES)) {
                findings.add(DbGrowthAdvisor.unusedIndex(i.table(), i.index(), i.bytes()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", i.table());
                m.put("index", i.index());
                m.put("bytes", i.bytes());
                out.add(m);
            }
            status.put("unused_indexes", "ok");
            return out;
        } catch (RuntimeException e) {
            status.put("unused_indexes", "failed");
            log.debug("Analiz: kullanılmayan dizinler okunamadı: {}", e.toString());
            return null;
        }
    }

    /** Sonucu saklar ve budar (en yeni {@value #KEEP_RUNS}, {@value #KEEP_DAYS} gün). Yazılamazsa null — sonuç yine döner. */
    private Long store(Instant start, Instant end, long ms, String actor, String pod, String status, Map<String, Object> result) {
        try {
            String body = json.writeValueAsString(result);
            jdbc.update(SQL_INSERT, ISO.format(start), ISO.format(end), ms, trim(actor, 120), trim(pod, 120), status, body);
            Long id = jdbc.queryForObject("SELECT MAX(id) FROM db_health_analysis_runs", Long.class);
            jdbc.update("DELETE FROM db_health_analysis_runs WHERE started_at < ?", ISO.format(start.minus(Duration.ofDays(KEEP_DAYS))));
            List<Long> nth = jdbc.query(SQL_NTH_ID, (rs, i) -> rs.getLong(1));
            if (!nth.isEmpty()) jdbc.update("DELETE FROM db_health_analysis_runs WHERE id < ?", nth.get(0));
            return id;
        } catch (Exception e) {
            log.warn("Veritabanı sağlık analizi sonucu saklanamadı: {}", e.toString());
            return null;
        }
    }

    /** En yeni analiz (tam sonuç); hiç yoksa null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> latest() {
        List<Map<String, Object>> rows = jdbc.query(SQL_LATEST, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", rs.getLong(1));
            m.put("started_at", rs.getString(2));
            m.put("finished_at", rs.getString(3));
            m.put("duration_ms", rs.getObject(4) instanceof Number n ? n.longValue() : null);
            m.put("actor", rs.getString(5));
            m.put("pod", rs.getString(6));
            m.put("status", rs.getString(7));
            String body = rs.getString(8);
            if (body != null) {
                try {
                    m.putAll(json.readValue(body, Map.class));
                } catch (Exception e) {
                    m.put("unreadable", true);
                }
            }
            return m;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** En yeni analizin özeti (görünüm başlığı); okunamazsa / yoksa null. */
    public Map<String, Object> latestSummary() {
        try {
            List<Map<String, Object>> rows = jdbc.query(SQL_LATEST_SUMMARY, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("run_id", rs.getLong(1));
                m.put("started_at", rs.getString(2));
                m.put("actor", rs.getString(3));
                m.put("status", rs.getString(4));
                return m;
            });
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
