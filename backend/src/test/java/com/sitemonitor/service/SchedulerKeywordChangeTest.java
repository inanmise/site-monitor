package com.sitemonitor.service;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.service.keyword.KeywordCheckSpec;
import com.sitemonitor.service.keyword.content.KeywordChangeBaselineService;
import com.sitemonitor.service.keyword.content.KeywordChangeDetector;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zamanlayıcının içerik değişimi / kural bağlaması (kw sözleşmesi §6.4 / §7.2 / §12 B13, be2 Aşama 2): KEYWORD_CHANGED kalemi yalnız
 * değişim DEĞERLENDİRİLDİYSE; zamanlanmış kontrolde temel yoksa otomatik alınır (teyit / elle almaz); sonuç şekli yeni
 * anahtarları taşır; tanım özeti; KEYWORD kalemine seviye yalnız kural eşiğinden geldiyse.
 */
class SchedulerKeywordChangeTest {

    private SchedulerService scheduler;
    private KeywordCheckerService checker;
    private KeywordChangeBaselineService baselines;
    private MonitoringOutageService outage;

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
        checker = mock(KeywordCheckerService.class);
        outage = mock(MonitoringOutageService.class);
        baselines = mock(KeywordChangeBaselineService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(KeywordCheckerService.class, checker);
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(KeywordMonitorRepository.class, mock(KeywordMonitorRepository.class));
        provided.put(KeywordResultRepository.class, mock(KeywordResultRepository.class));
        provided.put(CertificateInventoryRepository.class, mock(CertificateInventoryRepository.class));
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", mock(KeywordHeaderSecrets.class));
        ReflectionTestUtils.setField(scheduler, "keywordBaselines", baselines);
    }

    private static KeywordMonitor monitor() {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(41L); m.setName("Kampanya"); m.setUrl("https://kampanya.example.com/"); m.setKeyword("Kampanya");
        m.setMatchOperator("GTE"); m.setMatchCount(1); m.setTeamId(5L); m.setActive(true);
        m.setChangeDetect(true); m.setChangeMask("DIGITS");
        return m;
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("KEYWORD_CHANGED kalemi: değişim değerlendirildiyse (true → down, false → up); kapalı / temel yok → kalem YOK; "
            + "bağlam postanın okuduğu anahtarları taşır, hata metni yok (kesinti değil)")
    void changeItem() {
        KeywordMonitor m = monitor();
        Map<String, Object> summary = raw("removed_total", 1, "added_total", 3, "removed", List.of("a"), "added", List.of("b"));
        var down = scheduler.keywordChangeSweepItem(m, raw("content_changed", true, "change_summary", summary,
                "baseline_taken_at", "2026-11-01T06:00:00", "baseline_source", "AUTO"));
        assertThat(down).isNotNull();
        assertThat(down.alertType()).isEqualTo(EscalationService.TYPE_KEYWORD_CHANGED);
        assertThat(down.up()).isFalse();
        assertThat(down.error()).isNull();
        assertThat(down.ctxExtra()).containsEntry("changed_lines", 3).containsEntry("baseline_source", "AUTO")
                .containsEntry("change_mask", "DIGITS").containsEntry("monitor_id", 41L).containsKey("checked_at")
                .containsEntry("alert_level", "WARNING");
        assertThat(scheduler.keywordChangeSweepItem(m, raw("content_changed", false)).up()).isTrue();
        assertThat(scheduler.keywordChangeSweepItem(m, raw("baseline_missing", true))).isNull();
        m.setChangeDetect(null);
        assertThat(scheduler.keywordChangeSweepItem(m, raw("content_changed", true))).isNull();
    }

    @Test
    @DisplayName("Zamanlanmış kontrol: temel yoksa ölçülen anlık görüntüyle otomatik temel; teyit yeniden kontrolü temel ALMAZ")
    void autoBaselineOnlyOnScheduled() {
        KeywordMonitor m = monitor();
        var snap = KeywordChangeDetector.snapshot(List.of("x"), java.util.EnumSet.noneOf(
                com.sitemonitor.service.keyword.content.ContentMasker.Mask.class), List.of());
        when(checker.check(any(KeywordCheckSpec.class))).thenAnswer(a -> {
            KeywordCheckSpec sp = a.getArgument(0);
            if (sp.changeCapture() != null) sp.changeCapture().put(snap, 77L, "spec1");
            return raw("ok", true, "found", true, "count", 1, "baseline_missing", true);
        });
        Map<String, Object> out = ReflectionTestUtils.invokeMethod(scheduler, "recheckKeyword", m, true);
        verify(baselines).takeAuto(41L, snap, 77L, "spec1");
        assertThat(out).containsKey("keyword_spec");
        assertThat(out.get("keyword_spec")).isEqualTo(SchedulerService.keywordSpecHash(m, null));

        org.mockito.Mockito.clearInvocations(baselines);
        ReflectionTestUtils.invokeMethod(scheduler, "recheckKeyword", m, false);
        verify(baselines, never()).takeAuto(anyLong(), any(), anyLong(), anyString());
        // temel yüklendi (karşılaştırma için)
        ArgumentCaptor<KeywordCheckSpec> cap = ArgumentCaptor.forClass(KeywordCheckSpec.class);
        verify(checker, org.mockito.Mockito.atLeastOnce()).check(cap.capture());
        assertThat(cap.getValue().changeDetect()).isTrue();
        verify(baselines, org.mockito.Mockito.atLeastOnce()).view(41L);
    }

    @Test
    @DisplayName("kw son inceleme O-3: otomatik temel YALNIZ sağlıklı kontrolden — koşulu sağlamayan / hatalı kontrolün anlık görüntüsü "
            + "temel olmaz; değişim ölçülmediyse (change_skipped) KEYWORD_CHANGED kalemi yok (alarm açılmaz, kapanmaz)")
    void autoBaselineOnlyFromHealthyCheck() {
        KeywordMonitor m = monitor();
        var snap = KeywordChangeDetector.snapshot(List.of("x"), java.util.EnumSet.noneOf(
                com.sitemonitor.service.keyword.content.ContentMasker.Mask.class), List.of());
        when(checker.check(any(KeywordCheckSpec.class))).thenAnswer(a -> {
            KeywordCheckSpec sp = a.getArgument(0);
            if (sp.changeCapture() != null) sp.changeCapture().put(snap, 77L, "spec1");
            return raw("ok", false, "found", false, "count", 0, "baseline_missing", true);
        });
        ReflectionTestUtils.invokeMethod(scheduler, "recheckKeyword", m, true);
        verify(baselines, never()).takeAuto(anyLong(), any(), anyLong(), anyString());
        // hata metni olan "ok" da sağlıklı sayılmaz
        when(checker.check(any(KeywordCheckSpec.class))).thenAnswer(a -> {
            KeywordCheckSpec sp = a.getArgument(0);
            if (sp.changeCapture() != null) sp.changeCapture().put(snap, 77L, "spec1");
            return raw("ok", true, "found", true, "count", 1, "baseline_missing", true, "error", "HTTP 503");
        });
        ReflectionTestUtils.invokeMethod(scheduler, "recheckKeyword", m, true);
        verify(baselines, never()).takeAuto(anyLong(), any(), anyLong(), anyString());
        // ölçülmeyen değişim → kalem yok
        assertThat(scheduler.keywordChangeSweepItem(m, raw("ok", true, "change_skipped", "HTTP_ERROR"))).isNull();
    }

    @Test
    @DisplayName("Sonuç şekli: seviye, kural özeti, değişim, temel zamanı, tanım özeti taşınır (yalnız varsa — eski ham sonuç bugünkü şekil)")
    void outcomeCarriesBe2Keys() {
        KeywordMonitor m = monitor();
        Map<String, Object> out = SchedulerService.keywordOutcome(m, raw("ok", false, "found", false, "count", 0, "level", "CRITICAL",
                "rules_summary", "2/3 kural sağlanmadı", "content_changed", true, "change_summary", raw("added_total", 1),
                "baseline_taken_at", "2026-11-01T06:00:00", "baseline_source", "MANUAL", "keyword_spec", "abc"));
        assertThat(out).containsEntry("level", "CRITICAL").containsEntry("rules_summary", "2/3 kural sağlanmadı")
                .containsEntry("content_changed", true).containsEntry("baseline_source", "MANUAL").containsEntry("keyword_spec", "abc");
        Map<String, Object> legacy = SchedulerService.keywordOutcome(m, raw("found", true, "count", 2));
        assertThat(legacy).doesNotContainKeys("level", "rules_summary", "content_changed", "keyword_spec");
    }

    @Test
    @DisplayName("KEYWORD kalemi: seviye yalnız KURAL eşiğinden geldiyse bağlama yazılır (izleme seviyesine eşitse bugünkü bağlam); kural özeti")
    void keywordItemLevel() {
        KeywordMonitor m = monitor();
        m.setAlertLevel("HIGH");
        var item = (MonitoringOutageService.SweepItem) ReflectionTestUtils.invokeMethod(scheduler, "keywordSweepItem", m,
                SchedulerService.keywordOutcome(m, raw("ok", false, "count", 0, "level", "CRITICAL", "rules_summary", "özet")));
        assertThat(item.ctxExtra()).containsEntry("alert_level", "CRITICAL").containsEntry("rules_summary", "özet");
        var same = (MonitoringOutageService.SweepItem) ReflectionTestUtils.invokeMethod(scheduler, "keywordSweepItem", m,
                SchedulerService.keywordOutcome(m, raw("ok", false, "count", 0, "level", "HIGH")));
        assertThat(same.ctxExtra()).containsEntry("alert_level", "HIGH").doesNotContainKey("rules_summary");
    }

    @Test
    @DisplayName("B13 tanım özeti: kural / boyut / değişim / istek ayarı değişince değişir; aynı izlemede kararlı (16 onaltılık)")
    void specHash() {
        KeywordMonitor m = monitor();
        String h = SchedulerService.keywordSpecHash(m, null);
        assertThat(h).hasSize(16).isEqualTo(SchedulerService.keywordSpecHash(monitor(), null));
        m.setMaxBytes(1000);
        assertThat(SchedulerService.keywordSpecHash(m, null)).isNotEqualTo(h);
        KeywordMonitor r = monitor();
        r.setRulesJson("[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"a\",\"operator\":\"GTE\",\"count\":1}]");
        assertThat(SchedulerService.keywordSpecHash(r, null)).isNotEqualTo(h);
        KeywordMonitor s = monitor();
        s.setChangeSelector("#x");
        assertThat(SchedulerService.keywordSpecHash(s, null)).isNotEqualTo(h);
    }

    @Test
    @DisplayName("hata avı 2026-11-06 H4#7: tanım özeti istek parmak izinin tamamını (URL, gövde, kullanıcı adı, özel başlık, UA, "
            + "yönlendirme), şifreli parolayı, zaman aşımını ve vekil kipini içerir; örnek yöntemi başlıkları çözer")
    void specHashCoversRequest() {
        KeywordMonitor m = monitor();
        String h = SchedulerService.keywordSpecHash(m, null);
        java.util.List<java.util.function.Consumer<KeywordMonitor>> changes = java.util.List.of(
                x -> x.setUrl("https://kampanya.example.com/yeni"),
                x -> { x.setMethod("POST"); x.setRequestBody("a=1"); },
                x -> x.setBasicAuthUser("izleme"),
                x -> x.setBasicAuthPassEnc("enc:v1:yeni-parola"),
                x -> x.setUserAgentMode("BROWSER"),
                x -> x.setFollowRedirects(false),
                x -> x.setExpectedFinalUrl("/hesap"),
                x -> x.setTimeoutMs(20_000),
                x -> x.setUseProxy("ON"));
        for (int i = 0; i < changes.size(); i++) {
            KeywordMonitor x = monitor();
            changes.get(i).accept(x);
            assertThat(SchedulerService.keywordSpecHash(x, null)).as("değişiklik #" + i).isNotEqualTo(h);
        }
        assertThat(SchedulerService.keywordSpecHash(m, "X-Api-Key: a")).isNotEqualTo(h)
                .isNotEqualTo(SchedulerService.keywordSpecHash(m, "X-Api-Key: b"));
        KeywordHeaderSecrets secrets = (KeywordHeaderSecrets) ReflectionTestUtils.getField(scheduler, "keywordHeaderSecrets");
        when(secrets.effectiveHeaders(m)).thenReturn("X-Api-Key: a");
        assertThat(scheduler.keywordSpecHash(m)).isEqualTo(SchedulerService.keywordSpecHash(m, "X-Api-Key: a"));
    }

    @Test
    @DisplayName("Elle değerlendirme: değişim kalemi YALNIZ uzlaştırma (manual=true) ile beslenir; değişim yoksa çağrı yok")
    void manualEvaluationReconcilesChange() {
        KeywordMonitor m = monitor();
        scheduler.evaluateKeywordNow(m, raw("ok", true, "found", true, "count", 1, "content_changed", false));
        verify(outage).handleSweepResults(eq(EscalationService.TYPE_KEYWORD_CHANGED), any(), eq(true));
        org.mockito.Mockito.clearInvocations(outage);
        scheduler.evaluateKeywordNow(m, raw("ok", true, "found", true, "count", 1));
        verify(outage, never()).handleSweepResults(eq(EscalationService.TYPE_KEYWORD_CHANGED), any(), anyBoolean());
    }
}
