package com.sitemonitor.controller;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorExportService;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teknik hata raporu değer dışa aktarımı ucu (E23, 2026-10-18): kapı modülün okuma kapısı (izin + görünürlük 403), takım /
 * kanal okuma kapsamı (okunamayan 404, paylaşımla VIEW okuyan paylaşan takımı görür), parametre doğrulaması (400 alanlı),
 * JSON biçimi, CSV başlıkları + BOM + formül nötrleme, denetim satırı, kullanıcı başına dakikada 6. Dışa aktarım servisi
 * sahte — kapı ve biçimleme mantığı gerçek.
 */
@WebMvcTest(TechErrorReportExportController.class)
class TechErrorReportExportControllerTest {

    private static final long A = 1L, B = 2L;
    private static final String URL = "/api/tech-error-reports/export";
    /** Testler arası paylaşılan denetleyici örneğinin hız sınırı penceresi her testte ileri sarılır. */
    private static final AtomicLong CLOCK = new AtomicLong(1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired TechErrorReportExportController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean TechErrorReportService service;
    @MockitoBean TechErrorExportService exporter;
    @MockitoBean TeamRepository teams;
    @MockitoBean PermissionService permissions;
    @MockitoBean AuditService auditService;

    private final List<TechErrorChannel> channels = new ArrayList<>();

    private static Team team(long id) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + id);
        t.setActive(true);
        return t;
    }

    private TechErrorChannel channel(long id, long team) {
        TechErrorChannel c = new TechErrorChannel();
        c.setId(id);
        c.setTeamId(team);
        c.setName("Kanal " + id);
        c.setActive(true);
        c.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        channels.add(c);
        return c;
    }

    /** Satır: gerçek sütun sırası; "comment" alanı formül gibi başlayan metin, oran negatif yüzde. */
    private static List<Object> row(TechErrorChannel ch, String field, String type, Object value) {
        return Arrays.asList(ch.getTeamId(), "Takım " + ch.getTeamId(), ch.getId(), ch.getName(), 2026, 9, 100L + ch.getId(),
                "sent", "2026-10-02T08:00:00Z", false, null, field, "Etiket, " + field, type, value, false, "percent".equals(type));
    }

    @BeforeEach
    void setUp() {
        CLOCK.addAndGet(10 * 60_000L);
        controller.exportClock = CLOCK::get;
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(true);
        when(teams.findById(any())).thenAnswer(inv -> Optional.of(team((Long) inv.getArgument(0))));
        when(teams.findAllById(any())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) out.add(team((Long) o));
            return out;
        });
        when(service.now()).thenReturn(Instant.parse("2026-10-18T09:00:00Z"));
        when(service.channel(anyLong())).thenAnswer(inv -> channels.stream().filter(c -> c.getId().equals(inv.getArgument(0)))
                .findFirst().orElseThrow(NoSuchElementException::new));
        when(service.channels(any())).thenAnswer(inv -> {
            Collection<?> teamIds = inv.getArgument(0);
            return channels.stream().filter(c -> teamIds == null || teamIds.contains(c.getTeamId())).toList();
        });
        when(exporter.export(any(), any(), any(), any())).thenAnswer(inv -> {
            List<TechErrorChannel> chs = inv.getArgument(0);
            List<List<Object>> rows = new ArrayList<>();
            List<TechErrorExportService.ReportMeta> metas = new ArrayList<>();
            for (TechErrorChannel ch : chs) {
                rows.add(row(ch, "comment", "text", "=cmd|' /c calc'!A1"));
                rows.add(row(ch, "error-rate", "percent", new BigDecimal("-1.5")));
                rows.add(row(ch, "had-outage", "yesno", true));
                metas.add(new TechErrorExportService.ReportMeta(ch.getTeamId(), "Takım " + ch.getTeamId(), ch.getId(), ch.getName(),
                        2026, 9, 100L + ch.getId(), "sent", Instant.parse("2026-10-02T08:00:00Z"), false, null, 3));
            }
            return new TechErrorExportService.Result(metas, rows);
        });
        channel(10, A);
        channel(20, B);
    }

    private static MockHttpSession user(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u" + team);
        s.setAttribute("userId", 50L + team);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", List.of());
        return s;
    }

    private static MockHttpSession grantee(long ownTeam, long sharedTeam) {
        MockHttpSession s = user(ownTeam);
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(sharedTeam)));
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<Long> exportedChannelIds() {
        ArgumentCaptor<List<TechErrorChannel>> cap = ArgumentCaptor.forClass(List.class);
        verify(exporter, org.mockito.Mockito.atLeastOnce()).export(cap.capture(), any(), any(), any());
        return cap.getValue().stream().map(TechErrorChannel::getId).toList();
    }

    @Test
    @DisplayName("kapsam: okunamayan takım / kanal 404 (varlığı sızmaz), takım-kanal uyuşmazlığı 404, bozuk kimlik 400 alanlı; okumada izin yoksa 403; dışa aktarım çağrılmaz")
    void scope404() throws Exception {
        MockHttpSession s = user(A);
        mvc.perform(get(URL).param("team", "2").param("year", "2026").session(s)).andExpect(status().isNotFound());
        mvc.perform(get(URL).param("channel", "20").session(s)).andExpect(status().isNotFound());
        mvc.perform(get(URL).param("channel", "99").session(s)).andExpect(status().isNotFound());
        mvc.perform(get(URL).param("team", "1").param("channel", "20").session(user(B))).andExpect(status().isNotFound());
        mvc.perform(get(URL).param("team", "abc").param("year", "2026").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("team"));
        verify(exporter, never()).export(any(), any(), any(), any());
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(false);
        mvc.perform(get(URL).param("year", "2026").session(s)).andExpect(status().isForbidden());
        verify(auditService, never()).recordAction(eq("TECH_ERROR_REPORT_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("modül görünürlüğü (S6): listede olmayan takımın üyesi 403; listedeki takım 200; global yönetici her takımı dışa aktarır")
    void moduleHidden403() throws Exception {
        com.sitemonitor.service.AppSettingsService st = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        when(st.getString(eq(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn("2");
        com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(new com.sitemonitor.service.techerror.TechErrorVisibilityService(st));
        try {
            mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isForbidden());
            mvc.perform(get(URL).param("year", "2026").session(user(B))).andExpect(status().isOk());
            assertThat(exportedChannelIds()).containsExactly(20L);
            MockHttpSession admin = new MockHttpSession();
            admin.setAttribute("authenticated", Boolean.TRUE);
            admin.setAttribute("username", "admin");
            admin.setAttribute("userId", 1L);
            admin.setAttribute("systemRole", "ADMIN");
            clearInvocations(exporter);
            mvc.perform(get(URL).param("year", "2026").param("team", "all").session(admin)).andExpect(status().isOk());
            assertThat(exportedChannelIds()).containsExactly(10L, 20L);
        } finally {
            com.sitemonitor.service.techerror.TechErrorVisibilityService.setCurrent(null);
        }
    }

    @Test
    @DisplayName("erişim paylaşımı VIEW: paylaşımla okuyan paylaşan takımı (team=<id>) ve team=all'da kendi + paylaşılan takımı dışa aktarır; paylaşımsız takım 404")
    void grantReaderSeesGrantedTeam() throws Exception {
        MockHttpSession g = grantee(B, A);
        mvc.perform(get(URL).param("team", "1").param("year", "2026").session(g)).andExpect(status().isOk());
        assertThat(exportedChannelIds()).containsExactly(10L);
        clearInvocations(exporter);
        mvc.perform(get(URL).param("year", "2026").session(g)).andExpect(status().isOk());
        assertThat(exportedChannelIds()).containsExactly(10L, 20L);
        mvc.perform(get(URL).param("team", "1").param("year", "2026").session(user(B))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("parametreler: yıl 2020…bu yıl, tek kanal dışında zorunlu; ay 1–12 ve yıl ister; durum sent|all; biçim json|csv — hepsi 400 alanlı ve hakkı yemez")
    void parameterValidation() throws Exception {
        MockHttpSession s = user(A);
        for (String[] bad : new String[][] {
                {"year", null, "year"}, {"year", "2019", "year"}, {"year", "2027", "year"}, {"year", "yirmi", "year"},
                {"month", "13", "month"}, {"status", "draft", "status"}, {"format", "xml", "format"}}) {
            var req = get(URL).session(s);
            if (!"year".equals(bad[0])) req = req.param("year", "2026");
            if (bad[1] != null) req = req.param(bad[0], bad[1]);
            mvc.perform(req).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(bad[2]));
        }
        // Ay yıl ister (tek kanalda da)
        mvc.perform(get(URL).param("channel", "10").param("month", "9").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
        verify(exporter, never()).export(any(), any(), any(), any());
        // Tek kanal: yıl olmadan tüm yıllar; geçersizler hak yemedi → 6 istek daha geçer
        ArgumentCaptor<TechErrorExportService.Filter> f = ArgumentCaptor.forClass(TechErrorExportService.Filter.class);
        mvc.perform(get(URL).param("channel", "10").param("status", "all").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filter.year").value(org.hamcrest.Matchers.nullValue()));
        verify(exporter).export(any(), any(), f.capture(), any());
        assertThat(f.getValue()).isEqualTo(new TechErrorExportService.Filter(null, null, false));
        clearInvocations(exporter);
        mvc.perform(get(URL).param("year", "2026").param("month", "9").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filter.status").value("sent"));
        verify(exporter).export(any(), any(), f.capture(), any());
        assertThat(f.getValue()).isEqualTo(new TechErrorExportService.Filter(2026, 9, true));
    }

    @Test
    @DisplayName("JSON: {columns, rows, notes, reports}; satır sütun sayısında; sayı düz ondalık, mantıksal true/false; kişi verisi yok; no-store")
    void jsonShape() throws Exception {
        MvcResult res = mvc.perform(get(URL).param("year", "2026").param("team", "1").session(user(A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columns.length()").value(TechErrorExportService.COLUMNS.size()))
                .andExpect(jsonPath("$.data.columns[0]").value("team_id"))
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andExpect(jsonPath("$.data.rows[0].length()").value(TechErrorExportService.COLUMNS.size()))
                .andExpect(jsonPath("$.data.rows[1][14]").value(-1.5))
                .andExpect(jsonPath("$.data.rows[2][14]").value(true))
                .andExpect(jsonPath("$.data.notes.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.reports[0].report_status").value("sent"))
                .andExpect(jsonPath("$.data.reports[0].field_count").value(3))
                .andExpect(jsonPath("$.data.report_count").value(1))
                .andExpect(jsonPath("$.data.row_count").value(3))
                .andExpect(jsonPath("$.data.max_reports").value(TechErrorExportService.MAX_REPORTS))
                .andExpect(jsonPath("$.data.filter.team").value(1))
                .andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("last_sent_to", "_by_", "@example.com", "approval_note", "recipients\"");
    }

    @Test
    @DisplayName("CSV: text/csv UTF-8, attachment + teknik-hata-raporlari-<yıl>-<ay>.csv, no-store, BOM, başlık = sütunlar; formül gibi başlayan metin nötrlenir, negatif yüzde sayı kalır")
    void csvHeadersBomAndInjectionGuard() throws Exception {
        MvcResult res = mvc.perform(get(URL).param("year", "2026").param("month", "9").param("format", "csv").session(user(A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsStringIgnoringCase("charset=UTF-8")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"teknik-hata-raporlari-2026-09.csv\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        byte[] bytes = res.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
        String[] lines = text.split("\r\n");
        assertThat(lines[0]).isEqualTo(String.join(",", TechErrorExportService.COLUMNS));
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).contains("'=cmd|' /c calc'!A1");
        assertThat(lines[1]).contains("\"Etiket, comment\"");                 // virgüllü metin tırnaklanır
        assertThat(lines[2]).contains(",-1.5,");                              // sayı: nötrlenmez (BI için sayı kalır)
        assertThat(lines[2]).doesNotContain("'-1.5");
        assertThat(lines[3]).contains(",true,");
        assertThat(TechErrorReportExportController.fileName(2026, null)).isEqualTo("teknik-hata-raporlari-2026.csv");
        assertThat(TechErrorReportExportController.fileName(null, null)).isEqualTo("teknik-hata-raporlari-tum-yillar.csv");
        assertThat(TechErrorReportExportController.csvValue(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        assertThat(TechErrorReportExportController.csvValue("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(TechErrorReportExportController.csvValue(null)).isEmpty();
    }

    @Test
    @DisplayName("denetim: başarılı dışa aktarım TECH_ERROR_REPORT_EXPORT yazar (süzgeç + rapor / satır sayısı; takım ya da kanal kaynağı); tavan aşımı 400 EXPORT_TOO_LARGE, denetim yok")
    void auditAndTooLarge() throws Exception {
        MockHttpSession s = user(A);
        mvc.perform(get(URL).param("year", "2026").param("team", "1").param("format", "csv").session(s)).andExpect(status().isOk());
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"),
                contains("\"rows\":3"));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"),
                contains("\"format\":\"csv\""));
        mvc.perform(get(URL).param("channel", "10").session(s)).andExpect(status().isOk());
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TECH_ERROR_CHANNEL"),
                eq("10"), contains("\"reports\":1"));
        clearInvocations(auditService);
        org.mockito.Mockito.doThrow(new TechErrorExportService.TooLargeException()).when(exporter).export(any(), any(), any(), any());
        mvc.perform(get(URL).param("year", "2026").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"))
                .andExpect(jsonPath("$.max_reports").value(TechErrorExportService.MAX_REPORTS))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("5000")));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_REPORT_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("hız sınırı: kullanıcı başına dakikada 6; 7. istek 429 RATE_LIMITED + Retry-After; başka kullanıcı etkilenmez; pencere geçince açılır")
    void rateLimit() throws Exception {
        MockHttpSession me = user(A);
        for (int i = 0; i < TechErrorReportExportController.EXPORT_PER_MINUTE; i++) {
            mvc.perform(get(URL).param("year", "2026").session(me)).andExpect(status().isOk());
        }
        mvc.perform(get(URL).param("year", "2026").session(me))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").value(60))
                .andExpect(header().string("Retry-After", "60"));
        mvc.perform(get(URL).param("year", "2026").session(user(B))).andExpect(status().isOk());
        CLOCK.addAndGet(61_000L);
        mvc.perform(get(URL).param("year", "2026").session(me)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("notlar istek dilinde: değerler GÜNCEL hâl, kendi şablonu (field_id + field_type), hesaplanan / muaf anlamı, kişi verisi yok")
    void notesExplainTheData() throws Exception {
        mvc.perform(get(URL).param("year", "2026").header("X-Lang", "en").session(user(A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[0]").value(org.hamcrest.Matchers.containsString("CURRENT")))
                .andExpect(jsonPath("$.data.notes[1]").value(org.hamcrest.Matchers.containsString("status=all")))
                .andExpect(jsonPath("$.data.notes[2]").value(org.hamcrest.Matchers.containsString("field_type")))
                .andExpect(jsonPath("$.data.notes[5]").value(org.hamcrest.Matchers.containsString("No personal data")));
    }
}
