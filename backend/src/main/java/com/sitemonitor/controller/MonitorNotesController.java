package com.sitemonitor.controller;

import com.sitemonitor.model.MonitorGuide;
import com.sitemonitor.model.MonitorNote;
import com.sitemonitor.repository.MonitorGuideRepository;
import com.sitemonitor.repository.MonitorNoteRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Hedef-bazlı (ping=host, keyword=url) "Rehber & Notlar": alarm gelince ne yapılacağını anlatan tek REHBER
 * bloğu + her sorunda elle eklenen yapılandırılmış NOT günlüğü (Sorun / Yapılan işlem / Kök neden / Bakılacak
 * yerler). Yetki: görüntüleme monitoring.read, yazma monitoring.crud; düzenle/sil yalnız yazar veya TEAM_ADMIN/ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/notes")
@RequiredArgsConstructor
public class MonitorNotesController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /**
     * Notlar sekmesinin desteklediği izleme tipleri — {@link MonitorTypeCatalog#ORDER}'dan TÜRETİLİR.
     *
     * <p>Eskiden dokuz tip ELLE yazılıydı ve hiçbir kapı ona bakmıyordu: yeni bir izleme türü
     * eklendiğinde o türün Notlar sekmesi sessizce "Geçersiz izleme tipi" (400) döndürüyordu.
     * Bu gerçekten yaşandı — PAGE eksikti ve Sayfa Bütünlüğü modalında notlar açılmıyordu
     * (2026-08-03). Liste artık katalogla birlikte büyür.
     *
     * <p>{@code cert} BİLEREK dışarıda: sertifikaların kendi not yüzeyi var (sertifika modalı
     * ayrı uçları kullanır), buradaki hedef anahtarı monitör kimliği/host'udur.
     */
    static final Set<String> NOTE_EXCLUDED_TYPES = Set.of("cert");

    static final Set<String> TYPES = MonitorTypeCatalog.ORDER.stream()
            .filter(t -> !NOTE_EXCLUDED_TYPES.contains(t))
            .map(t -> t.toUpperCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final int MAX = 5000;

    private final MonitorGuideRepository guideRepo;
    private final MonitorNoteRepository noteRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** Rehber + notlar (hedefe göre). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam String type, @RequestParam String target, HttpSession session) {
        requireAuth(session);
        permissionService.require(session, "monitoring.read", "view");
        String t = normType(type);
        String tg = reqTarget(target);
        MonitorGuide guide = guideRepo.findByMonitorTypeAndTarget(t, tg).orElse(null);
        // TAKIM İZOLASYONU: not içeriği operasyoneldir ("Sorun / Yapılan işlem / Kök neden /
        // Bakılacak yerler") — başka bir takımın iç altyapı bilgisi. Kayıt oluşturulurken takım
        // ZATEN damgalanıyordu ({@code n.setTeamId}) ama hiçbir okuma/yazma yolunda kullanılmıyordu.
        List<MonitorNote> notes =
                noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc(t, tg)
                        .stream().filter(n -> noteVisible(session, n)).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("guide", guide);
        data.put("notes", notes);
        return ok(Map.of("data", data));
    }

    /** Rehberi kaydet (hedef başına tek satır — upsert). */
    @PutMapping("/guide")
    public ResponseEntity<Map<String, Object>> saveGuide(
            @RequestBody Map<String, String> body, HttpSession session, HttpServletRequest request) {
        requireAuth(session);
        permissionService.require(session, "monitoring.crud", "edit");
        String t = normType(body.get("type"));
        String tg = reqTarget(body.get("target"));
        String text = body.getOrDefault("guide", "");
        if (text != null && text.length() > MAX * 4)
            throw new IllegalArgumentException("Rehber çok uzun");
        MonitorGuide g = guideRepo.findByMonitorTypeAndTarget(t, tg).orElseGet(MonitorGuide::new);
        g.setMonitorType(t);
        g.setTarget(tg);
        g.setGuide(text);
        g.setUpdatedAt(now());
        g.setUpdatedBy(actor(session));
        MonitorGuide saved = guideRepo.save(g);
        auditService.recordAction("MONITOR_GUIDE_SAVE", session, request, "MONITOR_GUIDE",
                String.valueOf(saved.getId()), detail(t, tg));
        return ok(Map.of("data", saved, "message", "Guide saved"));
    }

    /** Yeni not ekle. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addNote(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAuth(session);
        permissionService.require(session, "monitoring.crud", "edit");
        String t = normType(str(body.get("type")));
        String tg = reqTarget(str(body.get("target")));
        String problem = str(body.get("problem"));
        if (problem == null || problem.isBlank())
            throw new IllegalArgumentException("Sorun alanı zorunlu");
        checkLen(problem);
        String action = str(body.get("action_taken")); checkLen(action);
        String root   = str(body.get("root_cause"));   checkLen(root);
        String refs   = str(body.get("refs"));          checkLen(refs);

        String user = (String) session.getAttribute("username");
        String name = (String) session.getAttribute("displayName");
        if (name == null || name.isBlank()) name = user;

        MonitorNote n = new MonitorNote();
        n.setMonitorType(t);
        n.setTarget(tg);
        n.setProblem(problem.trim());
        n.setActionTaken(trimOrNull(action));
        n.setRootCause(trimOrNull(root));
        n.setRefs(trimOrNull(refs));
        n.setTeamId(sessionTeamId(session));
        n.setAuthorUsername(user);
        n.setAuthorName(name);
        n.setCreatedAt(now());
        MonitorNote saved = noteRepo.save(n);
        auditService.recordAction("MONITOR_NOTE_ADD", session, request, "MONITOR_NOTE",
                String.valueOf(saved.getId()), detail(t, tg));
        return ok(Map.of("data", saved, "message", "Note added"));
    }

    /** Notu düzenle (yalnız yazar veya TEAM_ADMIN/ADMIN). */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateNote(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAuth(session);
        permissionService.require(session, "monitoring.crud", "edit");
        MonitorNote n = noteRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Not bulunamadı: " + id));
        if (n.getDeletedAt() != null) throw new NoSuchElementException("Not silinmiş");
        requireModify(session, n);
        String problem = str(body.get("problem"));
        if (problem == null || problem.isBlank())
            throw new IllegalArgumentException("Sorun alanı zorunlu");
        checkLen(problem);
        String action = str(body.get("action_taken")); checkLen(action);
        String root   = str(body.get("root_cause"));   checkLen(root);
        String refs   = str(body.get("refs"));          checkLen(refs);
        n.setProblem(problem.trim());
        n.setActionTaken(trimOrNull(action));
        n.setRootCause(trimOrNull(root));
        n.setRefs(trimOrNull(refs));
        n.setUpdatedAt(now());
        n.setUpdatedBy(actor(session));
        MonitorNote saved = noteRepo.save(n);
        auditService.recordAction("MONITOR_NOTE_EDIT", session, request, "MONITOR_NOTE",
                String.valueOf(id), detail(n.getMonitorType(), n.getTarget()));
        return ok(Map.of("data", saved, "message", "Note updated"));
    }

    /** Notu soft-delete et (yalnız yazar veya TEAM_ADMIN/ADMIN). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteNote(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAuth(session);
        permissionService.require(session, "monitoring.crud", "edit");
        MonitorNote n = noteRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Not bulunamadı: " + id));
        if (n.getDeletedAt() != null) return ok(Map.of("message", "Zaten silinmiş"));
        requireModify(session, n);
        n.setDeletedAt(now());
        n.setDeletedBy(actor(session));
        noteRepo.save(n);
        auditService.recordAction("MONITOR_NOTE_DELETE", session, request, "MONITOR_NOTE",
                String.valueOf(id), detail(n.getMonitorType(), n.getTarget()));
        return ok(Map.of("message", "Note deleted"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    /**
     * Düzenleme/silme yetkisi — <b>hangi takımın</b> yöneticisi olduğuna bakar.
     *
     * <p>Eski hâl yalnızca ROL DİZESİNE bakıyordu ({@code "TEAM_ADMIN".equals(role)}): A takımının
     * yöneticisi, B takımının notunu düzenleyip silebiliyordu (silme yumuşak, geri alma arayüzü
     * de yok). Kapı artık notun kendi takım damgasına bağlı.
     */
    private void requireModify(HttpSession session, MonitorNote n) {
        String user = (String) session.getAttribute("username");
        if (user != null && user.equals(n.getAuthorUsername())) return;   // kendi notu
        if (SessionScope.isGlobalAdmin(session)) return;
        // Takımsız (eski) kayıt: kime ait olduğu bilinmediği için yalnız global admin ya da yazarı
        // değiştirebilir — rol dizesine geri düşmek kapıyı yeniden açardı.
        if (n.getTeamId() == null)
            throw new SecurityException("Bu notu yalnız ekleyen ya da yönetici değiştirebilir");
        if (!SessionScope.canManage(session, n.getTeamId()))
            throw new SecurityException("Bu not sizin takımınıza ait değil");
    }

    /**
     * Not bu oturumda GÖRÜNMELİ mi.
     *
     * <p>Takımsız (eski ya da global admin tarafından yazılmış) notlar görünür kalır: kime ait
     * oldukları bilinmiyor ve gizlemek mevcut içeriği sessizce yok ederdi. Yeni notların hepsi
     * takım damgası taşıyor, dolayısıyla kapı ileriye dönük tam.
     */
    private boolean noteVisible(HttpSession session, MonitorNote n) {
        if (n.getTeamId() == null) return true;
        return SessionScope.canView(session, n.getTeamId());
    }

    private static String normType(String type) {
        String t = type != null ? type.trim().toUpperCase() : "";
        if (!TYPES.contains(t)) throw new IllegalArgumentException("Geçersiz izleme tipi: " + type);
        return t;
    }

    private static String reqTarget(String target) {
        String tg = target != null ? target.trim() : "";
        if (tg.isEmpty()) throw new IllegalArgumentException("target zorunlu");
        if (tg.length() > 500) throw new IllegalArgumentException("target çok uzun");
        return tg;
    }

    private static void checkLen(String s) {
        if (s != null && s.length() > MAX)
            throw new IllegalArgumentException("Alan " + MAX + " karakteri aşamaz");
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String trimOrNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s.trim(); }

    private static String detail(String type, String target) {
        return "{\"type\":\"" + type + "\",\"target\":\"" + target.replace("\"", "\\\"") + "\"}";
    }

    private Long sessionTeamId(HttpSession session) {
        Object v = session.getAttribute("teamId");
        return v instanceof Number num ? num.longValue() : null;
    }

    private static String now() { return ISO.format(Instant.now()); }

    private void requireAuth(HttpSession session) {
        if (session.getAttribute("username") == null)
            throw new SecurityException("Authentication required");
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(r);
    }
}
