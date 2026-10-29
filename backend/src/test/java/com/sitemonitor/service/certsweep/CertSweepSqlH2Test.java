package com.sitemonitor.service.certsweep;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parçalı sertifika taraması deposu H2'de (2026-10-29, çok pod Sürüm 3-4 paket A, tasarım §9.2 A) — taşınabilir yol: şema
 * yaması (iki kez, kayıp yarış, hata yutulur, depoya "hazır" bildirimi), koşu kurulumu (önce parçalar, sonra koşu; çakışan
 * kimlik / yarım kurulum temizliği), sahiplenme (sıra, belirteç, kira, koşu RUNNING + nabız kapısı, oluşturma eşiği, kaçınılan
 * işçi, deneme tavanı, kira dolumu → ikinci sahiplenme → kayıp), iki pod yarışı (her parça tam bir kez), yenileme, tamamlama
 * belirteç CAS'ı + koşu RUNNING, bırakma, iptal, nabız, karar / kapanış, öksüz kapatma, budama, ikinci görüş parçaları, durum
 * okuması, tablo yokken sessiz boş + 60 sn'de bir yeniden denetim, şablon zaman aşımları. Her test kendi H2 veritabanında.
 */
class CertSweepSqlH2Test {

    static final long T0 = 1_800_000_000_000L;
    static final long LEASE = 60_000L;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final CertSweepTestClock clock = new CertSweepTestClock(T0);
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    JdbcCertSweepStore store;

    @BeforeEach
    void schema() {
        store = new JdbcCertSweepStore(mp.jdbc(), mp.jdbc(), clock, null, nanos::get);
        new CertSweepSchemaPatch(mp.jdbc(), store).apply();
    }

    @AfterEach
    void close() {
        mp.close();
    }

    // ── yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    static CertChunkSpec.Entry entry(int i) {
        return new CertChunkSpec.Entry(i, "d" + i + ".example.com", i % 7 == 0 ? null : 443 + (i % 3), i % 2 == 0,
                i % 3 == 0 ? "" : (i % 3 == 1 ? null : "browser"), i % 5 == 0 ? 7 : null);
    }

    /** {@code n} alan adı, {@code size}'lık parçalar (son parça kısa olabilir). */
    static CertRunSpec spec(String runId, int n, int size) {
        List<CertChunkSpec> chunks = new ArrayList<>();
        List<CertChunkSpec.Entry> cur = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cur.add(entry(i));
            if (cur.size() == size) {
                chunks.add(CertChunkSpec.of(chunks.size(), cur));
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) chunks.add(CertChunkSpec.of(chunks.size(), cur));
        return new CertRunSpec(runId, "hourly", "coord-00000001", true, n, size, chunks);
    }

    int count(String table, String where) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    Map<String, Object> run(String runId) {
        return mp.jdbc().queryForMap("SELECT * FROM cert_sweep_runs WHERE run_id = ?", runId);
    }

    Map<String, Object> chunk(long id) {
        return mp.jdbc().queryForMap("SELECT * FROM cert_sweep_chunks WHERE id = ?", id);
    }

    ClaimedChunk claim(String owner) {
        return store.claim(owner, LEASE, Long.MAX_VALUE).orElseThrow();
    }

    static ChunkCompletion completion(ClaimedChunk c, String worker) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (CertChunkSpec.Entry e : c.entries()) results.add(Map.of("domain", e.domain(), "status", "valid", "run_id", c.runId()));
        return ChunkCompletion.of(c, worker, results, 0);
    }

    // ── şema ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Şema: iki tablo + üç dizin kurulur, ikinci koşu no-op, depoya 'hazır' bildirilir; H2 taşınabilir türdür")
    void schemaIsIdempotent() {
        CertSweepSchemaPatch p = new CertSweepSchemaPatch(mp.jdbc(), store);
        assertThat(p.tableReadable(CertSweepSql.RUNS)).isTrue();
        assertThat(p.tableReadable(CertSweepSql.CHUNKS)).isTrue();
        assertThat(p.apply()).isZero();
        for (String idx : List.of("idx_csr_state", "ux_csc_run_chunk", "idx_csc_state")) {
            Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE lower(index_name) = ?",
                    Integer.class, idx);
            assertThat(n).as(idx).isPositive();
        }
        assertThat(store.tablesReady()).isTrue();
        assertThat(store.postgres()).isFalse();
        assertThatThrownBy(() -> mp.jdbc().update("INSERT INTO cert_sweep_chunks (run_id, chunk_no, state, domains_json, "
                + "domain_count, attempts, created_ms) VALUES ('r', 0, 'PENDING', '[]', 0, 0, 1), ('r', 0, 'PENDING', '[]', 0, 0, 1)"))
                .as("koşu × parça numarası tekil").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Şema: depo yokken bildirim atlanır; yeni depo 'bilinmiyor' (hazır değil) başlar, yama 'hazır' der")
    void readinessSignal() {
        JdbcCertSweepStore fresh = new JdbcCertSweepStore(mp.jdbc(), clock, null);
        assertThat(fresh.tablesReady()).as("açılış yaması koşmadan hazır sayılmaz (tur klasik)").isFalse();
        assertThatCode(() -> new CertSweepSchemaPatch(mp.jdbc()).apply()).doesNotThrowAnyException();
        assertThat(fresh.tablesReady()).isFalse();
        new CertSweepSchemaPatch(mp.jdbc(), fresh).apply();
        assertThat(fresh.tablesReady()).isTrue();
        fresh.markTablesReady(false);
        assertThat(fresh.tablesReady()).isFalse();
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
            JdbcCertSweepStore s = new JdbcCertSweepStore(racing, clock, null);
            CertSweepSchemaPatch p = new CertSweepSchemaPatch(racing, s);
            assertThatCode(p::apply).doesNotThrowAnyException();
            assertThat(lost).hasValue(2);
            assertThat(p.tableReadable(CertSweepSql.CHUNKS)).isTrue();
            assertThat(s.tablesReady()).isTrue();
            assertThat(p.apply()).isZero();
        } finally {
            other.close();
        }
    }

    @Test
    @DisplayName("Şema: veritabanı hatası açılışı durdurmaz; depo 'yok' bilir")
    void schemaFailureIsSwallowed() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:certsweep-yok;IFEXISTS=TRUE"));
        JdbcCertSweepStore s = new JdbcCertSweepStore(broken, clock, null);
        assertThatCode(() -> new CertSweepSchemaPatch(broken, s).onReady()).doesNotThrowAnyException();
        assertThat(s.tablesReady()).isFalse();
    }

    // ── kurulum ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kurulum: parçalar + koşu satırı; ham değerler (port null, tls_mode boş / null, zaman aşımı) sahiplenmede aynen döner")
    void createRunAndEntriesRoundTrip() {
        CertRunSpec s = spec("run-a", 45, 20);
        store.createRun(s);
        Map<String, Object> r = run("run-a");
        assertThat(r).containsEntry("kind", "hourly").containsEntry("coordinator", "coord-00000001")
                .containsEntry("state", "RUNNING").containsEntry("evict_caches", true).containsEntry("domains_total", 45)
                .containsEntry("chunks_total", 3).containsEntry("chunk_size", 20).containsEntry("created_ms", T0)
                .containsEntry("heartbeat_ms", T0);
        assertThat(r.get("finished_ms")).isNull();
        assertThat(count("cert_sweep_chunks", "run_id = 'run-a' AND state = 'PENDING' AND attempts = 0 AND created_ms = " + T0))
                .isEqualTo(3);
        assertThat(mp.jdbc().queryForList("SELECT domain_count FROM cert_sweep_chunks WHERE run_id = 'run-a' ORDER BY chunk_no",
                Integer.class)).containsExactly(20, 20, 5);

        List<CertChunkSpec.Entry> got = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            ClaimedChunk c = claim("w1");
            assertThat(c.chunkNo()).isEqualTo(k);
            assertThat(c.runId()).isEqualTo("run-a");
            got.addAll(c.entries());
        }
        List<CertChunkSpec.Entry> want = new ArrayList<>();
        for (CertChunkSpec c : s.chunks()) want.addAll(c.entries());
        assertThat(got).as("Entry kayıtları değer eşitliğiyle aynı").isEqualTo(want);
        assertThat(got.get(0).toRow()).as("klasik satır biçimi").isEqualTo(want.get(0).toRow());
        assertThat(got).anyMatch(e -> e.port() == null).anyMatch(e -> "".equals(e.tlsMode()))
                .anyMatch(e -> e.tlsMode() == null).anyMatch(e -> e.timeoutSeconds() != null);
    }

    @Test
    @DisplayName("Kurulum: kimlik kullanımdaysa hiçbir şey yazılmaz / silinmez (başkasının satırına dokunulmaz)")
    void createRunIdTaken() {
        store.createRun(spec("run-x", 30, 20));
        assertThatThrownBy(() -> store.createRun(spec("run-x", 10, 5))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("cert_sweep_chunks", "run_id = 'run-x'")).isEqualTo(2);
        assertThat(run("run-x")).containsEntry("domains_total", 30);
    }

    @Test
    @DisplayName("Kurulum: koşu satırı yazılamazsa bu çağrının parçaları silinir, istisna fırlar (yarım kurulum işçilere görünmez)")
    void createRunFailureCleansChunks() {
        JdbcTemplate failing = new JdbcTemplate(mp.dataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.equals(CertSweepSql.SQL_INSERT_RUN)) throw new DataIntegrityViolationException("düştü");
                return super.update(sql, args);
            }
        };
        JdbcCertSweepStore s = new JdbcCertSweepStore(failing, clock, null);
        assertThatThrownBy(() -> s.createRun(spec("run-f", 450, 2))).isInstanceOf(DataAccessException.class);
        assertThat(count("cert_sweep_chunks", "run_id = 'run-f'")).as("225 parça (iki deyim) temizlendi").isZero();
        assertThat(count("cert_sweep_runs", "run_id = 'run-f'")).isZero();
    }

    @Test
    @DisplayName("Kurulum: 500 parça / 200'lük çok satırlı deyimlerle; parça yazılırken koşu satırı yokken sahiplenilemez")
    void bigRunAndInvisibleUntilRunRow() {
        // koşu satırı olmayan parça (yarım kurulum — en küçük kimlik) sahiplenilmez
        mp.jdbc().update("INSERT INTO cert_sweep_chunks (run_id, chunk_no, state, domains_json, domain_count, attempts, created_ms) "
                + "VALUES ('run-ghost', 0, 'PENDING', '[]', 0, 0, ?)", T0 - 1);
        assertThat(store.claim("w1", LEASE, Long.MAX_VALUE)).isEmpty();
        store.createRun(spec("run-big", 10_000, 20));
        assertThat(count("cert_sweep_chunks", "run_id = 'run-big'")).isEqualTo(500);
        assertThat(mp.jdbc().queryForObject("SELECT MIN(domain_count) FROM cert_sweep_chunks WHERE run_id = 'run-big'", Integer.class))
                .isEqualTo(20);
        ClaimedChunk c = claim("w1");
        assertThat(c.runId()).isEqualTo("run-big");
        assertThat(c.chunkNo()).isZero();
        assertThat(c.entries()).hasSize(20);
    }

    // ── sahiplenme ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sahiplenme: id sırası, deneme 1, 32 karakter belirteç, kira = şimdi + kira, satırda sahip / belirteç / an")
    void claimBasics() {
        store.createRun(spec("run-c", 60, 20));
        ClaimedChunk a = claim("w1");
        ClaimedChunk b = claim("w2");
        assertThat(b.id()).isGreaterThan(a.id());
        assertThat(a.attempts()).isEqualTo(1);
        assertThat(a.claimToken()).hasSize(32).isNotEqualTo(b.claimToken());
        assertThat(a.claimedMs()).isEqualTo(T0);
        assertThat(a.leaseUntilMs()).isEqualTo(T0 + LEASE);
        assertThat(a.coordinator()).as("koşunun koordinatörü döner (FX-WORKER)").isEqualTo("coord-00000001");
        assertThat(a.createdMs()).isEqualTo(T0);
        assertThat(a.avoidWorker()).isNull();
        assertThat(a.secondOpinion()).isFalse();
        Map<String, Object> row = chunk(a.id());
        assertThat(row).containsEntry("state", "CLAIMED").containsEntry("claimed_by", "w1")
                .containsEntry("claim_token", a.claimToken()).containsEntry("attempts", 1).containsEntry("lease_until_ms", T0 + LEASE);
        assertThat(store.claim("w3", LEASE, Long.MAX_VALUE)).isPresent();
        assertThat(store.claim("w3", LEASE, Long.MAX_VALUE)).as("üç parça bitti").isEmpty();
        assertThat(store.claim(null, LEASE, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("Sahiplenme kapısı: koordinatör nabzı 60 sn'den eski → ölü koşunun parçası alınmaz (tam 60 sn sınırda alınır); oluşturma eşiği")
    void claimGateHeartbeat() {
        store.createRun(spec("run-g", 20, 20));
        assertThat(store.claim("w1", LEASE, T0)).as("oluşturma eşiği: yalnız ÖNCE yazılanlar").isEmpty();
        clock.advance(CertChunking.COORDINATOR_ALIVE_MS + 1);
        assertThat(store.claim("w1", LEASE, Long.MAX_VALUE)).as("koordinatör nabzı eski — ölü koşunun parçası alınmaz").isEmpty();
        clock.advance(-1);
        assertThat(store.claim("w1", LEASE, Long.MAX_VALUE)).as("nabız tam 60 sn önce — canlı sayılır").isPresent();
        store.createRun(spec("run-g1", 20, 20));
        assertThat(store.claim("w1", LEASE, clock.nowMs() - 10_000)).as("10 sn'den yeni parça (lider çalması değil)").isEmpty();
        clock.advance(10_001);
        assertThat(store.claim("w1", LEASE, clock.nowMs() - 10_000)).as("10 sn'den eski parça (lider çalması)").isPresent();
    }

    @Test
    @DisplayName("Sahiplenme kapısı: koşu RUNNING değilse (DECIDING / kapalı) parçası alınmaz")
    void claimGateRunState() {
        store.createRun(spec("run-g2", 20, 20));
        assertThat(store.markDeciding("run-g2", "{}", 0, 0, 0)).isTrue();
        assertThat(store.claim("w1", LEASE, Long.MAX_VALUE)).as("DECIDING koşunun parçası alınmaz").isEmpty();
        store.createRun(spec("run-g4", 20, 20));
        assertThat(store.finish("run-g4", CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_SHUTDOWN, true)).isTrue();
        assertThat(store.claim("w1", LEASE, Long.MAX_VALUE)).as("kapalı koşunun (parçaları kalmış) parçası alınmaz").isEmpty();
    }

    @Test
    @DisplayName("Sahiplenme kapısı: ikinci görüş parçası kaçınılan işçiye (aykırı pod) verilmez, diğerine verilir")
    void claimGateAvoidWorker() {
        store.createRun(spec("run-g3", 5, 5));
        CertChunkSpec opinion = new CertChunkSpec(1, List.of(entry(0)), "w-bad");
        assertThat(store.addChunks("run-g3", List.of(opinion))).isEqualTo(1);
        assertThat(claim("w-bad").chunkNo()).as("ilk parça herkese açık").isZero();
        assertThat(store.claim("w-bad", LEASE, Long.MAX_VALUE)).as("ikinci görüş parçası kaçınılan işçiye verilmez").isEmpty();
        ClaimedChunk o = claim("w-good");
        assertThat(o.chunkNo()).isEqualTo(1);
        assertThat(chunk(o.id())).containsEntry("avoid_worker", "w-bad");
    }

    @Test
    @DisplayName("Sahiplenme kapısı (FX-WORKER SW-M1 / CR-L1 / CR-H-1 b): oluşturma eşiği yalnız BAŞKA koordinatörün ilk görüş parçalarına; "
            + "kendi koşusu ve ikinci görüş muaf; MIN eşik = yalnız muaflar; dönen satır koordinatör / bekleme / kaçınılan işçi taşır")
    void claimGateLeaderExemptions() {
        store.createRun(spec("run-x", 20, 20));   // koordinatör "coord-00000001", parçalar T0'da
        assertThat(store.claim("w1", LEASE, T0)).as("başka koordinatörün 0 sn'lik ilk görüş parçası — eşik").isEmpty();
        assertThat(store.claim("w1", LEASE, Long.MIN_VALUE)).as("MIN eşik: yalnız muaf parçalar — yok").isEmpty();
        ClaimedChunk own = store.claim("coord-00000001", LEASE, Long.MIN_VALUE).orElseThrow();
        assertThat(own.runId()).as("kendi koşusu eşikten muaf").isEqualTo("run-x");
        assertThat(own.coordinator()).isEqualTo("coord-00000001");
        assertThat(own.createdMs()).isEqualTo(T0);
        assertThat(own.pendingMsAtClaim()).isZero();

        store.createRun(spec("run-y", 5, 5));
        CertChunkSpec opinion = new CertChunkSpec(1, List.of(entry(0)), "w-bad");
        assertThat(store.addChunks("run-y", List.of(opinion))).isEqualTo(1);
        clock.advance(3_000);
        store.heartbeat("run-y");
        ClaimedChunk so = store.claim("w1", LEASE, Long.MIN_VALUE).orElseThrow();
        assertThat(so.chunkNo()).as("ikinci görüş eşikten muaf (ilk görüş parçası 0 değil)").isEqualTo(1);
        assertThat(so.avoidWorker()).isEqualTo("w-bad");
        assertThat(so.secondOpinion()).isTrue();
        assertThat(so.createdMs()).isEqualTo(T0);
        assertThat(so.pendingMsAtClaim()).isEqualTo(3_000);
        assertThat(store.claim("w-bad", LEASE, Long.MAX_VALUE).orElseThrow().chunkNo())
                .as("kaçınılan işçi yine ilk görüş parçasını alabilir").isZero();

        store.createRun(spec("run-z", 20, 20));
        clock.advance(15_000);
        store.heartbeat("run-z");
        ClaimedChunk aged = store.claim("w2", LEASE, clock.nowMs() - 10_000).orElseThrow();
        assertThat(aged.runId()).isEqualTo("run-z");
        assertThat(aged.pendingMsAtClaim()).as("bekleme = sahiplenme − oluşturma").isEqualTo(15_000);
        assertThat(aged.coordinator()).isEqualTo("coord-00000001");
    }

    @Test
    @DisplayName("Sahiplenme: kira dolunca başka işçi yeniden alır (deneme 2); ikinci kayıptan sonra parça KAYIP — işçilere verilmez")
    void leaseExpiryReclaimThenLost() {
        store.createRun(spec("run-l", 20, 20));
        ClaimedChunk first = claim("w1");
        clock.advance(LEASE - 1);
        store.heartbeat("run-l");
        assertThat(store.claim("w2", LEASE, Long.MAX_VALUE)).as("kira sürüyor").isEmpty();
        clock.advance(2);
        store.heartbeat("run-l");
        ClaimedChunk second = claim("w2");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(store.complete(completion(first, "w1"))).as("eski sahibin belirteci geçersiz").isFalse();

        clock.advance(LEASE + 1);
        store.heartbeat("run-l");
        assertThat(store.claim("w3", LEASE, Long.MAX_VALUE)).as("deneme tavanı (2) — koordinatör devralır").isEmpty();
        ChunkState st = store.chunkStates("run-l").get(0);
        assertThat(st.lost(clock.nowMs())).isTrue();
    }

    @Test
    @DisplayName("İki pod (dört depo) aynı anda 50 parçaya uzanır — her parça tam bir kez sahiplenilir (taşınabilir CAS)")
    void podsRaceEachChunkOnce() {
        store.createRun(spec("run-race", 1_000, 20));
        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            JdbcCertSweepStore s = new JdbcCertSweepStore(mp.jdbc(), clock, null);
            String owner = "pod" + w;
            tasks.add(() -> {
                List<Long> got = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    Optional<ClaimedChunk> c = s.claim(owner, LEASE, Long.MAX_VALUE);
                    if (c.isEmpty()) break;
                    got.add(c.get().id());
                }
                return got;
            });
        }
        List<List<Long>> res = mp.race(tasks);
        Set<Long> all = new HashSet<>();
        int total = 0;
        for (List<Long> r : res) {
            total += r.size();
            all.addAll(r);
        }
        assertThat(total).isEqualTo(50);
        assertThat(all).hasSize(50);
        assertThat(count("cert_sweep_chunks", "run_id = 'run-race' AND state = 'CLAIMED' AND attempts = 1")).isEqualTo(50);
    }

    // ── yenileme / tamamlama / bırakma / iptal ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yenileme: yalnız bu sahibin CLAIMED parçaları; boş küme deyim atmaz; kaybedilen eksik sayılır")
    void renew() {
        store.createRun(spec("run-r", 60, 20));
        ClaimedChunk a = claim("w1");
        ClaimedChunk b = claim("w1");
        ClaimedChunk c = claim("w2");
        clock.advance(15_000);
        assertThat(store.renew("w1", List.of(a.id(), b.id(), c.id()), LEASE)).as("w2'nin parçası yenilenmez").isEqualTo(2);
        assertThat(chunk(a.id())).containsEntry("lease_until_ms", T0 + 15_000 + LEASE);
        assertThat(chunk(c.id())).containsEntry("lease_until_ms", T0 + LEASE);
        assertThat(store.cancel("run-r", b.id())).isTrue();
        assertThat(store.renew("w1", List.of(a.id(), b.id()), LEASE)).as("iptal edilen yenilenmez").isEqualTo(1);
        assertThat(store.renew("w1", List.of(), LEASE)).isZero();
        assertThat(store.renew(null, List.of(a.id()), LEASE)).isZero();
    }

    @Test
    @DisplayName("Tamamlama: belirteç CAS'ı + koşu RUNNING; sayılar, işçi, bitiş yazılır, kira boşalır; geç / iptal / kapalı koşu → false")
    void complete() {
        store.createRun(spec("run-k", 60, 20));
        ClaimedChunk a = claim("w1");
        clock.advance(3_000);
        List<Map<String, Object>> res = new ArrayList<>();
        for (CertChunkSpec.Entry e : a.entries()) {
            res.add(e.index() % 4 == 0 ? Map.of("status", "error", "error_class", "NETWORK") : Map.of("status", "valid", "warning", true));
        }
        ChunkCompletion done = ChunkCompletion.of(a, "w1-instance", res, 5);
        ChunkCompletion forged = new ChunkCompletion(a.id(), "yanlis", "w9", done.resultsJson(), 20, 0, 0, 0);
        assertThat(store.complete(forged)).as("yanlış belirteç").isFalse();
        assertThat(store.complete(done)).isTrue();
        Map<String, Object> row = chunk(a.id());
        assertThat(row).containsEntry("state", "DONE").containsEntry("checked", 20).containsEntry("network_errors", 5)
                .containsEntry("errors", 5).containsEntry("warnings", 15).containsEntry("worker", "w1-instance")
                .containsEntry("finished_ms", T0 + 3_000);
        assertThat(row.get("lease_until_ms")).isNull();
        assertThat(store.complete(done)).as("ikinci kez yazılmaz").isFalse();

        Map<Long, String> json = store.readResults("run-k", List.of(a.id()));
        List<ChunkCompletion.Result> back = ChunkCompletion.decodeResults(json.get(a.id()));
        assertThat(back).hasSize(20);
        assertThat(back.get(0).index()).isEqualTo(a.entries().get(0).index());
        assertThat(back.get(0).result()).containsEntry("status", "error");

        ClaimedChunk b = claim("w1");
        assertThat(store.cancel("run-k", b.id())).isTrue();
        assertThat(store.complete(completion(b, "w1"))).as("koordinatör devraldı").isFalse();

        ClaimedChunk c = claim("w1");
        assertThat(store.finish("run-k", CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_LOCK_LOST, true)).isTrue();
        assertThat(store.complete(completion(c, "w1"))).as("koşu kapandı — sonuç düşer").isFalse();
    }

    @Test
    @DisplayName("Bırakma (kapanış): PENDING'e, deneme bir geri, sahip / belirteç / kira boşalır; başkasının ve DONE parçaya dokunulmaz")
    void release() {
        store.createRun(spec("run-b", 60, 20));
        ClaimedChunk a = claim("w1");
        ClaimedChunk b = claim("w1");
        ClaimedChunk c = claim("w2");
        assertThat(store.complete(completion(b, "w1"))).isTrue();
        assertThat(store.release("w1", List.of(a.id(), b.id(), c.id()))).isEqualTo(1);
        Map<String, Object> row = chunk(a.id());
        assertThat(row).containsEntry("state", "PENDING").containsEntry("attempts", 0);
        assertThat(row.get("claimed_by")).isNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_until_ms")).isNull();
        assertThat(chunk(b.id())).containsEntry("state", "DONE");
        assertThat(chunk(c.id())).containsEntry("state", "CLAIMED").containsEntry("claimed_by", "w2");
        assertThat(claim("w3").id()).as("bırakılan hemen yeniden alınır").isEqualTo(a.id());
        assertThat(store.release("w1", List.of())).isZero();
    }

    @Test
    @DisplayName("İptal (devralma): PENDING / CLAIMED → CANCELLED; DONE ya da başka koşu → false")
    void cancel() {
        store.createRun(spec("run-x2", 60, 20));
        List<ChunkState> s = store.chunkStates("run-x2");
        ClaimedChunk a = claim("w1");
        ClaimedChunk b = claim("w1");
        assertThat(store.complete(completion(b, "w1"))).isTrue();
        assertThat(store.cancel("run-x2", a.id())).isTrue();
        assertThat(store.cancel("run-x2", s.get(2).id())).as("PENDING de iptal edilir").isTrue();
        assertThat(store.cancel("run-x2", b.id())).as("DONE iptal edilmez — sonucu toplanır").isFalse();
        assertThat(store.cancel("baska", a.id())).isFalse();
        assertThat(store.chunkStates("run-x2")).extracting(ChunkState::state).containsExactly("CANCELLED", "DONE", "CANCELLED");
        assertThat(store.claim("w2", LEASE, Long.MAX_VALUE)).isEmpty();
    }

    // ── koordinatör yaşam döngüsü ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Nabız / parça durumları / karar / kapanış: DONE parçaları siler, kapanış yolunda parçalar kalır; kapalı koşu 0 satır")
    void lifecycle() {
        store.createRun(spec("run-life", 45, 20));
        clock.advance(10_000);
        assertThat(store.heartbeat("run-life")).isTrue();
        assertThat(run("run-life")).containsEntry("heartbeat_ms", T0 + 10_000);
        ClaimedChunk a = claim("w1");
        List<ChunkState> st = store.chunkStates("run-life");
        assertThat(st).extracting(ChunkState::chunkNo).containsExactly(0, 1, 2);
        assertThat(st.get(0)).satisfies(c -> {
            assertThat(c.claimed()).isTrue();
            assertThat(c.claimedBy()).isEqualTo("w1");
            assertThat(c.claimedMs()).isEqualTo(T0 + 10_000);
            assertThat(c.leaseUntilMs()).isEqualTo(T0 + 10_000 + LEASE);
            assertThat(c.domainCount()).isEqualTo(20);
            assertThat(c.createdMs()).isEqualTo(T0);
            assertThat(c.worker()).isNull();
            assertThat(c.checked()).isNull();
        });
        assertThat(st.get(2).pending()).isTrue();
        assertThat(st.get(2).claimedMs()).isNull();
        assertThat(store.complete(completion(a, "w1"))).isTrue();
        assertThat(store.chunkStates("run-life").get(0)).satisfies(c -> {
            assertThat(c.done()).isTrue();
            assertThat(c.worker()).isEqualTo("w1");
            assertThat(c.checked()).isEqualTo(20);
            assertThat(c.networkErrors()).isZero();
            assertThat(c.finishedMs()).isEqualTo(T0 + 10_000);
        });
        assertThat(store.readResults("run-life", List.of(a.id(), st.get(1).id()))).as("yalnız DONE").containsOnlyKeys(a.id());
        assertThat(store.readResults("baska", List.of(a.id()))).as("koşu uyuşmalı").isEmpty();

        String workers = CertSweepStatus.encodeWorkers(List.of(new CertSweepStatus.WorkerTally("w1", 1, 20, 0)));
        assertThat(store.markDeciding("run-life", workers, 2, 1, 3)).isTrue();
        assertThat(store.heartbeat("run-life")).as("DECIDING'de nabız 0 satır").isFalse();
        assertThat(store.markDeciding("run-life", workers, 0, 0, 0)).as("ikinci kez").isFalse();
        assertThat(run("run-life")).containsEntry("state", "DECIDING").containsEntry("takeovers", 2).containsEntry("reclaims", 1)
                .containsEntry("second_opinions", 3).containsEntry("workers_json", workers);

        clock.advance(5_000);
        assertThat(store.finish("run-life", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false)).isTrue();
        assertThat(run("run-life")).containsEntry("state", "DONE").containsEntry("outcome", "DECIDED")
                .containsEntry("finished_ms", T0 + 15_000);
        assertThat(count("cert_sweep_chunks", "run_id = 'run-life'")).as("kapanışta parçalar silinir").isZero();
        assertThat(store.finish("run-life", CertSweepStore.RUN_FAILED, CertSweepStore.OUTCOME_FAILED, false))
                .as("kapalı koşu yeniden kapatılmaz").isFalse();
        assertThat(run("run-life")).containsEntry("state", "DONE");

        store.createRun(spec("run-sd", 20, 20));
        assertThat(store.finish("run-sd", CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_SHUTDOWN, true)).isTrue();
        assertThat(count("cert_sweep_chunks", "run_id = 'run-sd'")).as("kapanış yolu tek UPDATE — parçalar kalır").isEqualTo(1);
    }

    @Test
    @DisplayName("Kapanış asla fırlatmaz (veritabanı kapalı) — false döner")
    void finishNeverThrows() {
        store.createRun(spec("run-n", 20, 20));
        mp.close();
        assertThatCode(() -> assertThat(store.finish("run-n", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false))
                .isFalse()).doesNotThrowAnyException();
        assertThat(store.finish(null, CertSweepStore.RUN_DONE, null, true)).isFalse();
    }

    @Test
    @DisplayName("Öksüz kapatma: açık her koşu ABANDONED / ORPHANED (önceki hâlleriyle döner); kapalı koşuların ve koşusuz parçalar silinir")
    void settleOrphans() {
        store.createRun(spec("run-o1", 40, 20));
        clock.advance(1_000);
        store.createRun(spec("run-o2", 20, 20));
        store.markDeciding("run-o2", "{}", 0, 0, 0);
        store.createRun(spec("run-closed", 20, 20));
        store.finish("run-closed", CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_SHUTDOWN, true);   // parçaları kaldı
        mp.jdbc().update("INSERT INTO cert_sweep_chunks (run_id, chunk_no, state, domains_json, domain_count, attempts, created_ms) "
                + "VALUES ('run-ghost', 0, 'PENDING', '[]', 0, 0, ?)", T0);
        clock.advance(2_000);

        List<CertSweepStatus.Run> settled = store.settleOrphans();
        assertThat(settled).extracting(CertSweepStatus.Run::runId).containsExactly("run-o1", "run-o2");
        assertThat(settled).extracting(CertSweepStatus.Run::state).containsExactly("RUNNING", "DECIDING");
        for (String id : List.of("run-o1", "run-o2")) {
            assertThat(run(id)).containsEntry("state", "ABANDONED").containsEntry("outcome", "ORPHANED")
                    .containsEntry("finished_ms", T0 + 3_000);
        }
        assertThat(run("run-closed")).containsEntry("outcome", "SHUTDOWN");
        assertThat(count("cert_sweep_chunks", "1 = 1")).isZero();
        assertThat(store.settleOrphans()).isEmpty();
    }

    @Test
    @DisplayName("Budama: yalnız eşikten eski KAPALI koşular, en çok verilen sayı; açık koşu ne kadar eski olsa da kalır")
    void pruneRuns() {
        for (int i = 0; i < 5; i++) {
            store.createRun(spec("old-" + i, 20, 20));
            store.finish("old-" + i, CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false);
            clock.advance(1_000);
        }
        store.createRun(spec("old-open", 20, 20));
        clock.advance(CertChunking.RUN_RETENTION_MS);
        store.createRun(spec("new", 20, 20));
        store.finish("new", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false);
        long cutoff = clock.nowMs() - CertChunking.RUN_RETENTION_MS;
        assertThat(store.pruneRuns(cutoff, 3)).isEqualTo(3);
        assertThat(store.pruneRuns(cutoff, 10)).isEqualTo(2);
        assertThat(mp.jdbc().queryForList("SELECT run_id FROM cert_sweep_runs ORDER BY run_id", String.class))
                .containsExactly("new", "old-open");
        assertThat(store.pruneRuns(cutoff, 0)).isZero();
    }

    @Test
    @DisplayName("Durum: son koşular (yeni önce), açık koşu, gruplu parçalar (bekleyen / işçi başına sahiplenilen ve biten, ağ hatası)")
    void status() {
        store.createRun(spec("run-s1", 20, 20));
        store.finish("run-s1", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false);
        clock.advance(1_000);
        store.createRun(spec("run-s2", 100, 20));
        ClaimedChunk a = claim("w1");
        ClaimedChunk b = claim("w2");
        claim("w2");
        List<Map<String, Object>> res = new ArrayList<>();
        for (int i = 0; i < 20; i++) res.add(Map.of("status", i < 4 ? "error" : "valid"));
        assertThat(store.complete(ChunkCompletion.of(b, "w2", res, 4))).isTrue();
        clock.advance(2_000);

        CertSweepStatus s = store.status(5);
        assertThat(s.nowMs()).isEqualTo(T0 + 3_000);
        assertThat(s.dbClockStale()).isFalse();
        assertThat(s.lastRuns()).extracting(CertSweepStatus.Run::runId).containsExactly("run-s2", "run-s1");
        assertThat(s.openRun().runId()).isEqualTo("run-s2");
        assertThat(s.chunks(CertSweepStore.CHUNK_PENDING)).isEqualTo(2);
        assertThat(s.chunks(CertSweepStore.CHUNK_CLAIMED)).isEqualTo(2);
        assertThat(s.chunks(CertSweepStore.CHUNK_DONE)).isEqualTo(1);
        assertThat(s.domainsMeasured()).isEqualTo(20);
        assertThat(s.oldestPendingAgeMs()).isEqualTo(2_000L);
        assertThat(s.perWorker()).extracting(CertSweepStatus.WorkerTally::worker).containsExactly("w2", "w1");
        assertThat(s.perWorker().get(0)).isEqualTo(new CertSweepStatus.WorkerTally("w2", 2, 40, 4));
        assertThat(a).isNotNull();

        clock.stale = true;
        assertThat(store.status(1).lastRuns()).hasSize(1);
        assertThat(store.status(1).dbClockStale()).isTrue();
        store.finish("run-s2", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false);
        assertThat(store.status(5).openRun()).isNull();
        assertThat(store.status(5).openRunChunks()).isEmpty();
    }

    // ── tablo yok ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tablo yok: sahiplenme boş / yenileme ve bırakma 0 / tamamlama false / kapanış false; kurulum ve durum fırlatır; "
            + "yokluk ezberlenir, sahiplenme 60 sn'de bir yeniden denetler ve tablo gelince 'hazır' olur")
    void missingTables() {
        MultiPodTestSupport empty = MultiPodTestSupport.create();
        try {
            AtomicInteger statements = new AtomicInteger();
            JdbcTemplate counting = new JdbcTemplate(countingConnections(empty.dataSource(), statements));
            JdbcCertSweepStore s = new JdbcCertSweepStore(counting, counting, clock, null, nanos::get);
            assertThat(s.claim("w1", LEASE, Long.MAX_VALUE)).isEmpty();
            assertThat(s.tablesReady()).isFalse();
            assertThat(s.renew("w1", List.of(1L), LEASE)).isZero();
            assertThat(s.release("w1", List.of(1L))).isZero();
            assertThat(s.complete(new ChunkCompletion(1L, "t", "w", "[]", 0, 0, 0, 0))).isFalse();
            assertThat(s.finish("r", CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false)).isFalse();
            assertThatThrownBy(() -> s.createRun(spec("r", 20, 20))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> s.status(5)).isInstanceOf(DataAccessException.class);

            int before = statements.get();
            assertThat(s.claim("w1", LEASE, Long.MAX_VALUE)).isEmpty();
            assertThat(statements.get()).as("yokluk ezberli — 60 sn dolmadan sorgu yok").isEqualTo(before);

            new CertSweepSchemaPatch(empty.jdbc()).apply();   // öteki pod kurdu (bu depoya bildirmeden)
            nanos.addAndGet(59_000_000_000L);
            assertThat(s.claim("w1", LEASE, Long.MAX_VALUE)).isEmpty();
            assertThat(s.tablesReady()).isFalse();
            nanos.addAndGet(2_000_000_000L);
            assertThat(s.claim("w1", LEASE, Long.MAX_VALUE)).as("yeniden denetim — tablo var, aday yok").isEmpty();
            assertThat(s.tablesReady()).isTrue();
            s.createRun(spec("r", 20, 20));
            assertThat(s.claim("w1", LEASE, Long.MAX_VALUE)).isPresent();
        } finally {
            empty.close();
        }
    }

    /** Bağlantı açılışlarını sayan veri kaynağı (her SQL deyimi bir bağlantı ister). */
    static javax.sql.DataSource countingConnections(javax.sql.DataSource real, AtomicInteger connections) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(CertSweepSqlH2Test.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ── kurulum biçimi ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring kurucusu: kendi şablonları 10 sn / kapanış 3 sn zaman aşımlı; sınıf düzeyinde NOT_SUPPORTED (çağıranın işlemine katılmaz)")
    void productionWiring() {
        JdbcCertSweepStore s = new JdbcCertSweepStore(mp.jdbc(), clock);
        assertThat(s.queryTimeouts()).containsExactly(CertSweepStore.QUERY_TIMEOUT_SECONDS, CertSweepStore.SHUTDOWN_QUERY_TIMEOUT_SECONDS);
        assertThat(mp.jdbc().getQueryTimeout()).as("paylaşılan şablona dokunulmaz").isEqualTo(-1);
        Transactional tx = JdbcCertSweepStore.class.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    @DisplayName("domains_json: bozuk JSON / biçimi bozuk öğe → atlanır (koordinatör eksikleri ölçer); sözdizimi kalıcı")
    void domainsJsonDecode() {
        assertThat(store.decodeDomains("{bozuk")).isEmpty();
        assertThat(store.decodeDomains(null)).isEmpty();
        assertThat(store.decodeDomains("[{\"i\":0,\"domain\":\"a\"},{\"i\":-1,\"domain\":\"b\"},{\"i\":2},{\"i\":3,\"domain\":\"\"},"
                + "{\"i\":4,\"domain\":\"e\",\"port\":8443,\"use_proxy\":true,\"tls_mode\":\"default\",\"timeout_seconds\":9}]"))
                .containsExactly(new CertChunkSpec.Entry(0, "a", null, false, null, null),
                        new CertChunkSpec.Entry(4, "e", 8443, true, "default", 9));
        assertThat(JdbcCertSweepStore.encodeDomains(List.of(new CertChunkSpec.Entry(3, "x.tr", 443, false, "", null))))
                .isEqualTo("[{\"i\":3,\"domain\":\"x.tr\",\"port\":443,\"use_proxy\":false,\"tls_mode\":\"\"}]");
    }
}
