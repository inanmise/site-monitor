package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Takımın kanal teknik hata raporu ŞABLON KÜTÜPHANESİ (2026-10-18, kullanıcı kararı D17: "takım şablon kütüphanesi / takım
 * varsayılanı — yeni kanal onu alır; 'varsayılana dön' de seçenek sunar"). Takım başına en çok 20 adlandırılmış şablon;
 * biri "takım varsayılanı" olabilir (en çok BİR — servis takım satırını kilitleyip öncekini koşullu toplu UPDATE ile
 * düşürür, PostgreSQL'de kısmi tekil indeks de korur). Şablon yazılırken KATI doğrulanır, okunurken hoşgörülü
 * ({@code TechErrorTemplate.fromStored}). Liste satırı şablon gövdesini taşımaz: sayımlar ve dil sütunlarda.
 *
 * <p>Kütüphane takımın KENDİ işidir: okuma ve yazma takımın kendi kapsamıyla (erişim paylaşımı açmaz). Takım silinince
 * yetim satırlar saklama kuralıyla temizlenir (kanallarla aynı yol). Kullanıcı kimlikleri yanıta yazılmaz — yalnız o anki
 * ad ({@code *_by_name}).
 */
@Entity
@Table(name = "tech_error_team_templates",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_team_template_name", columnNames = {"team_id", "name_key"}),
        indexes = @Index(name = "idx_tett_team", columnList = "team_id"))
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class TechErrorTeamTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Takım içinde tekillik anahtarı: kırpılmış + Türkçe küçük harf ad (kanallarla aynı kural). */
    @JsonIgnore
    @Column(name = "name_key", nullable = false, length = 100)
    private String nameKey;

    @JsonIgnore
    @Column(name = "template_json", columnDefinition = "TEXT", nullable = false)
    private String templateJson;

    /** Şablonun dili (tr | en) — liste satırı gövdeyi okumadan gösterir. */
    @Column(name = "template_lang", length = 4)
    private String templateLang;

    @Column(name = "section_count", nullable = false)
    private int sectionCount;

    @Column(name = "field_count", nullable = false)
    private int fieldCount;

    @Column(name = "slot_count", nullable = false)
    private int slotCount;

    /** Takım varsayılanı mı (takım başına en çok bir satır). Yeni kanal penceresi bunu ilk kaynak olarak seçer. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultEntry = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @JsonIgnore
    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonIgnore
    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;

    @Version
    private Long version;
}
