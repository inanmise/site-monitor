package com.sitemonitor.service.certhierarchy;

import com.sitemonitor.service.manualcert.TestCerts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ağ sertifikası hiyerarşisi (2026-10-12): sunucunun sunduğu zincir → kök ilk düğümler; kök sunucudan gelmediyse güven
 * deposundan (sistem → kurumsal) imzayla bulunur; bulunamazsa "kök yok" dürüstçe söylenir; yanlış sıra / fazladan
 * sertifika / kendinden imzalı yaprak / boş girdi / tavan. Sertifikalar testte üretilir ({@code example.test}).
 */
class NetworkCertificateHierarchyTest {

    private static TestCerts.Chain chain;
    private static X509Certificate otherRoot;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(120));
        KeyPair k = TestCerts.freshRsa();
        otherRoot = TestCerts.root("Unrelated Test Root", k, days(-10), days(100));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> v) {
        return (List<Map<String, Object>>) v.get("nodes");
    }

    private static X509Certificate[] arr(X509Certificate... c) { return c; }

    @Test
    @DisplayName("sunucu yaprak + ara gönderdi, kök SİSTEM deposunda → kök ilk, ara, yaprak; kök kaynağı güven deposu")
    void rootFromSystemStore() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter()),
                List.of(otherRoot, chain.root()), List.of(), Instant.now());
        List<Map<String, Object>> n = nodes(v);
        assertThat(n).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(n).extracting(m -> m.get("source")).containsExactly("trust_store", "server", "server");
        assertThat(n.get(0)).containsEntry("trust_store", "system").containsEntry("self_signed", true);
        assertThat(n.get(1)).containsEntry("trust_store", null);
        assertThat(v).containsEntry("presented_count", 2).containsEntry("root_source", "trust_store")
                .containsEntry("trust_store", "system").containsEntry("order_ok", true).containsEntry("issuer_missing", false)
                .containsEntry("root_trusted", true).containsEntry("missing_kind", null);
        assertThat((List<?>) v.get("extra_certificates")).isEmpty();
        // Her düğüm kendi bitiş tarihini ve kalan gününü taşır
        assertThat(n).allSatisfy(m -> assertThat(m.get("not_after")).isNotNull());
        assertThat(((Number) n.get(2).get("days_remaining")).longValue()).isBetween(118L, 120L);
        assertThat(n.get(0).get("pem").toString()).startsWith("-----BEGIN CERTIFICATE-----");
    }

    @Test
    @DisplayName("kök yalnız kurumsal CA paketinde → kurumsal; iki depoda da yoksa en üst düğüm 'kök yok' (uydurulmaz)")
    void corporateStoreAndMissingRoot() {
        Map<String, Object> corp = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter()),
                List.of(otherRoot), List.of(chain.root()), Instant.now());
        assertThat(corp).containsEntry("trust_store", "corporate").containsEntry("root_source", "trust_store");
        assertThat(nodes(corp).get(0)).containsEntry("trust_store", "corporate");

        Map<String, Object> none = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter()),
                List.of(otherRoot), List.of(), Instant.now());
        List<Map<String, Object>> n = nodes(none);
        assertThat(n).extracting(m -> m.get("role")).containsExactly("intermediate", "leaf");
        assertThat(n.get(0)).containsEntry("issuer_missing", true).containsEntry("source", "server");
        assertThat(none).containsEntry("root_source", null).containsEntry("trust_store", null).containsEntry("issuer_missing", true)
                .containsEntry("missing_kind", "root").containsEntry("root_trusted", false);
        assertThat(none.get("missing_issuer_dn").toString()).contains("Example Test Root CA");
    }

    @Test
    @DisplayName("sunucu kökü de gönderdi → kök sunucudan (depodan ikinci kez eklenmez); depoda olduğu için güvenilir")
    void rootSentByServer() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter(), chain.root()),
                List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).hasSize(3).extracting(m -> m.get("source")).containsOnly("server");
        assertThat(v).containsEntry("root_source", "server").containsEntry("trust_store", null).containsEntry("root_trusted", true);
        assertThat((List<?>) v.get("extra_certificates")).isEmpty();
    }

    @Test
    @DisplayName("inceleme: sunucunun gönderdiği ama HİÇBİR depoda olmayan kök 'güvenilir' sayılmaz")
    void serverRootNotInStoreIsNotTrusted() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter(), chain.root()),
                List.of(otherRoot), List.of(), Instant.now());
        assertThat(v).containsEntry("root_source", "server").containsEntry("root_trusted", false);
    }

    @Test
    @DisplayName("inceleme: çapraz imzalı kök — tarayıcı gibi DEPODAKİ kökte durur; sunucunun çapraz imzası 'fazladan', sıra doğru")
    void crossSignedStopsAtTrustStoreRoot() {
        KeyPair oldKey = TestCerts.freshRsa();
        X509Certificate oldRoot = TestCerts.root("Old Test Root", oldKey, days(-5000), days(-20));   // süresi dolmuş eski kök
        KeyPair rk = TestCerts.freshRsa();
        X509Certificate root = TestCerts.root("Cross Test Root", rk, days(-500), days(4000));
        X509Certificate cross = TestCerts.intermediate("Cross Test Root", rk, oldRoot, oldKey.getPrivate(), days(-500), days(-20));
        KeyPair ik = TestCerts.freshRsa();
        X509Certificate inter = TestCerts.intermediate("Cross Test Issuing CA", ik, root, rk.getPrivate(), days(-100), days(900));
        X509Certificate leaf = TestCerts.leaf("cross.example.test", List.of("cross.example.test"), TestCerts.freshRsa(),
                inter, ik.getPrivate(), days(-10), days(80));
        // eski kök depoda OLSA da olmasa da sonuç aynı: kök = depodaki geçerli "Cross Test Root"
        for (List<X509Certificate> sys : List.of(List.of(root), List.of(root, oldRoot))) {
            Map<String, Object> v = NetworkCertificateHierarchy.build(arr(leaf, inter, cross), sys, List.of(), Instant.now());
            List<Map<String, Object>> n = nodes(v);
            assertThat(n).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
            assertThat(n.get(0)).containsEntry("source", "trust_store").containsEntry("expired", false);
            assertThat(v).containsEntry("root_source", "trust_store").containsEntry("root_trusted", true).containsEntry("order_ok", true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> extras = (List<Map<String, Object>>) v.get("extra_certificates");
            assertThat(extras).hasSize(1);
            assertThat(extras.get(0)).containsEntry("expired", true);
        }
    }

    @Test
    @DisplayName("inceleme: kurumsal pakette yalnız ARA sertifika → 'kök bulundu' DENMEZ (missing_kind=anchor); kök de varsa yukarı yürür")
    void intermediateAnchorIsNotARoot() {
        Map<String, Object> onlyInter = NetworkCertificateHierarchy.build(arr(chain.leaf()), List.of(), List.of(chain.inter()), Instant.now());
        List<Map<String, Object>> n = nodes(onlyInter);
        assertThat(n).extracting(m -> m.get("role")).containsExactly("intermediate", "leaf");
        assertThat(n.get(0)).containsEntry("source", "trust_store").containsEntry("trust_store", "corporate").containsEntry("issuer_missing", true);
        assertThat(onlyInter).containsEntry("root_source", null).containsEntry("missing_kind", "anchor").containsEntry("root_trusted", false);

        Map<String, Object> both = NetworkCertificateHierarchy.build(arr(chain.leaf()), List.of(), List.of(chain.inter(), chain.root()), Instant.now());
        assertThat(nodes(both)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(nodes(both)).extracting(m -> m.get("source")).containsExactly("trust_store", "trust_store", "server");
        assertThat(both).containsEntry("root_source", "trust_store").containsEntry("trust_store", "corporate").containsEntry("root_trusted", true);
    }

    @Test
    @DisplayName("inceleme: sunucu yalnız yaprağı gönderdi, depoda veren yok → missing_kind=intermediate (kök değil ara eksik)")
    void missingIntermediate() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf()), List.of(otherRoot), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("leaf");
        assertThat(v).containsEntry("missing_kind", "intermediate").containsEntry("issuer_missing", true);
        assertThat(v.get("missing_issuer_dn").toString()).contains("Example Test Issuing CA");
    }

    @Test
    @DisplayName("inceleme: aynı ad + anahtarla iki depo kökü → şu an GEÇERLİ olan seçilir (karma sıradan bağımsız)")
    void prefersValidAnchor() {
        KeyPair rk = TestCerts.freshRsa();
        X509Certificate valid = TestCerts.root("Twin Test Root", rk, days(-100), days(3000));
        X509Certificate expired = TestCerts.root("Twin Test Root", rk, days(-4000), days(-30));
        KeyPair ik = TestCerts.freshRsa();
        X509Certificate inter = TestCerts.intermediate("Twin Test Issuing CA", ik, valid, rk.getPrivate(), days(-50), days(500));
        X509Certificate leaf = TestCerts.leaf("twin.example.test", List.of("twin.example.test"), TestCerts.freshRsa(),
                inter, ik.getPrivate(), days(-5), days(60));
        for (List<X509Certificate> sys : List.of(List.of(expired, valid), List.of(valid, expired))) {
            Map<String, Object> v = NetworkCertificateHierarchy.build(arr(leaf, inter), sys, List.of(), Instant.now());
            assertThat(nodes(v).get(0)).containsEntry("expired", false);
        }
    }

    @Test
    @DisplayName("yanlış sıra (yaprak, kök, ara) → yol yine doğru kurulur, order_ok=false; ilgisiz sertifika 'fazladan'")
    void wrongOrderAndExtras() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.root(), chain.inter(), otherRoot),
                List.of(), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(v).containsEntry("order_ok", false).containsEntry("root_source", "server");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extras = (List<Map<String, Object>>) v.get("extra_certificates");
        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).get("subject_dn").toString()).contains("Unrelated Test Root");
        assertThat(extras.get(0)).containsKeys("not_after", "days_remaining", "expired");
    }

    @Test
    @DisplayName("kendinden imzalı yaprak → tek düğüm 'leaf', kök kaynağı self_signed, 'kök yok' DEĞİL")
    void selfSignedLeaf() {
        X509Certificate ss = TestCerts.selfSignedLeaf("self.example.test", List.of("self.example.test"),
                TestCerts.freshRsa(), days(-1), days(30));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(ss), List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("leaf");
        assertThat(v).containsEntry("root_source", "self_signed").containsEntry("issuer_missing", false);
    }

    @Test
    @DisplayName("süresi dolmuş ara → o düğümde expired + NEGATİF kalan gün (sıfıra kırpılmaz)")
    void expiredIntermediateKeepsNegativeDays() {
        KeyPair rk = TestCerts.freshRsa();
        X509Certificate root = TestCerts.root("Old Test Root", rk, days(-4000), days(3000));
        KeyPair ik = TestCerts.freshRsa();
        X509Certificate inter = TestCerts.intermediate("Old Test Issuing CA", ik, root, rk.getPrivate(), days(-900), days(-5));
        X509Certificate leaf = TestCerts.leaf("old.example.test", List.of("old.example.test"), TestCerts.freshRsa(),
                inter, ik.getPrivate(), days(-30), days(60));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(leaf, inter), List.of(root), List.of(), Instant.now());
        Map<String, Object> mid = nodes(v).get(1);
        assertThat(mid).containsEntry("role", "intermediate").containsEntry("expired", true);
        assertThat(((Number) mid.get("days_remaining")).longValue()).isLessThan(0);
    }

    @Test
    @DisplayName("boş / null girdi → boş görünüm; dışarıdan gelen liste en çok MAX_PRESENTED işlenir")
    void emptyAndCap() {
        assertThat(NetworkCertificateHierarchy.build(null, List.of(), List.of(), Instant.now()))
                .containsEntry("presented_count", 0).containsEntry("certificate_count", 0);
        List<X509Certificate> many = new ArrayList<>();
        many.add(chain.leaf());
        for (int i = 0; i < 40; i++) many.add(TestCerts.root("Extra Test Root " + i, TestCerts.rsa(), days(-1), days(10 + i)));
        Map<String, Object> v = NetworkCertificateHierarchy.build(many.toArray(new X509Certificate[0]),
                List.of(), List.of(), Instant.now());
        assertThat(v).containsEntry("presented_count", NetworkCertificateHierarchy.MAX_PRESENTED);
        assertThat((List<?>) v.get("extra_certificates")).hasSize(NetworkCertificateHierarchy.MAX_EXTRAS);   // farklı 19 → tavan 10
    }

    @Test
    @DisplayName("inceleme 2: yaprağı iki kez gönderen sunucu (cert + fullchain) 'yanlış sıra' sayılmaz; tekrar fazladan da değil")
    void duplicatedLeafIsNotWrongOrder() {
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.leaf(), chain.inter()),
                List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(v).containsEntry("order_ok", true);
        assertThat((List<?>) v.get("extra_certificates")).isEmpty();
    }

    @Test
    @DisplayName("inceleme 2: CA bayraklı sunucu sertifikası ağaçta YAPRAK (ağaç yapraksız kalmaz)")
    void caLeafIsShownAsLeaf() {
        X509Certificate caLeaf = TestCerts.intermediate("appliance.example.test", TestCerts.freshRsa(), chain.inter(),
                chain.interKey().getPrivate(), days(-5), days(90));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(caLeaf, chain.inter()), List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
    }

    @Test
    @DisplayName("inceleme 4: CA OLMAYAN sertifika (son kullanıcı) imzalasa da veren olarak zincire girmez → 'yanlış ara sertifika'")
    void nonCaIssuerIsNotUsed() {
        KeyPair signerKey = TestCerts.freshRsa();
        X509Certificate signerLeaf = TestCerts.leaf("Rogue Signer", List.of("rogue.example.test"), signerKey,
                chain.inter(), chain.interKey().getPrivate(), days(-5), days(90));
        X509Certificate forged = TestCerts.leaf("api.example.test", List.of("api.example.test"), TestCerts.freshRsa(),
                signerLeaf, signerKey.getPrivate(), days(-1), days(30));
        assertThat(NetworkCertificateHierarchy.canIssue(signerLeaf)).isFalse();
        assertThat(NetworkCertificateHierarchy.canIssue(chain.inter())).isTrue();
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(forged, signerLeaf, chain.inter()),
                List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("leaf");
        assertThat(v).containsEntry("missing_kind", "wrong_intermediate").containsEntry("issuer_missing", true)
                .containsEntry("root_source", null).containsEntry("root_trusted", false);
    }

    @Test
    @DisplayName("inceleme 5: depodaki KENDİNDEN İMZALI kök CA uzantısı taşımasa da (eski kurumsal kök) güven çapasıdır — PKIX gibi")
    void storeRootWithoutCaExtensionsIsAnAnchor() {
        KeyPair rootKey = TestCerts.freshRsa();
        // basicConstraints yok, keyUsage keyCertSign içermiyor: canIssue false
        X509Certificate legacyRoot = TestCerts.selfSignedLeaf("Legacy Corp Root", null, rootKey, days(-100), days(1000));
        assertThat(NetworkCertificateHierarchy.canIssue(legacyRoot)).isFalse();
        KeyPair interKey = TestCerts.freshRsa();
        X509Certificate inter = TestCerts.intermediate("Legacy Corp Issuing", interKey, legacyRoot, rootKey.getPrivate(),
                days(-50), days(500));
        X509Certificate leaf = TestCerts.leaf("intra.example.test", List.of("intra.example.test"), TestCerts.freshRsa(),
                inter, interKey.getPrivate(), days(-1), days(60));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(leaf, inter), List.of(), List.of(legacyRoot), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(v).containsEntry("root_source", "trust_store").containsEntry("trust_store", "corporate")
                .containsEntry("root_trusted", true).containsEntry("missing_kind", null);
    }

    @Test
    @DisplayName("inceleme 5: imza algoritması bu JVM'de doğrulanamıyorsa ad eşleşen veren 'imzalıyor' sayılır (yanlış ara denmez)")
    void unsupportedSignatureAlgorithmCountsAsSigned() throws Exception {
        X509Certificate cert = org.mockito.Mockito.mock(X509Certificate.class);
        org.mockito.Mockito.when(cert.getIssuerX500Principal()).thenReturn(chain.inter().getSubjectX500Principal());
        org.mockito.Mockito.doThrow(new java.security.NoSuchAlgorithmException("GOST"))
                .when(cert).verify(org.mockito.ArgumentMatchers.any(java.security.PublicKey.class));
        assertThat(NetworkCertificateHierarchy.signs(chain.inter(), cert)).isTrue();
        // ad eşleşmezse yine hayır; gerçek imza hatası da hayır
        assertThat(NetworkCertificateHierarchy.signs(chain.root(), cert)).isFalse();
        org.mockito.Mockito.doThrow(new java.security.SignatureException("bozuk"))
                .when(cert).verify(org.mockito.ArgumentMatchers.any(java.security.PublicKey.class));
        assertThat(NetworkCertificateHierarchy.signs(chain.inter(), cert)).isFalse();
    }

    @Test
    @DisplayName("inceleme 6: imzası doğrulanamayan bağ yol kurar ama GÜVEN kararına girmez — hasUnverifiedLink; doğrulanmış zincirde "
            + "root_trusted ve signature_unverified=false")
    void unverifiableLinkIsNeverTrusted() throws Exception {
        X509Certificate cert = org.mockito.Mockito.mock(X509Certificate.class);
        org.mockito.Mockito.when(cert.getIssuerX500Principal()).thenReturn(chain.inter().getSubjectX500Principal());
        org.mockito.Mockito.doThrow(new java.security.NoSuchAlgorithmException("GOST"))
                .when(cert).verify(org.mockito.ArgumentMatchers.any(java.security.PublicKey.class));
        assertThat(NetworkCertificateHierarchy.signatureCheck(chain.inter(), cert)).isNull();
        assertThat(NetworkCertificateHierarchy.signatureCheck(chain.root(), cert)).isFalse();
        assertThat(NetworkCertificateHierarchy.signatureCheck(chain.inter(), chain.leaf())).isTrue();
        assertThat(NetworkCertificateHierarchy.hasUnverifiedLink(List.of(cert, chain.inter(), chain.root()))).isTrue();
        assertThat(NetworkCertificateHierarchy.hasUnverifiedLink(List.of(chain.leaf(), chain.inter(), chain.root()))).isFalse();
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter()), List.of(chain.root()), List.of(), Instant.now());
        assertThat(v).containsEntry("root_trusted", true).containsEntry("signature_unverified", false);
    }

    @Test
    @DisplayName("inceleme 4: sunucu veren ADINDA ama başka anahtarlı ara gönderdi → missing_kind=wrong_intermediate (eksik değil yanlış)")
    void wrongIntermediateSameNameOtherKey() {
        X509Certificate wrongInter = TestCerts.intermediate("Example Test Issuing CA", TestCerts.freshRsa(), chain.root(),
                chain.rootKey().getPrivate(), days(-10), days(500));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(chain.leaf(), wrongInter), List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("leaf");
        assertThat(v).containsEntry("missing_kind", "wrong_intermediate").containsEntry("issuer_missing", true);
        // doğru ara da gönderildiyse yol doğru kurulur; yanlış olan 'fazladan'
        Map<String, Object> ok = NetworkCertificateHierarchy.build(arr(chain.leaf(), wrongInter, chain.inter()),
                List.of(chain.root()), List.of(), Instant.now());
        assertThat(nodes(ok)).extracting(m -> m.get("role")).containsExactly("root", "intermediate", "leaf");
        assertThat(ok).containsEntry("missing_kind", null);
        assertThat((List<?>) ok.get("extra_certificates")).hasSize(1);
    }

    @Test
    @DisplayName("inceleme 4: TEK başına CA bayraklı sunucu sertifikası, veren yok → eksik olan ARA (kök değil)")
    void loneCaLeafMissingIntermediate() {
        X509Certificate caLeaf = TestCerts.intermediate("appliance.example.test", TestCerts.freshRsa(), chain.inter(),
                chain.interKey().getPrivate(), days(-5), days(90));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(caLeaf), List.of(otherRoot), List.of(), Instant.now());
        assertThat(nodes(v)).extracting(m -> m.get("role")).containsExactly("leaf");
        assertThat(v).containsEntry("missing_kind", "intermediate");
        // ara gönderilmiş, kök hiçbir depoda yok → eksik olan KÖK
        Map<String, Object> noRoot = NetworkCertificateHierarchy.build(arr(chain.leaf(), chain.inter()), List.of(otherRoot), List.of(), Instant.now());
        assertThat(noRoot).containsEntry("missing_kind", "root");
    }

    @Test
    @DisplayName("inceleme 2: güven deposundaki kendinden imzalı yaprak → self_signed + root_trusted")
    void selfSignedLeafInStoreIsTrusted() {
        X509Certificate ss = TestCerts.selfSignedLeaf("pinned.example.test", List.of("pinned.example.test"),
                TestCerts.freshRsa(), days(-1), days(30));
        Map<String, Object> v = NetworkCertificateHierarchy.build(arr(ss), List.of(), List.of(ss), Instant.now());
        assertThat(v).containsEntry("root_source", "self_signed").containsEntry("root_trusted", true);
    }

    @Test
    @DisplayName("inceleme 2: depoda birbirini imzalayan iki ara sertifika (A ↔ B) — yürüyüş döngüye girmez, sonlanır")
    void storeCrossSignCycleTerminates() {
        KeyPair ka = TestCerts.freshRsa();
        KeyPair kb = TestCerts.freshRsa();
        X509Certificate b0 = TestCerts.root("Cycle Test B", kb, days(-10), days(100));
        X509Certificate a = TestCerts.intermediate("Cycle Test A", ka, b0, kb.getPrivate(), days(-10), days(100));
        X509Certificate b = TestCerts.intermediate("Cycle Test B", kb, a, ka.getPrivate(), days(-10), days(100));
        X509Certificate leaf = TestCerts.leaf("cycle.example.test", List.of("cycle.example.test"), TestCerts.freshRsa(),
                a, ka.getPrivate(), days(-5), days(60));
        Map<String, Object> v = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> NetworkCertificateHierarchy.build(arr(leaf), List.of(), List.of(a, b), Instant.now()));
        assertThat(nodes(v)).hasSizeLessThanOrEqualTo(NetworkCertificateHierarchy.MAX_DEPTH + 1);
        assertThat(v).containsEntry("root_source", null).containsEntry("missing_kind", "anchor");
    }
}
