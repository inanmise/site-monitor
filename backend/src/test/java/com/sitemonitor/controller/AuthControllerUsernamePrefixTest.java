package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapDirectoryService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
import com.sitemonitor.service.loginpolicy.UsernamePrefixRule;
import com.sitemonitor.service.otp.LoginMethodsService;
import com.sitemonitor.service.otp.LoginOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kullanıcı adı ön eki kuralı — giriş ucu, LDAP, kodla giriş doğrulaması ve oturum kapısı (2026-10-21, kullanıcı isteği:
 * "yalnız N ile başlayan kullanıcıya login izni; MP'li ya da başka harfli olana izin vermeyelim").
 *
 * <p>Kural YAZILAN ada bakar ve kimlik bilgisi denetiminden ÖNCE reddeder: BCrypt yok, AD bind yok, veritabanı araması
 * yok, provizyon yok; denetim LOGIN_FAILED + BLOCKED (kilit sayacına girmez). Kurala uyan ad bugünkü gibi girer;
 * bootstrap yönetici her zaman muaf. LDAP'ta AD'nin döndürdüğü KANONİK ad da denetlenir.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = "site.monitor.username=admin")
class AuthControllerUsernamePrefixTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean com.sitemonitor.service.TourStateService tourStateService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.repository.AuditLogRepository auditLogRepo;
    @MockitoBean com.sitemonitor.service.ClientIpResolver clientIpResolver;
    @MockitoBean com.sitemonitor.service.LdapSettingsService ldapSettings;
    @MockitoBean LdapDirectoryService ldapDirectory;
    @MockitoBean com.sitemonitor.service.LdapProvisioningService ldapProvisioning;
    @MockitoBean com.sitemonitor.service.DeviceHistoryService deviceHistoryService;
    @MockitoBean com.sitemonitor.repository.RememberMeTokenRepository rememberMeTokenRepo;
    @MockitoBean com.sitemonitor.service.LoginIssueService loginIssueService;
    @MockitoBean com.sitemonitor.service.LoginIssueMailService loginIssueMailService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean LoginOtpService loginOtp;
    @MockitoBean LoginMethodsService loginMethods;
    @MockitoBean UsernamePrefixPolicy usernamePrefix;

    /** Taklit politikanın kararı GERÇEK kuraldan gelir (yalnız N izinli; bootstrap "admin" muaf). */
    final AtomicReference<UsernamePrefixRule> rule = new AtomicReference<>();

    AppUser n123;
    AppUser mp123;

    @BeforeEach
    void setUp() {
        rule.set(UsernamePrefixRule.of(true, "N", "", "admin"));
        when(usernamePrefix.allows(any())).thenAnswer(inv -> rule.get().allows(inv.getArgument(0)));
        when(usernamePrefix.deniedBody()).thenAnswer(inv -> UsernamePrefixRule.deniedBody(rule.get().prefixes()));

        n123 = user(1L, "N123", "LOCAL");
        mp123 = user(2L, "MP123", "LOCAL");
        when(clientIpResolver.resolve(any())).thenReturn("10.1.1.1");
        when(userService.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        when(userService.failuresNeededForLevel(anyInt())).thenReturn(5);
        when(auditService.recordLogin(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyInt(), any()))
                .thenReturn(new AuditLog());
        when(loginMethods.ldapLoginEnabled()).thenReturn(true);
        when(userService.findByUsername("n123")).thenReturn(Optional.of(n123));
        when(userService.findByUsername("N123")).thenReturn(Optional.of(n123));
        when(userService.authenticate("n123", "pw")).thenReturn(Optional.of(n123));
        when(userService.authenticate("N123", "pw")).thenReturn(Optional.of(n123));
        when(userService.findByUsername("MP123")).thenReturn(Optional.of(mp123));
        when(userService.authenticate("MP123", "pw")).thenReturn(Optional.of(mp123));
    }

    private static AppUser user(Long id, String name, String source) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(name);
        u.setSystemRole("USER");
        u.setActive(true);
        u.setAuthSource(source);
        return u;
    }

    private static String creds(String u, String p) {
        return "{\"username\":\"" + u.replace("\\", "\\\\") + "\",\"password\":\"" + p + "\"}";
    }

    @Test
    @DisplayName("MP… adı: kimlik bilgisi denetiminden ÖNCE 403 USERNAME_PREFIX_NOT_ALLOWED — BCrypt, AD bind, DB araması yok; denetim BLOCKED")
    void mpRefusedBeforeCredentialCheck() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("MP123", "pw")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USERNAME_PREFIX_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error_code").value("USERNAME_PREFIX_NOT_ALLOWED"))
                .andExpect(jsonPath("$.allowed_prefixes[0]").value("N"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        verify(userService, never()).authenticate(any(), any());
        verify(userService, never()).findByUsername(any());
        verify(userService, never()).burnPasswordCheck(any());
        verify(userService, never()).findInactiveWithValidPassword(any(), any());
        verify(ldapDirectory, never()).authenticate(any(), any());
        verify(ldapProvisioning, never()).provisionFromAd(any(), any(), any());
        verify(auditService).recordUsernamePrefixLogin(eq("MP123"), isNull(), isNull(), isNull(), eq("10.1.1.1"), any(), isNull());
        // kilit merdivenine / başarısız deneme özetine / oturuma GİRMEZ
        verify(auditService, never()).recordLogin(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyInt(), any());
        verify(userService, never()).recordFailedLogin(any(), any(), any());
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ret yanıtı hesabın varlığını söylemez: OLMAYAN MP adı ile VAR OLAN MP adı aynı gövdeyi alır")
    void sameBodyForUnknownAndExisting() throws Exception {
        String a = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("MP123", "pw")))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        String b = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("MP999", "x")))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("N… adı (küçük harf yazılsa da) bugünkü gibi girer")
    void nUserLogsIn() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("n123", "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(auditService, never()).recordUsernamePrefixLogin(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bootstrap yönetici her zaman muaf (kural N iken 'admin' girer)")
    void bootstrapAdminExempt() throws Exception {
        AppUser admin = user(9L, "ADMIN", "LOCAL");
        admin.setSystemRole("ADMIN");
        when(userService.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userService.authenticate("admin", "pw")).thenReturn(Optional.of(admin));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("admin", "pw")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DOMAIN\\ad biçimi temkinli: CORP\\n123 → ret (yazılan ad C ile başlar)")
    void domainFormRefused() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("CORP\\n123", "pw")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USERNAME_PREFIX_NOT_ALLOWED"));
        verify(userService, never()).authenticate(any(), any());
    }

    @Test
    @DisplayName("LDAP: yazılan ad uyuyor ama AD'nin KANONİK adı MP… → 403, provizyon HİÇ çalışmaz, denetim yolu LDAP")
    void ldapCanonicalNameChecked() throws Exception {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        when(userService.findByUsername("n.ali@corp.local")).thenReturn(Optional.empty());
        when(userService.findByUsername("MP555")).thenReturn(Optional.empty());
        when(ldapDirectory.authenticate("n.ali@corp.local", "adpass")).thenReturn(Optional.of(
                new LdapDirectoryService.LdapUser("MP555", "CN=MP555,DC=corp", Map.of())));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("n.ali@corp.local", "adpass")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USERNAME_PREFIX_NOT_ALLOWED"));
        verify(ldapProvisioning, never()).provisionFromAd(any(), any(), any());
        verify(auditService).recordUsernamePrefixLogin(eq("MP555"), isNull(), any(), any(), any(), any(), eq("LDAP"));
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("LDAP: N… adı AD'de doğrulanınca provizyon + giriş bugünkü gibi")
    void ldapAllowedNameProvisioned() throws Exception {
        com.sitemonitor.model.LdapSettings ls = new com.sitemonitor.model.LdapSettings();
        ls.setEnabled(true);
        when(ldapSettings.getOrDefaults()).thenReturn(ls);
        AppUser ad = user(5L, "N777", "LDAP");
        when(userService.findByUsername("n777")).thenReturn(Optional.empty());
        when(userService.findByUsername("N777")).thenReturn(Optional.empty());
        when(ldapDirectory.authenticate("n777", "adpass")).thenReturn(Optional.of(
                new LdapDirectoryService.LdapUser("N777", "CN=N777,DC=corp", Map.of())));
        when(ldapProvisioning.provisionFromAd(eq("N777"), any(), any())).thenReturn(ad);
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("n777", "adpass")))
                .andExpect(status().isOk());
        verify(ldapProvisioning).provisionFromAd(eq("N777"), any(), any());
    }

    @Test
    @DisplayName("kural KAPALI → MP… adı bugünkü gibi girer (davranış birebir)")
    void ruleOffUnchanged() throws Exception {
        rule.set(UsernamePrefixRule.disabled("admin"));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(creds("MP123", "pw")))
                .andExpect(status().isOk());
        verify(auditService, never()).recordUsernamePrefixLogin(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("kodla giriş: kural AÇILMADAN önce istenmiş gerçek kod doğru girilse de MP hesabı → 403, istek kapanır, oturum yok")
    void otpVerifyRealChallengeRefused() throws Exception {
        LoginOtpChallenge c = new LoginOtpChallenge();
        c.setId("11111111-2222-3333-4444-555555555555");
        c.setUsername("MP123");
        c.setUserId(2L);
        when(loginOtp.verify(eq(c.getId()), eq("123456"), any(), any())).thenReturn(new LoginOtpService.VerifyOutcome(null,
                new LoginOtpService.Verified(c, LoginOtpService.Channel.PUSH, mp123)));
        mvc.perform(post("/api/login/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge_id\":\"" + c.getId() + "\",\"code\":\"123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USERNAME_PREFIX_NOT_ALLOWED"));
        verify(loginOtp).block(c);
        verify(loginOtp, never()).consume(any());
        verify(auditService).recordUsernamePrefixLogin(eq("MP123"), eq(2L), any(), eq("USER"), any(), any(), eq("OTP_PUSH"));
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("açık oturum: kural açılınca MP oturumunun sonraki isteği 401 USERNAME_PREFIX_NOT_ALLOWED (interceptor kapısı)")
    void liveSessionCut() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "MP123");
        s.setAttribute("systemRole", "USER");
        mvc.perform(get("/api/session/ping").session(s))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USERNAME_PREFIX_NOT_ALLOWED"))
                .andExpect(jsonPath("$.allowed_prefixes[0]").value("N"));
        assertThat(s.getAttribute("usernamePrefixDenied")).isEqualTo(true);
        verify(rememberMeService).invalidateAllForUser("MP123");
        verify(userService, never()).isSessionSuperseded(any(), any());
    }
}
