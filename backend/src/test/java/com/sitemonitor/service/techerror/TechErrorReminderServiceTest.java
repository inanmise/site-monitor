package com.sitemonitor.service.techerror;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorApprovalToken;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReminder;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorApprovalTokenRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReminderRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teknik hata raporu hatırlatmaları (2026-10-17, 12. sürüm — C9 / C10) — H2 üzerinde gerçek talep tablosu ve koşullu
 * sorgular: takım başına opt-in, İstanbul günü sınırı, tekrar penceresi, gönderilmiş / kuyruktaki rapor hatırlatılmaz,
 * onay bekleyen rapor ayrı cümle, modül kapalı / adres yok / sistem bakımı günlük satırları, iki pod tek posta, push yalnız
 * açıkken, ulaşmayan hatırlatma ertesi gün yeniden; onay hatırlatması N günde bir, eskimiş istek hiç.
 */
@DataJpaTest
@Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorApprovalService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorReminderServiceTest {

    @Autowired TechErrorReportService service;
    @Autowired TechErrorApprovalService approvals;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReminderRepository reminders;
    @Autowired TechErrorApprovalTokenRepository tokens;
    @Autowired TechErrorReportEventRepository events;
    @Autowired TeamRepository teams;
    @Autowired AppUserRepository users;
    @Autowired NotificationLogRepository logs;
    @Autowired com.sitemonitor.repository.TechErrorReportImageRepository images;
    @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;

    @MockitoBean TechErrorReportSender sender;
    @MockitoBean EmailNotificationService email;
    @MockitoBean AppSettingsService appSettings;

    private final TechErrorVisibilityService visibility = mock(TechErrorVisibilityService.class);
    private final SystemMaintenanceService maintenance = mock(SystemMaintenanceService.class);
    private final UserPushService push = mock(UserPushService.class);
    private final Map<String, String> settings = new HashMap<>();

    private Team dijital, odeme;
    private AppUser tech, po1, po2;
    private TechErrorReminderService job;

    /** 5 Ekim 2026 09:15 İstanbul (UTC+3). */
    private static final Instant OCT5 = Instant.parse("2026-10-05T06:15:00Z");

    @BeforeEach
    void setUp() {
        tokens.deleteAll();
        events.deleteAll();
        versions.deleteAll();
        images.deleteAll();
        reminders.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        logs.deleteAll();
        users.deleteAll();
        teams.deleteAll();
        settings.clear();
        at(OCT5);
        dijital = team("Dijital Kanallar", "dijital@example.com");
        odeme = team("Ödeme Sistemleri", "odeme@example.com");
        tech = user("tech", "Teknik Kişi", "tech@example.com", "TECH", dijital.getId());
        po1 = user("po1", "PO Bir", "po1@example.com", "PO", dijital.getId());
        po2 = user("po2", "PO İki", "po2@example.com", "PO", dijital.getId());
        when(appSettings.getString(anyString(), any())).thenAnswer(inv -> settings.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(appSettings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            String v = settings.get((String) inv.getArgument(0));
            return v == null ? inv.getArgument(1) : Integer.parseInt(v);
        });
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            String v = settings.get((String) inv.getArgument(0));
            return v == null ? inv.getArgument(1) : Boolean.parseBoolean(v);
        });
        when(appSettings.getOverride(anyString())).thenAnswer(inv -> settings.get((String) inv.getArgument(0)));
        settings.put("site.monitor.app.base-url", "https://sm.example/");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn("SENT");
        when(email.fromAddress()).thenReturn("noreply@example.com");
        when(visibility.teamVisible(any())).thenReturn(true);
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 2));
        job = newJob();
    }

    private TechErrorReminderService newJob() {
        TechErrorReminderService j = new TechErrorReminderService(service, reminders, reports, channels, approvals, tokens, teams,
                email, logs, appSettings, visibility);
        j.setMaintenance(maintenance);
        j.setPush(push);
        return j;
    }

    private void at(Instant i) {
        service.useClock(Clock.fixed(i, ZoneOffset.UTC));
    }

    private Team team(String name, String mail) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(mail);
        t.setActive(true);
        return teams.save(t);
    }

    private AppUser user(String name, String display, String mail, String role, Long teamId) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setDisplayName(display);
        u.setEmail(mail);
        u.setOrgRole(role);
        u.setTeamId(teamId);
        u.setTeamIds(new LinkedHashSet<>(Set.of(teamId)));
        u.setActive(true);
        return users.save(u);
    }

    private TechErrorChannel channel(Team t, String name) {
        return service.createChannel(t.getId(), name, null, TechErrorTemplate.defaults("tr"), tech.getId());
    }

    private void optIn(Team... ts) {
        StringBuilder sb = new StringBuilder();
        for (Team t : ts) sb.append(sb.isEmpty() ? "" : ",").append(t.getId());
        settings.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, sb.toString());
    }

    private List<NotificationLog> rows(String trigger) {
        return logs.findAll().stream().filter(n -> trigger.equals(n.getTrigger())).toList();
    }

    private void markSent(TechErrorReport r) {
        Instant now = service.now();
        assertThat(reports.claimSend(r.getId(), now, now.minus(Duration.ofMinutes(10)))).isEqualTo(1);
        long rev = service.report(r.getId()).getContentRev() == null ? 0 : service.report(r.getId()).getContentRev();
        assertThat(reports.finishSent(r.getId(), now, "SENT", now, tech.getId(), "dijital@example.com", rev)).isEqualTo(1);
    }

    private void verifyMails(int n) {
        verify(email, times(n)).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("yalnız hatırlatmaya AÇIK takım: tek posta (takım adresi, Türkçe konu + dönem, kanal tablosu, derin bağlantı), push, günlük SENT; boş liste = herkes için kapalı")
    void optInOnly() {
        channel(dijital, "Mobil");
        channel(odeme, "POS");
        assertThat(job.run().teamsReminded()).as("boş liste: kapalı").isZero();
        verifyMails(0);
        assertThat(logs.findAll()).isEmpty();

        optIn(dijital);
        TechErrorReminderService.Result res = job.run();
        assertThat(res.teamsReminded()).isEqualTo(1);
        assertThat(res.pushTeams()).isEqualTo(1);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(to.capture(), any(), subject.capture(), html.capture(), anyString(), any(), eq(false), any());
        assertThat(to.getValue()).containsExactly("dijital@example.com");
        assertThat(subject.getValue()).isEqualTo("[Site Monitor] Teknik hata raporu hatırlatması — Eylül 2026");
        assertThat(html.getValue()).contains("Mobil").contains("Rapor açılmadı").doesNotContain("POS")
                .contains("tab=channelreports&amp;te_st=missing&amp;te_team=" + dijital.getId());
        List<NotificationLog> log = rows(TechErrorReminderService.TRIGGER);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getEmailStatus()).startsWith("SENT").contains("push 2");
        assertThat(log.get(0).getRecipientRole()).isEqualTo("TEAM");
        assertThat(log.get(0).getMessage()).contains("Mobil — Rapor açılmadı");
        @SuppressWarnings("unchecked") ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        verify(push).enqueueTeamNoticeLocalized(eq(dijital.getId()), eq(TechErrorReminderService.TRIGGER), eq("WARNING"),
                eq(TechErrorReminderService.PUSH_MONITOR_TYPE), anyString(), text.capture(), anyString(), any());
        assertThat(text.getValue().tr()).contains("Eylül 2026").contains("Mobil").doesNotContain("http");
        assertThat(text.getValue().en()).contains("September 2026").doesNotContain("http");
        // Talep yazıldı: kanal × dönem × PERIOD
        TechErrorReminder claim = reminders.findAll().get(0);
        assertThat(claim.getKind()).isEqualTo(TechErrorReminder.KIND_PERIOD);
        assertThat(claim.getReportMonth()).isEqualTo(9);
        assertThat(claim.getLastSentOn()).isEqualTo("2026-10-05");
    }

    @Test
    @DisplayName("gün sınırı İSTANBUL saatine göre: 4 Ekim 23:30 (İst) yok, 5 Ekim 00:30 (İst = 4 Ekim 21:30 UTC) var")
    void reminderDayIsIstanbul() {
        optIn(dijital);
        channel(dijital, "Mobil");
        at(Instant.parse("2026-10-04T20:30:00Z"));
        job.run();
        verifyMails(0);
        at(Instant.parse("2026-10-04T21:30:00Z"));
        job.run();
        verifyMails(1);
        // Gün ayarı: 10 → 5 Ekim artık erken
        reminders.deleteAll();
        settings.put(TechErrorReportSettings.KEY_REMINDER_DAY, "10");
        job.run();
        verifyMails(1);
    }

    @Test
    @DisplayName("tekrar penceresi: aynı gün ve sonraki 2 gün yok, 3. gün yeniden; 0 = yalnız bir kez; yeni ay = yeni dönem (Eylül artık hatırlatılmaz)")
    void repeatWindow() {
        optIn(dijital);
        channel(dijital, "Mobil");
        job.run();
        job.run();
        verifyMails(1);
        at(OCT5.plus(Duration.ofDays(2)));
        job.run();
        verifyMails(1);
        at(OCT5.plus(Duration.ofDays(3)));
        job.run();
        verifyMails(2);
        settings.put(TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "0");
        at(OCT5.plus(Duration.ofDays(10)));
        job.run();
        verifyMails(2);
        // Kasım: dönem Ekim — Eylül iki ay eskidi; Ekim için ilk hatırlatma
        at(Instant.parse("2026-11-05T06:15:00Z"));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        job.run();
        verify(email, times(3)).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getAllValues().get(2)).endsWith("Ekim 2026");
    }

    @Test
    @DisplayName("gönderilmiş, kuyruktaki ve gönderimden sonra değişen rapor hatırlatılmaz; taslak hatırlatılır")
    void sentReportIsNotReminded() {
        optIn(dijital);
        TechErrorChannel sent = channel(dijital, "Gönderilen");
        TechErrorChannel changed = channel(dijital, "Değişen");
        TechErrorChannel queued = channel(dijital, "Kuyrukta");
        TechErrorChannel draft = channel(dijital, "Taslak");
        markSent(service.createReport(sent, 2026, 9, tech.getId()));
        TechErrorReport ch = service.createReport(changed, 2026, 9, tech.getId());
        markSent(ch);
        reports.touchContent(ch.getId(), service.now());
        TechErrorReport q = service.createReport(queued, 2026, 9, tech.getId());
        Instant now = service.now();
        reports.claimSend(q.getId(), now, now.minus(Duration.ofMinutes(10)));
        reports.finishQueued(q.getId(), now, "QUEUED_RETRY: 421", now, 1L, tech.getId(), "dijital@example.com", 0L);
        service.createReport(draft, 2026, 9, tech.getId());
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("Taslak — gönderilmedi").doesNotContain("Gönderilen").doesNotContain("Değişen")
                .doesNotContain("Kuyrukta");
        // Yalnız taslak listelendi → derin bağlantı "taslak ya da değişen" süzgeciyle
        assertThat(html.getValue()).contains("te_st=open");
        assertThat(reminders.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("onay bekleyen rapor 'PO onayı bekliyor' cümlesiyle, reddedilen ve onay sürecinde değişen kendi cümlesiyle; hepsi gönderilene kadar listede")
    void pendingApprovalText() {
        optIn(dijital);
        TechErrorChannel a = channel(dijital, "Onaylı");
        TechErrorChannel b = channel(dijital, "Reddedilen");
        TechErrorChannel c = channel(dijital, "Değişen");
        service.updateChannel(a.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        TechErrorReport ra = service.createReport(service.channel(a.getId()), 2026, 9, tech.getId());
        Instant now = service.now();
        reports.requestApproval(ra.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        TechErrorReport rb = service.createReport(b, 2026, 9, tech.getId());
        reports.requestApproval(rb.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        reports.reject(rb.getId(), 0L, now, po1.getId(), "Eksik");
        TechErrorReport rc = service.createReport(c, 2026, 9, tech.getId());
        reports.requestApproval(rc.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        reports.touchContent(rc.getId(), now);
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("PO onayı bekliyor").contains("Reddedildi — düzeltip yeniden onaya gönderin")
                .contains("Onay sürecinde değişti").contains("PO&#39;ya ayrıca hatırlatma gider");
    }

    @Test
    @DisplayName("modül takıma kapalı → posta yok, push yok, günlükte 'SKIPPED: modül bu takıma kapalı' (talep harcanır — her gün yeni satır yok)")
    void moduleHiddenIsSkipped() {
        optIn(dijital);
        channel(dijital, "Mobil");
        when(visibility.teamVisible(dijital.getId())).thenReturn(false);
        TechErrorReminderService.Result res = job.run();
        assertThat(res.skipped()).isEqualTo(1);
        verifyMails(0);
        verify(push, never()).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: modül bu takıma kapalı"));
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        assertThat(rows(TechErrorReminderService.TRIGGER)).hasSize(1);
    }

    @Test
    @DisplayName("takım adresi yok: posta denenmez, günlükte SKIPPED; push açıksa takım üyelerine yine gider")
    void noAddressIsSkipped() {
        dijital.setEmail(null);
        teams.save(dijital);
        optIn(dijital);
        channel(dijital, "Mobil");
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).startsWith("SKIPPED: takımın e-posta adresi yok").contains("push 2"));
        // Push kapalı ve adres yok: yalnız günlük satırı, talep harcanır (ertesi gün tekrar yok)
        reminders.deleteAll();
        logs.deleteAll();
        settings.put(TechErrorReportSettings.KEY_REMINDER_PUSH, "false");
        job.run();
        verify(push, times(1)).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: takımın e-posta adresi yok"));
        assertThat(reminders.findAll()).singleElement().satisfies(r -> assertThat(r.getLastSentOn()).isEqualTo("2026-10-05"));
    }

    @Test
    @DisplayName("sistem bakımı susturması: talep YOK, posta yok, 'SKIPPED: sistem bakımı' satırı; bakım bitince ertesi gün gönderilir")
    void maintenanceMuteMakesNoClaim() {
        optIn(dijital);
        channel(dijital, "Mobil");
        when(maintenance.notificationsMuted()).thenReturn(true);
        job.run();
        verifyMails(0);
        assertThat(reminders.findAll()).isEmpty();
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı"));
        when(maintenance.notificationsMuted()).thenReturn(false);
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        verifyMails(1);
    }

    @Test
    @DisplayName("iki pod (aynı tablo): ikinci koşu talebi alamaz → tek posta; bayat sürümle talep null")
    void twoPodsOneMail() {
        optIn(dijital);
        channel(dijital, "Mobil");
        TechErrorReminderService pod2 = newJob();
        job.run();
        pod2.run();
        verifyMails(1);
        // Koşullu talep: okunan sürüm başka podda ilerlediyse sahiplenilemez
        TechErrorReminder row = reminders.findAll().get(0);
        TechErrorReminder stale = new TechErrorReminder();
        stale.setId(row.getId());
        stale.setClaimRev(row.getClaimRev() - 1);
        assertThat(job.claim(row.getChannelId(), java.time.YearMonth.of(2026, 9), TechErrorReminder.KIND_PERIOD, stale, "2026-10-08", ""))
                .isNull();
        // Satır yokken iki ilk talep (iki pod aynı anda okudu: ikisi de satır görmedi): yalnız biri kazanır (benzersizlik)
        assertThat(job.claim(row.getChannelId(), java.time.YearMonth.of(2026, 8), TechErrorReminder.KIND_PERIOD, null, "2026-10-05", ""))
                .isNotNull();
        assertThat(pod2.claim(row.getChannelId(), java.time.YearMonth.of(2026, 8), TechErrorReminder.KIND_PERIOD, null, "2026-10-05", ""))
                .isNull();
    }

    @Test
    @DisplayName("push yalnız açıkken; posta başarısız ve push yoksa talep geri bırakılır → aynı gün yeniden denenir (kaybolmaz)")
    void pushOnlyWhenEnabledAndFailedMailIsRetried() {
        optIn(dijital);
        channel(dijital, "Mobil");
        settings.put(TechErrorReportSettings.KEY_REMINDER_PUSH, "false");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn("FAILED: 550 reddedildi").thenReturn("SENT");
        job.run();
        verify(push, never()).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).startsWith("FAILED"));
        assertThat(reminders.findAll()).singleElement().satisfies(r -> assertThat(r.getLastSentOn()).isNull());
        job.run();
        verifyMails(2);
        assertThat(rows(TechErrorReminderService.TRIGGER)).extracting(NotificationLog::getEmailStatus).containsExactly("FAILED: 550 reddedildi", "SENT");
        assertThat(reminders.findAll()).singleElement().satisfies(r -> assertThat(r.getLastSentOn()).isEqualTo("2026-10-05"));
        // Posta başarısız ama push ulaştı: talep harcanır (aynı gün ikinci push yok)
        reminders.deleteAll();
        settings.put(TechErrorReportSettings.KEY_REMINDER_PUSH, "true");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn("FAILED: x");
        job.run();
        job.run();
        verify(push, times(1)).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("İngilizce şablonlu kanallar: konu ve gövde İngilizce; karışıksa Türkçe")
    void englishWhenAllChannelsAreEnglish() {
        optIn(dijital);
        service.createChannel(dijital.getId(), "Mobile", null, TechErrorTemplate.defaults("en"), tech.getId());
        job.run();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getValue()).isEqualTo("[Site Monitor] Technical error report reminder — September 2026");
        reminders.deleteAll();
        channel(dijital, "Mobil");
        job.run();
        verify(email, times(2)).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getAllValues().get(subject.getAllValues().size() - 1)).startsWith("[Site Monitor] Teknik hata raporu hatırlatması");
    }

    @Test
    @DisplayName("beklenmeyen hata bir takımda kalır: o takımın talebi bırakılır, günlükte FAILED; diğer takım yine hatırlatılır")
    void unexpectedErrorStaysInOneTeam() {
        optIn(dijital, odeme);
        TechErrorChannel mobil = channel(dijital, "Mobil");
        channel(odeme, "POS");
        when(visibility.teamVisible(dijital.getId())).thenThrow(new IllegalStateException("ayar okunamadı"));
        job.run();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(to.capture(), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(to.getValue()).containsExactly("odeme@example.com");
        assertThat(rows(TechErrorReminderService.TRIGGER)).extracting(NotificationLog::getEmailStatus)
                .anySatisfy(s -> assertThat(s).isEqualTo("FAILED: IllegalStateException"))
                .anySatisfy(s -> assertThat(s).startsWith("SENT"));
        assertThat(reminders.findAll()).filteredOn(r -> r.getChannelId().equals(mobil.getId()))
                .singleElement().satisfies(r -> assertThat(r.getLastSentOn()).as("bırakıldı → ertesi koşu yeniden").isNull());
    }

    // ── Onay hatırlatması ──────────────────────────────────────────────────────────────────────────

    private TechErrorReport pendingSince(Instant requestedAt) {
        TechErrorChannel ch = channel(dijital, "Mobil");
        service.updateChannel(ch.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        TechErrorReport r = service.createReport(service.channel(ch.getId()), 2026, 9, tech.getId());
        assertThat(reports.requestApproval(r.getId(), 0L, requestedAt, tech.getId(), requestedAt.minus(Duration.ofMinutes(10)))).isEqualTo(1);
        return service.report(r.getId());
    }

    @Test
    @DisplayName("onay hatırlatması: istek 3 gündür bekliyorsa PO başına kısa posta (yeni belirteç YOK), bağlantı geçerliliği yazılır; aynı gün ve pencere içinde tekrar yok; 3 gün sonra yeniden")
    void approvalReminderOncePerWindow() {
        TechErrorReport r = pendingSince(OCT5.minus(Duration.ofDays(2)));
        TechErrorApprovalToken t = new TechErrorApprovalToken();
        t.setReportId(r.getId());
        t.setUserId(po1.getId());
        t.setTokenHash("a".repeat(64));
        t.setApprovalRev(0L);
        t.setCreatedAt(OCT5.minus(Duration.ofDays(2)));
        t.setExpiresAt(OCT5.plus(Duration.ofDays(12)));
        tokens.save(t);
        job.run();
        verifyMails(0);                                                   // 2 gün: henüz erken
        at(OCT5.plus(Duration.ofDays(1)));
        TechErrorReminderService.Result res = job.run();
        assertThat(res.approvalReports()).isEqualTo(1);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(to.capture(), any(), subject.capture(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(to.getAllValues()).extracting(a -> a[0]).containsExactlyInAnyOrder("po1@example.com", "po2@example.com");
        assertThat(subject.getAllValues()).allMatch(s -> s.startsWith("[Site Monitor] Onay bekleyen teknik hata raporu — Mobil"));
        int po1Mail = to.getAllValues().get(0)[0].startsWith("po1") ? 0 : 1;
        assertThat(html.getAllValues().get(po1Mail)).contains("kişisel bağlantı").contains("Teknik Kişi").contains("3 gündür")
                .doesNotContain("approve-link");
        assertThat(html.getAllValues().get(1 - po1Mail)).contains("süresi doldu ya da size gönderilmedi");
        assertThat(tokens.findByReportIdOrderByIdAsc(r.getId())).hasSize(1);   // yeni bağlantı üretilmedi
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).hasSize(2).allMatch(n -> "PO".equals(n.getRecipientRole()) && "SENT".equals(n.getEmailStatus()));
        job.run();
        at(OCT5.plus(Duration.ofDays(3)));
        job.run();
        verifyMails(2);
        at(OCT5.plus(Duration.ofDays(4)));
        job.run();
        verifyMails(4);
    }

    @Test
    @DisplayName("eskimiş istek (içerik istekten sonra değişti) hiç hatırlatılmaz; karar verilmiş istek de")
    void staleRequestIsNeverReminded() {
        TechErrorReport r = pendingSince(OCT5.minus(Duration.ofDays(5)));
        reports.touchContent(r.getId(), service.now());
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).isEmpty();
        assertThat(reminders.findAll()).isEmpty();
    }

    @Test
    @DisplayName("onay hatırlatması: bakımda talep yok + SKIPPED; onaylayıcı adresi yoksa SKIPPED (talep harcanır)")
    void approvalReminderSkips() {
        pendingSince(OCT5.minus(Duration.ofDays(5)));
        when(maintenance.notificationsMuted()).thenReturn(true);
        job.run();
        assertThat(reminders.findAll()).isEmpty();
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı"));
        when(maintenance.notificationsMuted()).thenReturn(false);
        logs.deleteAll();
        po1.setEmail(null);
        po2.setEmail(" ");
        users.saveAll(List.of(po1, po2));
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: onaylayıcı e-postası yok"));
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getKind()).isEqualTo(TechErrorReminder.KIND_APPROVAL));
    }
}
