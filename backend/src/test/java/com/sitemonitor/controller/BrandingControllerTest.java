package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Branding (beyaz etiket) — izin gate'i, public endpoint, logo doğrulaması, banner versiyon artışı. */
@WebMvcTest(BrandingController.class)
class BrandingControllerTest {

    @Autowired MockMvc mvc;
    @Autowired org.springframework.core.env.Environment environment;

    @MockitoBean AppSettingsService settingsService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                        anyString(), anyString());
        org.mockito.Mockito.lenient().when(settingsService.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        org.mockito.Mockito.lenient().when(settingsService.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        org.mockito.Mockito.lenient().when(settingsService.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("Admin GET oturumsuz → 401; USER → 403; bootstrap admin → 200 (yalnız branding grubu)")
    void adminGet_gates() throws Exception {
        mvc.perform(get("/api/admin/branding/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/branding/settings").session(userSession("sre1")))
                .andExpect(status().isForbidden());

        when(settingsService.getCatalogForClient()).thenReturn(List.of(
                Map.of("key", "site.monitor.branding.app-name", "group", "branding", "type", "STRING"),
                Map.of("key", "site.monitor.app.base-url", "group", "general", "type", "STRING")));
        mvc.perform(get("/api/admin/branding/settings").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("site.monitor.branding.app-name"));
    }

    @Test
    @DisplayName("PUT bootstrap admin → 200 + audit; USER → 403")
    void adminPut_gates() throws Exception {
        when(settingsService.getCatalogForClient()).thenReturn(List.of());
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.app-name\":\"Akbank Monitor\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(put("/api/admin/branding/settings").session(userSession("sre1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Public GET /api/branding oturumsuz → 200 + varsayılan değerler")
    void publicBranding_noAuth_200() throws Exception {
        mvc.perform(get("/api/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.app_name").value("SiteMonitor"))
                .andExpect(jsonPath("$.data.banner_enabled").value(false))
                .andExpect(jsonPath("$.data.banner_version").value(0))
                // Surum BU UCTAN gelir: arayuz onu derleme zamaninda gomuyordu ve dev-server
                // yeniden baslatilmadikca BAYAT kaliyordu (kullanici v20.26.1 gorurken depo
                // v20.29.4'teydi). Uc PUBLIC olmak ZORUNDA: login sayfasi footer'da surumu
                // oturum acmadan gosteriyor.
                .andExpect(jsonPath("$.data.app_version").isNotEmpty());
    }

    @Test
    @DisplayName("Surum public ucta DONER ve AppVersion ile AYNI degerdir (tek dogruluk kaynagi)")
    void publicBranding_exposesAppVersion() throws Exception {
        String expected = com.sitemonitor.service.AppVersion.resolve(environment);

        mvc.perform(get("/api/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.app_version").value(expected));
    }

    @Test
    @DisplayName("Logo doğrulaması: yanlış MIME → 400; script'li SVG → 400; geçerli PNG → 200")
    void logoValidation() throws Exception {
        // Yanlış MIME (gif)
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.logo-data\":\"data:image/gif;base64,R0lGOD\"}}"))
                .andExpect(status().isBadRequest());
        // Script'li SVG
        String evilSvg = Base64.getEncoder().encodeToString(
                "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><script>x</script></svg>".getBytes());
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.logo-data\":\"data:image/svg+xml;base64," + evilSvg + "\"}}"))
                .andExpect(status().isBadRequest());
        // Geçerli küçük PNG
        when(settingsService.getCatalogForClient()).thenReturn(List.of());
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.logo-data\":\"data:image/png;base64," + png + "\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Logo 200KB üstü → 400")
    void logoTooLarge_400() throws Exception {
        String big = Base64.getEncoder().encodeToString(new byte[210 * 1024]);
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.logo-data\":\"data:image/png;base64," + big + "\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("banner-text değişince banner-version otomatik +1 olarak kaydedilir")
    void bannerVersion_autoIncrementsOnTextChange() throws Exception {
        when(settingsService.getString(eq("site.monitor.branding.banner-text"), anyString())).thenReturn("eski metin");
        when(settingsService.getInt(eq("site.monitor.branding.banner-version"), anyInt())).thenReturn(3);
        when(settingsService.getCatalogForClient()).thenReturn(List.of());

        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.banner-text\":\"yeni duyuru\"}}"))
                .andExpect(status().isOk());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return "yeni duyuru".equals(values.get("site.monitor.branding.banner-text"))
                    && "4".equals(values.get("site.monitor.branding.banner-version"));
        }), anyString());
    }

    @Test
    @DisplayName("şerit KAPALI→AÇIK yapılınca da banner-version +1 — kapatmış kullanıcılar yeniden görsün")
    void bannerVersion_incrementsWhenReEnabled() throws Exception {
        when(settingsService.getBoolean(eq("site.monitor.branding.banner-enabled"), anyBoolean())).thenReturn(false);
        when(settingsService.getInt(eq("site.monitor.branding.banner-version"), anyInt())).thenReturn(7);
        when(settingsService.getCatalogForClient()).thenReturn(List.of());

        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.banner-enabled\":\"true\"}}"))
                .andExpect(status().isOk());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return "8".equals(values.get("site.monitor.branding.banner-version"));
        }), anyString());
    }

    @Test
    @DisplayName("şerit zaten AÇIKken tekrar kaydetmek versiyonu ŞİŞİRMEZ")
    void bannerVersion_notIncrementedWhenAlreadyEnabled() throws Exception {
        when(settingsService.getBoolean(eq("site.monitor.branding.banner-enabled"), anyBoolean())).thenReturn(true);
        when(settingsService.getString(eq("site.monitor.branding.banner-tone"), anyString())).thenReturn("INFO");
        when(settingsService.getCatalogForClient()).thenReturn(List.of());

        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.banner-enabled\":\"true\","
                                + "\"site.monitor.branding.banner-tone\":\"INFO\"}}"))
                .andExpect(status().isOk());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return !values.containsKey("site.monitor.branding.banner-version");
        }), anyString());
    }

    @Test
    @DisplayName("ton değişince de banner-version +1")
    void bannerVersion_incrementsOnToneChange() throws Exception {
        when(settingsService.getString(eq("site.monitor.branding.banner-tone"), anyString())).thenReturn("INFO");
        when(settingsService.getInt(eq("site.monitor.branding.banner-version"), anyInt())).thenReturn(1);
        when(settingsService.getCatalogForClient()).thenReturn(List.of());

        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.branding.banner-tone\":\"CRITICAL\"}}"))
                .andExpect(status().isOk());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return "2".equals(values.get("site.monitor.branding.banner-version"));
        }), anyString());
    }

    private MockHttpSession userSession(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));
        return s;
    }
}
