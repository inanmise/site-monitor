package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedCheck;
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
    @Autowired PageSpeedCheckRepository checks;

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
        checks.deleteAll();
    }

    private static PageSpeedResource res(long monitorId, Long checkId, String reason, String url, String type,
                                         long bytes, Integer status, boolean third) {
        PageSpeedResource r = row(monitorId, url, reason);
        r.setCheckId(checkId);
        r.setType(type);
        r.setBytes(bytes);
        r.setStatusCode(status);
        r.setThirdParty(third);
        return r;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Sayfalama + süzgeç (2026-11-14): tür / taraf / alınamayan; bayt azalan, eşitlikte kimlik; sayılar süzgeçle")
    void latestPage_filtersAndPages() {
        repo.saveAll(List.of(
                res(1, 10L, PageSpeedResource.KEEP_LATEST, "https://a/1.js", "JS", 900, 200, false),
                res(1, 10L, PageSpeedResource.KEEP_LATEST, "https://a/2.js", "JS", 500, 404, true),
                res(1, 10L, PageSpeedResource.KEEP_LATEST, "https://a/3.png", "IMG", 700, 200, true),
                res(1, 10L, PageSpeedResource.KEEP_LATEST, "https://a/fav.ico", "FAVICON", 100, null, false),
                res(1, 10L, PageSpeedResource.KEEP_LATEST, "https://a/4.css", "CSS", 300, 200, false),
                res(1, 9L, PageSpeedResource.KEEP_BREACH, "https://a/eski.js", "JS", 5000, 200, false),
                res(2, 11L, PageSpeedResource.KEEP_LATEST, "https://b/x.js", "JS", 9999, 200, false)));
        var all = com.sitemonitor.controller.MonitoringController.ResFilter.of(null, null, false);
        var p0 = repo.latestPage(1L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(0, 2));
        assertThat(p0.getTotalElements()).isEqualTo(5);
        assertThat(p0.getContent()).extracting(PageSpeedResource::getUrl).containsExactly("https://a/1.js", "https://a/3.png");
        var p2 = repo.latestPage(1L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(2, 2));
        assertThat(p2.getContent()).extracting(PageSpeedResource::getUrl).containsExactly("https://a/fav.ico");

        var img = com.sitemonitor.controller.MonitoringController.ResFilter.of("img", null, false);
        assertThat(repo.latestPage(1L, img.allTypes(), img.types(), img.party(), img.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(PageSpeedResource::getUrl).containsExactly("https://a/3.png", "https://a/fav.ico");
        var third = com.sitemonitor.controller.MonitoringController.ResFilter.of(null, "third", false);
        assertThat(repo.latestPage(1L, third.allTypes(), third.types(), third.party(), third.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(PageSpeedResource::getUrl).containsExactly("https://a/3.png", "https://a/2.js");
        var failed = com.sitemonitor.controller.MonitoringController.ResFilter.of(null, null, true);
        assertThat(repo.latestPage(1L, failed.allTypes(), failed.types(), failed.party(), failed.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(PageSpeedResource::getUrl).containsExactly("https://a/2.js", "https://a/fav.ico");

        // İhlal anı: başka izlemenin checkId'si satır döndürmez
        assertThat(repo.checkPage(1L, 9L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(PageSpeedResource::getUrl).containsExactly("https://a/eski.js");
        assertThat(repo.checkPage(1L, 11L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getTotalElements()).isZero();

        // Sayaçlar: [tür, üçüncü taraf, alınamadı, adet]
        List<Object[]> facets = repo.latestFacets(1L);
        long n = facets.stream().mapToLong(f -> ((Number) f[3]).longValue()).sum();
        assertThat(n).isEqualTo(5);
        long failedN = facets.stream().filter(f -> ((Number) f[2]).intValue() == 1)
                .mapToLong(f -> ((Number) f[3]).longValue()).sum();
        assertThat(failedN).isEqualTo(2);
        assertThat(repo.checkFacets(1L, 9L)).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Tür kırılımı serisi (2026-11-14): yalnız o izleme, aralık içi, yeniden eskiye; eski satır null kalır")
    void typeSeriesRaw_readsBreakdownColumns() {
        PageSpeedCheck a = new PageSpeedCheck();
        a.setMonitorId(5L); a.setCheckedAt("2026-11-10T10:00:00"); a.setOk(true);
        a.setHtmlBytes(1000L); a.setJsBytes(2000L); a.setCssBytes(300L); a.setImgBytes(4000L); a.setFontBytes(50L); a.setOtherBytes(7L);
        PageSpeedCheck b = new PageSpeedCheck();
        b.setMonitorId(5L); b.setCheckedAt("2026-11-11T10:00:00"); b.setOk(true);   // eski satır: kırılım yok
        PageSpeedCheck c = new PageSpeedCheck();
        c.setMonitorId(6L); c.setCheckedAt("2026-11-10T11:00:00"); c.setOk(true); c.setJsBytes(1L);
        checks.saveAll(List.of(a, b, c));
        List<Object[]> rows = checks.typeSeriesRaw(5L, "2026-11-01T00:00:00", "2026-11-30T00:00:00", 100);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("2026-11-11T10:00:00");
        assertThat(rows.get(0)[2]).isNull();
        assertThat(((Number) rows.get(1)[2]).longValue()).isEqualTo(2000L);
        assertThat(((Number) rows.get(1)[4]).longValue()).isEqualTo(4000L);
        assertThat(checks.typeSeriesRaw(5L, "2026-11-01T00:00:00", "2026-11-10T12:00:00", 100)).hasSize(1);
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("İnceleme B4: ihlalin başladığı ölçüm hem LATEST hem BREACH yazılır — ölçüm seçilince TEK kopya (liste, sayı, süzgeç)")
    void checkPage_oneCopyPerCheck() {
        repo.saveAll(List.of(
                res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/1.js", "JS", 900, 200, false),
                res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/2.png", "IMG", 500, 200, true),
                res(1, 20L, PageSpeedResource.KEEP_BREACH, "https://a/1.js", "JS", 900, 200, false),
                res(1, 20L, PageSpeedResource.KEEP_BREACH, "https://a/2.png", "IMG", 500, 200, true),
                // ihlal anı olmayan son ölçüm: yalnız LATEST — o da tek kopya döner
                res(2, 30L, PageSpeedResource.KEEP_LATEST, "https://b/x.js", "JS", 100, 200, false)));
        var all = com.sitemonitor.controller.MonitoringController.ResFilter.of(null, null, false);
        var page = repo.checkPage(1L, 20L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(PageSpeedResource::getKeepReason).containsOnly(PageSpeedResource.KEEP_BREACH);
        assertThat(repo.countByMonitorIdAndCheckId(1L, 20L)).isEqualTo(2);
        long facetTotal = repo.checkFacets(1L, 20L).stream().mapToLong(row -> ((Number) row[3]).longValue()).sum();
        assertThat(facetTotal).isEqualTo(2);
        assertThat(repo.checkPage(2L, 30L, all.allTypes(), all.types(), all.party(), all.failed(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);
        // İnceleme B5: CSV son ölçümün kimliğini BİR KEZ okur
        assertThat(repo.latestCheckId(1L)).isEqualTo(20L);
        assertThat(repo.latestCheckId(99L)).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Şelale (2026-11-15): isteğin atıldığı ana göre sıralı, eski satır (start_ms yok) sonda; ölçümün TEK kopyası; başka izleme sızmaz; sınır")
    void waterfall_orderedByStartOneCopy() {
        PageSpeedResource a = res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/late.js", "JS", 100, 200, false);
        a.setStartMs(900);
        PageSpeedResource b = res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/first.css", "CSS", 100, 200, false);
        b.setStartMs(10);
        PageSpeedResource c = res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/old.png", "IMG", 100, 200, false);
        PageSpeedResource d = res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/mid.js", "JS", 100, 200, false);
        d.setStartMs(300);
        PageSpeedResource other = res(2, 20L, PageSpeedResource.KEEP_LATEST, "https://b/x.js", "JS", 100, 200, false);
        other.setStartMs(1);
        // İhlal anı: aynı ölçüm hem LATEST hem BREACH → tek kopya (BREACH)
        PageSpeedResource br1 = res(1, 30L, PageSpeedResource.KEEP_LATEST, "https://a/z.js", "JS", 100, 200, false);
        br1.setStartMs(5);
        PageSpeedResource br2 = res(1, 30L, PageSpeedResource.KEEP_BREACH, "https://a/z.js", "JS", 100, 200, false);
        br2.setStartMs(5);
        repo.saveAll(List.of(a, b, c, d, other, br1, br2));

        assertThat(repo.waterfall(1L, 20L, 10).stream().map(PageSpeedResource::getUrl).toList())
                .containsExactly("https://a/first.css", "https://a/mid.js", "https://a/late.js", "https://a/old.png");
        assertThat(repo.waterfall(1L, 20L, 2)).hasSize(2);
        assertThat(repo.waterfall(1L, 30L, 10)).hasSize(1);
        assertThat(repo.waterfall(2L, 30L, 10)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Görsel köprüsü: yalnız bu izlemenin saklı GÖRSEL satırı, adres birebir; başka tür / başka izleme / farklı adres boş")
    void findStoredImage_exactImageOfThisMonitor() {
        repo.saveAll(List.of(
                res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/hero.png", "IMG", 420_000, 200, false),
                res(1, 20L, PageSpeedResource.KEEP_LATEST, "https://a/app.js", "JS", 1000, 200, false),
                res(2, 21L, PageSpeedResource.KEEP_LATEST, "https://b/other.png", "IMG", 5, 200, false)));
        assertThat(repo.findStoredImage(1L, "https://a/hero.png")).singleElement()
                .satisfies(r -> assertThat(r.getBytes()).isEqualTo(420_000L));
        assertThat(repo.findStoredImage(1L, "https://a/app.js")).isEmpty();
        assertThat(repo.findStoredImage(1L, "https://b/other.png")).isEmpty();
        assertThat(repo.findStoredImage(1L, "https://a/HERO.png")).isEmpty();
    }
}
