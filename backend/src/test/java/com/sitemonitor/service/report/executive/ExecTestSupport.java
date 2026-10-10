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
