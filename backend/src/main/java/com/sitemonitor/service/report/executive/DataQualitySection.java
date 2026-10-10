package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.quality.DataQualityScore;
import com.sitemonitor.service.quality.DataQualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (g) TAKIM VERİ KALİTESİ PUANI — kurum puanı + bandı, aydan aya değişim, en düşük puanlı takımlar ve en çok puan
 * kaybettiren kurallar.
 *
 * <h2>Kaynak</h2>
 * <ul>
 *   <li><b>Puanlar</b> — RAPOR ANI fotoğrafı: {@link DataQualityService#digest()} (kurum geneli, SÜZGEÇSİZ; Veri Kalitesi
 *       ekranının kendi hesabı ve belleği — puan burada yeniden hesaplanmaz).</li>
 *   <li><b>Aydan aya değişim</b> — AYA BAĞLI: {@code data_quality_daily} günlük görüntülerinden ayın SON görüntüsü ile
 *       önceki ayın son görüntüsü ({@link DataQualityService#monthEnds}: İstanbul takvim günleri, iki sorgu). Görüntüler
 *       {@value DataQualityService#TREND_KEEP_DAYS} gün saklanır — daha eski aylarda ya da görüntü yokken değişim
 *       gösterilmez (not düşülür). Takım satırındaki değişim aynı iki günün takım puanlarıdır.</li>
 * </ul>
 * <b>Sorgu bütçesi:</b> özet (servisin sabit sayıda sorgusu, bellekli) + 2 görüntü sorgusu.
 *
 * <h2>Durum (kurum bandına göre)</h2>
 * Mükemmel / İyi → {@code OK}; İyileştirilmeli → {@code ATTENTION}; Zayıf → {@code CRITICAL}; puan yok → {@code NO_DATA}.
 * Ayrıca ay içinde kurum puanı ≥ {@value #DROP_WARN_POINTS} puan düştüyse {@code OK} → {@code ATTENTION}.
 *
 * <h2>Takım kapsamı (2026-10-10)</h2>
 * {@link DataQualityService#teamDigest(long)} (aynı bellekli değerlendirme, ek sorgu yok): TAKIMIN puanı / bandı / bulguları
 * ve TAKIM kovasında en çok puan kaybettiren kurallar; aydan aya değişim {@code data_quality_daily}'nin
 * {@code team_key = takım} satırlarından (ay sonu görüntüleri koşu boyu paylaşılır). Durum takım bandına göre, aynı kural.
 * "Kurum puanı", takım sıralaması ve "Zayıf takımlar" hükmü gösterilmez. Not: Veri Kalitesi kovaları envanteri SY
 * takımına göre toplar (UG kayıtları o takımın puanına girmez) ve sahiplik kuralları takım puanına hiç girmez.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataQualitySection implements ExecutiveSummarySection {

    public static final String KEY = "data-quality";
    static final int ORDER = 70;
    static final String TITLE = "Takım veri kalitesi puanı";
    static final int TEAM_LIMIT = 10;
    static final int RULE_LIMIT = 8;
    /** Ay içinde kurum puanı bu kadar (ya da daha çok) düştüyse "takip gerekli". */
    static final int DROP_WARN_POINTS = 5;

    private final DataQualityService quality;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        DataQualityService.MonthEnds me;
        try {
            // İki günün BÜTÜN kova puanları (kurum + takımlar) — koşu boyu paylaşılır, takım kendi satırını okur
            me = ctx.shared("data-quality.month_ends|" + ctx.month(), () -> quality.monthEnds(ctx.month().atDay(1)));
        } catch (Exception e) {
            log.debug("Yönetici özeti: veri kalitesi görüntüleri okunamadı: {}", e.toString());
            me = DataQualityService.MonthEnds.NONE;
        }
        if (me == null) me = DataQualityService.MonthEnds.NONE;
        if (ctx.teamScoped()) return evaluateTeam(ctx, quality.teamDigest(ctx.scopeTeamId()), me);
        DataQualityService.Digest d = quality.digest();
        return evaluate(ctx, d, me);
    }

    /** Bant → bölüm durumu. */
    static String statusOf(String band) {
        if (band == null) return NO_DATA;
        return switch (band) {
            case "EXCELLENT", "GOOD" -> OK;
            case "NEEDS_ATTENTION" -> ATTENTION;
            case "POOR" -> CRITICAL;
            default -> NO_DATA;
        };
    }

    /** Bant → ton (gösterge / tablo). */
    static String toneOf(String band) {
        if (band == null) return T_NEUTRAL;
        return switch (band) {
            case "EXCELLENT", "GOOD" -> T_OK;
            case "NEEDS_ATTENTION" -> T_WARN;
            case "POOR" -> T_BAD;
            default -> T_NEUTRAL;
        };
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, DataQualityService.Digest d, DataQualityService.MonthEnds me) {
        String asOf = d == null || d.generatedAt() == null ? ctx.nowIso() : ExecutiveSummaryContext.UTC_ISO.format(d.generatedAt());
        Instant asOfAt = d == null || d.generatedAt() == null ? ctx.now() : d.generatedAt();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).snapshot(asOf).headlineKpi("org_score");
        Integer org = d == null ? null : d.orgScore();
        String band = org == null ? DataQualityScore.Band.NO_DATA.name() : d.orgBand();
        int findings = d == null ? 0 : d.orgFindings();
        DataQualityService.MonthEnds ends = me == null ? DataQualityService.MonthEnds.NONE : me;

        Integer prevOrg = ends.prev().get(DataQualityService.ORG_KEY);
        Integer curOrg = ends.cur().get(DataQualityService.ORG_KEY);
        Integer change = prevOrg != null && curOrg != null ? Integer.valueOf(curOrg - prevOrg) : null;

        List<DataQualityService.TeamScore> scored = new ArrayList<>();
        if (d != null) for (DataQualityService.TeamScore t : d.teams()) if (t.score() != null) scored.add(t);
        int poor = 0;
        for (DataQualityService.TeamScore t : scored) if ("POOR".equals(t.band())) poor++;
        List<DataQualityService.IssueCount> costliest = d == null || d.costliest() == null ? List.of() : d.costliest();

        // ── Durum + hükümler ──
        if (org == null) {
            b.status(NO_DATA).verdict("NO_DATA", T_NEUTRAL, "Veri kalitesi puanı hesaplanamadı: puanlanacak kayıt ya da "
                    + "kural yok.");
        } else {
            String st = statusOf(band);
            if (OK.equals(st) && change != null && change <= -DROP_WARN_POINTS) st = ATTENTION;
            b.status(st);
            b.verdict("SCORE", toneOf(band), "Kurum veri kalitesi puanı " + org + " (" + ExecFormat.codeLabel("dq_band", band)
                    + "); " + findings + " açık bulgu.", org, new Param(band, "dq_band"), findings);
            if (change != null) {
                if (change > 0) {
                    b.verdict("MONTH_UP", T_OK, "Ay sonu görüntüsüne göre puan geçen aya kıyasla " + change + " puan arttı ("
                            + prevOrg + " → " + curOrg + ").", prevOrg, curOrg, change);
                } else if (change < 0) {
                    b.verdict("MONTH_DOWN", change <= -DROP_WARN_POINTS ? T_WARN : T_NEUTRAL, "Ay sonu görüntüsüne göre puan "
                            + "geçen aya kıyasla " + (-change) + " puan düştü (" + prevOrg + " → " + curOrg + ").",
                            prevOrg, curOrg, -change);
                } else {
                    b.verdict("MONTH_SAME", T_NEUTRAL, "Ay sonu görüntüsüne göre puan geçen ayla aynı (" + prevOrg + " → "
                            + curOrg + ").", prevOrg, curOrg);
                }
            }
            if (poor > 0) {
                DataQualityService.TeamScore worst = scored.get(0);
                b.verdict("POOR_TEAMS", T_WARN, poor + " takım Zayıf bantta (" + DataQualityScore.FAIR_MIN
                        + " puanın altında); en düşük: " + nz(worst.teamName()) + " (" + worst.score() + ").",
                        poor, DataQualityScore.FAIR_MIN, nz(worst.teamName()), worst.score());
            }
            if (!costliest.isEmpty()) {
                DataQualityService.IssueCount c0 = costliest.get(0);
                b.verdict("TOP_RULE", T_NEUTRAL, "En çok puan kaybettiren kural: " + ExecFormat.codeLabel("dq_rule", c0.code())
                        + " — düzeltilirse kurum puanı " + ExecFormat.num(c0.pointsLost(), 1) + " puan artar.",
                        new Param(c0.code(), "dq_rule"), num(c0.pointsLost()));
            }
        }

        // ── Göstergeler ──
        b.kpi(new Kpi("org_score", "Kurum puanı", org, "int", toneOf(band),
                ExecFormat.codeLabel("dq_band", band) + " · " + findings + " açık bulgu",
                List.of(new Param(band, "dq_band"), findings), null, null, null));
        // Ay sonu puanı + önceki aya göre fark: değer de fark da AYA BAĞLI (günlük görüntüler) — canlı puanla karışmaz.
        String deltaTone = change == null ? null
                : change > 0 ? T_OK : change == 0 ? T_NEUTRAL : change <= -DROP_WARN_POINTS ? T_BAD : T_WARN;
        b.kpi(new Kpi("month_end", "Ay sonu puanı", curOrg, "int",
                curOrg == null ? T_NEUTRAL : toneOf(DataQualityScore.band(curOrg).name()),
                curOrg == null ? null : ExecFormat.date(ends.curDay()) + " görüntüsü",
                curOrg == null ? List.of() : List.of(date(ends.curDay())),
                change == null ? null : Double.valueOf(change.doubleValue()), change == null ? null : "pp", deltaTone));
        b.kpi(new Kpi("teams_scored", "Puanlanan takım", scored.size(), "int", poor > 0 ? T_WARN : T_NEUTRAL,
                poor + " takım Zayıf bantta", List.of(poor), null, null, null));
        b.kpi(new Kpi("findings", "Açık bulgu", org == null ? null : Integer.valueOf(findings), "int", T_NEUTRAL,
                "düzeltme listesindeki kalem", List.of(), null, null, null));

        // ── En düşük puanlı takımlar ──
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (DataQualityService.TeamScore t : scored.subList(0, Math.min(TEAM_LIMIT, scored.size()))) {
            Integer p = ends.prev().get(t.teamId());
            Integer c = ends.cur().get(t.teamId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team", t.teamName());
            r.put("team_id", t.teamId());
            r.put("score", t.score());
            r.put("band", t.band());
            r.put("findings", t.findings());
            r.put("delta", p != null && c != null ? Double.valueOf(c - p) : null);
            r.put("state", toneOf(t.band()));
            teamRows.add(r);
        }
        b.table(new Table("lowest_teams", "En düşük puanlı takımlar", List.of(
                new Column("team", "Takım", "team"),
                new Column("score", "Puan", "int"),
                new Column("band", "Bant", "dq_band"),
                new Column("findings", "Bulgu", "int"),
                new Column("delta", "Değişim", "pp"),
                new Column("state", "Durum", "status")),
                teamRows, scored.size(), "Puanlanan takım yok."));

        // ── En çok puan kaybettiren kurallar ──
        List<Map<String, Object>> ruleRows = new ArrayList<>();
        for (DataQualityService.IssueCount c : costliest.subList(0, Math.min(RULE_LIMIT, costliest.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rule", c.code());
            r.put("failing", c.failing());
            r.put("eligible", c.eligible());
            r.put("points_lost", c.pointsLost());
            ruleRows.add(r);
        }
        b.table(new Table("costly_rules", "En çok puan kaybettiren kurallar", List.of(
                new Column("rule", "Kural", "dq_rule"),
                new Column("failing", "Kusurlu", "int"),
                new Column("eligible", "İncelenen", "int"),
                new Column("points_lost", "Kaybettirdiği puan", "num")),
                ruleRows, costliest.size(), "Kusurlu kural yok."));

        // ── Notlar ──
        b.note("ASOF", "Puanlar rapor anı fotoğrafıdır: " + ExecFormat.stamp(asOfAt) + " itibarıyla, kurum geneli ve "
                + "süzgeçsiz (Veri Kalitesi ekranıyla aynı kural).", datetime(asOf));
        if (change != null) {
            b.note("TREND", "Ay içindeki değişim, ayın son günlük görüntüsü (" + ExecFormat.date(ends.curDay())
                    + ") ile önceki ayın son görüntüsünü (" + ExecFormat.date(ends.prevDay()) + ") karşılaştırır.",
                    date(ends.prevDay()), date(ends.curDay()));
        } else {
            b.note("NO_TREND", "Bu ay ya da önceki ay için günlük görüntü yok; aydan aya karşılaştırma yapılamadı. "
                    + "Görüntüler her gün alınır ve " + DataQualityService.TREND_KEEP_DAYS + " gün saklanır.",
                    DataQualityService.TREND_KEEP_DAYS);
        }
        b.note("METHOD", "Puan = kural başına sağlıklı öğe oranının ağırlıklı ortalaması (YÜKSEK 3, ORTA 2, DÜŞÜK 1). Bantlar: "
                + DataQualityScore.EXCELLENT_MIN + " ve üstü Mükemmel, " + DataQualityScore.GOOD_MIN + " İyi, "
                + DataQualityScore.FAIR_MIN + " İyileştirilmeli, altı Zayıf. Sahipsiz kayıtlar yalnız kurum puanına girer.",
                DataQualityScore.EXCELLENT_MIN, DataQualityScore.GOOD_MIN, DataQualityScore.FAIR_MIN);
        b.data("org_score", org).data("band", band).data("findings", findings).data("month_change", change)
                .data("prev_month_score", prevOrg).data("month_end_score", curOrg)
                .data("prev_day", ends.prevDay()).data("cur_day", ends.curDay());
        return b.build();
    }

    /**
     * TAKIM kapsamı: takımın puanı / bandı / bulguları, ay sonu görüntülerinde takımın satırı ({@code team_key = takım}),
     * takım kovasında en çok puan kaybettiren kurallar. {@code d == null} → takım değerlendirmede yok (silinmiş / pasif).
     */
    SectionResult evaluateTeam(ExecutiveSummaryContext ctx, DataQualityService.TeamDigest d, DataQualityService.MonthEnds me) {
        String asOf = d == null || d.generatedAt() == null ? ctx.nowIso() : ExecutiveSummaryContext.UTC_ISO.format(d.generatedAt());
        Instant asOfAt = d == null || d.generatedAt() == null ? ctx.now() : d.generatedAt();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).snapshot(asOf).headlineKpi("team_score");
        Integer score = d == null ? null : d.score();
        String band = score == null ? DataQualityScore.Band.NO_DATA.name() : d.band();
        int findings = d == null ? 0 : d.findings();
        int items = d == null ? 0 : d.items();
        DataQualityService.MonthEnds ends = me == null ? DataQualityService.MonthEnds.NONE : me;
        Long teamKey = ctx.scopeTeamId();                    // takım kapsamında dolu; Map.of().get(null) NPE atardı
        Integer prevTeam = teamKey == null ? null : ends.prev().get(teamKey);
        Integer curTeam = teamKey == null ? null : ends.cur().get(teamKey);
        Integer change = prevTeam != null && curTeam != null ? Integer.valueOf(curTeam - prevTeam) : null;
        List<DataQualityService.IssueCount> costliest = d == null || d.costliest() == null ? List.of() : d.costliest();

        // ── Durum + hükümler ──
        if (score == null) {
            b.status(NO_DATA).verdict("TEAM_NO_DATA", T_NEUTRAL, "Takımın veri kalitesi puanı hesaplanamadı: puanlanacak "
                    + "kayıt ya da kural yok.");
        } else {
            String st = statusOf(band);
            if (OK.equals(st) && change != null && change <= -DROP_WARN_POINTS) st = ATTENTION;
            b.status(st);
            b.verdict("TEAM_SCORE", toneOf(band), "Takımın veri kalitesi puanı " + score + " ("
                    + ExecFormat.codeLabel("dq_band", band) + "); " + findings + " açık bulgu.",
                    score, new Param(band, "dq_band"), findings);
            if (change != null) {
                if (change > 0) {
                    b.verdict("MONTH_UP", T_OK, "Ay sonu görüntüsüne göre puan geçen aya kıyasla " + change + " puan arttı ("
                            + prevTeam + " → " + curTeam + ").", prevTeam, curTeam, change);
                } else if (change < 0) {
                    b.verdict("MONTH_DOWN", change <= -DROP_WARN_POINTS ? T_WARN : T_NEUTRAL, "Ay sonu görüntüsüne göre puan "
                            + "geçen aya kıyasla " + (-change) + " puan düştü (" + prevTeam + " → " + curTeam + ").",
                            prevTeam, curTeam, -change);
                } else {
                    b.verdict("MONTH_SAME", T_NEUTRAL, "Ay sonu görüntüsüne göre puan geçen ayla aynı (" + prevTeam + " → "
                            + curTeam + ").", prevTeam, curTeam);
                }
            }
            if (!costliest.isEmpty()) {
                DataQualityService.IssueCount c0 = costliest.get(0);
                b.verdict("TEAM_TOP_RULE", T_NEUTRAL, "En çok puan kaybettiren kural: "
                                + ExecFormat.codeLabel("dq_rule", c0.code()) + " — düzeltilirse takımın puanı "
                                + ExecFormat.num(c0.pointsLost(), 1) + " puan artar.",
                        new Param(c0.code(), "dq_rule"), num(c0.pointsLost()));
            }
        }

        // ── Göstergeler ──
        b.kpi(new Kpi("team_score", "Takım puanı", score, "int", toneOf(band),
                ExecFormat.codeLabel("dq_band", band) + " · " + findings + " açık bulgu",
                List.of(new Param(band, "dq_band"), findings), null, null, null));
        String deltaTone = change == null ? null
                : change > 0 ? T_OK : change == 0 ? T_NEUTRAL : change <= -DROP_WARN_POINTS ? T_BAD : T_WARN;
        b.kpi(new Kpi("month_end", "Ay sonu puanı", curTeam, "int",
                curTeam == null ? T_NEUTRAL : toneOf(DataQualityScore.band(curTeam).name()),
                curTeam == null ? null : ExecFormat.date(ends.curDay()) + " görüntüsü",
                curTeam == null ? List.of() : List.of(date(ends.curDay())),
                change == null ? null : Double.valueOf(change.doubleValue()), change == null ? null : "pp", deltaTone));
        b.kpi(new Kpi("findings", "Açık bulgu", score == null ? null : Integer.valueOf(findings), "int", T_NEUTRAL,
                "düzeltme listesindeki kalem", List.of(), null, null, null));
        b.kpi(new Kpi("items", "İncelenen öğe", d == null ? null : Integer.valueOf(items), "int", T_NEUTRAL,
                "takımın kayıtları, izlemeleri ve takım kaydı", List.of(), null, null, null));

        // ── Takımın en çok puan kaybettiren kuralları ──
        List<Map<String, Object>> ruleRows = new ArrayList<>();
        for (DataQualityService.IssueCount c : costliest.subList(0, Math.min(RULE_LIMIT, costliest.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rule", c.code());
            r.put("failing", c.failing());
            r.put("eligible", c.eligible());
            r.put("points_lost", c.pointsLost());
            ruleRows.add(r);
        }
        b.table(new Table("costly_rules", "En çok puan kaybettiren kurallar", List.of(
                new Column("rule", "Kural", "dq_rule"),
                new Column("failing", "Kusurlu", "int"),
                new Column("eligible", "İncelenen", "int"),
                new Column("points_lost", "Kaybettirdiği puan", "num")),
                ruleRows, costliest.size(), "Kusurlu kural yok."));

        // ── Notlar ──
        b.note("TEAM_ASOF", "Puanlar rapor anı fotoğrafıdır: " + ExecFormat.stamp(asOfAt) + " itibarıyla, takımın kendi "
                + "kayıtları (Veri Kalitesi ekranıyla aynı kural).", datetime(asOf));
        if (change != null) {
            b.note("TREND", "Ay içindeki değişim, ayın son günlük görüntüsü (" + ExecFormat.date(ends.curDay())
                    + ") ile önceki ayın son görüntüsünü (" + ExecFormat.date(ends.prevDay()) + ") karşılaştırır.",
                    date(ends.prevDay()), date(ends.curDay()));
        } else {
            b.note("NO_TREND", "Bu ay ya da önceki ay için günlük görüntü yok; aydan aya karşılaştırma yapılamadı. "
                    + "Görüntüler her gün alınır ve " + DataQualityService.TREND_KEEP_DAYS + " gün saklanır.",
                    DataQualityService.TREND_KEEP_DAYS);
        }
        b.note("TEAM_METHOD", "Puan = kural başına sağlıklı öğe oranının ağırlıklı ortalaması (YÜKSEK 3, ORTA 2, DÜŞÜK 1). "
                        + "Bantlar: " + DataQualityScore.EXCELLENT_MIN + " ve üstü Mükemmel, " + DataQualityScore.GOOD_MIN
                        + " İyi, " + DataQualityScore.FAIR_MIN + " İyileştirilmeli, altı Zayıf. Envanter kayıtları sorumlu "
                        + "(SY) takımına sayılır; sahipsiz kayıtlar takım puanına girmez.",
                DataQualityScore.EXCELLENT_MIN, DataQualityScore.GOOD_MIN, DataQualityScore.FAIR_MIN);
        b.data("team_score", score).data("band", band).data("findings", findings).data("items", items)
                .data("month_change", change).data("prev_month_score", prevTeam).data("month_end_score", curTeam)
                .data("prev_day", ends.prevDay()).data("cur_day", ends.curDay());
        return b.build();
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }
}
