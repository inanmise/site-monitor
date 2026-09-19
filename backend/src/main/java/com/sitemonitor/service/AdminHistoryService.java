package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yönetim Paneli "Değişiklik Geçmişi" (2026-09-20): eşik / eskalasyon kişisi / takım / kullanıcı için
 * "kim, ne zaman, neyi değiştirdi" — kaynak denetim kaydı ({@code audit_log}), sonradan düzenlenemez,
 * silinmiş kayıtlar da listede kalır.
 *
 * <p>{@link NotificationGroupHistoryService} deseninin genellenmiş hâli. Takım kapsamı: TEAM için
 * kaynak kimliği takımın kendisidir; ESCALATION_CONTACT için kişinin takımı (canlı satır ya da anlık
 * görüntüdeki {@code teamId}). USER ve ALERT_THRESHOLD kapsamlanmaz — denetleyici bunları yalnız global
 * yöneticiye açar.
 */
@Service
@RequiredArgsConstructor
public class AdminHistoryService {

    public static final Set<String> RESOURCES = Set.of("ALERT_THRESHOLD", "ESCALATION_CONTACT", "TEAM", "USER");
    /** Kapsamlı (takım bazlı) kullanıcıya açılabilen kaynaklar. */
    public static final Set<String> TEAM_SCOPED = Set.of("ESCALATION_CONTACT", "TEAM");

    static final int WINDOW_MAX = 2_000;
    private static final Pattern TEAM_IN_CHANGES = Pattern.compile("\"teamId\"\\s*:\\s*(\\d+)");
    private static final Pattern TEAM_TO_IN_CHANGES = Pattern.compile("\"teamId\"\\s*:\\s*\\{[^}]*\"to\"\\s*:\\s*(\\d+)");

    private final AuditLogRepository auditRepo;
    private final EscalationContactRepository contactRepo;

    public record Entry(AuditLog row, Long teamId) {}
    public record History(List<Entry> items, boolean truncated, int hidden) {}

    /**
     * @param resource   ALERT_THRESHOLD | ESCALATION_CONTACT | TEAM | USER
     * @param resourceId yalnız bu kaydın geçmişi ({@code null} = hepsi)
     * @param teamIds    görülebilir takımlar; {@code null} = sınırsız (global görücü)
     */
    public History history(String resource, String resourceId, Collection<Long> teamIds, int limit) {
        if (!RESOURCES.contains(resource)) throw new IllegalArgumentException("Bilinmeyen kaynak: " + resource);
        int cap = Math.max(1, Math.min(limit, 200));
        int window = Math.min(Math.max(cap * 5, 200), WINDOW_MAX);

        List<AuditLog> scanned = resourceId != null && !resourceId.isBlank()
                ? auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(resource, resourceId, PageRequest.of(0, window))
                : auditRepo.findByResourceTypeOrderByEventTimeDesc(resource, PageRequest.of(0, window));

        boolean unrestricted = teamIds == null;
        Set<Long> allowed = unrestricted ? Set.of() : new HashSet<>(teamIds);
        Map<Long, Long> contactTeams = "ESCALATION_CONTACT".equals(resource) ? liveContactTeams() : Map.of();

        List<Entry> out = new ArrayList<>();
        int hidden = 0;
        boolean more = false;
        for (AuditLog r : scanned) {
            Long team = teamOf(resource, r, contactTeams);
            if (!unrestricted && TEAM_SCOPED.contains(resource)) {
                // Kapalı tarafa düş: takımı bilinmeyen satır kapsamlı kullanıcıya gösterilmez.
                if (team == null || !allowed.contains(team)) { if (team == null) hidden++; continue; }
            }
            if (out.size() >= cap) { more = true; break; }
            out.add(new Entry(r, team));
        }
        return new History(out, more || scanned.size() >= window, hidden);
    }

    private Map<Long, Long> liveContactTeams() {
        Map<Long, Long> m = new HashMap<>();
        try {
            contactRepo.findAll().forEach(c -> { if (c.getId() != null) m.put(c.getId(), c.getTeamId()); });
        } catch (RuntimeException ignore) { /* geçmiş yine listelenir; takımı çözülemeyen satır gizlenir */ }
        return m;
    }

    /** Satırın takımı: TEAM → kaynak kimliği; kişi → canlı satır, yoksa anlık görüntü/fark içindeki teamId. */
    static Long teamOf(String resource, AuditLog r, Map<Long, Long> contactTeams) {
        Long rid = parseLong(r.getResourceId());
        if ("TEAM".equals(resource)) return rid;
        if ("ESCALATION_CONTACT".equals(resource)) {
            if (rid != null && contactTeams.containsKey(rid)) return contactTeams.get(rid);
            String ch = r.getChanges();
            if (ch != null) {
                Matcher to = TEAM_TO_IN_CHANGES.matcher(ch);
                if (to.find()) return Long.valueOf(to.group(1));
                Matcher m = TEAM_IN_CHANGES.matcher(ch);
                if (m.find()) return Long.valueOf(m.group(1));
            }
        }
        return null;
    }

    static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
