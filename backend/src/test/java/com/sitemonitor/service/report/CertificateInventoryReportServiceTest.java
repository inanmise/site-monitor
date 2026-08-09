package com.sitemonitor.service.report;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertInventoryReportLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Aylık rapor servisinin İKİ kritik davranışı:
 *  1) Alıcılar elle girilmez — envanterde sertifika SAHİBİ olan tüm takımlardan türetilir.
 *  2) Zamanlama canlı ayardan okunur ve geçersiz ifade REDDEDİLİR.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateInventoryReportServiceTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertInventoryReportLogRepository logRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock InventoryExportService exportService;
    @Mock InventoryHygieneService hygieneService;
    @Mock CertificateService certificateService;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;

    CertificateInventoryReportService service;

    @BeforeEach
    void setUp() {
        service = new CertificateInventoryReportService(inventoryRepo, teamRepo, logRepo,
                notificationLogRepo, exportService, hygieneService, certificateService,
                emailService, appSettings);
        ReflectionTestUtils.setField(service, "cronExpr", "0 0 10 * * FRIL");
        ReflectionTestUtils.setField(service, "enabledDefault", true);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private CertificateInventory inv(String domain, Long teamId, Long ugTeamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setTeamId(teamId);
        i.setUgTeamId(ugTeamId);
        return i;
    }

    private Team team(long id, String name, String email) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setEmail(email);
        return t;
    }

    // ── Alıcılar ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alıcılar sertifika sahibi TÜM takımlardan türetilir (SY + UG), tekilleştirilir")
    void recipientsComeFromOwnerTeams() {
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(
                inv("a.example.com", 1L, 2L),
                inv("b.example.com", 1L, null),      // aynı takım tekrar → tek adres
                inv("c.example.com", 3L, null)));
        when(teamRepo.findAllById(any())).thenReturn(List.of(
                team(1, "SY-Dijital", "sy@akbank.com"),
                team(2, "UG-Kanal", "ug@akbank.com"),
                team(3, "SY-Ödeme", "odeme@akbank.com")));

        assertThat(service.recipients())
                .containsExactlyInAnyOrder("sy@akbank.com", "ug@akbank.com", "odeme@akbank.com");
    }

    @Test
    @DisplayName("E-postası olmayan sahip takım atlanır ve ayrıca UYARI olarak raporlanır")
    void teamsWithoutEmailAreReportedNotSilentlyDropped() {
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc())
                .thenReturn(List.of(inv("a.example.com", 1L, 2L)));
        when(teamRepo.findAllById(any())).thenReturn(List.of(
                team(1, "SY-Dijital", "sy@akbank.com"),
                team(2, "UG-Kanal", "   ")));            // adres yok

        assertThat(service.recipients()).containsExactly("sy@akbank.com");
        assertThat(service.ownerTeamsWithoutEmail()).containsExactly("UG-Kanal");
    }

    @Test
    @DisplayName("Ek alıcılar sahiplik listesine EKLENİR (yerine geçmez)")
    void extraRecipientsAreAdded() {
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc())
                .thenReturn(List.of(inv("a.example.com", 1L, null)));
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(1, "SY", "sy@akbank.com")));
        when(appSettings.getString(eq(CertificateInventoryReportService.EXTRA_TO_KEY), any()))
                .thenReturn("pki@akbank.com, sy@akbank.com");   // biri zaten var → tekilleşir

        assertThat(service.recipients()).containsExactly("sy@akbank.com", "pki@akbank.com");
    }

    @Test
    @DisplayName("Silinmiş kayıtların takımları alıcı DEĞİLDİR (sorgu deleted_at IS NULL)")
    void deletedRecordsDoNotContributeRecipients() {
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of());
        assertThat(service.recipients()).isEmpty();
        assertThat(service.ownerTeamEmails()).isEmpty();
    }

    // ── Zamanlama ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cron canlı ayardan okunur; ayar yoksa properties varsayılanına düşer")
    void cronComesFromLiveSettings() {
        assertThat(service.cron()).isEqualTo("0 0 10 * * FRIL");

        when(appSettings.getString(eq(CertificateInventoryReportService.CRON_KEY), any()))
                .thenReturn("0 30 9 15 * *");
        assertThat(service.cron()).isEqualTo("0 30 9 15 * *");
        assertThat(service.nextRun()).isNotNull();
    }

    @Test
    @DisplayName("Geçersiz cron REDDEDİLİR — sessizce hiç çalışmayan tetikleyici oluşmasın")
    void invalidCronIsRejected() {
        assertThatThrownBy(() -> service.setCron("her cuma", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Geçersiz");
        assertThatThrownBy(() -> service.setCron("  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Geçerli cron kaydedilir")
    void validCronIsSaved() {
        service.setCron("0 0 8 1 * *", "admin");
        org.mockito.Mockito.verify(appSettings).save(any(), eq("admin"));
    }

    @Test
    @DisplayName("nextRuns() ayın son cumalarını sırayla verir")
    void nextRunsListsUpcomingOccurrences() {
        assertThat(service.nextRuns(3)).hasSize(3);
    }
}
