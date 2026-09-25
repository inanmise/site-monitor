package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_actor",      columnList = "actor"),
    @Index(name = "idx_audit_event_time", columnList = "event_time"),
    @Index(name = "idx_audit_event_type", columnList = "event_type"),
    @Index(name = "idx_audit_ip",         columnList = "ip_address"),
    @Index(name = "idx_audit_outcome",    columnList = "outcome"),
    @Index(name = "idx_audit_actor_id",   columnList = "actor_id"),
    @Index(name = "idx_audit_resource",   columnList = "resource_type, resource_id"),
    @Index(name = "idx_audit_correlation", columnList = "correlation_id"),
    @Index(name = "idx_audit_seq",        columnList = "seq"),
    @Index(name = "idx_audit_actor_team_time", columnList = "actor_team_id, event_time")
})
@Data
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Tamper-evident hash chain (AuditService.persist yazar; append-only) ──────
    /** Monoton sıra numarası — zincir sırası (id insert-sırası olsa da ayrı, açık sıra alanı). */
    @Column(name = "seq")
    private Long seq;

    /** Bu kaydın SHA-256 hash'i = hash(değişmez çekirdek alanlar + prev_hash). Geo/PTR alanları HARİÇ. */
    @Column(name = "row_hash", length = 64)
    private String rowHash;

    /** Bir önceki kaydın row_hash'i (zincir bağı). İlk kayıtta null/GENESIS. */
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_time", nullable = false, length = 30)
    private String eventTime;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_team_id")
    private Long actorTeamId;

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "ip_country", length = 100)
    private String ipCountry;

    @Column(name = "ip_city", length = 100)
    private String ipCity;

    @Column(name = "ip_org", length = 200)
    private String ipOrg;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Oturum kimliği — YANITA ÇIKMAZ.
     *
     * <p>{@code /api/me/audit} ve admin denetim ucu satırları ENTITY olarak serileştiriyor; bu alan
     * da JSON'a giriyordu. Hiçbir arayüz onu okumuyor, ama kullanıcıya açık bir sayfanın ağ
     * yükünde oturum kimliği taşımak gereksiz bir risktir — üstelik üründe ekran görüntüsü
     * yakalayan bir "sorun bildir" akışı var ve hata bildirimleri bu yükü taşıyabilir.
     *
     * <p>{@code @JsonIgnore} bütünlük zincirini ETKİLEMEZ: {@code AuditService.canonical()} hash'i
     * açık getter'larla üretir, Jackson'la değil.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Yapısal before/after diff (JSON: {"alan":{"from":x,"to":y}}) — güncelleme eylemlerinde.
     *  Hassas alanlar (parola/token/secret) AuditDiff tarafından *** maskelenir. */
    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes;

    /** İsteğe özgü korelasyon kimliği (CorrelationIdFilter) — ilişkili olayları bağlar. */
    @Column(name = "correlation_id", length = 40)
    private String correlationId;

    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    /** Comma-separated flags: OFF_HOURS, UNUSUAL_IP, GEO_VELOCITY, BRUTE_FORCE, RATE_LIMITED */
    @Column(name = "anomaly_flags", length = 200)
    private String anomalyFlags;

    /** Login anında reverse-DNS (PTR) ile çözünen hostname — varsa. Gösterimde tekrar nslookup
     *  yapılmaz; null ise yalnız IP gösterilir. */
    @Column(name = "ip_reverse_host", length = 255)
    private String ipReverseHost;

    /**
     * Ham User-Agent'in insan okunur ozeti ("Windows · Chrome") — TUREVDIR, kolon DEGILDIR.
     *
     * <p>Denetim tablolari satirlari ENTITY olarak serilestiriyor; ozeti burada uretince hem
     * {@code /api/me/audit} hem admin denetim ucu onu bedava alir ve arayuz ikinci bir
     * ayristirici yazmak zorunda kalmaz (tek dogruluk kaynagi {@link
     * com.sitemonitor.service.UserAgentSummary}). Ham UA alani yerinde kalir: tooltip'te ve
     * satir genisletmesinde gosterilir.
     *
     * <p>{@code @Transient}: Hibernate bunu kolon sanmasin. (Kimlik alan uzerinde oldugu icin
     * erisim zaten alan-bazli, ama niyet acikca yazili olsun.)
     *
     * <p>JSON adi {@code ua_summary} — global SNAKE_CASE stratejisi.
     */
    @jakarta.persistence.Transient
    public String getUaSummary() {
        return com.sitemonitor.service.UserAgentSummary.labelOf(userAgent);
    }
}
