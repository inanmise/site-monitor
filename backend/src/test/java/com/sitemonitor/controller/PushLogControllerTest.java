package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.PushLogQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Webhook Push Gönderim Logu uçları (2026-09-19): açık uçlu pencere (to=null) 200; kapsam (takım + kendi adı); requeue admin + denetim; 404/409. */
@WebMvcTest(PushLogController.class)
class PushLogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PushLogQueryService pushLogQueryService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.RememberMeService rememberMeService;
    @MockitoBean com.sitemonitor.service.UserService userService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettingsService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ADMIN".equals(role) ? "admin" : "user1");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    @Test
    @DisplayName("search: to=null pencere 200; kapsam USER → takımlar + kendi kullanıcı adı, ADMIN → global")
    void searchAndScope() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("items", List.of(Map.of("id", 1))); data.put("total", 1); data.put("page", 0); data.put("size", 25); data.put("from", "2026-09-12T00:00:00"); data.put("to", null);
        when(pushLogQueryService.search(any(), any(), anyInt(), anyInt(), any())).thenReturn(data);
        ArgumentCaptor<PushLogQueryService.Scope> cap = ArgumentCaptor.forClass(PushLogQueryService.Scope.class);

        mvc.perform(get("/api/admin/push-log/search").session(session("USER", List.of(3L)))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/push-log/search").session(session("ADMIN", null))).andExpect(status().isOk());
        verify(pushLogQueryService, org.mockito.Mockito.times(2)).search(any(), cap.capture(), anyInt(), anyInt(), any());
        assertThat(cap.getAllValues().get(0).global()).isFalse();
        assertThat(cap.getAllValues().get(0).teamIds()).containsExactly(3L);
        assertThat(cap.getAllValues().get(0).username()).isEqualTo("user1");
        assertThat(cap.getAllValues().get(1).global()).isTrue();
        verify(permissionService, org.mockito.Mockito.times(2)).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    @Test
    @DisplayName("requeue: USER 403; ADMIN → servis + USER_PUSH_REQUEUE denetimi; NOT_RETRYABLE 409; NOT_FOUND 404")
    void requeue() throws Exception {
        mvc.perform(post("/api/admin/push-log/5/requeue").session(session("USER", List.of(1L)))).andExpect(status().isForbidden());
        when(pushLogQueryService.requeue(eq(5L), any(), eq("admin"))).thenReturn(new PushLogQueryService.RequeueResult(true, null));
        mvc.perform(post("/api/admin/push-log/5/requeue").session(session("ADMIN", null))).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(true));
        verify(auditService).recordAction(eq("USER_PUSH_REQUEUE"), any(), eq("USER_PUSH"), eq("5"), any(), any());
        when(pushLogQueryService.requeue(eq(6L), any(), eq("admin"))).thenReturn(new PushLogQueryService.RequeueResult(false, "NOT_RETRYABLE"));
        mvc.perform(post("/api/admin/push-log/6/requeue").session(session("ADMIN", null))).andExpect(status().isConflict());
        when(pushLogQueryService.requeue(eq(7L), any(), eq("admin"))).thenReturn(new PushLogQueryService.RequeueResult(false, "NOT_FOUND"));
        mvc.perform(post("/api/admin/push-log/7/requeue").session(session("ADMIN", null))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("detail 404 kapsam dışı; export count/capped")
    void detailAndExport() throws Exception {
        when(pushLogQueryService.detail(eq(1L), any())).thenReturn(null);
        mvc.perform(get("/api/admin/push-log/1").session(session("ADMIN", null))).andExpect(status().isNotFound());
        when(pushLogQueryService.exportRows(any(), any(), anyInt(), any())).thenReturn(List.of(Map.of("id", 1)));
        mvc.perform(get("/api/admin/push-log/export").session(session("ADMIN", null))).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    }
}
