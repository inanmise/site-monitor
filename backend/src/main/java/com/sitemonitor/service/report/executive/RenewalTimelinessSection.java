package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.service.RenewalForecastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (d) YENİLEME SÜRESİNE UYUM — "sertifikalar zamanında yenileniyor mu?"
 *
 * <h2>Yenileme tespiti</h2>
 * Vade Takvimi'nin kuralı ({@code RenewalForecastService.renewals}): {@code certificate_checks}'te alan adı × parmak izi
 * grupları (TEK toplu sorgu — ham satır taşınmaz), ilk görülme sırasıyla. Ardışık iki grup arasında geçiş, yeni grup
 * ay içinde (İstanbul) ilk görüldüyse VE yeni sertifikanın bitişi eskisinden SONRAYSA bir yenilemedir (bitişi uzatmayan
 * parmak izi değişimi — yük dengeleyicide iki düğüm, aynı tarihli yeniden düzenleme — yenileme sayılmaz). Yenileme anı =
 * yeni sertifikanın izlemede İLK görüldüğü an. Önceki grubu görebilmek için sorgu ay başından
 * {@value #LOOKBACK_DAYS} gün geriden başlar.
 *
 * <h2>Sınıflar (kalan süre L = eski bitiş − yenileme anı, tam gün, aşağı yuvarlanır)</h2>
 * <ul>
 *   <li>{@code ON_TIME}: L ≥ hedef süre — "zamanında";</li>
 *   <li>{@code LATE}: {@value #LAST_MINUTE_DAYS} ≤ L &lt; hedef — "geç";</li>
 *   <li>{@code LAST_MINUTE}: 0 ≤ L &lt; {@value #LAST_MINUTE_DAYS} — "son dakika";</li>
 *   <li>{@code AFTER_EXPIRY}: L &lt; 0 — "süresi dolduktan sonra".</li>
 * </ul>
 * <b>Hedef süre</b> = {@code site.monitor.executive-summary.renewal-target-days} (varsayılan 30 gün); 0 ise Vade
 * Takvimi'nin tier bazlı yenileme süresi ({@code site.monitor.renewal.lead-days[-tN]}). <b>Uyum %</b> = ON_TIME / toplam.
 *
 * <h2>Plan ve gecikme (rapor anı)</h2>
 * <ul>
 *   <li>Ay içine planlanan yenilemeler: envanterde {@code renewal_planned_at} bu ayda olan kayıtlar. "Yapıldı" =
 *       Vade Takvimi'nin kuralı: şu an sunulan sertifikanın düzenlenme günü (İstanbul) ≥ plan günü. "Kaçırıldı" =
 *       plan günü geçti ve yapılmadı. Plan alanı yalnız GÜNCEL değeri tutar (geçmiş planlar saklanmaz).</li>
 *   <li>Gecikmede (şimdi): kalan süresi hedef sürenin altına düşmüş VE (planı yok YA DA planı geçmiş ve yapılmamış) aktif
 *       sertifikalar; süresi dolmuşlar her durumda.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenewalTimelinessSection implements ExecutiveSummarySection {

    public static final String KEY = "renewals";
    static final int ORDER = 40;
    static final String TITLE = "Yenileme süresine uyum";
    static final int LOOKBACK_DAYS = 60;
    static final int LAST_MINUTE_DAYS = 7;
    static final int LIST_LIMIT = 15;
    /** Uyum bu yüzdenin altındaysa "takip gerekli". */
    static final int COMPLIANCE_WARN_PCT = 80;

    static final String ON_TIME = "ON_TIME", LATE = "LATE", LAST_MINUTE = "LAST_MINUTE", AFTER_EXPIRY = "AFTER_EXPIRY";

    static final String SQL = "SELECT domain, fingerprint, MIN(checked_at) AS first_seen, MAX(not_after) AS not_after "
            + "FROM certificate_checks WHERE checked_at >= ? AND checked_at < ? "
            + "AND fingerprint IS NOT NULL AND fingerprint <> '' GROUP BY domain, fingerprint";

    private final JdbcTemplate jdbc;
    private final RenewalForecastService forecast;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /** Parmak izi grubu. */
    record Group(String fingerprint, String firstSeen, String notAfter) { }

    /** Tespit edilen yenileme. */
    record Renewal(String domain, Instant renewedAt, Instant prevNotAfter, long leadDays, String cls, int target) { }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        Map<String, List<Group>> groups = new HashMap<>();
        boolean readOk = true;
        try {
            String from = ExecutiveSummaryContext.UTC_ISO.format(ctx.from().minus(Duration.ofDays(LOOKBACK_DAYS)));
            jdbc.query(SQL, rs -> {
                String d = rs.getString("domain");
                if (d == null) return;
                groups.computeIfAbsent(d, k -> new ArrayList<>())
                        .add(new Group(rs.getString("fingerprint"), rs.getString("first_seen"), rs.getString("not_after")));
            }, from, ctx.toIso());
        } catch (Exception e) {
            readOk = false;
            log.debug("Yönetici özeti: yenileme geçmişi okunamadı: {}", e.toString());
        }
        Map<String, Integer> lead = null;
        if (ctx.renewalTargetDays() <= 0) {
            try { lead = forecast.leadDays(); } catch (Exception e) { log.debug("Yenileme süresi okunamadı: {}", e.toString()); }
        }
        return evaluate(ctx, groups, lead, readOk);
    }

    /** Hedef süre (gün): ayar &gt; 0 ise o; değilse tier bazlı Vade Takvimi süresi (o da yoksa 30). */
    int targetDays(ExecutiveSummaryContext ctx, Integer tier, Map<String, Integer> lead) {
        if (ctx.renewalTargetDays() > 0) return ctx.renewalTargetDays();
        if (lead == null) return 30;
        try { return forecast.leadFor(tier, lead); } catch (Exception e) { return lead.getOrDefault("default", 30); }
    }

    /** Kalan süreye göre sınıf (sınıf belgesi). */
    static String classify(long leadDays, int target) {
        if (leadDays < 0) return AFTER_EXPIRY;
        if (leadDays < LAST_MINUTE_DAYS) return LAST_MINUTE;
        if (leadDays < target) return LATE;
        return ON_TIME;
    }

    /** Ay içindeki yenilemeler — gruplar ilk görülme sırasıyla, yeni grup ayın içinde ve bitişi uzatmış olmalı. */
    static List<Renewal> detect(Map<String, List<Group>> groups, Instant from, Instant to,
                                java.util.function.Function<String, Integer> targetOf, Set<String> domains) {
        List<Renewal> out = new ArrayList<>();
        for (Map.Entry<String, List<Group>> e : groups.entrySet()) {
            if (domains != null && !domains.contains(e.getKey())) continue;
            List<Group> gs = new ArrayList<>(e.getValue());
            gs.removeIf(g -> AlarmNoiseSection.parse(g.firstSeen()) == null);
            gs.sort(Comparator.comparing((Group g) -> AlarmNoiseSection.parse(g.firstSeen())));
            for (int k = 1; k < gs.size(); k++) {
                Instant seen = AlarmNoiseSection.parse(gs.get(k).firstSeen());
                if (seen.isBefore(from) || !seen.isBefore(to)) continue;
                Instant prevNa = CertificateExpirySection.parseUtc(gs.get(k - 1).notAfter());
                Instant newNa = CertificateExpirySection.parseUtc(gs.get(k).notAfter());
                if (prevNa == null || newNa == null || !newNa.isAfter(prevNa)) continue;   // bitişi uzatmayan değişim
                long lead = Math.floorDiv(prevNa.getEpochSecond() - seen.getEpochSecond(), 86_400L);
                int target = targetOf.apply(e.getKey());
                out.add(new Renewal(e.getKey(), seen, prevNa, lead, classify(lead, target), target));
            }
        }
        return out;
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, Map<String, List<Group>> groups, Map<String, Integer> lead,
                           boolean readOk) {
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).headlineKpi("on_time_pct");
        Map<String, ExecutiveSummaryContext.InventoryRow> inv;
        try { inv = ctx.inventory(); } catch (Exception e) { inv = Map.of(); }
        if (inv == null) inv = Map.of();
        Map<Long, String> names;
        try { names = ctx.teamNames(); } catch (Exception e) { names = Map.of(); }
        if (names == null) names = Map.of();
        List<CertificateDto> latest;
        try { latest = ctx.latestCerts(); } catch (Exception e) { latest = List.of(); }
        if (latest == null) latest = List.of();

        Map<String, CertificateDto> byDomain = new HashMap<>();
        for (CertificateDto d : latest) if (d != null && d.getDomain() != null) byDomain.putIfAbsent(d.getDomain(), d);
        final Map<String, ExecutiveSummaryContext.InventoryRow> invF = inv;
        java.util.function.Function<String, Integer> tierOf = d -> {
            CertificateDto c = byDomain.get(d);
            if (c != null && c.getTier() != null) return c.getTier();
            ExecutiveSummaryContext.InventoryRow r = invF.get(d);
            return r == null ? null : r.tier();
        };
        java.util.function.Function<String, Integer> targetOf = d -> targetDays(ctx, tierOf.apply(d), lead);
        java.util.function.Function<String, Long> teamOf = d -> {
            CertificateDto c = byDomain.get(d);
            if (c != null && c.getTeamId() != null) return c.getTeamId();
            ExecutiveSummaryContext.InventoryRow r = invF.get(d);
            return r == null ? null : r.teamId();
        };

        // Yalnız şu an envanterde olan alan adları (silinmiş kayıtların yenilemesi sayılmaz — Vade Takvimi ile aynı).
        Set<String> domains = new java.util.HashSet<>(inv.keySet());
        domains.addAll(byDomain.keySet());
        List<Renewal> renewals = detect(groups, ctx.from(), ctx.to(), targetOf, domains);
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (String c : List.of(ON_TIME, LATE, LAST_MINUTE, AFTER_EXPIRY)) byClass.put(c, 0);
        for (Renewal r : renewals) byClass.merge(r.cls(), 1, Integer::sum);
        int total = renewals.size();
        int onTime = byClass.get(ON_TIME);
        Double compliance = total == 0 ? null : Math.round(1000.0 * onTime / total) / 10.0;

        // ── Planlar (bu aya planlananlar) ──
        String monthPrefix = ctx.month().toString();               // "2026-09"
        LocalDate today = ctx.now().atZone(ExecutiveSummaryContext.IST).toLocalDate();
        int planned = 0, done = 0, missed = 0, pending = 0;
        for (ExecutiveSummaryContext.InventoryRow r : inv.values()) {
            String p = r.renewalPlannedAt();
            if (p == null || !p.startsWith(monthPrefix)) continue;
            planned++;
            String state = planState(p, byDomain.get(r.domain()), today);
            switch (state) {
                case "done" -> done++;
                case "missed" -> missed++;
                default -> pending++;
            }
        }

        // ── Gecikmede (rapor anı) ──
        List<Map<String, Object>> overdue = new ArrayList<>();
        int overdueExpired = 0;
        for (CertificateDto d : latest) {
            if (d == null || d.getDomain() == null || Boolean.TRUE.equals(d.getPaused())) continue;
            Instant na = CertificateExpirySection.parseUtc(d.getNotAfter());
            if (na == null) continue;
            int days = CertificateExpirySection.daysBetween(ctx.now(), na);
            int target = targetOf.apply(d.getDomain());
            ExecutiveSummaryContext.InventoryRow r = inv.get(d.getDomain());
            String plan = r == null ? null : r.renewalPlannedAt();
            String reason = overdueReason(days, target, plan, d, today);
            if (reason == null) continue;
            if ("EXPIRED".equals(reason)) overdueExpired++;
            Long teamId = teamOf.apply(d.getDomain());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", d.getDomain());
            row.put("team", d.getTeamName() != null ? d.getTeamName() : teamId == null ? null : names.get(teamId));
            row.put("team_id", teamId);
            row.put("tier", tierOf.apply(d.getDomain()));
            row.put("days", days);
            row.put("target_days", target);
            row.put("planned_at", plan);
            row.put("reason", reason);
            row.put("state", "EXPIRED".equals(reason) ? T_BAD : T_WARN);
            overdue.add(row);
        }
        overdue.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("days"))
                .thenComparing(m -> String.valueOf(m.get("domain"))));

        // ── Durum + hükümler ──
        int afterExpiry = byClass.get(AFTER_EXPIRY);
        if (!readOk && total == 0 && overdue.isEmpty()) {
            b.status(NO_DATA).verdict("NO_DATA", T_NEUTRAL, "Yenileme geçmişi okunamadı.");
        } else {
            boolean critical = afterExpiry > 0 || overdueExpired > 0;
            boolean warn = (compliance != null && compliance < COMPLIANCE_WARN_PCT) || !overdue.isEmpty() || missed > 0;
            b.status(critical ? CRITICAL : warn ? ATTENTION : OK);
            if (total == 0) {
                b.verdict("NO_RENEWALS", T_NEUTRAL, "Bu ay tespit edilen sertifika yenilemesi yok.");
            } else {
                b.verdict("COMPLIANCE", compliance >= COMPLIANCE_WARN_PCT ? T_OK : T_WARN,
                        "Ay içindeki " + total + " yenilemenin " + onTime + " tanesi (%" + ExecFormat.num(compliance, 1)
                                + ") hedef süreden önce yapıldı.", total, onTime, pct(compliance));
            }
            if (afterExpiry > 0) {
                b.verdict("AFTER_EXPIRY", T_BAD, afterExpiry + " sertifika süresi dolduktan sonra yenilendi.", afterExpiry);
            }
            if (!overdue.isEmpty()) {
                b.verdict("OVERDUE", overdueExpired > 0 ? T_BAD : T_WARN, overdue.size()
                        + " sertifika yenileme penceresinde ve planı yok ya da planı geçmiş (" + overdueExpired
                        + " tanesinin süresi dolmuş).", overdue.size(), overdueExpired);
            }
            if (missed > 0) {
                b.verdict("PLANS_MISSED", T_WARN, "Bu aya planlanan " + planned + " yenilemenin " + missed
                        + " tanesi tarihinde yapılmadı.", planned, missed);
            }
        }

        boolean fixedTarget = ctx.renewalTargetDays() > 0;
        b.kpi(new Kpi("on_time_pct", "Zamanında yenileme", compliance, "pct",
                compliance == null ? T_NEUTRAL : compliance >= COMPLIANCE_WARN_PCT ? T_OK : T_WARN,
                fixedTarget ? "Hedef: bitişten en az " + ctx.renewalTargetDays() + " gün önce"
                        : "Hedef: seviye bazlı yenileme süresi", fixedTarget ? List.of(ctx.renewalTargetDays()) : List.of(),
                null, null, null));
        b.kpi(new Kpi("renewals", "Yenilenen sertifika", total, "int", T_NEUTRAL, "bu ay tespit edilen", List.of(),
                null, null, null));
        b.kpi(new Kpi("late", "Geç / son dakika", byClass.get(LATE) + byClass.get(LAST_MINUTE), "int",
                byClass.get(LATE) + byClass.get(LAST_MINUTE) > 0 ? T_WARN : T_OK,
                byClass.get(LAST_MINUTE) + " tanesi son " + LAST_MINUTE_DAYS + " günde",
                List.of(byClass.get(LAST_MINUTE), LAST_MINUTE_DAYS), null, null, null));
        b.kpi(new Kpi("after_expiry", "Süresi dolduktan sonra", afterExpiry, "int", afterExpiry > 0 ? T_BAD : T_OK,
                "kesinti riski yaşandı", List.of(), null, null, null));
        b.kpi(new Kpi("plans_done", "Plan gerçekleşme", done, "int", missed > 0 ? T_WARN : T_NEUTRAL,
                planned + " plandan · " + missed + " kaçırıldı", List.of(planned, missed), null, null, null));
        b.kpi(new Kpi("overdue", "Gecikmede (şimdi)", overdue.size(), "int",
                overdueExpired > 0 ? T_BAD : overdue.isEmpty() ? T_OK : T_WARN,
                overdueExpired + " tanesinin süresi dolmuş", List.of(overdueExpired), null, null, null));

        // ── Ayın yenilemeleri (sorunlular önce) ──
        List<Renewal> sorted = new ArrayList<>(renewals);
        Map<String, Integer> sev = Map.of(AFTER_EXPIRY, 0, LAST_MINUTE, 1, LATE, 2, ON_TIME, 3);
        sorted.sort(Comparator.comparingInt((Renewal r) -> sev.getOrDefault(r.cls(), 9))
                .thenComparingLong(Renewal::leadDays).thenComparing(Renewal::domain));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Renewal r : sorted.subList(0, Math.min(LIST_LIMIT, sorted.size()))) {
            Long teamId = teamOf.apply(r.domain());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", r.domain());
            m.put("team", teamId == null ? null : names.get(teamId));
            m.put("team_id", teamId);
            m.put("renewed_at", r.renewedAt().atZone(ExecutiveSummaryContext.IST).toLocalDate().toString());
            m.put("prev_expiry", r.prevNotAfter().atZone(ExecutiveSummaryContext.IST).toLocalDate().toString());
            m.put("lead_days", r.leadDays());
            m.put("target", r.target());
            m.put("class", r.cls());
            m.put("state", ON_TIME.equals(r.cls()) ? T_OK : AFTER_EXPIRY.equals(r.cls()) ? T_BAD : T_WARN);
            rows.add(m);
        }
        b.table(new Table("renewals", "Ay içindeki yenilemeler", List.of(
                new Column("domain", "Sertifika", "text"),
                new Column("team", "Takım", "team"),
                new Column("renewed_at", "Yenilendi", "date"),
                new Column("prev_expiry", "Eski bitiş", "date"),
                new Column("lead_days", "Kalan süre", "days"),
                new Column("class", "Sınıf", "renewal_class"),
                new Column("state", "Durum", "status")),
                rows, renewals.size(), "Bu ay tespit edilen yenileme yok."));
        b.table(new Table("overdue", "Gecikmedeki sertifikalar (rapor anı)", List.of(
                new Column("domain", "Sertifika", "text"),
                new Column("team", "Takım", "team"),
                new Column("tier", "Seviye", "tier"),
                new Column("days", "Kalan", "days"),
                new Column("target_days", "Hedef (gün)", "int"),
                new Column("planned_at", "Plan", "date"),
                new Column("reason", "Neden", "overdue_reason"),
                new Column("state", "Durum", "status")),
                overdue.subList(0, Math.min(LIST_LIMIT, overdue.size())), overdue.size(),
                "Yenileme penceresinde planı olmayan sertifika yok."));
        b.note("METHOD", "Yenileme = yeni sertifikanın izlemede ilk görüldüğü an; zamanında = eski sertifikanın bitişine "
                + "en az hedef süre (gün) varken. Son dakika: son " + LAST_MINUTE_DAYS + " gün.", LAST_MINUTE_DAYS);
        b.note("PLANS", "Plan alanı yalnız güncel değeri tutar; plan ve gecikme sayıları rapor anına göredir ("
                + ExecFormat.stamp(ctx.now()) + ").", datetime(ctx.nowIso()));
        b.data("by_class", byClass).data("total", total).data("compliance", compliance)
                .data("plans", Map.of("planned", planned, "done", done, "missed", missed, "pending", pending))
                .data("target_days", fixedTarget ? ctx.renewalTargetDays() : null)
                .data("last_minute_days", LAST_MINUTE_DAYS);
        return b.build();
    }

    /** Vade Takvimi'nin plan kuralı: sunulan sertifikanın düzenlenme günü ≥ plan günü → yapıldı. */
    static String planState(String planned, CertificateDto current, LocalDate today) {
        String nb = current == null ? null : istDay(current.getNotBefore());
        if (nb != null && nb.compareTo(planned) >= 0) return "done";
        return planned.compareTo(today.toString()) < 0 ? "missed" : "pending";
    }

    /**
     * Gecikme nedeni: süresi dolmuş → {@code EXPIRED}; kalan &lt; hedef ve plan yok → {@code NO_PLAN}; kalan &lt; hedef ve
     * plan geçmiş/yapılmamış → {@code PLAN_PASSED}; aksi null (gecikmede değil).
     */
    static String overdueReason(int days, int target, String plan, CertificateDto current, LocalDate today) {
        if (days < 0) return "EXPIRED";
        if (days >= target) return null;
        if (plan == null || plan.isBlank()) return "NO_PLAN";
        return "missed".equals(planState(plan, current, today)) ? "PLAN_PASSED" : null;
    }

    private static String istDay(String utc) {
        Instant t = CertificateExpirySection.parseUtc(utc);
        return t == null ? null : t.atZone(ExecutiveSummaryContext.IST).toLocalDate().toString();
    }
}
