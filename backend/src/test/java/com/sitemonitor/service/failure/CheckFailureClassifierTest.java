package com.sitemonitor.service.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.util.HttpBodies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.failure.CheckFailureReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Kontrol hata teşhisi sınıflandırıcısı (2026-10-05): her kod ailesi kendi girdisinden doğru koda iner; ayrıntı küçük,
 * yapısal ve SIRSIZ kalır; hiçbir yol fırlatmaz. Örnek host'lar {@code example.test} — gerçek kurum verisi yok.
 */
class CheckFailureClassifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(CheckFailure f) throws Exception {
        return JSON.readValue(f.json(), Map.class);
    }

    @Nested
    @DisplayName("istisna → kod (ağ / TLS / vekil / politika)")
    class Exceptions {

        @Test
        @DisplayName("bağlantı ailesi: reddedildi, zaman aşımı, yol yok, sıfırlandı")
        void connectFamily() {
            assertThat(CheckFailureClassifier.codeForException(new ConnectException("Connection refused"), false)).isEqualTo(CONNECT_REFUSED);
            assertThat(CheckFailureClassifier.codeForException(new SocketTimeoutException("Connect timed out"), false)).isEqualTo(CONNECT_TIMEOUT);
            assertThat(CheckFailureClassifier.codeForException(new NoRouteToHostException("No route to host"), false)).isEqualTo(HOST_UNREACHABLE);
            assertThat(CheckFailureClassifier.codeForException(new SocketException("Connection reset"), false)).isEqualTo(CONNECTION_RESET);
            assertThat(CheckFailureClassifier.codeForException(new SocketTimeoutException("Read timed out"), false)).isEqualTo(READ_TIMEOUT);
            assertThat(CheckFailureClassifier.codeForException(new PortUnreachableException("ICMP Port Unreachable"), false)).isEqualTo(CONNECT_REFUSED);
        }

        @Test
        @DisplayName("vekil yolunda TCP bağlantı hatası VEKİLE aittir; tünel reddi ayrı kod + vekil durum kodu")
        void proxyFamily() throws Exception {
            assertThat(CheckFailureClassifier.codeForException(new ConnectException("Connection refused"), true)).isEqualTo(PROXY_ERROR);
            assertThat(CheckFailureClassifier.codeForException(new SocketTimeoutException("Connect timed out"), true)).isEqualTo(PROXY_ERROR);
            CheckFailure f = CheckFailureClassifier.forException(new IOException("vekil tüneli reddetti: HTTP/1.1 403 Forbidden"), true);
            assertThat(f.reason()).isEqualTo(PROXY_REFUSED);
            assertThat(detail(f)).containsEntry("proxy_status", 403).containsEntry("via", "proxy").containsEntry("phase", "CONNECT");
            assertThat(CheckFailureClassifier.codeForException(new IOException("vekil tanımlı değil"), true)).isEqualTo(PROXY_ERROR);
            // vekil yolunda TLS hatası yine TLS'tir (tünel kuruldu, hedefle el sıkışma düştü)
            assertThat(CheckFailureClassifier.codeForException(new SSLHandshakeException("Received fatal alert: handshake_failure"), true)).isEqualTo(TLS_HANDSHAKE);
        }

        @Test
        @DisplayName("TLS: güven zinciri, ad uyuşmazlığı, genel el sıkışma")
        void tlsFamily() {
            assertThat(CheckFailureClassifier.codeForException(new SSLHandshakeException("PKIX path building failed: unable to find valid certification path"), false)).isEqualTo(TLS_TRUST);
            assertThat(CheckFailureClassifier.codeForException(new SSLHandshakeException("No name matching api.example.test found"), false)).isEqualTo(TLS_HOSTNAME);
            assertThat(CheckFailureClassifier.codeForException(new SSLHandshakeException("Remote host terminated the handshake"), false)).isEqualTo(TLS_HANDSHAKE);
        }

        @Test
        @DisplayName("politika: SSRF engeli, çözümlenemeyen host (DNS), boş hedef (yapılandırma)")
        void policyFamily() {
            assertThat(CheckFailureClassifier.codeForException(new SsrfGuard.BlockedException("izin verilmeyen hedef x.example.test → 169.254.169.254 (metadata)"), false)).isEqualTo(SSRF_BLOCKED);
            assertThat(CheckFailureClassifier.codeForException(new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + "nx.example.test"), false)).isEqualTo(DNS_RESOLVE);
            assertThat(CheckFailureClassifier.codeForException(new SsrfGuard.BlockedException(SsrfGuard.UNRESOLVABLE_PREFIX + "nx.example.test"), false)).isEqualTo(DNS_RESOLVE);
            assertThat(CheckFailureClassifier.codeForException(new SsrfGuard.BlockedException("boş hedef host"), false)).isEqualTo(CONFIG_ERROR);
            assertThat(CheckFailureClassifier.codeForException(new UnknownHostException("nx.example.test"), false)).isEqualTo(DNS_RESOLVE);
        }

        @Test
        @DisplayName("IPv4/IPv6 ailesinde adres yok → DNS_RESOLVE + family; gövde süre sınırı → READ_TIMEOUT; ham durum satırı → PROTOCOL_ERROR")
        void special() throws Exception {
            CheckFailure fam = CheckFailureClassifier.forException(new UnknownHostException("No IPv6 address"), false);
            assertThat(fam.reason()).isEqualTo(DNS_RESOLVE);
            assertThat(detail(fam)).containsEntry("family", "v6");
            assertThat(CheckFailureClassifier.codeForException(new HttpBodies.BodyDeadlineException("Sayfa gövdesi süre tavanını aştı"), false)).isEqualTo(READ_TIMEOUT);
            assertThat(CheckFailureClassifier.codeForException(new IOException("geçersiz HTTP yanıtı: SSH-2.0"), false)).isEqualTo(PROTOCOL_ERROR);
        }

        @Test
        @DisplayName("ayrıntı: evre + istisna sınıfı + ileti + kısa neden zinciri; sınıflanamayan → UNKNOWN; null güvenli")
        void detailAndUnknown() throws Exception {
            CheckFailure f = CheckFailureClassifier.forException(
                    new RuntimeException("dış sarmal", new ConnectException("Connection refused")), false);
            assertThat(f.reason()).isEqualTo(CONNECT_REFUSED);
            Map<String, Object> d = detail(f);
            assertThat(d).containsEntry("phase", "CONNECT").containsEntry("exception", "RuntimeException");
            assertThat((List<?>) d.get("cause_chain")).hasSize(2);
            assertThat(CheckFailureClassifier.codeForException(new IllegalStateException("garip"), false)).isEqualTo(UNKNOWN);
            assertThat(CheckFailureClassifier.codeForException(null, false)).isEqualTo(UNKNOWN);
            assertThatCode(() -> CheckFailureClassifier.forException(null, true)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("DNS")
    class Dns {
        @Test
        @DisplayName("rcode: NOERROR+boş → kayıt yok, NXDOMAIN, SERVFAIL, REFUSED, diğer → SERVFAIL; rcode ayrıntıda")
        void rcodes() throws Exception {
            assertThat(CheckFailureClassifier.forDnsRcode(0, "NOERROR").reason()).isEqualTo(DNS_NO_ANSWER);
            assertThat(CheckFailureClassifier.forDnsRcode(3, "NXDOMAIN").reason()).isEqualTo(DNS_NXDOMAIN);
            assertThat(CheckFailureClassifier.forDnsRcode(2, "SERVFAIL").reason()).isEqualTo(DNS_SERVFAIL);
            assertThat(CheckFailureClassifier.forDnsRcode(5, "REFUSED").reason()).isEqualTo(DNS_REFUSED);
            assertThat(CheckFailureClassifier.forDnsRcode(4, "NOTIMP").reason()).isEqualTo(DNS_SERVFAIL);
            assertThat(detail(CheckFailureClassifier.forDnsRcode(3, "NXDOMAIN"))).containsEntry("rcode", "NXDOMAIN").containsEntry("phase", "DNS");
        }

        @Test
        @DisplayName("istisna: zaman aşımı → DNS_TIMEOUT, geçersiz ad → CONFIG_ERROR, diğer → DNS_RESOLVE")
        void exceptions() throws Exception {
            assertThat(CheckFailureClassifier.forDnsException(new SocketTimeoutException("Query timed out")).reason()).isEqualTo(DNS_TIMEOUT);
            assertThat(CheckFailureClassifier.forDnsException(new IOException("Timed out while trying to resolve x.example.test./A")).reason()).isEqualTo(DNS_TIMEOUT);
            assertThat(CheckFailureClassifier.forDnsException(new org.xbill.DNS.TextParseException("bad name")).reason()).isEqualTo(CONFIG_ERROR);
            assertThat(CheckFailureClassifier.forDnsException(new IOException("network down")).reason()).isEqualTo(DNS_RESOLVE);
        }
    }

    @Nested
    @DisplayName("Ping")
    class Ping {
        @Test
        @DisplayName("ICMP yok, ad çözümlenemedi, yol yok, tüm paketler kayıp, sınıflanamayan")
        void ping() throws Exception {
            assertThat(CheckFailureClassifier.forPing(true, null, "ping: socket: Operation not permitted").reason()).isEqualTo(ICMP_UNAVAILABLE);
            assertThat(CheckFailureClassifier.forPing(false, null, "ping: unknown host nx.example.test").reason()).isEqualTo(DNS_RESOLVE);
            assertThat(CheckFailureClassifier.forPing(false, 100, "From 10.0.0.1 icmp_seq=1 Destination Host Unreachable\n4 packets transmitted, 0 received, 100% packet loss").reason())
                    .isEqualTo(HOST_UNREACHABLE);
            CheckFailure loss = CheckFailureClassifier.forPing(false, 100, "4 packets transmitted, 0 received, 100% packet loss");
            assertThat(loss.reason()).isEqualTo(ICMP_NO_REPLY);
            assertThat(detail(loss)).containsEntry("packet_loss", 100).containsEntry("phase", "ICMP");
            assertThat(CheckFailureClassifier.forPing(false, null, "beklenmeyen çıktı").reason()).isEqualTo(UNKNOWN);
        }
    }

    @Nested
    @DisplayName("HTTP / sayfa / alan adı / metin")
    class Conditions {
        @Test
        @DisplayName("HTTP durum uyuşmazlığı: kod + beklenen ayrıntıda")
        void httpStatus() throws Exception {
            CheckFailure f = CheckFailureClassifier.forHttpStatus(503, "2xx");
            assertThat(f.reason()).isEqualTo(HTTP_STATUS);
            assertThat(detail(f)).containsEntry("http_status", 503).containsEntry("expected", "2xx").containsEntry("phase", "RESPONSE");
        }

        @Test
        @DisplayName("sayfa kaynakları: kırık > zaman aşımı > güvensiz; sorun yoksa null")
        void pageResources() throws Exception {
            assertThat(CheckFailureClassifier.forPageResources(2, 1, 1, 40).reason()).isEqualTo(RESOURCES_BROKEN);
            assertThat(CheckFailureClassifier.forPageResources(0, 3, 0, 40).reason()).isEqualTo(RESOURCES_TIMEOUT);
            assertThat(CheckFailureClassifier.forPageResources(0, 0, 2, 40).reason()).isEqualTo(MIXED_CONTENT);
            assertThat(CheckFailureClassifier.forPageResources(0, 0, 0, 40)).isNull();
            assertThat(detail(CheckFailureClassifier.forPageResources(2, 1, 0, 40)))
                    .containsEntry("broken", 2).containsEntry("timeouts", 1).containsEntry("total_resources", 40);
        }

        @Test
        @DisplayName("alan adı: kayıt sunucusu yok / WHOIS, bitiş yok, 404, 429, 5xx, ayrıştırma, ağ/TLS, yapılandırma")
        void domain() throws Exception {
            assertThat(CheckFailureClassifier.forDomain("rdap http 404", false, false, null).reason()).isEqualTo(NO_PUBLIC_REGISTRY);
            assertThat(CheckFailureClassifier.forDomain("rdap http 404", false, true, "tr whois: boş/erişilemedi").reason()).isEqualTo(WHOIS_UNAVAILABLE);
            assertThat(CheckFailureClassifier.forDomain(null, true, false, null).reason()).isEqualTo(REGISTRY_NO_EXPIRY);
            assertThat(CheckFailureClassifier.forDomain("whois: no expiry parsed", null, true, null).reason()).isEqualTo(REGISTRY_NO_EXPIRY);
            assertThat(CheckFailureClassifier.forDomain("rdap http 404", true, false, null).reason()).isEqualTo(RDAP_NOT_FOUND);
            assertThat(CheckFailureClassifier.forDomain("rdap http 429", true, false, null).reason()).isEqualTo(RDAP_RATE_LIMITED);
            assertThat(CheckFailureClassifier.forDomain("rdap http 503", true, false, null).reason()).isEqualTo(RDAP_UNAVAILABLE);
            assertThat(CheckFailureClassifier.forDomain("rdap parse: Unexpected character", true, false, null).reason()).isEqualTo(RDAP_UNAVAILABLE);
            assertThat(CheckFailureClassifier.forDomain("PKIX path building failed", true, false, null).reason()).isEqualTo(TLS_TRUST);
            assertThat(CheckFailureClassifier.forDomain("HTTP connect timed out", null, false, null).reason()).isEqualTo(CONNECT_TIMEOUT);
            assertThat(CheckFailureClassifier.forDomain("garip bir şey", null, false, null).reason()).isEqualTo(RDAP_UNAVAILABLE);
            assertThat(CheckFailureClassifier.forDomain("geçersiz/çözümlenemeyen domain", null, false, null).reason()).isEqualTo(CONFIG_ERROR);
            Map<String, Object> d = detail(CheckFailureClassifier.forDomain("rdap http 404", false, true, "tr whois: boş/erişilemedi"));
            assertThat(d).containsEntry("registry_rdap", false).containsEntry("whois_tried", true).containsEntry("http_status", 404)
                    .containsEntry("phase", "REGISTRY");
        }

        @Test
        @DisplayName("yalnız metin (eski yollar): en yakın kod; boş → UNKNOWN")
        void fromMessage() {
            assertThat(CheckFailureClassifier.fromMessage(SsrfGuard.UNRESOLVABLE_PREFIX + "nx.example.test")).isEqualTo(DNS_RESOLVE);
            assertThat(CheckFailureClassifier.fromMessage("Connection refused")).isEqualTo(CONNECT_REFUSED);
            assertThat(CheckFailureClassifier.fromMessage("connect timed out")).isEqualTo(CONNECT_TIMEOUT);
            assertThat(CheckFailureClassifier.fromMessage("request timed out")).isEqualTo(READ_TIMEOUT);
            assertThat(CheckFailureClassifier.fromMessage("ana sayfa HTTP 502")).isEqualTo(HTTP_STATUS);
            assertThat(CheckFailureClassifier.fromMessage("çok fazla yönlendirme")).isEqualTo(REDIRECT_LIMIT);
            assertThat(CheckFailureClassifier.fromMessage("vekil tüneli reddetti: HTTP/1.1 403")).isEqualTo(PROXY_REFUSED);
            assertThat(CheckFailureClassifier.fromMessage("")).isEqualTo(UNKNOWN);
            assertThat(CheckFailureClassifier.fromMessage(null)).isEqualTo(UNKNOWN);
        }
    }

    @Nested
    @DisplayName("CheckFailure: güvenlik, tavan, sonuç haritası")
    class Holder {
        @Test
        @DisplayName("sırlar maskelenir: URL kullanıcı bilgisi, hassas sorgu, Basic/Bearer, Cookie, parola çifti")
        void secretsMasked() throws Exception {
            CheckFailure f = CheckFailure.of(UNKNOWN)
                    .with("message", "GET https://user:s3cretP4ss@app.example.test/x?token=abc123&page=2 failed")
                    .with("header", "Proxy-Authorization: Basic dXNlcjpwYXNzd29yZA==")
                    .with("cookie", "Cookie: SESSION=deadbeefcafebabe")
                    .with("pair", "password=hunter22 and api_key=XYZ987");
            String j = f.json();
            assertThat(j).doesNotContain("s3cretP4ss", "abc123", "dXNlcjpwYXNzd29yZA", "deadbeefcafebabe", "hunter22", "XYZ987");
            assertThat(j).contains("app.example.test").contains("page=2");
        }

        @Test
        @DisplayName("JSON ≤ 4000 karakter: uzun istisna zinciri ve ileti düşer, evre kalır")
        void capped() throws Exception {
            CheckFailure f = CheckFailure.of(CONNECT_TIMEOUT);
            for (int i = 0; i < 40; i++) f.with("k" + i, "x".repeat(290));
            String j = f.json();
            assertThat(j).isNotNull();
            assertThat(j.length()).isLessThanOrEqualTo(CheckFailure.DETAIL_MAX);
            assertThat(detail(f)).containsEntry("phase", "CONNECT");
        }

        @Test
        @DisplayName("null / boş değerler yazılmaz; listeler 8 elemana kısalır")
        void nullsAndLists() throws Exception {
            CheckFailure f = CheckFailure.of(DNS_RESOLVE).with("a", null).with("b", "  ").with("ips",
                    List.of("192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4", "192.0.2.5", "192.0.2.6", "192.0.2.7", "192.0.2.8", "192.0.2.9"));
            Map<String, Object> d = detail(f);
            assertThat(d).doesNotContainKeys("a", "b");
            assertThat((List<?>) d.get("ips")).hasSize(8);
        }

        @Test
        @DisplayName("applyTo: değişmez haritada fırlatmaz; reasonOf yalnız katalog kodunu kabul eder")
        void applyAndRead() {
            assertThatCode(() -> CheckFailure.of(UNKNOWN).applyTo(Map.of("open", false))).doesNotThrowAnyException();
            Map<String, Object> r = new LinkedHashMap<>();
            CheckFailure.of(ICMP_NO_REPLY).with("packet_loss", 100).applyTo(r);
            assertThat(CheckFailure.reasonOf(r)).isEqualTo("ICMP_NO_REPLY");
            assertThat(CheckFailure.detailOf(r)).contains("\"packet_loss\":100");
            Map<String, Object> bad = new HashMap<>();
            bad.put(CheckFailure.KEY_REASON, "NOT_A_CODE");
            assertThat(CheckFailure.reasonOf(bad)).isNull();
            assertThat(CheckFailure.reasonOf(null)).isNull();
            assertThat(CheckFailure.detailOf(Map.of(CheckFailure.KEY_DETAIL, "x".repeat(CheckFailure.DETAIL_MAX + 1)))).isNull();
        }

        @Test
        @DisplayName("katalog: kodlar benzersiz, kolon genişliğine sığar, her kodun evresi var")
        void catalogue() {
            assertThat(CheckFailureReason.CODES).doesNotHaveDuplicates().hasSize(CheckFailureReason.values().length);
            for (CheckFailureReason r : CheckFailureReason.values()) {
                assertThat(r.name().length()).isLessThanOrEqualTo(CheckFailureReason.CODE_MAX);
                assertThat(r.phase).isNotNull();
            }
        }
    }
}
