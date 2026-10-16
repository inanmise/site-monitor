package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.techerror.TechErrorFormat;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorRichText;
import com.sitemonitor.service.techerror.TechErrorSummary;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * Kanal teknik hata raporu e-postası (2026-10-13). Konu raporun ÇÖZÜMLENMİŞ başlığıdır ("Mobil Kanalı Teknik Hata
 * Mesajları - Eylül 2026") — ekip postayı üst yönetime olduğu gibi iletebilsin. Gövde kısadır: ilk gösterge bölümünün
 * değerleri + "PDF ektedir"; asıl içerik ekteki PDF'tedir. Bağlantısız düz metin eşi de üretilir (MailDoc).
 * 2026-10-14: şablonda yönetici özeti varsa gövde onunla açılır (PDF'teki metnin aynısı; madde ve kalın yazı korunur).
 */
public final class TechErrorReportMail {

    private TechErrorReportMail() {}

    /**
     * En çok bu kadar gösterge kutusu (sayı / yüzde / evet-hayır): ilk gösterge bölümü (girilen rakamlar) + hemen ardındaki
     * yalnız otomatik oranlardan oluşan bölüm (varsayılanda 8 + 7).
     */
    static final int MAX_STATS = 16;

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
        for (Section s : in.template().sections()) {
            if (!"summary".equals(s.kind())) continue;
            String text = TechErrorSummary.effective(s, in.template(), in.values(), in.channelName(), in.year(), in.month());
            if (text != null && !text.isBlank()) {
                d.heading(in.resolve(s.title()));
                d.raw(richHtml(text), TechErrorRichText.plain(text));
            }
            break;   // yalnız ilk özet
        }
        List<Stat> stats = new ArrayList<>();
        boolean first = true;
        for (Section s : in.template().sections()) {
            if (!"metrics".equals(s.kind())) continue;
            // İlk gösterge bölümü (raporun özeti) + ardından gelen YALNIZ otomatik oranlardan oluşan bölüm(ler) — 2026-10-16:
            // oranlar artık ayrı bölümde hesaplanıyor, teknik hata oranı e-postanın kutucuklarından düşmesin
            if (!first && !allComputed(s)) break;
            first = false;
            for (Field f : s.fields()) {
                if (stats.size() >= MAX_STATS) break;
                if (!"number".equals(f.type()) && !"percent".equals(f.type()) && !"yesno".equals(f.type())) continue;
                if (com.sitemonitor.service.techerror.TechErrorTemplate.exempt(f, in.values().fields())) continue;   // muaf alan
                // "Kesinti oldu mu?" gibi yönetici soru: "Evet" iken bağlı rakamlar zaten özette → yer kaplamaz;
                // "Hayır" iken (rakamlar muaf) ve cevapsızken gösterilir — e-posta PDF ile aynı şeyi söyler
                if (Boolean.TRUE.equals(in.values().fields().get(f.id())) && controlsInSection(s, f)) continue;
                stats.add(Stat.of(in.resolve(f.label()), TechErrorFormat.value(f, in.values().fields().get(f.id()), lang)));
            }
        }
        if (!stats.isEmpty()) d.stats(stats);
        if (com.sitemonitor.service.techerror.TechErrorCalc.anyRatioComputed(in.template(), in.values().fields())) {
            d.note(en ? "Counts were entered by the team; Site Monitor calculated the rates from those counts."
                    : "Adetleri ekip girmiştir; oranları Site Monitor bu adetlerden hesaplamıştır.");
        } else {
            d.note(en ? "Figures were entered by the team; Site Monitor does not calculate them."
                    : "Değerleri ekip girmiştir; Site Monitor bu rakamları hesaplamaz.");
        }
        String base = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
        if (!base.isBlank()) d.button(base + "/?tab=channelreports", en ? "Open technical error reports →" : "Teknik hata raporlarını aç →");
        String why = en
                ? "You received this e-mail because it is the team address of " + nz(in.teamName()) + " and a team member sent the report from Site Monitor."
                : "Bu e-posta, " + nz(in.teamName()) + " takımının adresi olduğu ve bir takım üyesi raporu Site Monitor'den gönderdiği için geldi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — technical error report" : "Site Monitor — teknik hata raporu", "Site Monitor");
        return d.build();
    }

    /**
     * Zengin metnin e-posta HTML'i: paragraf satırları {@code <p>}, maddeler asılı girintili satır (liste etiketi yok —
     * Outlook {@code <ul>} girintisini bozar), kalın {@code <strong>}. Her metin kaçırılır.
     */
    static String richHtml(String text) {
        StringBuilder b = new StringBuilder("<div>");
        for (TechErrorRichText.Block bl : TechErrorRichText.parse(text)) {
            if (bl.gap()) {
                b.append("<div style=\"height:10px;line-height:10px;font-size:1px\">&nbsp;</div>");
                continue;
            }
            StringBuilder inner = new StringBuilder();
            for (TechErrorRichText.Run r : bl.runs()) {
                String e = MailKit.esc(r.text());
                inner.append(r.bold() ? "<strong>" + e + "</strong>" : e);
            }
            int pad = bl.level() * 18 + (bl.item() ? 16 : 0);
            String marker = bl.item() ? (bl.marker() != null ? MailKit.esc(bl.marker()) : bl.level() == 0 ? "&#8226;" : bl.level() == 1 ? "&#8211;" : "&#183;") + "&nbsp;" : "";
            b.append("<p style=\"margin:0 0 ").append(bl.item() ? 4 : 2).append("px;padding-left:").append(pad)
             .append("px;").append(bl.item() ? "text-indent:-16px;" : "")
             .append("font-family:").append(MailTokens.FONT).append(";font-size:15px;line-height:24px;color:").append(MailTokens.FG)
             .append(";word-break:break-word;overflow-wrap:anywhere\">")
             .append(marker).append(inner).append("</p>");
        }
        return b.append("</div>").toString();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String nz(String s) { return blank(s) ? "—" : s.trim(); }

    /** Bölümün bütün alanları otomatik hesaplanıyor mu (varsayılanın "Oranları (otomatik)" bölümü). */
    private static boolean allComputed(Section s) {
        if (s.fields().isEmpty()) return false;
        for (Field f : s.fields()) if (f.calc() == null) return false;
        return true;
    }

    /** Bu evet/hayır sorusuna AYNI bölümde bağlı alan var mı? */
    private static boolean controlsInSection(Section s, Field q) {
        if (!"yesno".equals(q.type())) return false;
        for (Field f : s.fields()) if (q.id().equals(f.skipIfNo())) return true;
        return false;
    }
}
