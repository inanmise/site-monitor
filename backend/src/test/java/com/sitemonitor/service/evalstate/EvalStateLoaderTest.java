package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.evalstate.EvalLoadContinuity.Decision;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.PriorGeneration;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.TypeRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-2 A — devir yükleyicisi H2'de (tasarım R3-2 §3.4): her okuma yalnız {@code gen = prior.gen} (pencere
 * satırları hariç), katmana göre eşleme, ara lider sorgusu, üst sınır → {@code too_large}, belirteç değişince bırakma, okuma
 * hatası → {@code read_failed}, kısmi yük ASLA, en çok 5 ifade.
 */
class EvalStateLoaderTest {

    static final String OLD = "pod-a-00000001";
    static final String ME = "pod-b-00000002";
    static final String PORT = "PORT_DOWN";
    static final String KW = "KEYWORD";

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    SqlRecordingDataSource recording;
    EvalStateSql sql;
    long now;

    @BeforeEach
    void setUp() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        recording = new SqlRecordingDataSource(mp.dataSource());
        sql = new EvalStateSql(new JdbcTemplate(recording));
        now = mp.now();
        EvalStateSql w = new EvalStateSql(mp.jdbc());
        // Önceki sahibin kuşağı 5
        w.writeRecovery(List.of(new RecoveryRow(PORT, "db.example", 2, now - 5_000, 77L, now - 9_000, 1),
                new RecoveryRow(PORT, "zero.example", 0, null, null, null, 2)), OLD, 5, now);
        w.writeLastStatus(List.of(
                lastStatus(PORT, 7, "db.example", false, true, "NETWORK", now - 60_000),
                lastStatus(PORT, 8, "up.example", true, false, null, now - 30_000),
                lastStatus(PORT, 9, "ttl.example", false, true, "NETWORK",
                        now - MonitoringOutageService.siblingTtlMs() - 1),
                new LastStatusRow(PORT, "d:host|443", "host.example", "443", null, null, null, false, true, "NETWORK", "S",
                        now - 20_000, now - 20_000, 4)), OLD, 5, now);
        w.writeTypeState(List.of(new TypeRow(PORT, true, now - 100_000, now - 30_000, 30_000L, 5),
                new TypeRow(KW, false, null, null, 60_000L, 6)), OLD, 5, now);
        // Daha eski kuşak 4 — asla okunmaz (pencere hariç: her kuşak)
        w.writeRecovery(List.of(new RecoveryRow(PORT, "stale.example", 9, now, null, null, 1)), "pod-z", 4, now);
        w.writeLastStatus(List.of(lastStatus(KW, 21, "k1.example", false, true, "NETWORK", now - 120_000)), "pod-z", 4, now);
        w.writeTypeState(List.of(new TypeRow("DNS_FAILURE", true, now, now, 15_000L, 1)), "pod-z", 4, now);
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static LastStatusRow lastStatus(String type, long id, String domain, boolean up, boolean net, String cls, long at) {
        return new LastStatusRow(type, "m:" + id, domain, "443", id, 3L, true, up, net, cls, "S", at, at, id);
    }

    PriorGeneration cleanPrior() {
        long t = now - 10_000;
        return new PriorGeneration(5, OLD, t, 5L, t, t);
    }

    PriorGeneration crashPrior() {
        long t = now - 10_000;
        return new PriorGeneration(5, OLD, t, 5L, t, null);
    }

    EvalStateLoader.Outcome read(PriorGeneration prior, long token, AtomicLong current, int maxRows) {
        Decision d = EvalLoadContinuity.decide(prior, ME, now, false);
        return EvalStateLoader.read(sql, d, token, current::get, ME, now, maxRows);
    }

    @Test
    @DisplayName("FULL: kurtarma (> 0, yalnız kuşak 5), DOWN kardeşler (kimlikli, TTL içi, sınıfıyla), bastırma TRUE, tur görüldü, aralık, pencere")
    void fullTierMapsEveryField() {
        AtomicLong tok = new AtomicLong(42);
        EvalStateLoader.Outcome o = read(cleanPrior(), 42, tok, EvalStateLoader.MAX_LOAD_ROWS);
        assertThat(o.aborted()).isFalse();
        assertThat(o.skipped()).isNull();
        EvalLoad l = o.load();
        assertThat(l.token()).isEqualTo(42);
        assertThat(l.full()).isTrue();
        assertThat(l.recovery()).containsOnlyKeys(PORT + ":db.example");
        assertThat(l.recovery().get(PORT + ":db.example")).isEqualTo(new EvalLoad.Recovery(2, now - 5_000));
        assertThat(l.siblings()).containsOnlyKeys(EvalLoad.siblingKey(PORT, 7));
        EvalLoad.Sibling s = l.siblings().get(EvalLoad.siblingKey(PORT, 7));
        assertThat(s).isEqualTo(new EvalLoad.Sibling(PORT, "db.example", "443", 7, 3L, true, "NETWORK", now - 60_000));
        assertThat(l.suppressionActive()).containsExactly(PORT);
        assertThat(l.roundSeen()).containsExactly(PORT);
        assertThat(l.intervals()).containsEntry(PORT, 30_000L).containsEntry(KW, 60_000L).doesNotContainKey("DNS_FAILURE");
        // Pencere: her kuşaktan son 60 dk (kuşak 4'ün KEYWORD satırı dahil), TTL dışı / 60 dk'dan eski hariç
        assertThat(l.window()).containsOnlyKeys(PORT, KW);
        assertThat(l.window().get(PORT)).containsOnlyKeys("db.example", "up.example", "host.example");
        assertThat(l.window().get(PORT).get("db.example")).isEqualTo(new EvalLoad.SeedMark(true, now - 60_000));
        assertThat(l.window().get(PORT).get("up.example").networkDown()).isFalse();
        assertThat(l.window().get(KW)).containsOnlyKeys("k1.example");
        assertThat(l.meta().fromGeneration()).isEqualTo(5);
        assertThat(l.meta().fromWriter()).isEqualTo(OLD);
        assertThat(l.meta().handoverAgeMs()).isEqualTo(10_000L);
        assertThat(l.meta().durableAgeMs()).isEqualTo(10_000L);
        assertThat(l.meta().partialReason()).isNull();
        assertThat(recording.statements()).as("en çok 5 ifade (ara lider + 4 okuma)").hasSize(5);
    }

    @Test
    @DisplayName("OBSERVATIONS: kurtarma okunmaz; bastırma / tur görüldü taşınmaz; kardeşler + pencere + aralık; neden no_handover")
    void observationsTierCarriesOnlyObservations() {
        EvalStateLoader.Outcome o = read(crashPrior(), 7, new AtomicLong(7), EvalStateLoader.MAX_LOAD_ROWS);
        EvalLoad l = o.load();
        assertThat(l.full()).isFalse();
        assertThat(l.tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(l.recovery()).isEmpty();
        assertThat(l.suppressionActive()).isEmpty();
        assertThat(l.roundSeen()).isEmpty();
        assertThat(l.siblings()).containsOnlyKeys(EvalLoad.siblingKey(PORT, 7));
        assertThat(l.intervals()).containsKeys(PORT, KW);
        assertThat(l.window()).isNotEmpty();
        assertThat(l.meta().partialReason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
        assertThat(l.meta().handoverAgeMs()).isNull();
        assertThat(recording.statements()).as("ara lider + kurtarma okunmaz").hasSize(3)
                .noneMatch(q -> q.contains("monitor_check_schedule") || q.contains("recovery_up_count > 0"));
    }

    @Test
    @DisplayName("Ara lider: devirden (− 30 sn) sonra üçüncü pod'un sahiplendiği vade / boşaltıcı anahtarı → OBSERVATIONS / intermediate_leader")
    void intermediateLeaderDowngrades() {
        long handover = now - 10_000;
        JdbcTemplate j = mp.jdbc();
        String ins = "INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?,?,?,?)";
        // Sayılmayanlar: önceki sahip, ben, round: anahtarları, tarama kirası, devirden çok önceki sahiplenme, sahipsiz satır
        j.update(ins, "port:1", now, OLD, handover);
        j.update(ins, "port:2", now, ME, handover);
        j.update(ins, "round:uptime", now, "pod-c-00000003", handover);
        j.update(ins, EvalStateSql.LEASE_KEY, now, "pod-c-00000003", handover);
        j.update(ins, "port:3", now, "pod-c-00000003", handover - EvalLoadContinuity.INTERMEDIATE_SLACK_MS - 1);
        j.update(ins, "port:4", now, null, handover);
        assertThat(read(cleanPrior(), 1, new AtomicLong(1), 1000).load().full()).as("hiçbiri sayılmaz").isTrue();
        assertThat(sql.countIntermediate(handover - 30_000, OLD, ME)).isZero();

        recording.clear();
        j.update(ins, "lease:eval-inbox-drainer", now, "pod-c-00000003", handover - 30_000);   // sınır: tam −30 sn sayılır
        EvalStateLoader.Outcome o = read(cleanPrior(), 1, new AtomicLong(1), 1000);
        assertThat(o.load().tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(o.load().meta().partialReason()).isEqualTo(EvalLoadReport.REASON_INTERMEDIATE_LEADER);
        assertThat(o.load().recovery()).as("sayaç taşınmaz").isEmpty();
        assertThat(o.load().meta().handoverAgeMs()).as("geçerli devir işaretinin yaşı raporda").isEqualTo(10_000L);
        assertThat(recording.statements()).noneMatch(q -> q.contains("recovery_up_count > 0"));
    }

    @Test
    @DisplayName("Üst sınır: herhangi bir okuma sınırı aşarsa NONE / too_large — kısmi yük yok, deneme katmanı + kaynak raporda")
    void capMeansTooLarge() {
        // Sınır 1: kurtarma (1 satır) ve kardeş (1 satır) sığar, tür okuması (2 satır) aşar
        EvalStateLoader.Outcome o = read(cleanPrior(), 3, new AtomicLong(3), 1);
        assertThat(o.load()).isNull();
        assertThat(o.aborted()).isFalse();
        EvalLoadReport r = o.skipped();
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_TOO_LARGE);
        assertThat(r.attemptedTier()).isEqualTo(EvalLoad.TIER_FULL);
        assertThat(r.fromGeneration()).isEqualTo(5L);
        assertThat(r.fromPod()).isEqualTo(OLD);
        assertThat(r.recoveryCounters()).isZero();
        // Kurtarma okuması da sınırı aşabilir (2 sayaçlı satır, sınır 1)
        new EvalStateSql(mp.jdbc()).writeRecovery(List.of(new RecoveryRow(PORT, "db2.example", 1, now, null, null, 9)), OLD, 5,
                now);
        recording.clear();
        assertThat(read(cleanPrior(), 3, new AtomicLong(3), 1).skipped().reason()).isEqualTo(EvalLoadReport.REASON_TOO_LARGE);
        assertThat(recording.statements()).as("ara lider + kurtarma, sonra durur").hasSize(2);
        assertThat(EvalStateLoader.MAX_LOAD_ROWS).isEqualTo(200_000);
    }

    @Test
    @DisplayName("Belirteç ifadeler arasında değişirse (zaman aşımı / liderlik) okuma bırakılır: yük yok, rapor yok, kalan okumalar koşmaz")
    void tokenChangeAborts() {
        AtomicLong tok = new AtomicLong(11);
        AtomicInteger calls = new AtomicInteger();
        EvalStateLoader.Outcome o = EvalStateLoader.read(sql, EvalLoadContinuity.decide(cleanPrior(), ME, now, false), 11,
                () -> calls.incrementAndGet() >= 2 ? 0L : tok.get(), ME, now, 1000);
        assertThat(o.aborted()).isTrue();
        assertThat(o.load()).isNull();
        assertThat(o.skipped()).isNull();
        assertThat(recording.statements()).as("ara lider + kurtarma, sonra bırakıldı").hasSize(2);
    }

    @Test
    @DisplayName("Okuma hatası (ikinci ifade) → NONE / read_failed, kısa teknik özet (değer yok), kısmi yük yok")
    void readFailureIsReadFailed() {
        recording.failWhen = q -> q.contains("FROM monitor_last_status WHERE gen = ?");
        EvalStateLoader.Outcome o = read(cleanPrior(), 5, new AtomicLong(5), 1000);
        assertThat(o.load()).isNull();
        assertThat(o.skipped().reason()).isEqualTo(EvalLoadReport.REASON_READ_FAILED);
        assertThat(o.skipped().attemptedTier()).isEqualTo(EvalLoad.TIER_FULL);
        assertThat(o.failure()).contains("SQLException").doesNotContain("monitor_last_status");
    }

    @Test
    @DisplayName("NONE kararı okuma yapmaz; kararın raporunu döner")
    void noneDecisionReadsNothing() {
        Decision d = EvalLoadContinuity.decide(new PriorGeneration(5, OLD, now, null, null, null), ME, now, false);
        EvalStateLoader.Outcome o = EvalStateLoader.read(sql, d, 1, () -> 1L, ME, now, 1000);
        assertThat(o.skipped().reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
        assertThat(recording.statements()).isEmpty();
        assertThat(EvalStateLoader.read(sql, null, 1, () -> 1L, ME, now, 1000).skipped().reason())
                .isEqualTo(EvalLoadReport.REASON_NO_PREVIOUS_GENERATION);
    }
}
