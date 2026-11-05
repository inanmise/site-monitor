package com.sitemonitor.service;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivityLogService} yazımı GERÇEK işlemlerle (H2, aktivite inceleme 2026-11-05):
 * <ul>
 *   <li>D1 — aktivite INSERT'i düşse de çağıranın açık işlemi TAMAMLANIR (eskiden depo proxy'si katılımcı işlemi
 *       rollback-only işaretliyor, dış COMMIT {@code UnexpectedRollbackException} atıyordu — kontrol testi aynı senaryoyu
 *       eski yolla tekrarlar);</li>
 *   <li>açık işlemdeki satır COMMIT SONRASI yazılır; dış işlem geri alınırsa satır yok;</li>
 *   <li>D5 — 300'ü aşan özet kelime sınırında "…" ile sığar, satır kaybolmaz;</li>
 *   <li>O2 — sertifika / erişilebilirlik alarmının onay satırı izleme kimliğini açılış satırından alır (kısmi dizinli tek
 *       okuma, yerel SQL H2'de koşar); erişilebilirlik hedefi {@code alan:port}.</li>
 * </ul>
 * {@code NOT_SUPPORTED}: test işlemi dış işlem davranışını gizlerdi.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityLogServiceTransactionTest {

    @Autowired ActivityLogRepository activityRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired PlatformTransactionManager txManager;

    ActivityLogService svc;
    TransactionTemplate outer;

    @BeforeEach
    void setUp() {
        activityRepo.deleteAll();
        svc = new ActivityLogService(activityRepo);
        svc.setTransactionManager(txManager);
        outer = new TransactionTemplate(txManager);
    }

    @AfterEach
    void tearDown() {
        activityRepo.deleteAll();
        teamRepo.findAll().stream().filter(t -> t.getName() != null && t.getName().startsWith("act-tx-"))
                .forEach(teamRepo::delete);
    }

    private Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return teamRepo.save(t);
    }

    @Test
    @DisplayName("D1: açık işlemde aktivite INSERT'i düşer (monitor_type NOT NULL) → dış işlem yine COMMIT olur, iş verisi kalır")
    void failingInsertNeverRollsBackCaller() {
        outer.executeWithoutResult(st -> {
            team("act-tx-ok");
            // monitor_type null → NOT NULL ihlali (aktivite INSERT'i düşer)
            svc.recordLifecycle(null, 1L, null, null, 5L, "DELETED", "ops");
        });
        assertThat(teamRepo.findAll()).extracting(Team::getName).contains("act-tx-ok");
        assertThat(activityRepo.count()).isZero();
    }

    @Test
    @DisplayName("Kontrol: işlem altyapısı bağlı değilken (eski yol) aynı senaryo dış işlemi UnexpectedRollbackException ile geri aldırır")
    void legacyPathPoisonsCaller() {
        ActivityLogService legacy = new ActivityLogService(activityRepo);   // işlem yöneticisi yok → deponun save'i katılır
        assertThatThrownBy(() -> outer.executeWithoutResult(st -> {
            team("act-tx-legacy");
            legacy.recordLifecycle(null, 1L, null, null, 5L, "DELETED", "ops");
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(teamRepo.findAll()).extracting(Team::getName).doesNotContain("act-tx-legacy");
    }

    @Test
    @DisplayName("Açık işlemde satır COMMIT SONRASI yazılır (COMMIT'ten önce yok); dış işlem geri alınırsa satır HİÇ yazılmaz")
    void deferredToCommit_andDroppedOnRollback() {
        outer.executeWithoutResult(st -> {
            svc.recordLifecycle(ActivityLogService.PORT, 7L, "p", "h:1", 5L, "CREATED", "ops");
            assertThat(activityRepo.count()).as("COMMIT'ten önce").isZero();
        });
        assertThat(activityRepo.count()).as("COMMIT sonrası").isEqualTo(1);

        outer.executeWithoutResult(st -> {
            svc.recordLifecycle(ActivityLogService.PORT, 8L, "p", "h:2", 5L, "DELETED", "ops");
            st.setRollbackOnly();
        });
        assertThat(activityRepo.findAll()).extracting(ActivityLog::getMonitorId).containsExactly(7L);
    }

    @Test
    @DisplayName("İşlem yokken hemen yazılır (bugünkü yol)")
    void noTransaction_writesNow() {
        svc.recordLifecycle(ActivityLogService.HTTP, 3L, "h", "https://x", 5L, "CREATED", "ops");
        assertThat(activityRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("D5: 600 karakterlik özet 300'e kelime sınırında '…' ile sığar — satır gerçek INSERT'te kaybolmaz")
    void longSummaryFitsTheColumn() {
        String summary = ("alan" + " ").repeat(150);   // 750 karakter
        svc.recordLifecycle(ActivityLogService.HTTP, 3L, "h", "https://x", 5L, "CONFIG_CHANGED", "ops", summary);
        List<ActivityLog> rows = activityRepo.findAll();
        assertThat(rows).hasSize(1);
        String saved = rows.get(0).getResultSummary();
        assertThat(saved).hasSizeLessThanOrEqualTo(ActivityLogService.SUMMARY_MAX).endsWith("…");
        assertThat(saved.substring(0, saved.length() - 1)).endsWith("alan");   // kelime ortasında kesilmez
    }

    @Test
    @DisplayName("O2: sertifika alarmının onay satırı izleme kimliğini açılış satırından alır; erişilebilirlik hedefi alan:port")
    void alertMonitorIdFromTriggeredRow() {
        AlertEvent ev = new AlertEvent();
        ev.setId(900L);
        ev.setDomain("x.example.com");
        ev.setAlertType("EXPIRY");
        ev.setAlertLevel("HIGH");
        ev.setTeamId(5L);
        svc.recordAlert(ev, ActivityLogService.ACTION_ALERT_TRIGGERED, null, 42L, "system", null);
        svc.recordAlert(ev, ActivityLogService.ACTION_ALERT_ACKNOWLEDGED, null, null, "ayse", null);
        svc.recordAlert(ev, ActivityLogService.ACTION_ALERT_RESOLVED, null, null, "system", "otomatik");
        assertThat(activityRepo.findAll()).extracting(ActivityLog::getAction, ActivityLog::getMonitorType, ActivityLog::getMonitorId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ALERT_TRIGGERED", "CERT", 42L),
                        org.assertj.core.groups.Tuple.tuple("ALERT_ACKNOWLEDGED", "CERT", 42L),
                        org.assertj.core.groups.Tuple.tuple("ALERT_RESOLVED", "CERT", 42L));

        // Açılış satırı olmayan (sürümden önce açılmış) alarm: kimlik null kalır
        AlertEvent old = new AlertEvent();
        old.setId(901L);
        old.setDomain("old.example.com");
        old.setAlertType("EXPIRY");
        old.setAlertLevel("HIGH");
        svc.recordAlert(old, ActivityLogService.ACTION_ALERT_RESOLVED, null, null, "system", null);
        assertThat(activityRepo.findAll()).filteredOn(a -> Long.valueOf(901L).equals(a.getAlertEventId()))
                .extracting(ActivityLog::getMonitorId).containsExactly((Long) null);

        // Erişilebilirlik: tür UPTIME, hedef kontrol satırlarıyla aynı alan:port
        AlertEvent acc = new AlertEvent();
        acc.setId(902L);
        acc.setDomain("x.example.com");
        acc.setAlertType(EscalationService.TYPE_ACCESSIBILITY);
        acc.setAlertLevel("HIGH");
        acc.setContextJson("{\"port\":8443}");
        svc.recordAlert(acc, ActivityLogService.ACTION_ALERT_TRIGGERED, null, 77L, "system", null);
        svc.recordAlert(acc, ActivityLogService.ACTION_ALERT_RESOLVED, null, null, "system", null);
        assertThat(activityRepo.findAll()).filteredOn(a -> Long.valueOf(902L).equals(a.getAlertEventId()))
                .extracting(ActivityLog::getMonitorType, ActivityLog::getTarget, ActivityLog::getMonitorId)
                .containsOnly(org.assertj.core.groups.Tuple.tuple("UPTIME", "x.example.com:8443", 77L));
    }
}
