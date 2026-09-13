package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Haftalık rapor yorum dizisi (2026-09-13, ikinci tur): PO ↔ takım gidiş-gelişi tek yerde.
 * {@code kind}: COMMENT (serbest yorum) · SUBMIT · APPROVE · REJECT (iade notu) · REOPEN — durum
 * geçişleri sistem tarafından yorum olarak düşer, iade notu da burada yaşar (rapordaki alan korunur).
 * Rapor silinince yorumlar da silinir (service.delete + öksüz retention politikası).
 */
@Entity
@Table(name = "weekly_report_comments",
    indexes = { @Index(name = "idx_wrc_report", columnList = "report_id") })
@Data
@NoArgsConstructor
public class WeeklyReportComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(length = 16, nullable = false)
    private String kind = "COMMENT";

    /** Yazar görünen adı (username değil — kart/mailde de bu kullanılır). */
    @Column(length = 120)
    private String author;

    @Column(name = "author_id")
    private Long authorId;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "created_at", length = 30, nullable = false)
    private String createdAt;
}
