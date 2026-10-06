package com.sitemonitor.service.manualcert;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Testte ÜRETİLEN sertifika/anahtar deposu fikstürleri (ikili fikstür dosyası yok, gerçek kurum verisi yok). Adlar
 * RFC 2606/6761 ayrılmış alanlarından ({@code example.test}).
 */
public final class TestCerts {

    private TestCerts() { }

    private static final AtomicLong SERIAL = new AtomicLong(System.nanoTime());
    private static KeyPair cachedRsa;

    public static KeyPair rsa() {
        if (cachedRsa == null) cachedRsa = rsa(2048);
        return cachedRsa;
    }

    public static KeyPair rsa(int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static KeyPair freshRsa() { return rsa(2048); }

    public static Instant days(long d) { return Instant.now().plus(d, ChronoUnit.DAYS); }

    /** Kendinden imzalı kök CA. */
    public static X509Certificate root(String cn, KeyPair kp, Instant from, Instant to) {
        return build(cn, kp.getPublic(), cn, kp.getPrivate(), null, from, to, true, null, "SHA256withRSA");
    }

    /** Ara CA ({@code issuer} imzalı). */
    public static X509Certificate intermediate(String cn, KeyPair kp, X509Certificate issuer, PrivateKey issuerKey,
                                        Instant from, Instant to) {
        return build(cn, kp.getPublic(), dnCn(issuer), issuerKey, issuer, from, to, true, null, "SHA256withRSA");
    }

    /** Uç (sunucu) sertifikası — EKU serverAuth, SAN. */
    public static X509Certificate leaf(String cn, List<String> san, KeyPair kp, X509Certificate issuer, PrivateKey issuerKey,
                                Instant from, Instant to) {
        return leaf(cn, san, kp, issuer, issuerKey, from, to, "SHA256withRSA");
    }

    public static X509Certificate leaf(String cn, List<String> san, KeyPair kp, X509Certificate issuer, PrivateKey issuerKey,
                                Instant from, Instant to, String sigAlg) {
        return build(cn, kp.getPublic(), dnCn(issuer), issuerKey, issuer, from, to, false, san, sigAlg);
    }

    /** Kendinden imzalı uç sertifika (CA değil). */
    public static X509Certificate selfSignedLeaf(String cn, List<String> san, KeyPair kp, Instant from, Instant to) {
        return build(cn, kp.getPublic(), cn, kp.getPrivate(), null, from, to, false, san, "SHA256withRSA");
    }

    private static String dnCn(X509Certificate c) {
        return c.getSubjectX500Principal().getName().replaceFirst("^CN=", "").split(",")[0];
    }

    private static X509Certificate build(String subjectCn, java.security.PublicKey pub, String issuerCn, PrivateKey signKey,
                                         X509Certificate issuerCert, Instant from, Instant to, boolean ca,
                                         List<String> san, String sigAlg) {
        try {
            X500Name subject = new X500Name("CN=" + subjectCn + ", O=Example Test");
            X500Name issuer = issuerCert != null
                    ? X500Name.getInstance(issuerCert.getSubjectX500Principal().getEncoded())
                    : subject;
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(from), Date.from(to), subject, pub);
            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pub));
            if (issuerCert != null) {
                b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(issuerCert));
            }
            if (ca) {
                b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
                b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            } else {
                b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                if (san != null && !san.isEmpty()) {
                    GeneralName[] names = san.stream().map(s -> new GeneralName(GeneralName.dNSName, s)).toArray(GeneralName[]::new);
                    b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
                }
            }
            ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(signKey);
            return new JcaX509CertificateConverter().getCertificate(b.build(signer));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String pem(X509Certificate... certs) {
        return CertificateFileParser.toPem(List.of(certs));
    }

    public static String pemBlock(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    public static byte[] der(X509Certificate c) {
        try { return c.getEncoded(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static byte[] pkcs12(String alias, PrivateKey key, char[] password, X509Certificate... chain) {
        return keystore("PKCS12", alias, key, password, null, chain);
    }

    /** {@code type}: JKS / JCEKS / BKS / PKCS12. {@code trusted}: ek güvenilen sertifika girdileri (alias → sertifika). */
    public static byte[] keystore(String type, String alias, PrivateKey key, char[] password,
                           Map<String, X509Certificate> trusted, X509Certificate... chain) {
        try {
            KeyStore ks = "BKS".equals(type) ? KeyStore.getInstance("BKS", new BouncyCastleProvider()) : KeyStore.getInstance(type);
            ks.load(null, null);
            if (key != null) ks.setKeyEntry(alias, key, password, chain);
            if (trusted != null) for (Map.Entry<String, X509Certificate> e : trusted.entrySet()) ks.setCertificateEntry(e.getKey(), e.getValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ks.store(out, password);
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static byte[] pkcs7(X509Certificate... certs) {
        try {
            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
            List<Certificate> list = new ArrayList<>(List.of(certs));
            gen.addCertificates(new JcaCertStore(list));
            CMSSignedData data = gen.generate(new CMSProcessableByteArray(new byte[0]), false);
            return data.getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static byte[] csr(String cn, List<String> san, KeyPair kp) {
        try {
            PKCS10CertificationRequestBuilder b = new JcaPKCS10CertificationRequestBuilder(
                    new X500Name("CN=" + cn + ", O=Example Test"), kp.getPublic());
            if (san != null && !san.isEmpty()) {
                ExtensionsGenerator eg = new ExtensionsGenerator();
                GeneralName[] names = san.stream().map(s -> new GeneralName(GeneralName.dNSName, s)).toArray(GeneralName[]::new);
                eg.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
                b.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, eg.generate());
            }
            return b.build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate())).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String privateKeyPem(KeyPair kp) {
        return pemBlock("PRIVATE KEY", kp.getPrivate().getEncoded());
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    z.putNextEntry(new ZipEntry(e.getKey()));
                    z.write(e.getValue());
                    z.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Yaprak + ara + kök; sırasıyla anahtarları ile. */
    public record Chain(X509Certificate root, KeyPair rootKey, X509Certificate inter, KeyPair interKey,
                 X509Certificate leaf, KeyPair leafKey) { }

    public static Chain chain(String leafCn, Instant leafTo) {
        KeyPair rk = freshRsa();
        X509Certificate root = root("Example Test Root CA", rk, days(-3650), days(3650));
        KeyPair ik = freshRsa();
        X509Certificate inter = intermediate("Example Test Issuing CA", ik, root, rk.getPrivate(), days(-1000), days(2000));
        KeyPair lk = freshRsa();
        X509Certificate leaf = leaf(leafCn, List.of(leafCn), lk, inter, ik.getPrivate(), days(-30), leafTo);
        return new Chain(root, rk, inter, ik, leaf, lk);
    }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
