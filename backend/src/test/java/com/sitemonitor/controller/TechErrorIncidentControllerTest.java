package com.sitemonitor.controller;

import com.jayway.jsonpath.JsonPath;
import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorIncidentImportService;
import com.sitemonitor.service.techerror.TechErrorIncidentSyncService;
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Olay kaydı içe alma / canlı bağ uçları (2026-11-20): kapı matrisi (üye, yönetici, EDIT paylaşımı → yazar; VIEW paylaşımı,
 * AUDIT → 403; 7/24 operatörü, başka takım → 404), {@code incidents.view} gereksinimi, istek ayrıştırma hataları, servis
 * kodlarının yanıta taşınması, rapor ayrıntısındaki {@code incident_links} biçimi ve bulunamayan / görülemeyen olayın AYNI yanıtı.
 * Servisler sahte — kapı mantığı gerçek.
 */
@WebMvcTest({TechErrorIncidentController.class, TechErrorReportController.class})
class TechErrorIncidentControllerTest {

    private static final long A = 1L, B = 2L;
    private static final String BASE = "/api/tech-error-reports";

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean TechErrorReportService service;
    @MockitoBean TechErrorReportSender sender;
    @MockitoBean TeamRepository teams;
    @MockitoBean PermissionService permissions;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.techerror.TechErrorApprovalService approvals;
    @MockitoBean com.sitemonitor.service.techerror.TechErrorAnalyticsService analytics;
    @MockitoBean com.sitemonitor.service.techerror.TechErrorTeamTemplateService library;
    @MockitoBean TechErrorIncidentImportService importer;
    @MockitoBean TechErrorIncidentSyncService sync;
    @MockitoBean IncidentRecordRepository incidents;

    private final List<TechErrorReport> reports = new ArrayList<>();

    private TechErrorReport report(long id, long team) {
        TechErrorReport r = new TechErrorReport();
        r.setId(id);
        r.setChannelId(team * 10);
        r.setTeamId(team);
        r.setReportYear(2026);
        r.setReportMonth(11);
        r.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        r.setValuesJson(TechErrorValues.toJson(TechErrorValues.Values.empty()));
        r.setVersion(4L);
        reports.add(r);
        return r;
    }

    private static TechErrorChannel channel(long id, long team) {
        TechErrorChannel c = new TechErrorChannel();
        c.setId(id);
        c.setTeamId(team);
        c.setName("Kanal " + id);
        c.setActive(true);
        c.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        c.setTemplateVersion(1);
        return c;
    }

    @BeforeEach
    void setUp() {
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(true);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.crud"), eq("edit"))).thenAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            return !"AUDIT".equals(s.getAttribute("systemRole"));
        });
        when(permissions.allows(any(HttpSession.class), eq("incidents.view"), eq("view"))).thenAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            return s.getAttribute("noIncidents") == null;
        });
        when(teams.findById(any())).thenAnswer(inv -> {
            Team t = new Team();
            t.setId(inv.getArgument(0));
            t.setName("Takım " + inv.getArgument(0));
            t.setActive(true);
            return Optional.of(t);
        });
        when(service.report(anyLong())).thenAnswer(inv -> reports.stream().filter(r -> r.getId().equals(inv.getArgument(0)))
                .findFirst().orElseThrow(NoSuchElementException::new));
        when(service.channel(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return channel(id, id / 10);
        });
        when(service.defaultPeriod()).thenReturn(YearMonth.of(2026, 11));
        when(service.now()).thenReturn(Instant.parse("2026-12-05T09:00:00Z"));
        when(service.liveImageMeta(any())).thenReturn(List.of());
        when(service.previousReport(any())).thenReturn(Optional.empty());
        when(service.displayNames(any())).thenReturn(Map.of());
        when(service.refreshPreview(any(), any())).thenReturn(Map.of("differs", false, "lost_values", 0, "lost_images", 0));
        report(100, A);
        report(200, B);
    }

    private static MockHttpSession user(String role, long team, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("displayName", "Ayşe Yılmaz");
        s.setAttribute("userId", 50L);
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", manage);
        return s;
    }

    private static MockHttpSession member() {
        return user("USER", A, List.of());
    }

    private static MockHttpSession manager() {
        MockHttpSession s = user("TEAM_ADMIN", B, List.of(A));
        s.setAttribute("viewTeamIds", List.of(A, B));
        return s;
    }

    private static MockHttpSession grantee(boolean edit) {
        MockHttpSession s = user("USER", B, List.of());
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(A)));
        if (edit) s.setAttribute(SessionScope.ATTR_GRANT_EDIT, new ArrayList<>(List.of(A)));
        return s;
    }

    private static MockHttpSession audit() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "denetci");
        s.setAttribute("userId", 60L);
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }

    private static MockHttpSession nocOperator() {
        MockHttpSession s = user("USER", B, List.of());
        s.setAttribute("nocOperator", Boolean.TRUE);
        return s;
    }

    private static final String IMPORT = "{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[7]}";

    private void stubImport() {
        Map<String, Object> imported = new LinkedHashMap<>();
        imported.put("incident_id", 7L);
        imported.put("issue_id", "i-abc");
        imported.put("notes", List.of("time_unknown"));
        when(importer.importIncidents(eq(100L), eq(4L), any(), any(), any(), any()))
                .thenReturn(new TechErrorIncidentImportService.ImportOutcome(reports.get(0), List.of(imported), 1, 0));
    }

    // ── Kapı matrisi ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("içe alma kapıları: üye, takım yöneticisi ve EDIT paylaşımı yazar; VIEW paylaşımı ve AUDIT 403; 7/24 operatörü ve"
            + " başka takım 404 (raporun varlığı sızmaz); incidents.view yoksa 403")
    void importGateMatrix() throws Exception {
        stubImport();
        for (MockHttpSession s : List.of(member(), manager(), grantee(true))) {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(s).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.report.id").value(100))
                    .andExpect(jsonPath("$.data.imported[0].issue_id").value("i-abc"))
                    .andExpect(jsonPath("$.data.imported[0].notes[0]").value("time_unknown"));
        }
        for (MockHttpSession s : List.of(grantee(false), audit())) {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(s).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                    .andExpect(status().isForbidden());
        }
        for (MockHttpSession s : List.of(nocOperator(), user("USER", B, List.of()))) {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(s).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                    .andExpect(status().isNotFound());
        }
        MockHttpSession noInc = member();
        noInc.setAttribute("noIncidents", Boolean.TRUE);
        mvc.perform(post(BASE + "/reports/100/incident-imports").session(noInc).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/reports/100/incident-candidates").session(noInc)).andExpect(status().isForbidden());
        // yalnız yazabilen 3 oturum içe aldı; her biri denetime yazıldı
        verify(importer, org.mockito.Mockito.times(3)).importIncidents(eq(100L), eq(4L), any(), eq(50L), eq("Ayşe Yılmaz"), any());
        verify(auditService, org.mockito.Mockito.times(3)).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("TECH_ERROR_REPORT"), eq("100"), anyString());
    }

    @Test
    @DisplayName("olay okuma kuralı servise geçer: üye kendi takımının olayını okur, başka takımınkini okuyamaz; incidents.view yoksa hiç")
    @SuppressWarnings("unchecked")
    void incidentReaderFollowsIncidentRule() throws Exception {
        when(importer.candidates(any(), any(), any(), any(), eq(false), any())).thenReturn(Map.of("items", List.of(), "total", 0));
        mvc.perform(get(BASE + "/reports/100/incident-candidates?q=x&page=0&size=20").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        ArgumentCaptor<BiPredicate<Long, Long>> cap = ArgumentCaptor.forClass(BiPredicate.class);
        verify(importer).candidates(any(), eq("x"), eq(0), eq(20), eq(false), cap.capture());
        assertThat(cap.getValue().test(A, A)).isTrue();
        assertThat(cap.getValue().test(B, B)).isFalse();
        assertThat(cap.getValue().test(B, A)).isTrue();                // girenin takımı da olur (olay kaydı kuralı)
    }

    // ── İstek ayrıştırma ve servis kodları ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("istek doğrulaması: bölüm, olay listesi (1–10, tamsayı), parçalar, görsel bayrağı, sürüm — 400 alanın adıyla")
    void requestValidation() throws Exception {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("{\"version\":4,\"incident_ids\":[7]}", "section_id");
        bad.put("{\"version\":4,\"section_id\":\"issues\"}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[]}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[1,2,3,4,5,6,7,8,9,10,11]}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[-1]}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[1.5]}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[\"x\"]}", "incident_ids");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[7],\"parts\":[\"all\"]}", "parts");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[7],\"parts\":\"rca\"}", "parts");
        bad.put("{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[7],\"images\":\"evet\"}", "images");
        bad.put("{\"section_id\":\"issues\",\"incident_ids\":[7]}", "version");
        for (var e : bad.entrySet()) {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                            .content(e.getKey()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(e.getValue()));
        }
        // Tekrarlanan kimlikler atılır; parçalar kanonik değerlerle; "11 kimlik ama 10 farklı" geçer
        stubImport();
        mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"4\",\"section_id\":\"issues\",\"incident_ids\":[7,\"7\",7],\"parts\":[\"technical\",\"rca\"],\"images\":true}"))
                .andExpect(status().isOk());
        ArgumentCaptor<TechErrorIncidentImportService.Request> req = ArgumentCaptor.forClass(TechErrorIncidentImportService.Request.class);
        verify(importer).importIncidents(eq(100L), eq(4L), req.capture(), any(), any(), any());
        assertThat(req.getValue().incidentIds()).containsExactly(7L);
        assertThat(req.getValue().parts()).containsExactly("technical", "rca");
        assertThat(req.getValue().images()).isTrue();
        assertThat(req.getValue().sectionId()).isEqualTo("issues");
        verify(importer, never()).preview(any(), any(), any());
    }

    @Test
    @DisplayName("servis kodları yanıta taşınır: INCIDENT_NOT_FOUND / INCIDENT_OTHER_TEAM / SECTION_FULL 400 (alan incident_ids),"
            + " INCIDENT_ALREADY_IMPORTED 409 (+ incident_id), eski sürüm 409 CONCURRENT_MODIFICATION")
    void serviceCodes() throws Exception {
        for (String code : List.of("INCIDENT_NOT_FOUND", "INCIDENT_OTHER_TEAM", "SECTION_FULL", "REPORT_TOO_LARGE")) {
            when(importer.importIncidents(eq(100L), eq(4L), any(), any(), any(), any()))
                    .thenThrow(new FieldValidationException(code, "incident_ids", "Açıklayıcı ileti " + code));
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.field").value("incident_ids"))
                    .andExpect(jsonPath("$.error").value("Açıklayıcı ileti " + code));
        }
        when(importer.importIncidents(eq(100L), eq(4L), any(), any(), any(), any()))
                .thenThrow(new CodedConflictException("INCIDENT_ALREADY_IMPORTED", "zaten alınmış", Map.of("incident_id", 7L)));
        mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_ALREADY_IMPORTED"))
                .andExpect(jsonPath("$.incident_id").value(7));
        when(importer.importIncidents(eq(100L), eq(4L), any(), any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("tech_error_report version"));
        mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON).content(IMPORT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        verify(auditService, never()).recordAction(any(), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), anyString());
    }

    @Autowired TechErrorIncidentController incidentController;

    @Test
    @DisplayName("görselli içe alma yük sınırı (inceleme 2026-11-20): kişinin süren görselli içe alması varken ya da pod dolu iken 429"
            + " IMPORT_BUSY + Retry-After, servis çağrılmaz; görselsiz içe alma sınıra girmez; yer hata olsa da bırakılır")
    void imageImportGate() throws Exception {
        stubImport();
        String withImages = "{\"version\":4,\"section_id\":\"issues\",\"incident_ids\":[7],\"images\":true}";
        var gate = incidentController.imageGate;
        var held = gate.tryEnter("u:50");                              // member() = kullanıcı 50
        assertThat(held.slot()).isNotNull();
        try {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                            .content(withImages))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.code").value("IMPORT_BUSY"))
                    .andExpect(jsonPath("$.retry_after_seconds").value(5));
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                            .content(IMPORT))
                    .andExpect(status().isOk());                        // görselsiz: sınıra girmez
        } finally {
            held.slot().close();
        }
        var p1 = gate.tryEnter("x1");
        var p2 = gate.tryEnter("x2");
        try {
            mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                            .content(withImages))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("IMPORT_BUSY"));
        } finally {
            p1.slot().close();
            p2.slot().close();
        }
        when(importer.importIncidents(eq(100L), eq(4L), any(), any(), any(), any()))
                .thenThrow(new FieldValidationException("SECTION_FULL", "incident_ids", "dolu"));
        mvc.perform(post(BASE + "/reports/100/incident-imports").session(member()).contentType(MediaType.APPLICATION_JSON)
                        .content(withImages))
                .andExpect(status().isBadRequest());
        var again = gate.tryEnter("u:50");                             // hata olsa da yer bırakıldı
        assertThat(again.slot()).isNotNull();
        again.slot().close();
        verify(importer, org.mockito.Mockito.times(2)).importIncidents(eq(100L), eq(4L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("önizleme: proposals döner, denetime yazılmaz, rapor yazmaz")
    void preview() throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("incident_id", 7L);
        p.put("hidden", false);
        when(importer.preview(any(), any(), any())).thenReturn(List.of(p));
        mvc.perform(post(BASE + "/reports/100/incident-imports/preview").session(member()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section_id\":\"issues\",\"incident_ids\":[7]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proposals[0].incident_id").value(7))
                .andExpect(jsonPath("$.data.proposals[0].hidden").value(false));
        verify(auditService, never()).recordAction(any(), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), anyString());
        verify(importer, never()).importIncidents(any(), any(), any(), any(), any(), any());
    }

    // ── Canlı bağ uçları ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rapor ayrıntısı incident_links taşır (durum, başlık, sabitlenen gruplar, notlar); okunamazsa null + hata bayrağı")
    void reportDetailCarriesIncidentLinks() throws Exception {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("section_id", "issues");
        l.put("issue_id", "i-abc");
        l.put("incident_id", 7L);
        l.put("state", "stale");
        l.put("title", "Ödeme");
        l.put("status", "RESOLVED");
        l.put("occurred_at", "2026-11-05T07:00:00");
        l.put("pinned", List.of("errors"));
        l.put("notes", List.of("value_refused"));
        l.put("synced_at", "2026-12-04T09:00:00Z");
        l.put("incident_updated_at", "2026-11-10T08:00:00");
        when(sync.linksDto(any(), any(), any())).thenReturn(List.of(l));
        mvc.perform(get(BASE + "/reports/100").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident_links[0].state").value("stale"))
                .andExpect(jsonPath("$.data.incident_links[0].pinned[0]").value("errors"))
                .andExpect(jsonPath("$.data.incident_links[0].notes[0]").value("value_refused"))
                .andExpect(jsonPath("$.data.incident_links[0].incident_id").value(7))
                .andExpect(jsonPath("$.data.incident_links_error").doesNotExist());
        when(sync.linksDto(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        mvc.perform(get(BASE + "/reports/100").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident_links").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.incident_links_error").value(true));
    }

    @Test
    @DisplayName("yenile: sürüm + unpin servise; denetim; olay görülemezse / bağ yoksa 404 kodlarıyla; VIEW paylaşımı 403")
    void refresh() throws Exception {
        when(sync.refresh(eq(100L), eq(4L), eq("issues"), eq("i-abc"), any(), eq(50L), any()))
                .thenReturn(new TechErrorIncidentSyncService.Outcome(1, false, true));
        mvc.perform(post(BASE + "/reports/100/incident-links/issues/i-abc/refresh").session(member())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4,\"unpin\":[\"errors\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.id").value(100));
        verify(sync).refresh(eq(100L), eq(4L), eq("issues"), eq("i-abc"), eq(List.of("errors")), eq(50L), any());
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"),
                eq("100"), anyString());
        mvc.perform(post(BASE + "/reports/100/incident-links/issues/i-abc/refresh").session(member())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unpin\":\"all\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("version"));
        when(sync.refresh(eq(100L), eq(5L), any(), any(), any(), any(), any()))
                .thenThrow(new CodedNotFoundException("INCIDENT_NOT_FOUND", "Olay kaydı bulunamadı"));
        mvc.perform(post(BASE + "/reports/100/incident-links/issues/i-abc/refresh").session(member())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
        when(sync.refresh(eq(100L), eq(6L), any(), any(), any(), any(), any()))
                .thenThrow(new CodedNotFoundException("INCIDENT_LINK_NOT_FOUND", "Bağ bulunamadı"));
        mvc.perform(post(BASE + "/reports/100/incident-links/issues/i-abc/refresh").session(member())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":6}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_LINK_NOT_FOUND"));
        mvc.perform(post(BASE + "/reports/100/incident-links/issues/i-abc/refresh").session(grantee(false))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("bağı koparma: incident_links döner + denetim; yetişme: yazılan varsa denetim, rapor ayrıntısı döner")
    void unlinkAndSync() throws Exception {
        when(sync.unlink(100L, "issues", "i-abc", 50L)).thenReturn(7L);
        when(sync.linksDto(any(), any(), any())).thenReturn(List.of());
        mvc.perform(delete(BASE + "/reports/100/incident-links/issues/i-abc").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident_links.length()").value(0));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"),
                eq("100"), anyString());
        mvc.perform(delete(BASE + "/reports/100/incident-links/issues/i-abc").session(audit())).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/reports/200/incident-links/issues/i-abc").session(member())).andExpect(status().isNotFound());

        when(sync.catchUp(100L)).thenReturn(0).thenReturn(2);
        mvc.perform(post(BASE + "/reports/100/incident-links/sync").session(member())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.id").value(100));
        mvc.perform(post(BASE + "/reports/100/incident-links/sync").session(member())).andExpect(status().isOk());
        verify(auditService, org.mockito.Mockito.times(2)).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("TECH_ERROR_REPORT"), eq("100"), anyString());
    }

    @Test
    @DisplayName("olayın kullanıldığı raporlar: bulunamayan ve görülemeyen olay AYNI 404 yanıtı; okunabilen olayda liste; geçersiz kimlik 400")
    void usageSameAnswerForMissingAndHidden() throws Exception {
        IncidentRecord other = new IncidentRecord();
        other.setId(8L);
        other.setTeamId(B);
        other.setCreatedByTeamId(B);
        IncidentRecord own = new IncidentRecord();
        own.setId(9L);
        own.setTeamId(A);
        own.setCreatedByTeamId(A);
        when(incidents.findById(7L)).thenReturn(Optional.empty());
        when(incidents.findById(8L)).thenReturn(Optional.of(other));
        when(incidents.findById(9L)).thenReturn(Optional.of(own));
        String missing = mvc.perform(get(BASE + "/incident-usage?incident_id=7").session(member()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String hidden = mvc.perform(get(BASE + "/incident-usage?incident_id=8").session(member()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(missing, "$.code")).isEqualTo("INCIDENT_NOT_FOUND")
                .isEqualTo(JsonPath.read(hidden, "$.code"));
        assertThat((String) JsonPath.read(missing, "$.error")).isEqualTo(JsonPath.read(hidden, "$.error"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("report_id", 100L);
        row.put("state", "ok");
        when(sync.usage(eq(own), any(), any())).thenReturn(List.of(row));
        mvc.perform(get(BASE + "/incident-usage?incident_id=9").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].report_id").value(100))
                .andExpect(jsonPath("$.data.items[0].state").value("ok"));
        mvc.perform(get(BASE + "/incident-usage?incident_id=abc").session(member()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("incident_id"));
        MockHttpSession noInc = member();
        noInc.setAttribute("noIncidents", Boolean.TRUE);
        mvc.perform(get(BASE + "/incident-usage?incident_id=9").session(noInc)).andExpect(status().isForbidden());
    }
}
