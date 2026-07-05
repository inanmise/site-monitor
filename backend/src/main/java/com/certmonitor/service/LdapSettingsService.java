package com.certmonitor.service;

import com.certmonitor.model.LdapSettings;
import com.certmonitor.repository.LdapSettingsRepository;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and persists the singleton {@link LdapSettings} row, applying changes
 * live (no restart): the in-memory cache is refreshed on every {@link #save}.
 * The bind password is encrypted at rest and never serialized to the client.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapSettingsService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final LdapSettingsRepository repo;
    private final SecretCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile LdapSettings cached;

    @PostConstruct
    void load() {
        this.cached = repo.findById(LdapSettings.SINGLETON_ID).orElse(null);
        if (cached != null) {
            log.info("LDAP settings loaded (enabled={}, host={})", cached.getEnabled(), cached.getHost());
        }
    }

    /** Çok-pod tutarlılığı: başka bir instance kaydettiyse (updated_at farklı) cache'i DB'den tazele.
     *  Aynı pod kaydında no-op. Aralık: cert.monitor.settings.refresh-ms (vars. 10 sn). */
    @Scheduled(fixedDelayString = "${cert.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    void refreshFromDb() {
        try {
            LdapSettings db = repo.findById(LdapSettings.SINGLETON_ID).orElse(null);
            String dbUa  = db     != null ? db.getUpdatedAt()     : null;
            String curUa = cached != null ? cached.getUpdatedAt() : null;
            if (!java.util.Objects.equals(dbUa, curUa)) {
                this.cached = db;
                log.info("LDAP settings cache refreshed from DB (updated by another instance)");
            }
        } catch (Exception e) {
            log.debug("LDAP settings refresh skipped: {}", e.getMessage());
        }
    }

    /** Stored configuration, or sensible form defaults when nothing is configured yet. */
    public LdapSettings getOrDefaults() {
        if (cached != null) return cached;
        // Form defaults (matches the requested initial toggle states).
        LdapSettings d = new LdapSettings();
        d.setEnabled(true);
        d.setUseLdaps(true);
        d.setStartTls(false);
        d.setSkipCertVerification(true);
        d.setPort(636);
        d.setUserSearchFilter("(objectclass=person)");
        d.setUserAttribute("sAMAccountName");
        d.setEmailAttribute("mail");
        d.setDisplayAttribute("displayName");
        d.setGroupFilter("(objectclass=group)");
        d.setSkipMemberOf(false);
        d.setDefaultRole("ADMIN");
        d.setRoleMappingsJson("[]");
        return d;
    }

    /** True once a configuration row has actually been saved. */
    public boolean isConfigured() {
        return cached != null;
    }

    /** Gerçek bir şifreleme anahtarı (CERT_MONITOR_SECRET_KEY) ayarlı mı — UI uyarısı için. */
    public boolean isSecretKeyConfigured() {
        return cipher.isKeyConfigured();
    }

    /** Decrypted service-account password for the current configuration (null if unset). */
    public String decryptedBindPassword() {
        LdapSettings s = getOrDefaults();
        return cipher.decrypt(s.getBindPasswordEnc());
    }

    /** Persists incoming settings and refreshes the live cache. */
    public synchronized LdapSettings save(Map<String, Object> body, String actor) {
        LdapSettings s = (cached != null) ? cached : new LdapSettings();
        s.setId(LdapSettings.SINGLETON_ID);

        s.setEnabled(boolVal(body, "enabled", s.getEnabled()));
        s.setHost(trimToNull(strVal(body, "host", s.getHost())));
        s.setPort(intVal(body, "port", s.getPort() != null ? s.getPort() : 636));
        s.setUseLdaps(boolVal(body, "use_ldaps", s.getUseLdaps()));
        s.setStartTls(boolVal(body, "start_tls", s.getStartTls()));
        s.setSkipCertVerification(boolVal(body, "skip_cert_verification", s.getSkipCertVerification()));
        s.setCaCertPem(strVal(body, "ca_cert_pem", s.getCaCertPem()));
        s.setBindDn(trimToNull(strVal(body, "bind_dn", s.getBindDn())));

        // Bind password is write-only: only update when a non-blank value is sent.
        Object pw = body.get("bind_password");
        if (pw != null && !pw.toString().isBlank()) {
            s.setBindPasswordEnc(cipher.encrypt(pw.toString()));
        }

        s.setBaseDn(trimToNull(strVal(body, "base_dn", s.getBaseDn())));
        s.setUserSearchFilter(strVal(body, "user_search_filter", s.getUserSearchFilter()));
        s.setUserAttribute(strVal(body, "user_attribute", s.getUserAttribute()));
        s.setEmailAttribute(strVal(body, "email_attribute", s.getEmailAttribute()));
        s.setDisplayAttribute(strVal(body, "display_attribute", s.getDisplayAttribute()));
        s.setGroupSearchBase(trimToNull(strVal(body, "group_search_base", s.getGroupSearchBase())));
        s.setGroupFilter(strVal(body, "group_filter", s.getGroupFilter()));
        s.setSkipMemberOf(boolVal(body, "skip_member_of", s.getSkipMemberOf()));
        s.setDefaultRole(strVal(body, "default_role", s.getDefaultRole()));
        s.setRoleMappingsJson(serializeMappings(body.get("role_mappings")));

        s.setUpdatedAt(ISO.format(Instant.now()));
        s.setUpdatedBy(actor);

        this.cached = repo.save(s);
        log.info("LDAP settings saved by {} (enabled={}, host={}:{})",
                actor, s.getEnabled(), s.getHost(), s.getPort());
        return cached;
    }

    /** Client-facing view: omits the encrypted password, exposes a "set" flag and parsed mappings. */
    public Map<String, Object> toClientMap(LdapSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", bool(s.getEnabled()));
        m.put("host", s.getHost());
        m.put("port", s.getPort());
        m.put("use_ldaps", bool(s.getUseLdaps()));
        m.put("start_tls", bool(s.getStartTls()));
        m.put("skip_cert_verification", bool(s.getSkipCertVerification()));
        m.put("ca_cert_pem", s.getCaCertPem());
        m.put("bind_dn", s.getBindDn());
        m.put("bind_password_set", s.getBindPasswordEnc() != null && !s.getBindPasswordEnc().isBlank());
        m.put("base_dn", s.getBaseDn());
        m.put("user_search_filter", s.getUserSearchFilter());
        m.put("user_attribute", s.getUserAttribute());
        m.put("email_attribute", s.getEmailAttribute());
        m.put("display_attribute", s.getDisplayAttribute());
        m.put("group_search_base", s.getGroupSearchBase());
        m.put("group_filter", s.getGroupFilter());
        m.put("skip_member_of", bool(s.getSkipMemberOf()));
        m.put("role_mappings", parseMappings(s.getRoleMappingsJson()));
        m.put("default_role", s.getDefaultRole());
        m.put("updated_at", s.getUpdatedAt());
        m.put("updated_by", s.getUpdatedBy());
        return m;
    }

    public List<Map<String, String>> roleMappings() {
        return parseMappings(getOrDefaults().getRoleMappingsJson());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String serializeMappings(Object raw) {
        if (raw == null) return "[]";
        try {
            // Normalize to a clean list of {group, role} maps.
            List<Map<String, String>> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> mm) {
                        String g = str(mm.get("group"));
                        String r = str(mm.get("role"));
                        if (g != null && !g.isBlank() && r != null && !r.isBlank()) {
                            Map<String, String> entry = new LinkedHashMap<>();
                            entry.put("group", g.trim());
                            entry.put("role", r.trim());
                            out.add(entry);
                        }
                    }
                }
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("Failed to serialize role mappings: {}", e.getMessage());
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseMappings(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse role mappings JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static boolean bool(Boolean b) { return Boolean.TRUE.equals(b); }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String strVal(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return v != null ? v.toString() : fallback;
    }

    private static boolean boolVal(Map<String, Object> body, String key, Boolean fallback) {
        Object v = body.get(key);
        if (v == null) return Boolean.TRUE.equals(fallback);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Integer intVal(Map<String, Object> body, String key, Integer fallback) {
        Object v = body.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return fallback; }
    }
}
