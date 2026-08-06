package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E-posta marka logosu — TEK yardımcı (BRAND.md §5.1, Rev. 2026-08-06 Outlook saha bulgusu).
 *
 * Bağlayıcı kurallar:
 *  - Logo mail gövdesinde YALNIZ kart başlık çubuğunda, "Site Monitor" yazısının solunda,
 *    yazı satırıyla eş boyda (32px) durur → {@link #headerLockup()}. Serbest yüzen header/banner
 *    logosu YASAK (Outlook Word motoru CSS genişliğini yok sayar, PNG'yi doğal boyutta basar).
 *  - {@code width}/{@code height} HTML ATTRIBUTE olarak zorunlu; inline style ikincil destek.
 *  - Logo daima CID inline attachment (multipart/related): dış URL kurumsal gateway'lerde
 *    bloklanır, base64 img Gmail'de desteklenmez.
 *  - Varyant semantiği (BRAND.md §3): CRITICAL → critical; diğer TÜM alarm seviyeleri → warning;
 *    çözülme/rapor/hatırlatma/test → ok. muted mailde kullanılmaz.
 */
@Slf4j
public final class BrandMailAssets {

    public static final String CID = "brand-logo";
    /** Bilinen alarm severity'leri — açık eşleme; listede olmayan değer WARN loglanır (sessiz düşme yok). */
    private static final Set<String> KNOWN_LEVELS = Set.of("CRITICAL", "HIGH", "WARNING", "MEDIUM", "LOW", "INFO");
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private BrandMailAssets() {}

    /** Alarm severity → logo varyantı. Yalnız CRITICAL kırmızı yaprak; kalan tüm seviyeler amber. */
    public static String variantForLevel(String level) {
        if (level == null) return "warning";
        String l = level.toUpperCase(Locale.ROOT);
        if (l.contains("CRITICAL")) return "critical";
        if (!KNOWN_LEVELS.contains(l)) {
            log.warn("Bilinmeyen alarm severity '{}' — logo varyantı 'warning' varsayıldı (BrandMailAssets eşlemesine ekleyin)", level);
        }
        return "warning";
    }

    /** Classpath'ten varyant baytları (cache'li). Dosya yoksa null — mail logosuz gider, gönderim düşmez. */
    public static byte[] logoBytes(String variant) {
        return CACHE.computeIfAbsent(variant, v -> {
            try {
                return new ClassPathResource("email-assets/email-" + v + ".png").getContentAsByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }).length == 0 ? null : CACHE.get(variant);
    }

    /**
     * Kart başlık çubuğu logo lockup'ı: {@code [32px logo] Site Monitor} — düşman-istemci-güvenli.
     * Saha bulgusu #3 (2026-08-06): forward zincirleri (Gmail→Outlook) width attribute'unu ve
     * inline img+span dizilimini yeniden yazabiliyor → logo doğal boyutta bastı, yazı alt satıra
     * düştü. Savunma: (1) varlık FİZİKSEL 32px (attribute silinse bile doğal boyut = hedef boyut),
     * (2) dizilim inline değil TABLO HÜCRESİ (hiçbir istemci hücreleri alt alta kıramaz).
     * Mail başına TEK logo kuralı: bu parça yalnız başlık çubuğuna girer.
     */
    public static String headerLockup() {
        return headerLockup("#FFFFFF");
    }

    /** Yazı rengi özelleştirilebilir sürüm (koyu/açık header zeminleri için). */
    public static String headerLockup(String textColor) {
        // DİKKAT: hücrede line-height:0 KULLANMA — Outlook (Word motoru) hücre yüksekliğini satır
        // yüksekliğinden türetir ve görselin üstünü kırpar (saha bulgusu #4). Açık height + valign.
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td width=\"32\" height=\"32\" valign=\"middle\" style=\"width:32px;height:32px\">"
                + "<img src=\"cid:" + CID + "\" width=\"32\" height=\"32\" border=\"0\" alt=\"Site Monitor\" "
                + "style=\"display:block;width:32px;height:32px;max-width:32px;max-height:32px\"></td>"
                + "<td valign=\"middle\" style=\"padding-left:10px;font-size:18px;line-height:32px;font-weight:700;color:" + textColor
                + ";font-family:Segoe UI,Arial,sans-serif;white-space:nowrap\">Site Monitor</td>"
                + "</tr></table>";
    }

    /** setText'ten SONRA çağrılmalı (Spring MimeMessageHelper sırası). HTML cid içermiyorsa eklemez. */
    public static void addInline(MimeMessageHelper helper, String html, String variant) throws jakarta.mail.MessagingException {
        byte[] bytes = logoBytes(variant);
        if (bytes == null || html == null || !html.contains("cid:" + CID)) return;
        helper.addInline(CID, new ByteArrayResource(bytes), "image/png");
    }
}
