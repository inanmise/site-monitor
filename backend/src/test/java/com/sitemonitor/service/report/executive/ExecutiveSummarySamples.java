package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.RenewalForecastService;
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

        return ExecutiveSummaryService.assemble(ctx, List.of(a, b, c, d));
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
