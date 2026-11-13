package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapDirectoryService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AD'den kullanıcı ekleme uçları (2026-11-13 hata taraması): LDAP kapalıyken AD'ye gidilmez (409 LDAP_DISABLED); AD'ye
 * ulaşılamaması "bulunamadı" değil 503 AD_UNAVAILABLE; kullanıcı adı olmayan / bilgisayar hesabı sonuçlarda listelenmez
 * ve çekilemez (400 AD_NOT_USER_ACCOUNT). Gerçek LDAP'a gidilmez — dizin servisi sahte.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdUserControllerTest {

    @Mock LdapDirectoryService directory;
    @Mock LdapSettingsService ldapSettings;
    @Mock UserService userService;
    @Mock UsernamePrefixPolicy prefixPolicy;
    @Mock AuditService auditService;

    private MockMvc mvc;
    private MockHttpSession admin;
    private LdapSettings settings;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AdUserController controller = new AdUserController(directory, ldapSettings, userService, prefixPolicy, auditService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(ObjectProvider.class)))
                .build();
        admin = new MockHttpSession();
        admin.setAttribute("systemRole", "ADMIN");   // viewTeamIds yok → global yönetici
        admin.setAttribute("username", "admin");
        settings = new LdapSettings();
        settings.setEnabled(true);
        settings.setHost("dc.example.test");
        settings.setBaseDn("dc=example,dc=test");
        when(ldapSettings.isConfigured()).thenReturn(true);
        when(ldapSettings.getOrDefaults()).thenReturn(settings);
        when(userService.findByUsername(anyString())).thenReturn(Optional.empty());
        when(prefixPolicy.active()).thenReturn(false);
    }

    private static String body(String json) { return json; }

    @Test
    @DisplayName("LDAP KAPALI: arama / detay / çekme 409 LDAP_DISABLED, AD'ye hiç gidilmez")
    void ldapDisabled_refusedWithoutTouchingAd() throws Exception {
        settings.setEnabled(false);
        mvc.perform(post("/api/admin/ad-users/search").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"query\":\"ay\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_LDAP_DISABLED));
        mvc.perform(post("/api/admin/ad-users/detail").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"MP1\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_LDAP_DISABLED));
        mvc.perform(post("/api/admin/ad-users/provision").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"MP1\",\"system_role\":\"USER\",\"team_ids\":[5]}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_LDAP_DISABLED));
        verify(directory, never()).searchUsers(anyString(), anyInt());
        verify(directory, never()).lookupUser(anyString());
        verify(userService, never()).provisionAdUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("LDAP yapılandırılmamış: 409 LDAP_NOT_CONFIGURED")
    void notConfigured() throws Exception {
        when(ldapSettings.isConfigured()).thenReturn(false);
        mvc.perform(post("/api/admin/ad-users/search").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"query\":\"ay\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_LDAP_NOT_CONFIGURED));
        verify(directory, never()).searchUsers(anyString(), anyInt());
    }

    @Test
    @DisplayName("AD'ye ulaşılamıyor: detay ve çekme 503 AD_UNAVAILABLE (404 'bulunamadı' değil); kök neden istemciye gitmez")
    void adUnavailable_is503NotNotFound() throws Exception {
        when(directory.lookupUser(anyString())).thenThrow(new LdapDirectoryService.DirectoryUnavailableException(
                "dc01.corp.example:3269 connect timed out", null));
        mvc.perform(post("/api/admin/ad-users/detail").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"MP1\"}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_AD_UNAVAILABLE))
                .andExpect(jsonPath("$.error").value(not(containsString("dc01"))));
        mvc.perform(post("/api/admin/ad-users/provision").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"MP1\",\"system_role\":\"USER\",\"team_ids\":[5]}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_AD_UNAVAILABLE));
        verify(userService, never()).provisionAdUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AD araması erişilemez: 503 AD_UNAVAILABLE")
    void searchUnavailable_is503() throws Exception {
        when(directory.searchUsers(anyString(), anyInt())).thenThrow(new LdapDirectoryService.DirectoryUnavailableException(
                "connection closed", null));
        mvc.perform(post("/api/admin/ad-users/search").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"query\":\"ay\"}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_AD_UNAVAILABLE));
    }

    @Test
    @DisplayName("Bulunamadı yine 404 AD_USER_NOT_FOUND")
    void notFoundStays404() throws Exception {
        when(directory.lookupUser(anyString())).thenReturn(Optional.empty());
        mvc.perform(post("/api/admin/ad-users/detail").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"MP1\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AD_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("Arama: kullanıcı adı olmayan (posta kişisi) ve '$' ile biten (bilgisayar) kayıtlar listelenmez")
    void searchDropsContactsAndComputers() throws Exception {
        Map<String, Object> contact = new HashMap<>();
        contact.put("dn", "cn=Kişi,dc=x");
        contact.put("username", null);
        contact.put("displayName", "Posta Kişisi");
        Map<String, Object> computer = new HashMap<>(Map.of("dn", "cn=PC01,dc=x", "username", "PC01$", "displayName", "PC01"));
        Map<String, Object> user = new HashMap<>(Map.of("dn", "cn=60146,dc=x", "username", "MP60146", "displayName", "Ayşe Yılmaz"));
        when(directory.searchUsers(anyString(), anyInt())).thenReturn(List.of(contact, computer, user));
        mvc.perform(post("/api/admin/ad-users/search").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"query\":\"ay\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].username").value("MP60146"));
    }

    @Test
    @DisplayName("Çekme: '$' ile biten ad AD'ye gitmeden, bilgisayar hesabı (sAMAccountType) AD okumasından sonra 400 AD_NOT_USER_ACCOUNT")
    void provisionRefusesNonUserAccounts() throws Exception {
        mvc.perform(post("/api/admin/ad-users/provision").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"PC01$\",\"system_role\":\"USER\",\"team_ids\":[5]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_AD_NOT_USER_ACCOUNT));
        verify(directory, never()).lookupUser(anyString());

        when(directory.lookupUser("SRV01")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "SRV01", "sAMAccountType", "805306369", "objectClass", List.of("top", "computer"))));
        mvc.perform(post("/api/admin/ad-users/provision").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"SRV01\",\"system_role\":\"USER\",\"team_ids\":[5]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AdUserController.CODE_AD_NOT_USER_ACCOUNT));
        verify(userService, never()).provisionAdUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Çekme: normal kullanıcı hesabı eklenir (yönetici seçimleri servise geçer)")
    void provisionNormalUser() throws Exception {
        when(directory.lookupUser("mp60146")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "MP60146", "sAMAccountType", "805306368", "objectClass", List.of("top", "person", "user"))));
        AppUser saved = new AppUser();
        saved.setId(77L);
        saved.setUsername("MP60146");
        saved.setSystemRole("USER");
        saved.setTeamId(5L);
        when(userService.provisionAdUser(any(), any(), any(), any(), any(), any())).thenReturn(saved);
        mvc.perform(post("/api/admin/ad-users/provision").session(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"username\":\"mp60146\",\"system_role\":\"USER\",\"team_ids\":[5]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("MP60146"));
    }
}
