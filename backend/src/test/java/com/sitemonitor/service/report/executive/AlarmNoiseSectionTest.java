package com.sitemonitor.service.report.executive;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.AlertNoiseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** En gürültülü alarmlar — sayım, MTTA/MTTR, dalgalanma kuralı, sahiplenilmeyen oran, takım çözümü, ay sınırı. */
class AlarmNoiseSectionTest {

    private final AlertEventRepository repo = mock(AlertEventRepository.class);
    private final AlarmNoiseSection section = new AlarmNoiseSection(repo);

    /** [domain, type, level, created, resolvedAt, resolved, silent, acked, ackedAt, team]. */
    private static Object[] ev(String domain, String type, String level, String created, String resolvedAt,
                               boolean resolved, boolean silent, boolean acked, String ackedAt, Long team) {
        return new Object[]{ domain, type, level, created, resolvedAt, resolved, silent, acked, ackedAt, team };
    }

    @Test
    @DisplayName("sorgu İstanbul ay sınırlarıyla: [2026-08-31T21:00:00, 2026-09-30T21:00:00); önceki ay sayımı [07-31T21, 08-31T21)")
    void queryBoundaries() {
        when(repo.findExecutiveRows(anyString(), anyString())).thenReturn(List.of());
        when(repo.countCreatedBetween(anyString(), anyString())).thenReturn(0L);
        section.compute(ctx());
        verify(repo).findExecutiveRows("2026-08-31T21:00:00", "2026-09-30T21:00:00");
        verify(repo).countCreatedBetween("2026-07-31T21:00:00", "2026-08-31T21:00:00");
    }

    @Test
    @DisplayName("alarm yok → SORUNSUZ + NONE")
    void none() {
        SectionResult r = section.evaluate(ctx(), List.of(), 4L);
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(verdictCodes(r)).containsExactly("NONE");
        assertThat(kpi(r, "total_alarms").value()).isEqualTo(0);
        assertThat(kpi(r, "total_alarms").delta()).isEqualTo(-100.0);
    }

    @Test
    @DisplayName("MTTA / MTTR / sahiplenilmeyen oran: sessiz kapanış MTTR'a girmez, takım = damga yoksa envanter SY")
    void mttaMttr() {
        List<AlarmNoiseSection.Ev> evs = List.of(
                // 10 dk'da sahiplenildi, 30 dk'da kurtarıldı
                AlarmNoiseSection.Ev.of(ev("a.com", "HTTP_DOWN", "CRITICAL", "2026-09-01T10:00:00", "2026-09-01T10:30:00",
                        true, false, true, "2026-09-01T10:10:00", 1L)),
                // sahiplenilmedi, 60 dk'da kurtarıldı; damgasız → envanterden takım 2
                AlarmNoiseSection.Ev.of(ev("b.com", "PING_DOWN", "HIGH", "2026-09-02T10:00:00", "2026-09-02T11:00:00",
                        true, false, false, null, null)),
                // sessiz kapanış — MTTR'a girmez
                AlarmNoiseSection.Ev.of(ev("b.com", "PING_DOWN", "HIGH", "2026-09-03T10:00:00", "2026-09-03T10:01:00",
                        true, true, false, null, null)),
                // hâlâ açık, sahiplenilmemiş
                AlarmNoiseSection.Ev.of(ev("c.com", "PORT_DOWN", "WARNING", "2026-09-04T10:00:00", null,
                        false, false, false, null, 1L)));
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30, List.of(), invMap(inv("b.com", 2L, 1, null)),
                Map.of(1L, "Takım A", 2L, "Takım B"));
        SectionResult r = section.evaluate(c, evs, 2L);
        assertThat(kpi(r, "total_alarms").value()).isEqualTo(4);
        assertThat(kpi(r, "total_alarms").delta()).isEqualTo(100.0);            // 2 → 4
        assertThat(kpi(r, "critical").value()).isEqualTo(1);
        assertThat(kpi(r, "mtta").value()).isEqualTo(10.0);
        assertThat(kpi(r, "mttr").value()).isEqualTo(45.0);                    // (30 + 60) / 2
        assertThat(kpi(r, "unacked_pct").value()).isEqualTo(75.0);
        assertThat(kpi(r, "unacked_pct").hintParams()).containsExactly(1);    // açık ve sahiplenilmemiş
        assertThat(r.status()).isEqualTo(SectionResult.ATTENTION);              // artış ≥ %25 ve sahiplenilmeyen ≥ %50
        assertThat(verdictCodes(r)).containsExactly("TOTAL_UP", "TOP_TARGET", "UNACKED");
        SectionResult.Table teams = table(r, "top_teams");
        assertThat(teams.rows()).extracting(m -> m.get("team")).containsExactly("Takım A", "Takım B");
        assertThat(teams.rows().get(1).get("alerts")).isEqualTo(2);           // damgasız iki b.com alarmı → Takım B
        assertThat(r.notes()).extracting(SectionResult.Note::code).contains("METHOD", "SILENT");
    }

    @Test
    @DisplayName("dalgalanma: ≥ FLAP_MIN_ALERTS alarm VE ortalama kurtarma ≤ FLAP_MAX_AVG_MINUTES (Gürültü Analizi sabitleri)")
    void flapping() {
        assertThat(AlarmNoiseSection.isFlapping(AlertNoiseService.FLAP_MIN_ALERTS, (double) AlertNoiseService.FLAP_MAX_AVG_MINUTES)).isTrue();
        assertThat(AlarmNoiseSection.isFlapping(AlertNoiseService.FLAP_MIN_ALERTS - 1, 1.0)).isFalse();
        assertThat(AlarmNoiseSection.isFlapping(AlertNoiseService.FLAP_MIN_ALERTS, AlertNoiseService.FLAP_MAX_AVG_MINUTES + 0.1)).isFalse();
        assertThat(AlarmNoiseSection.isFlapping(99, null)).isFalse();

        List<AlarmNoiseSection.Ev> evs = new ArrayList<>();
        for (int i = 0; i < AlertNoiseService.FLAP_MIN_ALERTS; i++) {
            evs.add(AlarmNoiseSection.Ev.of(ev("flap.com", "HTTP_DOWN", "HIGH", "2026-09-05T10:0" + i + ":00",
                    "2026-09-05T10:0" + i + ":30", true, false, true, "2026-09-05T10:0" + i + ":10", 1L)));
        }
        evs.add(AlarmNoiseSection.Ev.of(ev("calm.com", "HTTP_DOWN", "HIGH", "2026-09-06T10:00:00", "2026-09-06T12:00:00",
                true, false, true, "2026-09-06T10:05:00", 1L)));
        SectionResult r = section.evaluate(ctx(), evs, null);
        assertThat(kpi(r, "flapping").value()).isEqualTo(1);
        assertThat(verdictCodes(r)).contains("FLAPPING", "TOTAL");   // önceki ay bilinmiyor → TOTAL
        SectionResult.Table top = table(r, "top_targets");
        assertThat(top.rows().get(0).get("target")).isEqualTo("flap.com");
        assertThat(top.rows().get(0).get("flapping")).isEqualTo(SectionResult.T_WARN);
        assertThat(top.rows().get(0).get("monitor_type")).isEqualTo("http");
        assertThat(top.rows().get(1).get("flapping")).isEqualTo(SectionResult.T_NEUTRAL);
        assertThat(table(r, "top_teams").rows().get(0).get("flapping")).isEqualTo(1);
    }

    @Test
    @DisplayName("ortanca: tek/çift eleman")
    void median() {
        assertThat(AlarmNoiseSection.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(AlarmNoiseSection.median(List.of(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5);
        assertThat(AlarmNoiseSection.median(List.of())).isNull();
    }
}
