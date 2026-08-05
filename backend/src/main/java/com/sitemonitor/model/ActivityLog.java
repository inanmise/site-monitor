package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Birleşik izleme aktivite kaydı — TÜM izleme türlerinin (sertifika/TLS, uptime, HTTP, port,
 * ping, DNS, keyword, domain) kontrol + yaşam döngüsü olaylarını tek akışta toplar. "Kayıtlar →
 * Aktivite" ekranını besler. Her türün kendi per-check geçmiş tablosu (certificate_checks,
 * http_checks, …) DEĞİŞMEZ; bu tablo ONLARA EK, birleşik/filtrelenebilir bir görünüm sağlar.
 *
 * İzolasyon TAKIM-bazlı: {@code teamId} kaydın izolasyon anahtarıdır ({@link com.sitemonitor.controller.SessionScope}
 * viewTeamIds ile server-side filtrelenir). Kişi-sahiplik alanı YOKTUR. teamId null olan kayıtlar
 * (takıma bağlanamayan aktivite) yalnız global görücüye (admin/AUDIT) görünür.
 *
 * Not: Kullanıcı-EYLEM denetimi için ayrı {@link AuditLog} vardır; bu tablo monitör-KONTROL aktivitesidir.
 */
@Entity
@Table(name = "activity_log", indexes = {
    @Index(name = "idx_act_team_time",  columnList = "team_id, activity_time"),
    @Index(name = "idx_act_time",       columnList = "activity_time"),
    @Index(name = "idx_act_type",       columnList = "monitor_type"),
    @Index(name = "idx_act_type_mon",   columnList = "monitor_type, monitor_id")
})
@Data
@NoArgsConstructor
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO-8601 UTC ("yyyy-MM-dd'T'HH:mm:ss") — diğer geçmiş tablolarıyla tutarlı; UI kullanıcı TZ'sine çevirir. */
    @Column(name = "activity_time", nullable = false, length = 30)
    private String activityTime;

    /** CERT | UPTIME | HTTP | PORT | PING | DNS | KEYWORD | DOMAIN */
    @Column(name = "monitor_type", nullable = false, length = 20)
    private String monitorType;

    /** İlgili monitör kaydının id'si — cert/uptime domain-anahtarlı olduğundan null olabilir. */
    @Column(name = "monitor_id")
    private Long monitorId;

    @Column(name = "monitor_name", length = 300)
    private String monitorName;

    /** "Nerede" — host:port / URL / domain. */
    @Column(name = "target", length = 500)
    private String target;

    /** SCHEDULED_CHECK | MANUAL_CHECK | CREATED | UPDATED | DELETED | PAUSED | RESUMED | ALERT_TRIGGERED | ALERT_RESOLVED */
    @Column(name = "action", nullable = false, length = 30)
    private String action;

    /** SUCCESS | WARNING | ERROR | TIMEOUT | UNKNOWN — renk kodunun temeli. */
    @Column(name = "result_status", length = 20)
    private String resultStatus;

    /** Kısa insan-okur özet: "42 gün kaldı", "200 · 340ms", "açık · 12ms". */
    @Column(name = "result_summary", length = 300)
    private String resultSummary;

    /** Uzun/ham detay (küçük JSON string) — opsiyonel. */
    @Column(name = "result_detail", columnDefinition = "TEXT")
    private String resultDetail;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "error_class", length = 40)
    private String errorClass;

    /** "scheduler" veya tetikleyen kullanıcı adı. */
    @Column(name = "actor", length = 100)
    private String actor;

    /** İZOLASYON anahtarı — kaydın ait olduğu takım. null = takımsız (yalnız global görücü görür). */
    @Column(name = "team_id")
    private Long teamId;

    /** Filtre/gösterim için tipli yardımcı alanlar (nullable). */
    @Column(name = "response_ms")
    private Long responseMs;

    @Column(name = "days_remaining")
    private Integer daysRemaining;
}
