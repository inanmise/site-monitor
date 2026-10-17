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
                .andExpect(jsonPath("$.data.template.sections.length()").value(10))
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
}
