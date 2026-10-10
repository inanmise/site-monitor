package com.sitemonitor.controller;

import com.sitemonitor.config.AuthInterceptor;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummaryDeliveryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySamples;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySettings;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Yönetici özeti uçlarının kapıları: okuma = kurum geneli görüntüleyici (global yönetici / AUDIT) + matris izni; kapsamlı
 * müdür ve sıradan kullanıcı ASLA; ayar / test / elle gönderim yalnız global yönetici; test postası yalnız isteyenin kendi
 * adresine (gövdedeki adres yok sayılır), sınır aşılınca 429; PDF uç denetlenir; oturumsuz erişim yok (PUBLIC değil).
 */
class ExecutiveSummaryControllerTest {

    private final ExecutiveSummaryService service = mock(ExecutiveSummaryService.class);
    private final ExecutiveSummaryDeliveryService delivery = mock(ExecutiveSummaryDeliveryService.class);
    private final ExecutiveSummarySettings settings = mock(ExecutiveSummarySettings.class);
    private final PermissionService perms = mock(PermissionService.class);
    private final AuditService audit = mock(AuditService.class);
    private ExecutiveSummaryController c;

    @BeforeEach
    void setUp() {
        c = new ExecutiveSummaryController(service, delivery, settings, perms, audit);
        when(service.parseMonth(any())).thenReturn(YearMonth.of(2026, 9));
        when(service.defaultMonth()).thenReturn(YearMonth.of(2026, 9));
        when(service.get(any(), anyBoolean(), anyBoolean())).thenReturn(ExecutiveSummarySamples.quiet());
        when(service.months()).thenReturn(List.of());
        when(delivery.status()).thenReturn(Map.of("enabled", false));
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
    @DisplayName("uç /api/executive-summary; oturumsuz erişim yok (AuthInterceptor PUBLIC listesinde değil)")
    @SuppressWarnings("unchecked")
    void mappingNotPublic() throws Exception {
        assertThat(ExecutiveSummaryController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/executive-summary");
        Field f = AuthInterceptor.class.getDeclaredField("PUBLIC");
        f.setAccessible(true);
        Set<String> pub = (Set<String>) f.get(null);
        assertThat(pub).isNotEmpty().noneMatch(p -> p.contains("executive"));
    }

    @Test
    @DisplayName("USER ve kapsamlı müdür (ADMIN + takım kapsamı) izin taşısa da okuyamaz — kurum geneli rapor")
    void scopedUsersDenied() {
        for (MockHttpSession s : List.of(session("USER", List.of(1L)), session("TEAM_ADMIN", List.of(1L)),
                session("ADMIN", List.of(1L, 2L)))) {
            assertThatThrownBy(() -> c.summary(null, null, null, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> c.pdf(null, null, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> c.getSettings(s)).isInstanceOf(SecurityException.class);
        }
        verifyNoInteractions(service, delivery);
    }

    @Test
    @DisplayName("AUDIT: okur (can_configure=false), ayarlara / teste / gönderime erişemez")
    void auditReadsOnly() {
        MockHttpSession s = session("AUDIT", null);
        ResponseEntity<Map<String, Object>> r = c.summary("2026-09", null, null, s);
        assertThat(r.getBody()).containsEntry("success", true).containsEntry("can_configure", false);
        verify(perms).require(any(HttpSession.class), eq("executive_summary.view"), eq("view"));
        assertThatThrownBy(() -> c.getSettings(s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.saveSettings(Map.of("enabled", true), s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.sendTest(Map.of(), s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.run(Map.of(), s)).isInstanceOf(SecurityException.class);
        verify(settings, never()).save(any(), any());
    }

    @Test
    @DisplayName("matris izni geri alınırsa global yönetici de okuyamaz")
    void permissionRevoked() {
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: executive_summary.view/view"))
                .when(perms).require(any(HttpSession.class), eq("executive_summary.view"), eq("view"));
        assertThatThrownBy(() -> c.summary(null, null, null, session("ADMIN", null))).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("global yönetici: özet + can_configure; live/fresh bayrakları servise geçer")
    void globalAdmin() {
        ResponseEntity<Map<String, Object>> r = c.summary("2026-09", "1", "true", session("ADMIN", null));
        assertThat(r.getBody()).containsEntry("can_configure", true).containsEntry("default_month", "2026-09");
        assertThat(r.getBody().get("data")).isInstanceOf(ExecutiveSummary.class);
        verify(service).get(YearMonth.of(2026, 9), true, true);
    }

    @Test
    @DisplayName("PDF: application/pdf + ek adı + no-store; denetim EXECUTIVE_SUMMARY_EXPORT")
    void pdf() {
        ResponseEntity<?> r = c.pdf("2026-09", null, session("AUDIT", null));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("site-monitor-yonetici-ozeti-2026-09.pdf");
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        assertThat(new String((byte[]) r.getBody(), 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        verify(audit).recordAction(eq("EXECUTIVE_SUMMARY_EXPORT"), any(HttpSession.class), eq("REPORT"),
                eq("executive-summary"), anyString(), isNull());
    }

    @Test
    @DisplayName("test postası: gövdedeki adres YOK SAYILIR, yalnız oturumdaki yöneticinin adresi; 429 sınır; adres yoksa 400")
    void sendTestOnlyToSelf() {
        MockHttpSession s = session("ADMIN", null);
        when(delivery.emailOf("kullanici")).thenReturn("ben@x.com");
        when(delivery.allowTest("kullanici")).thenReturn(true);
        when(delivery.sendTest(any(), anyString())).thenReturn(new ExecutiveSummaryDeliveryService.TestResult(true, "SENT", "ben@x.com"));
        ResponseEntity<Map<String, Object>> ok = c.sendTest(Map.of("email", "saldirgan@kotu.com", "month", "2026-09"), s);
        assertThat(ok.getBody()).containsEntry("success", true);
        verify(delivery).sendTest(YearMonth.of(2026, 9), "ben@x.com");
        verify(delivery, never()).sendTest(any(), eq("saldirgan@kotu.com"));
        verify(audit).recordAction(eq("EXECUTIVE_SUMMARY_TEST"), any(HttpSession.class), eq("REPORT"),
                eq("executive-summary"), anyString(), isNull());

        when(delivery.allowTest("kullanici")).thenReturn(false);
        ResponseEntity<Map<String, Object>> limited = c.sendTest(Map.of(), s);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getBody()).containsEntry("code", "RATE_LIMITED");

        when(delivery.emailOf("kullanici")).thenReturn(null);
        ResponseEntity<Map<String, Object>> noMail = c.sendTest(Map.of(), s);
        assertThat(noMail.getStatusCode().value()).isEqualTo(400);
        assertThat(noMail.getBody()).containsEntry("code", "NO_EMAIL");
        verify(delivery, times(1)).sendTest(any(), anyString());
    }

    @Test
    @DisplayName("ayar kaydı ve elle gönderim global yöneticide çalışır ve denetlenir")
    void saveAndRun() {
        MockHttpSession s = session("ADMIN", null);
        when(settings.save(any(), eq("kullanici"))).thenReturn(Set.of(ExecutiveSummarySettings.ENABLED_KEY));
        assertThat(c.saveSettings(Map.of("enabled", true), s).getBody()).containsEntry("success", true);
        verify(audit).recordAction(eq("EXECUTIVE_SUMMARY_SETTINGS"), any(HttpSession.class), eq("REPORT"),
                eq("executive-summary"), anyString(), isNull());

        when(delivery.sendNow(any(), eq("kullanici")))
                .thenReturn(new ExecutiveSummaryDeliveryService.Result("SENT", "2026-09", 3, 1, "SENT ×3"));
        assertThat(c.run(Map.of("month", "2026-09"), s).getBody()).containsEntry("success", true).containsEntry("code", "SENT");
        when(delivery.sendNow(any(), eq("kullanici")))
                .thenReturn(new ExecutiveSummaryDeliveryService.Result("NO_RECIPIENT", "2026-09", 0, 0, null));
        Map<String, Object> body = c.run(Map.of(), s).getBody();
        assertThat(body).containsEntry("success", false).containsEntry("code", "NO_RECIPIENT").containsKey("error");
    }
}
