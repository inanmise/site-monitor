package com.sitemonitor.repository;

import com.sitemonitor.model.SystemMetricSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sistem metrik örnekleri (2026-10-13) — H2'de yerel toplama sorgusu: önek uzunluğuyla kova (dakika / 10 dakika / saat),
 * pod süzgeci (CAST'li null), toplam / ortalama / en çok sütunları, pod listesi.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SystemMetricSampleRepositoryTest {

    @Autowired SystemMetricSampleRepository repo;

    private static SystemMetricSample s(String pod, String at, double cpu, int heap, long req, long err, Long p95, Long warn) {
        SystemMetricSample x = new SystemMetricSample();
        x.setInstanceId(pod);
        x.setPodName(pod + "-ad");
        x.setSampledAt(at);
        x.setCpuProcess(cpu);
        x.setHeapUsedMb(heap);
        x.setHeapMaxMb(1000);
        x.setHttpRequests(req);
        x.setHttpErrors(err);
        x.setHttpSumMs(req * 10);
        x.setHttpP95Ms(p95);
        x.setLogWarnDelta(warn);
        x.setDbActive(2);
        x.setDbMax(10);
        x.setHttpMinutes(1);
        x.setDeltaMinutes(1);
        return x;
    }

    @Test
    @DisplayName("kova: 16 = dakika, 15 = on dakika, 13 = saat; pod süzgeci; toplamlar ve en kötü p95")
    void aggregateBuckets() {
        repo.saveAll(List.of(
                s("pod-a", "2031-01-05T08:11:00", 10, 300, 60, 1, 120L, 1L),
                s("pod-a", "2031-01-05T08:12:00", 30, 500, 40, 0, 300L, null),
                s("pod-a", "2031-01-05T08:25:00", 20, 400, 10, 0, 90L, 2L),
                s("pod-b", "2031-01-05T08:11:00", 50, 700, 5, 5, 900L, 0L),
                s("pod-a", "2031-01-05T09:01:00", 5, 200, 1, 0, 10L, 0L)));

        Map<String, Object[]> tenMin = new HashMap<>();
        for (Object[] r : repo.aggregate("2031-01-05T08:00:00", "2031-01-05T08:59:59", null, 15)) tenMin.put(r[0] + "|" + r[1], r);
        assertThat(tenMin).containsOnlyKeys("pod-a|2031-01-05T08:1", "pod-a|2031-01-05T08:2", "pod-b|2031-01-05T08:1");
        Object[] a1 = tenMin.get("pod-a|2031-01-05T08:1");
        assertThat(((Number) a1[2]).longValue()).as("örnek sayısı").isEqualTo(2);
        assertThat(((Number) a1[3]).doubleValue()).as("ort. CPU").isEqualTo(20.0);
        assertThat(((Number) a1[4]).doubleValue()).as("en çok CPU").isEqualTo(30.0);
        assertThat(((Number) a1[7]).intValue()).as("en çok heap").isEqualTo(500);
        assertThat(((Number) a1[27]).longValue()).as("istek toplamı").isEqualTo(100);
        assertThat(((Number) a1[28]).longValue()).as("hata toplamı").isEqualTo(1);
        assertThat(((Number) a1[30]).longValue()).as("en kötü dakika p95").isEqualTo(300);
        assertThat(((Number) a1[32]).longValue()).as("null delta toplama girmez").isEqualTo(1);
        assertThat(((Number) a1[37]).longValue()).as("HTTP dakikası").isEqualTo(2);
        assertThat(((Number) a1[38]).longValue()).as("artış dakikası en sonda").isEqualTo(2);

        List<Object[]> onlyB = repo.aggregate("2031-01-05T08:00:00", "2031-01-05T09:59:59", "pod-b", 13);
        assertThat(onlyB).hasSize(1);
        assertThat(onlyB.get(0)[1]).isEqualTo("2031-01-05T08");

        List<Object[]> hourly = repo.aggregate("2031-01-05T00:00:00", "2031-01-05T23:59:59", null, 13);
        assertThat(hourly).extracting(r -> r[0] + "|" + r[1])
                .containsExactly("pod-a|2031-01-05T08", "pod-b|2031-01-05T08", "pod-a|2031-01-05T09");

        SystemMetricSample marker = new SystemMetricSample();
        marker.setInstanceId("pod-b");
        marker.setSampledAt("2031-01-05T08:12:00");
        marker.setShutdownMarker(Boolean.TRUE);
        repo.save(marker);
        Map<String, Object> pods = new HashMap<>();
        Map<String, Integer> markers = new HashMap<>();
        for (Object[] r : repo.podsSince("2031-01-05T08:00:00")) {
            pods.put(String.valueOf(r[0]), r[2]);
            markers.put(String.valueOf(r[0]), ((Number) r[3]).intValue());
        }
        assertThat(pods).containsEntry("pod-a", "2031-01-05T09:01:00").containsEntry("pod-b", "2031-01-05T08:12:00");
        assertThat(markers).as("kapanış işareti").containsEntry("pod-a", 0).containsEntry("pod-b", 1);
        assertThat(repo.findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc("2031-01-05T08:12:00")).as("işaret satırı son örnek sayılmaz")
                .extracting(SystemMetricSample::getInstanceId).doesNotContain("pod-b");
        assertThat(repo.aggregate("2031-01-05T08:00:00", "2031-01-05T08:59:59", "pod-b", 16))
                .as("işaret satırı toplamlara (örnek sayısına) karışmaz").extracting(r -> r[1]).doesNotContain("2031-01-05T08:12");
        assertThat(repo.findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc("2031-01-05T09:00:00")).hasSize(1);
    }
}
