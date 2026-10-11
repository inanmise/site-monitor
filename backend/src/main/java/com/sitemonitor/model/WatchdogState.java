package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kendi kendini izleme (watchdog, 2026-10-11) — kontrol başına SON durum. Satır sayısı sabit (kontrol kataloğu kadar;
 * {@code WatchdogCheck}); tüm pod'lar aynı durumu görür, dış izleyici ucu ({@code /api/public/health/watchdog}) buradan
 * okur. Geçmiş tutulmaz — her geçiş ve bildirim {@code notification_logs}'ta (tetik {@code WATCHDOG}).
 */
@Entity
@Table(name = "watchdog_state")
@Data
@NoArgsConstructor
public class WatchdogState {

    /** Kontrol anahtarı ({@code WatchdogCheck.key}). */
    @Id
    @Column(name = "check_key", length = 40)
    private String checkKey;

    /** OK | PROBLEM (onaylı durum — tek bir kötü ölçüm PROBLEM yapmaz). */
    @Column(length = 12, nullable = false)
    private String status = "OK";

    /** Son ölçümün sonucu: OK | PROBLEM | UNKNOWN | NA (uygulanmaz). */
    @Column(name = "last_result", length = 12)
    private String lastResult;

    /** Sorun varken etkin önem: CRITICAL | HIGH | MEDIUM. */
    @Column(length = 12)
    private String severity;

    /** Onaylı durumun başlangıcı (UTC ISO). */
    @Column(length = 19)
    private String since;

    /** Ardışık sorunlu / sağlıklı ölçüm sayısı (onay için). */
    @Column(name = "problem_streak")
    private Integer problemStreak = 0;

    @Column(name = "ok_streak")
    private Integer okStreak = 0;

    /** Son ölçümün olguları (JSON — sayılar ve kısa kodlar; ad, adres, hata metni YOK). */
    @Column(columnDefinition = "TEXT")
    private String facts;

    /** Son ölçüm anı (UTC ISO). */
    @Column(name = "evaluated_at", length = 19)
    private String evaluatedAt;

    /** Son bildirim denemesi anı ve türü (ALERT | REMIND | RECOVER, teslim edilemediyse "_FAILED" ekli). */
    @Column(name = "notified_at", length = 19)
    private String notifiedAt;

    @Column(name = "notified_kind", length = 20)
    private String notifiedKind;
}
