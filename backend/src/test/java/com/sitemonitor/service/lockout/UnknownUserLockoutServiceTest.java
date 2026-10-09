package com.sitemonitor.service.lockout;

import com.sitemonitor.model.LoginUnknownLockout;
import com.sitemonitor.repository.LoginUnknownLockoutRepository;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bilinmeyen adın kilit servisi (2026-10-09) — okuma, kademe yazımı, yarış yolları, veritabanı hatası yedeği, temizlik. */
class UnknownUserLockoutServiceTest {

    static final Instant NOW = Instant.parse("2026-10-09T08:00:00.250Z");

    LoginUnknownLockoutRepository repo;
    UnknownUserLockoutService svc;

    @BeforeEach
    void setUp() {
        repo = mock(LoginUnknownLockoutRepository.class);
        svc = new UnknownUserLockoutService(repo, () -> new LockoutLadder(List.of(30L, 120L, 600L, 1800L), List.of(5, 3, 2, 1)));
        svc.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static LoginUnknownLockout row(int level, String until, String lockedAt) {
        LoginUnknownLockout r = new LoginUnknownLockout();
        r.setUsernameKey("GHOST");
        r.setLockoutLevel(level);
        r.setLockoutUntil(until);
        r.setLastLockoutAt(lockedAt);
        return r;
    }

    @Test
    @DisplayName("Satır yoksa: kilit yok, 0. kademe bağlamı (sayım başı yok, eşik 5)")
    void noRow() {
        when(repo.findById("GHOST")).thenReturn(Optional.empty());
        assertThat(svc.status("GHOST").isBlocked()).isFalse();
        assertThat(svc.context("GHOST")).isEqualTo(new UnknownUserLockoutService.Context(null, 5));
    }

    @Test
    @DisplayName("Satır varsa: kalan süre mevcut hesapla aynı hesap, bağlam = son kilit anı + kademenin eşiği")
    void rowDrivesStatusAndContext() {
        when(repo.findById("GHOST")).thenReturn(Optional.of(row(2, "2026-10-09T08:02:00", "2026-10-09T08:00:00")));
        UserService.LockoutStatus st = svc.status("GHOST");
        assertThat(st.permanent()).isFalse();
        assertThat(st.secondsRemaining()).isEqualTo(119);
        assertThat(svc.context("GHOST")).isEqualTo(new UnknownUserLockoutService.Context("2026-10-09T08:00:00", 2));
    }

    @Test
    @DisplayName("İlk kilit: INSERT … ON CONFLICT DO NOTHING ile 1. kademe, 30 sn")
    void firstEscalationInserts() {
        when(repo.findById("GHOST")).thenReturn(Optional.empty());
        when(repo.insertStepIfAbsent(anyString(), anyInt(), any(), any(), any())).thenReturn(1);

        UserService.LockoutStatus st = svc.escalate("GHOST");

        assertThat(st.secondsRemaining()).isEqualTo(30);
        verify(repo).insertStepIfAbsent("GHOST", 1, "2026-10-09T08:00:30", "2026-10-09T08:00:00", "2026-10-09T08:00:00");
        verify(repo, never()).updateStep(anyString(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Yarış: başka pod satırı arada yarattıysa (INSERT 0) aynı değerler UPDATE ile yazılır")
    void insertRaceFallsBackToUpdate() {
        when(repo.findById("GHOST")).thenReturn(Optional.empty());
        when(repo.insertStepIfAbsent(anyString(), anyInt(), any(), any(), any())).thenReturn(0);
        when(repo.updateStep(anyString(), anyInt(), any(), any(), any())).thenReturn(1);

        assertThat(svc.escalate("GHOST").secondsRemaining()).isEqualTo(30);
        verify(repo).updateStep("GHOST", 1, "2026-10-09T08:00:30", "2026-10-09T08:00:00", "2026-10-09T08:00:00");
    }

    @Test
    @DisplayName("Sonraki kademe: UPDATE; son kademeden sonra son süre yinelenir")
    void laterEscalationUpdates() {
        when(repo.findById("GHOST")).thenReturn(Optional.of(row(4, "2026-10-09T07:00:00", "2026-10-09T06:30:00")));
        when(repo.updateStep(anyString(), anyInt(), any(), any(), any())).thenReturn(1);

        assertThat(svc.escalate("GHOST").secondsRemaining()).isEqualTo(1800);
        verify(repo).updateStep(eq("GHOST"), eq(5), eq("2026-10-09T08:30:00"), eq("2026-10-09T08:00:00"), any());
        verify(repo, never()).insertStepIfAbsent(anyString(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Satır arada silindiyse (UPDATE 0) yeniden yaratılır")
    void updateRaceFallsBackToInsert() {
        when(repo.findById("GHOST")).thenReturn(Optional.of(row(1, "2026-10-09T07:00:00", "2026-10-09T06:59:30")));
        when(repo.updateStep(anyString(), anyInt(), any(), any(), any())).thenReturn(0);
        when(repo.insertStepIfAbsent(anyString(), anyInt(), any(), any(), any())).thenReturn(1);

        assertThat(svc.escalate("GHOST").secondsRemaining()).isEqualTo(120);
        verify(repo).insertStepIfAbsent(eq("GHOST"), eq(2), any(), any(), any());
    }

    @Test
    @DisplayName("Veritabanı hatası: okuma 0. kademe, yazma bellek içi 1. kademe — istisna fırlamaz; kalan süre bellekten")
    void dbDownFallsBackToMemoryLevelOne() {
        var down = new DataAccessResourceFailureException("yok");
        when(repo.findById(anyString())).thenThrow(down);

        assertThat(svc.context("GHOST")).isEqualTo(new UnknownUserLockoutService.Context(null, 5));
        assertThat(svc.status("GHOST").isBlocked()).isFalse();
        assertThat(svc.escalate("GHOST").secondsRemaining()).isEqualTo(30);
        assertThat(svc.status("GHOST").secondsRemaining()).isEqualTo(29);   // bitiş saniyeye kırpılmış: 30 − 0,25 → 29
        assertThat(svc.status("OTHER").isBlocked()).isFalse();
    }

    @Test
    @DisplayName("Yalnız bellek örneği (bean yok): 1. kademe davranışı, veritabanına dokunmaz")
    void memoryOnly() {
        UnknownUserLockoutService mem = UnknownUserLockoutService.memoryOnly(() -> new LockoutLadder(null, null));
        assertThat(mem.context("X").failuresNeeded()).isEqualTo(5);
        assertThat(mem.escalate("X").secondsRemaining()).isEqualTo(30);
        assertThat(mem.escalate("X").secondsRemaining()).isEqualTo(30);   // hep 1. kademe
        assertThat(mem.status("X").isBlocked()).isTrue();
        assertThat(mem.purge()).isZero();
    }

    @Test
    @DisplayName("Anahtar sütuna sığar (100 karakter)")
    void keyTruncated() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        svc.status("Y".repeat(150));
        verify(repo).findById("Y".repeat(100));
    }

    @Test
    @DisplayName("Temizlik: saklama süresinden eski satırlar + üst sınırı aşan en eskiler")
    void purgeAgeAndCap() {
        svc.setLimits(365, 1000);
        when(repo.deleteUpdatedBefore(anyString())).thenReturn(4);
        when(repo.count()).thenReturn(1250L);
        when(repo.deleteOldest(250)).thenReturn(250);

        assertThat(svc.purge()).isEqualTo(254);
        verify(repo).deleteUpdatedBefore("2025-10-09T08:00:00");
        verify(repo).deleteOldest(250);
    }

    @Test
    @DisplayName("Temizlik: sınır altındaysa en eskiler silinmez; hata yutulur")
    void purgeUnderCapAndErrors() {
        svc.setLimits(30, 1000);
        when(repo.count()).thenReturn(999L);
        assertThat(svc.purge()).isZero();
        verify(repo, never()).deleteOldest(anyInt());

        when(repo.deleteUpdatedBefore(anyString())).thenThrow(new DataAccessResourceFailureException("yok"));
        assertThat(svc.purge()).isZero();
    }
}
