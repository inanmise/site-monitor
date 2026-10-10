package com.sitemonitor.service.crypto;

import java.util.Locale;

/**
 * Kripto envanteri sınıflandırıcısı (2026-10-10, kullanıcı isteği: "Kripto envanteri / kuantum sonrası hazırlık").
 * Algoritma × anahtar boyu kovası, imza özeti (hash) ailesi, kuantum sonrası (PQC) durumu ve geçiş kategorisi için
 * TEK kaynak — saf, durumsuz, hiç sorgu yok. Ön yüz bu kodları yalnız çevirir; kuralı kopyalamaz.
 *
 * <h2>Kurallar</h2>
 * <ul>
 *   <li><b>Anahtar kovası</b> ({@link KeyBucket}): RSA ≤1024 · 1025–2047 · 2048–3071 · 3072–4095 · ≥4096 · boyu
 *       bilinmeyen; EC P-256 / P-384 / P-521 / diğer; Ed25519 (Java'nın {@code EdDSA} adı da buraya) / Ed448; DSA;
 *       PQC (ML-DSA, SLH-DSA, FN-DSA/Falcon, ML-KEM, XMSS…); HİBRİT (bileşik: PQC + klasik aynı adda); diğer; bilinmiyor.
 *       Sıra önemlidir: PQC belirteci önce (ML-DSA adı "DSA" içerir), sonra EC ({@code ECDSA} "DSA" içerir), EdDSA,
 *       RSA, en son DSA.</li>
 *   <li><b>İmza özeti</b> ({@link SigHash}): MD5 (MD2 dahil), SHA-1, SHA-224, SHA-256 (SHA3-256 dahil), SHA-384,
 *       SHA-512, EdDSA, PQC, HİBRİT, diğer (ör. özeti parametrede olan RSASSA-PSS), bilinmiyor. Zayıf = MD5 / SHA-1.</li>
 *   <li><b>PQC durumu</b> ({@link PqcStatus}): bugünkü her RSA / ECC / EdDSA / DSA anahtarı Shor algoritmasına karşı
 *       {@code VULNERABLE}; bileşik ad {@code HYBRID}; yalnız PQC ad {@code PQC}; veri yoksa ya da algoritma
 *       tanınmıyorsa {@code UNKNOWN} (tanımadığımız bir adı "güvenli" ya da "zayıf" ilan etmeyiz).
 *       <b>Sınır:</b> TLS anahtar değişimi grubu (ör. X25519MLKEM768) saklanmıyor — hibrit anahtar değişimi gözlenemez;
 *       hüküm sertifikanın anahtar/imza algoritmasına dayanır.</li>
 *   <li><b>Geçiş kategorisi</b> ({@link Category}):
 *     <ul>
 *       <li>{@code BROKEN} — bugün zayıf: yaprak imzası MD5/SHA-1, RSA/DSA &lt; 2048, EC &lt; 256, ya da kök OLMAYAN
 *           bir ara sertifika MD5/SHA-1 imzalı. Yaprak kısmı {@code CertificateHealthRules.classifyWeakness} ile AYNI
 *           eşik (Zayıf Algoritma raporu ile çelişmez; test bunu doğruluk tablosuyla pinler).</li>
 *       <li>{@code LEGACY} — bugün kabul ama NIST SP 800-131A 2030 sonrası değil: RSA 2048–3071, DSA ≥ 2048.</li>
 *       <li>{@code MODERN} — klasik olarak güçlü (RSA ≥ 3072, EC ≥ 256, EdDSA); yalnız kuantuma karşı açık.</li>
 *       <li>{@code PQC_READY} — hibrit ya da PQC.</li>
 *       <li>{@code UNKNOWN} — anahtar verisi yok, boyu bilinmiyor ya da algoritma tanınmıyor.</li>
 *     </ul></li>
 * </ul>
 */
public final class CryptoClassifier {

    private CryptoClassifier() {}

    /** NIST SP 800-57 / 800-131A: 2030 sonrası asgari RSA/DSA boyu (Zayıf Algoritma raporundaki eşikle aynı). */
    public static final int RSA_2030_MIN_BITS = 3072;

    public enum Family { RSA, EC, EDDSA, DSA, PQC, HYBRID, OTHER, UNKNOWN }

    /** Algoritma × boy kovası — sıra arayüz ve dışa aktarım sırasıdır. */
    public enum KeyBucket {
        RSA_1024(Family.RSA), RSA_LT2048(Family.RSA), RSA_2048(Family.RSA), RSA_3072(Family.RSA), RSA_4096(Family.RSA),
        RSA_OTHER(Family.RSA),
        EC_P256(Family.EC), EC_P384(Family.EC), EC_P521(Family.EC), EC_OTHER(Family.EC),
        ED25519(Family.EDDSA), ED448(Family.EDDSA),
        DSA(Family.DSA),
        PQC(Family.PQC), HYBRID(Family.HYBRID),
        OTHER(Family.OTHER), UNKNOWN(Family.UNKNOWN);

        public final Family family;
        KeyBucket(Family family) { this.family = family; }
    }

    public enum SigHash {
        MD5, SHA1, SHA224, SHA256, SHA384, SHA512, EDDSA, PQC, HYBRID, OTHER, UNKNOWN;
        public boolean weak() { return this == MD5 || this == SHA1; }
    }

    public enum PqcStatus { VULNERABLE, HYBRID, PQC, UNKNOWN }

    public enum Category { BROKEN, LEGACY, MODERN, PQC_READY, UNKNOWN }

    /** Kuantum sonrası algoritma belirteçleri (büyük harf, tire/alt çizgi/boşluk atılmış adda aranır). */
    private static final String[] PQC_TOKENS = {
            "MLDSA", "DILITHIUM", "SLHDSA", "SPHINCS", "FALCON", "FNDSA", "MLKEM", "KYBER", "XMSS"};
    /** Bileşik (hibrit) adlarda PQC belirtecinin yanındaki klasik bileşen. */
    private static final String[] CLASSICAL_TOKENS = {
            "RSA", "ECDSA", "ED25519", "ED448", "P256", "P384", "P521", "BRAINPOOL", "COMPOSITE"};

    // ── Anahtar ──────────────────────────────────────────────────────────────────────────

    public static KeyBucket keyBucket(String keyAlgorithm, Integer keySize) {
        String n = norm(keyAlgorithm);
        if (n.isEmpty()) return KeyBucket.UNKNOWN;
        if (hasAny(n, PQC_TOKENS)) return hasAny(n, CLASSICAL_TOKENS) ? KeyBucket.HYBRID : KeyBucket.PQC;
        int size = keySize == null ? -1 : keySize;
        if (n.startsWith("EC")) {
            // "EC", "ECDSA", "ECDH", "ECPUBLICKEY" — Java EC anahtarının adı "EC"
            if (size == 256) return KeyBucket.EC_P256;
            if (size == 384) return KeyBucket.EC_P384;
            if (size == 521) return KeyBucket.EC_P521;
            return KeyBucket.EC_OTHER;
        }
        if (n.contains("448")) return KeyBucket.ED448;
        if (n.contains("25519") || n.equals("EDDSA")) return KeyBucket.ED25519;
        if (n.contains("RSA")) {
            if (size <= 0) return KeyBucket.RSA_OTHER;
            if (size <= 1024) return KeyBucket.RSA_1024;
            if (size < 2048) return KeyBucket.RSA_LT2048;
            if (size < RSA_2030_MIN_BITS) return KeyBucket.RSA_2048;
            if (size < 4096) return KeyBucket.RSA_3072;
            return KeyBucket.RSA_4096;
        }
        if (n.contains("DSA")) return KeyBucket.DSA;
        return KeyBucket.OTHER;
    }

    // ── İmza ─────────────────────────────────────────────────────────────────────────────

    public static SigHash sigHash(String signatureAlgorithm) {
        String n = norm(signatureAlgorithm);
        if (n.isEmpty()) return SigHash.UNKNOWN;
        if (hasAny(n, PQC_TOKENS)) return hasAny(n, CLASSICAL_TOKENS) ? SigHash.HYBRID : SigHash.PQC;
        if (n.contains("MD5") || n.contains("MD2")) return SigHash.MD5;
        if (n.contains("SHA1")) return SigHash.SHA1;
        if (n.contains("SHA224") || n.contains("SHA3224")) return SigHash.SHA224;
        if (n.contains("SHA256") || n.contains("SHA3256")) return SigHash.SHA256;
        if (n.contains("SHA384") || n.contains("SHA3384")) return SigHash.SHA384;
        if (n.contains("SHA512") || n.contains("SHA3512")) return SigHash.SHA512;
        if (n.contains("ED25519") || n.contains("ED448") || n.equals("EDDSA")) return SigHash.EDDSA;
        return SigHash.OTHER;
    }

    // ── PQC durumu ───────────────────────────────────────────────────────────────────────

    public static PqcStatus pqcStatus(KeyBucket key, SigHash leafSig) {
        if (key == KeyBucket.HYBRID || leafSig == SigHash.HYBRID) return PqcStatus.HYBRID;
        if (key == KeyBucket.PQC) return PqcStatus.PQC;
        return switch (key.family) {
            case RSA, EC, EDDSA, DSA -> PqcStatus.VULNERABLE;
            default -> PqcStatus.UNKNOWN;
        };
    }

    // ── Geçiş kategorisi ─────────────────────────────────────────────────────────────────

    /**
     * @param weakIntermediate kök OLMAYAN bir ara sertifika MD5/SHA-1 imzalı mı (yaprak dahil değil)
     */
    public static Category category(KeyBucket key, Integer keySize, SigHash leafSig, boolean weakIntermediate) {
        if (key == KeyBucket.UNKNOWN) return Category.UNKNOWN;
        if (key == KeyBucket.PQC || key == KeyBucket.HYBRID || leafSig == SigHash.HYBRID) return Category.PQC_READY;
        // Bugün zayıf — yaprak eşiği CertificateHealthRules.classifyWeakness ile aynı
        if (leafSig.weak() || weakIntermediate) return Category.BROKEN;
        int size = keySize == null ? -1 : keySize;
        switch (key.family) {
            case RSA, DSA -> {
                if (size <= 0) return Category.UNKNOWN;
                if (size < 2048) return Category.BROKEN;
                if (key.family == Family.DSA) return Category.LEGACY;   // DSA imza üretimi FIPS 186-5'te bırakıldı
                return size < RSA_2030_MIN_BITS ? Category.LEGACY : Category.MODERN;
            }
            case EC -> {
                if (size <= 0) return Category.UNKNOWN;
                return size < 256 ? Category.BROKEN : Category.MODERN;
            }
            case EDDSA -> { return Category.MODERN; }
            default -> { return Category.UNKNOWN; }
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────

    /** Büyük harf, tire / alt çizgi / boşluk / nokta atılmış ad ({@code "SHA-1withRSA"} → {@code "SHA1WITHRSA"}). */
    static String norm(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_\\-.]", "");
    }

    private static boolean hasAny(String n, String[] tokens) {
        for (String t : tokens) if (n.contains(t)) return true;
        return false;
    }
}
