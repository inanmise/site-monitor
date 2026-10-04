package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.keyword.KeywordFailureClassifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword uçtan uca tanılamasının KEYWORD'E ÖZGÜ bulgu kataloğu + hüküm kuralı (2026-10-04).
 *
 * <p>Üç kod ailesi bir arada akar, arayüz her birini kendi i18n ad alanından çevirir:
 * <ul>
 *   <li>bu sınıfın {@link #CODES}'u → {@code kwdx.finding.<KOD>.title|body};</li>
 *   <li>ağ/HTTP adımı bulguları (DNS_FAIL, TCP_TIMEOUT, PATH_DIFFERS, CLIENT_MISMATCH …) → HTTP tanılamasının kataloğu
 *       {@link HttpDiagFindings} ({@code httpdx.finding.*}) — ham ölçüm aynı ({@code RawHttpProbe});</li>
 *   <li>"neden bulunamadı" ipuçları ({@code KeywordBodyAnalyzer.HINT_CODES}) → {@code kwhint.<KOD>.title|cause}.</li>
 * </ul>
 * Kod eklemek = TR + EN metnini aynı değişiklikte eklemek ({@code KeywordDiagFindingsI18nGateTest}).
 *
 * <p>Hüküm önceliği HTTP ile aynı: {@code PATH_DIFFERS} / {@code BOTH_PATHS_FAIL} → izlemenin yolundaki ilk fail → ilk
 * warn (önce izlemenin yolu) → {@link #KEYWORD_OK}.
 */
public final class KeywordDiagFindings {

    private KeywordDiagFindings() {}

    public static final String KEYWORD_OK = "KEYWORD_OK";
    public static final String KEYWORD_NOT_FOUND = "KEYWORD_NOT_FOUND";
    public static final String KEYWORD_FOUND_FORBIDDEN = "KEYWORD_FOUND_FORBIDDEN";
    public static final String KEYWORD_COUNT_MISMATCH = "KEYWORD_COUNT_MISMATCH";
    public static final String KEYWORD_HTTP_ERROR = "KEYWORD_HTTP_ERROR";
    public static final String KEYWORD_EMPTY_BODY = "KEYWORD_EMPTY_BODY";
    public static final String KEYWORD_BODY_TRUNCATED = "KEYWORD_BODY_TRUNCATED";
    public static final String KEYWORD_REDIRECT_BLOCKED = "KEYWORD_REDIRECT_BLOCKED";
    public static final String KEYWORD_BODY_UNREAD = "KEYWORD_BODY_UNREAD";
    public static final String KEYWORD_SLOW = "KEYWORD_SLOW";

    /** TAM liste — i18n kapısı bunu okur. */
    public static final List<String> CODES = List.of(
            KEYWORD_OK, KEYWORD_NOT_FOUND, KEYWORD_FOUND_FORBIDDEN, KEYWORD_COUNT_MISMATCH, KEYWORD_HTTP_ERROR,
            KEYWORD_EMPTY_BODY, KEYWORD_BODY_TRUNCATED, KEYWORD_REDIRECT_BLOCKED, KEYWORD_BODY_UNREAD, KEYWORD_SLOW);

    /** Kontrolün başarısızlık nedeni kodu → tanılama bulgu kodu (koşul aileleri). */
    public static String fromFailureReason(String reason) {
        if (reason == null) return KEYWORD_NOT_FOUND;
        return switch (reason) {
            case KeywordFailureClassifier.KEYWORD_FOUND_FORBIDDEN -> KEYWORD_FOUND_FORBIDDEN;
            case KeywordFailureClassifier.KEYWORD_COUNT_MISMATCH -> KEYWORD_COUNT_MISMATCH;
            case KeywordFailureClassifier.HTTP_STATUS -> KEYWORD_HTTP_ERROR;
            case KeywordFailureClassifier.EMPTY_BODY -> KEYWORD_EMPTY_BODY;
            case KeywordFailureClassifier.BODY_TRUNCATED -> KEYWORD_BODY_TRUNCATED;
            case KeywordFailureClassifier.REDIRECT_BLOCKED -> KEYWORD_REDIRECT_BLOCKED;
            default -> KEYWORD_NOT_FOUND;
        };
    }

    /**
     * Hüküm: {@code {status, code, params, failed_step, path}} — {@link HttpDiagFindings#verdict} ile aynı öncelik, "ok"
     * kodu {@link #KEYWORD_OK}.
     */
    public static Map<String, Object> verdict(List<Map<String, Object>> findings, String monitorOutcome, String monitorFailedStep) {
        Map<String, Object> pick = null;
        String status = null;
        for (Map<String, Object> f : findings) {
            Object c = f.get("code");
            if (HttpDiagFindings.PATH_DIFFERS.equals(c) || HttpDiagFindings.BOTH_PATHS_FAIL.equals(c)) { pick = f; status = HttpDiagFindings.FAIL; break; }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if ("monitor".equals(f.get("path")) && HttpDiagFindings.FAIL.equals(f.get("severity"))) { pick = f; status = HttpDiagFindings.FAIL; break; }
            }
        }
        if (pick == null) {
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (Map<String, Object> f : findings) if ("monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : findings) if (!"monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : ordered) {
                if (HttpDiagFindings.WARN.equals(f.get("severity"))) { pick = f; status = HttpDiagFindings.WARN; break; }
            }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (KEYWORD_OK.equals(f.get("code")) && "monitor".equals(f.get("path"))) { pick = f; status = "ok"; break; }
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        if (pick == null) {
            boolean ok = "ok".equals(monitorOutcome);
            v.put("status", ok ? "ok" : HttpDiagFindings.FAIL);
            v.put("code", ok ? KEYWORD_OK : HttpDiagFindings.RESPONSE_TIMEOUT);
            v.put("params", new LinkedHashMap<>());
            v.put("failed_step", ok ? null : monitorFailedStep);
            v.put("path", "monitor");
            return v;
        }
        v.put("status", status);
        v.put("code", pick.get("code"));
        v.put("params", pick.get("params"));
        v.put("failed_step", HttpDiagFindings.FAIL.equals(status) ? monitorFailedStep : null);
        v.put("path", pick.get("path") != null ? pick.get("path") : "monitor");
        return v;
    }
}
