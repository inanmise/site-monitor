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
 * Sayfa Hızı 2. sürüm madde 14 (2026-11-15): Sayfa Hızı izlemesinin zamanlama penceresi / cron'u — oluşturma / güncelleme
 * doğrulaması (dbquery hata kodları, Sayfa Hızı metni, alt sınır 5 dk), düz aralığın bugünkü yolu (kolonlar null, aralık
 * tabanı bugünkü gibi kırpılır), plan sıfırlama kancası, önizleme ucu ve yanıt satırı alanları. Dilim kurulumu sentetik
 * zamanlama testiyle aynı.
 */
@WebMvcTest(MonitoringController.class)
// Kontrol Geçmişi v2 zarfı (resolve/clamp/envelope) GERÇEK servisle test edilir — mock'lanırsa
// kontrat testi mock'u test etmiş olur. Tek bağımlılığı AlertEventRepository zaten @MockitoBean.
@org.springframework.context.annotation.Import(com.sitemonitor.service.CheckHistoryService.class)
class MonitoringControllerPageSpeedScheduleTest {

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

    private static final String BASE = "\"name\":\"hiz-zaman\",\"groupName\":\"Grup A\",\"tags\":\"t1\","
            + "\"url\":\"https://hiz.example.com/\",\"teamId\":5";

    private void stubCreateSave(java.util.List<com.sitemonitor.model.PageSpeedMonitor> saved) {
        when(pageSpeedMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageSpeedMonitorRepo.save(any(com.sitemonitor.model.PageSpeedMonitor.class))).thenAnswer(a -> {
            com.sitemonitor.model.PageSpeedMonitor p = a.getArgument(0);
            if (p.getId() == null) p.setId(88L);
            saved.add(p);
            return p;
        });
    }

    private String create(String extra, int expectStatus) throws Exception {
        return mvc.perform(post("/api/monitoring/pagespeed").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + BASE + (extra.isEmpty() ? "" : "," + extra) + "}"))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private static com.sitemonitor.model.PageSpeedMonitor live(long id) {
        com.sitemonitor.model.PageSpeedMonitor m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(id); m.setName("hiz-" + id); m.setUrl("https://hiz-" + id + ".example.com/"); m.setTeamId(5L); m.setActive(true);
        m.setIntervalSeconds(1800);
        return m;
    }

    private String update(long id, String json, int expectStatus) throws Exception {
        return mvc.perform(put("/api/monitoring/pagespeed/" + id).session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("zamanlama anahtarı YOKSA bugünkü yol: kolonlar null, aralık tabanı (5 dk) bugünkü gibi kırpılır")
    void create_withoutScheduleKeys_isTodaysPath() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":60", 200);
        var m = saved.get(0);
        assertThat(m.getIntervalSeconds()).isEqualTo(300);   // PageSpeedRules.clampInterval
        assertThat(m.getScheduleMode()).isNull();
        assertThat(m.getWindowsJson()).isNull();
        assertThat(m.getNextRunAt()).isNull();
        assertThat(body).contains("\"schedule_mode\":\"INTERVAL\"").contains("\"next_run_at\":null")
                .contains("\"windows\":[]");
    }

    @Test
    @DisplayName("pencere kaydı (aralık kipi): pencere JSON'u yazılır, aralık korunur; yanıtta zamanlama alanları")
    void create_windows_storesWindows() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":900,\"schedule_mode\":\"INTERVAL\","
                + "\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]", 200);
        var m = saved.get(0);
        assertThat(m.getScheduleMode()).isEqualTo("INTERVAL");
        assertThat(m.getIntervalSeconds()).isEqualTo(900);
        assertThat(m.getWindowsJson()).contains("09:00").contains("18:00");
        assertThat(body).contains("\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
    }

    @Test
    @DisplayName("CRON kaydı: kip + ifade yazılır, aralık ETKİN aralık (günlük → 86400)")
    void create_cron_storesEffectiveInterval() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 8 * * *\",\"skip_holidays\":true", 200);
        var m = saved.get(0);
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getIntervalSeconds()).isEqualTo(86_400);
        assertThat(m.getSkipHolidays()).isTrue();
        assertThat(body).contains("\"effective_interval_seconds\":86400");
    }

    @Test
    @DisplayName("dakikalık cron → 400 DBQUERY_CRON_TOO_FREQUENT, Sayfa Hızı metni (en az 5 dakika); kayıt YOK")
    void create_cronTooFrequent_400() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"schedule_mode\":\"CRON\",\"cron_expression\":\"*/2 * * * *\"", 400);
        assertThat(body).contains("DBQUERY_CRON_TOO_FREQUENT").contains("5 dakika").contains("cron_expression");
        assertThat(saved).isEmpty();
        verify(pageSpeedMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("pencereli aralık 5 dk'nın altında → 400 interval_seconds (Sayfa Hızı metni); kayıt YOK")
    void create_windowedIntervalBelowFloor_400() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"intervalSeconds\":120,\"schedule_mode\":\"INTERVAL\","
                + "\"windows\":[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]", 400);
        assertThat(body).contains("interval_seconds").contains("Sayfa Hızı");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("güncelleme: zamanlama değişti → plan sıfırlama kancası, yanıtta next_run_at null")
    void update_scheduleChange_replans() throws Exception {
        var m = live(41L);
        m.setNextRunAt("2026-11-16T06:00:00");
        when(pageSpeedMonitorRepo.findById(41L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        String body = update(41L, "{\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 9 * * 1-5\"}", 200);
        verify(schedulerService).pageSpeedScheduleChanged(41L);
        assertThat(body).contains("\"next_run_at\":null").contains("\"schedule_mode\":\"CRON\"");
    }

    @Test
    @DisplayName("güncelleme: zamanlama anahtarı yok, düz aralıklı izleme → kolonlar ve plan dokunulmaz, kanca çağrılmaz")
    void update_withoutScheduleKeys_untouched() throws Exception {
        var m = live(42L);
        when(pageSpeedMonitorRepo.findById(42L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        update(42L, "{\"intervalSeconds\":3600}", 200);
        assertThat(m.getScheduleMode()).isNull();
        assertThat(m.getIntervalSeconds()).isEqualTo(3600);
        verify(schedulerService, never()).pageSpeedScheduleChanged(anyLong());
    }

    @Test
    @DisplayName("güncelleme: geçersiz zamanlama 400 — alarm kapatma / kayıt / kanca YOK")
    void update_invalidSchedule_noSideEffects() throws Exception {
        var m = live(43L);
        when(pageSpeedMonitorRepo.findById(43L)).thenReturn(Optional.of(m));
        update(43L, "{\"active\":false,\"schedule_mode\":\"CRON\",\"cron_expression\":\"bozuk ifade\"}", 400);
        verify(pageSpeedMonitorRepo, never()).save(any());
        verify(schedulerService, never()).pageSpeedScheduleChanged(anyLong());
        verify(escalationService, never()).resolveOpenAlertsSilently(any(), any(), any(), any());
        assertThat(m.getActive()).isTrue();
    }

    @Test
    @DisplayName("önizleme: Sayfa Hızı alt sınırı 5 dk — 2 dk'lık pencereli aralık hata, 10 dk geçerli ve sonraki ölçümler döner")
    void preview_usesPageSpeedFloor() throws Exception {
        String bad = mvc.perform(post("/api/monitoring/pagespeed/schedule-preview").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"schedule_mode\":\"INTERVAL\",\"interval_seconds\":120}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bad).contains("\"valid\":false").contains("interval_seconds");
        String good = mvc.perform(post("/api/monitoring/pagespeed/schedule-preview").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"schedule_mode\":\"INTERVAL\",\"interval_seconds\":600}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(good).contains("\"valid\":true").contains("\"effective_interval_seconds\":600");
    }

    @Test
    @DisplayName("L1: saklı planın AYNISI yeniden doğrulanmaz — 'hiç çalışmaz' hâle gelmiş cron'lu izlemede ad düzenlemesi 200")
    void update_unchangedSchedule_notRevalidated() throws Exception {
        var m = live(44L);
        m.setScheduleMode("CRON");
        m.setCronExpression("0 9 29 2 *");   // 29 Şubat: 366 günlük ufukta tetik yok → yeni kayıtta DBQUERY_NEVER_RUNS
        m.setIntervalSeconds(86_400);
        when(pageSpeedMonitorRepo.findById(44L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        update(44L, "{\"name\":\"yeni ad\",\"intervalSeconds\":86400,\"schedule_mode\":\"CRON\","
                + "\"cron_expression\":\"0 9 29 2 *\",\"windows\":[],\"blackouts\":[],\"skip_holidays\":false,\"skip_dates\":[]}", 200);
        assertThat(m.getName()).isEqualTo("yeni ad");   // kayıt geçti (400 yok)
        // Değişen (yine hiç çalışmayan) plan doğrulanır → 400
        String bad = update(44L, "{\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 9 30 2 *\"}", 400);
        assertThat(bad).contains("DBQUERY_SCHEDULE_NEVER_RUNS");
    }

    @Test
    @DisplayName("İnceleme 2026-11-17: aynı aylık CRON planıyla kayıt (ad değişimi) → saklı ETKİN aralık korunur (30 ↔ 31 gün "
            + "oynamaz), plan sıfırlanmaz, kanca çağrılmaz")
    void update_sameCronPlan_keepsEffectiveIntervalAndPlan() throws Exception {
        var m = live(45L);
        m.setScheduleMode("CRON");
        m.setCronExpression("0 9 1 * *");
        m.setIntervalSeconds(2_592_000);   // önceki kayıt anının ortancası (30 gün); bugün yeniden hesaplanırsa 31 gün çıkar
        m.setNextRunAt("2026-12-01T06:00:00");
        when(pageSpeedMonitorRepo.findById(45L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        String body = update(45L, "{\"name\":\"yeni ad\",\"intervalSeconds\":2592000,\"schedule_mode\":\"CRON\","
                + "\"cron_expression\":\"0 9 1 * *\",\"windows\":[],\"blackouts\":[],\"skip_holidays\":false,\"skip_dates\":[]}", 200);
        assertThat(m.getName()).isEqualTo("yeni ad");
        assertThat(m.getIntervalSeconds()).isEqualTo(2_592_000);
        verify(schedulerService, never()).pageSpeedScheduleChanged(anyLong());
        assertThat(body).doesNotContain("\"next_run_at\":null");
        // Plan GERÇEKTEN değişince (ifade) sıfırlanır
        update(45L, "{\"schedule_mode\":\"CRON\",\"cron_expression\":\"0 9 2 * *\"}", 200);
        verify(schedulerService).pageSpeedScheduleChanged(45L);
    }

    private void stubRestoreRow(long id, String snapshot) {
        com.sitemonitor.model.MonitorChangeLog r = new com.sitemonitor.model.MonitorChangeLog();
        r.setTeamId(5L);
        r.setSnapshot(snapshot);
        r.setResourceKind("PAGESPEED");
        r.setResourceId(id);
        r.setResourceName("hiz-" + id);
        r.setSeq(1);
        when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PAGESPEED", id, 1)).thenReturn(Optional.of(r));
    }

    @Test
    @DisplayName("geri yükleme zamanlamayı döndürür: doğrulanır, etkin aralık yeniden hesaplanır, plan sıfırlanır")
    void restore_schedule_validatedAndReplanned() throws Exception {
        var m = live(45L);
        when(pageSpeedMonitorRepo.findById(45L)).thenReturn(Optional.of(m));
        when(pageSpeedMonitorRepo.save(any(com.sitemonitor.model.PageSpeedMonitor.class))).thenAnswer(i -> i.getArgument(0));
        stubRestoreRow(45L, "{\"scheduleMode\":\"CRON\",\"cronExpression\":\"0 8 * * *\",\"intervalSeconds\":1800}");
        mvc.perform(post("/api/monitoring/changes/pagespeed/45/1/restore").session(session("ADMIN")))
                .andExpect(status().isOk());
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getIntervalSeconds()).isEqualTo(86_400);
        verify(schedulerService).pageSpeedScheduleChanged(45L);
    }

    @Test
    @DisplayName("bugünkü kurallardan geçmeyen zamanlamaya geri dönülemez (400, kayıt ve plan sıfırlama yok)")
    void restore_invalidSchedule_400() throws Exception {
        var m = live(46L);
        when(pageSpeedMonitorRepo.findById(46L)).thenReturn(Optional.of(m));
        stubRestoreRow(46L, "{\"scheduleMode\":\"CRON\",\"cronExpression\":\"bozuk ifade\"}");
        mvc.perform(post("/api/monitoring/changes/pagespeed/46/1/restore").session(session("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cron_expression"));
        verify(pageSpeedMonitorRepo, never()).save(any());
        verify(schedulerService, never()).pageSpeedScheduleChanged(anyLong());
    }
}
