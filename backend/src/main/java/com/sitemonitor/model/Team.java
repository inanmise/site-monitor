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

    private String createdAt;
    private String updatedAt;
}
