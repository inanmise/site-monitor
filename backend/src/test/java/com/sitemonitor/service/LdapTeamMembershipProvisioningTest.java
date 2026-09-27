package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD HATASI 2026-09-26: "Kullanıcı X, T takımının üyesi olmadığı hâlde Yönetim Panelinde T içinde
 * görünüyor; X'in müdür sicili bölüm başkanını, T'deki Y'nin müdürü X'i gösteriyor". Provizyon tarafında
 * bu duruma katkı veren her yol burada tek tek sabitlenir. Adlar/siciller yer tutucu.
 *
 * <p>Kurulum: kullanıcı ve takım depoları bellek-içi (id → kayıt), üyelik kaynak izi gerçek
 * {@link TeamMembershipSourceService} + sahte depo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapTeamMembershipProvisioningTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock LdapDirectoryService directory;
    @Mock EscalationContactRepository contactRepo;
    @Mock AppSettingsService appSettings;
    @Mock AuditService auditService;

    private LdapProvisioningService service;
    private TeamSourceFakes.Store sources;
    private final Map<String, AppUser> usersByName = new HashMap<>();
    private final Map<String, Team> teamsByName = new HashMap<>();
    private final Map<String, Object> settings = new HashMap<>();

    @BeforeEach
    void setUp() {
        sources = new TeamSourceFakes.Store();
        service = new LdapProvisioningService(userRepo, teamRepo, directory, contactRepo, appSettings,
                sources.service, auditService);
        when(appSettings.getBoolean(anyString(), any(Boolean.class)))
                .thenAnswer(inv -> settings.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(appSettings.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> settings.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(appSettings.getCsv(anyString(), anyString())).thenAnswer(inv -> {
            Object v = settings.get(inv.getArgument(0));
            String raw = v != null ? v.toString() : inv.getArgument(1);
            return List.of(raw.split(","));
        });
        AtomicLong userSeq = new AtomicLong(100);
        AtomicLong teamSeq = new AtomicLong(10);
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(userSeq.incrementAndGet());
            usersByName.put(u.getUsername(), u);
            return u;
        });
        when(userRepo.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(usersByName.get(((String) inv.getArgument(0)).toUpperCase(Locale.ROOT))));
        when(userRepo.findAllByEmployeeIdNormalized(anyString())).thenAnswer(inv -> {
            String s = ((String) inv.getArgument(0)).trim();
            return usersByName.values().stream().filter(u -> u.getEmployeeId() != null
                    && u.getEmployeeId().trim().equalsIgnoreCase(s)).toList();
        });
        when(userRepo.findById(any())).thenAnswer(inv -> usersByName.values().stream()
                .filter(u -> inv.getArgument(0).equals(u.getId())).findFirst());
        when(teamRepo.save(any(Team.class))).thenAnswer(inv -> {
            Team t = inv.getArgument(0);
            if (t.getId() == null) t.setId(teamSeq.incrementAndGet());
            teamsByName.put(t.getName(), t);
            return t;
        });
        when(teamRepo.findByName(anyString())).thenAnswer(inv -> Optional.ofNullable(teamsByName.get((String) inv.getArgument(0))));
        when(directory.groupMail(anyString())).thenReturn(Optional.empty());
        when(directory.findOne(anyString(), anyString())).thenReturn(Optional.empty());
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────

    private Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return teamRepo.save(t);
    }

    private AppUser existing(String username, String sicil, Long... teams) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setEmployeeId(sicil);
        u.setAuthSource("LDAP");
        u.setActive(true);
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        u.setTeamId(teams.length > 0 ? teams[0] : null);
        u.setUpdatedAt(ISO.format(Instant.now()));
        return userRepo.save(u);
    }

    private static String dn(String cn) {
        return "CN=" + cn + ",OU=Staff,DC=example,DC=com";
    }

    private static String group(String cn) {
        return "CN=" + cn + ",OU=ScrumGroups,OU=Staff,DC=example,DC=com";
    }

    // ── Hipotez 2: özyinelemeli müdür provizyonu astın takımını müdüre yazmaz ─────────────

    @Test
    @DisplayName("H2 (dışlandı, sabitlendi): Y'nin girişiyle provizyon edilen müdür X, takımını YALNIZ kendi AD kaydından alır — Y'nin T'si X'e geçmez")
    void recursiveManager_doesNotInheritSubordinatesTeam() {
        when(directory.findOne("cn", "100003")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "kullanici_x", "cn", "100003", "description", "D6",
                "manager", dn("100004"))));   // X'in müdürü: bölüm başkanı; X'in kendi grubu/company'si YOK
        Map<String, Object> y = Map.of("cn", "100002", "extensionAttribute4", dn("100003"),
                "memberOf", group("Takım A"));

        AppUser yUser = service.provisionFromAd("kullanici_y", "CN=kullanici_y", y);

        AppUser x = usersByName.get("KULLANICI_X");
        assertThat(yUser.getTeamIds()).hasSize(1);
        assertThat(x).isNotNull();
        assertThat(x.getTeamIds()).isEmpty();                    // T X'e geçmedi
        assertThat(x.getTeamId()).isNull();
        assertThat(yUser.getManagerId()).isEqualTo(x.getId());   // Y → X
        assertThat(x.getManagerSicil()).isEqualTo("100004");     // X → bölüm başkanı (sicil)
    }

    // ── Hipotez 4: bayat üyelik — AD artık desteklemiyor ──────────────────────────────────

    @Test
    @DisplayName("H4: AD hiç takım vermiyorsa YALNIZ AD'den türetilmiş (LDAP_*) üyelik budanır; elle ve kaynaksız (eski) üyelik kalır")
    void emptyAd_prunesOnlyLdapSourcedMemberships() {
        Team a = team("Takım A"), b = team("Takım B"), c = team("Takım C");
        AppUser u = existing("KULLANICI_X", "100003", a.getId(), b.getId(), c.getId());
        sources.put(u.getId(), a.getId(), UserTeamSource.LDAP_GROUP, "Takım A");
        sources.put(u.getId(), b.getId(), UserTeamSource.MANUAL, "admin");
        // c: kaynak izi yok (bu özellikten önce oluşmuş) → girişte silinmez

        AppUser out = service.provisionFromAd("kullanici_x", "CN=x", Map.of("cn", "100003"));

        assertThat(out.getTeamIds()).containsExactlyInAnyOrder(b.getId(), c.getId());
        assertThat(out.getTeamId()).isIn(b.getId(), c.getId());           // birincil geçerli bir üyelik
        assertThat(sources.get(u.getId(), a.getId())).isNull();           // izi de silindi
    }

    @Test
    @DisplayName("H4: budama ayarı KAPALIYSA eski davranış — AD boşken tüm üyelikler kalır")
    void emptyAd_pruneDisabled_keepsEverything() {
        settings.put(LdapProvisioningService.PRUNE_KEY, false);
        Team a = team("Takım A");
        AppUser u = existing("KULLANICI_X", "100003", a.getId());
        sources.put(u.getId(), a.getId(), UserTeamSource.LDAP_GROUP, "Takım A");

        AppUser out = service.provisionFromAd("kullanici_x", "CN=x", Map.of("cn", "100003"));

        assertThat(out.getTeamIds()).containsExactly(a.getId());
    }

    @Test
    @DisplayName("H4: yönetici yeniden eşitlemesi kilitsiz kullanıcının üyeliğini AD ile BİREBİR eşitler (AD boş → takımsız)")
    void adminResync_emptyAd_removesAllUnlockedMemberships() {
        Team a = team("Takım A");
        AppUser u = existing("KULLANICI_X", "100003", a.getId());   // kaynaksız (eski) üyelik

        LdapProvisioningService.SyncResult r = service.resyncFromAd("kullanici_x", Map.of("cn", "100003"));

        assertThat(r.teamsBefore()).containsExactly(a.getId());
        assertThat(r.teamsAfter()).isEmpty();
        assertThat(r.user().getTeamId()).isNull();
        verify(auditService, never()).recordSystemEvent(eq("USER_LDAP_SYNC"), any(), any(), any());  // denetimi controller yazar
    }

    @Test
    @DisplayName("H4: takım KİLİTLİ (elle düzenlenmiş) kullanıcıya yeniden eşitleme de dokunmaz")
    void adminResync_lockedTeams_untouched() {
        Team a = team("Takım A");
        AppUser u = existing("KULLANICI_X", "100003", a.getId());
        u.setTeamLocked(true);

        LdapProvisioningService.SyncResult r = service.resyncFromAd("kullanici_x",
                Map.of("cn", "100003", "memberOf", group("Takım B")));

        assertThat(r.teamsAfter()).containsExactly(a.getId());
    }

    @Test
    @DisplayName("H4: astın girişinde BAYAT müdür kaydı AD'den tazelenir — AD'nin artık desteklemediği LDAP üyeliği düşer")
    void staleManager_refreshedOnSubordinateLogin() {
        Team t = team("Takım A");
        AppUser x = existing("KULLANICI_X", "100003", t.getId());
        sources.put(x.getId(), t.getId(), UserTeamSource.LDAP_COMPANY, "company=ESKI-Takım A");
        x.setUpdatedAt(ISO.format(Instant.now().minusSeconds(3 * 86_400)));   // 3 gün önce
        when(directory.findOne("cn", "100003")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "kullanici_x", "cn", "100003", "manager", dn("100004"))));   // artık takımı yok

        service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002",
                "extensionAttribute4", dn("100003"), "memberOf", group("Takım A")));

        assertThat(x.getTeamIds()).isEmpty();
        assertThat(x.getManagerSicil()).isEqualTo("100004");
    }

    @Test
    @DisplayName("H4: taze müdür kaydı için AD'ye GİDİLMEZ (giriş başına ek sorgu yok)")
    void freshManager_notRequeried() {
        existing("KULLANICI_X", "100003");   // updatedAt = şimdi

        service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002", "extensionAttribute4", dn("100003")));

        verify(directory, never()).findOne("cn", "100003");
    }

    @Test
    @DisplayName("H4: AD'deki cn=<sicil> kaydı BAŞKA hesapsa müdür tazelenmez (yanlış kişiyi yazmaz)")
    void staleManager_differentAccount_notApplied() {
        Team t = team("Takım A");
        AppUser x = existing("KULLANICI_X", "100003", t.getId());
        x.setUpdatedAt(ISO.format(Instant.now().minusSeconds(3 * 86_400)));
        when(directory.findOne("cn", "100003")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "baska_hesap", "cn", "100003")));

        service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002", "extensionAttribute4", dn("100003")));

        assertThat(x.getTeamIds()).containsExactly(t.getId());
        assertThat(usersByName).doesNotContainKey("BASKA_HESAP");
    }

    // ── manager_id ↔ manager_sicil tutarlılığı ─────────────────────────────────────────────

    @Test
    @DisplayName("Müdür değişti ve yeni sicil çözülemiyor → manager_id ESKİ müdürde KALMAZ (null)")
    void unresolvableNewManager_clearsStaleManagerId() {
        AppUser oldMgr = existing("ESKI_MUDUR", "100050");
        AppUser y = existing("KULLANICI_Y", "100002");
        y.setManagerSicil("100050");
        y.setManagerId(oldMgr.getId());

        AppUser out = service.provisionFromAd("kullanici_y", "CN=y",
                Map.of("cn", "100002", "extensionAttribute4", dn("100099")));   // DB'de de AD'de de yok

        assertThat(out.getManagerSicil()).isEqualTo("100099");
        assertThat(out.getManagerId()).isNull();
    }

    @Test
    @DisplayName("AD'de müdür niteliği kalmadıysa manager_sicil ve manager_id temizlenir")
    void noManagerInAd_clearsBoth() {
        AppUser oldMgr = existing("ESKI_MUDUR", "100050");
        AppUser y = existing("KULLANICI_Y", "100002");
        y.setManagerSicil("100050");
        y.setManagerId(oldMgr.getId());

        AppUser out = service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002"));

        assertThat(out.getManagerSicil()).isNull();
        assertThat(out.getManagerId()).isNull();
    }

    // ── Hipotez 6: sicil bağı ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("H6: aynı sicilli İKİ aktif LDAP kullanıcısı → istisna YOK, bağ kurulmaz (yanlış kişiye bağlamaz)")
    void duplicateManagerSicil_noExceptionNoBinding() {
        existing("KOPYA_BIR", "100003");
        existing("KOPYA_IKI", "100003");

        AppUser out = service.provisionFromAd("kullanici_y", "CN=y",
                Map.of("cn", "100002", "extensionAttribute4", dn("100003")));

        assertThat(out.getManagerSicil()).isEqualTo("100003");
        assertThat(out.getManagerId()).isNull();
    }

    // ── Hipotez 5: müdür niteliği sırası ayarlanabilir ─────────────────────────────────────

    @Test
    @DisplayName("H5: ea4 bayat, manager DN güncel → ayarla sıra çevrilince manager kazanır; varsayılan ea4")
    void managerAttributeOrder_isConfigurable() {
        existing("MUDUR_ESKI", "100001");
        existing("MUDUR_YENI", "100002");
        Map<String, Object> attrs = Map.of("cn", "100010",
                "extensionAttribute4", dn("100001"), "manager", dn("100002"));

        assertThat(service.provisionFromAd("u1", "CN=u1", attrs).getManagerSicil()).isEqualTo("100001");

        settings.put(LdapProvisioningService.MANAGER_ATTRS_KEY, "manager,extensionAttribute4");
        AppUser out = service.provisionFromAd("u1", "CN=u1", attrs);
        assertThat(out.getManagerSicil()).isEqualTo("100002");
        assertThat(out.getManagerId()).isEqualTo(usersByName.get("MUDUR_YENI").getId());
    }

    @Test
    @DisplayName("H5: geçersiz nitelik adları (filtre enjeksiyonu / yazım hatası) atlanır; hepsi geçersizse varsayılan")
    void managerAttributeOrder_rejectsInvalidNames() {
        settings.put(LdapProvisioningService.MANAGER_ATTRS_KEY, "(cn=*),manager");
        assertThat(service.managerAttributeOrder()).containsExactly("manager");
        settings.put(LdapProvisioningService.MANAGER_ATTRS_KEY, "*,=");
        assertThat(service.managerAttributeOrder()).containsExactly("extensionAttribute4", "manager");
    }

    @Test
    @DisplayName("H5 tanılama: nitelik başına aday sicil — ea4 ile manager farklı kişiyi gösteriyorsa ikisi de görünür")
    void managerCandidates_showBothAttributes() {
        Map<String, String> c = LdapProvisioningService.managerCandidates(
                Map.of("extensionAttribute4", "100001", "manager", dn("100002")),
                List.of("extensionAttribute4", "manager"));
        assertThat(c).containsEntry("extensionAttribute4", "100001").containsEntry("manager", "100002");
    }

    // ── Hipotez 3: LDAP → takım eşleme kuralları ───────────────────────────────────────────

    @Test
    @DisplayName("H3: benzer adlı OU ('ScrumGroupsArchive', 'OldScrumGroups') takım sayılmaz — RDN tam eşleşme")
    void lookalikeOu_isNotATeam() {
        List<LdapProvisioningService.DerivedTeam> d = LdapProvisioningService.deriveTeams(Map.of("memberOf", List.of(
                "CN=Takım A,OU=ScrumGroupsArchive,DC=example,DC=com",
                "CN=Takım B,OU=OldScrumGroups,DC=example,DC=com",
                "CN=Takım C,ou = scrumgroups ,DC=example,DC=com")));
        assertThat(d).extracting(LdapProvisioningService.DerivedTeam::name).containsExactly("Takım C");
        assertThat(LdapProvisioningService.ignoredGroups(Map.of("memberOf",
                "CN=Takım A,OU=ScrumGroupsArchive,DC=example,DC=com")))
                .extracting(LdapProvisioningService.IgnoredGroup::reason).containsExactly("NOT_TEAM_OU");
    }

    @Test
    @DisplayName("H3: kaynak etiketi — grup LDAP_GROUP (CN), grup yokken company LDAP_COMPANY; boş değer takım üretmez")
    void derivedTeams_carrySource() {
        assertThat(LdapProvisioningService.deriveTeams(Map.of("memberOf", group("Takım A"), "company", "UZMAN-Takım Z")))
                .extracting(LdapProvisioningService.DerivedTeam::source).containsExactly(UserTeamSource.LDAP_GROUP);
        assertThat(LdapProvisioningService.deriveTeams(Map.of("company", "UZMAN-Takım Z")))
                .extracting(LdapProvisioningService.DerivedTeam::source).containsExactly(UserTeamSource.LDAP_COMPANY);
        assertThat(LdapProvisioningService.deriveTeams(Map.of("company", "UZMAN- , ", "memberOf", " "))).isEmpty();
    }

    @Test
    @DisplayName("H3: 'İ' içeren DN'de CN kayması yok (varsayılan yerel İngilizceyken de)")
    void cnOf_turkishDottedCapitalI_doesNotShiftIndex() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertThat(LdapProvisioningService.cnOf("OU=İİ,CN=100001,DC=example,DC=com")).isEqualTo("100001");
        } finally {
            Locale.setDefault(before);
        }
    }

    // ── Kaynak izi + denetim ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Girişte türetilen üyeliğin kaynağı yazılır; çıkan takımın izi silinir")
    void login_recordsSources() {
        Team old = team("Eski Takım");
        AppUser u = existing("KULLANICI_Y", "100002", old.getId());
        sources.put(u.getId(), old.getId(), UserTeamSource.LDAP_GROUP, "Eski Takım");

        AppUser out = service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002", "memberOf", group("Takım A")));

        Long a = teamsByName.get("Takım A").getId();
        assertThat(out.getTeamIds()).containsExactly(a);
        assertThat(sources.get(u.getId(), a).getSource()).isEqualTo(UserTeamSource.LDAP_GROUP);
        assertThat(sources.get(u.getId(), a).getDetail()).isEqualTo("Takım A");
        assertThat(sources.get(u.getId(), old.getId())).isNull();
    }

    @Test
    @DisplayName("Tek pod başarımı: küme değişmediyse üyelik koleksiyonu yeniden yazılmaz, aynı kaynak izi yeniden kaydedilmez")
    void unchangedMembership_noRewrite() {
        AppUser u = existing("KULLANICI_Y", "100002");
        Map<String, Object> attrs = Map.of("cn", "100002", "memberOf", group("Takım A"));
        service.provisionFromAd("kullanici_y", "CN=y", attrs);          // ilk giriş: üyelik + iz yazılır
        java.util.Set<Long> firstSet = u.getTeamIds();
        org.mockito.Mockito.clearInvocations(sources.repo);

        service.provisionFromAd("kullanici_y", "CN=y", attrs);          // aynı AD

        assertThat(u.getTeamIds()).isSameAs(firstSet);
        verify(sources.repo, never()).save(any(UserTeamSource.class));
    }

    @Test
    @DisplayName("LDAP kaynaklı üyelik/müdür DEĞİŞİKLİĞİ denetime yazılır (USER_LDAP_SYNC); değişiklik yoksa yazılmaz")
    void ldapChange_isAudited_onlyWhenSomethingChanged() {
        Team old = team("Eski Takım");
        AppUser u = existing("KULLANICI_Y", "100002", old.getId());
        Map<String, Object> attrs = Map.of("cn", "100002", "memberOf", group("Takım A"));

        service.provisionFromAd("kullanici_y", "CN=y", attrs);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordSystemEvent(eq("USER_LDAP_SYNC"), eq("USER"), eq(String.valueOf(u.getId())), detail.capture());
        assertThat(detail.getValue()).contains("\"via\":\"LOGIN\"").contains("removed");

        org.mockito.Mockito.clearInvocations(auditService);
        service.provisionFromAd("kullanici_y", "CN=y", attrs);   // aynı AD → değişiklik yok
        verify(auditService, never()).recordSystemEvent(eq("USER_LDAP_SYNC"), any(), any(), any());
    }

    @Test
    @DisplayName("Özyinelemeli müdür kaydı oluşturulunca denetim 'hangi astın girişiyle' bilgisini taşır")
    void recursiveManagerCreation_auditCarriesVia() {
        when(directory.findOne("cn", "100003")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "kullanici_x", "cn", "100003")));

        service.provisionFromAd("kullanici_y", "CN=y", Map.of("cn", "100002", "extensionAttribute4", dn("100003")));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce())
                .recordSystemEvent(eq("USER_LDAP_SYNC"), eq("USER"), any(), detail.capture());
        assertThat(detail.getAllValues()).anyMatch(d -> d.contains("MANAGER_OF:KULLANICI_Y") && d.contains("\"created\":true"));
    }
}
