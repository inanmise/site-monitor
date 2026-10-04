package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MyPushHistoryService;
import com.sitemonitor.service.PushI18n;
import com.sitemonitor.service.PushPreferences;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kişinin KENDİ push yüzeyi (2026-10-04, onaylı öneriler 3/4/5) — Etkinliklerim'deki "Bildirim tercihlerim" ve
 * "Push bildirimlerim". Push-opt-out deseni: kimlik YALNIZ oturumdan okunur, istekte kullanıcı/kimlik parametresi YOK
 * (IDOR yüzeyi açılmaz).
 *
 * <ul>
 *   <li>{@code GET/PUT /api/me/push-preferences} — en düşük seviye, izleme aileleri, push dili (denetim
 *       {@code PUSH_PREFS_UPDATE}); doğrulama hatası 400 + {@code field} (mesaj arayüz dilinde).</li>
 *   <li>{@code POST /api/me/push-snooze} — {@code {preset: 1h|4h|tomorrow|off, critical?}} (denetim {@code PUSH_SNOOZE}).</li>
 *   <li>{@code POST /api/me/push-test} — kendine test push'u ({@link UserPushService#sendSelfTest}); 10 dakikada en çok 3
 *       (429), tercihlerden bağımsız (açık test isteği), denetim {@code PUSH_SELF_TEST}.</li>
 *   <li>{@code GET /api/me/push-history?days=7|30&filter=all|sent|not_sent&page&size} — {@link MyPushHistoryService}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MyPushController {

    /** Kendine test push'u: pencere başına en çok bu kadar (kişi başına, pod içi). */
    static final int SELF_TEST_LIMIT = 3;
    static final Duration SELF_TEST_WINDOW = Duration.ofMinutes(10);

    private final UserService userService;
    private final UserPushService userPushService;
    private final MyPushHistoryService historyService;
    private final AuditService auditService;

    /** Test kancası. */
    Clock clock = Clock.systemUTC();

    /** Kendine test hız sınırı — kişi başına son denemelerin zamanları (bellek içi; pod başına, yeniden başlatmada sıfırlanır). */
    private final Map<String, Deque<Instant>> selfTests = new ConcurrentHashMap<>();

    // ── Tercihler ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/push-preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(HttpSession session) {
        AppUser u = currentUser(session);
        return ok(PushPreferences.toMap(u, Instant.now(clock)));
    }

    @PutMapping("/push-preferences")
    public ResponseEntity<Map<String, Object>> savePreferences(@RequestBody(required = false) Map<String, Object> body,
                                                               HttpSession session) {
        AppUser u = currentUser(session);
        Map<String, Object> b = body == null ? Map.of() : body;
        // Kısmi gövde: gönderilmeyen alan bugünkü değerini korur (arayüz yalnız değişeni yollayabilsin).
        Object lvl = b.containsKey("min_level") ? b.get("min_level") : u.getPushMinLevel();
        Object fam = b.containsKey("families") ? b.get("families") : u.getPushFamilies();
        Object lang = b.containsKey("lang") ? b.get("lang") : u.getPushLang();
        PushPreferences.Normalized n = PushPreferences.normalize(lvl, fam, lang);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("min_level", u.getPushMinLevel());
        before.put("families", u.getPushFamilies());
        before.put("lang", u.getPushLang());
        AppUser saved = userService.savePushPreferences(u, n.minLevel(), n.families(), n.lang());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("min_level", n.minLevel());
        after.put("families", n.families());
        after.put("lang", n.lang());
        if (!Objects.equals(before, after)) {
            auditService.recordAction("PUSH_PREFS_UPDATE", session, "USER", String.valueOf(saved.getId()),
                    AuditDetail.of("min_level", n.minLevel() == null ? "ALL" : n.minLevel(),
                            "families", n.families() == null ? "ALL" : n.families(),
                            "lang", PushI18n.norm(n.lang())),
                    com.sitemonitor.service.AuditDiff.diff(before, after));
        }
        return ok(PushPreferences.toMap(saved, Instant.now(clock)));
    }

    @PostMapping("/push-snooze")
    public ResponseEntity<Map<String, Object>> snooze(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session) {
        AppUser u = currentUser(session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Instant now = Instant.now(clock);
        Boolean critical = b.get("critical") instanceof Boolean c ? c : null;
        String preset = b.get("preset") == null ? null : b.get("preset").toString().trim().toLowerCase(Locale.ROOT);
        String until;
        if (preset == null || preset.isEmpty()) {
            // Yalnız "kritikler yine gelsin" değişti — süre korunur.
            if (critical == null) {
                throw new PushPreferences.FieldException("preset", Msg.t(
                        "Bir susturma seçeneği seçin.", "Choose a snooze option."));
            }
            until = u.getPushSnoozeUntil();
        } else {
            until = PushPreferences.iso(PushPreferences.snoozeUntil(preset, now));
        }
        AppUser saved = userService.savePushSnooze(u, until, critical);
        auditService.recordAction("PUSH_SNOOZE", session, "USER", String.valueOf(saved.getId()),
                AuditDetail.of("preset", preset == null || preset.isEmpty() ? "keep" : preset,
                        "until", until == null ? "-" : until,
                        "critical", !Boolean.FALSE.equals(saved.getPushSnoozeCritical())), null);
        return ok(PushPreferences.toMap(saved, now));
    }

    // ── Kendine test ───────────────────────────────────────────────────────────────────────

    @PostMapping("/push-test")
    public ResponseEntity<Map<String, Object>> selfTest(HttpSession session) {
        AppUser u = currentUser(session);
        Instant now = Instant.now(clock);
        String key = u.getUsername().trim().toLowerCase(Locale.ROOT);
        Long retryAfter = reserveSelfTest(key, now);
        if (retryAfter != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", Msg.t("Test sınırı: 10 dakikada en çok 3 deneme. Biraz sonra yeniden deneyin.",
                    "Test limit: at most 3 attempts per 10 minutes. Try again shortly."));
            body.put("code", "RATE_LIMITED");
            body.put("retry_after_seconds", retryAfter);
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(retryAfter)).body(body);
        }
        String lang = PushI18n.norm(u.getPushLang());
        UserPushService.DirectResult r = userPushService.sendSelfTest(u.getUsername(), lang);
        String outcome = r.ok() ? "OK" : "NOT_CONFIGURED".equals(r.error()) ? "NOT_CONFIGURED"
                : r.httpStatus() != null ? "HTTP" : "ERROR";
        auditService.recordAction("PUSH_SELF_TEST", session, "USER", String.valueOf(u.getId()),
                AuditDetail.of("outcome", outcome, "http_status", r.httpStatus() == null ? "-" : r.httpStatus(),
                        "lang", lang), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("outcome", outcome);
        out.put("http_status", r.httpStatus());
        out.put("error", r.ok() ? null : r.error());
        out.put("lang", lang);
        out.put("channel_enabled", userPushService.enabled());
        return ok(out);
    }

    /** Sınır içindeyse denemeyi kaydeder ve null döner; doluysa en eski denemenin düşmesine kalan saniye. */
    Long reserveSelfTest(String key, Instant now) {
        Deque<Instant> q = selfTests.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(SELF_TEST_WINDOW))) q.pollFirst();
            if (q.size() >= SELF_TEST_LIMIT) {
                long s = Duration.between(now, q.peekFirst().plus(SELF_TEST_WINDOW)).getSeconds();
                return Math.max(1L, s);
            }
            q.addLast(now);
            return null;
        }
    }

    // ── Geçmiş ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/push-history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(defaultValue = "7") int days,
                                                       @RequestParam(defaultValue = "all") String filter,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "25") int size,
                                                       HttpSession session) {
        AppUser u = currentUser(session);
        if (!MyPushHistoryService.FILTERS.contains(filter)) {
            throw new PushPreferences.FieldException("filter", Msg.t(
                    "Süzgeç all, sent ya da not_sent olmalı.", "Filter must be all, sent or not_sent."));
        }
        if (days != 7 && days != 30) {
            throw new PushPreferences.FieldException("days", Msg.t(
                    "Dönem 7 ya da 30 gün olmalı.", "The period must be 7 or 30 days."));
        }
        return ResponseEntity.ok(historyService.history(u.getUsername(), SessionScope.memberTeamIds(session),
                teamId -> SessionScope.canView(session, teamId), days, filter, page, size, Instant.now(clock)));
    }

    // ── yardımcılar ────────────────────────────────────────────────────────────────────────

    /** Oturumdaki kullanıcının taze satırı — kanonik kullanıcı adı DB'den gelir. */
    private AppUser currentUser(HttpSession session) {
        String username = session == null ? null : (String) session.getAttribute("username");
        if (username == null) throw new SecurityException("Not authenticated");
        return userService.findByUsername(username).orElseThrow(() -> new SecurityException("Not authenticated"));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.putAll(data);
        return ResponseEntity.ok(resp);
    }

    /** Alan-bazlı doğrulama hatası → 400 + {@code field}. */
    @ExceptionHandler(PushPreferences.FieldException.class)
    public ResponseEntity<Map<String, Object>> fieldError(PushPreferences.FieldException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        body.put("field", e.field());
        return ResponseEntity.status(400).body(body);
    }
}
