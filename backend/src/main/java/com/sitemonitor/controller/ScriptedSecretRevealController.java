package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.lockout.PersistedRowLimits;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sentetik izlemenin (k6) GİZLİ ortam değişkenini yöneticiye gösterir (2026-10-19, kullanıcı isteği: "sentetik izlemede
 * ortam değişkenleri gizli yapıldığında admin tarafından da görülemiyor. Admin kullanıcısı gizli bilgileri review
 * edebilmeli").
 *
 * <p>{@code POST /api/monitoring/scripted/{id}/env/{name}/reveal} → {@code {name, value}}: izlemenin GÜNCEL env'indeki tek
 * bir gizli değişken, sunucuda çözülür. POST (GET değil): önbelleğe alınmaz, önceden getirilmez; yanıt {@code no-store}.
 * Eski script sürümleri ÇÖZÜLMEZ.
 *
 * <p><b>Kim görebilir (kullanıcı kararı)</b> — {@link #canReveal}: GLOBAL yönetici ve izlemenin sahibi takımı KENDİ
 * kapsamıyla yöneten kapsamlı yönetici (müdür, rol ADMIN + {@code canManage}). Başka hiç kimse: takım üyesi / TEAM_ADMIN /
 * USER, AUDIT, 7/24 operatörü yok ve takım erişim paylaşımıyla ASLA (EDIT / DELETE paylaşımı dâhil) — yardımcı paylaşımla
 * genişleyen kapıları ({@code canOperateTeam}, {@code canDeleteTeamResource}, {@code canViewMonitoring}) kullanmaz.
 *
 * <p><b>Yanıtlar</b>: izleme yok ya da çağıran göremiyor → 404; görüyor ama gösteremez → 403 (denetimde ACCESS_DENIED);
 * değişken yok / gizli değil / değeri boş → 404 {@code NOT_FOUND}; şifreli değer çözülemiyor (anahtar değişmiş, kayıt
 * bozuk) → 409 {@code SECRET_UNREADABLE}; kullanıcı başına dakikada {@value #MAX_PER_MINUTE}, saatte
 * {@value #MAX_PER_HOUR} → 429 {@code RATE_LIMITED} + {@code Retry-After}. Sınır KÜME geneli (2026-10-23, çok pod Sürüm
 * 2a — MP-32): başarılı gösterimler denetim kaydından sayılır ({@link PersistedRowLimits#secretReveals}; yazım eşzamanlı)
 * — N pod'da N× gösterim artık yok. Sıra: ÖNCE küme sayımı (salt okuma; reddedilen istek pod kuyruğuna yazılmaz), SONRA
 * pod belleğindeki deneme kuyruğu (sayım okunamazsa tek kapı odur).
 * Başarılı her gösterim denetlenir
 * ({@code SCRIPTED_SECRET_REVEAL}: izleme kimliği + adı, takım, değişken ADI — değer ASLA). Değer günlüğe, denetime, hata
 * gövdesine, {@code notification_logs}'a ya da değişiklik geçmişine yazılmaz; {@code RequestLoggingFilter}
 * {@code /api/monitoring/scripted} gövdelerini zaten hiç yazmaz.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/scripted")
@RequiredArgsConstructor
public class ScriptedSecretRevealController {

    static final int MAX_PER_MINUTE = 10;
    static final int MAX_PER_HOUR = 30;
    static final long MINUTE_MS = 60_000L;
    static final long HOUR_MS = 3_600_000L;
    /** Env adı sınırı — daha uzun bir ad kayıtta zaten reddedilir; uzun girdi hiçbir aramaya / kayda girmez. */
    static final int MAX_NAME_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final ScriptedCheckerService scriptedChecker;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** Kullanıcı → son bir saatteki başarılı-kapı denemelerinin zamanları (pod başına; en çok {@value #MAX_PER_HOUR}). */
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    /**
     * Küme geneli sayım (MP-32) — pod kuyruğundan ÖNCE sorulur. İsteğe bağlı: {@code @WebMvcTest} dilimlerinde yok → yalnız
     * pod kuyruğu (20.165.1 davranışı); sayım okunamazsa ({@code null}) da yalnız pod kuyruğu.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PersistedRowLimits rowLimits;

    /** Test kancası. */
    void setRowLimits(PersistedRowLimits r) { this.rowLimits = r; }

    /**
     * Bu oturum {@code teamId} takımının izlemesindeki gizli değeri görebilir mi — uç kapısı ve satır bayrağı
     * ({@code can_reveal}) TEK kural. Global yönetici her takımda; kapsamlı yönetici yalnız KENDİ yönetim kapsamındaki
     * takımda ({@code manageTeamIds}; erişim paylaşımı bu listeye hiç girmez). Takımsız izleme yalnız global yöneticide.
     */
    public static boolean canReveal(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return true;
        return teamId != null && SessionScope.isScopedAdmin(session) && SessionScope.canManage(session, teamId);
    }

    @PostMapping("/{id}/env/{name}/reveal")
    public ResponseEntity<Map<String, Object>> reveal(@PathVariable Long id, @PathVariable String name, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        ScriptedMonitor m = scriptedMonitorRepo.findById(id).orElse(null);
        // Göremediği izlemenin varlığı da sızmasın: yok ile görünmez aynı 404
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId()))
            throw new NoSuchElementException(Msg.t(
                    "Sentetik izleme bulunamadı; silinmiş ya da sizin kapsamınızın dışında olabilir.",
                    "The synthetic monitor wasn't found; it may have been deleted or be outside your scope."));
        if (!canReveal(session, m.getTeamId()))
            throw new SecurityException(Msg.t(
                    "Gizli değeri yalnız global yönetici ve izlemenin takımını yöneten yönetici görebilir; takım erişim "
                            + "paylaşımı bu yetkiyi vermez.",
                    "Only a global administrator or the administrator who manages the monitor's team can see a secret "
                            + "value; team access sharing doesn't grant it."));
        if (name == null || name.isBlank() || name.length() > MAX_NAME_CHARS) throw notFound();

        // Önce küme geneli sayım (salt okuma; reddedilen istek pod kuyruğuna YAZILMAZ), sonra pod kuyruğu
        long retryAfterSec = clusterRetryAfter(session, System.currentTimeMillis());
        if (retryAfterSec <= 0) retryAfterSec = tryAcquire(userKey(session));
        if (retryAfterSec > 0) {
            Map<String, Object> body = errorBody("RATE_LIMITED", Msg.t(
                    "Çok sık gizli değer gösterimi — kullanıcı başına dakikada en çok " + MAX_PER_MINUTE + ", saatte en çok "
                            + MAX_PER_HOUR + " kez. " + retryAfterSec + " sn sonra yeniden deneyin.",
                    "Too many secret reveals — at most " + MAX_PER_MINUTE + " per minute and " + MAX_PER_HOUR
                            + " per hour per user. Try again in " + retryAfterSec + " s."));
            body.put("retry_after_seconds", retryAfterSec);
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(retryAfterSec))
                    .cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
        }

        ScriptedCheckerService.SecretLookup lookup = scriptedChecker.revealSecret(m, name);
        switch (lookup.status()) {
            case NOT_FOUND -> throw notFound();
            case UNREADABLE -> throw new GlobalExceptionHandler.CodedConflictException("SECRET_UNREADABLE", Msg.t(
                    "Kayıtlı gizli değer çözülemedi — şifreleme anahtarı değişmiş ya da kayıt bozulmuş olabilir. Değeri "
                            + "formda yeniden girip kaydedin.",
                    "The stored secret value couldn't be decrypted — the encryption key may have changed or the record is "
                            + "damaged. Enter the value again in the form and save."));
            default -> { /* OK */ }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("monitor_id", m.getId());
        detail.put("monitor", m.getName());
        detail.put("team_id", m.getTeamId());
        detail.put("variable", name);   // ADI — değer ASLA
        auditService.recordAction("SCRIPTED_SECRET_REVEAL", session, "SCRIPTED_MONITOR", String.valueOf(m.getId()),
                json(detail), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("value", lookup.value());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t(
                "Bu izlemede bu adla değeri kayıtlı gizli bir ortam değişkeni yok; değişken silinmiş, adı değişmiş ya da "
                        + "gizli olmaktan çıkarılmış olabilir. Formu kapatıp yeniden açın.",
                "This monitor has no secret environment variable with a stored value under this name; it may have been "
                        + "deleted, renamed or made non-secret. Close the form and open it again."));
    }

    /**
     * Kayan pencere: kabul edilirse 0, aksi hâlde yeniden denemeye kalan saniye (≥ 1). Dakikalık VE saatlik sınır aynı
     * kuyruktan okunur (kuyruk en çok {@value #MAX_PER_HOUR} zaman damgası tutar). Harita sınırlıdır.
     */
    long tryAcquire(String key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    /** Paket-özel: testler saati verir. */
    long tryAcquire(String key, long now) {
        synchronized (attempts) {
            if (attempts.size() > 1_000) {
                attempts.entrySet().removeIf(e -> {
                    Deque<Long> dq = e.getValue();
                    while (!dq.isEmpty() && now - dq.peekFirst() >= HOUR_MS) dq.pollFirst();
                    return dq.isEmpty();
                });
                if (attempts.size() > 10_000) attempts.clear();
            }
            Deque<Long> dq = attempts.computeIfAbsent(key, x -> new ArrayDeque<>());
            while (!dq.isEmpty() && now - dq.peekFirst() >= HOUR_MS) dq.pollFirst();
            if (dq.size() >= MAX_PER_HOUR) return secondsUntil(dq.peekFirst() + HOUR_MS, now);
            int inMinute = 0;
            Long oldestInMinute = null;
            for (java.util.Iterator<Long> it = dq.descendingIterator(); it.hasNext(); ) {
                long ts = it.next();
                if (now - ts >= MINUTE_MS) break;
                inMinute++;
                oldestInMinute = ts;
            }
            if (inMinute >= MAX_PER_MINUTE) return secondsUntil(oldestInMinute + MINUTE_MS, now);
            dq.addLast(now);
            return 0;
        }
    }

    /**
     * Küme geneli sınır (MP-32): bu kullanıcının son dakikadaki / saatteki BAŞARILI gösterimleri denetim kaydından. Sınır
     * doluysa yeniden denemeye kalan saniye (≥ 1), değilse ya da sayım okunamazsa 0. Denetim damgası saniyeye yuvarlıdır:
     * T anındaki satır kesim {@code ≥ T} oldukça sayılır → T + pencere + 1 sn'de düşer (en çok 1 sn daha sıkı).
     */
    long clusterRetryAfter(HttpSession session, long now) {
        if (rowLimits == null || session == null) return 0L;
        Long uid = longAttr(session.getAttribute("userId"));
        Object u = session.getAttribute("username");
        PersistedRowLimits.Window w = rowLimits.secretReveals(uid, u == null ? null : u.toString(), now, MINUTE_MS, HOUR_MS);
        if (w == null) return 0L;
        if (w.hour() >= MAX_PER_HOUR && w.hourOldestMs() != null) return secondsUntil(w.hourOldestMs() + HOUR_MS + 1_000L, now);
        if (w.minute() >= MAX_PER_MINUTE && w.minuteOldestMs() != null) return secondsUntil(w.minuteOldestMs() + MINUTE_MS + 1_000L, now);
        return 0L;
    }

    private static Long longAttr(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long secondsUntil(long at, long now) {
        return Math.max(1L, (at - now + 999L) / 1000L);
    }

    private static String userKey(HttpSession session) {
        Object uid = session != null ? session.getAttribute("userId") : null;
        if (uid != null) return "u:" + uid;
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? "n:" + u : "s:" + (session != null ? session.getId() : "anon");
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        return body;
    }

    private static String json(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return String.valueOf(m.get("monitor_id"));
        }
    }
}
