package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm iletimi (2026-11-05, kullanıcı isteği: "bir takım istediği bir izleme tipi için veya belirli bir izleme için oluşacak
 * alarmların başka bir takıma da gitmesi için başka takımı veya o takımdan belirli üyeleri ek olarak ekleyebilmeli").
 *
 * <p>{@code ownerTeamId} takımının (alarmın SAHİBİ — bağımsız izlemede damgalı takım, envanter kökenlide SY ya da UG)
 * kapsamdaki alarmları, sahip takım ne alıyorsa aynısıyla (açılış, yükselme, günlük hatırlatma, çözülme, elle gönderim; sessiz
 * saat, fırtına, kapalı kanal ve sistem bakımı kararları sahibinkini izler) {@code recipientTeamId} takımına — TEAM: takımın
 * alarm adresi (varsayılan bildirim grubu, yoksa takım e-postası) + push kuralları; MEMBERS: o takımın seçilen AKTİF üyeleri —
 * AYRI bir e-postayla ve push ile iletilir. Yalnız sahip takımı yöneten ekler / değiştirir; alıcı taraf salt okunur görür.
 *
 * <p>Kapsam: ALL (sahip takımın tüm izlemeleri), TYPES ({@code scopeTypes} CSV — {@code MonitorTypeCatalog.ORDER} anahtarları,
 * "cert" = envanter kaydı: sertifika + erişilebilirlik alarmları), MONITORS ({@code scopeMonitors} CSV, öğe
 * "&lt;tür&gt;:&lt;id&gt;" — cert için id envanter kimliği).
 *
 * <p>Adlar KALICIDIR (tablo / sütun). Yazma yalnız {@code AlertForwardService} üzerinden (iyimser kilit {@code version}).
 */
@Entity
@Table(name = "alert_forwards",
        indexes = {
                @Index(name = "idx_alert_fwd_owner", columnList = "owner_team_id"),
                @Index(name = "idx_alert_fwd_recipient", columnList = "recipient_team_id")
        })
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class AlertForward {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_TYPES = "TYPES";
    public static final String SCOPE_MONITORS = "MONITORS";

    public static final String RECIPIENT_TEAM = "TEAM";
    public static final String RECIPIENT_MEMBERS = "MEMBERS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "owner_team_id", nullable = false)
    private Long ownerTeamId;

    /** ALL | TYPES | MONITORS. */
    @Column(name = "scope_kind", length = 16, nullable = false)
    private String scopeKind;

    /** TYPES: tür anahtarları CSV (ör. "http,ping,cert"); diğer kapsamlarda null. */
    @Column(name = "scope_types", length = 200)
    private String scopeTypes;

    /** MONITORS: "&lt;tür&gt;:&lt;id&gt;" CSV (ör. "http:12,cert:40"); diğer kapsamlarda null. */
    @Column(name = "scope_monitors", columnDefinition = "TEXT")
    private String scopeMonitors;

    /** TEAM | MEMBERS. */
    @Column(name = "recipient_kind", length = 10, nullable = false)
    private String recipientKind;

    @Column(name = "recipient_team_id", nullable = false)
    private Long recipientTeamId;

    /** MEMBERS: app_users.id CSV (iç sayısal kimlik — API'de opak kimlik); TEAM'de null. */
    @Column(name = "recipient_user_ids", columnDefinition = "TEXT")
    private String recipientUserIds;

    @Column(nullable = false)
    private boolean enabled = true;

    /** UTC ISO (yyyy-MM-ddTHH:mm:ss) — seçilen İstanbul gününün sonu; null = süresiz. Bu andan sonra iletilmez. */
    @Column(name = "expires_at", length = 19)
    private String expiresAt;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", length = 19)
    private String createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_at", length = 19)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
