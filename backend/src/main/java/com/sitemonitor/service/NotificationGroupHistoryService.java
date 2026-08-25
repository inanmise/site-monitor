package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bildirim gruplarının DEĞİŞİKLİK GEÇMİŞİ — "kim, ne zaman, neyi değiştirdi/ekledi/sildi".
 *
 * <p><b>Neden ayrı bir uç:</b> kayıtların kaynağı {@code audit_log} (hash zinciri korumalı, tek
 * doğruluk kaynağı) ama {@code /api/admin/audit/*} uçları {@code audit_log.read} + global admin
 * ister. Bildirim Grupları ekranı {@code notification.groups} ile açılır; ikisi ayrık yetkilerdir.
 * Ekranı görebilen bir takım sorumlusu, kendi takımının grup geçmişini de görebilmelidir — ama
 * BAŞKA takımınkini asla. Saklama süresi geçmişinde ({@code RetentionChangeLog}) aynı gerekçeyle
 * aynı desen uygulanmış; burada da o izlenir.
 *
 * <p><b>Takım kapsamı silinmiş grupta da çözülmelidir.</b> Denetim satırı yalnız grup kimliğini
 * taşır; grup satırı silinince "bu kayıt hangi takımın?" sorusu canlı tablodan cevaplanamaz —
 * oysa kullanıcının en çok görmek istediği kayıt tam da silme kaydıdır. Kimlik→takım eşlemesi bu
 * yüzden üç kaynaktan kurulur: canlı gruplar, OLUŞTURMA anlık görüntüsü ve SİLME anlık görüntüsü.
 * Takımı çözülemeyen satır kapsamlı kullanıcıya GÖSTERİLMEZ (kapalı tarafa düşer) ve sayısı
 * ayrıca bildirilir — sessizce yutmak, eksik bir geçmişi tam sanmaya yol açardı.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationGroupHistoryService {

    public static final String RESOURCE = "NOTIFICATION_GROUP";

    /** Takım süzgeci bellekte uygulandığı için taranan pencere sınırlanır; sınır AÇIKÇA raporlanır. */
    private static final int WINDOW_MAX = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditLogRepository auditRepo;
    private final NotificationGroupRepository groupRepo;

    /** Tek satır: denetim kaydı + çözülmüş takım kimliği (null = çözülemedi). */
    public record Entry(AuditLog row, Long teamId) {}

    /**
     * @param items      görüntülenecek satırlar (yeniden eskiye)
     * @param truncated  pencere doldu; daha eski kayıtlar var olabilir
     * @param hidden     takımı çözülemediği için gizlenen satır sayısı
     */
    public record History(List<Entry> items, boolean truncated, int hidden) {}

    /**
     * @param teamIds  görülebilir takımlar; {@code null} = sınırsız (global görücü)
     * @param groupId  yalnız bu grubun geçmişi ({@code null} = hepsi)
     */
    public History history(Collection<Long> teamIds, Long groupId, int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        int window = Math.min(Math.max(cap * 5, 200), WINDOW_MAX);

        List<AuditLog> scanned = groupId != null
                ? auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                        RESOURCE, String.valueOf(groupId), PageRequest.of(0, window))
                : auditRepo.findByResourceTypeOrderByEventTimeDesc(RESOURCE, PageRequest.of(0, window));

        Map<Long, Long> teamOf = resolveTeams(scanned);
        boolean unrestricted = teamIds == null;
        Set<Long> allowed = unrestricted ? Set.of() : new HashSet<>(teamIds);

        List<Entry> out = new ArrayList<>();
        int hidden = 0;
        boolean more = false;
        for (AuditLog r : scanned) {
            Long team = teamOf.get(parseLong(r.getResourceId()));
            // Kapalı tarafa düş: takımı bilinmeyen satır, kapsamlı kullanıcıya gösterilmez.
            if (!unrestricted && (team == null || !allowed.contains(team))) {
                if (team == null) hidden++;
                continue;
            }
            if (out.size() >= cap) { more = true; break; }
            out.add(new Entry(r, team));
        }
        return new History(out, more || scanned.size() >= window, hidden);
    }

    /**
     * Grup kimliği → takım kimliği. Canlı gruplar tabloya, silinmişler denetim anlık
     * görüntüsüne dayanır.
     *
     * <p>OLUŞTURMA/SİLME kayıtları {@code changes} alanında düz anlık görüntü taşır
     * ({@code {"teamId":5,…}}); GÜNCELLEME kaydı fark taşır ({@code {"alan":{"from":…,"to":…}}}).
     * Ayrıştırıcı ikisini de kabul eder — takım hiç değişmediği için pratikte farkta çıkmaz, ama
     * ileride bir taşıma eklenirse okunur kalması bedavaya gelir.
     */
    private Map<Long, Long> resolveTeams(List<AuditLog> rows) {
        Map<Long, Long> teamOf = new HashMap<>();
        for (NotificationGroup g : groupRepo.findAll()) teamOf.put(g.getId(), g.getTeamId());
        for (AuditLog r : rows) {
            Long gid = parseLong(r.getResourceId());
            if (gid == null || teamOf.containsKey(gid)) continue;
            Long team = teamFromChanges(r.getChanges());
            if (team != null) teamOf.put(gid, team);
        }
        return teamOf;
    }

    static Long teamFromChanges(String changes) {
        if (changes == null || changes.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(changes).get("teamId");
            if (n == null) return null;
            if (n.isObject()) n = n.get("to");                 // fark biçimi
            return n != null && n.canConvertToLong() ? n.asLong() : null;
        } catch (Exception e) {
            // Eski kayıtlar JSON olmayabilir (Java Map.toString). Geçmişin tamamı bu yüzden
            // kaybolmasın diye sessizce atlanır; kapsam kararı yine kapalı tarafa düşer.
            return null;
        }
    }

    private static Long parseLong(String s) {
        try { return s == null ? null : Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
