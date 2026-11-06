package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TodayPanelSnapshotRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.InboxService;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.OpenAlertsSummaryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.TodayMonitorInsightsService;
import com.sitemonitor.service.TodayPanelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Performans 2026-11-06 (PF2 #1 / #5) — istek yolu bellekleri kapsam kurallarını BOZMAZ:
 * <ul>
 *   <li>{@code /api/me/today}: sonuç {@link TodayPanelController#monitoringScopeKey} anahtarıyla paylaşılır — anahtar
 *       {@code SessionScope.canViewMonitoring} yüklemini TAM belirler (7/24 operatörü ve global görüntüleyici {@code ALL},
 *       paylaşım alan kişi görüş + paylaşım takımları); aynı kapsam tek hesap, farklı paylaşım farklı anahtar.</li>
 *   <li>{@code /api/me/inbox}: kurum geneli ham veri paylaşılır, süzgeç {@code SessionScope.canView} — 7/24 operatörlüğü ve
 *       erişim paylaşımı bildirim kutusunu GENİŞLETMEZ (eski kural), önce geniş kapsam doldursa da.</li>
 * </ul>
 */
class TodayPanelMemoTest {

    CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    AlertEventRepository alertRepo = mock(AlertEventRepository.class);
    TeamRepository teamRepo = mock(TeamRepository.class);
    PermissionService perms = mock(PermissionService.class);
    TodayPanelService today;
    InboxService inbox;
    TodayPanelController controller;

    private static CertificateInventory inv(String domain, long team) {
        CertificateInventory i = new CertificateInventory(); i.setDomain(domain); i.setTeamId(team); i.setActive(true); return i;
    }

    private static AlertEvent open(long id, String domain, long team) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setTeamId(team); e.setAlertLevel("HIGH");
        e.setAlertType("HTTP_DOWN"); e.setCreatedAt("2026-11-05T10:00:00"); e.setResolved(false);
        return e;
    }

    @BeforeEach
    void setUp() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.example.com", 1L), inv("b.example.com", 2L)));
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.<Object[]>of(
                new Object[]{"a.example.com", 1L, null}, new Object[]{"b.example.com", 2L, null}));
        when(alertRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open(1, "a.example.com", 1L), open(2, "b.example.com", 2L)));
        when(alertRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of());
        Team t1 = new Team(); t1.setId(1L); t1.setName("A");
        Team t2 = new Team(); t2.setId(2L); t2.setName("B");
        when(teamRepo.findAll()).thenReturn(List.of(t1, t2));
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString())).thenReturn(true);

        today = new TodayPanelService(inventoryRepo, mock(LatestCheckRepository.class), alertRepo, mock(WeeklyReportRepository.class),
                teamRepo, mock(TodayMonitorInsightsService.class), mock(TodayPanelSnapshotRepository.class));
        ReflectionTestUtils.setField(today, "cacheMs", 30_000L);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        inbox = new InboxService(alertRepo, inventoryRepo, mock(MaintenanceWindowRepository.class), mock(MaintenanceService.class),
                mock(WeakAlgorithmExceptionRepository.class), mock(WeeklyReportRepository.class), settings);
        ReflectionTestUtils.setField(inbox, "teamRepo", teamRepo);
        ReflectionTestUtils.setField(inbox, "cacheMs", 15_000L);
        controller = new TodayPanelController(today, inbox, perms, mock(OpenAlertsSummaryService.class));
    }

    private static MockHttpSession session(String role, List<Long> view, List<Long> grants, boolean noc) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        s.setAttribute("username", "u-" + role);
        if (view != null) s.setAttribute("viewTeamIds", view);
        if (grants != null) s.setAttribute(SessionScope.ATTR_GRANT_VIEW, grants);
        if (noc) { s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE); s.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, List.of(9L)); }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> todayAlertIds(Map<String, Object> body) {
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        Map<String, Object> alerts = (Map<String, Object>) data.get("alerts");
        return ((List<Map<String, Object>>) alerts.get("items")).stream().map(m -> m.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> inboxKeys(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("data")).stream().map(m -> m.get("key")).toList();
    }

    @Test
    @DisplayName("monitoringScopeKey: global görüntüleyici ve 7/24 operatörü ALL; kapsamlı = görüş ∪ paylaşım (sıralı, tekil); görüş listesi yokken paylaşım yine anahtarda")
    void scopeKeyDeterminesThePredicate() {
        assertThat(TodayPanelController.monitoringScopeKey(session("ADMIN", null, null, false))).isEqualTo("ALL");
        assertThat(TodayPanelController.monitoringScopeKey(session("USER", List.of(1L), null, true))).isEqualTo("ALL");
        assertThat(TodayPanelController.monitoringScopeKey(session("USER", List.of(14L, 3L), List.of(5L, 3L), false))).isEqualTo("T:3,5,14");
        assertThat(TodayPanelController.monitoringScopeKey(session("USER", List.of(1L), null, false))).isEqualTo("T:1");
        assertThat(TodayPanelController.monitoringScopeKey(session("USER", null, List.of(5L), false))).isEqualTo("T:5");
        assertThat(TodayPanelController.monitoringScopeKey(session("USER", null, null, false))).isEqualTo("T:");
        // Kapsamlı müdür (ADMIN + görüş listesi) global DEĞİL
        assertThat(TodayPanelController.monitoringScopeKey(session("ADMIN", List.of(7L), null, false))).isEqualTo("T:7");
    }

    @Test
    @DisplayName("/me/today: aynı kapsam tek hesap; paylaşım alan kişi ayrı anahtar ve paylaşan takımın alarmını görür; 7/24 operatörü kurum geneli; sızıntı yok")
    void todayMemoKeepsScopes() {
        Map<String, Object> u1 = controller.today(false, session("USER", List.of(1L), null, false)).getBody();
        Map<String, Object> u1b = controller.today(false, session("USER", List.of(1L), null, false)).getBody();
        assertThat(todayAlertIds(u1)).containsExactly(1L);
        assertThat(todayAlertIds(u1b)).containsExactly(1L);
        verify(alertRepo, times(1)).findAllOpenOrderBySeverity();   // aynı kapsam → tek hesap

        Map<String, Object> grant = controller.today(false, session("USER", List.of(1L), List.of(2L), false)).getBody();
        assertThat(todayAlertIds(grant)).containsExactlyInAnyOrder(1L, 2L);
        Map<String, Object> noc = controller.today(false, session("USER", List.of(9L), null, true)).getBody();
        assertThat(todayAlertIds(noc)).containsExactlyInAnyOrder(1L, 2L);
        verify(alertRepo, times(3)).findAllOpenOrderBySeverity();

        // Önce geniş kapsamlar dolsa da dar kapsam yalnız kendisininkini görür
        Map<String, Object> u2 = controller.today(false, session("USER", List.of(2L), null, false)).getBody();
        assertThat(todayAlertIds(u2)).containsExactly(2L);
        // full=true ayrı anahtar (tavan farklı)
        controller.today(true, session("USER", List.of(1L), null, false));
        verify(alertRepo, times(5)).findAllOpenOrderBySeverity();
    }

    @Test
    @DisplayName("/me/inbox: kurum verisi paylaşılır ama 7/24 operatörlüğü ve paylaşım bildirim kutusunu GENİŞLETMEZ (canView kuralı aynı)")
    void inboxMemoKeepsPersonalScope() {
        assertThat(inboxKeys(controller.inbox(session("ADMIN", null, null, false), "current", 0, 25).getBody()))
                .contains("alert:1", "alert:2");
        assertThat(inboxKeys(controller.inbox(session("USER", List.of(1L), null, true), "current", 0, 25).getBody()))
                .contains("alert:1").doesNotContain("alert:2");   // 7/24 operatörü: bildirim kutusu kişisel
        assertThat(inboxKeys(controller.inbox(session("USER", List.of(1L), List.of(2L), false), "current", 0, 25).getBody()))
                .contains("alert:1").doesNotContain("alert:2");   // paylaşım: bildirim kutusu genişlemez
        assertThat(inboxKeys(controller.inbox(session("USER", List.of(2L), null, false), "current", 0, 25).getBody()))
                .contains("alert:2").doesNotContain("alert:1");
        verify(alertRepo, times(1)).findAllOpenOrderBySeverity();   // dört kullanıcı, tek kurum okuması
    }
}
