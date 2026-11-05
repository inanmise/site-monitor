package com.sitemonitor.service.activity;

import com.sitemonitor.service.activity.ActivityTimelineService.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActivityTimelineService#compute} — saf hesap (2026-11-05, aktivite zenginleştirmesi D): durum eşlemesi, kesinti
 * (ilk down → ilk up; warn / unknown bitirmez), sürüyor, MTTR / en uzun, erişilebilirlik yalnız zamanlanmış kontrollerden,
 * bilinmeyen asla %100 değil, alarm bağlama, "veri yok" boşluğu, 500 dönem tavanı ve seri kovaları.
 */
class ActivityTimelineComputeTest {

    private static final Instant FROM = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private static long seq = 1;

    private static Row r(String hhmm, String status) {
        return new Row(seq++, "2026-11-05T" + hhmm + ":00", "SCHEDULED_CHECK", status, 100L, null, null, null);
    }

    private static Row r(String hhmm, String status, String reason) {
        return new Row(seq++, "2026-11-05T" + hhmm + ":00", "SCHEDULED_CHECK", status, null, reason, null, null);
    }

    private static Row manual(String hhmm, String status) {
        return new Row(seq++, "2026-11-05T" + hhmm + ":00", "MANUAL_CHECK", status, 100L, null, null, null);
    }

    private static Row alert(String hhmm, long alertId) {
        return new Row(seq++, "2026-11-05T" + hhmm + ":00", "ALERT_TRIGGERED", "ERROR", null, null, null, alertId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    @DisplayName("kesinti: ilk down → ilk up; araya giren warn / unknown bitirmez; neden en sık olan; alarm bağlanır; MTTR")
    void outagesAndMttr() {
        List<Row> rows = List.of(
                r("01:00", "SUCCESS"),
                r("01:05", "ERROR", "DNS_NXDOMAIN"),
                alert("01:06", 77L),
                r("01:10", "UNKNOWN"),
                r("01:15", "TIMEOUT", "READ_TIMEOUT"),
                r("01:20", "ERROR", "DNS_NXDOMAIN"),
                r("01:25", "WARNING"),
                r("01:30", "SUCCESS"),          // kesinti 01:05 → 01:30 = 1500 sn
                r("02:00", "ERROR", "CONNECT_REFUSED"),
                r("02:10", "SUCCESS"));         // kesinti 02:00 → 02:10 = 600 sn
        Map<String, Object> m = ActivityTimelineService.compute(rows, FROM, TO, NOW, false);
        List<Map<String, Object>> outages = list(m, "outages");
        assertThat(outages).hasSize(2);
        Map<String, Object> o1 = outages.get(0);
        assertThat(o1.get("from")).isEqualTo("2026-11-05T01:05:00Z");
        assertThat(o1.get("to")).isEqualTo("2026-11-05T01:30:00Z");
        assertThat(o1.get("duration_s")).isEqualTo(1500L);
        assertThat(o1.get("checks")).isEqualTo(5);
        assertThat(o1.get("reason")).isEqualTo("DNS_NXDOMAIN");
        assertThat(o1.get("alert_event_id")).isEqualTo(77L);
        assertThat(o1.get("ongoing")).isEqualTo(false);
        assertThat(outages.get(1).get("alert_event_id")).isNull();
        assertThat(m.get("outage_count")).isEqualTo(2);
        assertThat(m.get("mttr_s")).isEqualTo(1050L);
        assertThat(m.get("longest_outage_s")).isEqualTo(1500L);
        assertThat(m.get("checks")).as("alarm satırı kontrol sayılmaz").isEqualTo(9);
    }

    @Test
    @DisplayName("sürüyor: to = null, süre pencere sonuna (geleceğe değil şimdiye) dek; MTTR yalnız kapanmışlardan (null)")
    void ongoingOutage() {
        Instant to = Instant.parse("2026-11-06T00:00:00Z");      // pencere sonu gelecekte
        Map<String, Object> m = ActivityTimelineService.compute(List.of(r("11:00", "SUCCESS"), r("11:30", "ERROR", "DNS_NXDOMAIN")),
                FROM, to, NOW, false);
        Map<String, Object> o = list(m, "outages").get(0);
        assertThat(o.get("to")).isNull();
        assertThat(o.get("ongoing")).isEqualTo(true);
        assertThat(o.get("duration_s")).isEqualTo(1800L);
        assertThat(m.get("mttr_s")).isNull();
        assertThat(m.get("longest_outage_s")).isEqualTo(1800L);
    }

    @Test
    @DisplayName("erişilebilirlik: yalnız zamanlanmış; (up + warn) ÷ ölçülen; healthy = up ÷ ölçülen; unknown dışarıda")
    void availability() {
        List<Row> rows = new ArrayList<>(List.of(r("01:00", "SUCCESS"), r("01:05", "SUCCESS"), r("01:10", "WARNING"),
                r("01:15", "ERROR"), r("01:20", "UNKNOWN"), manual("01:25", "ERROR"), manual("01:26", "ERROR")));
        Map<String, Object> m = ActivityTimelineService.compute(rows, FROM, TO, NOW, false);
        assertThat(m.get("scheduled_checks")).isEqualTo(5L);
        assertThat(m.get("checks")).isEqualTo(7);
        assertThat(m.get("availability_pct")).isEqualTo(75.0);
        assertThat(m.get("healthy_pct")).isEqualTo(50.0);
        assertThat(m.get("counts")).isEqualTo(Map.of("up", 2L, "warn", 1L, "down", 1L, "unknown", 1L));
    }

    @Test
    @DisplayName("bilinmeyen asla yeşil: yalnız UNKNOWN / yalnız elle kontrol / hiç satır → oranlar null")
    void unknownNeverGreen() {
        assertThat(ActivityTimelineService.compute(List.of(r("01:00", "UNKNOWN")), FROM, TO, NOW, false).get("availability_pct")).isNull();
        assertThat(ActivityTimelineService.compute(List.of(manual("01:00", "SUCCESS")), FROM, TO, NOW, false).get("availability_pct")).isNull();
        Map<String, Object> empty = ActivityTimelineService.compute(List.of(), FROM, TO, NOW, false);
        assertThat(empty.get("availability_pct")).isNull();
        assertThat(empty.get("healthy_pct")).isNull();
        assertThat(empty.get("mttr_s")).isNull();
        assertThat(empty.get("longest_outage_s")).isNull();
        assertThat(list(empty, "periods")).isEmpty();
        assertThat(list(empty, "series")).allMatch(p -> p.get("ms_avg") == null && ((Number) p.get("checks")).longValue() == 0);
    }

    @Test
    @DisplayName("dönemler: ardışık aynı durum birleşir; uzun boşluk 'veri yok' (unknown, NO_DATA) dönemi olur")
    void periodsAndGap() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(r(String.format("01:%02d", i * 5), "SUCCESS"));           // 01:00…01:25
        rows.add(r("01:30", "ERROR", "DNS_NXDOMAIN"));
        rows.add(r("01:35", "ERROR", "DNS_NXDOMAIN"));
        rows.add(r("04:00", "SUCCESS"));                                                                  // 2 sa 25 dk boşluk
        List<Map<String, Object>> p = list(ActivityTimelineService.compute(rows, FROM, TO, NOW, false), "periods");
        assertThat(p).extracting(x -> x.get("state")).containsExactly("up", "down", "unknown", "up");
        assertThat(p.get(0).get("checks")).isEqualTo(6);
        assertThat(p.get(1).get("reason")).isEqualTo("DNS_NXDOMAIN");
        assertThat(p.get(1).get("from")).isEqualTo("2026-11-05T01:30:00Z");
        assertThat(p.get(1).get("to")).isEqualTo("2026-11-05T01:40:00Z");      // son satır + medyan aralık (5 dk)
        assertThat(p.get(2).get("reason")).isEqualTo("NO_DATA");
        assertThat(p.get(2).get("to")).isEqualTo("2026-11-05T04:00:00Z");
        assertThat(p.get(0).get("reason")).as("up dönemine neden yazılmaz").isNull();
    }

    @Test
    @DisplayName("500'den çok dönem → eşit dilimlerde birleşir (en kötü durum kazanır, kısa kesinti kaybolmaz)")
    void periodMerge() {
        List<Row> rows = new ArrayList<>();
        Instant t = FROM;
        for (int i = 0; i < 1500; i++) {
            String st = (i % 2 == 0) ? "SUCCESS" : (i == 701 ? "ERROR" : "WARNING");
            rows.add(new Row(seq++, ActivityFilter.iso(t), "SCHEDULED_CHECK", st, 10L, null, null, null));
            t = t.plusSeconds(28);
        }
        Map<String, Object> m = ActivityTimelineService.compute(rows, FROM, TO, NOW, false);
        List<Map<String, Object>> p = list(m, "periods");
        assertThat(m.get("periods_merged")).isEqualTo(true);
        assertThat(p.size()).isLessThanOrEqualTo(500).isGreaterThan(0);
        assertThat(p).anyMatch(x -> "down".equals(x.get("state")));
        assertThat(p.stream().mapToInt(x -> ((Number) x.get("checks")).intValue()).sum()).isEqualTo(1500);
    }

    @Test
    @DisplayName("seri: adım ≤ 200 kova seçer; ms yalnız up / warn'dan; fail = down sayısı")
    void series() {
        assertThat(ActivityTimelineService.stepFor(FROM, FROM.plusSeconds(86_400))).isEqualTo(600);
        assertThat(ActivityTimelineService.stepFor(FROM, FROM.plusSeconds(7 * 86_400))).isEqualTo(3600);
        assertThat(ActivityTimelineService.stepFor(FROM, FROM.plusSeconds(90L * 86_400))).isEqualTo(43_200);
        List<Row> rows = List.of(new Row(1, "2026-11-05T00:01:00", "SCHEDULED_CHECK", "SUCCESS", 100L, null, null, null),
                new Row(2, "2026-11-05T00:02:00", "SCHEDULED_CHECK", "WARNING", 300L, null, null, null),
                new Row(3, "2026-11-05T00:03:00", "SCHEDULED_CHECK", "ERROR", 9000L, null, null, null));
        Map<String, Object> m = ActivityTimelineService.compute(rows, FROM, TO, NOW, false);
        List<Map<String, Object>> s = list(m, "series");
        assertThat(m.get("series_step_s")).isEqualTo(300L);
        assertThat(s.size()).isLessThanOrEqualTo(201);
        Map<String, Object> first = s.get(0);
        assertThat(first.get("t")).isEqualTo("2026-11-05T00:00:00Z");
        assertThat(first.get("checks")).isEqualTo(3L);
        assertThat(first.get("ms_avg")).isEqualTo(200L);
        assertThat(first.get("ms_max")).isEqualTo(300L);
        assertThat(first.get("fail")).isEqualTo(1L);
        assertThat(s.get(1).get("ms_avg")).as("boş kova = boşluk (null), sıfır değil").isNull();
    }

    @Test
    @DisplayName("kesilmiş sonuç: truncated + covered_from; kesinti pencere başında açıksa open_start")
    void truncatedAndOpenStart() {
        Map<String, Object> m = ActivityTimelineService.compute(List.of(r("03:00", "ERROR", "DNS_NXDOMAIN"), r("03:05", "SUCCESS")),
                FROM, TO, NOW, true);
        assertThat(m.get("truncated")).isEqualTo(true);
        assertThat(m.get("covered_from")).isEqualTo("2026-11-05T03:00:00Z");
        assertThat(list(m, "outages").get(0).get("open_start")).isEqualTo(true);
    }
}
