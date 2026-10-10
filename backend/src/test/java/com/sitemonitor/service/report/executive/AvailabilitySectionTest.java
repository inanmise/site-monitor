package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.MonitoringOverviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Erişilebilirlik hedefi uyumu — hesap doğruluk tablosu, İstanbul ay sınırları (saatlik kova), günlük özete düşüş,
 * pano satırlarından takım/hizmet eşlemesi.
 */
class AvailabilitySectionTest {

    private final AvailabilitySection section = new AvailabilitySection(mock(MonitoringOverviewService.class), mock(JdbcTemplate.class));

    private static AvailabilitySection.MonitorRef ref(Long team, String teamName, String group) {
        return new AvailabilitySection.MonitorRef("izleme", "http", team, teamName, group, true);
    }

    /** cur_total, cur_up, prev_total, prev_up. */
    private static long[] v(long ct, long cu, long pt, long pu) { return new long[]{ ct, cu, pt, pu }; }

    @Test
    @DisplayName("ay sınırı İstanbul: Eylül 2026 = [2026-08-31T21:00Z, 2026-09-30T21:00Z); saatlik kova sorgusu bu dizgelerle")
    void monthBoundaryIstanbul() {
        ExecutiveSummaryContext c = ctx();
        assertThat(c.from()).isEqualTo(Instant.parse("2026-08-31T21:00:00Z"));
        assertThat(c.to()).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
        assertThat(c.prevFrom()).isEqualTo(Instant.parse("2026-07-31T21:00:00Z"));
        assertThat(ExecutiveSummaryContext.HOUR_BUCKET.format(c.from())).isEqualTo("2026-08-31T21");
        // 31 Ağustos 21:00 UTC kovası İstanbul'da 1 Eylül'dür; 20:00 kovası hâlâ 31 Ağustos
        assertThat(AvailabilitySection.istDayOfHour("2026-08-31T21")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(AvailabilitySection.istDayOfHour("2026-08-31T20")).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(AvailabilitySection.istDayOfHour("bozuk")).isNull();

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>(Map.of("monitor_type", "HTTP", "monitor_key", "7",
                "cur_total", 100L, "cur_up", 99L, "prev_total", 0L, "prev_up", 0L));
        when(jdbc.queryForList(startsWith("SELECT monitor_type"), any(Object[].class))).thenReturn(List.of(row));
        when(jdbc.queryForList(startsWith("SELECT hour_bucket"), any(Object[].class))).thenReturn(List.of(
                new HashMap<>(Map.of("bucket", "2026-08-31T21", "total", 10L, "up", 10L)),
                new HashMap<>(Map.of("bucket", "2026-09-01T03", "total", 10L, "up", 9L))));
        AvailabilitySection s = new AvailabilitySection(mock(MonitoringOverviewService.class), jdbc);
        AvailabilitySection.Rollup r = s.loadRollup(c);
        verify(jdbc).queryForList(contains("FROM monitor_check_hourly"),
                eq("2026-08-31T21"), eq("2026-08-31T21"), eq("2026-08-31T21"), eq("2026-08-31T21"),
                eq("2026-07-31T21"), eq("2026-09-30T21"));
        assertThat(r.source()).isEqualTo("hourly_rollup");
        assertThat(r.approximate()).isFalse();
        assertThat(r.byKey().get("HTTP|7")).containsExactly(100L, 99L, 0L, 0L);
        // iki kova da İstanbul'da 1 Eylül
        assertThat(r.daily()).containsOnlyKeys(LocalDate.of(2026, 9, 1));
        assertThat(r.daily().get(LocalDate.of(2026, 9, 1))).containsExactly(20L, 19L);
    }

    @Test
    @DisplayName("saatlik özet boş → günlük özet (UTC günleri), 'yaklaşık' işaretli")
    void fallsBackToDaily() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("monitor_check_hourly"), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(matches("(?s)SELECT monitor_type.*monitor_check_daily.*"), any(Object[].class)))
                .thenReturn(List.of(new HashMap<>(Map.of("monitor_type", "PING", "monitor_key", "3",
                        "cur_total", 50L, "cur_up", 50L, "prev_total", 0L, "prev_up", 0L))));
        when(jdbc.queryForList(matches("(?s)SELECT day.*monitor_check_daily.*"), any(Object[].class)))
                .thenReturn(List.of(new HashMap<>(Map.of("bucket", "2026-09-02", "total", 50L, "up", 50L))));
        AvailabilitySection s = new AvailabilitySection(mock(MonitoringOverviewService.class), jdbc);
        AvailabilitySection.Rollup r = s.loadRollup(ctx());
        verify(jdbc).queryForList(contains("FROM monitor_check_daily"), eq("2026-09-01"), eq("2026-09-01"),
                eq("2026-09-01"), eq("2026-09-01"), eq("2026-08-01"), eq("2026-10-01"));
        assertThat(r.source()).isEqualTo("daily_rollup");
        assertThat(r.approximate()).isTrue();
        assertThat(r.byKey()).containsKey("PING|3");
    }

    @Test
    @DisplayName("hedef karşılandı: kurum %99,95 ≥ %99,9; bir takım altında → TAKİP; fark, bütçe, eşdeğer kesinti")
    void orgMetTeamBelow() {
        Map<String, long[]> byKey = new LinkedHashMap<>();
        byKey.put("HTTP|1", v(10000, 9999, 10000, 10000));   // A: %99,99 (önceki %100)
        byKey.put("HTTP|2", v(10000, 9991, 0, 0));           // B: %99,91
        byKey.put("PING|3", v(1000, 995, 0, 0));             // C: %99,5 — altında
        Map<String, AvailabilitySection.MonitorRef> refs = Map.of(
                "HTTP|1", ref(1L, "Takım A", "Ödeme"),
                "HTTP|2", ref(1L, "Takım A", null),
                "PING|3", ref(2L, "Takım B", "Ağ"));
        SectionResult r = section.evaluate(ctx(), new AvailabilitySection.Rollup(byKey, Map.of(), "hourly_rollup", false), refs);

        double org = AvailabilitySection.pctOf(9999 + 9991 + 995, 21000);
        assertThat(kpi(r, "org_availability").value()).isEqualTo(org);
        assertThat(org).isGreaterThanOrEqualTo(99.9);
        assertThat(r.status()).isEqualTo(SectionResult.ATTENTION);
        assertThat(verdictCodes(r)).containsExactly("ORG_MET", "TEAMS_BELOW");
        assertThat(kpi(r, "teams_meeting").value()).isEqualTo(1);
        assertThat(kpi(r, "teams_meeting").hintParams()).containsExactly(2);
        // Hizmet = takım × grup: A/Ödeme, A/(grupsuz), B/Ağ → 2 karşılıyor
        assertThat(kpi(r, "services_meeting").value()).isEqualTo(2);
        // Önceki ay: yalnız HTTP|1 (%100) → fark = org − 100
        assertThat(kpi(r, "org_availability").delta()).isEqualTo(AvailabilitySection.round2(org - 100.0));
        assertThat(kpi(r, "org_availability").deltaTone()).isEqualTo(SectionResult.T_BAD);
        // Hata bütçesi kullanımı = (100 − org) / (100 − 99,9) × 100
        assertThat((Double) kpi(r, "budget_used").value()).isEqualTo(AvailabilitySection.round2((100 - org) / (100 - 99.9) * 100));
        // Eşdeğer kesinti = (100 − org)% × Eylül dakikası (30 gün)
        assertThat((Double) kpi(r, "downtime_equiv").value()).isEqualTo(AvailabilitySection.round1((100 - org) / 100 * 30 * 24 * 60));
        // Takımlar en kötü önce
        SectionResult.Table teams = table(r, "teams");
        assertThat(teams.rows()).extracting(m -> m.get("team")).containsExactly("Takım B", "Takım A");
        assertThat(teams.rows().get(0).get("met")).isEqualTo(SectionResult.T_BAD);
        assertThat(teams.rows().get(1).get("met")).isEqualTo(SectionResult.T_OK);
    }

    @Test
    @DisplayName("hedef karşılanmadı → KRİTİK + ORG_MISSED (puan farkı parametrede)")
    void orgMissed() {
        Map<String, long[]> byKey = Map.of("HTTP|1", v(1000, 990, 0, 0));
        SectionResult r = section.evaluate(ctx(), new AvailabilitySection.Rollup(byKey, Map.of(), "hourly_rollup", false),
                Map.of("HTTP|1", ref(1L, "Takım A", null)));
        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);
        assertThat(r.verdicts().get(0).code()).isEqualTo("ORG_MISSED");
        assertThat(r.verdicts().get(0).params().stream().map(SectionResult::raw).toList()).containsExactly(99.0, 99.9, 0.9);
        // ondalıklı parametreler biçimiyle gider (arayüz kendi dilinde yazar)
        assertThat(r.verdicts().get(0).params().get(0)).isEqualTo(SectionResult.pct(99.0));
        assertThat(r.verdicts().get(0).params().get(2)).isEqualTo(SectionResult.num(0.9));
        assertThat(kpi(r, "org_availability").delta()).isNull();   // önceki ay verisi yok → karşılaştırma yok
    }

    @Test
    @DisplayName("tam sınır: %99,9 = hedef → karşılandı (≥)")
    void exactlyOnTarget() {
        Map<String, long[]> byKey = Map.of("HTTP|1", v(1000, 999, 0, 0));
        SectionResult r = section.evaluate(ctx(), new AvailabilitySection.Rollup(byKey, Map.of(), "hourly_rollup", false),
                Map.of("HTTP|1", ref(1L, "Takım A", null)));
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(verdictCodes(r)).containsExactly("ORG_MET", "TEAMS_ALL_MET");
    }

    @Test
    @DisplayName("veri yok → VERİ YOK; silinmiş izlemenin kontrolleri sayılmaz (not düşer)")
    void noDataAndUnmapped() {
        SectionResult empty = section.evaluate(ctx(), AvailabilitySection.Rollup.empty(), Map.of());
        assertThat(empty.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(verdictCodes(empty)).containsExactly("NO_DATA");

        Map<String, long[]> byKey = Map.of("HTTP|99", v(500, 100, 0, 0), "HTTP|1", v(100, 100, 0, 0));
        SectionResult r = section.evaluate(ctx(), new AvailabilitySection.Rollup(byKey, Map.of(), "hourly_rollup", false),
                Map.of("HTTP|1", ref(1L, "Takım A", null)));
        assertThat(kpi(r, "org_availability").value()).isEqualTo(100.0);
        assertThat(r.notes()).extracting(SectionResult.Note::code).contains("UNMAPPED");
    }

    @Test
    @DisplayName("en kötü hizmetler: en az 30 kontrolü olanlar, artan sırayla")
    void worstServicesNeedMinimumChecks() {
        Map<String, long[]> byKey = new LinkedHashMap<>();
        byKey.put("HTTP|1", v(10, 0, 0, 0));      // 10 kontrol → listeye girmez
        byKey.put("HTTP|2", v(100, 90, 0, 0));
        byKey.put("HTTP|3", v(100, 99, 0, 0));
        Map<String, AvailabilitySection.MonitorRef> refs = Map.of(
                "HTTP|1", ref(1L, "Takım A", "Az"), "HTTP|2", ref(1L, "Takım A", "Kötü"), "HTTP|3", ref(2L, "Takım B", "İyi"));
        SectionResult r = section.evaluate(ctx(), new AvailabilitySection.Rollup(byKey, Map.of(), "hourly_rollup", false), refs);
        SectionResult.Table worst = table(r, "worst_services");
        assertThat(worst.rows()).extracting(m -> m.get("service")).containsExactly("Kötü", "İyi");
        assertThat(worst.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("pano satırları + envanter → eşleme: silinmiş atlanır, Durum İzleme alan adıyla envanter takımına bağlanır")
    void monitorRefsFromOverviewAndInventory() {
        MonitoringOverviewService ov = mock(MonitoringOverviewService.class);
        Map<String, Object> http = new HashMap<>(Map.of("type", "http", "id", 5L, "name", "API", "team_id", 1L,
                "team_name", "Takım A", "active", true, "deleted", false));
        http.put("group_name", "Ödeme");
        Map<String, Object> gone = new HashMap<>(Map.of("type", "ping", "id", 6L, "deleted", true, "active", false));
        Map<String, Object> dns = new HashMap<>(Map.of("type", "dns", "id", 7L, "active", true, "deleted", false));
        when(ov.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("monitors", List.of(http, gone, dns)));
        AvailabilitySection s = new AvailabilitySection(ov, mock(JdbcTemplate.class));
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30, List.of(), invMap(inv("a.example.com", 2L, 1, null)),
                Map.of(2L, "Takım B"));
        Map<String, AvailabilitySection.MonitorRef> refs = s.monitorRefs(c);
        assertThat(refs).containsOnlyKeys("HTTP|5", "UPTIME|a.example.com");
        assertThat(refs.get("HTTP|5").group()).isEqualTo("Ödeme");
        assertThat(refs.get("UPTIME|a.example.com").teamName()).isEqualTo("Takım B");
    }

    @Test
    @DisplayName("devam eden ay: geçen dakika şimdiye kadar; ay bitmemiş notu")
    void partialMonth() {
        ExecutiveSummaryContext c = ctx(SEP, Instant.parse("2026-09-11T21:00:00Z"), 99.9, 30, List.of(), Map.of(), Map.of());
        assertThat(c.complete()).isFalse();
        assertThat(c.elapsedMinutes()).isEqualTo(11L * 24 * 60);
        assertThat(c.elapsedDays()).isEqualTo(12);   // 1–12 Eylül başlamış günler
        SectionResult r = section.evaluate(c, new AvailabilitySection.Rollup(Map.of("HTTP|1", v(10, 10, 0, 0)), Map.of(),
                "hourly_rollup", false), Map.of("HTTP|1", ref(1L, "A", null)));
        assertThat(r.notes()).extracting(SectionResult.Note::code).contains("PARTIAL");
    }
}
