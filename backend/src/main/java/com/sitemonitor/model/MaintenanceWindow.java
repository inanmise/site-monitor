package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bakım penceresi — atanan monitörler için (planlı/plansız) bir zaman aralığında alarmları bastırır ve bu süredeki
 * kesintiyi uptime istatistiğinden hariç tutar. Occurrence/recurrence hesabı MaintenanceService'te (DST-güvenli, java.time).
 * {@code active=false} = duraklatıldı (paused). Recurring pencereler deaktive/silinene kadar tekrar eder.
 */
@Entity
@Table(name = "maintenance_windows")
@Data
@NoArgsConstructor
public class MaintenanceWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** true = tüm monitörler (targetsJson yok sayılır). */
    @Column(name = "all_monitors")
    private Boolean allMonitors = false;

    /** Atanan monitör hedefleri — JSON dizi [{type,target,name}]. target = alarm-anahtarı (host/url/domain). */
    @Column(name = "targets_json", columnDefinition = "TEXT")
    private String targetsJson;

    /** IANA timezone (ör. "Europe/Istanbul") — occurrence'ın yerel saat dilimi. */
    @Column(nullable = false)
    private String timezone = "Europe/Istanbul";

    /** Anchor başlangıç anı — UTC ISO ("yyyy-MM-dd'T'HH:mm:ss"). Recurring'de yerel time-of-day + tarih buradan türetilir. */
    @Column(name = "start_at", nullable = false)
    private String startAt;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 60;

    /** NONE | DAILY | WEEKLY | MONTHLY */
    @Column(nullable = false)
    private String recurrence = "NONE";

    /** WEEKLY: ISO gün numaraları CSV "1..7" (1=Pzt .. 7=Paz). */
    @Column(name = "days_of_week")
    private String daysOfWeek;

    /** MONTHLY: ayın günü (1-31; ay kısaysa son güne clamp edilir). */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** false = duraklatıldı (paused); true = etkin. */
    @Column(nullable = false)
    private Boolean active = true;

    /** Sahibi/kapsamı (opsiyonel). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "created_by")
    private String createdBy;
}
