package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sayfa kullanımı (2026-09-13, System Health #1): ping → bellek → günlük tablo; sekme beyaz listesi; update-then-insert. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageUsageServiceTest {

    @Mock JdbcTemplate jdbc;

    @Test
    @DisplayName("record: geçersiz sekme anahtarı (büyük harf/URL/uzun) yazılmaz; geçerli olan biriktirilir ve son sekme hatırlanır")
    void recordFiltersTabKeys() {
        PageUsageService s = new PageUsageService(jdbc);
        s.record("Admin", "forecast");
        s.record("Admin", "forecast");
        s.record("Admin", "?tab=forecast&x=1");   // beyaz liste dışı
        s.record("Admin", "A".repeat(41));
        s.record(null, "forecast");
        assertThat(s.buffered()).isEqualTo(1);
        assertThat(s.lastTabOf("ADMIN")[0]).isEqualTo("forecast");
        assertThat(s.lastTabOf("nobody")).isNull();
    }

    @Test
    @DisplayName("flush: satır yoksa INSERT, varsa UPDATE (pings toplanır); yazılan düşer, sonraki flush boş")
    void flushUpsertsAndDrains() {
        PageUsageService s = new PageUsageService(jdbc);
        s.record("admin", "forecast"); s.record("admin", "forecast"); s.record("bob", "domains");
        when(jdbc.update(startsWith("UPDATE page_usage_daily"), any(Object[].class))).thenReturn(0, 1);
        s.flush();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).update(sql.capture(), any(Object[].class));
        List<String> sqls = sql.getAllValues();
        assertThat(sqls.stream().filter(x -> x.startsWith("UPDATE")).count()).isEqualTo(2);
        assertThat(sqls.stream().filter(x -> x.startsWith("INSERT")).count()).isEqualTo(1);   // ilki 0 satır → INSERT
        assertThat(s.buffered()).isZero();
        clearInvocations(jdbc);
        s.flush();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("flush: DB hatasında biriken veri kaybolmaz (sonraki tura kalır)")
    void flushKeepsBufferOnError() {
        PageUsageService s = new PageUsageService(jdbc);
        s.record("admin", "forecast");
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db down"));
        s.flush();
        assertThat(s.buffered()).isEqualTo(1);
    }

    @Test
    @DisplayName("rowsSince: sorgu hatası boş liste (panel çökmez)")
    void rowsSinceSwallowsErrors() {
        PageUsageService s = new PageUsageService(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("no table"));
        assertThat(s.rowsSince(7)).isEmpty();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("tab", "forecast")));
        assertThat(s.rowsSince(7)).hasSize(1);
    }
}
