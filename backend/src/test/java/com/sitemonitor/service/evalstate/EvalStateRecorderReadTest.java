package com.sitemonitor.service.evalstate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.evalstate.EvalStateSource.RecoveryValues;
import com.sitemonitor.service.evalstate.EvalStateSource.TypeValues;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Çok pod Sürüm 3-2 A — kaydedicinin devirde okuma tarafı (tasarım R3-2 §2.2 / §2.3 / §3.1 / §5.1 / §8): öncül satır kazanan
 * CAS'la yakalanır; işaretli nabız yalnız ayar açıkken; kuşak eksiksizliği (oynatma → boşaltma → eksiksiz; düşüş / kanca
 * hatası → eksik → yeniden oynatma; ayar kuşak ortasında açılınca oynatma); kalıcı an; devir yardımcısı (işaret son ifade,
 * NOT_QUIESCENT, INCOMPLETE, FENCED, NO_GENERATION, bütçe → BUDGET_EXCEEDED + işaret yok, halef CAS'ından sonra geç işaret
 * zararsız); işaret sütunları yokken 3-1 ifadelerine düşüş; GAINED'den sonra yükleme (FULL / OBSERVATIONS / NONE) ve
 * {@code eval-state-load} yayını; GAINED / kapanış yüzü (yalnız bellek). Her test kendi H2 veritabanında.
 */
class EvalStateRecorderReadTest {

    static final String A = "pod-a-00000001";
    static final String B = "pod-b-00000002";
    static final String PORT = "PORT_DOWN";
    static final String RK = PORT + ":db.example";

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicBoolean on = new AtomicBoolean(true);
    final AtomicReference<EvalStateMode> mode = new AtomicReference<>(EvalStateMode.WRITE);
    final List<EvalStateRecorder> built = new ArrayList<>();
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void setUp() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        mp.jdbc().execute(ClusterHealthStore.OBS_DDL);
        logger = (Logger) LoggerFactory.getLogger(EvalStateRecorder.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        for (EvalStateRecorder r : built) r.stop();
        mp.close();
    }

    /** Bir pod: kendi lider bayrağı, sahte kaynak, kaydedici. */
    final class Pod {
        final String writer;
        final AtomicBoolean leader = new AtomicBoolean(true);
        final LoadFakeSource src = new LoadFakeSource();
        final EvalStateRecorder rec;

        Pod(String writer, JdbcTemplate jdbc, int maxPending, ClusterHealthStore store) {
            this.writer = writer;
            rec = EvalStateRecorder.builder(jdbc).writer(writer).leaderHint(leader::get).mode(mode::get)
                    .clock(mp.clockSupplier()).readEnabled(on::get).flushMs(50).maxPending(maxPending)
                    .observationStore(store).build();
            rec.bindSource(src);
            src.sink = rec;
            src.clock = mp.clockSupplier();
            built.add(rec);
        }

        void gained() {
            rec.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, writer, mp.now(),
                    SweepLeadershipChangedEvent.GAINED));
        }

        /** Bellek: bir kurtarma sayacı, bastırma açık tür, kardeş DOWN. */
        void memory(int count) {
            src.recovery.put(RK, new RecoveryValues(count, mp.now() - 1_000, null, null));
            src.types.put(PORT, new TypeValues(true, true, 30_000L));
            src.down(PORT, 7, "db.example", EvalStateSource.ERROR_NETWORK, mp.now() - 2_000);
        }

        /** Zarif kapanış: devir aç, lider ipucu düşer, boşalt. */
        HandoverResult handover(boolean quiet) {
            rec.openHandover();
            leader.set(false);
            return rec.flushForHandover(EvalStateSink.HANDOVER_FLUSH_MS, quiet);
        }
    }

    Pod pod(String writer) {
        return new Pod(writer, mp.jdbc(), EvalStateRecorder.MAX_PENDING, new ClusterHealthStore(mp.jdbc()));
    }

    Map<String, Object> star() {
        return mp.jdbc().queryForMap("SELECT gen, writer, updated_ms, state_gen, flushed_ms, handover_ms FROM "
                + "monitor_eval_type_state WHERE alert_type = '*'");
    }

    Integer countIn(long gen) {
        List<Integer> v = mp.jdbc().queryForList("SELECT recovery_up_count FROM monitor_eval_state WHERE alert_type = ? "
                + "AND domain = 'db.example' AND gen = ?", Integer.class, PORT, gen);
        return v.isEmpty() ? null : v.get(0);
    }

    // ── İşaretli nabız + eksiksizlik ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ayar KAPALI: 3-1 nabzı — işaret sütunları hiç yazılmaz, oynatma yok, yükleme yüzü sorulmaz")
    void settingOff_noMarkerNoReplay() {
        on.set(false);
        Pod a = pod(A);
        a.memory(2);
        a.src.recovery.forEach((k, v) -> a.rec.recovery(k, PORT, "db.example"));
        assertThat(a.rec.flushNow()).isTrue();
        mp.advance(61_000);
        assertThat(a.rec.flushNow()).isTrue();
        Map<String, Object> s = star();
        assertThat(s.get("state_gen")).isNull();
        assertThat(s.get("flushed_ms")).isNull();
        assertThat(s.get("handover_ms")).isNull();
        assertThat(a.src.replays).hasValue(0);
        assertThat(a.rec.generationComplete()).isFalse();
        assertThat(a.rec.handoverReady()).isFalse();
        assertThat(a.rec.flushForHandover(700, true)).as("kapalıyken devir adımı yok").isEqualTo(HandoverResult.SKIPPED);
        assertThat(a.rec.lastHandover()).isNull();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Devir öncesi"));
    }

    @Test
    @DisplayName("Ayar AÇIK: kuşak alınınca bellek oynatılır; ilk boşaltma eksiksiz yapar; işaretli nabız state_gen + kalıcı an yazar")
    void settingOn_replayThenCompleteThenMarked() {
        Pod a = pod(A);
        a.memory(2);
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(a.src.replays).as("CAS'tan sonra oynatıldı").hasValue(1);
        assertThat(countIn(1)).as("oynatma aynı boşaltmada yazıldı").isEqualTo(2);
        assertThat(a.rec.generationComplete()).isTrue();
        Map<String, Object> s = star();
        assertThat(s.get("state_gen")).isEqualTo(1L);
        assertThat(s.get("flushed_ms")).as("ilk nabız oynatmadan ÖNCE — kuşak henüz eksik").isNull();
        mp.advance(5_000);
        assertThat(a.rec.flushNow()).isTrue();
        s = star();
        assertThat(s.get("flushed_ms")).as("bekleyen yok → kalıcı an = şimdi").isEqualTo(mp.now());
        assertThat(s.get("handover_ms")).isNull();
        assertThat(a.rec.durableAsOfMs()).isEqualTo(mp.now());
        assertThat(a.rec.handoverReady()).isTrue();
        assertThat(a.rec.status().readEnabled()).isTrue();
        assertThat(a.rec.status().handoverReady()).isTrue();
    }

    @Test
    @DisplayName("Kalıcı an: bekleyen işlem varken nabız SON başarılı boşaltmanın güvenli anını yazar (şimdi değil)")
    void durableAsOfIsTheLastFlushWhilePending() {
        Pod a = pod(A);
        a.memory(1);
        assertThat(a.rec.flushNow()).isTrue();
        long firstFlush = mp.now();
        assertThat(a.rec.durableAsOfMs()).isEqualTo(firstFlush);
        mp.advance(3_000);
        a.src.recovery.put(RK, new RecoveryValues(2, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");   // bekleyen işlem
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(star().get("flushed_ms")).as("nabız boşaltmanın başında, bekleyen var → önceki güvenli an")
                .isEqualTo(firstFlush);
        assertThat(a.rec.durableAsOfMs()).as("bu boşaltma bitti").isEqualTo(mp.now());
    }

    @Test
    @DisplayName("Düşüş (üst sınır) → kuşak eksik: nabız flushed_ms = NULL; yazıcı turu yeniden oynatır, sonra yeniden eksiksiz")
    void dropMakesIncompleteThenReplayRepairs() {
        Pod a = new Pod(A, mp.jdbc(), 3, null);   // bellek 3 işlem (kurtarma, tür, kardeş) — ilk oynatma sığar
        a.memory(2);
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(a.rec.generationComplete()).isTrue();
        for (String d : List.of("x", "y", "z", "w")) {   // sınır 3 → dördüncü anahtar (w) düşer
            a.src.recovery.put(PORT + ":" + d + ".example", new RecoveryValues(1, 1L, null, null));
            a.rec.recovery(PORT + ":" + d + ".example", PORT, d + ".example");
        }
        assertThat(a.rec.status().dropped()).isEqualTo(1);
        assertThat(a.rec.generationComplete()).isFalse();
        assertThat(a.rec.handoverReady()).isFalse();
        mp.advance(1_000);
        assertThat(a.rec.flushNow()).as("x, y, z yazılır; kuyruk yarıdan doluyken oynatma bekler").isTrue();
        assertThat(star().get("flushed_ms")).isNull();
        assertThat(wCount()).as("düşen anahtar henüz yok").isNull();
        int before = a.src.replays.get();
        a.rec.tick();   // yazıcı turu: yeniden oynat + boşalt
        assertThat(a.src.replays.get()).isGreaterThan(before);
        a.rec.flushNow();
        assertThat(wCount()).as("düşen anahtar oynatmayla yazıldı").isEqualTo(1);
        assertThat(a.rec.generationComplete()).isTrue();
    }

    @Test
    @DisplayName("Üst sınır 1 (yarısı 0): düşüşten sonra kuyruk boşalınca yine yeniden oynatılır (sonsuza dek eksik kalmaz)")
    void tinyPendingCapStillReplays() {
        Pod a = new Pod(A, mp.jdbc(), 1, null);
        a.src.recovery.put(RK, new RecoveryValues(1, 1L, null, null));
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(a.rec.generationComplete()).isTrue();
        a.src.recovery.put(PORT + ":x.example", new RecoveryValues(1, 1L, null, null));
        a.rec.recovery(PORT + ":x.example", PORT, "x.example");
        a.src.recovery.put(PORT + ":w.example", new RecoveryValues(1, 1L, null, null));
        a.rec.recovery(PORT + ":w.example", PORT, "w.example");   // düşer
        assertThat(a.rec.generationComplete()).isFalse();
        a.rec.flushNow();
        a.rec.tick();
        a.rec.flushNow();
        assertThat(wCount()).isEqualTo(1);
        assertThat(a.rec.generationComplete()).isTrue();
    }

    Integer wCount() {
        List<Integer> v = mp.jdbc().queryForList("SELECT recovery_up_count FROM monitor_eval_state WHERE domain = 'w.example'",
                Integer.class);
        return v.isEmpty() ? null : v.get(0);
    }

    @Test
    @DisplayName("Bellekten çıkmış anahtarın sıfırlaması düşerse tablo bayat-yüksek kalırdı — yeniden oynatma bu kuşağın anahtarlarını da yeniden okur")
    void lostResetOfRemovedKeyIsRepairedByReplay() {
        Pod a = new Pod(A, mp.jdbc(), 3, null);
        a.memory(3);
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(countIn(1)).isEqualTo(3);
        // Kuyruk dolu (üç başka anahtar bekliyor) iken bellek RK'yı sıfırlar → sıfırlama işlemi düşer
        for (String d : List.of("o1", "o2", "o3")) {
            a.src.recovery.put(PORT + ":" + d + ".example", new RecoveryValues(1, 1L, null, null));
            a.rec.recovery(PORT + ":" + d + ".example", PORT, d + ".example");
        }
        a.src.recovery.remove(RK);
        a.rec.recovery(RK, PORT, "db.example");
        assertThat(a.rec.status().dropped()).isEqualTo(1);
        mp.advance(1_000);
        a.rec.flushNow();   // o1..o3 yazılır
        assertThat(countIn(1)).as("sıfırlama düştü — tablo hâlâ 3").isEqualTo(3);
        a.rec.tick();   // yeniden oynatma: kaynak RK'yı artık oynatmaz ama bu kuşakta görülen anahtar yeniden okunur
        a.rec.flushNow();
        assertThat(countIn(1)).as("sıfırlama yazıldı (0) — devirde bayat sayaç yüklenmez").isZero();
        assertThat(a.rec.generationComplete()).isTrue();
    }

    @Test
    @DisplayName("Kanca hatası (kaynak sayacı arttı) → kuşak eksik, sonraki turda yeniden oynatılır")
    void hookFailureMakesIncomplete() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        assertThat(a.rec.generationComplete()).isTrue();
        a.src.hookFailures = 1;
        a.src.recovery.put(RK, new RecoveryValues(3, 1L, null, null));
        a.rec.recovery(RK, PORT, "db.example");
        a.rec.flushNow();
        assertThat(a.rec.generationComplete()).as("kanca sayacı oynatmadan beri değişti").isFalse();
        int before = a.src.replays.get();
        a.rec.tick();
        assertThat(a.src.replays.get()).as("kanca kaynaklı yeniden oynatma seyreltilir (L-5)").isEqualTo(before);
        assertThat(a.rec.generationComplete()).isFalse();
        mp.advance(EvalStateRecorder.REREPLAY_MIN_INTERVAL_MS);
        a.rec.tick();
        a.rec.flushNow();
        assertThat(a.src.replays.get()).isEqualTo(before + 1);
        assertThat(a.rec.generationComplete()).isTrue();
    }

    @Test
    @DisplayName("Ayar kuşak ORTASINDA açılır: yazıcı turu bir kez oynatır, sonra eksiksiz; oynatma hatası = yeniden denenir")
    void settingTurnedOnMidGeneration() {
        on.set(false);
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        assertThat(a.src.replays).hasValue(0);
        a.src.throwOnReplay = true;
        on.set(true);
        a.rec.tick();
        assertThat(a.src.replays).hasValue(1);
        assertThat(a.rec.generationComplete()).as("oynatma düştü").isFalse();
        a.src.throwOnReplay = false;
        a.rec.tick();
        assertThat(a.src.replays).hasValue(2);
        assertThat(a.rec.generationComplete()).isTrue();
        assertThat(countIn(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("GAINED: ayar açıkken yazıcı turu eski bir geri çekilmeyi beklemez (yük 20 sn kapısına yetişsin); kapalıyken 3-1 zamanlaması")
    void gainedClearsBackoffOnlyWhenOn() {
        for (boolean read : new boolean[] {true, false}) {
            on.set(read);
            String owner = read ? "pod-on-00000001" : "pod-off-00000002";
            mp.jdbc().update("DELETE FROM monitor_check_schedule");
            EvalStateRecorder r = EvalStateRecorder.builder(mp.jdbc()).writer(owner).leaderHint(() -> true).mode(mode::get)
                    .clock(mp.clockSupplier()).readEnabled(on::get).leaseOwner(owner).flushMs(50).build();
            r.bindSource(new LoadFakeSource());
            built.add(r);
            r.tick();   // kira yok → kuşak alınamadı → geri çekilme (1 sn)
            assertThat(r.generation()).isZero();
            mp.jdbc().update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) "
                    + "VALUES (?,?,?,?)", EvalStateSql.LEASE_KEY, mp.now() + 180_000, owner, mp.now());
            r.tick();
            assertThat(r.generation()).as("geri çekilme sürüyor").isZero();
            r.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, owner, mp.now(), SweepLeadershipChangedEvent.GAINED));
            r.tick();
            if (read) {
                assertThat(r.generation()).as("açık: GAINED geri çekilmeyi sildi").isPositive();
            } else {
                assertThat(r.generation()).as("kapalı: 3-1 gibi geri çekilme sürer").isZero();
            }
        }
    }

    // ── Öncül satır + yükleme ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Temiz devir: A boşaltır + işaretler; B'nin CAS'ı A'nın satırını yakalar → FULL yük uygulanır (sayaç, kardeş, bastırma, tur)")
    void cleanHandover_successorLoadsFull() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        assertThat(a.handover(true)).isEqualTo(HandoverResult.WRITTEN);
        Map<String, Object> s = star();
        assertThat(s.get("handover_ms")).isEqualTo(s.get("updated_ms")).isEqualTo(s.get("flushed_ms"));
        assertThat(s.get("state_gen")).isEqualTo(1L);

        mp.advance(2_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(55);
        assertThat(b.rec.flushNow()).isTrue();
        assertThat(b.src.applied).hasSize(1);
        EvalLoad l = b.src.applied.get(0);
        assertThat(l.token()).isEqualTo(55);
        assertThat(l.full()).isTrue();
        assertThat(l.recovery()).containsKey(RK);
        assertThat(l.recovery().get(RK).upCount()).isEqualTo(2);
        assertThat(l.siblings()).containsKey(EvalLoad.siblingKey(PORT, 7));
        assertThat(l.siblings().get(EvalLoad.siblingKey(PORT, 7)).errorClass()).isEqualTo(EvalStateSource.ERROR_NETWORK);
        assertThat(l.suppressionActive()).containsExactly(PORT);
        assertThat(l.roundSeen()).containsExactly(PORT);
        assertThat(l.intervals()).containsEntry(PORT, 30_000L);
        assertThat(l.meta().fromGeneration()).isEqualTo(1);
        assertThat(l.meta().fromWriter()).isEqualTo(A);
        assertThat(l.meta().handoverAgeMs()).isEqualTo(2_000L);
        // Uygulanan sayaç B'nin yeni kuşağına oynatıldı (sonraki devir de taşır)
        assertThat(countIn(2)).isEqualTo(2);
        assertThat(b.rec.generationComplete()).isTrue();
        // Rapor küme gözlemi olarak yayımlandı
        ClusterHealthStore.Observation obs = new ClusterHealthStore(mp.jdbc()).read(EvalLoadReport.OBSERVATION).orElseThrow();
        EvalLoadReport pub = EvalLoadReport.fromJson(obs.payload());
        assertThat(pub.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
        assertThat(pub.recoveryCounters()).isEqualTo(1);
        assertThat(b.rec.status().lastLoad()).isEqualTo(b.src.last);
    }

    @Test
    @DisplayName("B isteği: uygulama (yazıcı flushLock tutarken) sürerken uçuştaki değerlendirme kuyruğa alabilir — kilit beklemez, işlem aynı boşaltmada yazılır")
    void enqueueDuringApplyIsNotBlocked() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        Pod b = pod(B);
        b.gained();
        b.src.arm(12);
        AtomicBoolean enqueuedInTime = new AtomicBoolean();
        b.src.onApply = () -> {
            Thread sweep = new Thread(() -> {
                b.src.recovery.put(PORT + ":live.example", new RecoveryValues(1, mp.now(), null, null));
                b.rec.recovery(PORT + ":live.example", PORT, "live.example");
            }, "sweep-during-apply");
            sweep.start();
            try {
                sweep.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            enqueuedInTime.set(!sweep.isAlive());
        };
        assertThat(b.rec.flushNow()).isTrue();
        assertThat(enqueuedInTime).as("kuyruğa alma yazıcının kilidini beklemedi").isTrue();
        assertThat(b.src.applied).hasSize(1);
        assertThat(mp.jdbc().queryForObject("SELECT recovery_up_count FROM monitor_eval_state WHERE domain = 'live.example' "
                + "AND gen = 2", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("İki temiz devir A → B → C: oynatma yeni kuşağı eksiksiz yaptı — sayaç ikinci devirde de taşınır")
    void twoCleanHandoversCarryState() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        mp.advance(1_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(1);
        b.rec.flushNow();
        mp.advance(1_000);
        assertThat(b.handover(true)).isEqualTo(HandoverResult.WRITTEN);
        mp.advance(1_000);
        Pod c = pod("pod-c-00000003");
        c.gained();
        c.src.arm(2);
        c.rec.flushNow();
        assertThat(c.src.applied).hasSize(1);
        assertThat(c.src.applied.get(0).full()).isTrue();
        assertThat(c.src.applied.get(0).recovery().get(RK).upCount()).isEqualTo(2);
        assertThat(c.src.applied.get(0).meta().fromWriter()).isEqualTo(B);
    }

    @Test
    @DisplayName("Çökme (devir işareti yok): halef OBSERVATIONS / no_handover — sayaç, bastırma, tur taşınmaz; kardeş + aralık taşınır")
    void crashTakeoverLoadsObservations() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.rec.flushNow();   // işaretli nabız: kalıcı an yazıldı, devir yok
        mp.advance(60_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(9);
        b.rec.flushNow();
        EvalLoad l = b.src.applied.get(0);
        assertThat(l.tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(l.meta().partialReason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
        assertThat(l.recovery()).isEmpty();
        assertThat(l.suppressionActive()).isEmpty();
        assertThat(l.roundSeen()).isEmpty();
        assertThat(l.siblings()).containsKey(EvalLoad.siblingKey(PORT, 7));
        assertThat(l.intervals()).containsEntry(PORT, 30_000L);
    }

    @Test
    @DisplayName("Önceki sahip ayar kapalıyken yazdı (3-1 nabzı): halef NONE / previous_unmarked — kapı açılır, okuma yapılmaz")
    void previousUnmarked_skipped() {
        on.set(false);
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        on.set(true);
        mp.advance(1_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(3);
        b.rec.flushNow();
        assertThat(b.src.applied).isEmpty();
        assertThat(b.src.skipped).hasSize(1);
        EvalLoadReport r = b.src.skipped.get(0);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
        assertThat(r.fromGeneration()).isEqualTo(1L);
        assertThat(r.fromPod()).isEqualTo(A);
        assertThat(b.src.evalLoadToken()).as("kapı açıldı").isZero();
    }

    @Test
    @DisplayName("Belirteç yoksa (MOS kurmadı) yükleme yapılmaz; kuruluyken ayar kapanırsa → setting_turned_off, SQL yok")
    void noTokenNoLoad_settingTurnedOffSkips() {
        Pod a = pod(A);
        a.memory(1);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        Pod b = pod(B);
        b.gained();
        b.rec.flushNow();
        assertThat(b.src.applied).isEmpty();
        assertThat(b.src.skipped).isEmpty();

        Pod c = pod("pod-c-00000003");
        c.gained();
        c.src.arm(4);
        on.set(false);
        c.rec.flushNow();
        assertThat(c.src.skipped).extracting(EvalLoadReport::reason).containsExactly(EvalLoadReport.REASON_SETTING_TURNED_OFF);
        assertThat(c.src.replays).as("kapalı → oynatma yok").hasValue(0);
    }

    @Test
    @DisplayName("Okuma hatası → NONE / read_failed (WARN, teknik özet; değer yok), yük uygulanmaz, kapı açılır")
    void readFailureSkipsWithWarn() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        ds.failWhen = q -> q.contains("FROM monitor_last_status WHERE gen = ?");
        Pod b = new Pod(B, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        b.gained();
        b.src.arm(8);
        b.rec.flushNow();
        assertThat(b.src.applied).isEmpty();
        assertThat(b.src.skipped).extracting(EvalLoadReport::reason).containsExactly(EvalLoadReport.REASON_READ_FAILED);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("devirde okunamadı") && !e.getFormattedMessage().contains("db.example"));
    }

    // ── Devir yardımcısı ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Devir: kapanıştan sonra biten değerlendirmenin işlemi alınır (devir açık), yazılır, kendi zincirleri bırakılır, işaret SON ifade")
    void handoverWritesDataAbandonsOwnChainsThenMarker() {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.flushNow();
        // Süren zincir satırı (bu kuşak, bu yazan)
        new EvalStateSql(mp.jdbc()).writeChains(List.of(new EvalStateSql.ChainRow(EvalStateKeys.keyHash("c1"), "c1", 1, PORT,
                "db.example", null, "port", 7L, 1, 3, 30_000L, "2026-10-28T00:00:00", null, null, null, false,
                EvalStateSql.STATE_RUNNING, 1L, 1)), A, 1, mp.now());
        a.rec.openHandover();
        a.leader.set(false);
        a.src.recovery.put(RK, new RecoveryValues(3, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");   // lider ipucu false ama devir açık → kuyruğa girer
        ds.clear();
        mp.advance(500);
        assertThat(a.rec.flushForHandover(EvalStateSink.HANDOVER_FLUSH_MS, true)).isEqualTo(HandoverResult.WRITTEN);
        assertThat(countIn(1)).isEqualTo(3);
        assertThat(mp.jdbc().queryForObject("SELECT state FROM monitor_confirmations WHERE confirm_key = 'c1'", String.class))
                .isEqualTo(EvalStateSql.STATE_ABANDONED);
        List<String> st = ds.statements();
        assertThat(st.get(st.size() - 1)).as("işaret SON ifade").isEqualTo(EvalStateSql.SQL_HANDOVER_MARK);
        assertThat(st).contains(EvalStateSql.SQL_ABANDON_OWN);
        assertThat(ds.executed).as("hepsi yardımcı iş parçacığında — kapanan iş parçacığı veritabanına dokunmaz")
                .allMatch(e -> e.thread().equals(EvalStateRecorder.HANDOVER_THREAD_NAME));
        Map<String, Object> s = star();
        assertThat(s.get("handover_ms")).isEqualTo(mp.now()).isEqualTo(s.get("updated_ms")).isEqualTo(s.get("flushed_ms"));
        assertThat(a.rec.lastHandover()).isEqualTo(HandoverResult.WRITTEN);
        assertThat(a.rec.status().lastHandover()).isEqualTo(HandoverResult.WRITTEN);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().startsWith("Devir öncesi kalıcı durum boşaltıldı ve işaretlendi (kuşak 1"));
        // RELEASED devri kapatır: artık kuyruğa alınmaz
        a.rec.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, A, mp.now(), SweepLeadershipChangedEvent.RELEASED));
        a.rec.recovery(RK, PORT, "db.example");
        assertThat(a.rec.status().pending()).isZero();
    }

    @Test
    @DisplayName("Devir: değerlendirmeler durulmadı → veri yazılır, işaret YOK (NOT_QUIESCENT); kuşak eksik → INCOMPLETE")
    void handoverNotQuiescentAndIncomplete() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(500);
        assertThat(a.handover(false)).isEqualTo(HandoverResult.NOT_QUIESCENT);
        assertThat(star().get("handover_ms")).isNull();
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage()
                .equals("Devir öncesi kalıcı durum işaretlenemedi (not_quiescent) — halef yalnız gözlemleri ya da hiçbir "
                        + "durumu yüklemez"));

        Pod b = pod(B);
        b.src.throwOnReplay = true;   // oynatma düşer → kuşak eksik
        b.memory(1);
        b.rec.flushNow();
        assertThat(b.rec.generationComplete()).isFalse();
        assertThat(b.rec.handoverReady()).isFalse();
        assertThat(b.handover(true)).isEqualTo(HandoverResult.INCOMPLETE);
        assertThat(star().get("handover_ms")).isNull();
    }

    @Test
    @DisplayName("Devir: kuşak yok → NO_GENERATION; daha yeni kuşak alınmış → FENCED (hiçbir şey yazılmaz)")
    void handoverNoGenerationAndFenced() {
        Pod a = pod(A);
        assertThat(a.handover(true)).isEqualTo(HandoverResult.NO_GENERATION);
        Pod b = pod(B);
        b.memory(1);
        b.rec.flushNow();
        Pod c = pod("pod-c-00000003");
        c.rec.flushNow();   // kuşak 2 — B çitlendi
        assertThat(b.handover(true)).isEqualTo(HandoverResult.FENCED);
        assertThat(star().get("writer")).isEqualTo("pod-c-00000003");
        assertThat(star().get("handover_ms")).isNull();
    }

    @Test
    @DisplayName("Devir bütçesi: yavaş veritabanı → ≈ bütçede BUDGET_EXCEEDED, yardımcı sonra bitse de işaret YAZILMAZ")
    void handoverBudgetExceeded_noMarker() throws Exception {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.flushNow();
        ds.blockWhen = q -> q.startsWith("UPDATE monitor_eval_type_state SET updated_ms = ?, updated_at = ?, state_gen");
        long t0 = System.nanoTime();
        assertThat(a.handover(true)).isEqualTo(HandoverResult.BUDGET_EXCEEDED);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("bütçe 700 ms").isLessThan(Duration.ofMillis(2_000));
        ds.release();
        // Yardımcı bitene dek bekle (kilit bırakılır)
        long until = System.currentTimeMillis() + 10_000;
        while (ds.statements().stream().noneMatch(q -> q.equals(EvalStateSql.SQL_ABANDON_OWN))
                && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
        assertThat(ds.statements()).doesNotContain(EvalStateSql.SQL_HANDOVER_MARK);
        assertThat(star().get("handover_ms")).isNull();
        assertThat(a.rec.lastHandover()).isEqualTo(HandoverResult.BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("Geç işaret zararsız: halef CAS'ından sonra eski kuşağın işareti 0 satır; halef işaretsiz satırı görür")
    void lateMarkerAfterSuccessorCasIsHarmless() {
        Pod a = pod(A);
        a.memory(1);
        a.rec.flushNow();
        Pod b = pod(B);
        b.rec.flushNow();   // kuşak 2
        assertThat(new EvalStateSql(mp.jdbc()).handoverMark(1, A, mp.now())).isFalse();
        assertThat(star().get("gen")).isEqualTo(2L);
        assertThat(star().get("handover_ms")).isNull();
    }

    // ── Sütunlar yok → 3-1 ifadeleri ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İşaret sütunları yok (ilk açılış): 3-1 CAS + nabız, yazım sürer; yükleme columns_missing; sütunlar gelince işaretli nabız")
    void columnsMissingFallsBackTo31() {
        for (String c : EvalStateSql.MARKER_COLUMNS) mp.jdbc().execute("ALTER TABLE monitor_eval_type_state DROP COLUMN " + c);
        Pod a = pod(A);
        a.gained();
        a.src.arm(6);
        a.memory(2);
        assertThat(a.rec.flushNow()).as("3-1 yolu yazar").isTrue();
        assertThat(a.rec.markerColumnsMissing()).isTrue();
        assertThat(a.src.skipped).extracting(EvalLoadReport::reason).containsExactly(EvalLoadReport.REASON_COLUMNS_MISSING);
        assertThat(countIn(1)).isEqualTo(2);
        assertThat(a.rec.handoverReady()).isFalse();
        mp.advance(1_000);
        assertThat(a.rec.flushNow()).isTrue();
        for (String c : EvalStateSql.MARKER_COLUMNS) {
            mp.jdbc().execute("ALTER TABLE monitor_eval_type_state ADD COLUMN " + c + " BIGINT");
        }
        ReflectionTestUtils.setField(a.rec, "nextColumnsCheckNs", System.nanoTime() - 1);
        mp.advance(1_000);
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(a.rec.markerColumnsMissing()).isFalse();
        assertThat(star().get("state_gen")).isEqualTo(1L);
        assertThat(a.rec.handoverReady()).isTrue();
    }

    // ── Yayın ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yayın: yalnız daha yeni rapor; none/setting_off asla; ayar kapalıyken hiç; kip off iken hiç")
    void publishRules() {
        Pod a = pod(A);
        ClusterHealthStore store = new ClusterHealthStore(mp.jdbc());
        a.src.last = EvalLoadReport.none(mp.now(), A, EvalLoadReport.REASON_SETTING_OFF);
        a.rec.flushNow();
        assertThat(store.read(EvalLoadReport.OBSERVATION)).isEmpty();
        a.src.last = EvalLoadReport.none(mp.now() + 1, A, EvalLoadReport.REASON_TIMEOUT);
        on.set(false);
        a.rec.flushNow();
        assertThat(store.read(EvalLoadReport.OBSERVATION)).as("ayar kapalı").isEmpty();
        on.set(true);
        mode.set(EvalStateMode.OFF);
        a.rec.flushNow();
        assertThat(store.read(EvalLoadReport.OBSERVATION)).as("kip off").isEmpty();
        mode.set(EvalStateMode.WRITE);
        a.rec.tick();
        ClusterHealthStore.Observation o = store.read(EvalLoadReport.OBSERVATION).orElseThrow();
        assertThat(EvalLoadReport.fromJson(o.payload()).reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(o.instanceId()).isEqualTo(A);
        // Aynı / daha eski rapor yeniden yazılmaz
        mp.jdbc().update("DELETE FROM cluster_observations");
        a.rec.flushNow();
        assertThat(store.read(EvalLoadReport.OBSERVATION)).isEmpty();
    }

    // ── GAINED / kapanış yüzü: yalnız bellek ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadBlocker: hazır → null; kip off → mode_off; tablolar yok → tables_missing; kapanış → recorder_closing — SQL yok")
    void loadBlockerReasons() {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        assertThat(a.rec.loadBlocker()).isNull();
        mode.set(EvalStateMode.OFF);
        assertThat(a.rec.loadBlocker()).isEqualTo(EvalLoadReport.REASON_MODE_OFF);
        mode.set(EvalStateMode.VERIFY);
        ReflectionTestUtils.setField(a.rec, "tableMissing", true);
        assertThat(a.rec.loadBlocker()).isEqualTo(EvalLoadReport.REASON_TABLES_MISSING);
        ReflectionTestUtils.setField(a.rec, "tableMissing", false);
        a.rec.onContextClosed(null);
        assertThat(a.rec.loadBlocker()).isEqualTo(EvalLoadReport.REASON_RECORDER_CLOSING);
        assertThat(a.rec.handoverReady()).isFalse();
        a.rec.openHandover();
        assertThat(ds.executed).as("GAINED / kapanış yüzü veritabanına dokunmaz (J-2)").isEmpty();
    }

    @Test
    @DisplayName("handoverReady: ayar ∧ kuşak ∧ eksiksiz ∧ lider ∧ çit dışı değil; her biri tek başına false yapar")
    void handoverReadyTruth() {
        Pod a = pod(A);
        assertThat(a.rec.handoverReady()).as("kuşak yok").isFalse();
        a.memory(1);
        a.rec.flushNow();
        assertThat(a.rec.handoverReady()).isTrue();
        on.set(false);
        assertThat(a.rec.handoverReady()).isFalse();
        on.set(true);
        a.leader.set(false);
        assertThat(a.rec.handoverReady()).isFalse();
        a.leader.set(true);
        mode.set(EvalStateMode.OFF);
        assertThat(a.rec.handoverReady()).isFalse();
        mode.set(EvalStateMode.WRITE);
        assertThat(a.rec.handoverReady()).isTrue();
        a.rec.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, A, mp.now(), SweepLeadershipChangedEvent.LOST));
        assertThat(a.rec.handoverReady()).as("olay kuşağı bıraktı").isFalse();
    }

    @Test
    @DisplayName("Durum 20 bileşen: readEnabled, handoverReady, lastLoad (kaynaktan), lastHandover")
    void statusCarriesReadFields() {
        Pod a = pod(A);
        EvalLoadReport r = EvalLoadReport.none(1L, A, EvalLoadReport.REASON_NO_PREVIOUS_GENERATION);
        a.src.last = r;
        EvalStateSink.Status s = a.rec.status();
        assertThat(s.readEnabled()).isTrue();
        assertThat(s.handoverReady()).isFalse();
        assertThat(s.lastLoad()).isEqualTo(r);
        assertThat(s.lastHandover()).isNull();
        on.set(false);
        assertThat(a.rec.status().readEnabled()).isFalse();
        assertThatCode(() -> a.rec.flushForHandover(0, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("I-2 / J-2: ayar AÇIKKEN de kuyruğa alma çağıranın iş parçacığında bağlantı açmaz")
    void enqueueStillDbFreeWhenOn() {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.recovery(RK, PORT, "db.example");
        a.rec.typeState(PORT);
        a.rec.sweepEnd(PORT);
        a.rec.loadBlocker();
        a.rec.handoverReady();
        assertThat(ds.executed).isEmpty();
        a.rec.flushNow();
        assertThat(ds.executed).isNotEmpty();
    }

    // ── İnceleme düzeltmeleri (2026-10-28) ──────────────────────────────────────────────────────────────────────────

    /** Bir ifade (devir yardımcısında) görülene dek bekler. */
    static void awaitStatement(SqlRecordingDataSource ds, Predicate<String> q) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (ds.executed.stream().noneMatch(e -> q.test(e.sql())) && System.currentTimeMillis() < until) Thread.sleep(5);
        assertThat(ds.executed).as("beklenen ifade koştu").anyMatch(e -> q.test(e.sql()));
    }

    static void awaitParked(Thread t) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (t.getState() != Thread.State.WAITING && System.currentTimeMillis() < until) Thread.sleep(5);
        assertThat(t.getState()).as("iş parçacığı kilidi bekliyor").isEqualTo(Thread.State.WAITING);
    }

    @Test
    @DisplayName("L-3: lider ipucunu işaretten önce geçmiş bir boşaltma kilidi beklerken devir işareti yazılır → kilit "
            + "bırakılınca o boşaltma hiçbir şey yazmaz; handover_ms = updated_ms kalır; liderlik olayı temizler")
    void markerSurvivesAFlushWaitingOnTheLock() throws Exception {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        ds.clear();
        ds.blockWhen = q -> q.equals(EvalStateSql.SQL_ABANDON_OWN);   // yardımcı kilidi tutarken yavaş veritabanı
        a.rec.openHandover();
        a.leader.set(false);
        AtomicReference<HandoverResult> res = new AtomicReference<>();
        Thread closing = new Thread(() -> res.set(a.rec.flushForHandover(10_000, true)), "test-closing");
        closing.start();
        awaitStatement(ds, q -> q.equals(EvalStateSql.SQL_ABANDON_OWN));
        Thread writer = new Thread(a.rec::flushOnce, "test-writer-tick");   // ipucu denetimini önceden geçmiş yazıcı turu
        writer.start();
        awaitParked(writer);
        ds.release();
        closing.join(10_000);
        writer.join(10_000);
        assertThat(res.get()).isEqualTo(HandoverResult.WRITTEN);
        Map<String, Object> s = star();
        assertThat(s.get("handover_ms")).as("işaret silinmedi").isNotNull().isEqualTo(s.get("updated_ms"));
        assertThat(ds.executed).as("bekleyen boşaltma hiçbir ifade koşmadı")
                .noneMatch(e -> e.thread().equals("test-writer-tick"));
        mp.advance(1_000);
        assertThat(a.rec.flushOnce()).isFalse();
        assertThat(star().get("handover_ms")).isEqualTo(star().get("updated_ms"));
        // Her liderlik olayı temizler (yeniden lider olursa yazım sürer)
        a.leader.set(true);
        a.gained();
        assertThat(a.rec.flushNow()).isTrue();
        assertThat(a.rec.generation()).isEqualTo(2);
    }

    @Test
    @DisplayName("L-6: devir yazımı sürerken gelen zincir işlemi (zincirler yüklenmez) temiz devri INCOMPLETE yapmaz")
    void chainOpDuringHandoverStillWritten() throws Exception {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.rec.openHandover();
        a.leader.set(false);
        a.src.recovery.put(RK, new RecoveryValues(3, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");   // yardımcının yazacağı işlem
        Predicate<String> recoveryWrite = q -> q.startsWith("UPDATE monitor_eval_state")
                || q.startsWith("INSERT INTO monitor_eval_state");
        ds.clear();   // önceki boşaltmanın ifadeleri beklemeyi kandırmasın
        ds.blockWhen = recoveryWrite;
        AtomicReference<HandoverResult> res = new AtomicReference<>();
        Thread closing = new Thread(() -> res.set(a.rec.flushForHandover(10_000, true)), "test-closing");
        closing.start();
        awaitStatement(ds, recoveryWrite);
        a.rec.chainEnd("c-live", 5L);   // ölmekte olan liderde bir zincir adımı (devir açık → kuyruğa girer)
        ds.release();
        closing.join(10_000);
        assertThat(res.get()).isEqualTo(HandoverResult.WRITTEN);
        Map<String, Object> s = star();
        assertThat(s.get("handover_ms")).isEqualTo(s.get("updated_ms"));
        assertThat(countIn(1)).isEqualTo(3);
        assertThat(a.rec.status().pending()).as("yalnız zincir işlemi kaldı").isEqualTo(1);
    }

    @Test
    @DisplayName("L-6 karşıtı: devir yazımı sürerken gelen KURTARMA işlemi işareti engeller (INCOMPLETE)")
    void recoveryOpDuringHandoverStillIncomplete() throws Exception {
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        Pod a = new Pod(A, new JdbcTemplate(ds), EvalStateRecorder.MAX_PENDING, null);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.rec.openHandover();
        a.leader.set(false);
        a.src.recovery.put(RK, new RecoveryValues(3, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");
        Predicate<String> recoveryWrite = q -> q.startsWith("UPDATE monitor_eval_state")
                || q.startsWith("INSERT INTO monitor_eval_state");
        ds.clear();
        ds.blockWhen = recoveryWrite;
        AtomicReference<HandoverResult> res = new AtomicReference<>();
        Thread closing = new Thread(() -> res.set(a.rec.flushForHandover(10_000, true)), "test-closing");
        closing.start();
        awaitStatement(ds, recoveryWrite);
        a.src.recovery.put(RK, new RecoveryValues(0, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");   // yazımdan sonra gelen sıfırlama — yazılmadı
        ds.release();
        closing.join(10_000);
        assertThat(res.get()).isEqualTo(HandoverResult.INCOMPLETE);
        assertThat(star().get("handover_ms")).isNull();
    }

    @Test
    @DisplayName("L-5: sürekli istisna veren kanca → kuşak eksik kalır, ama yeniden oynatma dakikada en çok bir (her turda değil)")
    void persistentHookFailureReplaysAtMostOncePerMinute() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        assertThat(a.src.replays).hasValue(1);
        assertThat(a.rec.generationComplete()).isTrue();
        for (int i = 0; i < 120; i++) {   // 120 sn, saniyede bir yazıcı turu, her turda yeni kanca hatası
            a.src.hookFailures++;
            a.src.recovery.put(RK, new RecoveryValues(1 + (i % 2), mp.now(), null, null));
            a.rec.recovery(RK, PORT, "db.example");
            a.rec.tick();
            mp.advance(1_000);
        }
        assertThat(a.src.replays.get()).as("ilk oynatma + 60 sn'de bir").isEqualTo(2);
        assertThat(a.rec.generationComplete()).isFalse();
        assertThat(a.rec.handoverReady()).isFalse();
        // Kanca düzelirse bir sonraki seyreltilmiş oynatma kuşağı yeniden eksiksiz yapar
        mp.advance(EvalStateRecorder.REREPLAY_MIN_INTERVAL_MS);
        a.rec.tick();
        a.rec.flushNow();
        assertThat(a.rec.generationComplete()).isTrue();
    }

    @Test
    @DisplayName("L-5: düşüş kaynaklı yeniden oynatma seyreltilmez (kuyruk boşalınca hemen)")
    void dropReplayIsNotRateLimited() {
        Pod a = new Pod(A, mp.jdbc(), 3, null);
        a.memory(2);
        a.rec.flushNow();
        for (String d : List.of("x", "y", "z", "w")) {
            a.src.recovery.put(PORT + ":" + d + ".example", new RecoveryValues(1, 1L, null, null));
            a.rec.recovery(PORT + ":" + d + ".example", PORT, d + ".example");
        }
        a.rec.flushNow();
        int before = a.src.replays.get();
        a.rec.tick();
        assertThat(a.src.replays.get()).as("60 sn beklenmez").isGreaterThan(before);
    }

    @Test
    @DisplayName("L-5: kirli taşma (ayar açık) → seyreltilmiş boşluk: yeni kuşak + tam oynatma → yeniden eksiksiz; ayar kapalıyken kuşak değişmez")
    void dirtyOverflowRequestsAGap() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        assertThat(a.rec.generation()).isEqualTo(1);
        ReflectionTestUtils.setField(a.rec, "dirtyOverflow", true);
        a.src.recovery.put(RK, new RecoveryValues(3, mp.now(), null, null));
        a.rec.recovery(RK, PORT, "db.example");
        for (int i = 0; i < 5; i++) {
            a.rec.tick();
            mp.advance(1_000);
        }
        assertThat(a.rec.generation()).as("son oynatmadan bu yana 60 sn geçmedi — boşluk beklenir").isEqualTo(1);
        assertThat(a.rec.generationComplete()).isFalse();
        mp.advance(EvalStateRecorder.REREPLAY_MIN_INTERVAL_MS);
        for (int i = 0; i < 3; i++) {
            a.rec.tick();
            mp.advance(1_000);
        }
        assertThat(a.rec.generation()).as("boşluk → yeni kuşak").isEqualTo(2);
        assertThat(a.rec.generationComplete()).isTrue();
        assertThat(countIn(2)).as("yeni kuşağa tam oynatma").isEqualTo(3);

        on.set(false);
        Pod b = pod(B);
        b.memory(2);
        b.rec.flushNow();
        long g = b.rec.generation();
        ReflectionTestUtils.setField(b.rec, "dirtyOverflow", true);
        mp.advance(2 * EvalStateRecorder.REREPLAY_MIN_INTERVAL_MS);
        for (int i = 0; i < 3; i++) b.rec.tick();
        assertThat(b.rec.generation()).as("ayar kapalı: 3-1 davranışı, boşluk yok").isEqualTo(g);
        assertThat(b.src.replays).hasValue(0);
    }

    @Test
    @DisplayName("L-4: uygulama istisnası (MOS'ta beklenmeyen hata) → kapı HEMEN kapanır: read_failed raporu, belirteç 0, WARN")
    void applyThrowsEndsGateImmediately() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        mp.advance(1_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(21);
        b.src.onApply = () -> {
            throw new IllegalStateException("uygulama hatası db.example");
        };
        assertThat(b.rec.flushNow()).isTrue();
        assertThat(b.src.applied).isEmpty();
        assertThat(b.src.skipped).extracting(EvalLoadReport::reason).containsExactly(EvalLoadReport.REASON_READ_FAILED);
        assertThat(b.src.evalLoadToken()).as("kapı açıldı — 20 sn beklenmez").isZero();
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("devirde yüklenemedi")
                && e.getFormattedMessage().contains("IllegalStateException")
                && !e.getFormattedMessage().contains("db.example"));
    }

    @Test
    @DisplayName("L-4: eşleme hatası (DataAccessException değil) → kapı HEMEN kapanır, read_failed")
    void mapperThrowsEndsGateImmediately() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        mp.advance(1_000);
        JdbcTemplate throwing = new JdbcTemplate(mp.dataSource()) {
            @Override
            public <T> List<T> query(String q, RowMapper<T> rm, Object... args) {
                if (q.contains("FROM monitor_last_status WHERE gen = ?")) {
                    RowMapper<T> bad = (rs, n) -> {
                        throw new IllegalStateException("eşleme hatası");
                    };
                    return super.query(q, bad, args);
                }
                return super.query(q, rm, args);
            }
        };
        Pod b = new Pod(B, throwing, EvalStateRecorder.MAX_PENDING, null);
        b.gained();
        b.src.arm(22);
        b.rec.flushNow();
        assertThat(b.src.applied).isEmpty();
        assertThat(b.src.skipped).extracting(EvalLoadReport::reason).containsExactly(EvalLoadReport.REASON_READ_FAILED);
        assertThat(b.src.evalLoadToken()).isZero();
    }

    @Test
    @DisplayName("M-1: temiz devir ama gelen kutusunda değerlendirilmemiş elle sonuç → halef yalnız gözlemleri yükler "
            + "(pending_manual_results) — sayaç taşınmaz")
    void pendingInboxRowsGiveObservations() {
        mp.jdbc().execute(ManualEvalInbox.DDL);
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        assertThat(a.handover(true)).isEqualTo(HandoverResult.WRITTEN);
        mp.jdbc().update("INSERT INTO monitor_eval_inbox(monitor_type, monitor_id, manual, result_json, checked_ms, "
                + "source_pod, created_ms) VALUES (?,?,?,?,?,?,?)", ManualEvalInbox.TYPE_PORT, 7L, true, "{}", mp.now(),
                "pod-c-00000003", mp.now());
        mp.advance(2_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(31);
        b.rec.flushNow();
        EvalLoad l = b.src.applied.get(0);
        assertThat(l.tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(l.meta().partialReason()).isEqualTo(EvalLoadReport.REASON_PENDING_MANUAL_RESULTS);
        assertThat(l.recovery()).isEmpty();
        assertThat(l.siblings()).containsKey(EvalLoad.siblingKey(PORT, 7));
    }

    @Test
    @DisplayName("M-2: kaydedici yükleyiciye kaynağın pencere uzunluğu kuralını verir — tohum türün penceresiyle kesilir")
    void loaderUsesTheSourceWindowLength() {
        Pod a = pod(A);
        a.memory(2);
        a.rec.flushNow();
        mp.advance(1_000);
        a.handover(true);
        EvalStateSql w = new EvalStateSql(mp.jdbc());
        long now = mp.now();
        w.writeLastStatus(List.of(
                new EvalStateSql.LastStatusRow(PORT, "m:30", "slow.example", "443", 30L, 3L, true, false, true, "NETWORK",
                        "S", now - 40 * 60_000L, now - 40 * 60_000L, 100),
                new EvalStateSql.LastStatusRow(PORT, "m:31", "slow.example", "443", 31L, 3L, true, true, false, null, "S",
                        now - 60_000L, now - 60_000L, 101)), A, 1, now);
        mp.advance(1_000);
        Pod b = pod(B);
        b.gained();
        b.src.arm(41);
        b.rec.flushNow();
        assertThat(b.src.applied.get(0).window().get(PORT).get("slow.example").networkDown())
                .as("PORT penceresi 5 dk (tur 30 sn): 40 dk önceki ağ DOWN sayılmaz").isFalse();

        mp.advance(1_000);
        assertThat(b.handover(true)).isEqualTo(HandoverResult.WRITTEN);
        mp.advance(1_000);
        Pod c = pod("pod-c-00000003");
        c.src.windowLength = (type, interval) -> EvalWindowSeed.WINDOW_CAP_MS;   // kaynak 60 dk derse
        c.gained();
        c.src.arm(42);
        c.rec.flushNow();
        assertThat(c.src.applied.get(0).window().get(PORT).get("slow.example").networkDown())
                .as("kaynağın kuralı kullanıldı").isTrue();
    }
}
