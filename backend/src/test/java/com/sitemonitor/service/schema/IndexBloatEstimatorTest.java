package com.sitemonitor.service.schema;

import com.sitemonitor.service.schema.IndexCatalog.ColumnStat;
import com.sitemonitor.service.schema.IndexCatalog.IndexStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Btree beklenen boyut tahmini ve şişme kararı (2026-11-06). Gerçek ölçü: geçici PostgreSQL 16 kümesinde 300 bin satır
 * (bigint + 19 karakterlik ISO zaman), %95'i silinip {@code VACUUM ANALYZE} ve {@code REINDEX CONCURRENTLY} sonrası
 * {@code (m, c)} indeksi 93 sayfa = 761 856 bayt — tahmin birebir aynı.
 */
class IndexBloatEstimatorTest {

    private static final long MB = 1024L * 1024;

    private static IndexStats stats(double idxTuples, double tblTuples, long bytes, int ff, ColumnStat... cols) {
        return new IndexStats("i", "public.i", "t", idxTuples, tblTuples, bytes, 8192, ff, List.of(cols));
    }

    private static ColumnStat col(String n, Double w, Double nf) { return new ColumnStat(n, w, nf); }

    @Test
    @DisplayName("Ölçülen gerçek: 15 000 satır, (bigint 8, varchar 20) → 93 sayfa (geçici kümede yeniden kurulmuş indeks)")
    void matchesMeasuredRebuiltIndex() {
        long exp = IndexBloatEstimator.expectedBytes(stats(15_000, 15_000, 14 * MB, 90,
                col("m", 8.0, 0.0), col("c", 20.0, 0.0)));
        assertThat(exp).isEqualTo(93L * 8192);
    }

    @Test
    @DisplayName("İstatistik yok / satır sayısı bilinmiyor → -1 (tahmin yok → yeniden kurulmaz)")
    void unknown() {
        assertThat(IndexBloatEstimator.expectedBytes(stats(15_000, 15_000, MB, 90, col("m", 8.0, 0.0), col("c", null, null))))
                .isEqualTo(-1);
        assertThat(IndexBloatEstimator.expectedBytes(stats(-1, -1, MB, 90, col("m", 8.0, 0.0)))).isEqualTo(-1);
        assertThat(IndexBloatEstimator.expectedBytes(stats(15_000, 15_000, MB, 90))).isEqualTo(-1);
    }

    @Test
    @DisplayName("Satır sayısı = indeks ve tablo reltuples'unun BÜYÜĞÜ (biri -1 ise öbürü) — temkinli yön")
    void tuplesTakesTheLarger() {
        long fromIndex = IndexBloatEstimator.expectedBytes(stats(100_000, -1, MB, 90, col("m", 8.0, 0.0)));
        long fromTable = IndexBloatEstimator.expectedBytes(stats(-1, 100_000, MB, 90, col("m", 8.0, 0.0)));
        long larger = IndexBloatEstimator.expectedBytes(stats(10_000, 100_000, MB, 90, col("m", 8.0, 0.0)));
        assertThat(fromIndex).isEqualTo(fromTable).isEqualTo(larger).isGreaterThan(8192L * 100);
    }

    @Test
    @DisplayName("Null'lar: bit eşlemi başlığı + daha dar veri; fillfactor 100 daha az sayfa; boş tablo 2 sayfa (meta + kök)")
    void nullsFillfactorAndEmpty() {
        long noNull = IndexBloatEstimator.expectedBytes(stats(1_000_000, 1_000_000, MB, 90, col("m", 8.0, 0.0)));
        long halfNull = IndexBloatEstimator.expectedBytes(stats(1_000_000, 1_000_000, MB, 90, col("m", 8.0, 0.5)));
        assertThat(halfNull).as("başlık 8 → 16 (hizalı), veri 8 → 4 → 8: satır 16 → 24").isGreaterThan(noNull);
        long ff100 = IndexBloatEstimator.expectedBytes(stats(1_000_000, 1_000_000, MB, 100, col("m", 8.0, 0.0)));
        assertThat(ff100).isLessThan(noNull);
        assertThat(IndexBloatEstimator.expectedBytes(stats(0, 0, MB, 90, col("m", 8.0, 0.0)))).isEqualTo(2L * 8192);
    }

    @Test
    @DisplayName("Büyük indeks: üst düzeyler dahil, yaprak sayısının ~%1'i kadar fazla; döngü sınırlı")
    void largeIndexUpperLevels() {
        long exp = IndexBloatEstimator.expectedBytes(stats(10_000_000, 10_000_000, MB, 90, col("m", 8.0, 0.0), col("c", 20.0, 0.0)));
        long leaf = (long) Math.ceil(10_000_000 / 166.0);
        assertThat(exp / 8192).isGreaterThan(leaf + 1).isLessThan(leaf + leaf / 50 + 10);
        assertThat(IndexBloatEstimator.expectedBytes(stats(1e15, 1e15, MB, 90, col("m", 8.0, 0.0)))).isPositive();
    }

    @Test
    @DisplayName("Eşik: gerçek ≥ max(2 × beklenen, beklenen + en az fazla); küçük dizinlere dokunulmaz; beklenen yoksa hayır")
    void threshold() {
        long exp = 3 * MB;
        assertThat(IndexBloatEstimator.bloated(exp + 64 * MB, exp, 2.0, 64 * MB)).isTrue();
        assertThat(IndexBloatEstimator.bloated(exp + 64 * MB - 1, exp, 2.0, 64 * MB)).isFalse();
        assertThat(IndexBloatEstimator.bloated(32 * MB, MB, 2.0, 64 * MB)).as("32 kat ama 31 MB fazla").isFalse();
        long big = 500 * MB;
        assertThat(IndexBloatEstimator.bloated(2 * big, big, 2.0, 64 * MB)).isTrue();
        assertThat(IndexBloatEstimator.bloated(2 * big - 1, big, 2.0, 64 * MB)).as("oran sınırı").isFalse();
        assertThat(IndexBloatEstimator.bloated(100 * MB, -1, 2.0, 64 * MB)).isFalse();
        assertThat(IndexBloatEstimator.bloated(100 * MB, 0, 2.0, 0)).isFalse();
    }

    @Test
    @DisplayName("Seçim: en büyük fazla önce; adet ve toplam bayt tavanı (tek başına tavanı aşan atlanır, küçüğü alınır); tahminsizler sayılır")
    void selection() {
        IndexStats a = new IndexStats("a", "public.a", "t", 15_000, 15_000, 300 * MB, 8192, 90, List.of(col("m", 8.0, 0.0)));
        IndexStats b = new IndexStats("b", "public.b", "t", 15_000, 15_000, 120 * MB, 8192, 90, List.of(col("m", 8.0, 0.0)));
        IndexStats c = new IndexStats("c", "public.c", "t", 15_000, 15_000, 90 * MB, 8192, 90, List.of(col("m", 8.0, 0.0)));
        IndexStats healthy = new IndexStats("h", "public.h", "t", 15_000, 15_000, 300 * 1024, 8192, 90, List.of(col("m", 8.0, 0.0)));
        IndexStats noStats = new IndexStats("n", "public.n", "t", 15_000, 15_000, 300 * MB, 8192, 90, List.of(col("m", null, null)));
        var sel = IndexBloatEstimator.select(List.of(c, healthy, b, noStats, a), 2.0, 64 * MB, 10, Long.MAX_VALUE);
        assertThat(sel.picked()).extracting(x -> x.stats().name()).containsExactly("a", "b", "c");
        assertThat(sel.unknown()).isEqualTo(1);
        var two = IndexBloatEstimator.select(List.of(a, b, c), 2.0, 64 * MB, 2, Long.MAX_VALUE);
        assertThat(two.picked()).extracting(x -> x.stats().name()).containsExactly("a", "b");
        assertThat(two.deferred()).extracting(x -> x.stats().name()).containsExactly("c");
        var bytes = IndexBloatEstimator.select(List.of(a, b, c), 2.0, 64 * MB, 10, 250 * MB);
        assertThat(bytes.picked()).extracting(x -> x.stats().name()).containsExactly("b", "c");
        assertThat(bytes.deferred()).extracting(x -> x.stats().name()).containsExactly("a");
    }

    @Test
    @DisplayName("Okunur boyut")
    void human() {
        assertThat(IndexBloatEstimator.human(-1)).isEqualTo("?");
        assertThat(IndexBloatEstimator.human(512)).isEqualTo("512 B");
        assertThat(IndexBloatEstimator.human(8192)).isEqualTo("8 kB");
        assertThat(IndexBloatEstimator.human(183 * MB)).isEqualTo("183.0 MB");
        assertThat(IndexBloatEstimator.human(5L * 1024 * MB)).isEqualTo("5.00 GB");
    }
}
