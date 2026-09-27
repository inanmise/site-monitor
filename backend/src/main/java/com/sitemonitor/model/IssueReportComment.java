package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sorun bildirimi konuşma dizisi (2026-09-26): bildiren ↔ yönetici gidiş-gelişi + durum geçişleri tek yerde.
 * {@link WeeklyReportComment} deseni; düz {@code reportId} FK (proje deseni: @ManyToOne yok).
 *
 * <p>{@code kind}: COMMENT (serbest metin) · STATUS (durum geçişi — {@code body} yeni durum kodunu taşır;
 * zaman çizelgesi bu satırlardan türer). Yeniden açma da STATUS satırıdır ({@code byReporter=true}).
 * <p>{@code internal}: yönetici İÇ notu — bildiren HİÇBİR uçta görmez, bildirim üretmez.
 * <p>{@code byReporter}: satırı raporu bildiren kişi yazdı (kendi yorumu bildirim üretmez; yöneticilere
 * yeniden-açma haberi bu bayrakla türer). Rapor silinince yorumlar da silinir (LoginIssueService.purge + öksüz saklama).
 * Zaman damgaları ISO-8601 UTC String (proje konvansiyonu).
 */
@Entity
@Table(name = "issue_report_comments",
    indexes = {
        @Index(name = "idx_irc_report", columnList = "report_id"),
        @Index(name = "idx_irc_created", columnList = "created_at")
    })
@Data
@NoArgsConstructor
public class IssueReportComment {

    public static final String KIND_COMMENT = "COMMENT";
    public static final String KIND_STATUS = "STATUS";
    /** Yorum gövdesi üst sınırı (istemci sayaç + sunucu 400) — MyIssueReports/LoginIssueReports ile aynı. */
    public static final int MAX_BODY = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(length = 16, nullable = false)
    private String kind = KIND_COMMENT;

    /** Yazan kullanıcı adı (oturumdan; beyan değil). */
    @Column(name = "author_username", length = 100, nullable = false)
    private String authorUsername;

    /** Yazım anındaki sistem rolü (ADMIN / TEAM_ADMIN / USER / AUDIT) — bilgi amaçlı. */
    @Column(name = "author_role", length = 20)
    private String authorRole;

    /** Yönetici iç notu — bildirene asla dönmez. */
    @Column(nullable = false)
    private boolean internal = false;

    /** Satırı bildiren kişi yazdı. */
    @Column(name = "by_reporter", nullable = false)
    private boolean byReporter = false;

    /** COMMENT: metin · STATUS: yeni durum kodu (OPEN | IN_PROGRESS | RESOLVED). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", length = 30, nullable = false)
    private String createdAt;
}
