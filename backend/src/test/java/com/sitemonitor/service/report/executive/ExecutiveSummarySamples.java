package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.RenewalForecastService;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * GERÇEK bölüm hesaplarından geçen örnek özetler — e-posta sözleşme testi ({@code EmailSamples}), PDF testi ve i18n kapısı
 * aynı veriyi kullanır. Türkçe karakterli ve uzun adlar bilerek (çizim/kırpma zorlaması).
 */
public final class ExecutiveSummarySamples {

    private ExecutiveSummarySamples() { }

    public static final String TR_TEAM = "Ödeme Ağ Geçidi (ğüşıöç İĞÜŞÖÇ)";
    static final String LONG_HOST = "very-long-subdomain-name-for-stress-testing.internal-services.region-a.example.com";
    static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    /** Her bölüm dolu, sorunlu: hedef kaçtı, dalgalanma, süresi dolmuş sertifika, süresi dolduktan sonra yenileme. */
    public static ExecutiveSummary full() {
        return build(true);
    }

    /** Sakin ay: hedef karşılandı, alarm yok, 30 gün içinde bitiş yok, yenileme yok. */
    public static ExecutiveSummary quiet() {
        return build(false);
    }

    private static ExecutiveSummary build(boolean busy) {
        YearMonth m = YearMonth.of(2026, 9);
        Map<Long, String> teams = Map.of(1L, TR_TEAM, 2L, "Takım B", 3L, "İnternet Şubesi");
        List<CertificateDto> latest = new ArrayList<>();
        Map<String, ExecutiveSummaryContext.InventoryRow> inv = new LinkedHashMap<>();
        if (busy) {
            add(latest, inv, "expired.example.com", 1L, 1, NOW.minusSeconds(3 * 86_400L), "2025-09-01T00:00:00", null);
            add(latest, inv, LONG_HOST, 1L, 1, NOW.plusSeconds(9 * 86_400L), "2025-10-01T00:00:00", "2026-10-12");
            add(latest, inv, "odeme.example.com", 2L, 2, NOW.plusSeconds(40 * 86_400L), "2025-10-01T00:00:00", "2026-09-20");
            add(latest, inv, "renewed.example.com", 3L, 3, Instant.parse("2027-09-01T00:00:00Z"), "2026-09-03T00:00:00", "2026-09-02");
            add(latest, inv, "ontime.example.com", 3L, null, Instant.parse("2027-12-01T00:00:00Z"), "2026-09-10T00:00:00", null);
        } else {
            add(latest, inv, "far.example.com", 2L, 2, NOW.plusSeconds(200 * 86_400L), "2026-01-01T00:00:00", null);
        }
        ExecutiveSummaryContext ctx = new ExecutiveSummaryContext(m, NOW, 99.9, 30, () -> latest, () -> inv, () -> teams);

        // (a) erişilebilirlik
        AvailabilitySection av = new AvailabilitySection(mock(MonitoringOverviewService.class), mock(JdbcTemplate.class));
        Map<String, long[]> byKey = new LinkedHashMap<>();
        Map<String, AvailabilitySection.MonitorRef> refs = new HashMap<>();
        byKey.put("HTTP|1", new long[]{ 43_200, busy ? 42_900 : 43_199, 43_000, 42_990 });
        refs.put("HTTP|1", new AvailabilitySection.MonitorRef("API", "http", 1L, TR_TEAM, "Ödeme", true));
        byKey.put("PING|2", new long[]{ 8_640, 8_640, 8_000, 8_000 });
        refs.put("PING|2", new AvailabilitySection.MonitorRef("Ağ", "ping", 2L, "Takım B", null, true));
        byKey.put("UPTIME|" + LONG_HOST, new long[]{ 720, busy ? 700 : 720, 700, 700 });
        refs.put("UPTIME|" + LONG_HOST, new AvailabilitySection.MonitorRef(LONG_HOST, "uptime", 3L, "İnternet Şubesi", "Şube", true));
        Map<java.time.LocalDate, long[]> daily = new java.util.TreeMap<>();
        for (int d = 1; d <= 30; d++) daily.put(m.atDay(d), new long[]{ 1700, busy && d == 12 ? 1600 : 1700 });
        SectionResult a = av.evaluate(ctx, new AvailabilitySection.Rollup(byKey, daily, "hourly_rollup", false), refs);

        // (b) gürültü
        AlarmNoiseSection noise = new AlarmNoiseSection(mock(AlertEventRepository.class));
        List<AlarmNoiseSection.Ev> evs = new ArrayList<>();
        if (busy) {
            for (int i = 0; i < 7; i++) {
                evs.add(AlarmNoiseSection.Ev.of(new Object[]{ LONG_HOST, "HTTP_DOWN", i == 0 ? "CRITICAL" : "HIGH",
                        "2026-09-1" + i + "T10:00:00", "2026-09-1" + i + "T10:03:00", true, false, i % 2 == 0,
                        i % 2 == 0 ? "2026-09-1" + i + "T10:01:00" : null, 1L }));
            }
            evs.add(AlarmNoiseSection.Ev.of(new Object[]{ "odeme.example.com", "PORT_DOWN", "WARNING", "2026-09-20T02:00:00",
                    null, false, false, false, null, 2L }));
        }
        SectionResult b = noise.evaluate(ctx, evs, busy ? 4L : 0L);

        // (c) bitişler
        SectionResult c = new CertificateExpirySection().compute(ctx);

        // (d) yenileme
        RenewalTimelinessSection rn = new RenewalTimelinessSection(mock(JdbcTemplate.class), mock(RenewalForecastService.class));
        Map<String, List<RenewalTimelinessSection.Group>> groups = new HashMap<>();
        if (busy) {
            groups.put("renewed.example.com", List.of(
                    new RenewalTimelinessSection.Group("R1", "2026-05-01T00:00:00", "2026-09-01T00:00:00"),
                    new RenewalTimelinessSection.Group("R2", "2026-09-03T00:00:00", "2027-09-01T00:00:00")));
            groups.put("ontime.example.com", List.of(
                    new RenewalTimelinessSection.Group("O1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                    new RenewalTimelinessSection.Group("O2", "2026-09-10T00:00:00", "2027-12-01T00:00:00")));
        }
        SectionResult d = rn.evaluate(ctx, groups, null, true);

        // (e) TLS notu
        TlsGradeSection tls = new TlsGradeSection(mock(TlsGradeService.class));
        List<TlsGradeSection.Endpoint> graded = new ArrayList<>();
        graded.add(new TlsGradeSection.Endpoint("far.example.com", 2L, "Takım B", 2, "A+", List.of()));
        graded.add(new TlsGradeSection.Endpoint("a.example.com", 3L, "İnternet Şubesi", 3, "A", List.of("OCSP_STAPLING_MISSING")));
        List<TlsGradeChange> drops = new ArrayList<>();
        if (busy) {
            graded.add(new TlsGradeSection.Endpoint(LONG_HOST, 1L, TR_TEAM, 1, "F", List.of("CERT_EXPIRED", "TLS10_ENABLED")));
            graded.add(new TlsGradeSection.Endpoint("odeme.example.com", 2L, "Takım B", 2, "B", List.of("TLS10_ENABLED", "NO_TLS13")));
            graded.add(new TlsGradeSection.Endpoint("eski.example.com", 3L, "İnternet Şubesi", 3, "C",
                    List.of("WEAK_CIPHER_ACCEPTED", "TLS11_ENABLED")));
            drops.add(change(11L, LONG_HOST, 1L, "B", "F", "2026-09-21T08:00:00"));
            drops.add(change(12L, "odeme.example.com", 2L, "A", "B", "2026-09-05T08:00:00"));
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("endpoints", graded.size() + 1);
        coverage.put("ok", busy ? 3 : 2);
        coverage.put("partial", busy ? 1 : 0);
        coverage.put("failed", busy ? 1 : 0);
        coverage.put("pending", busy ? 1 : 1);
        SectionResult e = tls.evaluate(ctx, new TlsGradeSection.Inputs(graded, busy ? 2 : 0, busy ? 1 : 0, coverage,
                new TlsGradeService.DropWindow(drops.size(), drops)));

        // (f) kripto hazırlığı
        CryptoReadinessSection cr = new CryptoReadinessSection(mock(CryptoInventoryService.class));
        SectionResult f = cr.evaluate(ctx, cryptoSummary(busy));

        // (g) veri kalitesi
        DataQualitySection dq = new DataQualitySection(mock(DataQualityService.class));
        List<DataQualityService.TeamScore> teamScores = busy
                ? List.of(new DataQualityService.TeamScore(1L, TR_TEAM, 42, "POOR", 9),
                          new DataQualityService.TeamScore(2L, "Takım B", 81, "GOOD", 3),
                          new DataQualityService.TeamScore(3L, "İnternet Şubesi", 95, "EXCELLENT", 1))
                : List.of(new DataQualityService.TeamScore(2L, "Takım B", 93, "EXCELLENT", 1));
        List<DataQualityService.IssueCount> costly = busy
                ? List.of(new DataQualityService.IssueCount("INV_NO_TEAM", 4, 40, 6.2),
                          new DataQualityService.IssueCount("TEAM_NO_ESCALATION", 1, 3, 4.1),
                          new DataQualityService.IssueCount("INV_NO_CONTACTS", 12, 40, 1.9))
                : List.of(new DataQualityService.IssueCount("MON_NO_GROUP", 1, 20, 0.4));
        DataQualityService.Digest digest = new DataQualityService.Digest(NOW, busy ? 71 : 94, busy ? "NEEDS_ATTENTION" : "EXCELLENT",
                busy ? 17 : 1, teamScores, costly, costly);
        DataQualityService.MonthEnds ends = new DataQualityService.MonthEnds("2026-08-31", "2026-09-30",
                Map.of(0L, busy ? 78 : 93, 1L, 50), Map.of(0L, busy ? 71 : 94, 1L, 42));
        SectionResult g = dq.evaluate(ctx, digest, ends);

        return ExecutiveSummaryService.assemble(ctx, List.of(a, b, c, d, e, f, g));
    }

    private static TlsGradeChange change(Long inv, String domain, Long team, String from, String to, String at) {
        TlsGradeChange ch = new TlsGradeChange();
        ch.setInventoryId(inv);
        ch.setDomain(domain);
        ch.setTeamId(team);
        ch.setFromGrade(from);
        ch.setToGrade(to);
        ch.setDirection(TlsGradeChange.DROP);
        ch.setChangedAt(at);
        return ch;
    }

    /** {@code CryptoInventoryService.summary} biçiminde örnek (kurum geneli). */
    static Map<String, Object> cryptoSummary(boolean busy) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", busy ? 12 : 4);
        s.put("checked", busy ? 11 : 4);
        s.put("unchecked", busy ? 1 : 0);
        s.put("by_pqc", Map.of("VULNERABLE", busy ? 10 : 4, "HYBRID", 0, "PQC", 0, "UNKNOWN", busy ? 2 : 0));
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("BROKEN", busy ? 2 : 0);
        cat.put("LEGACY", busy ? 5 : 0);
        cat.put("MODERN", busy ? 3 : 4);
        cat.put("PQC_READY", 0);
        cat.put("UNKNOWN", busy ? 2 : 0);
        s.put("by_category", cat);
        s.put("by_band", Map.of("P1", busy ? 2 : 0, "P2", busy ? 4 : 0, "P3", busy ? 3 : 2, "P4", busy ? 3 : 2, "DONE", 0));
        s.put("remnants", Map.of("md5_leaf", 0, "sha1_leaf", busy ? 1 : 0, "md5_intermediate", 0,
                "sha1_intermediate", busy ? 1 : 0, "sha1_root", 0, "chains_examined", busy ? 11 : 4, "affected", busy ? 2 : 0));
        s.put("vulnerable_expiring_90d", busy ? 3 : 0);
        s.put("legacy_reissue", busy ? 1 : 0);
        s.put("generated_at", "2026-10-10T08:59:00");
        List<Map<String, Object>> top = new ArrayList<>();
        if (busy) {
            top.add(topRow(1, LONG_HOST, 1L, TR_TEAM, 1, "BROKEN", 90, "P1", 9));
            top.add(topRow(2, "odeme.example.com", 2L, "Takım B", 2, "LEGACY", 60, "P2", 40));
        } else {
            top.add(topRow(1, "far.example.com", 2L, "Takım B", 2, "MODERN", 35, "P3", 200));
        }
        s.put("top", top);
        return s;
    }

    private static Map<String, Object> topRow(int rank, String domain, Long team, String teamName, Integer tier,
                                              String category, int score, String band, Integer days) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("rank", rank);
        t.put("domain", domain);
        t.put("source", "NETWORK");
        t.put("team_id", team);
        t.put("team_name", teamName);
        t.put("tier", tier);
        t.put("category", category);
        t.put("score", score);
        t.put("band", band);
        t.put("days_remaining", days);
        return t;
    }

    private static void add(List<CertificateDto> latest, Map<String, ExecutiveSummaryContext.InventoryRow> inv,
                            String domain, Long team, Integer tier, Instant notAfter, String notBefore, String plan) {
        CertificateDto dto = new CertificateDto();
        dto.setDomain(domain);
        dto.setTeamId(team);
        dto.setTier(tier);
        dto.setNotAfter(ExecutiveSummaryContext.UTC_ISO.format(notAfter));
        dto.setNotBefore(notBefore);
        latest.add(dto);
        inv.put(domain, new ExecutiveSummaryContext.InventoryRow(domain, team, null, tier, null, plan));
    }
}
