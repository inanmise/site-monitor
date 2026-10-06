package com.sitemonitor.service;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.PolicyInformation;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir X.509 sertifikasından izleme sonucunun ALANLARINI çıkaran saf yardımcılar (2026-10-06).
 *
 * <p><b>Neden ayrı.</b> Bu gövde {@code CertificateCheckerService}'in özel metotlarıydı (ağdan okunan yaprak
 * sertifika). Elle yüklenen sertifikalar (dosyadan takip) AYNI sonuç haritasını üretmek zorunda — eşik, kademe,
 * alarm ve rapor hattı haritanın anahtarlarını okur. İki kopya zamanla ayrışırdı; kural tek yerde durur.
 * Davranış değişmedi: kontrol servisi buraya delege eder (saf taşıma).
 */
public final class CertificateFacts {

    private CertificateFacts() { }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String OID_CERT_POLICIES = "2.5.29.32";
    private static final String EV_OID            = "2.23.140.1.1";

    private static final String[] KEY_USAGE_NAMES = {
        "Digital Signature", "Non-Repudiation", "Key Encipherment", "Data Encipherment",
        "Key Agreement", "Certificate Signing", "CRL Signing", "Encipher Only", "Decipher Only"
    };

    private static final Map<String, String> EKU_NAMES = Map.of(
        "1.3.6.1.5.5.7.3.1", "TLS Web Server",
        "1.3.6.1.5.5.7.3.2", "TLS Web Client",
        "1.3.6.1.5.5.7.3.3", "Code Signing",
        "1.3.6.1.5.5.7.3.4", "Email Protection",
        "1.3.6.1.5.5.7.3.8", "Timestamping"
    );

    /** ISO-8601 (UTC, saniye hassasiyeti) — kontrol sonuçlarının ortak tarih biçimi. */
    public static String iso(Instant t) {
        return t == null ? null : ISO.format(t);
    }

    /**
     * Yaprak sertifikanın sonuç alanları — ağ kontrolünün {@code parseLeafCert} çıktısıyla BİREBİR aynı anahtarlar ve
     * değerler. İstisna fırlatabilir; çağıran kendi hata biçimine çevirir.
     *
     * @param warningDays süre uyarısı eşiği ({@code site.monitor.warning-days})
     * @param chainValidator OCSP/CRL adreslerinin okunduğu servis (yalnız uzantı okur, ağa çıkmaz)
     */
    public static Map<String, Object> leafResult(X509Certificate cert, String domain, int warningDays,
                                                 ChainValidationService chainValidator, Instant now) {
        Instant notBefore = cert.getNotBefore().toInstant();
        Instant notAfter = cert.getNotAfter().toInstant();

        // D5: '/' sıfıra doğru kırpar — 12 saat önce dolmuş sertifika -0.5 → 0 gün verir ve
        // ilk ~24 saat "expired" yerine "0 gün kaldı" görünürdü. floorDiv negatifi korur
        // (DomainCheckerService.daysUntil ile aynı kural).
        long daysRemaining = daysRemaining(notAfter, now);
        boolean warning = daysRemaining <= warningDays;

        String subjectCn = extractCn(cert.getSubjectX500Principal().getName());
        String issuerOrg = extractField(cert.getIssuerX500Principal().getName(), "O");
        String issuerCn = extractCn(cert.getIssuerX500Principal().getName());
        List<String> san = extractSan(cert);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domain);
        result.put("subject", subjectCn);
        result.put("issuer", issuerOrg);
        result.put("issuer_cn", issuerCn);
        result.put("not_before", ISO.format(notBefore));
        result.put("not_after", ISO.format(notAfter));
        result.put("days_remaining", (int) daysRemaining);
        result.put("warning", warning);
        result.put("status", warning ? "warning" : "valid");
        result.put("san", san);
        result.put("checked_at", ISO.format(now));
        // Extended certificate metadata
        result.put("serial_number", serialHex(cert));
        result.put("signature_algorithm", cert.getSigAlgName());
        result.put("public_key_algorithm", cert.getPublicKey().getAlgorithm());
        result.put("public_key_size", publicKeySize(cert.getPublicKey()));
        result.put("subject_dn", cert.getSubjectX500Principal().getName());
        result.put("issuer_dn", cert.getIssuerX500Principal().getName());
        result.put("key_usage", keyUsageList(cert.getKeyUsage()));
        result.put("ext_key_usage", extKeyUsageList(cert));
        result.put("is_ca", cert.getBasicConstraints() >= 0);
        result.put("ocsp_url", chainValidator != null ? chainValidator.extractOcspUrl(cert) : null);
        result.put("crl_url", chainValidator != null ? chainValidator.extractCrlUrl(cert) : null);
        result.put("cert_type", certType(cert, san));
        return result;
    }

    /** Kalan tam gün — negatifi korur (floorDiv). */
    public static long daysRemaining(Instant notAfter, Instant now) {
        return Math.floorDiv(notAfter.toEpochMilli() - now.toEpochMilli(), 86_400_000L);
    }

    /** Seri numarası — büyük harf onaltılık (ağ kontrolüyle aynı biçim). */
    public static String serialHex(X509Certificate cert) {
        return cert.getSerialNumber().toString(16).toUpperCase();
    }

    public static int publicKeySize(PublicKey key) {
        if (key instanceof RSAKey rsa) return rsa.getModulus().bitLength();
        if (key instanceof ECKey ec) return ec.getParams().getOrder().bitLength();
        if (key instanceof DSAKey dsa) return dsa.getParams().getP().bitLength();
        return -1;
    }

    public static List<String> keyUsageList(boolean[] ku) {
        if (ku == null) return Collections.emptyList();
        List<String> usages = new ArrayList<>();
        for (int i = 0; i < Math.min(ku.length, KEY_USAGE_NAMES.length); i++) {
            if (ku[i]) usages.add(KEY_USAGE_NAMES[i]);
        }
        return usages;
    }

    public static List<String> extKeyUsageList(X509Certificate cert) {
        try {
            List<String> eku = cert.getExtendedKeyUsage();
            if (eku == null) return Collections.emptyList();
            return eku.stream().map(oid -> EKU_NAMES.getOrDefault(oid, oid)).toList();
        } catch (Exception e) { return Collections.emptyList(); }
    }

    public static String extractCn(String dn) {
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith("CN="))
                .map(s -> s.substring(3))
                .findFirst()
                .orElse("Unknown");
    }

    public static String extractField(String dn, String field) {
        String prefix = field + "=";
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith(prefix))
                .map(s -> s.substring(prefix.length()))
                .findFirst()
                .orElse("Unknown");
    }

    public static String certType(X509Certificate cert, List<String> san) {
        String org = extractField(cert.getSubjectX500Principal().getName(), "O");
        boolean hasOrg = !"Unknown".equals(org);
        boolean isEv = false;
        try {
            byte[] rawExt = cert.getExtensionValue(OID_CERT_POLICIES);
            if (rawExt != null) {
                byte[] extBytes = ASN1OctetString.getInstance(
                    ASN1Primitive.fromByteArray(rawExt)).getOctets();
                CertificatePolicies policies = CertificatePolicies.getInstance(
                    ASN1Primitive.fromByteArray(extBytes));
                for (PolicyInformation pi : policies.getPolicyInformation()) {
                    if (EV_OID.equals(pi.getPolicyIdentifier().getId())) {
                        isEv = true; break;
                    }
                }
            }
        } catch (Exception ignored) {}
        String validation = isEv && hasOrg ? "Extended Validation (EV)"
                          : hasOrg         ? "Organization Validated (OV)"
                          :                  "Domain Validated (DV)";
        String scope = san.stream().anyMatch(s -> s.startsWith("*.")) ? "Wildcard"
                     : san.size() > 1                                  ? "Multi-Domain (SAN)"
                     :                                                    "Single Domain";
        return validation + " — " + scope;
    }

    public static List<String> extractSan(X509Certificate cert) {
        List<String> sans = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> entry : altNames) {
                    if (Integer.valueOf(2).equals(entry.get(0))) {
                        sans.add((String) entry.get(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return sans;
    }
}
