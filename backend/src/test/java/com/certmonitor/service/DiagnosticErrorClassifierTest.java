package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Alan adı tanılama hata sınıflandırıcısı — her hata metni/istisnası doğru koda eşleniyor mu. */
class DiagnosticErrorClassifierTest {

    @Test
    @DisplayName("PKIX / truststore hataları → PKIX_TRUST")
    void pkix() {
        assertThat(DiagnosticErrorClassifier.classify(
                "PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: "
                        + "unable to find valid certification path to requested target")).isEqualTo("PKIX_TRUST");
        assertThat(DiagnosticErrorClassifier.classify("(certificate_unknown) handshake failed")).isEqualTo("PKIX_TRUST");
        assertThat(DiagnosticErrorClassifier.classify(new javax.net.ssl.SSLHandshakeException("cert error"))).isEqualTo("PKIX_TRUST");
    }

    @Test
    @DisplayName("Ham TCP connect timeout (WHOIS/43) → CONNECT_TIMEOUT")
    void connectTimeout() {
        assertThat(DiagnosticErrorClassifier.classify("Connect timed out")).isEqualTo("CONNECT_TIMEOUT");
    }

    @Test
    @DisplayName("HTTP istek timeout → TIMEOUT")
    void httpTimeout() {
        assertThat(DiagnosticErrorClassifier.classify(new HttpTimeoutException("request timed out"))).isEqualTo("TIMEOUT");
        assertThat(DiagnosticErrorClassifier.classify("request timed out")).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("Proxy tünel hataları → PROXY")
    void proxy() {
        assertThat(DiagnosticErrorClassifier.classify("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\""))
                .isEqualTo("PROXY");
    }

    @Test
    @DisplayName("DNS çözümleme → DNS")
    void dns() {
        assertThat(DiagnosticErrorClassifier.classify(new UnknownHostException("data.iana.org"))).isEqualTo("DNS");
    }

    @Test
    @DisplayName("Bağlantı reddedildi → REFUSED")
    void refused() {
        assertThat(DiagnosticErrorClassifier.classify(new ConnectException("Connection refused"))).isEqualTo("REFUSED");
    }

    @Test
    @DisplayName("HTTP durum kodu → HTTP_<status>")
    void httpStatus() {
        assertThat(DiagnosticErrorClassifier.httpStatus(503)).isEqualTo("HTTP_503");
        assertThat(DiagnosticErrorClassifier.httpStatus(404)).isEqualTo("HTTP_404");
    }

    @Test
    @DisplayName("Tanınmayan / boş → UNKNOWN")
    void unknown() {
        assertThat(DiagnosticErrorClassifier.classify((String) null)).isEqualTo("UNKNOWN");
        assertThat(DiagnosticErrorClassifier.classify("")).isEqualTo("UNKNOWN");
        assertThat(DiagnosticErrorClassifier.classify("some unrelated failure")).isEqualTo("UNKNOWN");
        assertThat(DiagnosticErrorClassifier.classify((Throwable) null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Öncelik: PKIX, timeout'tan önce gelir")
    void precedence() {
        // Zincirde hem PKIX hem timeout geçse bile güven sorunu öncelikli.
        assertThat(DiagnosticErrorClassifier.classify("PKIX path building failed ... connection timed out"))
                .isEqualTo("PKIX_TRUST");
    }
}
