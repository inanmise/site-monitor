package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DnsCheckerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DNS uçtan uca tanılaması (2026-10-05) — sahte DNS yanıtlayıcısıyla (soket yok) her bulgu dalı: başarı, yetkili NXDOMAIN,
 * yalnız çözümleyicide NXDOMAIN (bayat negatif önbellek), DNSSEC kaynaklı SERVFAIL (CD ile yanıt), düz SERVFAIL, REFUSED,
 * tüm çözümleyiciler yanıtsız, çözümleyiciler/yetkili uyuşmazlığı, topal yetki + politika gereği sorulmayan NS, istenen
 * türde kayıt yok (CNAME / kardeş tür ipucu), beklenen değer dışı, kesilmiş UDP → TCP, yavaş çözümleyici, gerçek kontrolle
 * uyuşmazlık ve yan etkisizlik (yalnız {@code DnsCheckerService.check} — kayıt / değişiklik tespiti yok).
 */
class DnsDiagnosticsServiceTest {

    private static final String NAME = "www.example.test";
    private static final String ZONE = "example.test";
    private static final String RESOLVER = "192.0.2.53";
    private static final String PROP = "198.51.100.53";
    private static final String NS1_IP = "203.0.113.1";
    private static final String NS2_IP = "203.0.113.2";

    private FakeNetDiagNetwork net;
    private NetDiagWorkers workers;
    private DnsCheckerService checker;
    private AppSettingsService settings;
    private DnsDiagnosticsService svc;

    /** Senaryo: her sunucu için (ad, tür) → yanıt üretici. */
    interface Answer { Message of(Message q, String server, boolean tcp) throws Exception; }

    private Answer resolverAnswer;
    private Answer propAnswer;
    private Answer ns1Answer;
    private Answer ns2Answer;

    @BeforeEach
    void setUp() throws Exception {
        net = new FakeNetDiagNetwork();
        workers = new NetDiagWorkers();
        checker = mock(DnsCheckerService.class);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(eq("site.monitor.dns.query-timeout-ms"), anyInt())).thenReturn(2000);
        when(settings.getString(eq("site.monitor.dns.resolvers"), anyString())).thenReturn(PROP);
        svc = new DnsDiagnosticsService(net, workers, checker, settings);
        svc.env = k -> null;
        net.resolvers.add(new InetSocketAddress(FakeNetDiagNetwork.ip(RESOLVER), 53));
        net.hosts.put("ns1.example.test", List.of(FakeNetDiagNetwork.ip(NS1_IP)));
        net.hosts.put("ns2.example.test", List.of(FakeNetDiagNetwork.ip(NS2_IP)));
        when(checker.check(anyString(), anyString())).thenReturn(success(true, "192.0.2.10"));

        // Varsayılan: sağlıklı bölge
        resolverAnswer = (q, s, tcp) -> standardResolver(q, a("192.0.2.10"));
        propAnswer = resolverAnswer;
        ns1Answer = (q, s, tcp) -> authoritative(q, true, a("192.0.2.10"));
        ns2Answer = ns1Answer;
        net.dns = (server, q, tcp) -> {
            try {
                return switch (server) {
                    case RESOLVER -> resolverAnswer.of(q, server, tcp);
                    case PROP -> propAnswer.of(q, server, tcp);
                    case NS1_IP -> ns1Answer.of(q, server, tcp);
                    case NS2_IP -> ns2Answer.of(q, server, tcp);
                    default -> null;
                };
            } catch (java.io.IOException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
        };
    }

    @AfterEach
    void tearDown() { workers.shutdown(); }

    // ── Yanıt kurucuları ──────────────────────────────────────────────────────────────────────

    private static Name n(String s) throws Exception { return Name.fromString(s.endsWith(".") ? s : s + "."); }

    private static Record a(String ip) throws Exception { return new ARecord(n(NAME), DClass.IN, 300, FakeNetDiagNetwork.ip(ip)); }

    private static SOARecord soa(long serial) throws Exception {
        return new SOARecord(n(ZONE), DClass.IN, 3600, n("ns1.example.test"), n("hostmaster.example.test"), serial, 7200, 900, 1209600, 60);
    }

    private static Message reply(Message q, int rcode, boolean aa, List<Record> answers, List<Record> authority) {
        Message r = new Message(q.getHeader().getID());
        r.getHeader().setFlag(Flags.QR);
        if (q.getHeader().getFlag(Flags.RD)) {
            r.getHeader().setFlag(Flags.RD);
            r.getHeader().setFlag(Flags.RA);
        }
        if (aa) r.getHeader().setFlag(Flags.AA);
        r.getHeader().setRcode(rcode);
        r.addRecord(q.getQuestion(), Section.QUESTION);
        for (Record x : answers) r.addRecord(x, Section.ANSWER);
        for (Record x : authority) r.addRecord(x, Section.AUTHORITY);
        return r;
    }

    private static String qname(Message q) { return DnsDiagnosticsService.trimDot(q.getQuestion().getName().toString()); }

    /** Çözümleyici: bölge keşfi (SOA/NS) standart; hedef sorguya verilen kayıt. */
    private static Message standardResolver(Message q, Record target) throws Exception {
        int t = q.getQuestion().getType();
        String name = qname(q);
        if (t == Type.SOA && name.equals(NAME)) return reply(q, Rcode.NOERROR, false, List.of(), List.of(soa(2026100501)));
        if (t == Type.SOA && name.equals(ZONE)) return reply(q, Rcode.NOERROR, false, List.of(soa(2026100501)), List.of());
        if (t == Type.NS && name.equals(ZONE)) {
            return reply(q, Rcode.NOERROR, false, List.of(new NSRecord(n(ZONE), DClass.IN, 3600, n("ns2.example.test")),
                    new NSRecord(n(ZONE), DClass.IN, 3600, n("ns1.example.test"))), List.of());
        }
        if (t == Type.A && name.equals(NAME)) return target == null ? reply(q, Rcode.NOERROR, false, List.of(), List.of(soa(1)))
                : reply(q, Rcode.NOERROR, false, List.of(target), List.of());
        return reply(q, Rcode.NOERROR, false, List.of(), List.of(soa(1)));
    }

    private static Message authoritative(Message q, boolean aa, Record target) throws Exception {
        int t = q.getQuestion().getType();
        if (t == Type.SOA) return reply(q, Rcode.NOERROR, aa, List.of(soa(2026100501)), List.of());
        if (target == null) return reply(q, Rcode.NXDOMAIN, aa, List.of(), List.of(soa(2026100501)));
        return reply(q, Rcode.NOERROR, aa, List.of(target), List.of());
    }

    private static DnsMonitor monitor(String type) {
        DnsMonitor m = new DnsMonitor();
        m.setId(21L);
        m.setName("Web A");
        m.setDomain(NAME);
        m.setRecordType(type);
        return m;
    }

    private static Map<String, Object> success(boolean ok, String... values) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", ok);
        r.put("values", List.of(values));
        r.put("ttl", ok ? 300L : null);
        r.put("response_ms", 5L);
        if (!ok) r.put("error", "NXDOMAIN");
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> d) { return (List<Map<String, Object>>) d.get("findings"); }

    private static List<Object> codes(Map<String, Object> d) { return findings(d).stream().map(f -> f.get("code")).toList(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verdict(Map<String, Object> d) { return (Map<String, Object>) d.get("verdict"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> d) { return (Map<String, Object>) verdict(d).get("params"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> d, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) d.get("steps")) if (key.equals(s.get("key"))) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dns(Map<String, Object> d) { return (Map<String, Object>) d.get("dns"); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> d, String key) { return (List<Map<String, Object>>) dns(d).get(key); }

    // ── Testler ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("başarı: çözümleyici + iki yetkili aynı yanıt → DNS_OK; adımlar; yan etkisizlik (yalnız check)")
    void ok() {
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "DNS_OK").containsEntry("status", "ok");
        assertThat(step(d, "resolvers")).containsEntry("status", "ok");
        assertThat(step(d, "dnssec")).containsEntry("status", "skip");
        assertThat(step(d, "zone")).containsEntry("status", "ok");
        assertThat(step(d, "authoritative")).containsEntry("status", "ok");
        assertThat(step(d, "compare")).containsEntry("status", "ok");
        assertThat(dns(d)).containsEntry("zone", ZONE).containsEntry("ns", List.of("ns1.example.test", "ns2.example.test"));
        assertThat(rows(d, "resolvers")).hasSize(1);
        assertThat(rows(d, "resolvers").get(0)).containsEntry("server", RESOLVER).containsEntry("rcode", "NOERROR")
                .containsEntry("answers", List.of("192.0.2.10")).containsEntry("ttl", 300L);
        assertThat(rows(d, "authoritative")).hasSize(2);
        assertThat(rows(d, "authoritative").get(0)).containsEntry("aa", true).containsEntry("soa_serial", 2026100501L);
        verify(checker).check(NAME, "A");
        verifyNoMoreInteractions(checker);
        // Yetkili sorgular RD kapalı; yetkili adları SSRF politikasından geçti
        assertThat(net.calls).contains("vet ns1.example.test", "vet ns2.example.test");
        assertThat(String.valueOf(d.get("transcript"))).contains("norecurse").contains("NOERROR");
    }

    @Test
    @DisplayName("yetkili de NXDOMAIN → NXDOMAIN_AUTHORITATIVE (doğrulanmış, varyant yok)")
    void nxdomainAuthoritative() {
        resolverAnswer = (q, s, tcp) -> q.getQuestion().getType() == Type.A
                ? reply(q, Rcode.NXDOMAIN, false, List.of(), List.of(soa(1))) : standardResolver(q, null);
        ns1Answer = (q, s, tcp) -> authoritative(q, true, null);
        ns2Answer = ns1Answer;
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "NXDOMAIN_AUTHORITATIVE").containsEntry("status", "fail");
        assertThat(params(d)).containsEntry("zone", ZONE).containsEntry("reason", null);
        assertThat(codes(d)).doesNotContain("CLIENT_MISMATCH");
    }

    @Test
    @DisplayName("çözümleyici NXDOMAIN ama yetkili yanıt veriyor → NXDOMAIN_RESOLVER_ONLY (negatif TTL ile)")
    void nxdomainResolverOnly() {
        resolverAnswer = (q, s, tcp) -> q.getQuestion().getType() == Type.A
                ? reply(q, Rcode.NXDOMAIN, false, List.of(), List.of(soa(1))) : standardResolver(q, null);
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "NXDOMAIN_RESOLVER_ONLY");
        assertThat(params(d)).containsEntry("negative_ttl", 60L).containsEntry("auth_values", "192.0.2.10");
    }

    @Test
    @DisplayName("SERVFAIL + CD ile yanıt → SERVFAIL_DNSSEC; CD ile de SERVFAIL → SERVFAIL")
    void servfail() {
        resolverAnswer = (q, s, tcp) -> q.getHeader().getFlag(Flags.CD)
                ? reply(q, Rcode.NOERROR, false, List.of(a("192.0.2.10")), List.of())
                : reply(q, Rcode.SERVFAIL, false, List.of(), List.of());
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "SERVFAIL_DNSSEC");
        assertThat(step(d, "dnssec")).containsEntry("status", "fail");
        @SuppressWarnings("unchecked")
        Map<String, Object> ds = (Map<String, Object>) step(d, "dnssec").get("detail");
        assertThat(ds).containsEntry("validation_failure", true);
        assertThat(rows(d, "resolvers").get(0)).containsEntry("cd_rcode", "NOERROR");
        assertThat(codes(d)).contains("ZONE_NOT_FOUND");   // SERVFAIL'de bölge bulunamadı — çöküş değil, bulgu
        assertThat(step(d, "authoritative")).containsEntry("status", "skip");

        resolverAnswer = (q, s, tcp) -> reply(q, Rcode.SERVFAIL, false, List.of(), List.of());
        assertThat(verdict(svc.diagnose(monitor("A")))).containsEntry("code", "SERVFAIL");
    }

    @Test
    @DisplayName("REFUSED → RESOLVER_REFUSED; tüm çözümleyiciler yanıtsız → RESOLVER_TIMEOUT, bölge/yetkili adımları atlanır")
    void refusedAndTimeout() {
        resolverAnswer = (q, s, tcp) -> reply(q, Rcode.REFUSED, false, List.of(), List.of());
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        assertThat(verdict(svc.diagnose(monitor("A")))).containsEntry("code", "RESOLVER_REFUSED");

        resolverAnswer = (q, s, tcp) -> null;   // zaman aşımı
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "RESOLVER_TIMEOUT").containsEntry("status", "fail");
        assertThat(params(d)).containsEntry("servers", RESOLVER).containsEntry("reason", null);
        assertThat(step(d, "zone")).containsEntry("status", "skip");
        assertThat(step(d, "authoritative")).containsEntry("status", "skip");
        assertThat(rows(d, "resolvers").get(0)).containsEntry("error_kind", "timeout");
    }

    @Test
    @DisplayName("yayılım çözümleyicisi farklı yanıt → RESOLVERS_DISAGREE; yetkili farklı → AUTH_RESOLVER_MISMATCH (kalan TTL)")
    void disagreements() {
        DnsMonitor m = monitor("A");
        m.setPropagationCheck(true);
        propAnswer = (q, s, tcp) -> standardResolver(q, a("192.0.2.99"));
        ns1Answer = (q, s, tcp) -> authoritative(q, true, a("192.0.2.11"));
        ns2Answer = ns1Answer;
        Map<String, Object> d = svc.diagnose(m);
        assertThat(verdict(d)).containsEntry("status", "warn");
        assertThat(codes(d)).contains("RESOLVERS_DISAGREE", "AUTH_RESOLVER_MISMATCH", "DNS_OK");
        Map<String, Object> mm = findings(d).stream().filter(f -> "AUTH_RESOLVER_MISMATCH".equals(f.get("code"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) mm.get("params");
        assertThat(p).containsEntry("ttl_remaining", 300L).containsEntry("auth_values", "192.0.2.11");
        assertThat(rows(d, "resolvers")).hasSize(2);
        assertThat(rows(d, "resolvers").get(1)).containsEntry("label", "propagation");
        assertThat(step(d, "compare")).containsEntry("status", "warn");
    }

    @Test
    @DisplayName("topal yetki (AA yok) → LAME_DELEGATION; politika gereği sorulmayan NS → AUTH_UNREACHABLE (reason=policy), çöküş yok")
    void lameAndUnreachable() {
        ns2Answer = (q, s, tcp) -> authoritative(q, false, a("192.0.2.10"));
        net.hosts.remove("ns1.example.test");
        net.blocked.put("ns1.example.test", "izin verilmeyen hedef ns1.example.test → 127.0.0.1 (loopback/any-local)");
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(codes(d)).contains("LAME_DELEGATION", "AUTH_UNREACHABLE");
        Map<String, Object> un = findings(d).stream().filter(f -> "AUTH_UNREACHABLE".equals(f.get("code"))).findFirst().orElseThrow();
        assertThat(un.get("params")).asString().contains("policy").contains("ns1.example.test");
        assertThat(step(d, "authoritative")).containsEntry("status", "fail");
        assertThat(rows(d, "authoritative").get(0)).containsEntry("error_kind", "policy");
        assertThat(net.calls).noneMatch(c -> c.startsWith("dns 127.0.0.1"));
    }

    @Test
    @DisplayName("istenen türde kayıt yok: CNAME varsa reason=cname; yoksa kardeş tür (AAAA→A) ipucu")
    void noRecordOfType() {
        resolverAnswer = (q, s, tcp) -> q.getQuestion().getType() == Type.A && qname(q).equals(NAME)
                ? reply(q, Rcode.NOERROR, false, List.of(new CNAMERecord(n(NAME), DClass.IN, 300, n("lb.example.test"))), List.of())
                : standardResolver(q, null);
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "NO_RECORD_OF_TYPE");
        assertThat(params(d)).containsEntry("reason", "cname").containsEntry("cname", "lb.example.test.");

        resolverAnswer = (q, s, tcp) -> {
            int t = q.getQuestion().getType();
            if (t == Type.AAAA && qname(q).equals(NAME)) return reply(q, Rcode.NOERROR, false, List.of(), List.of(soa(1)));
            if (t == Type.A && qname(q).equals(NAME)) return reply(q, Rcode.NOERROR, false, List.of(a("192.0.2.10")), List.of());
            return standardResolver(q, null);
        };
        ns1Answer = (q, s, tcp) -> q.getQuestion().getType() == Type.AAAA
                ? reply(q, Rcode.NOERROR, true, List.of(), List.of(soa(2026100501))) : authoritative(q, true, a("192.0.2.10"));
        ns2Answer = ns1Answer;
        Map<String, Object> d2 = svc.diagnose(monitor("AAAA"));
        assertThat(verdict(d2)).containsEntry("code", "NO_RECORD_OF_TYPE");
        assertThat(params(d2)).containsEntry("reason", "other").containsEntry("other_types", "A").containsEntry("record_type", "AAAA");
    }

    @Test
    @DisplayName("beklenen değer dışı yanıt → EXPECTED_MISMATCH (DNS_UNEXPECTED alarmının nedeni)")
    void expectedMismatch() {
        DnsMonitor m = monitor("A");
        m.setExpectedValue("192.0.2.10\n192.0.2.11");
        resolverAnswer = (q, s, tcp) -> standardResolver(q, a("192.0.2.66"));
        ns1Answer = (q, s, tcp) -> authoritative(q, true, a("192.0.2.66"));
        ns2Answer = ns1Answer;
        Map<String, Object> d = svc.diagnose(m);
        assertThat(verdict(d)).containsEntry("code", "EXPECTED_MISMATCH").containsEntry("status", "fail");
        assertThat(params(d)).containsEntry("unexpected", "192.0.2.66").containsEntry("expected", "192.0.2.10, 192.0.2.11");
        assertThat(step(d, "compare")).containsEntry("status", "fail");
    }

    @Test
    @DisplayName("kesilmiş UDP yanıtı (TC) → TCP ile yeniden; TRUNCATED_UDP bilgi; satırda tc + tcp")
    void truncated() {
        resolverAnswer = (q, s, tcp) -> {
            if (q.getQuestion().getType() == Type.A && !tcp) {
                Message r = reply(q, Rcode.NOERROR, false, List.of(), List.of());
                r.getHeader().setFlag(Flags.TC);
                return r;
            }
            return standardResolver(q, a("192.0.2.10"));
        };
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "DNS_OK");
        assertThat(codes(d)).contains("TRUNCATED_UDP");
        assertThat(rows(d, "resolvers").get(0)).containsEntry("tc", true).containsEntry("tcp", true)
                .containsEntry("answers", List.of("192.0.2.10"));
        assertThat(net.calls).anyMatch(c -> c.startsWith("dns " + RESOLVER) && c.endsWith(" A tcp"));
    }

    @Test
    @DisplayName("yavaş çözümleyici (izlemenin eşiği) → SLOW_RESOLVER; gerçek kontrol uyuşmazsa CLIENT_MISMATCH")
    void slowAndMismatch() {
        DnsMonitor m = monitor("A");
        m.setSlowThresholdMs(1);
        resolverAnswer = (q, s, tcp) -> {
            Thread.sleep(5);
            return standardResolver(q, a("192.0.2.10"));
        };
        when(checker.check(anyString(), anyString())).thenReturn(success(false));
        Map<String, Object> d = svc.diagnose(m);
        assertThat(codes(d)).contains("SLOW_RESOLVER", "CLIENT_MISMATCH", "DNS_OK");
    }

    @Test
    @DisplayName("çözümleyici yapılandırılmamış → RESOLVER_TIMEOUT (reason=none), çöküş yok")
    void noResolvers() {
        net.resolvers.clear();
        Map<String, Object> d = svc.diagnose(monitor("A"));
        assertThat(verdict(d)).containsEntry("code", "RESOLVER_TIMEOUT");
        assertThat(params(d)).containsEntry("reason", "none");
        assertThat(step(d, "resolvers")).containsEntry("status", "fail");
    }

    @Test
    @DisplayName("vekil tanımlıysa PROXY_NOT_APPLICABLE (DNS vekilden geçmez)")
    void proxyNotApplicable() {
        net.proxy = true;
        assertThat(codes(svc.diagnose(monitor("A")))).contains("PROXY_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("address(): IPv4:port ayrıştırılır; IPv6 metni olduğu gibi (53)")
    void address() {
        assertThat(DnsDiagnosticsService.address("192.0.2.53:5353").getPort()).isEqualTo(5353);
        assertThat(DnsDiagnosticsService.address("192.0.2.53").getPort()).isEqualTo(53);
        assertThat(DnsDiagnosticsService.address("2001:db8::53").getPort()).isEqualTo(53);
    }

    @SuppressWarnings("unused")
    private static Record aaaa(String ip) throws Exception { return new AAAARecord(n(NAME), DClass.IN, 300, FakeNetDiagNetwork.ip(ip)); }
}
