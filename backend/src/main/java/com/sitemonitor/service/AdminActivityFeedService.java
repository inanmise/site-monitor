package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Çapraz yönetim değişiklik akışı (2026-11-13, yönetim zenginleştirmesi #2): takımlar, kullanıcılar, eskalasyon
 * kişileri, eşikler, bildirim grupları, alarm iletimi, erişim paylaşımı ve nöbet üzerindeki YÖNETİMSEL değişiklikler
 * tek "kim neyi ne zaman değiştirdi" akışında. Kaynak denetim kaydı ({@code audit_log}) — sonradan düzenlenemez,
 * silinmiş kayıtlar da kalır.
 *
 * <p><b>Alan = olay türleri kümesi.</b> Her alanın olay türleri ayrıktır (bir olay türü tek alana düşer); alanın satırı
 * hangi kaynak türüyle yazdığı da sabittir. Takımlar / kullanıcılar / kişiler / eşikler sekme geçmişinin
 * ({@link AdminHistoryService#EVENT_TYPES}) AYNI beyaz listesini kullanır — giriş / çıkış gibi gürültü yoktur.
 *
 * <p><b>Kapsam alan başına.</b> Çağıran (denetleyici) her izinli alan için görülebilir takımları verir
 * ({@code null} = sınırsız). Tüm alanlar sınırsızsa veritabanı sayfası kullanılır (toplam gerçek). Değilse en yeni
 * {@link #WINDOW_MAX} satır okunur, satır satır takımına göre süzülür ve bellekte sayfalanır; takımı çözülemeyen satır
 * kapsamlı görüntüleyiciye GÖSTERİLMEZ (kapalı tarafa düşer, sayısı {@code hidden}).
 */
@Service
@RequiredArgsConstructor
public class AdminActivityFeedService {

    /** Alan anahtarları — kalıcı (URL / arayüz / denetim). Sıra = arayüz çip sırası. */
    public static final List<String> AREAS = List.of(
            "teams", "users", "contacts", "thresholds", "groups", "forwards", "sharing", "oncall");

    /** Alan tanımı: satırın yazıldığı kaynak türü + alanın olay türleri (beyaz liste). */
    public record AreaDef(String key, String resourceType, List<String> types) {}

    static final Map<String, AreaDef> DEFS;
    /** Olay türü → alan (türler alanlar arasında ayrık; aynı tür iki alana düşmez). */
    static final Map<String, String> AREA_OF_TYPE;

    static {
        Map<String, AreaDef> d = new LinkedHashMap<>();
        d.put("teams", new AreaDef("teams", "TEAM", AdminHistoryService.EVENT_TYPES.get("TEAM")));
        d.put("users", new AreaDef("users", "USER", AdminHistoryService.EVENT_TYPES.get("USER")));
        d.put("contacts", new AreaDef("contacts", "ESCALATION_CONTACT", AdminHistoryService.EVENT_TYPES.get("ESCALATION_CONTACT")));
        d.put("thresholds", new AreaDef("thresholds", "ALERT_THRESHOLD", AdminHistoryService.EVENT_TYPES.get("ALERT_THRESHOLD")));
        d.put("groups", new AreaDef("groups", NotificationGroupHistoryService.RESOURCE, List.of(
                "NOTIFICATION_GROUP_CREATE", "NOTIFICATION_GROUP_UPDATE", "NOTIFICATION_GROUP_DEFAULT",
                "NOTIFICATION_GROUP_REASSIGN", "NOTIFICATION_GROUP_DELETE")));
        // İletim / paylaşım / nöbet olayları TEAM kaynak türüyle, kimlik = sahip takım olarak yazılır
        d.put("forwards", new AreaDef("forwards", "TEAM", List.of(
                "ALERT_FORWARD_CREATE", "ALERT_FORWARD_UPDATE", "ALERT_FORWARD_DELETE")));
        d.put("sharing", new AreaDef("sharing", "TEAM", List.of(
                "TEAM_ACCESS_GRANT_CREATE", "TEAM_ACCESS_GRANT_UPDATE", "TEAM_ACCESS_GRANT_DELETE")));
        d.put("oncall", new AreaDef("oncall", "TEAM", List.of(
                "ONCALL_SCHEDULE_SAVE", "ONCALL_OVERRIDE_ADD", "ONCALL_OVERRIDE_DELETE")));
        DEFS = Collections.unmodifiableMap(d);
        Map<String, String> byType = new HashMap<>();
        for (AreaDef a : d.values()) {
            for (String t : a.types()) {
                String prev = byType.put(t, a.key());
                if (prev != null) throw new IllegalStateException("Olay türü iki alanda: " + t + " (" + prev + ", " + a.key() + ")");
            }
        }
        AREA_OF_TYPE = Collections.unmodifiableMap(byType);
    }

    /** Kapsamlı görüntüleyicide okunan en yeni satır sayısı (pencere). */
    static final int WINDOW_MAX = 3_000;
    static final int SIZE_MAX = 200;

    private final AuditLogRepository auditRepo;
    private final EscalationContactRepository contactRepo;
    private final NotificationGroupHistoryService groupHistory;

    /** Bir alanın erişimi: görülebilir takımlar ({@code null} = sınırsız, global görücü / global alan). */
    public record Access(String area, Collection<Long> teams) {}

    public record Entry(AuditLog row, String area, Long teamId) {}

    /**
     * @param total     toplam satır (kapsamlı görüntüleyicide pencere içindeki görünür satır sayısı)
     * @param truncated pencere doldu — daha eski satırlar okunmadı
     * @param hidden    takımı çözülemediği için kapsamlı görüntüleyiciden gizlenen satır sayısı
     */
    public record Feed(List<Entry> items, long total, int page, int size, boolean truncated, int hidden) {
        public int totalPages() { return size <= 0 ? 0 : (int) Math.ceil(total / (double) size); }
    }

    /** Olay türünün alanı (bilinmeyen tür → {@code null}). */
    public static String areaOf(String eventType) {
        return eventType == null ? null : AREA_OF_TYPE.get(eventType);
    }

    /**
     * @param access izinli alanlar ve kapsamları (boş = hiçbir alan → boş akış, sorgu YOK)
     * @param actor  yalnız bu kişinin değişiklikleri (harf duyarsız; boş = herkes)
     * @param since  bu andan (UTC {@code yyyy-MM-ddTHH:mm:ss}) yeni; {@code null}/boş = tümü
     */
    public Feed feed(List<Access> access, String actor, String since, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, SIZE_MAX));
        Map<String, Access> byArea = new LinkedHashMap<>();
        if (access != null) for (Access a : access) if (a != null && DEFS.containsKey(a.area())) byArea.put(a.area(), a);
        if (byArea.isEmpty()) return new Feed(List.of(), 0, p, s, false, 0);

        Set<String> types = new LinkedHashSet<>();
        for (String area : byArea.keySet()) types.addAll(DEFS.get(area).types());
        String since0 = since == null ? "" : since;
        String who = actor == null || actor.isBlank() ? null : actor.trim().toLowerCase(Locale.ROOT);

        boolean unrestricted = byArea.values().stream().allMatch(a -> a.teams() == null);
        if (unrestricted) {
            Page<AuditLog> pg = who == null
                    ? auditRepo.findAdminFeed(types, since0, PageRequest.of(p, s))
                    : auditRepo.findAdminFeedByActor(types, since0, who, PageRequest.of(p, s));
            List<AuditLog> rows = pg.getContent();
            Resolver res = new Resolver(rows);
            List<Entry> out = new ArrayList<>(rows.size());
            for (AuditLog r : rows) {
                String area = areaOf(r.getEventType());
                out.add(new Entry(r, area, res.teamOf(area, r)));
            }
            return new Feed(out, pg.getTotalElements(), p, s, false, 0);
        }

        // Kapsamlı: pencere + satır satır süzme + bellek içi sayfalama
        List<AuditLog> scanned = who == null
                ? auditRepo.findAdminFeed(types, since0, PageRequest.of(0, WINDOW_MAX)).getContent()
                : auditRepo.findAdminFeedByActor(types, since0, who, PageRequest.of(0, WINDOW_MAX)).getContent();
        Resolver res = new Resolver(scanned);
        Map<String, Set<Long>> allowed = new HashMap<>();
        for (Access a : byArea.values()) if (a.teams() != null) allowed.put(a.area(), new HashSet<>(a.teams()));
        List<Entry> visible = new ArrayList<>();
        int hidden = 0;
        for (AuditLog r : scanned) {
            String area = areaOf(r.getEventType());
            if (area == null || !byArea.containsKey(area)) continue;
            Long team = res.teamOf(area, r);
            Set<Long> teams = allowed.get(area);
            if (teams == null) { visible.add(new Entry(r, area, team)); continue; }
            Set<Long> rowTeams = res.visibleOf(area, r, team);   // grup taşıma satırı iki takıma da görünür
            if (rowTeams.isEmpty()) { hidden++; continue; }   // kapalı tarafa düş
            boolean any = false;
            for (Long t : rowTeams) if (teams.contains(t)) { any = true; break; }
            if (!any) continue;
            visible.add(new Entry(r, area, team));
        }
        int from = Math.min(p * s, visible.size());
        int to = Math.min(from + s, visible.size());
        return new Feed(new ArrayList<>(visible.subList(from, to)), visible.size(), p, s,
                scanned.size() >= WINDOW_MAX, hidden);
    }

    /** Satırın takımı — okunan satırlar için TEK seferlik tablo okumalarıyla (kişi ve grup tabloları yalnız gerekirse). */
    private final class Resolver {
        private Map<Long, Long> contactTeams;
        private Map<Long, NotificationGroupHistoryService.RowTeams> groupTeams;
        private final List<AuditLog> rows;

        Resolver(List<AuditLog> rows) { this.rows = rows; }

        Long teamOf(String area, AuditLog r) {
            if (area == null) return null;
            AreaDef def = DEFS.get(area);
            if (def == null || !def.resourceType().equals(r.getResourceType())) return null;
            switch (area) {
                case "teams", "forwards", "sharing", "oncall" -> { return AdminHistoryService.parseLong(r.getResourceId()); }
                case "contacts" -> {
                    if (contactTeams == null) contactTeams = liveContactTeams();
                    return AdminHistoryService.teamOf("ESCALATION_CONTACT", r, contactTeams);
                }
                case "groups" -> {
                    // Satırın KENDİ zamanındaki takımı (2026-11-13: toplu taşıma — eski geçmiş eski takımda kalır)
                    if (groupTeams == null) groupTeams = groupTeams();
                    NotificationGroupHistoryService.RowTeams rt = groupTeams.get(r.getId());
                    return rt == null ? null : rt.team();
                }
                default -> { return null; }          // kullanıcılar / eşikler: takıma bağlı değil (yalnız global alan)
            }
        }

        /** Satırı görebilen takımlar: grup satırında satırın kendi zamanı (taşıma = iki taraf), diğerlerinde gösterim takımı. */
        Set<Long> visibleOf(String area, AuditLog r, Long team) {
            if ("groups".equals(area)) {
                if (groupTeams == null) groupTeams = groupTeams();
                NotificationGroupHistoryService.RowTeams rt = groupTeams.get(r.getId());
                return rt == null ? Set.of() : rt.visible();
            }
            return team == null ? Set.of() : Set.of(team);
        }

        private Map<Long, NotificationGroupHistoryService.RowTeams> groupTeams() {
            List<AuditLog> groupRows = new ArrayList<>();
            for (AuditLog r : rows) if (NotificationGroupHistoryService.RESOURCE.equals(r.getResourceType())) groupRows.add(r);
            try {
                return groupHistory.rowTeams(groupRows);
            } catch (RuntimeException e) {
                return Map.of();                     // takımı çözülemeyen grup satırı kapsamlıya gizlenir
            }
        }
    }

    private Map<Long, Long> liveContactTeams() {
        Map<Long, Long> m = new HashMap<>();
        try {
            contactRepo.findAll().forEach(c -> { if (c.getId() != null) m.put(c.getId(), c.getTeamId()); });
        } catch (RuntimeException ignore) { /* takımı çözülemeyen kişi satırı kapsamlıya gizlenir */ }
        return m;
    }
}
