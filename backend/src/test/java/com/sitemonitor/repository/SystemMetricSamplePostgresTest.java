package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.SystemMetricSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sistem metrik toplaması GERÇEK PostgreSQL'de (2026-10-13): türetilmiş tabloda {@code SUBSTRING(…, 1, :len) AS bucket},
 * dışta o sütuna göre GROUP BY, CAST'li null pod süzgeci, sondaki HTTP / artış dakikası sütunları ve JPQL CASE ile kapanış
 * işareti; AVG / SUM sayısal tipleri (numeric) Number olarak okunur.
 */
@PostgresIntegration
class SystemMetricSamplePostgresTest {

    @Test
    @DisplayName("on dakikalık kova + pod süzgeci + null süzgeç PostgreSQL'de çalışır")
    void aggregateOnPostgres() {
        SystemMetricSampleRepository repo = PostgresIt.app().bean(SystemMetricSampleRepository.class);
        String pod = "sms-it-" + UUID.randomUUID().toString().substring(0, 8);
        SystemMetricSample a = new SystemMetricSample();
        a.setInstanceId(pod);
        a.setSampledAt("2032-02-03T10:11:00");
        a.setCpuProcess(12.5);
        a.setHttpRequests(7L);
        a.setHttpErrors(1L);
        a.setHttpMinutes(1);
        a.setDeltaMinutes(1);
        SystemMetricSample b = new SystemMetricSample();
        b.setInstanceId(pod);
        b.setSampledAt("2032-02-03T10:14:00");
        b.setCpuProcess(7.5);
        b.setHttpRequests(3L);
        b.setHttpMinutes(3);
        b.setDeltaMinutes(3);
        SystemMetricSample stop = new SystemMetricSample();
        stop.setInstanceId(pod);
        stop.setSampledAt("2032-02-03T10:15:00");
        stop.setShutdownMarker(Boolean.TRUE);
        repo.saveAll(List.of(a, b, stop));

        List<Object[]> rows = repo.aggregate("2032-02-03T10:00:00", "2032-02-03T10:59:59", pod, 15);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo("2032-02-03T10:1");
        assertThat(((Number) rows.get(0)[3]).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) rows.get(0)[27]).longValue()).isEqualTo(10);
        assertThat(((Number) rows.get(0)[37]).longValue()).as("HTTP dakikası").isEqualTo(4);
        assertThat(((Number) rows.get(0)[38]).longValue()).as("artış dakikası").isEqualTo(4);
        assertThat(repo.podsSince("2032-02-03T10:00:00")).filteredOn(r -> pod.equals(r[0]))
                .singleElement().satisfies(r -> assertThat(((Number) r[3]).intValue()).as("kapanış işareti").isEqualTo(1));
        assertThat(repo.aggregate("2032-02-03T10:00:00", "2032-02-03T10:59:59", null, 16))
                .extracting(r -> String.valueOf(r[0])).contains(pod);
    }
}
