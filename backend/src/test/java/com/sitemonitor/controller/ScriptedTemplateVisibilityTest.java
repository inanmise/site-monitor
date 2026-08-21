package com.sitemonitor.controller;

import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import com.sitemonitor.repository.ScriptedTemplateVersionRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Şablon görünürlük/yazılabilirlik matrisi — planın tablosu, çalıştırılabilir hâlde.
 *
 * <h2>Durum kodu değişmezi</h2>
 * <ul>
 *   <li><b>404</b> — okuyamıyorsan HER fiil için. Şablon id'leri küçük ve tahmin edilebilir;
 *       403 dönmek "bu id var ama senin değil" bilgisini sızdırırdı.</li>
 *   <li><b>403</b> — okuyabiliyor ama yazamıyorsan.</li>
 * </ul>
 * Monitör ailesi her iki durumda da 403 döner; buradaki sapma bilinçli ve raporda kayıtlı.
 */
@WebMvcTest(ScriptedTemplateController.class)
class ScriptedTemplateVisibilityTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;

    private static final long ID_GENERAL   = 10L;
    private static final long ID_TEAM_A    = 11L;
    private static final long ID_TEAM_B    = 12L;
    private static final long ID_DELETED_A = 13L;

    private static final String VALID_SCRIPT = "export default function () { }";

    @Autowired MockMvc mvc;

    @MockitoBean ScriptedTemplateRepository templateRepo;
    @MockitoBean ScriptedTemplateVersionRepository versionRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateService certificateService;
    @MockitoBean ScriptedCheckerService scriptedChecker;

    // Interceptor zinciri (kimlik/metrik) — controller'ın kendi bağımlılıkları değil.
    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(certificateService.teamNamesById()).thenReturn(Map.of(TEAM_A, "Takım A", TEAM_B, "Takım B"));
        when(scriptedChecker.version()).thenReturn("v0.49.0");
        when(templateRepo.findById(ID_GENERAL)).thenReturn(Optional.of(general()));
        when(templateRepo.findById(ID_TEAM_A)).thenReturn(Optional.of(teamTemplate(ID_TEAM_A, TEAM_A, "A şablonu")));
        when(templateRepo.findById(ID_TEAM_B)).thenReturn(Optional.of(teamTemplate(ID_TEAM_B, TEAM_B, "B şablonu")));
        when(templateRepo.findById(ID_DELETED_A)).thenReturn(Optional.of(deletedA()));
        when(templateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(templateRepo.countDuplicateInScope(anyString(), any(), any())).thenReturn(0L);
        when(versionRepo.findTopByTemplateIdOrderBySequenceNoDesc(anyLong())).thenReturn(Optional.empty());
    }

    // ── Fikstürler ───────────────────────────────────────────────────────────────────────────

    private static ScriptedTemplate base(Long id, Long teamId, String name) {
        ScriptedTemplate t = new ScriptedTemplate();
        t.setId(id);
        t.setTeamId(teamId);
        t.setName(name);
        t.setScript(VALID_SCRIPT);
        t.setDescription("açıklama");
        t.setWhenToUse("ne zaman");
        t.setActive(true);
        t.setCurrentVersion("1.0.0");
        t.setCreatedAt("2026-08-20T10:00:00");
        t.setCreatedBy("seed");
        return t;
    }

    private static ScriptedTemplate general() {
        ScriptedTemplate t = base(ID_GENERAL, null, "Genel şablon");
        t.setBuiltinKey("smoke-health");
        return t;
    }

    private static ScriptedTemplate teamTemplate(Long id, Long teamId, String name) {
        return base(id, teamId, name);
    }

    private static ScriptedTemplate deletedA() {
        ScriptedTemplate t = base(ID_DELETED_A, TEAM_A, "Silinmiş A");
        t.setActive(false);
        t.setDeletedAt("2026-08-19T10:00:00");
        t.setDeletedBy("u");
        return t;
    }

    // ── Aktörler ─────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession actor(String role, List<Long> view, List<Long> manage, List<Long> member) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (view != null)   s.setAttribute("viewTeamIds", view);
        if (manage != null) s.setAttribute("manageTeamIds", manage);
        s.setAttribute("memberTeamIds", member);
        return s;
    }

    /** Takım A'nın sıradan üyesi. manage kapsamı BOŞ — K3 yalnız üyelikle çalışmak zorunda. */
    private static MockHttpSession userA() {
        return actor("USER", List.of(TEAM_A), List.of(), List.of(TEAM_A));
    }

    /** İki takımın üyesi — üyelik listesinin gerçekten çoklu olduğunu kanıtlar. */
    private static MockHttpSession userAB() {
        return actor("USER", List.of(TEAM_A, TEAM_B), List.of(), List.of(TEAM_A, TEAM_B));
    }

    private static MockHttpSession teamAdminA() {
        return actor("TEAM_ADMIN", List.of(TEAM_A), List.of(TEAM_A), List.of(TEAM_A));
    }

    /** Kısıtsız (bootstrap) admin: view ve manage kapsamı NULL → isGlobalAdmin. */
    private static MockHttpSession globalAdmin() {
        return actor("ADMIN", null, null, List.of());
    }

    /**
     * "Müdür": AD ile gelen ADMIN. Rolü ADMIN ama {@code viewTeamIds} DOLU olduğu için
     * {@code isGlobalAdmin} DEĞİL — gözettiği takımı görür, yazamaz. Bu kod tabanında müdür
     * hiçbir yerde monitör kuramıyor; şablonu istisna yapmak tutarsızlık olurdu.
     */
    private static MockHttpSession mudur() {
        return actor("ADMIN", List.of(TEAM_A), List.of(), List.of());
    }

    // ── Matris ───────────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> matrix() {
        return Stream.of(
                //          aktör            şablon          GET  PUT
                Arguments.of("USER-A",       ID_GENERAL,     200, 403),
                Arguments.of("USER-A",       ID_TEAM_A,      200, 200),
                Arguments.of("USER-A",       ID_TEAM_B,      404, 404),
                Arguments.of("USER-A",       ID_DELETED_A,   404, 404),

                Arguments.of("USER-AB",      ID_GENERAL,     200, 403),
                Arguments.of("USER-AB",      ID_TEAM_A,      200, 200),
                Arguments.of("USER-AB",      ID_TEAM_B,      200, 200),
                Arguments.of("USER-AB",      ID_DELETED_A,   404, 404),

                Arguments.of("TEAM_ADMIN-A", ID_GENERAL,     200, 403),
                Arguments.of("TEAM_ADMIN-A", ID_TEAM_A,      200, 200),
                Arguments.of("TEAM_ADMIN-A", ID_TEAM_B,      404, 404),
                Arguments.of("TEAM_ADMIN-A", ID_DELETED_A,   404, 404),

                Arguments.of("ADMIN",        ID_GENERAL,     200, 200),
                Arguments.of("ADMIN",        ID_TEAM_A,      200, 200),
                Arguments.of("ADMIN",        ID_TEAM_B,      200, 200),
                Arguments.of("ADMIN",        ID_DELETED_A,   200, 200),

                Arguments.of("MÜDÜR",        ID_GENERAL,     200, 403),
                Arguments.of("MÜDÜR",        ID_TEAM_A,      200, 403),
                Arguments.of("MÜDÜR",        ID_TEAM_B,      404, 404),
                Arguments.of("MÜDÜR",        ID_DELETED_A,   404, 404)
        );
    }

    private static MockHttpSession sessionFor(String label) {
        return switch (label) {
            case "USER-A"       -> userA();
            case "USER-AB"      -> userAB();
            case "TEAM_ADMIN-A" -> teamAdminA();
            case "ADMIN"        -> globalAdmin();
            case "MÜDÜR"        -> mudur();
            default -> throw new IllegalArgumentException(label);
        };
    }

    @ParameterizedTest(name = "{0} → şablon {1}: GET {2}, PUT {3}")
    @MethodSource("matrix")
    void visibilityMatrix(String label, long templateId, int getStatus, int putStatus) throws Exception {
        MockHttpSession s = sessionFor(label);

        mvc.perform(get("/api/monitoring/scripted/templates/" + templateId).session(s))
                .andExpect(status().is(getStatus));

        mvc.perform(put("/api/monitoring/scripted/templates/" + templateId).session(sessionFor(label))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"yeni açıklama\"}"))
                .andExpect(status().is(putStatus));
    }

    /** Yazma reddi HİÇBİR yan etki bırakmamalı — 403/404 dönüp yine de kaydetmek en sinsi hata. */
    @ParameterizedTest(name = "{0} → şablon {1} yazamaz: save() ÇAĞRILMAZ")
    @MethodSource("deniedWrites")
    void deniedWriteNeverPersists(String label, long templateId) throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + templateId).session(sessionFor(label))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"script\":\"export default function () { /* hack */ }\"}"));
        verify(templateRepo, never()).save(any());
        verify(versionRepo, never()).save(any());
        verifyNoInteractions(auditService);
    }

    static Stream<Arguments> deniedWrites() {
        return matrix().filter(a -> (int) a.get()[3] != 200)
                .map(a -> Arguments.of(a.get()[0], a.get()[1]));
    }

    // ── Listeleme kapsamı ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Liste: USER-A Genel + kendi takımını görür, TAKIM B SIZMAZ")
    void listScopesToReadableTeams() throws Exception {
        when(templateRepo.findActiveGeneral()).thenReturn(List.of(general()));
        when(templateRepo.findActiveByTeams(List.of(TEAM_A)))
                .thenReturn(List.of(teamTemplate(ID_TEAM_A, TEAM_A, "A şablonu")));

        mvc.perform(get("/api/monitoring/scripted/templates").session(userA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(2))
                .andExpect(jsonPath("$.data.templates[?(@.id == 12)]").isEmpty())
                .andExpect(jsonPath("$.data.can_create_general").value(false))
                .andExpect(jsonPath("$.data.can_view_trash").value(false))
                .andExpect(jsonPath("$.data.writable_team_ids[0]").value(1));
    }

    /**
     * BOŞ koleksiyonla {@code findActiveByTeams} çağrılırsa JPQL {@code in ()} üretilir —
     * sağlayıcıya bağlı sözdizimi hatası. Hiçbir takımı olmayan kullanıcıda bu yol hiç
     * çağrılmamalı; test o korumayı pinler.
     */
    @Test
    @DisplayName("Liste: kapsamsız kullanıcıda findActiveByTeams HİÇ çağrılmaz (boş IN listesi tuzağı)")
    void emptyScopeNeverQueriesByTeams() throws Exception {
        when(templateRepo.findActiveGeneral()).thenReturn(List.of(general()));
        MockHttpSession s = actor("USER", List.of(), List.of(), List.of());

        mvc.perform(get("/api/monitoring/scripted/templates").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(1));   // yalnız Genel
        verify(templateRepo, never()).findActiveByTeams(anyCollection());
    }

    @Test
    @DisplayName("Liste: global admin TÜM takımların şablonlarını görür")
    void adminSeesEveryTeam() throws Exception {
        when(templateRepo.findActiveGeneral()).thenReturn(List.of(general()));
        when(templateRepo.findAllActive()).thenReturn(List.of(
                general(), teamTemplate(ID_TEAM_A, TEAM_A, "A"), teamTemplate(ID_TEAM_B, TEAM_B, "B")));

        mvc.perform(get("/api/monitoring/scripted/templates").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(3))   // Genel 1× (findAllActive'de takım filtresi var)
                .andExpect(jsonPath("$.data.can_create_general").value(true));
    }

    @Test
    @DisplayName("Çöp kutusu yalnız admin'e: USER 403, admin 200")
    void trashIsAdminOnly() throws Exception {
        when(templateRepo.findTrash()).thenReturn(List.of(deletedA()));

        mvc.perform(get("/api/monitoring/scripted/templates?scope=trash").session(userA()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/scripted/templates?scope=trash").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(1));
    }

    // ── Yetenek bayrakları ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yetenek bayrakları yanıtta gelir — UI yetkiyi ASLA yeniden hesaplamaz")
    void capabilityFlagsAreServerComputed() throws Exception {
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_TEAM_A).session(userA()))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.can_promote").value(false))     // promote admin işi
                .andExpect(jsonPath("$.data.can_permanent_delete").value(false));

        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_TEAM_A).session(globalAdmin()))
                .andExpect(jsonPath("$.data.can_promote").value(true))
                .andExpect(jsonPath("$.data.can_permanent_delete").value(true));

        // Yerleşik Genel şablon: admin bile KALICI silemez (seeder diriltir).
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_GENERAL).session(globalAdmin()))
                .andExpect(jsonPath("$.data.can_demote").value(true))
                .andExpect(jsonPath("$.data.can_permanent_delete").value(false));
    }

    // ── Kapsam yükseltme ─────────────────────────────────────────────────────────────────────

    /**
     * <b>Sessiz yetki yükseltmesi kapısı.</b> PUT {@code teamId}'yi YOK SAYAR. Aksi hâlde
     * herhangi bir USER {@code teamId: null} göndererek şablonunu Genel'e — yalnız admin'in
     * yazabildiği katmana — taşıyabilirdi ve o andan sonra kendi şablonunu düzenleyemezdi bile.
     */
    @Test
    @DisplayName("PUT teamId:null gönderildiğinde şablon GENEL'e taşınmaz (kapsam yükseltme kapalı)")
    void putCannotPromoteToGeneral() throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID_TEAM_A).session(userA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":null,\"description\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1))
                .andExpect(jsonPath("$.data.scope").value("team"));
    }

    @Test
    @DisplayName("PUT başka takıma taşıma da yok sayılır")
    void putCannotMoveBetweenTeams() throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID_TEAM_A).session(userAB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":2,\"description\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1));
    }

    @Test
    @DisplayName("POST Genel şablon: admin değilse 403 — sessizce takıma DÜŞÜRÜLMEZ")
    void nonAdminCannotCreateGeneral() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(userA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":null,\"name\":\"X\",\"script\":\"" + VALID_SCRIPT + "\"}"))
                .andExpect(status().isForbidden());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST başka takım adına: 403")
    void cannotCreateInForeignTeam() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(userA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":2,\"name\":\"X\",\"script\":\"" + VALID_SCRIPT + "\"}"))
                .andExpect(status().isForbidden());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST kapsam belirtilmezse üyelik takımına yazılır (K3)")
    void createDefaultsToMemberTeam() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(userA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"script\":\"" + VALID_SCRIPT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1))
                .andExpect(jsonPath("$.data.current_version").value("1.0.0"));
    }

    // ── promote / demote ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("promote/demote yalnız admin: takım yöneticisi bile 403")
    void promoteIsAdminOnly() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_TEAM_A + "/promote").session(teamAdminA()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_GENERAL + "/demote").session(teamAdminA())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":1}"))
                .andExpect(status().isForbidden());
        verify(templateRepo, never()).save(any());
    }

    @Test
    @DisplayName("promote TAŞIMA yapar: team_id null olur, köken rozeti yazılır (K5)")
    void promoteMovesToGeneralWithOriginBadge() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_TEAM_A + "/promote").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").doesNotExist())
                .andExpect(jsonPath("$.data.scope").value("general"))
                .andExpect(jsonPath("$.data.source_team_name").value("Takım A"));
    }

    @Test
    @DisplayName("demote hedef takım ister; kökeni SİLMEZ (nereden geldiği tarihsel olgu)")
    void demoteKeepsOriginBadge() throws Exception {
        ScriptedTemplate promoted = general();
        promoted.setBuiltinKey(null);
        promoted.setSourceTeamName("Takım A");
        when(templateRepo.findById(ID_GENERAL)).thenReturn(Optional.of(promoted));

        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_GENERAL + "/demote").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());     // hedef takım zorunlu

        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_GENERAL + "/demote").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1))
                .andExpect(jsonPath("$.data.source_team_name").value("Takım A"));
    }

    // ── Silme ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Yerleşik şablon KALICI silinemez — seeder onu açılışta diriltir, 'silme çalışmadı' sanılır")
    void builtinCannotBePermanentlyDeleted() throws Exception {
        mvc.perform(delete("/api/monitoring/scripted/templates/" + ID_GENERAL + "?permanent=true")
                        .session(globalAdmin()))
                .andExpect(status().isBadRequest());
        verify(templateRepo, never()).delete(any());
    }

    @Test
    @DisplayName("Kalıcı silme yalnız admin; yumuşak silme takım üyesine açık")
    void deletePermissions() throws Exception {
        mvc.perform(delete("/api/monitoring/scripted/templates/" + ID_TEAM_A + "?permanent=true").session(userA()))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/monitoring/scripted/templates/" + ID_TEAM_A).session(userA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permanent").value(false));
    }

    @Test
    @DisplayName("undelete yalnız admin (çöp kutusu admin'in)")
    void undeleteIsAdminOnly() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_DELETED_A + "/undelete").session(teamAdminA()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID_DELETED_A + "/undelete").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.deleted_at").doesNotExist());
    }

    // ── Sürüm uçları ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sürüm listesi okunamayan şablonda 404 (script gövdesi başka takıma sızmaz)")
    void versionsRespectReadScope() throws Exception {
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_TEAM_B + "/versions").session(userA()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_TEAM_B + "/versions/1").session(userA()))
                .andExpect(status().isNotFound());
    }

    /** Yetki sınırı ŞABLON üzerinden kuruluyor: başka şablonun sürüm id'siyle içerik çekilemez. */
    @Test
    @DisplayName("Sürüm detayı: id başka şablona aitse 404 (çapraz-şablon sızıntısı yok)")
    void versionDetailRejectsCrossTemplateId() throws Exception {
        var v = new com.sitemonitor.model.ScriptedTemplateVersion();
        v.setId(77L);
        v.setTemplateId(ID_TEAM_B);            // B'nin sürümü
        v.setScript("gizli");
        when(versionRepo.findById(77L)).thenReturn(Optional.of(v));

        mvc.perform(get("/api/monitoring/scripted/templates/" + ID_TEAM_A + "/versions/77").session(userA()))
                .andExpect(status().isNotFound());
    }
}
