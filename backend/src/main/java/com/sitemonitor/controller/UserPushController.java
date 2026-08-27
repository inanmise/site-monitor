package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.util.Csv;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kişi-webhook (push) kanalı yönetimi — YALNIZ global admin.
 *
 * <p>Yüzey: bağlantı ayarları (başlık değerleri ŞİFRELİ saklanır, API'den asla düz dönmez —
 * write-only), katman matrisi ({@code user_push_scopes}), şablonlar (bilinmeyen yer tutucu
 * kaydetmede REDDEDİLİR — sessiz bozulma olmasın), test gönderimi (dakikada 3 tavan) ve
 * teslimat günlüğü (K11 süzgeçleri + CSV).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/user-push")
@RequiredArgsConstructor
public class UserPushController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_]+)}");
    /** Maskeli sır değeri — UI bu sabiti "değişmedi" olarak geri yollar. */
    private static final String MASKED = "*****";

    /** Ayar sayfasının okuduğu/yazdığı anahtarlar (headers ayrı ele alınır — şifreli). */
    private static final List<String> PLAIN_KEYS = List.of(
            "site.monitor.userpush.enabled", "site.monitor.userpush.url",
            "site.monitor.userpush.pipeline", "site.monitor.userpush.title",
            "site.monitor.userpush.timeout-connect-seconds", "site.monitor.userpush.timeout-total-seconds",
            "site.monitor.userpush.retry-max", "site.monitor.userpush.retry-backoff-seconds",
            "site.monitor.userpush.circuit-threshold", "site.monitor.userpush.circuit-cooldown-seconds",
            "site.monitor.userpush.hourly-cap", "site.monitor.userpush.role-groups",
            "site.monitor.userpush.template.down", "site.monitor.userpush.template.slow",
            "site.monitor.userpush.template.expiry", "site.monitor.userpush.template.changed",
            "site.monitor.userpush.template.resolved", "site.monitor.userpush.template.test",
            "site.monitor.userpush.realert-enabled",
            "site.monitor.userpush.quiet-start", "site.monitor.userpush.quiet-end",
            "site.monitor.userpush.quiet-min-level", "site.monitor.userpush.retention-days");

    private final AppSettingsService appSettings;
    private final UserPushService userPushService;
    private final UserPushDeliveryRepository deliveryRepo;
    private final UserPushScopeRepository scopeRepo;
    private final SecretCipher secretCipher;
    private final AuditService auditService;

    // ── Ayarlar ────────────────────────────────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireAdmin(session);
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String k : PLAIN_KEYS) settings.put(k, appSettings.getString(k, null));
        settings.put("site.monitor.userpush.headers", maskedHeaders());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings);
        out.put("scopes", scopeRepo.findAll());
        out.put("defaults", Map.of(
                "templates", UserPushService.DEFAULT_TEMPLATES,
                "placeholders", UserPushService.KNOWN_PLACEHOLDERS));
        out.put("health", userPushService.healthSnapshot());
        return ok(out);
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        Map<String, Object> toSave = new LinkedHashMap<>();
        for (String k : PLAIN_KEYS) {
            if (!body.containsKey(k)) continue;
            Object v = body.get(k);
            // Şablonlar: bilinmeyen yer tutucu KAYDETMEDE reddedilir — çalışma anında sessizce
            // "{typo}" basmak yerine kullanıcı daha kaydederken uyarılır.
            if (k.startsWith("site.monitor.userpush.template.") && v != null) {
                String bad = unknownPlaceholder(String.valueOf(v));
                if (bad != null)
                    return badRequest("Bilinmeyen yer tutucu: {" + bad + "} — geçerli olanlar: "
                            + String.join(", ", UserPushService.KNOWN_PLACEHOLDERS));
            }
            toSave.put(k, v);
        }
        if (body.containsKey("site.monitor.userpush.headers")) {
            toSave.put("site.monitor.userpush.headers", encryptHeaders(body.get("site.monitor.userpush.headers")));
        }
        appSettings.save(toSave, actor(session));
        auditService.recordAction("USER_PUSH_SETTINGS", session, "USER_PUSH", "settings",
                "Webhook bildirim ayarları güncellendi (" + toSave.size() + " anahtar)", null);
        return getSettings(session);
    }

    /** Katman matrisi: [{scopeType, scopeKey, enabled}] toplu upsert. */
    @PostMapping("/scopes")
    public ResponseEntity<Map<String, Object>> saveScopes(
            @RequestBody List<Map<String, Object>> body, HttpSession session) {
        requireAdmin(session);
        int changed = 0;
        for (Map<String, Object> e : body) {
            String type = String.valueOf(e.get("scopeType"));
            String key = String.valueOf(e.get("scopeKey"));
            if (!List.of("TEAM", "TYPE").contains(type) || key.isBlank() || "null".equals(key)) continue;
            boolean enabled = Boolean.TRUE.equals(e.get("enabled"));
            UserPushScope row = scopeRepo.findByScopeTypeAndScopeKey(type, key).orElseGet(() -> {
                UserPushScope s = new UserPushScope();
                s.setScopeType(type);
                s.setScopeKey(key);
                return s;
            });
            if (row.getId() == null || !row.getEnabled().equals(enabled)) changed++;
            row.setEnabled(enabled);
            scopeRepo.save(row);
        }
        auditService.recordAction("USER_PUSH_SCOPES", session, "USER_PUSH", "scopes",
                changed + " kapsam anahtarı güncellendi", null);
        return ok(Map.of("data", scopeRepo.findAll()));
    }

    // ── Test gönderimi ─────────────────────────────────────────────────────────────────────

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTest(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        if (!userPushService.enabled())
            return badRequest("Kanal kapalı — önce global anahtarı açın");
        // Dakikada 3 tavan: test ucu gerçek gönderim yapar, kazara döngüye alınmasın.
        String since = ISO.format(Instant.now().minus(Duration.ofMinutes(1)).atZone(ZONE).toLocalDateTime());
        if (deliveryRepo.countByTriggerAndCreatedAtGreaterThanEqual("TEST", since) >= 3)
            return badRequest("Test tavanı: dakikada en çok 3 deneme");
        List<String> usernames = new ArrayList<>();
        if (body.get("usernames") instanceof List<?> list)
            for (Object o : list) if (o != null && !o.toString().isBlank()) usernames.add(o.toString().trim());
        if (usernames.isEmpty()) return badRequest("En az bir sicil girin");
        if (usernames.size() > 10) return badRequest("Tek denemede en çok 10 sicil");
        String template = body.get("template") == null ? "test" : String.valueOf(body.get("template"));
        Map<String, Object> result = userPushService.sendTest(usernames, template, "TEST — " + actor(session));
        // Kural 0 + gizlilik: audit'e sicil listesi DEĞİL yalnız adet yazılır.
        auditService.recordAction("USER_PUSH_TEST", session, "USER_PUSH", "test",
                usernames.size() + " alıcıya test gönderimi (" + template + ")", null);
        return ok(Map.of("data", result));
    }

    // ── Teslimat günlüğü (K11) ─────────────────────────────────────────────────────────────

    @GetMapping("/deliveries")
    public ResponseEntity<Map<String, Object>> deliveries(
            @RequestParam(required = false) String username, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String monitorType, @RequestParam(required = false) String level,
            @RequestParam(required = false) String status, @RequestParam(required = false) String trigger,
            @RequestParam(required = false) String notificationId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            HttpSession session) {
        requireAdmin(session);
        var pg = deliveryRepo.search(blankToNull(username), teamId, blankToNull(monitorType),
                blankToNull(level), blankToNull(status), blankToNull(trigger),
                blankToNull(notificationId), blankToNull(q), blankToNull(from), blankToNull(to),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveries", pg.getContent());
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        return ok(out);
    }

    /** CSV — listeyle AYNI süzgeçler (ekranda 12 satır varken dosyada 800 çıkmasın). */
    @GetMapping("/deliveries/export")
    public ResponseEntity<byte[]> exportDeliveries(
            @RequestParam(required = false) String username, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String monitorType, @RequestParam(required = false) String level,
            @RequestParam(required = false) String status, @RequestParam(required = false) String trigger,
            @RequestParam(required = false) String notificationId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            HttpSession session) {
        requireAdmin(session);
        var pg = deliveryRepo.search(blankToNull(username), teamId, blankToNull(monitorType),
                blankToNull(level), blankToNull(status), blankToNull(trigger),
                blankToNull(notificationId), blankToNull(q), blankToNull(from), blankToNull(to),
                PageRequest.of(0, 10_000));
        StringBuilder sb = new StringBuilder();
        sb.append("created_at,sent_at,username,display_name,team_id,monitor_type,monitor_name,")
          .append("alert_level,trigger,status,http_status,attempts,notification_id,batch_id,message\n");
        for (UserPushDelivery d : pg.getContent()) {
            sb.append(Csv.cell(d.getCreatedAt())).append(',').append(Csv.cell(d.getSentAt())).append(',')
              .append(Csv.cell(d.getUsername())).append(',').append(Csv.cell(d.getDisplayName())).append(',')
              .append(Csv.cell(d.getTeamId())).append(',').append(Csv.cell(d.getMonitorType())).append(',')
              .append(Csv.cell(d.getMonitorName())).append(',').append(Csv.cell(d.getAlertLevel())).append(',')
              .append(Csv.cell(d.getTrigger())).append(',').append(Csv.cell(d.getStatus())).append(',')
              .append(Csv.cell(d.getHttpStatus())).append(',').append(Csv.cell(d.getAttempts())).append(',')
              .append(Csv.cell(d.getNotificationId())).append(',').append(Csv.cell(d.getBatchId())).append(',')
              .append(Csv.cell(d.getMessage())).append('\n');
        }
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};   // Excel Türkçe karakter için BOM
        byte[] csv = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] outBytes = new byte[bom.length + csv.length];
        System.arraycopy(bom, 0, outBytes, 0, bom.length);
        System.arraycopy(csv, 0, outBytes, bom.length, csv.length);
        auditService.recordAction("USER_PUSH_EXPORT", session, "USER_PUSH", "deliveries",
                pg.getContent().size() + " satır CSV dışa aktarıldı", null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=webhook-deliveries.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(outBytes);
    }

    /** E3 istatistik şeridi: 24 saat / 7 gün durum dağılımı. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(HttpSession session) {
        requireAdmin(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("last24h", statusCounts(Duration.ofHours(24)));
        out.put("last7d", statusCounts(Duration.ofDays(7)));
        out.put("health", userPushService.healthSnapshot());
        return ok(out);
    }

    private Map<String, Long> statusCounts(Duration window) {
        String since = ISO.format(Instant.now().minus(window).atZone(ZONE).toLocalDateTime());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : deliveryRepo.countByStatusSince(since))
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        return counts;
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────

    /** Kayıtlı başlıklar — sır değerleri MASKELİ döner (write-only sözleşmesi). */
    private List<Map<String, Object>> maskedHeaders() {
        List<Map<String, Object>> out = new ArrayList<>();
        String json = appSettings.getString("site.monitor.userpush.headers", "");
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr.isArray()) for (JsonNode n : arr) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", n.path("name").asText(""));
                boolean secret = n.path("secret").asBoolean(false);
                row.put("secret", secret);
                row.put("value", secret ? (n.path("value").asText("").isBlank() ? "" : MASKED)
                        : n.path("value").asText(""));
                out.add(row);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * Gelen başlık listesi → saklanan JSON. Sır değeri MASKED/boş geldiyse mevcut şifreli değer
     * KORUNUR (kullanıcı "değiştirmedim" demiş olur); yeni değer geldiyse şifrelenir.
     * Desen {@code MonitoringController.scriptedEnvJson} ile aynı.
     */
    private String encryptHeaders(Object incoming) {
        Map<String, String> existingSecrets = new LinkedHashMap<>();
        try {
            JsonNode cur = MAPPER.readTree(appSettings.getString("site.monitor.userpush.headers", "[]"));
            if (cur.isArray()) for (JsonNode n : cur)
                if (n.path("secret").asBoolean(false))
                    existingSecrets.put(n.path("name").asText(""), n.path("value").asText(""));
        } catch (Exception ignored) { }
        List<Map<String, Object>> out = new ArrayList<>();
        if (incoming instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                Object nm = e.get("name");
                if (nm == null || nm.toString().isBlank()) continue;
                String name = nm.toString().trim();
                boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));
                String val = e.get("value") == null ? "" : e.get("value").toString();
                String stored;
                if (secret) {
                    if (val.isBlank() || MASKED.equals(val)) stored = existingSecrets.getOrDefault(name, "");
                    else stored = secretCipher.encrypt(val);
                } else {
                    stored = val;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("value", stored);
                row.put("secret", secret);
                out.add(row);
            }
        }
        try { return MAPPER.writeValueAsString(out); } catch (Exception e) { return "[]"; }
    }

    /** Şablondaki İLK bilinmeyen yer tutucu; hepsi geçerliyse null. */
    static String unknownPlaceholder(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!UserPushService.KNOWN_PLACEHOLDERS.contains(m.group(1))) return m.group(1);
        }
        return null;
    }

    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            log.warn("Yetkisiz user-push yönetim denemesi: {}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u == null ? "?" : u.toString();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", message);
        return ResponseEntity.badRequest().body(out);
    }
}
