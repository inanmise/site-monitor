package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dosya okuma taraması — 2026-10-31 (k6 inceleme A1) eklemeleri: dolaylı {@code open} ({@code open-indirect}),
 * {@code handleSummary} (k6 döndürülen anahtarları dosya yolu sayıp YAZAR — ölçüldü: {@code --summary-export} ile
 * birlikte ikisi de yazılır), {@code k6/net/grpc} proto yükleme, yeni sistem yolları ve varsayılan politika BLOCK.
 *
 * <p>Tarama SEZGİSELDİR (kalıcı savunma işlem sertleştirmesidir — {@code ScriptedProcessHardening}). Bilinen kaçırmalar
 * aşağıda {@link #knownFalseNegatives} ile belgelenir: kod üretimiyle kurulan ad ({@code eval}, {@code Function}) ya da
 * değişkende biriktirilen metin taramadan geçer.
 */
class ScriptedFileReadScanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "const o = globalThis.open; o('/x');",
            "this.open('x')",
            "self . open('x')",
            "const f = open; f('/etc/passwd');",
            "open.call(null, 'x')",
            "const { a } = { a: open };",
            "globalThis['op' + 'en']('x')",
            "globalThis[\"o\" + \"pen\"]('x')",
            "globalThis[`open`]('x')",
            "globalThis['open']('x')",
    })
    @DisplayName("Dolaylı open → open-indirect")
    void indirectOpen(String script) {
        assertThat(ScriptedCheckerService.scanFileReads(script)).contains(ScriptedCheckerService.FR_HIT_OPEN_INDIRECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "const isOpen = true;",
            "socket.open(url);",
            "const o = { open: 1 };",
            "check(r, { 'open kapı': (x) => x.status === 200 });",
            "const m = obj['opener'];",
            "const k = arr['op'];",
            "// globalThis.open('x')",
            "http.get('https://x.example.com/open/items');",
    })
    @DisplayName("Yanlış pozitif değil")
    void notIndirect(String script) {
        assertThat(ScriptedCheckerService.scanFileReads(script)).doesNotContain(ScriptedCheckerService.FR_HIT_OPEN_INDIRECT,
                ScriptedCheckerService.FR_HIT_OPEN);
    }

    @Test
    void handleSummary_isFileWrite() {
        assertThat(ScriptedCheckerService.scanFileReads(
                "export function handleSummary(data) { return { '/tmp/x.txt': 'y' }; }"))
                .contains(ScriptedCheckerService.FR_HIT_HANDLE_SUMMARY);
        assertThat(ScriptedCheckerService.scanFileReads("export const handleSummary = (d) => ({ stdout: 'x' });"))
                .containsExactly(ScriptedCheckerService.FR_HIT_HANDLE_SUMMARY);
        // ad metin içinde ya da başka tanıtıcının parçası → değil
        assertThat(ScriptedCheckerService.scanFileReads("const s = 'handleSummary'; const myhandleSummary2 = 1;")).isEmpty();
    }

    @Test
    void grpcProtoLoad() {
        assertThat(ScriptedCheckerService.scanFileReads(
                "import grpc from 'k6/net/grpc'; const c = new grpc.Client(); c.load(['defs'], 'hello.proto');"))
                .contains(ScriptedCheckerService.FR_HIT_GRPC_PROTO);
        assertThat(ScriptedCheckerService.scanFileReads(
                "import grpc from 'k6/net/grpc'; const c = new grpc.Client(); c.loadProtoset('set.pb');"))
                .contains(ScriptedCheckerService.FR_HIT_GRPC_PROTO);
        // modül yoksa .load( başka bir şeydir
        assertThat(ScriptedCheckerService.scanFileReads("page.load('x');")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"'/var/log/app.log'", "'/var/lib/kubelet'", "'/dev/shm/x'", "\"/proc/self/environ\"", "`/tmp/`"})
    void systemPaths(String literal) {
        assertThat(ScriptedCheckerService.scanFileReads("const p = " + literal + ";"))
                .contains(ScriptedCheckerService.FR_HIT_SYSTEM_PATH);
    }

    @Test
    @DisplayName("Bilinen kaçırmalar (sezgisel tarama) — belge niteliğinde; değişirse belgeyi de güncelle")
    void knownFalseNegatives() {
        assertThat(ScriptedCheckerService.scanFileReads("const n = ['o','p','e','n'].join(''); globalThis[n]('x');"))
                .isEmpty();
        assertThat(ScriptedCheckerService.scanFileReads("new Function('return op' + 'en')()('x');")).isEmpty();
    }

    @Test
    void bracketScannerIsBounded() {
        String s = "x[" + "'a' + ".repeat(200_000) + "'b']";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ScriptedCheckerService.scanFileReads(s));
        String many = "[".repeat(300_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ScriptedCheckerService.scanFileReads(many));
    }

    @Test
    @DisplayName("Varsayılan politika BLOCK (ayar yazılmamışsa)")
    void defaultPolicyIsBlock() {
        var appSettings = mock(AppSettingsService.class);
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, appSettings,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new ProxySettings());
        svc.init();
        assertThat(ScriptedCheckerService.FILE_READ_POLICY_DEFAULT).isEqualTo("BLOCK");
        assertThat(svc.fileReadBlocking()).isTrue();
        var res = svc.runProcess("export function handleSummary(d) { return {}; }", List.of(), 10,
                ScriptedCheckerService.ProxyUse.DIRECT);
        assertThat(res.status()).isEqualTo(ScriptedCheckerService.STATUS_SKIPPED);
        assertThat(res.failureReason()).isEqualTo("SCRIPT_FILE_READ_BLOCKED");
    }

    @Test
    @DisplayName("Rapor: şablonlar da taranır; politika metni BLOCK")
    void auditScansTemplates() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(1L); m.setName("izleme"); m.setTeamId(5L);
        m.setScript("export default () => {};");
        ScriptedTemplate t = new ScriptedTemplate();
        t.setId(9L); t.setName("veri dosyalı şablon"); t.setTeamId(5L);
        t.setScript("const d = open('./d.json');");
        var repo = mock(ScriptedMonitorRepository.class);
        when(repo.findAll()).thenReturn(List.of(m));
        var tRepo = mock(ScriptedTemplateRepository.class);
        when(tRepo.findAllActive()).thenReturn(List.of(t));
        var settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        var audit = new ScriptedFileReadAudit(repo, tRepo, settings);
        assertThat(audit.scan()).isEmpty();
        assertThat(audit.scanTemplates()).singleElement().satisfies(x -> {
            assertThat(x.id()).isEqualTo(9L);
            assertThat(x.hits()).containsExactly(ScriptedCheckerService.FR_HIT_OPEN);
        });
        assertThat(audit.policy()).isEqualTo("BLOCK");
        audit.logOnStartup();
        // şablon deposu yoksa boş
        assertThat(new ScriptedFileReadAudit(repo, settings).scanTemplates()).isEmpty();
    }
}
