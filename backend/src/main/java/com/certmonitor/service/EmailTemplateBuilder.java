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
 * 640px ortalı kart, koyu-lacivert üst bant, kalan güne göre DİNAMİK aciliyet rengi (şerit+rozet+hero),
 * 72px gün sayacı + Outlook-güvenli progress bar (90 gün penceresi), mini zaman çizelgesi,
 * Türkçe açıklamalı EPP pill'leri, numaralı aksiyon planı, VML bulletproof CTA, footer.
 * HTML + plain-text pariteli (Row.text — stripHtml bitişikliği yaşanmaz).
 */
@Component
@RequiredArgsConstructor
public class EmailTemplateBuilder {

    private final AppSettingsService appSettings;

    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    // Severity renkleri (fallback) ve Türkçe etiketleri. Sistemdeki alarm seviyeleri: CRITICAL/HIGH/WARNING/MEDIUM/INFO.
    private static final String C_CRITICAL = "#C0392B", C_HIGH = "#D68910", C_MEDIUM = "#2874A6", C_INFO = "#1E8449";
    // Aciliyet skalası (kalan güne göre): yeşil → amber → turuncu → kırmızı → koyu kırmızı (ACİL).
    private static final String U_GREEN = "#1E8449", U_AMBER = "#D68910", U_ORANGE = "#CA6F1E",
            U_RED = "#C0392B", U_DARKRED = "#7B241C";
    private static final String NAVY = "#0F1B2D", INK = "#1F2937", MUTED = "#6B7280", LINE = "#E5E8EC",
            SOFT = "#F7F8FA", PILL_BG = "#EEF1F4", PILL_INK = "#475569", BAR_EMPTY = "#E5E8EC";

    /** Progress bar penceresi — "bitişe kalan gün / bu pencere" oranı çizilir. */
    private static final int PROGRESS_WINDOW_DAYS = 90;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", new Locale("tr", "TR"));
    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("d MMM", new Locale("tr", "TR"));

    private static final String LIGHT_SCHEME_META =
            "<meta name='color-scheme' content='light only'><meta name='supported-color-schemes' content='light only'>";

    /** Bilinen EPP durum kodları → kısa Türkçe açıklama (anahtar: lowercase + boşluksuz normalize). */
    static final Map<String, String> EPP_TR = Map.ofEntries(
            Map.entry("clienttransferprohibited", "Transfer kilidi aktif (registrar)"),
            Map.entry("clientdeleteprohibited",   "Silme kilidi aktif (registrar)"),
            Map.entry("clientupdateprohibited",   "Güncelleme kilidi aktif (registrar)"),
            Map.entry("clientrenewprohibited",    "Yenileme kilidi aktif (registrar)"),
            Map.entry("clienthold",               "Yayın durdurulmuş — DNS devre dışı (registrar hold)"),
            Map.entry("servertransferprohibited", "Transfer kilidi aktif (registry)"),
            Map.entry("serverdeleteprohibited",   "Silme kilidi aktif (registry)"),
            Map.entry("serverupdateprohibited",   "Güncelleme kilidi aktif (registry)"),
            Map.entry("serverrenewprohibited",    "Yenileme kilidi aktif (registry)"),
            Map.entry("serverhold",               "Yayın durdurulmuş — DNS devre dışı (registry hold)"),
            Map.entry("ok",                       "Aktif — kısıt yok"),
            Map.entry("active",                   "Aktif"),
            Map.entry("inactive",                 "Pasif — delegasyon yok"),
            Map.entry("redemptionperiod",         "KURTARMA DÖNEMİ — alan adı düşmek üzere, acil yenileyin"),
            Map.entry("pendingdelete",            "SİLİNME BEKLİYOR — kurtarma penceresi kapanıyor"),
            Map.entry("autorenewperiod",          "Otomatik yenileme dönemi"),
            Map.entry("addperiod",                "Yeni kayıt dönemi"),
            Map.entry("transferperiod",           "Transfer sonrası dönem"),
            Map.entry("renewperiod",              "Yenileme sonrası dönem"),
            Map.entry("pendingtransfer",          "Transfer işlemi sürüyor"),
            Map.entry("pendingrenew",             "Yenileme işlemi sürüyor"),
            Map.entry("pendingupdate",            "Güncelleme işlemi sürüyor"),
            Map.entry("pendingcreate",            "Oluşturma işlemi sürüyor"),
            Map.entry("pendingrestore",           "Geri yükleme işlemi sürüyor"));

    /** Alarm e-postası girdisi (içerik verisi). */
    public record AlertMail(String alertType, String level, String domain, String message,
                            Integer daysRemaining, Map<String, Object> ctx, String teamName) {}

    /** Detay satırı — HTML ve plain-text değerleri AYRI taşınır (stripHtml bitişikliği önlenir). */
    private record Row(String label, String html, String text) {
        static Row of(String label, String plain) { return new Row(label, esc(plain), plain); }
    }

    // ── Severity / aciliyet ────────────────────────────────────────────────────
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

    /** Kalan güne göre dinamik aciliyet rengi; gün yoksa severity fallback. */
    static String urgencyColor(Integer days, String level) {
        if (days == null) return severityColor(level);
        if (days <= 3)  return U_DARKRED;
        if (days <= 7)  return U_RED;
        if (days <= 14) return U_ORANGE;
        if (days <= 30) return U_AMBER;
        return U_GREEN;
    }

    private static boolean isDomain(String t) {
        return t != null && (t.startsWith("DOMAINMON_") || "DOMAIN_EXPIRY".equals(t));
    }
    private static boolean isPage(String t) { return "PAGE_DOWN".equals(t) || "PAGE_INTEGRITY".equals(t); }
    private static boolean isScripted(String t) { return "SCRIPTED_FAIL".equals(t); }
    private static boolean isCert(String t) { return tabFor(t).equals("dashboard"); }

    /** Sayfa-bütünlüğü durum kodu → Türkçe etiket. */
    private static String pageStatusTr(String s) {
        if (s == null) return null;
        return switch (s) { case "OK" -> "Sağlıklı"; case "DEGRADED" -> "Bozulmuş"; case "DOWN" -> "Erişilemez"; default -> s; };
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
        if (isPage(t)) return "page";
        if (isScripted(t)) return "scripted";
        return "dashboard";   // sertifika
    }

    /** Gün sayacı yoksa hero'da gösterilen tip etiketi. */
    private static String heroLabel(String t) {
        if (t == null) return "İZLEME UYARISI";
        return switch (t) {
            case "DOMAINMON_EXPIRY", "DOMAIN_EXPIRY" -> "ALAN ADI SÜRE BİTİŞİ";
            case "DOMAINMON_STATUS"  -> "ALAN ADI DURUM UYARISI";
            case "DOMAINMON_CHANGED" -> "ALAN ADI DEĞİŞİKLİK UYARISI";
            case "DOMAINMON_UNKNOWN" -> "ALAN ADI VERİ UYARISI";
            case "REVOKED"      -> "SERTİFİKA İPTAL UYARISI";
            case "MISMATCH"     -> "SERTİFİKA DAĞITIM UYARISI";
            case "CHAIN_BROKEN" -> "SERTİFİKA ZİNCİR UYARISI";
            default -> "İZLEME UYARISI";
        };
    }

    // ── HTML ─────────────────────────────────────────────────────────────────
    public String buildHtml(AlertMail m) {
        Integer days = m.daysRemaining();
        String color = urgencyColor(days, m.level());
        String badge = (days != null && days <= 3 ? "ACİL · " : "") + severityLabel(m.level());
        String domain = esc(m.domain());
        String expiryIso = firstNonNull(strCtx(m.ctx(), "expiry_date"), strCtx(m.ctx(), "not_after"));

        StringBuilder rows = new StringBuilder();
        for (Row r : detailRows(m)) rows.append(row(r.label(), r.html()));

        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String href = cta + "/?tab=" + tabFor(m.alertType())
                + (m.domain() != null ? "&domain=" + urlenc(m.domain()) : "");

        String checkedAt = strCtx(m.ctx(), "checked_at");

        StringBuilder sb = new StringBuilder(8192);
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
          // Aciliyet şeridi (üst çizgi)
          .append("<tr><td bgcolor='").append(color).append("' style='background-color:").append(color).append(";font-size:0;line-height:0;height:4px'>&nbsp;</td></tr>")
          // Gövde
          .append("<tr><td style='padding:26px 30px 8px'>")
          // Rozet
          .append("<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr>")
          .append("<td bgcolor='").append(color).append("' style='background-color:").append(color).append(";border-radius:4px;padding:4px 10px;font-size:11px;font-weight:700;letter-spacing:.08em;color:#FFFFFF'>").append(esc(badge)).append("</td>")
          .append("</tr></table>");
        // Hero — dev gün sayacı ya da tip etiketi
        if (days != null) {
            sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 2px'><tr>")
              .append("<td style='font-size:72px;line-height:1;font-weight:300;color:").append(color).append("'>").append(days).append("</td>")
              .append("<td valign='bottom' style='padding:0 0 8px 12px;font-size:13px;font-weight:700;letter-spacing:.14em;color:").append(MUTED).append("'>GÜN<br>KALDI</td>")
              .append("</tr></table>")
              .append(progressBar(days, color, expiryIso));
        } else {
            sb.append("<div style='margin:18px 0 2px;font-size:24px;font-weight:300;letter-spacing:.06em;color:").append(color).append("'>")
              .append(esc(heroLabel(m.alertType()))).append("</div>");
        }
        sb.append("<div style='font-size:22px;font-weight:700;color:").append(INK).append(";margin:10px 0 4px'>").append(domain).append("</div>")
          .append("<div style='font-size:14px;line-height:1.55;color:").append(MUTED).append(";margin:0 0 4px'>").append(esc(shortSummary(m))).append("</div>")
          .append("</td></tr>");
        // Mini zaman çizelgesi
        String timeline = timelineHtml(m, color, expiryIso);
        if (!timeline.isEmpty()) sb.append("<tr><td style='padding:6px 30px 0'>").append(timeline).append("</td></tr>");
        // Detay tablosu
        sb.append("<tr><td style='padding:8px 30px 4px'>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'>")
          .append(rows)
          .append("</table></td></tr>")
          // Önerilen Aksiyon — numaralı adımlar
          .append("<tr><td style='padding:18px 30px 4px'>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='").append(SOFT).append("' style='background-color:").append(SOFT).append(";border:1px solid ").append(LINE).append(";border-radius:8px'>")
          .append("<tr><td style='padding:16px 18px'>")
          .append("<div style='font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:").append(MUTED).append(";margin:0 0 8px'>Önerilen Aksiyon</div>");
        String urgent = urgentLine(m, expiryIso);
        if (urgent != null) {
            sb.append("<div style='font-size:13px;font-weight:700;color:").append(U_DARKRED).append(";margin:0 0 10px'>").append(esc(urgent)).append("</div>");
        }
        List<String> steps = actionSteps(m);
        if (steps.size() == 1) {
            sb.append("<div style='font-size:14px;line-height:1.55;color:").append(INK).append(";margin:0 0 14px'>").append(esc(steps.get(0))).append("</div>");
        } else {
            sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 14px'>");
            for (int i = 0; i < steps.size(); i++) {
                sb.append("<tr><td valign='top' style='padding:3px 8px 3px 0;font-size:14px;font-weight:700;color:").append(color).append("'>").append(i + 1).append(".</td>")
                  .append("<td style='padding:3px 0;font-size:14px;line-height:1.55;color:").append(INK).append("'>").append(esc(steps.get(i))).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append(ctaButton(href, "CertMonitor'de Görüntüle", color))
          .append("</td></tr></table></td></tr>")
          // "Neden bu e-postayı aldınız?" — alıcı şeffaflığı (StatusCake "Why am I seeing this email" esini)
          .append(whyReceivingBlock(m.teamName()))
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

    /** Plain-text multipart alternatifi — sayaç/timeline/adımlar HTML ile pariteli. */
    public String buildText(AlertMail m) {
        Integer days = m.daysRemaining();
        String head = days != null
                ? (days <= 3 ? "ACİL " : "") + days + " GÜN KALDI"
                : severityLabel(m.level());
        String expiryIso = firstNonNull(strCtx(m.ctx(), "expiry_date"), strCtx(m.ctx(), "not_after"));

        StringBuilder sb = new StringBuilder();
        sb.append("[CertMonitor] ").append(head).append(" · ").append(nz(m.domain())).append('\n');
        sb.append(shortSummary(m)).append("\n\n");
        if (days != null) {
            sb.append("Bitişe ").append(days).append(" gün / ").append(PROGRESS_WINDOW_DAYS).append(" günlük pencere");
            if (expiryIso != null) sb.append(" · Son tarih: ").append(formatHuman(expiryIso));
            sb.append('\n');
        }
        String tl = timelineText(m, expiryIso);
        if (tl != null) sb.append(tl).append('\n');
        sb.append('\n');
        for (Row r : detailRows(m)) sb.append(r.label()).append(": ").append(r.text()).append('\n');
        sb.append('\n').append("Önerilen Aksiyon:").append('\n');
        String urgent = urgentLine(m, expiryIso);
        if (urgent != null) sb.append(urgent).append('\n');
        List<String> steps = actionSteps(m);
        if (steps.size() == 1) {
            sb.append(steps.get(0)).append('\n');
        } else {
            for (int i = 0; i < steps.size(); i++) sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        sb.append(cta).append("/?tab=").append(tabFor(m.alertType()))
          .append(m.domain() != null ? "&domain=" + urlenc(m.domain()) : "").append('\n');
        sb.append("\n— CertMonitor ").append(subsystemLabel(m.alertType()));
        return sb.toString();
    }

    // ── Çözüldü (resolved) — executive, INFO/yeşil + zengin bağlam ────────────
    public String buildResolvedHtml(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return buildResolvedHtml(domain, alertType, resolvedBy, resolvedAt, null, null);
    }

    /**
     * Çözüm e-postası — alan adı/sertifika bağlamıyla zenginleştirilir: yenilenen bitiş tarihi,
     * kalan süre, registrar/CA, veri kaynağı, EPP durum kodları, ad sunucuları ve alarm süresi.
     * {@code ctx} {@link EscalationService#reconstructDomainContext} (domain) veya cert snapshot'ından gelir;
     * boş geçilirse eski sade davranış korunur.
     */
    public String buildResolvedHtml(String domain, String alertType, String resolvedBy, String resolvedAt,
                                    String createdAt, Map<String, Object> ctx) {
        return buildResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt, ctx, null);
    }

    /** teamNames verilirse "Neden bu e-postayı aldınız?" alıcı-şeffaflık bloğu eklenir (recovery şeffaflığı). */
    public String buildResolvedHtml(String domain, String alertType, String resolvedBy, String resolvedAt,
                                    String createdAt, Map<String, Object> ctx, String teamNames) {
        String d = esc(domain);
        String cta = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String href = cta + "/?tab=" + tabFor(alertType) + (domain != null ? "&domain=" + urlenc(domain) : "");

        boolean domainType = isDomain(alertType);
        String expiryIso = ctx == null ? null
                : firstNonNull(strCtx(ctx, "expiry_date"), strCtx(ctx, "not_after"));
        String daysRem = ctx == null ? null
                : firstNonNull(strCtx(ctx, "days_remaining"), strCtx(ctx, "days"));

        // Yeni bitiş tarihini öne çıkaran vurgu şeridi (varsa) — "yeni expire ne oldu?" sorusuna doğrudan yanıt.
        String hero = "";
        if (expiryIso != null) {
            String expiryHuman = formatHuman(expiryIso);
            String daysChip = (daysRem != null)
                    ? "<span style='display:inline-block;margin-left:8px;background:" + PILL_BG + ";color:" + PILL_INK + ";border-radius:999px;padding:2px 10px;font-size:12px;font-weight:700'>" + esc(daysRem) + " gün kaldı</span>"
                    : "";
            hero = "<tr><td style='padding:4px 30px 4px'>"
                    + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden'><tr>"
                    + "<td width='5' bgcolor='" + C_INFO + "' style='background-color:" + C_INFO + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
                    + "<td bgcolor='#F0FBF6' style='background-color:#F0FBF6;padding:14px 18px'>"
                    + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + C_INFO + ";margin-bottom:4px'>"
                    + (domainType ? "🔄 YENİ BİTİŞ TARİHİ" : "🔄 GÜNCEL BİTİŞ TARİHİ") + "</div>"
                    + "<div style='font-size:18px;font-weight:800;color:" + INK + "'>" + esc(expiryHuman) + daysChip + "</div>"
                    + "</td></tr></table></td></tr>";
        }

        StringBuilder rows = new StringBuilder();
        rows.append(row("Alan Adı", "<strong>" + d + "</strong>"));
        if (ctx != null) {
            if (expiryIso != null)
                rows.append(row(domainType ? "Yeni Bitiş Tarihi" : "Bitiş Tarihi", esc(formatHuman(expiryIso))));
            if (daysRem != null) rows.append(row("Kalan Süre", esc(daysRem) + " gün"));
            if (domainType) {
                String reg = strCtx(ctx, "registrar");
                if (reg != null) rows.append(row("Kayıt Kuruluşu", esc(reg)));
                String src = strCtx(ctx, "source");
                if (src != null) rows.append(row("Veri Kaynağı", esc(src)));
                String epp = strCtx(ctx, "status_codes");
                if (epp != null && !epp.isBlank()) rows.append(row("EPP Durum Kodları", eppPills(epp)));
                String ns = strCtx(ctx, "nameservers");
                if (ns != null) rows.append(row("Ad Sunucuları", esc(ns)));
            } else {
                String ca = firstNonNull(strCtx(ctx, "issuer_cn"), strCtx(ctx, "issuer"));
                if (ca != null) rows.append(row("Veren Kurum (CA)", esc(ca)));
            }
        }
        // Sayfa Bütünlüğü / Sayfa Yüklenemiyor çözümü — "sorun neydi + ne çözüldü" (2026-08-04).
        // Alarm-anı anahtarları snapshotContext'ten, resolved_* anahtarları çözüm anındaki son PageCheck'ten gelir;
        // eski (bu sürümden önce açılmış) alarmların snapshot'ında anahtarlar yok → satırlar zarifçe atlanır.
        // NOT: SCRIPTED_FAIL aynı şablona düşer ve aynı boşluğa sahiptir — follow-up (bkz. isScripted).
        if (ctx != null && isPage(alertType)) {
            rows.append(row("Çözülen Alarm",
                    "PAGE_DOWN".equals(alertType) ? "Sayfa Yüklenemiyor" : "Sayfa Bütünlüğü"));
            String detail = strCtx(ctx, "detail");
            if (detail != null && !detail.isBlank()) {
                rows.append(row("Sorun (alarm anı)", esc(detail)));
            } else {
                String b = strCtx(ctx, "broken_resources");
                if (b != null) rows.append(row("Kırık Kaynak (alarm anı)", esc(b)));
                String tmo = strCtx(ctx, "timeout_count");
                if (tmo != null) rows.append(row("Zaman Aşımı (alarm anı)", esc(tmo)));
                String mx = strCtx(ctx, "mixed_content_count");
                if (mx != null) rows.append(row("Mixed Content (alarm anı)", esc(mx)));
            }
            if ("PAGE_DOWN".equals(alertType)) {
                String err = strCtx(ctx, "last_error");
                if (err != null) rows.append(row("Son Hata", esc(err.length() > 120 ? err.substring(0, 120) + "…" : err)));
                String hs = strCtx(ctx, "http_status");
                if (hs != null) rows.append(row("HTTP Durumu", esc(hs)));
            }
            String tsv = strCtx(ctx, "problem_rows");
            if (tsv != null && !tsv.isBlank())
                rows.append(row("Giderilen Sorunlu Kaynaklar", pageIssuesHtml(tsv, intCtx(ctx, "problem_total"))));
            String cur = strCtx(ctx, "resolved_page_status");
            if (cur != null) {
                String tot  = strCtx(ctx, "resolved_total_resources");
                String when = strCtx(ctx, "resolved_checked_at");
                String curHtml = "OK".equals(cur)
                        ? "<strong style='color:" + C_INFO + "'>Sağlıklı</strong>"
                          + (tot != null ? " — " + esc(tot) + " kaynağın tümü erişilebilir" : "")
                        : esc(pageStatusTr(cur));
                if (when != null) curHtml += " <span style='color:" + MUTED + "'>(" + esc(formatHuman(when)) + ")</span>";
                rows.append(row("Güncel Durum", curHtml));
            }
        }
        String durHuman = durationHuman(createdAt, resolvedAt);
        if (durHuman != null) rows.append(row("Alarm Süresi", esc(durHuman)));
        if (resolvedAt != null) rows.append(row("Çözülme", esc(formatHuman(resolvedAt))));
        rows.append(row("Çözen", esc(resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)")));

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
                + "<div style='font-size:14px;color:" + MUTED + "'>" + esc(resolvedHeadline(alertType, expiryIso != null)) + "</div></td></tr>"
                + hero
                + "<tr><td style='padding:8px 30px 4px'><table role='presentation' width='100%' style='border-collapse:collapse'>" + rows + "</table></td></tr>"
                + whyReceivingBlock(teamNames)
                + "<tr><td style='padding:18px 30px 24px'>" + ctaButton(href, "CertMonitor'de Görüntüle", C_INFO)
                + "<div style='border-top:1px solid " + LINE + ";margin-top:18px;padding-top:12px;font-size:11px;color:#9AA3AF'>Bu e-posta CertMonitor tarafından otomatik gönderilmiştir.</div></td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    public String buildResolvedText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return buildResolvedText(domain, alertType, resolvedBy, resolvedAt, null, null);
    }

    public String buildResolvedText(String domain, String alertType, String resolvedBy, String resolvedAt,
                                    String createdAt, Map<String, Object> ctx) {
        boolean domainType = isDomain(alertType);
        String expiryIso = ctx == null ? null : firstNonNull(strCtx(ctx, "expiry_date"), strCtx(ctx, "not_after"));
        String daysRem = ctx == null ? null : firstNonNull(strCtx(ctx, "days_remaining"), strCtx(ctx, "days"));
        StringBuilder sb = new StringBuilder("[CertMonitor] ÇÖZÜLDÜ · " + nz(domain) + "\n"
                + resolvedHeadline(alertType, expiryIso != null));
        if (expiryIso != null)
            sb.append("\n").append(domainType ? "Yeni Bitiş Tarihi: " : "Bitiş Tarihi: ").append(formatHuman(expiryIso));
        if (daysRem != null) sb.append("\nKalan Süre: ").append(daysRem).append(" gün");
        if (ctx != null && domainType) {
            String reg = strCtx(ctx, "registrar"); if (reg != null) sb.append("\nKayıt Kuruluşu: ").append(reg);
            String epp = strCtx(ctx, "status_codes"); if (epp != null && !epp.isBlank()) sb.append("\nEPP Durum Kodları: ").append(eppText(epp));
        }
        if (ctx != null && isPage(alertType)) {   // sayfa çözümü — HTML ile aynı bilgi (2026-08-04)
            sb.append("\nÇözülen Alarm: ").append("PAGE_DOWN".equals(alertType) ? "Sayfa Yüklenemiyor" : "Sayfa Bütünlüğü");
            String detail = strCtx(ctx, "detail");
            if (detail != null && !detail.isBlank()) sb.append("\nSorun (alarm anı): ").append(detail);
            String tsv = strCtx(ctx, "problem_rows");
            if (tsv != null && !tsv.isBlank())
                sb.append("\nGiderilen Sorunlu Kaynaklar:\n").append(pageIssuesText(tsv, intCtx(ctx, "problem_total")));
            String cur = strCtx(ctx, "resolved_page_status");
            if (cur != null) {
                String tot = strCtx(ctx, "resolved_total_resources");
                sb.append("\nGüncel Durum: ").append("OK".equals(cur)
                        ? "Sağlıklı" + (tot != null ? " — " + tot + " kaynağın tümü erişilebilir" : "")
                        : pageStatusTr(cur));
            }
        }
        String durHuman = durationHuman(createdAt, resolvedAt);
        if (durHuman != null) sb.append("\nAlarm Süresi: ").append(durHuman);
        if (resolvedAt != null) sb.append("\nÇözülme: ").append(formatHuman(resolvedAt));
        sb.append("\nÇözen: ").append(resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)");
        return sb.toString();
    }

    /** Çözüm alt başlığı — yenileme bağlamı varsa daha açıklayıcı. */
    private static String resolvedHeadline(String alertType, boolean hasExpiry) {
        if (isDomain(alertType))
            return hasExpiry ? "Alan adı yenilendi, alarm otomatik olarak kapandı." : "Alarm otomatik olarak kapandı.";
        if (isCert(alertType))
            return hasExpiry ? "Sertifika yenilendi, alarm otomatik olarak kapandı." : "Alarm otomatik olarak kapandı.";
        if ("PAGE_INTEGRITY".equals(alertType))
            return "Sayfadaki bütünlük sorunu giderildi; kaynaklar yeniden sağlıklı — alarm otomatik olarak kapandı.";
        if ("PAGE_DOWN".equals(alertType))
            return "Sayfa yeniden yükleniyor — alarm otomatik olarak kapandı.";
        return "Alarm otomatik olarak kapandı.";
    }

    /** createdAt→resolvedAt insan-okur süre ("2 gün 4 saat" / "35 dakika"); ayrıştırılamazsa null. */
    static String durationHuman(String createdAtIso, String resolvedAtIso) {
        if (createdAtIso == null || resolvedAtIso == null) return null;
        try {
            java.time.Instant a = parseInstant(createdAtIso), b = parseInstant(resolvedAtIso);
            if (a == null || b == null) return null;
            long sec = java.time.Duration.between(a, b).getSeconds();
            if (sec < 0) return null;
            long dys = sec / 86400, hrs = (sec % 86400) / 3600, mins = (sec % 3600) / 60;
            if (dys > 0) return dys + " gün" + (hrs > 0 ? " " + hrs + " saat" : "");
            if (hrs > 0) return hrs + " saat" + (mins > 0 ? " " + mins + " dakika" : "");
            return Math.max(1, mins) + " dakika";
        } catch (Exception e) { return null; }
    }

    private static java.time.Instant parseInstant(String iso) {
        try { return java.time.Instant.parse(iso); } catch (Exception ignore) {}
        try { return java.time.LocalDateTime.parse(iso.trim().replace(' ', 'T'))
                .atZone(java.time.ZoneOffset.UTC).toInstant(); } catch (Exception ignore) {}
        return null;
    }

    // ── Progress bar (Outlook-güvenli: iki td, width% + bgcolor) ─────────────
    private static String progressBar(int days, String color, String expiryIso) {
        int pct = Math.max(2, Math.min(100, Math.round(days * 100f / PROGRESS_WINDOW_DAYS)));
        int rest = 100 - pct;
        StringBuilder sb = new StringBuilder();
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:12px 0 0;border-collapse:collapse'><tr>")
          .append("<td width='").append(pct).append("%' bgcolor='").append(color).append("' style='background-color:").append(color).append(";font-size:0;line-height:0;height:8px;border-radius:4px 0 0 4px'>&nbsp;</td>");
        if (rest > 0) {
            sb.append("<td width='").append(rest).append("%' bgcolor='").append(BAR_EMPTY).append("' style='background-color:").append(BAR_EMPTY).append(";font-size:0;line-height:0;height:8px;border-radius:0 4px 4px 0'>&nbsp;</td>");
        }
        sb.append("</tr></table>")
          .append("<div style='margin:6px 0 0;font-size:12px;color:").append(MUTED).append("'>")
          .append("Bitişe ").append(days).append(" gün");
        if (expiryIso != null) sb.append(" · Son tarih: ").append(esc(formatHuman(expiryIso)));
        sb.append("</div>");
        return sb.toString();
    }

    // ── Mini zaman çizelgesi ─────────────────────────────────────────────────
    /** Nokta: etiket + tarih (+ vurgu). */
    private record TlPoint(String label, String date, boolean emphasized) {}

    private List<TlPoint> timelinePoints(AlertMail m, String expiryIso) {
        List<TlPoint> pts = new ArrayList<>();
        String first = strCtx(m.ctx(), "first_alert_at");
        if (first != null) pts.add(new TlPoint("İlk Alarm", formatShort(first), false));
        String realert = strCtx(m.ctx(), "realert_count");
        if (realert != null) pts.add(new TlPoint("Bu Hatırlatma (#" + realert + ")", formatShort(OffsetDateTime.now(IST).toString()), false));
        String checked = strCtx(m.ctx(), "checked_at");
        if (checked != null) pts.add(new TlPoint("Son Kontrol", formatShort(checked), false));
        if (expiryIso != null) pts.add(new TlPoint("BİTİŞ", formatShort(expiryIso), true));
        return pts.size() >= 2 ? pts : List.of();
    }

    private String timelineHtml(AlertMail m, String color, String expiryIso) {
        List<TlPoint> pts = timelinePoints(m, expiryIso);
        if (pts.isEmpty()) return "";
        int w = 100 / pts.size();
        StringBuilder sb = new StringBuilder();
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:4px 0 8px'><tr>");
        for (TlPoint p : pts) {
            String dotColor = p.emphasized() ? color : "#B7BFC9";
            String labelColor = p.emphasized() ? color : MUTED;
            String weight = p.emphasized() ? "700" : "600";
            sb.append("<td width='").append(w).append("%' align='center' valign='top' style='padding:6px 4px;border-top:2px solid ").append(LINE).append("'>")
              .append("<span style='color:").append(dotColor).append(";font-size:14px;line-height:1'>&#9679;</span>")
              .append("<div style='font-size:10px;font-weight:").append(weight).append(";letter-spacing:.05em;text-transform:uppercase;color:").append(labelColor).append(";margin:4px 0 1px'>").append(esc(p.label())).append("</div>")
              .append("<div style='font-size:12px;color:").append(p.emphasized() ? color : INK).append(";font-weight:").append(weight).append("'>").append(esc(p.date())).append("</div>")
              .append("</td>");
        }
        sb.append("</tr></table>");
        return sb.toString();
    }

    private String timelineText(AlertMail m, String expiryIso) {
        List<TlPoint> pts = timelinePoints(m, expiryIso);
        if (pts.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (TlPoint p : pts) parts.add(p.label() + ": " + p.date());
        return String.join(" · ", parts);
    }

    // ── Detay satırları (tipe göre) ──────────────────────────────────────────
    private List<Row> detailRows(AlertMail m) {
        List<Row> out = new ArrayList<>();
        Map<String, Object> c = m.ctx();
        out.add(new Row("Alan Adı", "<strong>" + esc(m.domain()) + "</strong>", nz(m.domain())));
        if (isDomain(m.alertType())) {
            addIf(out, "Bitiş Tarihi", strCtx(c, "expiry_date") != null ? formatHuman(strCtx(c, "expiry_date")) : null);
            addIf(out, "Registrar", strCtx(c, "registrar"));
            addIf(out, "Veri Kaynağı", strCtx(c, "source"));
            String eppRaw = strCtx(c, "status_codes");
            if (eppRaw != null && !eppRaw.isBlank()) out.add(new Row("EPP Durum Kodları", eppPills(eppRaw), eppText(eppRaw)));
            addIf(out, "Nameserver'lar", strCtx(c, "nameservers"));
            addIf(out, "Sebep", strCtx(c, "last_error"));
        } else if (isCert(m.alertType())) {   // sertifika
            addIf(out, "Bitiş Tarihi", strCtx(c, "not_after") != null ? formatHuman(strCtx(c, "not_after")) : null);
            addIf(out, "Veren Kurum (CA)", firstNonNull(strCtx(c, "issuer_cn"), strCtx(c, "issuer")));
            addIf(out, "Sertifika CN", strCtx(c, "subject"));
            String fp = strCtx(c, "fingerprint");
            if (fp != null) out.add(new Row("SHA-256 Parmak İzi",
                    "<span style='font-family:Consolas,Menlo,monospace;font-size:12px'>" + esc(shortFp(fp)) + "</span>", shortFp(fp)));
            addIf(out, "Kalan Gün", m.daysRemaining() != null ? String.valueOf(m.daysRemaining()) : null);
        } else if (isPage(m.alertType())) {   // sayfa bütünlüğü
            addIf(out, "Durum", pageStatusTr(strCtx(c, "page_status")));
            addIf(out, "Mod", pageModeTr(strCtx(c, "page_mode")));
            addIf(out, "Kırık Kaynak", strCtx(c, "broken_resources"));
            addIf(out, "Zaman Aşımı", strCtx(c, "timeout_count"));   // 2026-08-04: kırıktan ayrı sayaç
            addIf(out, "Mixed Content", strCtx(c, "mixed_content_count"));
            addIf(out, "Alarm Kapsamı", alarmScopeText(c));       // bu monitör hangi sorunlarda alarm üretir
            addIf(out, "Doğrulama", confirmationText(c));         // N ardışık kontrolde doğrulandıktan sonra
            String rowsTsv = strCtx(c, "problem_rows");
            if (rowsTsv != null) {
                out.add(new Row("Sorunlu Kaynaklar", pageIssuesHtml(rowsTsv, intCtx(c, "problem_total")),
                        pageIssuesText(rowsTsv, intCtx(c, "problem_total"))));
            } else {
                String probs = strCtx(c, "problem_resources");   // geriye-uyum
                if (probs != null) out.add(new Row("Sorunlu Kaynaklar", esc(probs).replace("\n", "<br>"), probs));
            }
            addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
        } else if (isScripted(m.alertType())) {   // senaryo (k6)
            addIf(out, "Sonuç", strCtx(c, "scripted_status"));
            addIf(out, "Doğrulama", confirmationText(c));
            String failed = strCtx(c, "failed_checks");
            if (failed != null) out.add(new Row("Başarısız Check'ler", "<ul style='margin:0;padding-left:18px'>"
                    + java.util.Arrays.stream(failed.split("\n")).filter(s -> !s.isBlank())
                        .map(s -> "<li>" + esc(s) + "</li>").reduce("", String::concat) + "</ul>", failed));
            String tail = strCtx(c, "output_tail");
            if (tail != null) out.add(new Row("Son Çıktı (maskeli)",
                    "<pre style='margin:0;font-family:Consolas,Menlo,monospace;font-size:12px;white-space:pre-wrap;word-break:break-word;background:" + SOFT + ";padding:10px;border-radius:6px;color:" + INK + "'>"
                            + esc(tail.length() > 2000 ? tail.substring(tail.length() - 2000) : tail) + "</pre>", tail));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
        } else {   // uptime/port/dns/keyword/ping/network
            addIf(out, "Detay", firstNonNull(strCtx(c, "detail"), strCtx(c, "port"), strCtx(c, "record_type")));
            addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
        }
        addIf(out, "Sorumlu Takım", m.teamName());
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

    // ── Aksiyon planı ────────────────────────────────────────────────────────
    /** Tip bazlı numaralı yapılacaklar listesi (tek elemanlıysa düz cümle olarak basılır). */
    private List<String> actionSteps(AlertMail m) {
        if (isDomain(m.alertType())) {
            String reg = strCtx(m.ctx(), "registrar");
            return List.of(
                    "Registrar paneline giriş yapın" + (reg != null ? " (" + reg + ")" : ""),
                    "Alan adını en az 1 yıl yenileyin",
                    "Auto-renew (otomatik yenileme) özelliğini açın",
                    "Yenileme sonrası CertMonitor'ün otomatik doğrulamasını bekleyin — alarm kendiliğinden kapanır");
        }
        if (isPage(m.alertType())) {
            if ("PAGE_DOWN".equals(m.alertType()))
                return List.of("Sayfanın erişilebilirliğini kontrol edin (sunucu/uygulama/ağ). Sayfa yeniden yüklenince alarm otomatik kapanır.");
            return List.of(
                    "Yukarıdaki \"Sorunlu Kaynaklar\" listesindeki kırık link/resim/CSS/JS veya mixed content'i inceleyin",
                    "İlgili içerik/dağıtım ekibiyle kaynağı düzeltin (kaldırılmış varlık, yanlış yol, http→https)",
                    "CertMonitor'ün sonraki kontrolünü bekleyin — sorunlar giderilince alarm kendiliğinden kapanır");
        }
        if (isScripted(m.alertType())) {
            return List.of(
                    "Yukarıdaki \"Başarısız Check'ler\" listesini ve maskeli çıktıyı inceleyin (hangi adım başarısız oldu)",
                    "İlgili servisi/akışı (login, API zinciri, token/claim) kontrol edin; gerekirse izleme servis hesabının kimlik bilgilerini doğrulayın",
                    "CertMonitor'ün sonraki çalıştırmasını bekleyin — sentetik test yeniden geçince alarm kendiliğinden kapanır");
        }
        if (isCert(m.alertType())) {
            return List.of(
                    "CA/PKI ekibinden yeni sertifika talep edin",
                    "Yeni sertifikayı ilgili sunuculara ve sistemlere dağıtın",
                    "CertMonitor'de zincir ve geçerlilik doğrulamasını izleyin — yenilenen sertifika tespit edilince alarm kendiliğinden kapanır");
        }
        return List.of("Hedefin erişilebilirliğini kontrol edin. Sorun giderilince alarm otomatik kapanır.");
    }

    /** ≤7 gün kaldıysa aksiyon listesinin başındaki uyarı satırı; değilse null. */
    private String urgentLine(AlertMail m, String expiryIso) {
        if (m.daysRemaining() == null || m.daysRemaining() > 7) return null;
        return "⚠ Bugün aksiyon alın — son tarih " + (expiryIso != null ? formatHuman(expiryIso) : m.daysRemaining() + " gün sonra");
    }

    private static String subsystemLabel(String t) {
        if (isDomain(t)) return "Alan Adı İzleme";
        if ("ACCESSIBILITY".equals(t)) return "Durum İzleme";
        if ("PORT_DOWN".equals(t)) return "Port İzleme";
        if (t != null && t.startsWith("DNS_")) return "DNS İzleme";
        if ("KEYWORD".equals(t)) return "Keyword İzleme";
        if ("PING_DOWN".equals(t)) return "Ping İzleme";
        if (isPage(t)) return "Sayfa Bütünlüğü İzleme";
        if (isScripted(t)) return "Sentetik İzleme";
        return "Sertifika İzleme";
    }

    /** "Neden bu e-postayı aldınız?" — alıcı şeffaflık bloğu (Outlook-güvenli). Dış link YOK (banka). */
    static String whyReceivingBlock(String teamName) {
        String team = (teamName != null && !teamName.isBlank()) ? esc(teamName) : "ilgili izleme grubuna";
        String teamPhrase = (teamName != null && !teamName.isBlank()) ? "<strong>" + team + "</strong> ekibine" : team;
        return "<tr><td style='padding:6px 30px 0'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + SOFT + "' style='background-color:" + SOFT + ";border:1px solid " + LINE + ";border-radius:8px'>"
                + "<tr><td style='padding:12px 16px'>"
                + "<div style='font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:" + MUTED + ";margin-bottom:5px'>Neden bu e-postayı aldınız?</div>"
                + "<div style='font-size:13px;line-height:1.55;color:" + INK + "'>Bu bildirim " + teamPhrase
                + " tanımlı bir izleme için gönderildi. CertMonitor otomatik bir izleme sistemidir; bildirim tercihleri için sistem yöneticinize başvurun.</div>"
                + "</td></tr></table></td></tr>";
    }

    // ── Küçük render yardımcıları ────────────────────────────────────────────
    private static String row(String label, String valueHtml) {
        return "<tr>"
                + "<td width='35%' valign='top' style='padding:9px 12px 9px 0;border-bottom:1px solid " + LINE + ";font-size:11px;font-weight:600;letter-spacing:.05em;text-transform:uppercase;color:" + MUTED + "'>" + esc(label) + "</td>"
                + "<td valign='top' style='padding:9px 0;border-bottom:1px solid " + LINE + ";font-size:14px;color:" + INK + ";word-break:break-word'>" + valueHtml + "</td>"
                + "</tr>";
    }

    /** EPP kodları — her kod kendi satırında pill + kısa Türkçe açıklama (Outlook-güvenli iç tablo). */
    private static String eppPills(String csv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'>");
        for (String p : csv.split(",")) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            String desc = EPP_TR.get(normEppKey(v));
            sb.append("<tr><td style='padding:2px 0'>")
              .append("<span style='display:inline-block;background:" + PILL_BG + ";color:" + PILL_INK + ";border-radius:999px;padding:2px 9px;font-size:11px;white-space:nowrap'>").append(esc(v)).append("</span>")
              .append("</td><td style='padding:2px 0 2px 8px;font-size:12px;color:" + MUTED + "'>")
              .append(desc != null ? esc(desc) : "")
              .append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /** EPP kodlarının plain-text karşılığı: "kod (açıklama), kod2 (açıklama2)". */
    private static String eppText(String csv) {
        List<String> parts = new ArrayList<>();
        for (String p : csv.split(",")) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            String desc = EPP_TR.get(normEppKey(v));
            parts.add(desc != null ? v + " (" + desc + ")" : v);
        }
        return String.join(", ", parts);
    }

    private static String normEppKey(String code) {
        return code == null ? "" : code.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    /** VML/mso fallback'li bulletproof CTA butonu. */
    private static String ctaButton(String href, String label, String color) {
        String h = esc(href);
        return "<!--[if mso]><v:roundrect xmlns:v='urn:schemas-microsoft-com:vml' xmlns:w='urn:schemas-microsoft-com:office:word' href='" + h + "' style='height:40px;v-text-anchor:middle;width:220px' arcsize='12%' strokecolor='" + color + "' fillcolor='" + color + "'>"
                + "<w:anchorlock/><center style='color:#ffffff;font-family:Segoe UI,Arial,sans-serif;font-size:14px;font-weight:600'>" + esc(label) + "</center></v:roundrect><![endif]-->"
                + "<!--[if !mso]><!-- --><a href='" + h + "' style='display:inline-block;background:" + color + ";color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;padding:11px 22px;border-radius:6px'>" + esc(label) + "</a><!--<![endif]-->";
    }

    // ── Değer/format yardımcıları ────────────────────────────────────────────
    private static void addIf(List<Row> out, String label, String value) {
        if (value != null && !value.isBlank() && !"—".equals(value)) out.add(Row.of(label, value));
    }
    private static String strCtx(Map<String, Object> c, String k) {
        if (c == null) return null;
        Object v = c.get(k);
        if (v == null) return null;
        String s = String.valueOf(v);
        // Literal "null"/"undefined" (ctx serileştirmesinden sızabilir) → yok say; hiçbir meşru değer bu değildir.
        return (s.isBlank() || "null".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) ? null : s;
    }
    private static String firstNonNull(String... vs) { for (String v : vs) if (v != null && !v.isBlank()) return v; return null; }
    private static String nz(String s) { return s == null ? "" : s; }

    // ── Sayfa Bütünlüğü alarm-detay yardımcıları ─────────────────────────────
    private static Integer intCtx(Map<String, Object> c, String k) {
        Object v = c == null ? null : c.get(k);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? null : Integer.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
    private static Long longCtx(Map<String, Object> c, String k) {
        Object v = c == null ? null : c.get(k);
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? null : Long.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
    private static boolean boolCtx(Map<String, Object> c, String k) {
        Object v = c == null ? null : c.get(k);
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static String pageModeTr(String mode) {
        if (mode == null) return null;
        return "SITE_CRAWL".equalsIgnoreCase(mode) ? "Site Tarama" : "Tek Sayfa";
    }

    /** Bu monitörün hangi sorunlarda alarm ürettiğini özetler (3rd-party / mixed / timeout ayarları). */
    private static String alarmScopeText(Map<String, Object> c) {
        if (c == null || (!c.containsKey("alert_third_party") && !c.containsKey("alert_mixed_content")
                && !c.containsKey("alert_timeout"))) return null;
        return "Üçüncü-taraf kırıkları: " + (boolCtx(c, "alert_third_party") ? "Evet" : "Hayır")
             + " · Mixed content: " + (boolCtx(c, "alert_mixed_content") ? "Evet" : "Hayır")
             + " · Zaman aşımı: " + (boolCtx(c, "alert_timeout") ? "İzleniyor" : "İzlenmiyor");
    }

    /** "N ardışık kontrolde doğrulandıktan sonra üretildi (~Xsn arayla)" — tek-seferlik takılma yanlış-pozitif üretmez. */
    private static String confirmationText(Map<String, Object> c) {
        Integer att = intCtx(c, "monitor_confirm_attempts");
        if (att == null || att < 1) return null;
        String base = att + " ardışık kontrolde doğrulandıktan sonra üretildi";
        Long ims = longCtx(c, "monitor_confirm_interval_ms");
        if (ims != null && ims > 0) base += " (~" + Math.round(ims / 1000.0) + " sn arayla)";
        return base;
    }

    private static String pageIssueTypeLabel(String t) {
        return switch (t == null ? "" : t) {
            case "TIMEOUT" -> "Zaman aşımı";
            case "BROKEN" -> "Kırık";
            case "MIXED_CONTENT" -> "Mixed";
            case "BLOCKED" -> "Belirsiz";
            case "SLOW" -> "Yavaş";
            default -> t == null ? "" : t;
        };
    }
    private static String pageIssueTypeColor(String t) {
        return switch (t == null ? "" : t) {
            case "TIMEOUT" -> "#A16207";
            case "MIXED_CONTENT" -> "#B45309";
            case "SLOW" -> "#0369A1";
            case "BLOCKED" -> "#78716C";
            default -> "#B91C1C";   // BROKEN
        };
    }
    private static String truncUrl(String url) {
        if (url == null) return "";
        return url.length() > 100 ? url.substring(0, 99) + "…" : url;
    }

    /** Sorunlu kaynaklar — Outlook-güvenli iç tablo: her satır tür etiketi + kısaltılmış URL (+HTTP), ≤10 satır. */
    private static String pageIssuesHtml(String tsv, Integer total) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'>");
        int shown = 0;
        for (String line : tsv.split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            String type = p.length > 0 ? p[0] : "";
            String url  = p.length > 1 ? p[1] : "";
            String http = p.length > 2 ? p[2] : "";
            sb.append("<tr>")
              .append("<td valign='top' style='padding:5px 10px 5px 0;font-size:11px;font-weight:700;color:")
              .append(pageIssueTypeColor(type)).append(";white-space:nowrap'>").append(esc(pageIssueTypeLabel(type))).append("</td>")
              .append("<td valign='top' style='padding:5px 0;font-size:12px;line-height:1.45;color:").append(INK)
              .append(";font-family:Consolas,Menlo,monospace;word-break:break-all'>").append(esc(truncUrl(url)));
            if (!http.isBlank()) sb.append("<span style='color:").append(MUTED).append("'> · HTTP ").append(esc(http)).append("</span>");
            sb.append("</td></tr>");
            shown++;
        }
        if (total != null && total > shown) {
            sb.append("<tr><td colspan='2' style='padding:7px 0 0;font-size:12px;font-style:italic;color:").append(MUTED)
              .append("'>… ve ").append(total - shown).append(" kaynak daha (toplam ").append(total).append(")</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }
    /** Sorunlu kaynakların plain-text karşılığı. */
    private static String pageIssuesText(String tsv, Integer total) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String line : tsv.split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            sb.append(pageIssueTypeLabel(p.length > 0 ? p[0] : "")).append(" · ").append(p.length > 1 ? p[1] : "");
            if (p.length > 2 && !p[2].isBlank()) sb.append(" (HTTP ").append(p[2]).append(')');
            sb.append('\n');
            shown++;
        }
        if (total != null && total > shown) sb.append("… ve ").append(total - shown).append(" kaynak daha (toplam ").append(total).append(')');
        return sb.toString().trim();
    }
    private static String shortFp(String fp) {
        String f = fp.replace(":", "").trim();
        return f.length() > 20 ? f.substring(0, 8) + "…" + f.substring(f.length() - 8) : f;
    }

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

    /** ISO → "6 Ağu" (timeline için kompakt). Ayrıştırılamazsa tarih kısmı. */
    static String formatShort(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            String s = iso.trim();
            if (s.length() <= 10) {
                return java.time.LocalDate.parse(s).format(SHORT);
            }
            OffsetDateTime odt = OffsetDateTime.parse(
                    s.endsWith("Z") || s.contains("+") || s.matches(".*T.*[+-]\\d\\d:\\d\\d") ? s : s + "Z");
            return odt.atZoneSameInstant(IST).format(SHORT);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String urlenc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
