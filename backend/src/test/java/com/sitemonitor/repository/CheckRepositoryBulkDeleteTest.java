package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryAlertRows;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.model.PageSpeedResource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İzleme silinince geçmiş satırlarının TOPLU silinmesi (kapı incelemesi 2026-11-15).
 *
 * <p>Türetilmiş {@code deleteByMonitorId} her satırı varlık olarak yükleyip tek tek siliyordu — 1 dakikalık bir sorgu
 * izlemesinin ~259 000 kontrol satırı (TEXT kolonlarıyla) istek işleminde belleğe alınıyor, 259 000 {@code DELETE} gidiyordu.
 * Toplu JPQL silme: hiç varlık yüklenmez, tek {@code DELETE} deyimi; başka izlemenin satırına dokunulmaz. Üretim koşulu
 * ({@code NOT_SUPPORTED}): repository işlemi kendisi açar.</p>
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckRepositoryBulkDeleteTest {

    @Autowired DbQueryCheckRepository dbqChecks;
    @Autowired DbQueryAlertRowsRepository dbqRows;
    @Autowired PageSpeedCheckRepository psChecks;
    @Autowired PageSpeedResourceRepository psResources;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void clean() {
        dbqChecks.deleteAllInBatch();
        dbqRows.deleteAllInBatch();
        psChecks.deleteAllInBatch();
        psResources.deleteAllInBatch();
    }

    private Statistics stats() {
        Statistics s = emf.unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    /** Silmeyi istatistik altında koşturur: [silinen, yüklenen varlık, varlık silme, hazırlanan deyim]. */
    private long[] measure(Supplier<Integer> delete) {
        Statistics s = stats();
        s.clear();
        int n = delete.get();
        return new long[]{n, s.getEntityLoadCount(), s.getEntityDeleteCount(), s.getPrepareStatementCount()};
    }

    @Test
    @DisplayName("sorgu izlemesi kontrolleri: 60 satır tek DELETE ile, hiç varlık yüklenmeden silinir; başka izleme kalır")
    void dbQueryChecks() {
        List<DbQueryCheck> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) rows.add(dbq(7L, i));
        for (int i = 0; i < 4; i++) rows.add(dbq(8L, i));
        dbqChecks.saveAll(rows);
        long[] m = measure(() -> dbqChecks.deleteByMonitorId(7L));
        assertThat(m[0]).isEqualTo(60);
        assertThat(m[1]).as("yüklenen varlık").isZero();
        assertThat(m[2]).as("tek tek varlık silme").isZero();
        assertThat(m[3]).as("hazırlanan deyim (tek DELETE)").isEqualTo(1);
        assertThat(dbqChecks.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("sorgu alarm satırları, sayfa hızı ölçümleri ve kaynak kırılımı (LATEST / tümü) da toplu silinir")
    void otherSeriesTables() {
        for (int i = 0; i < 5; i++) {
            DbQueryAlertRows r = new DbQueryAlertRows();
            r.setAlertEventId(100L + i); r.setMonitorId(i < 4 ? 7L : 8L); r.setCapturedAt("2026-11-15T10:00:0" + i);
            dbqRows.save(r);
        }
        long[] a = measure(() -> dbqRows.deleteByMonitorId(7L));
        assertThat(a[0]).isEqualTo(4);
        assertThat(a[1]).isZero();
        assertThat(a[3]).isEqualTo(1);

        List<PageSpeedCheck> checks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            PageSpeedCheck c = new PageSpeedCheck();
            c.setMonitorId(i < 25 ? 7L : 8L); c.setCheckedAt(String.format("2026-11-15T10:%02d:00", i));
            checks.add(c);
        }
        psChecks.saveAll(checks);
        long[] p = measure(() -> psChecks.deleteByMonitorId(7L));
        assertThat(p[0]).isEqualTo(25);
        assertThat(p[1]).isZero();
        assertThat(p[3]).isEqualTo(1);
        assertThat(psChecks.count()).isEqualTo(5);

        List<PageSpeedResource> res = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            PageSpeedResource r = new PageSpeedResource();
            r.setMonitorId(i < 15 ? 7L : 8L);
            r.setCheckedAt("2026-11-15T10:00:00");
            r.setUrl("https://example.com/r" + i);
            r.setKeepReason(i < 10 ? PageSpeedResource.KEEP_LATEST : "BREACH");
            res.add(r);
        }
        psResources.saveAll(res);
        long[] l = measure(() -> psResources.deleteByMonitorIdAndKeepReason(7L, PageSpeedResource.KEEP_LATEST));
        assertThat(l[0]).isEqualTo(10);
        assertThat(l[1]).isZero();
        assertThat(l[3]).isEqualTo(1);
        long[] all = measure(() -> psResources.deleteByMonitorId(7L));
        assertThat(all[0]).isEqualTo(5);
        assertThat(all[1]).isZero();
        assertThat(psResources.count()).isEqualTo(5);
    }

    private static DbQueryCheck dbq(long monitorId, int i) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(String.format("2026-11-15T10:%02d:%02d", i / 60, i % 60));
        c.setOk(true);
        c.setStatus("OK");
        c.setRuleResultsJson("[{\"id\":\"r1\",\"measure\":" + i + "}]");
        return c;
    }
}
