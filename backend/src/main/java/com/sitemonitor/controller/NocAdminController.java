package com.sitemonitor.controller;

import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocGroupService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocNotificationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 7/24 İzleme Ekibi (NOC) YÖNETİMİ — yapılandırma ve GLOBAL gruplar (2026-09-27; sözleşme
 * {@code .migration/noc/CONTRACT.md}).
 *
 * <p><b>Yazma YALNIZ global yönetici</b> ({@link SessionScope#isGlobalAdmin}). {@code "ADMIN".equals(rol)} ya da
 * matris izni YETMEZ: AD'den gelen müdür de ADMIN rolüyle gelir ama takım-kapsamlıdır (proje tuzağı — v20.50.29
 * kritik bulgusu). Kurumun 7/24 ekibinin adreslerini ve hangi alarmların oraya gideceğini takım-kapsamlı biri
 * değiştirememeli.
 *
 * <p><b>Okuma</b>: global yönetici tam; kapsamlı yönetici (müdür) ve denetçi (AUDIT) okur ama grup
 * E-POSTALARINI GÖRMEZ ({@code emails: []}, {@code email_count}, {@code emails_hidden: true}). Diğer roller 403.
 */
@RestController
@RequestMapping("/api/admin/noc")
@RequiredArgsConstructor
public class NocAdminController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Denetim diff'ine giren grup alanları — adres listesi DAHİL ("alarmı kim alıyor"). */
    private static final String[] GROUP_FIELDS = {"name", "description", "emails", "active", "isDefault"};

    private final NocConfigService configService;
    private final NocGroupService groupService;
    private final NocNotificationGroupRepository groupRepo;
    private final NocMonitorDirectory directory;
    private final NocNotificationService notifications;
    private final AuditService auditService;
    private final com.sitemonitor.service.noc.NocOperatorService nocOperators;   // 7/24 izleme ekibi takımları (2026-10-04)

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "7/24 grubu bulunamadı"));
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    /** Görünen ad — AuthController oturuma {@code displayName} yazar (ad soyad → kullanıcı adı yedeği). */
    static String actorName(HttpSession session) {
        Object n = session != null ? session.getAttribute("displayName") : null;
        return n != null && !n.toString().isBlank() ? n.toString() : actor(session);
    }

    // ── Yetki ────────────────────────────────────────────────────────────────

    /** Global admin + kapsamlı yönetici (müdür) + denetçi okur. */
    static boolean canRead(HttpSession session) {
        if (SessionScope.isGlobalAdmin(session) || SessionScope.isScopedAdmin(session)) return true;
        return SessionScope.isGlobalViewer(session);   // AUDIT (viewTeamIds null) — rolü de doğrular
    }

    private static void requireRead(HttpSession session) {
        if (!canRead(session)) throw new SecurityException("7/24 İzleme Ekibi ayarlarını görme yetkiniz yok");
    }

    private static void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session))
            throw new SecurityException("7/24 İzleme Ekibi ayarlarını yalnız global yönetici değiştirebilir");
    }

    // ── Yapılandırma ─────────────────────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(HttpSession session) {
        requireRead(session);
        return ok(NocConfigService.toDto(configService.get()));
    }

    /** Gövde (camelCase): {@code { enabledTypes, minLevel, sendResolve, callInstructions }} — gelmeyen alan korunur. */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> putConfig(@RequestBody Map<String, Object> body, HttpSession session) {
        requireGlobalAdmin(session);
        Map<String, Object> before = NocConfigService.toDto(configService.get());
        NocConfigService.Config saved = configService.save(body, actor(session), actorName(session));
        Map<String, Object> after = NocConfigService.toDto(saved);
        before.remove("updated_at"); before.remove("updated_by_name");
        Map<String, Object> afterForDiff = new LinkedHashMap<>(after);
        afterForDiff.remove("updated_at"); afterForDiff.remove("updated_by_name");
        auditService.recordAction("NOC_CONFIG_UPDATE", session, "NOC", "config",
                com.sitemonitor.service.AuditDetail.of("min_level", saved.minLevel(), "send_resolve", saved.sendResolve(),
                        "disabled_types", saved.disabledTypeKeys()),
                AuditDiff.diff(before, afterForDiff));
        return ok(after);
    }

    // ── 7/24 izleme ekibi takımları (2026-10-04) ─────────────────────────────

    /**
     * İşaretli takımlar + künye + etkin operatör sayısı. Okuma {@link #canRead} (global admin, kapsamlı müdür, AUDIT) —
     * takım adları hassas değil; yazma yalnız global yönetici.
     */
    @GetMapping("/operator-teams")
    public ResponseEntity<Map<String, Object>> getOperatorTeams(HttpSession session) {
        requireRead(session);
        return ok(nocOperators.settingsDto());
    }

    /**
     * Seçim önizlemesi (kaydetmeden): takım başına aktif üye sayısı + etkilenecek aktif kullanıcılar (ad + 7/24 takımı).
     * {@code teamIds} virgüllü. Pasif kullanıcı ve pasif takım sayılmaz. Ad/takım bilgisi kurum geneli takım rehberinde
     * zaten herkese açık — telefon/e-posta YOK.
     */
    @GetMapping("/operator-teams/preview")
    public ResponseEntity<Map<String, Object>> previewOperatorTeams(@RequestParam(name = "teamIds", required = false) String teamIds,
                                                                    HttpSession session) {
        requireRead(session);
        return ok(nocOperators.preview(com.sitemonitor.service.noc.NocOperatorService.parseIds(teamIds)));
    }

    /**
     * Gövde {@code { teamIds: [..] }} — seçimi BAŞTAN yazar (boş liste = hiçbir takım). Yalnız GLOBAL yönetici (kapsamlı
     * müdür 403). Denetim: {@code NOC_TEAMS_UPDATE} + önce/sonra takım kimlikleri ve adları. Bu podda operatör önbelleği
     * hemen düşer; öteki pod'lar en geç {@code site.monitor.noc.operator-cache-ms} sonra görür.
     */
    @PutMapping("/operator-teams")
    public ResponseEntity<Map<String, Object>> putOperatorTeams(@RequestBody Map<String, Object> body, HttpSession session) {
        requireGlobalAdmin(session);
        if (body == null || !body.containsKey("teamIds"))
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("teamIds zorunlu", "teamIds is required"));
        com.sitemonitor.service.noc.NocOperatorService.SaveResult r =
                nocOperators.save(body.get("teamIds"), actor(session), actorName(session));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("team_ids", r.before());
        before.put("team_names", r.before().stream().map(id -> r.names().getOrDefault(id, "#" + id)).toList());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("team_ids", r.after());
        after.put("team_names", r.after().stream().map(id -> r.names().getOrDefault(id, "#" + id)).toList());
        List<Long> added = r.after().stream().filter(id -> !r.before().contains(id)).toList();
        List<Long> removed = r.before().stream().filter(id -> !r.after().contains(id)).toList();
        auditService.recordAction("NOC_TEAMS_UPDATE", session, "NOC", "operator-teams",
                com.sitemonitor.service.AuditDetail.of("count", r.after().size(),
                        "added", added.stream().map(id -> r.names().getOrDefault(id, "#" + id)).toList(),
                        "removed", removed.stream().map(id -> r.names().getOrDefault(id, "#" + id)).toList()),
                AuditDiff.diff(before, after));
        return ok(nocOperators.settingsDto());
    }

    // ── Gruplar ──────────────────────────────────────────────────────────────

    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> listGroups(HttpSession session) {
        requireRead(session);
        boolean reveal = SessionScope.isGlobalAdmin(session);
        List<NocNotificationGroup> all = groupService.list();
        Map<Long, int[]> usage = NocGroupService.usage(directory.all(), all);
        List<Map<String, Object>> out = new ArrayList<>();
        for (NocNotificationGroup g : all) {
            int[] u = usage.getOrDefault(g.getId(), new int[2]);
            out.add(NocGroupService.toAdminDto(g, reveal, u[0], u[1]));
        }
        return ok(out);
    }

    /** Gövde (camelCase): {@code { name, description, emails: [..], active, isDefault }}. */
    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody Map<String, Object> body, HttpSession session) {
        requireGlobalAdmin(session);
        NocNotificationGroup saved = groupService.create(groupService.validate(body), actor(session), actorName(session));
        auditService.recordAction("NOC_GROUP_CREATE", session, "NOC_GROUP", String.valueOf(saved.getId()), saved.getName(),
                AuditDiff.snapshotJson(AuditDiff.snapshot(saved, GROUP_FIELDS)));
        return ok(NocGroupService.toAdminDto(saved, true, 0, 0));
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> updateGroup(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                           HttpSession session) {
        requireGlobalAdmin(session);
        NocNotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null) return notFound();
        Map<String, Object> before = AuditDiff.snapshot(g, GROUP_FIELDS);
        NocNotificationGroup saved = groupService.update(g, groupService.validate(body), actor(session), actorName(session));
        auditService.recordAction("NOC_GROUP_UPDATE", session, "NOC_GROUP", String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, GROUP_FIELDS)));
        List<NocNotificationGroup> all = groupService.list();
        int[] u = NocGroupService.usage(directory.all(), all).getOrDefault(id, new int[2]);
        return ok(NocGroupService.toAdminDto(saved, true, u[0], u[1]));
    }

    /** Siler; bu grubu SEÇMİŞ izlemeler varsayılana düşer → {@code { affected_monitors }}. */
    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        NocNotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null) return notFound();
        // Denetim anlık görüntüsü silmeden ÖNCE — satır gittikten sonra kimin neyi sildiği okunamazdı.
        String snapshot = AuditDiff.snapshotJson(AuditDiff.snapshot(g, GROUP_FIELDS));
        String name = g.getName();
        int affected = groupService.deleteAndDetach(g);
        auditService.recordAction("NOC_GROUP_DELETE", session, "NOC_GROUP", String.valueOf(id),
                com.sitemonitor.service.AuditDetail.of("name", name, "affected_monitors", affected), snapshot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("affected_monitors", affected);
        return ok(out);
    }

    /** Grubun HER adresine ayrı test e-postası → {@code { sent, failed: [{email, error}] }}. */
    @PostMapping("/groups/{id}/test")
    public ResponseEntity<Map<String, Object>> testGroup(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        NocNotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null) return notFound();
        NocNotificationService.TestResult r = notifications.sendTest(g);
        auditService.recordAction("NOC_GROUP_TEST", session, "NOC_GROUP", String.valueOf(id),
                com.sitemonitor.service.AuditDetail.of("name", g.getName(), "sent", r.sent(), "failed", r.failed().size()), null);
        List<Map<String, Object>> failed = new ArrayList<>();
        for (NocNotificationService.Failure f : r.failed()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", f.email());
            m.put("error", f.error());
            failed.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", r.sent());
        out.put("failed", failed);
        return ok(out);
    }
}
