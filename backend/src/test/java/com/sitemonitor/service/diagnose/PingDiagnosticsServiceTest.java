package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.service.PingCheckerService;
import com.sitemonitor.service.ProcessProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Ping uçtan uca tanılaması (2026-10-05) — her bulgu dalı sahte ağla (soket / DNS / süreç YOK): politika reddi, ad
 * çözümlenemedi, IP sürümüne adres yok, pod'da ICMP yok (+ TCP ile host ayakta), ICMP süzülüyor ama host ayakta, host
 * erişilemez, kısmi kayıp + yüksek RTT, başarı, traceroute (yok / var), izlemenin gerçek kontrolüyle uyuşmazlık, vekilin
 * uygulanmadığı bilgisi ve ping çıktısı ayrıştırması (iputils / busybox / Windows TR+EN).
 */
class PingDiagnosticsServiceTest {

    private static final String HOST = "edge.example.test";
    private static final String LINUX_OK = """
            PING 192.0.2.10 (192.0.2.10) 56(84) bytes of data.
            64 bytes from 192.0.2.10: icmp_seq=1 ttl=57 time=11.9 ms
            --- 192.0.2.10 ping statistics ---
            4 packets transmitted, 4 received, 0% packet loss, time 3004ms
            rtt min/avg/max/mdev = 11.1/12.3/13.0/0.5 ms
            """;
    private static final String LINUX_LOSS = """
            --- 192.0.2.10 ping statistics ---
            4 packets transmitted, 0 received, 100% packet loss, time 3060ms
            """;
    private static final String LINUX_PARTIAL_SLOW = """
            4 packets transmitted, 3 received, 25% packet loss, time 3005ms
            rtt min/avg/max/mdev = 300.1/320.5/351.0/12.0 ms
            """;

    private FakeNetDiagNetwork net;
    private NetDiagWorkers workers;
    private PingCheckerService checker;
    private PingDiagnosticsService svc;

    @BeforeEach
    void setUp() {
        net = new FakeNetDiagNetwork();
        workers = new NetDiagWorkers();
        checker = mock(PingCheckerService.class);
        svc = new PingDiagnosticsService(net, workers, checker);
        svc.env = k -> null;
        svc.windows = false;
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.10"), FakeNetDiagNetwork.ip("2001:db8::10")));
        when(checker.check(anyString(), org.mockito.ArgumentMatchers.any(), anyInt(), anyInt())).thenReturn(up(true));
    }

    @AfterEach
    void tearDown() { workers.shutdown(); }

    private static PingMonitor monitor(String ipVersion) {
        PingMonitor m = new PingMonitor();
        m.setId(7L);
        m.setName("Edge");
        m.setHost(HOST);
        m.setIpVersion(ipVersion);
        m.setTimeoutMs(3000);
        m.setPacketCount(3);
        return m;
    }

    private static Map<String, Object> up(boolean up) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("up", up);
        r.put("rtt_ms", up ? 12L : null);
        r.put("packet_loss", up ? 0 : 100);
        if (!up) r.put("error", "Yanıt yok (%100 paket kaybı)");
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> d) {
        return (List<Map<String, Object>>) d.get("findings");
    }

    private static List<Object> codes(Map<String, Object> d) {
        return findings(d).stream().map(f -> f.get("code")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> d, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) d.get("steps")) if (key.equals(s.get("key"))) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verdict(Map<String, Object> d) { return (Map<String, Object>) d.get("verdict"); }

    @Test
    @DisplayName("başarı: 4/4 paket, RTT ayrıştırılır; hüküm PING_OK; izlemenin gerçek kontrolü kendi ayarlarıyla çağrılır")
    void ok() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_OK, 0, false));
        net.connects.put("192.0.2.10:443", FakeNetDiagNetwork.FakeSocket.of(""));
        net.connects.put("192.0.2.10:80", new ConnectException("Connection refused"));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(verdict(d)).containsEntry("code", "PING_OK").containsEntry("status", "ok");
        assertThat(d).containsEntry("kind", "ping").containsKey("transcript");
        @SuppressWarnings("unchecked")
        Map<String, Object> icmp = (Map<String, Object>) step(d, "icmp").get("detail");
        assertThat(icmp).containsEntry("packets_sent", 4).containsEntry("packets_received", 4).containsEntry("loss_pct", 0.0)
                .containsEntry("rtt_avg_ms", 12.3).containsEntry("target_ip", "192.0.2.10");
        assertThat(String.valueOf(icmp.get("command"))).startsWith("ping").contains("192.0.2.10").doesNotContain("-4");
        assertThat(step(d, "policy")).containsEntry("status", "ok");
        assertThat(step(d, "tcp_alive")).containsEntry("status", "ok");
        assertThat(step(d, "traceroute")).isNull();
        verify(checker).check(eq(HOST), eq("auto"), eq(3), eq(3000));
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) d.get("client_check");
        assertThat(client).containsEntry("ok", true);
        assertThat(codes(d)).doesNotContain("CLIENT_MISMATCH", "PROXY_NOT_APPLICABLE");
        assertThat(String.valueOf(d.get("transcript"))).contains("4 packets transmitted");
    }

    @Test
    @DisplayName("politika reddi: hiçbir paket gönderilmez, gerçek kontrol çağrılmaz, kalan adımlar atlanır")
    void policyBlocked() {
        net.blocked.put(HOST, "izin verilmeyen hedef " + HOST + " → 169.254.169.254 (cloud-metadata endpoint)");
        Map<String, Object> d = svc.diagnose(monitor("auto"), true);
        assertThat(verdict(d)).containsEntry("code", "POLICY_BLOCKED").containsEntry("status", "fail");
        assertThat(step(d, "policy")).containsEntry("status", "fail");
        assertThat(step(d, "icmp")).containsEntry("status", "skip");
        assertThat(step(d, "traceroute")).containsEntry("status", "skip");
        assertThat(net.calls).noneMatch(c -> c.startsWith("exec") || c.startsWith("connect"));
        verifyNoMoreInteractions(checker);
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) d.get("client_check");
        assertThat(client).containsEntry("skipped", true);
    }

    @Test
    @DisplayName("ad çözümlenemedi → DNS_FAILED; IP sürümüne adres yok → NO_ADDRESS_FOR_IP_VERSION")
    void dnsFailures() {
        net.unresolvable.add(HOST);
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(verdict(d)).containsEntry("code", "DNS_FAILED");
        assertThat(step(d, "dns")).containsEntry("status", "fail");

        net.unresolvable.clear();
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.10")));
        Map<String, Object> d2 = svc.diagnose(monitor("v6"), false);
        assertThat(verdict(d2)).containsEntry("code", "NO_ADDRESS_FOR_IP_VERSION");
        assertThat(net.calls).noneMatch(c -> c.startsWith("exec"));
    }

    @Test
    @DisplayName("pod'da ICMP yok → ICMP_UNAVAILABLE_HERE (warn), TCP 443 açık → reason=alive")
    void icmpUnavailable() {
        net.commands.put("ping", new ProcessProbe.Result("ping: socket: Operation not permitted", 2, false));
        net.connects.put("192.0.2.10:443", FakeNetDiagNetwork.FakeSocket.of(""));
        when(checker.check(anyString(), org.mockito.ArgumentMatchers.any(), anyInt(), anyInt()))
                .thenReturn(Map.of("up", false, "na", true, "error", "ICMP bu ortamda kullanılamıyor (yetki/binary)"));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(verdict(d)).containsEntry("code", "ICMP_UNAVAILABLE_HERE").containsEntry("status", "warn");
        assertThat(findings(d).get(0).get("params")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) verdict(d).get("params");
        assertThat(p).containsEntry("reason", "alive");
        assertThat(step(d, "icmp")).containsEntry("status", "warn");
        assertThat(codes(d)).doesNotContain("CLIENT_MISMATCH");   // ikisi de "ICMP yok" — uyuşmazlık değil
    }

    @Test
    @DisplayName("%100 kayıp + TCP RST (refused) → ICMP_FILTERED_HOST_ALIVE (port bilgisiyle)")
    void filteredButAlive() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_LOSS, 1, false));
        net.connects.put("192.0.2.10:443", new ConnectException("Connection refused"));
        when(checker.check(anyString(), org.mockito.ArgumentMatchers.any(), anyInt(), anyInt())).thenReturn(up(false));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(verdict(d)).containsEntry("code", "ICMP_FILTERED_HOST_ALIVE").containsEntry("status", "fail");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) verdict(d).get("params");
        assertThat(p).containsEntry("port", 443).containsEntry("ip", "192.0.2.10");
        assertThat(step(d, "icmp")).containsEntry("status", "fail");
    }

    @Test
    @DisplayName("%100 kayıp + TCP yanıtsız → HOST_UNREACHABLE")
    void hostUnreachable() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_LOSS, 1, false));
        when(checker.check(anyString(), org.mockito.ArgumentMatchers.any(), anyInt(), anyInt())).thenReturn(up(false));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(verdict(d)).containsEntry("code", "HOST_UNREACHABLE");
        assertThat(step(d, "tcp_alive")).containsEntry("status", "warn");
    }

    @Test
    @DisplayName("kısmi kayıp + yüksek RTT → PARTIAL_LOSS + HIGH_RTT (warn) ve PING_OK bilgi; IPv4 komutu -4 taşır")
    void partialLossAndHighRtt() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_PARTIAL_SLOW, 0, false));
        Map<String, Object> d = svc.diagnose(monitor("v4"), false);
        assertThat(verdict(d)).containsEntry("status", "warn").containsEntry("code", "PARTIAL_LOSS");
        assertThat(codes(d)).contains("PARTIAL_LOSS", "HIGH_RTT", "PING_OK");
        assertThat(codes(d).indexOf("PING_OK")).isGreaterThan(codes(d).indexOf("HIGH_RTT"));   // önem sırası
        assertThat(net.calls).anyMatch(c -> c.startsWith("exec ping -4"));
    }

    @Test
    @DisplayName("gerçek kontrol tanılamayla uyuşmazsa CLIENT_MISMATCH (warn)")
    void clientMismatch() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_OK, 0, false));
        when(checker.check(anyString(), org.mockito.ArgumentMatchers.any(), anyInt(), anyInt())).thenReturn(up(false));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(codes(d)).contains("CLIENT_MISMATCH");
        assertThat(verdict(d)).containsEntry("code", "CLIENT_MISMATCH").containsEntry("status", "warn");
    }

    @Test
    @DisplayName("traceroute: yalnız istenirse; komut yoksa TRACEROUTE_UNAVAILABLE (info, adım skip); varsa atlama sayısı")
    void traceroute() {
        net.commands.put("ping", new ProcessProbe.Result(LINUX_OK, 0, false));
        Map<String, Object> none = svc.diagnose(monitor("auto"), false);
        assertThat(net.calls).noneMatch(c -> c.startsWith("exec traceroute"));
        assertThat(step(none, "traceroute")).isNull();

        Map<String, Object> na = svc.diagnose(monitor("auto"), true);
        assertThat(step(na, "traceroute")).containsEntry("status", "skip");
        assertThat(codes(na)).contains("TRACEROUTE_UNAVAILABLE");

        net.commands.put("traceroute", new ProcessProbe.Result("""
                traceroute to 192.0.2.10 (192.0.2.10), 15 hops max
                 1  198.51.100.1  0.512 ms
                 2  203.0.113.7  4.100 ms
                 3  192.0.2.10  11.020 ms
                """, 0, false));
        Map<String, Object> d = svc.diagnose(monitor("auto"), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> tr = (Map<String, Object>) step(d, "traceroute").get("detail");
        assertThat(tr).containsEntry("hops", 3).containsEntry("reached", true).containsEntry("available", true);
        assertThat(step(d, "traceroute")).containsEntry("status", "ok");
        assertThat(net.calls).anyMatch(c -> c.startsWith("exec traceroute -n -m 15"));
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) d.get("options");
        assertThat(options).containsEntry("traceroute", true);
    }

    @Test
    @DisplayName("vekil tanımlıyken PROXY_NOT_APPLICABLE (info); vekil parolası hiçbir alanda düz görünmez")
    void proxyNotApplicable_andSecretMasked() throws Exception {
        net.proxy = true;
        net.proxySecret = "Prx-S3cret-Value";
        net.commands.put("ping", new ProcessProbe.Result(LINUX_OK + "\nnot: Prx-S3cret-Value", 0, false));
        Map<String, Object> d = svc.diagnose(monitor("auto"), false);
        assertThat(codes(d)).contains("PROXY_NOT_APPLICABLE");
        assertThat(verdict(d)).containsEntry("code", "PING_OK");
        assertThat(new ObjectMapper().writeValueAsString(d)).doesNotContain("Prx-S3cret-Value");
        verify(checker, never()).checkAsync(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("ayrıştırma: busybox, Windows EN ve Windows TR çıktıları; yetki hatası = unavailable")
    void parse() {
        PingDiagnosticsService.IcmpStats bb = PingDiagnosticsService.parse("""
                4 packets transmitted, 2 packets received, 50% packet loss
                round-trip min/avg/max = 1.100/2.200/3.300 ms
                """);
        assertThat(bb.sent()).isEqualTo(4);
        assertThat(bb.received()).isEqualTo(2);
        assertThat(bb.lossPct()).isEqualTo(50.0);
        assertThat(bb.rttAvg()).isEqualTo(2.2);

        PingDiagnosticsService.IcmpStats win = PingDiagnosticsService.parse("""
                Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),
                Minimum = 1ms, Maximum = 3ms, Average = 2ms
                """);
        assertThat(win.sent()).isEqualTo(4);
        assertThat(win.lossPct()).isEqualTo(0.0);
        assertThat(win.rttMin()).isEqualTo(1.0);
        assertThat(win.rttMax()).isEqualTo(3.0);
        assertThat(win.rttAvg()).isEqualTo(2.0);

        PingDiagnosticsService.IcmpStats tr = PingDiagnosticsService.parse("""
                Paketler: Gönderilen = 4, Alınan = 0, Kaybedilen = 4 (%100 kayıp),
                """);
        assertThat(tr.sent()).isEqualTo(4);
        assertThat(tr.received()).isEqualTo(0);
        assertThat(tr.lossPct()).isEqualTo(100.0);

        assertThat(PingDiagnosticsService.parse("komut çalıştırılamadı: ping").unavailable()).isTrue();
        assertThat(PingDiagnosticsService.parse("ping: permission denied (are you root?)").unavailable()).isTrue();
    }
}
