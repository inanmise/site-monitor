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

    private static PermissionGrant grant() {
        PermissionGrant g = new PermissionGrant();
        g.setRole("USER"); g.setResourceKey("inventory.list"); g.setAction("view"); g.setAllowed(true);
        g.setUpdatedBy("N12345"); g.setUpdatedAt("2026-09-25T10:00:00");
        return g;
    }

    @Test
    @DisplayName("GET matrix: global ADMIN 200 + catalog + can_edit=true + tam satır (kim değiştirdi dahil)")
    void getMatrix_globalAdminCanEdit() throws Exception {
        when(repo.findAll()).thenReturn(List.of(grant()));
        mvc.perform(get("/api/admin/permissions").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalog").isArray())
                .andExpect(jsonPath("$.can_edit").value(true))
                .andExpect(jsonPath("$.grants[0].updated_by").value("N12345"));
    }

    // 2026-09-25 kullanıcı kararı: admin dışındakiler matrisi OKUR ama değiştiremez. Eski "USER → 403"
    // iddiası bilinçli olarak ters çevrildi; yazma uçlarının 403'ü aşağıdaki testlerde aynen duruyor.
    @Test
    @DisplayName("GET matrix: USER / kapsamlı müdür 200 SALT OKUNUR — can_edit=false, satırda yalnız rol/kaynak/eylem/izin")
    void getMatrix_readOnlyForOthers() throws Exception {
        when(repo.findAll()).thenReturn(List.of(grant()));
        mvc.perform(get("/api/admin/permissions").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_edit").value(false))
                .andExpect(jsonPath("$.grants[0].role").value("USER"))
                .andExpect(jsonPath("$.grants[0].resource_key").value("inventory.list"))
                .andExpect(jsonPath("$.grants[0].allowed").value(true))
                .andExpect(jsonPath("$.grants[0].updated_by").doesNotExist())
                .andExpect(jsonPath("$.grants[0].updated_at").doesNotExist());

        MockHttpSession scoped = session("ADMIN");
        scoped.setAttribute("viewTeamIds", List.of(5L));    // kapsamlı müdür: rol ADMIN ama global DEĞİL
        mvc.perform(get("/api/admin/permissions").session(scoped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_edit").value(false));
        mvc.perform(put("/api/admin/permissions").session(scoped)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"resource_key\":\"inventory.list\",\"action\":\"view\",\"allowed\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/permissions/reset-to-defaults").session(scoped))
                .andExpect(status().isForbidden());
        verify(permissionService, never()).upsertGrant(any(), any(), any(), anyBoolean(), any());
        verify(permissionService, never()).seedDefaults();
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
