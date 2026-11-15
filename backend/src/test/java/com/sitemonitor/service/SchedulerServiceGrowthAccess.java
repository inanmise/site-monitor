package com.sitemonitor.service;

import com.sitemonitor.service.page.PageSpeedRules;

import java.util.List;

/** Test erişimi: {@code SchedulerService.pageSpeedGrowthSamplesOf} (paket görünürlüğü) başka paketteki depo testine. */
public final class SchedulerServiceGrowthAccess {
    private SchedulerServiceGrowthAccess() {}

    public static List<PageSpeedRules.GrowthSample> samplesOf(List<Object[]> rows, boolean extended) {
        return SchedulerService.pageSpeedGrowthSamplesOf(rows, extended);
    }

    /** {@code SchedulerService.growthWindowStart} (paket görünürlüğü). */
    public static String windowStart(java.time.Instant now, String growthSince) {
        return SchedulerService.growthWindowStart(now, growthSince);
    }

    /**
     * Zamanlayıcının GERÇEK büyüme okumasını ({@code pageSpeedGrowthRead}) verilen depoyla koşturur ve sonucu ölçüme uygular
     * ({@code withGrowth(…, complete)}) — {@code recheckPageSpeed}'in büyüme adımı. Yapıcısız örnek: yalnız depo alanı kurulur.
     */
    public static PageSpeedCheckerService.Result readAndApply(com.sitemonitor.repository.PageSpeedCheckRepository repo,
                                                             com.sitemonitor.model.PageSpeedMonitor m,
                                                             PageSpeedCheckerService.Result res) {
        SchedulerService s = org.mockito.Mockito.mock(SchedulerService.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        org.springframework.test.util.ReflectionTestUtils.setField(s, "pageSpeedCheckRepo", repo);
        SchedulerService.GrowthRead gr = s.pageSpeedGrowthRead(m.getId(), res.extended(), m.getGrowthSince());
        return PageSpeedCheckerService.withGrowth(res, m, gr == null ? null : gr.samples(), gr != null && gr.complete());
    }
}
