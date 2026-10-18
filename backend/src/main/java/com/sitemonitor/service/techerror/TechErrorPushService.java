package com.sitemonitor.service.techerror;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.TechErrorReminderMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Kanal teknik hata raporu PUSH bildirimleri (2026-10-18, kullanıcı isteği: "teknik hata raporu ile ilgili bildirimleri
 * haftalık rapordaki gibi push bildirimleri ile de yapalım. ayarlar üzerinden kontrol edilebilsin").
 *
 * <p>Haftalık rapor push'unun ({@code WeeklyReportService.pushTeamApproval}) aynası: kişiye doğrudan push
 * ({@link UserPushService#enqueueDirectLocalized}; opt-out, saat tavanı, devre kesici, tekilleştirme oradan) ya da takım
 * bildirimi ({@link UserPushService#enqueueTeamNoticeLocalized}; rol grubu / asgari seviye / pasif hesap / kişisel tercih
 * kuralları çözümde). Seviye her olayda {@value #LEVEL} (haftalık rapor emsali: bilgilendirme, alarm değil — başarısızlığı
 * metin söyler). Tür {@value #MONITOR_TYPE}: teslimat günlüğünde ve "Push bildirimlerim"de bu adla süzülür; kişisel aile
 * süzgeci (izleme türleri) bu bildirimlere uygulanmaz (haftalık rapor gibi — izleme değil).
 *
 * <p>Olaylar ve alıcılar (her biri Ayarlar → Teknik hata raporu → Push bildirimleri'nde ayrı anahtar, varsayılan AÇIK):
 * <ol>
 *   <li>Onay isteği / yeniden gönderilen istek → takımın PO onaylayıcıları (e-postası olmayanlar dahil; isteyen hariç).</li>
 *   <li>Onay hatırlatması (günlük iş) → aynı PO'lar, hatırlatma penceresi başına bir kez (iş talebi tekilleştirir).</li>
 *   <li>Karar (onay / red) → isteyen; kararı kendisi verdiyse atlanır.</li>
 *   <li>Rapor gönderildi (SENT; kuyruktaki gönderim sonuçlanınca da) → takım bildirimi, gönderen ve bu olay için zaten push
 *   alan kişiler hariç.</li>
 *   <li>Gönderim başarısız (FAILED; kuyruk sonunda FAILED; sonucu alınamayan eski kuyruk) → gönderen.</li>
 * </ol>
 *
 * <p>Kurallar: pasif hesap asla alıcı değildir; erişim paylaşımıyla (team access grant) okuyan kişi asla alıcı değildir
 * (alıcılar takımın üyeleri / PO'ları ve isteyen / gönderen — ikisi de kendi kapsamıyla yazar); modül takıma kapalıysa
 * push yok. Kişisel tercihler: takım bildiriminde çözümün kendisi, doğrudan push'ta {@link
 * UserPushRecipientResolver#preferenceSkip} (seviye / susturma; satır nedeniyle yazılır). Metinler kişinin push dilinde
 * (TR + EN), {@code PushText.pushSafe} ile değişmeyen karakterlerle, BAĞLANTISIZ (push uygulaması kurum ağının dışında).
 * Tekilleştirme anahtarları olay başına benzersiz (rapor + istek / karar / gönderim anı) — iki pod ya da yeniden deneme
 * ikinci push yazmaz (çıkış kutusunun anahtar × kullanıcı tekilliği). Hiçbir hata çağıranın akışını (gönderim, onay)
 * değiştirmez: her yöntem kendi hatasını yutar ve WARN yazar.
 */
@Slf4j
@Service
public class TechErrorPushService {

    public static final String MONITOR_TYPE = TechErrorReminderService.PUSH_MONITOR_TYPE;
    public static final String LEVEL = "WARNING";
    public static final String TRIGGER_APPROVAL = "TECH_ERROR_APPROVAL";
    public static final String TRIGGER_APPROVAL_REMINDER = "TECH_ERROR_APPROVAL_REMINDER";
    public static final String TRIGGER_DECISION = "TECH_ERROR_DECISION";
    public static final String TRIGGER_SENT = "TECH_ERROR_SENT";
    public static final String TRIGGER_SEND_FAILED = "TECH_ERROR_SEND_FAILED";
    /** Red notunun push'a giren ilk karakterleri (mesaj tavanı ~200; kalan "..." ile kesilir). */
    static final int NOTE_CHARS = 120;
    static final String HEAD_TR = "[Teknik hata raporu] ";
    static final String HEAD_EN = "[Technical error report] ";

    private final AppSettingsService settings;
    private final AppUserRepository users;

    /**
     * Push servisi tembel (geniş bağımlılık ağı; döngü olmasın). Uyarı: {@code @Lazy} + {@code required=false} fasulye YOKKEN
     * de vekil verir ve çağrı istisna atar — her çağrı aşağıda try/catch içinde (kurulum ve testlerde push yok sayılır).
     */
    @Autowired(required = false) @Lazy
    private UserPushService push;
    @Autowired(required = false)
    private TechErrorVisibilityService visibility;

    public TechErrorPushService(AppSettingsService settings, AppUserRepository users) {
        this.settings = settings;
        this.users = users;
    }

    /** Test kancaları. */
    void setPush(UserPushService p) { this.push = p; }
    void setVisibility(TechErrorVisibilityService v) { this.visibility = v; }

    /** Bildirimin raporu: kimlik, takım, kanal adı, dönem (dil başına ay adıyla yazılır). */
    public record Ctx(Long reportId, Long teamId, String channelName, int year, int month) {
        public Ctx {
            channelName = channelName == null ? "" : channelName;
        }

        public static Ctx of(TechErrorReport r, TechErrorChannel ch) {
            String name = ch != null && ch.getName() != null ? ch.getName() : "";
            return new Ctx(r.getId(), r.getTeamId(), name, r.getReportYear() == null ? 0 : r.getReportYear(),
                    r.getReportMonth() == null ? 0 : r.getReportMonth());
        }

        String subject(boolean en) {
            return (channelName.isBlank() ? "" : channelName.trim() + " ") + TechErrorReminderMail.period(year, month, en);
        }
    }

    // ── 1. Onay isteği ────────────────────────────────────────────────────────────────────────────────

    /**
     * Onay isteği (ya da 10 dakikadan eski bekleyen isteğin yeniden gönderimi) → onaylayıcılar. İsteğin postası kime ulaştıysa
     * ({@code mailedTo}, adres bazında — aynı kutuyu paylaşan PO da) "e-postanızdan ya da Site Monitor'den", ulaşmadıysa
     * (e-postası yok, PDF sırası dolu, posta kapalı / başarısız) "onay e-postası size ulaşmadı" der.
     *
     * @return kuyruğa alınan push sayısı
     */
    public int approvalRequested(Ctx c, List<TechErrorApprovalService.Approver> approvers, Long requesterId, String requesterName,
                                 boolean resend, Collection<String> mailedTo, Instant requestedAt) {
        if (!ready(c, TechErrorReportSettings.KEY_PUSH_APPROVAL_REQUEST)) return 0;
        try {
            Set<String> reached = lowerSet(mailedTo);
            List<AppUser> people = approverUsers(approvers, requesterId);
            List<UserPushService.DirectRecipient> got = new ArrayList<>();
            List<UserPushService.DirectRecipient> missed = new ArrayList<>();
            for (AppUser u : people) {
                String mail = u.getEmail() == null ? "" : u.getEmail().trim().toLowerCase(Locale.ROOT);
                (reached.contains(mail) ? got : missed).add(direct(u));
            }
            String key = (resend ? "te-appr-resend:" : "te-appr:") + c.reportId() + ":" + millis(requestedAt);
            String who = nz(requesterName);
            int queued = 0;
            if (!got.isEmpty()) queued += direct(got, c, TRIGGER_APPROVAL, requestText(c, resend, true, who), key);
            if (!missed.isEmpty()) queued += direct(missed, c, TRIGGER_APPROVAL, requestText(c, resend, false, who), key);
            return queued;
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} onay isteği push'u kuyruğa alınamadı (istek etkilenmedi): {}", c.reportId(), e.toString());
            return 0;
        }
    }

    static UserPushService.LocalizedText requestText(Ctx c, boolean resend, boolean mailReached, String requester) {
        String tr = HEAD_TR + (resend ? "Hatırlatma: " + c.subject(false) + " raporu hâlâ onayınızı bekliyor"
                : c.subject(false) + " raporu onayınızı bekliyor")
                + (mailReached ? "; e-postanızdan ya da Site Monitor'den karar verin."
                        : ". Onay e-postası size ulaşmadı; raporu Site Monitor'de açıp karar verin.")
                + byLine(" Talep eden: ", requester);
        String en = HEAD_EN + (resend ? "Reminder: " + c.subject(true) + " is still waiting for your approval"
                : c.subject(true) + " is waiting for your approval")
                + (mailReached ? "; decide from your e-mail or in Site Monitor."
                        : ". The approval e-mail did not reach you; open the report in Site Monitor and decide.")
                + byLine(" Requested by: ", requester);
        return new UserPushService.LocalizedText(tr, en);
    }

    // ── 2. Onay hatırlatması ──────────────────────────────────────────────────────────────────────────

    /**
     * Günlük onay hatırlatmasının push'u → aynı PO'lar (e-postası olmayanlar dahil, isteyen hariç). Hatırlatma işinin talebi
     * pencere başına bir kez çağırır; anahtar gün taşır (iki pod / yeniden deneme ikinci push yazmaz).
     *
     * @return kuyruğa alınan push sayısı (0 = push ulaşmadı — çağıran talebi buna göre bırakır)
     */
    public int approvalReminder(Ctx c, List<TechErrorApprovalService.Approver> approvers, Long requesterId, String requesterName,
                                long waitingDays, Instant requestedAt, String today) {
        if (!ready(c, TechErrorReportSettings.KEY_PUSH_APPROVAL_REMINDER)) return 0;
        try {
            List<UserPushService.DirectRecipient> list = new ArrayList<>();
            for (AppUser u : approverUsers(approvers, requesterId)) list.add(direct(u));
            if (list.isEmpty()) return 0;
            String key = "te-appr-remind:" + c.reportId() + ":" + millis(requestedAt) + ":" + nz(today);
            return direct(list, c, TRIGGER_APPROVAL_REMINDER, reminderText(c, waitingDays, nz(requesterName)), key);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} onay hatırlatması push'u kuyruğa alınamadı: {}", c.reportId(), e.toString());
            return 0;
        }
    }

    static UserPushService.LocalizedText reminderText(Ctx c, long days, String requester) {
        long d = Math.max(0, days);
        String tr = HEAD_TR + c.subject(false) + " raporu " + d + " gündür onayınızı bekliyor; Site Monitor'de açıp karar verin."
                + byLine(" Talep eden: ", requester);
        String en = HEAD_EN + c.subject(true) + " has been waiting for your approval for " + d + (d == 1 ? " day" : " days")
                + "; open it in Site Monitor and decide." + byLine(" Requested by: ", requester);
        return new UserPushService.LocalizedText(tr, en);
    }

    // ── 3. Karar ──────────────────────────────────────────────────────────────────────────────────────

    /** Kararın push'u isteyene gidecek mi (ayar açık, isteyen var ve kararı kendisi vermedi) — gönderim bildiriminden düşülür. */
    public boolean decisionGoesTo(Long requesterId, Long deciderId) {
        return requesterId != null && !Objects.equals(requesterId, deciderId)
                && TechErrorReportSettings.pushOn(settings, TechErrorReportSettings.KEY_PUSH_DECISION);
    }

    /**
     * Karar → isteyen (doğrudan). Onayda yöneticilere gönderimin sonucu da yazılır ({@code sendState} SENT / QUEUED; başka her
     * sonuç — SKIPPED, FAILED ya da {@code sendError} kodu — "e-posta BAŞARISIZ (kod)", haftalık rapor gibi); redde notun ilk
     * {@value #NOTE_CHARS} karakteri. İsteyen kararı kendisi verdiyse atlanır.
     */
    public void decided(Ctx c, Long requesterId, Long deciderId, String deciderName, boolean approved, String note,
                        String sendState, String sendError, Instant decidedAt) {
        if (!decisionGoesTo(requesterId, deciderId) || !ready(c, TechErrorReportSettings.KEY_PUSH_DECISION)) return;
        try {
            AppUser u = users.findById(requesterId).orElse(null);
            if (!active(u)) return;
            String key = "te-decision:" + c.reportId() + ":" + millis(decidedAt);
            direct(List.of(direct(u)), c, TRIGGER_DECISION, decisionText(c, approved, nz(deciderName), note, sendState, sendError), key);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} karar push'u kuyruğa alınamadı (karar etkilenmedi): {}", c.reportId(), e.toString());
        }
    }

    static UserPushService.LocalizedText decisionText(Ctx c, boolean approved, String decider, String note, String sendState,
                                                      String sendError) {
        if (!approved) {
            String n = noteHead(note);
            String tr = HEAD_TR + c.subject(false) + " raporu reddedildi; düzeltip yeniden onaya gönderin." + byLine(" Reddeden: ", decider)
                    + (n.isEmpty() ? "" : " Not: " + n);
            String en = HEAD_EN + c.subject(true) + " was rejected; fix it and submit it for approval again." + byLine(" Rejected by: ", decider)
                    + (n.isEmpty() ? "" : " Note: " + n);
            return new UserPushService.LocalizedText(tr, en);
        }
        String code = sendState != null ? sendState : sendError == null ? "FAILED" : sendError;
        String tr;
        String en;
        if ("SENT".equals(code)) {
            tr = HEAD_TR + c.subject(false) + " raporu onaylandı ve yöneticilere gönderildi.";
            en = HEAD_EN + c.subject(true) + " was approved and sent to the executives.";
        } else if ("QUEUED".equals(code)) {
            tr = HEAD_TR + c.subject(false) + " raporu onaylandı; yöneticilere gönderim kuyrukta, posta sunucusu yeniden deniyor.";
            en = HEAD_EN + c.subject(true) + " was approved; sending to the executives is queued and the mail server is retrying.";
        } else {
            tr = HEAD_TR + c.subject(false) + " raporu onaylandı ama yöneticilere e-posta BAŞARISIZ (" + code
                    + "). Raporu Site Monitor'den yeniden gönderin.";
            en = HEAD_EN + c.subject(true) + " was approved but the e-mail to the executives FAILED (" + code
                    + "). Send it again from Site Monitor.";
        }
        return new UserPushService.LocalizedText(tr + byLine(" Onaylayan: ", decider), en + byLine(" Approved by: ", decider));
    }

    // ── 4. Gönderildi ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Rapor gönderildi (SENT — eşzamanlı ya da kuyruktaki gönderim sonuçlanınca) → takım bildirimi. Gönderen ve
     * {@code alsoNotified} (bu olay için zaten doğrudan push alan — ör. onay kararını alan isteyen) hariç. Anahtar rapor +
     * içerik sayacı + talep anı: aynı gönderim iki yoldan (eşzamanlı / kuyruk) iki kez bildirilmez.
     */
    public void delivered(Ctx c, boolean executive, Long senderId, String senderName, Long sentRev, Instant claimedAt,
                          Collection<Long> alsoNotified) {
        if (!ready(c, TechErrorReportSettings.KEY_PUSH_DELIVERED)) return;
        try {
            Set<Long> skipIds = new LinkedHashSet<>();
            if (senderId != null) skipIds.add(senderId);
            if (alsoNotified != null) for (Long id : alsoNotified) if (id != null) skipIds.add(id);
            Set<String> excluded = new HashSet<>();
            if (!skipIds.isEmpty()) {
                for (AppUser u : users.findAllById(skipIds)) if (u.getUsername() != null) excluded.add(u.getUsername().trim());
            }
            String key = "te-sent:" + c.reportId() + ":" + (sentRev == null ? 0 : sentRev) + ":" + millis(claimedAt);
            push.enqueueTeamNoticeLocalized(c.teamId(), TRIGGER_SENT, LEVEL, MONITOR_TYPE, c.subject(false),
                    deliveredText(c, executive, nz(senderName)), key, excluded);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} gönderildi push'u kuyruğa alınamadı (gönderim etkilenmedi): {}", c.reportId(), e.toString());
        }
    }

    static UserPushService.LocalizedText deliveredText(Ctx c, boolean executive, String sender) {
        String tr = HEAD_TR + c.subject(false) + " raporu " + (executive ? "yöneticilere" : "takım adresine") + " gönderildi."
                + byLine(" Gönderen: ", sender);
        String en = HEAD_EN + c.subject(true) + " was sent to " + (executive ? "the executives" : "the team address") + "."
                + byLine(" Sent by: ", sender);
        return new UserPushService.LocalizedText(tr, en);
    }

    // ── 5. Gönderilemedi ──────────────────────────────────────────────────────────────────────────────

    /** Başarısızlık türü: anında başarısız, kuyruk sonunda başarısız, sonucu alınamayan (yerine geçilmiş) eski kuyruk. */
    public enum Failure { FAILED, QUEUE_FAILED, UNKNOWN }

    /**
     * Gönderim ulaşmadı → gönderen (doğrudan). {@code reason}: geçmiş olayının sebep kodu ({@code PDF_TOO_LARGE} vb.; metin
     * kodu değil anlamını yazar). Anahtar rapor + talep anı + tür.
     */
    public void sendFailed(Ctx c, boolean executive, Long senderId, Failure kind, String reason, Instant claimedAt) {
        if (senderId == null || !ready(c, TechErrorReportSettings.KEY_PUSH_FAILED)) return;
        try {
            AppUser u = users.findById(senderId).orElse(null);
            if (!active(u)) return;
            Failure k = kind == null ? Failure.FAILED : kind;
            String key = "te-fail:" + c.reportId() + ":" + millis(claimedAt) + (k == Failure.UNKNOWN ? ":unknown" : "");
            direct(List.of(direct(u)), c, TRIGGER_SEND_FAILED, failedText(c, executive, k, reason), key);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} gönderilemedi push'u kuyruğa alınamadı: {}", c.reportId(), e.toString());
        }
    }

    static UserPushService.LocalizedText failedText(Ctx c, boolean executive, Failure kind, String reason) {
        String whereTr = executive ? "yöneticilere" : "takım adresine";
        String whereEn = executive ? "the executives" : "the team address";
        if (kind == Failure.UNKNOWN) {
            // Yerine geçilen eski kuyruk: hangi alıcıya gittiği bu denemeden bilinmez — metin yer adı vermez
            return new UserPushService.LocalizedText(
                    HEAD_TR + c.subject(false) + " raporunun önceki gönderiminin sonucu alınamadı; rapor gönderilmemiş görünüyor. Site Monitor'den yeniden gönderin.",
                    HEAD_EN + "The result of the earlier send of " + c.subject(true) + " never arrived; the report shows as not sent. Send it again from Site Monitor.");
        }
        String whyTr;
        String whyEn;
        if (kind == Failure.QUEUE_FAILED) {
            whyTr = "posta sunucusu teslim edemedi";
            whyEn = "the mail server could not deliver it";
        } else if ("PDF_TOO_LARGE".equals(reason)) {
            whyTr = "PDF e-posta boyut sınırını aşıyor";
            whyEn = "the PDF exceeds the e-mail size limit";
        } else {
            whyTr = "e-posta gönderilemedi";
            whyEn = "the e-mail could not be sent";
        }
        return new UserPushService.LocalizedText(
                HEAD_TR + c.subject(false) + " raporu " + whereTr + " gönderilemedi (" + whyTr + "). Raporu Site Monitor'den yeniden gönderin.",
                HEAD_EN + c.subject(true) + " was not sent to " + whereEn + " (" + whyEn + "). Send it again from Site Monitor.");
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────

    /** Olay açık mı + kanal var mı + modül takıma açık mı (kapalıysa push yok — hatırlatmaların HIDDEN kapısı gibi). */
    private boolean ready(Ctx c, String key) {
        if (push == null || c == null || c.reportId() == null || c.teamId() == null) return false;
        if (!TechErrorReportSettings.pushOn(settings, key)) return false;
        TechErrorVisibilityService v = visibility;
        if (v == null) return true;
        try {
            if (v.teamVisible(c.teamId())) return true;
            log.debug("Teknik hata raporu #{} push'u atlandı: modül takım {} için kapalı", c.reportId(), c.teamId());
            return false;
        } catch (RuntimeException e) {
            return true;   // okunamayan görünürlük modülü kapatmaz (TechErrorVisibilityService ile aynı kural)
        }
    }

    /** Onaylayıcılar → aktif kullanıcılar (isteyen hariç; sıra korunur). */
    private List<AppUser> approverUsers(List<TechErrorApprovalService.Approver> approvers, Long requesterId) {
        List<Long> ids = new ArrayList<>();
        for (TechErrorApprovalService.Approver a : approvers == null ? List.<TechErrorApprovalService.Approver>of() : approvers) {
            if (a == null || a.id() == null || Objects.equals(a.id(), requesterId) || ids.contains(a.id())) continue;
            ids.add(a.id());
        }
        if (ids.isEmpty()) return List.of();
        Map<Long, AppUser> byId = new LinkedHashMap<>();
        for (AppUser u : users.findAllById(ids)) byId.put(u.getId(), u);
        List<AppUser> out = new ArrayList<>();
        for (Long id : ids) {
            AppUser u = byId.get(id);
            if (active(u)) out.add(u);
        }
        return out;
    }

    private static boolean active(AppUser u) {
        return u != null && Boolean.TRUE.equals(u.getActive()) && u.getUsername() != null && !u.getUsername().isBlank();
    }

    /** Kullanıcı → doğrudan alıcı: dil, opt-out ve kişisel tercih kararı (seviye / susturma — satır nedeniyle yazılır). */
    private static UserPushService.DirectRecipient direct(AppUser u) {
        String dn = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
        String skip = UserPushRecipientResolver.preferenceSkip(u, LEVEL, null, Instant.now());
        return new UserPushService.DirectRecipient(u.getUsername().trim(), dn, Boolean.TRUE.equals(u.getPushOptOut()),
                u.getPushLang(), skip);
    }

    private int direct(List<UserPushService.DirectRecipient> list, Ctx c, String trigger, UserPushService.LocalizedText text,
                       String key) {
        Map<String, Object> res = push.enqueueDirectLocalized(list, c.teamId(), trigger, LEVEL, MONITOR_TYPE, c.subject(false), text, key);
        return res != null && res.get("queued") instanceof Number n ? n.intValue() : 0;
    }

    /** Notun ilk {@value #NOTE_CHARS} karakteri, satır sonları boşluğa (push tek satır okunur). */
    static String noteHead(String note) {
        if (note == null) return "";
        String n = note.replaceAll("\\s+", " ").trim();
        if (n.length() <= NOTE_CHARS) return n;
        int cut = NOTE_CHARS;
        if (Character.isHighSurrogate(n.charAt(cut - 1))) cut--;   // vekil çifti bölünmez
        return n.substring(0, cut).trim() + "...";
    }

    /** " Talep eden: Ad." — ad boşsa hiç; ad zaten noktayla bitiyorsa ikinci nokta yok. */
    static String byLine(String label, String name) {
        if (name == null || name.isBlank()) return "";
        String n = name.trim();
        return label + n + (n.endsWith(".") ? "" : ".");
    }

    private static Set<String> lowerSet(Collection<String> mails) {
        Set<String> out = new HashSet<>();
        if (mails != null) for (String m : mails) if (m != null && !m.isBlank()) out.add(m.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static long millis(Instant at) {
        return at == null ? 0L : at.toEpochMilli();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
