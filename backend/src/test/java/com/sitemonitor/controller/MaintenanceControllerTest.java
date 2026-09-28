package com.sitemonitor.controller;

import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MaintenanceController.class)
class MaintenanceControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean MaintenanceWindowRepository repo;
    @MockitoBean MaintenanceService maintenanceService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean com.sitemonitor.service.AlertKeyOwnershipService targetOwnership;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    private MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MaintenanceWindow win() {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setId(1L); w.setName("DB bakımı"); w.setStartAt("2026-01-01T10:00:00");
        w.setDurationMinutes(60); w.setRecurrence("NONE"); w.setTimezone("Europe/Istanbul"); w.setActive(true);
        return w;
    }

    @Test
    @DisplayName("GET /maintenance: pencereleri DTO olarak döner (status/next_occurrence dahil)")
    void list() throws Exception {
        when(repo.findAllByOrderByStartAtDesc()).thenReturn(List.of(win()));
        when(maintenanceService.computeStatus(any(), any())).thenReturn("upcoming");
        when(maintenanceService.targetCount(any())).thenReturn(3);
        mvc.perform(get("/api/monitoring/maintenance").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("DB bakımı"))
                .andExpect(jsonPath("$.data[0].status").value("upcoming"))
                .andExpect(jsonPath("$.data[0].target_count").value(3));
    }

    @Test
    @DisplayName("POST /maintenance: geçerli gövde kaydeder + cache refresh")
    void create() throws Exception {
        when(repo.save(any(MaintenanceWindow.class))).thenAnswer(inv -> { MaintenanceWindow w = inv.getArgument(0); w.setId(9L); return w; });
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"Bakım\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":120,\"recurrence\":\"DAILY\",\"allMonitors\":true}"))
                .andExpect(status().isOk());
        verify(repo).save(any(MaintenanceWindow.class));
        verify(maintenanceService).refresh();
    }

    @Test
    @DisplayName("POST /maintenance: isim yoksa 400")
    void create_missingName() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json").content("{\"startAt\":\"2026-05-01T02:00:00\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance: ayrıştırılamayan startAt → 400 (sessiz-inert pencereyi önler) (M9)")
    void create_malformedStart_400() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"not-a-date\",\"durationMinutes\":60,\"recurrence\":\"NONE\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance: WEEKLY ama gün seçilmemiş → 400 (hiç tetiklenmeyen pencereyi önler) (M9)")
    void create_weeklyNoDays_400() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"2026-01-01T10:00:00\",\"durationMinutes\":60,\"recurrence\":\"WEEKLY\",\"daysOfWeek\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance/quick: ad-hoc pencere oluşturur")
    void quick() throws Exception {
        when(repo.save(any(MaintenanceWindow.class))).thenAnswer(inv -> { MaintenanceWindow w = inv.getArgument(0); w.setId(3L); return w; });
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"minutes\":30,\"allMonitors\":true}"))
                .andExpect(status().isOk());
        verify(repo).save(any(MaintenanceWindow.class));
    }

    @Test
    @DisplayName("DELETE /maintenance/{id}: siler + refresh")
    void delete_ok() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(win()));
        mvc.perform(delete("/api/monitoring/maintenance/1").session(session()))
                .andExpect(status().isOk());
        verify(repo).deleteById(1L);
        verify(maintenanceService).refresh();
    }

    @Test
    @DisplayName("GET /maintenance/active: aktif hedef bilgisini döner")
    void active() throws Exception {
        when(maintenanceService.activeInfo()).thenReturn(java.util.Map.of("all", false, "targets", List.of()));
        mvc.perform(get("/api/monitoring/maintenance/active").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(false));
    }

    // ── Takım kapsamı (IDOR) ────────────────────────────────────────────────────
    // Bakım pencereleri ALARM BASTIRIR. Eskiden yazma yolları yalnız izin anahtarına bakıyordu ve
    // kaydı id ile yüklüyordu → A takımının yöneticisi B takımının penceresini düzenleyip silebiliyordu.

    /** Takım 5'i YÖNETEN takım yöneticisi (global admin değil). */
    private MockHttpSession teamAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "po");
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(5L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(5L)));
        return s;
    }

    private static MaintenanceWindow winOfTeam(Long teamId) {
        MaintenanceWindow w = win();
        w.setTeamId(teamId);
        return w;
    }

    @Test
    @DisplayName("IDOR: PUT başka takımın penceresinde 403 — kayıt DEĞİŞMEZ")
    void update_otherTeamWindow_returns403() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(9L)));   // yabancı takım

        mvc.perform(put("/api/monitoring/maintenance/1").session(teamAdminSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ele geçirildi\",\"startAt\":\"2026-01-01T10:00:00\"}"))
                .andExpect(status().isForbidden());

        verify(repo, never()).save(any());
        verify(maintenanceService, never()).refresh();
    }

    @Test
    @DisplayName("IDOR: DELETE başka takımın penceresinde 403 — silinmez")
    void delete_otherTeamWindow_returns403() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(9L)));

        mvc.perform(delete("/api/monitoring/maintenance/1").session(teamAdminSession()))
                .andExpect(status().isForbidden());

        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("IDOR: pause/resume başka takımın penceresinde 403 — alarm bastırması değiştirilemez")
    void toggle_otherTeamWindow_returns403() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(9L)));

        mvc.perform(post("/api/monitoring/maintenance/1/pause").session(teamAdminSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/maintenance/1/resume").session(teamAdminSession()))
                .andExpect(status().isForbidden());

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Takım yöneticisi KENDİ takımının penceresini düzenleyebilir (200)")
    void update_ownTeamWindow_returns200() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(5L)));   // kendi takımı
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/maintenance/1").session(teamAdminSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DB bakımı v2\",\"startAt\":\"2026-01-01T10:00:00\",\"durationMinutes\":60}"))
                .andExpect(status().isOk());

        verify(repo).save(any());
        verify(maintenanceService).refresh();
    }

    @Test
    @DisplayName("Global admin her takımın penceresini yönetebilir (200)")
    void update_asGlobalAdmin_anyTeam_returns200() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(9L)));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/maintenance/1").session(session())   // session() = global ADMIN
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DB bakımı v2\",\"startAt\":\"2026-01-01T10:00:00\",\"durationMinutes\":60}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /maintenance: başka takımın penceresi listede YOK; herkesi etkileyen (allMonitors) GÖRÜNÜR")
    void list_scopedToViewableTeams_butGlobalWindowsVisible() throws Exception {
        MaintenanceWindow mine = winOfTeam(5L);   mine.setId(1L);   mine.setName("Kendi takımım");
        MaintenanceWindow other = winOfTeam(9L);  other.setId(2L);  other.setName("Baska takim");
        MaintenanceWindow global = winOfTeam(9L); global.setId(3L); global.setName("Tum izlemeler");
        global.setAllMonitors(true);   // senin izlemelerini de bastırıyor → gizlenmemeli
        when(repo.findAllByOrderByStartAtDesc()).thenReturn(List.of(mine, other, global));
        when(maintenanceService.computeStatus(any(), any())).thenReturn("upcoming");

        mvc.perform(get("/api/monitoring/maintenance").session(teamAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Kendi takımım"))
                .andExpect(jsonPath("$.data[1].name").value("Tum izlemeler"));
    }

    @Test
    @DisplayName("Takımsız (legacy) pencere yalnız global admin'e açık — sahipsizi kimse devralmasın")
    void update_teamlessWindow_teamAdmin_returns403() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(winOfTeam(null)));

        mvc.perform(put("/api/monitoring/maintenance/1").session(teamAdminSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"startAt\":\"2026-01-01T10:00:00\"}"))
                .andExpect(status().isForbidden());
    }

    // ── KAPSAM (A1, 2026-09-28): pencerenin NEYİ susturduğu ─────────────────────────
    // Sahiplik kapısı yalnız pencerenin takımına bakıyordu; allMonitors/targets gövdeden geliyordu. Takım A
    // yöneticisi tek bir /quick {"allMonitors":true} ile kurumun TÜM alarmlarını susturabiliyor,
    // targets:["b.example.com"] ile Takım B'nin host'unu kapatabiliyordu.

    /** AD-kaynaklı kapsamlı müdür: rol ADMIN ama takım 2'ye sınırlı — global DEĞİL. */
    private MockHttpSession scopedAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(2L)));
        return s;
    }

    private static String targetsBody(String... keys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"http\",\"target\":\"").append(keys[i]).append("\",\"name\":\"").append(keys[i]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private void owners(java.util.Map<String, java.util.Set<Long>> m) {
        when(targetOwnership.ownerTeams(any())).thenReturn(m);
    }

    private void saveEchoes() {
        when(repo.save(any(MaintenanceWindow.class))).thenAnswer(inv -> { MaintenanceWindow w = inv.getArgument(0); if (w.getId() == null) w.setId(11L); return w; });
    }

    @Test
    @DisplayName("KAPSAM: TEAM_ADMIN /quick allMonitors → 403; pencere yazılmaz, bastırma açılmaz")
    void quick_allMonitors_teamAdmin_403() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance/quick").session(teamAdminSession())
                        .contentType("application/json").content("{\"allMonitors\":true,\"minutes\":100000}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
        verify(maintenanceService, never()).refresh();
    }

    @Test
    @DisplayName("KAPSAM: kapsamlı müdür (ADMIN + viewTeamIds) /quick ve POST allMonitors → 403 (rol ADMIN ≠ global)")
    void allMonitors_scopedAdmin_403() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance/quick").session(scopedAdminSession())
                        .contentType("application/json").content("{\"allMonitors\":true,\"minutes\":30}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/maintenance").session(scopedAdminSession())
                        .contentType("application/json")
                        .content("{\"name\":\"Hepsi\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":60,\"allMonitors\":true}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("KAPSAM: başka takımın hedefi (b.example.com → takım 9) → 403 ve ileti hedefi adıyla söyler")
    void quick_foreignTarget_teamAdmin_403() throws Exception {
        owners(java.util.Map.of("b.example.com", java.util.Set.of(9L)));
        mvc.perform(post("/api/monitoring/maintenance/quick").session(teamAdminSession())
                        .contentType("application/json")
                        .content("{\"minutes\":30,\"targets\":" + targetsBody("b.example.com") + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("b.example.com")));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("KAPSAM: aynı anahtarı iki takım izliyorsa (5 + 9) → 403 — motor anahtarı türden bağımsız susturur")
    void create_sharedKey_teamAdmin_403() throws Exception {
        owners(java.util.Map.of("shared.example.com", java.util.Set.of(5L, 9L)));
        mvc.perform(post("/api/monitoring/maintenance").session(teamAdminSession())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":60,\"targets\":"
                                + targetsBody("shared.example.com") + "}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("KAPSAM: hiçbir satıra çözülmeyen ya da takımsız hedef → 403 (sahibi belirlenemeyeni kimse susturmasın)")
    void create_unknownOrTeamlessTarget_teamAdmin_403() throws Exception {
        java.util.Set<Long> teamless = new java.util.HashSet<>();
        teamless.add(null);
        owners(java.util.Map.of("legacy.example.com", teamless));   // "yok.example.com" haritada hiç yok
        for (String key : List.of("yok.example.com", "legacy.example.com")) {
            mvc.perform(post("/api/monitoring/maintenance").session(teamAdminSession())
                            .contentType("application/json")
                            .content("{\"name\":\"X\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":60,\"targets\":"
                                    + targetsBody(key) + "}"))
                    .andExpect(status().isForbidden());
        }
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("KAPSAM: kendi takımının hedefleri → 200; kapı motorun okuduğu anahtarları sordu")
    void create_ownTargets_teamAdmin_200() throws Exception {
        owners(java.util.Map.of("a.example.com", java.util.Set.of(5L), "https://a.example.com/", java.util.Set.of(5L)));
        saveEchoes();
        mvc.perform(post("/api/monitoring/maintenance").session(teamAdminSession())
                        .contentType("application/json")
                        .content("{\"name\":\"Kendi\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":60,\"targets\":"
                                + targetsBody("a.example.com", "https://a.example.com/") + "}"))
                .andExpect(status().isOk());
        verify(repo).save(any(MaintenanceWindow.class));
        verify(targetOwnership).ownerTeams(List.of("a.example.com", "https://a.example.com/"));
    }

    @Test
    @DisplayName("KAPSAM: global admin allMonitors ve herhangi bir hedef açabilir — sahiplik sorgusu bile yapılmaz")
    void globalAdmin_anyScope_200() throws Exception {
        saveEchoes();
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"allMonitors\":true,\"minutes\":60}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"minutes\":60,\"targets\":" + targetsBody("b.example.com") + "}"))
                .andExpect(status().isOk());
        verify(targetOwnership, never()).ownerTeams(any());
    }

    @Test
    @DisplayName("KAPSAM: PUT kendi penceresini allMonitors'a ÇEVİREMEZ; yabancı hedef ekleyemez → 403, kayıt yazılmaz")
    void update_switchToAllOrForeign_teamAdmin_403() throws Exception {
        // Her istekte TAZE kayıt: ilk PUT'un yarım uyguladığı allMonitors ikinci PUT'u yanlış dalda reddettirmesin.
        when(repo.findById(1L)).thenAnswer(i -> Optional.of(winOfTeam(5L)));
        owners(java.util.Map.of("b.example.com", java.util.Set.of(9L)));

        mvc.perform(put("/api/monitoring/maintenance/1").session(teamAdminSession())
                        .contentType("application/json").content("{\"allMonitors\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/monitoring/maintenance/1").session(teamAdminSession())
                        .contentType("application/json").content("{\"targets\":" + targetsBody("b.example.com") + "}"))
                .andExpect(status().isForbidden());

        verify(repo, never()).save(any());
        verify(maintenanceService, never()).refresh();
    }

    @Test
    @DisplayName("KAPSAM: eski allMonitors penceresi TEAM_ADMIN tarafından SÜRDÜRÜLEMEZ (403); duraklatılabilir (200)")
    void resume_legacyAllMonitors_teamAdmin_403_butPauseOk() throws Exception {
        MaintenanceWindow legacy = winOfTeam(5L);
        legacy.setAllMonitors(true);
        legacy.setActive(false);
        when(repo.findById(1L)).thenReturn(Optional.of(legacy));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/monitoring/maintenance/1/resume").session(teamAdminSession()))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());

        mvc.perform(post("/api/monitoring/maintenance/1/pause").session(teamAdminSession()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SÜRE TAVANI: kapsamlı yazar 7 gün (10080 dk geçer, 10081 → 400); global admin 30 gün (43201 → 400)")
    void durationCap() throws Exception {
        saveEchoes();
        owners(java.util.Map.of("a.example.com", java.util.Set.of(5L)));
        String own = targetsBody("a.example.com");

        mvc.perform(post("/api/monitoring/maintenance/quick").session(teamAdminSession())
                        .contentType("application/json").content("{\"minutes\":10081,\"targets\":" + own + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/maintenance").session(teamAdminSession())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":10081,\"targets\":" + own + "}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());

        mvc.perform(post("/api/monitoring/maintenance/quick").session(teamAdminSession())
                        .contentType("application/json").content("{\"minutes\":10080,\"targets\":" + own + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"allMonitors\":true,\"minutes\":43201}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"allMonitors\":true,\"minutes\":43200}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /active: global olmayan görüntüleyici YALNIZ kendi takımlarının hedeflerini görür; global hepsini")
    void active_scopedToViewer() throws Exception {
        when(maintenanceService.activeInfo()).thenReturn(java.util.Map.of("all", false,
                "targets", List.of("a.example.com", "b.example.com", "yetim.example.com")));
        when(targetOwnership.viewerTeams(any())).thenReturn(java.util.Map.of(
                "a.example.com", java.util.Set.of(5L), "b.example.com", java.util.Set.of(9L)));

        mvc.perform(get("/api/monitoring/maintenance/active").session(teamAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(false))
                .andExpect(jsonPath("$.data.targets.length()").value(1))
                .andExpect(jsonPath("$.data.targets[0]").value("a.example.com"));

        mvc.perform(get("/api/monitoring/maintenance/active").session(session()))   // global admin
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targets.length()").value(3));
    }
}
