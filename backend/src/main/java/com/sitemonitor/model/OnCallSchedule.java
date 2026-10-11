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

    @Column(name = "updated_at", length = 19)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
