package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TÜM alarm e-postaları (DOMAINMON_*, sertifika süre bitişi, uptime/ACCESSIBILITY, PORT_DOWN, DNS_*,
 * KEYWORD, PING_DOWN, network outage) tek merkezden bu builder'dan geçer — içerik veri parametre,
 * tasarım burada. Executive-premium, table-based, tümü inline CSS (Outlook/Exchange güvenli):
 * 640px ortalı kart, koyu-lacivert üst bant, ince severity şeridi + zarif rozet, hero metrik,
 * ayraç-çizgili detay tablosu, "Önerilen Aksiyon" kutusu + VML bulletproof CTA, footer. HTML + plain-text.
 */
@Component
@RequiredArgsConstructor
public class EmailTemplateBuilder {

    private final AppSettingsService appSettings;

    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    // Severity renkleri (spec) ve Türkçe etiketleri. Sistemdeki alarm seviyeleri: CRITICAL/HIGH/WARNING/MEDIUM/INFO.
    private static final String C_CRITICAL = "#C0392B", C_HIGH = "#D68910", C_MEDIUM = "#2874A6", C_INFO = "#1E8449";
    private static final String NAVY = "#0F1B2D", INK = "#1F2937", MUTED = "#6B7280", LINE = "#E5E8EC",
            SOFT = "#F7F8FA", PILL_BG = "#EEF1F4", PILL_INK = "#475569";

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", new Locale("tr", "TR"));

    private static final String LIGHT_SCHEME_META =
            "<meta name='color-scheme' content='light only'><meta name='supported-color-schemes' content='light only'>";

    /** Alarm e-postası girdisi (içerik verisi). */
    public record AlertMail(String alertType, String level, String domain, String message,
                            Integer daysRemaining, Map<String, Object> ctx, String teamName) {}

    // ── Severity ───────────────────────────────────────────────────────────────
    private static String severityColor(String level) {
        if (level == null) return C_MEDIUM;
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> C_CRITICAL;
            case "HIGH" -> C_HIGH;
            case "INFO", "LOW" -> C_INFO;
            default -> C_MEDIUM;   // WARNING/MEDIUM
        };
    }
    static String severityLabel(String level) {
        if (level == null) return "ORTA";
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH" -> "YÜKSEK";
            case "INFO", "LOW" -> "BİLGİ";
            default -> "ORTA";
        };
    }

    private static boolean isDomain(String t) {
        return t != null && (t.startsWith("DOMAINMON_") || "DOMAIN_EXPIRY".equals(t));
    }

    /** alertType → SPA deep-link tab (CTA butonu). */
    private static String tabFor(String t) {
        if (t == null) return "dashboard";
        if (isDomain(t)) return "domain";
        if ("ACCESSIBILITY".equals(t)) return "status";
        if ("PORT_DOWN".equals(t)) return "port";
        if (t.startsWith("DNS_")) return "dns";
        if ("KEYWORD".equals(t)) return "keyword";
        if ("PING_DOWN".equals(t)) return "ping";
        return "dashboard";   // sertifika
    }

    // ── HTML ─────────────────────────────────────────────────────────────────
    public String buildHtml(AlertMail m) {
        String color = severityColor(m.level());
        String badge = severityLabel(m.level());
        String domain = esc(m.domain());
        String metric = m.daysRemaining() != null ? String.valueOf(m.daysRemaining()) : "!";
        boolean showMetric = m.daysRemaining() != null;

        StringBuilder rows = new StringBuilder();
        for (String[] kv : detailRows(m)) rows.append(row(kv[0], kv[1]));

        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String href = cta + "/?tab=" + tabFor(m.alertType())
                + (m.domain() != null ? "&domain=" + urlenc(m.domain()) : "");

        String checkedAt = strCtx(m.ctx(), "checked_at");
        String actionText = actionParagraph(m);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>")
          .append("<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append(LIGHT_SCHEME_META)
          .append("<!--[if mso]><style>table,td,div,p,a,h1,h2{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->")
          .append("</head>")
          .append("<body style='margin:0;padding:0;background:#EDEFF2;font-family:\"Segoe UI\",\"Helvetica Neue\",Arial,sans-serif;color:").append(INK).append("'>")
          // Dış ortalayıcı
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#EDEFF2' style='background:#EDEFF2;mso-table-lspace:0;mso-table-rspace:0'>")
          .append("<tr><td align='center' style='padding:26px 12px'>")
          .append("<table role='presentation' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#FFFFFF' style='width:640px;max-width:640px;background:#FFFFFF;border:1px solid ").append(LINE).append(";border-radius:10px;overflow:hidden'>")
          // Üst bant — koyu lacivert wordmark + ENTERPRISE
          .append("<tr><td bgcolor='").append(NAVY).append("' style='background-color:").append(NAVY).append(";padding:18px 26px'>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>")
          .append("<td align='left' style='font-size:18px;font-weight:700;letter-spacing:.02em;color:#FFFFFF'>CertMonitor</td>")
          .append("<td align='right' style='font-size:10px;font-weight:700;letter-spacing:.18em;color:#8DA2BF'>ENTERPRISE</td>")
          .append("</tr></table></td></tr>")
          // Severity şeridi (üst çizgi)
          .append("<tr><td bgcolor='").append(color).append("' style='background-color:").append(color).append(";font-size:0;line-height:0;height:4px'>&nbsp;</td></tr>")
          // Gövde
          .append("<tr><td style='padding:26px 30px 8px'>")
          // Rozet
          .append("<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr>")
          .append("<td bgcolor='").append(color).append("' style='background-color:").append(color).append(";border-radius:4px;padding:4px 10px;font-size:11px;font-weight:700;letter-spacing:.08em;color:#FFFFFF'>").append(badge).append("</td>")
          .append("</tr></table>")
          // Hero
          .append("<div style='margin:18px 0 2px'>");
        if (showMetric) {
            sb.append("<span style='font-size:56px;line-height:1;font-weight:300;color:").append(color).append("'>").append(esc(metric)).append("</span>")
              .append("<span style='font-size:15px;color:").append(MUTED).append(";margin-left:8px'>").append(m.daysRemaining() != null && isDomain(m.alertType()) ? "gün kaldı" : "gün").append("</span>");
        }
        sb.append("</div>")
          .append("<div style='font-size:22px;font-weight:700;color:").append(INK).append(";margin:6px 0 4px'>").append(domain).append("</div>")
          .append("<div style='font-size:14px;line-height:1.55;color:").append(MUTED).append(";margin:0 0 4px'>").append(esc(shortSummary(m))).append("</div>")
          .append("</td></tr>")
          // Detay tablosu
          .append("<tr><td style='padding:8px 30px 4px'>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'>")
          .append(rows)
          .append("</table></td></tr>")
          // Önerilen Aksiyon
          .append("<tr><td style='padding:18px 30px 4px'>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='").append(SOFT).append("' style='background-color:").append(SOFT).append(";border:1px solid ").append(LINE).append(";border-radius:8px'>")
          .append("<tr><td style='padding:16px 18px'>")
          .append("<div style='font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:").append(MUTED).append(";margin:0 0 6px'>Önerilen Aksiyon</div>")
          .append("<div style='font-size:14px;line-height:1.55;color:").append(INK).append(";margin:0 0 14px'>").append(esc(actionText)).append("</div>")
          .append(ctaButton(href, "CertMonitor'de Görüntüle", color))
          .append("</td></tr></table></td></tr>")
          // Footer
          .append("<tr><td style='padding:22px 30px 24px'>")
          .append("<div style='border-top:1px solid ").append(LINE).append(";padding-top:12px;font-size:11px;line-height:1.6;color:#9AA3AF'>")
          .append("Bu e-posta CertMonitor ").append(esc(subsystemLabel(m.alertType()))).append(" tarafından otomatik gönderilmiştir")
          .append(checkedAt != null ? " · Son kontrol: " + esc(formatHuman(checkedAt)) : "")
          .append(" · Bildirim ayarları için yöneticinize başvurun.")
          .append("</div></td></tr>")
          .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    /** Plain-text multipart alternatifi. */
    public String buildText(AlertMail m) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CertMonitor] ").append(severityLabel(m.level())).append(" · ").append(nz(m.domain())).append('\n');
        sb.append(shortSummary(m)).append("\n\n");
        for (String[] kv : detailRows(m)) sb.append(kv[0]).append(": ").append(stripHtml(kv[1])).append('\n');
        sb.append('\n').append("Önerilen Aksiyon: ").append(actionParagraph(m)).append('\n');
        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        sb.append(cta).append("/?tab=").append(tabFor(m.alertType()))
          .append(m.domain() != null ? "&domain=" + urlenc(m.domain()) : "").append('\n');
        sb.append("\n— CertMonitor ").append(subsystemLabel(m.alertType()));
        return sb.toString();
    }

    // ── Çözüldü (resolved) — sade, INFO/yeşil ────────────────────────────────
    public String buildResolvedHtml(String domain, String alertType, String resolvedBy, String resolvedAt) {
        String d = esc(domain);
        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String href = cta + "/?tab=" + tabFor(alertType) + (domain != null ? "&domain=" + urlenc(domain) : "");
        StringBuilder rows = new StringBuilder();
        rows.append(row("Alan Adı", "<strong>" + d + "</strong>"));
        if (resolvedAt != null) rows.append(row("Çözülme", esc(formatHuman(resolvedAt))));
        if (resolvedBy != null && !resolvedBy.isBlank()) rows.append(row("Çözen", esc(resolvedBy)));
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
                + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" + LIGHT_SCHEME_META
                + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]--></head>"
                + "<body style='margin:0;padding:0;background:#EDEFF2;font-family:\"Segoe UI\",\"Helvetica Neue\",Arial,sans-serif;color:" + INK + "'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#EDEFF2' style='background:#EDEFF2'>"
                + "<tr><td align='center' style='padding:26px 12px'>"
                + "<table role='presentation' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#FFFFFF' style='width:640px;max-width:640px;background:#FFFFFF;border:1px solid " + LINE + ";border-radius:10px;overflow:hidden'>"
                + "<tr><td bgcolor='" + NAVY + "' style='background-color:" + NAVY + ";padding:18px 26px'><table role='presentation' width='100%'><tr>"
                + "<td align='left' style='font-size:18px;font-weight:700;color:#FFFFFF'>CertMonitor</td>"
                + "<td align='right' style='font-size:10px;font-weight:700;letter-spacing:.18em;color:#8DA2BF'>ENTERPRISE</td></tr></table></td></tr>"
                + "<tr><td bgcolor='" + C_INFO + "' style='background-color:" + C_INFO + ";font-size:0;line-height:0;height:4px'>&nbsp;</td></tr>"
                + "<tr><td style='padding:26px 30px 8px'>"
                + "<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr><td bgcolor='" + C_INFO + "' style='background-color:" + C_INFO + ";border-radius:4px;padding:4px 10px;font-size:11px;font-weight:700;letter-spacing:.08em;color:#FFFFFF'>ÇÖZÜLDÜ</td></tr></table>"
                + "<div style='font-size:22px;font-weight:700;color:" + INK + ";margin:16px 0 4px'>" + d + "</div>"
                + "<div style='font-size:14px;color:" + MUTED + "'>Alarm otomatik olarak kapandı.</div></td></tr>"
                + "<tr><td style='padding:8px 30px 4px'><table role='presentation' width='100%' style='border-collapse:collapse'>" + rows + "</table></td></tr>"
                + "<tr><td style='padding:18px 30px 24px'>" + ctaButton(href, "CertMonitor'de Görüntüle", C_INFO)
                + "<div style='border-top:1px solid " + LINE + ";margin-top:18px;padding-top:12px;font-size:11px;color:#9AA3AF'>Bu e-posta CertMonitor tarafından otomatik gönderilmiştir.</div></td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    public String buildResolvedText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return "[CertMonitor] ÇÖZÜLDÜ · " + nz(domain) + "\nAlarm otomatik olarak kapandı."
                + (resolvedAt != null ? "\nÇözülme: " + formatHuman(resolvedAt) : "")
                + (resolvedBy != null && !resolvedBy.isBlank() ? "\nÇözen: " + resolvedBy : "");
    }

    // ── Detay satırları (tipe göre) ──────────────────────────────────────────
    private List<String[]> detailRows(AlertMail m) {
        List<String[]> out = new ArrayList<>();
        Map<String, Object> c = m.ctx();
        out.add(new String[]{"Alan Adı", "<strong>" + esc(m.domain()) + "</strong>"});
        if (isDomain(m.alertType())) {
            addIf(out, "Bitiş Tarihi", strCtx(c, "expiry_date") != null ? esc(formatHuman(strCtx(c, "expiry_date"))) : null);
            addIf(out, "Registrar", esc(strCtx(c, "registrar")));
            addIf(out, "Kaynak", esc(strCtx(c, "source")));
            String eppRaw = strCtx(c, "status_codes");
            if (eppRaw != null && !eppRaw.isBlank()) out.add(new String[]{"EPP Durum Kodları", pills(eppRaw)});
            addIf(out, "Sebep", esc(strCtx(c, "last_error")));
        } else if (tabFor(m.alertType()).equals("dashboard")) {   // sertifika
            addIf(out, "Bitiş Tarihi", strCtx(c, "not_after") != null ? esc(formatHuman(strCtx(c, "not_after"))) : null);
            addIf(out, "Veren Kurum (CA)", esc(firstNonNull(strCtx(c, "issuer_cn"), strCtx(c, "issuer"))));
            addIf(out, "Kalan Gün", m.daysRemaining() != null ? String.valueOf(m.daysRemaining()) : null);
        } else {   // uptime/port/dns/keyword/ping/network
            addIf(out, "Detay", esc(firstNonNull(strCtx(c, "detail"), strCtx(c, "port"), strCtx(c, "record_type"))));
            addIf(out, "Hata", esc(firstNonNull(strCtx(c, "error"), strCtx(c, "last_error"))));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? esc(formatHuman(strCtx(c, "checked_at"))) : null);
        }
        addIf(out, "Sorumlu Takım", esc(m.teamName()));
        return out;
    }

    private String shortSummary(AlertMail m) {
        if (m.message() != null && !m.message().isBlank()) {
            String s = m.message().replaceAll("\\s+", " ").trim();
            return s.length() > 180 ? s.substring(0, 177) + "…" : s;
        }
        if (isDomain(m.alertType()) && m.daysRemaining() != null)
            return m.domain() + " alan adının kaydı " + m.daysRemaining() + " gün içinde doluyor.";
        return "CertMonitor bir izleme olayı tespit etti.";
    }

    private String actionParagraph(AlertMail m) {
        if (isDomain(m.alertType())) return "Alan adını registrar üzerinden yenileyin. Yenilenince alarm otomatik kapanır.";
        if (tabFor(m.alertType()).equals("dashboard")) return "Sertifikayı yenileyip ilgili sistemlere dağıtın; yenilenen sertifika tespit edilince alarm kapanır.";
        return "Hedefin erişilebilirliğini kontrol edin. Sorun giderilince alarm otomatik kapanır.";
    }

    private static String subsystemLabel(String t) {
        if (isDomain(t)) return "Alan Adı İzleme";
        if ("ACCESSIBILITY".equals(t)) return "Durum İzleme";
        if ("PORT_DOWN".equals(t)) return "Port İzleme";
        if (t != null && t.startsWith("DNS_")) return "DNS İzleme";
        if ("KEYWORD".equals(t)) return "Keyword İzleme";
        if ("PING_DOWN".equals(t)) return "Ping İzleme";
        return "Sertifika İzleme";
    }

    // ── Küçük render yardımcıları ────────────────────────────────────────────
    private static String row(String label, String valueHtml) {
        return "<tr>"
                + "<td width='35%' valign='top' style='padding:9px 12px 9px 0;border-bottom:1px solid " + LINE + ";font-size:11px;font-weight:600;letter-spacing:.05em;text-transform:uppercase;color:" + MUTED + "'>" + esc(label) + "</td>"
                + "<td valign='top' style='padding:9px 0;border-bottom:1px solid " + LINE + ";font-size:14px;color:" + INK + ";word-break:break-word'>" + valueHtml + "</td>"
                + "</tr>";
    }

    private static String pills(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String p : csv.split(",")) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            sb.append("<span style='display:inline-block;background:" + PILL_BG + ";color:" + PILL_INK + ";border-radius:999px;padding:2px 9px;font-size:11px;margin:0 5px 4px 0'>").append(esc(v)).append("</span>");
        }
        return sb.toString();
    }

    /** VML/mso fallback'li bulletproof CTA butonu. */
    private static String ctaButton(String href, String label, String color) {
        String h = esc(href);
        return "<!--[if mso]><v:roundrect xmlns:v='urn:schemas-microsoft-com:vml' xmlns:w='urn:schemas-microsoft-com:office:word' href='" + h + "' style='height:40px;v-text-anchor:middle;width:220px' arcsize='12%' strokecolor='" + color + "' fillcolor='" + color + "'>"
                + "<w:anchorlock/><center style='color:#ffffff;font-family:Segoe UI,Arial,sans-serif;font-size:14px;font-weight:600'>" + esc(label) + "</center></v:roundrect><![endif]-->"
                + "<!--[if !mso]><!-- --><a href='" + h + "' style='display:inline-block;background:" + color + ";color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;padding:11px 22px;border-radius:6px'>" + esc(label) + "</a><!--<![endif]-->";
    }

    // ── Değer/format yardımcıları ────────────────────────────────────────────
    private static void addIf(List<String[]> out, String label, String value) {
        if (value != null && !value.isBlank() && !"—".equals(value)) out.add(new String[]{label, value});
    }
    private static String strCtx(Map<String, Object> c, String k) {
        if (c == null) return null;
        Object v = c.get(k);
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }
    private static String firstNonNull(String... vs) { for (String v : vs) if (v != null && !v.isBlank()) return v; return null; }
    private static String nz(String s) { return s == null ? "" : s; }

    /** ISO (UTC) → "6 Ağustos 2026 15:37 (GMT+3)" (Europe/Istanbul). Ayrıştırılamazsa girdinin kısası. */
    static String formatHuman(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            String s = iso.trim();
            OffsetDateTime odt;
            if (s.length() <= 10) return s;   // date-only
            odt = OffsetDateTime.parse(s.endsWith("Z") || s.contains("+") || s.matches(".*T.*[+-]\\d\\d:\\d\\d") ? s : s + "Z");
            return odt.atZoneSameInstant(IST).format(HUMAN) + " (GMT+3)";
        } catch (Exception e) {
            return iso.length() > 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }
    private static String urlenc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
