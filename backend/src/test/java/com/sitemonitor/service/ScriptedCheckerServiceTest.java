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
