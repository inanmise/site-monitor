package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.Team;
import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportImage;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.repository.WeeklyReportImageRepository;
import com.certmonitor.repository.WeeklyReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Haftalık rapor iş mantığı: durum makinesi (DRAFT → PENDING_APPROVAL →
 * APPROVED/REJECTED), şablon kopyalama, yetki kontrolleri ve mail tetikleme.
 *
 * Yetki modeli (not sistemindeki requireAdminOrTeamAdmin'den BİLİNÇLİ sapma):
 * - Düzenleme/silme: ADMIN her takım, her durum, her hafta (onaylanıp müdüre
 *   gönderilmiş raporun düzeltilmesi dahil). USER/TEAM_ADMIN yalnız kendi
 *   takımının DRAFT/REJECTED raporunu ve yalnız içinde bulunulan + bir önceki
 *   ISO haftasını düzenler (önceki hafta: PO iadesi hafta sınırını aşabilsin).
 * - Onay/iade: ADMIN; aynı takımın TEAM_ADMIN'i; ya da orgRole=PO olan aynı
 *   takım kullanıcısı (session'da orgRole yok — DB'den okunur).
 * - Self-approval serbest: raporu düzenleyen PO kendi raporunu onaylayabilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportService {

    private final WeeklyReportRepository reportRepo;
    private final WeeklyReportImageRepository imageRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final EscalationContactRepository contactRepo;
    private final EmailNotificationService emailService;
    private final ObjectMapper objectMapper;

    @Value("${cert.monitor.weekly-report.image-max-bytes:2097152}")
    private long imageMaxBytes;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private static final int MAX_CONTENT_BYTES = 200 * 1024;
    private static final int MAX_CHANNELS = 20;

    /** Yumuşak kilit bayatlama eşiği — heartbeat 45 sn'de bir tazelenir,
     *  4 kaçırılmış vuruş sonrası kilit serbest sayılır. */
    private static final long LOCK_STALE_SECONDS = 180;

    private static final Pattern IMAGE_REF =
            Pattern.compile("/api/weekly-reports/images/(\\d+)");

    static final String DEFAULT_TEMPLATE_JSON = """
            {"version":1,
             "item1":{"total":0,"urgent":0,"high":0,"medium":0,"low":0,"status_text":"Çalışılıyor","tracking_url":"","notes_md":""},
             "item2":{"open_incidents":0,"problem_records":0,"postmortems":0,"incidents_url":"","problems_url":"","postmortems_url":"","notes_md":""},
             "item3":{"notes_md":""},
             "item4":{"channels":[
               {"id":"c-1","name":"İnternet","notes_md":""},
               {"id":"c-2","name":"Çağrı Merkezi","notes_md":""},
               {"id":"c-3","name":"Web Kanalı (Akbank.com)","notes_md":""}]}}
            """;

    /** Controller session'dan kurar — servis testleri MockHttpSession istemez. */
    public record Actor(Long userId, String username, String displayName,
                        Long teamId, String systemRole) {
        public boolean isAdmin() { return "ADMIN".equals(systemRole); }
        public boolean isAudit() { return "AUDIT".equals(systemRole); }
        public boolean isTeamAdmin() { return "TEAM_ADMIN".equals(systemRole); }
        public String display() {
            return displayName != null && !displayName.isBlank() ? displayName : username;
        }
    }

    // ── Listeleme / okuma ─────────────────────────────────────────────────────

    public List<WeeklyReport> list(Long requestedTeamId, Integer year, Actor actor) {
        int y = year != null ? year : LocalDate.now().getYear();
        Long teamId = actor.isAdmin() || actor.isAudit()
                ? requestedTeamId
                : actor.teamId(); // non-ADMIN kendi takımına zorlanır
        if (teamId == null) {
            return actor.isAdmin() || actor.isAudit()
                    ? reportRepo.findByReportYearOrderByTeamIdAscWeekNoDesc(y)
                    : List.of();
        }
        return reportRepo.findByTeamIdAndReportYearOrderByWeekNoDesc(teamId, y);
    }

    /** Yıl dropdown'ı: rapor bulunan yıllar (takım scoping'i list() ile aynı);
     *  içinde bulunulan ISO yılı yoksa başa eklenir — dropdown boş kalmaz. */
    public List<Integer> years(Long requestedTeamId, Actor actor) {
        Long teamId = actor.isAdmin() || actor.isAudit() ? requestedTeamId : actor.teamId();
        int current = LocalDate.now().get(WeekFields.ISO.weekBasedYear());
        if (!actor.isAdmin() && !actor.isAudit() && teamId == null) {
            return List.of(current);
        }
        List<Integer> years = new ArrayList<>(reportRepo.findDistinctYears(teamId));
        if (!years.contains(current)) years.add(0, current);
        return years;
    }

    public WeeklyReport get(Long id, Actor actor) {
        WeeklyReport r = reportRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Weekly report not found: " + id));
        requireCanRead(r, actor);
        return r;
    }

    public List<WeeklyReportImage> imagesMeta(Long reportId) {
        return imageRepo.findByReportIdOrderByIdAsc(reportId);
    }

    public boolean managerContactMissing(Long teamId) {
        return contactRepo.findByTeamIdAndRoleAndActiveTrue(teamId, "MANAGER").stream()
                .noneMatch(c -> c.getEmail() != null && !c.getEmail().isBlank());
    }

    // ── Oluşturma — şablon kopyalama ──────────────────────────────────────────

    public WeeklyReport create(Long requestedTeamId, int year, int weekNo, Actor actor) {
        Long teamId = actor.isAdmin() ? requestedTeamId : actor.teamId();
        if (teamId == null) throw new IllegalArgumentException("Takım belirlenemedi");
        if (weekNo < 1 || weekNo > 53) throw new IllegalArgumentException("Geçersiz hafta numarası: " + weekNo);
        teamRepo.findById(teamId)
                .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));
        if (!actor.isAdmin() && !Objects.equals(actor.teamId(), teamId)) {
            throw new SecurityException("Başka takım için rapor oluşturulamaz");
        }
        if (reportRepo.findByTeamIdAndReportYearAndWeekNo(teamId, year, weekNo).isPresent()) {
            throw new IllegalStateException("DUPLICATE_WEEK");
        }

        // Şablon: takımın en güncel raporunun yapısı (kanallar + takip URL'leri)
        String content = reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(teamId)
                .map(prev -> resetTemplate(prev.getContentJson()))
                .orElse(DEFAULT_TEMPLATE_JSON);

        String now = now();
        WeeklyReport r = new WeeklyReport();
        r.setTeamId(teamId);
        r.setReportYear(year);
        r.setWeekNo(weekNo);
        r.setWeekLabel(computeWeekLabel(year, weekNo));
        r.setStatus("DRAFT");
        r.setContentJson(content);
        r.setCreatedBy(actor.display());
        r.setCreatedAt(now);
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now);
        return reportRepo.save(r);
    }

    // ── Düzenleme kilidi (yumuşak) ───────────────────────────────────────────

    /** Kilit taze mi? (heartbeat LOCK_STALE_SECONDS içinde) */
    public boolean lockFresh(WeeklyReport r) {
        if (r.getEditingUserId() == null || r.getEditingHeartbeat() == null) return false;
        try {
            Instant hb = Instant.from(ISO.parse(r.getEditingHeartbeat()));
            return hb.isAfter(Instant.now().minusSeconds(LOCK_STALE_SECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean lockHeldByOther(WeeklyReport r, Actor a) {
        return r.getEditingUserId() != null
                && !Objects.equals(r.getEditingUserId(), a.userId())
                && lockFresh(r);
    }

    /** Kilidi al/tazele. Boş, kendine ait veya bayat kilit alınır; başkasında
     *  ve tazeyse yalnız ADMIN force ile devralır. */
    public Map<String, Object> acquireLock(Long id, boolean force, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanModify(r, actor);
        if (lockHeldByOther(r, actor) && !(force && actor.isAdmin())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("acquired", false);
            out.put("editing_by", r.getEditingBy());
            out.put("heartbeat_at", r.getEditingHeartbeat());
            return out;
        }
        r.setEditingUserId(actor.userId());
        r.setEditingBy(actor.display());
        r.setEditingHeartbeat(now());
        reportRepo.save(r);
        return Map.of("acquired", true);
    }

    /** Yalnız kendi kilidini bırakır — başkasının kilidine dokunmaz (sessiz). */
    public void releaseLock(Long id, Actor actor) {
        WeeklyReport r = reportRepo.findById(id).orElse(null);
        if (r == null || !Objects.equals(r.getEditingUserId(), actor.userId())) return;
        r.setEditingUserId(null);
        r.setEditingBy(null);
        r.setEditingHeartbeat(null);
        reportRepo.save(r);
    }

    private void clearLock(WeeklyReport r) {
        r.setEditingUserId(null);
        r.setEditingBy(null);
        r.setEditingHeartbeat(null);
    }

    // ── İçerik kaydetme ───────────────────────────────────────────────────────

    public WeeklyReport saveContent(Long id, String contentJson, Long clientVersion, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanModify(r, actor);
        // İyimser kilitleme: istemcinin yüklediği sürüm eskiyse kayıt reddedilir (409)
        if (clientVersion != null && r.getVersion() != clientVersion.intValue()) {
            throw new IllegalStateException("VERSION_CONFLICT: rapor " + r.getUpdatedBy()
                    + " tarafından " + r.getUpdatedAt() + " tarihinde güncellendi");
        }
        r.setContentJson(validateAndNormalizeContent(contentJson));
        if ("REJECTED".equals(r.getStatus())) {
            r.setStatus("DRAFT"); // iade sonrası düzenleme draft'a döndürür; rejectNote korunur
        }
        r.setVersion(r.getVersion() + 1);
        if (Objects.equals(r.getEditingUserId(), actor.userId())) {
            r.setEditingHeartbeat(now()); // kayıt = aktivite
        }
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        return reportRepo.save(r);
    }

    // ── Durum makinesi + mailler ──────────────────────────────────────────────

    /** DRAFT → PENDING_APPROVAL; PO'ya bilgilendirme maili (yoksa SKIPPED). */
    public Map<String, Object> submit(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanModify(r, actor);
        requireStatus(r, "DRAFT"); // ADMIN dahil: yalnız taslak onaya gönderilir
        r.setStatus("PENDING_APPROVAL");
        r.setVersion(r.getVersion() + 1);
        clearLock(r); // düzenleme bitti
        r.setSubmittedBy(actor.display());
        r.setSubmittedAt(now());
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        reportRepo.save(r);

        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";

        List<String> poEmails = resolvePoEmails(r.getTeamId());
        String poMail;
        if (poEmails.isEmpty()) {
            poMail = "SKIPPED_NO_CONTACT";
            log.warn("Haftalık rapor onaya gönderildi ama PO kontağı yok: team={} report={}", teamName, id);
        } else {
            String html = emailService.buildWeeklyReportSubmittedHtml(teamName, r.getWeekLabel(), actor.display());
            poMail = emailService.sendHtml(poEmails.toArray(new String[0]), null,
                    "[CertMonitor] " + teamName + " — " + r.getWeekLabel() + " raporu onayınızı bekliyor",
                    html, null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r);
        out.put("po_mail", poMail);
        return out;
    }

    /** PENDING → APPROVED; müdüre tam rapor maili (To=MANAGER, CC=Team.email). */
    public Map<String, Object> approve(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanApprove(r, actor);
        requireStatus(r, "PENDING_APPROVAL");

        List<EscalationContact> managers = contactRepo
                .findByTeamIdAndRoleAndActiveTrue(r.getTeamId(), "MANAGER").stream()
                .filter(c -> c.getEmail() != null && !c.getEmail().isBlank())
                .toList();
        if (managers.isEmpty()) {
            throw new IllegalStateException("MANAGER_CONTACT_MISSING");
        }

        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";
        String teamEmail = team != null && team.getEmail() != null && !team.getEmail().isBlank()
                ? team.getEmail().trim() : null;

        String[] to = managers.stream().map(c -> c.getEmail().trim()).toArray(String[]::new);
        String[] cc = teamEmail != null ? new String[]{teamEmail} : null;
        String managerName = managers.get(0).getName();

        List<EmailNotificationService.InlineImage> inline = collectInlineImages(r);
        String html = emailService.buildWeeklyReportHtml(
                teamName, r.getWeekLabel(), managerName, r.getContentJson(), true,
                imageDisplayWidths(inline));

        String mailStatus = emailService.sendHtml(to, cc,
                "[" + teamName + "] Haftalık Rapor — " + r.getWeekLabel(),
                html, inline);

        r.setStatus("APPROVED");
        r.setVersion(r.getVersion() + 1);
        clearLock(r);
        r.setApprovedBy(actor.display());
        r.setApprovedAt(now());
        r.setRejectNote(null);
        if ("SENT".equals(mailStatus) || (mailStatus != null && mailStatus.startsWith("QUEUED_RETRY"))) {
            r.setSentAt(now());
        }
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        reportRepo.save(r);

        log.info("Haftalık rapor onaylandı ve gönderildi: team={} week={} TO=[{}] CC=[{}] status={}",
                teamName, r.getWeekLabel(), String.join(", ", to),
                teamEmail != null ? teamEmail : "-", mailStatus);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r);
        out.put("mail_status", mailStatus);
        return out;
    }

    /** PENDING → REJECTED; düzeltme notu Team.email'e mail ile gider. */
    public WeeklyReport reject(Long id, String note, Actor actor) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("İade notu zorunludur");
        }
        WeeklyReport r = get(id, actor);
        requireCanApprove(r, actor);
        requireStatus(r, "PENDING_APPROVAL");
        r.setStatus("REJECTED");
        r.setVersion(r.getVersion() + 1);
        r.setRejectNote(note.trim());
        r.setRejectedBy(actor.display());
        r.setRejectedAt(now());
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        reportRepo.save(r);

        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";
        String teamEmail = team != null && team.getEmail() != null && !team.getEmail().isBlank()
                ? team.getEmail().trim() : null;
        if (teamEmail != null) {
            String html = emailService.buildWeeklyReportRejectedHtml(
                    teamName, r.getWeekLabel(), note.trim(), actor.display());
            emailService.sendHtml(new String[]{teamEmail}, null,
                    "[CertMonitor] " + teamName + " — " + r.getWeekLabel() + " raporu iade edildi",
                    html, null);
        } else {
            log.warn("İade maili atlanıyor — takım email'i yok: team={} report={}", teamName, id);
        }
        return r;
    }

    /** UI mail önizlemesi — görsel URL'leri /api olarak kalır. */
    public String buildPreviewHtml(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";
        String managerName = contactRepo.findByTeamIdAndRoleAndActiveTrue(r.getTeamId(), "MANAGER").stream()
                .findFirst().map(EscalationContact::getName).orElse(null);
        return emailService.buildWeeklyReportHtml(teamName, r.getWeekLabel(), managerName,
                r.getContentJson(), false);
    }

    // ── Görseller ─────────────────────────────────────────────────────────────

    public WeeklyReportImage storeImage(Long reportId, String caption, MultipartFile file, Actor actor) {
        WeeklyReport r = get(reportId, actor);
        requireCanModify(r, actor);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Dosya boş");
        if (file.getSize() > imageMaxBytes) {
            throw new IllegalArgumentException("Görsel çok büyük (limit " + (imageMaxBytes / 1024 / 1024) + "MB)");
        }
        String ct = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_IMAGE_TYPES.contains(ct)) {
            throw new IllegalArgumentException("Desteklenmeyen görsel tipi: " + ct);
        }
        try {
            WeeklyReportImage img = new WeeklyReportImage();
            img.setReportId(reportId);
            img.setCaption(caption != null ? caption.trim() : null);
            img.setContentType(ct);
            img.setSizeBytes(file.getSize());
            img.setData(file.getBytes());
            img.setCreatedBy(actor.display());
            img.setCreatedAt(now());
            return imageRepo.save(img);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Dosya okunamadı: " + e.getMessage());
        }
    }

    public WeeklyReportImage getImage(Long imageId, Actor actor) {
        WeeklyReportImage img = imageRepo.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Image not found: " + imageId));
        WeeklyReport r = reportRepo.findById(img.getReportId())
                .orElseThrow(() -> new NoSuchElementException("Report not found for image: " + imageId));
        requireCanRead(r, actor);
        return img;
    }

    public void deleteImage(Long imageId, Actor actor) {
        WeeklyReportImage img = imageRepo.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Image not found: " + imageId));
        WeeklyReport r = reportRepo.findById(img.getReportId())
                .orElseThrow(() -> new NoSuchElementException("Report not found for image: " + imageId));
        requireCanModify(r, actor);
        imageRepo.delete(img);
    }

    /** Raporu görselleriyle birlikte siler. Silme yetkisi = düzenleme yetkisi:
     *  ADMIN her raporu; USER/TEAM_ADMIN kendi takımının pencere içi
     *  DRAFT/REJECTED raporunu siler. Silinen rapor audit için döner. */
    @Transactional
    public WeeklyReport delete(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanModify(r, actor);
        imageRepo.deleteByReportId(id);
        reportRepo.delete(r);
        log.info("Haftalık rapor silindi: id={} team={} week={} by={}",
                id, r.getTeamId(), r.getWeekLabel(), actor.display());
        return r;
    }

    /** Mail görselleri için gösterim genişliği: min(560, doğal genişlik).
     *  Outlook width attribute'a uyar — taşma bu değerle engellenir; format
     *  okunamazsa (ör. webp) girilmez, builder 560 fallback kullanır. */
    private Map<Long, Integer> imageDisplayWidths(List<EmailNotificationService.InlineImage> images) {
        Map<Long, Integer> widths = new LinkedHashMap<>();
        for (EmailNotificationService.InlineImage img : images) {
            try {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img.data()));
                if (bi != null) {
                    widths.put(Long.parseLong(img.cid().substring("img".length())),
                            Math.min(560, bi.getWidth()));
                }
            } catch (Exception e) {
                log.debug("Görsel boyutu okunamadı: cid={} err={}", img.cid(), e.getMessage());
            }
        }
        return widths;
    }

    /** Onay mailindeki cid:img{id} referansları için raporun markdown'ında
     *  GERÇEKTEN kullanılan görselleri toplar. */
    private List<EmailNotificationService.InlineImage> collectInlineImages(WeeklyReport r) {
        Set<Long> referenced = new LinkedHashSet<>();
        Matcher m = IMAGE_REF.matcher(r.getContentJson() != null ? r.getContentJson() : "");
        while (m.find()) referenced.add(Long.parseLong(m.group(1)));
        List<EmailNotificationService.InlineImage> result = new ArrayList<>();
        for (WeeklyReportImage img : imageRepo.findByReportIdOrderByIdAsc(r.getId())) {
            if (referenced.contains(img.getId())) {
                result.add(new EmailNotificationService.InlineImage(
                        "img" + img.getId(), img.getData(), img.getContentType()));
            }
        }
        return result;
    }

    // ── Yetkiler / doğrulama ─────────────────────────────────────────────────

    private void requireCanRead(WeeklyReport r, Actor a) {
        if (a.isAdmin() || a.isAudit()) return;
        if (!Objects.equals(a.teamId(), r.getTeamId())) {
            throw new SecurityException("Bu rapora erişim yetkiniz yok");
        }
    }

    /** Düzenleme/silme yetkisi: ADMIN sınırsız (durum + hafta); diğerleri
     *  kendi takımının DRAFT/REJECTED raporunu yalnız mevcut + önceki ISO
     *  haftasında değiştirebilir. */
    private void requireCanModify(WeeklyReport r, Actor a) {
        if (a.isAdmin()) return;
        if (a.isAudit()) throw new SecurityException("AUDIT rolü rapor düzenleyemez");
        if (!Objects.equals(a.teamId(), r.getTeamId())) {
            throw new SecurityException("Başka takımın raporu düzenlenemez");
        }
        if (!inEditWindow(r.getReportYear(), r.getWeekNo())) {
            throw new SecurityException(
                    "Yalnızca içinde bulunulan ve bir önceki haftanın raporları düzenlenebilir");
        }
        requireStatus(r, "DRAFT", "REJECTED");
    }

    /** Rapor haftası, içinde bulunulan veya bir önceki ISO haftası mı?
     *  weekBasedYear kullanılır — yıl sınırında (1 Ocak / 53. hafta) doğru çalışır. */
    static boolean inEditWindow(int reportYear, int weekNo) {
        LocalDate today = LocalDate.now();
        for (LocalDate d : List.of(today, today.minusWeeks(1))) {
            if (d.get(WeekFields.ISO.weekBasedYear()) == reportYear
                    && d.get(WeekFields.ISO.weekOfWeekBasedYear()) == weekNo) {
                return true;
            }
        }
        return false;
    }

    private void requireCanApprove(WeeklyReport r, Actor a) {
        if (a.isAdmin()) return;
        if (a.isTeamAdmin() && Objects.equals(a.teamId(), r.getTeamId())) return;
        // PO: orgRole DB'den okunur (session'da yok)
        if (a.userId() != null && Objects.equals(a.teamId(), r.getTeamId())) {
            Optional<AppUser> u = userRepo.findById(a.userId());
            if (u.isPresent() && "PO".equals(u.get().getOrgRole())) return;
        }
        throw new SecurityException("Onay yetkisi yok — takımın PO'su, TEAM_ADMIN'i veya ADMIN gerekir");
    }

    private void requireStatus(WeeklyReport r, String... allowed) {
        for (String s : allowed) {
            if (s.equals(r.getStatus())) return;
        }
        throw new IllegalStateException("Geçersiz durum: " + r.getStatus()
                + " (beklenen: " + String.join("/", allowed) + ")");
    }

    private List<String> resolvePoEmails(Long teamId) {
        List<String> emails = contactRepo.findByTeamIdAndRoleAndActiveTrue(teamId, "PO").stream()
                .map(EscalationContact::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .toList();
        if (!emails.isEmpty()) return emails;
        // Fallback: orgRole=PO kullanıcıların email'leri
        return userRepo.findByTeamIdAndOrgRoleAndActiveTrue(teamId, "PO").stream()
                .map(AppUser::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .toList();
    }

    String validateAndNormalizeContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new IllegalArgumentException("İçerik boş olamaz");
        }
        if (contentJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("İçerik çok büyük (limit 200KB) — görselleri yükleme "
                    + "butonuyla ekleyin, base64 gömmeyin");
        }
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            // Madde 1 Toplam türetilir — istemci ne gönderirse göndersin sunucu yeniden hesaplar
            if (root.path("item1").isObject()) {
                ObjectNode i1 = (ObjectNode) root.path("item1");
                int total = 0;
                for (String k : List.of("urgent", "high", "medium", "low")) {
                    total += i1.path(k).asInt(0);
                }
                i1.put("total", total);
            }
            JsonNode channels = root.path("item4").path("channels");
            if (channels.isArray()) {
                if (channels.size() > MAX_CHANNELS) {
                    throw new IllegalArgumentException("En fazla " + MAX_CHANNELS + " kanal eklenebilir");
                }
                for (JsonNode ch : channels) {
                    if (ch.path("name").asText("").isBlank()) {
                        throw new IllegalArgumentException("Kanal adı boş olamaz");
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Geçersiz içerik JSON'ı: " + e.getMessage());
        }
    }

    /** ISO hafta etiketi: "2026-W24 (8–12 Haziran 2026)" — Pzt–Cum aralığı. */
    static String computeWeekLabel(int year, int weekNo) {
        LocalDate monday = LocalDate.of(year, 1, 4)
                .with(WeekFields.ISO.weekOfWeekBasedYear(), weekNo)
                .with(DayOfWeek.MONDAY);
        LocalDate friday = monday.plusDays(4);
        String[] months = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
                           "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"};
        String range = monday.getMonthValue() == friday.getMonthValue()
                ? monday.getDayOfMonth() + "–" + friday.getDayOfMonth() + " "
                  + months[friday.getMonthValue() - 1] + " " + friday.getYear()
                : monday.getDayOfMonth() + " " + months[monday.getMonthValue() - 1] + " – "
                  + friday.getDayOfMonth() + " " + months[friday.getMonthValue() - 1] + " " + friday.getYear();
        return year + "-W" + (weekNo < 10 ? "0" + weekNo : weekNo) + " (" + range + ")";
    }

    /** Şablon kopyalama: kanal id/ad + takip URL'leri korunur; sayılar
     *  sıfırlanır, notes_md/status_text boşalır. Bozuk JSON → default şablon. */
    static String resetTemplate(String prevContentJson) {
        ObjectMapper om = new ObjectMapper();
        try {
            ObjectNode root = (ObjectNode) om.readTree(prevContentJson);
            ObjectNode i1 = root.withObject("item1");
            for (String k : List.of("total", "urgent", "high", "medium", "low")) i1.put(k, 0);
            i1.put("status_text", "Çalışılıyor"); // combobox varsayılanı
            i1.put("notes_md", "");
            ObjectNode i2 = root.withObject("item2");
            for (String k : List.of("open_incidents", "problem_records", "postmortems")) i2.put(k, 0);
            i2.put("notes_md", "");
            root.withObject("item3").put("notes_md", "");
            JsonNode channels = root.path("item4").path("channels");
            if (channels.isArray()) {
                for (JsonNode ch : channels) {
                    if (ch instanceof ObjectNode chObj) chObj.put("notes_md", "");
                }
            }
            root.put("version", 1);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            return DEFAULT_TEMPLATE_JSON;
        }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
