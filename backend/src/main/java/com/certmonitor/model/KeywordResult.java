package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bir keyword kontrolünün sonucu (geçmiş). */
@Entity
@Table(name = "keyword_results", indexes = {
    @Index(name = "idx_kwr_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_kwr_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class KeywordResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Anahtar kelime sayfada bulundu mu (koşuldan bağımsız ham gözlem). */
    @Column(nullable = false)
    private Boolean found = false;

    /** Koşula göre "sağlıklı" mı: NOT_CONTAINS→ok=!found, CONTAINS→ok=found. */
    @Column(nullable = false)
    private Boolean ok = false;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_ms")
    private Long responseMs;

    /** Eşleşme bağlamı (±50 karakter) — UI'da gösterim. */
    @Column(columnDefinition = "TEXT")
    private String snippet;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
