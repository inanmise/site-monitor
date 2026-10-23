package com.sitemonitor.service.watchdog;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.SystemMetricSample;
import com.sitemonitor.repository.SystemMetricSampleRepository;
import com.sitemonitor.service.metrics.SystemMetricSampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saat uyumu ve küme havuzu GERÇEK PostgreSQL'de (2026-10-23, çok pod R2b, MP-36 / MP-37c): örnekleyicinin
 * {@code SELECT CURRENT_TIMESTAMP} ölçümü (sürücü {@code timestamptz}'yi {@code Timestamp} olarak döndürür) ve kendi kendini
 * izleme yoklamasının pod başına son örnek sorgusu (türetilmiş tabloda {@code MAX(sampled_at)} ile JOIN, {@code BOOLEAN} /
 * {@code INTEGER} / {@code BIGINT} kolonlar) şema yamasıyla eklenen {@code clock_skew_ms} kolonu üzerinde çalışır.
 */
@PostgresIntegration
class WatchdogClusterFactsPostgresTest {

    @Test
    @DisplayName("örnekleyici farkı ölçer ve yazar; yoklama canlı pod'u farkı ve havuzuyla bulur")
    void skewAndPodSamplesOnPostgres() {
        SystemMetricSampler sampler = PostgresIt.app().bean(SystemMetricSampler.class);
        sampler.tick();
        assertThat(sampler.lastClockSkewMs()).as("aynı makine: fark küçük").isNotNull();
        assertThat(Math.abs(sampler.lastClockSkewMs())).isLessThan(5_000L);

        SystemMetricSampleRepository repo = PostgresIt.app().bean(SystemMetricSampleRepository.class);
        String inst = "wd-it-" + UUID.randomUUID().toString().substring(0, 8);
        SystemMetricSample s = new SystemMetricSample();
        s.setInstanceId(inst);
        s.setPodName(inst);
        s.setSampledAt(WatchdogTransition.ISO.format(Instant.now().truncatedTo(ChronoUnit.MINUTES)));
        s.setDbPending(2);
        s.setDbActive(5);
        s.setDbMax(30);
        s.setClockSkewMs(41_000L);
        repo.save(s);

        WatchdogProbes probes = PostgresIt.app().bean(WatchdogProbes.class);
        List<WatchdogProbes.PodSample> live = probes.livePods(Instant.now());
        assertThat(live).filteredOn(p -> inst.equals(p.instanceId())).singleElement().satisfies(p -> {
            assertThat(p.skewMs()).isEqualTo(41_000L);
            assertThat(p.dbPending()).isEqualTo(2);
            assertThat(p.poolSaturated()).isTrue();
        });
        WatchdogProbes.Probe clock = probes.clockSkew(Instant.now());
        assertThat(clock.result()).isEqualTo(WatchdogTransition.Result.PROBLEM);
        assertThat(clock.facts()).containsEntry("worst_pod", inst);
        repo.delete(s);
    }
}
