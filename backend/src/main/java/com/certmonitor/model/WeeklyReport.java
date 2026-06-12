package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takım bazlı haftalık durum raporu. Takım + yıl + ISO hafta no başına TEK
 * kayıt. İçerik content_json'da yapılandırılmış JSON olarak tutulur
 * (4 sabit madde — bkz. WeeklyReportService.DEFAULT_TEMPLATE_JSON).
 *
 * Durum makinesi: DRAFT → PENDING_APPROVAL → APPROVED (müdüre otomatik mail)
 *                                          → REJECTED (düzeltme notuyla; kaydetme DRAFT'a döndürür)
 */
@Entity
@Table(name = "weekly_reports",
    uniqueConstraints = @UniqueConstraint(name = "ux_weekly_report_team_week",
        columnNames = {"team_id", "report_year", "week_no"}),
    indexes = {
        @Index(name = "idx_wr_team",   columnList = "team_id"),
        @Index(name = "idx_wr_status", columnList = "status")
    })
@Data
@NoArgsConstructor
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** "year" H2'de anahtar kelime — kolon adı bilinçli olarak report_year. */
    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "week_no", nullable = false)
    private Integer weekNo;

    /** Örn. "2026-W24 (8–12 Haziran 2026)" — mail konusunda ve UI'da kullanılır. */
    @Column(name = "week_label", length = 120)
    private String weekLabel;

    /** DRAFT | PENDING_APPROVAL | APPROVED | REJECTED */
    @Column(nullable = false, length = 24)
    private String status = "DRAFT";

    @Column(name = "content_json", columnDefinition = "TEXT", nullable = false)
    private String contentJson;

    /** PO iade notu — APPROVED olana kadar görünür kalır. */
    @Column(name = "reject_note", columnDefinition = "TEXT")
    private String rejectNote;

    private String submittedBy;
    private String submittedAt;
    private String approvedBy;
    private String approvedAt;
    private String rejectedBy;
    private String rejectedAt;

    /** Müdür mailinin gönderildiği an (SENT/QUEUED_RETRY). */
    private String sentAt;

    private String createdBy;
    private String createdAt;
    private String updatedBy;
    private String updatedAt;

    /** İyimser kilitleme sayacı — her içerik/durum değişiminde artar; istemci
     *  yüklediği sürümü kayıtta geri gönderir, uyuşmazlık 409 VERSION_CONFLICT. */
    @Column(nullable = false)
    private int version = 0;

    /** Yumuşak düzenleme kilidi — editör açıkken 45 sn'de bir heartbeat
     *  tazelenir; 180 sn aktivite yoksa kilit bayat sayılır. */
    @Column(name = "editing_by")
    private String editingBy;

    @Column(name = "editing_user_id")
    private Long editingUserId;

    @Column(name = "editing_heartbeat")
    private String editingHeartbeat;
}
