package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Small symmetric encryption helper for secrets that must be stored at rest yet
 * recovered in plaintext at runtime (e.g. the LDAP bind password). Uses
 * AES-256-GCM with a key derived (SHA-256) from {@code site.monitor.secret-key}.
 *
 * <p>Encrypted values are tagged with {@link #PREFIX} so we can tell them apart
 * from accidental plaintext. The key MUST be stable across pods in production
 * (set {@code SITE_MONITOR_SECRET_KEY} in the K8s secret) so a value encrypted
 * on one node decrypts on another.
 *
 * <p><b>Anahtar döndürme (2026-10-11):</b> {@code site.monitor.secret-key-previous}
 * ({@code SITE_MONITOR_SECRET_KEY_PREVIOUS}) YALNIZ ÇÖZME anahtarıdır: şifreleme her zaman güncel anahtarla yapılır,
 * çözme önce güncel, olmazsa önceki anahtarı dener (GCM etiketi hangisinin doğru olduğunu söyler — anahtar kimliği
 * gerekmez). Saklama biçimi DEĞİŞMEDİ ({@code enc:v1:}): eski sürüme geri dönüşte değerler aynı biçimde okunur.
 * Önceki anahtarla duran değerleri güncel anahtara taşıyan araç {@link SecretRotationService}.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;       // GCM standard nonce length
    private static final int TAG_BITS = 128;
    // geriye-uyum: DEV varsayilan anahtar DEGISTIRILMEZ — eski degerle sifrelenmis yerel secret'lar cozulemez olurdu.
    private static final String DEV_DEFAULT = "certmonitor-dev-secret-change-me";   // geriye-uyum: degistirme

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;
    private SecretKeySpec previousKey;
    private volatile boolean keyConfigured;
    private volatile boolean previousSameAsCurrent;

    @Value("${site.monitor.secret-key:}")
    private String configuredKey;

    /** Döndürme sırasında eski değerleri çözmek için önceki anahtar (yalnız çözme). Boş = yok. */
    @Value("${site.monitor.secret-key-previous:}")
    private String previousKeyMaterial;

    /** Saklanan bir şifreli değerin hangi anahtarla çözüldüğü. */
    public enum KeySlot { CURRENT, PREVIOUS, UNREADABLE }

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    private boolean isProd() {
        return activeProfiles != null && java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch(p -> p.equalsIgnoreCase("prod"));
    }

    /** true = gerçek bir SITE_MONITOR_SECRET_KEY ayarlı; false = güvensiz DEV varsayılanı kullanılıyor.
     *  UI, gerçek parola kaydetmeden önce admin'i uyarmak için bunu kullanır. */
    public boolean isKeyConfigured() {
        return keyConfigured;
    }

    /** Anahtar ayarlı değilken kullanılan gömülü DEV varsayılan anahtarı. Gizli değil (kaynak kodda
     *  sabit); admin "kayıtlı parolalar bununla mı şifrelenmiş?" testi yapabilsin diye UI'da gösterilir. */
    public String devDefaultKey() {
        return DEV_DEFAULT;
    }

    @PostConstruct
    void init() {
        this.keyConfigured = !(configuredKey == null || configuredKey.isBlank());
        String material = keyConfigured ? configuredKey : DEV_DEFAULT;
        boolean insecure = material.equals(DEV_DEFAULT);   // ayarlanmamış YA DA açıkça dev-default'a set edilmiş
        if (insecure && isProd()) {
            // Fail-fast: prod'da gömülü public dev anahtarına düşmek = at-rest LDAP/SMTP parolaları kaynak-kod
            // anahtarıyla şifrelenir (etkin düz metin). Operatörü zorla: startup'ı durdur.
            throw new IllegalStateException("site.monitor.secret-key (SITE_MONITOR_SECRET_KEY) prod profilinde "
                    + "ZORUNLU — güvensiz gömülü DEV anahtarına düşülemez. K8s secret'ta stabil bir anahtar ayarlayın.");
        }
        if (insecure) {
            this.keyConfigured = false;   // dev-default (UI uyarısı doğru kalsın)
            log.warn("SecretCipher: site.monitor.secret-key not set — using insecure DEV default. "
                    + "Set SITE_MONITOR_SECRET_KEY (stable across pods) before storing real secrets.");
        }
        this.key = aesKey(material);
        this.previousKey = null;
        this.previousSameAsCurrent = false;
        if (previousKeyMaterial != null && !previousKeyMaterial.isBlank()) {
            if (previousKeyMaterial.equals(material)) {
                // Aynı anahtar iki kez verilmiş — döndürme yok; çözme listesine eklemek yalnız iki kez denemek olurdu.
                this.previousSameAsCurrent = true;
                log.warn("SecretCipher: SITE_MONITOR_SECRET_KEY_PREVIOUS güncel anahtarla aynı — yok sayıldı.");
            } else {
                this.previousKey = aesKey(previousKeyMaterial);
                log.info("SecretCipher: önceki anahtar (yalnız çözme) ayarlı — döndürme sürüyor; yeniden şifreleme "
                        + "bitince SITE_MONITOR_SECRET_KEY_PREVIOUS kaldırılabilir.");
            }
        }
    }

    private static SecretKeySpec aesKey(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SecretCipher init failed", e);
        }
    }

    /** Önceki (yalnız çözme) anahtar ayarlı ve güncelden farklı mı. */
    public boolean isPreviousKeyConfigured() {
        return previousKey != null;
    }

    /** Önceki anahtar güncel anahtarla aynı verilmiş (yok sayıldı) — ekran uyarısı. */
    public boolean isPreviousSameAsCurrent() {
        return previousSameAsCurrent;
    }

    /** Encrypts plaintext → "enc:v1:<base64(iv||ciphertext)>". Null/blank passes through unchanged. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}. Returns null on null/blank/failure. Önce güncel, olmazsa önceki
     * anahtar (döndürme) denenir.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (!stored.startsWith(PREFIX)) {
            // Not in our format — treat as already-plaintext (defensive, e.g. manual DB edit).
            return stored;
        }
        String pt = tryDecrypt(stored, key);
        if (pt == null && previousKey != null) pt = tryDecrypt(stored, previousKey);
        if (pt == null) log.warn("SecretCipher: decrypt failed (wrong key or corrupt value?)");
        return pt;
    }

    /** {@code enc:v1:} biçimindeki (şifreli saklanan) değer mi. */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Şifreli değerin hangi anahtarla çözüldüğü (döndürme durumu). Şifreli olmayan değer için null. Loglama YOK. */
    public KeySlot classify(String stored) {
        if (!isEncrypted(stored)) return null;
        if (tryDecrypt(stored, key) != null) return KeySlot.CURRENT;
        if (previousKey != null && tryDecrypt(stored, previousKey) != null) return KeySlot.PREVIOUS;
        return KeySlot.UNREADABLE;
    }

    /**
     * Önceki anahtarla duran değeri güncel anahtarla yeniden şifreler. Değer zaten güncel anahtardaysa, çözülemiyorsa
     * ya da şifreli değilse null (yazılacak bir şey yok). Düz metin hiçbir yere loglanmaz.
     */
    public String reencryptIfPrevious(String stored) {
        if (!isEncrypted(stored) || previousKey == null) return null;
        if (tryDecrypt(stored, key) != null) return null;
        String pt = tryDecrypt(stored, previousKey);
        return pt == null ? null : encrypt(pt);
    }

    private static String tryDecrypt(String stored, SecretKeySpec k) {
        if (k == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_LEN) return null;
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verilen anahtar MATERYALİYLE (yapılandırılmış anahtar değil) çözer — admin'in anahtar
     * kurtarma/doğrulama aracı için. Yanlış anahtar (GCM doğrulaması düşer) / bozuk / şifreli-olmayan
     * değer → null. Loglama YOK (anahtar/plaintext sızdırmamak için).
     */
    public String decryptWith(String stored, String keyMaterial) {
        if (stored == null || stored.isBlank() || !stored.startsWith(PREFIX)) return null;
        if (keyMaterial == null || keyMaterial.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec k = new SecretKeySpec(digest, "AES");
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Amaca özel ALT ANAHTAR (2026-10-02, kodla giriş): HMAC-SHA256(SHA-256(anahtar materyali), "site-monitor:" + amaç).
     * Aynı {@code SITE_MONITOR_SECRET_KEY} tüm pod'larda aynı alt anahtarı verir (doğrulama başka pod'a düşebilir); alan
     * ayrımı sayesinde alt anahtar AES anahtarıyla aynı değildir. Gerçek anahtar AYARLI DEĞİLSE {@code null} döner —
     * gömülü DEV anahtarı kaynak kodda açık olduğundan ondan türetilen bir HMAC anahtarı veritabanını okuyana kodu
     * milisaniyede geri ürettirirdi; çağıran güvenli bir yedeğe düşer (bkz. {@code OtpCodes}). Loglama YOK.
     */
    public byte[] hmacSubKey(String purpose) {
        if (!keyConfigured || configuredKey == null || configuredKey.isBlank()) return null;
        return subKey(configuredKey, purpose);
    }

    /**
     * Önceki anahtardan aynı amaçla türetilen alt anahtar — döndürme sırasında kısa ömürlü kod özetleri (ör. başka pod'un
     * eski anahtarla verdiği giriş kodu) doğrulanabilsin. Gerçek anahtar ayarlı değilse, önceki anahtar yoksa ya da
     * önceki anahtar gömülü DEV anahtarıysa null (açık anahtardan HMAC türetilmez — bkz. {@link #hmacSubKey}).
     */
    public byte[] previousHmacSubKey(String purpose) {
        if (!keyConfigured || previousKey == null || previousKeyMaterial == null || previousKeyMaterial.isBlank()) return null;
        if (DEV_DEFAULT.equals(previousKeyMaterial)) return null;
        return subKey(previousKeyMaterial, purpose);
    }

    private static byte[] subKey(String material, String purpose) {
        try {
            byte[] root = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(root, "HmacSHA256"));
            return mac.doFinal(("site-monitor:" + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
