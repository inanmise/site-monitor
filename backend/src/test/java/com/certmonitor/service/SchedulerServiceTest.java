package com.certmonitor.service;

import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DnsRecordRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.NetworkOutageEventRepository;
import com.certmonitor.repository.PortCheckRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.UptimeCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * Baseline coverage for SchedulerService. The service has a 16-dependency
 * constructor and lots of side-effects, so the full happy-path is exercised by
 * the live application; here we cover the simple, hot-path getters and the
 * forceReleaseLock SQL contract.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock CertificateCheckerService checkerService;
    @Mock CertificateService certService;
    @Mock EmailNotificationService emailService;
    @Mock EscalationService escalationService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserService userService;
    @Mock DataSource dataSource;
    @Mock PortCheckerService portCheckerService;
    @Mock PortMonitorRepository portMonitorRepo;
    @Mock PortCheckRepository portCheckRepo;
    @Mock DnsCheckerService dnsCheckerService;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock UptimeHttpCheckerService uptimeHttpCheckerService;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock NetworkOutageEventRepository networkOutageRepo;
    @Mock ThreadPoolTaskExecutor certCheckExecutor;

    SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SchedulerService(
                checkerService, certService, emailService, escalationService,
                inventoryRepo, latestCheckRepo, thresholdRepo, jdbcTemplate,
                userService, dataSource,
                portCheckerService, portMonitorRepo, portCheckRepo,
                dnsCheckerService, dnsMonitorRepo, dnsRecordRepo,
                uptimeHttpCheckerService, uptimeCheckRepo, networkOutageRepo);
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", certCheckExecutor);
        lenient().when(inventoryRepo.countByActiveTrue()).thenReturn(0L);
    }

    @Test
    @DisplayName("isRunning() starts false on a fresh instance")
    void isRunning_initiallyFalse() {
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Network outage state starts inactive on a fresh instance")
    void networkOutage_initiallyInactive() {
        assertThat(scheduler.isNetworkOutageActive()).isFalse();
        assertThat(scheduler.getNetworkOutageDetectedAt()).isNull();
        assertThat(scheduler.getNetworkOutageResolvedAt()).isNull();
        assertThat(scheduler.getNetworkLastTotal()).isZero();
        assertThat(scheduler.getNetworkLastNetworkErrors()).isZero();
    }

    @Test
    @DisplayName("getStatus() returns the expected status map shape")
    void getStatus_returnsMapWithBootstrappedKeys() {
        when(inventoryRepo.countByActiveTrue()).thenReturn(42L);

        Map<String, Object> status = scheduler.getStatus();

        assertThat(status)
                .containsEntry("active_domains", 42L)
                .containsEntry("running", false)
                .containsEntry("last_run", "Not yet run")
                .containsKey("instance_id")
                .containsKey("current_run_id")
                .containsKey("last_run_id");
        assertThat((String) status.get("instance_id")).isNotBlank();
    }

    @Test
    @DisplayName("forceReleaseLock() deletes the cert-check row via jdbcTemplate")
    void forceReleaseLock_executesDeleteSql() {
        scheduler.forceReleaseLock();

        verify(jdbcTemplate).update(contains("DELETE FROM scheduler_lock"), any(Object[].class));
    }

    @Test
    @DisplayName("getShutdownSnapshot() returns running/instance/last_run keys")
    void getShutdownSnapshot_keysPresent() {
        Map<String, Object> snap = scheduler.getShutdownSnapshot();

        assertThat(snap)
                .containsKey("running")
                .containsKey("instance")
                .containsKey("last_run");
        assertThat(snap.get("running")).isEqualTo(false);
    }
}
