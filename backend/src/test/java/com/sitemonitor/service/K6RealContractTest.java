package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GERÇEK k6 SÖZLEŞME TESTLERİ (2026-10-29, k6 0.49 → 2.3 geçişi, A13). Diğer bütün senaryo testleri k6'yı taklit eder;
 * bu sınıf iki gerçek ikiliyi (eski = {@code K6_BIN_LEGACY}, aday = {@code K6_BIN_NEXT}) yerel bir HTTP sunucusuna karşı
 * koşturur ve motorun dayandığı sözleşmeyi doğrular: bayraklar, {@code --summary-export} biçimi, çıkış kodları, çökme ve
 * "Request Failed" satırları, sözdizimi hatasının satır:sütunu, tek iterasyon kuralı, eklenti engeli, API portu, User-Agent.
 *
 * <p>Yalnız {@code -Pk6-real} ile koşar (CI işi "backend-k6-contract"); ikili tanımlı değilse atlanır,
 * {@code K6_REAL_REQUIRED=true} iken eksik ikili TESTİ DÜŞÜRÜR.
 */
@Tag("k6-real")
class K6RealContractTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicInteger hits = new AtomicInteger();
    private static final List<String> userAgents = new CopyOnWriteArrayList<>();

    record Engine(String role, String bin) {
        @Override public String toString() { return role; }
    }

    static Stream<Engine> engines() {
        List<Engine> out = new ArrayList<>();
        String legacy = System.getenv("K6_BIN_LEGACY");
        String next = System.getenv("K6_BIN_NEXT");
        if (legacy != null && !legacy.isBlank()) out.add(new Engine("legacy", legacy.trim()));
        if (next != null && !next.isBlank()) out.add(new Engine("next", next.trim()));
        if ("true".equalsIgnoreCase(System.getenv("K6_REAL_REQUIRED"))) {
            assertThat(out).as("K6_REAL_REQUIRED=true ama K6_BIN_LEGACY / K6_BIN_NEXT tanımlı değil").hasSize(2);
        }
        Assumptions.assumeFalse(out.isEmpty(), "gerçek k6 ikilisi tanımlı değil (K6_BIN_LEGACY / K6_BIN_NEXT)");
        return out.stream();
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            hits.incrementAndGet();
            userAgents.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            byte[] body = "merhaba".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "k6-contract-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    private final Map<String, String> settingsOverrides = new ConcurrentHashMap<>();

    private ScriptedCheckerService service(Engine e) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (ScriptedCheckerService.K6_BIN_KEY.equals(key)) return e.bin();
            if (ScriptedCheckerService.K6_BIN_CANDIDATE_KEY.equals(key)) return "";
            if ("site.monitor.scripted.syntax-check-policy".equals(key)) return "BLOCK";
            return settingsOverrides.getOrDefault(key, i.getArgument(1));
        });
        when(s.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        SsrfGuard guard = mock(SsrfGuard.class);
        when(guard.blacklistCidrs()).thenReturn(List.of());   // yerel test sunucusu (127.0.0.1) engellenmesin
        ScriptedCheckerService svc = new ScriptedCheckerService(guard, null, s, new SimpleMeterRegistry(), new ProxySettings());
        svc.init();
        assertThat(svc.isAvailable()).as("k6 çalıştırılamadı: " + e.bin()).isTrue();
        return svc;
    }

    private static String env(String... kv) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"name\":\"").append(kv[i]).append("\",\"value\":\"").append(kv[i + 1]).append("\",\"secret\":false}");
        }
        return sb.append(']').toString();
    }

    private ScriptedCheckerService.ScriptedResult run(ScriptedCheckerService svc, String script) {
        return svc.test(script, env("U", base), 60, "OFF");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Sürüm yoklaması + geçen betik: PASS, check / istek / faz / sürüm alanları dolu")
    void passingScript(Engine e) {
        ScriptedCheckerService svc = service(e);
        assertThat(svc.version()).startsWith("v");
        var r = run(svc, """
                import http from 'k6/http';
                import { check } from 'k6';
                export default function () {
                  const res = http.get(__ENV.U + '/ok', { timeout: '10s' });
                  check(res, { 'durum 200': (x) => x.status === 200 });
                }
                """);
        assertThat(r.status()).as(r.error()).isEqualTo("PASS");
        assertThat(r.exitCode()).isZero();
        assertThat(r.checksPassed()).isEqualTo(1);
        assertThat(r.checksFailed()).isZero();
        assertThat(r.phases().httpReqs()).isEqualTo(1L);
        assertThat(r.phases().dataSent()).isPositive();
        assertThat(r.phases().waitingMs()).isNotNull();
        assertThat(r.checksJson()).contains("durum 200");
        assertThat(r.k6Version()).isEqualTo(svc.version());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Düşen check → FAIL; eşik ihlali → çıkış 99 FAIL")
    void failingCheckAndThreshold(Engine e) {
        ScriptedCheckerService svc = service(e);
        var r = run(svc, """
                import http from 'k6/http';
                import { check } from 'k6';
                export default function () { check(http.get(__ENV.U + '/ok', { timeout: '10s' }), { 'yanlış': () => false }); }
                """);
        assertThat(r.status()).isEqualTo("FAIL");
        var t = run(svc, """
                import http from 'k6/http';
                export const options = { thresholds: { http_req_duration: ['p(95)<0'] } };
                export default function () { http.get(__ENV.U + '/ok', { timeout: '10s' }); }
                """);
        assertThat(t.exitCode()).isEqualTo(99);
        assertThat(t.status()).isEqualTo("FAIL");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("default() içinde istisna: k6 çıkış 0 verir ama çökme izi okunur → ERROR")
    void crashIsError(Engine e) {
        ScriptedCheckerService svc = service(e);
        var r = run(svc, """
                import http from 'k6/http';
                export default function () { http.get(__ENV.U + '/ok', { timeout: '10s' }); tanimsiz.x(); }
                """);
        assertThat(r.exitCode()).isZero();
        assertThat(r.status()).isEqualTo("ERROR");
        assertThat(r.error()).contains("ReferenceError");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Başarısız istek: 'Request Failed' satırı hata metnine taşınır")
    void requestFailedLine(Engine e) throws Exception {
        ScriptedCheckerService svc = service(e);
        int closed;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closed = ss.getLocalPort(); }
        var r = svc.test("""
                import http from 'k6/http';
                import { check } from 'k6';
                export default function () { check(http.get('http://127.0.0.1:%d/x', { timeout: '5s' }), { ok: (x) => x.status === 200 }); }
                """.formatted(closed), "[]", 60, "OFF");
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(r.error()).contains("Request Failed");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("exec.test.fail(): aday 110 → FAIL; eski sürümde bu API yok → çökme → ERROR")
    void testFail(Engine e) {
        ScriptedCheckerService svc = service(e);
        var r = run(svc, """
                import exec from 'k6/execution';
                import { check } from 'k6';
                export default function () { check(1, { bir: () => true }); exec.test.fail('kasten'); }
                """);
        if ("next".equals(e.role())) {
            assertThat(r.exitCode()).isEqualTo(110);
            assertThat(r.status()).isEqualTo("FAIL");
            assertThat(r.error()).startsWith("Betik testi başarısız işaretledi");
        } else {
            assertThat(r.status()).isEqualTo("ERROR");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Betik vus / iterations / scenarios tanımlasa da TEK iterasyon koşar (--vus 1 --iterations 1)")
    void singleIteration(Engine e) {
        ScriptedCheckerService svc = service(e);
        int before = hits.get();
        var r = run(svc, """
                import http from 'k6/http';
                import { check } from 'k6';
                export const options = { vus: 5, iterations: 20,
                  scenarios: { s: { executor: 'constant-vus', vus: 3, duration: '5s' } } };
                export default function () { check(http.get(__ENV.U + '/ok', { timeout: '10s' }), { ok: (x) => x.status === 200 }); }
                """);
        assertThat(r.status()).as(r.error()).isEqualTo("PASS");
        assertThat(r.phases().httpReqs()).isEqualTo(1L);
        assertThat(hits.get() - before).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Sözdizimi hatası: kayıt doğrulaması satır:sütunla KESİN hata verir (BLOCK politikası)")
    void syntaxErrorBlocks(Engine e) {
        ScriptedCheckerService svc = service(e);
        var d = svc.validateScript("import http from 'k6/http';\nexport default function () {\n  const a = {;\n}\n", List.of());
        assertThat(d.blocking()).as(String.valueOf(d.warnings())).isNotNull().contains("Script derlenemedi");
        assertThat(ScriptedCheckerService.K6_LINE_COL.matcher(d.blocking()).find()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("k6/x eklentisi: kayıtta reddedilir; saklı betik koşulsa bile ağa çıkmadan hata (eklenti indirilmez)")
    void extensionNeverDownloaded(Engine e) {
        ScriptedCheckerService svc = service(e);
        String script = "import sql from 'k6/x/sql';\nexport default function () {}\n";
        assertThat(svc.validateScript(script, List.of()).blocking()).contains("k6/x/sql");
        long t0 = System.currentTimeMillis();
        var r = svc.test(script, "[]", 60, "OFF");
        assertThat(r.status()).isEqualTo("ERROR");
        assertThat(String.valueOf(r.error()) + r.outputTail()).doesNotContain("ingest.k6.io").doesNotContain("provision");
        assertThat(System.currentTimeMillis() - t0).isLessThan(30_000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("REST API portu: koşum sırasında 6565 dinlenmez (eski: efemer --address, aday: bayraksız kapalı)")
    void noApiPortListening(Engine e) throws Exception {
        ScriptedCheckerService svc = service(e);
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> run(svc, """
                import { sleep, check } from 'k6';
                export default function () { sleep(3); check(1, { ok: () => true }); }
                """));
        Thread.sleep(1500);
        boolean free;
        try (ServerSocket ss = new ServerSocket(6565, 1, InetAddress.getLoopbackAddress())) {
            free = true;
        } catch (java.io.IOException busy) {
            free = false;
        }
        var r = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(free).as("k6 (" + e.role() + ") 127.0.0.1:6565'i dinliyor").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("User-Agent ayarı verilir; betik options.userAgent tanımlıyorsa betiğinki korunur")
    void userAgentSetting(Engine e) {
        ScriptedCheckerService svc = service(e);
        settingsOverrides.put(K6EngineSettings.KEY_USER_AGENT, "SM-Sozlesme/1");
        userAgents.clear();
        run(svc, """
                import http from 'k6/http';
                import { check } from 'k6';
                export default function () { check(http.get(__ENV.U + '/ok', { timeout: '10s' }), { ok: (x) => x.status === 200 }); }
                """);
        run(svc, """
                import http from 'k6/http';
                import { check } from 'k6';
                export const options = { userAgent: 'Betik/9' };
                export default function () { check(http.get(__ENV.U + '/ok', { timeout: '10s' }), { ok: (x) => x.status === 200 }); }
                """);
        assertThat(userAgents).containsExactly("SM-Sozlesme/1", "Betik/9");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("Koşum çalışma dizinine dosya bırakmaz (geçici betik / özet / CA dosyaları silinir)")
    void noTempLeftovers(Engine e) throws Exception {
        ScriptedCheckerService svc = service(e);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (var s = Files.list(tmp)) { before = s.filter(p -> p.getFileName().toString().startsWith("k6-")).count(); }
        run(svc, "import { check } from 'k6';\nexport default function () { check(1, { ok: () => true }); }\n");
        long after;
        try (var s = Files.list(tmp)) { after = s.filter(p -> p.getFileName().toString().startsWith("k6-")).count(); }
        assertThat(after).isLessThanOrEqualTo(before);
    }
}
