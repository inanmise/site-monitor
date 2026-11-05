package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * İzleme ZAMAN ÇİZELGESİ (2026-11-05, aktivite zenginleştirmesi D): {@code GET /api/activity/timeline?monitor=TÜR:kimlik
 * &from=&to=} — bir izlemenin dönem şeridi, kesintileri, MTTR / en uzun kesinti / erişilebilirlik ve yanıt süresi serisi.
 *
 * <p><b>Kaynak:</b> {@code activity_log} KONTROL satırları (zamanlanmış + elle); erişilebilirlik yalnız ZAMANLANMIŞ
 * kontrollerden. Alarm açılış satırları ({@code ALERT_TRIGGERED}) aynı sorguda okunur ve kesintiye alarm kimliği olarak
 * bağlanır (kontrol sayılmaz). Pencere varsayılan son {@link #DEFAULT_DAYS} gün, en çok {@link #MAX_DAYS} gün; en çok
 * {@link #MAX_ROWS} satır — fazlası varsa EN YENİ satırlar alınır ({@code truncated}, {@code covered_from}).
 *
 * <p><b>Kapı:</b> izlemenin GÜNCEL takımı ({@code canViewMonitoring}; izleme tablosundan — Port / DNS envanter türevinde
 * envanterin takımı). İzleme silinmişse en yeni aktivite satırının takımı. Görülemeyen → 404 (varlık sızmaz). Satırlar
 * ayrıca oturumun okuma kapsamıyla süzülür (izleme başka takımdan taşındıysa eski takımın satırları sızmaz).
 *
 * <p><b>Durum eşlemesi:</b> SUCCESS → up, ERROR / TIMEOUT → down, WARNING → warn, diğer → unknown. Kesinti = ilk down →
 * ilk up arası (araya giren warn / unknown kesintiyi bitirmez). {@code availability_pct} = (up + warn) ÷ (up + warn + down)
 * — kesinti tanımıyla tutarlı "çalışıyordu" oranı; {@code healthy_pct} = up ÷ ölçülen. Bilinmeyen hiçbir orana girmez;
 * ölçülen zamanlanmış kontrol yoksa oranlar {@code null} (asla %100 değil).
 *
 * <p><b>Eski satırlar:</b> sertifika / erişilebilirlik kontrol satırları 2026-11-05'e dek {@code monitor_id} taşımıyordu;
 * bu iki türde envanter kimliğinin yanında aynı hedefi taşıyan kimliksiz satırlar da okunur.
 */
@Service
public class ActivityTimelineService {

    static final int DEFAULT_DAYS = 7, MAX_DAYS = 90, MAX_ROWS = 50_000, MAX_PERIODS = 500, MAX_SERIES = 200;
    /** Alarm, kesintinin bitişinden bu kadar sonra açıldıysa da o kesintiye bağlanır (doğrulama zinciri gecikmesi). */
    static final long ALERT_GRACE_S = 600;
    /** Boşluk: ardışık iki kontrol arasında max(3 × medyan aralık, bu) saniyeden uzun aralık "veri yok" dönemidir. */
    static final long GAP_FLOOR_S = 600;
    /** Seri adımı adayları (sn): en küçük uyan seçilir. */
    static final long[] STEPS = {60, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400};
    /** Aynı tür adları — {@code ActivityLogService} sabitleri. */
    public static final List<String> TYPES = List.of("CERT", "UPTIME", "HTTP", "PORT", "PING", "DNS", "KEYWORD", "DOMAIN",
            "PAGE", "SCRIPTED", "PAGESPEED", "DBQUERY");
    static final String NO_DATA = "NO_DATA";

    private final JdbcTemplate jdbc;
    /** Satır tavanı (testlerde küçültülür). */
    int maxRows = MAX_ROWS;

    public ActivityTimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** İzleme künyesi; {@code exists} = izleme tablosunda bulundu (false → silinmiş, en yeni aktivite satırından). */
    public record MonitorRef(String type, long id, String name, String target, Long teamId, boolean exists) {}

    /** Okunan satır (yalnız gereken kolonlar). */
    record Row(long id, String time, String action, String status, Long ms, String failureReason, String errorClass,
               Long alertEventId) {
        boolean check() { return ActivityFilter.CHECK_ACTIONS.contains(action); }
        boolean scheduled() { return "SCHEDULED_CHECK".equals(action); }
        String state() { return stateOf(status); }
        String reason() { return ActivitySignals.reasonKey(failureReason, errorClass); }
    }

    /**
     * Zaman çizelgesi. {@code canView} = izlemenin takımını okuyabilir mi (oturumun {@code canViewMonitoring}'i);
     * {@code scoped} / {@code scope} = oturumun satır kapsamı (liste ucuyla aynı). Görülemeyen / bulunamayan → 404.
     */
    public Map<String, Object> timeline(String monitorParam, String fromRaw, String toRaw, boolean scoped, List<Long> scope,
                                        Predicate<Long> canView, Instant now) {
        String mon = monitorParam == null ? "" : monitorParam.trim();
        if (mon.isEmpty()) {
            throw new FieldValidationException("monitor", Msg.t("İzleme seçilmedi (monitor=TÜR:kimlik).",
                    "No monitor selected (monitor=TYPE:id)."));
        }
        ActivityFilter parsed = ActivityFilter.parse(new ActivityFilter.Params(null, null, mon, null, fromRaw, toRaw,
                null, null, null, null, null), false, List.of());
        String type = parsed.monitorType();
        long id = parsed.monitorId();
        if (!TYPES.contains(type)) {
            throw new FieldValidationException("monitor", Msg.t("Bilinmeyen izleme türü: " + type + ".",
                    "Unknown monitor type: " + type + "."));
        }
        Instant to = parsed.to() == null ? now : ActivityFilter.instantOf(parsed.to());
        Instant from = parsed.from() == null ? to.minus(Duration.ofDays(DEFAULT_DAYS)) : ActivityFilter.instantOf(parsed.from());
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw new FieldValidationException("from", Msg.t(
                    "Zaman çizelgesi en çok " + MAX_DAYS + " günlük aralık gösterir. Aralığı daraltın.",
                    "The timeline shows at most " + MAX_DAYS + " days. Narrow the range."));
        }

        MonitorRef ref = resolve(type, id);
        if (ref == null) ref = fromNewestRow(type, id);
        if (ref == null || !canView.test(ref.teamId())) throw notFound();

        // ── Satırlar (tek sorgu; en yeni MAX_ROWS + 1 → sıra çevrilir) ──
        boolean legacy = ("CERT".equals(type) || "UPTIME".equals(type)) && ref.target() != null;
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, activity_time, action, result_status, response_ms, failure_reason,"
                + " error_class, alert_event_id FROM activity_log WHERE monitor_type = ?");
        args.add(type);
        if (legacy) {
            sql.append(" AND (monitor_id = ? OR (monitor_id IS NULL AND target = ?))");
            args.add(id);
            args.add(ref.target());
        } else {
            sql.append(" AND monitor_id = ?");
            args.add(id);
        }
        sql.append(" AND action IN ('SCHEDULED_CHECK', 'MANUAL_CHECK', 'ALERT_TRIGGERED') AND activity_time >= ? AND activity_time <= ?");
        args.add(ActivityFilter.iso(from));
        args.add(ActivityFilter.iso(to));
        if (scoped) {
            List<Long> sc = scope == null ? List.of() : scope.stream().filter(x -> x != null).toList();
            if (sc.isEmpty()) {
                sql.append(" AND 1 = 0");
            } else {
                sql.append(" AND team_id IN (").append(String.join(", ", Collections.nCopies(sc.size(), "?"))).append(")");
                args.addAll(sc);
            }
        }
        sql.append(" ORDER BY activity_time DESC, id DESC LIMIT ?");
        args.add(maxRows + 1);
        List<Row> rows = new ArrayList<>(jdbc.query(sql.toString(), (rs, i) -> new Row(rs.getLong("id"),
                rs.getString("activity_time"), rs.getString("action"),
                timelineStatus(type, rs.getString("action"), rs.getString("result_status"), rs.getString("failure_reason")),
                (Long) rs.getObject("response_ms", Long.class), rs.getString("failure_reason"), rs.getString("error_class"),
                (Long) rs.getObject("alert_event_id", Long.class)), args.toArray()));
        boolean truncated = rows.size() > maxRows;
        if (truncated) rows = new ArrayList<>(rows.subList(0, maxRows));
        Collections.reverse(rows);

        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("type", type);
        monitor.put("id", id);
        monitor.put("name", ref.name());
        monitor.put("target", ref.target());
        monitor.put("team_id", ref.teamId());
        monitor.put("team_name", teamName(ref.teamId()));
        monitor.put("exists", ref.exists());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monitor", monitor);
        out.putAll(compute(rows, from, to, now, truncated));
        return out;
    }

    // ── Hesap (saf; birim testi) ─────────────────────────────────────────────────────────────────────────

    /** Satırlar ZAMAN SIRASIYLA (eskiden yeniye). */
    static Map<String, Object> compute(List<Row> rows, Instant from, Instant to, Instant now, boolean truncated) {
        Instant end = to.isAfter(now) ? now : to;
        List<Row> checks = new ArrayList<>();
        List<Row> alerts = new ArrayList<>();
        for (Row r : rows) {
            if (r.check()) checks.add(r);
            else if ("ALERT_TRIGGERED".equals(r.action()) && r.alertEventId() != null) alerts.add(r);
        }
        long up = 0, warn = 0, down = 0, unknown = 0, scheduled = 0;
        for (Row r : checks) {
            if (!r.scheduled()) continue;
            scheduled++;
            switch (r.state()) {
                case "up" -> up++;
                case "warn" -> warn++;
                case "down" -> down++;
                default -> unknown++;
            }
        }
        long measured = up + warn + down;

        // ── Kesintiler ──
        List<Map<String, Object>> outages = new ArrayList<>();
        List<long[]> spans = new ArrayList<>();          // [from, to|-1] epoch sn — alarm eşlemesi için
        Map<String, Integer> reasons = null;
        Map<String, Object> cur = null;
        long curFrom = 0;
        int curChecks = 0;
        for (int i = 0; i < checks.size(); i++) {
            Row r = checks.get(i);
            String st = r.state();
            if ("down".equals(st)) {
                if (cur == null) {
                    cur = new LinkedHashMap<>();
                    curFrom = epoch(r.time());
                    cur.put("from", zulu(r.time()));
                    cur.put("first_activity_id", r.id());
                    cur.put("open_start", i == 0);           // pencerenin ilk kontrolü: daha önce başlamış olabilir
                    reasons = new LinkedHashMap<>();
                    curChecks = 0;
                }
                curChecks++;
                String rk = r.reason();
                if (rk != null) reasons.merge(rk, 1, Integer::sum);
            } else if (cur != null) {
                if ("up".equals(st)) {
                    long t = epoch(r.time());
                    closeOutage(cur, curFrom, t, curChecks, reasons, false);
                    outages.add(cur);
                    spans.add(new long[]{curFrom, t});
                    cur = null;
                } else {
                    curChecks++;
                }
            }
        }
        if (cur != null) {
            long e = end.getEpochSecond();
            closeOutage(cur, curFrom, Math.max(curFrom, e), curChecks, reasons, true);
            outages.add(cur);
            spans.add(new long[]{curFrom, -1});
        }
        // Alarm → kesinti (her kesinti ilk eşleşen alarmı alır)
        for (Map<String, Object> o : outages) o.put("alert_event_id", null);
        for (Row a : alerts) {
            long t = epoch(a.time());
            for (int k = 0; k < outages.size(); k++) {
                long[] sp = spans.get(k);
                Map<String, Object> o = outages.get(k);
                if (o.get("alert_event_id") != null) continue;
                if (t >= sp[0] && (sp[1] < 0 || t <= sp[1] + ALERT_GRACE_S)) {
                    o.put("alert_event_id", a.alertEventId());
                    break;
                }
            }
        }
        Long mttr = null, longest = null;
        long closedSum = 0, closedN = 0;
        for (Map<String, Object> o : outages) {
            long d = ((Number) o.get("duration_s")).longValue();
            if (longest == null || d > longest) longest = d;
            if (o.get("to") != null) {
                closedSum += d;
                closedN++;
            }
        }
        if (closedN > 0) mttr = Math.round(closedSum / (double) closedN);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", zulu(ActivityFilter.iso(from)));
        out.put("to", zulu(ActivityFilter.iso(to)));
        out.put("checks", checks.size());
        out.put("scheduled_checks", scheduled);
        out.put("counts", Map.of("up", up, "warn", warn, "down", down, "unknown", unknown));
        out.put("availability_pct", measured == 0 ? null : pct(up + warn, measured));
        out.put("healthy_pct", measured == 0 ? null : pct(up, measured));
        out.put("mttr_s", mttr);
        out.put("longest_outage_s", longest);
        out.put("outage_count", outages.size());
        List<Map<String, Object>> periods = periods(checks, end);
        boolean merged = false;
        if (periods.size() > MAX_PERIODS) {
            periods = mergePeriods(periods, from, end);
            merged = true;
        }
        out.put("periods", periods);
        out.put("periods_merged", merged);
        out.put("outages", outages);
        long step = stepFor(from, to);
        out.put("series", series(checks, from, to, step));
        out.put("series_step_s", step);
        out.put("truncated", truncated);
        out.put("covered_from", truncated && !rows.isEmpty() ? zulu(rows.get(0).time()) : null);
        return out;
    }

    private static void closeOutage(Map<String, Object> o, long from, long to, int checks, Map<String, Integer> reasons,
                                    boolean ongoing) {
        o.put("to", ongoing ? null : zuluOf(to));
        o.put("duration_s", Math.max(0, to - from));
        o.put("checks", checks);
        o.put("reason", topKey(reasons));
        o.put("ongoing", ongoing);
    }

    /** Ardışık aynı durumlu kontrollerden dönemler; uzun boşluk "veri yok" (unknown, {@code reason=NO_DATA}) dönemidir. */
    static List<Map<String, Object>> periods(List<Row> checks, Instant end) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (checks.isEmpty()) return out;
        long median = medianGap(checks);
        long gapLimit = Math.max(GAP_FLOOR_S, median > 0 ? 3 * median : 0);
        String state = null;
        long pFrom = 0, prev = 0;
        int n = 0;
        Map<String, Integer> reasons = new HashMap<>();
        for (Row r : checks) {
            long t = epoch(r.time());
            String st = r.state();
            if (state != null && t - prev > gapLimit) {
                long cut = Math.min(t, prev + Math.max(median, 1));
                out.add(period(pFrom, cut, state, n, reasons));
                out.add(period(cut, t, "unknown", 0, Map.of(NO_DATA, 1)));
                state = null;
            }
            if (state == null || !state.equals(st)) {
                if (state != null) out.add(period(pFrom, t, state, n, reasons));
                state = st;
                pFrom = t;
                n = 0;
                reasons = new HashMap<>();
            }
            n++;
            String rk = r.reason();
            if (rk != null && !"up".equals(st)) reasons.merge(rk, 1, Integer::sum);
            prev = t;
        }
        long last = Math.min(end.getEpochSecond(), prev + Math.max(median, 0));
        out.add(period(pFrom, Math.max(prev, last), state, n, reasons));
        return out;
    }

    private static Map<String, Object> period(long from, long to, String state, int checks, Map<String, Integer> reasons) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", zuluOf(from));
        p.put("to", zuluOf(Math.max(from, to)));
        p.put("state", state);
        p.put("checks", checks);
        p.put("reason", "up".equals(state) ? null : topKey(reasons));
        return p;
    }

    /**
     * {@link #MAX_PERIODS}'u aşan dönem listesini eşit zaman dilimlerine indirger: her dilim kesiştiği dönemlerin EN KÖTÜ
     * durumunu alır (down &gt; warn &gt; unknown &gt; up), ardışık aynı dilimler birleşir — kısa bir kesinti kaybolmaz.
     */
    static List<Map<String, Object>> mergePeriods(List<Map<String, Object>> periods, Instant from, Instant end) {
        long a = from.getEpochSecond(), b = Math.max(a + 1, end.getEpochSecond());
        long first = epochZ((String) periods.get(0).get("from"));
        if (first < a) a = first;
        long slot = Math.max(1, (long) Math.ceil((b - a) / (double) MAX_PERIODS));
        int n = (int) Math.min(MAX_PERIODS, (b - a + slot - 1) / slot);
        String[] st = new String[n];
        int[] cnt = new int[n];
        String[] rs = new String[n];
        for (Map<String, Object> p : periods) {
            long pf = epochZ((String) p.get("from")), pt = epochZ((String) p.get("to"));
            int i0 = (int) Math.max(0, Math.min(n - 1, (pf - a) / slot));
            int i1 = (int) Math.max(0, Math.min(n - 1, (Math.max(pf, pt - 1) - a) / slot));
            String s = (String) p.get("state");
            cnt[i0] += ((Number) p.get("checks")).intValue();
            for (int i = i0; i <= i1; i++) {
                if (st[i] == null || rank(s) > rank(st[i])) {
                    st[i] = s;
                    rs[i] = (String) p.get("reason");
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> cur = null;
        for (int i = 0; i < n; i++) {
            if (st[i] == null) {
                cur = null;
                continue;
            }
            long sf = a + i * slot, stt = Math.min(b, sf + slot);
            if (cur != null && st[i].equals(cur.get("state"))) {
                cur.put("to", zuluOf(stt));
                cur.put("checks", ((Number) cur.get("checks")).intValue() + cnt[i]);
                if (cur.get("reason") == null) cur.put("reason", rs[i]);
            } else {
                cur = new LinkedHashMap<>();
                cur.put("from", zuluOf(sf));
                cur.put("to", zuluOf(stt));
                cur.put("state", st[i]);
                cur.put("checks", cnt[i]);
                cur.put("reason", "up".equals(st[i]) ? null : rs[i]);
                out.add(cur);
            }
        }
        return out;
    }

    private static int rank(String state) {
        return switch (state == null ? "" : state) {
            case "down" -> 3;
            case "warn" -> 2;
            case "unknown" -> 1;
            default -> 0;
        };
    }

    /** Yanıt süresi serisi: her kova {@code t} (UTC, Z), {@code checks}, {@code ms_avg} / {@code ms_max} (up + warn, ölçülen), {@code fail} (down). */
    static List<Map<String, Object>> series(List<Row> checks, Instant from, Instant to, long step) {
        long start = Math.floorDiv(from.getEpochSecond(), step) * step;
        long stop = to.getEpochSecond();
        int n = (int) Math.min(MAX_SERIES + 1, Math.max(1, (stop - start) / step + 1));
        long[] c = new long[n], f = new long[n], sum = new long[n], cntMs = new long[n], max = new long[n];
        for (Row r : checks) {
            long t = epoch(r.time());
            int i = (int) ((t - start) / step);
            if (i < 0 || i >= n) continue;
            c[i]++;
            String st = r.state();
            if ("down".equals(st)) f[i]++;
            else if (r.ms() != null && ("up".equals(st) || "warn".equals(st))) {
                sum[i] += r.ms();
                cntMs[i]++;
                max[i] = Math.max(max[i], r.ms());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", zuluOf(start + i * step));
            p.put("checks", c[i]);
            p.put("ms_avg", cntMs[i] == 0 ? null : Math.round(sum[i] / (double) cntMs[i]));
            p.put("ms_max", cntMs[i] == 0 ? null : max[i]);
            p.put("fail", f[i]);
            out.add(p);
        }
        return out;
    }

    static long stepFor(Instant from, Instant to) {
        long span = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        for (long s : STEPS) if (span / s <= MAX_SERIES) return s;
        return STEPS[STEPS.length - 1];
    }

    private static long medianGap(List<Row> checks) {
        if (checks.size() < 2) return 0;
        int n = Math.min(checks.size() - 1, 5_000);              // örnek tavanı (sıralama maliyeti sınırlı)
        long[] d = new long[n];
        int off = checks.size() - 1 - n;
        for (int i = 0; i < n; i++) d[i] = epoch(checks.get(off + i + 1).time()) - epoch(checks.get(off + i).time());
        java.util.Arrays.sort(d);
        return d[n / 2];
    }

    /**
     * Zaman çizelgesinin gördüğü durum (2026-11-05, sözleşme incelemesi Y1): kayıtlı {@code result_status} DEĞİŞMEZ
     * (2026-10-11 sözlüğü; liste KPI'ları), ama alarm AÇAN uyarı sınıfı kontrol hataları çizelgede KESİNTİ sayılır —
     * HTTP / KEYWORD'de {@code WARNING} yalnız başarısız kontrol + HTTP kodu ile yazılır (durum uyuşmazlığı, gövde
     * doğrulaması, anahtar kelime koşulu; yavaş yanıt SUCCESS) → kesinti, eski (nedensiz) satırlarda da; SCRIPTED
     * {@code WARNING} = senaryo FAIL → kesinti, yalnız {@code SCRIPT_NO_CHECKS} (yapılandırma kusuru) uyarı kalır.
     * DBQUERY ihlali, sayfa DEGRADED, sayfa hızı SLOW uyarı kalır. Kontrol olmayan satır (alarm açılışı) dokunulmaz.
     */
    static String timelineStatus(String type, String action, String status, String reason) {
        if (!"WARNING".equalsIgnoreCase(status) || !ActivityFilter.CHECK_ACTIONS.contains(action)) return status;
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "HTTP", "KEYWORD" -> "ERROR";
            case "SCRIPTED" -> "SCRIPT_NO_CHECKS".equals(reason) ? status : "ERROR";
            default -> status;
        };
    }

    static String stateOf(String status) {
        if (status == null) return "unknown";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> "up";
            case "ERROR", "TIMEOUT" -> "down";
            case "WARNING" -> "warn";
            default -> "unknown";
        };
    }

    private static Double pct(long part, long whole) {
        return Math.round(part * 10_000.0 / whole) / 100.0;
    }

    private static String topKey(Map<String, Integer> counts) {
        if (counts == null) return null;
        String best = null;
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    static long epoch(String isoUtc) {
        return ActivityFilter.instantOf(isoUtc.length() > 19 ? isoUtc.substring(0, 19) : isoUtc).getEpochSecond();
    }

    private static long epochZ(String z) {
        return Instant.parse(z).getEpochSecond();
    }

    private static String zulu(String isoUtc) {
        return isoUtc + "Z";
    }

    private static String zuluOf(long epochSecond) {
        return ActivityFilter.iso(Instant.ofEpochSecond(epochSecond)) + "Z";
    }

    // ── İzleme künyesi ───────────────────────────────────────────────────────────────────────────────────

    /** İzleme tablosundan ad / hedef / takım (hedef biçimi aktivite yazıcılarınınkiyle aynı); yoksa {@code null}. */
    MonitorRef resolve(String type, long id) {
        try {
            return switch (type) {
                case "HTTP" -> simple(type, id, "SELECT name, url AS target, team_id FROM http_monitors WHERE id = ?");
                case "KEYWORD" -> simple(type, id, "SELECT name, url AS target, team_id FROM keyword_monitors WHERE id = ?");
                case "PAGE" -> simple(type, id, "SELECT name, url AS target, team_id FROM page_monitors WHERE id = ?");
                case "PAGESPEED" -> simple(type, id, "SELECT name, url AS target, team_id FROM pagespeed_monitors WHERE id = ?");
                case "PING" -> simple(type, id, "SELECT name, host AS target, team_id FROM ping_monitors WHERE id = ?");
                case "DOMAIN" -> simple(type, id, "SELECT name, domain AS target, team_id FROM domain_monitors WHERE id = ?");
                case "SCRIPTED" -> simple(type, id, "SELECT name, name AS target, team_id FROM scripted_monitors WHERE id = ?");
                case "DBQUERY" -> simple(type, id, "SELECT name, name AS target, team_id FROM dbquery_monitors WHERE id = ?");
                case "PORT" -> jdbc.queryForObject("SELECT name, host, port, team_id, standalone FROM port_monitors WHERE id = ?",
                        (rs, i) -> new MonitorRef(type, id, rs.getString("name"), rs.getString("host") + ":" + rs.getObject("port"),
                                effectiveTeam(rs.getString("host"), rs.getObject("standalone", Boolean.class),
                                        rs.getObject("team_id", Long.class)), true), id);
                case "DNS" -> jdbc.queryForObject("SELECT name, domain, record_type, team_id, standalone FROM dns_monitors WHERE id = ?",
                        (rs, i) -> new MonitorRef(type, id, rs.getString("name"), rs.getString("domain") + " " + rs.getString("record_type"),
                                effectiveTeam(rs.getString("domain"), rs.getObject("standalone", Boolean.class),
                                        rs.getObject("team_id", Long.class)), true), id);
                case "CERT", "UPTIME" -> jdbc.queryForObject("SELECT domain, port, team_id FROM certificate_inventory WHERE id = ?",
                        (rs, i) -> {
                            String d = rs.getString("domain");
                            Object port = rs.getObject("port");
                            String target = "UPTIME".equals(type) ? d + ":" + (port == null ? 443 : port) : d;
                            return new MonitorRef(type, id, d, target, rs.getObject("team_id", Long.class), true);
                        }, id);
                default -> null;
            };
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private MonitorRef simple(String type, long id, String sql) {
        return jdbc.queryForObject(sql, (rs, i) -> new MonitorRef(type, id, rs.getString("name"), rs.getString("target"),
                rs.getObject("team_id", Long.class), true), id);
    }

    /** Port / DNS: bağımsız satırın takımı kendisi; envanter türevinin takımı envanterin (yoksa saklanan) — izleme sayfalarıyla aynı. */
    private Long effectiveTeam(String domain, Boolean standalone, Long stored) {
        if (Boolean.TRUE.equals(standalone) || domain == null) return stored;
        List<Long> inv = jdbc.query("SELECT team_id FROM certificate_inventory WHERE domain = ?",
                (rs, i) -> rs.getObject("team_id", Long.class), domain);
        return inv.isEmpty() ? stored : inv.get(0);
    }

    /** Silinmiş izleme: en yeni aktivite satırının künyesi. */
    private MonitorRef fromNewestRow(String type, long id) {
        List<MonitorRef> r = jdbc.query("SELECT monitor_name, target, team_id FROM activity_log WHERE monitor_type = ?"
                        + " AND monitor_id = ? ORDER BY activity_time DESC, id DESC LIMIT 1",
                (rs, i) -> new MonitorRef(type, id, rs.getString("monitor_name"), rs.getString("target"),
                        rs.getObject("team_id", Long.class), false), type, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private String teamName(Long teamId) {
        if (teamId == null) return null;
        List<String> n = jdbc.query("SELECT name FROM teams WHERE id = ?", (rs, i) -> rs.getString(1), teamId);
        return n.isEmpty() ? null : n.get(0);
    }

    static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t(
                "İzleme bulunamadı ya da görme yetkiniz yok. Listeyi yenileyip yeniden deneyin.",
                "The monitor wasn't found, or you can't see it. Refresh the list and try again."));
    }
}
