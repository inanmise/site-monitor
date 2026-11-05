package com.sitemonitor.service;

import com.sitemonitor.model.KeywordMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyword ham sonucu → karar ve sweep şekli (kw sözleşmesi §10.6 / §8.1). Kararı kontrol verir ({@code ok}: adet koşulu +
 * durum kodu kuralı + son URL kuralı); {@code ok} OLMAYAN ham sonuç (eski 20.181 pod, takipçinin elle sonucu, dağıtık iş)
 * bugünkü hesapla değerlendirilir — karışık pod'da karar değişmez. E1 anahtarları yalnız varsa taşınır.
 */
class SchedulerKeywordOutcomeTest {

    private static KeywordMonitor monitor(String op, int n) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(3L); m.setUrl("https://x.example.com/"); m.setKeyword("Sepet");
        m.setMatchOperator(op); m.setMatchCount(n);
        return m;
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("ok varsa karar O (durum / son URL kuralı dâhil); hata varsa ok=true bile down")
    void okFromChecker() {
        KeywordMonitor m = monitor("GTE", 1);
        // adet tutsa da durum kuralı reddetti → down
        assertThat(SchedulerService.keywordOk(m, raw("found", true, "count", 4, "ok", false, "status_accepted", false))).isFalse();
        assertThat(SchedulerService.keywordOk(m, raw("found", false, "count", 0, "ok", true))).isTrue();
        assertThat(SchedulerService.keywordOk(m, raw("count", 4, "ok", true, "error", "x"))).isFalse();
    }

    @Test
    @DisplayName("ok yoksa (eski pod / eski ham sonuç) bugünkü hesap: hata yok ∧ adet koşulu")
    void legacyRaw() {
        assertThat(SchedulerService.keywordOk(monitor("GTE", 2), raw("found", true, "count", 3))).isTrue();
        assertThat(SchedulerService.keywordOk(monitor("GTE", 2), raw("found", true, "count", 1))).isFalse();
        assertThat(SchedulerService.keywordOk(monitor("LTE", 0), raw("found", false, "count", 0))).isTrue();
        assertThat(SchedulerService.keywordOk(monitor("LTE", 0), raw("found", false, "error", "connect timed out"))).isFalse();
        assertThat(SchedulerService.keywordOk(monitor("GTE", 1), raw("found", true))).as("count yok, found → 1").isTrue();
    }

    @Test
    @DisplayName("sweep şekli: E1 anahtarları (neden, ayrıntı, ipucu, alıntı, son URL, status_accepted) yalnız varsa; eski ham sonuç bugünkü şekil")
    void outcomeCarriesE1Keys() {
        KeywordMonitor m = monitor("GTE", 1);
        Map<String, Object> out = SchedulerService.keywordOutcome(m, raw("found", false, "count", 0, "ok", false,
                "http_status", 200, "response_ms", 12L, "failure_reason", "KEYWORD_NOT_FOUND",
                "failure_detail", "Sayfa yüklendi ama « Sepet » yok", "hints", List.of("LOGIN_PAGE"), "excerpt", "Oturum açın",
                "final_url", "https://x.example.com/giris", "status_accepted", true, "body_bytes", 900L));
        assertThat(out).containsEntry("status", "down").containsEntry("occurrences", 0)
                .containsEntry("failure_reason", "KEYWORD_NOT_FOUND").containsEntry("hints", List.of("LOGIN_PAGE"))
                .containsEntry("excerpt", "Oturum açın").containsEntry("final_url", "https://x.example.com/giris")
                .containsEntry("status_accepted", true)
                .doesNotContainKey("body_bytes");
        assertThat(SchedulerService.KEYWORD_OUTCOME_EXTRA_KEYS)
                .containsExactly("failure_reason", "failure_detail", "hints", "excerpt", "final_url", "status_accepted",
                        // kw-be2 Aşama 2 (§10.6) — sona eklendi (JSON gidiş-dönüş sırası)
                        "level", "rules_summary", "content_changed", "change_summary", "baseline_taken_at", "baseline_source",
                        "keyword_spec");

        Map<String, Object> legacy = SchedulerService.keywordOutcome(m, raw("found", true, "count", 2, "http_status", 200,
                "response_ms", 5L, "snippet", "… Sepet …"));
        assertThat(legacy.keySet()).containsExactly("status", "error", "http_status", "response_ms", "snippet", "occurrences");
        assertThat(legacy).containsEntry("status", "up");

        Map<String, Object> cfg = SchedulerService.keywordOutcome(m, raw("found", false, "error", "URL'de host yok",
                "config_error", true, "ok", false));
        assertThat(cfg).containsEntry("status", "up").containsEntry("error", null);
    }
}
