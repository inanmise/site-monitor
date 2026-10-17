package com.sitemonitor.service.mail;

import com.sitemonitor.service.EmailNotificationService.InlineImage;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.techerror.TechErrorCalc;
import com.sitemonitor.service.techerror.TechErrorFormat;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorRichText;
import com.sitemonitor.service.techerror.TechErrorSummary;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Column;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
import com.sitemonitor.service.techerror.TechErrorTemplate.TableRow;
import com.sitemonitor.service.techerror.TechErrorTiles;
import com.sitemonitor.service.techerror.TechErrorTiles.Tile;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kanal teknik hata raporu e-postası (2026-10-13; yeniden tasarım 2026-10-17, kullanıcı kararı S5: "Outlook'ta çok dar;
 * PDF içeriğinin benzeri e-postanın İÇİNDE, geniş alanda, yönetici sunumu gibi"). Konu raporun ÇÖZÜMLENMİŞ başlığıdır
 * ("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026") — ekip postayı üst yönetime olduğu gibi iletebilsin.
 *
 * <p>Düzen: kart {@link MailTokens#WIDTH_XWIDE} px (telefonda tek sütun — {@code .stack}); PDF kapağındaki lacivert başlık
 * bandı (başlık, takım · kanal · dönem, hazırlayan / üretim); ardından ŞABLON SIRASIYLA bölümler, PDF'teki gibi: yönetici
 * özeti (renkli kutucuklar iyi yeşil / kötü kırmızı / nötr mavi + cümleler + gömülü bölümlerin açıklamaları), gösterge
 * bölümleri (değer kutucukları, uzun açıklamalar), anomali konuları (dikkat turuncusu sayaçlı kartlar), metin bölümleri,
 * tablolar, görseller (≤ {@link TechErrorMailImages#MAX_WIDTH} px CID gömülü, bütçeyi aşan ya da okunamayan görsel "PDF
 * ekinde" kutusu; tam çözünürlük PDF ekinde), trend bölümleri için PDF notu; kapanış notu. Düz metin eşi her blokla
 * birlikte üretilir (MailDoc). Tablo tabanlı, Outlook hayalet tabloları, renkli sol şerit yok, dış görsel URL'si yok
 * (EmailResponsiveContractTest).
 */
public final class TechErrorReportMail {

    private TechErrorReportMail() {}

    /** Gövdenin iç genişliği (kart 1000 − kenarlık 2 − iç boşluk 2 × 24). */
    public static final int CONTENT_W = MailTokens.WIDTH_XWIDE - 2 - 48;
    /**
     * İki sütunlu görsel satırında hücre başına yatay iç boşluk toplamı (her yanda yarısı — simetrik; inceleme
     * 2026-10-17: tek yanlı 16 px'lik boşluk Outlook hayalet hücresinde görseli 8 px taşırıyordu).
     */
    static final int GUTTER = 16;
    /** PDF kapağının lacivert bandı ({@code TechErrorReportPdfWriter.DARK} = 15, 23, 42). */
    static final String NAVY = "#0f172a";
    static final String NAVY_SUB = "#cbd5e1";
    static final String NAVY_META = "#94a3b8";
    static final String WHITE = "#ffffff";
    static final String CARD = "#ffffff";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Istanbul"));

    /** Üretilen posta + gövdeye gömülecek görseller (Content-ID) + sayılar (günlük / test). */
    public record Built(MailDoc.Mail mail, List<InlineImage> inline, int embeddedImages, int skippedImages) {}

    /** Kapak bandından hemen sonra gelen ek blok (onay kartı — {@code TechErrorApprovalMail}); MailDoc API'siyle yazar. */
    @FunctionalInterface
    public interface Intro {
        void apply(MailDoc d);
    }

    public static String subject(TechErrorPdfInput in) {
        return in.title();
    }

    /** Girdinin kendi görselleriyle, varsayılan gömme bütçesiyle (örnekler, testler, önizleme). */
    public static MailDoc.Mail build(TechErrorPdfInput in, String appUrl, String fileName) {
        return buildFull(in, TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET), appUrl, fileName).mail();
    }

    /**
     * Tam üretim: {@code prepared} = e-posta için hazırlanmış görseller ({@link TechErrorMailImages#prepare} — gönderici
     * bunu PDF izninin içinde, görseller bellekteyken üretir; gömülenler CID ile, bütçeyi aşan ya da okunamayanlar "PDF
     * ekinde" kutusuyla; ikisinde de olmayan yuva "yüklenmedi"). {@code in} görselsiz olabilir ({@code withoutImages}).
     */
    public static Built buildFull(TechErrorPdfInput in, TechErrorMailImages.Result prepared, String appUrl, String fileName) {
        return buildFull(in, prepared, appUrl, fileName, null);
    }

    /** {@link #buildFull(TechErrorPdfInput, TechErrorMailImages.Result, String, String)} + banddan sonra ek blok (null = yok). */
    public static Built buildFull(TechErrorPdfInput in, TechErrorMailImages.Result prepared, String appUrl, String fileName,
                                  Intro intro) {
        TechErrorMailImages.Result imgs = prepared == null ? TechErrorMailImages.Result.empty() : prepared;
        boolean en = in.template().en();
        String lang = in.template().lang();
        String period = TechErrorTemplate.monthName(in.month(), lang) + " " + in.year();
        MailDoc d = MailDoc.create(subject(in)).extraWide()
                .preheader((en ? "PDF report attached · " : "PDF rapor ektedir · ") + period)
                .kicker(en ? "Technical error report" : "Teknik hata raporu");
        d.raw(bandHtml(in, period, en), bandPlain(in, period, en));
        if (intro != null) intro.apply(d);
        if (fileName == null) {
            // PDF posta sınırına sığmadı (onay isteği): gövde yine tam, ek yok — bunu söyle (inceleme 2026-10-17)
            d.paragraph(en
                    ? "The PDF did not fit this e-mail's size limit, so it is not attached; the full report and its images are in Site Monitor."
                    : "PDF bu postanın boyut sınırına sığmadığından eklenmedi; tam rapor ve görseller Site Monitor'de.");
        } else {
            d.paragraph(en
                    ? "The full report, with every image at full resolution, is in the attached PDF (" + fileName + ")."
                    : "Tam rapor — görseller tam çözünürlükte — ekteki PDF'tedir (" + fileName + ").");
        }

        for (Section s : in.template().sections()) {
            switch (s.kind()) {
                case "summary" -> summary(d, in, s);
                case "metrics" -> { if (TechErrorTemplate.hostOf(in.template(), s) == null) metrics(d, in, s, en, lang); }
                case "issues" -> issues(d, in, s, en, lang);
                case "text" -> text(d, in, s);
                case "table" -> table(d, in, s, en, lang);
                case "images" -> images(d, in, s, imgs, en);
                case "trend" -> trend(d, in, s, en);
                default -> { }
            }
        }
        if (TechErrorCalc.anyRatioComputed(in.template(), in.values().fields())) {
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
        List<InlineImage> inline = new ArrayList<>();
        for (TechErrorMailImages.Prepared p : imgs.embedded().values()) inline.add(p.inline());
        return new Built(d.build(), List.copyOf(inline), imgs.embedded().size(), imgs.skipped().size() + imgs.unreadable().size());
    }

    // ── Kapak bandı ──────────────────────────────────────────────────────────────────────────────────

    /** PDF kapağı gibi lacivert band: üst etiket, başlık, takım · kanal · dönem, hazırlayan / üretim. */
    static String bandHtml(TechErrorPdfInput in, String period, boolean en) {
        Locale loc = en ? Locale.ENGLISH : Locale.forLanguageTag("tr");
        String kicker = (en ? "Technical error report" : "Teknik hata raporu").toUpperCase(loc) + " · " + period.toUpperCase(loc);
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\" data-slot=\"te-mail-band\"><tr>"
                + "<td bgcolor=\"" + NAVY + "\" style=\"background-color:" + NAVY + ";border-radius:" + MailTokens.RADIUS_CARD + "px;padding:22px 24px;font-family:"
                + MailTokens.FONT + "\">"
                + "<p style=\"margin:0 0 6px;font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.06em;color:" + NAVY_SUB + "\">" + MailKit.esc(kicker) + "</p>"
                + "<p style=\"margin:0;font-size:22px;line-height:30px;font-weight:600;color:" + WHITE + ";word-break:break-word;overflow-wrap:anywhere\">" + MailKit.esc(in.title()) + "</p>"
                + "<p style=\"margin:8px 0 0;font-size:14px;line-height:20px;color:" + NAVY_SUB + ";word-break:break-word;overflow-wrap:anywhere\">"
                + MailKit.esc(join(in.teamName(), in.channelName(), period)) + "</p>"
                + "<p style=\"margin:6px 0 0;font-size:12px;line-height:16px;color:" + NAVY_META + "\">" + MailKit.esc(meta(in, en)) + "</p>"
                + "</td></tr></table>";
    }

    static String bandPlain(TechErrorPdfInput in, String period, boolean en) {
        return in.title() + "\n" + join(in.teamName(), in.channelName(), period) + "\n" + meta(in, en);
    }

    private static String meta(TechErrorPdfInput in, boolean en) {
        return (en ? "Prepared by: " : "Hazırlayan: ") + nz(in.preparedBy()) + " · " + (en ? "Generated: " : "Üretim: ")
                + STAMP.format(in.generatedAt() == null ? Instant.now() : in.generatedAt()) + (en ? " (Turkey time)" : " (Türkiye saati)");
    }

    // ── Yönetici özeti ───────────────────────────────────────────────────────────────────────────────

    /**
     * Yönetici özeti PDF'teki bloklarla (2026-10-17, {@link TechErrorSummary#blocks}): önce renkli kutucuklar (4b), sonra
     * metin paragrafları ve gömülü bölümlerin açıklamaları ilgili cümlenin altında — küçük kalın başlık = alanın adı (ad
     * boşsa "Açıklama") + girintili metin (sol şerit yok). Hiç kutucuk, metin ve not yoksa başlık da basılmaz.
     */
    private static void summary(MailDoc d, TechErrorPdfInput in, Section s) {
        boolean en = in.template().en();
        List<TechErrorSummary.Block> blocks = TechErrorSummary.blocks(s, in.template(), in.values(), in.previousOrNull(),
                in.channelName(), in.year(), in.month());
        List<Tile> tiles = TechErrorTiles.build(in.template(), s, in.values(), in.previousOrNull(), in.channelName(), in.year(), in.month());
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
        if (html.isEmpty() && tiles.isEmpty()) return;
        d.heading(in.resolve(s.title()));
        if (!tiles.isEmpty()) d.raw(tilesHtml(tiles, en), tilesPlain(tiles, en));
        if (!html.isEmpty()) d.raw(html.toString(), plain.toString());
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
    static String tileSub(Tile tile, boolean en) {
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
     * Renkli kutucuk şeridi (2026-10-17, 4b): satırda 3 (Outlook'ta mso tablo hücreleri, diğerlerinde satır içi bloklar);
     * zemin ve yazı rengi tondan; telefonda {@code .stack} ile alt alta — tek sütun (kullanıcı kararı S5).
     */
    static String tilesHtml(List<Tile> tiles, boolean en) {
        int n = tiles.size();
        int perRow = Math.min(3, n);
        String pct = perRow == 3 ? "33.33%" : (100 / perRow) + "%";
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" data-slot=\"te-tiles\"><tr><td style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (int i = 0; i < n; i++) {
            Tile tile = tiles.get(i);
            if (i > 0 && i % perRow == 0) sb.append("<!--[if mso]></tr><tr><![endif]-->");
            String[] col = tileColors(tile.tone());
            sb.append("<!--[if mso]><td width=\"").append(pct).append("\" valign=\"top\"><![endif]-->")
              .append("<div class=\"stack\" style=\"display:inline-block;vertical-align:top;width:100%;min-width:160px;max-width:").append(pct).append("\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:0 8px 8px 0\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td bgcolor=\"").append(col[0]).append("\" data-tone=\"").append(tile.tone()).append("\" style=\"background-color:").append(col[0])
              .append(";border-radius:8px;padding:14px 16px;font-family:").append(MailTokens.FONT).append("\">")
              .append("<p style=\"margin:0;font-size:12px;line-height:16px;font-weight:500;color:").append(col[1]).append("\">").append(MailKit.esc(tile.label())).append("</p>")
              .append("<p style=\"margin:4px 0 0;font-size:24px;line-height:30px;font-weight:700;color:").append(col[1])
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

    static String tilesPlain(List<Tile> tiles, boolean en) {
        StringBuilder t = new StringBuilder();
        for (Tile tile : tiles) {
            t.append(tile.label()).append(": ").append(tile.value());
            String sub = tileSub(tile, en);
            if (sub != null) t.append(" (").append(sub).append(')');
            t.append('\n');
        }
        return t.toString();
    }

    // ── Gösterge bölümleri ───────────────────────────────────────────────────────────────────────────

    /**
     * Gösterge bölümü PDF'teki gibi: her alanın değeri (muaf alanlar yok; boş değer "—"), satırda üç küçük kutucuk
     * (telefonda alt alta), uzun açıklamalar alan adı başlığıyla altında.
     */
    private static void metrics(MailDoc d, TechErrorPdfInput in, Section s, boolean en, String lang) {
        Map<String, Object> vals = in.values().fields();
        List<Field> shown = new ArrayList<>();
        for (Field f : s.fields()) if (!TechErrorTemplate.exempt(f, vals)) shown.add(f);
        if (shown.isEmpty()) return;      // bölümün tüm alanları muaf → PDF'teki gibi başlık da basılmaz
        d.heading(in.resolve(s.title()));
        List<Field> compact = new ArrayList<>();
        List<Field> longs = new ArrayList<>();
        for (Field f : shown) {
            if ("textarea".equals(f.type())) longs.add(f);
            else compact.add(f);
        }
        if (!compact.isEmpty()) {
            StringBuilder plain = new StringBuilder();
            List<String[]> cells = new ArrayList<>();
            for (Field f : compact) {
                String label = in.resolve(f.label());
                String value = TechErrorFormat.value(f, vals.get(f.id()), lang);
                cells.add(new String[] { label, value });
                plain.append(label).append(": ").append(value).append('\n');
            }
            d.raw(valueTilesHtml(cells), plain.toString());
        }
        for (Field f : longs) {
            Object v = vals.get(f.id());
            // PDF ile aynı: boş uzun açıklama "—" olarak basılır (alan vardı, doldurulmadı)
            String text = v == null || String.valueOf(v).isBlank() ? "—" : String.valueOf(v);
            String label = in.resolve(f.label());
            String html = "<div style=\"padding-left:16px;margin:2px 0 10px\">"
                    + "<p style=\"margin:0 0 2px;font-family:" + MailTokens.FONT + ";font-size:13px;line-height:18px;font-weight:bold;color:" + MailTokens.MUTED + "\">"
                    + MailKit.esc(label) + "</p>" + richHtml(text, 14, 22) + "</div>";
            d.raw(html, label + ":\n" + TechErrorRichText.plain(text));
        }
    }

    /** Küçük değer kutucukları: etiket + değer, satırda 3, telefonda alt alta ({@code .stack}); Outlook hayalet hücreleri. */
    static String valueTilesHtml(List<String[]> cells) {
        int n = cells.size();
        int perRow = Math.min(3, n);
        String pct = perRow == 3 ? "33.33%" : (100 / perRow) + "%";
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" data-slot=\"te-values\"><tr><td style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (int i = 0; i < n; i++) {
            String[] c = cells.get(i);
            if (i > 0 && i % perRow == 0) sb.append("<!--[if mso]></tr><tr><![endif]-->");
            sb.append("<!--[if mso]><td width=\"").append(pct).append("\" valign=\"top\"><![endif]-->")
              .append("<div class=\"stack\" style=\"display:inline-block;vertical-align:top;width:100%;min-width:160px;max-width:").append(pct).append("\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:0 8px 8px 0\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td bgcolor=\"").append(CARD).append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(MailTokens.BORDER)
              .append(";border-radius:8px;padding:10px 14px;font-family:").append(MailTokens.FONT).append("\">")
              .append("<p style=\"margin:0;font-size:12px;line-height:16px;font-weight:500;color:").append(MailTokens.MUTED).append("\">").append(MailKit.esc(c[0])).append("</p>")
              .append("<p style=\"margin:2px 0 0;font-size:18px;line-height:24px;font-weight:600;color:").append(MailTokens.FG)
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(MailKit.esc(c[1])).append("</p>")
              .append("</td></tr></table></td></tr></table></div><!--[if mso]></td><![endif]-->");
        }
        sb.append("<!--[if mso]></tr></table><![endif]--></td></tr></table>");
        return sb.toString();
    }

    // ── Anomali konuları ─────────────────────────────────────────────────────────────────────────────

    /** Her konu bir kart: numaralı başlık, dikkat turuncusu sayaç satırı (müşteri / hata adedi), zengin açıklama. */
    private static void issues(MailDoc d, TechErrorPdfInput in, Section s, boolean en, String lang) {
        d.heading(in.resolve(s.title()));
        List<Issue> list = in.values().issues().getOrDefault(s.id(), List.of());
        if (list.isEmpty()) {
            d.note(en ? "No topic was entered for this month." : "Bu ay için başlık girilmedi.");
            return;
        }
        int n = 0;
        for (Issue i : list) {
            n++;
            String title = i.displayTitle(s.itemTitle());
            if (title == null || title.isBlank()) title = en ? "Untitled topic" : "Başlıksız konu";
            String head = n + ". " + title;
            String counts = in.resolve(s.customersLabel()) + ": " + TechErrorFormat.count(i.customers(), lang)
                    + " · " + in.resolve(s.errorsLabel()) + ": " + TechErrorFormat.count(i.errors(), lang);
            boolean hasDesc = i.description() != null && !i.description().isBlank();
            String html = "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\" data-slot=\"te-issue\"><tr>"
                    + "<td bgcolor=\"" + CARD + "\" style=\"background-color:" + CARD + ";border:1px solid " + MailTokens.BORDER + ";border-radius:8px;padding:14px 16px;font-family:"
                    + MailTokens.FONT + ";color:" + MailTokens.FG + "\">"
                    + "<p style=\"margin:0 0 4px;font-size:15px;line-height:22px;font-weight:600;color:" + MailTokens.FG + ";word-break:break-word;overflow-wrap:anywhere\">" + MailKit.esc(head) + "</p>"
                    + "<p style=\"margin:0 0 " + (hasDesc ? 10 : 0) + "px;font-size:13px;line-height:20px;font-weight:600;color:" + MailTokens.WARNING + "\">" + MailKit.esc(counts) + "</p>"
                    + (hasDesc ? richHtml(i.description(), 14, 22) : "")
                    + "</td></tr></table>";
            d.raw(html, head + "\n" + counts + (hasDesc ? "\n" + TechErrorRichText.plain(i.description()) : ""));
        }
    }

    // ── Metin, tablo, görsel, trend ──────────────────────────────────────────────────────────────────

    private static void text(MailDoc d, TechErrorPdfInput in, Section s) {
        String stored = in.values().texts().get(s.id());
        String text = stored != null ? stored : s.text();
        if (text == null || text.isBlank()) return;     // kullanıcı metni bilerek boşalttı → bölüm basılmaz (PDF ile aynı)
        d.heading(in.resolve(s.title()));
        String resolved = in.resolveText(text);
        d.raw(richHtml(resolved), TechErrorRichText.plain(resolved));
    }

    /** Değeri olan tablo: ilk sütun satır adı (grup satırı kalın), sayısal sütunlar sağa hizalı; telefonda kart kopyası. */
    private static void table(MailDoc d, TechErrorPdfInput in, Section s, boolean en, String lang) {
        boolean any = false;
        for (TableRow r : s.rows()) for (Column col : s.columns()) if (in.values().cell(s.id(), r.id(), col.id()) != null) any = true;
        if (!any) return;
        List<Col> cols = new ArrayList<>();
        cols.add(Col.of(""));
        for (Column col : s.columns()) {
            String label = in.resolve(col.label());
            cols.add("text".equals(col.type()) ? Col.of(label) : Col.num(label));
        }
        List<List<Cell>> rows = new ArrayList<>();
        boolean seenGroup = false;
        for (TableRow r : s.rows()) {
            List<Cell> cells = new ArrayList<>();
            String label = in.resolve(r.label());
            // PDF ile aynı: grup satırından SONRAKİ satırlar girintili
            boolean indent = seenGroup && !r.group();
            cells.add(indent ? new Cell("&nbsp;&nbsp;&nbsp;" + MailKit.esc(label), "  " + label, null, false)
                    : Cell.of(label, null, r.group()));
            for (Column col : s.columns()) {
                Object v = in.values().cell(s.id(), r.id(), col.id());
                cells.add(Cell.of(TechErrorFormat.cell(col, v, lang), null, r.group()));
            }
            rows.add(cells);
            if (r.group()) seenGroup = true;
        }
        d.heading(in.resolve(s.title()));
        // Telefon kart kopyası yalnız küçük tablolar için: 30 × 10'luk bir tablonun ikinci kopyası Gmail'in 102 KB
        // kırpma sınırına yaklaştırır (inceleme 2026-10-17); büyük tablo telefonda tablo olarak kayar
        d.table(cols, rows, rows.size() <= TABLE_STACK_MAX_ROWS);
    }

    /** Telefon kart kopyası eklenen en büyük tablo (satır). */
    static final int TABLE_STACK_MAX_ROWS = 12;

    /**
     * Görsel bölümü: yarım yuvalar ikişer yan yana (telefonda alt alta), tam yuva tek başına; her görselin üstünde yuva adı.
     * Gömülen görsel {@code cid:} ile, bütçeyi aşan ya da okunamayan görsel "PDF ekinde" kutusuyla gösterilir; hiç görsel
     * yüklenmemişse PDF'teki not.
     */
    private static void images(MailDoc d, TechErrorPdfInput in, Section s, TechErrorMailImages.Result prepared, boolean en) {
        List<Slot> present = new ArrayList<>();
        for (Slot sl : s.slots()) if (prepared.present(sl.id())) present.add(sl);
        d.heading(in.resolve(s.title()));
        if (present.isEmpty()) {
            d.note(en ? "No image was uploaded to this section." : "Bu bölüme görsel yüklenmedi.");
            return;
        }
        int halfW = CONTENT_W / 2 - GUTTER;      // hücre %50 (475) − iki yanda 8 px = 459
        for (int i = 0; i < present.size(); ) {
            Slot a = present.get(i);
            Slot b = i + 1 < present.size() ? present.get(i + 1) : null;
            if ("half".equals(a.width()) && b != null && "half".equals(b.width())) {
                d.raw(imageRowHtml(in, List.of(a, b), halfW, prepared, en), imagePlain(in, List.of(a, b), prepared, en));
                i += 2;
            } else {
                d.raw(imageRowHtml(in, List.of(a), "half".equals(a.width()) ? halfW : CONTENT_W, prepared, en),
                        imagePlain(in, List.of(a), prepared, en));
                i += 1;
            }
        }
    }

    static String imageRowHtml(TechErrorPdfInput in, List<Slot> slots, int colPx, TechErrorMailImages.Result prepared, boolean en) {
        int n = slots.size();
        String pct = n == 2 ? "50%" : "100%";
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" data-slot=\"te-images\"><tr><td style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (int k = 0; k < n; k++) {
            Slot sl = slots.get(k);
            String caption = in.resolve(sl.label());
            TechErrorMailImages.Prepared p = prepared.embedded().get(sl.id());
            sb.append("<!--[if mso]><td width=\"").append(pct).append("\" valign=\"top\"><![endif]-->")
              .append("<div class=\"stack\" style=\"display:inline-block;vertical-align:top;width:100%;max-width:").append(pct).append("\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:")
              .append(n == 2 ? "0 " + (GUTTER / 2) + "px 12px " + (GUTTER / 2) + "px" : "0 0 12px 0").append(";font-family:").append(MailTokens.FONT).append("\">")
              .append("<p style=\"margin:0 0 6px;font-size:13px;line-height:18px;font-weight:600;color:").append(MailTokens.FG)
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(MailKit.esc(caption)).append("</p>");
            if (p != null) {
                int pxW = Math.min(p.width(), colPx);
                String alt = MailKit.esc(caption);
                // Outlook (Word motoru) yalnız piksel width özniteliğini okur; diğerleri akışkan (max-width) — sözleşme:
                // MSO dışında ≥ 500 px sabit genişlik yok
                sb.append("<!--[if mso]><img src=\"cid:").append(p.cid()).append("\" width=\"").append(pxW).append("\" alt=\"").append(alt)
                  .append("\" border=\"0\" style=\"display:block\"><![endif]-->")
                  .append("<!--[if !mso]><!--><img src=\"cid:").append(p.cid()).append("\" alt=\"").append(alt)
                  .append("\" border=\"0\" style=\"display:block;width:100%;max-width:").append(pxW).append("px;height:auto;border:1px solid ")
                  .append(MailTokens.BORDER).append(";border-radius:8px\"><!--<![endif]-->");
            } else {
                boolean unreadable = prepared.unreadable().contains(sl.id());
                sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\" data-slot=\"")
                  .append(unreadable ? "te-image-unreadable" : "te-image-pdf").append("\"><tr>")
                  .append("<td style=\"border:1px dashed ").append(MailTokens.BORDER).append(";border-radius:8px;padding:18px 16px;text-align:center;font-family:")
                  .append(MailTokens.FONT).append(";font-size:13px;line-height:18px;color:").append(MailTokens.MUTED).append("\">")
                  .append(MailKit.esc(placeholderText(unreadable, en)))
                  .append("</td></tr></table>");
            }
            sb.append("</td></tr></table></div><!--[if mso]></td><![endif]-->");
        }
        sb.append("<!--[if mso]></tr></table><![endif]--></td></tr></table>");
        return sb.toString();
    }

    /** Gömülmeyen yuvanın kutusu: bütçeye sığmadı → PDF ekinde; okunamadı → PDF'te de çizilemedi (aynı metin olmasın). */
    static String placeholderText(boolean unreadable, boolean en) {
        if (unreadable) return en ? "Image could not be read (not drawn in the PDF either)." : "Görsel okunamadı (PDF'te de çizilemedi).";
        return en ? "Image in the attached PDF (full resolution)." : "Görsel ekteki PDF'te (tam çözünürlük).";
    }

    static String imagePlain(TechErrorPdfInput in, List<Slot> slots, TechErrorMailImages.Result prepared, boolean en) {
        StringBuilder t = new StringBuilder();
        for (Slot sl : slots) {
            String tag;
            if (prepared.embedded().containsKey(sl.id())) tag = en ? "[Image] " : "[Görsel] ";
            else if (prepared.unreadable().contains(sl.id())) tag = en ? "[Image — unreadable] " : "[Görsel — okunamadı] ";
            else tag = en ? "[Image — in the PDF] " : "[Görsel — PDF ekinde] ";
            t.append(tag).append(in.resolve(sl.label())).append('\n');
        }
        return t.toString();
    }

    /** Trend grafikleri e-postada çizilmez: çizilecek alanı olan bölüm için PDF notu. */
    private static void trend(MailDoc d, TechErrorPdfInput in, Section s, boolean en) {
        Map<String, Object> vals = in.values().fields();
        boolean any = false;
        for (String ref : s.lines()) {
            for (Field f : in.template().allFields()) {
                if (f.id().equals(ref) && TechErrorTemplate.trendable(f) && !TechErrorTemplate.exempt(f, vals)) any = true;
            }
        }
        if (!any) return;
        d.heading(in.resolve(s.title()));
        d.note(en ? "The 12-month trend charts are in the attached PDF." : "Son 12 ayın trend grafikleri ekteki PDF'tedir.");
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

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

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (blank(p)) continue;
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.isEmpty() ? "—" : sb.toString();
    }
}
