package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocCallLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.*;
import com.sitemonitor.service.noc.NocCallListService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 7/24 arama kaydı uçlarının YETKİ MATRİSİ — gerçek {@link NocCallLogService} ile (depolar mock):
 * başka takımdan 7/24 operatörü yazar ve okur; takım üyesi yalnız okur; kapsamlı müdür (rol ADMIN, takım kapsamlı —
 * ADMIN matris satırı izni gösterse de) yazamaz; izinsiz kullanıcı 403; kapsam dışı üye 403. Yanıtlar snake_case,
 * telefon hiçbir yanıtta yok.
 */
@WebMvcTest(controllers = NocCallLogController.class)
@Import(NocCallLogService.class)
class NocCallLogControllerTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mvc;

    @MockitoBean NocCallLogRepository repo;
    @MockitoBean AlertEventRepository alertRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean NocCallListService callLists;
    @MockitoBean PermissionService permissionService;
    @MockitoBean ActivityLogService activityLog;
    @MockitoBean AuditService auditService;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession global, scoped, memberA, memberB, nocB, audit;
    private final Map<String, Boolean> nocGrant = new HashMap<>();
    private final List<NocCallLog> store = new ArrayList<>();

    private static MockHttpSession session(String user, String role, List<Long> view) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", view);
        return s;
    }

    private static String iso(Instant i) { return ISO.format(i); }

    private static AlertEvent alert(long id, long team) {
        AlertEvent a = new AlertEvent();
        a.setId(id); a.setTeamId(team); a.setDomain("t" + team + ".example.com"); a.setAlertType("HTTP_DOWN");
        a.setAlertLevel("CRITICAL"); a.setCreatedAt(iso(Instant.now().minus(Duration.ofHours(2))));
        return a;
    }

    private static Map<String, Object> person(long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", id); m.put("display_name", name); m.put("title", "Uzman"); m.put("has_phone", true);
        m.put("is_member", true);
        return m;
    }

    private NocCallLog stored(long id, long alertId, String by, Instant at) {
        NocCallLog c = new NocCallLog();
        c.setId(id); c.setAlertId(alertId); c.setTeamId(TEAM_A); c.setContactedName("Kişi A"); c.setOutcome("REACHED");
        c.setChannel("PHONE"); c.setContactedAt(iso(at)); c.setCreatedAt(iso(at)); c.setCreatedBy(by); c.setCreatedByName(by);
        store.add(c);
        return c;
    }

    @BeforeEach
    void setUp() {
        global = session("admin", "ADMIN", null);
        scoped = session("mudur", "ADMIN", List.of(TEAM_A));
        memberA = session("kisia", "USER", List.of(TEAM_A));
        memberB = session("kisib", "USER", List.of(TEAM_B));
        nocB = session("noc2", "TEAM_ADMIN", List.of(TEAM_B));   // 7/24 operatörü, KENDİ takımı B
        audit = session("denetci", "AUDIT", null);

        nocGrant.clear();
        nocGrant.put("ADMIN", true);
        nocGrant.put("TEAM_ADMIN", true);
        when(permissionService.allows(any(HttpSession.class), eq("noc_calls.write"), eq("edit")))
                .thenAnswer(i -> nocGrant.getOrDefault(((HttpSession) i.getArgument(0)).getAttribute("systemRole"), false));
        when(permissionService.allows(any(HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(true);

        when(alertRepo.findById(50L)).thenReturn(Optional.of(alert(50, TEAM_A)));
        when(alertRepo.findById(60L)).thenReturn(Optional.of(alert(60, TEAM_B)));
        when(alertRepo.findById(99L)).thenReturn(Optional.empty());
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());

        Team a = new Team(); a.setId(TEAM_A); a.setName("Takım A");
        when(teamRepo.findById(TEAM_A)).thenReturn(Optional.of(a));
        when(callLists.callListDto(TEAM_A)).thenReturn(List.of(person(11, "Kişi A")));
        AppUser m = new AppUser(); m.setId(20L); m.setDisplayName("Kişi M"); m.setPhone("0555"); m.setActive(true);
        when(callLists.resolveManager(any())).thenReturn(Optional.of(m));
        when(callLists.activeMembers(TEAM_A)).thenReturn(List.of());

        store.clear();
        when(repo.findByAlertIdOrderByContactedAtDescIdDesc(anyLong())).thenAnswer(i -> store.stream()
                .filter(c -> c.getAlertId().equals(i.getArgument(0))).toList());
        when(repo.findById(anyLong())).thenAnswer(i -> store.stream().filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        when(repo.save(any(NocCallLog.class))).thenAnswer(i -> {
            NocCallLog c = i.getArgument(0);
            c.setId(1000L + store.size());
            store.add(c);
            return c;
        });
    }

    // ── Okuma ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET noc-calls: başka takımdan 7/24 operatörü, takım üyesi, kapsamlı müdür (kendi takımı), denetçi okur; kapsam dışı 403")
    void readMatrix() throws Exception {
        stored(1, 50, "noc2", Instant.now().minus(Duration.ofMinutes(3)));
        for (MockHttpSession s : List.of(nocB, memberA, scoped, audit, global))
            mvc.perform(get("/api/alerts/50/noc-calls").session(s)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].contacted_name").value("Kişi A"))
                    .andExpect(jsonPath("$.data[0].contacted_at").exists())
                    .andExpect(jsonPath("$.data[0].created_by_name").value("noc2"))
                    .andExpect(jsonPath("$.data[0].contactedName").doesNotExist());
        mvc.perform(get("/api/alerts/50/noc-calls").session(memberB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/alerts/60/noc-calls").session(scoped)).andExpect(status().isForbidden());
        mvc.perform(get("/api/alerts/99/noc-calls").session(nocB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/alerts/50/noc-calls")).andExpect(status().isUnauthorized());
    }

    // ── Yazma ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST: 7/24 operatörü başka takımın uyarısına yazar (snake_case, telefonsuz, denetim + etkinlik); üye/kapsamlı müdür/izinsiz denetçi 403")
    void writeMatrix() throws Exception {
        String body = "{\"contactedUserId\":11,\"outcome\":\"REACHED\",\"note\":\"Kişi A bakıyor\"}";
        mvc.perform(post("/api/alerts/50/noc-calls").session(nocB).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contacted_user_id").value(11))
                .andExpect(jsonPath("$.data.contacted_name").value("Kişi A"))
                .andExpect(jsonPath("$.data.outcome").value("REACHED"))
                .andExpect(jsonPath("$.data.channel").value("PHONE"))
                .andExpect(jsonPath("$.data.note").value("Kişi A bakıyor"))
                .andExpect(jsonPath("$.data.can_delete").value(true))
                .andExpect(jsonPath("$.data.delete_until").exists())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
        verify(auditService).recordAction(eq("NOC_CALL_LOG_ADD"), any(HttpSession.class), eq("ALERT_EVENT"), eq("50"), anyString(), isNull());
        verify(activityLog).recordLifecycle(eq("HTTP"), isNull(), anyString(), anyString(), eq(TEAM_A),
                eq("NOC_CALL_LOGGED"), eq("noc2"), anyString());

        for (MockHttpSession s : List.of(memberA, memberB, scoped, audit))
            mvc.perform(post("/api/alerts/50/noc-calls").session(s).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts/50/noc-calls").session(global).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactedName\":\"Kişi E\",\"outcome\":\"ESCALATED\",\"channel\":\"TEAMS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contacted_user_id").doesNotExist());
        verify(repo, times(2)).save(any());
    }

    @Test
    @DisplayName("POST doğrulama: sonuç yok / kişi yok / gelecek / telefon adı → 400")
    void validation() throws Exception {
        String future = iso(Instant.now().plus(Duration.ofHours(1)));
        for (String bad : List.of("{\"contactedName\":\"Kişi A\"}", "{\"outcome\":\"REACHED\"}",
                "{\"contactedName\":\"Kişi A\",\"outcome\":\"REACHED\",\"contactedAt\":\"" + future + "\"}",
                "{\"contactedName\":\"0555 000 00 00\",\"outcome\":\"REACHED\"}",
                "{\"contactedUserId\":999,\"outcome\":\"REACHED\"}"))
            mvc.perform(post("/api/alerts/50/noc-calls").session(nocB).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("DELETE: giren 15 dk içinde; başkası ve süresi dolan 403; global yönetici her zaman")
    void deleteMatrix() throws Exception {
        stored(1, 50, "noc2", Instant.now().minus(Duration.ofMinutes(5)));
        stored(2, 50, "noc2", Instant.now().minus(Duration.ofMinutes(20)));
        stored(3, 50, "noc2", Instant.now().minus(Duration.ofMinutes(1)));
        MockHttpSession otherNoc = session("noc3", "TEAM_ADMIN", List.of(TEAM_B));
        mvc.perform(delete("/api/alerts/50/noc-calls/3").session(otherNoc)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/alerts/50/noc-calls/3").session(memberA)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/alerts/50/noc-calls/2").session(nocB)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/alerts/50/noc-calls/1").session(nocB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(1));
        mvc.perform(delete("/api/alerts/50/noc-calls/2").session(global)).andExpect(status().isOk());
        mvc.perform(delete("/api/alerts/60/noc-calls/3").session(global)).andExpect(status().isNotFound());   // başka uyarı
        verify(repo, times(2)).delete(any(NocCallLog.class));
        verify(auditService, times(2)).recordAction(eq("NOC_CALL_LOG_DELETE"), any(HttpSession.class), eq("ALERT_EVENT"),
                eq("50"), anyString(), isNull());
    }

    @Test
    @DisplayName("GET noc-contacts: yazabilen alır (kaynak etiketli, telefon YOK); üye ve kapsamlı müdür 403")
    void contacts() throws Exception {
        mvc.perform(get("/api/alerts/50/noc-contacts").session(nocB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].user_id").value(11))
                .andExpect(jsonPath("$.data[0].source").value("CALL_LIST"))
                .andExpect(jsonPath("$.data[1].source").value("MANAGER"))
                .andExpect(jsonPath("$.data[1].has_phone").value(true))
                .andExpect(jsonPath("$.data[1].phone").doesNotExist())
                .andExpect(jsonPath("$.data[*].source", not(hasItem("PHONE"))));
        for (MockHttpSession s : List.of(memberA, scoped))
            mvc.perform(get("/api/alerts/50/noc-contacts").session(s)).andExpect(status().isForbidden());
    }
}
