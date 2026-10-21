package com.sitemonitor.service.techerror;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PushText;
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
import java.util.HashMap;
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
 *   <li>Karar (onay / red) → isteyen; kararı kendisi verdiyse atlanır. İsteyen o arada takımdan ayrılmışsa da alır (onayı
 *   O istedi — doğrudan push takım üyeliği sormaz; "teslim edildi" push'u da öyle).</li>
 *   <li>Rapor gönderildi (SENT; kuyruktaki gönderim sonuçlanınca da) → takım bildirimi, gönderen ve bu olay için zaten push
 *   alan kişiler hariç; onaydan sonra kuyruğa giren gönderim teslim edilince onaya gönderen kişi bunun yerine DOĞRUDAN
 *   "teslim edildi" push'u alır (2026-10-19 — kişi başına tek push).</li>
 *   <li>Gönderim başarısız (FAILED; kuyruk sonunda FAILED; sonucu alınamayan eski kuyruk) → gönderen.</li>
 * </ol>
 *
 * <p>Kurallar: pasif hesap asla alıcı değildir; erişim paylaşımıyla (team access grant) okuyan kişi asla alıcı değildir
 * (alıcılar takımın üyeleri / PO'ları ve isteyen / gönderen — ikisi de kendi kapsamıyla yazar); modül takıma kapalıysa
 * push yok. Kişisel tercihler: takım bildiriminde çözümün kendisi, doğrudan push'ta {@link
 * UserPushRecipientResolver#preferenceSkip} (seviye / susturma; satır nedeniyle yazılır). Metinler kişinin push dilinde
 * (TR + EN), {@code PushText.pushSafe} ile değişmeyen karakterlerle, BAĞLANTISIZ ve SERBEST METİNSİZ (push uygulaması kurum
 * ağının dışında: red notu push'a yazılmaz, yalnız "ret notunu Site Monitor'de görüntüleyin" — karar 2026-10-18).
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
    static final String HEAD_TR = "[Teknik hata raporu] ";
    static final String HEAD_EN = "[Technical error report] ";
    /** Push mesaj tavanı okunamazsa (servis yok / hata) — {@code UserPushService} varsayılanı. */
    static final int DEFAULT_MESSAGE_LIMIT = 200;
    /** Push mesaj tavanının alt sınırı ({@code UserPushService.messageCharLimit}, 80–320) — çekirdek cümleler buna sığar. */
    static final int MIN_MESSAGE_LIMIT = 80;
    /** Red: çekirdek + öneri (inceleme 2026-10-19: tek parça 90 karakterdi, 80'lik tavanda kesiliyordu — öneri ayrı düşer). */
    static final String REJECT_CORE_TR = " raporu reddedildi.";
    static final String REJECT_ADVICE_TR = " Ret notu Site Monitor'de.";
    static final String REJECT_CORE_EN = " was rejected.";
    static final String REJECT_ADVICE_EN = " See note in Site Monitor.";

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
     * tur başına çağırır; anahtar TURU taşır ({@code round} = "r" + send_count — inceleme 2026-10-18: gün taşıyınca aynı turun
     * yeniden denemesi bu PO'lara her gün yeni bir push yazıyordu). Aynı turun yeniden denemesinde çağıran, ulaşılan PO'ları
     * listeden zaten çıkarır; çıkış kutusunun anahtar × kullanıcı tekilliği ikinci güvence.
     *
     * @return push'u kuyruğa alınan onaylayıcıların kimlikleri (boş = kimseye push ulaşmadı). Çağıran talebi PO BAŞINA
     *         karara bağlar: postası ya da push'u ulaşan PO ulaşılmış sayılır (inceleme 2026-10-18 — tek PO'ya giden push
     *         postası başarısız diğer PO'ların ertesi gün yeniden denenmesini engellememeli)
     */
    public Set<Long> approvalReminder(Ctx c, List<TechErrorApprovalService.Approver> approvers, Long requesterId, String requesterName,
                                      long waitingDays, Instant requestedAt, String round) {
        if (!ready(c, TechErrorReportSettings.KEY_PUSH_APPROVAL_REMINDER)) return Set.of();
        try {
            List<AppUser> people = approverUsers(approvers, requesterId);
            if (people.isEmpty()) return Set.of();
            List<UserPushService.DirectRecipient> list = new ArrayList<>();
            for (AppUser u : people) list.add(direct(u));
            String key = "te-appr-remind:" + c.reportId() + ":" + millis(requestedAt) + ":" + nz(round);
            return queuedIds(people, enqueue(list, c, TRIGGER_APPROVAL_REMINDER, reminderText(c, waitingDays, nz(requesterName)), key));
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} onay hatırlatması push'u kuyruğa alınamadı: {}", c.reportId(), e.toString());
            return Set.of();
        }
    }

    /**
     * Push'u kuyruğa alınan kişiler (kimlikle). Servis {@code queued_usernames} verir; vermeyen bir sonuçta (eski servis,
     * test sahtesi) yalnız HERKES kuyruktaysa herkes — aksi hâlde kimse (kimin aldığı bilinmeden ulaşılmış sayılmaz).
     */
    static Set<Long> queuedIds(List<AppUser> people, Map<String, Object> res) {
        Set<Long> out = new LinkedHashSet<>();
        if (res == null || people == null || people.isEmpty()) return out;
        if (res.get("queued_usernames") instanceof Collection<?> col) {
            Set<String> q = new HashSet<>();
            for (Object o : col) if (o != null) q.add(o.toString().trim());
            // Aynı kişi (2026-10-21): öbür hesabı push'u aldığı için atlanan hesap da ulaşılmış sayılır (karar kişi başına) —
            // yoksa yeniden denemede atlanan hesaba aynı hatırlatma ikinci kez giderdi.
            Map<String, String> same = new HashMap<>();
            if (res.get("same_person") instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet())
                    if (e.getKey() != null && e.getValue() != null) same.put(e.getKey().toString().trim(), e.getValue().toString().trim());
            }
            for (AppUser u : people) {
                if (u.getUsername() == null) continue;
                String name = u.getUsername().trim();
                if (q.contains(name) || (same.containsKey(name) && q.contains(same.get(name)))) out.add(u.getId());
            }
            return out;
        }
        int queued = res.get("queued") instanceof Number n ? n.intValue() : 0;
        if (queued > 0 && queued >= people.size()) for (AppUser u : people) out.add(u.getId());
        return out;
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
     * Karar → isteyen (doğrudan). Onayda yöneticilere gönderimin sonucu yazılır — her sonucun KENDİ cümlesi, ham kod asla
     * ({@link #approvedOutcome}); redde yalnız "reddedildi, ret notunu Site Monitor'de görüntüleyin" — notun KENDİSİ push'a
     * yazılmaz (karar 2026-10-18: push uygulaması kurum ağının dışında, serbest metin oraya gitmez; {@code note} parametresi
     * bilerek kullanılmaz). Metin kişinin push tavanına ({@code UserPushService.messageCharLimit}, 80–320) göre kurulur
     * ({@link #fitText}). İsteyen kararı kendisi verdiyse atlanır. İsteyen o arada takımdan ayrılmış olsa da push'u alır
     * (onayı O istedi; takım üyeliği sorulmaz — yalnız hesabın etkin olması; inceleme 2026-10-19).
     */
    public void decided(Ctx c, Long requesterId, Long deciderId, String deciderName, boolean approved, String note,
                        String sendState, String sendError, Instant decidedAt) {
        if (!decisionGoesTo(requesterId, deciderId) || !ready(c, TechErrorReportSettings.KEY_PUSH_DECISION)) return;
        try {
            AppUser u = users.findById(requesterId).orElse(null);
            if (!active(u)) return;
            String key = "te-decision:" + c.reportId() + ":" + millis(decidedAt);
            direct(List.of(direct(u)), c, TRIGGER_DECISION,
                    decisionText(c, approved, nz(deciderName), note, sendState, sendError, messageLimit()), key);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} karar push'u kuyruğa alınamadı (karar etkilenmedi): {}", c.reportId(), e.toString());
        }
    }

    static UserPushService.LocalizedText decisionText(Ctx c, boolean approved, String decider, String note, String sendState,
                                                      String sendError) {
        return decisionText(c, approved, decider, note, sendState, sendError, DEFAULT_MESSAGE_LIMIT);
    }

    /**
     * {@code limit}: push mesaj tavanı (kişi push'u bu tavanla kırpılır) — metin ona göre kurulur, önemli kısım kesilmez.
     * {@code note} push metnine GİRMEZ (yalnız imza uyumu için tutuldu).
     */
    static UserPushService.LocalizedText decisionText(Ctx c, boolean approved, String decider, String note, String sendState,
                                                      String sendError, int limit) {
        if (!approved) {
            return new UserPushService.LocalizedText(
                    fitText(c, false, REJECT_CORE_TR, REJECT_ADVICE_TR, " Reddeden: ", decider, limit),
                    fitText(c, true, REJECT_CORE_EN, REJECT_ADVICE_EN, " Rejected by: ", decider, limit));
        }
        String code = sendState != null ? sendState : sendError == null ? "FAILED" : sendError;
        String[] tr = approvedParts(code, false);
        String[] en = approvedParts(code, true);
        return new UserPushService.LocalizedText(fitText(c, false, " raporu " + tr[0], tr[1], " Onaylayan: ", decider, limit),
                fitText(c, true, " " + en[0], en[1], " Approved by: ", decider, limit));
    }

    /**
     * Onaydan sonra yöneticilere gönderimin sonucu — {@code [çekirdek cümle, öneri]} (öneri boş olabilir), her sonuca bir cümle
     * (inceleme 2026-10-18: SKIPPED / onay eskidi / zaten gönderiliyor / PDF sırası dolu hepsi "e-posta BAŞARISIZ (KOD)"
     * yazıyordu; atlanan gönderim başarısız değildir, eskiyen onay yeniden onay ister). Bilinmeyen kod "ulaşmadı" der — kod metne
     * yazılmaz. Çekirdek tavana sığdırmada asla düşmez; öneri dar tavanda (120) düşebilir. Çekirdek EN KÜÇÜK tavana (80)
     * sığacak kadar kısadır (inceleme 2026-10-19): başlık + en uzun dönem ("Ağustos 2026" / "September 2026") + " raporu " +
     * çekirdek ≤ 80 — ayrıntı öneriye taşındı (geniş tavanda görünür, darda önce o düşer).
     */
    static String[] approvedParts(String code, boolean en) {
        return switch (code == null ? "FAILED" : code) {
            case "SENT" -> en ? new String[] { "was approved and sent to the executives.", "" }
                    : new String[] { "onaylandı ve yöneticilere gönderildi.", "" };
            case "QUEUED" -> en ? new String[] { "was approved; queued for the executives.", " The mail server is retrying." }
                    : new String[] { "onaylandı; yönetici gönderimi kuyrukta.", " Posta sunucusu yeniden deniyor." };
            case "SKIPPED" -> en ? new String[] { "was approved; no e-mail to executives.",
                    " The e-mail channel is off or there is no active recipient." }
                    : new String[] { "onaylandı; yöneticilere posta gitmedi.", " E-posta kanalı kapalı ya da etkin alıcı yok." };
            case "APPROVAL_STALE" -> en ? new String[] { "was approved but changed; not sent.",
                    " It changed after the approval; submit it for approval again." }
                    : new String[] { "onaylandı ama değişti; gönderilmedi.",
                    " Onaydan sonra değişti; yeniden onaya gönderin." };
            case "SEND_IN_PROGRESS" -> en ? new String[] { "was approved; it is already being sent.",
                    " The result will show on the report in Site Monitor." }
                    : new String[] { "onaylandı; rapor zaten gönderiliyor.", " Sonuç Site Monitor'de raporda görünecek." };
            case "PDF_BUSY" -> en ? new String[] { "was approved; PDF queue busy, not sent.",
                    " The executives did not get it yet; send it again shortly." }
                    : new String[] { "onaylandı; PDF sırası dolu, gitmedi.",
                    " Yöneticilere henüz gönderilmedi; birazdan Site Monitor'den yeniden gönderin." };
            case "PDF_TOO_LARGE" -> en ? new String[] { "was approved but the PDF is too large.",
                    " It exceeds the e-mail limit; use smaller images and send again." }
                    : new String[] { "onaylandı ama PDF çok büyük, gitmedi.",
                    " PDF yöneticilere e-posta sınırını aşıyor; görselleri küçültüp yeniden gönderin." };
            default -> en ? new String[] { "was approved but the e-mail failed.",
                    " It did not reach the executives; send it again from Site Monitor." }
                    : new String[] { "onaylandı ama yöneticilere ulaşmadı.", " Raporu Site Monitor'den yeniden gönderin." };
        };
    }

    /** Sonuç cümlesinin tamamı (çekirdek + öneri) — testler ve günlük için. */
    static String approvedOutcome(String code, boolean en) {
        String[] p = approvedParts(code, en);
        return p[0] + p[1];
    }

    /**
     * Push metnini CANLI tavana ({@code limit}, 80–320) sığdırır (inceleme 2026-10-18, 2.–3. tur). Parçalar: başlık + konu
     * (kanal + dönem) + çekirdek cümle + öneri + imza. Düşme sırası: imza (tam → kısa ad → yok) → kanal adı kısalır (dönem
     * kalır, öneriyi tutmak için) → öneri düşer (kanal adı yeniden tam denenir). Başlık, dönem ve çekirdek cümle asla düşmez;
     * yalnız tavan çekirdeği bile taşıyamıyorsa (80) çıkış kutusunun kırpması devreye girer.
     */
    static String fitText(Ctx c, boolean en, String core, String advice, String byLabel, String name, int limit) {
        String head = en ? HEAD_EN : HEAD_TR;
        String full = c.subject(en);
        for (String adv : advice == null || advice.isEmpty() ? new String[] { "" } : new String[] { advice, "" }) {
            String body = head + full + core + adv;
            int over = safeLength(body) - limit;
            if (over > 0) {
                body = head + shrunkSubject(c, en, over) + core + adv;
                if (safeLength(body) > limit && !adv.isEmpty()) continue;   // öneriyle dönem bile sığmıyor → öneri düşer
            }
            return withBy(body, byLabel, name, limit);
        }
        return head + shrunkSubject(c, en, Integer.MAX_VALUE / 2) + core;   // erişilmez (son tur öneri boş)
    }

    /** Kanal adı {@code over} karakter kısalır (sözcük sınırında, "..."); çok kısa kalacaksa yalnız dönem. */
    private static String shrunkSubject(Ctx c, boolean en, int over) {
        String period = TechErrorReminderMail.period(c.year(), c.month(), en);
        String name = PushText.pushSafe(c.channelName().trim());
        int room = name == null ? 0 : name.length() - over;
        if (room < 8) return period;
        return PushText.truncate(name, room) + " " + period;
    }

    /** Gövde + imza; tam imza tavana sığmıyorsa kısa ad, o da sığmıyorsa imzasız (gövde asla kesilmez). */
    static String withBy(String body, String label, String name, int limit) {
        String full = body + byLine(label, name);
        if (safeLength(full) <= limit) return full;
        String brief = body + byLine(label, shortName(name));
        return safeLength(brief) <= limit ? brief : body;
    }

    /** Ad kısaltması: AD görünen adının parantezli bölüm / unvan kısmı atılır, en çok 40 karakter (sözcük sınırında). */
    static String shortName(String name) {
        if (name == null || name.isBlank()) return "";
        String n = name.trim();
        int p = n.indexOf(" (");
        if (p > 0) n = n.substring(0, p).trim();
        return n.length() > 40 ? PushText.truncate(n, 40) : n;
    }

    private static int safeLength(String s) {
        String safe = PushText.pushSafe(s);
        return safe == null ? 0 : safe.length();
    }

    /** Kişi push'unun tavanı — {@code UserPushService.messageCharLimit} (yönetici ayarı, 80–320); okunamazsa 200. */
    private int messageLimit() {
        try {
            int v = push.messageCharLimit();
            return v > 0 ? Math.min(320, Math.max(80, v)) : DEFAULT_MESSAGE_LIMIT;
        } catch (RuntimeException e) {
            return DEFAULT_MESSAGE_LIMIT;
        }
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
            push.enqueueTeamNoticeLocalized(c.teamId(), TRIGGER_SENT, LEVEL, MONITOR_TYPE, c.subject(false),
                    deliveredText(c, executive, nz(senderName)), sentKey(c, sentRev, claimedAt), excluded);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} gönderildi push'u kuyruğa alınamadı (gönderim etkilenmedi): {}", c.reportId(), e.toString());
        }
    }

    /** Bir teslimin ("gönderildi") tekilleştirme anahtarı: rapor + içerik sayacı + talep anı — takım ve isteyen push'u ortak. */
    static String sentKey(Ctx c, Long sentRev, Instant claimedAt) {
        return "te-sent:" + c.reportId() + ":" + (sentRev == null ? 0 : sentRev) + ":" + millis(claimedAt);
    }

    /**
     * Onaydan sonra KUYRUĞA giren yöneticilere gönderim teslim edildi (kullanıcı kararı 2026-10-19, "gitsin") → onaya gönderen
     * kişiye DOĞRUDAN "teslim edildi" push'u. Karar push'u ona "onaylandı; yöneticilere gönderim kuyrukta" demişti; teslimi
     * yalnız takım bildiriminden öğrenebiliyordu, oradan da (aynı teslim için ikinci push olmasın diye, 20.161.0) düşülüyordu —
     * hiç öğrenmiyordu. Kişi takım bildiriminden DÜŞÜLÜ kalır ({@link #delivered}'ın {@code alsoNotified}'ı): kişi başına tek
     * push. Anahtar takım bildirimiyle AYNI ({@link #sentKey}): iki pod / tekrarlanan okuma ya da dışlamanın kaçırdığı bir yol
     * çıkış kutusunun anahtar × kullanıcı tekilliğinde ikinci push yazamaz. Ayar anahtarı "Gönderildi" (sonucun olayı);
     * isteyen = gönderen ise (kendi onayı) ya da pasifse push yok (modülün öteki doğrudan push'ları gibi); opt-out / kişisel
     * tercih kendi atlama satırını yazar. Metin canlı push tavanına sığar ({@link #fitText}). Kuyrukta BAŞARISIZ sonuç bu yolu
     * kullanmaz ({@link #sendFailedTo} isteyene zaten söyler). İsteyen o arada takımdan ayrılmış olsa da push'u alır (onayı
     * O istedi; karar push'u gibi takım üyeliği sorulmaz — yalnız hesabın etkin olması; inceleme 2026-10-19).
     */
    public void queuedDeliveredToRequester(Ctx c, boolean executive, Long requesterId, Long senderId, String senderName,
                                           Long sentRev, Instant claimedAt) {
        if (requesterId == null || Objects.equals(requesterId, senderId)
                || !ready(c, TechErrorReportSettings.KEY_PUSH_DELIVERED)) return;
        try {
            AppUser u = users.findById(requesterId).orElse(null);
            if (!active(u)) return;
            direct(List.of(direct(u)), c, TRIGGER_SENT, requesterDeliveredText(c, executive, nz(senderName), messageLimit()),
                    sentKey(c, sentRev, claimedAt));
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} isteyene teslim push'u kuyruğa alınamadı (gönderim etkilenmedi): {}", c.reportId(), e.toString());
        }
    }

    /**
     * İsteyenin "teslim edildi" metni: çekirdek cümle ("… teslim edildi.") + öneri ("Kuyruktaki gönderim tamamlandı.") + imza
     * (yöneticilere gönderimde onaylayan PO, aksi hâlde gönderen). Canlı tavana {@link #fitText} ile sığar — başlık, dönem ve
     * çekirdek düşmez; çekirdek en küçük tavana (80) sığar (inceleme 2026-10-19: eskiden tek parça 98 / 103 karakterdi).
     */
    static UserPushService.LocalizedText requesterDeliveredText(Ctx c, boolean executive, String sender, int limit) {
        String tr = executive ? " raporu yöneticilere teslim edildi." : " raporu takım adresine teslim edildi.";
        String en = executive ? " was delivered to the executives." : " was delivered to the team address.";
        return new UserPushService.LocalizedText(
                fitText(c, false, tr, DELIVERED_ADVICE_TR, executive ? " Onaylayan: " : " Gönderen: ", sender, limit),
                fitText(c, true, en, DELIVERED_ADVICE_EN, executive ? " Approved by: " : " Sent by: ", sender, limit));
    }

    static final String DELIVERED_ADVICE_TR = " Kuyruktaki gönderim tamamlandı.";
    static final String DELIVERED_ADVICE_EN = " The queued send has completed.";

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
        sendFailedTo(c, executive, senderId == null ? List.of() : List.of(senderId), kind, reason, claimedAt);
    }

    /**
     * {@link #sendFailed} birden çok kişiye (inceleme 2026-10-18): onaydan sonra kuyruğa giren yöneticilere gönderim kuyrukta
     * BAŞARISIZ sonuçlanınca gönderen PO'nun yanında onaya gönderen kişiye de. Kimlikler tekilleştirilir (aynı kişi iki push
     * almaz; çıkış kutusu da anahtar × kullanıcı tekil), pasif hesap atlanır.
     */
    public void sendFailedTo(Ctx c, boolean executive, Collection<Long> recipientIds, Failure kind, String reason, Instant claimedAt) {
        Set<Long> ids = new LinkedHashSet<>();
        if (recipientIds != null) for (Long id : recipientIds) if (id != null) ids.add(id);
        if (ids.isEmpty() || !ready(c, TechErrorReportSettings.KEY_PUSH_FAILED)) return;
        try {
            Map<Long, AppUser> byId = new LinkedHashMap<>();
            for (AppUser u : users.findAllById(ids)) if (u != null) byId.put(u.getId(), u);
            List<UserPushService.DirectRecipient> list = new ArrayList<>();
            for (Long id : ids) {
                AppUser u = byId.get(id);
                if (active(u)) list.add(direct(u));
            }
            if (list.isEmpty()) return;
            Failure k = kind == null ? Failure.FAILED : kind;
            String key = "te-fail:" + c.reportId() + ":" + millis(claimedAt) + (k == Failure.UNKNOWN ? ":unknown" : "");
            direct(list, c, TRIGGER_SEND_FAILED, failedText(c, executive, k, reason), key);
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
        Map<String, Object> res = enqueue(list, c, trigger, text, key);
        return res != null && res.get("queued") instanceof Number n ? n.intValue() : 0;
    }

    private Map<String, Object> enqueue(List<UserPushService.DirectRecipient> list, Ctx c, String trigger,
                                        UserPushService.LocalizedText text, String key) {
        return push.enqueueDirectLocalized(list, c.teamId(), trigger, LEVEL, MONITOR_TYPE, c.subject(false), text, key);
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
