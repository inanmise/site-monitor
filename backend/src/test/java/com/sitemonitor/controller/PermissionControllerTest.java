package com.sitemonitor.controller;

import com.sitemonitor.model.PermissionGrant;
import com.sitemonitor.repository.PermissionGrantRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PermissionController.class)
class PermissionControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PermissionService permissionService;
    @MockitoBean PermissionGrantRepository repo;
    @MockitoBean AuditService auditService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    @Test
    @DisplayName("GET matrix: ADMIN 200 + catalog; USER 403")
    void getMatrix_adminOnly() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        mvc.perform(get("/api/admin/permissions").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalog").isArray());

        mvc.perform(get("/api/admin/permissions").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT grant: geçerli body 200; allowed string 'false' kabul edilir")
    void upsertGrant_ok() throws Exception {
        when(permissionService.upsertGrant(eq("USER"), eq("inventory.list"), eq("view"), anyBoolean(), any()))
                .thenReturn(new PermissionGrant());

        mvc.perform(put("/api/admin/permissions").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"resource_key\":\"inventory.list\",\"action\":\"view\",\"allowed\":true}"))
                .andExpect(status().isOk());

        // String "false" → ClassCastException(500) DEĞİL, normal işlenir
        mvc.perform(put("/api/admin/permissions").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"resource_key\":\"inventory.list\",\"action\":\"view\",\"allowed\":\"false\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT grant: eksik alan → 400; USER → 403")
    void upsertGrant_validationAndAuth() throws Exception {
        mvc.perform(put("/api/admin/permissions").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"action\":\"view\",\"allowed\":true}")) // resource_key yok
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/admin/permissions").session(session("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"resource_key\":\"inventory.list\",\"action\":\"view\",\"allowed\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST reset-to-defaults: ADMIN 200 + seedDefaults çağrılır")
    void reset_ok() throws Exception {
        mvc.perform(post("/api/admin/permissions/reset-to-defaults").session(session("ADMIN")))
                .andExpect(status().isOk());
        verify(permissionService).seedDefaults();
    }
}
