package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentsController.class)
class IncidentsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IncidentsController controller;

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
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    // Auth + metrics interceptor bağımlılıkları (WebMvc slice)
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);   // ADMIN + viewTeamIds YOK → global admin/viewer
        return s;
    }

    private static AlertEvent httpDown500() {
        AlertEvent e = new AlertEvent();
        e.setId(1L);
        e.setDomain("https://x.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("CRITICAL");
        e.setResolved(false);
        e.setCreatedAt("2026-07-10T10:00:00");
        e.setContextJson("{\"http_status\":500}");
        return e;
    }

    @Test
    @DisplayName("GET /incidents: en yeni türler entegre — SCRIPTED_FAIL→SYNTHETIC/down/tab=scripted, PAGE_INTEGRITY→INTEGRITY/content/tab=page (unknown/cert'e düşmez)")
    void list_mapsNewestMonitorTypes() throws Exception {
        AlertEvent scripted = new AlertEvent();
        scripted.setId(2L); scripted.setDomain("Login akışı"); scripted.setAlertType("SCRIPTED_FAIL");
        scripted.setAlertLevel("CRITICAL"); scripted.setResolved(false); scripted.setCreatedAt("2026-07-10T10:00:00");
        AlertEvent pageInt = new AlertEvent();
        pageInt.setId(3L); pageInt.setDomain("https://x/campaign"); pageInt.setAlertType("PAGE_INTEGRITY");
        pageInt.setAlertLevel("WARNING"); pageInt.setResolved(false); pageInt.setCreatedAt("2026-07-10T11:00:00");
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(scripted, pageInt)));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());
        com.sitemonitor.model.ScriptedMonitor sm = new com.sitemonitor.model.ScriptedMonitor();
        sm.setId(20L); sm.setName("Login akışı");
        when(scriptedMonitorRepo.findAll()).thenReturn(List.of(sm));
        com.sitemonitor.model.PageMonitor pm = new com.sitemonitor.model.PageMonitor();
        pm.setId(30L); pm.setName("Kampanya"); pm.setUrl("https://x/campaign");
        when(pageMonitorRepo.findAll()).thenReturn(List.of(pm));

        mvc.perform(get("/api/monitoring/incidents").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].root_cause.code").value("SYNTHETIC"))
                .andExpect(jsonPath("$.data[0].root_cause.category").value("down"))
                .andExpect(jsonPath("$.data[0].monitor.tab").value("scripted"))
                .andExpect(jsonPath("$.data[0].monitor.monitor_id").value(20))
                .andExpect(jsonPath("$.data[1].root_cause.code").value("INTEGRITY"))
                .andExpect(jsonPath("$.data[1].root_cause.category").value("content"))
                .andExpect(jsonPath("$.data[1].monitor.tab").value("page"))
                .andExpect(jsonPath("$.data[1].monitor.monitor_id").value(30));
    }

    @Test
    @DisplayName("GET /incidents: HTTP_DOWN + http_status=500 → root_cause 500/server_error, status ongoing, monitor=domain")
    void list_returnsIncidentDto() throws Exception {
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(httpDown500())));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());
        when(httpMonitorRepo.findAll()).thenReturn(List.of());   // eşleşen monitör yok → ad = domain

        mvc.perform(get("/api/monitoring/incidents").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ongoing"))
                .andExpect(jsonPath("$.data[0].root_cause.code").value("500"))
                .andExpect(jsonPath("$.data[0].root_cause.category").value("server_error"))
                .andExpect(jsonPath("$.data[0].monitor.name").value("https://x.example.com"))
                .andExpect(jsonPath("$.data[0].monitor.tab").value("http"))
                .andExpect(jsonPath("$.data[0].comment_count").value(0));
    }

    @Test
    @DisplayName("DELETE /incidents/{id}: ADMIN olmayan → 403, silme çağrılmaz")
    void delete_nonAdmin_forbidden() throws Exception {
        mvc.perform(delete("/api/monitoring/incidents/1").session(session("USER")))
                .andExpect(status().isForbidden());
        verify(alertEventRepo, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("DELETE /incidents/{id}: ADMIN → 200, alarm + yorumları silinir")
    void delete_admin_ok() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        mvc.perform(delete("/api/monitoring/incidents/1").session(session("ADMIN")))
                .andExpect(status().isOk());
        verify(alertEventRepo).deleteById(1L);
    }

    @Test
    @DisplayName("POST /incidents/{id}/comments: yorum eklenir (kaydedilir)")
    void addComment_saves() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(inv -> { AlertComment c = inv.getArgument(0); c.setId(7L); return c; });
        mvc.perform(post("/api/monitoring/incidents/1/comments").session(session("ADMIN"))
                        .contentType("application/json").content("{\"body\":\"needs attention\"}"))
                .andExpect(status().isOk());
        verify(commentRepo).save(any(AlertComment.class));
    }

    @Test
    @DisplayName("POST /incidents/{id}/comments: boş gövde → 400")
    void addComment_empty_rejected() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        mvc.perform(post("/api/monitoring/incidents/1/comments").session(session("ADMIN"))
                        .contentType("application/json").content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(commentRepo, never()).save(any());
    }

    @Test
    @DisplayName("SOZLESME: katalogdaki HER alarm tipi bir kok-neden kategorisine duser (unknown YOK)")
    void rootCause_everyCatalogAlertType_hasCategory() {
        // Bu kapi olmadan yeni bir izleme turu sessizce "bilinmeyen" kok nedene dusuyor: olay
        // ekraninda renk/etiket kayboluyor ve kategoriye gore gruplama calismiyor. Gercekten
        // yasandi — PAGESPEED_DOWN buraya eklenmemisti (PAGESPEED_SLOW `_SLOW` kuralina takildigi
        // icin dogru calisiyordu, yani hata YARIM gorunuyordu).
        java.util.List<String> unknown = new java.util.ArrayList<>();
        com.sitemonitor.service.MonitorTypeCatalog.ALERT_TYPES.values().stream()
                .flatMap(java.util.Set::stream)
                .forEach(alertType -> {
                    String cat = controller.rootCause(alertType, java.util.Map.of()).get("category");
                    if ("unknown".equals(cat)) unknown.add(alertType);
                });
        org.assertj.core.api.Assertions.assertThat(unknown)
                .as("kok-neden kategorisi olmayan alarm tipleri")
                .isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: katalogdaki HER alarm tipi DOGRU izleme ailesine ve SEKMESINE duser")
    void familyAndTab_everyCatalogAlertType_resolvesToItsOwnType() {
        // Kardes kapi: yukaridaki test yalniz rootCause()'u geziyordu, family()/tabFor() ikilisi
        // kapsam DISIydi — PAGESPEED_DOWN tam bu bosluktan gecti. `"PAGESPEED_DOWN"
        // .startsWith("PAGE_")` FALSE oldugu icin tip "cert" fallback'ine, sekme de "dashboard"a
        // dusuyordu: olay ekraninda alarm sertifika olayi gibi gruplaniyor, "izlemeye git" linki
        // Sayfa Hizi sekmesi yerine Panoya gidiyor ve monitor_id null kaliyordu.
        //
        // KASITLI ISTISNA: cert ailesi. ACCESSIBILITY katalogda "http" altinda toplanmis (haftalik
        // rapor ekseni) ama alarmi SchedulerService sertifika ENVANTERI satirlarindan uretiyor
        // (AlertEvent.domain = inv.getDomain()), yani olay ekraninda "cert"/pano DOGRU hedeftir.
        java.util.Map<String, String> wrong = new java.util.LinkedHashMap<>();
        com.sitemonitor.service.MonitorTypeCatalog.ALERT_TYPES.forEach((type, alertTypes) -> {
            if ("cert".equals(type)) return;                       // ayri eksen, yukaridaki nota bak
            for (String alertType : alertTypes) {
                if ("ACCESSIBILITY".equals(alertType)) continue;   // kasitli istisna
                String fam = IncidentsController.family(alertType);
                String tab = IncidentsController.tabFor(fam);
                if (!type.equals(fam) || "dashboard".equals(tab)) {
                    wrong.put(alertType, "aile=" + fam + " (beklenen " + type + "), sekme=" + tab);
                }
            }
        });
        org.assertj.core.api.Assertions.assertThat(wrong)
                .as("yanlis aileye/sekmeye dusen alarm tipleri")
                .isEmpty();
    }

    // ── Yorum silme: OKUMA kapsamı YAZMA yetkisi vermez ─────────────────────
    //
    // requireIncidentScope viewTeamIds ile sorar; silme kapısı ise yalnız ROL DİZESİNE bakıyordu
    // ("TEAM_ADMIN".equals(role)). Müdürün görüş alanı astlarının takımlarını kapsadığı için
    // A takımının yöneticisi, yalnızca GÖREBİLDİĞİ B takımının yorumlarını silebiliyordu.

    /** Görüş alanı iki takımı kapsıyor, yönetim yetkisi yalnız birini (müdür deseni). */
    private MockHttpSession scopedSession(String role, java.util.List<Long> view, java.util.List<Long> manage) {
        MockHttpSession s = session(role);
        s.setAttribute("viewTeamIds", view);
        s.setAttribute("manageTeamIds", manage);
        return s;
    }

    private static AlertEvent teamAlert(long id, Long teamId) {
        AlertEvent e = httpDown500();
        e.setId(id);
        e.setTeamId(teamId);
        return e;
    }

    private static AlertComment comment(long id, long alertId, String author) {
        AlertComment c = new AlertComment();
        c.setId(id); c.setAlertEventId(alertId); c.setAuthorUsername(author); c.setBody("x");
        return c;
    }

    @Test
    @DisplayName("DELETE /comments: yalnız GÖRDÜĞÜ takımın yorumunu TEAM_ADMIN silemez -> 403")
    void deleteComment_viewOnlyTeam_returns403() throws Exception {
        when(commentRepo.findById(5L)).thenReturn(Optional.of(comment(5L, 1L, "baskasi")));
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(teamAlert(1L, 2L)));   // B takımı

        mvc.perform(delete("/api/monitoring/incidents/comments/5")
                        .session(scopedSession("TEAM_ADMIN", List.of(1L, 2L), List.of(1L))))
                .andExpect(status().isForbidden());
        verify(commentRepo, never()).save(any());
    }

    @Test
    @DisplayName("DELETE /comments: YÖNETTİĞİ takımın yorumunu silebilir -> 200")
    void deleteComment_managedTeam_returns200() throws Exception {
        when(commentRepo.findById(5L)).thenReturn(Optional.of(comment(5L, 1L, "baskasi")));
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(teamAlert(1L, 1L)));   // A takımı
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(delete("/api/monitoring/incidents/comments/5")
                        .session(scopedSession("TEAM_ADMIN", List.of(1L, 2L), List.of(1L))))
                .andExpect(status().isOk());
        verify(commentRepo).save(any(AlertComment.class));
    }

    @Test
    @DisplayName("DELETE /comments: kendi yorumunu her kullanıcı silebilir -> 200")
    void deleteComment_ownComment_returns200() throws Exception {
        when(commentRepo.findById(5L)).thenReturn(Optional.of(comment(5L, 1L, "u")));   // oturum username = "u"
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(teamAlert(1L, 1L)));
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(delete("/api/monitoring/incidents/comments/5")
                        .session(scopedSession("USER", List.of(1L), List.of())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /comments: kapsam dışı incident -> 403 (yorumun varlığı numaralandırılamaz)")
    void deleteComment_outOfViewScope_returns403() throws Exception {
        when(commentRepo.findById(5L)).thenReturn(Optional.of(comment(5L, 1L, "baskasi")));
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(teamAlert(1L, 9L)));
        when(inventoryRepo.findByDomain(any())).thenReturn(Optional.empty());

        mvc.perform(delete("/api/monitoring/incidents/comments/5")
                        .session(scopedSession("TEAM_ADMIN", List.of(1L), List.of(1L))))
                .andExpect(status().isForbidden());
    }
}
