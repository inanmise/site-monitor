package com.sitemonitor.controller;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.dbquery.DbConnectionProfileService;
import com.sitemonitor.service.dbquery.DbConnectionSteps;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryRunner;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bağlantı denemesi adımları (dbq2 A3) ve bağlantı sağlık kartı (dbq2 A4) — GERÇEK bağlantı servisi, sahte depolar.
 *
 * <p>Değişmezler: yanıt HER ZAMAN altı adım taşır (deneme çalıştırıcıya ulaşmadıysa hepsi "atlandı"); eski alanlar aynen;
 * sağlık kartı yalnız bağlantıyı GÖREBİLENE (kendi takım kapsamı — erişim paylaşımı / 7/24 açmaz); "bilinmiyor" asla sıfır /
 * sorun yok yazılmaz; parola hiçbir yanıtta yok.
 */
@WebMvcTest(DbConnectionProfileController.class)
@Import(DbConnectionProfileService.class)
class DbConnectionStepsHealthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DbConnectionProfileRepository repo;
    @MockitoBean DbQueryMonitorRepository monitorRepo;
    @MockitoBean DbQueryCheckRepository checkRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean SecretCipher secretCipher;
    @MockitoBean SsrfGuard ssrfGuard;
    @MockitoBean DbQueryCheckerService checker;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateService certificateService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(teamRepo.existsById(anyLong())).thenReturn(true);
        when(certificateService.teamNamesById()).thenReturn(Map.of(1L, "Sipariş Takımı", 2L, "Ödeme Takımı"));
        when(checker.writableMode()).thenReturn("WARN");
    }

    private static MockHttpSession user(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ayse");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        return s;
    }

    private static DbConnectionProfile stored(long id, long team) {
        DbConnectionProfile p = new DbConnectionProfile();
        p.setId(id); p.setTeamId(team); p.setName("Sipariş veritabanı"); p.setDbType("POSTGRESQL");
        p.setHost("db-orders.internal"); p.setPort(5432); p.setDatabaseName("orders"); p.setUsername("ro_monitor");
        p.setPasswordEnc("enc:v1:ESKI-SIFRELI"); p.setSslMode("prefer"); p.setConnectTimeoutMs(10000);
        return p;
    }

    private static DbQueryMonitor mon(long id, String name, boolean active) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(id); m.setName(name); m.setActive(active); m.setTeamId(1L); m.setProfileId(5L);
        return m;
    }

    // ── A3: adımlar ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deneme: adımlar yanıtta (kod / süre / ayrıntı), eski alanlar aynen")
    void stepsInTestResponse() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        List<DbConnectionSteps.Step> steps = List.of(
                new DbConnectionSteps.Step("dns", "ok", 2L, null, "10.0.0.5"),
                new DbConnectionSteps.Step("tcp", "fail", 3000L, "TCP_TIMEOUT", "Veritabanı sunucusunun portuna bağlanılamadı"));
        when(checker.testConnection(any(), isNull())).thenReturn(new DbQueryRunner.ConnectionTest(false, 3002L, null,
                "Veritabanı sunucusunun portuna bağlanılamadı", "DB_UNREACHABLE",
                com.sitemonitor.service.dbquery.DbAccountPrivileges.Result.NOT_CHECKED, DbConnectionSteps.completed(steps)));
        mvc.perform(post("/api/monitoring/dbquery/connections/5/test").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.failure_reason").value("DB_UNREACHABLE"))
                .andExpect(jsonPath("$.data.duration_ms").value(3002))
                .andExpect(jsonPath("$.data.privileges.checked").value(false))
                .andExpect(jsonPath("$.data.steps.length()").value(6))
                .andExpect(jsonPath("$.data.steps[0].key").value("dns"))
                .andExpect(jsonPath("$.data.steps[0].status").value("ok"))
                .andExpect(jsonPath("$.data.steps[0].detail").value("10.0.0.5"))
                .andExpect(jsonPath("$.data.steps[1].code").value("TCP_TIMEOUT"))
                .andExpect(jsonPath("$.data.steps[1].ms").value(3000))
                .andExpect(jsonPath("$.data.steps[2].key").value("tls"))
                .andExpect(jsonPath("$.data.steps[2].status").value("skip"))
                .andExpect(jsonPath("$.data.steps[5].key").value("privileges"));
    }

    @Test
    @DisplayName("deneme çalıştırıcıya ulaşmadı (meşgul / eski biçim): altı adım da 'atlandı', neden üst düzey alanlarda; taslak deneme de adım taşır")
    void stepsAlwaysPresent() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        when(checker.testConnection(any(), any())).thenReturn(new DbQueryRunner.ConnectionTest(false, 0, null,
                "Veritabanı şu an meşgul", "DB_BUSY"));
        mvc.perform(post("/api/monitoring/dbquery/connections/test").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":5,\"name\":\"Yeni ad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failure_reason").value("DB_BUSY"))
                .andExpect(jsonPath("$.data.steps.length()").value(6))
                .andExpect(jsonPath("$.data.steps[*].status", everyItemIs("skip")));
    }

    private static org.hamcrest.Matcher<Iterable<? extends Object>> everyItemIs(String v) {
        return org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(v));
    }

    // ── A4: sağlık kartı ────────────────────────────────────────────────────

    @Test
    @DisplayName("sağlık: izlemeler (ada göre) + son durum, 24 sa çalıştırma / başarısızlık / başarı % / ort. süre; son hata kodu çalıştırma satırından; parola yok")
    void healthReport() throws Exception {
        DbConnectionProfile p = stored(5L, 1L);
        p.setLastOkAt("2026-11-05T09:00:00");
        p.setLastErrorAt("2026-11-05T08:30:00");
        p.setLastError("Veritabanı sunucusuna bağlanılamadı");
        p.setLastTestAt("2026-11-05T07:00:00");
        p.setLastTestOk(true);
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        when(monitorRepo.findByProfileId(5L)).thenReturn(List.of(mon(12L, "Zeta kuyruğu", true), mon(11L, "Bekleyen siparişler", false)));
        when(checkRepo.latestStatusFor(any())).thenReturn(List.<Object[]>of(new Object[] {11L, "BREACH"}));
        when(checkRepo.connectionRunStats(any(), anyString(), any())).thenReturn(List.<Object[]>of(new Object[] {200L, 8L, 312.6, 5L}));
        when(checkRepo.failureReasonsAt(any(), eq("2026-11-05T08:30:00"))).thenReturn(List.of("DB_TIMEOUT"));
        String body = mvc.perform(get("/api/monitoring/dbquery/connections/5/health").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitor_count").value(2))
                .andExpect(jsonPath("$.data.monitors[0].name").value("Bekleyen siparişler"))
                .andExpect(jsonPath("$.data.monitors[0].active").value(false))
                .andExpect(jsonPath("$.data.monitors[0].status").value("BREACH"))
                .andExpect(jsonPath("$.data.monitors[1].status").doesNotExist())
                .andExpect(jsonPath("$.data.runs_24h").value(200))
                .andExpect(jsonPath("$.data.failed_24h").value(8))
                .andExpect(jsonPath("$.data.success_pct").value(96.0))
                .andExpect(jsonPath("$.data.avg_duration_ms").value(313))
                .andExpect(jsonPath("$.data.last_ok_at").value("2026-11-05T09:00:00"))
                .andExpect(jsonPath("$.data.last_error.at").value("2026-11-05T08:30:00"))
                .andExpect(jsonPath("$.data.last_error.code").value("DB_TIMEOUT"))
                .andExpect(jsonPath("$.data.last_error.connection_class").value(true))
                .andExpect(jsonPath("$.data.other_failed_24h").value(5))
                .andExpect(jsonPath("$.data.unused").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ESKI-SIFRELI").doesNotContain("ro_monitor");
        verify(checkRepo).connectionRunStats(argThat(ids -> ids.containsAll(List.of(11L, 12L))), anyString(),
                eq(DbConnectionProfileService.CONNECTION_CODES));
    }

    @Test
    @DisplayName("D4: bağlantı sağlık kartı — kodlar bağlantı sınıfı (SQL / güvenlik reddi değil); son hata SQL ise connection_class false")
    void healthConnectionClass() {
        assertThat(DbConnectionProfileService.CONNECTION_CODES).containsExactlyInAnyOrder("DB_UNREACHABLE", "DB_DNS",
                "DB_TIMEOUT", "DB_AUTH", "DB_DRIVER", "DB_BLOCKED");
        DbConnectionProfile p = stored(5L, 1L);
        p.setLastErrorAt("2026-11-05T08:30:00");
        p.setLastError("sözdizimi hatası");
        DbQueryCheckRepository checks = mock(DbQueryCheckRepository.class);
        when(checks.latestStatusFor(any())).thenReturn(List.of());
        when(checks.connectionRunStats(any(), anyString(), any())).thenReturn(List.<Object[]>of(new Object[] {10L, 0L, 20.0, 4L}));
        when(checks.failureReasonsAt(any(), eq("2026-11-05T08:30:00"))).thenReturn(List.of("DB_SQL_ERROR"));
        Map<String, Object> h = DbConnectionProfileService.healthReport(p, List.of(mon(11L, "A", true)), checks, Instant.now());
        assertThat(h).containsEntry("failed_24h", 0L).containsEntry("other_failed_24h", 4L).containsEntry("success_pct", 100.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> le = (Map<String, Object>) h.get("last_error");
        assertThat(le).containsEntry("code", "DB_SQL_ERROR").containsEntry("connection_class", false);
    }

    @Test
    @DisplayName("sağlık: kullanılmayan bağlantı (izleme yok) → unused, sayım sorgusu yok, başarı % null; daha yeni başarısız deneme son hata olur")
    void healthUnusedAndFailedTest() throws Exception {
        DbConnectionProfile p = stored(5L, 1L);
        p.setLastErrorAt("2026-11-05T08:30:00");
        p.setLastError("eski hata");
        p.setLastTestAt("2026-11-05T10:00:00");
        p.setLastTestOk(false);
        p.setLastTestError("Kimlik doğrulanamadı");
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        when(monitorRepo.findByProfileId(5L)).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/dbquery/connections/5/health").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unused").value(true))
                .andExpect(jsonPath("$.data.monitor_count").value(0))
                .andExpect(jsonPath("$.data.runs_24h").value(0))
                .andExpect(jsonPath("$.data.success_pct").doesNotExist())
                .andExpect(jsonPath("$.data.last_error.at").value("2026-11-05T10:00:00"))
                .andExpect(jsonPath("$.data.last_error.message").value("Kimlik doğrulanamadı"))
                .andExpect(jsonPath("$.data.last_error.code").doesNotExist());
        verifyNoInteractions(checkRepo);
    }

    @Test
    @DisplayName("sağlık kapsamı: başka takım ve paylaşım okuyucusu 404; global admin görür")
    void healthScope() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        when(monitorRepo.findByProfileId(5L)).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/dbquery/connections/5/health").session(user(2L)))
                .andExpect(status().isNotFound());
        MockHttpSession grant = user(2L);
        grant.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(1L));
        mvc.perform(get("/api/monitoring/dbquery/connections/5/health").session(grant))
                .andExpect(status().isNotFound());
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("systemRole", "ADMIN");
        mvc.perform(get("/api/monitoring/dbquery/connections/5/health").session(admin))
                .andExpect(status().isOk());
        verify(permissionService, atLeastOnce()).require(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.read"), eq("view"));
    }

    @Test
    @DisplayName("sağlık: sayım okunamazsa runs / failed / başarı % null (asla 0 / sorun yok); durum okunamazsa status_read false")
    void healthUnreadable() {
        DbConnectionProfile p = stored(5L, 1L);
        DbQueryCheckRepository broken = mock(DbQueryCheckRepository.class);
        when(broken.latestStatusFor(any())).thenThrow(new IllegalStateException("db"));
        when(broken.connectionRunStats(any(), anyString(), any())).thenThrow(new IllegalStateException("db"));
        Map<String, Object> h = DbConnectionProfileService.healthReport(p, List.of(mon(11L, "A", true)), broken, Instant.now());
        assertThat(h.get("runs_24h")).isNull();
        assertThat(h.get("failed_24h")).isNull();
        assertThat(h.get("success_pct")).isNull();
        assertThat(h.get("status_read")).isEqualTo(false);
        assertThat(h.get("unused")).isEqualTo(false);
    }

    @Test
    @DisplayName("sağlık: en çok 100 izleme listelenir, toplam ayrı; son durum sorgusu yalnız listelenenler için (sabit sorgu sayısı)")
    void healthCapsMonitors() {
        DbConnectionProfile p = stored(5L, 1L);
        DbQueryCheckRepository checks = mock(DbQueryCheckRepository.class);
        when(checks.latestStatusFor(any())).thenReturn(List.of());
        when(checks.connectionRunStats(any(), anyString(), any())).thenReturn(List.<Object[]>of(new Object[] {0L, null, null, null}));
        List<DbQueryMonitor> many = new ArrayList<>();
        for (long i = 1; i <= 150; i++) many.add(mon(i, String.format("izleme %03d", i), true));
        Map<String, Object> h = DbConnectionProfileService.healthReport(p, many, checks, Instant.now());
        assertThat((List<?>) h.get("monitors")).hasSize(100);
        assertThat(h.get("monitor_count")).isEqualTo(150);
        assertThat(h.get("success_pct")).isNull();     // 0 çalıştırma → null
        assertThat(h.get("avg_duration_ms")).isNull();
        verify(checks, times(1)).latestStatusFor(argThat(ids -> ids.size() == 100));
        verify(checks, times(1)).connectionRunStats(argThat(ids -> ids.size() == 150), anyString(), any());
        verify(checks, never()).failureReasonsAt(any(), any());   // son hata yok → kod sorgusu yok
    }
}
