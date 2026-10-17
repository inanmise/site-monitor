package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorApprovalToken;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorApprovalTokenRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TechErrorApprovalMail;
import com.sitemonitor.service.mail.TechErrorMailImages;
import com.sitemonitor.service.mail.TechErrorReportMail;
import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Kanal teknik hata raporu PO onayı (2026-10-17, kullanıcı kararı S6: "onaylayıcı = ekipteki org rolü PO olanlar — biri
 * yeter; PO onaylayınca rapor OTOMATİK executive alıcılara gider; ekip 'Onaya gönder' → PO e-posta bağlantısından ya da
 * Site Monitor'den onaylar; red → not + taslağa döner; onaydan sonra değişirse yeniden onay").
 *
 * <p>Onay akışı yalnız yönetici (executive) alıcısı tanımlı kanallarda vardır ({@link #approvalRequired}); alıcısız
 * kanal eski akışı (takım adresine elle gönderim) byte-identical sürdürür. Durum raporun kendi satırında
 * ({@code approval_state} / {@code approval_rev}: istek içerik sayacına bağlanır — {@code content_rev} geçerse istek ya
 * da onay ESKİMİŞTİR). Her geçiş koşullu JPQL (at-most-once; iki sekme / iki pod aynı kararı iki kez yazamaz).
 * Bağlantı belirteçleri onaylayıcı başına, özetli, 14 gün, tek kullanımlık ({@code tech_error_approval_tokens}).
 * Onay mektubu: raporun tam gövdesi + PDF eki + onay / red bağlantısı; karar mektubu isteyene. Her posta kararı bir
 * {@code notification_logs} satırı bırakır (tetikleyici {@link #TRIGGER}). Onaylanan rapor aynı gönderim makinesiyle
 * ({@link TechErrorReportSender}, {@code Delivery.EXECUTIVE}: alıcılar = yönetici listesi, bilgi = takım adresi) gider;
 * gönderim başarısız olsa da onay kalır — ekip "Yöneticilere gönder" ile yeniden dener.
 */
@Slf4j
@Service
public class TechErrorApprovalService {

    public static final String TRIGGER = "TECH_ERROR_APPROVAL";
    public static final String APPROVER_ROLE = "PO";
    public static final Duration TOKEN_TTL = Duration.ofDays(14);
    /** Bekleyen istek bu kadar eskiyse aynı içerik için yeniden istenebilir (hatırlatma; yarıda kalan istek kurtarılır). */
    public static final Duration RESEND_AFTER = Duration.ofMinutes(10);
    public static final int MAX_NOTE = 1000;
    public static final String KIND_REQUEST = "APPROVAL_REQUEST";
    public static final String KIND_DECISION = "APPROVAL";
    /**
     * Yetki matrisindeki onay izni (A1, inceleme 2026-10-17): uygulama içi karar {@code tech_error_reports.approve/execute}
     * ister; e-posta bağlantısı da AYNI izni ister — rolü için izin kapatılan PO bağlantıdan da onaylayamaz (sayfa sebebini
     * söyler, belirteç tüketilmez). {@code TechErrorReportController.APPROVE} ile aynı anahtar.
     */
    public static final String APPROVE_PERMISSION = "tech_error_reports.approve";
    /**
     * Okuma izni (inceleme 2026-10-17): uygulama içi karar {@code tech_error_reports.read/view} + modül görünürlüğü ister
     * (raporu açamayan karar veremez); bağlantı yolu da ister — {@link #linkDecisionAllowed}. {@code TechErrorReportController.READ} ile aynı.
     */
    public static final String READ_PERMISSION = "tech_error_reports.read";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Rol izni (A1). Oturumsuz değerlendirme: kullanıcının {@code systemRole} satırı ({@link PermissionService#allows(String,
     * String, String)}); 7/24 dinamik izinleri yazma iznine zaten uygulanmaz. Fasulye yoksa (dilim / H2 testleri) izin VAR
     * sayılır — üretimde fasulye her zaman vardır.
     */
    @Autowired(required = false)
    PermissionService permissions;

    /**
     * Modül görünürlüğü (S6, inceleme 2026-10-17): uygulama içi karar raporun takımı modüle açık değilse rapora hiç ulaşmaz;
     * bağlantı da aynı kuralı uygular. Fasulye yoksa (dilim / H2 testleri) sınırsız — üretimde fasulye her zaman vardır.
     */
    @Autowired(required = false)
    TechErrorVisibilityService visibility;

    private final TechErrorReportService service;
    private final TechErrorReportRepository reports;
    private final TechErrorApprovalTokenRepository tokens;
    private final AppUserRepository users;
    private final TechErrorReportSender sender;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.tech-error-report.mail-inline-mb:6}")
    int mailInlineMb = 6;

    public TechErrorApprovalService(TechErrorReportService service, TechErrorReportRepository reports,
                                    TechErrorApprovalTokenRepository tokens, AppUserRepository users,
                                    TechErrorReportSender sender, EmailNotificationService email,
                                    NotificationLogRepository logs, AppSettingsService appSettings) {
        this.service = service;
        this.reports = reports;
        this.tokens = tokens;
        this.users = users;
        this.sender = sender;
        this.email = email;
        this.logs = logs;
        this.appSettings = appSettings;
    }

    // ── Kim onaylar, ne gerekir ──────────────────────────────────────────────────────────────────────

    /** Onaylayıcı: aktif, org rolü PO, takımın üyesi (birincil ya da ek). {@code email} boş olabilir (yalnız uygulama içi). */
    public record Approver(Long id, String name, String email) { }

    /** Yönetici alıcısı tanımlı kanal = gönderim onaya bağlı. */
    public static boolean approvalRequired(TechErrorChannel ch) {
        return !TechErrorReportSender.executiveRecipients(ch).isEmpty();
    }

    /** Takımın onaylayıcıları (aktif PO üyeler), kullanıcı adına göre; e-postası olmayan da listede (uygulama içi onay). */
    public List<Approver> approvers(Long teamId) {
        if (teamId == null) return List.of();
        List<Approver> out = new ArrayList<>();
        Set<String> seenMail = new LinkedHashSet<>();
        for (AppUser u : users.findActiveByOrgRoleInTeam(teamId, APPROVER_ROLE)) {
            String mail = u.getEmail() == null ? null : u.getEmail().trim();
            // Boş adres → yok; aynı kutuyu paylaşan ikinci PO → yok (inceleme 2026-10-17: eski satır adresi aynen bırakıyordu —
            // ortak kutuya iki istek postası, iki belirteç). Uygulama içi onay yine açık.
            if (mail != null && (mail.isEmpty() || !seenMail.add(mail.toLowerCase(Locale.ROOT)))) mail = null;
            out.add(new Approver(u.getId(), displayName(u), mail));
        }
        return out;
    }

    public boolean isApprover(Long userId, Long teamId) {
        if (userId == null) return false;
        for (Approver a : approvers(teamId)) if (userId.equals(a.id())) return true;
        return false;
    }

    /**
     * Kullanıcının rolü onay iznine sahip mi (A1)? Uygulama içi uç oturumdan sorar; bağlantı yolu oturumsuzdur, kullanıcının
     * {@code systemRole} satırına bakar. Fail-closed: rolü boş kullanıcı onaylayamaz; fasulye yoksa (test dilimi) izin var.
     */
    public boolean hasApprovePermission(AppUser u) {
        if (u == null) return false;
        if (permissions == null) return true;
        try {
            return permissions.allows(u.getSystemRole(), APPROVE_PERMISSION, PermissionCatalog.EXECUTE);
        } catch (RuntimeException e) {
            log.warn("Onay izni okunamadı (kullanıcı {}): {}", u.getId(), e.toString());
            return false;
        }
    }

    /**
     * Bağlantıdan karar uygulama içi kararla AYNI kapılardan geçer (inceleme 2026-10-17): rolün okuma izni
     * ({@value #READ_PERMISSION}/view — uygulama içinde raporu açamayan karar veremez), modül raporun takımına açık
     * ({@link TechErrorVisibilityService#teamVisible}; fasulye yoksa sınırsız) ve onay izni ({@link #hasApprovePermission}).
     * Biri tutmazsa sebep {@code no_permission}. Fail-closed: okunamayan izin / görünürlük = yok.
     */
    public boolean linkDecisionAllowed(AppUser u, Long teamId) {
        if (!hasApprovePermission(u)) return false;
        if (permissions != null) {
            try {
                if (!permissions.allows(u.getSystemRole(), READ_PERMISSION, PermissionCatalog.VIEW)) return false;
            } catch (RuntimeException e) {
                log.warn("Okuma izni okunamadı (kullanıcı {}): {}", u.getId(), e.toString());
                return false;
            }
        }
        if (visibility != null) {
            try {
                if (!visibility.teamVisible(teamId)) return false;
            } catch (RuntimeException e) {
                log.warn("Modül görünürlüğü okunamadı (takım {}): {}", teamId, e.toString());
                return false;
            }
        }
        return true;
    }

    private static String displayName(AppUser u) {
        String n = u.getDisplayName();
        return n == null || n.isBlank() ? u.getUsername() : n;
    }

    /** Kullanıcı adı (opak kimlik kuralı: kimlik değil ad döner). */
    public String displayNameOf(Long userId) {
        return service.displayName(userId);
    }

    // ── İstek ────────────────────────────────────────────────────────────────────────────────────────

    /** İstek sonucu: bilgilendirilen onaylayıcılar, kaçına posta gitti / gidemedi, postaların gitmeme sebebi. */
    public record RequestResult(List<Approver> approvers, int mailed, int failed, String mailProblem, Instant at) { }

    public RequestResult request(TechErrorReport report, TechErrorChannel channel, Team team, Long by) {
        if (!approvalRequired(channel)) {
            throw new CodedConflictException("EXEC_RECIPIENTS_MISSING", Msg.t(
                    "Bu kanalın yönetici (executive) alıcısı tanımlı değil; onay akışı yok. Takım yöneticisi kanal ayarlarından alıcı ekleyince onaya gönderebilirsiniz.",
                    "This channel has no executive recipients, so there is no approval flow. Once a team manager adds recipients in the channel settings you can submit it for approval."));
        }
        List<Approver> apps = approvers(team.getId());
        if (apps.isEmpty()) {
            throw new CodedConflictException("NO_APPROVER", Msg.t(
                    "Takımda onaylayabilecek aktif bir PO (ürün sahibi) üye yok. Yöneticiniz bir üyenin kurum rolünü PO yapınca onaya gönderebilirsiniz.",
                    "The team has no active member with the PO (product owner) role to approve it. Once an administrator gives a member the PO role you can submit it for approval."));
        }
        TechErrorReport fresh = service.report(report.getId());
        long rev = fresh.getContentRev() == null ? 0L : fresh.getContentRev();
        Instant now = service.now();
        if (reports.requestApproval(fresh.getId(), rev, now, by, now.minus(RESEND_AFTER)) == 0) {
            TechErrorReport again = service.report(fresh.getId());
            long againRev = again.getContentRev() == null ? 0L : again.getContentRev();
            if ("PENDING".equals(again.getApprovalState()) && again.getApprovalRev() != null && again.getApprovalRev() == againRev) {
                throw new CodedConflictException("APPROVAL_PENDING", Msg.t(
                        "Bu rapor için zaten bekleyen bir onay isteği var. PO'nun kararını bekleyin; " + RESEND_AFTER.toMinutes()
                                + " dakika sonra isteği yeniden gönderebilir, rapor değişirse hemen yeniden isteyebilirsiniz.",
                        "There is already a pending approval request for this report. Wait for the PO's decision; you can resend the request after "
                                + RESEND_AFTER.toMinutes() + " minutes, or at once if the report changes."));
            }
            throw new CodedConflictException("REPORT_CHANGED", Msg.t(
                    "Rapor siz onaya gönderirken değişti. Sayfayı yenileyip içeriği gördükten sonra yeniden onaya gönderin.",
                    "The report changed while you were submitting it. Reload the page, check the content and submit it again."));
        }
        // Eski bağlantılar "yerine geçti" olur (A2: silinse eski posta "geçersiz bağlantı" derdi — şimdi "daha yeni istek
        // var, en son postayı kullanın"; yerine geçilmiş belirteç asla karar vermez). Süresi de dolmuş eskiler budanır.
        // Onaylayıcı başına yeni belirteç (ham değer yalnız postada).
        tokens.supersedeByReportId(fresh.getId(), now);
        try {
            tokens.deleteSupersededExpired(fresh.getId(), now);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} eski onay belirteçleri budanamadı: {}", fresh.getId(), e.toString());
        }
        Map<Long, String> rawTokens = new LinkedHashMap<>();
        for (Approver a : apps) {
            if (a.email() == null) continue;
            String raw = newToken();
            TechErrorApprovalToken t = new TechErrorApprovalToken();
            t.setReportId(fresh.getId());
            t.setUserId(a.id());
            t.setTokenHash(hash(raw));
            t.setApprovalRev(rev);
            t.setCreatedAt(now);
            t.setExpiresAt(now.plus(TOKEN_TTL));
            tokens.save(t);
            rawTokens.put(a.id(), raw);
        }
        fresh = service.report(fresh.getId());
        List<String> mails = new ArrayList<>();
        for (Approver a : apps) if (a.email() != null) mails.add(a.email());

        // Onay postası: raporun tam gövdesi + PDF (izin içinde, bir kez üretilir; her onaylayıcıya kendi bağlantısıyla)
        int mailed = 0, failed = 0;
        String problem = null;
        List<String> mailedTo = new ArrayList<>();
        if (!mails.isEmpty()) {
            try {
                long budget = Math.max(0, mailInlineMb) * 1024L * 1024L;
                TechErrorMailImages.Result[] prepared = { TechErrorMailImages.Result.empty() };
                TechErrorReport pdfRow = fresh;
                TechErrorReportService.Rendered rendered = service.renderPdf(() -> {
                    TechErrorPdfInput loaded = service.pdfInput(pdfRow, channel, team.getName());
                    try {
                        prepared[0] = TechErrorMailImages.prepare(loaded, budget);
                    } catch (RuntimeException e) {
                        prepared[0] = TechErrorMailImages.allSkipped(loaded);
                    }
                    return loaded;
                });
                TechErrorPdfInput in = rendered.meta();
                byte[] pdf = rendered.pdf();
                String fileName = TechErrorReportPdfWriter.fileName(channel.getName(), fresh.getReportYear(), fresh.getReportMonth(),
                        in.template().lang());
                long max = sender.maxMailBytes();
                // PDF sınıra sığmazsa ek düşer, gövde bunu SÖYLER (fileName null) ve sebep döner (inceleme 2026-10-17)
                boolean pdfFits = pdf.length <= max;
                List<EmailNotificationService.MailAttachment> attachments = pdfFits
                        ? List.of(new EmailNotificationService.MailAttachment(fileName, pdf, "application/pdf")) : List.of();
                if (!pdfFits) problem = "PDF_TOO_LARGE";
                if (pdf.length + prepared[0].bytes() > max) prepared[0] = TechErrorMailImages.allSkipped(in);
                String requester = service.displayName(by);
                int execCount = TechErrorReportSender.executiveRecipients(channel).size();
                for (Approver a : apps) {
                    if (a.email() == null) continue;
                    String raw = rawTokens.get(a.id());
                    String decideUrl = raw == null || baseUrl() == null ? null
                            : baseUrl() + "/api/tech-error-reports/approve-link?token=" + raw;
                    TechErrorReportMail.Built built = TechErrorApprovalMail.request(in, prepared[0], baseUrl(), pdfFits ? fileName : null,
                            fresh.getId(), channel.getId(), requester, a.name(), execCount, decideUrl, (int) TOKEN_TTL.toDays());
                    String subject = TechErrorApprovalMail.requestSubject(in);
                    String status;
                    try {
                        status = email.sendHtml(new String[] { a.email() }, null, subject, built.mail().html(), built.mail().text(),
                                built.inline(), false, attachments);
                        if (status == null) status = "FAILED: sonuç yok";
                    } catch (RuntimeException e) {
                        status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                    }
                    saveLog(a.name(), a.email(), "PO", subject, status, team.getName());
                    if ("SENT".equals(status) || status.startsWith("QUEUED")) { mailed++; mailedTo.add(a.email()); }
                    else failed++;
                }
            } catch (TechErrorReportService.PdfBusyException busy) {
                problem = "PDF_BUSY";
                failed = mails.size();
                for (Approver a : apps) if (a.email() != null) saveLog(a.name(), a.email(), "PO", channel.getName(),
                        "SKIPPED: PDF sırası dolu, onay postası gönderilmedi", team.getName());
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu #{} onay postası hazırlanamadı: {}", fresh.getId(), e.toString());
                problem = "MAIL_FAILED";
                failed = mails.size();
            }
        } else {
            problem = "NO_APPROVER_EMAIL";
        }
        // Geçmiş olayı postalardan SONRA: kime gerçekten gittiği ve gitmeme sebebi (inceleme 2026-10-17)
        TechErrorHistoryService h = service.history();
        if (h != null) h.recordSend(fresh, KIND_REQUEST, by, "PENDING", mailedTo, problem, now);
        return new RequestResult(apps, mailed, failed, problem, now);
    }

    // ── Karar ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Karar sonucu: {@code state} APPROVED | REJECTED; onayda yöneticilere gönderimin sonucu ({@code sendState} SENT /
     * QUEUED / SKIPPED / FAILED, başarısızsa {@code sendError} kodu — onay yine kalır).
     */
    public record Decision(String state, TechErrorReportSender.Result send, String sendError, Instant at) { }

    public Decision decide(TechErrorReport report, TechErrorChannel channel, Team team, Long by, boolean approve, String rawNote,
                           String via) {
        if (!isApprover(by, team.getId())) {
            throw new SecurityException(Msg.t(
                    "Bu raporu yalnız takımın PO (ürün sahibi) rolündeki aktif üyeleri onaylayabilir ya da reddedebilir.",
                    "Only the team's active members with the PO (product owner) role can approve or reject this report."));
        }
        String note = validateNote(approve, rawNote);
        TechErrorReport fresh = service.report(report.getId());
        if (!"PENDING".equals(fresh.getApprovalState())) throw notPending();
        Long rev = fresh.getApprovalRev();
        long content = fresh.getContentRev() == null ? 0L : fresh.getContentRev();
        Instant now = service.now();
        int n;
        if (approve) {
            if (rev == null || content != rev) throw stale();
            n = reports.approve(fresh.getId(), rev, now, by, note);
            if (n == 0) {
                TechErrorReport again = service.report(fresh.getId());
                if (!"PENDING".equals(again.getApprovalState())) throw notPending();
                throw stale();
            }
        } else {
            n = reports.reject(fresh.getId(), rev, now, by, note);
            if (n == 0) throw notPending();
        }
        try {
            tokens.closeAllForReport(fresh.getId(), now);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} onay bağlantıları kapatılamadı: {}", fresh.getId(), e.toString());
        }
        String state = approve ? "APPROVED" : "REJECTED";
        TechErrorHistoryService h = service.history();
        if (h != null) {
            try {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("state", state);
                detail.put("via", via);
                if (note != null) detail.put("note", note.length() > 300 ? note.substring(0, 300) : note);
                h.record(fresh, KIND_DECISION, by, null, detail, now);
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu #{} onay olayı yazılamadı: {}", fresh.getId(), e.toString());
            }
        }
        TechErrorReportSender.Result send = null;
        String sendError = null;
        if (approve) {
            try {
                send = sender.send(service.report(fresh.getId()), channel, team, by, TechErrorReportSender.Delivery.EXECUTIVE);
            } catch (CodedConflictException e) {
                sendError = e.getCode();
            } catch (TechErrorReportService.PdfBusyException e) {
                sendError = "PDF_BUSY";
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu #{} onay sonrası yöneticilere gönderilemedi: {}", fresh.getId(), e.toString());
                sendError = "MAIL_FAILED";
            }
        }
        notifyRequester(fresh, channel, team, by, approve, note, send, sendError, now);
        return new Decision(state, send, sendError, now);
    }

    private void notifyRequester(TechErrorReport r, TechErrorChannel ch, Team team, Long deciderId, boolean approved, String note,
                                 TechErrorReportSender.Result send, String sendError, Instant now) {
        Long requesterId = r.getApprovalRequestedById();
        if (requesterId == null) return;
        AppUser u = users.findById(requesterId).orElse(null);
        if (u == null || Boolean.FALSE.equals(u.getActive()) || u.getEmail() == null || u.getEmail().isBlank()) return;
        try {
            TechErrorTemplate.Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
            String title = TechErrorTemplate.resolve(t.title(), ch.getName(), r.getReportYear(), r.getReportMonth(), t.lang());
            String sendState = send == null ? (approved ? (sendError == null ? "FAILED" : sendError) : null) : send.state();
            MailDoc.Mail mail = TechErrorApprovalMail.decision(title, t.en(), approved, note, service.displayName(deciderId),
                    sendState, send == null ? List.of() : send.to(), baseUrl(), r.getId(), ch.getId());
            String subject = TechErrorApprovalMail.decisionSubject(title, t.en(), approved);
            String status;
            try {
                status = email.sendHtml(new String[] { u.getEmail().trim() }, null, subject, mail.html(), mail.text(), null, false, null);
                if (status == null) status = "FAILED: sonuç yok";
            } catch (RuntimeException e) {
                status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
            }
            saveLog(displayName(u), u.getEmail().trim(), "REQUESTER", subject, status, team.getName());
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} karar postası gönderilemedi: {}", r.getId(), e.toString());
        }
    }

    /** Not kuralı (karar ve bağlantı yolu aynı): ≤ {@link #MAX_NOTE}, red için zorunlu; kırpılmış değer ya da null döner. */
    static String validateNote(boolean approve, String rawNote) {
        String note = rawNote == null ? null : rawNote.trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > MAX_NOTE) {
            throw new FieldValidationException("note", Msg.t("Not en çok " + MAX_NOTE + " karakter olabilir.",
                    "The note can be at most " + MAX_NOTE + " characters."));
        }
        if (!approve && note == null) {
            throw new FieldValidationException("note", Msg.t(
                    "Reddetme gerekçesini yazın: ekip raporu buna göre düzeltir.",
                    "Write the reason for rejecting: the team will fix the report accordingly."));
        }
        return note;
    }

    private static CodedConflictException notPending() {
        return new CodedConflictException("APPROVAL_NOT_PENDING", Msg.t(
                "Bu rapor için bekleyen bir onay isteği yok (karar verilmiş ya da istek geri çekilmiş olabilir). Sayfayı yenileyin.",
                "There is no pending approval request for this report (it may already be decided or withdrawn). Reload the page."));
    }

    private static CodedConflictException stale() {
        return new CodedConflictException("APPROVAL_STALE", Msg.t(
                "Rapor onay isteğinden sonra değişti; bu hâliyle onaylanamaz. Ekip raporu yeniden onaya göndermeli.",
                "The report changed after the approval request, so this version can't be approved. The team must submit it again."));
    }

    // ── Bağlantı belirteci ───────────────────────────────────────────────────────────────────────────

    /**
     * Bağlantı durumu: {@code valid} ise {@code reason} null; değilse not_found | used | superseded | expired | not_pending |
     * stale | approver_inactive | no_permission. Geçerli bağlantının raporu, kanalı ve onaylayıcısı da döner (sayfa özeti).
     * Sıra: kullanılmış → yerine geçilmiş (A2: yeni istek gönderildi — süresi de dolmuş olsa "en son postayı kullanın" demek
     * daha doğru) → süresi dolmuş → rapor / kanal yok → bekleyen istek değil → onaylayıcı değil → rolün onay / okuma izni
     * yok ya da modül takıma kapalı (A1, {@link #linkDecisionAllowed}).
     */
    public record TokenStatus(boolean valid, String reason, TechErrorApprovalToken token, TechErrorReport report,
                              TechErrorChannel channel, AppUser user, boolean stale) {
        public TokenStatus(boolean valid, String reason, TechErrorApprovalToken token, TechErrorReport report,
                           TechErrorChannel channel, AppUser user) {
            this(valid, reason, token, report, channel, user, false);
        }
    }

    public TokenStatus status(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 128) return new TokenStatus(false, "not_found", null, null, null, null);
        TechErrorApprovalToken t = tokens.findByTokenHash(hash(raw.trim())).orElse(null);
        if (t == null) return new TokenStatus(false, "not_found", null, null, null, null);
        Instant now = service.now();
        TechErrorReport r = reports.findById(t.getReportId()).orElse(null);
        TechErrorChannel ch = null;
        if (r != null) {
            try {
                ch = service.channel(r.getChannelId());
            } catch (RuntimeException e) {
                ch = null;
            }
        }
        AppUser u = users.findById(t.getUserId()).orElse(null);
        if (t.getUsedAt() != null) return new TokenStatus(false, "used", t, r, ch, u);
        if (t.getSupersededAt() != null) return new TokenStatus(false, "superseded", t, r, ch, u);
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(now)) return new TokenStatus(false, "expired", t, r, ch, u);
        if (r == null || ch == null) return new TokenStatus(false, "not_found", t, r, ch, u);
        if (!"PENDING".equals(r.getApprovalState()) || !Objects.equals(r.getApprovalRev(), t.getApprovalRev())) {
            return new TokenStatus(false, "not_pending", t, r, ch, u);
        }
        if (u == null || Boolean.FALSE.equals(u.getActive()) || !isApprover(u.getId(), r.getTeamId())) {
            return new TokenStatus(false, "approver_inactive", t, r, ch, u);
        }
        // A1: uygulama içi uçla aynı kapılar — rolün onay ya da okuma izni kapalıysa veya modül raporun takımına kapalıysa
        // PO bağlantıdan da karar veremez (inceleme 2026-10-17: okuma izni ve modül görünürlüğü de)
        if (!linkDecisionAllowed(u, r.getTeamId())) return new TokenStatus(false, "no_permission", t, r, ch, u);
        // İstekten sonra değişen içerik: bağlantı geçerli ama ONAYLANAMAZ (reddedilebilir) — sayfa bunu söyler
        long content = r.getContentRev() == null ? 0L : r.getContentRev();
        boolean stale = r.getApprovalRev() == null || content != r.getApprovalRev();
        return new TokenStatus(true, null, t, r, ch, u, stale);
    }

    /**
     * Bağlantıdan karar. Not önce doğrulanır (belirteç boşa tüketilmesin), eskimiş istek onaylanamaz ({@code stale}),
     * tek kullanım damgası karardan ÖNCE (iki tıklama iki karar yazmaz); karar beklenmedik bir hatayla yazılamazsa damga
     * geri alınır — bağlantı kullanılabilir kalır (inceleme 2026-10-17). Geçersizse {@code IllegalStateException(reason)}.
     */
    public Decision decideViaToken(String raw, Team team, boolean approve, String note) {
        TokenStatus st = status(raw);
        if (!st.valid()) throw new IllegalStateException(st.reason());
        // Derinlikli savunma (A1 / A2): status() bunları zaten eler; sırası değişse de belirteç tüketilmeden durur
        if (st.token().getSupersededAt() != null) throw new IllegalStateException("superseded");
        if (!linkDecisionAllowed(st.user(), st.report().getTeamId())) throw new IllegalStateException("no_permission");
        if (approve && st.stale()) throw new IllegalStateException("stale");
        validateNote(approve, note);
        Instant at = service.now();
        // Damga yalnız kullanılmamış VE yerine geçilmemiş satıra (inceleme 2026-10-17): durum okumasından sonra gelen yeni bir
        // istek eski bağlantıyı yerine geçirirse karar yazılmaz; satır yeniden okunur, sayfa gerçek sebebi söyler
        if (tokens.markUsed(st.token().getId(), at) == 0) throw new IllegalStateException(unusableReason(st.token().getId()));
        try {
            return decide(st.report(), st.channel(), team, st.user().getId(), approve, note, "email_token");
        } catch (CodedConflictException e) {
            throw e;            // karar verilmiş / eskimiş: bağlantının işi bitti
        } catch (RuntimeException e) {
            try {
                tokens.restoreUnused(st.token().getId(), at);
            } catch (RuntimeException ex) {
                log.warn("Onay bağlantısı damgası geri alınamadı (rapor {}): {}", st.report().getId(), ex.toString());
            }
            throw e;
        }
    }

    /**
     * Damga tutmadı ({@code markUsed} 0): satır ŞİMDİ ne durumda? Kullanılmış (karar verildi / başka tıklama) → {@code used};
     * yerine geçilmiş (yeni istek) → {@code superseded}; satır yok (rapor silindi) → {@code not_found}. Okunamazsa {@code used}
     * (karar yazılmadı; sayfa "Site Monitor'de güncel durumu görün" der).
     */
    private String unusableReason(Long tokenId) {
        try {
            TechErrorApprovalToken now = tokens.findById(tokenId).orElse(null);
            if (now == null) return "not_found";
            if (now.getUsedAt() != null) return "used";
            if (now.getSupersededAt() != null) return "superseded";
            return "used";
        } catch (RuntimeException e) {
            log.warn("Onay bağlantısı yeniden okunamadı (belirteç {}): {}", tokenId, e.toString());
            return "used";
        }
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    String baseUrl() {
        try {
            String url = appSettings.getString("site.monitor.app.base-url", "");
            return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void saveLog(String name, String to, String role, String subject, String status, String teamName) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(clip((name == null ? "" : name) + (teamName == null ? "" : " · " + teamName), 255));
            n.setRecipientEmail(to);
            n.setRecipientRole(role);
            n.setSubject(clip(subject, 500));
            n.setEmailStatus(clip(status, 250));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(email.fromAddress());
            logs.save(n);
        } catch (Exception e) {
            log.warn("Teknik hata raporu onay günlüğü yazılamadı: {}", e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
