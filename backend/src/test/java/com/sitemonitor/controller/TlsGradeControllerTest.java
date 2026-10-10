package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import com.sitemonitor.service.tlsgrade.TlsProfileJobService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TLS notu uçları (2026-10-10): okuma kapısı (takım kapsamı / 404 + güvenlik olayı), yeniden tarama kapısı
 * (diagnostics.run + takımı işletebilme), elle yüklenen sertifikada 409, dakikalık sınır (429), son düşüşlerin kapsamı.
 */
@WebMvcTest(TlsGradeController.class)
class TlsGradeControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean TlsGradeService gradeService;
    @MockitoBean TlsProfileJobService jobService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateService certificateService;

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(true);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(inv("a.example.com", 5L, false)));
        when(inventoryRepo.findByDomain("up.example.com")).thenReturn(Optional.of(inv("up.example.com", 5L, true)));
        when(inventoryRepo.findByDomain("missing.example.com")).thenReturn(Optional.empty());
        when(latestCheckRepo.findById(anyString())).thenReturn(Optional.empty());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grade", "B");
        detail.put("decisive", List.of("TLS10_ENABLED"));
        when(gradeService.detail(any(), any())).thenReturn(detail);
        TlsProfile p = new TlsProfile();
        p.setDomain("a.example.com");
        p.setStatus(TlsProfile.STATUS_OK);
        when(jobService.rescan(any(), anyString())).thenReturn(p);
    }

    private static CertificateInventory inv(String domain, Long team, boolean manual) {
        CertificateInventory i = new CertificateInventory();
        i.setId(Math.abs((long) domain.hashCode()));
        i.setDomain(domain);
        i.setTeamId(team);
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
    @DisplayName("Ayrıntı: takım üyesi okur; can_rescan izin + takım kapsamından")
    void detailForTeamMember() throws Exception {
        mvc.perform(get("/api/certificates/a.example.com/tls-grade").session(session("USER", 5L, 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("B"))
                .andExpect(jsonPath("$.data.can_rescan").value(true));
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(get("/api/certificates/a.example.com/tls-grade").session(session("USER", 5L, 1L)))
                .andExpect(jsonPath("$.data.can_rescan").value(false));
    }

    @Test
    @DisplayName("Ayrıntı: başka takımın kaydı → 404 + güvenlik olayı (varlık sızmaz)")
    void detailOtherTeamIsNotFound() throws Exception {
        mvc.perform(get("/api/certificates/a.example.com/tls-grade").session(session("USER", 2L, 2L)))
                .andExpect(status().isNotFound());
        verify(auditService).recordSecurityEvent(eq("CERT_HEALTH_DENIED"), any(), any(), eq("CERTIFICATE"),
                eq("a.example.com"), anyString());
        mvc.perform(get("/api/certificates/missing.example.com/tls-grade").session(session("ADMIN", null, 3L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Yeniden tarama: izin yoksa 403, servis çağrılmaz")
    void rescanNeedsPermission() throws Exception {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(post("/api/certificates/a.example.com/tls-grade/rescan").session(session("USER", 5L, 4L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("diagnostics.run")));
        verify(jobService, never()).rescan(any(), anyString());
    }

    @Test
    @DisplayName("Yeniden tarama: global admin → 200, denetim CERT_TLS_PROFILE_RESCAN")
    void rescanOk() throws Exception {
        mvc.perform(post("/api/certificates/a.example.com/tls-grade/rescan").session(session("ADMIN", null, 5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("B"))
                .andExpect(jsonPath("$.data.can_rescan").value(true));
        verify(jobService).rescan(any(), eq("user5"));
        verify(auditService).recordAction(eq("CERT_TLS_PROFILE_RESCAN"), any(HttpSession.class), eq("CERTIFICATE"),
                eq("a.example.com"), anyString(), isNull());
    }

    @Test
    @DisplayName("Yeniden tarama: elle yüklenen sertifika → 409 MANUAL_CERT")
    void rescanManualConflict() throws Exception {
        mvc.perform(post("/api/certificates/up.example.com/tls-grade/rescan").session(session("ADMIN", null, 6L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_CERT"));
        verify(jobService, never()).rescan(any(), anyString());
    }

    @Test
    @DisplayName("Yeniden tarama: aynı alan adında dakikada 4. istek → 429 (İngilizce ileti)")
    void rescanRateLimited() throws Exception {
        // Ayrı alan adı: denetleyici tekil (bağlam testler arasında paylaşılır) — pencere başka testten dolmasın
        when(inventoryRepo.findByDomain("rl.example.com")).thenReturn(Optional.of(inv("rl.example.com", 5L, false)));
        for (long u = 10; u < 13; u++) {
            mvc.perform(post("/api/certificates/rl.example.com/tls-grade/rescan").session(session("ADMIN", null, u)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/certificates/rl.example.com/tls-grade/rescan").session(session("ADMIN", null, 13L))
                        .header("X-Lang", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("per minute")));
    }

    @Test
    @DisplayName("Son düşüşler: kapsamlı kullanıcı kendi takım listesiyle, global admin kapsamsız (null) sorgular")
    void dropsScope() throws Exception {
        when(gradeService.drops(any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(gradeService.coverage(any())).thenReturn(Map.of("endpoints", 0));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        mvc.perform(get("/api/tls-grade/drops").session(session("USER", 5L, 20L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30));
        verify(gradeService).drops(eq(List.of(5L)), eq(30), eq(50), any());
        mvc.perform(get("/api/tls-grade/drops?days=7").session(session("ADMIN", null, 21L)))
                .andExpect(status().isOk());
        verify(gradeService).drops(isNull(), eq(7), eq(50), any());
    }
}
