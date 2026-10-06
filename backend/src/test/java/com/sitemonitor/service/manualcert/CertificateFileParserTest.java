package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.manualcert.CertificateFileParser.Result;
import com.sitemonitor.service.manualcert.CertificateFileParser.Warning;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manuel sertifika dosyası ayrıştırıcısı (2026-10-06): biçim İÇERİKTEN tanınır; özel anahtarlar sayılır ama okunmaz;
 * parola yanlış/eksik ayrımı; ZIP sınırları ve bomba koruması. Fikstürler testte üretilir.
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
    @DisplayName("PEM: çoklu CERTIFICATE bloğu sırasıyla okunur; biçim PEM")
    void pem_multipleCertificates() {
        Result r = CertificateFileParser.parse(utf8(pem(chain.leaf(), chain.inter(), chain.root())), "tam-zincir.pem", null, false);
        assertThat(r.format()).isEqualTo("PEM");
        assertThat(certs(r)).containsExactly(chain.leaf(), chain.inter(), chain.root());
        assertThat(codes(r)).isEmpty();
        assertThat(r.fileName()).isEqualTo("tam-zincir.pem");
    }

    @Test
    @DisplayName("PEM: her türden PRIVATE KEY bloğu SAYILIR ama okunmaz (PRIVATE_KEY_IGNORED {count})")
    void pem_privateKeysCountedAndIgnored() {
        KeyPair kp = rsa();
        String text = privateKeyPem(kp)
                + pemBlock("RSA PRIVATE KEY", new byte[] {1, 2, 3})
                + pemBlock("EC PRIVATE KEY", new byte[] {4, 5})
                + pemBlock("ENCRYPTED PRIVATE KEY", new byte[] {6})
                + pemBlock("OPENSSH PRIVATE KEY", new byte[] {7})
                + pem(chain.leaf());
        Result r = CertificateFileParser.parse(utf8(text), "anahtar-ve-sertifika.pem", null, false);
        assertThat(certs(r)).containsExactly(chain.leaf());
        Warning w = warning(r, "PRIVATE_KEY_IGNORED");
        assertThat(w).isNotNull();
        assertThat(w.severity()).isEqualTo("info");
        assertThat(w.params()).containsEntry("count", 5);
    }

    @Test
    @DisplayName("PEM: TRUSTED CERTIFICATE (OpenSSL güven ek bilgili) okunur")
    void pem_trustedCertificate() {
        byte[] withAux = concat(der(chain.root()), new byte[] {0x30, 0x00});   // DER sertifika + boş CertAux
        Result r = CertificateFileParser.parse(utf8(pemBlock("TRUSTED CERTIFICATE", withAux)), "trusted.pem", null, false);
        assertThat(certs(r)).containsExactly(chain.root());
    }

    @Test
    @DisplayName("PEM: CERTIFICATE REQUEST → csr bloğu + CSR_NOT_CERTIFICATE (sertifika yok)")
    void pem_csrDetected() {
        KeyPair kp = rsa();
        String text = pemBlock("CERTIFICATE REQUEST", csr("csr.example.test", List.of("csr.example.test", "www.csr.example.test"), kp));
        Result r = CertificateFileParser.parse(utf8(text), "istek.csr", null, false);
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
        Result r = CertificateFileParser.parse(utf8(pemBlock("PKCS7", pkcs7(chain.leaf(), chain.inter()))), "zincir.p7b", null, false);
        assertThat(r.format()).isEqualTo("PEM");
        assertThat(certs(r)).containsExactlyInAnyOrder(chain.leaf(), chain.inter());
    }

    @Test
    @DisplayName("Yapıştırılan metin: biçim TEXT, dosya adı yok")
    void pasted_isText() {
        Result r = CertificateFileParser.parse(utf8("  \n" + pem(chain.leaf())), "yoksay.pem", null, true);
        assertThat(r.format()).isEqualTo("TEXT");
        assertThat(r.fileName()).isNull();
        assertThat(certs(r)).containsExactly(chain.leaf());
    }

    // ── DER / Base64 / PKCS7 ─────────────────────────────────────────────────

    @Test
    @DisplayName("DER X.509 içerikten tanınır (uzantı .txt olsa bile)")
    void der_detectedByContent() {
        Result r = CertificateFileParser.parse(der(chain.leaf()), "yanlis-uzanti.txt", null, false);
        assertThat(r.format()).isEqualTo("DER");
        assertThat(certs(r)).containsExactly(chain.leaf());
    }

    @Test
    @DisplayName("Zırhsız Base64 DER okunur")
    void base64WithoutArmour() {
        String b64 = Base64.getMimeEncoder().encodeToString(der(chain.leaf()));
        Result r = CertificateFileParser.parse(utf8(b64), "sertifika.cer", null, false);
        assertThat(certs(r)).containsExactly(chain.leaf());
        assertThat(r.format()).isEqualTo("DER");
    }

    @Test
    @DisplayName("PKCS#7 (DER, .p7b) tüm sertifikaları verir")
    void pkcs7_der() {
        Result r = CertificateFileParser.parse(pkcs7(chain.leaf(), chain.inter(), chain.root()), "zincir.p7b", null, false);
        assertThat(r.format()).isEqualTo("PKCS7");
        assertThat(certs(r)).containsExactlyInAnyOrder(chain.leaf(), chain.inter(), chain.root());
    }

    @Test
    @DisplayName("DER CSR: csr + CSR_NOT_CERTIFICATE")
    void der_csr() {
        Result r = CertificateFileParser.parse(csr("der.example.test", List.of(), rsa()), "istek.der", null, false);
        assertThat(r.csr()).isNotNull();
        assertThat(codes(r)).contains("CSR_NOT_CERTIFICATE");
    }

    // ── PKCS#12 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PKCS#12 doğru parola: anahtar girdisinin zinciri okunur, alias ve anahtar girdisi işaretli; özel anahtar sayılır")
    void pkcs12_correctPassword() {
        byte[] pfx = pkcs12("sunucu", chain.leafKey().getPrivate(), PW, chain.leaf(), chain.inter(), chain.root());
        Result r = CertificateFileParser.parse(pfx, "sunucu.pfx", PW.clone(), false);
        assertThat(r.format()).isEqualTo("PKCS12");
        assertThat(r.needsPassword()).isFalse();
        assertThat(r.passwordError()).isFalse();
        assertThat(certs(r)).containsExactly(chain.leaf(), chain.inter(), chain.root());
        CertificateFileParser.ParsedCert first = r.certs().get(0);
        assertThat(first.keyEntry()).isTrue();
        assertThat(first.alias()).isEqualTo("sunucu");
        assertThat(warning(r, "PRIVATE_KEY_IGNORED").params()).containsEntry("count", 1);
    }

    @Test
    @DisplayName("PKCS#12 yanlış parola: password_error + PASSWORD_WRONG {format}, sertifika yok, NO_CERTIFICATE yok")
    void pkcs12_wrongPassword() {
        byte[] pfx = pkcs12("sunucu", chain.leafKey().getPrivate(), PW, chain.leaf());
        Result r = CertificateFileParser.parse(pfx, "sunucu.p12", "yanlis".toCharArray(), false);
        assertThat(r.passwordError()).isTrue();
        assertThat(r.certs()).isEmpty();
        Warning w = warning(r, "PASSWORD_WRONG");
        assertThat(w).isNotNull();
        assertThat(w.params()).containsEntry("format", "PKCS12");
        assertThat(codes(r)).doesNotContain("NO_CERTIFICATE");
    }

    @Test
    @DisplayName("PKCS#12 parola yok: needs_password + PASSWORD_REQUIRED {format}")
    void pkcs12_missingPassword() {
        byte[] pfx = pkcs12("sunucu", chain.leafKey().getPrivate(), PW, chain.leaf());
        Result r = CertificateFileParser.parse(pfx, "sunucu.pfx", null, false);
        assertThat(r.needsPassword()).isTrue();
        assertThat(r.certs()).isEmpty();
        assertThat(warning(r, "PASSWORD_REQUIRED").params()).containsEntry("format", "PKCS12");
    }

    // ── JKS / JCEKS / BKS ────────────────────────────────────────────────────

    @Test
    @DisplayName("JKS parolasız: sertifikalar okunur (bütünlük atlanır) — anahtar zinciri + güvenilen girdi")
    void jks_nullPassword() {
        byte[] jks = keystore("JKS", "uygulama", chain.leafKey().getPrivate(), PW,
                Map.of("kok", chain.root()), chain.leaf(), chain.inter());
        Result r = CertificateFileParser.parse(jks, "uygulama.jks", null, false);
        assertThat(r.format()).isEqualTo("JKS");
        assertThat(certs(r)).contains(chain.leaf(), chain.inter(), chain.root());
        assertThat(r.needsPassword()).isFalse();
        assertThat(codes(r)).doesNotContain("PASSWORD_WRONG", "NO_CERTIFICATE");
    }

    @Test
    @DisplayName("JKS yanlış parola: PASSWORD_WRONG uyarısı ama açık sertifikalar yine okunur")
    void jks_wrongPassword_stillReads() {
        byte[] jks = keystore("JKS", "uygulama", null, PW, Map.of("kok", chain.root()));
        Result r = CertificateFileParser.parse(jks, "truststore.jks", "yanlis".toCharArray(), false);
        assertThat(certs(r)).containsExactly(chain.root());
        assertThat(warning(r, "PASSWORD_WRONG").severity()).isEqualTo("warn");
        assertThat(r.passwordError()).isFalse();
    }

    @Test
    @DisplayName("JCEKS: içerikten tanınır, parolasız okunur")
    void jceks() {
        byte[] ks = keystore("JCEKS", "a", null, PW, Map.of("kok", chain.root()));
        Result r = CertificateFileParser.parse(ks, "x.bin", null, false);
        assertThat(r.format()).isEqualTo("JCEKS");
        assertThat(certs(r)).containsExactly(chain.root());
    }

    @Test
    @DisplayName("BKS (BouncyCastle) okunur")
    void bks() {
        byte[] ks = keystore("BKS", "a", null, PW, Map.of("kok", chain.root()));
        Result r = CertificateFileParser.parse(ks, "truststore.bks", PW.clone(), false);
        assertThat(r.format()).isEqualTo("BKS");
        assertThat(certs(r)).containsExactly(chain.root());
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
        Result r = CertificateFileParser.parse(zip(entries), "paket.zip", null, false);
        assertThat(r.format()).isEqualTo("ZIP");
        assertThat(certs(r)).contains(chain.leaf(), chain.inter(), chain.root());
        List<Object> skipped = r.warnings().stream().filter(w -> w.code().equals("ZIP_SKIPPED_ENTRY"))
                .map(w -> w.params().get("name")).toList();
        assertThat(skipped).containsExactlyInAnyOrder("README.txt", "ic-ice.zip");
        assertThat(r.certs().stream().map(CertificateFileParser.ParsedCert::source)).contains("leaf.pem");
    }

    @Test
    @DisplayName("ZIP: 50 girdi sınırı aşılınca okuma durur (ZIP_LIMIT {max_entries, max_mb})")
    void zip_entryLimit() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) entries.put("c" + i + ".pem", utf8(pem(chain.root())));
        Result r = CertificateFileParser.parse(zip(entries), "cok.zip", null, false);
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
        Result r = CertificateFileParser.parse(zip(entries), "bomba.zip", null, false);
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
        Result r = CertificateFileParser.parse(archive, "buyuk.zip", null, false);
        assertThat(codes(r)).contains("ZIP_LIMIT").doesNotContain("FILE_TOO_LARGE");
    }

    // ── Sınırlar / tanınmayan ────────────────────────────────────────────────

    @Test
    @DisplayName("5 MB üstü: FILE_TOO_LARGE {max_mb} ve ayrıştırılmaz")
    void fileTooLarge() {
        byte[] big = new byte[CertificateFileParser.MAX_BYTES + 1];
        Result r = CertificateFileParser.parse(big, "buyuk.pem", null, false);
        assertThat(warning(r, "FILE_TOO_LARGE").params()).containsEntry("max_mb", 5);
        assertThat(r.certs()).isEmpty();
    }

    @Test
    @DisplayName("Tanınmayan ikili içerik: UNSUPPORTED_FORMAT {name}")
    void unsupported() {
        byte[] junk = new byte[256];
        new Random(7).nextBytes(junk);
        junk[0] = 0x01;
        Result r = CertificateFileParser.parse(junk, "rastgele.bin", null, false);
        assertThat(warning(r, "UNSUPPORTED_FORMAT").params()).containsEntry("name", "rastgele.bin");
    }

    @Test
    @DisplayName("Sertifikasız PEM metni: NO_CERTIFICATE")
    void pemWithoutCertificate() {
        Result r = CertificateFileParser.parse(utf8(pemBlock("PUBLIC KEY", rsa().getPublic().getEncoded())), "pub.pem", null, false);
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

    @SuppressWarnings("unused")
    private static List<String> list(String... s) { return new ArrayList<>(List.of(s)); }
}
