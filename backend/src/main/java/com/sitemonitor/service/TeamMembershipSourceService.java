package com.sitemonitor.service;

import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.UserTeamSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takım üyeliği KAYNAK izlerinin tek yazarı (bkz. {@link UserTeamSource}). Üyelik gerçeği
 * {@code app_user_teams}'te kalır; burası yalnız "neden üye?" sorusunu cevaplar ve güvenli budamaya
 * (yalnız AD'den türetilmiş üyelik) dayanak olur.
 *
 * <p>İz yazımı ASLA asıl işlemi düşürmez: tablo henüz yoksa (ilk açılış) ya da yazım başarısızsa
 * uyarı loglanır, üyelik değişikliği yine uygulanır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamMembershipSourceService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final UserTeamSourceRepository repo;

    /** Kullanıcının takım → kaynak izi (yoksa boş harita). */
    public Map<Long, UserTeamSource> sourcesOf(Long userId) {
        Map<Long, UserTeamSource> out = new HashMap<>();
        if (userId == null) return out;
        try {
            for (UserTeamSource s : repo.findByUserId(userId)) out.put(s.getTeamId(), s);
        } catch (RuntimeException e) {
            log.warn("Üyelik kaynak izi okunamadı (user={}): {}", userId, e.getMessage());
        }
        return out;
    }

    /** Kullanıcı kümesinin izleri: kullanıcı → (takım → iz). */
    public Map<Long, Map<Long, UserTeamSource>> sourcesOfUsers(Collection<Long> userIds) {
        Map<Long, Map<Long, UserTeamSource>> out = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return out;
        try {
            for (UserTeamSource s : repo.findByUserIdIn(userIds)) {
                out.computeIfAbsent(s.getUserId(), k -> new HashMap<>()).put(s.getTeamId(), s);
            }
        } catch (RuntimeException e) {
            log.warn("Üyelik kaynak izleri okunamadı: {}", e.getMessage());
        }
        return out;
    }

    /** Tek bir üyeliğin izini yaz/güncelle. */
    public void record(Long userId, Long teamId, String source, String detail, String by) {
        if (userId == null || teamId == null || source == null) return;
        try {
            repo.save(new UserTeamSource(userId, teamId, source, cap(detail), now(), cap120(by)));
        } catch (RuntimeException e) {
            log.warn("Üyelik kaynak izi yazılamadı (user={} team={} source={}): {}", userId, teamId, source, e.getMessage());
        }
    }

    /** Aynı kaynağı birden çok takıma yaz. */
    public void recordAll(Long userId, Collection<Long> teamIds, String source, String detail, String by) {
        if (teamIds == null) return;
        for (Long t : teamIds) record(userId, t, source, detail, by);
    }

    /** Artık üye olunmayan takımların izlerini sil. */
    public void forget(Long userId, Collection<Long> teamIds) {
        if (userId == null || teamIds == null || teamIds.isEmpty()) return;
        try {
            repo.deleteByUserIdAndTeamIdIn(userId, List.copyOf(teamIds));
        } catch (RuntimeException e) {
            log.warn("Üyelik kaynak izi silinemedi (user={} teams={}): {}", userId, teamIds, e.getMessage());
        }
    }

    /** Kullanıcı silinince tüm izleri. */
    public void forgetUser(Long userId) {
        if (userId == null) return;
        try {
            repo.deleteByUserId(userId);
        } catch (RuntimeException e) {
            log.warn("Üyelik kaynak izleri silinemedi (user={}): {}", userId, e.getMessage());
        }
    }

    /** İstek bağlamındaki oturumun kullanıcı adı; bağlam yoksa {@code SYSTEM}. */
    public static String currentActor() {
        try {
            var ra = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                var s = sra.getRequest().getSession(false);
                Object u = s != null ? s.getAttribute("username") : null;
                if (u != null) return u.toString();
            }
        } catch (Exception ignored) { /* bağlam yok */ }
        return "SYSTEM";
    }

    private static String cap(String s) {
        if (s == null) return null;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String cap120(String s) {
        if (s == null) return null;
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String now() {
        return ISO.format(Instant.now());
    }
}
