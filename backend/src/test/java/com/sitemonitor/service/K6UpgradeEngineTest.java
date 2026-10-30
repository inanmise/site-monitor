package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * k6 0.49 → 2.3 geçişinin motor tarafı (2026-10-29, Yayın A). Fikstürler GERÇEK ikililerin çıktısıdır
 * ({@code src/test/resources/k6-fixtures}: 0.49.0 ve 2.3.0 Windows ikilileriyle ölçüldü, yol {@code /tmp}'ye çevrildi).
 */
class K6UpgradeEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static String fixture(String name) throws IOException {
        try (InputStream in = K6UpgradeEngineTest.class.getResourceAsStream("/k6-fixtures/" + name)) {
            assertThat(in).as("fikstür yok: " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Çıkış kodu 110 (exec.test.fail) = FAIL — kullanıcı kararı 2026-10-29 ──────────────────────────

    @Test
    @DisplayName("110 (exec.test.fail): iki karar tablosunda da FAIL; zaman aşımı yine her şeyi ezer")
    void exit110IsFail() {
        assertThat(ScriptedCheckerService.decideStatus(110, false, 0)).isEqualTo("FAIL");
        assertThat(ScriptedCheckerService.decideStatus(110, false, 1, 0, false, true, true)).isEqualTo("FAIL");
        assertThat(ScriptedCheckerService.decideStatus(110, true, 0)).isEqualTo("TIMEOUT");
        // Bilinmeyen sıfır-dışı kod hâlâ ERROR
        assertThat(ScriptedCheckerService.decideStatus(111, false, 0)).isEqualTo("ERROR");
        assertThat(ScriptedCheckerService.exitCodeLabel(110, false)).contains("exec.test.fail").contains("110");
    }

    @Test
    @DisplayName("110 hata metni: 'Betik testi başarısız işaretledi' + k6'nın sebep satırı (gerçek 2.3 çıktısı)")
    void exit110Message() throws IOException {
        String out = fixture("run-testfail-230.txt");
        String msg = ScriptedCheckerService.summarizeError("FAIL", new ProcessProbe.Result(out, 110, false), out);
        assertThat(msg).startsWith("Betik testi başarısız işaretledi (exec.test.fail, çıkış 110)");
        assertThat(msg).contains("test marked as failed: kasten");
    }

    // ── Sözdizimi hata deseni: 2.x'in satır:sütunu tanınır (0.49 Babel biçimi 2026-10-30'da kaldırıldı) ─────────

    @Test
    @DisplayName("Sözdizimi hatası: 2.3 (Line a:b) ve yığın izi (script:a:b) KESİN hata; 0.49 Babel biçimi artık tanınmaz")
    void syntaxErrorPattern() throws IOException {
        String d230 = ScriptedCheckerService.validationDetail(fixture("archive-syntax-230.txt"), List.of());
        assertThat(ScriptedCheckerService.K6_LINE_COL.matcher(d230).find()).as(d230).isTrue();
        assertThat(d230).isEqualTo("script: Line 4:1 Unexpected token ; (and 1 more errors)");
        String crash = ScriptedCheckerService.validationDetail(fixture("run-crash-230.txt"), List.of());
        assertThat(ScriptedCheckerService.K6_LINE_COL.matcher(crash).find()).as(crash).isTrue();
        // İmajda yalnız 2.x var: Babel'in "(3:13)" biçimi kesin hata sayılmaz (deseni 2026-10-30'da kalktı).
        String d049 = ScriptedCheckerService.validationDetail(fixture("archive-syntax-049.txt"), List.of());
        assertThat(ScriptedCheckerService.K6_LINE_COL.matcher(d049).find()).isFalse();
    }

    @Test
    @DisplayName("Eklenti içe aktarımı (ortam kapalıyken) satır:sütun taşımaz → kesin hata değil, sınıfı MODULE")
    void extensionImportOutput() throws IOException {
        String d230 = ScriptedCheckerService.validationDetail(fixture("archive-ext-230.txt"), List.of());
        assertThat(d230).contains("unknown modules").contains("k6/x/sql");
        assertThat(d230).doesNotContain("ingest.k6.io");
    }

    // ── Özet dosyası: iki sürümde de AYNI biçim; yeni biçim tanınır ──────────────────────────────────

    @Test
    @DisplayName("--summary-export: 0.49 ve 2.3 aynı alanları verir (check, istek, faz, data)")
    void summaryParityAcrossVersions() throws IOException {
        for (String name : new String[] { "ok", "refused", "crash" }) {
            ScriptedCheckerService.Summary a = ScriptedCheckerService.parseSummary(fixture("summary-" + name + "-049.json"), mapper);
            ScriptedCheckerService.Summary b = ScriptedCheckerService.parseSummary(fixture("summary-" + name + "-230.json"), mapper);
            assertThat(b.parsed).as(name).isTrue();
            assertThat(b.unrecognizedSchema).as(name).isFalse();
            assertThat(b.checksPassed).as(name).isEqualTo(a.checksPassed);
            assertThat(b.checksFailed).as(name).isEqualTo(a.checksFailed);
            assertThat(b.httpReqs).as(name).isEqualTo(a.httpReqs);
            assertThat(b.httpReqFailed).as(name).isEqualTo(a.httpReqFailed);
            assertThat(b.checksJson).as(name).isEqualTo(a.checksJson);
            assertThat(b.blockedMs == null).as(name).isEqualTo(a.blockedMs == null);
            assertThat(b.dataSent == null).as(name).isEqualTo(a.dataSent == null);
        }
    }

    @Test
    @DisplayName("Yeni makine-okunur özet biçimi tanınır (sessizce 'metrik yok' denmez)")
    void newSummarySchemaRecognized() throws IOException {
        ScriptedCheckerService.Summary s = ScriptedCheckerService.parseSummary(fixture("summary-newschema-230.json"), mapper);
        assertThat(s.parsed).isFalse();
        assertThat(s.unrecognizedSchema).isTrue();
        assertThat(ScriptedCheckerService.SUMMARY_SCHEMA_UNRECOGNIZED).contains("K6_NEW_MACHINE_READABLE_SUMMARY");
    }

    @Test
    @DisplayName("2.3 çıktısı: çökme sinyali ve 'Request Failed' satırı 0.49 ile aynı okunur")
    void crashAndRequestFailedParity() throws IOException {
        assertThat(ScriptedCheckerService.hasScriptCrash(fixture("run-crash-049.txt"))).isTrue();
        assertThat(ScriptedCheckerService.hasScriptCrash(fixture("run-crash-230.txt"))).isTrue();
        assertThat(ScriptedCheckerService.hasScriptCrash(fixture("run-refused-230.txt"))).isFalse();
        assertThat(ScriptedCheckerService.extractErrorLines(fixture("run-refused-230.txt")))
                .contains("Request Failed").contains("127.0.0.1:1");
        assertThat(ScriptedCheckerService.extractErrorLines(fixture("run-refused-049.txt")))
                .contains("Request Failed").contains("127.0.0.1:1");
        assertThat(ScriptedCheckerService.hasScriptCrash(fixture("run-ok-230.txt"))).isFalse();
    }

    // ── Güvenlik ortamı + GODEBUG + User-Agent ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Her k6 sürecine üç güvenlik değişkeni; kullanıcı env'i bunları ezemez; GODEBUG ayardan")
    void securityEnvAndGodebug() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(s.getString(K6EngineSettings.KEY_GODEBUG, "")).thenReturn("tlsrsakex=1,bilinmeyen=1");
        var svc = new ScriptedCheckerService(null, null, s, null, new ProxySettings());
        List<String> secrets = new ArrayList<>();
        Map<String, String> env = svc.buildProcessEnv(List.of(
                new ScriptedCheckerService.EnvVar("K6_AUTO_EXTENSION_RESOLUTION", "true", false),
                new ScriptedCheckerService.EnvVar("GODEBUG", "tlsmlkem=0", false),
                new ScriptedCheckerService.EnvVar("BASE_URL", "https://x", false)),
                ScriptedCheckerService.ProxyUse.DIRECT, null, secrets);
        assertThat(env).containsEntry("K6_AUTO_EXTENSION_RESOLUTION", "false")
                .containsEntry("K6_NO_USAGE_REPORT", "true")
                .containsEntry("K6_NEW_MACHINE_READABLE_SUMMARY", "false")
                .containsEntry("GODEBUG", "tlsrsakex=1")
                .containsEntry("BASE_URL", "https://x");
    }

    @Test
    @DisplayName("GODEBUG ayarı boşken değişken HİÇ verilmez")
    void noGodebugWhenBlank() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, s, null, new ProxySettings());
        Map<String, String> env = svc.buildProcessEnv(null, ScriptedCheckerService.ProxyUse.DIRECT, null, new ArrayList<>());
        assertThat(env).doesNotContainKey("GODEBUG");
        assertThat(ScriptedCheckerService.systemK6Env()).containsOnlyKeys(
                "K6_AUTO_EXTENSION_RESOLUTION", "K6_NO_USAGE_REPORT", "K6_NEW_MACHINE_READABLE_SUMMARY");
    }

    @Test
    @DisplayName("Betik options.userAgent tanımlıyorsa yönetici User-Agent'ı verilmez (env betik seçeneğini ezerdi)")
    void scriptUserAgentDetection() {
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "export const options = { userAgent: 'X/1' };\nexport default function () {}")).isTrue();
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "// options = { userAgent: 'X' }\nexport default function () {}")).isFalse();
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "http.get(u, { headers: { 'User-Agent': 'X' } });")).isFalse();
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(null)).isFalse();
        // inceleme L7: JSON biçimli anahtar ve atama da betiğin kendi User-Agent'ı sayılır; karşılaştırma sayılmaz
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "export const options = { \"userAgent\": \"X/1\" };")).isTrue();
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "export const options = {};\noptions.userAgent = 'X/1';")).isTrue();
        assertThat(ScriptedCheckerService.scriptDefinesUserAgent(
                "if (options.userAgent === 'X') {}")).isFalse();
    }

    // ── Desteklenen en eski sürüm (2026-10-30, Yayın C) ───────────────────────────────────────────────

    @Test
    @DisplayName("Desteklenmeyen etkin ikili (0.49): k6 kullanılamaz, sürüm 'desteklenmiyor' için korunur; 2.3 kullanılabilir")
    void unsupportedActiveDisablesK6() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, s, null, new ProxySettings());
        K6Profile v049 = K6Profile.fromVersionOutput("k6", "k6 v0.49.0", 1L);
        K6Profile v230 = K6Profile.fromVersionOutput("k6", "k6 v2.3.0", 1L);

        svc.setProfilesForTest(v049, null, null);
        assertThat(svc.isAvailable()).isFalse();
        assertThat(svc.version()).isNull();
        assertThat(svc.unsupportedVersion()).isEqualTo("v0.49.0");
        // Kontrol satırı / teşhis yanıtı "bulunamadı" demez: ikili var, sürümü eski (inceleme 2026-10-30).
        assertThat(svc.unavailableText()).contains("v0.49.0").contains("v2.0.0").doesNotContain("bulunamadı");
        assertThat(svc.unavailableMessage()).contains("v0.49.0").contains("v2.0.0").contains("k6 motoru");
        assertThat(svc.unavailableReason()).isEqualTo("k6 sürümü v0.49.0 desteklenmiyor (en az v2.0.0 gerekir)");

        svc.setProfilesForTest(v230, null, null);
        assertThat(svc.isAvailable()).isTrue();
        assertThat(svc.version()).isEqualTo("v2.3.0");
        assertThat(svc.unsupportedVersion()).isNull();

        svc.setProfilesForTest(K6Profile.unavailable("k6", 1L), null, null);
        assertThat(svc.unsupportedVersion()).isNull();
        assertThat(svc.unavailableText()).isEqualTo("k6 bulunamadı — Sentetik İzleme devre dışı");
        assertThat(svc.unavailableMessage()).contains("k6 bulunamadı");
        assertThat(svc.unavailableReason()).isEqualTo("k6 bulunamadı");
    }

    @Test
    @DisplayName("Koşamayan motor koşturulmaz (inceleme L2): yok / desteklenmeyen etkin profil → SKIPPED + neden, süreç başlamaz")
    void executeRefusesUnrunnableEngine() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(s.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, s, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new ProxySettings());
        svc.init();
        String script = "export default function () {}";

        // Etkin ayar "k6" ve yoklanan profil "k6" ama sürümü 0.49: giriş kapısını geçen bir çağrı bile koşturmaz.
        svc.setProfilesForTest(K6Profile.fromVersionOutput("k6", "k6 v0.49.0 (commit/b5328aa782, go1.21.6, linux/amd64)", 1L),
                null, null);
        var r = svc.runProcess(script, List.of(), 10, ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(r.status()).isEqualTo(ScriptedCheckerService.STATUS_SKIPPED);
        assertThat(r.error()).contains("v0.49.0").contains("desteklenmiyor");
        assertThat(r.durationMs()).isNull();
        assertThat(r.k6Version()).isNull();

        svc.setProfilesForTest(K6Profile.unavailable("k6", 1L), null, null);
        var missing = svc.runProcess(script, List.of(), 10, ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(missing.status()).isEqualTo(ScriptedCheckerService.STATUS_SKIPPED);
        assertThat(missing.error()).isEqualTo("k6 bulunamadı — Sentetik İzleme devre dışı");
        assertThat(ScriptedCheckerService.unavailableText(null)).isEqualTo("k6 bulunamadı — Sentetik İzleme devre dışı");
    }

    @Test
    @DisplayName("Sözdizimi sürümü kalktı: şablon kuralları sürüm almaz, liste uçları k6_syntax_version yazmaz (kaynak kapısı)")
    void noSyntaxVersionAnywhere() throws IOException {
        String ctl = Files.readString(Path.of("src/main/java/com/sitemonitor/controller/ScriptedTemplateController.java"));
        assertThat(ctl).doesNotContain("syntaxVersion").doesNotContain("k6_syntax_version");
        String mon = Files.readString(Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java"));
        assertThat(mon).doesNotContain("k6_syntax_version").contains("\"k6_unsupported_version\"");
        String rules = Files.readString(Path.of("src/main/java/com/sitemonitor/service/ScriptedTemplateRules.java"));
        assertThat(rules).doesNotContain("Babel 6").doesNotContain("syntaxLevel");
    }

    @Test
    @DisplayName("Kontrol satırına yazılan k6 sürümü kolona sığdırılır")
    void k6VersionFits() {
        assertThat(SchedulerService.fitK6Version("v2.3.0")).isEqualTo("v2.3.0");
        assertThat(SchedulerService.fitK6Version(null)).isNull();
        assertThat(SchedulerService.fitK6Version("  ")).isNull();
        assertThat(SchedulerService.fitK6Version("x".repeat(50))).hasSize(20);
    }

    @Test
    @DisplayName("ScriptedResult: eski 14 bileşenli kurucu sürümü null bırakır")
    void resultBackCompat() {
        var r = new ScriptedCheckerService.ScriptedResult("PASS", true, 1L, 0, 1, 0, 1L, 1L, 1L, null, null, null,
                false, ScriptedCheckerService.Phases.EMPTY);
        assertThat(r.k6Version()).isNull();
    }
}
