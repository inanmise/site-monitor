package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tek bir Senaryo İzleme (k6) kontrolünün sonucu. {@link PageCheck} iskeletini izler (monitorId'ye value-FK,
 * {@code ok} pipeline-uyumu bayrağı, ISO String zaman, index'ler). status = PASS/FAIL/ERROR/TIMEOUT.
 */
@Entity
@Table(name = "scripted_checks", indexes = {
        @Index(name = "idx_sc_monitor_id", columnList = "monitor_id"),
        @Index(name = "idx_sc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class ScriptedCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Pipeline-uyumu sağlık bayrağı (rollup/uptime sayımları): ok = (status == PASS). */
    @Column(nullable = false)
    private Boolean ok = false;

    /** PASS | FAIL | ERROR | TIMEOUT. */
    @Column(nullable = false)
    private String status = "ERROR";

    /** Toplam süreç süresi (ms). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** k6 süreç çıkış kodu (timeout'ta -1). */
    @Column(name = "exit_code")
    private Integer exitCode;

    /** k6 özetinden: geçen/kalan check sayısı. */
    @Column(name = "checks_passed")
    private Integer checksPassed;

    @Column(name = "checks_failed")
    private Integer checksFailed;

    /** k6 iteration_duration (ms). */
    @Column(name = "iteration_ms")
    private Long iterationMs;

    /** k6 http_req_duration özeti (ms). */
    @Column(name = "http_req_avg_ms")
    private Long httpReqAvgMs;

    @Column(name = "http_req_p95_ms")
    private Long httpReqP95Ms;

    /** Check-bazlı geçti/kaldı listesi — JSON: [{"name":..,"passed":bool}]. */
    @Column(name = "checks_json", columnDefinition = "TEXT")
    private String checksJson;

    /** Maskeli stdout/stderr kuyruğu (boyut sınırlı; secret env değerleri maskelenmiş). */
    @Column(name = "output_tail", columnDefinition = "TEXT")
    private String outputTail;

    /** Kısa hata özeti (ERROR/TIMEOUT'ta). */
    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
