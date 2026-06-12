package com.certmonitor.service;

import com.certmonitor.model.PermissionGrant;
import com.certmonitor.repository.PermissionGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
