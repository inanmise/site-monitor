package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * İzleme sayfası genişletilmiş toplu düzenleme ucu (2026-11-19): satır yeniden okunur, kapsam / paylaşım / değişmiyor kararı
 * burada; değişen satır TEKİL güncelleme ucundan yalnız değişen anahtarla geçer; hata satırı diğerlerini durdurmaz; özet denetim.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorBulkEditControllerTest {

    @Mock PermissionService permissionService;
    @Mock AuditService auditService;
    @Mock MonitoringController monitoring;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock DbQueryMonitorRepository dbQueryRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock NotificationGroupRepository groupRepo;
    @Mock TeamRepository teamRepo;

    MonitorBulkEditController controller;
    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new MonitorBulkEditController(permissionService, auditService);
        controller.monitoring = monitoring;
        controller.httpRepo = httpRepo;
        controller.pageSpeedRepo = pageSpeedRepo;
        controller.portRepo = portRepo;
        controller.dbQueryRepo = dbQueryRepo;
        controller.inventoryRepo = inventoryRepo;
        controller.groupRepo = groupRepo;
        controller.teamRepo = teamRepo;
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(ObjectProvider.class)))
                .build();
        admin = new MockHttpSession();
        admin.setAttribute("systemRole", "ADMIN");   // viewTeamIds yok → global yönetici
        Team a = new Team();
        a.setId(5L);
        a.setName("Takim A");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(a));
        when(monitoring.updateHttp(anyLong(), anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("success", true)));
        when(monitoring.updatePageSpeed(anyLong(), anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("success", true)));
        when(monitoring.updatePort(anyLong(), anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("success", true)));
        when(monitoring.updateDbQuery(anyLong(), anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("success", true)));
    }

    private static HttpMonitor http(long id, long team, int interval, String tags) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setName("Ödeme servisi " + id);
        m.setUrl("https://odeme" + id + ".example.com/");
        m.setTeamId(team);
        m.setIntervalSeconds(interval);
        m.setTags(tags);
        return m;
    }

    private MockHttpSession user(List<Long> teams, List<Long> grantView, List<Long> grantEdit) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", teams.get(0));
        s.setAttribute("viewTeamIds", teams);
        s.setAttribute("memberTeamIds", teams);
        if (grantView != null) s.setAttribute(SessionScope.ATTR_GRANT_VIEW, grantView);
        if (grantEdit != null) s.setAttribute(SessionScope.ATTR_GRANT_EDIT, grantEdit);
        return s;
    }

    private org.springframework.test.web.servlet.ResultActions call(MockHttpSession s, String json) throws Exception {
        return mvc.perform(post("/api/monitoring/bulk-edit").session(s).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("aralık: değişen satır tekil uçtan YALNIZ intervalSeconds ile geçer; aynı değer 'unchanged', çağrı yok; özet denetim")
    void intervalDoneAndUnchanged() throws Exception {
        when(httpRepo.findById(1L)).thenReturn(Optional.of(http(1, 5, 300, "web")));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(http(2, 5, 600, "web")));
        call(admin, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1,2],\"interval_seconds\":600}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("done"))
                .andExpect(jsonPath("$.data.results[0].name").value("Ödeme servisi 1"))
                .andExpect(jsonPath("$.data.results[0].team_name").value("Takim A"))
                .andExpect(jsonPath("$.data.results[1].reason").value("unchanged"));
        verify(monitoring).updateHttp(eq(1L), eq(Map.of("intervalSeconds", 600)), any());
        verify(monitoring, never()).updateHttp(eq(2L), anyMap(), any());
        verify(auditService, times(1)).recordAction(eq("MONITOR_BULK_EDIT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("MONITOR"), eq("http"), anyString());
    }

    @Test
    @DisplayName("görünmeyen takımın izlemesi 'not_found' (ad yok); yalnız görme paylaşımı 'out_of_scope'")
    void scope() throws Exception {
        when(httpRepo.findById(1L)).thenReturn(Optional.of(http(1, 9, 300, "web")));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(http(2, 7, 300, "web")));
        when(httpRepo.findById(3L)).thenReturn(Optional.empty());
        MockHttpSession s = user(List.of(5L), List.of(7L), null);
        call(s, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1,2,3],\"interval_seconds\":600}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("not_found"))
                .andExpect(jsonPath("$.data.results[0].name").doesNotExist())
                .andExpect(jsonPath("$.data.results[1].reason").value("out_of_scope"))
                .andExpect(jsonPath("$.data.results[1].name").value("Ödeme servisi 2"))
                .andExpect(jsonPath("$.data.results[2].reason").value("not_found"));
        verify(monitoring, never()).updateHttp(anyLong(), anyMap(), any());
    }

    @Test
    @DisplayName("paylaşımla (EDIT) yazan: aralık ve etiket uygulanır, seviye / kanal 'routing_locked'")
    void grantEditorRoutingLocked() throws Exception {
        when(httpRepo.findById(1L)).thenReturn(Optional.of(http(1, 7, 300, "web")));
        MockHttpSession s = user(List.of(5L), List.of(7L), List.of(7L));
        call(s, "{\"type\":\"http\",\"action\":\"alert_level\",\"ids\":[1],\"alert_level\":\"CRITICAL\"}")
                .andExpect(jsonPath("$.data.results[0].reason").value("routing_locked"));
        call(s, "{\"type\":\"http\",\"action\":\"channels\",\"ids\":[1],\"notify_email\":false}")
                .andExpect(jsonPath("$.data.results[0].reason").value("routing_locked"));
        call(s, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1],\"interval_seconds\":900}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"));
        verify(monitoring, times(1)).updateHttp(eq(1L), eq(Map.of("intervalSeconds", 900)), any());
    }

    @Test
    @DisplayName("CRON zamanlamalı Sayfa Hızı: aralık 'schedule_cron'; aralık zamanlamalı (INTERVAL) satır uygulanır")
    void cronSkipped() throws Exception {
        PageSpeedMonitor cron = new PageSpeedMonitor();
        cron.setId(1L); cron.setName("Ana sayfa"); cron.setTeamId(5L); cron.setIntervalSeconds(1800); cron.setScheduleMode("CRON");
        PageSpeedMonitor plain = new PageSpeedMonitor();
        plain.setId(2L); plain.setName("Kampanya"); plain.setTeamId(5L); plain.setIntervalSeconds(1800);
        when(pageSpeedRepo.findById(1L)).thenReturn(Optional.of(cron));
        when(pageSpeedRepo.findById(2L)).thenReturn(Optional.of(plain));
        call(admin, "{\"type\":\"pagespeed\",\"action\":\"interval\",\"ids\":[1,2],\"interval_seconds\":3600}")
                .andExpect(jsonPath("$.data.results[0].reason").value("schedule_cron"))
                .andExpect(jsonPath("$.data.results[1].status").value("done"));
        verify(monitoring, never()).updatePageSpeed(eq(1L), anyMap(), any());
    }

    @Test
    @DisplayName("etiketler: ekle / çıkar birleşik metin; son etiketi silmek 'last_tag'; aynı küme 'unchanged'")
    void tags() throws Exception {
        when(httpRepo.findById(1L)).thenReturn(Optional.of(http(1, 5, 300, "web, prod")));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(http(2, 5, 300, "old")));
        when(httpRepo.findById(3L)).thenReturn(Optional.of(http(3, 5, 300, "new,web")));
        call(admin, "{\"type\":\"http\",\"action\":\"tags\",\"ids\":[1,2,3],\"tags_add\":[\"new\"],\"tags_remove\":[\"PROD\",\"old\"]}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"))
                .andExpect(jsonPath("$.data.results[1].status").value("done"))
                .andExpect(jsonPath("$.data.results[2].reason").value("unchanged"));
        verify(monitoring).updateHttp(eq(1L), eq(Map.of("tags", "web,new")), any());
        verify(monitoring).updateHttp(eq(2L), eq(Map.of("tags", "new")), any());
        when(httpRepo.findById(4L)).thenReturn(Optional.of(http(4, 5, 300, "only")));
        call(admin, "{\"type\":\"http\",\"action\":\"tags\",\"ids\":[4],\"tags_remove\":[\"only\"]}")
                .andExpect(jsonPath("$.data.results[0].reason").value("last_tag"));
    }

    @Test
    @DisplayName("kanallar + grup: başka takımın grubu 'group_other_team'; varsayılana dönüş null yazar; silinmiş grup 400")
    void channelsAndGroup() throws Exception {
        HttpMonitor a = http(1, 5, 300, "web");
        a.setNotificationGroupId(11L);
        HttpMonitor b = http(2, 6, 300, "web");
        when(httpRepo.findById(1L)).thenReturn(Optional.of(a));
        when(httpRepo.findById(2L)).thenReturn(Optional.of(b));
        NotificationGroup g = new NotificationGroup();
        g.setId(20L); g.setTeamId(5L); g.setActive(true);
        when(groupRepo.findById(20L)).thenReturn(Optional.of(g));
        call(admin, "{\"type\":\"http\",\"action\":\"channels\",\"ids\":[1,2],\"notify_webhook\":false,\"group_mode\":\"set\",\"notification_group_id\":20}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"))
                .andExpect(jsonPath("$.data.results[1].reason").value("group_other_team"));
        verify(monitoring).updateHttp(eq(1L), eq(Map.of("notifyWebhook", false, "notificationGroupId", 20L)), any());

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        call(admin, "{\"type\":\"http\",\"action\":\"channels\",\"ids\":[1],\"notify_email\":true,\"group_mode\":\"default\"}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"));
        verify(monitoring, times(2)).updateHttp(eq(1L), body.capture(), any());
        assertThat(body.getValue()).containsOnlyKeys("notificationGroupId").containsEntry("notificationGroupId", null);   // e-posta zaten açık

        g.setActive(false);
        call(admin, "{\"type\":\"http\",\"action\":\"channels\",\"ids\":[1],\"group_mode\":\"set\",\"notification_group_id\":20}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("notification_group_id"));
    }

    @Test
    @DisplayName("veritabanı sorgusu snake_case anahtar alır; envanter türevi Port satırının takımı envanterin takımıdır")
    void dbQueryWireAndDerivedPort() throws Exception {
        DbQueryMonitor q = new DbQueryMonitor();
        q.setId(1L); q.setName("Bekleyen siparişler"); q.setTeamId(5L); q.setIntervalSeconds(300);
        when(dbQueryRepo.findById(1L)).thenReturn(Optional.of(q));
        call(admin, "{\"type\":\"dbquery\",\"action\":\"alert_level\",\"ids\":[1],\"alert_level\":\"HIGH\"}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"));
        verify(monitoring).updateDbQuery(eq(1L), eq(Map.of("alert_level", "HIGH")), any());

        PortMonitor p = new PortMonitor();
        p.setId(4L); p.setHost("db.example.com"); p.setPort(5432); p.setTeamId(9L); p.setStandalone(false); p.setIntervalSeconds(300);
        when(portRepo.findById(4L)).thenReturn(Optional.of(p));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("db.example.com"); inv.setTeamId(5L);
        when(inventoryRepo.findByDomain("db.example.com")).thenReturn(Optional.of(inv));
        MockHttpSession s = user(List.of(5L), null, null);
        call(s, "{\"type\":\"port\",\"action\":\"interval\",\"ids\":[4],\"interval_seconds\":600}")
                .andExpect(jsonPath("$.data.results[0].status").value("done"))
                .andExpect(jsonPath("$.data.results[0].name").value("db.example.com:5432"))
                .andExpect(jsonPath("$.data.results[0].team_id").value(5));
    }

    @Test
    @DisplayName("tekil uç yanıtları: 400 metni 'failed'; SecurityException 'out_of_scope'; beklenmeyen hata genel metin — diğer satır sürer")
    void updateOutcomes() throws Exception {
        for (long i = 1; i <= 4; i++) when(httpRepo.findById(i)).thenReturn(Optional.of(http(i, 5, 300, "web")));
        when(monitoring.updateHttp(eq(1L), anyMap(), any())).thenReturn(ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", "Aralık kabul edilmedi.")));
        doThrow(new SecurityException("no")).when(monitoring).updateHttp(eq(2L), anyMap(), any());
        doThrow(new IllegalStateException("boom java.sql.SQLException")).when(monitoring).updateHttp(eq(3L), anyMap(), any());
        call(admin, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1,2,3,4],\"interval_seconds\":900}")
                .andExpect(jsonPath("$.data.results[0].status").value("failed"))
                .andExpect(jsonPath("$.data.results[0].message").value("Aralık kabul edilmedi."))
                .andExpect(jsonPath("$.data.results[1].reason").value("out_of_scope"))
                .andExpect(jsonPath("$.data.results[2].status").value("failed"))
                .andExpect(jsonPath("$.data.results[2].message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))))
                .andExpect(jsonPath("$.data.results[3].status").value("done"))
                .andExpect(jsonPath("$.data.failed").value(2))
                .andExpect(jsonPath("$.data.done").value(1));
    }

    @Test
    @DisplayName("geçersiz istek 400 + alan, hiçbir satıra dokunulmaz; izin yoksa 403; güncelleme bileşeni yoksa 503")
    void requestErrors() throws Exception {
        call(admin, "{\"type\":\"domain\",\"action\":\"interval\",\"ids\":[1],\"interval_seconds\":60}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("interval_seconds"));
        verify(monitoring, never()).updateDomain(anyLong(), anyMap(), any());
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: monitoring.crud/edit"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.crud"), eq("edit"));
        call(admin, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1],\"interval_seconds\":600}")
                .andExpect(status().isForbidden());
        org.mockito.Mockito.reset(permissionService);
        controller.monitoring = null;
        call(admin, "{\"type\":\"http\",\"action\":\"interval\",\"ids\":[1],\"interval_seconds\":600}")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("plan(): seviye karşılaştırması etkin seviyeyle (null = WARNING)")
    void levelUnchangedForNull() {
        var req = com.sitemonitor.service.monitorbulk.MonitorBulkEdit.parse(Map.of("type", "http", "action", "alert_level",
                "ids", List.of(1), "alert_level", "WARNING"));
        var row = new MonitorBulkEditController.Row(1L, "x", 5L, 300, null, null, null, null, "web", false);
        assertThat(MonitorBulkEditController.plan(req, row, null, new java.util.LinkedHashMap<>())).isEqualTo("unchanged");
        var row2 = new MonitorBulkEditController.Row(1L, "x", 5L, 300, "HIGH", null, null, null, "web", false);
        Map<String, Object> patch = new java.util.LinkedHashMap<>();
        assertThat(MonitorBulkEditController.plan(req, row2, null, patch)).isNull();
        assertThat(patch).containsEntry("alertLevel", "WARNING");
    }
}
