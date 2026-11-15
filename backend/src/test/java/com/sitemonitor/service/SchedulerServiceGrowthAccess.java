package com.sitemonitor.service;

import com.sitemonitor.service.page.PageSpeedRules;

import java.util.List;

/** Test erişimi: {@code SchedulerService.pageSpeedGrowthSamplesOf} (paket görünürlüğü) başka paketteki depo testine. */
public final class SchedulerServiceGrowthAccess {
    private SchedulerServiceGrowthAccess() {}

    public static List<PageSpeedRules.GrowthSample> samplesOf(List<Object[]> rows, boolean extended) {
        return SchedulerService.pageSpeedGrowthSamplesOf(rows, extended);
    }
}
