package com.sitemonitor.service.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bildirimlere eklenen "Ne yapılmalı" runbook notu (2026-10-01, ürün sahibi taahhüdü: "Not yalnız izlemeye not
 * girilmişse bildirimin sonuna eklenir; mevcut içerik değişmez").
 *
 * <p>Kaynak, izleme detayının "Rehber &amp; Notlar" sekmesindeki REHBER bloğudur ({@code monitor_guide}, markdown).
 * Bu sınıf yalnız SAF dönüşümleri taşır — veri okumaz:
 * <ul>
 *   <li>{@link #toPlainText}: markdown → güvenli düz metin (biçim işaretleri, HTML etiketleri, görseller atılır).
 *       E-postada çıktı yine {@link MailKit#escBr} ile KAÇIRILIR; hiçbir koşulda ham HTML enjekte edilmez.</li>
 *   <li>{@link #truncate}: e-postada {@value #EMAIL_MAX}, webhook'ta {@value #WEBHOOK_MAX} karakter + "…".</li>
 *   <li>{@link #appendTo} / {@link #textBlock}: alarm e-postasının SONUNA (gövde bitince, alt bilgiden önce) bölüm.</li>
 *   <li>{@link #webhookMessage}: Teams/Slack mesaj metninin sonuna kısa ek.</li>
 * </ul>
 *
 * <p><b>Bayt-özdeşlik sözleşmesi:</b> bağlamda {@value #CTX_KEY} yoksa ya da boşsa HER yardımcı girdisini aynen
 * döndürür / hiçbir şey eklemez. Rehberi olmayan hedefin (yaygın durum) bildirimi bugünküyle bayt bayt aynıdır —
 * {@code RunbookNoteNoGuideIdentityTest} bunu pinler.
 */
public final class RunbookNote {

    private RunbookNote() { }

    /** Alarm e-posta bağlamındaki anahtar — değer: düz metne çevrilmiş ve {@value #EMAIL_MAX} karaktere kırpılmış rehber. */
    public static final String CTX_KEY = "runbook_text";
    /** E-postadaki rehber metni tavanı (karakter). */
    public static final int EMAIL_MAX = 1000;
    /** Teams/Slack mesajındaki rehber metni tavanı (karakter). */
    public static final int WEBHOOK_MAX = 300;

    public static final String TITLE = "Ne yapılmalı";
    public static final String SUBTITLE = "İzlemenin rehberinden";
    public static final String HINT = "Rehberin tamamı: Site Monitor → izleme detayı → \"Rehber & Notlar\" sekmesi.";
    static final String ELLIPSIS = "…";

    private static final Pattern FENCE = Pattern.compile("(?m)^\\s*(```|~~~).*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(\\s*([^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<((?:https?|mailto):[^>\\s]+)>");
    /** Gerçek HTML etiketi / yorumu — "eşik &lt; 5 ve &gt; 3" gibi düz metindeki işaretlere dokunmaz. */
    private static final Pattern TAG = Pattern.compile("(?s)<!--.*?-->|</?[A-Za-z][A-Za-z0-9:-]*(?:\\s[^<>]{0,500})?/?>");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern HEADING_TRAIL = Pattern.compile("(?m)\\s+#+\\s*$");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s{0,3}>\\s?");
    private static final Pattern BULLET = Pattern.compile("(?m)^(\\s*)[-*+]\\s+(?:\\[[ xX]\\]\\s+)?");
    private static final Pattern RULE = Pattern.compile("(?m)^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])");
    private static final Pattern ITALIC_UNDER = Pattern.compile("(?<![_\\w])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![_\\w])");
    private static final Pattern CODE = Pattern.compile("`+([^`\\n]+?)`+");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    /**
     * Markdown → düz metin. Biçim işaretleri atılır, metin korunur: başlık {@code #}'ları, kalın/italik/üstü çizili/
     * satır içi kod işaretleri, alıntı {@code >}, kod çiti satırları, yatay çizgi ve tablo ayraç satırları, HTML
     * etiketleri (içerikleri kalır), görseller (yalnız alt metni). Bağlantı {@code [metin](adres)} → "metin (adres)".
     * Madde işaretleri "• " olur. Boş girdi → "".
     */
    public static String toPlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        s = CONTROL.matcher(s).replaceAll("");
        s = FENCE.matcher(s).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("$1");
        s = replaceLinks(s);
        s = AUTOLINK.matcher(s).replaceAll("$1");
        s = TAG.matcher(s).replaceAll("");
        s = RULE.matcher(s).replaceAll("");
        s = TABLE_SEP.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = HEADING_TRAIL.matcher(s).replaceAll("");
        s = QUOTE.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("$1• ");
        s = CODE.matcher(s).replaceAll("$1");
        s = BOLD.matcher(s).replaceAll("$2");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = ITALIC_STAR.matcher(s).replaceAll("$1");
        s = ITALIC_UNDER.matcher(s).replaceAll("$1");
        s = decodeEntities(s);
        List<String> lines = new ArrayList<>();
        for (String line : s.split("\n", -1)) lines.add(stripTrailing(line));
        s = String.join("\n", lines);
        s = BLANK_LINES.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    private static String replaceLinks(String s) {
        Matcher m = LINK.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String text = m.group(1).strip();
            String url = m.group(2).strip();
            String rep = text.equalsIgnoreCase(url) ? text : text + " (" + url + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    /**
     * {@code max} karakteri aşan metni keser ve "…" ekler (toplam ≤ max). Mümkünse son 40 karakter içindeki bir boşlukta
     * keser (kelime ortasında bitmesin); vekil çifti (emoji) bölünmez. {@code null} → "".
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= ELLIPSIS.length()) return ELLIPSIS;
        int cut = max - ELLIPSIS.length();
        if (Character.isLowSurrogate(s.charAt(cut)) && cut > 0) cut--;
        int space = -1;
        for (int i = cut; i > Math.max(0, cut - 40); i--) {
            if (Character.isWhitespace(s.charAt(i))) { space = i; break; }
        }
        if (space > 0) cut = space;
        return stripTrailing(s.substring(0, cut)) + ELLIPSIS;
    }

    /** Bağlamdaki rehber metni ({@value #CTX_KEY}); yoksa / boşsa null. */
    public static String fromContext(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Object v = ctx.get(CTX_KEY);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /**
     * Alarm e-postasının sonuna "Ne yapılmalı" kartı (HTML + düz metin pariteli) — bağlamda rehber yoksa HİÇBİR ŞEY
     * eklemez. Metin {@link MailKit#escBr} ile kaçırılır (HTML enjeksiyonu yok); kart gövde bitince, alt bilgiden önce.
     */
    public static void appendTo(MailDoc d, Map<String, Object> ctx) {
        String text = fromContext(ctx);
        if (d == null || text == null) return;
        String body = truncate(text, EMAIL_MAX);
        String html = MailKit.space(MailKit.paragraph(MailKit.escBr(body)), 12) + MailKit.note(MailKit.esc(HINT));
        d.card(TITLE, SUBTITLE, html, body + "\n" + HINT);
    }

    /** Düz metin alarm e-postası için aynı bölüm (EmailTemplateBuilder.buildText) — rehber yoksa "". */
    public static String textBlock(Map<String, Object> ctx) {
        String text = fromContext(ctx);
        if (text == null) return "";
        return "\n" + TITLE + ":\n" + truncate(text, EMAIL_MAX) + "\n" + HINT + "\n";
    }

    /**
     * Teams/Slack mesaj metni: rehber varsa sonuna kısa ek (boşluklar tek satıra indirgenir, {@value #WEBHOOK_MAX}
     * karakter); yoksa {@code message} AYNEN döner.
     */
    public static String webhookMessage(String message, String plainGuide) {
        if (plainGuide == null || plainGuide.isBlank()) return message;
        String flat = plainGuide.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) return message;
        return (message == null ? "" : message) + "\n\n" + TITLE + ": " + truncate(flat, WEBHOOK_MAX) + "\n\n" + HINT;
    }
}
