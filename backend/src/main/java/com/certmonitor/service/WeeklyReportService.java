package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.Team;
import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportImage;
import com.certmonitor.model.WeeklyReportMail;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.repository.WeeklyReportImageRepository;
import com.certmonitor.repository.WeeklyReportMailRepository;
import com.certmonitor.repository.WeeklyReportRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
    private final WeeklyReportMailRepository mailRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final EscalationContactRepository contactRepo;
    private final EmailNotificationService emailService;
    private final ObjectMapper objectMapper;
    private final AppSettingsService appSettings;

    /** static resetTemplate için paylaşılan, thread-safe mapper — her çağrıda
     *  yeni ObjectMapper kurma maliyetini önler (Jackson 3 mapper'ları yeniden
     *  kullanım için tasarlıdır). */
    private static final ObjectMapper TEMPLATE_MAPPER = new ObjectMapper();

    @Value("${cert.monitor.weekly-report.image-max-bytes:2097152}")
    private long imageMaxBytes;

    /** E-posta onay linkleri için uygulamanın dış URL'i (reminder ile aynı ayar). */
    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Onay token'ı geçerlilik süresi (gün). */
    private static final long APPROVAL_TOKEN_TTL_DAYS = 7;
    private static final java.security.SecureRandom TOKEN_RNG = new java.security.SecureRandom();

    private String newApprovalToken() {
        byte[] b = new byte[24];
        TOKEN_RNG.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String approveUrl(String token) {
        // Canlı okunur (Genel Ayarlar'dan değişebilir); @Value yalnız fallback varsayılan.
        String url = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        return base + "/api/weekly-reports/approve-link?token=" + token;
    }

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

    /** Meta-only liste (byte[] data YÜKLENMEZ) — heap baskısını önler. */
    public List<com.certmonitor.repository.WeeklyReportImageMetaView> imagesMeta(Long reportId) {
        return imageRepo.findProjectedByReportIdOrderByIdAsc(reportId);
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

    // ── Takım transferi (toplu / tekil) ──────────────────────────────────────

    /**
     * Seçilen raporların sahibi takımı {@code targetTeamId}'ye taşır (YALNIZ ADMIN).
     * Hedef takımda aynı (yıl, hafta) raporu zaten varsa o rapor UNIQUE çakışması
     * nedeniyle atlanır; aynı takıma transfer ve bulunamayan id'ler de atlanır.
     * Raporun görsellerinin takım izolasyonu da güncellenir.
     * Sonuç: {@code {transferred:int, skipped:[{id, week_label, year, week_no, reason}]}}.
     */
    @Transactional
    public Map<String, Object> transfer(List<Long> ids, Long targetTeamId, Actor actor) {
        if (!actor.isAdmin()) throw new SecurityException("Transfer yetkisi yok — ADMIN gerekir");
        if (targetTeamId == null) throw new IllegalArgumentException("Hedef takım seçilmeli");
        if (!teamRepo.existsById(targetTeamId)) throw new IllegalArgumentException("Hedef takım bulunamadı: " + targetTeamId);
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Aktarılacak rapor seçilmeli");

        String now = now();
        int transferred = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : ids) {
            WeeklyReport r = reportRepo.findById(id).orElse(null);
            if (r == null) { skipped.add(skipEntry(id, null, "not_found")); continue; }
            if (Objects.equals(r.getTeamId(), targetTeamId)) { skipped.add(skipEntry(id, r, "same_team")); continue; }
            if (reportRepo.findByTeamIdAndReportYearAndWeekNo(targetTeamId, r.getReportYear(), r.getWeekNo()).isPresent()) {
                skipped.add(skipEntry(id, r, "conflict"));
                continue;
            }
            r.setTeamId(targetTeamId);
            r.setUpdatedBy(actor.display());
            r.setUpdatedAt(now);
            reportRepo.save(r);
            // Görsellerin takım izolasyonunu da hedef takıma taşı
            imageRepo.findByReportIdOrderByIdAsc(id).forEach(img -> {
                img.setTeamId(targetTeamId);
                imageRepo.save(img);
            });
            transferred++;
            log.info("Weekly report transferred id={} → team={} by={}", id, targetTeamId, actor.display());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transferred", transferred);
        out.put("skipped", skipped);
        return out;
    }

    private Map<String, Object> skipEntry(Long id, WeeklyReport r, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("week_label", r != null ? r.getWeekLabel() : null);
        m.put("year", r != null ? r.getReportYear() : null);
        m.put("week_no", r != null ? r.getWeekNo() : null);
        m.put("reason", reason);
        return m;
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

    /** Logout / oturum sonu: kullanıcının tuttuğu tüm rapor düzenleme kilitlerini bırakır.
     *  Böylece kullanıcı çıkınca başkaları "X düzenliyor" ipucunu görmeye devam etmez. */
    @Transactional
    public void releaseLocksForUser(Long userId) {
        if (userId == null) return;
        int n = reportRepo.clearLocksByUser(userId);
        if (n > 0) log.info("Oturum sonu: {} haftalık rapor düzenleme kilidi serbest bırakıldı (userId={})", n, userId);
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
        // E-posta ile hızlı onay token'ı (tek-kullanımlık, süreli)
        String token = newApprovalToken();
        r.setApprovalToken(token);
        r.setApprovalTokenExpiresAt(ISO.format(Instant.now().plus(java.time.Duration.ofDays(APPROVAL_TOKEN_TTL_DAYS))));
        reportRepo.save(r);

        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";

        List<String> poEmails = resolvePoEmails(r.getTeamId());
        String poSubject = "[CertMonitor] " + teamName + " — " + r.getWeekLabel() + " raporu onayınızı bekliyor";
        // PO mailine raporun TAMAMI gömülür + "Onayla" CTA'sı: PO maili açıp raporu görür
        // ve maildeki linkten (login'siz) onaylayabilir. Görseller inline gider.
        List<EmailNotificationService.InlineImage> inline = collectInlineImages(r);
        String poHtml = emailService.buildWeeklyReportHtml(
                teamName, r.getWeekLabel(), resolvePoDisplayName(r.getTeamId()),
                r.getContentJson(), true, imageDisplayWidths(inline),
                null, null, null, approveUrl(token));
        String poMail;
        if (poEmails.isEmpty()) {
            poMail = "SKIPPED_NO_CONTACT";
            log.warn("Haftalık rapor onaya gönderildi ama PO kontağı yok: team={} report={}", teamName, id);
        } else {
            poMail = emailService.sendHtml(poEmails.toArray(new String[0]), null, poSubject, poHtml, inline);
        }
        recordMail(r, "SUBMIT_PO", poEmails, null, poSubject, poHtml, poMail, actor);
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

        String mailStatus = sendApprovalMail(r, actor); // MANAGER yoksa 409 fırlatır

        r.setStatus("APPROVED");
        r.setVersion(r.getVersion() + 1);
        clearLock(r);
        clearApprovalToken(r);
        r.setApprovedBy(actor.display());
        r.setApprovedAt(now());
        r.setRejectNote(null);
        if (mailSent(mailStatus)) r.setSentAt(now());
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        reportRepo.save(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r);
        out.put("mail_status", mailStatus);
        return out;
    }

    private void clearApprovalToken(WeeklyReport r) {
        r.setApprovalToken(null);
        r.setApprovalTokenExpiresAt(null);
    }

    // ── E-posta ile hızlı onay (token, login'siz) ────────────────────────────

    /** Token durumunu döner (GET onay sayfası için): valid + rapor özeti. Mutasyon yok. */
    public Map<String, Object> approvalTokenStatus(String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        WeeklyReport r = (token == null || token.isBlank())
                ? null : reportRepo.findByApprovalToken(token.trim()).orElse(null);
        if (r == null) {
            out.put("valid", false);
            out.put("reason", "not_found");
            return out;
        }
        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        out.put("team_name", team != null ? team.getName() : "—");
        out.put("week_label", r.getWeekLabel());
        out.put("status", r.getStatus());
        if (tokenExpired(r)) { out.put("valid", false); out.put("reason", "expired"); return out; }
        if (!"PENDING_APPROVAL".equals(r.getStatus())) {
            out.put("valid", false);
            out.put("reason", "APPROVED".equals(r.getStatus()) ? "already_approved" : "not_pending");
            return out;
        }
        out.put("valid", true);
        return out;
    }

    /** Token ile onay (login'siz). Geçerli + süresi geçmemiş + PENDING_APPROVAL şart.
     *  Onaylayan, takımın PO'su olarak kaydedilir. Tek-kullanımlık: onaydan sonra token silinir. */
    @Transactional
    public Map<String, Object> approveViaToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Geçersiz onay bağlantısı");
        WeeklyReport r = reportRepo.findByApprovalToken(token.trim())
                .orElseThrow(() -> new IllegalArgumentException("Onay bağlantısı geçersiz veya kullanılmış"));
        if (tokenExpired(r)) throw new IllegalStateException("Onay bağlantısının süresi dolmuş");
        if (!"PENDING_APPROVAL".equals(r.getStatus())) {
            throw new IllegalStateException("APPROVED".equals(r.getStatus())
                    ? "Bu rapor zaten onaylanmış" : "Rapor onay bekleme durumunda değil");
        }
        String approver = resolvePoDisplayName(r.getTeamId());
        Actor actor = new Actor(null, "email-approval", approver, r.getTeamId(), "ADMIN");
        String mailStatus = sendApprovalMail(r, actor); // MANAGER yoksa 409 fırlatır

        r.setStatus("APPROVED");
        r.setVersion(r.getVersion() + 1);
        clearLock(r);
        clearApprovalToken(r);
        r.setApprovedBy(approver);
        r.setApprovedAt(now());
        r.setRejectNote(null);
        if (mailSent(mailStatus)) r.setSentAt(now());
        r.setUpdatedBy(approver);
        r.setUpdatedAt(now());
        reportRepo.save(r);
        log.info("Haftalık rapor e-posta linki ile onaylandı: id={} team={} approver={} mail={}",
                r.getId(), r.getTeamId(), approver, mailStatus);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r);
        out.put("mail_status", mailStatus);
        return out;
    }

    private boolean tokenExpired(WeeklyReport r) {
        if (r.getApprovalTokenExpiresAt() == null) return true;
        try {
            return Instant.from(ISO.parse(r.getApprovalTokenExpiresAt())).isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    /** Takımın AD müdür(leri): üyelerin bağlı olduğu distinct manager_id → aktif,
     *  e-postası olan AppUser'lar. MANAGER escalation contact yoksa onay maili için
     *  alıcı olarak kullanılır. */
    private List<AppUser> resolveTeamManagerUsers(Long teamId) {
        java.util.LinkedHashSet<Long> mgrIds = new java.util.LinkedHashSet<>();
        for (AppUser u : userRepo.findByTeamIdOrderByUsernameAsc(teamId)) {
            if (Boolean.TRUE.equals(u.getActive()) && u.getManagerId() != null) {
                mgrIds.add(u.getManagerId());
            }
        }
        List<AppUser> out = new ArrayList<>();
        for (Long id : mgrIds) {
            userRepo.findById(id)
                    .filter(m -> Boolean.TRUE.equals(m.getActive()))
                    .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                    .ifPresent(out::add);
        }
        return out;
    }

    /** Onaylayan etiketi: takımın aktif PO'sunun adı; yoksa genel etiket. */
    private String resolvePoDisplayName(Long teamId) {
        return userRepo.findByTeamIdAndOrgRoleAndActiveTrue(teamId, "PO").stream()
                .findFirst()
                .map(u -> {
                    String dn = u.getDisplayName();
                    return (dn != null && !dn.isBlank()) ? dn : u.getUsername();
                })
                .orElse("PO (e-posta onayı)");
    }

    /** Onaylanmış raporu (değiştirmeden, yeniden onaysız) müdüre TEKRAR gönderir
     *  — "mail ulaşmadı/sorun oldu" senaryosu. Yetki = onay yetkisi. */
    public Map<String, Object> resend(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireCanApprove(r, actor);
        requireStatus(r, "APPROVED");

        String mailStatus = sendApprovalMail(r, actor);
        r.setVersion(r.getVersion() + 1);
        if (mailSent(mailStatus)) r.setSentAt(now());
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        reportRepo.save(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r);
        out.put("mail_status", mailStatus);
        return out;
    }

    /** Onaylı raporu yeniden düzenlenebilir hale getirir (APPROVED → DRAFT).
     *  ADMIN her hafta; non-admin kendi takımı + düzenleme penceresi.
     *  Onay/gönderim alanları sıfırlanır (taze döngü); geçmiş mailler korunur. */
    public WeeklyReport reopen(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        requireStatus(r, "APPROVED");
        if (actor.isAudit()) throw new SecurityException("AUDIT rolü rapor düzenleyemez");
        if (!actor.isAdmin()) {
            if (!Objects.equals(actor.teamId(), r.getTeamId())) {
                throw new SecurityException("Başka takımın raporu revize edilemez");
            }
            if (!inEditWindow(r.getReportYear(), r.getWeekNo())) {
                throw new SecurityException(
                        "Yalnızca içinde bulunulan ve bir önceki haftanın raporları revize edilebilir");
            }
        }
        r.setStatus("DRAFT");
        r.setVersion(r.getVersion() + 1);
        r.setSentAt(null);
        r.setApprovedBy(null);
        r.setApprovedAt(null);
        r.setUpdatedBy(actor.display());
        r.setUpdatedAt(now());
        log.info("Haftalık rapor revizyona açıldı (APPROVED→DRAFT): id={} team={} by={}",
                id, r.getTeamId(), actor.display());
        return reportRepo.save(r);
    }

    private static boolean mailSent(String s) {
        return "SENT".equals(s) || (s != null && s.startsWith("QUEUED_RETRY"));
    }

    /** Müdüre rapor maili gönderir + kaydeder; mailStatus döner. MANAGER kontağı
     *  yoksa MANAGER_CONTACT_MISSING (409). approve ve resend ortak kullanır. */
    private String sendApprovalMail(WeeklyReport r, Actor actor) {
        List<EscalationContact> managers = contactRepo
                .findByTeamIdAndRoleAndActiveTrue(r.getTeamId(), "MANAGER").stream()
                .filter(c -> c.getEmail() != null && !c.getEmail().isBlank())
                .toList();
        String[] to;
        String managerName;
        if (!managers.isEmpty()) {
            to = managers.stream().map(c -> c.getEmail().trim()).toArray(String[]::new);
            managerName = managers.get(0).getName();
        } else {
            // MANAGER escalation contact yoksa AD müdürüne düş: takım üyelerinin
            // bağlı olduğu müdür(ler)in (manager_id → AppUser) e-postaları.
            List<AppUser> adManagers = resolveTeamManagerUsers(r.getTeamId());
            if (adManagers.isEmpty()) {
                throw new IllegalStateException("MANAGER_CONTACT_MISSING");
            }
            to = adManagers.stream().map(m -> m.getEmail().trim()).toArray(String[]::new);
            AppUser first = adManagers.get(0);
            managerName = (first.getDisplayName() != null && !first.getDisplayName().isBlank())
                    ? first.getDisplayName() : first.getUsername();
        }
        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";
        String teamEmail = team != null && team.getEmail() != null && !team.getEmail().isBlank()
                ? team.getEmail().trim() : null;
        String[] cc = teamEmail != null ? new String[]{teamEmail} : null;

        List<EmailNotificationService.InlineImage> inline = collectInlineImages(r);
        // Footer onay bilgisi: ilk onayda alanlar henüz set değil → actor/şimdi;
        // tekrar gönderimde orijinal onaylayan + yeni gönderim zamanı.
        String approver   = r.getApprovedBy() != null ? r.getApprovedBy() : actor.display();
        String approvedAt = r.getApprovedAt() != null ? r.getApprovedAt() : now();
        String html = emailService.buildWeeklyReportHtml(
                teamName, r.getWeekLabel(), managerName, r.getContentJson(), true,
                imageDisplayWidths(inline), approver, approvedAt, now());

        String subject = "[" + teamName + "] Haftalık Rapor — " + r.getWeekLabel();
        String mailStatus = emailService.sendHtml(to, cc, subject, html, inline);
        recordMail(r, "APPROVE_MANAGER", List.of(to),
                cc != null ? List.of(cc) : null, subject, html, mailStatus, actor);

        log.info("Haftalık rapor müdüre gönderildi: team={} week={} TO=[{}] CC=[{}] status={}",
                teamName, r.getWeekLabel(), String.join(", ", to),
                teamEmail != null ? teamEmail : "-", mailStatus);
        return mailStatus;
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
        clearApprovalToken(r);
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
        String rjSubject = "[CertMonitor] " + teamName + " — " + r.getWeekLabel() + " raporu iade edildi";
        String rjHtml = emailService.buildWeeklyReportRejectedHtml(
                teamName, r.getWeekLabel(), note.trim(), actor.display());
        String rjStatus;
        if (teamEmail != null) {
            rjStatus = emailService.sendHtml(new String[]{teamEmail}, null, rjSubject, rjHtml, null);
        } else {
            rjStatus = "SKIPPED_NO_CONTACT";
            log.warn("İade maili atlanıyor — takım email'i yok: team={} report={}", teamName, id);
        }
        recordMail(r, "REJECT_TEAM", teamEmail != null ? List.of(teamEmail) : List.of(),
                null, rjSubject, rjHtml, rjStatus, actor);
        return r;
    }

    /** UI mail önizlemesi — görsel URL'leri /api olarak kalır. */
    public String buildPreviewHtml(Long id, Actor actor) {
        WeeklyReport r = get(id, actor);
        Team team = teamRepo.findById(r.getTeamId()).orElse(null);
        String teamName = team != null ? team.getName() : "Takım";
        String managerName = contactRepo.findByTeamIdAndRoleAndActiveTrue(r.getTeamId(), "MANAGER").stream()
                .findFirst().map(EscalationContact::getName).orElse(null);
        // Önizlemede de footer onay bilgisi gösterilir (onaylı raporda maille aynı);
        // DRAFT'ta alanlar null → ilgili satırlar gizlenir.
        return emailService.buildWeeklyReportHtml(teamName, r.getWeekLabel(), managerName,
                r.getContentJson(), false, null,
                r.getApprovedBy(), r.getApprovedAt(), r.getSentAt());
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
            img.setTeamId(r.getTeamId()); // açık takım izolasyonu
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
        mailRepo.deleteByReportId(id);
        reportRepo.delete(r);
        log.info("Haftalık rapor silindi: id={} team={} week={} by={}",
                id, r.getTeamId(), r.getWeekLabel(), actor.display());
        return r;
    }

    // ── Mail gönderim geçmişi ────────────────────────────────────────────────

    /** Her gönderim DENEMESİNİ kaydeder — kayıt hatası mail akışını bozmaz. */
    private void recordMail(WeeklyReport r, String type, List<String> to, List<String> cc,
                            String subject, String html, String status, Actor actor) {
        try {
            WeeklyReportMail m = new WeeklyReportMail();
            m.setReportId(r.getId());
            m.setMailType(type);
            m.setFromAddress(emailService.fromAddress());
            m.setToAddresses(to != null ? String.join(", ", to) : "");
            m.setCcAddresses(cc != null && !cc.isEmpty() ? String.join(", ", cc) : null);
            m.setSubject(subject);
            m.setBodyHtml(html);
            m.setStatus(status != null ? status : "UNKNOWN");
            m.setCreatedBy(actor.display());
            m.setCreatedAt(now());
            mailRepo.save(m);
        } catch (Exception e) {
            log.warn("Mail gönderim kaydı yazılamadı: report={} type={} err={}",
                    r.getId(), type, e.getMessage());
        }
    }

    /** Raporun gönderim geçmişi (yeni → eski). Body'deki cid referansları UI
     *  iframe'inin görselleri oturumla yükleyebilmesi için /api'ye çevrilir. */
    public List<WeeklyReportMail> mails(Long reportId, Actor actor) {
        get(reportId, actor); // okuma yetkisi kontrolü
        List<WeeklyReportMail> list = mailRepo.findByReportIdOrderByIdDesc(reportId);
        for (WeeklyReportMail m : list) {
            if (m.getBodyHtml() != null) {
                m.setBodyHtml(m.getBodyHtml()
                        .replaceAll("cid:img(\\d+)", "/api/weekly-reports/images/$1"));
            }
        }
        return list;
    }

    /** Liste rozetleri: rapor başına EN SON gönderim kaydının status'u. */
    public Map<Long, String> lastMailStatuses(List<Long> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) return Map.of();
        Map<Long, WeeklyReportMail> latest = new HashMap<>();
        for (WeeklyReportMail m : mailRepo.findByReportIdIn(reportIds)) {
            WeeklyReportMail cur = latest.get(m.getReportId());
            if (cur == null || m.getId() > cur.getId()) latest.put(m.getReportId(), m);
        }
        Map<Long, String> out = new HashMap<>();
        latest.forEach((reportId, m) -> out.put(reportId, m.getStatus()));
        return out;
    }

    /** Mail görsellerinin DOĞAL genişliği (cid id → px). Bölüm sınırı (taşma
     *  kontrolü) builder'da konuma göre uygulanır; format okunamazsa (ör. webp)
     *  girilmez, builder bölüm tavanını fallback kullanır. */
    private Map<Long, Integer> imageDisplayWidths(List<EmailNotificationService.InlineImage> images) {
        Map<Long, Integer> widths = new LinkedHashMap<>();
        for (EmailNotificationService.InlineImage img : images) {
            try {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img.data()));
                if (bi != null) {
                    widths.put(Long.parseLong(img.cid().substring("img".length())), bi.getWidth());
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
    public static String computeWeekLabel(int year, int weekNo) {
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
        ObjectMapper om = TEMPLATE_MAPPER;
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
