package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BO4/O1 (bug regresyon 2026-09-27) — Sayfa Hızı "Kaynak Kırılımı" değişimi ATOMİK olmalı.
 *
 * <p>Sil + yaz eskiden {@code SchedulerService.writeResourceBreakdown}'daydı; metodun {@code @Transactional}'ı
 * aynı sınıftan çağrıldığı için proxy'den geçmiyor, silme ayrı commit ediliyordu → {@code saveAll} düşerse son
 * iyi kırılım kayboluyordu. Testler {@code NOT_SUPPORTED} ile koşar: {@code @DataJpaTest}'in kendi sarmal
 * transaction'ı eksikliği GİZLERDİ (bkz. {@code RepositoryWriteTransactionGuardTest}). {@code replaceLatest}'ten
 * {@code @Transactional} kaldırılırsa ilk test KIRMIZI olur.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PageSpeedResourceRepositoryTest {

    @Autowired PageSpeedResourceRepository repo;

    private static PageSpeedResource row(long monitorId, String url, String reason) {
        PageSpeedResource r = new PageSpeedResource();
        r.setMonitorId(monitorId);
        r.setCheckedAt("2026-09-27T10:00:00");   // yalnız saklanan damga; kayan pencereyle karşılaştırılmaz
        r.setUrl(url);
        r.setType("JS");
        r.setBytes(1000L);
        r.setKeepReason(reason);
        return r;
    }

    private List<String> urls(long monitorId, String reason) {
        return repo.findAll().stream()
                .filter(r -> r.getMonitorId() == monitorId && reason.equals(r.getKeepReason()))
                .map(PageSpeedResource::getUrl).sorted().toList();
    }

    @AfterEach
    void cleanUp() {
        repo.deleteAll();   // NOT_SUPPORTED: yazımlar gerçekten commit edildi, sınıfı kirletmesin
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("KAPI: yazma yarıda düşerse ESKİ LATEST kırılımı yerinde kalır (silme geri alınır)")
    void replaceLatest_failedWrite_keepsPreviousBreakdown() {
        repo.saveAll(List.of(row(1, "https://www.example.com/a.js", PageSpeedResource.KEEP_LATEST),
                row(1, "https://www.example.com/b.js", PageSpeedResource.KEEP_LATEST)));

        // İkinci satır url=null → NOT NULL ihlali: saveAll yarıda düşer.
        List<PageSpeedResource> broken = List.of(row(1, "https://www.example.com/c.js", PageSpeedResource.KEEP_LATEST),
                row(1, null, PageSpeedResource.KEEP_LATEST));
        assertThatThrownBy(() -> repo.replaceLatest(1L, broken)).isInstanceOf(RuntimeException.class);

        assertThat(urls(1, PageSpeedResource.KEEP_LATEST))
                .as("başarısız değişim son iyi kırılımı SİLMEMELİ")
                .containsExactly("https://www.example.com/a.js", "https://www.example.com/b.js");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("replaceLatest: yalnız O izlemenin LATEST satırları değişir; BREACH delili ve başka izleme dokunulmaz")
    void replaceLatest_replacesOnlyLatestOfThatMonitor() {
        repo.saveAll(List.of(row(1, "https://www.example.com/eski.js", PageSpeedResource.KEEP_LATEST),
                row(1, "https://www.example.com/delil.js", PageSpeedResource.KEEP_BREACH),
                row(2, "https://www.example.com/baska.js", PageSpeedResource.KEEP_LATEST)));

        repo.replaceLatest(1L, List.of(row(1, "https://www.example.com/yeni.js", PageSpeedResource.KEEP_LATEST)));

        assertThat(urls(1, PageSpeedResource.KEEP_LATEST)).containsExactly("https://www.example.com/yeni.js");
        assertThat(urls(1, PageSpeedResource.KEEP_BREACH)).containsExactly("https://www.example.com/delil.js");
        assertThat(urls(2, PageSpeedResource.KEEP_LATEST)).containsExactly("https://www.example.com/baska.js");
    }
}
