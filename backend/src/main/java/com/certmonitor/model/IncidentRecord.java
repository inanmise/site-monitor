package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SRE olay/hata ledger kaydı — manuel girilen, aranabilir incident geçmişi.
 * Mevcut sertifika/alarm akışlarından bağımsız, izole modül. Tüm zaman damgaları
 * ISO-8601 UTC String (proje konvansiyonu; AlertEvent/AuditLog ile aynı).
 *
 * Şema RAG-hazır: yapılandırılmış kolonlar (severity/category/service/rca/çözüm/
 * sla/error-budget) ileride event-korelasyon / RAG için indekslenebilir.
 */
@Entity
@Table(
    name = "incident_records",
    indexes = {
        @Index(name = "idx_inc_occurred_at", columnList = "occurredAt"),
        @Index(name = "idx_inc_severity",    columnList = "severity"),
        @Index(name = "idx_inc_category",    columnList = "category"),
        @Index(name = "idx_inc_status",      columnList = "status"),
        @Index(name = "idx_inc_service",     columnList = "service"),
        @Index(name = "idx_inc_channel",     columnList = "channel"),
        @Index(name = "idx_inc_team",        columnList = "teamId")
    }
)
@Data
@NoArgsConstructor
public class IncidentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    /** ISO-8601 UTC — olayın gerçekleştiği an; trend gün gruplaması bunun üzerinden. */
    @Column(nullable = false, length = 30)
    private String occurredAt;

    private String detectedAt;
    private String resolvedAt;

    /** CRITICAL | HIGH | MEDIUM | LOW */
    @Column(nullable = false, length = 20)
    private String severity;

    /** OPEN | INVESTIGATING | MITIGATED | RESOLVED */
    @Column(nullable = false, length = 20)
    private String status;

    /** DATABASE | NETWORK | CERTIFICATE | APPLICATION | INFRASTRUCTURE | OTHER */
    @Column(nullable = false, length = 30)
    private String category;

    /** Hata kodu — opsiyonel; incident_options(type=ERROR_CODE) listesinden seçilir ya da
     *  yeni eklenir (creatable). Kullanıcı kodu biliyorsa combobox'tan seçer, bilmiyorsa yeni ekler. */
    @Column(length = 100)
    private String errorCode;

    /** Fonksiyon kodu — opsiyonel; incident_options(type=FUNCTION_CODE) listesinden seçilir ya da
     *  yeni eklenir (creatable). Biliniyorsa girilir; bilinen kod yoksa combobox'a eklenir. */
    @Column(length = 100)
    private String functionCode;

    /** Kanal kodu — opsiyonel; incident_options(type=CHANNEL_CODE) listesinden seçilir ya da
     *  yeni eklenir (creatable). Mevcut serbest "channel" alanından ayrı bir koddur. */
    @Column(length = 100)
    private String channelCode;

    /** Etkilenen servis/domain — çoklu seçim CSV ('a, b, c'). incident sayfasından yönetilen liste; arama hedefi. */
    @Column(length = 500)
    private String service;

    /** Kanal(lar) — incident'ın geldiği kanal/uygulama, çoklu seçim CSV ('ATM, IVR'). (Bireysel İnternet
     *  Şubesi, Çağrı Merkezi Inbound, IVR, ATM ...). incident_options(type=CHANNEL) listesinden; sayfadan eklenebilir. */
    @Column(length = 500)
    private String channel;

    /** Olayın kayıtlı olduğu takım — girişte kullanıcı seçer (createdByTeamId = girişi yapanın
     *  takımı; bu ise olayın ait olduğu takım, farklı olabilir). teamName görüntü için snapshot. */
    private Long teamId;

    @Column(length = 255)
    private String teamName;

    /** Sade dille kök neden (Layer-1 executive). */
    @Column(columnDefinition = "TEXT")
    private String rcaSummary;

    /** Teknik detay / belirtiler. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Müdahale / çözüm adımları (intervention ledger). */
    @Column(columnDefinition = "TEXT")
    private String resolutionSteps;

    /** İş etkisi açıklaması. */
    @Column(columnDefinition = "TEXT")
    private String businessImpact;

    /** Blast-radius — etkilenen servisler (CSV; ileride normalize edilebilir). */
    @Column(length = 255)
    private String affectedServices;

    @Column(nullable = false)
    private Boolean slaBreached = false;

    /** Error budget tüketimi yüzdesi (ör. 12.0 = %12). */
    private Double errorBudgetBurnPct;

    private Integer durationMinutes;

    @Column(length = 500)
    private String runbookUrl;

    /** Kategori/dedup gruplama etiketleri (CSV). */
    @Column(length = 255)
    private String tags;

    private String createdBy;
    private Long   createdById;
    private Long   createdByTeamId;
    private String createdAt;
    private String updatedAt;
    private String updatedBy;
}
