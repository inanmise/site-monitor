package com.sitemonitor.model;

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

    /** true → rol admin tarafından manuel ayarlandı; LDAP provisyonu bu kullanıcının systemRole'üne
     *  DOKUNMAZ (aksi halde AD'den türetilen rol her girişte manuel değişikliği ezerdi). Null = AD-yönetimli. */
    @Column(name = "role_locked")
    private Boolean roleLocked;

    /** Organizational role: PO | TECH | MANAGER | CLEVEL | BOLUM_BASKANI | null (regular member) */
    @Column(name = "org_role")
    private String orgRole;

    /** true → org_role admin tarafından manuel ayarlandı; LDAP provisyonu seviyeden türetip bunu EZMEZ
     *  (aksi halde PO/D6/D7/TECH türetmesi her girişte manuel değişikliği ezerdi). Null = AD-yönetimli. */
    @Column(name = "org_role_locked")
    private Boolean orgRoleLocked;

    /** true → takım üyelikleri admin tarafından MANUEL düzenlendi; LDAP provisyonu {@link #teamIds}/{@link #teamId}'ye
     *  DOKUNMAZ (aksi halde AD grup üyeliğinden türetilen takımlar her girişte manuel atamayı ezerdi —
     *  2026-09-18 kullanıcı bildirimi: çok takımlı kullanıcının elle eklenen takımı girişte kayboluyordu).
     *  Kilit kaldırılınca (team-unlock) sonraki girişte AD yeniden yazar. Null = AD-yönetimli. */
    @Column(name = "team_locked")
    private Boolean teamLocked;

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
    // findAll() liste yollarında (UserDirectory, UserActivity) her satır için ayrı koleksiyon
    // fetch'i (N+1) yerine tek IN-sorgusuyla toplu getir.
    @org.hibernate.annotations.BatchSize(size = 100)
    private Set<Long> teamIds = new LinkedHashSet<>();

    @Column(nullable = false)
    private Boolean active = true;

    /** E1: kişi kendi profilinden webhook push'unu kapatabilir. Günlükte SKIPPED_USER_OPT_OUT olarak görünür. */
    @Column(name = "push_opt_out")
    private Boolean pushOptOut = false;

    /** Ürün turu durumu (JSON; bkz. TourStateService) — "bir daha gösterme" cihazdan bağımsız kalıcı. */
    @Column(name = "tour_state", columnDefinition = "TEXT")
    private String tourState;

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

    // ── Giriş damgaları (kullanıcının kendi güvenlik özeti + admin listesi) ──────────────
    //
    // Neden audit_log'dan TÜRETİLMİYOR: /api/me her sayfa açılışında çağrılıyor (tek pod,
    // 100 eşzamanlı kullanıcı), audit_log 180 gün sonra siliniyor ve başarısız girişlerde
    // audit aktörü kullanıcının YAZDIĞI ham metinle kaydediliyor (başarılıda canonical) —
    // yani LOWER()'lı bir sorgu hem idx_audit_actor'ı kullanamaz hem case tutarsızlığına takılır.

    /** EN SON başarılı girişin zamanı (ISO-UTC) = içinde bulunulan oturum. Admin görür. */
    @Column(name = "last_login_at", length = 30)
    private String lastLoginAt;

    /** En son başarılı girişin IP'si. {@code @JsonIgnore}: {@code GET /api/admin/users} HAM entity
     *  döndürüyor — işaretlenmezse IP'ler TEAM_ADMIN'e de açılırdı ({@link #activeSessionId} ile aynı gerekçe). */
    @JsonIgnore
    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    /** Girişin yapılış biçimi: {@code PASSWORD} veya {@code REMEMBER_ME} (sessiz çerez yenilemesi). */
    @Column(name = "last_login_method", length = 16)
    private String lastLoginMethod;

    /** BİR ÖNCEKİ başarılı giriş (ISO-UTC) — kullanıcıya gösterilen değer budur: içinde
     *  bulunduğu oturumun kendi zamanını göstermek "bu ben miydim?" sorusunu cevaplamaz. */
    @Column(name = "prev_login_at", length = 30)
    private String prevLoginAt;

    /** Bir önceki başarılı girişin IP'si (bkz. {@link #lastLoginIp} — aynı gerekçeyle {@code @JsonIgnore}). */
    @JsonIgnore
    @Column(name = "prev_login_ip", length = 64)
    private String prevLoginIp;

    /** Bir önceki girişin yapılış biçimi. */
    @Column(name = "prev_login_method", length = 16)
    private String prevLoginMethod;

    /** En son başarısız giriş denemesinin zamanı (ISO-UTC). Yalnız kullanıcı VARSA yazılır;
     *  bilinmeyen kullanıcı adında güncellenecek satır yoktur (enumeration yüzeyi de açılmaz). */
    @Column(name = "last_failed_login_at", length = 30)
    private String lastFailedLoginAt;

    /** En son başarısız denemenin IP'si (bkz. {@link #lastLoginIp} — aynı gerekçeyle {@code @JsonIgnore}). */
    @JsonIgnore
    @Column(name = "last_failed_login_ip", length = 64)
    private String lastFailedLoginIp;

    /** Başarısızlığın sebebi: {@code BAD_PASSWORD} veya {@code TEMP_PASSWORD_EXPIRED}
     *  ({@code UNKNOWN_USER} buraya HİÇ yazılmaz — güncellenecek kullanıcı satırı yoktur). */
    @Column(name = "last_failed_login_reason", length = 40)
    private String lastFailedLoginReason;

    /** Son başarılı girişten bu yana başarısız deneme sayısı — CANLI sayaç (girişte sıfırlanır).
     *  Admin için "şu anda deneniyor" sinyali. */
    @Column(name = "failed_since_login")
    private Integer failedSinceLogin = 0;

    /** {@link #failedSinceLogin} sayacının son başarılı girişte sıfırlanmadan ÖNCEKİ değeri.
     *  Kullanıcıya gösterilen sayı budur ("önceki girişinizden bu yana N başarısız deneme");
     *  snapshot alınmasaydı kullanıcı giriş yaptığı anda görmesi gereken sayıyı kaybederdi. */
    @Column(name = "failed_before_login")
    private Integer failedBeforeLogin = 0;

    private String createdAt;
    private String updatedAt;
}
