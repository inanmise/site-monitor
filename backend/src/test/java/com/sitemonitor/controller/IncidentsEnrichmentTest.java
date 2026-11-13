package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Olaylar zenginleştirmesi (2026-11-13, kullanıcı onaylı 14 madde) — sunucu tarafı: süzgeçler sunucuda (önem CSV,
 * takım, onaylı, takımsız, takımım, tekrarlayan, çözülme anı), tarih süzgeci İstanbul günü, kesin özet uç ucu, yorum
 * başına {@code can_delete}, takım duyarlı izleme bağı, zengin bağlam alanlarının kişi verisi kuralı ve dışa aktarım.
 * İzinler gerçek katalog varsayılanlarından.
 */
@WebMvcTest(IncidentsController.class)
@org.springframework.test.context.TestPropertySource(properties = "site.monitor.incidents.summary-cache-ms=0")
class IncidentsEnrichmentTest {

    private static final long OWN = 5L, FOREIGN = 9L;

    @Autowired MockMvc mvc;
    @Autowired IncidentsController controller;

    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean AlertCommentRepository commentRepo;
    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DomainMonitorRepository domainMonitorRepo;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    private static MockHttpSession session(String user, String role, List<Long> view, Long primary) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (primary != null) s.setAttribute("teamId", primary);
        return s;
    }

    private static MockHttpSession user() { return session("kisia", "USER", List.of(OWN), OWN); }
    private static MockHttpSession admin() { return session("admin", "ADMIN", null, null); }

    private static AlertEvent event(long id, Long teamId, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setTeamId(teamId); e.setDomain(domain);
        e.setAlertType("HTTP_DOWN"); e.setAlertLevel("CRITICAL"); e.setResolved(false);
        e.setCreatedAt("2026-11-12T03:00:00"); e.setMessage("HTTP 503");
        e.setAcknowledged(true); e.setAcknowledgedBy("Ayşe"); e.setAcknowledgedNote("Sağlayıcı bilgilendirildi, takip ediliyor");
        e.setRealertCount(3); e.setLastReAlertAt("2026-11-12T05:00:00"); e.setStormId(42L);
        return e;
    }

    private final AlertEvent ownEv = event(1L, OWN, "https://shared.example.com");
    private final AlertEvent foreignEv = event(2L, FOREIGN, "https://shared.example.com");

    private static boolean catalogAllows(Object session, String key, String action) {
        String role = (String) ((HttpSession) session).getAttribute("systemRole");
        Map<String, Boolean> actions = PermissionCatalog.defaultsFor(role).get(key);
        return actions != null && Boolean.TRUE.equals(actions.get(action));
    }

    @BeforeEach
    void setUp() {
        when(appSettings.getBoolean(eq(IncidentsController.VISIBLE_TO_ALL_KEY), anyBoolean())).thenReturn(true);
        when(permissionService.allows(any(HttpSession.class), anyString(), anyString()))
                .thenAnswer(i -> catalogAllows(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        doAnswer(i -> {
            if (!catalogAllows(i.getArgument(0), i.getArgument(1), i.getArgument(2)))
                throw new SecurityException("Bu işlem için yetkiniz yok: " + i.getArgument(1));
            return null;
        }).when(permissionService).require(any(HttpSession.class), anyString(), anyString());
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(ownEv));
        when(alertEventRepo.findById(2L)).thenReturn(Optional.of(foreignEv));
        when(alertEventRepo.findIncidentsFiltered(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ownEv, foreignEv)));
        when(alertEventRepo.countIncidentsByTypeFiltered(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.of());
        when(alertEventRepo.countIncidentsFiltered(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(7L);
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        Team a = new Team(); a.setId(OWN); a.setName("Takım A");
        Team b = new Team(); b.setId(FOREIGN); b.setName("Takım B");
        when(teamRepo.findAllById(any())).thenReturn(List.of(a, b));
        // AYNI URL'yi iki takım izliyor — olay kendi takımının izlemesine bağlanmalı
        com.sitemonitor.model.HttpMonitor mineMon = new com.sitemonitor.model.HttpMonitor();
        mineMon.setId(70L); mineMon.setName("Kendi kopyamız"); mineMon.setUrl("https://shared.example.com"); mineMon.setTeamId(OWN);
        com.sitemonitor.model.HttpMonitor theirs = new com.sitemonitor.model.HttpMonitor();
        theirs.setId(60L); theirs.setName("Onların kopyası"); theirs.setUrl("https://shared.example.com"); theirs.setTeamId(FOREIGN);
        when(httpMonitorRepo.findAll()).thenReturn(List.of(mineMon, theirs));
        when(alertEventRepo.countRecentByDomainAndType(any(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{ "https://shared.example.com", "HTTP_DOWN", 4L }));
    }

    @Test
    @DisplayName("liste: önem / takım / onaylı / takımsız / tekrarlayan / çözülme anı SUNUCUYA gider; server_facets=true")
    void list_serverFacets() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(admin())
                        .param("level", "critical,HIGH,bogus").param("teamId", "5").param("acknowledged", "true")
                        .param("teamless", "true").param("recurring", "true").param("resolvedSince", "2026-11-12T00:00:00")
                        .param("q", "50%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server_facets").value(true));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> levels = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> recur = ArgumentCaptor.forClass(String.class);
        verify(alertEventRepo).findIncidentsFiltered(any(), any(), any(), eq("2026-11-12T00:00:00"), any(), q.capture(),
                eq(true), levels.capture(), eq(Boolean.TRUE), eq(5L), eq(true), recur.capture(),
                anyBoolean(), anyBoolean(), any(), any(Pageable.class));
        assertThat(levels.getValue()).containsExactly("CRITICAL", "HIGH");
        assertThat(q.getValue()).isEqualTo("%50!%%");   // kaçışlı — "%" her şeyi döndürmez
        assertThat(recur.getValue()).isNotNull();
    }

    @Test
    @DisplayName("önem: dolu ama geçerli değer taşımayan süzgeç (kesişim boş → '-') HİÇBİR olayla eşleşmez, yok sayılmaz")
    void list_levelWithoutValidValueMatchesNothing() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(admin()).param("level", "-")).andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> levels = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo).findIncidentsFiltered(any(), any(), any(), any(), any(), any(), eq(true), levels.capture(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
        assertThat(levels.getValue()).containsExactly(IncidentsController.NO_LEVEL);
        clearInvocations(alertEventRepo);
        // boş parametre süzgeç değildir
        mvc.perform(get("/api/monitoring/incidents").session(admin()).param("level", "")).andExpect(status().isOk());
        verify(alertEventRepo).findIncidentsFiltered(any(), any(), any(), any(), any(), any(), eq(false), any(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("tarih süzgeci: yyyy-AA-gg → İstanbul günü (başlangıç 00:00 +03 = önceki gün 21:00 UTC; bitiş gününün SONU dahil)")
    void list_dayBoundsAreIstanbul() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(admin()).param("since", "2026-11-10").param("until", "2026-11-12"))
                .andExpect(status().isOk());
        verify(alertEventRepo).findIncidentsFiltered(any(), eq("2026-11-09T21:00:00"), eq("2026-11-12T20:59:59"), any(), any(),
                any(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
        // tam damga (eski istemci / derin bağlantı) aynen geçer
        assertThat(IncidentsController.sinceBound("2026-11-10T08:00:00", "since")).isEqualTo("2026-11-10T08:00:00");
        assertThat(IncidentsController.untilBound(null, "until")).isNull();
    }

    @Test
    @DisplayName("geçersiz gün → 400 VALIDATION_FAILED alanı since (sorgu çalışmaz)")
    void list_badDayIs400() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(admin()).param("since", "2026-13-45"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("since"));
        verify(alertEventRepo, never()).findIncidentsFiltered(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("takımım: oturumun birincil takımı; birincil takımı yoksa eşleşmeyen kimlik (boş küme)")
    void list_mineUsesPrimaryTeam() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(user()).param("mine", "true")).andExpect(status().isOk());
        verify(alertEventRepo).findIncidentsFiltered(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(),
                eq(OWN), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
        clearInvocations(alertEventRepo);
        mvc.perform(get("/api/monitoring/incidents").session(admin()).param("mine", "true")).andExpect(status().isOk());
        verify(alertEventRepo).findIncidentsFiltered(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(),
                eq(IncidentsController.NO_TEAM), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("takım duyarlı izleme bağı + kişi verisi kuralı: kendi satırda not / tekrar / fırtına / sahip takımlar; yabancı satırda yok")
    void list_rowContextAndPeopleRule() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(user()))
                .andExpect(status().isOk())
                // kendi satır: kendi takımının izlemesi (aynı URL'deki başka takımınki DEĞİL)
                .andExpect(jsonPath("$.data[0].monitor.monitor_id").value(70))
                .andExpect(jsonPath("$.data[0].monitor.name").value("Kendi kopyamız"))
                .andExpect(jsonPath("$.data[0].acknowledged_note").value("Sağlayıcı bilgilendirildi, takip ediliyor"))
                .andExpect(jsonPath("$.data[0].realert_count").value(3))
                .andExpect(jsonPath("$.data[0].storm_id").value(42))
                .andExpect(jsonPath("$.data[0].owner_team_ids[0]").value((int) OWN))
                .andExpect(jsonPath("$.data[0].can_notify").value(true))
                .andExpect(jsonPath("$.data[0].recurrence_7d").value(4))
                .andExpect(jsonPath("$.data[0].resolved_silently").value(false))
                // yabancı satır: izleme adı başka takımın izlemesi DEĞİL, kişi verisi / not / fırtına YOK
                .andExpect(jsonPath("$.data[1].monitor.name").value("Onların kopyası"))
                .andExpect(jsonPath("$.data[1].monitor.monitor_id").isEmpty())
                .andExpect(jsonPath("$.data[1].acknowledged_note").doesNotExist())
                .andExpect(jsonPath("$.data[1].acknowledged_by").doesNotExist())
                .andExpect(jsonPath("$.data[1].storm_id").doesNotExist())
                .andExpect(jsonPath("$.data[1].can_notify").value(false))
                .andExpect(jsonPath("$.data[1].recurrence_7d").value(4));
    }

    @Test
    @DisplayName("izleme seçimi: damgalı olay yalnız kendi takımının izlemesini alır; damgasız olay envanter takımınınkini, yoksa ilkini")
    void pickMonitorRule() {
        List<Object[]> cands = List.of(new Object[]{ "x", 1L, 9L }, new Object[]{ "y", 2L, 5L });
        assertThat(IncidentsController.pickMonitor(cands, 5L, null)[1]).isEqualTo(2L);
        assertThat(IncidentsController.pickMonitor(cands, 7L, null)).isNull();
        assertThat(IncidentsController.pickMonitor(cands, null, java.util.Set.of(5L))[1]).isEqualTo(2L);
        assertThat(IncidentsController.pickMonitor(cands, null, java.util.Set.of())[1]).isEqualTo(1L);
        assertThat(IncidentsController.pickMonitor(List.of(), 5L, null)).isNull();
        // Alarm bağlamının monitor_id'si önce: izleme başka takıma taşınmış olsa da olayı açan izleme bağlanır
        assertThat(IncidentsController.pickMonitor(cands, 1L, 7L, null)[1]).isEqualTo(1L);
        assertThat(IncidentsController.pickMonitor(cands, 99L, 5L, null)[1]).isEqualTo(2L);   // aday değil → takım kuralı
        assertThat(IncidentsController.pickMonitor(cands, 99L, 7L, null)).isNull();
    }

    @Test
    @DisplayName("özet: fresh anahtar başına en çok 5 sn'de bir taze hesaplatır (Yenile düğmesi DB'yi dövemez)")
    void summaryFreshIsThrottled() {
        String key = "test|" + System.nanoTime();
        assertThat(controller.allowFresh(key, false)).isFalse();
        assertThat(controller.allowFresh(key, true)).isTrue();
        assertThat(controller.allowFresh(key, true)).isFalse();
        assertThat(controller.allowFresh(key + "-b", true)).isTrue();
    }

    @Test
    @DisplayName("özet ucu: kesin sayılar (önem × onay), takımsız, takımım, 24 sa çözülen, MTTA/MTTR (sessiz hariç), en uzun açık, takım seçenekleri")
    void summary() throws Exception {
        when(alertEventRepo.countIncidentFacets(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(List.of(new Object[]{ "CRITICAL", true, 2L }, new Object[]{ "CRITICAL", false, 3L },
                        new Object[]{ "WARNING", false, 4L }));
        String recent = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().minusSeconds(3600));
        String recentPlus30 = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().minusSeconds(1800));
        // MTTR: biri 30 dk, biri sessiz (sayılmaz); MTTA: biri 30 dk
        when(alertEventRepo.findIncidentTimingRows(eq(Boolean.TRUE), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(List.of(new Object[]{ recent, null, recentPlus30, true, false },
                        new Object[]{ recent, null, recentPlus30, true, true }));
        when(alertEventRepo.findIncidentTimingRows(isNull(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[]{ recent, recentPlus30, null, false, null }));
        // Takım seçenekleri: açık olaylar (resolved=false, tarih yok) + son 90 günde açılıp kapanmışlar (resolved=true,
        // since dolu) — tüm geçmiş taranmaz
        when(alertEventRepo.countIncidentStampedTeams(eq(Boolean.FALSE), isNull(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.<Object[]>of(new Object[]{ OWN, 6L }));
        when(alertEventRepo.countIncidentStampedTeams(eq(Boolean.TRUE), notNull(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.<Object[]>of(new Object[]{ OWN, 1L }));
        when(alertEventRepo.countIncidentInventoryTeams(eq(Boolean.FALSE), isNull(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.<Object[]>of(new Object[]{ FOREIGN, null, 2L }));

        mvc.perform(get("/api/monitoring/incidents/summary").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("mine"))
                .andExpect(jsonPath("$.data.open").value(9))
                .andExpect(jsonPath("$.data.acknowledged").value(2))
                .andExpect(jsonPath("$.data.critical").value(5))
                .andExpect(jsonPath("$.data.teamless").value(7))
                .andExpect(jsonPath("$.data.mine").value(7))
                .andExpect(jsonPath("$.data.resolved_24h").value(7))
                .andExpect(jsonPath("$.data.mttr_24h_minutes").value(30.0))
                .andExpect(jsonPath("$.data.mttr_sample_7d").value(1))
                .andExpect(jsonPath("$.data.mtta_24h_minutes").value(30.0))
                .andExpect(jsonPath("$.data.longest_open.id").value(1))
                .andExpect(jsonPath("$.data.longest_open.name").value("Kendi kopyamız"))
                .andExpect(jsonPath("$.data.team_options[0].name").value("Takım A"))
                .andExpect(jsonPath("$.data.team_options[0].count").value(7))
                .andExpect(jsonPath("$.data.team_options[1].name").value("Takım B"));
        verify(alertEventRepo, never()).countIncidentStampedTeams(isNull(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    @DisplayName("özet: zamanlama okunamazsa MTTA/MTTR null (bilinmiyor) — sayılar yine döner")
    void summary_timingFailureIsUnknown() throws Exception {
        when(alertEventRepo.countIncidentFacets(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.of());
        when(alertEventRepo.findIncidentTimingRows(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("db"));
        mvc.perform(get("/api/monitoring/incidents/summary").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(0))
                .andExpect(jsonPath("$.data.mttr_7d_minutes").isEmpty())
                .andExpect(jsonPath("$.data.mine").isEmpty());   // birincil takım yok → bilinmiyor değil, "yok"
    }

    @Test
    @DisplayName("yorumlar: can_delete silme ucunun kuralı — yazar kendi yorumunu, USER başkasınınkini SİLEMEZ; global yönetici hepsini")
    void comments_canDelete() throws Exception {
        AlertComment mine = new AlertComment(); mine.setId(10L); mine.setAlertEventId(1L); mine.setAuthorUsername("kisia"); mine.setBody("benim");
        AlertComment other = new AlertComment(); other.setId(11L); other.setAlertEventId(1L); other.setAuthorUsername("baska"); other.setBody("onun");
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(1L)).thenReturn(List.of(mine, other));
        mvc.perform(get("/api/monitoring/incidents/1/comments").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].can_delete").value(true))
                .andExpect(jsonPath("$.data[1].can_delete").value(false))
                .andExpect(jsonPath("$.data[1].author_username").value("baska"));
        mvc.perform(get("/api/monitoring/incidents/1/comments").session(admin()))
                .andExpect(jsonPath("$.data[0].can_delete").value(true))
                .andExpect(jsonPath("$.data[1].can_delete").value(true));
    }

    @Test
    @DisplayName("dışa aktarım CSV: başlık + satır (yabancı satırda sahiplenen YOK), BOM, no-store, denetim INCIDENTS_EXPORT")
    void export_csv() throws Exception {
        var res = mvc.perform(get("/api/monitoring/incidents/export").session(user()).param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andReturn().getResponse();
        String csv = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿id,status,alert_level");
        String[] lines = csv.split("\r?\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains("Kendi kopyamız").contains("Ayşe");
        assertThat(lines[2]).contains("Onların kopyası").doesNotContain("Ayşe");
        verify(auditService).recordAction(eq("INCIDENTS_EXPORT"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("ALERT_EVENT"),
                eq("export"), contains("\"rows\":2"));
    }

    @Test
    @DisplayName("dışa aktarım JSON (Excel için) + kişi başına dakikada 6 sınırı → 429 RATE_LIMITED")
    void export_jsonAndRateLimit() throws Exception {
        MockHttpSession s = session("hizli", "USER", List.of(OWN), OWN);
        mvc.perform(get("/api/monitoring/incidents/export").session(s).param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.max_rows").value(IncidentsController.EXPORT_MAX_ROWS));
        for (int i = 1; i < IncidentsController.EXPORT_PER_MINUTE; i++)
            mvc.perform(get("/api/monitoring/incidents/export").session(s).param("format", "json")).andExpect(status().isOk());
        mvc.perform(get("/api/monitoring/incidents/export").session(s).param("format", "json"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/monitoring/incidents/export").session(s).param("format", "xls"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("damga ayrıştırma: kesirli / Z sonlu / bozuk")
    void parseStamp() {
        assertThat(IncidentsController.parseStamp("2026-11-12T03:00:00Z")).isEqualTo(java.time.Instant.parse("2026-11-12T03:00:00Z"));
        assertThat(IncidentsController.parseStamp("2026-11-12T03:00:00.123")).isEqualTo(java.time.Instant.parse("2026-11-12T03:00:00Z"));
        assertThat(IncidentsController.parseStamp("x")).isNull();
        assertThat(IncidentsController.parseStamp(null)).isNull();
    }
}
