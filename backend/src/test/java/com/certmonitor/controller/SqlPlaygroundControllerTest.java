package com.certmonitor.controller;

import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.SqlPlaygroundService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SqlPlaygroundController.class)
class SqlPlaygroundControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SqlPlaygroundService service;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.PermissionService permissionService;

    @Test
    @DisplayName("GET /tables without auth returns 401")
    void tables_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/sql/tables"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /tables as non-admin returns 403")
    void tables_nonAdmin_returns403() throws Exception {
        mvc.perform(get("/api/admin/sql/tables").session(auditSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /tables as admin returns 200 + data")
    void tables_admin_returns200() throws Exception {
        when(service.listTables()).thenReturn(List.of(Map.of("table_name", "teams")));

        mvc.perform(get("/api/admin/sql/tables").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].table_name").value("teams"));
    }

    @Test
    @DisplayName("GET /tables/{name}/columns returns column list")
    void columns_admin_returns200() throws Exception {
        when(service.listColumns("teams"))
                .thenReturn(List.of(Map.of("column_name", "id", "data_type", "bigint")));

        mvc.perform(get("/api/admin/sql/tables/teams/columns").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].column_name").value("id"));
    }

    @Test
    @DisplayName("POST /execute as non-admin returns 403")
    void execute_nonAdmin_returns403() throws Exception {
        mvc.perform(post("/api/admin/sql/execute")
                        .session(auditSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /execute as admin returns 200 + result")
    void execute_admin_returns200() throws Exception {
        when(service.execute(eq("SELECT 1"), anyString())).thenReturn(Map.of(
                "ok", true,
                "rows", List.of(Map.of("?column?", 1)),
                "rowCount", 1,
                "durationMs", 5L,
                "executedSql", "SELECT 1 LIMIT 1000"));

        mvc.perform(post("/api/admin/sql/execute")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rowCount").value(1));
    }

    @Test
    @DisplayName("GET /samples returns curated list")
    void samples_admin_returnsList() throws Exception {
        mvc.perform(get("/api/admin/sql/samples").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    private MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "n64954");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private MockHttpSession auditSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "auditor");
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }
}
