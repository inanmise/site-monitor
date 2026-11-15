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
}
