package com.sitemonitor.service.metrics;

import com.sitemonitor.model.SystemMetricSample;
import com.sitemonitor.repository.SystemMetricSampleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Metrik okumaları (2026-10-13): aralık → kova eşlemesi, bilinmeyen aralık 6 sa, satır → nokta (PostgreSQL numeric dahil),
 * boş / null alanlar "bilinmiyor" (0 değil), canlı pod eşiği, son saat toplamları.
 */
class SystemMetricsQueryServiceTest {

    private final SystemMetricSampleRepository repo = mock(SystemMetricSampleRepository.class);
    private final SystemMetricsQueryService svc = new SystemMetricsQueryService(repo);

    /** 39 sütunluk boş satır (pod + kova + örnek sayısı; artış dakikası = örnek sayısı — her örnek bir dakika). */
    private static Object[] row(String pod, String bucket, long samples) {
        Object[] r = new Object[39];
        r[0] = pod;
        r[1] = bucket;
        r[2] = samples;
        r[38] = samples;
        return r;
    }

    @Test
    @DisplayName("aralıklar: 1h dakika, 6h/24h on dakika, 7d saat; bilinmeyen → 6h; pencere uçları UTC dakika")
    void rangeMapping() {
        assertThat(SystemMetricsQueryService.range("1h").prefixLen()).isEqualTo(16);
        assertThat(SystemMetricsQueryService.range("24h").prefixLen()).isEqualTo(15);
        assertThat(SystemMetricsQueryService.range("7d").prefixLen()).isEqualTo(13);
        assertThat(SystemMetricsQueryService.range("x").key()).isEqualTo("6h");
        assertThat(SystemMetricsQueryService.range(null).key()).isEqualTo("6h");

        when(repo.aggregate(anyString(), anyString(), isNull(), eq(15))).thenReturn(List.of());
        when(repo.podsSince(anyString())).thenReturn(List.of());
        Map<String, Object> out = svc.series("24h", "  ", Instant.parse("2031-04-02T12:00:30Z"));
        assertThat(out).containsEntry("range", "24h").containsEntry("bucket", "10m")
                .as("başlangıç kova sınırına hizalı — ilk kova yarım kalmaz")
                .containsEntry("from", "2031-04-01T12:00:00").containsEntry("to", "2031-04-02T12:00:30");
        assertThat(out.get("instance")).as("boş pod = tümü").isNull();
        assertThat((List<?>) out.get("points")).isEmpty();
        verify(repo).aggregate("2031-04-01T12:00:00", "2031-04-02T12:00:30", null, 15);
        when(repo.aggregate(anyString(), anyString(), isNull(), eq(13))).thenReturn(List.of());
        assertThat(svc.series("7d", null, Instant.parse("2031-04-02T12:34:56Z"))).containsEntry("from", "2031-03-26T12:00:00");
    }

    @Test
    @DisplayName("nokta: kova başlangıcı, yüzdeler, PostgreSQL BigDecimal, eksik alan null (sıfır değil)")
    void pointMapping() {
        Object[] r = row("pod-a", "2031-04-02T12:1", 10);
        r[3] = new BigDecimal("12.345");   // AVG cpu
        r[7] = 600;                          // max heap used
        r[8] = 1000;                         // heap max
        r[15] = 8;                           // db active max
        r[18] = 10;                          // db max
        r[27] = new BigDecimal("200");       // req
        r[28] = new BigDecimal("5");         // err
        r[29] = new BigDecimal("4000");      // sum ms
        r[12] = 35L;                         // GC ms
        r[32] = 5L;                          // WARN
        r[34] = 30L;
        r[35] = 10L;
        r[37] = 10L;                         // HTTP dakikası
        Map<String, Object> p = SystemMetricsQueryService.point(r, 15);
        assertThat(p).as("dakika başına oran — yarım kova düşük görünmez").containsEntry("gc_pause_ms_pm", 3.5)
                .containsEntry("log_warn_pm", 0.5).containsEntry("delta_minutes", 10L);
        assertThat(p.get("log_error_pm")).isNull();
        assertThat(p).containsEntry("t", "2031-04-02T12:10:00").containsEntry("samples", 10L)
                .containsEntry("cpu_process_avg", 12.3).containsEntry("heap_pct_max", 60.0)
                .containsEntry("db_usage_pct_max", 80.0).containsEntry("http_requests", 200L)
                .containsEntry("http_error_pct", 2.5).containsEntry("http_avg_ms", 20L)
                .containsEntry("http_rpm", 20.0).containsEntry("cache_hit_pct", 75.0);
        assertThat(p).containsEntry("gc_pause_ms", 35L).containsEntry("log_warn", 5L);
        assertThat(p.get("log_error")).as("okunmayan sayaç null kalır").isNull();
        assertThat(p.get("exec_completed_pm")).isNull();

        Map<String, Object> empty = SystemMetricsQueryService.point(row("pod-a", "2031-04-02T12", 1), 13);
        assertThat(empty.get("t")).isEqualTo("2031-04-02T12:00:00");
        assertThat(empty.get("http_error_pct")).as("istek verisi yok → bilinmiyor").isNull();
        assertThat(empty.get("http_rpm")).isNull();
        assertThat(empty.get("heap_pct_max")).isNull();
        assertThat(empty.get("cache_hit_pct")).isNull();

        Object[] withMinutes = r.clone();
        withMinutes[37] = 4L;                // 10 örneğe yalnız 4 HTTP dakikası katlandı
        assertThat(SystemMetricsQueryService.point(withMinutes, 15)).as("istek/dk HTTP dakikasına bölünür").containsEntry("http_rpm", 50.0);

        // Yeniden başlatma: 2 satır ama biri taban (artışsız) → payda 1 dakika; geciken tick: 1 satır 3 dakikalık artış
        Object[] restart = r.clone();
        restart[2] = 2L;
        restart[38] = 1L;
        assertThat(SystemMetricsQueryService.point(restart, 15)).as("taban satırı dakika sayılmaz").containsEntry("gc_pause_ms_pm", 35.0);
        Object[] late = r.clone();
        late[2] = 1L;
        late[38] = 3L;
        assertThat(SystemMetricsQueryService.point(late, 15)).as("geciken tick sıçratmaz").containsEntry("gc_pause_ms_pm", 11.667);
        // Saatlik kova, 2 ERROR → 0,033/dk (bir ondalık 0 gösterirdi — sahte sıfır)
        Object[] hourly = row("pod-a", "2031-04-02T12", 60);
        hourly[33] = 2L;
        assertThat(SystemMetricsQueryService.point(hourly, 13)).containsEntry("log_error_pm", 0.033);
        Object[] baselineOnly = r.clone();
        baselineOnly[38] = null;
        assertThat(SystemMetricsQueryService.point(baselineOnly, 15).get("gc_pause_ms_pm")).as("yalnız taban → bilinmiyor").isNull();

        Object[] zero = row("pod-a", "2031-04-02T12:10", 1);
        zero[27] = 0L;
        assertThat(SystemMetricsQueryService.point(zero, 16)).containsEntry("http_error_pct", 0.0)
                .containsEntry("t", "2031-04-02T12:10:00");
        assertThat(SystemMetricsQueryService.point(zero, 16).get("http_avg_ms")).isNull();
    }

    @Test
    @DisplayName("aynı pod adında iki süreç (konteyner yeniden başladı) kimlik ekiyle ayrılır; tekil ad dokunulmaz")
    void duplicatePodNamesAreDisambiguated() {
        when(repo.podsSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"web-1-aaaa1111", "web-1", "2031-04-02T11:00:00"},
                new Object[]{"web-1-bbbb2222", "web-1", "2031-04-02T11:59:00"},
                new Object[]{"web-2-cccc3333", "web-2", "2031-04-02T11:59:00"}));
        when(repo.aggregate(anyString(), anyString(), isNull(), eq(15))).thenReturn(List.of());
        @SuppressWarnings("unchecked") List<Map<String, Object>> pods =
                (List<Map<String, Object>>) svc.series("6h", null, Instant.parse("2031-04-02T12:00:30Z")).get("pods");
        assertThat(pods).as("canlılar önce (eski 4 sütunlu satır: ilk örnek yok → son örnek, ad), susan eski süreç sonda")
                .extracting(p -> p.get("name")).containsExactly("web-1 · bbbb2222", "web-2", "web-1 · aaaa1111");
        assertThat(pods.get(0)).containsEntry("first_seen", null);
        assertThat(SystemMetricsQueryService.suffix("tek")).isEqualTo("tek");
        assertThat(SystemMetricsQueryService.suffix("son-")).isEqualTo("son-");
    }

    @Test
    @DisplayName("pod sırası: canlı → sessiz → durdu; aynı durumda ilk örneği en yeni (en son açılan) önce, ilk örneği bilinmeyen sonra; özet ve seri aynı sırada")
    void podOrderLiveFirstNewestFirst() {
        Instant now = Instant.parse("2031-04-02T12:00:30Z");
        when(repo.podsSince(anyString())).thenReturn(List.<Object[]>of(
                // [kimlik, ad, son örnek, işaret, ilk örnek]
                new Object[]{"stop-1", "pod-stop", "2031-04-02T11:59:00", 1, "2031-04-02T11:58:00"},
                new Object[]{"old-1", "pod-old", "2031-04-02T11:59:00", 0, "2031-03-30T08:00:00"},
                new Object[]{"silent-1", "pod-silent", "2031-04-02T11:40:00", 0, "2031-04-02T11:30:00"},
                new Object[]{"nofirst-1", "pod-a-nofirst", "2031-04-02T11:59:00", 0, null},
                new Object[]{"new-1", "pod-new", "2031-04-02T11:59:00", 0, "2031-04-02T11:20:00"},
                new Object[]{"silent-2", "pod-silent-old", "2031-04-02T11:50:00", 0, "2031-04-01T12:30:00"}));
        when(repo.findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc(anyString())).thenReturn(List.of());
        when(repo.aggregate(anyString(), anyString(), isNull(), anyInt())).thenReturn(List.of());

        @SuppressWarnings("unchecked") List<Map<String, Object>> ov = (List<Map<String, Object>>) svc.overview(now).get("pods");
        assertThat(ov).extracting(p -> p.get("id"))
                .as("canlı: en yeni süreç (11:20) eski süreçten (30 Mart) önce, ilk örneği bilinmeyen canlıların sonunda; "
                        + "sessiz: en son açılan önce; durmuş pod en sonda — en yeni olsa da")
                .containsExactly("new-1", "old-1", "nofirst-1", "silent-1", "silent-2", "stop-1");
        assertThat(ov.get(0)).containsEntry("first_seen", "2031-04-02T11:20:00").containsEntry("state", "live");
        assertThat(ov.get(5)).containsEntry("state", "stopped");

        @SuppressWarnings("unchecked") List<Map<String, Object>> se =
                (List<Map<String, Object>>) svc.series("6h", null, now).get("pods");
        assertThat(se).as("seri yanıtının podları da aynı sırada").extracting(p -> p.get("id"))
                .containsExactly("new-1", "old-1", "nofirst-1", "silent-1", "silent-2", "stop-1");

        // Aynı durum + aynı ilk örnek: son örneği yeni önce; o da eşitse ad
        Map<String, Object> x = new java.util.HashMap<>(Map.of("id", "x", "name", "b", "state", "live", "first_seen", "2031-04-02T11:00:00", "last_seen", "2031-04-02T11:59:00"));
        Map<String, Object> y = new java.util.HashMap<>(Map.of("id", "y", "name", "a", "state", "live", "first_seen", "2031-04-02T11:00:00", "last_seen", "2031-04-02T11:58:00"));
        Map<String, Object> z = new java.util.HashMap<>(Map.of("id", "z", "name", "a", "state", "live", "first_seen", "2031-04-02T11:00:00", "last_seen", "2031-04-02T11:59:00"));
        List<Map<String, Object>> tie = new java.util.ArrayList<>(List.of(y, x, z));
        tie.sort(SystemMetricsQueryService.POD_ORDER);
        assertThat(tie).extracting(p -> p.get("id")).containsExactly("z", "x", "y");
    }

    @Test
    @DisplayName("özet: canlı pod eşiği 3 dk, son örnek eşlenir, son saat toplamı; veri yoksa null")
    void overview() {
        Instant now = Instant.parse("2031-04-02T12:00:30Z");
        when(repo.podsSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"b-1", "pod-b", "2031-04-02T11:50:00", 0},
                new Object[]{"a-1", null, "2031-04-02T11:58:00", 0},
                new Object[]{"c-1", "pod-c", "2031-04-02T11:55:00", 1},
                new Object[]{"e-1", "pod-e", "2031-04-02T09:00:00", 0},
                new Object[]{"d-1", "pod-d", "2031-03-30T09:00:00", 1}));
        SystemMetricSample s = new SystemMetricSample();
        s.setInstanceId("a-1");
        s.setSampledAt("2031-04-02T11:58:00");
        s.setHeapUsedMb(512);
        s.setHeapMaxMb(1024);
        s.setDbActive(3);
        s.setDbMax(12);
        when(repo.findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc("2031-04-02T11:57:30")).thenReturn(List.of(s));
        Object[] m1 = row("a-1", "2031-04-02T11:58", 1);
        m1[27] = 100L;
        m1[28] = 2L;
        m1[29] = 1000L;
        m1[30] = 40L;
        m1[32] = 1L;
        Object[] m2 = row("b-1", "2031-04-02T11:50", 1);
        m2[27] = 50L;
        m2[29] = 500L;
        m2[30] = 90L;
        when(repo.aggregate(eq("2031-04-02T11:00:30"), eq("2031-04-02T12:00:30"), isNull(), eq(16)))
                .thenReturn(List.of(m1, m2));

        Map<String, Object> out = svc.overview(now);
        @SuppressWarnings("unchecked") List<Map<String, Object>> pods = (List<Map<String, Object>>) out.get("pods");
        assertThat(pods).as("24 saatten eski pod listede yok; canlı → sessiz (son örneği yeni önce) → durdu")
                .extracting(p -> p.get("name")).containsExactly("a-1", "pod-b", "pod-e", "pod-c");
        assertThat(pods.get(0)).containsEntry("live", true).containsEntry("state", "live");
        assertThat(pods.get(1)).containsEntry("live", false).containsEntry("state", "silent").containsEntry("latest", null);
        assertThat(pods.get(2)).as("işaretsiz 3 saattir susan pod (takılı / çökmüş) sessiz kalır").containsEntry("state", "silent");
        assertThat(pods.get(3)).as("kapanış işareti: 5 dk önce dağıtımla durdu — uyarı değil").containsEntry("state", "stopped");
        assertThat(pods.get(0)).doesNotContainKey("marker");
        assertThat(out).containsEntry("enabled", true);
        assertThat(out.get("served_uptime_s")).as("sunan podun çalışma süresi").isInstanceOf(Long.class);
        @SuppressWarnings("unchecked") Map<String, Object> latest = (Map<String, Object>) pods.get(0).get("latest");
        assertThat(latest).containsEntry("heap_pct", 50).containsEntry("db_usage_pct", 25);
        assertThat(out).containsEntry("pods_live", 1L);
        @SuppressWarnings("unchecked") Map<String, Object> hour = (Map<String, Object>) out.get("last_hour");
        assertThat(hour).containsEntry("http_requests", 150L).containsEntry("http_errors", 2L)
                .containsEntry("http_error_pct", 1.3).containsEntry("http_avg_ms", 10L)
                .containsEntry("http_p95_ms", 90L).containsEntry("log_warn", 1L);

        when(repo.aggregate(anyString(), anyString(), isNull(), eq(16))).thenReturn(List.of());
        when(repo.podsSince(anyString())).thenReturn(List.of());
        @SuppressWarnings("unchecked") Map<String, Object> none = (Map<String, Object>) svc.overview(now).get("last_hour");
        assertThat(none.get("http_requests")).isNull();
        assertThat(none.get("http_error_pct")).isNull();
        assertThat(none.get("log_error")).isNull();
        assertThat(none.get("caller_runs")).as("örnek yokken 0 uydurulmaz").isNull();
        assertThat(none.get("gc_pause_ms")).isNull();
        assertThat(none).containsEntry("samples", false);
    }
}
