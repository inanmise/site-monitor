package com.sitemonitor.service.manualcert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarayıcı gibi sertifika hiyerarşisi (2026-10-07): saklanan zincir (baş ilk) → KÖK İLK düğümler; roller, "kök dosyada
 * yok" işareti, alanlar ve düğüm başına TEK açık sertifika PEM'i. Sertifikalar testte üretilir ({@code example.test}).
 */
class ManualCertificateHierarchyTest {

    private static TestCerts.Chain chain;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("nodes");
    }

    private static List<Object> roles(List<Map<String, Object>> nodes) {
        return nodes.stream().map(n -> n.get("role")).toList();
    }

    @Test
    @DisplayName("tam zincir (yaprak, ara, kök saklı) → kök ilk, ara, yaprak son; derinlik/konum; baş yalnız yaprak; kök dosyada")
    void fullChain_rootFirst() {
        Map<String, Object> view = ManualCertificateHierarchy.view(List.of(chain.leaf(), chain.inter(), chain.root()), Instant.now());
        List<Map<String, Object>> n = nodes(view);
        assertThat(roles(n)).containsExactly("root", "intermediate", "leaf");
        assertThat(n).extracting(m -> m.get("depth")).containsExactly(0, 1, 2);
        assertThat(n).extracting(m -> m.get("position")).containsExactly(2, 1, 0);
        assertThat(n).extracting(m -> m.get("head")).containsExactly(false, false, true);
        assertThat(n.get(0)).containsEntry("self_signed", true).containsEntry("issuer_missing", false);
        assertThat(n.get(2).get("subject_dn").toString()).contains("CN=api.example.test");
        assertThat(view).containsEntry("certificate_count", 3).containsEntry("issuer_missing", false)
                .containsEntry("missing_issuer_dn", null);
    }

    @Test
    @DisplayName("yalnız yaprak yüklendi → tek düğüm (yaprak) + issuer_missing (düğümde ve gövdede), eksik verenin DN'i")
    void leafOnly_issuerMissing() {
        Map<String, Object> view = ManualCertificateHierarchy.view(List.of(chain.leaf()), Instant.now());
        List<Map<String, Object>> n = nodes(view);
        assertThat(roles(n)).containsExactly("leaf");
        assertThat(n.get(0)).containsEntry("issuer_missing", true).containsEntry("self_signed", false).containsEntry("head", true);
        assertThat(view).containsEntry("issuer_missing", true);
        assertThat(view.get("missing_issuer_dn").toString()).contains("CN=Example Test Issuing CA");
    }

    @Test
    @DisplayName("yaprak + ara (kök yok) → en üstteki ara 'dosyadaki en üst veren' (issuer_missing), yaprak altında")
    void leafAndIntermediate_topIsIntermediate() {
        Map<String, Object> view = ManualCertificateHierarchy.view(List.of(chain.leaf(), chain.inter()), Instant.now());
        List<Map<String, Object>> n = nodes(view);
        assertThat(roles(n)).containsExactly("intermediate", "leaf");
        assertThat(n.get(0)).containsEntry("issuer_missing", true);
        assertThat(n.get(1)).containsEntry("issuer_missing", false);
        assertThat(view.get("missing_issuer_dn").toString()).contains("CN=Example Test Root CA");
    }

    @Test
    @DisplayName("truststore başı bir CA (ara + kök) → kök + ara (baş); 'yaprak' rolü yok")
    void caHead_noLeafRole() {
        List<Map<String, Object>> n = nodes(ManualCertificateHierarchy.view(List.of(chain.inter(), chain.root()), Instant.now()));
        assertThat(roles(n)).containsExactly("root", "intermediate");
        assertThat(n.get(1)).containsEntry("head", true).containsEntry("is_ca", true);
    }

    @Test
    @DisplayName("tek kendinden imzalı CA → kök; tek kendinden imzalı sunucu sertifikası → yaprak (eksik veren yok)")
    void selfSignedSingles() {
        List<Map<String, Object>> rootOnly = nodes(ManualCertificateHierarchy.view(List.of(chain.root()), Instant.now()));
        assertThat(roles(rootOnly)).containsExactly("root");
        assertThat(rootOnly.get(0)).containsEntry("issuer_missing", false).containsEntry("head", true);

        KeyPair kp = TestCerts.rsa();
        X509Certificate self = TestCerts.selfSignedLeaf("self.example.test", List.of("self.example.test"), kp, days(-1), days(90));
        Map<String, Object> view = ManualCertificateHierarchy.view(List.of(self), Instant.now());
        assertThat(roles(nodes(view))).containsExactly("leaf");
        assertThat(nodes(view).get(0)).containsEntry("self_signed", true).containsEntry("issuer_missing", false);
        assertThat(view).containsEntry("issuer_missing", false);
    }

    @Test
    @DisplayName("alanlar: konu/düzenleyen parçaları, seri, tarihler, kalan gün, algoritmalar, SAN (DNS + IP), kullanımlar, kısıtlar, parmak izleri")
    void fields() throws Exception {
        KeyPair rk = TestCerts.rsa();
        X509Certificate root = build("CN=Fields Root CA, O=Example Test, C=TR", rk.getPublic(), null, rk.getPrivate(),
                days(-3650), days(3650), -1, null);
        KeyPair ik = TestCerts.freshRsa();
        X509Certificate inter = build("CN=Fields Issuing CA, OU=PKI, O=Example Test, C=TR", ik.getPublic(), root,
                rk.getPrivate(), days(-100), days(1000), 0, null);
        KeyPair lk = TestCerts.freshRsa();
        X509Certificate leaf = build("CN=fields.example.test, OU=Ops, OU=Platform, O=Example Test, L=Istanbul, ST=Marmara, C=TR",
                lk.getPublic(), inter, ik.getPrivate(), days(-10), days(45), null,
                List.of(new GeneralName(GeneralName.dNSName, "fields.example.test"),
                        new GeneralName(GeneralName.dNSName, "www.fields.example.test"),
                        new GeneralName(GeneralName.iPAddress, "192.0.2.10")));

        List<Map<String, Object>> n = nodes(ManualCertificateHierarchy.view(List.of(leaf, inter, root), Instant.now()));
        Map<String, Object> l = n.get(2);
        @SuppressWarnings("unchecked") Map<String, Object> subject = (Map<String, Object>) l.get("subject");
        assertThat(subject).containsEntry("cn", "fields.example.test").containsEntry("o", "Example Test")
                .containsEntry("l", "Istanbul").containsEntry("st", "Marmara").containsEntry("c", "TR");
        assertThat(subject.get("ou").toString()).contains("Ops").contains("Platform");
        @SuppressWarnings("unchecked") Map<String, Object> issuer = (Map<String, Object>) l.get("issuer");
        assertThat(issuer).containsEntry("cn", "Fields Issuing CA").containsEntry("ou", "PKI").containsEntry("l", null);
        assertThat(l.get("issuer_dn").toString()).contains("CN=Fields Issuing CA");
        assertThat(l.get("serial_number")).isEqualTo(leaf.getSerialNumber().toString(16).toUpperCase());
        assertThat(l.get("not_before").toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(l.get("not_after").toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(((Number) l.get("days_remaining")).longValue()).isBetween(43L, 45L);
        assertThat(l).containsEntry("expired", false).containsEntry("not_yet_valid", false)
                .containsEntry("signature_algorithm", "SHA256withRSA").containsEntry("public_key_algorithm", "RSA")
                .containsEntry("public_key_size", 2048).containsEntry("is_ca", false).containsEntry("path_length", null);
        assertThat(l.get("san")).isEqualTo(List.of("fields.example.test", "www.fields.example.test", "192.0.2.10"));
        assertThat(l.get("key_usage")).isEqualTo(List.of("Digital Signature", "Key Encipherment"));
        assertThat(l.get("ext_key_usage")).isEqualTo(List.of("TLS Web Server"));
        assertThat(l.get("sha256_fingerprint")).isEqualTo(ManualCertificateAnalyzer.fingerprint(leaf));
        assertThat(l.get("sha256_fingerprint").toString()).matches("[0-9A-F]{64}");
        assertThat(l.get("sha1_fingerprint").toString()).matches("[0-9A-F]{40}");

        assertThat(n.get(1)).containsEntry("is_ca", true).containsEntry("path_length", 0)
                .containsEntry("key_usage", List.of("Certificate Signing", "CRL Signing")).containsEntry("san", List.of());
        assertThat(n.get(0)).containsEntry("is_ca", true).containsEntry("path_length", null);   // sınırsız
    }

    @Test
    @DisplayName("her düğüm yalnız KENDİ açık sertifikasının PEM'ini taşır (geri okununca aynı sertifika)")
    void singleCertificatePemPerNode() {
        List<X509Certificate> stored = List.of(chain.leaf(), chain.inter(), chain.root());
        List<Map<String, Object>> n = nodes(ManualCertificateHierarchy.view(stored, Instant.now()));
        for (int depth = 0; depth < n.size(); depth++) {
            String pem = n.get(depth).get("pem").toString();
            assertThat(pem).startsWith("-----BEGIN CERTIFICATE-----");
            assertThat(pem.split("BEGIN CERTIFICATE", -1)).hasSize(2);   // tam bir blok
            assertThat(pem).doesNotContain("PRIVATE KEY");
            List<X509Certificate> back = CertificateFileParser.readPemChain(pem);
            assertThat(back).hasSize(1);
            assertThat(back.get(0)).isEqualTo(stored.get(stored.size() - 1 - depth));
        }
    }

    @Test
    @DisplayName("süresi dolmuş ara sertifika → expired + negatif kalan gün; boş/null girdi → boş görünüm")
    void expiredAndEmpty() {
        KeyPair rk = TestCerts.rsa();
        X509Certificate root = TestCerts.root("Old Root CA", rk, days(-4000), days(3000));
        KeyPair ik = TestCerts.freshRsa();
        X509Certificate oldInter = TestCerts.intermediate("Old Issuing CA", ik, root, rk.getPrivate(), days(-800), days(-5));
        List<Map<String, Object>> n = nodes(ManualCertificateHierarchy.view(List.of(oldInter, root), Instant.now()));
        assertThat(n.get(1)).containsEntry("expired", true);
        assertThat(((Number) n.get(1).get("days_remaining")).longValue()).isNegative();

        assertThat(nodes(ManualCertificateHierarchy.view(List.of(), Instant.now()))).isEmpty();
        assertThat(ManualCertificateHierarchy.nodes(null, Instant.now())).isEmpty();
        assertThat(ManualCertificateHierarchy.view(null, Instant.now())).containsEntry("certificate_count", 0)
                .containsEntry("issuer_missing", false);
    }

    /** Zengin konu adı, yol uzunluğu ve SAN (DNS + IP) taşıyan fikstür. {@code pathLen}: null = CA değil, -1 = sınırsız CA. */
    private static X509Certificate build(String subjectDn, java.security.PublicKey pub, X509Certificate issuer,
                                         PrivateKey signKey, Instant from, Instant to, Integer pathLen,
                                         List<GeneralName> san) throws Exception {
        X500Name subject = new X500Name(subjectDn);
        X500Name issuerName = issuer != null ? X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded()) : subject;
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuerName,
                BigInteger.valueOf(System.nanoTime()), Date.from(from), Date.from(to), subject, pub);
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pub));
        if (issuer != null) b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(issuer));
        if (pathLen != null) {
            b.addExtension(Extension.basicConstraints, true, pathLen < 0 ? new BasicConstraints(true) : new BasicConstraints(pathLen));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            if (san != null && !san.isEmpty()) {
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san.toArray(GeneralName[]::new)));
            }
        }
        return new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withRSA").build(signKey)));
    }
}
