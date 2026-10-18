package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kanal teknik hata raporu uçları (2026-10-13): izin + TAKIM kapsamı. Üye kendi takımının kanal/şablon/raporunu yazar;
 * başka takımın kaydı 404 (varlığı sızmaz); AUDIT okur ama yazamaz; kanal ve gönderilmiş rapor silmesi yönetim kapsamı
 * ister; gönderim sonucu doğru durum koduna döner. Servis sahte — kapı mantığı gerçek.
 */
@WebMvcTest(TechErrorReportController.class)
class TechErrorReportControllerTest {

    private static final long A = 1L, B = 2L;

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

    private final List<TechErrorChannel> channels = new ArrayList<>();
    private final List<TechErrorReport> reports = new ArrayList<>();

    private static Team team(long id) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + id);
        t.setActive(true);
        t.setEmail("takim" + id + "@example.com");
        return t;
    }

    private TechErrorChannel channel(long id, long team) {
        TechErrorChannel c = new TechErrorChannel();
        c.setId(id);
        c.setTeamId(team);
        c.setName("Kanal " + id);
        c.setActive(true);
        c.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        c.setTemplateVersion(3);
        channels.add(c);
        return c;
    }

    private TechErrorReport report(long id, long channel, long team, Instant sentAt) {
        TechErrorReport r = new TechErrorReport();
        r.setId(id);
        r.setChannelId(channel);
        r.setTeamId(team);
        r.setReportYear(2026);
        r.setReportMonth(9);
        r.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        r.setValuesJson(TechErrorValues.toJson(TechErrorValues.Values.empty()));
        r.setLastSentAt(sentAt);
        r.setVersion(4L);
        reports.add(r);
        return r;
    }

    @BeforeEach
    void setUp() {
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(true);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.crud"), eq("edit"))).thenAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            return !"AUDIT".equals(s.getAttribute("systemRole"));
        });
        when(teams.findById(any())).thenAnswer(inv -> Optional.of(team((Long) inv.getArgument(0))));
        when(teams.findAll()).thenReturn(List.of(team(A), team(B)));
        when(teams.findAllById(any())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) out.add(team((Long) o));
            return out;
        });
        when(service.channel(anyLong())).thenAnswer(inv -> channels.stream().filter(c -> c.getId().equals(inv.getArgument(0)))
                .findFirst().orElseThrow(NoSuchElementException::new));
        when(service.report(anyLong())).thenAnswer(inv -> reports.stream().filter(r -> r.getId().equals(inv.getArgument(0)))
                .findFirst().orElseThrow(NoSuchElementException::new));
        when(service.channels(any())).thenAnswer(inv -> {
            Collection<?> teamIds = inv.getArgument(0);
            return channels.stream().filter(c -> teamIds == null || teamIds.contains(c.getTeamId())).toList();
        });
        when(service.defaultPeriod()).thenReturn(YearMonth.of(2026, 9));
        when(service.now()).thenReturn(Instant.parse("2026-10-13T09:00:00Z"));
        when(service.periodRows(any(), any())).thenReturn(Map.of());
        when(service.reportCounts(any())).thenReturn(Map.of());
        when(service.refreshPreview(any(), any())).thenReturn(Map.of("differs", false, "lost_values", 0, "lost_images", 0));
        when(service.previousReport(any())).thenReturn(Optional.empty());
        when(service.displayNames(any())).thenReturn(Map.of());
        channel(10, A);
        channel(20, B);
    }

    private static MockHttpSession user(String role, long team, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("userId", 50L);
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", manage);
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

    @Test
    @DisplayName("genel bakış: üye yalnız kendi takımının kanallarını görür; can_edit sunucudan; AUDIT hepsini görür, yazamaz")
    void overviewScope() throws Exception {
        mvc.perform(get("/api/tech-error-reports/overview").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels.length()").value(1))
                .andExpect(jsonPath("$.data.channels[0].id").value(10))
                .andExpect(jsonPath("$.data.channels[0].can_edit").value(true))
                .andExpect(jsonPath("$.data.channels[0].can_delete").value(false))
                .andExpect(jsonPath("$.data.period.month").value(9))
                .andExpect(jsonPath("$.data.can_create").value(true));
        mvc.perform(get("/api/tech-error-reports/overview").session(audit()))
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                .andExpect(jsonPath("$.data.channels[0].can_edit").value(false))
                .andExpect(jsonPath("$.data.can_create").value(false));
    }

    @Test
    @DisplayName("başka takımın kanalı / raporu / görseli kimliğiyle istenirse 404 (varlığı sızmaz)")
    void foreignIs404() throws Exception {
        report(200, 20, B, null);
        TechErrorReportImage img = new TechErrorReportImage();
        img.setId(900L);
        img.setReportId(200L);
        img.setContentType("image/png");
        img.setData(new byte[]{ 1 });
        when(service.image(900L)).thenReturn(img);
        MockHttpSession s = user("USER", A, List.of());
        mvc.perform(get("/api/tech-error-reports/channels/20/template").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/channels/20/reports").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/reports/200").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/reports/200/pdf").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/images/900").session(s)).andExpect(status().isNotFound());
        mvc.perform(put("/api/tech-error-reports/channels/20/template").session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3,\"template\":{}}")).andExpect(status().isNotFound());
        verify(service, never()).saveTemplate(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    @DisplayName("şablon: üye kaydeder (sürüm + denetim); AUDIT okur, kaydı 403; sürüm yoksa 400")
    void templateWrite() throws Exception {
        when(service.saveTemplate(eq(10L), eq(3), any(), any())).thenReturn(4);
        String body = "{\"version\":3,\"template\":" + TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")) + "}";
        mvc.perform(put("/api/tech-error-reports/channels/10/template").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4));
        verify(auditService).recordAction(eq("TECH_ERROR_TEMPLATE_SAVE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_CHANNEL"),
                eq("10"), anyString());
        mvc.perform(get("/api/tech-error-reports/channels/10/template").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.channel.can_edit").value(false));
        mvc.perform(put("/api/tech-error-reports/channels/10/template").session(audit())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/tech-error-reports/channels/10/template").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("version"));
    }

    @Test
    @DisplayName("kanal silme yalnız yönetim kapsamı: USER 403, TEAM_ADMIN 200; başka takımın kanalına kanal açılamaz")
    void deleteAndCreateScope() throws Exception {
        mvc.perform(delete("/api/tech-error-reports/channels/10").session(user("USER", A, List.of())))
                .andExpect(status().isForbidden());
        verify(service, never()).deleteChannel(anyLong());
        when(service.deleteChannel(10L)).thenReturn(3);
        mvc.perform(delete("/api/tech-error-reports/channels/10").session(user("TEAM_ADMIN", A, List.of(A))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reports_deleted").value(3));
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":" + B + ",\"name\":\"Mobil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("team_id"));
        verify(service, never()).createChannel(eq(B), any(), any(), any(), any());
        verify(service, never()).createChannel(eq(B), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rapor silme: gönderilmemiş taslağı üye siler; gönderilmiş raporu yalnız yönetici")
    void reportDelete() throws Exception {
        report(100, 10, A, null);
        report(101, 10, A, Instant.parse("2026-10-02T08:00:00Z"));
        mvc.perform(delete("/api/tech-error-reports/reports/100").session(user("USER", A, List.of()))).andExpect(status().isOk());
        mvc.perform(delete("/api/tech-error-reports/reports/101").session(user("USER", A, List.of()))).andExpect(status().isForbidden());
        verify(service, never()).deleteReport(eq(101L), org.mockito.ArgumentMatchers.anyBoolean());
        mvc.perform(delete("/api/tech-error-reports/reports/101").session(user("TEAM_ADMIN", A, List.of(A)))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("rapor okuma: şablonda trend varsa son 12 ayın verisi; okunamazsa rapor yine açılır, trend 'okunamadı' der")
    void readReportTrend() throws Exception {
        TechErrorTemplate.Template t = com.sitemonitor.service.techerror.TechErrorTestTemplates.withTrend("tr");
        report(100, 10, A, null).setTemplateJson(TechErrorTemplate.toJson(t));
        when(service.imageMeta(100L)).thenReturn(List.of());
        var cur = com.sitemonitor.service.techerror.TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42)), t);
        when(service.trend(any(), any(), any(), any())).thenReturn(com.sitemonitor.service.techerror.TechErrorTrend.build(t, cur, 2026, 9, List.of()));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend.months.length()").value(12))
                .andExpect(jsonPath("$.data.trend.months[11].month").value(9))
                .andExpect(jsonPath("$.data.trend.values['error-rate'][11]").value(0.42))
                .andExpect(jsonPath("$.data.trend.min_points").value(2));
        when(service.trend(any(), any(), any(), any())).thenThrow(new IllegalStateException("db"));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend.unavailable").value(true))
                .andExpect(jsonPath("$.data.template").exists());
    }

    @Test
    @DisplayName("değer kaydı: otomatik kayıt geçmişte önceki olayla birleşince denetime YAZILMAZ; yeni olay açan otomatik kayıt"
            + " 'auto' ile, elle kayıt her değişiklikte yazılır")
    void saveReportAuditsOncePerAutoWindow() throws Exception {
        report(100, 10, A, null);
        TechErrorReport saved = new TechErrorReport();
        saved.setId(100L);
        saved.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        saved.setValuesJson(TechErrorValues.toJson(TechErrorValues.Values.empty()));
        saved.setVersion(5L);
        when(service.saveValues(eq(100L), eq(4L), any(), eq(50L), eq(true)))
                .thenReturn(new TechErrorReportService.SaveOutcome(saved, true))
                .thenReturn(new TechErrorReportService.SaveOutcome(saved, false));
        when(service.saveValues(eq(100L), eq(4L), any(), eq(50L), eq(false)))
                .thenReturn(new TechErrorReportService.SaveOutcome(saved, false));
        String auto = "{\"version\":4,\"auto\":true,\"values\":{\"fields\":{}}}";
        mvc.perform(put("/api/tech-error-reports/reports/100").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(auto))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(5));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());
        mvc.perform(put("/api/tech-error-reports/reports/100").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(auto))
                .andExpect(status().isOk());
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("100"),
                org.mockito.ArgumentMatchers.contains("\"auto\":true"));
        mvc.perform(put("/api/tech-error-reports/reports/100").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4,\"values\":{\"fields\":{}}}"))
                .andExpect(status().isOk());
        verify(auditService, org.mockito.Mockito.times(2)).recordAction(eq("TECH_ERROR_REPORT_UPDATE"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("100"), any());
    }

    @Test
    @DisplayName("rapor okuma: şablon + değer + düzenleme bayrakları; AUDIT için can_edit false ve şablon güncelleme önizlemesi yok")
    void readReport() throws Exception {
        report(100, 10, A, null);
        when(service.imageMeta(100L)).thenReturn(List.of());
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.title").value("Kanal 10 Kanalı Teknik Hata Mesajları - Eylül 2026"))
                .andExpect(jsonPath("$.data.report.status").value("draft"))
                .andExpect(jsonPath("$.data.report.version").value(4))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.team_email_set").value(true))
                .andExpect(jsonPath("$.data.template.sections.length()").value(8))
                .andExpect(jsonPath("$.data.template_update.differs").value(false));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(audit()))
                .andExpect(jsonPath("$.data.can_edit").value(false))
                .andExpect(jsonPath("$.data.template_update").isEmpty());
    }

    @Test
    @DisplayName("gönderim: SENT 200, SKIPPED 409 MAIL_DISABLED, FAILED 502 MAIL_FAILED, takım adresi yok 409; her sonuç denetlenir")
    void sendOutcomes() throws Exception {
        report(100, 10, A, null);
        MockHttpSession s = user("USER", A, List.of());
        when(sender.send(any(), any(), any(), any())).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("takim1@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SENT"))
                .andExpect(jsonPath("$.data.to[0]").value("takim1@example.com"));
        when(sender.send(any(), any(), any(), any())).thenReturn(
                new TechErrorReportSender.Result("SKIPPED", "SKIPPED_DISABLED", List.of("takim1@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAIL_DISABLED"));
        when(sender.send(any(), any(), any(), any())).thenReturn(
                new TechErrorReportSender.Result("FAILED", "FAILED: 554", List.of("takim1@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("MAIL_FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("554"))));
        when(sender.send(any(), any(), any(), any())).thenThrow(new CodedConflictException("TEAM_EMAIL_MISSING", "adres yok"));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_EMAIL_MISSING"));
        verify(auditService, org.mockito.Mockito.times(4)).recordAction(eq("TECH_ERROR_REPORT_SEND"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("TECH_ERROR_REPORT"), eq("100"), anyString());
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(audit())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rapor açma: kanalın takımına yazabilen açar; yıl/ay eksikse alan hatası")
    void createReport() throws Exception {
        TechErrorReport created = report(300, 10, A, null);
        when(service.createReport(any(), eq(2026), eq(9), any())).thenReturn(created);
        mvc.perform(post("/api/tech-error-reports/reports").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel_id\":10,\"year\":2026,\"month\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(300));
        mvc.perform(post("/api/tech-error-reports/reports").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel_id\":10,\"year\":2026}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("month"));
        mvc.perform(post("/api/tech-error-reports/reports").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel_id\":20,\"year\":2026,\"month\":9}"))
                .andExpect(status().isNotFound());
        verify(service, never()).createReport(eq(channels.get(1)), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
    // ── Değişiklik ve sürüm geçmişi (2026-10-14, Faz B) ──────────────────────────────────────────────

    @MockitoBean com.sitemonitor.service.techerror.TechErrorHistoryService history;

    private static com.sitemonitor.model.TechErrorReportEvent event(long id, String kind, Long actor, String actorName, String detail) {
        var e = new com.sitemonitor.model.TechErrorReportEvent();
        e.setId(id);
        e.setReportId(100L);
        e.setChannelId(10L);
        e.setTeamId(A);
        e.setKind(kind);
        e.setActorId(actor);
        e.setActorName(actorName);
        e.setEventAt(Instant.parse("2026-10-13T08:00:00Z"));
        e.setDetailJson(detail);
        return e;
    }

    @Test
    @DisplayName("rapor geçmişi: okuyan görür (ad, ayrıntı, sistem bayrağı; kullanıcı kimliği YOK); başka takım 404; bozuk sayfa 400")
    void reportHistory() throws Exception {
        report(100, 10, A, null);
        when(history.reportHistory(eq(100L), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.sitemonitor.service.techerror.TechErrorHistoryService.HistoryPage(List.of(
                        event(5, "SEND_RESULT", null, null, "{\"state\":\"SENT\"}"),
                        event(4, "VALUES", 7L, "Ayşe Yılmaz", "{\"changes\":[{\"label\":\"Oran\",\"before\":\"%0,4\",\"after\":\"%0,5\",\"op\":\"change\"}]}")),
                        true, Instant.parse("2026-10-01T08:00:00Z"), false));
        mvc.perform(get("/api/tech-error-reports/reports/100/history?limit=2").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(2))
                .andExpect(jsonPath("$.data.events[0].system").value(true))
                .andExpect(jsonPath("$.data.events[1].actor_name").value("Ayşe Yılmaz"))
                .andExpect(jsonPath("$.data.events[1].actor_id").doesNotExist())
                .andExpect(jsonPath("$.data.events[1].detail.changes[0].after").value("%0,5"))
                .andExpect(jsonPath("$.data.has_more").value(true))
                .andExpect(jsonPath("$.data.recorded_since").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.data.capped").value(false))
                .andExpect(jsonPath("$.data.max_events").value(500));
        verify(history).reportHistory(100L, null, 2);
        mvc.perform(get("/api/tech-error-reports/reports/100/history?before=4").session(audit())).andExpect(status().isOk());
        verify(history).reportHistory(100L, 4L, 50);
        mvc.perform(get("/api/tech-error-reports/reports/100/history").session(user("USER", B, List.of())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/reports/100/history?before=abc").session(user("USER", A, List.of())))
                .andExpect(status().isBadRequest());
    }

    private static com.sitemonitor.repository.TechErrorTemplateVersionRepository.Meta meta(int v) {
        return new com.sitemonitor.repository.TechErrorTemplateVersionRepository.Meta() {
            public int getVersion() { return v; }
            public Instant getSavedAt() { return Instant.parse("2026-10-0" + v + "T08:00:00Z"); }
            public String getSavedByName() { return "Kişi " + v; }
            public int getSectionCount() { return 5; }
            public int getFieldCount() { return 10 + v; }
            public int getSlotCount() { return 2; }
            public String getSourceKind() { return v == 1 ? "library" : null; }
            public String getSourceName() { return v == 1 ? "Takım varsayılanı" : null; }
        };
    }

    @Test
    @DisplayName("şablon sürümleri: kayıtlı liste en yeni önce, o anki işaretli; geçmişi olmayan kanal o anki sürümü tek satır görür")
    void templateVersions() throws Exception {
        when(history.templateVersions(10L)).thenReturn(List.of(meta(3), meta(2)));
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_version").value(3))
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].current").value(true))
                .andExpect(jsonPath("$.data.versions[0].recorded").value(true))
                .andExpect(jsonPath("$.data.versions[1].field_count").value(12))
                .andExpect(jsonPath("$.data.versions[1].saved_by_id").doesNotExist());
        when(history.templateVersions(10L)).thenReturn(List.of());
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.versions[0].version").value(3))
                .andExpect(jsonPath("$.data.versions[0].recorded").value(false))
                .andExpect(jsonPath("$.data.versions[0].current").value(true));
        mvc.perform(get("/api/tech-error-reports/channels/20/template/versions").session(user("USER", A, List.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("tek sürüm: kayıtlı olan gövdesiyle; kayıtlı değilse o anki sürüm kanaldan; başka kayıtsız sürüm 404")
    void templateVersionDetail() throws Exception {
        var v2 = new com.sitemonitor.model.TechErrorTemplateVersion();
        v2.setVersion(2);
        v2.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("en")));
        v2.setSavedByName("Kişi 2");
        when(history.templateVersion(10L, 2)).thenReturn(Optional.of(v2));
        when(history.templateVersion(10L, 3)).thenReturn(Optional.empty());
        when(history.templateVersion(10L, 1)).thenReturn(Optional.empty());
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions/2").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.lang").value("en"))
                .andExpect(jsonPath("$.data.current").value(false));
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions/3").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.lang").value("tr"))
                .andExpect(jsonPath("$.data.current").value(true));
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions/1").session(user("USER", A, List.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rapor yanıtı komşu raporları taşır: önceki / sonraki EN YAKIN dönem (ara ay boş olabilir)")
    void reportNeighbors() throws Exception {
        TechErrorReport self = report(100, 10, A, null);
        TechErrorReport older = report(101, 10, A, null);
        older.setReportMonth(6);
        TechErrorReport oldest = report(102, 10, A, null);
        oldest.setReportYear(2025);
        oldest.setReportMonth(12);
        TechErrorReport newer = report(103, 10, A, null);
        newer.setReportYear(2027);
        newer.setReportMonth(1);
        when(service.reportRows(10L)).thenReturn(List.of(TechErrorReportController.rowOf(newer), TechErrorReportController.rowOf(self),
                TechErrorReportController.rowOf(older), TechErrorReportController.rowOf(oldest)));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.neighbors.prev.id").value(101))
                .andExpect(jsonPath("$.data.neighbors.prev.month").value(6))
                .andExpect(jsonPath("$.data.neighbors.next.id").value(103))
                .andExpect(jsonPath("$.data.neighbors.next.year").value(2027));
        mvc.perform(get("/api/tech-error-reports/reports/102").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.neighbors.prev").doesNotExist())
                .andExpect(jsonPath("$.data.neighbors.next.id").value(101));
    }
    // ── PO onayı (2026-10-17, S6) ────────────────────────────────────────────────────────────────────

    private TechErrorChannel execChannel(long id, long team) {
        TechErrorChannel c = channel(id, team);
        c.setExecutiveEmails("ceo@example.com, cto@example.com");
        return c;
    }

    private static MockHttpSession globalAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("onay isteği: yazabilen üye 200 (onaylayıcılar, posta sayıları, onay bloğu) + denetim; servisin 409'u aynen; AUDIT 403")
    void approvalRequest() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        report(130, 30, A, null);
        when(approvals.request(any(), any(), any(), any())).thenReturn(new com.sitemonitor.service.techerror.TechErrorApprovalService.RequestResult(
                List.of(new com.sitemonitor.service.techerror.TechErrorApprovalService.Approver(7L, "PO Bir", "po1@example.com")), 1, 0, null, Instant.now()));
        mvc.perform(post("/api/tech-error-reports/reports/130/approval/request").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("pending"))
                .andExpect(jsonPath("$.data.approvers[0].name").value("PO Bir"))
                .andExpect(jsonPath("$.data.approvers[0].has_email").value(true))
                .andExpect(jsonPath("$.data.mailed").value(1))
                .andExpect(jsonPath("$.data.approval.state").value("none"));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_APPROVAL_REQUEST"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("130"), anyString());
        when(approvals.request(any(), any(), any(), any())).thenThrow(
                new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("EXEC_RECIPIENTS_MISSING", "alıcı yok"));
        mvc.perform(post("/api/tech-error-reports/reports/130/approval/request").session(user("USER", A, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXEC_RECIPIENTS_MISSING"));
        mvc.perform(post("/api/tech-error-reports/reports/130/approval/request").session(audit()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("karar: approve izni + PO üyeliği (servis doğrular) → 200 send_state + denetim; PO olmayan 403; geçersiz karar 400; izin yoksa 403")
    void approvalDecide() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        report(130, 30, A, null);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.approve"), eq("execute"))).thenReturn(true);
        when(approvals.decide(any(), any(), any(), any(), eq(true), any(), eq("app"))).thenReturn(
                new com.sitemonitor.service.techerror.TechErrorApprovalService.Decision("APPROVED",
                        new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 10, "Konu"), null, Instant.now()));
        String url = "/api/tech-error-reports/reports/130/approval/decide";
        mvc.perform(post(url).session(user("USER", A, List.of())).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("approved"))
                .andExpect(jsonPath("$.data.send_state").value("SENT"))
                .andExpect(jsonPath("$.data.sent_to[0]").value("ceo@example.com"));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_APPROVE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("130"), anyString());
        when(approvals.decide(any(), any(), any(), any(), eq(false), any(), eq("app"))).thenThrow(new SecurityException("PO değil"));
        mvc.perform(post(url).session(user("USER", A, List.of())).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).session(user("USER", A, List.of())).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.approve"), eq("execute"))).thenReturn(false);
        mvc.perform(post(url).session(user("USER", A, List.of())).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("gönderim onaya bağlı kanal: onaysız 409 APPROVAL_REQUIRED; onaylı ve değişmemiş → EXECUTIVE gönderim; onaydan sonra değişen 409 (stale); alıcısız kanal eski 4 argümanlı yol")
    void sendRequiresApproval() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        TechErrorReport r = report(130, 30, A, null);
        MockHttpSession s = user("USER", A, List.of());
        mvc.perform(post("/api/tech-error-reports/reports/130/send").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
        verify(sender, never()).send(any(), any(), any(), any(), any());
        verify(sender, never()).send(any(), any(), any(), any());
        r.setApprovalState("APPROVED");
        r.setApprovalRev(3L);
        r.setContentRev(3L);
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE))).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/130/send").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.to[0]").value("ceo@example.com"));
        r.setContentRev(4L);
        mvc.perform(post("/api/tech-error-reports/reports/130/send").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
        verify(sender, never()).send(any(), any(), any(), any());
        // Alıcısız kanal: eski yol
        channel(31, A);
        report(131, 31, A, null);
        when(sender.send(any(), any(), any(), any())).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("takim1@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/131/send").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.to[0]").value("takim1@example.com"));
    }

    @Test
    @DisplayName("kanal yönetici alıcıları: üye 403, takımı yöneten 200 (servise iletilir); DTO executive_count / approval_required / can_manage; ad değişikliği üyeye açık")
    void channelExecutiveEmails() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        org.assertj.core.api.Assertions.assertThat(ec.getExecutiveEmails()).isEqualTo("ceo@example.com, cto@example.com");
        org.assertj.core.api.Assertions.assertThat(TechErrorReportSender.executiveRecipients(ec)).containsExactly("ceo@example.com", "cto@example.com");
        when(service.updateChannel(anyLong(), any(), any())).thenAnswer(inv -> ec);
        String url = "/api/tech-error-reports/channels/30";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).session(user("USER", A, List.of()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"executive_emails\":\"x@example.com\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).updateChannel(anyLong(), any(), any());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).session(user("USER", A, List.of(A)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"executive_emails\":\"x@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executive_count").value(2))
                .andExpect(jsonPath("$.data.approval_required").value(true))
                .andExpect(jsonPath("$.data.can_manage").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).session(user("USER", A, List.of()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"Yeni\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(false));
    }

    @Test
    @DisplayName("rapor okuma: approval_detail — akış, alıcı sayısı, onaylayıcılar, can_request / is_approver / can_decide; bekleyen istekte karar açılır, istek kapanır")
    void reportApprovalDetail() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        TechErrorReport r = report(130, 30, A, null);
        when(approvals.approvers(A)).thenReturn(List.of(new com.sitemonitor.service.techerror.TechErrorApprovalService.Approver(50L, "Ben PO", "me@example.com")));
        when(approvals.isApprover(50L, A)).thenReturn(true);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.approve"), eq("execute"))).thenReturn(true);
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approval_detail.required").value(true))
                .andExpect(jsonPath("$.data.approval_detail.executive_count").value(2))
                .andExpect(jsonPath("$.data.approval_detail.approvers[0].name").value("Ben PO"))
                .andExpect(jsonPath("$.data.approval_detail.can_request").value(true))
                .andExpect(jsonPath("$.data.approval_detail.is_approver").value(true))
                .andExpect(jsonPath("$.data.approval_detail.can_decide").value(false))
                .andExpect(jsonPath("$.data.approval_detail.can_send_executive").value(false))
                .andExpect(jsonPath("$.data.report.approval.state").value("none"));
        r.setApprovalState("PENDING");
        r.setApprovalRev(0L);
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.approval_detail.can_decide").value(true))
                .andExpect(jsonPath("$.data.approval_detail.can_request").value(false))
                .andExpect(jsonPath("$.data.report.approval.state").value("pending"));
        r.setContentRev(2L);   // istekten sonra değişti → eskidi: yeniden istenebilir, karar verilemez
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.report.approval.state").value("stale"))
                .andExpect(jsonPath("$.data.approval_detail.can_decide").value(false))
                .andExpect(jsonPath("$.data.approval_detail.can_request").value(true));
        r.setApprovalState("APPROVED");
        r.setApprovalRev(2L);
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.report.approval.state").value("approved"))
                .andExpect(jsonPath("$.data.approval_detail.can_send_executive").value(true))
                .andExpect(jsonPath("$.data.approval_detail.can_request").value(false));   // onay boşa gitmesin
        // Bekleyen istek sessiz süreden eski → yeniden gönderilebilir (hatırlatma / yarıda kalan istek)
        r.setApprovalState("PENDING");
        r.setApprovalRev(2L);
        r.setApprovalRequestedAt(Instant.now().minus(com.sitemonitor.service.techerror.TechErrorApprovalService.RESEND_AFTER).minusSeconds(60));
        when(service.now()).thenReturn(Instant.now());
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.report.approval.state").value("pending"))
                .andExpect(jsonPath("$.data.approval_detail.can_request").value(true))
                .andExpect(jsonPath("$.data.approval_detail.resend").value(true));
    }

    @Test
    @DisplayName("modül görünürlüğü (S6): listede olmayan takımın üyesi overview'da 403, raporu 404; listedeki takım görür (kapalı takımın kanalı listelenmez); global yönetici her şeyi görür")
    void moduleVisibility() throws Exception {
        TechErrorChannel ec = execChannel(30, A);
        report(130, 30, A, null);
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            mvc.perform(get("/api/tech-error-reports/overview").session(user("USER", A, List.of()))).andExpect(status().isForbidden());
            mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", B, List.of()))).andExpect(status().isNotFound());
            mvc.perform(get("/api/tech-error-reports/overview").session(user("USER", B, List.of())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.channels[?(@.team_id == 1)]").isEmpty());
            mvc.perform(get("/api/tech-error-reports/reports/130").session(globalAdmin())).andExpect(status().isOk());
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        }
    }

    // ── E-posta önizlemesi (10. sürüm, 2026-10-17) ─────────────────────────────────────────────────

    @Test
    @DisplayName("e-posta önizlemesi: okuma kapsamı yeter (gönderim yok); konu / alıcılar / gövde / ek; yönetici alıcılı kanalda EXECUTIVE; paylaşımla okuyan adres görmez; başka takım 404")
    void mailPreview() throws Exception {
        report(100, 10, A, null);
        when(sender.preview(any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM))).thenReturn(
                new TechErrorReportSender.Preview("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026", List.of("takim1@example.com"), List.of(),
                        "TEAM", "<html><img src=\"data:image/png;base64,AAAA\"></html>", "metin", "mobil-2026-09.pdf", 1, 0, null,
                        new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"), "Ali Veli",
                                Instant.parse("2026-09-05T06:12:00Z"), null, null, null), 123_456L, 15, 0, 0, false));
        mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(audit()))      // salt okuyan da önizler
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026"))
                .andExpect(jsonPath("$.data.provenance.created_by").value("Ayşe Yılmaz"))
                .andExpect(jsonPath("$.data.provenance.updated_by").value("Ali Veli"))
                .andExpect(jsonPath("$.data.provenance.approval_state").doesNotExist())
                .andExpect(jsonPath("$.data.pdf_bytes").value(123456))
                .andExpect(jsonPath("$.data.max_mail_mb").value(15))
                .andExpect(jsonPath("$.data.delivery").value("TEAM"))
                .andExpect(jsonPath("$.data.to[0]").value("takim1@example.com"))
                .andExpect(jsonPath("$.data.to_count").value(1))
                .andExpect(jsonPath("$.data.to_dropped").value(0))             // A3: sınır dışında alıcı yok
                .andExpect(jsonPath("$.data.cc_dropped").value(0))
                .andExpect(jsonPath("$.data.pdf_too_large").value(false))
                .andExpect(jsonPath("$.data.recipients_hidden").value(false))
                .andExpect(jsonPath("$.data.file_name").value("mobil-2026-09.pdf"))
                .andExpect(jsonPath("$.data.images_embedded").value(1))
                .andExpect(jsonPath("$.data.html").value(org.hamcrest.Matchers.containsString("data:image/png")));
        verify(sender, never()).send(any(), any(), any(), any());
        verify(sender, never()).send(any(), any(), any(), any(), any());
        // Yönetici alıcılı kanal → EXECUTIVE teslim yolu
        execChannel(30, A);
        report(130, 30, A, null);
        when(sender.preview(any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE))).thenReturn(
                new TechErrorReportSender.Preview("Konu", List.of("ceo@example.com", "cfo@example.com"), List.of("takim1@example.com"),
                        "EXECUTIVE", "<html></html>", "metin", "m.pdf", 0, 0, "EXEC_RECIPIENTS_MISSING",
                        new TechErrorPdfInput.Provenance("Ayşe Yılmaz", Instant.parse("2026-09-03T11:05:00Z"), "Ayşe Yılmaz",
                                Instant.parse("2026-09-03T11:05:00Z"), "approved", "Mehmet PO", Instant.parse("2026-09-06T07:00:00Z")), 999L, 15, 3, 4, true));
        mvc.perform(get("/api/tech-error-reports/reports/130/mail-preview").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery").value("EXECUTIVE"))
                .andExpect(jsonPath("$.data.provenance.approval_state").value("approved"))
                .andExpect(jsonPath("$.data.provenance.approved_by").value("Mehmet PO"))
                .andExpect(jsonPath("$.data.provenance.approved_at").value("2026-09-06T07:00:00Z"))
                .andExpect(jsonPath("$.data.to.length()").value(2))
                .andExpect(jsonPath("$.data.to_dropped").value(3))             // A3: sunucunun saydığı fazla alıcı aynen döner
                .andExpect(jsonPath("$.data.cc_dropped").value(4))             // Bilgi (takım adresi) fazlası da ayrıca
                // Alıcı sorunu `problem`'i alır; PDF sınırı ayrı bayrakta kaybolmaz (inceleme 2026-10-17)
                .andExpect(jsonPath("$.data.problem").value("EXEC_RECIPIENTS_MISSING"))
                .andExpect(jsonPath("$.data.pdf_too_large").value(true))
                .andExpect(jsonPath("$.data.cc[0]").value("takim1@example.com"));
        // Paylaşımla okuyan: adresler gizli, sayılar var
        MockHttpSession g = grantee(B, A, false);
        mvc.perform(get("/api/tech-error-reports/reports/130/mail-preview").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.to").isEmpty())
                .andExpect(jsonPath("$.data.cc").isEmpty())
                .andExpect(jsonPath("$.data.to_count").value(2))
                .andExpect(jsonPath("$.data.cc_count").value(1))
                .andExpect(jsonPath("$.data.recipients_hidden").value(true))
                .andExpect(jsonPath("$.data.provenance.approved_by").value("Mehmet PO"));   // belgenin kimliği gövdede de yazar — gizlenmez
        // Başka takım
        mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(user("USER", B, List.of()))).andExpect(status().isNotFound());
    }

    private static TechErrorReportSender.Preview simplePreview() {
        return new TechErrorReportSender.Preview("Konu", List.of("takim1@example.com"), List.of(), "TEAM", "<html></html>", "metin",
                "m.pdf", 0, 0, null, null, 1L, 15, 0, 0, false);
    }

    @Test
    @DisplayName("PDF + e-posta önizlemesi kişi başı sınır (inceleme 2026-10-18): dakikada " + TechErrorReportController.RENDER_PER_MINUTE
            + " (ikisi ortak, yıl arşivi sığar), aşan 429 RATE_LIMITED + Retry-After; başka kişi etkilenmez; görülemeyen rapor (404) ve PDF sırası dolu (429) hak yemez; pencere geçince açılır")
    void pdfAndPreviewPerUserRate() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(5_000_000_000L);
        java.util.function.LongSupplier saved = controller.renderClock;
        controller.renderClock = clock::get;
        try {
            report(100, 10, A, null);
            report(200, 20, B, null);
            when(sender.preview(any(), any(), any(), any())).thenReturn(simplePreview());
            // 5 kez PDF sırası dolu, sonra beklenmeyen hata (üretim denendi → hak harcandı)
            TechErrorReportService.PdfBusyException busy = new TechErrorReportService.PdfBusyException("PDF sırası dolu");
            when(service.renderPdf(any())).thenThrow(busy, busy, busy, busy, busy, new RuntimeException("render hatası"));
            MockHttpSession me = user("USER", A, List.of());
            me.setAttribute("userId", 501L);
            // Görülemeyen rapor: 404, hak yemez
            for (int i = 0; i < 5; i++) {
                mvc.perform(get("/api/tech-error-reports/reports/200/pdf").session(me)).andExpect(status().isNotFound());
            }
            // PDF sırası dolu (kendi 429'u): üretim yapılmadı → hak geri verilir (yıl arşivinin 3 sn'lik yeniden denemeleri)
            for (int i = 0; i < 5; i++) {
                mvc.perform(get("/api/tech-error-reports/reports/100/pdf").session(me))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("RATE_LIMITED")));
            }
            assertThat(controller.rendering).isEmpty();
            // Önizleme + PDF aynı bütçeden: 19 önizleme + 1 PDF (beklenmeyen hata — üretim denendi, hak harcandı) = tam bütçe
            for (int i = 0; i < TechErrorReportController.RENDER_PER_MINUTE - 1; i++) {
                mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me)).andExpect(status().isOk());
            }
            mvc.perform(get("/api/tech-error-reports/reports/100/pdf").session(me)).andExpect(status().is5xxServerError());
            mvc.perform(get("/api/tech-error-reports/reports/100/pdf").session(me))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.retry_after_seconds").value(60))
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(String.valueOf(TechErrorReportController.RENDER_PER_MINUTE))));
            mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
            // Başka kişi etkilenmez
            MockHttpSession other = user("USER", A, List.of());
            other.setAttribute("userId", 502L);
            mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(other)).andExpect(status().isOk());
            // Pencere geçince açılır; işaret takılı kalmadı
            clock.addAndGet(61_000L);
            mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me)).andExpect(status().isOk());
            assertThat(controller.rendering).isEmpty();
        } finally {
            controller.renderClock = saved;
        }
    }

    @Test
    @DisplayName("PDF + e-posta önizlemesi kişi başına AYNI ANDA TEK: önizleme hazırlanırken aynı kişinin PDF isteği beklemeden 429 RATE_LIMITED + Retry-After 5 (hak yemez); başka kişi geçer; gönderim yolu bu sınıra bağlı değil")
    void pdfAndPreviewOnePerUserAtATime() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(7_000_000_000L);
        java.util.function.LongSupplier saved = controller.renderClock;
        controller.renderClock = clock::get;
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            report(100, 10, A, null);
            when(sender.preview(any(), any(), any(), any())).thenAnswer(inv -> {
                inside.countDown();
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                return simplePreview();
            });
            MockHttpSession me = user("USER", A, List.of());
            me.setAttribute("userId", 601L);
            java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> first = pool.submit(() ->
                    mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me)).andReturn());
            assertThat(inside.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(controller.rendering).contains("u:601");
            // Aynı kişinin ikinci isteği (PDF) beklemeden reddedilir; renderPdf hiç çağrılmaz
            mvc.perform(get("/api/tech-error-reports/reports/100/pdf").session(me))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.retry_after_seconds").value(5))
                    .andExpect(header().string("Retry-After", "5"));
            verify(service, never()).renderPdf(any());
            release.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(controller.rendering).isEmpty();
            // Eşzamanlılık reddi hak yemedi: 1 başarılı + (RENDER_PER_MINUTE - 1) daha geçer
            when(sender.preview(any(), any(), any(), any())).thenReturn(simplePreview());
            for (int i = 0; i < TechErrorReportController.RENDER_PER_MINUTE - 1; i++) {
                mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me)).andExpect(status().isOk());
            }
            mvc.perform(get("/api/tech-error-reports/reports/100/mail-preview").session(me))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            controller.renderClock = saved;
        }
    }

    @Test
    @DisplayName("bozuk şablon (inceleme 2026-10-18): kanalın kendi şablon ekranı hoşgörülü okur (onarılabilsin); ?strict=1 (başka kanaldan şablon al) ve yeni kanalda kopya kaynağı 409 TEMPLATE_UNREADABLE — sistem şablonuna sessizce düşmez, kanal açılmaz")
    void unreadableChannelTemplateIsNotReplacedBySystemDefault() throws Exception {
        TechErrorChannel broken = channel(40, A);
        broken.setName("Bozuk Kanal");
        broken.setTemplateJson("{bozuk");
        TechErrorChannel empty = channel(41, A);
        empty.setTemplateJson("{\"lang\":\"tr\",\"title\":\"x\",\"sections\":[]}");
        MockHttpSession s = user("USER", A, List.of());
        mvc.perform(get("/api/tech-error-reports/channels/40/template").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.sections").isNotEmpty());
        mvc.perform(get("/api/tech-error-reports/channels/40/template").param("strict", "1").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Bozuk Kanal")));
        mvc.perform(get("/api/tech-error-reports/channels/41/template").param("strict", "1").session(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"));
        mvc.perform(get("/api/tech-error-reports/channels/40/template").param("strict", "1").header("X-Lang", "en").session(s))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("couldn't be read")));
        mvc.perform(get("/api/tech-error-reports/channels/10/template").param("strict", "1").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.sections").isNotEmpty());
        // Başka takımın kanalı strict okumada da 404 (varlığı sızmaz)
        mvc.perform(get("/api/tech-error-reports/channels/20/template").param("strict", "1").session(s)).andExpect(status().isNotFound());
        for (long src : new long[] {40L, 41L}) {
            mvc.perform(post("/api/tech-error-reports/channels").session(s).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"team_id\":1,\"name\":\"Yeni\",\"copy_from\":" + src + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"))
                    .andExpect(jsonPath("$.field").value("copy_from"));
        }
        verify(service, never()).createChannel(any(), eq("Yeni"), any(), any(), any(), any());
        // Okunabilir kaynak kopyalanır
        when(service.createChannel(eq(A), eq("Kopya"), any(), any(), eq(50L), any())).thenReturn(channel(42, A));
        mvc.perform(post("/api/tech-error-reports/channels").session(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"Kopya\",\"copy_from\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template_source.kind").value("copy"));
    }

    // ── Şablon önizleme PDF'i (R19, 2026-10-17) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("R19 şablon önizleme PDF'i: okuyan (üye, AUDIT, paylaşımla okuyan) KAYDEDİLMEMİŞ taslağı gönderir → inline PDF, no-store, örnek değerler; başka takım 404; geçersiz taslak 400 template; PDF sırası dolu 429; hiçbir şey yazılmaz")
    void templatePreviewPdf() throws Exception {
        java.util.concurrent.atomic.AtomicReference<TechErrorPdfInput> seen = new java.util.concurrent.atomic.AtomicReference<>();
        when(service.renderPdf(any())).thenAnswer(inv -> {
            java.util.function.Supplier<TechErrorPdfInput> loader = inv.getArgument(0);
            TechErrorPdfInput in = loader.get();
            seen.set(in);
            return new TechErrorReportService.Rendered(in.withoutImages(), "%PDF-1.7 önizleme".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        // Düzenleyicideki taslak: başlığı değiştirilmiş, henüz kaydedilmemiş
        Map<String, Object> draft = new java.util.LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr")));
        draft.put("title", "Taslak Başlık {kanal} {ay} {yil}");
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("template", draft));
        MockHttpSession member = user("USER", A, List.of());
        member.setAttribute("displayName", "Ayşe Yılmaz");
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(member)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith("inline"),
                                org.hamcrest.Matchers.containsString("kanal-10-sablon-onizleme.pdf"))));
        TechErrorPdfInput in = seen.get();
        assertThat(in.sample()).isTrue();
        assertThat(in.title()).isEqualTo("Taslak Başlık Kanal 10 Eylül 2026");          // kaydedilmemiş taslak, önceki ay
        assertThat(in.channelName()).isEqualTo("Kanal 10");
        assertThat(in.teamName()).isEqualTo("Takım 1");
        assertThat(in.preparedBy()).isEqualTo("Ayşe Yılmaz");
        assertThat(in.values().fields()).containsKey("total-tx").containsKey("error-rate");
        // Salt okuyan (AUDIT) ve paylaşımla okuyan da önizler (şablonu okumakla aynı kapı)
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(audit())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(grantee(B, A, false))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        // Başka takımın kanalı: 404 (geçersiz taslakla da — kapsam doğrulamadan önce)
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(user("USER", B, List.of()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"template\":{}}")).andExpect(status().isNotFound());
        // Geçersiz taslak: kayıttaki gibi katı — 400, alan template
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":{\"lang\":\"tr\",\"title\":\"\",\"sections\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("template"));
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(member)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("template"));
        // PDF sırası dolu → 429
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any());
        mvc.perform(post("/api/tech-error-reports/channels/10/template/preview-pdf").session(member)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        // Okuma: hiçbir şey yazılmaz, denetim kaydı yok
        verify(service, never()).saveTemplate(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Autowired TechErrorReportController controller;

    @Test
    @DisplayName("R19 inceleme: şablon önizleme PDF'i kullanıcı başına dakikada 6 — 7. istek 429 RATE_LIMITED + Retry-After, PDF üretilmez; başka kullanıcı etkilenmez; pencere geçince yine açılır; geçersiz taslak (400) hak yemez")
    void templatePreviewPdfRateLimited() throws Exception {
        when(service.renderPdf(any())).thenReturn(new TechErrorReportService.Rendered(null, "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("template", TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"))));
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        java.util.function.LongSupplier saved = controller.previewClock;
        controller.previewClock = now::get;
        try {
            MockHttpSession me = user("USER", A, List.of());
            me.setAttribute("userId", 9_001L);                       // bu teste özel kullanıcı (bağlam testler arasında paylaşılır)
            String url = "/api/tech-error-reports/channels/10/template/preview-pdf";
            // Geçersiz taslak sınırdan önce reddedilir — hak yemez
            mvc.perform(post(url).session(me).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
            for (int i = 0; i < TechErrorReportController.PREVIEW_PER_MINUTE; i++) {
                now.addAndGet(1_000);
                mvc.perform(post(url).session(me).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            }
            now.addAndGet(1_000);
            mvc.perform(post(url).session(me).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("dakikada en çok 6")))
                    .andExpect(jsonPath("$.data.retry_after_seconds").value(54))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "54"));
            verify(service, org.mockito.Mockito.times(TechErrorReportController.PREVIEW_PER_MINUTE)).renderPdf(any());
            // Başka kullanıcı etkilenmez
            MockHttpSession other = user("USER", A, List.of());
            other.setAttribute("userId", 9_002L);
            mvc.perform(post(url).session(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            // İlk isteğin üstünden bir dakika geçince yeniden açılır
            now.addAndGet(54_000);
            mvc.perform(post(url).session(me).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        } finally {
            controller.previewClock = saved;
        }
    }

    // ── Takım erişim paylaşımı (9. sürüm, 2026-10-17) ──────────────────────────────────────────────

    private static MockHttpSession grantee(long ownTeam, long sharedTeam, boolean edit) {
        MockHttpSession s = user("USER", ownTeam, List.of());
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(sharedTeam)));
        if (edit) s.setAttribute(SessionScope.ATTR_GRANT_EDIT, new ArrayList<>(List.of(sharedTeam)));
        return s;
    }

    @Test
    @DisplayName("erişim paylaşımı VIEW: paylaşan takımın kanalı listede (shared_access, can_report / can_edit yok), rapor ve şablon okunur, yazma 403, rapor açma 403")
    void teamAccessGrant_viewReadsOnly() throws Exception {
        report(100, 10, A, null);
        MockHttpSession g = grantee(B, A, false);
        mvc.perform(get("/api/tech-error-reports/overview").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].shared_access").value(true))
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].can_report").value(false))
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].can_edit").value(false))
                .andExpect(jsonPath("$.data.channels[?(@.id == 20)].shared_access").value(false))
                .andExpect(jsonPath("$.data.teams[?(@.id == 1)].can_edit").value(false));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false))
                .andExpect(jsonPath("$.data.can_send").value(false))
                .andExpect(jsonPath("$.data.shared_access").value(true));
        mvc.perform(get("/api/tech-error-reports/channels/10/template").session(g)).andExpect(status().isOk());
        mvc.perform(put("/api/tech-error-reports/reports/100").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4,\"values\":{\"fields\":{}}}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tech-error-reports/reports").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel_id\":10,\"year\":2026,\"month\":8}"))
                .andExpect(status().isForbidden());
        // Paylaşımsız bir takımın kanalı yine görünmez
        MockHttpSession none = user("USER", B, List.of());
        mvc.perform(get("/api/tech-error-reports/reports/100").session(none)).andExpect(status().isNotFound());
        // Kişi verisi paylaşımla görünmez (inceleme 2026-10-17): gönderim alıcıları, yönetici alıcıları, onaylayıcı adları
        TechErrorChannel ec = execChannel(30, A);
        TechErrorReport sent = report(130, 30, A, Instant.parse("2026-10-05T08:00:00Z"));
        sent.setLastSentTo("ceo@example.com, cfo@example.com");
        when(service.periodRows(any(), any())).thenReturn(Map.of(30L, TechErrorReportController.rowOf(sent)));
        mvc.perform(get("/api/tech-error-reports/overview").session(g))
                .andExpect(jsonPath("$.data.channels[?(@.id == 30)].executive_emails").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.channels[?(@.id == 30)].executive_count").value(2))
                .andExpect(jsonPath("$.data.channels[?(@.id == 30)].period_report.last_sent_to").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.channels[?(@.id == 30)].period_report.last_sent_count").value(2));
        mvc.perform(get("/api/tech-error-reports/reports/130").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.last_sent_to").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.report.last_sent_count").value(2))
                .andExpect(jsonPath("$.data.approval_detail.executive_emails").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.approval_detail.approvers[*].name").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.approval_detail.approver_count").isNumber());
        // Takımın kendi üyesi aynı verileri görür
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.report.last_sent_to").value("ceo@example.com, cfo@example.com"))
                .andExpect(jsonPath("$.data.approval_detail.executive_emails.length()").value(2));
        // Üye + paylaşım: shared_access değil (kendi kapsamı önde)
        MockHttpSession memberToo = user("USER", A, List.of());
        memberToo.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(A)));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(memberToo))
                .andExpect(jsonPath("$.data.shared_access").value(false))
                .andExpect(jsonPath("$.data.can_send").value(true));
        // DELETE düzeyi bile silmez: silme takımın kendi kapsamı
        MockHttpSession del = grantee(B, A, true);
        del.setAttribute(SessionScope.ATTR_GRANT_DELETE, new ArrayList<>(List.of(A)));
        mvc.perform(delete("/api/tech-error-reports/reports/100").session(del)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tech-error-reports/channels/10").session(del)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("erişim paylaşımı EDIT: rapor içeriği yazılır (can_report / can_edit), ama gönderim, onay isteği, silme, kanal, şablon ve yönetici alıcıları 403 (can_send false)")
    void teamAccessGrant_editWritesContentNeverSends() throws Exception {
        report(100, 10, A, null);
        MockHttpSession g = grantee(B, A, true);
        mvc.perform(get("/api/tech-error-reports/overview").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].can_report").value(true))
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].can_edit").value(false))
                .andExpect(jsonPath("$.data.channels[?(@.id == 10)].shared_access").value(true));
        mvc.perform(get("/api/tech-error-reports/reports/100").session(g))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.can_send").value(false))
                .andExpect(jsonPath("$.data.can_delete").value(false));
        TechErrorReport saved = reports.stream().filter(x -> x.getId() == 100L).findFirst().orElseThrow();
        saved.setVersion(5L);
        when(service.saveValues(eq(100L), eq(4L), any(), eq(50L), eq(false)))
                .thenReturn(new TechErrorReportService.SaveOutcome(saved, false));
        mvc.perform(put("/api/tech-error-reports/reports/100").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4,\"values\":{\"fields\":{}}}"))
                .andExpect(status().isOk());
        // Rapor açma içerik yazmadır: EDIT paylaşımı açar (inceleme 2026-10-17 — writableChannel kanal kapsamıydı)
        TechErrorReport created = report(300, 10, A, null);
        when(service.createReport(any(), eq(2026), eq(8), any())).thenReturn(created);
        mvc.perform(post("/api/tech-error-reports/reports").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel_id\":10,\"year\":2026,\"month\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(300));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(g)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tech-error-reports/reports/100/approval/request").session(g)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/tech-error-reports/reports/100").session(g)).andExpect(status().isForbidden());
        mvc.perform(put("/api/tech-error-reports/channels/10").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/tech-error-reports/channels/10/template").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"template\":" + TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")) + "}"))
                .andExpect(status().isForbidden());
        // Kanal açma: takım alanı altında hata (mevcut sözleşme — 400 team_id), paylaşım takım için kanal açtırmaz
        mvc.perform(post("/api/tech-error-reports/channels").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"Yeni\",\"lang\":\"tr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("team_id"));
        verify(sender, never()).send(any(), any(), any(), any());
    }

    // ── Kendine test postası (R13 / B5) + alıcı özeti (B6), 2026-10-17 ───────────────────────────

    private static MockHttpSession withUid(MockHttpSession s, long uid) {
        s.setAttribute("userId", uid);
        return s;
    }

    @Test
    @DisplayName("bana test gönder: okuma kapsamı yeter (paylaşımla okuyan da) → 200 sent_to / status + denetim; teslim yolu kanala göre; dakikada 3 → 4. çağrı 429; adresi olmayan 409 NO_EMAIL; başka takım 404; SKIPPED 409, FAILED 502; gönderim yolu hiç çağrılmaz")
    void mailPreviewTest() throws Exception {
        report(100, 10, A, null);
        when(service.userEmail(50L)).thenReturn("u@example.com");
        when(sender.sendTest(any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM), eq("u@example.com"), anyString())).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("u@example.com"), 1234, "[TEST] Konu"));
        // 1) paylaşımla okuyan (yalnız VIEW): posta yalnız kendine gider — izinli
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(grantee(B, A, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent_to").value("u@example.com"))
                .andExpect(jsonPath("$.data.status").value("SENT"));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_TEST_MAIL"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("TECH_ERROR_REPORT"), eq("100"), anyString());
        // 2) yönetici alıcılı kanal → EXECUTIVE teslim yolu (dipnot gerçek posta gibi)
        execChannel(30, A);
        report(130, 30, A, null);
        when(sender.sendTest(any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE), eq("u@example.com"), anyString())).thenReturn(
                new TechErrorReportSender.Result("QUEUED", "QUEUED_RETRY 421", List.of("u@example.com"), 1234, "[TEST] Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/130/mail-preview/test").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        // 3) üçüncü deneme hâlâ sınır içinde; 4) dördüncü 429 (aynı dakika — service.now() sabit)
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(user("USER", A, List.of())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(user("USER", A, List.of())))
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").isNumber());
        verify(sender, org.mockito.Mockito.times(3)).sendTest(any(), any(), any(), any(), eq("u@example.com"), anyString());
        verify(auditService, org.mockito.Mockito.times(3)).recordAction(eq("TECH_ERROR_REPORT_TEST_MAIL"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), anyString(), anyString());
        // Adresi olmayan hesap: 409 NO_EMAIL (sınırdan ÖNCE; sayılmaz, hiçbir şey gönderilmez)
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(audit()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_EMAIL"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("e-posta adresi yok")));
        // Başka takım: 404 (varlık sızmaz — sınırdan da önce)
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(user("USER", B, List.of()))).andExpect(status().isNotFound());
        // Sonuç eşlemesi (yeni kullanıcı — sınır kişi başına): SKIPPED 409 MAIL_DISABLED, FAILED 502 MAIL_FAILED (SMTP metni dönmez)
        when(service.userEmail(51L)).thenReturn("v@example.com");
        when(sender.sendTest(any(), any(), any(), any(), eq("v@example.com"), anyString()))
                .thenReturn(new TechErrorReportSender.Result("SKIPPED", "SKIPPED_DISABLED", List.of("v@example.com"), 1234, "[TEST] Konu"))
                .thenReturn(new TechErrorReportSender.Result("FAILED", "FAILED: 554", List.of("v@example.com"), 1234, "[TEST] Konu"))
                .thenThrow(new CodedConflictException("PDF_TOO_LARGE", "büyük"));
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 51L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAIL_DISABLED"))
                .andExpect(jsonPath("$.data.sent_to").value("v@example.com"));
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 51L)))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("MAIL_FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("554"))));
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 51L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PDF_TOO_LARGE"));
        // Gönderim yolu hiç çalışmadı: rapor "gönderildi" olmaz
        verify(sender, never()).send(any(), any(), any(), any());
        verify(sender, never()).send(any(), any(), any(), any(), any());
        // Okuma izni olmayan 403
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(false);
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(user("USER", A, List.of()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("bana test gönder (inceleme 2026-10-17): posta denenmeden biten deneme hakkı harcamaz — 3× PDF sırası dolu (429) + PDF sınırı (409) sonrası 5. çağrı yine gider; posta denenen deneme harcar (3 gönderimden sonra 429 RATE_LIMITED); denetim sebebi kod (PDF_BUSY / PDF_TOO_LARGE), sınıf adı değil")
    void mailPreviewTest_failedAttemptsRefundQuota() throws Exception {
        report(100, 10, A, null);
        when(service.userEmail(52L)).thenReturn("w@example.com");
        when(sender.sendTest(any(), any(), any(), any(), eq("w@example.com"), anyString()))
                .thenThrow(new TechErrorReportService.PdfBusyException("meşgul"))
                .thenThrow(new TechErrorReportService.PdfBusyException("meşgul"))
                .thenThrow(new TechErrorReportService.PdfBusyException("meşgul"))
                .thenThrow(new CodedConflictException("PDF_TOO_LARGE", "büyük"))
                .thenReturn(new TechErrorReportSender.Result("SENT", "SENT", List.of("w@example.com"), 1234, "[TEST] Konu"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 52L)))
                    .andExpect(status().is(429))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("RATE_LIMITED")));
        }
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 52L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PDF_TOO_LARGE"));
        // Dört başarısız deneme hak harcamadı: sıradaki üç gönderim gider, dördüncüsü sınıra takılır
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 52L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SENT"));
        }
        mvc.perform(post("/api/tech-error-reports/reports/100/mail-preview/test").session(withUid(user("USER", A, List.of()), 52L)))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(sender, org.mockito.Mockito.times(7)).sendTest(any(), any(), any(), any(), eq("w@example.com"), anyString());
        // Denetim sebebi kod (decideApproval gibi): PDF_BUSY ×3, PDF_TOO_LARGE ×1 — sınıf adı yazılmaz
        verify(auditService, org.mockito.Mockito.times(3)).recordAction(eq("TECH_ERROR_REPORT_TEST_MAIL"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("100"),
                org.mockito.ArgumentMatchers.<String>argThat(s -> s != null && s.contains("PDF_BUSY")));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_TEST_MAIL"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_REPORT"), eq("100"),
                org.mockito.ArgumentMatchers.<String>argThat(s -> s != null && s.contains("PDF_TOO_LARGE")));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_REPORT_TEST_MAIL"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(),
                org.mockito.ArgumentMatchers.<String>argThat(s -> s != null && (s.contains("CodedConflictException") || s.contains("PdfBusyException"))));
    }

    @Test
    @DisplayName("alıcı özeti (B6): rapor ayrıntısı team_email_count (teslim edilebilir adres sayısı; yok → 0 ve team_email_set false) ve approval_detail.required / executive_count taşır — paylaşımla okuyan da sayıları görür")
    void reportDetailRecipientCounts() throws Exception {
        report(100, 10, A, null);
        when(teams.findById(eq(A))).thenAnswer(inv -> {
            Team t = team(A);
            t.setEmail("takim1@example.com; ikinci@example.com, bozuk-adres");
            return Optional.of(t);
        });
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_email_set").value(true))
                .andExpect(jsonPath("$.data.team_email_count").value(2))
                .andExpect(jsonPath("$.data.team_email_dropped").value(0))
                .andExpect(jsonPath("$.data.approval_detail.required").value(false))
                .andExpect(jsonPath("$.data.approval_detail.executive_count").value(0));
        // 20'den fazla teslim edilebilir adres: sayı postanın gittiği 20, fazlası ayrı (inceleme 2026-10-17 — "20" fazlalığı gizliyordu)
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 23; i++) many.append("u").append(i).append("@example.com; ");
        when(teams.findById(eq(A))).thenAnswer(inv -> {
            Team t = team(A);
            t.setEmail(many.toString());
            return Optional.of(t);
        });
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_email_count").value(20))
                .andExpect(jsonPath("$.data.team_email_dropped").value(3));
        when(teams.findById(eq(A))).thenAnswer(inv -> {
            Team t = team(A);
            t.setEmail("takim1@example.com; ikinci@example.com, bozuk-adres");
            return Optional.of(t);
        });
        // Paylaşımla okuyan: sayı var, adres yok
        mvc.perform(get("/api/tech-error-reports/reports/100").session(grantee(B, A, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_email_count").value(2))
                .andExpect(jsonPath("$.data.shared_access").value(true));
        // Adres yok → 0; yönetici alıcılı kanal → required + sayı
        when(teams.findById(eq(A))).thenAnswer(inv -> {
            Team t = team(A);
            t.setEmail("   ");
            return Optional.of(t);
        });
        execChannel(30, A);
        report(130, 30, A, null);
        mvc.perform(get("/api/tech-error-reports/reports/130").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_email_set").value(false))
                .andExpect(jsonPath("$.data.team_email_count").value(0))
                .andExpect(jsonPath("$.data.approval_detail.required").value(true))
                .andExpect(jsonPath("$.data.approval_detail.executive_count").value(2))
                .andExpect(jsonPath("$.data.approval_detail.approval.state").value("none"));
    }

    @Test
    @DisplayName("erişim paylaşımı: modül görünürlüğü (S6) paylaşılan takıma da uygulanır — kapalı takımın kanalı paylaşımla da görünmez")
    void teamAccessGrant_moduleVisibilityApplies() throws Exception {
        report(100, 10, A, null);
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            MockHttpSession g = grantee(B, A, true);
            mvc.perform(get("/api/tech-error-reports/overview").session(g))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.channels[?(@.id == 10)]").isEmpty());
            mvc.perform(get("/api/tech-error-reports/reports/100").session(g)).andExpect(status().isNotFound());
            mvc.perform(put("/api/tech-error-reports/reports/100").session(g)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4,\"values\":{\"fields\":{}}}"))
                    .andExpect(status().isNotFound());
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        }
    }

    // ── Kanal sırası toplu ucu + dönem özeti (R14 inceleme, 2026-10-17) ─────────────────────────────


    @Test
    @DisplayName("toplu kanal sırası: üye 200 (yeni sıra 1…n) + TEK denetim (önceki / yeni dizilim kimliklerle); değişiklik yoksa"
            + " denetim yok; gövde liste değilse ya da kimlik bozuksa 400 ids (servise gitmez); servisin 400'ü aynen")
    void channelOrder() throws Exception {
        channel(11, A);
        when(service.reorderChannels(eq(A), eq(List.of(11L, 10L)), eq(50L)))
                .thenReturn(new TechErrorReportService.ReorderResult(List.of(10L, 11L), List.of(11L, 10L), 2));
        mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[11,10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1))
                .andExpect(jsonPath("$.data.changed").value(2))
                .andExpect(jsonPath("$.data.channels[0].id").value(11))
                .andExpect(jsonPath("$.data.channels[0].sort_order").value(1))
                .andExpect(jsonPath("$.data.channels[1].id").value(10))
                .andExpect(jsonPath("$.data.channels[1].sort_order").value(2));
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService, org.mockito.Mockito.times(1)).recordAction(eq("TECH_ERROR_CHANNEL_REORDER"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"), detail.capture());
        assertThat(detail.getValue()).contains("\"before\":\"10,11\"").contains("\"after\":\"11,10\"").contains("\"team_id\":1");
        // Değişiklik yok → denetim yazılmaz
        when(service.reorderChannels(eq(A), eq(List.of(10L, 11L)), eq(50L)))
                .thenReturn(new TechErrorReportService.ReorderResult(List.of(10L, 11L), List.of(10L, 11L), 0));
        mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"10\",\"11\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(0));
        verify(auditService, org.mockito.Mockito.times(1)).recordAction(eq("TECH_ERROR_CHANNEL_REORDER"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
        // Bozuk gövde: servis çağrılmaz
        for (String bad : List.of("{}", "{\"ids\":\"10,11\"}", "{\"ids\":[10,\"x\"]}", "{\"ids\":[10,-3]}")) {
            mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(user("USER", A, List.of()))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("ids"));
        }
        // Eksik / fazla / başka takımın kimliği: servis reddeder → 400 ids
        when(service.reorderChannels(eq(A), eq(List.of(10L, 20L)), eq(50L)))
                .thenThrow(new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("ids", "Kanal listesi siz sıralarken değişti."));
        mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[10,20]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("ids"));
        verify(service, org.mockito.Mockito.times(3)).reorderChannels(any(), any(), any());
    }

    @Test
    @DisplayName("toplu kanal sırası kapısı: başka takım 404 TEAM_NOT_FOUND; yalnız paylaşımla (EDIT) yazabilen 403; AUDIT 403;"
            + " global yönetici her takım")
    void channelOrderScope() throws Exception {
        String body = "{\"ids\":[10]}";
        mvc.perform(put("/api/tech-error-reports/teams/2/channel-order").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(grantee(B, A, true))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/tech-error-reports/teams/1/channel-order").session(audit())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(service, never()).reorderChannels(any(), any(), any());
        when(service.reorderChannels(eq(B), eq(List.of(20L)), eq(1L)))
                .thenReturn(new TechErrorReportService.ReorderResult(List.of(20L), List.of(20L), 1));
        mvc.perform(put("/api/tech-error-reports/teams/2/channel-order").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[20]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("dönem özeti: okuma kapsamının takımları sayılır (üye kendi takımı, AUDIT hepsi); kapsam başına önbellek (aynı"
            + " kapsamın ikinci okuması servise gitmez); modül kapalı takımın üyesi 403 (genel bakışla aynı)")
    void periodSummary() throws Exception {
        controller.resetSummaryCache();
        when(service.periodSummary(any(), eq(YearMonth.of(2026, 9))))
                .thenReturn(new TechErrorReportService.PeriodSummary(4, 1, 2, 1, 3));
        mvc.perform(get("/api/tech-error-reports/period-summary").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period.year").value(2026))
                .andExpect(jsonPath("$.data.period.month").value(9))
                .andExpect(jsonPath("$.data.active").value(4))
                .andExpect(jsonPath("$.data.sent").value(1))
                .andExpect(jsonPath("$.data.open").value(2))
                .andExpect(jsonPath("$.data.missing").value(1))
                .andExpect(jsonPath("$.data.inactive").value(3))
                .andExpect(jsonPath("$.data.channels").doesNotExist());
        verify(service).periodSummary(eq(new java.util.LinkedHashSet<>(List.of(A))), eq(YearMonth.of(2026, 9)));
        // Aynı kapsam: önbellekten (servis ikinci kez çağrılmaz, kuyruk sonuçlandırılmaz)
        mvc.perform(get("/api/tech-error-reports/period-summary").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missing").value(1));
        verify(service, org.mockito.Mockito.times(1)).periodSummary(any(), any());
        verify(service, never()).reconcileQueued(any());
        // Kurum geneli görüntüleyici: ayrı kapsam anahtarı, tüm takımlar
        mvc.perform(get("/api/tech-error-reports/period-summary").session(audit())).andExpect(status().isOk());
        verify(service).periodSummary(eq(new java.util.LinkedHashSet<>(List.of(A, B))), eq(YearMonth.of(2026, 9)));
        // Modül görünürlüğü (S6): takımı kapalı üye 403
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            mvc.perform(get("/api/tech-error-reports/period-summary").session(user("USER", A, List.of()))).andExpect(status().isForbidden());
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
            controller.resetSummaryCache();
        }
    }

    // ── R18 (2026-10-18): gönderime ek Bilgi (C13) + kanal gönderim günlüğü (E22) ─────────────────

    @MockitoBean com.sitemonitor.service.techerror.TechErrorSendLogService sendLogs;

    @Test
    @DisplayName("C13 ek Bilgi: gövdede extra_cc varsa 6 argümanlı gönderim (takım yolu TEAM, onay akışında EXECUTIVE), yanıt cc taşır, denetim yalnız sayı; geçersiz giriş 400 VALIDATION_FAILED alan extra_cc, denetim yok; boş extra_cc = eski çağrı; dizi kabul, sayı türü 400; paylaşımla düzenleyen 403")
    void sendWithExtraCc() throws Exception {
        report(100, 10, A, null);
        MockHttpSession s = user("USER", A, List.of());
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM), eq("ek@example.com, iki@example.com"))).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("takim1@example.com"), 1234, "Konu", List.of("ek@example.com", "iki@example.com")));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":\"ek@example.com, iki@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SENT"))
                .andExpect(jsonPath("$.data.cc.length()").value(2))
                .andExpect(jsonPath("$.data.cc[0]").value("ek@example.com"));
        verify(sender, never()).send(any(), any(), any(), any());
        org.mockito.ArgumentCaptor<String> audit = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_SEND"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("TECH_ERROR_REPORT"), eq("100"), audit.capture());
        org.assertj.core.api.Assertions.assertThat(audit.getValue()).doesNotContain("ek@example.com");      // adres denetime yazılmaz

        // Dizi de kabul edilir (öğeler virgülle birleşir)
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM), eq("a@example.com, b@example.com"))).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("takim1@example.com"), 1, "Konu", List.of("a@example.com", "b@example.com")));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":[\"a@example.com\",\"b@example.com\"]}"))
                .andExpect(status().isOk());

        // Geçersiz adres: sunucunun alan hatası (gönderici doğrular — talepten önce)
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM), eq("bozuk")))
                .thenThrow(new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("extra_cc", "«bozuk» geçerli bir e-posta adresi değil."));
        org.mockito.Mockito.clearInvocations(auditService);
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":\"bozuk\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("extra_cc"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bozuk")));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_REPORT_SEND"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                any(), any(), anyString());
        // Sayı türü: 400 extra_cc, gönderim denenmez
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("extra_cc"));

        // Boş extra_cc = ek yok → eski 4 argümanlı çağrı (byte-identical yol)
        when(sender.send(any(), any(), any(), any())).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("takim1@example.com"), 1234, "Konu"));
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cc.length()").value(0));
        verify(sender).send(any(), any(), any(), any());

        // Onay akışlı kanal: onaylı rapor EXECUTIVE yoluyla, ek Bilgi ile
        execChannel(30, A);
        TechErrorReport approved = report(130, 30, A, null);
        approved.setApprovalState("APPROVED");
        approved.setApprovalRev(0L);
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE), eq("ek@example.com"))).thenReturn(
                new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 1, "Konu", List.of("takim1@example.com", "ek@example.com")));
        mvc.perform(post("/api/tech-error-reports/reports/130/send").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":\"ek@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cc[1]").value("ek@example.com"));

        // Paylaşımla düzenleyen gönderemez (ek Bilgi de)
        mvc.perform(post("/api/tech-error-reports/reports/100/send").session(grantee(B, A, true))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extra_cc\":\"ek@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E22 kanal gönderim günlüğü: takımın okuyanı alıcıları görür; ham durum metni HİÇ KİMSEYE dönmez (yalnız sebep kodu — inceleme 2026-10-18); sığmayan adres sayısı to_more; yalnız paylaşımla okuyan yalnız sayıları (to / cc null); AUDIT okur; başka takım 404 (servis çağrılmaz); sayfa / boyut / süzgeç servise geçer")
    void sendLog() throws Exception {
        report(100, 10, A, null);
        var row = new com.sitemonitor.service.techerror.TechErrorSendLogService.Row(77L, "2026-10-13T08:00:00Z", "TECH_ERROR_REPORT", "report",
                "TEAM", "failed", "SMTP_FAILED", 100L, 2026, 9,
                List.of("takim1@example.com"), List.of("ek@example.com"), 4, 1, 3, 0);
        when(sendLogs.page(eq(10L), any(), any(), any(), any())).thenReturn(
                new com.sitemonitor.service.techerror.TechErrorSendLogService.Page(List.of(row), 31, 1, 25, "report", "failed"));
        mvc.perform(get("/api/tech-error-reports/channels/10/send-log?page=1&size=25&kind=report&status=failed").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(31))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(25))
                .andExpect(jsonPath("$.data.total_pages").value(2))
                .andExpect(jsonPath("$.data.recipients_hidden").value(false))
                .andExpect(jsonPath("$.data.rows[0].id").value(77))
                .andExpect(jsonPath("$.data.rows[0].kind").value("report"))
                .andExpect(jsonPath("$.data.rows[0].role").value("TEAM"))
                .andExpect(jsonPath("$.data.rows[0].status").value("failed"))
                .andExpect(jsonPath("$.data.rows[0].reason").value("SMTP_FAILED"))
                .andExpect(jsonPath("$.data.rows[0].status_text").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("550"))))
                .andExpect(jsonPath("$.data.rows[0].year").value(2026))
                .andExpect(jsonPath("$.data.rows[0].month").value(9))
                .andExpect(jsonPath("$.data.rows[0].to[0]").value("takim1@example.com"))
                .andExpect(jsonPath("$.data.rows[0].to.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].to_more").value(3))
                .andExpect(jsonPath("$.data.rows[0].cc_more").value(0))
                .andExpect(jsonPath("$.data.rows[0].cc[0]").value("ek@example.com"))
                .andExpect(jsonPath("$.data.rows[0].to_count").value(4));
        verify(sendLogs).page(10L, "report", "failed", 1, 25);
        // Yalnız paylaşımla okuyan: kişi verisi yok, sayılar ve sınıf kalır
        mvc.perform(get("/api/tech-error-reports/channels/10/send-log").session(grantee(B, A, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients_hidden").value(true))
                .andExpect(jsonPath("$.data.rows[0].to").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.rows[0].cc").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.rows[0].status_text").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].reason").value("SMTP_FAILED"))      // kod kişi verisi taşımaz
                .andExpect(jsonPath("$.data.rows[0].to_more").value(0))
                .andExpect(jsonPath("$.data.rows[0].to_count").value(4))
                .andExpect(jsonPath("$.data.rows[0].cc_count").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("failed"));
        // AUDIT okur (adresleri görür — paylaşım değil, kurum geneli görüntüleyici)
        mvc.perform(get("/api/tech-error-reports/channels/10/send-log").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients_hidden").value(false));
        // Başka takım: 404 (varlığı sızmaz), servis çağrılmaz
        org.mockito.Mockito.clearInvocations(sendLogs);
        mvc.perform(get("/api/tech-error-reports/channels/10/send-log").session(user("USER", B, List.of())))
                .andExpect(status().isNotFound());
        verify(sendLogs, never()).page(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R18 geçmiş: yalnız paylaşımla okuyan gönderim olayının alıcı / ek Bilgi ADRESLERİNİ görmez (recipient_count / extra_cc_count kalır); takımın okuyanı görür")
    void historyMasksPeopleForGrantOnly() throws Exception {
        report(100, 10, A, null);
        when(history.reportHistory(eq(100L), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.sitemonitor.service.techerror.TechErrorHistoryService.HistoryPage(List.of(
                        event(9, "SEND", 7L, "Ayşe", "{\"state\":\"SENT\",\"recipient_count\":1,\"recipients\":[\"takim1@example.com\"],\"extra_cc\":[\"ek@example.com\",\"iki@example.com\"]}")),
                        false, Instant.parse("2026-10-01T08:00:00Z"), false));
        mvc.perform(get("/api/tech-error-reports/reports/100/history").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].detail.recipients[0]").value("takim1@example.com"))
                .andExpect(jsonPath("$.data.events[0].detail.extra_cc.length()").value(2));
        mvc.perform(get("/api/tech-error-reports/reports/100/history").session(grantee(B, A, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].detail.recipients").doesNotExist())
                .andExpect(jsonPath("$.data.events[0].detail.extra_cc").doesNotExist())
                .andExpect(jsonPath("$.data.events[0].detail.recipient_count").value(1))
                .andExpect(jsonPath("$.data.events[0].detail.extra_cc_count").value(2))
                .andExpect(jsonPath("$.data.events[0].detail.state").value("SENT"));
        org.assertj.core.api.Assertions.assertThat(TechErrorReportController.maskHistoryPeople(Map.of("state", "SENT"))).isEqualTo(Map.of("state", "SENT"));
        org.assertj.core.api.Assertions.assertThat(TechErrorReportController.extraCcOf(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(TechErrorReportController.extraCcOf(Map.of("extra_cc", ""))).isNull();
    }

    // ── Kanallar arası analiz (2026-10-18, R22) ──────────────────────────────────────────────────

    private static com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result analyticsResult() {
        java.util.List<Double> tx = new ArrayList<>(java.util.Collections.nCopies(12, (Double) null));
        tx.set(11, 1_250_000d);
        java.util.List<com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef> reps =
                new ArrayList<>(java.util.Collections.nCopies(12, (com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef) null));
        reps.set(11, new com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef(100L, true));
        java.util.List<YearMonth> months = new ArrayList<>();
        for (int i = 11; i >= 0; i--) months.add(YearMonth.of(2026, 9).minusMonths(i));
        return new com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result(months, 12,
                List.of(new com.sitemonitor.service.techerror.TechErrorAnalyticsService.FieldRef("total-tx", "Toplam işlem adedi", "number")),
                List.of(new com.sitemonitor.service.techerror.TechErrorAnalyticsService.FieldOption("total-tx", "Toplam işlem adedi", "number", 1)),
                List.of(new com.sitemonitor.service.techerror.TechErrorAnalyticsService.ChannelSeries(10L, "Kanal 10", A,
                        Map.of("total-tx", tx), reps)), 1, false, List.of(A));
    }

    private static com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result analyticsFallbackResult() {
        var base = analyticsResult();
        java.util.List<com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef> reps =
                new ArrayList<>(java.util.Collections.nCopies(12, (com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef) null));
        reps.set(11, new com.sitemonitor.service.techerror.TechErrorAnalyticsService.ReportRef(100L, false, true));
        var ch = base.channels().get(0);
        return new com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result(base.months(), 12,
                List.of(new com.sitemonitor.service.techerror.TechErrorAnalyticsService.FieldRef("total-tx", "Toplam işlem adedi", "number", List.of(20L))),
                base.available(),
                List.of(new com.sitemonitor.service.techerror.TechErrorAnalyticsService.ChannelSeries(ch.id(), ch.name(), ch.teamId(), ch.values(), reps)),
                1, false, List.of(A), List.of("gone-field"), true);
    }

    @SuppressWarnings("unchecked")
    private java.util.Collection<Long> analyticsTeams() {
        org.mockito.ArgumentCaptor<java.util.Collection<Long>> c = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(analytics, org.mockito.Mockito.atLeastOnce()).analytics(c.capture(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        return c.getValue();
    }

    @Test
    @DisplayName("analiz: üye kendi takımıyla, varsayılan 12 ay ve alan istenmeden; yanıtta boş ay null (0 değil), rapor kimliği + gönderildi, takım adı")
    void analytics_memberScopeAndShape() throws Exception {
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(analyticsResult());
        mvc.perform(get("/api/tech-error-reports/analytics").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team").value("all"))
                .andExpect(jsonPath("$.data.team_name").doesNotExist())
                .andExpect(jsonPath("$.data.months.length()").value(12))
                .andExpect(jsonPath("$.data.months[11].month").value(9))
                .andExpect(jsonPath("$.data.end.year").value(2026))
                .andExpect(jsonPath("$.data.fields[0].id").value("total-tx"))
                .andExpect(jsonPath("$.data.available_fields[0].channels").value(1))
                .andExpect(jsonPath("$.data.channels[0].team_name").value("Takım 1"))
                .andExpect(jsonPath("$.data.channels[0].points['total-tx'][11]").value(1_250_000d))
                .andExpect(jsonPath("$.data.channels[0].points['total-tx'][0]").doesNotExist())
                .andExpect(jsonPath("$.data.channels[0].reports[11].id").value(100))
                .andExpect(jsonPath("$.data.channels[0].reports[11].sent").value(true))
                .andExpect(jsonPath("$.data.channels[0].reports[0]").doesNotExist())
                .andExpect(jsonPath("$.data.teams[0].name").value("Takım 1"))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.channels_truncated").value(false))
                .andExpect(jsonPath("$.data.inactive_omitted").value(0))
                .andExpect(jsonPath("$.data.max_fields").value(4));
        verify(analytics).analytics(eq(java.util.Set.of(A)), eq(null), eq(12), eq(List.of()), eq(YearMonth.of(2026, 9)), any(), eq(false));
    }

    @Test
    @DisplayName("analiz (2026-10-18, pasif kanallar): kanal satırı active taşır — aktif true, penceresinde raporu olan pasif false (aktiflerin ardında); sığmayan pasifler ayrı işaret (channels_truncated + inactive_omitted), truncated yalnız aylar için")
    void analytics_channelActiveFlag() throws Exception {
        var base = analyticsResult();
        var ch = base.channels().get(0);
        var passive = new com.sitemonitor.service.techerror.TechErrorAnalyticsService.ChannelSeries(11L, "Eski Kanal", A, false,
                ch.values(), ch.reports());
        var res = new com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result(base.months(), 12, base.fields(),
                base.available(), List.of(ch, passive), 5, false, List.of(A), List.of(), false, true, 3);
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(res);
        mvc.perform(get("/api/tech-error-reports/analytics").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                .andExpect(jsonPath("$.data.channels[0].id").value(10))
                .andExpect(jsonPath("$.data.channels[0].active").value(true))
                .andExpect(jsonPath("$.data.channels[1].id").value(11))
                .andExpect(jsonPath("$.data.channels[1].name").value("Eski Kanal"))
                .andExpect(jsonPath("$.data.channels[1].active").value(false))
                .andExpect(jsonPath("$.data.channels[1].points['total-tx'][11]").value(1_250_000d))
                .andExpect(jsonPath("$.data.channel_count").value(5))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.channels_truncated").value(true))
                .andExpect(jsonPath("$.data.inactive_omitted").value(3))
                .andExpect(jsonPath("$.data.active_count").value(2))       // 5 kanal − 3 dışarıda kalan pasif
                .andExpect(jsonPath("$.data.active_omitted").value(0));
    }

    @Test
    @DisplayName("analiz: takım süzgeci yalnız okunabilen takım (başka takım / bozuk kimlik 404); AUDIT her takımı okur; alan ve ay parametreleri iletilir")
    void analytics_teamFilter() throws Exception {
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(analyticsResult());
        MockHttpSession s = user("USER", A, List.of());
        mvc.perform(get("/api/tech-error-reports/analytics").param("team", "2").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/analytics").param("team", "abc").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/analytics").param("team", "1").param("months", "24")
                        .param("fields", "error-rate, total-tx,error-rate").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team").value("1"))
                .andExpect(jsonPath("$.data.team_name").value("Takım 1"));
        verify(analytics).analytics(eq(java.util.Set.of(A)), eq(A), eq(24), eq(List.of("error-rate", "total-tx")), any(), any(), eq(false));
        org.mockito.Mockito.clearInvocations(analytics);
        // Seçili takımın aktif kanalı yoksa da takım seçicide kalır (sonuçta yalnız A'nın kanalı var); adı da yanıtta
        // (inceleme 2026-10-18, 2. tur — seçici çıplak "#kimlik" yazmasın)
        mvc.perform(get("/api/tech-error-reports/analytics").param("team", "2").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_name").value("Takım 2"))
                .andExpect(jsonPath("$.data.teams.length()").value(2))
                .andExpect(jsonPath("$.data.teams[1].id").value(2));
        assertThat(analyticsTeams()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    @DisplayName("analiz parametreleri: ay yalnız 6 / 12 / 24, en çok 4 alan, bozuk alan kimliği → 400 VALIDATION_FAILED (servise gitmez)")
    void analytics_paramValidation() throws Exception {
        MockHttpSession s = user("USER", A, List.of());
        mvc.perform(get("/api/tech-error-reports/analytics").param("months", "7").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("months"));
        mvc.perform(get("/api/tech-error-reports/analytics").param("months", "abc").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("months"));
        mvc.perform(get("/api/tech-error-reports/analytics").param("fields", "a,b,c,d,e").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("fields"));
        mvc.perform(get("/api/tech-error-reports/analytics").param("fields", "Toplam İşlem").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("fields"));
        mvc.perform(get("/api/tech-error-reports/analytics").param("fields", "x".repeat(500)).session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("fields"));
        verify(analytics, never()).analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("analiz: okuma izni yoksa 403; modül görünürlüğü (S6) kapalı takımın üyesi 403, açık takımın üyesi yalnız kendi takımı")
    void analytics_permissionAndModuleVisibility() throws Exception {
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(analyticsResult());
        MockHttpSession noRead = user("USER", A, List.of());
        noRead.setAttribute("systemRole", "NONE");
        when(permissions.allows(org.mockito.ArgumentMatchers.<HttpSession>argThat(x -> x != null && "NONE".equals(x.getAttribute("systemRole"))),
                eq("tech_error_reports.read"), eq("view"))).thenReturn(false);
        mvc.perform(get("/api/tech-error-reports/analytics").session(noRead)).andExpect(status().isForbidden());
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            mvc.perform(get("/api/tech-error-reports/analytics").session(user("USER", A, List.of()))).andExpect(status().isForbidden());
            mvc.perform(get("/api/tech-error-reports/analytics").session(user("USER", B, List.of()))).andExpect(status().isOk());
            assertThat(analyticsTeams()).containsExactly(B);
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        }
    }

    @Test
    @DisplayName("analiz + erişim paylaşımı: VIEW paylaşımı paylaşan takımı okur (süzgeçle de); modül o takıma kapalıysa paylaşımla da 404")
    void analytics_teamAccessGrant() throws Exception {
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(analyticsResult());
        MockHttpSession g = grantee(B, A, false);
        mvc.perform(get("/api/tech-error-reports/analytics").session(g)).andExpect(status().isOk());
        assertThat(analyticsTeams()).containsExactlyInAnyOrder(A, B);
        mvc.perform(get("/api/tech-error-reports/analytics").param("team", "1").session(g)).andExpect(status().isOk());
        verify(analytics).analytics(any(), eq(A), eq(12), any(), any(), any(), eq(false));
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            mvc.perform(get("/api/tech-error-reports/analytics").param("team", "1").session(grantee(B, A, false)))
                    .andExpect(status().isNotFound());
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        }
    }

    @Test
    @DisplayName("analiz: fresh=1 servise iletilir; düşen alanlar + varsayılana dönüş, alan türü farkı ve kuyruktaki rapor yanıtta")
    void analytics_freshFallbackMismatchQueued() throws Exception {
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(analyticsFallbackResult());
        mvc.perform(get("/api/tech-error-reports/analytics").param("fields", "gone-field").param("fresh", "1")
                        .session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields_fallback").value(true))
                .andExpect(jsonPath("$.data.dropped_fields[0]").value("gone-field"))
                .andExpect(jsonPath("$.data.fields[0].type_mismatch[0]").value(20))
                .andExpect(jsonPath("$.data.channels[0].reports[11].sent").value(false))
                .andExpect(jsonPath("$.data.channels[0].reports[11].queued").value(true));
        verify(analytics).analytics(any(), eq(null), eq(12), eq(List.of("gone-field")), any(), any(), eq(true));
        org.mockito.Mockito.clearInvocations(analytics);
        // Varsayılan yanıt: dönüş yok, düşen alan yok, kuyruk işareti yalnız kuyruktaki raporda
        when(analytics.analytics(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(analyticsResult());
        mvc.perform(get("/api/tech-error-reports/analytics").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields_fallback").value(false))
                .andExpect(jsonPath("$.data.dropped_fields.length()").value(0))
                .andExpect(jsonPath("$.data.fields[0].type_mismatch.length()").value(0))
                .andExpect(jsonPath("$.data.channels[0].reports[11].queued").doesNotExist());
        verify(analytics).analytics(any(), any(), eq(12), any(), any(), any(), eq(false));
    }

    // ── Takım şablon kütüphanesi (D17) ve raporun şablon sürümü (D19), 2026-10-18 ───────────────────

    private static com.sitemonitor.model.TechErrorTeamTemplate libraryEntry(long id, long team, String json) {
        com.sitemonitor.model.TechErrorTeamTemplate e = new com.sitemonitor.model.TechErrorTeamTemplate();
        e.setId(id);
        e.setTeamId(team);
        e.setName("Takım varsayılanı (Dijital Kanallar)");
        e.setTemplateJson(json);
        e.setVersion(1L);
        return e;
    }

    @Test
    @DisplayName("kütüphaneden kanal: kayıt AÇILAN takımın kütüphanesinden okunur, katı doğrulanır, kaynak (library + ad) servise ve denetime gider; başka takımın / yok kaydı 400 library_template_id, bozuk / bölümsüz kayıt 409 TEMPLATE_UNREADABLE (sistem şablonuna düşmez); kopya ile birlikte 400; yanıt gerçek kaynağı taşır")
    void createChannelFromLibrary() throws Exception {
        TechErrorTemplate.Template d = TechErrorTemplate.defaults("tr");
        TechErrorTemplate.Template titled = new TechErrorTemplate.Template(d.lang(), "Kütüphane {kanal} {ay} {yil}", d.sections());
        when(library.find(A, 5L)).thenReturn(Optional.of(libraryEntry(5, A, TechErrorTemplate.toJson(titled))));
        when(library.find(A, 6L)).thenReturn(Optional.of(libraryEntry(6, A, "{bozuk")));
        // Okunabilir JSON ama tek bölüm yok: hoşgörülü okuma sistem bölümlerine düşerdi — o da "okunamadı"
        when(library.find(A, 8L)).thenReturn(Optional.of(libraryEntry(8, A, "{\"lang\":\"tr\",\"title\":\"x\",\"sections\":[]}")));
        TechErrorChannel created = channel(30, A);
        when(service.createChannel(eq(A), eq("Mobil"), any(), any(), eq(50L), any())).thenReturn(created);
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"Mobil\",\"library_template_id\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(30))
                // Gerçekten kullanılan kaynak yanıtta (eski pod bunu yazmaz → istemci uyarır)
                .andExpect(jsonPath("$.data.template_source.kind").value("library"))
                .andExpect(jsonPath("$.data.template_source.library_template_id").value(5))
                .andExpect(jsonPath("$.data.template_source.name").value("Takım varsayılanı (Dijital Kanallar)"));
        verify(service).createChannel(eq(A), eq("Mobil"), any(),
                org.mockito.ArgumentMatchers.argThat(t -> "Kütüphane {kanal} {ay} {yil}".equals(t.title())), eq(50L),
                org.mockito.ArgumentMatchers.argThat(src -> src != null && "library".equals(src.kind())
                        && "Takım varsayılanı (Dijital Kanallar)".equals(src.name())));
        verify(auditService).recordAction(eq("TECH_ERROR_CHANNEL_CREATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_CHANNEL"),
                eq("30"), org.mockito.ArgumentMatchers.argThat(dt -> dt != null && dt.contains("library")));
        // Başka takımın kaydı: aranan takım AÇILAN kanalın takımı → bulunmaz
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"POS\",\"library_template_id\":77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("library_template_id"));
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"POS\",\"library_template_id\":6}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"))
                .andExpect(jsonPath("$.field").value("library_template_id"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("okunamadı")));
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"POS\",\"library_template_id\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"));
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"POS\",\"library_template_id\":5,\"copy_from\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("library_template_id"));
        verify(service, never()).createChannel(eq(A), eq("POS"), any(), any(), any(), any());
        // Varsayılan kaynak işaretlenir
        when(service.createChannel(eq(A), eq("ATM"), any(), any(), eq(50L), any())).thenReturn(channel(31, A));
        mvc.perform(post("/api/tech-error-reports/channels").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":1,\"name\":\"ATM\",\"lang\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template_source.kind").value("default"))
                .andExpect(jsonPath("$.data.template_source.name").doesNotExist());
        verify(service).createChannel(eq(A), eq("ATM"), any(), org.mockito.ArgumentMatchers.argThat(t -> "en".equals(t.lang())), eq(50L),
                org.mockito.ArgumentMatchers.argThat(src -> src != null && "default".equals(src.kind())));
    }

    @Test
    @DisplayName("genel bakış: takım satırı library_read taşır — kendi takımı true; YALNIZ paylaşımla görülen takım false")
    void overviewLibraryRead() throws Exception {
        mvc.perform(get("/api/tech-error-reports/overview").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teams[0].id").value(1))
                .andExpect(jsonPath("$.data.teams[0].library_read").value(true));
        mvc.perform(get("/api/tech-error-reports/overview").session(grantee(B, A, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teams[?(@.id == 1)].library_read").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.data.teams[?(@.id == 2)].library_read").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("sürüm penceresi (D19): her sürümde report_count (tek gruplu sorgu), sürümü bilinmeyen ve listede olmayan sürümlerin raporları ayrı; ilk sürümün kaynağı; ayrıntıda en yeni dönemler raporun kendi başlığıyla")
    void templateVersionsReportCounts() throws Exception {
        when(history.templateVersions(10L)).thenReturn(List.of(meta(3), meta(2), meta(1)));
        Map<Integer, Long> counts = new java.util.HashMap<>();
        counts.put(3, 4L);
        counts.put(1, 2L);
        counts.put(7, 5L);           // listede olmayan (budanmış) sürüm
        counts.put(null, 6L);        // sürümü bilinmeyen
        when(service.templateVersionCounts(10L)).thenReturn(counts);
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[0].report_count").value(4))
                .andExpect(jsonPath("$.data.versions[1].report_count").value(0))
                .andExpect(jsonPath("$.data.versions[2].report_count").value(2))
                .andExpect(jsonPath("$.data.versions[2].source_kind").value("library"))
                .andExpect(jsonPath("$.data.versions[2].source_name").value("Takım varsayılanı"))
                .andExpect(jsonPath("$.data.unknown_version_reports").value(6))
                .andExpect(jsonPath("$.data.unlisted_version_reports").value(5));
        verify(service, org.mockito.Mockito.times(1)).templateVersionCounts(10L);
        // Yalnız paylaşımla okuyan: kütüphane kaydının ADI verilmez (kütüphane takımın kendi alanı), tür kalır
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions").session(grantee(B, A, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[2].source_kind").value("library"))
                .andExpect(jsonPath("$.data.versions[2].source_name").value(org.hamcrest.Matchers.nullValue()));
        // Kayıtlı sürümü olmayan kanal: o anki sürüm satırı da sayıyı taşır
        when(history.templateVersions(10L)).thenReturn(List.of());
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions").session(user("USER", A, List.of())))
                .andExpect(jsonPath("$.data.versions[0].recorded").value(false))
                .andExpect(jsonPath("$.data.versions[0].report_count").value(4))
                .andExpect(jsonPath("$.data.unlisted_version_reports").value(7));
        TechErrorReport sep = report(100, 10, A, null);
        sep.setTitlePattern("{kanal} Raporu {ay} {yil}");
        sep.setTemplateLang("tr");
        when(service.reportsWithTemplateVersion(10L, 3)).thenReturn(List.of(TechErrorReportController.rowOf(sep)));
        when(history.templateVersion(10L, 3)).thenReturn(Optional.empty());
        mvc.perform(get("/api/tech-error-reports/channels/10/template/versions/3").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_count").value(4))
                .andExpect(jsonPath("$.data.reports[0].id").value(100))
                .andExpect(jsonPath("$.data.reports[0].month").value(9))
                .andExpect(jsonPath("$.data.reports[0].title").value("Kanal 10 Raporu Eylül 2026"));
    }

    @Test
    @DisplayName("rapor yanıtı ve liste satırı raporun şablon sürümünü taşır (D19); bilinmeyen null (sıfır değil)")
    void reportCarriesTemplateVersion() throws Exception {
        TechErrorReport r = report(100, 10, A, null);
        r.setTemplateVersion(2);
        report(101, 10, A, null);
        mvc.perform(get("/api/tech-error-reports/reports/100").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.template_version").value(2))
                .andExpect(jsonPath("$.data.channel.template_version").value(3));
        mvc.perform(get("/api/tech-error-reports/reports/101").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.template_version").value(org.hamcrest.Matchers.nullValue()));
        when(service.reportRows(10L)).thenReturn(List.of(TechErrorReportController.rowOf(r)));
        mvc.perform(get("/api/tech-error-reports/channels/10/reports").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reports[0].template_version").value(2));
    }
}
