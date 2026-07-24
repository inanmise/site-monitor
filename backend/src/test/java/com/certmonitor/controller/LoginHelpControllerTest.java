package com.certmonitor.controller;

import com.certmonitor.model.LoginIssueReport;
import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.ClientIpResolver;
import com.certmonitor.service.EmailNotificationService.InlineImage;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.LoginIssueMailService;
import com.certmonitor.service.LoginIssueService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Login "sorun bildir" — public erişim, zorunlu kullanıcı adı + e-posta, çoklu görsel, DB kalıcılık, rate-limit. */
@WebMvcTest(LoginHelpController.class)
class LoginHelpControllerTest {

    private static final String EMAIL = "user@akbank.com";

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
        when(appSettings.getBoolean(eq("cert.monitor.login-issues.enabled"), anyBoolean())).thenReturn(true);
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), anyString()))
                .thenReturn("admin@akbank.com");
        LoginIssueReport saved = new LoginIssueReport();
        saved.setId(42L); saved.setReportedAt("2026-07-24T09:00:00");
        when(loginIssueService.save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString())).thenReturn(saved);
    }

    @Test
    @DisplayName("OTURUMSUZ tam payload (email + hata + PNG) → 200 + referans; DB save + admin mail + reporter ACK + audit")
    void report_fullPayload_persistsAndNotifies() throws Exception {
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2});
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N12345\",\"email\":\"" + EMAIL + "\",\"errorText\":\"HTTP 423 Locked\","
                                + "\"message\":\"Hesabım kilitlendi, giriş yapamıyorum\","
                                + "\"image\":\"data:image/png;base64," + png + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reference").value("LIR-2026-000042"));

        // DB kalıcılık — email + resimle birlikte save çağrıldı (base64, prefix'siz).
        verify(loginIssueService).save(eq("N12345"), eq(EMAIL), eq("HTTP 423 Locked"),
                eq("Hesabım kilitlendi, giriş yapamıyorum"),
                argThat((List<LoginIssueService.ParsedImage> imgs) ->
                        imgs != null && imgs.size() == 1 && "image/png".equals(imgs.get(0).contentType())),
                eq("10.1.2.3"), any(), anyString());
        // Mailler ASYNC + loglu (loginIssueMailService.dispatch*) — reportId=42, refCode, alıcı, görsel eşleşir.
        verify(loginIssueMailService).dispatchReport(eq(42L), eq("LIR-2026-000042"), eq("admin@akbank.com"), eq("N12345"),
                eq("HTTP 423 Locked"), eq("Hesabım kilitlendi, giriş yapamıyorum"),
                argThat((List<InlineImage> imgs) -> imgs != null && imgs.size() == 1
                        && imgs.get(0).data().length == 6 && "image/png".equals(imgs.get(0).contentType())),
                eq("10.1.2.3"), any(), anyString());
        // Bildiren kişiye ACK — admin'e gidenle benzer (hata + görsel) + referans no.
        verify(loginIssueMailService).dispatchAck(eq(42L), eq("LIR-2026-000042"), eq(EMAIL), eq("N12345"),
                eq("HTTP 423 Locked"), eq("Hesabım kilitlendi, giriş yapamıyorum"),
                argThat((List<InlineImage> imgs) -> imgs != null && imgs.size() == 1), anyString());
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
                        .content("{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"iki görsel ekledim\",\"images\":["
                                + "\"data:image/png;base64," + a + "\",\"data:image/jpeg;base64," + b + "\"]}"))
                .andExpect(status().isOk());

        verify(loginIssueMailService).dispatchReport(anyLong(), anyString(), anyString(), eq("N1"), anyString(), anyString(),
                argThat((List<InlineImage> imgs) -> imgs != null && imgs.size() == 2
                        && imgs.get(0).data().length == 3 && "image/png".equals(imgs.get(0).contentType())
                        && imgs.get(1).data().length == 2 && "image/jpeg".equals(imgs.get(1).contentType())),
                anyString(), any(), anyString());
    }

    @Test
    @DisplayName("En fazla 5 görsel — 6. görselde 400; kayıt/mail gitmez")
    void report_tooManyImages_400() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.3.3.3");
        String one = "\"data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1}) + "\"";
        String arr = String.join(",", java.util.Collections.nCopies(6, one));
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"altı görsel\",\"images\":[" + arr + "]}"))
                .andExpect(status().isBadRequest());
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Zorunlu alanlar: username yok / email yok / geçersiz email / boş açıklama → 400; kayıt gitmez")
    void report_requiredFields_400() throws Exception {
        // username yok
        expect400("{\"email\":\"" + EMAIL + "\",\"message\":\"x\"}");
        // email yok
        expect400("{\"username\":\"N12345\",\"message\":\"x\"}");
        // geçersiz email
        expect400("{\"username\":\"N12345\",\"email\":\"bozuk\",\"message\":\"x\"}");
        // boş açıklama (email geçerli)
        expect400("{\"username\":\"N12345\",\"email\":\"" + EMAIL + "\",\"message\":\"  \"}");
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Görsel doğrulama: SVG/GIF → 400; 1MB üstü → 400")
    void report_imageValidation_400() throws Exception {
        expect400("{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"x\",\"image\":\"data:image/svg+xml;base64,PHN2Zz4=\"}");
        String big = Base64.getEncoder().encodeToString(new byte[1100 * 1024]);
        expect400("{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"x\",\"image\":\"data:image/png;base64," + big + "\"}");
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("IP başına saatte 3 bildirim — 4. istek 429")
    void report_rateLimited_429() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.7.7.7");
        String body = "{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"deneme\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Admin e-postası boşsa yine jenerik 200 (varlık sızdırılmaz) + DB kaydı yapılır")
    void report_noAdminEmail_stillGeneric200() throws Exception {
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), anyString())).thenReturn("");
        when(clientIpResolver.resolve(any())).thenReturn("10.9.9.9");

        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N2\",\"email\":\"" + EMAIL + "\",\"message\":\"şifremi unuttum\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(loginIssueService).save(eq("N2"), eq(EMAIL), anyString(), anyString(),
                any(), anyString(), any(), anyString());
        verify(loginIssueMailService, never()).dispatchReport(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Özellik kapalı (login-issues.enabled=false) → 404; kayıt/mail gitmez")
    void report_featureDisabled_404() throws Exception {
        when(appSettings.getBoolean(eq("cert.monitor.login-issues.enabled"), anyBoolean())).thenReturn(false);
        mvc.perform(post("/api/login-help")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"deneme\"}"))
                .andExpect(status().isNotFound());
        verify(loginIssueService, never()).save(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Rate-limit görsel doğrulamasından ÖNCE: 3 geçerli sonra geçersiz görselli 4. istek → 400 değil 429")
    void report_rateLimitBeforeImageValidation_429() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.8.8.8");
        String ok = "{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"deneme\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(ok))
                    .andExpect(status().isOk());
        }
        // 4. istek geçersiz (SVG) görsel içeriyor; görsel doğrulaması 400 döndürürdü. Rate-limit ÖNCE
        // çalıştığından decode'a hiç ulaşılmadan 429 dönmeli (kimliksiz decode amplifikasyonu engellenir).
        String withSvg = "{\"username\":\"N1\",\"email\":\"" + EMAIL + "\",\"message\":\"x\","
                + "\"image\":\"data:image/svg+xml;base64,PHN2Zz4=\"}";
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(withSvg))
                .andExpect(status().isTooManyRequests());
    }

    private void expect400(String body) throws Exception {
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
