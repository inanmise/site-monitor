package com.sitemonitor.service.page.scan.deep;

import java.util.List;
import java.util.Map;

/**
 * Güvenlik puanı (2026-11, kaynak analizi algoritma 3) — TEK formül, doğruluk tablolu. 100'den düşülür, 0–100'e sıkıştırılır.
 *
 * <ul>
 *   <li>Başlık (eksik / zayıf): csp 15 / 7, hsts 15 / 7 (sayfa http ise 30), x_content_type_options 8 / 4,
 *       frame_protection 10 / 5, referrer_policy 4 / 4, permissions_policy 3 / 1, coop 2 / 1, corp 1 / 1.</li>
 *   <li>Güvensiz çerez başına 5 (en çok 15).</li>
 *   <li>SRI'siz 3. taraf betik / stil başına 3 (en çok 12).</li>
 *   <li>Bilinen açıklı kütüphane: kritik / yüksek 15, orta / düşük 8 (en çok 30).</li>
 *   <li>Karışık içerik: etkin (betik / stil / iframe) 15, edilgen 5 (en çok 20).</li>
 * </ul>
 * Harf, performans notuyla aynı eşiklerle.
 */
public final class SecurityScore {

    /** {anahtar → {eksik, zayıf}} düşüleri. */
    static final Map<String, int[]> HEADER_PENALTY = Map.of(
            "csp", new int[]{15, 7},
            "hsts", new int[]{15, 7},
            "x_content_type_options", new int[]{8, 4},
            "frame_protection", new int[]{10, 5},
            "referrer_policy", new int[]{4, 4},
            "permissions_policy", new int[]{3, 1},
            "coop", new int[]{2, 1},
            "corp", new int[]{1, 1});
    static final int HTTP_PAGE_PENALTY = 30;
    static final int COOKIE_EACH = 5, COOKIE_CAP = 15;
    static final int SRI_EACH = 3, SRI_CAP = 12;
    static final int VULN_HIGH = 15, VULN_OTHER = 8, VULN_CAP = 30;
    static final int MIXED_ACTIVE = 15, MIXED_PASSIVE = 5, MIXED_CAP = 20;

    private SecurityScore() {}

    /**
     * @param libSeverities açıklı kütüphane başına en ağır önem
     */
    public static int score(List<SecurityHeaders.Header> headers, int insecureCookies, int missingSri,
                            List<String> libSeverities, int mixedActive, int mixedPassive) {
        int penalty = 0;
        for (SecurityHeaders.Header h : headers) {
            int[] p = HEADER_PENALTY.get(h.key());
            if (p == null) continue;
            if ("hsts".equals(h.key()) && "not_https".equals(h.issue())) penalty += HTTP_PAGE_PENALTY;
            else if (SecurityHeaders.MISSING.equals(h.status())) penalty += p[0];
            else if (SecurityHeaders.WEAK.equals(h.status())) penalty += p[1];
        }
        penalty += Math.min(COOKIE_CAP, COOKIE_EACH * Math.max(0, insecureCookies));
        penalty += Math.min(SRI_CAP, SRI_EACH * Math.max(0, missingSri));
        int vuln = 0;
        for (String s : libSeverities) vuln += JsLibraryCatalog.severityRank(s) >= 3 ? VULN_HIGH : VULN_OTHER;
        penalty += Math.min(VULN_CAP, vuln);
        penalty += Math.min(MIXED_CAP, MIXED_ACTIVE * Math.max(0, mixedActive) + MIXED_PASSIVE * Math.max(0, mixedPassive));
        return Math.max(0, Math.min(100, 100 - penalty));
    }
}
