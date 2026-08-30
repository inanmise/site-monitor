package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapProvisioningServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock LdapDirectoryService directory;
    @Mock EscalationContactRepository contactRepo;
    @Mock AppSettingsService appSettings;
    private LdapProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new LdapProvisioningService(userRepo, teamRepo, directory, contactRepo, appSettings);
        // Varsayılan davranış: otomatik müdür-kontağı ekleme KAPALI (üretim varsayılanıyla aynı).
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        AtomicLong userSeq = new AtomicLong(0);
        AtomicLong teamSeq = new AtomicLong(0);
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(userSeq.incrementAndGet());
            return u;
        });
        when(teamRepo.save(any(Team.class))).thenAnswer(inv -> {
            Team t = inv.getArgument(0);
            if (t.getId() == null) t.setId(teamSeq.incrementAndGet());
            return t;
        });
        when(userRepo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(teamRepo.findByName(anyString())).thenReturn(Optional.empty());
        when(directory.groupMail(anyString())).thenReturn(Optional.empty());
        when(directory.findOne(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("PRODUCT OWNER → orgRole PO + systemRole TEAM_ADMIN; all profile fields mapped")
    void mapsAllFields_poBecomesTeamAdmin() {
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy-darkside@example.com"));
        Map<String, Object> attrs = Map.ofEntries(
                Map.entry("mail", "erdi@example.com"),
                Map.entry("cn", "63999"),
                Map.entry("givenName", "Erdi"),
                Map.entry("sn", "İnanmış"),
                Map.entry("displayName", "Erdi İnanmış"),
                Map.entry("title", "Yazılım Mühendisi"),
                Map.entry("mobile", "+90 (532) 210 2594"),
                Map.entry("department", "TEKNOLOJİ"),
                Map.entry("description", "Uzman"),
                Map.entry("company", "PRODUCT OWNER"),
                Map.entry("extensionAttribute5", "8861;TEKN.MİM. VE TEMEL BANK. SERVİS YÖNETİMİ"),
                Map.entry("memberOf", List.of(
                        "CN=Takim A,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com",
                        "CN=dagitim-listesi,OU=DistributionGroups,OU=Groups,OU=Corp,DC=example,DC=com")));

        AppUser u = service.provisionFromAd("n34567", "CN=n34567,OU=Staff,DC=example,DC=com", attrs);

        assertThat(u.getUsername()).isEqualTo("N34567");   // username HER ZAMAN büyük harf
        assertThat(u.getEmail()).isEqualTo("erdi@example.com");
        assertThat(u.getEmployeeId()).isEqualTo("63999");          // cn = sicil
        assertThat(u.getFirstName()).isEqualTo("Erdi");
        assertThat(u.getLastName()).isEqualTo("İnanmış");
        assertThat(u.getDisplayName()).isEqualTo("Erdi İnanmış");
        assertThat(u.getTitle()).isEqualTo("Yazılım Mühendisi");
        assertThat(u.getPhone()).isEqualTo("+90 (532) 210 2594");
        assertThat(u.getDepartment()).isEqualTo("TEKNOLOJİ");
        assertThat(u.getCompanyLevel()).isEqualTo("Uzman");
        assertThat(u.getOrgRole()).isEqualTo("PO");
        assertThat(u.getSystemRole()).isEqualTo("TEAM_ADMIN");
        assertThat(u.getMudurlukId()).isEqualTo(8861L);
        assertThat(u.getMudurlukName()).isEqualTo("TEKN.MİM. VE TEMEL BANK. SERVİS YÖNETİMİ");
        assertThat(u.getAuthSource()).isEqualTo("LDAP");
        assertThat(u.getPasswordHash()).isNull();
        assertThat(u.getTeamId()).isNotNull();                      // Takim A team created
    }

    @Test
    @DisplayName("team extracted from ScrumGroups memberOf CN, created with group mail; PO becomes leader")
    void extractsTeamFromScrumGroups() {
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy-darkside@example.com"));
        org.mockito.ArgumentCaptor<Team> teamCap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "70001", "company", "PRODUCT OWNER",
                "memberOf", "CN=Takim A,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com");

        service.provisionFromAd("po1", "CN=po1,DC=example,DC=com", attrs);

        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(teamCap.capture());
        Team created = teamCap.getAllValues().stream()
                .filter(t -> "Takim A".equals(t.getName())).findFirst().orElseThrow();
        assertThat(created.getName()).isEqualTo("Takim A");
        assertThat(created.getEmail()).isEqualTo("sy-darkside@example.com");
        assertThat(created.getLeaderId()).isNotNull(); // PO set as leader
    }

    @Test
    @DisplayName("non-PO → USER; manager resolved from extensionAttribute4 and provisioned if missing")
    void regularUser_resolvesManager() {
        // Manager not in DB → looked up in AD by cn and provisioned.
        when(directory.findOne("cn", "99999")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "mgr1", "displayName", "Müdür Bey", "cn", "99999")));
        Map<String, Object> attrs = Map.of(
                "cn", "80002",
                "displayName", "Normal User",
                "extensionAttribute4", "CN=99999,OU=Staff,OU=Corp,DC=example,DC=com");

        AppUser u = service.provisionFromAd("usr1", "CN=usr1,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("USER");
        assertThat(u.getOrgRole()).isEqualTo("TECH");   // PO/D6/D7 değil → TECH (eski davranış: null)
        assertThat(u.getManagerSicil()).isEqualTo("99999");
        assertThat(u.getManagerId()).isNotNull();   // manager provisioned + linked
    }

    @Test
    @DisplayName("Faz 3b: recursively-provisioned manager (müdür) → systemRole ADMIN")
    void recursiveManager_becomesAdmin() {
        when(directory.findOne("cn", "99999")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "mgr1", "displayName", "Müdür Bey", "cn", "99999")));
        Map<String, Object> attrs = Map.of(
                "cn", "80002",
                "displayName", "Normal User",
                "extensionAttribute4", "CN=99999,OU=Staff,OU=Corp,DC=example,DC=com");

        service.provisionFromAd("usr1", "CN=usr1,DC=example,DC=com", attrs);

        org.mockito.ArgumentCaptor<AppUser> cap = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        AppUser manager = cap.getAllValues().stream()
                .filter(x -> "MGR1".equals(x.getUsername())).findFirst().orElseThrow();   // normalize → BÜYÜK
        assertThat(manager.getSystemRole()).isEqualTo("TEAM_ADMIN");  // müdür = takım kapsamlı yönetici
    }

    @Test
    @DisplayName("PO + müdür görünümü → TEAM_ADMIN; iki yol da aynı role çıkar, ADMIN yok")
    void poWhoIsAlsoManager_staysTeamAdmin() {
        when(userRepo.existsByManagerId(anyLong())).thenReturn(true);   // kendisine bağlı çalışan var
        Map<String, Object> attrs = Map.of(
                "cn", "90010", "displayName", "PO Boss", "company", "PRODUCT OWNER-Takim A");

        AppUser u = service.provisionFromAd("poboss", "CN=poboss,DC=example,DC=com", attrs);

        assertThat(u.getOrgRole()).isEqualTo("PO");
        assertThat(u.getSystemRole()).isEqualTo("TEAM_ADMIN");   // ADMIN'e YÜKSELMEZ
    }

    @Test
    @DisplayName("Elle ADMIN yapılmış PO ADMIN kalır — LDAP manuel yükseltmeyi geri almaz")
    void manuallyPromotedAdminPo_keepsAdmin() {
        AppUser existing = new AppUser();
        existing.setId(42L);
        existing.setUsername("POADMIN");
        existing.setSystemRole("ADMIN");          // admin panelinden elle verilmiş
        existing.setRoleLocked(false);
        when(userRepo.findByUsername("POADMIN")).thenReturn(Optional.of(existing));
        Map<String, Object> attrs = Map.of("cn", "90011", "company", "PRODUCT OWNER");

        AppUser u = service.provisionFromAd("poadmin", "CN=poadmin,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("ADMIN");
        assertThat(u.getOrgRole()).isEqualTo("PO");   // org rol yine de AD'den tazelenir
    }

    @Test
    @DisplayName("Rolü KİLİTLİ (role_locked) PO ADMIN kalır — manuel atama LDAP'tan ezilmez")
    void roleLockedAdminPo_keepsAdmin() {
        AppUser existing = new AppUser();
        existing.setId(43L);
        existing.setUsername("POLOCKED");
        existing.setSystemRole("ADMIN");
        existing.setRoleLocked(true);             // admin bilerek kilitledi
        when(userRepo.findByUsername("POLOCKED")).thenReturn(Optional.of(existing));
        Map<String, Object> attrs = Map.of("cn", "90012", "company", "PRODUCT OWNER");

        AppUser u = service.provisionFromAd("polocked", "CN=polocked,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("ADMIN");
        assertThat(u.getOrgRole()).isEqualTo("PO");   // org rol yine de tazelenir (orgRoleLocked ayrı)
    }

    @Test
    @DisplayName("AUDIT (denetçi) rolündeki PO korunur — denetim rolü LDAP'tan düşürülmez")
    void auditPo_keepsAudit() {
        AppUser existing = new AppUser();
        existing.setId(44L);
        existing.setUsername("POAUDIT");
        existing.setSystemRole("AUDIT");
        existing.setRoleLocked(false);
        when(userRepo.findByUsername("POAUDIT")).thenReturn(Optional.of(existing));
        Map<String, Object> attrs = Map.of("cn", "90013", "company", "PRODUCT OWNER");

        AppUser u = service.provisionFromAd("poaudit", "CN=poaudit,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("AUDIT");
    }

    @Test
    @DisplayName("Bağlı çalışanı olan kullanıcı (müdür) → TEAM_ADMIN; LDAP artık ADMIN vermez")
    void userWithSubordinate_becomesTeamAdmin() {
        when(userRepo.existsByManagerId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        Map<String, Object> attrs = Map.of("cn", "90003", "displayName", "Boss");

        AppUser u = service.provisionFromAd("boss1", "CN=boss1,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("TEAM_ADMIN");
    }

    @Test
    @DisplayName("ScrumGroups: '...Onayci' grubu takım sayılmaz; düz grup takım adı + mail o DN'den")
    void teamFromScrumGroups_skipsApproverGroup() {
        // Onayci grubunun maili yanlışlıkla seçilmesin diye yalnız düz grubun DN'ine mail ver.
        String teamDn = "CN=Takim B,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com";
        when(directory.groupMail(teamDn)).thenReturn(Optional.of("sy-dijitalbankacilik@example.com"));
        org.mockito.ArgumentCaptor<Team> teamCap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "64954", "company", "PRODUCT OWNER",
                "memberOf", List.of(
                        "CN=Takim B_Onayci,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com",
                        teamDn));

        service.provisionFromAd("n34567", "CN=n34567,DC=example,DC=com", attrs);

        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(teamCap.capture());
        Team created = teamCap.getAllValues().stream()
                .filter(t -> t.getName() != null && !t.getName().toLowerCase().contains("onayci"))
                .findFirst().orElseThrow();
        assertThat(created.getName()).isEqualTo("Takim B");
        assertThat(created.getEmail()).isEqualTo("sy-dijitalbankacilik@example.com");
        // "...Onayci" adıyla hiçbir takım oluşturulmamalı.
        assertThat(teamCap.getAllValues()).noneMatch(t ->
                t.getName() != null && t.getName().toLowerCase().contains("onayci"));
    }

    @Test
    @DisplayName("isApproverCn: yalnız Onayci/Onaycı ile bitenler true")
    void isApproverCnHelper() {
        assertThat(LdapProvisioningService.isApproverCn("Takim B_Onayci")).isTrue();
        assertThat(LdapProvisioningService.isApproverCn("Takim B Onaycı")).isTrue();
        assertThat(LdapProvisioningService.isApproverCn("Takim B")).isFalse();
        assertThat(LdapProvisioningService.isApproverCn(null)).isFalse();
    }

    @Test
    @DisplayName("cnOf extracts the CN value from a DN")
    void cnOfHelper() {
        assertThat(LdapProvisioningService.cnOf("CN=99999,OU=Staff,DC=example,DC=com")).isEqualTo("99999");
        assertThat(LdapProvisioningService.cnOf("CN=Takim A,OU=ScrumGroups,DC=akb")).isEqualTo("Takim A");
        assertThat(LdapProvisioningService.cnOf(null)).isNull();
    }

    @Test
    @DisplayName("çoklu ScrumGroup → tüm takımlar üyelik olur; birincil = memberOf'taki ilk grup")
    void collectsAllScrumGroupsIntoMembership() {
        Map<String, Object> attrs = Map.of(
                "cn", "70010",
                "memberOf", List.of(
                        "CN=SY-Alpha,OU=ScrumGroups,DC=example,DC=com",
                        "CN=SY-Beta,OU=ScrumGroups,DC=example,DC=com"));

        AppUser u = service.provisionFromAd("multi1", "CN=multi1,DC=akb", attrs);

        // teamSeq: ilk oluşturulan (SY-Alpha)=1, ikinci (SY-Beta)=2 — sıra korunur, birincil=1
        assertThat(u.getTeamIds()).containsExactly(1L, 2L);
        assertThat(u.getTeamId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("company fallback: ScrumGroup yokken company'den tek takım çıkarılır")
    void companyFallback_whenNoScrumGroup() {
        org.mockito.ArgumentCaptor<Team> cap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "80004", "company", "YAZILIM UZMANI-SY-MevduatMuhasebeSigorta");

        AppUser u = service.provisionFromAd("yaz1", "CN=yaz1,DC=akb", attrs);

        assertThat(u.getTeamIds()).hasSize(1);
        assertThat(u.getTeamId()).isNotNull();
        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(t -> "SY-MevduatMuhasebeSigorta".equals(t.getName()));
    }

    @Test
    @DisplayName("company fallback: birden çok takım (PO) → her takıma üye + PO her lidersiz takımın lideri")
    void companyFallback_multipleTeams() {
        org.mockito.ArgumentCaptor<Team> cap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "80005",
                "company", "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Takım A Mobil Servis");

        AppUser u = service.provisionFromAd("po3", "CN=po3,DC=akb", attrs);

        assertThat(u.getTeamIds()).hasSize(2);
        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        java.util.List<String> names = cap.getAllValues().stream().map(Team::getName).toList();
        assertThat(names).contains("SY-MevduatMuhasebeSigorta", "SY-Takım A Mobil Servis");
        // PO her iki takımın da lideri olmalı (lider boştu)
        assertThat(cap.getAllValues())
                .filteredOn(t -> t.getName() != null && t.getName().startsWith("SY-"))
                .allMatch(t -> u.getId().equals(t.getLeaderId()));
    }

    @Test
    @DisplayName("company fallback: ScrumGroup varsa company KULLANILMAZ")
    void companyFallback_notUsedWhenScrumGroupPresent() {
        org.mockito.ArgumentCaptor<Team> cap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "80006",
                "company", "YAZILIM UZMANI-SY-ShouldNotAppear",
                "memberOf", "CN=SY-RealTeam,OU=ScrumGroups,DC=example,DC=com");

        AppUser u = service.provisionFromAd("u6", "CN=u6,DC=akb", attrs);

        assertThat(u.getTeamIds()).hasSize(1);
        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        java.util.List<String> names = cap.getAllValues().stream().map(Team::getName).toList();
        assertThat(names).contains("SY-RealTeam");
        assertThat(names).doesNotContain("SY-ShouldNotAppear");
    }

    @Test
    @DisplayName("companyTeamNames: rol önekini at, yalnız ilk tireden böl, virgülle ayır")
    void companyTeamNamesHelper() {
        assertThat(LdapProvisioningService.companyTeamNames(
                "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Takım A Mobil Servis"))
                .containsExactly("SY-MevduatMuhasebeSigorta", "SY-Takım A Mobil Servis");
        assertThat(LdapProvisioningService.companyTeamNames("YAZILIM UZMANI-SY-MevduatMuhasebeSigorta"))
                .containsExactly("SY-MevduatMuhasebeSigorta");
        assertThat(LdapProvisioningService.companyTeamNames("SCRUM MASTER-SY-Takım A Mobil Servis"))
                .containsExactly("SY-Takım A Mobil Servis");
        assertThat(LdapProvisioningService.companyTeamNames("PRODUCT OWNER")).isEmpty();  // tire yok
        assertThat(LdapProvisioningService.companyTeamNames(null)).isEmpty();
    }

    @Test
    @DisplayName("provision: ayar AÇIKKEN takım + müdür varsa müdür otomatik MANAGER eskalasyon kontağı (HIGH) olur")
    void provision_autoCreatesManagerEscalationContact() {
        when(appSettings.getBoolean("site.monitor.escalation.auto-add-managers", false)).thenReturn(true);
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy@example.com"));
        // Müdür DB'de mevcut (employeeId=63535), e-postalı → resolveManagerLink onu bulur
        AppUser mgr = new AppUser();
        mgr.setId(700L); mgr.setUsername("mgr1"); mgr.setEmployeeId("99999");
        mgr.setActive(true); mgr.setEmail("mudur@example.com"); mgr.setDisplayName("Ali Müdür");
        when(userRepo.findByEmployeeId("99999")).thenReturn(Optional.of(mgr));
        when(userRepo.findById(700L)).thenReturn(Optional.of(mgr));
        when(contactRepo.findByTeamIdOrderByRoleAsc(anyLong())).thenReturn(List.of());

        Map<String, Object> attrs = Map.of(
                "cn", "80002", "displayName", "Üye",
                "extensionAttribute4", "CN=99999,OU=Staff,DC=example,DC=com",
                "memberOf", "CN=Takim A,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com");

        service.provisionFromAd("uye1", "CN=uye1,DC=example,DC=com", attrs);

        org.mockito.ArgumentCaptor<EscalationContact> cap = org.mockito.ArgumentCaptor.forClass(EscalationContact.class);
        org.mockito.Mockito.verify(contactRepo).save(cap.capture());
        EscalationContact c = cap.getValue();
        assertThat(c.getRole()).isEqualTo("MANAGER");
        assertThat(c.getMinAlertLevel()).isEqualTo("HIGH");
        assertThat(c.getEmail()).isEqualTo("mudur@example.com");
        assertThat(c.getActive()).isTrue();
        assertThat(c.getUserId()).isEqualTo(700L);
    }

    @Test
    @DisplayName("provision: ayar KAPALIYKEN (varsayılan) müdür kontağı OLUŞMAZ; manager bağlantısı yine kurulur")
    void provision_defaultOff_noManagerContactCreated() {
        // appSettings varsayılanı fallback döner → false (üretim varsayılanı)
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy@example.com"));
        AppUser mgr = new AppUser();
        mgr.setId(700L); mgr.setUsername("mgr1"); mgr.setEmployeeId("99999");
        mgr.setActive(true); mgr.setEmail("mudur@example.com");
        when(userRepo.findByEmployeeId("99999")).thenReturn(Optional.of(mgr));
        when(userRepo.findById(700L)).thenReturn(Optional.of(mgr));

        Map<String, Object> attrs = Map.of(
                "cn", "80002", "displayName", "Üye",
                "extensionAttribute4", "CN=99999,OU=Staff,DC=example,DC=com",
                "memberOf", "CN=Takim A,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com");

        AppUser saved = service.provisionFromAd("uye1", "CN=uye1,DC=example,DC=com", attrs);

        org.mockito.Mockito.verify(contactRepo, org.mockito.Mockito.never()).save(any(EscalationContact.class));
        assertThat(saved.getManagerId()).isEqualTo(700L);   // ilişki yine kaydedilir; yalnız kontak eklenmez
    }

    @Test
    @DisplayName("provision: ayar AÇIKKEN aynı müdür zaten MANAGER kontağıysa tekrar eklenmez")
    void provision_skipsDuplicateManagerContact() {
        when(appSettings.getBoolean("site.monitor.escalation.auto-add-managers", false)).thenReturn(true);
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy@example.com"));
        AppUser mgr = new AppUser();
        mgr.setId(700L); mgr.setUsername("mgr1"); mgr.setEmployeeId("99999");
        mgr.setActive(true); mgr.setEmail("mudur@example.com");
        when(userRepo.findByEmployeeId("99999")).thenReturn(Optional.of(mgr));
        when(userRepo.findById(700L)).thenReturn(Optional.of(mgr));
        EscalationContact existing = new EscalationContact();
        existing.setTeamId(1L); existing.setRole("MANAGER"); existing.setEmail("mudur@example.com");
        when(contactRepo.findByTeamIdOrderByRoleAsc(anyLong())).thenReturn(List.of(existing));

        Map<String, Object> attrs = Map.of(
                "cn", "80002", "displayName", "Üye",
                "extensionAttribute4", "CN=99999,OU=Staff,DC=example,DC=com",
                "memberOf", "CN=Takim A,OU=ScrumGroups,OU=Staff,OU=Corp,DC=example,DC=com");

        service.provisionFromAd("uye1", "CN=uye1,DC=example,DC=com", attrs);

        org.mockito.Mockito.verify(contactRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("role_locked kullanıcının rolü LDAP girişinde EZİLMEZ (manuel TEAM_ADMIN korunur)")
    void lockedRole_notOverwrittenOnLogin() {
        AppUser existing = new AppUser();
        existing.setId(500L);
        existing.setUsername("LOCKEDUSER");
        existing.setSystemRole("TEAM_ADMIN");    // admin elle yükseltti
        existing.setRoleLocked(true);            // + kilitledi
        existing.setAuthSource("LDAP");
        when(userRepo.findByUsername("LOCKEDUSER")).thenReturn(Optional.of(existing));
        // AD normalde bu kişiyi USER yapardı (PO/müdür değil)
        Map<String, Object> attrs = Map.of("cn", "12345", "displayName", "Locked User");

        AppUser u = service.provisionFromAd("lockeduser", "CN=lockeduser,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("TEAM_ADMIN");   // EZİLMEDİ
        assertThat(u.getRoleLocked()).isTrue();
    }

    @Test
    @DisplayName("role_locked yoksa (null) rol AD'den güncellenir — kilitsiz eski davranış korunur")
    void unlockedRole_stillUpdatedFromAd() {
        AppUser existing = new AppUser();
        existing.setId(501L);
        existing.setUsername("ADUSER");
        existing.setSystemRole("TEAM_ADMIN");    // AD-PO iken olmuş, artık PO değil
        existing.setRoleLocked(null);            // kilit yok → AD yönetir
        existing.setAuthSource("LDAP");
        when(userRepo.findByUsername("ADUSER")).thenReturn(Optional.of(existing));
        Map<String, Object> attrs = Map.of("cn", "12346", "displayName", "AD User");   // PO değil → USER

        AppUser u = service.provisionFromAd("aduser", "CN=aduser,DC=example,DC=com", attrs);

        assertThat(u.getSystemRole()).isEqualTo("USER");         // kilitsiz → AD davranışı
    }

    @Test
    @DisplayName("org_role türetme: seviye D6 (description) → orgRole MANAGER (PO değil)")
    void level_d6_becomesManager() {
        Map<String, Object> attrs = Map.of("cn", "11111", "displayName", "D6 User", "description", "D6");
        AppUser u = service.provisionFromAd("d6user", "CN=d6user,DC=example,DC=com", attrs);
        assertThat(u.getOrgRole()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("org_role türetme: seviye D7 → orgRole BOLUM_BASKANI (PO değil)")
    void level_d7_becomesBolumBaskani() {
        Map<String, Object> attrs = Map.of("cn", "22222", "displayName", "D7 User", "description", "D7");
        AppUser u = service.provisionFromAd("d7user", "CN=d7user,DC=example,DC=com", attrs);
        assertThat(u.getOrgRole()).isEqualTo("BOLUM_BASKANI");
    }

    @Test
    @DisplayName("org_role türetme: PO önceliği — company PRODUCT OWNER + seviye D6 → PO (D6'yı ezmez)")
    void po_takesPrecedenceOverLevel() {
        Map<String, Object> attrs = Map.of("cn", "33333", "displayName", "PO D6",
                "company", "PRODUCT OWNER", "description", "D6");
        AppUser u = service.provisionFromAd("pod6", "CN=pod6,DC=example,DC=com", attrs);
        assertThat(u.getOrgRole()).isEqualTo("PO");
    }

    @Test
    @DisplayName("org_role türetme: PO/D6/D7 değil (ör. D5) → orgRole TECH")
    void otherLevel_becomesTech() {
        Map<String, Object> attrs = Map.of("cn", "44444", "displayName", "D5 User", "description", "D5");
        AppUser u = service.provisionFromAd("d5user", "CN=d5user,DC=example,DC=com", attrs);
        assertThat(u.getOrgRole()).isEqualTo("TECH");
    }

    @Test
    @DisplayName("org_role_locked kullanıcının org rolü LDAP girişinde EZİLMEZ (manuel MANAGER korunur)")
    void lockedOrgRole_notOverwrittenOnLogin() {
        AppUser existing = new AppUser();
        existing.setId(600L);
        existing.setUsername("ORGLOCK");
        existing.setOrgRole("MANAGER");        // admin elle atadı
        existing.setOrgRoleLocked(true);       // + kilitledi
        existing.setAuthSource("LDAP");
        when(userRepo.findByUsername("ORGLOCK")).thenReturn(Optional.of(existing));
        // AD normalde TECH türetirdi (description yok, PO değil)
        Map<String, Object> attrs = Map.of("cn", "55555", "displayName", "Org Locked");

        AppUser u = service.provisionFromAd("orglock", "CN=orglock,DC=example,DC=com", attrs);

        assertThat(u.getOrgRole()).isEqualTo("MANAGER");   // EZİLMEDİ
        assertThat(u.getOrgRoleLocked()).isTrue();
    }
}
