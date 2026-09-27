package com.sitemonitor.service.mail;

import com.sitemonitor.service.BrandMailAssets;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sitemonitor.service.mail.MailTokens.BG;
import static com.sitemonitor.service.mail.MailTokens.BORDER;
import static com.sitemonitor.service.mail.MailTokens.CARD;
import static com.sitemonitor.service.mail.MailTokens.DIVIDER;
import static com.sitemonitor.service.mail.MailTokens.FG;
import static com.sitemonitor.service.mail.MailTokens.FONT;
import static com.sitemonitor.service.mail.MailTokens.MONO;
import static com.sitemonitor.service.mail.MailTokens.MUTED;
import static com.sitemonitor.service.mail.MailTokens.PRIMARY;
import static com.sitemonitor.service.mail.MailTokens.RADIUS_CARD;
import static com.sitemonitor.service.mail.MailTokens.RADIUS_CONTROL;
import static com.sitemonitor.service.mail.MailTokens.RADIUS_INNER;
import static com.sitemonitor.service.mail.MailTokens.SECONDARY;
import static com.sitemonitor.service.mail.MailTokens.SUBTLE;
import static com.sitemonitor.service.mail.MailTokens.TRACK;

/**
 * E-posta yapı taşları — Site Monitor'ün gönderdiği HER e-posta bu parçalardan kurulur
 * (e-posta yeniden tasarımı 2026-09-26, BRAND.md §5.1). Doğrudan değil {@link MailDoc}
 * üzerinden kullanılır; MailDoc HTML ile düz metni birlikte biriktirir.
 *
 * <p><b>Üç istemci ailesi, üç savunma:</b>
 * <ul>
 *   <li><b>Modern istemci</b> (Apple Mail, iOS, Gmail uygulaması, Outlook mobil/yeni): tek
 *       {@code <style>} bloğundaki {@code @media (max-width:620px)} sınıfları mobil düzeni kurar —
 *       tablo satırları karta dönüşür, butonlar tam genişlik olur, anahtar-değer alt alta iner.</li>
 *   <li><b>{@code <style>}'ı atan istemci</b> (bazı webmail'ler, Gmail'in üçüncü taraf hesapları):
 *       düzen "fluid-hybrid"dir — kart {@code width:100%;max-width:600px}, istatistik kutuları ve
 *       buton grupları satır sarabilen inline-block'lardır; medya sorgusu olmadan da yatay kaydırma
 *       çıkmaz.</li>
 *   <li><b>Outlook masaüstü</b> (Word motoru): {@code [if mso]} hayalet tabloları sabit 600/640
 *       genişliği ve sütunları kurar, butonlar VML {@code v:roundrect}'tir; köşeler kare kalır
 *       (bilinçli geri düşüş). Renkler daima {@code td bgcolor} + düz hex; div zemini, rgba, sol
 *       renkli şerit YOK.</li>
 * </ul>
 */
public final class MailKit {

    private MailKit() { }

    /** Mobil kırılım — kart (600) + dış boşluk sığmadığında mobil düzen devreye girer. */
    public static final int BREAKPOINT = 620;

    /** Açık-tema kilidi: Apple Mail/iOS karanlık modda beyaz kartı tersine çevirmesin (TEK kopya). */
    public static final String LIGHT_SCHEME_META =
            "<meta name=\"color-scheme\" content=\"light only\"><meta name=\"supported-color-schemes\" content=\"light\">";

    /** Tek duyarlı stil bloğu. Sınıf adları kısa ve düz tutulur (Gmail sınıf önekler, öznitelik seçici desteklemez). */
    static final String CSS =
            "body,table,td,a{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "table,td{mso-table-lspace:0pt;mso-table-rspace:0pt}"
            + "img{-ms-interpolation-mode:bicubic;border:0;outline:none;text-decoration:none}"
            + ".m-only{display:none;max-height:0;overflow:hidden}"
            // Sayısal/kısa hücre masaüstünde kırılmaz ("3 · 260 dk" iki satıra bölünmesin); stil atan
            // istemcide sınıf yok sayılır → hücre kırılabilir kalır, dar ekranda yatay taşma çıkmaz.
            + ".nw{white-space:nowrap}"
            + "@media only screen and (max-width:" + BREAKPOINT + "px){"
            + ".nw{white-space:normal!important}"
            + ".outer{padding:12px 8px!important}"
            + ".px{padding-left:16px!important;padding-right:16px!important}"
            + ".h1{font-size:20px!important;line-height:28px!important}"
            + ".stack{display:block!important;width:100%!important;max-width:100%!important;box-sizing:border-box}"
            + ".d-only{display:none!important}"
            + ".m-only{display:block!important;max-height:none!important;overflow:visible!important}"
            + ".col-opt{display:none!important}"
            // Tablo TABLO kalır (display:block anonim hücreyle içeriğe büzülür) → yalnız genişlik; <a> blok olur.
            + ".btn-t{width:100%!important}"
            + ".btn-full{display:block!important;box-sizing:border-box}"
            + ".bg-item{display:block!important;margin-right:0!important}"
            + ".kv-l{display:block!important;width:auto!important;padding:10px 0 2px!important;border-bottom:0!important}"
            + ".kv-v{display:block!important;width:auto!important;padding:0 0 10px!important}"
            + ".tile{max-width:50%!important}"
            + ".tile3{max-width:33.33%!important}"
            + ".hide-m{display:none!important}"
            + "}";

    /** Outlook masaüstü: ilk font (Inter) kurulu değilse Word Times New Roman'a düşer → Segoe UI zorlanır. */
    private static final String MSO_HEAD =
            "<!--[if mso]><noscript><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch>"
            + "</o:OfficeDocumentSettings></xml></noscript><![endif]-->"
            + "<!--[if mso]><style>body,table,td,th,div,p,a,span,li,h1,h2,strong,center{font-family:'Segoe UI',Arial,sans-serif!important}"
            + ".mono{font-family:Consolas,'Courier New',monospace!important}</style><![endif]-->";

    // ── Metin güvenliği ──────────────────────────────────────────────────────

    /**
     * TEK kaçış fonksiyonu: beş karakterin hepsi. Öznitelikler hem tek hem çift tırnakla yazıldığı
     * için tırnaklar da kaçırılır (eski {@code SmtpMailService.esc} yalnız {@code & < >} kaçırıyordu).
     */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Kaçır + satır sonlarını {@code <br>} yap — Outlook {@code white-space:pre-wrap}'i tanımaz. */
    public static String escBr(String s) {
        if (s == null) return "";
        return esc(s.replace("\r\n", "\n").replace('\r', '\n')).replace("\n", "<br>");
    }

    /**
     * Bağlantı hedefi e-postaya {@code href} olarak basılabilir mi? (BD1, bug regresyon 2026-09-27)
     *
     * <p>Açık bir ŞEMA varsa yalnız {@code http}, {@code https}, {@code mailto} kabul edilir; {@code javascript:},
     * {@code data:}, {@code vbscript:}, {@code file:} … reddedilir. Şemasız (göreli) değer kabul edilir — uygulama
     * taban adresi boşken üretilen {@code /?tab=…} bağlantıları eskisi gibi çizilir. Şema, tarayıcının yaptığı gibi
     * sekme/satır sonu/kontrol karakterleri ATILARAK okunur ({@code java&#9;script:} hilesi). Haftalık rapordaki
     * takip bağlantıları kullanıcı girdisidir ve sunucuda doğrulanmıyordu: API'ye doğrudan {@code javascript:}
     * yazılıp müdür onay mailinde tıklanabilir hâle getirilebiliyordu.
     */
    public static boolean safeHref(String url) {
        if (url == null) return false;
        StringBuilder b = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 0x20 && c != 0x7F) b.append(c);   // boşluk + C0 kontrol + DEL: tarayıcı şemada yok sayar
        }
        String s = b.toString();
        if (s.isEmpty()) return false;
        int colon = s.indexOf(':');
        if (colon <= 0) return true;                                   // şema yok → göreli
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') return true;          // ':' yol/sorgu içinde → şema değil
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return false;                                      // şema biçimine uymayan önek → reddet
        }
        String scheme = s.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto");
    }

    /**
     * Telefon numarasından {@code tel:} hedefi — YALNIZ rakamlar ve baştaki {@code +} (2026-09-27, 7/24 arama
     * listesi). 3 haneden kısa ya da rakamsız değer null. {@link #safeHref} bilerek {@code tel:} KABUL ETMEZ:
     * kullanıcı girdisi bağlantılar (haftalık rapor takip bağlantıları vb.) bu şemayı taşıyamaz; telefon bağlantısı
     * yalnız bu kurucudan, sunucunun kendi okuduğu değerden (AD {@code mobile}) üretilir.
     */
    public static String telHref(String phone) {
        if (phone == null || phone.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        String p = phone.trim();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c == '+' && sb.length() == 0) sb.append(c);
        }
        long digits = sb.chars().filter(ch -> ch >= '0' && ch <= '9').count();
        return digits < 3 ? null : "tel:" + sb;
    }

    /** Telefon bağlantısı: görünen metin kaçırılır, hedef {@link #telHref}; numara çevrilemezse düz (kaçırılmış) metin. */
    public static String telLink(String phone) {
        String href = telHref(phone);
        if (href == null) return esc(phone);
        return "<a href=\"" + href + "\" target=\"_blank\" style=\"color:" + PRIMARY
                + ";text-decoration:underline;white-space:nowrap\">" + esc(phone.trim()) + "</a>";
    }

    private static String font() {
        return "font-family:" + FONT + ";";
    }

    // ── Belge iskeleti ───────────────────────────────────────────────────────

    /** DOCTYPE → head (viewport, MSO ayarları, tek stil bloğu) → gizli önizleme metni → dış zemin → kart. */
    public static String open(String title, String preheader, int width) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>")
          .append("<html lang=\"tr\" xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">")
          .append("<head><meta charset=\"UTF-8\">")
          // maximum-scale YOK: kullanıcının yakınlaştırmasını kilitlemek erişilebilirlik ihlali.
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<meta name=\"x-apple-disable-message-reformatting\">")
          .append("<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">")
          .append("<meta name=\"format-detection\" content=\"telephone=no,date=no,address=no,email=no,url=no\">")
          .append(LIGHT_SCHEME_META)
          .append("<title>").append(esc(title)).append("</title>")
          .append(MSO_HEAD)
          .append("<style>").append(CSS).append("</style>")
          .append("</head>")
          .append("<body bgcolor=\"").append(BG).append("\" style=\"margin:0;padding:0;width:100%;background-color:").append(BG)
          .append(";-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%\">");
        if (preheader != null && !preheader.isBlank()) {
            // Gelen kutusu önizleme satırı — gövdede görünmez; ardındaki boşluk dolgusu istemcinin
            // gövdeden rastgele metin çekmesini önler.
            sb.append("<div class=\"preheader\" style=\"display:none;font-size:0;line-height:0;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;color:")
              .append(BG).append("\">").append(esc(preheader))
              .append("&#8199;&#65279;&#847;&#8199;&#65279;&#847;&#8199;&#65279;&#847;&#8199;&#65279;&#847;&#8199;&#65279;&#847;&#8199;&#65279;&#847;")
              .append("</div>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(BG)
          .append("\" style=\"background-color:").append(BG).append("\">")
          .append("<tr><td align=\"center\" class=\"outer\" style=\"padding:24px 12px\">")
          .append("<!--[if mso]><table role=\"presentation\" align=\"center\" width=\"").append(width)
          .append("\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->")
          .append("<table role=\"presentation\" class=\"card\" align=\"center\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"")
          .append(CARD).append("\" style=\"width:100%;max-width:").append(width).append("px;background-color:").append(CARD)
          .append(";border:1px solid ").append(BORDER).append(";border-radius:").append(RADIUS_CARD).append("px;border-collapse:separate\">");
        return sb.toString();
    }

    /** Beyaz başlık: logo lockup'ı (TEK logo, BRAND.md §5.1) + sağda alt-sistem etiketi. */
    public static String header(String kicker) {
        return "<tr><td class=\"px\" bgcolor=\"" + CARD + "\" style=\"padding:16px 24px;border-bottom:1px solid " + BORDER
                + ";border-radius:" + RADIUS_CARD + "px " + RADIUS_CARD + "px 0 0\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td align=\"left\" valign=\"middle\">" + BrandMailAssets.headerLockup(FG) + "</td>"
                + (kicker == null || kicker.isBlank() ? ""
                    : "<td align=\"right\" valign=\"middle\" style=\"padding-left:12px;" + font() + "font-size:12px;line-height:16px;font-weight:500;color:"
                      + MUTED + "\">" + esc(kicker) + "</td>")
                + "</tr></table></td></tr>";
    }

    public static String bodyOpen() {
        return "<tr><td class=\"px\" bgcolor=\"" + CARD + "\" style=\"padding:24px 24px 8px;" + font() + "color:" + FG + "\">";
    }

    public static String bodyClose() {
        return "</td></tr>";
    }

    /** Alt bilgi: (isteğe bağlı) "Neden bu e-postayı aldınız?" + meta satırı. Kartın alt köşeleri yuvarlak. */
    public static String footer(String whyHtml, String metaHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td class=\"px\" bgcolor=\"").append(SUBTLE).append("\" style=\"padding:16px 24px 20px;background-color:")
          .append(SUBTLE).append(";border-top:1px solid ").append(BORDER).append(";border-radius:0 0 ")
          .append(RADIUS_CARD).append("px ").append(RADIUS_CARD).append("px;").append(font()).append("\">");
        if (whyHtml != null && !whyHtml.isBlank()) {
            sb.append("<p style=\"margin:0 0 2px;font-size:13px;line-height:20px;font-weight:600;color:").append(FG)
              .append("\">Neden bu e-postayı aldınız?</p>")
              .append("<p style=\"margin:0 0 10px;font-size:13px;line-height:20px;color:").append(MUTED).append("\">")
              .append(whyHtml).append("</p>");
        }
        if (metaHtml != null && !metaHtml.isBlank()) {
            sb.append("<p style=\"margin:0;font-size:12px;line-height:18px;color:").append(MUTED).append("\">")
              .append(metaHtml).append("</p>");
        }
        return sb.append("</td></tr>").toString();
    }

    public static String close() {
        return "</table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>";
    }

    /** Blok aralığı: margin yerine td padding (Outlook tablo margin'ini güvenilir uygulamaz). */
    public static String space(String inner, int bottomPx) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:0 0 "
                + bottomPx + "px\">" + inner + "</td></tr></table>";
    }

    // ── Metin blokları ───────────────────────────────────────────────────────

    public static String title(String h1, String leadHtml) {
        String s = "<h1 class=\"h1\" style=\"margin:0;" + font() + "font-size:22px;line-height:30px;font-weight:600;color:" + FG
                + ";letter-spacing:-0.2px;word-break:break-word;overflow-wrap:anywhere\">" + esc(h1) + "</h1>";
        if (leadHtml != null && !leadHtml.isBlank()) {
            s += "<p style=\"margin:6px 0 0;" + font() + "font-size:15px;line-height:24px;color:" + MUTED
                    + ";word-break:break-word;overflow-wrap:anywhere\">" + leadHtml + "</p>";
        }
        return s;
    }

    public static String paragraph(String html) {
        return "<p style=\"margin:0;" + font() + "font-size:15px;line-height:24px;color:" + FG
                + ";word-break:break-word;overflow-wrap:anywhere\">" + html + "</p>";
    }

    public static String note(String html) {
        return "<p style=\"margin:0;" + font() + "font-size:13px;line-height:20px;color:" + MUTED
                + ";word-break:break-word;overflow-wrap:anywhere\">" + html + "</p>";
    }

    public static String heading(String text, String descHtml) {
        String s = "<h2 style=\"margin:0;" + font() + "font-size:15px;line-height:22px;font-weight:600;color:" + FG + "\">" + esc(text) + "</h2>";
        if (descHtml != null && !descHtml.isBlank()) {
            s += "<p style=\"margin:2px 0 0;" + font() + "font-size:13px;line-height:20px;color:" + MUTED + "\">" + descHtml + "</p>";
        }
        return s;
    }

    /**
     * Satır içi bağlantı — uzun URL kartı taşırmasın diye kırılabilir. Şeması izinli değilse ({@link #safeHref})
     * bağlantı KURULMAZ, etiket düz metin olarak basılır.
     */
    public static String link(String url, String label) {
        if (!safeHref(url)) return esc(label);
        return "<a href=\"" + esc(url) + "\" target=\"_blank\" style=\"color:" + PRIMARY
                + ";text-decoration:underline;word-break:break-word;overflow-wrap:anywhere\">" + esc(label) + "</a>";
    }

    /** Satır içi tek aralıklı metin (parmak izi, komut, kod). */
    public static String mono(String text) {
        return "<span class=\"mono\" style=\"font-family:" + MONO + ";font-size:13px\">" + esc(text) + "</span>";
    }

    /** Satır içi renkli/kalın vurgu. */
    public static String strong(String text, String color) {
        return "<strong style=\"font-weight:600;color:" + (color == null ? FG : color) + "\">" + esc(text) + "</strong>";
    }

    // ── Rozet (Badge) ────────────────────────────────────────────────────────

    /** Rozet: {@code solid} = dolu zemin + beyaz yazı (yalnız DESTRUCTIVE/INFO — kontrast), aksi tonlu; tone null = outline. */
    public record Badge(String label, Tone tone, boolean solid) {
        public static Badge solid(String label, Tone tone) { return new Badge(label, tone, true); }
        public static Badge tint(String label, Tone tone) { return new Badge(label, tone, false); }
        public static Badge outline(String label) { return new Badge(label, null, false); }
    }

    public static String badge(Badge b) {
        String bg, border, color;
        if (b.tone() == null) {
            bg = CARD; border = BORDER; color = FG;
        } else if (b.solid() && (b.tone() == Tone.DESTRUCTIVE || b.tone() == Tone.INFO)) {
            bg = b.tone().strong; border = b.tone().strong; color = "#ffffff";
        } else if (b.tone() == Tone.NEUTRAL) {
            bg = SECONDARY; border = SECONDARY; color = FG;
        } else {
            bg = b.tone().bg; border = b.tone().border; color = b.tone().text;
        }
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>"
                + "<td bgcolor=\"" + bg + "\" style=\"background-color:" + bg + ";border:1px solid " + border + ";border-radius:" + RADIUS_CONTROL
                + "px;padding:2px 8px;" + font() + "font-size:12px;line-height:16px;font-weight:600;color:" + color + ";white-space:nowrap\">"
                + esc(b.label()) + "</td></tr></table>";
    }

    /**
     * Satır sarabilen öğe dizisi (rozet, çip, buton): modern istemcide inline-block (dar ekranda
     * alt satıra iner), Outlook'ta hayalet tablo hücreleri. {@code itemClass} mobil sınıfı (ör. bg-item).
     */
    public static String inlineWrap(List<String> items, int gapPx, String itemClass) {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (String item : items) {
            sb.append("<!--[if mso]><td valign=\"top\" style=\"padding:0 ").append(gapPx).append("px ").append(gapPx).append("px 0\"><![endif]-->")
              .append("<div").append(itemClass == null ? "" : " class=\"" + itemClass + "\"")
              .append(" style=\"display:inline-block;vertical-align:top;margin:0 ").append(gapPx).append("px ").append(gapPx).append("px 0\">")
              .append(item).append("</div>")
              .append("<!--[if mso]></td><![endif]-->");
        }
        sb.append("<!--[if mso]></tr></table><![endif]-->").append("</div>");
        return sb.toString();
    }

    public static String badges(List<Badge> list) {
        List<String> items = new ArrayList<>();
        for (Badge b : list) if (b != null && b.label() != null && !b.label().isBlank()) items.add(badge(b));
        return inlineWrap(items, 6, null);
    }

    /** Çip listesi (ör. envanterdeki operasyonel bayraklar) — ikincil rozet dilinde, satır sarar. */
    public static String chips(List<String> labels) {
        List<String> items = new ArrayList<>();
        for (String l : labels) {
            items.add("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>"
                    + "<td bgcolor=\"" + SECONDARY + "\" style=\"background-color:" + SECONDARY + ";border:1px solid " + BORDER
                    + ";border-radius:" + RADIUS_CONTROL + "px;padding:4px 10px;" + font() + "font-size:13px;line-height:18px;font-weight:500;color:" + FG + "\">"
                    + "&#10003;&nbsp;" + esc(l) + "</td></tr></table>");
        }
        return inlineWrap(items, 6, null);
    }

    /** Satır içi hap (tablo hücresi içinde kısa kod/etiket). Outlook'ta kare gölgelendirme olarak düşer. */
    public static String pill(String label) {
        return "<span style=\"display:inline-block;background-color:" + SECONDARY + ";border:1px solid " + BORDER + ";border-radius:"
                + RADIUS_CONTROL + "px;padding:1px 8px;font-size:12px;line-height:18px;color:" + FG + ";white-space:nowrap\">" + esc(label) + "</span>";
    }

    // ── Uyarı (Alert) — tonlu zemin + tam kenarlık + ikon; SOL ŞERİT YOK ───

    public static String icon(Tone t) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>"
                + "<td width=\"20\" height=\"20\" align=\"center\" valign=\"middle\" bgcolor=\"" + t.strong + "\" style=\"width:20px;height:20px;background-color:"
                + t.strong + ";border-radius:10px;" + font() + "font-size:12px;line-height:20px;font-weight:700;color:#ffffff;text-align:center;mso-line-height-rule:exactly\">"
                + t.glyph + "</td></tr></table>";
    }

    public static String alert(Tone t, String titleHtml, String bodyHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
          .append("<td bgcolor=\"").append(t.bg).append("\" style=\"background-color:").append(t.bg).append(";border:1px solid ").append(t.border)
          .append(";border-radius:").append(RADIUS_INNER).append("px;padding:12px 14px\">")
          .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
          .append("<td width=\"20\" valign=\"top\" style=\"width:20px;padding:1px 12px 0 0\">").append(icon(t)).append("</td>")
          .append("<td valign=\"top\" style=\"").append(font()).append("font-size:14px;line-height:22px;color:").append(t.text)
          .append(";word-break:break-word;overflow-wrap:anywhere\">");
        if (titleHtml != null && !titleHtml.isBlank()) {
            sb.append("<p style=\"margin:0;font-weight:600;color:").append(t.text).append("\">").append(titleHtml).append("</p>");
        }
        if (bodyHtml != null && !bodyHtml.isBlank()) {
            // div (p değil): gövde blok içerik (liste, satır sonları) taşıyabilir; p içinde p geçersiz.
            sb.append("<div style=\"margin:").append(titleHtml == null || titleHtml.isBlank() ? "0" : "2px 0 0").append(";color:").append(t.text)
              .append("\">").append(bodyHtml).append("</div>");
        }
        return sb.append("</td></tr></table></td></tr></table>").toString();
    }

    // ── İç kart (Card) ───────────────────────────────────────────────────────

    public static String card(String title, String descHtml, String bodyHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
          .append("<td bgcolor=\"").append(CARD).append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(BORDER)
          .append(";border-radius:").append(RADIUS_INNER).append("px;padding:16px;").append(font()).append("color:").append(FG).append("\">");
        if (title != null && !title.isBlank()) {
            sb.append("<p style=\"margin:0 0 ").append(descHtml == null || descHtml.isBlank() ? "10" : "2")
              .append("px;font-size:14px;line-height:20px;font-weight:600;color:").append(FG).append("\">").append(esc(title)).append("</p>");
        }
        if (descHtml != null && !descHtml.isBlank()) {
            sb.append("<p style=\"margin:0 0 12px;font-size:13px;line-height:20px;color:").append(MUTED).append("\">").append(descHtml).append("</p>");
        }
        return sb.append(bodyHtml).append("</td></tr></table>").toString();
    }

    // ── Anahtar-değer ────────────────────────────────────────────────────────

    /** Satır: HTML ve düz metin değerleri AYRI taşınır (stripHtml bitişikliği yaşanmaz). */
    public record Row(String label, String html, String text) {
        public static Row of(String label, String plain) {
            return new Row(label, esc(plain), plain == null ? "" : plain);
        }
    }

    public static String keyValue(List<Row> rows) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String line = i == rows.size() - 1 ? "0" : "1px solid " + DIVIDER;
            sb.append("<tr>")
              .append("<td class=\"kv-l\" width=\"36%\" valign=\"top\" style=\"width:36%;padding:9px 12px 9px 0;border-bottom:").append(line).append(";")
              .append(font()).append("font-size:13px;line-height:20px;color:").append(MUTED).append("\">").append(esc(r.label())).append("</td>")
              .append("<td class=\"kv-v\" valign=\"top\" style=\"padding:9px 0;border-bottom:").append(line).append(";")
              .append(font()).append("font-size:14px;line-height:20px;color:").append(FG)
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(r.html() == null || r.html().isBlank() ? "—" : r.html()).append("</td>")
              .append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    // ── Buton ────────────────────────────────────────────────────────────────

    public enum Variant { PRIMARY, OUTLINE, SECONDARY, DESTRUCTIVE }

    /** Buton tanımı (grup için). */
    public record Btn(String url, String label, Variant variant) { }

    /** VML v:roundrect otomatik genişleyemez → görünür etiketten px türet; içerik genişliğini asla aşmaz. */
    public static int vmlWidth(String label, int max) {
        String visible = label == null ? "" : label.replaceAll("&[a-zA-Z]+;|&#\\d+;", "x").replaceAll("<[^>]+>", "");
        return Math.max(160, Math.min(max, visible.length() * 8 + 48));
    }

    /**
     * Kurşun geçirmez buton: Outlook'ta VML (tam alan tıklanır, 44px), diğerlerinde {@code <a>}
     * (12+20+12 = 44px dokunma hedefi + kenarlık). Mobilde {@code .btn-full} tam genişlik yapar.
     */
    public static String button(String url, String label, Variant v, int maxWidth) {
        if (url == null || url.isBlank() || !safeHref(url)) return "";   // izinsiz şema: buton hiç çizilmez (BD1)
        String fill, stroke, color;
        switch (v == null ? Variant.PRIMARY : v) {
            case OUTLINE -> { fill = CARD; stroke = BORDER; color = FG; }
            case SECONDARY -> { fill = SECONDARY; stroke = SECONDARY; color = FG; }
            case DESTRUCTIVE -> { fill = MailTokens.DESTRUCTIVE; stroke = MailTokens.DESTRUCTIVE; color = "#ffffff"; }
            default -> { fill = PRIMARY; stroke = PRIMARY; color = "#ffffff"; }
        }
        String href = esc(url);
        String text = esc(label);
        return "<table role=\"presentation\" class=\"btn-t\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td align=\"center\">"
                + "<!--[if mso]><v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\" href=\"" + href
                + "\" style=\"height:44px;v-text-anchor:middle;width:" + vmlWidth(label, maxWidth) + "px;\" arcsize=\"14%\" strokecolor=\"" + stroke
                + "\" fillcolor=\"" + fill + "\"><w:anchorlock/><center style=\"color:" + color
                + ";font-family:'Segoe UI',Arial,sans-serif;font-size:14px;font-weight:bold;\">" + text + "</center></v:roundrect><![endif]-->"
                + "<!--[if !mso]><!--><a class=\"btn-full\" href=\"" + href + "\" target=\"_blank\" style=\"display:inline-block;background-color:" + fill
                + ";border:1px solid " + stroke + ";border-radius:" + RADIUS_CONTROL + "px;color:" + color + ";" + font()
                + "font-size:14px;line-height:20px;font-weight:600;padding:12px 20px;text-align:center;text-decoration:none\">" + text + "</a><!--<![endif]-->"
                + "</td></tr></table>";
    }

    /** Buton grubu: masaüstünde yan yana, dar ekranda (stil olsa da olmasa da) alt alta sarar. */
    public static String buttonGroup(List<Btn> buttons, int maxWidth) {
        List<String> items = new ArrayList<>();
        for (Btn b : buttons) {
            String h = button(b.url(), b.label(), b.variant(), maxWidth);
            if (!h.isEmpty()) items.add(h);
        }
        return inlineWrap(items, 8, "bg-item");
    }

    // ── İstatistik kutuları (fluid-hybrid) ───────────────────────────────────

    /** İstatistik kutusu: etiket, değer, değer rengi (null = ana metin), alt ipucu (null olabilir). */
    public record Stat(String label, String value, String color, String hint) {
        public static Stat of(String label, String value) { return new Stat(label, value, null, null); }
    }

    /** Bir istatistik kutusunun en dar hâli — bunun altına inecekse kutu alt satıra sarar. */
    static final int TILE_MIN = 96;

    /**
     * Kutular medya sorgusu OLMADAN sarar: her biri {@code width:100%;max-width:%P;min-width:96px}
     * inline-block — kap yeterince genişse satırda P'lik paylar (4'lü/3'lü), daraldıkça min-width
     * devreye girip alt satıra iner. Yüzde, kabın GERÇEK genişliğine göre çalışır (sabit px ile
     * 640px ekranda 4'lü satır 3+1'e bölünüyordu). Stil bloğu varsa {@code .tile}/{@code .tile3}
     * telefonda satırı eşit böler. Outlook: yüzde genişlikli hayalet tablo sütunları.
     */
    public static String stats(List<Stat> tiles) {
        int n = tiles.size();
        if (n == 0) return "";
        int perRow = n <= 4 ? n : (n <= 6 ? 3 : 4);
        String pct = perRow == 3 ? "33.33%" : (100 / perRow) + "%";
        String cls = n == 1 ? null : (n % 3 == 0 ? "tile3" : "tile");
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"font-size:0;line-height:0\">");
        sb.append("<!--[if mso]><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><![endif]-->");
        for (int i = 0; i < n; i++) {
            Stat s = tiles.get(i);
            if (i > 0 && i % perRow == 0) sb.append("<!--[if mso]></tr><tr><![endif]-->");
            sb.append("<!--[if mso]><td width=\"").append(pct).append("\" valign=\"top\"><![endif]-->")
              .append("<div").append(cls == null ? "" : " class=\"" + cls + "\"")
              .append(" style=\"display:inline-block;vertical-align:top;width:100%;min-width:").append(TILE_MIN).append("px;max-width:")
              .append(pct).append("\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"padding:0 8px 8px 0\">")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td bgcolor=\"").append(CARD).append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(BORDER)
              .append(";border-radius:").append(RADIUS_INNER).append("px;padding:12px 14px;").append(font()).append("\">")
              .append("<p style=\"margin:0;font-size:12px;line-height:16px;font-weight:500;color:").append(MUTED).append("\">").append(esc(s.label())).append("</p>")
              .append("<p style=\"margin:4px 0 0;font-size:22px;line-height:28px;font-weight:600;color:").append(s.color() == null ? FG : s.color())
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(esc(s.value())).append("</p>");
            if (s.hint() != null && !s.hint().isBlank()) {
                sb.append("<p style=\"margin:2px 0 0;font-size:12px;line-height:16px;color:").append(MUTED).append("\">").append(esc(s.hint())).append("</p>");
            }
            sb.append("</td></tr></table></td></tr></table></div><!--[if mso]></td><![endif]-->");
        }
        sb.append("<!--[if mso]></tr></table><![endif]-->").append("</td></tr></table>");
        return sb.toString();
    }

    // ── Veri tablosu ─────────────────────────────────────────────────────────

    /**
     * Sütun: {@code right} sağa hizalı (sayılar), {@code optional} düşük öncelik (telefonda gizlenir),
     * {@code nowrap} kısa değer masaüstünde tek satır ({@code .nw}; telefonda serbest).
     */
    public record Col(String label, boolean right, boolean optional, boolean nowrap) {
        public static Col of(String label) { return new Col(label, false, false, false); }
        /** Sayısal: sağa hizalı + tek satır. */
        public static Col num(String label) { return new Col(label, true, false, true); }
        public static Col opt(String label) { return new Col(label, false, true, false); }
        /** Kısa metin (tarih, kod, durum): tek satır. */
        public static Col nw(String label) { return new Col(label, false, false, true); }

        String cls() {
            String c = (optional ? "col-opt " : "") + (nowrap ? "nw" : "");
            return c.isBlank() ? "" : " class=\"" + c.strip() + "\"";
        }
    }

    /** Hücre: HTML + düz metin; {@code color} değer rengi (null = ana metin), {@code bold} vurgu. */
    public record Cell(String html, String text, String color, boolean bold) {
        public static Cell of(String plain) { return new Cell(esc(plain), plain == null ? "" : plain, null, false); }
        public static Cell of(String plain, String color, boolean bold) { return new Cell(esc(plain), plain == null ? "" : plain, color, bold); }
        public static Cell html(String html, String text) { return new Cell(html, text, null, false); }
    }

    /** Bu satır sayısının üstünde mobil kart kopyası üretilmez (gövde boyutu — Gmail 102 KB'ta kırpar). */
    public static final int STACK_MAX_ROWS = 30;

    /**
     * Veri tablosu. Masaüstü tablosu ({@code .d-only}) + telefonda satır başına istif kart
     * ({@code .m-only}, varsayılan gizli, Outlook'a hiç gitmez). Satır sayısı {@link #STACK_MAX_ROWS}'u
     * aşarsa kopya üretilmez; düşük öncelikli sütunlar ({@code .col-opt}) telefonda gizlenir.
     * Stil atan istemcide masaüstü tablosu kalır: hücreler kırılabilir, yatay kaydırma çıkmaz.
     */
    public static String dataTable(List<Col> cols, List<List<Cell>> rows, boolean stack) {
        boolean cards = stack && rows.size() <= STACK_MAX_ROWS && cols.size() > 1;
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\"").append(cards ? " class=\"d-only\"" : "")
          .append(" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate;border:1px solid ")
          .append(BORDER).append(";border-radius:").append(RADIUS_INNER).append("px\"><tr>");
        for (int c = 0; c < cols.size(); c++) {
            Col col = cols.get(c);
            String radius = c == 0 && c == cols.size() - 1 ? RADIUS_INNER + "px " + RADIUS_INNER + "px 0 0"
                    : c == 0 ? RADIUS_INNER + "px 0 0 0" : c == cols.size() - 1 ? "0 " + RADIUS_INNER + "px 0 0" : "0";
            sb.append("<td").append(col.optional() ? " class=\"col-opt\"" : "").append(" bgcolor=\"").append(SUBTLE)
              .append("\" align=\"").append(col.right() ? "right" : "left").append("\" style=\"background-color:").append(SUBTLE)
              .append(";padding:10px 12px;border-bottom:1px solid ").append(BORDER).append(";border-radius:").append(radius).append(";")
              .append(font()).append("font-size:12px;line-height:16px;font-weight:600;color:").append(MUTED).append(";text-align:")
              .append(col.right() ? "right" : "left").append("\">").append(esc(col.label())).append("</td>");
        }
        sb.append("</tr>");
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> row = rows.get(r);
            String line = r == rows.size() - 1 ? "0" : "1px solid " + DIVIDER;
            sb.append("<tr>");
            for (int c = 0; c < cols.size(); c++) {
                Col col = cols.get(c);
                Cell cell = c < row.size() ? row.get(c) : Cell.of("—");
                sb.append("<td").append(col.cls()).append(" valign=\"top\" align=\"")
                  .append(col.right() ? "right" : "left").append("\" style=\"padding:10px 12px;border-bottom:").append(line).append(";")
                  .append(font()).append("font-size:13px;line-height:20px;color:").append(cell.color() == null ? FG : cell.color())
                  .append(cell.bold() || c == 0 ? ";font-weight:600" : "").append(";text-align:").append(col.right() ? "right" : "left")
                  // Çok sütunlu tabloda ilk (kimlik) sütunu harf harf ezilmesin — tablo düzeni kırılabilir
                  // hücreleri en dar hâline büzebiliyor; 120px ≈ bir alan adı parçası. YALNIZ telefonda kart
                  // kopyası olan (masaüstü-only) tabloda: her ekranda görünen tabloda telefonda taşma yapar.
                  .append(cards && c == 0 && cols.size() >= 4 ? ";min-width:120px" : "")
                  .append(";word-break:break-word;overflow-wrap:anywhere\">").append(blankDash(cell.html())).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        if (cards) sb.append(stackedCards(cols, rows));
        return sb.toString();
    }

    /** Telefon kartları — her satır bir kart: ilk sütun başlık, kalanlar etiket/değer. Outlook'a hiç gitmez. */
    private static String stackedCards(List<Col> cols, List<List<Cell>> rows) {
        StringBuilder sb = new StringBuilder("<!--[if !mso]><!--><div class=\"m-only\" style=\"display:none;max-height:0;overflow:hidden;mso-hide:all\">");
        for (List<Cell> row : rows) {
            Cell head = row.isEmpty() ? Cell.of("—") : row.get(0);
            sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate;margin:0 0 8px\"><tr>")
              .append("<td bgcolor=\"").append(CARD).append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(BORDER)
              .append(";border-radius:").append(RADIUS_INNER).append("px;padding:12px 14px;").append(font()).append("\">")
              .append("<p style=\"margin:0 0 6px;font-size:14px;line-height:20px;font-weight:600;color:").append(head.color() == null ? FG : head.color())
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(blankDash(head.html())).append("</p>")
              .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
            for (int c = 1; c < cols.size(); c++) {
                Cell cell = c < row.size() ? row.get(c) : Cell.of("—");
                sb.append("<tr><td valign=\"top\" style=\"padding:3px 12px 3px 0;font-size:12px;line-height:18px;color:").append(MUTED).append("\">")
                  .append(esc(cols.get(c).label())).append("</td>")
                  .append("<td valign=\"top\" align=\"right\" style=\"padding:3px 0;font-size:13px;line-height:18px;text-align:right;color:")
                  .append(cell.color() == null ? FG : cell.color()).append(cell.bold() ? ";font-weight:600" : "")
                  .append(";word-break:break-word;overflow-wrap:anywhere\">").append(blankDash(cell.html())).append("</td></tr>");
            }
            sb.append("</table></td></tr></table>");
        }
        return sb.append("</div><!--<![endif]-->").toString();
    }

    private static String blankDash(String html) {
        return html == null || html.isBlank() ? "—" : html;
    }

    // ── Liste / adımlar / kod ────────────────────────────────────────────────

    /** Numaralı adımlar — numara küçük ikincil daire (Outlook'ta kare). */
    public static String steps(List<String> itemsHtml) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
        for (int i = 0; i < itemsHtml.size(); i++) {
            String pad = i == itemsHtml.size() - 1 ? "0" : "10px";
            sb.append("<tr><td width=\"22\" valign=\"top\" style=\"width:22px;padding:0 10px ").append(pad).append(" 0\">")
              .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td width=\"22\" height=\"22\" align=\"center\" valign=\"middle\" bgcolor=\"").append(SECONDARY).append("\" style=\"width:22px;height:22px;background-color:")
              .append(SECONDARY).append(";border:1px solid ").append(BORDER).append(";border-radius:11px;").append(font())
              .append("font-size:12px;line-height:20px;font-weight:600;color:").append(FG).append(";text-align:center;mso-line-height-rule:exactly\">")
              .append(i + 1).append("</td></tr></table></td>")
              .append("<td valign=\"top\" style=\"padding:1px 0 ").append(pad).append(";").append(font()).append("font-size:14px;line-height:22px;color:").append(FG)
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(itemsHtml.get(i)).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    public static String bullets(List<String> itemsHtml) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
        for (int i = 0; i < itemsHtml.size(); i++) {
            String pad = i == itemsHtml.size() - 1 ? "0" : "6px";
            sb.append("<tr><td width=\"14\" valign=\"top\" style=\"width:14px;padding:0 6px ").append(pad).append(" 0;").append(font())
              .append("font-size:14px;line-height:22px;color:").append(MUTED).append("\">&#8226;</td>")
              .append("<td valign=\"top\" style=\"padding:0 0 ").append(pad).append(";").append(font()).append("font-size:14px;line-height:22px;color:").append(FG)
              .append(";word-break:break-word;overflow-wrap:anywhere\">").append(itemsHtml.get(i)).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Kod/çıktı bloğu — satır sonları {@code <br>} (Outlook pre-wrap tanımaz), uzun satır kırılır. */
    public static String pre(String text) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>"
                + "<td class=\"mono\" bgcolor=\"" + SUBTLE + "\" style=\"background-color:" + SUBTLE + ";border:1px solid " + BORDER + ";border-radius:"
                + RADIUS_CONTROL + "px;padding:12px 14px;font-family:" + MONO + ";font-size:12px;line-height:18px;color:" + FG
                + ";white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere\">" + escBr(text) + "</td></tr></table>";
    }

    // ── İlerleme / zaman çizelgesi / görsel / ayraç ──────────────────────────

    /** İlerleme çubuğu — iki hücre, yüzde genişlik + bgcolor (Outlook-güvenli). */
    public static String progress(int pct, String color) {
        int p = Math.max(2, Math.min(100, pct));
        int rest = 100 - p;
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>");
        sb.append("<td width=\"").append(p).append("%\" height=\"8\" bgcolor=\"").append(color).append("\" style=\"height:8px;background-color:").append(color)
          .append(";border-radius:").append(rest > 0 ? "4px 0 0 4px" : "4px").append(";font-size:0;line-height:0;mso-line-height-rule:exactly\">&nbsp;</td>");
        if (rest > 0) {
            sb.append("<td width=\"").append(rest).append("%\" height=\"8\" bgcolor=\"").append(TRACK).append("\" style=\"height:8px;background-color:").append(TRACK)
              .append(";border-radius:0 4px 4px 0;font-size:0;line-height:0;mso-line-height-rule:exactly\">&nbsp;</td>");
        }
        return sb.append("</tr></table>").toString();
    }

    /** Zaman çizelgesi noktası. */
    public record Point(String label, String date, boolean emphasized) { }

    /** Dikey zaman çizelgesi — her genişlikte aynı (mobil-öncelikli), vurgulu nokta ton rengiyle. */
    public static String timeline(List<Point> points, String accent) {
        StringBuilder sb = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            String dot = p.emphasized() ? accent : "#a1a1aa";
            String pad = i == points.size() - 1 ? "0" : "8px";
            sb.append("<tr><td width=\"8\" valign=\"top\" style=\"width:8px;padding:6px 10px 0 0\">")
              .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate\"><tr>")
              .append("<td width=\"8\" height=\"8\" bgcolor=\"").append(dot).append("\" style=\"width:8px;height:8px;background-color:").append(dot)
              .append(";border-radius:4px;font-size:0;line-height:0;mso-line-height-rule:exactly\">&nbsp;</td></tr></table></td>")
              .append("<td valign=\"top\" style=\"padding:0 12px ").append(pad).append(" 0;").append(font()).append("font-size:13px;line-height:20px;color:")
              .append(p.emphasized() ? FG : MUTED).append(p.emphasized() ? ";font-weight:600" : "").append("\">").append(esc(p.label())).append("</td>")
              .append("<td valign=\"top\" align=\"right\" style=\"padding:0 0 ").append(pad).append(";").append(font())
              .append("font-size:13px;line-height:20px;text-align:right;color:").append(p.emphasized() ? accent : FG)
              .append(p.emphasized() ? ";font-weight:600" : "").append("\">").append(esc(p.date())).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    /** Büyük metrik (ör. "25 gün kaldı") — rakam ton renginde, birim ikincil. */
    public static String metric(String value, String unit, String color) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td valign=\"bottom\" style=\"" + font() + "font-size:44px;line-height:44px;font-weight:600;letter-spacing:-1px;color:" + color
                + ";mso-line-height-rule:exactly\">" + esc(value) + "</td>"
                + (unit == null || unit.isBlank() ? ""
                    : "<td valign=\"bottom\" style=\"padding:0 0 5px 10px;" + font() + "font-size:15px;line-height:20px;color:" + MUTED + "\">" + esc(unit) + "</td>")
                + "</tr></table>";
    }

    /** Akışkan görsel — width özniteliği (Outlook) + {@code max-width:100%} (diğerleri). */
    public static String image(String src, String alt, int width) {
        return "<img src=\"" + esc(src) + "\" width=\"" + width + "\" alt=\"" + esc(alt) + "\" border=\"0\" style=\"display:block;width:100%;max-width:"
                + width + "px;height:auto;border:1px solid " + BORDER + ";border-radius:" + RADIUS_INNER + "px\">";
    }

    public static String separator() {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td height=\"1\" style=\"height:1px;border-top:1px solid " + BORDER + ";font-size:0;line-height:0;mso-line-height-rule:exactly\">&nbsp;</td></tr></table>";
    }

    // ── Düz metin paritesi ───────────────────────────────────────────────────

    /**
     * MailDoc'un ürettiği HTML → aynı belgenin yazılmış düz metni. Anahtar HTML dizesinin KENDİSİ
     * (zayıf referans): üretici HTML'i tuttuğu sürece gönderim hunisi metni bulur; HTML bırakılınca
     * kayıt çöp toplayıcıyla gider. Böylece {@code sendHtml(html)} çağıranlar imza değiştirmeden
     * multipart/alternative gönderir.
     */
    private static final Map<String, String> TEXT_BY_HTML = Collections.synchronizedMap(new WeakHashMap<>());

    static void rememberText(String html, String text) {
        if (html != null && text != null) TEXT_BY_HTML.put(html, text);
    }

    /** Gönderim hunisi için düz metin: MailDoc'un yazdığı metin; yoksa (ör. eski kayıtlı HTML) HTML'den türetilir. */
    public static String plainTextFor(String html) {
        if (html == null) return "";
        String t = TEXT_BY_HTML.get(html);
        return t != null ? t : htmlToText(html);
    }

    private static final Pattern MSO_BLOCK = Pattern.compile("(?is)<!--\\[if mso\\]>.*?<!\\[endif\\]-->");
    private static final Pattern MOBILE_COPY = Pattern.compile("(?is)<div class=\"m-only\".*?</div><!--<!\\[endif\\]-->");
    private static final Pattern PREHEADER = Pattern.compile("(?is)<div class=\"preheader\".*?</div>");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s[^>]*?href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");

    /** Kayıtlı/yabancı HTML'den okunur düz metin (yedek yol). */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html.replaceAll("(?is)<head.*?</head>", "").replaceAll("(?is)<style.*?</style>", "");
        s = MSO_BLOCK.matcher(s).replaceAll("");
        s = MOBILE_COPY.matcher(s).replaceAll("");
        s = PREHEADER.matcher(s).replaceAll("");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|tr|h1|h2|h3|li|table|ul|ol|pre)>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "- ");
        s = s.replaceAll("(?i)</t[dh]>", " ");
        Matcher m = ANCHOR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = unescape(m.group(1));
            String label = m.group(2).replaceAll("<[^>]+>", "").trim();
            String plainLabel = unescape(label);
            boolean showUrl = (url.startsWith("http://") || url.startsWith("https://")) && !plainLabel.equals(url);
            m.appendReplacement(sb, Matcher.quoteReplacement(showUrl ? label + " (" + url + ")" : label));
        }
        m.appendTail(sb);
        s = unescape(sb.toString().replaceAll("<[^>]+>", ""));
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : s.split("\n", -1)) {
            String t = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (t.isEmpty()) {
                if (++blank <= 1 && out.length() > 0) out.append('\n');
                continue;
            }
            blank = 0;
            out.append(t).append('\n');
        }
        return out.toString().trim();
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            // Görünmez dolgu karakterleri (önizleme dolgusu) düz metne taşınmaz.
            String rep = cp == 847 || cp == 8199 || cp == 65279 || cp == 8204 ? "" : new String(Character.toChars(cp));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&middot;", "·").replace("&rarr;", "→").replace("&zwnj;", "")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
