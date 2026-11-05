package com.sitemonitor.service.page.scan.deep;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code Set-Cookie} başlığından çerez OLGUSU (2026-11, kaynak analizi algoritma 3) — SAF. Çerezin DEĞERİ hiçbir yerde
 * tutulmaz: ayrıştırma adı ve öznitelikleri alır, değeri atar. JavaScript'in yazdığı çerezler görülmez (sunucu yanıtları).
 */
public final class CookieFacts {

    /** Çerez ömrü bu günü aşarsa {@code long_lived} (Chrome üst sınırı 400 gün). */
    static final long LONG_LIVED_DAYS = 400;
    static final int MAX_HEADER = 4096;

    public static final String NOT_SECURE = "not_secure", NO_HTTP_ONLY = "no_http_only", NO_SAME_SITE = "no_same_site",
            NONE_WITHOUT_SECURE = "none_without_secure", LONG_LIVED = "long_lived";

    /**
     * @param sameSite {@code Strict | Lax | None} (yazıldığı gibi büyük harfle başlar); yoksa null
     * @param maxAgeS  ömür (sn): Max-Age ya da Expires − şimdi; oturum çerezi null
     */
    public record Cookie(String name, String host, boolean secure, boolean httpOnly, String sameSite, Long maxAgeS) {}

    private CookieFacts() {}

    /** Tek {@code Set-Cookie} değeri; adı olmayan / bozuk başlık null. Değer ATILIR. */
    public static Cookie parse(String header, String host, long nowMs) {
        if (header == null || header.isBlank()) return null;
        String h = header.length() > MAX_HEADER ? header.substring(0, MAX_HEADER) : header;
        String[] parts = h.split(";", 40);
        String first = parts[0];
        int eq = first.indexOf('=');
        if (eq <= 0) return null;
        String name = first.substring(0, eq).trim();
        if (name.isEmpty()) return null;
        boolean secure = false, httpOnly = false;
        String sameSite = null;
        Long maxAge = null, expiresIn = null;
        for (int i = 1; i < parts.length; i++) {
            String a = parts[i].trim();
            int e = a.indexOf('=');
            String k = (e < 0 ? a : a.substring(0, e)).trim().toLowerCase(Locale.ROOT);
            String v = e < 0 ? "" : a.substring(e + 1).trim();
            switch (k) {
                case "secure" -> secure = true;
                case "httponly" -> httpOnly = true;
                case "samesite" -> sameSite = normalSameSite(v);
                case "max-age" -> {
                    try { maxAge = Long.parseLong(v); } catch (NumberFormatException ignore) { /* bozuk */ }
                }
                case "expires" -> {
                    try {
                        long exp = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                        expiresIn = (exp - nowMs) / 1000;
                    } catch (RuntimeException ignore) { /* bozuk tarih */ }
                }
                default -> { }
            }
        }
        Long life = maxAge != null ? maxAge : expiresIn;   // Max-Age Expires'tan önceliklidir
        return new Cookie(DeepUrls.clip(name, 100), host, secure, httpOnly, sameSite, life);
    }

    private static String normalSameSite(String v) {
        String l = v.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "strict" -> "Strict";
            case "lax" -> "Lax";
            case "none" -> "None";
            default -> v.isEmpty() ? null : DeepUrls.clip(v, 20);
        };
    }

    /**
     * Sorunlar: https sayfada Secure yok → {@code not_secure}; HttpOnly yok → {@code no_http_only}; SameSite yok →
     * {@code no_same_site}; SameSite=None + Secure yok → {@code none_without_secure} (tarayıcı reddeder); ömür >
     * {@value #LONG_LIVED_DAYS} gün → {@code long_lived}.
     */
    public static List<String> issues(Cookie c, boolean httpsPage) {
        List<String> out = new ArrayList<>();
        if (httpsPage && !c.secure()) out.add(NOT_SECURE);
        if (!c.httpOnly()) out.add(NO_HTTP_ONLY);
        if (c.sameSite() == null) out.add(NO_SAME_SITE);
        if ("None".equals(c.sameSite()) && !c.secure()) out.add(NONE_WITHOUT_SECURE);
        if (c.maxAgeS() != null && c.maxAgeS() > LONG_LIVED_DAYS * 86_400L) out.add(LONG_LIVED);
        return out;
    }

    /** Oturum / kimlik çerezine benziyor mu (adından): sess, sid, auth, token, login. */
    public static boolean sessionLike(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("sess") || n.contains("sid") || n.contains("auth") || n.contains("token") || n.contains("login");
    }

    /**
     * Güvensiz sayılır mı (öneri {@code INSECURE_COOKIES}): Secure yok / SameSite=None+Secure yok, ya da oturum benzeri
     * çerezde HttpOnly yok.
     */
    public static boolean insecure(List<String> issues, String name) {
        return issues.contains(NOT_SECURE) || issues.contains(NONE_WITHOUT_SECURE)
                || (issues.contains(NO_HTTP_ONLY) && sessionLike(name));
    }
}
