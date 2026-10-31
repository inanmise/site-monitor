package com.sitemonitor.service.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Senaryo koşum VERİSİ (2026-10-31, k6 inceleme D1) — adımlar, eşikler, TLS, hata sınıfı ve koşum bağlamı için SAF
 * yardımcılar: kayıt için JSON üretimi ({@code scripted_checks.steps_json|thresholds_json|tls_json|context_json|
 * failure_reason|failure_detail}) ve k6 {@code error_code} → {@link CheckFailureReason} eşlemesi.
 *
 * <p>k6 hata kodları (v2.3.0 ile ÖLÇÜLDÜ, {@code K6RealContractTest}): çözülemeyen ad 1101, kara listedeki IP 1110,
 * bağlantı reddi 1212, bilinmeyen CA 1310, istek zaman aşımı 1050, HTTP 404 → 1404 (4xx = 1400 + durum, 5xx = 1500 +
 * durum). Ölçülemeyenler k6 kaynağındaki ({@code lib/netext/httpext/error_codes.go}) adlarıyla: 1100 DNS genel, 1111
 * engelli ad, 1201 kırık boru, 1210 bağlantı hatası, 1211 bağlantı zaman aşımı, 1220 karşı taraf sıfırladı, 1300 TLS
 * genel, 1311 x509 ad uyuşmazlığı, 16xx HTTP/2 hataları, 1020 geçersiz URL.
 *
 * <p>Hiçbir yöntem istisna fırlatmaz — koşum verisi ÜST VERİDİR, kontrolün sonucunu (PASS/FAIL/…) asla değiştirmez.
 */
public final class ScriptedRunData {

    private ScriptedRunData() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code thresholds_json} en çok bu kadar eşik. */
    public static final int MAX_THRESHOLDS = 50;
    /** Eşik metrik / ifade adı tavanı. */
    static final int MAX_THRESHOLD_TEXT = 200;

    /** Eşik: k6 {@code --summary-export} {@code metrics.<metric>.thresholds = {"<expr>": aşıldı mı}} — ok = !aşıldı. */
    public record Threshold(String metric, String expr, boolean ok) {}

    /** Koşum bağlamı — bugünkü Türkçe "süreç bütçesi=… · çıkış=…" satırının yapılandırılmış hali. */
    public record RunContext(int processBudgetS, Integer requestTimeoutS, boolean viaProxy, boolean corporateCa,
                             boolean invertedBudget) {}

    // ── k6 error_code → neden kodu ─────────────────────────────────────────────────────────────

    /** k6 istek hata kodunu katalog koduna çevirir; null → null. */
    public static CheckFailureReason fromK6ErrorCode(Integer code) {
        if (code == null) return null;
        int c = code;
        if (c == 1101) return CheckFailureReason.DNS_NXDOMAIN;
        if (c >= 1100 && c <= 1109) return CheckFailureReason.DNS_RESOLVE;
        if (c == 1110 || c == 1111) return CheckFailureReason.SSRF_BLOCKED;
        if (c >= 1112 && c <= 1199) return CheckFailureReason.DNS_RESOLVE;
        if (c == 1050) return CheckFailureReason.READ_TIMEOUT;
        if (c == 1020) return CheckFailureReason.CONFIG_ERROR;
        if (c == 1211) return CheckFailureReason.CONNECT_TIMEOUT;
        if (c == 1212) return CheckFailureReason.CONNECT_REFUSED;
        if (c == 1201 || c == 1220) return CheckFailureReason.CONNECTION_RESET;
        if (c >= 1200 && c <= 1299) return CheckFailureReason.HOST_UNREACHABLE;
        if (c == 1310) return CheckFailureReason.TLS_TRUST;
        if (c == 1311) return CheckFailureReason.TLS_HOSTNAME;
        if (c >= 1300 && c <= 1399) return CheckFailureReason.TLS_HANDSHAKE;
        if (c >= 1400 && c <= 1599) return CheckFailureReason.HTTP_STATUS;
        if (c >= 1600 && c <= 1699) return CheckFailureReason.PROTOCOL_ERROR;
        return CheckFailureReason.UNKNOWN;
    }

    // ── Sınıflandırma ──────────────────────────────────────────────────────────────────────────

    /**
     * Sınıflandırma girdisi.
     *
     * @param status       PASS / FAIL / ERROR / TIMEOUT / NO_CHECKS
     * @param exitCode     k6 çıkış kodu (-1: süreç başlamadı / öldürüldü)
     * @param timedOut     süreç bütçesi doldu (biz öldürdük)
     * @param interrupted  koşum kesintiye uğradı (pod kapanışı / iptal)
     * @param scriptCrashed çıktıda betiğin patladığına dair kesin iz
     * @param override     önceden kararlaştırılmış neden (ör. uzak import SSRF engeli); varsa o kullanılır
     */
    public record Input(String status, int exitCode, boolean timedOut, boolean interrupted, boolean scriptCrashed,
                        Integer checksFailed, List<Threshold> thresholds, K6OutputParser.Parsed parsed,
                        CheckFailureReason override) {}

    /**
     * Öncelik (sözleşme): kesinti &gt; önceden kararlaştırılmış neden &gt; AĞ hatası (ilk başarısız isteğin kodu) &gt; eşik
     * &gt; check &gt; betik durdurdu (108 / 110) &gt; check yok &gt; istisna &gt; süreç zaman aşımı &gt; k6 başlatılamadı. PASS → null.
     */
    public static CheckFailure classify(Input in) {
        try {
            if (in == null || in.status() == null) return null;
            K6OutputParser.Parsed p = in.parsed() == null ? K6OutputParser.Parsed.EMPTY : in.parsed();
            if (in.interrupted()) return CheckFailure.of(CheckFailureReason.SCRIPT_INTERRUPTED);
            if ("PASS".equals(in.status())) return null;
            Threshold failedThreshold = null;
            if (in.thresholds() != null) {
                for (Threshold t : in.thresholds()) if (!t.ok()) { failedThreshold = t; break; }
            }
            CheckFailure f;
            if (in.override() != null) {
                f = CheckFailure.of(in.override());
            } else if (p.firstFailedErrorCode() != null) {
                f = CheckFailure.of(fromK6ErrorCode(p.firstFailedErrorCode()));
                f.with("error_code", p.firstFailedErrorCode());
                int code = p.firstFailedErrorCode();
                if (code >= 1400 && code <= 1599) f.with("http_status", code - 1000);
                f.with("target", p.firstFailedHost());
            } else if (failedThreshold != null || in.exitCode() == 99) {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_THRESHOLD_FAILED);
            } else if (in.checksFailed() != null && in.checksFailed() > 0) {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_CHECK_FAILED);
            } else if (in.exitCode() == 108 || in.exitCode() == 110) {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_ABORTED);
                f.with("exit_code", in.exitCode());
            } else if ("NO_CHECKS".equals(in.status())) {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_NO_CHECKS);
            } else if ("TIMEOUT".equals(in.status())) {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_PROCESS_TIMEOUT);
            } else if (in.exitCode() == -1 && !in.timedOut()) {
                f = CheckFailure.of(CheckFailureReason.K6_UNAVAILABLE);
            } else {
                f = CheckFailure.of(CheckFailureReason.SCRIPT_EXCEPTION);
                if (in.exitCode() != 0) f.with("exit_code", in.exitCode());
            }
            // ortak ayrıntılar: adım, düşen eşik, düşen check
            if (p.firstFailedStep() != null) f.withIfAbsent("step", p.firstFailedStep().isEmpty() ? null : p.firstFailedStep());
            if (failedThreshold != null) f.with("threshold", failedThreshold.metric() + " " + failedThreshold.expr());
            if (p.failingCheck() != null) f.with("check", p.failingCheck());
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** SKIPPED / ön kontrol sonuçları için tek kodlu sınıflandırma. */
    public static CheckFailure simple(CheckFailureReason reason) {
        return reason == null ? null : CheckFailure.of(reason);
    }

    // ── JSON üretimi (kayıt biçimi = tel biçimi, snake_case) ─────────────────────────────────────

    /** Adım listesi → JSON dizisi; boşsa null. */
    public static String stepsJson(List<K6OutputParser.Step> steps) {
        if (steps == null || steps.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (K6OutputParser.Step s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("duration_ms", s.durationMs());
            m.put("requests", s.requests());
            m.put("failed_requests", s.failedRequests());
            m.put("p95_ms", s.p95Ms());
            m.put("checks_passed", s.checksPassed());
            m.put("checks_failed", s.checksFailed());
            m.put("error_code", s.errorCode());
            out.add(m);
        }
        return write(out);
    }

    /** Eşik listesi → JSON dizisi (düşenler önce, sonra metrik / ifade adına göre); boşsa null. */
    public static String thresholdsJson(List<Threshold> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Threshold t : sorted(thresholds)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", t.metric());
            m.put("expr", t.expr());
            m.put("ok", t.ok());
            out.add(m);
            if (out.size() >= MAX_THRESHOLDS) break;
        }
        return write(out);
    }

    /** Düşen eşik önce, sonra metrik + ifade (k6 nesne sırası rastgeledir — sabit sıra). */
    public static List<Threshold> sorted(List<Threshold> in) {
        List<Threshold> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            if (a.ok() != b.ok()) return a.ok() ? 1 : -1;
            int c = a.metric().compareTo(b.metric());
            return c != 0 ? c : a.expr().compareTo(b.expr());
        });
        return out;
    }

    /** Eşik metni tavanı (ad / ifade). */
    public static String fitThresholdText(String s) {
        if (s == null) return "";
        return s.length() <= MAX_THRESHOLD_TEXT ? s : s.substring(0, MAX_THRESHOLD_TEXT - 1) + "…";
    }

    /** TLS host listesi → JSON dizisi (yalnız host / sürüm / istek; sertifika durumu OKUMA anında); boşsa null. */
    public static String tlsJson(List<K6OutputParser.TlsHost> hosts) {
        if (hosts == null || hosts.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (K6OutputParser.TlsHost h : hosts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", h.host());
            m.put("tls_version", h.tlsVersion());
            m.put("requests", h.requests());
            out.add(m);
        }
        return write(out);
    }

    /** Koşum bağlamı → JSON nesnesi. */
    public static String contextJson(RunContext c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("process_budget_s", c.processBudgetS());
        m.put("request_timeout_s", c.requestTimeoutS());
        m.put("egress", c.viaProxy() ? "proxy" : "direct");
        m.put("corporate_ca", c.corporateCa());
        m.put("inverted_budget", c.invertedBudget());
        return write(m);
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Okuma (geçmiş / liste / test yanıtı) ───────────────────────────────────────────────────

    /** Kayıtlı JSON'u nesneye çevirir (dizi → List, nesne → Map); bozuk / boş → null. Asla fırlatmaz. */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || n.isNull() || n.isMissingNode()) return null;
            return JSON.convertValue(n, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
