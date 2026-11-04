package com.sitemonitor.service.page.scan.deep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sayfanın güvenlik başlıklarının değerlendirmesi (2026-11, kaynak analizi algoritma 3) — SAF, doğruluk tablolu.
 *
 * <p>Girdi ana sayfanın (HTML belgesinin) SON yanıtının başlıklarıdır (küçük harfli ad → değerler). Her anahtar için
 * {@code ok | weak | missing} + tek sorun kodu. CSP başlık yoksa {@code <meta http-equiv>}'teki politika değerlendirilir
 * ({@code meta_only}); yalnız {@code Content-Security-Policy-Report-Only} varsa uygulanmaz → {@code weak / report_only}.
 * Puanlama {@link SecurityScore}'da.
 */
public final class SecurityHeaders {

    public static final String OK = "ok", WEAK = "weak", MISSING = "missing";
    public static final List<String> KEYS = List.of("csp", "hsts", "x_content_type_options", "frame_protection",
            "referrer_policy", "permissions_policy", "coop", "corp");
    /** HSTS kısa ömür eşiği (180 gün — proje kuralı, sertifika sağlık satırıyla aynı). */
    public static final long HSTS_MIN_S = 15_552_000L;
    static final int VALUE_MAX = DeepUrls.MAX_TEXT;

    /** Değerlendirilmiş başlık satırı. {@code value} ≤ 300 karakter (CSP'nin kendisi daha uzun olabilir). */
    public record Header(String key, String status, String value, String issue) {}

    /**
     * CSP değerlendirmesi: durum + tüm sorun kodları; {@code frameAncestors} = uygulanan (meta / Report-Only değil) bir
     * politikada {@code frame-ancestors} yönergesi var (tam metinden — gösterilen değer kırpılmış olabilir).
     */
    public record CspResult(String status, List<String> issues, String value, boolean frameAncestors) {}

    private SecurityHeaders() {}

    /**
     * @param headers ana yanıtın başlıkları (küçük harfli ad → değerler; null olabilir)
     * @param metaCsp {@code <meta http-equiv="Content-Security-Policy">} içeriği; yoksa null
     * @param https   sayfa https mi
     */
    public static List<Header> evaluate(Map<String, List<String>> headers, String metaCsp, boolean https) {
        Map<String, List<String>> h = headers == null ? Map.of() : headers;
        List<Header> out = new ArrayList<>();
        CspResult csp = csp(h, metaCsp);
        out.add(new Header("csp", csp.status(), csp.value(), csp.issues().isEmpty() ? null : csp.issues().get(0)));
        out.add(hsts(first(h, "strict-transport-security"), https));
        String xcto = first(h, "x-content-type-options");
        out.add(xcto == null ? new Header("x_content_type_options", MISSING, null, null)
                : "nosniff".equalsIgnoreCase(xcto.trim()) ? new Header("x_content_type_options", OK, clip(xcto), null)
                : new Header("x_content_type_options", WEAK, clip(xcto), "invalid"));
        out.add(frame(first(h, "x-frame-options"), csp));
        out.add(referrer(first(h, "referrer-policy")));
        String pp = first(h, "permissions-policy");
        String fp = first(h, "feature-policy");
        out.add(pp != null ? new Header("permissions_policy", OK, clip(pp), null)
                : fp != null ? new Header("permissions_policy", WEAK, clip(fp), "legacy_feature_policy")
                : new Header("permissions_policy", MISSING, null, null));
        String coop = first(h, "cross-origin-opener-policy");
        out.add(coop == null ? new Header("coop", MISSING, null, null) : coopHeader(coop));
        String corp = first(h, "cross-origin-resource-policy");
        out.add(corp == null ? new Header("corp", MISSING, null, null)
                : Set.of("same-origin", "same-site", "cross-origin").contains(corp.trim().toLowerCase(Locale.ROOT))
                ? new Header("corp", OK, clip(corp), null) : new Header("corp", WEAK, clip(corp), "invalid"));
        return out;
    }

    /** CSP: başlık (birden çok olabilir — hepsi uygulanır, en güçlüsü sayılır), yoksa meta, yoksa yalnız Report-Only. */
    public static CspResult csp(Map<String, List<String>> h, String metaCsp) {
        List<String> policies = h.getOrDefault("content-security-policy", List.of());
        boolean meta = false;
        if (policies.isEmpty() && metaCsp != null && !metaCsp.isBlank()) {
            policies = List.of(metaCsp);
            meta = true;
        }
        if (policies.isEmpty()) {
            List<String> ro = h.getOrDefault("content-security-policy-report-only", List.of());
            if (!ro.isEmpty()) return new CspResult(WEAK, List.of("report_only"), clip(ro.get(0)), false);
            return new CspResult(MISSING, List.of(), null, false);
        }
        // Birden çok politika birlikte uygulanır: herhangi biri sorunsuz bir betik politikası taşıyorsa o kazanır
        List<String> best = null;
        boolean ancestors = false;
        for (String p : policies) {
            List<String> issues = cspIssues(p);
            if (best == null || issues.size() < best.size()) best = issues;
            if (!meta && directives(p).containsKey("frame-ancestors")) ancestors = true;
        }
        List<String> issues = new ArrayList<>(best);
        if (meta) issues.add("meta_only");
        boolean weak = issues.stream().anyMatch(i -> !"meta_only".equals(i));
        return new CspResult(weak ? WEAK : OK, issues, clip(String.join(", ", policies)), ancestors);
    }

    /**
     * Tek politikanın sorunları: betik yönergesi ({@code script-src}, yoksa {@code default-src}) yok →
     * {@code no_script_policy}; {@code 'unsafe-inline'} (nonce / hash / {@code 'strict-dynamic'} yoksa — varsa tarayıcı
     * yok sayar) → {@code unsafe_inline}; {@code 'unsafe-eval'} → {@code unsafe_eval}; {@code *} ya da şema kaynağı
     * ({@code https:} / {@code http:} / {@code data:}) → {@code wildcard_source}.
     */
    static List<String> cspIssues(String policy) {
        Map<String, List<String>> d = directives(policy);
        List<String> src = d.containsKey("script-src") ? d.get("script-src") : d.get("default-src");
        List<String> out = new ArrayList<>();
        if (src == null) {
            out.add("no_script_policy");
            return out;
        }
        boolean nonceOrHash = src.stream().anyMatch(t -> t.startsWith("'nonce-") || t.startsWith("'sha256-")
                || t.startsWith("'sha384-") || t.startsWith("'sha512-") || t.equals("'strict-dynamic'"));
        if (src.contains("'unsafe-inline'") && !nonceOrHash) out.add("unsafe_inline");
        if (src.contains("'unsafe-eval'")) out.add("unsafe_eval");
        if (src.stream().anyMatch(t -> t.equals("*") || t.equals("https:") || t.equals("http:") || t.equals("data:"))) {
            out.add("wildcard_source");
        }
        return out;
    }

    /** Yönergeler (ad küçük harf → belirteçler, küçük harf); ilk tanım kazanır (tarayıcı gibi). Sınırlı. */
    static Map<String, List<String>> directives(String policy) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (policy == null) return out;
        String p = policy.length() > 16_384 ? policy.substring(0, 16_384) : policy;
        for (String part : p.split(";", 200)) {
            String[] tok = part.trim().toLowerCase(Locale.ROOT).split("\\s+", 300);
            if (tok.length == 0 || tok[0].isEmpty() || out.containsKey(tok[0])) continue;
            List<String> v = new ArrayList<>();
            for (int i = 1; i < tok.length; i++) if (!tok[i].isEmpty()) v.add(tok[i]);
            out.put(tok[0], v);
        }
        return out;
    }

    static Header hsts(String v, boolean https) {
        if (!https) return new Header("hsts", MISSING, null, "not_https");
        if (v == null) return new Header("hsts", MISSING, null, null);
        Long maxAge = null;
        for (String part : v.toLowerCase(Locale.ROOT).split(";", 20)) {
            String t = part.trim();
            if (t.startsWith("max-age=")) {
                try { maxAge = Long.parseLong(t.substring(8).replace("\"", "").trim()); } catch (NumberFormatException ignore) { /* bozuk */ }
            }
        }
        if (maxAge == null) return new Header("hsts", WEAK, clip(v), "invalid");
        if (maxAge <= 0) return new Header("hsts", WEAK, clip(v), "disabled");
        if (maxAge < HSTS_MIN_S) return new Header("hsts", WEAK, clip(v), "short_max_age");
        return new Header("hsts", OK, clip(v), null);
    }

    /** Çerçeve koruması: CSP {@code frame-ancestors} (meta'da çalışmaz) ya da X-Frame-Options DENY / SAMEORIGIN. */
    static Header frame(String xfo, CspResult csp) {
        if (csp.frameAncestors()) return new Header("frame_protection", OK, clip("frame-ancestors"), null);
        if (xfo == null) return new Header("frame_protection", MISSING, null, null);
        String x = xfo.trim().toUpperCase(Locale.ROOT);
        if (x.equals("DENY") || x.equals("SAMEORIGIN")) return new Header("frame_protection", OK, clip(xfo), null);
        if (x.startsWith("ALLOW-FROM")) return new Header("frame_protection", WEAK, clip(xfo), "allow_from_deprecated");
        return new Header("frame_protection", WEAK, clip(xfo), "invalid");
    }

    static Header referrer(String v) {
        if (v == null) return new Header("referrer_policy", MISSING, null, null);
        // Birden çok değer olabilir (virgülle) — tarayıcı tanıdığı SONUNCUYU kullanır
        String chosen = null;
        for (String t : v.toLowerCase(Locale.ROOT).split(",", 20)) {
            String s = t.trim();
            if (REFERRER_KNOWN.contains(s)) chosen = s;
        }
        if (chosen == null) return new Header("referrer_policy", WEAK, clip(v), "invalid");
        if (chosen.equals("unsafe-url")) return new Header("referrer_policy", WEAK, clip(v), "unsafe_url");
        if (chosen.equals("no-referrer-when-downgrade")) {
            return new Header("referrer_policy", WEAK, clip(v), "no_referrer_when_downgrade");
        }
        return new Header("referrer_policy", OK, clip(v), null);
    }

    private static final Set<String> REFERRER_KNOWN = Set.of("no-referrer", "no-referrer-when-downgrade", "origin",
            "origin-when-cross-origin", "same-origin", "strict-origin", "strict-origin-when-cross-origin", "unsafe-url");

    static Header coopHeader(String v) {
        String t = v.trim().toLowerCase(Locale.ROOT);
        int semi = t.indexOf(';');
        if (semi >= 0) t = t.substring(0, semi).trim();
        if (t.equals("same-origin") || t.equals("same-origin-allow-popups") || t.equals("noopener-allow-popups")) {
            return new Header("coop", OK, clip(v), null);
        }
        if (t.equals("unsafe-none")) return new Header("coop", WEAK, clip(v), "unsafe_none");
        return new Header("coop", WEAK, clip(v), "invalid");
    }

    private static String first(Map<String, List<String>> h, String name) {
        List<String> v = h.get(name);
        if (v == null || v.isEmpty()) return null;
        String s = v.get(0);
        return s == null ? null : s;
    }

    private static String clip(String v) {
        return DeepUrls.clip(v, VALUE_MAX);
    }
}
