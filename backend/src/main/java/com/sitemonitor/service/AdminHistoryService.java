package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * <p><b>Olay türü beyaz listesi.</b> {@code resource_type = USER} satırlarını yalnız yönetici değişiklikleri
 * değil, HER giriş/çıkış da üretir (LOGIN_SUCCESS/LOGIN_FAILED/LOGOUT, tur olayları, push ayarları). İlk sürüm
 * kaynak türüne göre ham okuyunca "Kullanıcılar geçmişi" giriş kaydı yağmuruna dönüştü ve asıl değişiklikler
 * görünmedi. Şimdi kaynak başına yalnız YÖNETİMSEL değişiklik olayları listelenir; arayüz bu listeyi süzgeç
 * çipi olarak da kullanır.
 *
 * <p>Sayfalama: global görücü için veritabanı sayfası (toplam sayı gerçek); takım kapsamlı kullanıcı için
 * pencere okunup satır satır süzülür ve bellekte sayfalanır (takımı bilinmeyen satır gizlenir, sayısı döner).
 */
@Service
@RequiredArgsConstructor
public class AdminHistoryService {

    public static final Set<String> RESOURCES = Set.of("ALERT_THRESHOLD", "ESCALATION_CONTACT", "TEAM", "USER");
    /** Kapsamlı (takım bazlı) kullanıcıya açılabilen kaynaklar. */
    public static final Set<String> TEAM_SCOPED = Set.of("ESCALATION_CONTACT", "TEAM");

    /** Kaynak → listelenen olay türleri (sıra = arayüzdeki süzgeç çipi sırası). */
    public static final Map<String, List<String>> EVENT_TYPES = Map.of(
            "ALERT_THRESHOLD", List.of("THRESHOLD_CREATE", "THRESHOLD_UPDATE", "THRESHOLD_DELETE"),
            "ESCALATION_CONTACT", List.of("CONTACT_CREATE", "CONTACT_UPDATE", "CONTACT_DELETE", "CONTACT_WEBHOOK_TEST"),
            "TEAM", List.of("TEAM_CREATE", "TEAM_UPDATE", "TEAM_BULK_UPDATE", "TEAM_DELETE", "TEAM_WEEKLY_NOTIFICATIONS", "TEAM_MOVE_ASSETS",
                    "TEAM_MEMBER_ADD", "TEAM_MEMBER_REMOVE", "WEEKLY_REPORT_ACCESS"),
            "USER", List.of("USER_CREATE", "USER_UPDATE", "USER_DELETE", "USER_BULK_UPDATE", "USER_PASSWORD_AUTO_RESET",
                    "USER_UNLOCK", "USER_ROLE_UNLOCK", "USER_TEAM_UNLOCK", "USER_ORG_ROLE_UNLOCK", "USER_TOUR_RESET",
                    "ACCOUNT_LOCKED", "SELF_PASSWORD_CHANGE", "SESSION_TERMINATE"));

    static final int WINDOW_MAX = 2_000;
    static final int SIZE_MAX = 200;
    private static final Pattern TEAM_IN_CHANGES = Pattern.compile("\"teamId\"\\s*:\\s*(\\d+)");
    private static final Pattern TEAM_TO_IN_CHANGES = Pattern.compile("\"teamId\"\\s*:\\s*\\{[^}]*\"to\"\\s*:\\s*(\\d+)");

    private final AuditLogRepository auditRepo;
    private final EscalationContactRepository contactRepo;

    public record Entry(AuditLog row, Long teamId) {}
    /** @param total  toplam satır (kapsamlı kullanıcıda pencere içindeki görünür satır sayısı) */
    public record History(List<Entry> items, long total, int page, int size, boolean truncated, int hidden) {
        public int totalPages() { return size <= 0 ? 0 : (int) Math.ceil(total / (double) size); }
    }

    /**
     * @param resource   ALERT_THRESHOLD | ESCALATION_CONTACT | TEAM | USER
     * @param resourceId yalnız bu kaydın geçmişi ({@code null} = hepsi)
     * @param types      olay türü süzgeci ({@code null}/boş = kaynağın tüm beyaz listesi); beyaz liste dışı yok sayılır
     * @param teamIds    görülebilir takımlar; {@code null} = sınırsız (global görücü)
     */
    public History history(String resource, String resourceId, Collection<String> types, Collection<Long> teamIds,
                           int page, int size) {
        if (!RESOURCES.contains(resource)) throw new IllegalArgumentException("Bilinmeyen kaynak: " + resource);
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, SIZE_MAX));
        List<String> allowed = EVENT_TYPES.get(resource);
        List<String> wanted = new ArrayList<>();
        if (types != null) for (String t : types) if (t != null && allowed.contains(t)) wanted.add(t);
        if (wanted.isEmpty()) wanted = allowed;
        boolean byId = resourceId != null && !resourceId.isBlank();
        Sort sort = Sort.by(Sort.Direction.DESC, "eventTime", "id");

        boolean unrestricted = teamIds == null || !TEAM_SCOPED.contains(resource);
        if (unrestricted) {
            Page<AuditLog> pg = byId
                    ? auditRepo.findByResourceTypeAndResourceIdAndEventTypeIn(resource, resourceId, wanted, PageRequest.of(p, s, sort))
                    : auditRepo.findByResourceTypeAndEventTypeIn(resource, wanted, PageRequest.of(p, s, sort));
            Map<Long, Long> contactTeams = "ESCALATION_CONTACT".equals(resource) ? liveContactTeams() : Map.of();
            List<Entry> out = new ArrayList<>();
            for (AuditLog r : pg.getContent()) out.add(new Entry(r, teamOf(resource, r, contactTeams)));
            return new History(out, pg.getTotalElements(), p, s, false, 0);
        }

        // Kapsamlı: pencere + bellek içi süzme/sayfalama.
        Set<Long> allowedTeams = new HashSet<>(teamIds);
        List<AuditLog> scanned = byId
                ? auditRepo.findByResourceTypeAndResourceIdAndEventTypeIn(resource, resourceId, wanted, PageRequest.of(0, WINDOW_MAX, sort)).getContent()
                : auditRepo.findByResourceTypeAndEventTypeIn(resource, wanted, PageRequest.of(0, WINDOW_MAX, sort)).getContent();
        Map<Long, Long> contactTeams = "ESCALATION_CONTACT".equals(resource) ? liveContactTeams() : Map.of();
        List<Entry> visible = new ArrayList<>();
        int hidden = 0;
        for (AuditLog r : scanned) {
            Long team = teamOf(resource, r, contactTeams);
            // Kapalı tarafa düş: takımı bilinmeyen satır kapsamlı kullanıcıya gösterilmez.
            if (team == null) { hidden++; continue; }
            if (!allowedTeams.contains(team)) continue;
            visible.add(new Entry(r, team));
        }
        int from = Math.min(p * s, visible.size());
        int to = Math.min(from + s, visible.size());
        return new History(new ArrayList<>(visible.subList(from, to)), visible.size(), p, s, scanned.size() >= WINDOW_MAX, hidden);
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

    /** Arayüz süzgeç çipleri için: kaynağın olay türleri (beyaz liste sırası). */
    public static List<String> eventTypesFor(String resource) {
        return EVENT_TYPES.getOrDefault(resource, List.of());
    }
}
