package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummaryDeliveryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySamples;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySettings;
import com.sitemonitor.service.report.executive.ExecutiveSummaryTeamService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Takım özeti kapıları (2026-10-10, kullanıcı kararı: takım müdürü kendi takımlarını görür ve yönettiği takımları
 * yapılandırır; global yönetici hepsini; AUDIT yalnız okur; USER / TEAM_ADMIN hiç):
 * kapsam seçimi, kapsam dışı takım 403, bilinmeyen takım 404, kurum özeti müdüre kapalı, takım ayarı / testi / elle
 * gönderimi yönetim kapsamıyla, kullanıcı kimlikleri gövdeden çözülür, denetim kaydı.
 */
class ExecutiveSummaryControllerTeamTest {

    private final ExecutiveSummaryService service = mock(ExecutiveSummaryService.class);
    private final ExecutiveSummaryDeliveryService delivery = mock(ExecutiveSummaryDeliveryService.class);
    private final ExecutiveSummarySettings settings = mock(ExecutiveSummarySettings.class);
    private final ExecutiveSummaryTeamService teamService = mock(ExecutiveSummaryTeamService.class);
    private final PermissionService perms = mock(PermissionService.class);
    private final AuditService audit = mock(AuditService.class);
    private ExecutiveSummaryController c;

    @BeforeEach
    void setUp() {
        c = new ExecutiveSummaryController(service, delivery, settings, perms, audit);
        c.setTeamService(teamService);
        when(service.parseMonth(any())).thenReturn(YearMonth.of(2026, 9));
        when(service.defaultMonth()).thenReturn(YearMonth.of(2026, 9));
        when(service.get(any(), anyBoolean(), anyBoolean())).thenReturn(ExecutiveSummarySamples.quiet());
        when(service.get(any(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(ExecutiveSummarySamples.quiet());
        when(service.months()).thenReturn(List.of());
        when(service.months(anyLong())).thenReturn(List.of());
        Map<Long, String> names = new LinkedHashMap<>();
        names.put(1L, "Zeta");
        names.put(2L, "Alfa");
        names.put(3L, "Beta");
        when(service.teamNames()).thenReturn(names);
        when(teamService.teamName(anyLong())).thenAnswer(i -> names.get(i.<Long>getArgument(0)));
        when(teamService.detail(anyLong())).thenAnswer(i -> new LinkedHashMap<>(Map.of("team_id", i.getArgument(0))));
        when(teamService.settingsOf(anyLong())).thenAnswer(i -> new ExecutiveSummaryTeamService.Settings(
                i.<Long>getArgument(0), true, true, true, List.of(), List.of(), null, null));
        when(delivery.nextRuns()).thenReturn(List.of());
    }

    /** Kapsamlı müdür: rol ADMIN + görüş kapsamı 1, 2; yönetim kapsamı yalnız 2. */
    private static MockHttpSession scoped() {
        MockHttpSession s = session("ADMIN", List.of(1L, 2L));
        s.setAttribute("manageTeamIds", List.of(2L));
        return s;
    }

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "kullanici");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    @Test
    @DisplayName("müdür: parametresiz → kapsamındaki İLK takım (A→Z); kapsam listesi yalnız kendi takımları, kurum yok")
    void scopedDefaultsToFirstTeam() {
        ResponseEntity<Map<String, Object>> r = c.summary(null, null, null, null, scoped());
        verify(service).get(YearMonth.of(2026, 9), 2L, false, false);         // Alfa (2) < Zeta (1)
        @SuppressWarnings("unchecked")
        Map<String, Object> scopes = (Map<String, Object>) r.getBody().get("scopes");
        assertThat(scopes).containsEntry("org", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> teams = (List<Map<String, Object>>) scopes.get("teams");
        assertThat(teams).extracting(t -> t.get("id")).containsExactly(2L, 1L);
        assertThat(teams).extracting(t -> t.get("can_configure")).containsExactly(true, false);
        assertThat(r.getBody()).containsEntry("can_configure", false).containsEntry("can_configure_team", true)
                .containsEntry("can_configure_any_team", true);
        verify(perms).require(any(HttpSession.class), eq("executive_summary.view"), eq("view"));
    }

    @Test
    @DisplayName("müdür: kapsam dışı takım 403, bilinmeyen takım 404, kurum özeti (team=org) 403, bozuk kimlik 400; PDF aynı kapı")
    void scopedBoundaries() {
        MockHttpSession s = scoped();
        assertThatThrownBy(() -> c.summary(null, "3", null, null, s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.summary(null, "99", null, null, s)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> c.summary(null, "org", null, null, s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.summary(null, "1;DROP", null, null, s)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> c.pdf(null, "3", null, s)).isInstanceOf(SecurityException.class);
        verify(service, never()).get(any(), anyBoolean(), anyBoolean());
        c.summary(null, "1", "1", "1", s);
        verify(service).get(YearMonth.of(2026, 9), 1L, true, true);
    }

    @Test
    @DisplayName("global görüntüleyici (AUDIT): parametresiz kurum; her takım seçilebilir; hiçbir takımı yapılandıramaz")
    void auditViewsAllConfiguresNone() {
        MockHttpSession s = session("AUDIT", null);
        ResponseEntity<Map<String, Object>> r = c.summary(null, null, null, null, s);
        verify(service).get(YearMonth.of(2026, 9), false, false);              // kurum yolu eski imzayla
        @SuppressWarnings("unchecked")
        Map<String, Object> scopes = (Map<String, Object>) r.getBody().get("scopes");
        assertThat(scopes).containsEntry("org", true);
        assertThat(r.getBody()).containsEntry("can_configure_any_team", false);
        c.summary(null, "3", null, null, s);
        verify(service).get(YearMonth.of(2026, 9), 3L, false, false);
        assertThatThrownBy(() -> c.teams(s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.team("3", s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.saveTeam("3", Map.of("enabled", true), s)).isInstanceOf(SecurityException.class);
        verify(teamService, never()).save(anyLong(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("USER ve TEAM_ADMIN izin taşısa da takım özetini göremez")
    void usersDenied() {
        for (MockHttpSession s : List.of(session("USER", List.of(1L)), session("TEAM_ADMIN", List.of(1L)))) {
            assertThatThrownBy(() -> c.summary(null, "1", null, null, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> c.teams(s)).isInstanceOf(SecurityException.class);
        }
        verify(service, never()).get(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("takım ayarları: müdür yalnız YÖNETTİĞİ takımı okur/yazar; liste yalnız yönetim kapsamı; global yönetici hepsi")
    void teamSettingsScope() {
        MockHttpSession s = scoped();
        c.teams(s);
        verify(teamService).overview(eq(List.of(2L)), eq(YearMonth.of(2026, 9)));
        assertThat(c.team("2", s).getBody()).containsEntry("success", true);
        assertThatThrownBy(() -> c.team("1", s)).isInstanceOf(SecurityException.class);       // görür ama yönetmez
        assertThatThrownBy(() -> c.team("99", s)).isInstanceOf(NoSuchElementException.class);

        MockHttpSession g = session("ADMIN", null);
        c.teams(g);
        verify(teamService).overview(isNull(), eq(YearMonth.of(2026, 9)));
        assertThat(c.team("1", g).getBody()).containsEntry("success", true);
    }

    @Test
    @DisplayName("takım ayarı kaydı: user_ids gövdeden çözülür (çözülemeyen sayılır), denetim EXECUTIVE_SUMMARY_TEAM_SETTINGS")
    @SuppressWarnings("unchecked")
    void saveTeam() {
        MockHttpSession g = session("ADMIN", null);
        when(teamService.save(eq(2L), any(), any(), anyInt(), eq("kullanici"))).thenReturn(Set.of("user_ids"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("user_ids", List.of(11, "12", "bilinmeyen-opak"));
        assertThat(c.saveTeam("2", body, g).getBody()).containsEntry("success", true);
        org.mockito.ArgumentCaptor<List<Long>> ids = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(teamService).save(eq(2L), eq(body), ids.capture(), eq(1), eq("kullanici"));
        assertThat(ids.getValue()).containsExactly(11L, 12L);
        verify(audit).recordAction(eq("EXECUTIVE_SUMMARY_TEAM_SETTINGS"), any(HttpSession.class), eq("REPORT"),
                eq("executive-summary"), anyString(), isNull());

        assertThatThrownBy(() -> c.saveTeam("2", Map.of("user_ids", "12"), g)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("takım testi yalnız isteyenin adresine, takım özetiyle; elle gönderim takım yoluyla ve team ayrıntısıyla denetlenir")
    void teamTestAndRun() {
        MockHttpSession s = scoped();
        when(delivery.emailOf("kullanici")).thenReturn("ben@example.com");
        when(delivery.allowTest("kullanici")).thenReturn(true);
        when(delivery.sendTeamTest(eq(2L), any(), eq("ben@example.com")))
                .thenReturn(new ExecutiveSummaryDeliveryService.TestResult(true, "SENT", "ben@example.com"));
        assertThat(c.sendTeamTest("2", Map.of("email", "baskasi@example.com"), s).getBody()).containsEntry("success", true);
        verify(delivery, never()).sendTest(any(), anyString());

        when(delivery.sendTeamNow(eq(2L), any(), eq("kullanici")))
                .thenReturn(new ExecutiveSummaryDeliveryService.Result("SENT", "2026-09", 2, 1, "SENT ×2"));
        assertThat(c.runTeam("2", Map.of(), s).getBody()).containsEntry("code", "SENT");
        when(delivery.sendTeamNow(eq(2L), any(), eq("kullanici")))
                .thenReturn(new ExecutiveSummaryDeliveryService.Result("NO_RECIPIENT", "2026-09", 0, 0, null));
        assertThat(c.runTeam("2", Map.of(), s).getBody()).containsEntry("success", false).containsKey("error");
        assertThatThrownBy(() -> c.runTeam("1", Map.of(), s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.sendTeamTest("1", Map.of(), s)).isInstanceOf(SecurityException.class);
        // kurum gönderimi / ayarı müdüre kapalı kalır
        assertThatThrownBy(() -> c.run(Map.of(), s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.getSettings(s)).isInstanceOf(SecurityException.class);
        verify(delivery, never()).sendNow(any(), any());
    }

    @Test
    @DisplayName("takım PDF'i: dosya adı takım adlı, denetim team ayrıntılı")
    void teamPdf() {
        ExecutiveSummary base = ExecutiveSummarySamples.quiet();
        ExecutiveSummary team = new ExecutiveSummary(base.month(), base.monthLabel(), base.from(), base.to(), base.complete(),
                base.generatedAt(), base.source(), base.status(), base.headline(), base.headlineKpis(), base.sections(),
                base.settings(), ExecutiveSummary.Scope.team(2L, "Alfa Ödeme"));
        when(service.get(any(), eq(2L), anyBoolean(), anyBoolean())).thenReturn(team);
        ResponseEntity<?> r = c.pdf(null, "2", null, scoped());
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("site-monitor-yonetici-ozeti-alfa-odeme-");
        verify(audit).recordAction(eq("EXECUTIVE_SUMMARY_EXPORT"), any(HttpSession.class), eq("REPORT"),
                eq("executive-summary"), contains("\"team\""), isNull());
    }
}
