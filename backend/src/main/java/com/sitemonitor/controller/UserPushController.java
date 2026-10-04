package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.util.Csv;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
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
    /** Teslimat satırları UTC yazılır ({@code UserPushService.ISO}); pencere sınırı da UTC olmalı (prod kapısı 2026-09-25,
     *  O-6 / eski Y20): İstanbul yereliyle hesaplanan "since" saklanan değerlerin 3 saat İLERİSİNDE kalıyordu — dakikada
     *  3 test tavanı hiç tetiklenmiyor, "son 24 saat" fiilen 21 saati sayıyordu. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC);
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
            "site.monitor.userpush.weekly.team-enabled", "site.monitor.userpush.weekly.manager-enabled",
            "site.monitor.userpush.quiet-start", "site.monitor.userpush.quiet-end",
            "site.monitor.userpush.quiet-min-level", "site.monitor.userpush.retention-days",
            // 2026-10-04: sayfa bunları gösteriyor ve gönderiyordu ama liste dışındaydılar — kayıt SESSİZCE düşüyordu
            // (mesaj/sebep tavanı, sertifika şablonu düzenlenemiyordu). Katalogda zaten vardılar.
            "site.monitor.userpush.template.cert", "site.monitor.userpush.template.degraded",
            "site.monitor.userpush.max-message-chars", "site.monitor.userpush.reason-max-chars",
            // Saat tavanı özeti + kritik muafiyeti (öneri 2), eskalasyon adımı push'u (öneri 6)
            "site.monitor.userpush.overflow-summary-enabled", "site.monitor.userpush.overflow-summary-minutes",
            "site.monitor.userpush.critical-bypass-cap", "site.monitor.escalation.step-push-enabled",
            // İngilizce başlık + şablonlar (öneri 5)
            "site.monitor.userpush.title.en",
            "site.monitor.userpush.template.down.en", "site.monitor.userpush.template.slow.en",
            "site.monitor.userpush.template.expiry.en", "site.monitor.userpush.template.changed.en",
            "site.monitor.userpush.template.cert.en", "site.monitor.userpush.template.degraded.en",
            "site.monitor.userpush.template.resolved.en", "site.monitor.userpush.template.test.en");

    private final AppSettingsService appSettings;
    private final UserPushService userPushService;
    private final UserPushDeliveryRepository deliveryRepo;
    private final UserPushScopeRepository scopeRepo;
    private final SecretCipher secretCipher;
    private final AuditService auditService;
    private final UserPushRecipientResolver recipientResolver;   // /explain (2026-09-11)
    private final AppUserRepository userRepo;                    // teslimat satırlarına takım adı (2026-09-11)
    private final TeamRepository teamRepo;

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
                "templates_en", com.sitemonitor.service.PushI18n.DEFAULT_TEMPLATES_EN,
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
                    return badRequest(Msg.t("Bilinmeyen yer tutucu: {", "Unknown placeholder: {") + bad + Msg.t("} — geçerli olanlar: ", "} — valid placeholders: ")
                            + String.join(", ", UserPushService.KNOWN_PLACEHOLDERS));
            }
            toSave.put(k, v);
        }
        if (body.containsKey("site.monitor.userpush.headers")) {
            toSave.put("site.monitor.userpush.headers", encryptHeaders(body.get("site.monitor.userpush.headers")));
        }
        // Değerler save'DEN ÖNCE okunur (save void döner + önbelleği anında tazeler).
        // Maskeleme kritik: `...webhook-url` hassas ANAHTAR sayılmıyor (kara listede "url" yok)
        // ama DEĞERİ yol/query içinde token taşıyabilir — AuditDiff değer gövdesini de temizler.
        Map<String, Object> pushBefore = new java.util.LinkedHashMap<>();
        for (String k : toSave.keySet()) pushBefore.put(k, appSettings.getString(k, null));

        appSettings.save(toSave, actor(session));

        auditService.recordAction("USER_PUSH_SETTINGS", session, "USER_PUSH", "settings",
                AuditDetail.of("keys", toSave.size()), AuditDiff.diff(pushBefore, toSave));
        return getSettings(session);
    }

    /** Katman matrisi: [{scopeType, scopeKey, enabled}] toplu upsert. */
    @PostMapping("/scopes")
    public ResponseEntity<Map<String, Object>> saveScopes(
            @RequestBody List<Map<String, Object>> body, HttpSession session) {
        requireAdmin(session);
        requireScopeRowsManageable(session, body);
        int changed = 0;
        // Bildirim KAPSAMI bir yetki ayarıdır ("hangi takım/tür push alır"): hangi anahtarın
        // açılıp kapandığı yazılmadan "N kapsam güncellendi" demek denetimde işe yaramıyordu.
        Map<String, Object> scopeBefore = new java.util.LinkedHashMap<>();
        Map<String, Object> scopeAfter  = new java.util.LinkedHashMap<>();
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
            scopeBefore.put(type + "/" + key, row.getId() == null ? null : row.getEnabled());
            scopeAfter.put(type + "/" + key, enabled);
            row.setEnabled(enabled);
            scopeRepo.save(row);
        }
        auditService.recordAction("USER_PUSH_SCOPES", session, "USER_PUSH", "scopes",
                AuditDetail.of("changed", changed, "submitted", body.size()),
                AuditDiff.diff(scopeBefore, scopeAfter));
        return ok(Map.of("data", scopeRepo.findAll()));
    }

    /**
     * Kapsam satırı yetkisi (2026-09-28 regresyon taraması): kapsamlı müdür (AD ADMIN) {@link #requireAdmin}'den geçip
     * HERHANGİ bir takımın push'unu kapatabiliyordu. Global olmayan çağıran yalnız YÖNETTİĞİ takımın TEAM satırını
     * değiştirir; TYPE satırı takımsızdır (bütün takımları etkiler) → yalnız global yönetici. Doğrulama yazmadan ÖNCE,
     * bütün gövde için yapılır: bir satır reddedilirse hiçbiri yazılmaz (yarım uygulanmış toplu işlem yok).
     */
    private static void requireScopeRowsManageable(HttpSession session, List<Map<String, Object>> body) {
        if (SessionScope.isGlobalAdmin(session)) return;
        for (Map<String, Object> e : body) {
            String type = String.valueOf(e.get("scopeType"));
            String key = String.valueOf(e.get("scopeKey"));
            if (!List.of("TEAM", "TYPE").contains(type) || key.isBlank() || "null".equals(key)) continue;   // kayıt döngüsü de atlar
            if ("TYPE".equals(type)) {
                throw new SecurityException(Msg.t(
                        "İzleme türü kapsamı bütün takımları etkiler; yalnız global yönetici değiştirebilir: ",
                        "A monitor-type scope affects every team; only a global administrator can change it: ") + key);
            }
            Long teamId = canonicalTeamId(key);
            if (teamId == null || !SessionScope.canManage(session, teamId)) {
                throw new SecurityException(Msg.t("Bu takımın push kapsamını değiştirme yetkiniz yok: ",
                        "You don't have permission to change this team's push scope: ") + key);
            }
        }
    }

    /** TEAM anahtarı → takım id'si; yalnız kanonik biçim ("5"; " 5"/"05" değil — kayıt anahtarı birebir yazılır). */
    private static Long canonicalTeamId(String key) {
        try {
            Long id = Long.valueOf(key);
            return String.valueOf(id).equals(key) ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── Test gönderimi ─────────────────────────────────────────────────────────────────────

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTest(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        if (!userPushService.enabled())
            return badRequest(Msg.t("Kanal kapalı — önce global anahtarı açın", "Channel is off — enable the global switch first"));
        // Dakikada 3 tavan: test ucu gerçek gönderim yapar, kazara döngüye alınmasın.
        String since = ISO.format(Instant.now().minus(Duration.ofMinutes(1)));
        if (deliveryRepo.countByTriggerAndCreatedAtGreaterThanEqual("TEST", since) >= 3)
            return badRequest(Msg.t("Test tavanı: dakikada en çok 3 deneme", "Test limit: at most 3 attempts per minute"));
        List<String> usernames = new ArrayList<>();
        if (body.get("usernames") instanceof List<?> list)
            for (Object o : list) if (o != null && !o.toString().isBlank()) usernames.add(o.toString().trim());
        if (usernames.isEmpty()) return badRequest("En az bir sicil girin");
        if (usernames.size() > 10) return badRequest(Msg.t("Tek denemede en çok 10 sicil", "At most 10 users per attempt"));
        requireTestRecipientsManageable(session, usernames);
        String template = body.get("template") == null ? "test" : String.valueOf(body.get("template"));
        // Şablon düzenleyicisinin TR / EN sekmesi (2026-10-04): test seçilen dilin şablonu ve başlığıyla gider.
        String lang = com.sitemonitor.service.PushI18n.norm(body.get("lang") == null ? null : String.valueOf(body.get("lang")));
        Map<String, Object> result = com.sitemonitor.service.PushI18n.isEn(lang)
                ? userPushService.sendTest(usernames, template, "TEST — " + actor(session), lang)
                : userPushService.sendTest(usernames, template, "TEST — " + actor(session));   // Türkçe: bugünkü yol
        // Kural 0 + gizlilik: audit'e sicil listesi DEĞİL yalnız adet yazılır.
        auditService.recordAction("USER_PUSH_TEST", session, "USER_PUSH", "test",
                usernames.size() + " alıcıya test gönderimi (" + template + ", " + lang + ")", null);
        return ok(Map.of("data", result));
    }

    /**
     * Test alıcısı yetkisi (2026-09-28 regresyon taraması): kapsamlı müdür test ucundan HERHANGİ bir sicile gerçek
     * push atabiliyordu. Global olmayan çağıran için her sicil, YÖNETTİĞİ bir takımın üyesi olmalı. Üyelik = birincil
     * takım VEYA {@code app_user_teams} ({@code findMemberIdentities}; alıcı çözümünün baktığı iki kaynak — birincil
     * takımı olup üyelik satırı eksik kişi de test edilebilsin, teşhis tam o kişi için yapılır). Kullanıcı tablosunda
     * olmayan sicil de reddedilir.
     */
    private void requireTestRecipientsManageable(HttpSession session, List<String> usernames) {
        if (SessionScope.isGlobalAdmin(session)) return;
        List<Long> managed = SessionScope.manageTeamIds(session);
        Set<String> members = new java.util.HashSet<>();
        if (managed != null && !managed.isEmpty()) {
            for (Object[] row : userRepo.findMemberIdentities(managed)) {
                if (row != null && row.length > 1 && row[1] != null) members.add(row[1].toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String u : usernames) {
            if (!members.contains(u.toLowerCase(java.util.Locale.ROOT))) {
                throw new SecurityException(Msg.t("Test gönderimi yalnız yönettiğiniz takımların üyelerine yapılabilir: ",
                        "Test pushes can only be sent to members of teams you manage: ") + u);
            }
        }
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
        var pg = scopedSearch(session, username, teamId, monitorType, level, status, trigger, notificationId, q, from, to,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveries", pg.getContent());
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        // 2026-09-11 (kullanıcı): "günlükte kişinin takımı da yazsın". Satırdaki team_id ALARMIN takımıdır
        // ve test gönderiminde boştur; kişinin ÜYE olduğu takımlar ayrı harita olarak eklenir (entity
        // serileştirmesine dokunulmaz → mevcut sözleşme bozulmaz).
        out.put("user_teams", teamNamesFor(pg.getContent()));
        return ok(out);
    }

    /**
     * Teslimat günlüğü TAKIM KAPSAMI (2026-09-28 regresyon taraması; `/explain` ve "Kim bilgilendirilir?" ile TEK kural):
     * global yönetici her satırı, kapsamlı müdür (AD ADMIN) yalnız YÖNETTİĞİ takımların alarmlarına ait satırları görür.
     * Eskiden {@link #requireAdmin} yeterliydi → müdür her takımın kişi adlarını, durumlarını (SKIPPED_USER_OPT_OUT
     * dâhil) ve mesaj metinlerini liste + CSV'den okuyabiliyordu. Yönetmediği takım süzgeci 403; takımsız
     * (test/sistem) satırlar müdüre gösterilmez.
     */
    private Page<UserPushDelivery> scopedSearch(HttpSession session, String username, Long teamId, String monitorType,
                                                String level, String status, String trigger, String notificationId,
                                                String q, String from, String to, Pageable pageable) {
        if (!SessionScope.isGlobalAdmin(session)) {
            if (teamId != null && !SessionScope.canManage(session, teamId)) {
                throw new SecurityException(Msg.t("Bu takımın push teslimatlarını görme yetkiniz yok.",
                        "You don't have permission to see this team's push deliveries."));
            }
            if (teamId == null) {
                List<Long> managed = SessionScope.manageTeamIds(session);
                if (managed == null || managed.isEmpty()) return Page.empty(pageable);
                return deliveryRepo.searchInTeams(managed, blankToNull(username), blankToNull(monitorType),
                        blankToNull(level), blankToNull(status), blankToNull(trigger),
                        blankToNull(notificationId), blankToNull(q), blankToNull(from), blankToNull(to), pageable);
            }
        }
        return deliveryRepo.search(blankToNull(username), teamId, blankToNull(monitorType),
                blankToNull(level), blankToNull(status), blankToNull(trigger),
                blankToNull(notificationId), blankToNull(q), blankToNull(from), blankToNull(to), pageable);
    }

    /** İstatistik kapsamı: global yönetici için null (tümü), kapsamlı müdür için yönettiği takımlar (boş olabilir). */
    private static Set<Long> statsScope(HttpSession session) {
        if (SessionScope.isGlobalAdmin(session)) return null;
        List<Long> managed = SessionScope.manageTeamIds(session);
        return managed == null ? Set.of() : new java.util.HashSet<>(managed);
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
        var pg = scopedSearch(session, username, teamId, monitorType, level, status, trigger, notificationId, q, from, to,
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
    /** "Bu takım + bu seviye için kim alır, kim neden almaz?" — alıcı çözümünün açıklamalı hâli (2026-09-11). */
    @GetMapping("/explain")
    public ResponseEntity<Map<String, Object>> explain(@RequestParam Long teamId,
                                                       @RequestParam(defaultValue = "HIGH") String level,
                                                       HttpSession session) {
        requireAdmin(session);
        // Kişi kararları (org rolü, opt-out) — "Kim bilgilendirilir?" ile TEK kural (2026-09-28): kapsamlı müdür yalnız
        // YÖNETTİĞİ takımı sorar. Eskiden requireAdmin'den geçen müdür, API'den herhangi bir takımın üyelerini okuyabiliyordu.
        if (PushDecisionAccess.of(session, teamId).level() != PushDecisionAccess.Level.FULL) {
            throw new SecurityException(Msg.t("Bu takımın push kararlarını görme yetkiniz yok.",
                    "You don't have permission to see this team's push decisions."));
        }
        String lvl = level == null ? "HIGH" : level.trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("level", lvl);
        out.put("members", recipientResolver.explain(teamId, lvl));
        return ok(out);
    }

    /** KPI pencereleri (2026-09-12, kullanıcı): 24 saat · 7 · 15 · 30 · 60 gün. Anahtar = API/UI sözleşmesi. */
    static final Map<String, Duration> STAT_WINDOWS = new LinkedHashMap<>();
    static {
        STAT_WINDOWS.put("24h", Duration.ofHours(24));
        STAT_WINDOWS.put("7d",  Duration.ofDays(7));
        STAT_WINDOWS.put("15d", Duration.ofDays(15));
        STAT_WINDOWS.put("30d", Duration.ofDays(30));
        STAT_WINDOWS.put("60d", Duration.ofDays(60));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(HttpSession session) {
        requireAdmin(session);
        Map<String, Object> out = new LinkedHashMap<>();
        // Geriye uyum: last24h / last7d düz sayaç haritası olarak kalır (eski istemci/test sözleşmesi).
        Set<Long> scope = statsScope(session);   // kapsamlı müdür: yalnız yönettiği takımların sayıları
        out.put("last24h", statusCounts(STAT_WINDOWS.get("24h"), scope));
        out.put("last7d", statusCounts(STAT_WINDOWS.get("7d"), scope));
        // Yeni sözleşme: windows[key] = { counts: {status→n}, teams: [{team_id, team_name, SENT, FAILED, total}] }
        Map<String, Object> windows = new LinkedHashMap<>();
        Map<Long, String> teamNames = new LinkedHashMap<>();
        teamRepo.findAll().forEach(tm -> teamNames.put(tm.getId(), tm.getName()));
        for (Map.Entry<String, Duration> w : STAT_WINDOWS.entrySet()) {
            String since = ISO.format(Instant.now().minus(w.getValue()));
            Map<String, Object> win = new LinkedHashMap<>();
            win.put("counts", statusCounts(w.getValue(), scope));
            win.put("teams", teamBreakdown(since, teamNames, scope));
            windows.put(w.getKey(), win);
        }
        out.put("windows", windows);
        out.put("health", userPushService.healthSnapshot());
        return ok(out);
    }

    private Map<String, Long> statusCounts(Duration window, Set<Long> scope) {
        String since = ISO.format(Instant.now().minus(window));
        Map<String, Long> counts = new LinkedHashMap<>();
        if (scope == null) {
            for (Object[] row : deliveryRepo.countByStatusSince(since))
                counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            return counts;
        }
        for (Object[] row : deliveryRepo.countByTeamAndStatusSince(since)) {
            Long teamId = row[0] == null ? null : ((Number) row[0]).longValue();
            if (teamId == null || !scope.contains(teamId)) continue;
            counts.merge(String.valueOf(row[1]), ((Number) row[2]).longValue(), Long::sum);
        }
        return counts;
    }

    /** Takım başına SENT/FAILED/toplam — toplam azalan; takımsız satırlar (test/sistem) "—" adıyla en sonda. */
    private List<Map<String, Object>> teamBreakdown(String since, Map<Long, String> teamNames, Set<Long> scope) {
        Map<Long, Map<String, Object>> byTeam = new LinkedHashMap<>();
        for (Object[] row : deliveryRepo.countByTeamAndStatusSince(since)) {
            Long teamId = row[0] == null ? null : ((Number) row[0]).longValue();
            if (scope != null && (teamId == null || !scope.contains(teamId))) continue;
            String status = String.valueOf(row[1]);
            long n = ((Number) row[2]).longValue();
            Map<String, Object> m = byTeam.computeIfAbsent(teamId, id -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("team_id", id);
                x.put("team_name", id == null ? null : teamNames.getOrDefault(id, "#" + id));
                x.put("SENT", 0L); x.put("FAILED", 0L); x.put("total", 0L);
                return x;
            });
            if ("SENT".equals(status)) m.put("SENT", (Long) m.get("SENT") + n);
            else if ("FAILED".equals(status)) m.put("FAILED", (Long) m.get("FAILED") + n);
            m.put("total", (Long) m.get("total") + n);
        }
        List<Map<String, Object>> out = new ArrayList<>(byTeam.values());
        out.sort((a, b) -> {
            boolean an = a.get("team_id") == null, bn = b.get("team_id") == null;
            if (an != bn) return an ? 1 : -1;
            return Long.compare((Long) b.get("total"), (Long) a.get("total"));
        });
        return out;
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
        // Kayitli degerler AD bazinda tutulur — yalniz secret=true satirlardan degil. Kullanici
        // "sir" kutusunu KALDIRIP kaydettiginde de kurtarma yapabilmek icin gerekli (asagiya bkz.).
        Map<String, String> existingValues = new LinkedHashMap<>();
        Map<String, Boolean> existingSecretFlags = new LinkedHashMap<>();
        // BOZUK KAYIT SESSIZCE SIR SILMEZ. Ayristirma hatasi eskiden yutuluyordu; existingValues
        // bos kalinca hem maskeli ("degistirilmedi") hem de bos-secret dali `getOrDefault(name, "")`
        // ile BOS DIZE yaziyordu — kullanici yalnizca bir baslik ADINI degistirse bile kayitli
        // Authorization token'i geri alinamaz bicimde siliniyor, hicbir hata/log cikmiyor ve
        // sonraki her push kimlik dogrulama hatasiyla dusuyordu. Artik loglanir ve KORUNMASI
        // GEREKEN bir deger varsa istek 409 ile reddedilir (kullanici degeri yeniden girip onarir).
        boolean existingUnreadable = false;
        try {
            JsonNode cur = MAPPER.readTree(appSettings.getString("site.monitor.userpush.headers", "[]"));
            if (cur.isArray()) for (JsonNode n : cur) {
                String nm = n.path("name").asText("");
                existingValues.put(nm, n.path("value").asText(""));
                existingSecretFlags.put(nm, n.path("secret").asBoolean(false));
            }
        } catch (Exception ex) {
            existingUnreadable = true;
            log.warn("userpush.headers ayristirilamadi — kayitli deger korunamiyor: {}", ex.toString());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (incoming instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                Object nm = e.get("name");
                if (nm == null || nm.toString().isBlank()) continue;
                String name = nm.toString().trim();
                boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));   // maskeli degerde asagida korunur
                String val = e.get("value") == null ? "" : e.get("value").toString();
                String stored;
                // MASKELI deger = "kullanici bu alani DEGISTIRMEDI". Karar bayraktan BAGIMSIZ
                // verilmeli: eskiden secret kutusu kaldirilinca else daline dusuluyor ve
                // stored = "*****" yaziliyordu — sifreli token GERI ALINAMAZ bicimde siliniyor,
                // sonraki her push "Authorization: *****" ile gidip FAILED oluyordu.
                boolean unchangedMasked = MASKED.equals(val);
                if (unchangedMasked || (secret && val.isBlank())) {
                    if (existingUnreadable)
                        throw new IllegalStateException("Kayitli push basliklari okunamadi (bozuk kayit): '"
                                + name + "' degeri korunamaz. Degeri yeniden girip kaydedin.");
                }
                if (unchangedMasked) {
                    stored = existingValues.getOrDefault(name, "");
                    // Deger sifreli saklaniyorsa "sir" bayragi da korunur: cozup duz metne
                    // yazmak, kullanicinin gormedigi bir sirri acikta birakmak olurdu.
                    if (Boolean.TRUE.equals(existingSecretFlags.get(name))) secret = true;
                } else if (secret) {
                    stored = val.isBlank() ? existingValues.getOrDefault(name, "") : secretCipher.encrypt(val);
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

    /** username → ÜYE olduğu takım adları (sıralı, tekilleştirilmiş). Boş sayfada sorgu yapılmaz. */
    private Map<String, List<String>> teamNamesFor(List<UserPushDelivery> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            Set<String> usernames = new LinkedHashSet<>();
            for (UserPushDelivery d : rows) {
                String u = d.getUsername();
                if (u != null && !u.isBlank() && !"-".equals(u)) usernames.add(u.trim().toUpperCase(java.util.Locale.ROOT));
            }
            if (usernames.isEmpty()) return out;
            List<Object[]> pairs = userRepo.findTeamMembershipsByUsernames(usernames);
            Map<String, Set<Long>> byUser = new LinkedHashMap<>();
            Set<Long> teamIds = new LinkedHashSet<>();
            for (Object[] row : pairs) {
                String u = String.valueOf(row[0]).trim().toUpperCase(java.util.Locale.ROOT);
                Long tid = ((Number) row[1]).longValue();
                byUser.computeIfAbsent(u, k -> new LinkedHashSet<>()).add(tid);
                teamIds.add(tid);
            }
            if (teamIds.isEmpty()) return out;
            Map<Long, String> names = new LinkedHashMap<>();
            teamRepo.findAllById(teamIds).forEach(t -> names.put(t.getId(), t.getName()));
            byUser.forEach((u, ids) -> {
                List<String> ns = new ArrayList<>();
                for (Long id : ids) { String n = names.get(id); if (n != null) ns.add(n); }
                if (!ns.isEmpty()) out.put(u, ns);
            });
        } catch (Exception e) {
            log.debug("Teslimat günlüğü takım zenginleştirmesi atlandı: {}", e.toString());
        }
        return out;
    }

    private void requireAdmin(HttpSession session) {
        // 2026-09-10: kapsamlı müdür (AD ADMIN) de yönetir; userpush.url/headers GLOBAL_ONLY olduğundan
        // AppSettingsService.save onları müdür için reddeder (gizli başlıklar saldırgan URL'ine gitmez).
        if (!SessionScope.isGlobalAdmin(session) && !SessionScope.isScopedAdmin(session)) {
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
