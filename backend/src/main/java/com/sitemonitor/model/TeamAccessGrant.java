package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takım erişim paylaşımı (2026-10-12, kullanıcı isteği: "bir ekip kendi izlemelerini başka bir takımın ya da kişinin
 * görebilmesi için açabilsin; görüntüleme / çalıştırma / düzenleme / silme düzeyleriyle; takım yöneticisi yönetsin").
 *
 * <p>{@code ownerTeamId} takımının izleme kayıtları (sertifikalar, envanter, dokuz izleme türü, alarmlar, olaylar, bakım,
 * notlar …) {@code granteeType} = TEAM ise o takımın AKTİF üyelerine, USER ise o kişiye {@code level} düzeyinde açılır.
 * Düzeyler KÜMÜLATİFTİR: VIEW &lt; EXECUTE &lt; EDIT &lt; DELETE. Yönetim yetkileri (kullanıcı/takım, bakım penceresi, nöbet,
 * bildirim grubu) hiçbir düzeyde verilmez; paylaşım geçişli değildir; bildirimler değişmez.
 *
 * <p>Tekillik {@code grantee_key} ("T:&lt;teamId&gt;" | "U:&lt;userId&gt;") ile: iki boş olabilen sütunda PostgreSQL NULL'ları
 * farklı saydığından UNIQUE kısıt mükerrer paylaşımı engellemezdi.
 */
@Entity
@Table(name = "team_access_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_access_grant", columnNames = {"owner_team_id", "grantee_key"}),
        indexes = {
                @Index(name = "idx_team_access_owner", columnList = "owner_team_id"),
                @Index(name = "idx_team_access_grantee_team", columnList = "grantee_team_id"),
                @Index(name = "idx_team_access_grantee_user", columnList = "grantee_user_id")
        })
@Data
@NoArgsConstructor
// Yalnız değişen sütunlar yazılır (2026-10-12): iki yöneticinin farklı alanları eşzamanlı düzenlemesi birbirini ezmez.
@org.hibernate.annotations.DynamicUpdate
public class TeamAccessGrant {

    public static final String TYPE_TEAM = "TEAM";
    public static final String TYPE_USER = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_team_id", nullable = false)
    private Long ownerTeamId;

    /** TEAM | USER. */
    @Column(name = "grantee_type", length = 10, nullable = false)
    private String granteeType;

    @Column(name = "grantee_team_id")
    private Long granteeTeamId;

    @Column(name = "grantee_user_id")
    private Long granteeUserId;

    /** "T:&lt;id&gt;" | "U:&lt;id&gt;" — tekillik anahtarı (sınıf açıklaması). */
    @Column(name = "grantee_key", length = 40, nullable = false)
    private String granteeKey;

    /** VIEW | EXECUTE | EDIT | DELETE ({@code TeamAccessLevel}). */
    @Column(name = "access_level", length = 10, nullable = false)
    private String level;

    /** UTC ISO (yyyy-MM-ddTHH:mm:ss); null = süresiz. Bu andan sonra paylaşım uygulanmaz. */
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

    public static String keyOf(String type, Long teamId, Long userId) {
        return TYPE_TEAM.equals(type) ? "T:" + teamId : "U:" + userId;
    }
}
