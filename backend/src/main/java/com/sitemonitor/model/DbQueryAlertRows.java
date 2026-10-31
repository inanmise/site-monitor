package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir veritabanı sorgu alarmının ALARM ANINDAKİ sonuç satırları (2026-10-31, kullanıcı kararı: "mailde + alarm ayrıntısında").
 * Alarm açılışında ve her yeniden bildirimde güncel satırlarla yazılır (alarm başına tek satır, son yazılan kalır).
 * Sınırlar: ≤ 50 satır × 20 kolon, hücre ≤ 200 karakter; maskelenen kolonların değeri "••••" yazılır (asıl değer saklanmaz).
 * Erişim paylaşımıyla (grant) okuyana gösterilmez (yalnız satır sayısı). Saklama: {@code dbquery-alert-rows}.
 */
@Entity
@Table(name = "dbquery_alert_rows",
       uniqueConstraints = @UniqueConstraint(name = "uk_dbqar_alert", columnNames = "alert_event_id"),
       indexes = @Index(name = "idx_dbqar_monitor", columnList = "monitor_id"))
@Data
@NoArgsConstructor
public class DbQueryAlertRows {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Satırların okunduğu çalıştırmanın zamanı (UTC ISO). */
    @Column(name = "captured_at", nullable = false, length = 30)
    private String capturedAt;

    /** Kolon adları JSON {@code ["col", …]}. */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    /** Satırlar JSON {@code [["v", null, …], …]} — her hücre metin ya da null. */
    @Column(name = "rows_json", columnDefinition = "TEXT")
    private String rowsJson;

    /** Maskelenen kolon adları JSON. */
    @Column(name = "masked_json", columnDefinition = "TEXT")
    private String maskedJson;

    /** Sorgunun döndürdüğü toplam satır (saklanandan fazla olabilir). */
    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "row_count_capped")
    private Boolean rowCountCapped;

    /** Saklanan satır / kolon sınırında kesildi mi. */
    @Column
    private Boolean truncated;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
