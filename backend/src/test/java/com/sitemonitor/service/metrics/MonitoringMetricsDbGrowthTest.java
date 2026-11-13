package com.sitemonitor.service.metrics;

import com.sitemonitor.service.dbgrowth.DbGrowthService;
import com.sitemonitor.service.dbgrowth.DbGrowthService.CapacityFacts;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Families;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Veritabanı büyümesi ölçümleri (2026-11-13): KÜME; PostgreSQL dışında / servis yokken seri YOK; bilinmeyen dolma günü yazılmaz. */
class MonitoringMetricsDbGrowthTest {

    @Test
    @DisplayName("servis yok ya da PostgreSQL değil → null (kaynak satırı yazılmaz, sahte 0 yok)")
    void absent() {
        assertThat(MonitoringMetricsExporter.dbGrowthFamilies(null)).isNull();
        DbGrowthService g = mock(DbGrowthService.class);
        when(g.capacityFacts()).thenReturn(new CapacityFacts(false, 0, null, null, null, false, 0, null));
        assertThat(MonitoringMetricsExporter.dbGrowthFamilies(g)).isNull();
    }

    @Test
    @DisplayName("boyut her zaman, dolma günü yalnız biliniyorsa; ikisi de KÜME kapsamlı ve etiketsiz")
    void values() {
        DbGrowthService g = mock(DbGrowthService.class);
        when(g.capacityFacts()).thenReturn(new CapacityFacts(true, 100L << 30, 42L << 30, 42.0, 120, true, 14, "2026-11-13"));
        Families f = MonitoringMetricsExporter.dbGrowthFamilies(g);
        assertThat(f.get(MonitoringMetricNames.DB_SIZE_BYTES)).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo((double) (42L << 30)));
        assertThat(f.get(MonitoringMetricNames.DB_DAYS_UNTIL_FULL)).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo(120.0));
        when(g.capacityFacts()).thenReturn(new CapacityFacts(true, 0, 42L << 30, null, null, false, 3, "2026-11-13"));
        Families none = MonitoringMetricsExporter.dbGrowthFamilies(g);
        assertThat(none.get(MonitoringMetricNames.DB_SIZE_BYTES)).hasSize(1);
        assertThat(none.get(MonitoringMetricNames.DB_DAYS_UNTIL_FULL)).isEmpty();
        assertThat(MonitoringMetricNames.def(MonitoringMetricNames.DB_SIZE_BYTES).scope()).isEqualTo(MonitoringMetricNames.Scope.CLUSTER);
        assertThat(MonitoringMetricNames.def(MonitoringMetricNames.DB_DAYS_UNTIL_FULL).scope()).isEqualTo(MonitoringMetricNames.Scope.CLUSTER);
    }
}
