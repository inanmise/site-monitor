package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Haftalık erişilebilirlik e-postası idempotency kaydı — bir takıma aynı (yıl, hafta) için
 * iki kez gönderim olmasın (cron çift tetik / manuel + zamanlanmış çakışması). Pencere = ISO hafta.
 */
@Entity
@Table(name = "weekly_availability_log",
    uniqueConstraints = @UniqueConstraint(name = "uk_wal_team_week", columnNames = {"team_id", "report_year", "week_no"}),
    indexes = @Index(name = "idx_wal_team_week", columnList = "team_id,report_year,week_no"))
@Data
@NoArgsConstructor
public class WeeklyAvailabilityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "week_no", nullable = false)
    private Integer weekNo;

    /** SENT | FAILED | NO_RECIPIENT */
    private String status;

    @Column(name = "sent_at")
    private String sentAt;

    @Column(name = "created_at")
    private String createdAt;
}
