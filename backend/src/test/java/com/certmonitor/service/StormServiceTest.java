package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.AlertStorm;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.AlertStormRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StormService birim testleri — eşik matematiği (COUNT/PERCENT + round edge), evaluate karar yolları
 * (disabled / non-down / eşik-altı / attach / promote), atomik-idempotent terfi ve "N eşzamanlı arıza →
 * tam olarak BİR toplu alarm" garantisi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormServiceTest {

    @Mock AlertStormRepository stormRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;

    private StormService storm;

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo);
    }

    private AlertEvent down(long id, String type, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("host" + id + ".example.com");
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setTeamId(teamId);
        e.setResolved(false);
        return e;
    }

    private void enabledAccountWide() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(false);
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(3);
        when(appSettings.getInt(eq(StormService.KEY_WINDOW), anyInt())).thenReturn(5);
    }

    // ── Eşik matematiği ───────────────────────────────────────────────────────

    @Test
    @DisplayName("computeThreshold COUNT → max(2, value)")
    void threshold_count() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(5);
        assertThat(storm.computeThreshold()).isEqualTo(5);

        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(1);   // 1'lik storm → taban 2
        assertThat(storm.computeThreshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeThreshold PERCENT round edge — %10 × 5 monitör → ceil(0.5)=1 → max(2,1)=2")
    void threshold_percent_rounding_edge() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(10);
        stubTotalMonitors(5, 0, 0, 0, 0, 0, 0);   // toplam 5 aktif monitör
        assertThat(storm.computeThreshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeThreshold PERCENT normal — %50 × 20 monitör → 10")
    void threshold_percent_normal() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(50);
        stubTotalMonitors(10, 4, 3, 3, 0, 0, 0);   // toplam 20
        assertThat(storm.computeThreshold()).isEqualTo(10);
    }

    private void stubTotalMonitors(long inv, long http, long keyword, long ping, long domain, long port, long dns) {
        when(inventoryRepo.countByActiveTrue()).thenReturn(inv);
        when(httpRepo.countByActiveTrue()).thenReturn(http);
        when(keywordRepo.countByActiveTrue()).thenReturn(keyword);
        when(pingRepo.countByActiveTrue()).thenReturn(ping);
        when(domainRepo.countByActiveTrue()).thenReturn(domain);
        when(portRepo.countByStandaloneTrueAndActiveTrue()).thenReturn(port);
        when(dnsRepo.countByStandaloneTrueAndActiveTrue()).thenReturn(dns);
    }

    // ── evaluate karar yolları ────────────────────────────────────────────────

    @Test
    @DisplayName("Storm KAPALI → SEND_INDIVIDUAL (sıfır etkileşim, bugünkü davranış)")
    void evaluate_disabled_sendsIndividual() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(false);
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verifyNoInteractions(stormRepo);
    }

    @Test
    @DisplayName("DOWN olmayan tip (HTTP_SSL) → SEND_INDIVIDUAL")
    void evaluate_nonDownType_sendsIndividual() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_SSL, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verifyNoInteractions(stormRepo);
    }

    @Test
    @DisplayName("Eşik altı → SEND_INDIVIDUAL (sel değil)")
    void evaluate_belowThreshold_sendsIndividual() {
        enabledAccountWide();
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT")).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L)));   // 2 < eşik 3
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Aktif storm varsa → attach (SUPPRESSED, stormId damgalanır, e-posta yok)")
    void evaluate_activeStorm_attaches() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(false);
        AlertStorm active = storm(100L);
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT")).thenReturn(Optional.of(active));

        AlertEvent e = down(9, EscalationService.TYPE_PORT_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(100L);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
        verify(stormRepo).save(active);   // memberCount bump
    }

    @Test
    @DisplayName("Terfi (kazanan) → SUPPRESSED + TEK toplu alarm + stormId damgalanır")
    void evaluate_promote_winner_sendsOneAggregatedAlert() {
        enabledAccountWide();
        AlertStorm created = storm(200L);
        // 1. çağrı (aktif kontrol) empty, 2. çağrı (insert sonrası) storm
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));   // 3 >= eşik 3
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);   // biz oluşturduk (kazanan)

        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(200L);
        verify(emailService, times(1)).buildStormAlertHtml(eq(3), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Terfi çakışması (kaybeden, rows=0) → SUPPRESSED ama toplu alarm GÖNDERMEZ (idempotent)")
    void evaluate_promote_loser_noDuplicateAlert() {
        enabledAccountWide();
        AlertStorm existing = storm(201L);
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);   // başka worker kazandı

        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("N eşzamanlı arıza → tam olarak BİR toplu alarm (kalanı attach)")
    void evaluate_nFailures_exactlyOneAggregatedAlert() {
        enabledAccountWide();
        AlertStorm created = storm(300L);
        // 1. çağrının aktif-kontrolü empty; sonrası hep aktif storm (post-insert + sonraki çağrıların aktif-kontrolü)
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        for (int i = 1; i <= 5; i++) {
            AlertEvent e = down(i, EscalationService.TYPE_HTTP_DOWN, 7L);
            assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        }
        // 5 arıza → yalnız 1 toplu alarm (ilk terfi); kalan 4 attach oldu
        verify(emailService, times(1)).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    private AlertStorm storm(long id) {
        AlertStorm s = new AlertStorm();
        s.setId(id);
        s.setScopeKey("ACCOUNT");
        s.setScopeType("ACCOUNT");
        s.setResolved(false);
        s.setCreatedAt("2026-07-10T09:00:00");
        return s;
    }
}
