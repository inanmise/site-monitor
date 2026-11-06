package com.sitemonitor.service.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Btree indeksinin BEKLENEN boyutu ve şişme kararı — saf hesap (2026-11-06, indeks bakımı). {@code pgstattuple} uzantısı
 * yok; yöntem bilinen katalog tahmini (ioguix "btree bloat estimation"): satır sayısı × hizalı indeks satırı genişliği
 * ({@code pg_stats.avg_width} × (1 − null_frac) toplamı + 8 bayt başlık [+ 4 bayt null bit eşlemi] + 4 bayt satır işaretçisi)
 * / (blok − sayfa başlığı 24 − btree özel alanı 16) × fillfactor; üst düzey sayfalar iç sayfa doluluğu %70 ile, + 1 meta
 * sayfa. Yerel geçici kümede yeniden kurulmuş bir indeksin gerçek sayfa sayısını birebir verdi (93 / 93). Btree
 * tekilleştirmesi (PG 13+) gerçeği tahminden KÜÇÜK yapabilir — karar yalnız "çok büyük"e bakar, bu yön güvenli.
 */
final class IndexBloatEstimator {

    static final int MAXALIGN = 8;
    static final int PAGE_HEADER = 24;
    static final int BTREE_SPECIAL = 16;
    static final int ITEM_ID = 4;
    static final int INDEX_TUPLE_HEADER = 8;
    /** Null içeren satırda {@code IndexAttributeBitMapData} ((32 + 7) / 8). */
    static final int NULL_BITMAP = 4;
    /** İç (yaprak olmayan) sayfa doluluğu — {@code BTREE_NONLEAF_FILLFACTOR}. */
    static final int NONLEAF_FILLFACTOR = 70;
    /** Üst düzey hesabında döngü tavanı (btree derinliği pratikte ≤ 5). */
    static final int MAX_LEVELS = 16;

    private IndexBloatEstimator() { }

    /**
     * Beklenen bayt; tahmin edilemiyorsa (satır sayısı bilinmiyor, bir sütunun istatistiği yok) -1. Satır sayısı = indeks ve
     * tablo {@code reltuples}'unun BÜYÜĞÜ (biri -1 ise öbürü): güncellenmemiş bir sayı beklentiyi büyütür → yeniden kurma
     * kararı zorlaşır (temkinli yön).
     */
    static long expectedBytes(IndexCatalog.IndexStats s) {
        double tuples = Math.max(s.indexTuples(), s.tableTuples());
        if (tuples < 0 || s.columns().isEmpty()) return -1;
        int bs = s.blockSize() > 0 ? s.blockSize() : 8192;
        int ff = s.fillfactor() >= 10 && s.fillfactor() <= 100 ? s.fillfactor() : 90;
        boolean anyNull = false;
        double width = 0;
        for (IndexCatalog.ColumnStat c : s.columns()) {
            if (c.avgWidth() == null) return -1;
            double nf = c.nullFrac() == null ? 0 : Math.min(1, Math.max(0, c.nullFrac()));
            if (nf > 0) anyNull = true;
            width += (1 - nf) * Math.max(0, c.avgWidth());
        }
        int header = align(INDEX_TUPLE_HEADER + (anyNull ? NULL_BITMAP : 0));
        double tuple = header + (width <= 0 ? 0 : Math.ceil(width / MAXALIGN) * MAXALIGN) + ITEM_ID;
        double usable = bs - PAGE_HEADER - BTREE_SPECIAL;
        long perLeaf = Math.max(1, (long) Math.floor(usable * ff / 100.0 / tuple));
        long leaf = Math.max(1, (long) Math.ceil(tuples / perLeaf));
        long fanout = Math.max(2, (long) Math.floor(usable * NONLEAF_FILLFACTOR / 100.0 / tuple));
        long upper = 0;
        long n = leaf;
        for (int i = 0; n > 1 && i < MAX_LEVELS; i++) {
            n = (n + fanout - 1) / fanout;
            upper += n;
        }
        return (1 + leaf + upper) * (long) bs;
    }

    /** Şişmiş mi: gerçek ≥ max(oran × beklenen, beklenen + en az fazla). Beklenen bilinmiyorsa hayır. */
    static boolean bloated(long actual, long expected, double factor, long minExcessBytes) {
        if (expected <= 0 || actual <= 0) return false;
        double threshold = Math.max(factor * expected, (double) expected + Math.max(0, minExcessBytes));
        return actual >= threshold;
    }

    /** Yeniden kurma adayı: istatistik + beklenen boyut. */
    record Candidate(IndexCatalog.IndexStats stats, long expectedBytes) {
        long excess() { return stats.bytes() - expectedBytes; }
    }

    /** Seçim sonucu: seçilenler ve bütçe yüzünden bu koşuda bırakılanlar. */
    record Selection(List<Candidate> picked, List<Candidate> deferred, int unknown) { }

    /**
     * Şişmiş indeksleri en büyük fazladan küçüğe sıralar ve koşu başına tavanlarla seçer: en çok {@code maxIndexes} indeks,
     * gerçek boyutları toplamı en çok {@code maxBytes} (tek başına tavanı aşan atlanır, sıradaki daha küçük denenir).
     */
    static Selection select(List<IndexCatalog.IndexStats> stats, double factor, long minExcessBytes, int maxIndexes, long maxBytes) {
        List<Candidate> bloated = new ArrayList<>();
        int unknown = 0;
        for (IndexCatalog.IndexStats s : stats) {
            long exp = expectedBytes(s);
            if (exp < 0) { unknown++; continue; }
            if (bloated(s.bytes(), exp, factor, minExcessBytes)) bloated.add(new Candidate(s, exp));
        }
        bloated.sort(Comparator.comparingLong(Candidate::excess).reversed().thenComparing(c -> c.stats().name()));
        List<Candidate> picked = new ArrayList<>();
        List<Candidate> deferred = new ArrayList<>();
        long used = 0;
        for (Candidate c : bloated) {
            if (picked.size() < Math.max(0, maxIndexes) && used + c.stats().bytes() <= maxBytes) {
                picked.add(c);
                used += c.stats().bytes();
            } else {
                deferred.add(c);
            }
        }
        return new Selection(List.copyOf(picked), List.copyOf(deferred), unknown);
    }

    static int align(int x) {
        return (x + MAXALIGN - 1) / MAXALIGN * MAXALIGN;
    }

    /** Okunur boyut (günlük satırları): "12,3 MB" değil "12.3 MB" — günlükte ayrıştırılabilir. */
    static String human(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f kB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
