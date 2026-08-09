package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aylık sertifika envanteri raporu idempotency kaydı — aynı (yıl, ay) için iki kez gönderim
 * olmasın (cron çift tetik, pod yeniden başlatma, manuel + zamanlanmış çakışması).
 * Pencere = takvim ayı; {@code WeeklyAvailabilityLog} kalıbının aylık eşi.
 *
 * <p>Test gönderimleri buraya YAZILMAZ — yalnız gerçek aylık koşu penceresini kilitler.
 */
@Entity
@Table(name = "cert_inventory_report_log",
    uniqueConstraints = @UniqueConstraint(name = "uk_cirl_year_month", columnNames = {"report_year", "month_no"}),
    indexes = @Index(name = "idx_cirl_year_month", columnList = "report_year,month_no"))
@Data
@NoArgsConstructor
public class CertInventoryReportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "month_no", nullable = false)
    private Integer monthNo;

    /** SENT | FAILED | NO_RECIPIENT */
    private String status;

    /** Rapordaki kayıt sayısı — geçmişte "o ay kaç sertifika vardı" sorusunu cevaplar. */
    @Column(name = "row_count")
    private Integer rowCount;

    /** Hijyen bulgusu toplamı — trend takibi (ay ay düşüyor mu?). */
    @Column(name = "finding_count")
    private Integer findingCount;

    @Column(name = "recipients")
    private String recipients;

    @Column(name = "sent_at")
    private String sentAt;

    @Column(name = "created_at")
    private String createdAt;
}
