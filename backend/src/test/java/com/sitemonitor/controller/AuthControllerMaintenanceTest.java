package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.util.SystemMaintenanceSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sistem Bakım Modu — giriş ucu ve oturum yoklaması (2026-10-02, kullanıcı kararı). Bakım aktifken yerel ve LDAP girişi
 * global yönetici olmayanlara 403 {@code MAINTENANCE} döner (kimlik bilgisi DOĞRULANDIKTAN sonra; yanlış parola genel 401);
 * global yönetici girer. Yoklama ve {@code /me} EK {@code maintenance} + {@code server_now} taşır.
 */
@WebMvcTest(AuthController.class)
class AuthControllerMaintenanceTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean com.sitemonitor.service.TourStateService tourStateService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.repository.AuditLogRepository auditLogRepo;
    @MockitoBean com.sitemonitor.service.ClientIpResolver clientIpResolver;
    @MockitoBean com.sitemonitor.service.LdapSettingsService ldapSettings;
    @MockitoBean com.sitemonitor.service.LdapDirectoryService ldapDirectory;
    @MockitoBean com.sitemonitor.service.LdapProvisioningService ldapProvisioning;
    @MockitoBean com.sitemonitor.service.DeviceHistoryService deviceHistoryService;
    @MockitoBean com.sitemonitor.repository.RememberMeTokenRepository rememberMeTokenRepo;
    @MockitoBean com.sitemonitor.service.LoginIssueService loginIssueService;
    @MockitoBean com.sitemonitor.service.LoginIssueMailService loginIssueMailService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean SystemMaintenanceService maintenance;

    AppUser user;
    AppUser admin;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setSystemRole("USER");
        user.setActive(true);
        admin = new AppUser();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setSystemRole("ADMIN");
        admin.setActive(true);
        when(userService.authenticate("testuser", "testpass")).thenReturn(Optional.of(user));
        when(userService.authenticate("testuser", "wrong")).thenReturn(Optional.empty());
        when(userService.authenticate("admin", "adminpass")).thenReturn(Optional.of(admin));
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(userService.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
        when(userService.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        when(userService.failuresNeededForLevel(anyInt())).thenReturn(5);
        when(auditService.recordLogin(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyInt(), any()))
                .thenReturn(new AuditLog());
        when(maintenance.isActive()).thenReturn(true);
        when(maintenance.isGlobalAdminAccount(user)).thenReturn(false);
        when(maintenance.isGlobalAdminAccount(admin)).thenReturn(true);
        when(maintenance.signalBody()).thenReturn(SystemMaintenanceSignal.body("Sistem bakımda",
                Map.of("state", "active", "start_at", "2026-10-02T19:00:00Z", "end_at", "2026-10-02T20:00:00Z")));
        when(maintenance.clientBlock()).thenReturn(Map.of("state", "active", "end_at", "2026-10-02T20:00:00Z"));
        when(maintenance.serverNow()).thenReturn("2026-10-02T19:10:00Z");
    }

    private static String creds(String u, String p) {
        return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}";
    }

    @Test
    @DisplayName("bakımda yerel kullanıcı (doğru parola) → 403 MAINTENANCE + pencere; oturum KURULMAZ; denetim BLOCKED + sayaç")
    void local_user_blocked() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("testuser", "testpass")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MAINTENANCE"))
                .andExpect(jsonPath("$.error_code").value("MAINTENANCE"))
                .andExpect(jsonPath("$.maintenance.end_at").value("2026-10-02T20:00:00Z"));
        verify(auditService).recordMaintenanceLogin(eq("testuser"), eq(1L), any(), eq("USER"), any(), any(), eq("PASSWORD"));
        verify(maintenance).recordBlockedLogin();
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
        // kilit sayacına ve kullanıcının başarısız-deneme özetine girmez
        verify(userService, never()).recordFailedLogin(any(), any(), any());
    }

    @Test
    @DisplayName("bakımda yanlış parola → genel 401 (MAINTENANCE değil — kim global yönetici numaralandırılamaz)")
    void wrongPassword_generic401() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("testuser", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
        verify(auditService, never()).recordMaintenanceLogin(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bakımda GLOBAL yönetici girer (200) ve yanıt bakım bloğunu taşır")
    void globalAdmin_allowed() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("admin", "adminpass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.maintenance.state").value("active"))
                .andExpect(jsonPath("$.server_now").value("2026-10-02T19:10:00Z"));
        verify(auditService, never()).recordMaintenanceLogin(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bakımda LDAP kullanıcısı (AD bind başarılı) → 403 MAINTENANCE; provizyon HİÇ çalışmaz")
    void ldap_blocked_noProvisioning() throws Exception {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        when(userService.findByUsername("aduser")).thenReturn(Optional.empty());
        when(ldapDirectory.authenticate("aduser", "adpass")).thenReturn(Optional.of(
                new com.sitemonitor.service.LdapDirectoryService.LdapUser("aduser", "CN=aduser,DC=corp", Map.of())));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("aduser", "adpass")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MAINTENANCE"));
        verify(ldapProvisioning, never()).provisionFromAd(any(), any(), any());
        verify(auditService).recordMaintenanceLogin(eq("aduser"), any(), any(), any(), any(), any(), eq("LDAP"));
    }

    @Test
    @DisplayName("pasif hesap kontrolü bakımdan ÖNCE: pasif + doğru parola → ACCOUNT_INACTIVE")
    void passive_beforeMaintenance() throws Exception {
        AppUser passive = new AppUser();
        passive.setId(3L);
        passive.setUsername("pasif");
        passive.setActive(false);
        when(userService.findByUsername("pasif")).thenReturn(Optional.of(passive));
        when(userService.authenticate("pasif", "p")).thenReturn(Optional.empty());
        when(userService.findInactiveWithValidPassword("pasif", "p")).thenReturn(Optional.of(passive));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("pasif", "p")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    @DisplayName("bakım YOKKEN giriş birebir: kullanıcı girer, MAINTENANCE yok")
    void noMaintenance_userLogsIn() throws Exception {
        when(maintenance.isActive()).thenReturn(false);
        when(maintenance.clientBlock()).thenReturn(Map.of("state", "none"));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("testuser", "testpass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.maintenance.state").value("none"));
    }

    @Test
    @DisplayName("oturum yoklaması: mevcut alan aynı + EK maintenance bloğu ve server_now (global yönetici oturumu)")
    void ping_carriesBlock() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        mvc.perform(get("/api/session/ping").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.maintenance.state").value("active"))
                .andExpect(jsonPath("$.server_now").value("2026-10-02T19:10:00Z"));
    }

    @Test
    @DisplayName("/api/me EK bakım bloğu taşır")
    void me_carriesBlock() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        mvc.perform(get("/api/me").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.maintenance.state").value("active"));
    }

    @Test
    @DisplayName("bakımda kullanıcı oturumunun yoklaması interceptor'da kesilir → 401 MAINTENANCE")
    void ping_userSession_cut() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "testuser");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));
        mvc.perform(get("/api/session/ping").session(s))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MAINTENANCE"));
    }
}
