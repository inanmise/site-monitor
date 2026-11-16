package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * İstek gövdesi sınırları (kapı incelemesi 2026-11-15): Jackson 3 belge boyunu sınırlamıyordu (metin 100M) — oturumsuz uçlar
 * ({@code /api/login}, kodla giriş, sorun / çökme bildirimi) gövdenin TAMAMINI ayrıştırıyordu; chunked gönderim
 * Content-Length denetimini de atlatır.
 *
 * <ul>
 *   <li>Oturumsuz uçlar: {@link PublicBodyLimitFilter} — Content-Length sınırı aşıyorsa gövde OKUNMADAN 413; uzunluk yoksa
 *       akış sayılır ve sınırda kesilir.</li>
 *   <li>Oturumlu uçlar: Jackson okuma kısıtı (32 MiB belge / 16 MiB metin) — meşru büyük gövde (10 MiB) geçer, aşan 413.</li>
 *   <li>Üretim ve test {@code application.properties} aynı değerleri taşır.</li>
 * </ul>
 */
@WebMvcTest(PublicJsonBodyLimitTest.EchoController.class)
@Import(PublicJsonBodyLimitTest.EchoController.class)
class PublicJsonBodyLimitTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    static final AtomicBoolean HANDLER_CALLED = new AtomicBoolean();

    @RestController
    static class EchoController {
        /** Oturumsuz uç (AuthInterceptor.PUBLIC) — gövde ayrıştırılırsa işleyici çağrılır. */
        @PostMapping("/api/login-help")
        Map<String, Object> publicEcho(@RequestBody Map<String, Object> body) {
            HANDLER_CALLED.set(true);
            return Map.of("success", true, "keys", body.size());
        }

        /** Oturumlu büyük gövde ucu. */
        @PostMapping("/api/test-echo/body")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            Object v = body.get("v");
            return Map.of("success", true, "len", v == null ? 0 : String.valueOf(v).length());
        }
    }

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(1L)));
        return s;
    }

    private static byte[] jsonWithString(int chars) {
        StringBuilder b = new StringBuilder(chars + 16);
        b.append("{\"v\":\"");
        for (int i = 0; i < chars; i++) b.append('a');
        b.append("\"}");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("oturumsuz uç: sınırı aşan Content-Length → 413, gövde ayrıştırılmaz (işleyici çağrılmaz)")
    void publicEndpoint_overCap_413WithoutParsing() throws Exception {
        HANDLER_CALLED.set(false);
        byte[] body = jsonWithString((int) PublicBodyLimitFilter.LOGIN_HELP_BYTES + 10);
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(HANDLER_CALLED).isFalse();
    }

    @Test
    @DisplayName("oturumsuz uç: sınır içindeki meşru gövde (5 görsel × ~1,4 MiB base64) geçer")
    void publicEndpoint_legitimateBodyPasses() throws Exception {
        HANDLER_CALLED.set(false);
        StringBuilder b = new StringBuilder("{\"message\":\"giris\",\"images\":[");
        String img = "data:image/png;base64," + "A".repeat(1_400_000);
        for (int i = 0; i < 5; i++) b.append(i == 0 ? "" : ",").append('"').append(img).append('"');
        b.append("]}");
        mvc.perform(post("/api/login-help").contentType(MediaType.APPLICATION_JSON).content(b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").value(2));
        assertThat(HANDLER_CALLED).isTrue();
    }

    @Test
    @DisplayName("oturumlu uç: 10 MiB metin (meşru büyük gövde) geçer; 16 MiB metin sınırını aşan → 413")
    void authenticatedEndpoint_jacksonStringLimit() throws Exception {
        mvc.perform(post("/api/test-echo/body").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithString(10 * 1024 * 1024)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.len").value(10 * 1024 * 1024));
        mvc.perform(post("/api/test-echo/body").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithString(16 * 1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("oturumlu uç: 32 MiB belge sınırını aşan gövde (küçük anahtarlar) → 413, OOM'a kadar okunmaz")
    void authenticatedEndpoint_jacksonDocumentLimit() throws Exception {
        StringBuilder b = new StringBuilder(34 * 1024 * 1024);
        b.append('{');
        int i = 0;
        while (b.length() < 33 * 1024 * 1024) {
            if (i > 0) b.append(',');
            b.append("\"k").append(i++).append("\":\"").append("x".repeat(200)).append('"');
        }
        b.append('}');
        mvc.perform(post("/api/test-echo/body").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content(b.toString()))
                .andExpect(status().isPayloadTooLarge());
    }

    // ── Süzgeç (MVC'siz) ───────────────────────────────────────────────────────

    /** Chunked gövde: Content-Length yok (-1); akış sayılır, sınır aşılınca okuma kesilir. */
    @Test
    @DisplayName("süzgeç: Content-Length olmayan (chunked) gövde sayılır — /api/login 64 KiB'yi aşınca okuma BodyLimitExceededException")
    void filter_chunkedBodyIsCounted() throws Exception {
        byte[] big = new byte[(int) PublicBodyLimitFilter.SMALL_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        req.setContent(big);
        List<HttpServletRequest> seen = new ArrayList<>();
        FilterChain chain = (r, s) -> seen.add((HttpServletRequest) r);
        new PublicBodyLimitFilter().doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(seen).hasSize(1);
        ServletInputStream in = seen.get(0).getInputStream();
        assertThatThrownBy(() -> drain(in)).isInstanceOf(PublicBodyLimitFilter.BodyLimitExceededException.class);

        // Sınır içindeki gövde tam okunur
        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/api/login");
        ok.setContent("{\"username\":\"a\",\"password\":\"b\"}".getBytes(StandardCharsets.UTF_8));
        seen.clear();
        new PublicBodyLimitFilter().doFilter(ok, new MockHttpServletResponse(), chain);
        assertThat(drain(seen.get(0).getInputStream())).isEqualTo(ok.getContentAsByteArray().length);
    }

    @Test
    @DisplayName("süzgeç: liste dışı yol ve GET dokunulmadan geçer; Content-Length sınırı aşan /api/login → 413, zincir çağrılmaz")
    void filter_scope() throws Exception {
        assertThat(PublicBodyLimitFilter.capFor("/api/login")).isEqualTo(PublicBodyLimitFilter.SMALL_BYTES);
        assertThat(PublicBodyLimitFilter.capFor("/api/login/otp/verify")).isEqualTo(PublicBodyLimitFilter.SMALL_BYTES);
        assertThat(PublicBodyLimitFilter.capFor("/api/admin/inventory/import")).isEqualTo(-1);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.setContent(new byte[(int) PublicBodyLimitFilter.SMALL_BYTES + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new PublicBodyLimitFilter().doFilter(req, res, (r, s) -> called.set(true));
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(called).isFalse();

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/other");
        other.setContent(new byte[(int) PublicBodyLimitFilter.LOGIN_HELP_BYTES + 1]);
        List<HttpServletRequest> seen = new ArrayList<>();
        new PublicBodyLimitFilter().doFilter(other, new MockHttpServletResponse(), (r, s) -> seen.add((HttpServletRequest) r));
        assertThat(seen.get(0)).isSameAs(other);
    }

    @Test
    @DisplayName("2026-11-15 arka uç inceleme: /api/login-help için TEK sınır 8 MiB (meşru ≤ ~7 MB geçer, eski ayrı süzgeç yok)")
    void loginHelpSingleCap() throws Exception {
        assertThat(PublicBodyLimitFilter.LOGIN_HELP_BYTES).isEqualTo(8L * 1024 * 1024);
        assertThat(PublicBodyLimitFilter.capFor("/api/login-help")).isEqualTo(8L * 1024 * 1024);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login-help");
        req.setContentType("application/json");
        req.setContent(new byte[(int) PublicBodyLimitFilter.LOGIN_HELP_BYTES + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new PublicBodyLimitFilter().doFilter(req, res, (r, s) -> called.set(true));
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"PAYLOAD_TOO_LARGE\"")
                .contains("8192 KB");
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("2026-11-15 arka uç inceleme: onay bağlantısı formunun sınır aşımı tarayıcıda HTML sayfası (ham JSON değil); diğer uçlar JSON kalır")
    void approvalLinkFormOverCap_isHtmlPage() throws Exception {
        for (String path : List.of("/api/weekly-reports/approve-link/reject", "/api/weekly-reports/approve-link/confirm",
                "/api/tech-error-reports/approve-link/reject", "/api/tech-error-reports/approve-link/confirm")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setContentType("application/x-www-form-urlencoded");
            req.setContent(new byte[(int) PublicBodyLimitFilter.SMALL_BYTES + 1]);
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            new PublicBodyLimitFilter().doFilter(req, res, (r, s) -> called.set(true));
            assertThat(res.getStatus()).as(path).isEqualTo(413);
            assertThat(res.getContentType()).as(path).startsWith("text/html");
            assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
            String body = res.getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as(path).startsWith("<!DOCTYPE html>").contains("Gönderilen form çok büyük").contains("Karar kaydedilmedi")
                    .doesNotContain("PAYLOAD_TOO_LARGE").doesNotContain("<script");
            assertThat(called).isFalse();
        }
        // İngilizce arayüz
        MockHttpServletRequest en = new MockHttpServletRequest("POST", "/api/weekly-reports/approve-link/reject");
        en.addHeader("X-Lang", "en");
        en.setContent(new byte[(int) PublicBodyLimitFilter.SMALL_BYTES + 1]);
        MockHttpServletResponse enRes = new MockHttpServletResponse();
        new PublicBodyLimitFilter().doFilter(en, enRes, (r, s) -> { });
        assertThat(enRes.getContentAsString(StandardCharsets.UTF_8)).contains("The form sent is too large").contains("lang='en'");
        // Sınır içindeki form gövdesi dokunulmadan geçer
        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/api/weekly-reports/approve-link/reject");
        ok.setContent("token=t&reason=Eksik".getBytes(StandardCharsets.UTF_8));
        AtomicBoolean passed = new AtomicBoolean();
        new PublicBodyLimitFilter().doFilter(ok, new MockHttpServletResponse(), (r, s) -> passed.set(true));
        assertThat(passed).isTrue();
        assertThat(PublicBodyLimitFilter.htmlFormPath("/api/login")).isFalse();
        assertThat(PublicBodyLimitFilter.htmlFormPath(null)).isFalse();
    }

    /** Content-Length'i bildirilmemiş (chunked) istek. */
    private static MockHttpServletRequest chunked(String path, String contentType, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        req.setContentType(contentType);
        req.setContent(body);
        return req;
    }

    @Test
    @DisplayName("2026-11-17 inceleme: uzunluğu bildirilmemiş (chunked) FORM / MULTIPART gövde sayılamaz (Tomcat sarmalayıcıyı "
            + "atlar) → OKUNMADAN 411; onay bağlantısında HTML, diğer uçlarda JSON LENGTH_REQUIRED; zincir çağrılmaz")
    void chunkedFormOrMultipart_isRefusedUnread() throws Exception {
        byte[] oversized = ("token=t&reason=" + "a".repeat((int) PublicBodyLimitFilter.SMALL_BYTES + 10))
                .getBytes(StandardCharsets.UTF_8);
        for (String path : List.of("/api/weekly-reports/approve-link/reject", "/api/tech-error-reports/approve-link/confirm")) {
            for (byte[] body : List.of(oversized, "token=t&reason=kisa".getBytes(StandardCharsets.UTF_8))) {
                MockHttpServletRequest req = chunked(path, "application/x-www-form-urlencoded; charset=UTF-8", body);
                MockHttpServletResponse res = new MockHttpServletResponse();
                AtomicBoolean called = new AtomicBoolean();
                new PublicBodyLimitFilter().doFilter(req, res, (r, s) -> called.set(true));
                assertThat(res.getStatus()).as(path).isEqualTo(411);
                assertThat(res.getContentType()).startsWith("text/html");
                assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("Form okunamadı").contains("Karar kaydedilmedi")
                        .doesNotContain("<script");
                assertThat(called).as(path).isFalse();
            }
        }
        for (String ct : List.of("multipart/form-data; boundary=x", "Multipart/Mixed", " application/X-WWW-Form-Urlencoded ")) {
            MockHttpServletRequest req = chunked("/api/login", ct, new byte[10]);
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            new PublicBodyLimitFilter().doFilter(req, res, (r, s) -> called.set(true));
            assertThat(res.getStatus()).as(ct).isEqualTo(411);
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"LENGTH_REQUIRED\"");
            assertThat(called).as(ct).isFalse();
        }
        // İngilizce
        MockHttpServletRequest en = chunked("/api/login", "multipart/form-data; boundary=x", new byte[10]);
        en.addHeader("X-Lang", "en");
        MockHttpServletResponse enRes = new MockHttpServletResponse();
        new PublicBodyLimitFilter().doFilter(en, enRes, (r, s) -> { });
        assertThat(enRes.getContentAsString(StandardCharsets.UTF_8)).contains("length was not declared");
    }

    @Test
    @DisplayName("2026-11-17 inceleme: chunked JSON hâlâ SAYILARAK geçer (411 değil); Content-Length'li form sınır içinde "
            + "dokunulmadan geçer; liste dışı yolda chunked form dokunulmaz")
    void chunkedJsonStillCounted_declaredFormPasses() throws Exception {
        List<HttpServletRequest> seen = new ArrayList<>();
        FilterChain chain = (r, s) -> seen.add((HttpServletRequest) r);
        MockHttpServletRequest json = chunked("/api/login", "application/json", "{\"username\":\"a\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        new PublicBodyLimitFilter().doFilter(json, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isInstanceOf(PublicBodyLimitFilter.Capped.class);

        seen.clear();
        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/api/weekly-reports/approve-link/reject");
        form.setContentType("application/x-www-form-urlencoded");
        form.setContent("token=t&reason=Eksik".getBytes(StandardCharsets.UTF_8));
        new PublicBodyLimitFilter().doFilter(form, new MockHttpServletResponse(), chain);
        assertThat(seen).hasSize(1);

        seen.clear();
        MockHttpServletRequest other = chunked("/api/admin/inventory/import", "multipart/form-data; boundary=x", new byte[10]);
        new PublicBodyLimitFilter().doFilter(other, new MockHttpServletResponse(), chain);
        assertThat(seen).containsExactly(other);

        assertThat(PublicBodyLimitFilter.containerParsedBody(null)).isFalse();
        assertThat(PublicBodyLimitFilter.containerParsedBody("application/json")).isFalse();
        assertThat(PublicBodyLimitFilter.containerParsedBody("text/plain")).isFalse();
        assertThat(PublicBodyLimitFilter.containerParsedBody("application/x-www-form-urlencoded;charset=UTF-8")).isTrue();
        assertThat(PublicBodyLimitFilter.containerParsedBody("multipart/form-data")).isTrue();
    }

    @Test
    @DisplayName("üretim ve test application.properties aynı Jackson okuma sınırlarını taşır; sınırlar meşru gövdelerin üstünde")
    void productionAndTestPropertiesAgree() throws IOException {
        Properties main = load(Path.of("src/main/resources/application.properties"));
        Properties test = load(Path.of("src/test/resources/application.properties"));
        for (String k : List.of("spring.jackson.factory.constraints.read.max-document-length",
                "spring.jackson.factory.constraints.read.max-string-length")) {
            assertThat(main.getProperty(k)).as(k).isNotBlank().isEqualTo(test.getProperty(k));
        }
        long doc = Long.parseLong(main.getProperty("spring.jackson.factory.constraints.read.max-document-length"));
        long str = Long.parseLong(main.getProperty("spring.jackson.factory.constraints.read.max-string-length"));
        // Meşru gövdeler: giriş sorun bildirimi (5 × 2 MiB), k6 betiği 512 K, envanter içe aktarımı (~20 MB)
        assertThat(doc).isGreaterThanOrEqualTo(PublicBodyLimitFilter.LOGIN_HELP_BYTES).isGreaterThanOrEqualTo(20L * 1024 * 1024);
        assertThat(str).isGreaterThan(com.sitemonitor.service.ScriptedSafetyRules.MAX_SCRIPT_CHARS * 4L);
    }

    private static Properties load(Path p) throws IOException {
        Properties out = new Properties();
        try (InputStream in = Files.newInputStream(p)) { out.load(in); }
        return out;
    }

    private static long drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) total += n;
        return total;
    }
}
