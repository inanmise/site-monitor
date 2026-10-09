package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.noc.NocCallListService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Olaylar — Sahiplen/Çöz penceresinin bağlam kartı (2026-09-28): kendi satır Alarm Geçmişi'yle AYNI alanları taşır
 * (mevcut sahip, e-posta/push teslim sayıları, 7/24 arama sayısı), başka ekibin satırı TAŞIMAZ; sayılar sayfa başına
 * kaynak başına TEK toplu sorguyla ve yalnız kendi satırların kimlikleriyle gelir.
 *
 * <p>7/24 özeti GERÇEK {@link NocCallLogService#decorate} ile (depo mock) — alan adı ve sayım kuralı üretimdeki gibi.
 * Yanıt gerçek tel biçiminde (snake_case) okunur; yabancı satırda "anahtar YOK" JSON ağacından doğrulanır (jsonPath'in
 * {@code doesNotExist}'i null değerli anahtarı da geçirirdi).
 */
@WebMvcTest(IncidentsController.class)
@Import(NocCallLogService.class)
class IncidentsActionContextTest {

    private static final long OWN = 5L, FOREIGN = 9L;
    /** Kendi satırda eklenen, yabancı satırda HİÇ bulunmaması gereken alanlar. */
    private static final List<String> OWNER_FIELDS = List.of(
            "acknowledged_by", "acknowledged_at", "email_sent_count", "email_failed_count", "push_summary", "noc_call_count");

    @Autowired MockMvc mvc;

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
    // Teslim + 7/24 özeti (Alarm Geçmişi'nin aynı sorguları)
    @MockitoBean NotificationLogRepository notificationLogRepo;
    @MockitoBean UserPushDeliveryRepository userPushDeliveryRepo;
    @MockitoBean NocCallLogRepository nocCallLogRepo;
    @MockitoBean NocCallListService callLists;
    @MockitoBean ActivityLogService activityLog;
    // Auth + metrics interceptor bağımlılıkları (WebMvc slice)
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "kisia");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN)));
        s.setAttribute("manageTeamIds", new ArrayList<>());
        s.setAttribute("teamId", OWN);
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN)));
        return s;
    }

    private static AlertEvent event(long id, Long teamId, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setTeamId(teamId); e.setDomain(domain);
        e.setAlertType("HTTP_DOWN"); e.setAlertLevel("CRITICAL"); e.setResolved(false);
        e.setCreatedAt("2026-09-28T03:00:00"); e.setMessage("HTTP 503");
        return e;
    }

    /** Kendi takımının olayı — Kişi A sahiplenmiş. */
    private AlertEvent ownEv;
    /** Başka takımın olayı — Kişi B sahiplenmiş; bu ad, sayılar ve aramalar çağırana SIZMAMALI. */
    private AlertEvent foreignEv;
    /** Damgasız ENVANTER olayı (ACCESSIBILITY); envanter SY başka takım, UG KENDİ takım → kendi satır (sahiplenilmemiş,
     *  bildirimi yok). Bağımsız izleme türü (HTTP_DOWN) OLAMAZ: 2026-10-09'dan beri onun sahibi yalnız damgalı takımdır. */
    private AlertEvent ugEv;

    private static boolean catalogAllows(Object session, String key, String action) {
        String role = (String) ((HttpSession) session).getAttribute("systemRole");
        Map<String, Boolean> actions = PermissionCatalog.defaultsFor(role).get(key);
        return actions != null && Boolean.TRUE.equals(actions.get(action));
    }

    @BeforeEach
    void setUp() {
        ownEv = event(1L, OWN, "https://own.example.com");
        ownEv.setAcknowledged(true); ownEv.setAcknowledgedBy("kisi.a"); ownEv.setAcknowledgedAt("2026-09-28T03:05:00");
        foreignEv = event(2L, FOREIGN, "https://foreign.example.com");
        foreignEv.setAcknowledged(true); foreignEv.setAcknowledgedBy("kisi.b"); foreignEv.setAcknowledgedAt("2026-09-28T03:06:00");
        foreignEv.setResolvedBy("Kişi E");   // kişi çözdü → başka ekibe AD verilmez
        ugEv = event(3L, null, "ug.example.com");
        ugEv.setAlertType("ACCESSIBILITY");   // envanter gibi yönlenen tür (AlertOwnership)

        when(appSettings.getBoolean(eq(IncidentsController.VISIBLE_TO_ALL_KEY), anyBoolean())).thenReturn(true);
        when(permissionService.allows(any(HttpSession.class), anyString(), anyString()))
                .thenAnswer(i -> catalogAllows(i.getArgument(0), i.getArgument(1), i.getArgument(2)));

        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(ownEv));
        when(alertEventRepo.findById(2L)).thenReturn(Optional.of(foreignEv));
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(foreignEv, ownEv, ugEv)));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.of());
        when(alertEventRepo.countIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(3L);
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());

        CertificateInventory ug = new CertificateInventory();
        ug.setDomain("ug.example.com"); ug.setTeamId(FOREIGN); ug.setUgTeamId(OWN);
        when(inventoryRepo.findByDomainIn(any())).thenReturn(List.of(ug));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        Team a = new Team(); a.setId(OWN); a.setName("Takım A");
        Team b = new Team(); b.setId(FOREIGN); b.setName("Takım B");
        when(teamRepo.findAllById(any())).thenReturn(List.of(a, b));

        // Özet depoları yabancı satır (2) için de veri döndürür: sorgu yanlışlıkla onu içerseydi DTO kapısı yine tutmalı.
        when(notificationLogRepo.countByAlertIds(any())).thenReturn(List.of(
                new Object[]{ 1L, 4L, 1L },
                new Object[]{ 2L, 7L, 0L }));
        when(userPushDeliveryRepo.countByAlertEventIdInGroupByStatus(any())).thenReturn(List.of(
                new Object[]{ 1L, "SENT", 2L },
                new Object[]{ 1L, "FAILED", 1L },
                new Object[]{ 1L, "SKIPPED_QUIET_HOURS", 1L },
                new Object[]{ 2L, "SENT", 5L }));
        when(nocCallLogRepo.summarizeByAlertIds(any())).thenReturn(List.of(
                new Object[]{ 1L, "Kişi C", "REACHED", "2026-09-28T03:10:00", 2L },
                new Object[]{ 2L, "Kişi D", "NO_ANSWER", "2026-09-28T03:11:00", 3L }));
    }

    private JsonNode body(org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
        String s = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(s);
    }

    private static JsonNode row(JsonNode data, long id) {
        for (JsonNode r : data) if (r.path("id").asLong() == id) return r;
        throw new AssertionError("satır yok: " + id);
    }

    private static void assertNoOwnerFields(JsonNode r) {
        for (String f : OWNER_FIELDS) assertThat(r.has(f)).as("yabancı satırda %s olmamalı", f).isFalse();
    }

    private static Set<Long> idsOf(Collection<Long> c) { return new HashSet<>(c); }

    @Test
    @DisplayName("kendi satır: sahip + e-posta/push teslim + 7/24 sayısı Alarm Geçmişi adlarıyla; yabancı satırda bu anahtarlar HİÇ yok")
    void ownRowCarriesContext_foreignRowDoesNot() throws Exception {
        JsonNode data = body(get("/api/monitoring/incidents").param("scope", "all").session(user())).path("data");
        assertThat(data.size()).isEqualTo(3);

        JsonNode own = row(data, 1);
        assertThat(own.path("can_manage").asBoolean()).isTrue();
        assertThat(own.path("acknowledged_by").asText()).isEqualTo("kisi.a");
        assertThat(own.path("acknowledged_at").asText()).isEqualTo("2026-09-28T03:05:00");
        assertThat(own.path("email_sent_count").asLong()).isEqualTo(4L);
        assertThat(own.path("email_failed_count").asLong()).isEqualTo(1L);
        assertThat(own.path("push_summary").path("sent").asLong()).isEqualTo(2L);
        assertThat(own.path("push_summary").path("failed").asLong()).isEqualTo(1L);
        assertThat(own.path("push_summary").path("skipped").asLong()).isEqualTo(1L);
        assertThat(own.path("noc_call_count").asLong()).isEqualTo(2L);

        // Envanter UG üzerinden kendi satır, bildirimi/araması yok: Alarm Geçmişi gibi 0/0 ve 0; push satırı yoksa alan yok.
        JsonNode ug = row(data, 3);
        assertThat(ug.path("can_manage").asBoolean()).isTrue();
        assertThat(ug.has("acknowledged_by")).isTrue();
        assertThat(ug.path("acknowledged_by").isNull()).isTrue();
        assertThat(ug.path("email_sent_count").asLong()).isEqualTo(0L);
        assertThat(ug.path("email_failed_count").asLong()).isEqualTo(0L);
        assertThat(ug.has("push_summary")).isFalse();
        assertThat(ug.path("noc_call_count").asLong()).isEqualTo(0L);

        // Başka ekibin olayı: onaylı olduğu (salt okunur rozet) görünür, ama KİMİN sahiplendiği ve sayılar görünmez.
        JsonNode foreign = row(data, 2);
        assertThat(foreign.path("can_manage").asBoolean()).isFalse();
        assertThat(foreign.path("acknowledged").asBoolean()).isTrue();
        assertNoOwnerFields(foreign);
        assertThat(foreign.toString()).doesNotContain("kisi.b").doesNotContain("Kişi D").doesNotContain("Kişi E");
        assertThat(foreign.path("resolved_by").isNull()).isTrue();
    }

    @Test
    @DisplayName("resolved_by: sahiplenilmeyen satırda kişi gizlenir, sistem kapanışı (system / inventory_* / Sistem (…)) kalır")
    void resolvedBy_personHiddenOnForeignRows_systemTokensKept() {
        assertThat(IncidentsController.resolvedByFor("Kişi E", false)).isNull();
        assertThat(IncidentsController.resolvedByFor("Kişi E", true)).isEqualTo("Kişi E");
        assertThat(IncidentsController.resolvedByFor("inventory_delete", false)).isEqualTo("inventory_delete");
        assertThat(IncidentsController.resolvedByFor("system", false)).isEqualTo("system");
        assertThat(IncidentsController.resolvedByFor("Sistem (bakım penceresi — sessiz kapanış)", false)).startsWith("Sistem");
        assertThat(IncidentsController.resolvedByFor(null, false)).isNull();
    }

    @Test
    @DisplayName("toplu: sayfa başına kaynak başına TEK sorgu (e-posta, push, 7/24) ve yalnız KENDİ satırların kimlikleriyle")
    void summariesAreBatchedAndOwnedOnly() throws Exception {
        body(get("/api/monitoring/incidents").param("scope", "all").session(user()));
        verify(notificationLogRepo, times(1)).countByAlertIds(argThat(ids -> idsOf(ids).equals(Set.of(1L, 3L))));
        verify(userPushDeliveryRepo, times(1)).countByAlertEventIdInGroupByStatus(argThat(ids -> idsOf(ids).equals(Set.of(1L, 3L))));
        verify(nocCallLogRepo, times(1)).summarizeByAlertIds(argThat(ids -> idsOf(ids).equals(Set.of(1L, 3L))));
        verifyNoMoreInteractions(notificationLogRepo, userPushDeliveryRepo, nocCallLogRepo);
    }

    @Test
    @DisplayName("sayfada kendi satır yok (yalnız başka ekiplerin olayları): özet sorgusu HİÇ atılmaz, alan yok")
    void foreignOnlyPage_noSummaryQueries() throws Exception {
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(foreignEv)));
        JsonNode data = body(get("/api/monitoring/incidents").param("scope", "others").session(user())).path("data");
        assertNoOwnerFields(row(data, 2));
        verifyNoInteractions(notificationLogRepo, userPushDeliveryRepo, nocCallLogRepo);
    }

    @Test
    @DisplayName("tekil olay (derin bağlantı): kendi olayda aynı alanlar, başka ekibin olayında yok ve sorgu atılmaz")
    void singleIncident_sameRule() throws Exception {
        JsonNode own = body(get("/api/monitoring/incidents/1").session(user())).path("data");
        assertThat(own.path("acknowledged_by").asText()).isEqualTo("kisi.a");
        assertThat(own.path("email_sent_count").asLong()).isEqualTo(4L);
        assertThat(own.path("email_failed_count").asLong()).isEqualTo(1L);
        assertThat(own.path("push_summary").path("sent").asLong()).isEqualTo(2L);
        assertThat(own.path("noc_call_count").asLong()).isEqualTo(2L);
        verify(notificationLogRepo).countByAlertIds(argThat(ids -> idsOf(ids).equals(Set.of(1L))));

        clearInvocations(notificationLogRepo, userPushDeliveryRepo, nocCallLogRepo);
        JsonNode foreign = body(get("/api/monitoring/incidents/2").session(user())).path("data");
        assertThat(foreign.path("can_manage").asBoolean()).isFalse();
        assertNoOwnerFields(foreign);
        verifyNoInteractions(notificationLogRepo, userPushDeliveryRepo, nocCallLogRepo);
    }

    @Test
    @DisplayName("özet sorgusu düşerse liste yine döner; o kaynağın alanı YAZILMAZ (uydurma 0 yok), diğerleri gelir")
    void summaryFailure_omitsOnlyThatField() throws Exception {
        when(notificationLogRepo.countByAlertIds(any())).thenThrow(new IllegalStateException("db down"));
        JsonNode own = row(body(get("/api/monitoring/incidents").param("scope", "all").session(user())).path("data"), 1);
        assertThat(own.has("email_sent_count")).isFalse();
        assertThat(own.has("email_failed_count")).isFalse();
        assertThat(own.path("push_summary").path("sent").asLong()).isEqualTo(2L);
        assertThat(own.path("noc_call_count").asLong()).isEqualTo(2L);
        assertThat(own.path("acknowledged_by").asText()).isEqualTo("kisi.a");
    }
}
