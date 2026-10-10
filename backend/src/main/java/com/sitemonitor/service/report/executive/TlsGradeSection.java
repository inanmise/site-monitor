package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.service.tlsgrade.TlsGradeRules;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (e) TLS YAPILANDIRMA NOTU — kurumun notlanan ağ uç noktalarında A+ … F dağılımı, ay içindeki not düşüşleri ve düşük
 * notun başlıca nedenleri.
 *
 * <h2>Kaynak (not YENİDEN HESAPLANMAZ)</h2>
 * <ul>
 *   <li><b>Dağılım</b> — RAPOR ANI fotoğrafı: paylaşılan {@code CertificateService.getAllLatest} satırlarının
 *       {@code tls_grade} / {@code tls_grade_reasons} alanları ({@link TlsGradeRules}'un liste satırına yazdığı not;
 *       bağlam başına TEK okuma, yeni sorgu yok). Elle yüklenen sertifika "uygulanamaz", notsuz ağ satırı (hiç kontrol
 *       edilmemiş / son kontrolü başarısız) "notsuz" sayılır.</li>
 *   <li><b>Profil kapsaması</b> — {@link TlsGradeService#coverageForDomains} (TEK toplu profil okuması).</li>
 *   <li><b>Düşüşler</b> — AYA BAĞLI: {@code tls_grade_changes} günlüğünde {@code [ay başı, ay sonu)} (İstanbul ayı, UTC
 *       damga) içindeki {@code DROP} kayıtları ({@link TlsGradeService#dropsBetween}: sayım + en yeni
 *       {@value #DROP_SCAN} satır). İlk tarama / eksik profil kaynaklı "bilgi değişimleri" ({@code REFINE}) düşüş
 *       sayılmaz. "Hâlâ düşük" = uç noktanın RAPOR ANINDAKİ notu, düşmeden önceki notun altında.</li>
 * </ul>
 * <b>Sorgu bütçesi:</b> 3 (profil kapsaması + düşüş sayımı + sınırlı düşüş listesi); gerisi paylaşılan bağlamdan.
 *
 * <h2>Durum eşikleri</h2>
 * <ul>
 *   <li>{@code NO_DATA} — notlanan uç nokta yok;</li>
 *   <li>{@code CRITICAL} — Seviye 1 (müşteriye açık üretim) bir uç nokta F notunda;</li>
 *   <li>{@code ATTENTION} — başka bir F notu, ya da C ve altı payı ≥ %{@value #LOW_SHARE_WARN_PCT}, ya da ay içinde düşüp
 *       hâlâ toparlanmamış uç nokta var;</li>
 *   <li>{@code OK} — aksi.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TlsGradeSection implements ExecutiveSummarySection {

    public static final String KEY = "tls-grade";
    static final int ORDER = 50;
    static final String TITLE = "TLS yapılandırma notu";
    /** Posta / PDF okunurluğu: tablo başına en çok satır. */
    static final int TABLE_LIMIT = 10;
    static final int REASON_LIMIT = 8;
    /** Ay penceresinde incelenen en çok düşüş satırı (sayım her zaman tam). */
    static final int DROP_SCAN = 500;
    /** C ve altı payı bu yüzdeye ulaşırsa "takip gerekli". */
    static final int LOW_SHARE_WARN_PCT = 10;
    /** A/A+ payı bu yüzdenin üstündeyse gösterge yeşil. */
    static final int TOP_SHARE_OK_PCT = 80;
    /** Profil kapsaması bu yüzdenin üstündeyse gösterge yeşil. */
    static final int COVERAGE_OK_PCT = 90;

    private final TlsGradeService gradeService;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /** Notlanan uç nokta (hesap görünümü). */
    record Endpoint(String domain, Long teamId, String teamName, Integer tier, String grade, List<String> reasons) { }

    /** Bölüm girdisi — {@link #compute} toplar, {@link #evaluate} saf. */
    record Inputs(List<Endpoint> graded, int ungraded, int notApplicable, Map<String, Object> coverage,
                  TlsGradeService.DropWindow drops) { }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        Map<String, ExecutiveSummaryContext.InventoryRow> inv = safeInventory(ctx);
        Map<Long, String> names = safeNames(ctx);
        List<CertificateDto> latest;
        try { latest = ctx.latestCerts(); } catch (Exception e) { latest = List.of(); }
        if (latest == null) latest = List.of();

        List<Endpoint> graded = new ArrayList<>();
        Set<String> manual = new HashSet<>();
        Set<String> seen = new HashSet<>();
        int ungraded = 0, notApplicable = 0;
        for (CertificateDto d : latest) {
            if (d == null || d.getDomain() == null || Boolean.TRUE.equals(d.getPaused())) continue;
            if (!seen.add(d.getDomain())) continue;
            if ("MANUAL".equalsIgnoreCase(d.getCertSource())) {
                manual.add(d.getDomain());
                notApplicable++;
                continue;
            }
            if (!TlsGradeRules.isGrade(d.getTlsGrade())) { ungraded++; continue; }
            ExecutiveSummaryContext.InventoryRow row = inv.get(d.getDomain());
            Long teamId = d.getTeamId() != null ? d.getTeamId() : row == null ? null : row.teamId();
            Integer tier = d.getTier() != null ? d.getTier() : row == null ? null : row.tier();
            String teamName = d.getTeamName() != null ? d.getTeamName() : teamId == null ? null : names.get(teamId);
            graded.add(new Endpoint(d.getDomain(), teamId, teamName, tier, d.getTlsGrade(),
                    d.getTlsGradeReasons() == null ? List.of() : d.getTlsGradeReasons()));
        }
        // Hiç kontrol edilmemiş envanter kaydı da "notsuz" ağ uç noktasıdır (son kontrol satırı yok)
        for (String domain : inv.keySet()) if (domain != null && !seen.contains(domain)) ungraded++;
        Set<String> network = new LinkedHashSet<>(inv.keySet());
        network.addAll(seen);
        network.removeAll(manual);

        Map<String, Object> coverage = null;
        try {
            coverage = gradeService.coverageForDomains(network);
        } catch (Exception e) {
            log.debug("Yönetici özeti: TLS profil kapsaması okunamadı: {}", e.toString());
        }
        TlsGradeService.DropWindow drops = null;
        try {
            drops = gradeService.dropsBetween(ctx.fromIso(), ctx.toIso(), DROP_SCAN);
        } catch (Exception e) {
            log.debug("Yönetici özeti: TLS not değişim günlüğü okunamadı: {}", e.toString());
        }
        return evaluate(ctx, new Inputs(graded, ungraded, notApplicable, coverage, drops));
    }

    /** Neden kodunun tavanı (null = bilgi notu / bilinmeyen kod). */
    static String capOf(String code) {
        try {
            return code == null ? null : TlsGradeRules.Reason.valueOf(code).cap();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Notu A'nın ALTINA çeken neden mi (tavanı B … F)? */
    static boolean pullsBelowA(String code) {
        String cap = capOf(code);
        return cap != null && TlsGradeRules.rank(cap) < TlsGradeRules.rank("A");
    }

    /** Notu belirleyen neden: tavanı nota eşit ilk kod (kodlar en kötü tavan önce gelir); yoksa ilk sınırlayıcı kod. */
    static String decisive(Endpoint e) {
        for (String c : e.reasons()) if (e.grade().equals(capOf(c))) return c;
        for (String c : e.reasons()) if (capOf(c) != null) return c;
        return null;
    }

    /** Not → tablo durum tonu: A+/A uygun, B nötr, C/D takip, F kritik. */
    static String gradeTone(String grade) {
        if (grade == null) return T_NEUTRAL;
        return switch (grade) {
            case "A+", "A" -> T_OK;
            case "B" -> T_NEUTRAL;
            case "C", "D" -> T_WARN;
            case "F" -> T_BAD;
            default -> T_NEUTRAL;
        };
    }

    /** Ayın bir düşüşü (uç nokta başına en ağırı tutulur). */
    record DropRow(String key, String domain, Long teamId, String from, String to, String at, String current,
                   boolean recovered, Integer tier) {
        int gap() { return TlsGradeRules.rank(from) - TlsGradeRules.rank(to); }
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, Inputs in) {
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).snapshot(ctx.nowIso()).headlineKpi("top_share");
        Map<String, ExecutiveSummaryContext.InventoryRow> inv = safeInventory(ctx);
        Map<Long, String> names = safeNames(ctx);
        List<Endpoint> graded = in.graded() == null ? List.of() : in.graded();

        // ── Dağılım ──
        Map<String, Integer> byGrade = new LinkedHashMap<>();
        Map<String, Integer> tier1ByGrade = new LinkedHashMap<>();
        for (String g : TlsGradeRules.GRADES) { byGrade.put(g, 0); tier1ByGrade.put(g, 0); }
        Map<String, Endpoint> byDomain = new HashMap<>();
        for (Endpoint e : graded) {
            byGrade.merge(e.grade(), 1, Integer::sum);
            if (Integer.valueOf(1).equals(e.tier())) tier1ByGrade.merge(e.grade(), 1, Integer::sum);
            byDomain.putIfAbsent(e.domain(), e);
        }
        int total = graded.size();
        int top = byGrade.get("A+") + byGrade.get("A");
        int low = byGrade.get("C") + byGrade.get("D") + byGrade.get("F");
        int fAll = byGrade.get("F");
        int fTier1 = tier1ByGrade.get("F");
        Double topShare = total == 0 ? null : round1(100.0 * top / total);
        Double lowShare = total == 0 ? null : round1(100.0 * low / total);

        // ── Düşük notun nedenleri (A'nın altındaki uç noktalarda, uç nokta başına bir kez) ──
        int belowA = 0;
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        for (Endpoint e : graded) {
            if (TlsGradeRules.rank(e.grade()) >= TlsGradeRules.rank("A")) continue;
            belowA++;
            for (String c : new LinkedHashSet<>(e.reasons())) if (pullsBelowA(c)) reasonCounts.merge(c, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> reasons = new ArrayList<>(reasonCounts.entrySet());
        reasons.sort(Comparator.comparingInt((Map.Entry<String, Integer> en) -> -en.getValue())
                .thenComparingInt(en -> TlsGradeRules.rank(capOf(en.getKey())))
                .thenComparingInt(en -> TlsGradeRules.REASON_CODES.indexOf(en.getKey())));

        // ── En düşük notlu uç noktalar (C ve altı) ──
        List<Endpoint> lowest = new ArrayList<>();
        for (Endpoint e : graded) if (TlsGradeRules.rank(e.grade()) <= TlsGradeRules.rank("C")) lowest.add(e);
        lowest.sort(Comparator.comparingInt((Endpoint e) -> TlsGradeRules.rank(e.grade()))
                .thenComparingInt(e -> e.tier() == null ? 9 : e.tier())
                .thenComparing(Endpoint::domain));

        // ── Ay içindeki düşüşler ──
        TlsGradeService.DropWindow dw = in.drops();
        Map<String, DropRow> dropByEndpoint = new LinkedHashMap<>();
        if (dw != null) {
            for (TlsGradeChange c : dw.rows()) {
                if (c == null || !TlsGradeRules.isGrade(c.getFromGrade()) || !TlsGradeRules.isGrade(c.getToGrade())) continue;
                String k = c.getInventoryId() != null ? "id:" + c.getInventoryId() : "d:" + c.getDomain();
                Endpoint now = c.getDomain() == null ? null : byDomain.get(c.getDomain());
                String current = now == null ? null : now.grade();
                boolean recovered = current != null && TlsGradeRules.rank(current) >= TlsGradeRules.rank(c.getFromGrade());
                ExecutiveSummaryContext.InventoryRow row = c.getDomain() == null ? null : inv.get(c.getDomain());
                Integer tier = now != null && now.tier() != null ? now.tier() : row == null ? null : row.tier();
                DropRow r = new DropRow(k, c.getDomain(), c.getTeamId(), c.getFromGrade(), c.getToGrade(), c.getChangedAt(),
                        current, recovered, tier);
                DropRow prev = dropByEndpoint.get(k);
                // Uç nokta başına EN AĞIR düşüş (satırlar yeniden eskiye geldiği için eşitlikte en yeni kalır)
                if (prev == null || r.gap() > prev.gap()) dropByEndpoint.put(k, r);
            }
        }
        List<DropRow> drops = new ArrayList<>(dropByEndpoint.values());
        int unrecovered = (int) drops.stream().filter(d -> !d.recovered()).count();
        drops.sort(Comparator.comparing(DropRow::recovered)
                .thenComparingInt(d -> -d.gap())
                .thenComparingInt(d -> TlsGradeRules.rank(d.to()))
                .thenComparingInt(d -> d.tier() == null ? 9 : d.tier())
                .thenComparing(d -> d.at() == null ? "" : d.at(), Comparator.reverseOrder())
                .thenComparing(d -> d.domain() == null ? "" : d.domain()));
        long dropTotal = dw == null ? 0 : dw.total();

        // ── Profil kapsaması ──
        Map<String, Object> cov = in.coverage();
        int endpoints = intOf(cov, "endpoints");
        int probed = intOf(cov, "ok") + intOf(cov, "partial");
        int probeGaps = intOf(cov, "failed");
        Double coveragePct = cov == null || endpoints == 0 ? null : round1(100.0 * probed / endpoints);

        // ── Durum + hükümler ──
        boolean warn = fAll > 0 || (lowShare != null && lowShare >= LOW_SHARE_WARN_PCT) || unrecovered > 0;
        if (total == 0) {
            b.status(NO_DATA).verdict("NO_DATA", T_NEUTRAL,
                    "Henüz notlanan TLS uç noktası yok (sertifika kontrolü yapılmış ağ uç noktası gerekir).");
        } else {
            b.status(fTier1 > 0 ? CRITICAL : warn ? ATTENTION : OK);
            String distTone = lowShare >= LOW_SHARE_WARN_PCT ? T_WARN : topShare >= TOP_SHARE_OK_PCT ? T_OK : T_NEUTRAL;
            b.verdict("DISTRIBUTION", distTone, "Notlanan " + total + " uç nokta: A/A+ payı " + ExecFormat.pct(topShare, 1)
                    + ", C ve altı " + ExecFormat.pct(lowShare, 1) + ".", total, pct(topShare), pct(lowShare));
            if (fAll > 0) {
                b.verdict("F_GRADES", fTier1 > 0 ? T_BAD : T_WARN, fAll + " uç nokta F notunda; " + fTier1
                        + " tanesi Seviye 1 (müşteriye açık üretim).", fAll, fTier1);
            }
        }
        if (dw != null && total > 0) {
            if (dropTotal > 0) {
                b.verdict("DROPS", unrecovered > 0 ? T_WARN : T_NEUTRAL, "Ay içinde " + dropTotal + " not düşüşü kaydedildi ("
                        + drops.size() + " uç nokta); " + unrecovered + " tanesi hâlâ eski notunun altında.",
                        dropTotal, drops.size(), unrecovered);
            } else {
                b.verdict("NO_DROPS", T_OK, "Ay içinde not düşüşü kaydedilmedi.");
            }
        }
        if (!reasons.isEmpty()) {
            Map.Entry<String, Integer> r0 = reasons.get(0);
            b.verdict("TOP_REASON", T_NEUTRAL, "Notu A'nın altına çeken en sık neden: "
                    + ExecFormat.codeLabel("tls_reason", r0.getKey()) + " (" + r0.getValue() + " uç nokta).",
                    new Param(r0.getKey(), "tls_reason"), r0.getValue());
        }

        // ── Göstergeler ──
        b.kpi(new Kpi("top_share", "A / A+ payı", topShare, "pct",
                topShare == null ? T_NEUTRAL : topShare >= TOP_SHARE_OK_PCT ? T_OK : topShare < 50 ? T_WARN : T_NEUTRAL,
                total + " uç noktanın " + top + " tanesi", List.of(total, top), null, null, null));
        b.kpi(new Kpi("low_share", "C ve altı", lowShare, "pct",
                lowShare == null ? T_NEUTRAL : lowShare >= LOW_SHARE_WARN_PCT ? T_WARN : low > 0 ? T_NEUTRAL : T_OK,
                low + " uç nokta", List.of(low), null, null, null));
        b.kpi(new Kpi("f_count", "F notu", fAll, "int", fTier1 > 0 ? T_BAD : fAll > 0 ? T_WARN : T_OK,
                fTier1 + " tanesi Seviye 1", List.of(fTier1), null, null, null));
        b.kpi(new Kpi("graded", "Notlanan uç nokta", total, "int", T_NEUTRAL,
                in.ungraded() + " notsuz · " + in.notApplicable() + " dosyadan (notlanmaz)",
                List.of(in.ungraded(), in.notApplicable()), null, null, null));
        b.kpi(new Kpi("probe_coverage", "TLS profili taranan", coveragePct, "pct",
                coveragePct == null ? T_NEUTRAL : coveragePct >= COVERAGE_OK_PCT ? T_OK : T_NEUTRAL,
                cov == null ? null : endpoints + " uç noktanın " + probed + " tanesi · " + probeGaps + " taranamadı",
                cov == null ? List.of() : List.of(endpoints, probed, probeGaps), null, null, null));
        b.kpi(new Kpi("drops", "Ay içindeki not düşüşü", dw == null ? null : Long.valueOf(dropTotal), "int",
                dw == null ? T_NEUTRAL : unrecovered > 0 ? T_WARN : dropTotal > 0 ? T_NEUTRAL : T_OK,
                dw == null ? null : drops.size() + " uç nokta · " + unrecovered + " tanesi hâlâ düşük",
                dw == null ? List.of() : List.of(drops.size(), unrecovered), null, null, null));

        // ── Tablolar ──
        List<Map<String, Object>> gradeRows = new ArrayList<>();
        for (String g : TlsGradeRules.GRADES) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("grade", g);
            r.put("endpoints", byGrade.get(g));
            r.put("share", total == 0 ? 0.0 : round1(100.0 * byGrade.get(g) / total));
            r.put("tier1", tier1ByGrade.get(g));
            r.put("state", gradeTone(g));
            gradeRows.add(r);
        }
        b.table(new Table("grades", "Not dağılımı", List.of(
                new Column("grade", "Not", "text"),
                new Column("endpoints", "Uç nokta", "int"),
                new Column("share", "Pay", "pct"),
                new Column("tier1", "Seviye 1'de", "int"),
                new Column("state", "Durum", "status")),
                total == 0 ? List.of() : gradeRows, total == 0 ? 0 : gradeRows.size(), "Notlanan uç nokta yok."));

        List<Map<String, Object>> lowRows = new ArrayList<>();
        for (Endpoint e : lowest.subList(0, Math.min(TABLE_LIMIT, lowest.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", e.domain());
            r.put("team", e.teamName());
            r.put("team_id", e.teamId());
            r.put("tier", e.tier());
            r.put("grade", e.grade());
            r.put("reason", decisive(e));
            r.put("state", gradeTone(e.grade()));
            lowRows.add(r);
        }
        b.table(new Table("lowest", "En düşük notlu uç noktalar (C ve altı)", List.of(
                new Column("domain", "Sertifika", "text"),
                new Column("team", "Takım", "team"),
                new Column("tier", "Seviye", "tier"),
                new Column("grade", "Not", "text"),
                new Column("reason", "Neden", "tls_reason"),
                new Column("state", "Durum", "status")),
                lowRows, lowest.size(), "C ve altında notlanan uç nokta yok."));

        if (dw != null) {
            List<Map<String, Object>> dropRows = new ArrayList<>();
            for (DropRow d : drops.subList(0, Math.min(TABLE_LIMIT, drops.size()))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("domain", d.domain());
                r.put("team", d.teamId() == null ? null : names.get(d.teamId()));
                r.put("team_id", d.teamId());
                r.put("tier", d.tier());
                r.put("from_grade", d.from());
                r.put("to_grade", d.to());
                r.put("dropped_at", istDay(d.at()));
                r.put("current_grade", d.current());
                r.put("state", d.recovered() ? T_OK : TlsGradeRules.rank(d.to()) <= TlsGradeRules.rank("D") ? T_BAD : T_WARN);
                dropRows.add(r);
            }
            b.table(new Table("drops", "Ay içindeki not düşüşleri (toparlanmayanlar önce)", List.of(
                    new Column("domain", "Sertifika", "text"),
                    new Column("team", "Takım", "team"),
                    new Column("tier", "Seviye", "tier"),
                    new Column("from_grade", "Önceki not", "text"),
                    new Column("to_grade", "Yeni not", "text"),
                    new Column("dropped_at", "Düştü", "date"),
                    new Column("current_grade", "Bugün", "text"),
                    new Column("state", "Durum", "status")),
                    dropRows, drops.size(), "Ay içinde not düşüşü kaydedilmedi."));
        }

        List<Map<String, Object>> reasonRows = new ArrayList<>();
        for (Map.Entry<String, Integer> en : reasons.subList(0, Math.min(REASON_LIMIT, reasons.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reason", en.getKey());
            r.put("cap", capOf(en.getKey()));
            r.put("endpoints", en.getValue());
            r.put("share", belowA == 0 ? 0.0 : round1(100.0 * en.getValue() / belowA));
            reasonRows.add(r);
        }
        b.table(new Table("reasons", "Notu A'nın altına çeken nedenler", List.of(
                new Column("reason", "Neden", "tls_reason"),
                new Column("cap", "Tavan", "text"),
                new Column("endpoints", "Uç nokta", "int"),
                new Column("share", "Pay", "pct")),
                reasonRows, reasons.size(), "A'nın altında notlanan uç nokta yok."));

        // ── Notlar ──
        b.note("ASOF", "Not dağılımı rapor anı fotoğrafıdır: " + ExecFormat.stamp(ctx.now()) + " itibarıyla aktif envanterin son "
                + "kontrolleri ve TLS profilleri. Dosyadan yüklenen sertifikalar notlanmaz.", datetime(ctx.nowIso()));
        b.note("METHOD", "Not, TLS notu kuralıyla (A+ … F; her bulgu bir tavan, sonuç en düşük tavan) hesaplanır, burada yeniden "
                + "hesaplanmaz. Düşüşler not değişim günlüğünden okunur; ilk tarama ya da eksik profil kaynaklı bilgi "
                + "değişimleri düşüş sayılmaz. Günlük, sertifika kontrol serisiyle aynı süre saklanır (varsayılan 180 gün).");
        if (cov != null && endpoints > 0 && probed == 0) {
            b.note("NO_PROFILES", "TLS profilleri henüz taranmadı: protokol sürümleri ve OCSP zımbalama bilinmediği için notlar "
                    + "en çok A olabilir. Günlük tarama ilerledikçe dağılım netleşir.");
        }
        if (dw == null) {
            b.note("DROPS_UNAVAILABLE", "Not değişim günlüğü okunamadı; ay içindeki düşüşler bu raporda gösterilemiyor.");
        } else if (dw.rows().size() < dropTotal) {
            b.note("DROPS_SAMPLED", "Ay içinde " + dropTotal + " düşüş kaydı var; uç nokta tablosu en yeni " + dw.rows().size()
                    + " kayıttan kuruldu.", dropTotal, dw.rows().size());
        }
        b.data("grades", byGrade).data("graded", total).data("ungraded", in.ungraded())
                .data("not_applicable", in.notApplicable()).data("top_share", topShare).data("low_share", lowShare)
                .data("f_tier1", fTier1).data("drops_total", dw == null ? null : Long.valueOf(dropTotal))
                .data("drops_unrecovered", dw == null ? null : Integer.valueOf(unrecovered)).data("coverage", cov);
        return b.build();
    }

    private static int intOf(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** UTC damga → İstanbul günü (ISO). */
    static String istDay(String utc) {
        Instant t = AlarmNoiseSection.parse(utc);
        return t == null ? null : t.atZone(ExecutiveSummaryContext.IST).toLocalDate().toString();
    }

    private static Map<String, ExecutiveSummaryContext.InventoryRow> safeInventory(ExecutiveSummaryContext ctx) {
        try {
            Map<String, ExecutiveSummaryContext.InventoryRow> m = ctx.inventory();
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<Long, String> safeNames(ExecutiveSummaryContext ctx) {
        try {
            Map<Long, String> m = ctx.teamNames();
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
