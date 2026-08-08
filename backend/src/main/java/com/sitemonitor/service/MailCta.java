package com.sitemonitor.service;

/**
 * Alarm e-postalarının altındaki "olay aksiyonu" butonları — olayın detayına git / olaya yorum yaz.
 *
 * <p>Bilinçli olarak <b>static</b> (Spring bean DEĞİL): şablon sınıflarının kurucuları değişmesin,
 * mevcut testler ({@code new EmailNotificationService(...)}) kırılmasın.
 *
 * <p>Buradaki "olay" = {@code AlertEvent} (makine üretimi alarm), uygulamada {@code ?tab=incidents}
 * ekranı. Ayrı ve ilgisiz olan manuel SRE kaydı ({@code IncidentRecord} / {@code incident-history})
 * ile karıştırılmamalı.
 *
 * <p>Olay kimliği yoksa tüm üreticiler <b>boş string</b> döner → çıktı bugünküyle bayt-bayt aynı
 * kalır (eski bağlamlar, fırtına postaları, domain'siz basit alarmlar).
 */
final class MailCta {

    private MailCta() { }

    static final String LABEL_DETAILS = "Olay detayını görüntüle";
    static final String LABEL_COMMENT = "Olaya yorum yap";
    static final String CAPTION = "BU OLAY İÇİN HIZLI AKSİYONLAR";

    /** {@code <base>/?tab=incidents&incident=<id>} — kimlik yoksa "". */
    static String incidentDetailsUrl(String baseUrl, Object alertEventId) {
        String id = normId(alertEventId);
        if (id == null || baseUrl == null || baseUrl.isBlank()) return "";
        return trimBase(baseUrl) + "/?tab=incidents&incident=" + id;
    }

    /** Detay URL'i + {@code &action=comment} — açılışta yorum kutusu açılır. */
    static String incidentCommentUrl(String baseUrl, Object alertEventId) {
        String url = incidentDetailsUrl(baseUrl, alertEventId);
        return url.isEmpty() ? "" : url + "&action=comment";
    }

    /**
     * İki ikincil (outline) buton — TEK satırda yan yana, VML + HTML çift dallı.
     * Birincil CTA (dolu zemin) olduğu gibi kalır; bunlar ondan ince bir ayraç ve küçük bir
     * başlıkla ayrılır, böylece üç buton yığılmış görünmez.
     */
    static String incidentActionRow(String baseUrl, Object alertEventId, String accent) {
        String details = incidentDetailsUrl(baseUrl, alertEventId);
        if (details.isEmpty()) return "";
        String comment = incidentCommentUrl(baseUrl, alertEventId);
        String color = (accent == null || accent.isBlank()) ? "#1e293b" : accent;

        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 4px'>"
            + "<tr><td align='center' style='border-top:1px solid #e2e8f0;padding-top:14px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.06em;color:#94a3b8;margin-bottom:10px'>"
            + CAPTION + "</div>"
            + "<!--[if mso]>"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center'><tr>"
            + vmlOutline(details, LABEL_DETAILS, color)
            + "<td width='12' style='width:12px;font-size:0;line-height:0'>&nbsp;</td>"
            + vmlOutline(comment, LABEL_COMMENT, color)
            + "</tr></table>"
            + "<![endif]-->"
            + "<!--[if !mso]><!-->"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center'><tr>"
            + htmlOutline(details, LABEL_DETAILS, color)
            + "<td width='12' style='width:12px;font-size:0;line-height:0'>&nbsp;</td>"
            + htmlOutline(comment, LABEL_COMMENT, color)
            + "</tr></table>"
            + "<!--<![endif]-->"
            + "</td></tr></table>";
    }

    /** Düz metin parite satırları — kimlik yoksa "". */
    static String incidentActionText(String baseUrl, Object alertEventId) {
        String details = incidentDetailsUrl(baseUrl, alertEventId);
        if (details.isEmpty()) return "";
        return "\nOlay detayı: " + details
             + "\nYorum yap:   " + incidentCommentUrl(baseUrl, alertEventId) + "\n";
    }

    /** VML v:roundrect auto-size yapamaz → görünür etiketten px genişlik türet (200–600 arası). */
    static int vmlWidth(String label) {
        String visible = label == null ? "" : label
                .replaceAll("&[a-zA-Z]+;|&#\\d+;", "x")
                .replaceAll("<[^>]+>", "");
        return Math.max(200, Math.min(600, visible.length() * 9 + 56));
    }

    private static String vmlOutline(String url, String label, String accent) {
        return "<td align='center'>"
            + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
            + " href=\"" + esc(url) + "\" style=\"height:36px;v-text-anchor:middle;width:" + vmlWidth(label) + "px;\""
            + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"#FFFFFF\">"
            + "<w:anchorlock/>"
            + "<center style=\"color:" + accent + ";font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:13px;font-weight:bold;\">"
            + label + "</center></v:roundrect></td>";
    }

    private static String htmlOutline(String url, String label, String accent) {
        return "<td align='center' bgcolor='#FFFFFF' style='background:#FFFFFF;border:1px solid " + accent
            + ";border-radius:6px'>"
            + "<a href='" + esc(url) + "' target='_blank' style='display:inline-block;padding:10px 18px;color:" + accent
            + ";text-decoration:none;font-size:13px;font-weight:bold;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>" + label + "</a></td>";
    }

    /** Sondaki bölü işaretlerini at (ayar "https://x/" gibi bittiğinde "//?tab=" oluşmasın). */
    private static String trimBase(String base) {
        return base.replaceAll("/+$", "");
    }

    /** Yalnız pozitif tam sayı kabul: ctx'ten gelen "null"/boş/çöp değer link üretmesin. */
    private static String normId(Object id) {
        if (id == null) return null;
        String s = String.valueOf(id).trim();
        return s.matches("\\d+") && !s.equals("0") ? s : null;
    }

    /** href'ler hem tek hem çift tırnak içinde geçiyor → beş karakterin hepsi kaçırılır. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
