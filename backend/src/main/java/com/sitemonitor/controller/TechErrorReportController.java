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
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorReportService;
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

    static final String READ = "tech_error_reports.read";
    static final String CRUD = "tech_error_reports.crud";

    private final TechErrorReportService service;
    private final TechErrorReportSender sender;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;

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
            m.put("period_report", pr == null ? null : statusDto(pr, service.now()));
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
            tm.put("can_edit", writer && canWrite(session, t.getId()));
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
        if (team == null || !canWrite(session, teamId)) {
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
            Map<String, Object> m = statusDto(r, now);
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
        TechErrorChannel ch = writableChannel(channelId, session);
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

        Map<String, Object> rep = statusDto(rowOf(r), now);
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
        data.put("can_edit", canEdit);
        data.put("can_delete", writer && canDeleteReport(session, r.getTeamId(), r.getLastSentAt(), r.getSendState()));
        data.put("team_email_set", team != null && !TechErrorReportSender.recipients(team).isEmpty());
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
        TechErrorReport saved = service.saveValues(r.getId(), version, b.get("values"), userId(session));
        boolean changed = !Objects.equals(saved.getVersion(), version);
        if (changed) {
            auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    AuditDetail.of("channel_id", r.getChannelId(), "year", r.getReportYear(), "month", r.getReportMonth(),
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
        TechErrorReport r = writableReport(id, session);
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
        TechErrorReportImage img = service.deleteImage(r.getId(), imageId);
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

    @PostMapping("/reports/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorReport r = writableReport(id, session);
        TechErrorChannel ch = service.channel(r.getChannelId());
        Team team = teams.findById(r.getTeamId()).orElseThrow(TechErrorReportController::notFoundChannel);
        TechErrorReportSender.Result res;
        try {
            res = sender.send(r, ch, team, userId(session));
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

    // ── Kapsam ───────────────────────────────────────────────────────────────────────────────────────

    /** Görünür takımlar: null = hepsi (kurum geneli görüntüleyici). */
    static Set<Long> visibleTeamIds(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        Set<Long> out = new LinkedHashSet<>();
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v != null) out.addAll(v);
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null) out.addAll(m);
        out.addAll(SessionScope.memberTeamIds(session));
        out.remove(null);
        return out;
    }

    static boolean canRead(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        if (SessionScope.isGlobalViewer(session)) return true;
        Set<Long> v = visibleTeamIds(session);
        return v != null && v.contains(teamId);
    }

    static boolean canWrite(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        return SessionScope.isGlobalAdmin(session) || SessionScope.canManage(session, teamId) || SessionScope.isMemberOf(session, teamId);
    }

    /** Hiç gönderilmemiş taslak: yazabilen siler; gönderilmiş rapor: yalnız yönetim kapsamı. */
    static boolean canDeleteReport(HttpSession session, Long teamId, Instant lastSentAt, String sendState) {
        if (!canWrite(session, teamId)) return false;
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
        if (!canWrite(session, ch.getTeamId())) {
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
                    "Bu raporu yalnız takımın üyeleri ve yöneticileri değiştirebilir.",
                    "Only the team's members and managers can change this report."));
        }
        return r;
    }

    private void requireRead(HttpSession session) {
        if (!permissions.allows(session, READ, "view")) throw new SecurityException(READ + " yetkisi gerekli");
    }

    private void requireWrite(HttpSession session) {
        if (!permissions.allows(session, CRUD, "edit")) throw new SecurityException(CRUD + " yetkisi gerekli");
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
        m.put("can_edit", writer && canWrite(session, ch.getTeamId()));
        m.put("can_delete", writer && SessionScope.canManage(session, ch.getTeamId()));
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
        return m;
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
        Template pt = TechErrorTemplate.fromStored(p.getTemplateJson());
        TechErrorValues.Values pv = TechErrorValues.fromStored(p.getValuesJson(), pt);
        Map<String, String> prevTypes = new HashMap<>();
        for (var f : pt.allFields()) prevTypes.put(f.id(), f.type());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var f : current.allFields()) {
            Object v = pv.fields().get(f.id());
            // Yalnız AYNI türdeki alan (inceleme 2026-10-13): kimliği başka bir alana geçmiş ya da türü değişmiş bir
            // alanın geçen ay değeri "geçen ay" ipucu olarak gösterilmez
            if (v != null && !"textarea".equals(f.type()) && !"text".equals(f.type()) && f.type().equals(prevTypes.get(f.id()))) {
                fields.put(f.id(), v);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("year", p.getReportYear());
        m.put("month", p.getReportMonth());
        m.put("fields", fields);
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
