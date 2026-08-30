package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.HttpURLConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OCSP/CRL indirmesinin SSRF kapısı — {@link ChainValidationService#guardTarget}.
 *
 * <p><b>Kapatılan açık.</b> Hedef URL, izlenen sunucunun sunduğu sertifikanın AIA / CRL-DP
 * uzantısından okunur: tamamen karşı tarafın yazdığı bir dize. Kapı yokken AIA'sında
 * {@code http://169.254.169.254/…} ya da bir iç servis adresi taşıyan bir sertifika,
 * uygulamaya iç ağa OCSP POST'u ve CRL GET'i attırabiliyordu; sonuç {@code revocation_status}
 * ve yanıt süresi üzerinden kör bir orakl olarak okunabiliyordu. Hedefi envantere ekleyebilen
 * herkes tetikleyebiliyordu.
 */
class OcspCrlSsrfGuardTest {

    private ChainValidationService service;
    private SsrfGuard guard;

    @BeforeEach
    void setUp() {
        service = new ChainValidationService();
        guard = mock(SsrfGuard.class);
        ReflectionTestUtils.setField(service, "ssrfGuard", guard);
    }

    @Test
    @DisplayName("Kapı: bağlantı açılmadan ÖNCE host SsrfGuard'a sorulur")
    void guardTarget_httpUrl_validatesHost() throws Exception {
        service.guardTarget("http://ocsp.example.com/status");
        verify(guard).validate("ocsp.example.com");
    }

    @Test
    @DisplayName("Kapı: politika reddi bağlantıyı DURDURUR (metadata uçu sertifikanın AIA'sından gelse bile)")
    void guardTarget_blockedHost_propagates() {
        doThrow(new SsrfGuard.BlockedException("cloud-metadata"))
                .when(guard).validate("169.254.169.254");

        assertThatThrownBy(() -> service.openWithProxy("http://169.254.169.254/ocsp"))
                .isInstanceOf(SsrfGuard.BlockedException.class)
                .hasMessageContaining("cloud-metadata");
    }

    @Test
    @DisplayName("Kapı: çözülemeyen host DURDURMAZ — vekil arkasında split-DNS meşrudur")
    void guardTarget_unresolvableHost_tolerated() {
        when(guard.validate("ocsp.internal-ca.example.com"))
                .thenThrow(new SsrfGuard.UnresolvableHostException("çözülemedi"));

        // İstisna yutulur; bağlantı denenir (pod çözemese de vekil çözebilir).
        assertThat(catchIo(() -> service.guardTarget("http://ocsp.internal-ca.example.com/"))).isNull();
    }

    @Test
    @DisplayName("Şema allow-list: ldap:// CRL-DP açıkça reddedilir ve guard'a hiç gitmez")
    void guardTarget_ldapScheme_rejected() {
        assertThatThrownBy(() -> service.guardTarget("ldap://ca.example.com/cn=CRL"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("desteklenmeyen");
        verify(guard, never()).validate(anyString());
    }

    @Test
    @DisplayName("Şema allow-list: file:// reddedilir (yerel dosya okuma yolu kapalı)")
    void guardTarget_fileScheme_rejected() {
        assertThatThrownBy(() -> service.guardTarget("file:///etc/passwd"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("desteklenmeyen");
        verify(guard, never()).validate(anyString());
    }

    @Test
    @DisplayName("Host'suz URL reddedilir (guard'a boş host geçirilmez)")
    void guardTarget_noHost_rejected() {
        assertThatThrownBy(() -> service.guardTarget("http:///ocsp"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("host yok");
        verify(guard, never()).validate(anyString());
    }

    @Test
    @DisplayName("Yönlendirme KAPALI: kapıdan geçen ilk host'tan sonra başka hedefe sıçranmaz")
    void openWithProxy_doesNotFollowRedirects() throws Exception {
        // Bağlantı AÇILMAZ (connect yok) — yalnız sözleşme okunur.
        HttpURLConnection conn = service.openWithProxy("http://ocsp.example.com/status");
        assertThat(conn.getInstanceFollowRedirects())
                .as("OCSP/CRL yönlendirmeye ihtiyaç duymaz; takip, guard'dan geçmemiş bir hedefe atlamaktır")
                .isFalse();
    }

    /** Fırlatılan IOException'ı döndürür; fırlatılmadıysa null. */
    private IOException catchIo(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
