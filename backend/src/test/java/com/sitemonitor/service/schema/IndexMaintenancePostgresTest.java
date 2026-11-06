package com.sitemonitor.service.schema;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schema.IndexCatalog.IndexFacts;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Outcome;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * İndeks bakımı GERÇEK PostgreSQL'de (2026-11-06) — {@code mvn -Ppostgres-it test}. Kanıtlanan: yeni kurulum gereksiz
 * indeksleri hiç yaratmaz ve kapsayanlar vardır; eski kurulum taklidinde temizlik yalnız doğrulananları düşürür (kapsayanı
 * olmayan / geçersiz kapsayanlı / benzersiz aday kalır); şişirilmiş bir indeks {@code REINDEX CONCURRENTLY} ile küçülür,
 * yarıda kalmış {@code _ccnew} kalıntısı temizlenir, benzersiz dizine dokunulmaz; açık bir işlem / anlık görüntü yeniden
 * kurmayı beklettiğinde iş {@code lock_timeout}'ta (sınırlı sürede) vazgeçer ve kalıntı temizlenir.
 */
@PostgresIntegration
class IndexMaintenancePostgresTest {

    private static final long MB = 1024L * 1024;

    /** Hedef → yeni kurulumda var olması gereken kapsayan (entity / açılış yaması / ertelenmiş katalog). */
    private static final Map<String, String> COVERING = Map.ofEntries(
            Map.entry("idx_pri_check", "idx_pri_check_id"),
            Map.entry("idx_mch_type_key_hour", "monitor_check_hourly_pkey"),
            Map.entry("idx_act_type", "idx_act_type_mon_time"),
            Map.entry("idx_act_type_mon", "idx_act_type_mon_time"),
            Map.entry("idx_portc_monitor_id", "idx_portc_monitor_checked"),
            Map.entry("idx_pc_monitor_id", "idx_pc_monitor_checked"),
            Map.entry("idx_dnsr_monitor_id", "idx_dnsr_monitor_checked"),
            Map.entry("idx_kwr_monitor_id", "idx_kwr_monitor_checked"),
            Map.entry("idx_pingc_monitor_id", "idx_pingc_monitor_checked"),
            Map.entry("idx_hc_monitor_id", "idx_hc_monitor_checked"),
            Map.entry("idx_dc_monitor_id", "idx_dc_monitor_checked"),
            Map.entry("idx_sc_monitor_id", "idx_sc_monitor_checked"),
            Map.entry("idx_pri_monitor_id", "idx_pri_monitor_checked"),
            Map.entry("idx_cc_domain", "idx_cc_domain_ts"),
            Map.entry("idx_uc_domain_port", "idx_uc_domain_port_checked"));

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @BeforeAll
    static void waitForDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);   // ertelenmiş kurucu SMIDXBLD'yi bıraksın, idx_act_type_mon_time kurulsun
    }

    private static IndexCatalog.Factory catalogs() {
        return () -> JdbcIndexCatalog.open(jdbc().getDataSource());
    }

    private static RedundantIndexCleanup cleanup(List<Target> targets, String marker) {
        return new RedundantIndexCleanup(targets, marker, () -> true, catalogs(), List::of, () -> false, job -> false, true,
                3_000, 600_000);
    }

    @SuppressWarnings("unchecked")
    private static IndexBloatMaintenance maintenance(List<String> tables, long lockTimeoutMs) {
        return new IndexBloatMaintenance(tables, () -> true, catalogs(), mock(ObjectProvider.class), true, lockTimeoutMs, 6,
                4096 * MB, 10 * 60_000L, MB, System::currentTimeMillis, Thread::sleep, () -> false);
    }

    private static Boolean validity(String index) {
        List<Boolean> v = jdbc().queryForList("SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = ?",
                Boolean.class, index);
        return v.isEmpty() ? null : v.getFirst();
    }

    private static long size(String index) {
        Long b = jdbc().queryForObject("SELECT pg_relation_size(to_regclass(?))", Long.class, index);
        return b == null ? -1 : b;
    }

    private static List<String> leftovers(String table) {
        return jdbc().queryForList("SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_class t"
                + " ON t.oid = i.indrelid WHERE t.relname = ? AND c.relname ~ '_cc(new|old)[0-9]*$'", String.class, table);
    }

    /** 300 bin satır, (m, c) indeksi, %95'i silinir + VACUUM ANALYZE: indeks ~14 MB, beklenen ~0,75 MB. */
    private static void bloatedTable(String table) {
        bloatedTable(table, false);
    }

    /** {@code withC}: ikinci şişmiş indeks {@code <tablo>_c (c)} — iki yeniden kurma arasında kilit bırakma gerçek PostgreSQL'de. */
    private static void bloatedTable(String table, boolean withC) {
        JdbcTemplate j = jdbc();
        j.execute("DROP TABLE IF EXISTS " + table);
        j.execute("CREATE TABLE " + table + " (id BIGSERIAL PRIMARY KEY, m BIGINT, c VARCHAR(40))");
        j.execute("INSERT INTO " + table + " (m, c) SELECT i % 20, to_char(timestamp '2026-01-01' + i * interval '1 second',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM generate_series(1, 300000) i");
        j.execute("CREATE INDEX " + table + "_mc ON " + table + " (m, c)");
        if (withC) j.execute("CREATE INDEX " + table + "_c ON " + table + " (c)");
        j.execute("DELETE FROM " + table + " WHERE id % 20 <> 0");
        j.execute("VACUUM (ANALYZE) " + table);
    }

    // ── 1. Yeni kurulum ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yeni kurulum: 15 gereksiz indeksin HİÇBİRİ yok; her birinin kapsayanı var ve geçerli; iş fasulyeleri kurulu")
    void freshInstall_noRedundantIndexes_coveringsValid() {
        for (Target t : RedundantIndexCleanup.TARGETS) {
            assertThat(validity(t.index())).as("%s yeni kurulumda yaratılmamalı", t.index()).isNull();
            assertThat(validity(COVERING.get(t.index()))).as("%s'in kapsayanı %s", t.index(), COVERING.get(t.index())).isTrue();
        }
        assertThat(COVERING.keySet()).containsExactlyInAnyOrderElementsOf(
                RedundantIndexCleanup.TARGETS.stream().map(Target::index).toList());
        assertThat(PostgresIt.app().bean(RedundantIndexCleanup.class)).isNotNull();
        assertThat(PostgresIt.app().bean(IndexBloatMaintenance.class)).isNotNull();
        assertThat(PostgresIt.app().bean(SchedulerService.class)).isNotNull();
    }

    @Test
    @DisplayName("Katalog okuması gerçek pg_index ile: sütunlar, anahtar sayısı, opclass / collation, PK / kısıt, kısmi bayrağı")
    void catalogReadsRealIndexes() throws Exception {
        try (IndexCatalog db = catalogs().open()) {
            List<IndexFacts> port = db.indexes("port_checks");
            IndexFacts comp = port.stream().filter(i -> i.name().equals("idx_portc_monitor_checked")).findFirst().orElseThrow();
            assertThat(comp.columns()).containsExactly("monitor_id", "checked_at");
            assertThat(comp.keyCount()).isEqualTo(2);
            assertThat(comp.opclasses()).hasSize(2);
            assertThat(comp.collations()).hasSize(2);
            assertThat(comp.options()).containsExactly("0", "0");
            assertThat(comp.valid()).isTrue();
            assertThat(comp.qualifiedName()).endsWith(".idx_portc_monitor_checked");
            IndexFacts pk = port.stream().filter(IndexFacts::primary).findFirst().orElseThrow();
            assertThat(pk.unique()).isTrue();
            assertThat(pk.constraintBacked()).isTrue();
            assertThat(port.stream().filter(i -> i.name().equals("idx_portc_fail")).findFirst().orElseThrow().partial()).isTrue();
            assertThat(db.indexes("it_no_such_table")).isEmpty();
            assertThat(db.building("port_checks")).isFalse();
        }
    }

    // ── 2. Eski kurulum taklidi: gereksiz indeks temizliği ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Eski kurulum: 15 aday + kapsayanı olmayan / geçersiz kapsayanlı / benzersiz deneme adayları → yalnız doğrulananlar düşer;"
            + " nişan yalnız iş bitince")
    void oldInstall_dropsOnlyVerified() throws Exception {
        JdbcTemplate j = jdbc();
        String marker = "it-ixm-" + UUID.randomUUID();
        List<String> probes = List.of("it_ixm_nocover", "it_ixm_invcover", "it_ixm_unique");
        try {
            for (Target t : RedundantIndexCleanup.TARGETS) {
                j.execute("CREATE INDEX " + t.index() + " ON " + t.table() + " (" + String.join(", ", t.columns()) + ")");
            }
            for (String p : probes) j.execute("DROP TABLE IF EXISTS " + p);
            j.execute("CREATE TABLE it_ixm_nocover (a INT, b INT)");
            j.execute("CREATE INDEX it_ixm_nocover_a ON it_ixm_nocover (a)");
            j.execute("CREATE TABLE it_ixm_invcover (a INT, b INT)");
            j.execute("INSERT INTO it_ixm_invcover VALUES (1, 1), (1, 1)");
            j.execute("CREATE INDEX it_ixm_invcover_a ON it_ixm_invcover (a)");
            try {
                j.execute("CREATE UNIQUE INDEX CONCURRENTLY it_ixm_invcover_ab ON it_ixm_invcover (a, b)");
            } catch (Exception expected) { /* tekrarlı veri → INVALID kapsayan kalır */ }
            assertThat(validity("it_ixm_invcover_ab")).as("hazırlık: geçersiz kapsayan").isFalse();
            j.execute("CREATE TABLE it_ixm_unique (a INT, b INT)");
            j.execute("CREATE UNIQUE INDEX it_ixm_unique_a ON it_ixm_unique (a)");
            j.execute("CREATE INDEX it_ixm_unique_ab ON it_ixm_unique (a, b)");

            List<Target> targets = new ArrayList<>(RedundantIndexCleanup.TARGETS);
            targets.add(new Target("it_ixm_nocover_a", "it_ixm_nocover", List.of("a")));
            targets.add(new Target("it_ixm_invcover_a", "it_ixm_invcover", List.of("a")));
            targets.add(new Target("it_ixm_unique_a", "it_ixm_unique", List.of("a")));
            RedundantIndexCleanup c = cleanup(targets, marker);

            assertThat(c.runOnce()).isEqualTo(Outcome.INCOMPLETE);
            for (Target t : RedundantIndexCleanup.TARGETS) {
                assertThat(validity(t.index())).as("%s düşmeliydi", t.index()).isNull();
                assertThat(validity(COVERING.get(t.index()))).as("kapsayan %s kalmalı", COVERING.get(t.index())).isTrue();
            }
            assertThat(validity("it_ixm_nocover_a")).as("kapsayanı yok → kalır").isTrue();
            assertThat(validity("it_ixm_invcover_a")).as("kapsayanı geçersiz → kalır").isTrue();
            assertThat(validity("it_ixm_unique_a")).as("benzersiz → bizim değil, kalır").isTrue();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, marker))
                    .as("kapsayanı olmayan aday varken nişan yazılmaz").isZero();

            // kapsayanlar düzelince sonraki tur biter; benzersiz aday yine kalır ama nişanı engellemez
            j.execute("CREATE INDEX it_ixm_nocover_ab ON it_ixm_nocover (a, b)");
            j.execute("DROP INDEX it_ixm_invcover_ab");
            j.execute("CREATE INDEX it_ixm_invcover_ab ON it_ixm_invcover (a, b)");
            assertThat(c.runOnce()).isEqualTo(Outcome.DONE);
            assertThat(validity("it_ixm_nocover_a")).isNull();
            assertThat(validity("it_ixm_invcover_a")).isNull();
            assertThat(validity("it_ixm_unique_a")).isTrue();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, marker))
                    .isOne();
            assertThat(c.runOnce()).as("nişan var → hiçbir şey").isEqualTo(Outcome.DONE);
        } finally {
            for (Target t : RedundantIndexCleanup.TARGETS) j.execute("DROP INDEX IF EXISTS " + t.index());
            for (String p : probes) j.execute("DROP TABLE IF EXISTS " + p);
            j.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", marker);
        }
    }

    @Test
    @DisplayName("Düşürmeyi bekleten açık okuma işlemi: lock_timeout'ta vazgeçer (sınırlı), aday GEÇERSİZ kalır; işlem bitince sonraki tur düşürür")
    void dropBlockedByOpenTransaction_givesUpThenFinishes() throws Exception {
        JdbcTemplate j = jdbc();
        String marker = "it-ixm-" + UUID.randomUUID();
        j.execute("DROP TABLE IF EXISTS it_ixm_blockdrop");
        j.execute("CREATE TABLE it_ixm_blockdrop (a INT, b INT)");
        j.execute("CREATE INDEX it_ixm_blockdrop_a ON it_ixm_blockdrop (a)");
        j.execute("CREATE INDEX it_ixm_blockdrop_ab ON it_ixm_blockdrop (a, b)");
        RedundantIndexCleanup c = cleanup(List.of(new Target("it_ixm_blockdrop_a", "it_ixm_blockdrop", List.of("a"))), marker);
        try (Connection blocker = PostgresIt.app().newSession()) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) { st.execute("SELECT count(*) FROM it_ixm_blockdrop"); }
            long t0 = System.nanoTime();
            assertThat(c.runOnce()).isEqualTo(Outcome.INCOMPLETE);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).as("sınırlı bekleme").isLessThan(30);
            assertThat(validity("it_ixm_blockdrop_a")).as("yarıda kalan DROP CONCURRENTLY adayı geçersiz bırakır").isFalse();
            blocker.rollback();
        } finally {
            try {
                assertThat(c.runOnce()).isEqualTo(Outcome.DONE);
                assertThat(validity("it_ixm_blockdrop_a")).isNull();
            } finally {
                j.execute("DROP TABLE IF EXISTS it_ixm_blockdrop");
                j.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", marker);
            }
        }
    }

    // ── 3. Şişmiş indeks bakımı ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Şişirilmiş indeks REINDEX CONCURRENTLY ile küçülür; _ccnew kalıntısı temizlenir; benzersiz (PK) dizine dokunulmaz")
    void bloatedIndexIsRebuilt_leftoverCleaned_uniqueUntouched() throws Exception {
        String t = "it_ixm_bloat1";
        try {
            bloatedTable(t, true);
            try {
                jdbc().execute("CREATE UNIQUE INDEX CONCURRENTLY " + t + "_dup_ccnew ON " + t + " (m)");
            } catch (Exception expected) { /* tekrarlı m → INVALID kalıntı */ }
            assertThat(validity(t + "_dup_ccnew")).as("hazırlık: kalıntı").isFalse();
            long before = size(t + "_mc");
            long pkBefore = size(t + "_pkey");
            assertThat(before).as("hazırlık: şişmiş").isGreaterThan(10 * MB);

            IndexBloatMaintenance.Report r = maintenance(List.of(t), 5_000).maintain();

            assertThat(r.locked()).isTrue();
            assertThat(r.leftoversDropped()).isEqualTo(1);
            assertThat(r.rebuilt()).as("en büyük fazla önce; arada SMIDXBLD bırakılıp yeniden alındı").containsExactly(t + "_mc", t + "_c");
            assertThat(r.failed()).isEmpty();
            assertThat(r.stoppedEarly()).isFalse();
            assertThat(size(t + "_c")).isLessThan(2 * MB);
            try (Connection probe = PostgresIt.app().newSession(); Statement st = probe.createStatement()) {
                // oturum düzeyi kilit: dene + bırak AYNI oturumda (havuz bağlantısı değil)
                try (var rs = st.executeQuery("SELECT pg_try_advisory_lock(" + DeferredIndexBuilder.LOCK_KEY + ")")) {
                    assertThat(rs.next() && rs.getBoolean(1)).as("koşu bitince SMIDXBLD bırakılmış").isTrue();
                }
                st.execute("SELECT pg_advisory_unlock(" + DeferredIndexBuilder.LOCK_KEY + ")");
            }
            long after = size(t + "_mc");
            assertThat(after).as("yeniden kurulmuş boyut (önce %d)", before).isLessThan(2 * MB);
            assertThat(validity(t + "_mc")).isTrue();
            assertThat(size(t + "_pkey")).as("benzersiz dizin yeniden kurulmaz").isEqualTo(pkBefore);
            assertThat(leftovers(t)).isEmpty();
            // ikinci koşu: şişme yok → hiçbir DDL
            IndexBloatMaintenance.Report again = maintenance(List.of(t), 5_000).maintain();
            assertThat(again.rebuilt()).isEmpty();
            assertThat(again.leftoversDropped()).isZero();
        } finally {
            jdbc().execute("DROP TABLE IF EXISTS " + t);
        }
    }

    @Test
    @DisplayName("Boşta bekleyen okuma işlemi yeniden kurmayı bekletir: lock_timeout'ta vazgeçer (sınırlı), _ccold kalır; işlem bitince temizlenir")
    void rebuildBlockedByIdleTransaction_givesUpAndCleansLater() throws Exception {
        String t = "it_ixm_bloat2";
        try {
            bloatedTable(t);
            try (Connection blocker = PostgresIt.app().newSession()) {
                blocker.setAutoCommit(false);
                try (Statement st = blocker.createStatement()) { st.execute("SELECT count(*) FROM " + t); }
                long t0 = System.nanoTime();
                IndexBloatMaintenance.Report r = maintenance(List.of(t), 2_000).maintain();
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).as("sınırlı bekleme").isLessThan(30);
                assertThat(r.failed()).containsExactly(t + "_mc");
                // Kesildiği aşamaya göre kalıntı _ccnew (yeni dizin bitmedi; JDBC oturumunun açık işlemi anlık görüntü tutar) ya da
                // _ccold (yer değiştirme oldu, eski dizin düşürülemedi — psql'de boşta bekleyen işlem); iki durumda da adlı dizin geçerli
                assertThat(validity(t + "_mc")).isTrue();
                assertThat(leftovers(t)).as("kalıntı okuyucu kilidi yüzünden hemen düşürülemedi").hasSize(1);
                assertThat(r.leftoversFailed()).isEqualTo(1);
                blocker.rollback();
            }
            IndexBloatMaintenance.Report next = maintenance(List.of(t), 2_000).maintain();
            assertThat(next.leftoversDropped()).isEqualTo(1);
            assertThat(leftovers(t)).isEmpty();
            assertThat(size(t + "_mc")).isLessThan(2 * MB);
        } finally {
            jdbc().execute("DROP TABLE IF EXISTS " + t);
        }
    }

    @Test
    @DisplayName("Açık REPEATABLE READ anlık görüntüsü doğrulama aşamasını bekletir: lock_timeout'ta vazgeçer, _ccnew hemen temizlenir")
    void rebuildBlockedBySnapshot_givesUpAndCleansAtOnce() throws Exception {
        String t = "it_ixm_bloat3";
        try {
            bloatedTable(t);
            jdbc().execute("DROP TABLE IF EXISTS it_ixm_other");
            jdbc().execute("CREATE TABLE it_ixm_other (x INT)");
            long before = size(t + "_mc");
            try (Connection blocker = PostgresIt.app().newSession()) {
                blocker.setAutoCommit(false);
                blocker.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (Statement st = blocker.createStatement()) { st.execute("SELECT count(*) FROM it_ixm_other"); }
                long t0 = System.nanoTime();
                IndexBloatMaintenance.Report r = maintenance(List.of(t), 2_000).maintain();
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).as("sınırlı bekleme").isLessThan(30);
                assertThat(r.failed()).containsExactly(t + "_mc");
                assertThat(leftovers(t)).as("yarıda kalan _ccnew hemen düşürüldü").isEmpty();
                assertThat(size(t + "_mc")).as("eski dizin yerinde").isEqualTo(before);
                blocker.rollback();
            }
        } finally {
            jdbc().execute("DROP TABLE IF EXISTS " + t);
            jdbc().execute("DROP TABLE IF EXISTS it_ixm_other");
        }
    }
}
