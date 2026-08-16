package com.sitemonitor.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL alan izleme türleri (Sayfa Bütünlüğü / HTTP / Kelime) için giriş normalizasyonu.
 *
 * <p><b>Neden var:</b> Kullanıcı URL alanına şemasız bir adres yazdığında ({@code www.axess.com.tr})
 * hiçbir katman bunu düzeltmiyordu. {@code URI.create("www.axess.com.tr")} istisna atmaz — RFC 3986'ya
 * göre bu geçerli bir <i>relative</i> referanstır — ama {@code getHost()} {@code null} döner. Sonuçta
 * kontrol motoru isteği hiç atamaz ve bu <b>yapılandırma hatası kesinti gibi</b> raporlanıp takıma
 * KRİTİK alarm e-postası gönderilirdi (2026-08-04, www.axess.com.tr).
 *
 * <p>Saf string işlemi — {@code URI}/{@code URL} sınıfları <b>bilerek</b> kullanılmaz: Kelime izlemesi
 * URL'de {@code {timestamp}} yer tutucusuna izin veriyor (KeywordCheckerService.applyTimestamp) ve
 * {@code URI.create} bu değerde {@code IllegalArgumentException} atar.
 */
public final class MonitorUrls {

    /** Kontrol motorlarının "bu URL kontrol edilemez" hata mesajı (tek kaynak). */
    public static final String CONFIG_ERROR_MSG =
            "yapılandırma hatası: URL'de geçerli bir host yok (şema eksik veya bozuk)";

    private static final Pattern SCHEME_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*://");

    private MonitorUrls() {}

    /**
     * Şemasız girdiye {@code https://} ekler. Mevcut şema (http/https/ftp/…) <b>asla</b> değiştirilmez —
     * kullanıcının bilinçli {@code http://} tercihi korunur. {@code //x.com} (protokol-relatif) → {@code https://x.com}.
     * null/boş girdi olduğu gibi (trim'lenmiş) döner.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return t;
        if (SCHEME_RE.matcher(t).find()) return t;      // şema var → dokunma
        if (t.startsWith("//")) return "https:" + t;    // protokol-relatif
        return "https://" + t;
    }

    /**
     * http(s) şemalı URL'den çıplak host; şema yoksa veya host boşsa {@code null}
     * (PublicSuffixService.extractHost ile aynı ayıklama, ek olarak şema zorunluluğu).
     */
    public static String hostOrNull(String url) {
        if (url == null) return null;
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return null;                                     // şemasız → kontrol edilemez
        String proto = s.substring(0, scheme).toLowerCase(Locale.ROOT);
        if (!"http".equals(proto) && !"https".equals(proto)) return null;  // mailto:, ftp:, file: … kontrol edilemez
        s = s.substring(scheme + 3);
        int slash = s.indexOf('/');   if (slash >= 0) s = s.substring(0, slash);
        int q = s.indexOf('?');       if (q >= 0) s = s.substring(0, q);
        int hash = s.indexOf('#');    if (hash >= 0) s = s.substring(0, hash);
        int at = s.indexOf('@');      if (at >= 0) s = s.substring(at + 1);
        if (s.startsWith("[")) {                                          // IPv6 literal: [::1]:8080
            int close = s.indexOf(']');
            s = close > 0 ? s.substring(0, close + 1) : s;
        } else {
            int colon = s.indexOf(':'); if (colon >= 0) s = s.substring(0, colon);
        }
        s = s.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        return s.isBlank() ? null : s;
    }

    /**
     * URL'de istek atmayı İMKÂNSIZ kılan boşluk/kontrol karakteri var mı?
     *
     * <p>Saha vakası (2026-06-22 → 2026-08-16, iki ay): bir Kelime izlemesinin URL'i
     * {@code "http://localhost:8080/health- Orjinal"} olarak kaydedildi. {@link #isCheckable}
     * yalnız HOST'a baktığı için ("localhost" geçerli) doğrulamadan geçti; her kontrol ise
     * {@code Illegal character in path at index 29} ile düştü. Toplamda 12 000+ başarısız kontrol
     * üretti, sıradan bir "DOWN" gibi göründü ve hiç fark edilmedi — üstelik alarm bastırma
     * oranını da şişirdi ({@code MonitoringOutageService.isOutageClass}).
     *
     * <p>Boşluk BİLEREK düzeltilmez (kırpma/kodlama yapılmaz): "%20" ile kaçırmak sessizce BAŞKA
     * bir hedefe istek atmak demektir. Kullanıcıya söylenir, o düzeltir.
     *
     * <p>{@code {timestamp}} yer tutucusu (Kelime izlemesi) boşluk içermez → bu denetimden etkilenmez.
     */
    public static boolean hasIllegalWhitespace(String url) {
        if (url == null) return false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    /** Kontrol motoru bu URL'e istek atabilir mi (http/https + host var + ayrıştırılabilir)? */
    public static boolean isCheckable(String url) {
        return hostOrNull(url) != null && !hasIllegalWhitespace(url == null ? null : url.trim());
    }
}
