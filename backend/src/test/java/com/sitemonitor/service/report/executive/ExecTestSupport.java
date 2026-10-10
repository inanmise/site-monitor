package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Yönetici özeti testlerinin ortak kurucuları (saf; Spring yok). */
final class ExecTestSupport {

    private ExecTestSupport() { }

    static final YearMonth SEP = YearMonth.of(2026, 9);
    /** Ekim'in 10'u 12:00 TR — Eylül tamamlanmış ay. */
    static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    static ExecutiveSummaryContext ctx(YearMonth m, Instant now, double target, int renewalDays,
                                       List<CertificateDto> latest,
                                       Map<String, ExecutiveSummaryContext.InventoryRow> inv,
                                       Map<Long, String> teams) {
        return new ExecutiveSummaryContext(m, now, target, renewalDays,
                () -> latest == null ? List.of() : latest,
                () -> inv == null ? Map.of() : inv,
                () -> teams == null ? Map.of() : teams);
    }

    static ExecutiveSummaryContext ctx() {
        return ctx(SEP, NOW, 99.9, 30, List.of(), Map.of(), Map.of(1L, "Takım A", 2L, "Takım B"));
    }

    /** Takım adları (takım kapsamı testleri): 1 kapsam takımı, 2 ve 3 başka takımlar. */
    static final Map<Long, String> TEAMS = Map.of(1L, "Takım A", 2L, "Takım B", 3L, "Takım C");

    /**
     * TAKIM kapsamlı bağlam (10 argümanlı kurucu) — Eylül 2026, hedef %99,9, yenileme 30 gün. {@code shared} null →
     * bağlama özel harita; aynı harita verilirse koşu boyu paylaşım sınanır.
     */
    static ExecutiveSummaryContext teamCtx(long teamId, List<CertificateDto> latest,
                                           Map<String, ExecutiveSummaryContext.InventoryRow> inv,
                                           Map<String, Object> shared) {
        return new ExecutiveSummaryContext(SEP, NOW, 99.9, 30,
                () -> latest == null ? List.of() : latest,
                () -> inv == null ? Map.of() : inv,
                () -> TEAMS, teamId, TEAMS.get(teamId), shared);
    }

    static ExecutiveSummaryContext teamCtx(long teamId, List<CertificateDto> latest,
                                           Map<String, ExecutiveSummaryContext.InventoryRow> inv) {
        return teamCtx(teamId, latest, inv, null);
    }

    /** Kurum bağlamı, takım testleriyle AYNI veriyle (karşılaştırma için). */
    static ExecutiveSummaryContext orgCtx(List<CertificateDto> latest, Map<String, ExecutiveSummaryContext.InventoryRow> inv) {
        return ctx(SEP, NOW, 99.9, 30, latest, inv, TEAMS);
    }

    /** SY + UG takımlı envanter satırı. */
    static ExecutiveSummaryContext.InventoryRow inv(String domain, Long team, Long ugTeam, Integer tier, String plan) {
        return new ExecutiveSummaryContext.InventoryRow(domain, team, ugTeam, tier, null, plan);
    }

    static List<String> noteCodes(SectionResult s) {
        return s.notes().stream().map(SectionResult.Note::code).toList();
    }

    static List<String> kpiCodes(SectionResult s) {
        return s.kpis().stream().map(SectionResult.Kpi::code).toList();
    }

    static List<String> tableCodes(SectionResult s) {
        return s.tables().stream().map(SectionResult.Table::code).toList();
    }

    /**
     * Takım kapsamında görünmemesi gereken kurum ifadeleri — hüküm / gösterge / tablo / not metinlerinde (Türkçe sunucu
     * metni; arayüz yeni kodların i18n'ini kullanır).
     */
    static void assertNoOrgWording(SectionResult s) {
        List<String> texts = new java.util.ArrayList<>();
        s.verdicts().forEach(v -> texts.add(v.text()));
        s.kpis().forEach(k -> { texts.add(k.label()); if (k.hint() != null) texts.add(k.hint()); });
        s.tables().forEach(t -> { texts.add(t.title()); if (t.empty() != null) texts.add(t.empty()); });
        s.notes().forEach(n -> texts.add(n.text()));
        for (String t : texts) {
            String low = t.toLowerCase(java.util.Locale.forLanguageTag("tr"));
            org.assertj.core.api.Assertions.assertThat(low).as("kurum ifadesi: " + t)
                    .doesNotContain("kurum").doesNotContain("organisation");
        }
    }

    static CertificateDto cert(String domain, Long teamId, Integer tier, String notAfter, String notBefore) {
        CertificateDto d = new CertificateDto();
        d.setDomain(domain);
        d.setTeamId(teamId);
        d.setTier(tier);
        d.setNotAfter(notAfter);
        d.setNotBefore(notBefore);
        return d;
    }

    static ExecutiveSummaryContext.InventoryRow inv(String domain, Long team, Integer tier, String plan) {
        return new ExecutiveSummaryContext.InventoryRow(domain, team, null, tier, null, plan);
    }

    static Map<String, ExecutiveSummaryContext.InventoryRow> invMap(ExecutiveSummaryContext.InventoryRow... rows) {
        Map<String, ExecutiveSummaryContext.InventoryRow> m = new LinkedHashMap<>();
        for (ExecutiveSummaryContext.InventoryRow r : rows) m.put(r.domain(), r);
        return m;
    }

    static SectionResult.Kpi kpi(SectionResult s, String code) {
        return s.kpis().stream().filter(k -> k.code().equals(code)).findFirst().orElseThrow(
                () -> new AssertionError("kpi yok: " + code));
    }

    static SectionResult.Table table(SectionResult s, String code) {
        return s.tables().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow(
                () -> new AssertionError("tablo yok: " + code));
    }

    static List<String> verdictCodes(SectionResult s) {
        return s.verdicts().stream().map(SectionResult.Verdict::code).toList();
    }
}
