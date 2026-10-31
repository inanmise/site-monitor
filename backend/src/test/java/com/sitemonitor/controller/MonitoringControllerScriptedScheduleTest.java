package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.DnsCheckerService;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * k6r D2 (2026-10-31): sentetik izlemenin zamanlama penceresi / cron'u — oluşturma / güncelleme / geri yükleme doğrulaması
 * (dbquery hata kodları, sentetik metni), düz aralığın bugünkü yolu (kolonlar null, aralık denetlenmez), plan sıfırlama kancası,
 * önizleme ucu ve liste satırı alanları.
 */
@WebMvcTest(MonitoringController.class)
// Kontrol Geçmişi v2 zarfı (resolve/clamp/envelope) GERÇEK servisle test edilir — mock'lanırsa
// kontrat testi mock'u test etmiş olur. Tek bağımlılığı AlertEventRepository zaten @MockitoBean.
@org.springframework.context.annotation.Import(com.sitemonitor.service.CheckHistoryService.class)
class MonitoringControllerScriptedScheduleTest {

    // (dilim kurulumu MonitoringControllerTest ile AYNI — o dosyanın paylaşılan bölgesine dokunmamak için ayrı sınıf)


    @Autowired MockMvc mvc;

    /** Saklama süreleri katalogdan okunur; kontrat testinde mock yeter (varsayılan 0 → fallback kullanılır). */
    @MockitoBean com.sitemonitor.service.retention.RetentionService retentionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.sitemonitor.service.ActivityLogService activityLog;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean com.sitemonitor.repository.MonitorChangeLogRepository changeLogRepo;

    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean CertificateCheckRepository certCheckRepo;
    @MockitoBean UptimeCheckRepository uptimeCheckRepo;
    @MockitoBean com.sitemonitor.service.MonitoringGroupService monitoringGroupService;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean PortCheckRepository portCheckRepo;
    @MockitoBean PortCheckerService portChecker;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DnsRecordRepository dnsRecordRepo;
    @MockitoBean DnsCheckerService dnsChecker;
    @MockitoBean CertificateService certificateService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;

    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    /** O7: keyword özel başlıkları şifreli saklanıyor; denetleyici düz değeri bu servisten alır. */
    @MockitoBean com.sitemonitor.service.KeywordHeaderSecrets keywordHeaderSecrets;
    @MockitoBean KeywordResultRepository keywordResultRepo;
    @MockitoBean com.sitemonitor.service.KeywordCheckerService keywordChecker;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean PingCheckRepository pingCheckRepo;
    @MockitoBean com.sitemonitor.service.PingCheckerService pingChecker;
    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean HttpCheckRepository httpCheckRepo;
    @MockitoBean com.sitemonitor.service.HttpCheckerService httpChecker;
    @MockitoBean DomainMonitorRepository domainMonitorRepo;
    @MockitoBean DomainCheckRepository domainCheckRepo;
    @MockitoBean com.sitemonitor.service.DomainExpiryReminderService domainReminders;   // hatırlatmalar (2026-09-22, E)
    @MockitoBean com.sitemonitor.service.DomainRenewalPlanService domainRenewalPlans;   // yenileme planı (2026-09-22, H)
    @MockitoBean com.sitemonitor.service.MonitoringTagService monitoringTags;   // etiket kataloğu (2026-09-22)
    @MockitoBean com.sitemonitor.service.DomainCheckerService domainChecker;
    @MockitoBean com.sitemonitor.service.PublicSuffixService publicSuffixService;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.EscalationService escalationService;
    /** Bağımsız Port/DNS kalıcı silmesi (2026-10-07) — servis kendi testinde (PermanentDeletionServiceTest) H2 ile sınanır. */
    @MockitoBean com.sitemonitor.service.PermanentDeletionService permanentDeletion;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean com.sitemonitor.service.MonitoringOutageService monitoringOutageService;   // canlı teyit endpoint'i (2026-08-03)
    @MockitoBean com.sitemonitor.service.AlertKeyOwnershipService alertKeyOwnership;       // teyit listesi kapsamı (A4, 2026-09-28)
    @MockitoBean AlertEventRepository alertEventRepo;
    // Değişiklik geçmişi kapsamı ekip ÜYELERİNİ de sorar (TeamActorScope, 2026-09-25).
    @MockitoBean com.sitemonitor.repository.AppUserRepository appUserRepo;

    // 9. tür (sayfa-bütünlüğü) — controller alan-enjekte eder → @WebMvcTest slice'ında mock zorunlu.
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PageCheckRepository pageCheckRepo;
    @MockitoBean PageResourceIssueRepository pageResourceIssueRepo;
    @MockitoBean com.sitemonitor.service.PageCheckerService pageChecker;
    @MockitoBean com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @MockitoBean com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @MockitoBean com.sitemonitor.repository.PageSpeedResourceRepository pageSpeedResourceRepo;
    @MockitoBean com.sitemonitor.service.PageSpeedCheckerService pageSpeedChecker;
    // 10. tür (senaryo/k6) — aynı desen: controller alan-enjekte eder → mock zorunlu.
    @MockitoBean com.sitemonitor.repository.ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean com.sitemonitor.repository.ScriptedCheckRepository scriptedCheckRepo;
    // k6 script sürüm geçmişi + otomatik taslak (controller alan enjeksiyonuyla kullanıyor).
    @MockitoBean com.sitemonitor.repository.ScriptedScriptVersionRepository scriptedVersionRepo;
    @MockitoBean com.sitemonitor.repository.ScriptedDraftRepository scriptedDraftRepo;
    @MockitoBean com.sitemonitor.service.ScriptedCheckerService scriptedChecker;
    @MockitoBean com.sitemonitor.service.ProxySettings proxySettings;
    @MockitoBean com.sitemonitor.service.SsrfGuard ssrfGuard;
    @MockitoBean com.sitemonitor.service.SecretCipher secretCipher;
    /** Bildirim gruplari: dilim baglami icin gerekli; stub YOK -> "hic grup yok" (birinci yasa). */
    @MockitoBean com.sitemonitor.repository.NotificationGroupRepository notificationGroupRepo;
    // Veritabanı sorgu izlemesi (11. tür, 2026-10-31): controller alanları required=false; dilimde sahte verilir
    @MockitoBean com.sitemonitor.repository.DbQueryMonitorRepository dbQueryMonitorRepo;
    @MockitoBean com.sitemonitor.repository.DbQueryCheckRepository dbQueryCheckRepo;
    @MockitoBean com.sitemonitor.repository.DbQueryAlertRowsRepository dbQueryAlertRowsRepo;
    @MockitoBean com.sitemonitor.repository.DbConnectionProfileRepository dbConnectionRepo;
    @MockitoBean com.sitemonitor.service.dbquery.DbQueryCheckerService dbQueryChecker;
    @MockitoBean com.sitemonitor.service.dbquery.HolidayCalendarService holidayCalendar;
    /** "Sorguyu dene" / "Maliyeti denetle" saatlik küme sayacı (inceleme 2026-10-31, K3) — sahte: varsayılan "sınır yok". */
    @MockitoBean com.sitemonitor.service.dbquery.DbQueryTestQuota dbQueryTestQuota;

    @BeforeEach
    void stubTeamMap() {
        // Takım varlık denetimi (2026-09-28, O3): varsayılan 'takım var'; olmayan takım testleri kendi stub'ını verir.
        when(teamRepo.existsById(anyLong())).thenReturn(true);
        // Senaryo kaydetme yolu artık kaydetmeden önce script doğrulaması çağırıyor; mock varsayılanı
        // null döner ve NPE'ye yol açar. Zararsız (engellemeyen, uyarısız) bir sonuç stub'la.
        // ÜÇ argümanlı aşırı yükleme: süreç bütçesi (timeoutSeconds) de geçiliyor — ters bütçe
        // uyarısı ("istek timeout'u ≥ süreç bütçesi") ancak bu bilinirse üretilebiliyor.
        org.mockito.Mockito.lenient().when(scriptedChecker.validateScript(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics(null, java.util.List.of()));
        // Retention mock'u stub'sız 0 döner; geçmiş VE seri uçları "from"u saklama penceresine kırptığı
        // için 0 gün, istenen aralığı sıfıra indirip kova genişliğini bozardı. Gerçek varsayılan: 180.
        org.mockito.Mockito.lenient().when(retentionService.historyRetentionDays(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(180);
        // İzleme uçları artık domain→takım map'ini buradan alıyor; boş map yeterli (team_name=null).
        when(certificateService.domainTeamNameMap()).thenReturn(java.util.Map.of());
        // teamNameMap() artık CertificateService.teamNamesById()'e (cache'li) delege ediyor.
        when(certificateService.teamNamesById()).thenReturn(java.util.Map.of());
        // Grup get-or-create artık merkezi servise gidiyor; testte ham adı (kanonik) geri döndür.
        when(monitoringGroupService.getOrCreateFor(any(), any(), any(), any()))
                .thenAnswer(i -> i.getArgument(2));
    }

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    private static final String BASE = "\"name\":\"k6-zaman\",\"groupName\":\"Grup A\",\"tags\":\"t1\","
            + "\"script\":\"export default function () {}\",\"teamId\":5";

    private void stubCreateSave(java.util.List<com.sitemonitor.model.ScriptedMonitor> saved) {
        when(scriptedMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(scriptedMonitorRepo.save(any(com.sitemonitor.model.ScriptedMonitor.class))).thenAnswer(a -> {
            com.sitemonitor.model.ScriptedMonitor p = a.getArgument(0);
            p.setId(77L);
            saved.add(p);
            return p;
        });
    }

    private String create(String extra, int expectStatus) throws Exception {
        return mvc.perform(post("/api/monitoring/scripted").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + BASE + (extra.isEmpty() ? "" : "," + extra) + "}"))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private static com.sitemonitor.model.ScriptedMonitor live(long id) {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(id); m.setName("k6-" + id); m.setTeamId(5L); m.setActive(true); m.setIntervalSeconds(300);
        m.setScript("export default function () {}");
        return m;
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("zamanlama anahtarı YOKSA bugünkü yol: kolonlar null, 30 sn'lik aralık bile denetlenmez (eski istemci bayt-özdeş)")
    void create_withoutScheduleKeys_isTodaysPath() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":30", 200);
        var m = saved.get(0);
        assertThat(m.getIntervalSeconds()).isEqualTo(30);
        assertThat(m.getScheduleMode()).isNull();
        assertThat(m.getCronExpression()).isNull();
        assertThat(m.getWindowsJson()).isNull();
        assertThat(m.getSkipHolidays()).isNull();
        assertThat(m.getNextRunAt()).isNull();
        assertThat(body).contains("\"schedule_mode\":\"INTERVAL\"").contains("\"next_run_at\":null")
                .contains("\"effective_interval_seconds\":30");
    }

    @Test
    @DisplayName("INTERVAL + boş pencere listeleri = plan yok: kolonlar null kalır, aralık denetlenmez")
    void create_plainIntervalScheduleKeys_staysPlain() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        create("\"intervalSeconds\":30,\"schedule_mode\":\"INTERVAL\",\"windows\":[],\"blackouts\":[],\"skip_holidays\":false,"
                + "\"skip_dates\":[]", 200);
        var m = saved.get(0);
        assertThat(m.getIntervalSeconds()).isEqualTo(30);
        assertThat(m.getScheduleMode()).isNull();
        assertThat(m.getWindowsJson()).isNull();
        assertThat(m.getSkipDatesJson()).isNull();
    }

    @Test
    @DisplayName("CRON kaydı: kip + ifade yazılır, aralık ETKİN aralık (günlük → 86400); yanıtta zamanlama alanları")
    void create_cron_storesScheduleAndEffectiveInterval() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 8 * * *\",\"skip_holidays\":true", 200);
        var m = saved.get(0);
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getCronExpression()).isEqualTo("0 8 * * *");
        assertThat(m.getIntervalSeconds()).isEqualTo(86_400);
        assertThat(m.getSkipHolidays()).isTrue();
        assertThat(body).contains("\"schedule_mode\":\"CRON\"").contains("\"cron_expression\":\"0 8 * * *\"")
                .contains("\"skip_holidays\":true").contains("\"effective_interval_seconds\":86400");
    }

    @Test
    @DisplayName("pencere kaydı (aralık kipi): pencere JSON'u yazılır, aralık korunur")
    void create_windows_storesWindows() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":600,\"schedule_mode\":\"INTERVAL\","
                + "\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]", 200);
        var m = saved.get(0);
        assertThat(m.getScheduleMode()).isEqualTo("INTERVAL");
        assertThat(m.getIntervalSeconds()).isEqualTo(600);
        assertThat(m.getWindowsJson()).contains("09:00").contains("18:00");
        assertThat(body).contains("\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
    }

    @Test
    @DisplayName("geçersiz cron → 400 VALIDATION_FAILED alan cron_expression; HİÇBİR yan etki yok (kayıt / grup)")
    void create_invalidCron_400_noSideEffect() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"her sabah\"", 400);
        assertThat(body).contains("\"code\":\"VALIDATION_FAILED\"").contains("\"field\":\"cron_expression\"");
        assertThat(saved).isEmpty();
        verify(monitoringGroupService, never()).getOrCreateFor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("çok sık cron (30 sn) → 400 kod DBQUERY_CRON_TOO_FREQUENT; metin sentetiğe göre (\"sorgu\" demez)")
    void create_tooFrequentCron_coded() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"*/30 * * * * *\"", 400);
        assertThat(body).contains("\"code\":\"DBQUERY_CRON_TOO_FREQUENT\"").contains("\"field\":\"cron_expression\"")
                .contains("senaryo").doesNotContain("sorgu");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("pencereli zamanlamada aralık < 60 sn → 400 alan interval_seconds (zamanlama düzenleyicisinin adı)")
    void create_windowsWithShortInterval_400() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":30,\"windows\":[{\"days\":[],\"from\":\"09:00\",\"to\":\"18:00\"}]", 400);
        assertThat(body).contains("\"field\":\"interval_seconds\"");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("hiç koşmayan zamanlama → 400 DBQUERY_SCHEDULE_NEVER_RUNS; geçersiz pencere → alan windows (birden çok alan birlikte)")
    void create_neverRunsAndBadWindow() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.ScriptedMonitor>();
        stubCreateSave(saved);
        // izinli pencere = yasak pencere → hiçbir an izinli değil
        String never = create("\"windows\":[{\"days\":[],\"from\":\"09:00\",\"to\":\"10:00\"}],"
                + "\"blackouts\":[{\"days\":[],\"from\":\"08:00\",\"to\":\"11:00\"}]", 400);
        assertThat(never).contains("\"code\":\"DBQUERY_SCHEDULE_NEVER_RUNS\"");
        String bad = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"bozuk\","
                + "\"windows\":[{\"days\":[9],\"from\":\"25:00\",\"to\":\"18:00\"}]", 400);
        assertThat(bad).contains("\"cron_expression\"").contains("\"windows\"");
        assertThat(saved).isEmpty();
    }

    // ── Güncelleme ───────────────────────────────────────────────────────────────────────────────────────────────

    private String update(long id, String json) throws Exception {
        return mvc.perform(put("/api/monitoring/scripted/" + id).session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("düz aralıklı izlemeye pencere eklenince plan sıfırlanır (scriptedScheduleChanged)")
    void update_addWindows_replans() throws Exception {
        var m = live(41L);
        when(scriptedMonitorRepo.findById(41L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(41L)).thenReturn(Optional.empty());
        update(41L, "{\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]}");
        assertThat(m.getWindowsJson()).contains("09:00");
        assertThat(m.getScheduleMode()).isEqualTo("INTERVAL");
        verify(schedulerService).scriptedScheduleChanged(41L);
    }

    @Test
    @DisplayName("düz aralıkta yalnız aralık değişirse plan SIFIRLANMAZ (bugünkü davranış — vade kısaltmayı kendisi yakalar)")
    void update_plainIntervalChange_noReplan() throws Exception {
        var m = live(42L);
        when(scriptedMonitorRepo.findById(42L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(42L)).thenReturn(Optional.empty());
        update(42L, "{\"intervalSeconds\":120}");
        assertThat(m.getIntervalSeconds()).isEqualTo(120);
        assertThat(m.getScheduleMode()).isNull();
        verify(schedulerService, never()).scriptedScheduleChanged(anyLong());
    }

    @Test
    @DisplayName("zamanlamalı izlemede zamanlama dışı alan (ad) değişince plan SIFIRLANMAZ; zamanlama kaldırılınca sıfırlanır")
    void update_plannedMonitor_onlyScheduleChangeReplans() throws Exception {
        var m = live(43L);
        m.setScheduleMode("CRON");
        m.setCronExpression("0 8 * * *");
        m.setIntervalSeconds(86_400);
        when(scriptedMonitorRepo.findById(43L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(43L)).thenReturn(Optional.empty());
        update(43L, "{\"name\":\"yeni ad\"}");
        verify(schedulerService, never()).scriptedScheduleChanged(anyLong());

        update(43L, "{\"schedule_mode\":\"INTERVAL\",\"intervalSeconds\":300}");
        assertThat(m.getScheduleMode()).isNull();           // düz aralığa dönüldü → kolonlar null
        assertThat(m.getCronExpression()).isNull();
        assertThat(m.getIntervalSeconds()).isEqualTo(300);
        verify(schedulerService).scriptedScheduleChanged(43L);
    }

    @Test
    @DisplayName("güncellemede geçersiz zamanlama → 400, izleme değişmez ve kaydedilmez")
    void update_invalidSchedule_400() throws Exception {
        var m = live(44L);
        when(scriptedMonitorRepo.findById(44L)).thenReturn(Optional.of(m));
        mvc.perform(put("/api/monitoring/scripted/44").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"başka\",\"schedule_mode\":\"CRON\",\"cron_expression\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cron_expression"));
        assertThat(m.getName()).isEqualTo("k6-44");
        verify(scriptedMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("pencereli izlemede yalnız aralık gönderilse de denetlenir (30 sn → 400); CRON'da gönderilen aralık etkin aralığa döner")
    void update_intervalOnlyOnPlannedMonitor() throws Exception {
        var windowed = live(45L);
        windowed.setScheduleMode("INTERVAL");
        windowed.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        when(scriptedMonitorRepo.findById(45L)).thenReturn(Optional.of(windowed));
        mvc.perform(put("/api/monitoring/scripted/45").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"intervalSeconds\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("interval_seconds"));
        verify(scriptedMonitorRepo, never()).save(any());

        var cron = live(46L);
        cron.setScheduleMode("CRON");
        cron.setCronExpression("0 8 * * *");
        cron.setIntervalSeconds(86_400);
        when(scriptedMonitorRepo.findById(46L)).thenReturn(Optional.of(cron));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(46L)).thenReturn(Optional.empty());
        update(46L, "{\"intervalSeconds\":600}");
        assertThat(cron.getIntervalSeconds()).isEqualTo(86_400);   // CRON'da aralık = tetiklerin medyanı
        verify(schedulerService, never()).scriptedScheduleChanged(46L);   // zamanlama değişmedi
    }

    // ── Geri yükleme ─────────────────────────────────────────────────────────────────────────────────────────────

    private void stubRestoreRow(long id, String snapshot) {
        com.sitemonitor.model.MonitorChangeLog r = new com.sitemonitor.model.MonitorChangeLog();
        r.setTeamId(5L);
        r.setSnapshot(snapshot);
        r.setResourceKind("SCRIPTED");
        r.setResourceId(id);
        r.setResourceName("k6-" + id);
        r.setSeq(1);
        when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("SCRIPTED", id, 1)).thenReturn(Optional.of(r));
    }

    @Test
    @DisplayName("geri yükleme zamanlamayı döndürür: doğrulanır, etkin aralık yeniden hesaplanır, plan sıfırlanır")
    void restore_schedule_validatedAndReplanned() throws Exception {
        var m = live(4L);
        when(scriptedMonitorRepo.findById(4L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        stubRestoreRow(4L, "{\"scheduleMode\":\"CRON\",\"cronExpression\":\"0 8 * * *\",\"intervalSeconds\":300}");
        mvc.perform(post("/api/monitoring/changes/scripted/4/1/restore").session(session("ADMIN")))
                .andExpect(status().isOk());
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getIntervalSeconds()).isEqualTo(86_400);
        verify(schedulerService).scriptedScheduleChanged(4L);
    }

    @Test
    @DisplayName("bugünkü kurallardan geçmeyen zamanlamaya geri dönülemez (400, kayıt yok)")
    void restore_invalidSchedule_400() throws Exception {
        var m = live(5L);
        when(scriptedMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
        stubRestoreRow(5L, "{\"scheduleMode\":\"CRON\",\"cronExpression\":\"bozuk ifade\"}");
        mvc.perform(post("/api/monitoring/changes/scripted/5/1/restore").session(session("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cron_expression"));
        verify(scriptedMonitorRepo, never()).save(any());
        verify(schedulerService, never()).scriptedScheduleChanged(anyLong());
    }

    @Test
    @DisplayName("zamanlama alanı taşımayan eski kayda geri dönmek planı sıfırlamaz")
    void restore_legacySnapshot_noReplan() throws Exception {
        var m = live(6L);
        when(scriptedMonitorRepo.findById(6L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        stubRestoreRow(6L, "{\"name\":\"eski ad\"}");
        mvc.perform(post("/api/monitoring/changes/scripted/6/1/restore").session(session("ADMIN")))
                .andExpect(status().isOk());
        verify(schedulerService, never()).scriptedScheduleChanged(anyLong());
    }

    // ── Önizleme + liste ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("önizleme: geçerli cron → sonraki 10 koşum + etkin aralık; dbquery ile aynı yanıt biçimi")
    void preview_validCron() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/schedule-preview").session(session("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 8 * * 1-5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.next.length()").value(10))
                .andExpect(jsonPath("$.data.never_runs").value(false))
                .andExpect(jsonPath("$.data.errors.length()").value(0));
    }

    @Test
    @DisplayName("önizleme: geçersiz tanım → valid=false, hata listesi (alan + kod + metin), sonraki koşum yok")
    void preview_invalid() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/schedule-preview").session(session("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"schedule_mode\":\"INTERVAL\",\"interval_seconds\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0].field").value("interval_seconds"))
                .andExpect(jsonPath("$.data.errors[0].code").value("DBQUERY_INTERVAL_INVALID"))
                .andExpect(jsonPath("$.data.next.length()").value(0));
    }

    @Test
    @DisplayName("liste satırı zamanlama alanlarını taşır; plan yalnız zamanlamalı izlemede görünür")
    void list_rowCarriesScheduleFields() throws Exception {
        var plain = live(51L);
        plain.setNextRunAt("2030-01-01T00:00:00");   // düz aralıkta saklı değer yok sayılır
        var planned = live(52L);
        planned.setScheduleMode("CRON");
        planned.setCronExpression("0 8 * * *");
        planned.setIntervalSeconds(86_400);
        planned.setNextRunAt("2030-01-02T05:00:00");
        when(scriptedCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(scriptedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(plain, planned));
        mvc.perform(get("/api/monitoring/scripted").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitors[0].schedule_mode").value("INTERVAL"))
                .andExpect(jsonPath("$.data.monitors[0].next_run_at").doesNotExist())
                .andExpect(jsonPath("$.data.monitors[0].windows.length()").value(0))
                .andExpect(jsonPath("$.data.monitors[1].schedule_mode").value("CRON"))
                .andExpect(jsonPath("$.data.monitors[1].cron_expression").value("0 8 * * *"))
                .andExpect(jsonPath("$.data.monitors[1].next_run_at").value("2030-01-02T05:00:00"))
                .andExpect(jsonPath("$.data.monitors[1].effective_interval_seconds").value(86400));
    }
}
