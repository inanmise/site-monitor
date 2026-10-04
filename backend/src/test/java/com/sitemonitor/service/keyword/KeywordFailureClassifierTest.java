package com.sitemonitor.service.keyword;

import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.util.HttpBodies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static com.sitemonitor.service.keyword.KeywordFailureClassifier.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyword hata teşhisi (2026-10-04) — başarısızlık nedeni sınıflandırıcısının TABLOSU: her kod temsilî istisnadan ya da
 * durum kodu / adet / gövde bileşiminden üretilir; ayrıntı metni TR, ≤ 500 ve sır içermez.
 */
class KeywordFailureClassifierTest {

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of(new HttpConnectTimeoutException("HTTP connect timed out"), TIMEOUT_CONNECT),
                Arguments.of(new HttpTimeoutException("request timed out"), TIMEOUT_READ),
                Arguments.of(new HttpBodies.BodyDeadlineException("Keyword: gövde 1000 ms içinde okunamadı"), TIMEOUT_READ),
                Arguments.of(new IOException("wrapped", new HttpBodies.BodyDeadlineException("deadline")), TIMEOUT_READ),
                Arguments.of(new UnknownHostException("nohost.example"), DNS),
                Arguments.of(new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + "nohost.example"), DNS),
                Arguments.of(new SsrfGuard.BlockedException("izin verilmeyen hedef: 169.254.169.254"), SSRF_BLOCKED),
                Arguments.of(new SSLHandshakeException("Received fatal alert: handshake_failure"), TLS_HANDSHAKE),
                Arguments.of(new SSLHandshakeException("PKIX path building failed: unable to find valid certification path"), TLS_CERT),
                Arguments.of(new SSLHandshakeException("No name matching site.example found"), TLS_CERT),
                Arguments.of(new ConnectException("Connection refused"), CONNECTION_REFUSED),
                Arguments.of(new ConnectException("Connection refused: proxy 10.0.0.1:8080"), PROXY),
                Arguments.of(new IOException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\""), PROXY),
                Arguments.of(new SocketException("Connection reset"), CONNECTION_RESET),
                Arguments.of(new EOFException("EOF reached while reading"), CONNECTION_RESET),
                Arguments.of(new IOException("HTTP/1.1 header parser received no bytes"), CONNECTION_RESET),
                Arguments.of(new NoRouteToHostException("No route to host"), HOST_UNREACHABLE),
                Arguments.of(new IOException("çok fazla yönlendirme (5 hop aşıldı)"), REDIRECT_LIMIT),
                Arguments.of(new IOException("Invalid chunk header"), PROTOCOL_ERROR),
                Arguments.of(new IllegalArgumentException("Illegal character in URI scheme"), CONFIG_ERROR),
                Arguments.of(new IllegalStateException("garip"), UNKNOWN),
                Arguments.of(null, UNKNOWN));
    }

    @ParameterizedTest(name = "{1} ← {0}")
    @MethodSource("exceptions")
    @DisplayName("istisna → kod (HTTP tanısının sınıflandırıcısı + gövde süre sınırı)")
    void exceptionTable(Throwable t, String expected) {
        assertThat(codeForException(t)).isEqualTo(expected);
        Reason r = forException(t, "site.example", 5000, false);
        assertThat(r.code()).isEqualTo(expected);
        assertThat(r.detail()).isNotBlank().hasSizeLessThanOrEqualTo(DETAIL_MAX);
    }

    @Test
    @DisplayName("ayrıntı metinleri: zaman aşımı süreyi, vekil yolu 'Vekil' öznesini, gövde süresi 'gövde' sözünü taşır")
    void exceptionDetails() {
        assertThat(forException(new HttpConnectTimeoutException("x"), "site.example", 4000, false).detail())
                .contains("site.example").contains("4000 ms");
        assertThat(forException(new HttpConnectTimeoutException("x"), "site.example", 4000, true).detail()).startsWith("Vekile");
        assertThat(forException(new HttpBodies.BodyDeadlineException("x"), "site.example", 3000, false).detail())
                .contains("gövdesi").contains("3000 ms");
        assertThat(forException(new HttpTimeoutException("x"), "site.example", 3000, false).detail()).contains("yanıt gelmedi");
        String longMsg = "x".repeat(5000);
        assertThat(forException(new IllegalStateException(longMsg), "h", 1000, false).detail()).hasSizeLessThanOrEqualTo(DETAIL_MAX);
    }

    @Test
    @DisplayName("koşul: kelime yoksa sırayla yönlendirme izlenemedi → HTTP durumu → boş gövde → okuma tavanı → bulunamadı")
    void conditionPresenceRule() {
        assertThat(forCondition(302, 0, "GTE", 1, 0, false, "Giriş").code()).isEqualTo(REDIRECT_BLOCKED);
        Reason status = forCondition(503, 0, "GTE", 1, 1200, false, "Giriş");
        assertThat(status.code()).isEqualTo(HTTP_STATUS);
        assertThat(status.detail()).contains("HTTP 503").contains("« Giriş »").contains("en az 1 kez");
        assertThat(forCondition(200, 0, "GTE", 1, 0, false, "Giriş").code()).isEqualTo(EMPTY_BODY);
        Reason trunc = forCondition(200, 0, "GTE", 1, 2_000_000, true, "Giriş");
        assertThat(trunc.code()).isEqualTo(BODY_TRUNCATED);
        assertThat(trunc.detail()).contains("1953 KB");
        Reason nf = forCondition(200, 0, "GTE", 1, 15_360, false, "Giriş");
        assertThat(nf.code()).isEqualTo(KEYWORD_NOT_FOUND);
        assertThat(nf.detail()).contains("HTTP 200").contains("15 KB").contains("hiç bulunamadı");
    }

    @Test
    @DisplayName("koşul: kelime VARSA 'olmamalı' kuralında yasak bulundu, diğer kurallarda adet uymadı")
    void conditionFoundRules() {
        Reason forbidden = forCondition(200, 2, "LTE", 0, 900, false, "Hata");
        assertThat(forbidden.code()).isEqualTo(KEYWORD_FOUND_FORBIDDEN);
        assertThat(forbidden.detail()).contains("2 kez").contains("OLMAMASI");
        assertThat(forCondition(200, 1, "EQ", 0, 900, false, "Hata").code()).isEqualTo(KEYWORD_FOUND_FORBIDDEN);
        assertThat(forCondition(200, 1, "LT", 1, 900, false, "Hata").code()).isEqualTo(KEYWORD_FOUND_FORBIDDEN);
        Reason count = forCondition(200, 1, "GTE", 3, 900, false, "ürün");
        assertThat(count.code()).isEqualTo(KEYWORD_COUNT_MISMATCH);
        assertThat(count.detail()).contains("1 kez").contains("en az 3 kez");
        assertThat(forCondition(500, 4, "LTE", 2, 900, false, "x").code()).isEqualTo(KEYWORD_COUNT_MISMATCH);
    }

    @Test
    @DisplayName("isAbsenceRule — arayüzdeki ruleOf 'absent' ile aynı (LTE0 / EQ0 / LT1)")
    void absenceRule() {
        assertThat(isAbsenceRule("LTE", 0)).isTrue();
        assertThat(isAbsenceRule("EQ", 0)).isTrue();
        assertThat(isAbsenceRule("LT", 1)).isTrue();
        assertThat(isAbsenceRule("GTE", 1)).isFalse();
        assertThat(isAbsenceRule("LTE", 2)).isFalse();
        assertThat(isAbsenceRule(null, 0)).isFalse();
    }

    @Test
    @DisplayName("katalog: 21 kod, tekrar yok; uzun anahtar kelime ayrıntıda kırpılır")
    void catalog() {
        assertThat(CODES).hasSize(21).doesNotHaveDuplicates().contains(KEYWORD_NOT_FOUND, TIMEOUT_READ, SSRF_BLOCKED, UNKNOWN);
        Reason r = forCondition(200, 0, "GTE", 1, 10, false, "k".repeat(400));
        assertThat(r.detail()).hasSizeLessThanOrEqualTo(DETAIL_MAX).contains("…");
    }
}
