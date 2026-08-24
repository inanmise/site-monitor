package com.sitemonitor.controller;

import com.sitemonitor.model.LoginIssueMailLog;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.model.LoginIssueReportImage;
import com.sitemonitor.repository.LoginIssueMailLogRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin Login Sorun Bildirimleri API — permission gate, listeleme, durum akışı. */
@WebMvcTest(LoginIssueController.class)
class LoginIssueControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean LoginIssueMailLogRepository mailLogRepo;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
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
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of("OPEN", 2L, "IN_PROGRESS", 1L, "RESOLVED", 0L));
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
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("admin@akbank.com");
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Hesap açıldı\"}"))
                .andExpect(status().isOk());
        // Çözüldü maili ASYNC + loglu (dispatchResolved): reportId, refCode, reporter, admin + zenginleştirilmiş
        // içerik (username/errorText/message/reportedAt null/eq), çözüm notu, çözülme zamanı, görseller.
        verify(loginIssueMailService).dispatchResolved(eq(9L), eq("LIR-2026-000009"),
                eq("reporter@akbank.com"), eq("admin@akbank.com"),
                any(), any(), any(), eq("2026-07-24T09:00:00"),
                eq("Hesap açıldı"), eq("2026-07-24T10:00:00"), any());
    }

    @Test
    void detail_notFound_404() throws Exception {
        when(loginIssueService.get(999L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/login-issues/999").session(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_mapsRealRow_refCodeSummaryImageCount() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(7L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("RESOLVED");
        r.setUsername("N77"); r.setIpAddress("1.2.3.4"); r.setImageCount(2);
        r.setResolvedAt("2026-07-24T11:30:00"); r.setResolvedBy("admin");   // liste satırında çözülme tarihi
        // 80+ karakter + iç boşluklar → özet 80'de kırpılır, "\s+" tek boşluğa iner, "…" eklenir.
        r.setMessage("Satır1\n\n  çok    boşluklu   ve uzun bir mesaj " + "x".repeat(90));
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r)));
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of("OPEN", 1L, "IN_PROGRESS", 0L, "RESOLVED", 0L));

        mvc.perform(get("/api/admin/login-issues?q=locked&since=2026-07-01T00:00:00&status=RESOLVED").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refCode").value("LIR-2026-000007"))
                .andExpect(jsonPath("$.data[0].username").value("N77"))
                .andExpect(jsonPath("$.data[0].imageCount").value(2))
                .andExpect(jsonPath("$.data[0].resolvedAt").value("2026-07-24T11:30:00"))
                .andExpect(jsonPath("$.data[0].messageSummary", org.hamcrest.Matchers.endsWith("…")))
                .andExpect(jsonPath("$.data[0].messageSummary", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\n"))))
                .andExpect(jsonPath("$.total").value(1));

        // q/since/status request param'ları servise iletilir (source/category filtresi yok → null).
        verify(loginIssueService).list(eq("RESOLVED"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq("locked"),
                eq("2026-07-01T00:00:00"), any(), anyInt(), anyInt());
    }

    @Test
    void detail_serialisesImagesAsDataUrls() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(5L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("OPEN");
        r.setUsername("N5"); r.setMessage("giriş yapamıyorum"); r.setImageCount(2);
        when(loginIssueService.get(5L)).thenReturn(Optional.of(r));
        LoginIssueReportImage i1 = new LoginIssueReportImage(); i1.setContentType("image/png");  i1.setDataBase64("AAAA");
        LoginIssueReportImage i2 = new LoginIssueReportImage(); i2.setContentType("image/jpeg"); i2.setDataBase64("BBBB");
        when(loginIssueService.images(5L)).thenReturn(List.of(i1, i2));

        mvc.perform(get("/api/admin/login-issues/5").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refCode").value("LIR-2026-000005"))
                .andExpect(jsonPath("$.data.images[0]").value("data:image/png;base64,AAAA"))
                .andExpect(jsonPath("$.data.images[1]").value("data:image/jpeg;base64,BBBB"));
    }

    @Test
    void detail_includesMailHistory() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("RESOLVED");
        r.setUsername("N9"); r.setMessage("giriş yok");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        LoginIssueMailLog m = new LoginIssueMailLog();
        m.setMailType("RESOLVED"); m.setRecipientTo("reporter@akbank.com"); m.setCc("admin@akbank.com");
        m.setEmailFrom("noreply@sitemonitor"); m.setSubject("[SiteMonitor] ✅ ... LIR-2026-000009");
        m.setBodyHtml("<html>çözüldü</html>");
        m.setStatus("SENT"); m.setForced(true); m.setSentAt("2026-07-24T10:00:00");
        when(mailLogRepo.findByReportIdOrderByIdAsc(9L)).thenReturn(List.of(m));

        mvc.perform(get("/api/admin/login-issues/9").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mailHistory[0].mailType").value("RESOLVED"))
                .andExpect(jsonPath("$.data.mailHistory[0].from").value("noreply@sitemonitor"))
                .andExpect(jsonPath("$.data.mailHistory[0].to").value("reporter@akbank.com"))
                .andExpect(jsonPath("$.data.mailHistory[0].cc").value("admin@akbank.com"))
                .andExpect(jsonPath("$.data.mailHistory[0].subject").value("[SiteMonitor] ✅ ... LIR-2026-000009"))
                .andExpect(jsonPath("$.data.mailHistory[0].body").value("<html>çözüldü</html>"))
                .andExpect(jsonPath("$.data.mailHistory[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.mailHistory[0].forced").value(true));
    }

    // ── Kalici silme (AYRI + hassas yetki) ───────────────────────────────────

    private com.sitemonitor.model.LoginIssueReport report(long id) {
        com.sitemonitor.model.LoginIssueReport r = new com.sitemonitor.model.LoginIssueReport();
        r.setId(id);
        r.setReportedAt("2026-08-24T10:00:00");
        r.setSource("USER_REPORT");
        r.setUsername("N68753");
        return r;
    }

    @Test
    @DisplayName("Kalici silme AYRI yetki ister — duzenleme yetkisi YETMEZ")
    void purge_requiresDedicatedPermission() throws Exception {
        // Raporun DURUMUNU degistirmek ile kaydi YOK ETMEK farkli yetkiler: ikincisi guvenlik
        // bildirimlerini de silebilir. "Raporlari yonetsin ama kanit silemesin" ayrimi korunmali.
        // require() asiri yuklu (HttpSession / String) → any() belirsiz kalir, TIPLI matcher sart.
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class),
                        eq("issues.login-reports.purge"), eq("execute"));

        mvc.perform(delete("/api/admin/login-issues/7").session(authed()))
                .andExpect(status().isForbidden());

        verify(loginIssueService, never()).purge(any());
    }

    @Test
    @DisplayName("Yetkiliyse kayit silinir ve DENETIME yazilir")
    void purge_deletesAndAudits() throws Exception {
        when(loginIssueService.get(7L)).thenReturn(Optional.of(report(7L)));

        mvc.perform(delete("/api/admin/login-issues/7").session(authed()))
                .andExpect(status().isOk());

        verify(loginIssueService).purge(7L);
        verify(auditService).recordAction(eq("LOGIN_ISSUE_PURGE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), eq("7"), anyString());
    }

    @Test
    @DisplayName("OLMAYAN kayit 404 — silme cagrilmaz")
    void purge_missingRowIs404() throws Exception {
        when(loginIssueService.get(99L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/admin/login-issues/99").session(authed()))
                .andExpect(status().isNotFound());

        verify(loginIssueService, never()).purge(any());
    }
}
