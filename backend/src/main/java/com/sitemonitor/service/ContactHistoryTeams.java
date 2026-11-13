package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eskalasyon kişisi denetim satırının KENDİ ZAMANINDAKİ takımı (2026-11-13 hata taraması). Kişi toplu işlemle ya da tekil
 * düzenlemeyle başka takıma taşınabilir; satırın takımı kişinin CANLI takımından okunursa A takımı dönemindeki geçmiş B'nin
 * müdürüne görünür, A'nın müdürü taşıma satırını bile göremezdi. Bildirim gruplarındaki kuralın aynısı
 * ({@link NotificationGroupHistoryService#rowTeams}):
 * <ol>
 *   <li>Taşıma satırı ({@code changes} içinde {@code teamId} farkı) → gösterim yeni takım, görünür eski + yeni takım.</li>
 *   <li>Düz {@code teamId} taşıyan anlık görüntü satırı → o anki takım.</li>
 *   <li>Diğerleri → kendinden SONRAKİ ilk taşımanın {@code from}'u; sonra taşıma yoksa canlı takım, kişi silinmişse kendinden
 *       ÖNCEKİ son taşımanın {@code to}'su.</li>
 * </ol>
 * Taşıma izi okunan satırlardan bağımsız olarak kişinin GÜNCELLEME satırlarından TEK sorguyla okunur (tavanlı) — akışın kişi /
 * tarih süzgeci taşıma satırını dışarıda bıraksa da eski satır yanlış takıma yazılmaz. Takımı çözülemeyen satır {@code null}
 * (kapsamlı görüntüleyiciye gösterilmez).
 */
final class ContactHistoryTeams {

    static final String RESOURCE = "ESCALATION_CONTACT";
    private static final String UPDATE_EVENT = "CONTACT_UPDATE";
    /** Taşıma izi okuma tavanı. */
    static final int MOVES_MAX = 5000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContactHistoryTeams() {}

    /**
     * @param rows  kişi denetim satırları (başka kaynak türü yok sayılır)
     * @param live  canlı kişi kimliği → takım (silinmiş kişi haritada yok)
     * @return denetim satırı kimliği → satırın takımı (çözülemeyen satır haritada yok)
     */
    static Map<Long, NotificationGroupHistoryService.RowTeams> rowTeams(List<AuditLog> rows, Map<Long, Long> live,
                                                                       AuditLogRepository auditRepo) {
        Map<Long, NotificationGroupHistoryService.RowTeams> out = new HashMap<>();
        if (rows == null || rows.isEmpty()) return out;
        Set<String> ids = new HashSet<>();
        for (AuditLog r : rows) {
            if (RESOURCE.equals(r.getResourceType()) && AdminHistoryService.parseLong(r.getResourceId()) != null) {
                ids.add(r.getResourceId().trim());
            }
        }
        Map<Long, List<Long[]>> moves = new HashMap<>();   // kişi → [denetim kimliği, from, to] (kimliğe göre artan)
        if (!ids.isEmpty() && auditRepo != null) {
            List<AuditLog> trail;
            try {
                // Yalnız takım değişikliği satırları, en yeni önce (tavan aşılırsa en ESKİ taşımalar düşer — rastgele değil)
                trail = auditRepo.findByResourceTypeAndEventTypeAndResourceIdInAndChangesContaining(RESOURCE, UPDATE_EVENT, ids,
                        "\"teamId\"", PageRequest.of(0, MOVES_MAX, org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id")));
            } catch (RuntimeException e) {
                trail = List.of();               // iz okunamazsa eski kural (canlı takım) — satır yine listelenir
            }
            for (AuditLog m : trail == null ? List.<AuditLog>of() : trail) {
                Long cid = AdminHistoryService.parseLong(m.getResourceId());
                Long[] mv = moveOf(m.getChanges());
                if (cid == null || mv == null || m.getId() == null) continue;
                moves.computeIfAbsent(cid, k -> new ArrayList<>()).add(new Long[] { m.getId(), mv[1], mv[2] });
            }
            for (List<Long[]> l : moves.values()) l.sort((a, b) -> Long.compare(a[0], b[0]));
        }
        for (AuditLog r : rows) {
            if (r.getId() == null || !RESOURCE.equals(r.getResourceType())) continue;
            Long cid = AdminHistoryService.parseLong(r.getResourceId());
            Long[] own = moveOf(r.getChanges());
            if (own != null) {
                Set<Long> vis = new LinkedHashSet<>();
                if (own[1] != null) vis.add(own[1]);
                if (own[2] != null) vis.add(own[2]);
                out.put(r.getId(), new NotificationGroupHistoryService.RowTeams(own[2], vis));
                continue;
            }
            Long team = flatTeam(r.getChanges());
            if (team == null && cid != null) {
                List<Long[]> list = moves.getOrDefault(cid, List.of());
                boolean later = false;
                for (Long[] m : list) {
                    if (m[0] > r.getId()) { team = m[1]; later = true; break; }
                }
                if (!later) {
                    if (live != null && live.containsKey(cid)) team = live.get(cid);
                    else {
                        for (Long[] m : list) if (m[0] < r.getId()) team = m[2];   // silinmiş kişi: son taşımanın hedefi
                    }
                }
            }
            if (team == null) team = legacyTeam(r.getChanges());   // JSON olmayan eski kayıt (önceki kural)
            if (team != null) out.put(r.getId(), new NotificationGroupHistoryService.RowTeams(team, Set.of(team)));
        }
        return out;
    }

    /** Taşıma farkı ({@code {"teamId":{"from":A,"to":B}}}) → {null, A, B} (A null olabilir — takımsız eski kişi); değilse null. */
    static Long[] moveOf(String changes) {
        if (changes == null || changes.isBlank() || !changes.contains("teamId")) return null;
        try {
            JsonNode n = JSON.readTree(changes).get("teamId");
            if (n == null || !n.isObject() || !n.has("to")) return null;
            JsonNode from = n.get("from"), to = n.get("to");
            Long f = from != null && from.canConvertToLong() ? from.asLong() : null;
            Long t = to != null && to.canConvertToLong() ? to.asLong() : null;
            if (f == null && t == null) return null;
            return new Long[] { null, f, t };
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern TEAM_IN = java.util.regex.Pattern.compile("\"teamId\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern TEAM_TO = java.util.regex.Pattern.compile("\"teamId\"\\s*:\\s*\\{[^}]*\"to\"\\s*:\\s*(\\d+)");

    /** Ayrıştırılamayan eski {@code changes} metnindeki teamId (önce fark hedefi, sonra düz değer); yoksa null. */
    static Long legacyTeam(String changes) {
        if (changes == null || changes.length() > 20_000 || !changes.contains("teamId")) return null;
        java.util.regex.Matcher to = TEAM_TO.matcher(changes);
        if (to.find()) return Long.valueOf(to.group(1));
        java.util.regex.Matcher m = TEAM_IN.matcher(changes);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /** Anlık görüntü satırının düz {@code teamId}'si (fark biçimi değil); yoksa null. */
    static Long flatTeam(String changes) {
        if (changes == null || changes.isBlank() || !changes.contains("teamId")) return null;
        try {
            JsonNode n = JSON.readTree(changes).get("teamId");
            return n != null && !n.isObject() && n.canConvertToLong() ? n.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
