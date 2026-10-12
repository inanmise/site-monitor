package com.sitemonitor.service.watchdog;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kendi kendini izleme (2026-10-11) GERÇEK PostgreSQL'de: her yoklamanın SQL'i gerçek şemada çalışır (hiçbiri
 * "ölçülemedi" olmaz), tur durumu {@code watchdog_state}'e yazar, dış uç gövdesi bu durumdan okunur.
 */
@PostgresIntegration
class WatchdogPostgresTest {

    @Test
    @DisplayName("yoklamalar gerçek şemada hatasız; değerlendirme durumu kalıcı yazar")
    void probesAndStateOnRealSchema() {
        WatchdogProbes probes = PostgresIt.app().bean(WatchdogProbes.class);
        Instant now = Instant.now();
        List<WatchdogProbes.Probe> all = probes.runAll(now);
        assertThat(all).hasSize(WatchdogCheck.values().length);
        assertThat(all).allSatisfy(p -> assertThat(p.facts().get("reason")).as(p.check().key()).isNotEqualTo("probe_failed"));

        WatchdogService svc = PostgresIt.app().bean(WatchdogService.class);
        svc.evaluate(now);
        Map<String, Object> pub = svc.publicStatus(now.plusSeconds(1));
        assertThat(pub.get("status")).isIn("UP", "DEGRADED", "DOWN");
        assertThat(PostgresIt.app().jdbc().queryForObject("SELECT COUNT(*) FROM watchdog_state", Integer.class))
                .isEqualTo(WatchdogCheck.values().length);
    }
}
