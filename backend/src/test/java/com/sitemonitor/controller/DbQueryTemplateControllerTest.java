package com.sitemonitor.controller;

import com.sitemonitor.model.DbQueryTemplate;
import com.sitemonitor.repository.DbQueryTemplateRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.dbquery.DbQueryRules;
import com.sitemonitor.service.dbquery.DbQuerySqlGuard;
import com.sitemonitor.service.dbquery.DbQueryTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Takım sorgu şablonları (2026-10-31) — GERÇEK servis. Takım başına ≤ 50 (409 TEMPLATE_LIBRARY_LIMIT), ad takımda tekil
 * (409 DUPLICATE_RECORD), sorgu güvenlik denetiminden geçer (400 DBQUERY_SQL_REJECTED + ihlaller), takımı değiştirilemez,
 * başka takımın şablonu görünmez (404) — erişim paylaşımı şablon kitaplığını açmaz.
 */
@WebMvcTest(DbQueryTemplateController.class)
@Import(DbQueryTemplateService.class)
class DbQueryTemplateControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DbQueryTemplateRepository repo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateService certificateService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockedStatic<DbQuerySqlGuard> guard;
    private MockedStatic<DbQueryRules> rules;

    @BeforeEach
    void setUp() {
        guard = mockStatic(DbQuerySqlGuard.class);
        guard.when(() -> DbQuerySqlGuard.check(any(), any())).thenReturn(new DbQuerySqlGuard.Result(true, List.of(), List.of("orders")));
        rules = mockStatic(DbQueryRules.class);
        rules.when(() -> DbQueryRules.fromApi(any())).thenReturn(new DbQueryRules.Parsed(List.of(), List.of()));
        rules.when(() -> DbQueryRules.toJson(any())).thenReturn("[]");
        rules.when(() -> DbQueryRules.fromJson(any())).thenReturn(List.of());
        rules.when(() -> DbQueryRules.toApi(any())).thenReturn(List.of());
        when(teamRepo.existsById(anyLong())).thenReturn(true);
        when(certificateService.teamNamesById()).thenReturn(Map.of(1L, "Sipariş Takımı"));
        when(repo.save(any())).thenAnswer(i -> {
            DbQueryTemplate t = i.getArgument(0);
            if (t.getId() == null) t.setId(70L);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        guard.close();
        rules.close();
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

    private static DbQueryTemplate stored(long id, long team) {
        DbQueryTemplate t = new DbQueryTemplate();
        t.setId(id); t.setTeamId(team); t.setName("Bekleyen siparişler"); t.setSqlText("SELECT COUNT(*) FROM orders");
        t.setRuleLogic("ANY");
        return t;
    }

    private static final String BODY = "{\"team_id\":1,\"name\":\"Bekleyen siparişler\",\"db_type\":\"POSTGRESQL\","
            + "\"sql_text\":\"SELECT COUNT(*) FROM orders\",\"purpose\":\"Birikmeyi yakalar\",\"rules\":[]}";

    @Test
    @DisplayName("oluşturma: kaydedilir + denetlenir; takımın 50 şablonu varken 409 TEMPLATE_LIBRARY_LIMIT; aynı ad 409")
    void createLimitAndDuplicate() throws Exception {
        mvc.perform(post("/api/monitoring/dbquery/templates").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(70))
                .andExpect(jsonPath("$.data.rule_logic").value("ANY"))
                .andExpect(jsonPath("$.data.can_edit").value(true));
        verify(auditService).recordAction(eq("DBQUERY_TEMPLATE_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("DBQUERY_TEMPLATE"), eq("70"), anyString(), any());

        when(repo.countByTeamId(1L)).thenReturn((long) DbQueryTemplateService.MAX_PER_TEAM);
        mvc.perform(post("/api/monitoring/dbquery/templates").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_LIBRARY_LIMIT"));

        when(repo.countByTeamId(1L)).thenReturn(3L);
        when(repo.existsDuplicate(eq("Bekleyen siparişler"), eq(1L), any())).thenReturn(true);
        mvc.perform(post("/api/monitoring/dbquery/templates").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RECORD"))
                .andExpect(jsonPath("$.field").value("name"));
    }

    @Test
    @DisplayName("oluşturma: güvenlik reddi 400 DBQUERY_SQL_REJECTED + ihlal; başka takıma ekleme 403")
    void createGuardAndScope() throws Exception {
        guard.when(() -> DbQuerySqlGuard.check(any(), any())).thenReturn(new DbQuerySqlGuard.Result(false, List.of(
                new DbQuerySqlGuard.Violation("WRITE_STATEMENT", "UPDATE", "Yalnız okuma sorgusu çalıştırılabilir.", 1, 1)), List.of()));
        mvc.perform(post("/api/monitoring/dbquery/templates").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DBQUERY_SQL_REJECTED"))
                .andExpect(jsonPath("$.fields.sql_text").exists())
                .andExpect(jsonPath("$.violations[0].token").value("UPDATE"));
        mvc.perform(post("/api/monitoring/dbquery/templates").session(user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("güncelleme: takım değiştirilemez (400 team_id); başka takımın şablonu 404; erişim paylaşımı listeyi açmaz")
    void updateScope() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(stored(7L, 1L)));
        mvc.perform(put("/api/monitoring/dbquery/templates/7").session(user(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("team_id"));
        mvc.perform(put("/api/monitoring/dbquery/templates/7").session(user(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/monitoring/dbquery/templates/7").session(user(2L)))
                .andExpect(status().isNotFound());

        when(repo.findByTeamIdInOrderByNameAsc(any())).thenAnswer(i -> {
            java.util.Collection<Long> teams = i.getArgument(0);
            return teams.contains(1L) ? List.of(stored(7L, 1L)) : List.of();
        });
        MockHttpSession grant = user(3L);
        grant.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(1L));
        mvc.perform(get("/api/monitoring/dbquery/templates").session(grant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/monitoring/dbquery/templates").session(user(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].team_name").value("Sipariş Takımı"));
    }

    @Test
    @DisplayName("silme: kendi takımının şablonu silinir ve denetlenir")
    void deleteOwn() throws Exception {
        DbQueryTemplate t = stored(7L, 1L);
        when(repo.findById(7L)).thenReturn(Optional.of(t));
        mvc.perform(delete("/api/monitoring/dbquery/templates/7").session(user(1L)))
                .andExpect(status().isOk());
        verify(repo).delete(t);
        verify(auditService).recordAction(eq("DBQUERY_TEMPLATE_DELETE"), any(jakarta.servlet.http.HttpSession.class),
                eq("DBQUERY_TEMPLATE"), eq("7"), anyString(), any());
    }
}
