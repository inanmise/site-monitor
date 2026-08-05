package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
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
                Map.of("key", "cert.monitor.branding.app-name", "group", "branding", "type", "STRING"),
                Map.of("key", "cert.monitor.app.base-url", "group", "general", "type", "STRING")));
        mvc.perform(get("/api/admin/branding/settings").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("cert.monitor.branding.app-name"));
    }

    @Test
    @DisplayName("PUT bootstrap admin → 200 + audit; USER → 403")
    void adminPut_gates() throws Exception {
        when(settingsService.getCatalogForClient()).thenReturn(List.of());
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.app-name\":\"Akbank Monitor\"}}"))
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
                .andExpect(jsonPath("$.data.app_name").value("Site Monitör"))
                .andExpect(jsonPath("$.data.banner_enabled").value(false))
                .andExpect(jsonPath("$.data.banner_version").value(0));
    }

    @Test
    @DisplayName("Logo doğrulaması: yanlış MIME → 400; script'li SVG → 400; geçerli PNG → 200")
    void logoValidation() throws Exception {
        // Yanlış MIME (gif)
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.logo-data\":\"data:image/gif;base64,R0lGOD\"}}"))
                .andExpect(status().isBadRequest());
        // Script'li SVG
        String evilSvg = Base64.getEncoder().encodeToString(
                "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><script>x</script></svg>".getBytes());
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.logo-data\":\"data:image/svg+xml;base64," + evilSvg + "\"}}"))
                .andExpect(status().isBadRequest());
        // Geçerli küçük PNG
        when(settingsService.getCatalogForClient()).thenReturn(List.of());
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.logo-data\":\"data:image/png;base64," + png + "\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Logo 200KB üstü → 400")
    void logoTooLarge_400() throws Exception {
        String big = Base64.getEncoder().encodeToString(new byte[210 * 1024]);
        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.logo-data\":\"data:image/png;base64," + big + "\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("banner-text değişince banner-version otomatik +1 olarak kaydedilir")
    void bannerVersion_autoIncrementsOnTextChange() throws Exception {
        when(settingsService.getString(eq("cert.monitor.branding.banner-text"), anyString())).thenReturn("eski metin");
        when(settingsService.getInt(eq("cert.monitor.branding.banner-version"), anyInt())).thenReturn(3);
        when(settingsService.getCatalogForClient()).thenReturn(List.of());

        mvc.perform(put("/api/admin/branding/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.branding.banner-text\":\"yeni duyuru\"}}"))
                .andExpect(status().isOk());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return "yeni duyuru".equals(values.get("cert.monitor.branding.banner-text"))
                    && "4".equals(values.get("cert.monitor.branding.banner-version"));
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
