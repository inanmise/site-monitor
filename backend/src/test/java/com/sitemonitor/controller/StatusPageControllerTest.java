package com.sitemonitor.controller;

import com.sitemonitor.config.AuthInterceptor;
import com.sitemonitor.config.WebConfig;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.StatusPageService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kurum içi Durum Sayfası ucu (2026-10-01): "Giriş gerektirir, dışarıya açılmaz" sözü — oturumsuz istek 401 (AuthInterceptor,
 * PUBLIC listesinde değil), oturum açmış herkes görür (izin anahtarı yok); "mevcudu bozma" — görüntüleyici yüklemleri bugünkü
 * ekranların kurallarıyla AYNI (izleme listesi = SessionScope.canView, olay = incidents.view + IncidentController.canReadIncident,
 * bakım = maintenance.view + MaintenanceController.canSeeWindow); bellek anahtarı kapsam + iki izin bayrağı; {@code fresh};
 * {@code no-store}.
 */
class StatusPageControllerTest {

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "kullanici");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    private static PermissionService perms(boolean incidents, boolean maintenance) {
        PermissionService p = mock(PermissionService.class);
        when(p.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(incidents);
        when(p.allows(any(jakarta.servlet.http.HttpSession.class), eq("maintenance.view"), eq("view"))).thenReturn(maintenance);
        return p;
    }

    @Test
    @DisplayName("Uç: GET /api/status-page")
    void mapping() throws Exception {
        assertThat(StatusPageController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api");
        assertThat(StatusPageController.class.getMethod("statusPage", String.class, jakarta.servlet.http.HttpSession.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/status-page");
    }

    @Test
    @DisplayName("Global görüntüleyici → anahtar ALL + izin bayrakları; üç yüklem de her şeyi görür")
    void globalViewer() {
        StatusPageService svc = mock(StatusPageService.class);
        when(svc.view(anyString(), any(), anyBoolean())).thenReturn(Map.of("k", "v"));
        StatusPageController c = new StatusPageController(svc, perms(true, true));

        assertThat(c.statusPage(null, session("ADMIN", null)).getBody()).containsEntry("success", true).containsEntry("data", Map.of("k", "v"));
        ArgumentCaptor<StatusPageService.Viewer> v = ArgumentCaptor.forClass(StatusPageService.Viewer.class);
        verify(svc).view(eq("ALL|inc=1|mw=1"), v.capture(), eq(false));
        assertThat(v.getValue().canSeeTeam().test(14L)).isTrue();
        assertThat(v.getValue().canSeeTeam().test(null)).isTrue();
        assertThat(v.getValue().canReadIncident().test(99L, 99L)).isTrue();
        assertThat(v.getValue().canReadIncident().test(null, null)).isTrue();
        assertThat(v.getValue().canSeeWindow().test(99L, false)).isTrue();

        c.statusPage(null, session("AUDIT", null));   // AUDIT de global görüntüleyici
        verify(svc, times(2)).view(eq("ALL|inc=1|mw=1"), any(), eq(false));
    }

    @Test
    @DisplayName("Kapsamlı kullanıcı: izleme listesi görüş takımları; olay = kayıt takımı YA DA giren takım kapsamda; pencere = kendi takımı / tüm izlemeler / takımsız")
    void scopedViewer() {
        StatusPageService svc = mock(StatusPageService.class);
        when(svc.view(anyString(), any(), anyBoolean())).thenReturn(Map.of());
        StatusPageController c = new StatusPageController(svc, perms(true, true));

        c.statusPage(null, session("USER", List.of(14L, 3L)));
        ArgumentCaptor<StatusPageService.Viewer> v = ArgumentCaptor.forClass(StatusPageService.Viewer.class);
        verify(svc).view(eq("T:3,14|inc=1|mw=1"), v.capture(), eq(false));
        StatusPageService.Viewer viewer = v.getValue();
        assertThat(viewer.canSeeTeam().test(14L)).isTrue();
        assertThat(viewer.canSeeTeam().test(99L)).isFalse();
        assertThat(viewer.canSeeTeam().test(null)).isFalse();
        assertThat(viewer.canReadIncident().test(14L, null)).isTrue();
        assertThat(viewer.canReadIncident().test(99L, 3L)).isTrue();     // girenin takımı kapsamda
        assertThat(viewer.canReadIncident().test(99L, 99L)).isFalse();
        assertThat(viewer.canReadIncident().test(null, null)).isFalse();  // takımsız olay: kapsamlıya görünmez (Olaylar listesiyle aynı)
        assertThat(viewer.canSeeWindow().test(14L, false)).isTrue();
        assertThat(viewer.canSeeWindow().test(99L, false)).isFalse();
        assertThat(viewer.canSeeWindow().test(99L, true)).isTrue();       // tüm izlemeler penceresi
        assertThat(viewer.canSeeWindow().test(null, false)).isTrue();     // takımsız (legacy) pencere

        // Kapsam listesi olmayan ayrıcalıksız oturum: kimseyi görmez ama sayfa yine döner (toplu görünüm)
        c.statusPage(null, session("USER", null));
        verify(svc).view(eq("NONE|inc=1|mw=1"), v.capture(), eq(false));
        assertThat(v.getValue().canSeeTeam().test(14L)).isFalse();
        assertThat(v.getValue().canReadIncident().test(14L, 14L)).isFalse();
    }

    @Test
    @DisplayName("İzin yoksa (incidents.view / maintenance.view) satır yüklemi kapalı ve bellek anahtarı ayrışır — global yöneticide bile")
    void permissionsGateRows() {
        StatusPageService svc = mock(StatusPageService.class);
        when(svc.view(anyString(), any(), anyBoolean())).thenReturn(Map.of());
        new StatusPageController(svc, perms(false, false)).statusPage(null, session("ADMIN", null));
        ArgumentCaptor<StatusPageService.Viewer> v = ArgumentCaptor.forClass(StatusPageService.Viewer.class);
        verify(svc).view(eq("ALL|inc=0|mw=0"), v.capture(), eq(false));
        assertThat(v.getValue().canReadIncident().test(14L, 14L)).isFalse();
        assertThat(v.getValue().canSeeWindow().test(null, true)).isFalse();
        assertThat(v.getValue().canSeeTeam().test(14L)).isTrue();          // izleme listesi izinden bağımsız (görüş kapsamı)
        assertThat(StatusPageController.memoKey("T:1", true, false)).isEqualTo("T:1|inc=1|mw=0");
    }

    @Test
    @DisplayName("TEK kaynak kurallar: IncidentController.canReadIncident ve MaintenanceController.canSeeWindow mevcut uçların davranışı")
    void sharedRules() {
        MockHttpSession admin = session("ADMIN", null), user = session("USER", List.of(5L));
        assertThat(IncidentController.canReadIncident(admin, null, null)).isTrue();
        assertThat(IncidentController.canReadIncident(user, 5L, 9L)).isTrue();
        assertThat(IncidentController.canReadIncident(user, 9L, 5L)).isTrue();
        assertThat(IncidentController.canReadIncident(user, 9L, 9L)).isFalse();
        assertThat(IncidentController.canReadIncident(user, null, null)).isFalse();
        assertThat(IncidentController.canReadIncident(session("USER", null), 5L, 5L)).isFalse();
        assertThat(MaintenanceController.canSeeWindow(admin, 9L, false)).isTrue();
        assertThat(MaintenanceController.canSeeWindow(user, 5L, false)).isTrue();
        assertThat(MaintenanceController.canSeeWindow(user, 9L, false)).isFalse();
        assertThat(MaintenanceController.canSeeWindow(user, 9L, true)).isTrue();
        assertThat(MaintenanceController.canSeeWindow(user, null, null)).isTrue();
    }

    @Test
    @DisplayName("fresh=1 / true → taze yol; parametresiz, 0 ya da çöp → bellek")
    void freshParam() {
        StatusPageService svc = mock(StatusPageService.class);
        when(svc.view(anyString(), any(), anyBoolean())).thenReturn(Map.of());
        StatusPageController c = new StatusPageController(svc, perms(true, true));
        c.statusPage("1", session("ADMIN", null));
        c.statusPage(" TRUE ", session("ADMIN", null));
        verify(svc, times(2)).view(eq("ALL|inc=1|mw=1"), any(), eq(true));
        c.statusPage(null, session("ADMIN", null));
        c.statusPage("0", session("ADMIN", null));
        c.statusPage("yes-please", session("ADMIN", null));
        verify(svc, times(3)).view(eq("ALL|inc=1|mw=1"), any(), eq(false));
    }

    @Test
    @DisplayName("Giriş gerektirir: oturumsuz istek 401 (PUBLIC değil, normalize yol varyantları da); oturum açmış her rol geçer")
    void requiresSession() throws Exception {
        UserService users = mock(UserService.class);
        AuthInterceptor interceptor = new AuthInterceptor(mock(RememberMeService.class), users, mock(AuthController.class), mock(AuditService.class));
        for (String p : new String[]{"/api/status-page", "/api/status-page/", "/api;x/status-page", "//api/status-page"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", p);
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(req, res, new Object())).as(p).isFalse();
            assertThat(res.getStatus()).as(p).isEqualTo(401);
        }
        for (String role : new String[]{"USER", "AUDIT", "ADMIN"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/status-page");
            req.setSession(session(role, "ADMIN".equals(role) ? null : List.of(1L)));
            assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).as(role).isTrue();
        }
        // Parola değiştirme zorunluyken erişilemez (beyaz listede değil)
        MockHttpServletRequest forced = new MockHttpServletRequest("GET", "/api/status-page");
        MockHttpSession s = session("USER", List.of(1L));
        s.setAttribute("mustChangePassword", true);
        forced.setSession(s);
        MockHttpServletResponse fr = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(forced, fr, new Object())).isFalse();
        assertThat(fr.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("PUBLIC listesinde durum sayfası yolu YOK (dışarıya açılmaz)")
    @SuppressWarnings("unchecked")
    void notPublic() throws Exception {
        Field f = AuthInterceptor.class.getDeclaredField("PUBLIC");
        f.setAccessible(true);
        Set<String> pub = (Set<String>) f.get(null);
        assertThat(pub).isNotEmpty().noneMatch(p -> p.contains("status"));
    }

    @Test
    @DisplayName("Yanıt önbelleğe alınmaz: Cache-Control no-store (public / max-age yok)")
    void noStore() throws Exception {
        var filter = new WebConfig().securityHeadersFilter();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/status-page"), res, new MockFilterChain());
        assertThat(res.getHeader("Cache-Control")).contains("no-store").doesNotContain("public").doesNotContain("max-age");
    }
}
