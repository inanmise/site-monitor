package com.sitemonitor.service;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.forward.AlertForwardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi kancaları (2026-11-05): takım silme sahibi / alıcısı olduğu iletimleri takım satırından ÖNCE, AYNI işlemde siler;
 * kullanıcı silme seçilen üye listelerinden çıkarır; takım silme reddedilirse iletimlere dokunulmaz; servis yoksa (birim testi /
 * eski kurulum) bugünkü davranış.
 */
class UserServiceAlertForwardHookTest {

    final AppUserRepository userRepo = mock(AppUserRepository.class);
    final TeamRepository teamRepo = mock(TeamRepository.class);
    final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    final EscalationContactRepository contactRepo = mock(EscalationContactRepository.class);
    final AlertForwardService forwards = mock(AlertForwardService.class);
    final UserService service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, mock(PasswordHistoryRepository.class));

    @Test
    @DisplayName("takım silme: iletimler takım satırından önce silinir")
    void deleteTeamDeletesForwardsFirst() {
        service.setAlertForwards(forwards);
        service.deleteTeam(3L);
        InOrder o = inOrder(forwards, teamRepo);
        o.verify(forwards).deleteForTeam(3L);
        o.verify(teamRepo).deleteById(3L);
    }

    @Test
    @DisplayName("takım silme reddedilirse (aktif sertifika) iletimlere dokunulmaz")
    void refusedDeleteKeepsForwards() {
        service.setAlertForwards(forwards);
        when(inventoryRepo.existsByTeamIdAndActiveTrueAndDeletedAtIsNull(3L)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteTeam(3L)).isInstanceOf(IllegalStateException.class);
        verify(forwards, never()).deleteForTeam(anyLong());
    }

    @Test
    @DisplayName("kullanıcı silme: seçilen üye listelerinden çıkarılır; servis yokken hata yok")
    void deleteUserRemovesFromMembers() {
        service.setAlertForwards(forwards);
        service.deleteUser(42L);
        verify(forwards).removeUser(42L);
        service.setAlertForwards(null);
        service.deleteUser(43L);
        verify(userRepo).deleteById(43L);
    }
}
