package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.diagnose.DnsDiagnosticsService;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory.Kind;
import com.sitemonitor.service.diagnose.PingDiagnosticsService;
import com.sitemonitor.service.diagnose.PortDiagnosticsService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ping / Port / DNS uçtan uca tanılama uçları (2026-10-05) — HTTP/keyword uçlarının aynası: yetki (diagnostics.run +
 * izlemenin ETKİN takımını işletebilme — envanter-türevi Port/DNS'te takım envanterden), 404 (yok / silinmiş), 400
 * (geçersiz hedef), 429 (kullanıcı VE izleme başına dakikada 6; aynı anda 4), 200 zarfı + run_id + denetim (sır yazılmaz),
 * geçmiş uçlarının tür + izleme ayrışması, oturumsuz 401. Uç tanılama servisinin DIŞINDA hiçbir şeye dokunmaz (kontrol
 * kaydı / alarm bağımlılığı yok — kapı NetDiagFindingsTest'te).
 */
@WebMvcTest(NetworkMonitorDiagnosticsController.class)
class NetworkMonitorDiagnosticsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired NetworkMonitorDiagnosticsController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PingMonitorRepository pingRepo;
    @MockitoBean PortMonitorRepository portRepo;
    @MockitoBean DnsMonitorRepository dnsRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PingDiagnosticsService pingDiagnostics;
    @MockitoBean PortDiagnosticsService portDiagnostics;
    @MockitoBean DnsDiagnosticsService dnsDiagnostics;
    @MockitoBean NetDiagnosticsHistory history;
    @MockitoBean AuditService auditService;

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(true);
        when(pingRepo.findById(41L)).thenReturn(Optional.of(ping(41L, 5L, "edge.example.test")));
        when(pingRepo.findById(43L)).thenReturn(Optional.of(ping(43L, 5L, " ")));
        when(pingRepo.findById(404L)).thenReturn(Optional.empty());
        when(portRepo.findById(51L)).thenReturn(Optional.of(port(51L, 5L, true, "db.example.test", 5432)));
        when(dnsRepo.findById(61L)).thenReturn(Optional.of(dns(61L, 5L, true, "www.example.test")));
        when(pingDiagnostics.diagnose(any(PingMonitor.class), anyBoolean())).thenAnswer(i -> result("PING_OK"));
        when(portDiagnostics.diagnose(any(PortMonitor.class))).thenAnswer(i -> result("CONNECT_REFUSED"));
        when(dnsDiagnostics.diagnose(any(DnsMonitor.class))).thenAnswer(i -> result("NXDOMAIN_AUTHORITATIVE"));
        when(history.save(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(88L);
    }

    private static PingMonitor ping(Long id, Long team, String host) {
        PingMonitor m = new PingMonitor();
        m.setId(id);
        m.setName("Ping " + id);
        m.setHost(host);
        m.setTeamId(team);
        return m;
    }

    private static PortMonitor port(Long id, Long team, boolean standalone, String host, int p) {
        PortMonitor m = new PortMonitor();
        m.setId(id);
        m.setName("Port " + id);
        m.setHost(host);
        m.setPort(p);
        m.setProtocol("TCP");
        m.setTeamId(team);
        m.setStandalone(standalone);
        return m;
    }

    private static DnsMonitor dns(Long id, Long team, boolean standalone, String domain) {
        DnsMonitor m = new DnsMonitor();
        m.setId(id);
        m.setName("DNS " + id);
        m.setDomain(domain);
        m.setRecordType("A");
        m.setTeamId(team);
        m.setStandalone(standalone);
        return m;
    }

    private static Map<String, Object> result(String code) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", code.endsWith("_OK") ? "ok" : "fail");
        verdict.put("code", code);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("verdict", verdict);
        data.put("transcript", "> CONNECT x Proxy-Authorization: ••••");
        return data;
    }

    private static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    private static MockHttpSession admin(Long userId) { return session("ADMIN", null, userId); }

    @Test
    @DisplayName("ping 200: run_id, traceroute gövdeden geçer, geçmiş PING türüyle yazılır, denetim PING_DIAGNOSTICS_RUN (döküm YAZILMAZ)")
    void ping_ok() throws Exception {
        mvc.perform(post("/api/monitoring/ping/41/diagnose").session(session("USER", 5L, 3L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"traceroute\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.run_id").value(88))
                .andExpect(jsonPath("$.data.verdict.code").value("PING_OK"));
        verify(pingDiagnostics).diagnose(any(PingMonitor.class), eq(true));
        verify(history).save(eq(Kind.PING), eq(41L), isNull(), any(), eq("user3"), eq(3L), eq(5L), any());
        verify(auditService).recordAction(eq("PING_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("PING_MONITOR"), eq("41"),
                argThat((String d) -> d != null && d.contains("PING_OK") && d.contains("traceroute") && !d.contains("Proxy-Authorization")),
                isNull());
    }

    @Test
    @DisplayName("ping gövdesiz → traceroute=false")
    void ping_noBody() throws Exception {
        mvc.perform(post("/api/monitoring/ping/41/diagnose").session(admin(1L))).andExpect(status().isOk());
        verify(pingDiagnostics).diagnose(any(PingMonitor.class), eq(false));
    }

    @Test
    @DisplayName("403: diagnostics.run yok / başka takımın izlemesi → servis çağrılmaz, denetim yazılmaz")
    void forbidden() throws Exception {
        mvc.perform(post("/api/monitoring/ping/41/diagnose").session(session("USER", 2L, 5L))).andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/port/51/diagnose").session(session("USER", 2L, 5L))).andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/dns/61/diagnose").session(session("USER", 2L, 5L))).andExpect(status().isForbidden());
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(post("/api/monitoring/dns/61/diagnose").session(admin(4L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("diagnostics.run")));
        mvc.perform(get("/api/monitoring/port/51/diagnose/history").session(admin(4L))).andExpect(status().isForbidden());
        verify(pingDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(portDiagnostics, never()).diagnose(any());
        verify(dnsDiagnostics, never()).diagnose(any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("envanter-türevi Port/DNS: ETKİN takım envanterden — envanterin takımı işletir, satırdaki eski takım işletemez")
    void inventoryDerived_effectiveTeam() throws Exception {
        when(portRepo.findById(52L)).thenReturn(Optional.of(port(52L, 5L, false, "inv.example.test", 443)));
        when(dnsRepo.findById(62L)).thenReturn(Optional.of(dns(62L, 5L, false, "inv.example.test")));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("inv.example.test");
        inv.setTeamId(2L);
        when(inventoryRepo.findByDomain("inv.example.test")).thenReturn(Optional.of(inv));
        mvc.perform(post("/api/monitoring/port/52/diagnose").session(session("USER", 2L, 7L))).andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/dns/62/diagnose").session(session("USER", 2L, 7L))).andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/port/52/diagnose").session(session("USER", 5L, 8L))).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/dns/62/diagnose/history").session(session("USER", 5L, 8L))).andExpect(status().isForbidden());
        verify(auditService).recordAction(eq("PORT_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("PORT_MONITOR"), eq("52"),
                argThat((String d) -> d != null && d.contains("CONNECT_REFUSED") && d.contains("443")), isNull());
        verify(auditService).recordAction(eq("DNS_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("DNS_MONITOR"), eq("62"),
                argThat((String d) -> d != null && d.contains("record_type")), isNull());
    }

    @Test
    @DisplayName("404 yok / silinmiş Port-DNS; 400 geçersiz hedef (İngilizce arayüzde İngilizce ileti)")
    void notFound_badTarget() throws Exception {
        mvc.perform(post("/api/monitoring/ping/404/diagnose").session(admin(6L))).andExpect(status().isNotFound());
        PortMonitor deleted = port(53L, 5L, true, "gone.example.test", 22);
        deleted.setDeletedAt("2026-10-01T00:00:00");
        when(portRepo.findById(53L)).thenReturn(Optional.of(deleted));
        mvc.perform(post("/api/monitoring/port/53/diagnose").session(admin(6L))).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/dns/999/diagnose").session(admin(6L))).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/ping/43/diagnose").session(admin(7L)).header("X-Lang", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("invalid")));
        verify(pingDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(portDiagnostics, never()).diagnose(any());
    }

    @Test
    @DisplayName("429: aynı izlemede dakikada 7. istek (farklı kullanıcılar)")
    void rateLimitedPerMonitor() throws Exception {
        for (long u = 100; u < 106; u++) {
            mvc.perform(post("/api/monitoring/dns/61/diagnose").session(admin(u))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/dns/61/diagnose").session(admin(106L)).header("X-Lang", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("per minute")));
        // Pencere TÜRE özgü: aynı numaralı ping izlemesi etkilenmez
        when(pingRepo.findById(61L)).thenReturn(Optional.of(ping(61L, 5L, "edge.example.test")));
        mvc.perform(post("/api/monitoring/ping/61/diagnose").session(admin(107L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("429: aynı kullanıcı dakikada 7. istek (farklı izlemeler, farklı türler); reddedilen istek izlemenin hakkını yemez")
    void rateLimitedPerUser() throws Exception {
        for (long id = 200; id < 206; id++) {
            when(pingRepo.findById(id)).thenReturn(Optional.of(ping(id, 5L, "h" + id + ".example.test")));
            mvc.perform(post("/api/monitoring/ping/" + id + "/diagnose").session(admin(900L))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/port/51/diagnose").session(admin(900L))).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/monitoring/port/51/diagnose").session(admin(901L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("429: aynı anda en çok 4 tanılama — izin kalmadıysa servis çağrılmaz")
    void rateLimitedInFlight() throws Exception {
        Semaphore inFlight = (Semaphore) ReflectionTestUtils.getField(controller, "inFlight");
        int drained = inFlight.drainPermits();
        try {
            mvc.perform(post("/api/monitoring/ping/41/diagnose").session(admin(950L)).header("X-Lang", "en"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error", containsString("Too many diagnostics")));
            verify(pingDiagnostics, never()).diagnose(any(), anyBoolean());
        } finally {
            inFlight.release(drained);
        }
        mvc.perform(post("/api/monitoring/ping/41/diagnose").session(admin(951L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("geçmiş: liste + kayıt türüyle okunur (çapraz tür açılamaz); başka izlemenin kaydı 404; kapsam dışı 403")
    void history() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5);
        row.put("verdict_code", "PING_OK");
        when(history.list(Kind.PING, 41L)).thenReturn(List.of(row));
        mvc.perform(get("/api/monitoring/ping/41/diagnose/history").session(admin(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verdict_code").value("PING_OK"));
        mvc.perform(get("/api/monitoring/ping/41/diagnose/history").session(session("USER", 2L, 9L)))
                .andExpect(status().isForbidden());

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", 9);
        when(history.get(Kind.PORT, 51L, 9L)).thenReturn(run);
        when(history.get(Kind.DNS, 61L, 9L)).thenReturn(null);   // geçmiş servisi türü tutmayan kaydı null döner
        mvc.perform(get("/api/monitoring/port/51/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run_id").value(9));
        // DNS ucu aynı numaralı kaydı DNS türüyle ister → yoktur → 404 (PORT kaydı DNS ucundan açılmaz)
        mvc.perform(get("/api/monitoring/dns/61/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isNotFound());
        verify(history).get(Kind.DNS, 61L, 9L);
        verify(history, never()).get(eq(Kind.PING), anyLong(), anyLong());
        mvc.perform(get("/api/monitoring/port/51/diagnose/history/9").session(session("USER", 2L, 11L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("oturumsuz istek 401 (AuthInterceptor) — uç PUBLIC değil")
    void requiresSession() throws Exception {
        mvc.perform(post("/api/monitoring/ping/41/diagnose")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitoring/dns/61/diagnose/history")).andExpect(status().isUnauthorized());
        verify(pingDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(history, never()).list(any(), anyLong());
    }
}
