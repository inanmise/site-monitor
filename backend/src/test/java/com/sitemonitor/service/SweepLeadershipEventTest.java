package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.GAINED;
import static com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.LOST;
import static com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.RELEASED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarama liderliği değişim olayı (2026-10-21, çok pod P0) — {@link SchedulerService} kira geçişlerinde
 * {@link SweepLeadershipChangedEvent} yayınlar: alış (GAINED), başka sahibin devralması (LOST) ve kapanışta bırakış
 * (RELEASED) her biri TAM BİR KEZ; kararlı yenilemeler (lider de takipçi de) hiçbir şey yayınlamaz; aynı veritabanını
 * paylaşan iki pod'da aynı anda en çok bir pod "lider" olur. Gerçek SQL (H2, PostgreSQL kipi) — {@link MultiPodTestSupport}.
 */
class SweepLeadershipEventTest {

    /** {@code site.monitor.scheduler.sweep-leader-ttl-ms} varsayılanı; lider her {@code TTL/3}'te yeniler. */
    static final long TTL = 180_000L;
    static final long RENEW = TTL / 3;

    MultiPodTestSupport mp;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static List<SweepLeadershipChangedEvent> events(Pod p) {
        return p.events().ofType(SweepLeadershipChangedEvent.class);
    }

    static List<String> reasons(Pod p) {
        return events(p).stream().map(SweepLeadershipChangedEvent::reason).toList();
    }

    /** Pod'un son olayına göre "lider olduğuna inanıyor mu". */
    static boolean believesLeader(Pod p) {
        List<SweepLeadershipChangedEvent> ev = events(p);
        return !ev.isEmpty() && ev.get(ev.size() - 1).leader();
    }

    @Test
    @DisplayName("alış: ilk kira yoklaması GAINED'i bir kez yayınlar; önbellekli sorular ve kararlı yenilemeler yayınlamaz")
    void gain_publishesOnce_steadyRenewalsPublishNothing() {
        Pod a = mp.pod(1);

        assertThat(a.scheduler().isSweepLeader()).isTrue();
        assertThat(events(a)).containsExactly(
                new SweepLeadershipChangedEvent(true, MultiPodTestSupport.owner(1), MultiPodTestSupport.T0, GAINED));

        assertThat(a.scheduler().isSweepLeader()).isTrue();          // önbellek
        for (int i = 0; i < 10; i++) {
            mp.advance(RENEW);
            a.scheduler().renewSweepLeadership();                    // yenileyici turu (her tur DB'ye sorar)
            assertThat(a.scheduler().isSweepLeader()).isTrue();      // tarama iş parçacığı (önbellek / yenileme)
        }
        assertThat(reasons(a)).containsExactly(GAINED);
        assertThat(mp.lease("sweep-leader").owner()).isEqualTo(MultiPodTestSupport.owner(1));
    }

    @Test
    @DisplayName("takipçi: kira başkasındayken yoklamalar ve yenilemeler hiçbir olay yayınlamaz")
    void follower_publishesNothing() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        a.scheduler().renewSweepLeadership();

        for (int i = 0; i < 10; i++) {
            b.scheduler().renewSweepLeadership();
            assertThat(b.scheduler().isSweepLeader()).isFalse();
            mp.advance(RENEW / 2);
            a.scheduler().renewSweepLeadership();
        }

        assertThat(events(b)).isEmpty();
        assertThat(reasons(a)).containsExactly(GAINED);
    }

    @Test
    @DisplayName("kayıp: lider yenileyemeden kira dolar, başka sahip alır → eski lider LOST'u bir kez yayınlar")
    void loss_publishesOnce_whenAnotherOwnerTakesTheLease() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        a.scheduler().renewSweepLeadership();
        b.scheduler().renewSweepLeadership();

        mp.advance(TTL + 1);                                         // a durakladı (GC, dolu havuz) — kira doldu
        b.scheduler().renewSweepLeadership();                        // b devralır
        assertThat(reasons(b)).containsExactly(GAINED);

        a.scheduler().renewSweepLeadership();                        // a geri döner, kirayı başkasında görür
        long lostAt = mp.now();
        assertThat(events(a)).hasSize(2);
        assertThat(events(a).get(1))
                .isEqualTo(new SweepLeadershipChangedEvent(false, MultiPodTestSupport.owner(1), lostAt, LOST));

        for (int i = 0; i < 5; i++) {                                // kayıp bir kez; sonraki yoklamalar sessiz
            mp.advance(RENEW / 2);
            a.scheduler().renewSweepLeadership();
            assertThat(a.scheduler().isSweepLeader()).isFalse();
            b.scheduler().renewSweepLeadership();
        }
        assertThat(reasons(a)).containsExactly(GAINED, LOST);
        assertThat(reasons(b)).containsExactly(GAINED);
    }

    @Test
    @DisplayName("kayıp: kira satırı doğrudan başka sahibe geçerse (süresi dolmamış) lider bir sonraki yoklamada LOST yayınlar")
    void loss_whenLeaseRowIsTakenByAnotherOwner() {
        Pod a = mp.pod(1);
        assertThat(a.scheduler().isSweepLeader()).isTrue();

        mp.jdbc().update("UPDATE monitor_check_schedule SET claimed_by = ?, next_due_at = ? WHERE monitor_key = 'lease:sweep-leader'",
                "baska-pod-1234abcd", mp.now() + TTL);
        mp.advance(RENEW);
        assertThat(a.scheduler().isSweepLeader()).isFalse();          // önbellek bayatladı → DB'ye sorar

        assertThat(reasons(a)).containsExactly(GAINED, LOST);
    }

    @Test
    @DisplayName("bırakış: kapanışta RELEASED bir kez; ikinci çağrı, sonraki yoklamalar yayınlamaz; takipçi hemen devralır")
    void release_publishesOnce_andFollowerTakesOverAtOnce() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        a.scheduler().renewSweepLeadership();
        b.scheduler().renewSweepLeadership();
        mp.advance(1_000);

        a.scheduler().releaseSweepLeadership();
        assertThat(events(a)).hasSize(2);
        assertThat(events(a).get(1))
                .isEqualTo(new SweepLeadershipChangedEvent(false, MultiPodTestSupport.owner(1), mp.now(), RELEASED));
        assertThat(mp.lease("sweep-leader")).isNull();

        a.scheduler().releaseSweepLeadership();                       // @PreDestroy + kapanış olayı: ikinci çağrı
        a.scheduler().renewSweepLeadership();                         // kapanırken gelen yenileyici turu
        assertThat(a.scheduler().isSweepLeader()).isFalse();
        assertThat(reasons(a)).containsExactly(GAINED, RELEASED);

        b.scheduler().renewSweepLeadership();                         // kira süresini beklemeden
        assertThat(reasons(b)).containsExactly(GAINED);
        assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(MultiPodTestSupport.owner(2));
    }

    @Test
    @DisplayName("bırakış: lider olmayan pod kapanırsa olay yayınlanmaz, liderin kirası yerinde kalır")
    void release_byFollower_publishesNothing() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        a.scheduler().renewSweepLeadership();
        b.scheduler().renewSweepLeadership();

        b.scheduler().releaseSweepLeadership();

        assertThat(events(b)).isEmpty();
        assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(MultiPodTestSupport.owner(1));
        assertThat(reasons(a)).containsExactly(GAINED);
    }

    @Test
    @DisplayName("küme kaydı yoksa (tek pod / eski davranış) olay yok; kira sorgusu hata verirse (yerel koşu) olay yok")
    void noStoreOrDbError_publishesNothing() {
        Pod legacy = mp.legacyPod();
        assertThat(legacy.scheduler().isSweepLeader()).isTrue();
        legacy.scheduler().renewSweepLeadership();
        legacy.scheduler().releaseSweepLeadership();
        assertThat(events(legacy)).isEmpty();

        Pod b = mp.pod(2);
        mp.jdbc().execute("DROP TABLE monitor_check_schedule");
        assertThat(b.scheduler().isSweepLeader()).as("DB hatası → yerel koşu").isTrue();
        b.scheduler().renewSweepLeadership();
        assertThat(events(b)).isEmpty();
    }

    @Test
    @DisplayName("dinleyici hata verirse liderlik kararı değişmez (yutulur), olay yine bir kez yayınlanmış olur")
    void listenerFailure_doesNotChangeTheDecision() {
        Pod a = mp.pod(1);
        a.events().listen(SweepLeadershipChangedEvent.class, e -> {
            throw new IllegalStateException("dinleyici bozuk");
        });

        assertThat(a.scheduler().isSweepLeader()).isTrue();
        mp.advance(RENEW);
        a.scheduler().renewSweepLeadership();

        assertThat(reasons(a)).containsExactly(GAINED);
        assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(MultiPodTestSupport.owner(1));
    }

    @Test
    @DisplayName("iki pod aynı anda yenilerken tek GAINED; devirler boyunca aynı anda en çok bir pod lider, olaylar dönüşümlü")
    void twoPods_exactlyOneLeaderAtATime() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        List<Pod> both = List.of(a, b);

        // Kararlı dönem: iki pod her turda AYNI ANDA yeniler — tek alış, kayıp yok.
        for (int round = 0; round < 20; round++) {
            mp.race(() -> { a.scheduler().renewSweepLeadership(); return null; },
                    () -> { b.scheduler().renewSweepLeadership(); return null; });
            assertLeaderInvariant(both);
            mp.advance(RENEW);
        }
        assertThat(events(a).size() + events(b).size()).as("kararlı dönemde tek geçiş").isEqualTo(1);

        // Devirler: arada bir pod "duraklar" (GC, dolu havuz — yenilemez; çoğunlukla lider); duraklama kira süresini
        // aşınca diğeri devralır, duraklayan dönünce LOST görür. Yalnız İKİ pod da yenilediği turlardan sonra değişmez
        // sınanır (duraklayan lider, dönüp yoklayana kadar kaybı bilemez — kiranın doğası).
        Random rnd = new Random(20261021L);
        Pod paused = null;
        int pausedRounds = 0;
        for (int round = 0; round < 120; round++) {
            if (paused == null && rnd.nextInt(6) == 0) {
                Pod leader = believesLeader(a) ? a : b;
                paused = rnd.nextInt(4) == 0 ? (leader == a ? b : a) : leader;
                pausedRounds = 1 + rnd.nextInt(6);                    // 1..6 tur × TTL/3 → ≥ 3 turda kira dolar
            }
            if (paused == null) {
                mp.race(() -> { a.scheduler().renewSweepLeadership(); return null; },
                        () -> { b.scheduler().renewSweepLeadership(); return null; });
                assertLeaderInvariant(both);
            } else {
                Pod active = paused == a ? b : a;
                active.scheduler().renewSweepLeadership();
                if (--pausedRounds == 0) paused = null;
            }
            mp.advance(RENEW);
        }

        for (Pod p : both) assertAlternates(p);
        long gains = both.stream().mapToLong(p -> reasons(p).stream().filter(GAINED::equals).count()).sum();
        long losses = both.stream().mapToLong(p -> reasons(p).stream().filter(LOST::equals).count()).sum();
        assertThat(gains - losses).as("şu an tam bir lider").isEqualTo(1);
        assertThat(gains).as("en az bir devir yaşandı").isGreaterThan(1);
    }

    /** İki pod da güncel yoklamayı yaptıktan sonra: tam bir pod lider olduğuna inanır ve kira satırı onundur. */
    private void assertLeaderInvariant(List<Pod> pods) {
        List<Pod> leaders = new ArrayList<>();
        for (Pod p : pods) if (believesLeader(p)) leaders.add(p);
        assertThat(leaders).as("aynı anda tek lider").hasSize(1);
        assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(leaders.get(0).owner());
    }

    /** Bir pod'un olayları GAINED, (LOST|RELEASED), GAINED … sırasıyla gelir; leader bayrağı nedenle uyumlu. */
    private static void assertAlternates(Pod p) {
        boolean leader = false;
        for (SweepLeadershipChangedEvent e : events(p)) {
            assertThat(e.leader()).as("%s: %s", p.owner(), e).isEqualTo(!leader);
            if (leader) assertThat(e.reason()).isIn(LOST, RELEASED);
            else assertThat(e.reason()).isEqualTo(GAINED);
            assertThat(e.owner()).isEqualTo(p.owner());
            leader = e.leader();
        }
    }
}
