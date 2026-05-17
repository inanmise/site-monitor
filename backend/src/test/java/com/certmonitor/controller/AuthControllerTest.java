package com.certmonitor.controller;

import com.certmonitor.model.AppUser;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    RememberMeService rememberMeService;

    @MockBean
    UserService userService;

    @MockBean
    com.certmonitor.service.HttpMetricsService httpMetricsService;

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
                .andExpect(jsonPath("$.username").value("testuser"));
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
}
