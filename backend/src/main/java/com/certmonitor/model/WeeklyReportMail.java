package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Haftalık rapor mail gönderim kaydı — her gönderim DENEMESİ (başarılı,
 * hatalı veya atlanmış) bir satırdır; rapor listesindeki "Geçmiş" modal'ı ve
 * "Gönderim hatası" rozeti buradan beslenir.
 *
 * mailType: SUBMIT_PO (PO onay bildirimi) | APPROVE_MANAGER (müdüre rapor)
 *           | REJECT_TEAM (takıma iade bildirimi)
 * status:   EmailNotificationService.sendHtml sonucu (SENT / QUEUED_RETRY* /
 *           FAILED:* / SKIPPED_DISABLED) veya alan bazlı SKIPPED_NO_CONTACT.
 */
@Entity
@Table(name = "weekly_report_mails",
    indexes = @Index(name = "idx_wrm_report", columnList = "report_id"))
@Data
@NoArgsConstructor
public class WeeklyReportMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "mail_type", nullable = false, length = 32)
    private String mailType;

    @Column(name = "from_address")
    private String fromAddress;

    /** Virgülle ayrılmış alıcılar. */
    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    @Column(columnDefinition = "TEXT")
    private String subject;

    /** Gönderilen HTML — UI'da görüntülenirken cid referansları /api'ye çevrilir. */
    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String status;

    private String createdBy;
    private String createdAt;
}
