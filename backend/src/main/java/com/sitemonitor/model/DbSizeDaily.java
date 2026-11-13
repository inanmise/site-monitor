package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Veritabanı büyümesinin GÜNLÜK görüntüsü (2026-11-13, Sistem Sağlığı → Veritabanı büyümesi).
 *
 * <p>Gün × tablo başına TEK satır ({@code uq_dsd_day_table}); {@code table_name = '*'} veritabanının toplamıdır
 * ({@code pg_database_size}). Tablo satırları {@code pg_stat_user_tables} + boyut fonksiyonlarından: toplam (tablo + dizin +
 * toast), ana çatal, dizinler, canlı / ölü satır tahmini ve birikimli ekleme / silme sayaçları ({@code n_tup_ins} /
 * {@code n_tup_del} — istatistik sıfırlanınca geri gider; fark negatifse o gün hız bilinmez). {@code oldest_at} = yaş kuralı
 * olan tablonun en eski kaydı (zaman aşımlı MIN; okunamadıysa null). Yazım {@code DbSizeSnapshotJob}: {@code INSERT … ON
 * CONFLICT DO NOTHING}; budama gece temizliği (RetentionCatalog {@code db-size-daily}, 730 gün). Kişisel veri yok.
 *
 * <p>Satırlar JDBC ile yazılır / okunur (repository yok); entity yalnız tabloyu {@code ddl-auto} ile kurar.
 */
@Entity
@Table(name = "db_size_daily",
       uniqueConstraints = @UniqueConstraint(name = "uq_dsd_day_table", columnNames = {"snap_day", "table_name"}),
       indexes = @Index(name = "idx_dsd_table_day", columnList = "table_name,snap_day"))
@Getter @Setter @NoArgsConstructor
public class DbSizeDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Europe/Istanbul takvim günü ({@code yyyy-MM-dd}). */
    @Column(name = "snap_day", nullable = false, length = 10)
    private String snapDay;

    /** Tablo adı; {@code '*'} = veritabanı toplamı. */
    @Column(name = "table_name", nullable = false, length = 128)
    private String tableName;

    /** Toplam bayt (tablo + dizin + toast); '*' satırında {@code pg_database_size}. */
    @Column(name = "total_bytes")
    private Long totalBytes;

    /** Ana çatal ({@code pg_relation_size}). */
    @Column(name = "table_bytes")
    private Long tableBytes;

    /** Dizinler ({@code pg_indexes_size}). */
    @Column(name = "index_bytes")
    private Long indexBytes;

    @Column(name = "live_rows")
    private Long liveRows;

    @Column(name = "dead_rows")
    private Long deadRows;

    /** Birikimli {@code n_tup_ins} (istatistik sıfırlanınca küçülür). */
    @Column(name = "ins_total")
    private Long insTotal;

    /** Birikimli {@code n_tup_del}. */
    @Column(name = "del_total")
    private Long delTotal;

    /** Yaş kuralı olan tablonun en eski kaydı (UTC ISO, 19 karakter); bilinmiyorsa null. */
    @Column(name = "oldest_at", length = 30)
    private String oldestAt;

    /** Yazan pod (örnek kimliği). */
    @Column(name = "writer", length = 80)
    private String writer;

    /** UTC ISO yazım anı. */
    @Column(name = "created_at", length = 30)
    private String createdAt;
}
