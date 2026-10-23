package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.evalstate.EvalStateSql.ChainRow;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kalıcı değerlendirme durumu GERÇEK PostgreSQL'de (2026-10-23, çok pod Sürüm 3-1, A1) — {@code mvn -Ppostgres-it test}:
 * açılış yaması dört tabloyu, dizini ve {@code fillfactor = 70}'i kurdu; {@code ON CONFLICT … DO UPDATE … WHERE} koruması
 * iki yazanın 1000 anahtarlık yarışında en yeni (gen, seq)'i bırakır (hem tek ifadelik upsert hem taşınabilir yol);
 * kuşak CAS'ı 8 iş parçacığında birbirinden farklı değerler verir. Satırlar kendi {@code alert_type} önekiyle yazılır ve
 * her testten sonra silinir (paylaşılan veritabanı).
 */
@PostgresIntegration
class EvalStatePostgresTest {

    private static final String TYPE = "ITR31_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @AfterEach
    void cleanUp() {
        for (String t : List.of(EvalStateSql.EVAL_STATE, EvalStateSql.LAST_STATUS, EvalStateSql.CONFIRMATIONS)) {
            jdbc().update("DELETE FROM " + t + " WHERE alert_type = ?", TYPE);
        }
        jdbc().update("DELETE FROM " + EvalStateSql.TYPE_STATE + " WHERE alert_type = ?", TYPE);
    }

    @Test
    @DisplayName("PostgreSQL: yama tabloları, dizini ve doluluk ayarını kurdu; ikinci koşu değişiklik yapmaz")
    void schemaInPlace() {
        for (String t : EvalStateSql.TABLES) {
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, t);
            assertThat(n).as(t).isEqualTo(1);
        }
        Boolean idx = jdbc().queryForObject("SELECT to_regclass('idx_mls_type_domain') IS NOT NULL", Boolean.class);
        assertThat(idx).isTrue();
        for (String t : List.of(EvalStateSql.EVAL_STATE, EvalStateSql.LAST_STATUS, EvalStateSql.CONFIRMATIONS)) {
            String opts = jdbc().queryForObject("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass(?::text)",
                    String.class, t);
            assertThat(opts).as(t).contains("fillfactor=70");
        }
        assertThat(new EvalStateSchemaPatch(jdbc()).apply()).as("ikinci koşu").isZero();
        assertThat(new EvalStateSql(jdbc()).postgres()).isTrue();
    }

    private static List<RecoveryRow> rows(String prefix, int n, int value, long seq) {
        List<RecoveryRow> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new RecoveryRow(TYPE, prefix + i, value, null, null, null, seq));
        return out;
    }

    private void race(EvalStateSql sql, String prefix) throws Exception {
        // Yazan A eski kuşak 10, büyük seq (çok kez); yazan B yeni kuşak 11, küçük seq — B her anahtarda kazanmalı.
        Callable<Void> a = () -> {
            for (int round = 0; round < 3; round++) sql.writeRecovery(rows(prefix, 1000, 1, 1_000 + round), "itA", 10, 1L);
            return null;
        };
        Callable<Void> b = () -> {
            sql.writeRecovery(rows(prefix, 1000, 2, 5), "itB", 11, 2L);
            sql.writeRecovery(rows(prefix, 1000, 3, 4), "itB", 11, 3L);   // aynı kuşakta küçük seq: yok sayılır
            return null;
        };
        runTogether(List.of(a, b));
        List<Map<String, Object>> stored = jdbc().queryForList(
                "SELECT gen, seq, recovery_up_count, writer FROM " + EvalStateSql.EVAL_STATE + " WHERE alert_type = ? "
                        + "AND domain LIKE ?", TYPE, prefix + "%");
        assertThat(stored).hasSize(1000);
        assertThat(stored).allSatisfy(r -> {
            assertThat(((Number) r.get("gen")).longValue()).isEqualTo(11L);
            assertThat(((Number) r.get("seq")).longValue()).isEqualTo(5L);
            assertThat(((Number) r.get("recovery_up_count")).intValue()).isEqualTo(2);
            assertThat(r.get("writer")).isEqualTo("itB");
        });
    }

    @Test
    @DisplayName("PostgreSQL ON CONFLICT … WHERE: 2 yazan × 1000 anahtar yarışı → en yeni (gen, seq) kalır")
    void upsertRace_newestWins() throws Exception {
        race(new EvalStateSql(jdbc()), "pg-");
    }

    @Test
    @DisplayName("PostgreSQL'de taşınabilir yol (tür bilinmiyor): aynı yarış, aynı sonuç — yarışan INSERT işlemi bozmaz")
    void portableRace_newestWins() throws Exception {
        race(EvalStateSql.withDialect(jdbc(), null), "pt-");
    }

    @Test
    @DisplayName("PostgreSQL: son durum ve zincir upsert'i (gen, seq) korumasıyla; zincir silme koşullu")
    void otherTablesOnPostgres() {
        EvalStateSql sql = new EvalStateSql(jdbc());
        sql.writeLastStatus(List.of(new LastStatusRow(TYPE, "m:1", "pg.example", "443", 1L, 2L, true, false, true,
                "NETWORK", "S", 10L, 10L, 3)), "itA", 20, 1L);
        sql.writeLastStatus(List.of(new LastStatusRow(TYPE, "m:1", "pg.example", "443", 1L, 2L, true, true, false,
                null, "M", 11L, 11L, 2)), "itA", 20, 2L);
        assertThat(jdbc().queryForObject("SELECT up FROM " + EvalStateSql.LAST_STATUS + " WHERE alert_type = ?",
                Boolean.class, TYPE)).isFalse();
        String key = TYPE + ":pg.example:443";
        ChainRow c = new ChainRow(EvalStateKeys.keyHash(key), key, 9, TYPE, "pg.example", "443", "port", 1L, 0, 3, 30_000L,
                "2026-10-23T10:00:00", null, null, "{\"a\":1}", false, "RUNNING", 1L, 1);
        sql.writeChains(List.of(c), "itA", 20, 1L);
        sql.writeChains(List.of(c.withState("FINISHING", 2)), "itA", 20, 2L);
        assertThat(jdbc().queryForObject("SELECT state FROM " + EvalStateSql.CONFIRMATIONS + " WHERE alert_type = ?",
                String.class, TYPE)).isEqualTo("FINISHING");
        assertThat(sql.deleteChains(List.of(new EvalStateSql.ChainDelete(c.keyHash(), 9, 3)), "itB", 20)).isZero();
        assertThat(sql.deleteChains(List.of(new EvalStateSql.ChainDelete(c.keyHash(), 9, 3)), "itA", 20)).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL kuşak CAS: 8 iş parçacığı → birbirinden farklı, artan kuşaklar; nabız yalnız en yenide")
    void generationCas_eightThreads() throws Exception {
        EvalStateSql sql = new EvalStateSql(jdbc());
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String writer = "itgen-" + i;
            tasks.add(() -> {
                for (int attempt = 0; attempt < 50; attempt++) {
                    long g = sql.acquireGeneration(writer, System.currentTimeMillis());
                    if (g > 0) return g;
                }
                return 0L;
            });
        }
        List<Long> gens = runTogether(tasks);
        assertThat(gens).allMatch(g -> g > 0);
        Set<Long> distinct = new HashSet<>(gens);
        assertThat(distinct).as("CAS iki iş parçacığına aynı kuşağı vermez").hasSize(8);
        long max = gens.stream().mapToLong(Long::longValue).max().orElseThrow();
        EvalStateSql.GenerationRow row = sql.generationRow();
        assertThat(row.gen()).isGreaterThanOrEqualTo(max);
        // Uygulamanın kendi kaydedicisi aynı satırı paylaşır: yalnız satırda duran kuşak + yazan nabız atabilir.
        assertThat(sql.heartbeat(row.gen(), row.writer(), System.currentTimeMillis())).isTrue();
        assertThat(sql.heartbeat(row.gen() - 1, row.writer(), System.currentTimeMillis())).isFalse();
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return t.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(120, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
