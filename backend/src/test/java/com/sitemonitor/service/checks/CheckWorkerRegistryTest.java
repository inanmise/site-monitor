package com.sitemonitor.service.checks;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PingCheckerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CheckWorkerRegistryImpl} H2'de (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §9.2 A): nabız upsert'i, canlı kuralı
 * (30 sn, durma), "başka pod" (kendi kimliği ve aynı pod adının önceki süreci sayılmaz), yetenek süzgeci, okunamayan / henüz
 * okunmamış / bayat önbellek → UNKNOWN, yalnız liderin budaması, yerel yetenekler (ICMP / k6 / işçi kapalı / gövde yok).
 */
class CheckWorkerRegistryTest {

    private static final long T0 = 1_800_000_000_000L;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final TestDbClock clock = new TestDbClock(T0);
    final AtomicLong nano = new AtomicLong(5_000_000_000L);
    final AtomicBoolean leader = new AtomicBoolean();
    final AtomicBoolean icmp = new AtomicBoolean(true);
    final AtomicBoolean k6 = new AtomicBoolean(true);
    final AtomicBoolean runner = new AtomicBoolean(true);
    DistributedChecks on;

    @BeforeEach
    void setUp() {
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        on = distributed(true, DistributedChecks.Tuning.DEFAULTS);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    static DistributedChecks distributed(boolean enabled, DistributedChecks.Tuning tuning) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean(eq(DistributedChecks.KEY_ENABLED), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(enabled);
        when(s.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn(CheckJobTypes.DEFAULT_CSV);
        return new DistributedChecks(s, tuning);
    }

    private CheckWorkerRegistryImpl registry(String self, DistributedChecks dc, int busy) {
        return registry(mp.jdbc(), self, dc, busy);
    }

    private CheckWorkerRegistryImpl registry(JdbcTemplate jdbc, String self, DistributedChecks dc, int busy) {
        CheckWorkerRegistryImpl r = new CheckWorkerRegistryImpl(jdbc, clock, dc, self, leader::get, icmp::get, icmp::get, k6::get,
                k6::get, runner::get, () -> new CheckWorkerRegistryImpl.LocalStats(16, busy, 7L, 1L, 420L), () -> "20.170.0",
                nano::get);
        r.probeIcmpNow();   // yoklama iş parçacığının ilk sondası (QW-L1: sonda dönmeden ping ilan edilmez)
        return r;
    }

    private void advance(long ms) {
        clock.advance(ms);
        nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    @Test
    @DisplayName("Nabız: ilk tur satırı ekler, sonraki tur günceller; sütunlar dolu")
    void heartbeatUpsert() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 3);
        a.tick();
        advance(10_000);
        a.tick();
        List<Map<String, Object>> rows = mp.jdbc().queryForList("SELECT * FROM monitor_check_workers");
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(r.get("instance_id")).isEqualTo("pod1-00000001");
        assertThat(r.get("pod")).isEqualTo("pod1");
        assertThat(r.get("caps")).isEqualTo(CheckJobTypes.DEFAULT_CSV);
        assertThat(((Number) r.get("slots")).intValue()).isEqualTo(16);
        assertThat(((Number) r.get("busy")).intValue()).isEqualTo(3);
        assertThat(r.get("completed")).isEqualTo(7L);
        assertThat(r.get("late")).isEqualTo(1L);
        assertThat(r.get("claim_lag_p95_ms")).isEqualTo(420L);
        assertThat(r.get("heartbeat_ms")).isEqualTo(T0 + 10_000);
        assertThat(r.get("version")).isEqualTo("20.170.0");
        assertThat(r.get("stopping")).isEqualTo(Boolean.FALSE);
        assertThat(a.status().heartbeats()).isEqualTo(2);
    }

    @Test
    @DisplayName("Canlı başka işçi: kendi satırı ve aynı pod adının önceki süreci sayılmaz; 30 sn kuralı; durma; yetenek")
    void liveOtherWorkersRule() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 0);
        CheckWorkerRegistryImpl b = registry("pod2-00000002", on, 0);
        CheckWorkerRegistryImpl previousA = registry("pod1-0000000f", on, 0);
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("henüz okunmadı").isEqualTo(CheckWorkerRegistry.UNKNOWN);
        icmp.set(false);   // b (ve önceki süreç) ICMP'siz yazar
        b.tick();
        previousA.tick();
        icmp.set(true);
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(1);
        assertThat(a.liveOtherWorkers(CheckJobTypes.PING)).as("b ping yetisiz").isZero();
        b.refresh();
        assertThat(b.liveOtherWorkers(CheckJobTypes.PING)).as("a ping alır; önceki süreç pod1 de başka sayılır b için")
                .isEqualTo(1);
        assertThat(b.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(2);
        assertThat(a.workers()).hasSize(3).filteredOn(CheckWorkerRegistry.WorkerView::live).hasSize(3);

        advance(25_000);
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("b'nin nabzı 25 sn önce").isEqualTo(1);
        advance(6_000);
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("önbellekten, okumadan sonra geçen süre sayılır").isZero();
        b.tick();
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(1);
        b.writeStopping();
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("duruyor").isZero();
        assertThat(a.workers()).filteredOn(w -> w.instanceId().equals("pod2-00000002"))
                .singleElement().satisfies(w -> assertThat(w.stopping()).isTrue());
    }

    @Test
    @DisplayName("Bayat önbellek (kayıt iş parçacığı takıldı) ve okunamayan tablo → UNKNOWN")
    void staleOrUnreadableIsUnknown() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 0);
        registry("pod2-00000002", on, 0).tick();
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(1);
        nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(CheckWorkerRegistryImpl.CACHE_STALE_MS + 1));
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(CheckWorkerRegistry.UNKNOWN);

        mp.jdbc().execute("DROP TABLE monitor_check_workers");
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(CheckWorkerRegistry.UNKNOWN);
        assertThat(a.workers()).isEmpty();
        assertThat(a.status().tableMissing()).isTrue();
        assertThat(a.status().readFailures()).isPositive();
    }

    @Test
    @DisplayName("Budama: yalnız lider, saatte bir, nabzı 1 günden eski satırlar")
    void pruneOnlyByLeaderHourly() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 0);
        mp.jdbc().update("INSERT INTO monitor_check_workers (instance_id, pod, caps, slots, heartbeat_ms, stopping) "
                + "VALUES ('old-00000009', 'old', 'port', 16, ?, TRUE)", T0 - CheckWorkerRegistry.PRUNE_AFTER_MS - 1);
        mp.jdbc().update("INSERT INTO monitor_check_workers (instance_id, pod, caps, slots, heartbeat_ms, stopping) "
                + "VALUES ('young-0000000a', 'young', 'port', 16, ?, TRUE)", T0 - 60_000);
        a.tick();
        assertThat(rowCount()).as("lider değil — budama yok").isEqualTo(3);
        leader.set(true);
        a.tick();
        assertThat(rowCount()).isEqualTo(2);
        assertThat(a.status().pruned()).isEqualTo(1);
        mp.jdbc().update("INSERT INTO monitor_check_workers (instance_id, pod, caps, slots, heartbeat_ms, stopping) "
                + "VALUES ('old2-0000000b', 'old2', 'port', 16, ?, TRUE)", T0 - CheckWorkerRegistry.PRUNE_AFTER_MS - 1);
        a.tick();
        assertThat(rowCount()).as("saatte bir").isEqualTo(3);
        advance(CheckWorkerRegistryImpl.PRUNE_EVERY_MS);
        a.tick();
        assertThat(rowCount()).isEqualTo(2);
    }

    private int rowCount() {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_workers", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Yerel yetenekler: ICMP yok → ping yok, k6 yok → scripted yok, gövde yok / işçi kapalı → hiçbiri")
    void localCapabilities() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 0);
        assertThat(a.localCapabilities()).containsExactlyElementsOf(CheckJobTypes.ALL);
        icmp.set(false);
        k6.set(false);
        assertThat(a.localCapabilities()).doesNotContain(CheckJobTypes.PING, CheckJobTypes.SCRIPTED)
                .contains(CheckJobTypes.PORT, CheckJobTypes.DNS);
        runner.set(false);
        assertThat(a.localCapabilities()).isEmpty();
        runner.set(true);
        DistributedChecks off = distributed(true, new DistributedChecks.Tuning(false, 16, 1000, 5000, 2000, 50, 5000, 600000));
        CheckWorkerRegistryImpl disabled = registry("pod3-00000003", off, 0);
        assertThat(disabled.localCapabilities()).isEmpty();
        disabled.tick();
        assertThat(rowCount()).as("işçi kapalı pod nabız yazmaz").isZero();
    }

    @Test
    @DisplayName("ICMP (inceleme QW-L1): ilk sonda dönmeden ping yok; sondanın 'yok'u 'na' tutma süresi dolsa da sonraki başarılı sondaya dek kalır")
    void icmpCapabilityNeverReturnsBeforeAProbe() {
        // PingCheckerService'in kuralı: son ICMP_NA_HOLD_MS içinde "na" yoksa icmpAvailable() true; sonda "na" damgalar.
        AtomicLong naAt = new AtomicLong(Long.MIN_VALUE);
        AtomicBoolean podHasIcmp = new AtomicBoolean(false);
        java.util.function.BooleanSupplier available = () -> naAt.get() == Long.MIN_VALUE
                || nano.get() - naAt.get() >= TimeUnit.MILLISECONDS.toNanos(PingCheckerService.ICMP_NA_HOLD_MS);
        java.util.function.BooleanSupplier probe = () -> {
            if (!podHasIcmp.get()) naAt.set(nano.get());
            return available.getAsBoolean();
        };
        CheckWorkerRegistryImpl a = new CheckWorkerRegistryImpl(mp.jdbc(), clock, on, "pod1-00000001", leader::get, available,
                probe, k6::get, k6::get, runner::get, () -> CheckWorkerRegistryImpl.LocalStats.IDLE, () -> "v", nano::get);
        assertThat(a.icmpProbeOk()).isNull();
        assertThat(a.localCapabilities()).as("ilk sonda dönmeden ping ilan edilmez").doesNotContain(CheckJobTypes.PING)
                .contains(CheckJobTypes.PORT);

        assertThat(a.probeIcmpNow()).isFalse();   // ICMP'siz pod
        assertThat(a.localCapabilities()).doesNotContain(CheckJobTypes.PING);
        advance(PingCheckerService.ICMP_NA_HOLD_MS);   // "na" tutma süresi doldu, yeni sonda henüz yok
        assertThat(available.getAsBoolean()).as("icmpAvailable() yeniden true").isTrue();
        assertThat(a.localCapabilities()).as("sondanın 'yok'u kalıcı — pencere yok").doesNotContain(CheckJobTypes.PING);
        a.tick();
        assertThat(mp.jdbc().queryForObject("SELECT caps FROM monitor_check_workers", String.class))
                .as("nabız da ping ilan etmez").doesNotContain(CheckJobTypes.PING);

        podHasIcmp.set(true);   // ör. düğüm yetkisi düzeldi
        assertThat(a.probeIcmpNow()).isTrue();
        assertThat(a.localCapabilities()).contains(CheckJobTypes.PING);
        naAt.set(nano.get());   // gerçek bir ping sonucu "na" (tek izleme EPERM) → 5 dk yok
        assertThat(a.localCapabilities()).doesNotContain(CheckJobTypes.PING);
        assertThat(CheckWorkerRegistryImpl.ICMP_PROBE_EVERY_MS)
                .as("her 'na' tutma penceresinde en az bir sonda").isEqualTo(PingCheckerService.ICMP_NA_HOLD_MS - 60_000L);
    }

    @Test
    @DisplayName("Okuma hatası (inceleme QW-L6): tek başarısız okuma önceki iyi görüntüyü korur (≤ 30 sn); ancak sonra UNKNOWN")
    void singleReadFailureKeepsPreviousSnapshot() {
        AtomicBoolean failReads = new AtomicBoolean();
        JdbcTemplate flaky = new JdbcTemplate(mp.dataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (failReads.get() && sql.equals(CheckJobSql.SQL_WORKERS)) throw new DataAccessResourceFailureException("db");
                return super.query(sql, rowMapper, args);
            }
        };
        registry("pod2-00000002", on, 0).tick();
        CheckWorkerRegistryImpl a = registry(flaky, "pod1-00000001", on, 0);
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(1);

        failReads.set(true);
        advance(10_000);
        a.tick();
        assertThat(a.status().readFailures()).isEqualTo(1);
        assertThat(a.keptAfterReadFailure()).isEqualTo(1);
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("tek hata türü klasiğe çevirmez").isEqualTo(1);
        assertThat(a.workers()).isNotEmpty();
        advance(10_000);
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("20 sn — hâlâ önceki iyi okuma").isEqualTo(0 + 1);

        advance(10_001);
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).as("önceki okuma 30 sn'yi aştı").isEqualTo(CheckWorkerRegistry.UNKNOWN);
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(CheckWorkerRegistry.UNKNOWN);
        assertThat(a.workers()).as("artık okunamadı").isEmpty();

        failReads.set(false);
        registry("pod2-00000002", on, 0).tick();
        a.tick();
        assertThat(a.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(1);
    }

    @Test
    @DisplayName("Kapanış: bayrak sonrası nabız yazılmaz; başlamamış kayıt kapanışta iş parçacığı açmaz")
    void closingStopsHeartbeats() {
        CheckWorkerRegistryImpl a = registry("pod1-00000001", on, 0);
        a.onContextClosed(null);
        assertThat(a.closing()).isTrue();
        a.tick();
        assertThat(rowCount()).isZero();
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .doesNotContain(CheckWorkerRegistryImpl.STOP_THREAD);
    }

    @Test
    @DisplayName("Başlatma: kayıt ve yoklama iş parçacıkları açılır (yalnız anahtar açıkken etkinleşince); kapanış durdurur")
    void startAndStopThreads() throws Exception {
        AtomicLong probes = new AtomicLong();
        CheckWorkerRegistryImpl a = new CheckWorkerRegistryImpl(mp.jdbc(), clock, on, "pod1-00000001", leader::get, icmp::get,
                () -> {
                    probes.incrementAndGet();
                    return true;
                }, k6::get, k6::get, runner::get, () -> CheckWorkerRegistryImpl.LocalStats.IDLE, () -> "v", System::nanoTime);
        a.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while ((rowCount() == 0 || probes.get() == 0) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(probes.get()).as("ICMP sondası başlangıçta").isPositive();
        assertThat(threadNames()).contains(CheckWorkerRegistryImpl.THREAD, CheckWorkerRegistryImpl.PROBE_THREAD);
        a.onContextClosed(null);
        deadline = System.currentTimeMillis() + 7_000;
        while (anyCheckWorkerThread() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(threadNames()).as("kapanış iki iş parçacığını da durdurur")
                .doesNotContain(CheckWorkerRegistryImpl.THREAD, CheckWorkerRegistryImpl.PROBE_THREAD);
        deadline = System.currentTimeMillis() + 5_000;
        while (!Boolean.TRUE.equals(mp.jdbc().queryForObject("SELECT stopping FROM monitor_check_workers", Boolean.class))
                && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(mp.jdbc().queryForObject("SELECT stopping FROM monitor_check_workers", Boolean.class)).isTrue();
    }

    static boolean anyCheckWorkerThread() {
        return threadNames().stream().anyMatch(n -> n.startsWith("check-worker"));
    }

    static java.util.Set<String> threadNames() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive()) out.add(t.getName());
        return out;
    }
}
