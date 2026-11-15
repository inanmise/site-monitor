package com.sitemonitor.controller;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PageSpeedResource;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PageSpeedResourceRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PageSpeedCheckerService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.scan.PageResourceImageService;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageOutcome;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageResult;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sayfa Hızı → görsel aracı köprüsü (2026-11-15, Sayfa Hızı 2. sürüm madde 22): adres, izlemenin SAKLANAN görsel kaynakları
 * arasında birebir olmalı (değilse dışarıya hiç istek yok — sunucu açık vekil olamaz); kapılar kaynak analizi görseliyle aynı
 * (monitoring.trigger + takımda çalıştırma; göremediği izleme 404); vekil kararı ölçümünkiyle aynı; denetim yalnız başarıda.
 */
@WebMvcTest(PageResourceImageController.class)
class PageSpeedImageEndpointTest {

    static final String PATH = "/api/monitoring/pagespeed/71/image";
    static final String URL = "https://cdn.example.test/img/hero.png?v=2";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PageResourceImageService images;
    @MockitoBean AuditService auditService;
    @MockitoBean PageSpeedMonitorRepository pageSpeedRepo;
    @MockitoBean PageSpeedResourceRepository pageSpeedResourceRepo;
    @MockitoBean PageSpeedCheckerService pageSpeedChecker;

    @BeforeEach
    void defaults() {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(71L);
        m.setName("Hız 71");
        m.setUrl("https://shop.example.test/");
        m.setTeamId(5L);
        when(pageSpeedRepo.findById(71L)).thenReturn(Optional.of(m));
        when(pageSpeedRepo.findById(404L)).thenReturn(Optional.empty());
        PageSpeedResource r = new PageSpeedResource();
        r.setMonitorId(71L);
        r.setCheckId(900L);
        r.setUrl(URL);
        r.setType("IMG");
        r.setBytes(420_000L);
        when(pageSpeedResourceRepo.findStoredImage(71L, URL)).thenReturn(List.of(r));
        when(pageSpeedResourceRepo.findStoredImage(eq(71L), eq("https://evil.example.test/x.png"))).thenReturn(List.of());
        when(pageSpeedChecker.viaProxyFor(any(PageSpeedMonitor.class))).thenReturn(true);
        when(images.imageFromStored(anyString(), any(), anyBoolean(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, PNG, "png", 1200, 800, 0, null, null, null));
    }

    static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req(String path, String body, MockHttpSession s) {
        return post(path).session(s).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("200: saklanan görsel — ölçülen boyut + ölçümün vekil kararı + kişi anahtarı servise; ham baytlar; denetim PAGESPEED_MONITOR, kontrol kimliğiyle")
    void ok() throws Exception {
        mvc.perform(req(PATH, "{\"url\":\"" + URL + "\"}", session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(content().bytes(PNG));
        verify(images).imageFromStored(URL, 420_000L, true, "u:2");
        verify(auditService).recordAction(eq("PAGE_RESOURCE_IMAGE_DOWNLOAD"), any(HttpSession.class), eq("PAGESPEED_MONITOR"),
                eq("71"), org.mockito.ArgumentMatchers.contains("\"check_id\":900"), isNull());
    }

    @Test
    @DisplayName("Saklanmayan adres → 404 RESOURCE_IMAGE_NOT_FOUND (ölçüm metniyle), dışarıya istek yok, denetim yok")
    void notStoredNeverFetched() throws Exception {
        mvc.perform(req(PATH, "{\"url\":\"https://evil.example.test/x.png\"}", session("USER", 5L, 2L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ölçüm")));
        verify(images, never()).imageFromStored(anyString(), any(), anyBoolean(), anyString());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Göremediği / olmayan izleme → 404 RESOURCE_MONITOR_NOT_FOUND; başka takımın izlemesi de aynı (varlığı sızmaz)")
    void notVisible() throws Exception {
        mvc.perform(req("/api/monitoring/pagespeed/404/image", "{\"url\":\"" + URL + "\"}", session("USER", 5L, 2L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"));
        mvc.perform(req(PATH, "{\"url\":\"" + URL + "\"}", session("USER", 8L, 3L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"));
        verify(images, never()).imageFromStored(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("Adres yok → 400 VALIDATION_FAILED url; servis çağrılmaz")
    void missingUrl() throws Exception {
        mvc.perform(req(PATH, "{}", session("USER", 5L, 2L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(images, never()).imageFromStored(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("Kaynak analizi görsel yolu (sınıf yolu değişti) aynen çalışır: /api/monitoring/page/{id}/resource-scans/{scanId}/image")
    void pagePathUnchanged() throws Exception {
        com.sitemonitor.model.PageMonitor pm = new com.sitemonitor.model.PageMonitor();
        pm.setId(61L);
        pm.setTeamId(5L);
        pm.setUseProxy("AUTO");
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(pm));
        when(images.image(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, PNG, "png", 1200, 800, 0, null, null, null));
        mvc.perform(req("/api/monitoring/page/61/resource-scans/900/image", "{\"url\":\"" + URL + "\"}", session("USER", 5L, 2L)))
                .andExpect(status().isOk());
        verify(images).image(61L, 900L, URL, "AUTO", "u:2");
    }
}
