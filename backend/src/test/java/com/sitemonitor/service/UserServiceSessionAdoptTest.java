package com.sitemonitor.service;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression: ISSUE-002 — restart sonrası JDBC oturumu yaşayan kullanıcı "Aktif Oturum"dan düşüyordu
 * (açılış temizliği activeSessionId'yi NULL yapar, ping'in UPDATE'i sid eşleşmediği için 0 satır etkiler).
 * Found by /qa on 2026-09-13
 * Report: .gstack/qa-reports/qa-report-localhost-2026-09-13.md
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceSessionAdoptTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        ReflectionTestUtils.setField(service, "activeWindowSeconds", 120L);
        ReflectionTestUtils.setField(service, "touchDebounceMs", 0L);
    }

    @Test
    @DisplayName("ping: işaret NULL (restart temizliği) → oturum yeniden sahiplenilir")
    void pingReadoptsClearedSession() {
        when(userRepo.touchLastSeen(eq("admin"), eq("sid-1"), anyString())).thenReturn(0);
        when(userRepo.adoptSessionIfNone(eq("admin"), eq("sid-1"), anyString())).thenReturn(1);
        service.touchActiveSession("admin", "sid-1");
        verify(userRepo).adoptSessionIfNone(eq("admin"), eq("sid-1"), anyString());
    }

    @Test
    @DisplayName("ping: işaret eşleşiyorsa yalnız tazeleme, sahiplenme denenmez")
    void pingTouchOnlyWhenMarkerMatches() {
        when(userRepo.touchLastSeen(eq("admin"), eq("sid-1"), anyString())).thenReturn(1);
        service.touchActiveSession("admin", "sid-1");
        verify(userRepo, never()).adoptSessionIfNone(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("ping: başka işaret varken (süpersede/kick) sahiplenme 0 satır — eski oturum geri gelmez")
    void pingDoesNotStealAnotherMarker() {
        when(userRepo.touchLastSeen(eq("admin"), eq("old-sid"), anyString())).thenReturn(0);
        when(userRepo.adoptSessionIfNone(eq("admin"), eq("old-sid"), anyString())).thenReturn(0);   // WHERE activeSessionId IS NULL tutmadı
        service.touchActiveSession("admin", "old-sid");
        verify(userRepo).adoptSessionIfNone(eq("admin"), eq("old-sid"), anyString());
        verify(userRepo, never()).save(any());
    }
}
