package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.ClientIpResolver;
import com.certmonitor.service.EmailNotificationService;
import com.certmonitor.service.EmailNotificationService.InlineImage;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Login "sorun bildir" — public erişim, zorunlu kullanıcı adı, çoklu görsel (≤5) doğrulaması, rate-limit. */
@WebMvcTest(LoginHelpController.class)
class LoginHelpControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean EmailNotificationService emailService;
    @MockitoBean AuditService auditService;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("10.1.2.3");
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), anyString()))
                .thenReturn("admin@akbank.com");
        when(emailService.sendLoginIssueReport(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString())).thenReturn("SENT");
    }

    @Test
    @DisplayName("OTURUMSUZ tam payload (hata metni + PNG) → 200; mail görsel bytes'ıyla çağrılır + audit")
    void report_fullPayload_sendsMailWithImage() throws Exception {
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2});
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N12345\",\"errorText\":\"HTTP 423 Locked\","
                                + "\"message\":\"Hesabım kilitlendi, giriş yapamıyorum\","
                                + "\"image\":\"data:image/png;base64," + png + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(emailService).sendLoginIssueReport(eq("admin@akbank.com"), eq("N12345"),
                eq("HTTP 423 Locked"), eq("Hesabım kilitlendi, giriş yapamıyorum"),
                argThat((List<InlineImage> imgs) -> imgs != null && imgs.size() == 1
                        && imgs.get(0).data().length == 6
                        && "image/png".equals(imgs.get(0).contentType())),
                eq("10.1.2.3"), any(), anyString());
        verify(auditService).recordAction(eq("LOGIN_HELP_REPORT"), eq("N12345"),
                any(), any(), any(), eq("LOGIN"), eq("10.1.2.3"), anyString(),
                eq("10.1.2.3"), any(), any());
    }

    @Test
    @DisplayName("Çoklu görsel: images[] dizisi (2 PNG) → 200; mail iki InlineImage ile çağrılır")
    void report_multipleImages_sendsAll() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.2.2.2");
        String a = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        String b = Base64.getEncoder().encodeToString(new byte[]{4, 5});
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"message\":\"iki görsel ekledim\",\"images\":["
                                + "\"data:image/png;base64," + a + "\",\"data:image/jpeg;base64," + b + "\"]}"))
                .andExpect(status().isOk());

        verify(emailService).sendLoginIssueReport(anyString(), eq("N1"), anyString(), anyString(),
                argThat((List<InlineImage> imgs) -> imgs != null && imgs.size() == 2
                        && imgs.get(0).data().length == 3 && "image/png".equals(imgs.get(0).contentType())
                        && imgs.get(1).data().length == 2 && "image/jpeg".equals(imgs.get(1).contentType())),
                anyString(), any(), anyString());
    }

    @Test
    @DisplayName("En fazla 5 görsel — 6. görselde 400; mail gitmez")
    void report_tooManyImages_400() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.3.3.3");
        String one = "\"data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1}) + "\"";
        String arr = String.join(",", java.util.Collections.nCopies(6, one));
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"message\":\"altı görsel\",\"images\":[" + arr + "]}"))
                .andExpect(status().isBadRequest());
        verify(emailService, never()).sendLoginIssueReport(anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Kullanıcı adı zorunlu → eksikse 400; boş açıklama → 400; mail gitmez")
    void report_requiredFields_400() throws Exception {
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"kullanıcı adı yok\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N12345\",\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(emailService, never()).sendLoginIssueReport(anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Görsel doğrulama: SVG/GIF → 400; 1MB üstü → 400")
    void report_imageValidation_400() throws Exception {
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"message\":\"x\",\"image\":\"data:image/svg+xml;base64,PHN2Zz4=\"}"))
                .andExpect(status().isBadRequest());
        String big = Base64.getEncoder().encodeToString(new byte[1100 * 1024]);
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"message\":\"x\",\"image\":\"data:image/png;base64," + big + "\"}"))
                .andExpect(status().isBadRequest());
        verify(emailService, never()).sendLoginIssueReport(anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("IP başına saatte 3 bildirim — 4. istek 429")
    void report_rateLimited_429() throws Exception {
        // Controller bean'i sınıf boyunca paylaşılır → bu teste özel IP (diğer testlerin kotası karışmasın)
        when(clientIpResolver.resolve(any())).thenReturn("10.7.7.7");
        String body = "{\"username\":\"N1\",\"message\":\"deneme\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Admin e-postası boşsa yine jenerik 200 (varlık sızdırılmaz), mail gitmez")
    void report_noAdminEmail_stillGeneric200() throws Exception {
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), anyString())).thenReturn("");
        when(clientIpResolver.resolve(any())).thenReturn("10.9.9.9");   // ayrı IP → rate limit karışmaz

        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N2\",\"message\":\"şifremi unuttum\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(emailService, never()).sendLoginIssueReport(anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), anyString());
    }
}
