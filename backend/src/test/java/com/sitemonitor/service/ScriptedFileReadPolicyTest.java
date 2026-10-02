package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sentetik betikte dosya okuma (2026-10-01, onaylı öneri 1: "önce raporla, sorun yoksa engelle").
 *
 * <p>k6 uygulamayla aynı kullanıcıyla çalışır; init bağlamındaki {@code open('/proc/1/environ')} pod'un gizli
 * değerlerini okuyabilir. Pinlenen sözleşme:
 * <ul>
 *   <li>Tarayıcı {@code open()} (doğrudan ve köşeli parantezle), {@code k6/fs}, yerel dosya importu ve sistem dosya
 *       yolunu bulur; yorumdaki ve metin içindeki benzer sözcüklere takılmaz; {@code 'https://…'} sonrasına aynı
 *       satırda yazılmış {@code open()} gizlenemez.</li>
 *   <li>Varsayılan REPORT: koşum DEĞİŞMEZ. BLOCK: dosya okuyan betik yürütülmez ("yürütülmedi" — kayıt/alarm yok).</li>
 *   <li>Rapor servisi yalnız dosya okuyan betikleri listeler.</li>
 * </ul>
 */
class ScriptedFileReadPolicyTest {

    @Test
    @DisplayName("Tarayıcı: dosya okuma yapılarını bulur")
    void scan_findsFileReads() {
        assertThat(ScriptedCheckerService.scanFileReads("const e = open('/proc/1/environ');"))
                .contains("open()", "sistem dosya yolu");
        assertThat(ScriptedCheckerService.scanFileReads("const d = open(\"./data.json\");")).containsExactly("open()");
        assertThat(ScriptedCheckerService.scanFileReads("globalThis['open']('x')")).containsExactly("open()");
        assertThat(ScriptedCheckerService.scanFileReads("import fs from 'k6/experimental/fs';")).containsExactly("k6/fs");
        assertThat(ScriptedCheckerService.scanFileReads("import x from '/etc/passwd';"))
                .contains("yerel dosya importu", "sistem dosya yolu");
        assertThat(ScriptedCheckerService.scanFileReads("const t = `/var/run/secrets/kubernetes.io/token`;"))
                .containsExactly("sistem dosya yolu");
        // URL'deki // yorum sanılıp aynı satırdaki open() gizlenemez
        assertThat(ScriptedCheckerService.scanFileReads("http.get('https://x.example.com'); open('/app/.env')"))
                .contains("open()");
    }

    @Test
    @DisplayName("Tarayıcı: yorumlar, metin içindeki sözcükler ve başka nesnenin open metodu yanlış pozitif değil")
    void scan_ignoresFalsePositives() {
        String script = String.join("\n",
                "import http from 'k6/http';",
                "import { check } from 'k6';",
                "// open('/etc/passwd') — eski not",
                "/* const x = open('/proc/self/environ'); */",
                "export default function () {",
                "  const r = http.get('https://example.com/api/open/items');",
                "  check(r, { 'kapı open (200)': (x) => x.status === 200 });",
                "  socket.open(url);",
                "}");
        assertThat(ScriptedCheckerService.scanFileReads(script)).isEmpty();
        assertThat(ScriptedCheckerService.scanFileReads(null)).isEmpty();
    }

    private ScriptedCheckerService service(String policy) {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var appSettings = mock(AppSettingsService.class);
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(eq(ScriptedCheckerService.FILE_READ_POLICY_KEY), anyString())).thenReturn(policy);
        var svc = new ScriptedCheckerService(null, null, appSettings, registry, new ProxySettings());
        svc.init();
        return svc;
    }

    @Test
    @DisplayName("BLOCK: dosya okuyan betik yürütülmez (SKIPPED + neden); dosya okumayan betik engele takılmaz")
    void block_skipsFileReadingScript() {
        var svc = service("BLOCK");
        assertThat(svc.fileReadBlocking()).isTrue();
        var res = svc.runProcess("open('/proc/1/environ')", List.of(), 10, ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(res.status()).isEqualTo(ScriptedCheckerService.STATUS_SKIPPED);
        assertThat(res.error()).contains("dosya okuyor").contains("open()");
        // dosya okumayan betik: engel değil, normal yol (test ortamında k6 yok → o nedenle atlanır)
        var normal = svc.runProcess("import http from 'k6/http'; export default () => http.get('https://x');", List.of(), 10, ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(normal.error()).doesNotContain("dosya okuyor");
    }

    @Test
    @DisplayName("REPORT (varsayılan): dosya okuyan betik de eskisi gibi koşum yoluna girer — davranış değişmez")
    void report_doesNotChangeExecution() {
        var svc = service("REPORT");
        assertThat(svc.fileReadBlocking()).isFalse();
        var res = svc.runProcess("open('/proc/1/environ')", List.of(), 10, ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(res.error()).doesNotContain("dosya okuyor");
    }

    @Test
    @DisplayName("Rapor: yalnız dosya okuyan betikler; pasif izleme de listelenir")
    void audit_listsOnlyFileReaders() {
        ScriptedMonitor a = new ScriptedMonitor();
        a.setId(1L); a.setName("Giriş akışı"); a.setTeamId(5L);
        a.setScript("import http from 'k6/http'; export default () => http.get('https://x');");
        ScriptedMonitor b = new ScriptedMonitor();
        b.setId(2L); b.setName("Veri dosyalı"); b.setTeamId(7L); b.setActive(false);
        b.setScript("const users = JSON.parse(open('./users.json'));");
        var repo = mock(ScriptedMonitorRepository.class);
        when(repo.findAll()).thenReturn(List.of(a, b));
        var settings = mock(AppSettingsService.class);
        when(settings.getString(eq(ScriptedCheckerService.FILE_READ_POLICY_KEY), anyString())).thenReturn("report");
        var audit = new ScriptedFileReadAudit(repo, settings);

        var f = audit.scan();
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(2L);
            assertThat(x.active()).isFalse();
            assertThat(x.hits()).containsExactly("open()");
        });
        assertThat(audit.policy()).isEqualTo("REPORT");
        audit.logOnStartup();   // fırlatmaz
    }
}
