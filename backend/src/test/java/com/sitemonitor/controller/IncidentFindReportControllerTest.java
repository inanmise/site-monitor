package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Olay ve Hata Geçmişi — "Bul ve Raporla" (2026-11-26, Paket 3) uç sözleşmesi: liste süzgeç parametreleri + sıralama +
 * {@code match_field} / {@code ext_filters}, dışa aktarım (kapsam, tavan, 429, denetim, formül koruması, no-store, JSON), eğilim
 * süzgeçleri, süzgeç takımları ve seçenek yeniden adlandırma / birleştirme (yalnız takımı yöneten, denetim kaydıyla).
 */
@WebMvcTest(IncidentController.class)
class IncidentFindReportControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IncidentController controller;

    @MockitoBean IncidentService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.IncidentNotificationService notificationService;
    @MockitoBean com.sitemonitor.service.IncidentAlertLinkService alertLinks;
    @MockitoBean com.sitemonitor.service.incident.IncidentPostmortemService postmortems;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private void allowView() {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
    }

    private void allowManage() {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
    }

    private static IncidentRecord rec(long id, String title) {
        IncidentRecord e = new IncidentRecord();
        e.setId(id);
        e.setTitle(title);
        e.setOccurredAt("2026-11-10T07:00:00");
        e.setSeverity("HIGH");
        e.setStatus("OPEN");
        e.setCategory("APPLICATION");
        e.setSlaBreached(false);
        e.setTeamId(5L);
        e.setTeamName("Takım A");
        e.setCreatedBy("sre1");
        e.setRcaSummary("**Kök** neden ![g](/api/incidents/images/3)");
        return e;
    }

    private static MockHttpSession userSessionTeam(Long teamId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "sre1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(teamId)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static MockHttpSession teamAdminSession(Long teamId) {
        MockHttpSession s = userSessionTeam(teamId);
        s.setAttribute("username", "lider");
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(teamId)));
        return s;
    }

    private static MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("liste: yeni süzgeçler servise gider, created_by=@me oturum kullanıcısı olur, sıralama + match_field + ext_filters yanıtta")
    void listForwardsFilters() throws Exception {
        allowView();
        when(service.list(any(IncidentService.ListFilter.class), any(), any(), any())).thenReturn(new PageImpl<>(List.of(rec(7, "DNS kesintisi"))));
        mvc.perform(get("/api/incidents").session(userSessionTeam(5L))
                        .param("q", "dns").param("service_exact", "web.example.com").param("channel_exact", "ATM")
                        .param("problem_type", "Kesinti").param("error_code", "E-1").param("tag", "prod")
                        .param("created_by", "@me").param("postmortem", "none").param("sort", "severity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ext_filters").value(true))
                .andExpect(jsonPath("$.sort").value("severity"))
                .andExpect(jsonPath("$.data[0].match_field").value("title"));
        ArgumentCaptor<IncidentService.ListFilter> f = ArgumentCaptor.forClass(IncidentService.ListFilter.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Long>> scope = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(service).list(f.capture(), scope.capture(), any(Pageable.class), eq("severity"));
        assertThat(f.getValue().serviceExact()).isEqualTo("web.example.com");
        assertThat(f.getValue().channelExact()).isEqualTo("ATM");
        assertThat(f.getValue().problemType()).isEqualTo("Kesinti");
        assertThat(f.getValue().errorCode()).isEqualTo("E-1");
        assertThat(f.getValue().tag()).isEqualTo("prod");
        assertThat(f.getValue().createdBy()).isEqualTo("sre1");
        assertThat(f.getValue().postmortem()).isEqualTo("none");
        assertThat(scope.getValue()).containsExactly(5L);
    }

    @Test
    @DisplayName("liste: okunamayan takım / doğru-yanlış süzgeci alan adlı 400 (servis çağrılmaz)")
    void listBadParams400() throws Exception {
        allowView();
        mvc.perform(get("/api/incidents").param("team_id", "x").session(adminSession()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("team_id"));
        mvc.perform(get("/api/incidents").param("open", "belki").session(adminSession()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("open"));
        verify(service, never()).list(any(IncidentService.ListFilter.class), any(), any(), any());
    }

    @Test
    @DisplayName("eğilim: liste süzgeçleriyle çağrılır (kapsam dahil)")
    void trendsForwardFilters() throws Exception {
        allowView();
        when(service.trends(any(IncidentService.ListFilter.class), any())).thenReturn(new LinkedHashMap<>(Map.of("filtered", true)));
        mvc.perform(get("/api/incidents/trends").session(userSessionTeam(5L)).param("since", "2026-11-01").param("tag", "prod"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.filtered").value(true))
                .andExpect(jsonPath("$.ext_filters").value(true));
        ArgumentCaptor<IncidentService.ListFilter> f = ArgumentCaptor.forClass(IncidentService.ListFilter.class);
        verify(service).trends(f.capture(), eq(List.of(5L)));
        assertThat(f.getValue().since()).isEqualTo("2026-11-01");
        assertThat(f.getValue().tag()).isEqualTo("prod");
    }

    @Test
    @DisplayName("inceleme 2026-11-26: view=daily yalnız günlük seriyi ister (servisin hafif yolu, tam hesap YOK); ext_filters kökte")
    void trendsDailyView() throws Exception {
        allowView();
        when(service.trends(any(IncidentService.ListFilter.class), any(), eq(true)))
                .thenReturn(new LinkedHashMap<>(Map.of("daily", List.of(), "filtered", true, "daily_only", true)));
        mvc.perform(get("/api/incidents/trends").session(userSessionTeam(5L)).param("view", "daily").param("tag", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily_only").value(true))
                .andExpect(jsonPath("$.ext_filters").value(true));
        verify(service).trends(any(IncidentService.ListFilter.class), eq(List.of(5L)), eq(true));
        verify(service, never()).trends(any(IncidentService.ListFilter.class), any());
        // bilinmeyen görünüm değeri = tam eğilim
        when(service.trends(any(IncidentService.ListFilter.class), any())).thenReturn(new LinkedHashMap<>(Map.of("filtered", false)));
        mvc.perform(get("/api/incidents/trends").session(userSessionTeam(5L)).param("view", "weekly"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.filtered").value(false));
        verify(service).trends(any(IncidentService.ListFilter.class), eq(List.of(5L)));
    }

    // ── Dışa aktarım ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dışa aktarım CSV: BOM + başlık + formül koruması + markdown düz metin + no-store + denetim; incidents.view yoksa 403")
    void exportCsv() throws Exception {
        mvc.perform(get("/api/incidents/export").session(userSessionTeam(5L))).andExpect(status().isForbidden());
        allowView();
        IncidentRecord r = rec(9, "=HYPERLINK(\"http://x\")");
        stubExport(1, List.of(r));
        MvcResult res = mvc.perform(get("/api/incidents/export").session(userSessionTeam(5L)).param("q", "x"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string("X-Export-Max-Rows", "10000"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("olay-gecmisi-")))
                .andReturn();
        String body = new String(res.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF" + "id,title,severity,status,category,occurred_at");
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(body).contains("2026-11-10 10:00:00");   // İstanbul saati
        assertThat(body).contains("Kök neden").doesNotContain("**").doesNotContain("/api/incidents/images/3");
        verify(service).exportIds(any(IncidentService.ListFilter.class), eq(List.of(5L)), any(), eq(IncidentController.EXPORT_MAX_ROWS));
        verify(auditService).recordAction(eq("INCIDENT_EXPORT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT"), eq("export"), anyString());
    }

    @Test
    @DisplayName("dışa aktarım JSON: sütunlar + satırlar + sayılar; tavan 10 000 (fazlası truncated) ve okuma 200'lük parçalarla")
    void exportJsonCapped() throws Exception {
        allowView();
        List<IncidentRecord> all = new ArrayList<>();
        for (int k = 1; k <= IncidentController.EXPORT_MAX_ROWS; k++) all.add(rec(k, "olay"));
        stubExport(10_250L, all);
        mvc.perform(get("/api/incidents/export").param("format", "json").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true"))
                .andExpect(jsonPath("$.data.columns[0]").value("id"))
                .andExpect(jsonPath("$.data.row_count").value(10_000))
                .andExpect(jsonPath("$.data.total").value(10_250))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.rows[0].title").value("olay"))
                .andExpect(jsonPath("$.data.rows[0].created_by").value("sre1"));
        // tek kimlik sorgusu + 50 × 200'lük birincil anahtar okuması (ofsetli sayfa, yeniden sayım yok)
        verify(service).exportIds(any(IncidentService.ListFilter.class), isNull(), any(), eq(IncidentController.EXPORT_MAX_ROWS));
        verify(service, times(50)).loadOrdered(argThat(l -> l.size() == 200));
        verify(service, never()).list(any(IncidentService.ListFilter.class), any(), any(), any());
    }

    @Test
    @DisplayName("dışa aktarım: dakikada 6'dan fazlası 429 RATE_LIMITED + Retry-After; aynı anda ikincisi 429 EXPORT_BUSY; bilinmeyen biçim 400 (hak düşmez)")
    void exportLimits() throws Exception {
        allowView();
        stubExport(0, List.of());
        MockHttpSession s = adminSession();
        s.setAttribute("username", "limit-user");   // dakikalık hak kişi başına — öteki testlerle paylaşılmasın
        mvc.perform(get("/api/incidents/export").param("format", "xml").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("format"));
        for (int k = 0; k < IncidentController.EXPORT_PER_MINUTE; k++) {
            mvc.perform(get("/api/incidents/export").session(s)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/incidents/export").session(s))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));

        @SuppressWarnings("unchecked")
        Set<String> running = (Set<String>) ReflectionTestUtils.getField(controller, "exportRunning");
        running.add("u:lider");
        try {
            mvc.perform(get("/api/incidents/export").session(teamAdminSession(5L)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("EXPORT_BUSY"));
        } finally {
            running.remove("u:lider");
        }
    }

    @Test
    @DisplayName("inceleme 2026-11-26: pod başına en çok 2 dışa aktarım — dolu → 429 EXPORT_BUSY + Retry-After 10, hiçbir şey "
            + "okunmaz, kişinin dakikalık hakkı İADE edilir; bitince yuva geri verilir")
    void exportPodSlots() throws Exception {
        allowView();
        stubExport(0, List.of());
        java.util.concurrent.Semaphore slots =
                (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(controller, "exportPodSlots");
        assertThat(slots.availablePermits()).isEqualTo(IncidentController.EXPORT_POD_SLOTS);
        MockHttpSession s = adminSession();
        s.setAttribute("username", "pod-busy-user");
        slots.acquire(IncidentController.EXPORT_POD_SLOTS);   // iki başka kişinin dışa aktarımı sürüyor
        try {
            for (int k = 0; k < IncidentController.EXPORT_PER_MINUTE + 2; k++) {
                mvc.perform(get("/api/incidents/export").session(s))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("EXPORT_BUSY"))
                        .andExpect(header().string("Retry-After", String.valueOf(IncidentController.EXPORT_POD_RETRY_SECONDS)))
                        .andExpect(jsonPath("$.retry_after_seconds").value((int) IncidentController.EXPORT_POD_RETRY_SECONDS));
            }
        } finally {
            slots.release(IncidentController.EXPORT_POD_SLOTS);
        }
        verify(service, never()).exportIds(any(IncidentService.ListFilter.class), any(), any(), anyInt());
        verify(auditService, never()).recordAction(eq("INCIDENT_EXPORT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
        // pod doluyken hak düşmedi: kişi hâlâ dakikada EXPORT_PER_MINUTE dışa aktarım yapabilir; her biri yuvayı geri verir
        for (int k = 0; k < IncidentController.EXPORT_PER_MINUTE; k++) {
            mvc.perform(get("/api/incidents/export").session(s)).andExpect(status().isOk());
            assertThat(slots.availablePermits()).isEqualTo(IncidentController.EXPORT_POD_SLOTS);
        }
        mvc.perform(get("/api/incidents/export").session(s))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(slots.availablePermits()).isEqualTo(IncidentController.EXPORT_POD_SLOTS);
    }

    @Test
    @DisplayName("inceleme 2026-11-26: dışa aktarım yazılırken hata olsa da pod yuvası geri verilir")
    void exportPodSlotReleasedOnError() throws Exception {
        allowView();
        when(service.exportIds(any(IncidentService.ListFilter.class), any(), any(), anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        java.util.concurrent.Semaphore slots =
                (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(controller, "exportPodSlots");
        MockHttpSession s = adminSession();
        s.setAttribute("username", "pod-error-user");
        mvc.perform(get("/api/incidents/export").session(s)).andExpect(status().is5xxServerError());
        assertThat(slots.availablePermits()).isEqualTo(IncidentController.EXPORT_POD_SLOTS);
    }

    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    /** Dışa aktarım planı: kimlikler + toplam, kayıtlar kimlik parçalarıyla (gerçek servisin iki adımı). */
    private void stubExport(long total, List<IncidentRecord> recs) {
        when(service.exportIds(any(IncidentService.ListFilter.class), any(), any(), anyInt()))
                .thenReturn(new IncidentService.ExportIds(total, recs.stream().map(IncidentRecord::getId).toList()));
        Map<Long, IncidentRecord> byId = new LinkedHashMap<>();
        for (IncidentRecord r : recs) byId.put(r.getId(), r);
        when(service.loadOrdered(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    /** Akışa geçmeden önceki gövde (ResponseEntity haritası) — {@link #jsonStreamIsByteIdenticalToTheOldBody} kâhini. */
    private static Map<String, Object> oldJsonBody(List<IncidentRecord> recs, long total) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int clipped = 0;
        for (IncidentRecord e : recs) {
            com.sitemonitor.service.incident.IncidentExportRows.Row r =
                    com.sitemonitor.service.incident.IncidentExportRows.row(e, u -> null, null, true);
            clipped += r.textClipped();
            rows.add(r.values());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", com.sitemonitor.service.incident.IncidentExportRows.COLUMNS);
        data.put("rows", rows);
        data.put("row_count", rows.size());
        data.put("total", total);
        data.put("max_rows", IncidentController.EXPORT_MAX_ROWS);
        data.put("truncated", total > rows.size());
        data.put("text_truncated", false);
        data.put("text_clipped", clipped);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    @Test
    @DisplayName("JSON gövdesi akışla yazılır ve eski ResponseEntity gövdesiyle BAYT BAYT aynı (global olmayan görüntüleyici de; no-store)")
    void jsonStreamIsByteIdenticalToTheOldBody() throws Exception {
        allowView();
        IncidentRecord a = rec(9, "Ödeme \"gecikme\" — =1+1");
        a.setTags("prod, ödeme");
        a.setAffectedCustomers(1200);
        a.setOutageRelated(true);
        a.setOutageId("INC-1");
        a.setResolvedAt("2026-11-10T09:00:00");
        IncidentRecord b = rec(10, "İkinci");
        b.setTechErrorCount(42L);
        stubExport(2, List.of(a, b));
        MvcResult res = mvc.perform(get("/api/incidents/export").param("format", "json").session(userSessionTeam(5L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(objectMapper.writeValueAsString(oldJsonBody(List.of(a, b), 2)));
        assertThat(body).contains("\"row_count\":2").contains("\"max_rows\":" + IncidentController.EXPORT_MAX_ROWS);
    }

    @Test
    @DisplayName("JSON'da kullanıcı kimliği yok: sütun adları ve gövdedeki hiçbir anahtar UserRefWire'ın çevirdiği adlardan değil — opak kimlik tavsiyesi bu akışa gerekmez")
    void jsonCarriesNoUserRefKeys() throws Exception {
        java.util.Set<String> forbidden = new java.util.HashSet<>(com.sitemonitor.service.userref.UserRefWire.KEYS);
        forbidden.addAll(com.sitemonitor.service.userref.UserRefWire.JSON_TEXT_KEYS);
        forbidden.addAll(List.of("resource_type", "resourceType", "resource_id", "resourceId"));
        assertThat(com.sitemonitor.service.incident.IncidentExportRows.COLUMNS).doesNotContainAnyElementsOf(forbidden);
        allowView();
        stubExport(1, List.of(rec(3, "x")));
        String body = mvc.perform(get("/api/incidents/export").param("format", "json").session(userSessionTeam(5L)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectKeys(objectMapper.readTree(body), keys);
        assertThat(keys).contains("success", "data", "columns", "rows", "created_by", "team");
        assertThat(keys).doesNotContainAnyElementsOf(forbidden);
    }

    private static void collectKeys(tools.jackson.databind.JsonNode n, java.util.Set<String> out) {
        if (n == null) return;
        if (n.isArray()) {
            for (tools.jackson.databind.JsonNode c : n) collectKeys(c, out);
        } else if (n.isObject()) {
            for (Map.Entry<String, tools.jackson.databind.JsonNode> e : n.properties()) {
                out.add(e.getKey());
                collectKeys(e.getValue(), out);
            }
        }
    }

    @Test
    @DisplayName("kişinin 'süren dışa aktarım' işareti gövde TÜMÜYLE yazılana dek tutulur (JSON ve CSV); yazma bitince bırakılır")
    void markerHeldWhileBodyIsWritten() throws Exception {
        allowView();
        stubExport(1, List.of(rec(1, "a")));
        @SuppressWarnings("unchecked")
        Set<String> running = (Set<String>) ReflectionTestUtils.getField(controller, "exportRunning");
        MockHttpSession s = adminSession();
        s.setAttribute("username", "marker-user");
        List<Boolean> seen = new ArrayList<>();
        for (String fmt : List.of("json", "csv")) {
            org.springframework.mock.web.MockHttpServletResponse resp = new org.springframework.mock.web.MockHttpServletResponse() {
                @Override
                public jakarta.servlet.ServletOutputStream getOutputStream() {
                    jakarta.servlet.ServletOutputStream real = super.getOutputStream();
                    return new jakarta.servlet.ServletOutputStream() {
                        @Override public boolean isReady() { return true; }
                        @Override public void setWriteListener(jakarta.servlet.WriteListener l) { }
                        @Override public void write(int b) throws java.io.IOException {
                            seen.add(running.contains("u:marker-user"));
                            real.write(b);
                        }
                        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                            seen.add(running.contains("u:marker-user"));
                            real.write(b, off, len);
                        }
                    };
                }
            };
            controller.export(fmt, null, Map.of("format", fmt), s, new org.springframework.mock.web.MockHttpServletRequest(), resp);
            assertThat(resp.getContentAsByteArray()).isNotEmpty();
        }
        assertThat(seen).isNotEmpty().allMatch(Boolean::booleanValue);
        assertThat(running).doesNotContain("u:marker-user");
    }

    // ── Seçenek yönetimi ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("seçenek kullanımı / yeniden adlandırma: takımı yönetmeyen (USER) 403, başka takımın yöneticisi 403; servis çağrılmaz")
    void optionManageScope() throws Exception {
        allowView();
        allowManage();
        mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").param("team_id", "5").session(userSessionTeam(5L)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/incidents/options").session(teamAdminSession(6L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"team_id\":5,\"from\":[\"ATM\"],\"to\":\"Atm\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).renameOptions(any(), any(), any(), any(), any());
        verify(service, never()).optionUsage(any(), any());
    }

    @Test
    @DisplayName("seçenek yönetimi: incidents.manage yoksa takım yöneticisi de 403")
    void optionManageNeedsPermission() throws Exception {
        allowView();
        mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").param("team_id", "5").session(teamAdminSession(5L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("takım yöneticisi: kullanım listesi; birleştirme INCIDENT_OPTIONS_MERGE, tek değer INCIDENT_OPTIONS_RENAME denetimi")
    void optionRenameAndMergeAudited() throws Exception {
        allowView();
        allowManage();
        when(service.optionUsage(any(), eq(5L))).thenReturn(new IncidentService.UsageReport(
                List.of(new IncidentService.OptionUsage("ATM", 3, true, false)), false, 3));
        mvc.perform(get("/api/incidents/options/usage").param("type", "channel").param("team_id", "5").session(teamAdminSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("CHANNEL"))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.rows_read").value(3))
                .andExpect(jsonPath("$.data.max_rows").value(IncidentService.OPTION_USAGE_MAX_ROWS))
                .andExpect(jsonPath("$.data.values[0].value").value("ATM"))
                .andExpect(jsonPath("$.data.values[0].records").value(3))
                .andExpect(jsonPath("$.data.values[0].team_option").value(true));

        when(service.renameOptions(eq("CHANNEL"), eq(5L), eq(List.of("IVR", "Çağrı")), eq("Sesli"), eq("lider"))).thenReturn(
                new IncidentService.RenameResult("CHANNEL", 5L, "Sesli", true, 4, Map.of("IVR", 3, "Çağrı", 1), List.of(1L, 2L), 1,
                        List.of("Çağrı")));
        mvc.perform(put("/api/incidents/options").session(teamAdminSession(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"team_id\":5,\"from\":[\"IVR\",\"Çağrı\"],\"to\":\"Sesli\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("merge"))
                .andExpect(jsonPath("$.data.records").value(4))
                .andExpect(jsonPath("$.data.global_kept[0]").value("Çağrı"));
        verify(auditService).recordAction(eq("INCIDENT_OPTIONS_MERGE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT_OPTION"), eq("CHANNEL"), anyString());

        when(service.renameOptions(eq("ERROR_CODE"), eq(5L), eq(List.of("E-1")), eq("E-01"), eq("lider"))).thenReturn(
                new IncidentService.RenameResult("ERROR_CODE", 5L, "E-01", false, 2, Map.of("E-1", 2), List.of(1L, 2L), 0, List.of()));
        mvc.perform(put("/api/incidents/options").session(teamAdminSession(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ERROR_CODE\",\"team_id\":5,\"from\":\"E-1\",\"to\":\"E-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("rename"));
        verify(auditService).recordAction(eq("INCIDENT_OPTIONS_RENAME"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT_OPTION"), eq("ERROR_CODE"), anyString());
    }

    @Test
    @DisplayName("inceleme 2026-11-26: kullanım okuması tavanı aştıysa yanıt truncated + okunan satır taşır")
    void optionUsageTruncated() throws Exception {
        allowManage();
        when(service.optionUsage(eq("CHANNEL"), eq(5L))).thenReturn(new IncidentService.UsageReport(
                List.of(new IncidentService.OptionUsage("ATM", 20_000, true, false)), true, IncidentService.OPTION_USAGE_MAX_ROWS));
        mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").param("team_id", "5").session(teamAdminSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.rows_read").value(IncidentService.OPTION_USAGE_MAX_ROWS));
    }

    @Test
    @DisplayName("inceleme 2026-11-26: geçersiz / eksik team_id KAPSAMDAN ÖNCE alan adlı 400 (eskiden 403 'yetkiniz yok'); "
            + "izin yoksa yine 403; bilinmeyen tür servisin alan adlı 400'ü; servis ve denetim çağrılmaz")
    void optionTeamIdValidation() throws Exception {
        // izin yok → geçersiz takım da 403 (izin önce)
        mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").param("team_id", "x").session(teamAdminSession(5L)))
                .andExpect(status().isForbidden());
        allowManage();
        for (String bad : new String[]{"x", "0", "-3", "1.5", "99999999999999999999"}) {
            mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").param("team_id", bad).session(teamAdminSession(5L)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("team_id"));
        }
        mvc.perform(get("/api/incidents/options/usage").param("type", "CHANNEL").session(adminSession()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("team_id"));
        for (String body : new String[]{
                "{\"type\":\"CHANNEL\",\"team_id\":\"abc\",\"from\":[\"ATM\"],\"to\":\"Atm\"}",
                "{\"type\":\"CHANNEL\",\"from\":[\"ATM\"],\"to\":\"Atm\"}",
                "{\"type\":\"CHANNEL\",\"team_id\":0,\"from\":[\"ATM\"],\"to\":\"Atm\"}",
                "{\"type\":\"CHANNEL\",\"team_id\":2.5,\"from\":[\"ATM\"],\"to\":\"Atm\"}"}) {
            mvc.perform(put("/api/incidents/options").session(adminSession()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value("team_id"));
        }
        // sayı ya da metin olarak geçerli kimlik kabul edilir
        when(service.renameOptions(any(), eq(5L), any(), any(), any())).thenReturn(
                new IncidentService.RenameResult("CHANNEL", 5L, "Atm", false, 0, Map.of("ATM", 0), List.of(), 0, List.of()));
        mvc.perform(put("/api/incidents/options").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"team_id\":\"5\",\"from\":[\"ATM\"],\"to\":\"Atm\"}"))
                .andExpect(status().isOk());
        verify(service, never()).optionUsage(any(), any());
        verify(service, times(1)).renameOptions(any(), any(), any(), any(), any());
        // bilinmeyen tür: servis alan adlı 400 verir, metin girdiyi yansıtmaz
        when(service.optionUsage(eq("<b>x</b>"), eq(5L))).thenThrow(
                new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("type", "Seçenek türü tanınmadı."));
        mvc.perform(get("/api/incidents/options/usage").param("type", "<b>x</b>").param("team_id", "5").session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("type"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<b>x</b>"))));
    }

    @Test
    @DisplayName("optionTeamId: sayı / rakam metni kabul, ondalık-tam sayı kabul, diğer her şey alan adlı hata")
    void optionTeamIdUnit() {
        assertThat(IncidentController.optionTeamId(5)).isEqualTo(5L);
        assertThat(IncidentController.optionTeamId(5L)).isEqualTo(5L);
        assertThat(IncidentController.optionTeamId(" 12 ")).isEqualTo(12L);
        assertThat(IncidentController.optionTeamId(7.0d)).isEqualTo(7L);
        for (Object bad : new Object[]{null, "", "x", "0", "-1", 2.5d, Double.POSITIVE_INFINITY, Double.NaN, 0, -4L,
                "1234567890123456789", "١٢"}) {
            assertThatThrownByField(() -> IncidentController.optionTeamId(bad));
        }
    }

    private static void assertThatThrownByField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOfSatisfying(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class,
                        e -> assertThat(e.getFields()).containsOnlyKeys("team_id"));
    }

    @Test
    @DisplayName("yeniden adlandırma doğrulama hatası → 400 alan adlı, denetim YOK")
    void renameValidation400() throws Exception {
        allowManage();
        when(service.renameOptions(any(), any(), any(), any(), any())).thenThrow(
                new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("to", "Yeni değeri yazın."));
        mvc.perform(put("/api/incidents/options").session(adminSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"team_id\":5,\"from\":[\"ATM\"],\"to\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("to"));
        verify(auditService, never()).recordAction(eq("INCIDENT_OPTIONS_RENAME"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }
}
