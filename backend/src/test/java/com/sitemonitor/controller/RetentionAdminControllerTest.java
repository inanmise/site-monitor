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
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;
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

    // ── YIKICI UÇLAR ─────────────────────────────────────────────────────────────
    // Bu iki uç kalıcı satır siler; buraya kadar HİÇ testleri yoktu. Legal-hold reddi bozulursa
    // yasal saklama açıkken veri silinir (geri alınamaz + uyum ihlali); dry-run'ın parametresi
    // yanlışlıkla false'a dönerse "hiçbir şey silinmez" denilen düğme GERÇEKTEN siler.

    private static RetentionService.RunResult result(boolean dryRun, long rows) {
        return new RetentionService.RunResult(1L, "2026-08-15T03:30:00", "2026-08-15T03:30:05",
                dryRun, false, rows, 0, 5000L, List.of());
    }

    @Test
    @DisplayName("YASAL SAKLAMA açıkken /run reddedilir ve execute HİÇ çağrılmaz")
    void runNow_legalHoldActive_rejected_andNoExecute() throws Exception {
        when(retentionService.holdActive()).thenReturn(true);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/retention/run").session(admin()))
                .andExpect(status().is4xxClientError());

        verify(retentionService, never()).execute(anyBoolean(), anyString());
    }

    @Test
    @DisplayName("/dry-run GERÇEKTEN dry: execute(true, ...) ile çağrılır (silme yapmaz)")
    void dryRun_callsExecuteWithDryRunTrue() throws Exception {
        when(retentionService.execute(anyBoolean(), anyString())).thenReturn(result(true, 1234));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/retention/dry-run").session(admin()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Boolean> cap = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        verify(retentionService).execute(cap.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(cap.getValue(), "dry-run parametresi TRUE olmalı");
    }

    @Test
    @DisplayName("/run legal-hold kapalıyken execute(false, ...) ile siler ve denetim kaydı yazar")
    void runNow_holdInactive_executesAndAudits() throws Exception {
        when(retentionService.holdActive()).thenReturn(false);
        when(retentionService.execute(anyBoolean(), anyString())).thenReturn(result(false, 4200));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/retention/run").session(admin()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Boolean> cap = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        verify(retentionService).execute(cap.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertFalse(cap.getValue(), "gerçek koşumda dry-run FALSE olmalı");
        verify(auditService).recordAction(eq("RETENTION_RUN_MANUAL"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), anyString(), contains("4200"));
    }

    @Test
    @DisplayName("backfill-hourly days=0 → varsayılan pencereyle çalışır (0 gün ile çağrılmaz)")
    void backfillHourly_daysZero_usesDefaultWindow() throws Exception {
        when(schedulerService.backfillHourlyRollup(anyInt())).thenReturn(42);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/retention/backfill-hourly").session(admin()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Integer> cap = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(schedulerService).backfillHourlyRollup(cap.capture());
        org.junit.jupiter.api.Assertions.assertTrue(cap.getValue() > 0, "gün sayısı pozitif olmalı");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("PUT /approval: politika id'siyle onay katalog önekli anahtara yazılır ve denetlenir")
    void saveApproval_persistsApprovalKey() throws Exception {
        mvc.perform(put("/api/admin/retention/approval").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policy_id\":\"user-push-deliveries\",\"note\":\"uygundur\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Onay kaydedildi"));
        org.mockito.Mockito.verify(settingsService).save(org.mockito.ArgumentMatchers.argThat(m -> {
            Object values = m.get("values");
            return values instanceof java.util.Map<?, ?> v
                    && v.containsKey(com.sitemonitor.service.AppSettingsCatalog.RETENTION_APPROVAL_PREFIX + "user-push-deliveries")
                    && String.valueOf(v.values().iterator().next()).endsWith("|uygundur");
        }), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(auditService).recordAction(org.mockito.ArgumentMatchers.eq("RETENTION_APPROVAL_SAVE"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("RETENTION_POLICY"),
                org.mockito.ArgumentMatchers.eq("user-push-deliveries"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── 2026-09-10: Son çalışmalar — sunucu-taraflı sayfalama/süzgeç/sıralama + CSV ─────────

    private static com.sitemonitor.model.RetentionRun run(long id, boolean dry, boolean hold, long deleted, int failed) {
        com.sitemonitor.model.RetentionRun r = new com.sitemonitor.model.RetentionRun();
        r.setId(id); r.setStartedAt("2026-09-0" + id + "T03:00:00"); r.setFinishedAt("2026-09-0" + id + "T03:00:05");
        r.setDryRun(dry); r.setHoldActive(hold); r.setTotalDeleted(deleted); r.setFailedCount(failed);
        r.setDurationMs(5000L); r.setTriggeredBy("scheduler"); r.setInstanceId("pod-a");
        return r;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("GET /runs: süzgeç/sıralama repo'ya beyaz-listeyle geçer, yanıt total/page/size taşır")
    void runs_filtersAndPagingForwarded() throws Exception {
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pg =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(runRepo.search(eq("dry"), eq(true), eq("2026-09-01T00:00:00"), eq("2026-09-30T23:59:59"), eq("adm"), eq("activity-log"), pg.capture()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(run(1, true, false, 0, 1)),
                        org.springframework.data.domain.PageRequest.of(1, 20), 41));
        when(itemRepo.findByRunIdInOrderByIdAsc(any())).thenReturn(java.util.List.of());

        mvc.perform(get("/api/admin/retention/runs?page=1&size=20&kind=dry&failed=true&policyId=activity-log&since=2026-09-01T00:00:00&until=2026-09-30T23:59:59&q=ADM&sort=total_deleted&dir=asc")
                        .session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.data[0].id").value(1));
        org.springframework.data.domain.Sort sort = pg.getValue().getSort();
        org.assertj.core.api.Assertions.assertThat(sort.getOrderFor("totalDeleted").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
        org.assertj.core.api.Assertions.assertThat(pg.getValue().getPageNumber()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pg.getValue().getPageSize()).isEqualTo(20);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("GET /runs: size 100'e kırpılır, bilinmeyen sort/kind varsayılana düşer, eski limit param'ı sayfa boyutu sayılır")
    void runs_clampsAndDefaults() throws Exception {
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pg =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(runRepo.search(eq("all"), eq(false), isNull(), isNull(), isNull(), isNull(), pg.capture()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        mvc.perform(get("/api/admin/retention/runs?size=999&kind=bogus&sort=hack&dir=sideways").session(admin()))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(pg.getValue().getPageSize()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(pg.getValue().getSort().getOrderFor("startedAt").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);

        mvc.perform(get("/api/admin/retention/runs?limit=10").session(admin())).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(pg.getValue().getPageSize()).isEqualTo(10);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("GET /runs/export: aynı süzgeçle CSV — başlık + koşum satırı + kalemler tek hücrede")
    void runsExport_csv() throws Exception {
        com.sitemonitor.model.RetentionRun r = run(3, false, false, 120, 0);
        when(runRepo.search(eq("real"), eq(false), isNull(), isNull(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(r)));
        com.sitemonitor.model.RetentionRunItem it = new com.sitemonitor.model.RetentionRunItem();
        it.setRunId(3L); it.setPolicyId("activity-log"); it.setTableName("activity_log"); it.setRowsDeleted(120);
        when(itemRepo.findByRunIdInOrderByIdAsc(any())).thenReturn(java.util.List.of(it));

        String csv = mvc.perform(get("/api/admin/retention/runs/export?kind=real").session(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("retention-runs.csv")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(csv).contains("id,started_at,finished_at,kind,total_deleted")
                .contains("3,2026-09-03T03:00:00,2026-09-03T03:00:05,real,120,0,5000,scheduler,pod-a,activity-log=120");
    }
}
