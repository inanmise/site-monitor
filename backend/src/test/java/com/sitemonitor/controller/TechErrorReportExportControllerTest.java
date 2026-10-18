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
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

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
                "sent", "2026-10-02T08:00:00Z", false, null, field, "Etiket, " + field, type, value, false, "percent".equals(type),
                "percent".equals(type) ? "OK" : null);
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
                .andExpect(jsonPath("$.data.columns[" + (TechErrorExportService.COLUMNS.size() - 1) + "]").value("calc_status"))
                .andExpect(jsonPath("$.data.rows[1][" + TechErrorExportService.COLUMNS.indexOf("calc_status") + "]").value("OK"))
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

    /** Eski gövde kurulumu (akışa geçmeden önceki kod) — {@link #jsonStreamIsByteIdenticalToTheOldBody} kâhini. */
    private static java.util.Map<String, Object> oldBody(TechErrorExportService.Result res, java.util.Map<String, Object> filter) {
        List<java.util.Map<String, Object>> reports = new ArrayList<>();
        for (TechErrorExportService.ReportMeta r : res.reports()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("team_id", r.teamId());
            m.put("team_name", r.teamName());
            m.put("channel_id", r.channelId());
            m.put("channel_name", r.channelName());
            m.put("year", r.year());
            m.put("month", r.month());
            m.put("report_id", r.reportId());
            m.put("report_status", r.status());
            m.put("sent_at", r.sentAt() == null ? null : r.sentAt().toString());
            m.put("changed_after_send", r.changedAfterSend());
            m.put("approval_state", r.approvalState());
            m.put("field_count", r.fieldCount());
            reports.add(m);
        }
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("columns", TechErrorExportService.COLUMNS);
        data.put("rows", res.rows());
        data.put("notes", TechErrorReportExportController.notes(true));
        data.put("reports", reports);
        data.put("report_count", res.reports().size());
        data.put("row_count", res.rows().size());
        data.put("max_reports", TechErrorExportService.MAX_REPORTS);
        data.put("filter", filter);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    @Test
    @DisplayName("JSON akışla yazılır (inceleme 2026-10-18) ve eski ResponseEntity gövdesiyle BAYT BAYT aynıdır (kâhin: eski harita + uygulamanın ObjectMapper'ı); application/json, no-store")
    void jsonStreamIsByteIdenticalToTheOldBody() throws Exception {
        MvcResult res = mvc.perform(get(URL).param("year", "2026").param("team", "1").session(user(A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        TechErrorExportService.Result data = exporter.export(List.of(channels.get(0)), java.util.Map.of(), null, null);
        java.util.Map<String, Object> filter = new java.util.LinkedHashMap<>();
        filter.put("team", 1L);
        filter.put("channel", null);
        filter.put("year", 2026);
        filter.put("month", null);
        filter.put("status", "sent");
        assertThat(body).isEqualTo(objectMapper.writeValueAsString(oldBody(data, filter)));
        assertThat(body).contains("\"report_count\":1").contains("\"max_reports\":" + TechErrorExportService.MAX_REPORTS);
    }

    @Test
    @DisplayName("JSON'da kullanıcı kimliği yok: sütun adları ve gövdedeki hiçbir anahtar UserRefWire'ın çevirdiği adlardan değil — opak kimlik tavsiyesi bu akışa uygulanmaz ve gerekmez")
    void jsonCarriesNoUserRefKeys() throws Exception {
        java.util.Set<String> forbidden = new java.util.HashSet<>(com.sitemonitor.service.userref.UserRefWire.KEYS);
        forbidden.addAll(com.sitemonitor.service.userref.UserRefWire.JSON_TEXT_KEYS);
        forbidden.addAll(List.of("resource_type", "resourceType", "resource_id", "resourceId"));
        assertThat(TechErrorExportService.COLUMNS).doesNotContainAnyElementsOf(forbidden);
        String body = mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectKeys(objectMapper.readTree(body), keys);
        assertThat(keys).contains("success", "data", "columns", "rows", "reports", "filter", "team_id", "report_id");
        assertThat(keys).doesNotContainAnyElementsOf(forbidden);
    }

    private static void collectKeys(tools.jackson.databind.JsonNode n, java.util.Set<String> out) {
        if (n == null) return;
        if (n.isArray()) {
            for (tools.jackson.databind.JsonNode c : n) collectKeys(c, out);
        } else if (n.isObject()) {
            for (java.util.Map.Entry<String, tools.jackson.databind.JsonNode> e : n.properties()) {
                out.add(e.getKey());
                collectKeys(e.getValue(), out);
            }
        }
    }

    /** JSON yazılırken (değer serileştirilirken) bir eylem koşturan değer — CSV testindeki {@code toString} sondasının JSON karşılığı. */
    public static final class JsonProbe {
        private final Runnable onWrite;

        JsonProbe(Runnable onWrite) {
            this.onWrite = onWrite;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String value() {
            onWrite.run();
            return "probe";
        }
    }

    @Test
    @DisplayName("JSON yazılırken: pod izni geri verilmiş, kişinin \"süren dışa aktarım\" işareti DURUYOR — aynı kişinin ikinci isteği beklemeden 429 EXPORT_BUSY; yazma bitince işaret kalkar")
    void markerHeldWhileJsonIsWritten() throws Exception {
        java.util.concurrent.atomic.AtomicInteger permitsWhileWriting = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean markerWhileWriting = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<MvcResult> same = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        JsonProbe probe = new JsonProbe(() -> {
            if (!once.compareAndSet(false, true)) return;
            try {
                permitsWhileWriting.set(controller.exportSlots.availablePermits());
                markerWhileWriting.set(controller.running.contains("u:" + (50L + A)));
                same.set(pool.submit(() -> perform(get(URL).param("year", "2026").session(user(A))))
                        .get(20, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        org.mockito.Mockito.doAnswer(inv -> {
            List<TechErrorChannel> chs = inv.getArgument(0);
            List<List<Object>> rows = new ArrayList<>();
            for (TechErrorChannel ch : chs) rows.add(row(ch, "comment", "text", ch.getId() == 10L ? probe : "metin"));
            return new TechErrorExportService.Result(List.of(), rows);
        }).when(exporter).export(any(), any(), any(), any());
        try {
            MvcResult first = mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isOk()).andReturn();
            assertThat(first.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"probe\"");
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).isNull();
        assertThat(permitsWhileWriting.get()).as("JSON yazılırken pod izni geri verilmiş").isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
        assertThat(markerWhileWriting.get()).as("kişinin işareti yazma bitene dek durur").isTrue();
        assertThat(same.get().getResponse().getStatus()).isEqualTo(429);
        assertThat(code(same.get())).isEqualTo("EXPORT_BUSY");
        assertThat(controller.running).isEmpty();
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
        // Akıtılan gövde, bellekteki biçimle bayt bayt aynı
        List<List<Object>> sample = List.of(Arrays.asList(1L, "Takım, A", 10L, "=X", 2026, 9, 5L, "sent", null, false, null,
                "f", "Etiket", "percent", new BigDecimal("-1.5"), false, true, "OK"));
        java.io.StringWriter sw = new java.io.StringWriter();
        TechErrorReportExportController.writeCsv(sw, sample);
        assertThat(sw.toString()).isEqualTo(TechErrorReportExportController.csv(sample));
        assertThat(sw.toString()).startsWith("\uFEFFteam_id,").contains(",-1.5,").contains("'=X").contains(",true,OK\r\n");
        assertThat(lines[0]).endsWith(",exempt,computed,calc_status");
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
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
                .andExpect(jsonPath("$.data.notes[3]").value(org.hamcrest.Matchers.containsString("LIVE")))
                .andExpect(jsonPath("$.data.notes[3]").value(org.hamcrest.Matchers.containsString("computed = false")))
                .andExpect(jsonPath("$.data.notes[3]").value(org.hamcrest.Matchers.containsString("calc_status")))
                .andExpect(jsonPath("$.data.notes[3]").value(org.hamcrest.Matchers.containsString("MISSING")))
                .andExpect(jsonPath("$.data.notes[3]").value(org.hamcrest.Matchers.containsString("does not mean")))
                .andExpect(jsonPath("$.data.notes[5]").value(org.hamcrest.Matchers.containsString("CURRENT executive recipient list")))
                .andExpect(jsonPath("$.data.notes[6]").value(org.hamcrest.Matchers.containsString("No personal data")));
    }

    @Test
    @DisplayName("kuyruk: okumadan ÖNCE kapsamdaki kanalların kuyruktaki gönderimleri sonuçlandırılır (tek çağrı, diğer okuma uçları gibi);"
            + " doğrulama / kapsam / hız reddinde çağrılmaz")
    void reconcilesQueuedBeforeReading() throws Exception {
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("userId", 1L);
        admin.setAttribute("systemRole", "ADMIN");
        mvc.perform(get(URL).param("year", "2026").session(admin)).andExpect(status().isOk());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service, exporter);
        order.verify(service).reconcileQueued(List.of(10L, 20L));
        order.verify(exporter).export(any(), any(), any(), any());
        clearInvocations(service, exporter);
        mvc.perform(get(URL).param("channel", "10").session(user(A))).andExpect(status().isOk());
        verify(service).reconcileQueued(List.of(10L));
        clearInvocations(service);
        mvc.perform(get(URL).param("year", "2019").session(user(A))).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("channel", "20").session(user(A))).andExpect(status().isNotFound());
        verify(service, never()).reconcileQueued(any());
    }

    @Test
    @DisplayName("eşzamanlılık: pod genelinde en çok 2 dışa aktarım; dolu → 429 EXPORT_BUSY + Retry-After, dışa aktarım ve kuyruk"
            + " sonuçlandırma çağrılmaz, kişinin dakikalık hakkı YENMEZ; izin her yanıtta (hata dahil) geri verilir")
    void concurrencyLimitDoesNotEatTheQuota() throws Exception {
        MockHttpSession me = user(A);
        long savedWait = controller.exportWaitMs;
        controller.exportWaitMs = 0;
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
        controller.exportSlots.acquire(TechErrorReportExportController.EXPORT_CONCURRENCY);
        try {
            for (int i = 0; i < TechErrorReportExportController.EXPORT_PER_MINUTE + 2; i++) {
                mvc.perform(get(URL).param("year", "2026").session(me))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("EXPORT_BUSY"))
                        .andExpect(jsonPath("$.retry_after_seconds").value(10))
                        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("2")))
                        .andExpect(header().string("Retry-After", "10"));
            }
            verify(exporter, never()).export(any(), any(), any(), any());
            verify(service, never()).reconcileQueued(any());
        } finally {
            controller.exportSlots.release(TechErrorReportExportController.EXPORT_CONCURRENCY);
            controller.exportWaitMs = savedWait;
        }
        // Meşgul denemeler hakkı yemedi: 6 istek geçer
        for (int i = 0; i < TechErrorReportExportController.EXPORT_PER_MINUTE; i++) {
            mvc.perform(get(URL).param("year", "2026").session(me)).andExpect(status().isOk());
        }
        // Tavan aşımı (400) ve CSV yolu izni geri verir
        org.mockito.Mockito.doThrow(new TechErrorExportService.TooLargeException()).when(exporter).export(any(), any(), any(), any());
        mvc.perform(get(URL).param("year", "2026").session(user(B))).andExpect(status().isBadRequest());
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        try {
            return mvc.perform(b).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String code(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? "" : com.jayway.jsonpath.JsonPath.read(body, "$.code");
    }

    @Test
    @DisplayName("pod izni YANIT YAZILMADAN bırakılır: CSV yazılırken izin geri verilmiş — başka kullanıcının dışa aktarımı beklemeden geçer;"
            + " aynı kullanıcının ikinci isteği (süren dışa aktarımı varken) pod dolu olsa da BEKLEMEDEN 429 EXPORT_BUSY ve dakikalık hakkı yemez")
    void permitReleasedBeforeStreamingAndOnePerUser() throws Exception {
        long savedWait = controller.exportWaitMs;
        controller.exportWaitMs = 0;                                   // izin bırakılmamışsa ikinci kullanıcı anında düşer
        int held = TechErrorReportExportController.EXPORT_CONCURRENCY - 1;
        java.util.concurrent.atomic.AtomicInteger permitsWhileWriting = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicReference<MvcResult> other = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<MvcResult> same = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicLong sameMs = new AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        // CSV yazılırken (csvValue → toString) çalışır: o an akış sürüyor
        Object probe = new Object() {
            @Override
            public String toString() {
                if (once.compareAndSet(false, true)) {
                    try {
                        permitsWhileWriting.set(controller.exportSlots.availablePermits());
                        other.set(pool.submit(() -> perform(get(URL).param("year", "2026").param("team", "2").session(user(B))))
                                .get(20, java.util.concurrent.TimeUnit.SECONDS));
                        // Pod dolu + uzun bekleme: aynı kişinin isteği yine de beklemeden reddedilmeli
                        int drained = controller.exportSlots.drainPermits();
                        controller.exportWaitMs = 4_000;
                        try {
                            long t0 = System.nanoTime();
                            same.set(pool.submit(() -> perform(get(URL).param("year", "2026").session(user(A))))
                                    .get(20, java.util.concurrent.TimeUnit.SECONDS));
                            sameMs.set((System.nanoTime() - t0) / 1_000_000L);
                        } finally {
                            controller.exportWaitMs = 0;
                            controller.exportSlots.release(drained);
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                }
                return "probe";
            }
        };
        org.mockito.Mockito.doAnswer(inv -> {
            List<TechErrorChannel> chs = inv.getArgument(0);
            List<List<Object>> rows = new ArrayList<>();
            for (TechErrorChannel ch : chs) rows.add(row(ch, "comment", "text", ch.getId() == 10L ? probe : "metin"));
            return new TechErrorExportService.Result(List.of(), rows);
        }).when(exporter).export(any(), any(), any(), any());
        controller.exportSlots.acquire(held);                         // tek izin kalsın: ilk istek alır, yazmadan bırakmalı
        try {
            MvcResult first = mvc.perform(get(URL).param("year", "2026").param("format", "csv").session(user(A)))
                    .andExpect(status().isOk()).andReturn();
            assertThat(first.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(",probe,");
        } finally {
            controller.exportSlots.release(held);
            controller.exportWaitMs = savedWait;
            pool.shutdownNow();
        }
        assertThat(failure.get()).isNull();
        assertThat(permitsWhileWriting.get()).as("CSV yazılırken pod izni geri verilmiş").isEqualTo(1);
        assertThat(other.get().getResponse().getStatus()).as("başka kullanıcı akış sürerken geçer").isEqualTo(200);
        assertThat(same.get().getResponse().getStatus()).isEqualTo(429);
        assertThat(code(same.get())).isEqualTo("EXPORT_BUSY");
        assertThat(same.get().getResponse().getHeader("Retry-After")).isEqualTo("10");
        assertThat(sameMs.get()).as("aynı kişinin ikinci isteği izin beklemez").isLessThan(2_000L);
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
        assertThat(controller.running).isEmpty();
        // Reddedilen eşzamanlı istek hakkı yemedi: 1 başarılı + 5 daha = dakikada 6
        for (int i = 0; i < TechErrorReportExportController.EXPORT_PER_MINUTE - 1; i++) {
            mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).param("year", "2026").session(user(A)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("JSON yolu da izni yanıt gövdesi yazılmadan bırakır; hata (tavan aşımı) ve beklenmeyen istisna izni ve kişi işaretini geri verir")
    void permitAndMarkerReleasedOnEveryPath() throws Exception {
        mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isOk());
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
        assertThat(controller.running).isEmpty();
        org.mockito.Mockito.doThrow(new RuntimeException("beklenmeyen")).when(exporter).export(any(), any(), any(), any());
        mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().is5xxServerError());
        assertThat(controller.exportSlots.availablePermits()).isEqualTo(TechErrorReportExportController.EXPORT_CONCURRENCY);
        assertThat(controller.running).isEmpty();
        // Ardından aynı kişi yeniden dışa aktarabilir (işaret takılı kalmadı)
        org.mockito.Mockito.doThrow(new TechErrorExportService.TooLargeException()).when(exporter).export(any(), any(), any(), any());
        mvc.perform(get(URL).param("year", "2026").session(user(A))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"));
        assertThat(controller.running).isEmpty();
    }

    @Test
    @DisplayName("meşgul bütçesi: kişi başına dakikada " + TechErrorReportExportController.EXPORT_BUSY_PER_MINUTE + " EXPORT_BUSY reddi, sonrası BEKLEMEDEN"
            + " 429 RATE_LIMITED + Retry-After (pod boşalsa da pencere boşalana dek); başka kullanıcı etkilenmez; pencere geçince açılır")
    void busyRefusalsHaveTheirOwnBudget() throws Exception {
        MockHttpSession me = user(A);
        long savedWait = controller.exportWaitMs;
        controller.exportWaitMs = 0;
        int drained = controller.exportSlots.drainPermits();
        try {
            for (int i = 0; i < TechErrorReportExportController.EXPORT_BUSY_PER_MINUTE; i++) {
                mvc.perform(get(URL).param("year", "2026").session(me))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("EXPORT_BUSY"));
            }
            mvc.perform(get(URL).param("year", "2026").session(me))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.retry_after_seconds").value(60))
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                            String.valueOf(TechErrorReportExportController.EXPORT_BUSY_PER_MINUTE))));
        } finally {
            controller.exportSlots.release(drained);
            controller.exportWaitMs = savedWait;
        }
        // Pod boş ama bütçe penceresi dolu: yine beklemeden sınırlanır, dışa aktarım çağrılmaz
        mvc.perform(get(URL).param("year", "2026").session(me))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(exporter, never()).export(any(), any(), any(), any());
        mvc.perform(get(URL).param("year", "2026").session(user(B))).andExpect(status().isOk());
        CLOCK.addAndGet(61_000L);
        mvc.perform(get(URL).param("year", "2026").session(me)).andExpect(status().isOk());
        assertThat(controller.running).isEmpty();
    }

    @Test
    @DisplayName("meşgul reddi kayıtları sınırlı: 1 000 anahtarı aşan haritada süresi geçenler atılır; anahtar başına en çok bütçe kadar kayıt")
    void busyMapIsBounded() {
        long t0 = CLOCK.get();
        for (int i = 0; i < 1_200; i++) controller.noteBusy("k" + i);
        for (int i = 0; i < 3 * TechErrorReportExportController.EXPORT_BUSY_PER_MINUTE; i++) controller.noteBusy("k0");
        assertThat(controller.busyRetryAfter("k0")).isEqualTo(60L);
        assertThat(controller.busyRetryAfter("k1")).isNull();
        CLOCK.set(t0 + 61_000L);
        controller.noteBusy("yeni");
        synchronized (controller.busyCalls) {
            assertThat(controller.busyCalls).containsOnlyKeys("yeni");
        }
        assertThat(controller.busyRetryAfter("k0")).isNull();
    }
}
