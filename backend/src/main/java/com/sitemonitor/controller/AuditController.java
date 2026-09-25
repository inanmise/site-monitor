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
        TeamActorScope scope = auditReadScope(session);

        Page<AuditLog> result = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                scope.all(), scope.teamIds(), scope.actorIds(), scope.actorNames(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.getContent());
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("total_pages", result.getTotalPages());
        // Ön yüz sistem-geneli yüzeyleri (özet, bütünlük) buna göre gösterir/gizler.
        body.put("scope", scope.all() ? "ALL" : "TEAM");
        return ok(body);
    }

    /** Bir kaynağın tüm değişiklik geçmişi ("bu izlemeye/kullanıcıya kim ne yaptı"). */
    @GetMapping("/audit/resource/{type}/{id}")
    public ResponseEntity<Map<String, Object>> resourceHistory(
            @PathVariable String type, @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        TeamActorScope scope = auditReadScope(session);
        var rows = scope.all()
                ? auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                        type, id, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                : scopedHistory(scope, null, type, id, limit);
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Bir kullanıcının tüm eylemleri. */
    @GetMapping("/audit/actor/{actorId}")
    public ResponseEntity<Map<String, Object>> actorHistory(
            @PathVariable Long actorId,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        TeamActorScope scope = auditReadScope(session);
        var rows = scope.all()
                ? auditLogRepo.findByActorIdOrderByEventTimeDesc(actorId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                : scopedHistory(scope, actorId, null, null, limit);
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Aynı correlation ID'den doğan ilişkili olaylar (detay panelinde). */
    @GetMapping("/audit/correlation/{cid}")
    public ResponseEntity<Map<String, Object>> correlated(@PathVariable String cid, HttpSession session) {
        TeamActorScope scope = auditReadScope(session);
        return ok(Map.of("data", visible(auditLogRepo.findByCorrelationIdOrderBySeqAsc(cid), scope)));
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
        // Dışa aktarma listeyle AYNI kapsamı taşır: ekip kullanıcısı yalnız ekip arkadaşlarının satırlarını indirir.
        TeamActorScope scope = auditReadScope(session);

        int cap = 50_000;
        List<AuditLog> rows = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                scope.all(), scope.teamIds(), scope.actorIds(), scope.actorNames(),
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
        // Katalog ekip kapsamındaki süzgeç listesi için de gerekli; SAYIMLAR ise sistem-geneli (tüm kullanıcıların
        // son 90 günü) → yalnız admin/AUDIT'e döner. Ekip kullanıcısında `count` alanı hiç yoktur.
        boolean full = auditReadScope(session).all();

        Map<String, Long> counts = full ? auditService.eventTypeCounts() : Map.of();
        List<Map<String, Object>> data = new ArrayList<>();
        for (com.sitemonitor.service.AuditEventCatalog.Event e : com.sitemonitor.service.AuditEventCatalog.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", e.type());
            row.put("category", e.category());
            if (full) row.put("count", counts.getOrDefault(e.type(), 0L));
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
        TeamActorScope scope = auditReadScope(session);

        // Kapsam dışı satır 404 döner (403 değil): kaydın VARLIĞI da ekip dışına sızmasın.
        return auditLogRepo.findById(id)
                .filter(row -> teamVisible(row, scope))
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
        // Rapor çağıranın GÖRÜŞ kapsamıyla kurulur: viewTeamIds global admin/AUDIT için null
        // (tüm takımlar), kapsamlı müdür için kendi takımları. Yazma uçları 2026-09-23'te
        // kapsama alınmıştı; okuma tarafı aynı gün eşitlendi.
        return ok(weakAlgoService.build(SessionScope.viewTeamIds(session)));
    }

    /** CSV dışa aktarma — sertifika + TLS + zincir bulguları tek dosyada; denetim kaydı düşer. */
    @GetMapping(value = "/audit/weak-algorithms/export", produces = "text/csv")
    public ResponseEntity<String> weakAlgorithmExport(HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weak_algo.read", "view");
        Map<String, Object> body = weakAlgoService.build(SessionScope.viewTeamIds(session));   // CSV de kapsamlı
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
        ResponseEntity<Map<String, Object>> denied = denyIfDomainNotManageable(session, domain);
        if (denied != null) return denied;
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
        ResponseEntity<Map<String, Object>> denied = denyIfDomainNotManageable(session, domain);
        if (denied != null) return denied;
        boolean removed = weakAlgoService.clearException(domain);
        if (removed) auditService.recordAction("WEAK_ALGO_EXCEPTION_CLEAR", session, request, "WEAK_ALGO", domain, com.sitemonitor.service.AuditDetail.of("domain", domain, "removed", true));
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
        ResponseEntity<Map<String, Object>> denied = denyIfDomainNotManageable(session, domain);
        if (denied != null) return denied;
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
        if (!hasFullAuditAccess(session)) throw new SecurityException("Audit access required");
    }

    /** Sistem-geneli denetçi: global admin ya da AUDIT rolü. */
    private static boolean hasFullAuditAccess(HttpSession session) {
        return SessionScope.isGlobalAdmin(session) || "AUDIT".equals(session.getAttribute("systemRole"));
    }

    /**
     * Denetim KAYITLARININ okuma kapsamı (2026-09-25, kullanıcı kararı: "ekip üyeleri görebilsin — tüm olaylar,
     * tam ayrıntı"). Global admin / AUDIT: sınırsız, {@code audit_log.read} izni aranır (değişmedi). Diğer herkes
     * (USER, TEAM_ADMIN, kapsamlı müdür): yalnız görüş alanındaki takımların ÜYELERİNİN eylemleri — IP, konum ve
     * cihaz dâhil tam satır. Ekip kapsamında {@code audit_log.read} ARANMAZ: kural "kendi ekibinin kaydı"dır ve
     * satırlar zaten aktör üyeliğiyle sınırlanır ({@code NotificationGroupController#history} emsali). Kullanıcı
     * kararı (2026-09-25, regresyon R8): ekip görünürlüğü HER ZAMAN açık, matristen kapatılmaz; matris satırı
     * {@code audit_log.read} yalnız SİSTEM GENELİ denetimi (admin/AUDIT) anlatır. ADMIN/AUDIT aktörlerinin
     * satırları ekip kapsamına girmez (R2).
     *
     * <p>Sistem-geneli yüzeyler (hash-zinciri bütünlüğü, özet istatistikler, olay türü sayımları, başka
     * kullanıcının cihaz geçmişi) bu kapsamı KULLANMAZ — {@link #requireAuditAccess} ile admin/AUDIT'te kalır.
     */
    private TeamActorScope auditReadScope(HttpSession session) {
        if (hasFullAuditAccess(session)) {
            permissionService.require(session, "audit_log.read", "view");
            return TeamActorScope.unrestricted();
        }
        return TeamActorScope.ofTeams(SessionScope.viewTeamIds(session), appUserRepo);
    }

    /** Ekip kapsamına HİÇ girmeyen aktör rolleri (kullanıcı kararı 2026-09-25, regresyon R2) — sorgudaki kuralla aynı. */
    private static final Set<String> SYSTEM_WIDE_ROLES = Set.of("ADMIN", "AUDIT");

    /** Satır bu kapsamda görünür mü? Sayfalanan liste AYNI kuralı sorguda uygular ({@code findAdvanced}). */
    private static boolean teamVisible(AuditLog r, TeamActorScope scope) {
        if (scope.all()) return true;
        if (r.getActorRole() != null && SYSTEM_WIDE_ROLES.contains(r.getActorRole())) return false;
        return scope.allows(r.getActorTeamId(), r.getActorId(), r.getActor());
    }

    /** Bellekte süzülen küçük listeler (korelasyon) için aynı kural. */
    private static List<AuditLog> visible(List<AuditLog> rows, TeamActorScope scope) {
        if (scope.all()) return rows;
        return rows.stream().filter(r -> teamVisible(r, scope)).toList();
    }

    /**
     * Ekip kapsamında kaynak/aktör geçmişi: kapsam SORGUDA uygulanır (regresyon R7). Önce ilk N satırı çekip
     * sonra süzmek, son N olayı yabancı aktörlerinse zaman çizelgesini boş/eksik döndürüyordu.
     */
    private List<AuditLog> scopedHistory(TeamActorScope scope, Long actorId, String resourceType, String resourceId, int limit) {
        return auditLogRepo.findAdvanced(null, actorId, false, typesOrDummy(null), resourceType, resourceId,
                null, null, null, null, false, null,
                false, scope.teamIds(), scope.actorIds(), scope.actorNames(),
                PageRequest.of(0, Math.max(1, Math.min(limit, 500)))).getContent();
    }

    /**
     * Zayıf-algoritma YAZMA uçlarının takım kapısı.
     *
     * <p>{@code weak_algo.manage} izni {@code PermissionCatalog}'ta TEAM_ADMIN varsayılanında
     * AÇIK ve bu bilinçli: takım yöneticisi KENDİ alanının bulgusuna istisna yazabilmeli. Ama
     * uçlar {@code {domain}}'i hiçbir kapıdan geçirmiyordu — kapsamlı bir müdür, BAŞKA takımın
     * zayıf kripto bulgusunu güvenlik raporundan istediği tarihe kadar sildirebiliyor, başkasının
     * istisnasını kaldırabiliyor ve ilgisiz bir takıma CRITICAL mail+push tetikleyebiliyordu
     * (kapsamlı müdür sınıfı: rol ADMIN ama yetki takım-kapsamlı).
     *
     * <p>Kardeş emsali {@code MonitoringController.denyIfDomainNotViewable}; burada YAZMA
     * söz konusu olduğu için {@code canManage} kullanılır. Global admin/AUDIT (sistem-geneli
     * denetçi) her alandan geçer — {@code canManage} onlar için zaten true döner.
     */
    private ResponseEntity<Map<String, Object>> denyIfDomainNotManageable(HttpSession session, String domain) {
        // Sistem-geneli denetçi (global admin / AUDIT) envanterden BAĞIMSIZ geçer: kapı yalnız
        // kapsamlı kullanıcılar için var ve envanterde olmayan bir alana istisna yazma davranışı
        // onlar için aynen korunur (kapı eklemek mevcut yeteneği daraltmasın).
        if (SessionScope.isGlobalViewer(session)) return null;
        CertificateInventory inv = domain == null ? null : inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null)
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Alan envanterde yok"));
        if (SessionScope.canManage(session, inv.getTeamId())) return null;
        if (inv.getUgTeamId() != null && SessionScope.canManage(session, inv.getUgTeamId())) return null;
        return ResponseEntity.status(403).body(Map.of("success", false, "error",
                "Bu alan adı sizin takımınıza ait değil"));
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
