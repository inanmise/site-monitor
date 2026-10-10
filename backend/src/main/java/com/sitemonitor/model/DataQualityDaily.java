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
 * Veri kalitesi puanının GÜNLÜK görüntüsü (2026-10-10) — 30 günlük eğilim çizgisi için.
 *
 * <p>Gün × kova başına TEK satır ({@code uq_dqd_day_team}): {@code team_key} takım kimliği; {@code 0} = kurum,
 * {@code -1} = Sahipsiz kovası. Yazım {@code INSERT … ON CONFLICT DO NOTHING} (iki pod aynı gün yazsa da tek satır);
 * {@code DataQualitySnapshotJob} her yazımda {@value com.sitemonitor.service.quality.DataQualityService#TREND_KEEP_DAYS}
 * günden eski satırları siler → tablo SINIRLI (RetentionCatalog BOUNDED). Kişisel veri yok (takım kimliği + sayılar).
 *
 * <p>Satırlar JDBC ile yazılır/okunur (repository yok); entity yalnız tabloyu {@code ddl-auto} ile kurar.
 */
@Entity
@Table(name = "data_quality_daily",
       uniqueConstraints = @UniqueConstraint(name = "uq_dqd_day_team", columnNames = {"snap_day", "team_key"}),
       indexes = @Index(name = "idx_dqd_day", columnList = "snap_day"))
@Getter @Setter @NoArgsConstructor
public class DataQualityDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Europe/Istanbul takvim günü ({@code yyyy-MM-dd}). */
    @Column(name = "snap_day", nullable = false, length = 10)
    private String snapDay;

    /** Takım kimliği; 0 = kurum, -1 = Sahipsiz. */
    @Column(name = "team_key", nullable = false)
    private Long teamKey;

    /** 0..100; hiç kural uygulanmıyorsa null. */
    @Column(name = "score")
    private Integer score;

    @Column(name = "findings", nullable = false)
    private Integer findings;

    @Column(name = "items", nullable = false)
    private Integer items;

    /** UTC ISO yazım anı. */
    @Column(name = "created_at", length = 30)
    private String createdAt;
}
