package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Yeni cihazdan giriş" bildirimi (E1).
 *
 * <p>Testlerin çoğu YANLIŞ ALARM tuzaklarını pinler. Bu bildirimin değeri güvenilirliğinde:
 * gereksiz yere çalarsa kullanıcı onu görmezden gelmeye başlar ve gerçek uyarı da kaybolur.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewDeviceNotifierTest {

    @Mock AuditLogRepository auditLogRepo;
    @Mock AppUserRepository appUserRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock GeoIpService geoIpService;

    @InjectMocks NewDeviceNotifier notifier;

    private static final String CHROME_120 =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final String CHROME_121 =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/121 Safari/537.36";
    private static final String FIREFOX_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7; rv:121.0) Gecko/20100101 Firefox/121.0";

    @BeforeEach
    void setUp() {
        when(appSettings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        AppUser u = new AppUser();
        u.setUsername("N12345");
        u.setEmail("kadir@example.com");
        when(appUserRepo.findByUsername(anyString())).thenReturn(Optional.of(u));
        when(geoIpService.isPrivateIp(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("TARAYICI GUNCELLEMESI yanlis alarm URETMEZ (Chrome/120 → Chrome/121 ayni cihazdir)")
    void browserUpdateIsNotANewDevice() {
        // ASIL TUZAK: ham UA esitligi kullanilsaydi her tarayici guncellemesi "yeni cihaz" derdi
        // ve kullanici bildirimi gormezden gelmeye baslardi.
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong()))
                .thenReturn(List.of(CHROME_120));

        notifier.notifyIfNewDevice(9L, "N12345", CHROME_121, "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GERCEKTEN yeni cihazda bildirim GIDER")
    void genuinelyNewDeviceNotifies() {
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong()))
                .thenReturn(List.of(CHROME_120));

        notifier.notifyIfNewDevice(9L, "N12345", FIREFOX_MAC, "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService).sendNewDeviceEmail(
                org.mockito.ArgumentMatchers.eq("kadir@example.com"), anyString(),
                org.mockito.ArgumentMatchers.eq("macOS · Firefox"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("ILK giris bildirilmez — gecmisi olmayan herkese anlamsiz uyari gitmesin")
    void firstEverLoginIsSilent() {
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong())).thenReturn(List.of());

        notifier.notifyIfNewDevice(9L, "N12345", CHROME_120, "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TANINMAYAN cihaz (curl/bot/prob) bildirim URETMEZ")
    void unknownAgentIsSilent() {
        // "Oturum" diye bir uyari kullaniciya hicbir sey anlatmaz; ustelik her tanimadigimiz
        // istemci posta uretirdi.
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong()))
                .thenReturn(List.of(CHROME_120));

        notifier.notifyIfNewDevice(9L, "N12345", "curl/8.4.0", "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Ayar KAPALIYKEN hicbir sorgu bile yapilmaz (opt-in)")
    void disabledByDefaultDoesNothing() {
        when(appSettings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);

        notifier.notifyIfNewDevice(9L, "N12345", FIREFOX_MAC, "10.0.0.1", "2026-08-24T10:00:00");

        verify(auditLogRepo, never()).findDistinctLoginUserAgents(anyString(), anyLong());
        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("E-postasi OLMAYAN kullanicida sessizce vazgecilir (patlamaz)")
    void userWithoutEmailIsSkipped() {
        AppUser noMail = new AppUser();
        noMail.setUsername("N12345");
        when(appUserRepo.findByUsername(anyString())).thenReturn(Optional.of(noMail));
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong()))
                .thenReturn(List.of(CHROME_120));

        notifier.notifyIfNewDevice(9L, "N12345", FIREFOX_MAC, "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Depo PATLASA BILE giris etkilenmez — hata yutulur")
    void repositoryFailureIsSwallowed() {
        when(auditLogRepo.findDistinctLoginUserAgents(anyString(), anyLong()))
                .thenThrow(new RuntimeException("DB down"));

        // Istisna DISARI CIKMAMALI: bildirim hatasi girisi engellememeli.
        notifier.notifyIfNewDevice(9L, "N12345", FIREFOX_MAC, "10.0.0.1", "2026-08-24T10:00:00");

        verify(emailService, never()).sendNewDeviceEmail(any(), any(), any(), any(), any(), any());
    }
}
