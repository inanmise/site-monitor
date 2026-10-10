package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (a) ERİŞİLEBİLİRLİK HEDEFİ UYUMU — uygulamada resmî bir SLO özelliği YOK; bu bölüm "SLO uyumu" talebini dürüstçe
 * "ölçülen erişilebilirlik × kurum hedefi" olarak karşılar ve öyle etiketlenir.
 *
 * <h2>Tanım</h2>
 * <ul>
 *   <li><b>Erişilebilirlik</b> = ay içindeki başarılı kontrol / toplam kontrol (KONTROL AĞIRLIKLI), kaynak gece yazılan
 *       {@code monitor_check_hourly} özeti — Durum Sayfası'nın 7 gün kullanılabilirliğiyle AYNI özet ailesi, ham
 *       kontrol tablolarına istek yolunda dokunulmaz. Türler: HTTP, Ping, Port, Keyword, Sayfa, Sayfa Hızı, Sentetik
 *       ve Durum İzleme (sertifika envanteri erişimi; bakım dakikaları sayım dışı). DNS ve alan adı kaydının
 *       erişilebilirlik ölçüsü yoktur.</li>
 *   <li><b>Ay sınırı</b> İstanbul takvimiyle: saatlik kova {@code [ayın 1'i 00:00 TR, sonraki ayın 1'i 00:00 TR)} =
 *       UTC'de 3 saat önce. Saatlik özet bu ay için yoksa (saklama 365 gün) günlük özete düşülür ve "yaklaşık" notu
 *       düşülür (UTC gün sınırları).</li>
 *   <li><b>Hedef</b> {@code site.monitor.executive-summary.availability-target} (varsayılan %99,9). Takım / hizmet
 *       (takım × izleme grubu, Durum Sayfası'nın hizmet tanımı) hedefi karşılar ⇔ erişilebilirliği ≥ hedef.</li>
 *   <li><b>Hata bütçesi kullanımı</b> = (100 − erişilebilirlik) / (100 − hedef); hedef %100 ise tanımsız.</li>
 *   <li><b>Eşdeğer kesinti</b> = (100 − erişilebilirlik) % × ayın (geçen) dakikası — kontrol ağırlıklı YAKLAŞIK değer.</li>
 * </ul>
 * İzleme → takım/grup eşlemesi İzleme Panosu'nun kurum geneli (bellekli) satırlarından; Durum İzleme satırları envanterin
 * SY takımı ve grubundan. Silinmiş izlemelerin özet satırları dahil EDİLMEZ (sayısı notta).
 *
 * <h2>Takım kapsamı (2026-10-10)</h2>
 * Bir izleme takımın kapsamındadır ⇔ kendi takımı ({@code team_id}) kapsam takımıdır YA DA envanter türevidir (Durum
 * İzleme ya da envanterden türeyen Port) ve host'u kapsamdaki envanterdedir ({@link ExecutiveSummaryContext#domainInScope}
 * — SY ya da UG). Kurum geneli izleme toplamları ve günlük seri koşu boyu paylaşılır ({@link ExecutiveSummaryContext#shared});
 * takımın günlük serisi izleme × İstanbul günü toplamından (koşu başına TEK sorgu) bellekte süzülür. Takımda "takımlara
 * göre" tablosu ve takım sayısı hükmü yerine hizmet hükmü ve "en düşük erişilebilirlikli izlemeler" tablosu gelir;
 * silinmiş izlemeler bir takıma bağlanamadığı için takım özetinde not düşülmez.
 *
 * <h2>Sorgu bütçesi</h2>
 * İki toplu sorgu (izleme başına cari + önceki ay toplamı; kurum geneli saat kovaları → İstanbul günleri — takım kapsamında
 * izleme × gün) + gerekirse aynı ikisi günlük özete. Pano satırları ve envanter paylaşılan bellekten.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvailabilitySection implements ExecutiveSummarySection {

    public static final String KEY = "availability";
    static final int ORDER = 10;
    static final String TITLE = "Erişilebilirlik hedefi uyumu";

    /** Rollup tür adları (monitor_check_hourly.monitor_type). */
    static final List<String> ROLLUP_TYPES = List.of("HTTP", "PING", "PORT", "KEYWORD", "PAGE", "PAGESPEED", "SCRIPTED", "UPTIME");
    /** Pano tür anahtarı → rollup tür adı. */
    static final Map<String, String> OVERVIEW_TO_ROLLUP = Map.of(
            "http", "HTTP", "ping", "PING", "port", "PORT", "keyword", "KEYWORD",
            "page", "PAGE", "pagespeed", "PAGESPEED", "scripted", "SCRIPTED");
    /** "En kötü hizmetler" listesine girmek için gereken en az kontrol (gürültülü küçük örnekler sıralamayı ele geçirmesin). */
    static final int MIN_SERVICE_CHECKS = 30;
    static final int WORST_LIMIT = 10;
    static final int TEAM_LIMIT = 50;

    private static final String IN_TYPES = "('HTTP','PING','PORT','KEYWORD','PAGE','PAGESPEED','SCRIPTED','UPTIME')";

    /** İzleme başına cari + önceki ay toplamları — params: cur, cur, cur, cur, prevFrom, curTo. */
    static String perMonitorSql(String table, String col) {
        return "SELECT monitor_type, monitor_key, "
                + "SUM(CASE WHEN " + col + " >= ? THEN total_checks ELSE 0 END) AS cur_total, "
                + "SUM(CASE WHEN " + col + " >= ? THEN up_checks ELSE 0 END) AS cur_up, "
                + "SUM(CASE WHEN " + col + " < ? THEN total_checks ELSE 0 END) AS prev_total, "
                + "SUM(CASE WHEN " + col + " < ? THEN up_checks ELSE 0 END) AS prev_up "
                + "FROM " + table + " WHERE " + col + " >= ? AND " + col + " < ? AND monitor_type IN " + IN_TYPES
                + " GROUP BY monitor_type, monitor_key";
    }

    /** Kurum geneli kova toplamları (günlük seri) — params: from, to. */
    static String perBucketSql(String table, String col) {
        return "SELECT " + col + " AS bucket, SUM(total_checks) AS total, SUM(up_checks) AS up FROM " + table
                + " WHERE " + col + " >= ? AND " + col + " < ? AND monitor_type IN " + IN_TYPES + " GROUP BY " + col;
    }

    /**
     * Takım kapsamının günlük serisi: izleme × kova toplamları — params: from, to. {@code bucketExpr} kova ifadesi,
     * {@code nextDayExpr} kovanın İstanbul'da ertesi güne düşüp düşmediği (1/0; sabit {@code 0} ise gruplamaya girmez).
     */
    static String perMonitorBucketSql(String table, String col, String bucketExpr, String nextDayExpr) {
        boolean constant = "0".equals(nextDayExpr);
        return "SELECT monitor_type, monitor_key, " + bucketExpr + " AS bucket, " + nextDayExpr + " AS next_day, "
                + "SUM(total_checks) AS total, SUM(up_checks) AS up FROM " + table + " WHERE " + col + " >= ? AND " + col
                + " < ? AND monitor_type IN " + IN_TYPES + " GROUP BY monitor_type, monitor_key, " + bucketExpr
                + (constant ? "" : ", " + nextDayExpr);
    }

    /**
     * Saatlik özetten izleme × İstanbul günü: UTC günü ({@code substr(…,1,10)}) + İstanbul'da ertesi güne düşen saat mi
     * ({@code saat ≥ boundaryHour}). Ay boyunca sabit, tam saatlik, pozitif ofset gerekir ({@link #dayBoundaryHour});
     * değilse {@code boundaryHour < 0} → saat kovası olduğu gibi döner (satır çok, sonuç aynı).
     */
    static String perMonitorDaySqlHourly(int boundaryHour) {
        if (boundaryHour < 0) return perMonitorBucketSql("monitor_check_hourly", "hour_bucket", "hour_bucket", "0");
        String late = "CASE WHEN substr(hour_bucket, 12, 2) >= '" + String.format(Locale.ROOT, "%02d", boundaryHour)
                + "' THEN 1 ELSE 0 END";
        return perMonitorBucketSql("monitor_check_hourly", "hour_bucket", "substr(hour_bucket, 1, 10)", late);
    }

    /**
     * {@code zone}'da gün sınırına denk gelen UTC saati (UTC+3 → 21: 21:00Z ve sonrası ertesi gündür). Ofset ay boyunca
     * değişiyorsa, tam saat değilse ya da negatifse -1 (çağıran saat kovasına düşer). UTC+0 → 24 (hiçbir saat ertesi gün değil).
     */
    static int dayBoundaryHour(ZoneId zone, Instant from, Instant to) {
        ZoneOffset a = zone.getRules().getOffset(from);
        ZoneOffset b = zone.getRules().getOffset(to.minusSeconds(1));
        int secs = a.getTotalSeconds();
        if (!a.equals(b) || secs < 0 || secs % 3600 != 0 || secs >= 86_400) return -1;
        return 24 - secs / 3600;
    }

    private final MonitoringOverviewService overviewService;
    private final JdbcTemplate jdbc;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /**
     * İzleme kimliği (rollup anahtarı {@code TÜR|anahtar}) → takım/grup. {@code host} + {@code derived}: envanter türevi
     * (Durum İzleme, envanterden türeyen Port) satırın host'u — takım kapsamında SY ya da UG eşlemesi için.
     */
    record MonitorRef(String name, String type, Long teamId, String teamName, String group, boolean active,
                      String host, boolean derived) {
        MonitorRef(String name, String type, Long teamId, String teamName, String group, boolean active) {
            this(name, type, teamId, teamName, group, active, null, false);
        }
    }

    /**
     * Rollup okuması: anahtar → [cur_total, cur_up, prev_total, prev_up]; gün → [total, up] (kurum geneli seri).
     * {@code monitorDays}: takım kapsamında izleme anahtarı → İstanbul günü → [total, up] (kurum kapsamında null; takım
     * serisi bundan, kapsamdaki izlemelerle bellekte kurulur).
     */
    record Rollup(Map<String, long[]> byKey, Map<LocalDate, long[]> daily, String source, boolean approximate,
                  Map<String, Map<LocalDate, long[]>> monitorDays) {
        Rollup(Map<String, long[]> byKey, Map<LocalDate, long[]> daily, String source, boolean approximate) {
            this(byKey, daily, source, approximate, null);
        }
        static Rollup empty() { return new Rollup(Map.of(), Map.of(), "none", false); }
    }

    /** Koşu boyu paylaşılan ham veri anahtarlarının öneki ({@link ExecutiveSummaryContext#shared}). */
    static final String SHARED = "availability.";

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        Rollup rollup = loadRollup(ctx);
        Map<String, MonitorRef> monitors = monitorRefs(ctx);
        return evaluate(ctx, rollup, monitors);
    }

    // ── Veri okuma (sabit sorgu sayısı) ─────────────────────────────────────────────────────────────────────────────

    /**
     * Kurum geneli ham toplamlar koşu boyu paylaşılır: aynı gönderim koşusundaki kurum + takım özetleri izleme toplamını
     * TEK kez okur. Kurum kapsamı günlük seriyi kurum geneli kovadan, takım kapsamı izleme × günden (koşu başına bir kez)
     * kurar; takım süzmesi {@link #evaluate}'te bellekte.
     */
    Rollup loadRollup(ExecutiveSummaryContext ctx) {
        String cur = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.from());
        String prev = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.prevFrom());
        String end = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.to());
        boolean team = ctx.teamScoped();
        try {
            Map<String, long[]> byKey = ctx.shared(SHARED + "hourly.per_monitor|" + cur,
                    () -> perMonitor("monitor_check_hourly", "hour_bucket", cur, prev, end));
            boolean hasCur = byKey.values().stream().anyMatch(v -> v[0] > 0);
            if (hasCur) {
                if (team) {
                    int boundary = dayBoundaryHour(ExecutiveSummaryContext.IST, ctx.from(), ctx.to());
                    Map<String, Map<LocalDate, long[]>> days = ctx.shared(SHARED + "hourly.monitor_days|" + cur,
                            () -> monitorDays(perMonitorDaySqlHourly(boundary), boundary < 0 ? DayMode.HOUR : DayMode.SPLIT,
                                    cur, end));
                    return new Rollup(byKey, Map.of(), "hourly_rollup", false, days);
                }
                Map<LocalDate, long[]> daily = ctx.shared(SHARED + "hourly.org_days|" + cur, () -> {
                    Map<LocalDate, long[]> out = new TreeMap<>();
                    for (Map<String, Object> r : jdbc.queryForList(perBucketSql("monitor_check_hourly", "hour_bucket"), cur, end)) {
                        LocalDate day = istDayOfHour(String.valueOf(r.get("bucket")));
                        if (day == null) continue;
                        long[] acc = out.computeIfAbsent(day, d -> new long[2]);
                        acc[0] += asLong(r.get("total"));
                        acc[1] += asLong(r.get("up"));
                    }
                    return out;
                });
                return new Rollup(byKey, daily, "hourly_rollup", false);
            }
            // Saatlik özet yok (saklama dışı / henüz yazılmadı) → günlük özet, UTC gün sınırları (yaklaşık).
            String dCur = ctx.month().atDay(1).toString();
            String dPrev = ctx.month().minusMonths(1).atDay(1).toString();
            String dEnd = ctx.month().plusMonths(1).atDay(1).toString();
            Map<String, long[]> dByKey = ctx.shared(SHARED + "daily.per_monitor|" + dCur,
                    () -> perMonitor("monitor_check_daily", "day", dCur, dPrev, dEnd));
            if (dByKey.values().stream().noneMatch(v -> v[0] > 0)) {
                // Günlükte de cari ay yok → veri yok (önceki ay saatlikte olabilir; sonuç yine "veri yok" der).
                return new Rollup(byKey, Map.of(), byKey.isEmpty() ? "none" : "hourly_rollup", false);
            }
            if (team) {
                Map<String, Map<LocalDate, long[]>> days = ctx.shared(SHARED + "daily.monitor_days|" + dCur,
                        () -> monitorDays(perMonitorBucketSql("monitor_check_daily", "day", "day", "0"), DayMode.DAY,
                                dCur, dEnd));
                return new Rollup(dByKey, Map.of(), "daily_rollup", true, days);
            }
            Map<LocalDate, long[]> daily = ctx.shared(SHARED + "daily.org_days|" + dCur, () -> {
                Map<LocalDate, long[]> out = new TreeMap<>();
                for (Map<String, Object> r : jdbc.queryForList(perBucketSql("monitor_check_daily", "day"), dCur, dEnd)) {
                    try {
                        LocalDate day = LocalDate.parse(String.valueOf(r.get("bucket")).substring(0, 10));
                        long[] acc = out.computeIfAbsent(day, d -> new long[2]);
                        acc[0] += asLong(r.get("total"));
                        acc[1] += asLong(r.get("up"));
                    } catch (Exception ignored) { /* bozuk kova atlanır */ }
                }
                return out;
            });
            return new Rollup(dByKey, daily, "daily_rollup", true);
        } catch (Exception e) {
            log.debug("Yönetici özeti: erişilebilirlik özeti okunamadı: {}", e.toString());
            return Rollup.empty();
        }
    }

    /** Kova → İstanbul günü çevirisi: SPLIT = UTC günü + ertesi gün bayrağı, HOUR = saat kovası, DAY = gün (UTC, yaklaşık). */
    enum DayMode { SPLIT, HOUR, DAY }

    /**
     * İzleme × İstanbul günü toplamları (takım serisi için, koşu başına bir kez). Satırlar akış hâlinde işlenir (harita
     * listesi kurulmaz); bozuk kova atlanır.
     */
    Map<String, Map<LocalDate, long[]>> monitorDays(String sql, DayMode mode, String from, String to) {
        Map<String, Map<LocalDate, long[]>> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            String type = rs.getString("monitor_type");
            String key = rs.getString("monitor_key");
            String bucket = rs.getString("bucket");
            if (type == null || key == null || bucket == null) return;
            LocalDate day;
            try {
                day = switch (mode) {
                    case SPLIT -> {
                        LocalDate d = LocalDate.parse(bucket.substring(0, 10));
                        yield rs.getInt("next_day") == 1 ? d.plusDays(1) : d;
                    }
                    case HOUR -> istDayOfHour(bucket);
                    case DAY -> LocalDate.parse(bucket.substring(0, 10));
                };
            } catch (Exception ignored) {
                return;                                              // bozuk kova atlanır
            }
            if (day == null) return;
            long[] acc = out.computeIfAbsent(type + "|" + key.trim(), k -> new TreeMap<>())
                    .computeIfAbsent(day, d -> new long[2]);
            acc[0] += rs.getLong("total");
            acc[1] += rs.getLong("up");
        }, from, to);
        return out;
    }

    private Map<String, long[]> perMonitor(String table, String col, String cur, String prev, String end) {
        Map<String, long[]> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(perMonitorSql(table, col), cur, cur, cur, cur, prev, end)) {
            Object type = r.get("monitor_type"), key = r.get("monitor_key");
            if (type == null || key == null) continue;
            out.put(String.valueOf(type) + "|" + String.valueOf(key).trim(), new long[]{
                    asLong(r.get("cur_total")), asLong(r.get("cur_up")),
                    asLong(r.get("prev_total")), asLong(r.get("prev_up"))});
        }
        return out;
    }

    /** "2026-08-31T21" (UTC saat kovası) → İstanbul günü (2026-09-01). */
    static LocalDate istDayOfHour(String bucket) {
        if (bucket == null || bucket.length() < 13) return null;
        try {
            return LocalDateTime.parse(bucket.substring(0, 13) + ":00:00").atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ExecutiveSummaryContext.IST).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * İzleme anahtarı → takım/grup (kurum geneli; takım süzmesi {@link #inScope} ile). Durum İzleme satırları SÜZÜLMEMİŞ
     * envanterden kurulur: takım kapsamında kapsam dışı bir alan adının kontrolleri "silinmiş izleme" sayılmasın.
     */
    Map<String, MonitorRef> monitorRefs(ExecutiveSummaryContext ctx) {
        Map<String, MonitorRef> out = new HashMap<>(ctx.shared(SHARED + "overview_refs", this::overviewRefs));
        // Durum İzleme (sertifika envanteri erişimi) — anahtar alan adı; takım/grup envanterden.
        Map<Long, String> names = safeTeamNames(ctx);
        try {
            for (ExecutiveSummaryContext.InventoryRow inv : ctx.allInventory().values()) {
                if (inv.domain() == null) continue;
                String group = inv.groupName() != null && !inv.groupName().isBlank() ? inv.groupName().trim() : null;
                out.put("UPTIME|" + inv.domain(), new MonitorRef(inv.domain(), "uptime", inv.teamId(),
                        inv.teamId() == null ? null : names.get(inv.teamId()), group, true, inv.domain(), true));
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: envanter okunamadı: {}", e.toString());
        }
        return out;
    }

    /** İzleme Panosu'nun kurum geneli satırları → izleme eşlemesi (koşu boyu paylaşılır; hata → boş, bölüm düşmez). */
    @SuppressWarnings("unchecked")
    private Map<String, MonitorRef> overviewRefs() {
        Map<String, MonitorRef> out = new HashMap<>();
        try {
            Map<String, Object> ov = overviewService.build(TtlMemo.scopeKey(true, null), t -> true, true,
                    MonitoringOverviewService.ORG_SUMMARY_WINDOW_HOURS, false);
            List<Map<String, Object>> rows = ov != null && ov.get("monitors") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            for (Map<String, Object> r : rows) {
                if (r == null || Boolean.TRUE.equals(r.get("deleted"))) continue;
                String rt = OVERVIEW_TO_ROLLUP.get(String.valueOf(r.get("type")));
                if (rt == null || r.get("id") == null) continue;
                String group = r.get("group_name") instanceof String g && !g.isBlank() ? g.trim() : null;
                boolean active = Boolean.TRUE.equals(r.get("active")) && !Boolean.TRUE.equals(r.get("inventory_inactive"));
                // Port'ta standalone=false → envanter türevi satır (host'u envanter alan adı); diğer türlerde null.
                boolean derived = Boolean.FALSE.equals(r.get("standalone"));
                out.put(rt + "|" + r.get("id"), new MonitorRef(str(r.get("name")), String.valueOf(r.get("type")),
                        asLongObj(r.get("team_id")), str(r.get("team_name")), group, active, str(r.get("target")), derived));
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: pano satırları okunamadı: {}", e.toString());
        }
        return out;
    }

    /**
     * İzleme kapsamda mı: kurum geneli → hepsi; takım → kendi takımı kapsam takımı YA DA envanter türevi ve host'u
     * kapsamdaki envanterde (SY ya da UG).
     */
    static boolean inScope(ExecutiveSummaryContext ctx, MonitorRef ref) {
        if (!ctx.teamScoped()) return true;
        if (ref == null) return false;
        if (ref.teamId() != null && ctx.teamInScope(ref.teamId())) return true;
        return ref.derived() && ctx.domainInScope(ref.host());
    }

    private static Map<Long, String> safeTeamNames(ExecutiveSummaryContext ctx) {
        try {
            Map<Long, String> m = ctx.teamNames();
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Değerlendirme (saf — birim testleri doğrudan sınar) ─────────────────────────────────────────────────────────

    private static final class Acc {
        final String key;
        String name;
        String type;
        Long teamId;
        String teamName;
        String group;
        long curTotal, curUp, prevTotal, prevUp;
        int monitors;

        Acc(String key) { this.key = key; }

        void add(long[] v) {
            curTotal += v[0];
            curUp += Math.min(v[1], v[0]);
            prevTotal += v[2];
            prevUp += Math.min(v[3], v[2]);
            if (v[0] > 0) monitors++;
        }

        Double cur() { return curTotal > 0 ? pctOf(curUp, curTotal) : null; }
        Double prev() { return prevTotal > 0 ? pctOf(prevUp, prevTotal) : null; }
    }

    static Double pctOf(long up, long total) {
        if (total <= 0) return null;
        return Math.round(up * 1_000_000.0 / total) / 10_000.0;   // 4 ondalık (yüzde)
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, Rollup rollup, Map<String, MonitorRef> monitors) {
        if (ctx.teamScoped()) return evaluateTeam(ctx, rollup, monitors);
        double target = ctx.availabilityTarget();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).headlineKpi("org_availability");
        Acc org = new Acc("org");
        Map<String, Acc> teams = new LinkedHashMap<>();
        Map<String, Acc> services = new LinkedHashMap<>();
        int unmapped = 0;
        for (Map.Entry<String, long[]> e : rollup.byKey().entrySet()) {
            long[] v = e.getValue();
            if (v[0] <= 0 && v[2] <= 0) continue;
            MonitorRef ref = monitors.get(e.getKey());
            if (ref == null) { unmapped++; continue; }      // silinmiş izleme → dahil değil
            org.add(v);
            String tk = ref.teamId() == null ? "-" : String.valueOf(ref.teamId());
            Acc t = teams.computeIfAbsent(tk, k -> new Acc(k));
            t.teamId = ref.teamId();
            if (t.teamName == null) t.teamName = ref.teamName();
            t.add(v);
            String sk = tk + "|" + (ref.group() == null ? "" : ref.group().toLowerCase(Locale.ROOT));
            Acc s = services.computeIfAbsent(sk, k -> new Acc(k));
            s.teamId = ref.teamId();
            if (s.teamName == null) s.teamName = ref.teamName();
            if (s.group == null) s.group = ref.group();
            s.add(v);
        }
        int activeNoData = 0;
        for (Map.Entry<String, MonitorRef> m : monitors.entrySet()) {
            if (!m.getValue().active()) continue;
            long[] v = rollup.byKey().get(m.getKey());
            if (v == null || v[0] <= 0) activeNoData++;
        }

        Double orgCur = org.cur(), orgPrev = org.prev();
        b.data("target", target).data("source", rollup.source()).data("approximate", rollup.approximate())
                .data("org_availability", orgCur).data("prev_availability", orgPrev)
                .data("daily", dailySeries(rollup.daily()));

        notes(b, ctx, rollup, unmapped);
        if (orgCur == null) {
            b.status(NO_DATA).verdict("NO_DATA", T_NEUTRAL,
                    "Bu ay için erişilebilirlik verisi yok (gece özeti henüz yazılmamış olabilir).");
            b.kpi(new Kpi("org_availability", "Kurum erişilebilirliği", null, "pct", T_NEUTRAL,
                    "Hedef " + ExecFormat.pct(target, 3), List.of(pct(target)), null, null, null));
            return b.build();
        }

        List<Acc> teamList = new ArrayList<>(teams.values());
        int teamsMeeting = 0;
        for (Acc t : teamList) if (t.cur() != null && t.cur() >= target) teamsMeeting++;
        List<Acc> svcList = new ArrayList<>(services.values());
        int svcMeasured = 0, svcMeeting = 0;
        for (Acc s : svcList) {
            if (s.cur() == null) continue;
            svcMeasured++;
            if (s.cur() >= target) svcMeeting++;
        }
        int teamsMeasured = (int) teamList.stream().filter(t -> t.cur() != null).count();
        int teamsBelow = teamsMeasured - teamsMeeting;
        boolean orgMet = orgCur >= target;
        Double delta = orgPrev == null ? null : round2(orgCur - orgPrev);
        Double budgetBurn = target >= 100 ? null : round2((100 - orgCur) / (100 - target) * 100);
        double downtime = (100 - orgCur) / 100.0 * ctx.elapsedMinutes();

        b.status(!orgMet ? CRITICAL : teamsBelow > 0 ? ATTENTION : OK);
        if (orgMet) {
            b.verdict("ORG_MET", T_OK, "Kurum erişilebilirliği " + ExecFormat.pct(orgCur, 3) + " — hedef "
                    + ExecFormat.pct(target, 3) + " karşılandı.", pct(orgCur), pct(target));
        } else {
            b.verdict("ORG_MISSED", T_BAD, "Kurum erişilebilirliği " + ExecFormat.pct(orgCur, 3) + " — hedef "
                    + ExecFormat.pct(target, 3) + " karşılanmadı (" + ExecFormat.num(target - orgCur, 3) + " puan altında).",
                    pct(orgCur), pct(target), num(round3(target - orgCur)));
        }
        if (teamsBelow > 0) {
            b.verdict("TEAMS_BELOW", T_WARN, teamsMeasured + " takımdan " + teamsBelow + " tanesi hedefin altında.",
                    teamsMeasured, teamsBelow);
        } else {
            b.verdict("TEAMS_ALL_MET", T_OK, "Ölçülen " + teamsMeasured + " takımın tamamı hedefi karşıladı.", teamsMeasured);
        }

        b.kpi(new Kpi("org_availability", "Kurum erişilebilirliği", orgCur, "pct", orgMet ? T_OK : T_BAD,
                "Hedef " + ExecFormat.pct(target, 3), List.of(pct(target)), delta, "pp",
                delta == null ? null : delta >= 0 ? T_OK : T_BAD));
        b.kpi(new Kpi("target", "Erişilebilirlik hedefi", target, "pct", T_NEUTRAL,
                "Kurum hedefi (Ayarlar)", List.of(), null, null, null));
        b.kpi(new Kpi("teams_meeting", "Hedefi karşılayan takım", teamsMeeting, "int",
                teamsBelow > 0 ? T_WARN : T_OK, teamsMeasured + " takımdan", List.of(teamsMeasured), null, null, null));
        b.kpi(new Kpi("services_meeting", "Hedefi karşılayan hizmet", svcMeeting, "int",
                svcMeeting < svcMeasured ? T_WARN : T_OK, svcMeasured + " hizmetten", List.of(svcMeasured), null, null, null));
        b.kpi(new Kpi("budget_used", "Hata bütçesi kullanımı", budgetBurn, "pct",
                budgetBurn == null ? T_NEUTRAL : budgetBurn > 100 ? T_BAD : budgetBurn > 75 ? T_WARN : T_OK,
                "Hedefin izin verdiği kesintinin kullanılan payı", List.of(), null, null, null));
        b.kpi(new Kpi("downtime_equiv", "Eşdeğer kesinti", round1(downtime), "minutes", T_NEUTRAL,
                "Kontrol ağırlıklı yaklaşık süre", List.of(), null, null, null));
        b.kpi(new Kpi("monitors_measured", "Ölçülen izleme", org.monitors, "int", T_NEUTRAL,
                activeNoData + " aktif izlemede veri yok", List.of(activeNoData), null, null, null));

        // ── Takımlar (en kötü önce) ──
        Collator collator = Collator.getInstance(Locale.forLanguageTag("tr"));
        collator.setStrength(Collator.SECONDARY);
        teamList.sort(Comparator.comparing((Acc a) -> a.cur() == null ? Double.MAX_VALUE : a.cur())
                .thenComparing(a -> a.teamName == null ? "￿" : a.teamName, collator));
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (Acc t : teamList) {
            if (t.cur() == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team", t.teamName);
            r.put("team_id", t.teamId);
            r.put("monitors", t.monitors);
            r.put("checks", t.curTotal);
            r.put("availability", t.cur());
            r.put("prev", t.prev());
            r.put("delta", t.prev() == null ? null : round2(t.cur() - t.prev()));
            r.put("met", t.cur() >= target ? T_OK : T_BAD);
            teamRows.add(r);
        }
        b.table(new Table("teams", "Takımlara göre", List.of(
                new Column("team", "Takım", "team"),
                new Column("monitors", "İzleme", "int"),
                new Column("checks", "Kontrol", "int"),
                new Column("availability", "Erişilebilirlik", "pct"),
                new Column("prev", "Önceki ay", "pct"),
                new Column("delta", "Fark", "pp"),
                new Column("met", "Hedef", "status")),
                teamRows.size() > TEAM_LIMIT ? teamRows.subList(0, TEAM_LIMIT) : teamRows, teamRows.size(),
                "Bu ay ölçülen takım yok."));

        // ── En kötü hizmetler ──
        b.table(worstServices(svcList, target));
        b.data("teams_measured", teamsMeasured).data("teams_below", teamsBelow);
        return b.build();
    }

    /** En düşük erişilebilirlikli hizmetler (en az {@value #MIN_SERVICE_CHECKS} kontrol, artan) — kurum ve takım aynı tablo. */
    private static Table worstServices(List<Acc> svcList, double target) {
        List<Acc> worst = new ArrayList<>();
        for (Acc s : svcList) if (s.cur() != null && s.curTotal >= MIN_SERVICE_CHECKS) worst.add(s);
        worst.sort(Comparator.comparing((Acc a) -> a.cur()).thenComparing(a -> -a.curTotal));
        List<Map<String, Object>> worstRows = new ArrayList<>();
        for (Acc s : worst.subList(0, Math.min(WORST_LIMIT, worst.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("service", s.group);
            r.put("ungrouped", s.group == null);
            r.put("team", s.teamName);
            r.put("team_id", s.teamId);
            r.put("monitors", s.monitors);
            r.put("checks", s.curTotal);
            r.put("availability", s.cur());
            r.put("met", s.cur() >= target ? T_OK : T_BAD);
            worstRows.add(r);
        }
        return new Table("worst_services", "En düşük erişilebilirlikli hizmetler", List.of(
                new Column("service", "Hizmet", "service"),
                new Column("team", "Takım", "team"),
                new Column("monitors", "İzleme", "int"),
                new Column("checks", "Kontrol", "int"),
                new Column("availability", "Erişilebilirlik", "pct"),
                new Column("met", "Hedef", "status")),
                worstRows, worst.size(), "En az " + MIN_SERVICE_CHECKS + " kontrolü olan hizmet yok.");
    }

    /**
     * TAKIM kapsamı: yalnız {@link #inScope} izlemeler. Hükümler ve ana gösterge "Takım …" kodlarıyla; "takımlara göre"
     * tablosu ve takım sayısı yerine hizmet hükmü + en düşük erişilebilirlikli İZLEMELER. Günlük seri izleme × günden
     * (kapsamdaki izlemeler). Silinmiş izlemeler bir takıma bağlanamaz → sayılmaz, not da düşülmez.
     */
    private SectionResult evaluateTeam(ExecutiveSummaryContext ctx, Rollup rollup, Map<String, MonitorRef> monitors) {
        double target = ctx.availabilityTarget();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).headlineKpi("team_availability");
        Acc team = new Acc("team");
        Map<String, Acc> services = new LinkedHashMap<>();
        List<Acc> monitorAccs = new ArrayList<>();
        for (Map.Entry<String, long[]> e : rollup.byKey().entrySet()) {
            long[] v = e.getValue();
            if (v[0] <= 0 && v[2] <= 0) continue;
            MonitorRef ref = monitors.get(e.getKey());
            if (!inScope(ctx, ref)) continue;                // silinmiş (ref yok) ya da başka takımın izlemesi
            team.add(v);
            String tk = ref.teamId() == null ? "-" : String.valueOf(ref.teamId());
            String sk = tk + "|" + (ref.group() == null ? "" : ref.group().toLowerCase(Locale.ROOT));
            Acc s = services.computeIfAbsent(sk, k -> new Acc(k));
            s.teamId = ref.teamId();
            if (s.teamName == null) s.teamName = ref.teamName();
            if (s.group == null) s.group = ref.group();
            s.add(v);
            Acc m = new Acc(e.getKey());
            m.name = ref.name();
            m.type = ref.type();
            m.teamId = ref.teamId();
            m.teamName = ref.teamName();
            m.group = ref.group();
            m.add(v);
            monitorAccs.add(m);
        }
        int activeNoData = 0;
        for (Map.Entry<String, MonitorRef> m : monitors.entrySet()) {
            if (!m.getValue().active() || !inScope(ctx, m.getValue())) continue;
            long[] v = rollup.byKey().get(m.getKey());
            if (v == null || v[0] <= 0) activeNoData++;
        }
        // Günlük seri: izleme × gün toplamından YALNIZ kapsamdaki izlemeler (kurum serisi takımda asla gösterilmez)
        Map<LocalDate, long[]> daily = new TreeMap<>();
        if (rollup.monitorDays() != null) {
            for (Map.Entry<String, Map<LocalDate, long[]>> e : rollup.monitorDays().entrySet()) {
                if (!inScope(ctx, monitors.get(e.getKey()))) continue;
                for (Map.Entry<LocalDate, long[]> d : e.getValue().entrySet()) {
                    long[] acc = daily.computeIfAbsent(d.getKey(), k -> new long[2]);
                    acc[0] += d.getValue()[0];
                    acc[1] += Math.min(d.getValue()[1], d.getValue()[0]);
                }
            }
        }

        Double cur = team.cur(), prev = team.prev();
        b.data("target", target).data("source", rollup.source()).data("approximate", rollup.approximate())
                .data("team_availability", cur).data("prev_availability", prev)
                .data("daily", dailySeries(daily));
        teamNotes(b, ctx, rollup);
        if (cur == null) {
            b.status(NO_DATA).verdict("TEAM_NO_DATA", T_NEUTRAL, "Bu ay takımın erişilebilirlik verisi yok: ölçülen "
                    + "izlemesi yok ya da gece özeti henüz yazılmamış.");
            b.kpi(new Kpi("team_availability", "Takım erişilebilirliği", null, "pct", T_NEUTRAL,
                    "Hedef " + ExecFormat.pct(target, 3), List.of(pct(target)), null, null, null));
            return b.build();
        }

        List<Acc> svcList = new ArrayList<>(services.values());
        int svcMeasured = 0, svcMeeting = 0;
        for (Acc s : svcList) {
            if (s.cur() == null) continue;
            svcMeasured++;
            if (s.cur() >= target) svcMeeting++;
        }
        int svcBelow = svcMeasured - svcMeeting;
        boolean met = cur >= target;
        Double delta = prev == null ? null : round2(cur - prev);
        Double budgetBurn = target >= 100 ? null : round2((100 - cur) / (100 - target) * 100);
        double downtime = (100 - cur) / 100.0 * ctx.elapsedMinutes();

        b.status(!met ? CRITICAL : svcBelow > 0 ? ATTENTION : OK);
        if (met) {
            b.verdict("TEAM_MET", T_OK, "Takım erişilebilirliği " + ExecFormat.pct(cur, 3) + " — hedef "
                    + ExecFormat.pct(target, 3) + " karşılandı.", pct(cur), pct(target));
        } else {
            b.verdict("TEAM_MISSED", T_BAD, "Takım erişilebilirliği " + ExecFormat.pct(cur, 3) + " — hedef "
                    + ExecFormat.pct(target, 3) + " karşılanmadı (" + ExecFormat.num(target - cur, 3) + " puan altında).",
                    pct(cur), pct(target), num(round3(target - cur)));
        }
        if (svcBelow > 0) {
            b.verdict("SERVICES_BELOW", T_WARN, svcMeasured + " hizmetten " + svcBelow + " tanesi hedefin altında.",
                    svcMeasured, svcBelow);
        } else {
            b.verdict("SERVICES_ALL_MET", T_OK, "Ölçülen " + svcMeasured + " hizmetin tamamı hedefi karşıladı.", svcMeasured);
        }

        b.kpi(new Kpi("team_availability", "Takım erişilebilirliği", cur, "pct", met ? T_OK : T_BAD,
                "Hedef " + ExecFormat.pct(target, 3), List.of(pct(target)), delta, "pp",
                delta == null ? null : delta >= 0 ? T_OK : T_BAD));
        b.kpi(new Kpi("team_target", "Erişilebilirlik hedefi", target, "pct", T_NEUTRAL,
                "Ayarlardaki ortak hedef", List.of(), null, null, null));
        b.kpi(new Kpi("services_meeting", "Hedefi karşılayan hizmet", svcMeeting, "int",
                svcMeeting < svcMeasured ? T_WARN : T_OK, svcMeasured + " hizmetten", List.of(svcMeasured), null, null, null));
        b.kpi(new Kpi("budget_used", "Hata bütçesi kullanımı", budgetBurn, "pct",
                budgetBurn == null ? T_NEUTRAL : budgetBurn > 100 ? T_BAD : budgetBurn > 75 ? T_WARN : T_OK,
                "Hedefin izin verdiği kesintinin kullanılan payı", List.of(), null, null, null));
        b.kpi(new Kpi("downtime_equiv", "Eşdeğer kesinti", round1(downtime), "minutes", T_NEUTRAL,
                "Kontrol ağırlıklı yaklaşık süre", List.of(), null, null, null));
        b.kpi(new Kpi("monitors_measured", "Ölçülen izleme", team.monitors, "int", T_NEUTRAL,
                activeNoData + " aktif izlemede veri yok", List.of(activeNoData), null, null, null));

        b.table(worstServices(svcList, target));

        // ── En düşük erişilebilirlikli izlemeler (takımın kendi kırılımı) ──
        List<Acc> worst = new ArrayList<>();
        for (Acc m : monitorAccs) if (m.cur() != null && m.curTotal >= MIN_SERVICE_CHECKS) worst.add(m);
        worst.sort(Comparator.comparing((Acc a) -> a.cur()).thenComparing(a -> -a.curTotal)
                .thenComparing(a -> a.key));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Acc m : worst.subList(0, Math.min(WORST_LIMIT, worst.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("monitor", m.name);
            r.put("monitor_type", m.type);
            r.put("service", m.group);
            r.put("ungrouped", m.group == null);
            r.put("checks", m.curTotal);
            r.put("availability", m.cur());
            r.put("met", m.cur() >= target ? T_OK : T_BAD);
            rows.add(r);
        }
        b.table(new Table("worst_monitors", "En düşük erişilebilirlikli izlemeler", List.of(
                new Column("monitor", "İzleme", "text"),
                new Column("monitor_type", "Tür", "monitor_type"),
                new Column("service", "Hizmet", "service"),
                new Column("checks", "Kontrol", "int"),
                new Column("availability", "Erişilebilirlik", "pct"),
                new Column("met", "Hedef", "status")),
                rows, worst.size(), "En az " + MIN_SERVICE_CHECKS + " kontrolü olan izleme yok."));
        b.data("services_measured", svcMeasured).data("services_below", svcBelow);
        return b.build();
    }

    /** Takım notları: yöntem (kapsam tanımıyla), yaklaşıklık, ölçü kapsamı, devam eden ay. */
    private static void teamNotes(SectionResult.Builder b, ExecutiveSummaryContext ctx, Rollup rollup) {
        b.note("TEAM_METHOD", "Erişilebilirlik = başarılı kontrol / toplam kontrol (kontrol ağırlıklı), gece yazılan saatlik "
                + "özetten; ay sınırları Türkiye saatine göre. Takımın kendi izlemeleri ile sorumlu (SY) ya da uygulama "
                + "geliştirici (UG) olduğu envanter kayıtlarının Durum İzleme ve türev Port kontrolleri sayılır. Resmî bir SLO "
                + "değil, ortak hedefe göre ölçülen değerdir.");
        if (rollup.approximate()) {
            b.note("APPROX", "Bu ay için saatlik özet bulunamadı; günlük özet (UTC gün sınırları) kullanıldı — ay "
                    + "sınırında birkaç saatlik kayma olabilir.");
        }
        b.note("SCOPE", "DNS ve alan adı kaydı izlemelerinin erişilebilirlik ölçüsü yoktur. Bakım pencereleri yalnız "
                + "Durum İzleme kontrollerinde sayım dışıdır.");
        if (!ctx.complete()) {
            b.note("PARTIAL", "Ay devam ediyor; özet gece işlendiği için son ~24 saat dahil olmayabilir.");
        }
    }

    private static void notes(SectionResult.Builder b, ExecutiveSummaryContext ctx, Rollup rollup, int unmapped) {
        b.note("METHOD", "Erişilebilirlik = başarılı kontrol / toplam kontrol (kontrol ağırlıklı), gece yazılan saatlik "
                + "özetten; ay sınırları Türkiye saatine göre. Resmî bir SLO değil, kurum hedefine göre ölçülen değerdir.");
        if (rollup.approximate()) {
            b.note("APPROX", "Bu ay için saatlik özet bulunamadı; günlük özet (UTC gün sınırları) kullanıldı — ay "
                    + "sınırında birkaç saatlik kayma olabilir.");
        }
        b.note("SCOPE", "DNS ve alan adı kaydı izlemelerinin erişilebilirlik ölçüsü yoktur. Bakım pencereleri yalnız "
                + "Durum İzleme kontrollerinde sayım dışıdır.");
        if (!ctx.complete()) {
            b.note("PARTIAL", "Ay devam ediyor; özet gece işlendiği için son ~24 saat dahil olmayabilir.");
        }
        if (unmapped > 0) {
            b.note("UNMAPPED", unmapped + " silinmiş izlemenin kontrolleri hesaba katılmadı.", unmapped);
        }
    }

    private static List<Map<String, Object>> dailySeries(Map<LocalDate, long[]> daily) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : new TreeMap<>(daily).entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", e.getKey().toString());
            p.put("availability", pctOf(e.getValue()[1], e.getValue()[0]));
            p.put("checks", e.getValue()[0]);
            out.add(p);
        }
        return out;
    }

    static double round1(double v) { return Math.round(v * 10) / 10.0; }
    static double round2(double v) { return Math.round(v * 100) / 100.0; }
    static double round3(double v) { return Math.round(v * 1000) / 1000.0; }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Long asLongObj(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
