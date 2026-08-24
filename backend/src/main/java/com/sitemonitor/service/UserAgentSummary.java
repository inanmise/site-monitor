package com.sitemonitor.service;

/**
 * User-Agent → insan okunur cihaz özeti ({@code os}, {@code browser}, {@code device}).
 *
 * <p><b>Neden sunucuda:</b> özet hem Cihaz Geçmişi ekranında hem denetim tablolarında hem de
 * remember-me satırında kullanılıyor. Tek yerde üretilmezse üç ayrı ayrıştırıcı birbirinden
 * sapar ve aynı tarayıcı iki ekranda iki farklı adla görünür.
 *
 * <p><b>Dış kütüphane YOK</b> — tablo güdümlü, küçük ve testle pinli. Kaynağı uydurulmadı:
 * kural tablosu, üretimde çalışan {@code AuditLogViewer.parseBrowser}'dan taşındı; üzerine iOS
 * tarayıcıları ve {@code device} eklendi.
 *
 * <p><b>SIRA SÖZLEŞMEDİR</b> — UA dizeleri kasten birbirini taklit eder:
 * <ul>
 *   <li>Edge, Chrome'un UA'sını taşır ({@code Edg/}) → Chrome'dan ÖNCE bakılır.</li>
 *   <li>Opera da öyle ({@code OPR/}).</li>
 *   <li>iOS'ta Chrome {@code CriOS/}, Firefox {@code FxiOS/} kullanır ve İKİSİ de "Safari/"
 *       içerir → Safari'den önce bakılmazsa iPhone'daki Chrome "Safari" görünür (taşınan
 *       ayrıştırıcının gerçek kusuruydu, burada düzeltildi).</li>
 *   <li>Safari EN SONDA: Chromium tabanlı her tarayıcı "Safari/" taşır.</li>
 *   <li>Android UA'sı "Linux" içerir → Linux'tan ÖNCE bakılır.</li>
 *   <li>iOS UA'sı "like Mac OS X" içerir → macOS'tan ÖNCE bakılır.</li>
 * </ul>
 *
 * <p>Tanınamayan UA {@code null} alanlarla döner — arayüz o satırı ham UA dökmek yerine genel
 * "Oturum" etiketiyle çizer. Ham UA yalnız satır genişletmesinde/tooltip'te gösterilir.
 */
public final class UserAgentSummary {

    private UserAgentSummary() {}

    /** @param device {@code "mobile"} | {@code "desktop"} | {@code null} (bilinmiyor) */
    public record Summary(String os, String browser, String device) {

        /** Hiçbir şey tanınmadı mı — arayüz genel etiket kullansın. */
        public boolean isUnknown() { return os == null && browser == null; }

        /** Ekranda gösterilen kısa biçim: "Windows · Chrome". Tanınmayan → null. */
        public String label() {
            if (os != null && browser != null) return os + " · " + browser;
            return os != null ? os : browser;   // biri tanındıysa onu göster
        }
    }

    public static final Summary UNKNOWN = new Summary(null, null, null);

    public static Summary of(String ua) {
        if (ua == null || ua.isBlank()) return UNKNOWN;
        return new Summary(os(ua), browser(ua), device(ua));
    }

    /** Kısa etiket ya da null — çağıranların çoğu yalnız bunu ister. */
    public static String labelOf(String ua) {
        return of(ua).label();
    }

    private static String browser(String ua) {
        if (has(ua, "Edg/", "EdgA/", "Edge/"))  return "Edge";      // Chrome'dan ÖNCE
        if (has(ua, "OPR/", "Opera/"))           return "Opera";     // Chrome'dan ÖNCE
        if (has(ua, "CriOS/"))                   return "Chrome";    // iOS Chrome, Safari'den ÖNCE
        if (has(ua, "FxiOS/"))                   return "Firefox";   // iOS Firefox, Safari'den ÖNCE
        if (has(ua, "Chrome/"))                  return "Chrome";
        if (has(ua, "Firefox/"))                 return "Firefox";
        if (has(ua, "Safari/"))                  return "Safari";    // EN SONDA
        return null;
    }

    private static String os(String ua) {
        if (has(ua, "Windows NT"))                       return "Windows";
        if (has(ua, "Android"))                          return "Android";   // "Linux" da içerir
        if (has(ua, "iPhone", "iPad", "iPod"))           return "iOS";       // "Mac OS X" de içerir
        if (has(ua, "Mac OS"))                           return "macOS";
        if (has(ua, "Linux"))                            return "Linux";
        return null;
    }

    private static String device(String ua) {
        if (has(ua, "Android", "iPhone", "iPad", "iPod", "Mobile")) return "mobile";
        if (has(ua, "Windows NT", "Mac OS", "Linux"))               return "desktop";
        return null;
    }

    private static boolean has(String ua, String... needles) {
        for (String n : needles) if (ua.contains(n)) return true;
        return false;
    }
}
