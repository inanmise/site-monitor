package com.certmonitor.controller;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.AuditLog;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RememberMeService rememberMeService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean
    AuditService auditService;

    @MockitoBean
    com.certmonitor.repository.AuditLogRepository auditLogRepo;

    @MockitoBean
    com.certmonitor.service.ClientIpResolver clientIpResolver;

    @MockitoBean
    com.certmonitor.service.LdapSettingsService ldapSettings;

    @MockitoBean
    com.certmonitor.service.LdapDirectoryService ldapDirectory;

    @MockitoBean
    com.certmonitor.service.LdapProvisioningService ldapProvisioning;

    private AppUser testUser;

    @BeforeEach
    void setup() {
        testUser = new AppUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setSystemRole("USER");
        testUser.setActive(true);
        // teamId null → no team name lookup

        when(userService.authenticate("testuser", "testpass")).thenReturn(Optional.of(testUser));
        when(userService.authenticate("testuser", "wrongpass")).thenReturn(Optional.empty());
        when(userService.authenticate("nobody", "testpass")).thenReturn(Optional.empty());
        when(userService.authenticate("", "")).thenReturn(Optional.empty());
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
        when(userService.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        when(userService.failuresNeededForLevel(anyInt())).thenReturn(5);
        when(auditService.recordLogin(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), anyInt())).thenReturn(new AuditLog());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/login with valid credentials returns 200 and success:true")
    void login_validCredentials_returns200() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.must_change_password").value(false));
    }

    // ── LDAP login ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LDAP enabled + AD success: provisions a USER and returns 200")
    void login_ldapUser_success_returns200() throws Exception {
        com.certmonitor.model.LdapSettings ls = new com.certmonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        when(userService.findByUsername("aduser")).thenReturn(Optional.empty()); // no local row
        when(ldapDirectory.authenticate("aduser", "adpass")).thenReturn(Optional.of(
                new com.certmonitor.service.LdapDirectoryService.LdapUser(
                        "aduser", "CN=aduser,DC=corp",
                        java.util.Map.of("displayName", "AD User", "mail", "ad@corp.com"))));
        AppUser provisioned = new AppUser();
        provisioned.setId(99L);
        provisioned.setUsername("aduser");
        provisioned.setSystemRole("USER");
        provisioned.setActive(true);
        provisioned.setAuthSource("LDAP");
        when(ldapProvisioning.provisionFromAd(eq("aduser"), any(), any())).thenReturn(provisioned);

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aduser\",\"password\":\"adpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("aduser"));
    }

    @Test
    @DisplayName("LDAP enabled + wrong AD password: returns 401")
    void login_ldapUser_badPassword_returns401() throws Exception {
        com.certmonitor.model.LdapSettings ls = new com.certmonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        when(userService.findByUsername("adbad")).thenReturn(Optional.empty());
        when(ldapDirectory.authenticate("adbad", "bad")).thenReturn(Optional.empty());

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adbad\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Local 'admin'-style account uses BCrypt even when LDAP is enabled")
    void login_localAccount_usesBcrypt_whenLdapEnabled() throws Exception {
        com.certmonitor.model.LdapSettings ls = new com.certmonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        // testuser is LOCAL (authSource null) and findByUsername returns it → BCrypt path
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    @DisplayName("POST /api/login with an expired temp password returns 401 + error_code=TEMP_PASSWORD_EXPIRED")
    void login_expiredTempPassword_returns401WithErrorCode() throws Exception {
        AppUser expired = new AppUser();
        expired.setId(3L);
        expired.setUsername("expireduser");
        expired.setSystemRole("USER");
        expired.setActive(true);
        expired.setMustChangePassword(true);
        when(userService.authenticate("expireduser", "tmpPass99")).thenReturn(Optional.of(expired));
        when(userService.findByUsername("expireduser")).thenReturn(Optional.of(expired));
        when(userService.isTempPasswordExpired(expired)).thenReturn(true);

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"expireduser\",\"password\":\"tmpPass99\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("TEMP_PASSWORD_EXPIRED"));
    }

    @Test
    @DisplayName("POST /api/login with a user flagged for forced change exposes must_change_password=true")
    void login_userWithForcedChange_exposesFlag() throws Exception {
        AppUser forced = new AppUser();
        forced.setId(2L);
        forced.setUsername("forceduser");
        forced.setSystemRole("USER");
        forced.setActive(true);
        forced.setMustChangePassword(true);
        when(userService.authenticate("forceduser", "tmpPass99")).thenReturn(Optional.of(forced));
        when(userService.findByUsername("forceduser")).thenReturn(Optional.of(forced));

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"forceduser\",\"password\":\"tmpPass99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.must_change_password").value(true));
    }

    @Test
    @DisplayName("POST /api/login with wrong password returns 401")
    void login_wrongPassword_returns401() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/login with wrong username returns 401")
    void login_wrongUsername_returns401() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"testpass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/login with empty body returns 401")
    void login_emptyBody_returns401() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/login sets authenticated attribute on new session (session fixation prevention)")
    void login_validCredentials_setsSession() throws Exception {
        org.springframework.test.web.servlet.MvcResult result =
                mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn();

        jakarta.servlet.http.HttpSession newSession = result.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(newSession).isNotNull();
        org.assertj.core.api.Assertions.assertThat(newSession.getAttribute("authenticated"))
                .isEqualTo(Boolean.TRUE);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/logout returns 200 and success:true")
    void logout_returnsOk() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);

        mvc.perform(post("/api/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/logout without session still returns 200")
    void logout_withoutSession_returnsOk() throws Exception {
        mvc.perform(post("/api/logout"))
                .andExpect(status().isOk());
    }

    // ── /me ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/me with authenticated session returns username")
    void me_authenticated_returnsUsername() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    @DisplayName("GET /api/me without session returns 401")
    void me_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── Self-service password change ──────────────────────────────────────────

    @Test
    @DisplayName("POST /api/me/change-password without session returns 401")
    void changeOwnPassword_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"oldpass\",\"new_password\":\"newpass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/me/change-password missing current_password returns 400")
    void changeOwnPassword_missingCurrent_returns400() throws Exception {
        mvc.perform(post("/api/me/change-password")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"new_password\":\"newpass1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/me/change-password as USER role with correct current returns 200")
    void changeOwnPassword_asUser_correctCurrent_returns200() throws Exception {
        // userService.changePassword(4-arg) is a mock — no exception means success
        mvc.perform(post("/api/me/change-password")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"oldpass\",\"new_password\":\"newpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Password changed"));
    }

    @Test
    @DisplayName("POST /api/me/change-password with wrong current_password returns 403")
    void changeOwnPassword_wrongCurrent_returns403() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("Invalid admin password"))
                .when(userService).changePassword(eq(1L), any(), eq("testuser"), any());

        mvc.perform(post("/api/me/change-password")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"wrong\",\"new_password\":\"newpass1\"}"))
                .andExpect(status().isForbidden());
    }

    /** Builds a session that mimics a logged-in USER (not admin). */
    private MockHttpSession selfSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", "USER");
        return s;
    }
}
