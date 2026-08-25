package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.repository.AppUserRepository;
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
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Oturum içi "Sorun Bildir" (USER_REPORT) — kimlik oturumdan, e-posta kuralı (profil birincil /
 *  yoksa zorunlu + profile kaydetme), maskeleme, kategori doğrulama, digest modunda tekil mail atlama. */
@WebMvcTest(IssueReportController.class)
class IssueReportControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean AuditService auditService;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean AppUserRepository appUserRepository;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession session;
    private String username;

    @BeforeEach
    void setUp(TestInfo info) {
        when(clientIpResolver.resolve(any())).thenReturn("10.1.2.3");
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("admin@example.com");
        when(appSettings.getBoolean(eq("site.monitor.issue-reports.daily-digest"), anyBoolean())).thenReturn(false);
        LoginIssueReport saved = new LoginIssueReport();
        saved.setId(88L); saved.setReportedAt("2026-08-07T00:00:00");
        when(loginIssueService.save(anyString(), anyString(), any(), anyString(),
                anyList(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class))).thenReturn(saved);
        // Rate-limit haritası controller bean'inde sınıf boyu yaşar → her test KENDİ kullanıcısıyla
        // koşar ki testler birbirinin 10/saat bütçesini tüketmesin.
        username = "N-" + info.getTestMethod().map(m -> m.getName()).orElse("x");
        session = new MockHttpSession();
        session.setAttribute("authenticated", true);
        session.setAttribute("username", username);
    }

    private AppUser userWithEmail(String email) {
        AppUser u = new AppUser();
        u.setUsername(username); u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("Profil e-postası VARSA: payload e-postası YOK SAYILIR, profil adresi kullanılır; kimlik oturumdan")
    void report_profileEmail_wins() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("profil@example.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Grafik bos gorunuyor\",\"category\":\"BLOCKER\","
                                + "\"email\":\"sahte@x.com\",\"username\":\"BASKASI\","
                                + "\"url\":\"https://cm.example.com/?tab=scripted\",\"tabKey\":\"scripted\","
                                + "\"appVersion\":\"20.1.0\",\"theme\":\"dark\",\"lang\":\"tr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("LIR-2026-000088"))
                .andExpect(jsonPath("$.emailSavedToProfile").value(false));

        verify(loginIssueService).save(eq(username), eq("profil@example.com"), any(), eq("Grafik bos gorunuyor"),
                anyList(), eq("10.1.2.3"), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) -> "USER_REPORT".equals(m.source())
                        && "BLOCKER".equals(m.category()) && "scripted".equals(m.tabKey())
                        && m.autoContextJson() != null && m.autoContextJson().contains("dark")));
        verify(loginIssueMailService).dispatchUserReport(eq(88L), eq("LIR-2026-000088"), eq("admin@example.com"),
                eq(username), eq("profil@example.com"), eq("BLOCKER"), anyString(), any(), any(),
                eq("scripted"), eq("20.1.0"), anyList(), eq("10.1.2.3"), any(), anyString());
        verify(loginIssueMailService).dispatchAck(eq(88L), anyString(), eq("profil@example.com"),
                eq(username), any(), anyString(), anyList(), anyString());
        // Profil e-postası varken profil GÜNCELLENMEZ.
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("Profil e-postası YOKSA: payload e-postası zorunlu; eksikse 400")
    void report_noProfileEmail_required() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail(null)));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"sorun var\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveEmailToProfile=true: verilen e-posta profile yazılır + yanıt bayrağı true")
    void report_saveEmailToProfile() throws Exception {
        AppUser u = userWithEmail(null);
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(u));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"sorun var\",\"email\":\"yeni@example.com\",\"saveEmailToProfile\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSavedToProfile").value(true));
        verify(appUserRepository).save(argThat((AppUser saved) -> "yeni@example.com".equals(saved.getEmail())));
    }

    @Test
    @DisplayName("Gizlilik: errorText + url + failedRequests yollarındaki hassas query değerleri MASKELENİR")
    void report_masksSecrets() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"errorText\":\"GET /api/x?token=GIZLI123 failed\","
                                + "\"url\":\"https://cm/?tab=a&password=SIFRE1\","
                                + "\"failedRequests\":[{\"path\":\"/api/y?api_key=KEY99\",\"status\":500,\"at\":\"t\"}]}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(),
                argThat((String err) -> !err.contains("GIZLI123") && err.contains("*****")),
                anyString(), anyList(), anyString(), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) ->
                        !m.autoContextJson().contains("SIFRE1") && !m.autoContextJson().contains("KEY99")));
    }

    @Test
    @DisplayName("Geçersiz kategori → 400; boş açıklama → 400")
    void report_validation() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"category\":\"WRONG\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Digest modu AÇIK: tekil admin maili ATLANIR, ACK yine gider, kayıt yazılır")
    void report_digestMode_skipsAdminMail() throws Exception {
        when(appSettings.getBoolean(eq("site.monitor.issue-reports.daily-digest"), anyBoolean())).thenReturn(true);
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"digest testi\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(), any(), eq("digest testi"),
                anyList(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
        verify(loginIssueMailService, never()).dispatchUserReport(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), any(), any(), any(), any(), anyList(),
                anyString(), any(), anyString());
        verify(loginIssueMailService).dispatchAck(anyLong(), anyString(), eq("p@x.com"),
                anyString(), any(), anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("Rate-limit: aynı kullanıcıdan 11. bildirim → 429 (10/saat)")
    void report_rateLimit() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/issue-reports").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"m" + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m11\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
