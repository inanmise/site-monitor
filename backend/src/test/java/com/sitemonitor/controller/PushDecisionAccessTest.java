package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Kim bilgilendirilir?" push ayağı (2026-09-28) — kişi bazlı push kararlarını KİM görür ve kararlar Ayarlar'daki
 * "Kim alır?" aracıyla AYNI mı.
 *
 * <p>Resolver GERÇEK ({@link UserPushRecipientResolver}, depo + ayar mock'lu): "aynı servis → aynı karar kodları"
 * iddiası ancak gerçek karar mantığı koşarken anlamlıdır. Fixture kimlikleri sahte sicillerdir (N0000x).
 */
class PushDecisionAccessTest {

    private static final String GROUPS = """
            {"uzman":{"enabled":true,"source":"orgRole","patterns":["TECH"],"minLevel":"WARNING"},
             "po":{"enabled":true,"source":"orgRole","patterns":["PO"],"minLevel":"WARNING"},
             "yonetici":{"enabled":true,"source":"orgRole","patterns":["MANAGER"],"minLevel":"CRITICAL"},
             "bolum_baskani":{"enabled":false,"source":"orgRole","patterns":["BOLUM_BASKANI"],"minLevel":"CRITICAL"}}""";

    private AppUserRepository userRepo;
    private UserPushRecipientResolver resolver;
    private UserPushService pushService;

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq("site.monitor.userpush.role-groups"), anyString())).thenReturn(GROUPS);
        resolver = new UserPushRecipientResolver(userRepo, settings);
        pushService = mock(UserPushService.class);
        when(pushService.scenarioChannel(anyLong(), anyString(), anyBoolean()))
                .thenReturn(Map.of("enabled", true, "configured", true, "block_reason", "SKIPPED_QUIET_HOURS"));

        AppUser optOut = user("N00003", "PO"); optOut.setPushOptOut(true);
        AppUser passive = user("N00006", "TECH"); passive.setActive(false);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "TECH"),            // RECIPIENT
                user("N00002", null),              // NO_ORG_ROLE
                optOut,                            // SKIPPED_USER_OPT_OUT
                user("N00004", "MANAGER"),         // BELOW_MIN_LEVEL (yönetici CRITICAL+, senaryo HIGH)
                user("N00005", "BOLUM_BASKANI"),   // GROUP_DISABLED
                passive,                           // INACTIVE
                user("N00007", "DANISMAN")));      // NO_GROUP
        AppUser orphan = user("N00008", "TECH");
        orphan.setTeamIds(new LinkedHashSet<>());   // birincil takımı 5 ama üyelik satırı yok
        orphan.setTeamId(5L);
        when(userRepo.findByTeamIdOrderByUsernameAsc(5L)).thenReturn(List.of(orphan));
    }

    private static AppUser user(String username, String orgRole) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName("Kişi " + username);
        u.setTitle("Uzman");
        u.setOrgRole(orgRole);
        u.setActive(true);
        u.setPhone("05550000000");   // sızıntı kapısı: hiçbir yanıtta görünmemeli
        u.setTeamIds(new LinkedHashSet<>(Set.of(5L)));
        return u;
    }

    // ── Oturumlar (SessionScope sözleşmesi: viewTeamIds null = global) ─────────────────────────────

    private static MockHttpSession session(String role, String username, Long primary, List<Long> view, List<Long> manage,
                                           List<Long> member) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", role);
        if (primary != null) s.setAttribute("teamId", primary);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        if (member != null) s.setAttribute("memberTeamIds", new ArrayList<>(member));
        return s;
    }

    private static MockHttpSession globalAdmin() { return session("ADMIN", "admin", null, null, null, List.of()); }
    /** AD ADMIN (müdür): rol ADMIN ama kapsamlı — 5'i yönetir, 9'u yönetmez. */
    private static MockHttpSession scopedAdmin() { return session("ADMIN", "N00009", 5L, List.of(5L), List.of(5L), List.of(5L)); }
    private static MockHttpSession teamAdmin() { return session("TEAM_ADMIN", "N00010", 5L, List.of(5L, 7L), List.of(5L), List.of(5L, 7L)); }
    private static MockHttpSession member(String username) { return session("USER", username, 5L, List.of(5L), List.of(), List.of(5L)); }
    private static MockHttpSession outsider() { return session("USER", "N00011", 9L, List.of(9L), List.of(), List.of(9L)); }
    private static MockHttpSession audit() { return session("AUDIT", "N00012", null, null, List.of(), List.of()); }

    @Test
    @DisplayName("Görünürlük: global yönetici her takım; yöneten (TEAM_ADMIN/müdür) yalnız yönettiği; üye kendini; diğerleri hiç")
    void accessPerRole() {
        assertThat(PushDecisionAccess.of(globalAdmin(), 5L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.FULL, "GLOBAL_ADMIN", "FULL"));
        assertThat(PushDecisionAccess.of(globalAdmin(), 999L).level()).isEqualTo(PushDecisionAccess.Level.FULL);

        assertThat(PushDecisionAccess.of(scopedAdmin(), 5L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.FULL, "TEAM_MANAGER", "LIMITED"));
        // Müdür tuzağı: rol "ADMIN" ama yönetmediği takımda global DEĞİL
        assertThat(PushDecisionAccess.of(scopedAdmin(), 9L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.NONE, "NOT_MEMBER", "LIMITED"));

        assertThat(PushDecisionAccess.of(teamAdmin(), 5L).level()).isEqualTo(PushDecisionAccess.Level.FULL);
        assertThat(PushDecisionAccess.of(teamAdmin(), 7L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.SELF, "MEMBER_SELF", "NONE"));

        assertThat(PushDecisionAccess.of(member("N00001"), 5L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.SELF, "MEMBER_SELF", "NONE"));
        assertThat(PushDecisionAccess.of(outsider(), 5L)).isEqualTo(new PushDecisionAccess.Access(PushDecisionAccess.Level.NONE, "NOT_MEMBER", "NONE"));
        // AUDIT global OKUR ama kişi kararları kişisel veri → üyesi olmadığı takımda yok
        assertThat(PushDecisionAccess.of(audit(), 5L).level()).isEqualTo(PushDecisionAccess.Level.NONE);
        // Eski oturum (memberTeamIds yok) birincil takıma düşer
        MockHttpSession legacy = session("USER", "N00001", 5L, List.of(5L), List.of(), null);
        assertThat(PushDecisionAccess.of(legacy, 5L).level()).isEqualTo(PushDecisionAccess.Level.SELF);
        assertThat(PushDecisionAccess.of(globalAdmin(), null).level()).isEqualTo(PushDecisionAccess.Level.FULL);
        assertThat(PushDecisionAccess.of(member("N00001"), null).level()).isEqualTo(PushDecisionAccess.Level.NONE);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("FULL: alanlar VE almayanlar — karar kodları Ayarlar 'Kim alır?' (explain) ile BİREBİR aynı")
    void fullLeg_sameDecisionsAsExplainTool() {
        Map<String, Object> leg = PushDecisionAccess.pushLeg(globalAdmin(), 5L, "HIGH", false, resolver, pushService);

        List<UserPushRecipientResolver.Explanation> rows = (List<UserPushRecipientResolver.Explanation>) leg.get("push");
        assertThat(rows).extracting(UserPushRecipientResolver.Explanation::decision).containsExactly(
                "RECIPIENT", "NO_ORG_ROLE", "SKIPPED_USER_OPT_OUT", "BELOW_MIN_LEVEL", "GROUP_DISABLED", "INACTIVE",
                "NO_GROUP", "MISSING_MEMBERSHIP");
        assertThat(rows).isEqualTo(resolver.explain(5L, "HIGH"));   // aynı servis, aynı satırlar
        assertThat(leg).containsEntry("push_access", "FULL").containsEntry("push_access_reason", "GLOBAL_ADMIN")
                .containsEntry("push_settings", "FULL").containsEntry("push_viewer", "admin");
        assertThat((Map<String, Object>) leg.get("push_channel")).containsEntry("block_reason", "SKIPPED_QUIET_HOURS");

        // Ayarlar → Webhook → "Kim alır?" ucu da AYNI kararları döner (tek kaynak)
        UserPushController c = new UserPushController(mock(AppSettingsService.class), pushService,
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(com.sitemonitor.service.SecretCipher.class),
                mock(com.sitemonitor.service.AuditService.class), resolver, userRepo,
                mock(com.sitemonitor.repository.TeamRepository.class));
        Map<String, Object> explained = (Map<String, Object>) c.explain(5L, "HIGH", globalAdmin()).getBody().get("data");
        assertThat(explained.get("members")).isEqualTo(rows);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("SELF: üye yalnız KENDİ satırını görür (harf duyarsız); NONE: kişi satırı yok, kanal durumu var")
    void selfAndNoneLegs() {
        Map<String, Object> self = PushDecisionAccess.pushLeg(member("n00003"), 5L, "HIGH", false, resolver, pushService);
        List<UserPushRecipientResolver.Explanation> rows = (List<UserPushRecipientResolver.Explanation>) self.get("push");
        assertThat(rows).extracting(UserPushRecipientResolver.Explanation::username).containsExactly("N00003");
        assertThat(rows.get(0).decision()).isEqualTo("SKIPPED_USER_OPT_OUT");
        assertThat(self).containsEntry("push_access", "SELF").containsEntry("push_access_reason", "MEMBER_SELF")
                .containsEntry("push_settings", "NONE");

        // Takımın üyesi ama satırı yok (ör. yeni eklenmiş) → boş liste, başkalarının satırı DEĞİL
        Map<String, Object> ghost = PushDecisionAccess.pushLeg(member("N09999"), 5L, "HIGH", false, resolver, pushService);
        assertThat((List<?>) ghost.get("push")).isEmpty();

        for (MockHttpSession s : List.of(outsider(), audit(), scopedAdmin())) {
            Long team = "ADMIN".equals(s.getAttribute("systemRole")) ? 9L : 5L;   // müdür: yönetmediği takım
            Map<String, Object> none = PushDecisionAccess.pushLeg(s, team, "HIGH", false, resolver, pushService);
            assertThat(none).doesNotContainKey("push").containsEntry("push_access", "NONE")
                    .containsEntry("push_access_reason", "NOT_MEMBER").containsKey("push_channel");
        }
    }

    @Test
    @DisplayName("Hata yolları: explain düşerse push_error (kanal durumu kalır); kanal okunamazsa kişi kararları yine döner")
    void failurePaths() {
        UserPushRecipientResolver broken = mock(UserPushRecipientResolver.class);
        when(broken.explain(anyLong(), anyString())).thenThrow(new IllegalStateException("db down"));
        Map<String, Object> leg = PushDecisionAccess.pushLeg(globalAdmin(), 5L, "HIGH", false, broken, pushService);
        assertThat(leg).containsEntry("push_error", "db down").doesNotContainKey("push").containsKey("push_channel");

        UserPushService noChannel = mock(UserPushService.class);
        when(noChannel.scenarioChannel(anyLong(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("x"));
        Map<String, Object> leg2 = PushDecisionAccess.pushLeg(globalAdmin(), 5L, "HIGH", false, resolver, noChannel);
        assertThat(leg2).doesNotContainKey("push_channel").containsKey("push");
    }

    @Test
    @DisplayName("SIZINTI KAPISI: tel biçiminde kişi satırı yalnız izinli alanları taşır — telefon/webhook adresi YOK")
    void wireFormat_noPhoneNoUrl() throws Exception {
        ObjectMapper snake = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String json = snake.writeValueAsString(PushDecisionAccess.pushLeg(globalAdmin(), 5L, "HIGH", false, resolver, pushService));
        assertThat(json).doesNotContain("0555").doesNotContain("phone").doesNotContain("http");
        var tree = snake.readTree(json);
        var names = new java.util.TreeSet<String>();
        tree.get("push").get(0).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("username", "display_name", "title", "org_role", "active", "group",
                "group_enabled", "min_level", "opt_out", "decision");
    }

    @Test
    @DisplayName("Ayarlar 'Kim alır?' ucu da aynı kural: müdür yönetmediği takımı soramaz (403), yönettiğini sorar")
    void explainEndpoint_sameVisibilityRule() {
        UserPushController c = new UserPushController(mock(AppSettingsService.class), pushService,
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(com.sitemonitor.service.SecretCipher.class),
                mock(com.sitemonitor.service.AuditService.class), resolver, userRepo,
                mock(com.sitemonitor.repository.TeamRepository.class));
        assertThat(c.explain(5L, "HIGH", scopedAdmin()).getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> c.explain(9L, "HIGH", scopedAdmin())).isInstanceOf(SecurityException.class);
        assertThat(c.explain(9L, "HIGH", globalAdmin()).getStatusCode().value()).isEqualTo(200);
        // requireAdmin kapısı değişmedi: USER ve TEAM_ADMIN hiç giremez
        assertThatThrownBy(() -> c.explain(5L, "HIGH", member("N00001"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.explain(5L, "HIGH", teamAdmin())).isInstanceOf(SecurityException.class);
    }
}
