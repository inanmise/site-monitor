package com.sitemonitor.service.keyword;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ham keyword kontrol sonucu → {@code keyword_results} satırı — zamanlanmış tur ({@code SchedulerService.recheckKeyword})
 * ve elle "Şimdi kontrol et" ({@code MonitoringController.triggerKeyword}) AYNI eşlemeyi kullanır (2026-10-04). Eskiden
 * iki yerde elle kopyalanmıştı; yeni teşhis alanlarından biri bir yolda unutulsaydı o yolun satırları "ayrıntısız"
 * kalırdı.
 *
 * <p>Eski alanlar AYNEN (found, occurrences, ok, http_status, response_ms, snippet, error, checked_at). Ek alanlar:
 * yanıt meta verisi her satırda; neden / ayrıntı / ipuçları / alıntı YALNIZ {@code ok=false} satırda (başarılı satıra
 * teşhis yazılmaz). {@code ok} çağıranın kararıdır — bu sınıf onu DEĞİŞTİRMEZ.
 */
public final class KeywordResultMapper {

    private KeywordResultMapper() {}

    /** Alıntı kolonunun güvenlik tavanı (analizör zaten ≤ 600 üretir). */
    static final int EXCERPT_CAP = 1000;
    /** {@code error} kolonu VARCHAR(255) — uzun istisna metni INSERT'i düşürmesin. */
    static final int ERROR_CAP = 255;

    public static KeywordResult build(KeywordMonitor m, Map<String, Object> r, boolean ok, String checkedAt) {
        boolean found = Boolean.TRUE.equals(r.getOrDefault("found", false));
        int count = r.get("count") instanceof Number cn ? cn.intValue() : (found ? 1 : 0);
        KeywordResult res = new KeywordResult();
        res.setMonitorId(m.getId());
        res.setFound(found);
        res.setOccurrences(count);
        res.setOk(ok);
        res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
        res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
        res.setSnippet((String) r.get("snippet"));
        String err = (String) r.get("error");
        res.setError(err == null ? null : (err.length() > ERROR_CAP ? err.substring(0, ERROR_CAP - 1) + "…" : err));
        res.setCheckedAt(checkedAt);
        // ── Yanıt meta verisi (her satır) ──
        res.setFinalUrl(str(r.get("final_url")));
        res.setRedirectCount(r.get("redirect_count") instanceof Number n ? n.intValue() : null);
        res.setContentType(cap(str(r.get("content_type")), 200));
        res.setBodyBytes(r.get("body_bytes") instanceof Number n ? n.longValue() : null);
        res.setBodyTruncated(r.get("body_truncated") instanceof Boolean b ? b : null);
        res.setCharset(cap(str(r.get("charset")), 60));
        res.setVia(cap(str(r.get("via")), 10));
        if (ok) return res;
        // ── Teşhis (yalnız başarısız satır) ──
        String reason = str(r.get("failure_reason"));
        String detail = str(r.get("failure_detail"));
        if (reason == null) {
            // Savunma: çağıran beklentisiz kontrol yaptıysa ya da sonuç eski biçimdeyse — kural yine yazılsın.
            if (err != null) {
                reason = KeywordFailureClassifier.UNKNOWN;
                detail = "İstek tamamlanamadı: " + err;
            } else {
                Integer st = res.getHttpStatus();
                long bytes = res.getBodyBytes() == null ? 1L : res.getBodyBytes();
                KeywordFailureClassifier.Reason rc = KeywordFailureClassifier.forCondition(
                        st == null ? 200 : st, count, m.getMatchOperator(), m.getMatchCount() != null ? m.getMatchCount() : 1,
                        bytes, Boolean.TRUE.equals(res.getBodyTruncated()), m.getKeyword());
                reason = rc.code();
                detail = rc.detail();
            }
        }
        res.setFailureReason(cap(reason, 40));
        res.setFailureDetail(KeywordFailureClassifier.cap(detail));
        res.setHints(hints(r.get("hints")));
        res.setExcerpt(cap(str(r.get("excerpt")), EXCERPT_CAP));
        return res;
    }

    private static List<String> hints(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
