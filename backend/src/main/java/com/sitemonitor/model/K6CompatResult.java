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
 * Uyumluluk taramasında bir izlemenin ADAY k6 ile sonucu + karşılaştırıldığı son gerçek kontrol (2026-10-29).
 *
 * <p>{@code diff}: SAME_PASS (ikisi de geçti) · SAME_FAIL (zaten başarısızdı, aynı durum) · REGRESSION (gerçekte geçiyor,
 * aday ile geçmiyor — geçişi durduran bulgu) · FLAKY (aday ilk denemede geçmedi, yeniden denemede geçti) · IMPROVED · CHANGED
 * (ikisi de başarısız, durum farklı) · SLOWER (ikisi de geçti, aday belirgin yavaş) · NO_BASELINE (karşılaştırılacak gerçek
 * kontrol yok) · NOT_RUN (koşulamadı — ör. aday ikili yok, dosya okuma engeli).
 *
 * <p>Hata metni maskelenmiştir (gizli env değerleri) ve kırpılır; betik çıktısı saklanmaz.
 */
@Entity
@Table(name = "k6_compat_results", indexes = {
        @Index(name = "idx_k6cr_scan_id", columnList = "scan_id")
})
@Data
@NoArgsConstructor
public class K6CompatResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "monitor_id")
    private Long monitorId;

    @Column(name = "monitor_name", length = 300)
    private String monitorName;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "via_proxy")
    private Boolean viaProxy;

    /** Aday ile {@code k6 archive}: OK / TIMED_OUT / DEFINITE / INCONCLUSIVE / NOT_RUN. */
    @Column(length = 16)
    private String archive;

    @Column(name = "archive_error", columnDefinition = "TEXT")
    private String archiveError;

    /** Aday ile koşum durumu (PASS / FAIL / ERROR / TIMEOUT / NO_CHECKS / SKIPPED); derlenemediyse null. */
    @Column(length = 16)
    private String status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "checks_passed")
    private Integer checksPassed;

    @Column(name = "checks_failed")
    private Integer checksFailed;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** SYNTAX / MODULE / TLS / DNS / CONNECT / TIMEOUT / CHECK / SCRIPT / OTHER — null = hata yok. */
    @Column(name = "error_class", length = 20)
    private String errorClass;

    /** Gerilemede bir kez yeniden denendi mi (FLAKY ayrımı). */
    private Boolean retried;

    @Column(name = "baseline_status", length = 16)
    private String baselineStatus;

    @Column(name = "baseline_exit_code")
    private Integer baselineExitCode;

    @Column(name = "baseline_checks_passed")
    private Integer baselineChecksPassed;

    @Column(name = "baseline_checks_failed")
    private Integer baselineChecksFailed;

    @Column(name = "baseline_duration_ms")
    private Long baselineDurationMs;

    @Column(name = "baseline_checked_at", length = 30)
    private String baselineCheckedAt;

    @Column(name = "baseline_k6_version", length = 20)
    private String baselineK6Version;

    @Column(name = "baseline_error_class", length = 20)
    private String baselineErrorClass;

    @Column(length = 20)
    private String diff;

    @Column(name = "checked_at", length = 30)
    private String checkedAt;
}
