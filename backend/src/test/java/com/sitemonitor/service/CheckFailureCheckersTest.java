package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.page.PageFetchCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Kontrol hata teşhisi (2026-10-05) — CHECKER düzeyi: her checker başarısız sonuca {@code failure_reason} +
 * {@code failure_detail} ekler, başarılı sonuca HİÇBİR ŞEY eklemez ve mevcut alanları (open/up/status/error) değiştirmez.
 * Gerçek soketler yalnız 127.0.0.1 (izin verici SsrfGuard; metadata yine bloklu); ping çıktısı sabit (ProcessProbe mock).
 */
class CheckFailureCheckersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        return new SsrfGuard(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<String, Object> r) throws Exception {
        return JSON.readValue((String) r.get(CheckFailure.KEY_DETAIL), Map.class);
    }

    /** Tek bağlantıyı kabul edip sabit baytları yazan yerel sunucu (HTTP durum satırı / banner taklidi). */
    private static ServerSocket serveOnce(String payload) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = server.accept(); OutputStream os = s.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.ISO_8859_1));
                os.flush();
                Thread.sleep(200);
            } catch (Exception ignore) { /* test sonu */ }
        }, "chkfail-serve");
        t.setDaemon(true);
        t.start();
        return server;
    }

    // ── Port ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Port: kapalı yerel port → CONNECT_REFUSED + hedef/tür/yol/çözümlenen IP; bekleyen anahtar sızmaz")
    void port_closed_refused() throws Exception {
        PortCheckerService svc = new PortCheckerService(permissiveGuard());
        Map<String, Object> r = svc.check("127.0.0.1", 1, 800);
        assertThat(r.get("open")).isEqualTo(false);
        assertThat(r.get("error")).isNotNull();
        assertThat(r.get(CheckFailure.KEY_REASON)).isIn("CONNECT_REFUSED", "CONNECT_TIMEOUT");
        assertThat(r).doesNotContainKey(PortCheckerService.PENDING_FAILURE);
        Map<String, Object> d = detail(r);
        assertThat(d).containsEntry("target", "127.0.0.1:1").containsEntry("protocol", "TCP").containsEntry("via", "direct")
                .containsEntry("timeout_ms", 800).containsEntry("phase", "CONNECT");
        assertThat(String.valueOf(d.get("resolved_ips"))).contains("127.0.0.1");
    }

    @Test
    @DisplayName("Port: açık port → neden anahtarı YOK (başarılı satıra teşhis yazılmaz)")
    void port_open_noFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Map<String, Object> r = new PortCheckerService(permissiveGuard()).check("127.0.0.1", server.getLocalPort(), 1000);
            assertThat(r.get("open")).isEqualTo(true);
            assertThat(r).doesNotContainKeys(CheckFailure.KEY_REASON, CheckFailure.KEY_DETAIL, PortCheckerService.PENDING_FAILURE);
        }
    }

    @Test
    @DisplayName("Port HTTP: beklenmeyen durum kodu → HTTP_STATUS + kod + beklenen; error metni eskisi gibi")
    void port_http_status() throws Exception {
        try (ServerSocket server = serveOnce("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")) {
            Map<String, Object> r = new PortCheckerService(permissiveGuard())
                    .check("127.0.0.1", server.getLocalPort(), 2000, "HTTP", "/", "2xx");
            assertThat(r.get("open")).isEqualTo(false);
            assertThat(r.get("error")).isEqualTo("HTTP 503 (beklenen: 2xx)");
            assertThat(r.get(CheckFailure.KEY_REASON)).isEqualTo("HTTP_STATUS");
            assertThat(detail(r)).containsEntry("http_status", 503).containsEntry("expected", "2xx").containsEntry("protocol", "HTTP");
        }
    }

    @Test
    @DisplayName("Port BANNER: beklenen yanıt yok → BANNER_MISMATCH + beklenen + gelen")
    void port_banner_mismatch() throws Exception {
        try (ServerSocket server = serveOnce("SSH-2.0-OpenSSH_test\r\n")) {
            Map<String, Object> r = new PortCheckerService(permissiveGuard())
                    .check("127.0.0.1", server.getLocalPort(), 2000, "BANNER", null, "220");
            assertThat(r.get("open")).isEqualTo(false);
            assertThat(r.get(CheckFailure.KEY_REASON)).isEqualTo("BANNER_MISMATCH");
            assertThat(detail(r)).containsEntry("expected", "220").containsEntry("got", "SSH-2.0-OpenSSH_test");
        }
    }

    @Test
    @DisplayName("Port: bulut metadata adresi → SSRF_BLOCKED (istek hiç atılmadı)")
    void port_ssrfBlocked() throws Exception {
        Map<String, Object> r = new PortCheckerService(permissiveGuard()).check("169.254.169.254", 80, 500);
        assertThat(r.get("open")).isEqualTo(false);
        assertThat(r.get(CheckFailure.KEY_REASON)).isEqualTo("SSRF_BLOCKED");
        assertThat(detail(r)).containsEntry("phase", "POLICY");
    }

    // ── Uptime ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Durum (uptime): kapalı port → neden + via=direct + çözümlenen IP; açık port → anahtar yok")
    void uptime() throws Exception {
        UptimeHttpCheckerService svc = new UptimeHttpCheckerService(permissiveGuard());
        Map<String, Object> down = svc.check("127.0.0.1", 1, 800);
        assertThat(down.get("status")).isEqualTo("down");
        assertThat(down.get(CheckFailure.KEY_REASON)).isIn("CONNECT_REFUSED", "CONNECT_TIMEOUT");
        assertThat(detail(down)).containsEntry("via", "direct").containsEntry("target", "127.0.0.1:1");
        try (ServerSocket server = new ServerSocket(0)) {
            Map<String, Object> up = svc.check("127.0.0.1", server.getLocalPort(), 1000);
            assertThat(up.get("status")).isEqualTo("up");
            assertThat(up).doesNotContainKeys(CheckFailure.KEY_REASON, CheckFailure.KEY_DETAIL);
        }
    }

    // ── Ping ──────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> ping(String canned) {
        try (MockedStatic<ProcessProbe> probe = mockStatic(ProcessProbe.class)) {
            probe.when(() -> ProcessProbe.run(anyList(), (String) org.mockito.ArgumentMatchers.isNull(), anyInt()))
                    .thenReturn(new ProcessProbe.Result(canned, 0, false));
            return new PingCheckerService().check("host.example.test", "auto", 4, 3000);
        }
    }

    @Test
    @DisplayName("Ping: ICMP yok → ICMP_UNAVAILABLE (up=false, na=true AYNEN); %100 kayıp → ICMP_NO_REPLY; ad yok → DNS_RESOLVE")
    void ping_reasons() throws Exception {
        Map<String, Object> na = ping("ping: socket: Operation not permitted");
        assertThat(na.get("up")).isEqualTo(false);
        assertThat(na.get("na")).isEqualTo(true);
        assertThat(na.get(CheckFailure.KEY_REASON)).isEqualTo("ICMP_UNAVAILABLE");

        Map<String, Object> loss = ping("4 packets transmitted, 0 received, 100% packet loss, time 3000ms");
        assertThat(loss.get("up")).isEqualTo(false);
        assertThat(loss.get("error")).isEqualTo("Yanıt yok (%100 paket kaybı)");
        assertThat(loss.get(CheckFailure.KEY_REASON)).isEqualTo("ICMP_NO_REPLY");
        assertThat(detail(loss)).containsEntry("packet_loss", 100).containsEntry("packets", 4)
                .containsEntry("target", "host.example.test").containsEntry("timeout_ms", 3000);

        assertThat(ping("ping: unknown host nx.example.test").get(CheckFailure.KEY_REASON)).isEqualTo("DNS_RESOLVE");

        Map<String, Object> ok = ping("4 packets transmitted, 4 received, 0% packet loss\nrtt min/avg/max/mdev = 1.0/2.0/3.0/0.1 ms");
        assertThat(ok.get("up")).isEqualTo(true);
        assertThat(ok).doesNotContainKeys(CheckFailure.KEY_REASON, CheckFailure.KEY_DETAIL);
    }

    // ── DNS ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DNS: geçersiz ad (boş etiket) → CONFIG_ERROR + kayıt türü; success/values/error sözleşmesi aynı")
    void dns_invalidName() throws Exception {
        DnsCheckerService svc = new DnsCheckerService(mock(AppSettingsService.class));
        Map<String, Object> r = svc.check("a..b.example.test", "A");
        assertThat(r.get("success")).isEqualTo(false);
        assertThat(r.get("values")).isEqualTo(List.of());
        assertThat(r).containsKey("error");
        assertThat(r.get(CheckFailure.KEY_REASON)).isEqualTo("CONFIG_ERROR");
        assertThat(detail(r)).containsEntry("record_type", "A").containsEntry("target", "a..b.example.test");
    }

    // ── Sayfa çekimi (Sayfa Bütünlüğü + Sayfa Hızı ortak çekirdeği) ─────────────────────────────

    @Test
    @DisplayName("Sayfa çekimi: kapalı port → istisnadan sınıflandırma; metadata → SSRF_BLOCKED; sınıflandırma ana sayfa nedenine akar")
    void pageFetch() {
        PageFetchCore core = new PageFetchCore(permissiveGuard());
        core.init();
        // Windows'ta kapalı yerel porta bağlantı reddi ~2 sn sürebilir → bütçe geniş; üç sonuç da istisnadan sınıflanır
        PageFetchCore.Fetch closed = core.fetch("http://127.0.0.1:1/", "GET", PageFetchCore.FetchOptions.body(5000, "test"));
        assertThat(closed.status()).isZero();
        assertThat(closed.failure()).isNotNull();
        assertThat(closed.failure().code()).isIn("CONNECT_REFUSED", "CONNECT_TIMEOUT", "READ_TIMEOUT");
        assertThat(closed.failure().detail()).containsEntry("target", "127.0.0.1").containsEntry("via", "direct");

        PageFetchCore.Fetch blocked = core.fetch("http://169.254.169.254/", "GET", PageFetchCore.FetchOptions.body(1500, "test"));
        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.failure().code()).isEqualTo("SSRF_BLOCKED");

        assertThat(PageCheckerService.mainFailure(closed).code()).isEqualTo(closed.failure().code());
        PageFetchCore.Fetch http = new PageFetchCore.Fetch(503, 10, 20, 0, null, null, false, false);
        assertThat(PageCheckerService.mainFailure(http).code()).isEqualTo("HTTP_STATUS");
        assertThat(PageCheckerService.mainFailure(http).detail()).containsEntry("http_status", 503);
    }
}
