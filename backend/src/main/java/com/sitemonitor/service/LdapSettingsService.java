package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.repository.LdapSettingsRepository;
import com.sitemonitor.service.ldap.LdapRoleMapping;
import com.sitemonitor.util.Msg;
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
     *  Aynı pod kaydında no-op. Aralık: site.monitor.settings.refresh-ms (vars. 10 sn). */
    // synchronized (inceleme 4, 2026-10-12): kayıtla aynı kilit — tazeleme kayıttan ÖNCE okuduğu eski satırı kayıttan
    // SONRA önbelleğe yazıp bu pod'un az önceki kaydını bir tur (≤ 10 sn) geri alamasın.
    @Scheduled(fixedDelayString = "${site.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    synchronized void refreshFromDb() {
        try {
            LdapSettings db = repo.findById(LdapSettings.SINGLETON_ID).orElse(null);
            String dbUa  = db     != null ? db.getUpdatedAt()     : null;
            String curUa = cached != null ? cached.getUpdatedAt() : null;
            // Satır her turda alınır (2026-10-12): updated_at saniye çözünürlüklü — iki pod aynı saniyede kaydederse damga
            // eşit kalıp önbellek bir sonraki kayda dek bayat kalıyordu. Sorgu zaten her turda koşuyor; ek maliyet yok.
            this.cached = db;
            if (!java.util.Objects.equals(dbUa, curUa)) {
                log.info("LDAP settings cache refreshed from DB (updated by another instance)");
            }
        } catch (Exception e) {
            log.debug("LDAP settings refresh skipped: {}", e.getMessage());
        }
    }

    /** Stored configuration, or sensible form defaults when nothing is configured yet. */
    public LdapSettings getOrDefaults() {
        if (cached != null) return cached;
        return defaults();
    }

    /** Form varsayılanları (istenen ilk anahtar durumları) — ilk kayıtta gövdede olmayan alanlar bunlardan gelir. */
    static LdapSettings defaults() {
        LdapSettings d = new LdapSettings();
        d.setEnabled(true);
        d.setUseLdaps(true);
        d.setStartTls(false);
        d.setSkipCertVerification(false);   // güvenli varsayılan: doğrulama AÇIK (bkz. LdapSettings)
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

    /** Gerçek bir şifreleme anahtarı (SITE_MONITOR_SECRET_KEY) ayarlı mı — UI uyarısı için. */
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
        // Kayıt DB'deki GÜNCEL satır üzerine uygulanır (2026-10-12): pod önbelleği başka pod'un son kaydından ≤ 10 sn geri
        // olabilir; gövdede olmayan alanlar önbellekten alınınca o kayıt (ör. rol eşlemesi) sessizce geri alınıyordu.
        // Önbellek nesnesi de yerinde DEĞİŞTİRİLMEZ (kayıt düşerse canlı ayar yarım kalmasın). İlk kayıtta varsayılanlar.
        LdapSettings s = repo.findById(LdapSettings.SINGLETON_ID).orElseGet(LdapSettingsService::defaults);
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
        // Eşlemeler yalnız gövdede VARSA yazılır (2026-10-11): eskiden anahtar yoksa "[]" yazılıp kayıtlı kurallar sessizce
        // siliniyordu. Doğrulama kayıttan ÖNCE: geçersiz rol / aşırı uzun grup / çok kural → 400 (alan hatası).
        if (body.containsKey("role_mappings")) s.setRoleMappingsJson(serializeMappings(validateMappings(body.get("role_mappings"))));
        s.setRoleMappingEnabled(boolVal(body, "role_mapping_enabled", Boolean.TRUE.equals(s.getRoleMappingEnabled())));
        if (body.containsKey("role_mapping_fallback")) s.setRoleMappingFallback(validateFallback(body.get("role_mapping_fallback")));

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
        m.put("role_mapping_enabled", Boolean.TRUE.equals(s.getRoleMappingEnabled()));
        m.put("role_mapping_fallback", LdapRoleMapping.normalizeFallback(s.getRoleMappingFallback()));
        m.put("updated_at", s.getUpdatedAt());
        m.put("updated_by", s.getUpdatedBy());
        return m;
    }

    public List<Map<String, String>> roleMappings() {
        return parseMappings(getOrDefaults().getRoleMappingsJson());
    }

    /** Giriş / eşitlemede uygulanacak eşleme yapılandırması; eşleme KAPALIYSA null (bugünkü davranış aynen). */
    public RoleMappingConfig activeRoleMapping() {
        LdapSettings s = getOrDefaults();
        if (!Boolean.TRUE.equals(s.getRoleMappingEnabled())) return null;
        return new RoleMappingConfig(LdapRoleMapping.rulesOf(parseMappings(s.getRoleMappingsJson())),
                LdapRoleMapping.normalizeFallback(s.getRoleMappingFallback()));
    }

    /** Uygulanacak kurallar (sıralı) + eşleşme yoksa davranış ("DERIVED" ya da sabit rol). */
    public record RoleMappingConfig(List<LdapRoleMapping.Rule> rules, String fallback) {}

    /** Kural listesini doğrular: en çok {@value LdapRoleMapping#MAX_RULES} satır, grup ≤ 512 karakter, rol katalogda. */
    public static Object validateMappings(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new FieldValidationException("role_mappings", Msg.t("Rol eşlemeleri bir liste olmalı.", "Role mappings must be a list."));
        }
        int rows = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mm)) continue;
            String g = str(mm.get("group"));
            String r = str(mm.get("role"));
            if (g == null || g.isBlank()) continue;   // boş satır: sessizce atlanır (eskisi gibi)
            if (++rows > LdapRoleMapping.MAX_RULES) {
                throw new FieldValidationException("role_mappings", Msg.t(
                        "En çok " + LdapRoleMapping.MAX_RULES + " rol eşlemesi tanımlanabilir · fazla satırları silip yeniden kaydedin.",
                        "At most " + LdapRoleMapping.MAX_RULES + " role mappings are allowed · remove the extra rows and save again."));
            }
            if (g.trim().length() > LdapRoleMapping.MAX_GROUP_LENGTH) {
                throw new FieldValidationException("role_mappings", Msg.t(
                        "Grup değeri en çok " + LdapRoleMapping.MAX_GROUP_LENGTH + " karakter olabilir · grubun adını (CN) ya da tam DN'ini yazın.",
                        "A group value can be at most " + LdapRoleMapping.MAX_GROUP_LENGTH + " characters · enter the group name (CN) or its full DN."));
            }
            if (!LdapRoleMapping.isRole(r)) {
                throw new FieldValidationException("role_mappings", Msg.t(
                        "\"" + g.trim() + "\" satırının rolü geçersiz · ADMIN, TEAM_ADMIN, AUDIT ya da USER seçin.",
                        "The role on row \"" + g.trim() + "\" is not valid · choose ADMIN, TEAM_ADMIN, AUDIT or USER."));
            }
        }
        return list;
    }

    /** "DERIVED" (bugünkü kural) ya da katalogdaki bir rol; boş → DERIVED; başka değer → 400. */
    public static String validateFallback(Object raw) {
        String v = str(raw);
        if (v == null || v.isBlank()) return LdapRoleMapping.FALLBACK_DERIVED;
        String up = v.trim().toUpperCase(java.util.Locale.ROOT);
        if (LdapRoleMapping.FALLBACK_DERIVED.equals(up) || LdapRoleMapping.isRole(up)) return up;
        throw new FieldValidationException("role_mapping_fallback", Msg.t(
                "Eşleşme yoksa uygulanacak davranış geçersiz · \"bugünkü kural\" ya da bir rol seçin.",
                "The no-match behaviour is not valid · choose \"current rule\" or a role."));
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
