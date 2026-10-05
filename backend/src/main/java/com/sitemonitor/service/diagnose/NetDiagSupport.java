package com.sitemonitor.service.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagMasker;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Ping / Port / DNS tanılama sonucunun ORTAK zarfı (2026-10-05) — sözleşme tek yerde:
 * <pre>
 * { run_id, kind, type, started_at, duration_ms, monitor{…}, target{host, port?, protocol?, record_type?, ip_version?},
 *   route{own, proxy_configured, decision?}, proxy{configured, address, auth}, source{pod, node, pod_ip},
 *   verdict{code, status, params, path}, steps[], findings[], paths?[], client_check{…}, transcript, options{…} }
 * </pre>
 * Son adımda {@link HttpDiagMasker#scrubDeep} — vekil parolası / Basic jetonu hiçbir alanda düz kalmaz.
 */
public final class NetDiagSupport {

    private NetDiagSupport() {}

    /** Maskeleyici: başlık adı yok (ham soket), yalnız bilinen sır DEĞERLERİ süzülür. */
    public static HttpDiagMasker masker(NetDiagNetwork net) {
        return new HttpDiagMasker(Set.of(), net == null ? List.of() : net.secretValues());
    }

    public static Map<String, Object> proxyMap(NetDiagNetwork net) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("configured", false);
        p.put("address", null);
        p.put("auth", false);
        if (net == null || !net.proxyConfigured()) return p;
        p.put("configured", true);
        p.put("address", net.proxyAddress());
        p.put("auth", net.proxyAuth());
        return p;
    }

    public static Map<String, Object> route(String own, boolean proxyConfigured, Map<String, Object> decision) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("own", own);
        r.put("proxy_configured", proxyConfigured);
        if (decision != null) r.put("decision", decision);
        return r;
    }

    /** Pod / düğüm / pod IP — HTTP/keyword tanılamasıyla aynı kaynak bilgisi. */
    public static Map<String, Object> source(UnaryOperator<String> env) {
        Map<String, Object> s = new LinkedHashMap<>();
        String pod = blankToNull(env.apply("POD_NAME"));
        if (pod == null) pod = blankToNull(env.apply("HOSTNAME"));
        if (pod == null) {
            try { pod = InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) { pod = null; }
        }
        s.put("pod", pod);
        s.put("node", blankToNull(env.apply("NODE_NAME")));
        s.put("pod_ip", blankToNull(env.apply("POD_IP")));
        return s;
    }

    /**
     * Zarfı kurar (bulguları önem sırasına dizer, hükmü seçer) ve son savunma hattı olarak sırları süzer.
     *
     * @param okCode türün başarı kodu (hüküm "ok" olduğunda)
     */
    public static Map<String, Object> envelope(String kind, NetDiagRun run, Map<String, Object> monitor,
                                               Map<String, Object> target, Map<String, Object> route,
                                               Map<String, Object> proxy, Map<String, Object> source,
                                               String okCode, List<Map<String, Object>> paths,
                                               Map<String, Object> clientCheck, Map<String, Object> extra,
                                               Map<String, Object> options, HttpDiagMasker masker) {
        if (run.timeLimited()) {
            run.finding(NetDiagFindings.RUN_TIME_LIMIT, NetDiagFindings.WARN,
                    NetDiagFindings.params("limit_s", NetDiagRun.RUN_CAP_MS / 1000));
        }
        List<Map<String, Object>> findings = NetDiagFindings.ordered(run.findings());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("run_id", null);
        d.put("kind", kind);
        d.put("type", kind);
        d.put("started_at", Instant.ofEpochMilli(run.startMs()).truncatedTo(ChronoUnit.SECONDS).toString());
        d.put("duration_ms", run.elapsed());
        d.put("monitor", monitor);
        d.put("target", target);
        d.put("route", route);
        d.put("proxy", proxy);
        d.put("source", source);
        d.put("verdict", NetDiagFindings.verdict(findings, okCode));
        d.put("findings", new ArrayList<>(findings));
        d.put("steps", run.steps());
        if (paths != null) d.put("paths", paths);
        if (extra != null) d.putAll(extra);
        d.put("client_check", clientCheck);
        d.put("options", options == null ? new LinkedHashMap<>() : options);
        d.put("transcript", run.transcript());
        if (masker != null) masker.scrubDeep(d);
        return d;
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** IP sürümü: v4 | v6 | auto. */
    public static String ipVersion(String raw) {
        if (raw == null) return "auto";
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "v4".equals(v) || "v6".equals(v) ? v : "auto";
    }

    /** Aileye göre süz: v4 → yalnız IPv4, v6 → yalnız IPv6, auto → olduğu gibi. */
    public static List<InetAddress> family(List<InetAddress> all, String ipVersion) {
        if (all == null) return List.of();
        if ("v4".equals(ipVersion)) return all.stream().filter(a -> a instanceof java.net.Inet4Address).toList();
        if ("v6".equals(ipVersion)) return all.stream().filter(a -> a instanceof java.net.Inet6Address).toList();
        return all;
    }

    public static long count(List<InetAddress> all, boolean v6) {
        if (all == null) return 0;
        return all.stream().filter(a -> v6 ? a instanceof java.net.Inet6Address : a instanceof java.net.Inet4Address).count();
    }

    public static List<String> ips(List<InetAddress> all) {
        List<String> out = new ArrayList<>();
        if (all != null) for (InetAddress a : all) out.add(NetDiagRun.ip(a));
        return out;
    }
}
