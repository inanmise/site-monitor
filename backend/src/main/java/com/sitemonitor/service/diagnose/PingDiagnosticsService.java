package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.service.PingCheckerService;
import com.sitemonitor.service.ProcessProbe;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sitemonitor.service.diagnose.NetDiagFindings.FAIL;
import static com.sitemonitor.service.diagnose.NetDiagFindings.INFO;
import static com.sitemonitor.service.diagnose.NetDiagFindings.WARN;
import static com.sitemonitor.service.diagnose.NetDiagFindings.params;

/**
 * PING UÇTAN UCA TANILAMASI (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi eksik olan … izlemeler için tanılama
 * ekleyelim. ve hata alındığında detaylıca ne hatası aldığını görelim").
 *
 * <p>Adımlar: {@code policy} (SSRF — izleme denetleyicilerinin kuralı; reddedilen hedefe hiçbir paket gitmez) →
 * {@code dns} (A/AAAA, izlemenin IP sürümü, her IP) → {@code icmp} (izlemenin KENDİ komutu
 * {@link PingCheckerService#buildPingArgs}, 4 paket; kayıp + RTT min/ort/maks; "bu pod'da ICMP yok" ayrımı) →
 * {@code tcp_alive} (443 ve 80'e ≤ 2 sn TCP yoklaması: "ICMP süzülüyor ama host ayakta" ile "host kapalı/erişilemez"
 * ayrımı) → isteğe bağlı {@code traceroute} (yalnız istenirse; ≤ 15 atlama, ≤ 20 sn).
 *
 * <p>{@code client_check} = izlemenin GERÇEK kontrolü ({@link PingCheckerService#check}, izlemenin ayarlarıyla). Hiçbir
 * kontrol kaydı yazılmaz, alarm değerlendirilmez, ayar değişmez. ICMP HTTP vekilinden GEÇMEZ (bulgu
 * {@code PROXY_NOT_APPLICABLE}).
 */
@Service
public class PingDiagnosticsService {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "PING_DIAG";
    static final int PACKETS = 4;
    static final int TCP_PROBE_MS = 2000;
    static final int[] TCP_PROBE_PORTS = {443, 80};
    static final int TRACE_MAX_HOPS = 15;
    static final int TRACE_TIMEOUT_S = 20;
    /** Ortalama RTT bunu aşarsa uyarı (ms) — mutlak eşik; izlemenin "yavaş" kuralı tabana göredir. */
    static final long HIGH_RTT_MS = 250;
    static final int OUTPUT_MAX = 4000;

    private static final Pattern LOSS = Pattern.compile("(\\d+(?:[.,]\\d+)?)%\\s*(?:packet\\s*)?loss", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOSS_TR = Pattern.compile("%\\s*(\\d+(?:[.,]\\d+)?)\\s*kay", Pattern.CASE_INSENSITIVE);
    private static final Pattern TX_RX = Pattern.compile("(\\d+)\\s+packets\\s+transmitted,\\s*(\\d+)\\s+(?:packets\\s+)?received", Pattern.CASE_INSENSITIVE);
    private static final Pattern TX_RX_WIN = Pattern.compile("(?:Sent|Gönderilen)\\s*=\\s*(\\d+),\\s*(?:Received|Alınan)\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RTT_UNIX = Pattern.compile("=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)");
    private static final Pattern RTT_WIN_MIN = Pattern.compile("(?:Minimum|En Az)\\s*=\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern RTT_WIN_MAX = Pattern.compile("(?:Maximum|En Çok)\\s*=\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);
    private static final Pattern RTT_WIN_AVG = Pattern.compile("(?:Average|Ortalama)\\s*=\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);

    private final NetDiagNetwork net;
    private final NetDiagWorkers workers;
    private final PingCheckerService pingChecker;

    /** Ortam değişkeni okuyucu — test kaynak bilgisini sabitleyebilsin. */
    UnaryOperator<String> env = System::getenv;
    /** İşletim sistemi (komut kurulumu) — test sabitleyebilsin. */
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public PingDiagnosticsService(NetDiagNetwork net, NetDiagWorkers workers, PingCheckerService pingChecker) {
        this.net = net;
        this.workers = workers;
        this.pingChecker = pingChecker;
    }

    /** Ayrıştırılmış ping çıktısı. */
    record IcmpStats(boolean unavailable, Integer sent, Integer received, Double lossPct, Double rttMin, Double rttAvg, Double rttMax) {}

    public Map<String, Object> diagnose(PingMonitor m, boolean traceroute) {
        NetDiagRun run = new NetDiagRun();
        HttpDiagMasker masker = NetDiagSupport.masker(net);
        String host = m.getHost() == null ? "" : m.getHost().trim();
        String ipVersion = NetDiagSupport.ipVersion(m.getIpVersion());
        int timeoutMs = NetDiagRun.clampTimeout(m.getTimeoutMs(), 5000);
        int packetCount = m.getPacketCount() != null && m.getPacketCount() > 0 ? m.getPacketCount() : 4;
        boolean proxyConfigured = net.proxyConfigured();

        run.info("Ping tanılaması: " + host + " (IP sürümü " + ipVersion + ", " + PACKETS + " paket, zaman aşımı " + timeoutMs + " ms)");
        if (proxyConfigured) {
            run.finding(NetDiagFindings.PROXY_NOT_APPLICABLE, INFO, params("proxy", net.proxyAddress(), "protocol", "ICMP"));
            run.info("ICMP HTTP vekilinden geçmez — paketler pod'dan doğrudan çıkar.");
        }

        Map<String, Object> clientCheck = clientSkipped("policy");
        IcmpStats stats = null;
        Boolean tcpAlive = null;
        InetAddress target = null;

        // ── policy + dns ──
        NetDiagRun.Step policy = run.step("policy").put("host", host);
        NetDiagRun.Step dns = run.step("dns").put("host", host).put("ip_version", ipVersion);
        List<InetAddress> vetted = null;
        try {
            vetted = net.vet(host);
            policy.put("result", "allowed").ok();
        } catch (SsrfGuard.UnresolvableHostException ue) {
            policy.put("result", "not_evaluated").skip("dns");
            dns.fail(ue);
            run.info("Ad çözümlenemedi: " + host);
            run.finding(NetDiagFindings.DNS_FAILED, FAIL, params("host", host, "error", NetDiagRun.message(ue)));
        } catch (SsrfGuard.BlockedException be) {
            policy.put("result", "blocked").put("reason", NetDiagRun.message(be)).fail(be);
            run.skip("dns", "policy");
            run.info("Hedef politika gereği engellendi: " + NetDiagRun.message(be));
            run.finding(NetDiagFindings.POLICY_BLOCKED, FAIL, params("host", host, "reason", NetDiagRun.message(be)));
        }
        if (vetted != null) {
            List<InetAddress> fam = NetDiagSupport.family(vetted, ipVersion);
            dns.put("addresses", NetDiagSupport.ips(vetted))
                    .put("v4", NetDiagSupport.count(vetted, false))
                    .put("v6", NetDiagSupport.count(vetted, true));
            run.info("Çözümlenen adresler: " + String.join(", ", NetDiagSupport.ips(vetted)));
            if (fam.isEmpty()) {
                dns.fail("no " + ipVersion + " address");
                run.finding(NetDiagFindings.NO_ADDRESS_FOR_IP_VERSION, FAIL, params("host", host, "ip_version", ipVersion,
                        "available", String.join(", ", NetDiagSupport.ips(vetted))));
            } else {
                target = fam.get(0);
                dns.put("target_ip", NetDiagRun.ip(target)).ok();
            }
        }

        Future<Map<String, Object>> clientF = null;
        if (vetted == null) clientCheck = clientSkipped("blocked".equals(policy.detail().get("result")) ? "policy" : "dns");
        if (vetted != null) {
            // İzlemenin GERÇEK kontrolü — kendi ayarlarıyla, paralel. Kayıt YOK (yalnız sonuç haritası).
            int pc = packetCount;
            clientF = workers.submit(() -> pingChecker.check(host, m.getIpVersion(), pc, m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000));
        }

        if (target == null) {
            run.skip("icmp", vetted == null ? "policy" : "dns");
            run.skip("tcp_alive", vetted == null ? "policy" : "dns");
            if (traceroute) run.skip("traceroute", vetted == null ? "policy" : "dns");
        } else {
            String ip = NetDiagRun.ip(target);
            // ICMP ve TCP yoklamaları paralel.
            InetAddress tgt = target;
            int timeoutSec = Math.max(PACKETS + 1, (int) Math.ceil(timeoutMs / 1000.0) + 1);
            List<String> args = PingCheckerService.buildPingArgs(ip, "auto".equals(ipVersion) ? null : ipVersion, PACKETS, timeoutSec);
            Future<ProcessProbe.Result> icmpF = workers.submit(() -> net.exec(args, Math.min(timeoutSec + 2, 40)));
            List<Future<Map<String, Object>>> tcpF = new ArrayList<>();
            for (int port : TCP_PROBE_PORTS) tcpF.add(workers.submit(() -> tcpProbe(tgt, port)));

            // ── icmp ──
            NetDiagRun.Step icmp = run.step("icmp").put("target_ip", ip).put("packets", PACKETS);
            icmp.put("command", String.join(" ", args));
            run.sent(String.join(" ", args));
            ProcessProbe.Result r = NetDiagWorkers.await(icmpF, run.deadline());
            if (r == null) {
                run.markTimeLimited();
                icmp.fail("timeout");
            } else {
                String out = r.output() == null ? "" : r.output();
                for (String line : out.split("\\R")) if (!line.isBlank()) run.recv(line.strip());
                stats = parse(out);
                icmp.put("available", !stats.unavailable())
                        .put("exit_code", r.exitCode())
                        .put("packets_sent", stats.sent())
                        .put("packets_received", stats.received())
                        .put("loss_pct", stats.lossPct())
                        .put("rtt_min_ms", stats.rttMin())
                        .put("rtt_avg_ms", stats.rttAvg())
                        .put("rtt_max_ms", stats.rttMax());
                if (stats.unavailable()) icmp.put("reason", "icmp_unavailable").warn();
                else if (stats.lossPct() != null && stats.lossPct() >= 100) icmp.fail("100% packet loss");
                else if (stats.lossPct() != null && stats.lossPct() > 0) icmp.warn();
                else if (stats.lossPct() == null && stats.rttAvg() == null) icmp.fail(firstLine(out));
                else icmp.ok();
            }

            // ── tcp_alive ──
            NetDiagRun.Step tcp = run.step("tcp_alive").put("target_ip", ip);
            List<Map<String, Object>> probes = new ArrayList<>();
            boolean anyAnswer = false;
            boolean anyOpen = false;
            for (Future<Map<String, Object>> f : tcpF) {
                Map<String, Object> p = NetDiagWorkers.await(f, run.deadline());
                if (p == null) { run.markTimeLimited(); continue; }
                probes.add(p);
                String res = String.valueOf(p.get("result"));
                run.info("TCP " + ip + ":" + p.get("port") + " → " + res + (p.get("ms") != null ? " (" + p.get("ms") + " ms)" : ""));
                if ("open".equals(res)) { anyOpen = true; anyAnswer = true; }
                if ("refused".equals(res)) anyAnswer = true;
            }
            tcp.put("probes", probes).put("alive", anyAnswer);
            tcpAlive = anyAnswer;
            if (anyOpen) tcp.ok();
            else if (anyAnswer) tcp.put("note", "rst").ok();
            else tcp.warn();

            // ── traceroute (yalnız istenirse) ──
            if (traceroute) traceroute(run, target);
        }

        // ── Bulgular ──
        boolean icmpOk = false;
        if (stats != null) {
            if (stats.unavailable()) {
                // reason: arayüz gövde varyantını seçer (TCP yoklaması host'u ayakta buldu mu)
                run.finding(NetDiagFindings.ICMP_UNAVAILABLE_HERE, WARN, params("host", host,
                        "reason", tcpAlive == null ? "unknown" : tcpAlive ? "alive" : "no_answer"));
            } else if (stats.lossPct() != null && stats.lossPct() >= 100) {
                if (Boolean.TRUE.equals(tcpAlive)) {
                    run.finding(NetDiagFindings.ICMP_FILTERED_HOST_ALIVE, FAIL, params("host", host, "ip", NetDiagRun.ip(target),
                            "port", firstAnsweringPort(run)));
                } else if (Boolean.FALSE.equals(tcpAlive)) {
                    run.finding(NetDiagFindings.HOST_UNREACHABLE, FAIL, params("host", host, "ip", NetDiagRun.ip(target)));
                } else {
                    run.finding(NetDiagFindings.ALL_PACKETS_LOST, FAIL, params("host", host, "sent", stats.sent()));
                }
            } else if (stats.lossPct() == null && stats.rttAvg() == null) {
                run.finding(NetDiagFindings.ALL_PACKETS_LOST, FAIL, params("host", host, "sent", PACKETS));
            } else {
                icmpOk = true;
                if (stats.lossPct() != null && stats.lossPct() > 0) {
                    run.finding(NetDiagFindings.PARTIAL_LOSS, WARN, params("loss_pct", fmt(stats.lossPct()),
                            "sent", stats.sent(), "received", stats.received()));
                }
                if (stats.rttAvg() != null && stats.rttAvg() > HIGH_RTT_MS) {
                    run.finding(NetDiagFindings.HIGH_RTT, WARN, params("rtt_avg_ms", fmt(stats.rttAvg()), "threshold_ms", HIGH_RTT_MS));
                }
                run.finding(NetDiagFindings.PING_OK, INFO, params("rtt_avg_ms", fmt(stats.rttAvg()),
                        "loss_pct", fmt(stats.lossPct() == null ? 0.0 : stats.lossPct()), "ip", NetDiagRun.ip(target)));
            }
        }

        if (clientF != null) {
            Map<String, Object> c = NetDiagWorkers.await(clientF, run.deadline());
            if (c == null) run.markTimeLimited();
            clientCheck = clientResult(c);
            boolean clientNa = Boolean.TRUE.equals(clientCheck.get("na"));
            boolean diagNa = stats != null && stats.unavailable();
            if (stats != null && !clientNa && !diagNa && Boolean.TRUE.equals(clientCheck.get("ok")) != icmpOk) {
                run.finding(NetDiagFindings.CLIENT_MISMATCH, WARN, params("diag", icmpOk ? "up" : "down",
                        "client", Boolean.TRUE.equals(clientCheck.get("ok")) ? "up" : "down",
                        "client_error", clientCheck.get("error")));
            }
        }

        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("id", m.getId());
        monitor.put("name", m.getName());
        monitor.put("host", host);
        monitor.put("ip_version", ipVersion);
        monitor.put("timeout_ms", m.getTimeoutMs());
        monitor.put("packet_count", packetCount);
        Map<String, Object> tgtMap = new LinkedHashMap<>();
        tgtMap.put("host", host);
        tgtMap.put("ip_version", ipVersion);
        tgtMap.put("protocol", "ICMP");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("traceroute", traceroute);
        return NetDiagSupport.envelope("ping", run, monitor, tgtMap,
                NetDiagSupport.route("direct", proxyConfigured, null), NetDiagSupport.proxyMap(net),
                NetDiagSupport.source(env), NetDiagFindings.PING_OK, null, clientCheck, null, options, masker);
    }

    /** ICMP süzülüyor bulgusunun portu: TCP yoklamalarından ilk yanıt veren. */
    private static Object firstAnsweringPort(NetDiagRun run) {
        for (Map<String, Object> s : run.steps()) {
            if (!"tcp_alive".equals(s.get("key"))) continue;
            Object d = s.get("detail");
            if (!(d instanceof Map<?, ?> dm) || !(dm.get("probes") instanceof List<?> probes)) return null;
            for (Object o : probes) {
                if (o instanceof Map<?, ?> p && ("open".equals(p.get("result")) || "refused".equals(p.get("result")))) return p.get("port");
            }
        }
        return null;
    }

    /** Tek TCP yoklaması (≤ 2 sn): açık / reddedildi (RST — host ayakta) / zaman aşımı / erişilemez. */
    Map<String, Object> tcpProbe(InetAddress ip, int port) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("port", port);
        p.put("ip", NetDiagRun.ip(ip));
        long t0 = System.nanoTime();
        try (Socket ignored = net.connect(ip, port, TCP_PROBE_MS)) {
            p.put("result", "open");
        } catch (Exception e) {
            p.put("result", NetDiagRun.connectOutcome(e));
            p.put("error", NetDiagRun.message(e));
        }
        p.put("ms", (System.nanoTime() - t0) / 1_000_000L);
        return p;
    }

    private void traceroute(NetDiagRun run, InetAddress target) {
        String ip = NetDiagRun.ip(target);
        List<String> args = windows
                ? List.of("tracert", "-d", "-h", String.valueOf(TRACE_MAX_HOPS), "-w", "1000", ip)
                : List.of("traceroute", "-n", "-m", String.valueOf(TRACE_MAX_HOPS), "-w", "1", "-q", "1", ip);
        NetDiagRun.Step step = run.step("traceroute").put("command", String.join(" ", args)).put("max_hops", TRACE_MAX_HOPS);
        run.sent(String.join(" ", args));
        int budget = (int) Math.max(1, Math.min(TRACE_TIMEOUT_S, (run.deadline() - System.currentTimeMillis()) / 1000 - 1));
        ProcessProbe.Result r = net.exec(args, budget);
        String out = r == null || r.output() == null ? "" : r.output();
        String low = out.toLowerCase(Locale.ROOT);
        if (out.startsWith("komut çalıştırılamadı") || low.contains("operation not permitted") || low.contains("permission denied")) {
            step.put("available", false);
            step.skip("unavailable");
            run.finding(NetDiagFindings.TRACEROUTE_UNAVAILABLE, INFO, params("command", args.get(0)));
            return;
        }
        int hops = 0;
        boolean reached = false;
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.matches("^\\d+\\s.*")) {
                hops++;
                if (t.contains(ip)) reached = true;
            }
        }
        step.put("available", true).put("hops", hops).put("reached", reached)
                .put("output", out.length() > OUTPUT_MAX ? out.substring(0, OUTPUT_MAX) + "…" : out);
        for (String line : out.split("\\R")) if (!line.isBlank()) run.recv(line.strip());
        if (r != null && r.timedOut()) step.put("timed_out", true);
        if (reached) step.ok();
        else step.warn();
    }

    /** ping çıktısını ayrıştırır (iputils / busybox / Windows TR+EN). */
    static IcmpStats parse(String out) {
        String o = out == null ? "" : out;
        String low = o.toLowerCase(Locale.ROOT);
        if (o.startsWith("komut çalıştırılamadı") || low.contains("operation not permitted")
                || low.contains("permission denied") || low.contains("socket operation")) {
            return new IcmpStats(true, null, null, null, null, null, null);
        }
        Integer sent = null, received = null;
        Matcher tx = TX_RX.matcher(o);
        if (tx.find()) { sent = Integer.valueOf(tx.group(1)); received = Integer.valueOf(tx.group(2)); }
        else {
            Matcher w = TX_RX_WIN.matcher(o);
            if (w.find()) { sent = Integer.valueOf(w.group(1)); received = Integer.valueOf(w.group(2)); }
        }
        Double loss = num(LOSS, o, 1);
        if (loss == null) loss = num(LOSS_TR, o, 1);
        if (loss == null && sent != null && sent > 0 && received != null) loss = 100.0 * (sent - received) / sent;
        Double min = null, avg = null, max = null;
        Matcher u = RTT_UNIX.matcher(o);
        if (u.find()) {
            min = parseD(u.group(1));
            avg = parseD(u.group(2));
            max = parseD(u.group(3));
        } else {
            min = num(RTT_WIN_MIN, o, 1);
            max = num(RTT_WIN_MAX, o, 1);
            avg = num(RTT_WIN_AVG, o, 1);
        }
        return new IcmpStats(false, sent, received, loss, min, avg, max);
    }

    private static Double num(Pattern p, String s, int g) {
        Matcher m = p.matcher(s);
        return m.find() ? parseD(m.group(g)) : null;
    }

    private static Double parseD(String s) {
        try { return Double.valueOf(s.replace(',', '.')); } catch (Exception e) { return null; }
    }

    static Object fmt(Double d) {
        if (d == null) return null;
        if (d == Math.rint(d)) return d.longValue();
        return Math.round(d * 10) / 10.0;
    }

    private static String firstLine(String s) {
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) return t.length() > 160 ? t.substring(0, 160) : t;
        }
        return "yanıt yok";
    }

    /** İzlemenin gerçek kontrolünün sonucu — {@code ok} = up. */
    static Map<String, Object> clientResult(Map<String, Object> r) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (r == null) {
            c.put("ok", false);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            return c;
        }
        c.put("ok", Boolean.TRUE.equals(r.get("up")));
        c.put("up", Boolean.TRUE.equals(r.get("up")));
        c.put("na", Boolean.TRUE.equals(r.get("na")));
        c.put("rtt_ms", r.get("rtt_ms"));
        c.put("packet_loss", r.get("packet_loss"));
        c.put("error", r.get("error"));
        c.put("failure_reason", r.get("failure_reason"));
        return c;
    }

    static Map<String, Object> clientSkipped(String reason) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("ok", false);
        c.put("skipped", true);
        c.put("reason", reason);
        return c;
    }
}
