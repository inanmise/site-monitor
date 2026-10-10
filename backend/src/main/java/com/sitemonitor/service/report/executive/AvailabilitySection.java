package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * <h2>Sorgu bütçesi</h2>
 * İki toplu sorgu (izleme başına cari + önceki ay toplamı; kurum geneli saat kovaları → İstanbul günleri) + gerekirse
 * aynı ikisi günlük özete. Pano satırları ve envanter paylaşılan bellekten.
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

    private final MonitoringOverviewService overviewService;
    private final JdbcTemplate jdbc;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /** İzleme kimliği (rollup anahtarı {@code TÜR|anahtar}) → takım/grup. */
    record MonitorRef(String name, String type, Long teamId, String teamName, String group, boolean active) { }

    /** Rollup okuması: anahtar → [cur_total, cur_up, prev_total, prev_up]; gün → [total, up]. */
    record Rollup(Map<String, long[]> byKey, Map<LocalDate, long[]> daily, String source, boolean approximate) {
        static Rollup empty() { return new Rollup(Map.of(), Map.of(), "none", false); }
    }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        Rollup rollup = loadRollup(ctx);
        Map<String, MonitorRef> monitors = monitorRefs(ctx);
        return evaluate(ctx, rollup, monitors);
    }

    // ── Veri okuma (sabit sorgu sayısı) ─────────────────────────────────────────────────────────────────────────────

    Rollup loadRollup(ExecutiveSummaryContext ctx) {
        String cur = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.from());
        String prev = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.prevFrom());
        String end = ExecutiveSummaryContext.HOUR_BUCKET.format(ctx.to());
        try {
            Map<String, long[]> byKey = perMonitor("monitor_check_hourly", "hour_bucket", cur, prev, end);
            boolean hasCur = byKey.values().stream().anyMatch(v -> v[0] > 0);
            if (hasCur) {
                Map<LocalDate, long[]> daily = new TreeMap<>();
                for (Map<String, Object> r : jdbc.queryForList(perBucketSql("monitor_check_hourly", "hour_bucket"), cur, end)) {
                    LocalDate day = istDayOfHour(String.valueOf(r.get("bucket")));
                    if (day == null) continue;
                    long[] acc = daily.computeIfAbsent(day, d -> new long[2]);
                    acc[0] += asLong(r.get("total"));
                    acc[1] += asLong(r.get("up"));
                }
                return new Rollup(byKey, daily, "hourly_rollup", false);
            }
            // Saatlik özet yok (saklama dışı / henüz yazılmadı) → günlük özet, UTC gün sınırları (yaklaşık).
            String dCur = ctx.month().atDay(1).toString();
            String dPrev = ctx.month().minusMonths(1).atDay(1).toString();
            String dEnd = ctx.month().plusMonths(1).atDay(1).toString();
            Map<String, long[]> dByKey = perMonitor("monitor_check_daily", "day", dCur, dPrev, dEnd);
            if (dByKey.values().stream().noneMatch(v -> v[0] > 0)) {
                // Günlükte de cari ay yok → veri yok (önceki ay saatlikte olabilir; sonuç yine "veri yok" der).
                return new Rollup(byKey, Map.of(), byKey.isEmpty() ? "none" : "hourly_rollup", false);
            }
            Map<LocalDate, long[]> daily = new TreeMap<>();
            for (Map<String, Object> r : jdbc.queryForList(perBucketSql("monitor_check_daily", "day"), dCur, dEnd)) {
                try {
                    LocalDate day = LocalDate.parse(String.valueOf(r.get("bucket")).substring(0, 10));
                    long[] acc = daily.computeIfAbsent(day, d -> new long[2]);
                    acc[0] += asLong(r.get("total"));
                    acc[1] += asLong(r.get("up"));
                } catch (Exception ignored) { /* bozuk kova atlanır */ }
            }
            return new Rollup(dByKey, daily, "daily_rollup", true);
        } catch (Exception e) {
            log.debug("Yönetici özeti: erişilebilirlik özeti okunamadı: {}", e.toString());
            return Rollup.empty();
        }
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

    @SuppressWarnings("unchecked")
    Map<String, MonitorRef> monitorRefs(ExecutiveSummaryContext ctx) {
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
                out.put(rt + "|" + r.get("id"), new MonitorRef(str(r.get("name")), String.valueOf(r.get("type")),
                        asLongObj(r.get("team_id")), str(r.get("team_name")), group, active));
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: pano satırları okunamadı: {}", e.toString());
        }
        // Durum İzleme (sertifika envanteri erişimi) — anahtar alan adı; takım/grup envanterden.
        Map<Long, String> names = safeTeamNames(ctx);
        try {
            for (ExecutiveSummaryContext.InventoryRow inv : ctx.inventory().values()) {
                if (inv.domain() == null) continue;
                String group = inv.groupName() != null && !inv.groupName().isBlank() ? inv.groupName().trim() : null;
                out.put("UPTIME|" + inv.domain(), new MonitorRef(inv.domain(), "uptime", inv.teamId(),
                        inv.teamId() == null ? null : names.get(inv.teamId()), group, true));
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: envanter okunamadı: {}", e.toString());
        }
        return out;
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
        b.table(new Table("worst_services", "En düşük erişilebilirlikli hizmetler", List.of(
                new Column("service", "Hizmet", "service"),
                new Column("team", "Takım", "team"),
                new Column("monitors", "İzleme", "int"),
                new Column("checks", "Kontrol", "int"),
                new Column("availability", "Erişilebilirlik", "pct"),
                new Column("met", "Hedef", "status")),
                worstRows, worst.size(), "En az " + MIN_SERVICE_CHECKS + " kontrolü olan hizmet yok."));
        b.data("teams_measured", teamsMeasured).data("teams_below", teamsBelow);
        return b.build();
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
