package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.evalstate.EvalStateSql.ChainDelete;
import com.sitemonitor.service.evalstate.EvalStateSql.ChainRow;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.Stored;
import com.sitemonitor.service.evalstate.EvalStateSql.TypeRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kalıcı değerlendirme durumu SQL'i H2'de (taşınabilir yol; 2026-10-23, çok pod Sürüm 3-1, A1): "yalnız daha yeni (gen,
 * seq) kazanır" koruması dört tabloda, UPDATE → INSERT → UPDATE yarış yolu, koşullu zincir silme, kuşak CAS + çit nabzı,
 * ABANDONED işaretleme + devralma sayıları, budama, okuyucular. Her test kendi H2 veritabanında ({@link MultiPodTestSupport})
 * — paylaşılan {@code testdb}'ye satır sızmaz; kapanışta veritabanı silinir.
 */
class EvalStateSqlH2Test {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    EvalStateSql sql;

    @BeforeEach
    void schema() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        sql = new EvalStateSql(mp.jdbc());
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private static RecoveryRow rec(String domain, int count, long seq) {
        return new RecoveryRow("PORT_DOWN", domain, count, count > 0 ? 1_000L + count : null, null, null, seq);
    }

    private int countOf(String domain) {
        return mp.jdbc().queryForObject("SELECT recovery_up_count FROM monitor_eval_state WHERE alert_type = 'PORT_DOWN' "
                + "AND domain = ?", Integer.class, domain);
    }

    @Test
    @DisplayName("H2 türü PostgreSQL değil — taşınabilir yol seçilir")
    void dialectIsPortableOnH2() {
        assertThat(sql.postgres()).isFalse();
    }

    @Test
    @DisplayName("Kurtarma: daha yeni kuşak kazanır; eski kuşak ya da aynı kuşakta küçük seq yok sayılır")
    void recoveryGuard_newerGenWins_olderIgnored() {
        sql.writeRecovery(List.of(rec("a.example", 3, 5)), "pod1", 1, 10_000L);
        assertThat(countOf("a.example")).isEqualTo(3);
        sql.writeRecovery(List.of(rec("a.example", 9, 4)), "pod1", 1, 11_000L);   // aynı kuşak, küçük seq
        assertThat(countOf("a.example")).as("küçük seq ezemez").isEqualTo(3);
        sql.writeRecovery(List.of(rec("a.example", 9, 5)), "pod1", 1, 11_000L);   // aynı seq
        assertThat(countOf("a.example")).as("eşit seq ezemez").isEqualTo(3);
        sql.writeRecovery(List.of(rec("a.example", 2, 6)), "pod1", 1, 12_000L);
        assertThat(countOf("a.example")).as("büyük seq yazar").isEqualTo(2);
        sql.writeRecovery(List.of(rec("a.example", 7, 1_000)), "pod0", 0, 13_000L);   // eski lider, büyük seq
        assertThat(countOf("a.example")).as("eski kuşak ezemez").isEqualTo(2);
        sql.writeRecovery(List.of(rec("a.example", 0, 1)), "pod2", 2, 14_000L);   // yeni lider, küçük seq
        assertThat(countOf("a.example")).as("yeni kuşak kazanır").isZero();
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT writer, gen, seq, updated_ms, updated_at, "
                + "recovery_last_counted_ms FROM monitor_eval_state WHERE domain = 'a.example'");
        assertThat(row).containsEntry("writer", "pod2").containsEntry("gen", 2L).containsEntry("seq", 1L)
                .containsEntry("updated_ms", 14_000L).containsEntry("updated_at", "1970-01-01T00:00:14");
        assertThat(row.get("recovery_last_counted_ms")).as("sıfırlama satırı silmez, null yazar").isNull();
    }

    @Test
    @DisplayName("Toplu yazım: 450 satır (3 parça), karışık yeni / var olan anahtarlar")
    void batchAcrossChunks() {
        List<RecoveryRow> first = new java.util.ArrayList<>();
        for (int i = 0; i < 450; i++) first.add(rec("d" + i, 1, i + 1));
        assertThat(sql.writeRecovery(first, "pod1", 1, 1_000L)).isEqualTo(450);
        List<RecoveryRow> second = new java.util.ArrayList<>();
        for (int i = 0; i < 450; i++) second.add(rec("d" + i, i % 2 == 0 ? 5 : 1, i % 2 == 0 ? 10_000 : 0));
        sql.writeRecovery(second, "pod1", 1, 2_000L);
        assertThat(countOf("d0")).isEqualTo(5);
        assertThat(countOf("d1")).as("eski seq'li tek satırlar dokunulmadı").isEqualTo(1);
        assertThat(mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_eval_state", Integer.class)).isEqualTo(450);
    }

    @Test
    @DisplayName("UPDATE ile INSERT arasında başka yazan (eski) satır ekledi → üçüncü adım UPDATE yine yeni değeri yazar")
    void portableRace_insertConflict_thenUpdateApplies() {
        AtomicBoolean injected = new AtomicBoolean();
        JdbcTemplate racing = new JdbcTemplate(mp.dataSource()) {
            @Override
            public int[] batchUpdate(String s, List<Object[]> args) {
                if (s.startsWith("INSERT INTO monitor_eval_state") && injected.compareAndSet(false, true)) {
                    new EvalStateSql(mp.jdbc()).writeRecovery(List.of(rec("race.example", 8, 1)), "old", 0, 1L);
                }
                return super.batchUpdate(s, args);
            }
        };
        new EvalStateSql(racing).writeRecovery(List.of(rec("race.example", 4, 3)), "pod1", 1, 2L);
        assertThat(injected).isTrue();
        assertThat(countOf("race.example")).as("eski satır araya girdi ama yeni kuşak kazandı").isEqualTo(4);
    }

    @Test
    @DisplayName("Son durum, tür ve zincir tablolarında da aynı koruma; okuyucular null'ları korur")
    void otherTablesGuardAndReadBack() {
        LastStatusRow ls = new LastStatusRow("PORT_DOWN", "m:7", "h.example", "443", 7L, 3L, true, false, true,
                EvalStateSource.ERROR_NETWORK, EvalStateSql.SOURCE_SCHEDULED, 5_000L, 4_000L, 10);
        sql.writeLastStatus(List.of(ls), "pod1", 1, 5_000L);
        sql.writeLastStatus(List.of(new LastStatusRow("PORT_DOWN", "m:7", "h.example", "443", 7L, 3L, true, true, false,
                null, EvalStateSql.SOURCE_MANUAL, 6_000L, 6_000L, 9)), "pod1", 1, 6_000L);   // küçük seq
        List<Stored<LastStatusRow>> back = sql.readLastStatus(1);
        assertThat(back).hasSize(1);
        assertThat(back.get(0).row()).isEqualTo(ls);
        assertThat(back.get(0).writer()).isEqualTo("pod1");

        TypeRow tr = new TypeRow("PORT_DOWN", true, 1_000L, 2_000L, 30_000L, 3);
        sql.writeTypeState(List.of(tr), "pod1", 1, 1L);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", false, null, null, null, 2)), "pod1", 1, 2L);
        assertThat(sql.readTypeState(1)).extracting(Stored::row).containsExactly(tr);

        ChainRow cr = chain("k1", 7, 3, "pod1");
        sql.writeChains(List.of(cr), "pod1", 1, 1L);
        sql.writeChains(List.of(cr.withState(EvalStateSql.STATE_FINISHING, 2)), "pod1", 1, 2L);   // küçük seq
        assertThat(sql.readConfirmations(1)).extracting(Stored::row).containsExactly(cr);
    }

    @Test
    @DisplayName("'*' kuşak satırı tür yazımıyla ASLA ezilmez")
    void typeWriteNeverTouchesGenerationRow() {
        long g = sql.acquireGeneration("pod1", 1_000L);
        sql.writeTypeState(List.of(new TypeRow(EvalStateKeys.GENERATION_ROW, true, 1L, 1L, 1L, 99)), "x", 99, 2L);
        assertThat(sql.generationRow().gen()).isEqualTo(g);
        assertThat(sql.generationRow().writer()).isEqualTo("pod1");
    }

    private static ChainRow chain(String key, long chainId, long seq, String writerIgnored) {
        return new ChainRow(EvalStateKeys.keyHash(key), key, chainId, "PORT_DOWN", "h.example", "443", "port", 7L, 1, 3,
                30_000L, "2026-10-23T10:00:00", 50_000L, "[{\"status\":\"down\"}]", "{\"monitor_id\":7}", false,
                EvalStateSql.STATE_RUNNING, 1_000L, seq);
    }

    @Test
    @DisplayName("Zincir silme koşullu: başka zincir / başka yazan / daha yeni kuşak satırına dokunmaz")
    void chainDeleteIsConditional() {
        sql.writeChains(List.of(chain("k1", 7, 1, "pod1")), "pod1", 2, 1L);
        String h = EvalStateKeys.keyHash("k1");
        assertThat(sql.deleteChains(List.of(new ChainDelete(h, 8, 5)), "pod1", 2)).as("başka zincir").isZero();
        assertThat(sql.deleteChains(List.of(new ChainDelete(h, 7, 5)), "pod2", 2)).as("başka yazan").isZero();
        assertThat(sql.deleteChains(List.of(new ChainDelete(h, 7, 5)), "pod1", 1)).as("satır daha yeni kuşakta").isZero();
        assertThat(sql.deleteChains(List.of(new ChainDelete(h, 7, 5)), "pod1", 3)).as("kendi eski kuşak satırı silinir")
                .isEqualTo(1);
        assertThat(mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_confirmations", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Kuşak CAS: tohum 0 → 1, 2 …; çit nabzı yalnız güncel kuşak + yazanda 1 satır")
    void generationCasAndHeartbeat() {
        assertThat(sql.generationRow()).isNull();
        assertThat(sql.acquireGeneration("pod1", 1_000L)).isEqualTo(1);
        assertThat(sql.acquireGeneration("pod1", 2_000L)).isEqualTo(2);
        assertThat(sql.heartbeat(2, "pod1", 3_000L)).isTrue();
        assertThat(sql.generationRow()).isEqualTo(new EvalStateSql.GenerationRow(2, "pod1", 3_000L));
        assertThat(sql.heartbeat(1, "pod1", 3_000L)).as("eski kuşak").isFalse();
        assertThat(sql.acquireGeneration("pod2", 4_000L)).isEqualTo(3);
        assertThat(sql.heartbeat(2, "pod1", 5_000L)).as("başka lider kuşak aldı").isFalse();
        assertThat(sql.heartbeat(3, "pod1", 5_000L)).as("yazan farklı").isFalse();
        assertThat(sql.heartbeat(3, "pod2", 5_000L)).isTrue();
    }

    @Test
    @DisplayName("GAINED: önceki kuşakların zincirleri ABANDONED, devralınan sayılar yalnız eski kuşak satırlarından")
    void abandonedAndInheritedCounts() {
        long now = 100L * 60 * 60 * 1000;
        sql.writeRecovery(List.of(rec("a", 2, 1), rec("b", 0, 2)), "pod1", 1, now);
        sql.writeLastStatus(List.of(
                new LastStatusRow("PORT_DOWN", "m:1", "a", "443", 1L, 1L, true, false, true, "NETWORK", "S", now - 1_000, now, 3),
                new LastStatusRow("PORT_DOWN", "m:2", "a", "443", 2L, 1L, true, false, false, "OTHER", "S",
                        now - EvalStateRecorder.SIBLING_TTL_MS - 1, now, 4),
                new LastStatusRow("PORT_DOWN", "d:a|443", "a", "443", null, null, null, false, true, "NETWORK", "S", now, now, 5)),
                "pod1", 1, now);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", true, now, now, 30_000L, 6),
                new TypeRow("DNS_FAILURE", false, null, now, 30_000L, 7)), "pod1", 1, now);
        sql.writeChains(List.of(chain("k1", 1, 8, "pod1"), chain("k2", 2, 9, "pod1")), "pod1", 1, now);
        sql.writeRecovery(List.of(rec("c", 4, 1)), "pod2", 2, now);   // bu kuşağın kendi satırı — devralma değil

        assertThat(sql.markAbandoned(2, now)).isEqualTo(2);
        assertThat(sql.markAbandoned(2, now)).as("ikinci kez değişmez").isZero();
        assertThat(sql.countInheritedRecovery(2)).isEqualTo(1);
        assertThat(sql.countInheritedSiblings(2, now - EvalStateRecorder.SIBLING_TTL_MS)).as("TTL içi, kimlikli DOWN").isEqualTo(1);
        assertThat(sql.countInheritedSuppressions(2)).isEqualTo(1);
        assertThat(sql.previousGeneration(2)).isEqualTo(1L);
        assertThat(sql.previousGeneration(1)).isNull();
    }

    @Test
    @DisplayName("Budama: eski son durum, sıfır + zincirsiz eski kurtarma, ABANDONED / bayat zincir; tür satırları kalır")
    void pruneRules() {
        long now = 30L * 24 * 60 * 60 * 1000;
        long old = now - 8L * 24 * 60 * 60 * 1000;
        sql.writeLastStatus(List.of(
                new LastStatusRow("PING_DOWN", "m:1", "a", null, 1L, null, null, true, false, null, "S", old, old, 1),
                new LastStatusRow("PING_DOWN", "m:2", "a", null, 2L, null, null, true, false, null, "S", now, now, 2)),
                "pod1", 1, now);
        sql.writeRecovery(List.of(rec("zero-old", 0, 1)), "pod1", 1, old);
        sql.writeRecovery(List.of(rec("count-old", 2, 2)), "pod1", 1, old);
        sql.writeRecovery(List.of(new RecoveryRow("PORT_DOWN", "chain-old", 0, null, 5L, 1L, 3)), "pod1", 1, old);
        sql.writeRecovery(List.of(rec("zero-new", 0, 4)), "pod1", 1, now);
        sql.writeChains(List.of(chain("stale", 1, 1, "pod1")), "pod1", 1, now - 2L * 60 * 60 * 1000);
        sql.writeChains(List.of(chain("fresh", 2, 2, "pod1")), "pod1", 1, now);
        sql.writeChains(List.of(chain("abandoned", 3, 3, "pod1")), "pod1", 1, now);
        mp.jdbc().update("UPDATE monitor_confirmations SET state = 'ABANDONED' WHERE confirm_key = 'abandoned'");
        sql.writeTypeState(List.of(new TypeRow("PING_DOWN", false, null, old, null, 4)), "pod1", 1, old);

        int[] n = sql.prune(now - 7L * 24 * 60 * 60 * 1000, now - 7L * 24 * 60 * 60 * 1000, now - 60L * 60 * 1000);
        assertThat(n).containsExactly(1, 1, 2);
        assertThat(mp.jdbc().queryForList("SELECT domain FROM monitor_eval_state ORDER BY domain", String.class))
                .containsExactly("chain-old", "count-old", "zero-new");
        assertThat(mp.jdbc().queryForList("SELECT confirm_key FROM monitor_confirmations", String.class)).containsExactly("fresh");
        assertThat(mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_eval_type_state", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL ifadesi: ON CONFLICT … DO UPDATE … WHERE (gen, seq) koruması (gerçek PG: EvalStatePostgresTest)")
    void postgresUpsertShape() {
        assertThat(EvalStateSql.SPEC_EVAL_STATE.pgUpsert)
                .startsWith("INSERT INTO monitor_eval_state AS cur (alert_type, domain, recovery_up_count")
                .contains("ON CONFLICT (alert_type, domain) DO UPDATE SET recovery_up_count = EXCLUDED.recovery_up_count")
                .contains("writer = EXCLUDED.writer, gen = EXCLUDED.gen, seq = EXCLUDED.seq")
                .endsWith("WHERE cur.gen < EXCLUDED.gen OR (cur.gen = EXCLUDED.gen AND cur.seq < EXCLUDED.seq)");
        assertThat(EvalStateSql.SPEC_CONFIRMATIONS.pgUpsert).contains("ON CONFLICT (key_hash) DO UPDATE");
        assertThat(EvalStateSql.SPEC_LAST_STATUS.update)
                .endsWith("WHERE alert_type = ? AND item_key = ? AND (gen < ? OR (gen = ? AND seq < ?))");
        assertThat(EvalStateSql.SPEC_TYPE_STATE.insert).endsWith("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("Metin kırpma sütun sınırına; null kalır")
    void clip() {
        assertThat(EvalStateSql.clip(null, 3)).isNull();
        assertThat(EvalStateSql.clip("abcd", 3)).isEqualTo("abc");
        assertThat(EvalStateSql.clip("ab", 3)).isEqualTo("ab");
        assertThat(EvalStateSql.iso(0L)).isEqualTo("1970-01-01T00:00:00");
    }
}
