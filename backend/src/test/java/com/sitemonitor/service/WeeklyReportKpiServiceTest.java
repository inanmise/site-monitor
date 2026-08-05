package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** WeeklyReportKpiService — pencere/delta/trend/boş-takım + uptime delege doğrulaması (canlı repo/servis mock'lu). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportKpiServiceTest {

    @Mock CertificateService certificateService;
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock WeeklyAvailabilityReportService availabilityService;
    @Mock WeeklyScoreCalculator scoreCalculator;
    @Mock com.sitemonitor.repository.TeamRepository teamRepo;

    @InjectMocks WeeklyReportKpiService service;

    private LatestCheck lc(String domain, String notAfter, String notBefore) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setNotAfter(notAfter); c.setNotBefore(notBefore);
        return c;
    }
    private CertificateInventory inv(String domain, String domainExpiry) {
        CertificateInventory ci = new CertificateInventory();
        ci.setDomain(domain); ci.setDomainExpiry(domainExpiry);
        return ci;
    }
    private String utc(LocalDate d, int plusDays) {
        return d.plusDays(plusDays).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    @Test
    @DisplayName("compute: windowed delta (expiring cur>prev), snapshot kartlar, uptime delege, 8-hafta trend")
    void compute_fullAggregation() {
        long teamId = 5L;
        LocalDate baseMon = WeeklyAvailabilityReportService.mondayOfIsoWeek(2026, 28);
        LocalDate prevMon = baseMon.minusWeeks(1);

        // Envanter: biri registrar bitişi ≤7 gün (kritik domain), biri değil.
        CertificateInventory ciCrit = inv("crit.com", LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).plusDays(3).toString());
        CertificateInventory ciOk   = inv("ok.com", LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).plusDays(120).toString());
        when(inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(teamId)))
                .thenReturn(List.of(ciCrit, ciOk));

        // Cert bitişleri: 2 cert BU hafta dolar, 1 cert ÖNCEKİ hafta → expiring delta cur=2, prev=1.
        when(latestCheckRepo.findByDomainIn(anyList())).thenReturn(List.of(
                lc("a.com", utc(baseMon, 2), utc(baseMon, 1)),   // bu hafta dolan + yenilenen
                lc("b.com", utc(baseMon, 3), null),               // bu hafta dolan
                lc("c.com", utc(prevMon, 2), null)));             // önceki hafta dolan

        when(certificateService.getStatsForTeams(List.of(teamId)))
                .thenReturn(Map.of("total_certificates", 10, "expiring_in_7_days", 2));

        // windowForMonday: Monday'den deterministik Window (from/to yalnız alarm sorgusunu etkiler).
        when(availabilityService.windowForMonday(any(LocalDate.class))).thenAnswer(i -> {
            LocalDate m = i.getArgument(0);
            return new WeeklyAvailabilityReportService.Window(
                    m + "T00:00:00", m.plusDays(6) + "T23:59:59",
                    m.plusDays(6).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant(),
                    m.get(WeekFields.ISO.weekBasedYear()), m.get(WeekFields.ISO.weekOfWeekBasedYear()),
                    "Hafta " + m.get(WeekFields.ISO.weekOfWeekBasedYear()));
        });
        // Alarm: her çağrıda 3+2=5.
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), anyList()))
                .thenReturn(List.of(new Object[]{"EXPIRY", 3L}, new Object[]{"PING_DOWN", 2L}));
        // Uptime: mevcut haftada %99.5.
        when(availabilityService.weeklyUptime(eq(teamId), any()))
                .thenReturn(new EmailNotificationService.AvailabilitySummary(2, 2, 99.5, "a", 100.0, "b", 99.0, 0, null));
        // Özet yolu (buildSummary): açık-alarm domain sorgusu — boş sayfa (NPE önleme).
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyList(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        WeeklyReportKpiService.WeeklyReportKpis k = service.compute(teamId, 2026, 28);

        // Snapshot kartlar
        assertThat(k.current().totalCerts()).isEqualTo(10);
        assertThat(k.current().criticalCerts()).isEqualTo(2);
        assertThat(k.current().criticalDomains()).isEqualTo(1);
        // Windowed + delta
        assertThat(k.current().expiringInWindow()).isEqualTo(2);
        assertThat(k.previous().expiringInWindow()).isEqualTo(1);
        assertThat(k.current().renewedInWindow()).isEqualTo(1);   // a.com notBefore bu hafta
        assertThat(k.current().alarmsOpened()).isEqualTo(5);
        assertThat(k.current().uptimePct()).isEqualTo(99.5);
        // 8 haftalık trend
        assertThat(k.trend8w()).hasSize(8);
        assertThat(k.trend8w().get(7).week()).isEqualTo(k.current().weekLabel() == null ? 0
                : baseMon.get(WeekFields.ISO.weekOfWeekBasedYear()));  // son eleman = bu hafta
    }

    @Test
    @DisplayName("compute: teamId null → tüm KPI sıfır, trend boş, NPE yok")
    void compute_nullTeam_zeros() {
        when(availabilityService.windowForMonday(any(LocalDate.class))).thenAnswer(i -> {
            LocalDate m = i.getArgument(0);
            return new WeeklyAvailabilityReportService.Window(m + "T00:00:00", m + "T23:59:59",
                    m.atStartOfDay(ZoneOffset.UTC).toInstant(), 2026, 28, "Hafta 28");
        });

        WeeklyReportKpiService.WeeklyReportKpis k = service.compute(null, 2026, 28);

        assertThat(k.current().totalCerts()).isZero();
        assertThat(k.current().alarmsOpened()).isZero();
        assertThat(k.current().uptimePct()).isNull();
        assertThat(k.trend8w()).isEmpty();
        assertThat(k.summary()).isNull();   // teamId null → özet yok
    }

    @Test
    @DisplayName("summary: aksiyonlar tier ASC → gün ASC; açık-alarm domaini işaretli; type=cert; paragraf tr+en")
    void compute_summary_actionsOrdered() {
        long teamId = 9L;
        when(inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(teamId)))
                .thenReturn(List.of(inv("d.com", null)));
        when(latestCheckRepo.findByDomainIn(anyList())).thenReturn(List.of());
        when(latestCheckRepo.findWeakAlgorithmCandidates()).thenReturn(List.of());
        when(certificateService.getStatsForTeams(List.of(teamId)))
                .thenReturn(Map.of("total_certificates", 3, "expiring_in_7_days", 0));
        stubWindow();
        // tier2/5g, tier1/40g, tier1/10g → beklenen sıra: tier1/10, tier1/40, tier2/5
        when(certificateService.getAllLatestForTeams(List.of(teamId))).thenReturn(List.of(
                certDto("t2.com", 2, 5), certDto("t1b.com", 1, 40), certDto("t1a.com", 1, 10)));
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyList(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(alertOn("t1a.com"))));

        WeeklyReportKpiService.WeeklyReportKpis k = service.compute(teamId, 2026, 28);

        assertThat(k.summary()).isNotNull();
        assertThat(k.summary().actions()).extracting(WeeklyReportKpiService.ActionItem::name)
                .containsExactly("t1a.com", "t1b.com", "t2.com");
        assertThat(k.summary().actions().get(0).hasOpenAlarm()).isTrue();
        assertThat(k.summary().actions().get(0).type()).isEqualTo("cert");
        assertThat(k.summary().managerText()).containsKeys("tr", "en");
        assertThat(k.summary().score()).isNotNull();
    }

    @Test
    @DisplayName("summary: 8 haftadan eski rapor → null (frontend gizler, backfill yok)")
    void compute_oldWeek_summaryNull() {
        long teamId = 9L;
        when(inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(teamId)))
                .thenReturn(List.of(inv("d.com", null)));
        when(latestCheckRepo.findByDomainIn(anyList())).thenReturn(List.of());
        when(certificateService.getStatsForTeams(List.of(teamId))).thenReturn(Map.of());
        stubWindow();
        LocalDate old = LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).minusWeeks(20);
        int y = old.get(WeekFields.ISO.weekBasedYear());
        int wk = old.get(WeekFields.ISO.weekOfWeekBasedYear());

        WeeklyReportKpiService.WeeklyReportKpis k = service.compute(teamId, y, wk);
        assertThat(k.summary()).isNull();
    }

    private void stubWindow() {
        when(availabilityService.windowForMonday(any(LocalDate.class))).thenAnswer(i -> {
            LocalDate m = i.getArgument(0);
            return new WeeklyAvailabilityReportService.Window(
                    m + "T00:00:00", m.plusDays(6) + "T23:59:59",
                    m.plusDays(6).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant(),
                    m.get(WeekFields.ISO.weekBasedYear()), m.get(WeekFields.ISO.weekOfWeekBasedYear()),
                    "Hafta " + m.get(WeekFields.ISO.weekOfWeekBasedYear()));
        });
    }
    private com.sitemonitor.dto.CertificateDto certDto(String domain, Integer tier, Integer days) {
        com.sitemonitor.dto.CertificateDto d = new com.sitemonitor.dto.CertificateDto();
        d.setDomain(domain); d.setTier(tier); d.setDaysRemaining(days);
        return d;
    }
    private com.sitemonitor.model.AlertEvent alertOn(String domain) {
        com.sitemonitor.model.AlertEvent e = new com.sitemonitor.model.AlertEvent();
        e.setDomain(domain);
        return e;
    }
}
