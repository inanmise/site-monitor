package com.sitemonitor.controller;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kayıtlı veritabanı bağlantıları (2026-10-31, veritabanı sorgu izlemesi) — GERÇEK servis, sahte depolar.
 *
 * <p>Değişmezler: bağlantı TAKIM belgesidir (başka takım göremez / kullanamaz; erişim paylaşımı ve 7/24 açmaz); parola
 * ASLA yanıtta ya da denetim ayrıntısında yok; boş parola mevcut şifreli değeri korur; kullanılan bağlantı silinemez ve
 * takımı değiştirilemez (409 + izlemeler); deneme uçları satır verisi döndürmez ve oturum başına beklemelidir.
 */
@WebMvcTest(DbConnectionProfileController.class)
@Import(DbConnectionProfileService.class)
class DbConnectionProfileControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DbConnectionProfileRepository repo;
    @MockitoBean DbQueryMonitorRepository monitorRepo;
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
        when(secretCipher.encrypt(anyString())).thenAnswer(i -> "enc:v1:" + Integer.toHexString(i.getArgument(0).hashCode()));
        when(repo.save(any())).thenAnswer(i -> {
            DbConnectionProfile p = i.getArgument(0);
            if (p.getId() == null) p.setId(50L);
            return p;
        });
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MockHttpSession user(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ayse");
        s.setAttribute("displayName", "Ayşe Yılmaz (Sipariş / Uzman)");
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

    private static final String CREATE = "{\"team_id\":1,\"name\":\"Sipariş veritabanı\",\"db_type\":\"POSTGRESQL\","
            + "\"host\":\"db-orders.internal\",\"database_name\":\"orders\",\"username\":\"ro_monitor\","
            + "\"password\":\"Ac1k-Parola!\"}";

    @Test
    @DisplayName("oluşturma: parola şifreli saklanır, yanıtta ve denetimde YOK (yalnız password_set); tür varsayılanları (port 5432, SSL prefer)")
    void createEncryptsPasswordAndNeverReturnsIt() throws Exception {
        String body = mvc.perform(post("/api/monitoring/dbquery/connections").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(50))
                .andExpect(jsonPath("$.data.password_set").value(true))
                .andExpect(jsonPath("$.data.port").value(5432))
                .andExpect(jsonPath("$.data.ssl_mode").value("prefer"))
                .andExpect(jsonPath("$.data.health.state").value("untested"))
                .andExpect(jsonPath("$.data.created_by_name").value("Ayşe Yılmaz (Sipariş / Uzman)"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Ac1k-Parola").doesNotContain("enc:v1").doesNotContain("password_enc");

        org.mockito.ArgumentCaptor<DbConnectionProfile> cap = org.mockito.ArgumentCaptor.forClass(DbConnectionProfile.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getPasswordEnc()).startsWith("enc:v1:").doesNotContain("Ac1k-Parola");
        // Denetim ayrıntısında parola değeri yok
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("DBQUERY_CONNECTION_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("DBQUERY_CONNECTION"), eq("50"), detail.capture(), isNull());
        assertThat(String.valueOf(detail.getValue())).doesNotContain("Ac1k-Parola").doesNotContain("enc:v1");
    }

    @Test
    @DisplayName("oluşturma: başka takıma bağlantı eklenemez (403); alan hataları tek yanıtta (host biçimi, port, kullanıcı adı)")
    void createScopeAndFieldErrors() throws Exception {
        mvc.perform(post("/api/monitoring/dbquery/connections").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.replace("\"team_id\":1", "\"team_id\":2")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/dbquery/connections").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"x\",\"db_type\":\"POSTGRESQL\",\"host\":\"jdbc:postgresql://evil/db\","
                                + "\"port\":70000,\"database_name\":\"orders\",\"username\":\"a;b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.host").exists())
                .andExpect(jsonPath("$.fields.port").exists())
                .andExpect(jsonPath("$.fields.username").exists());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("oluşturma: ağ politikası reddi (döngü / meta veri) ve çözülemeyen ad host alanında hata")
    void createSsrfBlocked() throws Exception {
        doThrow(new SsrfGuard.BlockedException("izin verilmeyen")).when(ssrfGuard).validate("169.254.169.254");
        mvc.perform(post("/api/monitoring/dbquery/connections").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.replace("db-orders.internal", "169.254.169.254")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.host").value(org.hamcrest.Matchers.containsString("izin verilmiyor")));
        doThrow(new SsrfGuard.UnresolvableHostException("çözülemedi")).when(ssrfGuard).validate("yok.internal");
        mvc.perform(post("/api/monitoring/dbquery/connections").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.replace("db-orders.internal", "yok.internal")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.host").value(org.hamcrest.Matchers.containsString("DNS")));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("liste: yalnız kendi takımının bağlantıları; erişim paylaşımı bağlantıyı AÇMAZ; başka takım ?team_id 403")
    void listIsTeamScopedAndGrantDoesNotOpen() throws Exception {
        when(repo.findByTeamIdInOrderByNameAsc(any())).thenAnswer(i -> {
            java.util.Collection<Long> teams = i.getArgument(0);
            return teams.contains(1L) ? List.of(stored(5L, 1L)) : List.of();
        });
        mvc.perform(get("/api/monitoring/dbquery/connections").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].team_name").value("Sipariş Takımı"))
                .andExpect(jsonPath("$.data[0].can_edit").value(true))
                .andExpect(jsonPath("$.data[0].password_set").value(true));

        MockHttpSession grant = user(3L);
        grant.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(1L));
        grant.setAttribute(SessionScope.ATTR_GRANT_EDIT, List.of(1L));
        mvc.perform(get("/api/monitoring/dbquery/connections").session(grant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/monitoring/dbquery/connections").param("team_id", "1").session(grant))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("güncelleme: boş parola mevcut şifreli değeri KORUR; password_clear siler; başka takımın bağlantısı 404")
    void updateKeepsPasswordWhenBlank() throws Exception {
        DbConnectionProfile p = stored(5L, 1L);
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        mvc.perform(put("/api/monitoring/dbquery/connections/5").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Sipariş DB\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sipariş DB"))
                .andExpect(jsonPath("$.data.password_set").value(true));
        assertThat(p.getPasswordEnc()).isEqualTo("enc:v1:ESKI-SIFRELI");

        mvc.perform(put("/api/monitoring/dbquery/connections/5").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password_clear\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password_set").value(false));

        mvc.perform(put("/api/monitoring/dbquery/connections/5").session(user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ele geçirme\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("kullanılan bağlantı: silinemez ve takımı değiştirilemez (409 DBQUERY_CONNECTION_IN_USE + izlemeler)")
    void inUseConnectionCannotBeDeletedOrMoved() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(9L); m.setName("Bekleyen siparişler"); m.setProfileId(5L); m.setTeamId(1L); m.setActive(true);
        when(monitorRepo.findByProfileId(5L)).thenReturn(List.of(m));
        MockHttpSession both = user(1L);
        both.setAttribute("teamIds", List.of(1L, 2L));
        both.setAttribute("viewTeamIds", List.of(1L, 2L));

        mvc.perform(delete("/api/monitoring/dbquery/connections/5").session(both))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DBQUERY_CONNECTION_IN_USE"))
                .andExpect(jsonPath("$.monitors[0].name").value("Bekleyen siparişler"));
        mvc.perform(put("/api/monitoring/dbquery/connections/5").session(both)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DBQUERY_CONNECTION_IN_USE"));
        verify(repo, never()).delete(any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("silme: kullanılmayan bağlantı silinir ve denetlenir")
    void deleteUnused() throws Exception {
        DbConnectionProfile p = stored(5L, 1L);
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        when(monitorRepo.findByProfileId(5L)).thenReturn(List.of());
        mvc.perform(delete("/api/monitoring/dbquery/connections/5").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(repo).delete(p);
        verify(auditService).recordAction(eq("DBQUERY_CONNECTION_DELETE"), any(jakarta.servlet.http.HttpSession.class),
                eq("DBQUERY_CONNECTION"), eq("5"), anyString(), isNull());
    }

    @Test
    @DisplayName("deneme: kayıtlı bağlantı yalnız sahip takımca denenir; satır verisi yok; oturum başına 5 sn bekleme")
    void testSavedScopeAndCooldown() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        when(checker.testConnection(any(), isNull())).thenReturn(new DbQueryRunner.ConnectionTest(true, 37L, "PostgreSQL 16.2", null, null));
        MockHttpSession s = user(1L);
        mvc.perform(post("/api/monitoring/dbquery/connections/5/test").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.duration_ms").value(37))
                .andExpect(jsonPath("$.data.server_version").value("PostgreSQL 16.2"));
        mvc.perform(post("/api/monitoring/dbquery/connections/5/test").session(s))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").exists());
        mvc.perform(post("/api/monitoring/dbquery/connections/5/test").session(user(2L)))
                .andExpect(status().isNotFound());
        verify(checker, times(1)).testConnection(any(), any());
    }

    @Test
    @DisplayName("taslak deneme: yazılan parola yalnız denemeye geçer, kayıt / sağlık kolonları yazılmaz; kimliksiz kopya")
    void testDraftPassesPasswordOverrideOnly() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(stored(5L, 1L)));
        when(checker.testConnection(any(), any())).thenReturn(new DbQueryRunner.ConnectionTest(false, 5000L, null,
                "Bağlantı zaman aşımına uğradı", "DB_TIMEOUT"));
        mvc.perform(post("/api/monitoring/dbquery/connections/test").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"host\":\"db-orders-2.internal\",\"password\":\"Yeni-Parola\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.failure_reason").value("DB_TIMEOUT"));
        org.mockito.ArgumentCaptor<DbConnectionProfile> prof = org.mockito.ArgumentCaptor.forClass(DbConnectionProfile.class);
        verify(checker).testConnection(prof.capture(), eq("Yeni-Parola"));
        assertThat(prof.getValue().getId()).isNull();
        assertThat(prof.getValue().getHost()).isEqualTo("db-orders-2.internal");
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("sağlık durumu: hiç sinyal yok = untested (asla ok değil); en yeni sinyal kazanır; bağsız başarılı = unused")
    void healthStateNeverGreenWhenUnknown() {
        DbConnectionProfile p = stored(5L, 1L);
        assertThat(DbConnectionProfileService.health(p, true).get("state")).isEqualTo("untested");
        p.setLastErrorAt("2026-10-31T09:00:00");
        p.setLastOkAt("2026-10-31T08:00:00");
        assertThat(DbConnectionProfileService.health(p, true).get("state")).isEqualTo("failing");
        p.setLastTestAt("2026-10-31T10:00:00");
        p.setLastTestOk(true);
        assertThat(DbConnectionProfileService.health(p, true).get("state")).isEqualTo("ok");
        assertThat(DbConnectionProfileService.health(p, false).get("state")).isEqualTo("unused");
    }
}
