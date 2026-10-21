package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.WeeklyAvailabilityLog;
import com.sitemonitor.model.WeeklyReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Haftalık rapor / haftalık erişilebilirlik SAHİPLENMELERİ (2026-10-21, çok pod MP-39 / MP-40) GERÇEK PostgreSQL'de:
 * READ COMMITTED altında kilidi bekleyen koşullu UPDATE, kazanan kesinleşince WHERE'i YENİDEN değerlendirir ve 0 satır
 * döner (hata değil) — kaybeden posta göndermez; null parametre bağlama ({@code score}), {@code COALESCE} CAS'ı ve
 * {@code uk_wal_team_week} benzersizliği.
 */
@PostgresIntegration
class WeeklyClaimsPostgresTest {

    private static final String AT = "2026-10-21T08:00:00";
    private static final String CONTENT = "{\"version\":1}";

    @Test
    @DisplayName("haftalık rapor: kilidi bekleyen ikinci onay kazanandan sonra 0 satır alır; null skor; bayat içerik kaydı 0")
    void weeklyReportClaims() throws Exception {
        WeeklyReportRepository reports = PostgresIt.app().bean(WeeklyReportRepository.class);
        TransactionTemplate tx = new TransactionTemplate(PostgresIt.app().bean(PlatformTransactionManager.class));
        var jdbc = PostgresIt.app().jdbc();
        long team = 912_000L + (long) (Math.random() * 10_000);
        WeeklyReport r = new WeeklyReport();
        r.setTeamId(team);
        r.setReportYear(2026);
        r.setWeekNo(43);
        r.setWeekLabel("2026-W43");
        r.setStatus("DRAFT");
        r.setContentJson(CONTENT);
        Long id = reports.saveAndFlush(r).getId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(reports.claimSubmit(id, "Ekip", AT, "TOK", "2026-10-28T08:00:00", null)).isEqualTo(1);
            assertThat(reports.claimSubmit(id, "Ekip", AT, "TOK2", "2026-10-28T08:00:00", null)).isZero();
            WeeklyReport db = reports.findById(id).orElseThrow();
            assertThat(db.getStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(db.getApprovalToken()).isEqualTo("TOK");
            assertThat(db.getScore()).isNull();
            assertThat(db.getVersion()).isEqualTo(1);
            // bayat içerik kaydı (sürüm 0) onaya gönderilmiş raporu geri çeviremez
            assertThat(reports.updateContent(id, 0, CONTENT, "DRAFT", "Bayat", AT)).isZero();

            // Pod A satırı kilitleyip onaylar ve 500 ms tutar; pod B'nin koşullu onayı bekler, sonra 0 alır (hata yok).
            CountDownLatch locked = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> tx.execute(st -> {
                jdbc.queryForList("SELECT id FROM weekly_reports WHERE id = ? FOR UPDATE", id);
                jdbc.update("UPDATE weekly_reports SET status = 'APPROVED', version = version + 1, approved_at = ? WHERE id = ?",
                        AT, id);
                locked.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            int n = reports.claimApprove(id, "Pod B", AT);
            assertThat((System.nanoTime() - t0) / 1_000_000L).as("kilidi bekledi").isGreaterThanOrEqualTo(300L);
            holder.get(10, TimeUnit.SECONDS);
            assertThat(n).as("kazanan kesinleşince WHERE yeniden değerlendirildi").isZero();
            assertThat(reports.findById(id).orElseThrow().getVersion()).isEqualTo(2);

            assertThat(reports.markSent(id, "1999-01-01T00:00:00", AT)).isZero();   // başka onay döngüsü
            assertThat(reports.markSent(id, AT, "2026-10-21T08:00:05")).isEqualTo(1);
            assertThat(reports.claimResend(id, 1, "Pod B", AT)).isZero();             // bayat sürüm
            assertThat(reports.claimResend(id, 2, "Pod B", AT)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            reports.deleteById(id);
        }
    }

    @Test
    @DisplayName("haftalık erişilebilirlik: ikinci INSERT benzersizlikten düşer; COALESCE CAS ve sahiplenme damgasıyla kapanış")
    void availabilityClaims() {
        WeeklyAvailabilityLogRepository wal = PostgresIt.app().bean(WeeklyAvailabilityLogRepository.class);
        var jdbc = PostgresIt.app().jdbc();
        long team = 913_000L + (long) (Math.random() * 10_000);
        WeeklyAvailabilityLog row = row(team, "SENDING", AT);
        wal.save(row);
        try {
            assertThatThrownBy(() -> wal.save(row(team, "SENDING", AT))).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(wal.finishClaim(team, 2026, 42, "2026-10-21T07:59:59", "SENT", AT)).isZero();   // başka sahiplenme
            assertThat(wal.finishClaim(team, 2026, 42, AT, "FAILED", "2026-10-21T08:01:00")).isEqualTo(1);
            assertThat(wal.finishClaim(team, 2026, 42, AT, "SENT", "2026-10-21T08:01:00")).isZero();   // zaten kapandı

            assertThat(wal.claimExisting(team, 2026, 42, "FAILED", "2026-10-21T07:00:00", "2026-10-21T09:00:00")).isZero();
            assertThat(wal.claimExisting(team, 2026, 42, "FAILED", "2026-10-21T08:01:00", "2026-10-21T09:00:00")).isEqualTo(1);
            assertThat(wal.claimExisting(team, 2026, 42, "FAILED", "2026-10-21T08:01:00", "2026-10-21T09:00:01")).isZero();

            jdbc.update("UPDATE weekly_availability_log SET status = NULL, sent_at = NULL WHERE team_id = ?", team);
            assertThat(wal.claimExisting(team, 2026, 42, "-", "-", "2026-10-21T10:00:00")).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM weekly_availability_log WHERE team_id = ?", team);
        }
    }

    private static WeeklyAvailabilityLog row(long team, String status, String stamp) {
        WeeklyAvailabilityLog l = new WeeklyAvailabilityLog();
        l.setTeamId(team);
        l.setReportYear(2026);
        l.setWeekNo(42);
        l.setStatus(status);
        l.setSentAt(stamp);
        l.setCreatedAt(stamp);
        return l;
    }
}
