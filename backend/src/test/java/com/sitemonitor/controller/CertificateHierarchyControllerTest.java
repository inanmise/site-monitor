package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.manualcert.TestCerts;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ağ sertifikası hiyerarşi ucu (2026-10-12): okuma kapısı (izin, envanter, takım kapsamı SY/UG, 7/24 operatörü), elle
 * yüklenen kayıtta 409, kayıt ayarlarıyla AYNI yol (port / proxy / TLS kipi / zaman aşımı), kök güven deposundan, dakikalık
 * sınır (429), bağlantı hatasında istisna metni sızmayan 502, SSRF engelinde 400.
 */
@WebMvcTest(CertificateHierarchyController.class)
class CertificateHierarchyControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CertificateHierarchyController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean CertificateCheckerService checkerService;
    @MockitoBean TrustEvaluator trustEvaluator;
    @MockitoBean PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;

    private static TestCerts.Chain chain;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("a.example.com", days(90));
    }

    @BeforeEach
    void defaults() throws Exception {
        CertificateInventory a = inv("a.example.com", 5L, null, false);
        a.setPort(8443);
        a.setUseProxy(true);
        a.setTlsMode("default");
        a.setTimeoutSeconds(15);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(a));
        when(inventoryRepo.findByDomain("ug.example.com")).thenReturn(Optional.of(inv("ug.example.com", 5L, 9L, false)));
        when(inventoryRepo.findByDomain("up.example.com")).thenReturn(Optional.of(inv("up.example.com", 5L, null, true)));
        when(inventoryRepo.findByDomain("missing.example.com")).thenReturn(Optional.empty());
        when(checkerService.capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new CertificateCheckerService.PresentedChain(new X509Certificate[]{chain.leaf(), chain.inter()}, true));
        when(trustEvaluator.trustAnchors()).thenReturn(new TrustEvaluator.Anchors(List.of(chain.root()), List.of()));
    }

    private static CertificateInventory inv(String domain, Long team, Long ug, boolean manual) {
        CertificateInventory i = new CertificateInventory();
        i.setId(Math.abs((long) domain.hashCode()));
        i.setDomain(domain);
        i.setTeamId(team);
        i.setUgTeamId(ug);
        i.setActive(true);
        if (manual) i.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return i;
    }

    private static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    @Test
    @DisplayName("takım üyesi: kayıt ayarlarıyla AYNI yol; kök güven deposundan; düğümler kök ilk, her birinin bitiş tarihi")
    void teamMemberGetsHierarchy() throws Exception {
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("a.example.com"))
                .andExpect(jsonPath("$.data.port").value(8443))
                .andExpect(jsonPath("$.data.via").value("proxy"))
                .andExpect(jsonPath("$.data.root_source").value("trust_store"))
                .andExpect(jsonPath("$.data.trust_store").value("system"))
                .andExpect(jsonPath("$.data.nodes.length()").value(3))
                .andExpect(jsonPath("$.data.nodes[0].role").value("root"))
                .andExpect(jsonPath("$.data.nodes[0].source").value("trust_store"))
                .andExpect(jsonPath("$.data.nodes[1].role").value("intermediate"))
                .andExpect(jsonPath("$.data.nodes[2].role").value("leaf"))
                .andExpect(jsonPath("$.data.nodes[2].not_after").exists())
                .andExpect(jsonPath("$.data.nodes[2].days_remaining").exists());
        verify(checkerService).capturePresentedChain("a.example.com", 8443, true, "default", 15);
    }

    @Test
    @DisplayName("UG takımı üyesi ve 7/24 operatörü okur; başka takım 403; envanterde yok 404; izin yoksa 403 — el sıkışma yapılmaz")
    void readGate() throws Exception {
        mvc.perform(get("/api/certificates/ug.example.com/hierarchy").session(session("USER", 9L, 2L)))
                .andExpect(status().isOk());
        MockHttpSession noc = session("USER", 3L, 3L);
        noc.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        noc.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, List.of(3L));
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(noc)).andExpect(status().isOk());

        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 7L, 4L)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/certificates/missing.example.com/hierarchy").session(session("ADMIN", null, 5L)))
                .andExpect(status().isNotFound());
        doThrow(new SecurityException("izin yok")).when(permissionService)
                .require(any(HttpSession.class), eq("inventory.list"), eq("view"));
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 6L)))
                .andExpect(status().isForbidden());
        verify(checkerService, never()).capturePresentedChain(eq("missing.example.com"), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("elle yüklenen kayıt → 409 MANUAL_CERT, ağa çıkılmaz")
    void manualIsConflict() throws Exception {
        mvc.perform(get("/api/certificates/up.example.com/hierarchy").session(session("USER", 5L, 1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_CERT"));
        verify(checkerService, never()).capturePresentedChain(eq("up.example.com"), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("bağlantı hatası → 502 HIERARCHY_UNREACHABLE, istisna metni istemciye gitmez; SSRF engeli → 400 BLOCKED")
    void failures() throws Exception {
        doThrow(new IOException("Connection reset by 10.1.2.3:8443"))
                .when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 1L)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("HIERARCHY_UNREACHABLE"))
                .andExpect(jsonPath("$.error", not(containsString("10.1.2.3"))));
        doThrow(new SsrfGuard.BlockedException("iç ağ engelli"))
                .when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 2L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLOCKED"))
                .andExpect(jsonPath("$.error", not(containsString("iç ağ engelli"))));
    }

    @Test
    @DisplayName("kullanıcı × alan adı başına dakikada en çok MAX_PER_MINUTE; aşımda 429 ve el sıkışma yapılmaz")
    void rateLimited() throws Exception {
        MockHttpSession s = session("USER", 5L, 42L);
        for (int i = 0; i < CertificateHierarchyController.MAX_PER_MINUTE; i++) {
            mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(s)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(s))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(checkerService, org.mockito.Mockito.times(CertificateHierarchyController.MAX_PER_MINUTE))
                .capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        // başka kullanıcı etkilenmez
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 43L)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("inceleme: ad çözülemezse 502 DNS_UNRESOLVABLE (güvenlik engeli DEĞİL); beklenmeyen hata 500, bağlantı hatası gibi gösterilmez")
    void dnsAndUnexpectedErrors() throws Exception {
        doThrow(new SsrfGuard.UnresolvableHostException("çözülemedi"))
                .when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 11L)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DNS_UNRESOLVABLE"));
        doThrow(new IllegalStateException("kod hatası 10.9.9.9"))
                .when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 12L)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("HIERARCHY_FAILED"))
                .andExpect(jsonPath("$.error", not(containsString("10.9.9.9"))));
    }

    @Test
    @DisplayName("inceleme: önizlemenin başarılı yolu (via / tls_mode) kullanılır; tanınmayan ipucu yok sayılır")
    void routeHints() throws Exception {
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").param("via", "direct").param("tls_mode", "browser")
                        .session(session("USER", 5L, 21L)))
                .andExpect(status().isOk());
        verify(checkerService).capturePresentedChain("a.example.com", 8443, false, "browser", 15);
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").param("via", "x").param("tls_mode", "evil")
                        .session(session("USER", 5L, 22L)))
                .andExpect(status().isOk());
        verify(checkerService, org.mockito.Mockito.atLeastOnce()).capturePresentedChain("a.example.com", 8443, true, "default", 15);
    }

    @Test
    @DisplayName("inceleme 2: izin her yolda geri verilir (200 / 429 / 502 / 500); 'meşgul' reddi dakikalık hakkı yemez")
    void permitsAlwaysReleasedAndBusyDoesNotSpendQuota() throws Exception {
        int max = CertificateHierarchyController.MAX_CONCURRENT;
        MockHttpSession s = session("USER", 5L, 77L);
        // meşgul: tüm izinler dolu → 429, kullanıcının hakkı harcanmaz
        controller.inFlight.acquire(max);
        try {
            mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(s))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            controller.inFlight.release(max);
        }
        for (int i = 0; i < CertificateHierarchyController.MAX_PER_MINUTE; i++) {
            mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(s)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(s)).andExpect(status().isTooManyRequests());
        org.assertj.core.api.Assertions.assertThat(controller.inFlight.availablePermits()).isEqualTo(max);
        doThrow(new IOException("reset")).when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 78L))).andExpect(status().isBadGateway());
        org.assertj.core.api.Assertions.assertThat(controller.inFlight.availablePermits()).isEqualTo(max);
        doThrow(new IllegalStateException("bug")).when(checkerService).capturePresentedChain(anyString(), anyInt(), anyBoolean(), any(), any());
        mvc.perform(get("/api/certificates/a.example.com/hierarchy").session(session("USER", 5L, 79L))).andExpect(status().isInternalServerError());
        org.assertj.core.api.Assertions.assertThat(controller.inFlight.availablePermits()).isEqualTo(max);
    }

    @Test
    @DisplayName("inceleme 2: proxy'siz kayıtta via=proxy ipucu, otomatik geri düşüş kapalıyken proxy'ye zorlamaz")
    void proxyHintNeedsRecordOrAutoFallback() throws Exception {
        mvc.perform(get("/api/certificates/ug.example.com/hierarchy").param("via", "proxy").session(session("USER", 9L, 31L)))
                .andExpect(status().isOk());
        verify(checkerService).capturePresentedChain(eq("ug.example.com"), anyInt(), eq(false), any(), any());
        when(checkerService.autoProxyFallbackEnabled()).thenReturn(true);
        mvc.perform(get("/api/certificates/ug.example.com/hierarchy").param("via", "proxy").session(session("USER", 9L, 32L)))
                .andExpect(status().isOk());
        verify(checkerService).capturePresentedChain(eq("ug.example.com"), anyInt(), eq(true), any(), any());
    }
}
