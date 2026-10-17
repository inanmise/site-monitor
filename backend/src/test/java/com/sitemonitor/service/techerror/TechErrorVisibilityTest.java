package com.sitemonitor.service.techerror;

import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Modül görünürlüğü (2026-10-17, S6): CSV ayar, takım ve oturum kuralları, statik erişim. */
class TechErrorVisibilityTest {

    @AfterEach
    void reset() {
        TechErrorVisibilityService.setCurrent(null);
    }

    private static TechErrorVisibilityService withSetting(String csv) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(eq(TechErrorReportSettings.KEY_TEAM_IDS), any())).thenReturn(csv);
        return new TechErrorVisibilityService(s);
    }

    private static MockHttpSession session(String role, Long primary, List<Long> view, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (primary != null) s.setAttribute("teamId", primary);
        if (view != null) s.setAttribute("viewTeamIds", view);
        if (manage != null) s.setAttribute("manageTeamIds", manage);
        return s;
    }

    @Test
    @DisplayName("ayar: boş = herkes (null); CSV ayrıştırma toleranslı (bozuk parça atlanır, 0 / eksi yok); kayıt katı (bozuk parça 400, > 500 takım 400)")
    void settingParsing() {
        assertThat(TechErrorReportSettings.enabledTeamIds(null)).isNull();
        assertThat(TechErrorReportSettings.enabledTeamIds("  ")).isNull();
        assertThat(TechErrorReportSettings.enabledTeamIds("3, 5;7 x 0 -1")).containsExactly(3L, 5L, 7L);
        assertThat(TechErrorReportSettings.enabledTeamIds("x")).isNull();
        TechErrorReportSettings.validate(TechErrorReportSettings.KEY_TEAM_IDS, "3,5");
        TechErrorReportSettings.validate("baska.anahtar", "x");                  // başka anahtar: dokunmaz
        TechErrorReportSettings.validate(TechErrorReportSettings.KEY_TEAM_IDS, "");  // boş: varsayılana dön
        assertThatThrownBy(() -> TechErrorReportSettings.validate(TechErrorReportSettings.KEY_TEAM_IDS, "3,x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TechErrorReportSettings.validate(TechErrorReportSettings.KEY_TEAM_IDS, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 501; i++) many.append(i).append(',');
        assertThatThrownBy(() -> TechErrorReportSettings.validate(TechErrorReportSettings.KEY_TEAM_IDS, many.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TechErrorReportSettings.isKey(TechErrorReportSettings.KEY_TEAM_IDS)).isTrue();
    }

    @Test
    @DisplayName("takım ve oturum: kısıtsız ayarda herkes; kısıtlıda yalnız listedeki takımlar — üyelik, yönetim ya da görüş kapsamı yeter; kurum geneli görüntüleyici (global admin, AUDIT) her zaman; okuma hatası = herkes")
    void teamAndSessionRules() {
        TechErrorVisibilityService open = withSetting("");
        assertThat(open.restricted()).isFalse();
        assertThat(open.teamVisible(42L)).isTrue();
        assertThat(open.sessionSees(session("USER", 42L, null, null))).isTrue();

        TechErrorVisibilityService v = withSetting("1,2");
        assertThat(v.restricted()).isTrue();
        assertThat(v.teamVisible(1L)).isTrue();
        assertThat(v.teamVisible(3L)).isFalse();
        assertThat(v.teamVisible(null)).isFalse();
        assertThat(v.sessionSees(session("USER", 1L, null, null))).isTrue();            // üye
        assertThat(v.sessionSees(session("USER", 3L, null, null))).isFalse();           // başka takımın üyesi
        assertThat(v.sessionSees(session("ADMIN", 9L, List.of(2L), List.of(2L)))).isTrue();   // kapsamlı müdür, görüş kapsamında açık takım
        assertThat(v.sessionSees(session("ADMIN", 9L, List.of(3L), List.of(3L)))).isFalse();
        assertThat(v.sessionSees(session("ADMIN", null, null, null))).isTrue();         // global yönetici
        // Erişim paylaşımı (9. sürüm): kendi takımı kapalı, paylaşan takım açık → modül görünür (menü + 403 kapısı aynı küme)
        MockHttpSession granted = session("USER", 3L, null, null);
        granted.setAttribute(com.sitemonitor.controller.SessionScope.ATTR_GRANT_VIEW, new java.util.ArrayList<>(List.of(1L)));
        assertThat(v.sessionSees(granted)).isTrue();
        MockHttpSession grantedClosed = session("USER", 3L, null, null);
        grantedClosed.setAttribute(com.sitemonitor.controller.SessionScope.ATTR_GRANT_VIEW, new java.util.ArrayList<>(List.of(4L)));
        assertThat(v.sessionSees(grantedClosed)).isFalse();
        assertThat(v.sessionSees(session("AUDIT", 3L, null, null))).isTrue();           // kurum geneli görüntüleyici
        assertThat(v.sessionSees(null)).isFalse();

        AppSettingsService broken = mock(AppSettingsService.class);
        when(broken.getString(any(), any())).thenThrow(new IllegalStateException("db"));
        assertThat(new TechErrorVisibilityService(broken).enabledTeamIds()).isNull();

        // Statik erişim: bean yoksa her takım görünür; varsa onun kuralı
        TechErrorVisibilityService.setCurrent(null);
        assertThat(TechErrorVisibilityService.visible(3L)).isTrue();
        TechErrorVisibilityService.setCurrent(v);
        assertThat(TechErrorVisibilityService.visible(3L)).isFalse();
        assertThat(TechErrorVisibilityService.visible(1L)).isTrue();
        assertThat(TechErrorVisibilityService.current()).isSameAs(v);
    }
}
