package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm fırtınası (alert storm) — çok sayıda monitör kısa bir pencerede birden düştüğünde
 * bireysel alarmları TEK toplu bildirime indirgeyen agregasyon kaydı. Yalnız BİLDİRİMİ gruplar:
 * altındaki incident'ler (AlertEvent) per-monitör kaydedilmeye devam eder (geçmiş/uptime etkilenmez);
 * bağ {@code AlertEvent.stormId} ile kurulur ("storm X'in parçası").
 *
 * Scope: account-wide (scopeKey="ACCOUNT") ya da monitör grubu (scopeKey=grup adı) — ayardaki
 * "Alert storm based on monitor groups" toggle'ı belirler. Aynı scope için AYNI ANDA en fazla bir
 * aktif (resolved=false) storm olabilir; bu, {@code ux_alert_storms_active} kısmi UNIQUE indeksiyle
 * (scope_key WHERE resolved=false) DB seviyesinde garanti edilir → eşzamanlı terfi denemeleri
 * INSERT … ON CONFLICT ile tek kazanana düşer (idempotent, çift alarm yok).
 *
 * Tüm zaman damgaları AlertEvent ile aynı biçimde sabit-genişlik UTC ISO String'tir
 * ("yyyy-MM-dd'T'HH:mm:ss") → sözlüksel (lexicographic) aralık karşılaştırması geçerlidir.
 */
@Entity
@Table(
    name = "alert_storms",
    indexes = {
        @Index(name = "idx_as_resolved", columnList = "resolved"),
        @Index(name = "idx_as_scope",    columnList = "scopeKey,resolved")
    }
)
@Data
@NoArgsConstructor
public class AlertStorm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "ACCOUNT" (account-wide) veya monitör grubu adı (per-group modda). */
    @Column(nullable = false)
    private String scopeKey;

    /** ACCOUNT | GROUP */
    private String scopeType;

    @Column(nullable = false)
    private Boolean resolved = false;

    /** Storm'a bağlanmış (bildirimi bastırılmış) toplam monitör sayısı — bilgilendirme/e-posta için. */
    private Integer memberCount;

    /** Ortak kök-neden: tüm üyeler aynı tipteyse o alertType, karışıksa "MIXED". */
    private String rootCause;

    /** Toplu bildirim gönderilen takım adları (audit/insan-okur). */
    @Column(columnDefinition = "TEXT")
    private String notifiedTeams;

    /** Storm'un başladığı (terfi) an — UTC ISO. */
    private String createdAt;

    /** Storm'un çözüldüğü an — UTC ISO (resolved=true iken dolu). */
    private String resolvedAt;

    /** Son toplu (aggregate) bildirim anı — günlük re-alert kadansı için (aynı-UTC-gün kuralı). */
    private String lastReAlertAt;
}
