package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Markalı 404 (2026-10-08): bilinmeyen sayfa → SPA kabuğu + HTTP 404; bilinmeyen {@code /api/**} → JSON 404
 * ({@code code=NOT_FOUND}); eksik statik varlık → düz metin 404 (ASLA HTML); {@code /} ve var olan varlıklar değişmez.
 * Gerçek Spring MVC statik kaynak işleyicisi + {@link WebConfig} filtreleri (CSP, Cache-Control) ile koşar; statik konum
 * test fikstürüdür ({@code classpath:/spa-not-found/}).
 */
@WebMvcTest(controllers = SpaNotFoundAdvice.class)
@TestPropertySource(properties = "spring.web.resources.static-locations=classpath:/spa-not-found/")
class SpaNotFoundAdviceTest {

    private static final String SHELL_MARKER = "spa-not-found-test-shell";

    @Autowired MockMvc mvc;

    // Dilim ortak bean'leri (AuthInterceptor her @WebMvcTest diliminde kurulur) — bkz. ActivityControllerTest
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean AuditService auditService;
    @MockitoBean HttpMetricsService httpMetricsService;

    private static MockHttpSession signedIn() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        return s;
    }

    @Test
    @DisplayName("Bilinmeyen sayfa (Accept: text/html) → 404 + SPA kabuğu (text/html), no-store, CSP değişmeden")
    void unknownPage_servesShellWith404() throws Exception {
        for (String path : new String[]{"/foo", "/x/y", "/eski-sayfa.html", "/certificates/example.com"}) {
            MvcResult r = mvc.perform(get(path).accept(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML))
                    .andExpect(status().isNotFound())
                    .andReturn();
            MockHttpServletResponse res = r.getResponse();
            assertThat(res.getContentType()).as(path).startsWith("text/html");
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).as(path).contains(SHELL_MARKER).contains("<div id=\"root\">");
            assertThat(res.getHeader("Cache-Control")).as(path).contains("no-store").doesNotContain("max-age").doesNotContain("public");
            assertThat(res.getHeader("Content-Security-Policy")).as(path).contains("script-src 'self';");
            assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }

    @Test
    @DisplayName("Eksik statik varlık → düz metin 404 (HTML DEĞİL — modül yüklemesi HTML almasın); 1 yıllık immutable 404 olmaz")
    void missingAsset_isPlain404() throws Exception {
        for (String path : new String[]{"/assets/index-deadbeef.js", "/assets/index-deadbeef.css", "/assets/x.js.map",
                "/brand/logo-nope-32.png", "/fonts/Nope.woff2", "/nope.svg", "/favicon-999.png", "/site.webmanifest"}) {
            MvcResult r = mvc.perform(get(path).accept(MediaType.TEXT_HTML, MediaType.ALL))
                    .andExpect(status().isNotFound())
                    .andReturn();
            MockHttpServletResponse res = r.getResponse();
            assertThat(res.getContentType()).as(path).startsWith("text/plain");
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).as(path).doesNotContain("<html").doesNotContain(SHELL_MARKER);
            // /assets/** için filtre "public, max-age=31536000, immutable" yazar — 404'te geçerli OLMAMALI
            assertThat(res.getHeader("Cache-Control")).as(path).contains("no-store").doesNotContain("immutable");
            assertThat(res.getHeaders("Cache-Control")).as(path).hasSize(1);
        }
    }

    @Test
    @DisplayName("Bilinmeyen /api (oturumlu) → JSON 404 code=NOT_FOUND, tarayıcı text/html istese bile; ileti istek dilinde")
    void unknownApi_isJson404() throws Exception {
        mvc.perform(get("/api/does-not-exist").session(signedIn()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).startsWith("application/json"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("GET /api/does-not-exist")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bulunamadı")));
        mvc.perform(get("/api/does-not-exist").session(signedIn()).header("X-Lang", "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("was not found")));
    }

    @Test
    @DisplayName("Oturumsuz bilinmeyen /api → kimlik kapısı ÖNCE: 401 (yol keşfi 404/401 farkından yapılamaz)")
    void unknownApi_withoutSession_staysUnauthorized() throws Exception {
        mvc.perform(get("/api/does-not-exist").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Değişmeyenler: / → index.html (200), var olan varlık 200")
    void existingPaths_unchanged() throws Exception {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        MvcResult asset = mvc.perform(get("/assets/app.js")).andExpect(status().isOk()).andReturn();
        assertThat(asset.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("spaNotFoundFixture");
        MvcResult index = mvc.perform(get("/index.html").accept(MediaType.TEXT_HTML)).andExpect(status().isOk()).andReturn();
        assertThat(index.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(SHELL_MARKER);
    }

    @Test
    @DisplayName("HTML istemeyen (Accept: */* / JSON) uzantısız bilinmeyen yol → düz metin 404")
    void unknownPath_withoutHtmlAccept_isPlain() throws Exception {
        MvcResult r = mvc.perform(get("/foo").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("text/plain");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SHELL_MARKER);
    }

    @Test
    @DisplayName("Sınıflandırma: API / varlık / sayfa / diğer")
    void classify() {
        assertThat(SpaNotFoundAdvice.classify("GET", "/api/x", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.API);
        assertThat(SpaNotFoundAdvice.classify("GET", "/api", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.API);
        assertThat(SpaNotFoundAdvice.classify("GET", "/apis", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.PAGE);
        assertThat(SpaNotFoundAdvice.classify("GET", "/assets/a", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.ASSET);
        assertThat(SpaNotFoundAdvice.classify("GET", "/x/LOGO.PNG", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.ASSET);
        assertThat(SpaNotFoundAdvice.classify("GET", "/old.html", "text/html,application/xhtml+xml")).isEqualTo(SpaNotFoundAdvice.Kind.PAGE);
        assertThat(SpaNotFoundAdvice.classify("HEAD", "/foo", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.PAGE);
        assertThat(SpaNotFoundAdvice.classify("GET", "/.env", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.PAGE);
        assertThat(SpaNotFoundAdvice.classify("POST", "/foo", "text/html")).isEqualTo(SpaNotFoundAdvice.Kind.OTHER);
        assertThat(SpaNotFoundAdvice.classify("GET", "/foo", "*/*")).isEqualTo(SpaNotFoundAdvice.Kind.OTHER);
        assertThat(SpaNotFoundAdvice.classify("GET", "/foo", null)).isEqualTo(SpaNotFoundAdvice.Kind.OTHER);
        assertThat(SpaNotFoundAdvice.parseLocations(" file:./frontend/dist/ , classpath:/static,,"))
                .containsExactly("file:./frontend/dist/", "classpath:/static/");
    }

    @Test
    @DisplayName("Arayüz paketlenmemişse (index.html yok) sayfa isteği de düz metin 404 — hata/yığın yok")
    void noShell_fallsBackToPlain() throws Exception {
        SpaNotFoundAdvice advice = new SpaNotFoundAdvice(new DefaultResourceLoader(), "classpath:/no-such-dir/,file:./no-such-dir/");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/foo");
        req.addHeader("Accept", "text/html");
        MockHttpServletResponse res = new MockHttpServletResponse();
        advice.handleNoResource(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/foo", "/foo"), req, res);
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentType()).startsWith("text/plain");
        assertThat(advice.indexHtml()).isNull();
    }

    @Test
    @DisplayName("Kabuk önbelleği: aynı dosya ikinci kez okunmaz, içerik aynı")
    void shell_isCachedAndStable() {
        SpaNotFoundAdvice advice = new SpaNotFoundAdvice(new DefaultResourceLoader(), "classpath:/spa-not-found/");
        byte[] a = advice.indexHtml();
        byte[] b = advice.indexHtml();
        assertThat(a).isNotNull();
        assertThat(new String(a, StandardCharsets.UTF_8)).contains(SHELL_MARKER);
        assertThat(b).isEqualTo(a);
    }

    @Test
    @DisplayName("site.webmanifest MIME eşlemesi application.properties'ten ServerProperties'e BAĞLANIR (anahtar yazımı doğru)")
    void webmanifestMimeMapping_binds() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            props.load(in);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("server.mime-mappings.")) map.put(k, props.getProperty(k));
        }
        ServerProperties server = new Binder(new MapConfigurationPropertySource(map))
                .bind("server", ServerProperties.class).orElseGet(ServerProperties::new);
        assertThat(server.getMimeMappings().get("webmanifest")).isEqualTo("application/manifest+json");
    }
}
