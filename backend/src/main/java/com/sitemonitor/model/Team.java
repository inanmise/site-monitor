package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "teams")
@Data
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    /** ID of the user who leads / owns this team (PO or team lead). Required. */
    @Column(name = "leader_id")
    private Long leaderId;

    /** Elle atanmış takım müdürü (2026-09-10). NULL → Takım Yönetimi ekranı üyelerin AD müdür zincirinden
     *  türetir (utils/teamManager.js). Dolu → ekranda bu kişi yazılır; AD zinciri ezilmez, yalnız öncelik alır.
     *  Nullable kolon: ddl-auto=update dolu tabloya sorunsuz ekler (NOT NULL/UNIQUE değil). */
    @Column(name = "manager_id")
    private Long managerId;

    @Column(length = 200)
    private String email;

    /** Cuma "haftalık raporunu gir" hatırlatması bu takıma gitsin mi? Opt-in: YENİ takım kapalı doğar,
     *  takımın kendi üyeleri Takım Yönetimi'nden açar. Mevcut takımlar açılış yamasında TRUE'ya
     *  çekilir (davranışları değişmesin) — bu yüzden nullable Boolean ve okuma Boolean.TRUE.equals ile. */
    @Column(name = "weekly_reminder_enabled")
    private Boolean weeklyReminderEnabled = false;

    /** Pazartesi haftalık erişilebilirlik raporu bu takıma gitsin mi? Kurallar yukarıdakiyle aynı. */
    @Column(name = "weekly_availability_enabled")
    private Boolean weeklyAvailabilityEnabled = false;

    /** Haftalık rapor kanal şablonu — JSON dizi (["İnternet","Çağrı Merkezi"]); takımın ilk raporu bununla başlar (2026-09-13). */
    /**
     * Haftalık Raporlar modülü bu takıma AÇIK mı (2026-09-16, kullanıcı kararı)? VARSAYILAN KAPALI:
     * modül herkese görünen bir ekran değildir; yönetici Ayarlar → Haftalık Raporlar'dan takım takım açar.
     * Kapalıyken takım sayfayı görmez, uçlar 403 döner, hatırlatma maili/panosu o takımı saymaz.
     */
    @Column(name = "weekly_reports_enabled")
    private Boolean weeklyReportsEnabled = false;

    @Column(name = "weekly_channels", columnDefinition = "TEXT")
    private String weeklyChannels;

    private String createdAt;
    private String updatedAt;
}
