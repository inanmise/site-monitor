package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Çok pod R2a (MP-38) gerçek PostgreSQL'de: iki {@link AuditService} örneği ("iki pod", ayrı JVM kilitleri) işlem İÇİNDE
 * ve işlem DIŞINDA aynı anda denetim yazar → her satırın seq'i tekil, zincir sağlam (işlem içindeki yazım COMMIT sonrası
 * advisory lock altında). {@code ux_audit_seq} tekil + kısmi: kesimden sonraki aynı seq eklenemez, kesim öncesi serbest.
 * {@code mvn -Ppostgres-it test}.
 */
@PostgresIntegration
class AuditChainPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static void awaitDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
    }

    private static AuditService pod() {
        AuditService s = new AuditService(PostgresIt.app().bean(AuditLogRepository.class), mock(GeoIpService.class),
                mock(NewDeviceNotifier.class), mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        s.setChainLockSupport(jdbc(), PostgresIt.app().bean(PlatformTransactionManager.class));
        // Elle kurulan pod'da @Value yok: yazılamayan kayıt (ör. ux_audit_seq ihlali) NPE yerine fallback dosyasına düşsün —
        // gerilemede test açık bir satır sayısı farkıyla düşer.
        org.springframework.test.util.ReflectionTestUtils.setField(s, "fallbackFile",
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "site-monitor-it", "audit-fallback-" + UUID.randomUUID() + ".jsonl").toString());
        return s;
    }

    @Test
    @DisplayName("PostgreSQL: ux_audit_seq tekil + kısmi; iki pod işlem içi / dışı aynı anda yazar → tekil seq, zincir sağlam")
    void uniqueSeqIndex_andTwoPodsInterleaving_chainValid() throws Exception {
        awaitDeferredIndexes();
        JdbcTemplate jdbc = jdbc();
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_audit_seq'", String.class);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("(seq)").contains("seq_locked IS NOT NULL");

        // Dizin kapsamı: NEGATİF seq (zincirin ucunu — MAX(seq) — etkilemez; uygulamanın eşzamanlı yazımları bozulmaz).
        long probe = -1_000_000L - (UUID.randomUUID().getMostSignificantBits() & 0xFFFFF);
        String locked = "INSERT INTO audit_log (seq, event_type, event_time, actor, outcome, seq_locked) VALUES (?, 'IT_PROBE', ?, 'it', 'SUCCESS', TRUE)";
        String legacy = "INSERT INTO audit_log (seq, event_type, event_time, actor, outcome) VALUES (?, 'IT_PROBE', ?, 'it', 'SUCCESS')";
        try {
            jdbc.update(locked, probe, "2026-10-23T00:00:01");
            assertThatThrownBy(() -> jdbc.update(locked, probe, "2026-10-24T00:00:01"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // eski kodun satırı (seq_locked NULL — tarih ne olursa olsun) kapsam dışı: eski çatallar kurulumu düşürmez
            jdbc.update(legacy, probe, "2026-12-01T00:00:01");
            jdbc.update(legacy, probe, "2026-12-02T00:00:01");
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE event_type = 'IT_PROBE' AND seq < 0");
        }

        AuditService a = pod();
        AuditService b = pod();
        TransactionTemplate tt = new TransactionTemplate(PostgresIt.app().bean(PlatformTransactionManager.class));
        String tag = "it-chain-" + UUID.randomUUID().toString().substring(0, 8);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CyclicBarrier start = new CyclicBarrier(writers);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    AuditService svc = n % 2 == 0 ? a : b;
                    for (int k = 0; k < 5; k++) {
                        if ((n + k) % 2 == 0) {
                            tt.executeWithoutResult(st -> {
                                svc.recordSystemEvent("IT_CHAIN_TX", "SYSTEM", tag, "pod" + (n % 2));
                                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            });
                        } else {
                            svc.recordSystemEvent("IT_CHAIN", "SYSTEM", tag, "pod" + (n % 2));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id = ?", Integer.class, tag);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id = ? AND seq_locked = TRUE", Integer.class, tag))
                .as("yeni yazıcının her satırı kilit damgalı (indeks kapsamında)").isEqualTo(writers * 5);
        Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT seq) FROM audit_log WHERE resource_id = ?", Integer.class, tag);
        assertThat(rows).isEqualTo(writers * 5);
        assertThat(distinct).as("seq'ler tekil").isEqualTo(rows);
        AuditService.ChainVerification v = a.verifyChain();
        assertThat(v.ok()).as("zincir sağlam: %s", v).isTrue();
    }

    // ── 2026-10-23 prod hatası: seq'siz eski satırlar + PostgreSQL NULLS FIRST ──────────────────────────────────────

    /** Zincirden önce yazılmış satır (seq / hash yok). */
    private static void insertLegacy(JdbcTemplate jdbc, String tag) {
        jdbc.update("INSERT INTO audit_log (event_type, event_time, actor, outcome, resource_id)"
                + " VALUES ('IT_LEGACY', '2026-06-01T08:00:00', 'old', 'SUCCESS', ?)", tag);
    }

    /** Hatalı uç sorgusunun (20.168.0) yazdığı satır: seq 1 + GENESIS + kilit damgası. */
    private static void insertDegenerate(JdbcTemplate jdbc, String tag, String time) {
        jdbc.update("INSERT INTO audit_log (seq, prev_hash, row_hash, seq_locked, event_type, event_time, actor, outcome, resource_id)"
                + " VALUES (1, 'GENESIS', md5(random()::text), TRUE, 'IT_DEGENERATE', ?, 'it', 'SUCCESS', ?)", time, tag);
    }

    private static List<Long> interleave(String tag, int writers) throws Exception {
        AuditService a = pod();
        AuditService b = pod();
        TransactionTemplate tt = new TransactionTemplate(PostgresIt.app().bean(PlatformTransactionManager.class));
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CyclicBarrier start = new CyclicBarrier(writers);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    AuditService svc = n % 2 == 0 ? a : b;
                    for (int k = 0; k < 4; k++) {
                        if ((n + k) % 2 == 0) {
                            tt.executeWithoutResult(st -> svc.recordSystemEvent("IT_CHAIN_TX", "SYSTEM", tag, "pod" + (n % 2)));
                        } else {
                            svc.recordSystemEvent("IT_CHAIN", "SYSTEM", tag, "pod" + (n % 2));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return jdbc().queryForList("SELECT seq FROM audit_log WHERE resource_id = ? ORDER BY seq", Long.class, tag);
    }

    private static boolean indexValid(JdbcTemplate jdbc) {
        List<Boolean> v = jdbc.queryForList(AuditChainRestartJob.SQL_INDEX_VALID, Boolean.class);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    @Test
    @DisplayName("PostgreSQL NULLS FIRST: seq'siz eski satırlar varken iki pod artan, tekil seq yazar (eskiden hep seq 1 + GENESIS)")
    void legacyNullRows_twoPods_increasingUniqueSeq() throws Exception {
        awaitDeferredIndexes();
        JdbcTemplate jdbc = jdbc();
        String legacyTag = "it-legacy-" + UUID.randomUUID().toString().substring(0, 8);
        insertLegacy(jdbc, legacyTag);
        insertLegacy(jdbc, legacyTag);
        try {
            // Gerçek PostgreSQL: eski uç sorgusunun ("ORDER BY seq DESC LIMIT 1") gördüğü "son satır" seq'siz satır
            assertThat(jdbc.queryForList("SELECT seq FROM audit_log ORDER BY seq DESC LIMIT 1", Long.class)).containsExactly((Long) null);
            Long maxBefore = jdbc.queryForObject("SELECT MAX(seq) FROM audit_log", Long.class);

            String tag = "it-nulls-" + UUID.randomUUID().toString().substring(0, 8);
            List<Long> seqs = interleave(tag, 6);

            assertThat(seqs).hasSize(24).doesNotHaveDuplicates();
            assertThat(seqs).as("hepsi zincirin ucundan sonra (seq 1 değil)").allMatch(s -> s > (maxBefore == null ? 0 : maxBefore));
            AuditService.ChainVerification v = pod().verifyChain();
            assertThat(v.ok()).as("zincir sağlam: %s", v).isTrue();
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE resource_id = ? AND seq IS NULL", legacyTag);
        }
    }

    @Test
    @DisplayName("PostgreSQL: bozuk zincir → yeniden başlatma satırı, doğrulama oradan, öncesi doğrulanamaz; ux_audit_seq yeniden kurulur")
    void restartJob_degenerateChain_verifiedFromRestart_uniqueIndexBuildable() throws Exception {
        awaitDeferredIndexes();
        JdbcTemplate jdbc = jdbc();
        String tag = "it-degenerate-" + UUID.randomUUID().toString().substring(0, 8);
        // Prod'daki durumu kur: indeks hiç kurulamamış (yinelenen kilitli seq 1'ler), seq'siz eski satırlar + bozuk dönem
        jdbc.execute("DROP INDEX IF EXISTS " + AuditChainRestartJob.INDEX);
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AuditChainRestartJob.MARKER);
        try {
            insertLegacy(jdbc, tag);
            insertDegenerate(jdbc, tag, "2026-07-28T10:00:00");
            insertDegenerate(jdbc, tag, "2026-08-15T10:00:00");
            insertDegenerate(jdbc, tag, "2026-10-22T10:00:00");
            assertThat(pod().verifyChain().ok()).as("bugünkü davranış: bozuk dönem kırık görünür").isFalse();

            AuditService svc = pod();
            // Üretimdeki kurucu: YALNIZ ux_audit_seq (DeferredIndexBuilder.only — inceleme F6); lastRun()'a yazmaz, yani
            // SchemaPatchPostgresTest'in okuduğu açılış koşusu değişmez.
            CompletableFuture<?>[] kicked = new CompletableFuture<?>[1];
            AuditChainRestartJob job = new AuditChainRestartJob(svc, jdbc, (n, t) -> Optional.ofNullable(t.get()),
                    () -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()), null,
                    () -> kicked[0] = DeferredIndexBuilder.only(jdbc, AuditChainRestartJob.INDEX).startInBackground(),
                    System::currentTimeMillis);
            CompletableFuture<DeferredIndexBuilder.Result> bootRun = DeferredIndexBuilder.lastRun();
            job.run();

            AuditLog restart = PostgresIt.app().bean(AuditLogRepository.class)
                    .findTopByEventTypeAndSeqIsNotNullOrderBySeqAsc(AuditService.EVENT_CHAIN_RESTART).orElseThrow();
            assertThat(restart.getPrevHash()).isEqualTo("GENESIS");
            assertThat(restart.getSeq()).isEqualTo(jdbc.queryForObject("SELECT MAX(seq) FROM audit_log WHERE id < ?", Long.class,
                    restart.getId()) + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq = 1 AND seq_locked IS NOT NULL", Integer.class))
                    .as("yeniden başlatma öncesi yinelenen kilit damgaları kalktı").isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id = ? AND seq = 1 AND prev_hash = 'GENESIS'",
                    Integer.class, tag)).as("eski satırlar yazıldığı gibi").isEqualTo(3);

            // Ertelenmiş kurucu bu iş tarafından yeniden başlatıldı → indeks artık kurulabiliyor
            CompletableFuture<?> kick = job.lastIndexKick();
            assertThat(kick).as("indeks geçersizken kurucu yeniden başlatılmalı").isNotNull().isSameAs(kicked[0]);
            Object result = kick.get(5, TimeUnit.MINUTES);
            assertThat(result).asString().as("yalnız ux_audit_seq kuruldu (katalogun geri kalanı koşmadı)")
                    .contains("built=1", "valid=0", "failed=0");
            assertThat(DeferredIndexBuilder.lastRun()).as("açılış koşusu kaydı değişmedi").isSameAs(bootRun);
            assertThat(indexValid(jdbc)).as("ux_audit_seq geçerli").isTrue();
            job.run();
            assertThat(job.finished()).as("indeks geçerli → bu pod'da iş bitti").isTrue();

            long unverified = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq < ?", Long.class, restart.getSeq());
            AuditService.ChainVerification v = svc.verifyChain();
            assertThat(v.ok()).as("yeniden başlatmadan itibaren sağlam: %s", v).isTrue();
            assertThat(v.verifiedFrom().id()).isEqualTo(restart.getId());
            assertThat(v.verifiedFrom().kind()).isEqualTo(AuditService.VerifiedFrom.RESTART);
            assertThat(v.verifiedFrom().seq()).isEqualTo(restart.getSeq());
            assertThat(v.unverified()).isEqualTo(unverified).isGreaterThanOrEqualTo(3);

            // Ardından iki pod iç içe yazar: indeks varken tekil seq, zincir sağlam
            List<Long> seqs = interleave(tag + "-after", 4);
            assertThat(seqs).hasSize(16).doesNotHaveDuplicates().allMatch(s -> s > restart.getSeq());
            assertThat(svc.verifyChain().ok()).isTrue();
        } finally {
            // Başka sınıfların beklediği indeks her koşulda geri gelsin (test yarıda düşse bile)
            jdbc.update("UPDATE audit_log SET seq_locked = NULL WHERE resource_id = ? AND seq = 1", tag);
            if (!indexValid(jdbc)) {
                jdbc.execute("DROP INDEX IF EXISTS " + AuditChainRestartJob.INDEX);
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + AuditChainRestartJob.INDEX
                        + " ON audit_log(seq) WHERE seq_locked IS NOT NULL");
            }
        }
    }

    // ── İnceleme F1: uç sorgusunun planı (yalnız PostgreSQL'de görülür; H2'nin planlayıcı istatistiği yok) ───────────

    /** AuditLogRepository#findTopBySeqIsNotNullOrderBySeqDesc'in ürettiği sorgunun biçimi (AuditChainNullsFirstH2Test bağlar). */
    static final String TAIL_SQL = "SELECT a.* FROM audit_log a WHERE a.seq IS NOT NULL ORDER BY a.seq DESC FETCH FIRST 1 ROWS ONLY";

    private static List<com.fasterxml.jackson.databind.JsonNode> planNodes(JdbcTemplate jdbc, String sql) throws Exception {
        String json = jdbc.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class);
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(0).get("Plan");
        List<com.fasterxml.jackson.databind.JsonNode> out = new ArrayList<>();
        java.util.ArrayDeque<com.fasterxml.jackson.databind.JsonNode> todo = new java.util.ArrayDeque<>(List.of(root));
        while (!todo.isEmpty() && out.size() < 200) {
            com.fasterxml.jackson.databind.JsonNode n = todo.pop();
            out.add(n);
            for (com.fasterxml.jackson.databind.JsonNode c : n.path("Plans")) todo.push(c);
        }
        return out;
    }

    private static List<String> nodeTypes(List<com.fasterxml.jackson.databind.JsonNode> nodes) {
        return nodes.stream().map(n -> n.path("Node Type").asText()).toList();
    }

    @Test
    @DisplayName("PostgreSQL planı (inceleme F1): istatistik 'zincirli satırların hepsi seq 1' derken uç sorgusu tek satırlık geriye indeks taraması — Sort / Seq Scan yok")
    void tailQueryPlan_degenerateStatistics_backwardIndexScanNoSort() throws Exception {
        JdbcTemplate jdbc = jdbc();
        String tag = "it-plan-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // Prod'un durumu: zincirli satırların ezici çoğunluğu seq 1 (kilit damgasız — ux_audit_seq kapsamı dışında)
            jdbc.update("INSERT INTO audit_log (seq, prev_hash, row_hash, event_type, event_time, actor, outcome, resource_id)"
                    + " SELECT 1, 'GENESIS', md5(g::text), 'IT_PLAN', '2026-08-01T00:00:00', 'it', 'SUCCESS', ?"
                    + " FROM generate_series(1, 30000) g", tag);
            jdbc.execute("ALTER TABLE audit_log ALTER COLUMN seq SET (n_distinct = 1)");
            jdbc.execute("ANALYZE audit_log");
            assertThat(jdbc.queryForObject("SELECT n_distinct FROM pg_stats WHERE schemaname = current_schema()"
                    + " AND tablename = 'audit_log' AND attname = 'seq'", Double.class))
                    .as("istatistik: tek seq değeri (prod'daki bozuk zincir)").isEqualTo(1.0);

            List<com.fasterxml.jackson.databind.JsonNode> tail = planNodes(jdbc, TAIL_SQL);
            assertThat(nodeTypes(tail)).as("uç sorgusu sıralamaz, tabloyu taramaz: %s", nodeTypes(tail))
                    .doesNotContain("Sort", "Incremental Sort", "Seq Scan", "Parallel Seq Scan", "Gather Merge");
            assertThat(tail).as("idx_audit_seq geriye taranır").anySatisfy(n -> {
                assertThat(n.path("Node Type").asText()).isEqualTo("Index Scan");
                assertThat(n.path("Scan Direction").asText()).isEqualTo("Backward");
                assertThat(n.path("Index Name").asText()).isEqualTo("idx_audit_seq");
            });

            // Kapının duyarlılığı: eşitlik kırıcılı ESKİ biçim (", id DESC") aynı istatistikte SIRALAR — gerileme görülür
            List<String> old = nodeTypes(planNodes(jdbc, TAIL_SQL.replace("ORDER BY a.seq DESC", "ORDER BY a.seq DESC, a.id DESC")));
            assertThat(old).as("eşitlik kırıcılı biçim: %s", old).containsAnyOf("Sort", "Incremental Sort");
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE resource_id = ?", tag);
            jdbc.execute("ALTER TABLE audit_log ALTER COLUMN seq RESET (n_distinct)");
            jdbc.execute("ANALYZE audit_log");
        }
    }
}
