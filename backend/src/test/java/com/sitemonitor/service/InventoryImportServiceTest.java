package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Envanter içe aktarma (2026-09-12, envanter #6): plan/commit ayrımı, kapsam, eşleşme ve alan kuralları. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryImportServiceTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock MonitorHistoryService monitorHistory;
    @Mock MonitoringGroupService monitoringGroupService;
    @Mock SchedulerService schedulerService;
    @Mock PlatformService platformService;
    @InjectMocks InventoryImportService service;

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() {
        when(teamRepo.findAll()).thenReturn(List.of(team(5L, "Takım A"), team(9L, "Takım B")));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.save(any())).thenAnswer(inv -> { CertificateInventory c = inv.getArgument(0); if (c.getId() == null) c.setId(100L); return c; });
        when(monitoringGroupService.getOrCreate(anyLong(), anyString(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("plan: yeni kayıt 'create', takım adıyla çözülür, hiçbir şey yazılmaz; commit aynı gövdeyi yazar + geçmiş + anlık kontrol")
    void planThenCommit() {
        List<Map<String, Object>> rows = List.of(row("domain", "https://www.example.com/", "team", "takım a", "tier", "T1", "port", "8443", "netscaler", "evet"));
        var plan = service.plan(rows, t -> true, "admin", null);
        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.created()).isEqualTo(1);
        assertThat(plan.rows().get(0).domain()).isEqualTo("www.example.com");
        assertThat(plan.rows().get(0).changes()).contains("domain", "tier", "netscaler");
        verify(inventoryRepo, never()).save(any());
        verify(schedulerService, never()).checkSingleDomainAsync(anyString(), anyInt(), anyBoolean(), any());

        var done = service.commit(rows, t -> true, "admin", null);
        assertThat(done.dryRun()).isFalse();
        assertThat(done.created()).isEqualTo(1);
        verify(inventoryRepo).save(argThat(c -> c.getDomain().equals("www.example.com") && c.getTeamId() == 5L
                && c.getTier() == 1 && c.getPort() == 8443 && Boolean.TRUE.equals(c.getNetscaler()) && Boolean.TRUE.equals(c.getActive())));
        verify(monitorHistory).record(eq(MonitorHistoryService.INVENTORY), eq(100L), eq("www.example.com"), eq(5L),
                eq(MonitorHistoryService.CREATE), isNull(), anyMap(), eq("import"), isNull());
        verify(schedulerService).checkSingleDomainAsync("www.example.com", 8443, false, null);
    }

    @Test
    @DisplayName("mevcut kayıt: yalnız DOLU gelen alanlar yazılır, boş hücre dokunmaz; değişiklik yoksa skip:no_change")
    void updateOnlyFilledFields() {
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain("a.example.com"); ex.setTeamId(5L); ex.setTier(2); ex.setSvcMgmtContact("eski@example.com"); ex.setPort(443);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(ex));

        var r = service.commit(List.of(row("domain", "a.example.com", "tier", "1", "svc_mgmt_contact", "", "app_dev_contact", "yeni@example.com")), t -> true, "admin", null);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.rows().get(0).changes()).containsExactly("tier", "app_dev_contact");
        assertThat(ex.getSvcMgmtContact()).isEqualTo("eski@example.com");   // boş hücre = dokunma
        assertThat(ex.getTier()).isEqualTo(1);
        verify(monitorHistory).record(eq(MonitorHistoryService.INVENTORY), eq(7L), any(), eq(5L), eq(MonitorHistoryService.UPDATE), anyMap(), anyMap(), eq("import"), isNull());

        var same = service.commit(List.of(row("domain", "a.example.com", "tier", "1")), t -> true, "admin", null);
        assertThat(same.skipped()).isEqualTo(1);
        assertThat(same.rows().get(0).reason()).isEqualTo("no_change");
    }

    @Test
    @DisplayName("D9 (2026-09-28): bilinmeyen UG takım adı satır HATASIDIR (unknown_ug_team) — sessizce yok sayılıp 'create' raporlanmaz")
    void unknownUgTeam_isRowError() {
        var r = service.commit(List.of(row("domain", "ug.example.com", "team", "Takım A", "ug_team", "Takım Z")), t -> true, "admin", null);
        assertThat(r.errors()).isEqualTo(1);
        assertThat(r.rows().get(0).reason()).isEqualTo("unknown_ug_team");
        verify(inventoryRepo, never()).save(any());
        var ok = service.commit(List.of(row("domain", "ug2.example.com", "team", "Takım A", "ug_team", "Takım B")), t -> true, "admin", null);
        assertThat(ok.created()).isEqualTo(1);   // bilinen UG adı → normal
    }

    @Test
    @DisplayName("2026-09-28: içe aktarmada takım değişirse türev Port/DNS izlemeleri de yeni takıma eşitlenir (alarm ESKİ takıma gitmez); plan ve takımsız değişiklik eşitlemez")
    void teamChange_syncsDerivedMonitors() {
        DerivedMonitorTeamSync sync = mock(DerivedMonitorTeamSync.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "derivedMonitorTeamSync", sync);
        CertificateInventory planned = new CertificateInventory();
        planned.setId(7L); planned.setDomain("a.example.com"); planned.setTeamId(5L); planned.setTier(2); planned.setPort(443);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(planned));
        service.plan(List.of(row("domain", "a.example.com", "team", "Takım B")), t -> true, "admin", null);
        verifyNoInteractions(sync);                                   // prova yazmaz, eşitlemez

        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain("a.example.com"); ex.setTeamId(5L); ex.setTier(2); ex.setPort(443);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(ex));
        service.commit(List.of(row("domain", "a.example.com", "tier", "1")), t -> true, "admin", null);
        verifyNoInteractions(sync);                                   // takım değişmedi

        var r = service.commit(List.of(row("domain", "a.example.com", "team", "Takım B")), t -> true, "admin", null);
        assertThat(r.rows().get(0).changes()).contains("team");
        assertThat(ex.getTeamId()).isEqualTo(9L);
        verify(sync).syncTeam("a.example.com", 9L);
    }

    @Test
    @DisplayName("kapsam SATIR BAŞINA: yabancı takım skip:scope, batch durmaz; aynı domain ikinci kez skip:duplicate_row; SİLİNEN ad yeniden eklenir (2026-10-07)")
    void scopeDeletedDuplicate() {
        PermanentDeletionService deletion = org.mockito.Mockito.mock(PermanentDeletionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "permanentDeletion", deletion);
        CertificateInventory del = new CertificateInventory(); del.setId(1L); del.setDomain("gone.example.com"); del.setTeamId(5L); del.setDeletedAt("2026-09-01T00:00:00");
        when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.of(del));
        var r = service.commit(List.of(
                row("domain", "foreign.example.com", "team", "9"),
                row("domain", "mine.example.com", "team", "5"),
                row("domain", "mine.example.com", "team", "5"),
                row("domain", "gone.example.com", "team", "5", "tier", "1"),
                row("domain", "noteam.example.com"),
                row("domain", "not a domain !", "team", "5"),
                row("domain", "x.example.com", "team", "Takım Yok")),
                t -> t != null && t == 5L, "po", null);
        // Silme KALICI: eski sürümden kalmış çöp satırı adı tutmaz — "deleted" nedeni yok, satır YENİ kayıt olarak açılır.
        assertThat(r.created()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(2);
        assertThat(r.errors()).isEqualTo(3);
        assertThat(r.rows()).extracting("reason").containsExactly("scope", null, "duplicate_row", null, "team_required", "invalid_domain", "unknown_team");
        assertThat(r.rows()).extracting("action").contains("create").doesNotContain("deleted");
        verify(inventoryRepo, times(2)).save(any());
        verify(deletion).purgeLegacyBinRows("gone.example.com");   // eski satır yeni kayıttan ÖNCE kalıcı silinir
        verify(deletion, never()).purgeLegacyBinRows("foreign.example.com");   // kapsam dışı satır hiçbir şey silmez
    }

    @Test
    @DisplayName("Silme KALICI (2026-10-07): kuru koşu (plan) silinen adı 'oluştur' gösterir ama HİÇBİR ŞEY silmez")
    void plan_legacyBinRow_showsCreate_noPurge() {
        PermanentDeletionService deletion = org.mockito.Mockito.mock(PermanentDeletionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "permanentDeletion", deletion);
        CertificateInventory del = new CertificateInventory(); del.setId(1L); del.setDomain("gone.example.com"); del.setTeamId(9L); del.setDeletedAt("2026-09-01T00:00:00");
        when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.of(del));
        var r = service.plan(List.of(row("domain", "gone.example.com", "team", "5")), t -> t != null && t == 5L, "po", null);
        assertThat(r.rows()).extracting("action", "reason").containsExactly(org.assertj.core.groups.Tuple.tuple("create", null));
        org.mockito.Mockito.verifyNoInteractions(deletion);
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("org geneli görünürlük (2026-09-26): BAŞKA takımın MEVCUT alan adı CSV ile ezilemez / kendi takımına çekilemez — skip:duplicate_other_team (+ sahibi takım, 2026-09-28), tek alan bile yazılmaz")
    void existingForeignDomain_cannotBeOverwrittenOrHijacked() {
        CertificateInventory foreign = new CertificateInventory();
        foreign.setId(8L); foreign.setDomain("foreign.example.com"); foreign.setTeamId(9L); foreign.setTier(3); foreign.setPort(443);
        foreign.setSvcMgmtContact("Takım B - destek@example.com");
        when(inventoryRepo.findByDomain("foreign.example.com")).thenReturn(Optional.of(foreign));
        java.util.function.Predicate<Long> manager5 = t -> t != null && t == 5L;

        var r = service.commit(List.of(
                row("domain", "foreign.example.com", "team", "5", "tier", "1"),                        // kendi takımına çekme
                row("domain", "https://FOREIGN.example.com/login", "svc_mgmt_contact", "saldirgan@example.com")),   // yalnız alan ezme
                manager5, "po", null);

        assertThat(r.updated()).isZero();
        assertThat(r.rows()).extracting("reason").containsExactly("duplicate_other_team", "duplicate_row");
        // Satır sahibi takımı taşır (içe aktarma penceresi "hangi ekipte kayıtlı" rozetini çizer); ikinci satır taşımaz.
        assertThat(r.rows()).extracting("teamId", "teamName").containsExactly(
                org.assertj.core.groups.Tuple.tuple(9L, "Takım B"), org.assertj.core.groups.Tuple.tuple(null, null));
        var alone = service.commit(List.of(row("domain", "foreign.example.com", "svc_mgmt_contact", "saldirgan@example.com")),
                manager5, "po", null);
        assertThat(alone.rows()).extracting("action", "reason").containsExactly(org.assertj.core.groups.Tuple.tuple("skip", "duplicate_other_team"));
        verify(inventoryRepo, never()).save(any());
        verify(monitorHistory, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(foreign.getTeamId()).isEqualTo(9L);
        assertThat(foreign.getTier()).isEqualTo(3);
        assertThat(foreign.getSvcMgmtContact()).isEqualTo("Takım B - destek@example.com");
    }

    @Test
    @DisplayName("mükerrer (2026-09-28): eski KARIŞIK HARFLİ satır da bulunur (mükerrer satır açılmaz); başka takımın SİLİNMİŞ kaydı engel değil (2026-10-07)")
    void deletedCarriesOwner_andLegacyMixedCaseRowIsMatched() {
        CertificateInventory gone = new CertificateInventory();
        gone.setId(3L); gone.setDomain("gone.example.com"); gone.setTeamId(9L); gone.setDeletedAt("2026-09-01T00:00:00");
        when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.of(gone));
        CertificateInventory legacy = new CertificateInventory();   // tam eşleşme ("legacy.example.com") BULAMAZ
        legacy.setId(4L); legacy.setDomain("Legacy.Example.com"); legacy.setTeamId(9L); legacy.setPort(443);
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("legacy.example.com")).thenReturn(Optional.of(legacy));
        java.util.function.Predicate<Long> manager5 = t -> t != null && t == 5L;

        var r = service.commit(List.of(
                row("domain", "gone.example.com", "team", "5"),
                row("domain", "legacy.example.com", "team", "5", "tier", "1")), manager5, "po", null);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.rows()).extracting("action", "reason", "teamId", "teamName").containsExactly(
                org.assertj.core.groups.Tuple.tuple("create", null, null, null),
                org.assertj.core.groups.Tuple.tuple("skip", "duplicate_other_team", 9L, "Takım B"));
        verify(inventoryRepo, times(1)).save(argThat(c -> "gone.example.com".equals(c.getDomain()) && c.getTeamId() == 5L));
        org.mockito.Mockito.clearInvocations(inventoryRepo);

        // Kendi takımının karışık harfli satırı → GÜNCELLENİR (ikinci satır yaratılmaz)
        legacy.setTeamId(5L);
        var own = service.commit(List.of(row("domain", "legacy.example.com", "tier", "1")), manager5, "po", null);
        assertThat(own.rows()).extracting("action").containsExactly("update");
        assertThat(own.created()).isZero();
        verify(inventoryRepo).save(argThat(c -> c.getId() == 4L && c.getTier() == 1));
    }

    /** Kapsamlı müdür oturumu: rol ADMIN ama takım 5 ile sınırlı → global DEĞİL. */
    private static org.springframework.mock.web.MockHttpSession scopedManager() {
        var s = new org.springframework.mock.web.MockHttpSession();
        s.setAttribute("username", "po");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(5L)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(5L)));
        return s;
    }

    @Test
    @DisplayName("BO9 kardeşi: içe aktarımda UG takımını BAŞKA takıma çevirmek yalnız global admin — kapsamlı müdür skip:scope, hiçbir alan yazılmaz")
    void ugTeamChange_requiresGlobalAdmin() {
        CertificateInventory mine = new CertificateInventory();
        mine.setId(7L); mine.setDomain("mine.example.com"); mine.setTeamId(5L); mine.setUgTeamId(null); mine.setTier(2); mine.setPort(443);
        when(inventoryRepo.findByDomain("mine.example.com")).thenReturn(Optional.of(mine));
        java.util.function.Predicate<Long> manager5 = t -> t != null && t == 5L;

        var r = service.commit(List.of(
                row("domain", "mine.example.com", "ug_team", "Takım B", "tier", "1"),     // mevcut kayıt: UG → takım 9
                row("domain", "new.example.com", "team", "5", "ug_team", "9")),          // yeni kayıt: UG → takım 9
                manager5, "po", scopedManager());
        assertThat(r.rows()).extracting("action", "reason").containsExactly(
                org.assertj.core.groups.Tuple.tuple("skip", "scope"), org.assertj.core.groups.Tuple.tuple("skip", "scope"));
        assertThat(mine.getUgTeamId()).isNull();
        assertThat(mine.getTier()).as("satır bütün olarak atlanır").isEqualTo(2);
        verify(inventoryRepo, never()).save(any());

        // Aynı değer (dışa aktar → içe aktar gidiş-dönüşü) ve kaydın kendi takımı engellenmez.
        mine.setUgTeamId(9L);
        var same = service.commit(List.of(row("domain", "mine.example.com", "ug_team", "9", "tier", "1"),
                row("domain", "own.example.com", "team", "5", "ug_team", "5")), manager5, "po", scopedManager());
        assertThat(same.updated()).isEqualTo(1);
        assertThat(same.created()).isEqualTo(1);
        assertThat(mine.getTier()).isEqualTo(1);
    }

    @Test
    @DisplayName("BO9 kardeşi: global admin içe aktarımda UG takımını değiştirebilir (transfer-ug ile aynı yetki)")
    void ugTeamChange_globalAdminAllowed() {
        CertificateInventory rec = new CertificateInventory();
        rec.setId(7L); rec.setDomain("a.example.com"); rec.setTeamId(5L); rec.setPort(443);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(rec));
        var admin = new org.springframework.mock.web.MockHttpSession();
        admin.setAttribute("systemRole", "ADMIN");   // viewTeamIds yok → global

        var r = service.commit(List.of(row("domain", "a.example.com", "ug_team", "Takım B")), t -> true, "admin", admin);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(rec.getUgTeamId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("evet/hayır/1/0/x/✓ → bayrak; bilinmeyen metin → dokunma")
    void booleans() {
        assertThat(InventoryImportService.boolOrNull("Evet")).isTrue();
        assertThat(InventoryImportService.boolOrNull("✓")).isTrue();
        assertThat(InventoryImportService.boolOrNull("hayır")).isFalse();
        assertThat(InventoryImportService.boolOrNull("0")).isFalse();
        assertThat(InventoryImportService.boolOrNull("belki")).isNull();
        assertThat(InventoryImportService.boolOrNull("")).isNull();
        assertThat(InventoryImportService.intOrNull("T3")).isEqualTo(3);
        assertThat(InventoryImportService.intOrNull("abc")).isNull();
    }

    @Test
    @DisplayName("platform: katalogda olmayan değer 'error:unknown_platform' — kayıtlı platformu SESSİZCE silmez")
    void unknownPlatformIsRejectedNotWiped() {
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain("a.example.com"); ex.setTeamId(5L); ex.setPlatform("IIS");
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(ex));
        when(platformService.normalize("Tomcat")).thenReturn(null);

        var res = service.commit(List.of(row("domain", "a.example.com", "team", "takım a", "platform", "Tomcat")),
                t -> true, "admin", null);

        assertThat(res.errors()).isEqualTo(1);
        assertThat(res.rows().get(0).action()).isEqualTo("error");
        assertThat(res.rows().get(0).reason()).isEqualTo("unknown_platform");
        assertThat(ex.getPlatform()).isEqualTo("IIS");   // eski yol burada null yazıyordu
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("platform: katalog ADI koda çözülür; kod zaten aynıysa hayalet 'update' üretmez")
    void platformNameResolvesToCodeWithoutPhantomChange() {
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain("a.example.com"); ex.setTeamId(5L); ex.setPlatform("OPENSHIFT");
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(ex));
        when(platformService.normalize("OpenShift")).thenReturn("OPENSHIFT");

        var res = service.commit(List.of(row("domain", "a.example.com", "team", "takım a", "platform", "OpenShift")),
                t -> true, "admin", null);

        assertThat(res.skipped()).isEqualTo(1);
        assertThat(res.rows().get(0).reason()).isEqualTo("no_change");   // eski yol: ham "OpenShift" != kod → sahte update
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("platform: katalogdaki farklı yazım koda çözülür ve gerçekten değişiyorsa yazılır")
    void platformChangeIsWritten() {
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain("a.example.com"); ex.setTeamId(5L); ex.setPlatform("IIS");
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(ex));
        when(platformService.normalize("openshift")).thenReturn("OPENSHIFT");

        var res = service.commit(List.of(row("domain", "a.example.com", "team", "takım a", "platform", "openshift")),
                t -> true, "admin", null);

        assertThat(res.updated()).isEqualTo(1);
        assertThat(res.rows().get(0).changes()).contains("platform");
        assertThat(ex.getPlatform()).isEqualTo("OPENSHIFT");
    }
}
