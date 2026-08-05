package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    private static LatestCheck lc(String domain, String sig, String keyAlgo, Integer keySize) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain);
        c.setSignatureAlgorithm(sig);
        c.setPublicKeyAlgorithm(keyAlgo);
        c.setPublicKeySize(keySize);
        c.setStatus("valid");
        return c;
    }

    @Test
    @DisplayName("listAudit: AUDIT 200 + sayfalı sonuç; USER 403")
    void listAudit_access() throws Exception {
        when(auditLogRepo.findAdvanced(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog()), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/admin/audit").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/admin/audit").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("resourceHistory: AUDIT 200 + kayıt sayısı; USER 403 (izolasyon)")
    void resourceHistory_access() throws Exception {
        when(auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(eq("PORT_MONITOR"), eq("7"), any()))
                .thenReturn(List.of(new AuditLog()));
        mvc.perform(get("/api/admin/audit/resource/PORT_MONITOR/7").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/audit/resource/PORT_MONITOR/7").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("actorHistory: AUDIT 200 (bir kullanıcının tüm eylemleri)")
    void actorHistory_ok() throws Exception {
        when(auditLogRepo.findByActorIdOrderByEventTimeDesc(eq(5L), any())).thenReturn(List.of(new AuditLog()));
        mvc.perform(get("/api/admin/audit/actor/5").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("integrity: verifyChain sonucu (ok + checked) döner")
    void integrity_ok() throws Exception {
        when(auditService.verifyChain())
                .thenReturn(new com.sitemonitor.service.AuditService.ChainVerification(true, null, null, 42));
        mvc.perform(get("/api/admin/audit/integrity").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.checked").value(42));
    }

    @Test
    @DisplayName("export: CSV döner + dışa aktarma İŞLEMİ AUDIT_EXPORT olarak denetlenir (denetimin denetimi)")
    void export_selfAudited() throws Exception {
        when(auditLogRepo.findAdvanced(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog()), PageRequest.of(0, 1), 1));
        mvc.perform(get("/api/admin/audit/export?format=csv").session(session("AUDIT")))
                .andExpect(status().isOk());
        verify(auditService).recordAction(eq("AUDIT_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("AUDIT_LOG"), eq("export"), contains("csv"));
    }

    @Test
    @DisplayName("auditStats: ADMIN 200 + istatistik anahtarları")
    void auditStats_ok() throws Exception {
        // /audit/stats artık AuditService.buildStats()'ı (60 sn cache) çağırır — slice'ta mock.
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total_24h", 5L);
        stats.put("total_7d", 10L);
        when(auditService.buildStats()).thenReturn(stats);

        mvc.perform(get("/api/admin/audit/stats").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_24h").exists())
                .andExpect(jsonPath("$.data.total_7d").exists());
    }

    @Test
    @DisplayName("weak-algorithms: classifyWeakness sınır değerleri (MD5/SHA1/RSA1024/EC200/EC128) doğru sınıflanır")
    void weakAlgorithms_classification() throws Exception {
        when(latestCheckRepo.findWeakAlgorithmCandidates()).thenReturn(List.of(
                lc("md5.example",    "MD5withRSA",     "RSA", 2048),   // CRITICAL (hash)
                lc("sha1.example",   "SHA1withRSA",    "RSA", 2048),   // HIGH (hash)
                lc("rsa1024.example","SHA256withRSA",  "RSA", 1024),   // CRITICAL (kısa anahtar <=1024)
                lc("ec200.example",  "SHA256withECDSA","EC",  200),    // HIGH (EC <256, >=192)
                lc("ec128.example",  "SHA256withECDSA","EC",  128),    // CRITICAL (EC <192)
                lc("ok.example",     "SHA256withRSA",  "RSA", 2048)    // zayıf değil → elenir
        ));
        when(inventoryRepo.findByDomainIn(anyList())).thenReturn(List.of());
        when(teamRepo.findAll()).thenReturn(List.of());

        mvc.perform(get("/api/admin/audit/weak-algorithms").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))      // ok.example elendi
                .andExpect(jsonPath("$.critical").value(3))   // md5, rsa1024, ec128
                .andExpect(jsonPath("$.high").value(2));      // sha1, ec200
    }
}
