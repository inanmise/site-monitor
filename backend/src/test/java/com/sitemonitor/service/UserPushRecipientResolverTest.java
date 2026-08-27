package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Alıcı çözümü — K2 karma unvan modeli + şiddet kuralı + E1 opt-out.
 *
 * <p>Fixture kimlikleri Kural 0'a uygun SAHTE sicillerdir; unvanlar temsilidir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPushRecipientResolverTest {

    @Mock AppUserRepository userRepo;
    @Mock AppSettingsService appSettings;
    @InjectMocks UserPushRecipientResolver resolver;

    private static final String GROUPS_ALL_ON = """
            {"yonetici":{"enabled":true,"source":"title","patterns":["*Yönetici*","*Müdür*"],"minLevel":"HIGH"},
             "uzman":{"enabled":true,"source":"title","patterns":["*Uzman*"],"minLevel":"WARNING"},
             "po":{"enabled":true,"source":"orgRole","patterns":["PO"],"minLevel":"WARNING"}}""";

    private void groups(String json) {
        when(appSettings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.role-groups"), anyString()))
                .thenReturn(json);
    }

    private AppUser user(String username, String title, String orgRole) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName("Kişi " + username);
        u.setTitle(title);
        u.setOrgRole(orgRole);
        u.setActive(true);
        u.setTeamIds(new java.util.LinkedHashSet<>(Set.of(5L)));
        return u;
    }

    @Test
    @DisplayName("Hiçbir grup açık değilse (varsayılan) KİMSEYE gitmez — üyeler sorgulanmaz bile")
    void allGroupsOff_returnsEmpty() {
        groups(UserPushRecipientResolver.DEFAULT_GROUPS_JSON);

        assertThat(resolver.resolve(5L, "CRITICAL")).isEmpty();
    }

    @Test
    @DisplayName("Karma model: PO orgRole'dan KESİN, Uzman title DESENİNDEN eşleşir")
    void hybridMatching() {
        groups(GROUPS_ALL_ON);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "Kıdemli Uzman", null),          // uzman: title deseni
                user("N00002", "Takım Elemanı", "PO"),          // po: orgRole kesin
                user("N00003", "Takım Elemanı", null)));        // hiçbir gruba girmiyor

        var out = resolver.resolve(5L, "WARNING");

        assertThat(out).extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00001", "N00002");
    }

    @Test
    @DisplayName("Yönetici grubu yalnız HIGH/CRITICAL alır — WARNING'de listeye girmez (müdür sözleşmesi)")
    void managerGroup_minLevelHigh() {
        groups(GROUPS_ALL_ON);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00010", "Bölüm Yöneticisi", null),
                user("N00011", "Uzman", null)));

        assertThat(resolver.resolve(5L, "WARNING"))
                .extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00011");
        assertThat(resolver.resolve(5L, "HIGH"))
                .extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactlyInAnyOrder("N00010", "N00011");
    }

    @Test
    @DisplayName("E1 opt-out: kişi listede kalır ama SKIPPED_USER_OPT_OUT nedeniyle — görünmez sessizlik yok")
    void optOut_visibleInResult() {
        groups(GROUPS_ALL_ON);
        AppUser u = user("N00020", "Uzman", null);
        u.setPushOptOut(true);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u));

        var out = resolver.resolve(5L, "HIGH");

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.username()).isEqualTo("N00020");
            assertThat(r.skipReason()).isEqualTo("SKIPPED_USER_OPT_OUT");
        });
    }

    @Test
    @DisplayName("Pasif kullanıcı ve kapalı grup üyesi HİÇ aday olmaz")
    void inactiveAndDisabledGroupExcluded() {
        groups("""
                {"yonetici":{"enabled":false,"source":"title","patterns":["*Yönetici*"],"minLevel":"HIGH"},
                 "uzman":{"enabled":true,"source":"title","patterns":["*Uzman*"],"minLevel":"WARNING"}}""");
        AppUser inactive = user("N00030", "Uzman", null);
        inactive.setActive(false);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                inactive,
                user("N00031", "Yönetici", null),    // grubu KAPALI
                user("N00032", "Uzman", null)));

        assertThat(resolver.resolve(5L, "CRITICAL"))
                .extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00032");
    }

    @Test
    @DisplayName("Çoklu takım üyeliğinde aynı kişi TEK kez döner (tekilleştirme)")
    void deduplication() {
        groups(GROUPS_ALL_ON);
        AppUser u = user("N00040", "Uzman", null);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u, u));

        assertThat(resolver.resolve(5L, "HIGH")).hasSize(1);
    }

    @Test
    @DisplayName("Bozuk role-groups JSON'u = tüm gruplar kapalı — istisna değil, sessiz güvenli taraf")
    void malformedJson_failsClosed() {
        groups("{bozuk json");

        assertThat(resolver.resolve(5L, "CRITICAL")).isEmpty();
    }

    @Test
    @DisplayName("Joker desen eşleme: başta/sonda/ortada yıldız + Türkçe harf duyarlılığı")
    void wildcardMatching() {
        assertThat(UserPushRecipientResolver.matchesAny("Kıdemli Yazılım Uzmanı", List.of("*Uzman*"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Uzman Yardımcısı", List.of("Uzman*"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Kıdemli Uzman", List.of("*Uzman"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Analist", List.of("*Uzman*"), false)).isFalse();
        assertThat(UserPushRecipientResolver.matchesAny("PO", List.of("PO"), true)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("PONY", List.of("PO"), true)).isFalse();
    }
}
