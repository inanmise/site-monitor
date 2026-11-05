package com.sitemonitor.service;

import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlarmText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sorgu alarmı postasının "İlk ihlal", "Kaç çalıştırmadır" ve "Son değerler" bölümü (2026-11-05, dbq2 C4): son ZAMANLANMIŞ
 * çalıştırmalardan TEK sorgu; yalnız ölçülen değer / durum (satır verisi YOK); okunamazsa bölüm yazılmaz, posta yine gider.
 */
class DbQueryAlarmMailHistoryTest {

    /** {@code [checkedAt, status, metricValue, ok, breachLevel, failureReason, slow]} */
    private static Object[] row(String at, String status, Double value, boolean ok, String level) {
        return new Object[] {at, status, value, ok, level, ok ? null : "DB_TIMEOUT", false};
    }

    private static List<Object[]> threshold() {
        return List.of(
                row("2026-11-05T09:00:00", "BREACH", 31.0, true, "CRITICAL"),
                row("2026-11-05T08:55:00", "BREACH", 27.0, true, "WARNING"),
                row("2026-11-05T08:50:00", "BREACH", 12.0, true, "WARNING"),
                row("2026-11-05T08:45:00", "OK", 6.0, true, "NONE"),
                row("2026-11-05T08:40:00", "FAIL", null, false, "NONE"));
    }

    @Test
    @DisplayName("eşik: ardışık BREACH sayısı + serinin ilk zamanı; tablo yeniden eskiye; FAIL satırının değeri yazılmaz")
    void thresholdStreak() {
        Map<String, Object> extra = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(extra, threshold(), EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(extra).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 3)
                .containsEntry(EscalationService.CTX_DBQUERY_FIRST_PROBLEM, "2026-11-05T08:50:00")
                .containsEntry(EscalationService.CTX_DBQUERY_STREAK_KIND, "BREACH")
                .doesNotContainKey(EscalationService.CTX_DBQUERY_STREAK_CAPPED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hist = (List<Map<String, Object>>) extra.get(EscalationService.CTX_DBQUERY_HISTORY);
        assertThat(hist).hasSize(5);
        assertThat(hist.get(0)).containsEntry("checked_at", "2026-11-05T09:00:00").containsEntry("metric_value", 31.0);
        assertThat(hist.get(4).get("metric_value")).isNull();
        assertThat(hist.get(0)).containsOnlyKeys("checked_at", "status", "metric_value", "breach_level");
    }

    @Test
    @DisplayName("hata: ok=false serisi; kural değerlendirilemedi (DB_RULE_UNEVALUABLE) → UNKNOWN serisi; neden yoksa en yeni satırdan")
    void failStreaks() {
        List<Object[]> fails = List.of(row("2026-11-05T09:00:00", "FAIL", null, false, "NONE"),
                row("2026-11-05T08:55:00", "FAIL", null, false, "NONE"), row("2026-11-05T08:50:00", "OK", 1.0, true, "NONE"));
        Map<String, Object> f = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(f, fails, EscalationService.TYPE_DBQUERY_FAIL, "DB_TIMEOUT");
        assertThat(f).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 2).containsEntry(EscalationService.CTX_DBQUERY_STREAK_KIND, "FAIL");

        List<Object[]> unknown = List.of(row("2026-11-05T09:00:00", "UNKNOWN", 4.0, true, "NONE"),
                row("2026-11-05T08:55:00", "UNKNOWN", 4.0, true, "NONE"), row("2026-11-05T08:50:00", "UNKNOWN", 4.0, true, "NONE"),
                row("2026-11-05T08:45:00", "FAIL", null, false, "NONE"));
        Map<String, Object> u = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(u, unknown, EscalationService.TYPE_DBQUERY_FAIL, DbQueryAlarmText.RULE_UNEVALUABLE_CODE);
        assertThat(u).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 3).containsEntry(EscalationService.CTX_DBQUERY_STREAK_KIND, "UNKNOWN");
        // D9: tür YALNIZ alarm türü + bağlamdan — neden yoksa en yeni (UNKNOWN) satırdan "değerlendirilemeyen" çıkarılmaz
        Map<String, Object> notInferred = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(notInferred, unknown, EscalationService.TYPE_DBQUERY_FAIL, null);
        assertThat(notInferred).doesNotContainKey(EscalationService.CTX_DBQUERY_STREAK)
                .containsKey(EscalationService.CTX_DBQUERY_HISTORY);
        // eski anlık görüntü: neden yok ama hata metni "[DB_RULE_UNEVALUABLE] …" → UNKNOWN serisi
        Map<String, Object> oldCtx = new LinkedHashMap<>();
        oldCtx.put("error", DbQueryAlarmText.unevaluableError(List.of("column_missing"), 3));
        assertThat(EscalationService.dbQueryFailureReason(oldCtx)).isEqualTo(DbQueryAlarmText.RULE_UNEVALUABLE_CODE);
        assertThat(EscalationService.dbQueryFailureReason(Map.of("error", "[DB_TIMEOUT] zaman aşımı"))).isNull();
        assertThat(EscalationService.dbQueryFailureReason(Map.of("failure_reason", "DB_AUTH"))).isEqualTo("DB_AUTH");
    }

    /** sql_hash'li satır (8. kolon). */
    private static Object[] hashed(String at, String status, Double value, String hash) {
        return new Object[] {at, status, value, true, "WARNING", null, false, hash};
    }

    @Test
    @DisplayName("D9: sorgu değişince seri ve tablo kesilir — eski sorgunun ihlal satırları sayılmaz, 'en az' yazılmaz")
    void seriesCutAtSqlHashChange() {
        List<Object[]> rows = List.of(
                hashed("2026-11-05T09:00:00", "BREACH", 31.0, "h2"),
                hashed("2026-11-05T08:55:00", "BREACH", 30.0, "h2"),
                hashed("2026-11-05T08:50:00", "BREACH", 99.0, "h1"),
                hashed("2026-11-05T08:45:00", "BREACH", 98.0, "h1"));
        Map<String, Object> e = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(e, rows, EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(e).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 2)
                .containsEntry(EscalationService.CTX_DBQUERY_FIRST_PROBLEM, "2026-11-05T08:55:00")
                .doesNotContainKey(EscalationService.CTX_DBQUERY_STREAK_CAPPED);
        assertThat((List<?>) e.get(EscalationService.CTX_DBQUERY_HISTORY)).hasSize(2);

        // pencerenin tamamı aynı sorgunun ihlali → "en az"; sorgu değişimiyle kesilen 100'lük pencere "en az" değil
        List<Object[]> full = new ArrayList<>();
        for (int i = 0; i < EscalationService.DBQUERY_HISTORY_READ; i++)
            full.add(hashed(String.format("2026-11-05T%02d:%02d:00", 9 - i / 60, 59 - i % 60), "BREACH", 50.0, i < 99 ? "h2" : "h1"));
        Map<String, Object> cut = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(cut, full, EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(cut).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 99).doesNotContainKey(EscalationService.CTX_DBQUERY_STREAK_CAPPED);
    }

    @Test
    @DisplayName("en yeni çalıştırma sorunlu değilse seri yazılmaz (yalnız tablo); okunan pencerenin tamamı sorunluysa 'en az'; boş → hiçbir şey")
    void noStreakCappedAndEmpty() {
        List<Object[]> recovered = new ArrayList<>(threshold());
        recovered.add(0, row("2026-11-05T09:05:00", "OK", 4.0, true, "NONE"));
        Map<String, Object> r = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(r, recovered, EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(r).containsKey(EscalationService.CTX_DBQUERY_HISTORY).doesNotContainKey(EscalationService.CTX_DBQUERY_STREAK)
                .doesNotContainKey(EscalationService.CTX_DBQUERY_FIRST_PROBLEM);

        List<Object[]> all = new ArrayList<>();
        for (int i = 0; i < EscalationService.DBQUERY_HISTORY_READ; i++)
            all.add(row(String.format("2026-11-05T%02d:%02d:00", 9 - i / 60, 59 - i % 60), "BREACH", 50.0, true, "CRITICAL"));
        Map<String, Object> capped = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(capped, all, EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(capped).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 100).containsEntry(EscalationService.CTX_DBQUERY_STREAK_CAPPED, true);
        assertThat((List<?>) capped.get(EscalationService.CTX_DBQUERY_HISTORY)).hasSize(EscalationService.DBQUERY_HISTORY_SHOWN);

        Map<String, Object> none = new LinkedHashMap<>();
        EscalationService.putDbQueryHistory(none, List.of(), EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        EscalationService.putDbQueryHistory(none, null, EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        assertThat(none).isEmpty();
    }

    // ── posta parçaları (EscalationService) ─────────────────────────────────

    EscalationService esc;
    DbQueryCheckRepository checks;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(EscalationService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[c.getParameterCount()];
        Class<?>[] pt = c.getParameterTypes();
        for (int i = 0; i < pt.length; i++) args[i] = pt[i].isPrimitive() ? null : mock(pt[i]);
        esc = (EscalationService) c.newInstance(args);
        checks = mock(DbQueryCheckRepository.class);
        ReflectionTestUtils.setField(esc, "dbQueryMonitorRepo", mock(DbQueryMonitorRepository.class));
        ReflectionTestUtils.setField(esc, "dbQueryCheckRepo", checks);
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", 42L);
        c.put(EscalationService.CTX_DBQUERY_FRESH, "2026-11-05T09:00:00");
        return c;
    }

    @Test
    @DisplayName("parçalar: eşik / hata alarmında tek sorgu (son 100 zamanlanmış); yavaş sorgu alarmında yok; okunamazsa sessiz (posta yine kurulur)")
    void mailParts() {
        when(checks.recentScheduledSummary(eq(42L), anyInt())).thenReturn(threshold());
        var parts = esc.dbQueryMailParts(900L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", ctx());
        assertThat(parts.extra()).containsEntry(EscalationService.CTX_DBQUERY_STREAK, 3);
        verify(checks).recentScheduledSummary(42L, EscalationService.DBQUERY_HISTORY_READ);
        // geçmiş GÜNLÜK kopyasında da kalır (yalnız ölçülen değer — satır verisi değil)
        assertThat(parts.apply(ctx(), false)).containsKey(EscalationService.CTX_DBQUERY_HISTORY);

        var slow = esc.dbQueryMailParts(901L, EscalationService.TYPE_DBQUERY_SLOW, "WARNING", ctx());
        assertThat(slow.extra()).doesNotContainKey(EscalationService.CTX_DBQUERY_HISTORY);

        when(checks.recentScheduledSummary(anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));
        var broken = esc.dbQueryMailParts(902L, EscalationService.TYPE_DBQUERY_FAIL, "HIGH", ctx());
        assertThat(broken.extra()).doesNotContainKey(EscalationService.CTX_DBQUERY_HISTORY);
    }

    @Test
    @DisplayName("parçalar: izleme kimliği yoksa sorgu yok")
    void noMonitorNoQuery() {
        var parts = esc.dbQueryMailParts(903L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", Map.of());
        assertThat(parts.extra()).doesNotContainKey(EscalationService.CTX_DBQUERY_HISTORY);
        verify(checks, never()).recentScheduledSummary(any(), anyInt());
    }

    // ── görünüm (EmailTemplateBuilder) ──────────────────────────────────────

    @Test
    @DisplayName("posta: 'İlk İhlal', 'Kaç Çalıştırmadır', 'Son Değerler' tablosu — HTML ve düz metin aynı bilgi; hata serisinde 'İlk Hata'")
    void rendering() {
        AppSettingsService app = mock(AppSettingsService.class);
        when(app.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        EmailTemplateBuilder b = new EmailTemplateBuilder(app);
        ReflectionTestUtils.setField(b, "appBaseUrl", "http://cm.local");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", 42L);
        c.put("monitor_name", "Bekleyen Siparişler");
        c.put("checked_at", "2026-11-05T09:00:00");
        c.put("metric_value", 31.0);
        c.put("dbquery_status", "BREACH");
        EscalationService.putDbQueryHistory(c, threshold(), EscalationService.TYPE_DBQUERY_THRESHOLD, null);
        var mail = new EmailTemplateBuilder.AlertMail("DBQUERY_THRESHOLD", "CRITICAL", "Bekleyen Siparişler",
                "KRİTİK: sorgu kuralı ihlal edildi.", null, c, "Takım A");
        String html = b.buildHtml(mail);
        String text = b.buildText(mail);
        for (String s : new String[] {html, text}) {
            assertThat(s).contains("İlk İhlal").contains("5 Kasım 2026 11:50").contains("3 zamanlanmış çalıştırmadır")
                    .contains("Son Değerler").contains("İhlal (kritik)").contains("İhlal (uyarı)").contains("Normal").contains("Hata")
                    .contains("27");
        }
        assertThat(html.indexOf("Son Değerler")).isGreaterThan(0);

        Map<String, Object> f = new LinkedHashMap<>(c);
        f.keySet().removeIf(k -> k.startsWith("dbquery_streak") || k.startsWith("dbquery_first") || k.equals("dbquery_history"));
        EscalationService.putDbQueryHistory(f, List.<Object[]>of(row("2026-11-05T09:00:00", "FAIL", null, false, "NONE")),
                EscalationService.TYPE_DBQUERY_FAIL, "DB_TIMEOUT");
        String failHtml = b.buildHtml(new EmailTemplateBuilder.AlertMail("DBQUERY_FAIL", "HIGH", "Bekleyen Siparişler",
                "YÜKSEK: sorgu çalıştırılamadı.", null, f, "Takım A"));
        assertThat(failHtml).contains("İlk Hata").contains("1 zamanlanmış çalıştırmadır").doesNotContain("İlk İhlal");

        // geçmiş yoksa bölümler hiç yazılmaz
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("monitor_id", 42L);
        String none = b.buildHtml(new EmailTemplateBuilder.AlertMail("DBQUERY_THRESHOLD", "CRITICAL", "Bekleyen Siparişler",
                "KRİTİK", null, plain, "Takım A"));
        assertThat(none).doesNotContain("Son Değerler").doesNotContain("Kaç Çalıştırmadır");
    }

    // ── dbq2 inceleme O2: maskeli kolonun ölçüsü postada gizlenir ─────────────

    private static final double SECRET = 31415.0, SECRET_OLD = 27182.0;

    private DbQueryMonitorRepository monitors() {
        return (DbQueryMonitorRepository) ReflectionTestUtils.getField(esc, "dbQueryMonitorRepo");
    }

    private static DbQueryMonitor monitor(String rulesJson, String maskedJson) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(42L);
        m.setName("Bekleyen Siparişler");
        m.setRulesJson(rulesJson);
        m.setMaskedColumnsJson(maskedJson);
        m.setMailSubjectTemplate("Değer {deger}");
        return m;
    }

    private static Map<String, Object> rule(String id, String kind, Double compared, String actual, boolean passed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("label", "Kural " + id);
        r.put("kind", kind);
        r.put("actual", actual);
        r.put("expected", "> 10");
        r.put("level", passed ? "NONE" : "CRITICAL");
        r.put("passed", passed);
        r.put("evaluated", true);
        r.put("measure", compared);
        r.put("compared", compared);
        return r;
    }

    private static Map<String, Object> breachCtx(List<Map<String, Object>> rules, Double metric) {
        Map<String, Object> c = ctx();
        c.put("monitor_name", "Bekleyen Siparişler");
        c.put("checked_at", "2026-11-05T09:00:00");
        if (metric != null) c.put("metric_value", metric);
        c.put("dbquery_status", "BREACH");
        c.put("rule_results", rules);
        c.put("rule_summary", DbQueryAlarmText.breachSummary(rules));
        return c;
    }

    private static List<Object[]> secretHistory() {
        return List.of(row("2026-11-05T09:00:00", "BREACH", SECRET, true, "CRITICAL"),
                row("2026-11-05T08:55:00", "BREACH", SECRET_OLD, true, "CRITICAL"));
    }

    private static String render(String type, Map<String, Object> c) {
        AppSettingsService app = mock(AppSettingsService.class);
        when(app.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        EmailTemplateBuilder b = new EmailTemplateBuilder(app);
        ReflectionTestUtils.setField(b, "appBaseUrl", "http://cm.local");
        var mail = new EmailTemplateBuilder.AlertMail(type, "CRITICAL", "Bekleyen Siparişler", "KRİTİK", null, c, "Takım A");
        return b.buildHtml(mail) + "\n" + b.buildText(mail);
    }

    private static final String ONE_MASKED_RULE = "[{\"id\":\"r1\",\"kind\":\"COLUMN_VALUE\",\"column\":\"Tutar\",\"aggregate\":\"FIRST\"}]";

    @Test
    @DisplayName("O2: kuralın kolonu maskeliyse 'Ölçülen Değer', 'Son Değerler', kural 'Gelen' değeri, ihlal özeti ve konu "
            + "postada (gönderilen + günlük kopyası) •••• — durum / zaman kalır")
    void maskedColumnMeasureHidden() {
        when(monitors().findById(42L)).thenReturn(Optional.of(monitor(ONE_MASKED_RULE, "[\"tutar\"]")));
        when(checks.recentScheduledSummary(eq(42L), anyInt())).thenReturn(secretHistory());
        List<Map<String, Object>> rules = List.of(rule("r1", "COLUMN_VALUE", SECRET, "31415", false));
        Map<String, Object> c = breachCtx(rules, SECRET);
        var parts = esc.dbQueryMailParts(900L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", c);

        assertThat(parts.extra()).containsEntry("metric_value", "••••");
        assertThat(parts.subject()).contains("••••").doesNotContain("31415");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hist = (List<Map<String, Object>>) parts.extra().get(EscalationService.CTX_DBQUERY_HISTORY);
        assertThat(hist).extracting(h -> h.get("metric_value")).containsOnly("••••");
        assertThat(hist).extracting(h -> h.get("checked_at")).containsExactly("2026-11-05T09:00:00", "2026-11-05T08:55:00");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> masked = (List<Map<String, Object>>) parts.extra().get("rule_results");
        assertThat(masked.get(0)).containsEntry("actual", "••••").containsEntry("expected", "> 10")
                .doesNotContainKey("measure").doesNotContainKey("compared").containsEntry("passed", false);
        assertThat(String.valueOf(parts.extra().get("rule_summary"))).contains("••••").doesNotContain("31415");

        for (boolean withRows : new boolean[] {true, false}) {   // gönderilen ve notification_logs kopyası aynı maskeyle
            String out = render("DBQUERY_THRESHOLD", parts.apply(c, withRows));
            assertThat(out).contains("••••").contains("Son Değerler").contains("İhlal (kritik)")
                    .doesNotContain("31415").doesNotContain("27182");
        }
    }

    @Test
    @DisplayName("O2: ölçü maskesiz kuraldan geldiyse 'Ölçülen Değer' açık; maskeli kuralın 'gelen'i maskeli; metin eşleşmesi "
            + "sayısı açık; maske yoksa hiçbir şey değişmez")
    void onlyMaskedRulesHidden() {
        String rulesJson = "[{\"id\":\"r1\",\"kind\":\"COLUMN_VALUE\",\"column\":\"adet\"},"
                + "{\"id\":\"r2\",\"kind\":\"COLUMN_VALUE\",\"column\":\"tutar\"},"
                + "{\"id\":\"r3\",\"kind\":\"TEXT_MATCH\",\"column\":\"tutar\"}]";
        when(monitors().findById(42L)).thenReturn(Optional.of(monitor(rulesJson, "[\"TUTAR\"]")));
        when(checks.recentScheduledSummary(eq(42L), anyInt())).thenReturn(secretHistory());
        List<Map<String, Object>> rules = List.of(rule("r1", "COLUMN_VALUE", 5.0, "5", true),
                rule("r2", "COLUMN_VALUE", SECRET, "31415", false), rule("r3", "TEXT_MATCH", 2.0, "2", false));
        var parts = esc.dbQueryMailParts(900L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", breachCtx(rules, 5.0));
        assertThat(parts.extra()).doesNotContainKey("metric_value");   // bağlamdaki 5 (maskesiz kural) aynen kalır
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rr = (List<Map<String, Object>>) parts.extra().get("rule_results");
        assertThat(rr.get(0)).containsEntry("actual", "5");
        assertThat(rr.get(1)).containsEntry("actual", "••••");
        assertThat(rr.get(2)).containsEntry("actual", "2");   // metin eşleşmesi: gelen = eşleşen satır SAYISI (hücre değil)

        // maske listesi boş → hiçbir şey yazılmaz
        when(monitors().findById(42L)).thenReturn(Optional.of(monitor(rulesJson, "[]")));
        var plain = esc.dbQueryMailParts(901L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", breachCtx(rules, SECRET));
        assertThat(plain.extra()).doesNotContainKey("metric_value").doesNotContainKey("rule_results");
        assertThat(plain.subject()).contains("31415");
    }

    @Test
    @DisplayName("O2: izleme okunamazsa temkinli (ölçü maskeli); çözüm postası ve saklanan alarm iletisi de maskeli")
    void unreadableMonitorAndResolutionAndMessage() {
        when(monitors().findById(42L)).thenThrow(new IllegalStateException("db"));
        List<Map<String, Object>> rules = List.of(rule("r1", "COLUMN_VALUE", SECRET, "31415", false));
        var parts = esc.dbQueryMailParts(900L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", breachCtx(rules, SECRET));
        assertThat(parts.extra()).containsEntry("metric_value", "••••");

        org.mockito.Mockito.reset(monitors());
        when(monitors().findById(42L)).thenReturn(Optional.of(monitor(ONE_MASKED_RULE, "[\"tutar\"]")));
        com.sitemonitor.model.DbQueryCheck latest = new com.sitemonitor.model.DbQueryCheck();
        latest.setStatus("BREACH");
        latest.setMetricValue(SECRET_OLD);
        latest.setRuleResultsJson("[{\"id\":\"r1\",\"kind\":\"COLUMN_VALUE\",\"actual\":\"27182\",\"evaluated\":true,"
                + "\"passed\":false,\"level\":\"WARNING\",\"compared\":27182.0}]");
        when(checks.findTopByMonitorIdOrderByCheckedAtDesc(42L)).thenReturn(Optional.of(latest));
        Map<String, Object> resolved = ReflectionTestUtils.invokeMethod(esc, "withDbQueryResolvedValues", breachCtx(rules, SECRET));
        assertThat(resolved).containsEntry("resolved_metric_value", "••••").containsEntry("metric_value", "••••");
        assertThat(String.valueOf(resolved.get("resolved_rule_summary"))).doesNotContain("27182");
        assertThat(String.valueOf(resolved.get("rule_summary"))).doesNotContain("31415");

        String msg = ReflectionTestUtils.invokeMethod(esc, "monitoringMessageBody", "Bekleyen Siparişler",
                EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", breachCtx(rules, SECRET));
        assertThat(msg).contains("••••").doesNotContain("31415");
    }

    @Test
    @DisplayName("D9: postada taban bekleyen kural 'Değerlendirilemedi' yazmaz (nötr metin)")
    void baselineNotesNeutral() {
        Map<String, Object> r = rule("r1", "CHANGE", null, "—", true);
        r.put("evaluated", false);
        r.put("note", "baseline_reset");
        assertThat(EmailTemplateBuilder.dbQueryRuleState(r)).startsWith("Taban yeniden kuruluyor");
        r.put("note", "first_run");
        assertThat(EmailTemplateBuilder.dbQueryRuleState(r)).startsWith("Taban kuruluyor");
        r.put("note", "no_seasonal_baseline");
        assertThat(EmailTemplateBuilder.dbQueryRuleState(r)).startsWith("Taban kuruluyor");
        r.put("note", "column_missing");
        assertThat(EmailTemplateBuilder.dbQueryRuleState(r)).isEqualTo(DbQueryAlarmText.ruleStateText(r));

        r.put("note", "baseline_reset");
        Map<String, Object> c = breachCtx(List.of(r), null);
        assertThat(render("DBQUERY_THRESHOLD", c)).contains("Taban yeniden kuruluyor").doesNotContain("Değerlendirilemedi");
    }
}
