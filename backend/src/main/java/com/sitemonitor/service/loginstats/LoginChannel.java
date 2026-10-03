package com.sitemonitor.service.loginstats;

import java.util.Locale;

/**
 * Giriş KANALI (2026-10-03, Giriş Yöntemleri → İstatistikler) — kullanıcının kimliğini hangi yoldan kanıtladığı.
 *
 * <ul>
 *   <li>{@link #LOCAL} — yerel hesap parolası (BCrypt; kurulumdaki bootstrap admin ve LDAP kapalıyken yerel doğrulama).</li>
 *   <li>{@link #LDAP} — Active Directory parolası (AD bind).</li>
 *   <li>{@link #OTP_PUSH} / {@link #OTP_EMAIL} — tek kullanımlık kod (push / e-posta).</li>
 *   <li>{@link #REMEMBER_ME} — "beni hatırla" çereziyle sessiz yeniden giriş.</li>
 * </ul>
 * Sıra = arayüzdeki kart / seri sırası.
 */
public enum LoginChannel {
    LDAP, LOCAL, OTP_PUSH, OTP_EMAIL, REMEMBER_ME;

    /**
     * Denetim ayrıntısındaki {@code method} → kanal. Eski ad {@code PASSWORD} (yerel BCrypt yolu — ret satırlarının
     * neden metnindeki "(PASSWORD)") {@link #LOCAL} sayılır. Tanınmayan / boş → {@code null}.
     */
    public static LoginChannel fromMethod(String method) {
        if (method == null || method.isBlank()) return null;
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "LOCAL", "PASSWORD" -> LOCAL;
            case "LDAP" -> LDAP;
            case "REMEMBER_ME" -> REMEMBER_ME;
            case "OTP_PUSH" -> OTP_PUSH;
            case "OTP_EMAIL" -> OTP_EMAIL;
            default -> null;
        };
    }

    /** Kodla giriş olaylarının {@code channel} ayrıntısı ({@code PUSH} / {@code EMAIL}) → kanal. */
    public static LoginChannel fromOtpChannel(String channel) {
        if (channel == null) return null;
        return switch (channel.trim().toUpperCase(Locale.ROOT)) {
            case "PUSH" -> OTP_PUSH;
            case "EMAIL" -> OTP_EMAIL;
            default -> null;
        };
    }

    /** Hesabın KAYNAĞINA göre parola kanalı: kaynağı boş ya da LOCAL → {@link #LOCAL}, diğerleri {@link #LDAP}. */
    public static LoginChannel ofAccountSource(String authSource) {
        return authSource == null || authSource.isBlank() || "LOCAL".equalsIgnoreCase(authSource.trim()) ? LOCAL : LDAP;
    }

    /** Kodla giriş kanalı mı (huni gösterilir). */
    public boolean otp() {
        return this == OTP_PUSH || this == OTP_EMAIL;
    }
}
