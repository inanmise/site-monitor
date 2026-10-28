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
        // Sayımlar ÖNCEKİ kuşağın (2'nin yerini aldığı 1) satırlarıdır (FX-STATE #3: parametre önceki kuşak).
        assertThat(sql.countInheritedRecovery(1)).isEqualTo(1);
        assertThat(sql.countInheritedSiblings(1, now - EvalStateRecorder.SIBLING_TTL_MS)).as("TTL içi, kimlikli DOWN").isEqualTo(1);
        assertThat(sql.countInheritedSuppressions(1)).isEqualTo(1);
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

        int[] n = sql.prune(1, now - 7L * 24 * 60 * 60 * 1000, now - 7L * 24 * 60 * 60 * 1000, now - 60L * 60 * 1000);
        assertThat(n).as("kuşak 1 güncel: eski kuşak kuralı devreye girmez").containsExactly(1, 1, 2, 0);
        assertThat(mp.jdbc().queryForList("SELECT domain FROM monitor_eval_state ORDER BY domain", String.class))
                .containsExactly("chain-old", "count-old", "zero-new");
        assertThat(mp.jdbc().queryForList("SELECT confirm_key FROM monitor_confirmations", String.class)).containsExactly("fresh");
        assertThat(mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_eval_type_state", Integer.class)).isEqualTo(1);
    }

    // ── R3-1 FX-STATE ───────────────────────────────────────────────────────────────────────────────────────────────

    private void lease(String owner) {
        mp.jdbc().update("DELETE FROM monitor_check_schedule WHERE monitor_key = ?", EvalStateSql.LEASE_KEY);
        if (owner != null) {
            mp.jdbc().update("INSERT INTO monitor_check_schedule (monitor_key, next_due_at, claimed_by, claimed_at) "
                    + "VALUES (?, ?, ?, ?)", EvalStateSql.LEASE_KEY, Long.MAX_VALUE, owner, 0L);
        }
    }

    @Test
    @DisplayName("FX #1: kiraya bağlı CAS — kira başkasında / yokken kuşak alınamaz, '*' değişmez; kira sahibi alır")
    void leaseBoundCas_onlyTheLeaseHolderTakesAGeneration() {
        assertThat(sql.acquireGeneration("podB", 1_000L, "podB", () -> true)).as("kira yok").isZero();
        lease("podB");
        assertThat(sql.acquireGeneration("podB", 1_000L, "podB", () -> true)).isEqualTo(1);
        assertThat(sql.acquireGeneration("podA", 2_000L, "podA", () -> true)).as("kira B'de — eski lider A").isZero();
        assertThat(sql.generationRow()).isEqualTo(new EvalStateSql.GenerationRow(1, "podB", 1_000L));
        assertThat(sql.heartbeat(1, "podB", 3_000L)).as("gerçek lider çitlenmedi").isTrue();
        AtomicBoolean asked = new AtomicBoolean();
        assertThat(sql.acquireGeneration("podB", 4_000L, "podB", () -> { asked.set(true); return false; }))
                .as("artık istenmiyor: CAS yapılmaz").isZero();
        assertThat(asked).isTrue();
        assertThat(sql.generationRow().gen()).isEqualTo(1);
        lease("podA");
        assertThat(sql.acquireGeneration("podA", 5_000L, "podA", () -> true)).as("kira A'ya geçti").isEqualTo(2);
    }

    @Test
    @DisplayName("FX #5: '*' satırı silinmişse var olan en büyük kuşağın üstünden tohumlanır (eski satırlar ezilebilir kalır)")
    void reseedStartsAboveExistingGenerations() {
        sql.writeRecovery(List.of(rec("a", 2, 1)), "old", 7, 1_000L);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", true, 1L, 1L, 1L, 1)), "old", 9, 1_000L);
        assertThat(sql.generationRow()).isNull();
        long g = sql.acquireGeneration("pod1", 2_000L);
        assertThat(g).as("en büyük kuşak 9 → yeni kuşak 10").isEqualTo(10);
        sql.writeRecovery(List.of(rec("a", 0, 1)), "pod1", g, 3_000L);
        assertThat(countOf("a")).as("yeni kuşak eski satırı ezer").isZero();
    }

    @Test
    @DisplayName("FX #3: devralınan sayılar YALNIZ önceki kuşağın satırları; önceki kuşak = kuşak − 1 (satırı olmasa da)")
    void inheritedCountsOnlyThePreviousGeneration() {
        long now = 100L * 60 * 60 * 1000;
        sql.writeRecovery(List.of(rec("stale1", 2, 1), rec("stale2", 3, 2)), "old", 1, now);
        sql.writeRecovery(List.of(rec("prev", 1, 1)), "prev", 3, now);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", true, now, now, 1L, 1)), "old", 1, now);
        assertThat(sql.previousGeneration(4)).isEqualTo(3L);
        assertThat(sql.previousGeneration(5)).as("CAS'ın yerini aldığı kuşak, satırı olmasa da").isEqualTo(4L);
        assertThat(sql.countInheritedRecovery(3)).as("kuşak 1'in bayat sayaçları sayılmaz").isEqualTo(1);
        assertThat(sql.countInheritedSuppressions(3)).isZero();
    }

    @Test
    @DisplayName("FX #3: eski kuşağın 7 günden eski satırları sayıdan bağımsız budanır (güncel kuşağınki ve '*' kalır)")
    void pruneOldGenerationsRegardlessOfCount() {
        long now = 30L * 24 * 60 * 60 * 1000;
        long old = now - 8L * 24 * 60 * 60 * 1000;
        long cut = now - 7L * 24 * 60 * 60 * 1000;
        sql.acquireGeneration("pod1", old);
        sql.acquireGeneration("pod1", old);   // '*' = kuşak 2
        sql.writeRecovery(List.of(rec("gen1-old", 2, 1)), "pod1", 1, old);
        sql.writeRecovery(List.of(rec("gen1-new", 2, 2)), "pod1", 1, now);
        sql.writeRecovery(List.of(rec("gen2-old", 2, 3)), "pod1", 2, old);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", true, old, old, 1L, 1)), "pod1", 1, old);
        sql.writeTypeState(List.of(new TypeRow("DNS_FAILURE", true, old, old, 1L, 2)), "pod1", 2, old);
        int[] n = sql.prune(2, cut, cut, now - 60L * 60 * 1000);
        assertThat(n[1]).as("kuşak 1'in eski sayacı").isEqualTo(1);
        assertThat(n[3]).as("kuşak 1'in eski tür satırı").isEqualTo(1);
        assertThat(mp.jdbc().queryForList("SELECT domain FROM monitor_eval_state ORDER BY domain", String.class))
                .containsExactly("gen1-new", "gen2-old");
        assertThat(mp.jdbc().queryForList("SELECT alert_type FROM monitor_eval_type_state ORDER BY alert_type", String.class))
                .containsExactly("*", "DNS_FAILURE");
    }

    @Test
    @DisplayName("FX F2: son durum silme koşullu — daha eski kuşak / küçük seq silinmez; daha yeni (gen, seq) siler")
    void lastStatusDeleteIsGuarded() {
        sql.writeLastStatus(List.of(new LastStatusRow("PORT_DOWN", "m:9", "h", null, 9L, null, null, false, true, "NETWORK",
                "S", 1L, 1L, 5)), "pod1", 2, 1L);
        assertThat(sql.deleteLastStatus(List.of(new EvalStateSql.LastStatusDelete("PORT_DOWN", "m:9", 99)), 1))
                .as("eski kuşak").isZero();
        assertThat(sql.deleteLastStatus(List.of(new EvalStateSql.LastStatusDelete("PORT_DOWN", "m:9", 4)), 2))
                .as("küçük seq").isZero();
        assertThat(sql.deleteLastStatus(List.of(new EvalStateSql.LastStatusDelete("PORT_DOWN", "m:9", 6)), 2)).isEqualTo(1);
        assertThat(sql.readLastStatus(2)).isEmpty();
    }

    @Test
    @DisplayName("FX #6: sorgu zaman aşımlı şablon aynı veri kaynağında; sahte (veri kaynaksız) şablon olduğu gibi kalır")
    void withQueryTimeout() {
        JdbcTemplate t = EvalStateSql.withQueryTimeout(mp.jdbc(), 15);
        assertThat(t).isNotSameAs(mp.jdbc());
        assertThat(t.getQueryTimeout()).isEqualTo(15);
        assertThat(t.getDataSource()).isSameAs(mp.jdbc().getDataSource());
        assertThat(mp.jdbc().getQueryTimeout()).as("paylaşılan şablon değişmez").isNotEqualTo(15);
        JdbcTemplate noDs = new JdbcTemplate();
        assertThat(EvalStateSql.withQueryTimeout(noDs, 15)).isSameAs(noDs);
        assertThat(EvalStateSql.withQueryTimeout(mp.jdbc(), 0)).isSameAs(mp.jdbc());
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

    // ── Çok pod Sürüm 3-2: devir işaretleri ve öncül satır ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3-2: acquireGenerationWithPrior KAZANAN CAS'ın okuduğu satırı döner (yerine geçilen kuşak + işaretler); ilk satır tohum")
    void acquireWithPrior_returnsReplacedRow() {
        EvalStateSql.Acquired first = sql.acquireGenerationWithPrior("pod1", 1_000L, null, () -> true);
        assertThat(first.gen()).isEqualTo(1L);
        assertThat(first.prior()).as("tohum satırı (boş veritabanı → kuşak 0, yazan = ben)")
                .isEqualTo(new EvalStateSql.PriorGeneration(0L, "pod1", 1_000L, null, null, null));
        assertThat(sql.heartbeatMarked(1, "pod1", 2_000L, 1_500L)).isTrue();
        EvalStateSql.Acquired second = sql.acquireGenerationWithPrior("pod2", 3_000L, null, () -> true);
        assertThat(second.gen()).isEqualTo(2L);
        assertThat(second.prior()).isEqualTo(new EvalStateSql.PriorGeneration(1L, "pod1", 2_000L, 1L, 1_500L, null));
        // Önceki sahip artık çitli: hem 3-1 hem işaretli nabız 0 satır
        assertThat(sql.heartbeat(1, "pod1", 4_000L)).isFalse();
        assertThat(sql.heartbeatMarked(1, "pod1", 4_000L, 4_000L)).isFalse();
        assertThat(sql.acquireGenerationWithPrior("pod3", 5_000L, null, () -> false)).isEqualTo(EvalStateSql.Acquired.NONE);
    }

    @Test
    @DisplayName("3-2: kiraya bağlı öncül CAS — kira sahibi değilse NONE (değer değişmedi → yeniden denenmez), sahibiyse alır")
    void acquireWithPrior_leaseBound() {
        sql.acquireGeneration("seed", 1L);
        mp.jdbc().update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?,?,?,?)",
                EvalStateSql.LEASE_KEY, 99L, "pod-holder", 1L);
        assertThat(sql.acquireGenerationWithPrior("pod-stale", 2L, "pod-stale", () -> true)).isEqualTo(EvalStateSql.Acquired.NONE);
        EvalStateSql.Acquired a = sql.acquireGenerationWithPrior("pod-holder", 3L, "pod-holder", () -> true);
        assertThat(a.gen()).isEqualTo(2L);
        assertThat(a.prior().writer()).isEqualTo("seed");
    }

    @Test
    @DisplayName("3-2: devir işareti üç zamanı eşitler; sonraki 3-1 nabzı updated_ms'i kaydırır (geçersiz kılar); işaretli nabız handover'ı siler")
    void handoverMarkAndInvalidation() {
        long g = sql.acquireGeneration("pod1", 1_000L);
        assertThat(sql.handoverMark(g, "pod1", 5_000L)).isTrue();
        EvalStateSql.Acquired peek = peekPrior();
        assertThat(peek.prior()).isEqualTo(new EvalStateSql.PriorGeneration(g, "pod1", 5_000L, g, 5_000L, 5_000L));
        assertThat(sql.heartbeat(g, "pod1", 6_000L)).as("3-1 nabzı").isTrue();
        EvalStateSql.PriorGeneration after = peekPrior().prior();
        assertThat(after.handoverMs()).isEqualTo(5_000L);
        assertThat(after.updatedMs()).as("handover_ms ≠ updated_ms → temiz devir değil").isEqualTo(6_000L);
        assertThat(sql.heartbeatMarked(g, "pod1", 7_000L, null)).isTrue();
        EvalStateSql.PriorGeneration marked = peekPrior().prior();
        assertThat(marked.handoverMs()).isNull();
        assertThat(marked.flushedMs()).as("eksik kuşak → null").isNull();
        assertThat(marked.stateGen()).isEqualTo(g);
        assertThat(sql.handoverMark(g, "başkası", 8_000L)).as("başka yazan").isFalse();
        assertThat(sql.handoverMark(g + 1, "pod1", 8_000L)).as("başka kuşak").isFalse();
    }

    /** {@code '*'} satırının işaret sütunlarıyla düz okuması (CAS yok). */
    private EvalStateSql.Acquired peekPrior() {
        Map<String, Object> r = mp.jdbc().queryForMap("SELECT gen, writer, updated_ms, state_gen, flushed_ms, handover_ms "
                + "FROM monitor_eval_type_state WHERE alert_type = '*'");
        EvalStateSql.PriorGeneration p = new EvalStateSql.PriorGeneration(((Number) r.get("gen")).longValue(),
                (String) r.get("writer"), ((Number) r.get("updated_ms")).longValue(), longOrNull(r.get("state_gen")),
                longOrNull(r.get("flushed_ms")), longOrNull(r.get("handover_ms")));
        return new EvalStateSql.Acquired(p.gen(), p);
    }

    private static Long longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    @Test
    @DisplayName("3-2: SQL_ABANDON_OWN yalnız bu yazanın bu kuşaktaki süren zincirlerini ABANDONED yapar")
    void abandonOwnOnlyMine() {
        EvalStateSql.ChainRow c = new EvalStateSql.ChainRow(EvalStateKeys.keyHash("k1"), "k1", 1, "PORT_DOWN", "x", null,
                "port", null, 0, 3, 0L, "2026-10-28T00:00:00", null, null, null, false, "RUNNING", 1L, 1);
        sql.writeChains(List.of(c), "pod1", 3, 1L);
        EvalStateSql.ChainRow other = new EvalStateSql.ChainRow(EvalStateKeys.keyHash("k2"), "k2", 2, "PORT_DOWN", "y", null,
                "port", null, 0, 3, 0L, "2026-10-28T00:00:00", null, null, null, false, "RUNNING", 1L, 1);
        sql.writeChains(List.of(other), "pod2", 2, 1L);
        assertThat(sql.abandonOwn("pod1", 3, 9L)).isEqualTo(1);
        assertThat(sql.abandonOwn("pod1", 3, 9L)).as("idempotent").isZero();
        assertThat(mp.jdbc().queryForObject("SELECT state FROM monitor_confirmations WHERE confirm_key = 'k2'", String.class))
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("3-2: işaret sütunları okunabiliyor; sütun yoksa false (BadSqlGrammar), işaretli ifadeler BadSqlGrammar fırlatır")
    void markerColumnsProbe() {
        assertThat(sql.markerColumnsReadable()).isTrue();
        for (String c : EvalStateSql.MARKER_COLUMNS) {
            mp.jdbc().execute("ALTER TABLE monitor_eval_type_state DROP COLUMN " + c);
        }
        assertThat(sql.markerColumnsReadable()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.acquireGenerationWithPrior("pod1", 1L, null, () -> true))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
        long g = sql.acquireGeneration("pod1", 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.heartbeatMarked(g, "pod1", 2L, 2L))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
        assertThat(sql.heartbeat(g, "pod1", 2L)).as("3-1 nabzı sütunsuz çalışır").isTrue();
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
