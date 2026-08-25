package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StormService birim testleri — eşik matematiği (COUNT/PERCENT + round edge), evaluate karar yolları
 * (disabled / non-down / eşik-altı / attach / promote), atomik-idempotent terfi ve "N eşzamanlı arıza →
 * tam olarak BİR toplu alarm" garantisi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormServiceTest {

    @Mock AlertStormRepository stormRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock com.sitemonitor.repository.PageMonitorRepository pageRepo;
    @Mock com.sitemonitor.repository.ScriptedMonitorRepository scriptedRepo;
    @Mock com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedRepo;
    /** Bilerek STUB'LANMAZ: null donus = "hic grup yok" -> eski Team.email yolu isler (birinci yasa). */
    @Mock NotificationGroupService notificationGroups;

    private StormService storm;

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, notificationGroups);
    }

    private AlertEvent down(long id, String type, Long teamId) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("host" + id + ".example.com");
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setTeamId(teamId);
        e.setResolved(false);
        return e;
    }

    private void enabledAccountWide() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(false);
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(3);
        when(appSettings.getInt(eq(StormService.KEY_WINDOW), anyInt())).thenReturn(5);
    }

    // ── Eşik matematiği ───────────────────────────────────────────────────────

    @Test
    @DisplayName("computeThreshold COUNT → max(2, value)")
    void threshold_count() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(5);
        assertThat(storm.computeThreshold()).isEqualTo(5);

        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(1);   // 1'lik storm → taban 2
        assertThat(storm.computeThreshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeThreshold PERCENT round edge — %10 × 5 monitör → ceil(0.5)=1 → max(2,1)=2")
    void threshold_percent_rounding_edge() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(10);
        stubTotalMonitors(5, 0, 0, 0, 0, 0, 0);   // toplam 5 aktif monitör
        assertThat(storm.computeThreshold()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeThreshold PERCENT normal — %50 × 20 monitör → 10")
    void threshold_percent_normal() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(50);
        stubTotalMonitors(10, 4, 3, 3, 0, 0, 0);   // toplam 20
        assertThat(storm.computeThreshold()).isEqualTo(10);
    }

    private void stubTotalMonitors(long inv, long http, long keyword, long ping, long domain, long port, long dns) {
        when(inventoryRepo.countByActiveTrue()).thenReturn(inv);
        when(httpRepo.countByActiveTrue()).thenReturn(http);
        when(keywordRepo.countByActiveTrue()).thenReturn(keyword);
        when(pingRepo.countByActiveTrue()).thenReturn(ping);
        when(domainRepo.countByActiveTrue()).thenReturn(domain);
        when(portRepo.countByStandaloneTrueAndActiveTrue()).thenReturn(port);
        when(dnsRepo.countByStandaloneTrueAndActiveTrue()).thenReturn(dns);
    }

    // ── evaluate karar yolları ────────────────────────────────────────────────

    @Test
    @DisplayName("Storm KAPALI → SEND_INDIVIDUAL (sıfır etkileşim, bugünkü davranış)")
    void evaluate_disabled_sendsIndividual() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(false);
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verifyNoInteractions(stormRepo);
    }

    @Test
    @DisplayName("DOWN olmayan tip (HTTP_SSL) → SEND_INDIVIDUAL")
    void evaluate_nonDownType_sendsIndividual() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_SSL, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verifyNoInteractions(stormRepo);
    }

    @Test
    @DisplayName("Eşik altı → SEND_INDIVIDUAL (sel değil)")
    void evaluate_belowThreshold_sendsIndividual() {
        enabledAccountWide();
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT")).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L)));   // 2 < eşik 3
        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Aktif storm varsa → attach (SUPPRESSED, stormId damgalanır, e-posta yok)")
    void evaluate_activeStorm_attaches() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(false);
        AlertStorm active = storm(100L);
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT")).thenReturn(Optional.of(active));

        AlertEvent e = down(9, EscalationService.TYPE_PORT_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(100L);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
        verify(stormRepo).save(active);   // memberCount bump
    }

    @Test
    @DisplayName("Terfi (kazanan) → SUPPRESSED + TEK toplu alarm + stormId damgalanır")
    void evaluate_promote_winner_sendsOneAggregatedAlert() {
        enabledAccountWide();
        AlertStorm created = storm(200L);
        // 1. çağrı (aktif kontrol) empty, 2. çağrı (insert sonrası) storm
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));   // 3 >= eşik 3
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);   // biz oluşturduk (kazanan)

        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(200L);
        verify(emailService, times(1)).buildStormAlertHtml(eq(3), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Terfi çakışması (kaybeden, rows=0) → SUPPRESSED ama toplu alarm GÖNDERMEZ (idempotent)")
    void evaluate_promote_loser_noDuplicateAlert() {
        enabledAccountWide();
        AlertStorm existing = storm(201L);
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);   // başka worker kazandı

        AlertEvent e = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        verify(emailService, never()).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("N eşzamanlı arıza → tam olarak BİR toplu alarm (kalanı attach)")
    void evaluate_nFailures_exactlyOneAggregatedAlert() {
        enabledAccountWide();
        AlertStorm created = storm(300L);
        // 1. çağrının aktif-kontrolü empty; sonrası hep aktif storm (post-insert + sonraki çağrıların aktif-kontrolü)
        when(stormRepo.findByScopeKeyAndResolvedFalse("ACCOUNT"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenReturn(List.of(down(1, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(2, EscalationService.TYPE_HTTP_DOWN, 7L),
                                    down(3, EscalationService.TYPE_HTTP_DOWN, 7L)));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        for (int i = 1; i <= 5; i++) {
            AlertEvent e = down(i, EscalationService.TYPE_HTTP_DOWN, 7L);
            assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        }
        // 5 arıza → yalnız 1 toplu alarm (ilk terfi); kalan 4 attach oldu
        verify(emailService, times(1)).buildStormAlertHtml(anyInt(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Per-group storm counts the triggering monitor — no off-by-one (M1)")
    void evaluate_perGroup_countsTriggeringMonitor() {
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(true);   // per-group ON
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(2);    // eşik 2
        when(appSettings.getInt(eq(StormService.KEY_WINDOW), anyInt())).thenReturn(5);
        // Grup çözümü: HTTP monitör "G" grubunda.
        com.sitemonitor.model.HttpMonitor mon = new com.sitemonitor.model.HttpMonitor();
        mon.setGroupName("G");
        when(httpRepo.findFirstByUrlOrderByIdAsc(anyString())).thenReturn(Optional.of(mon));

        AlertStorm created = storm(400L);
        created.setScopeKey("G");
        when(stormRepo.findByScopeKeyAndResolvedFalse("G"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(created));
        // DB, tetikleyenin group_name'i henüz commit edilmediğinden onu HARİÇ döner (yalnız 1 diğer üye).
        when(alertEventRepo.findOpenDownSinceInGroup(anyCollection(), anyString(), eq("G")))
                .thenReturn(List.of(down(2, EscalationService.TYPE_HTTP_DOWN, 7L)));
        when(jdbcTemplate.update(startsWith("INSERT INTO alert_storms"), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        AlertEvent current = down(1, EscalationService.TYPE_HTTP_DOWN, 7L);
        // Sorgu 1 üye döner, eşik 2. Off-by-one hatasında (tetikleyen sayılmaz) SEND_INDIVIDUAL olurdu.
        assertThat(storm.evaluate(current, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(current.getStormId()).isEqualTo(400L);
        assertThat(current.getGroupName()).isEqualTo("G");
    }

    private AlertStorm storm(long id) {
        AlertStorm s = new AlertStorm();
        s.setId(id);
        s.setScopeKey("ACCOUNT");
        s.setScopeType("ACCOUNT");
        s.setResolved(false);
        s.setCreatedAt("2026-07-10T09:00:00");
        return s;
    }

    // ── Payda bütünlüğü: yüzde eşiği TÜM izleme türlerini saymalı ─────────────
    //
    // 2026-08-23'te bulunan hata: SCRIPTED_FAIL storm ÜYESİ olabiliyordu (DOWN_ALERT_TYPES
    // içinde) ama sentetik izlemeler paydada yoktu. Yüzde eşiği olduğundan küçük çıkıyor,
    // fırtına erken ilan ediliyor ve bireysel alarmlar erken bastırılıyordu — kimse hata
    // görmüyor, yalnız alarm davranışı sessizce değişiyordu.
    //
    // Eski testler bunu göremezdi: page/scripted depoları ALAN enjeksiyonlu, testte null
    // kalıyor ve 0 katkı veriyordu. Bu yüzden aşağıda ikisi de reflection ile bağlanıyor.

    /** Storm üyesi olabilen her alarm türü ↔ paydayı besleyen depo alanı. */
    private static final java.util.Map<String, String> REPO_FIELD_BY_ALERT_TYPE = java.util.Map.of(
            EscalationService.TYPE_ACCESSIBILITY, "inventoryRepo",
            EscalationService.TYPE_HTTP_DOWN, "httpRepo",
            EscalationService.TYPE_PORT_DOWN, "portRepo",
            EscalationService.TYPE_PING_DOWN, "pingRepo",
            EscalationService.TYPE_DNS_FAILURE, "dnsRepo",
            EscalationService.TYPE_KEYWORD, "keywordRepo",
            EscalationService.TYPE_PAGE_DOWN, "pageRepo",
            EscalationService.TYPE_SCRIPTED_FAIL, "scriptedRepo",
            EscalationService.TYPE_PAGESPEED_DOWN, "pageSpeedRepo");

    private void injectFieldRepos() {
        org.springframework.test.util.ReflectionTestUtils.setField(storm, "pageRepo", pageRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(storm, "scriptedRepo", scriptedRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(storm, "pageSpeedRepo", pageSpeedRepo);
    }

    @Test
    @DisplayName("KAPI: storm üyesi olabilen HER tür paydada karşılığını bulmalı")
    void everyStormMemberTypeHasADenominatorSource() {
        // Yeni bir DOWN türü eklenip paydaya bağlanmazsa bu satır ADIYLA söyleyerek kırılır.
        assertThat(REPO_FIELD_BY_ALERT_TYPE.keySet())
                .as("DOWN_ALERT_TYPES'a yeni tür eklendi ama StormService paydasına bağlanmadı "
                        + "(totalActiveMonitors) — yüzde eşiği yanlış hesaplanır")
                .containsExactlyInAnyOrderElementsOf(EscalationService.DOWN_ALERT_TYPES);
    }

    @Test
    @DisplayName("Payda sentetik, sayfa ve sayfa hızı izlemelerini de sayar")
    void denominatorIncludesPageAndScripted() {
        injectFieldRepos();
        stubTotalMonitors(2, 2, 2, 2, 2, 2, 2);          // 7 tür × 2 = 14
        when(pageRepo.countByActiveTrue()).thenReturn(3L);
        when(scriptedRepo.countByActiveTrue()).thenReturn(5L);
        when(pageSpeedRepo.countByActiveTrue()).thenReturn(4L);

        assertThat(storm.totalActiveMonitors()).isEqualTo(26L);   // 14 + 3 + 5 + 4
    }

    @Test
    @DisplayName("Sentetik payda dışında kalınca eşik DÜŞÜYORDU — regresyon kilidi")
    void percentThresholdCountsScriptedMonitors() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(10);
        injectFieldRepos();
        stubTotalMonitors(20, 0, 0, 0, 0, 0, 0);
        when(pageRepo.countByActiveTrue()).thenReturn(0L);
        when(scriptedRepo.countByActiveTrue()).thenReturn(80L);   // ağırlık sentetikte
        when(pageSpeedRepo.countByActiveTrue()).thenReturn(0L);

        // Doğru payda 100 → %10 = 10. Sentetik sayılmasaydı payda 20 → eşik 2 çıkardı:
        // 100 monitörlük bir kurulumda 2 arıza "fırtına" sayılırdı.
        assertThat(storm.computeThreshold()).isEqualTo(10);
    }

    @Test
    @DisplayName("Payda sorgusu patlarsa 0'a düşer ama eşik TABANIN altına inmez")
    void denominatorFailureFallsBackSafely() {
        when(appSettings.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("PERCENT");
        when(appSettings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(50);
        when(inventoryRepo.countByActiveTrue()).thenThrow(new RuntimeException("db yok"));

        assertThat(storm.totalActiveMonitors()).isZero();
        assertThat(storm.computeThreshold()).isEqualTo(2);   // taban: 1 arızada fırtına ilan edilmez
    }

    @Test
    @DisplayName("Payda 60 sn önbelleklenir — her kesintide tüm tablolar sayılmaz")
    void denominatorIsCached() {
        injectFieldRepos();
        stubTotalMonitors(1, 1, 1, 1, 1, 1, 1);
        when(pageRepo.countByActiveTrue()).thenReturn(0L);
        when(scriptedRepo.countByActiveTrue()).thenReturn(0L);

        assertThat(storm.totalActiveMonitors()).isEqualTo(7L);
        assertThat(storm.totalActiveMonitors()).isEqualTo(7L);

        verify(inventoryRepo, org.mockito.Mockito.times(1)).countByActiveTrue();
        verify(scriptedRepo, org.mockito.Mockito.times(1)).countByActiveTrue();
    }


    // ── Bildirim grubu yönlendirmesi ─────────────────────────────────────────

    /**
     * {@code addTeam} özel; refleksiyonla çağrılıyor çünkü tek kamusal giriş noktası
     * ({@code sendStormAlert}) e-posta/webhook gönderimini de tetikliyor ve bu testin
     * sorusu yalnızca "alıcı listesine ne konuyor".
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private java.util.List<String> collectFor(Long teamId) {
        java.util.List<String> emails = new java.util.ArrayList<>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                storm, "addTeam", teamId, new java.util.HashMap(),
                new java.util.HashSet<String>(), emails, new java.util.LinkedHashSet<String>());
        return emails;
    }

    @Test
    @DisplayName("STORM: takımın varsayılan grubu takım mailinin YERİNE geçer")
    void storm_defaultGroupReplacesTeamEmail() {
        com.sitemonitor.model.Team t = new com.sitemonitor.model.Team();
        t.setId(7L); t.setName("Dijital SY"); t.setEmail("takim@bank.com");
        when(teamRepo.findById(7L)).thenReturn(java.util.Optional.of(t));
        // Damga YOK: storm birçok monitörü tek maile topluyor, birinin grubunu seçmek keyfî olurdu.
        when(notificationGroups.overrideFor(7L)).thenReturn(new NotificationGroupService.Override(
                java.util.List.of("nobet@bank.com"), NotificationGroupService.Source.TEAM_DEFAULT_GROUP,
                20L, "Takım Nöbet"));

        assertThat(collectFor(7L)).containsExactly("nobet@bank.com");
    }

    @Test
    @DisplayName("STORM: grup yoksa davranış BUGÜNKÜNÜN AYNISI — takım maili")
    void storm_noGroup_keepsTeamEmail() {
        com.sitemonitor.model.Team t = new com.sitemonitor.model.Team();
        t.setId(7L); t.setName("Dijital SY"); t.setEmail("takim@bank.com");
        when(teamRepo.findById(7L)).thenReturn(java.util.Optional.of(t));
        when(notificationGroups.overrideFor(7L)).thenReturn(NotificationGroupService.Override.NONE);

        assertThat(collectFor(7L)).containsExactly("takim@bank.com");
    }
}
