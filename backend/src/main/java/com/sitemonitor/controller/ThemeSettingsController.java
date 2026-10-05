package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ThemeCatalog;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ayarlar → Görünüm → Temalar (2026-10-05, kullanıcı isteği). Kullanıcı seçicisinde hangi temaların listeleneceği ve
 * seçimi olmayan kullanıcının varsayılan teması ({@link ThemeCatalog}).
 *
 * <ul>
 *   <li>{@code GET /api/admin/themes} — Ayarlar'a giren herkes (bootstrap admin ya da matris izni
 *       {@code settings.branding/edit}; kapsamlı müdür dahil) okur; {@code read_only} global yönetici dışındakiler için
 *       {@code true} (anahtarlar {@code AppSettingsCatalog.GLOBAL_ONLY}).</li>
 *   <li>{@code PUT /api/admin/themes} — {@code {enabled:[...], default:"..."}}; YALNIZ global yönetici (diğerleri 403).
 *       Doğrulama alan adıyla döner (400 + {@code field}: {@code enabled} / {@code default}); kayıt mevcut ayar yolundan
 *       ({@link AppSettingsService#save} — aynı kuralı orada da uygular) geçer, denetim {@code THEME_SETTINGS_SAVE} + alan
 *       farkı.</li>
 * </ul>
 * Kullanıcının seçimi localStorage {@code site-monitor-theme}'da durur ve kişisel tercihlerle hesabına aynalanır
 * ({@code UserPreferencesService.THEME_LOCAL_KEY}, 2026-10-05 ürün kararı) — sonraki oturumlar o temayla açılır; kapatılan
 * tema seçilmişse varsayılan uygulanır ama seçim silinmez. Açık politika {@code GET /api/branding} {@code themes} alanıyla
 * herkese (oturumsuz) gider.
 */
@RestController
@RequiredArgsConstructor
public class ThemeSettingsController {

    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;

    @GetMapping("/api/admin/themes")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        requireSettingsAccess(session);
        return ok(view(session), null);
    }

    @PutMapping("/api/admin/themes")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Tema ayarlarını yalnız global yönetici değiştirebilir.",
                    "Only a global administrator can change the theme settings."));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        List<String> enabled = ThemeCatalog.canonical(enabledList(in.get("enabled")));
        String def = in.get("default") == null ? ThemeCatalog.SYSTEM : String.valueOf(in.get("default")).trim();
        // Bilinmeyen kimlik canonical() ile düşer — onu sessizce yutmamak için ham listeyi de denetle.
        for (String id : enabledList(in.get("enabled"))) {
            if (!ThemeCatalog.isKnown(id)) {
                throw new FieldException("enabled", Msg.t("Bilinmeyen tema: " + id, "Unknown theme: " + id));
            }
        }
        ThemeCatalog.Problem p = ThemeCatalog.validate(enabled, def);
        if (p != null) throw new FieldException(p.field(), Msg.t(p.tr(), p.en()));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ThemeCatalog.KEY_ENABLED, String.join(",", enabled));
        values.put(ThemeCatalog.KEY_DEFAULT, def);
        Map<String, Object> before = new LinkedHashMap<>();
        for (String k : values.keySet()) before.put(k, settingsService.getString(k, null));

        settingsService.save(Map.of("values", values), actor(session));
        auditService.recordAction("THEME_SETTINGS_SAVE", session, request, "SETTINGS", "themes",
                AuditDetail.of("enabled", enabled.size(), "default", def),
                AuditDiff.diff(before, values));
        return ok(view(session), Msg.t("Tema ayarları kaydedildi (yeniden başlatma gerekmez)",
                "Theme settings saved (no restart needed)"));
    }

    /** Alan-bazlı doğrulama hatası → 400 + {@code field}. */
    @ExceptionHandler(FieldException.class)
    public ResponseEntity<Map<String, Object>> fieldError(FieldException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", e.getMessage());
        out.put("field", e.field());
        return ResponseEntity.status(400).body(out);
    }

    /** Alan adlı doğrulama hatası. */
    public static class FieldException extends IllegalArgumentException {
        private final String field;
        public FieldException(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }

    // ── Görünüm ─────────────────────────────────────────────────────────────────

    private Map<String, Object> view(HttpSession session) {
        Map<String, Object> eff = ThemeCatalog.publicView(settingsService);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", eff.get("enabled"));
        data.put("default", eff.get("default"));
        data.put("read_only", !SessionScope.isGlobalAdmin(session));
        List<Map<String, Object>> themes = new ArrayList<>();
        for (ThemeCatalog.Theme t : ThemeCatalog.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("scheme", t.scheme());
            themes.add(m);
        }
        data.put("themes", themes);
        // "Varsayılana dön" — kurulumun (application.properties / env) değerleri; boşsa katalog varsayılanı.
        data.put("defaults", ThemeCatalog.effective(ThemeCatalog.DEFAULT_ENABLED_CSV, ThemeCatalog.SYSTEM));
        return data;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────

    /** {@code ["light","dark"]} ya da {@code "light,dark"} → kırpılmış kimlik listesi; başka biçim → 400 (enabled). */
    private static List<String> enabledList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof String s)) {
                    throw new FieldException("enabled", Msg.t("Tema listesi metin kimliklerinden oluşmalı.",
                            "The theme list must contain text identifiers."));
                }
                if (!s.isBlank() && !out.contains(s.trim())) out.add(s.trim());
            }
            return out;
        }
        if (raw instanceof String s) return ThemeCatalog.parseCsv(s);
        throw new FieldException("enabled", Msg.t("Tema listesi bekleniyor.", "A list of themes is expected."));
    }

    /** Ayarlar sayfasına giriş: bootstrap admin her zaman; aksi halde matris izni (Marka ile aynı anahtar). */
    private void requireSettingsAccess(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, "settings.branding", "edit");
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "anonymous" : u.toString();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        if (message != null) out.put("message", message);
        return ResponseEntity.ok(out);
    }
}
