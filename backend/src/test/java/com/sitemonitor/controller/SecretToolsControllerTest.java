package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SecretRotationService;
import com.sitemonitor.service.SecretToolsService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SecretToolsController.class)
class SecretToolsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SecretToolsService service;
    @MockitoBean SecretRotationService rotation;
    @MockitoBean SecretCipher cipher;
    @MockitoBean AuditService auditService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        // Bootstrap admin ("admin") require'ı atlar; non-bootstrap için matris izni reddini simüle et → 403.
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST decrypt oturumsuz → 401")
    void decrypt_unauthenticated_401() throws Exception {
        mvc.perform(post("/api/admin/secret-tools/decrypt")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST decrypt admin olmayan → 403")
    void decrypt_nonAdmin_403() throws Exception {
        mvc.perform(post("/api/admin/secret-tools/decrypt").session(session("sre1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST decrypt bootstrap admin → 200 + data")
    void decrypt_admin_200() throws Exception {
        when(service.decryptWithKey("mykey")).thenReturn(List.of(
                Map.of("label", "SMTP Parolası", "column", "smtp_settings.password_enc",
                        "present", true, "ok", true, "value", "s3cret")));
        mvc.perform(post("/api/admin/secret-tools/decrypt").session(session("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"mykey\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].ok").value(true));
    }

    @Test
    @DisplayName("2026-10-11: döndürme durumu — bootstrap admin 200, yetkisiz 403; info önceki anahtar bayrağını taşır")
    void rotationStatus() throws Exception {
        when(rotation.status()).thenReturn(Map.of("totals", Map.of("previous", 3), "can_reencrypt", true));
        when(cipher.isPreviousKeyConfigured()).thenReturn(true);
        mvc.perform(get("/api/admin/secret-tools/rotation").session(session("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.previous").value(3));
        mvc.perform(get("/api/admin/secret-tools/rotation").session(session("sre1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/secret-tools/info").session(session("admin")))
                .andExpect(jsonPath("$.previous_key_set").value(true));
    }

    @Test
    @DisplayName("2026-10-11: yeniden şifreleme onaysız 400; onaylı 200 + yalnız sayılarla denetim; ret 409 + kod")
    void reencrypt() throws Exception {
        mvc.perform(post("/api/admin/secret-tools/rotation/reencrypt").session(session("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(rotation, never()).reencrypt(anyString());

        when(rotation.reencrypt("admin")).thenReturn(Map.of("values_rewritten", 4, "rows_rewritten", 3,
                "changed_meanwhile", 0, "unreadable", 1));
        mvc.perform(post("/api/admin/secret-tools/rotation/reencrypt").session(session("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values_rewritten").value(4));
        verify(auditService).recordAction(eq("SECRET_REENCRYPT"), org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("secret-tools"),
                eq("{\"values\":4,\"rows\":3,\"changed_meanwhile\":0,\"unreadable\":1}"));

        when(rotation.reencrypt("admin")).thenThrow(new SecretRotationService.RotationRefused("REENCRYPT_RUNNING", "sürüyor"));
        mvc.perform(post("/api/admin/secret-tools/rotation/reencrypt").session(session("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REENCRYPT_RUNNING"))
                .andExpect(jsonPath("$.success").value(false));

        mvc.perform(post("/api/admin/secret-tools/rotation/reencrypt").session(session("sre1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));   // settings gate bayrağı (literal username yerine)
        return s;
    }
}
