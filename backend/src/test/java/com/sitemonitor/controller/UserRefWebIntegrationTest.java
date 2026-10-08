package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.userref.UserPublicIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opak kullanıcı kimliği — GERÇEK Spring MVC yapılandırmasıyla (Boot'un otomatik Jackson dönüştürücüsü + kayıtlı
 * {@code @ControllerAdvice}). Birim testi dönüştürücüyü elle kurar; bu test, üretimdeki dönüştürücünün kapıdan
 * gerçekten geçtiğini kanıtlar (kapı yanlış dönüştürücü türüne bağlansaydı sessizce no-op kalırdı).
 */
@WebMvcTest(UserDirectoryController.class)
class UserRefWebIntegrationTest {

    private static final String P5 = "00000000-0000-4000-8000-000000000005";

    @Autowired MockMvc mvc;

    @MockitoBean AppUserRepository userRepo;
    @MockitoBean UserPublicIds userPublicIds;
    @MockitoBean AppSettingsService appSettings;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean AuditService auditService;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        AppUser u = new AppUser();
        u.setId(5L);
        u.setUsername("ali");
        u.setDisplayName("Ali");
        when(userRepo.findAll()).thenReturn(List.of(u));
        when(userRepo.findById(5L)).thenReturn(Optional.of(u));
        when(userPublicIds.publicIdOf(5L)).thenReturn(P5);
        when(userPublicIds.resolve(eq(P5), anyBoolean())).thenReturn(5L);
        when(userPublicIds.resolve(eq("5"), eq(true))).thenReturn(5L);
        when(userPublicIds.resolve(eq("5"), eq(false))).thenReturn(null);
    }

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "N12345");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    @Test
    @DisplayName("dizin: global admin sayısal id görür; kullanıcı ve AUDIT opak kimlik görür")
    void directory() throws Exception {
        mvc.perform(get("/api/users/directory").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5));
        mvc.perform(get("/api/users/directory").session(session("USER", List.of(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(P5))
                .andExpect(jsonPath("$.data[0].username").value("ali"));
        mvc.perform(get("/api/users/directory").session(session("AUDIT", null)))
                .andExpect(jsonPath("$.data[0].id").value(P5));
    }

    @Test
    @DisplayName("fotoğraf: kullanıcı sıralı sayıyla tarama yapamaz (404, depo sorgulanmaz); opak kimlik çözülür")
    void photoRejectsNumericFromNonGlobal() throws Exception {
        mvc.perform(get("/api/users/5/photo").session(session("USER", List.of(1L))))
                .andExpect(status().isNotFound());
        verify(userRepo, never()).findById(anyLong());
        mvc.perform(get("/api/users/" + P5 + "/photo").session(session("USER", List.of(1L))))
                .andExpect(status().isNoContent());   // çözüldü, kullanıcı bulundu; fotoğrafı yok → 204 (bugünkü yanıt)
        verify(userRepo).findById(5L);
    }
}
