package com.sitemonitor.service.schema;

import java.sql.SQLException;
import java.util.List;

/**
 * İndeks bakım işlerinin ({@link RedundantIndexCleanup}, {@link IndexBloatMaintenance}) veritabanı yüzü — gerçeği
 * {@link JdbcIndexCatalog} (PostgreSQL, havuz DIŞI oturum, otomatik commit); birim testlerinde sahte. Yalnız geçerli şema
 * ({@code current_schema()}) okunur ve yazılır.
 */
interface IndexCatalog extends AutoCloseable {

    /** Oturum düzeyi advisory lock dener (beklemez). */
    boolean tryLock(long key) throws SQLException;

    /** Kilidi bırakır; hata yutulur (oturum kapanınca zaten bırakılır). */
    void unlock(long key);

    /** {@code schema_patch_markers}'ta nişan var mı (tablo gerekirse kurulur). */
    boolean markerExists(String key) throws SQLException;

    /** Nişanı yazar (zaten varsa dokunmaz). */
    void writeMarker(String key, int rowsAffected) throws SQLException;

    /** Tablonun bütün indeksleri (geçerli şema); tablo yoksa boş liste. */
    List<IndexFacts> indexes(String table) throws SQLException;

    /** Tabloda şu an bir indeks kuruluyor / yeniden kuruluyor mu ({@code pg_stat_progress_create_index}); okunamazsa false. */
    boolean building(String table);

    /** Verilen tabloların sade btree indekslerinin şişme tahmini girdileri (yalnız geçerli, benzersiz olmayan, kısmi / ifade olmayan). */
    List<IndexStats> bloatInputs(List<String> tables) throws SQLException;

    /** Verilen tablolardaki yarıda kalmış {@code REINDEX … CONCURRENTLY} kalıntıları (GEÇERSİZ {@code *_ccnew} / {@code *_ccold}). */
    List<Leftover> leftovers(List<String> tables) throws SQLException;

    /** İndeksin bayt boyutu; yoksa -1. */
    long indexBytes(String qualifiedName) throws SQLException;

    /**
     * DDL'i oturumda {@code lock_timeout} ve {@code statement_timeout} ile koşar, sonra ikisini sıfırlar. Her bekleme (kilit,
     * eski anlık görüntü / işlem — {@code CONCURRENTLY} aşamaları {@code VirtualXactLock} ile bekler) {@code lock_timeout}
     * ile, toplam süre {@code statement_timeout} ile sınırlı (2026-11-06'da geçici kümede doğrulandı).
     */
    void executeBounded(String ddl, long lockTimeoutMs, long statementTimeoutMs) throws SQLException;

    /** Süren deyimi iptal eder (kapanışta, BAŞKA bir iş parçacığından); hata yutulur. */
    void cancel();

    @Override
    void close();

    /** Oturum açıcı. */
    @FunctionalInterface
    interface Factory {
        IndexCatalog open() throws SQLException;
    }

    /**
     * Bir indeksin katalog bilgisi. {@code columns} = {@code indkey} sırasıyla bütün sütunlar (anahtar + INCLUDE; ifade
     * konumu boş dize); {@code opclasses} / {@code collations} / {@code options} yalnız anahtar sütunlar için (oid / seçenek
     * bit'i metin olarak).
     */
    record IndexFacts(String name, String qualifiedName, String table, String accessMethod,
                      boolean unique, boolean primary, boolean exclusion, boolean constraintBacked,
                      boolean valid, boolean ready, boolean live, boolean partial, boolean expressions,
                      int keyCount, List<String> columns, List<String> opclasses, List<String> collations,
                      List<String> options, long bytes) {

        public IndexFacts {
            columns = List.copyOf(columns);
            opclasses = List.copyOf(opclasses);
            collations = List.copyOf(collations);
            options = List.copyOf(options);
        }

        /** Anahtar sütunlar (INCLUDE hariç). */
        List<String> keyColumns() {
            return columns.subList(0, Math.min(Math.max(0, keyCount), columns.size()));
        }
    }

    /** Şişme tahmini için bir sütunun istatistiği ({@code pg_stats}); null = istatistik yok. */
    record ColumnStat(String name, Double avgWidth, Double nullFrac) { }

    /**
     * Bir indeksin şişme tahmini girdileri: indeks ve tablo satır sayısı tahmini ({@code reltuples}; -1 = hiç
     * analiz / vacuum edilmemiş), gerçek boyut, blok boyutu, fillfactor (yaprak), sütun istatistikleri (indkey sırasıyla).
     */
    record IndexStats(String name, String qualifiedName, String table, double indexTuples, double tableTuples,
                      long bytes, int blockSize, int fillfactor, List<ColumnStat> columns) {

        public IndexStats {
            columns = List.copyOf(columns);
        }
    }

    /** Yarıda kalmış yeniden kurma kalıntısı; {@code tableBuilding} = tabloda şu an bir kurulum sürüyor (dokunulmaz). */
    record Leftover(String name, String qualifiedName, String table, long bytes, boolean tableBuilding) { }
}
