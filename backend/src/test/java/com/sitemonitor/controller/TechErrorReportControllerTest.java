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
                .andExpect(jsonPath("$.data.template.sections.length()").value(11))
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
}
