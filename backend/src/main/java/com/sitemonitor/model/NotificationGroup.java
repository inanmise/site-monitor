package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Takım Bildirim Grubu — alarm e-postalarının gideceği adlandırılmış alıcı listesi.
 *
 * <p><b>Neden var:</b> bugüne dek alarmlar TEK bir adrese gidiyordu ({@code Team.email}). Aynı
 * takım içinde farklı alarmların farklı kişilere gitmesi gerekiyor — ödeme nöbetçisi ile altyapı
 * nöbetçisi aynı kutuyu okumuyor.
 *
 * <p><b>Çözümleme zinciri</b> (bkz. {@code NotificationGroupService}):
 * monitöre seçili grup → takımın varsayılan grubu → {@code Team.email}.
 * Hiç grup tanımlı değilse davranış BUGÜNKÜNÜN AYNISIDIR — bu geliştirmenin birinci yasası.
 *
 * <p><b>Eskalasyon kişileri AYRI katmandır:</b> grup yalnız takım-maili bileşenini değiştirir;
 * {@code escalation_contacts} (müdür/severity) aynen ÜSTÜNE eklenmeye devam eder.
 */
@Entity
@Table(name = "notification_groups",
       indexes = {
           @Index(name = "idx_ng_team", columnList = "team_id"),
           @Index(name = "idx_ng_team_default", columnList = "team_id,is_default"),
       })
@Getter @Setter @NoArgsConstructor
public class NotificationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sahibi takım — grup DAİMA bir takıma aittir; takımlar arası paylaşım yoktur. */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** Takım içinde benzersiz (uygulama katmanında doğrulanır). */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Normalize edilmiş virgüllü adres listesi. BOŞ grup kaydedilemez — adresi olmayan bir grup
     * seçildiğinde alarm sessizce kimseye gitmezdi.
     */
    @Column(columnDefinition = "TEXT")
    private String emails;

    /**
     * Takımın varsayılan grubu mu — takım başına EN FAZLA BİR tane (uygulama katmanı korur;
     * ikinci "varsayılan yap" öncekini indirir ve ikisi de audit'e yazılır).
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /** Soft-delete: pasif grup çözümlemede YOK SAYILIR, kayıtlı monitör seçimi korunur. */
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;
}
