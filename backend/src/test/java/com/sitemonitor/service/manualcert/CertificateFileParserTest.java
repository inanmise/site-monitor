package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.manualcert.CertificateFileParser.Result;
import com.sitemonitor.service.manualcert.CertificateFileParser.Warning;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manuel sertifika dosyası ayrıştırıcısı (2026-10-06): biçim İÇERİKTEN tanınır; ZIP sınırları ve bomba koruması.
 *
 * <p>2026-10-08 (kullanıcı isteği: özel anahtar sunucuya gelmez): ham yolda PKCS#12, JKS / JCEKS / BKS ve her türden özel
 * anahtar (ZIP içinde de) yalnız TANINIR — {@code privateMaterial} işaretlenir, hiçbir sertifika kullanılmaz (çağıran
 * 400 döner); parola parametresi yoktur. Ayıklanmış yükleme {@link CertificateFileParser#fromExtracted}. Fikstürler
 * testte üretilir.
 */
class CertificateFileParserTest {

    private static Chain chain;
    private static final char[] PW = "test-parola".toCharArray();

    @BeforeAll
    static void setUp() {
        chain = TestCerts.chain("api.example.test", days(200));
    }

    private static List<String> codes(Result r) {
        return r.warnings().stream().map(Warning::code).toList();
    }

    private static Warning warning(Result r, String code) {
        return r.warnings().stream().filter(w -> w.code().equals(code)).findFirst().orElse(null);
    }

    private static List<X509Certificate> certs(Result r) {
        return r.certs().stream().map(CertificateFileParser.ParsedCert::cert).toList();
    }

    // ── PEM ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PEM: çoklu CERTIFICATE bloğu sırasıyla okunur; biçim PEM; gizli malzeme yok")
    void pem_multipleCertificates() {
        Result r = CertificateFileParser.parse(utf8(pem(chain.leaf(), chain.inter(), chain.root())), "tam-zincir.pem", false);
        assertThat(r.format()).isEqualTo("PEM");
        assertThat(certs(r)).containsExactly(chain.leaf(), chain.inter(), chain.root());
        assertThat(codes(r)).isEmpty();
        assertThat(r.fileName()).isEqualTo("tam-zincir.pem");
        assertThat(r.privateMaterial()).isNull();
    }

    @Test
    @DisplayName("PEM: her türden PRIVATE KEY bloğu (kapanışı bozuk olsa da) gizli malzemedir — sertifikalar da KULLANILMAZ")
    void pem_privateKeysRejected() {
        KeyPair kp = rsa();
        String text = privateKeyPem(kp)
                + pemBlock("RSA PRIVATE KEY", new byte[] {1, 2, 3})
                + pemBlock("EC PRIVATE KEY", new byte[] {4, 5})
                + pemBlock("ENCRYPTED PRIVATE KEY", new byte[] {6})
                + pemBlock("OPENSSH PRIVATE KEY", new byte[] {7})
                + "-----BEGIN DSA PRIVATE KEY-----\nAAAA\n"            // kapanışsız
                + pem(chain.leaf());
        Result r = CertificateFileParser.parse(utf8(text), "anahtar-ve-sertifika.pem", false);
        assertThat(r.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PRIVATE_KEY);
        assertThat(r.privateKeyCount()).isEqualTo(6);
        assertThat(r.certs()).isEmpty();
        assertThat(codes(r)).doesNotContain("PRIVATE_KEY_KEPT_LOCAL");
    }

    @Test
    @DisplayName("PEM: TRUSTED CERTIFICATE (OpenSSL güven ek bilgili) okunur")
    void pem_trustedCertificate() {
        byte[] withAux = concat(der(chain.root()), new byte[] {0x30, 0x00});   // DER sertifika + boş CertAux
        Result r = CertificateFileParser.parse(utf8(pemBlock("TRUSTED CERTIFICATE", withAux)), "trusted.pem", false);
        assertThat(certs(r)).containsExactly(chain.root());
    }

    @Test
    @DisplayName("PEM: CERTIFICATE REQUEST → csr bloğu + CSR_NOT_CERTIFICATE (sertifika yok)")
    void pem_csrDetected() {
        KeyPair kp = rsa();
        String text = pemBlock("CERTIFICATE REQUEST", csr("csr.example.test", List.of("csr.example.test", "www.csr.example.test"), kp));
        Result r = CertificateFileParser.parse(utf8(text), "istek.csr", false);
        assertThat(r.certs()).isEmpty();
        assertThat(r.csr()).isNotNull();
        assertThat(r.csr().cn()).isEqualTo("csr.example.test");
        assertThat(r.csr().san()).containsExactly("csr.example.test", "www.csr.example.test");
        assertThat(r.csr().keyAlg()).isEqualTo("RSA");
        assertThat(r.csr().keySize()).isEqualTo(2048);
        assertThat(r.csr().signatureAlgorithm()).containsIgnoringCase("SHA256");
        Warning w = warning(r, "CSR_NOT_CERTIFICATE");
        assertThat(w).isNotNull();
        assertThat(w.severity()).isEqualTo("error");
        assertThat(w.params()).containsEntry("cn", "csr.example.test");
        assertThat(codes(r)).doesNotContain("NO_CERTIFICATE");
    }

    @Test
    @DisplayName("PEM bloğu içinde PKCS7 okunur")
    void pem_pkcs7Block() {
        Result r = CertificateFileParser.parse(utf8(pemBlock("PKCS7", pkcs7(chain.leaf(), chain.inter()))), "zincir.p7b", false);
        assertThat(r.format()).isEqualTo("PEM");
        assertThat(certs(r)).containsExactlyInAnyOrder(chain.leaf(), chain.inter());
    }

    @Test
    @DisplayName("Yapıştırılan metin: biçim TEXT, dosya adı yok")
    void pasted_isText() {
        Result r = CertificateFileParser.parse(utf8("  \n" + pem(chain.leaf())), "yoksay.pem", true);
        assertThat(r.format()).isEqualTo("TEXT");
        assertThat(r.fileName()).isNull();
        assertThat(certs(r)).containsExactly(chain.leaf());
    }

    // ── DER / Base64 / PKCS7 ─────────────────────────────────────────────────

    @Test
    @DisplayName("DER X.509 içerikten tanınır (uzantı .txt olsa bile)")
    void der_detectedByContent() {
        Result r = CertificateFileParser.parse(der(chain.leaf()), "yanlis-uzanti.txt", false);
        assertThat(r.format()).isEqualTo("DER");
        assertThat(certs(r)).containsExactly(chain.leaf());
    }

    @Test
    @DisplayName("Zırhsız Base64 DER okunur")
    void base64WithoutArmour() {
        String b64 = Base64.getMimeEncoder().encodeToString(der(chain.leaf()));
        Result r = CertificateFileParser.parse(utf8(b64), "sertifika.cer", false);
        assertThat(certs(r)).containsExactly(chain.leaf());
        assertThat(r.format()).isEqualTo("DER");
    }

    @Test
    @DisplayName("PKCS#7 (DER, .p7b) tüm sertifikaları verir")
    void pkcs7_der() {
        Result r = CertificateFileParser.parse(pkcs7(chain.leaf(), chain.inter(), chain.root()), "zincir.p7b", false);
        assertThat(r.format()).isEqualTo("PKCS7");
        assertThat(certs(r)).containsExactlyInAnyOrder(chain.leaf(), chain.inter(), chain.root());
        assertThat(r.privateMaterial()).isNull();
    }

    @Test
    @DisplayName("DER CSR: csr + CSR_NOT_CERTIFICATE")
    void der_csr() {
        Result r = CertificateFileParser.parse(csr("der.example.test", List.of(), rsa()), "istek.der", false);
        assertThat(r.csr()).isNotNull();
        assertThat(codes(r)).contains("CSR_NOT_CERTIFICATE");
    }

    @Test
    @DisplayName("DER özel anahtar (PKCS#8 düz ve şifreli) yalnız ŞEKLİNDEN tanınır → gizli malzeme")
    void der_privateKeyDetected() {
        KeyPair kp = rsa();
        Result plain = CertificateFileParser.parse(kp.getPrivate().getEncoded(), "anahtar.key", false);
        assertThat(plain.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PRIVATE_KEY);
        // EncryptedPrivateKeyInfo şekli: SEQUENCE { AlgorithmIdentifier, OCTET STRING }
        byte[] enc = new byte[] {0x30, 0x14, 0x30, 0x0B, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01,
                0x05, 0x0D, 0x04, 0x05, 1, 2, 3, 4, 5};
        Result encrypted = CertificateFileParser.parse(enc, "anahtar-sifreli.der", false);
        assertThat(encrypted.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PRIVATE_KEY);
        assertThat(encrypted.certs()).isEmpty();
    }

    // ── Anahtar depoları: yalnız TANINIR, açılmaz ────────────────────────────

    @Test
    @DisplayName("PKCS#12: parolasız da parolalı da AÇILMAZ — gizli malzeme PKCS12, sertifika yok, parola uyarısı yok")
    void pkcs12_detectedNotOpened() {
        byte[] pfx = pkcs12("sunucu", chain.leafKey().getPrivate(), PW, chain.leaf(), chain.inter(), chain.root());
        Result r = CertificateFileParser.parse(pfx, "sunucu.pfx", false);
        assertThat(r.format()).isEqualTo("PKCS12");
        assertThat(r.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PKCS12);
        assertThat(r.certs()).isEmpty();
        assertThat(codes(r)).doesNotContain("PASSWORD_REQUIRED", "PASSWORD_WRONG", "NO_CERTIFICATE");
        // Base64 metin olarak yapıştırılan PFX de tanınır
        Result pasted = CertificateFileParser.parse(utf8(Base64.getMimeEncoder().encodeToString(pfx)), null, true);
        assertThat(pasted.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PKCS12);
    }

    @Test
    @DisplayName("JKS / JCEKS / BKS: imzasından tanınır → gizli malzeme (truststore da — anahtar deposu biçimi reddedilir)")
    void keystores_detected() {
        byte[] jks = keystore("JKS", "uygulama", chain.leafKey().getPrivate(), PW, Map.of("kok", chain.root()), chain.leaf(), chain.inter());
        assertThat(CertificateFileParser.parse(jks, "uygulama.jks", false).privateMaterial()).isEqualTo("JKS");
        byte[] trust = keystore("JKS", "uygulama", null, PW, Map.of("kok", chain.root()));
        Result tr = CertificateFileParser.parse(trust, "truststore.jks", false);
        assertThat(tr.privateMaterial()).isEqualTo("JKS");
        assertThat(tr.certs()).isEmpty();
        byte[] jceks = keystore("JCEKS", "a", null, PW, Map.of("kok", chain.root()));
        assertThat(CertificateFileParser.parse(jceks, "x.bin", false).privateMaterial()).isEqualTo("JCEKS");
        byte[] bks = keystore("BKS", "a", null, PW, Map.of("kok", chain.root()));
        Result b = CertificateFileParser.parse(bks, "truststore.bks", false);
        assertThat(b.privateMaterial()).isEqualTo("BKS");
        assertThat(b.format()).isEqualTo("BKS");
    }

    // ── Ayıklanmış yükleme ───────────────────────────────────────────────────

    @Test
    @DisplayName("fromExtracted: sertifikalar + takma ad + anahtar girdisi korunur; özel anahtar sayısı PRIVATE_KEY_KEPT_LOCAL {count} (bilgi)")
    void fromExtracted_keptLocalWarning() {
        Result r = CertificateFileParser.fromExtracted("PKCS12", "C:\\yol\\sunucu.pfx", 4321, List.of(
                new CertificateFileParser.ParsedCert(chain.leaf(), "sunucu", true, null),
                new CertificateFileParser.ParsedCert(chain.inter(), "sunucu", false, null)), null, 2);
        assertThat(r.format()).isEqualTo("PKCS12");
        assertThat(r.fileName()).isEqualTo("sunucu.pfx");
        assertThat(r.sizeBytes()).isEqualTo(4321);
        assertThat(r.privateMaterial()).isNull();
        assertThat(certs(r)).containsExactly(chain.leaf(), chain.inter());
        assertThat(r.certs().get(0).keyEntry()).isTrue();
        Warning w = warning(r, "PRIVATE_KEY_KEPT_LOCAL");
        assertThat(w.severity()).isEqualTo("info");
        assertThat(w.params()).containsEntry("count", 2);
        assertThat(CertificateFileParser.WARNING_CODES).contains("PRIVATE_KEY_KEPT_LOCAL").doesNotContain("PRIVATE_KEY_IGNORED");
    }

    @Test
    @DisplayName("fromExtracted: sertifika yok + anahtar yok → NO_CERTIFICATE, uyarı yok; yalnız CSR → CSR_NOT_CERTIFICATE")
    void fromExtracted_empty() {
        Result empty = CertificateFileParser.fromExtracted("PEM", "x.pem", 10, List.of(), null, 0);
        assertThat(codes(empty)).containsExactly("NO_CERTIFICATE");
        CertificateFileParser.CsrInfo csr = CertificateFileParser.parseCsr(csr("csr.example.test", List.of(), rsa()));
        Result c = CertificateFileParser.fromExtracted("PEM", "x.csr", 10, List.of(), csr, 0);
        assertThat(codes(c)).containsExactly("CSR_NOT_CERTIFICATE");
    }

    // ── ZIP ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ZIP: her girdi ayrıştırılır; sertifika olmayan ve iç içe arşiv girdileri ZIP_SKIPPED_ENTRY")
    void zip_entriesAndSkips() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("certs/leaf.pem", utf8(pem(chain.leaf())));
        entries.put("certs/chain.p7b", pkcs7(chain.inter(), chain.root()));
        entries.put("README.txt", utf8("bu bir sertifika değil"));
        entries.put("ic-ice.zip", zip(Map.of("x.pem", utf8(pem(chain.leaf())))));
        Result r = CertificateFileParser.parse(zip(entries), "paket.zip", false);
        assertThat(r.format()).isEqualTo("ZIP");
        assertThat(r.privateMaterial()).isNull();
        assertThat(certs(r)).contains(chain.leaf(), chain.inter(), chain.root());
        List<Object> skipped = r.warnings().stream().filter(w -> w.code().equals("ZIP_SKIPPED_ENTRY"))
                .map(w -> w.params().get("name")).toList();
        assertThat(skipped).containsExactlyInAnyOrder("README.txt", "ic-ice.zip");
        assertThat(r.certs().stream().map(CertificateFileParser.ParsedCert::source)).contains("leaf.pem");
    }

    @Test
    @DisplayName("ZIP içinde özel anahtar (.key) ya da anahtar deposu (PFX) → tüm arşiv gizli malzeme, sertifika yok")
    void zip_withSecretRejected() {
        Map<String, byte[]> withKey = new LinkedHashMap<>();
        withKey.put("leaf.pem", utf8(pem(chain.leaf())));
        withKey.put("leaf.key", utf8(privateKeyPem(rsa())));
        Result r = CertificateFileParser.parse(zip(withKey), "paket.zip", false);
        assertThat(r.privateMaterial()).isEqualTo(CertificateFileParser.SECRET_PRIVATE_KEY);
        assertThat(r.certs()).isEmpty();

        Map<String, byte[]> withPfx = new LinkedHashMap<>();
        withPfx.put("leaf.pem", utf8(pem(chain.leaf())));
        withPfx.put("sunucu.pfx", pkcs12("sunucu", chain.leafKey().getPrivate(), PW, chain.leaf()));
        assertThat(CertificateFileParser.parse(zip(withPfx), "paket.zip", false).privateMaterial()).isEqualTo("PKCS12");
    }

    @Test
    @DisplayName("ZIP: 50 girdi sınırı aşılınca okuma durur (ZIP_LIMIT {max_entries, max_mb})")
    void zip_entryLimit() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) entries.put("c" + i + ".pem", utf8(pem(chain.root())));
        Result r = CertificateFileParser.parse(zip(entries), "cok.zip", false);
        Warning w = warning(r, "ZIP_LIMIT");
        assertThat(w).isNotNull();
        assertThat(w.params()).containsEntry("max_entries", 50).containsEntry("max_mb", 5);
        assertThat(r.certs()).hasSize(50);   // sınıra kadar okunanlar kalır, 51. açılmaz
    }

    @Test
    @DisplayName("ZIP bombası: yüksek sıkıştırma oranlı girdi açılmaz (ZIP_LIMIT), bellek büyümez")
    void zip_bombRatio() {
        byte[] zeros = new byte[2 * 1024 * 1024];   // 2 MB sıfır → birkaç KB'a sıkışır (oran ≫ 200)
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.pem", utf8(pem(chain.leaf())));
        entries.put("bomb.bin", zeros);
        Result r = CertificateFileParser.parse(zip(entries), "bomba.zip", false);
        assertThat(codes(r)).contains("ZIP_LIMIT");
        assertThat(certs(r)).containsExactly(chain.leaf());
    }

    @Test
    @DisplayName("ZIP: toplam açılmış veri 5 MB'ı aşarsa okuma durur")
    void zip_totalSizeLimit() {
        // Rastgele onaltılık metin ~2 kat sıkışır: arşiv 5 MB'ın ALTINDA kalır (dosya sınırına takılmaz), oran kapısı
        // da tetiklenmez; açılmış toplam 6 MB → toplam sınırı.
        Random rnd = new Random(42);
        char[] hex = "0123456789abcdef".toCharArray();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            byte[] text = new byte[2 * 1024 * 1024];
            for (int j = 0; j < text.length; j++) text[j] = (byte) hex[rnd.nextInt(16)];
            entries.put("metin" + i + ".txt", text);
        }
        byte[] archive = zip(entries);
        assertThat(archive.length).isLessThan(CertificateFileParser.MAX_BYTES);
        Result r = CertificateFileParser.parse(archive, "buyuk.zip", false);
        assertThat(codes(r)).contains("ZIP_LIMIT").doesNotContain("FILE_TOO_LARGE");
    }

    // ── Sınırlar / tanınmayan ────────────────────────────────────────────────

    @Test
    @DisplayName("5 MB üstü: FILE_TOO_LARGE {max_mb} ve ayrıştırılmaz")
    void fileTooLarge() {
        byte[] big = new byte[CertificateFileParser.MAX_BYTES + 1];
        Result r = CertificateFileParser.parse(big, "buyuk.pem", false);
        assertThat(warning(r, "FILE_TOO_LARGE").params()).containsEntry("max_mb", 5);
        assertThat(r.certs()).isEmpty();
    }

    @Test
    @DisplayName("Tanınmayan ikili içerik: UNSUPPORTED_FORMAT {name}")
    void unsupported() {
        byte[] junk = new byte[256];
        new Random(7).nextBytes(junk);
        junk[0] = 0x01;
        Result r = CertificateFileParser.parse(junk, "rastgele.bin", false);
        assertThat(warning(r, "UNSUPPORTED_FORMAT").params()).containsEntry("name", "rastgele.bin");
        assertThat(r.privateMaterial()).isNull();
    }

    @Test
    @DisplayName("Sertifikasız PEM metni: NO_CERTIFICATE")
    void pemWithoutCertificate() {
        Result r = CertificateFileParser.parse(utf8(pemBlock("PUBLIC KEY", rsa().getPublic().getEncoded())), "pub.pem", false);
        assertThat(codes(r)).contains("NO_CERTIFICATE");
    }

    @Test
    @DisplayName("toPem / readPemChain gidiş-dönüş sırayı korur; yalnız sertifika içerir")
    void pemRoundTrip() {
        String pem = CertificateFileParser.toPem(List.of(chain.leaf(), chain.inter()));
        assertThat(pem).doesNotContain("PRIVATE KEY");
        assertThat(CertificateFileParser.readPemChain(pem)).containsExactly(chain.leaf(), chain.inter());
    }

    @Test
    @DisplayName("Dosya adı temizlenir: yol parçası ve denetim karakterleri atılır, ≤ 255")
    void sanitizeFileName() {
        assertThat(CertificateFileParser.sanitizeFileName("C:\\Users\\x\\..\\gizli\\sertifika.pfx")).isEqualTo("sertifika.pfx");
        assertThat(CertificateFileParser.sanitizeFileName("../../etc/a\u0000b.pem")).isEqualTo("ab.pem");
        assertThat(CertificateFileParser.sanitizeFileName("   ")).isNull();
        assertThat(CertificateFileParser.sanitizeFileName("x".repeat(300) + ".pem")).hasSize(255).endsWith(".pem");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
