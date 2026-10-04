package com.sitemonitor.service.keyword;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ham kontrol sonucu → {@code keyword_results} satırı (2026-10-04): zamanlanmış tur ve elle kontrolün ORTAK eşlemesi.
 * Eski alanlar aynen; meta her satırda; teşhis yalnız başarısız satırda; {@code hints} API'de liste, JSON metni sızmaz.
 */
class KeywordResultMapperTest {

    private static KeywordMonitor monitor() {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(9L);
        m.setUrl("https://site.example.com/");
        m.setKeyword("Kampanya");
        m.setMatchOperator("GTE");
        m.setMatchCount(1);
        return m;
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> r = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    @Test
    @DisplayName("başarılı satır: eski alanlar + meta; neden/ayrıntı/ipucu/alıntı YAZILMAZ")
    void okRow() {
        KeywordResult res = KeywordResultMapper.build(monitor(), raw("found", true, "count", 2, "http_status", 200,
                "response_ms", 40L, "snippet", "… Kampanya …", "final_url", "https://site.example.com/", "redirect_count", 0,
                "content_type", "text/html", "body_bytes", 1234L, "body_truncated", false, "charset", "UTF-8", "via", "direct",
                "failure_reason", "SHOULD_NOT_APPEAR", "hints", List.of("CASE_MISMATCH"), "excerpt", "x"), true, "2026-10-04T10:00:00");
        assertThat(res.getOk()).isTrue();
        assertThat(res.getFound()).isTrue();
        assertThat(res.getOccurrences()).isEqualTo(2);
        assertThat(res.getMonitorId()).isEqualTo(9L);
        assertThat(res.getFinalUrl()).isEqualTo("https://site.example.com/");
        assertThat(res.getBodyBytes()).isEqualTo(1234L);
        assertThat(res.getVia()).isEqualTo("direct");
        assertThat(res.getFailureReason()).isNull();
        assertThat(res.getFailureDetail()).isNull();
        assertThat(res.getHints()).isEmpty();
        assertThat(res.getExcerpt()).isNull();
    }

    @Test
    @DisplayName("başarısız satır: neden + ayrıntı + ipuçları + alıntı yazılır")
    void failedRow() {
        KeywordResult res = KeywordResultMapper.build(monitor(), raw("found", false, "count", 0, "http_status", 200,
                "failure_reason", "KEYWORD_NOT_FOUND", "failure_detail", "Sayfa yüklendi ama « Kampanya » yok",
                "hints", List.of("LOGIN_PAGE", "JS_RENDERED"), "excerpt", "Giriş yapın"), false, "t");
        assertThat(res.getFailureReason()).isEqualTo("KEYWORD_NOT_FOUND");
        assertThat(res.getFailureDetail()).contains("Kampanya");
        assertThat(res.getHints()).containsExactly("LOGIN_PAGE", "JS_RENDERED");
        assertThat(res.getHintsJson()).isEqualTo("[\"LOGIN_PAGE\",\"JS_RENDERED\"]");
        assertThat(res.getExcerpt()).isEqualTo("Giriş yapın");
    }

    @Test
    @DisplayName("savunma: neden yoksa hata → UNKNOWN, değilse kuraldan türetilir; uzun hata 255'e kırpılır")
    void fallbacks() {
        KeywordResult err = KeywordResultMapper.build(monitor(), raw("found", false, "error", "x".repeat(400)), false, "t");
        assertThat(err.getFailureReason()).isEqualTo("UNKNOWN");
        assertThat(err.getError()).hasSize(255);
        assertThat(err.getFailureDetail()).hasSizeLessThanOrEqualTo(500);
        KeywordResult cond = KeywordResultMapper.build(monitor(), raw("found", false, "count", 0, "http_status", 404,
                "body_bytes", 50L), false, "t");
        assertThat(cond.getFailureReason()).isEqualTo("HTTP_STATUS");
    }

    @Test
    @DisplayName("JSON: 'hints' DİZİ olarak çıkar, ham 'hints_json' sızmaz; eski satır (null) boş liste")
    void jsonShape() {
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        KeywordResult r = new KeywordResult();
        r.setHints(List.of("CASE_MISMATCH"));
        r.setFailureReason("KEYWORD_NOT_FOUND");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"hints\":[\"CASE_MISMATCH\"]").contains("\"failure_reason\":\"KEYWORD_NOT_FOUND\"")
                .doesNotContain("hints_json").doesNotContain("hintsJson");
        assertThat(mapper.writeValueAsString(new KeywordResult())).contains("\"hints\":[]");
    }

    @Test
    @DisplayName("ipucu JSON ayrıştırma bozuk/uydurma kodu atar; yazım 500 karakteri aşmaz")
    void hintsParsing() {
        assertThat(KeywordResult.parseHints("[\"A_B\",\"bad code\",\"<x>\",\"OK2\"]")).containsExactly("A_B", "OK2");
        assertThat(KeywordResult.parseHints(null)).isEmpty();
        assertThat(KeywordResult.toHintsJson(List.of())).isNull();
        List<String> many = java.util.Collections.nCopies(100, "WAF_OR_BLOCK_PAGE");
        assertThat(KeywordResult.toHintsJson(many)).hasSizeLessThanOrEqualTo(500).endsWith("]");
    }
}
