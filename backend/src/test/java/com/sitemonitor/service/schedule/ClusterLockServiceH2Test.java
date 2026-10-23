package com.sitemonitor.service.schedule;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yenilenen küme kilidi (2026-10-21, çok pod R1-A, MP-02) — iki sahip AYNI H2 veritabanını (PostgreSQL kipi, gerçek
 * {@code scheduler_lock} SQL'i) ve ortak saati paylaşır: alış, yenileme, süre dolumu, kayıp (LOST), sert tavan, bırakma,
 * kapanışta tümünü bırakma, tablo yokken eski geri düşüş ve geçici hatada güvenli taraf.
 */
class ClusterLockServiceH2Test {

    private static final long SEC = 1_000L;
    private static final long MIN = 60 * SEC;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    ClusterLockService owner(int n) {
        return mp.locks(MultiPodTestSupport.owner(n));
    }

    @Test
    @DisplayName("iki sahip yarışır: tek kazanan; aynı sahip de ikinci kez alamaz; bırakınca diğeri alır")
    void twoOwnersRace_singleWinner() {
        ClusterLockService a = owner(1);
        ClusterLockService b = owner(2);
        List<Boolean> won = mp.race(() -> a.tryAcquire("job").isPresent(), () -> b.tryAcquire("job").isPresent());
        assertThat(won).containsExactlyInAnyOrder(true, false);
        ClusterLockService winner = won.get(0) ? a : b;
        ClusterLockService loser = won.get(0) ? b : a;
        assertThat(winner.tryAcquire("job")).as("aynı sahip ikinci kez alamaz").isEmpty();
        assertThat(mp.schedulerLockHolder("job")).isEqualTo(winner.owner());
        assertThat(winner.held("job")).isTrue();
        assertThat(loser.held("job")).isFalse();

        assertThat(winner.release("job")).isTrue();
        assertThat(mp.schedulerLockHolder("job")).isNull();
        assertThat(loser.tryAcquire("job")).isPresent();
    }

    @Test
    @DisplayName("yenileme: iş kilit süresinden (3 dk) uzun sürse de satır yenilenir — diğer sahip alamaz; yenileme durunca TTL sonra alır")
    void renewalKeepsTheLockPastTtl_holderDiesThenOtherTakesOver() {
        ClusterLockService a = owner(1);
        ClusterLockService b = owner(2);
        assertThat(a.tryAcquire("cert-check")).isPresent();
        for (int i = 0; i < 40; i++) {                  // 20 dk boyunca her 30 sn yenileme (kilit süresi 3 dk)
            mp.advance(30 * SEC);
            assertThat(a.renewAll()).isEqualTo(1);
            assertThat(b.tryAcquire("cert-check")).as("yenilenen kilit başka sahibe geçmez").isEmpty();
        }
        assertThat(a.held("cert-check")).isTrue();

        // a öldü (yenileme yok): TTL dolmadan b alamaz, dolunca alır
        mp.advance(2 * MIN + 50 * SEC);
        assertThat(b.tryAcquire("cert-check")).isEmpty();
        assertThat(a.held("cert-check")).as("son yazılan süre geçmedi").isTrue();
        mp.advance(11 * SEC);
        assertThat(a.held("cert-check")).as("süre geçti → artık kesin bende değil").isFalse();
        assertThat(b.tryAcquire("cert-check")).isPresent();

        // eski sahip geri gelir: yenileme 0 satır → LOST, sonuç yazmaz; bırakması yeni sahibin satırını silmez
        assertThat(a.renewAll()).isZero();
        assertThat(a.hold("cert-check")).map(ClusterLockService.Hold::state).contains(ClusterLockService.State.LOST);
        assertThat(a.held("cert-check")).isFalse();
        a.release("cert-check");
        assertThat(mp.schedulerLockHolder("cert-check")).isEqualTo(b.owner());
    }

    @Test
    @DisplayName("yönetici satırı silerse (zorla bırakma) yenileme KAYBI fark eder: held=false, LOST")
    void forcedDelete_detectedAsLost() {
        ClusterLockService a = owner(1);
        assertThat(a.tryAcquire("cert-check")).isPresent();
        mp.jdbc().update("DELETE FROM scheduler_lock WHERE name = 'cert-check'");
        mp.advance(30 * SEC);
        assertThat(a.renewAll()).isZero();
        assertThat(a.held("cert-check")).isFalse();
        assertThat(a.hold("cert-check")).map(ClusterLockService.Hold::state).contains(ClusterLockService.State.LOST);
        // 2026-10-23 (çok pod R2b): kayıp sayacı (sitemonitor_cluster_lock_lost) tutuş başına BİR kez artar
        assertThat(a.lostCount()).isEqualTo(1);
        mp.advance(30 * SEC);
        a.renewAll();
        assertThat(a.lostCount()).as("aynı tutuş ikinci kez sayılmaz").isEqualTo(1);
        assertThat(owner(2).lostCount()).as("pod başına").isZero();
    }

    @Test
    @DisplayName("sert tavan: 3 sa sonra yenileme durur (askıda iş kümeyi kilitlemez), TTL sonra diğer sahip alır; uzun işler 4 sa")
    void hardCap_stopsRenewal() {
        assertThat(ClusterLockService.hardCapFor("nightly-cleanup")).isEqualTo(Duration.ofHours(4));
        assertThat(ClusterLockService.hardCapFor("ping-rollup-rebuild")).isEqualTo(Duration.ofHours(4));
        assertThat(ClusterLockService.hardCapFor("cert-check")).isEqualTo(Duration.ofHours(3));

        ClusterLockService a = owner(1);
        ClusterLockService b = owner(2);
        assertThat(a.tryAcquire("stuck", Duration.ofMinutes(3), Duration.ofMinutes(10))).isPresent();
        int renewed = 0;
        for (int i = 0; i < 30; i++) {                  // 15 dk
            mp.advance(30 * SEC);
            renewed += a.renewAll();
        }
        assertThat(renewed).as("yalnız tavana kadar (10 dk → 19 tur)").isEqualTo(19);
        assertThat(a.hold("stuck")).map(ClusterLockService.Hold::state).contains(ClusterLockService.State.CAPPED);
        assertThat(a.held("stuck")).isFalse();
        assertThat(b.tryAcquire("stuck")).isPresent();
    }

    @Test
    @DisplayName("yenileme veritabanı hatası: tutuş son yazılan süreye kadar geçerli, sonra güvenli tarafta false")
    void renewalDbError_heldUntilLastConfirmedUntil() {
        AtomicInteger fail = new AtomicInteger();
        JdbcTemplate flaky = Mockito.spy(mp.jdbc());
        Mockito.doAnswer(inv -> {
            if (fail.get() > 0) throw new DataAccessResourceFailureException("havuz dolu");
            return inv.callRealMethod();
        }).when(flaky).update(Mockito.eq(ClusterLockService.SQL_RENEW), Mockito.any(Object[].class));
        ClusterLockService a = new ClusterLockService(flaky, MultiPodTestSupport.owner(1), mp.clockSupplier(), false);
        assertThat(a.tryAcquire("job")).isPresent();
        fail.set(1);
        mp.advance(2 * MIN);
        assertThat(a.renewAll()).isZero();
        assertThat(a.held("job")).as("son yazılan süre (3 dk) dolmadı").isTrue();
        mp.advance(61 * SEC);
        assertThat(a.held("job")).isFalse();
        fail.set(0);
        assertThat(a.renewAll()).as("veritabanı döndü, satır hâlâ bende → yenilenir").isEqualTo(1);
        assertThat(a.held("job")).isTrue();
    }

    @Test
    @DisplayName("kapanış: releaseAllOwn yalnız BU sahibin satırlarını siler; ölen pod'un kilidi beklemeden devralınır")
    void releaseAllOwn_onlyOwnRows() {
        ClusterLockService a = owner(1);
        ClusterLockService b = owner(2);
        a.tryAcquire("watchdog");
        a.tryAcquire("escalation-steps");
        b.tryAcquire("quiet-digest");
        assertThat(a.releaseAllOwn()).isEqualTo(2);
        assertThat(mp.schedulerLockHolder("watchdog")).isNull();
        assertThat(mp.schedulerLockHolder("escalation-steps")).isNull();
        assertThat(mp.schedulerLockHolder("quiet-digest")).isEqualTo(b.owner());
        assertThat(b.tryAcquire("watchdog")).isPresent();
    }

    @Test
    @DisplayName("eski tutuşun bırakılması yeni tutuşun satırını SİLMEZ (zorla bırak → aynı pod yeniden aldı)")
    void staleHoldReleaseKeepsNewRow() {
        ClusterLockService a = owner(1);
        Optional<ClusterLockService.Hold> first = a.tryAcquire("cert-check");
        assertThat(first).isPresent();
        mp.jdbc().update("DELETE FROM scheduler_lock WHERE name = 'cert-check'");   // yönetici zorla bıraktı
        a.forget("cert-check");
        Optional<ClusterLockService.Hold> second = a.tryAcquire("cert-check");
        assertThat(second).isPresent();
        assertThat(a.release(first.get())).isFalse();
        assertThat(mp.schedulerLockHolder("cert-check")).isEqualTo(a.owner());
        assertThat(a.held("cert-check")).isTrue();
        assertThat(a.release(second.get())).isTrue();
        assertThat(mp.schedulerLockHolder("cert-check")).isNull();
    }

    @Test
    @DisplayName("tablo YOK → eski tek pod geri düşüşü (kilitsiz koş, held=true); geçici hata → güvenli taraf (alınamadı)")
    void missingTableLegacy_transientErrorFailsClosed() {
        JdbcTemplate noTable = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:locknotable" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        ClusterLockService legacy = new ClusterLockService(noTable, "legacy-1", mp.clockSupplier(), false);
        Optional<ClusterLockService.Hold> h = legacy.tryAcquire("job");
        assertThat(h).isPresent();
        assertThat(h.get().legacy()).isTrue();
        assertThat(legacy.held("job")).isTrue();
        assertThat(legacy.renewAll()).isZero();
        assertThat(legacy.release(h.get())).isTrue();

        JdbcTemplate down = Mockito.mock(JdbcTemplate.class, inv -> {
            throw new DataAccessResourceFailureException("bağlantı yok");
        });
        ClusterLockService broken = new ClusterLockService(down, "broken-1", mp.clockSupplier(), false);
        assertThat(broken.tryAcquire("job")).isEmpty();
        assertThat(broken.held("job")).isFalse();
    }

    @Test
    @DisplayName("kapanış başladı (stopAcquiring): yeni kilit verilmez, tutulan kilit yenilenmeye devam eder; close() kalanları bırakır")
    void stopAcquiring_refusesNewKeepsRenewing() {
        ClusterLockService a = owner(1);
        assertThat(a.tryAcquire("running-job")).isPresent();
        a.stopAcquiring();
        assertThat(a.acquiringStopped()).isTrue();
        assertThat(a.tryAcquire("new-job")).isEmpty();
        assertThat(mp.schedulerLockHolder("new-job")).isNull();
        mp.advance(30 * SEC);
        assertThat(a.renewAll()).isEqualTo(1);
        assertThat(a.held("running-job")).isTrue();
        a.close();
        assertThat(mp.schedulerLockHolder("running-job")).isNull();
    }

    @Test
    @DisplayName("kilit metni eski sürümle aynı biçimde (UTC yyyy-MM-ddTHH:mm:ss) — yuvarlanan dağıtımda eski pod aynı kuralla okur")
    void lockedUntilFormatUnchanged() {
        ClusterLockService a = owner(1);
        a.tryAcquire("job");
        String until = mp.jdbc().queryForObject("SELECT locked_until FROM scheduler_lock WHERE name = 'job'", String.class);
        assertThat(until).isEqualTo(ClusterLockService.iso(mp.now() + ClusterLockService.RENEWED_TTL.toMillis()));
        assertThat(until).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }
}
