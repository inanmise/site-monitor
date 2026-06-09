package com.certmonitor.model;

import jakarta.persistence.*;
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

    @Column(nullable = false, unique = true)
    private String domain;

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

    // ── Süreç ve açıklama alanları ────────────────────────────────────────────
    @Column(name = "purchased_by", length = 200)
    private String purchasedBy;

    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    /** Criticality tier: 1=Customer-Facing Prod, 2=Internal Prod, 3=UAT/Pre-Prod, 4=Dev/Sandbox, null=unclassified */
    @Column(name = "tier")
    private Integer tier;

    private String createdAt;
    private String updatedAt;
}
