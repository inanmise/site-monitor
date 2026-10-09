package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Alarm sahipliği (2026-10-09) — eylem kapılarının kuralı yönlendirmeyle AYNI yüklemdir
 * ({@link EscalationService#isStandaloneEvent}); ikisi ayrışırsa izin ile bildirim farklı takımları gösterir.
 */
class AlertOwnershipTest {

    private static final long A = 1L, B = 2L, C = 3L;

    private static AlertEvent ev(String type, Long teamId, String ctx) {
        AlertEvent e = new AlertEvent();
        e.setId(1L); e.setDomain("api.example.com"); e.setAlertType(type); e.setTeamId(teamId); e.setContextJson(ctx);
        return e;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PING_DOWN", "PING_SLOW", "HTTP_DOWN", "HTTP_SLOW", "KEYWORD", "PAGE_DOWN", "SCRIPTED_FAIL",
            "PAGESPEED_DOWN", "DOMAINMON_EXPIRY", "DOMAINMON_STATUS", "DOMAIN_EXPIRY"})
    @DisplayName("bağımsız izleme türleri envanter gibi YÖNLENMEZ — yönlendirme yüklemiyle birebir")
    void standaloneTypes_doNotRouteLikeInventory(String type) {
        AlertEvent e = ev(type, B, null);
        assertThat(AlertOwnership.routesLikeInventory(e)).isEqualTo(!EscalationService.isStandaloneEvent(e));
        assertThat(AlertOwnership.routesLikeInventory(e)).isFalse();
    }

    @Test
    @DisplayName("Port/DNS çift kaynaklı: bağlamda team_id / standalone işareti → bağımsız; damgasız türev → envanter")
    void portDns_dependOnContextMark() {
        assertThat(AlertOwnership.routesLikeInventory(ev("PORT_DOWN", B, "{\"team_id\":2,\"monitor_id\":5}"))).isFalse();
        assertThat(AlertOwnership.routesLikeInventory(ev("DNS_FAILURE", null, "{\"standalone\":true}"))).isFalse();
        assertThat(AlertOwnership.routesLikeInventory(ev("PORT_DOWN", A, "{\"monitor_id\":5}"))).isTrue();
        assertThat(AlertOwnership.routesLikeInventory(ev("DNS_FAILURE", null, null))).isTrue();
    }

    @Test
    @DisplayName("sertifika ve ACCESSIBILITY envanter gibi yönlenir")
    void certAndAccessibility_routeLikeInventory() {
        assertThat(AlertOwnership.routesLikeInventory(ev("EXPIRY", null, null))).isTrue();
        assertThat(AlertOwnership.routesLikeInventory(ev("ACCESSIBILITY", null, "{\"monitor_id\":3}"))).isTrue();
    }

    @Test
    @DisplayName("ownerTeamIds: bağımsız → yalnız damga (envanter SY/UG yok sayılır); envanter → damga + SY + UG")
    void ownerTeamIds_followRouting() {
        assertThat(AlertOwnership.ownerTeamIds(ev("PING_DOWN", B, null), A, C)).containsExactly(B);
        assertThat(AlertOwnership.ownerTeamIds(ev("PING_DOWN", null, null), A, C)).isEmpty();   // damgasız bağımsız: sahipsiz
        assertThat(AlertOwnership.ownerTeamIds(ev("EXPIRY", null, null), A, C)).containsExactlyInAnyOrder(A, C);
        assertThat(AlertOwnership.ownerTeamIds(ev("PORT_DOWN", B, "{\"monitor_id\":5}"), A, C)).containsExactlyInAnyOrder(B, A, C);
    }

    @Test
    @DisplayName("ownerTeamIds(repo): bağımsız alarmda envanter OKUNMAZ; envanter alarmında tek okuma")
    void ownerTeamIds_repoOnlyForInventoryRouted() {
        CertificateInventoryRepository repo = mock(CertificateInventoryRepository.class);
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("api.example.com"); inv.setTeamId(A); inv.setUgTeamId(C);
        when(repo.findByDomain("api.example.com")).thenReturn(Optional.of(inv));

        assertThat(AlertOwnership.ownerTeamIds(ev("HTTP_DOWN", B, null), repo)).containsExactly(B);
        verify(repo, never()).findByDomain(anyString());

        assertThat(AlertOwnership.ownerTeamIds(ev("ACCESSIBILITY", null, null), repo)).containsExactlyInAnyOrder(A, C);
        verify(repo, times(1)).findByDomain("api.example.com");
    }

    @Test
    @DisplayName("ownedByAny: kesişim; boş / null kapsam → hayır")
    void ownedByAny() {
        assertThat(AlertOwnership.ownedByAny(Set.of(A, C), List.of(C))).isTrue();
        assertThat(AlertOwnership.ownedByAny(Set.of(B), List.of(A, C))).isFalse();
        assertThat(AlertOwnership.ownedByAny(Set.of(A), null)).isFalse();
        assertThat(AlertOwnership.ownedByAny(Set.of(), List.of(A))).isFalse();
    }
}
