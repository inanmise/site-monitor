package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 (2026-10-28, paket C; kullanıcı kararı D-2, tasarım R3-2 §6): takipçi pod'un "Teyit denemesi X/N" listesi
 * {@code monitor_confirmations}'tan. Ayar kapalı / lider → bellek AYNEN (J-1, veritabanı yok); takipçi + ayar açık + canlı
 * {@code '*'} → o kuşağın ve yazanın ABANDONED olmayan satırları MOS biçimiyle; her hata → bellek; 2 sn ezber; sınır 2000.
 */
class ActiveConfirmationsServiceTest {

    static final String WRITER = "sitemonitor-a-0000000a";

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicLong clock = new AtomicLong(MultiPodTestSupport.T0);
    final AtomicBoolean readOn = new AtomicBoolean(true);
    final AtomicBoolean leader = new AtomicBoolean(false);
    final List<Map<String, Object>> memoryRows = new ArrayList<>();
    final AtomicInteger memoryCalls = new AtomicInteger();
    final Function<String, List<Map<String, Object>>> memory = d -> {
        memoryCalls.incrementAndGet();
        return memoryRows;
    };

    @BeforeEach
    void schema() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
    }

    @AfterEach
    void close() {
        mp.close();
    }

    private ActiveConfirmationsService service(JdbcTemplate jdbc) {
        return new ActiveConfirmationsService(memory, readOn::get, leader::get, jdbc, clock::get);
    }

    private ActiveConfirmationsService service() {
        return service(mp.jdbc());
    }

    private void star(long gen, String writer, long updatedMs) {
        mp.jdbc().update("DELETE FROM monitor_eval_type_state WHERE alert_type = '*'");
        mp.jdbc().update("INSERT INTO monitor_eval_type_state (alert_type, writer, gen, seq, updated_ms, updated_at) "
                + "VALUES ('*', ?, ?, 0, ?, 'x')", writer, gen, updatedMs);
    }

    private static int seq;

    private void chain(String domain, String alertType, String detail, int attempt, int total, String firstFailure,
                       Long nextMs, String state, long gen, String writer, long createdMs) {
        chain(domain, alertType, detail, attempt, total, firstFailure, nextMs, state, gen, writer, createdMs, clock.get() - 1_000);
    }

    /** Açık {@code updated_ms} ile (varsayılan: şimdiden 1 sn önce — canlı zincir). */
    private void chain(String domain, String alertType, String detail, int attempt, int total, String firstFailure,
                       Long nextMs, String state, long gen, String writer, long createdMs, long updatedMs) {
        String hash = String.format("%064d", ++seq);
        mp.jdbc().update("INSERT INTO monitor_confirmations (key_hash, confirm_key, chain_id, alert_type, domain, detail, "
                        + "monitor_type, monitor_id, attempt, total_attempts, delay_ms, first_failure_at, next_attempt_ms, "
                        + "attempts_json, ctx_json, ctx_truncated, state, created_ms, writer, gen, seq, updated_ms, updated_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, 'http', 7, ?, ?, 30000, ?, ?, NULL, NULL, FALSE, ?, ?, ?, ?, 1, ?, 'x')",
                hash, alertType + ":" + domain + ":" + detail, alertType, domain, detail, attempt, total, firstFailure,
                nextMs, state, createdMs, writer, gen, updatedMs);
    }

    private void running(String domain, long createdMs) {
        chain(domain, "HTTP_DOWN", null, 1, 3, "2026-10-21T05:59:00", MultiPodTestSupport.T0 + 30_000, "RUNNING", 4, WRITER, createdMs);
    }

    // ── J-1 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("J-1: ayar kapalı → bellek AYNEN (aynı liste nesnesi), veritabanına hiç gidilmez — takipçide de")
    void settingOff_memoryVerbatim() {
        readOn.set(false);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        memoryRows.add(new LinkedHashMap<>(Map.of("domain", "a.example.com")));
        ActiveConfirmationsService s = service(jdbc);
        assertThat(s.list(null)).isSameAs(memoryRows);
        assertThat(s.list("a.example.com")).isSameAs(memoryRows);
        leader.set(true);
        assertThat(s.list(null)).isSameAs(memoryRows);
        verifyNoInteractions(jdbc);
        assertThat(memoryCalls).hasValue(3);
    }

    @Test
    @DisplayName("lider (ayar açık) → bellek AYNEN, veritabanı yok; ayar okunamazsa = kapalı (bellek)")
    void leaderOrSettingFailure_memory() {
        leader.set(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveConfirmationsService s = service(jdbc);
        assertThat(s.list(null)).isSameAs(memoryRows);
        ActiveConfirmationsService broken = new ActiveConfirmationsService(memory,
                () -> { throw new IllegalStateException("x"); }, () -> false, jdbc, clock::get);
        assertThat(broken.list(null)).isSameAs(memoryRows);
        ActiveConfirmationsService hintBroken = new ActiveConfirmationsService(memory, () -> true,
                () -> { throw new IllegalStateException("x"); }, jdbc, clock::get);
        assertThat(hintBroken.list(null)).as("ipucu okunamazsa lider sayılır = bellek").isSameAs(memoryRows);
        verifyNoInteractions(jdbc);
    }

    // ── Takipçi + ayar açık ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("takipçi + açık + canlı '*' → o kuşağın satırları MOS biçimiyle (sıra, alanlar, ISO sonraki deneme, null), oluşturma sırası")
    void follower_tableRowsMappedLikeMemory() {
        star(4, WRITER, clock.get() - 5_000);
        chain("b.example.com", "PORT_DOWN", "443", 2, 3, "2026-10-21T05:58:00", null, "FINISHING", 4, WRITER, 2_000);
        running("a.example.com", 1_000);
        List<Map<String, Object>> rows = service().list(null);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(
                Map.entry("alert_type", "HTTP_DOWN"), Map.entry("domain", "a.example.com"), entryNull("detail"),
                Map.entry("attempt", 1), Map.entry("total_attempts", 3), Map.entry("started_at", "2026-10-21T05:59:00"),
                Map.entry("next_attempt_at", "2026-10-21T06:00:30"));
        assertThat(rows.get(1)).containsEntry("alert_type", "PORT_DOWN").containsEntry("detail", "443")
                .containsEntry("attempt", 2).containsEntry("next_attempt_at", null);
        assertThat(memoryCalls).as("bellek kullanılmadı").hasValue(0);
    }

    private static Map.Entry<String, Object> entryNull(String k) {
        return new java.util.AbstractMap.SimpleEntry<>(k, null);
    }

    @Test
    @DisplayName("alan adları ve sırası MOS.activeConfirmations ile aynı (kaynak kapısı)")
    void keysMatchMemoryMapping() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/MonitoringOutageService.java"),
                StandardCharsets.UTF_8);
        int start = src.indexOf("public List<Map<String, Object>> activeConfirmations(String domainFilter)");
        assertThat(start).isPositive();
        String body = src.substring(start, src.indexOf("\n    }", start));
        List<String> mosKeys = new ArrayList<>();
        Matcher m = Pattern.compile("m\\.put\\(\"([a-z_]+)\"").matcher(body);
        while (m.find()) mosKeys.add(m.group(1));
        star(4, WRITER, clock.get());
        running("a.example.com", 1_000);
        assertThat(new ArrayList<>(service().list(null).get(0).keySet())).isEqualTo(mosKeys);
        assertThat(mosKeys).contains("alert_type", "next_attempt_at");
    }

    @Test
    @DisplayName("alan adı süzgeci büyük / küçük harf duyarsız eşitlik (MOS yüklemi); eşleşmeyen yok")
    void domainFilterEqualsIgnoreCase() {
        star(4, WRITER, clock.get());
        running("Api.Example.com", 1_000);
        running("other.example.com", 2_000);
        ActiveConfirmationsService s = service();
        assertThat(s.list("api.example.COM")).extracting(r -> r.get("domain")).containsExactly("Api.Example.com");
        assertThat(s.list("api.example")).isEmpty();
        assertThat(s.list(null)).hasSize(2);
    }

    @Test
    @DisplayName("ABANDONED, önceki kuşak ve başka yazanın satırları gösterilmez")
    void onlyLiveGenerationAndWriter() {
        star(5, WRITER, clock.get());
        chain("ab.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "ABANDONED", 5, WRITER, 1);
        chain("old.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "RUNNING", 4, WRITER, 2);
        chain("other.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "RUNNING", 5, "sitemonitor-b-0000000b", 3);
        chain("live.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "RUNNING", 5, WRITER, 4);
        assertThat(service().list(null)).extracting(r -> r.get("domain")).containsExactly("live.example.com");
    }

    @Test
    @DisplayName("'*' bayat (> 130 sn) / yok / kuşak 0 → bellek (takipçide boş = bugün); tam 130 sn hâlâ canlı")
    void staleOrMissingGeneration_memory() {
        assertThat(ActiveConfirmationsService.LIVE_MS).isEqualTo(130_000L);
        running("a.example.com", 1_000);
        memoryRows.add(Map.of("domain", "memory"));
        assertThat(service().list(null)).as("'*' yok").isSameAs(memoryRows);
        star(4, WRITER, clock.get() - ActiveConfirmationsService.LIVE_MS - 1);
        assertThat(service().list(null)).as("bayat").isSameAs(memoryRows);
        star(4, WRITER, clock.get() - ActiveConfirmationsService.LIVE_MS);
        assertThat(service().list(null)).as("sınırda canlı").extracting(r -> r.get("domain")).containsExactly("a.example.com");
        star(0, WRITER, clock.get());
        assertThat(service().list(null)).as("kuşak 0").isSameAs(memoryRows);
    }

    @Test
    @DisplayName("tablolar yok / zaman aşımı / herhangi bir hata → bellek (asla fırlatmaz)")
    void failures_memory() {
        memoryRows.add(Map.of("domain", "memory"));
        MultiPodTestSupport empty = MultiPodTestSupport.create();
        try {
            assertThat(service(empty.jdbc()).list(null)).as("tablo yok").isSameAs(memoryRows);
        } finally {
            empty.close();
        }
        JdbcTemplate slow = mock(JdbcTemplate.class);
        when(slow.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("3 sn"));
        assertThat(service(slow).list(null)).as("zaman aşımı").isSameAs(memoryRows);
        JdbcTemplate weird = mock(JdbcTemplate.class);
        when(weird.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("x"));
        assertThat(service(weird).list(null)).isSameAs(memoryRows);
        assertThat(service(null).list(null)).as("şablon yok").isSameAs(memoryRows);
    }

    @Test
    @DisplayName("2 sn ezber: pencere içinde ikinci istek veritabanına gitmez (yeni satırı görmez); sonra görür; hata da ezberlenir")
    void memoTwoSeconds() {
        star(4, WRITER, clock.get());
        running("a.example.com", 1_000);
        ActiveConfirmationsService s = service();
        assertThat(s.list(null)).hasSize(1);
        running("b.example.com", 2_000);
        clock.addAndGet(ActiveConfirmationsService.MEMO_MS - 1);
        assertThat(s.list(null)).as("ezber").hasSize(1);
        clock.addAndGet(1);
        assertThat(s.list(null)).as("ezber doldu").hasSize(2);

        AtomicInteger queries = new AtomicInteger();
        JdbcTemplate counting = mock(JdbcTemplate.class);
        when(counting.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    queries.incrementAndGet();
                    throw new QueryTimeoutException("x");
                });
        ActiveConfirmationsService f = service(counting);
        f.list(null);
        f.list(null);
        f.list("x");
        assertThat(queries).as("başarısız okuma da 2 sn ezberlenir (yavaş veritabanı her istekte beklenmez)").hasValue(1);
    }

    @Test
    @DisplayName("L-2: 20 dk'dır güncellenmeyen RUNNING satırı (kaybolmuş zincir-sonu silmesi) hayalettir — gösterilmez; sınırda gösterilir")
    void staleRunningRowIgnored() {
        assertThat(ActiveConfirmationsService.CHAIN_ROW_MAX_AGE_MS).isEqualTo(20 * 60_000L);
        star(4, WRITER, clock.get() - 5_000);   // kuşak canlı (kalp atışı taze)
        long now = clock.get();
        chain("phantom.example.com", "HTTP_DOWN", null, 2, 3, "x", null, "RUNNING", 4, WRITER, 1,
                now - ActiveConfirmationsService.CHAIN_ROW_MAX_AGE_MS - 1);
        chain("edge.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "RUNNING", 4, WRITER, 2,
                now - ActiveConfirmationsService.CHAIN_ROW_MAX_AGE_MS);
        chain("live.example.com", "HTTP_DOWN", null, 1, 3, "x", null, "FINISHING", 4, WRITER, 3, now - 30_000);
        assertThat(service().list(null)).extracting(r -> r.get("domain"))
                .containsExactly("edge.example.com", "live.example.com");
        assertThat(memoryCalls).as("tablo okundu, bellek kullanılmadı").hasValue(0);
    }

    @Test
    @DisplayName("en çok 2000 satır")
    void limit() {
        star(4, WRITER, clock.get());
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < ActiveConfirmationsService.MAX_ROWS + 5; i++) {
            batch.add(new Object[] {String.format("h%063d", i), "k" + i, "d" + i + ".example.com", (long) i});
        }
        mp.jdbc().batchUpdate("INSERT INTO monitor_confirmations (key_hash, confirm_key, chain_id, alert_type, domain, detail, "
                + "monitor_type, monitor_id, attempt, total_attempts, delay_ms, first_failure_at, next_attempt_ms, attempts_json, "
                + "ctx_json, ctx_truncated, state, created_ms, writer, gen, seq, updated_ms, updated_at) VALUES (?, ?, 1, 'HTTP_DOWN', ?, "
                + "NULL, NULL, NULL, 1, 3, 30000, 'x', NULL, NULL, NULL, FALSE, 'RUNNING', ?, '" + WRITER + "', 4, 1, "
                + clock.get() + ", 'x')", batch);
        List<Map<String, Object>> rows = service().list(null);
        assertThat(rows).hasSize(ActiveConfirmationsService.MAX_ROWS);
        assertThat(rows.get(0)).containsEntry("domain", "d0.example.com");
    }

    @Test
    @DisplayName("okuma sürerken başka istek BEKLEMEZ: taze ezber yoksa bellek döner (yavaş veritabanı istekleri yığmaz)")
    void concurrentRequestDoesNotWait() throws Exception {
        CountDownLatch inQuery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcTemplate blocking = mock(JdbcTemplate.class);
        when(blocking.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    inQuery.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return List.of();
                });
        memoryRows.add(Map.of("domain", "memory"));
        ActiveConfirmationsService s = service(blocking);
        Thread t = new Thread(() -> s.list(null));
        t.start();
        assertThat(inQuery.await(10, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        assertThat(s.list(null)).isSameAs(memoryRows);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2_000L);
        release.countDown();
        t.join(10_000);
    }

    @Test
    @DisplayName("üretim kurucusu kendi şablonunu 3 sn sorgu zaman aşımıyla kurar (paylaşılan şablona dokunmaz)")
    @SuppressWarnings("unchecked")
    void productionConstructorUsesThreeSecondTimeout() {
        org.springframework.beans.factory.ObjectProvider<com.sitemonitor.service.SchedulerService> sched =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        org.springframework.beans.factory.ObjectProvider<com.sitemonitor.service.AppSettingsService> settings =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        JdbcTemplate shared = new JdbcTemplate(mp.dataSource());
        ActiveConfirmationsService s = new ActiveConfirmationsService(mock(com.sitemonitor.service.MonitoringOutageService.class),
                sched, settings, shared);
        JdbcTemplate own = (JdbcTemplate) ReflectionTestUtils.getField(s, "jdbc");
        assertThat(own).isNotSameAs(shared);
        assertThat(own.getQueryTimeout()).isEqualTo(ActiveConfirmationsService.QUERY_TIMEOUT_SECONDS).isEqualTo(3);
        assertThat(shared.getQueryTimeout()).isEqualTo(-1);
        // ayarlar çözülemezse kapalı → bellek (MOS sahtesi boş liste)
        assertThat(s.list(null)).isEmpty();
    }
}
