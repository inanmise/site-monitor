package com.sitemonitor.service;

import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Bilinmiyor ≠ sağlıklı" (BUG_REGRESYON_2026-09-29, O-2 ve O-5): yürütülemeyen / veri getirmeyen kontrol hiçbir türde
 * "sağlıklı" kalem üretmemeli — ne kurtarma sayacını artırır ne sıfırlar. Eskiden:
 * <ul>
 *   <li>O-2: yürütülemeyen (SKIPPED) elle sentetik koşum değerlendiriliyor, SCRIPTED_SLOW kalemi "sağlıklı" sayılıp açık
 *       yavaşlık alarmını kanıtsız kapatabiliyordu; düşen koşumda da SLOW "sağlıklı" sayılıyordu.</li>
 *   <li>O-5: RDAP/WHOIS/DNSBL sorgusu düşünce (veri yok) alan adı EXPIRY/STATUS/TRANSFER_LOCK/BLACKLIST kalemleri
 *       "sağlıklı" sayılıyor, KRİTİK alarm "ÇÖZÜLDÜ" diye kapanıp sonraki başarılı sorguda yeniden açılıyordu.</li>
 * </ul>
 * Servis kurucusu değişse de derlensin diye parametre TİPİNE göre sahte nesnelerle kurulur.
 */
class SweepNoDataIsNotHealthyTest {

    private MonitoringOutageService outage;
    private ScriptedCheckerService scripted;
    private SchedulerService scheduler;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        outage = mock(MonitoringOutageService.class);
        scripted = mock(ScriptedCheckerService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckerService", scripted);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Verilen türün elle değerlendirmesine giden kalemler (son çağrı). */
    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> itemsFor(String type) {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> c = ArgumentCaptor.forClass(List.class);
        verify(outage, atLeastOnce()).handleSweepResults(eq(type), c.capture(), anyBoolean());
        return c.getValue();
    }

    private static ScriptedMonitor scenario(boolean slowEnabled) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(8L);
        m.setName("Takım A / Giriş Akışı");
        m.setTeamId(7L);
        m.setSlowResponseEnabled(slowEnabled);
        m.setSlowThresholdMs(15000);
        return m;
    }

    // ── O-2 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O-2: yürütülemeyen (SKIPPED) elle sentetik koşum HİÇ değerlendirilmez (ne FAIL ne SLOW kalemi)")
    void skippedManualScriptedRun_isNotEvaluated() throws Exception {
        ScriptedCheckerService.ScriptedResult skipped = new ScriptedCheckerService.ScriptedResult("SKIPPED", false, null, -1,
                null, null, null, null, null, null, null, "k6 havuzu dolu", false, ScriptedCheckerService.Phases.EMPTY);
        lenient().when(scripted.run(any())).thenReturn(skipped);
        lenient().when(scripted.runManual(any())).thenReturn(skipped);   // elle koşum ayrı kotadan (D-10)

        scheduler.triggerScriptedCheckAsync(scenario(true)).get(10, TimeUnit.SECONDS);

        verify(outage, never()).handleSweepResults(anyString(), anyList(), anyBoolean());
        verify(outage, never()).handleSweepResults(anyString(), anyList());
    }

    @Test
    @DisplayName("O-2: yavaşlık izlemesi AÇIK ve koşum DÜŞTÜ → SLOW kalemi üretilmez (süre ölçülemedi = kanıt yok)")
    void failedRun_withSlowEnabled_producesNoSlowItem() {
        scheduler.evaluateScriptedNow(scenario(true), raw("up", false, "detail", "FAIL — 0/2", "response_ms", 60000L));
        assertThat(itemsFor(EscalationService.TYPE_SCRIPTED_SLOW)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_SCRIPTED_FAIL)).hasSize(1);
    }

    @Test
    @DisplayName("O-2 korunur: yavaşlık KAPALIYKEN sentetik 'up' (asılı SLOW alarmını kapatır); geçen yavaş koşum DOWN")
    void slowDisabled_emitsSyntheticUp_andSlowPassingRunIsDown() {
        scheduler.evaluateScriptedNow(scenario(false), raw("up", false, "detail", "FAIL — 0/2"));
        List<MonitoringOutageService.SweepItem> off = itemsFor(EscalationService.TYPE_SCRIPTED_SLOW);
        assertThat(off).hasSize(1);
        assertThat(off.get(0).up()).isTrue();

        clearInvocations(outage);
        scheduler.evaluateScriptedNow(scenario(true), raw("up", true, "detail", "PASS", "response_ms", 20000L));
        List<MonitoringOutageService.SweepItem> slow = itemsFor(EscalationService.TYPE_SCRIPTED_SLOW);
        assertThat(slow).hasSize(1);
        assertThat(slow.get(0).up()).isFalse();
    }

    @Test
    @DisplayName("O-2: SLOW yeniden ölçümü (teyit/kurtarma zinciri) koşum düşerse 'skipped' döner — 'up' DEĞİL")
    void slowRecheck_failedRun_isSkipped() {
        when(scripted.run(any())).thenReturn(new ScriptedCheckerService.ScriptedResult("FAIL", false, 1200L, 99,
                1, 3, null, null, null, null, null, "threshold", false, ScriptedCheckerService.Phases.EMPTY));
        scheduler.evaluateScriptedNow(scenario(true), raw("up", true, "detail", "PASS", "response_ms", 20000L));
        MonitoringOutageService.SweepItem slow = itemsFor(EscalationService.TYPE_SCRIPTED_SLOW).get(0);

        Map<String, Object> r = slow.recheck().get();
        assertThat(r.get("status")).isEqualTo("skipped");
    }

    // ── O-5 ────────────────────────────────────────────────────────────────────────────────────

    private static DomainMonitor domain(boolean lockAlert) {
        DomainMonitor m = new DomainMonitor();
        m.setId(9L);
        m.setName("Alan A");
        m.setDomain("example.com");
        m.setTeamId(7L);
        m.setTransferLockAlert(lockAlert);
        return m;
    }

    @Test
    @DisplayName("O-5: veri yok (RDAP düştü, status UNKNOWN) → EXPIRY/STATUS/TRANSFER_LOCK/BLACKLIST kalemi ÜRETİLMEZ; UNKNOWN alarmı DOWN")
    void noDomainData_producesNoHealthyItems() {
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "UNKNOWN", "error", "RDAP timeout",
                "transfer_lock", "UNKNOWN", "blacklist_status", DnsblCheckerService.UNKNOWN));

        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_EXPIRY)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_STATUS)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_BLACKLIST)).isEmpty();
        List<MonitoringOutageService.SweepItem> unknown = itemsFor(EscalationService.TYPE_DOMAINMON_UNKNOWN);
        assertThat(unknown).hasSize(1);
        assertThat(unknown.get(0).up()).isFalse();
    }

    @Test
    @DisplayName("O-5 korunur: gerçek sağlıklı veri → EXPIRY/STATUS/LOCK/BLACKLIST 'up'; kilit uyarısı KAPALI + UNKNOWN → sentetik 'up'; kara liste KAPALI (SKIPPED) → 'up'")
    void realHealthyData_andDisabledToggles_areUp() {
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "OK", "source", "RDAP", "days_remaining", 200,
                "transfer_lock", "BOTH", "blacklist_status", DnsblCheckerService.CLEAN));
        for (String t : List.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_STATUS,
                EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST)) {
            List<MonitoringOutageService.SweepItem> it = itemsFor(t);
            assertThat(it).as(t).hasSize(1);
            assertThat(it.get(0).up()).as(t).isTrue();
        }

        clearInvocations(outage);
        scheduler.evaluateDomainAlarmsNow(domain(false), raw("status", "OK", "days_remaining", 200,
                "transfer_lock", "UNKNOWN", "blacklist_status", DnsblCheckerService.SKIPPED));
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK)).hasSize(1);
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK).get(0).up()).isTrue();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_BLACKLIST)).hasSize(1);
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_BLACKLIST).get(0).up()).isTrue();
    }

    @Test
    @DisplayName("O-5: veri VAR ama kilit durumu doğrulanamadı (WHOIS kaynağı → UNKNOWN), kilit uyarısı açık → LOCK kalemi yok")
    void lockUnknown_withAlertOn_producesNoLockItem() {
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "OK", "days_remaining", 200,
                "transfer_lock", "UNKNOWN", "blacklist_status", DnsblCheckerService.CLEAN));
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_EXPIRY)).hasSize(1);
    }

    // ── D-b5 / D-b4 ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-b5: WHOIS yedeğinde BOŞ EPP listesi 'kod yok' değil 'okunamadı' → STATUS kalemi yok; WHOIS'te kod varsa ölçülü")
    void whoisEmptyEppList_producesNoStatusItem() {
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "OK", "source", "WHOIS", "days_remaining", 200,
                "status_codes", List.of(), "transfer_lock", "UNKNOWN", "blacklist_status", DnsblCheckerService.CLEAN));
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_STATUS)).isEmpty();
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_EXPIRY)).as("gün WHOIS'ten ölçülü").hasSize(1);

        clearInvocations(outage);
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "OK", "source", "WHOIS", "days_remaining", 200,
                "status_codes", List.of("clientTransferProhibited"), "transfer_lock", "UNKNOWN",
                "blacklist_status", DnsblCheckerService.CLEAN));
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_STATUS)).hasSize(1);
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_STATUS).get(0).up()).isTrue();
    }

    @Test
    @DisplayName("D-b4: alan adı yeniden kontrolü VERİ getirmezse (RDAP düştü) EXPIRY/STATUS/LOCK/BLACKLIST hükmü 'skipped' — 'up' DEĞİL")
    void domainRecheck_noData_isSkipped() {
        scheduler.evaluateDomainAlarmsNow(domain(true), raw("status", "WARNING", "source", "RDAP", "days_remaining", 10,
                "epp_warn", true, "status_codes", List.of("clientHold"), "transfer_lock", "NONE",
                "blacklist_status", DnsblCheckerService.LISTED));
        DomainCheckerService checker = (DomainCheckerService) ReflectionTestUtils.getField(scheduler, "domainCheckerService");
        when(checker.checkRecheck(any(DomainMonitor.class))).thenReturn(raw("status", "UNKNOWN", "source", "NONE",
                "error", "RDAP timeout", "transfer_lock", "UNKNOWN", "blacklist_status", DnsblCheckerService.UNKNOWN));
        for (String t : List.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_STATUS,
                EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST)) {
            MonitoringOutageService.SweepItem it = itemsFor(t).get(0);
            assertThat(it.up()).as(t).isFalse();
            assertThat(it.recheck().get().get("status")).as(t).isEqualTo("skipped");
        }
        assertThat(itemsFor(EscalationService.TYPE_DOMAINMON_UNKNOWN).get(0).recheck().get().get("status"))
                .as("veri yokluğu UNKNOWN türünün kendi arızasıdır").isEqualTo("down");

        when(checker.checkRecheck(any(DomainMonitor.class))).thenReturn(raw("status", "OK", "source", "RDAP",
                "days_remaining", 200, "status_codes", List.of(), "transfer_lock", "BOTH",
                "blacklist_status", DnsblCheckerService.CLEAN));
        for (String t : List.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_STATUS,
                EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST)) {
            assertThat(itemsFor(t).get(0).recheck().get().get("status")).as(t + " gerçek sağlıklı veri").isEqualTo("up");
        }
    }
}
