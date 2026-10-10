package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TLS notu DEĞİŞİM günlüğü (2026-10-10): her not değişimi bir satır — "son düşüşler" listesi ve sertifika penceresinin
 * not geçmişi buradan okur. İlk değerlendirme satır üretmez (karşılaştırılacak not yok). Saklama: sertifika ham serisiyle
 * aynı pencere ({@code RetentionCatalog} "tls-grade-changes").
 */
@Entity
@Table(name = "tls_grade_changes", indexes = {
        @Index(name = "idx_tgc_changed_at", columnList = "changedAt"),
        @Index(name = "idx_tgc_inventory", columnList = "inventoryId")
})
@Data
@NoArgsConstructor
public class TlsGradeChange {

    public static final String DROP = "DROP";
    public static final String RISE = "RISE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long inventoryId;

    @Column(length = 253)
    private String domain;

    /** Değişim anındaki sorumlu (SY) takım — kapsam süzgeci. */
    private Long teamId;

    /** Değişim anındaki UG takımı — kapsam süzgeci. */
    private Long ugTeamId;

    @Column(length = 2)
    private String fromGrade;

    @Column(length = 2)
    private String toGrade;

    /** {@link #DROP} | {@link #RISE}. */
    @Column(length = 10)
    private String direction;

    /** Yeni notu sınırlayan neden kodları, virgülle. */
    @Column(length = 1000)
    private String reasons;

    /** ISO-8601 UTC. */
    @Column(length = 19)
    private String changedAt;
}
