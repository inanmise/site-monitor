package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.service.SchedulerServiceGrowthAccess;
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

/**
 * Sayfa Hızı 2c (2026-11-15) — büyüme referansının okuması: tek izleme, zaman penceresi, en yeni önce, LIMIT. Sorgu
 * {@code IN (:id, :id)} + {@code UNTIL_R} biçimindedir (genel planda da dizin); burada H2'de doğru satırları doğru sırayla
 * döndürdüğü ve zamanlayıcının süzgeciyle (tam, aynı kip, zamanlanmış) birlikte örnek listesini doğru kurduğu pinlenir.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PageSpeedGrowthWindowRepositoryTest {

    @Autowired PageSpeedCheckRepository checks;

    @AfterEach
    void cleanUp() {
        checks.deleteAll();
    }

    private PageSpeedCheck row(long monitorId, String at, long bytes, int requests) {
        PageSpeedCheck c = new PageSpeedCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(at);
        c.setOk(true);
        c.setCapped(false);
        c.setBytesTruncated(false);
        c.setTotalBytes(bytes);
        c.setRequestCount(requests);
        return c;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Yalnız o izlemenin pencere içi satırları, en yeni önce, LIMIT; süzgeç kısmi / başka kip / elle / teyidi atar")
    void windowAndFilter() {
        checks.save(row(1, "2026-11-01T10:00:00", 1, 1));                // pencere dışı (eski)
        checks.save(row(1, "2026-11-10T10:00:00", 100_000, 10));
        checks.save(row(1, "2026-11-11T10:00:00", 110_000, 11));
        PageSpeedCheck partial = row(1, "2026-11-12T10:00:00", 50_000, 5);
        partial.setTimeTruncated(true);
        checks.save(partial);
        PageSpeedCheck ext = row(1, "2026-11-13T10:00:00", 900_000, 90);
        ext.setExtended(true);
        checks.save(ext);
        PageSpeedCheck manual = row(1, "2026-11-13T11:00:00", 800_000, 80);
        manual.setManual(true);
        checks.save(manual);
        PageSpeedCheck recheck = row(1, "2026-11-13T12:00:00", 700_000, 70);
        recheck.setRecheck(true);
        recheck.setBreachedMetrics("GROWSIZE,SIZEBAND");
        checks.save(recheck);
        PageSpeedCheck band = row(1, "2026-11-14T10:00:00", 120_000, 12);
        band.setBreachedMetrics("SIZE,SIZEBAND");
        checks.save(band);
        PageSpeedCheck down = row(1, "2026-11-14T11:00:00", 0, 1);
        down.setOk(false);
        checks.save(down);
        checks.save(row(2, "2026-11-14T10:00:00", 5, 5));                // başka izleme
        checks.save(row(1, "2026-11-16T10:00:00", 1, 1));                // pencere dışı (gelecek)

        List<Object[]> rows = checks.growthWindow(1L, "2026-11-08T00:00:00", "2026-11-15T00:00:00", 100);
        assertThat(rows).hasSize(8);
        assertThat(rows.get(0)[0]).isEqualTo(0L);                        // en yeni önce
        assertThat(rows.get(rows.size() - 1)[0]).isEqualTo(100_000L);

        var classic = SchedulerServiceGrowthAccess.samplesOf(rows, false);
        assertThat(classic).extracting(s -> s.totalBytes()).containsExactly(120_000L, 110_000L, 100_000L);
        assertThat(classic.get(0).bandBreach()).isTrue();
        assertThat(classic.get(1).bandBreach()).isFalse();
        var extended = SchedulerServiceGrowthAccess.samplesOf(rows, true);
        assertThat(extended).extracting(s -> s.totalBytes()).containsExactly(900_000L);

        assertThat(checks.growthWindow(1L, "2026-11-08T00:00:00", "2026-11-15T00:00:00", 2)).hasSize(2);
    }

    private static final java.time.format.DateTimeFormatter ISO =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC);

    private static com.sitemonitor.service.PageSpeedCheckerService.Result measured(long bytes) {
        return new com.sitemonitor.service.PageSpeedCheckerService.Result("OK", 200, 100, 200, 1000, bytes, 10, 0, false, false,
                List.of(), null, List.of(), null, 0, null, false, null, List.of(), null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("İnceleme 2026-11-15: ölçüm tanımı değişince (growth_since) eski tanımın ölçümleri referans DEĞİL — sonraki "
            + "ölçümün büyüme anahtarları kararsız, GROWSIZE yok; growth_since olmadan aynı geçmiş GROWSIZE açar")
    void growthSinceResetsTheReference() {
        java.time.Instant now = java.time.Instant.now();
        for (int i = 1; i <= 8; i++) checks.save(row(7, ISO.format(now.minus(java.time.Duration.ofHours(6L * i))), 100_000, 10));
        com.sitemonitor.model.PageSpeedMonitor m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(7L);
        m.setUrl("https://yeni.example.com/");
        m.setGrowthBytesPct(20);

        // tanım değişmedi (2c öncesi izleme, growth_since boş): eski sayfanın 8 ölçümü referans → %200 büyüme ihlal
        var before = com.sitemonitor.service.SchedulerServiceGrowthAccess.readAndApply(checks, m, measured(300_000));
        assertThat(before.breached()).containsExactly("GROWSIZE");

        // URL / kip değişti → growth_since = şimdi − 1 dk: pencere ondan başlar, referans boş → kararsız, ihlal YOK
        m.setGrowthSince(ISO.format(now.minus(java.time.Duration.ofMinutes(1))));
        var after = com.sitemonitor.service.SchedulerServiceGrowthAccess.readAndApply(checks, m, measured(300_000));
        assertThat(after.breached()).isEmpty();
        assertThat(after.status()).isEqualTo("OK");
        assertThat(after.undecided()).containsExactly("GROWSIZE");

        // yeni tanımla 5 tam ölçüm birikince karar yeniden verilir (yalnız growth_since sonrası satırlar)
        for (int i = 0; i < 5; i++) checks.save(row(7, ISO.format(now.minusSeconds(50 - i * 5L)), 290_000, 10));
        var again = com.sitemonitor.service.SchedulerServiceGrowthAccess.readAndApply(checks, m, measured(300_000));
        assertThat(again.breached()).isEmpty();
        assertThat(again.undecided()).isEmpty();
        assertThat(again.growth().medianBytes()).isEqualTo(290_000L);
    }

    private static com.sitemonitor.service.PageSpeedCheckerService.Result partialMeasured(long bytes) {
        return new com.sitemonitor.service.PageSpeedCheckerService.Result("OK", 200, 100, 200, 1000, bytes, 10, 0, true, false,
                List.of(), null, List.of(), null, 0, null, false, null, List.of(), null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("İnceleme 2026-11-17: HER ölçümü kısmi sayfa — tam 7 günlük pencerede tam ölçüm yok → büyüme karşılaştırılamaz "
            + "(ne ihlal ne kararsız: açık alarm diğer eşiklerle kapanabilir); tanım yeni (growth_since) → kararsız kalır")
    void alwaysPartialPageIsNotComparable() {
        java.time.Instant now = java.time.Instant.now();
        for (int i = 1; i <= 12; i++) {
            PageSpeedCheck p = row(8, ISO.format(now.minus(java.time.Duration.ofHours(12L * i))), 900_000, 90);
            p.setCapped(true);   // kaynak tavanı: kısmi → referansa girmez
            checks.save(p);
        }
        com.sitemonitor.model.PageSpeedMonitor m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(8L);
        m.setUrl("https://agir.example.com/");
        m.setGrowthBytesPct(20);
        m.setGrowthRequestsPct(20);
        m.setGrowthBytesBand(true);

        var r = com.sitemonitor.service.SchedulerServiceGrowthAccess.readAndApply(checks, m, partialMeasured(950_000));
        assertThat(r.growth().samples()).isZero();
        assertThat(r.growth().fullWindow()).isTrue();
        assertThat(r.breached()).isEmpty();
        assertThat(r.undecided()).as("tam pencerede dolamayacak referans kararsız sayılmaz").isEmpty();

        // Tanım az önce değişti: pencere kısa, referans dolabilir → kararsız (açık alarm sahte kapanmaz)
        m.setGrowthSince(ISO.format(now.minus(java.time.Duration.ofHours(1))));
        var young = com.sitemonitor.service.SchedulerServiceGrowthAccess.readAndApply(checks, m, partialMeasured(950_000));
        assertThat(young.growth().fullWindow()).isFalse();
        assertThat(young.undecided()).containsExactly("GROWSIZE", "GROWREQ", "SIZEBAND");
    }

    @Test
    @DisplayName("Pencerenin başı: max(şimdi − 7 gün, growth_since) — boş ya da daha eski growth_since 7 günü değiştirmez")
    void windowStart() {
        java.time.Instant now = java.time.Instant.parse("2026-11-15T12:00:00Z");
        assertThat(com.sitemonitor.service.SchedulerServiceGrowthAccess.windowStart(now, null)).isEqualTo("2026-11-08T12:00:00");
        assertThat(com.sitemonitor.service.SchedulerServiceGrowthAccess.windowStart(now, "2026-11-01T00:00:00"))
                .isEqualTo("2026-11-08T12:00:00");
        assertThat(com.sitemonitor.service.SchedulerServiceGrowthAccess.windowStart(now, "2026-11-15T11:30:00"))
                .isEqualTo("2026-11-15T11:30:00");
    }
}
