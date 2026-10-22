package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.report.executive.ExecTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gönderim — opt-in (varsayılan KAPALI), alıcılar (pasif düşer, global yönetici dahil, müdür hariç), BCC 100'lük dilimler,
 * "tam bir kez" talebi (UNIQUE yarışı, koşullu yeniden talep), iz (notification_logs), telafi penceresi, test postası.
 * Posta servisi SAHTE — gerçek posta gönderilmez.
 */
class ExecutiveSummaryDeliveryServiceTest {

    private final ExecutiveSummaryService summaryService = mock(ExecutiveSummaryService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ExecutiveSummaryReportRepository repo = mock(ExecutiveSummaryReportRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final InactiveRecipientGuard guard = mock(InactiveRecipientGuard.class);
    private ExecutiveSummarySettings settings;
    private ExecutiveSummaryDeliveryService svc;

    @BeforeEach
    void setUp() {
        // Ayar servisi varsayılanı geri döner → ExecutiveSummarySettings gerçek varsayılanlarıyla çalışır
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        settings = new ExecutiveSummarySettings(appSettings);
        svc = new ExecutiveSummaryDeliveryService(summaryService, settings, repo, email, logs, users, userService, appSettings);
        svc.setInactiveGuard(guard);
        when(summaryService.now()).thenReturn(NOW);
        when(summaryService.currentMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.defaultMonth()).thenReturn(YearMonth.of(2026, 9));
        when(summaryService.compute(any())).thenReturn(sample());
        when(repo.findByReportYearAndReportMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        // Koşullu bitirme (R2a, MP-42): bütün varlık kaydı yerine; talep edilen satırın alanları nesnede güncellenir
        when(repo.finishIfClaimed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(email.sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList())).thenReturn("SENT");
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of());
    }

    static ExecutiveSummary sample() {
        SectionResult sec = SectionResult.builder("availability", 10, "Erişilebilirlik hedefi uyumu")
                .status(SectionResult.OK).headlineKpi("org_availability")
                .verdict("ORG_MET", SectionResult.T_OK, "Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.", 99.95, 99.9)
                .kpi(new SectionResult.Kpi("org_availability", "Kurum erişilebilirliği", 99.95, "pct", SectionResult.T_OK,
                        "Hedef %99,9", List.of(99.9), 0.02, "pp", SectionResult.T_OK))
                .build();
        return new ExecutiveSummary("2026-09", "Eylül 2026", "2026-08-31T21:00:00", "2026-09-30T21:00:00", true,
                "2026-10-10T09:00:00", ExecutiveSummary.SOURCE_LIVE, SectionResult.OK, sec.verdicts(),
                List.of(new ExecutiveSummary.HeadlineKpi("availability", sec.kpis().get(0))), List.of(sec),
                Map.of("availability_target", 99.9, "renewal_target_days", 30), null);
    }

    private void enable(String recipientsCsv, boolean admins) {
        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.ENABLED_KEY), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.INCLUDE_ADMINS_KEY), anyBoolean())).thenReturn(admins);
        when(appSettings.getString(eq(ExecutiveSummarySettings.RECIPIENTS_KEY), any())).thenReturn(recipientsCsv);
    }

    private static AppUser user(String name, String role, String source, String mail) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setEmail(mail);
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("opt-in: varsayılan KAPALI → posta yok, ayın kaydına SKIPPED_DISABLED izi")
    void defaultOff() {
        assertThat(settings.enabled()).isFalse();
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo("DISABLED");
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).saveAndFlush(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.SKIPPED_DISABLED);
        assertThat(row.getValue().getReportMonth()).isEqualTo(9);
        assertThat(row.getValue().getAttempts()).isZero();
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
        assertThat(svc.catchUp().status()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("alıcılar: açık liste (tekil, pasif düşer) + aktif global yöneticiler; müdür (LDAP + kapsam) ve USER hariç")
    void recipients() {
        enable("a@x.com, A@X.com; passive@x.com bad-address", true);
        when(guard.isInactiveOnlyEmail("passive@x.com")).thenReturn(true);
        AppUser local = user("admin", "ADMIN", "LOCAL", "g1@x.com");
        AppUser scoped = user("mudur", "ADMIN", "LDAP", "m@x.com");
        AppUser ldapGlobal = user("ldapadmin", "ADMIN", "LDAP", "lg@x.com");
        AppUser plain = user("u", "USER", "LOCAL", "u@x.com");
        AppUser noMail = user("admin2", "ADMIN", "LOCAL", " ");
        AppUser dup = user("admin3", "ADMIN", "LOCAL", "A@x.com");
        when(userService.computeViewTeamIds(scoped)).thenReturn(List.of(1L));
        when(userService.computeViewTeamIds(ldapGlobal)).thenReturn(null);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(local, scoped, ldapGlobal, plain, noMail, dup));

        ExecutiveSummaryDeliveryService.Recipients rc = svc.recipients();
        assertThat(rc.emails()).containsExactly("a@x.com", "g1@x.com", "lg@x.com");
        assertThat(rc.explicitCount()).isEqualTo(1);
        assertThat(rc.adminCount()).isEqualTo(2);
        assertThat(rc.droppedInactive()).isEqualTo(1);

        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.INCLUDE_ADMINS_KEY), anyBoolean())).thenReturn(false);
        assertThat(svc.recipients().emails()).containsExactly("a@x.com");
    }

    @Test
    @DisplayName("250 alıcı → BCC 100/100/50, PDF eki, dilim başına iz; kayıt SENT + özet içeriği; konu [Site Monitor]")
    void bccChunksAndTrace() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add("u" + i + "@x.com");
        enable(String.join(",", many), false);

        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.SENT);
        assertThat(r.recipients()).isEqualTo(250);
        assertThat(r.chunks()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(3)).sendHtmlBccWithAttachments(bcc.capture(), subject.capture(), anyString(), anyString(), att.capture());
        assertThat(bcc.getAllValues()).extracting(a -> a.length).containsExactly(100, 100, 50);
        assertThat(subject.getValue()).startsWith("[Site Monitor] Aylık Yönetici Özeti · Eylül 2026");
        EmailNotificationService.MailAttachment pdf = att.getValue().get(0);
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.fileName()).isEqualTo("site-monitor-yonetici-ozeti-2026-09.pdf");
        assertThat(new String(pdf.data(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(3)).save(log.capture());
        assertThat(log.getAllValues()).allSatisfy(n -> {
            assertThat(n.getTrigger()).isEqualTo(ExecutiveSummaryDeliveryService.TRIGGER);
            assertThat(n.getRecipientEmail()).startsWith("BCC×").hasSizeLessThanOrEqualTo(250);
            assertThat(n.getEmailStatus()).isEqualTo("SENT");
        });

        // Yeniden temel (2026-10-22, R2a MP-42): bitirme koşullu UPDATE — bütün varlık kaydı yok
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).saveAndFlush(row.capture());
        verify(repo).finishIfClaimed(any(), eq(row.getValue().getClaimedAt()), eq("SENT"), eq(250), eq(3), any(), any(), any(), any(), any());
        verify(repo, never()).save(any());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.SENT);
        assertThat(row.getValue().getRecipientCount()).isEqualTo(250);
        assertThat(row.getValue().getChunkCount()).isEqualTo(3);
        assertThat(row.getValue().getSummaryJson()).contains("\"month\":\"2026-09\"");
    }

    @Test
    @DisplayName("tam bir kez: SENT kaydı → ALREADY_SENT; SENDING → IN_PROGRESS; INSERT yarışı kaybedilirse posta yok")
    void exactlyOnce() {
        enable("a@x.com", false);
        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setId(1L);
        sent.setStatus(ExecutiveSummaryReport.SENT);
        sent.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(sent));
        assertThat(svc.runScheduled().status()).isEqualTo("ALREADY_SENT");

        sent.setStatus(ExecutiveSummaryReport.SENDING);
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");

        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_esr_year_month"));
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
    }

    @Test
    @DisplayName("2026-10-12: SENDING'de kalmış ay — zamanlanmış yol dokunmaz; elle gönderim yalnız 1 saatten eski talebi devralır")
    void staleSendingTakeover() {
        enable("a@x.com", false);
        ExecutiveSummaryReport stuck = new ExecutiveSummaryReport();
        stuck.setId(9L);
        stuck.setStatus(ExecutiveSummaryReport.SENDING);
        stuck.setAttempts(1);
        stuck.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(NOW.minus(java.time.Duration.ofMinutes(10))));
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuck));
        assertThat(svc.sendNow(YearMonth.of(2026, 9), "yonetici").status()).isEqualTo("IN_PROGRESS");   // taze talep: gerçekten sürüyor
        stuck.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(NOW.minus(java.time.Duration.ofHours(2))));
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");                              // zamanlanmış yol devralmaz
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
        when(repo.reclaim(eq(9L), eq("SENDING"), eq(1), eq(2), anyString(), eq("MANUAL"), eq("yonetici"))).thenReturn(1);
        assertThat(svc.sendNow(YearMonth.of(2026, 9), "yonetici").status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("2026-10-12: talepten sonra beklenmeyen hata kaydı SENDING'de BIRAKMAZ (FAILED → telafi yeniden dener); istisna dışarı sızmaz")
    void unexpectedErrorAfterClaimIsFailed() {
        enable("a@x.com", false);
        // 1. çağrı veri hazır mı kapısı, 2. talep anı, 3. e-posta gövdesinin damgası (beklenmeyen hata), 4. kaydın kapanış anı
        when(summaryService.now()).thenReturn(NOW).thenReturn(NOW).thenThrow(new IllegalStateException("saat okunamadı"))
                .thenReturn(NOW);
        ArgumentCaptor<ExecutiveSummaryReport> saved = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        var r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.FAILED);
        verify(repo, atLeastOnce()).saveAndFlush(saved.capture());       // R2a MP-42: koşullu bitirme talep nesnesini günceller
        verify(repo).finishIfClaimed(any(), any(), eq("FAILED"), any(), any(), any(), any(), any(), any(), any());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.FAILED);
        assertThat(saved.getValue().getDetail()).contains("yarıda kesildi");
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
    }

    @Test
    @DisplayName("FAILED (deneme < 3) koşullu UPDATE ile yeniden talep edilir; UPDATE 0 satır → başka pod aldı; tavan dolunca yalnız elle")
    void reclaim() {
        enable("a@x.com", false);
        ExecutiveSummaryReport failed = new ExecutiveSummaryReport();
        failed.setId(7L);
        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(failed));

        when(repo.reclaim(eq(7L), eq("FAILED"), eq(1), eq(2), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(0);
        assertThat(svc.runScheduled().status()).isEqualTo("NOT_CLAIMED");
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        when(repo.reclaim(eq(7L), eq("FAILED"), eq(1), eq(2), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(1);
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());

        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(3);
        assertThat(svc.runScheduled().status()).isEqualTo("NOT_CLAIMED");
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(3), eq(4), anyString(), eq("MANUAL"), eq("yonetici"))).thenReturn(1);
        assertThat(svc.sendNow(YearMonth.of(2026, 9), "yonetici").status()).isEqualTo(ExecutiveSummaryReport.SENT);
    }

    /** Örnek özet + hesaplanamamış ("error") bir bölüm. */
    static ExecutiveSummary withFailedSection() {
        ExecutiveSummary ok = sample();
        List<SectionResult> secs = new ArrayList<>(ok.sections());
        secs.add(SectionResult.failed("expirations", 30, "Yaklaşan sertifika bitişleri"));
        return new ExecutiveSummary(ok.month(), ok.monthLabel(), ok.from(), ok.to(), ok.complete(), ok.generatedAt(),
                ok.source(), SectionResult.ERROR, ok.headline(), ok.headlineKpis(), secs, ok.settings(), ok.scope());
    }

    @Test
    @DisplayName("2026-10-12: zamanlanmış gönderimde hesaplanamayan bölüm → posta YOK, FAILED (telafi yeniden dener); son denemede ve elle gönderilir")
    void failedSectionIsRetriedNotSent() {
        enable("a@x.com", false);
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T06:00:00Z"));   // planlanan an (telafi penceresinin başı)
        when(summaryService.compute(any())).thenReturn(withFailedSection());
        ArgumentCaptor<ExecutiveSummaryReport> saved = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);

        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();               // 1. deneme
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.FAILED);
        assertThat(r.detail()).contains("expirations").contains("telafi");
        verify(repo, atLeastOnce()).saveAndFlush(saved.capture());       // R2a MP-42: koşullu bitirme talep nesnesini günceller
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.FAILED);
        assertThat(saved.getValue().getSummaryJson()).as("yarım görüntü saklanmaz").isNull();
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        // 3. deneme (tavan) → elde olanla gönderilir: rapor hiç gitmemekten iyidir
        ExecutiveSummaryReport row = new ExecutiveSummaryReport();
        row.setId(7L);
        row.setStatus(ExecutiveSummaryReport.FAILED);
        row.setAttempts(2);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(row));
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(2), eq(3), anyString(), eq("CATCH_UP"), eq("system"))).thenReturn(1);
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(2), eq(3), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(1);
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());

        // Elle gönderim (ilk deneme olsa bile) her zaman gönderir — yönetici sayfada hatayı görerek karar verir
        when(repo.findByReportYearAndReportMonth(2026, 8)).thenReturn(Optional.empty());
        when(summaryService.compute(YearMonth.of(2026, 8))).thenReturn(withFailedSection());
        assertThat(svc.sendNow(YearMonth.of(2026, 8), "yonetici").status()).isEqualTo(ExecutiveSummaryReport.SENT);
    }

    @Test
    @DisplayName("2026-10-12: global yöneticiler okunamazsa (seçenek açık) eksik listeyle GÖNDERİLMEZ — talep yok (inceleme 4), telafi yeniden dener; önizleme çizilir")
    void adminReadFailureDoesNotSendPartial() {
        enable("a@x.com", true);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ExecutiveSummaryDeliveryService.Recipients rc = svc.recipients();
        assertThat(rc.complete()).isFalse();
        assertThat(rc.emails()).containsExactly("a@x.com");                       // ayar ekranı önizlemesi yine çizilir

        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryDeliveryService.RECIPIENTS_UNAVAILABLE);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        // Seçenek kapalıyken kullanıcı listesi okunmaz → tam sayılır
        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.INCLUDE_ADMINS_KEY), anyBoolean())).thenReturn(false);
        assertThat(svc.recipients().complete()).isTrue();
    }

    @Test
    @DisplayName("alıcı yok → posta yok, NO_RECIPIENT + 'SKIPPED: alıcı yok' izi (nedeni söyler)")
    void noRecipient() {
        enable("", false);
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.NO_RECIPIENT);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: alıcı yok");
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).saveAndFlush(row.capture());                        // R2a MP-42: koşullu bitirme talep nesnesini günceller
        verify(repo).finishIfClaimed(any(), any(), eq("NO_RECIPIENT"), any(), any(), any(), any(), any(), any(), any());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.NO_RECIPIENT);
    }

    @Test
    @DisplayName("dilim sonuçları → kayıt durumu: hepsi gitti SENT, karışık PARTIAL, posta kapalı SKIPPED_MAIL_OFF, hepsi hata FAILED")
    void finalStatus() {
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SENT", 100, "QUEUED_RETRY", 5))).isEqualTo("SENT");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SENT", 100, "FAILED", 5))).isEqualTo("PARTIAL");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SKIPPED_DISABLED", 5))).isEqualTo("SKIPPED_MAIL_OFF");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("FAILED", 5))).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of())).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("FAILED: x")).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("QUEUED_RETRY: 421")).isEqualTo("QUEUED_RETRY");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("SKIPPED_DISABLED")).isEqualTo("SKIPPED_DISABLED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("SENT")).isEqualTo("SENT");

        enable("a@x.com", false);
        when(email.sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList())).thenReturn("SKIPPED_DISABLED");
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SKIPPED_MAIL_OFF);
    }

    @Test
    @DisplayName("telafi: planlı andan önce NOT_DUE; 72 saat içinde kaçan ayı gönderir; pencere kapanınca WINDOW_CLOSED")
    void catchUp() {
        enable("a@x.com", false);
        assertThat(ExecutiveSummaryDeliveryService.plannedFireThisMonth(YearMonth.of(2026, 10), "0 0 9 1 * *"))
                .isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
        assertThat(ExecutiveSummaryDeliveryService.plannedFireThisMonth(YearMonth.of(2026, 10), "geçersiz"))
                .isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));

        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T05:59:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo("NOT_DUE");
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-04T06:01:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo("WINDOW_CLOSED");
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T07:17:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryReport.SENT);

        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setStatus(ExecutiveSummaryReport.SENT);
        sent.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(sent));
        assertThat(svc.catchUp().status()).isEqualTo("NOTHING_TO_DO");
    }

    @Test
    @DisplayName("2026-10-12: ayın verisi hazır olmadan (sonraki ayın 1'i 06:00 TR öncesi) zamanlanmış / telafi gönderim BEKLER — kayıt açılmaz, posta yok")
    void waitsForNightlyRollup() {
        enable("a@x.com", false);
        assertThat(ExecutiveSummaryService.dataReady(YearMonth.of(2026, 10), Instant.parse("2026-11-01T02:59:00Z"))).isFalse();
        assertThat(ExecutiveSummaryService.dataReady(YearMonth.of(2026, 10), Instant.parse("2026-11-01T03:00:00Z"))).isTrue();
        assertThat(ExecutiveSummaryService.dataReady(YearMonth.of(2026, 9), Instant.parse("2026-11-01T00:00:00Z"))).isTrue();
        assertThat(ExecutiveSummaryService.dataReady(YearMonth.of(2026, 11), Instant.parse("2026-11-20T00:00:00Z"))).isFalse();

        // Cron 1'i 01:00'e kurulmuş: tetik 01:00 TR'de bekler, saatlik telafi 06:00 sonrası gönderir
        when(appSettings.getString(eq(ExecutiveSummarySettings.CRON_KEY), any())).thenReturn("0 0 1 1 * *");
        when(summaryService.currentMonth()).thenReturn(YearMonth.of(2026, 11));
        when(summaryService.defaultMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-31T22:00:00Z"));          // 1 Kasım 01:00 TR
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryDeliveryService.DATA_PENDING);
        when(summaryService.now()).thenReturn(Instant.parse("2026-11-01T01:30:00Z"));          // 04:30 TR
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryDeliveryService.DATA_PENDING);
        verify(repo, never()).saveAndFlush(any());
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        when(summaryService.now()).thenReturn(Instant.parse("2026-11-01T03:10:00Z"));          // 06:10 TR
        when(summaryService.compute(YearMonth.of(2026, 10))).thenReturn(sample());
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryReport.SENT);
    }

    @Test
    @DisplayName("test postası: yalnız verilen (isteyenin) adrese TO ile; ay kaydı YAZILMAZ; iz EXECUTIVE_SUMMARY_TEST; sınır 3/10 dk")
    void sendTest() {
        when(email.sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), anyList())).thenReturn("SENT");
        ExecutiveSummaryDeliveryService.TestResult r = svc.sendTest(YearMonth.of(2026, 9), "ben@x.com");
        assertThat(r.ok()).isTrue();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtmlWithAttachments(to.capture(), isNull(), subject.capture(), anyString(), isNull(), anyList());
        assertThat(to.getValue()).containsExactly("ben@x.com");
        assertThat(subject.getValue()).startsWith("[TEST] [Site Monitor]");
        verify(repo, never()).save(any());
        verify(repo, never()).saveAndFlush(any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo(ExecutiveSummaryDeliveryService.TRIGGER_TEST);

        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isFalse();
        assertThat(svc.allowTest("baska")).isTrue();
    }

    @Test
    @DisplayName("2026-10-12: elle kurum gönderimi alıcı listesi eksikken TALEP ETMEZ (gönderilmiş ay FAILED'e dönmez) — RECIPIENTS_UNAVAILABLE + iz")
    void manualSendChecksRecipientsBeforeClaim() {
        enable("a@x.com", true);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ExecutiveSummaryDeliveryService.Result r = svc.sendNow(YearMonth.of(2026, 9), "yonetici");
        assertThat(r.status()).isEqualTo(ExecutiveSummaryDeliveryService.RECIPIENTS_UNAVAILABLE);
        verify(repo, never()).saveAndFlush(any());
        verify(repo, never()).reclaim(any(), any(), any(), any(), any(), any(), any());
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: global yöneticiler okunamadı");
        assertThat(svc.status()).containsEntry("recipient_complete", false);
    }

    @Test
    @DisplayName("inceleme 4: ZAMANLANMIŞ kurum gönderimi de alıcı listesi eksikken ayı TALEP ETMEZ — deneme hakkı yanmaz, telafi dener")
    void scheduledSendChecksRecipientsBeforeClaim() {
        enable("a@x.com", true);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryDeliveryService.RECIPIENTS_UNAVAILABLE);
        verify(repo, never()).saveAndFlush(any());
        verify(repo, never()).reclaim(any(), any(), any(), any(), any(), any(), any());
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: global yöneticiler okunamadı");
    }

    @Test
    @DisplayName("inceleme 5: ay zaten gönderilmiş / gönderiliyorsa alıcılar OKUNMAZ ve 'okunamadı' izi yazılmaz; "
            + "okunamayan ayda iz pod başına bir kez (saatlik telafi her saat satır yazmaz)")
    void settledMonthSkipsRecipientsAndTraceOnce() {
        enable("a@x.com", true);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setId(1L);
        sent.setStatus(ExecutiveSummaryReport.SENT);
        sent.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(sent));
        assertThat(svc.runScheduled().status()).isEqualTo("ALREADY_SENT");
        sent.setStatus(ExecutiveSummaryReport.SENDING);
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");
        verify(users, never()).findByActiveTrueOrderByUsernameAsc();
        verify(logs, never()).save(any());
        verify(repo, never()).reclaim(any(), any(), any(), any(), any(), any(), any());   // talep denenmez (inceleme 6)
        verify(summaryService, never()).compute(any());

        // talep edilmemiş ay + okunamayan alıcılar: üç deneme, TEK iz satırı
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.empty());
        for (int i = 0; i < 3; i++) {
            assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryDeliveryService.RECIPIENTS_UNAVAILABLE);
        }
        verify(logs, org.mockito.Mockito.times(1)).save(any());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("2026-10-12: tek LDAP yöneticisinin kapsamı okunamazsa liste EKSİK işaretlenir (sessizce düşmez)")
    void unreadableAdminScopeMarksIncomplete() {
        enable("a@x.com", true);
        AppUser ldapAdmin = user("ldapadmin", "ADMIN", "LDAP", "lg@x.com");
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(ldapAdmin));
        when(userService.computeViewTeamIds(ldapAdmin)).thenThrow(new IllegalStateException("ldap"));
        ExecutiveSummaryDeliveryService.Recipients rc = svc.recipients();
        assertThat(rc.complete()).isFalse();
        assertThat(rc.emails()).containsExactly("a@x.com");
        assertThat(svc.isGlobalAdmin(ldapAdmin)).isFalse();
    }

    @Test
    @DisplayName("2026-10-12: 06:00 öncesi zamanlamada kurum kapalıysa SKIPPED_DISABLED izi yine yazılır, DATA_PENDING döner")
    void dataPendingStillRecordsDisabled() {
        when(summaryService.defaultMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-31T22:00:00Z"));   // 1 Kasım 01:00 TR
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryDeliveryService.DATA_PENDING);
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).saveAndFlush(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.SKIPPED_DISABLED);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK
    }

    @Test
    @DisplayName("2026-10-12: hatalı bölümde erteleme izi yazılır; telafi penceresinin son saatinde ertelenmez, gönderilir")
    void deferralTraceAndLastChance() {
        enable("a@x.com", false);
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T06:00:00Z"));
        when(summaryService.compute(any())).thenReturn(withFailedSection());
        svc.runScheduled();
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, atLeastOnce()).save(log.capture());
        assertThat(log.getAllValues()).extracting(NotificationLog::getEmailStatus)
                .anyMatch(s -> s.startsWith("SKIPPED: Bölüm hesaplanamadı (expirations)"));
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());   // iz satırı gönderen adresi okur — posta YOK

        // Planlanan an 1 Ekim 09:00 TR (06:00Z); 71 sa 30 dk sonra son şans → 1. denemede bile gönderilir
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.empty());
        when(summaryService.currentMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-04T05:30:00Z"));
        assertThat(svc.lastCatchUpChance(YearMonth.of(2026, 9))).isTrue();
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-02T06:00:00Z"));
        assertThat(svc.lastCatchUpChance(YearMonth.of(2026, 9))).isFalse();
    }

    // ── Çok pod R2a (MP-42, 2026-10-22) ─────────────────────────────────────────────────────────────────────────────

    /** Telafi penceresinin içi: planlı an 1 Ekim 06:00 UTC'den 27 saat sonra. */
    private static final Instant IN_WINDOW = Instant.parse("2026-10-02T09:00:00Z");

    private ExecutiveSummaryReport stuckSending(Instant claimedAt, int attempts) {
        ExecutiveSummaryReport stuck = new ExecutiveSummaryReport();
        stuck.setId(9L);
        stuck.setReportYear(2026);
        stuck.setReportMonth(9);
        stuck.setStatus(ExecutiveSummaryReport.SENDING);
        stuck.setAttempts(attempts);
        stuck.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(claimedAt));
        return stuck;
    }

    @SuppressWarnings("unchecked")
    private org.springframework.jdbc.core.JdbcTemplate traces(List<String[]> rows) {
        org.springframework.jdbc.core.JdbcTemplate j = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(j.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any())).thenReturn(rows);
        return j;
    }

    @Test
    @DisplayName("R2a MP-42: SENDING'de ölen pod'un kaydı (1 saatten eski, HİÇBİR dilim izi yok) — telafi koşullu FAILED yazar ve TEK kez yeniden gönderir; ikinci pod (koşullu yazımı kaybeden) göndermez")
    void staleSendingWithoutTrace_catchUpResendsOnce() {
        enable("a@x.com", false);
        when(summaryService.now()).thenReturn(IN_WINDOW);
        Instant claimed = IN_WINDOW.minus(java.time.Duration.ofHours(2));
        ExecutiveSummaryReport stuck = stuckSending(claimed, 1);
        String claimedAt = stuck.getClaimedAt();
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuck));
        svc.setJdbc(traces(List.of()));
        when(repo.finishIfClaimed(eq(9L), eq(claimedAt), eq("FAILED"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1).thenReturn(0);
        when(repo.reclaim(eq(9L), eq("FAILED"), eq(1), eq(2), anyString(), eq("CATCH_UP"), eq("system"))).thenReturn(1);

        ExecutiveSummaryDeliveryService.Result r = svc.catchUp();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, atLeastOnce()).save(log.capture());
        assertThat(log.getAllValues()).anySatisfy(n -> assertThat(n.getEmailStatus()).startsWith("SKIPPED: Gönderen pod yarıda durdu")
                .contains("yeniden deneniyor"));

        // ikinci pod aynı bayat SENDING kaydını okudu: koşullu FAILED yazımı 0 → hiçbir şey yapmaz
        ExecutiveSummaryDeliveryService other = new ExecutiveSummaryDeliveryService(summaryService, settings, repo, email, logs,
                users, userService, appSettings);
        other.setJdbc(traces(List.of()));
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuckSending(claimed, 1)));
        assertThat(other.catchUp().status()).isEqualTo("IN_PROGRESS");
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("R2a MP-42: bayat SENDING'in dilim izi VAR (bazı dilimler gitmiş olabilir) → YENİDEN GÖNDERİLMEZ; izlerden PARTIAL + iz satırı; taze SENDING ve iz okunamazsa dokunulmaz")
    void staleSendingWithTraces_partialNoResend() {
        enable("a@x.com", false);
        when(summaryService.now()).thenReturn(IN_WINDOW);
        ExecutiveSummaryReport stuck = stuckSending(IN_WINDOW.minus(java.time.Duration.ofHours(2)), 1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuck));
        svc.setJdbc(traces(List.of(new String[] { "BCC×100: a@x.com, b@x.com", "SENT" }, new String[] { "—", "SKIPPED: x" })));

        ExecutiveSummaryDeliveryService.Result r = svc.catchUp();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.PARTIAL);
        assertThat(r.recipients()).isEqualTo(100);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        verify(repo, never()).reclaim(any(), any(), any(), any(), any(), any(), any());
        verify(repo).finishIfClaimed(eq(9L), any(), eq("PARTIAL"), eq(100), eq(1), any(), any(), any(), any(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: Gönderen pod yarıda durdu").contains("yeniden gönderilmedi");

        // taze (1 saatten yeni) SENDING: gerçekten sürüyor — dokunulmaz
        org.mockito.Mockito.clearInvocations(repo, logs);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuckSending(IN_WINDOW.minusSeconds(600), 1)));
        assertThat(svc.catchUp().status()).isEqualTo("IN_PROGRESS");
        verify(repo, never()).finishIfClaimed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // iz okunamıyor (JdbcTemplate yok): eski kural — yalnız elle devralınır
        svc.setJdbc(null);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(stuckSending(IN_WINDOW.minus(java.time.Duration.ofHours(2)), 1)));
        assertThat(svc.catchUp().status()).isEqualTo("IN_PROGRESS");
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("R2a MP-42: bayat SENDING, iz yok ama deneme hakkı dolmuş → FAILED + iz, gönderilmez (elle karar)")
    void staleSendingAtMaxAttempts_failedNoResend() {
        enable("a@x.com", false);
        when(summaryService.now()).thenReturn(IN_WINDOW);
        when(repo.findByReportYearAndReportMonth(2026, 9))
                .thenReturn(Optional.of(stuckSending(IN_WINDOW.minus(java.time.Duration.ofHours(2)), ExecutiveSummaryDeliveryService.MAX_ATTEMPTS)));
        svc.setJdbc(traces(List.of()));
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryReport.FAILED);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("FAILED: Gönderen pod yarıda durdu").contains("deneme hakkı doldu");
    }

    @Test
    @DisplayName("R2a MP-42: bitirme yazımı geçici hatada 3 kez denenir (dilimler gitti — kayıt SENDING'de kalmasın); talep başkasına geçtiyse (0 satır) yeniden denenmez")
    void finishWriteRetried() {
        enable("a@x.com", false);
        when(repo.finishIfClaimed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db"))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db"))
                .thenReturn(1);
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(repo, times(3)).finishIfClaimed(any(), any(), eq("SENT"), any(), any(), any(), any(), any(), any(), any());
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());

        org.mockito.Mockito.clearInvocations(repo);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.empty());
        when(repo.finishIfClaimed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        svc.sendNow(YearMonth.of(2026, 9), "yonetici");
        verify(repo, times(1)).finishIfClaimed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R2a MP-42: deneme aralığı — son talebi 30 dk'dan yeni FAILED kaydı zamanlanmış / telafi yolu yeniden TALEP ETMEZ (deneme hakkı boşa yanmaz); elle gönderim bekletmez")
    void reclaimSpacing() {
        enable("a@x.com", false);
        ExecutiveSummaryReport failed = new ExecutiveSummaryReport();
        failed.setId(7L);
        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(1);
        failed.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(NOW.minusSeconds(17 * 60)));
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(failed));
        assertThat(svc.runScheduled().status()).isEqualTo("NOT_CLAIMED");
        verify(repo, never()).reclaim(any(), any(), any(), any(), any(), any(), any());
        failed.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(NOW.minusSeconds(31 * 60)));
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(1), eq(2), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(1);
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        // elle: taze talep de bekletmez
        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(2);
        failed.setClaimedAt(ExecutiveSummaryContext.UTC_ISO.format(NOW.minusSeconds(60)));
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(2), eq(3), anyString(), eq("MANUAL"), eq("yonetici"))).thenReturn(1);
        assertThat(svc.sendNow(YearMonth.of(2026, 9), "yonetici").status()).isEqualTo(ExecutiveSummaryReport.SENT);
        assertThat(ExecutiveSummaryDeliveryService.chunkSize("BCC×37: a@x")).isEqualTo(37);
        assertThat(ExecutiveSummaryDeliveryService.chunkSize("—")).isZero();
    }
}
