package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giriş Yöntemleri → "Kullanıcı adı ön eki" kartının uçları (2026-10-21): görünüm bloğu ve form alanları, kayıt
 * normalizasyonu (liste / CSV → büyük harf CSV), alan bazlı hatalar, kendi hesabını kilitleme ({@code LOCKOUT_SELF},
 * AppSettingsService'ten), önizleme YALNIZ global yönetici ve verilen değerlerle; PUBLIC uç yalnız ipucu taşır.
 */
@WebMvcTest(LoginMethodsController.class)
class LoginMethodsUsernamePrefixTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginMethodsService methods;
    @MockitoBean com.sitemonitor.service.loginstats.LoginStatsService loginStats;
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean AuditService auditService;
    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean OtpCodes otpCodes;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    @MockitoBean UsernamePrefixPolicy policy;

    @BeforeEach
    void setUp() {
        when(policy.allows(any())).thenReturn(true);   // oturum kapısı (interceptor) testi bozmasın
        when(methods.settingsView()).thenReturn(Map.of("ldap_enabled", true));
        when(methods.publicView()).thenReturn(new LinkedHashMap<>(Map.of("ldap", true)));
        when(methods.contactCoverage()).thenReturn(Map.of());
        when(methods.smtpStatus()).thenReturn(Map.of());
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("enabled", true);
        up.put("active", true);
        up.put("allowed", List.of("N"));
        up.put("exempt", List.of("OPS"));
        up.put("bootstrap_admin", "admin");
        when(policy.settingsView()).thenReturn(up);
    }

    private static MockHttpSession session(String role, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "N100");
        s.setAttribute("systemRole", role);
        if (scoped) s.setAttribute("viewTeamIds", new ArrayList<>(List.of(3L)));
        return s;
    }

    @Test
    @DisplayName("görünüm: settings içinde üç form alanı + ayrı username_prefix bloğu (salt politika sunucudan)")
    void viewCarriesBlock() throws Exception {
        mvc.perform(get("/api/admin/login-methods").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings.ldap_enabled").value(true))
                .andExpect(jsonPath("$.data.settings.username_prefix_enabled").value(true))
                .andExpect(jsonPath("$.data.settings.username_prefix_allowed[0]").value("N"))
                .andExpect(jsonPath("$.data.settings.username_prefix_exempt[0]").value("OPS"))
                .andExpect(jsonPath("$.data.username_prefix.bootstrap_admin").value("admin"));
    }

    @Test
    @DisplayName("kayıt: liste / CSV normalize edilir (büyük harf, tekil) ve katalog anahtarlarıyla yazılır; denetimde ön ek durumu")
    @SuppressWarnings("unchecked")
    void saveNormalizes() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"username_prefix_enabled\":true,\"username_prefix_allowed\":[\"n\",\" mp \",\"N\"],"
                                + "\"username_prefix_exempt\":\"ops, servis.hesap\"}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settingsService).save(cap.capture(), eq("N100"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry("site.monitor.login.username-prefix.enabled", "true")
                .containsEntry("site.monitor.login.username-prefix.allowed", "N,MP")
                .containsEntry("site.monitor.login.username-prefix.exempt", "OPS,SERVIS.HESAP")
                .hasSize(3);
        verify(auditService).recordAction(eq("LOGIN_METHODS_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("login-methods"), any(), any());
    }

    @Test
    @DisplayName("geçersiz ön ek → 400 + field username_prefix_allowed; geçersiz muaf ad → field username_prefix_exempt; kayıt yok")
    void saveFieldErrors() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"username_prefix_allowed\":[\"N-1\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("username_prefix_allowed"));
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"username_prefix_exempt\":[\"a b\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("username_prefix_exempt"));
        verify(settingsService, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("kendi hesabını kilitleme (AppSettingsService) → 400 LOCKOUT_SELF + field; denetim yazılmaz")
    void lockoutSelfSurfaces() throws Exception {
        doThrow(new GlobalExceptionHandler.FieldValidationException("LOCKOUT_SELF", "username_prefix_allowed", "kendi hesabınız"))
                .when(settingsService).save(any(), anyString());
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"username_prefix_enabled\":true,\"username_prefix_allowed\":\"MP\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCKOUT_SELF"))
                .andExpect(jsonPath("$.field").value("username_prefix_allowed"));
        verify(auditService, never()).recordAction(eq("LOGIN_METHODS_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("önizleme: global yönetici, verilen değerlerle (normalize) servise gider; kaydedilmiş değer okunmaz")
    void previewGlobalAdmin() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active_users", 10L);
        out.put("refused", 3L);
        out.put("sample", List.of(Map.of("username", "MP1", "display_name", "Mehmet")));
        when(policy.preview("N,MP", "OPS", "N100")).thenReturn(out);
        mvc.perform(get("/api/admin/login-methods/username-prefix/preview").session(session("ADMIN", false))
                        .param("allowed", "n, mp").param("exempt", "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refused").value(3))
                .andExpect(jsonPath("$.data.sample[0].username").value("MP1"));
    }

    @Test
    @DisplayName("önizleme: boş liste ve geçersiz ön ek 400 + field; kapsamlı müdür / USER 403")
    void previewGuards() throws Exception {
        mvc.perform(get("/api/admin/login-methods/username-prefix/preview").session(session("ADMIN", false)).param("allowed", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("username_prefix_allowed"));
        mvc.perform(get("/api/admin/login-methods/username-prefix/preview").session(session("ADMIN", false)).param("allowed", "Ç"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("username_prefix_allowed"));
        mvc.perform(get("/api/admin/login-methods/username-prefix/preview").session(session("ADMIN", true)).param("allowed", "N"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/login-methods/username-prefix/preview").session(session("USER", false)).param("allowed", "N"))
                .andExpect(status().isForbidden());
        verify(policy, never()).preview(any(), any(), any());
    }

    @Test
    @DisplayName("kapsamlı müdür kaydedemez (403) — uç global yöneticiye kapılı")
    void scopedAdminCannotSave() throws Exception {
        mvc.perform(put("/api/admin/login-methods").session(session("ADMIN", true)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"username_prefix_enabled\":false}}"))
                .andExpect(status().isForbidden());
        verify(settingsService, never()).save(any(), anyString());
    }
}
