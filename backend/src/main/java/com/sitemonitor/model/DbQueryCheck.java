package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Veritabanı sorgu izlemesinin tek çalıştırması (ekle-yalnız seri, 2026-10-31).
 *
 * <p>{@code ok} = kullanılabilirlik: YALNIZ sorgu çalıştırılamadığında (bağlantı / kimlik / SQL hatası, zaman aşımı) false.
 * Kural ihlali bir kesinti değildir: {@code status = BREACH}, {@code ok = true} — sparkline / SLA / günlük özet ihlali
 * "kesinti" saymaz; kontrol geçmişindeki "sorunlu" süzgeci {@code status <> 'OK'} okur.
 *
 * <p>SATIR VERİSİ burada YOKTUR (ilke: izleme bir veri çekme aracı değildir) — yalnız sayılar, kural sonuçları ve maskeli
 * hata metni. Alarm anındaki satırlar ayrı tabloda ({@link DbQueryAlertRows}) alarmla birlikte saklanır.
 */
@Entity
@Table(name = "dbquery_checks",
       indexes = @Index(name = "idx_dbqc_monitor_checked", columnList = "monitor_id,checked_at"))
@Data
@NoArgsConstructor
public class DbQueryCheck {

    public static final String STATUS_OK = "OK", STATUS_BREACH = "BREACH", STATUS_FAIL = "FAIL";
    public static final String LEVEL_NONE = "NONE", LEVEL_WARNING = "WARNING", LEVEL_CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "checked_at", nullable = false, length = 30)
    private String checkedAt;

    @Column(nullable = false)
    private Boolean ok = true;

    /** {@link #STATUS_OK} | {@link #STATUS_BREACH} | {@link #STATUS_FAIL}. */
    @Column(length = 10)
    private String status;

    /** İhlal seviyesi: {@link #LEVEL_NONE} | {@link #LEVEL_WARNING} | {@link #LEVEL_CRITICAL} (FAIL'de NONE). */
    @Column(name = "breach_level", length = 10)
    private String breachLevel;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "row_count")
    private Integer rowCount;

    /** Satır sayısı okunma sınırında kesildi ("en az {@code rowCount}"). */
    @Column(name = "row_count_capped")
    private Boolean rowCountCapped;

    /** Grafik değeri: ilk değerlendirilen sayısal kuralın ölçtüğü sayı (yoksa satır sayısı). */
    @Column(name = "metric_value")
    private Double metricValue;

    /** Kural sonuçları JSON (her kural: id, label, kind, actual, expected, level, passed, note, numeric). */
    @Column(name = "rule_results_json", columnDefinition = "TEXT")
    private String ruleResultsJson;

    /** Süre yavaş sorgu eşiğini aştı mı. */
    @Column
    private Boolean slow;

    /** Elle "Şimdi kontrol et" / tetik (true) — değişim kuralının "önceki çalıştırması" yalnız zamanlanmış satırlardan okunur. */
    @Column
    private Boolean manual;

    /**
     * Teyit / kurtarma yeniden kontrolü (true, 2026-10-31 inceleme Y1) — BANT DIŞI satır: değişim kuralının tabanı olmaz
     * (bir sonraki zamanlanmış çalıştırma onu değil, önceki ZAMANLANMIŞ satırı okur). {@code manual} ile birlikte "zamanlanmış"
     * = ikisi de false / null. Geçmişte "Teyit / kurtarma" diye etiketlenir. null = bu kolondan önceki satır (zamanlanmış).
     */
    @Column
    private Boolean recheck;

    /** Koşan sorgunun özeti ({@link DbQueryMonitor#getSqlHash()} o anki değeri). */
    @Column(name = "sql_hash", length = 16)
    private String sqlHash;

    /** Maskeli hata metni (≤ 500) — parola / bağlantı dizesi asla yazılmaz. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Kısa hata kodu (DB_UNREACHABLE, DB_AUTH, DB_TIMEOUT, DB_SQL_ERROR, DB_GUARD_REJECTED, DB_PROFILE_MISSING, …). */
    @Column(name = "failure_reason", length = 48)
    private String failureReason;

    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;
}
