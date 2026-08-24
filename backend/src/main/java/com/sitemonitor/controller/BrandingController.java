package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Branding (beyaz etiket): login sayfası + uygulama kimliği kurum-özel yapılır (logo/renk/metinler)
 * ve tüm kullanıcılara duyuru şeridi yönetilir. GeneralSettingsController deseni: küratörlü ayarlar
 * (AppSettingsCatalog "branding" grubu) CANLI kaydedilir (restart yok), audit'lenir (BRANDING_SAVE).
 *
 * <p><b>Public endpoint:</b> {@code GET /api/branding} auth GEREKTİRMEZ — login sayfası auth öncesi
 * logo/başlık/renk okur (AuthInterceptor.PUBLIC + WebConfig'de 60 sn cache). Yalnız branding
 * değerleri döner; hassas hiçbir ayar sızmaz.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BrandingController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String PREFIX = "site.monitor.branding.";
    private static final String LOGO_KEY = PREFIX + "logo-data";
    private static final String BANNER_TEXT_KEY = PREFIX + "banner-text";
    private static final String BANNER_VERSION_KEY = PREFIX + "banner-version";
    private static final String BANNER_ENABLED_KEY = PREFIX + "banner-enabled";
    private static final String BANNER_TONE_KEY = PREFIX + "banner-tone";

    private static final int LOGO_MAX_BYTES = 200 * 1024;
    private static final Pattern LOGO_DATA_URL =
            Pattern.compile("^data:image/(png|jpeg|svg\\+xml);base64,([A-Za-z0-9+/=\\s]+)$");
    /** SVG içinde aktif içerik: script etiketi, on* event attribute'ları, javascript: URL'leri. */
    private static final Pattern SVG_ACTIVE_CONTENT =
            Pattern.compile("(?i)<script|\\bon\\w+\\s*=|javascript:");

    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final org.springframework.core.env.Environment environment;

    // ── Admin: ayar sayfası ───────────────────────────────────────────────────

    @GetMapping("/api/admin/branding/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session, "settings.branding", "edit");
        return ok(Map.of("data", brandingCatalog()));
    }

    @PutMapping("/api/admin/branding/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.branding", "edit");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();

        validateLogo(values.get(LOGO_KEY));

        // Duyuru versiyonu: kullanıcı şeridi X ile kapattığında kapattığı VERSİYON saklanır; şerit
        // ancak versiyon artınca yeniden görünür. Bu yüzden versiyon, admin'in "bunu yeniden göster"
        // anlamına gelen HER eyleminde artmalı:
        //   · metin değişti  · şerit KAPALI→AÇIK yapıldı  · ton değişti
        // Eskiden yalnız metin sayılıyordu; şeridi kapatmış kullanıcılar admin şeridi yeniden
        // etkinleştirdiğinde onu HİÇ göremiyordu (metin değişene kadar kalıcı olarak gizli).
        if (bannerShouldResurface(values)) {
            int version = settingsService.getInt(BANNER_VERSION_KEY, 0);
            values = new LinkedHashMap<>(values);
            values.put(BANNER_VERSION_KEY, String.valueOf(version + 1));
        }

        settingsService.save(Map.of("values", values), actor(session));
        auditService.recordAction("BRANDING_SAVE", session, request,
                "SETTINGS", "branding", "{\"keys\":" + values.size() + "}");
        return ok(Map.of(
                "data", brandingCatalog(),
                "message", "Ayarlar kaydedildi (yeniden başlatma gerekmez)"));
    }

    // ── Public: login/app kabuğu (auth YOK — AuthInterceptor.PUBLIC) ──────────

    @GetMapping("/api/branding")
    public ResponseEntity<Map<String, Object>> publicBranding() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("app_name",          settingsService.getString(PREFIX + "app-name", "SiteMonitor"));
        b.put("tab_title",         settingsService.getString(PREFIX + "tab-title", "SiteMonitor"));
        b.put("login_title",       settingsService.getString(PREFIX + "login-title", ""));
        b.put("login_subtitle",    settingsService.getString(PREFIX + "login-subtitle", ""));
        b.put("signin_label",      settingsService.getString(PREFIX + "signin-label", ""));
        b.put("username_label",    settingsService.getString(PREFIX + "username-label", ""));
        b.put("footer_text",       settingsService.getString(PREFIX + "footer-text", ""));
        b.put("primary_color",     settingsService.getString(PREFIX + "primary-color", ""));
        b.put("logo_data",         settingsService.getString(LOGO_KEY, ""));
        b.put("banner_enabled",    settingsService.getBoolean(PREFIX + "banner-enabled", false));
        b.put("banner_text",       settingsService.getString(BANNER_TEXT_KEY, ""));
        b.put("banner_link",       settingsService.getString(PREFIX + "banner-link", ""));
        b.put("banner_link_label", settingsService.getString(PREFIX + "banner-link-label", ""));
        b.put("banner_tone",       settingsService.getString(PREFIX + "banner-tone", "INFO"));
        b.put("banner_version",    settingsService.getInt(BANNER_VERSION_KEY, 0));
        // Uygulama sürümü ÇALIŞMA ANINDA buradan gelir. Arayüz bunu derleme zamanında gömüyordu
        // (Vite `define` → __APP_VERSION__), o değer dev-server BAŞLARKEN bir kez okunuyordu ve
        // sürüm yükseltmesinden sonra sunucu yeniden başlatılmadıkça ekranda BAYAT kalıyordu
        // (kullanıcı v20.26.1 görürken depo v20.29.4'teydi). AppVersion dosyayı her istekte
        // çözdüğü için burada verilen değer her zaman günceldir. Bu uç zaten public ve login
        // sayfasınca çekiliyor → ne yeni uç ne de ek istek gerekti (60 sn cache yeterli).
        b.put("app_version",       com.sitemonitor.service.AppVersion.resolve(environment));
        return ok(Map.of("data", b));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Katalogtan yalnız branding grubunu döner (Genel Ayarlar tüm grupları döner; burada gerek yok). */
    private List<Map<String, Object>> brandingCatalog() {
        return settingsService.getCatalogForClient().stream()
                .filter(row -> "branding".equals(row.get("group")))
                .toList();
    }

    /** Logo data-URL doğrulaması: MIME png/jpeg/svg+xml, decode ≤200KB, SVG'de aktif içerik yok.
     *  Boş/null = logoyu kaldır (override temizleme) — doğrulama atlanır. */
    /** Bu kayıt, kapatmış kullanıcılarda şeridi yeniden gösterecek bir değişiklik içeriyor mu? */
    private boolean bannerShouldResurface(Map<String, Object> values) {
        Object text = values.get(BANNER_TEXT_KEY);
        if (text != null && !String.valueOf(text).equals(settingsService.getString(BANNER_TEXT_KEY, ""))) return true;

        Object tone = values.get(BANNER_TONE_KEY);
        if (tone != null && !String.valueOf(tone).equals(settingsService.getString(BANNER_TONE_KEY, "INFO"))) return true;

        // Yalnız KAPALI→AÇIK geçişi; açıkken tekrar kaydetmek versiyonu şişirmesin.
        Object enabled = values.get(BANNER_ENABLED_KEY);
        return enabled != null
                && Boolean.parseBoolean(String.valueOf(enabled))
                && !settingsService.getBoolean(BANNER_ENABLED_KEY, false);
    }

    private void validateLogo(Object logoVal) {
        if (logoVal == null || String.valueOf(logoVal).isBlank()) return;
        String logo = String.valueOf(logoVal).trim();
        var m = LOGO_DATA_URL.matcher(logo);
        if (!m.matches()) {
            throw new IllegalArgumentException("Logo yalnız data:image/png|jpeg|svg+xml;base64 formatında olabilir");
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Logo base64 içeriği çözümlenemedi");
        }
        if (decoded.length > LOGO_MAX_BYTES) {
            throw new IllegalArgumentException("Logo en fazla 200KB olabilir (mevcut: " + (decoded.length / 1024) + "KB)");
        }
        if ("svg+xml".equalsIgnoreCase(m.group(1))) {
            String svg = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            if (SVG_ACTIVE_CONTENT.matcher(svg).find()) {
                throw new IllegalArgumentException("SVG logo script/event içeremez");
            }
        }
    }

    /** Konfigüre bootstrap admin HER ZAMAN erişir (kilitlenme-güvenli fallback); aksi halde matris izni. */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, key, action);
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
