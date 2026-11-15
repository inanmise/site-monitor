package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takım nöbet çizelgesi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Takım başına TEK satır. Sıralı
 * üye listesi {@code anchor}'dan başlayarak günlük ya da haftalık döner; o anki nöbetçiyi {@code OnCallRotation} hesaplar
 * (yer değiştirmeler {@link OnCallOverride}). {@code notifyOncall} açıksa takımın YÜKSEK / KRİTİK alarm bildirimleri
 * mevcut alıcılara EK olarak o anki nöbetçiye de (e-posta + push) gider — kapalıyken bildirim davranışı birebir aynı.
 */
@Entity
@Table(name = "oncall_schedules", uniqueConstraints = @UniqueConstraint(name = "uk_oncall_team", columnNames = "team_id"))
@Data
@NoArgsConstructor
public class OnCallSchedule {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** Çizelge etkin mi (kapalıyken nöbetçi yok, bildirim eklenmez). */
    @Column(nullable = false)
    private Boolean enabled = false;

    /** DAILY | WEEKLY. */
    @Column(length = 10, nullable = false)
    private String rotation = WEEKLY;

    /** İlk devir anı — İstanbul yerel "yyyy-MM-ddTHH:mm"; sonraki devirler bundan rotasyon uzunluğu kadar aralıkla. */
    @Column(length = 16, nullable = false)
    private String anchor;

    /** Sıralı üye kimlikleri (virgüllü, en çok 50) — ilk üye {@code anchor}'dan itibaren nöbetçi. */
    @Column(name = "member_ids", columnDefinition = "TEXT")
    private String memberIds;

    /** YÜKSEK / KRİTİK alarm bildirimleri nöbetçiye de gitsin (e-posta + push). Varsayılan KAPALI. */
    @Column(name = "notify_oncall", nullable = false)
    private Boolean notifyOncall = false;

    // ── Haftalık nöbet bildirimi (2026-11-15, kullanıcı isteği: "takımın nöbetçi bilgisini her hafta pazartesi 08:30'da
    // push ve mail ile ekibe bildirelim; takım kendisi açsın, ayarlarını kendisi yapsın"). Hepsi BOŞ OLABİLİR — eski satır
    // ve hiç açılmamış takım: kapalı. Varsayılanlar okurken uygulanır (OnCallWeeklyNoticeSlots.Settings). ──────────────

    /** Haftalık bildirim açık mı (null = kapalı — varsayılan). */
    @Column(name = "weekly_notice_enabled")
    private Boolean weeklyNoticeEnabled;

    /** E-posta kanalı (takımın alarm adresi) — null = açık. */
    @Column(name = "weekly_notice_mail")
    private Boolean weeklyNoticeMail;

    /** Push kanalı (takım üyeleri, takım bildirimi kuralları) — null = açık. */
    @Column(name = "weekly_notice_push")
    private Boolean weeklyNoticePush;

    /** Haftanın günü 1 (Pazartesi) … 7 (Pazar) — null = Pazartesi. */
    @Column(name = "weekly_notice_day")
    private Integer weeklyNoticeDay;

    /** İstanbul saati "HH:mm" — null = 08:30. */
    @Column(name = "weekly_notice_time", length = 5)
    private String weeklyNoticeTime;

    /**
     * Açma / gün / saat en son ne zaman değişti (UTC ISO) — yalnız "zamanı kaçırıldı" izinin karar ölçütü: bu andan ÖNCEKİ
     * bir dilim kaçırılmış sayılmaz (salı açılan bildirim o haftanın pazartesisini "kaçırmadı").
     */
    @Column(name = "weekly_notice_since", length = 19)
    private String weeklyNoticeSince;

    @Column(name = "updated_at", length = 19)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
