package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.service.baseline.BaselineConfigService;
import com.sitemonitor.service.baseline.BaselineSampleStore;
import com.sitemonitor.service.baseline.BaselineService;
import com.sitemonitor.service.baseline.BaselineTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gerileme taraması 2026-11-13 (bulgu 3): kalıcı silmedeki taban çizgisi temizliği silme işlemini BOZAMAZ. Eskiden
 * {@code BaselineConfigService.remove} (REQUIRED) dış işleme katılıyordu; örnek silmesi istisna atınca proxy dış işlemi
 * rollback-only işaretliyor, {@code PermanentDeletionService} catch'te WARN yazıp devam etse de commit
 * {@code UnexpectedRollbackException} ile düşüyordu (envanter / bağımsız Port-DNS silmesi 500). Artık temizlik commit
 * SONRASI kendi işleminde koşar. GERÇEK işlem yöneticisi + gerçek {@link BaselineConfigService} vekili (H2), testler
 * {@code NOT_SUPPORTED} — servisin kendi işlemi üretimdeki gibi commit olur.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:permdeletebaseline;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,DAY",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import({PermanentDeletionService.class, BaselineConfigService.class})
class PermanentDeletionBaselineTxTest {

    private static final String NOW = "2026-11-13T10:00:00";

    @Autowired PermanentDeletionService service;
    @Autowired PortMonitorRepository portRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired MonitorBaselineConfigRepository configRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean EscalationService escalationService;
    @MockitoBean BaselineSampleStore store;
    @MockitoBean BaselineService baselineService;

    /** Ham DDL özet tabloları (SchedulerService.applySchemaPatches) — entity'leri yok, ddl-auto kurmaz. */
    @BeforeEach
    void rawTables() {
        for (String t : List.of("monitor_check_daily", "monitor_check_hourly")) {
            String bucket = t.endsWith("daily") ? "day VARCHAR(10)" : "hour_bucket VARCHAR(13)";
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + t + " (monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                    + bucket + " NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, avg_response_ms INTEGER, "
                    + "max_response_ms INTEGER)");
        }
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of("monitor_baseline_config", "port_checks", "port_monitors", "certificate_inventory")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    private PortMonitor port(String host, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setName(host); m.setHost(host); m.setPort(443); m.setTeamId(5L); m.setStandalone(standalone); m.setActive(true);
        m.setCreatedAt(NOW); m.setUpdatedAt(NOW);
        return portRepo.save(m);
    }

    private void baselineConfig(Long monitorId) {
        MonitorBaselineConfig c = new MonitorBaselineConfig();
        c.setMonitorType(BaselineTypes.PORT); c.setMonitorId(monitorId); c.setEnabled(true);
        c.setCreatedAt(NOW); c.setUpdatedAt(NOW);
        configRepo.save(c);
    }

    private int configRows(Long monitorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM monitor_baseline_config WHERE monitor_type = ? AND monitor_id = ?",
                Integer.class, BaselineTypes.PORT, monitorId);
        return n == null ? 0 : n;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("bağımsız Port silmesi: örnek silmesi istisna atsa da silme COMMIT olur (500 / UnexpectedRollback yok); "
            + "taban yapılandırması da kendi işleminde silinir")
    void standaloneDelete_sampleCleanupFails_deletionStillCommits() {
        PortMonitor m = port("solo.example.com", true);
        baselineConfig(m.getId());
        when(store.deleteForMonitor(anyString(), anyLong())).thenThrow(new IllegalStateException("sorgu zaman aşımı"));

        assertThatCode(() -> service.deleteStandalonePort(m)).doesNotThrowAnyException();

        assertThat(portRepo.findById(m.getId())).as("silme commit oldu").isEmpty();
        assertThat(configRows(m.getId())).as("yapılandırma kendi işleminde silindi").isZero();
        verify(store).deleteForMonitor(BaselineTypes.PORT, m.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("envanter silmesi (türev Port): taban temizliği istisna atsa da envanter ve türev izleme silinir")
    void inventoryDelete_sampleCleanupFails_deletionStillCommits() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("gone.example.com"); inv.setPort(443); inv.setTeamId(5L); inv.setActive(true);
        inv.setCreatedAt(NOW); inv.setUpdatedAt(NOW);
        CertificateInventory saved = inventoryRepo.save(inv);
        PortMonitor derived = port("gone.example.com", false);
        baselineConfig(derived.getId());
        when(store.deleteForMonitor(anyString(), anyLong())).thenThrow(new IllegalStateException("bağlantı koptu"));

        assertThatCode(() -> service.deleteInventory(saved)).doesNotThrowAnyException();

        assertThat(inventoryRepo.findById(saved.getId())).isEmpty();
        assertThat(portRepo.findById(derived.getId())).isEmpty();
        assertThat(configRows(derived.getId())).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("silme geri alınırsa taban çizgisine DOKUNULMAZ (izleme hâlâ var) — temizlik yalnız commit sonrası koşar")
    void rolledBackDeletion_keepsBaseline() {
        PortMonitor m = port("kept.example.com", true);
        baselineConfig(m.getId());

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            service.deleteStandalonePort(m);
            status.setRollbackOnly();
        });

        assertThat(portRepo.findById(m.getId())).isPresent();
        assertThat(configRows(m.getId())).isEqualTo(1);
        verify(store, never()).deleteForMonitor(anyString(), anyLong());
    }
}
