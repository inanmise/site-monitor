package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sistem Bakım Modu sorguları (2026-10-02) — gerçek SQL H2 üstünde, test transaction'ı KAPALI ({@code NOT_SUPPORTED}):
 * her yazan sorgu kendi {@code @Transactional}'ını taşımalı. En kritik sözleşme: yöneticinin bayat varlık kaydı
 * ({@code save}) atomik SAYAÇLARI ve yan iş DAMGALARINI ezemez ({@code updatable = false}).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SystemMaintenanceQueriesTest {

    @Autowired SystemMaintenanceWindowRepository repo;
    @Autowired SystemMaintenanceSuppressionRepository suppressionRepo;
    @Autowired AlertEventRepository alertEventRepo;

    @AfterEach
    void clean() {
        suppressionRepo.deleteAll();
        repo.deleteAll();
        alertEventRepo.deleteAll();
    }

    private SystemMaintenanceWindow window(String start, String end) {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setStartAt(start);
        w.setEndAt(end);
        w.setRevision(1);
        w.setSessionsEnded(0);
        w.setLoginsBlocked(0);
        w.setNotificationsSuppressed(0);
        w.setJobsDone(false);
        return repo.save(w);
    }

    @Test
    @DisplayName("atomik sayaçlar artar; yöneticinin BAYAT varlık kaydı sayaçları ve iş damgalarını EZMEZ")
    void counters_survive_staleEntitySave() {
        SystemMaintenanceWindow stale = window("2026-10-02T10:00:00", "2026-10-02T11:00:00");
        Long id = stale.getId();
        assertThat(repo.incrementSessionsEnded(id)).isEqualTo(1);
        repo.incrementSessionsEnded(id);
        repo.incrementLoginsBlocked(id);
        repo.incrementNotificationsSuppressed(id);
        assertThat(repo.claimStartLog(id, "2026-10-02T10:00:05")).isEqualTo(1);

        stale.setEndAt("2026-10-02T11:30:00");   // "Uzat" — bayat kopya (sayaçlar 0, damga null)
        repo.save(stale);

        SystemMaintenanceWindow fresh = repo.findById(id).orElseThrow();
        assertThat(fresh.getEndAt()).isEqualTo("2026-10-02T11:30:00");
        assertThat(fresh.getSessionsEnded()).isEqualTo(2);
        assertThat(fresh.getLoginsBlocked()).isEqualTo(1);
        assertThat(fresh.getNotificationsSuppressed()).isEqualTo(1);
        assertThat(fresh.getStartLoggedAt()).isEqualTo("2026-10-02T10:00:05");
    }

    @Test
    @DisplayName("sahiplenmeler tam bir kez: duyuru, düzeltme (sürüm), başlangıç/bitiş denetimi, telafi; iş tamam bayrağı")
    void claims_once() {
        Long id = window("2026-10-02T10:00:00", "2026-10-02T11:00:00").getId();
        assertThat(repo.claimAnnounceMail(id, "t1", 1)).isEqualTo(1);
        assertThat(repo.claimAnnounceMail(id, "t2", 1)).isEqualTo(0);
        assertThat(repo.claimCorrectionMail(id, "t3", 1)).as("aynı sürüm → düzeltme yok").isEqualTo(0);
        assertThat(repo.claimCorrectionMail(id, "t3", 2)).isEqualTo(1);
        assertThat(repo.claimCorrectionMail(id, "t4", 2)).isEqualTo(0);
        assertThat(repo.claimEndLog(id, "t5")).isEqualTo(1);
        assertThat(repo.claimEndLog(id, "t6")).isEqualTo(0);
        assertThat(repo.claimCatchUp(id, "t7")).isEqualTo(1);
        assertThat(repo.claimCatchUp(id, "t8")).isEqualTo(0);
        repo.recordAnnounceMail(id, "SENT ×3", 3);
        repo.recordCaughtUp(id, 4);
        repo.incrementCorrectionMails(id);

        assertThat(repo.existsPendingJobs()).isTrue();
        assertThat(repo.findPendingJobs()).extracting(SystemMaintenanceWindow::getId).containsExactly(id);
        repo.markJobsDone(id);
        assertThat(repo.existsPendingJobs()).isFalse();

        SystemMaintenanceWindow w = repo.findById(id).orElseThrow();
        assertThat(w.getMailedRevision()).isEqualTo(2);
        assertThat(w.getAnnounceMailStatus()).isEqualTo("SENT ×3");
        assertThat(w.getAnnounceMailCount()).isEqualTo(3);
        assertThat(w.getCaughtUpCount()).isEqualTo(4);
        assertThat(w.getCorrectionMailCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("bitiş e-postası (2026-10-02): sahiplenme tam bir kez; durum/sayı kaydı; bayat varlık kaydı damgaları EZMEZ, "
            + "email_on_end varlıkla yazılır")
    void endMail_claimOnce_survivesStaleSave() {
        SystemMaintenanceWindow stale = window("2026-10-02T10:00:00", "2026-10-02T11:00:00");
        Long id = stale.getId();
        assertThat(repo.claimEndMail(id, "2026-10-02T11:00:20")).isEqualTo(1);
        assertThat(repo.claimEndMail(id, "2026-10-02T11:00:50")).as("ikinci pod / ikinci tur").isEqualTo(0);
        assertThat(repo.recordEndMail(id, "SENT ×12", 12)).isEqualTo(1);

        stale.setEmailOnEnd(false);   // yöneticinin bayat kopyası (damga null, sayı null)
        repo.save(stale);

        SystemMaintenanceWindow fresh = repo.findById(id).orElseThrow();
        assertThat(fresh.getEmailOnEnd()).isFalse();
        assertThat(fresh.getEndMailAt()).isEqualTo("2026-10-02T11:00:20");
        assertThat(fresh.getEndMailStatus()).isEqualTo("SENT ×12");
        assertThat(fresh.getEndMailCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("canlı pencereler: iptal edilmemiş ve bitmemiş, başlangıca göre")
    void liveWindows() {
        Long a = window("2026-10-02T12:00:00", "2026-10-02T13:00:00").getId();
        Long b = window("2026-10-02T09:00:00", "2026-10-02T11:00:00").getId();
        window("2026-10-01T09:00:00", "2026-10-01T10:00:00");   // bitmiş
        SystemMaintenanceWindow c = window("2026-10-02T14:00:00", "2026-10-02T15:00:00");
        c.setCancelledAt("2026-10-02T09:00:00");
        repo.save(c);
        assertThat(repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc("2026-10-02T10:00:00"))
                .extracting(SystemMaintenanceWindow::getId).containsExactly(b, a);
    }

    @Test
    @DisplayName("susturma satırı (bakım × alarm) TEK: touch sayar + açılış bayrağını yükseltir; ikinci satır UNIQUE ihlali")
    void suppression_uniqueAndTouch() {
        Long wid = window("2026-10-02T10:00:00", "2026-10-02T11:00:00").getId();
        SystemMaintenanceSuppression s = new SystemMaintenanceSuppression();
        s.setWindowId(wid);
        s.setAlertEventId(77L);
        s.setFirstTrigger("DAILY_REALERT");
        s.setOpening(false);
        s.setSuppressedCount(1);
        s.setFirstAt("2026-10-02T10:01:00");
        suppressionRepo.save(s);

        assertThat(suppressionRepo.touch(wid, 77L, "2026-10-02T10:02:00", true)).isEqualTo(1);
        assertThat(suppressionRepo.touch(wid, 78L, "2026-10-02T10:02:00", true)).isEqualTo(0);
        SystemMaintenanceSuppression after = suppressionRepo.findByWindowIdAndAlertEventId(wid, 77L).orElseThrow();
        assertThat(after.getSuppressedCount()).isEqualTo(2);
        assertThat(after.getOpening()).isTrue();

        SystemMaintenanceSuppression dup = new SystemMaintenanceSuppression();
        dup.setWindowId(wid);
        dup.setAlertEventId(77L);
        assertThatThrownBy(() -> suppressionRepo.save(dup)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(suppressionRepo.markOutcome(wid, List.of(77L), SystemMaintenanceSuppression.OUTCOME_CAUGHT_UP, "t")).isEqualTo(1);
        assertThat(suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(wid)).isEmpty();
        assertThat(suppressionRepo.findByWindowId(wid)).hasSize(1);
    }

    private AlertEvent event(boolean resolved, boolean acked, String lastReAlertAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain("x.example.com");
        e.setAlertLevel("CRITICAL");
        e.setAlertType("HTTP_DOWN");
        e.setResolved(resolved);
        e.setAcknowledged(acked);
        e.setCreatedAt("2026-10-02T10:05:00");
        e.setLastReAlertAt(lastReAlertAt);
        return alertEventRepo.save(e);
    }

    @Test
    @DisplayName("telafi damgası: yalnız AÇIK + ONAYSIZ + damgası bakım bitişinden önce olan alarm sıfırlanır")
    void clearInitialStampForCatchUp() {
        Long open = event(false, false, "2026-10-02T10:05:00").getId();
        Long closed = event(true, false, "2026-10-02T10:05:00").getId();
        Long acked = event(false, true, "2026-10-02T10:05:00").getId();
        Long later = event(false, false, "2026-10-02T11:30:00").getId();   // bakımdan SONRA gerçek bildirim (ör. eskalasyon)
        int n = alertEventRepo.clearInitialStampForCatchUp(List.of(open, closed, acked, later), "2026-10-02T11:00:00");
        assertThat(n).isEqualTo(1);
        assertThat(alertEventRepo.findById(open).orElseThrow().getLastReAlertAt()).isNull();
        assertThat(alertEventRepo.findById(closed).orElseThrow().getLastReAlertAt()).isNotNull();
        assertThat(alertEventRepo.findById(acked).orElseThrow().getLastReAlertAt()).isNotNull();
        assertThat(alertEventRepo.findById(later).orElseThrow().getLastReAlertAt()).isEqualTo("2026-10-02T11:30:00");
    }
}
