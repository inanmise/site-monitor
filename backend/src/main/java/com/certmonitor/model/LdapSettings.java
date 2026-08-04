package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Singleton (id=1) row holding the runtime-editable LDAP / Active Directory
 * configuration. Edited from the admin-only Settings page and applied live
 * (no restart) — {@code LdapSettingsService} reloads its cache on every save.
 *
 * <p>The bind password is stored AES-GCM encrypted in {@code bindPasswordEnc}
 * and is never returned to the frontend.
 */
@Entity
@Table(name = "ldap_settings")
@Data
@NoArgsConstructor
public class LdapSettings {

    /** Fixed singleton id — there is only ever one configuration row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    // ── Master switch ───────────────────────────────────────────────────────
    @Column(nullable = false)
    private Boolean enabled = false;

    // ── Connection ──────────────────────────────────────────────────────────
    @Column(length = 255)
    private String host;

    private Integer port = 636;

    @Column(name = "use_ldaps", nullable = false)
    private Boolean useLdaps = true;

    @Column(name = "start_tls", nullable = false)
    private Boolean startTls = false;

    /** Varsayılan KAPALI (2026-08-05): açıkken zincir + hostname hiç doğrulanmaz ve yüklü CA PEM
     *  kullanılmaz → bind/kullanıcı parolaları MITM'e açık. Mevcut kayıtlar bu değişiklikten etkilenmez. */
    @Column(name = "skip_cert_verification", nullable = false)
    private Boolean skipCertVerification = false;

    /** Optional internal-CA PEM bundle (used when not skipping cert verification). */
    @Column(name = "ca_cert_pem", columnDefinition = "TEXT")
    private String caCertPem;

    @Column(name = "bind_dn", length = 512)
    private String bindDn;

    /** AES-GCM encrypted service-account password. Never serialized to the client. */
    @ToString.Exclude   // şifreli de olsa bind parolası toString/log'a sızmasın
    @Column(name = "bind_password_enc", columnDefinition = "TEXT")
    private String bindPasswordEnc;

    // ── User search ─────────────────────────────────────────────────────────
    @Column(name = "base_dn", length = 512)
    private String baseDn;

    @Column(name = "user_search_filter", length = 512)
    private String userSearchFilter = "(objectclass=person)";

    @Column(name = "user_attribute", length = 100)
    private String userAttribute = "sAMAccountName";

    @Column(name = "email_attribute", length = 100)
    private String emailAttribute = "mail";

    @Column(name = "display_attribute", length = 100)
    private String displayAttribute = "displayName";

    // ── Group lookup (optional) ───────────────────────────────────────────────
    @Column(name = "group_search_base", length = 512)
    private String groupSearchBase;

    @Column(name = "group_filter", length = 512)
    private String groupFilter = "(objectclass=group)";

    /** Drop memberOf from the user search (AD MaxValRange / 1MB workaround). */
    @Column(name = "skip_member_of", nullable = false)
    private Boolean skipMemberOf = false;

    // ── Group → role mapping ──────────────────────────────────────────────────
    /** JSON array of {"group":"<dn-or-cn-substring>","role":"<role>"} entries. */
    @Column(name = "role_mappings_json", columnDefinition = "TEXT")
    private String roleMappingsJson = "[]";

    /** Role applied when no group mapping matches (AppUser.systemRole token). */
    @Column(name = "default_role", length = 50)
    private String defaultRole = "ADMIN";

    // ── Audit ─────────────────────────────────────────────────────────────────
    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
