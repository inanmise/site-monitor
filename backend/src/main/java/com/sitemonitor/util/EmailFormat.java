package com.sitemonitor.util;

import java.util.regex.Pattern;

/**
 * Tek e-posta adresi biçim denetimi (2026-10-08, "doğrulanmadan alınan veri var mı? doğrulama ekle").
 *
 * <p>Kullanıcı, takım ve eskalasyon kişisi e-postaları yeni değerde bu kurala bakar. Kural bilinçli olarak
 * {@code NotificationGroupService}'teki GEVŞEK desenle aynıdır: RFC 5322'yi tam uygulayan bir regex okunmaz olur ve
 * kurumsal adreslerde yanlış-negatif üretir — amaç yazım hatasını ("ali@", "ali example.com", "a@b.com; c@d.com")
 * yakalamak. Liste kabul edilmez: alarm gönderimi ({@code MimeMessageHelper.setTo}) alan başına TEK adres bekler.
 *
 * <p>YALNIZ yeni girdide çağrılır; saklı (eski) değerler ve LDAP/AD'den gelen adresler denetlenmez.
 */
public final class EmailFormat {

    private EmailFormat() {}

    /** RFC 5321 yol sınırı. */
    public static final int MAX_LENGTH = 254;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]{2,}$");

    /** Kırpılmış değer tek ve biçimce makul bir e-posta adresi mi? {@code null}/boş → false. */
    public static boolean isValid(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return !s.isEmpty() && s.length() <= MAX_LENGTH && EMAIL.matcher(s).matches();
    }
}
