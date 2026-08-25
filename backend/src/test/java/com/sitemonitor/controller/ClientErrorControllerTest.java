package com.sitemonitor.controller;

import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ErrorBoundary otomatik çökme bildirimi — public erişim, oturumdan kullanıcı adı,
 *  DB kalıcılık (sorun-bildirimleri ekranı) + admin maili + audit, toggle ve rate-limit. */
@WebMvcTest(ClientErrorController.class)
class ClientErrorControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean AuditService auditService;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("10.1.2.3");
        when(appSettings.getBoolean(eq("site.monitor.client-errors.enabled"), anyBoolean())).thenReturn(true);
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString()))
                .thenReturn("admin@example.com");
        LoginIssueReport saved = new LoginIssueReport();
        saved.setId(77L); saved.setReportedAt("2026-08-06T20:00:00");
        when(loginIssueService.save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class))).thenReturn(saved);
    }

    @Test
    @DisplayName("Oturumlu çökme bildirimi → 200 + referans; kullanıcı adı OTURUMDAN, DB save + admin mail + audit")
    void report_withSession_usesSessionUsername() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", true);
        session.setAttribute("username", "N12345");

        mvc.perform(post("/api/client-error-report").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"TypeError: Cannot read properties of undefined (reading 'endsWith')\","
                                + "\"url\":\"https://certmonitor-test.example.com/?tab=scripted\","
                                + "\"username\":\"HACKER-BEYANI\"}"))   // istemci beyanı YOK SAYILIR
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reference").value("LIR-2026-000077"));

        // Kullanıcı adı oturumdan (payload'daki beyan değil); mesajda sayfa URL'i; görsel yok;
        // meta: source=CLIENT_ERROR + tab anahtarı URL'den çıkarılır.
        verify(loginIssueService).save(eq("N12345"), eq(""),
                contains("endsWith"), contains("?tab=scripted"),
                any(), eq("10.1.2.3"), any(), anyString(),
                argThat((com.sitemonitor.service.LoginIssueService.ReportMeta m) ->
                        "CLIENT_ERROR".equals(m.source()) && "scripted".equals(m.tabKey())));
        verify(loginIssueMailService).dispatchClientError(eq(77L), eq("LIR-2026-000077"), eq("admin@example.com"),
                eq("N12345"), contains("endsWith"), contains("?tab=scripted"),
                eq("10.1.2.3"), any(), anyString());
        verify(auditService).recordAction(eq("CLIENT_ERROR_REPORT"), eq("N12345"),
                any(), any(), any(), eq("UI"), eq("10.1.2.3"), anyString(),
                eq("10.1.2.3"), any(), any());
    }

    @Test
    @DisplayName("Oturumsuz bildirim → kullanıcı '(oturumsuz)'; yine kaydedilir + mail gider")
    void report_withoutSession_recordsAnonymous() throws Exception {
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"ReferenceError: x is not defined\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("LIR-2026-000077"));

        verify(loginIssueService).save(eq("(oturumsuz)"), eq(""),
                eq("ReferenceError: x is not defined"), anyString(),
                any(), eq("10.1.2.3"), any(), anyString(), any(LoginIssueService.ReportMeta.class));
    }

    @Test
    @DisplayName("Boş errorText → 400; kayıt ve mail yok")
    void report_blankError_rejected() throws Exception {
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
    }

    @Test
    @DisplayName("Uzun stack (>10k) REDDEDİLMEZ — kırpılarak kaydedilir (otomatik bildirimde resubmit yok)")
    void report_longStack_truncatedNotRejected() throws Exception {
        String longStack = "E".repeat(12_000);
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"" + longStack + "\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(),
                argThat((String s) -> s.length() <= 10_050 && s.endsWith("(kırpıldı)")),
                anyString(), any(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
    }

    @Test
    @DisplayName("Özellik kapalı (client-errors.enabled=false) → 404; hiçbir şey kaydedilmez")
    void report_featureDisabled_returns404() throws Exception {
        when(appSettings.getBoolean(eq("site.monitor.client-errors.enabled"), anyBoolean())).thenReturn(false);
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"x\"}"))
                .andExpect(status().isNotFound());
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
    }

    @Test
    @DisplayName("system-admin.email boş → kayıt yazılır ama mail dispatch edilmez")
    void report_noAdminEmail_skipsMail() throws Exception {
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("");
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"boom\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(), eq("boom"), anyString(),
                any(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
        verify(loginIssueMailService, never()).dispatchClientError(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Rate-limit: aynı IP'den 6. bildirim → 429 (5/saat)")
    void report_rateLimited_returns429() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.9.9.9");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/client-error-report")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"errorText\":\"crash " + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/client-error-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorText\":\"crash 6\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
