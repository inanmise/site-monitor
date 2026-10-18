package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorTeamTemplateService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Takım şablon kütüphanesi uçları (2026-10-18, D17): kütüphane takımın KENDİ işi — üye / yönetici okur ve yazar, AUDIT ve
 * kapsamlı müdür (görüş) okur ama yazamaz, başka takım ve YALNIZ erişim paylaşımıyla gören 404; şablon katı doğrulanır
 * (400 template); değişmeyen PUT denetlenmez; eski sürüm 409. Servis sahte — kapı mantığı gerçek.
 */
@WebMvcTest(TechErrorTeamTemplateController.class)
class TechErrorTeamTemplateControllerTest {

    private static final long A = 1L, B = 2L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean TechErrorTeamTemplateService library;
    @MockitoBean TeamRepository teams;
    @MockitoBean PermissionService permissions;
    @MockitoBean AuditService auditService;

    private static Team team(long id) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + id);
        t.setActive(true);
        return t;
    }

    private static TechErrorTeamTemplate entry(long id, long team, String name, boolean def) {
        TechErrorTeamTemplate e = new TechErrorTeamTemplate();
        e.setId(id);
        e.setTeamId(team);
        e.setName(name);
        e.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        e.setTemplateLang("tr");
        e.setSectionCount(8);
        e.setFieldCount(20);
        e.setSlotCount(17);
        e.setDefaultEntry(def);
        e.setUpdatedAt(Instant.parse("2026-10-17T08:00:00Z"));
        e.setUpdatedByName("Ayşe Yılmaz");
        e.setCreatedById(55L);
        e.setUpdatedById(55L);
        e.setVersion(3L);
        return e;
    }

    private static TechErrorTeamTemplateRepository.Meta meta(TechErrorTeamTemplate e) {
        return new TechErrorTeamTemplateRepository.Meta() {
            public Long getId() { return e.getId(); }
            public Long getTeamId() { return e.getTeamId(); }
            public String getName() { return e.getName(); }
            public Boolean getDefaultEntry() { return e.isDefaultEntry(); }
            public String getTemplateLang() { return e.getTemplateLang(); }
            public int getSectionCount() { return e.getSectionCount(); }
            public int getFieldCount() { return e.getFieldCount(); }
            public int getSlotCount() { return e.getSlotCount(); }
            public Instant getCreatedAt() { return e.getCreatedAt(); }
            public String getCreatedByName() { return e.getCreatedByName(); }
            public Instant getUpdatedAt() { return e.getUpdatedAt(); }
            public String getUpdatedByName() { return e.getUpdatedByName(); }
            public Long getVersion() { return e.getVersion(); }
        };
    }

    @BeforeEach
    void setUp() {
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(true);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.crud"), eq("edit"))).thenAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            return !"AUDIT".equals(s.getAttribute("systemRole"));
        });
        when(teams.findById(any())).thenAnswer(inv -> Optional.of(team((Long) inv.getArgument(0))));
        TechErrorTeamTemplate def = entry(5, A, "Mobil kanallar şablonu (Dijital Kanallar / Bireysel Bankacılık)", true);
        TechErrorTeamTemplate other = entry(6, A, "POS", false);
        when(library.list(A)).thenReturn(List.of(meta(def), meta(other)));
        when(library.get(eq(A), eq(5L))).thenReturn(def);
        when(library.get(eq(A), eq(6L))).thenReturn(other);
        when(library.get(eq(B), anyLong())).thenThrow(new NoSuchElementException("Kütüphane şablonu bulunamadı"));
    }

    private static MockHttpSession user(String role, long team, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("userId", 50L);
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", manage);
        return s;
    }

    /** Kapsamlı müdür: takımı GÖRÜR (viewTeamIds) ama üyesi / yöneticisi değil → okur, yazamaz. */
    private static MockHttpSession viewer(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("userId", 70L);
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of());
        s.setAttribute("manageTeamIds", List.of());
        return s;
    }

    private static MockHttpSession audit() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "denetci");
        s.setAttribute("userId", 60L);
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }

    /** Kendi takımı B; A takımını YALNIZ erişim paylaşımıyla (EDIT) görür. */
    private static MockHttpSession grantee() {
        MockHttpSession s = user("USER", B, List.of());
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(A)));
        s.setAttribute(SessionScope.ATTR_GRANT_EDIT, new ArrayList<>(List.of(A)));
        return s;
    }

    private static String body(Map<String, Object> m) throws Exception {
        return JSON.writeValueAsString(m);
    }

    private static Map<String, Object> tplBody(String name, Object makeDefault) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("template", TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr")));
        if (makeDefault != null) b.put("default", makeDefault);
        return b;
    }

    @Test
    @DisplayName("liste: üye kendi takımını okur — gövde yok, varsayılan + sayımlar + ad; kullanıcı kimliği yok; can_edit")
    void listOwnTeam() throws Exception {
        mvc.perform(get("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1))
                .andExpect(jsonPath("$.data.max").value(20))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.templates.length()").value(2))
                .andExpect(jsonPath("$.data.templates[0].is_default").value(true))
                .andExpect(jsonPath("$.data.templates[0].section_count").value(8))
                .andExpect(jsonPath("$.data.templates[0].updated_by_name").value("Ayşe Yılmaz"))
                .andExpect(jsonPath("$.data.templates[0].template").doesNotExist())
                .andExpect(jsonPath("$.data.templates[0].updated_by_id").doesNotExist())
                .andExpect(jsonPath("$.data.templates[0].can_edit").value(true));
        mvc.perform(get("/api/tech-error-reports/teams/1/templates/5").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.sections").isArray())
                .andExpect(jsonPath("$.data.created_by_id").doesNotExist());
    }

    @Test
    @DisplayName("kapsam: başka takım 404 TEAM_NOT_FOUND; YALNIZ paylaşımla gören 404 (kütüphane paylaşılmaz); AUDIT ve kapsamlı müdür okur (can_edit false), yazamaz (403); global yönetici yazar")
    void scope() throws Exception {
        mvc.perform(get("/api/tech-error-reports/teams/1/templates").session(user("USER", B, List.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        mvc.perform(get("/api/tech-error-reports/teams/1/templates").session(grantee()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/teams/1/templates/5").session(grantee()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(grantee())
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Paylaşım", null))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tech-error-reports/teams/1/templates").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false))
                .andExpect(jsonPath("$.data.templates[0].can_edit").value(false));
        mvc.perform(get("/api/tech-error-reports/teams/1/templates").session(viewer(A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(audit())
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Denetçi", null))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/tech-error-reports/teams/1/templates/6").session(viewer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "x", "version", 3))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tech-error-reports/teams/1/templates/6").session(viewer(A)))
                .andExpect(status().isForbidden());
        verify(library, never()).create(anyLong(), any(), any(), anyBoolean(), any());
        verify(library, never()).delete(anyLong(), anyLong());
        when(library.create(eq(B), anyString(), any(), anyBoolean(), any())).thenReturn(entry(9, B, "Global", false));
        MockHttpSession g = new MockHttpSession();
        g.setAttribute("authenticated", Boolean.TRUE);
        g.setAttribute("userId", 1L);
        g.setAttribute("systemRole", "ADMIN");
        mvc.perform(post("/api/tech-error-reports/teams/2/templates").session(g)
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Global", null))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ekleme: şablon KATI doğrulanır (bozuk → 400 template, servis çağrılmaz); geçerli → 200 + TECH_ERROR_TEAM_TEMPLATE_CREATE; servisin 409 kodları aynen")
    void create() throws Exception {
        Map<String, Object> bad = tplBody("Bozuk", null);
        bad.put("template", Map.of("sections", List.of(Map.of("id", "a b", "kind", "nope"))));
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("template"));
        verify(library, never()).create(anyLong(), any(), any(), anyBoolean(), any());
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Yeni", "evet"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("default"));
        when(library.create(eq(A), eq("Yeni"), any(), eq(true), eq(50L))).thenReturn(entry(7, A, "Yeni", true));
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Yeni", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.is_default").value(true));
        verify(auditService).recordAction(eq("TECH_ERROR_TEAM_TEMPLATE_CREATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"), anyString());
        when(library.create(eq(A), eq("Dolu"), any(), anyBoolean(), any()))
                .thenThrow(new CodedConflictException("TEMPLATE_LIBRARY_LIMIT", "en çok 20"));
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Dolu", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_LIBRARY_LIMIT"));
        when(library.create(eq(A), eq("Aynı"), any(), anyBoolean(), any()))
                .thenThrow(new CodedConflictException("TEMPLATE_NAME_EXISTS", "var"));
        mvc.perform(post("/api/tech-error-reports/teams/1/templates").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(tplBody("Aynı", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NAME_EXISTS"));
    }

    @Test
    @DisplayName("güncelleme: yalnız gönderilen alanlar servise; değişiklik yoksa denetim YOK; değiştiyse TECH_ERROR_TEAM_TEMPLATE_UPDATE; eski sürüm 409 CONCURRENT_MODIFICATION")
    void update() throws Exception {
        TechErrorTeamTemplate other = entry(6, A, "POS", false);
        when(library.update(eq(A), eq(6L), eq(3L), eq(false), isNull(), isNull(), eq(true), eq(50L)))
                .thenReturn(new TechErrorTeamTemplateService.UpdateResult(other, List.of()));
        mvc.perform(put("/api/tech-error-reports/teams/1/templates/6").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("default", true, "version", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_TEAM_TEMPLATE_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), anyString());
        TechErrorTeamTemplate renamed = entry(6, A, "POS yeni", false);
        renamed.setVersion(4L);
        // Eski ad servisin KİLİT ALTINDA okuduğu değerden gelir (kilit dışında okunan "POS" değil: arada biri yeniden
        // adlandırmıştı) — denetim name_before'u sonuçtan yazar, ek okuma yapmaz
        when(library.update(eq(A), eq(6L), eq(3L), eq(true), eq("POS yeni"), isNull(), isNull(), eq(50L)))
                .thenReturn(new TechErrorTeamTemplateService.UpdateResult(renamed, List.of("name"), "POS (kilit altında)"));
        mvc.perform(put("/api/tech-error-reports/teams/1/templates/6").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "POS yeni", "version", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.version").value(4));
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("TECH_ERROR_TEAM_TEMPLATE_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"), detail.capture());
        org.assertj.core.api.Assertions.assertThat(detail.getValue()).contains("POS (kilit altında)").contains("POS yeni");
        verify(library, never()).get(anyLong(), anyLong());
        when(library.update(eq(A), eq(6L), eq(2L), anyBoolean(), any(), any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("eski"));
        mvc.perform(put("/api/tech-error-reports/teams/1/templates/6").session(user("USER", A, List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "x", "version", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("okunamayan (bozuk / bölümsüz) kayıt: tek kayıt okuması 409 TEMPLATE_UNREADABLE — sistem TR şablonunu \"seçtiğiniz kayıt\" diye vermez")
    void unreadableEntry() throws Exception {
        TechErrorTeamTemplate broken = entry(7, A, "Bozuk kayıt", false);
        broken.setTemplateJson("{bozuk");
        TechErrorTeamTemplate empty = entry(8, A, "Bölümsüz kayıt", false);
        empty.setTemplateJson("{\"lang\":\"tr\",\"title\":\"x\",\"sections\":[]}");
        when(library.get(eq(A), eq(7L))).thenReturn(broken);
        when(library.get(eq(A), eq(8L))).thenReturn(empty);
        mvc.perform(get("/api/tech-error-reports/teams/1/templates/7").session(user("USER", A, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Bozuk kayıt")))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/tech-error-reports/teams/1/templates/8").session(user("USER", A, List.of())).header("X-Lang", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNREADABLE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("couldn't be read")));
    }

    @Test
    @DisplayName("silme: üye siler + TECH_ERROR_TEAM_TEMPLATE_DELETE; başka takımın kaydı 404")
    void deleteEntry() throws Exception {
        when(library.delete(A, 6L)).thenReturn(entry(6, A, "POS", false));
        mvc.perform(delete("/api/tech-error-reports/teams/1/templates/6").session(user("USER", A, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(auditService).recordAction(eq("TECH_ERROR_TEAM_TEMPLATE_DELETE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"), anyString());
        when(library.delete(eq(B), anyLong())).thenThrow(new NoSuchElementException("Kütüphane şablonu bulunamadı"));
        mvc.perform(delete("/api/tech-error-reports/teams/2/templates/6").session(user("USER", B, List.of())))
                .andExpect(status().isNotFound());
    }
}
