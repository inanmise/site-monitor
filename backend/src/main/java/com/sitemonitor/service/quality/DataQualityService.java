package com.sitemonitor.service.quality;

import com.sitemonitor.service.quality.DataQualityEvaluator.Bucket;
import com.sitemonitor.service.quality.DataQualityEvaluator.Evaluation;
import com.sitemonitor.service.quality.DataQualityEvaluator.Finding;
import com.sitemonitor.service.quality.DataQualityScore.Count;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Takım VERİ KALİTESİ PUANI (2026-10-10) — kurum geneli hesap, kısa bellek, görüş kapsamına göre süzülmüş görünümler.
 *
 * <p><b>Maliyet.</b> Hesap kurum geneli TEK kez yapılır ({@link DataQualitySource}: sabit sayıda sorgu, takım/satır
 * başına sorgu yok) + eğilim için TEK sorgu, ve {@code site.monitor.data-quality.cache-ms} (60 sn) paylaşılır; aynı
 * anda gelen istekler tek hesabı bekler. {@code fresh=true} (Yenile) belleği en çok {@value #FRESH_MIN_MS} ms'de bir
 * atlar. Görünüm süzmesi bellekteki sonuç üzerinde yapılır (istek başına sorgu yok).
 *
 * <p><b>Kapsam</b> çağıranın {@link Viewer}'ıyla verilir (denetleyici oturumdan kurar): takım satırları yalnız
 * görebildiği takımlar; Sahipsiz kovası yalnız tüm izlemeyi görenlere (global görüntüleyici / 7/24 operatörü). Kurum
 * puanı herkese bir KIYAS sayısı olarak gösterilir (ad/kayıt içermez). Yanıtta kullanıcı kimliği YOK.
 *
 * <p><b>Yeniden kullanım:</b> {@link #digest()} aylık yönetici özeti gibi başka raporlara kurum puanı + takım
 * puanları + en sık sorunları süzülmemiş verir (görünürlüğü çağıran uygular).
 */
@Slf4j
@Service
public class DataQualityService {

    /** Eğilim çizgisi uzunluğu (gün). */
    public static final int TREND_DAYS = 30;
    /** Günlük görüntü saklama (gün) — yazımda daha eskisi silinir (BOUNDED). */
    public static final int TREND_KEEP_DAYS = 120;
    /** {@code fresh} isteği belleği en sık bu aralıkla atlar. */
    static final long FRESH_MIN_MS = 5_000L;
    /** Takım kartındaki "en çok puan kaybettiren" sorun sayısı. */
    static final int TOP_ISSUES = 3;
    /** Kurum / Sahipsiz kovalarının {@code team_key}'i. */
    public static final long ORG_KEY = 0L;
    public static final long UNASSIGNED_KEY = -1L;

    static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL_TREND = "SELECT snap_day, team_key, score FROM data_quality_daily WHERE snap_day >= ? "
            + "ORDER BY snap_day";
    static final String SQL_EXISTS = "SELECT COUNT(*) FROM data_quality_daily WHERE snap_day = ? AND team_key = 0";
    static final String SQL_INSERT = "INSERT INTO data_quality_daily(snap_day, team_key, score, findings, items, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    static final String SQL_PRUNE = "DELETE FROM data_quality_daily WHERE snap_day < ?";

    private final DataQualitySource source;
    private final JdbcTemplate jdbc;
    private final long cacheMs;
    /** Test kancası. */
    Clock clock = Clock.systemUTC();

    private final Object computeLock = new Object();
    private volatile Memo memo;

    /** Bellekteki hesap: an, sonuç, kova anahtarı → eğilim noktaları. */
    record Memo(long atMs, Evaluation eval, Map<Long, List<TrendPoint>> trend) {}

    /** Günlük eğilim noktası. */
    public record TrendPoint(String day, Integer score) {}

    /**
     * Görüntüleyen: takım görünürlüğü, Sahipsiz kovası ve satır başına "düzeltebilir mi" bayrakları. Denetleyici
     * oturumdan kurar ({@code SessionScope}); testler doğrudan verir.
     */
    public record Viewer(boolean seesAll, boolean globalAdmin, Predicate<Long> canSeeTeam,
                         Predicate<Long> canEditInventory, Predicate<Long> canOperateMonitor,
                         Predicate<Long> canManageTeam) {}

    public DataQualityService(DataQualitySource source, JdbcTemplate jdbc,
                              @Value("${site.monitor.data-quality.cache-ms:60000}") long cacheMs) {
        this.source = source;
        this.jdbc = jdbc;
        this.cacheMs = Math.max(0, cacheMs);
    }

    // ── Hesap + bellek ───────────────────────────────────────────────────────────────────────────────

    /** Bellekteki (ya da yeni) sonuç. */
    public Evaluation evaluation(boolean fresh) {
        return current(fresh).eval();
    }

    Memo current(boolean fresh) {
        long now = clock.millis();
        Memo m = memo;
        if (m != null && usable(m, now, fresh)) return m;
        synchronized (computeLock) {
            m = memo;
            now = clock.millis();
            if (m != null && usable(m, now, fresh)) return m;
            Instant at = clock.instant();
            Evaluation eval = DataQualityEvaluator.evaluate(source.load(at), at);
            Memo next = new Memo(now, eval, trend(at));
            memo = next;
            return next;
        }
    }

    private boolean usable(Memo m, long now, boolean fresh) {
        long age = now - m.atMs();
        if (age < 0) return false;
        if (fresh) return age < FRESH_MIN_MS;
        return age < cacheMs;
    }

    /** Belleği düşür (günlük görüntü yazıldıktan sonra eğilim yeni noktayı göstersin). */
    void invalidate() {
        memo = null;
    }

    private Map<Long, List<TrendPoint>> trend(Instant at) {
        Map<Long, List<TrendPoint>> out = new HashMap<>();
        try {
            String since = LocalDate.ofInstant(at, IST).minusDays(TREND_DAYS - 1L).toString();
            jdbc.query(SQL_TREND, rs -> {
                Object s = rs.getObject(3);
                out.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>())
                        .add(new TrendPoint(rs.getString(1), s == null ? null : ((Number) s).intValue()));
            }, since);
        } catch (Exception e) {
            log.debug("Veri kalitesi eğilimi okunamadı (tablo henüz yok?): {}", e.getMessage());
        }
        return out;
    }

    // ── Özet görünümü ────────────────────────────────────────────────────────────────────────────────

    /** {@code GET /api/data-quality}: kurum puanı + görünür takımların sıralaması (+ Sahipsiz özeti). */
    public Map<String, Object> summary(Viewer v, boolean fresh) {
        Memo m = current(fresh);
        Evaluation e = m.eval();
        String today = LocalDate.ofInstant(e.at(), IST).toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", ISO.format(e.at()));
        out.put("org", orgView(e.org(), m.trend().get(ORG_KEY), today));

        List<Map<String, Object>> teams = new ArrayList<>();
        for (Bucket b : e.teams()) {
            if (!v.canSeeTeam().test(b.teamId())) continue;
            Map<String, Object> t = bucketHead(b, m.trend().get(b.teamId()), today);
            t.put("top_issues", topIssues(b));
            teams.add(t);
        }
        out.put("teams", teams);
        if (v.seesAll()) {
            Bucket u = e.unassigned();
            Map<String, Object> um = new LinkedHashMap<>();
            um.put("findings", u.findingCount());
            um.put("items", u.items());
            um.put("rules", ruleRows(u, false, null));
            out.put("unassigned", um);
        }
        out.put("notes", notes(e));
        out.put("catalog", catalog());
        out.put("config", config());
        return out;
    }

    /**
     * {@code GET /api/data-quality/teams/{id}}: düzeltme listesi. {@code key}: takım kimliği ya da {@code unassigned}.
     * Görünmeyen takım / Sahipsiz kovasını göremeyen için {@code null} (denetleyici 404 — varlığı bile söylenmez).
     */
    public Map<String, Object> teamDetail(Viewer v, String key, boolean fresh) {
        Memo m = current(fresh);
        Evaluation e = m.eval();
        String today = LocalDate.ofInstant(e.at(), IST).toString();
        Bucket b;
        boolean unassigned = "unassigned".equals(key);
        if (unassigned) {
            if (!v.seesAll()) return null;
            b = e.unassigned();
        } else {
            Long id = parseId(key);
            if (id == null || !v.canSeeTeam().test(id)) return null;
            b = e.team(id);
            if (b == null) return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", ISO.format(e.at()));
        out.put("unassigned", unassigned);
        Map<String, Object> head = unassigned ? new LinkedHashMap<>() : bucketHead(b, m.trend().get(b.teamId()), today);
        if (unassigned) {
            head.put("findings", b.findingCount());
            head.put("items", b.items());
        }
        out.put("team", head);
        out.put("rules", ruleRows(b, true, v));
        out.put("notes", notes(e));
        out.put("org_score", e.org().score());
        return out;
    }

    // ── Yeniden kullanılabilir özet (yönetici özeti vb.) ─────────────────────────────────────────────

    public record TeamScore(long teamId, String teamName, Integer score, String band, int findings) {}

    public record IssueCount(String code, int failing, int eligible) {}

    /** Süzülmemiş kurum özeti — kurum puanı, takım puanları (kötüden iyiye), kurumda en çok kusurlu kurallar. */
    public record Digest(Instant generatedAt, Integer orgScore, String orgBand, int orgFindings,
                         List<TeamScore> teams, List<IssueCount> topIssues) {}

    public Digest digest() {
        Evaluation e = evaluation(false);
        List<TeamScore> teams = new ArrayList<>();
        for (Bucket b : e.teams()) {
            Integer s = b.score();
            teams.add(new TeamScore(b.teamId(), b.teamName(), s, DataQualityScore.band(s).name(), b.findingCount()));
        }
        teams.sort(Comparator.comparing((TeamScore t) -> t.score() == null ? Integer.MAX_VALUE : t.score())
                .thenComparing(t -> t.teamName() == null ? "" : t.teamName()));
        List<IssueCount> issues = new ArrayList<>();
        for (Map.Entry<DataQualityRule, Count> en : e.org().counts().entrySet()) {
            if (en.getValue().failing() > 0)
                issues.add(new IssueCount(en.getKey().name(), en.getValue().failing(), en.getValue().eligible()));
        }
        issues.sort(Comparator.comparingInt(IssueCount::failing).reversed().thenComparing(IssueCount::code));
        Integer org = e.org().score();
        return new Digest(e.at(), org, DataQualityScore.band(org).name(), e.org().findingCount(),
                List.copyOf(teams), List.copyOf(issues.size() > 5 ? issues.subList(0, 5) : issues));
    }

    // ── Günlük görüntü (eğilim) ──────────────────────────────────────────────────────────────────────

    /** Bugünün kurum satırı yazılmış mı (tek küçük sorgu; iş kilit almadan çıkar). */
    public boolean snapshotWritten(Instant now) {
        String day = LocalDate.ofInstant(now, IST).toString();
        Integer n = jdbc.queryForObject(SQL_EXISTS, Integer.class, day);
        return n != null && n > 0;
    }

    /**
     * Bugünün görüntüsünü yaz (gün × kova tek satır; ikinci yazım no-op) ve {@value #TREND_KEEP_DAYS} günden eskiyi sil.
     * Dönüş: eklenmeye çalışılan satır sayısı.
     */
    public int writeDailySnapshot(Instant now) {
        String day = LocalDate.ofInstant(now, IST).toString();
        Evaluation e = evaluation(true);
        String created = ISO.format(now);
        List<Object[]> rows = new ArrayList<>();
        rows.add(row(day, ORG_KEY, e.org(), created));
        rows.add(row(day, UNASSIGNED_KEY, e.unassigned(), created));
        for (Bucket b : e.teams()) rows.add(row(day, b.teamId(), b, created));
        jdbc.batchUpdate(SQL_INSERT, rows);
        String cutoff = LocalDate.ofInstant(now, IST).minusDays(TREND_KEEP_DAYS).toString();
        int pruned = jdbc.update(SQL_PRUNE, cutoff);
        invalidate();
        log.info("Veri kalitesi günlük görüntüsü: {} kova yazıldı ({}), {} eski satır silindi", rows.size(), day, pruned);
        return rows.size();
    }

    private static Object[] row(String day, long key, Bucket b, String created) {
        return new Object[]{day, key, b.score(), b.findingCount(), b.items(), created};
    }

    // ── Görünüm parçaları ────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> orgView(Bucket org, List<TrendPoint> trend, String today) {
        Map<String, Object> o = new LinkedHashMap<>();
        Integer s = org.score();
        o.put("score", s);
        o.put("band", DataQualityScore.band(s).name());
        o.put("findings", org.findingCount());
        o.put("items", org.items());
        o.put("delta_7d", delta(s, trend, today));
        o.put("trend", trendView(trend));
        o.put("rules", ruleRows(org, false, null));
        return o;
    }

    private Map<String, Object> bucketHead(Bucket b, List<TrendPoint> trend, String today) {
        Map<String, Object> t = new LinkedHashMap<>();
        Integer s = b.score();
        t.put("id", b.teamId());
        t.put("name", b.teamName());
        t.put("score", s);
        t.put("band", DataQualityScore.band(s).name());
        t.put("findings", b.findingCount());
        t.put("items", b.items());
        t.put("delta_7d", delta(s, trend, today));
        t.put("trend", trendView(trend));
        return t;
    }

    /** Puanın 7 gün önceki görüntüye göre farkı; o günün görüntüsü yoksa null. */
    static Integer delta(Integer score, List<TrendPoint> trend, String today) {
        if (score == null || trend == null || today == null) return null;
        String weekAgo;
        try {
            weekAgo = LocalDate.parse(today).minusDays(7).toString();
        } catch (Exception e) {
            return null;
        }
        for (TrendPoint p : trend) {
            if (weekAgo.equals(p.day()) && p.score() != null) return score - p.score();
        }
        return null;
    }

    private static List<Map<String, Object>> trendView(List<TrendPoint> trend) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (trend == null) return out;
        for (TrendPoint p : trend) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", p.day());
            m.put("score", p.score());
            out.add(m);
        }
        return out;
    }

    /** En çok puan kaybettiren ilk {@value #TOP_ISSUES} kural (kusuru olanlar). */
    private static List<Map<String, Object>> topIssues(Bucket b) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataQualityRule r : DataQualityRule.values()) {
            Count c = b.counts().get(r);
            if (c == null || c.failing() == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.name());
            m.put("severity", r.severity.name());
            m.put("failing", c.failing());
            m.put("eligible", c.eligible());
            m.put("points", DataQualityScore.pointsLost(b.counts(), r));
            rows.add(m);
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("points")).doubleValue())
                .thenComparing(m -> String.valueOf(m.get("code"))));
        return rows.size() > TOP_ISSUES ? new ArrayList<>(rows.subList(0, TOP_ISSUES)) : rows;
    }

    /**
     * Uygulanan kurallar katalog sırasıyla. {@code withItems}: düzeltme kalemleri (kural başına en çok
     * {@value DataQualityEvaluator#MAX_ITEMS_PER_RULE}; fazlası {@code truncated}).
     */
    private static List<Map<String, Object>> ruleRows(Bucket b, boolean withItems, Viewer v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataQualityRule r : DataQualityRule.values()) {
            Count c = b.counts().get(r);
            if (c == null || !c.applies()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.name());
            m.put("scope", r.scope.name());
            m.put("severity", r.severity.name());
            m.put("weight", r.weight());
            m.put("eligible", c.eligible());
            m.put("failing", c.failing());
            m.put("health", Math.round(c.health() * 1000.0) / 1000.0);
            m.put("points", DataQualityScore.pointsLost(b.counts(), r));
            if (withItems) {
                List<Finding> all = b.findings().getOrDefault(r, List.of());
                int cap = DataQualityEvaluator.MAX_ITEMS_PER_RULE;
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 0; i < all.size() && i < cap; i++) items.add(item(all.get(i), v));
                m.put("items", items);
                m.put("truncated", Math.max(0, all.size() - cap));
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> item(Finding f, Viewer v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", f.kind());
        m.put("type", f.type());
        m.put("id", f.id());
        m.put("name", f.name());
        m.put("target", f.target());
        m.put("team_id", f.teamId());
        if (f.manual()) m.put("manual", true);
        // "facts" (detail DEĞİL): "detail" UserRefWire'ın JSON-metni alanıdır — burada kullanıcı kimliği yok,
        // kapsamlı görüntüleyicinin yanıtı boşuna ağaç dönüşümüne girmesin.
        m.put("facts", f.detail());
        m.put("can_edit", canEdit(f, v));
        return m;
    }

    /** Kalemi düzeltme ekranı açılabilir mi — kaydın KENDİ yazma kapısı (sunucu yine kendi ucunda uygular). */
    static boolean canEdit(Finding f, Viewer v) {
        if (v == null) return false;
        if (v.globalAdmin()) return true;
        Long team = f.teamId();
        if (team == null) return false;
        return switch (f.kind()) {
            case "inventory" -> v.canEditInventory().test(team);
            case "monitor" -> v.canOperateMonitor().test(team);
            case "team" -> v.canManageTeam().test(team);
            default -> false;
        };
    }

    private static List<Map<String, Object>> notes(Evaluation e) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataQualityEvaluator.Note n : e.notes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", n.code());
            m.put("params", n.params());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataQualityRule r : DataQualityRule.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.name());
            m.put("scope", r.scope.name());
            m.put("severity", r.severity.name());
            m.put("weight", r.weight());
            m.put("ownership", r.ownership);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("band_excellent", DataQualityScore.EXCELLENT_MIN);
        m.put("band_good", DataQualityScore.GOOD_MIN);
        m.put("band_fair", DataQualityScore.FAIR_MIN);
        m.put("paused_long_days", DataQualityEvaluator.PAUSED_LONG_DAYS);
        m.put("repeated_errors", DataQualityEvaluator.REPEATED_ERRORS);
        m.put("error_window_days", DataQualitySource.ERROR_WINDOW_DAYS);
        return m;
    }

    private static Long parseId(String key) {
        if (key == null || key.isBlank() || key.length() > 19) return null;
        try {
            return Long.valueOf(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
