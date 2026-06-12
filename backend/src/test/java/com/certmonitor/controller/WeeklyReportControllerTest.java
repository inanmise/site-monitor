package com.certmonitor.controller;

import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportImage;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import com.certmonitor.service.WeeklyReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WeeklyReportController.class)
class WeeklyReportControllerTest {

    @Autowired
    MockMvc mvc;

    // AuthInterceptor bağımlılıkları — @WebMvcTest zorunlu seti
    @MockBean RememberMeService rememberMeService;
    @MockBean UserService userService;
    @MockBean AuthController authController;
    @MockBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockBean WeeklyReportService service;
    @MockBean AuditService auditService;

    private static WeeklyReport report(Long id, Long teamId, String status) {
        WeeklyReport r = new WeeklyReport();
        r.setId(id); r.setTeamId(teamId); r.setReportYear(2026); r.setWeekNo(24);
        r.setWeekLabel("2026-W24 (8–12 Haziran 2026)");
        r.setStatus(status);
        r.setContentJson("{\"version\":1}");
        return r;
    }

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "regularuser");
        s.setAttribute("displayName", "Regular User");
        s.setAttribute("userId", 42L);
        s.setAttribute("teamId", 2L);
        s.setAttribute("systemRole", "USER");
        return s;
    }

    @Test
    @DisplayName("GET /api/weekly-reports without auth returns 401")
    void list_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/weekly-reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/weekly-reports returns summaries without content_json")
    void list_returnsSummaries() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(List.of(report(1L, 2L, "DRAFT")));

        mvc.perform(get("/api/weekly-reports").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].week_label").value("2026-W24 (8–12 Haziran 2026)"))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].content_json").doesNotExist());
    }

    @Test
    @DisplayName("GET /years returns distinct report years")
    void years_returnsList() throws Exception {
        when(service.years(any(), any())).thenReturn(List.of(2026, 2025));

        mvc.perform(get("/api/weekly-reports/years").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(2026))
                .andExpect(jsonPath("$.data[1]").value(2025));
    }

    @Test
    @DisplayName("GET /{id} returns report + images metadata + manager flag")
    void get_returnsFullReport() throws Exception {
        when(service.get(eq(5L), any())).thenReturn(report(5L, 2L, "DRAFT"));
        WeeklyReportImage img = new WeeklyReportImage();
        img.setId(9L); img.setCaption("Grafik"); img.setContentType("image/png"); img.setSizeBytes(123L);
        when(service.imagesMeta(5L)).thenReturn(List.of(img));
        when(service.managerContactMissing(2L)).thenReturn(true);

        mvc.perform(get("/api/weekly-reports/5").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.images[0].caption").value("Grafik"))
                .andExpect(jsonPath("$.data.manager_contact_missing").value(true));
    }

    @Test
    @DisplayName("POST create without year/week returns 400")
    void create_missingFields_400() throws Exception {
        mvc.perform(post("/api/weekly-reports").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{id}/reject service IllegalArgument (blank note) → 400")
    void reject_blankNote_400() throws Exception {
        when(service.reject(eq(5L), any(), any()))
                .thenThrow(new IllegalArgumentException("İade notu zorunludur"));

        mvc.perform(post("/api/weekly-reports/5/reject").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{id}/approve maps MANAGER_CONTACT_MISSING to 409 and SecurityException to 403")
    void approve_errorMapping() throws Exception {
        when(service.approve(eq(5L), any()))
                .thenThrow(new IllegalStateException("MANAGER_CONTACT_MISSING"));
        mvc.perform(post("/api/weekly-reports/5/approve").session(userSession()))
                .andExpect(status().isConflict());

        when(service.approve(eq(6L), any()))
                .thenThrow(new SecurityException("Onay yetkisi yok"));
        mvc.perform(post("/api/weekly-reports/6/approve").session(userSession()))
                .andExpect(status().isForbidden());

        when(service.approve(eq(7L), any()))
                .thenThrow(new NoSuchElementException("not found"));
        mvc.perform(post("/api/weekly-reports/7/approve").session(userSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{id}/images multipart upload returns image metadata")
    void uploadImage_multipart() throws Exception {
        WeeklyReportImage img = new WeeklyReportImage();
        img.setId(9L); img.setCaption("Grafik"); img.setContentType("image/png"); img.setSizeBytes(3L);
        when(service.storeImage(eq(5L), eq("Grafik"), any(), any())).thenReturn(img);

        MockMultipartFile file = new MockMultipartFile("file", "g.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/weekly-reports/5/images").file(file)
                        .param("caption", "Grafik").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.caption").value("Grafik"));
    }

    @Test
    @DisplayName("GET /images/{id} serves binary with content type")
    void serveImage_binary() throws Exception {
        WeeklyReportImage img = new WeeklyReportImage();
        img.setId(9L); img.setContentType("image/png"); img.setData(new byte[]{1, 2, 3});
        when(service.getImage(eq(9L), any())).thenReturn(img);

        mvc.perform(get("/api/weekly-reports/images/9").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("GET /{id}/preview returns html")
    void preview_returnsHtml() throws Exception {
        when(service.buildPreviewHtml(eq(5L), any())).thenReturn("<html>rapor</html>");

        mvc.perform(get("/api/weekly-reports/5/preview").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<html>rapor</html>"));
    }

    @Test
    @DisplayName("POST /{id}/lock acquires; PUT /{id} passes version to service as Long")
    void lock_andVersionedSave() throws Exception {
        when(service.acquireLock(eq(5L), eq(false), any())).thenReturn(Map.of("acquired", true));
        mvc.perform(post("/api/weekly-reports/5/lock").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acquired").value(true));

        when(service.saveContent(eq(5L), anyString(), eq(7L), any())).thenReturn(report(5L, 2L, "DRAFT"));
        mvc.perform(put("/api/weekly-reports/5").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content_json\":\"{}\",\"version\":7}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).saveContent(eq(5L), eq("{}"), eq(7L), any());
    }

    @Test
    @DisplayName("POST /{id}/unlock returns 200; version conflict maps to 409")
    void unlock_andConflictMapping() throws Exception {
        mvc.perform(post("/api/weekly-reports/5/unlock").session(userSession()))
                .andExpect(status().isOk());

        when(service.saveContent(eq(6L), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("VERSION_CONFLICT: rapor güncellendi"));
        mvc.perform(put("/api/weekly-reports/6").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content_json\":\"{}\",\"version\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE /{id} deletes report and records audit event")
    void delete_recordsAudit() throws Exception {
        when(service.delete(eq(5L), any())).thenReturn(report(5L, 2L, "DRAFT"));

        mvc.perform(delete("/api/weekly-reports/5").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Deleted"));

        org.mockito.Mockito.verify(auditService).recordAction(
                eq("WEEKLY_REPORT_DELETE"), any(), any(),
                eq("WEEKLY_REPORT"), eq("5"), contains("2026-W24"));
    }

    @Test
    @DisplayName("DELETE /{id} maps week-window SecurityException to 403")
    void delete_forbidden_403() throws Exception {
        when(service.delete(eq(6L), any()))
                .thenThrow(new SecurityException("Yalnızca içinde bulunulan ve bir önceki haftanın raporları düzenlenebilir"));

        mvc.perform(delete("/api/weekly-reports/6").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /{id}/submit returns po_mail status")
    void submit_returnsPoMail() throws Exception {
        when(service.submit(eq(5L), any())).thenReturn(Map.of(
                "data", report(5L, 2L, "PENDING_APPROVAL"),
                "po_mail", "SENT"));

        mvc.perform(post("/api/weekly-reports/5/submit").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.po_mail").value("SENT"));
    }
}
