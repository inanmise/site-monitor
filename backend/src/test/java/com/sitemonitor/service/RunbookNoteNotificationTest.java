package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.MonitorGuide;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.mail.RunbookNote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runbook notu — bildirim hunisi uçtan uca (2026-10-01). Ürün sahibi taahhüdü: "Not yalnız izlemeye not girilmişse
 * bildirimin sonuna eklenir; mevcut içerik değişmez."
 *
 * <p>GERÇEK {@link EmailNotificationService} (SMTP kapalı → gönderim yok ama gövde kurulur ve {@code notification_logs}
 * satırına TAM HTML olarak yazılır) + gerçek {@link EscalationService}; webhook gövdesi
 * {@link WebhookService#buildTeamsPayload} / {@link WebhookService#buildSlackPayload} ile kurulur. Böylece "bayt
 * özdeşliği" iddiası şablonun çıktısı üzerinde, özellik KAPALI (servis yok) ile AÇIK-ama-rehber-yok arasında sınanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunbookNoteNotificationTest {

    @Mock NotificationGroupService notificationGroups;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock WeeklyAvailabilityReportService weeklyAvailability;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock TeamRepository teamRepo;
    @Mock SmtpSettingsService smtpSettings;
    @Mock MaintenanceService maintenanceService;
    @Mock StormService stormService;
    @Mock UserPushService userPushService;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock PageCheckRepository pageCheckRepo;
    @Mock AppSettingsService appSettings;
    @Mock SmtpMailService smtpMailService;
    @Mock MonitorGuideRepository guideRepo;

    static final Long TEAM = 9L;
    static final String KW = "https://kw.example.com/odeme";
    /** Bildirimlerdeki "şimdi" damgaları (dd.MM.yyyy HH:mm[:ss]) — iki koşu arasında dakika dönebilir. */
    static final String TS = "\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}(:\\d{2})?";

    private EscalationService service;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "https://sitemonitor.example.com");
        SmtpSettings smtp = new SmtpSettings();
        smtp.setEnabled(false);   // gönderim YOK; gövde yine kurulur ve günlüğe yazılır
        when(smtpSettings.getOrDefaults()).thenReturn(smtp);
        EmailNotificationService email = new EmailNotificationService(smtpSettings, smtpMailService, notificationLogRepo, appSettings, tb);
        ReflectionTestUtils.setField(email, "appBaseUrl", "https://sitemonitor.example.com");

        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, email, weeklyAvailability,
                webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo, smtpSettings,
                maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo, dnsRecordRepo,
                pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);

        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Team team = new Team();
        team.setId(TEAM);
        team.setName("Takım 9");
        team.setEmail("takim9@example.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(TEAM)).thenReturn(List.of(
                contact("Kişi T", "kisi.t@example.com", "TEAMS", "https://teams.example.com/hook/abc"),
                contact("Kişi S", "kisi.s@example.com", "SLACK", "https://hooks.slack.com/services/T/B/xyz")));
    }

    private static EscalationContact contact(String name, String email, String type, String url) {
        EscalationContact c = new EscalationContact();
        c.setName(name);
        c.setEmail(email);
        c.setRole("TECH");
        c.setMinAlertLevel("WARNING");
        c.setActive(true);
        c.setTeamId(TEAM);
        c.setWebhookType(type);
        c.setWebhookUrl(url);
        return c;
    }

    private static Map<String, Object> keywordCtx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", TEAM);
        ctx.put("standalone", true);
        ctx.put("monitor_name", "Ödeme sayfası");
        ctx.put("keyword", "Ödeme");
        ctx.put("operator", "GTE");
        ctx.put("match_count", 1);
        ctx.put("occurrences", 0);
        ctx.put("first_failure_at", "2026-10-01T06:00:00");
        ctx.put("monitor_id", 42);
        return ctx;
    }

    /** Bir gönderimin tam çıktısı: günlüğe yazılan her satır (alıcı, konu, TAM gövde, durumlar) + webhook gövdeleri. */
    private record Sent(List<String> logRows, List<String> webhookPayloads, List<String> webhookMessages) { }

    private Sent fireKeywordAlarm() {
        clearInvocations(notificationLogRepo, webhookService, userPushService, guideRepo);
        service.processConfirmedOutage(KW, EscalationService.TYPE_KEYWORD, "CRITICAL", keywordCtx());
        return captured();
    }

    private Sent captured() {
        ArgumentCaptor<NotificationLog> logs = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(logs.capture());
        List<String> rows = new ArrayList<>();
        for (NotificationLog l : logs.getAllValues()) {
            rows.add(String.join(" | ", l.getRecipientName(), String.valueOf(l.getRecipientEmail()), l.getSubject(),
                    String.valueOf(l.getMessage()).replaceAll(TS, "<TS>"), l.getEmailStatus(), l.getWebhookStatus(),
                    l.getTrigger()));
        }
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class), title = ArgumentCaptor.forClass(String.class),
                msg = ArgumentCaptor.forClass(String.class), level = ArgumentCaptor.forClass(String.class);
        verify(webhookService, atLeast(0)).send(type.capture(), anyString(), title.capture(), msg.capture(), level.capture());
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < type.getAllValues().size(); i++) {
            String color = WebhookService.levelToColor(level.getAllValues().get(i));
            Object p = "SLACK".equalsIgnoreCase(type.getAllValues().get(i))
                    ? WebhookService.buildSlackPayload(title.getAllValues().get(i), msg.getAllValues().get(i), color)
                    : WebhookService.buildTeamsPayload(title.getAllValues().get(i), msg.getAllValues().get(i), color);
            payloads.add(p.toString());
        }
        return new Sent(rows, payloads, msg.getAllValues());
    }

    private void enableFeature() {
        ReflectionTestUtils.setField(service, "runbookNotes", new RunbookNoteService(guideRepo));
    }

    @Test
    @DisplayName("Rehbersiz hedef: özellik AÇIK iken e-posta (tam HTML) ve Teams/Slack gövdeleri özellik KAPALI ile BAYT BAYT aynı")
    void noGuide_outputByteIdenticalToFeatureOff() {
        Sent off = fireKeywordAlarm();                 // özellik yok (servis null) = bugünkü davranış
        assertThat(off.logRows()).isNotEmpty();
        assertThat(off.webhookPayloads()).hasSize(2);

        enableFeature();
        when(guideRepo.findByMonitorTypeAndTarget(anyString(), anyString())).thenReturn(Optional.empty());
        Sent on = fireKeywordAlarm();

        assertThat(on.logRows()).containsExactlyElementsOf(off.logRows());
        assertThat(on.webhookPayloads()).containsExactlyElementsOf(off.webhookPayloads());
        assertThat(String.join("\n", on.logRows())).doesNotContain(RunbookNote.TITLE);
        verify(guideRepo, times(1)).findByMonitorTypeAndTarget("KEYWORD", KW);   // gönderim başına TEK sorgu
    }

    @Test
    @DisplayName("Boş (yalnız boşluk/biçim) rehber de 'rehber yok' sayılır — çıktı yine aynı")
    void blankGuide_sameAsNoGuide() {
        Sent off = fireKeywordAlarm();
        enableFeature();
        MonitorGuide g = new MonitorGuide();
        g.setGuide("   \n\n ");
        when(guideRepo.findByMonitorTypeAndTarget("KEYWORD", KW)).thenReturn(Optional.of(g));
        Sent on = fireKeywordAlarm();
        assertThat(on.logRows()).containsExactlyElementsOf(off.logRows());
        assertThat(on.webhookPayloads()).containsExactlyElementsOf(off.webhookPayloads());
    }

    @Test
    @DisplayName("Rehberli hedef: e-postanın SONUNA kaçırılmış 'Ne yapılmalı' (≤1000), webhook'a ≤300 ek; tek sorgu; push/ana içerik aynı")
    void withGuide_appendedEscapedTruncated_restUnchanged() {
        Sent off = fireKeywordAlarm();
        enableFeature();
        MonitorGuide g = new MonitorGuide();
        g.setGuide("## İlk adım\n**Ödeme servisini** yeniden başlat <script>alert('x')</script> (eşik < 5 & hata > 3)\n\n"
                + "ayrıntı ".repeat(300));
        when(guideRepo.findByMonitorTypeAndTarget("KEYWORD", KW)).thenReturn(Optional.of(g));
        Sent on = fireKeywordAlarm();

        verify(guideRepo, times(1)).findByMonitorTypeAndTarget(anyString(), anyString());   // 1 e-posta + 2 webhook → 1 sorgu
        // E-posta satırı (takım + kontaklar, tek posta): not eklendi, HTML enjekte edilmedi
        String mailRow = on.logRows().stream().filter(r -> r.contains("takim9@example.com")).findFirst().orElseThrow();
        assertThat(mailRow).contains("Ne yapılmalı").contains("İzlemenin rehberinden")
                // etiketler düz metne çevrilirken atılır; kalan işaretler KAÇIRILIR — ham HTML asla enjekte edilmez
                .contains("Ödeme servisini yeniden başlat alert(&#39;x&#39;) (eşik &lt; 5 &amp; hata &gt; 3)")
                .doesNotContain("<script>").doesNotContain("alert('x')").contains("…");
        // Ek dışındaki her şey aynı: özellik-kapalı gövde + TEK bitişik ekleme (kart) = özellik-açık gövde
        String offMail = off.logRows().stream().filter(r -> r.contains("takim9@example.com")).findFirst().orElseThrow();
        String inserted = pureInsertion(offMail, mailRow);
        assertThat(inserted).contains("Ne yapılmalı").contains(RunbookNote.SUBTITLE);
        // Ek gövdenin SONUNDA: olay eylem bağlantılarından sonra, alt bilgiden ("Neden bu e-postayı aldınız") önce
        int note = mailRow.indexOf("Ne yapılmalı");
        assertThat(note).isGreaterThan(mailRow.indexOf("tab=alerthistory"));
        assertThat(note).isLessThan(mailRow.indexOf("Site Monitor otomatik bir izleme sistemidir"));

        // Webhook: mesaj aynen + sonda tek satırlık ek (≤300) + ipucu
        String offMsg = off.webhookMessages().get(0);
        for (String m : on.webhookMessages()) {
            assertThat(m).startsWith(offMsg + "\n\nNe yapılmalı: İlk adım Ödeme servisini yeniden başlat alert('x') (eşik < 5 & hata > 3) ayrıntı");
            assertThat(m).endsWith("\n\n" + RunbookNote.HINT).doesNotContain("<script>");
            String guide = m.substring((offMsg + "\n\nNe yapılmalı: ").length(), m.length() - ("\n\n" + RunbookNote.HINT).length());
            assertThat(guide.length()).isLessThanOrEqualTo(RunbookNote.WEBHOOK_MAX);
        }
        // Kişi push'u notu ALMAZ: push'a giden bağlam rehber anahtarı taşımaz
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> pushCtx = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(userPushService, atLeastOnce()).enqueueAlert(any(), anyString(), any(), pushCtx.capture(), any());
        assertThat(pushCtx.getAllValues()).allSatisfy(c -> assertThat(c).doesNotContainKey(RunbookNote.CTX_KEY));
    }

    @Test
    @DisplayName("Çözüm bildirimi (e-posta + webhook) rehber olsa da DEĞİŞMEZ — rehber hiç sorgulanmaz")
    void resolution_neverCarriesRunbook() {
        enableFeature();
        MonitorGuide g = new MonitorGuide();
        g.setGuide("Servisi yeniden başlat");
        when(guideRepo.findByMonitorTypeAndTarget(anyString(), anyString())).thenReturn(Optional.of(g));
        AlertEvent open = new AlertEvent();
        open.setId(77L);
        open.setDomain(KW);
        open.setAlertType(EscalationService.TYPE_KEYWORD);
        open.setAlertLevel("CRITICAL");
        open.setTeamId(TEAM);
        open.setResolved(false);
        open.setCreatedAt("2026-10-01T06:00:00");
        open.setContextJson("{\"team_id\":9,\"standalone\":true,\"keyword\":\"Ödeme\",\"monitor_id\":42}");
        when(alertEventRepo.findById(77L)).thenReturn(Optional.of(open));

        service.resolve(77L, "kisi.t");

        Sent s = captured();
        assertThat(String.join("\n", s.logRows())).contains("ÇÖZÜLDÜ").doesNotContain(RunbookNote.TITLE)
                .doesNotContain(RunbookNote.HINT);
        assertThat(s.webhookMessages()).allSatisfy(m -> assertThat(m).doesNotContain(RunbookNote.TITLE));
        verifyNoInteractions(guideRepo);
    }

    /**
     * {@code on}, {@code off}'a TEK bitişik parça eklenmiş hâli mi? Ortak önek + ortak sonek {@code off}'un TAMAMINI
     * kapsamalı (hiçbir mevcut bayt değişmemiş/silinmemiş); dönüş = eklenen parça.
     */
    static String pureInsertion(String off, String on) {
        int p = 0;
        while (p < off.length() && p < on.length() && off.charAt(p) == on.charAt(p)) p++;
        int s = 0;
        while (s < off.length() - p && s < on.length() - p
                && off.charAt(off.length() - 1 - s) == on.charAt(on.length() - 1 - s)) s++;
        assertThat(p + s).as("özellik-kapalı çıktının her baytı korunmalı").isEqualTo(off.length());
        return on.substring(p, on.length() - s);
    }
}
