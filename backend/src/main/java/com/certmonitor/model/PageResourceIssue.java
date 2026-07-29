package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir sayfa-bütünlüğü kontrolünde tespit edilen TEK sorunlu kaynak (yalnız sorunlular saklanır).
 * Sağlıklı kaynaklar {@link PageCheck} sayaçlarına yansır. En hızlı büyüyen tablolardan biri olduğu için
 * (monitor_id, checked_at) index'i + gece batch-purge (SchedulerService) baştan tasarlanmıştır.
 */
@Entity
@Table(name = "page_resource_issues", indexes = {
    @Index(name = "idx_pri_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_pri_checked_at", columnList = "checked_at"),
    @Index(name = "idx_pri_check_id", columnList = "check_id")
})
@Data
@NoArgsConstructor
public class PageResourceIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bağlı olduğu PageCheck (gece purge önce çocuk sonra ana tablo). */
    @Column(name = "check_id", nullable = false)
    private Long checkId;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Sorunlu kaynağın URL'i. */
    @Column(name = "resource_url", columnDefinition = "TEXT")
    private String resourceUrl;

    /** Kaynak türü: IMG | CSS | JS | LINK | IFRAME | FONT | FAVICON. */
    @Column(name = "resource_type")
    private String resourceType;

    /** Kaynağın bulunduğu (referans veren) sayfa URL'i — SITE_CRAWL'da kaynak-sayfa→hedef ilişkisi. */
    @Column(name = "source_page", columnDefinition = "TEXT")
    private String sourcePage;

    /** Sorun türü: BROKEN | MIXED_CONTENT | TIMEOUT | SLOW. */
    @Column(name = "issue_type")
    private String issueType;

    /** Birinci-taraf (same-origin) mı — alarm politikası ({@code alertThirdParty}) buna bakar. */
    @Column(name = "first_party")
    private Boolean firstParty = true;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "checked_at")
    private String checkedAt;
}
