package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.schedule.ClusterLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MP-46 (2026-10-23, çok pod R2a · D2) — izleme grubu açılış doldurmasının takımsız izleme adımı GERÇEK H2 üstünde:
 * koşullu UPDATE (okumadan sonra yapılan duraklatma / takım ataması ezilmez), tek pod (kilit), sürümlü nişan (ikinci açılış
 * hiçbir izlemeyi okumaz), yarıda kalan koşu nişan yazmaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitoringGroupBackfillMultiPodTest {

    @Autowired DataSource dataSource;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired KeywordMonitorRepository keywordRepo;
    @Autowired DomainMonitorRepository domainRepo;
    @Autowired PageMonitorRepository pageRepo;
    @Autowired ScriptedMonitorRepository scriptedRepo;
    @Autowired PageSpeedMonitorRepository pageSpeedRepo;

    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(MultiPodTestSupport.SCHEDULER_LOCK_DDL);
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM scheduler_lock");
        jdbc.update("DELETE FROM schema_patch_markers");
        httpRepo.deleteAll();
        inventoryRepo.deleteAll();
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("shop.example.com");
        inv.setPort(443);
        inv.setActive(true);
        inv.setTeamId(5L);
        inventoryRepo.save(inv);
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler (envanter sorguları) temiz başlasın. */
    @AfterEach
    void tearDown() {
        httpRepo.deleteAll();
        pingRepo.deleteAll();
        portRepo.deleteAll();
        dnsRepo.deleteAll();
        keywordRepo.deleteAll();
        domainRepo.deleteAll();
        pageRepo.deleteAll();
        scriptedRepo.deleteAll();
        pageSpeedRepo.deleteAll();
        inventoryRepo.deleteAll();
        jdbc.update("DELETE FROM scheduler_lock");
        jdbc.update("DELETE FROM schema_patch_markers");
    }

    private MonitoringGroupBackfill backfill(JdbcTemplate j, ClusterLockService locks) {
        MonitoringGroupService groups = mock(MonitoringGroupService.class);
        when(groups.getOrCreate(anyLong(), anyString(), anyString(), anyString())).thenAnswer(i -> i.getArgument(2));
        MonitoringGroupBackfill b = new MonitoringGroupBackfill(inventoryRepo, httpRepo, pingRepo, portRepo, dnsRepo, keywordRepo,
                domainRepo, pageRepo, scriptedRepo, pageSpeedRepo, groups);
        b.setJdbc(j);
        b.setLockService(locks);
        return b;
    }

    private ClusterLockService locks(int n) {
        return new ClusterLockService(jdbc, MultiPodTestSupport.owner(n), System::currentTimeMillis, false);
    }

    private Long http(String name, String url, Long team) {
        HttpMonitor m = new HttpMonitor();
        m.setName(name);
        m.setUrl(url);
        m.setTeamId(team);
        m.setActive(true);
        m.setGroupName("Ödeme");
        return httpRepo.save(m).getId();
    }

    /** Takımsız http satırlarını okuduktan HEMEN sonra (yazımdan önce) başka bir yazarın değişikliğini uygulayan şablon. */
    private JdbcTemplate concurrentEditAfterRead(Runnable edit) {
        AtomicBoolean once = new AtomicBoolean(true);
        return new JdbcTemplate(dataSource) {
            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                List<Map<String, Object>> rows = super.queryForList(sql);
                if (sql.contains("FROM http_monitors") && once.compareAndSet(true, false)) edit.run();
                return rows;
            }
        };
    }

    @Test
    @DisplayName("Okumadan sonra yapılan duraklatma ve takım ataması EZİLMEZ; yalnız hâlâ takımsız satıra takım yazılır")
    void concurrentEdit_notOverwritten() {
        Long paused = http("Ödeme", "https://shop.example.com/pay", null);
        Long assigned = http("Sepet", "https://shop.example.com/cart", null);
        JdbcTemplate j = concurrentEditAfterRead(() -> {
            jdbc.update("UPDATE http_monitors SET active = FALSE WHERE id = ?", paused);    // lider duraklattı
            jdbc.update("UPDATE http_monitors SET team_id = 9 WHERE id = ?", assigned);     // kullanıcı takım atadı
        });

        assertThat(backfill(j, locks(1)).fillNullTeamsOnce()).isEqualTo(1);

        HttpMonitor p = httpRepo.findById(paused).orElseThrow();
        assertThat(p.getTeamId()).isEqualTo(5L);
        assertThat(p.getActive()).as("bütün varlık kaydı duraklatmayı geri alırdı").isFalse();
        assertThat(httpRepo.findById(assigned).orElseThrow().getTeamId()).as("kullanıcının ataması ezilmez").isEqualTo(9L);
    }

    @Test
    @DisplayName("Nişan: ikinci açılış hiçbir izlemeyi okumaz; ilkinden sonra eklenen takımsız satıra dokunulmaz")
    void markerStopsSecondRun() {
        http("A", "https://shop.example.com/a", null);
        MonitoringGroupBackfill b = backfill(jdbc, locks(1));
        assertThat(b.fillNullTeamsOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                MonitoringGroupBackfill.MARKER)).isEqualTo(1);

        Long later = http("B", "https://shop.example.com/b", null);
        assertThat(backfill(jdbc, locks(2)).fillNullTeamsOnce()).isEqualTo(-1);
        assertThat(httpRepo.findById(later).orElseThrow().getTeamId()).isNull();
    }

    @Test
    @DisplayName("Kilit başka pod'dayken atlar (nişan yazmaz); kilit boşalınca koşar")
    void lockHeldElsewhere_skips() {
        Long id = http("A", "https://shop.example.com/a", null);
        ClusterLockService other = locks(1);
        assertThat(other.tryAcquire(MonitoringGroupBackfill.LOCK_NAME)).isPresent();
        assertThat(backfill(jdbc, locks(2)).fillNullTeamsOnce()).isEqualTo(-1);
        assertThat(httpRepo.findById(id).orElseThrow().getTeamId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers", Integer.class)).isZero();

        other.release(MonitoringGroupBackfill.LOCK_NAME);
        assertThat(backfill(jdbc, locks(2)).fillNullTeamsOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("Yarıda kalan koşu nişan YAZMAZ; sonraki açılış tamamlar (koşullu yazım tekrar güvenli)")
    void failureLeavesNoMarker() {
        http("A", "https://shop.example.com/a", null);
        JdbcTemplate failing = new JdbcTemplate(dataSource) {
            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                if (sql.contains("FROM ping_monitors")) throw new IllegalStateException("tablo okunamadı");
                return super.queryForList(sql);
            }
        };
        assertThat(backfill(failing, locks(1)).fillNullTeamsOnce()).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_lock", Integer.class)).as("kilit bırakıldı").isZero();

        assertThat(backfill(jdbc, locks(1)).fillNullTeamsOnce()).as("ilk satır zaten dolu, yeniden yazılmaz").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers", Integer.class)).isEqualTo(1);
    }
}
