package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapDirectoryService;
import com.sitemonitor.service.LdapProvisioningService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AD'den kullanıcı arama / görüntüleme / çekme (2026-11-11, ürün isteği).
 *
 * <p>Yönetici, "Kullanıcı Ekle → AD'den ara" ile bir AD kullanıcısını GİRİŞ YAPMADAN sisteme çeker:
 * {@link LdapDirectoryService#searchUsers kısmi arama} → {@link #detail detay/önizleme} → yöneticinin
 * seçtiği rol/takımla {@link UserService#provisionAdUser provision}. Yalnız GLOBAL admin'e açık (LDAP
 * işlemleri uygulama geneli); mutasyon denetlenir ({@code USER_PROVISION_FROM_AD}).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ad-users")
@RequiredArgsConstructor
public class AdUserController {

    private final LdapDirectoryService directoryService;
    private final LdapSettingsService ldapSettings;
    private final UserService userService;
    private final UsernamePrefixPolicy prefixPolicy;
    private final AuditService auditService;

    /** Yalnız global admin; kapsamlı müdür / USER geçemez. */
    private void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Bu işlem yalnız global yöneticilere açıktır.",
                    "This action is available to global administrators only."));
        }
    }

    private void requireLdapConfigured() {
        if (!ldapSettings.isConfigured()) {
            throw new IllegalStateException(Msg.t(
                    "LDAP / Active Directory yapılandırılmamış — önce Ayarlar → LDAP'tan bağlantıyı tanımlayın.",
                    "LDAP / Active Directory is not configured — set it up under Settings → LDAP first."));
        }
    }

    // ── Arama ──────────────────────────────────────────────────────────────
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        requireGlobalAdmin(session);
        requireLdapConfigured();
        String query = str(body.get("query"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(Msg.t("Arama değeri boş olamaz", "Search value cannot be blank"));
        }
        int limit = intOr(body.get("limit"), 25);
        List<Map<String, Object>> raw = directoryService.searchUsers(query, limit);
        List<Map<String, Object>> results = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            String username = str(r.get("username"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dn", r.get("dn"));
            m.put("username", username);
            m.put("display_name", r.get("displayName"));
            m.put("email", r.get("email"));
            m.put("title", r.get("title"));
            m.put("department", r.get("department"));
            annotateLocal(m, username);
            results.add(m);
        }
        return ok(Map.of("results", results, "count", results.size()));
    }

    // ── Detay / önizleme ───────────────────────────────────────────────────
    @PostMapping("/detail")
    public ResponseEntity<Map<String, Object>> detail(@RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        requireGlobalAdmin(session);
        requireLdapConfigured();
        String username = str(body.get("username"));
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(Msg.t("Kullanıcı adı boş olamaz", "Username cannot be blank"));
        }
        Optional<Map<String, Object>> found = directoryService.findUser(username);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("AD_USER_NOT_FOUND",
                    Msg.t("AD'de bu kullanıcı bulunamadı (ya da birden çok eşleşti).",
                          "That user was not found in AD (or matched more than one).")));
        }
        Map<String, Object> attrs = found.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", adStr(attrs, "sAMAccountName", username));
        out.put("dn", attrs.get("_dn"));
        out.put("display_name", adStr(attrs, "displayName", null));
        out.put("email", adStr(attrs, "mail", null));
        out.put("employee_id", adStr(attrs, "cn", null));       // Sicil No
        out.put("first_name", adStr(attrs, "givenName", null));
        out.put("last_name", adStr(attrs, "sn", null));
        out.put("title", adStr(attrs, "title", null));
        out.put("department", adStr(attrs, "department", null));
        out.put("manager", adStr(attrs, "manager", null));
        // Türetilen takım ÖNİZLEMESİ (AD gruplarından; öneri — yönetici gerçek takımı seçer)
        List<String> derivedTeams = new ArrayList<>();
        try {
            for (LdapProvisioningService.DerivedTeam dt : LdapProvisioningService.deriveTeams(attrs)) {
                if (dt.name() != null && !dt.name().isBlank()) derivedTeams.add(dt.name());
            }
        } catch (RuntimeException ignored) { /* önizleme — hata eklemeyi engellemez */ }
        out.put("derived_teams", derivedTeams);
        annotateLocal(out, (String) out.get("username"));
        return ok(Map.of("data", out));
    }

    // ── Provision (sisteme çek) ────────────────────────────────────────────
    @PostMapping("/provision")
    public ResponseEntity<Map<String, Object>> provision(@RequestBody Map<String, Object> body,
                                                         HttpSession session, HttpServletRequest request) {
        requireGlobalAdmin(session);
        requireLdapConfigured();
        String username = str(body.get("username"));
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(Msg.t("Kullanıcı adı boş olamaz", "Username cannot be blank"));
        }
        // Zaten yerelde var mı → 409 + mevcut kayıt (çift ekleme engellenir)
        Optional<AppUser> existing = userService.findByUsername(UserService.normalizeUsername(username));
        if (existing.isPresent()) {
            AppUser e = existing.get();
            Map<String, Object> resp = err("USER_EXISTS", Msg.t(
                    "Bu kullanıcı zaten sistemde — düzenleme ekranından yönetin.",
                    "This user is already in the system — manage them from the edit screen."));
            resp.put("user", Map.of("id", e.getId(), "username", e.getUsername(),
                    "display_name", e.getDisplayName() == null ? "" : e.getDisplayName()));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        // AD'den TAZE öznitelikleri çek (provision bunları profil alanlarına yazar)
        Optional<Map<String, Object>> found = directoryService.findUser(username);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("AD_USER_NOT_FOUND",
                    Msg.t("AD'de bu kullanıcı bulunamadı (ya da birden çok eşleşti).",
                          "That user was not found in AD (or matched more than one).")));
        }
        Map<String, Object> attrs = found.get();
        String canonical = adStr(attrs, "sAMAccountName", username);   // AD kanonik adı
        String systemRole = str(body.get("system_role"));
        String orgRole = str(body.get("org_role"));
        Collection<Long> teamIds = longList(body.get("team_ids"));
        Long primaryTeamId = longOrNull(body.get("primary_team_id"));

        AppUser user = userService.provisionAdUser(canonical, attrs, systemRole, orgRole, teamIds, primaryTeamId);

        boolean prefixBlocked = prefixPolicy.active() && !prefixPolicy.allows(user.getUsername());
        auditService.recordAction("USER_PROVISION_FROM_AD", session, request, "USER", user.getUsername(),
                "{\"role\":\"" + user.getSystemRole() + "\",\"teamId\":" + user.getTeamId()
                        + ",\"source\":\"ad_search\"}");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("user", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "display_name", user.getDisplayName() == null ? "" : user.getDisplayName(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "system_role", user.getSystemRole()));
        resp.put("prefix_blocked", prefixBlocked);
        return ok(resp);
    }

    // ── yardımcılar ────────────────────────────────────────────────────────
    private void annotateLocal(Map<String, Object> m, String username) {
        if (username == null || username.isBlank()) {
            m.put("exists_locally", false);
            m.put("prefix_blocked", false);
            return;
        }
        Optional<AppUser> local = userService.findByUsername(UserService.normalizeUsername(username));
        m.put("exists_locally", local.isPresent());
        local.ifPresent(u -> m.put("local_user_id", u.getId()));
        m.put("prefix_blocked", prefixPolicy.active() && !prefixPolicy.allows(username));
    }

    private static String adStr(Map<String, Object> attrs, String key, String fallback) {
        Object v = attrs == null ? null : attrs.get(key);
        if (v instanceof List<?> list) v = list.isEmpty() ? null : list.get(0);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o).trim(); }

    private static int intOr(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? dflt : Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static Long longOrNull(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try { String s = String.valueOf(o).trim(); return s.isEmpty() ? null : Long.parseLong(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static List<Long> longList(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object e : c) { Long v = longOrNull(e); if (v != null) out.add(v); }
        }
        return out;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        return ResponseEntity.ok(r);
    }

    private Map<String, Object> err(String code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("code", code);
        r.put("error", message);
        return r;
    }
}
