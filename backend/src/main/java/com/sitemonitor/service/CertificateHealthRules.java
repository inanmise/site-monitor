package com.sitemonitor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sertifika sağlığı SINIFLANDIRMA kuralları — saf, durumsuz, tablo-güdümlü.
 *
 * <p><b>Neden ayrı ve saf.</b> Aynı hüküm iki ekranda veriliyor: sağlık kontrol listesi ve Zayıf
 * Algoritma Raporu. İkisi ayrı yerlerde karar verirse aynı sertifika bir ekranda "zayıf", diğerinde
 * "temiz" görünebilir — kullanıcı hangisine inanacağını bilemez. Kural burada tek yerde durur;
 * {@code AuditController.classifyWeakness} da buraya bakar.
 *
 * <p><b>UNKNOWN, FAIL DEĞİLDİR.</b> Kurumsal proxy arkasında OCSP/CRL erişilemeyebilir, eski
 * kayıtlarda cipher/protokol boştur. Bilinmeyeni kırmızıya boyamak yanlış alarm üretir ve
 * kullanıcı bir süre sonra tüm kırmızıları görmezden gelmeye başlar. Bilinmeyen ayrı bir durumdur.
 */
public final class CertificateHealthRules {

    private CertificateHealthRules() { }

    /** Satır durumu. NA: bu domain için anlamsız (ör. sayfa izlemesi yoksa karışık içerik). */
    public enum Status { OK, WARN, FAIL, UNKNOWN, NA }

    /** Şifreleme kademesi (K5): yüzde skoru UYDURMAK yerine savunulabilir üç kademe. */
    public enum CipherTier { STRONG, ACCEPTABLE, WEAK, UNKNOWN }

    // ── Protokol ────────────────────────────────────────────────────────────

    /**
     * TLS sürümü → durum. 1.3 ve 1.2 kabul, 1.1 ve altı FAIL (PCI-DSS ve tarayıcılar bıraktı),
     * boş/tanınmayan UNKNOWN.
     */
    public static Status protocolStatus(String tlsVersion) {
        if (isBlank(tlsVersion)) return Status.UNKNOWN;
        String v = tlsVersion.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (v.contains("1.3")) return Status.OK;
        if (v.contains("1.2")) return Status.OK;
        // "TLSV1" (noktasız) Java'nın TLS 1.0 için döndürdüğü GERÇEK değerdir
        // (SSLSession.getProtocol()); atlanırsa canlı bir TLS 1.0 bağlantısı "doğrulanamadı"
        // görünür ve gerçek bir güvenlik sorunu gizlenirdi.
        if (v.contains("1.1") || v.contains("1.0") || v.equals("TLSV1") || v.startsWith("SSL")) {
            return Status.FAIL;
        }
        return Status.UNKNOWN;
    }

    /** TLS 1.2 güvenli ama 1.3 değil → arayüzde "güncel değil" notu için ayrı bilgi. */
    public static boolean isLatestProtocol(String tlsVersion) {
        return !isBlank(tlsVersion) && tlsVersion.toUpperCase(Locale.ROOT).contains("1.3");
    }

    // ── Şifreleme ───────────────────────────────────────────────────────────

    /**
     * Cipher suite → kademe.
     *
     * <p>AEAD (GCM/CHACHA20/CCM) güçlü; CBC bugün kırılmış değil ama modern değil (Lucky13 ailesi
     * ve TLS 1.3'ün tamamen kaldırmış olması) → kabul edilebilir-uyarı; 3DES/RC4/DES/EXPORT/NULL/
     * anon ve MD5 imzalı süitler zayıf. Tanınmayan ad UNKNOWN — yeni bir süiti "zayıf" ilan edip
     * yanlış alarm üretmeyiz.
     */
    public static CipherTier cipherTier(String cipherSuite) {
        if (isBlank(cipherSuite)) return CipherTier.UNKNOWN;
        String c = cipherSuite.trim().toUpperCase(Locale.ROOT);

        if (c.contains("NULL") || c.contains("ANON") || c.contains("EXPORT")
                || c.contains("RC4") || c.contains("3DES") || c.contains("DES_CBC")
                || c.contains("_DES_") || c.contains("MD5") || c.contains("IDEA")
                || c.contains("SEED") || c.contains("PSK_WITH_NULL")) {
            return CipherTier.WEAK;
        }
        if (c.contains("GCM") || c.contains("CHACHA20") || c.contains("CCM")) return CipherTier.STRONG;
        if (c.contains("CBC")) return CipherTier.ACCEPTABLE;
        return CipherTier.UNKNOWN;
    }

    public static Status cipherStatus(String cipherSuite) {
        return switch (cipherTier(cipherSuite)) {
            case STRONG -> Status.OK;
            case ACCEPTABLE -> Status.WARN;
            case WEAK -> Status.FAIL;
            case UNKNOWN -> Status.UNKNOWN;
        };
    }

    // ── PFS (ileriye dönük gizlilik) ────────────────────────────────────────

    /**
     * PFS durumu, ek ağ trafiği OLMADAN türetilir.
     *
     * <p>TLS 1.3'te tüm anahtar değişimi ephemeral'dır — süit adına bakmaya gerek yok. TLS 1.2'de
     * karar süit adındadır: {@code ECDHE_}/{@code DHE_} varsa PFS, statik {@code RSA_}/{@code DH_}
     * ile başlıyorsa yok. Ne sürüm ne ad biliniyorsa UNKNOWN.
     */
    public static Status pfsStatus(String tlsVersion, String cipherSuite) {
        if (isLatestProtocol(tlsVersion)) return Status.OK;          // TLS 1.3 → daima PFS
        if (isBlank(cipherSuite)) return Status.UNKNOWN;
        String c = cipherSuite.toUpperCase(Locale.ROOT);
        if (c.contains("ECDHE") || c.contains("DHE_")) return Status.OK;
        if (c.startsWith("TLS_RSA") || c.startsWith("SSL_RSA")
                || c.contains("_DH_") || c.contains("_ECDH_")) return Status.FAIL;
        return Status.UNKNOWN;
    }

    // ── İmza algoritması ve anahtar boyu ────────────────────────────────────

    /**
     * İmza algoritması → durum. MD2/MD5 ve SHA-1 çakışma saldırılarına açık; CA/Browser Forum
     * 2016'da yasakladı.
     */
    public static Status signatureStatus(String signatureAlgorithm) {
        if (isBlank(signatureAlgorithm)) return Status.UNKNOWN;
        String up = signatureAlgorithm.toUpperCase(Locale.ROOT);
        if (up.contains("MD2") || up.contains("MD5")) return Status.FAIL;
        if (up.contains("SHA1") || up.contains("SHA-1")) return Status.FAIL;
        return Status.OK;
    }

    /** RSA/DSA &lt; 2048 bit, EC &lt; 256 bit → FAIL (NIST SP 800-57 ile aynı çizgi). */
    public static Status keySizeStatus(String keyAlgorithm, Integer keySize) {
        if (isBlank(keyAlgorithm) || keySize == null || keySize <= 0) return Status.UNKNOWN;
        String up = keyAlgorithm.toUpperCase(Locale.ROOT);
        if (up.contains("RSA") || up.contains("DSA")) return keySize < 2048 ? Status.FAIL : Status.OK;
        if (up.contains("EC")) return keySize < 256 ? Status.FAIL : Status.OK;
        return Status.UNKNOWN;
    }

    /**
     * Zayıf Algoritma Raporunun şiddet sınıfı — {@code AuditController} buraya delege eder ki
     * iki ekran aynı girdiye aynı hükmü versin.
     *
     * @param weaknesses bulgular buraya EKLENİR (çağıranın listesi)
     * @return CRITICAL / HIGH / null (zayıflık yok)
     */
    public static String classifyWeakness(String signatureAlgorithm, String keyAlgorithm,
                                          Integer keySize, List<String> weaknesses) {
        List<String> found = weaknesses != null ? weaknesses : new ArrayList<>();
        String maxSev = null;

        if (!isBlank(signatureAlgorithm)) {
            String up = signatureAlgorithm.toUpperCase(Locale.ROOT);
            if (up.contains("MD2") || up.contains("MD5")) {
                found.add("Deprecated hash: " + signatureAlgorithm);
                maxSev = "CRITICAL";
            } else if (up.contains("SHA1") || up.contains("SHA-1")) {
                found.add("Weak hash: " + signatureAlgorithm);
                maxSev = worst(maxSev, "HIGH");
            }
        }

        if (!isBlank(keyAlgorithm) && keySize != null) {
            String up = keyAlgorithm.toUpperCase(Locale.ROOT);
            if (up.contains("RSA") || up.contains("DSA")) {
                if (keySize < 2048) {
                    found.add("Short key: " + keyAlgorithm + " " + keySize + "-bit");
                    maxSev = worst(maxSev, keySize <= 1024 ? "CRITICAL" : "HIGH");
                }
            } else if (up.contains("EC") && keySize < 256) {
                found.add("Short EC key: " + keySize + "-bit");
                maxSev = worst(maxSev, keySize < 192 ? "CRITICAL" : "HIGH");
            }
        }
        return maxSev;
    }

    private static String worst(String cur, String cand) {
        if ("CRITICAL".equals(cur) || "CRITICAL".equals(cand)) return "CRITICAL";
        if ("HIGH".equals(cur)) return cur;
        return cand;
    }

    // ── SAN kapsaması ───────────────────────────────────────────────────────

    /**
     * İstenen alan adı sertifikanın SAN listesinde var mı (RFC 6125 joker kuralı).
     *
     * <p>Joker YALNIZ en soldaki etiketi karşılar ve TEK etikettir: {@code *.akbank.com}
     * "www.akbank.com"u karşılar, "a.b.akbank.com"u ya da çıplak "akbank.com"u KARŞILAMAZ.
     * Bunu gevşetmek, gerçekte kapsanmayan bir alan adını "kapsanıyor" göstermek olurdu.
     *
     * <p>SAN listesi boşsa UNKNOWN döner — sertifikada SAN olmaması eski bir kusurdur ama
     * verinin bize ulaşmamış olması da aynı görünür; ikisini ayırt edemediğimiz için hüküm
     * vermeyiz.
     */
    public static Status sanCoverage(String domain, java.util.List<String> sanEntries) {
        if (isBlank(domain) || sanEntries == null || sanEntries.isEmpty()) return Status.UNKNOWN;
        String host = domain.trim().toLowerCase(Locale.ROOT);
        for (String raw : sanEntries) {
            if (raw == null) continue;
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (entry.equals(host)) return Status.OK;
            if (entry.startsWith("*.") && matchesWildcard(host, entry.substring(2))) return Status.OK;
        }
        return Status.FAIL;
    }

    /** "*.example.com" → host, tam olarak BİR etiket daha derinde ve son eki aynı olmalı. */
    private static boolean matchesWildcard(String host, String suffix) {
        if (suffix.isEmpty() || !host.endsWith("." + suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length() - 1);
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    // ── Süre ────────────────────────────────────────────────────────────────

    /**
     * Kalan gün → durum; eşikler ÇAĞIRANDAN gelir (alarm eşikleri ayarlardan yönetiliyor, burada
     * ikinci bir sabit tanımlamak iki ekranı ayrıştırırdı).
     */
    public static Status expiryStatus(Integer daysRemaining, int warningDays, int criticalDays) {
        if (daysRemaining == null) return Status.UNKNOWN;
        if (daysRemaining < 0) return Status.FAIL;
        if (daysRemaining <= criticalDays) return Status.FAIL;
        if (daysRemaining <= warningDays) return Status.WARN;
        return Status.OK;
    }

    // ── Depolanan durum etiketleri ──────────────────────────────────────────

    /** VALID→OK, REVOKED→FAIL, boş/UNKNOWN→UNKNOWN. Zincir ve güven durumu da aynı kalıpta. */
    public static Status fromStatusLabel(String label, String okValue, String failValue) {
        if (isBlank(label)) return Status.UNKNOWN;
        String v = label.trim().toUpperCase(Locale.ROOT);
        if (v.equals(okValue)) return Status.OK;
        if (v.equals(failValue)) return Status.FAIL;
        return Status.UNKNOWN;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
