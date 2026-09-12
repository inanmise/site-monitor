package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Zayıf Algoritma Raporu istisnası (2026-09-12): bir alan için "biliniyor, kabul edildi / planlı
 * yenileme" kaydı. Rapor satırı silinmez, ayrı gösterilir; {@code until} tarihi geçince istisna
 * SÜRESİ DOLMUŞ sayılır ve satır yeniden aktif bulguya döner (kalıcı susturma yok).
 *
 * <p>Yeni tablo — dolu tabloya NOT NULL eklenmiyor (ddl-auto sessiz düşme tuzağı yok).
 */
@Entity
@Table(name = "weak_algo_exception",
       uniqueConstraints = @UniqueConstraint(name = "uq_weak_algo_exception_domain", columnNames = "domain"))
@Data
@NoArgsConstructor
public class WeakAlgorithmException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 253)
    private String domain;

    /** Gerekçe — serbest metin (ör. "yenileme 2026-11'de, satıcı SHA-1 dışı vermiyor"). */
    @Column(length = 1000)
    private String reason;

    /** Son geçerlilik günü (ISO yyyy-MM-dd). Boş = süresiz DEĞİL; sunucu zorunlu kılar. */
    @Column(name = "until_date", length = 10)
    private String until;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** ISO-8601 UTC (yyyy-MM-dd'T'HH:mm:ss). */
    @Column(name = "created_at", length = 19)
    private String createdAt;
}
