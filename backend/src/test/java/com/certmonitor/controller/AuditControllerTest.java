package com.certmonitor.controller;

import com.certmonitor.model.AuditLog;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.repository.AuditLogRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired MockMvc mvc;

    @MockBean RememberMeService rememberMeService;
    @MockBean UserService userService;
    @MockBean AuthController authController;
    @MockBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockBean AuditLogRepository auditLogRepo;
    @MockBean LatestCheckRepository latestCheckRepo;
    @MockBean CertificateInventoryRepository inventoryRepo;
    @MockBean TeamRepository teamRepo;

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
        when(auditLogRepo.findFiltered(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog()), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/admin/audit").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/admin/audit").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("auditStats: ADMIN 200 + istatistik anahtarları")
    void auditStats_ok() throws Exception {
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
