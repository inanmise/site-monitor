package com.certmonitor.service;

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
 * AES-256-GCM with a key derived (SHA-256) from {@code cert.monitor.secret-key}.
 *
 * <p>Encrypted values are tagged with {@link #PREFIX} so we can tell them apart
 * from accidental plaintext. The key MUST be stable across pods in production
 * (set {@code CERT_MONITOR_SECRET_KEY} in the K8s secret) so a value encrypted
 * on one node decrypts on another.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;       // GCM standard nonce length
    private static final int TAG_BITS = 128;
    private static final String DEV_DEFAULT = "certmonitor-dev-secret-change-me";

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    @Value("${cert.monitor.secret-key:}")
    private String configuredKey;

    @PostConstruct
    void init() {
        String material = (configuredKey == null || configuredKey.isBlank()) ? DEV_DEFAULT : configuredKey;
        if (material.equals(DEV_DEFAULT)) {
            log.warn("SecretCipher: cert.monitor.secret-key not set — using insecure DEV default. "
                    + "Set CERT_MONITOR_SECRET_KEY (stable across pods) before storing real secrets.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SecretCipher init failed", e);
        }
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

    /** Decrypts a value produced by {@link #encrypt}. Returns null on null/blank/failure. */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (!stored.startsWith(PREFIX)) {
            // Not in our format — treat as already-plaintext (defensive, e.g. manual DB edit).
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("SecretCipher: decrypt failed (wrong key or corrupt value?): {}", e.getMessage());
            return null;
        }
    }
}
