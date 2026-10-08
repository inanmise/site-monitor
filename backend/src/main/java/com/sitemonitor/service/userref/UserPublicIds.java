package com.sitemonitor.service.userref;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sitemonitor.controller.SessionScope;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kullanıcının OPAK kimliği ({@code app_users.public_id}, UUID) — 2026-10-08, kullanıcı kararı: "bir kullanıcı başka bir
 * kullanıcının id'sini okuyamasın". Sıralı sayısal id ile kullanıcı taraması ({@code /api/users/5/photo}, {@code 6},
 * {@code 7} …) kapanır.
 *
 * <p><b>Kural.</b> GLOBAL admin sayısal id görür ve gönderebilir (bugünkü davranış, bayt bayt aynı). Diğer herkes —
 * AUDIT ve kapsamlı müdür DAHİL (kullanıcı kararı) — yanıtlarda yalnız opak kimliği görür
 * ({@link com.sitemonitor.config.UserRefResponseAdvice}) ve uçlara yalnız opak kimlik gönderebilir ({@link #resolve(UserPublicIds, Object,
 * HttpSession)}); sayısal girdi onlar için "bulunamadı" sayılır.
 *
 * <p><b>Kalıcılık.</b> Kimlik bir kez atanır ve değişmez (yeni satırda {@code AppUser#assignPublicId}, eski satırlarda
 * {@link #backfill()} / ilk okumada koşullu UPDATE — iki pod aynı satıra yazarsa {@code public_id IS NULL} koşulu tek
 * kazanan bırakır). Değişmediği için önbellek süresizdir (kullanıcı sayısı kadar giriş).
 */
@Slf4j
@Service
public class UserPublicIds {

    /** Kabul edilen biçim — küçük harfli UUID (girdi kırpılıp küçültülür). */
    public static final Pattern FORMAT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,18}$");

    private final JdbcTemplate jdbc;
    private final Cache<Long, String> byId = Caffeine.newBuilder().maximumSize(200_000).build();
    private final Cache<String, Long> byPublic = Caffeine.newBuilder().maximumSize(200_000).build();

    public UserPublicIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String newPublicId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Açılışta: kimliği olmayan satırlara kimlik verir ve tüm eşlemeyi önbelleğe alır (kullanıcı tablosu küçük —
     * tek sorgu). Hata açılışı durdurmaz; eksik kalan satır ilk okumada {@link #publicIdOf} ile tamamlanır.
     */
    public int backfill() {
        int assigned = 0;
        try {
            List<Long> missing = jdbc.queryForList("SELECT id FROM app_users WHERE public_id IS NULL", Long.class);
            for (Long id : missing) {
                assigned += jdbc.update("UPDATE app_users SET public_id = ? WHERE id = ? AND public_id IS NULL",
                        newPublicId(), id);
            }
            jdbc.query("SELECT id, public_id FROM app_users WHERE public_id IS NOT NULL", rs -> {
                remember(rs.getLong(1), rs.getString(2));
            });
            if (assigned > 0) log.info("Kullanıcı opak kimliği: {} eski hesaba kimlik verildi", assigned);
        } catch (RuntimeException e) {
            log.warn("Kullanıcı opak kimliği doldurulamadı (ilk okumada tamamlanır): {}", e.toString());
        }
        return assigned;
    }

    /** Sayısal id → opak kimlik; satırda yoksa atar. Kullanıcı yoksa (silinmiş) null. */
    public String publicIdOf(Long id) {
        if (id == null) return null;
        String hit = byId.getIfPresent(id);
        if (hit != null) return hit;
        List<String> rows = jdbc.queryForList("SELECT public_id FROM app_users WHERE id = ?", String.class, id);
        if (rows.isEmpty()) return null;
        String pid = rows.get(0);
        if (pid == null) {
            jdbc.update("UPDATE app_users SET public_id = ? WHERE id = ? AND public_id IS NULL", newPublicId(), id);
            List<String> again = jdbc.queryForList("SELECT public_id FROM app_users WHERE id = ?", String.class, id);
            pid = again.isEmpty() ? null : again.get(0);
        }
        if (pid != null) remember(id, pid);
        return pid;
    }

    /** Opak kimlik → sayısal id; biçim bozuksa ya da kullanıcı yoksa null. */
    public Long idOf(String publicId) {
        if (publicId == null) return null;
        String p = publicId.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(p).matches()) return null;
        Long hit = byPublic.getIfPresent(p);
        if (hit != null) return hit;
        List<Long> rows = jdbc.queryForList("SELECT id FROM app_users WHERE public_id = ?", Long.class, p);
        if (rows.isEmpty()) return null;
        Long id = rows.get(0);
        remember(id, p);
        return id;
    }

    /**
     * İstemciden gelen kullanıcı referansı → sayısal id. Opak kimlik herkesten kabul edilir; sayısal değer (sayı ya da
     * yalnız rakam metni) YALNIZ {@code numericAllowed} iken. Geçersiz / bilinmeyen / izinsiz → null.
     */
    public Long resolve(Object raw, boolean numericAllowed) {
        if (raw == null) return null;
        if (raw instanceof Number n) return numericAllowed ? Long.valueOf(n.longValue()) : null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        if (DIGITS.matcher(s).matches()) return numericAllowed ? Long.valueOf(Long.parseLong(s)) : null;
        return idOf(s);
    }

    /**
     * Uç yardımcısı: GLOBAL admin sayısal da gönderebilir. {@code svc == null} yalnız bean'siz birim testlerinde olur
     * (denetleyici elle kurulur) — o zaman eski sayısal ayrıştırma korunur.
     */
    public static Long resolve(UserPublicIds svc, Object raw, HttpSession session) {
        if (svc == null) return legacyNumeric(raw);
        return svc.resolve(raw, SessionScope.isGlobalAdmin(session));
    }

    /** Bean'siz (birim testi) yol: yalnız sayısal biçim. */
    static Long legacyNumeric(Object raw) {
        if (raw instanceof Number n) return Long.valueOf(n.longValue());
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return DIGITS.matcher(s).matches() ? Long.valueOf(Long.parseLong(s)) : null;
    }

    /** Toplu önbellek ısıtma için (testler) — dışarıdan atanmış eşleme. */
    void remember(long id, String publicId) {
        if (publicId == null) return;
        String p = publicId.toLowerCase(Locale.ROOT);
        byId.put(id, p);
        byPublic.put(p, id);
    }

    /** Teşhis: önbellekteki eşleme sayısı. */
    public Map<String, Long> stats() {
        return Map.of("cached", byId.estimatedSize());
    }
}
