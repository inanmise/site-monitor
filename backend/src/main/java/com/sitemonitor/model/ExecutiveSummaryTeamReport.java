package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TAKIM yönetici özeti gönderim kaydı (2026-10-10, kullanıcı isteği: takıma özel özet + alıcılar) — takım × ay başına TEK
 * satır; kurum geneli kaydın ({@link ExecutiveSummaryReport}) takım karşılığı ve aynı "tam bir kez" kapısı:
 * (takım, yıl, ay) {@code UNIQUE} talebi, başarısız ayın koşullu UPDATE ile yeniden talebi
 * ({@code ExecutiveSummaryTeamReportRepository#reclaim}).
 *
 * <p>{@code summaryJson}: gönderilen takım özetinin tam içeriği — ekran geçmiş ayı bu kayıttan çizer (postadaki ile aynı
 * sayılar). Test gönderimleri buraya yazılmaz.
 *
 * <p>Saklama: takım silinince yetim satır temizlenir ({@code RetentionCatalog} "executive-summary-team-reports-orphan");
 * zamanlanmış gönderim her koşuda seçilebilir pencereden (24 ay) eski satırları siler — takım × 25 aydan fazla birikmez.
 */
@Entity
@Table(name = "executive_summary_team_reports",
    uniqueConstraints = @UniqueConstraint(name = "uk_estr_team_year_month",
            columnNames = {"team_id", "report_year", "report_month"}),
    indexes = @Index(name = "idx_estr_year_month", columnList = "report_year, report_month"))
@Data
@NoArgsConstructor
public class ExecutiveSummaryTeamReport implements ExecutiveReportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "report_month", nullable = false)
    private Integer reportMonth;

    @Column(name = "status", length = 24)
    private String status;

    /** SCHEDULED | CATCH_UP | MANUAL */
    @Column(name = "trigger_kind", length = 16)
    private String triggerKind;

    @Column(name = "attempts")
    private Integer attempts;

    @Column(name = "recipient_count")
    private Integer recipientCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "summary_status", length = 16)
    private String summaryStatus;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "claimed_at", length = 40)
    private String claimedAt;

    @Column(name = "sent_at", length = 40)
    private String sentAt;

    @Column(name = "created_at", length = 40)
    private String createdAt;
}
