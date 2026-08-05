package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir {@link LoginIssueReport} için gönderilen (veya gönderilmeye çalışılan) bildirim mailinin kaydı —
 * admin triyaj ekranındaki "Gönderilen E-postalar" geçmişini besler. Login-issue mailleri
 * {@code notification_logs}'a yazılmaz (o tablo {@code alertEventId NOT NULL} ister); bu ayrı tablo
 * kime/ne zaman/hangi tür mailin gittiğini + teslim durumunu tutar.
 *
 * <p>Düz {@code reportId} FK (proje deseni: @ManyToOne yok). {@code mailType} ve {@code status}
 * String kolonlardır (enum eşlemesi yok): mailType ∈ {REPORT_ADMIN, REPORTER_ACK, RESOLVED};
 * status = {@code EmailNotificationService}'in döndürdüğü ham değer
 * (SENT / FAILED:… / SKIPPED_DISABLED / SKIPPED_NO_RECIPIENT / QUEUED_RETRY…). {@code sentAt} ISO-8601 UTC.
 */
@Entity
@Table(
    name = "login_issue_mail_logs",
    indexes = { @Index(name = "idx_lim_report", columnList = "reportId") }
)
@Data
@NoArgsConstructor
public class LoginIssueMailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column(length = 40)
    private String refCode;

    /** REPORT_ADMIN | REPORTER_ACK | RESOLVED */
    @Column(nullable = false, length = 20)
    private String mailType;

    @Column(length = 255)
    private String recipientTo;

    /** Virgülle ayrılmış CC (yoksa null). */
    @Column(columnDefinition = "TEXT")
    private String cc;

    /** Gönderen (from) adresi — DB SMTP ayarlarından. */
    @Column(length = 255)
    private String emailFrom;

    /** Mail konusu (referans no içerir). */
    @Column(columnDefinition = "TEXT")
    private String subject;

    /** Gönderilen mailin HTML gövdesi (alıcının gördüğüyle aynı; ekran görüntüleri cid ile ayrıdır). */
    @Column(columnDefinition = "TEXT")
    private String bodyHtml;

    /** EmailNotificationService dönüş değeri (SENT / FAILED:… / SKIPPED_*). */
    @Column(length = 100)
    private String status;

    /** status FAILED ise hata metni (aksi halde null). */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Gönderim, mail mute'una (SmtpSettings.enabled=false) rağmen zorlanarak mı yapıldı. */
    @Column(nullable = false)
    private boolean forced;

    /** ISO-8601 UTC. */
    @Column(nullable = false, length = 40)
    private String sentAt;
}
