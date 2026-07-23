package com.certmonitor.controller;

import com.certmonitor.model.LoginIssueReport;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.EmailNotificationService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.LoginIssueService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin Login Sorun Bildirimleri API — permission gate, listeleme, durum akışı. */
@WebMvcTest(LoginIssueController.class)
class LoginIssueControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean EmailNotificationService emailService;
    @MockitoBean com.certmonitor.service.AppSettingsService appSettings;
    // AuthInterceptor bağımlılıkları:
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession authed() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "someadmin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    void list_returnsCountsAndData() throws Exception {
        when(loginIssueService.list(any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(loginIssueService.counts()).thenReturn(Map.of("OPEN", 2L, "IN_PROGRESS", 1L, "RESOLVED", 0L));
        mvc.perform(get("/api/admin/login-issues").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.counts.OPEN").value(2));
    }

    @Test
    void noPermission_403() throws Exception {
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: issues.login-reports/view"))
                .when(permissionService).require(any(HttpSession.class), eq("issues.login-reports"), eq("view"));
        mvc.perform(get("/api/admin/login-issues").session(authed()))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolveWithoutNote_400() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("OPEN"); r.setReportedAt("2026-07-23T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("RESOLVED"), any(), anyString()))
                .thenThrow(new IllegalArgumentException("Çözüm notu zorunludur"));
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusUpdate_success() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("IN_PROGRESS"); r.setReportedAt("2026-07-23T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("IN_PROGRESS"), any(), anyString())).thenReturn(r);
        when(loginIssueService.images(9L)).thenReturn(List.of());
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void resolve_notifiesReporterByEmail() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("RESOLVED"); r.setReportedAt("2026-07-24T09:00:00");
        r.setReporterEmail("reporter@akbank.com"); r.setResolutionNote("Hesap açıldı"); r.setResolvedAt("2026-07-24T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("RESOLVED"), any(), anyString())).thenReturn(r);
        when(loginIssueService.images(9L)).thenReturn(List.of());
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), anyString())).thenReturn("admin@akbank.com");
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Hesap açıldı\"}"))
                .andExpect(status().isOk());
        // Hem bildirene (To) hem sistem yöneticisine (CC/admin) gider.
        verify(emailService).sendLoginIssueResolved(eq("reporter@akbank.com"), eq("admin@akbank.com"),
                eq("LIR-2026-000009"), eq("Hesap açıldı"), eq("2026-07-24T10:00:00"));
    }

    @Test
    void detail_notFound_404() throws Exception {
        when(loginIssueService.get(999L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/login-issues/999").session(authed()))
                .andExpect(status().isNotFound());
    }
}
