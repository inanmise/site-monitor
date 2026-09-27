package com.sitemonitor.controller;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Org geneli görünürlük (2026-09-26) — Tüm Sertifikalar listesi + sertifika detay penceresinin uçları.
 *
 * <p>Okuma genişler (liste {@code scope=all}, kart geçmişi, SSL önizlemesi, sağlık listesi); Pano
 * ({@code /certificates}) ve alarm listesi takım kapsamında kalır; iş başlatan/durum değiştiren uçlar
 * (anlık kontrol, sağlık tazeleme, yenileme onayı) başka takımın alan adını 403 ile reddeder.
 */
@WebMvcTest(CertificateController.class)
@Import({com.sitemonitor.service.CertificateHealthService.class, InventoryVisibility.class})
class InventoryOrgVisibilityCertificateTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean CertificateService certService;
    @MockitoBean CertificateCheckerService checkerService;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean NetworkOutageEventRepository networkOutageRepo;
    @MockitoBean com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;
    @MockitoBean com.sitemonitor.service.ExtendedHealthService extendedHealthService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;
    @MockitoBean com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;
    @MockitoBean com.sitemonitor.repository.AlertThresholdRepository thresholdRepo;
    @MockitoBean com.sitemonitor.service.CertificateAppLayerProbe appLayerProbe;
    @MockitoBean com.sitemonitor.service.ExecutiveStatsService executiveStatsService;

    private static final long OWN_TEAM = 5L, FOREIGN_TEAM = 9L;

    private CertificateDto ownDto, foreignDto;

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", OWN_TEAM);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    private static MockHttpSession teamAdmin() {
        MockHttpSession s = user();
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    private static CertificateInventory inv(String domain, long teamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(teamId); i.setPort(443); i.setActive(true);
        return i;
    }

    private static CertificateDto dto(String domain, long teamId, String teamName) {
        CertificateDto d = new CertificateDto();
        d.setDomain(domain); d.setTeamId(teamId); d.setTeamName(teamName); d.setStatus("valid");
        d.setIssuerCn("Example CA"); d.setDaysRemaining(40);
        return d;
    }

    private void switchOn(boolean on) {
        when(appSettings.getBoolean(eq(InventoryVisibility.SETTING_KEY), anyBoolean())).thenReturn(on);
    }

    @BeforeEach
    void setUp() {
        switchOn(true);
        when(permissionService.allows(any(HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(true);
        when(inventoryRepo.findByDomain("own.example.com")).thenReturn(Optional.of(inv("own.example.com", OWN_TEAM)));
        when(inventoryRepo.findByDomain("foreign.example.com")).thenReturn(Optional.of(inv("foreign.example.com", FOREIGN_TEAM)));
        ownDto = dto("own.example.com", OWN_TEAM, "Takım A");
        foreignDto = dto("foreign.example.com", FOREIGN_TEAM, "Takım B");
        when(certService.getPaginated(any(com.sitemonitor.dto.CertListQuery.class), any())).thenAnswer(i -> {
            java.util.Collection<Long> teams = i.getArgument(1);
            List<CertificateDto> rows = teams == null ? List.of(foreignDto, ownDto)
                    : teams.contains(OWN_TEAM) ? List.of(ownDto) : List.of();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("data", rows);
            out.put("pagination", Map.of("current_page", 1, "per_page", 20, "total", rows.size(), "total_pages", 1));
            out.put("facets", Map.of("all", rows.size()));
            out.put("shared", Map.of());
            return out;
        });
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(i -> new LinkedHashMap<>(Map.of("domain", i.getArgument(0), "status", "valid")));
    }

    // ══ Tüm Sertifikalar listesi ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("liste scope=all → servis TÜM envanter kapsamıyla (null) çağrılır; satır başına can_manage; önbellek DTO'su değişmez")
    void listAll_orgWide_withPerRowCanManage() throws Exception {
        mvc.perform(get("/api/certificates/list").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.visible_to_all").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.data[0].team_name").value("Takım B"))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[1].can_manage").value(true))
                .andExpect(jsonPath("$.facets.all").value(2));
        verify(certService).getPaginated(any(com.sitemonitor.dto.CertListQuery.class), isNull());
        // Önbellekteki (paylaşılan) nesnelere yazılmadı — bayrak yalnız kopyada.
        assertThat(ownDto.getCanManage()).isNull();
        assertThat(foreignDto.getCanManage()).isNull();
    }

    @Test
    @DisplayName("liste scope=mine (varsayılan) → görüş kapsamı [5]; facet'ler de o kümeden")
    void listMine_teamScoped() throws Exception {
        mvc.perform(get("/api/certificates/list").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].can_manage").value(true))
                .andExpect(jsonPath("$.facets.all").value(1));
        verify(certService).getPaginated(any(com.sitemonitor.dto.CertListQuery.class), eq(List.of(OWN_TEAM)));
    }

    @Test
    @DisplayName("ayar KAPALI → liste ve CSV scope=all isteğinde de takım kapsamında kalır")
    void listAll_switchOff_staysMine() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/certificates/list").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.visible_to_all").value(false))
                .andExpect(jsonPath("$.data.length()").value(1));
        verify(certService).getPaginated(any(com.sitemonitor.dto.CertListQuery.class), eq(List.of(OWN_TEAM)));
        when(certService.exportCsv(any(), any(), any())).thenReturn("domain\r\n");
        mvc.perform(get("/api/certificates/export.csv").param("scope", "all").session(user())).andExpect(status().isOk());
        verify(certService).exportCsv(any(), eq(List.of(OWN_TEAM)), any());
    }

    @Test
    @DisplayName("CSV scope=all → listeyle AYNI küme (null kapsam); denetim ayrıntısı kapsamı taşır")
    void exportAll_sameScopeAsList() throws Exception {
        when(certService.exportCsv(any(), any(), any())).thenReturn("domain\r\nforeign.example.com\r\n");
        mvc.perform(get("/api/certificates/export.csv").param("scope", "all").session(user())).andExpect(status().isOk());
        verify(certService).exportCsv(any(), isNull(), any());
        verify(auditService).recordAction(eq("CERT_LIST_EXPORT"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("CERTIFICATE"), eq("export"),
                org.mockito.ArgumentMatchers.contains("\"scope\":\"all\""));
    }

    @Test
    @DisplayName("Pano (/certificates) ayar açıkken de takım kapsamlı — can_manage alanı yok")
    void dashboard_staysTeamScoped() throws Exception {
        when(certService.getAllLatestForTeams(List.of(OWN_TEAM))).thenReturn(List.of(ownDto));
        mvc.perform(get("/api/certificates").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].can_manage").doesNotExist());
        verify(certService).getAllLatestForTeams(List.of(OWN_TEAM));
        verify(certService, never()).getAllLatestForTeams(isNull());
    }

    // ══ Detay penceresi: okuma uçları ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kart geçmişi / SSL önizlemesi / sağlık listesi: başka takımın alan adı OKUNUR (ayar açık)")
    void detailReads_foreignAllowed() throws Exception {
        when(certService.getHistory("foreign.example.com", 30)).thenReturn(List.of(foreignDto));
        mvc.perform(get("/api/history/foreign.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("foreign.example.com"));
        mvc.perform(get("/api/check-preview/foreign.example.com").session(user()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/certificates/foreign.example.com/health").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("foreign.example.com"));
        // Önizleme salt okunur: hiçbir sonuç kaydedilmez, önbellek boşaltılmaz.
        verify(certService, never()).saveResult(any());
        verify(certService, never()).evictAllCaches();
    }

    @Test
    @DisplayName("ayar KAPALI → aynı okuma uçları bugünkü gibi reddeder (geçmiş 403, sağlık 404)")
    void detailReads_switchOff_rejected() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/history/foreign.example.com").session(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/check-preview/foreign.example.com").session(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/certificates/foreign.example.com/health").session(user())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("alarm listesi (/history/{domain}/alerts) takım kapsamlı kalır — ayar açıkken de 403")
    void domainAlerts_stayTeamScoped() throws Exception {
        mvc.perform(get("/api/history/foreign.example.com/alerts").session(user())).andExpect(status().isForbidden());
        verify(alertEventRepo, never()).findByDomainOrderByCreatedAtDesc(anyString());
    }

    // ══ Detay penceresi: iş başlatan / durum değiştiren uçlar ═════════════════════════════════════

    @Test
    @DisplayName("anlık kontrol + sağlık tazeleme + yenileme onayı: başka takımın alan adı 403 (hiçbir şey koşmaz/yazılmaz)")
    void workTriggers_foreignRejected() throws Exception {
        mvc.perform(get("/api/check/foreign.example.com").session(teamAdmin())).andExpect(status().isForbidden());
        mvc.perform(post("/api/certificates/foreign.example.com/health/refresh").session(teamAdmin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/certificates/foreign.example.com/health/confirm-renewal").session(teamAdmin()))
                .andExpect(status().isForbidden());
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
        verify(certService, never()).saveResult(any());
        verify(latestCheckRepo, never()).save(any());
    }

    @Test
    @DisplayName("kendi takımında anlık kontrol + sağlık tazeleme çalışmaya devam eder")
    void workTriggers_ownAllowed() throws Exception {
        mvc.perform(get("/api/check/own.example.com").session(user())).andExpect(status().isOk());
        mvc.perform(post("/api/certificates/own.example.com/health/refresh").session(user())).andExpect(status().isOk());
        verify(certService, org.mockito.Mockito.times(2)).saveResult(any());
    }
}
