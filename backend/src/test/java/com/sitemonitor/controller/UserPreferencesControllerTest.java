package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserPreferencesService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code /api/me/preferences}: yalnız OTURUMDAKİ kullanıcının satırı (istekte kullanıcı parametresi yok — gövdedeki
 * bir kimlik beyaz liste dışı anahtar olarak 400 olur), kullanıcı kimliği yoksa kullanıcı adından çözülür, oturumsuz
 * 403; denetim YALNIZ açık seçimler (favoriler / açılış sekmesi / görünümler) değişince — {@code local} aynası denetlenmez.
 */
class UserPreferencesControllerTest {

    private static MockHttpSession session(Long userId, String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        if (username != null) s.setAttribute("username", username);
        if (userId != null) s.setAttribute("userId", userId);
        return s;
    }

    private final UserPreferencesService svc = mock(UserPreferencesService.class);
    private final UserService users = mock(UserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final UserPreferencesController c = new UserPreferencesController(svc, users, audit);

    @Test
    @DisplayName("Uçlar: GET + PUT /api/me/preferences")
    void mapping() throws Exception {
        assertThat(UserPreferencesController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/me");
        assertThat(UserPreferencesController.class.getMethod("get", jakarta.servlet.http.HttpSession.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/preferences");
        assertThat(UserPreferencesController.class.getMethod("put", Map.class, jakarta.servlet.http.HttpSession.class)
                .getAnnotation(PutMapping.class).value()).containsExactly("/preferences");
    }

    @Test
    @DisplayName("GET oturumdaki kullanıcının belgesini döner")
    void getOwnRow() {
        when(svc.get(42L)).thenReturn(new UserPreferencesService.Snapshot(Map.of("landingTab", "http"), "2026-10-02T08:00:00Z"));
        var body = c.get(session(42L, "ayse")).getBody();
        assertThat(body).containsEntry("success", true).containsEntry("updated_at", "2026-10-02T08:00:00Z");
        assertThat(body.get("prefs")).isEqualTo(Map.of("landingTab", "http"));
        verify(svc).get(42L);
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("userId oturumda yoksa kullanıcı adından çözülür; ikisi de yoksa 403 (SecurityException)")
    void resolvesUserIdFromUsername() {
        AppUser u = new AppUser();
        u.setId(9L);
        when(users.findByUsername("mehmet")).thenReturn(Optional.of(u));
        when(svc.get(9L)).thenReturn(new UserPreferencesService.Snapshot(Map.of(), null));
        c.get(session(null, "mehmet"));
        verify(svc).get(9L);

        assertThatThrownBy(() -> c.get(session(null, null))).isInstanceOf(SecurityException.class);
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> c.put(Map.of("landingTab", "http"), session(null, "ghost"))).isInstanceOf(SecurityException.class);
        verify(svc, never()).merge(anyLong(), any());
    }

    @Test
    @DisplayName("PUT yalnız oturumdaki kullanıcıya yazar; favoriler değişince USER_PREFERENCES_UPDATE denetlenir")
    void putAuditsExplicitChoices() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("favorites", List.of(Map.of("type", "http", "id", 1L)));
        when(svc.merge(eq(42L), any())).thenReturn(new UserPreferencesService.Result(prefs, Set.of("favorites"), "2026-10-02T08:00:00Z"));
        var body = c.put(Map.of("favorites", List.of(Map.of("type", "http", "id", 1))), session(42L, "ayse")).getBody();
        assertThat(body).containsEntry("success", true).containsEntry("prefs", prefs);
        verify(svc).merge(eq(42L), any());
        verify(audit).recordAction(eq("USER_PREFERENCES_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER"), eq("42"), contains("favorites"), eq("{\"favorites\":1}"));
    }

    @Test
    @DisplayName("yalnız local aynası değişince denetim YAZILMAZ (gürültü)")
    void localMirrorNotAudited() {
        when(svc.merge(eq(42L), any())).thenReturn(new UserPreferencesService.Result(Map.of("local", Map.of("sidebar-open", "false")), Set.of("local"), "t"));
        c.put(Map.of("local", Map.of("sidebar-open", "false")), session(42L, "ayse"));
        verify(audit, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("doğrulama hatası (bilinmeyen anahtar) servisten 400 olarak yayılır — denetim yok")
    void validationErrorPropagates() {
        when(svc.merge(eq(42L), any())).thenThrow(new IllegalArgumentException("Bilinmeyen tercih anahtarı: userId"));
        assertThatThrownBy(() -> c.put(Map.of("userId", 7), session(42L, "ayse"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(audit);
    }
}
