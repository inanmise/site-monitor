package com.sitemonitor.service;

import com.sitemonitor.service.CertificateHealthRules.CipherTier;
import com.sitemonitor.service.CertificateHealthRules.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sertifika sağlığı sınıflandırma kuralları — tablo güdümlü.
 *
 * <p>Bu kuralların iki tüketicisi var (sağlık kontrol listesi ve Zayıf Algoritma Raporu); ikisi
 * de buraya bakıyor. Bir eşiğin sessizce kayması, aynı sertifikanın bir ekranda temiz diğerinde
 * zayıf görünmesi demek olurdu.
 *
 * <p><b>UNKNOWN, FAIL değildir</b> kuralı burada da esastır: kurumsal proxy arkasında bilgi
 * eksik gelebilir ve bunu kırmızıya boyamak yanlış alarm üretir.
 */
class CertificateHealthRulesTest {

    // ── Protokol ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "protokol {0} → {1}")
    @CsvSource({
            "TLSv1.3, OK",
            "TLSv1.2, OK",
            "TLSv1.1, FAIL",
            "TLSv1,   FAIL",
            "TLSv1.0, FAIL",
            "SSLv3,   FAIL",
            "QUIC,    UNKNOWN",
    })
    void protocolStatus(String version, String expected) {
        assertThat(CertificateHealthRules.protocolStatus(version)).isEqualTo(Status.valueOf(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("Protokol bilinmiyorsa UNKNOWN — eski kayıtlar kırmızıya boyanmaz")
    void protocolUnknownWhenMissing(String version) {
        assertThat(CertificateHealthRules.protocolStatus(version)).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("Yalnız TLS 1.3 'en güncel' sayılır (1.2 kabul edilir ama güncel değil)")
    void latestProtocol() {
        assertThat(CertificateHealthRules.isLatestProtocol("TLSv1.3")).isTrue();
        assertThat(CertificateHealthRules.isLatestProtocol("TLSv1.2")).isFalse();
        assertThat(CertificateHealthRules.isLatestProtocol(null)).isFalse();
    }

    // ── Şifreleme ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "cipher {0} → {1}")
    @CsvSource({
            "TLS_AES_256_GCM_SHA384,                        STRONG",
            "TLS_CHACHA20_POLY1305_SHA256,                  STRONG",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,         STRONG",
            "TLS_ECDHE_RSA_WITH_AES_128_CCM,                STRONG",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,         ACCEPTABLE",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA,                 WEAK",
            "SSL_RSA_WITH_RC4_128_MD5,                      WEAK",
            "TLS_RSA_EXPORT_WITH_DES40_CBC_SHA,             WEAK",
            "TLS_ECDH_anon_WITH_AES_128_CBC_SHA,            WEAK",
            "TLS_RSA_WITH_NULL_SHA256,                      WEAK",
            "TLS_FUTURE_SUITE_2030,                         UNKNOWN",
    })
    void cipherTier(String suite, String expected) {
        assertThat(CertificateHealthRules.cipherTier(suite)).isEqualTo(CipherTier.valueOf(expected));
    }

    @Test
    @DisplayName("Kademe → durum eşlemesi: kabul edilebilir UYARI, zayıf HATA, bilinmeyen UNKNOWN")
    void cipherStatusMapping() {
        assertThat(CertificateHealthRules.cipherStatus("TLS_AES_128_GCM_SHA256")).isEqualTo(Status.OK);
        assertThat(CertificateHealthRules.cipherStatus("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA")).isEqualTo(Status.WARN);
        assertThat(CertificateHealthRules.cipherStatus("TLS_RSA_WITH_RC4_128_SHA")).isEqualTo(Status.FAIL);
        assertThat(CertificateHealthRules.cipherStatus(null)).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("3DES, CBC içerse bile ZAYIF kalır — zayıflık kontrolü önce bakılır")
    void weakBeatsAcceptable() {
        assertThat(CertificateHealthRules.cipherTier("TLS_RSA_WITH_3DES_EDE_CBC_SHA")).isEqualTo(CipherTier.WEAK);
    }

    // ── PFS ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TLS 1.3'te PFS her zaman açıktır — süit adına bakmaya gerek yok")
    void pfsAlwaysOnTls13() {
        assertThat(CertificateHealthRules.pfsStatus("TLSv1.3", "TLS_AES_256_GCM_SHA384")).isEqualTo(Status.OK);
        assertThat(CertificateHealthRules.pfsStatus("TLSv1.3", null)).isEqualTo(Status.OK);
    }

    @ParameterizedTest(name = "TLS1.2 + {0} → {1}")
    @CsvSource({
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, OK",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,   OK",
            "TLS_RSA_WITH_AES_128_GCM_SHA256,       FAIL",
            "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256,  FAIL",
            "TLS_YENI_SUIT,                         UNKNOWN",
    })
    void pfsFromCipherName(String suite, String expected) {
        assertThat(CertificateHealthRules.pfsStatus("TLSv1.2", suite)).isEqualTo(Status.valueOf(expected));
    }

    @Test
    @DisplayName("Ne sürüm ne süit biliniyorsa PFS UNKNOWN — 'kapalı' demek yanlış olurdu")
    void pfsUnknownWhenNothingKnown() {
        assertThat(CertificateHealthRules.pfsStatus(null, null)).isEqualTo(Status.UNKNOWN);
    }

    // ── İmza ve anahtar ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "imza {0} → {1}")
    @CsvSource({
            "SHA256withRSA, OK",
            "SHA384withECDSA, OK",
            "SHA1withRSA,   FAIL",
            "SHA-1withRSA,  FAIL",
            "MD5withRSA,    FAIL",
            "MD2withRSA,    FAIL",
    })
    void signatureStatus(String algo, String expected) {
        assertThat(CertificateHealthRules.signatureStatus(algo)).isEqualTo(Status.valueOf(expected));
    }

    @ParameterizedTest(name = "{0} {1} bit → {2}")
    @CsvSource({
            "RSA, 2048, OK",
            "RSA, 4096, OK",
            "RSA, 1024, FAIL",
            "DSA, 1024, FAIL",
            "EC,   256, OK",
            "EC,   224, FAIL",
            "Ed25519, 256, UNKNOWN",
    })
    void keySizeStatus(String algo, int size, String expected) {
        assertThat(CertificateHealthRules.keySizeStatus(algo, size)).isEqualTo(Status.valueOf(expected));
    }

    @Test
    @DisplayName("Anahtar bilgisi eksikse UNKNOWN")
    void keySizeUnknown() {
        assertThat(CertificateHealthRules.keySizeStatus("RSA", null)).isEqualTo(Status.UNKNOWN);
        assertThat(CertificateHealthRules.keySizeStatus(null, 2048)).isEqualTo(Status.UNKNOWN);
        assertThat(CertificateHealthRules.keySizeStatus("RSA", 0)).isEqualTo(Status.UNKNOWN);
    }

    // ── Zayıf Algoritma Raporu ile ORTAK hüküm ──────────────────────────────

    @Test
    @DisplayName("Rapor sınıflandırması: SHA-1 HIGH, MD5 CRITICAL, 1024-bit RSA CRITICAL")
    void weaknessSeverities() {
        List<String> w = new ArrayList<>();
        assertThat(CertificateHealthRules.classifyWeakness("SHA1withRSA", "RSA", 2048, w)).isEqualTo("HIGH");
        assertThat(w).hasSize(1).first().asString().contains("SHA1");

        w.clear();
        assertThat(CertificateHealthRules.classifyWeakness("MD5withRSA", "RSA", 2048, w)).isEqualTo("CRITICAL");

        w.clear();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "RSA", 1024, w)).isEqualTo("CRITICAL");

        w.clear();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "RSA", 1536, w)).isEqualTo("HIGH");

        w.clear();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "EC", 224, w)).isEqualTo("HIGH");

        w.clear();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "EC", 160, w)).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("Sağlam sertifikada zayıflık yok (null) ve bulgu listesi boş kalır")
    void noWeakness() {
        List<String> w = new ArrayList<>();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "RSA", 2048, w)).isNull();
        assertThat(w).isEmpty();
    }

    @Test
    @DisplayName("Rapor sınıflandırması ile satır hükmü ÇELİŞMEZ — aynı girdi, aynı yön")
    void reportAndRowAgree() {
        // Rapor "zayıf" diyorsa satır da FAIL olmalı; rapor sessizse satır OK olmalı.
        for (String[] c : new String[][] {
                { "SHA1withRSA", "RSA", "2048" },
                { "MD5withRSA", "RSA", "2048" },
                { "SHA256withRSA", "RSA", "1024" },
        }) {
            List<String> w = new ArrayList<>();
            String sev = CertificateHealthRules.classifyWeakness(c[0], c[1], Integer.valueOf(c[2]), w);
            boolean rowFails = CertificateHealthRules.signatureStatus(c[0]) == Status.FAIL
                    || CertificateHealthRules.keySizeStatus(c[1], Integer.valueOf(c[2])) == Status.FAIL;
            assertThat(sev).isNotNull();
            assertThat(rowFails).as("rapor zayıf dedi, satır da FAIL olmalı: %s", (Object) c).isTrue();
        }

        List<String> clean = new ArrayList<>();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "RSA", 2048, clean)).isNull();
        assertThat(CertificateHealthRules.signatureStatus("SHA256withRSA")).isEqualTo(Status.OK);
        assertThat(CertificateHealthRules.keySizeStatus("RSA", 2048)).isEqualTo(Status.OK);
    }


    // ── SAN kapsaması ───────────────────────────────────────────────────────
    //
    // Kullanıcı bildirimi (2026-08-23): "domain is covered by the certificate kısmı not verified
    // duruyor". İki hata birdeydi: satır SAN'a değil parmak izi PİNİNE bağlanmıştı ve eşleme de
    // yanlıştı ("COMPLETE" bekleniyordu, kod "OK" üretiyor) → sağlıklı her sertifika UNKNOWN.

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} ⊂ {1} → {2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "www.akbank.com,     www.akbank.com,       OK",
            "WWW.AKBANK.COM,     www.akbank.com,       OK",
            "www.akbank.com,     *.akbank.com,         OK",
            "akbank.com,         *.akbank.com,         FAIL",
            "a.b.akbank.com,     *.akbank.com,         FAIL",
            "www.akbank.com,     *.baska.com,          FAIL",
            "www.akbank.com,     akbank.com,           FAIL",
            "sub.akbank.com,     *.,                   FAIL",
    })
    void sanCoverageRules(String domain, String entry, String expected) {
        assertThat(CertificateHealthRules.sanCoverage(domain, java.util.List.of(entry)))
                .isEqualTo(Status.valueOf(expected));
    }

    @Test
    @DisplayName("Listede EŞLEŞEN bir giriş varsa yeterli (birden çok SAN normaldir)")
    void sanCoverageScansWholeList() {
        assertThat(CertificateHealthRules.sanCoverage("api.akbank.com",
                java.util.List.of("www.akbank.com", "akbank.com", "*.akbank.com")))
                .isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("SAN listesi boş/bilinmiyorsa UNKNOWN — 'kapsamıyor' demek yanlış olurdu")
    void sanCoverageUnknownWhenListMissing() {
        assertThat(CertificateHealthRules.sanCoverage("a.com", null)).isEqualTo(Status.UNKNOWN);
        assertThat(CertificateHealthRules.sanCoverage("a.com", java.util.List.of())).isEqualTo(Status.UNKNOWN);
        assertThat(CertificateHealthRules.sanCoverage(null, java.util.List.of("a.com"))).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("Bozuk girişler ATLANIR, listedeki geçerli eşleşme yine bulunur")
    void sanCoverageSkipsJunkEntries() {
        java.util.List<String> san = new java.util.ArrayList<>();
        san.add(null);
        san.add("   ");
        san.add("www.akbank.com");
        assertThat(CertificateHealthRules.sanCoverage("www.akbank.com", san)).isEqualTo(Status.OK);
    }

    // ── Süre ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} gün (uyarı 30 / kritik 7) → {1}")
    @CsvSource({
            "90,  OK",
            "31,  OK",
            "30,  WARN",
            "17,  WARN",
            "8,   WARN",
            "7,   FAIL",
            "0,   FAIL",
            "-3,  FAIL",
    })
    void expiryStatus(int days, String expected) {
        assertThat(CertificateHealthRules.expiryStatus(days, 30, 7)).isEqualTo(Status.valueOf(expected));
    }

    @Test
    @DisplayName("Kalan gün bilinmiyorsa UNKNOWN")
    void expiryUnknown() {
        assertThat(CertificateHealthRules.expiryStatus(null, 30, 7)).isEqualTo(Status.UNKNOWN);
    }

    // ── Etiket eşlemesi ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Durum etiketi: beklenen değerler eşlenir, gerisi UNKNOWN")
    void statusLabels() {
        assertThat(CertificateHealthRules.fromStatusLabel("VALID", "VALID", "REVOKED")).isEqualTo(Status.OK);
        assertThat(CertificateHealthRules.fromStatusLabel("revoked", "VALID", "REVOKED")).isEqualTo(Status.FAIL);
        assertThat(CertificateHealthRules.fromStatusLabel("UNKNOWN", "VALID", "REVOKED")).isEqualTo(Status.UNKNOWN);
        assertThat(CertificateHealthRules.fromStatusLabel(null, "VALID", "REVOKED")).isEqualTo(Status.UNKNOWN);
    }
}
