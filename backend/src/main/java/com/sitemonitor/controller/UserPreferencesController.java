package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserPreferencesService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kişisel tercihler (2026-10-02, onaylı öneri 23) — favori izlemeler, açılış sekmesi, kayıtlı görünümler ve tarayıcıdaki
 * beyaz listeli tercihlerin sunucu aynası. Push-opt-out deseni: kullanıcı YALNIZ KENDİ satırını okur/yazar; kimlik
 * oturumdan gelir, istekte kullanıcı/kimlik parametresi YOKTUR (IDOR yüzeyi açılmaz).
 *
 * <ul>
 *   <li>{@code GET /api/me/preferences} → {@code {success, prefs, updated_at}} (satır yoksa boş belge).</li>
 *   <li>{@code PUT /api/me/preferences} — kısmi nesne; üst düzey anahtar değiştirilir, {@code local} girdi bazında
 *       birleşir (bkz. {@link UserPreferencesService}). Bilinmeyen anahtar / sınır aşımı 400 (mesaj arayüz dilinde).</li>
 * </ul>
 *
 * <p><b>Denetim:</b> yalnız kullanıcının AÇIK seçimleri ({@code favorites}, {@code landingTab}, {@code savedViews})
 * değiştiğinde {@code USER_PREFERENCES_UPDATE} yazılır. {@code local} aynası (kenar çubuğu, sayfa boyutu, katlanan
 * bölümler …) arayüzün 1 sn'lik toplu yansımasıdır — her yansımayı denetlemek defteri gürültüye boğardı
 * (taslak otomatik kaydının gerekçesiyle aynı) ve güvenlik değeri taşımaz.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class UserPreferencesController {

    /** Denetlenen (kullanıcının bilinçli seçtiği) üst düzey anahtarlar. */
    static final Set<String> AUDITED_KEYS = Set.of(
            UserPreferencesService.FAVORITES, UserPreferencesService.LANDING_TAB, UserPreferencesService.SAVED_VIEWS);

    private final UserPreferencesService preferences;
    private final UserService userService;
    private final AuditService auditService;

    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        long userId = currentUserId(session);
        var snap = preferences.get(userId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("prefs", snap.prefs());
        resp.put("updated_at", snap.updatedAt());
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> put(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        long userId = currentUserId(session);
        var result = preferences.merge(userId, body);
        var audited = result.changed().stream().filter(AUDITED_KEYS::contains).sorted().toList();
        if (!audited.isEmpty()) {
            auditService.recordAction("USER_PREFERENCES_UPDATE", session, "USER", String.valueOf(userId),
                    "Kişisel tercihler güncellendi: " + String.join(", ", audited), summary(result.prefs(), audited));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("prefs", result.prefs());
        resp.put("updated_at", result.updatedAt());
        return ResponseEntity.ok(resp);
    }

    /** Denetim ayrıntısı: değişen açık seçimlerin ÖZETİ (adlar/parametreler değil — sayılar ve açılış sekmesi). */
    private static String summary(Map<String, Object> prefs, java.util.List<String> keys) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            Object v = prefs.get(k);
            String val;
            if (UserPreferencesService.LANDING_TAB.equals(k)) val = v == null ? "null" : "\"" + String.valueOf(v).replace("\"", "") + "\"";
            else if (v instanceof java.util.List<?> l) val = String.valueOf(l.size());
            else if (v instanceof Map<?, ?> m) val = String.valueOf(m.values().stream().mapToInt(x -> x instanceof java.util.List<?> l ? l.size() : 0).sum());
            else val = "0";
            if (!first) sb.append(',');
            sb.append('"').append(k).append("\":").append(val);
            first = false;
        }
        return sb.append('}').toString();
    }

    /** Oturumdaki kullanıcı kimliği; yoksa kullanıcı adından çözülür. Oturumsuz → 403 (AuthInterceptor zaten 401 verir). */
    private long currentUserId(HttpSession session) {
        Object raw = session.getAttribute("userId");
        if (raw instanceof Number n && n.longValue() > 0) return n.longValue();
        Object username = session.getAttribute("username");
        if (username == null) throw new SecurityException("Not authenticated");
        return userService.findByUsername(String.valueOf(username))
                .map(u -> u.getId())
                .orElseThrow(() -> new SecurityException("Not authenticated"));
    }
}
