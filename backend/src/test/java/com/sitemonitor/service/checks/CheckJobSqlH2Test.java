package com.sitemonitor.service.checks;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dağıtık kontrol kuyruğu H2'de (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §9.2 A) — taşınabilir yol: şema yaması (iki
 * kez, kayıp yarış), gönderme tekilleştirme, sahiplenme (limit, sıra, tür süzgeci, deneme tavanı, kira dolumu, oluşturma
 * eşiği, eşzamanlı iki işçi), tamamlama belirteç CAS'ı, bırakma, kira-bağlı alma (tam bir kez), biçme, özet, tablo yokken boş.
 * Her test kendi H2 veritabanında ({@link MultiPodTestSupport}: {@code monitor_check_schedule} kurulu).
 */
class CheckJobSqlH2Test {

    private static final long T0 = 1_800_000_000_000L;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final TestDbClock clock = new TestDbClock(T0);
    JdbcCheckJobStore store;

    @BeforeEach
    void schema() {
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        store = new JdbcCheckJobStore(mp.jdbc(), clock, null, System::nanoTime);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private int count(String where) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_jobs WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    private void leaseTo(String owner) {
        assertThat(mp.schedule(owner).acquireLease("sweep-leader", mp.now(), 180_000)).isTrue();
    }

    private static CheckJobStore.Completion done(CheckJobRow r, String json, String worker) {
        return new CheckJobStore.Completion(r.id(), r.claimToken(), json, false, worker);
    }

    @Test
    @DisplayName("Şema: iki tablo + dizin kurulur, H2 taşınabilir türdür; ikinci koşu no-op")
    void schemaIsIdempotent() {
        CheckJobSchemaPatch p = new CheckJobSchemaPatch(mp.jdbc());
        assertThat(p.tableReadable(CheckJobSql.JOBS)).isTrue();
        assertThat(p.tableReadable(CheckJobSql.WORKERS)).isTrue();
        assertThat(p.apply()).isZero();
        Integer idx = mp.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.indexes "
                + "WHERE lower(index_name) = 'idx_mcj_state'", Integer.class);
        assertThat(idx).isPositive();
        assertThat(store.postgres()).isFalse();
    }

    @Test
    @DisplayName("Şema: iki pod aynı anda açılır — CREATE yarışı kaybedilse de tablo varsa hata yok, değişiklik sayılmaz")
    void createRaceLost_isTolerated() {
        MultiPodTestSupport other = MultiPodTestSupport.create();
        try {
            AtomicInteger lost = new AtomicInteger();
            JdbcTemplate racing = new JdbcTemplate(other.dataSource()) {
                @Override
                public void execute(String sql) {
                    super.execute(sql);
                    if (sql.startsWith("CREATE TABLE")) {
                        lost.incrementAndGet();
                        throw new DuplicateKeyException("pg_type_typname_nsp_index");
                    }
                }
            };
            CheckJobSchemaPatch p = new CheckJobSchemaPatch(racing);
            assertThatCode(p::apply).doesNotThrowAnyException();
            assertThat(lost).hasValue(2);
            assertThat(p.tableReadable(CheckJobSql.JOBS)).isTrue();
            assertThat(p.apply()).isZero();
        } finally {
            other.close();
        }
    }

    @Test
    @DisplayName("Şema: veritabanı hatası açılışı durdurmaz")
    void schemaFailureIsSwallowed() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:checkjobs-yok;IFEXISTS=TRUE"));
        assertThatCode(() -> new CheckJobSchemaPatch(broken).onReady()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Gönderme: izleme başına en çok bir açık iş; açık iş varken yeniden gönderme 0; bitmiş iş yenisini engellemez")
    void dispatchDedupe() {
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(1L, 2L, 2L, 3L), 150_000, "leader")).isEqualTo(3);
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(1L, 2L, 3L, 4L), 150_000, "leader")).as("yalnız 4 yeni").isEqualTo(1);
        assertThat(store.dispatch(CheckJobTypes.HTTP, List.of(1L), 150_000, "leader")).as("başka tür, aynı kimlik").isEqualTo(1);
        assertThat(count("state = 'PENDING'")).isEqualTo(5);
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT * FROM monitor_check_jobs WHERE job_type = 'port' AND monitor_id = 1");
        assertThat(row.get("lease_ms")).isEqualTo(150_000L);
        assertThat(row.get("created_ms")).isEqualTo(T0);
        assertThat(row.get("dispatcher")).isEqualTo("leader");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();

        List<CheckJobRow> c = store.claim(Set.of(CheckJobTypes.PORT), 1, "w1");
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(c.get(0).monitorId()), 150_000, "leader"))
                .as("CLAIMED da açık iştir").isZero();
        store.complete(List.of(done(c.get(0), "{\"ok\":true}", "w1")));
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(c.get(0).monitorId()), 150_000, "leader"))
                .as("DONE açık değildir — sonraki dönem gönderilebilir").isEqualTo(1);
    }

    @Test
    @DisplayName("Gönderme: boş / bilinmeyen tür / null kimlik → 0, deyim yok; 200'lük parçalar")
    void dispatchEdges() {
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(), 150_000, "l")).isZero();
        assertThat(store.dispatch("domain", List.of(1L), 150_000, "l")).isZero();
        List<Long> many = new ArrayList<>();
        many.add(null);
        for (long i = 0; i < 450; i++) many.add(1_000 + i);
        assertThat(store.dispatch(CheckJobTypes.PING, many, 150_000, "l")).isEqualTo(450);
        assertThat(count("job_type = 'ping'")).isEqualTo(450);
    }

    @Test
    @DisplayName("Sahiplenme: limit, id sırası, tür süzgeci, deneme +1, tek belirteç, kira = şimdi + iş kirası")
    void claimBasics() {
        store.dispatch(CheckJobTypes.PORT, List.of(10L, 11L, 12L), 150_000, "l");
        store.dispatch(CheckJobTypes.SCRIPTED, List.of(20L), CheckJobTypes.LEASE_MS_SCRIPTED, "l");
        clock.advance(500);
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.PORT, CheckJobTypes.PING), 2, "w1");
        assertThat(got).extracting(CheckJobRow::monitorId).containsExactly(10L, 11L);
        assertThat(got).extracting(CheckJobRow::attempts).containsOnly(1);
        assertThat(got).extracting(CheckJobRow::claimToken).doesNotContainNull().hasSize(2);
        assertThat(got.get(0).claimToken()).isEqualTo(got.get(1).claimToken()).hasSizeLessThanOrEqualTo(CheckJobSql.TOKEN_MAX);
        assertThat(got.get(0).claimedMs()).isEqualTo(T0 + 500);
        assertThat(got.get(0).leaseUntilMs()).isEqualTo(T0 + 500 + 150_000);
        assertThat(got.get(0).claimLagMs()).isEqualTo(500);
        assertThat(got.get(0).id()).isLessThan(got.get(1).id());
        Map<String, Object> stored = mp.jdbc().queryForMap("SELECT * FROM monitor_check_jobs WHERE id = ?", got.get(0).id());
        assertThat(stored.get("state")).isEqualTo("CLAIMED");
        assertThat(stored.get("claimed_by")).isEqualTo("w1");
        assertThat(stored.get("lease_until_ms")).isEqualTo(T0 + 500 + 150_000);

        assertThat(store.claim(Set.of(CheckJobTypes.PORT), 5, "w2")).extracting(CheckJobRow::monitorId).containsExactly(12L);
        assertThat(store.claim(Set.of(CheckJobTypes.PORT), 5, "w2")).as("hepsi sahiplenildi").isEmpty();
        List<CheckJobRow> k6 = store.claim(Set.of(CheckJobTypes.SCRIPTED), 5, "w2");
        assertThat(k6).singleElement().satisfies(r -> assertThat(r.leaseMs()).isEqualTo(CheckJobTypes.LEASE_MS_SCRIPTED));
        assertThat(store.claim(Set.of(), 5, "w1")).isEmpty();
        assertThat(store.claim(Set.of(CheckJobTypes.PORT), 0, "w1")).isEmpty();
        assertThat(store.claim(Set.of("domain"), 5, "w1")).isEmpty();
    }

    @Test
    @DisplayName("Sahiplenme: kirası dolan iş BİR kez yeniden alınır (deneme 2), sonra tavan; eski işçinin tamamlaması geç kalır")
    void leaseExpiryAndAttemptsCap() {
        store.dispatch(CheckJobTypes.HTTP, List.of(5L), 150_000, "l");
        CheckJobRow first = store.claim(Set.of(CheckJobTypes.HTTP), 1, "w1").get(0);
        clock.advance(149_999);
        assertThat(store.claim(Set.of(CheckJobTypes.HTTP), 1, "w2")).as("kira sürüyor").isEmpty();
        clock.advance(2);
        CheckJobRow second = store.claim(Set.of(CheckJobTypes.HTTP), 1, "w2").get(0);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        boolean[] lateResult = store.complete(List.of(done(first, "{\"late\":1}", "w1")));
        assertThat(lateResult).containsExactly(false);
        clock.advance(150_001);
        assertThat(store.claim(Set.of(CheckJobTypes.HTTP), 1, "w3")).as("MAX_ATTEMPTS = 2").isEmpty();
        assertThat(store.complete(List.of(done(second, "{\"ok\":true}", "w2")))).as("kira dolmuş ama hâlâ bu belirteç")
                .containsExactly(true);
        assertThat(count("state = 'DONE'")).isEqualTo(1);
        assertThat(mp.jdbc().queryForObject("SELECT worker FROM monitor_check_jobs WHERE id = ?", String.class, first.id()))
                .isEqualTo("w2");
    }

    @Test
    @DisplayName("Sahiplenme: oluşturma eşiği (liderin 'çal' sahiplenmesi) yalnız daha eski işleri alır")
    void createdBeforeFilter() {
        store.dispatch(CheckJobTypes.PORT, List.of(1L), 150_000, "l");
        clock.advance(6_000);
        store.dispatch(CheckJobTypes.PORT, List.of(2L), 150_000, "l");
        List<CheckJobRow> stolen = store.claim(Set.of(CheckJobTypes.PORT), 10, "leader", clock.nowMs() - 5_000);
        assertThat(stolen).extracting(CheckJobRow::monitorId).containsExactly(1L);
        assertThat(store.claim(Set.of(CheckJobTypes.PORT), 10, "w1", CheckJobStore.ANY_AGE))
                .extracting(CheckJobRow::monitorId).containsExactly(2L);
    }

    @Test
    @DisplayName("Sahiplenme: iki işçi aynı anda 300 işe uzanır — her iş tam bir kez alınır (H2 CAS yolu)")
    void concurrentClaimsAreDisjoint() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 300; i++) ids.add(i);
        store.dispatch(CheckJobTypes.PORT, ids, 150_000, "l");
        JdbcCheckJobStore other = new JdbcCheckJobStore(mp.jdbc(), clock, null, System::nanoTime);
        Callable<List<Long>> a = () -> drain(store, "wA");
        Callable<List<Long>> b = () -> drain(other, "wB");
        List<List<Long>> res = mp.race(a, b);
        Set<Long> all = new HashSet<>();
        int total = 0;
        for (List<Long> r : res) {
            all.addAll(r);
            total += r.size();
        }
        assertThat(total).isEqualTo(300);
        assertThat(all).hasSize(300);
    }

    private static List<Long> drain(CheckJobStore s, String owner) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            List<CheckJobRow> got = s.claim(Set.of(CheckJobTypes.PORT), 7, owner);
            if (got.isEmpty()) break;
            for (CheckJobRow r : got) out.add(r.id());
        }
        return out;
    }

    @Test
    @DisplayName("Bırakma: yalnız bu sahibin CLAIMED işleri PENDING'e döner, deneme bir geri; yeniden sahiplenilebilir")
    void releaseNotStarted() {
        store.dispatch(CheckJobTypes.DNS, List.of(1L, 2L), 150_000, "l");
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.DNS), 2, "w1");
        assertThat(store.release("w2", List.of(got.get(0).id()))).as("başka sahip").isZero();
        assertThat(store.release("w1", List.of(got.get(0).id(), got.get(1).id()))).isEqualTo(2);
        Map<String, Object> r = mp.jdbc().queryForMap("SELECT * FROM monitor_check_jobs WHERE id = ?", got.get(0).id());
        assertThat(r.get("state")).isEqualTo("PENDING");
        assertThat(r.get("claimed_by")).isNull();
        assertThat(r.get("claim_token")).isNull();
        assertThat(((Number) r.get("attempts")).intValue()).isZero();
        assertThat(store.complete(List.of(done(got.get(0), "{}", "w1")))).as("bırakılan işin eski belirteci").containsExactly(false);
        assertThat(store.claim(Set.of(CheckJobTypes.DNS), 5, "w2")).hasSize(2)
                .extracting(CheckJobRow::attempts).containsOnly(1);
        assertThat(store.release("w1", List.of())).isZero();
    }

    @Test
    @DisplayName("Alma: yalnız kira sahibi alır, satırlar silinir (tam bir kez), id sırasında; sonuç alanları dolu")
    void takeIsLeaseBound() {
        store.dispatch(CheckJobTypes.PING, List.of(1L, 2L, 3L), 150_000, "l");
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.PING), 3, "w1");
        clock.advance(1_000);
        store.complete(List.of(done(got.get(2), "{\"up\":false}", "w1"), done(got.get(0), "{\"up\":true}", "w1")));
        String leader = MultiPodTestSupport.owner(1);
        assertThat(store.take(10, leader)).as("kira satırı yok").isEmpty();
        leaseTo(MultiPodTestSupport.owner(2));
        assertThat(store.take(10, leader)).as("kira başkasında").isEmpty();
        assertThat(count("state = 'DONE'")).isEqualTo(2);

        mp.jdbc().update("UPDATE " + ClusterScheduleService.TABLE + " SET claimed_by = ? WHERE monitor_key = ?", leader,
                CheckJobSql.LEASE_KEY);
        List<CheckResultRow> taken = store.take(10, leader);
        assertThat(taken).extracting(CheckResultRow::monitorId).containsExactly(1L, 3L);
        CheckResultRow r = taken.get(0);
        assertThat(r.type()).isEqualTo(CheckJobTypes.PING);
        assertThat(r.worker()).isEqualTo("w1");
        assertThat(r.finishedMs()).isEqualTo(T0 + 1_000);
        assertThat(r.claimedMs()).isEqualTo(T0);
        assertThat(r.result()).containsEntry("up", true);
        assertThat(r.resultTruncated()).isFalse();
        assertThat(store.take(10, leader)).as("tam bir kez").isEmpty();
        assertThat(count("1 = 1")).as("bitmemiş iş kalır").isEqualTo(1);
    }

    @Test
    @DisplayName("Biçme: kirası dolmuş VE iki kez sahiplenilmiş işler silinir; diğerleri kalır")
    void reap() {
        store.dispatch(CheckJobTypes.PORT, List.of(1L, 2L), 150_000, "l");
        store.claim(Set.of(CheckJobTypes.PORT), 2, "w1");
        clock.advance(150_001);
        CheckJobRow again = store.claim(Set.of(CheckJobTypes.PORT), 1, "w2").get(0);
        assertThat(store.reap(10)).as("ikinci kira sürüyor, birincinin denemesi 1").isEmpty();
        clock.advance(150_001);
        List<CheckJobRow> reaped = store.reap(10);
        assertThat(reaped).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(again.id());
            assertThat(r.attempts()).isEqualTo(2);
            assertThat(r.type()).isEqualTo(CheckJobTypes.PORT);
            assertThat(r.createdMs()).isEqualTo(T0);
        });
        assertThat(count("1 = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("Özet: durum × tür sayıları, en eski bekleyen / biten yaşı, kirası dolmuş sahiplenme")
    void stats() {
        store.dispatch(CheckJobTypes.PORT, List.of(1L, 2L, 3L), 150_000, "l");
        clock.advance(2_000);
        store.dispatch(CheckJobTypes.HTTP, List.of(1L), 150_000, "l");
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.PORT), 2, "w1");
        clock.advance(1_000);
        store.complete(List.of(done(got.get(0), "{}", "w1")));
        clock.advance(4_000);
        CheckJobStore.QueueStats s = store.stats();
        assertThat(s.nowMs()).isEqualTo(T0 + 7_000);
        assertThat(s.count(CheckJobStore.STATE_PENDING)).isEqualTo(2);
        assertThat(s.count(CheckJobStore.STATE_PENDING, CheckJobTypes.HTTP)).isEqualTo(1);
        assertThat(s.count(CheckJobStore.STATE_CLAIMED)).isEqualTo(1);
        assertThat(s.count(CheckJobStore.STATE_DONE)).isEqualTo(1);
        assertThat(s.oldestPendingAgeMs()).isEqualTo(7_000);
        assertThat(s.oldestDoneAgeMs()).isEqualTo(4_000);
        assertThat(s.expiredClaims()).isZero();
        assertThat(s.types()).containsExactly(CheckJobTypes.HTTP, CheckJobTypes.PORT);
        clock.advance(150_000);
        assertThat(store.stats().expiredClaims()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tablo yok (yama koşmadı): gönderme DIŞINDAKİ her yöntem boş / 0 döner, fırlatmaz")
    void tablesMissing_emptyResults() {
        MultiPodTestSupport bare = MultiPodTestSupport.create();
        try {
            JdbcCheckJobStore s = new JdbcCheckJobStore(bare.jdbc(), clock, null, System::nanoTime);
            // Yeniden taban (inceleme QW-L9 / DA-M1): gönderme artık sessiz 0 dönmez, fırlatır — ayrı testte.
            assertThat(s.claim(Set.of(CheckJobTypes.PORT), 3, "w")).isEmpty();
            assertThat(s.complete(List.of(new CheckJobStore.Completion(1, "t", "{}", false, "w")))).containsExactly(false);
            assertThat(s.release("w", List.of(1L))).isZero();
            assertThat(s.take(5, "x")).isEmpty();
            assertThat(s.reap(5)).isEmpty();
            CheckJobStore.QueueStats st = s.stats();
            assertThat(st.rows()).isEmpty();
            assertThat(st.oldestPendingAgeMs()).isNull();
        } finally {
            bare.close();
        }
    }

    @Test
    @DisplayName("Gönderme, iş tablosu yokken: sessiz 0 değil — DataAccessResourceFailureException (Türkçe, SQL metni yok)")
    void dispatchOnMissingTableSignalsFailure() {
        MultiPodTestSupport bare = MultiPodTestSupport.create();
        try {
            for (Boolean dialect : new Boolean[] {null, Boolean.TRUE, Boolean.FALSE}) {
                JdbcCheckJobStore s = new JdbcCheckJobStore(bare.jdbc(), clock, dialect, System::nanoTime);
                assertThatThrownBy(() -> s.dispatch(CheckJobTypes.PORT, List.of(1L, 2L), 150_000, "l"))
                        .as("tür: %s", dialect)
                        .isInstanceOf(DataAccessResourceFailureException.class)
                        .hasMessage(JdbcCheckJobStore.DISPATCH_TABLE_MISSING)
                        .hasNoCause();
            }
            JdbcCheckJobStore s = new JdbcCheckJobStore(bare.jdbc(), clock, null, System::nanoTime);
            assertThat(s.dispatch(CheckJobTypes.PORT, List.of(), 150_000, "l")).as("boş liste: deyim yok, fırlatmaz").isZero();
            assertThat(JdbcCheckJobStore.DISPATCH_TABLE_MISSING).doesNotContainIgnoringCase("select")
                    .doesNotContainIgnoringCase("insert").doesNotContain(CheckJobSql.JOBS);
        } finally {
            bare.close();
        }
    }

    @Test
    @DisplayName("Biçme: bitişi 2 × result-stale-ms'ten eski DONE satırları da silinir (dönen listeye girmez, ayrı sayılır, ≤ 200); gençler kalır")
    void reapDropsDoneOlderThanStaleCap() {
        long staleMs = 120_000L;   // ayar: 2 dk → eşik 4 dk
        store.setDistributedChecks(new DistributedChecks(null,
                new DistributedChecks.Tuning(true, 16, 1_000, 5_000, 2_000, 50, 5_000, staleMs)));
        store.dispatch(CheckJobTypes.PORT, List.of(1L, 2L, 3L), 150_000, "l");
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.PORT), 3, "w1");
        store.complete(List.of(done(got.get(0), "{\"up\":true}", "w1"), done(got.get(1), "{\"up\":true}", "w1")));
        clock.advance(2 * staleMs - 1_000);
        store.complete(List.of(done(got.get(2), "{\"up\":false}", "w1")));   // genç DONE (bitiş şimdi)
        assertThat(store.reap(10)).as("eşik henüz aşılmadı").isEmpty();
        assertThat(count("state = 'DONE'")).isEqualTo(3);
        assertThat(store.staleDoneReaped()).isZero();

        clock.advance(1_001);
        List<CheckJobRow> reaped = store.reap(10);
        assertThat(reaped).as("dönen liste yalnız kirası dolmuş işler — 'N kez sahiplenildi' uyarısı DONE için yazılmaz").isEmpty();
        assertThat(store.staleDoneReaped()).isEqualTo(2);
        assertThat(count("state = 'DONE'")).as("genç DONE kalır").isEqualTo(1);
        assertThat(mp.jdbc().queryForObject("SELECT monitor_id FROM monitor_check_jobs", Long.class)).isEqualTo(3L);

        // Çağrı başına en çok 200.
        List<Long> many = new ArrayList<>();
        for (long i = 0; i < 250; i++) many.add(1_000 + i);
        store.dispatch(CheckJobTypes.HTTP, many, 150_000, "l");
        List<CheckJobStore.Completion> all = new ArrayList<>();
        for (CheckJobRow r : store.claim(Set.of(CheckJobTypes.HTTP), 500, "w1")) all.add(done(r, "{}", "w1"));
        store.complete(all);
        clock.advance(2 * staleMs + 1);
        store.reap(10);
        assertThat(store.staleDoneReaped()).isEqualTo(2 + JdbcCheckJobStore.STALE_DONE_REAP_LIMIT);
        store.reap(10);
        assertThat(store.staleDoneReaped()).isEqualTo(2 + 250 + 1);   // + az önceki genç satır (artık o da eski)
        assertThat(count("1 = 1")).isZero();
        assertThat(store.staleDoneReapFailures()).isZero();
    }

    @Test
    @DisplayName("Biçme: eski DONE adımının hatası kirası dolmuş işlerin sonucunu düşürmez")
    void reapStaleDoneFailureKeepsExpiredClaimsResult() {
        store.dispatch(CheckJobTypes.PORT, List.of(1L), 150_000, "l");
        store.claim(Set.of(CheckJobTypes.PORT), 1, "w1");
        clock.advance(150_001);
        store.claim(Set.of(CheckJobTypes.PORT), 1, "w2");
        clock.advance(150_001);
        JdbcTemplate failingDone = new JdbcTemplate(mp.dataSource()) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                if (sql.equals(CheckJobSql.SQL_REAP_DONE_CANDIDATES)) {
                    throw new DataAccessResourceFailureException("db");
                }
                return super.queryForList(sql, elementType, args);
            }
        };
        JdbcCheckJobStore s = new JdbcCheckJobStore(failingDone, clock, null, System::nanoTime);
        assertThat(s.reap(10)).hasSize(1);
        assertThat(s.staleDoneReapFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Son inceleme F1: ikinci parça düşerse PartialDispatchException yazılmış ilk 200 kimliği taşır; satırlar yalnız onlar")
    void partialDispatchReportsCommittedPrefix() {
        java.util.concurrent.atomic.AtomicInteger batches = new java.util.concurrent.atomic.AtomicInteger();
        JdbcTemplate failSecond = new JdbcTemplate(mp.dataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
                if (sql.equals(CheckJobSql.SQL_DISPATCH_ONE) && batches.incrementAndGet() == 2) {
                    throw new DataAccessResourceFailureException("db");
                }
                return super.batchUpdate(sql, batchArgs);
            }
        };
        JdbcCheckJobStore s = new JdbcCheckJobStore(failSecond, clock, null, System::nanoTime);
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 450; i++) ids.add(i);
        CheckJobStore.PartialDispatchException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                CheckJobStore.PartialDispatchException.class, () -> s.dispatch(CheckJobTypes.PORT, ids, 150_000, "l"));
        assertThat(e).isNotNull();
        assertThat(e.committed()).hasSize(CheckJobSql.DISPATCH_CHUNK).containsExactlyElementsOf(ids.subList(0, CheckJobSql.DISPATCH_CHUNK));
        assertThat(count("job_type = 'port'")).isEqualTo(CheckJobSql.DISPATCH_CHUNK);

        // ilk parça düşerse sade istisna (yazılan yok — gönderen hepsini bekletir)
        batches.set(1);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> s.dispatch(CheckJobTypes.PING, ids.subList(0, 5), 150_000, "l")))
                .isInstanceOf(DataAccessResourceFailureException.class).isNotInstanceOf(CheckJobStore.PartialDispatchException.class);
    }

    @Test
    @DisplayName("Kodlanmış sonuç gidiş-dönüşü: kırpılmış sonuç işareti saklanır ve okunur")
    void truncatedFlagRoundTrip() {
        store.dispatch(CheckJobTypes.PAGE, List.of(9L), 150_000, "l");
        CheckJobRow r = store.claim(Set.of(CheckJobTypes.PAGE), 1, "w1").get(0);
        store.complete(List.of(new CheckJobStore.Completion(r.id(), r.claimToken(), "{\"a\":\"b\"}", true, "w1")));
        leaseTo("leader-1");
        CheckResultRow got = store.take(5, "leader-1").get(0);
        assertThat(got.resultTruncated()).isTrue();
        assertThat(got.result()).containsEntry("a", "b");
    }
}
