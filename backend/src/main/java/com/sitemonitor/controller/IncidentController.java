package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.IncidentAlertLinkService;
import com.sitemonitor.service.IncidentNotificationService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SRE Olay & Hata Geçmişi REST API'si — manuel ledger CRUD + filtreli arama + trend.
 * İzin: görüntüleme = incidents.view, yazma/silme = incidents.manage (katalog grant'leri,
 * Permission Matrix'ten yönetilir; defaults SchedulerService.seedMissingDefaults ile backfill).
 * AuthInterceptor /api/** için oturum zorunluluğunu zaten uygular. Mevcut akışlardan izole.
 */
@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService service;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final IncidentNotificationService notificationService;
    /** Alarmdan açılan kayıt (2026-10-01): alert_event_id doğrulaması + alarm detayının bağlı-kayıt sorgusu. */
    private final IncidentAlertLinkService alertLinks;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(name = "team_id", required = false) Long teamId,
            @RequestParam(name = "sla_breached", required = false) Boolean slaBreached,
            @RequestParam(required = false) Boolean open,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session) {
        requireView(session);
        int sz = Math.max(1, Math.min(size, 200));
        Page<IncidentRecord> result = this.service.list(q, severity, category, status, service, channel,
                since, until, teamId, slaBreached, open, incidentViewScope(session),
                PageRequest.of(Math.max(0, page), sz, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return ok(Map.of(
                "data",  result.getContent().stream().map(this::dto).toList(),
                "total", result.getTotalElements(),
                "page",  result.getNumber(),
                "size",  result.getSize()));
    }

    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> trends(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            HttpSession session) {
        requireView(session);
        return ok(Map.of("data", service.trends(since, until, incidentViewScope(session))));
    }

    // ── Yönetilen seçenekler (kanal / domain) — creatable dropdown beslemesi ──────────────────

    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> options(@RequestParam String type, HttpSession session) {
        requireView(session);
        // Seçenekler takıma özel: kullanıcı global + kendi takımını görür; admin tümünü.
        return ok(Map.of("data", service.listOptions(type, longAttr(session, "teamId"),
                SessionScope.isGlobalAdmin(session))));
    }

    @PostMapping("/options")
    public ResponseEntity<Map<String, Object>> addOption(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        String type  = body.get("type")  == null ? null : String.valueOf(body.get("type"));
        String value = body.get("value") == null ? null : String.valueOf(body.get("value"));
        String saved = service.addOption(type, value, (String) session.getAttribute("username"),
                longAttr(session, "teamId"));   // seçenek ekleyenin takımına yazılır (admin → global)
        auditService.recordAction("INCIDENT_OPTION_ADD", session, request,
                "INCIDENT_OPTION", type, "{\"value\":\"" + safe(saved) + "\"}");
        return ok(Map.of("data", saved));
    }

    @DeleteMapping("/options")
    public ResponseEntity<Map<String, Object>> deleteOption(
            @RequestParam String type, @RequestParam String value,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        // Kullanıcı yalnız kendi takımının seçeneğini silebilir; admin her şeyi.
        service.removeOption(type, value, longAttr(session, "teamId"),
                SessionScope.isGlobalAdmin(session));
        auditService.recordAction("INCIDENT_OPTION_DELETE", session, request,
                "INCIDENT_OPTION", type, "{\"value\":\"" + safe(value) + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    /**
     * Bir alarmdan açılmış olay kayıtları (2026-10-01) — alarm detayının "Olay kaydı #N" bağlantıları. Yalnız alarm
     * DETAYI çağırır (liste satırı başına istek yok). Kapı listeyle aynı: incidents.view + olay kaydı takım kapsamı.
     */
    @GetMapping("/by-alert/{alertEventId}")
    public ResponseEntity<Map<String, Object>> byAlert(@PathVariable Long alertEventId, HttpSession session) {
        requireView(session);
        return ok(Map.of("data", alertLinks.linked(alertEventId, incidentViewScope(session))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        requireView(session);
        IncidentRecord e = service.get(id);
        requireIncidentRead(session, e);   // takım kapsamı (IDOR engeli)
        return ok(Map.of("data", dto(e)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        // Hedef takım kapsamı (2026-09-28, A2): gövdedeki team_id doğrulanmadan kayda yazılıyordu. incidents.manage
        // USER'da açık → kullanıcı Takım B'nin team_id'siyle sahte olay açıp (send_notification ile) B'nin
        // ekibine/müdürüne mail attırabiliyor, B'nin SLA/ledger'ını kirletebiliyordu. Kural /transfer ile AYNI.
        if (body.containsKey("team_id")) requireTransferTarget(session, longVal(body.get("team_id")));
        // Alarmdan açılan kayıt: alarm var mı (400) ve kullanıcı onu görebiliyor mu (403) — kayıttan ÖNCE.
        alertLinks.validate(session, body, null);
        IncidentRecord e = service.create(body,
                (String) session.getAttribute("username"),
                longAttr(session, "userId"), longAttr(session, "teamId"));
        auditService.recordAction("INCIDENT_CREATE", session, request,
                "INCIDENT", String.valueOf(e.getId()),
                "{\"severity\":\"" + e.getSeverity() + "\",\"category\":\"" + e.getCategory() + "\""
                        + (e.getAlertEventId() != null ? ",\"alert_event_id\":" + e.getAlertEventId() : "") + "}");
        Map<String, Object> created = dto(e);
        // Mail YALNIZ kullanıcı "Mail gönder"i seçtiyse gider (varsayılan: kayıtta mail yok).
        if (Boolean.TRUE.equals(body.get("send_notification")))
            notificationService.notifyIncident(created, "NEW"); // takım + müdür executive bildirim (async)
        return ok(Map.of("data", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentRecord cur = service.get(id);
        requireIncidentWrite(session, cur);   // takım kapsamı (IDOR engeli)
        // Takım DEĞİŞİYORSA hedef de kapsamda olmalı (A2) — PUT, /transfer'in atladığı kapıdan geçen ikinci yoldu.
        // Aynı team_id'yi geri göndermek (form her kayıtta tüm alanları yollar) taşıma değildir → kapıya takılmaz.
        if (body.containsKey("team_id") && !java.util.Objects.equals(longVal(body.get("team_id")), cur.getTeamId()))
            requireTransferTarget(session, longVal(body.get("team_id")));
        // Kaynak alarm DEĞİŞİYORSA yeni alarm doğrulanır (aynı değeri geri göndermek kapıya takılmaz).
        alertLinks.validate(session, body, cur.getAlertEventId());
        String prevStatus = cur.getStatus(); // RESOLVED'e GEÇİŞ tespiti için
        IncidentRecord e = service.update(id, body, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_UPDATE", session, request,
                "INCIDENT", id.toString(),
                "{\"severity\":\"" + e.getSeverity() + "\",\"status\":\"" + e.getStatus() + "\"}");
        Map<String, Object> updated = dto(e);
        boolean justResolved = "RESOLVED".equals(e.getStatus()) && !"RESOLVED".equals(prevStatus);
        // Mail YALNIZ kullanıcı "Mail gönder"i seçtiyse gider (varsayılan: kayıtta mail yok).
        if (Boolean.TRUE.equals(body.get("send_notification")))
            notificationService.notifyIncident(updated, justResolved ? "RESOLVED" : "UPDATED"); // async bildirim
        return ok(Map.of("data", updated));
    }

    /** Gidecek bildirim mailinin ÖNİZLEME HTML'i — kaydetmez/göndermez (modal'daki "Mail Önizle"). */
    @PostMapping("/preview-notification")
    public ResponseEntity<Map<String, Object>> previewNotification(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireManage(session);
        String kind = body.get("kind") instanceof String k ? k : "UPDATED";
        return ok(Map.of("html", notificationService.previewHtml(body, kind)));
    }

    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        List<Long> ids = new java.util.ArrayList<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) {
                try { ids.add(Long.valueOf(String.valueOf(o))); } catch (Exception ignored) {}
            }
        }
        Long teamId = longVal(body.get("team_id"));
        String teamName = body.get("team_name") == null ? null : String.valueOf(body.get("team_name"));
        // Takım kapsamı (IDOR engeli) — kardeş uçların (update/delete/uploadImage) deseni burada EKSİKTİ:
        // yalnız incidents.manage isteniyordu ve o yetki USER'a açık, yani bir kullanıcı BAŞKA takımın
        // olayını kendi takımına (ya da kendi olayını yabancı bir takıma) taşıyabiliyordu.
        // KISMİ BAŞARI YOK: ekran toplu seçimle çalışıyor; yetkisiz TEK kayıt varsa hiçbiri taşınmaz —
        // yarım transfer kullanıcıya "hepsi taşındı" izlenimi verirdi.
        for (Long id : ids) {
            try {
                requireIncidentWrite(session, service.get(id));
            } catch (java.util.NoSuchElementException ignore) {
                /* kayıt yok → transfer da atlayacak (findAllById); doğrulama dışı bırakılır */
            }
        }
        requireTransferTarget(session, teamId);
        int n = service.transfer(ids, teamId, teamName, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_TRANSFER", session, request,
                "INCIDENT", String.valueOf(teamId),
                "{\"count\":" + n + ",\"team\":\"" + safe(teamName) + "\"}");
        return ok(Map.of("data", Map.of("transferred", n)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireDelete(session);
        requireIncidentWrite(session, service.get(id));   // takım kapsamı (IDOR engeli)
        IncidentRecord e = service.delete(id);
        auditService.recordAction("INCIDENT_DELETE", session, request,
                "INCIDENT", id.toString(),
                "{\"title\":\"" + safe(e.getTitle()) + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    // ── Görseller (markdown alanlarına gömülür) ───────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        requireIncidentWrite(session, service.get(id));   // takım kapsamı (IDOR engeli)
        IncidentImage img = service.storeImage(id, caption, file, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_IMAGE_ADD", session, request,
                "INCIDENT", id.toString(),
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", Map.of(
                "id", img.getId(), "size_bytes", img.getSizeBytes(), "content_type", img.getContentType())));
    }

    /** Taslak yükleme — olay henüz kaydedilmeden (create modu). incidentId yok; kaydedince
     *  IncidentService.linkImages markdown'daki id'leri yeni olaya bağlar. */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadDraftImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentImage img = service.storeImage(null, caption, file, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_IMAGE_ADD", session, request,
                "INCIDENT", "draft",
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", Map.of(
                "id", img.getId(), "size_bytes", img.getSizeBytes(), "content_type", img.getContentType())));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> serveImage(@PathVariable Long imageId, HttpSession session) {
        requireView(session);
        IncidentImage img = service.getImage(imageId);
        if (img.getIncidentId() != null) requireIncidentRead(session, service.get(img.getIncidentId())); // takım kapsamı
        // Taslak (henüz olaya bağlanmamış) görsel YALNIZ yükleyene / global görücüye açık (prod kapısı 2026-09-25, Y-1):
        // takım kapısı taslakta hiç işlemiyordu, id taramasıyla başkasının taslağı okunabiliyordu.
        else if (!SessionScope.isGlobalViewer(session)
                && !String.valueOf(session.getAttribute("username")).equalsIgnoreCase(img.getCreatedBy()))
            throw new java.util.NoSuchElementException("Görsel bulunamadı");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .header("Cache-Control", "private, max-age=3600")
                .body(img.getData());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireView(HttpSession session) {
        if (!permissionService.allows(session, "incidents.view", "view"))
            throw new SecurityException("incidents.view yetkisi gerekli");
    }

    private void requireManage(HttpSession session) {
        if (!permissionService.allows(session, "incidents.manage", "edit"))
            throw new SecurityException("incidents.manage yetkisi gerekli");
    }

    // ── Takım kapsamı (IDOR engeli): olaylar takıma-gizli. Olay SINIRI = takım ÜYELİĞİ (viewTeamIds);
    //    global viewer/admin (AUDIT dahil) tümünü görür. Eylem yetkisini (view/manage/delete) ayrıca
    //    requireView/requireManage/requireDelete kontrol eder — bu yüzden YAZMA da AYNI üyelik sınırını
    //    kullanır (USER'ın manageTeamIds'i boştur ama kendi takımının olayını düzenleyebilmeli). ──
    private List<Long> incidentViewScope(HttpSession session) {
        // OKUMA kapsamı: 7/24 izleme ekibi operatörü (2026-10-04) tüm takımların olay kayıtlarını okur (liste, eğilim,
        // alarm bağlantıları). Yazma kapıları (requireIncidentWrite / requireTransferTarget) bunu KULLANMAZ.
        return SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIds(session);
    }
    private void requireIncidentRead(HttpSession session, IncidentRecord e) {
        if (SessionScope.isGlobalViewer(session)) return;   // kayda dokunmadan (eski sıra korunur)
        if (canReadIncident(session, e.getTeamId(), e.getCreatedByTeamId())) return;
        throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }
    /**
     * Olay kaydı OKUMA kuralı — TEK kaynak (bu denetleyici ve Durum Sayfası, 2026-10-01): global görüntüleyici hepsini;
     * diğerleri kaydın takımı YA DA giren takım görüş kapsamındaysa ({@code findFiltered}'ın {@code :scope} koşuluyla
     * aynı). {@code incidents.view} izni ayrıca çağıranda aranır.
     */
    public static boolean canReadIncident(HttpSession session, Long teamId, Long createdByTeamId) {
        // 7/24 izleme ekibi operatörü (2026-10-04) her takımın kaydını OKUR; yazma sınırı inOwnIncidentScope'ta kalır.
        if (SessionScope.isNocOperator(session)) return true;
        return inOwnIncidentScope(session, teamId, createdByTeamId);
    }
    /** KENDİ takım sınırı (eski okuma = yazma sınırı) — global görüntüleyici hepsi; diğerleri kaydın / girenin takımı. */
    static boolean inOwnIncidentScope(HttpSession session, Long teamId, Long createdByTeamId) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> v = SessionScope.viewTeamIds(session);
        return v != null && ((teamId != null && v.contains(teamId)) || (createdByTeamId != null && v.contains(createdByTeamId)));
    }
    /**
     * Yazma sınırı = KENDİ takım üyeliği (eski okuma sınırı). Eylem yetkisini requireManage/requireDelete kontrol eder.
     * 7/24 operatörünün okuma genişlemesi (2026-10-04) BURAYA girmez: başka takımın kaydını okur, düzenleyemez/silemez.
     */
    private void requireIncidentWrite(HttpSession session, IncidentRecord e) {
        if (SessionScope.isGlobalViewer(session)) return;   // kayda dokunmadan (eski sıra korunur)
        if (inOwnIncidentScope(session, e.getTeamId(), e.getCreatedByTeamId())) return;
        throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }

    /**
     * Transfer HEDEFİ de kapsam içinde olmalı — kaynak kaydı doğrulamak tek başına yetmez.
     *
     * <p>Aksi halde kullanıcı kendi takımının olayını yabancı bir takıma taşıyıp kaydı KENDİ görüş
     * alanından çıkarabilirdi: geri alması mümkün olmayan tek yönlü bir veri kaybı (artık okuma
     * yetkisi de yok). Sınır, {@link #requireIncidentRead} ile aynı üyelik sınırıdır.
     */
    private void requireTransferTarget(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v != null && v.contains(teamId)) return;
        throw new SecurityException("Hedef takım sizin takım(lar)ınız arasında değil");
    }

    /** Silme yalnız TEAM_ADMIN/ADMIN (incidents.delete/execute); USER gir/düzenle yapar, silemez. */
    private void requireDelete(HttpSession session) {
        if (!permissionService.allows(session, "incidents.delete", "execute"))
            throw new SecurityException("incidents.delete yetkisi gerekli");
    }

    private Map<String, Object> dto(IncidentRecord e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("occurred_at", e.getOccurredAt());
        m.put("detected_at", e.getDetectedAt());
        m.put("resolved_at", e.getResolvedAt());
        m.put("severity", e.getSeverity());
        m.put("status", e.getStatus());
        m.put("category", e.getCategory());
        m.put("error_code", e.getErrorCode());
        m.put("function_code", e.getFunctionCode());
        m.put("channel_code", e.getChannelCode());
        m.put("service", e.getService());
        m.put("channel", e.getChannel());
        m.put("team_id", e.getTeamId());
        m.put("team_name", e.getTeamName());
        m.put("alert_event_id", e.getAlertEventId());
        m.put("rca_summary", e.getRcaSummary());
        m.put("description", e.getDescription());
        m.put("resolution_steps", e.getResolutionSteps());
        m.put("business_impact", e.getBusinessImpact());
        m.put("affected_services", e.getAffectedServices());
        m.put("problem_types", e.getProblemTypes());
        m.put("affected_app", e.getAffectedApp());
        m.put("affected_systems", e.getAffectedSystems());
        m.put("affected_customers", e.getAffectedCustomers());
        m.put("affected_transactions", e.getAffectedTransactions());
        m.put("sla_breached", e.getSlaBreached());
        m.put("error_budget_burn_pct", e.getErrorBudgetBurnPct());
        m.put("duration_minutes", e.getDurationMinutes());
        m.put("runbook_url", e.getRunbookUrl());
        m.put("tags", e.getTags());
        m.put("created_by", e.getCreatedBy());
        m.put("created_at", e.getCreatedAt());
        m.put("updated_by", e.getUpdatedBy());
        m.put("updated_at", e.getUpdatedAt());
        return m;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    private static Long longVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
    }

    private Long longAttr(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        if (v instanceof Long l) return l;
        if (v != null) { try { return Long.valueOf(v.toString()); } catch (Exception ignored) {} }
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\\", "");
    }
}
