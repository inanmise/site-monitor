package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.service.techerror.TechErrorApprovalService;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorVisibilityService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/**
 * Kanal teknik hata raporları (2026-10-13, kullanıcı isteği). Bir takımın BİRDEN ÇOK kanalı olur; her kanalın kendi
 * şablonu vardır ve her ay önceki ayın raporu bu şablondan açılıp PDF olarak takımın e-posta adresine gönderilir.
 *
 * <p><b>Kapılar.</b> İzin matrisi: okuma {@code tech_error_reports.read/view}, yazma {@code tech_error_reports.crud/edit}.
 * Kapsam TAKIM belgesidir (izleme verisi değil — erişim paylaşımı ve 7/24 genişlemesi UYGULANMAZ):
 * <ul>
 *   <li>okuma: kurum geneli görüntüleyici (global yönetici / AUDIT), görüş kapsamı, yönetim kapsamı ya da ÜYELİK;</li>
 *   <li>yazma (kanal, şablon, rapor, görsel, gönderim): global yönetici, yönetim kapsamı ya da ÜYELİK;</li>
 *   <li>kanal silme ve gönderilmiş raporu silme: yönetim kapsamı (TEAM_ADMIN / kapsamlı müdür / global yönetici).</li>
 * </ul>
 * Görülemeyen kayıt kimliğiyle istenirse 404 döner (varlığı sızmaz). Satırlar {@code can_edit} / {@code can_delete}
 * taşır — arayüz yazma düğmesini yalnız sunucu kabul edecekse çizer.
 */
@RestController
@RequestMapping("/api/tech-error-reports")
public class TechErrorReportController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TechErrorReportController.class);

    static final String READ = "tech_error_reports.read";
    static final String CRUD = "tech_error_reports.crud";
    /** PO onayı / reddi (2026-10-17, S6): izin GEREKLİ ama yetmez — uç ayrıca takımın aktif PO üyesi olmayı ister. */
    static final String APPROVE = "tech_error_reports.approve";

    private final TechErrorReportService service;
    private final TechErrorReportSender sender;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;

    /** Değişiklik / sürüm geçmişi (2026-10-14). Yoksa (dilim testleri) geçmiş yazılmaz, okuma uçları boş döner. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.techerror.TechErrorHistoryService history;

    /** PO onayı (2026-10-17, S6). Yoksa (dilim testleri) onay uçları 409 {@code APPROVAL_UNAVAILABLE} döner. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    TechErrorApprovalService approvals;

    public TechErrorReportController(TechErrorReportService service, TechErrorReportSender sender, TeamRepository teams,
                                     PermissionService permissions, AuditService auditService) {
        this.service = service;
        this.sender = sender;
        this.teams = teams;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    // ── Genel bakış: takımlar + kanallar + varsayılan dönemin durumu ────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(value = "team", required = false) String team,
                                                        HttpSession session) {
        requireRead(session);
        boolean writer = permissions.allows(session, CRUD, "edit");
        Set<Long> visible = visibleTeamIds(session);          // null = hepsi
        Map<Long, Team> teamMap = new LinkedHashMap<>();
        for (Team t : visible == null ? teams.findAll() : teams.findAllById(visible)) teamMap.put(t.getId(), t);
        Long teamFilter = team == null || team.isBlank() ? null : parseId(team);
        List<TechErrorChannel> chs = service.channels(visible == null ? null : teamMap.keySet());
        List<TechErrorChannel> shown = new ArrayList<>();
        for (TechErrorChannel ch : chs) {
            if (!teamMap.containsKey(ch.getTeamId())) continue;      // takımı silinmiş (yetim temizliğini bekliyor)
            if (!canRead(session, ch.getTeamId())) continue;         // kapsam dışı / modülü kapalı takım (S6)
            if (teamFilter != null && !teamFilter.equals(ch.getTeamId())) continue;
            shown.add(ch);
        }
        YearMonth period = service.defaultPeriod();
        List<Long> ids = shown.stream().map(TechErrorChannel::getId).toList();
        service.reconcileQueued(ids);                        // kuyruktaki gönderimler sonuçlandıysa durum güncel
        Map<Long, TechErrorReportRepository.Row> periodRows = service.periodRows(ids, period);
        Map<Long, Long> counts = service.reportCounts(ids);

        List<Map<String, Object>> channelRows = new ArrayList<>();
        for (TechErrorChannel ch : shown) {
            Map<String, Object> m = channelDto(ch, teamMap.get(ch.getTeamId()), session, writer);
            m.put("report_count", counts.getOrDefault(ch.getId(), 0L));
            TechErrorReportRepository.Row pr = periodRows.get(ch.getId());
            m.put("period_report", pr == null ? null : maskSharedPeople(statusDto(pr, service.now()), session, ch.getTeamId()));
            channelRows.add(m);
        }
        List<Map<String, Object>> teamRows = new ArrayList<>();
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        List<Team> sortedTeams = new ArrayList<>(teamMap.values());
        sortedTeams.removeIf(t -> Boolean.FALSE.equals(t.getActive()) && shown.stream().noneMatch(c -> c.getTeamId().equals(t.getId())));
        sortedTeams.sort(Comparator.comparing(t -> t.getName() == null ? "" : t.getName(), col));
        for (Team t : sortedTeams) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.getId());
            tm.put("name", t.getName());
            tm.put("active", !Boolean.FALSE.equals(t.getActive()));
            tm.put("has_email", !TechErrorReportSender.recipients(t).isEmpty());
            tm.put("can_edit", writer && canWriteOwn(session, t.getId()));          // kanal açılabilen takımlar (paylaşım değil)
            tm.put("can_manage", writer && SessionScope.canManage(session, t.getId()));
            teamRows.add(tm);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", Map.of("year", period.getYear(), "month", period.getMonthValue()));
        data.put("teams", teamRows);
        data.put("channels", channelRows);
        data.put("can_create", writer && teamRows.stream().anyMatch(t -> Boolean.TRUE.equals(t.get("can_edit"))));
        return ok(data);
    }

    // ── Kanallar ─────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/channels")
    public ResponseEntity<Map<String, Object>> createChannel(@RequestBody Map<String, Object> body, HttpSession session,
                                                             HttpServletRequest request) {
        requireWrite(session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Long teamId = b.get("team_id") == null ? null : parseId(String.valueOf(b.get("team_id")));
        if (teamId == null) throw new FieldValidationException("team_id", Msg.t("Kanalın takımını seçin.", "Choose the channel's team."));
        Team team = teams.findById(teamId).orElse(null);
        if (team != null && Boolean.FALSE.equals(team.getActive())) {
            throw new FieldValidationException("team_id", Msg.t(
                    "Bu takım pasif; pasif takıma kanal açılamaz.", "This team is inactive; you can't open a channel for it."));
        }
        if (team == null || !canWriteOwn(session, teamId)) {
            throw new FieldValidationException("team_id", Msg.t(
                    "Bu takım için kanal açamazsınız: üyesi ya da yöneticisi değilsiniz.",
                    "You can't open a channel for this team: you're not a member or manager of it."));
        }
        Template template;
        Object copyFrom = b.get("copy_from");
        if (copyFrom != null && !String.valueOf(copyFrom).isBlank()) {
            Long srcId = parseId(String.valueOf(copyFrom));
            TechErrorChannel src = srcId == null ? null : readableChannel(srcId, session);
            if (src == null) throw new FieldValidationException("copy_from", Msg.t(
                    "Şablonu kopyalanacak kanal bulunamadı. Listeden yeniden seçin.",
                    "The channel to copy the template from wasn't found. Pick it again from the list."));
            template = TechErrorTemplate.fromStored(src.getTemplateJson());
        } else {
            String lang = b.get("lang") == null ? null : String.valueOf(b.get("lang"));
            template = TechErrorTemplate.defaults("en".equals(lang) ? "en" : "tr");
        }
        TechErrorChannel ch = service.createChannel(teamId, str(b.get("name")), str(b.get("description")), template, userId(session));
        auditService.recordAction("TECH_ERROR_CHANNEL_CREATE", session, request, "TECH_ERROR_CHANNEL", String.valueOf(ch.getId()),
                AuditDetail.of("team_id", teamId, "name", ch.getName(), "copy_from", copyFrom));
        return ok(channelDto(ch, team, session, true));
    }

    @PutMapping("/channels/{id}")
    public ResponseEntity<Map<String, Object>> updateChannel(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                             HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorChannel before = writableChannel(id, session);
        String oldName = before.getName();
        boolean oldActive = before.isActive();
        if (body != null && body.containsKey("executive_emails") && !SessionScope.canManage(session, before.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Yönetici (executive) alıcılarını yalnız takımı yönetenler değiştirebilir.",
                    "Only the team's managers can change the executive recipients."));
        }
        TechErrorChannel ch = service.updateChannel(id, body == null ? Map.of() : body, userId(session));
        auditService.recordAction("TECH_ERROR_CHANNEL_UPDATE", session, request, "TECH_ERROR_CHANNEL", String.valueOf(id),
                AuditDetail.of("name_before", oldName, "name", ch.getName(), "active_before", oldActive, "active", ch.isActive(),
                        "fields", body == null ? List.of() : new ArrayList<>(body.keySet())));
        return ok(channelDto(ch, teams.findById(ch.getTeamId()).orElse(null), session, true));
    }

    @DeleteMapping("/channels/{id}")
    public ResponseEntity<Map<String, Object>> deleteChannel(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        if (!SessionScope.canManage(session, ch.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Kanalı yalnız takım yöneticisi silebilir. Kanalı pasife alabilir ya da yöneticinizden silmesini isteyebilirsiniz.",
                    "Only a team manager can delete a channel. You can turn it off, or ask your manager to delete it."));
        }
        int removed = service.deleteChannel(id);
        auditService.recordAction("TECH_ERROR_CHANNEL_DELETE", session, request, "TECH_ERROR_CHANNEL", String.valueOf(id),
                AuditDetail.of("team_id", ch.getTeamId(), "name", ch.getName(), "reports_deleted", removed));
        return ok(Map.of("deleted", true, "reports_deleted", removed));
    }

    @GetMapping("/channels/{id}/template")
    public ResponseEntity<Map<String, Object>> getTemplate(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channelDto(ch, teams.findById(ch.getTeamId()).orElse(null), session,
                permissions.allows(session, CRUD, "edit")));
        data.put("template", TechErrorTemplate.toMap(TechErrorTemplate.fromStored(ch.getTemplateJson())));
        data.put("version", ch.getTemplateVersion());
        return ok(data);
    }

    @PutMapping("/channels/{id}/template")
    public ResponseEntity<Map<String, Object>> saveTemplate(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorChannel ch = writableChannel(id, session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Object v = b.get("version");
        if (!(v instanceof Number n)) throw new FieldValidationException("version", Msg.t(
                "Şablonun okunan sürümü gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                "The template version you read wasn't sent. Reload the page and try again."));
        Template t = TechErrorTemplate.normalize(b.get("template"));
        int next = service.saveTemplate(ch.getId(), n.intValue(), t, userId(session));
        auditService.recordAction("TECH_ERROR_TEMPLATE_SAVE", session, request, "TECH_ERROR_CHANNEL", String.valueOf(id),
                AuditDetail.of("name", ch.getName(), "version", next, "sections", t.sections().size(),
                        "fields", t.allFields().size(), "slots", t.allSlots().size()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template", TechErrorTemplate.toMap(t));
        data.put("version", next);
        return ok(data);
    }

    @GetMapping("/templates/default")
    public ResponseEntity<Map<String, Object>> defaultTemplate(@RequestParam(value = "lang", required = false) String lang,
                                                               HttpSession session) {
        requireRead(session);
        return ok(Map.of("template", TechErrorTemplate.toMap(TechErrorTemplate.defaults("en".equals(lang) ? "en" : "tr"))));
    }

    // ── Raporlar ─────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/channels/{id}/reports")
    public ResponseEntity<Map<String, Object>> listReports(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        boolean writer = permissions.allows(session, CRUD, "edit");
        Team team = teams.findById(ch.getTeamId()).orElse(null);
        Template current = TechErrorTemplate.fromStored(ch.getTemplateJson());
        service.reconcileQueued(List.of(ch.getId()));
        List<TechErrorReportRepository.Row> rows = service.reportRows(ch.getId());
        Map<Long, long[]> imgs = service.imageCounts(rows.stream().map(TechErrorReportRepository.Row::getId).toList());
        Set<Long> userIds = new HashSet<>();
        for (var r : rows) { userIds.add(r.getUpdatedById()); userIds.add(r.getLastSentById()); }
        Map<Long, String> names = service.displayNames(userIds);
        Instant now = service.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = maskSharedPeople(statusDto(r, now), session, r.getTeamId());
            // Raporun KENDİ başlığı (gönderilmiş raporun adı şablon sonradan değişse de PDF'teki gibi kalır)
            String pattern = r.getTitlePattern() != null ? r.getTitlePattern() : current.title();
            String lang = r.getTitlePattern() != null && r.getTemplateLang() != null ? r.getTemplateLang() : current.lang();
            m.put("title", TechErrorTemplate.resolve(pattern, ch.getName(), r.getReportYear(), r.getReportMonth(), lang));
            m.put("updated_by_name", nameOf(names, r.getUpdatedById()));
            m.put("last_sent_by_name", nameOf(names, r.getLastSentById()));
            long[] ic = imgs.getOrDefault(r.getId(), new long[]{ 0, 0 });
            m.put("image_count", ic[0]);
            m.put("can_delete", writer && canDeleteReport(session, ch.getTeamId(), r.getLastSentAt(), r.getSendState()));
            out.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channelDto(ch, team, session, writer));
        data.put("reports", out);
        YearMonth def = service.defaultPeriod();
        data.put("default_period", Map.of("year", def.getYear(), "month", def.getMonthValue()));
        return ok(data);
    }

    @PostMapping("/reports")
    public ResponseEntity<Map<String, Object>> createReport(@RequestBody Map<String, Object> body, HttpSession session,
                                                            HttpServletRequest request) {
        requireWrite(session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Long channelId = b.get("channel_id") == null ? null : parseId(String.valueOf(b.get("channel_id")));
        if (channelId == null) throw notFoundChannel();
        TechErrorChannel ch = contentWritableChannel(channelId, session);     // rapor açma = içerik yazma: EDIT paylaşımı da (9. sürüm)
        int year = intOf(b.get("year"), "year");
        int month = intOf(b.get("month"), "month");
        TechErrorReport r;
        try {
            r = service.createReport(ch, year, month, userId(session));
        } catch (CodedConflictException e) {
            // Eşzamanlı açılış: servisin işlemi geri alındı — kazanan raporu YENİ bir okumayla bul, istemci onu açsın
            if ("REPORT_EXISTS".equals(e.getCode()) && (e.getExtra() == null || !e.getExtra().containsKey("report_id"))) {
                Long existing = service.findReportId(ch.getId(), year, month);
                if (existing != null) throw new CodedConflictException("REPORT_EXISTS", e.getMessage(), Map.of("report_id", existing));
            }
            throw e;
        }
        auditService.recordAction("TECH_ERROR_REPORT_CREATE", session, request, "TECH_ERROR_REPORT", String.valueOf(r.getId()),
                AuditDetail.of("channel_id", ch.getId(), "channel", ch.getName(), "year", year, "month", month));
        return ok(Map.of("id", r.getId()));
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorReport r = readableReport(id, session);
        if (service.reconcileQueued(List.of(r.getChannelId())) > 0) r = service.report(r.getId());
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElse(null);
        boolean writer = permissions.allows(session, CRUD, "edit");
        boolean canEdit = writer && canWrite(session, r.getTeamId());
        TechErrorTemplate.Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        TechErrorValues.Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        Instant now = service.now();

        Map<String, Object> rep = maskSharedPeople(statusDto(rowOf(r), now), session, r.getTeamId());
        rep.put("version", r.getVersion());
        rep.put("title", TechErrorTemplate.resolve(t.title(), ch.getName(), r.getReportYear(), r.getReportMonth(), t.lang()));
        Map<Long, String> names = service.displayNames(Arrays.asList(r.getCreatedById(), r.getUpdatedById(), r.getLastSentById()));
        rep.put("created_by_name", nameOf(names, r.getCreatedById()));
        rep.put("updated_by_name", nameOf(names, r.getUpdatedById()));
        rep.put("last_sent_by_name", nameOf(names, r.getLastSentById()));
        rep.put("file_name", TechErrorReportPdfWriter.fileName(ch.getName(), r.getReportYear(), r.getReportMonth(), t.lang()));

        List<Map<String, Object>> imgs = new ArrayList<>();
        for (TechErrorReportImageRepository.Meta m : service.imageMeta(r.getId())) imgs.add(imageDto(m));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report", rep);
        data.put("channel", channelDto(ch, team, session, writer));
        data.put("template", TechErrorTemplate.toMap(t));
        data.put("values", TechErrorValues.toMap(v));
        data.put("images", imgs);
        data.put("previous", previousDto(r, t));
        List<TechErrorReportRepository.Row> channelRows = service.reportRows(r.getChannelId());
        data.put("neighbors", neighborsDto(r, channelRows));
        // Otomatik trend (2026-10-15, Faz C): yalnız şablonda trend bölümü varsa; okunamazsa rapor yine açılır, trend
        // "okunamadı" der (boş grafik ya da "en az iki ay" denmez)
        if (!com.sitemonitor.service.techerror.TechErrorTrend.referencedFields(t).isEmpty()) {
            try {
                data.put("trend", trendDto(service.trend(r, t, v, channelRows)));
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu #{} trend verisi okunamadı: {}", r.getId(), e.toString());
                data.put("trend", Map.of("unavailable", true));
            }
        }
        data.put("can_edit", canEdit);
        data.put("can_delete", writer && canDeleteReport(session, r.getTeamId(), r.getLastSentAt(), r.getSendState()));
        // Gönderim / onay isteği takımın kendi kapsamı — paylaşımla düzenleyen gönderemez (9. sürüm); satır paylaşımla mı okunuyor
        data.put("can_send", writer && canWriteOwn(session, r.getTeamId()));
        data.put("shared_access", SessionScope.viewableOnlyByGrant(session, r.getTeamId()));
        data.put("team_email_set", team != null && !TechErrorReportSender.recipients(team).isEmpty());
        data.put("approval_detail", approvalDetail(r, ch, team, session, writer));
        data.put("template_update", canEdit ? service.refreshPreview(r, ch) : null);
        data.put("limits", Map.of("image_max_bytes", com.sitemonitor.service.techerror.TechErrorImageValidator.MAX_BYTES,
                "report_image_max_bytes", TechErrorReportService.MAX_REPORT_IMAGE_BYTES));
        return ok(data);
    }

    @PutMapping("/reports/{id}")
    public ResponseEntity<Map<String, Object>> saveReport(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                          HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = writableReport(id, session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Long version = b.get("version") instanceof Number n ? Long.valueOf(n.longValue()) : null;
        // Otomatik kayıt (2026-10-16): istemci 30 sn'de bir; geçmişte birleşir, denetime pencere başına bir kez yazılır
        boolean auto = Boolean.TRUE.equals(b.get("auto"));
        TechErrorReportService.SaveOutcome outcome = service.saveValues(r.getId(), version, b.get("values"), userId(session), auto);
        TechErrorReport saved = outcome.report();
        boolean changed = !Objects.equals(saved.getVersion(), version);
        if (changed && !outcome.mergedHistory()) {
            auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    auto ? AuditDetail.of("channel_id", r.getChannelId(), "year", r.getReportYear(), "month", r.getReportMonth(),
                            "version", saved.getVersion(), "auto", true)
                         : AuditDetail.of("channel_id", r.getChannelId(), "year", r.getReportYear(), "month", r.getReportMonth(),
                            "version", saved.getVersion()));
        }
        TechErrorTemplate.Template t = TechErrorTemplate.fromStored(saved.getTemplateJson());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", saved.getVersion());
        data.put("changed", changed);
        data.put("values", TechErrorValues.toMap(TechErrorValues.fromStored(saved.getValuesJson(), t)));
        data.put("updated_at", iso(saved.getUpdatedAt()));
        data.put("updated_by_name", service.displayName(saved.getUpdatedById()));
        return ok(data);
    }

    @PostMapping("/reports/{id}/refresh-template")
    public ResponseEntity<Map<String, Object>> refreshTemplate(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                               HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = writableReport(id, session);
        Long version = body != null && body.get("version") instanceof Number n ? Long.valueOf(n.longValue()) : null;
        TechErrorReportService.RefreshResult res = service.refreshTemplate(r.getId(), version, userId(session));
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("template_refreshed", true, "removed_values", res.removedValues(), "removed_images", res.removedImages()));
        return ok(Map.of("version", res.report().getVersion(), "removed_values", res.removedValues(),
                "removed_images", res.removedImages()));
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<Map<String, Object>> deleteReport(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = ownWritableReport(id, session);
        if (!canDeleteReport(session, r.getTeamId(), r.getLastSentAt(), r.getSendState())) {
            throw new SecurityException(Msg.t(
                    "Gönderilmiş bir raporu yalnız takım yöneticisi silebilir.",
                    "Only a team manager can delete a report that has been sent."));
        }
        // Üye yalnız hiç gönderilmemiş taslağı siler — karar silme anında veritabanında yeniden verilir
        boolean wasSent = service.deleteReport(r.getId(), !SessionScope.canManage(session, r.getTeamId()));
        auditService.recordAction("TECH_ERROR_REPORT_DELETE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("channel_id", r.getChannelId(), "year", r.getReportYear(), "month", r.getReportMonth(),
                        "was_sent", wasSent));
        return ok(Map.of("deleted", true));
    }

    // ── Değişiklik ve sürüm geçmişi (2026-10-14) ─────────────────────────────────────────────────────

    /**
     * Raporun değişiklik geçmişi (en yeni önce, anahtar kümesi sayfalı: {@code before} = son görülen olayın kimliği).
     * Okuma kapısı raporla aynı. Kullanıcı kimliği verilmez — yalnız o anki ad. {@code recorded_since}: geçmişin ne
     * zamandan beri tutulduğu (bu özellikten önce açılmış raporda ondan önceki değişiklikler yoktur).
     */
    @GetMapping("/reports/{id}/history")
    public ResponseEntity<Map<String, Object>> reportHistory(@PathVariable Long id,
                                                             @RequestParam(value = "before", required = false) String before,
                                                             @RequestParam(value = "limit", required = false) Integer limit,
                                                             HttpSession session) {
        requireRead(session);
        TechErrorReport r = readableReport(id, session);
        Long beforeId = before == null || before.isBlank() ? null : parseId(before);
        if (before != null && !before.isBlank() && beforeId == null) {
            throw new FieldValidationException("before", Msg.t("Geçmiş sayfası okunamadı; listeyi yenileyin.",
                    "The history page couldn't be read; reload the list."));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        boolean more = false;
        boolean capped = false;
        Instant since = null;
        if (history != null) {
            var page = history.reportHistory(r.getId(), beforeId, limit == null ? 50 : limit);
            for (var e : page.events()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("at", iso(e.getEventAt()));
                m.put("kind", e.getKind());
                m.put("actor_name", e.getActorName());
                m.put("system", e.getActorId() == null);
                m.put("version", e.getReportVersion());
                m.put("detail", com.sitemonitor.service.techerror.TechErrorHistoryService.detail(e));
                list.add(m);
            }
            more = page.hasMore();
            since = page.recordedSince();
            capped = page.capped();
        }
        Map<Long, String> names = service.displayNames(Arrays.asList(r.getCreatedById(), r.getUpdatedById()));
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("created_at", iso(r.getCreatedAt()));
        rep.put("created_by_name", nameOf(names, r.getCreatedById()));
        rep.put("updated_at", iso(r.getUpdatedAt()));
        rep.put("updated_by_name", nameOf(names, r.getUpdatedById()));
        data.put("events", list);
        data.put("has_more", more);
        data.put("recorded_since", iso(since));
        data.put("capped", capped);
        data.put("max_events", com.sitemonitor.service.techerror.TechErrorHistoryService.MAX_EVENTS_PER_REPORT);
        data.put("report", rep);
        return ok(data);
    }

    /**
     * Kanal şablonunun sürümleri (en yeni önce; şablon gövdesi yok). Bu özellikten önce açılmış ve o günden beri hiç
     * kaydedilmemiş kanalda geçmiş yoktur — o anki sürüm kanalın kendisinden tek satır olarak verilir ({@code
     * recorded:false}).
     */
    @GetMapping("/channels/{id}/template/versions")
    public ResponseEntity<Map<String, Object>> templateVersions(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        List<Map<String, Object>> out = new ArrayList<>();
        boolean currentRecorded = false;
        if (history != null) {
            for (var v : history.templateVersions(ch.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", v.getVersion());
                m.put("saved_at", iso(v.getSavedAt()));
                m.put("saved_by_name", v.getSavedByName());
                m.put("section_count", v.getSectionCount());
                m.put("field_count", v.getFieldCount());
                m.put("slot_count", v.getSlotCount());
                m.put("current", v.getVersion() == ch.getTemplateVersion());
                m.put("recorded", true);
                if (v.getVersion() == ch.getTemplateVersion()) currentRecorded = true;
                out.add(m);
            }
        }
        if (!currentRecorded) {
            Template t = TechErrorTemplate.fromStored(ch.getTemplateJson());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", ch.getTemplateVersion());
            m.put("saved_at", iso(ch.getTemplateUpdatedAt()));
            m.put("saved_by_name", service.displayName(ch.getTemplateUpdatedById()));
            m.put("section_count", t.sections().size());
            m.put("field_count", t.allFields().size());
            m.put("slot_count", t.allSlots().size());
            m.put("current", true);
            m.put("recorded", false);
            out.add(0, m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current_version", ch.getTemplateVersion());
        data.put("versions", out);
        data.put("max_versions", com.sitemonitor.service.techerror.TechErrorHistoryService.MAX_TEMPLATE_VERSIONS);
        return ok(data);
    }

    /** Tek şablon sürümünün tam hâli (salt okuma). Kayıtlı değilse ve istenen o anki sürümse kanalın kendisinden. */
    @GetMapping("/channels/{id}/template/versions/{version}")
    public ResponseEntity<Map<String, Object>> templateVersion(@PathVariable Long id, @PathVariable int version,
                                                               HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        Map<String, Object> data = new LinkedHashMap<>();
        var stored = history == null ? Optional.<com.sitemonitor.model.TechErrorTemplateVersion>empty()
                : history.templateVersion(ch.getId(), version);
        if (stored.isPresent()) {
            var v = stored.get();
            data.put("version", v.getVersion());
            data.put("saved_at", iso(v.getSavedAt()));
            data.put("saved_by_name", v.getSavedByName());
            data.put("template", TechErrorTemplate.toMap(TechErrorTemplate.fromStored(v.getTemplateJson())));
        } else if (version == ch.getTemplateVersion()) {
            data.put("version", ch.getTemplateVersion());
            data.put("saved_at", iso(ch.getTemplateUpdatedAt()));
            data.put("saved_by_name", service.displayName(ch.getTemplateUpdatedById()));
            data.put("template", TechErrorTemplate.toMap(TechErrorTemplate.fromStored(ch.getTemplateJson())));
        } else {
            throw new NoSuchElementException(Msg.t(
                    "Bu şablon sürümü kayıtlı değil; sürüm geçmişi bu özellikten sonra tutulmaya başladı ya da eski sürümler budandı.",
                    "This template version isn't recorded; the version history started with this feature, or older versions were pruned."));
        }
        data.put("current", version == ch.getTemplateVersion());
        return ok(data);
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/reports/{id}/images/{slot}")
    public ResponseEntity<Map<String, Object>> uploadImage(@PathVariable Long id, @PathVariable String slot,
                                                           @RequestParam("file") MultipartFile file,
                                                           HttpSession session, HttpServletRequest request) throws IOException {
        requireWrite(session);
        TechErrorReport r = writableReport(id, session);
        if (file == null || file.isEmpty()) throw new FieldValidationException("file", Msg.t(
                "Dosya seçilmedi ya da boş.", "No file was chosen, or it's empty."));
        if (file.getSize() > com.sitemonitor.service.techerror.TechErrorImageValidator.MAX_BYTES) {
            throw new FieldValidationException("file", Msg.t(
                    "Görsel en çok 5 MB olabilir. Ekran görüntüsünü küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "An image can be at most 5 MB. Make the screenshot smaller or save it as JPEG, then upload it again."));
        }
        TechErrorReportImage img = service.upload(r.getId(), slot, file.getBytes(), file.getOriginalFilename(), userId(session));
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("image_added", slot, "size_bytes", img.getSizeBytes(), "width", img.getWidth(), "height", img.getHeight()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", img.getId());
        m.put("slot_id", img.getSlotId());
        m.put("content_type", img.getContentType());
        m.put("file_name", img.getFileName());
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("size_bytes", img.getSizeBytes());
        m.put("uploaded_at", iso(img.getUploadedAt()));
        m.put("uploaded_by_name", service.displayName(img.getUploadedById()));
        return ok(m);
    }

    @DeleteMapping("/reports/{id}/images/{imageId}")
    public ResponseEntity<Map<String, Object>> deleteImage(@PathVariable Long id, @PathVariable Long imageId,
                                                           HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = writableReport(id, session);
        TechErrorReportImage img = service.deleteImage(r.getId(), imageId, userId(session));
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("image_removed", img.getSlotId()));
        return ok(Map.of("deleted", true));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable Long imageId, HttpSession session) {
        requireRead(session);
        TechErrorReportImage img = service.image(imageId);
        TechErrorReport r = readableReport(img.getReportId(), session);
        if (!r.getId().equals(img.getReportId())) throw new NoSuchElementException();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(img.getData());
    }

    // ── PDF + gönderim ───────────────────────────────────────────────────────────────────────────────

    @GetMapping("/reports/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorReport r = readableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElse(null);
        String teamName = team == null ? null : team.getName();
        // İzin görseller belleğe yüklenmeden ÖNCE alınır (renderPdf yükleyiciyi izinle çağırır)
        TechErrorReportService.Rendered out = service.renderPdf(() -> service.pdfInput(r, ch, teamName));
        byte[] pdf = out.pdf();
        String name = TechErrorReportPdfWriter.fileName(ch.getName(), r.getReportYear(), r.getReportMonth(), out.meta().template().lang());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    /**
     * E-posta önizlemesi (10. sürüm): gönderilecek postanın gövdesi, konusu, alıcıları ve eki — okuma kapsamı yeter (PDF gibi);
     * yalnız paylaşımla okuyan adresleri değil sayıları görür (kişi verisi kuralı). Gönderim yok, iz yok. Teslim yolu kanalın
     * onay akışına göre: yönetici alıcısı tanımlıysa EXECUTIVE (yöneticilere, takım bilgide), yoksa TEAM.
     */
    @GetMapping("/reports/{id}/mail-preview")
    public ResponseEntity<Map<String, Object>> mailPreview(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorReport r = readableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElseThrow(TechErrorReportController::notFoundChannel);
        TechErrorReportSender.Delivery delivery = TechErrorApprovalService.approvalRequired(ch)
                ? TechErrorReportSender.Delivery.EXECUTIVE : TechErrorReportSender.Delivery.TEAM;
        TechErrorReportSender.Preview p = sender.preview(r, ch, team, delivery);
        boolean hidden = SessionScope.viewableOnlyByGrant(session, r.getTeamId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", p.subject());
        m.put("delivery", p.role());
        m.put("to", hidden ? List.of() : p.to());
        m.put("cc", hidden ? List.of() : p.cc());
        m.put("to_count", p.to().size());
        m.put("cc_count", p.cc().size());
        m.put("recipients_hidden", hidden);
        m.put("problem", p.problem());
        m.put("file_name", p.fileName());
        m.put("images_embedded", p.embeddedImages());
        m.put("images_skipped", p.skippedImages());
        m.put("pdf_bytes", p.pdfBytes());
        m.put("max_mail_mb", p.maxMailMb());
        m.put("html", p.html());
        m.put("text", p.text());
        // Kimlik (2026-10-17): oluşturan / son düzenleyen / onay — belgenin kendi bilgisi, gövdede de yazar; her okuyan görür
        TechErrorPdfInput.Provenance pv = p.provenance();
        if (pv != null) {
            Map<String, Object> who = new LinkedHashMap<>();
            who.put("created_by", pv.createdBy());
            who.put("created_at", iso(pv.createdAt()));
            who.put("updated_by", pv.updatedBy());
            who.put("updated_at", iso(pv.updatedAt()));
            who.put("approval_state", pv.approvalState());
            who.put("approved_by", pv.approvedBy());
            who.put("approved_at", iso(pv.approvedAt()));
            m.put("provenance", who);
        }
        return ok(m);
    }

    @PostMapping("/reports/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = ownWritableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElseThrow(TechErrorReportController::notFoundChannel);
        // Yönetici alıcısı tanımlı kanal (S6): gönderim yalnız onaylı ve onaydan beri değişmemiş raporda, yöneticilere
        boolean required = TechErrorApprovalService.approvalRequired(ch);
        if (required && (!"APPROVED".equals(r.getApprovalState()) || r.getApprovalRev() == null
                || !Objects.equals(r.getApprovalRev(), r.getContentRev() == null ? 0L : r.getContentRev()))) {
            return error(409, "APPROVAL_REQUIRED", Msg.t(
                    "Bu kanalın raporları PO onayıyla gönderilir: önce \"Onaya gönder\" ile isteyin; PO onaylayınca rapor yöneticilere kendiliğinden gider. Onaydan sonra değişen rapor yeniden onay ister.",
                    "Reports of this channel are sent after PO approval: submit it with \"Send for approval\" first; once the PO approves, the report goes to the executives automatically. A report changed after approval needs approval again."),
                    Map.of("approval", approvalDto(rowOf(r))));
        }
        TechErrorReportSender.Result res;
        try {
            res = required ? sender.send(r, ch, team, userId(session), TechErrorReportSender.Delivery.EXECUTIVE)
                    : sender.send(r, ch, team, userId(session));
        } catch (RuntimeException e) {
            auditService.recordAction("TECH_ERROR_REPORT_SEND", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    AuditDetail.of("outcome", e instanceof TechErrorReportService.PdfBusyException ? "SKIPPED" : "FAILED",
                            "reason", e.getClass().getSimpleName()));
            throw e;
        }
        auditService.recordAction("TECH_ERROR_REPORT_SEND", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("outcome", res.state(), "recipients", res.to().size(), "pdf_bytes", res.pdfBytes(),
                        "channel", ch.getName(), "year", r.getReportYear(), "month", r.getReportMonth()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", res.state());
        data.put("to", res.to());
        data.put("subject", res.subject());
        data.put("pdf_bytes", res.pdfBytes());
        switch (res.state()) {
            case "SENT", "QUEUED" -> { return ok(data); }
            case "SKIPPED" -> {
                return error(409, "MAIL_DISABLED", Msg.t(
                        "E-posta gönderimi sistemde kapalı ya da alıcı adresleri pasif kullanıcılara ait; rapor gönderilmedi. PDF'i indirip elle iletebilirsiniz.",
                        "E-mail sending is turned off, or the recipient addresses belong to inactive users; the report wasn't sent. You can download the PDF and forward it yourself."), data);
            }
            default -> {
                return error(502, "MAIL_FAILED", Msg.t(
                        "E-posta sunucusu raporu kabul etmedi. Birkaç dakika sonra tekrar deneyin; sorun sürerse PDF'i indirip elle iletin ve sistem yöneticisine bildirin.",
                        "The mail server didn't accept the report. Try again in a few minutes; if it keeps happening, download the PDF, forward it yourself and tell a system administrator."), data);
            }
        }
    }

    // ── PO onayı (2026-10-17, S6) ─────────────────────────────────────────────────────────────────────

    @GetMapping("/reports/{id}/approval")
    public ResponseEntity<Map<String, Object>> approval(@PathVariable Long id, HttpSession session) {
        requireRead(session);
        TechErrorReport r = readableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElse(null);
        boolean writer = permissions.allows(session, CRUD, "edit");
        return ok(approvalDetail(r, ch, team, session, writer));
    }

    @PostMapping("/reports/{id}/approval/request")
    public ResponseEntity<Map<String, Object>> requestApproval(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorApprovalService a = requireApprovals();
        TechErrorReport r = ownWritableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElseThrow(TechErrorReportController::notFoundChannel);
        TechErrorApprovalService.RequestResult res;
        try {
            res = a.request(r, ch, team, userId(session));
        } catch (RuntimeException e) {
            auditService.recordAction("TECH_ERROR_REPORT_APPROVAL_REQUEST", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    AuditDetail.of("outcome", "FAILED", "reason", e instanceof CodedConflictException c ? c.getCode() : e.getClass().getSimpleName()));
            throw e;
        }
        auditService.recordAction("TECH_ERROR_REPORT_APPROVAL_REQUEST", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("outcome", "PENDING", "approvers", res.approvers().size(), "mailed", res.mailed(), "failed", res.failed(),
                        "mail_problem", res.mailProblem(), "channel", ch.getName(), "year", r.getReportYear(), "month", r.getReportMonth()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", "pending");
        data.put("approvers", approverRows(res.approvers()));
        data.put("mailed", res.mailed());
        data.put("failed", res.failed());
        data.put("mail_problem", res.mailProblem());
        data.put("approval", approvalDto(rowOf(service.report(id))));
        return ok(data);
    }

    @PostMapping("/reports/{id}/approval/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                              HttpSession session, HttpServletRequest request) {
        requireRead(session);
        if (!permissions.allows(session, APPROVE, "execute")) throw new SecurityException(APPROVE + " yetkisi gerekli");
        TechErrorApprovalService a = requireApprovals();
        TechErrorReport r = readableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElseThrow(TechErrorReportController::notFoundChannel);
        Map<String, Object> b = body == null ? Map.of() : body;
        String decision = b.get("decision") == null ? "" : String.valueOf(b.get("decision")).trim().toLowerCase(Locale.ROOT);
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new FieldValidationException("decision", Msg.t("Karar 'approve' ya da 'reject' olmalı.", "The decision must be 'approve' or 'reject'."));
        }
        boolean approve = "approve".equals(decision);
        String note = b.get("note") == null ? null : String.valueOf(b.get("note"));
        String event = approve ? "TECH_ERROR_REPORT_APPROVE" : "TECH_ERROR_REPORT_REJECT";
        TechErrorApprovalService.Decision d;
        try {
            d = a.decide(r, ch, team, userId(session), approve, note, "app");
        } catch (RuntimeException e) {
            auditService.recordAction(event, session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    AuditDetail.of("outcome", "FAILED", "reason", e instanceof CodedConflictException c ? c.getCode() : e.getClass().getSimpleName()));
            throw e;
        }
        auditService.recordAction(event, session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("via", "app", "channel", ch.getName(), "send_state", d.send() == null ? d.sendError() : d.send().state(),
                        "recipients", d.send() == null ? 0 : d.send().to().size()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", d.state().toLowerCase(Locale.ROOT));
        data.put("send_state", d.send() == null ? null : d.send().state());
        data.put("send_error", d.sendError());
        data.put("sent_to", d.send() == null ? List.of() : d.send().to());
        data.put("approval", approvalDto(rowOf(service.report(id))));
        return ok(data);
    }

    private TechErrorApprovalService requireApprovals() {
        if (approvals == null) {
            throw new CodedConflictException("APPROVAL_UNAVAILABLE", Msg.t("Onay akışı bu sunucuda kullanılamıyor.",
                    "The approval flow is not available on this server."));
        }
        return approvals;
    }

    /**
     * Onay bloğunun ayrıntısı: akış var mı (kanalda yönetici alıcısı), alıcı sayısı, onaylayıcılar (ad; e-postası
     * olmayan uygulama içinden onaylar), isteyen / karar veren adı, bu oturum isteyebilir / karar verebilir mi.
     */
    private Map<String, Object> approvalDetail(TechErrorReport r, TechErrorChannel ch, Team team, HttpSession session, boolean writer) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean required = TechErrorApprovalService.approvalRequired(ch);
        List<String> execs = TechErrorReportSender.executiveRecipients(ch);
        boolean sharedOnly = SessionScope.viewableOnlyByGrant(session, r.getTeamId());   // kişi verisi paylaşımla görünmez
        m.put("required", required);
        m.put("executive_count", execs.size());
        m.put("executive_emails", sharedOnly ? null : execs);
        List<TechErrorApprovalService.Approver> apps = approvals == null || team == null ? List.of() : approvals.approvers(team.getId());
        m.put("approvers", approverRows(apps, sharedOnly));
        m.put("approver_count", apps.size());
        Map<Long, String> names = service.displayNames(Arrays.asList(r.getApprovalRequestedById(), r.getApprovalDecidedById()));
        m.put("requested_by_name", nameOf(names, r.getApprovalRequestedById()));
        m.put("decided_by_name", nameOf(names, r.getApprovalDecidedById()));
        Map<String, Object> a = approvalDto(rowOf(r));
        m.put("approval", a);
        String state = String.valueOf(a.get("state"));
        boolean canWrite = writer && canWriteOwn(session, r.getTeamId());        // onay / gönderim paylaşımla açılmaz (9. sürüm)
        // İstenebilir: akış var, onaylayıcı var ve istek yok / reddedildi / eskidi; bekleyen istek sessiz süreden eskiyse
        // yeniden gönderilebilir (hatırlatma); ONAYLI ve değişmemiş rapor yeniden istenmez (onay boşa gitmesin — inceleme 2026-10-17)
        Instant now = service.now();
        boolean resend = "pending".equals(state) && r.getApprovalRequestedAt() != null
                && r.getApprovalRequestedAt().isBefore(now.minus(TechErrorApprovalService.RESEND_AFTER));
        boolean requestable = required && !apps.isEmpty()
                && ("none".equals(state) || "rejected".equals(state) || "stale".equals(state) || resend);
        m.put("can_request", canWrite && requestable && approvals != null);
        m.put("resend", canWrite && resend && approvals != null);
        Long uid = userId(session);
        boolean approver = uid != null && apps.stream().anyMatch(ap -> uid.equals(ap.id()));
        m.put("is_approver", approver);
        m.put("can_decide", approver && "pending".equals(state) && permissions.allows(session, APPROVE, "execute"));
        // Onaylı ve onaydan beri değişmemiş rapor: yazabilen yöneticilere (yeniden) gönderebilir
        m.put("can_send_executive", canWrite && required && "approved".equals(state));
        return m;
    }

    private static List<Map<String, Object>> approverRows(List<TechErrorApprovalService.Approver> apps) {
        return approverRows(apps, false);
    }

    /** Onaylayıcı satırları; paylaşımla okuyana ad verilmez (kişi listesi) — yalnız sayı ve e-posta var/yok. */
    private static List<Map<String, Object>> approverRows(List<TechErrorApprovalService.Approver> apps, boolean nameless) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TechErrorApprovalService.Approver a : apps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", nameless ? null : a.name());
            row.put("has_email", a.email() != null);
            out.add(row);
        }
        return out;
    }

    // ── Kapsam ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Görünür takımlar: null = hepsi (kurum geneli görüntüleyici). Modül görünürlüğü (S6) takım bazlıysa kapsamdaki
     * takımlardan yalnız açık olanlar kalır — kurum geneli görüntüleyici her takımı görür (ayarı o yönetir).
     */
    static Set<Long> visibleTeamIds(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        Set<Long> out = ownVisibleTeamIds(session);
        out.addAll(SessionScope.grantViewTeamIds(session));              // erişim paylaşımı (9. sürüm, 2026-10-17): VIEW ve üstü okur
        out.remove(null);
        out.removeIf(t -> !TechErrorVisibilityService.visible(t));
        return out;
    }

    /**
     * Takımın KENDİ kapsamı (paylaşım hariç) — yönetim yüzeyleri (Ayarlar → Teknik hata raporu genel bakışı) bunu kullanır:
     * yönetim alanları paylaşımla açılmaz (inceleme 2026-10-17). null = kurum geneli görüntüleyici.
     */
    static Set<Long> ownVisibleTeamIds(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        Set<Long> out = new LinkedHashSet<>();
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v != null) out.addAll(v);
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null) out.addAll(m);
        out.addAll(SessionScope.memberTeamIds(session));
        out.remove(null);
        out.removeIf(t -> !TechErrorVisibilityService.visible(t));
        return out;
    }

    /**
     * Kişi verisi paylaşımla görünmez (proje kuralı, 4. inceleme turu 2026-10-12): yalnız paylaşımla okuyan kişi gönderim
     * alıcılarını (adresler) görmez — yalnız sayısı kalır. Yönetici alıcıları ve onaylayıcı adları ilgili DTO'larda maskelenir.
     */
    static Map<String, Object> maskSharedPeople(Map<String, Object> row, HttpSession session, Long teamId) {
        if (row == null || !SessionScope.viewableOnlyByGrant(session, teamId)) return row;
        Object to = row.get("last_sent_to");
        if (to != null) {
            row.put("last_sent_to", null);
            row.put("last_sent_count", TechErrorReportSender.parseAddresses(String.valueOf(to)).size());
        }
        return row;
    }

    static boolean canRead(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        if (SessionScope.isGlobalViewer(session)) return true;
        Set<Long> v = visibleTeamIds(session);
        return v != null && v.contains(teamId);
    }

    /**
     * Rapor İÇERİĞİNİ yazma (değerler, görseller, rapor açma, şablonun son hâlini uygulama): takımın üyesi / yöneticisi
     * ya da takım erişim paylaşımında EDIT (ve üstü) düzeyi olan (9. sürüm, 2026-10-17 — kullanıcı isteği: "raporlar
     * paylaşıma açılabilsin"). Kanal / şablon / onay / gönderim / silme ve yönetici alıcıları {@link #canWriteOwn} ister.
     */
    static boolean canWrite(HttpSession session, Long teamId) {
        return canWriteOwn(session, teamId)
                || (teamId != null && TechErrorVisibilityService.visible(teamId) && SessionScope.grantedEdit(session, teamId));
    }

    /** Takımın KENDİ yazma kapsamı (paylaşım hariç): kanal açma / düzenleme / silme, şablon, onay isteği, gönderim, rapor silme. */
    static boolean canWriteOwn(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        if (SessionScope.isGlobalAdmin(session)) return true;
        if (!TechErrorVisibilityService.visible(teamId)) return false;      // modül bu takıma kapalı (S6)
        return SessionScope.canManage(session, teamId) || SessionScope.isMemberOf(session, teamId);
    }

    /** Hiç gönderilmemiş taslak: takımın yazabileni siler; gönderilmiş rapor: yalnız yönetim kapsamı. Paylaşım silmez. */
    static boolean canDeleteReport(HttpSession session, Long teamId, Instant lastSentAt, String sendState) {
        if (!canWriteOwn(session, teamId)) return false;
        // Kuyruktaki gönderim teslim edilmiş olabilir: üye için "gönderilmiş" sayılır
        return (lastSentAt == null && !"QUEUED".equals(sendState)) || SessionScope.canManage(session, teamId);
    }

    private TechErrorChannel readableChannel(Long id, HttpSession session) {
        TechErrorChannel ch;
        try {
            ch = service.channel(id);
        } catch (NoSuchElementException e) {
            return null;
        }
        return canRead(session, ch.getTeamId()) ? ch : null;
    }

    private TechErrorChannel writableChannel(Long id, HttpSession session) {
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        if (!canWriteOwn(session, ch.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu kanalı yalnız takımın üyeleri ve yöneticileri değiştirebilir.",
                    "Only the team's members and managers can change this channel."));
        }
        return ch;
    }

    private TechErrorReport readableReport(Long id, HttpSession session) {
        TechErrorReport r = service.report(id);
        if (!canRead(session, r.getTeamId())) {
            throw new NoSuchElementException(Msg.t(
                    "Rapor bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                    "The report wasn't found; it may have been deleted or you can't see it. Refresh the list."));
        }
        return r;
    }

    private TechErrorReport writableReport(Long id, HttpSession session) {
        TechErrorReport r = readableReport(id, session);
        if (!canWrite(session, r.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu raporu yalnız takımın üyeleri ve yöneticileri (ya da düzenleme paylaşımı olanlar) değiştirebilir.",
                    "Only the team's members and managers (or people with an edit share) can change this report."));
        }
        return r;
    }

    /** Rapor AÇMA (içerik yazma kapsamı): takımın üyesi / yöneticisi ya da EDIT paylaşımı (9. sürüm); kanalı değiştirmez. */
    private TechErrorChannel contentWritableChannel(Long id, HttpSession session) {
        TechErrorChannel ch = readableChannel(id, session);
        if (ch == null) throw notFoundChannel();
        if (!canWrite(session, ch.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu kanalda rapor açmak için takımın üyesi ya da yöneticisi olmanız (ya da düzenleme paylaşımı almanız) gerekir.",
                    "To open a report in this channel you must be a member or manager of the team (or have an edit share)."));
        }
        return ch;
    }

    /** Gönderim / onay isteği / silme: paylaşımla DEĞİL — takımın kendi yazma kapsamı (9. sürüm). */
    private TechErrorReport ownWritableReport(Long id, HttpSession session) {
        TechErrorReport r = readableReport(id, session);
        if (!canWriteOwn(session, r.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu işlemi yalnız takımın üyeleri ve yöneticileri yapabilir; erişim paylaşımı raporu düzenletir, göndertmez.",
                    "Only the team's members and managers can do this; an access share lets you edit the report, not send it."));
        }
        return r;
    }

    private void requireRead(HttpSession session) {
        if (!permissions.allows(session, READ, "view")) throw new SecurityException(READ + " yetkisi gerekli");
        requireModuleVisible(session);
    }

    /** Modül görünürlüğü (S6): takım bazlıysa kapsamında açık bir takım olmayan kullanıcı 403 alır (arayüz menüyü de gizler). */
    private static void requireModuleVisible(HttpSession session) {
        TechErrorVisibilityService v = TechErrorVisibilityService.current();
        if (v != null && !v.sessionSees(session)) {
            throw new SecurityException(Msg.t(
                    "Kanal teknik hata raporu modülü takımınız için açık değil. Gerekiyorsa sistem yöneticisi Ayarlar → Teknik hata raporu'ndan açar.",
                    "The channel technical error report module is not enabled for your team. A system administrator can enable it under Settings → Technical error report."));
        }
    }

    private void requireWrite(HttpSession session) {
        if (!permissions.allows(session, CRUD, "edit")) throw new SecurityException(CRUD + " yetkisi gerekli");
        requireModuleVisible(session);
    }

    // ── DTO ──────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> channelDto(TechErrorChannel ch, Team team, HttpSession session, boolean writer) {
        Template t = TechErrorTemplate.fromStored(ch.getTemplateJson());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("team_id", ch.getTeamId());
        m.put("team_name", team == null ? null : team.getName());
        m.put("name", ch.getName());
        m.put("description", ch.getDescription());
        m.put("active", ch.isActive());
        m.put("sort_order", ch.getSortOrder());
        m.put("template_version", ch.getTemplateVersion());
        m.put("template_updated_at", iso(ch.getTemplateUpdatedAt()));
        m.put("lang", t.lang());
        m.put("section_count", t.sections().size());
        m.put("field_count", t.allFields().size());
        m.put("slot_count", t.allSlots().size());
        m.put("can_edit", writer && canWriteOwn(session, ch.getTeamId()));       // kanal / şablon: takımın kendi kapsamı
        m.put("can_report", writer && canWrite(session, ch.getTeamId()));        // rapor açma / içerik: paylaşım EDIT de (9. sürüm)
        m.put("can_delete", writer && SessionScope.canManage(session, ch.getTeamId()));
        m.put("can_manage", writer && SessionScope.canManage(session, ch.getTeamId()));
        m.put("shared_access", SessionScope.viewableOnlyByGrant(session, ch.getTeamId()));
        // PO onayı (S6): yönetici alıcıları (takımı yönetenler değiştirir) — doluysa gönderim onaya bağlı
        List<String> execs = TechErrorReportSender.executiveRecipients(ch);
        // Adresler kişi verisidir: yalnız paylaşımla okuyan sayıyı görür (inceleme 2026-10-17)
        m.put("executive_emails", SessionScope.viewableOnlyByGrant(session, ch.getTeamId()) ? null : ch.getExecutiveEmails());
        m.put("executive_count", execs.size());
        m.put("approval_required", !execs.isEmpty());
        return m;
    }

    /** Liste / durum satırı. {@code status}: draft | sending | sent | changed (gönderimden sonra değişti). */
    static Map<String, Object> statusDto(TechErrorReportRepository.Row r, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) return m;
        m.put("id", r.getId());
        m.put("channel_id", r.getChannelId());
        m.put("team_id", r.getTeamId());
        m.put("year", r.getReportYear());
        m.put("month", r.getReportMonth());
        m.put("status", status(r, now));
        m.put("send_state", r.getSendState());
        m.put("last_send_status", publicSendStatus(r.getSendState()));
        m.put("created_at", iso(r.getCreatedAt()));
        m.put("updated_at", iso(r.getUpdatedAt()));
        m.put("content_changed_at", iso(r.getContentChangedAt()));
        m.put("last_sent_at", iso(r.getLastSentAt()));
        m.put("last_attempt_at", iso(r.getLastAttemptAt()));
        m.put("last_sent_to", r.getLastSentTo());
        m.put("send_count", r.getSendCount());
        m.put("approval", approvalDto(r));
        return m;
    }

    /**
     * Onay bloğu (2026-10-17, S6): {@code state} none | pending | approved | rejected | stale (istek ya da onay, içerik
     * değiştiği için eskidi — yeniden onay gerekir). Kişi adları çağıranın eklediği ad haritasından (opak kimlik kuralı).
     */
    static Map<String, Object> approvalDto(TechErrorReportRepository.Row r) {
        Map<String, Object> a = new LinkedHashMap<>();
        String st = r.getApprovalState();
        long content = r.getContentRev() == null ? 0L : r.getContentRev();
        boolean stale = st != null && !"REJECTED".equals(st) && r.getApprovalRev() != null && content > r.getApprovalRev();
        a.put("state", st == null ? "none" : stale ? "stale" : st.toLowerCase(Locale.ROOT));
        a.put("raw_state", st);
        a.put("stale", stale);
        a.put("requested_at", iso(r.getApprovalRequestedAt()));
        a.put("decided_at", iso(r.getApprovalDecidedAt()));
        a.put("note", r.getApprovalNote());
        return a;
    }

    /** Varlıktan liste satırı görünümü (tek rapor okumasında ikinci sorgu olmasın). */
    static TechErrorReportRepository.Row rowOf(TechErrorReport r) {
        return new TechErrorReportRepository.Row() {
            public Long getId() { return r.getId(); }
            public Long getChannelId() { return r.getChannelId(); }
            public Long getTeamId() { return r.getTeamId(); }
            public Integer getReportYear() { return r.getReportYear(); }
            public Integer getReportMonth() { return r.getReportMonth(); }
            public Instant getCreatedAt() { return r.getCreatedAt(); }
            public Instant getUpdatedAt() { return r.getUpdatedAt(); }
            public Long getUpdatedById() { return r.getUpdatedById(); }
            public Instant getContentChangedAt() { return r.getContentChangedAt(); }
            public Long getContentRev() { return r.getContentRev(); }
            public Long getSentRev() { return r.getSentRev(); }
            public String getTitlePattern() { return r.getTitlePattern(); }
            public String getTemplateLang() { return r.getTemplateLang(); }
            public String getSendState() { return r.getSendState(); }
            public Instant getSendClaimedAt() { return r.getSendClaimedAt(); }
            public Instant getLastSentAt() { return r.getLastSentAt(); }
            public Long getLastSentById() { return r.getLastSentById(); }
            public String getLastSentTo() { return r.getLastSentTo(); }
            public Instant getLastAttemptAt() { return r.getLastAttemptAt(); }
            public String getLastSendStatus() { return r.getLastSendStatus(); }
            public int getSendCount() { return r.getSendCount(); }
            public String getApprovalState() { return r.getApprovalState(); }
            public Long getApprovalRev() { return r.getApprovalRev(); }
            public Instant getApprovalRequestedAt() { return r.getApprovalRequestedAt(); }
            public Long getApprovalRequestedById() { return r.getApprovalRequestedById(); }
            public Instant getApprovalDecidedAt() { return r.getApprovalDecidedAt(); }
            public Long getApprovalDecidedById() { return r.getApprovalDecidedById(); }
            public String getApprovalNote() { return r.getApprovalNote(); }
        };
    }

    static String status(TechErrorReportRepository.Row r, Instant now) {
        if ("SENDING".equals(r.getSendState()) && r.getSendClaimedAt() != null
                && r.getSendClaimedAt().isAfter(now.minus(java.time.Duration.ofMinutes(10)))) return "sending";
        if (r.getLastSentAt() == null) return "QUEUED".equals(r.getSendState()) ? "queued" : "draft";
        if (r.getSentRev() != null) {
            long content = r.getContentRev() == null ? 0L : r.getContentRev();
            return content > r.getSentRev() ? "changed" : "sent";
        }
        // Sayaçtan önceki gönderim: zaman damgası
        if (r.getContentChangedAt() != null && r.getContentChangedAt().isAfter(r.getLastSentAt())) return "changed";
        return "sent";
    }

    /** Son denemenin sonucu (ham SMTP iletisi değil — yalnız sınıf). */
    static String publicSendStatus(String state) {
        if (state == null) return null;
        return switch (state) {
            case "SENT", "QUEUED", "SKIPPED", "FAILED", "SENDING" -> state.toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private Map<String, Object> previousDto(TechErrorReport r, Template current) {
        Optional<TechErrorReport> prev = service.previousReport(r);
        if (prev.isEmpty()) return null;
        TechErrorReport p = prev.get();
        // Yalnız AYNI türdeki alan (inceleme 2026-10-13): kimliği başka bir alana geçmiş ya da türü değişmiş bir alanın geçen
        // ay değeri "geçen ay" ipucu olarak gösterilmez — sunucunun değişim hesabı da aynı süzgeci kullanır
        Map<String, Object> fields = TechErrorReportService.sameTypeFields(p, current);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("year", p.getReportYear());
        m.put("month", p.getReportMonth());
        m.put("fields", fields);
        return m;
    }

    /** Trend: aylar (eskiden yeniye) + alan → değerler (boş ay null). Değerler kayıtlı hâl; düzenleyici kendi ayını taslakla günceller. */
    private static Map<String, Object> trendDto(com.sitemonitor.service.techerror.TechErrorTrend tr) {
        if (tr == null) tr = com.sitemonitor.service.techerror.TechErrorTrend.empty();
        List<Map<String, Object>> months = new ArrayList<>();
        for (java.time.YearMonth ym : tr.months()) months.add(Map.of("year", ym.getYear(), "month", ym.getMonthValue()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("months", months);
        m.put("values", new LinkedHashMap<>(tr.values()));
        m.put("min_points", com.sitemonitor.service.techerror.TechErrorTrend.MIN_POINTS);
        return m;
    }

    /**
     * Aynı kanalın bu rapordan önceki ve sonraki EN YAKIN raporu (dönem sırası; ara ay boş olabilir) — rapor ekranında
     * tek tıkla geçiş. Kanalın rapor listesi zaten sınırlı ({@code reportRows}, ≤ 600 hafif satır); trend aynı listeyi
     * kullanır (bir kez okunur).
     */
    private Map<String, Object> neighborsDto(TechErrorReport r, List<TechErrorReportRepository.Row> rows) {
        int here = r.getReportYear() * 12 + r.getReportMonth();
        TechErrorReportRepository.Row prev = null, next = null;
        for (var row : rows) {
            int p = row.getReportYear() * 12 + row.getReportMonth();
            if (p < here && (prev == null || p > prev.getReportYear() * 12 + prev.getReportMonth())) prev = row;
            if (p > here && (next == null || p < next.getReportYear() * 12 + next.getReportMonth())) next = row;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prev", prev == null ? null : Map.of("id", prev.getId(), "year", prev.getReportYear(), "month", prev.getReportMonth()));
        m.put("next", next == null ? null : Map.of("id", next.getId(), "year", next.getReportYear(), "month", next.getReportMonth()));
        return m;
    }

    private Map<String, Object> imageDto(TechErrorReportImageRepository.Meta i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("slot_id", i.getSlotId());
        m.put("content_type", i.getContentType());
        m.put("file_name", i.getFileName());
        m.put("width", i.getWidth());
        m.put("height", i.getHeight());
        m.put("size_bytes", i.getSizeBytes());
        m.put("uploaded_at", iso(i.getUploadedAt()));
        return m;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private static String nameOf(Map<Long, String> names, Long id) {
        return id == null || names == null ? null : names.get(id);
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Long userId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return v == null ? null : Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static Long parseId(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(Object o, String field) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException ignored) { /* aşağıda */ }
        }
        throw new FieldValidationException(field, "year".equals(field)
                ? Msg.t("Yıl seçin.", "Choose a year.") : Msg.t("Ay seçin.", "Choose a month."));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static NoSuchElementException notFoundChannel() {
        return new NoSuchElementException(Msg.t(
                "Kanal bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The channel wasn't found; it may have been deleted or you can't see it. Refresh the list."));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        body.put("data", data);
        return ResponseEntity.status(status).body(body);
    }
}
