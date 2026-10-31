package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.HolidayDay;
import com.sitemonitor.repository.HolidayDayRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** r177 (H-7): takvimin kapsadığı son gün + "yakında bitiyor" uyarısı; bir yılın sabit tarihli resmî tatillerini ekleme. */
class HolidayCalendarCoverageTest {

    private static Map<String, Object> d(String day) {
        return Map.of("day", day, "name", "x");
    }

    @Test
    void coverageUntilAndEndsSoon() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        Map<String, Object> far = HolidayCalendarService.coverage(List.of(d("2026-10-29"), d("2027-04-23"), d("2027-01-01")), today);
        assertThat(far).containsEntry("coverage_until", "2027-04-23").containsEntry("ends_soon", false)
                .containsEntry("ends_soon_days", 60);
        // son gün 60 gün içinde → uyarı (sınır dahil)
        assertThat(HolidayCalendarService.coverage(List.of(d("2026-12-30")), today)).containsEntry("ends_soon", true);
        assertThat(HolidayCalendarService.coverage(List.of(d("2027-01-01")), today)).containsEntry("ends_soon", false);
        // boş takvim: kapsam yok, uyarı var; bozuk gün atlanır
        Map<String, Object> empty = HolidayCalendarService.coverage(List.of(), today);
        assertThat(empty.get("coverage_until")).isNull();
        assertThat(empty).containsEntry("ends_soon", true);
        assertThat(HolidayCalendarService.coverage(List.of(d("bozuk"), d("2028-01-01")), today))
                .containsEntry("coverage_until", "2028-01-01");
        assertThat(HolidayCalendarService.coverage(null, today)).containsEntry("ends_soon", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addFixedAddsOnlyMissingDaysOfTheYear() {
        HolidayDayRepository repo = mock(HolidayDayRepository.class);
        HolidayDay existing = new HolidayDay();
        existing.setDay("2027-05-01");
        existing.setName("Bizim adımız");   // aynı gün farklı adla — dokunulmaz
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(existing)));
        HolidayCalendarService svc = new HolidayCalendarService(repo);
        assertThat(svc.addFixed(2027, "Global Yönetici")).isEqualTo(6);
        ArgumentCaptor<Iterable<HolidayDay>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(saved.capture());
        List<String> days = new ArrayList<>();
        for (HolidayDay h : saved.getValue()) {
            days.add(h.getDay());
            assertThat(h.getCreatedByName()).isEqualTo("Global Yönetici");
            assertThat(h.getName()).isNotBlank();
        }
        assertThat(days).containsExactly("2027-01-01", "2027-04-23", "2027-05-19", "2027-07-15", "2027-08-30", "2027-10-29");
        assertThat(existing.getName()).isEqualTo("Bizim adımız");
        // dini bayramlar (yıl yıl kayar) listede YOK
        assertThat(HolidayCalendarService.FIXED_PUBLIC_HOLIDAYS).hasSize(7);
    }

    @Test
    void addFixedNothingToAddWritesNothing() {
        HolidayDayRepository repo = mock(HolidayDayRepository.class);
        List<HolidayDay> all = new ArrayList<>();
        for (String[] f : HolidayCalendarService.FIXED_PUBLIC_HOLIDAYS) {
            HolidayDay h = new HolidayDay();
            h.setDay("2028-" + f[0]);
            h.setName(f[1]);
            all.add(h);
        }
        when(repo.findAll()).thenReturn(all);
        HolidayCalendarService svc = new HolidayCalendarService(repo);
        assertThat(svc.addFixed(2028, "x")).isZero();
        verify(repo, never()).saveAll(any());
        assertThatThrownBy(() -> svc.addFixed(1500, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
