package com.sitemonitor.controller;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SmtpLogQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SMTP Gönderim Logu uçları (2026-09-19): açık uçlu pencerede {@code to} NULL → yanıt gövdesi Map.of ile
 * kurulunca NPE (tarayıcıda "Sunucu hatası" olarak yakalandı) — regresyon kapısı; kapsam türetimi;
 * yeniden gönderim yalnız admin + denetim kaydı; 404/409 eşlemesi.
 */
@WebMvcTest(SmtpLogController.class)
class SmtpLogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SmtpLogQueryService smtpLogQueryService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.RememberMeService rememberMeService;
    @MockitoBean com.sitemonitor.service.UserService userService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettingsService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;   // HttpMetricsInterceptor (WebConfig) — SystemControllerTest ile aynı

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ADMIN".equals(role) ? "admin" : "user1");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    @Test
    @DisplayName("search: açık uçlu pencerede to=null → 200 (Map.of NPE regresyonu); from/total taşınır")
    void search_openEndedWindow_ok() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("items", List.of(Map.of("id", 1))); data.put("total", 1); data.put("page", 0); data.put("size", 25);
        data.put("from", "2026-09-12T00:00:00"); data.put("to", null);
        when(smtpLogQueryService.search(any(), any(), anyInt(), anyInt(), any())).thenReturn(data);

        mvc.perform(get("/api/admin/smtp-log/search").param("from", "2026-09-12T00:00:00").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.from").value("2026-09-12T00:00:00"));
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    @Test
    @DisplayName("kapsam: USER oturumu görüş takımları + envanter alanlarıyla (küçük harf) süzülür; ADMIN global")
    void scope_derivedFromSession() throws Exception {
        when(smtpLogQueryService.summary(any(), any(), any())).thenReturn(Map.of("kpi", Map.of()));
        when(inventoryRepo.findDomainsForTeams(any())).thenReturn(List.of("Shop.example.com"));
        ArgumentCaptor<SmtpLogQueryService.Scope> cap = ArgumentCaptor.forClass(SmtpLogQueryService.Scope.class);

        mvc.perform(get("/api/admin/smtp-log/summary").session(session("USER", List.of(2L)))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/smtp-log/summary").session(session("ADMIN", null))).andExpect(status().isOk());

        verify(smtpLogQueryService, org.mockito.Mockito.times(2)).summary(any(), cap.capture(), any());
        SmtpLogQueryService.Scope scoped = cap.getAllValues().get(0), global = cap.getAllValues().get(1);
        assertThat(scoped.global()).isFalse();
        assertThat(scoped.teamIds()).containsExactly(2L);
        assertThat(scoped.domains()).containsExactly("shop.example.com");
        assertThat(global.global()).isTrue();
    }

    @Test
    @DisplayName("resend: USER → 403; ADMIN → servis çağrısı + SMTP_RESEND denetim kaydı; ALERT_RESOLVED → 409, NOT_FOUND → 404")
    void resend_adminOnlyAndAudited() throws Exception {
        mvc.perform(post("/api/admin/smtp-log/5/resend").session(session("USER", List.of(1L)))).andExpect(status().isForbidden());

        when(smtpLogQueryService.resend(eq(5L), any(), eq("admin"))).thenReturn(new SmtpLogQueryService.ResendResult(true, "SENT", 99L, null));
        mvc.perform(post("/api/admin/smtp-log/5/resend").session(session("ADMIN", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true)).andExpect(jsonPath("$.new_log_id").value(99));
        verify(auditService).recordAction(eq("SMTP_RESEND"), any(), eq("NOTIFICATION_LOG"), eq("5"), any(), any());
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.actions"), eq("execute"));

        when(smtpLogQueryService.resend(eq(6L), any(), eq("admin"))).thenReturn(new SmtpLogQueryService.ResendResult(false, null, null, "ALERT_RESOLVED"));
        mvc.perform(post("/api/admin/smtp-log/6/resend").session(session("ADMIN", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALERT_RESOLVED"));
        when(smtpLogQueryService.resend(eq(7L), any(), eq("admin"))).thenReturn(new SmtpLogQueryService.ResendResult(false, null, null, "NOT_FOUND"));
        mvc.perform(post("/api/admin/smtp-log/7/resend").session(session("ADMIN", null))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("detail: kapsam dışı/yok → 404; export: count + capped")
    void detailAndExport() throws Exception {
        when(smtpLogQueryService.detail(eq(1L), any())).thenReturn(null);
        mvc.perform(get("/api/admin/smtp-log/1").session(session("ADMIN", null))).andExpect(status().isNotFound());
        when(smtpLogQueryService.exportRows(any(), any(), anyInt(), any())).thenReturn(List.of(Map.of("id", 1), Map.of("id", 2)));
        mvc.perform(get("/api/admin/smtp-log/export").session(session("ADMIN", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.capped").value(false));
    }
}
