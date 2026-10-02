package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    @MockitoBean com.sitemonitor.service.TourStateService tourStateService;   // ürün turu (2026-09-13)

    @MockitoBean
    com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean
    AuditService auditService;

    @MockitoBean
    com.sitemonitor.repository.AuditLogRepository auditLogRepo;

    @MockitoBean
    com.sitemonitor.service.ClientIpResolver clientIpResolver;

    @MockitoBean
    com.sitemonitor.service.LdapSettingsService ldapSettings;

    @MockitoBean
    com.sitemonitor.service.LdapDirectoryService ldapDirectory;

    @MockitoBean
    com.sitemonitor.service.LdapProvisioningService ldapProvisioning;

    @MockitoBean
    com.sitemonitor.service.DeviceHistoryService deviceHistoryService;

    @MockitoBean
    com.sitemonitor.repository.RememberMeTokenRepository rememberMeTokenRepo;

    @MockitoBean
    com.sitemonitor.service.LoginIssueService loginIssueService;

    @MockitoBean
    com.sitemonitor.service.LoginIssueMailService loginIssueMailService;

    @MockitoBean
    com.sitemonitor.service.AppSettingsService appSettings;

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

    @Test
    @DisplayName("Aktif oturum CANONICAL username ile kaydedilir (AD case farkı aktif sayımı bozmasın)")
    void login_recordsActiveSessionWithCanonicalUsername() throws Exception {
        // Kullanıcı farklı case ile girer ("TestUser"); DB canonical "testuser". AD girişinde tipik.
        when(userService.findByUsername("TestUser")).thenReturn(Optional.of(testUser));
        when(userService.authenticate("TestUser", "testpass")).thenReturn(Optional.of(testUser));

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"TestUser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Giriş damgası YAZILAN case ("TestUser") ile DEĞİL, canonical user.getUsername() ("testuser")
        // ile yazılmalı — aksi halde findByUsername (case-sensitive) satırı bulamaz, kullanıcı aktif sayılmaz.
        org.mockito.Mockito.verify(userService).recordSuccessfulLogin(
                org.mockito.ArgumentMatchers.eq("testuser"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(UserService.LoginMethod.PASSWORD));
        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never()).recordSuccessfulLogin(
                org.mockito.ArgumentMatchers.eq("TestUser"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── Giriş güvenliği özeti (login_info) ────────────────────────────────────

    @Test
    @DisplayName("Giriş yanıtı login_info taşır: gösterilen değer BİR ÖNCEKİ giriş, bu oturum değil")
    void login_returnsLoginInfo() throws Exception {
        when(userService.recordSuccessfulLogin(any(), any(), any(), any()))
                .thenReturn(new UserService.LoginStamp(
                        "2026-08-10T09:00:00", "10.0.0.9", "PASSWORD", 3,
                        "2026-08-11T10:00:00", "10.0.0.8", "BAD_PASSWORD",
                        "2026-08-12T11:00:00", "PASSWORD", false));

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login_info.prev_login_at").value("2026-08-10T09:00:00"))
                .andExpect(jsonPath("$.login_info.prev_login_ip").value("10.0.0.9"))
                .andExpect(jsonPath("$.login_info.failed_before_login").value(3))
                .andExpect(jsonPath("$.login_info.last_failed_at").value("2026-08-11T10:00:00"))
                .andExpect(jsonPath("$.login_info.first_login").value(false));
    }

    @Test
    @DisplayName("İlk girişte login_info null alanlarla döner (Map.of null kabul etmez — regresyon kalkanı)")
    void login_firstLoginSerializesNulls() throws Exception {
        when(userService.recordSuccessfulLogin(any(), any(), any(), any()))
                .thenReturn(UserService.LoginStamp.empty());

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login_info.first_login").value(true))
                .andExpect(jsonPath("$.login_info.failed_before_login").value(0));
    }

    @Test
    @DisplayName("Başarısız giriş: kullanıcı VARSA damga yazılır, YOKSA hiç yazılmaz (enumeration yüzeyi açılmaz)")
    void failedLogin_stampsOnlyExistingUser() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(userService)
                .recordFailedLogin(org.mockito.ArgumentMatchers.eq("testuser"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("BAD_PASSWORD"));

        org.mockito.Mockito.clearInvocations(userService);
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"testpass\"}"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .recordFailedLogin(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── Kilit sayacı KANONİK adla (prod kapısı 2026-09-25, O-1) ──────────────────────────────
    // Kimlik doğrulama harf duyarsız ama deneme sayacı (a.actor = :actor) duyarlıydı: "admin"/"ADMIN"/
    // "Admin" ayrı sayılıyor, her varyant kendi 4 deneme hakkını alıyor, kilit hiç devreye girmiyordu.

    @Test
    @DisplayName("O-1: mevcut kullanıcı farklı harfle denenirse başarısız giriş KAYITLI adla yazılır")
    void failedLogin_existingUser_recordedUnderCanonicalName() throws Exception {
        when(userService.findByUsername("TESTUSER")).thenReturn(Optional.of(testUser));   // kayıtlı ad "testuser"

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"TESTUSER\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized());

        verify(auditService).recordLogin(eq("testuser"), any(), any(), any(), any(), any(), any(),
                eq(false), eq("BAD_PASSWORD"), any(), anyInt());
        verify(auditService, never()).recordLogin(eq("TESTUSER"), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("O-1: bilinmeyen kullanıcı adı BÜYÜK harfe kanonikleştirilir (varyantlar tek sayaçta birikir)")
    void failedLogin_unknownUser_recordedUpperCase() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"testpass\"}"))
                .andExpect(status().isUnauthorized());

        verify(auditService).recordLogin(eq("NOBODY"), any(), any(), any(), any(), any(), any(),
                eq(false), eq("UNKNOWN_USER"), any(), anyInt());
    }

    @Test
    @DisplayName("O-1: BRUTE_FORCE eşiğinde kilit ve ACCOUNT_LOCKED olayı KANONİK ada uygulanır")
    void bruteForce_lockAppliedToCanonicalName() throws Exception {
        when(userService.findByUsername("TestUser")).thenReturn(Optional.of(testUser));
        AuditLog brute = new AuditLog();
        brute.setAnomalyFlags("BRUTE_FORCE");
        when(auditService.recordLogin(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), anyInt())).thenReturn(brute);
        when(userService.applyProgressiveLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 30));

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"TestUser\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isLocked());

        verify(userService).applyProgressiveLockout("testuser");
        verify(auditService).recordAction(eq("ACCOUNT_LOCKED"), eq("testuser"), any(), any(), any(), eq("USER"),
                eq("testuser"), any(), any(), any(), any());
    }

    // ── Tek aktif oturum onayı ────────────────────────────────────────────────

    @Test
    @DisplayName("Başka yerde CANLI aktif oturum varsa (force yok) → 409 ACTIVE_SESSION_EXISTS, oturum düşmez")
    void login_activeSessionElsewhere_returns409() throws Exception {
        when(userService.hasLiveSession(any())).thenReturn(true);

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("ACTIVE_SESSION_EXISTS"));

        // Bu dalda oturum KURULMUYOR → giriş damgası da basılmamalı. Aksi halde kullanıcı hiç
        // giremediği hâlde "önceki giriş" değeri bu denemeyle ezilir ve gerçek son giriş kaybolur.
        // (clearLockoutOnSuccess bu kontrolden ÖNCE çağrılıyor; damga oraya bağlanamaz.)
        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .recordSuccessfulLogin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("force_login=true → canlı oturum olsa bile düşürülür, giriş 200 ile tamamlanır")
    void login_activeSessionElsewhere_forceLogin_returns200() throws Exception {
        when(userService.hasLiveSession(any())).thenReturn(true);

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\",\"force_login\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    @DisplayName("TERMINATED sentinel'i aktif oturum sayılmaz → onay gerekmez, 200")
    void login_terminatedSentinel_noConfirmation_returns200() throws Exception {
        testUser.setActiveSessionId("TERMINATED:abc-123");

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── LDAP login ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LDAP enabled + AD success: provisions a USER and returns 200")
    void login_ldapUser_success_returns200() throws Exception {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        when(userService.findByUsername("aduser")).thenReturn(Optional.empty()); // no local row
        when(ldapDirectory.authenticate("aduser", "adpass")).thenReturn(Optional.of(
                new com.sitemonitor.service.LdapDirectoryService.LdapUser(
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
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
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
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
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

    /**
     * Hareketsizlik süresi AYARDAN gelir ve /me ile taşınır.
     *
     * <p>Eskiden yalnız derleme zamanı ayarlanabiliyordu (VITE_INACTIVITY_MS, 5 dk): süreyi
     * değiştirmek yeniden derleyip dağıtmayı gerektiriyordu.
     */
    @Test
    @DisplayName("/me hareketsizlik ayarını taşır (varsayılan 60 dk)")
    void me_carriesIdleConfig() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(60);
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-warn-seconds"), anyInt())).thenReturn(60);

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactivity_minutes").value(60))
                .andExpect(jsonPath("$.inactivity_warn_seconds").value(60));
    }

    /**
     * SINIRLAR. 0/negatif bir değer herkesi ANINDA dışarı atardı ve ayarı yanlış giren kişi
     * kendi düzeltemezdi — giriş yapar yapmaz atılırdı. Üst sınır sunucu oturum ömrüyle (24 sa)
     * hizalı: ötesini vaat etmek yalan olurdu, sunucu oturumu zaten düşürür.
     */
    @Test
    @DisplayName("/me: 0 ve aşırı değerler SINIRLANIR (kendini dışarı kilitleme yok)")
    void me_idleConfigIsClamped() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");

        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(0);
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-warn-seconds"), anyInt())).thenReturn(60);
        mvc.perform(get("/api/me").session(session))
                .andExpect(jsonPath("$.inactivity_minutes").value(1));

        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(99999);
        mvc.perform(get("/api/me").session(session))
                .andExpect(jsonPath("$.inactivity_minutes").value(24 * 60));
    }

    @Test
    @DisplayName("/me: uyarı süresi TOPLAMI aşamaz — aşarsa uyarı hiç görünmezdi")
    void me_warnCannotExceedTotal() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(2);
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-warn-seconds"), anyInt())).thenReturn(9999);

        mvc.perform(get("/api/me").session(session))
                .andExpect(jsonPath("$.inactivity_warn_seconds").value(2 * 60 - 1));
    }

    // ── Bayat oturum takım kapsamı (2026-09-25, kullanıcı isteği: USER izleme/alan adı ekleyebilmeli) ──
    // Takım öznitelikleri yalnız girişte yazılıyordu: sonradan takıma eklenen kullanıcı requireInventoryWriter /
    // canOperateTeam'de 403 alıyor, /me taze team_ids yanında bayat team_id veriyordu.

    private MockHttpSession staleUserSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", null);
        s.setAttribute("memberTeamIds", new java.util.ArrayList<Long>());
        s.setAttribute("viewTeamIds", new java.util.ArrayList<Long>());
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        return s;
    }

    private void userNowInTeam5() {
        testUser.setTeamId(5L);
        testUser.getTeamIds().add(5L);
        when(userService.computeMemberTeamIds(testUser)).thenReturn(List.of(5L));
        when(userService.computeViewTeamIds(testUser)).thenReturn(List.of(5L));
        when(userService.computeManageTeamIds(testUser)).thenReturn(List.of());
        com.sitemonitor.model.Team t = new com.sitemonitor.model.Team();
        t.setId(5L); t.setName("Takım A");
        when(userService.findTeamById(5L)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("/me: girişten SONRA takıma eklenen kullanıcının oturumu DB'den tazelenir — team_id 5, üyelik [5]")
    void me_refreshesStaleTeamScope() throws Exception {
        userNowInTeam5();
        MockHttpSession session = staleUserSession();

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team_id").value(5))
                .andExpect(jsonPath("$.team_name").value("Takım A"));

        assertThat(session.getAttribute("teamId")).isEqualTo(5L);
        assertThat(session.getAttribute("memberTeamIds")).isEqualTo(List.of(5L));
        assertThat(session.getAttribute("viewTeamIds")).isEqualTo(List.of(5L));
        assertThat(session.getAttribute("teamName")).isEqualTo("Takım A");
    }

    @Test
    @DisplayName("/me: oturum rolü DB rolünden FARKLIYSA takım kapsamına dokunulmaz (rol semantiği değişmez)")
    void me_roleChanged_doesNotRefreshScope() throws Exception {
        userNowInTeam5();
        testUser.setSystemRole("ADMIN");   // DB'de rol yükseltilmiş; oturum hâlâ USER
        MockHttpSession session = staleUserSession();

        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());

        assertThat(session.getAttribute("teamId")).isNull();
        assertThat(session.getAttribute("memberTeamIds")).isEqualTo(List.of());
        assertThat(session.getAttribute("viewTeamIds")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("GET /api/me without session returns 401")
    void me_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── Oturum ping (hızlı süpersede yakalama) ─────────────────────────────────

    @Test
    @DisplayName("GET /api/session/ping with session returns 200")
    void sessionPing_authenticated_returns200() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");

        mvc.perform(get("/api/session/ping").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/session/ping without session returns 401 (interceptor)")
    void sessionPing_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/session/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/session/leave (2026-10-02): beacon gövdesi okunmaz, 204; kullanıcı bu oturumla 'ayrıldı' işaretlenir")
    void sessionLeave_marksLeft_returns204() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticated", Boolean.TRUE);
        session.setAttribute("username", "testuser");

        mvc.perform(post("/api/session/leave").session(session)
                        .contentType(MediaType.TEXT_PLAIN).content("leave"))
                .andExpect(status().isNoContent());
        verify(userService).markLeft("testuser", session.getId());
    }

    @Test
    @DisplayName("POST /api/session/leave oturumsuz → 401 (interceptor), işaretleme yok")
    void sessionLeave_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/session/leave"))
                .andExpect(status().isUnauthorized());
        verify(userService, org.mockito.Mockito.never()).markLeft(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    // ── Self-service push opt-out (2026-09-10: istemci yolu yanlıştı, uç burada pinlenir) ──

    @Test
    @DisplayName("POST /api/me/push-opt-out: oturumlu kullanıcı kendi bayrağını yazar, yanıt push_opt_out döner")
    void pushOptOut_setsFlag_returnsIt() throws Exception {
        mvc.perform(post("/api/me/push-opt-out")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opt_out\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.push_opt_out").value(true));
        verify(userService).savePushOptOut(argThat(u -> Boolean.TRUE.equals(u.getPushOptOut())));
    }

    @Test
    @DisplayName("POST /api/me/push-opt-out oturumsuz → 401; eski yanlış yol /api/auth/me/push-opt-out → 404")
    void pushOptOut_unauthenticated_andWrongPath() throws Exception {
        mvc.perform(post("/api/me/push-opt-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opt_out\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/me/push-opt-out")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opt_out\":true}"))
                .andExpect(status().isNotFound());
    }

    // ── Kişisel push sessiz saati (2026-10-01, onaylı öneri 15) ──

    @Test
    @DisplayName("POST /api/me/push-quiet-hours: kullanıcı KENDİ penceresini yazar (normalize), yanıt push_quiet döner; denetim izi")
    void pushQuietHours_savesOwnWindow() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(userService.savePushQuietHours(any(), any())).thenAnswer(inv -> {
            com.sitemonitor.model.AppUser u = inv.getArgument(0);
            com.sitemonitor.service.QuietHours.Config c = inv.getArgument(1);
            u.setPushQuietStart(c.start()); u.setPushQuietEnd(c.end());
            u.setPushQuietDays(c.days()); u.setPushQuietMinLevel(c.minLevel());
            return u;
        });
        mvc.perform(post("/api/me/push-quiet-hours")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"22:00\",\"end\":\"07:00\",\"days\":[\"fri\",\"MON\"],\"min_level\":\"CRITICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.push_quiet.start").value("22:00"))
                .andExpect(jsonPath("$.push_quiet.days").value("MON,FRI"))
                .andExpect(jsonPath("$.push_quiet.min_level").value("CRITICAL"));
        verify(userService).savePushQuietHours(any(),
                eq(new com.sitemonitor.service.QuietHours.Config("22:00", "07:00", "MON,FRI", "CRITICAL")));
        verify(auditService).recordAction(eq("USER_PUSH_QUIET_HOURS"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER"), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("POST /api/me/push-quiet-hours: hatalı pencere 400 ve kayıt YOK; boş başlangıç+bitiş = kaldır")
    void pushQuietHours_validatesAndClears() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        mvc.perform(post("/api/me/push-quiet-hours")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"22:00\",\"end\":\"22:00\"}"))
                .andExpect(status().isBadRequest());
        verify(userService, never()).savePushQuietHours(any(), any());

        when(userService.savePushQuietHours(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/me/push-quiet-hours")
                        .session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":\"\",\"end\":\"\"}"))
                .andExpect(status().isOk());
        verify(userService).savePushQuietHours(any(), eq(com.sitemonitor.service.QuietHours.Config.NONE));
    }

    // ── Ürün turu durumu (2026-09-13) ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/me: tour alanı — durum yoksa null, varsa çözülmüş JSON")
    void me_returnsTourState() throws Exception {
        mvc.perform(get("/api/me").session(selfSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tour").doesNotExist());
        testUser.setTourState("{\"status\":\"dismissed\",\"version\":1}");
        mvc.perform(get("/api/me").session(selfSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tour.status").value("dismissed"))
                .andExpect(jsonPath("$.tour.version").value(1));
    }

    @Test
    @DisplayName("POST /api/me/tour: yama servise gider, yanıt güncel tour; dismissed geçişi TOUR_DISMISSED denetimi yazar")
    void setTour_appliesPatch_andAuditsDismiss() throws Exception {
        when(tourStateService.apply(any(AppUser.class), any(), eq(false)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("status", "dismissed", "version", 1, "last_step", "cards")));
        mvc.perform(post("/api/me/tour").session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"dismissed\",\"version\":1,\"last_step\":\"cards\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.tour.status").value("dismissed"));
        verify(tourStateService).apply(argThat(u -> "testuser".equals(u.getUsername())), argThat(m -> "dismissed".equals(m.get("status"))), eq(false));
        verify(auditService).recordAction(eq("TOUR_DISMISSED"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER"), eq("1"), anyString(), org.mockito.ArgumentMatchers.contains("\"last_step\":\"cards\""));
    }

    @Test
    @DisplayName("POST /api/me/tour: aynı durumda kalınca denetim YAZILMAZ; reset → tour null; oturumsuz 401")
    void setTour_noAuditWhenUnchanged_reset_unauth() throws Exception {
        testUser.setTourState("{\"status\":\"completed\"}");
        when(tourStateService.apply(any(AppUser.class), any(), eq(false)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("status", "completed", "last_step", "help")));
        mvc.perform(post("/api/me/tour").session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"last_step\":\"help\"}"))
                .andExpect(status().isOk());
        verify(auditService, never()).recordAction(eq("TOUR_COMPLETED"), any(jakarta.servlet.http.HttpSession.class), anyString(), anyString(), anyString(), any());
        when(tourStateService.apply(any(AppUser.class), any(), eq(true))).thenReturn(null);
        mvc.perform(post("/api/me/tour").session(selfSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reset\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tour").doesNotExist());
        mvc.perform(post("/api/me/tour").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"completed\"}"))
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

    @Test
    @DisplayName("photoResponse: foto yok/geçersiz → 204 (404 DEĞİL) → HTTP metriklerinde hata sayılmaz")
    void photoResponse_noPhoto_returns204() {
        assertThat(AuthController.photoResponse(null).getStatusCode().value()).isEqualTo(204);
        assertThat(AuthController.photoResponse("   ").getStatusCode().value()).isEqualTo(204);
        assertThat(AuthController.photoResponse("@@@ not base64 @@@").getStatusCode().value()).isEqualTo(204);
    }

    @Test
    @DisplayName("photoResponse: geçerli base64 → 200 image/jpeg")
    void photoResponse_valid_returns200() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        var resp = AuthController.photoResponse(b64);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getFirst("Content-Type")).isEqualTo("image/jpeg");
    }

    // ── Cihaz Gecmisi uclari (self-scope) ────────────────────────────────────

    @Test
    @DisplayName("GET /me/devices: kimlik OTURUMDAN okunur — kullanici secen parametre YOKTUR")
    void devices_isSelfScoped() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(deviceHistoryService.devicesFor(any(), any()))
                .thenReturn(java.util.Map.of("current", java.util.Map.of("known", false),
                                             "remembered", java.util.List.of()));

        mvc.perform(get("/api/me/devices").session(selfSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // ASIL KANIT: servise OTURUMDAKI kullanici gecti; baska kullaniciya gecis yolu yok.
        org.mockito.ArgumentCaptor<AppUser> cap = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(deviceHistoryService).devicesFor(cap.capture(), any());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getUsername()).isEqualTo("testuser");
    }

    @Test
    @DisplayName("GET /me/devices: OTURUM ACILMAMISSA veri sizmaz")
    void devices_requiresAuthentication() throws Exception {
        mvc.perform(get("/api/me/devices"))
                .andExpect(status().is4xxClientError());

        verify(deviceHistoryService, never()).devicesFor(any(), any());
    }

    @Test
    @DisplayName("GET /me/devices: cookie token'i YALNIZ hash'lenir; ham deger servise gitmez")
    void devices_passesOnlyTokenHash() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(rememberMeService.hashOf("HAM-TOKEN")).thenReturn("HASH-DEGERI");
        when(deviceHistoryService.devicesFor(any(), any())).thenReturn(java.util.Map.of());

        mvc.perform(get("/api/me/devices")
                        .session(selfSession())
                        .cookie(new jakarta.servlet.http.Cookie(RememberMeService.COOKIE_NAME, "HAM-TOKEN")))
                .andExpect(status().isOk());

        verify(deviceHistoryService).devicesFor(any(), org.mockito.ArgumentMatchers.eq("HASH-DEGERI"));
        verify(deviceHistoryService, never()).devicesFor(any(), org.mockito.ArgumentMatchers.eq("HAM-TOKEN"));
    }

    @Test
    @DisplayName("GET /me/devices/logins: sayfa boyutu ve failed bayragi servise gecer")
    void deviceLogins_passesParams() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(deviceHistoryService.loginsFor(any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(java.util.Map.of("rows", java.util.List.of()));

        mvc.perform(get("/api/me/devices/logins?page=2&size=25&failed=true")
                        .session(selfSession()))
                .andExpect(status().isOk());

        verify(deviceHistoryService).loginsFor(any(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(25));
    }

    // ── Cihaz eylemleri (Faz 2) ──────────────────────────────────────────────

    @Test
    @DisplayName("IDOR: BASKASININ hatirlanan cihazi 404 doner (403 DEGIL — varlik sizmasin)")
    void revokeRemembered_foreignRow_is404() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        // Sahiplik kontrolu SORGUNUN ICINDE: baskasinin satirinda 0 satir silinir.
        when(rememberMeTokenRepo.deleteByIdAndUsername(99L, "testuser")).thenReturn(0);

        mvc.perform(delete("/api/me/devices/remembered/99").session(selfSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        // Denetim kaydi da YAZILMAZ — olmayan/yabanci satir icin olay uretmeyiz.
        verify(auditService, never()).recordAction(org.mockito.ArgumentMatchers.eq("REMEMBER_TOKEN_REVOKE"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Kendi cihazini iptal: satir silinir ve DENETIME yazilir")
    void revokeRemembered_ownRow_deletesAndAudits() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(rememberMeTokenRepo.deleteByIdAndUsername(5L, "testuser")).thenReturn(1);

        mvc.perform(delete("/api/me/devices/remembered/5").session(selfSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).recordAction(org.mockito.ArgumentMatchers.eq("REMEMBER_TOKEN_REVOKE"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), org.mockito.ArgumentMatchers.eq("5"), anyString());
    }

    @Test
    @DisplayName("Diger cihazlardan cikis: TUM token'lar iptal + denetim")
    void logoutOthers_revokesAll() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        mvc.perform(post("/api/me/devices/logout-others").session(selfSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(rememberMeService).invalidateAllForUser("testuser");
        verify(auditService).recordAction(org.mockito.ArgumentMatchers.eq("SESSION_REVOKE_ALL"),
                any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IDOR: BASKASININ giris kaydi bildirilemez (404) ve bildirim URETILMEZ")
    void reportLogin_foreignAuditRow_is404() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(auditLogRepo.findOwnById(org.mockito.ArgumentMatchers.eq(42L), anyString()))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/me/devices/report-login").session(selfSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"auditId\":42}"))
                .andExpect(status().isNotFound());

        verify(loginIssueService, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PAROLA DEGISIMI tum hatirlanan girisleri IPTAL eder (calinan parola sonrasi cihaz disari atilir)")
    void changePassword_revokesRememberMeTokens() throws Exception {
        // Bu eksikti: parolasini degistiren kullanicinin eski cihazi remember-me cookie'siyle
        // 7 gun daha otomatik giris yapabiliyordu — yani parola degistirmek saldirganı DISARI
        // ATMIYORDU.
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        mvc.perform(post("/api/me/change-password").session(selfSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"eski\",\"new_password\":\"YeniParola123!\"}"))
                .andExpect(status().isOk());

        verify(rememberMeService).invalidateAllForUser("testuser");
    }

    // ── "Bu girisi ben yapmadim" bildirimi GERCEKTEN ulasir mi ───────────────

    private com.sitemonitor.model.AuditLog ownLoginRow() {
        com.sitemonitor.model.AuditLog a = new com.sitemonitor.model.AuditLog();
        a.setId(42L);
        a.setEventTime("2026-08-24T09:15:00");
        a.setIpAddress("88.1.2.3");
        a.setUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537.36");
        return a;
    }

    private com.sitemonitor.model.LoginIssueReport savedReport() {
        com.sitemonitor.model.LoginIssueReport r = new com.sitemonitor.model.LoginIssueReport();
        r.setId(7L);
        r.setReportedAt("2026-08-24T10:00:00");
        return r;
    }

    @Test
    @DisplayName("Bildirim YONETICIYE mail olarak gider ve bildirene ALINDI teyidi doner")
    void reportLogin_notifiesAdminAndUser() {
        // Onceki hali yalniz DB satiri aciyordu: kayit vardi ama KIMSE haberdar olmuyordu —
        // hesabinin ele gecirildigini dusunup bildiren biri icin akisin butun degeri kaybolur.
        testUser.setEmail("kadir@example.com");
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(auditLogRepo.findOwnById(org.mockito.ArgumentMatchers.eq(42L), anyString()))
                .thenReturn(Optional.of(ownLoginRow()));
        when(loginIssueService.save(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(savedReport());
        when(appSettings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.system-admin.email"), anyString()))
                .thenReturn("admin@example.com");
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenReturn(false);

        try {
            mvc.perform(post("/api/me/devices/report-login").session(selfSession())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"auditId\":42}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ref").value("LIR-2026-000007"));
        } catch (Exception e) { throw new RuntimeException(e); }

        // NOT: auditService mock oldugu icin resolveIp() NULL doner → o konumda anyString()
        // kullanilamaz (null'i kabul etmez). Ayni sekilde errorText/appVersion bilincli null.
        verify(loginIssueMailService).dispatchUserReport(any(), org.mockito.ArgumentMatchers.eq("LIR-2026-000007"),
                org.mockito.ArgumentMatchers.eq("admin@example.com"), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), any(), any(),
                any(), any(), anyString());
        verify(loginIssueMailService).dispatchAck(any(), org.mockito.ArgumentMatchers.eq("LIR-2026-000007"),
                org.mockito.ArgumentMatchers.eq("kadir@example.com"), anyString(), any(), anyString(),
                any(), anyString());
    }

    @Test
    @DisplayName("GUNLUK OZET acikken tekil admin maili ATLANIR; ACK yine gider")
    void reportLogin_digestSuppressesAdminMail() throws Exception {
        // Karde akışlarla aynı kural: ozet cron'u toplayacagi icin tekil mail gonderilmez.
        testUser.setEmail("kadir@example.com");
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(auditLogRepo.findOwnById(org.mockito.ArgumentMatchers.eq(42L), anyString()))
                .thenReturn(Optional.of(ownLoginRow()));
        when(loginIssueService.save(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(savedReport());
        when(appSettings.getString(anyString(), anyString())).thenReturn("admin@example.com");
        when(appSettings.getBoolean(org.mockito.ArgumentMatchers.eq("site.monitor.issue-reports.daily-digest"),
                anyBoolean())).thenReturn(true);

        mvc.perform(post("/api/me/devices/report-login").session(selfSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"auditId\":42}"))
                .andExpect(status().isOk());

        verify(loginIssueMailService, never()).dispatchUserReport(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(loginIssueMailService).dispatchAck(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("YABANCI kayitta hicbir mail gitmez (IDOR kapisi bildirimden ONCE)")
    void reportLogin_foreignRowSendsNothing() throws Exception {
        when(userService.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(auditLogRepo.findOwnById(org.mockito.ArgumentMatchers.eq(42L), anyString()))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/me/devices/report-login").session(selfSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"auditId\":42}"))
                .andExpect(status().isNotFound());

        verify(loginIssueMailService, never()).dispatchUserReport(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(loginIssueMailService, never()).dispatchAck(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── Pasif hesap (2026-10-02, kullanıcı kararı: pasif kullanıcı hiçbir yoldan giriş yapamaz) ──────────

    private static AppUser passiveUser(String username, String authSource) {
        AppUser u = new AppUser();
        u.setId(55L);
        u.setUsername(username);
        u.setSystemRole("USER");
        u.setActive(false);
        u.setAuthSource(authSource);
        return u;
    }

    private static com.sitemonitor.model.LdapSettings ldapOn() {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        return ls;
    }

    private void assertNoSessionNoLogin(org.springframework.test.web.servlet.MvcResult result) {
        assertThat(result.getRequest().getSession(false)).as("oturum kurulmamalı").isNull();
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
        verify(userService, never()).clearLockoutOnSuccess(any());
        verify(rememberMeService, never()).generateToken(any(), any(), any());
    }

    @Test
    @DisplayName("Pasif YEREL hesap + DOĞRU parola → 403 ACCOUNT_INACTIVE (TR metin); oturum yok; denetim ACCOUNT_INACTIVE, kilit sayacına GİRMEZ")
    void login_passiveLocal_correctPassword_403() throws Exception {
        AppUser p = passiveUser("pasif", null);
        when(userService.findByUsername("pasif")).thenReturn(Optional.of(p));
        when(userService.authenticate("pasif", "dogru")).thenReturn(Optional.empty());
        when(userService.findInactiveWithValidPassword("pasif", "dogru")).thenReturn(Optional.of(p));

        var result = mvc.perform(post("/api/login").header("X-Lang", "tr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pasif\",\"password\":\"dogru\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
                .andExpect(jsonPath("$.error_code").value("ACCOUNT_INACTIVE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("pasif")))
                .andReturn();

        assertNoSessionNoLogin(result);
        verify(auditService).recordInactiveLogin(eq("pasif"), eq(55L), any(), eq("USER"), any(), any(), eq("PASSWORD"));
        verify(userService).recordFailedLogin("pasif", "127.0.0.1", "ACCOUNT_INACTIVE");
        // İlerleyici kilit yanlış parolaya karşıdır: pasif hesabın (doğru parolalı) denemesi BRUTE_FORCE sayımına girmez.
        verify(auditService, never()).recordLogin(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyInt());
        verify(userService, never()).applyProgressiveLockout(any());
    }

    @Test
    @DisplayName("Pasif hesap mesajı arayüz dilinde (X-Lang: en)")
    void login_passiveLocal_englishMessage() throws Exception {
        AppUser p = passiveUser("pasif", null);
        when(userService.findByUsername("pasif")).thenReturn(Optional.of(p));
        when(userService.findInactiveWithValidPassword("pasif", "dogru")).thenReturn(Optional.of(p));

        mvc.perform(post("/api/login").header("X-Lang", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pasif\",\"password\":\"dogru\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Your account is inactive; sign-in is not allowed. Contact your administrator."));
    }

    @Test
    @DisplayName("Pasif YEREL hesap + YANLIŞ parola → bugünkü genel 401 (pasif bilgisi sızmaz, numaralandırma yok)")
    void login_passiveLocal_wrongPassword_generic401() throws Exception {
        AppUser p = passiveUser("pasif", null);
        when(userService.findByUsername("pasif")).thenReturn(Optional.of(p));
        when(userService.authenticate("pasif", "yanlis")).thenReturn(Optional.empty());
        // findInactiveWithValidPassword → boş (parola tutmadı)

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pasif\",\"password\":\"yanlis\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.error_code").doesNotExist());

        verify(auditService, never()).recordInactiveLogin(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Pasif LDAP hesabı + BAŞARILI AD bind → 403 ACCOUNT_INACTIVE; provizyon HİÇ çalışmaz (yeniden aktifleşmez / profil-takım değişmez), oturum yok")
    void login_passiveLdap_successfulBind_403_noProvisioning() throws Exception {
        when(ldapSettings.getOrDefaults()).thenReturn(ldapOn());
        AppUser p = passiveUser("ADPASIF", "LDAP");
        when(userService.findByUsername("adpasif")).thenReturn(Optional.of(p));
        when(ldapDirectory.authenticate("adpasif", "adpass")).thenReturn(Optional.of(
                new com.sitemonitor.service.LdapDirectoryService.LdapUser(
                        "adpasif", "CN=adpasif,DC=corp", java.util.Map.of("mail", "pasif@corp.com"))));

        var result = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adpasif\",\"password\":\"adpass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
                .andReturn();

        assertNoSessionNoLogin(result);
        verify(ldapProvisioning, never()).provisionFromAd(any(), any(), any());
        verify(userService, never()).updateUser(any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditService).recordInactiveLogin(eq("ADPASIF"), eq(55L), any(), any(), any(), any(), eq("LDAP"));
        assertThat(p.getActive()).isFalse();
    }

    @Test
    @DisplayName("Pasif LDAP hesabı + YANLIŞ AD parolası → genel 401 (pasif bilgisi sızmaz)")
    void login_passiveLdap_wrongPassword_generic401() throws Exception {
        when(ldapSettings.getOrDefaults()).thenReturn(ldapOn());
        AppUser p = passiveUser("ADPASIF", "LDAP");
        when(userService.findByUsername("adpasif")).thenReturn(Optional.of(p));
        when(ldapDirectory.authenticate("adpasif", "bad")).thenReturn(Optional.empty());

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adpasif\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
        verify(auditService, never()).recordInactiveLogin(any(), any(), any(), any(), any(), any(), any());
        verify(ldapProvisioning, never()).provisionFromAd(any(), any(), any());
    }

    @Test
    @DisplayName("AKTİF LDAP hesabı (satır var, aktif) değişmez: provizyon çalışır, 200")
    void login_activeLdap_existingRow_unchanged() throws Exception {
        when(ldapSettings.getOrDefaults()).thenReturn(ldapOn());
        AppUser row = passiveUser("ADAKTIF", "LDAP");
        row.setActive(true);
        when(userService.findByUsername("adaktif")).thenReturn(Optional.of(row));
        when(ldapDirectory.authenticate("adaktif", "adpass")).thenReturn(Optional.of(
                new com.sitemonitor.service.LdapDirectoryService.LdapUser("adaktif", "CN=adaktif,DC=corp", java.util.Map.of())));
        when(ldapProvisioning.provisionFromAd(eq("adaktif"), any(), any())).thenReturn(row);

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adaktif\",\"password\":\"adpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(ldapProvisioning).provisionFromAd(eq("adaktif"), any(), any());
        verify(auditService, never()).recordInactiveLogin(any(), any(), any(), any(), any(), any(), any());
    }
}
