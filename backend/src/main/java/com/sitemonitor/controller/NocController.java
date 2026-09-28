package com.sitemonitor.controller;

import com.sitemonitor.model.NocTarget;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.noc.NocCallListService;
import com.sitemonitor.service.noc.NocCoverageService;
import com.sitemonitor.service.noc.NocGroupIds;
import com.sitemonitor.service.noc.NocGroupService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocMonitorService;
import com.sitemonitor.service.noc.NocType;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * 7/24 İzleme Ekibi (NOC) — kimliği doğrulanmış herkesin uçları (2026-09-27; sözleşme
 * {@code .migration/noc/CONTRACT.md}): grup seçici, kapsam, izleme başına aç/kapa + toplu, takım arama listesi.
 *
 * <h2>Yetki</h2>
 * <ul>
 *   <li><b>Okuma</b> — kapsam ve seçenekler {@code monitoring.read}; kapsam satırları görüş alanıyla sınırlı
 *       ({@code viewTeamIds}; global görücü hepsi). Envanter kökenli DNS/Port satırlarının takımı envanterden
 *       gelir ve UG takımı da görür ({@code MonitoringController.inventoryViewable} ile aynı).</li>
 *   <li><b>Aç/kapa</b> — izlemenin KENDİ güncelleme ucunun kapısı: matris izni ({@code monitoring.crud} /
 *       envanter için {@code inventory.crud}, {@code edit}) + takım kapsamı ({@link SessionScope#canOperateTeam};
 *       envanter için {@link SessionScope#canWriteInventory}). Kapı yeniden yazılmadı, aynı yöntem çağrılıyor.</li>
 *   <li><b>Arama listesi yazma</b> — takımın yöneticisi ({@link SessionScope#canManage}: TEAM_ADMIN, kapsamlı
 *       müdür, global admin), takıma elle atanmış müdür ya da takım lideri. Okuma: takım görüş kapsamında.</li>
 * </ul>
 * Telefon HİÇBİR yanıtta yok — yalnız {@code has_phone}.
 */
@RestController
@RequestMapping("/api/noc")
@RequiredArgsConstructor
public class NocController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Geçmiş/denetim diff'ine giren alanlar (MON_FIELDS / INVENTORY_FIELDS'taki adlar). */
    static final String[] NOC_FIELDS = {"nocNotify", "nocGroupIds"};
    static final int MAX_BULK = 500;

    private final NocNotificationGroupRepository groupRepo;
    private final NocCoverageService coverage;
    private final NocMonitorService monitors;
    private final NocCallListService callLists;
    private final com.sitemonitor.service.noc.NocConfigService configService;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MonitorHistoryService monitorHistory;
    private final ActivityLogService activityLog;
    /**
     * SSL (sertifika envanteri) satırında 7/24 değişince Genel Bakış sertifika listesinin önbellekleri
     * ({@code cert-latest} … {@code card-extras}) boşaltılır — kart {@code noc_notify}'ı o listeden okur (2026-09-28).
     * Envanter formu (AdminController) zaten boşaltıyordu; bu uç (7/24 Kapsamı aç/kapa + toplu) boşaltmıyordu →
     * kart 300 sn'ye dek eski durumu gösterirdi.
     */
    private final com.sitemonitor.service.CertificateService certService;

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", msg));
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        for (Team t : teamRepo.findAll()) if (t.getId() != null) m.put(t.getId(), t.getName());
        return m;
    }

    // ── Seçenekler ───────────────────────────────────────────────────────────

    /**
     * İzleme formu grup seçicisi — e-posta YOK. Pasifler de döner (kayıtlı seçimi göstermek için).
     * {@code disabled_types}: yöneticinin 7/24'ü KAPATTIĞI türler — form "bu tür için kapatıldı" uyarısını yalnız
     * yönetici okuyabilen yapılandırma ucunu çağırmadan gösterir.
     *
     * <p>Kart göstergesi ekleri (2026-09-28, izleme/sertifika kartındaki "7/24 açık · iletilmiyor"): {@code has_active_group}
     * = kapsam nedeninin ({@link NocCoverageService#reason}) grup yüklemiyle AYNI ({@link NocGroupService#anyUsable}:
     * aktif VE adresli en az bir grup) — istemci "aktif grup yok" hükmünü kendisi tahmin etmez; {@code min_level} = 7/24'e
     * giden en düşük seviye (açıklama metni). İkisi de hassas değil; e-posta/adres sayısı yine YOK.
     */
    @GetMapping("/groups/options")
    public ResponseEntity<Map<String, Object>> groupOptions(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<com.sitemonitor.model.NocNotificationGroup> all = groupRepo.findAllByOrderByNameAsc();
        var cfg = configService.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", all.stream().map(NocGroupService::toOptionDto).toList());
        out.put("disabled_types", cfg.disabledTypeKeys());
        out.put("has_active_group", NocGroupService.anyUsable(all));
        out.put("min_level", cfg.minLevel());
        return ok(out);
    }

    // ── Kapsam ───────────────────────────────────────────────────────────────

    /** Satır görüş kapsamında mı (envanter kökenlide UG takımı da). */
    static boolean visible(HttpSession session, NocMonitorDirectory.Row r) {
        if (SessionScope.canView(session, r.teamId())) return true;
        return r.ugTeamId() != null && SessionScope.canView(session, r.ugTeamId());
    }

    /** Satırda 7/24'ü değiştirebilir mi — izlemenin kendi güncelleme kapısı. */
    boolean canEdit(HttpSession session, NocType type, Long teamId) {
        if (!permissionService.allows(session, type.permission, "edit")) return false;
        return type == NocType.SSL ? SessionScope.canWriteInventory(session, teamId)
                                   : SessionScope.canOperateTeam(session, teamId);
    }

    @GetMapping("/coverage")
    public ResponseEntity<Map<String, Object>> coverage(@RequestParam(name = "team_id", required = false) Long teamId,
                                                        @RequestParam(required = false) String type,
                                                        HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        NocType t = null;
        if (type != null && !type.isBlank()) {
            t = NocType.parse(type);
            if (t == null) return error(400, "Bilinmeyen izleme türü: " + type);
        }
        // Kapsam DIŞI takım süzgeci: 403 (sessiz boş liste "o takımda hiç izleme yok" yanılgısı üretirdi).
        if (teamId != null && !SessionScope.canView(session, teamId))
            return error(403, "Bu takımın kapsamını görme yetkiniz yok");
        Predicate<NocMonitorDirectory.Row> vis = r -> visible(session, r);
        Predicate<NocMonitorDirectory.Row> edit = r -> canEdit(session, r.type(), r.teamId());
        return ok(coverage.compute(teamId, t, vis, edit, teamNames()));
    }

    // ── İzleme başına aç/kapa ────────────────────────────────────────────────

    /** Tek izlemede 7/24: gövde {@code { enabled, groupIds? }} — yanıt güncel kapsam satırı. */
    @PutMapping("/monitors/{type}/{id}")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable String type, @PathVariable Long id,
                                                      @RequestBody Map<String, Object> body, HttpSession session) {
        NocType t = NocType.parse(type);
        if (t == null) return error(400, "Bilinmeyen izleme türü: " + type);
        if (!(body.get("enabled") instanceof Boolean enabled)) return error(400, "enabled true/false olmalı");
        permissionService.require(session, t.permission, "edit");
        NocTarget m = monitors.load(t, id);
        if (m == null) return error(404, "İzleme bulunamadı");
        NocMonitorDirectory.Row row = monitors.row(t, id);
        Long team = row != null ? row.teamId() : teamOf(m);
        if (!canEdit(session, t, team)) throw new SecurityException("Bu izleme üzerinde yetkiniz yok");

        Map<String, Object> before = AuditDiff.snapshot(m, NOC_FIELDS);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("nocNotify", enabled);
        if (body.containsKey("groupIds")) patch.put("nocGroupIds", body.get("groupIds"));
        monitors.applyFromBody(m, patch);
        String changes = persist(t, m, before, row, team, session, null);
        if (t == NocType.SSL && changes != null) certService.evictAllCaches();
        auditService.recordAction("NOC_MONITOR_UPDATE", session, t.auditResource, String.valueOf(id),
                row != null ? row.name() : String.valueOf(id), changes);
        NocMonitorDirectory.Row fresh = monitors.row(t, id);
        if (fresh == null) {
            Map<String, Object> minimal = new LinkedHashMap<>();
            minimal.put("type", t.name());
            minimal.put("id", id);
            minimal.put("noc_notify", Boolean.TRUE.equals(m.getNocNotify()));
            minimal.put("noc_group_ids", NocGroupIds.parse(m.getNocGroupIds()));
            return ok(minimal);
        }
        return ok(coverage.item(fresh, true, teamNames()));
    }

    /** Toplu: {@code { items: [{type, id}], enabled }} → {@code { updated, skipped: [{type, id, reason}] }}. */
    @PostMapping("/monitors/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (!(body.get("enabled") instanceof Boolean enabled)) return error(400, "enabled true/false olmalı");
        if (!(body.get("items") instanceof List<?> items)) return error(400, "items bir liste olmalı");
        if (items.size() > MAX_BULK) return error(400, "Tek istekte en fazla " + MAX_BULK + " izleme");
        int updated = 0;
        boolean sslChanged = false;
        List<Map<String, Object>> skipped = new ArrayList<>();
        try {
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> it)) continue;
            Object rawType = it.get("type");
            Object rawId = it.get("id");
            NocType t = NocType.parse(rawType == null ? null : rawType.toString());
            Long id = rawId instanceof Number n ? n.longValue() : parseLong(rawId);
            if (t == null || id == null) { skipped.add(skip(rawType, rawId, "INVALID")); continue; }
            if (!permissionService.allows(session, t.permission, "edit")) { skipped.add(skip(t.name(), id, "FORBIDDEN")); continue; }
            NocTarget m = monitors.load(t, id);
            if (m == null) { skipped.add(skip(t.name(), id, "NOT_FOUND")); continue; }
            NocMonitorDirectory.Row row = monitors.row(t, id);
            Long team = row != null ? row.teamId() : teamOf(m);
            if (!canEdit(session, t, team)) { skipped.add(skip(t.name(), id, "FORBIDDEN")); continue; }
            if (enabled.equals(Boolean.TRUE.equals(m.getNocNotify()))) { skipped.add(skip(t.name(), id, "UNCHANGED")); continue; }
            Map<String, Object> before = AuditDiff.snapshot(m, NOC_FIELDS);
            monitors.applyFromBody(m, Map.of("nocNotify", enabled));
            if (persist(t, m, before, row, team, session, "toplu 7/24 işlemi") != null && t == NocType.SSL) sslChanged = true;
            updated++;
        }
        } finally {
            // döngü sonunda TEK boşaltma — ortada bir kayıt düşse de o ana kadar değişen sertifikalar için
            // (regresyon 2026-09-28b B5: istisnada boşaltma atlanıyor, kart 300 sn eski 7/24 durumu gösteriyordu)
            if (sslChanged) certService.evictAllCaches();
        }
        auditService.recordAction("NOC_MONITOR_BULK", session, "NOC", "bulk",
                com.sitemonitor.service.AuditDetail.of("enabled", enabled, "updated", updated, "skipped", skipped.size()), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("skipped", skipped);
        return ok(out);
    }

    /** Kaydet + ürün geçmişi + etkinlik akışı (izleme düzenlemesiyle aynı iz). Dönüş: denetim diff'i. */
    private String persist(NocType t, NocTarget m, Map<String, Object> before, NocMonitorDirectory.Row row,
                           Long team, HttpSession session, String note) {
        Map<String, Object> after = AuditDiff.snapshot(m, NOC_FIELDS);
        String changes = AuditDiff.diff(before, after);
        if (changes == null) return null;   // değişen bir şey yok → kayıt, geçmiş ve akış satırı da yok
        monitorHistory.stampUpdated(m, session);
        touchUpdatedAt(m);
        monitors.save(t, m);
        String name = row != null ? row.name() : String.valueOf(m.getId());
        String target = row != null ? row.target() : null;
        var changeRow = monitorHistory.record(t.historyKind, m.getId(), name, team, MonitorHistoryService.UPDATE,
                before, after, note == null ? "7/24 bildirimi" : note, session);
        if (changeRow != null) {
            activityLog.recordLifecycle(t.activityType, m.getId(), name, target, team, "CONFIG_CHANGED", actor(session),
                    Boolean.TRUE.equals(m.getNocNotify()) ? "7/24 bildirimi açıldı" : "7/24 bildirimi kapatıldı");
        }
        return changes;
    }

    private static void touchUpdatedAt(Object entity) {
        try {
            entity.getClass().getMethod("setUpdatedAt", String.class).invoke(entity, ISO.format(Instant.now()));
        } catch (Exception ignored) { /* kolon yok → künye yok */ }
    }

    private static Long teamOf(Object entity) {
        try {
            Object v = entity.getClass().getMethod("getTeamId").invoke(entity);
            return v instanceof Number n ? n.longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> skip(Object type, Object id, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("reason", reason);
        return m;
    }

    private static Long parseLong(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    // ── Takım arama listesi ──────────────────────────────────────────────────

    @GetMapping("/teams/{teamId}/call-list")
    public ResponseEntity<Map<String, Object>> callList(@PathVariable Long teamId, HttpSession session) {
        if (teamRepo.findById(teamId).isEmpty()) return error(404, "Takım bulunamadı");
        if (!canReadCallList(session, teamId)) return error(403, "Bu takımın arama listesini görme yetkiniz yok");
        return ok(callLists.callListDto(teamId));
    }

    @GetMapping("/teams/{teamId}/members")
    public ResponseEntity<Map<String, Object>> members(@PathVariable Long teamId, HttpSession session) {
        if (teamRepo.findById(teamId).isEmpty()) return error(404, "Takım bulunamadı");
        if (!canReadCallList(session, teamId)) return error(403, "Bu takımın üyelerini görme yetkiniz yok");
        return ok(callLists.membersDto(teamId));
    }

    /**
     * Arama listesini OKUYABİLİR mi: takım görüş kapsamında YA DA listeyi düzenleyebilir ({@link #canEditCallList}).
     * Takıma elle atanmış müdür/lider görüş kapsamı dışında olabilir; yazabildiği listeyi okuyamaması düzenleyiciyi
     * boş açtırır ve kaydetmek mevcut sırayı SİLERDİ. Yanıtta telefon yok ({@code has_phone}).
     */
    boolean canReadCallList(HttpSession session, Long teamId) {
        return SessionScope.canView(session, teamId) || canEditCallList(session, teamId);
    }

    /** Arama listesini düzenleyebilir mi — takım yöneticisi/müdürü/lideri ya da global admin. */
    boolean canEditCallList(HttpSession session, Long teamId) {
        if (SessionScope.canManage(session, teamId)) return true;   // global admin + yönetim kapsamı
        Object uid = session != null ? session.getAttribute("userId") : null;
        Long userId = uid instanceof Number n ? n.longValue() : null;
        return callLists.isTeamManagerOrLeader(teamId, userId);
    }

    @PutMapping("/teams/{teamId}/call-list")
    public ResponseEntity<Map<String, Object>> updateCallList(@PathVariable Long teamId,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpSession session) {
        if (teamRepo.findById(teamId).isEmpty()) return error(404, "Takım bulunamadı");
        if (!canEditCallList(session, teamId))
            throw new SecurityException("Arama listesini yalnız takımın yöneticisi/müdürü düzenleyebilir");
        if (!(body.get("userIds") instanceof List<?> raw)) return error(400, "userIds bir liste olmalı");
        List<Long> ids = new ArrayList<>();
        for (Object o : raw) {
            Long v = o instanceof Number n ? n.longValue() : parseLong(o);
            if (v == null) return error(400, "Geçersiz kullanıcı kimliği: " + o);
            ids.add(v);
        }
        List<Map<String, Object>> before = callLists.callListDto(teamId);
        List<Map<String, Object>> saved = callLists.replace(teamId, ids, actor(session));
        auditService.recordAction("NOC_CALL_LIST_UPDATE", session, "TEAM", String.valueOf(teamId),
                com.sitemonitor.service.AuditDetail.of("team_id", teamId, "count", saved.size()),
                "{\"userIds\":{\"from\":" + userIdsJson(before) + ",\"to\":" + userIdsJson(saved) + "}}");
        return ok(saved);
    }

    private static String userIdsJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (Map<String, Object> r : rows) {
            if (sb.length() > 1) sb.append(',');
            sb.append(r.get("user_id"));
        }
        return sb.append(']').toString();
    }
}
