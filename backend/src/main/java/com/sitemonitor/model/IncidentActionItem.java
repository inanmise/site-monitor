package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Postmortem aksiyonu (2026-10-11): olaydan çıkan iş — sahibi, son tarihi, önceliği, türü (önle / tespit et / etkiyi
 * azalt / süreç) ve durumu. Sahip bir kullanıcıdır ({@code owner_user_id}, yanıtta opak kimlik — UserRefWire.KEYS);
 * ad anlık görüntü olarak da tutulur (kullanıcı silinse de satır okunur). Son tarih İstanbul günü {@code yyyy-MM-dd}.
 * {@code reminded_on}: son hatırlatma günü — günlük iş koşullu UPDATE ile sahiplenir (iki pod aynı hatırlatmayı atmaz).
 */
@Entity
@Table(name = "incident_action_items", indexes = {
        @Index(name = "idx_inc_action_incident", columnList = "incident_id"),
        @Index(name = "idx_inc_action_owner", columnList = "owner_user_id"),
        @Index(name = "idx_inc_action_status_due", columnList = "status,due_date")
})
@Data
@NoArgsConstructor
public class IncidentActionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** PREVENT | DETECT | MITIGATE | PROCESS | OTHER */
    @Column(nullable = false, length = 20)
    private String kind;

    /** HIGH | MEDIUM | LOW */
    @Column(nullable = false, length = 10)
    private String priority;

    /** OPEN | IN_PROGRESS | DONE | CANCELLED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "owner_name", length = 255)
    private String ownerName;

    /** yyyy-MM-dd (İstanbul günü); null = son tarih yok. */
    @Column(name = "due_date", length = 10)
    private String dueDate;

    @Column(name = "created_by", length = 100) private String createdBy;
    @Column(name = "created_at", length = 30) private String createdAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;
    @Column(name = "updated_at", length = 30) private String updatedAt;
    @Column(name = "completed_at", length = 30) private String completedAt;
    @Column(name = "reminded_on", length = 10) private String remindedOn;
}
