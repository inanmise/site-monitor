package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ürün turu durumu (2026-09-13): kullanıcı başına, SUNUCUDA — "bir daha gösterme" başka tarayıcı /
 * bilgisayardan girince de geçerli olsun. {@code app_users.tour_state} JSON:
 *
 * <pre>{ "status": "completed|dismissed|snoozed|started", "version": 1, "last_step": "cards",
 *   "snoozed": 2, "seen_pages": ["all","forecast"], "checklist": {"tour": true, "card": true},
 *   "updated_at": "2026-09-13T10:00:00" }</pre>
 *
 * <p>Kurallar: {@code dismissed} kalıcıdır (yalnız kullanıcı "yeniden başlat" ya da yönetici "sıfırla"
 * ile döner); {@code snoozed} en çok {@link #SNOOZE_MAX} kez sorulur; {@code version} tur içeriği
 * büyüyünce "yenilikler" turunu tetikler (istemci karşılaştırır). Bilinmeyen alanlar yazılmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TourStateService {

    public static final int SNOOZE_MAX = 3;
    public static final Set<String> STATUSES = Set.of("started", "completed", "dismissed", "snoozed");
    private static final int PAGE_KEY_MAX = 40, PAGES_MAX = 100, STEP_MAX = 60, CHECKLIST_MAX = 20;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppUserRepository userRepo;
    /** Statik: özet ve çözümleme enjeksiyonsuz yerlerden (UserActivityService) de çağrılır. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** JSON → harita (bozuk/boş → null; istemci "hiç görmedi" sayar). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object o = JSON.readValue(json, Object.class);
            return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null;
        } catch (Exception e) {
            log.debug("tour_state çözülemedi: {}", e.toString());
            return null;
        }
    }

    /**
     * Yamayı mevcut duruma uygular ve kaydeder. Döner: yeni durum (null = sıfırlandı).
     * {@code reset=true} tüm durumu siler (kullanıcı "turu yeniden başlat" ya da yönetici sıfırlama).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(AppUser user, Map<String, Object> patch, boolean reset) {
        if (reset) {
            user.setTourState(null);
            userRepo.save(user);
            return null;
        }
        Map<String, Object> cur = parse(user.getTourState());
        if (cur == null) cur = new LinkedHashMap<>();
        Object st = patch.get("status");
        if (st != null) {
            String s = String.valueOf(st);
            if (!STATUSES.contains(s)) throw new IllegalArgumentException("status must be one of " + STATUSES);
            // dismissed kalıcı: "snoozed/started" onu ezmez (yanlış tıklamayla tur geri gelmesin)
            if (!"dismissed".equals(cur.get("status")) || "completed".equals(s) || "dismissed".equals(s)) cur.put("status", s);
            if ("snoozed".equals(s)) cur.put("snoozed", Math.min(SNOOZE_MAX, toInt(cur.get("snoozed")) + 1));
        }
        if (patch.get("version") != null) cur.put("version", Math.max(0, toInt(patch.get("version"))));
        if (patch.get("last_step") != null) cur.put("last_step", trunc(String.valueOf(patch.get("last_step")), STEP_MAX));
        if (patch.get("seen_page") != null) {
            String page = trunc(String.valueOf(patch.get("seen_page")), PAGE_KEY_MAX);
            if (page.matches("[a-z0-9-]{1,40}")) {
                List<String> pages = new ArrayList<>();
                if (cur.get("seen_pages") instanceof List<?> l) for (Object o : l) if (o != null) pages.add(String.valueOf(o));
                if (!pages.contains(page)) pages.add(page);
                if (pages.size() > PAGES_MAX) pages = pages.subList(pages.size() - PAGES_MAX, pages.size());
                cur.put("seen_pages", pages);
            }
        }
        if (patch.get("checklist") instanceof Map<?, ?> cl) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (cur.get("checklist") instanceof Map<?, ?> old) old.forEach((k, v) -> merged.put(String.valueOf(k), v));
            for (Map.Entry<?, ?> e : cl.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.matches("[a-z0-9-]{1,30}") && merged.size() < CHECKLIST_MAX) merged.put(k, Boolean.TRUE.equals(e.getValue()));
            }
            cur.put("checklist", merged);
        }
        if (patch.get("checklist_hidden") != null) cur.put("checklist_hidden", Boolean.TRUE.equals(patch.get("checklist_hidden")));
        cur.put("updated_at", ISO.format(Instant.now()));
        try {
            user.setTourState(JSON.writeValueAsString(cur));
        } catch (Exception e) {
            throw new IllegalArgumentException("tour_state yazılamadı: " + e.getMessage());
        }
        userRepo.save(user);
        return cur;
    }

    /** Yönetici özeti: aktif kullanıcılar arasında tamamlayan / kapatan / hiç görmeyen. */
    public static Map<String, Integer> summarize(Collection<AppUser> users) {
        int completed = 0, dismissed = 0, none = 0, other = 0;
        for (AppUser u : users) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            Map<String, Object> s = parse(u.getTourState());
            String st = s == null ? null : String.valueOf(s.get("status"));
            if (st == null || "null".equals(st)) none++;
            else if ("completed".equals(st)) completed++;
            else if ("dismissed".equals(st)) dismissed++;
            else other++;
        }
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("completed", completed);
        m.put("dismissed", dismissed);
        m.put("pending", other);
        m.put("none", none);
        return m;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? 0 : Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private static String trunc(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
