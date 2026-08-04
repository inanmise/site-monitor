package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir sayfa-bütünlüğü kontrolünün ÖZETİ (geçmiş). Sağlıklı kaynaklar yalnız buradaki sayaçlara yansır;
 * sorunlu kaynaklar ayrıca {@link PageResourceIssue} satırlarına yazılır. {@code ok}/{@code status}
 * çifti: {@code ok} pipeline uyumu için (HttpCheck deseni), {@code status} üç-durumlu UI/rollup semantiği.
 */
@Entity
@Table(name = "page_checks", indexes = {
    @Index(name = "idx_pc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_pc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class PageCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Sağlıklı mı: ana sayfa alındı + alarm-uygun sorun yok. (pipeline uyumu; status ile birlikte) */
    @Column(nullable = false)
    private Boolean ok = false;

    /** Üç-durumlu özet: OK | DEGRADED (sayfa döndü, sorunlu kaynak var) | DOWN (ana sayfa alınamadı). */
    @Column(name = "status")
    private String status = "OK";

    /** Ana sayfanın HTTP durum kodu. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Toplam kontrol süresi (ms) — ana sayfa + kaynak doğrulama. */
    @Column(name = "response_ms")
    private Long responseMs;

    /** Bulunan toplam (tekilleştirilmiş) kaynak sayısı. */
    @Column(name = "total_resources")
    private Integer totalResources = 0;

    /** Kırık kaynak sayısı (yalnız BROKEN — 2026-08-04'e kadar TIMEOUT da bu sayaçtaydı; artık ayrı). */
    @Column(name = "broken_resources")
    private Integer brokenResources = 0;

    /** Zaman aşımı kaynak sayısı (TIMEOUT) — kırıktan AYRI sayaç; eski satırlarda null (o dönem kırığa dahildi). */
    @Column(name = "timeout_count")
    private Integer timeoutCount = 0;

    /** Mixed content (https sayfada http kaynak) sayısı. */
    @Column(name = "mixed_content_count")
    private Integer mixedContentCount = 0;

    /** SITE_CRAWL modunda taranan sayfa sayısı (SINGLE_PAGE'te 1). */
    @Column(name = "pages_crawled")
    private Integer pagesCrawled = 1;

    /** Ana sayfa gövdesinin içerik hash'i (defacement sinyali — bilgi amaçlı, alarm üretmez). */
    @Column(name = "content_hash")
    private String contentHash;

    /** Ana sayfa gövde boyutu (bayt) — önceki kontrollere göre anormal sapma sinyali (bilgi amaçlı). */
    @Column(name = "body_bytes")
    private Long bodyBytes;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
