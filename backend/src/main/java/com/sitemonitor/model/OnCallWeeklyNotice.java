package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Haftalık nöbet bildirimi gönderim kaydı (2026-11-15) — takım × ISO hafta başına TEK satır. Satır gönderimden ÖNCE
 * {@code SENDING} olarak eklenir ({@code uk_oncall_wn_team_week} ikinci pod'u / ikinci turu durdurur) ve sonuçla kapanır:
 * SENT / PARTIAL / SKIPPED / FAILED. Yarıda kalan (pod öldü) {@code SENDING} satırı YENİDEN GÖNDERİLMEZ, {@code UNKNOWN}
 * olur. Yalnız {@code FAILED} (hiçbir kanal ulaşmadı) aynı gün içinde sınırlı kez yeniden denenir.
 *
 * <p>{@code weekKey} = dilimin İstanbul tarihinin ISO haftası PAZARTESİ'si ("yyyy-MM-dd"): takım hafta ortasında günü
 * değiştirse de o hafta ikinci bildirim gitmez. Yazım yalnız koşullu UPDATE'lerle (OnCallWeeklyNoticeRepository).
 */
@Entity
@Table(name = "oncall_weekly_notices",
        uniqueConstraints = @UniqueConstraint(name = "uk_oncall_wn_team_week", columnNames = {"team_id", "week_key"}),
        indexes = @Index(name = "idx_oncall_wn_week", columnList = "week_key, team_id"))
@Data
@NoArgsConstructor
public class OnCallWeeklyNotice {

    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String PARTIAL = "PARTIAL";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** ISO haftanın Pazartesi'si (İstanbul) — "yyyy-MM-dd". */
    @Column(name = "week_key", length = 10, nullable = false)
    private String weekKey;

    /** Bu haftanın dilimi (UTC ISO yyyy-MM-ddTHH:mm:ss). */
    @Column(name = "slot_at", length = 19)
    private String slotAt;

    /** SENDING | SENT | PARTIAL | SKIPPED | FAILED | UNKNOWN. */
    @Column(length = 12, nullable = false)
    private String status;

    /** Kalıcı neden kodu (NO_ONCALL, MAINTENANCE, MISSED, CHANNELS_OFF, NO_TEAM, TEAM_INACTIVE, INTERRUPTED, NO_RECIPIENT …). */
    @Column(length = 32)
    private String reason;

    /** E-posta sonucu (posta servisinin durum metni ya da "SKIPPED: …"), sütuna sığdırılır. */
    @Column(name = "mail_status", length = 255)
    private String mailStatus;

    /** Kuyruğa giren push sayısı. */
    /** Push kanalının sonucu: delivered | skipped | failed | off (null = eski satır / hiç gönderilmedi). */
    @Column(name = "push_status", length = 16)
    private String pushStatus;

    @Column(name = "push_count")
    private Integer pushCount;

    /** Hata sınıfı (yalnız sınıf adı — ileti değil). */
    @Column(name = "error_class", length = 120)
    private String errorClass;

    /** Deneme sayısı (FAILED yeniden denemesinde artar). */
    @Column(nullable = false)
    private Integer attempts = 1;

    /** Sahiplenme anı (UTC ISO) — koşullu kapanışın anahtarı. */
    @Column(name = "claimed_at", length = 19)
    private String claimedAt;

    /** Sahiplenen pod. */
    @Column(name = "claimed_by", length = 120)
    private String claimedBy;

    @Column(name = "finished_at", length = 19)
    private String finishedAt;

    /** Satırın ilk yazıldığı an (UTC ISO) — saklama kuralının yaş kolonu. */
    @Column(name = "created_at", length = 19, nullable = false)
    private String createdAt;
}
