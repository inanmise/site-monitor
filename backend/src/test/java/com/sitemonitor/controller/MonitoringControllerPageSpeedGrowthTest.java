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
 * Sayfa Hızı 2c (2026-11-15): genişletilmiş ölçüm anahtarı (YENİ izleme varsayılan AÇIK, gövde false derse kapalı, mevcut
 * izleme dokunulmadan kalır) ve büyüme alarmı alanlarının doğrulaması (1–1000 tam sayı, boş = kapalı, kayıtlı değer yeniden
 * denetlenmez, yan etkiden ÖNCE 400 + alan). Dilim kurulumu zamanlama testiyle aynı.
 */
@WebMvcTest(MonitoringController.class)
// Kontrol Geçmişi v2 zarfı (resolve/clamp/envelope) GERÇEK servisle test edilir — mock'lanırsa
// kontrat testi mock'u test etmiş olur. Tek bağımlılığı AlertEventRepository zaten @MockitoBean.
@org.springframework.context.annotation.Import(com.sitemonitor.service.CheckHistoryService.class)
class MonitoringControllerPageSpeedGrowthTest {

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

    private static final String BASE = "\"name\":\"hiz-buyume\",\"groupName\":\"Grup A\",\"tags\":\"t1\","
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
    @DisplayName("YENİ izleme genişletilmiş kipte doğar; gövde açıkça false derse klasik; büyüme alanları yazılır")
    void create_defaultsExtendedOn() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        String body = create("\"growthBytesPct\":25,\"growthRequestsPct\":40,\"growthBytesBand\":true", 200);
        var m = saved.get(0);
        assertThat(m.getExtendedMeasurement()).isTrue();
        assertThat(m.getGrowthBytesPct()).isEqualTo(25);
        assertThat(m.getGrowthRequestsPct()).isEqualTo(40);
        assertThat(m.getGrowthBytesBand()).isTrue();
        assertThat(body).contains("\"extended_measurement\":true").contains("\"growth_bytes_pct\":25")
                .contains("\"growth_requests_pct\":40").contains("\"growth_bytes_band\":true");

        saved.clear();
        create("\"extendedMeasurement\":false", 200);
        assertThat(saved.get(0).getExtendedMeasurement()).isFalse();
        assertThat(saved.get(0).getGrowthBytesPct()).isNull();
        assertThat(saved.get(0).getGrowthBytesBand()).isNull();
    }

    @Test
    @DisplayName("büyüme yüzdesi aralık dışı / ondalık / metin → 400 VALIDATION_FAILED + alan; kayıt YOK")
    void create_invalidGrowth_400() throws Exception {
        var saved = new java.util.ArrayList<com.sitemonitor.model.PageSpeedMonitor>();
        stubCreateSave(saved);
        for (String bad : new String[]{"\"growthBytesPct\":0", "\"growthBytesPct\":1001", "\"growthRequestsPct\":12.5",
                "\"growthRequestsPct\":\"on\""}) {
            String body = create(bad, 400);
            String field = bad.substring(1, bad.indexOf('"', 1));
            assertThat(body).contains("VALIDATION_FAILED").contains("\"field\":\"" + field + "\"");
        }
        String band = create("\"growthBytesBand\":\"evet\"", 400);
        assertThat(band).contains("\"field\":\"growthBytesBand\"");
        assertThat(saved).isEmpty();
        verify(pageSpeedMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("mevcut izleme: anahtar gelmezse kip ve büyüme alanları DOKUNULMAZ; null gönderilirse kapanır")
    void update_untouchedUnlessSent() throws Exception {
        var m = live(51L);
        when(pageSpeedMonitorRepo.findById(51L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        update(51L, "{\"name\":\"yeni\"}", 200);
        assertThat(m.getExtendedMeasurement()).isNull();   // mevcut izleme klasik kipte kalır
        assertThat(m.getGrowthBytesPct()).isNull();

        update(51L, "{\"extendedMeasurement\":true,\"growthBytesPct\":30}", 200);
        assertThat(m.getExtendedMeasurement()).isTrue();
        assertThat(m.getGrowthBytesPct()).isEqualTo(30);

        update(51L, "{\"growthBytesPct\":null,\"growthBytesBand\":false}", 200);
        assertThat(m.getGrowthBytesPct()).isNull();
        assertThat(m.getGrowthBytesBand()).isNull();   // kapalı = null (eski sürüm alanı tanımaz, durum aynı)
    }

    @Test
    @DisplayName("kayıtlı (eski kurala göre geçersiz) yüzde değişmeden gelirse yeniden denetlenmez; değişen denetlenir")
    void update_unchangedNotRevalidated() throws Exception {
        var m = live(52L);
        m.setGrowthBytesPct(5000);   // elle yazılmış eski değer
        when(pageSpeedMonitorRepo.findById(52L)).thenReturn(Optional.of(m));
        stubCreateSave(new java.util.ArrayList<>());
        update(52L, "{\"name\":\"yeni\",\"growthBytesPct\":5000}", 200);
        String bad = update(52L, "{\"growthBytesPct\":4000}", 400);
        assertThat(bad).contains("\"field\":\"growthBytesPct\"");
    }

    @Test
    @DisplayName("kip değişimi işaretleri: yalnız sayfası alınmış ölçümler arasında, eskiden yeniye; en çok 100")
    void modeChanges() {
        java.util.List<Object[]> rows = new java.util.ArrayList<>();   // en yeni önce: [checkedAt, ..., extended(1), ok(2)]
        rows.add(new Object[]{"2026-11-15T12:00:00", true, true});
        rows.add(new Object[]{"2026-11-15T11:00:00", false, false});   // sayfa alınamadı → sayılmaz
        rows.add(new Object[]{"2026-11-15T10:00:00", true, true});
        rows.add(new Object[]{"2026-11-15T09:00:00", null, true});
        rows.add(new Object[]{"2026-11-15T08:00:00", null, true});
        var out = MonitoringController.pageSpeedModeChanges(rows, 1, 2);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("at", "2026-11-15T10:00:00").containsEntry("event", "MODE")
                .containsEntry("extended", true);
        assertThat(MonitoringController.pageSpeedModeChanges(null, 1, 2)).isEmpty();

        java.util.List<Object[]> flip = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) flip.add(new Object[]{"t" + i, i % 2 == 0, true});
        assertThat(MonitoringController.pageSpeedModeChanges(flip, 1, 2)).hasSize(100);
    }
}
