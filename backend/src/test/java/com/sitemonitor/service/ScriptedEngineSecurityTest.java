package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Senaryo motoru güvenlik maddeleri (2026-10-31, k6 inceleme A2 / A6 / A7): NUL'lu ortam değeri, uzak import SSRF
 * denetimi, "her yer" adresleri, alt süreç ortam izolasyonu, kesinti sonucu ve koşum dizini temizliği.
 */
class ScriptedEngineSecurityTest {

    private static Map<String, Object> var(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    @Test
    @DisplayName("NUL içeren ortam değeri kayıtta ve testte 400 (alan env); değer iletiye yazılmaz")
    void nulInEnvValueRejected() {
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForSave(List.of(var("TOKEN", "ab\0cd")), Map.of()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(ScriptedEnvPolicy.FIELD))
                .hasMessageContaining("TOKEN")
                .hasMessageNotContaining("zq");
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForTest(List.of(var("TOKEN", "x\0"))))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("Saklı eski NUL'lu değer koşumda ayıklanır (ProcessBuilder istisnası yerine)")
    void storedNulStrippedAtRuntime() {
        assertThat(ScriptedCheckerService.stripNul("A", "a\0b\0")).isEqualTo("ab");
        assertThat(ScriptedCheckerService.stripNul("A", "temiz")).isEqualTo("temiz");
        assertThat(ScriptedCheckerService.stripNul("A", null)).isEmpty();
    }

    @Test
    @DisplayName("Kara liste: 0.0.0.0 / :: (her yer) adresleri loopback kapalıyken listede (k6 ile ölçüldü)")
    void anyLocalAddressesBlacklisted() {
        assertThat(SsrfGuard.blacklistCidrs(true, false)).contains("0.0.0.0/32", "::/128", "127.0.0.0/8");
        assertThat(SsrfGuard.blacklistCidrs(true, true)).doesNotContain("0.0.0.0/32", "::/128");
    }

    @Test
    @DisplayName("Uzak import adresleri: yorumlar atlanır, tekil, tavanlı")
    void remoteImportUrls() {
        String s = String.join("\n",
                "import { a } from 'https://cdn.example.com/a.js';",
                "// import { b } from 'https://yorum.example.com/b.js';",
                "import c from \"https://cdn.example.com/a.js\";",
                "const d = require('https://other.example.org/d.js');",
                "const e = await import(`https://dyn.example.net/e.js`);",
                "import http from 'k6/http';");
        assertThat(ScriptedCheckerService.remoteImportUrls(s)).containsExactly(
                "https://cdn.example.com/a.js", "https://other.example.org/d.js", "https://dyn.example.net/e.js");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("import x").append(i).append(" from 'https://h").append(i).append(".example.com/m.js';\n");
        assertThat(ScriptedCheckerService.remoteImportUrls(many.toString())).hasSize(ScriptedCheckerService.MAX_REMOTE_IMPORTS);
        assertThat(ScriptedCheckerService.remoteImportUrls(null)).isEmpty();
    }

    private static ScriptedCheckerService serviceWithGuard(SsrfGuard guard) {
        var settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        return new ScriptedCheckerService(guard, null, settings,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new ProxySettings());
    }

    @Test
    @DisplayName("Uzak import SSRF: engelli host kayıtta / testte / koşumda yakalanır — k6'nın kara listesi indirmeyi KAPSAMAZ")
    void remoteImportGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(false);
        var svc = serviceWithGuard(new SsrfGuard(s));
        assertThat(svc.blockedRemoteImport("import { x } from 'https://127.0.0.1:18732/mod.js';")).startsWith("127.0.0.1 (");
        assertThat(svc.blockedRemoteImport("import { x } from 'https://169.254.169.254/latest/meta-data';"))
                .startsWith("169.254.169.254 (");
        // çözülemeyen host engel değil (k6 indiremez, kendi hatasını verir)
        assertThat(svc.blockedRemoteImport("import { x } from 'https://" + TestHosts.UNRESOLVABLE + "/m.js';")).isNull();
        assertThat(svc.blockedRemoteImport("import http from 'k6/http';")).isNull();
        // SsrfGuard yoksa (elle kurulan testler) denetim yok
        assertThat(serviceWithGuard(null).blockedRemoteImport("import { x } from 'https://127.0.0.1/m.js';")).isNull();
    }

    @Test
    @DisplayName("Tanılama araçları JVM ortamını miras almaz (yalnız izinli adlar)")
    void diagnosticToolsDoNotInheritJvmEnv() {
        ProcessBuilder pb = new ProcessBuilder("x");
        pb.environment().put("SITE_MONITOR_SECRET_KEY", "gizli");
        pb.environment().put("SPRING_DATASOURCE_PASSWORD", "parola");
        pb.environment().put("PATH", "/usr/bin");
        pb.environment().put("Path", "C:\\Windows");
        ProcessProbe.isolate(pb);
        assertThat(pb.environment()).doesNotContainKeys("SITE_MONITOR_SECRET_KEY", "SPRING_DATASOURCE_PASSWORD");
        assertThat(pb.environment().keySet().stream().anyMatch(k -> k.equalsIgnoreCase("PATH"))).isTrue();
    }

    @Test
    @DisplayName("Kesinti sonucu: interrupted bayrağı + kesme durumu korunur")
    void interruptedProbeResult() throws Exception {
        Thread.currentThread().interrupt();
        try {
            ProcessProbe.Result r = ProcessProbe.run(List.of(javaBin(), "-version"), Map.of(), null, 30, true, 1024, true, null);
            assertThat(r.interrupted()).isTrue();
            assertThat(r.exitCode()).isEqualTo(-1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        ProcessProbe.Result ok = ProcessProbe.run(List.of(javaBin(), "-version"), Map.of(), null, 30, true, 1024, true,
                p -> { throw new IllegalStateException("kanca patladı"); });
        assertThat(ok.interrupted()).isFalse();
        assertThat(ok.exitCode()).isZero();         // kanca hatası koşumu bozmaz
    }

    @Test
    @DisplayName("Koşum dizini: özyineli silinir (betiğin bıraktığı alt dizinler dahil)")
    void runDirDeletedRecursively() throws Exception {
        Path dir = ScriptedCheckerService.createRunDir();
        Files.writeString(dir.resolve("summary.json"), "{}");
        Path sub = Files.createDirectories(dir.resolve("a/b/c"));
        Files.writeString(sub.resolve("x.txt"), "y");
        ScriptedCheckerService.deleteTree(dir);
        assertThat(Files.exists(dir)).isFalse();
        ScriptedCheckerService.deleteTree(null);
        ScriptedCheckerService.deleteTree(dir);     // zaten yok — fırlatmaz
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    @DisplayName("Uzun koşum dizini yolu satır kırpmasında 'Line 4:1'i kesmez (Windows'ta ölçülen k6 2.3 çıktısı)")
    void longRunDirPathKeepsLineCol() {
        // logfmt kaçışlı Windows yolu: C:\\Users\\…\\k6-run-…\\ (k6'nın bastığı biçim)
        String dir = "C:\\\\Users\\\\7636\\\\AppData\\\\Local\\\\Temp\\\\k6-run-12345678901234567890\\\\";
        String url = "file:///C:/Users/7636/AppData/Local/Temp/k6-run-12345678901234567890/k6-script-98765432109876543210.js";
        String raw = "time=\"2026-10-31T19:18:10+03:00\" level=error msg=\"could not initialize '" + dir
                + "k6-script-98765432109876543210.js': could not load JS test '" + url + "': " + url
                + ": Line 4:1 Unexpected token ; (and 1 more errors)\"";
        String detail = ScriptedCheckerService.validationDetail(raw, List.of());
        assertThat(detail).contains("script: Line 4:1").doesNotContain("k6-run-").doesNotContain("Users");
        assertThat(ScriptedCheckerService.K6_LINE_COL.matcher(detail).find()).isTrue();
    }
}
