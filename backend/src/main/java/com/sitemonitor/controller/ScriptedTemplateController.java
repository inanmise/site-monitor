package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.model.ScriptedTemplateVersion;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import com.sitemonitor.repository.ScriptedTemplateVersionRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.ScriptedTemplateRules;
import com.sitemonitor.service.VersionLabels;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Takım-kapsamlı k6 şablon kütüphanesi.
 *
 * <p><b>Neden {@code MonitoringController}'a eklenmedi:</b> o sınıf ~3700 satır ve
 * {@code MonitoringControllerTest} ~40 {@code @MockitoBean} taşıyor; buraya eklenen her uç o
 * dilimin kurulum maliyetini büyütürdü. Ayrı {@code @WebMvcTest} dilimi altı bean ile açılıyor.
 *
 * <h2>İki yetki ilkesi</h2>
 * <ul>
 *   <li><b>Okuma:</b> Genel şablon ({@code teamId == null}) HERKESE açıktır ve
 *       {@code SessionScope.canView}'a HİÇ girmez — o metot {@code teamId == null} için
 *       {@code false} döner, yani Genel şablonu hiç kimse göremezdi.</li>
 *   <li><b>Yazma:</b> {@code canManage} YETMEZ — USER'ın manage kapsamı boştur (K3: takımın her
 *       ÜYESİ kendi takımının şablonunu düzenler). Bu yüzden {@code SessionScope.isMemberOf}.</li>
 * </ul>
 *
 * <h2>Durum kodu değişmezi</h2>
 * Okunamayan şablon her fiil için <b>404</b> (id'ler küçük ve tahmin edilebilir; gereksinim
 * "başka takımın şablonu listede görünmesin"). Okunabilir ama yazılamazsa <b>403</b>. Monitör
 * ailesi her ikisinde de 403 döner; buradaki sapma bilinçlidir.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class ScriptedTemplateController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Denetim diff'ine giren alanlar. {@code script} bilinçli olarak DIŞARIDA: tam gövde her
     *  düzenlemede audit satırına kopyalanırsa tablo şişer — script geçmişi sürüm tablosunda. */
    private static final String[] TEMPLATE_FIELDS = {
            "name", "nameEn", "description", "descriptionEn", "whenToUse", "whenToUseEn",
            "envJson", "tags", "teamId", "active", "currentVersion", "builtinKey"
    };

    private final ScriptedTemplateRepository templateRepo;
    private final ScriptedTemplateVersionRepository versionRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertificateService certificateService;
    private final ScriptedCheckerService scriptedChecker;

    // ── Yanıt yardımcıları (MonitoringController ile aynı zarf) ──────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> forbidden(String msg) {
        return ResponseEntity.status(403).body(Map.of("success", false, "error", msg));
    }

    private static final String NOT_FOUND_MSG = "Şablon bulunamadı";

    // ── Yetki ────────────────────────────────────────────────────────────────────────────────

    /** Genel = herkes; takım şablonu = görüş kapsamı; silinmiş = yalnız global admin (çöp kutusu). */
    static boolean canReadTemplate(HttpSession session, ScriptedTemplate t) {
        if (t == null) return false;
        if (!Boolean.TRUE.equals(t.getActive())) return SessionScope.isGlobalAdmin(session);
        if (t.getTeamId() == null) return true;                       // Genel — canView'a GİRMEZ
        return SessionScope.canView(session, t.getTeamId());
    }

    /** Genel şablonu yalnız global admin yazar (K3); takım şablonunu üyeleri + yönetenleri. */
    static boolean canWriteTemplate(HttpSession session, ScriptedTemplate t) {
        if (t == null) return false;
        if (t.getTeamId() == null) return SessionScope.isGlobalAdmin(session);
        if (SessionScope.isGlobalAdmin(session)) return true;
        return SessionScope.canManage(session, t.getTeamId())
                || SessionScope.isMemberOf(session, t.getTeamId());
    }

    /** Yeni şablon bu takıma yazılabilir mi? (POST'un kapsam çözümü) */
    private static boolean canWriteTeam(HttpSession session, Long teamId) {
        if (teamId == null) return SessionScope.isGlobalAdmin(session);
        return SessionScope.isGlobalAdmin(session)
                || SessionScope.canManage(session, teamId)
                || SessionScope.isMemberOf(session, teamId);
    }

    /**
     * Kullanıcının yazabildiği takımlar — UI kapsam seçicisini bundan doldurur, tahmin etmez.
     *
     * <p><b>{@link #canWriteTeam} ile AYNI cevabı vermek zorundadır.</b> Global admin oradaki ilk
     * satırda her takıma yazabiliyor; burada ise yalnız ÜYE/yönetici olduğu takımlar sayılıyordu.
     * Sonuç: admin'e kapsam seçicisinde "Herkes görür" dışında hiçbir takım görünmüyor, oysa aynı
     * isteği elle göndermek kabul edilirdi — yani yetki değil, yalnız listeleme eksikti. Üyelik
     * önce ekleniyor: {@link #primaryWritableTeam} ilk sırayı varsayılan sayar, admin'in kendi
     * takımı rastgele bir takımın arkasına düşmemeli.
     */
    private List<Long> writableTeamIds(HttpSession session) {
        LinkedHashSet<Long> out = new LinkedHashSet<>(SessionScope.memberTeamIds(session));
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage != null) out.addAll(manage);
        if (SessionScope.isGlobalAdmin(session)) out.addAll(certificateService.teamNamesById().keySet());
        return new ArrayList<>(out);
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String actorName(HttpSession session) {
        Object n = session != null ? session.getAttribute("fullName") : null;
        return n != null && !n.toString().isBlank() ? n.toString() : actor(session);
    }

    // ── Listeleme ────────────────────────────────────────────────────────────────────────────

    /**
     * @param scope {@code all} (varsayılan) · {@code general} · {@code team} · {@code trash} (admin)
     */
    @GetMapping("/scripted/templates")
    public ResponseEntity<Map<String, Object>> listTemplates(
            @RequestParam(required = false, defaultValue = "all") String scope, HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "view");
        String s = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);

        List<ScriptedTemplate> rows;
        if ("trash".equals(s)) {
            if (!SessionScope.isGlobalAdmin(session)) return forbidden("Çöp kutusu yalnız yöneticilere açıktır");
            rows = templateRepo.findTrash();
        } else if ("general".equals(s)) {
            rows = templateRepo.findActiveGeneral();
        } else if ("team".equals(s)) {
            rows = readableTeamTemplates(session);
        } else {
            rows = new ArrayList<>(templateRepo.findActiveGeneral());
            rows.addAll(readableTeamTemplates(session));
        }

        Map<Long, String> teams = certificateService.teamNamesById();
        List<Map<String, Object>> items = rows.stream().map(t -> row(t, session, teams, false)).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", items);
        out.put("can_create_general", SessionScope.isGlobalAdmin(session));
        out.put("can_view_trash", SessionScope.isGlobalAdmin(session));
        out.put("writable_team_ids", writableTeamIds(session));
        out.put("k6_version", scriptedChecker.version());
        return ok(out);
    }

    /**
     * Görülebilir TAKIM şablonları. Global görücü (admin/AUDIT) hepsini alır.
     *
     * <p>Tuzak: {@code findActiveByTeams}'i boş koleksiyonla çağırmak JPQL {@code in ()} üretir —
     * sağlayıcıya bağlı sözdizimi hatası. Koru burada.
     */
    private List<ScriptedTemplate> readableTeamTemplates(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) {
            return templateRepo.findAllActive().stream().filter(t -> t.getTeamId() != null).toList();
        }
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view == null || view.isEmpty()) return List.of();
        return templateRepo.findActiveByTeams(view);
    }

    @GetMapping("/scripted/templates/{id}")
    public ResponseEntity<Map<String, Object>> getTemplate(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "view");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (!canReadTemplate(session, t)) return notFound(NOT_FOUND_MSG);
        return ok(row(t, session, certificateService.teamNamesById(), true));
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────────────────────

    @PostMapping("/scripted/templates")
    public ResponseEntity<Map<String, Object>> createTemplate(@RequestBody Map<String, Object> body,
                                                              HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");

        // Kapsam çözümü — "Genel" istemek AÇIK bir taleptir ve sessizce takıma düşürülmez:
        // kullanıcı herkese açık sandığı bir şablonu takımına yazmış olmasın.
        boolean askedGeneral = body.containsKey("teamId") && body.get("teamId") == null;
        Long teamId;
        if (askedGeneral) {
            if (!SessionScope.isGlobalAdmin(session))
                return forbidden("Genel şablon oluşturma yetkiniz yok; bir takım seçin.");
            teamId = null;
        } else {
            Long requested = body.get("teamId") instanceof Number n ? n.longValue() : null;
            if (requested != null && !canWriteTeam(session, requested))
                return forbidden("Bu takıma şablon ekleyemezsiniz.");
            teamId = requested != null ? requested : primaryWritableTeam(session);
            if (teamId == null) return badRequest("Takım seçimi zorunludur; şablon oluşturulamıyor.");
        }

        List<Map<String, Object>> envDefs = envDefsOf(body.get("env"));
        var diag = ScriptedTemplateRules.check(null, str(body.get("name")), str(body.get("description")),
                str(body.get("whenToUse")), str(body.get("script")), envDefs, scriptedChecker.version());
        if (diag.blocked()) return badRequest(diag.blocking());

        String scanErr = secretScanError(str(body.get("script")));
        if (scanErr != null) return badRequest(scanErr);

        String name = str(body.get("name")).trim();
        if (templateRepo.countDuplicateInScope(name, teamId, null) > 0)
            return badRequest("Bu ad bu kapsamda zaten kullanılıyor.");

        String now = ISO.format(Instant.now());
        ScriptedTemplate t = new ScriptedTemplate();
        t.setTeamId(teamId);
        t.setActive(true);
        applyFields(t, body, envDefs);
        t.setCreatedAt(now);
        t.setCreatedBy(actor(session));
        t.setCreatedByName(actorName(session));
        t.setUpdatedAt(now);
        t.setUpdatedBy(actor(session));
        t.setUpdatedByName(actorName(session));
        ScriptedTemplate saved = templateRepo.save(t);
        saved.setCurrentVersion(writeVersion(saved, "CREATE", null, str(body.get("versionNote")), session));
        saved = templateRepo.save(saved);

        auditService.recordAction("TEMPLATE_CREATE", session, "SCRIPTED_TEMPLATE", String.valueOf(saved.getId()),
                saved.getName(), AuditDiff.diff(null, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));

        Map<String, Object> out = new LinkedHashMap<>(row(saved, session, certificateService.teamNamesById(), true));
        if (!diag.warnings().isEmpty()) out.put("warnings", diag.warnings());
        return ok(out);
    }

    /** Kapsam belirtilmemişse yazılabilir İLK takım — üyelik önce (K3), sonra yönetim. */
    private Long primaryWritableTeam(HttpSession session) {
        List<Long> writable = writableTeamIds(session);
        if (!writable.isEmpty()) return writable.get(0);
        Object primary = session != null ? session.getAttribute("teamId") : null;
        return primary instanceof Number n ? n.longValue() : null;
    }

    // ── Güncelleme ───────────────────────────────────────────────────────────────────────────

    /**
     * İçerik/metin güncellemesi.
     *
     * <p><b>{@code teamId} bilinçli olarak YOK SAYILIR.</b> Kapsam değişimi yalnız
     * {@code /promote} ve {@code /demote} ile olur. Aksi hâlde herhangi bir USER
     * {@code teamId: null} göndererek kendi şablonunu Genel'e — yani yalnız admin'in
     * yazabildiği katmana — taşıyabilirdi; sessiz bir yetki yükseltmesi.
     */
    @PutMapping("/scripted/templates/{id}")
    public ResponseEntity<Map<String, Object>> updateTemplate(@PathVariable Long id,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (!canReadTemplate(session, t)) return notFound(NOT_FOUND_MSG);
        if (!canWriteTemplate(session, t)) return forbidden("Bu şablonu düzenleme yetkiniz yok.");

        Map<String, Object> before = AuditDiff.snapshot(t, TEMPLATE_FIELDS);
        List<Map<String, Object>> envDefs = body.containsKey("env") ? envDefsOf(body.get("env")) : envDefsOf(t.getEnvJson());
        String script = body.containsKey("script") ? str(body.get("script")) : t.getScript();
        String name   = body.containsKey("name")   ? str(body.get("name"))   : t.getName();

        var diag = ScriptedTemplateRules.check(t.getBuiltinKey(), name,
                body.containsKey("description") ? str(body.get("description")) : t.getDescription(),
                body.containsKey("whenToUse") ? str(body.get("whenToUse")) : t.getWhenToUse(),
                script, envDefs, scriptedChecker.version());
        if (diag.blocked()) return badRequest(diag.blocking());
        String scanErr = secretScanError(script);
        if (scanErr != null) return badRequest(scanErr);

        if (name != null && !name.trim().equals(t.getName())
                && templateRepo.countDuplicateInScope(name.trim(), t.getTeamId(), t.getId()) > 0)
            return badRequest("Bu ad bu kapsamda zaten kullanılıyor.");

        String oldScript = t.getScript();
        String oldEnv = t.getEnvJson();
        applyFields(t, body, envDefs);
        t.setUpdatedAt(ISO.format(Instant.now()));
        t.setUpdatedBy(actor(session));
        t.setUpdatedByName(actorName(session));
        ScriptedTemplate saved = templateRepo.save(t);

        // Sürüm YALNIZ içerik (script/env) değişince yazılır — ad/açıklama düzenlemesi geçmişi
        // gereksiz satırlarla şişirmez; o değişiklikler denetim kaydında zaten görünür.
        if (!Objects.equals(oldScript, saved.getScript()) || !Objects.equals(oldEnv, saved.getEnvJson())) {
            String restoredFrom = str(body.get("restoredFrom"));
            boolean restore = restoredFrom != null && !restoredFrom.isBlank();
            String note = restore ? restoredFrom + " sürümünden geri yüklendi" : str(body.get("versionNote"));
            saved.setCurrentVersion(writeVersion(saved, restore ? "RESTORE" : "EDIT",
                    str(body.get("bumpType")), note, session));
            saved = templateRepo.save(saved);
        }

        auditService.recordAction("TEMPLATE_UPDATE", session, "SCRIPTED_TEMPLATE", String.valueOf(saved.getId()),
                saved.getName(), AuditDiff.diff(before, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));

        Map<String, Object> out = new LinkedHashMap<>(row(saved, session, certificateService.teamNamesById(), true));
        if (!diag.warnings().isEmpty()) out.put("warnings", diag.warnings());
        return ok(out);
    }

    // ── Silme / geri alma ────────────────────────────────────────────────────────────────────

    @DeleteMapping("/scripted/templates/{id}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean permanent,
            HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (!canReadTemplate(session, t)) return notFound(NOT_FOUND_MSG);

        if (permanent) {
            if (!SessionScope.isGlobalAdmin(session)) return forbidden("Kalıcı silme yalnız yöneticilere açıktır.");
            // Yerleşiği kalıcı silmek YASAK: seeder onu bir sonraki açılışta diriltir ve admin
            // "silme çalışmadı" diye destek talebi açar. Yumuşak silme (gizleme) kalıcı davranır.
            if (t.getBuiltinKey() != null)
                return badRequest("Yerleşik şablon kalıcı silinemez; uygulama açılışında yeniden oluşturulur. "
                        + "Gizlemek için normal silmeyi kullanın.");
            versionRepo.deleteAll(versionRepo.findByTemplateIdOrderBySequenceNoDesc(id));
            templateRepo.delete(t);
            auditService.recordAction("TEMPLATE_DELETE", session, "SCRIPTED_TEMPLATE", String.valueOf(id),
                    t.getName(), AuditDiff.diff(AuditDiff.snapshot(t, TEMPLATE_FIELDS), null));
            return ok(Map.of("deleted", true, "permanent", true));
        }

        if (!canWriteTemplate(session, t)) return forbidden("Bu şablonu silme yetkiniz yok.");
        if (!Boolean.TRUE.equals(t.getActive())) return ok(Map.of("deleted", true, "permanent", false));
        Map<String, Object> before = AuditDiff.snapshot(t, TEMPLATE_FIELDS);
        t.setActive(false);
        t.setDeletedAt(ISO.format(Instant.now()));
        t.setDeletedBy(actor(session));
        ScriptedTemplate saved = templateRepo.save(t);
        writeVersion(saved, "DELETE", null, null, session);
        auditService.recordAction("TEMPLATE_DELETE", session, "SCRIPTED_TEMPLATE", String.valueOf(id),
                saved.getName(), AuditDiff.diff(before, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));
        return ok(Map.of("deleted", true, "permanent", false));
    }

    /**
     * Çöp kutusundan geri alma — yalnız admin (K4).
     *
     * <p>Uç adı {@code /undelete}, {@code /restore} DEĞİL: bu ailede "restore" zaten SÜRÜM geri
     * yüklemeyi ({@code PUT + restoredFrom}) anlatıyor. Aynı sözcüğü iki farklı fiil için
     * kullanmak destek talebi üretir.
     */
    @PostMapping("/scripted/templates/{id}/undelete")
    public ResponseEntity<Map<String, Object>> undeleteTemplate(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");
        if (!SessionScope.isGlobalAdmin(session)) return forbidden("Çöp kutusu yalnız yöneticilere açıktır.");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (t == null) return notFound(NOT_FOUND_MSG);
        Map<String, Object> before = AuditDiff.snapshot(t, TEMPLATE_FIELDS);
        t.setActive(true);
        t.setDeletedAt(null);
        t.setDeletedBy(null);
        t.setUpdatedAt(ISO.format(Instant.now()));
        ScriptedTemplate saved = templateRepo.save(t);
        writeVersion(saved, "UNDELETE", null, null, session);
        auditService.recordAction("TEMPLATE_UPDATE", session, "SCRIPTED_TEMPLATE", String.valueOf(id),
                saved.getName(), AuditDiff.diff(before, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));
        return ok(row(saved, session, certificateService.teamNamesById(), false));
    }

    // ── Genele açma / geri çekme (K5) ────────────────────────────────────────────────────────

    /** Takım şablonunu Genel'e TAŞIR ({@code teamId → null}) ve köken rozetini yazar. */
    @PostMapping("/scripted/templates/{id}/promote")
    public ResponseEntity<Map<String, Object>> promoteTemplate(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");
        if (!SessionScope.isGlobalAdmin(session)) return forbidden("Genele açma yalnız yöneticilere açıktır.");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (t == null || !Boolean.TRUE.equals(t.getActive())) return notFound(NOT_FOUND_MSG);
        if (t.getTeamId() == null) return badRequest("Şablon zaten genel.");
        if (templateRepo.countDuplicateInScope(t.getName(), null, t.getId()) > 0)
            return badRequest("Genel kapsamda bu adda bir şablon zaten var; önce adı değiştirin.");

        Map<String, Object> before = AuditDiff.snapshot(t, TEMPLATE_FIELDS);
        String teamName = certificateService.teamNamesById().get(t.getTeamId());
        t.setSourceTeamName(teamName != null ? teamName : ("#" + t.getTeamId()));
        t.setTeamId(null);
        t.setPromotedAt(ISO.format(Instant.now()));
        t.setPromotedBy(actor(session));
        t.setUpdatedAt(ISO.format(Instant.now()));
        ScriptedTemplate saved = templateRepo.save(t);
        writeVersion(saved, "PROMOTE", null, saved.getSourceTeamName() + " takımından genele açıldı", session);
        auditService.recordAction("TEMPLATE_PROMOTE", session, "SCRIPTED_TEMPLATE", String.valueOf(id),
                saved.getName(), AuditDiff.diff(before, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));
        return ok(row(saved, session, certificateService.teamNamesById(), false));
    }

    /** Genel şablonu bir takıma geri çeker (K5'in geri alma yolu). */
    @PostMapping("/scripted/templates/{id}/demote")
    public ResponseEntity<Map<String, Object>> demoteTemplate(@PathVariable Long id,
                                                              @RequestBody(required = false) Map<String, Object> body,
                                                              HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "edit");
        if (!SessionScope.isGlobalAdmin(session)) return forbidden("Genelden çıkarma yalnız yöneticilere açıktır.");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (t == null || !Boolean.TRUE.equals(t.getActive())) return notFound(NOT_FOUND_MSG);
        if (t.getTeamId() != null) return badRequest("Şablon zaten bir takıma ait.");

        Long target = body != null && body.get("teamId") instanceof Number n ? n.longValue() : null;
        if (target == null) return badRequest("Hedef takım zorunludur.");
        if (templateRepo.countDuplicateInScope(t.getName(), target, t.getId()) > 0)
            return badRequest("Hedef takımda bu adda bir şablon zaten var; önce adı değiştirin.");

        Map<String, Object> before = AuditDiff.snapshot(t, TEMPLATE_FIELDS);
        t.setTeamId(target);
        t.setPromotedAt(null);
        t.setPromotedBy(null);
        // sourceTeamName KORUNUR: şablonun nereden geldiği tarihsel bir olgu, mevcut kapsamı değil.
        t.setUpdatedAt(ISO.format(Instant.now()));
        ScriptedTemplate saved = templateRepo.save(t);
        writeVersion(saved, "DEMOTE", null, "Genelden takım kapsamına çekildi", session);
        auditService.recordAction("TEMPLATE_DEMOTE", session, "SCRIPTED_TEMPLATE", String.valueOf(id),
                saved.getName(), AuditDiff.diff(before, AuditDiff.snapshot(saved, TEMPLATE_FIELDS)));
        return ok(row(saved, session, certificateService.teamNamesById(), false));
    }

    // ── Sürüm geçmişi ────────────────────────────────────────────────────────────────────────

    /** Sürüm listesi — script GÖVDESİ hariç (yüzlerce sürümde yanıt şişmesin). */
    @GetMapping("/scripted/templates/{id}/versions")
    public ResponseEntity<Map<String, Object>> templateVersions(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "view");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (!canReadTemplate(session, t)) return notFound(NOT_FOUND_MSG);
        List<Map<String, Object>> rows = versionRepo.findByTemplateIdOrderBySequenceNoDesc(id).stream()
                .map(v -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", v.getId());
                    r.put("version", v.getVersion());
                    r.put("sequence_no", v.getSequenceNo());
                    r.put("event_type", v.getEventType());
                    r.put("team_id", v.getTeamId());
                    r.put("note", v.getNote());
                    r.put("created_at", v.getCreatedAt());
                    r.put("created_by", v.getCreatedBy());
                    r.put("created_by_name", v.getCreatedByName());
                    r.put("script_chars", v.getScript() == null ? 0 : v.getScript().length());
                    r.put("current", v.getVersion() != null && v.getVersion().equals(t.getCurrentVersion()));
                    return r;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", rows);
        out.put("current_version", t.getCurrentVersion());
        return ok(out);
    }

    /** Tek sürümün gövdesi — önizleme ve "editöre yükle". */
    @GetMapping("/scripted/templates/{id}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> templateVersionDetail(@PathVariable Long id,
                                                                     @PathVariable Long versionId,
                                                                     HttpSession session) {
        permissionService.require(session, "monitoring.scripted_templates", "view");
        ScriptedTemplate t = templateRepo.findById(id).orElse(null);
        if (!canReadTemplate(session, t)) return notFound(NOT_FOUND_MSG);
        ScriptedTemplateVersion v = versionRepo.findById(versionId).orElse(null);
        // Başka şablonun sürüm id'siyle içerik çekilememeli — yetki sınırı ŞABLON üzerinden kuruluyor.
        if (v == null || !id.equals(v.getTemplateId())) return notFound("Sürüm bulunamadı");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", v.getId());
        out.put("version", v.getVersion());
        out.put("sequence_no", v.getSequenceNo());
        out.put("event_type", v.getEventType());
        out.put("note", v.getNote());
        out.put("created_at", v.getCreatedAt());
        out.put("created_by", v.getCreatedBy());
        out.put("created_by_name", v.getCreatedByName());
        out.put("script", v.getScript());
        out.put("env", envDefsOf(v.getEnvJson()));
        return ok(out);
    }

    // ── İç yardımcılar ───────────────────────────────────────────────────────────────────────

    /**
     * Sürüm satırı yazar ve yeni etiketi döner.
     *
     * <p>{@code writeScriptVersion} ikizi — <b>istisnayı yutan try/catch dahil</b>: sürüm geçmişi
     * yardımcı bir kayıttır, yazılamadı diye kullanıcının şablonu düşmez.
     */
    private String writeVersion(ScriptedTemplate t, String eventType, String bumpType,
                                String note, HttpSession session) {
        try {
            var last = versionRepo.findTopByTemplateIdOrderBySequenceNoDesc(t.getId());
            String version = last.map(v -> VersionLabels.nextVersion(v.getVersion(), bumpType))
                    .orElse(VersionLabels.FIRST_VERSION);
            ScriptedTemplateVersion row = new ScriptedTemplateVersion();
            row.setTemplateId(t.getId());
            row.setSequenceNo(last.map(v -> v.getSequenceNo() + 1).orElse(0));
            row.setVersion(version);
            row.setEventType(eventType);
            row.setScript(t.getScript());
            row.setEnvJson(t.getEnvJson());
            row.setTeamId(t.getTeamId());
            row.setNote(note == null || note.isBlank() ? null : note.trim());
            row.setCreatedAt(ISO.format(Instant.now()));
            row.setCreatedBy(actor(session));
            row.setCreatedByName(actorName(session));
            versionRepo.save(row);
            return version;
        } catch (Exception e) {
            log.warn("Şablon sürümü yazılamadı (template={}): {}", t.getId(), e.toString());
            return t.getCurrentVersion();
        }
    }

    /** Yazılabilir metin/içerik alanlarını gövdeden varlığa uygular. {@code teamId} DAHİL DEĞİL. */
    private void applyFields(ScriptedTemplate t, Map<String, Object> body, List<Map<String, Object>> envDefs) {
        if (body.containsKey("name"))          t.setName(trimOrNull(body.get("name")));
        if (body.containsKey("nameEn"))        t.setNameEn(trimOrNull(body.get("nameEn")));
        if (body.containsKey("description"))   t.setDescription(trimOrNull(body.get("description")));
        if (body.containsKey("descriptionEn")) t.setDescriptionEn(trimOrNull(body.get("descriptionEn")));
        if (body.containsKey("whenToUse"))     t.setWhenToUse(trimOrNull(body.get("whenToUse")));
        if (body.containsKey("whenToUseEn"))   t.setWhenToUseEn(trimOrNull(body.get("whenToUseEn")));
        if (body.containsKey("script"))        t.setScript(str(body.get("script")));
        if (body.containsKey("tags"))          t.setTags(tagsCsv(body.get("tags")));
        if (body.containsKey("env"))           t.setEnvJson(writeJson(envDefs));
    }

    /** Yanıt satırı. {@code includeBody} yalnız tekil uçlarda true — liste yanıtı script taşımaz. */
    private Map<String, Object> row(ScriptedTemplate t, HttpSession session,
                                    Map<Long, String> teams, boolean includeBody) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", t.getId());
        r.put("name", t.getName());
        r.put("name_en", t.getNameEn());
        r.put("description", t.getDescription());
        r.put("description_en", t.getDescriptionEn());
        r.put("when_to_use", t.getWhenToUse());
        r.put("when_to_use_en", t.getWhenToUseEn());
        r.put("tags", tagList(t.getTags()));
        r.put("team_id", t.getTeamId());
        r.put("team_name", t.getTeamId() == null ? null : teams.get(t.getTeamId()));
        r.put("scope", t.getTeamId() == null ? "general" : "team");
        r.put("builtin", t.getBuiltinKey() != null);
        r.put("builtin_key", t.getBuiltinKey());
        // Seçicinin ürettiği `tpl:<token>` — seed'lenen satır eski monitörlerle AYNI dizeyi üretir.
        r.put("select_token", t.getBuiltinKey() != null ? t.getBuiltinKey() : String.valueOf(t.getId()));
        r.put("source_team_name", t.getSourceTeamName());
        r.put("promoted_at", t.getPromotedAt());
        r.put("promoted_by", t.getPromotedBy());
        r.put("current_version", t.getCurrentVersion());
        r.put("active", t.getActive());
        r.put("deleted_at", t.getDeletedAt());
        r.put("deleted_by", t.getDeletedBy());
        r.put("created_at", t.getCreatedAt());
        r.put("created_by", t.getCreatedBy());
        r.put("created_by_name", t.getCreatedByName());
        r.put("updated_at", t.getUpdatedAt());
        r.put("updated_by", t.getUpdatedBy());
        r.put("updated_by_name", t.getUpdatedByName());
        // env DEFINITIONS taşınır (secret DEĞER yok — varlık zaten tutmuyor), liste yanıtında da:
        // seçici "bu şablon hangi değişkenleri ister" bilgisini ikinci istek atmadan gösterir.
        r.put("env", envDefsOf(t.getEnvJson()));
        r.put("script_chars", t.getScript() == null ? 0 : t.getScript().length());
        if (includeBody) r.put("script", t.getScript());

        // Yetenek bayrakları — UI ASLA tahmin etmez.
        boolean admin = SessionScope.isGlobalAdmin(session);
        boolean write = canWriteTemplate(session, t);
        r.put("can_edit", write);
        r.put("can_delete", write);
        r.put("can_promote", admin && t.getTeamId() != null && Boolean.TRUE.equals(t.getActive()));
        r.put("can_demote", admin && t.getTeamId() == null && Boolean.TRUE.equals(t.getActive()));
        r.put("can_permanent_delete", admin && t.getBuiltinKey() == null);
        return r;
    }

    /**
     * Gömülü gizli değer taraması — şablonlarda <b>KOŞULSUZ engel</b>.
     *
     * <p>Monitör tarafı bunu {@code site.monitor.scripted.hardcoded-secret-policy} ayarına bağlar
     * ve varsayılanı {@code WARN}'dır. Şablon bilinçli olarak daha katı: bir monitörün script'i
     * tek bir kayıtta yaşar, şablon ise <b>kopyalanmak için</b> vardır — sızan bir değer N
     * monitöre çoğalır ve kaynağı geriye izlenemez. Kaçış yolu ayar değil, doğru çözüm:
     * değeri {@code env} TANIMI yapıp script'te {@code __ENV} üzerinden okumak.
     */
    private static String secretScanError(String script) {
        if (script == null) return null;
        List<String> hits = ScriptedCheckerService.scanHardcodedSecrets(script);
        if (hits.isEmpty()) return null;
        return "Şablon gövdesinde sabit-kodlu gizli değer tespit edildi (" + String.join(", ", hits)
                + "). Şablonlar kopyalanmak için vardır; bu değeri ortam değişkeni TANIMI olarak "
                + "ekleyin ve script'te __ENV üzerinden okuyun.";
    }

    // ── Serileştirme ─────────────────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String trimOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Gövdeden env TANIM listesi. Girdi hem dizi hem JSON dize olabilir (sürüm satırından okurken). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> envDefsOf(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> l) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                return out;
            }
            String s = raw.toString();
            if (s.isBlank()) return List.of();
            return MAPPER.readValue(s, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            // Bozuk JSON şablonu görünmez yapmamalı — boş tanım listesiyle devam.
            log.debug("env tanımı ayrıştırılamadı: {}", e.toString());
            return List.of();
        }
    }

    private static String writeJson(List<Map<String, Object>> envDefs) {
        if (envDefs == null || envDefs.isEmpty()) return null;
        try {
            // `value` anahtarı ScriptedTemplateRules'ta zaten reddedildi; burada ikinci bir
            // savunma katmanı olarak da ayıklanır (doğrudan çağrılan yeni bir yol açılırsa diye).
            List<Map<String, Object>> clean = new ArrayList<>();
            for (Map<String, Object> e : envDefs) {
                Map<String, Object> c = new LinkedHashMap<>(e);
                c.remove("value");
                clean.add(c);
            }
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("env tanımı yazılamadı: {}", e.toString());
            return null;
        }
    }

    /** CSV ⇄ liste — etiketler varlıkta CSV, API'de dizi. */
    private static String tagsCsv(Object raw) {
        if (raw instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) if (o != null && !o.toString().isBlank()) parts.add(o.toString().trim());
            return parts.isEmpty() ? null : String.join(",", parts);
        }
        return trimOrNull(raw);
    }

    private static List<String> tagList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
}
