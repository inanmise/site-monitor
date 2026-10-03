package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.otp.LoginMethodsService;
import com.sitemonitor.service.otp.LoginOtpService;
import com.sitemonitor.util.SystemMaintenanceSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kodla giriş uçları + LDAP girişi anahtarı (2026-10-02, kullanıcı isteği). Doğrulama başarılıysa oturum ŞİFRE
 * GİRİŞİYLE AYNI yoldan kurulur: /api/login ile aynı gövde, giriş damgası (yöntem OTP_PUSH), LOGIN denetimi (ayrıntıda
 * yöntem), 409 + forceLogin (istek tüketilmez), bakım 403, pasif 403 ve hesap kilidi 423 YALNIZ doğru koddan sonra.
 * LDAP girişi kapalıyken yerel olmayan her ad (var / yok) ve yerel hesabın yanlış parolası AYNI 401 gövdesini alır;
 * yerel hesaplar ve kurulumdaki bootstrap admin etkilenmez.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = "site.monitor.username=admin")
class AuthControllerOtpTest {

    @Autowired MockMvc mvc;
    @Autowired AuthController controller;

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
    @MockitoBean LoginOtpService loginOtp;
    @MockitoBean LoginMethodsService loginMethods;

    AppUser alice;
    LoginOtpChallenge challenge;

    static final String CID = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void setUp() {
        alice = new AppUser();
        alice.setId(7L);
        alice.setUsername("ALICE");
        alice.setSystemRole("USER");
        alice.setActive(true);
        alice.setAuthSource("LDAP");
        challenge = new LoginOtpChallenge();
        challenge.setId(CID);
        challenge.setUsername("ALICE");
        challenge.setUserId(7L);
        challenge.setChannel("PUSH");
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.5");
        when(userService.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        when(userService.failuresNeededForLevel(anyInt())).thenReturn(5);
        when(auditService.recordLogin(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyInt(), any()))
                .thenReturn(new AuditLog());
        when(loginMethods.ldapLoginEnabled()).thenReturn(true);
        when(loginOtp.consume(any())).thenReturn(true);
    }

    private void verified() {
        when(loginOtp.verify(eq(CID), eq("123456"), any(), any())).thenReturn(new LoginOtpService.VerifyOutcome(null,
                new LoginOtpService.Verified(challenge, LoginOtpService.Channel.PUSH, alice)));
    }

    private static String verifyBody(boolean force) {
        return "{\"challenge_id\":\"" + CID + "\",\"code\":\"123456\",\"remember_me\":false,\"forceLogin\":" + force + "}";
    }

    // ── İstek ucu ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("istek ucu servisin sonucunu AYNEN döner (durum + gövde); IP ve dil servise iletilir")
    void request_passThrough() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("challenge_id", CID);
        body.put("channel", "push");
        body.put("expires_in", 45);
        body.put("resend_in", 30);
        when(loginOtp.request(eq("alice"), eq("push"), any(), any(), eq("10.0.0.5"), any(), eq(true)))
                .thenReturn(new LoginOtpService.Result(200, body));
        mvc.perform(post("/api/login/otp/request").header("X-Lang", "en").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"channel\":\"push\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge_id").value(CID))
                .andExpect(jsonPath("$.expires_in").value(45))
                .andExpect(jsonPath("$.resend_in").value(30));
    }

    @Test
    @DisplayName("2026-10-03: gövdedeki phone / email servise AYNEN iletilir (kişi bilgisi yoksa null); 400 PHONE_REQUIRED alan adıyla döner")
    void request_forwardsContact() throws Exception {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("challenge_id", CID);
        when(loginOtp.request(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LoginOtpService.Result(200, ok));
        mvc.perform(post("/api/login/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"channel\":\"push\",\"phone\":\"0500 000 00 00\"}"))
                .andExpect(status().isOk());
        verify(loginOtp).request(eq("alice"), eq("push"), eq("0500 000 00 00"), org.mockito.ArgumentMatchers.isNull(), eq("10.0.0.5"), any(), anyBoolean());
        mvc.perform(post("/api/login/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"channel\":\"email\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isOk());
        verify(loginOtp).request(eq("alice"), eq("email"), org.mockito.ArgumentMatchers.isNull(), eq("alice@example.com"), eq("10.0.0.5"), any(), anyBoolean());

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("success", false);
        bad.put("code", "PHONE_REQUIRED");
        bad.put("error_code", "PHONE_REQUIRED");
        bad.put("error", "Enter your registered mobile number.");
        bad.put("field", "phone");
        when(loginOtp.request(eq("bob"), eq("push"), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LoginOtpService.Result(400, bad));
        mvc.perform(post("/api/login/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"channel\":\"push\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("PHONE_REQUIRED"))
                .andExpect(jsonPath("$.field").value("phone"));
    }

    // ── Doğrulama ucu ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("doğru kod → /api/login ile AYNI gövde + oturum; damga OTP_PUSH; LOGIN denetimi ayrıntıda yöntem; istek tüketilir")
    void verify_success_sameSessionPath() throws Exception {
        verified();
        MvcResult r = mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.username").value("ALICE"))
                .andExpect(jsonPath("$.must_change_password").value(false))
                .andReturn();
        assertThat(r.getRequest().getSession(false)).isNotNull();
        assertThat(r.getRequest().getSession(false).getAttribute("username")).isEqualTo("ALICE");
        verify(loginOtp).consume(challenge);
        verify(userService).recordSuccessfulLogin(eq("ALICE"), any(), eq("10.0.0.5"), eq(UserService.LoginMethod.OTP_PUSH));
        verify(auditService).recordLogin(eq("ALICE"), eq(7L), any(), eq("USER"), eq("10.0.0.5"), any(), any(),
                eq(true), any(), any(), eq(5), eq("OTP_PUSH"));
        verify(rememberMeService).invalidateAllForUser("ALICE");
    }

    @Test
    @DisplayName("mustChangePassword: kodla girişte de oturuma yazılır (interceptor kuralı aynen)")
    void verify_mustChangePassword_carried() throws Exception {
        alice.setMustChangePassword(true);
        verified();
        MvcResult r = mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.must_change_password").value(true))
                .andReturn();
        assertThat(r.getRequest().getSession(false).getAttribute("mustChangePassword")).isEqualTo(true);
    }

    @Test
    @DisplayName("remember_me=true → remember-me çerezi (SameSite=Strict) şifre girişiyle aynı")
    void verify_rememberMe_cookie() throws Exception {
        verified();
        when(rememberMeService.generateToken(eq("ALICE"), any(), any())).thenReturn("tok");
        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge_id\":\"" + CID + "\",\"code\":\"123456\",\"remember_me\":true}"))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Set-Cookie")).contains(RememberMeService.COOKIE_NAME + "=tok")
                        .contains("SameSite=Strict"));
    }

    @Test
    @DisplayName("başka yerde canlı oturum → 409 ACTIVE_SESSION_EXISTS; istek TÜKETİLMEZ (onay süresince tutulur); forceLogin ile 200")
    void verify_activeSession_409_thenForce() throws Exception {
        verified();
        when(userService.hasLiveSession(alice)).thenReturn(true);
        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("ACTIVE_SESSION_EXISTS"));
        verify(loginOtp).holdForConfirmation(challenge);
        verify(loginOtp, never()).consume(any());
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());

        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(loginOtp).consume(challenge);
    }

    @Test
    @DisplayName("pasif hesap: YALNIZ doğru koddan sonra 403 ACCOUNT_INACTIVE; istek kapatılır, oturum yok")
    void verify_inactive_403_afterCorrectCode() throws Exception {
        alice.setActive(false);
        verified();
        MvcResult r = mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("ACCOUNT_INACTIVE"))
                .andReturn();
        assertThat(r.getRequest().getSession(false)).isNull();
        verify(loginOtp).block(challenge);
        verify(auditService).recordInactiveLogin(eq("ALICE"), eq(7L), any(), eq("USER"), any(), any(), eq("OTP_PUSH"));
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("bakım: global yönetici olmayan kullanıcı doğru koddan sonra 403 MAINTENANCE; istek kapatılır")
    void verify_maintenance_403() throws Exception {
        SystemMaintenanceService maintenance = org.mockito.Mockito.mock(SystemMaintenanceService.class);
        when(maintenance.isActive()).thenReturn(true);
        when(maintenance.isGlobalAdminAccount(alice)).thenReturn(false);
        when(maintenance.signalBody()).thenReturn(SystemMaintenanceSignal.body("Sistem bakımda",
                Map.of("state", "active", "end_at", "2026-10-02T20:00:00Z")));
        controller.setSystemMaintenance(maintenance);
        try {
            verified();
            mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error_code").value("MAINTENANCE"));
            verify(loginOtp).block(challenge);
            verify(auditService).recordMaintenanceLogin(eq("ALICE"), eq(7L), any(), eq("USER"), any(), any(), eq("OTP_PUSH"));
        } finally {
            controller.setSystemMaintenance(null);
        }
    }

    @Test
    @DisplayName("hesap kilitli (doğru koddan sonra) → 423; denetim LOGIN_FAILED BLOCKED (kilit sayacına girmez); istek kapatılır")
    void verify_locked_423() throws Exception {
        verified();
        when(userService.checkLockout("ALICE")).thenReturn(new UserService.LockoutStatus(false, 90));
        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.wait_seconds").value(90));
        verify(loginOtp).block(challenge);
        verify(auditService).recordLockedLogin(eq("ALICE"), eq(7L), any(), eq("USER"), any(), any(), eq("OTP_PUSH"));
    }

    @Test
    @DisplayName("hata yanıtları servisten AYNEN: 401 OTP_INVALID + attempts_left; oturum kurulmaz")
    void verify_failure_passThrough() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "OTP_INVALID");
        body.put("error_code", "OTP_INVALID");
        body.put("error", "Kod hatalı.");
        body.put("attempts_left", 2);
        when(loginOtp.verify(any(), any(), any(), any()))
                .thenReturn(new LoginOtpService.VerifyOutcome(new LoginOtpService.Result(401, body), null));
        MvcResult r = mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                .andExpect(jsonPath("$.attempts_left").value(2))
                .andReturn();
        assertThat(r.getRequest().getSession(false)).isNull();
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("tüketim yarışı kaybedildi (eşzamanlı ikinci istek) → 401 OTP_EXPIRED, oturum yok")
    void verify_consumeLost() throws Exception {
        verified();
        when(loginOtp.consume(any())).thenReturn(false);
        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    // ── LDAP ile giriş anahtarı ───────────────────────────────────────────────

    private void ldapIntegrationOn() {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
    }

    private String loginBody(String u, String p) {
        return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}";
    }

    @Test
    @DisplayName("LDAP girişi KAPALI: LDAP kullanıcısı, olmayan ad ve yerel hesabın yanlış parolası AYNI 401 gövdesi; AD'ye gidilmez")
    void ldapDisabled_sameResponseForAll() throws Exception {
        ldapIntegrationOn();
        when(loginMethods.ldapLoginEnabled()).thenReturn(false);
        when(userService.findByUsername("alice")).thenReturn(Optional.of(alice));   // LDAP hesabı
        AppUser local = new AppUser();
        local.setId(3L);
        local.setUsername("LOCALOPS");
        local.setSystemRole("USER");
        local.setActive(true);
        local.setAuthSource("LOCAL");
        when(userService.findByUsername("localops")).thenReturn(Optional.of(local));
        when(userService.authenticate("localops", "wrong")).thenReturn(Optional.empty());

        String a = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("alice", "x")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error_code").value("LDAP_LOGIN_DISABLED"))
                .andReturn().getResponse().getContentAsString();
        String b = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("nobody", "x")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String c = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("localops", "wrong")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(b).isEqualTo(a);
        assertThat(c).isEqualTo(a);
        verify(ldapDirectory, never()).authenticate(any(), any());
        verify(auditService).recordLdapDisabledLogin(eq("ALICE"), any(), any());
        verify(auditService).recordLdapDisabledLogin(eq("NOBODY"), any(), any());
        // parola denenmedi → kilit sayacı / kullanıcının başarısız deneme özeti etkilenmez
        verify(userService, never()).applyProgressiveLockout("ALICE");
        verify(userService, never()).recordFailedLogin(eq("ALICE"), any(), any());
        // zamanlama dengesi: yerel olmayan dal da BCrypt maliyetini öder
        verify(userService, org.mockito.Mockito.times(2)).burnPasswordCheck("x");
    }

    @Test
    @DisplayName("LDAP girişi KAPALI: yerel hesap doğru parolayla girer; kurulumdaki bootstrap admin her zaman girer")
    void ldapDisabled_localAndBootstrapUnaffected() throws Exception {
        ldapIntegrationOn();
        when(loginMethods.ldapLoginEnabled()).thenReturn(false);
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setUsername("ADMIN");
        admin.setSystemRole("ADMIN");
        admin.setActive(true);
        admin.setAuthSource("LOCAL");
        when(userService.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userService.authenticate("admin", "adminpass")).thenReturn(Optional.of(admin));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", "adminpass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(ldapDirectory, never()).authenticate(any(), any());

        // Bootstrap adı yerel satır olarak görünmese bile LDAP-kapalı ön reddine düşmez: AD'ye gidilmez, YEREL doğrulama denenir
        when(userService.findByUsername("admin")).thenReturn(Optional.empty());
        when(userService.authenticate("admin", "adminpass")).thenReturn(Optional.of(admin));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", "adminpass")))
                .andExpect(status().isOk());
        verify(ldapDirectory, never()).authenticate(any(), any());
        verify(auditService, never()).recordLdapDisabledLogin(any(), any(), any());
    }

    @Test
    @DisplayName("LDAP girişi AÇIK (varsayılan): davranış bugünkü gibi — AD bind denenir, yanlış parola düz 401")
    void ldapEnabled_unchanged() throws Exception {
        ldapIntegrationOn();
        when(userService.findByUsername("adbad")).thenReturn(Optional.empty());
        when(ldapDirectory.authenticate("adbad", "bad")).thenReturn(Optional.empty());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("adbad", "bad")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"))
                .andExpect(jsonPath("$.error_code").doesNotExist());
        verify(ldapDirectory).authenticate("adbad", "bad");
        verify(auditService, never()).recordLdapDisabledLogin(any(), any(), any());
    }
}
