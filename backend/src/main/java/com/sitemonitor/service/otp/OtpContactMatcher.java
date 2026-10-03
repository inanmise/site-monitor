package com.sitemonitor.service.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Kodla giriş isteğinde KİŞİ BİLGİSİ doğrulaması (2026-10-03, kullanıcı isteği: "push ile loginde telefon no, mail ile
 * loginde mail adresi de sorulsun; kullanıcı adı ile eşleşirse kod gönderilsin"). Saf yardımcı — durum, log, DB yok.
 *
 * <h3>Telefon</h3>
 * Kayıtlı taraf {@code AppUser.phone} — AD {@code mobile} özniteliğinden eşitlenen CEP numarası (modelde ayrı bir "mobil"
 * alanı yok; tek telefon alanı zaten cep numarasıdır). İki taraf da AYNI kuralla normalleşir:
 * <ol>
 *   <li>yalnız rakamlar kalır (boşluk, {@code + ( ) - .} vb. atılır; Unicode rakamları — tam genişlik, Arap-Hint —
 *       ASCII'ye çevrilir),</li>
 *   <li>baştaki ülke kodu {@code 0090} ya da {@code 90} (geride en az 10 hane kalıyorsa) ve ardından baştaki tek trunk
 *       {@code 0} düşer,</li>
 *   <li>en az 10 hane kalmalı (yoksa eşleşme YOK), karşılaştırılan SON 10 hanedir.</li>
 * </ol>
 * Böylece "0532 123 45 67", "+90 (532) 123-45-67", "905321234567" ve "5321234567" aynı numaradır.
 *
 * <h3>E-posta</h3>
 * Baştaki/sondaki boşluk kırpılır, büyük/küçük harf duyarsız TAM eşleşme ({@code AppUser.email}).
 *
 * <p>Karşılaştırmalar {@link MessageDigest#isEqual} ile (eşit uzunlukta sabit süreli). Girilen değer hiçbir yere
 * yazılmaz — çağıran yalnız sonucu (eşleşti / eşleşmedi) kullanır.
 */
public final class OtpContactMatcher {

    /** Karşılaştırılan hane sayısı (ulusal numara). */
    static final int PHONE_DIGITS = 10;
    /** Girdinin işlenecek en uzun hâli — daha uzunu doğrudan eşleşmez (makul bir telefon/e-posta bu sınırın çok altında). */
    static final int MAX_PHONE_INPUT = 64;
    static final int MAX_EMAIL_INPUT = 254;

    private OtpContactMatcher() { }

    /**
     * Telefonu karşılaştırılabilir 10 haneye indirger; yetersizse {@code null}.
     * Örnek: {@code "+90 (532) 123-45-67"} → {@code "5321234567"}; {@code "0532 123"} → {@code null}.
     */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.length() > MAX_PHONE_INPUT) return null;
        StringBuilder d = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) {
                int v = Character.digit(c, 10);
                if (v >= 0) d.append((char) ('0' + v));
            }
        }
        String s = d.toString();
        if (s.startsWith("0090")) s = s.substring(4);
        else if (s.startsWith("90") && s.length() >= PHONE_DIGITS + 2) s = s.substring(2);
        if (s.startsWith("0")) s = s.substring(1);
        if (s.length() < PHONE_DIGITS) return null;
        return s.substring(s.length() - PHONE_DIGITS);
    }

    /** Kayıtlı telefon karşılaştırmaya ELVERİŞLİ mi (en az 10 hane). */
    public static boolean usablePhone(String registered) {
        return normalizePhone(registered) != null;
    }

    /** Girilen telefon kayıtlı telefonla aynı mı — iki taraf da normalleşir; biri yetersizse false. */
    public static boolean phoneMatches(String entered, String registered) {
        String a = normalizePhone(entered);
        String b = normalizePhone(registered);
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    /** Girilen e-posta kayıtlı adresle aynı mı — kırpılmış, büyük/küçük harf duyarsız tam eşleşme. */
    public static boolean emailMatches(String entered, String registered) {
        if (entered == null || registered == null || entered.length() > MAX_EMAIL_INPUT) return false;
        String a = entered.strip().toLowerCase(Locale.ROOT);
        String b = registered.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
