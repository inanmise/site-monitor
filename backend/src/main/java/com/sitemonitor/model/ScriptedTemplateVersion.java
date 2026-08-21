package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir şablonun sürüm geçmişi — append-only (asla güncellenmez/silinmez).
 *
 * <p>Desen {@link ScriptedScriptVersion} ile BİREBİR aynı: sıra numarası + olay türü + TAM
 * snapshot (diff değil — geri yükleme diff zincirini yeniden kurmak zorunda kalmasın).
 *
 * <p><b>İki fark var ve ikisi de bilinçli:</b>
 * <ul>
 *   <li>{@link #teamId} — o ANDAKİ kapsam. PROMOTE/DEMOTE satırı böylece kendi kendini anlatır,
 *       zaman çizelgesini çizmek için şablon tablosuna join gerekmez.</li>
 *   <li>Olay sözlüğü daha geniş: monitörde CREATE/EDIT/RESTORE yeterliydi, şablonda yaşam döngüsü
 *       olayları da (genele açma, silme, geri alma) sürüm satırı üretir. Amaç "kim ne zaman ne
 *       yaptı" sorusunun TEK bir zaman çizelgesinden okunması — iki ayrı kaynağı birleştirmek
 *       zorunda kalan kullanıcı, sonunda ikisine de bakmaz.</li>
 * </ul>
 */
@Entity
@Table(
    name = "scripted_template_versions",
    indexes = {
        @Index(name = "idx_stv_template_seq", columnList = "templateId,sequenceNo"),
        @Index(name = "idx_stv_created",      columnList = "createdAt")
    }
)
@Data
@NoArgsConstructor
public class ScriptedTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    /** 0 = ilk kayıt, sonra 1, 2, … — sıralama ve "bir sonraki numara" için. */
    @Column(nullable = false)
    private Integer sequenceNo;

    /** Kullanıcıya gösterilen sürüm etiketi: {@code 1.0.0}, {@code 1.0.1}, {@code 2.0.0}. */
    @Column(nullable = false, length = 20)
    private String version;

    /** SEED / CREATE / EDIT / RESTORE / PROMOTE / DEMOTE / DELETE / UNDELETE */
    @Column(nullable = false, length = 16)
    private String eventType;

    /** O sürümdeki script gövdesinin TAM kopyası. */
    @Column(columnDefinition = "TEXT")
    private String script;

    /** O sürümdeki env TANIMLARI (şablon değer taşımaz — burada da taşımaz). */
    @Column(columnDefinition = "TEXT")
    private String envJson;

    /** O andaki kapsam: null = Genel, dolu = takım. PROMOTE/DEMOTE satırını okunur kılar. */
    private Long teamId;

    /** Serbest not: "1.0.2 sürümünden geri yüklendi", "DijitalSY takımından genele açıldı" gibi. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    private String createdAt;

    @Column(nullable = false)
    private String createdBy;

    private String createdByName;
}
