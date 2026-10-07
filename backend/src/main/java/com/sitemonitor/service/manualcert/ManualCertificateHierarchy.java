package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.CertificateFacts;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

import javax.security.auth.x500.X500Principal;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elle yüklenen bir sertifika sürümünün TARAYICI GİBİ hiyerarşisi (2026-10-07, kullanıcı isteği: "root → intermediate →
 * leaf alt alta"). Saklanan açık zincirden ({@code chain_pem}: baş ilk, ardından verenler yapraktan köke) ÇEVRİM-DIŞI
 * kurulur — ağ yok, yazma yok, alarm yok.
 *
 * <p><b>Sıra</b>: KÖK İLK, takip edilen baş (yaprak) SON — Chrome / Firefox / Windows "Sertifika Hiyerarşisi" gibi.
 * {@code depth} 0 = en üst.
 *
 * <p><b>Roller</b>:
 * <ul>
 *   <li>en üstteki kendinden imzalı sertifika {@code root} (tek başına yüklenmiş kendinden imzalı CA da kök);</li>
 *   <li>takip edilen baş: CA değilse {@code leaf}; CA ise (truststore'daki ara/kök) gerçek rolü;</li>
 *   <li>arada kalanlar {@code intermediate}.</li>
 * </ul>
 * En üstteki sertifika kendinden imzalı DEĞİLSE kök dosyada yoktur: o düğüm "dosyadaki en üst veren"dir ve
 * {@code issuer_missing=true} taşır (istemci zinciri kendi güven deposundan tamamlar). Yalnız yaprak yüklenmişse yaprağın
 * kendisi işaretlenir.
 *
 * <p>Her düğüm kendi TEK açık sertifikasının PEM'ini taşır (özel anahtar hiçbir zaman saklanmaz/dönmez).
 */
public final class ManualCertificateHierarchy {

    private ManualCertificateHierarchy() { }

    public static final String ROLE_ROOT = "root";
    public static final String ROLE_INTERMEDIATE = "intermediate";
    public static final String ROLE_LEAF = "leaf";

    /**
     * Görünüm gövdesi: {@code nodes} (kök ilk), {@code issuer_missing}, {@code missing_issuer_dn},
     * {@code certificate_count}.
     *
     * @param stored saklanan sırayla zincir — baş (takip edilen) ilk, ardından verenler yapraktan köke
     */
    public static Map<String, Object> view(List<X509Certificate> stored, Instant now) {
        List<Map<String, Object>> nodes = nodes(stored, now);
        Map<String, Object> top = nodes.isEmpty() ? null : nodes.get(0);
        boolean missing = top != null && Boolean.TRUE.equals(top.get("issuer_missing"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certificate_count", nodes.size());
        out.put("issuer_missing", missing);
        out.put("missing_issuer_dn", missing ? top.get("issuer_dn") : null);
        out.put("nodes", nodes);
        return out;
    }

    /** Düğümler — KÖK İLK, baş SON. Boş / null girdi → boş liste. */
    public static List<Map<String, Object>> nodes(List<X509Certificate> stored, Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        int n = stored.size();
        // Saklanan sıra baş → kök; görünüm kök → baş.
        for (int depth = 0; depth < n; depth++) {
            int i = n - 1 - depth;                 // saklanan dizideki konum (0 = baş)
            X509Certificate c = stored.get(i);
            boolean top = depth == 0;
            boolean head = i == 0;
            boolean selfSigned = ManualCertificateChains.isSelfSigned(c);
            boolean ca = ManualCertificateChains.isCa(c);
            out.add(node(c, role(top, head, selfSigned, ca), depth, i, head, selfSigned, ca,
                    top && !selfSigned, now));
        }
        return out;
    }

    /** Rol kuralı (sınıf açıklaması). */
    static String role(boolean top, boolean head, boolean selfSigned, boolean ca) {
        if (top && selfSigned && (!head || ca)) return ROLE_ROOT;
        if (head) return ca ? (selfSigned ? ROLE_ROOT : ROLE_INTERMEDIATE) : ROLE_LEAF;
        return ROLE_INTERMEDIATE;
    }

    private static Map<String, Object> node(X509Certificate c, String role, int depth, int position, boolean head,
                                            boolean selfSigned, boolean ca, boolean issuerMissing, Instant now) {
        Instant notBefore = c.getNotBefore().toInstant();
        Instant notAfter = c.getNotAfter().toInstant();
        long days = CertificateFacts.daysRemaining(notAfter, now);
        int keySize = CertificateFacts.publicKeySize(c.getPublicKey());
        int bc = c.getBasicConstraints();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("depth", depth);
        m.put("position", position);
        m.put("head", head);
        m.put("subject_dn", c.getSubjectX500Principal().getName());
        m.put("subject", nameParts(c.getSubjectX500Principal()));
        m.put("issuer_dn", c.getIssuerX500Principal().getName());
        m.put("issuer", nameParts(c.getIssuerX500Principal()));
        m.put("serial_number", CertificateFacts.serialHex(c));
        m.put("not_before", CertificateFacts.iso(notBefore));
        m.put("not_after", CertificateFacts.iso(notAfter));
        m.put("days_remaining", days);
        m.put("expired", now.isAfter(notAfter));
        m.put("not_yet_valid", now.isBefore(notBefore));
        m.put("signature_algorithm", c.getSigAlgName());
        m.put("public_key_algorithm", c.getPublicKey().getAlgorithm());
        m.put("public_key_size", keySize > 0 ? keySize : null);
        m.put("san", subjectAltNames(c));
        m.put("key_usage", CertificateFacts.keyUsageList(c.getKeyUsage()));
        m.put("ext_key_usage", CertificateFacts.extKeyUsageList(c));
        m.put("is_ca", ca);
        // -1: CA değil; Integer.MAX_VALUE: sınırsız → null (istemci "sınırsız" yazar)
        m.put("path_length", ca && bc != Integer.MAX_VALUE ? Integer.valueOf(bc) : null);
        m.put("self_signed", selfSigned);
        m.put("issuer_missing", issuerMissing);
        m.put("sha256_fingerprint", digestHex(c, "SHA-256"));
        m.put("sha1_fingerprint", digestHex(c, "SHA-1"));
        m.put("pem", CertificateFileParser.toPem(List.of(c)));
        return m;
    }

    /**
     * Ad parçaları: {@code cn, o, ou, l, st, c} (yoksa null). Aynı türün birden çok değeri (ör. iki OU) kodlama sırasıyla
     * ", " ile birleşir. Değerler kaçışsız düz metin.
     */
    static Map<String, String> nameParts(X500Principal principal) {
        Map<String, String> m = new LinkedHashMap<>();
        X500Name name;
        try {
            name = X500Name.getInstance(principal.getEncoded());
        } catch (Exception e) {
            name = null;
        }
        m.put("cn", part(name, BCStyle.CN));
        m.put("o", part(name, BCStyle.O));
        m.put("ou", part(name, BCStyle.OU));
        m.put("l", part(name, BCStyle.L));
        m.put("st", part(name, BCStyle.ST));
        m.put("c", part(name, BCStyle.C));
        return m;
    }

    private static String part(X500Name name, ASN1ObjectIdentifier oid) {
        if (name == null) return null;
        List<String> values = new ArrayList<>();
        for (RDN rdn : name.getRDNs()) {
            for (AttributeTypeAndValue tv : rdn.getTypesAndValues()) {
                if (oid.equals(tv.getType())) {
                    String s = text(tv.getValue());
                    if (s != null && !s.isBlank()) values.add(s.strip());
                }
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static String text(ASN1Encodable v) {
        if (v == null) return null;
        if (v instanceof ASN1String s) return s.getString();
        try {
            return IETFUtils.valueToString(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** Alternatif adlar — DNS adları ve IP adresleri, sertifikadaki sırayla. */
    static List<String> subjectAltNames(X509Certificate c) {
        List<String> out = new ArrayList<>();
        try {
            Collection<List<?>> alt = c.getSubjectAlternativeNames();
            if (alt == null) return out;
            for (List<?> e : alt) {
                if (e.size() < 2) continue;
                Object type = e.get(0);
                if ((Integer.valueOf(2).equals(type) || Integer.valueOf(7).equals(type)) && e.get(1) instanceof String s) {
                    out.add(s);
                }
            }
        } catch (Exception ignored) {
            // okunamayan uzantı: ad listesi boş
        }
        return out;
    }

    /** DER kodlamasının özeti — büyük harf onaltılık, ayraçsız (sürümün {@code fingerprint} biçimi). */
    static String digestHex(X509Certificate c, String algorithm) {
        try {
            byte[] d = MessageDigest.getInstance(algorithm).digest(c.getEncoded());
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
