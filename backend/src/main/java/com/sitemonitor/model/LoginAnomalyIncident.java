package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Başarısız-login anomali INCIDENT'i — mail bombardımanını önleyen durum kaydı. Aynı anda tek açık
 * incident (global güvenlik durumu). Detektör anomali gördükçe cooldown/escalation kararları bu satıra
 * bakar; anomali bitince resolved işaretlenir. Her gönderilen uyarı ayrıca notification_logs'a düşer.
 */
@Entity
@Table(name = "login_anomaly_incident", indexes = {
        @Index(name = "idx_lai_resolved", columnList = "resolved"),
        @Index(name = "idx_lai_opened", columnList = "opened_at")
})
@Data
public class LoginAnomalyIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opened_at", length = 40)
    private String openedAt;            // ISO UTC

    @Column(name = "last_alert_at", length = 40)
    private String lastAlertAt;         // ISO UTC — cooldown burdan hesaplanır

    @Column(name = "realert_count")
    private int realertCount;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "resolved_at", length = 40)
    private String resolvedAt;

    @Column(name = "peak_total")
    private long peakTotal;             // pencere-toplamının gördüğü zirve (escalation eşiği)

    @Column(name = "rules_signature", length = 300)
    private String rulesSignature;      // tetiklenen kural kodlarının sıralı CSV imzası

    @Column(name = "rule_count")
    private int ruleCount;

    @Column(name = "last_window_start", length = 40)
    private String lastWindowStart;

    @Column(name = "last_window_end", length = 40)
    private String lastWindowEnd;

    @Column(columnDefinition = "TEXT")
    private String summary;             // son uyarının kısa insan-okur özeti (liste UI)
}
