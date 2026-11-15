package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.PageSpeedRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı 2c (2026-11-15) — büyüme alarmının ölçüme bağlanışı ve görünür yüzleri: {@link PageSpeedCheckerService#withGrowth}
 * (referans eklenir, karar yeniden verilir), zamanlayıcının örnek süzgeci, dağıtık iş hedef parmak izinin ölçüm kipini taşıması
 * (eski ayarla ölçülmüş sonuç düşer) ve alarm postasının büyüme satırları ("önceki hafta medyanı X → şimdi Y (+Z %)").
 */
class PageSpeedGrowthWiringTest {

    private static PageSpeedCheckerService.Result ok(long bytes, int requests) {
        return new PageSpeedCheckerService.Result("OK", 200, 100, 200, 1000, bytes, requests, 0, false, false, List.of(), null,
                List.of(), null, 0, null, false, null, List.of(), null);
    }

    private static List<PageSpeedRules.GrowthSample> samples(int n, long bytes, int requests) {
        List<PageSpeedRules.GrowthSample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new PageSpeedRules.GrowthSample(bytes, requests, false));
        return out;
    }

    private static PageSpeedMonitor monitor(Integer bytesPct) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(3L);
        m.setUrl("https://hiz.example.com/");
        m.setGrowthBytesPct(bytesPct);
        return m;
    }

    @Test
    @DisplayName("withGrowth: yeterli referansla büyüme SLOW; referans okunamadıysa (null) karar yok ama kararsız; eşik yoksa aynı nesne")
    void withGrowth() {
        PageSpeedMonitor m = monitor(20);
        var grown = PageSpeedCheckerService.withGrowth(ok(200_000, 10), m, samples(5, 100_000, 10));
        assertThat(grown.status()).isEqualTo("SLOW");
        assertThat(grown.breached()).containsExactly("GROWSIZE");
        assertThat(grown.growth().medianBytes()).isEqualTo(100_000L);

        var unread = PageSpeedCheckerService.withGrowth(ok(200_000, 10), m, null);
        assertThat(unread.status()).isEqualTo("OK");
        assertThat(unread.breached()).isEmpty();
        assertThat(unread.undecided()).containsExactly("GROWSIZE");

        var plain = ok(200_000, 10);
        assertThat(PageSpeedCheckerService.withGrowth(plain, monitor(null), samples(5, 1, 1))).isSameAs(plain);
        var down = new PageSpeedCheckerService.Result("DOWN", 500, 0, 0, 0, 0, 0, 0, false, false, List.of(), "x",
                List.of(), null, 0, null, false, null, List.of(), null);
        assertThat(PageSpeedCheckerService.withGrowth(down, m, samples(5, 1, 1))).isSameAs(down);
    }

    @Test
    @DisplayName("withGrowth: başka eşiğin ihlali korunur (yeniden değerlendirme hepsini birlikte verir)")
    void otherBreachesKept() {
        PageSpeedMonitor m = monitor(20);
        m.setMaxRequests(5);
        var r = PageSpeedCheckerService.withGrowth(ok(100_000, 10), m, samples(6, 100_000, 10));
        assertThat(r.breached()).containsExactly("REQUESTS");
        assertThat(r.status()).isEqualTo("SLOW");
    }

    @Test
    @DisplayName("Örnek süzgeci: sayfası alınamayan, kısmi, başka kip, elle ve teyit ölçümü referansa girmez; bant işareti okunur")
    void sampleFilter() {
        List<Object[]> rows = new ArrayList<>();
        // [totalBytes, requestCount, breachedMetrics, ok, capped, bytesTruncated, timeTruncated, extended, manual, recheck]
        rows.add(new Object[]{100L, 10, null, true, false, false, null, null, null, null});
        rows.add(new Object[]{200L, 20, "SIZE,SIZEBAND", true, false, false, null, null, null, null});
        rows.add(new Object[]{300L, 30, null, false, false, false, null, null, null, null});
        rows.add(new Object[]{400L, 40, null, true, true, false, null, null, null, null});
        rows.add(new Object[]{500L, 50, null, true, false, true, null, null, null, null});
        rows.add(new Object[]{600L, 60, null, true, false, false, true, null, null, null});
        rows.add(new Object[]{700L, 70, null, true, false, false, null, true, null, null});
        rows.add(new Object[]{800L, 80, null, true, false, false, null, null, true, null});
        rows.add(new Object[]{900L, 90, null, true, false, false, null, null, null, true});
        rows.add(new Object[]{1000L, 1});                       // bozuk satır atlanır
        var classic = SchedulerService.pageSpeedGrowthSamplesOf(rows, false);
        assertThat(classic).extracting(PageSpeedRules.GrowthSample::totalBytes).containsExactly(100L, 200L);
        assertThat(classic.get(1).bandBreach()).isTrue();
        assertThat(SchedulerService.pageSpeedGrowthSamplesOf(rows, true))
                .extracting(PageSpeedRules.GrowthSample::totalBytes).containsExactly(700L);
        assertThat(SchedulerService.pageSpeedGrowthSamplesOf(null, false)).isEmpty();
    }

    @Test
    @DisplayName("Dağıtık iş hedefi ölçüm kipini taşır: kip değişince eski ayarla ölçülmüş sonuç uyuşmaz (düşer)")
    void checkTargetCarriesMode() {
        PageSpeedMonitor m = monitor(null);
        String classic = SchedulerService.pageSpeedCheckTarget(m);
        assertThat(classic).isEqualTo("https://hiz.example.com/");   // mevcut izleme: parmak izi bugünküyle aynı
        m.setExtendedMeasurement(true);
        assertThat(SchedulerService.pageSpeedCheckTarget(m)).isEqualTo(classic + "|ext").isNotEqualTo(classic);
    }

    @Test
    @DisplayName("Alarm postası: büyüme satırı medyan → şimdi (+yüzde), bant üst ucu, referans yoksa 'karşılaştırılamadı'")
    void mailRows() {
        var rows = EmailTemplateBuilder.pageSpeedThresholdRows(
                "GROWSIZE\t20|1000\t1400\t1\t0\nGROWREQ\t30|\t14\t0\t0\nSIZEBAND\t900|1100|up\t1400\t1\t0\nSIZEBAND\t||up\t1400\t0\t0");
        assertThat(rows).hasSize(4);
        assertThat(EmailTemplateBuilder.psMeasuredText(rows.get(0))).isEqualTo("önceki hafta medyanı 1000 KB → şimdi 1400 KB (+40 %)");
        assertThat(EmailTemplateBuilder.psThresholdText(rows.get(0))).contains("+20 %");
        assertThat(EmailTemplateBuilder.psStateText(rows.get(0))).isEqualTo("Aşıldı");
        assertThat(EmailTemplateBuilder.psStateText(rows.get(1))).startsWith("Karşılaştırılamadı");
        assertThat(EmailTemplateBuilder.psMeasuredText(rows.get(1))).isEqualTo("şimdi 14 istek");
        assertThat(EmailTemplateBuilder.psThresholdText(rows.get(2))).isEqualTo("≤ 1100 KB");
        assertThat(EmailTemplateBuilder.psStateText(rows.get(2))).isEqualTo("Bandın üstünde");
        assertThat(EmailTemplateBuilder.psStateText(rows.get(3))).contains("bant kuruluyor");
    }
}
