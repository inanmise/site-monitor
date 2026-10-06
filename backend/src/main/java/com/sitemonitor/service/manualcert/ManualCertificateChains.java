package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.TrustEvaluator;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Yüklenen dosyadaki sertifikalardan ZİNCİR kurma ve çevrim-dışı GÜVEN hükmü (2026-10-06).
 *
 * <p>Zincir: her adımda konu adı = verenin adı OLAN ve imzası doğrulanan sertifika aranır (AKI/SKI yalnız imza
 * doğrulanamadığında yedek ölçüttür). Döngü ve en çok 10 adım korunur.
 *
 * <p>Güven ({@code trust_status}), ağ kontrolüyle aynı {@link TrustEvaluator}'dan geçer — AMA yalnız zincir
 * TAMAMSA hüküm verilir (kendinden imzalı köke ulaşır ya da güven deposundaki bir köke bağlanır). Yalnız yaprak
 * yüklenmişse doğrulanacak bir şey yoktur: {@code UNKNOWN} (asla {@code UNTRUSTED} — sınıfın "UNKNOWN, FAIL
 * değildir" kuralı). Sunucu kimlik doğrulaması için olmayan (EKU'da serverAuth yok) ya da CA sertifikası
 * girdilerinde güvenilmeyen sonuç da {@code UNKNOWN}'dur: TLS sunucu doğrulayıcısı onları kullanım kısıtı
 * yüzünden reddeder, bu bir güven kusuru değildir.
 */
public final class ManualCertificateChains {

    private ManualCertificateChains() { }

    static final int MAX_DEPTH = 10;
    private static final String EKU_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String EKU_ANY = "2.5.29.37.0";

    /** Güven hükmü + zincirin tam olup olmadığı. */
    public record Trust(String status, boolean chainComplete, String missingIssuer) { }

    /** {@code cert} için havuzdan kurulan zincir — sertifikanın KENDİSİ hariç, yapraktan köke doğru. */
    public static List<X509Certificate> buildChain(X509Certificate cert, Collection<X509Certificate> pool) {
        List<X509Certificate> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(key(cert));
        X509Certificate cur = cert;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (isSelfSigned(cur)) break;
            X509Certificate issuer = findIssuer(cur, pool);
            if (issuer == null || !seen.add(key(issuer))) break;
            chain.add(issuer);
            cur = issuer;
        }
        return chain;
    }

    static X509Certificate findIssuer(X509Certificate cur, Collection<X509Certificate> pool) {
        X509Certificate byKeyId = null;
        byte[] aki = authorityKeyId(cur);
        for (X509Certificate cand : pool) {
            if (cand == cur || !cand.getSubjectX500Principal().equals(cur.getIssuerX500Principal())) continue;
            try {
                cur.verify(cand.getPublicKey());
                return cand;
            } catch (Exception notThisOne) {
                byte[] ski = subjectKeyId(cand);
                if (byKeyId == null && aki != null && ski != null && Arrays.equals(aki, ski)
                        && !(notThisOne instanceof java.security.SignatureException)
                        && !(notThisOne instanceof java.security.InvalidKeyException)) {
                    byKeyId = cand;   // imza algoritması sağlayıcıda yoksa ad + anahtar kimliği eşleşmesi yeterli
                }
            }
        }
        return byKeyId;
    }

    /** Konu = veren VE kendi anahtarıyla doğrulanıyor (algoritma desteklenmiyorsa ad eşitliği yeterli). */
    public static boolean isSelfSigned(X509Certificate c) {
        if (!c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) return false;
        try {
            c.verify(c.getPublicKey());
            return true;
        } catch (java.security.SignatureException | java.security.InvalidKeyException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isCa(X509Certificate c) {
        return c.getBasicConstraints() >= 0;
    }

    /**
     * Çevrim-dışı güven hükmü.
     *
     * @param chain {@code leaf} HARİÇ zincir (yapraktan köke)
     */
    public static Trust trust(TrustEvaluator evaluator, X509Certificate leaf, List<X509Certificate> chain) {
        X509Certificate top = chain.isEmpty() ? leaf : chain.get(chain.size() - 1);
        boolean endsSelfSigned = isSelfSigned(top);
        X509Certificate[] full = new X509Certificate[chain.size() + 1];
        full[0] = leaf;
        for (int i = 0; i < chain.size(); i++) full[i + 1] = chain.get(i);
        boolean trusted = false;
        if (evaluator != null) {
            try {
                trusted = evaluator.evaluate(full).trusted();
            } catch (Exception ignore) {
                trusted = false;
            }
        }
        boolean complete = endsSelfSigned || trusted;
        String missing = complete ? null : top.getIssuerX500Principal().getName();
        String status;
        if (trusted) status = "TRUSTED";
        else if (!complete) status = "UNKNOWN";
        else if (isCa(leaf) || !serverAuthCapable(leaf)) status = "UNKNOWN";
        else status = "UNTRUSTED";
        return new Trust(status, complete, missing);
    }

    static boolean serverAuthCapable(X509Certificate c) {
        try {
            List<String> eku = c.getExtendedKeyUsage();
            return eku == null || eku.contains(EKU_SERVER_AUTH) || eku.contains(EKU_ANY);
        } catch (Exception e) {
            return true;
        }
    }

    /** Açık anahtarın (SPKI) SHA-256'sı — büyük harf onaltılık. */
    public static String publicKeySha256(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getPublicKey().getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(X509Certificate c) {
        try {
            return Arrays.toString(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(c));
        }
    }

    private static byte[] authorityKeyId(X509Certificate c) {
        try {
            byte[] ext = c.getExtensionValue(Extension.authorityKeyIdentifier.getId());
            if (ext == null) return null;
            AuthorityKeyIdentifier aki = AuthorityKeyIdentifier.getInstance(ASN1OctetString.getInstance(ext).getOctets());
            return aki.getKeyIdentifier();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] subjectKeyId(X509Certificate c) {
        try {
            byte[] ext = c.getExtensionValue(Extension.subjectKeyIdentifier.getId());
            if (ext == null) return null;
            return SubjectKeyIdentifier.getInstance(ASN1OctetString.getInstance(ext).getOctets()).getKeyIdentifier();
        } catch (Exception e) {
            return null;
        }
    }
}
