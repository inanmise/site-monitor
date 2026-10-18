package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Teknik hata raporunun grafik yuvasına yüklenen görsel (2026-10-13) — yuva başına EN ÇOK bir satır (aynı yuvaya yeni
 * yükleme eskisinin yerini alır). Yalnız PNG / JPEG; tür, boyut ve piksel ölçüsü yüklemede baytlardan doğrulanır
 * ({@code TechErrorImageValidator}). {@code @Lob} bilinçli olarak yok: PostgreSQL'de BYTEA (haftalık rapor deseni).
 */
@Entity
@Table(name = "tech_error_report_images",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_image_slot", columnNames = {"report_id", "slot_id"}),
        indexes = @Index(name = "idx_teri_report", columnList = "report_id"))
@Data
@NoArgsConstructor
public class TechErrorReportImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** Şablon yuvası ya da (2026-10-18) konu görseli {@code iss:<bölüm>:<konu>:<n>} — 120 (SchedulerService yaması büyütür). */
    @Column(name = "slot_id", nullable = false, length = 120)
    private String slotId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Uzunluk H2 içindir (varsayılan VARBINARY(255)); PostgreSQL'de tür yine BYTEA. Sınır yüklemede 5 MB. */
    @JsonIgnore
    @Column(nullable = false, length = 10 * 1024 * 1024)
    private byte[] data;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @JsonIgnore
    @Column(name = "uploaded_by_id")
    private Long uploadedById;
}
