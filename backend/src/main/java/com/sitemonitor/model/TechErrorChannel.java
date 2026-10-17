package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal teknik hata raporunun KANALI (2026-10-13, kullanıcı isteği: "bir takımda birden fazla kanal olabilir; her ekip
 * kendi teknik hata raporunun şablonunu kendisi oluşturabilmeli"). Takım başına çok satır (Mobil, Bireysel İnternet
 * Şubesi, POS …); her kanalın KENDİ şablonu {@code template_json}'da durur — "X kanalının şablonunu aç" X'i açar.
 *
 * <p>Şablon eşzamanlılığı {@code template_version} ile korunur (koşullu UPDATE, {@code TechErrorChannelRepository#saveTemplate}):
 * ad / açıklama / aktiflik değişikliği şablon sürümünü ARTIRMAZ, böylece listede yeniden adlandırma açık şablon
 * düzenleyicisini çakışmaya düşürmez. Şablon sütunları {@code updatable=false} + {@code @DynamicUpdate} (inceleme
 * 2026-10-13): bir ad değişikliği arada kaydedilen şablonu okuduğu eski hâliyle ezemez. Kullanıcı kimlikleri yanıta
 * yazılmaz (DTO ad döner; opak kimlik kuralı).
 */
@Entity
@Table(name = "tech_error_channels",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_channel_team_name", columnNames = {"team_id", "name_key"}),
        indexes = @Index(name = "idx_tec_team", columnList = "team_id"))
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class TechErrorChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 120)
    private String name;

    /** Takım içinde tekillik anahtarı: kırpılmış + Türkçe küçük harf ad. */
    @JsonIgnore
    @Column(name = "name_key", nullable = false, length = 120)
    private String nameKey;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order")
    private Integer sortOrder;

    /**
     * Yönetici (executive) alıcıları (2026-10-17, S6): virgül / noktalı virgül ayraçlı adres listesi, ≤ 20 adres.
     * Doluysa raporun gönderimi PO ONAYINA bağlıdır: onaylanınca rapor bu adreslere (takım adresi bilgide) gider.
     * Boşsa eski akış (takım adresine elle gönderim) değişmez. Yalnız takımı yönetenler değiştirir.
     */
    @Column(name = "executive_emails", length = 2000)
    private String executiveEmails;

    @JsonIgnore
    @Column(name = "template_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String templateJson;

    @Column(name = "template_version", nullable = false, updatable = false)
    private int templateVersion = 1;

    @Column(name = "template_updated_at", updatable = false)
    private Instant templateUpdatedAt;

    @JsonIgnore
    @Column(name = "template_updated_by_id", updatable = false)
    private Long templateUpdatedById;

    @Column(name = "created_at")
    private Instant createdAt;

    @JsonIgnore
    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonIgnore
    @Column(name = "updated_by_id")
    private Long updatedById;
}
