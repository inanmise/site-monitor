package com.sitemonitor.service.scripted;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * k6 {@code --out json} ayrıştırıcısı (2026-10-31, k6 inceleme D1). Fikstürler k6 v2.3.0 ile GERÇEKTEN üretildi
 * ({@code src/test/resources/k6-fixtures/out-*-230.ndjson}, betikleri {@code script-*-230.js}).
 */
class K6OutputParserTest {

    private static K6OutputParser.Parsed fixture(String name) throws IOException {
        try (InputStream in = K6OutputParserTest.class.getResourceAsStream("/k6-fixtures/" + name)) {
            assertThat(in).as(name).isNotNull();
            return K6OutputParser.parse(in, null);
        }
    }

    private static K6OutputParser.Parsed text(String s) {
        return K6OutputParser.parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    void groupsFixture_stepsDurationsChecks() throws IOException {
        K6OutputParser.Parsed p = fixture("out-groups-230.ndjson");
        assertThat(p.anyPoint()).isTrue();
        assertThat(p.truncated()).isFalse();
        assertThat(p.steps()).extracting(K6OutputParser.Step::name).containsExactly("Giriş", "Giriş › Alt adım");
        K6OutputParser.Step giris = p.steps().get(0);
        assertThat(giris.durationMs()).isEqualTo(36L);          // group_duration 35.7952 (iç içe grup dahil)
        assertThat(giris.requests()).isEqualTo(1);
        assertThat(giris.failedRequests()).isZero();
        assertThat(giris.p95Ms()).isEqualTo(15L);                // tek örnek 14.5059
        assertThat(giris.checksPassed()).isEqualTo(1);
        assertThat(giris.checksFailed()).isZero();
        assertThat(giris.errorCode()).isNull();
        K6OutputParser.Step alt = p.steps().get(1);
        assertThat(alt.durationMs()).isEqualTo(8L);
        assertThat(alt.p95Ms()).isEqualTo(6L);
        assertThat(p.firstFailedErrorCode()).isNull();
        assertThat(p.firstFailedStep()).isNull();
        assertThat(p.failingCheck()).isNull();
        assertThat(p.tls()).isEmpty();                           // http:// — TLS yok
    }

    @Test
    void refusedFixture_firstFailedRequestInRoot_nameTagHidesHost() throws IOException {
        K6OutputParser.Parsed p = fixture("out-tls-refused-230.ndjson");
        assertThat(p.firstFailedErrorCode()).isEqualTo(1212);
        assertThat(p.firstFailedStep()).isEqualTo("");           // kök (group "")
        assertThat(p.firstFailedStatus()).isZero();
        assertThat(p.firstFailedHost()).isEqualTo("127.0.0.1");
        // istekte tags.name = 'a' → k6 url etiketini de 'a' yapar: host bilinemez, TLS listesine girmez
        assertThat(p.tls()).isEmpty();
        K6OutputParser.Step root = p.steps().stream().filter(s -> s.name().isEmpty()).findFirst().orElseThrow();
        assertThat(root.durationMs()).isNull();                  // kökün group_duration noktası yoktur
        assertThat(root.failedRequests()).isEqualTo(1);
        assertThat(root.errorCode()).isEqualTo(1212);
    }

    @Test
    void tlsHostFixture_versionPerHost_and404Code() throws IOException {
        K6OutputParser.Parsed p = fixture("out-tls-host-230.ndjson");
        assertThat(p.tls()).containsExactly(new K6OutputParser.TlsHost("localhost", "tls1.3", 2));
        assertThat(p.firstFailedErrorCode()).isEqualTo(1404);
        assertThat(p.firstFailedStatus()).isEqualTo(404);
        assertThat(p.firstFailedStep()).isEqualTo("Eksik");
        assertThat(p.failingCheck()).isEqualTo("Eksik › bulundu");
        assertThat(p.steps()).extracting(K6OutputParser.Step::name).containsExactly("Güvenli", "Eksik");
        assertThat(p.steps().get(1).checksFailed()).isEqualTo(1);
    }

    @Test
    void blacklistedFixture_code1110() throws IOException {
        K6OutputParser.Parsed p = fixture("out-blacklisted-230.ndjson");
        assertThat(p.firstFailedErrorCode()).isEqualTo(1110);
        assertThat(ScriptedRunData.fromK6ErrorCode(p.firstFailedErrorCode()))
                .isEqualTo(com.sitemonitor.service.failure.CheckFailureReason.SSRF_BLOCKED);
    }

    @Test
    void maskAppliesToStepCheckAndHost() throws IOException {
        try (InputStream in = K6OutputParserTest.class.getResourceAsStream("/k6-fixtures/out-tls-host-230.ndjson")) {
            K6OutputParser.Parsed p = K6OutputParser.parse(in, s -> s.replace("localhost", "*******").replace("Eksik", "*******"));
            assertThat(p.tls()).extracting(K6OutputParser.TlsHost::host).containsExactly("*******");
            assertThat(p.firstFailedHost()).isEqualTo("*******");
            assertThat(p.failingCheck()).isEqualTo("******* › bulundu");
            assertThat(p.steps()).extracting(K6OutputParser.Step::name).contains("*******");
        }
    }

    @Test
    void stepName_andHostOf() {
        assertThat(K6OutputParser.stepName("")).isEmpty();
        assertThat(K6OutputParser.stepName(null)).isEmpty();
        assertThat(K6OutputParser.stepName("::A")).isEqualTo("A");
        assertThat(K6OutputParser.stepName("::A::B::C")).isEqualTo("A › B › C");
        assertThat(K6OutputParser.hostOf("https://Example.COM:8443/x?y=1")).isEqualTo("example.com");
        assertThat(K6OutputParser.hostOf("a")).isNull();
        assertThat(K6OutputParser.hostOf("ftp://x/")).isNull();
        assertThat(K6OutputParser.hostOf(null)).isNull();
        assertThat(K6OutputParser.hostOf("https://" + "a".repeat(5000))).isNull();
    }

    @Test
    void percentile_isK6Interpolation() {
        double[] v = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        // k6: p × (n − 1) = 0.95 × 9 = 8.55 → 90 + 0.55 × 10
        assertThat(K6OutputParser.percentile(v, 10, 0.95)).isCloseTo(95.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(K6OutputParser.percentile(new double[]{7}, 1, 0.95)).isEqualTo(7);
        assertThat(K6OutputParser.percentile(new double[0], 0, 0.95)).isZero();
    }

    @Test
    void mostFrequentErrorCodePerStep_tieTakesLowest() {
        String line = "{\"metric\":\"http_reqs\",\"type\":\"Point\",\"data\":{\"value\":1,\"tags\":{\"group\":\"::G\",\"error_code\":\"%s\"}}}\n";
        K6OutputParser.Parsed p = text(String.format(line, "1212") + String.format(line, "1101") + String.format(line, "1212")
                + String.format(line, "1404") + String.format(line, "1404"));
        assertThat(p.steps().get(0).requests()).isEqualTo(5);
        assertThat(p.steps().get(0).errorCode()).isEqualTo(1212);   // 1212 ve 1404 ikişer kez → küçük kod
    }

    @Test
    void stepCap_truncatesButKeepsCountingExisting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < K6OutputParser.MAX_STEPS + 10; i++) {
            sb.append("{\"metric\":\"http_reqs\",\"type\":\"Point\",\"data\":{\"value\":1,\"tags\":{\"group\":\"::G")
                    .append(i).append("\"}}}\n");
        }
        sb.append("{\"metric\":\"http_reqs\",\"type\":\"Point\",\"data\":{\"value\":1,\"tags\":{\"group\":\"::G0\"}}}\n");
        K6OutputParser.Parsed p = text(sb.toString());
        assertThat(p.truncated()).isTrue();
        assertThat(p.steps()).hasSize(K6OutputParser.MAX_STEPS);
        assertThat(p.steps().get(0).requests()).isEqualTo(2);
    }

    @Test
    void longNamesAreFitted() {
        String name = "x".repeat(500);
        K6OutputParser.Parsed p = text("{\"metric\":\"checks\",\"type\":\"Point\",\"data\":{\"value\":0,\"tags\":{\"group\":\"::"
                + name + "\",\"check\":\"c\"}}}\n");
        assertThat(p.steps().get(0).name()).hasSize(K6OutputParser.MAX_STEP_NAME).endsWith("…");
        assertThat(p.failingCheck()).hasSize(K6OutputParser.MAX_STEP_NAME);
    }

    @Test
    void oversizedLineSkipped_badJsonIgnored_emptyAndNullSafe() {
        String big = "{\"metric\":\"http_reqs\",\"type\":\"Point\",\"data\":{\"value\":1,\"tags\":{\"group\":\"::Dev\",\"url\":\""
                + "a".repeat(K6OutputParser.MAX_LINE_BYTES + 10) + "\"}}}\n";
        String ok = "{\"metric\":\"http_reqs\",\"type\":\"Point\",\"data\":{\"value\":1,\"tags\":{\"group\":\"::Ok\"}}}\n";
        K6OutputParser.Parsed p = text(big + "{\"metric\":\"http_reqs\",\"type\":\"Point\",bozuk\n" + ok);
        assertThat(p.steps()).extracting(K6OutputParser.Step::name).containsExactly("Ok");
        assertThat(K6OutputParser.parse(null, null)).isSameAs(K6OutputParser.Parsed.EMPTY);
        assertThat(text("").anyPoint()).isFalse();
        assertThat(text("\r\n\r\n").steps()).isEmpty();
        // CRLF satır sonları
        assertThat(text(ok.replace("\n", "\r\n")).steps()).hasSize(1);
    }

    @Test
    void endlessLineWithoutNewline_stopsAtByteCap() {
        // Satır sonu olmayan dev akış: dosya boyu kadar değil, toplam tavanda durur
        InputStream endless = new InputStream() {
            long n = 0;
            @Override public int read() { n++; return 'a'; }
            @Override public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                n += len;
                return len;
            }
        };
        K6OutputParser.Parsed p = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> K6OutputParser.parse(endless, null));
        assertThat(p.truncated()).isTrue();
        assertThat(p.steps()).isEmpty();
    }

    @Test
    void lineCap_truncates() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < K6OutputParser.MAX_LINES + 5; i++) sb.append("{}\n");
        K6OutputParser.Parsed p = text(sb.toString());
        assertThat(p.truncated()).isTrue();
    }

    @Test
    void streamFailureIsTruncatedNotThrown() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("disk"); }
        };
        K6OutputParser.Parsed p = K6OutputParser.parse(broken, null);
        assertThat(p.truncated()).isTrue();
        assertThat(p.steps()).isEqualTo(List.of());
    }
}
