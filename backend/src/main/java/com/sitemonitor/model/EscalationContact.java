package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A contact that receives certificate alerts.
 * minAlertLevel controls at which severity this contact starts receiving alerts:
 *   WARNING  → receives WARNING, HIGH, CRITICAL
 *   HIGH     → receives HIGH, CRITICAL
 *   CRITICAL → receives CRITICAL only
 */
@Entity
@Table(name = "escalation_contacts")
@Data
@NoArgsConstructor
// Yalnız değişen kolonlar yazılır (kapı incelemesi 2026-11-15): tekil düzenleme (AdminController.updateContact, tek
// işlemde oku-yaz) aynı anda yapılan toplu etkin / seviye / takım yazımını (EscalationContactRepository.bulkSet*) geri almaz.
@org.hibernate.annotations.DynamicUpdate
public class EscalationContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Link to an AppUser — when set, name/email are derived from the user on save */
    @Column(name = "user_id")
    private Long userId;

    /**
     * Bağlı kullanıcı etkin mi — yalnız kişi listesi uçlarında doldurulur, SAKLANMAZ (2026-11-13 son inceleme): kapsamlı
     * müdürün kullanıcı listesi kapsamla süzülü olduğu için kapsam dışı kullanıcıya bağlı kişinin pasifliğini istemci
     * bilemiyordu (kapsam matrisi sürekli "bilinmiyor"). null = bağsız ya da bilinmiyor.
     */
    @Transient
    private Boolean userActive;

    /**
     * Bağlı kullanıcı SİLİNMİŞ ({@code user_id} dolu ama kullanıcı satırı yok) — yalnız kişi listesi uçlarında doldurulur,
     * SAKLANMAZ (2026-11-26, H paketi B1). Eski veri için de doğru: silme kararından önce silinmiş kullanıcıya bağlı kalmış
     * kişiler de görünür. null = bağsız ya da bilinmiyor.
     */
    @Transient
    private Boolean userDeleted;

    /**
     * Kişi neden pasif (2026-11-26, H paketi B1): {@link #DEACTIVATED_USER_DELETED} = bağlı kullanıcı silindi, kişi aynı
     * işlemde hedefli koşullu UPDATE ile pasife alındı ({@code EscalationContactRepository.deactivateForDeletedUser}).
     * null = bilinmiyor / elle. Kullanıcı bağı değişince (başka kullanıcıya bağlama ya da bağı kaldırma) temizlenir;
     * kişi elle yeniden etkinleştirilse de bağ silinmiş kullanıcıda kaldıkça değer korunur (alıcı süzgeci onu yine düşürür).
     */
    @Column(name = "deactivated_reason", length = 40)
    private String deactivatedReason;

    /** {@link #deactivatedReason} değeri: bağlı kullanıcı silindi. Kalıcı ad (arayüz rozeti bu değeri okur). */
    public static final String DEACTIVATED_USER_DELETED = "USER_DELETED";

    private String name;

    private String email;

    /** Organizational role: PO, TECH, MANAGER, CLEVEL */
    @Column(nullable = false)
    private String role;

    /** Minimum alert level to trigger notification: WARNING, HIGH, CRITICAL */
    @Column(nullable = false)
    private String minAlertLevel = "WARNING";

    /** Optional webhook URL for Teams or Slack. TEXT (DATA-3, 2026-10-22): Teams "Workflows" (Power Automate) adresleri ~290
     *  karakter — VARCHAR(255) varsayılanı kaydı 400 VALUE_TOO_LONG ile reddediyordu. */
    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    /** TEAMS or SLACK */
    private String webhookType;

    /** Team this contact belongs to — alerts only fire for certs in the same team */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    private String createdAt;

    /**
     * Zamana bağlı eskalasyon adımı (2026-10-01, opt-in). {@code null} ya da {@code 0} = BUGÜNKÜ davranış: kişi alarm
     * açılır açılmaz (ve seviye artışı / günlük hatırlatma / çözümde) bilgilendirilir. Değer (1–1440 dk) verilirse kişi
     * ANLIK bildirimlere girmez; alarm AÇIK ve ONAYSIZ olarak bu kadar dakika beklerse {@code EscalationStepService} ona
     * BİR kez "eskalasyon adımı" gönderir — adım gittikten sonra o alarmın normal alıcısıdır. Kapsam/seviye kuralları
     * ({@code EscalationContactScope}) aynen geçerlidir. Şema: {@code SchedulerService.applySchemaPatches}.
     */
    @Column(name = "delay_minutes")
    private Integer delayMinutes;
}
