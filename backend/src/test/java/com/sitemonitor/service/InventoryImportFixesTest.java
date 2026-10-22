package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
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
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İçe aktarma düzeltmeleri (2026-10-22): pasife alma açık alarmları kapatır (ADMIN-6); önizleme izleme grubu YARATMAZ
 * (ADMIN-8); sütun sınırını aşan hücre satır hatasıdır, tüm dosya geri alınmaz (ADMIN-9); takım değişince yabancı bildirim
 * grubu ve UG = SY düşer (ADMIN-13); güncelleme "son güncelleyen" künyesini basar (ADMIN-4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryImportFixesTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock MonitorHistoryService monitorHistory;
    @Mock MonitoringGroupService monitoringGroupService;
    @Mock SchedulerService schedulerService;
    @Mock PlatformService platformService;
    @Mock EscalationService escalationService;
    @Mock NotificationGroupRepository notificationGroupRepo;
    @InjectMocks InventoryImportService service;

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private CertificateInventory existing(String domain, long team) {
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L); ex.setDomain(domain); ex.setTeamId(team); ex.setPort(443); ex.setActive(true);
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.of(ex));
        return ex;
    }

    @BeforeEach
    void setUp() {
        service.setEscalationService(escalationService);
        service.setNotificationGroupRepo(notificationGroupRepo);
        when(teamRepo.findAll()).thenReturn(List.of(team(5L, "Takım A"), team(9L, "Takım B")));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.save(any())).thenAnswer(inv -> { CertificateInventory c = inv.getArgument(0); if (c.getId() == null) c.setId(100L); return c; });
        when(monitoringGroupService.getOrCreate(anyLong(), anyString(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("ADMIN-6: içe aktarma kaydı pasife alınca açık alarmlar sessizce kapanır; zaten pasif / önizleme / aktif tutan satır kapatmaz")
    void deactivateClosesAlarms() {
        existing("off.example.com", 5L);
        var plan = service.plan(List.of(row("domain", "off.example.com", "active", "hayır")), t -> true, "admin", null);
        assertThat(plan.updated()).isEqualTo(1);
        verify(escalationService, never()).closeAlertsOnDeactivate(any());

        existing("off.example.com", 5L);   // önizleme kopuk nesneyi bellekte değiştirdi — gerçek koşu taze okur
        var r = service.commit(List.of(row("domain", "off.example.com", "active", "hayır")), t -> true, "admin", null);
        assertThat(r.updated()).isEqualTo(1);
        verify(escalationService).closeAlertsOnDeactivate("off.example.com");

        CertificateInventory paused = existing("paused.example.com", 5L);
        paused.setActive(false);
        service.commit(List.of(row("domain", "paused.example.com", "active", "hayır", "tier", "2")), t -> true, "admin", null);
        verify(escalationService, never()).closeAlertsOnDeactivate("paused.example.com");
    }

    @Test
    @DisplayName("ADMIN-8: önizleme izleme grubunu YARATMAZ (getOrCreate çağrılmaz, kanonik ad okunur); işleme yaratır")
    void dryRunNeverCreatesGroups() {
        when(monitoringGroupService.canonicalOrNew(5L, "cert", "ödeme")).thenReturn("Ödeme");
        var plan = service.plan(List.of(row("domain", "g.example.com", "team", "Takım A", "group", "ödeme")), t -> true, "admin", null);
        assertThat(plan.created()).isEqualTo(1);
        assertThat(plan.rows().get(0).changes()).contains("group");
        verify(monitoringGroupService, never()).getOrCreate(any(), any(), any(), any());

        // Var olan grup adı aynı (kanonik) → değişiklik yok sayılır, önizleme yine yazmaz
        CertificateInventory ex = existing("same.example.com", 5L);
        ex.setGroupName("Ödeme");
        var same = service.plan(List.of(row("domain", "same.example.com", "group", "ödeme")), t -> true, "admin", null);
        assertThat(same.rows().get(0).reason()).isEqualTo("no_change");
        verify(monitoringGroupService, never()).getOrCreate(any(), any(), any(), any());

        service.commit(List.of(row("domain", "g.example.com", "team", "Takım A", "group", "ödeme")), t -> true, "admin", null);
        verify(monitoringGroupService).getOrCreate(5L, "cert", "ödeme", "admin");
    }

    @Test
    @DisplayName("ADMIN-9: sütun sınırını aşan hücre → satır 'value_too_long' (sütun changes'te); diğer satırlar işlenir; sınırdaki değer geçer")
    void tooLongCellIsRowError() {
        var r = service.commit(List.of(
                row("domain", "long.example.com", "team", "Takım A", "svc_mgmt_contact", "x".repeat(301)),
                row("domain", "ok.example.com", "team", "Takım A", "svc_mgmt_contact", "y".repeat(300)),
                row("domain", "pd.example.com", "team", "Takım A", "platform_detail", "z".repeat(161))), t -> true, "admin", null);
        assertThat(r.errors()).isEqualTo(2);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.rows().get(0).action()).isEqualTo("error");
        assertThat(r.rows().get(0).reason()).isEqualTo("value_too_long");
        assertThat(r.rows().get(0).changes()).containsExactly("svc_mgmt_contact");
        assertThat(r.rows().get(2).changes()).containsExactly("platform_detail");
        verify(inventoryRepo, times(1)).save(argThat(c -> "ok.example.com".equals(c.getDomain())));
    }

    @Test
    @DisplayName("ADMIN-13: takım değişince eski takımın bildirim grubu düşer (yeni takımınki kalır); UG = yeni SY düşer")
    void teamChangeDropsForeignGroupAndUg() {
        NotificationGroup g5 = new NotificationGroup(); g5.setId(40L); g5.setTeamId(5L);
        NotificationGroup g9 = new NotificationGroup(); g9.setId(41L); g9.setTeamId(9L);
        when(notificationGroupRepo.findById(40L)).thenReturn(Optional.of(g5));
        when(notificationGroupRepo.findById(41L)).thenReturn(Optional.of(g9));

        CertificateInventory ex = existing("move.example.com", 5L);
        ex.setNotificationGroupId(40L);
        ex.setUgTeamId(9L);
        var r = service.commit(List.of(row("domain", "move.example.com", "team", "Takım B")), t -> true, "admin", null);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(ex.getTeamId()).isEqualTo(9L);
        assertThat(ex.getNotificationGroupId()).as("A'nın grubu B'de geçersiz").isNull();
        assertThat(ex.getUgTeamId()).as("UG = SY anlamsız").isNull();
        assertThat(r.rows().get(0).changes()).contains("team", "notification_group", "ug_team");

        CertificateInventory ex2 = new CertificateInventory();
        ex2.setId(8L); ex2.setDomain("keep.example.com"); ex2.setTeamId(5L); ex2.setPort(443); ex2.setActive(true);
        ex2.setNotificationGroupId(41L);
        when(inventoryRepo.findByDomain("keep.example.com")).thenReturn(Optional.of(ex2));
        service.commit(List.of(row("domain", "keep.example.com", "team", "Takım B")), t -> true, "admin", null);
        assertThat(ex2.getNotificationGroupId()).as("hedef takımın grubu korunur").isEqualTo(41L);

        // Yeni kayıt: UG = SY gönderildiyse UG yazılmaz
        var created = service.commit(List.of(row("domain", "new.example.com", "team", "Takım A", "ug_team", "Takım A")), t -> true, "admin", null);
        assertThat(created.created()).isEqualTo(1);
        verify(inventoryRepo).save(argThat(c -> "new.example.com".equals(c.getDomain()) && c.getUgTeamId() == null));
    }

    @Test
    @DisplayName("Son inceleme 2026-10-22: takımı DEĞİŞMEYEN satırda eski UG = SY dokunulmaz — yalnız gelen alan değişir (geçmiş / denetim gürültüsü yok)")
    void untouchedTeamKeepsLegacyUg() {
        CertificateInventory ex = existing("legacy.example.com", 5L);
        ex.setUgTeamId(5L);
        ex.setTier(3);
        var r = service.commit(List.of(row("domain", "legacy.example.com", "tier", "2")), t -> true, "admin", null);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(ex.getUgTeamId()).isEqualTo(5L);
        assertThat(r.rows().get(0).changes()).containsExactly("tier");

        // Değişiklik yoksa satır hiç güncellenmez
        var none = service.commit(List.of(row("domain", "legacy.example.com", "tier", "2")), t -> true, "admin", null);
        assertThat(none.updated()).isZero();
        assertThat(ex.getUgTeamId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("ADMIN-4: oturumla yapılan içe aktarma güncellemesi 'son güncelleyen' künyesini basar")
    void updateStampsUpdatedBy() {
        CertificateInventory ex = existing("stamp.example.com", 5L);
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", "ADMIN");
        service.commit(List.of(row("domain", "stamp.example.com", "tier", "1")), t -> true, "admin", s);
        verify(monitorHistory).stampUpdated(ex, s);
    }
}
