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
            {"yonetici":{"enabled":true,"source":"orgRole","patterns":["MANAGER","BOLUM_BASKANI","CLEVEL"],"minLevel":"HIGH"},
             "uzman":{"enabled":true,"source":"orgRole","patterns":["TECH"],"minLevel":"WARNING"},
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
    @DisplayName("2026-09-11 (ürün kararı): gruplar ORG ROLÜYLE eşleşir, UNVAN yok sayılır — 'Yazılım Geliştirici' unvanlı TECH, Uzman grubuna girer")
    void orgRoleMatching_titleIgnored() {
        groups(GROUPS_ALL_ON);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "Yazılım Geliştirici", "TECH"),   // uzman: org rolü TECH (unvan desene uymasa da)
                user("N00002", "Takım Elemanı", "PO"),           // po
                user("N00003", "Kıdemli Uzman", null)));         // org rolü YOK → unvanı "Uzman" olsa da aday değil

        var out = resolver.resolve(5L, "WARNING");

        assertThat(out).extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00001", "N00002");
    }

    @Test
    @DisplayName("Eski kayıt (source:title + unvan desenleri) okunurken grup anahtarının org-rol kümesine çevrilir")
    void legacyTitleConfig_convertedToOrgRoles() {
        groups("""
                {"yonetici":{"enabled":true,"source":"title","patterns":["*Yönetici*","*Müdür*"],"minLevel":"HIGH"},
                 "uzman":{"enabled":true,"source":"title","patterns":["*Uzman*"],"minLevel":"WARNING"}}""");
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "Yazılım Geliştirici", "TECH"),     // eski desen "*Uzman*" uymazdı; org rolüyle girer
                user("N00002", "Bölüm Müdürü", "MANAGER"),
                user("N00003", "Bölüm Müdürü", null)));            // unvanı eşleşirdi; artık org rolü olmadan girmez

        assertThat(resolver.resolve(5L, "HIGH")).extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00001", "N00002");
        var rules = resolver.groupRules();
        assertThat(rules.get("uzman").patterns()).containsExactly("TECH");
        assertThat(rules.get("yonetici").patterns()).containsExactly("MANAGER", "BOLUM_BASKANI", "CLEVEL");
        assertThat(rules.get("yonetici").source()).isEqualTo("orgRole");
    }

    @Test
    @DisplayName("Yönetici grubu yalnız HIGH/CRITICAL alır — WARNING'de listeye girmez (müdür sözleşmesi)")
    void managerGroup_minLevelHigh() {
        groups(GROUPS_ALL_ON);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00010", "Bölüm Yöneticisi", "MANAGER"),
                user("N00011", "Uzman", "TECH")));

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
        AppUser u = user("N00020", "Uzman", "TECH");
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
                {"yonetici":{"enabled":false,"source":"orgRole","patterns":["MANAGER"],"minLevel":"HIGH"},
                 "uzman":{"enabled":true,"source":"orgRole","patterns":["TECH"],"minLevel":"WARNING"}}""");
        AppUser inactive = user("N00030", "Uzman", "TECH");
        inactive.setActive(false);
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                inactive,
                user("N00031", "Yönetici", "MANAGER"),    // grubu KAPALI
                user("N00032", "Uzman", "TECH")));

        assertThat(resolver.resolve(5L, "CRITICAL"))
                .extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00032");
    }

    @Test
    @DisplayName("Çoklu takım üyeliğinde aynı kişi TEK kez döner (tekilleştirme)")
    void deduplication() {
        groups(GROUPS_ALL_ON);
        AppUser u = user("N00040", "Uzman", "TECH");
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
    @DisplayName("matchesAny yardımcısı: joker + kesin eşleme (grup eşlemesi yalnız KESİN/orgRole yolunu kullanır)")
    void wildcardMatching() {
        assertThat(UserPushRecipientResolver.matchesAny("Kıdemli Yazılım Uzmanı", List.of("*Uzman*"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Uzman Yardımcısı", List.of("Uzman*"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Kıdemli Uzman", List.of("*Uzman"), false)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("Analist", List.of("*Uzman*"), false)).isFalse();
        assertThat(UserPushRecipientResolver.matchesAny("PO", List.of("PO"), true)).isTrue();
        assertThat(UserPushRecipientResolver.matchesAny("PONY", List.of("PO"), true)).isFalse();
    }

    // 2026-09-10: RESOLVE alicilari = acilista push ALANLAR; seviye/grup kurali uygulanmaz.
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("resolvePrior: aktif kullanici gider, opt-out sebebiyle isaretlenir, pasif/bilinmeyen/sistem satiri dusurulur, tekrar tekillesir")
    void resolvePrior_activeOptOutInactive() {
        AppUser ok = user("N1", "Uzman", "MEMBER");
        AppUser optOut = user("N2", "Uzman", "MEMBER"); optOut.setPushOptOut(true);
        AppUser gone = user("N3", "Uzman", "MEMBER"); gone.setActive(false);
        when(userRepo.findByUsername("N1")).thenReturn(java.util.Optional.of(ok));
        when(userRepo.findByUsername("N2")).thenReturn(java.util.Optional.of(optOut));
        when(userRepo.findByUsername("N3")).thenReturn(java.util.Optional.of(gone));
        when(userRepo.findByUsername("N9")).thenReturn(java.util.Optional.empty());

        var out = resolver.resolvePrior(java.util.List.of("N1", "N2", "N3", "N9", "-", "N1"));

        assertThat(out).extracting(UserPushRecipientResolver.Recipient::username).containsExactly("N1", "N2");
        assertThat(out.get(0).skipReason()).isNull();
        assertThat(out.get(1).skipReason()).isEqualTo("SKIPPED_USER_OPT_OUT");
    }

    @Test
    @DisplayName("2026-09-11: explain() elenenleri de gerekçesiyle döndürür — 'aynı takımda ama push gitmiyor' sorusunun cevabı")
    void explain_listsEveryMemberWithDecision() {
        groups(GROUPS_ALL_ON);
        AppUser optOut = user("N00004", "Kıdemli Uzman", "TECH"); optOut.setPushOptOut(true);
        AppUser passive = user("N00005", "Uzman", "TECH"); passive.setActive(false);
        AppUser noId = user("", "Uzman", "TECH");
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "Kıdemli Uzman", "TECH"),        // RECIPIENT
                user("N00002", "Takım Elemanı", "PO"),          // RECIPIENT (PO)
                user("N00003", "Yazılım Geliştirici", null),    // NO_ORG_ROLE — org rolü atanmamış (veri eksik)
                user("N00006", "Bölüm Müdürü", "MANAGER"),      // BELOW_MIN_LEVEL (yönetici HIGH+, olay WARNING)
                optOut, passive, noId));
        AppUser orphan = user("N00007", "Uzman", "TECH");
        orphan.setTeamIds(new java.util.LinkedHashSet<>());   // üyelik tablosunda satırı yok
        orphan.setTeamId(5L);
        when(userRepo.findByTeamIdOrderByUsernameAsc(5L)).thenReturn(List.of(orphan));

        var out = resolver.explain(5L, "WARNING");

        assertThat(out).extracting(UserPushRecipientResolver.Explanation::decision)
                .containsExactly("RECIPIENT", "RECIPIENT", "NO_ORG_ROLE", "BELOW_MIN_LEVEL",
                                 "SKIPPED_USER_OPT_OUT", "INACTIVE", "SKIPPED_NO_ID", "MISSING_MEMBERSHIP");
        assertThat(out.get(2).group()).isNull();
        assertThat(out.get(3).group()).isEqualTo("yonetici");
        assertThat(out.get(3).minLevel()).isEqualTo("HIGH");
        assertThat(out.get(4).optOut()).isTrue();
        // Emrullah vakası (2026-09-11): unvanı hiçbir desene uymayan ama org rolü TECH olan üye ALIR;
        // org rolü hiçbir gruba atanmamış (ör. özel kod) üye NO_GROUP'tur.
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00008", "Yazılım Geliştirici", "TECH"),
                user("N00009", "Analist", "DANISMAN")));
        when(userRepo.findByTeamIdOrderByUsernameAsc(5L)).thenReturn(List.of());
        assertThat(resolver.explain(5L, "WARNING")).extracting(UserPushRecipientResolver.Explanation::decision)
                .containsExactly("RECIPIENT", "NO_GROUP");
        when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(
                user("N00001", "Kıdemli Uzman", "TECH"), user("N00002", "Takım Elemanı", "PO"),
                user("N00003", "Yazılım Geliştirici", null), user("N00006", "Bölüm Müdürü", "MANAGER"),
                optOut, passive, noId));
        // resolve() ile tutarlı: yalnız RECIPIENT + SKIPPED_* satırları oradaki listede
        assertThat(resolver.resolve(5L, "WARNING")).extracting(UserPushRecipientResolver.Recipient::username)
                .containsExactly("N00001", "N00002", "N00004", "-");
    }
}
