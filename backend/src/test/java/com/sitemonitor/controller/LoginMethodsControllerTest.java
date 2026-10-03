package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.otp.LoginMethodsService;
import com.sitemonitor.service.otp.OtpCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giriş Yöntemleri uçları (2026-10-02). PUBLIC uç oturumsuz ve yalnız yapılandırma taşır (kişi bilgisi yok; anahtar
 * kümesi sabit); yönetim uçları YALNIZ global yönetici (kapsamlı müdür / USER 403); kayıt sunucuda aralık doğrular
 * (400 + alan adı) ve LOGIN_METHODS_SETTINGS_SAVE denetimini alan farkıyla yazar.
 */
@WebMvcTest(LoginMethodsController.class)
class LoginMethodsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginMethodsService methods;
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean AuditService auditService;
    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean OtpCodes otpCodes;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("ldap", true);
        pub.put("otp_push", false);
        pub.put("otp_email", true);
        pub.put("push_ttl", 45);
        pub.put("email_ttl", 60);
        pub.put("resend_cooldown", 30);
        pub.put("push_requires_phone", true);
        pub.put("email_requires_email", false);
        when(methods.publicView()).thenReturn(pub);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("active_users", 120L);
        coverage.put("with_phone", 90L);
        coverage.put("with_email", 118L);
        when(methods.contactCoverage()).thenReturn(coverage);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("ldap_enabled", true);
        settings.put("email_enabled", true);
        when(methods.settingsView()).thenReturn(settings);
        when(methods.smtpStatus()).thenReturn(Map.of("configured", true));
        AuditLog row = new AuditLog();
        row.setId(5L);
        row.setEventType("LOGIN_OTP_REQUESTED");
        row.setActor("ALICE");
        row.setOutcome("SUCCESS");
        row.setDetail("{\"channel\":\"EMAIL\",\"result\":\"SENT\"}");
        when(auditLogRepo.findRecentOtpActivity(any(), any())).thenReturn(List.of(row));
    }

    private static MockHttpSession session(String role, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "boss");
        s.setAttribute("systemRole", role);
        if (scoped) s.setAttribute("viewTeamIds", new ArrayList<>(List.of(3L)));
        return s;
    }

    @Test
    @DisplayName("PUBLIC: oturumsuz 200 — anahtar kümesi SABİT (yalnız yapılandırma, kişi / IP / sayaç yok)")
    void publicView_noSession() throws Exception {
        String json = mvc.perform(get("/api/public/login-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.otp_push").value(false))
                .andExpect(jsonPath("$.email_ttl").value(60))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
        assertThat(m.keySet()).containsExactlyInAnyOrder("success", "ldap", "otp_push", "otp_email", "push_ttl", "email_ttl",
                "resend_cooldown", "push_requires_phone", "email_requires_email");
        // 2026-10-03: yalnız YAPILANDIRMA bayrakları — kimin hangi bilgiye sahip olduğu (kapsam) PUBLIC uçta YOK
        assertThat(m).containsEntry("push_requires_phone", true).containsEntry("email_requires_email", false)
                .doesNotContainKey("coverage");
        verify(methods, never()).contactCoverage();
    }

    @Test
    @DisplayName("yönetim GET: oturumsuz 401, USER 403, kapsamlı müdür 403, global yönetici 200 (ayar + sınır + durum + önizleme + etkinlik)")
    void adminGet_globalOnly() throws Exception {
        mvc.perform(get("/api/admin/login-methods")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/login-methods").session(session("USER", false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/login-methods").session(session("ADMIN", true))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/login-methods").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings.ldap_enabled").value(true))
                .andExpect(jsonPath("$.data.limits.ttl[0]").value(30))
                .andExpect(jsonPath("$.data.limits.ttl[1]").value(300))
                .andExpect(jsonPath("$.data.status.smtp.configured").value(true))
                .andExpect(jsonPath("$.data.public.otp_email").value(true))
                .andExpect(jsonPath("$.data.activity[0].event_type").value("LOGIN_OTP_REQUESTED"))
                .andExpect(jsonPath("$.data.activity_types[0]").value("LOGIN_OTP_REQUESTED"))
                // 2026-10-03: kişi bilgisi kapsamı (tek toplu sorgu, yalnız sayılar)
                .andExpect(jsonPath("$.data.coverage.active_users").value(120))
                .andExpect(jsonPath("$.data.coverage.with_phone").value(90))
                .andExpect(jsonPath("$.data.coverage.with_email").value(118));
    }

    @Test
    @DisplayName("kayıt: aralık dışı süre → 400 + field; yazılmaz, denetlenmez")
    void save_rangeValidation() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"push_ttl_seconds\":20,\"max_attempts\":3}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("push_ttl_seconds"));
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"max_attempts\":\"abc\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("max_attempts"));
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"ldap_enabled\":\"maybe\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("ldap_enabled"));
        verify(settingsService, never()).save(any(), anyString());
        verify(auditService, never()).recordAction(eq("LOGIN_METHODS_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("kayıt: geçerli değerler katalog anahtarlarıyla TEK save'de yazılır + LOGIN_METHODS_SETTINGS_SAVE (alan farkıyla)")
    @SuppressWarnings("unchecked")
    void save_ok_audited() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"ldap_enabled\":false,\"email_enabled\":true,\"email_ttl_seconds\":60,"
                                + "\"max_requests_per_ip\":50,\"allow_global_admins\":false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settingsService).save(cap.capture(), eq("boss"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry("site.monitor.login.ldap-enabled", "false")
                .containsEntry("site.monitor.login.otp.email.enabled", "true")
                .containsEntry("site.monitor.login.otp.email.ttl-seconds", "60")
                .containsEntry("site.monitor.login.otp.max-requests-per-ip", "50")
                .containsEntry("site.monitor.login.otp.allow-global-admins", "false")
                .hasSize(5);
        verify(auditService).recordAction(eq("LOGIN_METHODS_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("login-methods"), any(), any());
    }

    @Test
    @DisplayName("2026-10-03: kişi bilgisi anahtarları + eşleşmeme sınırı kaydedilir; sınır 1–20 dışı 400 + field")
    @SuppressWarnings("unchecked")
    void save_contactSettings() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"max_contact_mismatches\":21}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("max_contact_mismatches"));
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"push_require_phone\":false,\"email_require_email\":true,"
                                + "\"max_contact_mismatches\":7}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settingsService).save(cap.capture(), eq("boss"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry("site.monitor.login.otp.push.require-phone", "false")
                .containsEntry("site.monitor.login.otp.email.require-email", "true")
                .containsEntry("site.monitor.login.otp.max-contact-mismatches", "7")
                .hasSize(3);
    }

    @Test
    @DisplayName("kayıt: kapsamlı müdür 403 (GLOBAL_ONLY anahtarlar ayrıca AppSettingsService'te reddedilir)")
    void save_scopedAdmin_403() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", true)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"ldap_enabled\":false}}"))
                .andExpect(status().isForbidden());
        verify(settingsService, never()).save(any(), anyString());
    }
}
