package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.service.RenewalForecastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Yaklaşan bitişler + yenileme süresine uyum — TAKIM kapsamı (2026-10-10): bağlamın süzülmüş (SY ya da UG) envanteri ve
 * son sertifikaları; "takımlara göre" tablosu yok, kapsam notu var; yenileme grupları koşu boyu bir kez okunur.
 */
class CertificateSectionsTeamScopeTest {

    /** NOW'dan {@code days} gün + 1 saat sonra biten sertifika. */
    private static String in(int days) {
        return NOW.plusSeconds(days * 86_400L + 3600).toString();
    }

    /** sy (SY=1) süresi dolmuş, ug (SY=2, UG=1) 10 gün, other (SY=2) 5 gün + süresi dolmuş other2. */
    private static final List<CertificateDto> LATEST = List.of(
            cert("sy.example.com", 1L, 1, in(-2), "2025-09-01T00:00:00"),
            cert("ug.example.com", 2L, 2, in(10), "2025-10-01T00:00:00"),
            cert("other.example.com", 2L, 1, in(5), "2025-10-01T00:00:00"),
            cert("other2.example.com", 3L, 1, in(-1), "2025-09-01T00:00:00"));

    private static final Map<String, ExecutiveSummaryContext.InventoryRow> INV = invMap(
            inv("sy.example.com", 1L, null, 1, null),
            inv("ug.example.com", 2L, 1L, 2, "2026-10-15"),
            inv("other.example.com", 2L, null, 1, null),
            inv("other2.example.com", 3L, null, 1, null));

    // ── Yaklaşan bitişler ──

    @Test
    @DisplayName("bitişler (a) yalnız takımın SY/UG sertifikaları, (c) takım tablosu yok + kapsam notu, kurum ifadesi yok")
    void expirationsTeam() {
        SectionResult r = new CertificateExpirySection().compute(teamCtx(1, LATEST, INV));
        assertThat(r.data()).containsEntry("total", 2).containsEntry("expired", 1).containsEntry("within30", 1);
        assertThat(kpi(r, "expired").value()).isEqualTo(1);
        assertThat(kpi(r, "within30").value()).isEqualTo(1);
        assertThat(table(r, "soonest").rows()).extracting(m -> m.get("domain"))
                .containsExactly("sy.example.com", "ug.example.com");
        assertThat(table(r, "soonest").rows().get(1).get("planned_at")).isEqualTo("2026-10-15");
        assertThat(tableCodes(r)).containsExactly("by_tier", "soonest");
        assertThat(noteCodes(r)).containsExactly("ASOF", "TEAM_SCOPE");
        assertThat(r.headlineKpi()).isEqualTo("within30");
        assertNoOrgWording(r);
    }

    @Test
    @DisplayName("bitişler (b) kurum kapsamı değişmez: bütün sertifikalar, 'takımlara göre' tablosu, kapsam notu yok")
    void expirationsOrg() {
        SectionResult r = new CertificateExpirySection().compute(orgCtx(LATEST, INV));
        assertThat(r.data()).containsEntry("total", 4).containsEntry("expired", 2);
        assertThat(tableCodes(r)).containsExactly("by_tier", "by_team", "soonest");
        assertThat(noteCodes(r)).containsExactly("ASOF");
        assertThat(table(r, "by_team").rows()).hasSize(3);
    }

    // ── Yenileme süresine uyum ──

    private static Map<String, List<RenewalTimelinessSection.Group>> groups() {
        Map<String, List<RenewalTimelinessSection.Group>> g = new HashMap<>();
        // Her alan adı Eylül'de zamanında yenilendi (eski bitiş 2026-12-01, yeni 2027-12-01)
        for (String d : List.of("sy.example.com", "ug.example.com", "other.example.com", "other2.example.com")) {
            g.put(d, List.of(new RenewalTimelinessSection.Group(d + "-1", "2026-05-01T00:00:00", "2026-12-01T00:00:00"),
                    new RenewalTimelinessSection.Group(d + "-2", "2026-09-10T00:00:00", "2027-12-01T00:00:00")));
        }
        return g;
    }

    @Test
    @DisplayName("yenileme (a) yalnız takımın SY/UG alan adlarının yenilemeleri + gecikmeleri, (c) kapsam notu, kurum ifadesi yok")
    void renewalsTeam() {
        RenewalTimelinessSection s = new RenewalTimelinessSection(mock(JdbcTemplate.class), mock(RenewalForecastService.class));
        SectionResult r = s.evaluate(teamCtx(1, LATEST, INV), groups(), null, true);
        assertThat(kpi(r, "renewals").value()).isEqualTo(2);
        assertThat(table(r, "renewals").rows()).extracting(m -> m.get("domain"))
                .containsExactlyInAnyOrder("sy.example.com", "ug.example.com");
        // Gecikmede: sy süresi dolmuş (EXPIRED), ug 10 gün kaldı + plan 2026-10-15 gelecekte → gecikmede değil
        assertThat(table(r, "overdue").rows()).extracting(m -> m.get("domain")).containsExactly("sy.example.com");
        // Plan: ug planı Ekim'de → Eylül planı yok
        assertThat(kpi(r, "plans_done").hintParams()).containsExactly(0, 0);
        assertThat(noteCodes(r)).containsExactly("METHOD", "PLANS", "TEAM_SCOPE");
        assertNoOrgWording(r);

        SectionResult org = s.evaluate(orgCtx(LATEST, INV), groups(), null, true);
        assertThat(kpi(org, "renewals").value()).isEqualTo(4);
        assertThat(noteCodes(org)).containsExactly("METHOD", "PLANS");
    }

    @Test
    @DisplayName("yenileme: parmak izi grupları koşu boyu paylaşılır — iki takım + kurum bağlamı aynı haritayla TEK sorgu")
    void renewalsSharedQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RenewalTimelinessSection s = new RenewalTimelinessSection(jdbc, mock(RenewalForecastService.class));
        Map<String, Object> shared = new ConcurrentHashMap<>();
        s.compute(teamCtx(1, LATEST, INV, shared));
        s.compute(teamCtx(2, LATEST, INV, shared));
        s.compute(new ExecutiveSummaryContext(SEP, NOW, 99.9, 30, () -> LATEST, () -> INV, () -> TEAMS, null, null, shared));
        verify(jdbc, times(1)).query(eq(RenewalTimelinessSection.SQL), any(RowCallbackHandler.class),
                eq("2026-07-02T21:00:00"), eq("2026-09-30T21:00:00"));

        // Okuma hatası paylaşılmaz (saklanmaz): sonraki bağlam yeniden dener, ikisi de VERİ YOK
        JdbcTemplate bad = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db")).when(bad).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        RenewalTimelinessSection f = new RenewalTimelinessSection(bad, mock(RenewalForecastService.class));
        Map<String, Object> shared2 = new ConcurrentHashMap<>();
        assertThat(f.compute(teamCtx(1, List.of(), Map.of(), shared2)).status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(f.compute(teamCtx(2, List.of(), Map.of(), shared2)).status()).isEqualTo(SectionResult.NO_DATA);
        verify(bad, times(2)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
}
