package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.*;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Saklama ayarları kaydetmenin DENETİM izini ve değişiklik geçmişi ucunun sözleşmesi.
 * "Kim ne zaman neyi değiştirdi" sorusunun cevabı buradan üretiliyor.
 */
@WebMvcTest(RetentionAdminController.class)
class RetentionAdminControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RetentionService retentionService;
    @MockitoBean RetentionRunRepository runRepo;
    @MockitoBean RetentionRunItemRepository itemRepo;
    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean AuditService auditService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    private static final String ACTIVITY_KEY = "site.monitor.activity.retention-days";

    @BeforeEach
    void setUp() {
        // activity-log politikası: mevcut etkin değer 90 gün
        when(retentionService.effectiveDays(any())).thenAnswer(inv -> 90);
        when(retentionService.overview(anyBoolean())).thenReturn(List.of());
        when(retentionService.holdActive()).thenReturn(false);
    }

    private MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "ADMIN");
        s.setAttribute("bootstrapAdmin", Boolean.TRUE);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("Kaydetme: değişen HER politika için ayrı RETENTION_POLICY_CHANGE + eski→yeni diff")
    void savingWritesPerPolicyAuditWithDiff() throws Exception {
        mvc.perform(put("/api/admin/retention/settings").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"" + ACTIVITY_KEY + "\":\"365\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));

        verify(auditService).recordAction(eq("RETENTION_POLICY_CHANGE"), any(), any(),
                eq("RETENTION_POLICY"), eq("activity-log"),
                contains("90g → 365g"),
                argThat(ch -> ch != null && ch.contains("\"from\":90") && ch.contains("\"to\":365")));
    }

    @Test
    @DisplayName("Değişmeyen değer denetim kaydı ÜRETMEZ (gürültü yok)")
    void unchangedValueWritesNoPolicyAudit() throws Exception {
        mvc.perform(put("/api/admin/retention/settings").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"" + ACTIVITY_KEY + "\":\"90\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(0));

        // 7-argümanlı overload (session + request + changes) — imza açıkça belirtilir, aksi halde
        // 6-argümanlı kardeşiyle karışır.
        verify(auditService, never()).recordAction(eq("RETENTION_POLICY_CHANGE"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Boş değer = override kaldırma da GERÇEK değişikliktir (eskiden sessizce atlanıyordu)")
    void clearingOverrideCountsAsChange() throws Exception {
        // activity-log varsayılanı 365; mevcut etkin değer 90 → temizlemek 90 → 365 demektir
        mvc.perform(put("/api/admin/retention/settings").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"" + ACTIVITY_KEY + "\":\"\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));

        verify(auditService).recordAction(eq("RETENTION_POLICY_CHANGE"), any(), any(),
                eq("RETENTION_POLICY"), eq("activity-log"), anyString(),
                argThat(ch -> ch != null && ch.contains("\"to\":365")));
    }

    @Test
    @DisplayName("Kısaltma ayrıca RETENTION_SETTINGS_SHORTENED olayı yazar")
    void shorteningWritesSeparateEvent() throws Exception {
        mvc.perform(put("/api/admin/retention/settings").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"" + ACTIVITY_KEY + "\":\"30\"}}"))
                .andExpect(status().isOk());

        verify(auditService).recordAction(eq("RETENTION_SETTINGS_SHORTENED"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("RETENTION"), eq("settings"), contains("90→30"));
    }

    @Test
    @DisplayName("Taban sınırının altındaki değer 400 döner ve HİÇBİR şey kaydedilmez")
    void belowFloorIsRejected() throws Exception {
        String pingKey = RetentionCatalog.byId("series-ping").orElseThrow().settingKey();
        mvc.perform(put("/api/admin/retention/settings").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"" + pingKey + "\":\"3\"}}"))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("GET /changes: audit_log'dan eski→yeni çıkarılır (kendi izniyle, denetim iznine gerek yok)")
    void changesEndpointParsesDiff() throws Exception {
        AuditLog row = new AuditLog();
        row.setResourceId("activity-log");
        row.setActor("ADMIN");
        row.setEventTime("2026-08-08T19:00:00");
        row.setIpAddress("10.0.0.1");
        row.setChanges("{\"days\":{\"from\":90,\"to\":365}}");
        when(auditLogRepo.findByResourceTypeOrderByEventTimeDesc(eq("RETENTION_POLICY"), any()))
                .thenReturn(List.of(row));

        mvc.perform(get("/api/admin/retention/changes").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].policy_id").value("activity-log"))
                .andExpect(jsonPath("$.data[0].table").value("activity_log"))
                .andExpect(jsonPath("$.data[0].actor").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].from").value(90))
                .andExpect(jsonPath("$.data[0].to").value(365));

        // Denetim ucunun izni (audit_log.read) İSTENMEZ — retention admini 403 almamalı.
        verify(permissionService, never()).require(
                any(jakarta.servlet.http.HttpSession.class), eq("audit_log.read"), anyString());
    }

    @Test
    @DisplayName("Diff ayrıştırıcı bozuk/eksik payload'da null döner (ekran yine çalışır)")
    void diffParserTolerant() {
        org.assertj.core.api.Assertions.assertThat(
                RetentionAdminController.parseDaysDiff("{\"days\":{\"from\":90,\"to\":365}}"))
                .containsExactly(90, 365);
        org.assertj.core.api.Assertions.assertThat(RetentionAdminController.parseDaysDiff(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(RetentionAdminController.parseDaysDiff("{}")).isNull();
        org.assertj.core.api.Assertions.assertThat(
                RetentionAdminController.parseDaysDiff("{\"approval\":{\"from\":\"\",\"to\":\"x\"}}")).isNull();
    }
}
