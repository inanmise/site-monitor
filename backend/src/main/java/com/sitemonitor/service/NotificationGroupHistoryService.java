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
     * Bir denetim satırının takımı (2026-11-13, toplu taşıma): {@code team} gösterim takımı, {@code visible} satırı
     * görebilen takımlar. Satır KENDİ ZAMANINDAKİ takıma aittir — taşınan grubun eski geçmişini eski takım görmeye devam
     * eder, yeni takım yalnız taşımadan sonrasını görür; taşıma satırının kendisi İKİ tarafa da görünür (gösterim = yeni).
     */
    public record RowTeams(Long team, Set<Long> visible) {}

    /** Taşıma izi okuma tavanı (grup güncelleme satırı; taşımalar yalnız toplu uçtan doğar, nadirdir). */
    private static final int MOVES_MAX = 5000;
    private static final String UPDATE_EVENT = "NOTIFICATION_GROUP_UPDATE";

    /**
     * @param items      görüntülenecek satırlar (yeniden eskiye)
     * @param truncated  pencere doldu; daha eski kayıtlar var olabilir
     * @param hidden     takımı çözülemediği için gizlenen satır sayısı
     */
    public record History(List<Entry> items, boolean truncated, int hidden) {}

    /**
     * Sayfalı geçmiş (2026-09-20, kullanıcı bildirimi: liste sayfalama yapısında olsun).
     * @param total  kapsam süzgecinden geçen satır sayısı (taranan pencere içinde; {@code truncated} ise alt sınır)
     */
    public record HistoryPage(List<Entry> items, int total, int page, int size, boolean truncated, int hidden) {}

    /**
     * Sayfalı okuma: taranan pencere ({@link #WINDOW_MAX}) içinde kapsam süzgeci uygulanır, sayfa dilimi kesilir.
     * Pencere dolduysa {@code truncated} — daha eski kayıtlar var olabilir; sayfa sayısı pencereyle sınırlıdır.
     */
    public HistoryPage page(Collection<Long> teamIds, Long groupId, int page, int size) {
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        int window = WINDOW_MAX;
        List<AuditLog> scanned = groupId != null
                ? auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                        RESOURCE, String.valueOf(groupId), PageRequest.of(0, window))
                : auditRepo.findByResourceTypeOrderByEventTimeDesc(RESOURCE, PageRequest.of(0, window));
        Map<Long, RowTeams> teamOf = rowTeams(scanned);
        boolean unrestricted = teamIds == null;
        Set<Long> allowed = unrestricted ? Set.of() : new HashSet<>(teamIds);
        List<Entry> all = new ArrayList<>();
        int hidden = 0;
        for (AuditLog r : scanned) {
            RowTeams rt = teamOf.get(r.getId());
            Long team = rt == null ? null : rt.team();
            if (!unrestricted && !visibleTo(rt, allowed)) {
                if (team == null) hidden++;
                continue;
            }
            all.add(new Entry(r, team));
        }
        int from = Math.min(pg * sz, all.size());
        int to = Math.min(from + sz, all.size());
        return new HistoryPage(new ArrayList<>(all.subList(from, to)), all.size(), pg, sz, scanned.size() >= window, hidden);
    }

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

        Map<Long, RowTeams> teamOf = rowTeams(scanned);
        boolean unrestricted = teamIds == null;
        Set<Long> allowed = unrestricted ? Set.of() : new HashSet<>(teamIds);

        List<Entry> out = new ArrayList<>();
        int hidden = 0;
        boolean more = false;
        for (AuditLog r : scanned) {
            RowTeams rt = teamOf.get(r.getId());
            Long team = rt == null ? null : rt.team();
            // Kapalı tarafa düş: takımı bilinmeyen satır, kapsamlı kullanıcıya gösterilmez.
            if (!unrestricted && !visibleTo(rt, allowed)) {
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
    Map<Long, Long> resolveTeams(List<AuditLog> rows) {
        return resolveTeams(rows, liveTeams());
    }

    private Map<Long, Long> liveTeams() {
        Map<Long, Long> live = new HashMap<>();
        for (NotificationGroup g : groupRepo.findAll()) live.put(g.getId(), g.getTeamId());
        return live;
    }

    private static Map<Long, Long> resolveTeams(List<AuditLog> rows, Map<Long, Long> live) {
        Map<Long, Long> teamOf = new HashMap<>(live);
        for (AuditLog r : rows) {
            Long gid = parseLong(r.getResourceId());
            if (gid == null || teamOf.containsKey(gid)) continue;
            Long team = teamFromChanges(r.getChanges());
            if (team != null) teamOf.put(gid, team);
        }
        return teamOf;
    }

    /** Satır, izinli takımlardan en az birine görünür mü (takımı çözülemeyen satır KAPALI tarafa düşer). */
    public static boolean visibleTo(RowTeams rt, Set<Long> allowed) {
        if (rt == null || rt.visible().isEmpty()) return false;
        for (Long t : rt.visible()) if (allowed.contains(t)) return true;
        return false;
    }

    /**
     * Denetim satırı kimliği → satırın KENDİ ZAMANINDAKİ takımı. Kurallar (kimlik sırası = yazım sırası):
     * <ol>
     *   <li>Taşıma satırı ({@code changes} içinde {@code teamId} farkı) → gösterim yeni takım, görünür iki takım.</li>
     *   <li>Anlık görüntü satırı (oluşturma / silme: düz {@code teamId}) → o anki takım.</li>
     *   <li>Diğerleri → kendinden SONRAKİ ilk taşımanın {@code from}'u; sonra taşıma yoksa canlı grubun takımı; grup
     *       silinmişse kendinden ÖNCEKİ son taşımanın {@code to}'su, taşıma hiç yoksa anlık görüntü ({@link #resolveTeams}).
     *       2026-11-13 hata taraması: silinmiş + taşınmış grupta kişi süzgeci silme satırını dışarıda bırakınca "güncel takım"
     *       okunan OLUŞTURMA anlık görüntüsünden (taşımadan ÖNCEKİ takım) kuruluyordu — taşımadan sonraki satır eski takıma
     *       yazılıyordu.</li>
     * </ol>
     * Taşıma izi, okunan satırlardan bağımsız olarak grubun güncelleme satırlarından okunur (TEK sorgu, tavanlı) — akışın
     * kişi / tarih süzgeci taşıma satırını dışarıda bıraksa da eski satır yanlış takıma yazılmaz.
     */
    public Map<Long, RowTeams> rowTeams(List<AuditLog> rows) {
        Map<Long, RowTeams> out = new HashMap<>();
        if (rows == null || rows.isEmpty()) return out;
        Map<Long, Long> live = liveTeams();
        Map<Long, Long> current = resolveTeams(rows, live);
        Set<String> ids = new HashSet<>();
        for (AuditLog r : rows) if (parseLong(r.getResourceId()) != null) ids.add(r.getResourceId().trim());
        Map<Long, List<long[]>> moves = new HashMap<>();   // grup → [denetim kimliği, from, to] (kimliğe göre artan)
        if (!ids.isEmpty()) {
            for (AuditLog m : auditRepo.findByResourceTypeAndEventTypeAndResourceIdIn(
                    RESOURCE, UPDATE_EVENT, ids, PageRequest.of(0, MOVES_MAX))) {
                Long gid = parseLong(m.getResourceId());
                long[] mv = moveOf(m.getChanges());
                if (gid == null || mv == null || m.getId() == null) continue;
                moves.computeIfAbsent(gid, k -> new ArrayList<>()).add(new long[] { m.getId(), mv[0], mv[1] });
            }
            for (List<long[]> l : moves.values()) l.sort((a, b) -> Long.compare(a[0], b[0]));
        }
        for (AuditLog r : rows) {
            if (r.getId() == null) continue;
            Long gid = parseLong(r.getResourceId());
            long[] own = moveOf(r.getChanges());
            if (own != null) {
                Set<Long> vis = new HashSet<>();
                vis.add(own[0]);
                vis.add(own[1]);
                out.put(r.getId(), new RowTeams(own[1], vis));
                continue;
            }
            Long team = flatTeam(r.getChanges());
            if (team == null && gid != null) {
                List<long[]> list = moves.getOrDefault(gid, List.of());
                boolean later = false;
                for (long[] m : list) {
                    if (m[0] > r.getId()) { team = m[1]; later = true; break; }
                }
                if (!later) {
                    if (live.containsKey(gid)) team = live.get(gid);
                    else {
                        for (long[] m : list) if (m[0] < r.getId()) team = m[2];   // silinmiş grup: son taşımanın hedefi
                        if (team == null) team = current.get(gid);
                    }
                }
            }
            out.put(r.getId(), new RowTeams(team, team == null ? Set.of() : Set.of(team)));
        }
        return out;
    }

    /** Taşıma farkı ({@code {"teamId":{"from":A,"to":B}}}) → {A, B}; değilse null. */
    static long[] moveOf(String changes) {
        if (changes == null || changes.isBlank() || !changes.contains("teamId")) return null;
        try {
            JsonNode n = JSON.readTree(changes).get("teamId");
            if (n == null || !n.isObject()) return null;
            JsonNode from = n.get("from"), to = n.get("to");
            if (from == null || to == null || !from.canConvertToLong() || !to.canConvertToLong()) return null;
            return new long[] { from.asLong(), to.asLong() };
        } catch (Exception e) {
            return null;
        }
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
