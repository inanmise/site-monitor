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

    // ── 2026-10-31 inceleme D1 (bellek) / D2 (güncel ayar) ────────────────────────────────────────────────────────────

    static DbQueryCheckerService.Result withStatus(String status, boolean slow, String checkedAt) {
        return new DbQueryCheckerService.Result(status, "NONE", 10, 1, false, List.of(), List.of("id", "deger"),
                List.of(List.of("1", "v")), List.of(), false, List.of(), null, slow, null, null, checkedAt, "h", 1L, null);
    }

    @Test
    @DisplayName("D1: yalnız ihlal / yavaş çalıştırmanın satırları tutulur; sağlıklı çalıştırma girişi siler")
    void onlyBreachOrSlowIsRemembered() {
        svc.remember(5L, withStatus("OK", false, "2026-10-31T09:00:00"));
        assertThat(svc.latest(5L)).isEmpty();
        svc.remember(5L, withStatus("OK", true, "2026-10-31T09:01:00"));
        assertThat(svc.latest(5L)).as("yavaş sorgu satırları").isPresent();
        svc.remember(5L, withStatus("BREACH", false, "2026-10-31T09:02:00"));
        assertThat(svc.latest(5L)).isPresent();
        svc.remember(5L, withStatus("OK", false, "2026-10-31T09:03:00"));
        assertThat(svc.latest(5L)).as("ihlal bitti — eski satırlar yeni alarma taşınmaz").isEmpty();
        assertThat(svc.rememberedChars()).isZero();
    }

    @Test
    @DisplayName("D1: toplam karakter bütçesi aşılınca EN ESKİ girişler düşer; giriş süresi dolunca kullanılmaz")
    void byteBudgetAndAge() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        svc.setClockForTests(now::get);
        List<String> cols = new ArrayList<>();
        for (int c = 0; c < 20; c++) cols.add("c" + c);
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < 50; r++) rows.add(new ArrayList<>(Collections.nCopies(20, "x".repeat(199))));
        long per = DbQueryAlertRowsService.weight(DbQueryAlertRowsService.of(result("t", cols, rows, List.of(), 50, false, false)));
        int fits = (int) (DbQueryAlertRowsService.MAX_REMEMBERED_CHARS / per);
        for (long id = 1; id <= fits + 5; id++) svc.remember(id, result("2026-10-31T09:00:00", cols, rows, List.of(), 50, false, false));
        assertThat(svc.rememberedChars()).isLessThanOrEqualTo(DbQueryAlertRowsService.MAX_REMEMBERED_CHARS);
        assertThat(svc.latest(1L)).as("en eski düştü").isEmpty();
        assertThat(svc.latest((long) fits + 5)).isPresent();

        now.addAndGet(DbQueryAlertRowsService.MAX_AGE_MS + 1);
        assertThat(svc.latest((long) fits + 5)).as("süresi doldu (silinen izlemenin girişi takipçide de kalmaz)").isEmpty();
        svc.remember(999L, simple("2026-10-31T10:00:00", "yeni"));
        assertThat(svc.rememberedCount()).as("eski girişler budandı").isEqualTo(1);
    }

    private com.sitemonitor.repository.DbQueryMonitorRepository monitors(com.sitemonitor.model.DbQueryMonitor m) {
        com.sitemonitor.repository.DbQueryMonitorRepository r = mock(com.sitemonitor.repository.DbQueryMonitorRepository.class);
        when(r.findById(5L)).thenReturn(Optional.ofNullable(m));
        svc.setMonitorRepository(r);
        return r;
    }

    @Test
    @DisplayName("D2: 'satırları maile ekle' sonradan kapatıldıysa gönderimde satır YOK, hiçbir şey yazılmaz; ayrıntı da göstermez")
    void rowsTurnedOffAtSendTime() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "gizli"));
        svc.capture(100L, 5L);   // ayar yok (izleme deposu bağlı değil) — açılışta yazıldı
        com.sitemonitor.model.DbQueryMonitor m = new com.sitemonitor.model.DbQueryMonitor();
        m.setId(5L);
        m.setMailIncludeRows(false);
        monitors(m);
        svc.remember(5L, simple("2026-10-31T10:00:00", "daha-yeni"));
        assertThat(svc.capture(100L, 5L)).isEmpty();
        assertThat(table.get(100L).getRowsJson()).as("daha yeni satırlar yazılmadı").doesNotContain("daha-yeni");
        assertThat(svc.stored(100L)).isEmpty();
    }

    @Test
    @DisplayName("D2: sonradan maskelenen kolon gönderimde '••••' — bellekteki ve saklı kopya maskesiz gitmez")
    void newMaskAppliedAtSendTime() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "4111-1111"));
        com.sitemonitor.model.DbQueryMonitor m = new com.sitemonitor.model.DbQueryMonitor();
        m.setId(5L);
        m.setMaskedColumnsJson("[\"DEGER\"]");
        monitors(m);
        DbQueryAlertRowsService.Rows out = svc.capture(100L, 5L).orElseThrow();
        assertThat(out.rows().get(0)).containsExactly("1", DbQueryAlertRowsService.MASK);
        assertThat(out.masked()).contains("deger");
        assertThat(table.get(100L).getRowsJson()).doesNotContain("4111");

        // Saklı satır maskesiz kalmışsa (maske açılıştan SONRA eklendi) gönderimde maskelenir ve kopya güncellenir.
        svc.setMonitorRepository(null);
        svc.remember(5L, simple("2026-10-31T09:30:00", "5555-2222"));
        svc.capture(200L, 5L);
        assertThat(table.get(200L).getRowsJson()).contains("5555");
        svc.forget(5L);
        monitors(m);
        assertThat(svc.capture(200L, 5L).orElseThrow().rows().get(0)).containsExactly("1", DbQueryAlertRowsService.MASK);
        assertThat(table.get(200L).getRowsJson()).doesNotContain("5555");
        assertThat(svc.stored(200L).orElseThrow().rows().get(0).get(1)).isEqualTo(DbQueryAlertRowsService.MASK);
    }

    @Test
    @DisplayName("D-2: mail kolonlarından sonradan çıkarılan kolon gönderimde düşer — bellekteki ve saklı kopyada da kalmaz")
    void mailColumnsNarrowedAtSendTime() {
        svc.remember(5L, result("2026-10-31T09:00:00", List.of("id", "kart", "deger"),
                List.of(List.of("1", "4111-1111", "v")), List.of("kart"), 1, false, false));
        svc.capture(200L, 5L);   // ayar yok — açılışta üç kolonla saklandı
        assertThat(table.get(200L).getRowsJson()).contains("4111-1111");

        com.sitemonitor.model.DbQueryMonitor m = new com.sitemonitor.model.DbQueryMonitor();
        m.setId(5L);
        m.setMailColumnsJson("[\"ID\",\"deger\"]");
        monitors(m);

        // Bellek yolu (yeni alarm): kolon listesi daraltılır, sıra korunur, maskeli listesi süzülür.
        DbQueryAlertRowsService.Rows out = svc.capture(300L, 5L).orElseThrow();
        assertThat(out.columns()).containsExactly("id", "deger");
        assertThat(out.rows()).containsExactly(List.of("1", "v"));
        assertThat(out.masked()).isEmpty();
        assertThat(out.truncated()).isTrue();
        assertThat(table.get(300L).getRowsJson()).doesNotContain("4111");

        // Saklı yol (bellek yok — lider değişti): saklı kopya da daraltılıp yeniden yazılır.
        svc.forget(5L);
        assertThat(svc.capture(200L, 5L).orElseThrow().columns()).containsExactly("id", "deger");
        assertThat(table.get(200L).getRowsJson()).doesNotContain("4111");
        assertThat(svc.stored(200L).orElseThrow().rows()).containsExactly(List.of("1", "v"));

        // Mail kolonları tümüyle değişti (eski kolonların hiçbiri kalmadı) → satır gönderilmez.
        m.setMailColumnsJson("[\"tutar\"]");
        assertThat(svc.capture(200L, 5L)).isEmpty();
        assertThat(svc.stored(200L)).isEmpty();

        // Liste boş = daraltma yok (kayıtta seçilen kolonlar aynen).
        m.setMailColumnsJson(null);
        svc.remember(5L, simple("2026-10-31T10:00:00", "w"));
        assertThat(svc.capture(400L, 5L).orElseThrow().columns()).containsExactly("id", "deger");
    }

    @Test
    @DisplayName("D2: izlemenin ayarı okunamazsa satır gönderilmez (doğrulanamayan veri gitmez); izleme silinmişse saklı satır kalır")
    void unreadableSettingsSendNothing() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "v"));
        com.sitemonitor.repository.DbQueryMonitorRepository r = mock(com.sitemonitor.repository.DbQueryMonitorRepository.class);
        when(r.findById(5L)).thenThrow(new IllegalStateException("db"));
        svc.setMonitorRepository(r);
        assertThat(svc.capture(100L, 5L)).isEmpty();
        verify(repo, never()).save(any());

        monitors(null);   // silinmiş izleme: ayar yok → saklanan maske geçerli
        assertThat(svc.capture(100L, 5L)).isPresent();
    }

    @Test
    @DisplayName("r177 (Ö-6): 'yalnız seçilen kolonlar' kipinde seçim boşsa gönderimde satır YOK (ilk kolonlara düşülmez); FIRST_N eski davranış")
    void selectedModeWithoutColumnsSendsNoRows() {
        svc.remember(5L, simple("2026-10-31T09:00:00", "gizli"));
        com.sitemonitor.model.DbQueryMonitor m = new com.sitemonitor.model.DbQueryMonitor();
        m.setId(5L);
        m.setMailColumnsMode(com.sitemonitor.model.DbQueryMonitor.MAIL_COLUMNS_SELECTED);
        monitors(m);
        assertThat(svc.capture(100L, 5L)).isEmpty();
        assertThat(table).doesNotContainKey(100L);
        m.setMailColumnsJson("[\"id\"]");
        assertThat(svc.capture(101L, 5L).orElseThrow().columns()).containsExactly("id");
        m.setMailColumnsJson(null);
        m.setMailColumnsMode(null);
        assertThat(svc.capture(102L, 5L).orElseThrow().columns()).containsExactly("id", "deger");
    }
}
