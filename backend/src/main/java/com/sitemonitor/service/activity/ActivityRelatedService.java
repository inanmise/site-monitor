package com.sitemonitor.service.activity;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.util.Msg;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "Aynı anda ne oldu?" — KÖK NEDEN İPUÇLARI (2026-11-05, aktivite zenginleştirmesi F):
 * {@code GET /api/activity/{id}/related?window=120}. Bir aktivitenin zamanı ± {@code window} saniyesindeki BAŞARISIZ
 * kontrol satırlarını toplar; neden / host / takım dağılımını, şüpheli neden kodlarını ({@link #SUSPECT_CODES}) ve satırın
 * izlemesine ait olguları ({@link #FACT_CODES}) döner. "Başarısız" = zaman çizelgesinin KESİNTİ kuralı (hata avı O1,
 * 2026-11-05 — tek kural: {@link ActivityTimelineService#isOutage} / {@link ActivityTimelineService#OUTAGE_SQL}): ERROR /
 * TIMEOUT ve HTTP / Keyword WARNING (ok=false + HTTP kodu — 503'ler) ile senaryo FAIL (NO_CHECKS hariç). Eskiden yalnız
 * ERROR / TIMEOUT sayılıyordu; en yaygın HTTP arızasında (aynı WAF arkasında 5 izleme 503) panel "başka hata yok" diyordu.
 *
 * <p><b>Kapı = {@code GET /api/activity/{id}} ile aynı</b> (satırın takımı {@code canViewMonitoring}; değilse 404).
 * Pencere YALNIZ oturumun okuma kapsamındaki satırlardan kurulur: başka takımın satırı, sayısı ya da takım adı hiçbir alanda
 * dönmez — şüpheli eşikleri de görünen satırlarla hesaplanır (gizli bir takımın durumu "yaygın kesinti" diye sızmasın).
 *
 * <p><b>Sayılar bu satır HARİÇ</b> (2026-11-05 inceleme): {@code total} / {@code monitors} / {@code by_*} penceredeki BAŞKA
 * hatalı kontrol satırlarıdır ({@code total = 0} → "başka hatalı kontrol yok"); {@code window_total} bu satırı da sayar
 * ({@code anchor_in_window}). Şüpheli kodlar tüm satırlardan (bu satır dahil) hesaplanır.
 *
 * <p><b>Sorgu sayısı sabit</b>, satır başına sorgu yok: satırın kendisi (1) + pencere (1) + izlemenin olguları (1, satır
 * başarısızsa seri başlangıcı için +1) + görünen takım adları (≤ 1) + "hâlâ hata veriyor" adayı için izlemenin künyesi
 * (≤ 2 — envanter türevi Port / DNS'te envanter de okunur): ≤ 7.
 *
 * <p><b>Eşikler bu sınıfta sabittir</b>; kodlar KALICI (ön yüz {@code act.rel.suspect.<KOD>} / {@code act.rel.fact.<KOD>}
 * anahtarlarına çevirir; JS listesi bu listelerle testte eşitlenir).
 */
@Service
public class ActivityRelatedService {

    /** Şüpheli neden kodları (sıra = gösterim önceliği). */
    public static final List<String> SUSPECT_CODES =
            List.of("DNS_WIDE", "NETWORK_WIDE", "TLS_WIDE", "PROXY_WIDE", "SAME_HOST", "SAME_TEAM", "SINGLE");
    /** Olgu kodları (sıra = gösterim sırası). */
    public static final List<String> FACT_CODES =
            List.of("FIRST_FAILURE_IN_DAYS", "FAILURES_24H", "TIMEOUTS_24H", "LAST_SUCCESS", "RECOVERED_AFTER", "STILL_FAILING");

    static final int WINDOW_DEFAULT_S = 120, WINDOW_MIN_S = 30, WINDOW_MAX_S = 900;
    /** {@code *_WIDE}: aynı sınıftan en az bu kadar izleme VE penceredeki başarısız izlemelerin en az bu yüzdesi. */
    static final int WIDE_MIN_MONITORS = 3, WIDE_MIN_SHARE_PCT = 50;
    /** {@code SAME_HOST}: aynı host'ta (bu izleme dahil) en az bu kadar başarısız izleme. */
    static final int SAME_HOST_MIN_MONITORS = 2;
    /** {@code SAME_TEAM}: aynı takımda (bu izleme dahil) en az bu kadar başarısız izleme. */
    static final int SAME_TEAM_MIN_MONITORS = 3;
    static final int MAX_ITEMS = 50, MAX_WINDOW_ROWS = 2_000, MAX_BUCKETS = 20;
    /** Olgular için geriye / ileriye bakış (gün); izleme kimliği taşımayan eski satırlarda daha kısa (hedef eşleşmesi pahalı). */
    static final int LOOKBACK_DAYS = 30, LEGACY_LOOKBACK_DAYS = 7;
    private static final List<String> WIDE_CLASSES =
            List.of(ActivitySignals.DNS, ActivitySignals.NETWORK, ActivitySignals.TLS, ActivitySignals.PROXY);

    private final JdbcTemplate jdbc;
    /** İzlemenin GÜNCEL künyesi (takım + etkinlik) — {@code resolve} kuralı tek kaynak (hata avı D3). */
    private final ActivityTimelineService monitors;
    /** "Şimdi" (testlerde sabitlenir) — "hâlâ hata veriyor" yalnız bakış penceresi bugüne uzanırken söylenir. */
    java.util.function.Supplier<Instant> clock = Instant::now;

    public ActivityRelatedService(JdbcTemplate jdbc) {
        this(jdbc, new ActivityTimelineService(jdbc));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ActivityRelatedService(JdbcTemplate jdbc, ActivityTimelineService monitors) {
        this.jdbc = jdbc;
        this.monitors = monitors;
    }

    /** Satır + hata nedeni (kolon varlıkta henüz yoksa bile adla okunur). */
    record Rel(ActivityLog log, String failureReason) {
        String key() {
            ActivityLog a = log;
            return a.getMonitorType() + ":" + (a.getMonitorId() != null ? String.valueOf(a.getMonitorId()) : "t:" + a.getTarget());
        }
        String host() { return ActivitySignals.hostOf(log.getMonitorType(), log.getTarget()); }
        String reason() { return ActivitySignals.reasonKey(failureReason, log.getErrorClass()); }
        String cls() { return ActivitySignals.classOf(failureReason, log.getErrorClass()); }
        boolean failing() {
            return ActivityTimelineService.isOutage(log.getMonitorType(), log.getAction(), log.getResultStatus(), failureReason,
                    log.getResultSummary());
        }
        long epoch() { return ActivityTimelineService.epoch(log.getActivityTime()); }
    }

    /** Sonuç: gövde (öğeler hariç) + öğeler (denetleyici kendi satır biçimine çevirir). */
    public record Result(Map<String, Object> body, List<ActivityLog> items) {}

    /**
     * @param canView  satırın / takımın okunabilir olup olmadığı (oturumun {@code canViewMonitoring}'i)
     * @param scoped   oturumun satır kapsamı var mı (liste ucuyla aynı); {@code scope} = görünen takımlar
     */
    public Result related(long id, Integer windowParam, boolean scoped, List<Long> scope, Predicate<Long> canView) {
        int window = windowParam == null ? WINDOW_DEFAULT_S : windowParam;
        if (window < WINDOW_MIN_S || window > WINDOW_MAX_S) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("window", Msg.t(
                    "Pencere " + WINDOW_MIN_S + " ile " + WINDOW_MAX_S + " saniye arasında olmalı.",
                    "The window must be between " + WINDOW_MIN_S + " and " + WINDOW_MAX_S + " seconds."));
        }
        List<Rel> one = jdbc.query("SELECT * FROM activity_log WHERE id = ?", ActivityRelatedService::map, id);
        if (one.isEmpty() || !canView.test(one.get(0).log().getTeamId())) throw notFound();
        Rel anchor = one.get(0);
        Instant at = Instant.ofEpochSecond(anchor.epoch());

        // ── Pencere (görünen kapsam) ──
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM activity_log WHERE activity_time >= ? AND activity_time <= ?"
                + " AND " + ActivityTimelineService.OUTAGE_SQL + " AND action IN ('SCHEDULED_CHECK', 'MANUAL_CHECK')");
        args.add(ActivityFilter.iso(at.minusSeconds(window)));
        args.add(ActivityFilter.iso(at.plusSeconds(window)));
        appendScope(sql, args, scoped, scope);
        sql.append(" ORDER BY activity_time DESC, id DESC LIMIT ?");
        args.add(MAX_WINDOW_ROWS + 1);
        List<Rel> rows = new ArrayList<>(jdbc.query(sql.toString(), ActivityRelatedService::map, args.toArray()));
        boolean truncated = rows.size() > MAX_WINDOW_ROWS;
        if (truncated) rows = new ArrayList<>(rows.subList(0, MAX_WINDOW_ROWS));

        Map<String, Object> body = analyze(anchor, rows, window, truncated);

        // ── Takım adları (yalnız görünen satırların takımları) ──
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byTeam = (List<Map<String, Object>>) body.get("by_team");
        Set<Long> teamIds = new LinkedHashSet<>();
        for (Map<String, Object> t : byTeam) if (t.get("team_id") != null) teamIds.add((Long) t.get("team_id"));
        if (anchor.log().getTeamId() != null) teamIds.add(anchor.log().getTeamId());
        Map<Long, String> names = teamNames(teamIds);
        for (Map<String, Object> t : byTeam) t.put("team_name", names.get((Long) t.get("team_id")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suspects = (List<Map<String, Object>>) body.get("suspects");
        for (Map<String, Object> s : suspects) {
            if ("SAME_TEAM".equals(s.get("code"))) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) s.get("params");
                p.put("team_name", names.get((Long) p.get("team_id")));
            }
        }

        body.put("facts", facts(anchor, scoped, scope, canView));

        // Öğeler: bu satır hariç, zamana yakınlığa göre
        long t0 = anchor.epoch();
        List<Rel> near = new ArrayList<>();
        for (Rel r : rows) if (r.log().getId() != null && !r.log().getId().equals(anchor.log().getId())) near.add(r);
        near.sort(Comparator.<Rel>comparingLong(r -> Math.abs(r.epoch() - t0)).thenComparing(r -> -r.log().getId()));
        List<ActivityLog> items = new ArrayList<>();
        for (int i = 0; i < near.size() && i < MAX_ITEMS; i++) items.add(near.get(i).log());
        return new Result(body, items);
    }

    // ── Analiz (saf; birim testi) ────────────────────────────────────────────────────────────────────────

    /** Pencere satırlarından dağılımlar + şüpheliler (takım adları sonra eklenir). */
    static Map<String, Object> analyze(Rel anchor, List<Rel> rows, int window, boolean truncated) {
        Instant at = Instant.ofEpochSecond(anchor.epoch());
        Map<String, Integer> reasonRows = new HashMap<>(), hostRows = new HashMap<>();
        Map<Long, Integer> teamRows = new HashMap<>();
        Map<String, Set<String>> reasonMon = new HashMap<>(), hostMon = new HashMap<>(), classMon = new HashMap<>();
        Map<Long, Set<String>> teamMon = new HashMap<>();
        Set<String> monitors = new HashSet<>();
        boolean nullTeam = false;
        int nullTeamRows = 0;
        Set<String> nullTeamMon = new HashSet<>();
        // Şüpheliler penceredeki TÜM hatalı satırlardan (bu satır dahil); dağılımlar ve sayılar yalnız BAŞKA satırlardan
        // (inceleme ön yüz O3: "başka hatalı kontrol yok" durumu üretilebilsin — öğeler de bu satırı içermez).
        Map<String, Set<String>> hostAll = new HashMap<>();
        Map<Long, Set<String>> teamAll = new HashMap<>();
        Set<String> monitorsAll = new HashSet<>();
        Long anchorId = anchor.log().getId();
        boolean anchorIn = false;
        int others = 0;
        for (Rel r : rows) {
            String key = r.key();
            monitorsAll.add(key);
            classMon.computeIfAbsent(r.cls(), k -> new HashSet<>()).add(key);
            String hostAny = r.host();
            if (hostAny != null) hostAll.computeIfAbsent(hostAny, k -> new HashSet<>()).add(key);
            if (r.log().getTeamId() != null) teamAll.computeIfAbsent(r.log().getTeamId(), k -> new HashSet<>()).add(key);
            if (anchorId != null && anchorId.equals(r.log().getId())) {
                anchorIn = true;
                continue;
            }
            others++;
            monitors.add(key);
            String reason = r.reason();
            reasonRows.merge(reason == null ? "" : reason, 1, Integer::sum);
            reasonMon.computeIfAbsent(reason == null ? "" : reason, k -> new HashSet<>()).add(key);
            String host = r.host();
            if (host != null) {
                hostRows.merge(host, 1, Integer::sum);
                hostMon.computeIfAbsent(host, k -> new HashSet<>()).add(key);
            }
            Long team = r.log().getTeamId();
            if (team == null) {
                nullTeam = true;
                nullTeamRows++;
                nullTeamMon.add(key);
            } else {
                teamRows.merge(team, 1, Integer::sum);
                teamMon.computeIfAbsent(team, k -> new HashSet<>()).add(key);
            }
        }

        Map<String, Object> a = new LinkedHashMap<>();
        ActivityLog x = anchor.log();
        a.put("id", x.getId());
        a.put("time", x.getActivityTime());
        a.put("type", x.getMonitorType());
        a.put("monitor_id", x.getMonitorId());
        a.put("action", x.getAction());
        a.put("status", x.getResultStatus());
        a.put("target", x.getTarget());
        a.put("host", anchor.host());
        a.put("team_id", x.getTeamId());
        a.put("failure_reason", anchor.failureReason());
        a.put("error_class", x.getErrorClass());
        a.put("reason", anchor.reason());
        a.put("reason_class", anchor.cls());
        a.put("failing", anchor.failing());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", a);
        body.put("window_s", window);
        body.put("from", ActivityFilter.iso(at.minusSeconds(window)));
        body.put("to", ActivityFilter.iso(at.plusSeconds(window)));
        body.put("total", others);                      // bu satır HARİÇ penceredeki hatalı kontroller
        body.put("monitors", monitors.size());          // o satırların ayrı izleme sayısı
        body.put("window_total", rows.size());          // bu satır dahil (pencerede hatalıysa)
        body.put("anchor_in_window", anchorIn);
        body.put("truncated", truncated);
        List<Map<String, Object>> byReason = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted(reasonRows)) {
            byReason.add(bucket("reason", e.getKey().isEmpty() ? null : e.getKey(), e.getValue(), reasonMon.get(e.getKey()).size()));
        }
        body.put("by_reason", byReason);
        List<Map<String, Object>> byHost = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted(hostRows)) byHost.add(bucket("host", e.getKey(), e.getValue(), hostMon.get(e.getKey()).size()));
        body.put("by_host", byHost);
        List<Map<String, Object>> byTeam = new ArrayList<>();
        List<Map.Entry<Long, Integer>> teams = new ArrayList<>(teamRows.entrySet());
        teams.sort(Map.Entry.<Long, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<Long, Integer> e : teams) {
            if (byTeam.size() >= MAX_BUCKETS) break;
            Map<String, Object> m = bucket("team_id", e.getKey(), e.getValue(), teamMon.get(e.getKey()).size());
            m.put("team_name", null);
            byTeam.add(m);
        }
        if (nullTeam && byTeam.size() < MAX_BUCKETS) {             // takımsız satırlar (yalnız global görücü görür)
            Map<String, Object> m = bucket("team_id", null, nullTeamRows, nullTeamMon.size());
            m.put("team_name", null);
            byTeam.add(m);
        }
        body.put("by_team", byTeam);

        // ── Şüpheliler ──
        List<Map<String, Object>> suspects = new ArrayList<>();
        int total = monitorsAll.size();
        List<String> order = new ArrayList<>(WIDE_CLASSES);
        if (order.remove(anchor.cls())) order.add(0, anchor.cls());          // satırın kendi sınıfı önce
        for (String cls : order) {
            int n = classMon.getOrDefault(cls, Set.of()).size();
            if (n >= WIDE_MIN_MONITORS && n * 100L >= (long) WIDE_MIN_SHARE_PCT * total) {
                suspects.add(suspect(cls + "_WIDE", "monitors", n, "total_monitors", total,
                        "share_pct", (int) Math.round(n * 100.0 / total)));
            }
        }
        String anchorKey = anchor.key();
        String host = anchor.host();
        if (host != null) {
            Set<String> otherMon = new HashSet<>(hostAll.getOrDefault(host, Set.of()));
            otherMon.remove(anchorKey);
            int n = otherMon.size() + (anchor.failing() ? 1 : 0);
            if (n >= SAME_HOST_MIN_MONITORS && !otherMon.isEmpty()) suspects.add(suspect("SAME_HOST", "host", host, "monitors", n));
        }
        Long team = x.getTeamId();
        if (team != null) {
            Set<String> s = new HashSet<>(teamAll.getOrDefault(team, Set.of()));
            if (anchor.failing()) s.add(anchorKey);
            if (s.size() >= SAME_TEAM_MIN_MONITORS) suspects.add(suspect("SAME_TEAM", "team_id", team, "monitors", s.size()));
        }
        if (anchor.failing() && (monitorsAll.isEmpty() || (monitorsAll.size() == 1 && monitorsAll.contains(anchorKey)))) {
            suspects.add(suspect("SINGLE"));
        }
        body.put("suspects", suspects);
        return body;
    }

    // ── Olgular (bu izlemenin kontrolleri) ───────────────────────────────────────────────────────────────

    List<Map<String, Object>> facts(Rel anchor, boolean scoped, List<Long> scope, Predicate<Long> canView) {
        ActivityLog x = anchor.log();
        List<Object> key = new ArrayList<>();
        String keySql;
        int days;
        if (x.getMonitorType() != null && x.getMonitorId() != null) {
            keySql = "monitor_type = ? AND monitor_id = ?";
            key.add(x.getMonitorType());
            key.add(x.getMonitorId());
            days = LOOKBACK_DAYS;
        } else if (x.getMonitorType() != null && x.getTarget() != null) {
            keySql = "monitor_type = ? AND monitor_id IS NULL AND target = ?";
            key.add(x.getMonitorType());
            key.add(x.getTarget());
            days = LEGACY_LOOKBACK_DAYS;
        } else {
            return List.of();
        }
        Instant at = Instant.ofEpochSecond(anchor.epoch());
        String t = ActivityFilter.iso(at);
        String d24 = ActivityFilter.iso(at.minus(Duration.ofHours(24)));
        String lookFrom = ActivityFilter.iso(at.minus(Duration.ofDays(days)));
        String lookTo = ActivityFilter.iso(at.plus(Duration.ofDays(days)));

        List<Object> args = new ArrayList<>(List.of(d24, t, d24, t, t, t, t, t));
        args.addAll(key);
        args.add(lookFrom);
        args.add(lookTo);
        String fail = ActivityTimelineService.OUTAGE_SQL;   // başarısız kontrol = zaman çizelgesinin kesinti kuralı (O1)
        StringBuilder sql = new StringBuilder("SELECT"
                + " SUM(CASE WHEN " + fail + " AND activity_time >= ? AND activity_time <= ? THEN 1 ELSE 0 END) AS f24,"
                + " SUM(CASE WHEN result_status = 'TIMEOUT' AND activity_time >= ? AND activity_time <= ? THEN 1 ELSE 0 END) AS t24,"
                + " MAX(CASE WHEN result_status = 'SUCCESS' AND activity_time < ? THEN activity_time END) AS last_ok,"
                + " MIN(CASE WHEN result_status = 'SUCCESS' AND activity_time > ? THEN activity_time END) AS next_ok,"
                + " MAX(CASE WHEN activity_time > ? THEN activity_time END) AS last_after,"
                + " MAX(CASE WHEN " + fail + " AND activity_time > ? THEN activity_time END) AS last_fail_after,"
                + " MIN(activity_time) AS earliest"
                + " FROM activity_log WHERE " + keySql
                + " AND action IN ('SCHEDULED_CHECK', 'MANUAL_CHECK') AND activity_time >= ? AND activity_time <= ?");
        appendScope(sql, args, scoped, scope);
        Map<String, Object> a = jdbc.queryForMap(sql.toString(), args.toArray());
        long f24 = ActivityQueryService.num(a.get("f24")), t24 = ActivityQueryService.num(a.get("t24"));
        String lastOk = str(a.get("last_ok")), nextOk = str(a.get("next_ok")), lastAfter = str(a.get("last_after")),
                lastFailAfter = str(a.get("last_fail_after")), earliest = str(a.get("earliest"));

        String prevFail = null, streakStart = null;
        if (anchor.failing()) {
            List<Object> b = new ArrayList<>(List.of(lastOk == null ? "" : lastOk, lastOk == null ? "" : lastOk, t));
            b.addAll(key);
            b.add(lookFrom);
            b.add(t);
            StringBuilder sqlB = new StringBuilder("SELECT"
                    + " MAX(CASE WHEN " + fail + " AND activity_time < ? THEN activity_time END) AS prev_fail,"
                    + " MIN(CASE WHEN " + fail + " AND activity_time > ? AND activity_time <= ? THEN activity_time END) AS streak"
                    + " FROM activity_log WHERE " + keySql
                    + " AND action IN ('SCHEDULED_CHECK', 'MANUAL_CHECK') AND activity_time >= ? AND activity_time <= ?");
            appendScope(sqlB, b, scoped, scope);
            Map<String, Object> m = jdbc.queryForMap(sqlB.toString(), b.toArray());
            prevFail = str(m.get("prev_fail"));
            streakStart = str(m.get("streak"));
        }
        // "Hâlâ hata veriyor" (hata avı D3): yalnız sonrası gerçekten görülebiliyorsa — bakış penceresi BUGÜNE uzanıyor
        // (çapa son {@code days} gün içinde; daha eskide sonraki satırlar okunmadı) ve izleme HÂLÂ VAR, ETKİN, güncel takımı
        // görülebilir (duraklatılan / silinen / görülemeyen takıma taşınan izlemenin sonrasında satır olmaması "başarı yok"
        // demek değildir). Kimliksiz eski satır doğrulanamaz → söylenmez. Künye yalnız aday olduğunda okunur.
        boolean stillCandidate = anchor.failing() && nextOk == null && (lastAfter == null || lastAfter.equals(lastFailAfter));
        boolean stillFailing = stillCandidate && x.getMonitorId() != null && !at.plus(Duration.ofDays(days)).isBefore(clock.get())
                && monitorLive(x.getMonitorType(), x.getMonitorId(), canView);
        return buildFacts(anchor.failing(), x.getActivityTime(), f24, t24, lastOk, nextOk, stillFailing,
                earliest, prevFail, streakStart);
    }

    /** İzleme hâlâ var, etkin (taranıyor) ve güncel takımı görülebilir mi; okunamazsa hayır (asla tahmin yok). */
    private boolean monitorLive(String type, Long id, Predicate<Long> canView) {
        try {
            ActivityTimelineService.MonitorRef ref = monitors.resolve(type == null ? "" : type.toUpperCase(java.util.Locale.ROOT), id);
            return ref != null && ref.exists() && ref.active() && (canView == null || canView.test(ref.teamId()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Olgu kuralları (saf; birim testi). Zamanlar aktivite biçiminde ({@code yyyy-MM-ddTHH:mm:ss}, UTC). {@code stillFailing}
     * çağıranın kararıdır (sonraki satırların hepsi hatalı / yok VE izleme canlı + görülebilir + pencere bugüne uzanıyor).
     */
    static List<Map<String, Object>> buildFacts(boolean failing, String anchorTime, long f24, long t24, String lastOk,
                                                String nextOk, boolean stillFailing, String earliest,
                                                String prevFail, String streakStart) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        String start = streakStart != null ? streakStart : anchorTime;
        if (failing && lastOk != null) {
            if (prevFail != null) {
                long d = (ActivityTimelineService.epoch(start) - ActivityTimelineService.epoch(prevFail)) / 86_400;
                if (d >= 1) byCode.put("FIRST_FAILURE_IN_DAYS", fact("FIRST_FAILURE_IN_DAYS", "days", d, "at_least", false));
            } else if (earliest != null) {
                long d = (ActivityTimelineService.epoch(start) - ActivityTimelineService.epoch(earliest)) / 86_400;
                if (d >= 1) byCode.put("FIRST_FAILURE_IN_DAYS", fact("FIRST_FAILURE_IN_DAYS", "days", d, "at_least", true));
            }
        }
        if (f24 > 0) byCode.put("FAILURES_24H", fact("FAILURES_24H", "n", f24));
        if (t24 > 0) byCode.put("TIMEOUTS_24H", fact("TIMEOUTS_24H", "n", t24));
        if (failing && lastOk != null) byCode.put("LAST_SUCCESS", fact("LAST_SUCCESS", "at", lastOk));
        if (failing && nextOk != null) {
            long s = ActivityTimelineService.epoch(nextOk) - ActivityTimelineService.epoch(start);
            byCode.put("RECOVERED_AFTER", fact("RECOVERED_AFTER", "seconds", Math.max(0, s), "at", nextOk));
        }
        if (failing && nextOk == null && stillFailing) {
            byCode.put("STILL_FAILING", fact("STILL_FAILING", "since", start));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : FACT_CODES) if (byCode.containsKey(code)) out.add(byCode.get(code));
        return out;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────────

    private static Rel map(ResultSet rs, int i) throws SQLException {
        ActivityLog a = ActivityQueryService.ROW.mapRow(rs, i);
        return new Rel(a, optString(rs, "failure_reason"));
    }

    /** Kolonu adla okur; sonuçta yoksa (eski şema) {@code null}. */
    static String optString(ResultSet rs, String column) {
        try {
            return rs.getString(rs.findColumn(column));
        } catch (SQLException e) {
            return null;
        }
    }

    private static void appendScope(StringBuilder sql, List<Object> args, boolean scoped, List<Long> scope) {
        if (!scoped) return;
        List<Long> sc = scope == null ? List.of() : scope.stream().filter(v -> v != null).toList();
        if (sc.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND team_id IN (").append(String.join(", ", Collections.nCopies(sc.size(), "?"))).append(")");
        args.addAll(sc);
    }

    private Map<Long, String> teamNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<Long> list = new ArrayList<>(ids);
        jdbc.query("SELECT id, name FROM teams WHERE id IN (" + String.join(", ", Collections.nCopies(list.size(), "?")) + ")",
                rs -> { out.put(rs.getLong(1), rs.getString(2)); }, list.toArray());
        return out;
    }

    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return l.size() > MAX_BUCKETS ? l.subList(0, MAX_BUCKETS) : l;
    }

    private static Map<String, Object> bucket(String keyName, Object key, int count, int monitors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(keyName, key);
        m.put("count", count);
        m.put("monitors", monitors);
        return m;
    }

    private static Map<String, Object> suspect(String code, Object... kv) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        s.put("params", params(kv));
        return s;
    }

    private static Map<String, Object> fact(String code, Object... kv) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        s.put("params", params(kv));
        return s;
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) p.put(String.valueOf(kv[i]), kv[i + 1]);
        return p;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Bu ucun 404 kodu (hata avı D4) — eski pod'un eşlemesiz ucu {@code NOT_FOUND} döner; ön yüz ikisini ayırt eder. */
    public static final String NOT_FOUND_CODE = "ACTIVITY_NOT_FOUND";

    static NoSuchElementException notFound() {
        return new com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException(NOT_FOUND_CODE, Msg.t(
                "Aktivite bulunamadı ya da görme yetkiniz yok. Listeyi yenileyip yeniden deneyin.",
                "The activity wasn't found, or you can't see it. Refresh the list and try again."));
    }
}
