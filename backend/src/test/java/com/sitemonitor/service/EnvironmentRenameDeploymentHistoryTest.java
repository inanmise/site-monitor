package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.repository.AppSettingRepository;
import com.sitemonitor.repository.DeploymentHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ortam adı Genel Ayarlar'dan değişince "Devreye alma" satırı boş kalmasın (2026-09-29): koşan örneğin dağıtım kaydı
 * yeni ada TAŞINIR. Taşıma, ayar kaydının commit'inden SONRA koşan bir {@code @TransactionalEventListener} içinde
 * {@code REQUIRES_NEW} ile yazılır — bu yol ancak üretimdeki gibi İŞLEMSİZ bir testte kanıtlanır
 * ({@code NOT_SUPPORTED}; {@code @DataJpaTest}'in test-işlemi AFTER_COMMIT'i hiç tetiklemez ve eksik
 * işlem beyanını gizler — bkz. RepositoryWriteTransactionGuardTest gerekçesi).
 */
@DataJpaTest
@Import({AppSettingsService.class, BuildInfo.class, DeploymentHistoryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.environment=staging",
        "site.monitor.version=20.91.0",
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class EnvironmentRenameDeploymentHistoryTest {

    @Autowired AppSettingsService settings;
    @Autowired DeploymentHistoryService deployments;
    @Autowired DeploymentHistoryRepository historyRepo;
    @Autowired AppSettingRepository settingRepo;
    @MockitoBean ReleaseIndexService releaseIndex;

    @BeforeEach
    void clean() {
        historyRepo.deleteAll();
        settingRepo.deleteAll();
        settings.refreshFromDb();
    }

    @AfterEach
    void cleanAfter() { clean(); }

    private static Map<String, Object> env(String v) {
        return Map.of("values", Map.of(BuildInfo.ENV_KEY, v));
    }

    @Test
    @DisplayName("kaydet (staging → prod): commit sonrası koşan kayıt prod'a taşınır, not iz taşır, current(prod) dolu")
    void save_relabelsRunningRecord_afterCommit() {
        deployments.recordStartup();
        Long id = deployments.currentId();
        assertThat(id).isNotNull();
        assertThat(historyRepo.findById(id)).get().extracting(DeploymentHistory::getEnvironment).isEqualTo("staging");

        settings.save(env("prod"), "admin");

        DeploymentHistory row = historyRepo.findById(id).orElseThrow();
        assertThat(row.getEnvironment()).isEqualTo("prod");
        assertThat(row.getNote()).startsWith("Ortam adı değiştirildi: staging → prod (");
        assertThat(historyRepo.count()).as("yeni satır YOK — BOUNDED tablo her adlandırmada büyümez").isEqualTo(1);
        assertThat(deployments.current("prod").version()).isEqualTo("20.91.0");
        assertThat(deployments.current("staging").version()).isNull();
        assertThat(deployments.environments()).contains("prod");
    }

    @Test
    @DisplayName("boşaltınca (prod → staging) aynı kayıt geri taşınır; iki iz de notta, tek satır")
    void clear_movesBack() {
        deployments.recordStartup();
        Long id = deployments.currentId();
        settings.save(env("prod"), "admin");
        settings.save(env(""), "admin");
        DeploymentHistory row = historyRepo.findById(id).orElseThrow();
        assertThat(row.getEnvironment()).isEqualTo("staging");
        assertThat(row.getNote()).contains("staging → prod").contains("prod → staging");
        assertThat(historyRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("kapanmış geçmiş kaydına dokunulmaz (yalnız koşan satır taşınır)")
    void endedRows_untouched() {
        DeploymentHistory old = new DeploymentHistory();
        old.setStartedAt("2026-09-01T08:00:00Z"); old.setRecordedAt("2026-09-01T08:00:00Z");
        old.setEnvironment("staging"); old.setVersion("20.90.0"); old.setSource(DeploymentHistory.SOURCE_STARTUP);
        old.setEndedAt("2026-09-02T08:00:00Z"); old.setEndReason("graceful");
        historyRepo.save(old);
        deployments.recordStartup();

        settings.save(env("prod"), "admin");

        List<DeploymentHistory> staging = historyRepo.findByEnvironmentOrderByStartedAtAscIdAsc("staging");
        assertThat(staging).extracting(DeploymentHistory::getVersion).containsExactly("20.90.0");
        assertThat(staging.get(0).getNote()).isNull();
        assertThat(historyRepo.findByEnvironmentOrderByStartedAtAscIdAsc("prod")).hasSize(1);
    }
}
