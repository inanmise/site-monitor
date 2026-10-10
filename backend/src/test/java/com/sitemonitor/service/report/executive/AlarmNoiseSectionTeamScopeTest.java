package com.sitemonitor.service.report.executive;

import com.sitemonitor.repository.AlertEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * En gürültülü alarmlar — TAKIM kapsamı (2026-10-10): damga = takım YA DA envanter gibi yönlenen alarm + alan adı takımın
 * SY/UG envanterinde (bildirim yönlendirmesiyle aynı yüklem; başka takımın aynı host'taki bağımsız izlemesi sayılmaz),
 * önceki ay sayısı da süzülür, ay satırları koşu boyu bir kez okunur.
 */
class AlarmNoiseSectionTeamScopeTest {

    private static final String FROM = "2026-08-31T21:00:00", TO = "2026-09-30T21:00:00", PREV = "2026-07-31T21:00:00";
    private static final String STANDALONE_C = "{\"team_id\":3,\"standalone\":true}";

    /** Envanter: sy (SY=1), ug (SY=2, UG=1), other (SY=2). */
    private static final Map<String, ExecutiveSummaryContext.InventoryRow> INV = invMap(
            inv("sy.example.com", 1L, null, 1, null),
            inv("ug.example.com", 2L, 1L, 2, null),
            inv("other.example.com", 2L, null, 2, null));

    /** [domain, type, level, created, resolvedAt, resolved, silent, acked, ackedAt, team, context]. */
    private static Object[] row(String domain, String type, String level, String created, Long team, String context) {
        return new Object[]{ domain, type, level, created, null, false, false, false, null, team, context };
    }

    /** Ayın alarmları — takım 1 açısından 5 kapsamda (✓), 4 kapsam dışı (✗). */
    private static List<Object[]> monthRows() {
        List<Object[]> r = new ArrayList<>();
        r.add(row("sy.example.com", "ACCESSIBILITY", "HIGH", "2026-09-01T10:00:00", 1L, null));          // ✓ damga
        r.add(row("ug.example.com", "ACCESSIBILITY", "HIGH", "2026-09-02T10:00:00", 2L, null));          // ✓ UG envanteri
        r.add(row("ug.example.com", "PORT_DOWN", "CRITICAL", "2026-09-03T10:00:00", 3L, STANDALONE_C));  // ✗ C'nin bağımsız Port'u
        r.add(row("ug.example.com", "PORT_DOWN", "WARNING", "2026-09-04T10:00:00", 2L, null));           // ✓ envanter türevi Port
        r.add(row("ug.example.com", "HTTP_DOWN", "HIGH", "2026-09-05T10:00:00", 3L, "{\"team_id\":3}")); // ✗ bağımsız HTTP (tür)
        r.add(row("own-http.example.com", "HTTP_DOWN", "CRITICAL", "2026-09-06T10:00:00", 1L, "{\"team_id\":1}")); // ✓ kendi HTTP
        r.add(row("other.example.com", "ACCESSIBILITY", "HIGH", "2026-09-07T10:00:00", 2L, null));       // ✗ başka takımın envanteri
        r.add(row("nowhere.example.com", "PING_DOWN", "HIGH", "2026-09-08T10:00:00", null, null));       // ✗ damgasız, envanter dışı
        r.add(row("sy.example.com", "ACCESSIBILITY", "WARNING", "2026-09-09T10:00:00", null, null));     // ✓ damgasız, SY envanteri
        return r;
    }

    private static List<Object[]> prevRows() {
        List<Object[]> r = new ArrayList<>();
        r.add(row("sy.example.com", "ACCESSIBILITY", "HIGH", "2026-08-10T10:00:00", 1L, null));          // ✓
        r.add(row("ug.example.com", "PORT_DOWN", "HIGH", "2026-08-11T10:00:00", 3L, STANDALONE_C));      // ✗
        r.add(row("ug.example.com", "ACCESSIBILITY", "HIGH", "2026-08-12T10:00:00", 2L, null));          // ✓
        return r;
    }

    private static AlertEventRepository repo() {
        AlertEventRepository repo = mock(AlertEventRepository.class);
        when(repo.findExecutiveRows(FROM, TO)).thenReturn(monthRows());
        when(repo.findExecutiveRows(PREV, FROM)).thenReturn(prevRows());
        when(repo.countCreatedBetween(anyString(), anyString())).thenReturn(3L);
        return repo;
    }

    @Test
    @DisplayName("(a) yalnız takımın alarmları: damga / SY-UG envanteri + envanter yönlenmesi; başka takımın bağımsız izlemesi ✗")
    void onlyTeamAlarms() {
        AlertEventRepository repo = repo();
        SectionResult r = new AlarmNoiseSection(repo).compute(teamCtx(1, List.of(), INV));
        assertThat(kpi(r, "total_alarms").value()).isEqualTo(5);
        assertThat(kpi(r, "critical").value()).isEqualTo(1);              // yalnız kendi HTTP'si (C'nin CRITICAL Port'u değil)
        // Önceki ay da süzülür: 3 satırın 2'si kapsamda → 2 → 5 (%150 artış); kurum sayımı ÇAĞRILMAZ
        assertThat(r.data().get("prev_total")).isEqualTo(2L);
        assertThat(kpi(r, "total_alarms").delta()).isEqualTo(150.0);
        verify(repo, never()).countCreatedBetween(anyString(), anyString());
        verify(repo).findExecutiveRows(PREV, FROM);
        SectionResult.Table top = table(r, "top_targets");
        assertThat(top.rows()).extracting(m -> m.get("target"))
                .containsOnly("sy.example.com", "ug.example.com", "own-http.example.com");
        assertThat(top.total()).isEqualTo(4);                              // sy|ACC, ug|ACC, ug|PORT_DOWN, own|HTTP_DOWN

        // Takım 3: yalnız damgalı iki bağımsız alarmı (envanteri yok)
        SectionResult t3 = new AlarmNoiseSection(repo()).compute(teamCtx(3, List.of(), INV));
        assertThat(kpi(t3, "total_alarms").value()).isEqualTo(2);
        assertThat(t3.data().get("prev_total")).isEqualTo(1L);
    }

    @Test
    @DisplayName("bağımsızlık işareti: tür listesi ya da bağlamdaki team_id / standalone (AlertOwnership ile aynı yüklem)")
    void scopePredicate() {
        ExecutiveSummaryContext c = teamCtx(1, List.of(), INV);
        assertThat(AlarmNoiseSection.inScope(c, AlarmNoiseSection.Ev.of(row("ug.example.com", "DNS_FAILURE", "HIGH",
                "2026-09-01T00:00:00", 2L, "{\"standalone\":true}")))).isFalse();
        assertThat(AlarmNoiseSection.inScope(c, AlarmNoiseSection.Ev.of(row("ug.example.com", "DNS_FAILURE", "HIGH",
                "2026-09-01T00:00:00", 2L, "{\"record_type\":\"A\"}")))).isTrue();
        assertThat(AlarmNoiseSection.inScope(c, AlarmNoiseSection.Ev.of(row("ug.example.com", "KEYWORD", "HIGH",
                "2026-09-01T00:00:00", null, null)))).isFalse();                       // tür bağımsız → damgasız sahipsiz
        assertThat(AlarmNoiseSection.inScope(c, AlarmNoiseSection.Ev.of(row("ug.example.com", "PORT_DOWN", "HIGH",
                "2026-09-01T00:00:00", 2L, "{bozuk")))).isTrue();                      // bozuk bağlam → tür listesine düşer
        assertThat(AlarmNoiseSection.inScope(orgCtx(List.of(), INV), AlarmNoiseSection.Ev.of(row("nowhere", "PING_DOWN",
                "HIGH", "2026-09-01T00:00:00", null, null)))).isTrue();                // kurum: her satır
        // 10 sütunlu (bağlamsız) eski izdüşüm de okunur
        AlarmNoiseSection.Ev legacy = AlarmNoiseSection.Ev.of(new Object[]{ "a", "X", "HIGH", "2026-09-01T00:00:00", null,
                false, false, false, null, 1L });
        assertThat(legacy.context()).isNull();
    }

    @Test
    @DisplayName("(b) kurum kapsamı değişmez: bütün satırlar, kurum TEK sayımı, 'takımlar' tablosu, kapsam notu yok")
    void orgUnchanged() {
        AlertEventRepository repo = repo();
        SectionResult r = new AlarmNoiseSection(repo).compute(orgCtx(List.of(), INV));
        assertThat(kpi(r, "total_alarms").value()).isEqualTo(9);
        assertThat(r.data().get("prev_total")).isEqualTo(3L);
        verify(repo).countCreatedBetween(PREV, FROM);
        verify(repo, never()).findExecutiveRows(PREV, FROM);
        assertThat(tableCodes(r)).containsExactly("top_targets", "top_teams");
        assertThat(noteCodes(r)).doesNotContain("TEAM_SCOPE");
    }

    @Test
    @DisplayName("(c) takım: 'takımlar' yerine türe göre kırılım + kapsam notu; kurum ifadesi yok")
    void teamCodesAndWording() {
        SectionResult r = new AlarmNoiseSection(repo()).compute(teamCtx(1, List.of(), INV));
        assertThat(r.headlineKpi()).isEqualTo("total_alarms");
        assertThat(tableCodes(r)).containsExactly("top_targets", "by_type");
        assertThat(noteCodes(r)).contains("METHOD", "TEAM_SCOPE");
        SectionResult.Table byType = table(r, "by_type");
        // http: sy ACC ×2 + ug ACC + own HTTP_DOWN = 4 (ACCESSIBILITY katalogda http); port: 1
        assertThat(byType.rows()).extracting(m -> m.get("monitor_type")).containsExactly("http", "port");
        assertThat(byType.rows().get(0)).containsEntry("alerts", 4).containsEntry("critical", 1);
        assertThat(byType.rows().get(1)).containsEntry("alerts", 1).containsEntry("unacked_pct", 100.0);
        assertNoOrgWording(r);
    }

    @Test
    @DisplayName("koşu boyu paylaşım: iki takım bağlamı aynı haritayla → ayın ve önceki ayın satırları BİR kez okunur")
    void sharedAcrossTeams() {
        AlertEventRepository repo = repo();
        AlarmNoiseSection s = new AlarmNoiseSection(repo);
        Map<String, Object> shared = new ConcurrentHashMap<>();
        s.compute(teamCtx(1, List.of(), INV, shared));
        s.compute(teamCtx(2, List.of(), INV, shared));
        verify(repo, times(1)).findExecutiveRows(FROM, TO);
        verify(repo, times(1)).findExecutiveRows(PREV, FROM);
        verify(repo, never()).countCreatedBetween(anyString(), anyString());
    }
}
