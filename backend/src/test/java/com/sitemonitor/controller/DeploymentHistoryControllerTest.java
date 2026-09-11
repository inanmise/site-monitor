package com.sitemonitor.controller;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Sürüm & Dağıtım geçmişi yönetim ucu: okuma {@code release_history.read}, yazma
 * {@code release_history.edit} + kapsamlı müdür (AD ADMIN) reddi; sayfalama/sıralama beyaz listesi;
 * CSV sözleşmesi (BOM + başlık); yalnız MANUAL satır silinir (409); her yazma/dışa aktarma denetlenir.
 */
@WebMvcTest(DeploymentHistoryController.class)
class DeploymentHistoryControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean DeploymentHistoryService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    // Web altyapısı (interceptor/filter bağımlılıkları)
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    private static DeploymentHistory row(long id, String version, String source) {
        DeploymentHistory d = new DeploymentHistory();
        d.setId(id); d.setStartedAt("2026-09-0" + id + "T10:00:00Z"); d.setRecordedAt(d.getStartedAt());
        d.setEnvironment("prod"); d.setVersion(version); d.setSource(source);
        d.setGitCommit("0123456789abcdef"); d.setPodName("pod-a"); d.setCreatedBy("SYSTEM");
        d.setNote("not, \"tırnaklı\"");
        return d;
    }

    /** Yerel bootstrap yönetici — izin matrisi sorulmaz. */
    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "ADMIN");
        s.setAttribute("bootstrapAdmin", Boolean.TRUE);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    /** AUDIT rolü — okuma izni matristen sorulur. */
    private static MockHttpSession auditor() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "denetci");
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }

    /** Kapsamlı müdür: rol ADMIN ama viewTeamIds dolu (AD'den gelen takım-kapsamlı yönetici). */
    private static MockHttpSession scopedAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "mudur");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(1L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(1L)));
        return s;
    }

    @BeforeEach
    void setUp() {
        when(service.search(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(service.kindsFor(any())).thenReturn(Map.of());
        when(service.environments()).thenReturn(List.of("prod"));
        when(service.currentEnvironment()).thenReturn("prod");
        when(service.toMap(any())).thenAnswer(inv -> {
            DeploymentHistoryService.Derived d = inv.getArgument(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.row().getId()); m.put("version", d.row().getVersion()); m.put("kind", d.kind().name());
            return m;
        });
    }

    // ── Okuma kapısı ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET liste: AUDIT rolü release_history.read/view izniyle geçer; yanıt sayfa alanlarını taşır")
    void list_readGate_allowed() throws Exception {
        DeploymentHistory r = row(1, "20.54.0", DeploymentHistory.SOURCE_STARTUP);
        when(service.search(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1));
        when(service.kindsFor(any())).thenReturn(Map.of(1L,
                new DeploymentHistoryService.Derived(r, DeploymentHistoryService.Kind.UPGRADE, "20.53.2")));

        mvc.perform(get("/api/admin/deployments").session(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_pages").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("UPGRADE"))
                .andExpect(jsonPath("$.environments[0]").value("prod"));
        verify(permissionService).require(any(HttpSession.class), eq("release_history.read"), eq("view"));
    }

    @Test
    @DisplayName("GET liste/timeline/matrix/export: okuma izni yoksa 403 ve servis çağrılmaz")
    void read_denied() throws Exception {
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(any(HttpSession.class), eq("release_history.read"), eq("view"));
        mvc.perform(get("/api/admin/deployments").session(auditor())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/deployments/timeline").session(auditor())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/deployments/matrix").session(auditor())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/deployments/export").session(auditor())).andExpect(status().isForbidden());
        verify(service, never()).search(any(), any(), any(), any(), any(), any(Pageable.class));
        verify(service, never()).timeline(any());
        verify(service, never()).matrix(anyBoolean());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("bootstrap admin okumada izin matrisini atlar")
    void list_bootstrapAdminSkipsMatrix() throws Exception {
        mvc.perform(get("/api/admin/deployments").session(admin())).andExpect(status().isOk());
        verify(permissionService, never()).require(any(HttpSession.class), anyString(), anyString());
    }

    @Test
    @DisplayName("GET timeline: ortam boşsa koşan ortam; summary + environments + backfillCandidates eklenir")
    void timeline_defaultsToCurrentEnv() throws Exception {
        when(service.timeline("prod")).thenReturn(Map.of("environment", "prod", "transitions", List.of()));
        when(service.summary("prod")).thenReturn(Map.of("rollbacks", 0));
        when(service.backfillPreview()).thenReturn(4);
        mvc.perform(get("/api/admin/deployments/timeline").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("prod"))
                .andExpect(jsonPath("$.data.summary.rollbacks").value(0))
                .andExpect(jsonPath("$.data.environments[0]").value("prod"))
                .andExpect(jsonPath("$.data.backfillCandidates").value(4));
        mvc.perform(get("/api/admin/deployments/timeline?env=staging").session(admin())).andExpect(status().isOk());
        verify(service).timeline("staging");
    }

    // ── Sayfalama / sıralama / kaynak normalizasyonu ──────────────────────────────

    @Test
    @DisplayName("GET liste: size 100'e kırpılır, page 0 → 1, bilinmeyen sort startedAt DESC'e düşer, source normalize")
    void list_clampsAndWhitelists() throws Exception {
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        mvc.perform(get("/api/admin/deployments?page=0&size=999&sort=hack&dir=sideways&source=bogus&env=prod")
                        .session(admin()))
                .andExpect(status().isOk());
        verify(service).search(eq("prod"), isNull(), isNull(), isNull(), isNull(), pg.capture());
        assertThat(pg.getValue().getPageNumber()).isZero();
        assertThat(pg.getValue().getPageSize()).isEqualTo(DeploymentHistoryService.MAX_PAGE);
        assertThat(pg.getValue().getSort().getOrderFor("startedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pg.getValue().getSort().getOrderFor("hack")).isNull();

        mvc.perform(get("/api/admin/deployments?page=3&size=10&sort=version&dir=asc&source=manual&since=2026-09-01T00:00:00Z&q=pod")
                        .session(admin()))
                .andExpect(status().isOk());
        verify(service).search(isNull(), eq("MANUAL"), eq("2026-09-01T00:00:00Z"), isNull(), eq("pod"), pg.capture());
        assertThat(pg.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pg.getValue().getPageSize()).isEqualTo(10);
        assertThat(pg.getValue().getSort().getOrderFor("version").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(pg.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("sortOf / normSource: beyaz liste — bilinmeyen alan startedAt, bilinmeyen kaynak süzgeçsiz")
    void staticHelpers() {
        assertThat(DeploymentHistoryController.sortOf("ENVIRONMENT", "ASC").getOrderFor("environment").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(DeploymentHistoryController.sortOf(null, null).getOrderFor("startedAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(DeploymentHistoryController.sortOf("id; DROP TABLE", "asc").getOrderFor("startedAt")).isNotNull();

        assertThat(DeploymentHistoryController.normSource(null)).isNull();
        assertThat(DeploymentHistoryController.normSource(" ")).isNull();
        assertThat(DeploymentHistoryController.normSource("ALL")).isNull();
        assertThat(DeploymentHistoryController.normSource("startup")).isEqualTo("STARTUP");
        assertThat(DeploymentHistoryController.normSource(" Backfill ")).isEqualTo("BACKFILL");
        assertThat(DeploymentHistoryController.normSource("manual")).isEqualTo("MANUAL");
        assertThat(DeploymentHistoryController.normSource("bogus")).isNull();
    }

    // ── CSV ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET export: text/csv, ilk karakter BOM, başlık satırı, tür sütunu ve tırnaklı hücre; EXPORT denetimi")
    void export_csvContract() throws Exception {
        DeploymentHistory r = row(3, "20.54.0", DeploymentHistory.SOURCE_STARTUP);
        when(service.search(any(), eq("STARTUP"), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(service.kindsFor(any())).thenReturn(Map.of(3L,
                new DeploymentHistoryService.Derived(r, DeploymentHistoryService.Kind.RESTART, "20.54.0")));

        String csv = mvc.perform(get("/api/admin/deployments/export?source=startup").session(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("deployment-history.csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.charAt(0)).isEqualTo('﻿');
        String[] lines = csv.substring(1).split("\r\n");
        assertThat(lines[0]).isEqualTo("id,started_at,ready_at,ended_at,end_reason,environment,version,kind,"
                + "previous_version,image_version,git_commit,image_ref,helm_release,helm_revision,"
                + "helm_chart_version,instance_id,pod_name,node_name,source,created_by,note");
        assertThat(lines[1]).startsWith("3,2026-09-03T10:00:00Z,,,,prod,20.54.0,RESTART,20.54.0,,0123456789abcdef,")
                .endsWith("STARTUP,SYSTEM,\"not, \"\"tırnaklı\"\"\"");
        assertThat(lines).hasSize(2);

        verify(auditService).recordAction(eq("SYSTEM_DEPLOYMENT_EXPORT"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("SYSTEM"), eq("deployments"), contains("\"rows\":1"));
    }

    @Test
    @DisplayName("GET export: formül karakteriyle başlayan not METNE sabitlenir (CWE-1236, Csv.cell ortak kuralı)")
    void export_neutralisesFormulaPrefix() throws Exception {
        DeploymentHistory r = row(4, "20.54.0", "MANUAL");
        r.setNote("=1+1");
        when(service.search(any(), eq("MANUAL"), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(service.kindsFor(any())).thenReturn(Map.of());

        String csv = mvc.perform(get("/api/admin/deployments/export?source=manual").session(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.get(1)).endsWith(",MANUAL,SYSTEM,'=1+1");
    }

    // ── Yazma kapısı ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST elle kayıt: kapsamlı müdür (AD ADMIN) 403 — matris izni olsa bile; servis çağrılmaz")
    void createManual_scopedAdminForbidden() throws Exception {
        mvc.perform(post("/api/admin/deployments").session(scopedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"prod\",\"version\":\"1.0.0\",\"started_at\":\"2026-09-01T10:00:00Z\",\"note\":\"CR-1\"}"))
                .andExpect(status().isForbidden());
        verify(permissionService).require(any(HttpSession.class), eq("release_history.edit"), eq("edit"));
        verify(service, never()).createManual(anyString(), any());
        verify(auditService, never()).recordAction(eq("SYSTEM_DEPLOYMENT_MANUAL"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST/DELETE/backfill: edit izni yoksa 403")
    void write_permissionDenied() throws Exception {
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(any(HttpSession.class), eq("release_history.edit"), eq("edit"));
        mvc.perform(post("/api/admin/deployments").session(auditor())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/deployments/backfill").session(auditor())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/deployments/1").session(auditor())).andExpect(status().isForbidden());
        verify(service, never()).createManual(anyString(), any());
        verify(service, never()).backfill(anyString(), any());
        verify(service, never()).deleteManual(anyLong());
    }

    @Test
    @DisplayName("POST elle kayıt: gövde snake_case okunur, helm_revision sayıya çevrilir, MANUAL denetimi yazılır")
    void createManual_ok() throws Exception {
        DeploymentHistory saved = row(9, "1.0.0", DeploymentHistory.SOURCE_MANUAL);
        when(service.createManual(eq("ADMIN"), any())).thenReturn(saved);

        mvc.perform(post("/api/admin/deployments").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"prod\",\"version\":\"v1.0.0\",\"started_at\":\"2026-09-01T10:00:00Z\","
                                + "\"note\":\"CR-1\",\"commit\":\"abc\",\"helm_revision\":\"12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(9));

        ArgumentCaptor<DeploymentHistoryService.ManualRequest> cap =
                ArgumentCaptor.forClass(DeploymentHistoryService.ManualRequest.class);
        verify(service).createManual(eq("ADMIN"), cap.capture());
        assertThat(cap.getValue().environment()).isEqualTo("prod");
        assertThat(cap.getValue().version()).isEqualTo("v1.0.0");
        assertThat(cap.getValue().startedAt()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(cap.getValue().note()).isEqualTo("CR-1");
        assertThat(cap.getValue().commit()).isEqualTo("abc");
        assertThat(cap.getValue().helmRevision()).isEqualTo(12);
        verify(auditService).recordAction(eq("SYSTEM_DEPLOYMENT_MANUAL"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("SYSTEM"), eq("9"), contains("\"version\":\"1.0.0\""));
    }

    @Test
    @DisplayName("POST elle kayıt: servis doğrulaması (IllegalArgument) 400; helm_revision sayı değilse null geçer")
    void createManual_validationError() throws Exception {
        when(service.createManual(anyString(), any())).thenThrow(new IllegalArgumentException("Sürüm X.Y.Z biçiminde olmalı"));
        mvc.perform(post("/api/admin/deployments").session(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"prod\",\"version\":\"bad\",\"helm_revision\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("X.Y.Z")));
        ArgumentCaptor<DeploymentHistoryService.ManualRequest> cap =
                ArgumentCaptor.forClass(DeploymentHistoryService.ManualRequest.class);
        verify(service).createManual(eq("ADMIN"), cap.capture());
        assertThat(cap.getValue().helmRevision()).isNull();
        verify(auditService, never()).recordAction(eq("SYSTEM_DEPLOYMENT_MANUAL"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST backfill: gövdesiz çağrı koşan ortama düşer; sonuç döner ve BACKFILL denetimi yazılır")
    void backfill_ok() throws Exception {
        when(service.backfill("ADMIN", null)).thenReturn(Map.of("environment", "prod", "candidates", 3, "inserted", 2, "skippedExisting", 1));
        mvc.perform(post("/api/admin/deployments/backfill").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inserted").value(2));
        verify(auditService).recordAction(eq("SYSTEM_DEPLOYMENT_BACKFILL"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("SYSTEM"), eq("deployments"), contains("\"inserted\":2"));

        when(service.backfill("ADMIN", "staging")).thenReturn(Map.of("environment", "staging", "inserted", 0));
        mvc.perform(post("/api/admin/deployments/backfill").session(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"staging\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("staging"));
    }

    @Test
    @DisplayName("DELETE: STARTUP satırı → 409 (IllegalStateException) ve denetim yazılmaz")
    void delete_nonManualConflict() throws Exception {
        when(service.deleteManual(5L)).thenThrow(new IllegalStateException("Yalnız elle girilen kayıt silinebilir"));
        mvc.perform(delete("/api/admin/deployments/5").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
        verify(auditService, never()).recordAction(eq("SYSTEM_DEPLOYMENT_DELETE"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE: MANUAL satır silinir → 200 + DELETE denetimi; olmayan id 404")
    void delete_manualOk_and_notFound() throws Exception {
        when(service.deleteManual(7L)).thenReturn(row(7, "1.0.0", DeploymentHistory.SOURCE_MANUAL));
        mvc.perform(delete("/api/admin/deployments/7").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(auditService).recordAction(eq("SYSTEM_DEPLOYMENT_DELETE"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("SYSTEM"), eq("7"), contains("\"environment\":\"prod\""));

        when(service.deleteManual(8L)).thenThrow(new java.util.NoSuchElementException("Dağıtım kaydı bulunamadı: 8"));
        mvc.perform(delete("/api/admin/deployments/8").session(admin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("scoped admin okuyabilir (yalnız yazma kapalı)")
    void scopedAdmin_canRead() throws Exception {
        mvc.perform(get("/api/admin/deployments").session(scopedAdmin())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/deployments/matrix").session(scopedAdmin())).andExpect(status().isOk());
        verify(service).matrix(false);
    }
}
