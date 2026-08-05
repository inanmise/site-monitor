package com.sitemonitor.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "certificate_inventory")
@Data
@NoArgsConstructor
public class CertificateInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Domain boş olamaz")
    @Size(max = 253, message = "Domain en fazla 253 karakter olabilir")
    @Pattern(
        regexp = "^(?!-)(?!.*--)(?:\\*\\.)?[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
        message = "Geçersiz domain formatı"
    )
    @Column(nullable = false, unique = true)
    private String domain;

    @Min(value = 1,     message = "Port 1 ile 65535 arasında olmalı")
    @Max(value = 65535, message = "Port 1 ile 65535 arasında olmalı")
    @Column(nullable = false)
    private Integer port = 443;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String owner;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "deleted_at")
    private String deletedAt;

    /** Team responsible for this certificate */
    @Column(name = "team_id")
    private Long teamId;

    /** SHA-256 hex of the expected (newly renewed) cert — used for DEPLOYMENT_INCOMPLETE detection */
    private String expectedFingerprint;

    @Column(columnDefinition = "TEXT")
    private String expectedSubject;

    // ── İkinci takım (Uygulama Geliştirici / UG) ──────────────────────────────
    @Column(name = "ug_team_id")
    private Long ugTeamId;

    /** Mantıksal grup (ör. "X Sistemleri") — takım-bazlı; monitoring_groups registry'sine bağlı. */
    @Column(name = "group_name")
    private String groupName;

    // ── Operasyonel boolean alanlar ───────────────────────────────────────────
    @Column(name = "external_vendor")    private Boolean externalVendor;
    @Column(name = "action_required")    private Boolean actionRequired;
    @Column(name = "openshift")          private Boolean openshift;
    @Column(name = "ssl_pinning")        private Boolean sslPinning;
    @Column(name = "internal_cert")      private Boolean internalCert;
    @Column(name = "jks_keystore")       private Boolean jksKeystore;
    @Column(name = "server_update")      private Boolean serverUpdate;
    @Column(name = "netscaler")          private Boolean netscaler;
    @Column(name = "waf_enabled")        private Boolean wafEnabled;
    @Column(name = "in_use")             private Boolean inUse;
    @Column(name = "ev_certificate")     private Boolean evCertificate;
    @Column(name = "transferred_to_sy")  private Boolean transferredToSy;
    @Column(name = "use_proxy")          private Boolean useProxy;

    /** Per-domain TLS handshake mode override: null=inherit global setting,
     *  "browser" (TLS 1.2 + ALPN) or "default" (JDK defaults, TLS 1.3). */
    @Column(name = "tls_mode", length = 16)
    private String tlsMode;

    // ── Süreç ve açıklama alanları ────────────────────────────────────────────
    @Column(name = "purchased_by", length = 200)
    private String purchasedBy;

    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    /** Criticality tier: 1=Customer-Facing Prod, 2=Internal Prod, 3=UAT/Pre-Prod, 4=Dev/Sandbox, null=unclassified */
    @Min(value = 1, message = "Tier 1 ile 4 arasında olmalı")
    @Max(value = 4, message = "Tier 1 ile 4 arasında olmalı")
    @Column(name = "tier")
    private Integer tier;

    private String createdAt;
    private String updatedAt;

    // ── Alan adı (registrar) süre bitişi — Alan Adı Tanılama aracıyla doldurulur (TLS sertifika bitişinden AYRI). ──
    @Column(name = "domain_expiry", columnDefinition = "TEXT")
    private String domainExpiry;

    @Column(name = "domain_registrar", columnDefinition = "TEXT")
    private String domainRegistrar;

    @Column(name = "domain_expiry_checked_at", columnDefinition = "TEXT")
    private String domainExpiryCheckedAt;

    // ── Liste yanıtı için takım isimleri (DB'de tutulmaz; listInventory doldurur) ──
    // USER rolünde frontend tüm takım listesini çekemediğinden (kendi takımıyla
    // filtreli) isimler burada sunucuda çözülür → her rolde SY/UG takım adı görünür.
    @Transient private String teamName;
    @Transient private String ugTeamName;
}
