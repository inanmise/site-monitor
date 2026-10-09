package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Olaylar — org geneli SALT OKUNUR görünürlük (2026-09-28, kullanıcı kararı): "Olaylar sayfası tüm kullanıcılara açık;
 * bir kullanıcı başka takımlara açılan olay kayıtlarını görebilir, takımına ait / başka ekiplere ait diye süzebilir,
 * ama o olay kayıtlarına müdahale edemez."
 *
 * <p>İzinler GERÇEK katalog varsayılanlarından ({@link PermissionCatalog#defaultsFor}) — AUDIT'in {@code alerts.actions}
 * taşımadığı, USER'ın taşıdığı burada sabitlenmez, katalogdan okunur. Kapsamlı müdür (rol ADMIN, görüş kapsamı dolu)
 * global yönetici SAYILMAZ (proje tuzağı).
 */
@WebMvcTest(IncidentsController.class)
class IncidentsOrgVisibilityTest {

    private static final long OWN = 5L, FOREIGN = 9L, SUB = 6L;

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
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    // ── Oturumlar ────────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession session(String user, String role, List<Long> view, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        s.setAttribute("teamId", OWN);
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN)));
        return s;
    }

    private static MockHttpSession user()        { return session("kisia", "USER", List.of(OWN), List.of()); }
    private static MockHttpSession teamAdmin()   { return session("kisib", "TEAM_ADMIN", List.of(OWN), List.of(OWN)); }
    /** AD ADMIN (müdür): rol ADMIN ama görüş = yönetim = kendi + ast takımı → GLOBAL DEĞİL. */
    private static MockHttpSession scopedAdmin() { return session("mudur", "ADMIN", List.of(OWN, SUB), List.of(OWN, SUB)); }
    private static MockHttpSession audit()       { return session("noc1", "AUDIT", null, null); }
    private static MockHttpSession globalAdmin() { return session("admin", "ADMIN", null, null); }

    // ── Olaylar ──────────────────────────────────────────────────────────────────────────────────

    private static AlertEvent event(long id, Long teamId, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setTeamId(teamId); e.setDomain(domain);
        e.setAlertType("HTTP_DOWN"); e.setAlertLevel("CRITICAL"); e.setResolved(false);
        e.setCreatedAt("2026-09-28T03:00:00"); e.setMessage("HTTP 503");
        return e;
    }

    /** Kendi takımının damgalı olayı. */
    private final AlertEvent ownEv = event(1L, OWN, "https://own.example.com");
    /** Başka takımın damgalı olayı. */
    private final AlertEvent foreignEv = event(2L, FOREIGN, "https://foreign.example.com");
    /** Damgasız ENVANTER olayı (ACCESSIBILITY); envanterde SY başka takım, UG KENDİ takım → yine "kendi" (incidentOwnedBy
     *  kuralı). Bağımsız izleme türü OLAMAZ: 2026-10-09'dan beri onun sahibi yalnız damgalı takımdır (AlertOwnership). */
    private final AlertEvent ugEv = withType(event(3L, null, "ug.example.com"), "ACCESSIBILITY");

    private static AlertEvent withType(AlertEvent e, String type) {
        e.setAlertType(type);
        return e;
    }

    private void switchOn(boolean on) {
        when(appSettings.getBoolean(eq(IncidentsController.VISIBLE_TO_ALL_KEY), anyBoolean())).thenReturn(on);
    }

    private static boolean catalogAllows(Object session, String key, String action) {
        String role = (String) ((HttpSession) session).getAttribute("systemRole");
        Map<String, Boolean> actions = PermissionCatalog.defaultsFor(role).get(key);
        return actions != null && Boolean.TRUE.equals(actions.get(action));
    }

    @BeforeEach
    void setUp() {
        switchOn(true);
        when(permissionService.allows(any(HttpSession.class), anyString(), anyString()))
                .thenAnswer(i -> catalogAllows(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        doAnswer(i -> {
            if (!catalogAllows(i.getArgument(0), i.getArgument(1), i.getArgument(2)))
                throw new SecurityException("Bu işlem için yetkiniz yok: " + i.getArgument(1));
            return null;
        }).when(permissionService).require(any(HttpSession.class), anyString(), anyString());

        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(ownEv));
        when(alertEventRepo.findById(2L)).thenReturn(Optional.of(foreignEv));
        when(alertEventRepo.findById(3L)).thenReturn(Optional.of(ugEv));
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(foreignEv, ownEv, ugEv)));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(List.of());
        when(alertEventRepo.countIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(40L);
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());

        CertificateInventory ug = new CertificateInventory();
        ug.setDomain("ug.example.com"); ug.setTeamId(FOREIGN); ug.setUgTeamId(OWN);
        when(inventoryRepo.findByDomainIn(any())).thenReturn(List.of(ug));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.findByDomain("ug.example.com")).thenReturn(Optional.of(ug));

        Team a = new Team(); a.setId(OWN); a.setName("Takım A");
        Team b = new Team(); b.setId(FOREIGN); b.setName("Takım B");
        when(teamRepo.findAllById(any())).thenReturn(List.of(a, b));

        com.sitemonitor.model.HttpMonitor own = new com.sitemonitor.model.HttpMonitor();
        own.setId(70L); own.setName("Kendi sitesi"); own.setUrl("https://own.example.com");
        com.sitemonitor.model.HttpMonitor foreign = new com.sitemonitor.model.HttpMonitor();
        foreign.setId(90L); foreign.setName("Başka site"); foreign.setUrl("https://foreign.example.com");
        when(httpMonitorRepo.findAll()).thenReturn(List.of(own, foreign));
    }

    // ══ Liste: kapsam parametresi ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("USER varsayılan (scope yok) = BUGÜNKÜ görünüm: kendi kapsamıyla sorgu; yanıt scope=mine + çip sayıları")
    void list_defaultIsMine() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.visible_to_all").value(true))
                .andExpect(jsonPath("$.scope_counts.mine").value(3))      // sayfa toplamı (PageImpl 3 satır)
                .andExpect(jsonPath("$.scope_counts.all").value(40))      // tek ek sayım: tümü
                .andExpect(jsonPath("$.scope_counts.others").value(37));
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(true), eq(false), eq(List.of(OWN)), any(Pageable.class));
        verify(alertEventRepo).countIncidents(any(), any(), any(), any(), any(), eq(false), eq(false), any());
        verify(alertEventRepo).countIncidentsByType(any(), any(), any(), any(), eq(true), eq(false), eq(List.of(OWN)));
    }

    @Test
    @DisplayName("USER scope=others → TAMAMLAYICI küme (outside=true); scope=all → kapsamsız; sayılar tutarlı")
    void list_othersAndAll() throws Exception {
        when(alertEventRepo.countIncidents(any(), any(), any(), any(), any(), eq(true), eq(false), any())).thenReturn(12L);
        mvc.perform(get("/api/monitoring/incidents").param("scope", "others").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("others"))
                .andExpect(jsonPath("$.scope_counts.mine").value(12))
                .andExpect(jsonPath("$.scope_counts.others").value(3))
                .andExpect(jsonPath("$.scope_counts.all").value(15));
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(true), eq(true), eq(List.of(OWN)), any(Pageable.class));

        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.scope_counts.all").value(3))
                .andExpect(jsonPath("$.scope_counts.mine").value(12))
                .andExpect(jsonPath("$.scope_counts.others").value(0));   // taban: negatif sayı yok
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(false), eq(false), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("TEAM_ADMIN ve KAPSAMLI müdür scope=others: kendi görüş kapsamlarının tamamlayıcısı — müdür global sanılmaz")
    void list_teamAdminAndScopedAdmin() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").param("scope", "others").session(teamAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("others"));
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(true), eq(true), eq(List.of(OWN)), any(Pageable.class));

        mvc.perform(get("/api/monitoring/incidents").param("scope", "others").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("others"))
                .andExpect(jsonPath("$.scope_counts").exists());
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(true), eq(true), eq(List.of(OWN, SUB)), any(Pageable.class));
    }

    @Test
    @DisplayName("global görüntüleyici (AUDIT, global admin): her olay zaten KENDİ kapsamında — anahtar yok, scope=mine, kapsamsız sorgu")
    void list_globalViewersUnchanged() throws Exception {
        for (MockHttpSession s : List.of(audit(), globalAdmin())) {
            mvc.perform(get("/api/monitoring/incidents").param("scope", "others").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("mine"))
                    .andExpect(jsonPath("$.scope_counts").doesNotExist())
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0].can_manage").value(true));
        }
        verify(alertEventRepo, times(2)).findIncidents(any(), any(), any(), any(), any(), eq(false), eq(false), any(), any(Pageable.class));
        verify(alertEventRepo, never()).countIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    @DisplayName("ayar KAPALI: scope=others/all isteği mine'a düşer (bugünkü takım kapsamı), çip sayısı yok, visible_to_all=false")
    void list_switchOff_behavesLikeToday() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.visible_to_all").value(false))
                .andExpect(jsonPath("$.scope_counts").doesNotExist());
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(true), eq(false), eq(List.of(OWN)), any(Pageable.class));
        verify(alertEventRepo, never()).countIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    @DisplayName("görüş kapsamı BOŞ kullanıcı: 'Takımımın' boş (sorgu yok), 'Diğer ekiplerin' = tümü; oturumda kapsam yoksa kapalı düşer")
    void list_emptyScope() throws Exception {
        MockHttpSession lonely = session("yalniz", "USER", List.of(), List.of());
        mvc.perform(get("/api/monitoring/incidents").session(lonely))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.scope_counts.mine").value(0))
                .andExpect(jsonPath("$.scope_counts.others").value(40));
        verify(alertEventRepo, never()).findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));

        mvc.perform(get("/api/monitoring/incidents").param("scope", "others").session(lonely))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("others"));
        verify(alertEventRepo).findIncidents(any(), any(), any(), any(), any(), eq(false), eq(false), any(), any(Pageable.class));

        // viewTeamIds niteliği hiç yok (bozuk/eski oturum) + ayar kapalı: eskiden kapsamsız sorguya düşüyordu, artık boş.
        switchOn(false);
        MockHttpSession noScope = session("eski", "USER", null, null);
        mvc.perform(get("/api/monitoring/incidents").session(noScope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        verify(alertEventRepo, times(1)).findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class));
    }

    // ══ Satır bayrakları + veri azaltma ═════════════════════════════════════════════════════════

    @Test
    @DisplayName("can_manage/can_act/can_delete: yabancı satır false; damgalı kendi + envanter UG üzerinden kendi true; USER silemez")
    void rowFlags_user() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(2))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[0].can_act").value(false))
                .andExpect(jsonPath("$.data[0].can_delete").value(false))
                .andExpect(jsonPath("$.data[0].team_name").value("Takım B"))
                .andExpect(jsonPath("$.data[1].id").value(1))
                .andExpect(jsonPath("$.data[1].can_manage").value(true))
                .andExpect(jsonPath("$.data[1].can_act").value(true))
                .andExpect(jsonPath("$.data[1].can_delete").value(false))
                .andExpect(jsonPath("$.data[2].id").value(3))
                .andExpect(jsonPath("$.data[2].can_manage").value(true))   // UG takımı benim
                .andExpect(jsonPath("$.data[2].team_id").value(9));        // gösterilen takım yine SY (sütun kuralı)
    }

    @Test
    @DisplayName("veri azaltma: yabancı satırda izlemenin iç kimliği (monitor_id) YOK, kendi satırda var; ad/tür/sekme korunur")
    void foreignRow_dropsMonitorId() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].monitor.monitor_id").isEmpty())
                .andExpect(jsonPath("$.data[0].monitor.name").value("Başka site"))
                .andExpect(jsonPath("$.data[0].monitor.type").value("http"))
                .andExpect(jsonPath("$.data[1].monitor.monitor_id").value(70))
                // bildirim alıcısı / teslimat / arama kaydı alanı olay DTO'sunda HİÇ yok (ayrı, takım kapsamlı uçlar)
                .andExpect(jsonPath("$.data[0].recipients").doesNotExist())
                .andExpect(jsonPath("$.data[0].notifications").doesNotExist())
                .andExpect(jsonPath("$.data[0].noc_calls").doesNotExist());
    }

    @Test
    @DisplayName("AUDIT (7/24 operatörü): her olay kendi kapsamında görünür ama can_act=false (alerts.actions yok); global admin silebilir")
    void rowFlags_auditAndGlobalAdmin() throws Exception {
        mvc.perform(get("/api/monitoring/incidents").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].can_manage").value(true))
                .andExpect(jsonPath("$.data[0].can_act").value(false))
                .andExpect(jsonPath("$.data[0].can_delete").value(false));
        mvc.perform(get("/api/monitoring/incidents").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].can_act").value(true))
                .andExpect(jsonPath("$.data[0].can_delete").value(true));
        // Kapsamlı müdür rol ADMIN olsa da silemez (isGlobalAdmin, "ADMIN".equals DEĞİL)
        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].can_act").value(true))
                .andExpect(jsonPath("$.data[1].can_delete").value(false));
    }

    // ══ Tekil olay + yorumlar (OKUMA) ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("başka ekibin olayı: detay + yorum dizisi OKUNUR (salt okunur bayraklarla); ayar kapalıyken bugünkü gibi 403")
    void detailAndComments_foreignReadable() throws Exception {
        AlertComment c = new AlertComment();
        c.setId(31L); c.setAlertEventId(2L); c.setBody("DB yeniden başlatıldı"); c.setAuthorUsername("kisib"); c.setAuthorName("Kişi B");
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(2L)).thenReturn(List.of(c));

        for (MockHttpSession s : List.of(user(), teamAdmin(), scopedAdmin())) {
            mvc.perform(get("/api/monitoring/incidents/2").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(2))
                    .andExpect(jsonPath("$.data.can_manage").value(false))
                    .andExpect(jsonPath("$.data.can_act").value(false))
                    .andExpect(jsonPath("$.data.monitor.monitor_id").isEmpty());
            mvc.perform(get("/api/monitoring/incidents/2/comments").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].body").value("DB yeniden başlatıldı"));
        }
        // Kendi olayı: bayraklar açık, izleme kimliği var
        mvc.perform(get("/api/monitoring/incidents/1").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(true))
                .andExpect(jsonPath("$.data.can_act").value(true))
                .andExpect(jsonPath("$.data.monitor.monitor_id").value(70));

        switchOn(false);
        mvc.perform(get("/api/monitoring/incidents/2").session(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/incidents/2/comments").session(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/incidents/1").session(user())).andExpect(status().isOk());
    }

    // ══ YAZMA uçları: başka ekibin olayında HER ZAMAN 403 ════════════════════════════════════════

    @Test
    @DisplayName("POST yorum: başka ekibin olayına USER/TEAM_ADMIN/kapsamlı müdür 403 (ayar AÇIKKEN de); AUDIT 403 (alerts.actions yok)")
    void addComment_foreign_forbidden() throws Exception {
        for (MockHttpSession s : List.of(user(), teamAdmin(), scopedAdmin(), audit())) {
            mvc.perform(post("/api/monitoring/incidents/2/comments").session(s)
                            .contentType("application/json").content("{\"body\":\"müdahale denemesi\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(commentRepo, never()).save(any());
        verify(auditService, never()).recordAction(eq("INCIDENT_COMMENT_ADD"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DELETE yorum: başka ekibin olayındaki yorum — KENDİ yazdığı olsa bile — 403; soft-delete yazılmaz")
    void deleteComment_foreign_forbidden() throws Exception {
        AlertComment mine = new AlertComment();
        mine.setId(41L); mine.setAlertEventId(2L); mine.setAuthorUsername("kisia"); mine.setBody("eski yorumum");
        AlertComment theirs = new AlertComment();
        theirs.setId(42L); theirs.setAlertEventId(2L); theirs.setAuthorUsername("kisib"); theirs.setBody("onların");
        when(commentRepo.findById(41L)).thenReturn(Optional.of(mine));
        when(commentRepo.findById(42L)).thenReturn(Optional.of(theirs));
        for (MockHttpSession s : List.of(user(), teamAdmin(), scopedAdmin())) {
            mvc.perform(delete("/api/monitoring/incidents/comments/41").session(s)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/monitoring/incidents/comments/42").session(s)).andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/monitoring/incidents/comments/42").session(audit())).andExpect(status().isForbidden());
        verify(commentRepo, never()).save(any());
    }

    @Test
    @DisplayName("DELETE olay: başka ekibin (ve kendi) olayını global olmayan hiç kimse silemez — kapsamlı müdür dahil; global admin siler")
    void deleteIncident_nonGlobal_forbidden() throws Exception {
        for (MockHttpSession s : List.of(user(), teamAdmin(), scopedAdmin(), audit())) {
            mvc.perform(delete("/api/monitoring/incidents/2").session(s)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/monitoring/incidents/1").session(s)).andExpect(status().isForbidden());
        }
        verify(alertEventRepo, never()).deleteById(anyLong());

        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(2L)).thenReturn(List.of());
        mvc.perform(delete("/api/monitoring/incidents/2").session(globalAdmin())).andExpect(status().isOk());
        verify(alertEventRepo).deleteById(2L);
    }

    @Test
    @DisplayName("kendi olayı: yazma yolları DEĞİŞMEDİ — USER yorum ekler (200)")
    void ownIncident_writesUnchanged() throws Exception {
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(i -> { AlertComment c = i.getArgument(0); c.setId(77L); return c; });
        mvc.perform(post("/api/monitoring/incidents/1/comments").session(user())
                        .contentType("application/json").content("{\"body\":\"bakıyoruz\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/incidents/3/comments").session(user())   // envanter UG üzerinden kendi
                        .contentType("application/json").content("{\"body\":\"UG takımı olarak bakıyoruz\"}"))
                .andExpect(status().isOk());
        verify(commentRepo, times(2)).save(any(AlertComment.class));
    }
    // ══ 7/24 izleme ekibi operatörü (2026-10-04): her olayı TAM okur, her olaya YORUM yazar; başka eylem yok ══════════

    /** Takım OWN'un USER'ı — 7/24 izleme ekibi takımında olduğu için operatör (bayrak oturumda, rol değişmez). */
    private static MockHttpSession nocOperator() {
        MockHttpSession s = user();
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        return s;
    }

    @Test
    @DisplayName("7/24 operatörü: başka ekibin olayını ayar KAPALIYKEN de okur (tam: izleme bağlantısı); can_act/can_manage KAPALI, can_comment AÇIK")
    void nocOperator_readsForeignFullyRegardlessOfSetting() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/monitoring/incidents/2").session(nocOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(false))
                .andExpect(jsonPath("$.data.can_act").value(false))
                .andExpect(jsonPath("$.data.can_delete").value(false))
                .andExpect(jsonPath("$.data.can_comment").value(true))
                .andExpect(jsonPath("$.data.monitor.monitor_id").value(90));
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(2L)).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/incidents/2/comments").session(nocOperator())).andExpect(status().isOk());
        mvc.perform(get("/api/monitoring/incidents").param("scope", "all").session(nocOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.visible_to_all").value(true));
        // Sıradan kullanıcı: ayar kapalıyken başka ekibin olayı 403; yorum bayrağı kendi olayında alerts.actions'a bağlı
        mvc.perform(get("/api/monitoring/incidents/2").session(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/incidents/1").session(user()))
                .andExpect(jsonPath("$.data.can_comment").value(true));
        mvc.perform(get("/api/monitoring/incidents/1").session(audit()))
                .andExpect(jsonPath("$.data.can_comment").value(false));
    }

    @Test
    @DisplayName("7/24 operatörü: başka ekibin olayına YORUM yazar (denetim kaydı ile); kendi yorumunu siler, başkasınınkini silemez; olay silemez")
    void nocOperator_commentsOnForeignIncident() throws Exception {
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(i -> { AlertComment c = i.getArgument(0); c.setId(88L); return c; });
        mvc.perform(post("/api/monitoring/incidents/2/comments").session(nocOperator())
                        .contentType("application/json").content("{\"body\":\"7/24 aradı: nöbetçi bakıyor\"}"))
                .andExpect(status().isOk());
        verify(commentRepo, times(1)).save(any(AlertComment.class));
        verify(auditService).recordAction(eq("INCIDENT_COMMENT_ADD"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("ALERT_EVENT"), eq("2"), anyString());

        AlertComment mine = new AlertComment();
        mine.setId(41L); mine.setAlertEventId(2L); mine.setAuthorUsername("kisia"); mine.setBody("7/24 notum");
        AlertComment theirs = new AlertComment();
        theirs.setId(42L); theirs.setAlertEventId(2L); theirs.setAuthorUsername("kisib"); theirs.setBody("onların");
        when(commentRepo.findById(41L)).thenReturn(Optional.of(mine));
        when(commentRepo.findById(42L)).thenReturn(Optional.of(theirs));
        mvc.perform(delete("/api/monitoring/incidents/comments/42").session(nocOperator())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/monitoring/incidents/comments/41").session(nocOperator())).andExpect(status().isOk());
        mvc.perform(delete("/api/monitoring/incidents/2").session(nocOperator())).andExpect(status().isForbidden());
        verify(alertEventRepo, never()).deleteById(anyLong());
    }

    // ══ 2026-10-09: bağımsız izleme olayında yazma yalnız SAHİBİ takımın (AlertOwnership) ═══════════════════════════════

    @Test
    @DisplayName("bağımsız olay (FOREIGN'ın Ping'i), host'un envanter UG'si OWN: OWN okur (liste kuralı) ama salt okunur — yorum 403; 7/24 operatörü yorum yazar; envanter olayı (ACCESSIBILITY) eskisi gibi OWN'un")
    void standaloneOnMyInventoryHost_readOnlyForInventoryTeam() throws Exception {
        switchOn(false);   // org geneli okuma KAPALI: okuma yalnız liste kuralından (envanter kolu) gelir
        AlertEvent ping = event(4L, FOREIGN, "ug.example.com");
        ping.setAlertType("PING_DOWN");
        ping.setContextJson("{\"team_id\":9,\"monitor_id\":31}");
        when(alertEventRepo.findById(4L)).thenReturn(Optional.of(ping));
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(i -> { AlertComment c = i.getArgument(0); c.setId(91L); return c; });

        mvc.perform(get("/api/monitoring/incidents/4").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(false))
                .andExpect(jsonPath("$.data.can_act").value(false))
                .andExpect(jsonPath("$.data.can_comment").value(false));
        mvc.perform(post("/api/monitoring/incidents/4/comments").session(user())
                        .contentType("application/json").content("{\"body\":\"envanter takımı olarak yorum denemesi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/incidents/4/comments").session(nocOperator())
                        .contentType("application/json").content("{\"body\":\"7/24 aradı: sahibi takım bakıyor\"}"))
                .andExpect(status().isOk());
        // Sahibi takımın üyesi (FOREIGN) yorum yazar.
        mvc.perform(post("/api/monitoring/incidents/4/comments").session(session("kisif", "USER", List.of(FOREIGN), List.of()))
                        .contentType("application/json").content("{\"body\":\"sahibi takım olarak bakıyoruz\"}"))
                .andExpect(status().isOk());
        // Envanter gibi yönlenen olay (#3, UG = OWN) değişmedi.
        mvc.perform(post("/api/monitoring/incidents/3/comments").session(user())
                        .contentType("application/json").content("{\"body\":\"UG takımı olarak bakıyoruz\"}"))
                .andExpect(status().isOk());
        verify(commentRepo, times(3)).save(any(AlertComment.class));

        // Liste satırı: "Takımımın olayları"nda görünse de (sorgu envanter kuralını taşır) bayraklar kapalı.
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ping, ugEv)));
        mvc.perform(get("/api/monitoring/incidents").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(4))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[0].can_comment").value(false))
                .andExpect(jsonPath("$.data[1].id").value(3))
                .andExpect(jsonPath("$.data[1].can_manage").value(true));
    }
}
