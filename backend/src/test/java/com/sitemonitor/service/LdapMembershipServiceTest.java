package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tanılama + onarım servisi: "Kullanıcı X, T'nin üyesi değil ama T'de görünüyor" vakasını ekrandan
 * açıklayabilmek (kaynak + AD desteği + girişte/yeniden eşitlemede ne olur) ve güvenle düzeltmek.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapMembershipServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock LdapDirectoryService directory;
    @Mock LdapProvisioningService provisioning;

    private LdapMembershipService service;
    private TeamSourceFakes.Store sources;

    @BeforeEach
    void setUp() {
        sources = new TeamSourceFakes.Store();
        service = new LdapMembershipService(userRepo, teamRepo, directory, provisioning, sources.service);
        when(provisioning.managerAttributeOrder()).thenReturn(List.of("extensionAttribute4", "manager"));
        when(provisioning.pruneUnsupportedTeams()).thenReturn(true);   // A1-D1: tahmin ayarı okur; üretim varsayılanı AÇIK
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(10L, "Takım A"), team(11L, "Takım B")));
        when(teamRepo.findByName("Takım B")).thenReturn(Optional.of(team(11L, "Takım B")));
        when(teamRepo.findByName("Takım A")).thenReturn(Optional.of(team(10L, "Takım A")));
    }

    private static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static AppUser x(Long... teams) {
        AppUser u = new AppUser();
        u.setId(3L);
        u.setUsername("KULLANICI_X");
        u.setAuthSource("LDAP");
        u.setActive(true);
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        u.setTeamId(teams.length > 0 ? teams[0] : null);
        return u;
    }

    @Test
    @DisplayName("membership: kaynak izi olan üyelik kaynağıyla, izi olmayan LEGACY olarak döner (LDAP'a gitmez)")
    void membership_showsSourcePerTeam() {
        AppUser u = x(10L, 11L);
        sources.put(3L, 11L, UserTeamSource.MANUAL, "admin");

        Map<String, Object> m = service.membership(u);

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("memberships");
        assertThat(rows).extracting(r -> r.get("source")).containsExactly(LdapMembershipService.LEGACY, UserTeamSource.MANUAL);
        assertThat(rows).extracting(r -> r.get("team_name")).containsExactly("Takım A", "Takım B");
        verify(directory, never()).findUser(anyString());
    }

    @Test
    @DisplayName("check: X'in T (Takım A) üyeliği AD'de desteklenmiyor → yeniden eşitlemede REMOVE; kaynaksız olduğu için girişte KEEP")
    void check_flagsUnsupportedMembership() {
        AppUser u = x(10L);
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.of(Map.of(
                "_dn", "CN=100003,OU=Staff,DC=example,DC=com", "cn", "100003",
                "memberOf", List.of("CN=Takım A,OU=ScrumGroupsArchive,DC=example,DC=com"),
                "extensionAttribute4", "100004", "manager", "CN=100005,OU=Staff,DC=example,DC=com")));

        Map<String, Object> c = service.check(u);

        assertThat(c.get("found")).isEqualTo(true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) c.get("memberships");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("team_id")).isEqualTo(10L);
            assertThat(r.get("supported_by_ad")).isEqualTo(false);
            assertThat(r.get("on_resync")).isEqualTo("REMOVE");
            assertThat(r.get("on_login")).isEqualTo("KEEP");
            assertThat(r.get("source")).isEqualTo(LdapMembershipService.LEGACY);
        });
        @SuppressWarnings("unchecked") List<Map<String, Object>> ignored = (List<Map<String, Object>>) c.get("ignored_groups");
        assertThat(ignored).singleElement().satisfies(g -> assertThat(g.get("reason")).isEqualTo("NOT_TEAM_OU"));
        @SuppressWarnings("unchecked") Map<String, Object> mgr = (Map<String, Object>) c.get("manager");
        assertThat(mgr.get("attributes_disagree")).isEqualTo(true);   // ea4=100004, manager=100005
        assertThat(mgr.get("ad_sicil")).isEqualTo("100004");
    }

    @Test
    @DisplayName("check: LDAP kaynaklı ve AD'de desteklenmeyen üyelik girişte de REMOVE; kilitliyse KEEP_LOCKED")
    void check_loginOutcomeFollowsSourceAndLock() {
        AppUser u = x(10L, 11L);
        sources.put(3L, 10L, UserTeamSource.LDAP_GROUP, "Takım A");
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.of(Map.of("cn", "100003",
                "memberOf", "CN=Takım B,OU=ScrumGroups,DC=example,DC=com")));

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.check(u).get("memberships");
        assertThat(rows).filteredOn(r -> r.get("team_id").equals(10L)).singleElement()
                .satisfies(r -> assertThat(r.get("on_login")).isEqualTo("REMOVE"));
        assertThat(rows).filteredOn(r -> r.get("team_id").equals(11L)).singleElement()
                .satisfies(r -> assertThat(r.get("on_resync")).isEqualTo("KEEP"));

        u.setTeamLocked(true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> locked =
                (List<Map<String, Object>>) service.check(u).get("memberships");
        assertThat(locked).allSatisfy(r -> assertThat(r.get("on_resync")).isEqualTo("KEEP_LOCKED"));
    }

    @Test
    @DisplayName("check: AD HİÇ takım vermiyorsa girişte yalnız LDAP kaynaklı üyelik REMOVE, kaynaksız (eski) KEEP; yeniden eşitlemede ikisi de REMOVE")
    void check_emptyAd_loginRemovesOnlyLdapSourced() {
        AppUser u = x(10L, 11L);
        sources.put(3L, 10L, UserTeamSource.LDAP_COMPANY, "company=UZMAN-Takım A");
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.of(Map.of("cn", "100003")));

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.check(u).get("memberships");
        assertThat(rows).filteredOn(r -> r.get("team_id").equals(10L)).singleElement().satisfies(r -> {
            assertThat(r.get("on_login")).isEqualTo("REMOVE");
            assertThat(r.get("on_resync")).isEqualTo("REMOVE");
        });
        assertThat(rows).filteredOn(r -> r.get("team_id").equals(11L)).singleElement().satisfies(r -> {
            assertThat(r.get("on_login")).isEqualTo("KEEP");
            assertThat(r.get("on_resync")).isEqualTo("REMOVE");
        });
    }

    @Test
    @DisplayName("check: fields — 11 AD alanı kanonik sırada, kilit + AD/uygulama farkı; müdür satırı sicillerden, müdürlük ad kısmından")
    void check_listsFieldsWithLockAndDiff() {
        AppUser u = x(10L);
        u.setDisplayName("Kullanıcı X");
        u.setEmail("x@example.com");
        u.setEmployeeId("100003");
        u.setTitle("Elle Ünvan");          // elle yazıldı → kilitli
        u.setPhone("+90 555 000 00 00");   // AD farklı ama kilitsiz → girişte AD değeri gelir
        u.setMudurlukName("Teknoloji");
        u.setManagerSicil("100004");
        u.setLockedFields("title");
        Map<String, Object> ad = new java.util.HashMap<>();
        ad.put("cn", "100003");
        ad.put("displayName", "Kullanıcı X");
        ad.put("mail", "X@EXAMPLE.COM");   // yalnız büyük/küçük harf → aynı sayılır
        ad.put("title", "AD Ünvan");
        ad.put("mobile", "+90 555 111 11 11");
        ad.put("extensionAttribute5", "42;Teknoloji");
        ad.put("extensionAttribute4", "100004");
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.of(ad));

        Map<String, Object> c = service.check(u);

        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = (List<Map<String, Object>>) c.get("fields");
        assertThat(fields).extracting(f -> f.get("key")).containsExactlyElementsOf(com.sitemonitor.model.LdapFieldLocks.FIELDS);
        Map<String, Map<String, Object>> byKey = new java.util.HashMap<>();
        for (Map<String, Object> f : fields) byKey.put((String) f.get("key"), f);
        assertThat(byKey.get("title")).containsEntry("ad", "AD Ünvan").containsEntry("local", "Elle Ünvan")
                .containsEntry("locked", true).containsEntry("differs", true);
        assertThat(byKey.get("phone")).containsEntry("locked", false).containsEntry("differs", true);
        assertThat(byKey.get("email")).containsEntry("locked", false).containsEntry("differs", false);
        assertThat(byKey.get("mudurluk")).containsEntry("ad", "Teknoloji").containsEntry("local", "Teknoloji").containsEntry("differs", false);
        assertThat(byKey.get("manager")).containsEntry("ad", "100004").containsEntry("local", "100004").containsEntry("differs", false);
        assertThat(byKey.get("department")).containsEntry("ad", null).containsEntry("local", null).containsEntry("differs", false);
        assertThat(fields).allSatisfy(f -> assertThat(f.keySet()).containsExactly("key", "ad", "local", "locked", "differs"));
        assertThat(c.get("locked_fields")).isEqualTo(List.of("title"));
    }

    @Test
    @DisplayName("check: yerel hesap AD'ye sorulmaz; AD'de bulunamayan kullanıcı found=false döner")
    void check_localOrMissing() {
        AppUser local = x(10L);
        local.setAuthSource("LOCAL");
        assertThat(service.check(local).get("reason")).isEqualTo("LOCAL_ACCOUNT");
        verify(directory, never()).findUser(anyString());

        AppUser u = x(10L);
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.empty());
        assertThat(service.check(u).get("found")).isEqualTo(false);
    }

    @Test
    @DisplayName("resync: yerel hesap reddedilir; AD'de yoksa açık hata; varsa provizyon servisine giriş gibi gider")
    void resync_pathways() {
        AppUser local = x(10L);
        local.setAuthSource("LOCAL");
        assertThatThrownBy(() -> service.resync(local)).isInstanceOf(IllegalArgumentException.class);

        AppUser u = x(10L);
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resync(u)).isInstanceOf(IllegalStateException.class);

        Map<String, Object> attrs = Map.of("cn", "100003");
        when(directory.findUser("KULLANICI_X")).thenReturn(Optional.of(attrs));
        service.resync(u);
        verify(provisioning).resyncFromAd("KULLANICI_X", attrs);
    }

    @Test
    @DisplayName("resyncTeam: kilitli ve yerel üyeler ATLANIR; biri hata verirse diğerleri sürer")
    void resyncTeam_skipsLockedAndLocal_continuesOnFailure() {
        AppUser locked = x(10L); locked.setId(1L); locked.setUsername("KILITLI"); locked.setTeamLocked(true);
        AppUser local = x(10L); local.setId(2L); local.setUsername("YEREL"); local.setAuthSource("LOCAL");
        AppUser failing = x(10L); failing.setId(3L); failing.setUsername("HATALI");
        AppUser okUser = x(10L); okUser.setId(4L); okUser.setUsername("TAMAM");
        when(teamRepo.findById(10L)).thenReturn(Optional.of(team(10L, "Takım A")));
        when(userRepo.findMembersOfTeams(List.of(10L))).thenReturn(List.of(locked, local, failing, okUser));
        when(directory.findUser("HATALI")).thenReturn(Optional.empty());
        when(directory.findUser("TAMAM")).thenReturn(Optional.of(Map.of("cn", "1")));
        AppUser after = x(); after.setId(4L); after.setUsername("TAMAM");
        when(provisioning.resyncFromAd("TAMAM", Map.of("cn", "1"))).thenReturn(
                new LdapProvisioningService.SyncResult(after, false, List.of(10L), List.of(), null, null));

        List<Map<String, Object>> r = service.resyncTeam(10L);

        assertThat(r).extracting(m -> m.get("status")).containsExactly("SKIPPED_LOCKED", "SKIPPED_LOCAL", "FAILED", "OK");
        assertThat(r.get(3).get("still_member")).isEqualTo(false);
    }
}
