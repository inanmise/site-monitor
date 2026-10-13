package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.techerror.TechErrorFormat;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * Kanal teknik hata raporu e-postası (2026-10-13). Konu raporun ÇÖZÜMLENMİŞ başlığıdır ("Mobil Kanalı Teknik Hata
 * Mesajları - Eylül 2026") — ekip postayı üst yönetime olduğu gibi iletebilsin. Gövde kısadır: ilk gösterge bölümünün
 * değerleri + "PDF ektedir"; asıl içerik ekteki PDF'tedir. Bağlantısız düz metin eşi de üretilir (MailDoc).
 */
public final class TechErrorReportMail {

    private TechErrorReportMail() {}

    /** En çok 8 gösterge kutusu (ilk gösterge bölümünden, sayı / yüzde / evet-hayır). */
    static final int MAX_STATS = 8;

    public static String subject(TechErrorPdfInput in) {
        return in.title();
    }

    public static MailDoc.Mail build(TechErrorPdfInput in, String appUrl, String fileName) {
        boolean en = in.template().en();
        String lang = in.template().lang();
        String period = TechErrorTemplate.monthName(in.month(), lang) + " " + in.year();
        MailDoc d = MailDoc.create(subject(in)).wide()   // rapor ailesi 640 px (EmailResponsiveContractTest)
                .preheader((en ? "PDF report attached · " : "PDF rapor ektedir · ") + period)
                .kicker(en ? "Technical error report" : "Teknik hata raporu");
        d.badges(Badge.tint(period, Tone.INFO), Badge.outline(blank(in.channelName()) ? "—" : in.channelName()));
        d.title(in.title(), en
                ? "The " + nz(in.teamName()) + " team prepared this month's report for the " + nz(in.channelName())
                        + " channel. The full report, with its topics and charts, is in the attached PDF (" + fileName + ")."
                : nz(in.teamName()) + " takımı " + nz(in.channelName()) + " kanalının aylık raporunu hazırladı. Başlıklar ve "
                        + "grafiklerle tam rapor ekteki PDF'tedir (" + fileName + ").");
        List<Stat> stats = new ArrayList<>();
        for (Section s : in.template().sections()) {
            if (!"metrics".equals(s.kind())) continue;
            for (Field f : s.fields()) {
                if (stats.size() >= MAX_STATS) break;
                if (!"number".equals(f.type()) && !"percent".equals(f.type()) && !"yesno".equals(f.type())) continue;
                if (com.sitemonitor.service.techerror.TechErrorTemplate.exempt(f, in.values().fields())) continue;   // muaf alan
                // "Kesinti oldu mu?" gibi yönetici soru: "Evet" iken bağlı rakamlar zaten özette → yer kaplamaz;
                // "Hayır" iken (rakamlar muaf) ve cevapsızken gösterilir — e-posta PDF ile aynı şeyi söyler
                if (Boolean.TRUE.equals(in.values().fields().get(f.id())) && controlsInSection(s, f)) continue;
                stats.add(Stat.of(in.resolve(f.label()), TechErrorFormat.value(f, in.values().fields().get(f.id()), lang)));
            }
            break;   // yalnız ilk gösterge bölümü (raporun özeti)
        }
        if (!stats.isEmpty()) d.stats(stats);
        d.note(en ? "Figures were entered by the team; Site Monitor does not calculate them."
                : "Değerleri ekip girmiştir; Site Monitor bu rakamları hesaplamaz.");
        String base = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
        if (!base.isBlank()) d.button(base + "/?tab=channelreports", en ? "Open technical error reports →" : "Teknik hata raporlarını aç →");
        String why = en
                ? "You received this e-mail because it is the team address of " + nz(in.teamName()) + " and a team member sent the report from Site Monitor."
                : "Bu e-posta, " + nz(in.teamName()) + " takımının adresi olduğu ve bir takım üyesi raporu Site Monitor'den gönderdiği için geldi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String nz(String s) { return blank(s) ? "—" : s.trim(); }

    /** Bu evet/hayır sorusuna AYNI bölümde bağlı alan var mı (özet yalnız ilk gösterge bölümünü okur)? */
    private static boolean controlsInSection(Section s, Field q) {
        if (!"yesno".equals(q.type())) return false;
        for (Field f : s.fields()) if (q.id().equals(f.skipIfNo())) return true;
        return false;
    }
}
