package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.AlertNoiseService;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.MonitorTypeCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (b) EN GÜRÜLTÜLÜ ALARMLAR — ay içinde AÇILAN alarmlar (İstanbul ay sınırları), Alarm Gürültü Analizi'nin kurallarıyla.
 *
 * <h2>Tanımlar</h2>
 * <ul>
 *   <li><b>Hedef</b> = alan adı × alarm tipi (Gürültü Analizi'nin anahtarı). <b>Takım</b> = alarmın damgalı takımı, yoksa
 *       envanterin SY takımı (aynı kural).</li>
 *   <li><b>Dalgalanma (flapping)</b> = hedefin ayda ≥ {@link AlertNoiseService#FLAP_MIN_ALERTS} alarmı VE gerçek
 *       kurtarmalarının ortalama süresi ≤ {@link AlertNoiseService#FLAP_MAX_AVG_MINUTES} dk (Gürültü Analizi sabitleri).</li>
 *   <li><b>MTTA</b> = sahiplenilen alarmlarda (sahiplenme anı − açılış) ortalaması. <b>MTTR</b> = GERÇEKTEN kurtarılarak
 *       kapanan alarmlarda (kapanış − açılış) ortalaması; sessiz kapanışlar (silme/duraklatma) MTTR'a girmez.</li>
 *   <li><b>Sahiplenilmeyen oran</b> = ay içinde açılıp hiç sahiplenilmemiş alarm / toplam (kendiliğinden kapananlar dahil
 *       — "kimse bakmadı" göstergesi).</li>
 * </ul>
 * <b>Sorgu bütçesi:</b> ayın alarmları için TEK dar izdüşüm + önceki ay için TEK sayım; envanter ve takım adları paylaşılan
 * bağlamdan.
 *
 * <h2>Takım kapsamı (2026-10-10)</h2>
 * Alarm takımın kapsamındadır ⇔ damgalı takımı ({@code alert_events.team_id}) kapsam takımıdır YA DA alarm envanter gibi
 * yönlenir ({@link AlertOwnership#routesLikeInventory} — bildirim yönlendirmesiyle AYNI yüklem: tür listesi + bağlamdaki
 * bağımsız izleme işareti) ve alan adı kapsamdaki envanterdedir (SY ya da UG). Böylece aynı host'taki başka bir takımın
 * bağımsız Port/HTTP izlemesinin alarmı sayılmaz. Damgasız ve envanter dışı alarm hiçbir takımın kapsamında değildir.
 * Ayın ve önceki ayın satırları koşu boyu paylaşılır ({@link ExecutiveSummaryContext#shared}); takım süzmesi bellekte —
 * önceki ay sayısı da süzülür (kurum kapsamı bugünkü TEK sayımı kullanır). Takımda "takımlar" tablosu yerine türe göre
 * kırılım gelir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmNoiseSection implements ExecutiveSummarySection {

    public static final String KEY = "noise";
    static final int ORDER = 20;
    static final String TITLE = "En gürültülü alarmlar";
    static final int TOP = 10;
    /** Sahiplenilmeyen oran bu yüzdeyi aşarsa bölüm "takip gerekli". */
    static final int UNACKED_WARN_PCT = 50;
    /** Önceki aya göre artış bu yüzdeyi aşarsa bölüm "takip gerekli". */
    static final int INCREASE_WARN_PCT = 25;
    /** Koşu boyu paylaşılan ay satırlarının anahtar öneki ({@link ExecutiveSummaryContext#shared}). */
    static final String SHARED_ROWS = "noise.rows|";

    private final AlertEventRepository alertRepo;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /** Alarm satırı (repo izdüşümü sırası). {@code context}: alarm-anı bağlam JSON'u (yalnız takım kapsamı okur). */
    record Ev(String domain, String type, String level, String createdAt, String resolvedAt, boolean resolved,
              boolean silent, boolean acked, String ackedAt, Long teamId, String context) {
        static Ev of(Object[] r) {
            return new Ev(s(r[0]), s(r[1]), s(r[2]), s(r[3]), s(r[4]), Boolean.TRUE.equals(r[5]), Boolean.TRUE.equals(r[6]),
                    Boolean.TRUE.equals(r[7]), s(r[8]), r[9] instanceof Number n ? Long.valueOf(n.longValue()) : null,
                    r.length > 10 ? s(r[10]) : null);
        }
        private static String s(Object v) { return v == null ? null : String.valueOf(v); }
    }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        List<Ev> events = rows(ctx, ctx.fromIso(), ctx.toIso());
        Long prev = null;
        try {
            if (ctx.teamScoped()) {
                long n = 0;
                for (Ev e : rows(ctx, ctx.prevFromIso(), ctx.fromIso())) if (inScope(ctx, e)) n++;
                prev = n;
            } else {
                prev = alertRepo.countCreatedBetween(ctx.prevFromIso(), ctx.fromIso());
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: önceki ay alarm sayısı okunamadı: {}", e.toString());
        }
        return evaluate(ctx, events, prev);
    }

    /** {@code [from, to)} içinde açılan alarmlar — kurum geneli, koşu boyu paylaşılır (takım süzmesi çağıranda). */
    private List<Ev> rows(ExecutiveSummaryContext ctx, String from, String to) {
        return ctx.shared(SHARED_ROWS + from + "|" + to, () -> {
            List<Ev> out = new ArrayList<>();
            List<Object[]> raw = alertRepo.findExecutiveRows(from, to);
            if (raw != null) {
                for (Object[] r : raw) if (r != null && r.length >= 10) out.add(Ev.of(r));
            }
            return List.copyOf(out);
        });
    }

    /**
     * Alarm kapsamda mı: kurum geneli → hepsi; takım → damga kapsam takımı YA DA envanter gibi yönlenen alarm ve alan adı
     * kapsamdaki envanterde (SY ya da UG). Bağlam yalnız gerektiğinde ayrıştırılır.
     */
    static boolean inScope(ExecutiveSummaryContext ctx, Ev e) {
        if (!ctx.teamScoped()) return true;
        if (e == null) return false;
        if (e.teamId() != null && ctx.teamInScope(e.teamId())) return true;
        if (e.domain() == null || !ctx.domainInScope(e.domain())) return false;
        AlertEvent probe = new AlertEvent();
        probe.setAlertType(e.type());
        probe.setContextJson(e.context());
        return AlertOwnership.routesLikeInventory(probe);
    }

    private static final class TargetAcc {
        String domain, type;
        Long teamId;
        int count, unacked, resolved, critical;
        double minutesSum;
        final List<Double> durations = new ArrayList<>();
    }

    private static final class TeamAcc {
        Long teamId;
        int alerts, critical, unacked, acked, resolved, flapTargets;
        double ackMinutes, resolveMinutes;
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, List<Ev> events, Long prevCount) {
        boolean teamScope = ctx.teamScoped();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).headlineKpi("total_alarms");
        Map<String, Long> domainTeam = new java.util.HashMap<>();
        try {
            for (ExecutiveSummaryContext.InventoryRow inv : ctx.inventory().values()) {
                if (inv.domain() != null && inv.teamId() != null) domainTeam.put(inv.domain(), inv.teamId());
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti: envanter okunamadı: {}", e.toString());
        }
        Map<Long, String> names = names(ctx);

        Map<String, TargetAcc> targets = new LinkedHashMap<>();
        Map<Long, TeamAcc> teams = new LinkedHashMap<>();
        // Takım kapsamı: izleme türüne göre kırılım ("takımlar" tablosunun yerine)
        Map<String, TeamAcc> byType = new LinkedHashMap<>();
        int total = 0, critical = 0, unacked = 0, silent = 0, stillOpenUnacked = 0, acked = 0, resolvedCount = 0;
        double ackSum = 0, resolveSum = 0;
        for (Ev e : events) {
            if (!inScope(ctx, e)) continue;                    // kurum kapsamında her satır kapsamda
            total++;
            boolean crit = "CRITICAL".equalsIgnoreCase(e.level());
            if (crit) critical++;
            Long team = e.teamId() != null ? e.teamId() : (e.domain() == null ? null : domainTeam.get(e.domain()));
            TeamAcc ta = teams.computeIfAbsent(team, k -> { TeamAcc a = new TeamAcc(); a.teamId = k; return a; });
            TeamAcc ty = teamScope ? byType.computeIfAbsent(String.valueOf(MonitorTypeCatalog.typeOfAlert(e.type())),
                    k -> new TeamAcc()) : null;
            if (ty != null) {
                ty.alerts++;
                if (crit) ty.critical++;
            }
            ta.alerts++;
            if (crit) ta.critical++;
            String key = (e.domain() == null ? "?" : e.domain()) + "|" + e.type();
            TargetAcc tg = targets.computeIfAbsent(key, k -> new TargetAcc());
            tg.domain = e.domain();
            tg.type = e.type();
            if (tg.teamId == null) tg.teamId = team;
            tg.count++;
            if (crit) tg.critical++;
            Instant created = parse(e.createdAt());
            if (!e.acked()) {
                unacked++; ta.unacked++; tg.unacked++;
                if (ty != null) ty.unacked++;
                if (!e.resolved()) stillOpenUnacked++;
            } else {
                Instant at = parse(e.ackedAt());
                if (created != null && at != null && !at.isBefore(created)) {
                    double m = (at.toEpochMilli() - created.toEpochMilli()) / 60000.0;
                    acked++; ackSum += m; ta.acked++; ta.ackMinutes += m;
                    if (ty != null) { ty.acked++; ty.ackMinutes += m; }
                }
            }
            if (e.resolved() && e.silent()) silent++;
            if (e.resolved() && !e.silent()) {
                Instant r = parse(e.resolvedAt());
                if (created != null && r != null && r.isAfter(created)) {
                    double m = (r.toEpochMilli() - created.toEpochMilli()) / 60000.0;
                    resolvedCount++; resolveSum += m; ta.resolved++; ta.resolveMinutes += m;
                    tg.resolved++; tg.minutesSum += m; tg.durations.add(m);
                    if (ty != null) { ty.resolved++; ty.resolveMinutes += m; }
                }
            }
        }

        // ── Hedef satırları + dalgalanma ──
        List<TargetAcc> targetList = new ArrayList<>(targets.values());
        int flapTargets = 0, flapAlerts = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        targetList.sort(Comparator.comparingInt((TargetAcc t) -> -t.count)
                .thenComparing(t -> t.domain == null ? "" : t.domain).thenComparing(t -> t.type == null ? "" : t.type));
        for (TargetAcc t : targetList) {
            Double avg = t.resolved == 0 ? null : t.minutesSum / t.resolved;
            boolean flap = isFlapping(t.count, avg);
            if (flap) {
                flapTargets++; flapAlerts += t.count;
                TeamAcc ta = teams.get(t.teamId);
                if (ta != null) ta.flapTargets++;
            }
            if (rows.size() < TOP) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("target", t.domain);
                r.put("alert_type", t.type);
                r.put("monitor_type", MonitorTypeCatalog.typeOfAlert(t.type));
                r.put("team", t.teamId == null ? null : names.get(t.teamId));
                r.put("team_id", t.teamId);
                r.put("count", t.count);
                r.put("share", total == 0 ? 0.0 : round1(100.0 * t.count / total));
                r.put("median_minutes", median(t.durations));
                r.put("unacked", t.unacked);
                r.put("flapping", flap ? T_WARN : T_NEUTRAL);
                rows.add(r);
            }
        }

        // ── Takım satırları ──
        List<TeamAcc> teamList = new ArrayList<>(teams.values());
        teamList.sort(Comparator.comparingInt((TeamAcc t) -> -t.alerts)
                .thenComparing(t -> t.teamId == null ? Long.MAX_VALUE : t.teamId));
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (TeamAcc t : teamList.subList(0, Math.min(TOP, teamList.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team", t.teamId == null ? null : names.get(t.teamId));
            r.put("team_id", t.teamId);
            r.put("alerts", t.alerts);
            r.put("critical", t.critical);
            r.put("unacked_pct", t.alerts == 0 ? 0.0 : round1(100.0 * t.unacked / t.alerts));
            r.put("mtta", t.acked == 0 ? null : round1(t.ackMinutes / t.acked));
            r.put("mttr", t.resolved == 0 ? null : round1(t.resolveMinutes / t.resolved));
            r.put("flapping", t.flapTargets);
            teamRows.add(r);
        }

        Double mtta = acked == 0 ? null : round1(ackSum / acked);
        Double mttr = resolvedCount == 0 ? null : round1(resolveSum / resolvedCount);
        Double unackedPct = total == 0 ? null : round1(100.0 * unacked / total);
        Double changePct = prevCount == null || prevCount == 0 ? null : round1(100.0 * (total - prevCount) / prevCount);
        double perDay = round1((double) total / ctx.elapsedDays());

        // ── Durum + hükümler ──
        if (total == 0) {
            b.status(OK).verdict("NONE", T_OK, "Bu ay hiç alarm açılmadı.");
        } else {
            boolean warn = flapTargets > 0 || (unackedPct != null && unackedPct >= UNACKED_WARN_PCT)
                    || (changePct != null && changePct >= INCREASE_WARN_PCT);
            b.status(warn ? ATTENTION : OK);
            if (changePct == null) {
                b.verdict("TOTAL", T_NEUTRAL, "Bu ay " + total + " alarm açıldı (günde ortalama "
                        + ExecFormat.num(perDay, 1) + ").", total, num(perDay));
            } else {
                b.verdict(changePct > 0 ? "TOTAL_UP" : changePct < 0 ? "TOTAL_DOWN" : "TOTAL_SAME",
                        changePct >= INCREASE_WARN_PCT ? T_WARN : changePct <= 0 ? T_OK : T_NEUTRAL,
                        "Bu ay " + total + " alarm açıldı; geçen aya (" + prevCount + ") göre "
                                + (changePct == 0 ? "değişim yok." : "%" + ExecFormat.num(Math.abs(changePct), 1)
                                + (changePct > 0 ? " artış." : " azalış.")),
                        total, prevCount, num(Math.abs(changePct)));
            }
            TargetAcc top = targetList.get(0);
            b.verdict("TOP_TARGET", T_NEUTRAL, "En gürültülü hedef " + nz(top.domain) + " ("
                            + MonitorTypeCatalog.label(MonitorTypeCatalog.typeOfAlert(top.type)) + "): " + top.count
                            + " alarm, toplamın %" + ExecFormat.num(100.0 * top.count / total, 1) + "'i.",
                    nz(top.domain), monitorType(MonitorTypeCatalog.typeOfAlert(top.type)), top.count,
                    pct(round1(100.0 * top.count / total)));
            if (flapTargets > 0) {
                b.verdict("FLAPPING", T_WARN, flapTargets + " hedef dalgalanıyor (sık açılıp kısa sürede kapanan "
                        + flapAlerts + " alarm) — eşik ya da onay sayısı gözden geçirilmeli.", flapTargets, flapAlerts);
            }
            if (unackedPct != null && unackedPct >= UNACKED_WARN_PCT) {
                b.verdict("UNACKED", T_WARN, "Alarmların %" + ExecFormat.num(unackedPct, 1)
                        + "'i hiç sahiplenilmedi.", pct(unackedPct));
            }
        }

        b.kpi(new Kpi("total_alarms", "Açılan alarm", total, "int",
                changePct != null && changePct >= INCREASE_WARN_PCT ? T_WARN : T_NEUTRAL,
                "Günde ortalama " + ExecFormat.num(perDay, 1), List.of(num(perDay)),
                changePct, changePct == null ? null : "pct_change",
                changePct == null ? null : changePct > 0 ? T_BAD : T_OK));
        b.kpi(new Kpi("critical", "Kritik", critical, "int", critical > 0 ? T_BAD : T_OK,
                total == 0 ? "—" : "toplamın %" + ExecFormat.num(100.0 * critical / total, 1) + "'i",
                List.of(pct(total == 0 ? 0.0 : round1(100.0 * critical / total))), null, null, null));
        b.kpi(new Kpi("mtta", "Ortalama sahiplenme süresi (MTTA)", mtta, "minutes", T_NEUTRAL,
                acked + " sahiplenilen alarm", List.of(acked), null, null, null));
        b.kpi(new Kpi("mttr", "Ortalama çözüm süresi (MTTR)", mttr, "minutes", T_NEUTRAL,
                resolvedCount + " kurtarılan alarm", List.of(resolvedCount), null, null, null));
        b.kpi(new Kpi("unacked_pct", "Sahiplenilmeyen oran", unackedPct, "pct",
                unackedPct != null && unackedPct >= UNACKED_WARN_PCT ? T_WARN : T_NEUTRAL,
                stillOpenUnacked + " tanesi hâlâ açık", List.of(stillOpenUnacked), null, null, null));
        b.kpi(new Kpi("flapping", "Dalgalanan hedef", flapTargets, "int", flapTargets > 0 ? T_WARN : T_OK,
                flapAlerts + " alarm", List.of(flapAlerts), null, null, null));

        b.table(new Table("top_targets", "En çok alarm üreten hedefler", List.of(
                new Column("target", "Hedef", "text"),
                new Column("monitor_type", "Tür", "monitor_type"),
                new Column("team", "Takım", "team"),
                new Column("count", "Alarm", "int"),
                new Column("share", "Pay", "pct"),
                new Column("median_minutes", "Ortanca süre", "minutes"),
                new Column("unacked", "Sahiplenilmeyen", "int"),
                new Column("flapping", "Dalgalanma", "status")),
                rows, targetList.size(), "Bu ay alarm açılmadı."));
        if (teamScope) {
            b.table(byTypeTable(byType));
        } else {
            b.table(new Table("top_teams", "En çok alarm alan takımlar", List.of(
                    new Column("team", "Takım", "team"),
                    new Column("alerts", "Alarm", "int"),
                    new Column("critical", "Kritik", "int"),
                    new Column("unacked_pct", "Sahiplenilmeyen", "pct"),
                    new Column("mtta", "MTTA", "minutes"),
                    new Column("mttr", "MTTR", "minutes"),
                    new Column("flapping", "Dalgalanan hedef", "int")),
                    teamRows, teamList.size(), "Bu ay alarm açılmadı."));
        }
        b.note("METHOD", "Ay içinde AÇILAN alarmlar sayılır (Türkiye saatiyle). Dalgalanma: ayda en az "
                        + AlertNoiseService.FLAP_MIN_ALERTS + " alarm ve ortalama kurtarma en çok "
                        + AlertNoiseService.FLAP_MAX_AVG_MINUTES + " dk. Sessiz kapanışlar (silme / duraklatma) MTTR'a girmez.",
                AlertNoiseService.FLAP_MIN_ALERTS, AlertNoiseService.FLAP_MAX_AVG_MINUTES);
        if (silent > 0) b.note("SILENT", silent + " alarm sessiz kapandı (kurtarma sayılmadı).", silent);
        if (teamScope) {
            b.note("TEAM_SCOPE", "Takımın alarmları: takıma damgalı alarmlar ile takımın sorumlu (SY) ya da uygulama "
                    + "geliştirici (UG) olduğu envanter kayıtlarının sertifika, erişim ve envanterden türeyen Port/DNS "
                    + "alarmları. Aynı host'taki başka takımların bağımsız izlemeleri sayılmaz.");
        }
        b.data("total", total).data("prev_total", prevCount).data("change_pct", changePct)
                .data("silent", silent).data("per_day", perDay);
        return b.build();
    }

    /** Takım kapsamı: izleme türüne göre alarmlar (en çok önce; türü bilinmeyen alarm tipi "null" anahtarında). */
    private static Table byTypeTable(Map<String, TeamAcc> byType) {
        List<Map.Entry<String, TeamAcc>> list = new ArrayList<>(byType.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<String, TeamAcc> en) -> -en.getValue().alerts)
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, TeamAcc> en : list.subList(0, Math.min(TOP, list.size()))) {
            TeamAcc t = en.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("monitor_type", "null".equals(en.getKey()) ? null : en.getKey());
            r.put("alerts", t.alerts);
            r.put("critical", t.critical);
            r.put("unacked_pct", t.alerts == 0 ? 0.0 : round1(100.0 * t.unacked / t.alerts));
            r.put("mtta", t.acked == 0 ? null : round1(t.ackMinutes / t.acked));
            r.put("mttr", t.resolved == 0 ? null : round1(t.resolveMinutes / t.resolved));
            rows.add(r);
        }
        return new Table("by_type", "Türe göre alarmlar", List.of(
                new Column("monitor_type", "Tür", "monitor_type"),
                new Column("alerts", "Alarm", "int"),
                new Column("critical", "Kritik", "int"),
                new Column("unacked_pct", "Sahiplenilmeyen", "pct"),
                new Column("mtta", "MTTA", "minutes"),
                new Column("mttr", "MTTR", "minutes")),
                rows, list.size(), "Bu ay alarm açılmadı.");
    }

    /** Gürültü Analizi'nin flap kuralı (tek kaynak: {@link AlertNoiseService} sabitleri). */
    static boolean isFlapping(int count, Double avgMinutes) {
        return count >= AlertNoiseService.FLAP_MIN_ALERTS && avgMinutes != null
                && avgMinutes <= AlertNoiseService.FLAP_MAX_AVG_MINUTES;
    }

    static Double median(List<Double> v) {
        if (v == null || v.isEmpty()) return null;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        return round1(m);
    }

    private static Map<Long, String> names(ExecutiveSummaryContext ctx) {
        try {
            Map<Long, String> m = ctx.teamNames();
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            if (s.endsWith("Z") || s.endsWith("z")) return Instant.parse(s.toUpperCase(Locale.ROOT));
            if (s.matches(".*[+-]\\d\\d:?\\d\\d$")) return OffsetDateTime.parse(s).toInstant();
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }

    static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
