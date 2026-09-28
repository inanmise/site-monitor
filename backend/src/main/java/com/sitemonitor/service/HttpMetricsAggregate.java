package com.sitemonitor.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTTP metrik satırlarının TEK GEÇİŞTE toplanması (2026-09-28, İstek Gezgini yeniden tasarımı) — saf yardımcılar,
 * Spring yok. {@link HttpMetricsQueryService} satırları akış olarak okur ve buradaki {@link Agg}'lere döker; böylece
 * 7 günlük aralık bile sabit bellekte özetlenir (kova başına ve uç başına birer toplayıcı, satır listesi tutulmaz).
 *
 * <p>Ek olarak uç adının GÜVENLİ biçimi ({@link #safeEndpoint}): arayüze giden her uç adı yöntem + yol ŞABLONUDUR.
 * Kaydedici zaten Spring'in rota şablonunu yazar ({@code HttpMetricsInterceptor}); burası derinlemesine savunma —
 * eski/yanlış beslenmiş bir satır sorgu dizesi, sayısal kimlik, UUID, belirteç ya da e-posta taşıyorsa ekrana ve
 * CSV'ye ham hâliyle çıkmasın.
 */
final class HttpMetricsAggregate {

    private HttpMetricsAggregate() { }

    /** Durum sınıfı dizini: 0 = 2xx, 1 = 3xx, 2 = 4xx, 3 = 5xx, 4 = diğer (1xx ve geçersiz kod {@code 0}). */
    static final String[] CLASS_KEYS = {"status_2xx", "status_3xx", "status_4xx", "status_5xx", "status_other"};

    static int classIndex(int code) {
        if (code >= 200 && code <= 299) return 0;
        if (code >= 300 && code <= 399) return 1;
        if (code >= 400 && code <= 499) return 2;
        if (code >= 500 && code <= 599) return 3;
        return 4;
    }

    // ── Uç adı ───────────────────────────────────────────────────────────────────────────────────

    private static final Pattern METHOD = Pattern.compile("[A-Z]{3,7}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Uzun, rakam içeren belirteç görünümlü parça (oturum/sıfırlama belirteci, hash). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_~.=+%-]{24,}");
    static final int MAX_LEN = 200;

    /**
     * Arayüze çıkan uç adı: "YÖNTEM /yol/şablonu". Sorgu dizesi ve parça (#) atılır, denetim karakterleri silinir,
     * yol parçalarından sayısal kimlik / UUID {@code {id}}, uzun belirteç {@code {token}}, e-posta {@code {value}}
     * olur; şablon parçaları ({@code {id}}, yıldızlar) ve {@code (other)} gibi özel kovalar olduğu gibi kalır.
     */
    static String safeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) return "(other)";
        String s = raw.strip();
        int cut = firstOf(s, '?', '#');
        if (cut >= 0) s = s.substring(0, cut);
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) clean.append(c);
        }
        s = clean.toString().strip();
        String method = null;
        String path = s;
        int sp = s.indexOf(' ');
        if (sp > 0 && METHOD.matcher(s.substring(0, sp)).matches()) {
            method = s.substring(0, sp);
            path = s.substring(sp + 1).strip();
        }
        String safePath = path.startsWith("/") ? maskSegments(path) : path;
        String out = method == null ? safePath : method + " " + safePath;
        if (out.isBlank()) return "(other)";
        return out.length() > MAX_LEN ? out.substring(0, MAX_LEN) : out;
    }

    private static String maskSegments(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(maskSegment(parts[i]));
        }
        return sb.toString();
    }

    private static String maskSegment(String seg) {
        if (seg.isEmpty() || seg.startsWith("{") || seg.startsWith("*")) return seg;
        if (seg.indexOf('@') >= 0) return "{value}";
        if (DIGITS.matcher(seg).matches() || UUID.matcher(seg).matches()) return "{id}";
        if (TOKEN.matcher(seg).matches() && seg.chars().anyMatch(Character::isDigit)) return "{token}";
        return seg;
    }

    private static int firstOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /** "GET /api/x" → "GET"; yöntemsiz özel kova ("(other)", "(overflow)") → "". */
    static String methodOf(String endpoint) {
        if (endpoint == null) return "";
        int sp = endpoint.indexOf(' ');
        return sp > 0 && METHOD.matcher(endpoint.substring(0, sp)).matches() ? endpoint.substring(0, sp) : "";
    }

    /** "GET /api/x" → "/api/x"; yöntemsiz ad olduğu gibi. */
    static String pathOf(String endpoint) {
        if (endpoint == null) return "";
        String m = methodOf(endpoint);
        return m.isEmpty() ? endpoint : endpoint.substring(m.length() + 1);
    }

    // ── Satır ────────────────────────────────────────────────────────────────────────────────────

    /** Akış satırı ({@code HttpMetricMinuteRepository.streamRange} kolon sırası). Uç adı GÜVENLİ biçimde. */
    record Row(String bucket, String endpoint, long count, long errors, long sumMs, long maxMs, long minMs,
               String hist, String codes) {

        static Row of(Object[] r) {
            return new Row(str(r[0]), safeEndpoint(str(r[1])), lng(r[2]), lng(r[3]), lng(r[4]), lng(r[5]), lng(r[6]),
                    str(r[7]), str(r[8]));
        }

        private static String str(Object o) { return o == null ? null : o.toString(); }
        private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    }

    /**
     * "200:118,404:2" → kod/adet çiftleri. Bozuk parça atlanır (bir satırın bozukluğu tüm özeti düşürmesin).
     * Dönüş: [kod, adet] dizileri.
     */
    static List<long[]> parseCodes(String csv) {
        List<long[]> out = new ArrayList<>(4);
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try {
                long code = Long.parseLong(part.substring(0, c).trim());
                long n = Long.parseLong(part.substring(c + 1).trim());
                if (n > 0 && code >= 0 && code <= 999) out.add(new long[]{code, n});
            } catch (NumberFormatException ignore) {
                // bozuk parça — atla
            }
        }
        return out;
    }

    // ── Toplayıcı ───────────────────────────────────────────────────────────────────────────────

    /** Bir kova / bir uç / tüm aralık için toplam. Durum kodları yalnız {@code trackCodes} açıkken tutulur. */
    static final class Agg {
        long count;
        long errors;
        long sumMs;
        long maxMs;
        long minMs = Long.MAX_VALUE;
        final long[] hist = new long[HttpMetricsService.HIST_LEN];
        final long[] classes = new long[CLASS_KEYS.length];
        /** Durum kodu kolonu olmayan (2026-09-28 öncesi) satırların istekleri. */
        long unclassified;
        String lastSeen;
        private final Map<Integer, Long> codes;

        Agg(boolean trackCodes) { this.codes = trackCodes ? new HashMap<>() : null; }

        void add(Row r) {
            count += r.count();
            errors += r.errors();
            sumMs += r.sumMs();
            if (r.maxMs() > maxMs) maxMs = r.maxMs();
            if (r.count() > 0 && r.minMs() < minMs) minMs = r.minMs();
            long[] h = HttpMetricsQueryService.parseHist(r.hist());
            for (int i = 0; i < hist.length; i++) hist[i] += h[i];
            long classified = 0;
            for (long[] kv : parseCodes(r.codes())) {
                int code = (int) kv[0];
                classes[classIndex(code)] += kv[1];
                classified += kv[1];
                if (codes != null) codes.merge(code, kv[1], Long::sum);
            }
            if (classified < r.count()) unclassified += r.count() - classified;
            if (r.bucket() != null && (lastSeen == null || r.bucket().compareTo(lastSeen) > 0)) lastSeen = r.bucket();
        }

        long avg() { return count > 0 ? sumMs / count : 0L; }

        double errorRatePct() { return count > 0 ? Math.round(errors * 1000.0 / count) / 10.0 : 0.0; }

        long p(double q) { return HttpMetricsQueryService.percentile(hist, q, maxMs); }

        /** Durum sınıfı alanları (+ sınıfsız) — hedef haritaya eklenir. */
        void putClasses(Map<String, Object> m) {
            for (int i = 0; i < CLASS_KEYS.length; i++) m.put(CLASS_KEYS[i], classes[i]);
            m.put("unclassified", unclassified);
        }

        /** Durum kodları, adede göre azalan; en çok {@code limit} satır ([kod, adet] haritaları). */
        List<Map<String, Object>> codeList(int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (codes == null) return out;
            codes.entrySet().stream()
                    .sorted((a, b) -> b.getValue().equals(a.getValue())
                            ? Integer.compare(a.getKey(), b.getKey()) : Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .forEach(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", e.getKey());
                        m.put("count", e.getValue());
                        out.add(m);
                    });
            return out;
        }

        /** Uç satırı (tablo / en yavaş / en çok hata listeleri). */
        Map<String, Object> toEndpoint(String endpoint) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", endpoint);
            m.put("method", methodOf(endpoint));
            m.put("path", pathOf(endpoint));
            m.put("count", count);
            m.put("errors", errors);
            m.put("error_rate_pct", errorRatePct());
            m.put("avg_ms", avg());
            m.put("max_ms", maxMs);
            m.put("p50_ms", p(0.50));
            m.put("p95_ms", p(0.95));
            m.put("p99_ms", p(0.99));
            putClasses(m);
            m.put("last_seen", lastSeen);
            return m;
        }
    }
}
