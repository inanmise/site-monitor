package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserBulkDeactivationService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Toplu pasife alma uçlarının kapısı (2026-10-02): YALNIZ global yönetici. Kapsamlı müdür (ADMIN + dolu viewTeamIds),
 * TEAM_ADMIN, USER ve AUDIT 403; liste değişince 409 + {@code code=BULK_LIST_CHANGED}; ikinci geri alma 409.
 */
@WebMvcTest(UserBulkDeactivationController.class)
class UserBulkDeactivationControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserBulkDeactivationService service;
    @MockitoBean PermissionService permissionService;

    // WebConfig / AuthInterceptor / HttpMetricsInterceptor bağımlılıkları.
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuditService auditService;   // AuthInterceptor pasif kapısı denetimi + 403 güvenlik olayı

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "yonetici");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    private static MockHttpSession globalAdmin() { return session("ADMIN", null); }

    private static final String PREVIEW = "/api/admin/users/bulk-deactivate/preview";
    private static final String APPLY = "/api/admin/users/bulk-deactivate";
    private static final String UNDO = "/api/admin/users/bulk-deactivate/7/undo";
    private static final String HISTORY = "/api/admin/users/bulk-operations";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("Global olmayan HER rol (kapsamlı müdür, TEAM_ADMIN, USER, AUDIT) dört uçta da 403 — servis hiç çağrılmaz")
    void nonGlobal_forbiddenEverywhere() throws Exception {
        List<MockHttpSession> sessions = List.of(
                session("ADMIN", List.of(5L)), session("TEAM_ADMIN", List.of(5L)),
                session("USER", List.of(5L)), session("AUDIT", null));
        for (MockHttpSession s : sessions) {
            mvc.perform(json(post(PREVIEW), "{\"criteria\":{}}").session(s)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(containsString("global")));
            mvc.perform(json(post(APPLY), "{\"criteria\":{},\"expected_count\":1}").session(s)).andExpect(status().isForbidden());
            mvc.perform(post(UNDO).session(s)).andExpect(status().isForbidden());
            mvc.perform(get(HISTORY).session(s)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("kimliksiz istek → 401")
    void unauthenticated_401() throws Exception {
        mvc.perform(json(post(PREVIEW), "{}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Global yönetici önizleme: ölçüt normalize edilip servise gider, yanıt data altında")
    void preview_ok() throws Exception {
        when(service.preview(any(), eq(1L))).thenReturn(Map.of("total", 3, "excluded", Map.of("admins", 2)));

        mvc.perform(json(post(PREVIEW), "{\"criteria\":{\"scope\":\"teams\",\"team_ids\":[5,6],\"inactive_days\":90,\"auth_source\":\"ldap\"}}")
                        .session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.excluded.admins").value(2));

        ArgumentCaptor<UserBulkDeactivationService.Criteria> c = ArgumentCaptor.forClass(UserBulkDeactivationService.Criteria.class);
        verify(service).preview(c.capture(), eq(1L));
        assertThat(c.getValue().teamIds()).containsExactly(5L, 6L);
        assertThat(c.getValue().inactiveDays()).isEqualTo(90);
        assertThat(c.getValue().authSource()).isEqualTo("LDAP");
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("users.crud"), eq("edit"));
    }

    @Test
    @DisplayName("Ölçütte ADMIN rolü → 400 (servis çağrılmaz)")
    void preview_adminRole_400() throws Exception {
        mvc.perform(json(post(PREVIEW), "{\"criteria\":{\"system_role\":\"ADMIN\"}}").session(globalAdmin()))
                .andExpect(status().isBadRequest());
        verify(service, never()).preview(any(), any());
    }

    @Test
    @DisplayName("Uygula: expected_count + not servise gider")
    void apply_ok() throws Exception {
        when(service.apply(any(), eq(12), eq("temizlik"), any())).thenReturn(Map.of("operation_id", 9L, "ok", 12, "failed", 0));

        mvc.perform(json(post(APPLY), "{\"criteria\":{},\"expected_count\":12,\"note\":\"temizlik\"}").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation_id").value(9))
                .andExpect(jsonPath("$.data.ok").value(12));
    }

    @Test
    @DisplayName("Uygula: ölçüt gövdesi yoksa 400 — eksik gövde 'herkes' varsayılanına düşmez")
    void apply_missingCriteria_400() throws Exception {
        mvc.perform(json(post(APPLY), "{\"expected_count\":12}").session(globalAdmin()))
                .andExpect(status().isBadRequest());
        verify(service, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Liste değişti → 409 + code=BULK_LIST_CHANGED + current_count")
    void apply_listChanged_409() throws Exception {
        when(service.apply(any(), any(), any(), any())).thenThrow(new UserBulkDeactivationService.ListChangedException(
                "Liste değişti, önizlemeyi yenileyin", 11));

        mvc.perform(json(post(APPLY), "{\"criteria\":{},\"expected_count\":12}").session(globalAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(UserBulkDeactivationService.CODE_LIST_CHANGED))
                .andExpect(jsonPath("$.current_count").value(11))
                .andExpect(jsonPath("$.error").value(containsString("önizlemeyi yenileyin")));
    }

    @Test
    @DisplayName("Geri al: ikinci kez 409; ilk çağrı 200")
    void undo_conflict() throws Exception {
        when(service.undo(eq(7L), any())).thenReturn(Map.of("ok", 3)).thenThrow(new IllegalStateException("Bu işlem zaten geri alındı."));

        mvc.perform(post(UNDO).session(globalAdmin())).andExpect(status().isOk()).andExpect(jsonPath("$.data.ok").value(3));
        mvc.perform(post(UNDO).session(globalAdmin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("zaten geri alındı")));
    }

    @Test
    @DisplayName("Geçmiş: son işlemler data.operations altında")
    void history_ok() throws Exception {
        when(service.history()).thenReturn(List.of(Map.of("id", 2L, "can_undo", true)));

        mvc.perform(get(HISTORY).session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].id").value(2))
                .andExpect(jsonPath("$.data.operations[0].can_undo").value(true));
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("users.list"), eq("view"));
        verify(service, never()).undo(anyLong(), isNull());
    }
}
