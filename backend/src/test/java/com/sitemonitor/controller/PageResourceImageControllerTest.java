package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.scan.PageResourceImageService;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageOutcome;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageResult;
import com.sitemonitor.service.page.scan.PageResourceImageService.Projection;
import com.sitemonitor.service.page.scan.PageResourceImageService.ProjectionOutcome;
import com.sitemonitor.service.page.scan.PageResourceImageService.Replacement;
import com.sitemonitor.service.page.scan.ScanImageLimiter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Görsel aracı uçları: indirme = analizi başlatma kapısı (monitoring.trigger + takımda çalıştırma; EXECUTE paylaşım olur, VIEW
 * paylaşım / AUDIT olmaz; göremediği izleme 404), projeksiyon = okuma kapısı; her sonucun kodu + durumu + ek alanları; 200
 * yanıtı belge olarak çizilemez (tür servisten = sihirli bayt, attachment, nosniff, no-store); denetim yalnız başarılı indirmede.
 */
@WebMvcTest(PageResourceImageController.class)
class PageResourceImageControllerTest {

    static final String IMAGE = "/api/monitoring/page/61/resource-scans/900/image";
    static final String PROJECTION = "/api/monitoring/page/61/resource-scans/900/image-projection";
    static final String URL = "https://cdn.example.test/img/hero.final.png?v=3&w=1200";
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

    static ImageResult result(ImageOutcome o) {
        return new ImageResult(o, null, null, null, null, 0, null, null, null);
    }

    static ImageResult okPng() {
        return new ImageResult(ImageOutcome.OK, PNG, "png", 1200, 800, 0, null, null, null);
    }

    static Projection projectionOk() {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("grade", 71);
        side.put("letter", "C");
        side.put("page_bytes", 3_400_000L);
        side.put("image_bytes", 2_100_000L);
        side.put("load_ms", 2400L);
        side.put("large_images", 3);
        side.put("scores", Map.of("images", Map.of("score", 40, "lost", 9.0)));
        Map<String, Object> after = new LinkedHashMap<>(side);
        after.put("grade", 84);
        after.put("letter", "B");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("algorithm_version", 3);
        data.put("capped", false);
        data.put("applied", 2);
        data.put("ignored", 1);
        data.put("before", side);
        data.put("after", after);
        data.put("reused_components", List.of("render_blocking"));
        return new Projection(ProjectionOutcome.OK, data, 0, null);
    }

    @BeforeEach
    void defaults() {
        PageMonitor m = new PageMonitor();
        m.setId(61L);
        m.setName("Sayfa 61");
        m.setUrl("https://shop.example.test/?token=GIZLI99");
        m.setTeamId(5L);
        m.setUseProxy("AUTO");
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(m));
        when(pageMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(okPng());
        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString())).thenReturn(projectionOk());
    }

    static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("displayName", "Kullanıcı " + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    static MockHttpSession admin() { return session("ADMIN", null, 1L); }

    static MockHttpServletRequestBuilder json(String path, String body, MockHttpSession s) {
        return post(path).session(s).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
    }

    static String urlBody(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    void verifyNoAudit() {
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), anyString(), anyString(), any(), any());
    }

    // ── Özgün görsel ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200: ham baytlar; Content-Type türden, attachment + nosniff + no-store, X-Image-*; servise birebir adres + izlemenin vekil kuralı + kişi anahtarı; denetim sorgusuz adresle")
    void imageOk() throws Exception {
        mvc.perform(json(IMAGE, urlBody(URL), session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().longValue("Content-Length", PNG.length))
                .andExpect(header().stringValues("Content-Disposition", "attachment; filename=\"hero.final.png\""))
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(header().string("X-Image-Type", "png"))
                .andExpect(header().string("X-Image-Width", "1200"))
                .andExpect(header().string("X-Image-Height", "800"))
                .andExpect(content().bytes(PNG));
        verify(images).image(61L, 900L, URL, "AUTO", "u:2");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("PAGE_RESOURCE_IMAGE_DOWNLOAD"), any(HttpSession.class), eq("PAGE_MONITOR"), eq("61"),
                detail.capture(), isNull());
        assertThat(detail.getValue()).contains("\"scan_id\"", "900", "cdn.example.test/img/hero.final.png", "\"bytes\"",
                        String.valueOf(PNG.length), "\"type\"", "png", "Sayfa 61")
                .doesNotContain("v=3", "w=1200", "GIZLI99", "?");
    }

    @Test
    @DisplayName("200: tür servisin dediğidir (uzantı .php olsa da webp); boyut bilinmiyorsa X-Image-Width/Height yok; Türkçe ad filename* ile")
    void imageHeadersFollowDetectedType() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, new byte[]{'R', 'I', 'F', 'F'}, "webp", null, null, 0, null, null, null));
        // İstemci yalnız JSON kabul ettiğini söylese de başarılı yanıt ham görseldir (hata gövdeleri JSON)
        mvc.perform(json(IMAGE, urlBody("https://a.example.test/resim.php?id=7"), admin()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"resim.webp\""))
                .andExpect(header().string("X-Image-Type", "webp"))
                .andExpect(header().doesNotExist("X-Image-Width"))
                .andExpect(header().doesNotExist("X-Image-Height"));
        mvc.perform(json(IMAGE, urlBody("https://a.example.test/g%C3%B6rsel%20ad%C4%B1.png"), admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"g_rsel_ad_.webp\"; filename*=UTF-8''g%C3%B6rsel_ad%C4%B1.webp"));
    }

    @Test
    @DisplayName("kapılar = analizi başlatma: izin yok 403; başka takım / bilinmeyen izleme 404; VIEW paylaşım ve AUDIT 403; EXECUTE paylaşım 200 — reddedilende servis ve denetim çağrılmaz")
    void imageGates() throws Exception {
        MockHttpSession other = session("USER", 9L, 3L);
        // Görülemeyen / bilinmeyen izleme KENDİ koduyla (genel NOT_FOUND eski pod'un "uç yok" yanıtıdır — arayüz ayırır)
        mvc.perform(json(IMAGE, urlBody(URL), other))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(json("/api/monitoring/page/404/resource-scans/900/image", urlBody(URL), admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(PageResourceImageController.MONITOR_NOT_FOUND_CODE));
        // İstemci görsel kabul etse de hata yanıtı JSON'dur
        mvc.perform(json(IMAGE, urlBody(URL), other).accept("image/*"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"));

        MockHttpSession viewGrant = session("USER", 9L, 4L);
        viewGrant.setAttribute("grantViewTeamIds", List.of(5L));
        mvc.perform(json(IMAGE, urlBody(URL), viewGrant)).andExpect(status().isForbidden());
        mvc.perform(json(IMAGE, urlBody(URL), session("AUDIT", null, 6L))).andExpect(status().isForbidden());
        verify(images, never()).image(anyLong(), anyLong(), anyString(), any(), anyString());
        verifyNoAudit();

        MockHttpSession execGrant = session("USER", 9L, 5L);
        execGrant.setAttribute("grantViewTeamIds", List.of(5L));
        execGrant.setAttribute("grantExecTeamIds", List.of(5L));
        mvc.perform(json(IMAGE, urlBody(URL), execGrant)).andExpect(status().isOk());
        verify(images).image(61L, 900L, URL, "AUTO", "u:5");

        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isForbidden());
        verify(images, never()).image(anyLong(), anyLong(), anyString(), any(), eq("u:1"));
    }

    @Test
    @DisplayName("400 VALIDATION_FAILED (alan url): gövde yok, url yok / boş / metin değil / 4096'dan uzun — servis çağrılmaz (sınıra sayılmaz); bozuk JSON 400")
    void imageValidation() throws Exception {
        mvc.perform(post(IMAGE).session(admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.field").value("url"));
        for (String body : List.of("{}", "{\"url\":\"\"}", "{\"url\":\"   \"}", "{\"url\":null}", "{\"url\":17}",
                "{\"url\":[\"https://a.example.test/x.png\"]}", "{\"url\":{\"a\":1}}", urlBody("https://a.example.test/" + "x".repeat(4096)))) {
            mvc.perform(json(IMAGE, body, admin())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.field").value("url"));
        }
        mvc.perform(json(IMAGE, "{bozuk", admin())).andExpect(status().isBadRequest());
        mvc.perform(json(IMAGE, "[1,2]", admin())).andExpect(status().isBadRequest());
        verify(images, never()).image(anyLong(), anyLong(), anyString(), any(), anyString());
        verifyNoAudit();
    }

    @Test
    @DisplayName("her sonucun kodu ve durumu: 404 tarama / görsel yok, 409 ayrıntı yok / maskeli, 413 (+max_bytes, bilinen boyut), 400 BLOCKED, 502 (+reason / http_status), 415 — hiçbiri denetlenmez")
    void imageOutcomes() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.SCAN_NOT_FOUND));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_NOT_FOUND"))
                .andExpect(jsonPath("$.error").isNotEmpty());

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.ITEMS_GONE));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_ITEMS_GONE"));

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.NOT_FOUND));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_NOT_FOUND"));

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.MASKED));
        mvc.perform(json(IMAGE, urlBody("https://a.example.test/x.png?token=*****"), admin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_URL_MASKED"));

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.TOO_LARGE));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_TOO_LARGE"))
                .andExpect(jsonPath("$.max_bytes").value(PageResourceImageService.MAX_IMAGE_BYTES))
                .andExpect(jsonPath("$.bytes").doesNotExist());
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.TOO_LARGE, null, null, null, null, 0, null, null, 31_457_280L));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(413))
                .andExpect(jsonPath("$.max_bytes").value(26_214_400))
                .andExpect(jsonPath("$.bytes").value(31_457_280));

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.BLOCKED));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLOCKED"));

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.FETCH_FAILED, null, null, null, null, 0, null, 503, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_FETCH_FAILED"))
                .andExpect(jsonPath("$.http_status").value(503))
                .andExpect(jsonPath("$.reason").doesNotExist());
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.FETCH_FAILED, null, null, null, null, 0, "READ_TIMEOUT", null, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(502))
                .andExpect(jsonPath("$.reason").value("READ_TIMEOUT"))
                .andExpect(jsonPath("$.http_status").doesNotExist());

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(result(ImageOutcome.UNSUPPORTED));
        // Hata gövdesi her zaman JSON — istemci yalnız görsel kabul ettiğini söylese de (406'ya dönmez)
        mvc.perform(json(IMAGE, urlBody(URL), admin()).accept(MediaType.IMAGE_PNG)).andExpect(status().is(415))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_UNSUPPORTED"));
        verifyNoAudit();
    }

    @Test
    @DisplayName("429: kişi sınırı RATE_LIMITED + Retry-After + retry_after_seconds; eşzamanlılık RESOURCE_IMAGE_BUSY + Retry-After: 2 — denetlenmez")
    void imageLimits() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.RATE, null, null, null, null, 37, null, null, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").value(37));
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.BUSY, null, null, null, null, 2, null, null, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_BUSY"))
                .andExpect(jsonPath("$.retry_after_seconds").value(2));
        verifyNoAudit();
    }

    @Test
    @DisplayName("servis beklenmedik hata verirse 500 — denetim yazılmaz, gövdede istisna metni yok")
    void imageUnexpectedFailure() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenThrow(new RuntimeException("gizli-ic-ayrinti"));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is5xxServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("gizli-ic-ayrinti"))));
        verifyNoAudit();
    }

    // ── Projeksiyon ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("projeksiyon 200: {success, data{algorithm_version, capped, applied, ignored, before, after, reused_components}}; girdiler servise aynen; denetim YOK")
    void projectionOkAndArguments() throws Exception {
        String body = """
                {"use_measured": true, "images": [
                  {"url": "https://cdn.example.test/a.png", "bytes": 48213, "width": 800, "height": 533, "type": "WebP"},
                  {"url": "https://cdn.example.test/b.jpg", "bytes": 9}
                ]}""";
        mvc.perform(json(PROJECTION, body, session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.algorithm_version").value(3))
                .andExpect(jsonPath("$.data.capped").value(false))
                .andExpect(jsonPath("$.data.applied").value(2))
                .andExpect(jsonPath("$.data.ignored").value(1))
                .andExpect(jsonPath("$.data.before.grade").value(71))
                .andExpect(jsonPath("$.data.before.letter").value("C"))
                .andExpect(jsonPath("$.data.before.page_bytes").value(3_400_000))
                .andExpect(jsonPath("$.data.before.image_bytes").value(2_100_000))
                .andExpect(jsonPath("$.data.before.load_ms").value(2400))
                .andExpect(jsonPath("$.data.before.large_images").value(3))
                .andExpect(jsonPath("$.data.before.scores.images.score").value(40))
                .andExpect(jsonPath("$.data.before.scores.images.lost").value(9.0))
                .andExpect(jsonPath("$.data.after.grade").value(84))
                .andExpect(jsonPath("$.data.after.letter").value("B"))
                .andExpect(jsonPath("$.data.reused_components[0]").value("render_blocking"));
        verify(images).project(61L, 900L, true, List.of(
                new Replacement("https://cdn.example.test/a.png", 48213, 800, 533, "webp"),
                new Replacement("https://cdn.example.test/b.jpg", 9, null, null, null)), "u:2");
        verifyNoAudit();

        // Gövdesiz / boş gövde: ölçülmüş kazanç kapalı, liste boş
        mvc.perform(post(PROJECTION).session(admin())).andExpect(status().isOk());
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isOk());
        verify(images, org.mockito.Mockito.times(2)).project(61L, 900L, false, List.of(), "u:1");
    }

    @Test
    @DisplayName("projeksiyon kapıları = okuma: izin yok 403; başka takım / bilinmeyen izleme 404; VIEW paylaşım ve AUDIT okur")
    void projectionGates() throws Exception {
        mvc.perform(json(PROJECTION, "{}", session("USER", 9L, 3L)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"));
        mvc.perform(json("/api/monitoring/page/404/resource-scans/900/image-projection", "{}", admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_MONITOR_NOT_FOUND"));
        verify(images, never()).project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString());

        MockHttpSession viewGrant = session("USER", 9L, 4L);
        viewGrant.setAttribute("grantViewTeamIds", List.of(5L));
        mvc.perform(json(PROJECTION, "{}", viewGrant)).andExpect(status().isOk());
        mvc.perform(json(PROJECTION, "{}", session("AUDIT", null, 6L))).andExpect(status().isOk());

        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isForbidden());
        verify(images, never()).project(anyLong(), anyLong(), anyBoolean(), anyList(), eq("u:1"));
        // Projeksiyon çalıştırma iznini SORMAZ
        verify(permissionService, never()).require(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"));
    }

    @Test
    @DisplayName("projeksiyon 400 VALIDATION_FAILED: use_measured boolean değil; images liste değil / 200'den çok; girdi nesne değil; url / bytes / width / height / type alan adıyla — servis çağrılmaz")
    void projectionValidation() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("{\"use_measured\":\"yes\"}", "use_measured");
        cases.put("{\"use_measured\":1}", "use_measured");
        cases.put("{\"images\":{}}", "images");
        cases.put("{\"images\":\"x\"}", "images");
        cases.put("{\"images\":[7]}", "images[0]");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10},null]}", "images[1]");
        cases.put("{\"images\":[{\"bytes\":10}]}", "images[0].url");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10},{\"url\":\" \",\"bytes\":10}]}", "images[1].url");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\"}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":0}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":-5}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10.5}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":\"10\"}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":1073741825}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":1e300}]}", "images[0].bytes");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10,\"width\":0}]}", "images[0].width");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10,\"width\":100001}]}", "images[0].width");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10,\"height\":\"9\"}]}", "images[0].height");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10,\"type\":\"svg\"}]}", "images[0].type");
        cases.put("{\"images\":[{\"url\":\"https://a.example.test/a.png\",\"bytes\":10,\"type\":7}]}", "images[0].type");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            mvc.perform(json(PROJECTION, c.getKey(), admin())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(c.getValue()));
        }
        StringBuilder many = new StringBuilder("{\"images\":[");
        for (int i = 0; i <= PageResourceImageService.MAX_PROJECTION_IMAGES; i++) {
            if (i > 0) many.append(',');
            many.append("{\"url\":\"https://a.example.test/").append(i).append(".png\",\"bytes\":10}");
        }
        many.append("]}");
        mvc.perform(json(PROJECTION, many.toString(), admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("images"));
        verify(images, never()).project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString());

        // Tam sınırda (200 girdi; tam sayı değerli ondalık 12.0 kabul) geçer
        String ok = many.toString().replaceFirst("\\{\"url\":\"https://a\\.example\\.test/0\\.png\",\"bytes\":10\\},", "")
                .replace("\"bytes\":10}]", "\"bytes\":12.0}]");
        mvc.perform(json(PROJECTION, ok, admin())).andExpect(status().isOk());
        verify(images).project(eq(61L), eq(900L), eq(false),
                argThat((List<Replacement> l) -> l.size() == 200 && l.get(199).bytes() == 12), eq("u:1"));
    }

    @Test
    @DisplayName("projeksiyon sonuçları: 404 tarama yok, 409 ayrıntı yok / bitmemiş / hesaplanamaz (+reason), 429 RATE_LIMITED + Retry-After")
    void projectionOutcomes() throws Exception {
        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                .thenReturn(new Projection(ProjectionOutcome.SCAN_NOT_FOUND, null, 0, null));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_NOT_FOUND"));
        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                .thenReturn(new Projection(ProjectionOutcome.ITEMS_GONE, null, 0, null));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_ITEMS_GONE"));
        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                .thenReturn(new Projection(ProjectionOutcome.NOT_DONE, null, 0, null));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_NOT_DONE"));
        for (String reason : List.of("algorithm", "baseline")) {
            when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                    .thenReturn(new Projection(ProjectionOutcome.NOT_PROJECTABLE, null, 0, reason));
            mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_NOT_PROJECTABLE"))
                    .andExpect(jsonPath("$.reason").value(reason))
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                .thenReturn(new Projection(ProjectionOutcome.RATE, null, 12, null));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").value(12));
        verifyNoAudit();
    }

    // ── Dosya adı ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dosya adı: son yol parçası, uzantı TÜRDEN; tırnak / satır sonu / yol ayırıcı taşımaz; boşsa image; en çok 80 karakter")
    void fileNameRules() {
        assertThat(PageResourceImageController.fileName("https://a.com/img/hero.final.png?v=1#x", "webp")).isEqualTo("hero.final.webp");
        assertThat(PageResourceImageController.fileName("https://a.com/", "png")).isEqualTo("image.png");
        assertThat(PageResourceImageController.fileName("https://a.com", "jpeg")).isEqualTo("image.jpg");
        assertThat(PageResourceImageController.fileName("https://a.com/a%22b%0D%0Ac.jpg", "jpeg")).isEqualTo("a_b__c.jpg");
        assertThat(PageResourceImageController.fileName("https://a.com/x/..%2F..%2Fetc%2Fpasswd", "gif")).isEqualTo("passwd.gif");
        assertThat(PageResourceImageController.fileName("https://a.com/.htaccess", "png")).isEqualTo("htaccess.png");
        assertThat(PageResourceImageController.fileName("https://a.com/___.png", "png")).isEqualTo("image.png");
        assertThat(PageResourceImageController.fileName("not a url", "png")).isEqualTo("image.png");
        assertThat(PageResourceImageController.fileName("https://a.com/g%C3%B6rsel%20ad%C4%B1.png", "avif"))
                .isEqualTo("g\u00f6rsel_ad\u0131.avif");
        String longName = PageResourceImageController.fileName("https://a.com/" + "a".repeat(300) + ".png", "bmp");
        assertThat(longName).isEqualTo("a".repeat(PageResourceImageController.FILE_NAME_MAX) + ".bmp");

        assertThat(PageResourceImageController.contentDisposition("hero.png")).isEqualTo("attachment; filename=\"hero.png\"");
        String turkish = PageResourceImageController.contentDisposition("g\u00f6rsel_ad\u0131.png");
        assertThat(turkish).isEqualTo("attachment; filename=\"g_rsel_ad_.png\"; filename*=UTF-8''g%C3%B6rsel_ad%C4%B1.png");
        for (String url : List.of("https://a.com/a%22%3B%20filename%3Devil.html%0D%0ASet-Cookie%3Ax.png", "https://a.com/%5C%22.png")) {
            String cd = PageResourceImageController.contentDisposition(PageResourceImageController.fileName(url, "png"));
            assertThat(cd).doesNotContain("\r", "\n", "\\").endsWith(".png\"");
            assertThat(cd.chars().filter(c -> c == '"').count()).as(cd).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("gövde yardımcıları: tam sayı kuralı (kesirli / metin / dev sayı değil) ve url uzunluk sınırı")
    void bodyHelpers() {
        assertThat(PageResourceImageController.whole(12)).isEqualTo(12L);
        assertThat(PageResourceImageController.whole(12L)).isEqualTo(12L);
        assertThat(PageResourceImageController.whole(12.0)).isEqualTo(12L);
        assertThat(PageResourceImageController.whole(12.5)).isNull();
        assertThat(PageResourceImageController.whole(Double.NaN)).isNull();
        assertThat(PageResourceImageController.whole(Double.POSITIVE_INFINITY)).isNull();
        assertThat(PageResourceImageController.whole(1e300)).isNull();
        assertThat(PageResourceImageController.whole("12")).isNull();
        assertThat(PageResourceImageController.whole(null)).isNull();
        assertThat(PageResourceImageController.whole(new java.math.BigInteger("99999999999999999999999"))).isNull();
        assertThat(PageResourceImageController.whole(new java.math.BigDecimal("7.00"))).isEqualTo(7L);
        assertThat(PageResourceImageController.whole(new java.math.BigDecimal("7.01"))).isNull();
        assertThat(PageResourceImageController.urlOf("https://a.com/" + "x".repeat(4082), "url")).hasSize(4096);
        assertThatThrownBy(() -> PageResourceImageController.urlOf("https://a.com/" + "x".repeat(4083), "url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PageResourceImageController.replacements(null)).isEmpty();
        assertThat(PageResourceImageController.useMeasured(null)).isFalse();
        assertThat(PageResourceImageController.useMeasured(Boolean.TRUE)).isTrue();
    }

    // ── İnceleme düzeltmeleri (2026-11-14) ────────────────────────────────────────────────

    /** Gövdesi yazılırken bekleyen (yavaş istemci) ya da kopan istemciyi taklit eden yanıt. */
    static final class GatedResponse extends MockHttpServletResponse {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean abort;
        volatile int written;

        GatedResponse(boolean abort) {
            this.abort = abort;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {
                }

                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    writing.countDown();
                    if (abort) throw new IOException("Broken pipe");   // istemci koptu (ClientAbortException)
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("zaman aşımı");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    written += len;
                }
            };
        }
    }

    /** Yük hakkı: pod'da tek yuva — hak tutulurken başka kişi "meşgul" alır. */
    static boolean podBusy(ScanImageLimiter lim) {
        ScanImageLimiter.Decision d = lim.tryAcquire("u:yoklama");
        if (d.allowed()) d.ticket().close();
        return d.busy();
    }

    @Test
    @DisplayName("yük hakkı gövde YAZILANA dek tutulur (yavaş istemci); yazma bitince bırakılır — Spring gövde yazmaz (null döner)")
    void leaseHeldUntilBodyWritten() throws Exception {
        ScanImageLimiter lim = new ScanImageLimiter(60, 2, 1, () -> 1_000L);
        ScanImageLimiter.Ticket ticket = lim.tryAcquire("u:2").ticket();
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, PNG, "png", 1200, 800, 0, null, null, null, Boolean.TRUE, ticket));
        PageResourceImageController controller = new PageResourceImageController(pageMonitorRepo, permissionService, images,
                auditService);
        GatedResponse slow = new GatedResponse(false);
        AtomicReference<Object> returned = new AtomicReference<>("-");
        Thread t = new Thread(() -> returned.set(controller.image(61L, 900L, Map.of("url", URL), session("USER", 5L, 2L), slow)));
        t.start();
        assertThat(slow.writing.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(podBusy(lim)).as("gövde yazılırken hak tutuluyor").isTrue();
        slow.release.countDown();
        t.join(10_000);
        assertThat(t.isAlive()).isFalse();
        assertThat(returned.get()).as("yanıt yöntemin içinde yazıldı").isNull();
        assertThat(slow.written).isEqualTo(PNG.length);
        assertThat(slow.getStatus()).isEqualTo(200);
        assertThat(slow.getContentType()).isEqualTo("image/png");
        assertThat(slow.getHeader("Content-Length")).isEqualTo(String.valueOf(PNG.length));
        assertThat(slow.getHeader("X-Image-Tls-Verified")).isEqualTo("true");
        assertThat(podBusy(lim)).as("yazma bitti: hak bırakıldı").isFalse();
        verify(auditService).recordAction(eq("PAGE_RESOURCE_IMAGE_DOWNLOAD"), any(HttpSession.class), eq("PAGE_MONITOR"),
                eq("61"), anyString(), isNull());
    }

    @Test
    @DisplayName("istemci yazma sırasında koparsa (ClientAbortException) yöntem sessizce biter ve yük hakkı yine bırakılır")
    void leaseReleasedOnClientAbort() {
        ScanImageLimiter lim = new ScanImageLimiter(60, 2, 1, () -> 1_000L);
        ScanImageLimiter.Ticket ticket = lim.tryAcquire("u:2").ticket();
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, PNG, "png", null, null, 0, null, null, null, null, ticket));
        PageResourceImageController controller = new PageResourceImageController(pageMonitorRepo, permissionService, images,
                auditService);
        GatedResponse gone = new GatedResponse(true);
        Object r = controller.image(61L, 900L, Map.of("url", URL), session("USER", 5L, 2L), gone);
        assertThat(r).isNull();
        assertThat(gone.writing.getCount()).as("yazma denendi").isZero();
        assertThat(podBusy(lim)).as("kopan istemci hakkı tutmaz").isFalse();
        assertThat(gone.getHeader("X-Image-Tls-Verified")).as("TLS hükmü yoksa başlık yok").isNull();
    }

    @Test
    @DisplayName("ret sonucunda da (hak taşımasa) release çağrısı zararsız; hak taşıyan ret beklenmez ama bırakılır")
    void leaseReleasedOnEveryOutcome() throws Exception {
        ScanImageLimiter lim = new ScanImageLimiter(60, 2, 1, () -> 1_000L);
        ScanImageLimiter.Ticket ticket = lim.tryAcquire("u:2").ticket();
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.UNSUPPORTED, null, null, null, null, 0, null, null, null, null, ticket));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(415));
        assertThat(podBusy(lim)).isFalse();
    }

    @Test
    @DisplayName("200: X-Image-Tls-Verified yalnız TLS hükmü varsa (true / false); http son adımda yok")
    void tlsVerifiedHeader() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.OK, PNG, "png", 1, 1, 0, null, null, null, Boolean.FALSE, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isOk())
                .andExpect(header().string("X-Image-Tls-Verified", "false"))
                .andExpect(content().bytes(PNG));
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString())).thenReturn(okPng());
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Image-Tls-Verified"));
    }

    @Test
    @DisplayName("429 RESOURCE_IMAGE_HOST_LIMITED (+ Retry-After, retry_after_seconds); 502 reason TLS_DOWNGRADE kendi metniyle; projeksiyon 429 RESOURCE_PROJECTION_BUSY + Retry-After: 2 — denetlenmez")
    void newCodes() throws Exception {
        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.HOST_LIMITED, null, null, null, null, 23, null, null, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "23"))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_HOST_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").value(23))
                .andExpect(jsonPath("$.error").isNotEmpty());

        when(images.image(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new ImageResult(ImageOutcome.FETCH_FAILED, null, null, null, null, 0,
                        PageResourceImageService.REASON_TLS_DOWNGRADE, null, null));
        mvc.perform(json(IMAGE, urlBody(URL), admin())).andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("RESOURCE_IMAGE_FETCH_FAILED"))
                .andExpect(jsonPath("$.reason").value("TLS_DOWNGRADE"))
                .andExpect(jsonPath("$.http_status").doesNotExist())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("https")));

        when(images.project(anyLong(), anyLong(), anyBoolean(), anyList(), anyString()))
                .thenReturn(new Projection(ProjectionOutcome.BUSY, null, 2, null));
        mvc.perform(json(PROJECTION, "{}", admin())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("RESOURCE_PROJECTION_BUSY"))
                .andExpect(jsonPath("$.retry_after_seconds").value(2));
        verifyNoAudit();
    }
}
