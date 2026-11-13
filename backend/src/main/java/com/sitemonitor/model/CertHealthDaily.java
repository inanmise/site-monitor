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
 * Sertifika sağlığının GÜNLÜK görüntüsü (2026-11-13, İstatistikler "90 günlük sağlık serisi").
 *
 * <p>Gün × kova başına TEK satır ({@code uq_chd_day_team}): {@code team_key} takım kimliği, {@code 0} = kurum. Bir sertifika
 * hem SY hem UG takımının kovasında sayılır (takımlar TOPLANMAZ — kurum kovası her sertifikayı bir kez sayar). Sayımlar
 * {@code CertHealthCounts} kuralıyla (yönetici özetinin "filo sağlığı" ile aynı). Yazım {@code INSERT … ON CONFLICT DO NOTHING};
 * {@code CertHealthSnapshotJob} her yazımda {@value com.sitemonitor.service.CertHealthDailyService#KEEP_DAYS} günden eski
 * satırları siler → tablo SINIRLI (RetentionCatalog BOUNDED). Kişisel veri yok (takım kimliği + sayılar).
 *
 * <p>Satırlar JDBC ile yazılır / okunur (repository yok); entity yalnız tabloyu {@code ddl-auto} ile kurar.
 */
@Entity
@Table(name = "cert_health_daily",
       uniqueConstraints = @UniqueConstraint(name = "uq_chd_day_team", columnNames = {"snap_day", "team_key"}),
       indexes = @Index(name = "idx_chd_team_day", columnList = "team_key, snap_day"))
@Getter @Setter @NoArgsConstructor
public class CertHealthDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Europe/Istanbul takvim günü ({@code yyyy-MM-dd}). */
    @Column(name = "snap_day", nullable = false, length = 10)
    private String snapDay;

    /** Takım kimliği; 0 = kurum. */
    @Column(name = "team_key", nullable = false)
    private Long teamKey;

    @Column(name = "total", nullable = false)
    private Integer total;

    @Column(name = "evaluated", nullable = false)
    private Integer evaluated;

    @Column(name = "ok", nullable = false)
    private Integer ok;

    @Column(name = "under30", nullable = false)
    private Integer under30;

    @Column(name = "under7", nullable = false)
    private Integer under7;

    @Column(name = "expired", nullable = false)
    private Integer expired;

    @Column(name = "error_count", nullable = false)
    private Integer errorCount;

    /** UTC ISO yazım anı. */
    @Column(name = "created_at", length = 30)
    private String createdAt;
}
