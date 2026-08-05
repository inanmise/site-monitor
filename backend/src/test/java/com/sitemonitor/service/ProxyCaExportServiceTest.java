package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Yakalanan zincir → yapıştırılmaya hazır PEM + özet biçimlendirme. */
@ExtendWith(MockitoExtension.class)
class ProxyCaExportServiceTest {

    @Mock CertificateCheckerService certChecker;
    ProxyCaExportService svc;

    @BeforeEach
    void setUp() { svc = new ProxyCaExportService(certChecker); }

    private X509Certificate cert(String subjectCn, String issuerCn, int basicConstraints) {
        X509Certificate c = mock(X509Certificate.class);
        lenient().when(c.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + subjectCn));
        lenient().when(c.getIssuerX500Principal()).thenReturn(new X500Principal("CN=" + issuerCn));
        lenient().when(c.getBasicConstraints()).thenReturn(basicConstraints);
        lenient().when(c.getNotAfter()).thenReturn(new Date(0));
        try { lenient().when(c.getEncoded()).thenReturn((subjectCn + "-der").getBytes()); } catch (Exception ignore) {}
        return c;
    }

    @Test
    @DisplayName("leaf+intermediate+root → yaprağı atar, 2 CA'yı PEM'e koyar, kökü self-signed işaretler")
    void fullChain() throws Exception {
        X509Certificate leaf = cert("data.iana.org", "AKBANK-ISSUING-CA", -1);
        X509Certificate inter = cert("AKBANK-ISSUING-CA", "AKBANK-ROOT-CA-256", 0);
        X509Certificate root = cert("AKBANK-ROOT-CA-256", "AKBANK-ROOT-CA-256", 1);
        when(certChecker.captureProxyChain(anyString(), anyInt())).thenReturn(new X509Certificate[]{ leaf, inter, root });

        Map<String, Object> out = svc.capture("data.iana.org", 443);

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("ca_count")).isEqualTo(2);
        String pem = (String) out.get("ca_pem");
        assertThat(pem).contains("-----BEGIN CERTIFICATE-----").contains("-----END CERTIFICATE-----");
        // yaprak (data.iana.org) PEM'e girmemeli — 2 blok olmalı
        assertThat(pem.split("BEGIN CERTIFICATE", -1).length - 1).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) out.get("chain");
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).get("leaf")).isEqualTo(true);
        assertThat(chain.get(2).get("self_signed")).isEqualTo(true);   // kök
        assertThat(chain.get(1).get("self_signed")).isEqualTo(false);  // ara
    }

    @Test
    @DisplayName("yalnız yaprak gelirse → ca_count=0, PEM boş (ok yine true)")
    void leafOnly() throws Exception {
        X509Certificate leaf = cert("data.iana.org", "AKBANK-ISSUING-CA", -1);
        when(certChecker.captureProxyChain(anyString(), anyInt())).thenReturn(new X509Certificate[]{ leaf });

        Map<String, Object> out = svc.capture("data.iana.org", 443);

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("ca_count")).isEqualTo(0);
        assertThat((String) out.get("ca_pem")).isEmpty();
    }

    @Test
    @DisplayName("yakalama hatası → ok=false + error_class")
    void captureFails() throws Exception {
        when(certChecker.captureProxyChain(anyString(), anyInt()))
                .thenThrow(new IOException("Connect timed out"));

        Map<String, Object> out = svc.capture("data.iana.org", 443);

        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("error_class")).isEqualTo("CONNECT_TIMEOUT");
        assertThat(out.get("error")).isEqualTo("Connect timed out");
    }
}
