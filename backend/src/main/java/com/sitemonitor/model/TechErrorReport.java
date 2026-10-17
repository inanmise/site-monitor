package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal × yıl × ay teknik hata raporu (2026-10-13). Açılırken kanalın o anki şablonu {@code template_json}'a KOPYALANIR:
 * şablon sonradan değişse de gönderilmiş rapor değişmez; taslak "şablonun son hâlini uygula" ile isteyerek alır.
 *
 * <p>Değerler {@code values_json}'da (alan kimliği → değer). Eşzamanlı düzenleme {@link Version} ile korunur. Görsel
 * yükleme / gönderim talebi / gönderim sonucu bu sürümü ARTIRMAZ (toplu JPQL güncellemesi): açık düzenleyici bir görsel
 * yüklendi diye çakışmaya düşmez. "Gönderimden sonra değişti" {@code content_changed_at > last_sent_at} ile türetilir.
 *
 * <p>{@code @DynamicUpdate} + gönderim sütunlarında {@code updatable=false} (inceleme 2026-10-13): değer kaydı tüm
 * sütunları okuduğu hâliyle yazsaydı, arada biten bir gönderim talebinin / sonucunun yazdığı durumu geri alırdı.
 */
@Entity
@Table(name = "tech_error_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_report_period",
                columnNames = {"channel_id", "report_year", "report_month"}),
        indexes = {
                @Index(name = "idx_ter_team", columnList = "team_id"),
                @Index(name = "idx_ter_period", columnList = "report_year, report_month")
        })
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class TechErrorReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    /** Kanalın takımı (kapsam sorguları için kopya — kanal takım değiştirmez). */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** "year" H2'de ayrılmış sözcük — kolon adları haftalık raporla aynı desende. */
    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "report_month", nullable = false)
    private Integer reportMonth;

    @JsonIgnore
    @Column(name = "template_json", columnDefinition = "TEXT", nullable = false)
    private String templateJson;

    @JsonIgnore
    @Column(name = "values_json", columnDefinition = "TEXT", nullable = false)
    private String valuesJson;

    /**
     * Raporun KENDİ şablonunun başlık kalıbı ve dili (kopyanın özeti — inceleme 2026-10-13): liste, başlığı kanalın
     * GÜNCEL şablonundan çözüyordu; şablon başlığı değişince gönderilmiş raporlar PDF'tekinden farklı adla görünüyordu.
     * Açılışta ve "şablonun son hâlini uygula"da yazılır; null (bu sütundan önceki satır) → kanal şablonu.
     */
    @Column(name = "title_pattern", length = 250)
    private String titlePattern;

    @Column(name = "template_lang", length = 4)
    private String templateLang;

    @Column(name = "created_at")
    private Instant createdAt;

    @JsonIgnore
    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonIgnore
    @Column(name = "updated_by_id")
    private Long updatedById;

    /** Değer kaydı, görsel yükleme/silme ya da şablon uygulama — her içerik değişikliği (toplu JPQL ile de yazılır). */
    @Column(name = "content_changed_at")
    private Instant contentChangedAt;

    /**
     * İçerik SAYACI (inceleme 2026-10-13, 2. tur): her içerik değişikliği yalnız toplu JPQL ile +1 yazar
     * ({@code TechErrorReportRepository#touchContent}); gönderim, PDF'in okunduğu andaki değeri {@code sent_rev}'e
     * yazar. "Gönderimden sonra değişti" = {@code content_rev > sent_rev}. Zaman damgası karşılaştırması, işlem
     * tamamlanmadan alınan "şimdi" yüzünden PDF'e girmeyen bir kaydı "gönderildi" gösterebiliyordu. Null = 0
     * (bu sütundan önceki satır; o zaman zaman damgasına düşülür).
     */
    @Column(name = "content_rev", updatable = false)
    private Long contentRev = 0L;

    @Column(name = "sent_rev", updatable = false)
    private Long sentRev;

    /**
     * Posta servisinin kuyruğa aldığı (421) gönderim (3. inceleme turu): teslim o an BİLİNMİYOR. Gönderimin
     * {@code notification_logs} satırı ({@code send_log_id}) servis yeniden denemeyi bitirince SENT / FAILED olarak
     * güncellenir; okuma uçları bu satırı görüp raporu sonlandırır ({@code TechErrorReportService#reconcileQueued}).
     * Teslim edilirse "son gönderim" bu bekleyen alanlardan yazılır.
     */
    @JsonIgnore
    @Column(name = "send_log_id", updatable = false)
    private Long sendLogId;

    @JsonIgnore
    @Column(name = "queued_to", length = 500, updatable = false)
    private String queuedTo;

    @JsonIgnore
    @Column(name = "queued_by_id", updatable = false)
    private Long queuedById;

    @JsonIgnore
    @Column(name = "queued_rev", updatable = false)
    private Long queuedRev;

    /** NULL (hiç denenmedi) | SENDING | SENT | QUEUED | FAILED | SKIPPED — yalnız toplu JPQL ile yazılır. */
    @Column(name = "send_state", length = 16, updatable = false)
    private String sendState;

    @Column(name = "send_claimed_at", updatable = false)
    private Instant sendClaimedAt;

    @Column(name = "last_sent_at", updatable = false)
    private Instant lastSentAt;

    @JsonIgnore
    @Column(name = "last_sent_by_id", updatable = false)
    private Long lastSentById;

    @Column(name = "last_sent_to", length = 500, updatable = false)
    private String lastSentTo;

    @Column(name = "last_attempt_at", updatable = false)
    private Instant lastAttemptAt;

    @Column(name = "last_send_status", length = 255, updatable = false)
    private String lastSendStatus;

    @Column(name = "send_count", nullable = false, updatable = false)
    private int sendCount = 0;

    // ── PO onayı (2026-10-17, S6) — yalnız koşullu JPQL yazar; bir varlık kaydı arada verilen kararı ezmez ──

    /** null | PENDING | APPROVED | REJECTED. */
    @Column(name = "approval_state", length = 16, updatable = false)
    private String approvalState;

    /** İsteğin / kararın bağlı olduğu içerik sayacı: {@code content_rev} bunu geçerse onay eskimiştir (yeniden onay). */
    @Column(name = "approval_rev", updatable = false)
    private Long approvalRev;

    @Column(name = "approval_requested_at", updatable = false)
    private Instant approvalRequestedAt;

    @JsonIgnore
    @Column(name = "approval_requested_by_id", updatable = false)
    private Long approvalRequestedById;

    @Column(name = "approval_decided_at", updatable = false)
    private Instant approvalDecidedAt;

    @JsonIgnore
    @Column(name = "approval_decided_by_id", updatable = false)
    private Long approvalDecidedById;

    /** Karar notu (red gerekçesi ya da onay notu), ≤ 1000. */
    @Column(name = "approval_note", length = 1000, updatable = false)
    private String approvalNote;

    @Version
    private Long version;
}
