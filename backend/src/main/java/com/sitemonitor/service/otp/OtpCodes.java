package com.sitemonitor.service.otp;

import com.sitemonitor.service.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Kodla giriş kriptografisi (2026-10-02) — kod üretimi, HMAC özeti ve sabit-zamanlı karşılaştırma TEK yerde.
 *
 * <ul>
 *   <li><b>Kod:</b> {@link SecureRandom}, 000000–999999 (baştaki sıfırlar korunur — {@code Locale.ROOT} ile biçim, yerel
 *       rakam sistemine kaymaz).</li>
 *   <li><b>Özet:</b> HMAC-SHA256(anahtar, challengeKimliği + ":" + kod) — kod yalnız ONU ALAN challenge kimliğiyle
 *       eşleşir; aynı kod başka bir istekte geçersizdir. Veritabanında yalnız 64 hex özet durur.</li>
 *   <li><b>Anahtar:</b> {@code SITE_MONITOR_SECRET_KEY}'den türetilen alt anahtar ({@link SecretCipher#hmacSubKey}) — tüm
 *       pod'larda aynı, doğrulama başka pod'a düşebilir. Anahtar AYARLI DEĞİLSE (yalnız yerel/dev; prod profili anahtarsız
 *       açılmaz) süreç başına rastgele 32 baytlık anahtar + WARN: gömülü DEV anahtarı kaynak kodda açık olduğundan ondan
 *       türetmek, veritabanını okuyan birine 10^6 adayı denetip kodu geri ürettirirdi. Bedeli: anahtarsız ÇOK pod'lu
 *       kurulumda başka pod'a düşen doğrulama "yanlış kod" sayılır (prod'da olamaz).</li>
 *   <li><b>Karşılaştırma:</b> {@link MessageDigest#isEqual} — sabit zamanlı.</li>
 *   <li><b>Anahtar döndürme (2026-10-11):</b> önceki anahtar ({@code SITE_MONITOR_SECRET_KEY_PREVIOUS}) ayarlıysa doğrulama
 *       onun alt anahtarıyla alınan özeti de kabul eder — dağıtım sırasında eski pod'un verdiği kod yeni pod'da geçer.
 *       Yeni özet HER ZAMAN güncel anahtarla alınır; iki özet de her doğrulamada hesaplanır (süre sızdırmaz).</li>
 * </ul>
 * Bu sınıf hiçbir şey LOGLAMAZ (kod ve anahtar asla).
 */
@Slf4j
@Component
public class OtpCodes {

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;
    private final byte[] previousKey;
    private final boolean ephemeral;

    public OtpCodes(SecretCipher secretCipher) {
        byte[] k = secretCipher == null ? null : secretCipher.hmacSubKey("login-otp");
        if (k == null) {
            k = new byte[32];
            random.nextBytes(k);
            this.ephemeral = true;
            log.warn("Kodla giriş: SITE_MONITOR_SECRET_KEY ayarlı değil — kod özetleri süreç başına rastgele bir anahtarla "
                    + "alınıyor (çok pod'lu kurulumda doğrulama başka pod'a düşerse kod geçersiz sayılır). Prod'da anahtar zorunludur.");
        } else {
            this.ephemeral = false;
        }
        this.key = k;
        this.previousKey = ephemeral || secretCipher == null ? null : secretCipher.previousHmacSubKey("login-otp");
    }

    /** Anahtar süreç başına mı (gerçek gizli anahtar ayarlı değil) — ayarlar sayfası uyarı gösterir. */
    public boolean ephemeralKey() {
        return ephemeral;
    }

    /** Yeni 6 haneli kod. */
    public String newCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    /** HMAC-SHA256(anahtar, challengeId + ":" + kod) → 64 hex. */
    public String hmac(String challengeId, String code) {
        return hmacWith(key, challengeId, code);
    }

    private static String hmacWith(byte[] k, String challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            byte[] out = mac.doFinal(((challengeId == null ? "" : challengeId) + ":" + (code == null ? "" : code))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC hesaplanamadı");
        }
    }

    /** Sabit-zamanlı eşleşme: girilen kodun özeti saklanan özete eşit mi. Biçim bozuk olsa da özet HER ZAMAN hesaplanır. */
    public boolean matches(String challengeId, String code, String storedHex) {
        byte[] computed = hmac(challengeId, code).getBytes(StandardCharsets.US_ASCII);
        byte[] stored = (storedHex == null ? "" : storedHex).getBytes(StandardCharsets.US_ASCII);
        boolean current = MessageDigest.isEqual(computed, stored);
        if (previousKey == null) return current;
        // Döndürme penceresi: önceki anahtarın özeti de HER ZAMAN hesaplanır (kısa devre yok — süre farkı sızdırmaz)
        boolean previous = MessageDigest.isEqual(
                hmacWith(previousKey, challengeId, code).getBytes(StandardCharsets.US_ASCII), stored);
        return current | previous;
    }
}
