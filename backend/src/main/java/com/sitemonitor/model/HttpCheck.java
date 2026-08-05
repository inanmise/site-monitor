package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bir HTTP uptime kontrolünün sonucu (geçmiş). */
@Entity
@Table(name = "http_checks", indexes = {
    @Index(name = "idx_hc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_hc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class HttpCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Sağlıklı mı: durum kodu expectedStatus pattern'ine uyuyor + hata yok. */
    @Column(nullable = false)
    private Boolean ok = false;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_ms")
    private Long responseMs;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
