package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Müdür sicili → kullanıcı seçimi (prod hatası 2026-09-26, hipotez 6). Yanlış kişiye bağlamaktansa
 * bağlamamak; istisna fırlatmamak. Siciller yer tutucu.
 */
class ManagerLookupTest {

    private static AppUser u(long id, boolean active, String authSource) {
        AppUser a = new AppUser();
        a.setId(id);
        a.setUsername("U" + id);
        a.setEmployeeId("100001");
        a.setActive(active);
        a.setAuthSource(authSource);
        return a;
    }

    @Test
    @DisplayName("tek aday → o")
    void single() {
        assertThat(ManagerLookup.pick(List.of(u(1, true, "LDAP")), 9L, "100001")).map(AppUser::getId).contains(1L);
    }

    @Test
    @DisplayName("kişinin kendisi aday değil")
    void excludesSelf() {
        assertThat(ManagerLookup.pick(List.of(u(1, true, "LDAP")), 1L, "100001")).isEmpty();
    }

    @Test
    @DisplayName("aktif hesap pasiften önce gelir")
    void prefersActive() {
        assertThat(ManagerLookup.pick(List.of(u(1, false, "LDAP"), u(2, true, "LOCAL")), 9L, "100001"))
                .map(AppUser::getId).contains(2L);
    }

    @Test
    @DisplayName("aynı kademede iki aday → LDAP kaynaklı olan")
    void prefersLdapWithinTier() {
        assertThat(ManagerLookup.pick(List.of(u(1, true, "LOCAL"), u(2, true, "LDAP")), 9L, "100001"))
                .map(AppUser::getId).contains(2L);
    }

    @Test
    @DisplayName("iki aktif LDAP aday → BELİRSİZ, bağlanmaz (istisna yok)")
    void ambiguous_returnsEmpty() {
        assertThat(ManagerLookup.pick(List.of(u(1, true, "LDAP"), u(2, true, "LDAP")), 9L, "100001")).isEmpty();
    }

    @Test
    @DisplayName("yalnız pasif adaylar varsa aynı kural pasifler arasında")
    void onlyInactive() {
        assertThat(ManagerLookup.pick(List.of(u(1, false, "LDAP")), 9L, "100001")).map(AppUser::getId).contains(1L);
    }

    @Test
    @DisplayName("resolve: sicil kırpılarak sorulur; boş sicil depoya hiç gitmez")
    void resolve_trimsAndSkipsBlank() {
        AppUserRepository repo = mock(AppUserRepository.class);
        when(repo.findAllByEmployeeIdNormalized("100001")).thenReturn(List.of(u(1, true, "LDAP")));
        assertThat(ManagerLookup.resolve(repo, "  100001 ", null)).map(AppUser::getId).contains(1L);
        assertThat(ManagerLookup.resolve(repo, "  ", null)).isEmpty();
        verify(repo, never()).findAllByEmployeeIdNormalized("");
    }
}
