package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Saf/statik karar + ayrıştırma + tarama mantığı (Spring/k6 gerektirmez). */
class ScriptedCheckerServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("decideStatus: karar tablosu (PASS/FAIL/ERROR/TIMEOUT)")
    void decideStatus_table() {
        assertThat(ScriptedCheckerService.decideStatus(0, false, 0)).isEqualTo("PASS");
        assertThat(ScriptedCheckerService.decideStatus(0, false, 2)).isEqualTo("FAIL");   // exit 0 ama check kaldı
        assertThat(ScriptedCheckerService.decideStatus(99, false, 0)).isEqualTo("FAIL");  // k6 threshold
        assertThat(ScriptedCheckerService.decideStatus(107, false, 0)).isEqualTo("ERROR"); // script/runtime hatası
        assertThat(ScriptedCheckerService.decideStatus(-1, false, 0)).isEqualTo("ERROR");  // binary/çalıştırma hatası
        assertThat(ScriptedCheckerService.decideStatus(0, true, 0)).isEqualTo("TIMEOUT");  // timeout her şeyi ezer
        assertThat(ScriptedCheckerService.decideStatus(99, true, 5)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("parseSummary: geçerli k6 özeti → check sayıları + http/iteration süreleri + per-check listesi")
    void parseSummary_valid() {
        String json = "{\"metrics\":{\"checks\":{\"passes\":3,\"fails\":1},"
                + "\"http_req_duration\":{\"avg\":120.5,\"p(95)\":250.0},"
                + "\"iteration_duration\":{\"avg\":800.0}},"
                + "\"root_group\":{\"checks\":{\"status is 200\":{\"passes\":2,\"fails\":0},"
                + "\"body ok\":{\"passes\":1,\"fails\":1}}}}";
        ScriptedCheckerService.Summary s = ScriptedCheckerService.parseSummary(json, mapper);
        assertThat(s.checksPassed).isEqualTo(3);
        assertThat(s.checksFailed).isEqualTo(1);
        assertThat(s.httpReqAvgMs).isEqualTo(121L);   // round(120.5)
        assertThat(s.httpReqP95Ms).isEqualTo(250L);
        assertThat(s.iterationMs).isEqualTo(800L);
        assertThat(s.checksJson).contains("status is 200").contains("body ok")
                .contains("\"passed\":true").contains("\"passed\":false");
    }

    @Test
    @DisplayName("parseSummary: bozuk/boş JSON → istisna yok, boş özet")
    void parseSummary_brokenIsSafe() {
        assertThat(ScriptedCheckerService.parseSummary("{bozuk", mapper).checksPassed).isNull();
        assertThat(ScriptedCheckerService.parseSummary("", mapper).checksFailed).isNull();
        assertThat(ScriptedCheckerService.parseSummary("{}", mapper).checksJson).isNull();
    }

    // ── 2026-08 regresyonu: 289/289 koşum NPE ile düştü ve TANI TAMAMEN KAYBOLDU ──────────────
    // decideStatus(int) primitive imzası, Summary.checksFailed (Integer) null iken argüman
    // değerlendirilirken auto-unboxing NPE atıyordu. NPE dıştaki catch'e düşüp err()'e gidiyor,
    // err() ise exitCode/durationMs/outputTail'i siliyordu → k6'nın gerçek çıktısı hiç kaydedilmedi.

    @Test
    @DisplayName("REGRESYON: metrics.checks olmayan özet → parseSummary null bırakır, decideStatus NPE ATMAZ")
    void decideStatus_nullChecks_noNpe() {
        // k6 hiç check() çalıştırmadıysa özet JSON'unda checks düğümü HİÇ olmaz
        String noChecks = "{\"metrics\":{\"http_req_duration\":{\"avg\":10.0}}}";
        ScriptedCheckerService.Summary s = ScriptedCheckerService.parseSummary(noChecks, mapper);
        assertThat(s.checksFailed).isNull();   // ayrıştırıcı null'ı KORUR (sözleşme)

        assertThat(ScriptedCheckerService.decideStatus(0, false, s.checksPassed, s.checksFailed)).isEqualTo("PASS");
        assertThat(ScriptedCheckerService.decideStatus(107, false, s.checksPassed, s.checksFailed)).isEqualTo("ERROR");
        assertThat(ScriptedCheckerService.decideStatus(99, false, s.checksPassed, s.checksFailed)).isEqualTo("FAIL");
        assertThat(ScriptedCheckerService.decideStatus(0, true, s.checksPassed, s.checksFailed)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("REGRESYON: tamamen boş/bozuk özet → NPE yok; parsed=false ile 'hiç özet' ayırt edilir")
    void decideStatus_emptySummary_noNpe() {
        ScriptedCheckerService.Summary empty = ScriptedCheckerService.parseSummary("", mapper);
        assertThat(empty.parsed).isFalse();
        assertThat(ScriptedCheckerService.decideStatus(-1, false, empty.checksPassed, empty.checksFailed))
                .isEqualTo("ERROR");
        // geçerli özette parsed=true — "bozuk özet" ile "hiç özet" artık ayrışıyor
        assertThat(ScriptedCheckerService.parseSummary("{\"metrics\":{}}", mapper).parsed).isTrue();
    }

    @Test
    @DisplayName("REGRESYON: beklenmeyen istisnada exitCode/durationMs/outputTail KAYBOLMAZ")
    void buildResult_keepsDiagnostics() {
        var r = new ProcessProbe.Result("ERRO[0001] GoError: dial tcp: i/o timeout", 107, false);
        var s = ScriptedCheckerService.parseSummary("", mapper);
        var res = ScriptedCheckerService.buildResult("ERROR", 1234L, r, s, r.output(), "hata");

        assertThat(res.exitCode()).isEqualTo(107);      // eskiden -1'e eziliyordu
        assertThat(res.durationMs()).isEqualTo(1234L);  // eskiden null'a eziliyordu
        assertThat(res.outputTail()).contains("GoError"); // eskiden null'a eziliyordu
        assertThat(res.ok()).isFalse();
    }

    @Test
    @DisplayName("extractErrorLines: ERRO[/GoError/level=error satırları ayıklanır, tekilleştirilir, tavanlanır")
    void extractErrorLines_contract() {
        String out = "\n  running (00m01.0s)\n"
                + "ERRO[0001] GoError: dial tcp 10.0.0.1:443: connect: connection refused\n"
                + "ERRO[0001] GoError: dial tcp 10.0.0.1:443: connect: connection refused\n"  // aynı satır
                + "time=\"x\" level=error msg=\"script aborted\"\n"
                + "default ✓ [--------] 1 VUs\n";
        String lines = ScriptedCheckerService.extractErrorLines(out);
        assertThat(lines).isNotNull();
        assertThat(lines.lines()).hasSize(2);                    // tekilleştirme çalıştı
        assertThat(lines).contains("connection refused").contains("script aborted");
        assertThat(lines).doesNotContain("running (00m01");      // gürültü alınmadı

        // eşleşme yoksa null → çağıran eski son-400-karakter davranışına düşer
        assertThat(ScriptedCheckerService.extractErrorLines("hepsi temiz\nbitti")).isNull();
        assertThat(ScriptedCheckerService.extractErrorLines(null)).isNull();
        assertThat(ScriptedCheckerService.extractErrorLines("")).isNull();

        // en fazla 3 satır
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 10; i++) many.append("ERRO[000").append(i).append("] GoError: hata ").append(i).append('\n');
        assertThat(ScriptedCheckerService.extractErrorLines(many.toString()).lines()).hasSize(3);
    }

    @Test
    @DisplayName("exitCodeLabel: k6 v0.49 çıkış kodları insan diline çevrilir; 105 bizim timeout'umuz DEĞİL")
    void exitCodeLabel_table() {
        assertThat(ScriptedCheckerService.exitCodeLabel(99, false)).contains("threshold");
        assertThat(ScriptedCheckerService.exitCodeLabel(107, false)).contains("çalışma-zamanı");
        assertThat(ScriptedCheckerService.exitCodeLabel(104, false)).contains("yapılandırma");
        // 105 = dış sinyal. ProcessProbe kendi timeout'unda exitCode'u -1 yaptığı için bu kod
        // bizim sonlandırmamız olamaz → pod restart / OOM / node tahliyesi işareti.
        assertThat(ScriptedCheckerService.exitCodeLabel(105, false)).contains("OOM");
        assertThat(ScriptedCheckerService.exitCodeLabel(-1, true)).contains("sonlandırıldı");
        assertThat(ScriptedCheckerService.exitCodeLabel(-1, false)).contains("başlatılamadı");
        assertThat(ScriptedCheckerService.exitCodeLabel(42, false)).contains("42");   // bilinmeyen → ham kod
    }

    @Test
    @DisplayName("scanHardcodedSecrets: sabit-kodlu parola/token/apikey yakalanır (yalnız anahtar-adı raporlanır)")
    void scanHardcodedSecrets_detects() {
        String script = "const password = \"süpergizli123\";\n"
                + "let token: 'abc123def456';\n"
                + "headers = { Authorization: \"Bearer eyJhbGciOi\" };\n"
                + "const apiKey = \"sk-live-9f8e7d6c\";";
        var hits = ScriptedCheckerService.scanHardcodedSecrets(script);
        assertThat(hits).contains("password").contains("token").contains("authorization").contains("apikey");
        // DEĞERLER raporlanmaz (sızıntı yok)
        assertThat(String.join(",", hits)).doesNotContain("süpergizli123").doesNotContain("sk-live");
    }

    @Test
    @DisplayName("scanHardcodedSecrets: __ENV kullanan temiz script → bulgu yok")
    void scanHardcodedSecrets_cleanEnvUsage() {
        String clean = "const user = __ENV.USER; const pass = __ENV.PASS;\nhttp.post(url, { username: user, password: pass });";
        // password: <değişken> (literal değil) → yakalanmaz
        assertThat(ScriptedCheckerService.scanHardcodedSecrets(clean)).isEmpty();
        assertThat(ScriptedCheckerService.scanHardcodedSecrets(null)).isEmpty();
    }
}
