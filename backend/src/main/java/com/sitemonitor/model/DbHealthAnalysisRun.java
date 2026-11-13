package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * İstek üzerine koşan veritabanı sağlık analizinin sonucu (2026-11-13, Sistem Sağlığı → Veritabanı büyümesi →
 * "Analizi çalıştır"). Koşu başına TEK satır: kim, ne zaman, ne kadar sürdü, hangi pod, durum ve sonuç JSON'u (bulgular,
 * bölümler — SQL metni / satır verisi YOK, yalnız tablo / dizin adları ve sayılar). Servis her koşudan sonra kendisi budar:
 * en yeni 50 koşu ve 180 günden yeni olanlar kalır (RetentionCatalog EXTERNAL). Kişisel veri: yalnız koşturan kullanıcı adı.
 *
 * <p>Satırlar JDBC ile yazılır / okunur; entity yalnız tabloyu {@code ddl-auto} ile kurar.
 */
@Entity
@Table(name = "db_health_analysis_runs", indexes = @Index(name = "idx_dhar_started", columnList = "started_at"))
@Getter @Setter @NoArgsConstructor
public class DbHealthAnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UTC ISO başlangıç. */
    @Column(name = "started_at", nullable = false, length = 30)
    private String startedAt;

    @Column(name = "finished_at", length = 30)
    private String finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Koşturan kullanıcı adı. */
    @Column(name = "actor", length = 120)
    private String actor;

    /** Koşan pod adı. */
    @Column(name = "pod", length = 120)
    private String pod;

    /** {@code OK} (bütün bölümler okundu) | {@code PARTIAL} (bir bölüm okunamadı / süre doldu) | {@code UNSUPPORTED}. */
    @Column(name = "status", length = 16)
    private String status;

    /** Sonuç (JSON). */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
}
