package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckWorkerTestHooks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sayfa Hızı 2c bağımsız inceleme düzeltmeleri (2026-11-15), zamanlayıcı tarafı: büyüme okumasının penceresi
 * {@code growth_since}'ten başlar, okuma sınıra takılmadıysa geçmiş "tam" sayılır (bant kontrollü uyumu) ve işçinin büyüme
 * okuması SIRASINDA bıraktığı iş geçmiş satırı yazmaz (F4 kuralı — ölçümden sonraki denetim yetmiyordu).
 */
class PageSpeedGrowthReviewFixTest {

    R33SweepHarness h;
    List<PageSpeedMonitor> all;

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
        all = h.pageSpeedFixture();
        all.get(0).setGrowthBytesPct(20);   // 1 numaralı izleme: büyüme eşiği tanımlı → okuma yapılır
    }

    @Test
    @DisplayName("F4: işçi işi büyüme okuması sırasında bırakılırsa (zaman aşımı) sayfa hızı geçmiş satırı yazılmaz; bırakılmayan iş yazar")
    void jobAbandonedDuringGrowthReadWritesNothing() {
        when(h.pageSpeedCheckRepo.growthWindow(eq(1L), anyString(), anyString(), anyInt())).thenAnswer(i -> {
            CheckWorkerTestHooks.abandonCurrentJob();   // okuma ≤ 5 sn sürerken işçinin süresi doldu
            return List.of();
        });
        Map<String, Object> out = CheckWorkerTestHooks.runAsJob(() -> h.scheduler.runCheckJob(CheckJobTypes.PAGESPEED, 1L));
        assertThat(CheckJobRunner.carriesResult(out)).isFalse();
        verify(h.pageSpeedCheckRepo, never()).save(any());

        // aynı iş, bırakılmadan → bugünkü gibi yazar
        when(h.pageSpeedCheckRepo.growthWindow(eq(1L), anyString(), anyString(), anyInt())).thenReturn(List.of());
        Map<String, Object> ok = CheckWorkerTestHooks.runAsJob(() -> h.scheduler.runCheckJob(CheckJobTypes.PAGESPEED, 1L));
        assertThat(CheckJobRunner.carriesResult(ok)).isTrue();
        verify(h.pageSpeedCheckRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("Büyüme okuması growth_since'ten başlar (daha yeniyse); tam/kesik bayrağı LIMIT'e takılıp takılmamaya göre")
    void growthReadWindowAndCompleteness() {
        List<String> froms = new ArrayList<>();
        when(h.pageSpeedCheckRepo.growthWindow(eq(1L), anyString(), anyString(), anyInt())).thenAnswer(i -> {
            froms.add(i.getArgument(1));
            int limit = i.getArgument(3);
            List<Object[]> rows = new ArrayList<>();
            for (int k = 0; k < (froms.size() == 1 ? 4 : limit); k++)
                rows.add(new Object[]{100_000L, 10, null, true, false, false, null, null, null, null});
            return rows;
        });
        String since = "2099-01-01T00:00:00";   // gelecekteki bir değer kesinlikle 7 günden yeni
        SchedulerService.GrowthRead few = h.scheduler.pageSpeedGrowthRead(1L, false, since);
        assertThat(froms.get(0)).isEqualTo(since);
        assertThat(few.samples()).hasSize(4);
        assertThat(few.complete()).isTrue();

        SchedulerService.GrowthRead full = h.scheduler.pageSpeedGrowthRead(1L, false, null);
        assertThat(froms.get(1)).isNotEqualTo(since);
        assertThat(full.samples()).hasSize(com.sitemonitor.service.page.PageSpeedRules.GROWTH_READ_LIMIT);
        assertThat(full.complete()).isFalse();   // sınıra takıldı: daha eski örnekler okunmadı

        when(h.pageSpeedCheckRepo.growthWindow(eq(1L), anyString(), anyString(), anyInt()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("yavaş"));
        assertThat(h.scheduler.pageSpeedGrowthRead(1L, false, null)).isNull();   // okunamadı → bilinmiyor
    }
}
