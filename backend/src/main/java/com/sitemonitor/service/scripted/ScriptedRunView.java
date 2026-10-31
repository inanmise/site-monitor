package com.sitemonitor.service.scripted;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.service.ScriptedCheckerService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Senaryo koşum verisinin TEL biçimi (2026-10-31, k6 inceleme D1) — test yanıtı, kontrol geçmişi satırı ve liste satırının
 * son koşumu aynı alanları aynı adlarla taşır:
 * <ul>
 *   <li>{@code steps} [{name, duration_ms, requests, failed_requests, p95_ms, checks_passed, checks_failed, error_code}],
 *       {@code steps_truncated}</li>
 *   <li>{@code thresholds} [{metric, expr, ok}]</li>
 *   <li>{@code tls} [{host, tls_version, requests, cert_state (tracked|untracked|unknown), cert?: {domain,
 *       days_remaining, status}}] — {@code cert_*} OKUMA anında envanterden; {@code cert} yalnız {@code tracked} ve
 *       izleyen o envanter satırını okuyabiliyorsa</li>
 *   <li>{@code failure_reason} (kod), {@code failure_detail} (küçük nesne), {@code run_context} (nesne)</li>
 * </ul>
 * ESKİ satırlar (bu sürümden önce) bu alanları TAŞIMAZ — anahtar yazılmaz, uydurulmaz. Hiçbir yöntem fırlatmaz.
 */
public final class ScriptedRunView {

    private ScriptedRunView() {}

    /** TLS girdisinin envanterle eşleşmesi (doldurulana dek). */
    public static final String CERT_UNKNOWN = "unknown";
    public static final String CERT_TRACKED = "tracked";
    public static final String CERT_UNTRACKED = "untracked";

    /** Kontrol satırının koşum verisi alanları — yalnız DOLU olanlar. */
    public static Map<String, Object> fields(ScriptedCheck c) {
        if (c == null) return new LinkedHashMap<>();
        return fields(c.getStepsJson(), Boolean.TRUE.equals(c.getStepsTruncated()), c.getThresholdsJson(), c.getTlsJson(),
                c.getFailureReason(), c.getFailureDetail(), c.getContextJson());
    }

    /** Koşum sonucunun (test yanıtı) koşum verisi alanları. */
    public static Map<String, Object> fields(ScriptedCheckerService.RunDetails d) {
        if (d == null) return new LinkedHashMap<>();
        return fields(d.stepsJson(), d.stepsTruncated(), d.thresholdsJson(), d.tlsJson(), d.failureReason(),
                d.failureDetail(), d.contextJson());
    }

    private static Map<String, Object> fields(String stepsJson, boolean stepsTruncated, String thresholdsJson,
                                              String tlsJson, String failureReason, String failureDetail,
                                              String contextJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object steps = ScriptedRunData.parse(stepsJson);
        if (steps instanceof List<?>) {
            out.put("steps", steps);
            out.put("steps_truncated", stepsTruncated);
        }
        Object th = ScriptedRunData.parse(thresholdsJson);
        if (th instanceof List<?>) out.put("thresholds", th);
        Object tls = ScriptedRunData.parse(tlsJson);
        if (tls instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("host", m.get("host"));
                row.put("tls_version", m.get("tls_version"));
                row.put("requests", m.get("requests"));
                row.put("cert_state", CERT_UNKNOWN);
                rows.add(row);
            }
            out.put("tls", rows);
        }
        if (failureReason != null && !failureReason.isBlank()) out.put("failure_reason", failureReason);
        Object fd = ScriptedRunData.parse(failureDetail);
        if (fd instanceof Map<?, ?>) out.put("failure_detail", fd);
        Object ctx = ScriptedRunData.parse(contextJson);
        if (ctx instanceof Map<?, ?>) out.put("run_context", ctx);
        return out;
    }

    /**
     * Sertifika durumu kaynağı — sayfa başına TEK toplu okuma: host kümesi için envanter satırları ve son sertifika
     * kontrolleri.
     */
    public interface CertLookup {
        List<CertificateInventory> inventory(Collection<String> hosts);
        List<LatestCheck> latest(Collection<String> domains);
    }

    /**
     * Satırların {@code tls} girdilerine {@code cert_state} / {@code cert} ekler. Bütün satırların hostları TEK seferde
     * okunur. {@code canRead}: izleyen o envanter satırını okuyabiliyor mu (SY ya da UG takımı izleme okuma kapsamında).
     * Okuyamadığı satır {@code unknown} kalır (varlığı söylenmez gibi davranılmaz ama ayrıntı verilmez). Okuma hatası →
     * hepsi {@code unknown}.
     */
    public static void decorateTls(List<Map<String, Object>> rows, CertLookup lookup,
                                   Predicate<CertificateInventory> canRead) {
        if (rows == null || rows.isEmpty() || lookup == null) return;
        Set<String> hosts = new LinkedHashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null || !(row.get("tls") instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("host") instanceof String h) || h.isBlank()) continue;
                @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) m;
                entries.add(e);
                hosts.add(h.toLowerCase(Locale.ROOT));
            }
        }
        if (entries.isEmpty()) return;
        Map<String, CertificateInventory> byHost = new HashMap<>();
        Map<String, LatestCheck> latestByDomain = new HashMap<>();
        try {
            for (CertificateInventory inv : lookup.inventory(hosts)) {
                if (inv == null || inv.getDomain() == null) continue;
                String key = inv.getDomain().toLowerCase(Locale.ROOT);
                CertificateInventory prev = byHost.get(key);
                // aynı domain'in birden çok satırı: 443 / etkin olan tercih edilir
                if (prev == null || preferred(inv, prev)) byHost.put(key, inv);
            }
            if (!byHost.isEmpty()) {
                List<String> domains = byHost.values().stream().map(CertificateInventory::getDomain).toList();
                for (LatestCheck lc : lookup.latest(domains)) {
                    if (lc != null && lc.getDomain() != null) latestByDomain.putIfAbsent(lc.getDomain().toLowerCase(Locale.ROOT), lc);
                }
            }
        } catch (Exception e) {
            return;   // hepsi unknown kalır
        }
        for (Map<String, Object> e : entries) {
            String host = ((String) e.get("host")).toLowerCase(Locale.ROOT);
            CertificateInventory inv = byHost.get(host);
            if (inv == null) {
                e.put("cert_state", CERT_UNTRACKED);
                continue;
            }
            boolean readable;
            try {
                readable = canRead != null && canRead.test(inv);
            } catch (Exception ex) {
                readable = false;
            }
            if (!readable) {
                e.put("cert_state", CERT_UNKNOWN);
                continue;
            }
            e.put("cert_state", CERT_TRACKED);
            Map<String, Object> cert = new LinkedHashMap<>();
            cert.put("domain", inv.getDomain());
            LatestCheck lc = latestByDomain.get(host);
            cert.put("days_remaining", lc == null ? null : lc.getDaysRemaining());
            cert.put("status", lc == null ? null : lc.getStatus());
            e.put("cert", cert);
        }
    }

    private static boolean preferred(CertificateInventory a, CertificateInventory b) {
        boolean aActive = !Boolean.FALSE.equals(a.getActive()), bActive = !Boolean.FALSE.equals(b.getActive());
        if (aActive != bActive) return aActive;
        boolean a443 = a.getPort() == null || a.getPort() == 443, b443 = b.getPort() == null || b.getPort() == 443;
        return a443 && !b443;
    }

    // ── Adım trendi (step-series) ──────────────────────────────────────────────────────────────

    /** Trendde en çok bu kadar adım (en sık görülenler). */
    public static final int MAX_SERIES_STEPS = 15;

    /**
     * {@code [checked_at, steps_json]} satırlarından (yeniden eskiye ya da eskiden yeniye — sıralanır) adım başına süre
     * serisi: {@code [{name, points: [{ts, duration_ms, failed}]}]} — en sık {@link #MAX_SERIES_STEPS} adım, noktalar
     * zamana göre artan. Adı boş (kök) adım "" adıyla döner.
     */
    public static List<Map<String, Object>> stepSeries(List<Object[]> rows, Function<String, Object> parser) {
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        Map<String, Integer> freq = new HashMap<>();
        List<Object[]> sorted = new ArrayList<>();
        if (rows != null) for (Object[] r : rows) if (r != null && r.length >= 2) sorted.add(r);
        sorted.sort((x, y) -> String.valueOf(x[0]).compareTo(String.valueOf(y[0])));
        for (Object[] r : sorted) {
            if (r == null || r.length < 2 || !(r[1] instanceof String json)) continue;
            Object parsed = parser == null ? ScriptedRunData.parse(json) : parser.apply(json);
            if (!(parsed instanceof List<?> steps)) continue;
            for (Object o : steps) {
                if (!(o instanceof Map<?, ?> s)) continue;
                String name = s.get("name") == null ? "" : String.valueOf(s.get("name"));
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("ts", r[0]);
                pt.put("duration_ms", s.get("duration_ms"));
                pt.put("failed", num(s.get("failed_requests")) > 0 || num(s.get("checks_failed")) > 0);
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(pt);
                freq.merge(name, 1, Integer::sum);
            }
        }
        List<String> top = new ArrayList<>(byName.keySet());
        top.sort((a, b) -> Integer.compare(freq.getOrDefault(b, 0), freq.getOrDefault(a, 0)));
        Set<String> keep = new LinkedHashSet<>(top.subList(0, Math.min(MAX_SERIES_STEPS, top.size())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byName.entrySet()) {   // ilk görülme sırası
            if (!keep.contains(e.getKey())) continue;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", e.getKey());
            step.put("points", e.getValue());
            out.add(step);
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
