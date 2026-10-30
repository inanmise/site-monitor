package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Merkezi izleme alarm akışının ({@code GET /api/public/alarm-feed}) DIŞ çağrı günlüğü — 2026-10-30, kullanıcı isteği: "kim ne
 * zaman çağırdı, hangi IP'den, ne döndük bilgisi hep elimizde olsun". Her çağrı (reddedilenler dahil) bir satır; yanıtın
 * BİREBİR kopyası {@link AlarmFeedResponse}'ta özet ({@code response_hash}) ile tutulur — dakikalık çağrıların çoğu aynı
 * gövdeyi döndürdüğü için her gövde bir kez saklanır.
 *
 * <p>Zamanlar ISO-8601 UTC ({@code yyyy-MM-dd'T'HH:mm:ss.SSS}, sıralanabilir metin). Metin alanları sütuna sığdırılır
 * ({@link #fitColumns}) — çok uzun bir başlık satırı yazımı düşürmesin.
 */
@Entity
@Table(name = "alarm_feed_requests", indexes = {
        @Index(name = "idx_afr_requested", columnList = "requested_at"),
        @Index(name = "idx_afr_ip_requested", columnList = "client_ip,requested_at"),
        @Index(name = "idx_afr_hash", columnList = "response_hash")
})
@Data
@NoArgsConstructor
public class AlarmFeedRequest {

    public static final int IP_MAX = 64;
    public static final int FORWARDED_MAX = 500;
    public static final int UA_MAX = 500;
    public static final int QUERY_MAX = 1000;
    public static final int ERROR_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_at", nullable = false, length = 30)
    private String requestedAt;

    /** Uygulamanın çözdüğü istemci IP'si ({@code ClientIpResolver} — erişim listesi bununla karşılaştırılır). */
    @Column(name = "client_ip", length = IP_MAX)
    private String clientIp;

    /** Bağlantının geldiği adres (çoğunlukla giriş katmanı / ingress). */
    @Column(name = "remote_addr", length = IP_MAX)
    private String remoteAddr;

    /** Ham {@code X-Forwarded-For} zinciri (kanıt — çözülmemiş hali). */
    @Column(name = "forwarded_for", length = FORWARDED_MAX)
    private String forwardedFor;

    @Column(name = "user_agent", length = UA_MAX)
    private String userAgent;

    @Column(length = 10)
    private String method;

    /** Sorgu dizgesi (yoksa null). */
    @Column(name = "query_string", length = QUERY_MAX)
    private String query;

    /** HTTP durum kodu. */
    private Integer status;

    /** OK | DISABLED | IP_DENIED | RATE_LIMITED | ERROR. */
    @Column(length = 20)
    private String outcome;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "open_count")
    private Integer openCount;

    @Column(name = "resolved_count")
    private Integer resolvedCount;

    /** Yanıt tavanı kestiyse true. */
    private Boolean truncated;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "response_bytes")
    private Long responseBytes;

    /** Yanıt gövdesinin SHA-256 özeti — gövde {@link AlarmFeedResponse} satırında (saklama açıksa). */
    @Column(name = "response_hash", length = 64)
    private String responseHash;

    /** json | csv. */
    @Column(length = 8)
    private String format;

    /** Yanıtlayan pod'un adı. */
    @Column(name = "served_by", length = 120)
    private String servedBy;

    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Hata sınıfı (yalnız ERROR / sorun; ileti değil). */
    @Column(length = ERROR_MAX)
    private String error;

    @PrePersist
    void fitColumns() {
        clientIp = fit(clientIp, IP_MAX);
        remoteAddr = fit(remoteAddr, IP_MAX);
        forwardedFor = fit(forwardedFor, FORWARDED_MAX);
        userAgent = fit(userAgent, UA_MAX);
        query = fit(query, QUERY_MAX);
        error = fit(error, ERROR_MAX);
        method = fit(method, 10);
        servedBy = fit(servedBy, 120);
        requestId = fit(requestId, 64);
    }

    public static String fit(String v, int max) {
        if (v == null) return null;
        if (v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }
}
