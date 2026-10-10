package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.service.RenewalForecastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static com.sitemonitor.service.report.executive.RenewalTimelinessSection.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Yenileme süresine uyum — sınıf doğruluk tablosu, ay içi yenileme tespiti (İstanbul sınırı, bitişi uzatmayan değişim),
 * plan durumu, gecikme nedenleri, hedef sürenin kaynağı.
 */
class RenewalTimelinessSectionTest {

    private final RenewalForecastService forecast = mock(RenewalForecastService.class);
    private final RenewalTimelinessSection section = new RenewalTimelinessSection(mock(JdbcTemplate.class), forecast);

    @ParameterizedTest(name = "kalan {0} gün, hedef {1} → {2}")
    @CsvSource({
            "-1,30,AFTER_EXPIRY", "-40,30,AFTER_EXPIRY",
            "0,30,LAST_MINUTE", "6,30,LAST_MINUTE",
            "7,30,LATE", "29,30,LATE",
            "30,30,ON_TIME", "120,30,ON_TIME",
            "5,5,LAST_MINUTE", "14,14,ON_TIME", "13,14,LATE" })
    @DisplayName("sınıf: L<0 süresi dolduktan sonra · 0≤L<7 son dakika · 7≤L<hedef geç · L≥hedef zamanında")
    void classify(long lead, int target, String expected) {
        assertThat(RenewalTimelinessSection.classify(lead, target)).isEqualTo(expected);
    }

    private static RenewalTimelinessSection.Group g(String fp, String firstSeen, String notAfter) {
        return new RenewalTimelinessSection.Group(fp, firstSeen, notAfter);
    }

    private static Map<String, List<RenewalTimelinessSection.Group>> groups() {
        Map<String, List<RenewalTimelinessSection.Group>> m = new HashMap<>();
        m.put("a.com", List.of(g("A1", "2026-06-01T00:00:00", "2026-10-01T00:00:00"),
                g("A2", "2026-09-05T08:00:00", "2027-10-01T00:00:00")));                  // 25 gün kala → GEÇ
        m.put("b.com", List.of(g("B2", "2026-09-10T00:00:00", "2027-12-01T00:00:00"),
                g("B1", "2026-05-01T00:00:00", "2026-12-01T00:00:00")));                  // sıra karışık; 82 gün → ZAMANINDA
        m.put("c.com", List.of(g("C1", "2026-05-01T00:00:00", "2026-09-12T00:00:00"),
                g("C2", "2026-09-08T00:00:00", "2027-09-12T00:00:00")));                  // 4 gün → SON DAKİKA
        m.put("d.com", List.of(g("D1", "2026-05-01T00:00:00", "2026-09-01T00:00:00"),
                g("D2", "2026-09-03T00:00:00", "2027-09-01T00:00:00")));                  // −2 → SÜRESİ DOLDUKTAN SONRA
        m.put("e.com", List.of(g("E1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                g("E2", "2026-08-31T21:30:00", "2027-12-01T00:00:00")));                  // İstanbul'da 1 Eylül 00:30 → Eylül'de
        m.put("f.com", List.of(g("F1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                g("F2", "2026-08-31T20:30:00", "2027-12-01T00:00:00")));                  // İstanbul'da 31 Ağustos 23:30 → Ağustos
        m.put("g.com", List.of(g("G1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                g("G2", "2026-09-15T00:00:00", "2026-11-01T00:00:00")));                  // bitişi uzatmıyor → yenileme değil
        m.put("h.com", List.of(g("H1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                g("H2", "2026-09-15T00:00:00", "2027-12-01T00:00:00")));                  // envanterde yok → sayılmaz
        return m;
    }

    @Test
    @DisplayName("tespit: ay içi + bitişi uzatan geçişler; İstanbul ay sınırı; envanter dışı alan adı sayılmaz")
    void detect() {
        ExecutiveSummaryContext c = ctx();
        List<RenewalTimelinessSection.Renewal> rs = RenewalTimelinessSection.detect(groups(), c.from(), c.to(), d -> 30,
                Set.of("a.com", "b.com", "c.com", "d.com", "e.com", "f.com", "g.com"));
        Map<String, String> cls = new HashMap<>();
        Map<String, Long> lead = new HashMap<>();
        for (RenewalTimelinessSection.Renewal r : rs) { cls.put(r.domain(), r.cls()); lead.put(r.domain(), r.leadDays()); }
        assertThat(cls).containsOnlyKeys("a.com", "b.com", "c.com", "d.com", "e.com");
        assertThat(cls).containsEntry("a.com", LATE).containsEntry("b.com", ON_TIME).containsEntry("c.com", LAST_MINUTE)
                .containsEntry("d.com", AFTER_EXPIRY).containsEntry("e.com", ON_TIME);
        assertThat(lead).containsEntry("a.com", 25L).containsEntry("c.com", 4L).containsEntry("d.com", -2L);
    }

    @Test
    @DisplayName("bölüm: uyum %, plan gerçekleşme, gecikmedekiler, durum KRİTİK (süresi dolduktan sonra yenileme)")
    void evaluate() {
        CertificateDto a = cert("a.com", 1L, 1, "2027-10-01T00:00:00", "2026-09-05T08:00:00");
        CertificateDto b = cert("b.com", 1L, 1, "2027-12-01T00:00:00", "2026-09-10T00:00:00");
        CertificateDto c = cert("c.com", 2L, 2, "2027-09-12T00:00:00", "2026-09-08T00:00:00");
        CertificateDto d = cert("d.com", 2L, 2, "2027-09-01T00:00:00", "2026-09-03T00:00:00");
        CertificateDto e = cert("e.com", 2L, 2, "2027-12-01T00:00:00", "2026-08-31T21:30:00");
        // gecikmede: süresi dolmuş + planı yok + planı geçmiş
        CertificateDto x = cert("x.com", 1L, 1, NOW.minusSeconds(86_400).toString(), "2025-10-01T00:00:00");
        CertificateDto y = cert("y.com", 1L, 1, NOW.plusSeconds(10 * 86_400L + 60).toString(), "2025-10-01T00:00:00");
        CertificateDto z = cert("z.com", 2L, 3, NOW.plusSeconds(12 * 86_400L + 60).toString(), "2025-10-01T00:00:00");
        ExecutiveSummaryContext ctx = ctx(SEP, NOW, 99.9, 30, List.of(a, b, c, d, e, x, y, z),
                invMap(inv("a.com", 1L, 1, "2026-09-03"), inv("b.com", 1L, 1, null), inv("c.com", 2L, 2, null),
                        inv("d.com", 2L, 2, null), inv("e.com", 2L, 2, null), inv("x.com", 1L, 1, null),
                        inv("y.com", 1L, 1, null), inv("z.com", 2L, 3, "2026-09-20")),
                Map.of(1L, "Takım A", 2L, "Takım B"));
        SectionResult r = section.evaluate(ctx, groups(), null, true);

        assertThat(kpi(r, "renewals").value()).isEqualTo(5);
        assertThat(kpi(r, "on_time_pct").value()).isEqualTo(40.0);      // b + e
        assertThat(kpi(r, "late").value()).isEqualTo(2);                 // a (geç) + c (son dakika)
        assertThat(kpi(r, "after_expiry").value()).isEqualTo(1);
        // plan: a.com 2026-09-03 → sunulan sertifika 09-05'te düzenlendi → YAPILDI; z.com 09-20 → KAÇIRILDI
        assertThat(kpi(r, "plans_done").value()).isEqualTo(1);
        assertThat(kpi(r, "plans_done").hintParams()).containsExactly(2, 1);
        assertThat(kpi(r, "overdue").value()).isEqualTo(3);
        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);
        assertThat(verdictCodes(r)).containsExactly("COMPLIANCE", "AFTER_EXPIRY", "OVERDUE", "PLANS_MISSED");

        SectionResult.Table overdue = table(r, "overdue");
        assertThat(overdue.rows()).extracting(m -> m.get("domain")).containsExactly("x.com", "y.com", "z.com");
        assertThat(overdue.rows()).extracting(m -> m.get("reason")).containsExactly("EXPIRED", "NO_PLAN", "PLAN_PASSED");
        SectionResult.Table renewals = table(r, "renewals");
        assertThat(renewals.rows().get(0).get("class")).isEqualTo(AFTER_EXPIRY);   // sorunlular önce
        assertThat(renewals.rows().get(0).get("renewed_at")).isEqualTo("2026-09-03");
    }

    @Test
    @DisplayName("gecikme nedeni / plan durumu doğruluk tablosu")
    void overdueReasonAndPlanState() {
        LocalDate today = LocalDate.of(2026, 10, 10);
        CertificateDto old = cert("o.com", 1L, 1, null, "2025-10-01T00:00:00");
        CertificateDto fresh = cert("f.com", 1L, 1, null, "2026-10-05T00:00:00");
        assertThat(RenewalTimelinessSection.overdueReason(-1, 30, "2026-12-01", old, today)).isEqualTo("EXPIRED");
        assertThat(RenewalTimelinessSection.overdueReason(40, 30, null, old, today)).isNull();
        assertThat(RenewalTimelinessSection.overdueReason(10, 30, null, old, today)).isEqualTo("NO_PLAN");
        assertThat(RenewalTimelinessSection.overdueReason(10, 30, "2026-10-01", old, today)).isEqualTo("PLAN_PASSED");
        assertThat(RenewalTimelinessSection.overdueReason(10, 30, "2026-10-20", old, today)).isNull();
        assertThat(RenewalTimelinessSection.overdueReason(10, 30, "2026-10-01", fresh, today)).isNull();
        assertThat(RenewalTimelinessSection.planState("2026-10-01", fresh, today)).isEqualTo("done");
        assertThat(RenewalTimelinessSection.planState("2026-10-01", old, today)).isEqualTo("missed");
        assertThat(RenewalTimelinessSection.planState("2026-10-10", old, today)).isEqualTo("pending");
    }

    @Test
    @DisplayName("hedef süre: ayar > 0 → o; 0 → Vade Takvimi'nin tier süresi")
    void targetSource() {
        assertThat(section.targetDays(ctx(), 1, null)).isEqualTo(30);
        Map<String, Integer> lead = Map.of("default", 14, "t1", 21);
        when(forecast.leadFor(eq(1), anyMap())).thenReturn(21);
        ExecutiveSummaryContext tierCtx = ctx(SEP, NOW, 99.9, 0, List.of(), Map.of(), Map.of());
        assertThat(section.targetDays(tierCtx, 1, lead)).isEqualTo(21);
        assertThat(section.targetDays(tierCtx, 1, null)).isEqualTo(30);   // süre okunamadı → 30
    }

    @Test
    @DisplayName("sorgu ay başından 60 gün geriden, ay sonuna dek; okuma hatası → VERİ YOK")
    void queryWindowAndFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RenewalTimelinessSection s = new RenewalTimelinessSection(jdbc, forecast);
        s.compute(ctx());
        verify(jdbc).query(eq(RenewalTimelinessSection.SQL), any(org.springframework.jdbc.core.RowCallbackHandler.class),
                eq("2026-07-02T21:00:00"), eq("2026-09-30T21:00:00"));

        doThrow(new RuntimeException("db")).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class),
                any(Object[].class));
        SectionResult r = s.compute(ctx());
        assertThat(r.status()).isEqualTo(SectionResult.NO_DATA);
    }
}
