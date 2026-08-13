package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir sentetik monitörün k6 script'inin SÜRÜM geçmişi — append-only (asla güncellenmez/silinmez).
 *
 * <p>Neden ayrı tablo: script'in eski hâlleri bugüne kadar yalnız {@code audit_log.changes} içinde
 * ham JSON diff olarak duruyordu; 365 günlük denetim saklamasından sonra siliniyor, sorgulanabilir
 * bir sürüm listesi ve geri dönüş yolu bulunmuyordu.
 *
 * <p>Desen {@link CertificateNoteRevision} ile aynı: sıra numarası + olay türü + tam snapshot
 * (diff DEĞİL — geri yükleme diff'ten yeniden kurmayı gerektirmesin).
 */
@Entity
@Table(
    name = "scripted_script_versions",
    indexes = {
        @Index(name = "idx_ssv_monitor_seq", columnList = "monitorId,sequenceNo"),
        @Index(name = "idx_ssv_created",     columnList = "createdAt")
    }
)
@Data
@NoArgsConstructor
public class ScriptedScriptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long monitorId;

    /** 0 = ilk kayıt (CREATE), sonra 1, 2, … — sıralama ve "bir sonraki numara" için. */
    @Column(nullable = false)
    private Integer sequenceNo;

    /** Kullanıcıya gösterilen sürüm etiketi: {@code 1.0.0}, {@code 1.0.1}, {@code 2.0.0}. */
    @Column(nullable = false, length = 20)
    private String version;

    /** CREATE / EDIT / RESTORE */
    @Column(nullable = false, length = 16)
    private String eventType;

    /** O sürümdeki k6 script gövdesinin TAM kopyası. */
    @Column(columnDefinition = "TEXT")
    private String script;

    /** O sürümdeki env tanımları (secret DEĞERLER şifreli hâliyle taşınır — düz metin asla). */
    @Column(columnDefinition = "TEXT")
    private String envJson;

    /** Serbest not: "1.0.2 sürümünden geri yüklendi" gibi. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    private String createdAt;

    @Column(nullable = false)
    private String createdBy;

    private String createdByName;
}
