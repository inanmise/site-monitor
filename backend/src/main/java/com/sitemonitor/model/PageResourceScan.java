package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sayfa Bütünlüğü "Kaynak analizi" çalıştırması (2026-10-31) — yalnız kullanıcı isteyince çalışan nesne taraması. Çalıştırma
 * başına TEK satır: özet sayılar (tarih çizelgesi + geçmiş, 1 yıl saklanır), ayrıntılı analiz ({@code analysis_json}) ve
 * nesne listesi ({@code items_json} — izleme başına SON 20 çalıştırmada tutulur, eskilerde NULL'a çekilir). Yazımlar yalnız
 * JDBC ifadeleri ({@code PageResourceScanService}); bu varlık şemayı ({@code ddl-auto}) taşır. Zamanlar UTC ISO
 * ({@code started_at} saklama politikasının sütunu); canlılık epoch ms ({@code heartbeat_ms}).
 */
@Entity
@Table(name = "page_resource_scans", indexes = {
        @Index(name = "idx_prs_monitor", columnList = "monitor_id, id"),
        @Index(name = "idx_prs_started", columnList = "started_at"),
        @Index(name = "idx_prs_status", columnList = "status")})
@Data
@NoArgsConstructor
public class PageResourceScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** İzlemenin çalıştırma anındaki takımı (bilgi; erişim izlemenin GÜNCEL takımıyla denetlenir). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    /** RUNNING | DONE | FAILED | INTERRUPTED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** direct | proxy */
    @Column(name = "via", length = 10)
    private String via;

    @Column(name = "started_at", length = 30)
    private String startedAt;

    @Column(name = "finished_at", length = 30)
    private String finishedAt;

    @Column(name = "started_ms")
    private Long startedMs;

    /** Çalıştıran pod'un son canlılık anı — eskiyen RUNNING satırı INTERRUPTED sayılır. */
    @Column(name = "heartbeat_ms")
    private Long heartbeatMs;

    @Column(name = "owner_pod", length = 120)
    private String ownerPod;

    /** Çalıştıranın görünen adı (kim çalıştırdı). */
    @Column(name = "requested_by", length = 200)
    private String requestedBy;

    @Column(name = "progress_done")
    private Integer progressDone;

    @Column(name = "progress_total")
    private Integer progressTotal;

    @Column(name = "grade")
    private Integer grade;

    @Column(name = "grade_letter", length = 2)
    private String gradeLetter;

    @Column(name = "load_ms")
    private Long loadMs;

    @Column(name = "html_ttfb_ms")
    private Long htmlTtfbMs;

    @Column(name = "html_ms")
    private Long htmlMs;

    @Column(name = "page_bytes")
    private Long pageBytes;

    @Column(name = "request_count")
    private Integer requestCount;

    @Column(name = "image_bytes")
    private Long imageBytes;

    @Column(name = "image_count")
    private Integer imageCount;

    @Column(name = "document_count")
    private Integer documentCount;

    @Column(name = "document_bytes")
    private Long documentBytes;

    @Column(name = "link_count")
    private Integer linkCount;

    @Column(name = "link_bytes")
    private Long linkBytes;

    @Column(name = "failed_count")
    private Integer failedCount;

    @Column(name = "capped")
    private Boolean capped;

    /** Hata KODU (okumada dile çevrilir): PAGE_HTTP:404, PAGE_FAILED:TIMEOUT, PAGE_BLOCKED, SHUTDOWN, STALE, INTERNAL:… */
    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "analysis_json", columnDefinition = "TEXT")
    private String analysisJson;

    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Column(name = "items_kept")
    private Boolean itemsKept;
}
