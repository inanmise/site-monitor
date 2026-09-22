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
    @DisplayName("kapsam SATIR BAŞINA: yabancı takım skip:scope, batch durmaz; silinmiş kayıt skip:deleted; aynı domain ikinci kez skip:duplicate_row")
    void scopeDeletedDuplicate() {
        CertificateInventory del = new CertificateInventory(); del.setId(1L); del.setDomain("gone.example.com"); del.setTeamId(5L); del.setDeletedAt("2026-09-01T00:00:00");
        when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.of(del));
        var r = service.commit(List.of(
                row("domain", "foreign.example.com", "team", "9"),
                row("domain", "mine.example.com", "team", "5"),
                row("domain", "mine.example.com", "team", "5"),
                row("domain", "gone.example.com", "tier", "1"),
                row("domain", "noteam.example.com"),
                row("domain", "not a domain !", "team", "5"),
                row("domain", "x.example.com", "team", "Takım Yok")),
                t -> t != null && t == 5L, "po", null);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(3);
        assertThat(r.errors()).isEqualTo(3);
        assertThat(r.rows()).extracting("reason").containsExactly("scope", null, "duplicate_row", "deleted", "team_required", "invalid_domain", "unknown_team");
        verify(inventoryRepo, times(1)).save(any());
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
