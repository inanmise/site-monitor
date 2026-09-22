package com.sitemonitor.controller;

import com.sitemonitor.model.Platform;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.PlatformService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Platform kataloğu uçları (2026-09-22): okuma her oturuma, yazma Ayarlar yetkisine; denetim izi; kullanımdaki platform 409. */
@WebMvcTest(PlatformController.class)
class PlatformControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PlatformService platforms;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        doThrow(new SecurityException("no perm")).when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString());
    }

    private static Platform p(long id, String code, String name) {
        Platform p = new Platform(); p.setId(id); p.setCode(code); p.setName(name); p.setActive(true); p.setSortOrder(10);
        return p;
    }

    @Test
    @DisplayName("GET: oturumsuz 401; USER aktif listeyi görür; ?all=true yalnız Ayarlar yetkisiyle (kullanım sayısı dâhil)")
    void list() throws Exception {
        mvc.perform(get("/api/admin/platforms")).andExpect(status().isUnauthorized());
        when(platforms.listActive()).thenReturn(List.of(p(1, "IIS", "IIS (Windows)")));
        mvc.perform(get("/api/admin/platforms").session(session("sre1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("IIS"))
                .andExpect(jsonPath("$.data[0].name").value("IIS (Windows)"));
        mvc.perform(get("/api/admin/platforms").param("all", "true").session(session("sre1"))).andExpect(status().isForbidden());
        when(platforms.listWithUsage()).thenReturn(List.of(Map.of("code", "IIS", "usage", 4L)));
        mvc.perform(get("/api/admin/platforms").param("all", "true").session(session("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].usage").value(4));
    }

    @Test
    @DisplayName("POST/PUT: Ayarlar yetkisi + denetim izi; geçersiz kod 400")
    void createUpdate() throws Exception {
        mvc.perform(post("/api/admin/platforms").session(session("sre1")).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"K8S\",\"name\":\"K8s\"}"))
                .andExpect(status().isForbidden());
        when(platforms.create(eq("K8S_PROD"), eq("K8s Prod"), any(), any(), eq("admin"))).thenReturn(p(9, "K8S_PROD", "K8s Prod"));
        mvc.perform(post("/api/admin/platforms").session(session("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"K8S_PROD\",\"name\":\"K8s Prod\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("K8S_PROD"));
        verify(auditService).recordAction(eq("PLATFORM_CREATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("PLATFORM"), eq("K8S_PROD"), any());
        when(platforms.create(any(), any(), any(), any(), any())).thenThrow(new IllegalArgumentException("Kod 2–20 karakter"));
        mvc.perform(post("/api/admin/platforms").session(session("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"a b\",\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
        when(platforms.get(9L)).thenReturn(p(9, "K8S_PROD", "K8s Prod"));
        when(platforms.update(eq(9L), eq("Kubernetes Prod"), any(), eq(false), any())).thenReturn(p(9, "K8S_PROD", "Kubernetes Prod"));
        mvc.perform(put("/api/admin/platforms/9").session(session("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kubernetes Prod\",\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Kubernetes Prod"));
        verify(auditService).recordAction(eq("PLATFORM_UPDATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("PLATFORM"), eq("K8S_PROD"), any());
    }

    @Test
    @DisplayName("DELETE: kullanımdaysa 409 (denetim yazılmaz); değilse silinir + PLATFORM_DELETE")
    void delete_() throws Exception {
        when(platforms.get(3L)).thenReturn(p(3, "LINUX", "Linux"));
        when(platforms.delete(3L)).thenReturn(false);
        mvc.perform(delete("/api/admin/platforms/3").session(session("admin"))).andExpect(status().isConflict());
        verify(auditService, never()).recordAction(eq("PLATFORM_DELETE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());
        when(platforms.delete(3L)).thenReturn(true);
        mvc.perform(delete("/api/admin/platforms/3").session(session("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        verify(auditService).recordAction(eq("PLATFORM_DELETE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("PLATFORM"), eq("LINUX"), any());
    }

    private MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));
        return s;
    }
}
