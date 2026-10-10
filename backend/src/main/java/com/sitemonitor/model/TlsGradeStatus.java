package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envanter kaydının SON BİLİNEN TLS notu (2026-10-10) — "not düştü mü" sorusunun durumu.
 *
 * <p>Not her liste isteğinde yeniden HESAPLANIR (kural {@code TlsGradeRules}); bu tablo yalnız karşılaştırma için son
 * notu tutar. {@code TlsProfileJobService} turunda güncel not buradakiyle karşılaştırılır; farklıysa
 * {@code tls_grade_changes}'e bir satır yazılır ve düşüşte {@link #droppedFrom} / {@link #droppedAt} damgalanır (kart
 * "not düştü" göstergesi bunu okur). Notlanamayan (kontrol hatası, kayıt yok) satır durumu DEĞİŞTİRMEZ — geçici bir
 * hata düşüş sayılmaz.
 *
 * <p>Anahtar envanter kimliği: silinip yeniden eklenen alan adı eski notla karşılaştırılmaz (yeni kayıt yeni kimlik).
 * Yetim satırları gece temizliği atar ({@code RetentionCatalog} "tls-grade-status-orphan").
 */
@Entity
@Table(name = "tls_grade_status")
@Data
@NoArgsConstructor
public class TlsGradeStatus {

    @Id
    private Long inventoryId;

    @Column(length = 253)
    private String domain;

    /** "A+" … "F". */
    @Column(length = 2)
    private String grade;

    /** Notu sınırlayan neden kodları, virgülle (sıralı) — değişim tespiti ve günlük için. */
    @Column(length = 1000)
    private String reasons;

    /** Son değerlendirme anı (ISO UTC). */
    @Column(length = 19)
    private String evaluatedAt;

    @Column(length = 2)
    private String previousGrade;

    /** Notun son değiştiği an (ISO UTC). */
    @Column(length = 19)
    private String changedAt;

    /** Son DÜŞÜŞÜN önceki notu; not o düzeye geri çıkınca temizlenir. */
    @Column(length = 2)
    private String droppedFrom;

    @Column(length = 19)
    private String droppedAt;
}
