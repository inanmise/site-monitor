package com.sitemonitor.service.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Küme geneli zamanlama kaydı (2026-10-09) — GERÇEK SQL (H2, PostgreSQL kipi, işlem dışı / autocommit: üretimdeki
 * {@code JdbcTemplate} çağrılarıyla aynı). Aynı veritabanını paylaşan iki "pod" (farklı {@code claimed_by}) ile: ilk
 * görüşte ve her aralıkta yalnız BİR sahiplenme, ızgara korunur, bayat önbellek çift sahiplenme üretmez, eşzamanlı
 * yarışta tek kazanan, kısaltılmış aralık hemen vadeli ama pod saat kayması değil, cron tarzı tur kaydı bir sonraki
 * tetiğe kadar tutar, budama silinen izlemeleri atar ve tur satırlarını korur, tablo yoksa istisna ÇAĞIRANA gider.
 */
class ClusterScheduleServiceH2Test {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long T0 = 1_791_540_000_000L;   // 2026-10-09 civarı, sabit
    private static final long MIN = 60_000L;

    JdbcTemplate jdbc;
    ClusterScheduleService podA;
    ClusterScheduleService podB;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:clustersched" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(ClusterScheduleService.DDL);
        jdbc.execute(ClusterScheduleService.DDL);   // yama idempotent (IF NOT EXISTS)
        podA = new ClusterScheduleService(jdbc, "pod-a");
        podB = new ClusterScheduleService(jdbc, "pod-b");
    }

    /** SchedulerService.checkDue ile aynı ızgara kuralı. */
    private static LongUnaryOperator grid(long nowMs, long intervalMs) {
        return prev -> {
            if (prev > nowMs) return nowMs + intervalMs;
            long next = prev;
            while (next <= nowMs) next += intervalMs;
            return next;
        };
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("SELECT * FROM monitor_check_schedule WHERE monitor_key = ?", key);
    }

    @Test
    @DisplayName("ilk görüş: aynı anda bakan iki pod'dan yalnız biri sahiplenir; diğeri vadeyi öğrenir")
    void firstSight_onlyOnePodClaims() {
        var a = podA.claim("port:1", T0, MIN, null, grid(T0, MIN));
        var b = podB.claim("port:1", T0, MIN, null, grid(T0, MIN));

        assertThat(a.due()).isTrue();
        assertThat(a.nextDueAt()).isEqualTo(T0 + MIN);
        assertThat(b.due()).isFalse();
        assertThat(b.nextDueAt()).isEqualTo(T0 + MIN);
        assertThat(row("port:1")).containsEntry("claimed_by", "pod-a").containsEntry("next_due_at", T0 + MIN);
    }

    @Test
    @DisplayName("aralık dolunca yeniden TAM BİR KEZ vadeli; geç sahiplenme ızgarayı kaydırmaz")
    void dueAgainAfterInterval_exactlyOnce_gridPreserved() {
        podA.claim("http:7", T0, MIN, null, grid(T0, MIN));

        long late = T0 + MIN + 5_000;                       // tur 5 sn geç uyandı
        var b = podB.claim("http:7", late, MIN, null, grid(late, MIN));
        var a = podA.claim("http:7", late, MIN, T0 + MIN, grid(late, MIN));   // A eski önbelleğiyle

        assertThat(b.due()).isTrue();
        assertThat(b.nextDueAt()).isEqualTo(T0 + 2 * MIN);   // ızgara: T0+2dk (T0+2dk+5sn DEĞİL)
        assertThat(a.due()).isFalse();
        assertThat(a.nextDueAt()).isEqualTo(T0 + 2 * MIN);
        assertThat(row("http:7")).containsEntry("claimed_by", "pod-b");
    }

    @Test
    @DisplayName("iki pod aynı bayat değeri biliyor: CAS'ı yalnız ilki kazanır, ikincisi atlar")
    void sameKnownValue_onlyFirstCasWins() {
        podA.claim("dns:3", T0, MIN, null, grid(T0, MIN));
        long t = T0 + MIN;

        var a = podA.claim("dns:3", t, MIN, T0 + MIN, grid(t, MIN));
        var b = podB.claim("dns:3", t, MIN, T0 + MIN, grid(t, MIN));

        assertThat(a.due()).isTrue();
        assertThat(b.due()).isFalse();
        assertThat(b.nextDueAt()).isEqualTo(T0 + 2 * MIN);
    }

    @Test
    @DisplayName("vadesi gelmemiş satır: bilinen değer olmadan da (yeni açılan pod) sahiplenilmez")
    void notDue_freshPodDoesNotClaim() {
        podA.claim("ping:9", T0, 5 * MIN, null, grid(T0, 5 * MIN));

        var fresh = new ClusterScheduleService(jdbc, "pod-c").claim("ping:9", T0 + MIN, 5 * MIN, null, grid(T0 + MIN, 5 * MIN));

        assertThat(fresh.due()).isFalse();
        assertThat(fresh.nextDueAt()).isEqualTo(T0 + 5 * MIN);
    }

    @Test
    @DisplayName("eşzamanlı yarış: 8 pod aynı anda aynı vadeyi sahiplenmeye çalışır → her pencerede tek kazanan")
    void concurrentPods_exactlyOneWinnerPerWindow() throws Exception {
        int pods = 8;
        int keys = 25;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            for (int round = 0; round < 3; round++) {
                long now = T0 + round * MIN;
                for (int k = 0; k < keys; k++) {
                    String key = "keyword:" + k;
                    CyclicBarrier start = new CyclicBarrier(pods);
                    List<Future<Boolean>> results = new ArrayList<>();
                    for (int p = 0; p < pods; p++) {
                        ClusterScheduleService pod = new ClusterScheduleService(jdbc, "pod-" + p);
                        Long known = round == 0 ? null : now;   // ilk turda INSERT yarışı, sonra CAS yarışı
                        results.add(pool.submit(() -> {
                            start.await(5, TimeUnit.SECONDS);
                            return pod.claim(key, now, MIN, known, grid(now, MIN)).due();
                        }));
                    }
                    int winners = 0;
                    for (Future<Boolean> f : results) if (f.get(10, TimeUnit.SECONDS)) winners++;
                    assertThat(winners).as("tur %d anahtar %s", round, key).isEqualTo(1);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("aralık kısaltıldı (vade nominal + kayma payından ileride) → hemen vadeli; pod saat kayması → değil")
    void shortenedInterval_dueNow_butClockSkewIsNot() {
        podA.claim("scripted:4", T0, 60 * MIN, null, grid(T0, 60 * MIN));     // 1 saatlik aralıkla başladı

        long t = T0 + 30_000;
        var b = podB.claim("scripted:4", t, MIN, null, grid(t, MIN));         // aralık 1 dk'ya indirildi
        assertThat(b.due()).isTrue();
        assertThat(b.nextDueAt()).isEqualTo(t + MIN);

        // Kayma: A'nın yazdığı vade B'nin saatine göre nominal + (< pay) ileride → vadeli DEĞİL
        podA.claim("port:5", T0, MIN, null, grid(T0, MIN));
        long skewedNow = T0 - 90_000;                                           // B'nin saati 90 sn geride
        assertThat(podB.claim("port:5", skewedNow, MIN, null, grid(skewedNow, MIN)).due()).isFalse();
        assertThat(ClusterScheduleService.isDue(T0 + MIN, skewedNow, MIN)).isFalse();
        assertThat(ClusterScheduleService.isDue(T0 + MIN, T0 + MIN, MIN)).isTrue();   // tam sınır vadeli
    }

    @Test
    @DisplayName("cron tarzı tur kaydı: bir sonraki tetikten önceki ana kadar tutulur, o an gelince tekrar alınır")
    void cronStyleRound_heldUntilNextTrigger() {
        long next = T0 + 60 * MIN - MIN;                                       // sıradaki tetik − 1 dk pay
        var a = podA.claim("round:weekly-report-reminder", T0, next - T0, null, prev -> next);
        var b = podB.claim("round:weekly-report-reminder", T0 + 5_000, next - T0 - 5_000, null, prev -> next);

        assertThat(a.due()).isTrue();
        assertThat(b.due()).as("geç tetiklenen pod aynı turu yinelemez").isFalse();

        long nextNext = next + 60 * MIN;
        var b2 = podB.claim("round:weekly-report-reminder", next, nextNext - next, null, prev -> nextNext);
        assertThat(b2.due()).isTrue();
        assertThat(b2.nextDueAt()).isEqualTo(nextNext);
    }

    @Test
    @DisplayName("budama: canlı kümede olmayan izleme satırları silinir; canlı, 'round:' ve 'lease:' satırları kalır")
    void prune_removesDeletedMonitors_keepsLiveAndRounds() {
        for (String k : List.of("port:1", "port:2", "domain:5", "round:uptime", "round:cert-hourly")) {
            podA.claim(k, T0, MIN, null, grid(T0, MIN));
        }
        podA.acquireLease("sweep-leader", T0, 3 * MIN);

        int deleted = podA.prune(Set.of("port:1", "domain:5"));

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT monitor_key FROM monitor_check_schedule ORDER BY monitor_key", String.class))
                .containsExactly("domain:5", "lease:sweep-leader", "port:1", "round:cert-hourly", "round:uptime");
    }

    // ── Kira (tarama liderliği) ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kira: tek sahip; sahibi yeniler; süre dolunca başkası alır, eski sahip alamaz; yalnız sahibi bırakabilir")
    void lease_singleHolder_renew_expire_release() {
        long ttl = 3 * MIN;
        assertThat(podA.acquireLease("sweep-leader", T0, ttl)).isTrue();
        assertThat(podB.acquireLease("sweep-leader", T0 + 1_000, ttl)).isFalse();
        assertThat(podA.acquireLease("sweep-leader", T0 + MIN, ttl)).as("sahibi yeniler").isTrue();
        assertThat(podA.lease("sweep-leader")).isEqualTo(new ClusterScheduleService.Lease("pod-a", T0 + MIN + ttl));

        assertThat(podB.acquireLease("sweep-leader", T0 + MIN + ttl - 1, ttl)).as("süre dolmadan").isFalse();
        assertThat(podB.acquireLease("sweep-leader", T0 + MIN + ttl + 1, ttl)).as("süre doldu").isTrue();
        assertThat(podA.acquireLease("sweep-leader", T0 + MIN + ttl + 2, ttl)).as("eski sahip geri alamaz").isFalse();

        assertThat(podA.releaseLease("sweep-leader")).as("sahibi olmayan bırakamaz").isFalse();
        assertThat(podB.releaseLease("sweep-leader")).isTrue();
        assertThat(podA.lease("sweep-leader")).isNull();
        assertThat(podA.acquireLease("sweep-leader", T0 + MIN + ttl + 3, ttl)).as("bırakılınca hemen alınır").isTrue();
    }

    @Test
    @DisplayName("kira: aynı host'taki önceki örneğin kirası silinir; başka host'unki ve kendi kiram silinmez")
    void lease_clearPreviousInstanceOfSameHost() {
        var prev = new ClusterScheduleService(jdbc, "host1-aaaa1111");
        var me = new ClusterScheduleService(jdbc, "host1-bbbb2222");
        prev.acquireLease("sweep-leader", T0, 3 * MIN);

        assertThat(new ClusterScheduleService(jdbc, "host2-cccc").clearLeaseOfPreviousInstance("sweep-leader", "host2-")).isZero();
        assertThat(me.clearLeaseOfPreviousInstance("sweep-leader", "host1-")).isEqualTo(1);
        assertThat(me.acquireLease("sweep-leader", T0 + 1_000, 3 * MIN)).isTrue();
        assertThat(me.clearLeaseOfPreviousInstance("sweep-leader", "host1-")).as("kendi kiram").isZero();
    }

    @Test
    @DisplayName("kira: 8 pod süresi dolmuş (ve hiç olmayan) kiraya aynı anda uzanır → her seferinde TEK lider")
    void lease_concurrentTakeover_singleWinner() throws Exception {
        int pods = 8;
        long ttl = 3 * MIN;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            for (int round = 0; round < 10; round++) {
                long now = T0 + round * (ttl + 1);                   // her turda önceki kira dolmuş (tur 0: satır yok)
                CyclicBarrier start = new CyclicBarrier(pods);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int p = 0; p < pods; p++) {
                    ClusterScheduleService pod = new ClusterScheduleService(jdbc, "pod-" + round + "-" + p);
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return pod.acquireLease("sweep-leader", now, ttl);
                    }));
                }
                int winners = 0;
                for (Future<Boolean> f : results) if (f.get(10, TimeUnit.SECONDS)) winners++;
                assertThat(winners).as("tur %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("tablo yoksa istisna ÇAĞIRANA gider (SchedulerService bellek içi davranışa düşer) — yutulmaz")
    void missingTable_propagates() {
        DriverManagerDataSource empty = new DriverManagerDataSource(
                "jdbc:h2:mem:clusterschedempty" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        var broken = new ClusterScheduleService(new JdbcTemplate(empty), "pod-x");

        assertThatThrownBy(() -> broken.claim("port:1", T0, MIN, null, grid(T0, MIN)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> broken.claim("port:1", T0, MIN, T0, grid(T0, MIN)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
