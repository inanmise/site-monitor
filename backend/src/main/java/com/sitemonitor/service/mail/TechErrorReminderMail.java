package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.techerror.TechErrorTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Kanal teknik hata raporu hatırlatma postaları (2026-10-17, 12. sürüm — kullanıcı onayı C9 / C10). Üçü de 640 px
 * ({@code wide}), düz metin eşi MailDoc'tan, bağlantı yalnız uygulama adresi (base-url) tanımlıysa.
 *
 * <ul>
 *   <li><b>Dönem hatırlatması</b> ({@link #team}) — TAKIMA tek posta: dönem raporu açılmamış / gönderilmemiş kanallar ve
 *   her birinin durumu (onay bekleyen, reddedilen, onaydan sonra değişen ayrı cümleyle).</li>
 *   <li><b>Onay hatırlatması</b> ({@link #approval}) — PO'ya kısa posta: kim istedi, kaç gündür bekliyor; YENİ bağlantı
 *   ÜRETİLMEZ — özgün postadaki kişisel bağlantı süresi bitene kadar geçerlidir (bitiş tarihi yazılır).</li>
 *   <li><b>Diğer onaylayıcılara bilgi</b> ({@link #approverInfo}) — "rapor X tarafından onaylandı / reddedildi; işlem
 *   gerekmez".</li>
 * </ul>
 */
public final class TechErrorReminderMail {

    private TechErrorReminderMail() {}

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(IST);

    /**
     * Kanalın dönem durumu (hatırlatmada): rapor yok / taslak / PO onayı bekliyor / reddedildi / onaylandı ama yöneticilere
     * gönderilmedi / onaydan sonra değişti. {@code approvalFlow}: kanalın yönetici alıcısı var (taslak cümlesi "onaya gönderin").
     */
    public enum State { MISSING, DRAFT, PENDING, REJECTED, APPROVED_UNSENT, STALE, QUEUED_UNKNOWN }

    public record Item(String channelName, State state, boolean approvalFlow) { }

    public static String period(int year, int month, boolean en) {
        return TechErrorTemplate.monthName(month, en ? "en" : "tr") + " " + year;
    }

    public static String teamSubject(int year, int month, boolean en) {
        return teamSubject(year, month, en, null);
    }

    /**
     * Takım hatırlatmasının konusu: dönem + TAKIM ADI (inceleme 2026-10-18) — posta servisi 421 yeniden denemesinin sonucunu
     * günlük satırına KONUYLA bulur; aynı koşuda her takıma aynı konu giderse sonuç başka takımın satırına yazılabilirdi.
     */
    public static String teamSubject(int year, int month, boolean en, String teamName) {
        String suffix = teamName == null || teamName.isBlank() ? "" : " · " + teamName.trim();
        return en ? "[Site Monitor] Technical error report reminder — " + period(year, month, true) + suffix
                : "[Site Monitor] Teknik hata raporu hatırlatması — " + period(year, month, false) + suffix;
    }

    /**
     * Konuyu rapora özgü kılar: özel bir başlık kalıbı kanalı ya da dönemi taşımıyorsa onlar eklenir (yeniden deneme sonucu
     * konuyla eşlenir — iki raporun aynı konusu olmasın). Varsayılan başlık ("{kanal} Kanalı … {ay} {yil}") olduğu gibi kalır.
     */
    public static String uniqueTitle(String title, String channelName, String periodLabel) {
        String t = nz(title);
        if (channelName != null && !channelName.isBlank() && !t.contains(channelName.trim())) t += " · " + channelName.trim();
        if (periodLabel != null && !periodLabel.isBlank() && !t.contains(periodLabel.trim())) t += " · " + periodLabel.trim();
        return t;
    }

    public static String stateText(Item i, boolean en) {
        return switch (i.state()) {
            case MISSING -> en ? "Report not started" : "Rapor açılmadı";
            case DRAFT -> i.approvalFlow() ? (en ? "Draft — not submitted for approval" : "Taslak — onaya gönderilmedi")
                    : (en ? "Draft — not sent" : "Taslak — gönderilmedi");
            case PENDING -> en ? "Awaiting PO approval" : "PO onayı bekliyor";
            case REJECTED -> en ? "Rejected — fix it and submit it again" : "Reddedildi — düzeltip yeniden onaya gönderin";
            case APPROVED_UNSENT -> en ? "Approved — not sent to the executives yet" : "Onaylandı — yöneticilere henüz gönderilmedi";
            case STALE -> en ? "Changed during approval — submit it again" : "Onay sürecinde değişti — yeniden onaya gönderin";
            case QUEUED_UNKNOWN -> en ? "The send result never arrived — send the report again"
                    : "Gönderim sonucu alınamadı — raporu yeniden gönderin";
        };
    }

    private static Tone stateTone(State s) {
        return switch (s) {
            case MISSING, REJECTED, QUEUED_UNKNOWN -> Tone.DESTRUCTIVE;
            case PENDING -> Tone.INFO;
            default -> Tone.WARNING;
        };
    }

    /** Takımın kanal raporları sayfası (takım süzgeçli); listede yalnız "başlanmamış" varsa o süzgeçle. */
    public static String channelsUrl(String appUrl, Long teamId, List<Item> items) {
        if (appUrl == null || appUrl.isBlank()) return null;
        boolean allMissing = items != null && !items.isEmpty() && items.stream().allMatch(i -> i.state() == State.MISSING);
        boolean noneMissing = items != null && items.stream().noneMatch(i -> i.state() == State.MISSING);
        String st = allMissing ? "&te_st=missing" : noneMissing ? "&te_st=open" : "";
        return appUrl.replaceAll("/+$", "") + "/?tab=channelreports" + st + (teamId == null ? "" : "&te_team=" + teamId);
    }

    /**
     * Dönem hatırlatması (takıma). {@code repeatDays} 0 = yalnız bir kez; {@code day} ayın kaçıncı günü başladığı (dipnot).
     */
    public static MailDoc.Mail team(String teamName, Long teamId, int year, int month, List<Item> items, boolean en,
                                    String appUrl, int day, int repeatDays) {
        List<Item> all = items == null ? List.of() : items;
        String p = period(year, month, en);
        long pending = all.stream().filter(i -> i.state() == State.PENDING).count();
        MailDoc d = MailDoc.create(teamSubject(year, month, en, teamName)).wide()
                .preheader(en ? p + ": " + all.size() + " channel report(s) still to send" : p + " dönemi: " + all.size() + " kanalın raporu bekliyor")
                .kicker(en ? "Technical error report · reminder" : "Teknik hata raporu · hatırlatma");
        d.badges(Badge.tint(en ? all.size() + " CHANNEL(S)" : all.size() + " KANAL", Tone.WARNING), Badge.outline("Site Monitor"));
        String team = nz(teamName);
        d.title(en ? p + " reports are waiting" : p + " raporları bekliyor",
                en ? "Hello " + team + ", the " + p + " technical error report has not been sent yet for the channels below. Open each "
                        + "report, complete it and send it; on channels with executive recipients the report goes to the PO for approval first."
                   : "Merhaba " + team + ", aşağıdaki kanallarda " + p + " teknik hata raporu henüz gönderilmedi. Raporu açıp tamamlayın ve "
                        + "gönderin; yönetici alıcısı tanımlı kanallarda rapor önce PO onayına gider.");
        List<List<Cell>> cells = new ArrayList<>();
        for (Item i : all) {
            cells.add(List.of(Cell.of(i.channelName(), null, true), Cell.of(stateText(i, en), stateTone(i.state()).strong, true)));
        }
        if (!cells.isEmpty()) d.table(List.of(Col.of(en ? "Channel" : "Kanal"), Col.of(en ? "Status" : "Durum")), cells);
        if (pending > 0) {
            d.note(en ? "Reports awaiting approval need no action from the team: the PO gets a separate reminder."
                    : "Onay bekleyen raporlar için takımın yapacağı bir şey yok: PO'ya ayrıca hatırlatma gider.");
        }
        String url = channelsUrl(appUrl, teamId, all);
        if (url != null) d.button(url, en ? "Open channel reports →" : "Kanal raporlarını aç →");
        String why = en
                ? "You received this e-mail because your team is enrolled in technical error report reminders (Settings → Technical error report). "
                        + "The reminder starts on day " + day + " of the month and " + (repeatDays <= 0 ? "comes only once." : "repeats every " + repeatDays + " day(s) until the report is sent.")
                : "Bu e-posta, takımınız teknik hata raporu hatırlatmasına açık olduğu için gönderildi (Ayarlar → Teknik hata raporu). "
                        + "Hatırlatma ayın " + day + ". gününden itibaren " + (repeatDays <= 0 ? "yalnız bir kez gelir." : "rapor gönderilene kadar her " + repeatDays + " günde bir gelir.");
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    // ── Onay hatırlatması ────────────────────────────────────────────────────────────────────────────

    public static String approvalSubject(String title, boolean en) {
        return en ? "[Site Monitor] Technical error report awaiting approval — " + nz(title)
                : "[Site Monitor] Onay bekleyen teknik hata raporu — " + nz(title);
    }

    /**
     * Onay hatırlatması (PO'ya). {@code linkValidUntil}: bu onaylayıcının özgün bağlantısı geçerliyse bitişi, değilse null
     * (süresi doldu / bağlantı yok — uygulamada karar verilir). {@code waitingDays}: istekten bu yana gün.
     */
    public static MailDoc.Mail approval(String title, boolean en, String approverName, String requesterName, Instant requestedAt,
                                        long waitingDays, Instant linkValidUntil, String reportUrl, String teamName, int everyDays) {
        return approval(title, en, approverName, requesterName, requestedAt, waitingDays, linkValidUntil, false, reportUrl, teamName, everyDays);
    }

    /**
     * {@code linkUnconfirmed}: bu onaylayıcının GEÇERLİ bir bağlantısı var ama postasının ulaştığı bilinmiyor (20.153'ten
     * önceki istekler damgasızdır) — not "süresi doldu / size gönderilmedi" iddiasında bulunmaz, yalnız Site Monitor'ü gösterir.
     */
    public static MailDoc.Mail approval(String title, boolean en, String approverName, String requesterName, Instant requestedAt,
                                        long waitingDays, Instant linkValidUntil, boolean linkUnconfirmed, String reportUrl,
                                        String teamName, int everyDays) {
        String who = requesterName == null || requesterName.isBlank() ? (en ? "A team member" : "Bir takım üyesi") : requesterName.trim();
        String when = requestedAt == null ? "—" : DT.format(requestedAt);
        MailDoc d = MailDoc.create(approvalSubject(title, en)).wide()
                .preheader(en ? "Waiting for your decision for " + waitingDays + " day(s)" : waitingDays + " gündür kararınızı bekliyor")
                .kicker(en ? "Technical error report · approval reminder" : "Teknik hata raporu · onay hatırlatması");
        d.badges(Badge.solid(en ? "Approval needed" : "Onay bekliyor", Tone.WARNING));
        d.title(nz(title), en
                ? "Hello " + nz(approverName) + ", " + who + " submitted this report for your approval on " + when + "; it has been waiting for "
                        + waitingDays + " day(s). Approving sends it to the channel's executive recipients; rejecting returns it to the team with your note."
                : "Merhaba " + nz(approverName) + ", " + who + " bu raporu " + when + " tarihinde onayınıza sundu; " + waitingDays
                        + " gündür karar bekliyor. Onaylarsanız rapor kanalın yönetici alıcılarına gider; reddederseniz notunuzla ekibe döner.");
        if (linkValidUntil != null) {
            d.note(en ? "The personal link in the original approval e-mail still works until " + DT.format(linkValidUntil)
                            + " (Istanbul time) — you can decide from there without signing in."
                    : "Özgün onay postasındaki kişisel bağlantı " + DT.format(linkValidUntil)
                            + " (İstanbul saati) tarihine kadar geçerli — oradan giriş yapmadan karar verebilirsiniz.");
        } else if (linkUnconfirmed) {
            d.note(en ? "Open the report in Site Monitor to decide; if you still have the original approval e-mail, its personal link may work too."
                    : "Karar vermek için raporu Site Monitor'de açın; özgün onay postası elinizdeyse oradaki kişisel bağlantı da çalışıyor olabilir.");
        } else {
            d.note(en ? "The link in the original approval e-mail has expired or was not sent to you; open the report in Site Monitor to decide."
                    : "Özgün onay postasındaki bağlantının süresi doldu ya da size gönderilmedi; karar vermek için raporu Site Monitor'de açın.");
        }
        if (reportUrl != null) d.button(reportUrl, en ? "Open in Site Monitor →" : "Site Monitor'de aç →");
        String why = en
                ? "You received this e-mail because you are a PO of the " + nz(teamName) + " team and the report has been awaiting approval. "
                        + "This reminder comes every " + everyDays + " day(s) until a decision is made."
                : "Bu e-posta, " + nz(teamName) + " takımının PO rolündeki üyesi olduğunuz ve rapor onay beklediği için gönderildi. "
                        + "Hatırlatma, karar verilene kadar her " + everyDays + " günde bir gelir.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    // ── Diğer onaylayıcılara bilgi ───────────────────────────────────────────────────────────────────

    public static String infoSubject(String title, boolean en, boolean approved) {
        if (en) return "[Site Monitor] " + (approved ? "Approved" : "Rejected") + ", no action needed — " + nz(title);
        return "[Site Monitor] " + (approved ? "Onaylandı" : "Reddedildi") + ", işlem gerekmez — " + nz(title);
    }

    public static MailDoc.Mail approverInfo(String title, boolean en, boolean approved, String deciderName, Instant decidedAt,
                                            String note, String reportUrl) {
        String who = nz(deciderName);
        String when = decidedAt == null ? "" : " (" + DT.format(decidedAt) + ")";
        MailDoc d = MailDoc.create(infoSubject(title, en, approved)).wide()
                .preheader(en ? (approved ? "Approved by " : "Rejected by ") + who + " — no action needed"
                        : who + (approved ? " onayladı" : " reddetti") + " — işlem gerekmez")
                .kicker(en ? "Technical error report · approval" : "Teknik hata raporu · onay");
        d.badges(approved ? Badge.tint(en ? "Approved" : "Onaylandı", Tone.SUCCESS) : Badge.tint(en ? "Rejected" : "Reddedildi", Tone.NEUTRAL));
        d.title(nz(title), en
                ? "The report was " + (approved ? "approved" : "rejected") + " by " + who + when + "; no action is needed from you."
                : "Rapor " + who + " tarafından " + (approved ? "onaylandı" : "reddedildi") + when + "; sizin bir işlem yapmanız gerekmez.");
        if (note != null && !note.isBlank()) d.card(en ? "Note" : "Not", null, MailKit.escBr(note), note);
        if (reportUrl != null) d.button(reportUrl, en ? "Open the report →" : "Raporu aç →", Variant.OUTLINE);
        String why = en ? "You received this e-mail because you are one of the report's approvers; another approver made the decision."
                : "Bu e-posta, raporun onaylayıcılarından biri olduğunuz için gönderildi; kararı başka bir onaylayıcı verdi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }
}
