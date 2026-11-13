package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.SqlSavedQuery;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SqlPlaygroundService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.sqlplayground.SensitiveColumnException;
import com.sitemonitor.service.sqlplayground.SqlRunRegistry;
import com.sitemonitor.service.sqlplayground.SqlSavedQueryService;
import com.sitemonitor.service.sqlplayground.SqlSensitiveGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SqlPlaygroundController.class)
class SqlPlaygroundControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SqlPlaygroundService service;
    @MockitoBean SqlSavedQueryService savedQueries;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;

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
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST /execute as admin returns 200 + result (eski {sql} gövdesi aynen) + denetim")
    void execute_admin_returns200() throws Exception {
        when(service.execute(any(SqlPlaygroundService.QueryRequest.class), anyString())).thenReturn(Map.of(
                "ok", true,
                "rows", List.of(Map.of("?column?", 1)),
                "rowCount", 1,
                "durationMs", 5L,
                "executedSql", "SELECT 1 LIMIT 1000",
                "run_id", "run-abcdef01",
                "masked_columns", List.of("password_hash"),
                "cancelled", false));

        mvc.perform(post("/api/admin/sql/execute")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.masked_columns[0]").value("password_hash"));
        ArgumentCaptor<SqlPlaygroundService.QueryRequest> req = ArgumentCaptor.forClass(SqlPlaygroundService.QueryRequest.class);
        verify(service).execute(req.capture(), eq("n34567"));
        assertThat(req.getValue().sql()).isEqualTo("SELECT 1");
        assertThat(req.getValue().params()).isEmpty();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("SQL_EXECUTE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("QUERY"), anyString(), detail.capture());
        assertThat(detail.getValue()).contains("\"masked_columns\":1").contains("run-abcdef01");
    }

    @Test
    @DisplayName("POST /execute parametre + run_id taşır; parametre DEĞERİ denetime yazılmaz")
    void execute_paramsAndRunId() throws Exception {
        when(service.execute(any(SqlPlaygroundService.QueryRequest.class), anyString()))
                .thenReturn(Map.of("ok", true, "rows", List.of(), "rowCount", 0, "durationMs", 1L, "executedSql", "x",
                        "masked_columns", List.of(), "cancelled", false, "run_id", "run-12345678"));
        mvc.perform(post("/api/admin/sql/execute").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT :team\",\"run_id\":\"run-12345678\","
                                + "\"params\":{\"team\":{\"value\":\"gizli-deger-xyz\",\"type\":\"text\"}}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<SqlPlaygroundService.QueryRequest> req = ArgumentCaptor.forClass(SqlPlaygroundService.QueryRequest.class);
        verify(service).execute(req.capture(), anyString());
        assertThat(req.getValue().runId()).isEqualTo("run-12345678");
        assertThat(req.getValue().params().get("team").value()).isEqualTo("gizli-deger-xyz");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("SQL_EXECUTE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), any(), detail.capture());
        assertThat(detail.getValue()).doesNotContain("gizli-deger-xyz").contains("\"params\":1");
    }

    @Test
    @DisplayName("POST /execute hassas kolon reddi → 400 SQL_SENSITIVE_COLUMN + başarısız denetim (sebep, kolon)")
    void execute_sensitive_400() throws Exception {
        when(service.execute(any(SqlPlaygroundService.QueryRequest.class), anyString())).thenThrow(new SensitiveColumnException(
                new SqlSensitiveGuard.Verdict(false, true, "password_hash", SqlSensitiveGuard.R_EXPRESSION)));
        mvc.perform(post("/api/admin/sql/execute").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1 FROM app_users WHERE password_hash = 'x'\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SQL_SENSITIVE_COLUMN"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("password_hash")));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("SQL_EXECUTE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("QUERY"), anyString(), detail.capture());
        assertThat(detail.getValue()).contains("\"ok\":false").contains("expression").contains("password_hash");
    }

    @Test
    @DisplayName("POST /execute parametre biçimi bozuk → 400 VALIDATION_FAILED; sql metin değilse 400")
    void execute_badBody_400() throws Exception {
        mvc.perform(post("/api/admin/sql/execute").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/admin/sql/execute").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT :a\",\"params\":[1,2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SQL_PARAM_INVALID"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST /cancel → servis sonucu + SQL_CANCEL denetimi; non-admin 403")
    void cancel() throws Exception {
        when(service.cancel("run-12345678", "n34567")).thenReturn(new SqlRunRegistry.CancelResult(false, "finished"));
        mvc.perform(post("/api/admin/sql/cancel").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"run-12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(false))
                .andExpect(jsonPath("$.reason").value("finished"));
        verify(auditService).recordAction(eq("SQL_CANCEL"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("QUERY"), eq("run-12345678"), anyString());
        mvc.perform(post("/api/admin/sql/cancel").session(auditSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"run-12345678\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /explain → plan + SQL_EXPLAIN denetimi")
    void explain() throws Exception {
        when(service.explain(any(), eq(true), eq("n34567"))).thenReturn(Map.of("ok", true, "analyzed", true,
                "plan", List.of(), "summary", Map.of("total_cost", 1.5), "run_id", "r-1", "cancelled", false));
        mvc.perform(post("/api/admin/sql/explain").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\",\"analyze\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total_cost").value(1.5));
        verify(auditService).recordAction(eq("SQL_EXPLAIN"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("QUERY"), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /export CSV: satırlar dosyaya akar, başlıklar dolar, maskeli kolon başlıkta, SQL_EXPORT denetimi")
    void export_csv() throws Exception {
        doAnswer(inv -> {
            SqlPlaygroundService.RowSink sink = inv.getArgument(3);
            sink.columns(new String[]{"name", "şifre_enc"});
            sink.row(new Object[]{"alpha", "••••"});
            sink.row(new Object[]{"=cmd", "••••"});
            return new SqlPlaygroundService.RunOutcome(true, false, null, new String[]{"name", "şifre_enc"},
                    List.of("şifre_enc"), 2, false, 7L, "run-export-1");
        }).when(service).export(any(), eq(SqlPlaygroundService.EXPORT_CSV_MAX_ROWS), eq("n34567"), any());

        MvcResult r = mvc.perform(post("/api/admin/sql/export").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT name FROM teams\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Rows", "2"))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string("X-Export-Max-Rows", String.valueOf(SqlPlaygroundService.EXPORT_CSV_MAX_ROWS)))
                .andExpect(header().string("X-Masked-Columns", "%C5%9Fifre_enc"))
                .andExpect(header().string("X-Run-Id", "run-export-1"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        String body = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").contains("alpha").contains("'=cmd");
        verify(auditService).recordAction(eq("SQL_EXPORT"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("QUERY"), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /export JSON: data.columns + rows dizileri")
    void export_json() throws Exception {
        doAnswer(inv -> {
            SqlPlaygroundService.RowSink sink = inv.getArgument(3);
            sink.columns(new String[]{"id"});
            sink.row(new Object[]{1L});
            return new SqlPlaygroundService.RunOutcome(true, false, null, new String[]{"id"}, List.of(), 1, true, 7L, "run-j");
        }).when(service).export(any(), eq(SqlPlaygroundService.EXPORT_JSON_MAX_ROWS), anyString(), any());
        mvc.perform(post("/api/admin/sql/export").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT id FROM teams\",\"format\":\"json\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true"))
                .andExpect(header().string("X-Export-Truncated-Reason", "rows"))
                .andExpect(jsonPath("$.data.columns[0]").value("id"))
                .andExpect(jsonPath("$.data.rows[0][0]").value(1))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    @DisplayName("POST /export: biçim hatası 400 (denetim yok), sorgu hatası 400 SQL_EXPORT_FAILED, durdurma 409 SQL_CANCELLED")
    void export_errors() throws Exception {
        mvc.perform(post("/api/admin/sql/export").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\",\"format\":\"xlsx\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("format"));
        verify(auditService, never()).recordAction(eq("SQL_EXPORT"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());

        when(service.export(any(), anyInt(), anyString(), any())).thenReturn(
                new SqlPlaygroundService.RunOutcome(false, false, "boom", new String[0], List.of(), 0, false, 1L, "r-1"));
        mvc.perform(post("/api/admin/sql/export").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SQL_EXPORT_FAILED"));

        when(service.export(any(), anyInt(), anyString(), any())).thenReturn(
                new SqlPlaygroundService.RunOutcome(false, true, "stopped", new String[0], List.of(), 0, false, 1L, "r-2"));
        mvc.perform(post("/api/admin/sql/export").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SQL_CANCELLED"))
                .andExpect(jsonPath("$.cancelled").value(true));
    }

    @Test
    @DisplayName("POST /export: kişi başına dakikada 6 → 7. istek 429 RATE_LIMITED + Retry-After")
    void export_rateLimited() throws Exception {
        when(service.export(any(), anyInt(), anyString(), any())).thenReturn(
                new SqlPlaygroundService.RunOutcome(true, false, null, new String[0], List.of(), 0, false, 1L, "r"));
        MockHttpSession s = adminSession();
        s.setAttribute("username", "rate-user");
        for (int i = 0; i < SqlPlaygroundController.EXPORT_PER_MINUTE; i++) {
            mvc.perform(post("/api/admin/sql/export").session(s).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":\"SELECT 1\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/admin/sql/export").session(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("X-Masked-Columns: ASCII dışı ve ayırıcı yüzde kodlu")
    void headerList() {
        assertThat(SqlPlaygroundController.headerList(List.of("a_b", "x,y", "ş"))).isEqualTo("a_b,x%2Cy,%C5%9F");
        assertThat(SqlPlaygroundController.headerList(List.of())).isEmpty();
    }

    @Test
    @DisplayName("GET /history: parametresiz eski yanıt; parametre varsa sayfalı mod")
    void history() throws Exception {
        when(service.recentHistory("n34567")).thenReturn(List.of());
        mvc.perform(get("/api/admin/sql/history").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(service, never()).historyPage(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());

        when(service.historyPage("n34567", "teams", "error", "all", null, null, 2, 50))
                .thenReturn(Map.of("data", List.of(), "total", 0L, "page", 2, "size", 50, "slow_ms", 5000L));
        mvc.perform(get("/api/admin/sql/history").param("q", "teams").param("status", "error").param("scope", "all")
                        .param("page", "2").param("size", "50").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.slow_ms").value(5000));
        when(service.historyPage(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new FieldValidationException("status", "bad"));
        mvc.perform(get("/api/admin/sql/history").param("status", "weird").session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("status"));
    }

    @Test
    @DisplayName("GET /samples: kategori + açıklamalı ≥ 30 örnek")
    void samples_admin_returnsList() throws Exception {
        mvc.perform(get("/api/admin/sql/samples").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data[0].category").isNotEmpty())
                .andExpect(jsonPath("$.data[0].description").isNotEmpty())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(30)));
    }

    @Test
    @DisplayName("GET /dictionary: tablolar ve kolon açıklamaları")
    void dictionary() throws Exception {
        mvc.perform(get("/api/admin/sql/dictionary").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.alert_events.description").isNotEmpty())
                .andExpect(jsonPath("$.tables.app_users.columns.password_hash").value(org.hamcrest.Matchers.containsString("maskelenir")));
    }

    @Test
    @DisplayName("Kayıtlı sorgu: oluşturma 200 + denetim; görünmeyen 404; başkasınınkini düzenleme 403; değişiklik yoksa denetim yok")
    void saved() throws Exception {
        SqlSavedQuery e = new SqlSavedQuery();
        e.setId(7L);
        e.setTitle("Açık alarmlar");
        e.setShared(true);
        e.setSqlText("SELECT 1");
        when(savedQueries.create(any(), eq("n34567"), any())).thenReturn(new SqlSavedQueryService.Saved(e, true, List.of()));
        when(savedQueries.row(any(), anyString())).thenReturn(Map.of("id", 7, "title", "Açık alarmlar"));
        mvc.perform(post("/api/admin/sql/saved").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Açık alarmlar\",\"sql_text\":\"SELECT 1\",\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7));
        verify(auditService).recordAction(eq("SQL_SAVED_QUERY_CREATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("SQL_SAVED_QUERY"), eq("7"), anyString());

        when(savedQueries.get(eq(9L), anyString())).thenThrow(new NoSuchElementException("yok"));
        mvc.perform(get("/api/admin/sql/saved/9").session(adminSession())).andExpect(status().isNotFound());

        when(savedQueries.update(eq(8L), any(), anyString())).thenThrow(new SecurityException("not owner"));
        mvc.perform(put("/api/admin/sql/saved/8").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"version\":0}"))
                .andExpect(status().isForbidden());

        when(savedQueries.update(eq(7L), any(), anyString())).thenReturn(new SqlSavedQueryService.Saved(e, false, List.of()));
        mvc.perform(put("/api/admin/sql/saved/7").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
        verify(auditService, never()).recordAction(eq("SQL_SAVED_QUERY_UPDATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());

        when(savedQueries.delete(7L, "n34567")).thenReturn(e);
        mvc.perform(delete("/api/admin/sql/saved/7").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(auditService).recordAction(eq("SQL_SAVED_QUERY_DELETE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("SQL_SAVED_QUERY"), eq("7"), anyString());

        mvc.perform(get("/api/admin/sql/saved").session(auditSession())).andExpect(status().isForbidden());
    }

    private MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "n34567");
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
