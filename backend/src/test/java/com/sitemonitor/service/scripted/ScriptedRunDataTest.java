package com.sitemonitor.service.scripted;

import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Senaryo koşum verisi: k6 hata kodu eşlemesi, neden önceliği, JSON biçimleri (2026-10-31, k6 inceleme D1). */
class ScriptedRunDataTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // ÖLÇÜLENLER (k6 v2.3.0): NXDOMAIN, kara liste, ret, bilinmeyen CA, istek zaman aşımı, HTTP 404
            "1101,DNS_NXDOMAIN", "1110,SSRF_BLOCKED", "1212,CONNECT_REFUSED", "1310,TLS_TRUST", "1050,READ_TIMEOUT",
            "1404,HTTP_STATUS",
            // k6 kaynağındaki adlarla
            "1100,DNS_RESOLVE", "1102,DNS_RESOLVE", "1111,SSRF_BLOCKED", "1150,DNS_RESOLVE", "1020,CONFIG_ERROR",
            "1211,CONNECT_TIMEOUT", "1201,CONNECTION_RESET", "1220,CONNECTION_RESET", "1210,HOST_UNREACHABLE",
            "1311,TLS_HOSTNAME", "1300,TLS_HANDSHAKE", "1320,TLS_HANDSHAKE", "1503,HTTP_STATUS", "1600,PROTOCOL_ERROR",
            "1000,UNKNOWN", "1700,UNKNOWN", "42,UNKNOWN"
    })
    void errorCodeMapping(int code, CheckFailureReason expected) {
        assertThat(ScriptedRunData.fromK6ErrorCode(code)).isEqualTo(expected);
    }

    @Test
    void errorCodeNull() {
        assertThat(ScriptedRunData.fromK6ErrorCode(null)).isNull();
    }

    private static K6OutputParser.Parsed parsed(Integer code, String step, Integer status, String host, String check) {
        return new K6OutputParser.Parsed(List.of(), false, List.of(), code, step, status, host, check, true);
    }

    private static ScriptedRunData.Input in(String status, int exit, boolean timedOut, boolean interrupted,
                                           Integer checksFailed, List<ScriptedRunData.Threshold> th,
                                           K6OutputParser.Parsed p, CheckFailureReason override) {
        return new ScriptedRunData.Input(status, exit, timedOut, interrupted, false, checksFailed, th, p, override);
    }

    private static final ScriptedRunData.Threshold FAILED_TH =
            new ScriptedRunData.Threshold("http_req_duration", "p(95)<500", false);
    private static final ScriptedRunData.Threshold OK_TH =
            new ScriptedRunData.Threshold("checks", "rate>0.9", true);

    @Test
    void passIsNull_interruptedWinsEverything() {
        assertThat(ScriptedRunData.classify(in("PASS", 0, false, false, 0, List.of(), null, null))).isNull();
        CheckFailure f = ScriptedRunData.classify(in("PASS", 0, false, true, 0, List.of(), null, null));
        assertThat(f.reason()).isEqualTo(CheckFailureReason.SCRIPT_INTERRUPTED);
        CheckFailure g = ScriptedRunData.classify(in("FAIL", 99, false, true, 3, List.of(FAILED_TH),
                parsed(1212, "", 0, "x", null), CheckFailureReason.SSRF_BLOCKED));
        assertThat(g.reason()).isEqualTo(CheckFailureReason.SCRIPT_INTERRUPTED);
        assertThat(ScriptedRunData.classify(null)).isNull();
        assertThat(ScriptedRunData.classify(in(null, 0, false, false, 0, null, null, null))).isNull();
    }

    @Test
    void overrideBeatsNetwork() {
        CheckFailure f = ScriptedRunData.classify(in("ERROR", -1, false, false, null, null,
                parsed(1212, "", 0, "x", null), CheckFailureReason.SSRF_BLOCKED));
        assertThat(f.reason()).isEqualTo(CheckFailureReason.SSRF_BLOCKED);
    }

    @Test
    void networkBeatsThresholdAndCheck_withDetail() {
        CheckFailure f = ScriptedRunData.classify(in("FAIL", 99, false, false, 2, List.of(OK_TH, FAILED_TH),
                parsed(1404, "Giriş › Ödeme", 404, "shop.example.com", "Giriş › Ödeme › durum 200"), null));
        assertThat(f.reason()).isEqualTo(CheckFailureReason.HTTP_STATUS);
        Map<String, Object> d = f.detail();
        assertThat(d).containsEntry("error_code", 1404).containsEntry("http_status", 404)
                .containsEntry("target", "shop.example.com").containsEntry("step", "Giriş › Ödeme")
                .containsEntry("threshold", "http_req_duration p(95)<500")
                .containsEntry("check", "Giriş › Ödeme › durum 200");
    }

    @Test
    void networkWithoutHttpStatus_rootStepOmitted() {
        CheckFailure f = ScriptedRunData.classify(in("FAIL", 0, false, false, 0, List.of(),
                parsed(1101, "", 0, null, null), null));
        assertThat(f.reason()).isEqualTo(CheckFailureReason.DNS_NXDOMAIN);
        assertThat(f.detail()).containsEntry("error_code", 1101).doesNotContainKeys("http_status", "target", "step");
    }

    @Test
    void thresholdBeatsCheck_exit99AloneIsThreshold() {
        assertThat(ScriptedRunData.classify(in("FAIL", 99, false, false, 2, List.of(FAILED_TH), parsed(null, null, null, null, null), null))
                .reason()).isEqualTo(CheckFailureReason.SCRIPT_THRESHOLD_FAILED);
        assertThat(ScriptedRunData.classify(in("FAIL", 99, false, false, 0, List.of(), null, null))
                .reason()).isEqualTo(CheckFailureReason.SCRIPT_THRESHOLD_FAILED);
    }

    @Test
    void checkFailed() {
        CheckFailure f = ScriptedRunData.classify(in("FAIL", 0, false, false, 1, List.of(OK_TH),
                parsed(null, null, null, null, "durum 200"), null));
        assertThat(f.reason()).isEqualTo(CheckFailureReason.SCRIPT_CHECK_FAILED);
        assertThat(f.detail()).containsEntry("check", "durum 200").doesNotContainKey("threshold");
    }

    @Test
    void abortedNoChecksTimeoutUnavailableException() {
        CheckFailure aborted = ScriptedRunData.classify(in("FAIL", 110, false, false, 0, null, null, null));
        assertThat(aborted.reason()).isEqualTo(CheckFailureReason.SCRIPT_ABORTED);
        assertThat(aborted.detail()).containsEntry("exit_code", 110);
        assertThat(ScriptedRunData.classify(in("ERROR", 108, false, false, 0, null, null, null)).reason())
                .isEqualTo(CheckFailureReason.SCRIPT_ABORTED);
        assertThat(ScriptedRunData.classify(in("NO_CHECKS", 0, false, false, 0, null, null, null)).reason())
                .isEqualTo(CheckFailureReason.SCRIPT_NO_CHECKS);
        assertThat(ScriptedRunData.classify(in("TIMEOUT", -1, true, false, null, null, null, null)).reason())
                .isEqualTo(CheckFailureReason.SCRIPT_PROCESS_TIMEOUT);
        assertThat(ScriptedRunData.classify(in("ERROR", -1, false, false, null, null, null, null)).reason())
                .isEqualTo(CheckFailureReason.K6_UNAVAILABLE);
        CheckFailure ex = ScriptedRunData.classify(in("ERROR", 107, false, false, null, null, null, null));
        assertThat(ex.reason()).isEqualTo(CheckFailureReason.SCRIPT_EXCEPTION);
        assertThat(ex.detail()).containsEntry("exit_code", 107);
        assertThat(ScriptedRunData.classify(in("ERROR", 0, false, false, null, null, null, null)).detail())
                .doesNotContainKey("exit_code");
    }

    @Test
    void stepsJson_thresholdsSorted_tls_context() {
        assertThat(ScriptedRunData.stepsJson(List.of())).isNull();
        assertThat(ScriptedRunData.stepsJson(null)).isNull();
        String steps = ScriptedRunData.stepsJson(List.of(new K6OutputParser.Step("Giriş", 36L, 1, 0, 15L, 1, 0, null)));
        assertThat(steps).isEqualTo("[{\"name\":\"Giriş\",\"duration_ms\":36,\"requests\":1,\"failed_requests\":0,"
                + "\"p95_ms\":15,\"checks_passed\":1,\"checks_failed\":0,\"error_code\":null}]");

        String th = ScriptedRunData.thresholdsJson(List.of(OK_TH, FAILED_TH,
                new ScriptedRunData.Threshold("checks", "rate>0.5", true)));
        assertThat(th).isEqualTo("[{\"metric\":\"http_req_duration\",\"expr\":\"p(95)<500\",\"ok\":false},"
                + "{\"metric\":\"checks\",\"expr\":\"rate>0.5\",\"ok\":true},"
                + "{\"metric\":\"checks\",\"expr\":\"rate>0.9\",\"ok\":true}]");
        assertThat(ScriptedRunData.thresholdsJson(List.of())).isNull();

        assertThat(ScriptedRunData.tlsJson(List.of(new K6OutputParser.TlsHost("a.example", "tls1.3", 2))))
                .isEqualTo("[{\"host\":\"a.example\",\"tls_version\":\"tls1.3\",\"requests\":2}]");
        assertThat(ScriptedRunData.tlsJson(List.of())).isNull();

        assertThat(ScriptedRunData.contextJson(new ScriptedRunData.RunContext(130, 60, true, false, false)))
                .isEqualTo("{\"process_budget_s\":130,\"request_timeout_s\":60,\"egress\":\"proxy\","
                        + "\"corporate_ca\":false,\"inverted_budget\":false}");
        assertThat(ScriptedRunData.contextJson(new ScriptedRunData.RunContext(30, null, false, true, true)))
                .contains("\"request_timeout_s\":null", "\"egress\":\"direct\"", "\"inverted_budget\":true");
        assertThat(ScriptedRunData.contextJson(null)).isNull();
    }

    @Test
    void thresholdsJsonCapped() {
        List<ScriptedRunData.Threshold> many = new java.util.ArrayList<>();
        for (int i = 0; i < ScriptedRunData.MAX_THRESHOLDS + 20; i++) {
            many.add(new ScriptedRunData.Threshold("m" + String.format("%03d", i), "avg<1", true));
        }
        Object parsed = ScriptedRunData.parse(ScriptedRunData.thresholdsJson(many));
        assertThat((List<?>) parsed).hasSize(ScriptedRunData.MAX_THRESHOLDS);
    }

    @Test
    void fitThresholdText() {
        assertThat(ScriptedRunData.fitThresholdText(null)).isEmpty();
        assertThat(ScriptedRunData.fitThresholdText("p(95)<1")).isEqualTo("p(95)<1");
        assertThat(ScriptedRunData.fitThresholdText("x".repeat(300))).hasSize(ScriptedRunData.MAX_THRESHOLD_TEXT).endsWith("…");
    }

    @Test
    void parseNeverThrows() {
        assertThat(ScriptedRunData.parse(null)).isNull();
        assertThat(ScriptedRunData.parse("  ")).isNull();
        assertThat(ScriptedRunData.parse("{bozuk")).isNull();
        assertThat(ScriptedRunData.parse("null")).isNull();
        assertThat(ScriptedRunData.parse("[1,2]")).isEqualTo(List.of(1, 2));
        assertThat(ScriptedRunData.parse("{\"a\":1}")).isEqualTo(Map.of("a", 1));
    }
}
