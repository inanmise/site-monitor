package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.service.tlsgrade.FakeTlsServer.Reply;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.HelloResult;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.Kind;
import com.sitemonitor.service.tlsgrade.TlsHelloProbe.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ham ClientHello yoklaması (2026-10-10) — sahte sunucuyla: sürüm kabul / ret, zaman aşımı, çöp yanıt, kapanma, vekil
 * reddi, zımbalama ve HelloRetryRequest. Her test kendi zaman sınırıyla koşar (sonsuz bekleme olmaz).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TlsHelloProbeTest {

    private static final int GCM = 0xc02f;

    @Test
    @DisplayName("TLS 1.0 kabul edilirse ACCEPTED ve seçilen sürüm/takım okunur")
    void tls10Accepted() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0301, 0xc013, false, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS10, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(r.version()).isEqualTo(TlsHelloProbe.TLS10);
            assertThat(r.cipherSuiteName()).isEqualTo("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA");
            assertThat(s.hellos.get(0).legacyVersion()).isEqualTo(TlsHelloProbe.TLS10);
            assertThat(s.hellos.get(0).sni()).isEqualTo("www.example.com");
        }
    }

    @Test
    @DisplayName("TLS 1.0 ölümcül protocol_version uyarısıyla reddedilirse REJECTED (+ uyarı kodu)")
    void tls10RejectedByAlert() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.alert(2, 70)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS10, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(r.alert()).isEqualTo(70);
        }
    }

    @Test
    @DisplayName("Sunucu başka sürüm seçerse (1.1 istendi, 1.0 geldi) REJECTED")
    void downgradeIsRejected() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0301, 0x002f, false, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS11, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(r.version()).isEqualTo(TlsHelloProbe.TLS10);
        }
    }

    @Test
    @DisplayName("Uyarı düzeyindeki unrecognized_name atlanır, ardından gelen ServerHello kabul edilir")
    void warningAlertSkipped() throws IOException {
        byte[] reply = FakeTlsServer.concat(FakeTlsServer.alert(1, 112), FakeTlsServer.serverHello(0x0303, GCM, false, false));
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(reply), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
        }
    }

    @Test
    @DisplayName("TLS 1.3: supported_versions + key_share gönderilir; seçilen 0x0304 → ACCEPTED")
    void tls13Accepted() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0304, 0x1301, true, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS13, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(r.version()).isEqualTo(TlsHelloProbe.TLS13);
            assertThat(s.hellos.get(0).offersTls13()).isTrue();
            assertThat(s.hellos.get(0).legacyVersion()).isEqualTo(0x0303);
        }
    }

    @Test
    @DisplayName("TLS 1.3 istendi ama sunucu 1.2 seçti (supported_versions yok) → REJECTED")
    void tls13NotSupported() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0303, GCM, false, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS13, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        }
    }

    @Test
    @DisplayName("Zımbalama: CertificateStatus görülürse YES")
    void staplingYes() throws IOException {
        byte[] reply = FakeTlsServer.concat(
                FakeTlsServer.serverHello(0x0303, GCM, false, true),
                FakeTlsServer.record(22, FakeTlsServer.handshake(11, new byte[300])),
                FakeTlsServer.record(22, FakeTlsServer.handshake(22, new byte[40])));
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(reply), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(r.stapling()).isEqualTo("YES");
            assertThat(s.hellos.get(0).statusRequest()).isTrue();
        }
    }

    @Test
    @DisplayName("Zımbalama: yankı var ama ServerHelloDone önce gelirse NO; yankı yoksa NO")
    void staplingNo() throws IOException {
        byte[] promisedButAbsent = FakeTlsServer.concat(
                FakeTlsServer.serverHello(0x0303, GCM, false, true),
                FakeTlsServer.record(22, FakeTlsServer.concat(FakeTlsServer.handshake(11, new byte[100]),
                        FakeTlsServer.handshake(14, new byte[0]))));
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(promisedButAbsent), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 3_000);
            assertThat(r.stapling()).isEqualTo("NO");
        }
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0303, GCM, false, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 3_000);
            assertThat(r.stapling()).isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("Zımbalama yankılandı ama sunucu sustu → kabul edildi, zımbalama UNKNOWN (tahmin yok)")
    void staplingUnknownOnStall() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> new Reply(FakeTlsServer.serverHello(0x0303, GCM, false, true), 3_000), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 1_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(r.stapling()).isEqualTo("UNKNOWN");
        }
    }

    @Test
    @DisplayName("Yanıt gelmezse süre sınırında TIMEOUT (asılı kalmaz)")
    void timeout() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.stall(4_000), 1)) {
            long t0 = System.currentTimeMillis();
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS10, Kind.VERSION, false, 800);
            assertThat(r.outcome()).isEqualTo(Outcome.TIMEOUT);
            assertThat(System.currentTimeMillis() - t0).isLessThan(3_000);
        }
    }

    @Test
    @DisplayName("Cevapsız kapanma CLOSED; HTTP gibi çöp yanıt PROTOCOL_ERROR")
    void closedAndGarbage() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.close(), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS10, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        }
        byte[] http = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(http), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.PROTOCOL_ERROR);
        }
    }

    @Test
    @DisplayName("Aşırı büyük kayıt uzunluğu PROTOCOL_ERROR (bellek ayırmadan)")
    void oversizedRecord() throws IOException {
        byte[] huge = {22, 3, 3, (byte) 0xff, (byte) 0xff};
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(huge), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.PROTOCOL_ERROR);
        }
    }

    @Test
    @DisplayName("Bağlantı / vekil reddi CONNECT_FAILED (istisna dışarı sızmaz)")
    void connectFailed() {
        HelloResult refused = TlsHelloProbe.probe(t -> { throw new ConnectException("Connection refused"); },
                "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 1_000);
        assertThat(refused.outcome()).isEqualTo(Outcome.CONNECT_FAILED);
        HelloResult proxy = TlsHelloProbe.probe(t -> { throw new IOException("vekil tüneli reddetti: HTTP/1.1 403 Forbidden"); },
                "www.example.com", TlsHelloProbe.TLS12, Kind.VERSION, true, 1_000);
        assertThat(proxy.outcome()).isEqualTo(Outcome.CONNECT_FAILED);
        assertThat(proxy.error()).contains("403");
    }

    @Test
    @DisplayName("Zayıf takım sorusu: yalnız zayıf takımlar önerilir; sunucu RC4 seçerse ACCEPTED")
    void weakCipherAccepted() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.serverHello(0x0303, 0x0005, false, false)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", 0, Kind.WEAK_CIPHERS, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(r.cipherSuiteName()).isEqualTo("TLS_RSA_WITH_RC4_128_SHA");
            for (int suite : s.hellos.get(0).suites()) {
                assertThat(TlsHelloProbe.cipherName(suite)).matches(".*(RC4|3DES|_DES_|EXPORT|NULL|anon).*");
            }
        }
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.of(FakeTlsServer.alert(2, 40)), 1)) {
            HelloResult r = TlsHelloProbe.probe(s.connector(), "www.example.com", 0, Kind.WEAK_CIPHERS, false, 3_000);
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        }
    }

    @Test
    @DisplayName("HelloRetryRequest TLS 1.3 desteğini kanıtlar; supported_versions'suz HRR bozuk sayılır")
    void helloRetryRequest() {
        byte[] hrrRandom = hexBytes("CF21AD74E59A6111BE1D8C021E65B891C2A211167ABB8C5E079E09E2C8A8339C");
        byte[] withSv = shBody(hrrRandom, true);
        TlsHelloProbe.ServerHello sh = TlsHelloProbe.parseServerHello(withSv, 0, withSv.length);
        assertThat(sh).isNotNull();
        assertThat(sh.version()).isEqualTo(TlsHelloProbe.TLS13);
        byte[] withoutSv = shBody(hrrRandom, false);
        assertThat(TlsHelloProbe.parseServerHello(withoutSv, 0, withoutSv.length)).isNull();
    }

    @Test
    @DisplayName("Kesik ServerHello gövdesi null döner (sınır dışı okuma yok)")
    void truncatedServerHello() {
        assertThat(TlsHelloProbe.parseServerHello(new byte[10], 0, 10)).isNull();
        byte[] b = shBody(new byte[32], false);
        b[34] = 40;   // oturum kimliği uzunluğu > 32
        assertThat(TlsHelloProbe.parseServerHello(b, 0, b.length)).isNull();
    }

    @Test
    @DisplayName("ClientHello: kayıt başlığı, IP'ye SNI yok, TLS 1.0 sorusunda imza algoritması yok")
    void clientHelloShape() throws IOException {
        byte[] hello = TlsHelloProbe.clientHello("10.1.2.3", TlsHelloProbe.TLS10, Kind.VERSION, false, new SecureRandom());
        assertThat(hello[0]).isEqualTo((byte) 22);
        assertThat(((hello[1] & 0xff) << 8) | (hello[2] & 0xff)).isEqualTo(0x0301);
        FakeTlsServer.Hello h = FakeTlsServer.readHello(new ByteArrayInputStream(hello));
        assertThat(h.sni()).isNull();
        assertThat(h.legacyVersion()).isEqualTo(TlsHelloProbe.TLS10);
        assertThat(h.offersTls13()).isFalse();
        FakeTlsServer.Hello h13 = FakeTlsServer.readHello(new ByteArrayInputStream(
                TlsHelloProbe.clientHello("example.com", TlsHelloProbe.TLS13, Kind.VERSION, false, new SecureRandom())));
        assertThat(h13.offersTls13()).isTrue();
        assertThat(h13.suites()).containsExactly(0x1301, 0x1302, 0x1303);
    }

    private static byte[] shBody(byte[] random, boolean supportedVersions) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(3); o.write(3);
        o.writeBytes(random);
        o.write(0);
        o.write(0x13); o.write(0x01);
        o.write(0);
        if (supportedVersions) {
            o.write(0); o.write(6);
            o.write(0); o.write(0x2b); o.write(0); o.write(2); o.write(3); o.write(4);
        } else {
            o.write(0); o.write(0);
        }
        return o.toByteArray();
    }

    private static byte[] hexBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
