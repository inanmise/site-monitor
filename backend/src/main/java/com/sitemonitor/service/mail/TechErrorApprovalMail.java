package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.techerror.TechErrorPdfInput;

import java.util.List;

/**
 * PO onayı postaları (2026-10-17, S6). İSTEK postası: raporun tam gövdesi ({@link TechErrorReportMail#buildFull}) — PO
 * raporu postada okur — başında onay kartı: kim istedi, onaylanınca kaç yöneticiye gideceği, onay / red bağlantısı
 * (girişsiz sayfa, onaylayıcıya özel, 14 gün) ve "Site Monitor'de aç". KARAR postası isteyene: onaylandı / reddedildi,
 * not, yöneticilere gönderimin sonucu. Bağlantılar yalnız uygulama adresi tanımlıysa (base-url) basılır.
 */
public final class TechErrorApprovalMail {

    private TechErrorApprovalMail() {}

    public static String requestSubject(TechErrorPdfInput in) {
        return (in.template().en() ? "[Approval needed] " : "[Onay bekliyor] ") + in.title();
    }

    public static String decisionSubject(String title, boolean en, boolean approved) {
        String tag = en ? (approved ? "[Approved] " : "[Rejected] ") : (approved ? "[Onaylandı] " : "[Reddedildi] ");
        return tag + title;
    }

    /** Rapor sayfasının uygulama içi adresi (kanal + rapor parametreleriyle). */
    public static String reportUrl(String appUrl, Long channelId, Long reportId) {
        if (appUrl == null || appUrl.isBlank()) return null;
        return appUrl.replaceAll("/+$", "") + "/?tab=channelreports&te_view=report&te_ch=" + channelId + "&te_rep=" + reportId;
    }

    /** Onay isteği: tam rapor gövdesi + başta onay kartı. {@code decideUrl} null ise bağlantı yerine uygulama içi yönlendirme. */
    public static TechErrorReportMail.Built request(TechErrorPdfInput in, TechErrorMailImages.Result prepared, String appUrl,
                                                    String fileName, Long reportId, Long channelId, String requesterName,
                                                    String approverName, int executiveCount, String decideUrl, int ttlDays) {
        boolean en = in.template().en();
        String reportUrl = reportUrl(appUrl, channelId, reportId);
        String who = requesterName == null || requesterName.isBlank() ? (en ? "A team member" : "Bir takım üyesi") : requesterName;
        String body = en
                ? who + " submitted this report for your approval. When you approve it, Site Monitor sends it to the "
                        + executiveCount + " executive recipient(s) of the channel at once (the team address in CC). If you reject it, "
                        + "the team gets your note and the report goes back to draft. The link below is personal and valid for " + ttlDays + " days."
                : who + " bu raporu onayınıza sundu. Onaylarsanız Site Monitor raporu kanalın " + executiveCount
                        + " yönetici alıcısına hemen gönderir (takım adresi bilgide). Reddederseniz ekip notunuzu alır ve rapor taslağa döner. "
                        + "Aşağıdaki bağlantı size özeldir ve " + ttlDays + " gün geçerlidir.";
        TechErrorReportMail.Intro intro = d -> {
            d.badges(Badge.solid(en ? "Approval needed" : "Onay bekliyor", Tone.WARNING));
            d.alert(Tone.WARNING, en ? "Your approval is needed, " + nz(approverName) : "Onayınız bekleniyor, " + nz(approverName), body);
            if (decideUrl != null) {
                d.buttons(en ? "Decide from this e-mail (no sign-in needed):" : "Bu postadan karar verin (giriş gerekmez):",
                        List.of(new Btn(decideUrl, en ? "Approve or reject →" : "Onayla ya da reddet →", Variant.PRIMARY)));
            } else {
                d.note(en ? "Open the report in Site Monitor to approve or reject it."
                        : "Onaylamak ya da reddetmek için raporu Site Monitor'de açın.");
            }
            if (reportUrl != null) d.button(reportUrl, en ? "Open in Site Monitor" : "Site Monitor'de aç", Variant.OUTLINE);
        };
        return TechErrorReportMail.buildFull(in, prepared, appUrl, fileName, intro, TechErrorReportMail.Audience.APPROVER);
    }

    /** Karar postası (isteyene). {@code sendState}: SENT | QUEUED | SKIPPED | FAILED | <hata kodu>; red için null. */
    public static MailDoc.Mail decision(String title, boolean en, boolean approved, String note, String deciderName,
                                        String sendState, List<String> sentTo, String appUrl, Long reportId, Long channelId) {
        MailDoc d = MailDoc.create(decisionSubject(title, en, approved)).wide()
                .preheader(en ? (approved ? "Approved by " : "Rejected by ") + nz(deciderName) : nz(deciderName) + (approved ? " onayladı" : " reddetti"))
                .kicker(en ? "Technical error report · approval" : "Teknik hata raporu · onay");
        d.badges(approved ? Badge.solid(en ? "Approved" : "Onaylandı", Tone.SUCCESS) : Badge.solid(en ? "Rejected" : "Reddedildi", Tone.DESTRUCTIVE));
        d.title(title, en ? (approved ? "Approved by " : "Rejected by ") + nz(deciderName) : nz(deciderName) + (approved ? " onayladı." : " reddetti."));
        if (approved) {
            if ("SENT".equals(sendState) || "QUEUED".equals(sendState)) {
                String to = sentTo == null || sentTo.isEmpty() ? "" : " (" + String.join(", ", sentTo) + ")";
                d.paragraph(en
                        ? ("SENT".equals(sendState) ? "The report was sent to the executive recipients" : "The report was handed to the mail server for the executive recipients") + to + "."
                        : ("SENT".equals(sendState) ? "Rapor yönetici alıcılara gönderildi" : "Rapor yönetici alıcılar için posta sunucusuna verildi") + to + ".");
            } else {
                d.alert(Tone.DESTRUCTIVE, en ? "Not sent yet" : "Henüz gönderilmedi", en
                        ? "The approval is recorded, but the mail to the executive recipients could not be sent (" + nz(sendState) + "). Open the report and use \"Send to executives\" to try again."
                        : "Onay kaydedildi; ancak yönetici alıcılara posta gönderilemedi (" + nz(sendState) + "). Raporu açıp \"Yöneticilere gönder\" ile yeniden deneyin.");
            }
        } else {
            d.paragraph(en ? "The report is back in draft. Fix it according to the note and submit it for approval again."
                    : "Rapor taslağa döndü. Nota göre düzeltip yeniden onaya gönderin.");
        }
        if (note != null && !note.isBlank()) d.card(en ? "Note" : "Not", null, MailKit.escBr(note), note);
        String url = reportUrl(appUrl, channelId, reportId);
        if (url != null) d.button(url, en ? "Open the report →" : "Raporu aç →");
        String why = en ? "You received this e-mail because you submitted the report for approval in Site Monitor."
                : "Bu e-posta, raporu Site Monitor'de onaya siz gönderdiğiniz için geldi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }
}
