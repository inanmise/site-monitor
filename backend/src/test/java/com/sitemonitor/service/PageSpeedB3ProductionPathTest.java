package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.baseline.BaselineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prod kapısı düzeltmesi B3 (2026-11-15): bant karar veremezken (kuruluyor / bilinmiyor) YÜKLEME DIŞI bir eşikle açılmış
 * Sayfa Hızı alarmı, o eşikler içine dönünce kapanabilmeli. Açık alarmın sebebi eskiden {@code breached_metrics} bağlam
 * anahtarından okunuyordu — ama alarm bağlamı {@code EscalationService.snapshotContext} beyaz listesinden saklanır ve o
 * anahtar listede yok; metot her zaman false dönüyor, alarm haftalarca açık kalabiliyordu. Eski test bağlamı ELLE yazdığı için
 * göremedi. Bu test bağlamı ÜRETİMİN yazdığı yoldan kurar: tarama kalemi ({@code addPageSpeedSweepItems}) → MOS'un teyit
 * bağlamı (kalem bağlamı aynen) → {@code snapshotContext} (alarm olayına yazılan JSON) → sonraki karar verilemeyen tur.
 */
class PageSpeedB3ProductionPathTest {

    private static final String URL = "https://hiz.example.com/";

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    /** Bant AÇIK ama karar veremiyor (kuruluyor) — sayfa ölçüldü; eşik tablosu ve kanıtlı ihlaller verilen. */
    private static Map<String, Object> buildingRound(List<String> breached, String thresholds) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", breached.isEmpty() ? "OK" : "SLOW");
        r.put("reachable", true);
        r.put("within_thresholds", breached.isEmpty());
        r.put("breached", breached);
        r.put("undecided", List.of());
        r.put("response_ms", 1200L);
        r.put("total_bytes", 400_000L);
        r.put(SchedulerService.PS_THRESHOLDS, thresholds);
        r.put(BaselineService.CTX_ON, true);
        r.put(BaselineService.CTX_EVALUATED, false);
        return r;
    }

    private static List<MonitoringOutageService.SweepItem> slowItems(SchedulerService s, PageSpeedMonitor m, Map<String, Object> r) {
        List<MonitoringOutageService.SweepItem> down = new ArrayList<>();
        List<MonitoringOutageService.SweepItem> slow = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(s, "addPageSpeedSweepItems", m, r, down, slow);
        return slow;
    }

    @Test
    @DisplayName("B3 üretim yolu: TTFB ile açılan alarmın saklanan bağlamından sebep okunur; sonraki kararsız turda kalem 'up'")
    void alarmOpenedByTtfbClosesThroughStoredContext() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        provided.put(tools.jackson.databind.ObjectMapper.class, new tools.jackson.databind.ObjectMapper());
        EscalationService escalation = build(EscalationService.class, provided);
        provided.put(EscalationService.class, escalation);
        SchedulerService scheduler = build(SchedulerService.class, provided);

        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(61L); m.setName("Ödeme sayfası"); m.setUrl(URL); m.setTeamId(3L); m.setMaxTtfbMs(300);

        // Tur 1: bant kuruluyor, TTFB eşiği aşıldı → kalem DOWN (alarm açılır). Bağlam üretimin yolundan saklanır.
        List<MonitoringOutageService.SweepItem> first = slowItems(scheduler, m,
                buildingRound(List.of("TTFB"), "LOAD\t3000\t1200\t0\t0\nTTFB\t300\t900\t1\t0"));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).up()).isFalse();
        Map<String, Object> outage = new LinkedHashMap<>(first.get(0).ctxExtra());   // MOS buildOutageContext: putAll
        String json = ReflectionTestUtils.invokeMethod(escalation, "snapshotContext", outage);
        assertThat(json).as("eşik tablosu saklanır").contains(SchedulerService.PS_THRESHOLDS)
                .as("breached_metrics beyaz listede değil — eski okuyucu bunu bulamıyordu").doesNotContain("breached_metrics");

        AlertEvent open = new AlertEvent();
        open.setDomain(URL);
        open.setAlertType(EscalationService.TYPE_PAGESPEED_SLOW);
        open.setContextJson(json);
        AlertEventRepository repo = mock(AlertEventRepository.class);
        when(repo.findOpenAlert(URL, EscalationService.TYPE_PAGESPEED_SLOW)).thenReturn(Optional.of(open));
        ReflectionTestUtils.setField(scheduler, "pageSpeedAlertRepo", repo);
        assertThat(scheduler.openPageSpeedSlowWithoutLoad(m)).isTrue();

        // Tur 2: bant HÂLÂ karar veremiyor, TTFB artık eşik içinde → kalem UP (alarm kapanabilir)
        List<MonitoringOutageService.SweepItem> second = slowItems(scheduler, m,
                buildingRound(List.of(), "LOAD\t3000\t1100\t0\t0\nTTFB\t300\t200\t0\t0"));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).up()).isTrue();
    }

    @Test
    @DisplayName("Sebep okuma: ihlal bayraklı tablo satırları; LOAD / BAND yükleme kaynaklı; büyüme anahtarları yükleme DIŞI; tablo yoksa eski anahtar")
    void causesTruthTable() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"ps_thresholds\":\"TTFB\\t300\\t900\\t1\\t0\\nSIZE\\t1000\\t900\\t0\\t0\"}")))
                .containsExactly("TTFB");
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"ps_thresholds\":\"BAND\\t95|105|up\\t300\\t1\\t0\"}")))
                .containsExactly("BAND");
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"ps_thresholds\":\"GROWSIZE\\t20|1000\\t1400\\t1\\t0\"}")))
                .containsExactly("GROWSIZE");
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"breached_metrics\":[\"JS\"]}"))).containsExactly("JS");
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"breached_metrics\":\"IMG,FONT\"}"))).containsExactly("IMG", "FONT");
        assertThat(SchedulerService.pageSpeedAlarmCauses(om.readTree("{\"url\":\"x\"}"))).isNull();
        assertThat(SchedulerService.pageSpeedAlarmCauses(null)).isNull();
    }
}
