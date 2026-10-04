package com.sitemonitor.service;

import com.sitemonitor.model.PermissionGrant;
import com.sitemonitor.repository.PermissionGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock PermissionGrantRepository repo;
    @InjectMocks PermissionService service;

    private static PermissionGrant grant(String role, String key, String action, boolean allowed) {
        PermissionGrant g = new PermissionGrant();
        g.setRole(role); g.setResourceKey(key); g.setAction(action); g.setAllowed(allowed);
        return g;
    }

    @BeforeEach
    void seedCache() {
        when(repo.findAll()).thenReturn(List.of(
                grant("USER", "inventory.list", "view", true),
                grant("USER", "inventory.crud", "edit", false)));
        service.rebuildCache();
    }

    @Test
    @DisplayName("allows: tanımlı true grant → true; false grant → false")
    void allows_definedGrants() {
        assertThat(service.allows("USER", "inventory.list", "view")).isTrue();
        assertThat(service.allows("USER", "inventory.crud", "edit")).isFalse();
    }

    @Test
    @DisplayName("allows: fail-closed — null/eksik role/key/action hep false")
    void allows_failClosed() {
        assertThat(service.allows((String) null, "inventory.list", "view")).isFalse();
        assertThat(service.allows("USER", null, "view")).isFalse();
        assertThat(service.allows("USER", "inventory.list", null)).isFalse();
        assertThat(service.allows("UNKNOWN_ROLE", "inventory.list", "view")).isFalse();
        assertThat(service.allows("USER", "unknown.resource", "view")).isFalse();
        assertThat(service.allows("USER", "inventory.list", "execute")).isFalse(); // tanımsız action
    }

    @Test
    @DisplayName("upsertGrant: ADMIN izni revoke edilemez (SecurityException), DB'ye yazılmaz")
    void upsertGrant_adminCannotBeRevoked() {
        assertThatThrownBy(() -> service.upsertGrant("ADMIN", "inventory.crud", "edit", false, "admin"))
                .isInstanceOf(SecurityException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("upsertGrant: normal grant kaydedilir ve cache yeniden kurulur")
    void upsertGrant_savesAndRebuilds() {
        when(repo.findByRoleAndResourceKeyAndAction("USER", "notes.read", "view"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionGrant saved = service.upsertGrant("USER", "notes.read", "view", true, "admin");

        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getAllowed()).isTrue();
        verify(repo).save(any(PermissionGrant.class));
        verify(repo, atLeast(2)).findAll(); // ilk seed + upsert sonrası rebuild
    }

    @Test
    @DisplayName("seedMissingDefaults: yalnız EKSİK grant eklenir, mevcutlara dokunulmaz")
    void seedMissingDefaults_addsOnlyMissing() {
        // Varsayılan: tüm grant'ler zaten var → hiçbiri eklenmez
        when(repo.findByRoleAndResourceKeyAndAction(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new PermissionGrant()));
        // İstisna: ADMIN weekly_reports.approve/execute eksik → tek ekleme beklenir
        when(repo.findByRoleAndResourceKeyAndAction("ADMIN", "weekly_reports.approve", "execute"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.seedMissingDefaults();

        ArgumentCaptor<PermissionGrant> cap = ArgumentCaptor.forClass(PermissionGrant.class);
        verify(repo, times(1)).save(cap.capture());
        PermissionGrant saved = cap.getValue();
        assertThat(saved.getRole()).isEqualTo("ADMIN");
        assertThat(saved.getResourceKey()).isEqualTo("weekly_reports.approve");
        assertThat(saved.getAction()).isEqualTo("execute");
        assertThat(saved.getAllowed()).isTrue();
    }

    @Test
    @DisplayName("refreshFromDb: başka instance grant değiştirince cache DB'den tazelenir (çok-pod)")
    void refreshFromDb_picksUpExternalChange() {
        assertThat(service.allows("USER", "inventory.crud", "edit")).isFalse();   // seed
        // Başka bir pod DB'de crud=true yaptı + yeni bir grant ekledi
        when(repo.findAll()).thenReturn(List.of(
                grant("USER", "inventory.list", "view", true),
                grant("USER", "inventory.crud", "edit", true),
                grant("USER", "notes.read", "view", true)));
        service.refreshFromDb();
        assertThat(service.allows("USER", "inventory.crud", "edit")).isTrue();
        assertThat(service.allows("USER", "notes.read", "view")).isTrue();
    }

    @Test
    @DisplayName("refreshFromDb: DB değişmediyse no-op (cache aynı)")
    void refreshFromDb_noopWhenUnchanged() {
        service.refreshFromDb();   // findAll seed ile aynı
        assertThat(service.allows("USER", "inventory.list", "view")).isTrue();
        assertThat(service.allows("USER", "inventory.crud", "edit")).isFalse();
    }

    // ── Politika yükseltmesi (applyPolicyUpgrades) ────────────────────────────
    //
    // Katalog varsayilani SONRADAN gevsetildiginde mevcut kurulumlar geride kalir:
    // seedMissingDefaults yalniz EKSIK satiri ekler, var olan allowed=false satirini CEVIRMEZ.
    // Bu yuzden ayri bir yukseltme adimi var — ve yalniz INSAN ELI DEGMEMIS satirlara dokunur.

    private static PermissionGrant grantBy(String role, String key, String action,
                                           boolean allowed, String updatedBy) {
        PermissionGrant g = grant(role, key, action, allowed);
        g.setUpdatedBy(updatedBy);
        return g;
    }

    @Test
    @DisplayName("Yukseltme: tohumlanmis (system) KAPALI satiri ACAR")
    void policyUpgrade_flipsSystemSeededRow() {
        var edit = grantBy("USER", "monitoring.scripted", "edit", false, "system");
        var exec = grantBy("USER", "monitoring.scripted", "execute", false, "system");
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "edit"))
                .thenReturn(Optional.of(edit));
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "execute"))
                .thenReturn(Optional.of(exec));

        service.applyPolicyUpgrades();

        assertThat(edit.getAllowed()).isTrue();
        assertThat(exec.getAllowed()).isTrue();
        verify(repo, times(2)).save(any(PermissionGrant.class));
    }

    @Test
    @DisplayName("Yukseltme INSAN kararina DOKUNMAZ (yonetici kapattiysa kapali kalir)")
    void policyUpgrade_respectsAdminDecision() {
        // EN KRITIK DAVRANIS: bir yonetici bu yetkiyi bilincli kapattiysa migration onu EZMEZ.
        // Ayrica kendini sinirlar — yukseltmeden sonra kapatilirsa updated_by artik o yonetici
        // olur ve bir daha asla geri acilmaz; aksi halde her acilista yoneticiyle kavga ederdi.
        var edit = grantBy("USER", "monitoring.scripted", "edit", false, "ali.veli");
        var exec = grantBy("USER", "monitoring.scripted", "execute", false, "ali.veli");
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "edit"))
                .thenReturn(Optional.of(edit));
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "execute"))
                .thenReturn(Optional.of(exec));

        service.applyPolicyUpgrades();

        assertThat(edit.getAllowed()).isFalse();
        assertThat(exec.getAllowed()).isFalse();
        verify(repo, never()).save(any(PermissionGrant.class));
    }

    @Test
    @DisplayName("Yukseltme IDEMPOTENT: zaten acik satirda yazma YAPMAZ")
    void policyUpgrade_isIdempotent() {
        var edit = grantBy("USER", "monitoring.scripted", "edit", true, "system");
        var exec = grantBy("USER", "monitoring.scripted", "execute", true, "system");
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "edit"))
                .thenReturn(Optional.of(edit));
        when(repo.findByRoleAndResourceKeyAndAction("USER", "monitoring.scripted", "execute"))
                .thenReturn(Optional.of(exec));

        service.applyPolicyUpgrades();

        verify(repo, never()).save(any(PermissionGrant.class));
    }

    @Test
    @DisplayName("Satir HIC YOKSA yukseltme uretmez (onu seedMissingDefaults ekler)")
    void policyUpgrade_skipsMissingRow() {
        when(repo.findByRoleAndResourceKeyAndAction(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.applyPolicyUpgrades();

        verify(repo, never()).save(any(PermissionGrant.class));
    }

    // ── 2026-09-28: AUDIT (7/24 izleme ekibi) → noc_calls.write varsayılanı ──────────────────
    //
    // Yükseltmede üç kurulum durumu var ve üçü de burada pinli:
    //  (1) v20.87.0 çalışmış kurulum: satır VAR, allowed=false, updated_by='system' → AÇILIR.
    //  (2) Yönetici satırı elle değiştirmiş (kapatmış ya da açıp kapatmış): updated_by = kişi → DOKUNULMAZ.
    //  (3) Satır hiç yok (v20.87.0 öncesinden doğrudan yükseltme): seedMissingDefaults katalogdan AÇIK ekler.

    @Test
    @DisplayName("AUDIT noc_calls.write: v20.87.0'ın tohumladığı KAPALI (system) satır yükseltmede AÇILIR")
    void policyUpgrade_auditNocCallsWrite_flipsSeededRow() {
        var row = grantBy("AUDIT", "noc_calls.write", "edit", false, "system");
        when(repo.findByRoleAndResourceKeyAndAction(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.findByRoleAndResourceKeyAndAction("AUDIT", "noc_calls.write", "edit")).thenReturn(Optional.of(row));

        service.applyPolicyUpgrades();

        assertThat(row.getAllowed()).isTrue();
        assertThat(row.getUpdatedBy()).isEqualTo("system");   // yükseltme insan kararı gibi görünmez
        verify(repo).save(row);
    }

    @Test
    @DisplayName("AUDIT noc_calls.write: yönetici elle KAPATTIYSA yükseltme dokunmaz (her açılışta kavga yok)")
    void policyUpgrade_auditNocCallsWrite_respectsAdminDecision() {
        var row = grantBy("AUDIT", "noc_calls.write", "edit", false, "yonetici.a");
        when(repo.findByRoleAndResourceKeyAndAction(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.findByRoleAndResourceKeyAndAction("AUDIT", "noc_calls.write", "edit")).thenReturn(Optional.of(row));

        service.applyPolicyUpgrades();

        assertThat(row.getAllowed()).isFalse();
        verify(repo, never()).save(any(PermissionGrant.class));
    }

    @Test
    @DisplayName("AUDIT noc_calls.write: satırı hiç olmayan kurulumda seedMissingDefaults AÇIK ekler (alerts.actions eklenmez)")
    void seedMissing_auditNocCallsWrite_addedOpen() {
        when(repo.findByRoleAndResourceKeyAndAction(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new PermissionGrant()));
        when(repo.findByRoleAndResourceKeyAndAction("AUDIT", "noc_calls.write", "edit")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.seedMissingDefaults();

        ArgumentCaptor<PermissionGrant> cap = ArgumentCaptor.forClass(PermissionGrant.class);
        verify(repo, times(1)).save(cap.capture());
        PermissionGrant saved = cap.getValue();
        assertThat(saved.getRole()).isEqualTo("AUDIT");
        assertThat(saved.getResourceKey()).isEqualTo("noc_calls.write");
        assertThat(saved.getAction()).isEqualTo("edit");
        assertThat(saved.getAllowed()).isTrue();
        assertThat(saved.getUpdatedBy()).isEqualTo("system");
    }
    @Test
    @DisplayName("7/24 operatörü (takım üyeliği, 2026-10-04): dinamik OKUMA izinleri + arama kaydı; yazma/yönetim izni YOK; snapshot üst üste biner")
    void nocOperatorDynamicGrants() {
        org.springframework.mock.web.MockHttpSession op = new org.springframework.mock.web.MockHttpSession();
        op.setAttribute("systemRole", "USER");
        op.setAttribute(com.sitemonitor.controller.SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        org.springframework.mock.web.MockHttpSession plain = new org.springframework.mock.web.MockHttpSession();
        plain.setAttribute("systemRole", "USER");

        // Rol satırı (yukarıdaki tohum) yalnız inventory.list'i açıyor: operatöre dinamik izinler eklenir
        assertThat(service.allows(op, "alerts.read", "view")).isTrue();
        assertThat(service.allows(op, "noc_calls.write", "edit")).isTrue();
        assertThat(service.allows(op, "incidents.view", "view")).isTrue();
        assertThat(service.allows(plain, "alerts.read", "view")).isFalse();
        assertThat(service.allows(plain, "noc_calls.write", "edit")).isFalse();
        // Yazma/yönetim izinleri dinamik listede YOK
        assertThat(service.allows(op, "inventory.crud", "edit")).isFalse();
        assertThat(service.allows(op, "alerts.actions", "execute")).isFalse();
        assertThat(service.allows(op, "audit_log.read", "view")).isFalse();
        assertThat(service.allows(op, "system_health.read", "view")).isFalse();
        // require() aynı kuralı izler
        service.require(op, "monitoring.read", "view");
        assertThatThrownBy(() -> service.require(op, "monitoring.crud", "edit")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.require(plain, "monitoring.read", "view")).isInstanceOf(SecurityException.class);
        // Bayrak yalnız Boolean.TRUE iken sayılır
        org.springframework.mock.web.MockHttpSession bogus = new org.springframework.mock.web.MockHttpSession();
        bogus.setAttribute("systemRole", "USER");
        bogus.setAttribute(com.sitemonitor.controller.SessionScope.ATTR_NOC_OPERATOR, "true");
        assertThat(service.allows(bogus, "alerts.read", "view")).isFalse();

        var snap = service.snapshotForSession(op);
        assertThat(snap.get("noc_calls.write").get("edit")).isTrue();
        assertThat(snap.get("alerts.read").get("view")).isTrue();
        assertThat(snap.get("inventory.list").get("view")).isTrue();            // rol satırı korunur
        assertThat(snap.get("inventory.crud").get("edit")).isFalse();
        assertThat(service.snapshotForSession(plain)).doesNotContainKey("noc_calls.write");
    }
}
