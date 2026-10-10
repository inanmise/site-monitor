package com.sitemonitor.controller;

import com.sitemonitor.service.*;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kripto envanteri uçları (2026-10-10): {@code weak_algo.read/view} kapısı (Zayıf Algoritma raporuyla aynı), görüş
 * kapsamı aynen servise geçer (global → null, kapsamlı → kendi takımları, 7/24 operatörü GENİŞLEMEZ), {@code fresh},
 * dışa aktarım denetim izi ve hata sözleşmesi (bilinmeyen biçim → 400 VALIDATION_FAILED + field).
 */
@WebMvcTest(CryptoInventoryController.class)
class CryptoInventoryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CryptoInventoryService cryptoInventoryService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(argThat((HttpSession s) -> s != null && !"ok".equals(s.getAttribute("perm"))),
                         eq("weak_algo.read"), eq("view"));
        when(cryptoInventoryService.build(any(), anyBoolean())).thenReturn(Map.of("rows", List.of(), "summary", Map.of("total", 0)));
    }

    private MockHttpSession session(String role, List<Long> viewTeamIds, boolean perm) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        if (perm) s.setAttribute("perm", "ok");
        return s;
    }

    @Test
    @DisplayName("oturumsuz → 401; weak_algo.read yok → 403 (her iki uçta)")
    void gates() throws Exception {
        mvc.perform(get("/api/crypto-inventory")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/crypto-inventory").session(session("USER", List.of(4L), false))).andExpect(status().isForbidden());
        mvc.perform(post("/api/crypto-inventory/export-audit").session(session("USER", List.of(4L), false))
                .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"xlsx\",\"rows\":3}")).andExpect(status().isForbidden());
        verifyNoInteractions(cryptoInventoryService);
        verify(auditService, never()).recordAction(eq("CRYPTO_INVENTORY_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());
    }

    @Test
    @DisplayName("kapsam: global admin/AUDIT → null (tüm takımlar); kapsamlı → görüş takımları; fresh=1 geçer")
    void scopePassthrough() throws Exception {
        mvc.perform(get("/api/crypto-inventory").session(session("ADMIN", null, true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.summary.total").value(0));
        verify(cryptoInventoryService).build(isNull(), eq(false));

        mvc.perform(get("/api/crypto-inventory?fresh=1").session(session("ADMIN", List.of(7L, 9L), true))).andExpect(status().isOk());
        verify(cryptoInventoryService).build(eq(List.of(7L, 9L)), eq(true));

        mvc.perform(get("/api/crypto-inventory").session(session("AUDIT", null, true))).andExpect(status().isOk());
        verify(cryptoInventoryService, times(2)).build(isNull(), eq(false));
    }

    @Test
    @DisplayName("7/24 operatörü bu raporda genişlemez: kapsam yine viewTeamIds")
    void nocOperatorNotWidened() throws Exception {
        MockHttpSession s = session("USER", List.of(4L), true);
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        mvc.perform(get("/api/crypto-inventory").session(s)).andExpect(status().isOk());
        verify(cryptoInventoryService).build(eq(List.of(4L)), eq(false));
    }

    @Test
    @DisplayName("dışa aktarım denetim izi: CRYPTO_INVENTORY_EXPORT (biçim, satır, süzgeç, kapsam)")
    void exportAudit() throws Exception {
        mvc.perform(post("/api/crypto-inventory/export-audit").session(session("USER", List.of(4L), true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\",\"rows\":42,\"filtered\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.format").value("pdf")).andExpect(jsonPath("$.data.rows").value(42));
        verify(auditService).recordAction(eq("CRYPTO_INVENTORY_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CRYPTO_INVENTORY"), eq("export"),
                eq("{\"format\":\"pdf\",\"rows\":42,\"filtered\":true,\"scope\":\"TEAM\"}"));
    }

    @Test
    @DisplayName("bilinmeyen biçim → 400 VALIDATION_FAILED + field=format; denetim kaydı yazılmaz")
    void exportAuditRejectsUnknownFormat() throws Exception {
        mvc.perform(post("/api/crypto-inventory/export-audit").session(session("ADMIN", null, true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"docx\",\"rows\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("format"));
        verify(auditService, never()).recordAction(eq("CRYPTO_INVENTORY_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());
    }
}
