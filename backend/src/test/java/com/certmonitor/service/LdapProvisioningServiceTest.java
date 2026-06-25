package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.TeamRepository;
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
    private LdapProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new LdapProvisioningService(userRepo, teamRepo, directory, contactRepo);
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
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy-darkside@akbank.com"));
        Map<String, Object> attrs = Map.ofEntries(
                Map.entry("mail", "erdi@akbank.com"),
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
                        "CN=SY-DarkSide,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb",
                        "CN=aidatasy,OU=DistributionGroups,OU=Groups,OU=Aknet,DC=aknet,DC=akb")));

        AppUser u = service.provisionFromAd("n64954", "CN=n64954,OU=BTPersonel,DC=aknet,DC=akb", attrs);

        assertThat(u.getUsername()).isEqualTo("N64954");   // username HER ZAMAN büyük harf
        assertThat(u.getEmail()).isEqualTo("erdi@akbank.com");
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
        assertThat(u.getTeamId()).isNotNull();                      // SY-DarkSide team created
    }

    @Test
    @DisplayName("team extracted from ScrumGroups memberOf CN, created with group mail; PO becomes leader")
    void extractsTeamFromScrumGroups() {
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy-darkside@akbank.com"));
        org.mockito.ArgumentCaptor<Team> teamCap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "70001", "company", "PRODUCT OWNER",
                "memberOf", "CN=SY-DarkSide,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb");

        service.provisionFromAd("po1", "CN=po1,DC=aknet,DC=akb", attrs);

        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(teamCap.capture());
        Team created = teamCap.getAllValues().stream()
                .filter(t -> "SY-DarkSide".equals(t.getName())).findFirst().orElseThrow();
        assertThat(created.getName()).isEqualTo("SY-DarkSide");
        assertThat(created.getEmail()).isEqualTo("sy-darkside@akbank.com");
        assertThat(created.getLeaderId()).isNotNull(); // PO set as leader
    }

    @Test
    @DisplayName("non-PO → USER; manager resolved from extensionAttribute4 and provisioned if missing")
    void regularUser_resolvesManager() {
        // Manager not in DB → looked up in AD by cn and provisioned.
        when(directory.findOne("cn", "63535")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "mgr1", "displayName", "Müdür Bey", "cn", "63535")));
        Map<String, Object> attrs = Map.of(
                "cn", "80002",
                "displayName", "Normal User",
                "extensionAttribute4", "CN=63535,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb");

        AppUser u = service.provisionFromAd("usr1", "CN=usr1,DC=aknet,DC=akb", attrs);

        assertThat(u.getSystemRole()).isEqualTo("USER");
        assertThat(u.getOrgRole()).isNull();
        assertThat(u.getManagerSicil()).isEqualTo("63535");
        assertThat(u.getManagerId()).isNotNull();   // manager provisioned + linked
    }

    @Test
    @DisplayName("Faz 3b: recursively-provisioned manager (müdür) → systemRole ADMIN")
    void recursiveManager_becomesAdmin() {
        when(directory.findOne("cn", "63535")).thenReturn(Optional.of(Map.of(
                "sAMAccountName", "mgr1", "displayName", "Müdür Bey", "cn", "63535")));
        Map<String, Object> attrs = Map.of(
                "cn", "80002",
                "displayName", "Normal User",
                "extensionAttribute4", "CN=63535,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb");

        service.provisionFromAd("usr1", "CN=usr1,DC=aknet,DC=akb", attrs);

        org.mockito.ArgumentCaptor<AppUser> cap = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        AppUser manager = cap.getAllValues().stream()
                .filter(x -> "MGR1".equals(x.getUsername())).findFirst().orElseThrow();   // normalize → BÜYÜK
        assertThat(manager.getSystemRole()).isEqualTo("ADMIN");  // müdür = scoped ADMIN
    }

    @Test
    @DisplayName("Faz 3b: a user with a subordinate in DB (existsByManagerId) → ADMIN (müdür)")
    void userWithSubordinate_becomesAdmin() {
        when(userRepo.existsByManagerId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        Map<String, Object> attrs = Map.of("cn", "90003", "displayName", "Boss");

        AppUser u = service.provisionFromAd("boss1", "CN=boss1,DC=aknet,DC=akb", attrs);

        assertThat(u.getSystemRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("ScrumGroups: '...Onayci' grubu takım sayılmaz; düz grup takım adı + mail o DN'den")
    void teamFromScrumGroups_skipsApproverGroup() {
        // Onayci grubunun maili yanlışlıkla seçilmesin diye yalnız düz grubun DN'ine mail ver.
        String teamDn = "CN=SY-Dijital Bankacilik,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb";
        when(directory.groupMail(teamDn)).thenReturn(Optional.of("sy-dijitalbankacilik@akbank.com"));
        org.mockito.ArgumentCaptor<Team> teamCap = org.mockito.ArgumentCaptor.forClass(Team.class);
        Map<String, Object> attrs = Map.of(
                "cn", "64954", "company", "PRODUCT OWNER",
                "memberOf", List.of(
                        "CN=SY-Dijital Bankacilik_Onayci,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb",
                        teamDn));

        service.provisionFromAd("n64954", "CN=n64954,DC=aknet,DC=akb", attrs);

        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(teamCap.capture());
        Team created = teamCap.getAllValues().stream()
                .filter(t -> t.getName() != null && !t.getName().toLowerCase().contains("onayci"))
                .findFirst().orElseThrow();
        assertThat(created.getName()).isEqualTo("SY-Dijital Bankacilik");
        assertThat(created.getEmail()).isEqualTo("sy-dijitalbankacilik@akbank.com");
        // "...Onayci" adıyla hiçbir takım oluşturulmamalı.
        assertThat(teamCap.getAllValues()).noneMatch(t ->
                t.getName() != null && t.getName().toLowerCase().contains("onayci"));
    }

    @Test
    @DisplayName("isApproverCn: yalnız Onayci/Onaycı ile bitenler true")
    void isApproverCnHelper() {
        assertThat(LdapProvisioningService.isApproverCn("SY-Dijital Bankacilik_Onayci")).isTrue();
        assertThat(LdapProvisioningService.isApproverCn("SY-Dijital Bankacilik Onaycı")).isTrue();
        assertThat(LdapProvisioningService.isApproverCn("SY-Dijital Bankacilik")).isFalse();
        assertThat(LdapProvisioningService.isApproverCn(null)).isFalse();
    }

    @Test
    @DisplayName("cnOf extracts the CN value from a DN")
    void cnOfHelper() {
        assertThat(LdapProvisioningService.cnOf("CN=63535,OU=BTPersonel,DC=aknet,DC=akb")).isEqualTo("63535");
        assertThat(LdapProvisioningService.cnOf("CN=SY-DarkSide,OU=ScrumGroups,DC=akb")).isEqualTo("SY-DarkSide");
        assertThat(LdapProvisioningService.cnOf(null)).isNull();
    }

    @Test
    @DisplayName("çoklu ScrumGroup → tüm takımlar üyelik olur; birincil = memberOf'taki ilk grup")
    void collectsAllScrumGroupsIntoMembership() {
        Map<String, Object> attrs = Map.of(
                "cn", "70010",
                "memberOf", List.of(
                        "CN=SY-Alpha,OU=ScrumGroups,DC=aknet,DC=akb",
                        "CN=SY-Beta,OU=ScrumGroups,DC=aknet,DC=akb"));

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
                "company", "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Dijital Mobil Servis");

        AppUser u = service.provisionFromAd("po3", "CN=po3,DC=akb", attrs);

        assertThat(u.getTeamIds()).hasSize(2);
        org.mockito.Mockito.verify(teamRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        java.util.List<String> names = cap.getAllValues().stream().map(Team::getName).toList();
        assertThat(names).contains("SY-MevduatMuhasebeSigorta", "SY-Dijital Mobil Servis");
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
                "memberOf", "CN=SY-RealTeam,OU=ScrumGroups,DC=aknet,DC=akb");

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
                "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Dijital Mobil Servis"))
                .containsExactly("SY-MevduatMuhasebeSigorta", "SY-Dijital Mobil Servis");
        assertThat(LdapProvisioningService.companyTeamNames("YAZILIM UZMANI-SY-MevduatMuhasebeSigorta"))
                .containsExactly("SY-MevduatMuhasebeSigorta");
        assertThat(LdapProvisioningService.companyTeamNames("SCRUM MASTER-SY-Dijital Mobil Servis"))
                .containsExactly("SY-Dijital Mobil Servis");
        assertThat(LdapProvisioningService.companyTeamNames("PRODUCT OWNER")).isEmpty();  // tire yok
        assertThat(LdapProvisioningService.companyTeamNames(null)).isEmpty();
    }

    @Test
    @DisplayName("provision: takım + müdür varsa müdür otomatik MANAGER eskalasyon kontağı (HIGH) olur")
    void provision_autoCreatesManagerEscalationContact() {
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy@akbank.com"));
        // Müdür DB'de mevcut (employeeId=63535), e-postalı → resolveManagerLink onu bulur
        AppUser mgr = new AppUser();
        mgr.setId(700L); mgr.setUsername("mgr1"); mgr.setEmployeeId("63535");
        mgr.setActive(true); mgr.setEmail("mudur@akbank.com"); mgr.setDisplayName("Ali Müdür");
        when(userRepo.findByEmployeeId("63535")).thenReturn(Optional.of(mgr));
        when(userRepo.findById(700L)).thenReturn(Optional.of(mgr));
        when(contactRepo.findByTeamIdOrderByRoleAsc(anyLong())).thenReturn(List.of());

        Map<String, Object> attrs = Map.of(
                "cn", "80002", "displayName", "Üye",
                "extensionAttribute4", "CN=63535,OU=BTPersonel,DC=aknet,DC=akb",
                "memberOf", "CN=SY-DarkSide,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb");

        service.provisionFromAd("uye1", "CN=uye1,DC=aknet,DC=akb", attrs);

        org.mockito.ArgumentCaptor<EscalationContact> cap = org.mockito.ArgumentCaptor.forClass(EscalationContact.class);
        org.mockito.Mockito.verify(contactRepo).save(cap.capture());
        EscalationContact c = cap.getValue();
        assertThat(c.getRole()).isEqualTo("MANAGER");
        assertThat(c.getMinAlertLevel()).isEqualTo("HIGH");
        assertThat(c.getEmail()).isEqualTo("mudur@akbank.com");
        assertThat(c.getActive()).isTrue();
        assertThat(c.getUserId()).isEqualTo(700L);
    }

    @Test
    @DisplayName("provision: aynı müdür zaten MANAGER kontağıysa tekrar eklenmez")
    void provision_skipsDuplicateManagerContact() {
        when(directory.groupMail(anyString())).thenReturn(Optional.of("sy@akbank.com"));
        AppUser mgr = new AppUser();
        mgr.setId(700L); mgr.setUsername("mgr1"); mgr.setEmployeeId("63535");
        mgr.setActive(true); mgr.setEmail("mudur@akbank.com");
        when(userRepo.findByEmployeeId("63535")).thenReturn(Optional.of(mgr));
        when(userRepo.findById(700L)).thenReturn(Optional.of(mgr));
        EscalationContact existing = new EscalationContact();
        existing.setTeamId(1L); existing.setRole("MANAGER"); existing.setEmail("mudur@akbank.com");
        when(contactRepo.findByTeamIdOrderByRoleAsc(anyLong())).thenReturn(List.of(existing));

        Map<String, Object> attrs = Map.of(
                "cn", "80002", "displayName", "Üye",
                "extensionAttribute4", "CN=63535,OU=BTPersonel,DC=aknet,DC=akb",
                "memberOf", "CN=SY-DarkSide,OU=ScrumGroups,OU=BTPersonel,OU=Aknet,DC=aknet,DC=akb");

        service.provisionFromAd("uye1", "CN=uye1,DC=aknet,DC=akb", attrs);

        org.mockito.Mockito.verify(contactRepo, org.mockito.Mockito.never()).save(any());
    }
}
