package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tatil takvimi tohumu (2026-10-31) — inceleme D6: nişan OKUNAMAZSA "bitti" sayılmaz. Eskiden {@code done()} okuma hatasında
 * {@code true} dönüyor, {@code run()} bunu {@code doneSeen = true} yapıyordu: tek bir geçici DB hatası o pod'da tohumu kalıcı
 * kapatıyordu.
 */
class HolidayCalendarSeedTest {

    static final String MARKER_COUNT = "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?";

    JdbcTemplate jdbc;
    @SuppressWarnings("unchecked")
    ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
    SchedulerService scheduler = mock(SchedulerService.class);
    HolidayCalendarService calendar = mock(HolidayCalendarService.class);
    HolidayCalendarSeed seed;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        seed = new HolidayCalendarSeed(jdbc, provider, calendar);
    }

    private boolean doneSeen() {
        return (Boolean) ReflectionTestUtils.getField(seed, "doneSeen");
    }

    @Test
    @DisplayName("D6: nişan okunamadı → bu tur atlanır, doneSeen DEĞİŞMEZ; sonraki turda okunan nişan 'bitti' der")
    void unreadableMarkerDoesNotStopSeeding() {
        doThrow(new DataAccessResourceFailureException("veritabanı geçici olarak yok")).doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(eq(MARKER_COUNT), eq(Integer.class), eq(HolidayCalendarSeed.KEY))).thenReturn(1);

        seed.run();
        assertThat(doneSeen()).as("okunamayan nişan 'bitti' sayılmaz").isFalse();
        verify(provider, never()).getIfAvailable();   // bilinmiyor → kilit / tohum denenmez

        seed.run();
        assertThat(doneSeen()).as("okunan nişan → bitti").isTrue();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
    }

    @Test
    @DisplayName("D6: nişan yazılmamış → kilit altında yeniden okunur; orada okunamazsa hata (deneme sayılır), tohum / nişan YAZILMAZ")
    void unreadableMarkerInsideLockWritesNothing() {
        // 1. okuma (ön denetim): nişan yok; 2. okuma (kilit içinde): okunamadı
        doNothing().doThrow(new DataAccessResourceFailureException("bağlantı koptu")).when(jdbc).execute(anyString());
        when(jdbc.queryForObject(eq(MARKER_COUNT), eq(Integer.class), eq(HolidayCalendarSeed.KEY))).thenReturn(0);
        when(scheduler.callWithSchedulerLock(eq(HolidayCalendarSeed.LOCK), any(Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((Supplier<?>) i.getArgument(1)).get()));

        seed.run();
        assertThat(doneSeen()).isFalse();
        assertThat(((java.util.concurrent.atomic.AtomicInteger) ReflectionTestUtils.getField(seed, "failures")).get())
                .as("okunamayan nişan bir başarısız deneme sayılır").isEqualTo(1);
        verify(jdbc, never()).update(contains("INSERT INTO holiday_days"), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT INTO schema_patch_markers"), any(Object[].class));
    }

    @Test
    @DisplayName("D6: seedLocked doğrudan — okunamayan nişanda istisna; okunan 'yazılmış' nişanda 0 ve doneSeen")
    void seedLockedTriState() {
        doThrow(new DataAccessResourceFailureException("yok")).when(jdbc).execute(anyString());
        assertThatThrownBy(() -> seed.seedLocked()).isInstanceOf(IllegalStateException.class);
        assertThat(doneSeen()).isFalse();

        JdbcTemplate ok = mock(JdbcTemplate.class);
        when(ok.queryForObject(eq(MARKER_COUNT), eq(Integer.class), eq(HolidayCalendarSeed.KEY))).thenReturn(1);
        HolidayCalendarSeed s2 = new HolidayCalendarSeed(ok, provider, calendar);
        assertThat(s2.seedLocked()).isZero();
        assertThat((Boolean) ReflectionTestUtils.getField(s2, "doneSeen")).isTrue();
        verify(ok, never()).queryForObject(eq("SELECT COUNT(*) FROM holiday_days"), eq(Integer.class));
    }
}
