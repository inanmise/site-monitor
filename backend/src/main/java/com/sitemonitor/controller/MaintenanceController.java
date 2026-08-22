package com.sitemonitor.controller;

import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bakım penceresi yönetimi (CRUD + pause/resume + ad-hoc "start now" + /active). Occurrence/bastırma mantığı
 * {@link MaintenanceService}'te. Yetki: view=maintenance.view, yaz=maintenance.manage, sil=maintenance.delete.
 * Her yazım {@code maintenanceService.refresh()} çağırır → aktif-hedef cache'i anında güncellenir.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RECURRENCE = Set.of("NONE", "DAILY", "WEEKLY", "MONTHLY");

    private final MaintenanceWindowRepository repo;
    private final MaintenanceService maintenanceService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MonitorHistoryService monitorHistory;

    /** Bakım penceresinin geçmişte tutulan alanları (denetim burada yalnız "{}" yazıyordu). */
    private static final String[] MAINTENANCE_FIELDS = {
        "name", "description", "targetsJson", "startAt", "durationMinutes",
        "daysOfWeek", "dayOfMonth", "active", "teamId"
    };

    // ── Liste ──────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpSession session) {
        permissionService.require(session, "maintenance.view", "view");
        Instant now = Instant.now();
        // Kapsam: kendi takımının pencereleri + HERKESİ etkileyenler. "allMonitors" penceresi senin
        // izlemelerini de bastırdığı için gizlenmesi zarar verir (alarm neden gelmiyor sorusu cevapsız
        // kalır); takımsız legacy kayıtlar da aynı nedenle görünür bırakılıyor. Global admin/AUDIT hepsini görür.
        List<Map<String, Object>> data = repo.findAllByOrderByStartAtDesc().stream()
                .filter(w -> SessionScope.canView(session, w.getTeamId())
                        || Boolean.TRUE.equals(w.getAllMonitors())
                        || w.getTeamId() == null)
                .map(w -> dto(w, now)).toList();
        return ok(Map.of("data", data));
    }

    /** Aktif bakım hedefleri (badge overlay için). */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active(HttpSession session) {
        permissionService.require(session, "maintenance.view", "view");
        return ok(Map.of("data", maintenanceService.activeInfo()));
    }

    // ── Oluştur / Güncelle / Sil ─────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                       HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "maintenance.manage", "edit");
        if (blank(body.get("name")))    throw new IllegalArgumentException("İsim zorunlu");
        if (blank(body.get("startAt"))) throw new IllegalArgumentException("Başlangıç zamanı zorunlu");
        MaintenanceWindow w = new MaintenanceWindow();
        applyFields(w, body);
        validateWindow(w);
        w.setActive(true);
        w.setCreatedAt(now());
        w.setUpdatedAt(now());
        w.setCreatedBy(actor(session));
        w.setTeamId(sessionTeamId(session));
        MaintenanceWindow saved = repo.save(w);
        maintenanceService.refresh();
        auditService.recordAction("MAINTENANCE_CREATE", session, request, "MAINTENANCE_WINDOW", String.valueOf(saved.getId()), "{}");
        // Denetim burada detay olarak "{}" yazıyor — yani "bir pencere oluşturuldu" bilinir ama
        // HANGİ değerlerle bilinmez. Ürün geçmişi ilk değerleri taşır.
        monitorHistory.record(MonitorHistoryService.MAINTENANCE, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MAINTENANCE_FIELDS), null, session);
        return ok(Map.of("data", dto(saved, Instant.now()), "message", "Maintenance window created"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "maintenance.manage", "edit");
        MaintenanceWindow w = requireManageable(id, session);
        Map<String, Object> _before = AuditDiff.snapshot(w, MAINTENANCE_FIELDS);
        applyFields(w, body);
        validateWindow(w);
        w.setUpdatedAt(now());
        MaintenanceWindow saved = repo.save(w);
        maintenanceService.refresh();
        auditService.recordAction("MAINTENANCE_UPDATE", session, request, "MAINTENANCE_WINDOW", String.valueOf(id), "{}");
        monitorHistory.record(MonitorHistoryService.MAINTENANCE, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MAINTENANCE_FIELDS), null, session);
        return ok(Map.of("data", dto(saved, Instant.now()), "message", "Maintenance window updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id,
                                                      HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "maintenance.delete", "execute");
        MaintenanceWindow doomed = requireManageable(id, session);
        // HARD delete: satır gittikten sonra snapshot alınamaz.
        Map<String, Object> _before = AuditDiff.snapshot(doomed, MAINTENANCE_FIELDS);
        String name = doomed.getName();
        Long teamId = doomed.getTeamId();
        repo.deleteById(id);
        maintenanceService.refresh();
        auditService.recordAction("MAINTENANCE_DELETE", session, request, "MAINTENANCE_WINDOW", String.valueOf(id), "{}");
        monitorHistory.record(MonitorHistoryService.MAINTENANCE, id, name, teamId,
                MonitorHistoryService.DELETE, _before, null, null, session);
        return ok(Map.of("message", "Maintenance window deleted"));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return toggle(id, false, session, request);
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return toggle(id, true, session, request);
    }

    private ResponseEntity<Map<String, Object>> toggle(Long id, boolean active, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "maintenance.manage", "edit");
        MaintenanceWindow w = requireManageable(id, session);
        w.setActive(active);
        w.setUpdatedAt(now());
        MaintenanceWindow saved = repo.save(w);
        maintenanceService.refresh();
        auditService.recordAction(active ? "MAINTENANCE_RESUME" : "MAINTENANCE_PAUSE", session, request,
                "MAINTENANCE_WINDOW", String.valueOf(id), "{}");
        return ok(Map.of("data", dto(saved, Instant.now()), "message", active ? "Resumed" : "Paused"));
    }

    // ── Ad-hoc "start now" ───────────────────────────────────────────────────────
    @PostMapping("/quick")
    public ResponseEntity<Map<String, Object>> quick(@RequestBody Map<String, Object> body,
                                                     HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "maintenance.manage", "edit");
        int minutes = body.get("minutes") instanceof Number n ? Math.max(1, n.intValue()) : 60;
        MaintenanceWindow w = new MaintenanceWindow();
        w.setName(blank(body.get("name")) ? "Ad-hoc bakım" : body.get("name").toString().trim());
        boolean all = body.get("allMonitors") instanceof Boolean b && b;
        w.setAllMonitors(all);
        if (!all) w.setTargetsJson(serializeTargets(body.get("targets")));
        w.setTimezone(blank(body.get("timezone")) ? "Europe/Istanbul" : body.get("timezone").toString().trim());
        w.setStartAt(now());               // şimdi
        w.setDurationMinutes(minutes);
        w.setRecurrence("NONE");
        w.setActive(true);
        w.setCreatedAt(now());
        w.setUpdatedAt(now());
        w.setCreatedBy(actor(session));
        w.setTeamId(sessionTeamId(session));
        MaintenanceWindow saved = repo.save(w);
        maintenanceService.refresh();
        auditService.recordAction("MAINTENANCE_QUICK", session, request, "MAINTENANCE_WINDOW", String.valueOf(saved.getId()), "{}");
        return ok(Map.of("data", dto(saved, Instant.now()), "message", "Maintenance started"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private void applyFields(MaintenanceWindow w, Map<String, Object> body) {
        if (body.containsKey("name") && !blank(body.get("name"))) w.setName(body.get("name").toString().trim());
        if (body.containsKey("description")) w.setDescription(blank(body.get("description")) ? null : body.get("description").toString());
        if (body.get("allMonitors") instanceof Boolean b) w.setAllMonitors(b);
        if (body.containsKey("targets")) w.setTargetsJson(serializeTargets(body.get("targets")));
        if (!blank(body.get("timezone"))) w.setTimezone(body.get("timezone").toString().trim());
        if (!blank(body.get("startAt"))) w.setStartAt(body.get("startAt").toString().trim());
        if (body.get("durationMinutes") instanceof Number n) w.setDurationMinutes(Math.max(1, n.intValue()));
        if (!blank(body.get("recurrence"))) {
            String r = body.get("recurrence").toString().trim().toUpperCase();
            w.setRecurrence(RECURRENCE.contains(r) ? r : "NONE");
        }
        if (body.containsKey("daysOfWeek")) w.setDaysOfWeek(csvDays(body.get("daysOfWeek")));
        if (body.get("dayOfMonth") instanceof Number n) w.setDayOfMonth(n.intValue());
    }

    /**
     * Sunucu-tarafı doğrulama (M9) — sessizce inert (hiç tetiklenmeyen) bir bakım penceresini önler:
     * ayrıştırılamayan startAt (occurrence engine null döner → hiç aktif olmaz) ve gün seçilmemiş WEEKLY
     * (hiçbir güne uymaz → hiç tetiklenmez). Geçersizse IllegalArgumentException → 400.
     */
    static void validateWindow(MaintenanceWindow w) {
        if (parseIso(w.getStartAt()) == null)
            throw new IllegalArgumentException("Geçersiz başlangıç zamanı (örn. 2026-01-01T23:00:00)");
        String rec = w.getRecurrence() == null ? "NONE" : w.getRecurrence();
        if ("WEEKLY".equals(rec) && !hasAnyDayOfWeek(w.getDaysOfWeek()))
            throw new IllegalArgumentException("Haftalık bakım için en az bir gün seçin");
    }

    private static boolean hasAnyDayOfWeek(String csv) {
        if (csv == null || csv.isBlank()) return false;
        for (String p : csv.split(",")) {
            try { int d = Integer.parseInt(p.trim()); if (d >= 1 && d <= 7) return true; }
            catch (NumberFormatException ignore) { /* atla */ }
        }
        return false;
    }

    /** MaintenanceService.parse ile AYNI kabul: "yyyy-MM-ddTHH:mm:ss" veya Z/offset'li ISO; aksi halde null. */
    private static java.time.Instant parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.trim();
            if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:?\\d\\d$")) return java.time.Instant.parse(s);
            return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> dto(MaintenanceWindow w, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               w.getId());
        m.put("name",             w.getName());
        m.put("description",      w.getDescription());
        m.put("all_monitors",     w.getAllMonitors());
        m.put("targets",          parseTargets(w.getTargetsJson()));
        m.put("target_count",     maintenanceService.targetCount(w));
        m.put("timezone",         w.getTimezone());
        m.put("start_at",         w.getStartAt());
        m.put("duration_minutes", w.getDurationMinutes());
        m.put("recurrence",       w.getRecurrence());
        m.put("days_of_week",     w.getDaysOfWeek());
        m.put("day_of_month",     w.getDayOfMonth());
        m.put("active",           w.getActive());
        m.put("status",           maintenanceService.computeStatus(w, now));
        m.put("next_occurrence",  maintenanceService.nextOccurrence(w, now));
        m.put("team_id",          w.getTeamId());
        m.put("created_at",       w.getCreatedAt());
        m.put("created_by",       w.getCreatedBy());
        return m;
    }

    private MaintenanceWindow require(Long id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Bakım penceresi bulunamadı: " + id));
    }

    /**
     * Yazma yolları için TAKIM KAPSAMI. Eskiden yalnız izin anahtarı kontrol ediliyordu; kayıt id ile
     * yüklendiği için A takımının yöneticisi B takımının penceresini düzenleyebiliyor/silebiliyordu (IDOR).
     * Bakım pencereleri ALARM BASTIRDIĞI için etkisi yüksek: yanlış pencere silinince bastırılması gereken
     * alarmlar patlar, yanlış pencere açılınca gerçek kesinti sessizce yutulur.
     * Kural diğer izleme türleriyle aynı: global admin her pencereyi, takım yöneticisi yalnız kendi takımını.
     * Takımsız (legacy) pencereler yalnız global admin'e açık — sahibi belirlenemeyeni kimse devralmasın.
     */
    private MaintenanceWindow requireManageable(Long id, HttpSession session) {
        MaintenanceWindow w = require(id);
        if (!SessionScope.canManage(session, w.getTeamId()))
            throw new SecurityException("Bu bakım penceresini yönetme yetkiniz yok");
        return w;
    }

    private static String csvDays(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) if (o != null) parts.add(o.toString().trim());
            return parts.isEmpty() ? null : String.join(",", parts);
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String serializeTargets(Object v) {
        if (v == null) return null;
        try { return MAPPER.writeValueAsString(v); } catch (Exception e) { return null; }
    }

    private static Object parseTargets(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return MAPPER.readValue(json, List.class); } catch (Exception e) { return List.of(); }
    }

    private static boolean blank(Object o) { return o == null || o.toString().isBlank(); }
    private static String now() { return ISO.format(Instant.now()); }
    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }
    private Long sessionTeamId(HttpSession session) {
        Object v = session.getAttribute("teamId");
        return v instanceof Number num ? num.longValue() : null;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(r);
    }
}
