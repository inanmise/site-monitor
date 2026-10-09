package com.sitemonitor.service.manualcert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Doğrusal PEM taraması (2026-10-09, ReDoS): eski kalıplarla BİREBİR aynı bloklar ve özel anahtar sayısı; eşi olmayan
 * BEGIN yığını ya da uzun etiket dizisi artık anında biter.
 */
class CertificateFileParserPemScanTest {

    /** Eski kalıplar — çıktı kâhini. */
    private static final Pattern OLD_PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 .]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
    private static final Pattern OLD_PRIVATE_BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 .]*PRIVATE KEY[A-Z0-9 .]*-----");

    private static List<String> oldBlocks(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = OLD_PEM_BLOCK.matcher(text);
        while (m.find()) out.add(m.group(1) + "|" + m.group(2));
        return out;
    }

    private static int oldPrivate(String text) {
        int n = 0;
        Matcher m = OLD_PRIVATE_BEGIN.matcher(text);
        while (m.find()) n++;
        return n;
    }

    private static List<String> newBlocks(CertificateFileParser.PemScan s) {
        return s.blocks().stream().map(b -> b.label() + "|" + b.body()).toList();
    }

    private static void assertSameAsOld(String text) {
        CertificateFileParser.PemScan scan = CertificateFileParser.scanPem(text);
        assertThat(newBlocks(scan)).as("bloklar: %s", text.length() > 200 ? text.substring(0, 200) + "…" : text)
                .isEqualTo(oldBlocks(text));
        assertThat(scan.privateKeyHeaders()).as("özel anahtar başlığı").isEqualTo(oldPrivate(text));
    }

    @Test
    @DisplayName("Gerçekçi PEM metinleri: zincir, özel anahtar, RFC 1421 başlıkları, CRLF, bozuk/eşsiz bloklar — eskisiyle aynı")
    void realisticInputs_matchOldRegex() {
        Chain c = TestCerts.chain("api.example.test", days(200));
        String chainPem = pem(c.leaf(), c.inter(), c.root());
        String key = TestCerts.privateKeyPem(TestCerts.rsa());
        List<String> inputs = List.of(
                chainPem,
                key + chainPem,
                chainPem.replace("\n", "\r\n"),
                "Proc-Type: 4,ENCRYPTED\n" + chainPem,
                "-----BEGIN CERTIFICATE-----\nProc-Type: x\nDEK-Info: y\n\nAAAA\n-----END CERTIFICATE-----",
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END X509 CERTIFICATE-----\n" + chainPem,      // etiket uyuşmuyor
                "-----BEGIN CERTIFICATE-----\nAAAA\n" + chainPem,                                     // iç içe BEGIN
                "-----END CERTIFICATE-----\n" + chainPem + "-----BEGIN CERTIFICATE-----\nkapanmadı",
                "------BEGIN CERTIFICATE------\nAAAA\n------END CERTIFICATE------",                  // fazladan tire
                "-----BEGIN A----------END A-----",                                                 // boş gövde, bitişik
                "-----BEGIN A-----END A-----",                                                      // paylaşılan tire
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nxx\n-----BEGIN RSA PRIVATE KEY-----\n",
                "-----BEGIN  PRIVATE KEY -----\nx\n-----END  PRIVATE KEY -----",                     // boşluklu etiket
                "",
                "düz metin, PEM yok");
        for (String in : inputs) assertSameAsOld(in);
    }

    @Test
    @DisplayName("Bulanık karşılaştırma: rastgele işaretçi/metin dizilerinde 3000 örnek — eskisiyle aynı")
    void fuzz_matchesOldRegex() {
        String[] labels = { "CERTIFICATE", "A", "PRIVATE KEY", "RSA PRIVATE KEY", "X509 CERTIFICATE", "B C" };
        String[] filler = { "", "\n", "AAAA", "-", "--", "-----", " ", "BEGIN", "END", "x:y\n" };
        Random rnd = new Random(20261009L);
        for (int n = 0; n < 3000; n++) {
            StringBuilder sb = new StringBuilder();
            int parts = 1 + rnd.nextInt(10);
            for (int i = 0; i < parts; i++) {
                if (rnd.nextInt(3) == 0) {
                    sb.append(filler[rnd.nextInt(filler.length)]);
                } else {
                    sb.append(rnd.nextBoolean() ? "-----BEGIN " : "-----END ")
                      .append(labels[rnd.nextInt(labels.length)]).append("-----");
                }
            }
            assertSameAsOld(sb.toString());
        }
    }

    @Test
    @DisplayName("KAPI (ReDoS): 5 MB eşsiz BEGIN yığını ve uzun özel anahtar etiketi saniyeler değil milisaniyeler sürer")
    void pathologicalInputs_areLinear() {
        String begins = "-----BEGIN A-----\n".repeat(290_000);
        String longLabel = "-----BEGIN " + "PRIVATE KEY ".repeat(400_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(CertificateFileParser.scanPem(begins).blocks()).isEmpty();
            assertThat(CertificateFileParser.scanPem(longLabel).privateKeyHeaders()).isZero();
            CertificateFileParser.Result r = CertificateFileParser.parse(
                    begins.getBytes(StandardCharsets.US_ASCII), "yigin.pem", true);
            assertThat(r.certs()).isEmpty();
        });
    }

    @Test
    @DisplayName("Ayrıştırıcı uçtan uca: tam zincir → 3 sertifika sırasıyla; özel anahtar eklenince sayılır ve yükleme reddedilir (davranış aynı)")
    void parse_endToEnd_unchanged() {
        Chain c = TestCerts.chain("api.example.test", days(200));
        String chainPem = pem(c.leaf(), c.inter(), c.root());
        CertificateFileParser.Result r = CertificateFileParser.parse(chainPem.getBytes(StandardCharsets.UTF_8), "z.pem", true);
        List<X509Certificate> certs = r.certs().stream().map(CertificateFileParser.ParsedCert::cert).toList();
        assertThat(certs).containsExactly(c.leaf(), c.inter(), c.root());
        assertThat(r.privateKeyCount()).isZero();

        String withKey = TestCerts.privateKeyPem(TestCerts.rsa()) + chainPem;
        CertificateFileParser.Result k = CertificateFileParser.parse(withKey.getBytes(StandardCharsets.UTF_8), "k.pem", true);
        assertThat(k.privateKeyCount()).isEqualTo(1);
        assertThat(k.privateMaterial()).isNotNull();
        assertThat(k.certs()).as("gizli malzemeli yüklemede hiçbir sertifika kullanılmaz").isEmpty();
    }
}
