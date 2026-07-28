package com.certmonitor.controller;

import com.certmonitor.model.GuideLink;
import com.certmonitor.repository.GuideLinkRepository;
import com.certmonitor.service.RememberMeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for GuideLinkController. Verifies:
 *  - unauthenticated callers get 401 from the auth interceptor
 *  - non-admin authenticated users can GET but not POST/PUT/DELETE
 *  - admins can perform full CRUD
 *  - validation rejects missing required fields with 400
 *  - missing id on PUT/DELETE surfaces 404 via GlobalExceptionHandler
 */
@WebMvcTest(GuideLinkController.class)
class GuideLinkControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean GuideLinkRepository repo;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean com.certmonitor.service.UserService userService;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.certmonitor.repository.AppUserRepository userRepo;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.PermissionService permissionService;
    @MockitoBean com.certmonitor.service.AuditService auditService;

    @Test
    @DisplayName("GET /api/guide-links without session returns 401")
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/guide-links"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/guide-links as USER returns 200 with sorted data")
    void list_asUser_returnsLinks() throws Exception {
        when(repo.findAllByOrderByCategoryAscSortOrderAscIdAsc())
                .thenReturn(List.of(link(1L, "Netscaler", "Vserver", "https://wiki/x")));

        mvc.perform(get("/api/guide-links").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].title").value("Vserver"))
                .andExpect(jsonPath("$.data[0].category").value("Netscaler"));
    }

    @Test
    @DisplayName("POST /api/guide-links as USER returns 403")
    void create_asUser_returnsForbidden() throws Exception {
        mvc.perform(post("/api/guide-links")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"X\",\"title\":\"Y\",\"url\":\"https://z\"}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/guide-links as ADMIN creates a link")
    void create_asAdmin_returnsOk() throws Exception {
        when(repo.save(any())).thenAnswer(inv -> {
            GuideLink g = inv.getArgument(0);
            g.setId(42L);
            return g;
        });

        mvc.perform(post("/api/guide-links")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WAF\",\"title\":\"Cert swap\",\"url\":\"https://example/waf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.category").value("WAF"));
    }

    @Test
    @DisplayName("POST /api/guide-links with blank required field returns 400")
    void create_blankUrl_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/guide-links")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"X\",\"title\":\"Y\",\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("PUT /api/guide-links/{id} as ADMIN updates the link")
    void update_asAdmin_returnsOk() throws Exception {
        GuideLink existing = link(7L, "WAF", "Old", "https://old");
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/guide-links/7")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WAF\",\"title\":\"New title\",\"url\":\"https://new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("New title"));
    }

    @Test
    @DisplayName("PUT /api/guide-links/{id} with unknown id returns 404")
    void update_unknownId_returns404() throws Exception {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(put("/api/guide-links/999")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"X\",\"title\":\"Y\",\"url\":\"https://z\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/guide-links/{id} as ADMIN deletes the link")
    void delete_asAdmin_returnsOk() throws Exception {
        when(repo.existsById(5L)).thenReturn(true);

        mvc.perform(delete("/api/guide-links/5").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Deleted"));
        verify(repo).deleteById(5L);
    }

    @Test
    @DisplayName("DELETE /api/guide-links/{id} as USER returns 403")
    void delete_asUser_returnsForbidden() throws Exception {
        mvc.perform(delete("/api/guide-links/1").session(userSession()))
                .andExpect(status().isForbidden());
        verify(repo, never()).deleteById(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin1");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user1");
        s.setAttribute("systemRole", "USER");
        return s;
    }

    private GuideLink link(Long id, String cat, String title, String url) {
        GuideLink g = new GuideLink();
        g.setId(id);
        g.setCategory(cat);
        g.setTitle(title);
        g.setUrl(url);
        g.setSortOrder(0);
        return g;
    }
}
