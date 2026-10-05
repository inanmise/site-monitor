package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DnsCheckerService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import org.springframework.stereotype.Service;
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;

import static com.sitemonitor.service.diagnose.NetDiagFindings.FAIL;
import static com.sitemonitor.service.diagnose.NetDiagFindings.INFO;
import static com.sitemonitor.service.diagnose.NetDiagFindings.WARN;
import static com.sitemonitor.service.diagnose.NetDiagFindings.params;

/**
 * DNS UÇTAN UCA TANILAMASI (2026-10-05). İzlemenin kayıt türü + adı:
 * <ol>
 *   <li>{@code resolvers} — izlemenin sorduğu HER çözümleyiciye AYRI AYRI (işletim sisteminin çözümleyicileri; izlemede
 *       yayılım kontrolü açıksa {@code site.monitor.dns.resolvers} da): rcode, yanıtlar, TTL, süre, AA/AD/TC bayrakları;
 *       kesilmiş (TC) UDP yanıtı TCP ile yeniden sorulur.</li>
 *   <li>{@code zone} — bölgenin (zone) SOA'sı ve NS kayıtları.</li>
 *   <li>{@code authoritative} — her yetkili sunucuya DOĞRUDAN (RD kapalı): AA bayrağı, rcode, yanıtlar, SOA seri no.
 *       Yetkili sunucu YENİ bir çıkış hedefidir → önce izleme denetleyicilerinin SSRF politikasından geçer; ulaşılamayan /
 *       engellenen sunucu bir BULGUDUR, çöküş değil.</li>
 *   <li>{@code dnssec} — SERVFAIL veren çözümleyiciye CD (doğrulamayı kapat) bayrağıyla yeniden: CD ile yanıt geliyorsa
 *       sorun DNSSEC doğrulamasıdır.</li>
 *   <li>{@code compare} — çözümleyiciler kendi aralarında / yetkiliyle / izlemenin beklenen değeriyle.</li>
 * </ol>
 * {@code client_check} = izlemenin GERÇEK sorgusu ({@link DnsCheckerService#check}) — {@code dns_records} satırı YAZILMAZ,
 * değişiklik tespitine DOKUNULMAZ. DNS HTTP vekilinden geçmez.
 */
@Service
public class DnsDiagnosticsService {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "DNS_DIAG";
    static final int MAX_RESOLVERS = 6;
    static final int MAX_NS = 4;
    static final int MAX_VALUES_IN_PARAMS = 6;
    static final long DEFAULT_SLOW_MS = 1000;

    private final NetDiagNetwork net;
    private final NetDiagWorkers workers;
    private final DnsCheckerService dnsChecker;
    private final AppSettingsService appSettings;

    UnaryOperator<String> env = System::getenv;

    public DnsDiagnosticsService(NetDiagNetwork net, NetDiagWorkers workers, DnsCheckerService dnsChecker,
                                 AppSettingsService appSettings) {
        this.net = net;
        this.workers = workers;
        this.dnsChecker = dnsChecker;
        this.appSettings = appSettings;
    }

    /** Tek sorgunun sonucu (çözümleyici ya da yetkili sunucu satırı). */
    static final class Row {
        String server;
        String label;      // system | propagation | authoritative
        String ns;         // yetkili satırında NS adı
        Integer rcode;
        String rcodeName;
        final List<String> values = new ArrayList<>();
        final List<String> cnames = new ArrayList<>();
        Long ttl;
        Long ms;
        boolean aa, ad, ra, tc, tcp;
        Long negativeTtl;
        Long soaSerial;
        String error;
        String errorKind;   // timeout | error | policy | dns
        final List<String> lines = new ArrayList<>();
        Integer cdRcode;
        String cdRcodeName;
        final List<String> cdValues = new ArrayList<>();

        boolean responded() { return errorKind == null && rcode != null; }
        boolean answered() { return responded() && rcode == Rcode.NOERROR && !values.isEmpty(); }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server", server);
            m.put("label", label);
            if (ns != null) m.put("ns", ns);
            m.put("rcode", rcodeName);
            m.put("answers", new ArrayList<>(values));
            if (!cnames.isEmpty()) m.put("cname", new ArrayList<>(cnames));
            m.put("ttl", ttl);
            m.put("ms", ms);
            m.put("aa", aa);
            m.put("ad", ad);
            m.put("tc", tc);
            m.put("tcp", tcp);
            if (negativeTtl != null) m.put("negative_ttl", negativeTtl);
            if (soaSerial != null) m.put("soa_serial", soaSerial);
            if (cdRcodeName != null) {
                m.put("cd_rcode", cdRcodeName);
                m.put("cd_answers", new ArrayList<>(cdValues));
            }
            m.put("error", error);
            m.put("error_kind", errorKind);
            return m;
        }
    }

    public Map<String, Object> diagnose(DnsMonitor m) {
        NetDiagRun run = new NetDiagRun();
        HttpDiagMasker masker = NetDiagSupport.masker(net);
        String name = DnsCheckerService.toHostname(m.getDomain() == null ? "" : m.getDomain());
        String rawType = m.getRecordType() == null || m.getRecordType().isBlank() ? "A" : m.getRecordType().trim().toUpperCase(Locale.ROOT);
        int parsedType = Type.value(rawType);
        final int type = parsedType < 0 ? Type.A : parsedType;
        final String typeName = parsedType < 0 ? "A" : rawType;
        int timeout = queryTimeout();
        long slowMs = m.getSlowThresholdMs() != null && m.getSlowThresholdMs() > 0 ? m.getSlowThresholdMs() : DEFAULT_SLOW_MS;
        boolean proxyConfigured = net.proxyConfigured();

        run.info("DNS tanılaması: " + name + " " + typeName + " (sorgu zaman aşımı " + timeout + " ms)");
        if (proxyConfigured) {
            run.finding(NetDiagFindings.PROXY_NOT_APPLICABLE, INFO, params("proxy", net.proxyAddress(), "protocol", "DNS"));
        }

        // İzlemenin GERÇEK sorgusu — paralel; kayıt YOK.
        Future<Map<String, Object>> clientF = workers.submit(() -> dnsChecker.check(m.getDomain(), typeName));

        // ── resolvers ──
        List<Row> resolverRows = resolverPlan(m);
        NetDiagRun.Step rs = run.step("resolvers").put("name", name).put("record_type", typeName);
        int qType = type;
        List<Future<Row>> rf = new ArrayList<>();
        for (Row r : resolverRows) rf.add(workers.submit(() -> query(r, name, qType, timeout, true, false)));
        List<Row> resolved = new ArrayList<>();
        for (int i = 0; i < rf.size(); i++) {
            Row r = NetDiagWorkers.await(rf.get(i), run.deadline());
            if (r == null) {
                run.markTimeLimited();
                r = resolverRows.get(i);
                r.error = "süre sınırı";
                r.errorKind = "timeout";
            }
            resolved.add(r);
            run.blank();
            for (String l : r.lines) run.raw(l);
        }
        long okCount = resolved.stream().filter(Row::answered).count();
        rs.put("queried", resolved.size()).put("answered", okCount)
                .put("servers", resolved.stream().map(r -> r.server).toList());
        Row primary = resolved.stream().filter(r -> "system".equals(r.label) && r.responded()).findFirst().orElse(null);
        if (resolved.isEmpty()) rs.fail("no resolver configured");
        else if (primary != null && primary.answered() && okCount == resolved.size()) rs.ok();
        else if (primary != null && primary.answered()) rs.warn();
        else rs.fail(primary == null ? "no answer" : primary.rcodeName);

        // ── dnssec (yalnız SERVFAIL varsa) ──
        List<Row> servfails = resolved.stream().filter(r -> r.responded() && r.rcode == Rcode.SERVFAIL).toList();
        if (servfails.isEmpty()) {
            run.skip("dnssec", "not_needed");
        } else {
            NetDiagRun.Step ds = run.step("dnssec");
            List<Future<Row>> cf = new ArrayList<>();
            for (Row r : servfails) cf.add(workers.submit(() -> cdRetry(r, name, qType, timeout)));
            int validationFailures = 0;
            for (Future<Row> f : cf) {
                Row r = NetDiagWorkers.await(f, run.deadline());
                if (r == null) { run.markTimeLimited(); continue; }
                if (r.cdRcode != null && (r.cdRcode == Rcode.NOERROR || r.cdRcode == Rcode.NXDOMAIN)) validationFailures++;
                run.info("CD ile yeniden: " + r.server + " → " + (r.cdRcodeName == null ? "yanıt yok" : r.cdRcodeName)
                        + (r.cdValues.isEmpty() ? "" : " · " + String.join(", ", r.cdValues)));
            }
            ds.put("servers", servfails.stream().map(r -> r.server).toList()).put("validation_failure", validationFailures > 0);
            if (validationFailures > 0) ds.fail("dnssec validation failure");
            else ds.warn();
        }

        // ── zone ──
        NetDiagRun.Step zs = run.step("zone");
        Row zoneVia = primary != null ? primary : resolved.stream().filter(Row::responded).findFirst().orElse(null);
        String zone = null;
        List<String> nsNames = List.of();
        Long resolverSerial = null;
        if (zoneVia == null) {
            zs.skip("no_resolver");
        } else {
            Object[] z = findZone(zoneVia.server, name, timeout, run);
            zone = (String) z[0];
            resolverSerial = (Long) z[1];
            if (zone != null) nsNames = nsOf(zoneVia.server, zone, timeout, run);
            zs.put("zone", zone).put("ns", nsNames).put("soa_serial", resolverSerial).put("via", zoneVia.server);
            if (zone == null || nsNames.isEmpty()) {
                zs.warn();
                run.finding(NetDiagFindings.ZONE_NOT_FOUND, WARN, params("name", name, "zone", zone));
            } else {
                zs.ok();
            }
        }

        // ── authoritative ──
        List<Row> authRows = new ArrayList<>();
        if (nsNames.isEmpty()) {
            run.skip("authoritative", zone == null ? "zone" : "no_ns");
        } else {
            NetDiagRun.Step as = run.step("authoritative").put("zone", zone);
            List<String> nsList = nsNames.size() > MAX_NS ? nsNames.subList(0, MAX_NS) : nsNames;
            String zoneF = zone;
            List<Future<Row>> af = new ArrayList<>();
            for (String ns : nsList) af.add(workers.submit(() -> authoritative(ns, name, qType, zoneF, timeout)));
            for (int i = 0; i < af.size(); i++) {
                Row r = NetDiagWorkers.await(af.get(i), run.deadline());
                if (r == null) {
                    run.markTimeLimited();
                    r = new Row();
                    r.ns = nsList.get(i);
                    r.label = "authoritative";
                    r.error = "süre sınırı";
                    r.errorKind = "timeout";
                }
                authRows.add(r);
                run.blank();
                for (String l : r.lines) run.raw(l);
            }
            long aaCount = authRows.stream().filter(r -> r.responded() && r.aa).count();
            as.put("queried", authRows.size()).put("authoritative_answers", aaCount)
                    .put("servers", authRows.stream().map(r -> r.ns).toList());
            if (aaCount == authRows.size()) as.ok();
            else if (aaCount > 0) as.warn();
            else as.fail("no authoritative answer");
        }

        // ── Bulgular ──
        boolean diagOk = primary != null && primary.answered();
        List<String> primaryValues = primary == null ? List.of() : primary.values;
        List<String> expected = DnsCheckerService.splitLines(m.getExpectedValue());
        List<String> unexpected = diagOk ? DnsCheckerService.unexpectedValues(m.getExpectedValue(), primaryValues) : List.of();
        List<Row> systemRows = resolved.stream().filter(r -> "system".equals(r.label)).toList();
        if (resolved.isEmpty()) {
            run.finding(NetDiagFindings.RESOLVER_TIMEOUT, FAIL, params("servers", "—", "reason", "none"));
        } else if (primary == null) {
            List<Row> base = systemRows.isEmpty() ? resolved : systemRows;
            boolean allTimeout = base.stream().allMatch(r -> "timeout".equals(r.errorKind));
            run.finding(NetDiagFindings.RESOLVER_TIMEOUT, FAIL, params("servers", servers(base), "ms", timeout,
                    "reason", allTimeout ? null : "error", "error", base.isEmpty() ? null : base.get(0).error));
        } else if (primary.rcode == Rcode.NXDOMAIN) {
            Row authAnswer = authRows.stream().filter(Row::answered).findFirst().orElse(null);
            boolean authNx = authRows.stream().anyMatch(r -> r.responded() && r.aa && r.rcode == Rcode.NXDOMAIN);
            if (authAnswer != null) {
                run.finding(NetDiagFindings.NXDOMAIN_RESOLVER_ONLY, FAIL, params("name", name, "server", primary.server,
                        "negative_ttl", primary.negativeTtl, "auth_values", join(authAnswer.values), "ns", authAnswer.ns));
            } else {
                run.finding(NetDiagFindings.NXDOMAIN_AUTHORITATIVE, FAIL, params("name", name, "zone", zone,
                        "reason", authNx ? null : "unconfirmed"));
            }
        } else if (primary.rcode == Rcode.SERVFAIL) {
            boolean dnssec = primary.cdRcode != null && (primary.cdRcode == Rcode.NOERROR || primary.cdRcode == Rcode.NXDOMAIN);
            run.finding(dnssec ? NetDiagFindings.SERVFAIL_DNSSEC : NetDiagFindings.SERVFAIL, FAIL,
                    params("name", name, "server", primary.server, "cd_rcode", primary.cdRcodeName));
        } else if (primary.rcode == Rcode.REFUSED) {
            run.finding(NetDiagFindings.RESOLVER_REFUSED, FAIL, params("server", primary.server, "name", name));
        } else if (primary.rcode != Rcode.NOERROR) {
            run.finding(NetDiagFindings.SERVFAIL, FAIL, params("name", name, "server", primary.server, "rcode", primary.rcodeName,
                    "reason", "other"));
        } else if (primary.values.isEmpty()) {
            List<String> other = primary.cnames.isEmpty() ? otherTypes(primary.server, name, type, timeout) : List.of();
            run.finding(NetDiagFindings.NO_RECORD_OF_TYPE, FAIL, params("name", name, "record_type", typeName,
                    "cname", primary.cnames.isEmpty() ? null : join(primary.cnames), "other_types", other.isEmpty() ? null : join(other),
                    "reason", !primary.cnames.isEmpty() ? "cname" : !other.isEmpty() ? "other" : null));
        } else {
            if (!unexpected.isEmpty()) {
                run.finding(NetDiagFindings.EXPECTED_MISMATCH, FAIL, params("unexpected", join(unexpected), "expected", join(expected),
                        "record_type", typeName));
            }
            run.finding(NetDiagFindings.DNS_OK, INFO, params("record_type", typeName, "answers", primary.values.size(),
                    "values", join(primary.values), "ms", primary.ms, "server", primary.server));
        }

        // Ek (ikincil) bulgular — birincil karar ne olursa olsun.
        for (Row r : resolved) {
            if (r == primary || r.responded()) continue;
            if (primary != null) {
                run.finding(NetDiagFindings.RESOLVER_TIMEOUT, WARN, params("servers", r.server, "ms", timeout,
                        "reason", "timeout".equals(r.errorKind) ? null : "error", "error", r.error));
            }
        }
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        for (Row r : resolved) if (r.answered()) sets.put(r.server, new TreeSet<>(r.values));
        if (new LinkedHashSet<>(sets.values()).size() > 1) {
            List<String> parts = new ArrayList<>();
            sets.forEach((k, v) -> parts.add(k + ": " + join(new ArrayList<>(v))));
            run.finding(NetDiagFindings.RESOLVERS_DISAGREE, WARN, params("sets", String.join(" | ", parts), "count", sets.size()));
        }
        Row authAns = authRows.stream().filter(r -> r.answered() && r.aa).findFirst().orElse(null);
        if (diagOk && authAns != null && !new TreeSet<>(authAns.values).equals(new TreeSet<>(primaryValues))) {
            run.finding(NetDiagFindings.AUTH_RESOLVER_MISMATCH, WARN, params("resolver_values", join(primaryValues),
                    "auth_values", join(authAns.values), "ttl_remaining", primary == null ? null : primary.ttl,
                    "server", primary == null ? null : primary.server, "ns", authAns.ns));
        }
        List<String> lame = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        String unreachableReason = null;
        for (Row r : authRows) {
            if (r.responded()) {
                if (!r.aa || r.rcode == Rcode.REFUSED || r.rcode == Rcode.SERVFAIL) lame.add(r.ns);
            } else {
                unreachable.add(r.ns + (r.server != null ? " (" + r.server + ")" : ""));
                if (unreachableReason == null) unreachableReason = r.errorKind;
            }
        }
        if (!lame.isEmpty()) run.finding(NetDiagFindings.LAME_DELEGATION, WARN, params("ns", String.join(", ", lame), "zone", zone));
        if (!unreachable.isEmpty()) {
            run.finding(NetDiagFindings.AUTH_UNREACHABLE, WARN, params("ns", String.join(", ", unreachable),
                    "reason", "policy".equals(unreachableReason) ? "policy" : null));
        }
        List<String> truncated = resolved.stream().filter(r -> r.tc).map(r -> r.server).toList();
        List<String> truncatedAuth = authRows.stream().filter(r -> r.tc).map(r -> r.ns).toList();
        if (!truncated.isEmpty() || !truncatedAuth.isEmpty()) {
            List<String> all = new ArrayList<>(truncated);
            all.addAll(truncatedAuth);
            run.finding(NetDiagFindings.TRUNCATED_UDP, INFO, params("servers", String.join(", ", all)));
        }
        Row slowest = resolved.stream().filter(r -> r.responded() && r.ms != null && r.ms > slowMs)
                .max((a, b) -> Long.compare(a.ms, b.ms)).orElse(null);
        if (slowest != null) {
            run.finding(NetDiagFindings.SLOW_RESOLVER, WARN, params("server", slowest.server, "ms", slowest.ms, "threshold_ms", slowMs));
        }

        // ── compare ──
        NetDiagRun.Step cs = run.step("compare");
        boolean resolversAgree = new LinkedHashSet<>(sets.values()).size() <= 1;
        Set<Set<String>> authSets = new LinkedHashSet<>();
        for (Row r : authRows) if (r.answered() && r.aa) authSets.add(new TreeSet<>(r.values));
        cs.put("resolvers_agree", resolversAgree)
                .put("authoritative_agree", authSets.size() <= 1)
                .put("auth_vs_resolver", !diagOk || authAns == null ? "n/a"
                        : new TreeSet<>(authAns.values).equals(new TreeSet<>(primaryValues)) ? "same" : "differs")
                .put("expected", expected.isEmpty() ? null : expected)
                .put("unexpected", unexpected.isEmpty() ? null : unexpected);
        if (!unexpected.isEmpty()) cs.fail("unexpected values");
        else if (!resolversAgree || authSets.size() > 1 || "differs".equals(cs.detail().get("auth_vs_resolver"))) cs.warn();
        else cs.ok();

        // ── client_check ──
        Map<String, Object> c = NetDiagWorkers.await(clientF, run.deadline());
        if (c == null) run.markTimeLimited();
        Map<String, Object> clientCheck = clientResult(c);
        if (c != null && Boolean.TRUE.equals(clientCheck.get("ok")) != diagOk) {
            run.finding(NetDiagFindings.CLIENT_MISMATCH, WARN, params("diag", diagOk ? "ok" : "fail",
                    "client", Boolean.TRUE.equals(clientCheck.get("ok")) ? "ok" : "fail", "client_error", clientCheck.get("error")));
        }

        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("domain", m.getDomain());
        mon.put("record_type", typeName);
        mon.put("expected_value", expected.isEmpty() ? null : expected);
        mon.put("propagation_check", Boolean.TRUE.equals(m.getPropagationCheck()));
        mon.put("slow_threshold_ms", m.getSlowThresholdMs());
        Map<String, Object> tgt = new LinkedHashMap<>();
        tgt.put("host", name);
        tgt.put("record_type", typeName);
        Map<String, Object> dnsBlock = new LinkedHashMap<>();
        dnsBlock.put("name", name);
        dnsBlock.put("record_type", typeName);
        dnsBlock.put("zone", zone);
        dnsBlock.put("ns", nsNames);
        dnsBlock.put("timeout_ms", timeout);
        dnsBlock.put("resolvers", resolved.stream().map(Row::toMap).toList());
        dnsBlock.put("authoritative", authRows.stream().map(Row::toMap).toList());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dns", dnsBlock);
        return NetDiagSupport.envelope("dns", run, mon, tgt, NetDiagSupport.route("direct", proxyConfigured, null),
                NetDiagSupport.proxyMap(net), NetDiagSupport.source(env), NetDiagFindings.DNS_OK, null, clientCheck, extra, null, masker);
    }

    // ── Plan ─────────────────────────────────────────────────────────────────────────────────

    /** İzlemenin sorduğu çözümleyiciler: sistem (ExtendedResolver sırası) + yayılım kontrolü açıksa ayar listesi. */
    List<Row> resolverPlan(DnsMonitor m) {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (InetSocketAddress a : net.systemResolvers()) {
            String ip = a.getAddress() != null ? NetDiagRun.ip(a.getAddress()) : a.getHostString();
            if (ip == null || !seen.add(ip + ":" + a.getPort())) continue;
            Row r = new Row();
            r.server = a.getPort() == 53 ? ip : ip + ":" + a.getPort();
            r.label = "system";
            rows.add(r);
            if (rows.size() >= MAX_RESOLVERS) return rows;
        }
        if (Boolean.TRUE.equals(m.getPropagationCheck())) {
            String csv = appSettings == null ? "8.8.8.8,1.1.1.1,9.9.9.9"
                    : appSettings.getString("site.monitor.dns.resolvers", "8.8.8.8,1.1.1.1,9.9.9.9");
            for (String s : csv.split(",")) {
                String ip = s.trim();
                if (ip.isEmpty() || !seen.add(ip + ":53")) continue;
                Row r = new Row();
                r.server = ip;
                r.label = "propagation";
                rows.add(r);
                if (rows.size() >= MAX_RESOLVERS) break;
            }
        }
        return rows;
    }

    int queryTimeout() {
        int t = appSettings == null ? 2000 : appSettings.getInt("site.monitor.dns.query-timeout-ms", 2000);
        return Math.max(500, Math.min(5000, t));
    }

    // ── Sorgular ─────────────────────────────────────────────────────────────────────────────

    static InetSocketAddress address(String server) {
        String host = server;
        int port = 53;
        int c = server.lastIndexOf(':');
        if (c > 0 && server.indexOf(':') == c) {   // IPv4:port (IPv6 metni birden çok ':' taşır)
            host = server.substring(0, c);
            try { port = Integer.parseInt(server.substring(c + 1)); } catch (Exception ignore) { port = 53; }
        }
        return new InetSocketAddress(host, port);
    }

    static Message newQuery(String name, int type, boolean rd, boolean ad, boolean cd) throws Exception {
        Message q = Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
        if (!rd) q.getHeader().unsetFlag(Flags.RD);
        if (ad) q.getHeader().setFlag(Flags.AD);
        if (cd) q.getHeader().setFlag(Flags.CD);
        return q;
    }

    /** Satırın sunucusuna sorgu (TC → TCP ile bir kez daha). Satırı doldurup döner; asla fırlatmaz. */
    Row query(Row r, String name, int type, int timeout, boolean rd, boolean cd) {
        String flags = (rd ? "RD" : "norecurse") + (rd ? " AD" : "") + (cd ? " CD" : "");
        r.lines.add("* " + ("authoritative".equals(r.label) ? "Yetkili sunucu " + r.ns + " (" + r.server + ")"
                : ("propagation".equals(r.label) ? "Yayılım çözümleyicisi " : "Sistem çözümleyicisi ") + r.server));
        r.lines.add("> " + name + ". IN " + Type.string(type) + "  (UDP, " + flags + ")");
        long t0 = System.nanoTime();
        try {
            Message q = newQuery(name, type, rd, rd, cd);
            Message resp = net.dns(address(r.server), q, timeout, false);
            if (resp != null && resp.getHeader().getFlag(Flags.TC)) {
                r.tc = true;
                r.lines.add("< yanıt kesildi (TC) — TCP ile yeniden soruluyor");
                r.lines.add("> " + name + ". IN " + Type.string(type) + "  (TCP, " + flags + ")");
                resp = net.dns(address(r.server), newQuery(name, type, rd, rd, cd), timeout, true);
                r.tcp = true;
            }
            r.ms = (System.nanoTime() - t0) / 1_000_000L;
            fill(r, resp, type);
        } catch (Exception e) {
            r.ms = (System.nanoTime() - t0) / 1_000_000L;
            r.error = NetDiagRun.message(e);
            r.errorKind = isTimeout(e) ? "timeout" : "error";
            r.lines.add("< " + ("timeout".equals(r.errorKind) ? "yanıt yok (zaman aşımı " + timeout + " ms)" : "hata: " + r.error));
        }
        return r;
    }

    private static void fill(Row r, Message resp, int type) {
        if (resp == null) {
            r.error = "boş yanıt";
            r.errorKind = "error";
            return;
        }
        r.rcode = resp.getRcode();
        r.rcodeName = rcodeName(r.rcode);
        r.aa = resp.getHeader().getFlag(Flags.AA);
        r.ad = resp.getHeader().getFlag(Flags.AD);
        r.ra = resp.getHeader().getFlag(Flags.RA);
        if (resp.getHeader().getFlag(Flags.TC)) r.tc = true;
        for (Record rec : resp.getSection(Section.ANSWER)) {
            if (type != Type.CNAME && rec.getType() == Type.CNAME) {
                r.cnames.add(rec.rdataToString());
                continue;
            }
            if (rec.getType() != type) continue;
            r.values.add(rec.rdataToString());
            if (r.ttl == null || rec.getTTL() < r.ttl) r.ttl = rec.getTTL();
        }
        Collections.sort(r.values);
        for (Record rec : resp.getSection(Section.AUTHORITY)) {
            if (rec instanceof SOARecord soa) {
                r.negativeTtl = Math.min(soa.getMinimum(), soa.getTTL());
                if (r.soaSerial == null) r.soaSerial = soa.getSerial();
            }
        }
        r.lines.add("< " + r.rcodeName + " · flags: " + resp.getHeader().printFlags().trim() + " · " + r.values.size() + " yanıt · "
                + r.ms + " ms" + (r.tcp ? " · TCP" : ""));
        for (String c : r.cnames) r.lines.add("<   CNAME " + c);
        for (String v : r.values) r.lines.add("<   " + v + (r.ttl != null ? "  (TTL ≤ " + r.ttl + ")" : ""));
        if (r.values.isEmpty() && r.negativeTtl != null) r.lines.add("<   SOA (negatif önbellek TTL " + r.negativeTtl + ")");
    }

    /** SERVFAIL veren çözümleyiciye CD bayrağıyla bir kez daha (DNSSEC doğrulaması kapalı). */
    Row cdRetry(Row r, String name, int type, int timeout) {
        try {
            Message resp = net.dns(address(r.server), newQuery(name, type, true, false, true), timeout, false);
            r.cdRcode = resp == null ? null : resp.getRcode();
            r.cdRcodeName = r.cdRcode == null ? null : rcodeName(r.cdRcode);
            if (resp != null) {
                for (Record rec : resp.getSection(Section.ANSWER)) if (rec.getType() == type) r.cdValues.add(rec.rdataToString());
            }
        } catch (Exception e) {
            r.cdRcodeName = isTimeout(e) ? "TIMEOUT" : "ERROR";
        }
        return r;
    }

    /**
     * Bölgeyi bul: ad (ve gerekirse ataları) için SOA — yanıttaki SOA'nın sahibi ADIN KENDİSİYSE bölge odur; CNAME yoksa
     * AUTHORITY'deki SOA'nın sahibi bölgedir. @return {zone, serial}
     */
    Object[] findZone(String server, String name, int timeout, NetDiagRun run) {
        String candidate = name;
        for (int i = 0; i < 5 && candidate != null && !candidate.isEmpty() && !run.expired(); i++) {
            try {
                Message resp = net.dns(address(server), newQuery(candidate, Type.SOA, true, false, false), timeout, false);
                if (resp != null) {
                    boolean cname = false;
                    for (Record rec : resp.getSection(Section.ANSWER)) {
                        if (rec instanceof SOARecord soa && sameName(rec.getName(), candidate)) {
                            run.info("Bölge: " + candidate + " (SOA seri " + soa.getSerial() + ", " + server + ")");
                            return new Object[]{ candidate, soa.getSerial() };
                        }
                        if (rec.getType() == Type.CNAME) cname = true;
                    }
                    if (!cname) {
                        for (Record rec : resp.getSection(Section.AUTHORITY)) {
                            if (rec instanceof SOARecord soa) {
                                String zone = trimDot(rec.getName().toString());
                                run.info("Bölge: " + zone + " (SOA seri " + soa.getSerial() + ", " + server + ")");
                                return new Object[]{ zone, soa.getSerial() };
                            }
                        }
                    }
                }
            } catch (Exception e) {
                run.info("Bölge sorgusu başarısız (" + candidate + "): " + NetDiagRun.message(e));
                return new Object[]{ null, null };
            }
            int dot = candidate.indexOf('.');
            candidate = dot < 0 ? null : candidate.substring(dot + 1);
        }
        return new Object[]{ null, null };
    }

    List<String> nsOf(String server, String zone, int timeout, NetDiagRun run) {
        try {
            Message resp = net.dns(address(server), newQuery(zone, Type.NS, true, false, false), timeout, false);
            List<String> out = new ArrayList<>();
            if (resp != null) {
                for (Record rec : resp.getSection(Section.ANSWER)) {
                    if (rec instanceof NSRecord ns) out.add(trimDot(ns.getTarget().toString()));
                }
            }
            Collections.sort(out);
            run.info("Yetkili sunucular (" + zone + "): " + (out.isEmpty() ? "—" : String.join(", ", out)));
            return out;
        } catch (Exception e) {
            run.info("NS sorgusu başarısız (" + zone + "): " + NetDiagRun.message(e));
            return List.of();
        }
    }

    /** Yetkili sunucuya doğrudan: önce SSRF politikası (ad → IP), sonra RD kapalı sorgu + bölgenin SOA seri no'su. */
    Row authoritative(String ns, String name, int type, String zone, int timeout) {
        Row r = new Row();
        r.ns = ns;
        r.label = "authoritative";
        List<InetAddress> ips;
        try {
            ips = net.vet(ns);
        } catch (SsrfGuard.UnresolvableHostException ue) {
            r.error = NetDiagRun.message(ue);
            r.errorKind = "dns";
            r.lines.add("* Yetkili sunucu " + ns + " — ad çözümlenemedi");
            return r;
        } catch (SsrfGuard.BlockedException be) {
            r.error = NetDiagRun.message(be);
            r.errorKind = "policy";
            r.lines.add("* Yetkili sunucu " + ns + " — politika gereği sorulmadı: " + r.error);
            return r;
        }
        InetAddress ip = ips.stream().filter(a -> a instanceof Inet4Address).findFirst().orElse(ips.isEmpty() ? null : ips.get(0));
        if (ip == null) {
            r.error = "adres yok";
            r.errorKind = "dns";
            return r;
        }
        r.server = NetDiagRun.ip(ip);
        query(r, name, type, timeout, false, false);
        if (r.responded() && zone != null) {
            try {
                Message soa = net.dns(new InetSocketAddress(ip, 53), newQuery(zone, Type.SOA, false, false, false), timeout, false);
                if (soa != null) {
                    for (Record rec : soa.getSection(Section.ANSWER)) {
                        if (rec instanceof SOARecord s) { r.soaSerial = s.getSerial(); break; }
                    }
                }
                if (r.soaSerial != null) r.lines.add("<   SOA seri " + r.soaSerial);
            } catch (Exception ignore) { /* seri no bilgi amaçlı */ }
        }
        return r;
    }

    /** İstenen türde kayıt yoksa kardeş tür (A ↔ AAAA, aksi hâlde A) var mı — ipucu. */
    List<String> otherTypes(String server, String name, int type, int timeout) {
        int other = type == Type.A ? Type.AAAA : Type.A;
        try {
            Message resp = net.dns(address(server), newQuery(name, other, true, false, false), timeout, false);
            if (resp != null) {
                for (Record rec : resp.getSection(Section.ANSWER)) if (rec.getType() == other) return List.of(Type.string(other));
            }
        } catch (Exception ignore) { /* ipucu */ }
        return List.of();
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────

    static boolean isTimeout(Throwable e) {
        Throwable t = e;
        for (int i = 0; t != null && i < 6; i++) {
            if (t instanceof SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException) return true;
            String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("timed out") || msg.contains("timeout")) return true;
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        return false;
    }

    static String rcodeName(int rcode) {
        try { return Rcode.string(rcode); } catch (Exception e) { return String.valueOf(rcode); }
    }

    private static boolean sameName(Name n, String s) {
        return n != null && trimDot(n.toString()).equalsIgnoreCase(trimDot(s));
    }

    static String trimDot(String s) {
        if (s == null) return null;
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String servers(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row r : rows) out.add(r.server);
        return String.join(", ", out);
    }

    static String join(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        List<String> v = values.size() > MAX_VALUES_IN_PARAMS ? values.subList(0, MAX_VALUES_IN_PARAMS) : values;
        String s = String.join(", ", v);
        return values.size() > MAX_VALUES_IN_PARAMS ? s + " (+" + (values.size() - MAX_VALUES_IN_PARAMS) + ")" : s;
    }

    static Map<String, Object> clientResult(Map<String, Object> r) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (r == null) {
            c.put("ok", false);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            return c;
        }
        c.put("ok", Boolean.TRUE.equals(r.get("success")));
        c.put("values", r.get("values"));
        c.put("ttl", r.get("ttl"));
        c.put("response_ms", r.get("response_ms"));
        c.put("error", r.get("error"));
        c.put("failure_reason", r.get("failure_reason"));
        return c;
    }

}
