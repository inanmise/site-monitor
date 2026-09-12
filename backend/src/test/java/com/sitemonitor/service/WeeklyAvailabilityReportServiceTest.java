package com.sitemonitor.service;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Haftalık erişilebilirlik raporu — availability/kesinti hesabı (computeRow) + gönderim akışı
 * (alıcı çözümü, idempotency, devre dışı, kesinti olmasa da gönderim). Repo'lar mock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyAvailabilityReportServiceTest {

    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock AppUserRepository userRepo;
    @Mock EmailNotificationService emailService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock WeeklyAvailabilityLogRepository walRepo;
    @Mock AppSettingsService appSettings;
    @Mock com.sitemonitor.service.report.WeeklyOutageReportService outageReportService;
    @Mock com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @Mock com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @Mock DeploymentHistoryService deploymentHistory;

    private WeeklyAvailabilityReportService service;

    /**
     * "Hiç mail gitmedi" iddiası İKİ metodu birden kapsamalı.
     *
     * <p>Zamanlanmış gönderim {@code sendHtmlWithAttachments}'a geçti; yalnız {@code sendHtml}'e
     * bakan bir {@code never()} artık kod mail GÖNDERSE BİLE yeşil kalırdı — sessiz bir yalancı
     * yeşil. Tek yerde toplandı ki bir sonraki imza değişiminde de aynı tuzak kurulmasın.
     */
    private void verifyNoMailSent() {
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
        verify(emailService, never()).sendHtmlWithAttachments(any(), any(), any(), any(), any(), any());
    }

    @BeforeEach
    void setUp() {
        service = new WeeklyAvailabilityReportService(teamRepo, inventoryRepo, uptimeCheckRepo,
                latestCheckRepo, contactRepo, userRepo, emailService, notificationLogRepo, walRepo, appSettings,
                outageReportService, pageSpeedMonitorRepo, pageSpeedCheckRepo, deploymentHistory);
        when(appSettings.getBoolean(eq("site.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(true);
        when(emailService.sendHtml(any(), any(), any(), any(), any())).thenReturn("SENT");
        when(emailService.sendHtmlWithAttachments(any(), any(), any(), any(), any(), any())).thenReturn("SENT");
        when(outageReportService.collect(any(), any(), any())).thenReturn(outageData(3, 1));
        when(outageReportService.pdf(any())).thenReturn(new byte[]{ 1, 2, 3 });
        when(emailService.buildWeeklyAvailabilityHtml(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("<html></html>");
        // Varsayılan: dağıtım geçmişi boş → "dağıtım yapılmadı" satırı (rapor yine gider).
        when(deploymentHistory.currentEnvironment()).thenReturn("prod");
        when(deploymentHistory.derivedFor(any())).thenReturn(java.util.List.of());
        // Varsayılan: takımın sayfa hızı izlemesi yok → bölüm hiç çizilmez.
        when(pageSpeedMonitorRepo.findByActiveTrue()).thenReturn(java.util.List.of());
        when(emailService.getEmailFrom()).thenReturn("noreply@sitemonitor");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(latestCheckRepo.findById(anyString())).thenReturn(Optional.empty());
    }

    /**
     * Asgari kesinti verisi — yalnız gövdedeki ek bandının okuduğu alanlar dolu.
     *
     * <p>Kayıt geniş (28 alan) ama bu testler onun İÇERİĞİYLE ilgilenmiyor; ilgilendikleri şey
     * verinin bir kez toplanıp hem gövdeye hem PDF'e verilmesi. Alanları tek tek doldurmak testi
     * kırılganlaştırır: kayda yeni bir alan eklendiğinde burası da değişmek zorunda kalırdı.
     */
    private static com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData outageData(
            int totalAlarms, int stillOpen) {
        return new com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData(
                "Dijital", "15–21 Haziran 2026", "22.06.2026 10:00",
                totalAlarms, stillOpen, 0, 2, 120, 99.5, 1, totalAlarms, 0, totalAlarms,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    private UptimeCheck uc(String status, Long ms, String checkedAt) {
        UptimeCheck c = new UptimeCheck();
        c.setDomain("x"); c.setPort(443);
        c.setStatus(status); c.setResponseMs(ms); c.setCheckedAt(checkedAt);
        return c;
    }

    private UptimeCheck ucMaint(String status, String checkedAt) {
        UptimeCheck c = uc(status, null, checkedAt);
        c.setMaintenance(true);
        return c;
    }

    @Test
    @DisplayName("computeRow: availability %, kesinti sayısı/süresi, ort/p95")
    void computeRow_basic() {
        List<UptimeCheck> checks = List.of(
                uc("up",   100L, "2026-06-15T00:00:00"),
                uc("up",   200L, "2026-06-15T00:01:00"),
                uc("down", null, "2026-06-15T00:02:00"),
                uc("down", null, "2026-06-15T00:03:00"),
                uc("up",   300L, "2026-06-15T00:04:00"));
        Instant windowEnd = Instant.parse("2026-06-15T00:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, 45);

        assertThat(r.availabilityPct()).isEqualTo(60.0);     // 3 up / 5
        assertThat(r.outageCount()).isEqualTo(1);
        assertThat(r.downtimeMinutes()).isEqualTo(2);        // 00:02 → 00:04 kurtarma
        assertThat(r.longestOutageMinutes()).isEqualTo(2);
        assertThat(r.avgMs()).isEqualTo(200);                // (100+200+300)/3
        assertThat(r.p95Ms()).isEqualTo(300);
        assertThat(r.certDaysRemaining()).isEqualTo(45);
    }

    @Test
    @DisplayName("computeRow: kurtarmasız kesinti pencere sonuna kadar sayılır")
    void computeRow_unresolvedOutage() {
        List<UptimeCheck> checks = List.of(
                uc("up",   100L, "2026-06-15T00:00:00"),
                uc("down", null, "2026-06-15T00:02:00"),
                uc("down", null, "2026-06-15T00:03:00"));
        Instant windowEnd = Instant.parse("2026-06-15T00:10:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        assertThat(r.outageCount()).isEqualTo(1);
        assertThat(r.downtimeMinutes()).isEqualTo(8);        // 00:02 → 00:10 (pencere sonu)
        assertThat(r.availabilityPct()).isEqualTo(33.33);    // 1 up / 3 (2 ondalık)
    }

    @Test
    @DisplayName("computeRow: bakım süresi downtime dakikalarına SAYILMAZ (yalnız %'den değil) (M2)")
    void computeRow_maintenanceExcludedFromDowntime() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım başladı
                ucMaint("down", "2026-06-15T10:30:00"),
                ucMaint("down", "2026-06-15T10:55:00"),      // bakım penceresi
                uc("up",   100L, "2026-06-15T11:00:00"));
        Instant windowEnd = Instant.parse("2026-06-15T11:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        // Kesinti yalnız 10:00 → 10:05 = 5 dk (bakımdaki 50 dk HARİÇ). Hatalı kod 10:00 → 11:00 = 60 dk sayardı.
        assertThat(r.downtimeMinutes()).isEqualTo(5);
        assertThat(r.longestOutageMinutes()).isEqualTo(5);
        // % yalnız bakım-dışı örneklerden: 1 up / 2 = %50.
        assertThat(r.availabilityPct()).isEqualTo(50.0);
        assertThat(r.outageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("computeRow: bakımı köprüleyen TEK kesinti tek sayılır (M2-COUNT); bakım dakikaları yine hariç")
    void computeRow_outageBridgingMaintenanceCountedOnce() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                uc("down", null, "2026-06-15T10:02:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım kesintiyi böler ama KAPATMAZ
                ucMaint("down", "2026-06-15T10:30:00"),
                uc("down", null, "2026-06-15T10:55:00"),      // bakımdan SONRA hâlâ down → aynı kesinti
                uc("down", null, "2026-06-15T10:58:00"),
                uc("up",   100L, "2026-06-15T11:00:00"));
        Instant windowEnd = Instant.parse("2026-06-15T11:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        // Tek sürekli kesinti (bakımla bölünmüş) → 1 sayılır, 2 DEĞİL. (M2-COUNT öncesi kod 2 sayardı.)
        assertThat(r.outageCount()).isEqualTo(1);
        // Süre: 10:00→10:05 (5dk) + 10:55→11:00 (5dk) = 10dk; aradaki 50dk bakım HARİÇ.
        assertThat(r.downtimeMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("computeRow: bakım + araya GERÇEK kurtarma (up) → 2 ayrı kesinti sayılır")
    void computeRow_recoveryBetweenMaintenanceCountsTwo() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım (kesintiyi böler)
                uc("up",   100L, "2026-06-15T10:30:00"),      // GERÇEK kurtarma → kesinti kapanır
                uc("up",   100L, "2026-06-15T10:35:00"),
                uc("down", null, "2026-06-15T10:40:00"),      // yeni kesinti (araya kurtarma girdi)
                uc("up",   100L, "2026-06-15T10:45:00"));
        Instant windowEnd = Instant.parse("2026-06-15T10:50:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);
        assertThat(r.outageCount()).isEqualTo(2);   // kurtarma araya girdiği için köprüleme YOK
    }

    @Test
    @DisplayName("computeRow: veri yoksa availability null (ortalamaya katılmaz)")
    void computeRow_noData() {
        AvailabilityRow r = service.computeRow("x", List.of(), Instant.now(), null);
        assertThat(r.availabilityPct()).isNull();
        assertThat(r.outageCount()).isZero();
        assertThat(r.avgMs()).isNull();
    }

    @Test
    @DisplayName("computeRow: tek down örnek (2015/2016) 100'e yuvarlanmaz → %99.95 (2 ondalık)")
    void computeRow_singleDownNotRoundedTo100() {
        List<UptimeCheck> checks = new java.util.ArrayList<>();
        for (int i = 0; i < 2015; i++) checks.add(uc("up", 100L, "2026-06-15T00:00:00"));
        checks.add(uc("down", null, "2026-06-21T23:00:00"));
        AvailabilityRow r = service.computeRow("x", checks, Instant.parse("2026-06-22T00:00:00Z"), null);
        assertThat(r.availabilityPct()).isEqualTo(99.95);   // ÖNCEDEN 100.0 görünüyordu
        assertThat(r.outageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("computeRow: down varsa availability asla 100.00 değil — clamp 99.99")
    void computeRow_clampNever100() {
        List<UptimeCheck> checks = new java.util.ArrayList<>();
        for (int i = 0; i < 20000; i++) checks.add(uc("up", 100L, "2026-06-15T00:00:00"));
        checks.add(uc("down", null, "2026-06-21T23:00:00"));  // 20000/20001 → yuvarlama 100.00 → clamp
        AvailabilityRow r = service.computeRow("x", checks, Instant.parse("2026-06-22T00:00:00Z"), null);
        assertThat(r.availabilityPct()).isEqualTo(99.99);
    }

    @Test
    @DisplayName("send: takım kutusu TO + PO/MANAGER kontakları CC; kesinti olmasa da gönderir")
    void send_toTeamWithCc_alwaysSends() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        // hepsi up → kesinti yok ama yine de gönderilmeli
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO"))
                .thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> ccCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtmlWithAttachments(toCap.capture(), ccCap.capture(), anyString(), anyString(), any(), any());
        assertThat(toCap.getValue()).containsExactly("dijital@x.com");
        assertThat(ccCap.getValue()).contains("po@x.com");
        verify(walRepo).save(any(WeeklyAvailabilityLog.class));
        assertThat(result.sent()).isEqualTo(1);
    }

    // ── Haftalık kesinti PDF'i (mail eki) ────────────────────────────────────

    @Test
    @DisplayName("Kesinti PDF'i EK olarak iliştirilir; toplayıcı e-postanın HESAPLADIĞI satırları alır")
    void send_attachesOutagePdf() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        service.sendWeeklyReports(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> attCap =
                ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), attCap.capture());
        assertThat(attCap.getValue()).hasSize(1);
        var att = attCap.getValue().get(0);
        assertThat(att.contentType()).isEqualTo("application/pdf");
        assertThat(att.fileName()).startsWith("haftalik-kesinti-raporu_dijital_").endsWith(".pdf");
        assertThat(att.data()).isNotEmpty();

        // Erişilebilirlik satırları YENİDEN hesaplanmaz — e-postanın ürettiği liste toplayıcıya geçer.
        // Aksi halde 200-1000+ domainlik uptime sorguları tek podda ikinci kez koşardı.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AvailabilityRow>> rowsCap = ArgumentCaptor.forClass(List.class);
        verify(outageReportService).collect(any(), any(), rowsCap.capture());
        assertThat(rowsCap.getValue()).hasSize(2);
        assertThat(rowsCap.getValue()).extracting(AvailabilityRow::domain)
                .containsExactlyInAnyOrder("a.com", "b.com");
    }

    @Test
    @DisplayName("Gövde EKİ DUYURUR: dosya adı ve içeriği e-posta HTML'ine yazılır")
    void send_bodyAnnouncesTheAttachment() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        service.sendWeeklyReports(false);

        // Ek sessizce iliştirilseydi okuyanların çoğu — özellikle telefonda — fark etmezdi.
        ArgumentCaptor<EmailNotificationService.AttachmentInfo> attCap =
                ArgumentCaptor.forClass(EmailNotificationService.AttachmentInfo.class);
        verify(emailService).buildWeeklyAvailabilityHtml(any(), any(), any(), any(), attCap.capture(), any(), any(), any());
        assertThat(attCap.getValue()).isNotNull();
        assertThat(attCap.getValue().fileName()).endsWith(".pdf");
        assertThat(attCap.getValue().monitorTypeCount()).isEqualTo(MonitorTypeCatalog.ORDER.size());
    }

    @Test
    @DisplayName("Kesinti verisi bir KEZ toplanır — gövde bandı ve PDF aynı veriyi kullanır")
    void send_collectsOutageDataOnlyOnce() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        service.sendWeeklyReports(false);

        // Gövde bandı ekin içeriğini yazdığı için veri HTML'den ÖNCE toplanmak zorunda; sonra PDF
        // için yeniden toplansaydı bütün alarm sorguları tek podda İKİ KEZ koşardı.
        verify(outageReportService, times(1)).collect(any(), any(), any());
        verify(outageReportService, times(1)).pdf(any());
    }

    @Test
    @DisplayName("Veri toplanamazsa gövde ekten SÖZ ETMEZ — olmayan bir eke atıf yapılmaz")
    void send_collectFailure_bodyDoesNotMentionAttachment() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(outageReportService.collect(any(), any(), any())).thenThrow(new RuntimeException("patladı"));

        var result = service.sendWeeklyReports(false);

        ArgumentCaptor<EmailNotificationService.AttachmentInfo> attCap =
                ArgumentCaptor.forClass(EmailNotificationService.AttachmentInfo.class);
        verify(emailService).buildWeeklyAvailabilityHtml(any(), any(), any(), any(), attCap.capture(), any(), any(), any());
        assertThat(attCap.getValue()).isNull();      // gövdede ek bandı çizilmez
        verify(outageReportService, never()).pdf(any());
        assertThat(result.sent()).isEqualTo(1);      // rapor yine gitti
    }

    @Test
    @DisplayName("PDF ÜRETİLEMEZSE mail EK OLMADAN yine gider — rapor bir ek hatası yüzünden düşmez")
    void send_pdfFailure_stillSendsMailWithoutAttachment() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        // Toplayıcı patlıyor (veritabanı hatası, bozuk veri, ne olursa)
        when(outageReportService.collect(any(), any(), any()))
                .thenThrow(new RuntimeException("kesinti toplama patladı"));

        var result = service.sendWeeklyReports(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> attCap =
                ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), attCap.capture());
        assertThat(attCap.getValue()).isEmpty();     // ek yok
        assertThat(result.sent()).isEqualTo(1);      // ama rapor GİTTİ
    }

    @Test
    @DisplayName("PDF boş dönerse (üretim düştü) ek iliştirilmez — 0 baytlık dosya gönderilmez")
    void send_emptyPdf_producesNoAttachment() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(outageReportService.pdf(any())).thenReturn(new byte[0]);

        var result = service.sendWeeklyReports(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> attCap =
                ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), attCap.capture());
        assertThat(attCap.getValue()).isEmpty();
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("Takım anahtarı KAPALI/NULL → o takıma erişilebilirlik raporu gitmez (domain'i olsa bile)")
    void send_teamWithAvailabilityDisabled_isSkipped() {
        Team off = team(5L, "Kapali", "kapali@x.com", false);
        Team nulls = team(6L, "Null", "null@x.com", true);
        nulls.setWeeklyAvailabilityEnabled(null);           // kolon yeni eklendi, dokunulmamış satır
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(off, nulls));

        var result = service.sendWeeklyReports(false);

        verifyNoMailSent();
        assertThat(result.sent()).isZero();
        assertThat(result.skippedDisabled()).isEqualTo(2);
        // Kapalı takım için domain sorgusu bile çalışmamalı.
        verify(inventoryRepo, never()).findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(anyLong());
    }

    @Test
    @DisplayName("İki anahtar BAĞIMSIZ: hatırlatma kapalı olsa da erişilebilirlik raporu gider")
    void send_switchesAreIndependent() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        t.setWeeklyReminderEnabled(false);                  // Cuma hatırlatması kapalı
        t.setWeeklyAvailabilityEnabled(true);               // Pazartesi raporu açık
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        assertThat(service.sendWeeklyReports(false).sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("send: devre dışıysa hiç gönderilmez")
    void send_disabled() {
        when(appSettings.getBoolean(eq("site.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(false);
        var result = service.sendWeeklyReports(false);
        verifyNoMailSent();
        assertThat(result.sent()).isZero();
    }

    @Test
    @DisplayName("send: aynı hafta SENT ise (force=false) atlanır; force=true gönderir")
    void send_idempotent() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        WeeklyAvailabilityLog sentLog = new WeeklyAvailabilityLog();
        sentLog.setStatus("SENT");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(sentLog));

        assertThat(service.sendWeeklyReports(false).sent()).isZero();         // zaten gönderilmiş → atla
        verifyNoMailSent();

        assertThat(service.sendWeeklyReports(true).sent()).isEqualTo(1);      // force → gönder
        verify(emailService).sendHtmlWithAttachments(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("send: domain'i olmayan takım atlanır")
    void send_skipsNoDomains() {
        Team t = team(9L, "Boş", "bos@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(9L)).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);
        verifyNoMailSent();
        assertThat(result.skippedNoDomains()).isEqualTo(1);
    }

    @Test
    @DisplayName("send: takım kutusu yoksa CC'den ilki TO olur; hiç alıcı yoksa atlanır")
    void send_noTeamEmail_promotesCc_orSkips() {
        Team noMail = team(7L, "Mailsiz", null);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(noMail));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(7L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 100L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(7L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(7L, "MANAGER")).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtmlWithAttachments(toCap.capture(), any(), anyString(), anyString(), any(), any());
        assertThat(toCap.getValue()).containsExactly("po@x.com");   // CC adayı TO'ya terfi
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("preview: tek takım HTML + çözülmüş TO/CC + domainCount; göndermez")
    void preview_buildsHtmlAndResolvesRecipients() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());

        var p = service.preview(5L);

        assertThat(p.html()).isEqualTo("<html></html>");
        assertThat(p.teamName()).isEqualTo("Dijital");
        assertThat(p.to()).containsExactly("dijital@x.com");
        assertThat(p.cc()).contains("po@x.com");
        assertThat(p.domainCount()).isEqualTo(2);
        assertThat(p.noRecipients()).isFalse();
        verifyNoMailSent();
    }

    @Test
    @DisplayName("preview: bilinmeyen takım → IllegalArgumentException")
    void preview_unknownTeam_throws() {
        when(teamRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("windowForOffset: 1 = lastFullWeekWindow (geçen tam hafta); 0 = içinde bulunulan hafta (bitiş ≈ now)")
    void windowForOffset_currentVsLast() {
        var last = service.windowForOffset(1);
        var lastAlias = service.lastFullWeekWindow();
        assertThat(last.year()).isEqualTo(lastAlias.year());
        assertThat(last.week()).isEqualTo(lastAlias.week());
        assertThat(last.weekLabel()).isEqualTo(lastAlias.weekLabel());

        var cur = service.windowForOffset(0);
        LocalDate thisMonday = LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"))
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThat(cur.week())
                .isEqualTo(thisMonday.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
        assertThat(cur.week()).isNotEqualTo(last.week());                 // bu hafta ≠ geçen hafta
        assertThat(cur.windowEnd()).isBeforeOrEqualTo(Instant.now().plusSeconds(5));  // gelecek Pazar'a değil, now'a kırpılı
    }

    @Test
    @DisplayName("weekOptions: [0]=bu hafta(current), [1]=e-posta(emailed), 9 seçenek, etiketler dolu")
    void weekOptions_currentAndEmailedFlags() {
        var opts = service.weekOptions();
        assertThat(opts).hasSize(9);
        assertThat(opts.get(0).offset()).isZero();
        assertThat(opts.get(0).current()).isTrue();
        assertThat(opts.get(0).emailed()).isFalse();
        assertThat(opts.get(1).offset()).isEqualTo(1);
        assertThat(opts.get(1).current()).isFalse();
        assertThat(opts.get(1).emailed()).isTrue();
        assertThat(opts).allSatisfy(o -> assertThat(o.label()).isNotBlank());
    }

    @Test
    @DisplayName("preview(teamId, offset): seçilen haftanın etiketini kullanır; null → geçen hafta (offset 1)")
    void preview_usesSelectedWeek() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(anyLong(), anyString())).thenReturn(List.of());

        assertThat(service.preview(5L, 0).weekLabel()).isEqualTo(service.windowForOffset(0).weekLabel());   // bu hafta
        assertThat(service.preview(5L, 2).weekLabel()).isEqualTo(service.windowForOffset(2).weekLabel());   // 2 hafta önce
        assertThat(service.preview(5L, null).weekLabel()).isEqualTo(service.preview(5L, 1).weekLabel());     // null = offset 1
    }

    @Test
    @DisplayName("sendTest: yalnız verilen adrese, cc yok, idempotency log'una YAZMAZ")
    void sendTest_sendsOnlyToGivenAddress_noCc_noWalLog() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        String status = service.sendTest(5L, "tester@x.com");
        assertThat(status).isEqualTo("SENT");

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> ccCap = ArgumentCaptor.forClass(String[].class);
        // Test maili gerçek mailin aynısı: kesinti PDF'ini de taşır, yoksa gönderimden önce
        // doğrulanamazdı.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> attCap =
                ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtmlWithAttachments(toCap.capture(), ccCap.capture(),
                contains("[TEST]"), anyString(), any(), attCap.capture());
        assertThat(toCap.getValue()).containsExactly("tester@x.com");
        assertThat(ccCap.getValue()).isNull();
        assertThat(attCap.getValue()).hasSize(1);
        assertThat(attCap.getValue().get(0).fileName()).endsWith(".pdf");
        verify(walRepo, never()).save(any(WeeklyAvailabilityLog.class));   // idempotency log'una dokunmaz

        // Arşiv: NotificationLog yazılır; alertEventId=0 sentinel (NOT NULL) + test trigger'ı
        ArgumentCaptor<NotificationLog> nlCap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(nlCap.capture());
        assertThat(nlCap.getValue().getAlertEventId()).isEqualTo(0L);
        assertThat(nlCap.getValue().getTrigger()).isEqualTo("WEEKLY_AVAILABILITY_TEST");
    }

    @Test
    @DisplayName("setEnabled: appSettings.save doğru key + values ile çağrılır")
    @SuppressWarnings("unchecked")
    void setEnabled_persistsViaAppSettings() {
        service.setEnabled(false, "admin");

        ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.forClass(Map.class);
        verify(appSettings).save(bodyCap.capture(), eq("admin"));
        Object values = bodyCap.getValue().get("values");
        assertThat(values).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) values).get("site.monitor.weekly-availability.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("status: yalnız domain'i olan takımlar + alıcılar + son gönderim")
    void status_listsTeamsWithDomains() {
        Team a = team(5L, "Dijital", "dijital@x.com");
        Team b = team(9L, "Boş", "bos@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(a, b));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(9L)).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());
        WeeklyAvailabilityLog sentLog = new WeeklyAvailabilityLog();
        sentLog.setStatus("SENT"); sentLog.setSentAt("2026-06-15T08:00:00");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(sentLog));

        var st = service.status();

        assertThat(st.enabled()).isTrue();
        assertThat(st.teams()).hasSize(1);                  // domain'siz takım listede yok
        var ts = st.teams().get(0);
        assertThat(ts.name()).isEqualTo("Dijital");
        assertThat(ts.domainCount()).isEqualTo(1);
        assertThat(ts.to()).containsExactly("dijital@x.com");
        assertThat(ts.cc()).contains("po@x.com");
        assertThat(ts.lastStatus()).isEqualTo("SENT");
        assertThat(ts.lastSentAt()).isEqualTo("2026-06-15T08:00:00");
        verifyNoMailSent();
    }

    @Test
    @DisplayName("history: trigger'a göre listeler + alanları eşler; includeTest=false → yalnız ana trigger")
    void history_listsAndMaps() {
        NotificationLog n = nlog(7L, "Dijital", "dijital@x.com", "po@x.com", "[SiteMonitor] Dijital", "SENT", "WEEKLY_AVAILABILITY");
        when(notificationLogRepo.findByTriggerInOrderBySentAtDesc(any(), any())).thenReturn(List.of(n));

        var list = service.history(50, false);

        assertThat(list).hasSize(1);
        var a = list.get(0);
        assertThat(a.id()).isEqualTo(7L);
        assertThat(a.team()).isEqualTo("Dijital");
        assertThat(a.to()).isEqualTo("dijital@x.com");
        assertThat(a.cc()).isEqualTo("po@x.com");
        assertThat(a.status()).isEqualTo("SENT");
        assertThat(a.trigger()).isEqualTo("WEEKLY_AVAILABILITY");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> trigCap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationLogRepo).findByTriggerInOrderBySentAtDesc(trigCap.capture(), any());
        assertThat(trigCap.getValue()).containsExactly("WEEKLY_AVAILABILITY");   // test maili hariç
    }

    @Test
    @DisplayName("history: includeTest=true → test trigger'ı da dahil")
    void history_includeTest() {
        when(notificationLogRepo.findByTriggerInOrderBySentAtDesc(any(), any())).thenReturn(List.of());
        service.history(50, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> trigCap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationLogRepo).findByTriggerInOrderBySentAtDesc(trigCap.capture(), any());
        assertThat(trigCap.getValue()).contains("WEEKLY_AVAILABILITY", "WEEKLY_AVAILABILITY_TEST");
    }

    @Test
    @DisplayName("historyItem: haftalık trigger ise saklanan HTML döner")
    void historyItem_returnsHtml() {
        NotificationLog n = nlog(7L, "Dijital", "dijital@x.com", null, "konu", "SENT", "WEEKLY_AVAILABILITY");
        n.setMessage("<html>arşiv</html>");
        when(notificationLogRepo.findById(7L)).thenReturn(Optional.of(n));

        var d = service.historyItem(7L);
        assertThat(d.html()).isEqualTo("<html>arşiv</html>");
        assertThat(d.team()).isEqualTo("Dijital");
        assertThat(d.trigger()).isEqualTo("WEEKLY_AVAILABILITY");
    }

    @Test
    @DisplayName("historyItem: haftalık olmayan trigger → reddedilir (başka notifikasyon sızdırılmaz)")
    void historyItem_rejectsForeignTrigger() {
        NotificationLog n = nlog(8L, "X", "x@x.com", null, "k", "SENT", "ESCALATION");
        when(notificationLogRepo.findById(8L)).thenReturn(Optional.of(n));
        assertThatThrownBy(() -> service.historyItem(8L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("weekRangeLabel: TAM hafta Pzt–Paz (15–21, 15–19 DEĞİL); ay/yıl geçişleri")
    void weekRangeLabel_coversFullWeek() {
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21)))
                .isEqualTo("15–21 Haziran 2026");
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 5)))
                .isEqualTo("29 Haziran – 5 Temmuz 2026");
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)))
                .isEqualTo("29 Aralık 2025 – 4 Ocak 2026");
    }

    @Test
    @DisplayName("schedulerHealth: enabled + cron + sıradaki çalışma + son çalışma özeti (gönderilen/hata)")
    void schedulerHealth_summary() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cronExpr", "0 0 10 ? * MON");
        WeeklyAvailabilityLog last = new WeeklyAvailabilityLog();
        last.setReportYear(2026); last.setWeekNo(25); last.setStatus("SENT"); last.setSentAt("2026-06-22T07:00:00");
        when(walRepo.findTopByOrderBySentAtDesc()).thenReturn(Optional.of(last));
        WeeklyAvailabilityLog a = new WeeklyAvailabilityLog(); a.setStatus("SENT");
        WeeklyAvailabilityLog b = new WeeklyAvailabilityLog(); b.setStatus("FAILED: smtp down");
        when(walRepo.findByReportYearAndWeekNo(2026, 25)).thenReturn(List.of(a, b));

        var m = service.schedulerHealth();

        assertThat(m.get("enabled")).isEqualTo(true);
        assertThat(m.get("cron")).isEqualTo("0 0 10 ? * MON");
        assertThat(m.get("next_run")).isNotNull();                 // cron'dan hesaplanır
        assertThat(m.get("last_run_at")).isEqualTo("2026-06-22T07:00:00");
        assertThat(m.get("last_run_sent")).isEqualTo(1L);
        assertThat(m.get("last_run_failed")).isEqualTo(1L);
        assertThat(m.get("last_run_teams")).isEqualTo(2);
        assertThat(m.get("last_run_week")).isNotNull();
    }

    @Test
    @DisplayName("schedulerHealth: hiç çalışma yoksa last_run_at null")
    void schedulerHealth_neverRun() {
        when(walRepo.findTopByOrderBySentAtDesc()).thenReturn(Optional.empty());
        var m = service.schedulerHealth();
        assertThat(m.get("last_run_at")).isNull();
        assertThat(m).doesNotContainKey("last_run_sent");
    }

    // ── helpers ──
    private NotificationLog nlog(Long id, String team, String to, String cc, String subject, String status, String trigger) {
        NotificationLog n = new NotificationLog();
        n.setId(id); n.setRecipientName(team); n.setRecipientEmail(to); n.setCc(cc);
        n.setSubject(subject); n.setEmailStatus(status); n.setTrigger(trigger);
        n.setSentAt("2026-06-15T08:00:00");
        return n;
    }

    /** Varsayılan fabrika: erişilebilirlik raporu anahtarı AÇIK — "kapalıysa gönderilmez" ayrı testte. */
    // ── E2: Sürüm & Dağıtım satırı ───────────────────────────────────────────────────────────

    private static com.sitemonitor.model.DeploymentHistory dep(long id, String startedAtUtcNoZ, String version) {
        com.sitemonitor.model.DeploymentHistory d = new com.sitemonitor.model.DeploymentHistory();
        d.setId(id); d.setStartedAt(startedAtUtcNoZ + "Z"); d.setRecordedAt(d.getStartedAt());
        d.setEnvironment("prod"); d.setVersion(version); d.setSource("STARTUP");
        return d;
    }

    @Test
    @DisplayName("E2: pencere içindeki dağıtımlar sayılır (yükseltme+geri alma = dağıtım, restart ayrı), pencere dışı elenir")
    void deployments_countedWithinWindow() {
        WeeklyAvailabilityReportService.Window w = service.lastFullWeekWindow();
        // Pencere sınırları 'Z'siz UTC (İstanbul Pzt 00:00 = UTC Paz 21:00); satırlar 'Z'li —
        // from tam sınırda dahil, to'dan sonra hariç. "inside" from'a göre türetilir, tarih parçasından değil.
        String from = w.fromUtc();
        String inside = java.time.LocalDateTime.parse(w.fromUtc()).plusHours(12).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String before = java.time.LocalDateTime.parse(w.fromUtc()).minusSeconds(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String after = java.time.LocalDateTime.parse(w.toUtc()).plusSeconds(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<com.sitemonitor.service.DeploymentHistoryService.Derived> derived =
                com.sitemonitor.service.DeploymentHistoryService.deriveKinds(List.of(
                        dep(1, before, "1.0.0"),     // pencere ÖNCESİ — FIRST_SEEN ama sayılmaz
                        dep(2, from, "1.1.0"),       // UPGRADE (sınırda, dahil)
                        dep(3, inside, "1.1.0"),     // RESTART
                        dep(4, inside, "1.0.0"),     // ROLLBACK
                        dep(5, inside, "1.2.0"),     // UPGRADE
                        dep(6, after, "1.3.0")));    // pencere SONRASI — sayılmaz
        when(deploymentHistory.derivedFor("prod")).thenReturn(derived);

        EmailNotificationService.DeploymentWeekly d = service.collectDeployments(w);
        assertThat(d).isNotNull();
        assertThat(d.deployments()).isEqualTo(3);
        assertThat(d.restarts()).isEqualTo(1);
        assertThat(d.rollbacks()).isEqualTo(1);
        assertThat(d.fromVersion()).isEqualTo("1.0.0");
        assertThat(d.toVersion()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("E2: geçmiş boşsa sıfır satırı; servis patlarsa null (rapor satırsız gider) — mail yine HTML üreticisine ulaşır")
    void deployments_emptyAndFailureTolerant() {
        WeeklyAvailabilityReportService.Window w = service.lastFullWeekWindow();
        assertThat(service.collectDeployments(w))
                .isEqualTo(new EmailNotificationService.DeploymentWeekly(0, null, null, 0, 0));

        when(deploymentHistory.derivedFor(any())).thenThrow(new RuntimeException("db"));
        assertThat(service.collectDeployments(w)).isNull();

        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com")));
        service.sendWeeklyReports(false);
        ArgumentCaptor<EmailNotificationService.DeploymentWeekly> cap =
                ArgumentCaptor.forClass(EmailNotificationService.DeploymentWeekly.class);
        verify(emailService).buildWeeklyAvailabilityHtml(any(), any(), any(), any(), any(), any(), cap.capture(), any());
        assertThat(cap.getValue()).isNull();
        verify(emailService).sendHtmlWithAttachments(any(), any(), any(), any(), any(), any());
    }

    private Team team(Long id, String name, String email) {
        return team(id, name, email, true);
    }
    private Team team(Long id, String name, String email, boolean availabilityEnabled) {
        Team t = new Team(); t.setId(id); t.setName(name); t.setEmail(email); t.setActive(true);
        t.setWeeklyAvailabilityEnabled(availabilityEnabled); return t;
    }
    private CertificateInventory inv(String domain) {
        CertificateInventory c = new CertificateInventory(); c.setDomain(domain); c.setPort(443); c.setTeamId(5L); return c;
    }
    private EscalationContact contact(String email) {
        EscalationContact c = new EscalationContact(); c.setEmail(email); c.setActive(true); return c;
    }

    // ── Sayfa Hızı bölümü (K10) ────────────────────────────────────────────────────────────

    private com.sitemonitor.model.PageSpeedMonitor psMon(Long id, String name, Long teamId) {
        var m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(id); m.setName(name); m.setUrl("https://" + name); m.setTeamId(teamId); m.setActive(true);
        return m;
    }

    /** weeklySummary satırı: [monitorId, sayı, ort ms, ihlal sayısı]. */
    private Object[] psSummary(long id, long count, double avgMs, long breaches) {
        return new Object[]{ id, count, avgMs, breaches };
    }

    private void arrangeTeamWithUptime(Team t) {
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(t.getId()))
                .thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
    }

    private EmailNotificationService.PageSpeedWeekly capturePageSpeed() {
        ArgumentCaptor<EmailNotificationService.PageSpeedWeekly> cap =
                ArgumentCaptor.forClass(EmailNotificationService.PageSpeedWeekly.class);
        verify(emailService).buildWeeklyAvailabilityHtml(any(), any(), any(), any(), any(), cap.capture(), any(), any());
        return cap.getValue();
    }

    @Test
    @DisplayName("Sayfa Hızı bölümü en yavaş sayfaları SIRALI verir ve geçen haftayı kıyaslar")
    void pageSpeedSection_sortsSlowestFirstAndComparesToPreviousWeek() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        arrangeTeamWithUptime(t);
        when(pageSpeedMonitorRepo.findByActiveTrue()).thenReturn(List.of(
                psMon(1L, "hizli", 5L), psMon(2L, "yavas", 5L)));
        // Bu hafta: yavas=900ms (2 ihlal), hizli=200ms. Geçen hafta: yavas=600ms, hizli=200ms.
        when(pageSpeedCheckRepo.weeklySummary(any(), any(), any()))
                .thenReturn(List.of(psSummary(1L, 10, 200.0, 0), psSummary(2L, 10, 900.0, 2)))
                .thenReturn(List.of(psSummary(1L, 10, 200.0, 0), psSummary(2L, 10, 600.0, 0)));

        service.sendWeeklyReports(false);

        var ps = capturePageSpeed();
        assertThat(ps).isNotNull();
        assertThat(ps.monitorCount()).isEqualTo(2);
        assertThat(ps.breachedMonitorCount()).isEqualTo(1);
        // EN YAVAŞ ÜSTTE — sıralama tersse rapor "sorun yok" izlenimi verir.
        assertThat(ps.slowest()).extracting(EmailNotificationService.PageSpeedWeeklyRow::name)
                .containsExactly("yavas", "hizli");
        var slowest = ps.slowest().get(0);
        assertThat(slowest.avgLoadMs()).isEqualTo(900L);
        assertThat(slowest.prevAvgLoadMs()).isEqualTo(600L);   // trend oku bunu okur
        assertThat(slowest.breachedChecks()).isEqualTo(2L);
    }

    @Test
    @DisplayName("BAŞKA takımın sayfa hızı izlemesi rapora SIZMAZ")
    void pageSpeedSection_isTeamScoped() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        arrangeTeamWithUptime(t);
        when(pageSpeedMonitorRepo.findByActiveTrue()).thenReturn(List.of(
                psMon(1L, "bizim", 5L), psMon(2L, "baskasinin", 99L)));
        when(pageSpeedCheckRepo.weeklySummary(any(), any(), any()))
                .thenReturn(List.<Object[]>of(psSummary(1L, 5, 300.0, 0)));

        service.sendWeeklyReports(false);

        assertThat(capturePageSpeed().slowest())
                .extracting(EmailNotificationService.PageSpeedWeeklyRow::name)
                .containsExactly("bizim");
    }

    @Test
    @DisplayName("Sayfa hızı izlemesi olmayan takımda bölüm HİÇ çizilmez (null geçer)")
    void pageSpeedSection_absentWhenNoMonitors() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        arrangeTeamWithUptime(t);
        when(pageSpeedMonitorRepo.findByActiveTrue()).thenReturn(List.of());

        service.sendWeeklyReports(false);

        assertThat(capturePageSpeed()).isNull();
    }

    @Test
    @DisplayName("Bölüm toplanamazsa rapor YİNE gider — yan bölüm haftalık raporu düşürmemeli")
    void pageSpeedSection_failureDoesNotBlockTheReport() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        arrangeTeamWithUptime(t);
        when(pageSpeedMonitorRepo.findByActiveTrue()).thenThrow(new IllegalStateException("db kapali"));

        var result = service.sendWeeklyReports(false);

        assertThat(result.sent()).isEqualTo(1);       // mail gitti
        assertThat(capturePageSpeed()).isNull();      // yalnız bölüm yok
    }

    @Test
    @DisplayName("Geçen hafta penceresi tam BİR HAFTA geriye kaydırılır (kıyas aynı uzunlukta olsun)")
    void previousWeekWindowIsShiftedByExactlyOneWeek() {
        assertThat(WeeklyAvailabilityReportService.shiftWeek("2026-08-17T00:00:00"))
                .isEqualTo("2026-08-10T00:00:00");
        assertThat(WeeklyAvailabilityReportService.shiftWeek("2026-01-05T21:00:00"))
                .isEqualTo("2025-12-29T21:00:00");   // yıl sınırını doğru geçer
    }
    @Test
    @DisplayName("2026-09-12: collectWeakAlgo — takımın alanlarında zayıf sayısı ve taranan sayısı; depo düşerse null (bant atlanır, rapor gider)")
    void collectWeakAlgo_countsAndDegrades() {
        com.sitemonitor.model.CertificateInventory a = new com.sitemonitor.model.CertificateInventory(); a.setDomain("a.example.com");
        com.sitemonitor.model.CertificateInventory b = new com.sitemonitor.model.CertificateInventory(); b.setDomain("b.example.com");
        com.sitemonitor.model.LatestCheck weak = new com.sitemonitor.model.LatestCheck();
        weak.setDomain("a.example.com"); weak.setSignatureAlgorithm("SHA1withRSA"); weak.setPublicKeyAlgorithm("RSA"); weak.setPublicKeySize(2048); weak.setCheckedAt("2026-01-01T00:00:00");
        com.sitemonitor.model.LatestCheck other = new com.sitemonitor.model.LatestCheck();
        other.setDomain("other.example.com"); other.setSignatureAlgorithm("MD5withRSA");
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(weak));
        when(latestCheckRepo.findById("b.example.com")).thenReturn(java.util.Optional.empty());
        when(latestCheckRepo.findWeakAlgorithmCandidates()).thenReturn(java.util.List.of(weak, other));

        EmailNotificationService.WeakAlgoWeekly w = service.collectWeakAlgo(java.util.List.of(a, b));
        assertThat(w.weak()).isEqualTo(1);
        assertThat(w.scanned()).isEqualTo(1);

        when(latestCheckRepo.findWeakAlgorithmCandidates()).thenThrow(new RuntimeException("db"));
        assertThat(service.collectWeakAlgo(java.util.List.of(a, b))).isNull();
    }
}
