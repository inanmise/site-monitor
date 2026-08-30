package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sitemonitor.service.CertificateHealthRules.FLAG_HOSTNAME_MISMATCH;
import static com.sitemonitor.service.CertificateHealthRules.FLAG_UNTRUSTED_CA;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sertifika GÜVENLİK hükmü — süreden bağımsız.
 *
 * <p>Kusurun kanıtı: var olmayan bir alan adı NXDOMAIN-hijack ile bir ev modeminin yönetim
 * paneline çözüldü; modemin kendinden imzalı sertifikası ({@code CN=192.168.1.1},
 * issuer {@code ZTE-ROOT-CA}, 1775 gün kalan) yeşil "Geçerli" göründü ve HİÇ alarm üretmedi.
 * Chrome aynı adresi {@code ERR_CERT_AUTHORITY_INVALID} ile reddediyordu.
 *
 * <p>Hüküm zaten hesaplanıyordu (sağlık satırları); eksik olan onu tek bir adla dışarı vermekti.
 */
class CertificateSecurityFlagsTest {

    @Test
    @DisplayName("Sağlıklı sertifika: tam eşleşen SAN + güvenilir zincir → bayrak YOK")
    void healthy_noFlags() {
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("www.example.com", "example.com"), "TRUSTED"))
                .isEmpty();
    }

    @Test
    @DisplayName("Joker SAN: bir etiket derinliği eşleşir, iki etiket ve çıplak alan adı EŞLEŞMEZ")
    void wildcardFollowsRfc6125() {
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("*.example.com"), "TRUSTED")).isEmpty();
        assertThat(CertificateHealthRules.securityFlags(
                "a.b.example.com", List.of("*.example.com"), "TRUSTED"))
                .containsExactly(FLAG_HOSTNAME_MISMATCH);
        assertThat(CertificateHealthRules.securityFlags(
                "example.com", List.of("*.example.com"), "TRUSTED"))
                .containsExactly(FLAG_HOSTNAME_MISMATCH);
    }

    @Test
    @DisplayName("SAN listesi BOŞSA bayrak üretilmez — UNKNOWN, FAIL değildir")
    void emptySan_isUnknownNotFailure() {
        assertThat(CertificateHealthRules.securityFlags("www.example.com", List.of(), "TRUSTED")).isEmpty();
        assertThat(CertificateHealthRules.securityFlags("www.example.com", null, "TRUSTED")).isEmpty();
    }

    @Test
    @DisplayName("trust_status UNKNOWN/boş → güven bayrağı üretilmez (hafif kontrol yanlış alarm vermez)")
    void unknownTrust_isNotFailure() {
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("www.example.com"), "UNKNOWN")).isEmpty();
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("www.example.com"), null)).isEmpty();
    }

    @Test
    @DisplayName("Güvenilmeyen zincir tek başına UNTRUSTED_CA bayrağı üretir")
    void untrustedChain_flagged() {
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("www.example.com"), "UNTRUSTED"))
                .containsExactly(FLAG_UNTRUSTED_CA);
    }

    @Test
    @DisplayName("Modem paneli şekli (hijack): İKİ bayrak birden — bugün bu sertifika 'Geçerli' görünüyordu")
    void hijackedModemPanel_bothFlags() {
        // SAN yalnız iç IP'yi taşıyor, zincir hiçbir köke bağlanmıyor.
        assertThat(CertificateHealthRules.securityFlags(
                "www.olmayan-alan-adi.example", List.of("192.0.2.1"), "UNTRUSTED"))
                .containsExactly(FLAG_HOSTNAME_MISMATCH, FLAG_UNTRUSTED_CA);
    }

    @Test
    @DisplayName("Alan adı büyük/küçük harf farkı uyuşmazlık SAYILMAZ (DNS harf duyarsızdır)")
    void caseInsensitive() {
        assertThat(CertificateHealthRules.securityFlags(
                "WWW.Example.COM", List.of("www.example.com"), "TRUSTED")).isEmpty();
    }

    @Test
    @DisplayName("Bayrak listesi kalan gün / bitiş tarihi ile İLGİSİZDİR")
    void independentOfExpiry() {
        // 1775 gün kalan bir sertifika da bu host için kabul edilemez olabilir — kusurun özü buydu.
        assertThat(CertificateHealthRules.securityFlags(
                "www.example.com", List.of("192.0.2.1"), "UNTRUSTED")).hasSize(2);
        assertThat(CertificateHealthRules.expiryStatus(1775, 30, 7))
                .isEqualTo(CertificateHealthRules.Status.OK);   // süre tarafı tertemiz
    }
}
