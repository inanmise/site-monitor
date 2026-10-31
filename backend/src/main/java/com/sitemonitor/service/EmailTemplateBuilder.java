package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Point;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailTokens;
import com.sitemonitor.service.mail.MailTokens.Tone;
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
 * TÜM süre-bitişi ve kusur alarm e-postaları (DOMAINMON_*, sertifika süre bitişi/iptal/zincir,
 * sayfa, sentetik, sayfa hızı, yavaşlık aileleri) tek merkezden bu builder'dan geçer — içerik veri
 * parametre, tasarım {@link MailDoc}'ta (e-posta yeniden tasarımı 2026-09-26).
 *
 * <p>Düzen: nötr başlık (logo + alt-sistem) → önem rozeti (+ ≤3 günde ACİL) → alan adı başlığı →
 * kalan gün metrik kartı (ilerleme çubuğu, 90 gün penceresi) ya da tonlu uyarı → zaman çizelgesi →
 * ayrıntılar → envanter kartları → Önerilen Aksiyon kartı (adımlar + birincil buton) → olay
 * aksiyonları → alt bilgi ("Neden bu e-postayı aldınız?"). Aciliyet rengi kalan günden gelir.
 * HTML + plain-text pariteli ({@link #buildText} — stripHtml bitişikliği yaşanmaz).
 */
@Component
@RequiredArgsConstructor
public class EmailTemplateBuilder {

    private final AppSettingsService appSettings;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Progress bar penceresi — "bitişe kalan gün / bu pencere" oranı çizilir. */
    private static final int PROGRESS_WINDOW_DAYS = 90;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", new Locale("tr", "TR"));
    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("d MMM", new Locale("tr", "TR"));

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

    // ── Severity / aciliyet ────────────────────────────────────────────────────
    /** Önem → ton: KRİTİK kırmızı, YÜKSEK amber, UYARI mavi, BİLGİ nötr. */
    static Tone severityTone(String level) {
        if (level == null) return Tone.INFO;
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> Tone.DESTRUCTIVE;
            case "HIGH" -> Tone.WARNING;
            case "INFO", "LOW" -> Tone.NEUTRAL;
            default -> Tone.INFO;   // WARNING/MEDIUM
        };
    }
    /**
     * Önem sözcüğü — TEK sözlük {@link EscalationService#levelWordTr} (D-2, 2026-09-29): WARNING e-posta konusu,
     * rozeti, ileti gövdesi, push ve arayüz rozetinde aynı sözcük ("UYARI"). Eskiden burada "ORTA" yazıyordu; aynı
     * e-postada rozet "ORTA", gövde "UYARI:" çelişiyordu.
     */
    static String severityLabel(String level) {
        return EscalationService.levelWordTr(level);
    }
    /** Önem rozeti — KRİTİK dolu kırmızı, diğerleri tonlu. */
    static Badge severityBadge(String level) {
        Tone t = severityTone(level);
        return t == Tone.DESTRUCTIVE ? Badge.solid(severityLabel(level), t) : Badge.tint(severityLabel(level), t);
    }

    /** Kalan güne göre aciliyet tonu (≤7 kırmızı, ≤30 amber, üstü yeşil); gün yoksa önem tonu. */
    static Tone urgencyTone(Integer days, String level) {
        if (days == null) return severityTone(level);
        if (days <= 7)  return Tone.DESTRUCTIVE;
        if (days <= 30) return Tone.WARNING;
        return Tone.SUCCESS;
    }

    /** Aciliyet rengi (metrik rakamı, çubuk, vurgu) — {@link #urgencyTone} ile aynı skala. */
    static String urgencyColor(Integer days, String level) {
        return urgencyTone(days, level).strong;
    }

    private static boolean isDomain(String t) {
        return t != null && (t.startsWith("DOMAINMON_") || "DOMAIN_EXPIRY".equals(t));
    }
    /**
     * HTTP izleme ailesi ({@code HTTP_DOWN} / {@code HTTP_SLOW} / {@code HTTP_SSL}) — 2026-10-22, ALERTS-13. Eskiden
     * {@link #tabFor}'da dalı yoktu, sertifika varsayılanına düşüyordu: e-posta "Sertifika İzleme" başlığı, sertifika ayrıntı
     * satırları ve "CA/PKI ekibinden yeni sertifika talep edin" adımlarıyla gidiyordu.
     */
    static boolean isHttp(String t) { return t != null && t.startsWith("HTTP_"); }
    /** Süre bitişi türleri — "yenilendi" / "Yeni bitiş tarihi" dili YALNIZ bunlarda (ALERTS-15). */
    static boolean isExpiryType(String t) {
        return "EXPIRY".equals(t) || "DOMAINMON_EXPIRY".equals(t) || "DOMAIN_EXPIRY".equals(t);
    }
    /**
     * Alarmı bir KİŞİ mi kapattı (ALERTS-15)? Sistem kapanışları "Sistem (…)" / "system" ile ya da boş yazılır; başka her değer
     * kişi adıdır. Elle kapanışta "otomatik olarak kapandı" / "yenilendi" denmez.
     */
    static boolean manualResolve(String resolvedBy) {
        if (resolvedBy == null || resolvedBy.isBlank()) return false;
        String r = resolvedBy.trim().toLowerCase(java.util.Locale.ROOT);
        return !(r.startsWith("sistem") || r.startsWith("system"));
    }
    /**
     * Kapanış DOĞRULANMIŞ bir iyileşme mi (sağlıklı kontrolle uzlaştırma: boş / "Sistem (otomatik)" / "system")? Başka bir sistem
     * gerekçesi ("Sistem (domain değişti)", "Sistem (alarm türü ayardan kapatıldı — sorun sürüyor olabilir)" …) iyileşme
     * DEĞİLDİR — o çözüm postası "düzeldi / yenilendi" demez, yalnız "otomatik olarak kapandı" der.
     */
    static boolean verifiedRecovery(String resolvedBy) {
        if (resolvedBy == null || resolvedBy.isBlank()) return true;
        String r = resolvedBy.trim().toLowerCase(java.util.Locale.ROOT);
        return r.equals("sistem (otomatik)") || r.equals("system") || r.equals("sistem");
    }
    private static boolean isPage(String t) { return "PAGE_DOWN".equals(t) || "PAGE_INTEGRITY".equals(t); }
    private static boolean isScripted(String t) {
        return "SCRIPTED_FAIL".equals(t) || "SCRIPTED_SLOW".equals(t);
    }
    private static boolean isPageSpeed(String t) {
        return "PAGESPEED_DOWN".equals(t) || "PAGESPEED_SLOW".equals(t);
    }
    /** Veritabanı sorgu izlemesi ailesi (11. tür, 2026-10-31) — {@link #tabFor} dalı ŞART: yoksa {@link #isCert} true olur
     *  ve posta "Sertifika İzleme" başlığı / sertifika adımlarıyla giderdi. */
    static boolean isDbQuery(String t) { return t != null && t.startsWith("DBQUERY_"); }
    /**
     * AİLE tanımları — tek tip yerine tür ailesi.
     *
     * <p><b>Neden gerekti.</b> {@code tabFor}/{@code subsystemLabel} yalnız ailenin "DOWN"
     * üyesini tanıyordu; {@code PORT_SLOW}/{@code KEYWORD_SLOW} (ve yeni {@code PING_SLOW})
     * son dala düşüyordu. Sonuç: yavaşlık alarmının e-postası "Sertifika İzleme" başlığıyla
     * gidiyor ve linki izleme sayfası yerine panoya çıkıyordu — yanlış bilgi, yanlış hedef.
     * Sayfa/Sentetik/Sayfa-Hızı aileleri bu yüzden zaten aile olarak tanımlıydı; port, ping ve
     * keyword eksik kalmıştı.
     */
    private static boolean isPing(String t) { return "PING_DOWN".equals(t) || "PING_SLOW".equals(t); }
    private static boolean isPort(String t) { return "PORT_DOWN".equals(t) || "PORT_SLOW".equals(t); }
    private static boolean isKeyword(String t) {
        return t != null && (t.equals("KEYWORD") || t.startsWith("KEYWORD_"));
    }
    /** Sertifika alarmı mı (EXPIRY/REVOKED/MISMATCH/CHAIN_BROKEN — "dashboard" sekmesine düşen default dal).
     *  Paket görünürlüğü: {@code EscalationService} envanter zenginleştirmesini aynı tanıma bağlar. */
    static boolean isCert(String t) { return tabFor(t).equals("dashboard"); }

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
        if (isPort(t)) return "port";
        if (t.startsWith("DNS_")) return "dns";
        if (isKeyword(t)) return "keyword";
        if (isPing(t)) return "ping";
        if (isPage(t)) return "page";
        if (isPageSpeed(t)) return "pagespeed";
        if (isScripted(t)) return "scripted";
        if (isHttp(t)) return "http";
        if (isDbQuery(t)) return "dbquery";
        return "dashboard";   // sertifika
    }

    /** Gün sayacı yoksa uyarı kutusunun başlığı (tip etiketi). */
    private static String heroLabel(String t) {
        if (t == null) return "İzleme Uyarısı";
        return switch (t) {
            case "DOMAINMON_EXPIRY", "DOMAIN_EXPIRY" -> "Alan Adı Süre Bitişi";
            case "DOMAINMON_STATUS"  -> "Alan Adı Durum Uyarısı";
            case "DOMAINMON_CHANGED" -> "Alan Adı Değişiklik Uyarısı";
            case "DOMAINMON_UNKNOWN" -> "Alan Adı Veri Uyarısı";
            case "DOMAINMON_TRANSFER_LOCK" -> "Alan Adı Transfer Kilidi";
            case "DOMAINMON_BLACKLIST" -> "Alan Adı Kara Liste";
            case "REVOKED"      -> "Sertifika İptal Uyarısı";
            case "MISMATCH"     -> "Sertifika Dağıtım Uyarısı";
            case "CHAIN_BROKEN" -> "Sertifika Zincir Uyarısı";
            case "HOSTNAME_MISMATCH" -> "Sertifika Alan Adı Uyuşmazlığı";
            case "UNTRUSTED_CA" -> "Güvenilmeyen Sertifika Uyarısı";
            case "HTTP_DOWN" -> "HTTP/Website Erişilemez";
            case "HTTP_SLOW" -> "HTTP Yavaş Yanıt";
            case "HTTP_SSL" -> "HTTP İzlemesi — TLS Sertifika Sorunu";
            case "DBQUERY_FAIL" -> "Veritabanı Sorgusu Çalıştırılamadı";
            case "DBQUERY_THRESHOLD" -> "Veritabanı Sorgu Kuralı İhlali";
            case "DBQUERY_SLOW" -> "Veritabanı Yavaş Sorgu";
            default -> "İzleme Uyarısı";
        };
    }

    // ── HTML ─────────────────────────────────────────────────────────────────
    public String buildHtml(AlertMail m) {
        Integer days = m.daysRemaining();
        Tone tone = urgencyTone(days, m.level());
        String expiryIso = firstNonNull(strCtx(m.ctx(), "expiry_date"), strCtx(m.ctx(), "not_after"));
        String href = liveBaseUrl() + alertQuery(m.alertType(), m.domain(), m.ctx());
        String checkedAt = strCtx(m.ctx(), "checked_at");
        String summary = shortSummary(m);

        MailDoc d = MailDoc.create("[Site Monitor] " + severityLabel(m.level()) + " · " + nz(m.domain()))
                .preheader(summary)
                .kicker(subsystemLabel(m.alertType()));
        // Sorgu sonucu satır tablosu veri-yoğundur → 640 px kart (telefonda yine tek sütun).
        if (isDbQuery(m.alertType()) && dbQueryRows(m.ctx()) != null) d.wide();
        d.badges(severityBadge(m.level()), days != null && days <= 3 ? Badge.solid("ACİL", Tone.DESTRUCTIVE) : null);
        // Süre-DIŞI sertifika kusurlarında gün sayacı yanıltıcıdır (1775 gün geçerli ama kabul
        // edilemez sertifika) → metrik yerine tip etiketli uyarı gösterilir.
        if (days != null && EscalationService.isDurationAlert(m.alertType())) {
            d.title(nz(m.domain()), summary);
            d.metricCard(String.valueOf(days), "gün kaldı", tone.strong, Math.round(days * 100f / PROGRESS_WINDOW_DAYS),
                    "Bitişe " + days + " gün" + (expiryIso != null ? " · Son tarih: " + formatHuman(expiryIso) : ""));
        } else {
            d.title(nz(m.domain()), null);
            d.alert(tone, heroLabel(m.alertType()), summary);
        }
        List<TlPoint> pts = timelinePoints(m, expiryIso);
        if (!pts.isEmpty()) {
            List<Point> points = new ArrayList<>();
            for (TlPoint p : pts) points.add(new Point(p.label(), p.date(), p.emphasized()));
            d.timeline(points, tone.strong);
        }
        d.keyValue("Ayrıntılar", detailRows(m));
        if (isDbQuery(m.alertType())) dbQuerySections(d, m.ctx());
        // Envanter bağlamı — "Önerilen Aksiyon"dan ÖNCE: okuyucu önce sertifikanın nerede durduğunu
        // (operasyonel bayraklar) ve takımın kendi yenileme sürecini görür, sonra genel adımları.
        manualSourceSection(d, m);   // yalnız elle yüklenen sertifikada (ctx anahtarı yoksa hiçbir şey eklenmez)
        opsSection(d, m);
        contactsSection(d, m);
        changeDescSection(d, m);
        actionSection(d, m, expiryIso, href);
        MailCta.appendIncidentActions(d, liveBaseUrl(), m.ctx() == null ? null : m.ctx().get("alert_event_id"));
        // Runbook notu (2026-10-01): izlemenin rehberi varsa gövdenin SONUNA "Ne yapılmalı"; yoksa hiçbir şey eklenmez.
        com.sitemonitor.service.mail.RunbookNote.appendTo(d, m.ctx());
        d.footerWhy(m.teamName())
         .footerMeta("Bu e-posta Site Monitor " + subsystemLabel(m.alertType()) + " tarafından otomatik gönderilmiştir",
                 checkedAt != null ? "Son kontrol: " + formatHuman(checkedAt) : null,
                 "Bildirim ayarları için yöneticinize başvurun.");
        return d.html();
    }

    /** "Önerilen Aksiyon" kartı: (≤7 gün) acil satırı + numaralı adımlar + birincil buton. */
    private void actionSection(MailDoc d, AlertMail m, String expiryIso, String href) {
        String urgent = urgentLine(m, expiryIso);
        List<String> steps = actionSteps(m);
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        if (urgent != null) {
            html.append(MailKit.space(MailKit.alert(Tone.DESTRUCTIVE, MailKit.esc(urgent), null), 12));
            text.append(urgent).append('\n');
        }
        if (steps.size() == 1) {
            html.append(MailKit.space(MailKit.paragraph(MailKit.esc(steps.get(0))), 16));
            text.append(steps.get(0)).append('\n');
        } else {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                items.add(MailKit.esc(steps.get(i)));
                text.append(i + 1).append(". ").append(steps.get(i)).append('\n');
            }
            html.append(MailKit.space(MailKit.steps(items), 16));
        }
        html.append(MailKit.button(href, "Site Monitor'de Görüntüle", MailKit.Variant.PRIMARY, d.contentWidth() - 34));
        text.append("Site Monitor'de Görüntüle: ").append(href);
        d.card("Önerilen Aksiyon", null, html.toString(), text.toString());
    }

    /** Plain-text multipart alternatifi — sayaç/timeline/adımlar HTML ile pariteli. */
    public String buildText(AlertMail m) {
        Integer days = m.daysRemaining();
        String head = days != null
                ? (days <= 3 ? "ACİL " : "") + days + " GÜN KALDI"
                : severityLabel(m.level());
        String expiryIso = firstNonNull(strCtx(m.ctx(), "expiry_date"), strCtx(m.ctx(), "not_after"));

        StringBuilder sb = new StringBuilder();
        sb.append("[Site Monitor] ").append(head).append(" · ").append(nz(m.domain())).append('\n');
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
        if (isDbQuery(m.alertType())) sb.append(dbQueryText(m.ctx()));
        // HTML paritesi: envanter bölümleri metin sürümde de aynı sırayla yer alır.
        String manualSrc = m.ctx() == null ? null : strCtx(m.ctx(), EscalationService.CTX_MANUAL_SOURCE);
        if (manualSrc != null) sb.append('\n').append(manualSrc).append('\n');
        List<String> ops = opsLabels(m.ctx());
        if (!ops.isEmpty()) sb.append("\nOperasyonel Bilgiler: ").append(String.join(", ", ops)).append('\n');
        Map<String, String> contacts = contactMap(m.ctx());
        if (!contacts.isEmpty()) {
            sb.append("\nSorumlu Ekipler:\n");
            // E-posta duz metinde adres olarak AYNEN yazilir; mailto: sarmalamasi yalniz HTML'de.
            contacts.forEach((label, value) -> sb.append(label).append(": ").append(value).append('\n'));
        }
        String desc = m.ctx() == null ? null : strCtx(m.ctx(), "inv_change_desc");
        if (desc != null && !desc.isBlank()) {
            List<String> lines = descLines(desc);
            if (!lines.isEmpty()) {
                sb.append("\nDeğişiklik Açıklaması:\n");
                for (String line : lines) sb.append(line).append('\n');
            }
        }
        sb.append('\n').append("Önerilen Aksiyon:").append('\n');
        String urgent = urgentLine(m, expiryIso);
        if (urgent != null) sb.append(urgent).append('\n');
        List<String> steps = actionSteps(m);
        if (steps.size() == 1) {
            sb.append(steps.get(0)).append('\n');
        } else {
            for (int i = 0; i < steps.size(); i++) sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        // HTML ile aynı taban (sondaki / kırpılır, boşsa ""); eskiden ham ayar "//?tab=" üretebiliyordu.
        sb.append(liveBaseUrl()).append(alertQuery(m.alertType(), m.domain(), m.ctx())).append('\n');
        // HTML/metin paritesi: olay aksiyon linkleri metin sürümde de bulunur (kimlik yoksa "").
        sb.append(MailCta.incidentActionText(liveBaseUrl(), m.ctx() == null ? null : m.ctx().get("alert_event_id")));
        // HTML paritesi: runbook notu (rehber yoksa "" — metin bugünküyle aynı).
        sb.append(com.sitemonitor.service.mail.RunbookNote.textBlock(m.ctx()));
        sb.append("\n— Site Monitor ").append(subsystemLabel(m.alertType()));
        return sb.toString();
    }

    // ── Çözüldü (resolved) — yeşil rozet + zengin bağlam ─────────────────────
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

    /** teamNames verilirse "Neden bu e-postayı aldınız?" alıcı-şeffaflık bloğu takım adıyla yazılır. */
    public String buildResolvedHtml(String domain, String alertType, String resolvedBy, String resolvedAt,
                                    String createdAt, Map<String, Object> ctx, String teamNames) {
        String d = esc(domain);
        String href = liveBaseUrl() + alertQuery(alertType, domain, ctx);

        boolean domainType = isDomain(alertType);
        String expiryIso = ctx == null ? null
                : firstNonNull(strCtx(ctx, "expiry_date"), strCtx(ctx, "not_after"));
        String daysRem = ctx == null ? null
                : firstNonNull(strCtx(ctx, "days_remaining"), strCtx(ctx, "days"));

        List<Row> rows = new ArrayList<>();
        rows.add(new Row(isDbQuery(alertType) ? "İzleme" : "Alan Adı", "<strong>" + d + "</strong>", nz(domain)));
        // "Yeni" yalnız süre bitişi alarmının OTOMATİK kapanışında doğrudur (ALERTS-15): kara liste / kilit / durum çözümü ya da
        // elle kapanış alan adının yenilendiğini söylemez.
        boolean renewed = isExpiryType(alertType) && verifiedRecovery(resolvedBy);
        if (ctx != null) {
            if (expiryIso != null)
                rows.add(Row.of(domainType && renewed ? "Yeni Bitiş Tarihi" : "Bitiş Tarihi", formatHuman(expiryIso)));
            if (daysRem != null) rows.add(Row.of("Kalan Süre", daysRem + " gün"));
            if (domainType) {
                String reg = strCtx(ctx, "registrar");
                if (reg != null) rows.add(Row.of("Kayıt Kuruluşu", reg));
                String src = strCtx(ctx, "source");
                if (src != null) rows.add(Row.of("Veri Kaynağı", src));
                String epp = strCtx(ctx, "status_codes");
                if (epp != null && !epp.isBlank()) rows.add(new Row("EPP Durum Kodları", eppPills(epp), eppText(epp)));
                String ns = strCtx(ctx, "nameservers");
                if (ns != null) rows.add(Row.of("Ad Sunucuları", ns));
            } else {
                String ca = firstNonNull(strCtx(ctx, "issuer_cn"), strCtx(ctx, "issuer"));
                if (ca != null) rows.add(Row.of("Veren Kurum (CA)", ca));
            }
        }
        // Sayfa Bütünlüğü / Sayfa Yüklenemiyor çözümü — "sorun neydi + ne çözüldü" (2026-08-04).
        // Alarm-anı anahtarları snapshotContext'ten, resolved_* anahtarları çözüm anındaki son PageCheck'ten gelir;
        // eski (bu sürümden önce açılmış) alarmların snapshot'ında anahtarlar yok → satırlar zarifçe atlanır.
        if (ctx != null && isPage(alertType)) {
            rows.add(Row.of("Çözülen Alarm", "PAGE_DOWN".equals(alertType) ? "Sayfa Yüklenemiyor" : "Sayfa Bütünlüğü"));
            String detail = strCtx(ctx, "detail");
            if (detail != null && !detail.isBlank()) {
                rows.add(Row.of("Sorun (alarm anı)", detail));
            } else {
                String b = strCtx(ctx, "broken_resources");
                if (b != null) rows.add(Row.of("Kırık Kaynak (alarm anı)", b));
                String tmo = strCtx(ctx, "timeout_count");
                if (tmo != null) rows.add(Row.of("Zaman Aşımı (alarm anı)", tmo));
                String mx = strCtx(ctx, "mixed_content_count");
                if (mx != null) rows.add(Row.of("Mixed Content (alarm anı)", mx));
            }
            if ("PAGE_DOWN".equals(alertType)) {
                String err = strCtx(ctx, "last_error");
                if (err != null) rows.add(Row.of("Son Hata", err.length() > 120 ? err.substring(0, 120) + "…" : err));
                String hs = strCtx(ctx, "http_status");
                if (hs != null) rows.add(Row.of("HTTP Durumu", hs));
            }
            String tsv = strCtx(ctx, "problem_rows");
            if (tsv != null && !tsv.isBlank())
                rows.add(new Row("Giderilen Sorunlu Kaynaklar", pageIssuesHtml(tsv, intCtx(ctx, "problem_total")),
                        pageIssuesText(tsv, intCtx(ctx, "problem_total"))));
            String cur = strCtx(ctx, "resolved_page_status");
            if (cur != null) {
                String tot  = strCtx(ctx, "resolved_total_resources");
                String when = strCtx(ctx, "resolved_checked_at");
                String curText = "OK".equals(cur)
                        ? "Sağlıklı" + (tot != null ? " — " + tot + " kaynağın tümü erişilebilir" : "")
                        : pageStatusTr(cur);
                String curHtml = "OK".equals(cur)
                        ? MailKit.strong("Sağlıklı", MailTokens.SUCCESS) + (tot != null ? " — " + esc(tot) + " kaynağın tümü erişilebilir" : "")
                        : esc(pageStatusTr(cur));
                if (when != null) {
                    curHtml += " <span style=\"color:" + MailTokens.MUTED + "\">(" + esc(formatHuman(when)) + ")</span>";
                    curText += " (" + formatHuman(when) + ")";
                }
                rows.add(new Row("Güncel Durum", curHtml, curText));
            }
        }
        if (ctx != null && isDbQuery(alertType)) rows.addAll(dbQueryResolvedRows(alertType, ctx));
        String durHuman = durationHuman(createdAt, resolvedAt);
        // Sentetik cozumu: alarm e-postasinda dolu bir dal vardi ama COZUM e-postasinda YOKTU.
        // Nobetci "ne duzeldi" bilgisini alamiyor, jenerik "alarm kapandi" cumlesiyle kaliyordu.
        if (ctx != null && "SCRIPTED_SLOW".equals(alertType)) {
            rows.add(Row.of("Çözülen Alarm", "Sentetik Yavaş Koşum"));
            String sMs = strCtx(ctx, "duration_ms");
            String sTh = strCtx(ctx, "threshold_ms");
            if (sMs != null) rows.add(Row.of("Süre (alarm anı)", sMs + " ms"));
            if (sTh != null) rows.add(Row.of("Eşik", sTh + " ms"));
        } else if (ctx != null && EscalationService.isScripted(alertType)) {
            rows.add(Row.of("Çözülen Alarm", "Sentetik İzleme"));
            String sDetail = strCtx(ctx, "detail");
            if (sDetail != null && !sDetail.isBlank()) rows.add(Row.of("Sorun (alarm anı)", sDetail));
            String sErr = firstNonNull(strCtx(ctx, "error"), strCtx(ctx, "last_error"));
            if (sErr != null && !sErr.isBlank())
                rows.add(Row.of("Hata (alarm anı)", sErr.length() > 200 ? sErr.substring(0, 200) + "…" : sErr));
            String sFailed = strCtx(ctx, "failed_checks");
            if (sFailed != null && !sFailed.isBlank()) rows.add(Row.of("Düşen Doğrulamalar", sFailed));
        }
        if (durHuman != null) rows.add(Row.of("Alarm Süresi", durHuman));
        if (resolvedAt != null) rows.add(Row.of("Çözülme", formatHuman(resolvedAt)));
        rows.add(Row.of("Çözen", resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)"));

        String headline = resolvedHeadline(alertType, expiryIso != null, resolvedBy);
        MailDoc doc = MailDoc.create("[Site Monitor] ÇÖZÜLDÜ · " + nz(domain))
                .preheader(headline)
                .kicker(subsystemLabel(alertType));
        doc.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS));
        doc.title(nz(domain), headline);
        // Yeni bitiş tarihini öne çıkaran vurgu (varsa) — "yeni expire ne oldu?" sorusuna doğrudan yanıt.
        if (expiryIso != null) {
            doc.alert(Tone.SUCCESS, (domainType && renewed ? "Yeni bitiş tarihi: " : "Güncel bitiş tarihi: ") + formatHuman(expiryIso),
                    daysRem != null ? daysRem + " gün kaldı" : null);
        }
        doc.keyValue("Ayrıntılar", rows);
        doc.button(href, "Site Monitor'de Görüntüle");
        MailCta.appendIncidentActions(doc, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        doc.footerWhy(teamNames).footerMeta("Bu e-posta Site Monitor tarafından otomatik gönderilmiştir.");
        return doc.html();
    }

    public String buildResolvedText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return buildResolvedText(domain, alertType, resolvedBy, resolvedAt, null, null);
    }

    public String buildResolvedText(String domain, String alertType, String resolvedBy, String resolvedAt,
                                    String createdAt, Map<String, Object> ctx) {
        boolean domainType = isDomain(alertType);
        String expiryIso = ctx == null ? null : firstNonNull(strCtx(ctx, "expiry_date"), strCtx(ctx, "not_after"));
        String daysRem = ctx == null ? null : firstNonNull(strCtx(ctx, "days_remaining"), strCtx(ctx, "days"));
        boolean renewed = isExpiryType(alertType) && verifiedRecovery(resolvedBy);
        StringBuilder sb = new StringBuilder("[Site Monitor] ÇÖZÜLDÜ · " + nz(domain) + "\n"
                + resolvedHeadline(alertType, expiryIso != null, resolvedBy));
        if (expiryIso != null)
            sb.append("\n").append(domainType && renewed ? "Yeni Bitiş Tarihi: " : "Bitiş Tarihi: ").append(formatHuman(expiryIso));
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
        if (ctx != null && isDbQuery(alertType)) {   // HTML ile aynı bilgi (veritabanı sorgusu)
            for (Row r : dbQueryResolvedRows(alertType, ctx)) sb.append("\n").append(r.label()).append(": ").append(r.text());
        }
        String durHuman = durationHuman(createdAt, resolvedAt);
        if (ctx != null && "SCRIPTED_SLOW".equals(alertType)) {   // HTML ile ayni bilgi
            sb.append("\nÇözülen Alarm: Sentetik Yavaş Koşum");
            String sMs = strCtx(ctx, "duration_ms");
            String sTh = strCtx(ctx, "threshold_ms");
            if (sMs != null) sb.append("\nSüre (alarm anı): ").append(sMs).append(" ms");
            if (sTh != null) sb.append("\nEşik: ").append(sTh).append(" ms");
        } else if (ctx != null && EscalationService.isScripted(alertType)) {
            sb.append("\nÇözülen Alarm: Sentetik İzleme");
            String sDetail = strCtx(ctx, "detail");
            if (sDetail != null && !sDetail.isBlank()) sb.append("\nSorun (alarm anı): ").append(sDetail);
            String sErr = firstNonNull(strCtx(ctx, "error"), strCtx(ctx, "last_error"));
            if (sErr != null && !sErr.isBlank())
                sb.append("\nHata (alarm anı): ").append(sErr.length() > 200 ? sErr.substring(0, 200) + "…" : sErr);
            String sFailed = strCtx(ctx, "failed_checks");
            if (sFailed != null && !sFailed.isBlank()) sb.append("\nDüşen Doğrulamalar: ").append(sFailed);
        }
        if (durHuman != null) sb.append("\nAlarm Süresi: ").append(durHuman);
        if (resolvedAt != null) sb.append("\nÇözülme: ").append(formatHuman(resolvedAt));
        sb.append("\nÇözen: ").append(resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)");
        // HTML/metin paritesi: HTML'deki "Site Monitor'de Görüntüle" düğmesinin metin karşılığı (2026-09-28).
        sb.append("\nSite Monitor'de Görüntüle: ").append(liveBaseUrl()).append(alertQuery(alertType, domain, ctx));
        // HTML/metin paritesi: olay aksiyon linkleri (kimlik yoksa "").
        sb.append(MailCta.incidentActionText(liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id")));
        return sb.toString();
    }

    /**
     * Veritabanı sorgu alarmı çözüm satırları (HTML + düz metin ortak): ne için, nerede, alarm anında ne vardı ve ŞU AN ne
     * var. Alarm anı anahtarları olayın anlık görüntüsünden ({@link EscalationService#RESOLVED_CONTEXT_KEYS}), {@code
     * resolved_*} anahtarları çözüm anındaki SON çalıştırmadan gelir (EscalationService.withDbQueryResolvedValues).
     */
    static List<Row> dbQueryResolvedRows(String alertType, Map<String, Object> ctx) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Çözülen Alarm", switch (alertType == null ? "" : alertType) {
            case "DBQUERY_FAIL" -> "Veritabanı Sorgu Hatası";
            case "DBQUERY_SLOW" -> "Veritabanı Yavaş Sorgu";
            default -> "Veritabanı Sorgu Eşiği";
        }));
        String purpose = strCtx(ctx, "purpose");
        if (purpose != null) rows.add(Row.of("Alarm Amacı", purpose));
        List<String> conn = new ArrayList<>();
        String profile = strCtx(ctx, "profile_name");
        if (profile != null) conn.add(profile);
        String type = com.sitemonitor.service.dbquery.DbQueryAlarmText.dbTypeLabel(strCtx(ctx, "db_type"));
        if (type != null) conn.add(type);
        if (!conn.isEmpty()) rows.add(Row.of("Bağlantı", String.join(" · ", conn)));
        if ("DBQUERY_FAIL".equals(alertType)) {
            String reason = com.sitemonitor.service.dbquery.DbQueryAlarmText.failureLabel(strCtx(ctx, "failure_reason"));
            if (reason != null) rows.add(Row.of("Sorun (alarm anı)", reason));
            String err = firstNonNull(strCtx(ctx, "error"), strCtx(ctx, "last_error"));
            if (err != null) rows.add(Row.of("Hata (alarm anı)", err.length() > 200 ? err.substring(0, 200) + "…" : err));
        } else {
            String was = firstNonNull(strCtx(ctx, "rule_summary"), strCtx(ctx, "detail"));
            if (was != null) rows.add(Row.of("Sorun (alarm anı)", was));
        }
        String cur = strCtx(ctx, "resolved_dbquery_status");
        if (cur != null) {
            String curText = switch (cur) {
                case "OK" -> "Sorgu çalışıyor, kurallar sağlanıyor";
                case "BREACH" -> "Sorgu çalışıyor — kural ihlali sürüyor: " + nz(strCtx(ctx, "resolved_rule_summary"));
                case "FAIL" -> "Son çalıştırma başarısız";
                default -> cur;
            };
            String when = strCtx(ctx, "resolved_checked_at");
            rows.add(Row.of("Güncel Durum", curText + (when != null ? " (" + formatHuman(when) + ")" : "")));
            String n = strCtx(ctx, "resolved_row_count");
            if (n != null) rows.add(Row.of("Dönen Satır (güncel)", ("true".equalsIgnoreCase(strCtx(ctx, "resolved_row_count_capped")) ? "≥ " : "") + n));
            String val = com.sitemonitor.service.EscalationService.numberText(ctx.get("resolved_metric_value"));
            if (val != null) rows.add(Row.of("Ölçülen Değer (güncel)", val));
            String dur = strCtx(ctx, "resolved_duration_ms");
            if (dur != null) rows.add(Row.of("Süre (güncel)", dur + " ms"));
        }
        return rows;
    }

    /**
     * Çözüm alt başlığı — türe ve kapanış biçimine göre (ALERTS-15). "Yenilendi" yalnız süre bitişi türünün otomatik
     * kapanışında; kişi kapattıysa "elle kapatıldı" (yanında "Çözen: <kişi>" satırı) — "otomatik olarak kapandı" denmez.
     */
    static String resolvedHeadline(String alertType, boolean hasExpiry, String resolvedBy) {
        if (manualResolve(resolvedBy)) return "Alarm elle kapatıldı.";
        if (!verifiedRecovery(resolvedBy)) return "Alarm otomatik olarak kapandı.";
        if (alertType != null) {
            switch (alertType) {
                case "DOMAINMON_BLACKLIST": return "Alan adı artık kara listede görünmüyor — alarm otomatik olarak kapandı.";
                case "DOMAINMON_TRANSFER_LOCK": return "Transfer kilidi yeniden etkin — alarm otomatik olarak kapandı.";
                case "DOMAINMON_STATUS": return "Sorunlu alan adı durumu kalktı — alarm otomatik olarak kapandı.";
                case "DOMAINMON_UNKNOWN": return "Alan adı kayıt bilgisi yeniden okunabiliyor — alarm otomatik olarak kapandı.";
                case "CHAIN_BROKEN": return "Sertifika zinciri düzeldi — alarm otomatik olarak kapandı.";
                case "HOSTNAME_MISMATCH": return "Sertifika alan adıyla yeniden eşleşiyor — alarm otomatik olarak kapandı.";
                case "UNTRUSTED_CA": return "Sertifika yeniden güvenilir görünüyor — alarm otomatik olarak kapandı.";
                case "MISMATCH": return "Sertifika dağıtımı yeniden tutarlı — alarm otomatik olarak kapandı.";
                case "REVOKED": return "Sertifikada iptal durumu artık görülmüyor — alarm otomatik olarak kapandı.";
                case "HTTP_DOWN": return "Adres yeniden yanıt veriyor — alarm otomatik olarak kapandı.";
                case "HTTP_SLOW": return "Yanıt süresi eşiğin altına indi — alarm otomatik olarak kapandı.";
                case "HTTP_SSL": return "TLS sertifika sorunu giderildi — alarm otomatik olarak kapandı.";
                default: break;
            }
        }
        if (isDomain(alertType))
            return hasExpiry && isExpiryType(alertType) ? "Alan adı yenilendi, alarm otomatik olarak kapandı." : "Alarm otomatik olarak kapandı.";
        if (isCert(alertType))
            return hasExpiry && isExpiryType(alertType) ? "Sertifika yenilendi, alarm otomatik olarak kapandı." : "Alarm otomatik olarak kapandı.";
        if ("PAGE_INTEGRITY".equals(alertType))
            return "Sayfadaki bütünlük sorunu giderildi; kaynaklar yeniden sağlıklı — alarm otomatik olarak kapandı.";
        if ("PAGE_DOWN".equals(alertType))
            return "Sayfa yeniden yükleniyor — alarm otomatik olarak kapandı.";
        if ("SCRIPTED_SLOW".equals(alertType))
            return "Senaryo koşum süresi eşiğin altına indi — alarm otomatik olarak kapandı.";
        if (EscalationService.isScripted(alertType))
            return "Senaryo yeniden başarılı — alarm otomatik olarak kapandı.";
        if ("DBQUERY_FAIL".equals(alertType))
            return "Veritabanı sorgusu yeniden çalışıyor — alarm otomatik olarak kapandı.";
        if ("DBQUERY_THRESHOLD".equals(alertType))
            return "Sorgu sonucu kuralları yeniden sağlıyor — alarm otomatik olarak kapandı.";
        if ("DBQUERY_SLOW".equals(alertType))
            return "Sorgu süresi eşiğin altına indi — alarm otomatik olarak kapandı.";
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
        out.add(new Row(isHttp(m.alertType()) ? "Adres" : isDbQuery(m.alertType()) ? "İzleme" : "Alan Adı",
                "<strong>" + esc(m.domain()) + "</strong>", nz(m.domain())));
        if (isDbQuery(m.alertType())) {   // veritabanı sorgusu — ne için, nerede, ne zaman, ne döndü (2026-10-31)
            addIf(out, "Alarm Amacı", strCtx(c, "purpose"));
            addIf(out, "Bağlantı", dbQueryConnectionText(c));
            addIf(out, "Çalışma Zamanı", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
            String dur = strCtx(c, "duration_ms");
            String th = strCtx(c, "threshold_ms");
            addIf(out, "Süre", dur != null ? dur + " ms" + (th != null ? " (yavaş sorgu eşiği " + th + " ms)" : "") : null);
            addIf(out, "Dönen Satır", dbQueryRowCountText(c));
            addIf(out, "Ölçülen Değer", com.sitemonitor.service.EscalationService.numberText(c == null ? null : c.get("metric_value")));
            if ("DBQUERY_FAIL".equals(m.alertType())) {
                addIf(out, "Hata Türü", com.sitemonitor.service.dbquery.DbQueryAlarmText.failureLabel(strCtx(c, "failure_reason")));
                addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            } else {
                addIf(out, "İhlal", "DBQUERY_THRESHOLD".equals(m.alertType()) ? strCtx(c, "rule_summary") : null);
            }
            addIf(out, "Doğrulama", confirmationText(c));
        } else if (isHttp(m.alertType())) {   // HTTP izleme — sertifika satırları değil, isteğin sonucu (ALERTS-13)
            String url = strCtx(c, "url");
            if (url != null && !url.equals(m.domain())) addIf(out, "URL", url);
            addIf(out, "HTTP Durumu", strCtx(c, "http_status"));
            addIf(out, "Yanıt Süresi", strCtx(c, "response_ms") != null ? strCtx(c, "response_ms") + " ms" : null);
            addIf(out, "Eşik", strCtx(c, "threshold_ms") != null ? strCtx(c, "threshold_ms") + " ms" : null);
            addIf(out, "Sertifika Kalan Gün", strCtx(c, "ssl_days_remaining"));
            addIf(out, "Detay", strCtx(c, "detail"));
            addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
        } else if (isDomain(m.alertType())) {
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
            if (fp != null) out.add(new Row("SHA-256 Parmak İzi", MailKit.mono(shortFp(fp)), shortFp(fp)));
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
                if (probs != null) out.add(new Row("Sorunlu Kaynaklar", MailKit.escBr(probs), probs));
            }
            addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            addIf(out, "Son Kontrol", strCtx(c, "checked_at") != null ? formatHuman(strCtx(c, "checked_at")) : null);
        } else if (isScripted(m.alertType())) {   // senaryo (k6)
            addIf(out, "Sonuç", strCtx(c, "scripted_status"));
            addIf(out, "Doğrulama", confirmationText(c));
            // "Başarısız Check'ler" NEYİN düştüğünü söyler, NEDEN düştüğünü değil. Bu satır olmadan
            // nöbetçi "1✓/2✗" görüp k6 çıktısını açmak zorunda kalıyordu (Sayfa dalında zaten vardı).
            addIf(out, "Hata", firstNonNull(strCtx(c, "error"), strCtx(c, "last_error")));
            String failed = strCtx(c, "failed_checks");
            if (failed != null) {
                List<String> items = new ArrayList<>();
                for (String s : failed.split("\n")) if (!s.isBlank()) items.add(esc(s));
                out.add(new Row("Başarısız Check'ler", MailKit.bullets(items), failed));
            }
            String tail = strCtx(c, "output_tail");
            if (tail != null) out.add(new Row("Son Çıktı (maskeli)",
                    MailKit.pre(tail.length() > 2000 ? tail.substring(tail.length() - 2000) : tail), tail));
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
        return "Site Monitor bir izleme olayı tespit etti.";
    }

    // ── Aksiyon planı ────────────────────────────────────────────────────────
    /** Tip bazlı numaralı yapılacaklar listesi (tek elemanlıysa düz cümle olarak basılır). */
    private List<String> actionSteps(AlertMail m) {
        if (isDomain(m.alertType())) {
            String reg = strCtx(m.ctx(), "registrar");
            String panel = "Registrar paneline giriş yapın" + (reg != null ? " (" + reg + ")" : "");
            // Adımlar TÜRE göre (ALERTS-15): kara liste / transfer kilidi / durum / veri / değişiklik alarmı "alan adını
            // yenileyin" demez — yenileme yalnız süre bitişinin çaresidir.
            return switch (m.alertType()) {
                case "DOMAINMON_BLACKLIST" -> List.of(
                        "Alan adının hangi kara listede (DNSBL) göründüğünü yukarıdaki ayrıntılardan kontrol edin",
                        "Listelenme nedenini giderin (spam gönderimi, ele geçirilmiş sunucu, açık posta aktarıcısı) ve listenin kaldırma (delist) adımlarını izleyin",
                        "Kara listeden çıkış doğrulanınca alarm kendiliğinden kapanır");
                case "DOMAINMON_TRANSFER_LOCK" -> List.of(
                        panel + " ve transfer kilidini (clientTransferProhibited) yeniden açın",
                        "Kilidi siz kaldırmadıysanız yetkisiz bir transfer girişimi olabilir — registrar ile hemen iletişime geçin",
                        "Kilit yeniden görülünce alarm kendiliğinden kapanır");
                case "DOMAINMON_STATUS" -> List.of(
                        "Yukarıdaki EPP durum kodlarını inceleyin (ör. serverHold, clientHold, pendingDelete, redemptionPeriod)",
                        panel + " ya da registrar ile iletişime geçip durumun nedenini öğrenin ve giderin",
                        "Sorunlu durum kalkınca alarm kendiliğinden kapanır");
                case "DOMAINMON_UNKNOWN" -> List.of(
                        "Kayıt bilgisinin neden okunamadığını kontrol edin (RDAP / WHOIS erişimi, vekil sunucu, sorgu sınırı)",
                        "Site Monitor'de alan adı kaydında \"Sorun Tanıla\" ile sorguyu adım adım izleyin",
                        "Kayıt bilgisi yeniden okunabilince alarm kendiliğinden kapanır");
                case "DOMAINMON_CHANGED" -> List.of(
                        "Değişikliğin (registrar, ad sunucuları, durum kodları) planlı olup olmadığını ekibinizle doğrulayın",
                        "Planlı değilse " + (reg != null ? reg + " registrar'ı" : "registrar") + " ile hemen iletişime geçin",
                        "İnceleme bitince alarmı Site Monitor'de elle kapatın — değişiklik alarmı kendiliğinden kapanmaz");
                default -> List.of(
                        panel,
                        "Alan adını en az 1 yıl yenileyin",
                        "Auto-renew (otomatik yenileme) özelliğini açın",
                        "Yenileme sonrası Site Monitor'ün otomatik doğrulamasını bekleyin — alarm kendiliğinden kapanır");
            };
        }
        if (isHttp(m.alertType())) {   // ALERTS-13: HTTP alarmı sertifika adımlarını almaz (HTTP_SSL hariç)
            return switch (m.alertType()) {
                case "HTTP_SLOW" -> List.of(
                        "Yanıt süresini artıran bileşeni inceleyin (uygulama, veritabanı, yük dengeleyici, ağ)",
                        "Yukarıdaki yanıt süresi ve eşiği karşılaştırın; eşik gerçekçi değilse izleme ayarını gözden geçirin",
                        "Yanıt süresi eşiğin altına inince alarm kendiliğinden kapanır");
                case "HTTP_SSL" -> List.of(
                        "Adresin TLS sertifikasının geçerliliğini ve zincirini kontrol edin",
                        "Süresi doluyorsa ya da güvenilmiyorsa CA/PKI ekibinden yeni sertifika talep edip ilgili sunuculara dağıtın",
                        "Sertifika sorunu giderilince alarm kendiliğinden kapanır");
                default -> List.of(
                        "Adresin erişilebilirliğini kontrol edin (sunucu, uygulama, yük dengeleyici, ağ / WAF)",
                        "Yukarıdaki HTTP durumu ve hata ayrıntısına bakın; gerekirse Site Monitor'de \"Uçtan uca tanıla\" ile isteği izleyin",
                        "Erişim geri gelince alarm kendiliğinden kapanır");
            };
        }
        if (isDbQuery(m.alertType())) {
            // r177 (H-2): sorgu çalışıyor ama kural değerlendirilemiyor — bağlantı / ağ değil, kural / kolon sorunu
            if ("DBQUERY_FAIL".equals(m.alertType()) && com.sitemonitor.service.dbquery.DbQueryAlarmText.RULE_UNEVALUABLE_CODE
                    .equals(strCtx(m.ctx(), "failure_reason"))) {
                return List.of(
                        "Sorgu çalışıyor ama kuralların okuduğu kolon sonuçta yok ya da değeri uygun değil (sayı / zaman damgası) — kolon adı ya da sorgu değişmiş olabilir",
                        "Site Monitor'de izlemeyi açıp \"Sorguyu dene\" ile dönen kolonları ve kural sonucunu görün; kuralı ya da sorguyu düzeltin",
                        "Kurallar yeniden değerlendirilebilince alarm kendiliğinden kapanır");
            }
            return switch (m.alertType()) {
                case "DBQUERY_FAIL" -> List.of(
                        "Yukarıdaki hata türüne bakın: bağlantı / ağ, kimlik bilgisi, sorgu hatası ya da zaman aşımı",
                        "Veritabanı sunucusunun erişilebilirliğini ve bağlantı tanımını (sunucu, port, veritabanı, kullanıcı) Site Monitor'de \"Bağlantılar\" penceresinden deneyin",
                        "Sorgu yeniden çalışınca alarm kendiliğinden kapanır");
                case "DBQUERY_SLOW" -> List.of(
                        "Sorgunun neden yavaşladığını inceleyin (veri hacmi, eksik indeks, kilit beklemesi, sunucu yükü)",
                        "Yukarıdaki süreyi yavaş sorgu eşiğiyle karşılaştırın; eşik gerçekçi değilse izleme ayarını gözden geçirin",
                        "Sorgu süresi eşiğin altına inince alarm kendiliğinden kapanır");
                default -> List.of(
                        "Yukarıdaki kurallar tablosunda hangi kuralın, hangi değerle ihlal edildiğine bakın",
                        "Alarm amacında tanımlanan iş sürecini (ör. bekleyen kayıtlar, hatalı işlemler) ilgili ekiple kontrol edin",
                        "Kurallar yeniden sağlanınca alarm kendiliğinden kapanır — sonraki çalıştırma zamanlamaya göre yapılır");
            };
        }
        if (isPage(m.alertType())) {
            if ("PAGE_DOWN".equals(m.alertType()))
                return List.of("Sayfanın erişilebilirliğini kontrol edin (sunucu/uygulama/ağ). Sayfa yeniden yüklenince alarm otomatik kapanır.");
            return List.of(
                    "Yukarıdaki \"Sorunlu Kaynaklar\" listesindeki kırık link/resim/CSS/JS veya mixed content'i inceleyin",
                    "İlgili içerik/dağıtım ekibiyle kaynağı düzeltin (kaldırılmış varlık, yanlış yol, http→https)",
                    "Site Monitor'ün sonraki kontrolünü bekleyin — sorunlar giderilince alarm kendiliğinden kapanır");
        }
        if (isScripted(m.alertType())) {
            return List.of(
                    "Yukarıdaki \"Başarısız Check'ler\" listesini ve maskeli çıktıyı inceleyin (hangi adım başarısız oldu)",
                    "İlgili servisi/akışı (login, API zinciri, token/claim) kontrol edin; gerekirse izleme servis hesabının kimlik bilgilerini doğrulayın",
                    "Site Monitor'ün sonraki çalıştırmasını bekleyin — sentetik test yeniden geçince alarm kendiliğinden kapanır");
        }
        if (isCert(m.alertType())) {
            return List.of(
                    "CA/PKI ekibinden yeni sertifika talep edin",
                    "Yeni sertifikayı ilgili sunuculara ve sistemlere dağıtın",
                    "Site Monitor'de zincir ve geçerlilik doğrulamasını izleyin — yenilenen sertifika tespit edilince alarm kendiliğinden kapanır");
        }
        return List.of("Hedefin erişilebilirliğini kontrol edin. Sorun giderilince alarm otomatik kapanır.");
    }

    /** ≤7 gün kaldıysa aksiyon listesinin başındaki uyarı satırı; değilse null. */
    private String urgentLine(AlertMail m, String expiryIso) {
        if (m.daysRemaining() == null || m.daysRemaining() > 7) return null;
        return "Bugün aksiyon alın — son tarih " + (expiryIso != null ? formatHuman(expiryIso) : m.daysRemaining() + " gün sonra");
    }

    private static String subsystemLabel(String t) {
        if (isDomain(t)) return "Alan Adı İzleme";
        if ("ACCESSIBILITY".equals(t)) return "Durum İzleme";
        if (isPort(t)) return "Port İzleme";
        if (t != null && t.startsWith("DNS_")) return "DNS İzleme";
        if (isKeyword(t)) return "Keyword İzleme";
        if (isPing(t)) return "Ping İzleme";
        if (isPage(t)) return "Sayfa Bütünlüğü İzleme";
        if (isPageSpeed(t)) return "Sayfa Hızı İzleme";
        if (isScripted(t)) return "Sentetik İzleme";
        if (isHttp(t)) return "HTTP/Website İzleme";
        if (isDbQuery(t)) return "Veritabanı Sorgu İzleme";
        return "Sertifika İzleme";
    }

    // ── Envanter bölümleri (yalnız sertifika alarmlarında, ctx doluysa) ───────

    /** Değişiklik açıklamasında gösterilecek en fazla satır ve karakter — Gmail 102KB üstünü kırpar. */
    private static final int DESC_MAX_LINES = 12;
    private static final int DESC_MAX_CHARS = 1200;
    private static final String DESC_TRUNC = "… tamamı için Site Monitor'de görüntüleyin";

    /** ctx'teki {@code inv_ops} listesi (yalnız "Evet" olan operasyonel bayrakların etiketleri). */
    private static List<String> opsLabels(Map<String, Object> ctx) {
        if (ctx == null || !(ctx.get("inv_ops") instanceof List<?> raw)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null && !o.toString().isBlank()) out.add(o.toString());
        }
        return out;
    }

    /**
     * SERTİFİKA KAYNAĞI — yalnız elle yüklenen sertifika (2026-10-06): sertifika sunucudan değil yüklenen DOSYADAN
     * izleniyor; yenilemek yeni sürüm yüklemektir. Bağlam anahtarı yoksa (ağ kaydı) kart hiç eklenmez.
     */
    private static void manualSourceSection(MailDoc d, AlertMail m) {
        String src = m.ctx() == null ? null : strCtx(m.ctx(), EscalationService.CTX_MANUAL_SOURCE);
        if (src == null) return;
        d.card("Sertifika Kaynağı", null, MailKit.paragraph(esc(src)), src);
    }

    /**
     * OPERASYONEL BİLGİLER — envanterde "Evet" işaretli bayraklar, satır sarabilen çipler.
     * Çipler {@code td bgcolor} ile boyanır (Outlook kuralı). Veri yoksa kart hiç eklenmez.
     */
    private static void opsSection(MailDoc d, AlertMail m) {
        List<String> labels = opsLabels(m.ctx());
        if (labels.isEmpty()) return;
        d.card("Operasyonel Bilgiler", null, MailKit.chips(labels), String.join(", ", labels));
    }

    /** ctx'teki {@code inv_contacts} haritasi (etiket → deger, yalniz DOLU alanlar, ekleme sirali). */
    private static Map<String, String> contactMap(Map<String, Object> ctx) {
        if (ctx == null || !(ctx.get("inv_contacts") instanceof Map<?, ?> raw)) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String v = e.getValue().toString().trim();
            if (!v.isBlank()) out.put(e.getKey().toString(), v);
        }
        return out;
    }

    /**
     * SORUMLU EKIPLER — sertifikayi kimin yenileyecegi: Servis Yonetimi / Uygulama Gelistirme /
     * IISAdmin / WAFAdmin.
     *
     * <p><b>Neden 4 sutunlu tablo DEGIL:</b> kurumsal bir e-posta adresi dar sutunda iki-uc satira
     * kirilir, Outlook'ta sutun genislikleri oynar. Etiket/deger satiri (telefonda alt alta) hem dar
     * ekranda hem Outlook'ta guvenli. Yalniz dolu alanlar basilir; hicbiri dolu degilse kart HIC eklenmez.
     */
    private static void contactsSection(MailDoc d, AlertMail m) {
        Map<String, String> contacts = contactMap(m.ctx());
        if (contacts.isEmpty()) return;
        List<Row> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : contacts.entrySet()) {
            rows.add(new Row(e.getKey(), linkifyEmails(e.getValue()), e.getValue()));
            text.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        d.card("Sorumlu Ekipler", null, MailKit.keyValue(rows), text.toString());
    }

    /** Serbest metin icindeki e-posta belirteci — kasten GEVSEK; amac linklemek, dogrulamak degil. */
    private static final java.util.regex.Pattern EMAIL_IN_TEXT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * Degerdeki e-posta benzeri belirtecleri {@code mailto:} baglantisina cevirir; geri kalan her
     * sey {@code esc} ile kacirilir. Deger serbest metindir ("Ad Soyad - ad.soyad@example.com"),
     * bu yuzden tamamini link yapmak yerine yalniz adres parcasi linklenir.
     */
    static String linkifyEmails(String raw) {
        java.util.regex.Matcher mt = EMAIL_IN_TEXT.matcher(raw);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (mt.find()) {
            out.append(esc(raw.substring(last, mt.start())));
            String addr = mt.group();
            out.append("<a href=\"mailto:").append(esc(addr)).append("\" style=\"color:").append(MailTokens.PRIMARY)
               .append(";text-decoration:underline\">").append(esc(addr)).append("</a>");
            last = mt.end();
        }
        out.append(esc(raw.substring(last)));
        return out.toString();
    }

    /**
     * DEĞİŞİKLİK AÇIKLAMASI — takımın envantere yazdığı yenileme süreci.
     * Metin uygulamada Markdown render edilir ({@code InventoryDetails.jsx}); e-postada BİLİNÇLİ olarak
     * yorumlanmaz: kaçırılmış düz metin + {@code <br>} en güvenli (enjeksiyon yok, Outlook'ta kırılmaz)
     * ve zaten "1." "2." diye yazılan numaralandırma görsel olarak aynı okunur.
     */
    private static void changeDescSection(MailDoc d, AlertMail m) {
        String desc = m.ctx() == null ? null : strCtx(m.ctx(), "inv_change_desc");
        if (desc == null || desc.isBlank()) return;
        List<String> lines = descLines(desc);
        if (lines.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) body.append("<br>");
            body.append(esc(lines.get(i)));
        }
        d.card("Değişiklik Açıklaması", null, MailKit.paragraph(body.toString()), String.join("\n", lines));
    }

    /** Boş satırları atar, satır ve karakter tavanını uygular; kırpıldıysa son satır uyarıdır. */
    static List<String> descLines(String desc) {
        List<String> out = new ArrayList<>();
        int chars = 0;
        boolean truncated = false;
        for (String raw : desc.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (out.size() >= DESC_MAX_LINES || chars + line.length() > DESC_MAX_CHARS) { truncated = true; break; }
            out.add(line);
            chars += line.length();
        }
        if (truncated) out.add(DESC_TRUNC);
        return out;
    }

    // ── Küçük render yardımcıları ────────────────────────────────────────────

    /** EPP kodları — her kod kendi satırında hap + kısa Türkçe açıklama (Outlook-güvenli iç tablo). */
    private static String eppPills(String csv) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">");
        for (String p : csv.split(",")) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            String desc = EPP_TR.get(normEppKey(v));
            sb.append("<tr><td valign=\"top\" style=\"padding:2px 8px 2px 0\">").append(MailKit.pill(v)).append("</td>")
              .append("<td valign=\"top\" style=\"padding:3px 0;font-size:13px;line-height:18px;color:").append(MailTokens.MUTED).append("\">")
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

    /** CANLI base-url (sondaki bölü işaretleri atılmış); {@code appBaseUrl} yalnız fallback. */
    private String liveBaseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        return (url == null || url.isBlank()) ? "" : url.replaceAll("/+$", "");
    }

    // ── Veritabanı sorgu izlemesi (11. tür, 2026-10-31) — alarm postası bölümleri ─────────────────────────────────────
    //
    // Kullanıcı isteği: "ne bekleniyordu, ne geldi, ne çalıştı, sorgu ne zaman çalıştı, kaç satır döndü, satırlar kolon
    // bazlı tablo". Satırlar YALNIZ gönderilen postanın geçici bağlamında gelir (DbQueryAlertRowsService.CTX_ROWS);
    // bildirim günlüğüne yazılan kopyada yerine yalnız satır sayısı vardır (CTX_ROWS_WITHHELD).

    /**
     * Postadaki satır tablosunun BAYT bütçesi (yaklaşık) — Gmail 102 KB üstünü kırpar; aşan satırlar "+N satır daha" olur.
     * Hücre metni + hücre başına HTML yükü ({@link #DBQUERY_CELL_OVERHEAD}) sayılır; telefon kart kopyası yalnız küçük
     * tablolarda üretilir (kopya içeriği ikiye katlar).
     */
    static final int DBQUERY_ROWS_CHAR_BUDGET = 40_000;
    /** MailKit veri tablosunda bir hücrenin satır içi stil / etiket yükü (ölçülmüş, yuvarlanmış). */
    static final int DBQUERY_CELL_OVERHEAD = 260;
    /** Telefon kart kopyası üretilen en büyük tablo: kolon ve satır sayısı. */
    static final int DBQUERY_STACK_MAX_COLS = 6, DBQUERY_STACK_MAX_ROWS = 10;
    /** Sorgu kod bloğu tavanı. */
    static final int DBQUERY_SQL_MAX = 4000;

    /** Bağlamdaki alarm anı satırları (yoksa null). */
    static com.sitemonitor.service.dbquery.DbQueryAlertRowsService.Rows dbQueryRows(Map<String, Object> c) {
        Object v = c == null ? null : c.get(com.sitemonitor.service.dbquery.DbQueryAlertRowsService.CTX_ROWS);
        return v instanceof com.sitemonitor.service.dbquery.DbQueryAlertRowsService.Rows r && !r.isEmpty() ? r : null;
    }

    /** "Ad · tür · sunucu:port/veritabanı" — kullanıcı adı / parola ASLA yazılmaz (bağlamda da yoktur). */
    static String dbQueryConnectionText(Map<String, Object> c) {
        List<String> parts = new ArrayList<>();
        String name = strCtx(c, "profile_name");
        if (name != null) parts.add(name);
        String type = com.sitemonitor.service.dbquery.DbQueryAlarmText.dbTypeLabel(strCtx(c, "db_type"));
        if (type != null) parts.add(type);
        String target = strCtx(c, "dbquery_target");
        if (target != null) parts.add(target);
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** "1.250" ya da sınırlıysa "≥ 1000"; yoksa null. */
    static String dbQueryRowCountText(Map<String, Object> c) {
        String n = strCtx(c, "row_count");
        if (n == null) return null;
        return (boolCtx(c, "row_count_capped") ? "≥ " : "") + n;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dbQueryRuleResults(Map<String, Object> c) {
        Object v = c == null ? null : c.get("rule_results");
        if (!(v instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** Kurallar tablosu (Kural · Beklenen · Gelen · Durum) + sorgu kod bloğu + sonuç satırları. */
    private static void dbQuerySections(MailDoc d, Map<String, Object> c) {
        List<Map<String, Object>> rules = dbQueryRuleResults(c);
        if (!rules.isEmpty()) {
            List<MailKit.Col> cols = List.of(MailKit.Col.of("Kural"), MailKit.Col.of("Beklenen"),
                    MailKit.Col.of("Gelen"), MailKit.Col.nw("Durum"));
            List<List<MailKit.Cell>> rows = new ArrayList<>();
            for (Map<String, Object> r : rules) {
                boolean violated = Boolean.FALSE.equals(r.get("passed")) && !Boolean.FALSE.equals(r.get("evaluated"));
                String state = com.sitemonitor.service.dbquery.DbQueryAlarmText.ruleStateText(r);
                String color = violated ? ("CRITICAL".equals(String.valueOf(r.get("level"))) ? Tone.DESTRUCTIVE.text : Tone.WARNING.text)
                        : Boolean.FALSE.equals(r.get("evaluated")) ? MailTokens.MUTED : MailTokens.SUCCESS;
                String label = r.get("label") != null && !String.valueOf(r.get("label")).isBlank() ? String.valueOf(r.get("label"))
                        : com.sitemonitor.service.dbquery.DbQueryAlarmText.kindLabel(r.get("kind") == null ? null : String.valueOf(r.get("kind")));
                rows.add(List.of(MailKit.Cell.of(label), MailKit.Cell.of(nzv(r.get("expected"))),
                        MailKit.Cell.of(nzv(r.get("actual")), violated ? color : null, violated),
                        MailKit.Cell.of(state, color, violated)));
            }
            d.heading("Kurallar");
            d.table(cols, rows);
        }
        String sql = strCtx(c, "dbquery_sql");
        if (sql != null) d.pre("Çalıştırılan Sorgu", sql.length() > DBQUERY_SQL_MAX ? sql.substring(0, DBQUERY_SQL_MAX) + "…" : sql);
        dbQueryRowsSection(d, c);
    }

    private static String nzv(Object o) {
        if (o == null) return "—";
        String s = String.valueOf(o);
        return s.isBlank() || "null".equals(s) ? "—" : s;
    }

    /** Sonuç satırları tablosu — gönderimde satırlar, günlük kopyasında yalnız sayı; kapalıysa / yoksa açıklama. */
    private static void dbQueryRowsSection(MailDoc d, Map<String, Object> c) {
        com.sitemonitor.service.dbquery.DbQueryAlertRowsService.Rows rows = dbQueryRows(c);
        if (rows != null) {
            RowsView v = rowsView(rows);
            d.heading("Sorgu Sonucu (alarm anı)", v.caption());
            d.table(v.cols(), v.cells(), v.stack());
            if (v.more() != null) d.note(v.more());
            return;
        }
        Object withheld = c == null ? null : c.get(com.sitemonitor.service.dbquery.DbQueryAlertRowsService.CTX_ROWS_WITHHELD);
        if (withheld != null) {
            d.note("Sorgu sonucu satırları (" + withheld + " satır) alıcılara gönderilen postadadır; bildirim günlüğündeki bu "
                    + "kopyada saklanmaz. Alarm ayrıntısında \"Alarm anındaki sorgu sonucu\" bölümünden görülebilir.");
        } else if (boolCtx(c, "dbquery_rows_off")) {
            d.note("Bu izlemede sorgu sonucu satırları maile eklenmiyor (izleme ayarı).");
        }
    }

    /** Tablo görünümü: kolonlar, hücreler (bütçe içinde), başlık açıklaması ve "+N satır / +M kolon daha" notu. */
    private record RowsView(List<MailKit.Col> cols, List<List<MailKit.Cell>> cells, String caption, String more, boolean stack) {}

    private static RowsView rowsView(com.sitemonitor.service.dbquery.DbQueryAlertRowsService.Rows rows) {
        java.util.Set<String> masked = new java.util.HashSet<>();
        if (rows.masked() != null) for (String m : rows.masked()) if (m != null) masked.add(m.toLowerCase(Locale.ROOT));
        List<MailKit.Col> cols = new ArrayList<>();
        for (String name : rows.columns()) {
            String label = name == null || name.isBlank() ? "—" : name;
            cols.add(masked.contains(label.toLowerCase(Locale.ROOT)) ? MailKit.Col.of(label + " (maskeli)") : MailKit.Col.of(label));
        }
        List<List<MailKit.Cell>> cells = new ArrayList<>();
        int budget = DBQUERY_ROWS_CHAR_BUDGET, shown = 0, used = 0;
        for (List<String> r : rows.rows()) {
            int cost = cols.size() * DBQUERY_CELL_OVERHEAD;
            List<MailKit.Cell> line = new ArrayList<>(cols.size());
            for (int i = 0; i < cols.size(); i++) {
                String v = r != null && i < r.size() ? r.get(i) : null;
                cost += v == null ? 4 : v.length() + 1;
                line.add(v == null ? MailKit.Cell.of("NULL", MailTokens.MUTED, false) : MailKit.Cell.of(v));
            }
            if (shown > 0 && cost > budget) break;
            budget -= cost;
            used += cost;
            cells.add(line);
            shown++;
        }
        // Telefon kart kopyası içeriği ikiye katlar → yalnız küçük tabloda ve kopya bütçeye sığıyorsa.
        boolean stack = cols.size() <= DBQUERY_STACK_MAX_COLS && shown <= DBQUERY_STACK_MAX_ROWS
                && used * 2L <= DBQUERY_ROWS_CHAR_BUDGET;
        int total = rows.rowCount() != null ? rows.rowCount() : rows.rows().size();
        int moreRows = Math.max(0, total - shown);
        String caption = (rows.rowCountCapped() ? "En az " : "") + total + " satır döndü"
                + (shown < total ? " — ilk " + shown + " satır gösteriliyor" : "")
                + (masked.isEmpty() ? "" : ". Maskelenen kolonların değeri " + com.sitemonitor.service.dbquery.DbQueryAlertRowsService.MASK
                    + " olarak yazılır");
        String more = null;
        if (moreRows > 0 || rows.truncated()) {
            List<String> parts = new ArrayList<>();
            if (moreRows > 0) parts.add("+" + (rows.rowCountCapped() ? "en az " : "") + moreRows + " satır daha");
            if (rows.truncated()) parts.add("bazı kolonlar / uzun hücreler kısaltıldı");
            more = String.join(" · ", parts) + " — tamamı veritabanında; Site Monitor sonuç verisini saklamaz.";
        }
        return new RowsView(cols, cells, caption, more, stack);
    }

    /** Düz metin paritesi: kurallar, sorgu, sonuç satırları (HTML ile aynı bilgi). */
    static String dbQueryText(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> rules = dbQueryRuleResults(c);
        if (!rules.isEmpty()) {
            sb.append("\nKurallar:\n");
            for (Map<String, Object> r : rules) {
                String label = r.get("label") != null && !String.valueOf(r.get("label")).isBlank() ? String.valueOf(r.get("label"))
                        : com.sitemonitor.service.dbquery.DbQueryAlarmText.kindLabel(r.get("kind") == null ? null : String.valueOf(r.get("kind")));
                sb.append("- ").append(label).append(" · Beklenen: ").append(nzv(r.get("expected")))
                  .append(" · Gelen: ").append(nzv(r.get("actual")))
                  .append(" · Durum: ").append(com.sitemonitor.service.dbquery.DbQueryAlarmText.ruleStateText(r)).append('\n');
            }
        }
        String sql = strCtx(c, "dbquery_sql");
        if (sql != null) sb.append("\nÇalıştırılan Sorgu:\n").append(sql.length() > DBQUERY_SQL_MAX ? sql.substring(0, DBQUERY_SQL_MAX) + "…" : sql).append('\n');
        com.sitemonitor.service.dbquery.DbQueryAlertRowsService.Rows rows = dbQueryRows(c);
        if (rows != null) {
            RowsView v = rowsView(rows);
            sb.append("\nSorgu Sonucu (alarm anı) — ").append(v.caption()).append(":\n");
            List<String> header = new ArrayList<>();
            for (MailKit.Col col : v.cols()) header.add(col.label());
            sb.append(String.join(" | ", header)).append('\n');
            for (List<MailKit.Cell> line : v.cells()) {
                List<String> vals = new ArrayList<>();
                for (MailKit.Cell cell : line) vals.add(cell.text());
                sb.append(String.join(" | ", vals)).append('\n');
            }
            if (v.more() != null) sb.append(v.more()).append('\n');
        } else {
            Object withheld = c == null ? null : c.get(com.sitemonitor.service.dbquery.DbQueryAlertRowsService.CTX_ROWS_WITHHELD);
            if (withheld != null) sb.append("\nSorgu sonucu satırları (").append(withheld)
                    .append(" satır) gönderilen postadadır; bu kopyada saklanmaz.\n");
            else if (boolCtx(c, "dbquery_rows_off")) sb.append("\nBu izlemede sorgu sonucu satırları maile eklenmiyor.\n");
        }
        return sb.toString();
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
            case "TIMEOUT", "MIXED_CONTENT" -> Tone.WARNING.text;
            case "SLOW" -> Tone.INFO.text;
            case "BLOCKED" -> MailTokens.MUTED;
            default -> Tone.DESTRUCTIVE.text;   // BROKEN
        };
    }
    private static String truncUrl(String url) {
        if (url == null) return "";
        return url.length() > 100 ? url.substring(0, 99) + "…" : url;
    }

    /** Sorunlu kaynaklar — tür etiketi + kısaltılmış URL (+HTTP), ≤10 satır; URL kırılabilir (taşma yok). */
    private static String pageIssuesHtml(String tsv, Integer total) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">");
        int shown = 0;
        for (String line : tsv.split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            String type = p.length > 0 ? p[0] : "";
            String url  = p.length > 1 ? p[1] : "";
            String http = p.length > 2 ? p[2] : "";
            sb.append("<tr>")
              .append("<td valign=\"top\" style=\"padding:4px 10px 4px 0;font-size:12px;line-height:18px;font-weight:600;color:")
              .append(pageIssueTypeColor(type)).append(";white-space:nowrap\">").append(esc(pageIssueTypeLabel(type))).append("</td>")
              .append("<td valign=\"top\" class=\"mono\" style=\"padding:4px 0;font-size:12px;line-height:18px;color:").append(MailTokens.FG)
              .append(";font-family:").append(MailTokens.MONO).append(";word-break:break-all;overflow-wrap:anywhere\">").append(esc(truncUrl(url)));
            if (!http.isBlank()) sb.append("<span style=\"color:").append(MailTokens.MUTED).append("\"> · HTTP ").append(esc(http)).append("</span>");
            sb.append("</td></tr>");
            shown++;
        }
        if (total != null && total > shown) {
            sb.append("<tr><td colspan=\"2\" style=\"padding:6px 0 0;font-size:12px;line-height:18px;color:").append(MailTokens.MUTED)
              .append("\">… ve ").append(total - shown).append(" kaynak daha (toplam ").append(total).append(")</td></tr>");
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

    /** Tek kaçış kaynağı {@link MailKit#esc} (beş karakter). */
    static String esc(String s) {
        return MailKit.esc(s);
    }
    private static String urlenc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Uyarı bağlantısının sorgu dizesi: {@code ?tab=<sekme>[&domain=<alan>][&open=cert]}. Yalnız SERTİFİKA alarmında
     * ({@link EscalationService#CERT_ALERT_TYPES}) {@code open=cert} Pano'da süzmekle kalmaz, alanın SERTİFİKA
     * PENCERESİNİ açar (frontend {@code hooks/useCertDeepLink.js}; tek seferlik) — 2026-09-28.
     *
     * <p>HTTP ailesi ({@code HTTP_*}) {@link #tabFor}'da ayrı dal olmadığından panoya düşüyor, bağlantı URL'yi alan adı
     * sanıp panoyu süzüyordu (regresyon taraması 2026-09-28). Bağlantı artık HTTP sekmesinde izlemenin kendisini açar
     * ({@code &monitor=<id>}, bağlamda varsa). 2026-10-22 (ALERTS-13): şablon içeriği de ayrıldı — {@code tabFor} HTTP için
     * {@code "http"} döner, {@code isCert} HTTP türlerinde false (sertifika satırları / adımları / envanter zenginleştirmesi yok).
     */
    static String alertQuery(String alertType, String domain) { return alertQuery(alertType, domain, null); }

    static String alertQuery(String alertType, String domain, Map<String, Object> ctx) {
        if (alertType != null && alertType.startsWith("HTTP_")) {
            Long id = monitorIdOf(ctx);
            return "/?tab=http" + (id != null ? "&monitor=" + id : "");
        }
        if (isDbQuery(alertType)) {   // veritabanı sorgusu: izlemenin kendisi (alan adı yok — anahtar izleme adıdır)
            Long id = monitorIdOf(ctx);
            return "/?tab=dbquery" + (id != null ? "&monitor=" + id : "");
        }
        String tab = tabFor(alertType);
        if (domain == null) return "/?tab=" + tab;
        boolean certAlert = alertType != null && EscalationService.CERT_ALERT_TYPES.contains(alertType);
        return "/?tab=" + tab + "&domain=" + urlenc(domain) + (certAlert ? "&open=cert" : "");
    }

    private static Long monitorIdOf(Map<String, Object> ctx) {
        Object v = ctx == null ? null : ctx.get("monitor_id");
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            String t = String.valueOf(v).trim();
            if (t.matches("[0-9]{1,18}")) return Long.parseLong(t);
        }
        return null;
    }
}
