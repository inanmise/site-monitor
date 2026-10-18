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

    private Instant clock = OCT5;

    private void at(Instant i) {
        clock = i;
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

    /** Kanallar varsayılan olarak dönemden ÖNCE açılır (dönem bittikten sonra açılan kanal o dönem için hatırlatılmaz). */
    private static final Instant CHANNEL_CREATED = Instant.parse("2026-08-01T09:00:00Z");

    private TechErrorChannel channel(Team t, String name) {
        return channelAt(t, name, CHANNEL_CREATED, TechErrorTemplate.defaults("tr"));
    }

    private TechErrorChannel channelAt(Team t, String name, Instant created, TechErrorTemplate.Template tpl) {
        Instant keep = clock;
        at(created);
        try {
            return service.createChannel(t.getId(), name, null, tpl, tech.getId());
        } finally {
            at(keep);
        }
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
        assertThat(subject.getValue()).isEqualTo("[Site Monitor] Teknik hata raporu hatırlatması — Eylül 2026 · Dijital Kanallar");
        assertThat(html.getValue()).contains("Mobil").contains("Rapor açılmadı").doesNotContain("POS")
                .contains("tab=channelreports&amp;te_st=missing&amp;te_team=" + dijital.getId());
        List<NotificationLog> log = rows(TechErrorReminderService.TRIGGER);
        assertThat(log).hasSize(1);
        // inceleme 2026-10-18: email_status YALNIZ posta servisinin cevabı (SMTP günlüğü / SENT sayaçları "SENT" görür);
        // push notu iletide
        assertThat(log.get(0).getEmailStatus()).isEqualTo("SENT");
        assertThat(log.get(0).getMessage()).endsWith(" · push 2");
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
        assertThat(subject.getAllValues().get(2)).endsWith("Ekim 2026 · Dijital Kanallar");
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
        // Üçünün de onay akışı var (yönetici alıcısı): akışı olmayan kanalda onay durumu geçersizdir — inceleme 2026-10-18
        for (TechErrorChannel x : List.of(a, b, c)) service.updateChannel(x.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        TechErrorReport ra = service.createReport(service.channel(a.getId()), 2026, 9, tech.getId());
        Instant now = service.now();
        reports.requestApproval(ra.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        TechErrorReport rb = service.createReport(service.channel(b.getId()), 2026, 9, tech.getId());
        reports.requestApproval(rb.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        reports.reject(rb.getId(), 0L, now, po1.getId(), "Eksik");
        TechErrorReport rc = service.createReport(service.channel(c.getId()), 2026, 9, tech.getId());
        reports.requestApproval(rc.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        reports.touchContent(rc.getId(), now);
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("PO onayı bekliyor").contains("Reddedildi — düzeltip yeniden onaya gönderin")
                .contains("Onay sürecinde değişti").contains("PO&#39;ya ayrıca hatırlatma gider");
    }

    @Test
    @DisplayName("inceleme 2026-10-18: modül takıma kapalı → posta / push yok, 'SKIPPED: modül bu takıma kapalı' dönem başına BİR kez; dönem talebi HARCANMAZ → kapı açılınca (tekrar 0 olsa da) hatırlatma gider")
    void moduleHiddenIsSkipped() {
        optIn(dijital);
        channel(dijital, "Mobil");
        settings.put(TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "0");
        when(visibility.teamVisible(dijital.getId())).thenReturn(false);
        TechErrorReminderService.Result res = job.run();
        assertThat(res.skipped()).isEqualTo(1);
        verifyMails(0);
        verify(push, never()).enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: modül bu takıma kapalı"));
        assertThat(reminders.findAll()).singleElement().satisfies(r -> assertThat(r.getKind()).isEqualTo(TechErrorReminderService.SKIP_HIDDEN));
        at(OCT5.plus(Duration.ofDays(1)));
        assertThat(job.run().skipped()).as("aynı sebep ertesi gün yeni satır yazdırmaz").isZero();
        assertThat(rows(TechErrorReminderService.TRIGGER)).hasSize(1);
        // Kapı açıldı: dönem talebi boştu → hatırlatma bugün gider (tekrar 0 = "bir kez" yine de ilk hatırlatmayı alır)
        when(visibility.teamVisible(dijital.getId())).thenReturn(true);
        at(OCT5.plus(Duration.ofDays(2)));
        assertThat(job.run().teamsReminded()).isEqualTo(1);
        verifyMails(1);
        assertThat(reminders.findAll()).extracting(TechErrorReminder::getKind)
                .containsExactlyInAnyOrder(TechErrorReminderService.SKIP_HIDDEN, TechErrorReminder.KIND_PERIOD);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: pasif takım → 'SKIPPED: pasif takım' bir kez, talep yok; takım yeniden aktif olunca hatırlatılır")
    void passiveTeamIsSkippedBeforeClaim() {
        optIn(dijital);
        channel(dijital, "Mobil");
        dijital.setActive(false);
        teams.save(dijital);
        job.run();
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: pasif takım"));
        assertThat(reminders.findAll()).noneMatch(r -> TechErrorReminder.KIND_PERIOD.equals(r.getKind()));
        dijital.setActive(true);
        teams.save(dijital);
        at(OCT5.plus(Duration.ofDays(2)));
        job.run();
        verifyMails(1);
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
                .satisfies(n -> {
                    assertThat(n.getEmailStatus()).isEqualTo("SKIPPED: takımın e-posta adresi yok");
                    assertThat(n.getMessage()).contains("push 2");
                });
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
        channelAt(dijital, "Mobile", CHANNEL_CREATED, TechErrorTemplate.defaults("en"));
        job.run();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getValue()).isEqualTo("[Site Monitor] Technical error report reminder — September 2026 · Dijital Kanallar");
        reminders.deleteAll();
        channel(dijital, "Mobil");
        job.run();
        verify(email, times(2)).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getAllValues().get(subject.getAllValues().size() - 1)).startsWith("[Site Monitor] Teknik hata raporu hatırlatması");
    }

    @Test
    @DisplayName("beklenmeyen hata bir takımda kalır: o takımın talebi alınmaz / bırakılır, günlükte FAILED; diğer takım yine hatırlatılır")
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
        // Kapı talepten ÖNCE değerlendirilir: hata talebi hiç almadan durur (satır yok ya da bırakılmış) → ertesi koşu yeniden
        assertThat(reminders.findAll()).filteredOn(r -> r.getChannelId().equals(mobil.getId()))
                .allSatisfy(r -> assertThat(r.getLastSentOn()).as("bırakıldı → ertesi koşu yeniden").isNull());
        org.mockito.Mockito.doReturn(true).when(visibility).teamVisible(dijital.getId());
        job.run();
        verify(email, times(2)).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
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
        t.setMailedAt(OCT5.minus(Duration.ofDays(2)));                    // bağlantılı posta po1'e ulaştı
        tokens.save(t);
        // po2'nin belirteci var ama postası ULAŞMADI (PDF sırası dolu / posta kapalı / base-url boş): bağlantı anılmaz
        TechErrorApprovalToken t2 = new TechErrorApprovalToken();
        t2.setReportId(r.getId());
        t2.setUserId(po2.getId());
        t2.setTokenHash("b".repeat(64));
        t2.setApprovalRev(0L);
        t2.setCreatedAt(OCT5.minus(Duration.ofDays(2)));
        t2.setExpiresAt(OCT5.plus(Duration.ofDays(12)));
        tokens.save(t2);
        // po2'nin ÖNCEKİ (yeniden gönderilen, aynı rev) isteğinin belirteci: postası ulaşmıştı ama yerine geçildi → canlı değil
        TechErrorApprovalToken t3 = new TechErrorApprovalToken();
        t3.setReportId(r.getId());
        t3.setUserId(po2.getId());
        t3.setTokenHash("c".repeat(64));
        t3.setApprovalRev(0L);
        t3.setCreatedAt(OCT5.minus(Duration.ofDays(3)));
        t3.setExpiresAt(OCT5.plus(Duration.ofDays(11)));
        t3.setMailedAt(OCT5.minus(Duration.ofDays(3)));
        t3.setSupersededAt(OCT5.minus(Duration.ofDays(2)));
        tokens.save(t3);
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
        // po2'nin geçerli belirteci damgasız VE damgadan (20.153) önce üretildi: ulaşıp ulaşmadığı bilinmiyor → nötr metin
        // (inceleme 2026-10-18: eskiden "süresi doldu ya da size gönderilmedi" diyordu)
        assertThat(html.getAllValues().get(1 - po1Mail)).contains("özgün onay postası elinizdeyse")
                .doesNotContain("süresi doldu").doesNotContain("tarihine kadar geçerli");
        assertThat(tokens.findByReportIdOrderByIdAsc(r.getId())).hasSize(3);   // yeni bağlantı üretilmedi
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).hasSize(2).allMatch(n -> "PO".equals(n.getRecipientRole()) && "SENT".equals(n.getEmailStatus()));
        // Kanal gönderim günlüğü (E22, tür "reminder"): onay hatırlatması satırı rapora ve kanalına bağlıdır
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).allSatisfy(n -> {
            assertThat(n.getTechErrorReportId()).isEqualTo(r.getId());
            assertThat(n.getTechErrorChannelId()).isEqualTo(r.getChannelId());
        });
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

    // ── İnceleme 2026-10-18 ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inceleme 2026-10-18: altı aydan eski dönemin bekleyen onayı her gün DEĞİL pencere başına bir kez hatırlatılır (budama satır yaşına göre); 6 aydır güncellenmemiş satır silinir")
    void oldPeriodApprovalOncePerWindow() {
        TechErrorChannel ch = channel(dijital, "Mobil");
        service.updateChannel(ch.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        TechErrorReport r = service.createReport(service.channel(ch.getId()), 2026, 3, tech.getId());
        Instant asked = Instant.parse("2026-03-25T08:00:00Z");
        assertThat(reports.requestApproval(r.getId(), 0L, asked, tech.getId(), asked.minus(Duration.ofMinutes(10)))).isEqualTo(1);
        job.run();
        verifyMails(2);                                                   // iki PO
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        at(OCT5.plus(Duration.ofDays(2)));
        job.run();
        verifyMails(2);                                                   // pencere içinde tekrar yok (eskiden her gün yeniden)
        at(OCT5.plus(Duration.ofDays(3)));
        job.run();
        verifyMails(4);
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> {
            assertThat(rm.getKind()).isEqualTo(TechErrorReminder.KIND_APPROVAL);
            assertThat(rm.getReportMonth()).isEqualTo(3);
        });
        // Satır yaşı: 7 aydır güncellenmemiş satır silinir; eski dönemin TAZE satırı (yukarıdaki onay) kalır
        TechErrorReminder old = new TechErrorReminder();
        old.setChannelId(ch.getId());
        old.setReportYear(2026);
        old.setReportMonth(1);
        old.setKind(TechErrorReminder.KIND_PERIOD);
        old.setRefKey("");
        old.setLastSentOn("2026-02-05");
        old.setSendCount(1);
        old.setClaimRev(1);
        old.setCreatedAt("2026-02-05T06:15:00");
        old.setUpdatedAt("2026-02-05T06:15:00");
        reminders.save(old);
        job.run();
        assertThat(reminders.findAll()).extracting(TechErrorReminder::getReportMonth).containsExactly(3);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: dönem BİTTİKTEN sonra açılan kanal o dönem için hatırlatılmaz (bitiş = 1 Ekim 00:00 İstanbul); dönem içinde açılan hatırlatılır")
    void channelCreatedAfterPeriodIsNotReminded() {
        optIn(dijital);
        channelAt(dijital, "KanalEski", Instant.parse("2026-09-30T20:59:59Z"), TechErrorTemplate.defaults("tr"));   // dönemin son anı
        channelAt(dijital, "KanalSinir", Instant.parse("2026-09-30T21:00:00Z"), TechErrorTemplate.defaults("tr"));  // tam dönem bitişi
        channelAt(dijital, "KanalYeni", Instant.parse("2026-10-02T09:00:00Z"), TechErrorTemplate.defaults("tr"));
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("KanalEski").doesNotContain("KanalSinir").doesNotContain("KanalYeni");
        assertThat(reminders.findAll()).hasSize(1);
        assertThat(TechErrorReminderService.periodEnd(java.time.YearMonth.of(2026, 9))).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
    }

    private NotificationLog sendLog(String status) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(0L);
        n.setSentAt("2026-10-05T05:50:00");
        n.setRecipientName("Dijital Kanallar");
        n.setRecipientEmail("dijital@example.com");
        n.setRecipientRole("TEAM");
        n.setSubject("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        n.setEmailStatus(status);
        n.setWebhookStatus("SKIPPED");
        n.setTrigger("TECH_ERROR_REPORT");
        n.setEmailFrom("noreply@example.com");
        return logs.save(n);
    }

    private TechErrorReport queue(TechErrorChannel ch, Instant claimedAt, Long logId) {
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        assertThat(reports.claimSend(r.getId(), claimedAt, claimedAt.minus(Duration.ofMinutes(10)))).isEqualTo(1);
        assertThat(reports.finishQueued(r.getId(), claimedAt, "QUEUED_RETRY: 421", claimedAt, logId, tech.getId(),
                "dijital@example.com", 0L)).isEqualTo(1);
        return r;
    }

    @Test
    @DisplayName("inceleme 2026-10-18: koşu başında kuyruk sonuçlandırılır (posta servisi SENT yazdı → gönderilmiş, hatırlatılmaz); sonucu artık gelemeyecek kuyruk (> 20 dk) 'Gönderim sonucu alınamadı' cümlesiyle hatırlatılır; taze kuyruk beklenir")
    void stuckQueuedIsReminded() {
        optIn(dijital);
        Instant old = OCT5.minus(Duration.ofMinutes(25));
        TechErrorReport delivered = queue(channel(dijital, "KuyrukTeslim"), old, sendLog("SENT").getId());
        queue(channel(dijital, "KuyrukTakili"), old, sendLog("QUEUED_RETRY: 421").getId());
        queue(channel(dijital, "KuyrukTaze"), OCT5.minus(Duration.ofMinutes(5)), sendLog("QUEUED_RETRY: 421").getId());
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("KuyrukTakili").contains("Gönderim sonucu alınamadı")
                .doesNotContain("KuyrukTeslim").doesNotContain("KuyrukTaze");
        assertThat(service.report(delivered.getId()).getLastSentAt()).as("kuyruk sonuçlandı").isNotNull();
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).contains("KuyrukTakili — Gönderim sonucu alınamadı"));
    }

    @Test
    @DisplayName("inceleme 2026-10-18: zamanlanmış tur küme genelinde GÜNDE BİR (tur kaydı): aynı gün ikinci pod'un turu hiçbir şey yapmaz; ertesi gün yeniden; kayıt okunamazsa tur yine koşar")
    void dailyRoundOncePerCluster() {
        optIn(dijital);
        channel(dijital, "Mobil");
        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:teround" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute(com.sitemonitor.service.schedule.ClusterScheduleService.DDL);
        job.setClusterSchedule(new com.sitemonitor.service.schedule.ClusterScheduleService(jdbc, "pod-a"));
        TechErrorReminderService pod2 = newJob();
        pod2.setClusterSchedule(new com.sitemonitor.service.schedule.ClusterScheduleService(jdbc, "pod-b"));
        // Bakımda talep yazılmaz: tur kapısı olmasa her koşu yeni bir SKIPPED satırı yazardı
        when(maintenance.notificationsMuted()).thenReturn(true);
        job.runRound();
        assertThat(pod2.runRound()).isEqualTo(new TechErrorReminderService.Result(0, 0, 0, 0));
        assertThat(job.runRound()).isEqualTo(new TechErrorReminderService.Result(0, 0, 0, 0));
        assertThat(rows(TechErrorReminderService.TRIGGER)).hasSize(1);
        when(maintenance.notificationsMuted()).thenReturn(false);
        at(OCT5.plus(Duration.ofDays(1)));
        assertThat(pod2.runRound().teamsReminded()).isEqualTo(1);
        assertThat(job.runRound().teamsReminded()).isZero();
        verifyMails(1);
        // Kayıt okunamıyor: tur koşar (talep satırları çift postayı yine önler)
        jdbc.execute("DROP TABLE monitor_check_schedule");
        assertThat(job.roundDue(service.now())).isTrue();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: push notu gerçek sebebi söyler — alıcılar vardı ama hepsi atlandıysa sayı, servis sebebi varsa o; 'alıcı yok' yalnız gerçekten yoksa")
    void pushNoteTellsTheReason() {
        assertThat(TechErrorReminderService.pushNote(Map.of("queued", 2), 2)).isEqualTo(" · push 2");
        assertThat(TechErrorReminderService.pushNote(Map.of("queued", 0, "skipped", 3), 0))
                .isEqualTo(" · push: 3 alıcı atlandı (tercih / sınır / tekrar)");
        assertThat(TechErrorReminderService.pushNote(Map.of("queued", 0, "skipped", 0, "reason", "SKIPPED_DISABLED"), 0))
                .isEqualTo(" · push: SKIPPED_DISABLED");
        assertThat(TechErrorReminderService.pushNote(Map.of("queued", 0, "skipped", 0), 0)).isEqualTo(" · push: alıcı yok");
        optIn(dijital);
        channel(dijital, "Mobil");
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 0, "skipped", 3));
        job.run();
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> {
                    assertThat(n.getEmailStatus()).isEqualTo("SENT");
                    assertThat(n.getMessage()).endsWith(" · push: 3 alıcı atlandı (tercih / sınır / tekrar)");
                });
    }

    @Test
    @DisplayName("inceleme 2026-10-18: konu takıma / rapora özgü — aynı koşudaki iki takımın konusu farklı (421 yeniden deneme sonucu konuyla eşlenir); özel başlık kanalı / dönemi taşımıyorsa eklenir")
    void subjectsAreUnique() {
        optIn(dijital, odeme);
        channel(dijital, "Mobil");
        channel(odeme, "POS");
        job.run();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(subject.getAllValues()).containsExactlyInAnyOrder(
                "[Site Monitor] Teknik hata raporu hatırlatması — Eylül 2026 · Dijital Kanallar",
                "[Site Monitor] Teknik hata raporu hatırlatması — Eylül 2026 · Ödeme Sistemleri");
        assertThat(rows(TechErrorReminderService.TRIGGER)).extracting(NotificationLog::getSubject).doesNotHaveDuplicates();
        assertThat(com.sitemonitor.service.mail.TechErrorReminderMail.uniqueTitle("Aylık rapor", "Mobil", "Eylül 2026"))
                .isEqualTo("Aylık rapor · Mobil · Eylül 2026");
        assertThat(com.sitemonitor.service.mail.TechErrorReminderMail.uniqueTitle("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026", "Mobil", "Eylül 2026"))
                .isEqualTo("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
    }

    @Test
    @DisplayName("inceleme 2026-10-18: onay hatırlatması kapıları talepten ÖNCE — onay akışı kaldırılmış kanal, pasif takım, modülü kapalı takım hatırlatılmaz; her sebep günlükte bir kez, onay talebi boş kalır; kapı açılınca hatırlatılır")
    void approvalGates() {
        TechErrorReport r = pendingSince(OCT5.minus(Duration.ofDays(5)));
        service.updateChannel(r.getChannelId(), Map.of("executive_emails", ""), tech.getId());      // akış kaldırıldı
        job.run();
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).extracting(NotificationLog::getEmailStatus)
                .containsExactly("SKIPPED: kanalın onay akışı kaldırıldı");
        service.updateChannel(r.getChannelId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        dijital.setActive(false);
        teams.save(dijital);
        job.run();
        job.run();
        dijital.setActive(true);
        teams.save(dijital);
        when(visibility.teamVisible(dijital.getId())).thenReturn(false);
        job.run();
        job.run();
        verifyMails(0);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).extracting(NotificationLog::getEmailStatus)
                .containsExactly("SKIPPED: kanalın onay akışı kaldırıldı", "SKIPPED: pasif takım", "SKIPPED: modül bu takıma kapalı");
        assertThat(reminders.findAll()).noneMatch(rm -> TechErrorReminder.KIND_APPROVAL.equals(rm.getKind()));
        when(visibility.teamVisible(dijital.getId())).thenReturn(true);
        job.run();
        verifyMails(2);
    }

    // ── İnceleme 2026-10-18 (2. tur) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inceleme 2026-10-18: onay akışı kaldırılmış kanalda (yönetici alıcısı yok) eski onay durumu geçersiz — bekleyen / reddedilen rapor 'Taslak — gönderilmedi' cümlesiyle, 'PO'ya ayrıca hatırlatma gider' notu yok; akış varken eski cümleler")
    void noApprovalFlowMeansDraft() {
        optIn(dijital);
        TechErrorChannel a = channel(dijital, "Bekleyen");
        TechErrorChannel b = channel(dijital, "Reddedilen");
        for (TechErrorChannel c : List.of(a, b)) service.updateChannel(c.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        Instant now = service.now();
        TechErrorReport ra = service.createReport(service.channel(a.getId()), 2026, 9, tech.getId());
        reports.requestApproval(ra.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        TechErrorReport rb = service.createReport(service.channel(b.getId()), 2026, 9, tech.getId());
        reports.requestApproval(rb.getId(), 0L, now, tech.getId(), now.minus(Duration.ofMinutes(10)));
        reports.reject(rb.getId(), 0L, now, po1.getId(), "Eksik");
        // Yönetici alıcıları kaldırıldı: onay akışı yok — ekip takım adresine doğrudan gönderebilir
        for (TechErrorChannel c : List.of(a, b)) service.updateChannel(c.getId(), Map.of("executive_emails", ""), tech.getId());
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getValue()).contains("Taslak — gönderilmedi").doesNotContain("PO onayı bekliyor")
                .doesNotContain("Reddedildi").doesNotContain("PO&#39;ya ayrıca hatırlatma gider");
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).contains("Bekleyen — Taslak — gönderilmedi").contains("Reddedilen — Taslak — gönderilmedi"));
        // Saf kural: akış varken eski cümleler, yokken taslak
        TechErrorReportRepository.Row pending = service.periodRows(List.of(a.getId()), java.time.YearMonth.of(2026, 9)).get(a.getId());
        assertThat(TechErrorReminderService.stateOf(pending, now, true)).isEqualTo(com.sitemonitor.service.mail.TechErrorReminderMail.State.PENDING);
        assertThat(TechErrorReminderService.stateOf(pending, now, false)).isEqualTo(com.sitemonitor.service.mail.TechErrorReminderMail.State.DRAFT);
        assertThat(TechErrorReminderService.stateOf(null, now, false)).isEqualTo(com.sitemonitor.service.mail.TechErrorReminderMail.State.MISSING);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: takım adresinde 20'den fazla alıcı — posta ilk 20'ye gider, fazlası SESSİZ düşmez: günlük iletisi '+N adres sınır dışında' der")
    void periodReminderOverflowIsLogged() {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 22; i++) many.append(i == 1 ? "" : ",").append("k").append(i).append("@x.io");   // kolon 200 karakter
        dijital.setEmail(many.toString());
        teams.save(dijital);
        optIn(dijital);
        channel(dijital, "Mobil");
        job.run();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(to.capture(), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any());
        assertThat(to.getValue()).hasSize(TechErrorReportSender.MAX_RECIPIENTS).contains("k1@x.io", "k20@x.io")
                .doesNotContain("k21@x.io");
        assertThat(rows(TechErrorReminderService.TRIGGER)).singleElement().satisfies(n -> {
            assertThat(n.getEmailStatus()).isEqualTo("SENT");
            assertThat(n.getMessage()).contains("+2 adres sınır dışında (en çok 20 alıcı)");
        });
        assertThat(TechErrorReminderService.overflowNote(0)).isEmpty();
        // Not iletiyi kırpmaz: uzun özet kısalır, not kalır
        String longMsg = "x".repeat(5000);
        assertThat(TechErrorReminderService.withNote(longMsg, " · push 2")).hasSize(4000).endsWith(" · push 2");
        assertThat(TechErrorReminderService.withNote("Mobil", "")).isEqualTo("Mobil");
    }

    private TechErrorReport pendingOn(String channelName, Instant requestedAt) {
        TechErrorChannel ch = channel(dijital, channelName);
        service.updateChannel(ch.getId(), Map.of("executive_emails", "ceo@example.com"), tech.getId());
        TechErrorReport r = service.createReport(service.channel(ch.getId()), 2026, 9, tech.getId());
        assertThat(reports.requestApproval(r.getId(), 0L, requestedAt, tech.getId(), requestedAt.minus(Duration.ofMinutes(10)))).isEqualTo(1);
        return service.report(r.getId());
    }

    @Test
    @DisplayName("inceleme 2026-10-18: ilk onay hatırlatması da İSTANBUL GÜNÜYLE — 2 Ekim'de 09:15'ten önce ve sonra yapılan istekler 5 Ekim 09:15'te (3 gün) ikisi de hatırlatılır (eski 72 saat kuralı 11:00 isteğini bir gün geç hatırlatıyordu); 3 Ekim isteği henüz değil")
    void firstApprovalReminderUsesIstanbulDays() {
        TechErrorReport early = pendingOn("Erken", Instant.parse("2026-10-02T05:00:00Z"));    // 2 Ekim 08:00 İst
        TechErrorReport late = pendingOn("Geç", Instant.parse("2026-10-02T08:00:00Z"));       // 2 Ekim 11:00 İst (72 saat dolmadı)
        TechErrorReport next = pendingOn("Ertesi", Instant.parse("2026-10-03T05:00:00Z"));    // 3 Ekim 08:00 İst
        TechErrorReminderService.Result res = job.run();
        assertThat(res.approvalReports()).isEqualTo(2);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).extracting(NotificationLog::getTechErrorReportId)
                .containsOnly(early.getId(), late.getId()).doesNotContain(next.getId());
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).extracting(NotificationLog::getTechErrorReportId).contains(next.getId());
        // Saf kural: istek günü + N ≤ bugün (saat değil); İstanbul gece yarısı sınırı (2 Ekim 23:30 İst = 2 Ekim 20:30 UTC)
        java.time.LocalDate oct5 = java.time.LocalDate.of(2026, 10, 5);
        assertThat(TechErrorReminderService.firstDue(Instant.parse("2026-10-02T20:30:00Z"), oct5, 3)).isTrue();
        assertThat(TechErrorReminderService.firstDue(Instant.parse("2026-10-02T21:30:00Z"), oct5, 3)).as("3 Ekim 00:30 İst").isFalse();
        assertThat(TechErrorReminderService.firstDue(null, oct5, 3)).isFalse();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: postadaki 'N gündür bekliyor' İSTANBUL GÜNLERİYLE — 4 Ekim 16:00 isteği 5 Ekim 09:15'te '1 gündür' (24 saatlik adım '0 gündür' diyordu); N=3'te altbilgi ile gövde aynı sayıyı söyler")
    void waitingDaysCountIstanbulDays() {
        settings.put(TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, "1");
        pendingOn("Mobil", Instant.parse("2026-10-04T13:00:00Z"));                             // 4 Ekim 16:00 İst
        job.run();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(any(), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(html.getAllValues()).allSatisfy(h -> assertThat(h).contains("1 gündür").doesNotContain("0 gündür"));
        // Saf kural: gün farkı (saat değil); İstanbul gece yarısı sınırı; negatif / boş → 0
        java.time.LocalDate oct5 = java.time.LocalDate.of(2026, 10, 5);
        assertThat(TechErrorReminderService.waitingDays(Instant.parse("2026-10-02T13:00:00Z"), oct5)).as("2 Ekim 16:00 → 3 gün").isEqualTo(3);
        assertThat(TechErrorReminderService.waitingDays(Instant.parse("2026-10-04T21:30:00Z"), oct5)).as("5 Ekim 00:30 İst").isZero();
        assertThat(TechErrorReminderService.waitingDays(Instant.parse("2026-10-04T20:30:00Z"), oct5)).as("4 Ekim 23:30 İst").isEqualTo(1);
        assertThat(TechErrorReminderService.waitingDays(Instant.parse("2026-10-07T06:00:00Z"), oct5)).isZero();
        assertThat(TechErrorReminderService.waitingDays(null, oct5)).isZero();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: damgadan (20.153) SONRA üretilen ve postası ulaşmayan belirteç için hatırlatma 'süresi doldu ya da size gönderilmedi' der; ulaşmış canlı bağlantı bitişiyle anılır")
    void unmailedTokenAfterStampSaysNotSent() {
        Instant nov = Instant.parse("2026-11-10T06:15:00Z");                                 // 10 Kasım 09:15 İst
        at(nov);
        TechErrorReport r = pendingOn("Mobil", nov.minus(Duration.ofDays(3)));
        for (AppUser po : List.of(po1, po2)) {
            TechErrorApprovalToken t = new TechErrorApprovalToken();
            t.setReportId(r.getId());
            t.setUserId(po.getId());
            t.setTokenHash((po == po1 ? "d" : "e").repeat(64));
            t.setApprovalRev(0L);
            t.setCreatedAt(nov.minus(Duration.ofDays(3)));
            t.setExpiresAt(nov.plus(Duration.ofDays(11)));
            t.setMailTracked(Boolean.TRUE);                                                 // damgayı izleyen sürüm üretti
            if (po == po1) t.setMailedAt(nov.minus(Duration.ofDays(3)));
            tokens.save(t);
        }
        job.run();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(to.capture(), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        int po1Mail = to.getAllValues().get(0)[0].startsWith("po1") ? 0 : 1;
        assertThat(html.getAllValues().get(po1Mail)).contains("tarihine kadar geçerli").doesNotContain("süresi doldu");
        assertThat(html.getAllValues().get(1 - po1Mail)).contains("süresi doldu ya da size gönderilmedi")
                .doesNotContain("özgün onay postası elinizdeyse");
        TechErrorReminderService.Links links = job.links(reports.findByApprovalStateAndApprovalRequestedAtLessThanEqual("PENDING", nov).get(0), nov);
        assertThat(links.live()).containsOnlyKeys(po1.getId());
        assertThat(links.unconfirmed()).isEmpty();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: ayrım takvim tarihinden değil VERİDEN — eski sürümün / yuvarlanan dağıtımda eski pod'un ürettiği damgasız belirteç (mail_tracked NULL) eski tarih sınırından SONRA da nötr metin alır; damgayı izleyen sürümün damgasız belirteci 'gönderilmedi' der")
    void untrackedTokenIsNeutralWhateverTheDate() {
        Instant nov = Instant.parse("2026-11-10T06:15:00Z");                                 // eski sabit sınırdan (19 Ekim) çok sonra
        at(nov);
        TechErrorReport r = pendingOn("Mobil", nov.minus(Duration.ofDays(3)));
        for (AppUser po : List.of(po1, po2)) {
            TechErrorApprovalToken t = new TechErrorApprovalToken();
            t.setReportId(r.getId());
            t.setUserId(po.getId());
            t.setTokenHash((po == po1 ? "f" : "9").repeat(64));
            t.setApprovalRev(0L);
            t.setCreatedAt(nov.minus(Duration.ofDays(3)));
            t.setExpiresAt(nov.plus(Duration.ofDays(11)));
            if (po == po2) t.setMailTracked(Boolean.TRUE);                                  // po1: eski pod (bayrak yok)
            tokens.save(t);
        }
        job.run();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(to.capture(), any(), anyString(), html.capture(), anyString(), any(), anyBoolean(), any());
        int po1Mail = to.getAllValues().get(0)[0].startsWith("po1") ? 0 : 1;
        assertThat(html.getAllValues().get(po1Mail)).contains("özgün onay postası elinizdeyse")
                .doesNotContain("süresi doldu").doesNotContain("tarihine kadar geçerli");
        assertThat(html.getAllValues().get(1 - po1Mail)).contains("süresi doldu ya da size gönderilmedi");
        TechErrorReminderService.Links links = job.links(reports.findByApprovalStateAndApprovalRequestedAtLessThanEqual("PENDING", nov).get(0), nov);
        assertThat(links.live()).isEmpty();
        assertThat(links.unconfirmed()).containsOnly(po1.getId());
    }

    // ── Onay hatırlatması push'u (2026-10-18) ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("onay hatırlatması push'u: aynı talep / pencere, tüm PO'lar (e-postasız dahil, isteyen hariç); posta BAŞARISIZ ama push HER PO'ya ulaştı → talep harcanır (ertesi gün yeniden YOK); ikisi de ulaşmadı → bırakılır; kapalı anahtar → push yok, posta sürer")
    void approvalReminderPushCountsAsDelivered() {
        TechErrorPushService pushes = new TechErrorPushService(appSettings, users);
        pushes.setPush(push);
        pushes.setVisibility(visibility);
        job.setPushes(pushes);
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> Map.of("queued", ((List<?>) inv.getArgument(0)).size()));
        AppUser noMail = user("po3", "PO Üç", null, "PO", dijital.getId());
        TechErrorReport r = pendingSince(OCT5.minus(Duration.ofDays(3)));
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn("FAILED: 550");
        TechErrorReminderService.Result res = job.run();
        assertThat(res.approvalReports()).isEqualTo(1);
        org.mockito.ArgumentCaptor<List<UserPushService.DirectRecipient>> who = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(push).enqueueDirectLocalized(who.capture(), eq(dijital.getId()), eq(TechErrorPushService.TRIGGER_APPROVAL_REMINDER),
                eq("WARNING"), eq("TECH_ERROR_REPORT"), anyString(), any(), key.capture());
        assertThat(who.getValue()).extracting(UserPushService.DirectRecipient::username).containsExactly("po1", "po2", "po3");
        assertThat(key.getValue()).isEqualTo("te-appr-remind:" + r.getId() + ":" + r.getApprovalRequestedAt().toEpochMilli() + ":2026-10-05");
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).hasSize(2).allSatisfy(n -> assertThat(n.getMessage()).endsWith(" · push 3"));
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getLastSentOn()).isEqualTo("2026-10-05"));
        at(OCT5.plus(Duration.ofDays(1)));
        job.run();                                                         // pencere içinde: posta da push da yok
        verify(push, times(1)).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());

        // E-postası olan PO kalmadı: "onaylayıcı e-postası yok" satırı + push ile ulaşılır
        reminders.deleteAll();
        logs.deleteAll();
        for (AppUser u : List.of(po1, po2)) { u.setEmail(null); users.save(u); }
        at(OCT5.plus(Duration.ofDays(4)));
        res = job.run();
        assertThat(res.approvalReports()).isEqualTo(1);
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).singleElement()
                .satisfies(n -> assertThat(n.getEmailStatus()).isEqualTo(TechErrorReminderService.ST_NO_APPROVER_EMAIL));

        // Push de ulaşmadı (hepsi atlandı) ve posta BAŞARISIZ → talep bırakılır, ertesi gün yeniden
        for (AppUser u : List.of(po1, po2)) { u.setEmail(u.getUsername() + "@example.com"); users.save(u); }
        reminders.deleteAll();
        org.mockito.Mockito.doReturn(Map.of("queued", 0, "skipped", 3)).when(push)
                .enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        res = job.run();
        assertThat(res.approvalReports()).isZero();
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getLastSentOn()).isNull());

        // Anahtar kapalı: push yok, posta sürer (başarılı posta talebi harcar)
        reminders.deleteAll();
        org.mockito.Mockito.clearInvocations(push);
        settings.put(TechErrorReportSettings.KEY_PUSH_APPROVAL_REMINDER, "false");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn("SENT");
        assertThat(job.run().approvalReports()).isEqualTo(1);
        verify(push, never()).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertThat(noMail.getId()).isNotNull();
        job.setPushes(null);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: onay hatırlatmasında karar PO BAŞINA — postası ya da push'u ulaşan PO ulaşılmış; postası BAŞARISIZ ve push'u olmayan tek PO bile varsa talep bırakılır (ertesi gün yeniden), başka bir PO'ya giden push bunu örtmez; posta SKIPPED (kapalı / pasif) o PO için son karar")
    void approvalReminderDecidesPerPo() {
        TechErrorPushService pushes = new TechErrorPushService(appSettings, users);
        pushes.setPush(push);
        pushes.setVisibility(visibility);
        job.setPushes(pushes);
        AppUser noMail = user("po3", "PO Üç", null, "PO", dijital.getId());
        pendingSince(OCT5.minus(Duration.ofDays(3)));
        // po1 postayla ulaşıldı, po2'nin postası BAŞARISIZ; push yalnız e-postası olmayan po3'e kuyruklandı
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((String[]) inv.getArgument(0))[0].startsWith("po2") ? "FAILED: 550" : "SENT");
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Map.of("queued", 1, "queued_usernames", List.of("po3")));
        TechErrorReminderService.Result res = job.run();
        assertThat(res.approvalReports()).isEqualTo(1);                       // bazı PO'lara ulaştı
        assertThat(reminders.findAll()).singleElement()
                .satisfies(rm -> assertThat(rm.getLastSentOn()).as("po2'ye ulaşılmadı → talep bırakıldı").isNull());
        assertThat(rows(TechErrorReminderService.TRIGGER_APPROVAL)).hasSize(2)
                .allSatisfy(n -> assertThat(n.getMessage()).endsWith(" · push 1"));

        // Ertesi gün: po2'nin postası yine BAŞARISIZ ama push'u bu kez kuyrukta → her PO'ya ulaşıldı, talep harcanır
        at(OCT5.plus(Duration.ofDays(1)));
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Map.of("queued", 2, "queued_usernames", List.of("po2", "po3")));
        assertThat(job.run().approvalReports()).isEqualTo(1);
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getLastSentOn()).isEqualTo("2026-10-06"));

        // Postası SKIPPED (kanal kapalı / pasif adres) ve push'u olmayan PO: o PO için son karar — talep bırakılmaz
        reminders.deleteAll();
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((String[]) inv.getArgument(0))[0].startsWith("po2") ? "SKIPPED: e-posta kapalı" : "SENT");
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Map.of("queued", 0, "queued_usernames", List.of()));
        assertThat(job.run().approvalReports()).isEqualTo(1);
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getLastSentOn()).isEqualTo("2026-10-06"));

        // Push hiç yokken de aynı kural: bir PO'nun postası BAŞARISIZ → bırakılır (eskiden başka bir PO'ya ulaşmak yetiyordu)
        reminders.deleteAll();
        job.setPushes(null);
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((String[]) inv.getArgument(0))[0].startsWith("po2") ? "FAILED: 550" : "SENT");
        assertThat(job.run().approvalReports()).isEqualTo(1);
        assertThat(reminders.findAll()).singleElement().satisfies(rm -> assertThat(rm.getLastSentOn()).isNull());
        assertThat(noMail.getId()).isNotNull();
    }
}
