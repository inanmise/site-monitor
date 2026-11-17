package com.sitemonitor.controller;

import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kanal satırı şablon özeti önbelleği (2026-11-17 perf): genel bakış / kanal listesi her çağrıda her kanalın şablonunu
 * ayrıştırıyordu. Önbellek kanal başına; anahtar sürüm + metin — değişen şablon asla eski özetle gösterilmez, ayrıştırma
 * hatası önbelleğe yazılmaz, boyut sınırlı.
 */
class TechErrorTemplateSummaryCacheTest {

    private static final String TR = TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr"));
    private static final String EN = TechErrorTemplate.toJson(TechErrorTemplate.defaults("en"));

    private static Function<String, Template> counting(AtomicInteger calls) {
        return json -> { calls.incrementAndGet(); return TechErrorTemplate.fromStored(json); };
    }

    @Test
    @DisplayName("aynı kanal + sürüm + metin bir kez ayrıştırılır; özet eski yolla (fromStored) birebir")
    void sameKeyParsedOnce() {
        AtomicInteger calls = new AtomicInteger();
        TechErrorReportController.TemplateSummaryCache cache = new TechErrorReportController.TemplateSummaryCache(counting(calls));
        TechErrorReportController.TemplateSummary first = cache.get(10L, 3, TR);
        TechErrorReportController.TemplateSummary second = cache.get(10L, 3, TR);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first).isEqualTo(TechErrorReportController.TemplateSummary.of(TechErrorTemplate.fromStored(TR)));
        assertThat(first.lang()).isEqualTo("tr");
    }

    @Test
    @DisplayName("sürüm ya da metin değişince yeniden ayrıştırılır (sürüm artmasa da metin farkı yakalanır); bozuk metin = sistem şablonu sonucu")
    void versionOrTextChangeReparses() {
        AtomicInteger calls = new AtomicInteger();
        TechErrorReportController.TemplateSummaryCache cache = new TechErrorReportController.TemplateSummaryCache(counting(calls));
        cache.get(10L, 3, TR);
        assertThat(cache.get(10L, 4, TR).lang()).isEqualTo("tr");
        assertThat(cache.get(10L, 4, EN).lang()).isEqualTo("en");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(cache.get(10L, 4, "{bozuk")).isEqualTo(TechErrorReportController.TemplateSummary.of(TechErrorTemplate.fromStored("{bozuk")));
        assertThat(cache.get(10L, 4, null)).isEqualTo(TechErrorReportController.TemplateSummary.of(TechErrorTemplate.fromStored(null)));
        assertThat(cache.size()).isEqualTo(1);   // kanal başına tek kayıt
    }

    @Test
    @DisplayName("ayrıştırma atarsa önbelleğe yazılmaz (sonraki çağrı yeniden dener); kimliksiz kanal önbelleğe girmez; boyut sınırlı")
    void failureNotCachedAndBounded() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] fail = {true};
        TechErrorReportController.TemplateSummaryCache cache = new TechErrorReportController.TemplateSummaryCache(json -> {
            calls.incrementAndGet();
            if (fail[0]) throw new IllegalStateException("geçici");
            return TechErrorTemplate.fromStored(json);
        });
        assertThatThrownBy(() -> cache.get(10L, 3, TR)).isInstanceOf(IllegalStateException.class);
        fail[0] = false;
        assertThat(cache.get(10L, 3, TR).lang()).isEqualTo("tr");
        assertThat(calls.get()).isEqualTo(2);
        cache.get(null, 1, TR);
        cache.get(null, 1, TR);
        assertThat(calls.get()).isEqualTo(4);

        Template tr = TechErrorTemplate.defaults("tr");
        TechErrorReportController.TemplateSummaryCache big = new TechErrorReportController.TemplateSummaryCache(json -> tr);
        for (long id = 1; id <= TechErrorReportController.TemplateSummaryCache.MAX + 5; id++) big.get(id, 1, TR);
        assertThat(big.size()).isLessThanOrEqualTo(TechErrorReportController.TemplateSummaryCache.MAX);
    }
}
