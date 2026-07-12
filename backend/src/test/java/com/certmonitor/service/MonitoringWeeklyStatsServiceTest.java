package com.certmonitor.service;

import com.certmonitor.model.HttpMonitor;
import com.certmonitor.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** MonitoringWeeklyStatsService — oran hesabı, tür→alarm eşlemesi, top-3, delta, null kapıları. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringWeeklyStatsServiceTest {

    @Mock HttpMonitorRepository httpMonitorRepo;
    @Mock PortMonitorRepository portMonitorRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock KeywordMonitorRepository keywordMonitorRepo;
    @Mock PingMonitorRepository pingMonitorRepo;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock HttpCheckRepository httpCheckRepo;
    @Mock PortCheckRepository portCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock KeywordResultRepository keywordResultRepo;
    @Mock PingCheckRepository pingCheckRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock CertificateCheckRepository certCheckRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateService certificateService;
    @Mock WeeklyAvailabilityReportService availabilityService;

    @InjectMocks MonitoringWeeklyStatsService service;

    private final LocalDate baseMon = WeeklyAvailabilityReportService.mondayOfIsoWeek(2026, 28);
    private final String curFrom = baseMon + "T00:00:00";

    /** Object[] satırlarını güvenle sarar — List.of(new Object[]{...}) tek elemanı varargs olarak YAYAR (ClassCast tuzağı). */
    private static List<Object[]> rows(Object[]... r) { return List.of(r); }

    private HttpMonitor http(long id, String name, long teamId) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id); m.setName(name); m.setUrl("https://" + name); m.setTeamId(teamId);
        m.setActive(true); m.setCreatedAt("2020-01-01T00:00:00Z");
        return m;
    }

    private void stubWindows() {
        when(availabilityService.windowForMonday(any(LocalDate.class))).thenAnswer(i -> {
            LocalDate m = i.getArgument(0);
            return new WeeklyAvailabilityReportService.Window(m + "T00:00:00", m.plusDays(6) + "T23:59:59",
                    m.plusDays(6).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant(),
                    m.get(WeekFields.ISO.weekBasedYear()), m.get(WeekFields.ISO.weekOfWeekBasedYear()), "Hafta");
        });
        when(certificateService.getStatsForTeams(anyList())).thenReturn(Map.of("expiring_in_30_days", 0));
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyList(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
    }

    @Test
    @DisplayName("compute: http oranı = Σbaşarılı/Σtoplam; tür→alarm (ACCESSIBILITY→http, PORT_DOWN→port); top-3 en kötü; extra=ort ms")
    void compute_httpTypeAndBuckets() {
        long teamId = 5L;
        stubWindows();
        when(httpMonitorRepo.findByActiveTrue()).thenReturn(List.of(http(1, "a", teamId), http(2, "b", teamId)));
        // cur: id1 10/9 (200ms), id2 5/5 (100ms) → oran (9+5)/(10+5)=93.3; ort ms ağırlıklı=(200*10+100*5)/15=166.7
        when(httpCheckRepo.weeklyStatsByMonitor(anyList(), any(), any())).thenAnswer(i ->
                curFrom.equals(i.getArgument(1))
                        ? rows(new Object[]{1L, 10L, 9L, 200.0}, new Object[]{2L, 5L, 5L, 100.0})
                        : rows());
        // Alarm: açılan (resolved=null, since=curFrom) → ACCESSIBILITY 3 + PORT_DOWN 2; açık(false)→ACCESSIBILITY 1
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), anyList()))
                .thenAnswer(i -> {
                    Boolean resolved = i.getArgument(0);
                    String since = i.getArgument(1);
                    if (Boolean.FALSE.equals(resolved)) return rows(new Object[]{"ACCESSIBILITY", 1L});
                    if (Boolean.TRUE.equals(resolved)) return rows();
                    return curFrom.equals(since)
                            ? rows(new Object[]{"ACCESSIBILITY", 3L}, new Object[]{"PORT_DOWN", 2L})
                            : rows();  // önceki hafta
                });

        var stats = service.compute(teamId, 2026, 28);
        assertThat(stats).isNotNull();
        var http = stats.types().stream().filter(t -> t.type().equals("http")).findFirst().orElseThrow();
        assertThat(http.activeMonitors()).isEqualTo(2);
        assertThat(http.totalChecks()).isEqualTo(15);
        assertThat(http.successRate()).isEqualTo(93.3);
        assertThat(http.alarmsOpened()).isEqualTo(3);      // ACCESSIBILITY http'ye düşer
        assertThat(http.alarmsOpen()).isEqualTo(1);
        assertThat(http.extra()).isEqualTo(166.7);         // ağırlıklı ort ms
        assertThat(http.successRateDelta()).isNull();      // önceki hafta veri yok
        assertThat(http.top3()).extracting(MonitoringWeeklyStatsService.TopTarget::name).containsExactly("a"); // id1 %90 tek sorunlu

        var port = stats.types().stream().filter(t -> t.type().equals("port")).findFirst().orElseThrow();
        assertThat(port.alarmsOpened()).isEqualTo(2);      // PORT_DOWN port'a düşer, http'ye değil
        assertThat(port.successRate()).isNull();           // 0 kontrol → oran null (sıfıra bölme yok)
        assertThat(port.activeMonitors()).isZero();
    }

    @Test
    @DisplayName("compute: teamId null → null")
    void compute_nullTeam() {
        stubWindows();
        assertThat(service.compute(null, 2026, 28)).isNull();
    }

    @Test
    @DisplayName("compute: 8 haftadan eski rapor → null (backfill yok)")
    void compute_oldWeek_null() {
        stubWindows();
        LocalDate old = LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).minusWeeks(20);
        assertThat(service.compute(9L, old.get(WeekFields.ISO.weekBasedYear()),
                old.get(WeekFields.ISO.weekOfWeekBasedYear()))).isNull();
    }
}
