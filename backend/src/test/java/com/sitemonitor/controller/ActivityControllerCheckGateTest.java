package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.activity.ActivityExportService;
import com.sitemonitor.service.activity.ActivityQueryService;
import com.sitemonitor.service.activity.ActivityRelatedService;
import com.sitemonitor.service.activity.ActivityTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Hata avı D1 / D4 (2026-11-05): kontrol ayrıntısı kapısı listede ve kök neden öğelerinde de — {@code monitoring.read/view}
 * yoksa satırın {@code failure_detail}'i (maskeli kontrol JSON'u: çözülen IP'ler, vekil, gövde doğrulama iletisi) null;
 * neden kodu kalır (ayrıntı ucunun {@code check_hidden} kuralıyla AYNI yardımcı). Kök neden ucunun 404'ü kendi koduyla
 * ({@code ACTIVITY_NOT_FOUND}) — eski pod'un eşlemesiz ucundan ({@code NOT_FOUND}) ayırt edilir.
 */
@WebMvcTest(ActivityController.class)
class ActivityControllerCheckGateTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean ActivityLogRepository repo;
    @MockitoBean ActivityQueryService queries;
    @MockitoBean ActivityTimelineService timelines;
    @MockitoBean ActivityExportService exports;
    @MockitoBean ActivityRelatedService relatedSvc;
    @MockitoBean com.sitemonitor.repository.TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(1L)));
        return s;
    }

    private static ActivityLog row() {
        ActivityLog a = new ActivityLog();
        a.setId(9L);
        a.setTeamId(1L);
        a.setMonitorType("HTTP");
        a.setMonitorId(42L);
        a.setAction("SCHEDULED_CHECK");
        a.setResultStatus("WARNING");
        a.setActivityTime("2026-11-05T10:00:00");
        a.setFailureReason("BODY_ASSERTION");
        a.setFailureDetail("{\"kind\":\"BODY_ASSERTION\",\"resolved\":[\"10.0.0.9\"]}");
        return a;
    }

    private void readable(boolean yes) {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.read"), eq("view"))).thenReturn(yes);
    }

    @Test
    @DisplayName("liste: monitoring.read yoksa failure_detail null (neden kodu kalır); izinliyse ayrıntı nesnesi")
    void listHidesFailureDetailWithoutPermission() throws Exception {
        when(queries.list(any(), anyInt(), anyInt())).thenReturn(new ActivityQueryService.PageResult(List.of(row()), 1));
        readable(false);
        mvc.perform(get("/api/activity").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].failure_reason").value("BODY_ASSERTION"))
                .andExpect(jsonPath("$.data[0].failure_detail").value(nullValue()));
        readable(true);
        mvc.perform(get("/api/activity").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].failure_detail.kind").value("BODY_ASSERTION"));
    }

    @Test
    @DisplayName("kök neden öğeleri: monitoring.read yoksa failure_detail null")
    void relatedItemsHideFailureDetailWithoutPermission() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", 1);
        when(relatedSvc.related(eq(9L), any(), anyBoolean(), any(), any()))
                .thenReturn(new ActivityRelatedService.Result(body, List.of(row())));
        readable(false);
        mvc.perform(get("/api/activity/9/related").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].failure_reason").value("BODY_ASSERTION"))
                .andExpect(jsonPath("$.data.items[0].failure_detail").value(nullValue()));
        readable(true);
        mvc.perform(get("/api/activity/9/related").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].failure_detail.kind").value("BODY_ASSERTION"));
    }

    @Test
    @DisplayName("kök neden 404: kendi kodu ACTIVITY_NOT_FOUND (eski pod'un NOT_FOUND'u değil)")
    void relatedNotFoundHasOwnCode() throws Exception {
        when(relatedSvc.related(eq(5L), any(), anyBoolean(), any(), any())).thenThrow(new GlobalExceptionHandler.CodedNotFoundException(
                ActivityRelatedService.NOT_FOUND_CODE, "Aktivite bulunamadı ya da görme yetkiniz yok."));
        mvc.perform(get("/api/activity/5/related").session(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Aktivite bulunamadı ya da görme yetkiniz yok."));
    }
}
