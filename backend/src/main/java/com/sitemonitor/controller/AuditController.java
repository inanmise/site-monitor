package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository           auditLogRepo;
    private final LatestCheckRepository        latestCheckRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository               teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final com.sitemonitor.service.DeviceHistoryService deviceHistoryService;
    private final com.sitemonitor.repository.AppUserRepository appUserRepo;
    private final com.sitemonitor.service.WeakAlgorithmReportService weakAlgoService;
    private final com.sitemonitor.service.EmailNotificationService emailService;
    private final com.sitemonitor.service.UserPushService userPushService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> listAudit(
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "50")    int     size,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       Long    actorId,
            @RequestParam(required = false)       String  eventType,   // CSV: çoklu tür
            @RequestParam(required = false)       String  resourceType,
            @RequestParam(required = false)       String  resourceId,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  ip,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(required = false)       String  q,           // serbest metin
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        Page<AuditLog> result = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.getContent());
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("total_pages", result.getTotalPages());
        return ok(body);
    }

    /** Bir kaynağın tüm değişiklik geçmişi ("bu izlemeye/kullanıcıya kim ne yaptı"). */
    @GetMapping("/audit/resource/{type}/{id}")
    public ResponseEntity<Map<String, Object>> resourceHistory(
            @PathVariable String type, @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        var rows = auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                type, id, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Bir kullanıcının tüm eylemleri. */
    @GetMapping("/audit/actor/{actorId}")
    public ResponseEntity<Map<String, Object>> actorHistory(
            @PathVariable Long actorId,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        var rows = auditLogRepo.findByActorIdOrderByEventTimeDesc(
                actorId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Aynı correlation ID'den doğan ilişkili olaylar (detay panelinde). */
    @GetMapping("/audit/correlation/{cid}")
    public ResponseEntity<Map<String, Object>> correlated(@PathVariable String cid, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        return ok(Map.of("data", auditLogRepo.findByCorrelationIdOrderBySeqAsc(cid)));
    }

    /** Hash zinciri bütünlük doğrulaması — kurcalama tespiti. */
    @GetMapping("/audit/integrity")
    public ResponseEntity<Map<String, Object>> integrity(HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        AuditService.ChainVerification v = auditService.verifyChain();
        return ok(Map.of("data", Map.of("ok", v.ok(), "checked", v.checked(),
                "broken_seq", v.brokenSeq() == null ? "" : v.brokenSeq(),
                "broken_id", v.brokenId() == null ? "" : v.brokenId())));
    }

    /** Filtreli sonucun CSV/JSON dışa aktarımı — DIŞA AKTARMA İŞLEMİ KENDİSİ DE denetlenir (AUDIT_EXPORT). */
    @GetMapping("/audit/export")
    public ResponseEntity<String> export(
            @RequestParam(defaultValue = "csv")   String  format,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       Long    actorId,
            @RequestParam(required = false)       String  eventType,
            @RequestParam(required = false)       String  resourceType,
            @RequestParam(required = false)       String  resourceId,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  ip,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(required = false)       String  q,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session, HttpServletRequest request) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        int cap = 50_000;
        List<AuditLog> rows = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                PageRequest.of(0, cap)).getContent();

        boolean json = "json".equalsIgnoreCase(format);
        String body = json ? toJson(rows) : toCsv(rows);
        // Denetimin denetimi: kim, hangi filtreyle, kaç kayıt dışa aktardı.
        auditService.recordAction("AUDIT_EXPORT", session, request, "AUDIT_LOG", "export",
                "{\"format\":\"" + (json ? "json" : "csv") + "\",\"rows\":" + rows.size() + "}");

        String fname = "audit-" + now().substring(0, 10) + (json ? ".json" : ".csv");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fname + "\"")
                .header("Content-Type", (json ? "application/json" : "text/csv") + "; charset=utf-8")
                .body(body);
    }

    @GetMapping("/audit/stats")
    public ResponseEntity<Map<String, Object>> auditStats(HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        // Stat üretimi AuditService.buildStats()'a taşındı (60 sn Caffeine cache — audit_log
        // ölçeklenince her açılışta 10 aggregate çalıştırmamak için). Auth burada kalır, cache'lenmez.
        return ok(Map.of("data", auditService.buildStats()));
    }

    /**
     * Olay türü kataloğu — arayüzdeki filtre listesinin KAYNAĞI.
     *
     * <p>Liste ön yüzde elle tutulduğu sürece sürükleniyordu: 162 türün yalnız 32'si
     * seçilebiliyor, bütün {@code MAINTENANCE_*}, {@code SQL_EXECUTE} ve 12 {@code WEEKLY_REPORT_*}
     * türü filtrede hiç görünmüyordu. Katalog sunucudan gelince yeni bir tür eklendiğinde arayüze
     * dokunmak gerekmez.
     *
     * <p>{@code count} son 90 günün tür başına sayısıdır: sıfır olan türler arayüzde soluk
     * gösterilir, böylece kullanıcı boş döneceğini bildiği bir filtreyi uygulamaz.
     */
    @GetMapping("/audit/event-types")
    public ResponseEntity<Map<String, Object>> auditEventTypes(HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        Map<String, Long> counts = auditService.eventTypeCounts();
        List<Map<String, Object>> data = new ArrayList<>();
        for (com.sitemonitor.service.AuditEventCatalog.Event e : com.sitemonitor.service.AuditEventCatalog.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", e.type());
            row.put("category", e.category());
            row.put("count", counts.getOrDefault(e.type(), 0L));
            data.add(row);
        }
        return ok(Map.of("data", data,
                "categories", com.sitemonitor.service.AuditEventCatalog.CATEGORY_ORDER));
    }

    /**
     * Tek bir denetim satırı — olay bağlantısının ("şu olaya bak") çalışabilmesi için.
     *
     * <p>Bugüne kadar tekil satır çekmenin yolu yoktu: paylaşılan bir bağlantı, satır o anda
     * listelenen sayfada değilse sessizce hiçbir şey açmıyordu.
     */
    @GetMapping("/audit/{id}")
    public ResponseEntity<Map<String, Object>> auditById(@PathVariable Long id, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        return auditLogRepo.findById(id)
                .map(row -> ok(Map.of("data", row)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("success", false, "error", "Denetim kaydı bulunamadı")));
    }

    /**
     * Zayıf Algoritma Raporu — zengin gövde {@link com.sitemonitor.service.WeakAlgorithmReportService}'ten
     * (2026-09-12). Eski alanlar ({@code data/total/critical/high}) aynen korunur.
     */
    @GetMapping("/audit/weak-algorithms")
    public ResponseEntity<Map<String, Object>> weakAlgorithmReport(HttpSession session) {
        permissionService.require(session, "weak_algo.read", "view");
        return ok(weakAlgoService.build());
    }

    /** CSV dışa aktarma — sertifika + TLS + zincir bulguları tek dosyada; denetim kaydı düşer. */
    @GetMapping(value = "/audit/weak-algorithms/export", produces = "text/csv")
    public ResponseEntity<String> weakAlgorithmExport(HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.read", "view");
        Map<String, Object> body = weakAlgoService.build();
        String csv = weakAlgoService.toCsv(body);
        auditService.recordAction("WEAK_ALGO_EXPORT", session, request, "WEAK_ALGO", "export",
                "{\"rows\":" + body.get("total") + ",\"tls\":" + ((Map<?, ?>) body.get("tls")).get("total")
                + ",\"chain\":" + ((Map<?, ?>) body.get("chain")).get("total") + "}");
        String fname = "weak-algorithms-" + now().substring(0, 10) + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fname + "\"")
                .header("Content-Type", "text/csv; charset=utf-8")
                .body(csv);
    }

    /** İstisna (kabul edildi / planlı yenileme): {reason, until}. Süresi geçince satır yeniden aktif olur. */
    @PostMapping("/audit/weak-algorithms/{domain}/exception")
    public ResponseEntity<Map<String, Object>> weakAlgorithmException(
            @PathVariable String domain, @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.manage", "edit");
        try {
            var e = weakAlgoService.setException(domain, body.get("reason"), body.get("until"),
                    String.valueOf(session.getAttribute("username")));
            auditService.recordAction("WEAK_ALGO_EXCEPTION_SET", session, request, "WEAK_ALGO", domain,
                    "{\"until\":\"" + e.getUntil() + "\"}");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", e.getDomain()); out.put("reason", e.getReason()); out.put("until", e.getUntil());
            return ok(Map.of("data", out));
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error",
                    "until_past".equals(ex.getMessage()) ? "Bitiş tarihi geçmişte olamaz" : "Geçerli bir bitiş tarihi (yyyy-aa-gg) gerekli"));
        }
    }

    @DeleteMapping("/audit/weak-algorithms/{domain}/exception")
    public ResponseEntity<Map<String, Object>> weakAlgorithmExceptionClear(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.manage", "edit");
        boolean removed = weakAlgoService.clearException(domain);
        if (removed) auditService.recordAction("WEAK_ALGO_EXCEPTION_CLEAR", session, request, "WEAK_ALGO", domain, null);
        return ok(Map.of("removed", removed));
    }

    /**
     * Sorumlu takıma bildir — e-posta (takım adresi) + push (takım alıcıları, seviye CRITICAL).
     * Alarm olayı üretmez; gün içinde aynı alan için tekrar tıklama push'u yeniden yazmaz (dedupe).
     */
    @PostMapping("/audit/weak-algorithms/{domain}/notify")
    public ResponseEntity<Map<String, Object>> weakAlgorithmNotify(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.manage", "edit");
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Alan envanterde yok"));
        LatestCheck lc = latestCheckRepo.findById(domain).orElse(null);
        Team team = inv.getTeamId() == null ? null : teamRepo.findById(inv.getTeamId()).orElse(null);
        if (team == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Alanın sorumlu takımı yok"));

        List<String> weaknesses = new ArrayList<>();
        String severity = lc == null ? null : com.sitemonitor.service.CertificateHealthRules.classifyWeakness(
                lc.getSignatureAlgorithm(), lc.getPublicKeyAlgorithm(), lc.getPublicKeySize(), weaknesses);
        String what = weaknesses.isEmpty() ? "TLS/zincir bulgusu" : String.join(", ", weaknesses);
        String subject = "[Site Monitor] Zayıf algoritma — " + domain;
        String message = "Sertifika güvenlik standardını karşılamıyor: " + what
                + ". Sorumlu takımla koordineli yenileme planı gerekiyor. Rapor: Ayarlar → Zayıf Algoritma Raporu.";

        Map<String, Object> out = new LinkedHashMap<>();
        String email = team.getEmail();
        if (email != null && !email.isBlank()) {
            String st = emailService.sendAlert(new String[] {email}, subject, message, domain,
                    severity == null ? "WARNING" : severity, "WEAK_ALGORITHM",
                    lc == null ? null : lc.getDaysRemaining(), null);
            out.put("email", st);
            out.put("email_to", email);
        } else {
            out.put("email", "SKIPPED_NO_TEAM_EMAIL");
        }
        String day = now().substring(0, 10);
        out.put("push", userPushService.enqueueTeamNotice(team.getId(), "WEAK_ALGO", "CRITICAL", domain,
                "[Zayıf algoritma] " + domain + " — " + what, "WEAK_ALGO:" + domain + ":" + day));
        auditService.recordAction("WEAK_ALGO_NOTIFY", session, request, "WEAK_ALGO", domain,
                "{\"team_id\":" + team.getId() + ",\"email\":\"" + out.get("email") + "\"}");
        return ok(Map.of("data", out));
    }


    // ── Cihaz Geçmişi — ADMIN salt-okunur görünümü (K8) ──────────────────────
    //
    // AYRI uç olması BİLİNÇLİ: /api/me/devices self-scope'tur ve kimliği YALNIZ oturumdan okur;
    // oraya "başka kullanıcı" parametresi eklemek o güvencenin kendisini delerdi. Buradaki uç
    // denetim yetkisiyle (audit_log.read + AUDIT/ADMIN) korunur ve YALNIZ OKUR — iptal/çıkış
    // eylemleri bu yola HİÇ açılmaz; bir yönetici başkasının cihazını buradan düşüremez.

    @GetMapping("/users/{id}/devices")
    public ResponseEntity<Map<String, Object>> userDevices(@PathVariable Long id, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        AppUser target = appUserRepo.findById(id).orElse(null);
        if (target == null) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Kullanıcı bulunamadı"));

        // currentTokenHash = null: yöneticinin tarayıcısı hedef kullanıcının cihazı DEĞİL,
        // dolayısıyla hiçbir satır "bu cihaz" diye işaretlenmez.
        return ok(Map.of("data", deviceHistoryService.devicesFor(target, null)));
    }

    @GetMapping("/users/{id}/devices/logins")
    public ResponseEntity<Map<String, Object>> userDeviceLogins(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean failed,
            HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        AppUser target = appUserRepo.findById(id).orElse(null);
        if (target == null) return ResponseEntity.status(404).body(Map.of("success", false, "error", "Kullanıcı bulunamadı"));

        return ok(Map.of("data", deviceHistoryService.loginsFor(target, failed, page, size)));
    }

    private void requireAuditAccess(HttpSession session) {
        String role = (String) session.getAttribute("systemRole");
        if (!SessionScope.isGlobalAdmin(session) && !"AUDIT".equals(role))
            throw new SecurityException("Audit access required");
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String nil(String v) { return (v == null || v.isBlank()) ? null : v; }
    private String now()         { return ISO.format(Instant.now()); }

    private String like(String s) {
        String v = nil(s);
        return v == null ? null : "%" + v.toLowerCase() + "%";
    }

    private List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /** Boş liste yerine dummy (typeFilter=false kısa-devre ederken IN () çalışmasın). */
    private List<String> typesOrDummy(String s) {
        List<String> c = csv(s);
        return c.isEmpty() ? List.of("") : c;
    }

    private String toCsv(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("seq,event_time,event_type,actor,actor_role,ip_address,resource_type,resource_id,outcome,failure_reason,detail,changes,correlation_id\n");
        for (AuditLog a : rows) {
            sb.append(csvCell(a.getSeq())).append(',').append(csvCell(a.getEventTime())).append(',')
              .append(csvCell(a.getEventType())).append(',').append(csvCell(a.getActor())).append(',')
              .append(csvCell(a.getActorRole())).append(',').append(csvCell(a.getIpAddress())).append(',')
              .append(csvCell(a.getResourceType())).append(',').append(csvCell(a.getResourceId())).append(',')
              .append(csvCell(a.getOutcome())).append(',').append(csvCell(a.getFailureReason())).append(',')
              .append(csvCell(a.getDetail())).append(',')
              .append(csvCell(a.getChanges())).append(',').append(csvCell(a.getCorrelationId())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Ortak kurala devreder ({@link com.sitemonitor.util.Csv#cell}) — denetim disa aktarimi da
     * formul notrlemesi almali: aktor adi, kaynak adi ve detay alanlari dis veriden besleniyor.
     */
    private static String csvCell(Object o) {
        return com.sitemonitor.util.Csv.cell(o);
    }

    private String toJson(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (AuditLog a : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"seq\":").append(a.getSeq())
              .append(",\"event_time\":").append(js(a.getEventTime()))
              .append(",\"event_type\":").append(js(a.getEventType()))
              .append(",\"actor\":").append(js(a.getActor()))
              .append(",\"actor_role\":").append(js(a.getActorRole()))
              .append(",\"ip_address\":").append(js(a.getIpAddress()))
              .append(",\"resource_type\":").append(js(a.getResourceType()))
              .append(",\"resource_id\":").append(js(a.getResourceId()))
              .append(",\"outcome\":").append(js(a.getOutcome()))
              .append(",\"failure_reason\":").append(js(a.getFailureReason()))
              .append(",\"detail\":").append(js(a.getDetail()))
              .append(",\"changes\":").append(js(a.getChanges()))
              .append(",\"row_hash\":").append(js(a.getRowHash()))
              .append(",\"correlation_id\":").append(js(a.getCorrelationId()))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }
}
