package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AYLIK YÖNETİCİ ÖZETİ gönderim kaydı (2026-10-10) — ay başına TEK satır, gönderimin "tam bir kez" kapısı.
 *
 * <p>Talep (yıl, ay) {@code UNIQUE} kısıtıyla kilitlenir: iki pod aynı anda talep ederse ikincisinin INSERT'ü kısıtta
 * düşer ve gönderim yapmaz. Başarısız (FAILED) bir ay, deneme sayısı tavanın altındaysa koşullu UPDATE ile YENİDEN
 * talep edilebilir ({@code ExecutiveSummaryReportRepository#reclaim}) — talep satırı aynı anda tek pod tarafından alınır.
 *
 * <p>{@code summaryJson}: gönderilen özetin TAM içeriği (bölümler, sayılar, hükümler). Uygulama içi ekran geçmiş ayı
 * bu "fotoğraftan" gösterir — postadaki ile ekrandaki sayılar aynı kalır (anlık bölümler, ör. yaklaşan bitişler,
 * gönderim anına aittir). Test gönderimleri buraya YAZILMAZ.
 *
 * <p>Saklama: BOUNDED (yılda 12 satır) — {@code RetentionCatalog} "executive-summary-reports"; silinmez.
 */
@Entity
@Table(name = "executive_summary_reports",
    uniqueConstraints = @UniqueConstraint(name = "uk_esr_year_month", columnNames = {"report_year", "report_month"}))
@Data
@NoArgsConstructor
public class ExecutiveSummaryReport implements ExecutiveReportRow {

    /** Gönderim sürüyor (talep alındı). */
    public static final String SENDING = "SENDING";
    /** Tüm dilimler gönderildi (ya da kuyruğa alındı). */
    public static final String SENT = "SENT";
    /** Dilimlerin bir kısmı gitti, bir kısmı başarısız. */
    public static final String PARTIAL = "PARTIAL";
    /** Hiçbir dilim gitmedi — tavan dolana dek saatlik telafi yeniden dener. */
    public static final String FAILED = "FAILED";
    /** Alıcı yok (adres listesi boş, global yönetici adresi yok ya da hepsi pasif). */
    public static final String NO_RECIPIENT = "NO_RECIPIENT";
    /** Zamanlanmış koşu geldi ama özet kapalı (varsayılan) — gönderilmedi, iz bırakıldı. */
    public static final String SKIPPED_DISABLED = "SKIPPED_DISABLED";
    /** E-posta kanalı kapalı (SMTP susturulmuş) — gönderilmedi. */
    public static final String SKIPPED_MAIL_OFF = "SKIPPED_MAIL_OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    /** Dilim sonuçlarının özeti ("SENT ×120, FAILED ×12") ya da atlama nedeni. */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "subject", length = 300)
    private String subject;

    /** Özetin genel durumu (ok | attention | critical | no_data) — geçmiş listesinde rozet. */
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
