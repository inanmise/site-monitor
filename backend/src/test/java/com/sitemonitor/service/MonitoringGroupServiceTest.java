package com.sitemonitor.service;

import com.sitemonitor.model.MonitoringGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.MonitoringGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MonitoringGroupService — TAKIM + TÜR bazlı get-or-create, tek-tür rename cascade, 403/409, kapsam sızıntısızlığı. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringGroupServiceTest {

    @Mock MonitoringGroupRepository groupRepo;
    @Mock CertificateInventoryRepository certRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock DomainMonitorRepository domainRepo;
    // Sentetik grup sayımı 20.19.x'te bağlandı; mock EKLENMEZSE @InjectMocks alanı null bırakır ve
    // listForScope tüm testlerde NPE'ye düşer (sayım yolu her çağrıda bu repo'ya uğrar).
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;
    @Mock AuditService auditService;
    @Mock com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @Mock org.springframework.transaction.PlatformTransactionManager txManager;

    @InjectMocks MonitoringGroupService service;

    private static MonitoringGroup grp(long id, long team, String type, String name) {
        MonitoringGroup g = new MonitoringGroup();
        g.setId(id); g.setTeamId(team); g.setType(type); g.setName(name); g.setNameLower(name.toLowerCase());
        return g;
    }
    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }
    private static MockHttpSession adminSession() { var s = new MockHttpSession(); s.setAttribute("systemRole", "ADMIN"); return s; }
    private static MockHttpSession otherTeamUser() {
        var s = new MockHttpSession();
        s.setAttribute("systemRole", "USER"); s.setAttribute("teamId", 2L);
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        return s;
    }

    @Test
    void getOrCreate_createsCanonical_trimmed() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "deneme")).thenReturn(Optional.empty());
        when(groupRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.getOrCreate(1L, "dns", "  Deneme ", "u")).isEqualTo("Deneme");
    }

    @Test
    void getOrCreate_caseInsensitiveDedup_returnsExistingCanonical() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "deneme")).thenReturn(Optional.of(grp(7, 1, "dns", "Deneme")));
        assertThat(service.getOrCreate(1L, "dns", "DENEME", "u")).isEqualTo("Deneme");
        verify(groupRepo, never()).saveAndFlush(any());
    }

    @Test
    void rename_cascadesOnlyToThatTypeAndTypeScopedAlertHistory() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "new")).thenReturn(Optional.empty());
        when(dnsRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(4);

        int affected = service.rename(5L, "new", adminSession());
        assertThat(affected).isEqualTo(4);
        verify(dnsRepo).renameGroupForTeam(1L, "old", "new");
        verify(httpRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());   // YALNIZ o tür
        verify(alertEventRepo).renameGroupForTeamAndTypes(eq(1L), eq("old"), eq("new"), any());
    }

    @Test
    void rename_nameConflictInSameTeamAndType_throws409() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "taken")).thenReturn(Optional.of(grp(9, 1, "dns", "taken")));
        assertThatThrownBy(() -> service.rename(5L, "taken", adminSession())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rename_otherTeamUser_throws403() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));   // takım 1
        assertThatThrownBy(() -> service.rename(5L, "new", otherTeamUser())).isInstanceOf(SecurityException.class);
    }

    @Test
    void listForScope_user_seesOnlyOwnTeams_noLeak() {
        when(groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(List.of(2L))).thenReturn(List.of(grp(9, 2, "dns", "x")));
        when(teamRepo.findAll()).thenReturn(List.of(team(2, "T2")));
        var out = service.listForScope(List.of(2L), null, null);
        assertThat(out).extracting(MonitoringGroupService.GroupInfo::teamId).containsExactly(2L);
        verify(groupRepo, never()).findAllByOrderByTeamIdAscTypeAscNameAsc();   // admin-only sorgu çağrılmaz
    }

    // ── getOrCreate kenar durumları ─────────────────────────────────────────────
    @Test
    void getOrCreate_nullOrBlankName_returnsNull_noWrite() {
        assertThat(service.getOrCreate(1L, "dns", null, "u")).isNull();
        assertThat(service.getOrCreate(1L, "dns", "   ", "u")).isNull();
        verify(groupRepo, never()).saveAndFlush(any());
    }

    @Test
    void getOrCreate_nullTeam_passthroughTrimmed_noRegistryWrite() {
        assertThat(service.getOrCreate(null, "dns", "  X ", "u")).isEqualTo("X");   // takım zorunlu kuralı çağıran uçta
        verify(groupRepo, never()).findByTeamIdAndTypeAndNameLower(any(), anyString(), anyString());
        verify(groupRepo, never()).saveAndFlush(any());
    }

    @Test
    void getOrCreate_raceOnSave_refetchesCanonical() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "deneme"))
                .thenReturn(Optional.empty())                                 // ilk kontrol: yok
                .thenReturn(Optional.of(grp(7, 1, "dns", "Deneme")));         // eşzamanlı create sonrası: var
        when(groupRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThat(service.getOrCreate(1L, "dns", "deneme", "u")).isEqualTo("Deneme");
    }

    @Test
    void getOrCreateFor_derivesTypeFromEntityClass() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "ping", "g")).thenReturn(Optional.of(grp(3, 1, "ping", "G")));
        assertThat(service.getOrCreateFor(new com.sitemonitor.model.PingMonitor(), 1L, "g", "u")).isEqualTo("G");
        verify(groupRepo).findByTeamIdAndTypeAndNameLower(1L, "ping", "g");   // "ping" türü nesne sınıfından çıkarıldı
    }

    // ── rename kenar durumları ──────────────────────────────────────────────────
    @Test
    void rename_notFound_throwsNoSuchElement() {
        when(groupRepo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rename(9L, "x", adminSession())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rename_blankNewName_throws400() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        assertThatThrownBy(() -> service.rename(5L, "   ", adminSession())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rename_sameName_noOpReturnsZero_noCascadeNoAudit() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "same")));
        assertThat(service.rename(5L, "same", adminSession())).isZero();
        verify(dnsRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());
        verify(auditService, never()).recordAction(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rename_httpType_cascadesOnlyHttp() {
        when(groupRepo.findById(6L)).thenReturn(Optional.of(grp(6, 1, "http", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "http", "new")).thenReturn(Optional.empty());
        when(httpRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(3);
        assertThat(service.rename(6L, "new", adminSession())).isEqualTo(3);
        verify(httpRepo).renameGroupForTeam(1L, "old", "new");
        verify(dnsRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());   // yalnız o tür
    }

    @Test
    void rename_ownTeamNonAdmin_allowed() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 2, "dns", "old")));   // takım 2 = kullanıcının takımı
        when(groupRepo.findByTeamIdAndTypeAndNameLower(2L, "dns", "new")).thenReturn(Optional.empty());
        when(dnsRepo.renameGroupForTeam(2L, "old", "new")).thenReturn(1);
        assertThat(service.rename(5L, "new", otherTeamUser())).isEqualTo(1);   // ownTeam (canManage değil) → izinli
    }

    @Test
    void rename_writesAudit_onSuccess() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "new")).thenReturn(Optional.empty());
        when(dnsRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(2);
        service.rename(5L, "new", adminSession());
        verify(auditService).recordAction(eq("MONITOR_GROUP_RENAME"), any(), any(), any(), any(),
                eq("MONITOR_GROUP"), eq("5"), contains("\"old\":\"old\""), any(), any(), any());
    }

    // ── listForScope kapsam varyantları ─────────────────────────────────────────
    @Test
    void listForScope_admin_usesFindAll() {
        when(groupRepo.findAllByOrderByTeamIdAscTypeAscNameAsc()).thenReturn(List.of(grp(1, 1, "dns", "x")));
        var out = service.listForScope(null, null, null);   // viewTeamIds null = global admin
        assertThat(out).hasSize(1);
        verify(groupRepo).findAllByOrderByTeamIdAscTypeAscNameAsc();
    }

    @Test
    void listForScope_emptyScope_returnsEmpty_noQueries() {
        assertThat(service.listForScope(List.of(), null, null)).isEmpty();   // hiçbir takım kapsamı → kısa devre
        verify(groupRepo, never()).findAllByOrderByTeamIdAscTypeAscNameAsc();
        verify(certRepo, never()).groupCountsByTeam();
        verify(teamRepo, never()).findAll();
    }

    @Test
    void listForScope_aggregatesCountsAndTeamName() {
        when(groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(List.of(1L)))
                .thenReturn(List.of(grp(1, 1, "dns", "deneme")));
        when(dnsRepo.groupCountsByTeam()).thenReturn(List.<Object[]>of(new Object[]{1L, "deneme", 4L}));
        when(teamRepo.findAll()).thenReturn(List.of(team(1, "T1")));
        var out = service.listForScope(List.of(1L), null, null);
        assertThat(out).singleElement().satisfies(g -> {
            assertThat(g.name()).isEqualTo("deneme");
            assertThat(g.count()).isEqualTo(4);
            assertThat(g.teamName()).isEqualTo("T1");
        });
    }

    @Test
    void listForScope_countMergesCasingVariants() {
        // Monitör tablosunda "Prod"(2) + "prod"(3) → registry'deki tek "Prod" grubunda count 5 görünmeli
        when(groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(List.of(1L)))
                .thenReturn(List.of(grp(1, 1, "dns", "Prod")));
        when(dnsRepo.groupCountsByTeam()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Prod", 2L}, new Object[]{1L, "prod", 3L}));
        when(teamRepo.findAll()).thenReturn(List.of(team(1, "T1")));
        var out = service.listForScope(List.of(1L), null, null);
        assertThat(out).singleElement().satisfies(g -> assertThat(g.count()).isEqualTo(5));
    }

    @Test
    void listForScope_typeFilter_scansOnlyThatTypeTable_andSingleTeamLookup() {
        // Sıcak yol (form autocomplete): teamId+type verildiğinde 7 tablo taraması ve findAll yapılmamalı
        when(groupRepo.findByTeamIdAndTypeOrderByNameAsc(1L, "dns")).thenReturn(List.of(grp(1, 1, "dns", "x")));
        when(dnsRepo.groupCountsByTeam()).thenReturn(List.of());
        when(teamRepo.findById(1L)).thenReturn(Optional.of(team(1, "T1")));
        var out = service.listForScope(List.of(1L), 1L, "dns");
        assertThat(out).hasSize(1);
        verify(dnsRepo).groupCountsByTeam();
        verify(certRepo,    never()).groupCountsByTeam();
        verify(httpRepo,    never()).groupCountsByTeam();
        verify(pingRepo,    never()).groupCountsByTeam();
        verify(portRepo,    never()).groupCountsByTeam();
        verify(keywordRepo, never()).groupCountsByTeam();
        verify(domainRepo,  never()).groupCountsByTeam();
        verify(teamRepo,    never()).findAll();
    }

    @Test
    void rename_uniqueIndexRace_throws409NotRaw() {
        // TOCTOU: ön-kontrol geçse de saveAndFlush unique index'e çarparsa 500 değil 409 (IllegalStateException)
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "new")).thenReturn(Optional.empty());
        when(groupRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThatThrownBy(() -> service.rename(5L, "new", adminSession())).isInstanceOf(IllegalStateException.class);
        verify(dnsRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());
    }

    // ── SONRADAN grup taşımaya başlayan türler: page + scripted ─────────────────
    // typeOf bu ikisini üretiyordu (form grup önerileri çalışıyordu) ama rename zincirine hiç bağlanmamışlardı:
    // registry adı değişiyor, monitör satırları ESKİ grup adında kalıyordu → monitörler sessizce gruptan düşüyordu.

    @Test
    void rename_scriptedType_cascadesToScriptedRowsAndScriptedAlertHistory() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "scripted", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "scripted", "new")).thenReturn(Optional.empty());
        when(scriptedRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(2);

        assertThat(service.rename(5L, "new", adminSession())).isEqualTo(2);
        verify(scriptedRepo).renameGroupForTeam(1L, "old", "new");
        verify(dnsRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());   // yalnız o tür
        verify(alertEventRepo).renameGroupForTeamAndTypes(eq(1L), eq("old"), eq("new"),
                argThat(types -> types.containsAll(List.of("SCRIPTED_FAIL", "SCRIPTED_SLOW"))));
    }

    @Test
    void rename_pageType_cascadesToPageRowsAndPageAlertHistory() {
        when(groupRepo.findById(6L)).thenReturn(Optional.of(grp(6, 1, "page", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "page", "new")).thenReturn(Optional.empty());
        when(pageRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(3);

        assertThat(service.rename(6L, "new", adminSession())).isEqualTo(3);
        verify(pageRepo).renameGroupForTeam(1L, "old", "new");
        verify(alertEventRepo).renameGroupForTeamAndTypes(eq(1L), eq("old"), eq("new"),
                argThat(types -> types.containsAll(List.of("PAGE_DOWN", "PAGE_INTEGRITY"))));
    }

    @Test
    void listForScope_countsPageGroups() {
        when(groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(List.of(1L)))
                .thenReturn(List.of(grp(1, 1, "page", "Prod")));
        when(pageRepo.groupCountsByTeam()).thenReturn(List.<Object[]>of(new Object[]{1L, "Prod", 6L}));
        when(teamRepo.findAll()).thenReturn(List.of(team(1, "T1")));
        var out = service.listForScope(List.of(1L), null, null);
        assertThat(out).singleElement().satisfies(g -> assertThat(g.count()).isEqualTo(6));   // sayım yolunda da yoktu
    }

    /** BEKÇİ: typeOf'un ürettiği HER tür rename zincirinin tamamına bağlı olmalı — monitör tablosu cascade'i VE
     *  o türe ait alarm tipleri. Yeni bir izleme türü typeOf'a eklenip burada unutulursa bu test kırmızıya döner
     *  (page/scripted'de tam olarak bu yaşandı: grup adı registry'de değişip monitörlerde kalıyordu). */
    @Test
    void everyTypeDerivedFromMonitorEntity_isFullyWiredIntoRename() {
        List<Object> monitors = List.of(
                new com.sitemonitor.model.HttpMonitor(), new com.sitemonitor.model.PingMonitor(),
                new com.sitemonitor.model.PortMonitor(), new com.sitemonitor.model.DnsMonitor(),
                new com.sitemonitor.model.KeywordMonitor(), new com.sitemonitor.model.DomainMonitor(),
                new com.sitemonitor.model.PageMonitor(), new com.sitemonitor.model.ScriptedMonitor());

        // Türü üretim kodunun kendisinden (typeOf) topla — liste testte sabitlenirse bekçi işe yaramaz.
        List<String> types = new java.util.ArrayList<>();
        when(groupRepo.findByTeamIdAndTypeAndNameLower(eq(1L), anyString(), eq("probe")))
                .thenAnswer(inv -> {
                    types.add(inv.getArgument(1));
                    return Optional.of(grp(1, 1, inv.getArgument(1), "Probe"));
                });
        for (Object m : monitors) service.getOrCreateFor(m, 1L, "probe", "u");
        assertThat(types).doesNotContain("");   // typeOf default'una düşen monitör = registry'ye türsüz yazılır

        // Her tür için: cascade edilen tablo 1 satır dönmeli (bağlı değilse default 0) + alarm tipleri boş olmamalı.
        for (String type : types) {
            clearInvocations(alertEventRepo);
            when(certRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(httpRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(pingRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(portRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(dnsRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(keywordRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(domainRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(pageRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(scriptedRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(1);
            when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, type, "old")));
            when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, type, "new")).thenReturn(Optional.empty());

            assertThat(service.rename(5L, "new", adminSession()))
                    .as("%s türü cascadeRename'e bağlı değil (monitör satırları eski grup adında kalır)", type)
                    .isEqualTo(1);
            verify(alertEventRepo, description(type + " türü TYPE_ALERTS'te yok (alarm geçmişi eski grup adında kalır)"))
                    .renameGroupForTeamAndTypes(eq(1L), eq("old"), eq("new"), argThat(t -> t != null && !t.isEmpty()));
        }
    }
}
