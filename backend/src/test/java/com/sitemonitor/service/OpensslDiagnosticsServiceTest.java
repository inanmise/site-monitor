package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * openssl çıktı ayrıştırma + komut kurma birim testleri. Gerçek openssl
 * çalıştırma (ProcessBuilder) ortam bağımlı olduğundan test edilmez.
 */
class OpensslDiagnosticsServiceTest {

    private final OpensslDiagnosticsService svc = new OpensslDiagnosticsService(null);

    private static final String OK_OUTPUT = """
            CONNECTED(00000003)
            depth=2 C = US, O = Example Root
            ---
            Certificate chain
             0 s:CN = www.akbank.com
               i:CN = Example CA
            -----BEGIN CERTIFICATE-----
            MIID...
            -----END CERTIFICATE-----
            ---
            Server certificate
            subject=CN = www.akbank.com
            issuer=CN = Example CA
            ---
            Server public key is 2048 bit
            ---
            SSL handshake has read 4096 bytes
            ---
            New, TLSv1.2, Cipher is ECDHE-RSA-AES128-GCM-SHA256
            SSL-Session:
                Protocol  : TLSv1.2
                Cipher    : ECDHE-RSA-AES128-GCM-SHA256
            Verify return code: 0 (ok)
            """;

    private static final String FAIL_OUTPUT = """
            CONNECTED(00000003)
            140735...:error:1408F10B:SSL routines:ssl3_get_record:wrong version number
            ---
            no peer certificate available
            ---
            New, (NONE), Cipher is (NONE)
            SSL handshake has read 0 bytes
            """;

    @Test
    @DisplayName("buildProtocolArgs: shell yok, doğru flag dizisi")
    void buildProtocolArgs_structure() {
        ReflectionTestUtils.setField(svc, "opensslBin", "openssl");
        List<String> args = svc.buildProtocolArgs("www.akbank.com", 443, "-tls1_2");
        assertThat(args).containsExactly(
                "openssl", "s_client", "-connect", "www.akbank.com:443",
                "-servername", "www.akbank.com", "-tls1_2");
    }

    @Test
    @DisplayName("parseHandshakeSucceeded: gerçek cipher → true; (NONE)/handshake failure → false")
    void parseHandshake() {
        assertThat(OpensslDiagnosticsService.parseHandshakeSucceeded(OK_OUTPUT)).isTrue();
        assertThat(OpensslDiagnosticsService.parseHandshakeSucceeded(FAIL_OUTPUT)).isFalse();
        assertThat(OpensslDiagnosticsService.parseHandshakeSucceeded("")).isFalse();
        assertThat(OpensslDiagnosticsService.parseHandshakeSucceeded(null)).isFalse();
    }

    @Test
    @DisplayName("parseNegotiated: protokol + cipher çıkarılır")
    void parseNegotiated() {
        Map<String, Object> n = OpensslDiagnosticsService.parseNegotiated(OK_OUTPUT);
        assertThat(n.get("protocol")).isEqualTo("TLSv1.2");
        assertThat(n.get("cipher")).isEqualTo("ECDHE-RSA-AES128-GCM-SHA256");
    }

    @Test
    @DisplayName("parseCertificate: subject/issuer/anahtar/doğrulama")
    void parseCertificate() {
        Map<String, Object> c = OpensslDiagnosticsService.parseCertificate(OK_OUTPUT);
        assertThat(c.get("subject")).isEqualTo("CN = www.akbank.com");
        assertThat(c.get("issuer")).isEqualTo("CN = Example CA");
        assertThat(c.get("key_bits")).isEqualTo(2048);
        assertThat(c.get("verify_code")).isEqualTo(0);
        assertThat(c.get("verify_result")).isEqualTo("ok");
    }

    @Test
    @DisplayName("isConnectFailure: timeout/refused/errno → true; başarılı çıktı → false")
    void isConnectFailure_detection() {
        assertThat(OpensslDiagnosticsService.isConnectFailure(
                "connect:errno=110\nconnection timed out")).isTrue();
        assertThat(OpensslDiagnosticsService.isConnectFailure(
                "...\n[zaman aşımı: 8s — bağlantı kurulamadı]")).isTrue();
        assertThat(OpensslDiagnosticsService.isConnectFailure("connection refused")).isTrue();
        assertThat(OpensslDiagnosticsService.isConnectFailure(OK_OUTPUT)).isFalse();
        assertThat(OpensslDiagnosticsService.isConnectFailure(null)).isTrue();
    }

    @Test
    @DisplayName("deriveFlags: zayıf anahtar / self-signed / expired bayrakları")
    void deriveFlags() {
        assertThat(OpensslDiagnosticsService.deriveFlags(Map.of("key_bits", 1024))).contains("WEAK_KEY");
        assertThat(OpensslDiagnosticsService.deriveFlags(Map.of("signature_algorithm", "sha1WithRSAEncryption")))
                .contains("SHA1_SIG");
        assertThat(OpensslDiagnosticsService.deriveFlags(
                Map.of("verify_code", 18, "verify_result", "self signed certificate")))
                .contains("SELF_SIGNED");
        assertThat(OpensslDiagnosticsService.deriveFlags(
                Map.of("verify_code", 10, "verify_result", "certificate has expired")))
                .contains("EXPIRED");
        assertThat(OpensslDiagnosticsService.deriveFlags(Map.of("key_bits", 2048, "verify_code", 0, "verify_result", "ok")))
                .isEmpty();
    }
}
