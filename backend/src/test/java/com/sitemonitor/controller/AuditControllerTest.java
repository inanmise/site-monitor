package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import org.springframework.http.MediaType;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.DeviceHistoryService deviceHistoryService;
    @MockitoBean com.sitemonitor.repository.AppUserRepository appUserRepo;
    @MockitoBean com.sitemonitor.service.WeakAlgorithmReportService weakAlgoService;
    @MockitoBean com.sitemonitor.service.EmailNotificationService emailService;
    @MockitoBean com.sitemonitor.service.UserPushService userPushService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    private static LatestCheck lc(String domain, String sig, String keyAlgo, Integer keySize) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain);
        c.setSignatureAlgorithm(sig);
        c.setPublicKeyAlgorithm(keyAlgo);
        c.setPublicKeySize(keySize);
        c.setStatus("valid");
        return c;
    }

    @Test
    @DisplayName("listAudit: AUDIT 200 + sayfalı sonuç; USER 403")
    void listAudit_access() throws Exception {
        when(auditLogRepo.findAdvanced(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog()), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/admin/audit").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/admin/audit").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("event-types: katalog + kategori + sayac doner; /audit/{id} yoluna YUTULMAZ")
    void eventTypes_returnsCatalog() throws Exception {
        // Kritik yonlendirme sinavi: "/audit/event-types" literal yolu, "/audit/{id}" sablonundan
        // ONCE eslesmeli. Aksi halde uc, "event-types" dizesini Long'a cevirmeye calisip patlardi.
        when(auditService.eventTypeCounts()).thenReturn(java.util.Map.of("LOGIN", 42L));

        mvc.perform(get("/api/admin/audit/event-types").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.type == 'LOGIN')].count").value(42))
                .andExpect(jsonPath("$.data[?(@.type == 'LOGIN')].category").value("AUTH"))
                .andExpect(jsonPath("$.categories").isArray());

        mvc.perform(get("/api/admin/audit/event-types").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("audit/{id}: tekil satir doner; yoksa 404 (paylasilan olay baglantisinin sarti)")
    void auditById_singleRow() throws Exception {
        AuditLog row = new AuditLog();
        row.setId(77L);
        row.setEventType("USER_DELETE");
        when(auditLogRepo.findById(77L)).thenReturn(java.util.Optional.of(row));
        when(auditLogRepo.findById(999L)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/admin/audit/77").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event_type").value("USER_DELETE"));

        mvc.perform(get("/api/admin/audit/999").session(session("AUDIT")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/admin/audit/77").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("resourceHistory: AUDIT 200 + kayıt sayısı; USER 403 (izolasyon)")
    void resourceHistory_access() throws Exception {
        when(auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(eq("PORT_MONITOR"), eq("7"), any()))
                .thenReturn(List.of(new AuditLog()));
        mvc.perform(get("/api/admin/audit/resource/PORT_MONITOR/7").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/audit/resource/PORT_MONITOR/7").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("actorHistory: AUDIT 200 (bir kullanıcının tüm eylemleri)")
    void actorHistory_ok() throws Exception {
        when(auditLogRepo.findByActorIdOrderByEventTimeDesc(eq(5L), any())).thenReturn(List.of(new AuditLog()));
        mvc.perform(get("/api/admin/audit/actor/5").session(session("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("integrity: verifyChain sonucu (ok + checked) döner")
    void integrity_ok() throws Exception {
        when(auditService.verifyChain())
                .thenReturn(new com.sitemonitor.service.AuditService.ChainVerification(true, null, null, 42));
        mvc.perform(get("/api/admin/audit/integrity").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.checked").value(42));
    }

    @Test
    @DisplayName("export: CSV döner + dışa aktarma İŞLEMİ AUDIT_EXPORT olarak denetlenir (denetimin denetimi)")
    void export_selfAudited() throws Exception {
        when(auditLogRepo.findAdvanced(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog()), PageRequest.of(0, 1), 1));
        mvc.perform(get("/api/admin/audit/export?format=csv").session(session("AUDIT")))
                .andExpect(status().isOk());
        verify(auditService).recordAction(eq("AUDIT_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("AUDIT_LOG"), eq("export"), contains("csv"));
    }

    @Test
    @DisplayName("auditStats: ADMIN 200 + istatistik anahtarları")
    void auditStats_ok() throws Exception {
        // /audit/stats artık AuditService.buildStats()'ı (60 sn cache) çağırır — slice'ta mock.
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total_24h", 5L);
        stats.put("total_7d", 10L);
        when(auditService.buildStats()).thenReturn(stats);

        mvc.perform(get("/api/admin/audit/stats").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_24h").exists())
                .andExpect(jsonPath("$.data.total_7d").exists());
    }

    // ── Zayıf Algoritma Raporu (2026-09-12: gövde servisten, uçlar: rapor / dışa aktar / istisna / bildir) ──

    private static java.util.Map<String, Object> weakBody() {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("data", List.of(java.util.Map.of("domain", "sha1.example.com", "severity", "HIGH")));
        b.put("total", 1); b.put("critical", 0L); b.put("high", 1L);
        b.put("tls", java.util.Map.of("total", 2, "rows", List.of()));
        b.put("chain", java.util.Map.of("total", 0, "rows", List.of()));
        return b;
    }

    @Test
    @DisplayName("weak-algorithms: gövde servisten aynen döner (eski data/total/critical/high sözleşmesi korunur)")
    void weakAlgorithms_delegatesToService() throws Exception {
        when(weakAlgoService.build()).thenReturn(weakBody());
        mvc.perform(get("/api/admin/audit/weak-algorithms").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.high").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("sha1.example.com"))
                .andExpect(jsonPath("$.tls.total").value(2));
    }

    @Test
    @DisplayName("weak-algorithms/export: text/csv indirme + WEAK_ALGO_EXPORT denetim kaydı")
    void weakAlgorithms_exportCsv() throws Exception {
        when(weakAlgoService.build()).thenReturn(weakBody());
        when(weakAlgoService.toCsv(any())).thenReturn("source,severity,domain\r\ncertificate,HIGH,sha1.example.com\r\n");
        mvc.perform(get("/api/admin/audit/weak-algorithms/export").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("weak-algorithms-")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("source,severity,domain")));
        org.mockito.Mockito.verify(auditService).recordAction(eq("WEAK_ALGO_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class), eq("WEAK_ALGO"), eq("export"), any());
    }

    @Test
    @DisplayName("weak-algorithms/{domain}/exception: manage yetkisi ister (USER 403); geçmiş tarih 400; geçerli → 200 + denetim")
    void weakAlgorithms_exception() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("yok")).when(permissionService).require(any(HttpSession.class), eq("weak_algo.manage"), eq("edit"));
        mvc.perform(post("/api/admin/audit/weak-algorithms/a.example.com/exception").session(session("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"until\":\"2099-01-01\"}"))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.doNothing().when(permissionService).require(any(HttpSession.class), eq("weak_algo.manage"), eq("edit"));
        when(weakAlgoService.setException(eq("a.example.com"), any(), eq("2020-01-01"), any()))
                .thenThrow(new IllegalArgumentException("until_past"));
        mvc.perform(post("/api/admin/audit/weak-algorithms/a.example.com/exception").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"until\":\"2020-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bitiş tarihi geçmişte olamaz"));

        com.sitemonitor.model.WeakAlgorithmException e = new com.sitemonitor.model.WeakAlgorithmException();
        e.setDomain("a.example.com"); e.setReason("plan"); e.setUntil("2099-01-01");
        when(weakAlgoService.setException(eq("a.example.com"), eq("plan"), eq("2099-01-01"), eq("u"))).thenReturn(e);
        mvc.perform(post("/api/admin/audit/weak-algorithms/a.example.com/exception").session(session("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"plan\",\"until\":\"2099-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.until").value("2099-01-01"));
        org.mockito.Mockito.verify(auditService).recordAction(eq("WEAK_ALGO_EXCEPTION_SET"), any(HttpSession.class), any(HttpServletRequest.class), eq("WEAK_ALGO"), eq("a.example.com"), any());

        when(weakAlgoService.clearException("a.example.com")).thenReturn(true);
        mvc.perform(delete("/api/admin/audit/weak-algorithms/a.example.com/exception").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
        org.mockito.Mockito.verify(auditService).recordAction(eq("WEAK_ALGO_EXCEPTION_CLEAR"), any(HttpSession.class), any(HttpServletRequest.class), eq("WEAK_ALGO"), eq("a.example.com"), any());
    }

    @Test
    @DisplayName("weak-algorithms/{domain}/notify: envanterde yok 404; takımsız 400; takımlı → e-posta + push, denetim kaydı")
    void weakAlgorithms_notify() throws Exception {
        when(inventoryRepo.findByDomain("none.example.com")).thenReturn(java.util.Optional.empty());
        mvc.perform(post("/api/admin/audit/weak-algorithms/none.example.com/notify").session(session("ADMIN")))
                .andExpect(status().isNotFound());

        CertificateInventory orphan = new CertificateInventory(); orphan.setDomain("orphan.example.com");
        when(inventoryRepo.findByDomain("orphan.example.com")).thenReturn(java.util.Optional.of(orphan));
        when(latestCheckRepo.findById("orphan.example.com")).thenReturn(java.util.Optional.empty());
        mvc.perform(post("/api/admin/audit/weak-algorithms/orphan.example.com/notify").session(session("ADMIN")))
                .andExpect(status().isBadRequest());

        CertificateInventory inv = new CertificateInventory(); inv.setDomain("sha1.example.com"); inv.setTeamId(3L);
        Team team = new Team(); team.setId(3L); team.setName("Takım A"); team.setEmail("takim-a@example.com");
        when(inventoryRepo.findByDomain("sha1.example.com")).thenReturn(java.util.Optional.of(inv));
        when(teamRepo.findById(3L)).thenReturn(java.util.Optional.of(team));
        when(latestCheckRepo.findById("sha1.example.com")).thenReturn(java.util.Optional.of(lc("sha1.example.com", "SHA1withRSA", "RSA", 2048)));
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn("SENT");
        when(userPushService.enqueueTeamNotice(eq(3L), eq("WEAK_ALGO"), eq("CRITICAL"), eq("sha1.example.com"), anyString(), anyString()))
                .thenReturn(java.util.Map.of("queued", 2, "skipped", 0));

        mvc.perform(post("/api/admin/audit/weak-algorithms/sha1.example.com/notify").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("SENT"))
                .andExpect(jsonPath("$.data.email_to").value("takim-a@example.com"))
                .andExpect(jsonPath("$.data.push.queued").value(2));
        // E-posta içeriği zayıflığı adıyla taşır (SHA-1) ve seviye classifyWeakness'tan (HIGH) gelir.
        org.mockito.Mockito.verify(emailService).sendAlert(any(String[].class), org.mockito.ArgumentMatchers.contains("sha1.example.com"),
                org.mockito.ArgumentMatchers.contains("SHA1withRSA"), eq("sha1.example.com"), eq("HIGH"), eq("WEAK_ALGORITHM"), any(), any());
        org.mockito.Mockito.verify(auditService).recordAction(eq("WEAK_ALGO_NOTIFY"), any(HttpSession.class), any(HttpServletRequest.class), eq("WEAK_ALGO"), eq("sha1.example.com"), any());
    }

    // ── Cihaz Gecmisi admin gorunumu (K8) ────────────────────────────────────

    @Test
    @DisplayName("Admin cihaz gorunumu DENETIM YETKISI ister — sirali USER 403 alir")
    void userDevices_requiresAuditAccess() throws Exception {
        mvc.perform(get("/api/admin/users/5/devices").session(session("USER")))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(deviceHistoryService, org.mockito.Mockito.never())
                .devicesFor(any(), any());
    }

    @Test
    @DisplayName("AUDIT rolu baskasinin cihaz gecmisini OKUR; 'bu cihaz' isareti VERILMEZ")
    void userDevices_auditRoleCanRead() throws Exception {
        com.sitemonitor.model.AppUser target = new com.sitemonitor.model.AppUser();
        target.setId(5L); target.setUsername("N12345");
        when(appUserRepo.findById(5L)).thenReturn(java.util.Optional.of(target));
        when(deviceHistoryService.devicesFor(any(), any())).thenReturn(java.util.Map.of());

        mvc.perform(get("/api/admin/users/5/devices").session(session("AUDIT")))
                .andExpect(status().isOk());

        // currentTokenHash NULL gecmeli: yoneticinin tarayicisi hedefin cihazi DEGIL, hicbir
        // satir "bu cihaz" diye isaretlenmemeli.
        org.mockito.Mockito.verify(deviceHistoryService)
                .devicesFor(any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Olmayan kullanici 404 — bos panel yerine acik cevap")
    void userDevices_unknownUser_is404() throws Exception {
        when(appUserRepo.findById(999L)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/admin/users/999/devices").session(session("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SOZLESME: admin yolunda EYLEM ucu YOKTUR (yonetici baskasinin cihazini dusuremez)")
    void adminPathExposesNoActions() throws Exception {
        // Salt-okunur olmasi bilincli: iptal/cikis yalniz kullanicinin KENDI self-scope
        // ucundadir. Bu kapi olmadan biri kolayca "admin de iptal edebilsin" diye ekler.
        mvc.perform(delete("/api/admin/users/5/devices/remembered/1").session(session("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/users/5/devices/logout-others").session(session("ADMIN")))
                .andExpect(status().isNotFound());
    }
}
