package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.retention.RetentionPolicy;

import java.util.List;
import java.util.Map;

/**
 * Veritabanı büyümesinin veri kaynağı (2026-11-13) — gerçeği {@link PgDbSizeSource} (yalnız PostgreSQL, her deyim zaman
 * aşımlı); testlerde sahte. H2 / başka veritabanında {@link #supported()} FALSE döner ve hiçbir okuma yapılmaz.
 */
public interface DbSizeSource {

    /** {@code TRUE} PostgreSQL, {@code FALSE} başka veritabanı (ölçülmez), {@code null} şu an okunamadı. */
    Boolean supported();

    /** {@code pg_database_size(current_database())}. */
    long databaseBytes();

    /** Geçerli şemadaki kullanıcı tabloları — boyutlar ve istatistik sayaçları (tek deyim). */
    List<TableSize> tables();

    /**
     * Yaş kuralının zaman kolonunda en eski değer (UTC ISO, 19 karakter); tablo boş → null. Okuma hatası / zaman aşımı
     * İSTİSNA fırlatır (analiz bölümü "kısmi" der; görüntü işi null yazar) — 2026-11-13 inceleme.
     */
    String oldest(RetentionPolicy policy);

    /**
     * Zaman kolonlarının {@code pg_stats.histogram_bounds}'u — tek deyim; anahtar {@code tablo.kolon}. İstatistik yoksa /
     * görülemiyorsa (yönetilen PostgreSQL izni) o anahtar yoktur.
     */
    Map<String, List<String>> histograms(List<String[]> tableColumns);

    /** Kuralın yeni kesimle sileceği satır sayısı (aynı WHERE ile zaman aşımlı COUNT); süre doldu / hata → null. */
    Long countOlder(RetentionPolicy policy, String cutoff);

    /** Tablo başına vacuum / analyze anları ve ölü satır (analiz). */
    List<VacuumStat> vacuumStats();

    /** Hiç taranmamış (idx_scan = 0), en az {@code minBytes} büyüklüğünde, benzersiz / kısıt olmayan dizinler. */
    List<IndexInfo> unusedIndexes(long minBytes);

    /** Sade btree dizinlerinin şişme tahmini (gerçek + beklenen bayt). */
    List<BloatInfo> bloat(List<String> tables);

    /** Son 24 saatte {@code table} (izleme seri tablosu) içinde en çok satır üreten izlemeler (en çok {@code limit}). */
    List<long[]> hotMonitors(String table, String sinceIso, int limit);

    record TableSize(String name, long totalBytes, long tableBytes, long indexBytes, long liveRows, long deadRows,
                     long insTotal, long delTotal) {}

    record VacuumStat(String table, long liveRows, long deadRows, String lastAutovacuum, String lastVacuum,
                      String lastAutoanalyze, long totalBytes) {}

    record IndexInfo(String table, String index, long bytes) {}

    record BloatInfo(String table, String index, long bytes, long expectedBytes, boolean bloated) {}
}
