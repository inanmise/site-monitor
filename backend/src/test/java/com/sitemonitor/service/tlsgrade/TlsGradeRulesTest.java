package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.service.tlsgrade.TlsGradeRules.Grade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TLS notu kuralının DOĞRULUK TABLOSU (2026-10-10). Her satır "kusursuz" bir uç noktadan (A+) tek bir değişiklikle
 * başlar ve beklenen notu + notu belirleyen neden kodunu sabitler. Kural değişirse bu tablo bilinçli olarak güncellenir.
 */
class TlsGradeRulesTest {

    /** A+ uç: TLS 1.3 + 1.2 açık, 1.0/1.1 kapalı, zımbalama var, zayıf takım yok, AEAD + ECDHE, uzun HSTS. */
    static LatestCheck perfectCheck() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("www.example.com");
        lc.setStatus("valid");
        lc.setDaysRemaining(120);
        lc.setSan("[\"www.example.com\"]");
        lc.setTrustStatus("TRUSTED");
        lc.setChainStatus("VALID");
        lc.setRevocationStatus("VALID");
        lc.setPublicKeyAlgorithm("EC");
        lc.setPublicKeySize(256);
        lc.setSignatureAlgorithm("SHA256withECDSA");
        lc.setTlsVersion("TLSv1.2");
        lc.setCipherSuite("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        lc.setHstsStatus("ENABLED");
        lc.setHstsPolicy("{\"header\":\"max-age=31536000\",\"max_age\":31536000}");
        lc.setFingerprint("AA");
        return lc;
    }

    static TlsProfile perfectProfile() {
        TlsProfile p = new TlsProfile();
        p.setDomain("www.example.com");
        p.setStatus(TlsProfile.STATUS_OK);
        p.setTls13(TlsProfile.YES);
        p.setTls12(TlsProfile.YES);
        p.setTls11(TlsProfile.NO);
        p.setTls10(TlsProfile.NO);
        p.setOcspStapling(TlsProfile.YES);
        p.setWeakCipher(TlsProfile.NO);
        p.setPreferredCipher("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        return p;
    }

    @Test
    @DisplayName("Kusursuz uç noktası A+ ve hiçbir neden taşımaz")
    void perfectIsAPlus() {
        Grade g = TlsGradeRules.evaluate(perfectCheck(), perfectProfile(), false);
        assertThat(g.state()).isEqualTo(TlsGradeRules.GRADED);
        assertThat(g.grade()).isEqualTo("A+");
        assertThat(g.reasons()).isEmpty();
    }

    static Stream<Arguments> truthTable() {
        return Stream.of(
                row("süresi dolmuş → F", "F", "CERT_EXPIRED", (lc, p) -> lc.setDaysRemaining(-2)),
                row("alan adı uyuşmuyor → F", "F", "HOSTNAME_MISMATCH", (lc, p) -> lc.setSan("[\"other.example.org\"]")),
                row("güvenilmeyen CA → F", "F", "CERT_UNTRUSTED", (lc, p) -> lc.setTrustStatus("UNTRUSTED")),
                row("iptal edilmiş → F", "F", "CERT_REVOKED", (lc, p) -> lc.setRevocationStatus("REVOKED")),
                row("kırık zincir → F", "F", "CHAIN_BROKEN", (lc, p) -> lc.setChainStatus("BROKEN")),
                row("RSA 1024 → F", "F", "KEY_WEAK", (lc, p) -> { lc.setPublicKeyAlgorithm("RSA"); lc.setPublicKeySize(1024); }),
                row("EC 224 → F", "F", "KEY_WEAK", (lc, p) -> lc.setPublicKeySize(224)),
                row("SHA-1 imza → F", "F", "SIG_WEAK", (lc, p) -> lc.setSignatureAlgorithm("SHA1withRSA")),
                row("NULL takımı anlaşıldı → F", "F", "CIPHER_INSECURE", (lc, p) -> lc.setCipherSuite("TLS_RSA_WITH_NULL_SHA")),
                row("EXPORT takımı kabul ediliyor → F", "F", "INSECURE_CIPHER_ACCEPTED",
                        (lc, p) -> { p.setWeakCipher(TlsProfile.YES); p.setWeakCipherSuite("TLS_RSA_EXPORT_WITH_RC4_40_MD5"); }),
                row("3DES anlaşıldı → D", "D", "CIPHER_WEAK", (lc, p) -> lc.setCipherSuite("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA")),
                row("TLS 1.2 yok + zayıf takım kabulü → D (yığılma)", "D", "MULTIPLE_SERIOUS",
                        (lc, p) -> { p.setTls12(TlsProfile.NO); p.setWeakCipher(TlsProfile.YES); p.setWeakCipherSuite("TLS_RSA_WITH_RC4_128_SHA"); }),
                row("TLS 1.2 yok → C", "C", "NO_TLS12", (lc, p) -> p.setTls12(TlsProfile.NO)),
                row("anlaşılan sürüm TLS 1.0 → C", "C", "NO_TLS12", (lc, p) -> lc.setTlsVersion("TLSv1")),
                row("RC4 kabul ediliyor → C", "C", "WEAK_CIPHER_ACCEPTED",
                        (lc, p) -> { p.setWeakCipher(TlsProfile.YES); p.setWeakCipherSuite("TLS_RSA_WITH_RC4_128_SHA"); }),
                row("TLS 1.0 açık → B", "B", "TLS10_ENABLED", (lc, p) -> p.setTls10(TlsProfile.YES)),
                row("TLS 1.1 açık → B", "B", "TLS11_ENABLED", (lc, p) -> p.setTls11(TlsProfile.YES)),
                row("PFS yok (statik RSA) → B", "B", "NO_PFS", (lc, p) -> lc.setCipherSuite("TLS_RSA_WITH_AES_128_GCM_SHA256")),
                row("CBC seçiliyor → B", "B", "CIPHER_CBC", (lc, p) -> lc.setCipherSuite("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA")),
                row("TLS 1.3 yok → A", "A", "NO_TLS13", (lc, p) -> p.setTls13(TlsProfile.NO)),
                row("HSTS yok → A", "A", "HSTS_MISSING", (lc, p) -> lc.setHstsStatus("MISSING")),
                row("HSTS kısa (30 gün) → A", "A", "HSTS_SHORT",
                        (lc, p) -> lc.setHstsPolicy("{\"header\":\"max-age=2592000\",\"max_age\":2592000}")),
                row("HSTS hiç bakılmadı → A", "A", "HSTS_NOT_CHECKED", (lc, p) -> lc.setHstsStatus(null)),
                row("zımbalama yok → A", "A", "OCSP_STAPLING_MISSING", (lc, p) -> p.setOcspStapling(TlsProfile.NO)),
                row("profil başarısız → A", "A", "PROFILE_FAILED", (lc, p) -> p.setStatus(TlsProfile.STATUS_FAILED)),
                row("profilde TLS 1.0 bilinmiyor → A", "A", "PROFILE_PARTIAL", (lc, p) -> p.setTls10(TlsProfile.UNKNOWN)),
                row("zımbalama bilinmiyor → kesinti YOK (A+)", "A+", null, (lc, p) -> p.setOcspStapling(TlsProfile.UNKNOWN)),
                row("RSA 2048 → yalnız 2030 bilgi notu (A+)", "A+", null,
                        (lc, p) -> { lc.setPublicKeyAlgorithm("RSA"); lc.setPublicKeySize(2048); lc.setSignatureAlgorithm("SHA256withRSA"); }));
    }

    private static Arguments row(String name, String grade, String decisive, BiConsumer<LatestCheck, TlsProfile> mutate) {
        return Arguments.of(name, grade, decisive, mutate);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("truthTable")
    void truthTableRow(String name, String grade, String decisive, BiConsumer<LatestCheck, TlsProfile> mutate) {
        LatestCheck lc = perfectCheck();
        TlsProfile p = perfectProfile();
        mutate.accept(lc, p);
        Grade g = TlsGradeRules.evaluate(lc, p, false);
        assertThat(g.grade()).as(name).isEqualTo(grade);
        if (decisive == null) {
            assertThat(g.reasons()).as(name).isEmpty();
        } else {
            assertThat(g.decisive().stream().map(TlsGradeRules.Finding::code).toList()).as(name).contains(decisive);
            assertThat(g.reasons().get(0).cap()).as("en kötü tavan önce").isEqualTo(grade);
        }
    }

    @Test
    @DisplayName("Profil henüz yok → A (A+ kanıt ister), PROFILE_PENDING")
    void noProfileCapsAtA() {
        Grade g = TlsGradeRules.evaluate(perfectCheck(), null, false);
        assertThat(g.grade()).isEqualTo("A");
        assertThat(g.codes()).containsExactly("PROFILE_PENDING");
    }

    @Test
    @DisplayName("Birden çok neden: en kötü tavan kazanır, nedenler kötüden iyiye sıralı")
    void worstCapWinsAndOrdering() {
        LatestCheck lc = perfectCheck();
        lc.setHstsStatus("MISSING");
        TlsProfile p = perfectProfile();
        p.setTls10(TlsProfile.YES);
        p.setTls13(TlsProfile.NO);
        Grade g = TlsGradeRules.evaluate(lc, p, false);
        assertThat(g.grade()).isEqualTo("B");
        assertThat(g.codes()).containsExactly("TLS10_ENABLED", "NO_TLS13", "HSTS_MISSING");
        assertThat(g.decisive()).extracting(TlsGradeRules.Finding::code).containsExactly("TLS10_ENABLED");
    }

    @Test
    @DisplayName("Elle yüklenen sertifika notlanmaz (ağ notu uydurulmaz)")
    void manualIsNotApplicable() {
        Grade g = TlsGradeRules.evaluate(perfectCheck(), perfectProfile(), true);
        assertThat(g.state()).isEqualTo(TlsGradeRules.NOT_APPLICABLE);
        assertThat(g.stateReason()).isEqualTo("MANUAL");
        assertThat(g.grade()).isNull();
        assertThat(g.graded()).isFalse();
    }

    @Test
    @DisplayName("Kontrol yok / son kontrol hatalı → veri yok (not yok)")
    void noDataStates() {
        assertThat(TlsGradeRules.evaluate(null, perfectProfile(), false).stateReason()).isEqualTo("NO_CHECK");
        LatestCheck err = perfectCheck();
        err.setStatus("error");
        Grade g = TlsGradeRules.evaluate(err, perfectProfile(), false);
        assertThat(g.state()).isEqualTo(TlsGradeRules.NO_DATA);
        assertThat(g.stateReason()).isEqualTo("CHECK_FAILED");
        assertThat(g.grade()).isNull();
    }

    @Test
    @DisplayName("Anlaşılan takım yoksa profilin tercih ettiği takım değerlendirilir")
    void preferredCipherFallback() {
        LatestCheck lc = perfectCheck();
        lc.setCipherSuite(null);
        lc.setTlsVersion(null);
        TlsProfile p = perfectProfile();
        p.setPreferredCipher("TLS_RSA_WITH_AES_128_CBC_SHA");
        Grade g = TlsGradeRules.evaluate(lc, p, false);
        assertThat(g.grade()).isEqualTo("B");
        assertThat(g.codes()).contains("CIPHER_CBC", "NO_PFS");
    }

    @Test
    @DisplayName("Parametreler: anahtar boyu ve HSTS günleri neden metnine taşınır")
    void paramsCarried() {
        LatestCheck lc = perfectCheck();
        lc.setPublicKeyAlgorithm("RSA");
        lc.setPublicKeySize(1024);
        lc.setHstsPolicy("{\"max_age\":864000}");
        Grade g = TlsGradeRules.evaluate(lc, perfectProfile(), false);
        TlsGradeRules.Finding key = g.reasons().stream().filter(f -> f.code().equals("KEY_WEAK")).findFirst().orElseThrow();
        assertThat(key.params()).containsExactly("RSA", 1024);
        TlsGradeRules.Finding hsts = g.reasons().stream().filter(f -> f.code().equals("HSTS_SHORT")).findFirst().orElseThrow();
        assertThat(hsts.params()).containsExactly(10L);
    }

    @Test
    @DisplayName("2030 bilgi notu notu etkilemez; RSA 3072 not üretmez")
    void key2030Note() {
        LatestCheck lc = perfectCheck();
        lc.setPublicKeyAlgorithm("RSA");
        lc.setPublicKeySize(2048);
        Grade g = TlsGradeRules.evaluate(lc, perfectProfile(), false);
        assertThat(g.notes()).extracting(TlsGradeRules.Finding::code).containsExactly("KEY_2030");
        lc.setPublicKeySize(3072);
        assertThat(TlsGradeRules.evaluate(lc, perfectProfile(), false).notes()).isEmpty();
    }

    @Test
    @DisplayName("Not sıralaması: A+ en iyi, F en kötü, tanınmayan 0")
    void rankOrder() {
        assertThat(TlsGradeRules.rank("A+")).isGreaterThan(TlsGradeRules.rank("A"));
        assertThat(TlsGradeRules.rank("A")).isGreaterThan(TlsGradeRules.rank("B"));
        assertThat(TlsGradeRules.rank("D")).isGreaterThan(TlsGradeRules.rank("F"));
        assertThat(TlsGradeRules.rank("X")).isZero();
        assertThat(TlsGradeRules.rank(null)).isZero();
        assertThat(TlsGradeRules.GRADES).containsExactly("A+", "A", "B", "C", "D", "F");
    }

    @Test
    @DisplayName("Her neden kodunun tavanı geçerli bir nottur ya da bilgi notudur")
    void catalogCapsAreValid() {
        for (TlsGradeRules.Reason r : TlsGradeRules.Reason.values()) {
            assertThat(r.cap() == null || TlsGradeRules.isGrade(r.cap())).as(r.name()).isTrue();
            assertThat(r.cap()).as("A+ tavanı anlamsız: " + r.name()).isNotEqualTo("A+");
        }
        assertThat(TlsGradeRules.REASON_CODES).doesNotHaveDuplicates();
        assertThat(List.copyOf(TlsGradeRules.STATE_CODES)).containsExactly("MANUAL", "NO_CHECK", "CHECK_FAILED");
    }
}
