package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbQueryAlertRows;
import com.sitemonitor.repository.DbQueryAlertRowsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Alarm anındaki sorgu satırları — bellek (lider pod, izleme başına en güncel) → alarmla saklama (2026-10-31). */
class DbQueryAlertRowsServiceTest {

    DbQueryAlertRowsRepository repo;
    DbQueryAlertRowsService svc;
    final Map<Long, DbQueryAlertRows> table = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(DbQueryAlertRowsRepository.class);
        lenient().when(repo.findByAlertEventId(anyLong())).thenAnswer(i -> Optional.ofNullable(table.get((Long) i.getArgument(0))));
        lenient().when(repo.save(any(DbQueryAlertRows.class))).thenAnswer(i -> {
            DbQueryAlertRows r = i.getArgument(0);
            table.put(r.getAlertEventId(), r);
            return r;
        });
        svc = new DbQueryAlertRowsService(repo);
    }

    static DbQueryCheckerService.Result result(String checkedAt, List<String> cols, List<List<String>> rows, List<String> masked,
                                               Integer rowCount, boolean capped, boolean truncated) {
        return new DbQueryCheckerService.Result("BREACH", "CRITICAL", 10, rowCount, capped, List.of(), cols, rows, masked,
                truncated, List.of(), null, false, null, null, checkedAt, "h", 1L, null);
    }

    static DbQueryCheckerService.Result simple(String checkedAt, String value) {
        return result(checkedAt, List.of("id", "deger"), List.of(List.of("1", value)), List.of(), 1, false, false);
    }

    @Test
    @DisplayName("bellek: son çalıştırma tutulur; satırsız sonuç (FAIL / mail kapalı) belleği temizler; forget siler")
    void rememberAndForget() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "a"));
        assertThat(svc.latest(5L)).isPresent();
        assertThat(svc.latest(5L).get().rows()).containsExactly(List.of("1", "a"));

        svc.remember(5L, result("2026-10-31T09:05:00", List.of(), List.of(), List.of(), null, false, false));
        assertThat(svc.latest(5L)).isEmpty();

        svc.remember(6L, simple("2026-10-31T09:00:00", "b"));
        svc.forget(6L);
        assertThat(svc.latest(6L)).isEmpty();
        svc.remember(null, simple("x", "c"));
        assertThat(svc.latest(null)).isEmpty();
    }

    @Test
    @DisplayName("sınırlar: en çok 50 satır, 20 kolon, 200 karakter hücre — aşım 'truncated'; null hücre korunur")
    void bounds() {
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < 25; i++) cols.add("c" + i);
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < 60; r++) {
            List<String> row = new ArrayList<>(Collections.nCopies(25, "v"));
            if (r == 0) row.set(1, null);
            if (r == 1) row.set(2, "x".repeat(500));
            rows.add(row);
        }
        DbQueryAlertRowsService.Rows out = DbQueryAlertRowsService.of(result("t", cols, rows, List.of("c3"), 60, false, false));
        assertThat(out.columns()).hasSize(DbQueryAlertRowsService.MAX_COLUMNS);
        assertThat(out.rows()).hasSize(DbQueryAlertRowsService.MAX_ROWS);
        assertThat(out.rows().get(0)).hasSize(DbQueryAlertRowsService.MAX_COLUMNS);
        assertThat(out.rows().get(0).get(1)).isNull();
        assertThat(out.rows().get(1).get(2)).hasSize(DbQueryAlertRowsService.MAX_CELL).endsWith("…");
        assertThat(out.truncated()).isTrue();
        assertThat(out.masked()).containsExactly("c3");
        assertThat(out.rowCount()).isEqualTo(60);

        DbQueryAlertRowsService.Rows small = DbQueryAlertRowsService.of(simple("t", "a"));
        assertThat(small.truncated()).isFalse();
    }

    @Test
    @DisplayName("capture: bellekteki satırlar alarmla saklanır; aynı alarmın sonraki bildirimi DAHA YENİ çalıştırmayla günceller, eskisiyle değil")
    void captureStoresAndKeepsNewest() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "ilk"));
        Optional<DbQueryAlertRowsService.Rows> c1 = svc.capture(100L, 5L);
        assertThat(c1).isPresent();
        assertThat(table.get(100L).getRowsJson()).contains("ilk");
        assertThat(table.get(100L).getMonitorId()).isEqualTo(5L);

        // Daha yeni çalıştırma → günlük yeniden uyarı alarm satırını günceller.
        svc.remember(5L, simple("2026-10-31T10:00:00", "yeni"));
        assertThat(svc.capture(100L, 5L).get().rows().get(0)).containsExactly("1", "yeni");
        assertThat(table.get(100L).getRowsJson()).contains("yeni");

        // Bellek daha ESKİ (başka pod liderken yazılmış satır daha yeni) → saklı satır korunur.
        DbQueryAlertRows stored = table.get(100L);
        stored.setCapturedAt("2026-10-31T11:00:00");
        svc.remember(5L, simple("2026-10-31T10:30:00", "eski"));
        assertThat(svc.capture(100L, 5L).get().rows().get(0)).containsExactly("1", "yeni");

        // Bellek yok (lider değişti) → saklı satır okunur, yazma yok.
        svc.forget(5L);
        assertThat(svc.capture(100L, 5L)).isPresent();
        assertThat(svc.stored(100L).get().columns()).containsExactly("id", "deger");
        assertThat(svc.capture(null, 5L)).isEmpty();
    }

    @Test
    @DisplayName("capture: tekil kısıt yarışı (iki gönderim aynı anda) → kazananın satırı güncellenir; hata fırlamaz")
    void captureUniqueRace() {
        AtomicBoolean first = new AtomicBoolean(true);
        DbQueryAlertRows winner = new DbQueryAlertRows();
        winner.setAlertEventId(200L);
        winner.setMonitorId(5L);
        winner.setCapturedAt("2026-10-31T08:00:00");
        when(repo.findByAlertEventId(200L)).thenAnswer(i -> first.getAndSet(false) ? Optional.empty() : Optional.of(winner));
        when(repo.save(any(DbQueryAlertRows.class))).thenAnswer(i -> {
            DbQueryAlertRows r = i.getArgument(0);
            if (r != winner) throw new DataIntegrityViolationException("uk_dbqar_alert");
            return r;
        });
        svc.remember(5L, simple("2026-10-31T09:00:00", "v"));
        assertThat(svc.capture(200L, 5L)).isPresent();
        assertThat(winner.getRowsJson()).contains("\"v\"");
    }

    @Test
    @DisplayName("capture: depo düşerse bildirim yine bellekteki satırları alır (hata fırlamaz)")
    void captureRepoFailureFallsBackToMemory() {
        when(repo.findByAlertEventId(300L)).thenThrow(new IllegalStateException("db"));
        svc.remember(5L, simple("2026-10-31T09:00:00", "m"));
        assertThat(svc.capture(300L, 5L)).isPresent();
        verify(repo, never()).save(any());
    }
}
