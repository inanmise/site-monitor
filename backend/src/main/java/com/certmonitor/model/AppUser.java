package com.certmonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "app_users",
    indexes = {
        @Index(name = "idx_user_username", columnList = "username"),
        @Index(name = "idx_user_team",     columnList = "team_id")
    })
@Data
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt hash for LOCAL accounts. NULL for LDAP users — they authenticate
     *  against AD, so no password is ever stored for them. */
    @JsonIgnore
    @ToString.Exclude   // parola hash'i toString/log'a ASLA sızmasın
    @Column(name = "password_hash")
    private String passwordHash;

    private String displayName;
    private String email;

    /** Sicil No — from AD `cn`. */
    @Column(name = "employee_id", length = 50)
    private String employeeId;

    // ── AD profile fields (populated on LDAP login) ───────────────────────────
    @Column(name = "first_name", length = 100)
    private String firstName;        // AD givenName

    @Column(name = "last_name", length = 100)
    private String lastName;         // AD sn

    @Column(length = 150)
    private String title;            // AD title

    @Column(length = 50)
    private String phone;            // AD mobile

    @Column(length = 200)
    private String department;       // AD department

    /** Şirket içi seviye — AD `description`. */
    @Column(name = "company_level", length = 200)
    private String companyLevel;

    /** Manager's sicil (cn) from AD extensionAttribute4 / manager. */
    @Column(name = "manager_sicil", length = 50)
    private String managerSicil;

    /** FK to the manager's AppUser row (resolved/provisioned from managerSicil). */
    @Column(name = "manager_id")
    private Long managerId;

    /** Müdürlük (directorate) id + name — AD extensionAttribute5 "ID;Name". */
    @Column(name = "mudurluk_id")
    private Long mudurlukId;

    @Column(name = "mudurluk_name", length = 250)
    private String mudurlukName;

    /** Base64 JPEG from AD thumbnailPhoto. Served via a dedicated photo endpoint,
     *  never inlined in JSON. */
    @JsonIgnore
    @Column(name = "photo_base64", columnDefinition = "TEXT")
    private String photoBase64;

    /** ADMIN — full access to all teams; USER — restricted to own team */
    @Column(nullable = false)
    private String systemRole = "USER";

    /** Organizational role: PO | TECH | MANAGER | CLEVEL | null (regular member) */
    @Column(name = "org_role")
    private String orgRole;

    /** Birincil takım (geriye-uyum + varsayılanlar: denetim actorTeamId, haftalık rapor varsayılanı,
     *  Nav gösterimi, eskalasyon kontağı). Her zaman {@link #teamIds} içindedir. */
    @Column(name = "team_id")
    private Long teamId;

    /** Kullanıcının ÜYE olduğu TÜM takımlar (çoklu takım). Birincil takım da bu kümededir.
     *  Görünürlük/yetki scope'u (computeViewTeamIds/computeManageTeamIds) bu kümeden beslenir.
     *  EAGER: /me ve admin-liste JSON'unda (tx dışı) okunur. Join kolonu pinli (varsayılan
     *  app_user_id değil, user_id) — backfill patch'i bununla aynı isimde olmalı. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_teams", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "team_id")
    private Set<Long> teamIds = new LinkedHashSet<>();

    @Column(nullable = false)
    private Boolean active = true;

    /** Authentication source: "LOCAL" (BCrypt password) or "LDAP" (AD bind).
     *  Null (legacy rows) is treated as LOCAL. LDAP users carry a sentinel
     *  password hash that never matches, so they can only sign in via AD. */
    @Column(name = "auth_source", length = 20)
    private String authSource = "LOCAL";

    /** ISO-UTC timestamp until which this account is temporarily locked. */
    @Column(name = "lockout_until", length = 30)
    private String lockoutUntil;

    /** How many times progressive lockout has been applied (drives escalation). */
    @Column(name = "failed_block_count")
    private Integer failedBlockCount = 0;

    /** Permanent lock set after max escalation — only admin can clear. */
    @Column(name = "permanent_lock")
    private Boolean permanentLock = false;

    /** ISO-UTC timestamp when the last progressive lockout was applied (used to reset the failure-count window). */
    @Column(name = "last_lockout_at", length = 30)
    private String lastLockoutAt;

    /** Set to true by admin auto-reset; cleared on the next successful changePassword.
     *  Frontend renders a non-dismissible password-change modal while this is true. */
    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

    /** ISO-8601 UTC instant when the admin-issued temporary password expires.
     *  null whenever the current password is permanent. Set to now+24h by
     *  adminAutoResetPassword and cleared on the next successful changePassword. */
    @Column(name = "temp_password_expires_at", length = 30)
    private String tempPasswordExpiresAt;

    /** Tek aktif oturum: kullanıcının EN GÜNCEL oturumunun ID'si. Yeni login/remember-me reauth bunu
     *  günceller; AuthInterceptor her istekte karşılaştırır, eşleşmeyen (eski) oturumu kapatır.
     *  Store-agnostik (bellek/jdbc fark etmez). İstemciye sızmasın diye @JsonIgnore. */
    @JsonIgnore
    @ToString.Exclude   // aktif oturum kimliği (token benzeri) toString/log'a sızmasın
    @Column(name = "active_session_id", length = 200)
    private String activeSessionId;

    /** Aktif oturumun son etkinlik (ping) zamanı — ISO-8601 UTC. Frontend ~15 sn'de bir
     *  /api/session/ping çağırır; "aktif kullanıcı" sayımı ve login-onayı bunu tazelik penceresiyle
     *  kontrol eder. Tarayıcı logout'suz kapanınca ping durur → bayatlar → aktif sayılmaz. */
    @JsonIgnore
    @Column(name = "last_seen_at", length = 30)
    private String lastSeenAt;

    private String createdAt;
    private String updatedAt;
}
