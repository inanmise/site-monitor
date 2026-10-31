package com.sitemonitor.repository;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryAlertRows;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.DbQueryTemplate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Veritabanı sorgu izlemesi API katmanının kullandığı JPQL / türetilmiş sorgular (H2) — yanlış alan adı derlenir ama ilk
 * çağrıda patlar; burada her biri en az bir kez gerçekten çalışır. Silme yolları {@code NOT_SUPPORTED} ile koşar (kendi
 * işlemini açmayan türetilmiş silme üretimde TransactionRequiredException verirdi; {@code @DataJpaTest}'in işlemi bunu gizler).
 * Native (PostgreSQL) sorgular — {@code findLatestPerMonitor} (LATERAL), {@code historyHistogram} — bilinçli DIŞARIDA.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DbQueryApiRepositoriesTest {

    @Autowired DbQueryMonitorRepository monitors;
    @Autowired DbQueryCheckRepository checks;
    @Autowired DbConnectionProfileRepository connections;
    @Autowired DbQueryTemplateRepository templates;
    @Autowired DbQueryAlertRowsRepository alertRows;
    @Autowired EntityManager em;

    private DbConnectionProfile conn(long team, String name) {
        DbConnectionProfile p = new DbConnectionProfile();
        p.setTeamId(team); p.setName(name); p.setDbType("POSTGRESQL");
        p.setHost("db.internal"); p.setPort(5432); p.setDatabaseName("orders"); p.setUsername("ro");
        return connections.save(p);
    }

    private DbQueryMonitor mon(long team, String name, Long profile) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setTeamId(team); m.setName(name); m.setActive(true); m.setProfileId(profile);
        m.setSqlText("SELECT 1");
        return monitors.save(m);
    }

    private DbQueryTemplate tpl(long team, String name) {
        DbQueryTemplate t = new DbQueryTemplate();
        t.setTeamId(team); t.setName(name); t.setSqlText("SELECT 1");
        return templates.save(t);
    }

    private DbQueryCheck check(long monitorId, String at, boolean ok, String status, int ms, int rows) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId); c.setCheckedAt(at); c.setOk(ok); c.setStatus(status);
        c.setDurationMs(ms); c.setRowCount(rows); c.setMetricValue((double) rows);
        return checks.save(c);
    }

    @Test
    @DisplayName("izleme: ad takımda tekil (büyük-küçük harf duyarsız, kendisi hariç); bağlantıyı kullananlar")
    void monitorDuplicateAndUsers() {
        DbConnectionProfile p = conn(1L, "Sipariş DB");
        DbQueryMonitor a = mon(1L, "Bekleyen Siparişler", p.getId());
        mon(2L, "Bekleyen Siparişler", null);
        assertThat(monitors.existsDuplicate("bekleyen siparişler", 1L, null)).isTrue();
        assertThat(monitors.existsDuplicate("Bekleyen Siparişler", 1L, a.getId())).isFalse();
        assertThat(monitors.existsDuplicate("Bekleyen Siparişler", 3L, null)).isFalse();
        assertThat(monitors.findByProfileId(p.getId())).extracting(DbQueryMonitor::getName).containsExactly("Bekleyen Siparişler");
        assertThat(monitors.findAllByOrderByNameAsc()).hasSize(2);
    }

    @Test
    @DisplayName("bağlantı + şablon: ad tekilliği, sayım, takım taşıma (moveTeam)")
    void connectionAndTemplateQueries() {
        DbConnectionProfile p = conn(1L, "Sipariş DB");
        conn(1L, "Ödeme DB");
        assertThat(connections.existsDuplicate("sipariş db", 1L, null)).isTrue();
        assertThat(connections.existsDuplicate("Sipariş DB", 1L, p.getId())).isFalse();
        assertThat(connections.countByTeamId(1L)).isEqualTo(2);
        assertThat(connections.findByTeamIdInOrderByNameAsc(List.of(1L, 9L))).extracting(DbConnectionProfile::getName)
                .containsExactly("Sipariş DB", "Ödeme DB");

        tpl(1L, "Bekleyen siparişler");
        assertThat(templates.existsDuplicate("BEKLEYEN siparişler", 1L, null)).isTrue();
        assertThat(templates.countByTeamId(1L)).isEqualTo(1);

        assertThat(connections.moveTeam(1L, 5L)).isEqualTo(2);
        assertThat(templates.moveTeam(1L, 5L)).isEqualTo(1);
        em.clear();
        assertThat(connections.findByTeamIdOrderByNameAsc(5L)).hasSize(2);
        assertThat(templates.findByTeamIdOrderByNameAsc(5L)).hasSize(1);
        assertThat(connections.countByTeamId(1L)).isZero();
    }

    @Test
    @DisplayName("kontrol geçmişi: 'sorunlu' = status <> OK (ihlal + hata); 'hata' = ok false; seri en yeni önce, sınırlı")
    void checkHistoryQueries() {
        long id = mon(1L, "Seri", null).getId();
        check(id, "2026-10-30T10:00:00", true, "OK", 10, 1);
        check(id, "2026-10-30T11:00:00", true, "BREACH", 20, 50);
        check(id, "2026-10-30T12:00:00", false, "FAIL", 30, 0);
        String from = "2026-10-30T00:00:00", to = "2026-10-30T23:59:59";
        var page = PageRequest.of(0, 10);
        assertThat(checks.findByMonitorIdAndCheckedAtBetween(id, from, to, page).getTotalElements()).isEqualTo(3);
        assertThat(checks.findByMonitorIdAndStatusNotAndCheckedAtBetween(id, "OK", from, to, page).getContent())
                .extracting(DbQueryCheck::getStatus).containsExactlyInAnyOrder("BREACH", "FAIL");
        assertThat(checks.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, from, to, page).getContent())
                .extracting(DbQueryCheck::getStatus).containsExactly("FAIL");
        assertThat(checks.countByMonitorIdAndStatusNotAndCheckedAtBetween(id, "OK", from, to)).isEqualTo(2);
        assertThat(checks.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, from, to)).isEqualTo(1);
        assertThat(checks.findTopByMonitorIdOrderByCheckedAtDesc(id)).get().extracting(DbQueryCheck::getStatus).isEqualTo("FAIL");

        List<Object[]> series = checks.seriesRaw(id, from, to, 2);
        assertThat(series).hasSize(2);
        assertThat(series.get(0)[0]).isEqualTo("2026-10-30T12:00:00");
        assertThat(series.get(0)).hasSize(6);
        assertThat(series.get(0)[5]).isEqualTo("FAIL");
        assertThat(checks.historyBounds(id).get(0)).containsExactly("2026-10-30T10:00:00", "2026-10-30T12:00:00");
    }

    @Autowired HolidayDayRepository holidays;

    @Test
    @DisplayName("tatil takvimi deposu: kolon holiday_date (\"day\" H2'de ayrılmış sözcük) — kayıt + gün sırasıyla okuma")
    void holidayRepository() {
        for (String[] d : new String[][] {{"2026-10-29", "Cumhuriyet Bayramı"}, {"2026-01-01", "Yılbaşı"}}) {
            com.sitemonitor.model.HolidayDay h = new com.sitemonitor.model.HolidayDay();
            h.setDay(d[0]); h.setName(d[1]);
            holidays.save(h);
        }
        assertThat(holidays.findAllByOrderByDayAsc()).extracting(com.sitemonitor.model.HolidayDay::getDay)
                .containsExactly("2026-01-01", "2026-10-29");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("silme (işlemsiz çağrı): izlemenin kontrolleri ve alarm anı satırları kendi işlemiyle silinir")
    void deleteByMonitorIdRunsWithoutCallerTransaction() {
        long id = 900_001L;
        check(id, "2026-10-30T10:00:00", true, "OK", 10, 1);
        DbQueryAlertRows r = new DbQueryAlertRows();
        r.setAlertEventId(77L); r.setMonitorId(id); r.setCapturedAt("2026-10-30T10:00:00");
        alertRows.save(r);
        try {
            assertThat(checks.deleteByMonitorId(id)).isEqualTo(1);
            assertThat(alertRows.deleteByMonitorId(id)).isEqualTo(1);
            assertThat(alertRows.findByAlertEventId(77L)).isEmpty();
        } finally {
            checks.deleteByMonitorId(id);
            alertRows.deleteByMonitorId(id);
        }
    }
}
