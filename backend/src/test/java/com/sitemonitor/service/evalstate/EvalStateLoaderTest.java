package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.evalstate.EvalLoadContinuity.Decision;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.PriorGeneration;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.TypeRow;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-2 A — devir yükleyicisi H2'de (tasarım R3-2 §3.4): her okuma yalnız {@code gen = prior.gen} (pencere
 * satırları hariç), katmana göre eşleme, ara lider sorgusu, gelen kutusu (bekleyen elle sonuç → gözlemler), pencere tohumu
 * tür başına kesimle, üst sınır → {@code too_large}, belirteç değişince bırakma, okuma / eşleme hatası → {@code read_failed},
 * kısmi yük ASLA, en çok 6 ifade.
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
        assertThat(recording.statements()).as("en çok 6 ifade (ara lider + gelen kutusu + 4 okuma)").hasSize(6)
                .contains(EvalStateSql.SQL_INBOX_PENDING + " LIMIT 1");
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
        assertThat(recording.statements()).as("ara lider + gelen kutusu + kurtarma okunmaz").hasSize(3)
                .noneMatch(q -> q.contains("monitor_check_schedule") || q.contains("recovery_up_count > 0")
                        || q.contains(ManualEvalInbox.TABLE));
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
        assertThat(recording.statements()).as("ara lider + gelen kutusu + kurtarma, sonra durur").hasSize(3);
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
        assertThat(recording.statements()).as("ara lider + gelen kutusu, sonra bırakıldı").hasSize(2);
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

    // ── İnceleme düzeltmeleri (2026-10-28) ──────────────────────────────────────────────────────────────────────────

    void inboxRow() {
        mp.jdbc().update("INSERT INTO monitor_eval_inbox(monitor_type, monitor_id, manual, result_json, checked_ms, "
                + "source_pod, created_ms) VALUES (?,?,?,?,?,?,?)", ManualEvalInbox.TYPE_PORT, 7L, true, "{}", now,
                "pod-c-00000003", now);
    }

    @Test
    @DisplayName("M-1: gelen kutusunda değerlendirilmemiş elle sonuç → tam aday gözlemlere iner (pending_manual_results); "
            + "boş kutu / tablo yok = tam; gözlem adayı kutuyu okumaz; kutu okunamazsa read_failed")
    void pendingManualResultsDowngrade() {
        mp.jdbc().execute(ManualEvalInbox.DDL);
        assertThat(read(cleanPrior(), 2, new AtomicLong(2), 1000).load().full()).as("boş kutu").isTrue();
        inboxRow();
        recording.clear();
        EvalStateLoader.Outcome o = read(cleanPrior(), 2, new AtomicLong(2), 1000);
        EvalLoad l = o.load();
        assertThat(l.tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(l.meta().partialReason()).isEqualTo(EvalLoadReport.REASON_PENDING_MANUAL_RESULTS);
        assertThat(l.recovery()).as("sayaç taşınmaz (bekleyen DOWN sıfırlaması kaybolmasın)").isEmpty();
        assertThat(l.suppressionActive()).isEmpty();
        assertThat(l.roundSeen()).isEmpty();
        assertThat(l.siblings()).as("gözlemler taşınır").containsKey(EvalLoad.siblingKey(PORT, 7));
        assertThat(l.meta().handoverAgeMs()).as("geçerli devir işaretinin yaşı raporda").isEqualTo(10_000L);
        assertThat(recording.statements()).contains(EvalStateSql.SQL_INBOX_PENDING + " LIMIT 1")
                .noneMatch(q -> q.contains("recovery_up_count > 0"));

        recording.clear();
        assertThat(read(crashPrior(), 3, new AtomicLong(3), 1000).load().tier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(recording.statements()).as("gözlem adayı kutuyu okumaz").noneMatch(q -> q.contains(ManualEvalInbox.TABLE));

        recording.failWhen = q -> q.contains(ManualEvalInbox.TABLE);
        EvalStateLoader.Outcome failed = read(cleanPrior(), 4, new AtomicLong(4), 1000);
        assertThat(failed.load()).isNull();
        assertThat(failed.skipped().reason()).isEqualTo(EvalLoadReport.REASON_READ_FAILED);
        assertThat(failed.skipped().attemptedTier()).isEqualTo(EvalLoad.TIER_FULL);
    }

    @Test
    @DisplayName("M-1: kutu tablosu yoksa (BadSqlGrammar) satır yok sayılır — tam; ara lider zaten indirdiyse kutu okunmaz")
    void inboxMissingTableAndIntermediateOrder() {
        assertThat(sql.inboxHasPendingRows()).isFalse();
        assertThat(read(cleanPrior(), 1, new AtomicLong(1), 1000).load().full()).isTrue();
        mp.jdbc().execute(ManualEvalInbox.DDL);
        inboxRow();
        mp.jdbc().update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) "
                + "VALUES (?,?,?,?)", "port:9", now, "pod-c-00000003", now - 5_000);
        recording.clear();
        EvalStateLoader.Outcome o = read(cleanPrior(), 1, new AtomicLong(1), 1000);
        assertThat(o.load().meta().partialReason()).as("ilk düşen denetim").isEqualTo(EvalLoadReport.REASON_INTERMEDIATE_LEADER);
        assertThat(recording.statements()).noneMatch(q -> q.contains(ManualEvalInbox.TABLE));
    }

    @Test
    @DisplayName("M-2: pencere tohumu türün KENDİ penceresiyle (VERIFY kuralı) — 40 dk önceki ağ DOWN + 1 dk önceki UP → "
            + "işaret ağ DOWN DEĞİL; pencere uzunluğu kuralı yüklenen tur aralığıyla sorulur")
    void windowSeedUsesTheTypeWindow() {
        new EvalStateSql(mp.jdbc()).writeLastStatus(List.of(
                lastStatus(PORT, 30, "slow.example", false, true, "NETWORK", now - 40 * 60_000L),
                lastStatus(PORT, 31, "slow.example", true, false, null, now - 60_000L)), OLD, 5, now);
        Map<String, Long> asked = new ConcurrentHashMap<>();
        Decision d = EvalLoadContinuity.decide(cleanPrior(), ME, now, false);
        EvalLoad l = EvalStateLoader.read(sql, d, 1, () -> 1L, ME, now, 1000, (type, interval) -> {
            asked.put(type, interval);
            return EvalWindowSeed.DEFAULT_WINDOW_LENGTH.windowMs(type, interval);
        }).load();
        assertThat(asked).as("tür başına, önceki kuşağın tur aralığıyla").containsEntry(PORT, 30_000L)
                .containsEntry(KW, 60_000L);
        assertThat(l.window().get(PORT).get("slow.example")).as("PORT penceresi 5 dk: 40 dk önceki satır dışarıda")
                .isEqualTo(new EvalLoad.SeedMark(false, now - 60_000L));
        assertThat(l.window().get(PORT).get("db.example")).as("pencere içindeki ağ DOWN yine sayılır")
                .isEqualTo(new EvalLoad.SeedMark(true, now - 60_000));
        // Varsayılan kural (7 bağımsız değişkenli okuma) aynı sonucu verir
        assertThat(read(cleanPrior(), 1, new AtomicLong(1), 1000).load().window()).isEqualTo(l.window());
        // Kural pencereyi 60 dk derse eski satır içeride → ağ DOWN (kuralın gerçekten kullanıldığı)
        EvalLoad wide = EvalStateLoader.read(sql, d, 1, () -> 1L, ME, now, 1000,
                (type, interval) -> EvalWindowSeed.WINDOW_CAP_MS).load();
        assertThat(wide.window().get(PORT).get("slow.example").networkDown()).isTrue();
        // Kural istisna verirse o tür tohumlanmaz (boş pencere = yeniden başlatma), yük yine uygulanır
        EvalLoad broken = EvalStateLoader.read(sql, d, 1, () -> 1L, ME, now, 1000, (type, interval) -> {
            if (PORT.equals(type)) throw new IllegalStateException("kural");
            return 300_000L;
        }).load();
        assertThat(broken.window()).doesNotContainKey(PORT).containsKey(KW);
        assertThat(broken.recovery()).isNotEmpty();
    }

    @Test
    @DisplayName("L-4: eşleme hatası (DataAccessException DEĞİL, ör. IllegalStateException) → NONE / read_failed hemen, kısa "
            + "teknik özet (değer yok), kısmi yük yok")
    void mapperFailureIsReadFailed() {
        JdbcTemplate throwing = new JdbcTemplate(recording) {
            @Override
            public <T> List<T> query(String q, RowMapper<T> rm, Object... args) {
                if (q.contains("FROM monitor_last_status WHERE gen = ?")) {
                    RowMapper<T> bad = (rs, n) -> {
                        throw new IllegalStateException("eşleme hatası db.example");
                    };
                    return super.query(q, bad, args);
                }
                return super.query(q, rm, args);
            }
        };
        EvalStateLoader.Outcome o = EvalStateLoader.read(new EvalStateSql(throwing),
                EvalLoadContinuity.decide(cleanPrior(), ME, now, false), 5, () -> 5L, ME, now, 1000);
        assertThat(o.aborted()).isFalse();
        assertThat(o.load()).isNull();
        assertThat(o.skipped().reason()).isEqualTo(EvalLoadReport.REASON_READ_FAILED);
        assertThat(o.skipped().attemptedTier()).isEqualTo(EvalLoad.TIER_FULL);
        assertThat(o.failure()).contains("IllegalStateException").doesNotContain("db.example");
    }
}
