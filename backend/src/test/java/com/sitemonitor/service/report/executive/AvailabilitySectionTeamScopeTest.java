package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.MonitoringOverviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Erişilebilirlik — TAKIM kapsamı (2026-10-10): yalnız takımın izlemeleri (kendi takımı ya da envanter türevi + host'u
 * takımın SY/UG envanterinde), takım kodları ve metinleri, takım günlük serisi (izleme × gün), kurum çıktısı değişmez.
 */
class AvailabilitySectionTeamScopeTest {

    private final AvailabilitySection section = new AvailabilitySection(mock(MonitoringOverviewService.class), mock(JdbcTemplate.class));

    /** Envanter: sy (SY=1), ug (SY=2, UG=1), other (SY=2). */
    private static final Map<String, ExecutiveSummaryContext.InventoryRow> INV = invMap(
            inv("sy.example.com", 1L, null, 1, null),
            inv("ug.example.com", 2L, 1L, 2, null),
            inv("other.example.com", 2L, null, 2, null));

    private static long[] v(long ct, long cu, long pt, long pu) { return new long[]{ ct, cu, pt, pu }; }

    private static AvailabilitySection.MonitorRef own(String name, Long team, String teamName, String group) {
        return new AvailabilitySection.MonitorRef(name, "http", team, teamName, group, true);
    }

    /** Kurum geneli izleme toplamları + eşleme (takım 1 açısından: 5 kapsamda, 3 kapsam dışı, 1 silinmiş). */
    private static Map<String, long[]> byKey() {
        Map<String, long[]> m = new LinkedHashMap<>();
        m.put("HTTP|1", v(1000, 1000, 1000, 1000));          // takım 1 — kapsamda
        m.put("HTTP|2", v(1000, 500, 0, 0));                 // takım 2 — kapsam DIŞI
        m.put("PORT|3", v(1000, 990, 0, 0));                 // türev Port, host ug (UG=1) — kapsamda
        m.put("PORT|4", v(1000, 0, 0, 0));                   // takım 3'ün BAĞIMSIZ Port'u, aynı host — kapsam DIŞI
        m.put("UPTIME|sy.example.com", v(1000, 1000, 0, 0)); // envanter SY=1 — kapsamda
        m.put("UPTIME|ug.example.com", v(1000, 1000, 0, 0)); // envanter UG=1 — kapsamda
        m.put("UPTIME|other.example.com", v(1000, 0, 0, 0)); // envanter SY=2 — kapsam DIŞI
        m.put("HTTP|99", v(1000, 0, 0, 0));                  // silinmiş izleme — eşlemesiz
        return m;
    }

    private static Map<String, AvailabilitySection.MonitorRef> refs() {
        Map<String, AvailabilitySection.MonitorRef> r = new HashMap<>();
        r.put("HTTP|1", own("API", 1L, "Takım A", "Ödeme"));
        r.put("HTTP|2", own("B-API", 2L, "Takım B", "Ödeme"));
        r.put("PORT|3", new AvailabilitySection.MonitorRef("ug:443", "port", 2L, "Takım B", "Ağ", true, "ug.example.com", true));
        r.put("PORT|4", new AvailabilitySection.MonitorRef("c:22", "port", 3L, "Takım C", null, true, "ug.example.com", false));
        r.put("UPTIME|sy.example.com", new AvailabilitySection.MonitorRef("sy.example.com", "uptime", 1L, "Takım A", null, true, "sy.example.com", true));
        r.put("UPTIME|ug.example.com", new AvailabilitySection.MonitorRef("ug.example.com", "uptime", 2L, "Takım B", null, true, "ug.example.com", true));
        r.put("UPTIME|other.example.com", new AvailabilitySection.MonitorRef("other.example.com", "uptime", 2L, "Takım B", null, true, "other.example.com", true));
        return r;
    }

    /** İzleme × gün: her izleme 1 ve 2 Eylül'de yarı yarıya. */
    private static Map<String, Map<LocalDate, long[]>> monitorDays() {
        Map<String, Map<LocalDate, long[]>> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : byKey().entrySet()) {
            Map<LocalDate, long[]> d = new TreeMap<>();
            d.put(LocalDate.of(2026, 9, 1), new long[]{ e.getValue()[0] / 2, e.getValue()[1] / 2 });
            d.put(LocalDate.of(2026, 9, 2), new long[]{ e.getValue()[0] / 2, e.getValue()[1] / 2 });
            out.put(e.getKey(), d);
        }
        return out;
    }

    private SectionResult team(long teamId) {
        return section.evaluate(teamCtx(teamId, List.of(), INV),
                new AvailabilitySection.Rollup(byKey(), Map.of(), "hourly_rollup", false, monitorDays()), refs());
    }

    @Test
    @DisplayName("(a) yalnız takımın izlemeleri: kendi + SY/UG envanter türevi; başka takımın bağımsız Port'u ve silinmiş izleme sayılmaz")
    void onlyTeamMonitors() {
        SectionResult r = team(1);
        // Kapsamda: HTTP|1 (1000/1000) + PORT|3 (990/1000) + UPTIME sy (1000) + UPTIME ug (1000) → 3990 / 4000
        assertThat(kpi(r, "team_availability").value()).isEqualTo(AvailabilitySection.pctOf(3990, 4000));
        assertThat(kpi(r, "monitors_measured").value()).isEqualTo(4);
        // Önceki ay: yalnız HTTP|1 (%100) → fark
        assertThat(kpi(r, "team_availability").delta())
                .isEqualTo(AvailabilitySection.round2(AvailabilitySection.pctOf(3990, 4000) - 100.0));
        // Günlük seri yalnız kapsamdaki izlemelerden (1995 / 2000 her gün)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) r.data().get("daily");
        assertThat(daily).extracting(p -> p.get("checks")).containsExactly(2000L, 2000L);
        assertThat(daily).extracting(p -> p.get("availability"))
                .containsExactly(AvailabilitySection.pctOf(1995, 2000), AvailabilitySection.pctOf(1995, 2000));
        // İzleme tablosu: yalnız kapsamdakiler, en kötü önce (PORT|3 %99)
        SectionResult.Table mons = table(r, "worst_monitors");
        assertThat(mons.rows()).extracting(m -> m.get("monitor"))
                .containsExactlyInAnyOrder("ug:443", "API", "sy.example.com", "ug.example.com");
        assertThat(mons.rows().get(0).get("monitor")).isEqualTo("ug:443");
        assertThat(mons.rows().get(0).get("monitor_type")).isEqualTo("port");
        // Hizmet tablosunda başka takımın hizmeti yok (HTTP|2 'Ödeme' takım 2'ye ait)
        assertThat(table(r, "worst_services").rows()).noneMatch(m -> Long.valueOf(2L).equals(m.get("team_id"))
                && "Ödeme".equals(m.get("service")));
        // Silinmiş izleme bir takıma bağlanamaz → UNMAPPED notu takım özetinde yok
        assertThat(noteCodes(r)).doesNotContain("UNMAPPED");

        // Takım 3: yalnız kendi bağımsız Port'u (0/1000) → hedef kaçtı
        SectionResult t3 = team(3);
        assertThat(kpi(t3, "team_availability").value()).isEqualTo(0.0);
        assertThat(verdictCodes(t3)).containsExactly("TEAM_MISSED", "SERVICES_BELOW");
        assertThat(t3.status()).isEqualTo(SectionResult.CRITICAL);
    }

    @Test
    @DisplayName("(b) kurum kapsamı aynı veriyle değişmez: ORG_* hükümleri, takımlar tablosu, UNMAPPED notu, kurum günlük serisi")
    void orgUnchanged() {
        Map<LocalDate, long[]> orgDaily = Map.of(LocalDate.of(2026, 9, 1), new long[]{ 7000, 4490 });
        SectionResult org = section.evaluate(orgCtx(List.of(), INV),
                new AvailabilitySection.Rollup(byKey(), orgDaily, "hourly_rollup", false), refs());
        assertThat(org.headlineKpi()).isEqualTo("org_availability");
        assertThat(verdictCodes(org)).containsExactly("ORG_MISSED", "TEAMS_BELOW");
        assertThat(kpiCodes(org)).containsExactly("org_availability", "target", "teams_meeting", "services_meeting",
                "budget_used", "downtime_equiv", "monitors_measured");
        assertThat(tableCodes(org)).containsExactly("teams", "worst_services");
        assertThat(noteCodes(org)).contains("METHOD", "UNMAPPED").doesNotContain("TEAM_METHOD");
        assertThat(kpi(org, "org_availability").value()).isEqualTo(AvailabilitySection.pctOf(4490, 7000));
        assertThat(org.data()).containsKey("org_availability").doesNotContainKey("team_availability");

        // 7 argümanlı kurucu ile 10 argümanlı (kapsam null) aynı çıktıyı verir
        ExecutiveSummaryContext tenArg = new ExecutiveSummaryContext(SEP, NOW, 99.9, 30, List::of, () -> INV, () -> TEAMS,
                null, null, null);
        SectionResult same = section.evaluate(tenArg, new AvailabilitySection.Rollup(byKey(), orgDaily, "hourly_rollup", false), refs());
        assertThat(same).isEqualTo(org);
    }

    @Test
    @DisplayName("(c) takım kodları: TEAM_MET + hizmet hükmü, team_availability / team_target, takım tablosu yok, kurum ifadesi yok")
    void teamCodesAndWording() {
        SectionResult r = team(1);
        assertThat(r.headlineKpi()).isEqualTo("team_availability");
        assertThat(kpiCodes(r)).containsExactly("team_availability", "team_target", "services_meeting", "budget_used",
                "downtime_equiv", "monitors_measured");
        assertThat(tableCodes(r)).containsExactly("worst_services", "worst_monitors");
        assertThat(noteCodes(r)).contains("TEAM_METHOD", "SCOPE").doesNotContain("METHOD");
        // %99,75 < %99,9 → kaçtı; hizmetler: A/Ödeme %100, B/Ağ (PORT|3) %99, A/- (sy uptime) %100, B/- (ug uptime) %100
        assertThat(verdictCodes(r)).containsExactly("TEAM_MISSED", "SERVICES_BELOW");
        assertThat(r.verdicts().get(1).params()).containsExactly(4, 1);
        assertThat(r.data()).containsKey("team_availability").doesNotContainKey("org_availability")
                .doesNotContainKey("teams_measured");
        assertNoOrgWording(r);

        // Hedef karşılandı + bütün hizmetler: takım 1, PORT|3 olmadan
        Map<String, long[]> ok = byKey();
        ok.put("PORT|3", v(1000, 1000, 0, 0));
        SectionResult met = section.evaluate(teamCtx(1, List.of(), INV),
                new AvailabilitySection.Rollup(ok, Map.of(), "hourly_rollup", false, monitorDays()), refs());
        assertThat(verdictCodes(met)).containsExactly("TEAM_MET", "SERVICES_ALL_MET");
        assertThat(met.status()).isEqualTo(SectionResult.OK);
        assertNoOrgWording(met);
    }

    @Test
    @DisplayName("(c) takımın ölçülen izlemesi yok → TEAM_NO_DATA (kurumda veri olsa bile), ana gösterge team_availability")
    void teamNoData() {
        SectionResult r = section.evaluate(teamCtx(42, List.of(), INV),
                new AvailabilitySection.Rollup(byKey(), Map.of(), "hourly_rollup", false, monitorDays()), refs());
        assertThat(r.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(verdictCodes(r)).containsExactly("TEAM_NO_DATA");
        assertThat(kpiCodes(r)).containsExactly("team_availability");
        assertThat(r.headlineKpi()).isEqualTo("team_availability");
        assertNoOrgWording(r);
    }

    @Test
    @DisplayName("gün sınırı: İstanbul (UTC+3) → 21; UTC → 24; yaz saati değişen ay ya da negatif ofset → -1 (saat kovasına düşülür)")
    void dayBoundary() {
        ExecutiveSummaryContext c = ctx();
        assertThat(AvailabilitySection.dayBoundaryHour(ExecutiveSummaryContext.IST, c.from(), c.to())).isEqualTo(21);
        assertThat(AvailabilitySection.dayBoundaryHour(ZoneOffset.UTC, c.from(), c.to())).isEqualTo(24);
        Instant marFrom = Instant.parse("2026-02-28T23:00:00Z"), marTo = Instant.parse("2026-03-31T22:00:00Z");
        assertThat(AvailabilitySection.dayBoundaryHour(ZoneId.of("Europe/Berlin"), marFrom, marTo)).isEqualTo(-1);
        assertThat(AvailabilitySection.dayBoundaryHour(ZoneId.of("America/Sao_Paulo"), c.from(), c.to())).isEqualTo(-1);
        assertThat(AvailabilitySection.perMonitorDaySqlHourly(21))
                .contains("substr(hour_bucket, 12, 2) >= '21'").contains("GROUP BY monitor_type, monitor_key, substr(hour_bucket, 1, 10)");
        assertThat(AvailabilitySection.perMonitorDaySqlHourly(-1)).contains("hour_bucket AS bucket, 0 AS next_day")
                .endsWith("GROUP BY monitor_type, monitor_key, hour_bucket");
    }

    @Test
    @DisplayName("takım okuması: kurum izleme toplamı + izleme × gün (İstanbul günü: 21Z kovası ertesi gün); kurum kova sorgusu YOK")
    void teamLoadRollup() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(startsWith("SELECT monitor_type"), any(Object[].class))).thenReturn(List.of(
                new HashMap<>(Map.of("monitor_type", "HTTP", "monitor_key", "1", "cur_total", 20L, "cur_up", 19L,
                        "prev_total", 0L, "prev_up", 0L))));
        ResultSet late = rs("HTTP", "1", "2026-08-31", 1, 10, 10);    // 31 Ağustos 21–23Z → 1 Eylül
        ResultSet early = rs("HTTP", "1", "2026-09-01", 0, 10, 9);    // 1 Eylül 00–20Z → 1 Eylül
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            h.processRow(late);
            h.processRow(early);
            return null;
        }).when(jdbc).query(contains("next_day"), any(RowCallbackHandler.class), any(Object[].class));
        AvailabilitySection s = new AvailabilitySection(mock(MonitoringOverviewService.class), jdbc);

        AvailabilitySection.Rollup r = s.loadRollup(teamCtx(1, List.of(), INV));
        verify(jdbc).query(contains("substr(hour_bucket, 12, 2) >= '21'"), any(RowCallbackHandler.class),
                eq("2026-08-31T21"), eq("2026-09-30T21"));
        verify(jdbc, never()).queryForList(startsWith("SELECT hour_bucket"), any(Object[].class));
        assertThat(r.monitorDays()).containsOnlyKeys("HTTP|1");
        assertThat(r.monitorDays().get("HTTP|1")).containsOnlyKeys(LocalDate.of(2026, 9, 1));
        assertThat(r.monitorDays().get("HTTP|1").get(LocalDate.of(2026, 9, 1))).containsExactly(20L, 19L);
        assertThat(r.daily()).isEmpty();                              // kurum serisi takımda hiç kurulmaz
    }

    @Test
    @DisplayName("eşleme: Durum İzleme satırları SÜZÜLMEMİŞ envanterden — kapsam dışı alan adının kontrolleri 'silinmiş' sayılmaz")
    void refsFromUnfilteredInventory() {
        MonitoringOverviewService ov = mock(MonitoringOverviewService.class);
        Map<String, Object> port = new HashMap<>(Map.of("type", "port", "id", 3L, "name", "ug:443", "team_id", 2L,
                "active", true, "deleted", false, "standalone", false, "target", "ug.example.com"));
        when(ov.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("monitors", List.of(port)));
        AvailabilitySection s = new AvailabilitySection(ov, mock(JdbcTemplate.class));
        Map<String, AvailabilitySection.MonitorRef> refs = s.monitorRefs(teamCtx(1, List.of(), INV));
        assertThat(refs).containsOnlyKeys("PORT|3", "UPTIME|sy.example.com", "UPTIME|ug.example.com", "UPTIME|other.example.com");
        assertThat(refs.get("PORT|3").derived()).isTrue();
        assertThat(refs.get("PORT|3").host()).isEqualTo("ug.example.com");
        assertThat(AvailabilitySection.inScope(teamCtx(1, List.of(), INV), refs.get("PORT|3"))).isTrue();
        assertThat(AvailabilitySection.inScope(teamCtx(1, List.of(), INV), refs.get("UPTIME|other.example.com"))).isFalse();
        assertThat(AvailabilitySection.inScope(teamCtx(1, List.of(), INV), null)).isFalse();
    }

    @Test
    @DisplayName("koşu boyu paylaşım: iki takım bağlamı aynı haritayla → izleme toplamı, izleme × gün ve pano satırları BİR kez")
    void sharedAcrossTeams() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(startsWith("SELECT monitor_type"), any(Object[].class))).thenReturn(List.of(
                new HashMap<>(Map.of("monitor_type", "HTTP", "monitor_key", "1", "cur_total", 20L, "cur_up", 20L,
                        "prev_total", 0L, "prev_up", 0L))));
        MonitoringOverviewService ov = mock(MonitoringOverviewService.class);
        when(ov.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("monitors", List.of()));
        AvailabilitySection s = new AvailabilitySection(ov, jdbc);
        Map<String, Object> shared = new ConcurrentHashMap<>();
        s.compute(teamCtx(1, List.of(), INV, shared));
        s.compute(teamCtx(2, List.of(), INV, shared));
        verify(jdbc, times(1)).queryForList(startsWith("SELECT monitor_type"), any(Object[].class));
        verify(jdbc, times(1)).query(contains("next_day"), any(RowCallbackHandler.class), any(Object[].class));
        verify(ov, times(1)).build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean());
    }

    private static ResultSet rs(String type, String key, String bucket, int nextDay, long total, long up) throws Exception {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("monitor_type")).thenReturn(type);
        when(r.getString("monitor_key")).thenReturn(key);
        when(r.getString("bucket")).thenReturn(bucket);
        when(r.getInt("next_day")).thenReturn(nextDay);
        when(r.getLong("total")).thenReturn(total);
        when(r.getLong("up")).thenReturn(up);
        return r;
    }
}
