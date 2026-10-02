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
 * Sistem bakımında SUSTURULAN alarm bildiriminin kaydı (2026-10-02, kullanıcı kararı) — bakım × alarm başına TEK satır.
 *
 * <p>"Bildirimler bakım boyunca sussun" açıkken her alarm bildirimi (e-posta, webhook, push, 7/24) gönderilmez; iz
 * {@code notification_logs}'a ({@code SYSTEM_MAINTENANCE / SKIPPED: sistem bakımı}) düşer. Bu tablo ise bakım bitince
 * yapılacak TELAFİNİN listesidir: {@link #opening} = alarmın AÇILIŞ bildirimi (INITIAL / ESCALATION) susturuldu. Bakım
 * bitişinde hâlâ açık ve onaylanmamış olan böyle alarmların "ilk bildirim" damgası sıfırlanır; bir sonraki tur INITIAL'ı
 * normal kurallarla BİR kez gönderir. Bakım içinde kapanan alarm için hiçbir şey gitmez ({@link #outcome}).
 *
 * <p>Saklama: {@code first_at} üzerinden bildirim günlüğüyle aynı süre (RetentionCatalog
 * {@code system-maintenance-suppressions}).
 */
@Entity
@Table(name = "system_maintenance_suppressions",
        uniqueConstraints = @UniqueConstraint(name = "uq_sms_window_alert", columnNames = {"window_id", "alert_event_id"}))
@Data
@NoArgsConstructor
public class SystemMaintenanceSuppression {

    public static final String OUTCOME_CAUGHT_UP = "CAUGHT_UP";
    public static final String OUTCOME_RESOLVED = "RESOLVED";
    public static final String OUTCOME_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String OUTCOME_NOT_OPENING = "NOT_OPENING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** İlk susturulan bildirimin tetiği (INITIAL / ESCALATION / DAILY_REALERT / RESOLUTION …). */
    @Column(name = "first_trigger", length = 40)
    private String firstTrigger;

    /** Açılış bildirimi (INITIAL / ESCALATION) susturuldu mu — telafinin koşulu. */
    @Column(name = "opening")
    private Boolean opening;

    @Column(name = "suppressed_count")
    private Integer suppressedCount;

    @Column(name = "first_at", length = 30)
    private String firstAt;

    @Column(name = "last_at", length = 30)
    private String lastAt;

    /** Telafi kararı: CAUGHT_UP / RESOLVED / ACKNOWLEDGED / NOT_OPENING. */
    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "caught_up_at", length = 30)
    private String caughtUpAt;
}
