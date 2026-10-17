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
        boolean tilesShown = false;
        for (Section s : in.template().sections()) {
            if (!"summary".equals(s.kind())) continue;
            tilesShown = summary(d, in, s);
            break;   // yalnız ilk özet
        }
        List<Stat> stats = new ArrayList<>();
        boolean first = true;
        // Özette renkli kutucuk şeridi varsa (2026-10-17, 4b) düz gösterge kutuları yinelenmez — şerit yoksa eski zincir
        for (Section s : tilesShown ? List.<Section>of() : in.template().sections()) {
            if (!"metrics".equals(s.kind())) continue;
            // Özete gömülü bölüm (2026-10-17) kutucuk zincirine girmez: rakamı özet cümlesinde, açıklaması cümlenin altında
            if (TechErrorTemplate.hostOf(in.template(), s) != null) continue;
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
     * Yönetici özeti PDF'teki bloklarla (2026-10-17, {@link TechErrorSummary#blocks}): metin paragrafları, gömülü bölümlerin
     * açıklamaları ilgili cümlenin altında — küçük kalın başlık = alanın adı (ad boşsa "Açıklama") + girintili metin (sol
     * şerit yok). Hiç metin ve not yoksa başlık da basılmaz.
     */
    private static boolean summary(MailDoc d, TechErrorPdfInput in, Section s) {
        boolean en = in.template().en();
        List<TechErrorSummary.Block> blocks = TechErrorSummary.blocks(s, in.template(), in.values(), in.previousOrNull(),
                in.channelName(), in.year(), in.month());
        List<com.sitemonitor.service.techerror.TechErrorTiles.Tile> tiles = com.sitemonitor.service.techerror.TechErrorTiles.build(
                in.template(), s, in.values(), in.previousOrNull(), in.channelName(), in.year(), in.month());
        StringBuilder html = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        for (TechErrorSummary.Block b : blocks) {
            if (!b.note() && TechErrorRichText.blank(b.text())) continue;
            if (!plain.isEmpty()) plain.append("\n\n");
            if (b.note()) {
                String label = b.label() == null || b.label().isBlank() ? (en ? "Note" : "Açıklama") : b.label();
                html.append("<div style=\"padding-left:16px;margin:2px 0 10px\">")
                    .append("<p style=\"margin:0 0 2px;font-family:").append(MailTokens.FONT)
                    .append(";font-size:13px;line-height:18px;font-weight:bold;color:").append(MailTokens.MUTED).append("\">")
                    .append(MailKit.esc(label)).append("</p>")
                    .append(richHtml(b.text(), 14, 22)).append("</div>");
                plain.append(label).append(":\n").append(TechErrorRichText.plain(b.text()));
            } else {
                html.append(html.isEmpty() ? "<div>" : "<div style=\"margin-top:8px\">").append(richHtml(b.text())).append("</div>");
                plain.append(TechErrorRichText.plain(b.text()));
            }
        }
        if (html.isEmpty() && tiles.isEmpty()) return false;
        d.heading(in.resolve(s.title()));
        if (!tiles.isEmpty()) d.raw(tilesHtml(tiles, en), tilesPlain(tiles, en));
        if (!html.isEmpty()) d.raw(html.toString(), plain.toString());
        return !tiles.isEmpty();
    }

    /** Ton → {zemin, yazı} (PDF ile aynı palet: iyi yeşil, kötü kırmızı, nötr mavi, bilinmeyen gri). */
    static String[] tileColors(String tone) {
        return switch (tone) {
            case "good" -> new String[] { "#dcfce7", "#166534" };
            case "bad" -> new String[] { "#fee2e2", "#991b1b" };
            case "neutral" -> new String[] { "#dbeafe", "#1e3a8a" };
            default -> new String[] { "#f4f4f5", "#52525b" };
        };
    }

    /** Kutucuğun alt satırı: ok + fark, varsa "· hedef %99,95"; ikisi de yoksa null. */
    static String tileSub(com.sitemonitor.service.techerror.TechErrorTiles.Tile tile, boolean en) {
        StringBuilder sub = new StringBuilder();
        if (tile.delta() != null) {
            if ("up".equals(tile.dir())) sub.append("▲ ");
            else if ("down".equals(tile.dir())) sub.append("▼ ");
            sub.append(tile.delta());
        }
        if (tile.target() != null) {
            if (!sub.isEmpty()) sub.append(" · ");
            sub.append(en ? "target " : "hedef ").append(tile.target());
        }
        return sub.isEmpty() ? null : sub.toString();
    }

    /**
     * Renkli kutucuk şeridi (2026-10-17, 4b): satırda 3 (Outlook'ta mso tablo hücreleri, diğerlerinde satır içi bloklar —
     * {@code MailKit.stats} düzeni), zemin ve yazı rengi tondan; dar ekranda {@code tile3} sınıfı alt alta diziyor.
     */
    static String tilesHtml(List<com.sitemonitor.service.techerror.TechErrorTiles.Tile> tiles, boolean en) {
        int n = tiles.size();
        int perRow = Math.min(3, n);
        String pct = perRow == 3 ? "33.33%" : (100 / perRow) + "%";
        // Dar ekran sınıfı satırdaki kutucuk sayısına göre (MailKit.stats ile aynı): tek kutucuk sınıfsız, iki → tile, üç → tile3
        String cls = perRow == 3 ? " class=\"tile3\"" : perRow == 2 ? " class=\"tile\"" : "";
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" data-slot=\"te-tiles\"><tr><td style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (int i = 0; i < n; i++) {
            var tile = tiles.get(i);
            if (i > 0 && i % perRow == 0) sb.append("<!--[if mso]></tr><tr><![endif]-->");
            String[] col = tileColors(tile.tone());
            sb.append("<!--[if mso]><td width=\"").append(pct).append("\" valign=\"top\"><![endif]-->")
              .append("<div").append(cls).append(" style=\"display:inline-block;vertical-align:top;width:100%;min-width:160px;max-width:").append(pct).append("\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:0 8px 8px 0\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td bgcolor=\"").append(col[0]).append("\" data-tone=\"").append(tile.tone()).append("\" style=\"background-color:").append(col[0])
              .append(";border-radius:8px;padding:12px 14px;font-family:").append(MailTokens.FONT).append("\">")
              .append("<p style=\"margin:0;font-size:12px;line-height:16px;font-weight:500;color:").append(col[1]).append("\">").append(MailKit.esc(tile.label())).append("</p>")
              .append("<p style=\"margin:4px 0 0;font-size:22px;line-height:28px;font-weight:700;color:").append(col[1])
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(MailKit.esc(tile.value())).append("</p>");
            String sub = tileSub(tile, en);
            if (sub != null) {
                sb.append("<p style=\"margin:2px 0 0;font-size:12px;line-height:16px;color:").append(col[1]).append("\">").append(MailKit.esc(sub)).append("</p>");
            }
            sb.append("</td></tr></table></td></tr></table></div><!--[if mso]></td><![endif]-->");
        }
        sb.append("<!--[if mso]></tr></table><![endif]--></td></tr></table>");
        return sb.toString();
    }

    static String tilesPlain(List<com.sitemonitor.service.techerror.TechErrorTiles.Tile> tiles, boolean en) {
        StringBuilder t = new StringBuilder();
        for (var tile : tiles) {
            t.append(tile.label()).append(": ").append(tile.value());
            String sub = tileSub(tile, en);
            if (sub != null) t.append(" (").append(sub).append(')');
            t.append('\n');
        }
        return t.toString();
    }

    /**
     * Zengin metnin e-posta HTML'i: paragraf satırları {@code <p>}, maddeler asılı girintili satır (liste etiketi yok —
     * Outlook {@code <ul>} girintisini bozar), kalın {@code <strong>}. Her metin kaçırılır.
     */
    static String richHtml(String text) {
        return richHtml(text, 15, 24);
    }

    /** {@link #richHtml(String)} verilen yazı boyutu / satır yüksekliğiyle (özet notu bir punto küçük). */
    static String richHtml(String text, int fontPx, int lineHeightPx) {
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
             .append("font-family:").append(MailTokens.FONT).append(";font-size:").append(fontPx).append("px;line-height:")
             .append(lineHeightPx).append("px;color:").append(MailTokens.FG)
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
