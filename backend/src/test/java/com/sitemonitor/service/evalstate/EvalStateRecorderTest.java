package com.sitemonitor.service.evalstate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.evalstate.EvalStateSource.RecoveryValues;
import com.sitemonitor.service.evalstate.EvalStateSource.TypeValues;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Kalıcı değerlendirme durumu kaydedicisi (2026-10-23, çok pod Sürüm 3-1, A1; tasarım R3-1 §2.4): kuyruğa alma çağıranın
 * iş parçacığında VERİTABANINA DOKUNMAZ (I-2), birleştirme en büyük seq'i tutar ve seq kaynak okumasından ÖNCE alınır,
 * değişmeyen değer yazılmaz, üst sınır + kirli işaret, hata sayaçları + dakikada bir WARN, tablo yokken sessiz yeniden deneme,
 * liderlik olayları sıfırlar, çit nabzı 0 satır → düşür + dur, kip off → yazma yok + boşluk, kapanış ortak süreyle sınırlı.
 * Her test kendi H2 veritabanında ({@link MultiPodTestSupport}; kapanışta silinir).
 */
class EvalStateRecorderTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicLong clock = new AtomicLong(1_000_000L);
    final AtomicBoolean leader = new AtomicBoolean(true);
    final AtomicReference<EvalStateMode> mode = new AtomicReference<>(EvalStateMode.WRITE);
    final FakeSource src = new FakeSource();
    final List<EvalStateRecorder> built = new ArrayList<>();

    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(EvalStateRecorder.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        for (EvalStateRecorder r : built) r.stop();
        mp.close();
    }

    private void schema() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
    }

    private EvalStateRecorder recorder(JdbcTemplate jdbc, String writer) {
        return recorder(jdbc, writer, EvalStateRecorder.MAX_PENDING);
    }

    private EvalStateRecorder recorder(JdbcTemplate jdbc, String writer, int maxPending) {
        EvalStateRecorder r = EvalStateRecorder.builder(jdbc).writer(writer).leaderHint(leader::get).mode(mode::get)
                .clock(clock::get).flushMs(50).lastStatusRefreshMs(120_000).maxPending(maxPending).build();
        r.bindSource(src);
        built.add(r);
        return r;
    }

    private EvalStateRecorder recorder() {
        schema();
        return recorder(mp.jdbc(), "pod1-00000001");
    }

    private static SweepItem item(String type, long monitorId, String domain, boolean up, String error) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("team_id", 3L);
        ctx.put("standalone", true);
        return new SweepItem(type, domain, "443", up, error, ctx, null);
    }

    private Integer countOf(String domain) {
        List<Integer> v = mp.jdbc().queryForList("SELECT recovery_up_count FROM monitor_eval_state WHERE domain = ?",
                Integer.class, domain);
        return v.isEmpty() ? null : v.get(0);
    }

    private int rows(String table) {
        return mp.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long warnCount() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    // ── Kuyruğa alma: veritabanı yalnız yazıcı iş parçacığında (I-2) ───────────────────────────────────────────────

    @Test
    @DisplayName("I-2: kuyruğa alma çağıranın iş parçacığında hiç bağlantı açmaz; tüm JDBC 'eval-state-writer'da")
    void enqueueDoesNoJdbcOnCallerThread() {
        schema();
        List<String> threads = new CopyOnWriteArrayList<>();
        DelegatingDataSource spy = new DelegatingDataSource(mp.dataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                threads.add(Thread.currentThread().getName());
                return super.getConnection();
            }
        };
        EvalStateRecorder r = recorder(new JdbcTemplate(spy), "pod1-00000001");
        src.recovery.put("PORT_DOWN:a.example", new RecoveryValues(2, 1L, null, null));
        src.types.put("PORT_DOWN", new TypeValues(true, true, 30_000L));

        // Yazıcı yokken bile çağrılar bağlantı açmaz
        r.recovery("PORT_DOWN:a.example", "PORT_DOWN", "a.example");
        r.observation("PORT_DOWN", item("PORT_DOWN", 7, "a.example", false, "Connection refused"), false, clock.get());
        r.typeState("PORT_DOWN");
        SweepItem it = item("PORT_DOWN", 7, "a.example", false, "x");
        r.chainStart("PORT_DOWN:a.example:443", 1, it, new ConfirmState("PORT_DOWN", "a.example", "443", 0, 3,
                "2026-10-23T10:00:00", clock.get() + 30_000), "2026-10-23T10:00:00", 30_000);
        r.chainProgress("PORT_DOWN:a.example:443", 1, new ConfirmState("PORT_DOWN", "a.example", "443", 1, 3,
                "2026-10-23T10:00:00", clock.get() + 60_000), List.of(Map.of("status", "down")));
        r.chainFinishing("PORT_DOWN:a.example:443", 1, 1);
        r.sweepEnd("PORT_DOWN");
        r.status();
        assertThat(threads).as("kuyruğa alma / durum veritabanına gitmez").isEmpty();

        r.start();
        assertThat(r.awaitFlushed(r.seq(), 10_000)).isTrue();   // test iş parçacığı yalnız bekler
        r.chainEnd("PORT_DOWN:a.example:443", 1);
        r.sweepEnd("PORT_DOWN");
        assertThat(r.awaitFlushed(r.seq(), 10_000)).isTrue();
        assertThat(threads).isNotEmpty().allMatch(EvalStateRecorder.THREAD_NAME::equals);
        assertThat(countOf("a.example")).isEqualTo(2);
        assertThat(rows("monitor_last_status")).isEqualTo(1);
        assertThat(rows("monitor_confirmations")).as("zincir bitti → silindi").isZero();
    }

    @Test
    @DisplayName("Birleştirme: aynı anahtarın üç değişimi tek satır, son değer ve en büyük seq")
    void coalescingKeepsHighestSeqAndLatestValue() {
        EvalStateRecorder r = recorder();
        for (int v = 1; v <= 3; v++) {
            src.recovery.put("PING_DOWN:h", new RecoveryValues(v, (long) v, null, null));
            r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        }
        assertThat(r.status().pending()).isEqualTo(1);
        assertThat(r.flushNow()).isTrue();
        var stored = r.sql().readEvalState(r.generation());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).row().upCount()).isEqualTo(3);
        assertThat(stored.get(0).row().seq()).isEqualTo(r.seq());
        assertThat(r.status().pending()).isZero();
        assertThat(r.flushedSeq()).isEqualTo(r.seq());
    }

    @Test
    @DisplayName("seq kaynak okumasından ÖNCE alınır: okunan değer o seq'le yazılır")
    void seqAllocatedBeforeReadingSource() {
        EvalStateRecorder r = recorder();
        AtomicLong seqAtRead = new AtomicLong(-1);
        src.onRecoveryRead = () -> seqAtRead.set(r.seq());
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.flushNow();
        long rowSeq = r.sql().readEvalState(r.generation()).get(0).row().seq();
        assertThat(seqAtRead.get()).as("okuma anında seq zaten ayrılmıştı").isEqualTo(rowSeq).isPositive();
    }

    @Test
    @DisplayName("Değişmeyen değer yazılmaz: sayaçsız alan adının sıfırlaması, aynı durumdaki gözlem (tazeleme süresi içinde)")
    void noOpFilter() {
        EvalStateRecorder r = recorder();
        for (int i = 0; i < 5; i++) r.recovery("HTTP_DOWN:ok.example", "HTTP_DOWN", "ok.example");   // NONE
        assertThat(r.status().pending()).as("sıfır yazım").isZero();
        src.recovery.put("HTTP_DOWN:ok.example", new RecoveryValues(1, 5L, null, null));
        r.recovery("HTTP_DOWN:ok.example", "HTTP_DOWN", "ok.example");
        src.recovery.remove("HTTP_DOWN:ok.example");
        r.recovery("HTTP_DOWN:ok.example", "HTTP_DOWN", "ok.example");
        r.flushNow();
        assertThat(countOf("ok.example")).as("sıfırlama satırı silmez, sıfır yazar").isZero();
        r.recovery("HTTP_DOWN:ok.example", "HTTP_DOWN", "ok.example");
        assertThat(r.status().pending()).isZero();

        SweepItem down = item("PORT_DOWN", 9, "p.example", false, "Connection timed out");
        r.observation("PORT_DOWN", down, false, clock.get());
        r.flushNow();
        long first = mp.jdbc().queryForObject("SELECT observed_ms FROM monitor_last_status", Long.class);
        r.observation("PORT_DOWN", down, false, clock.get() + 60_000);
        assertThat(r.status().pending()).as("aynı durum, 120 sn içinde").isZero();
        r.observation("PORT_DOWN", down, true, clock.get() + 121_000);
        assertThat(r.status().pending()).as("tazeleme süresi doldu").isEqualTo(1);
        r.flushNow();
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT observed_ms, changed_ms, source, up, network_down, error_class "
                + "FROM monitor_last_status");
        assertThat(row.get("observed_ms")).isEqualTo(clock.get() + 121_000);
        assertThat(row.get("changed_ms")).as("durum değişmedi").isEqualTo(first);
        assertThat(String.valueOf(row.get("source")).trim()).isEqualTo("M");
        assertThat(row).containsEntry("up", false).containsEntry("network_down", true).containsEntry("error_class", "NETWORK");
        r.observation("PORT_DOWN", item("PORT_DOWN", 9, "p.example", true, null), false, clock.get() + 130_000);
        r.flushNow();
        Map<String, Object> up = mp.jdbc().queryForMap("SELECT changed_ms, up, network_down, error_class FROM monitor_last_status");
        assertThat(up).containsEntry("up", true).containsEntry("network_down", false).containsEntry("changed_ms", clock.get() + 130_000);
        assertThat(up.get("error_class")).isNull();
    }

    @Test
    @DisplayName("Üst sınır: yeni anahtar düşer + sayılır + kirli; yeniden kuyruğa alınınca kirli kalkar")
    void capDropsNewKeysAndMarksDirty() {
        schema();
        EvalStateRecorder r = recorder(mp.jdbc(), "pod1", 2);
        for (String d : List.of("a", "b", "c")) {
            src.recovery.put("PORT_DOWN:" + d, new RecoveryValues(1, 1L, null, null));
            r.recovery("PORT_DOWN:" + d, "PORT_DOWN", d);
        }
        assertThat(r.status().dropped()).isEqualTo(1);
        assertThat(r.status().pending()).isEqualTo(2);
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PORT_DOWN:c", r.seq())).as("kirli").isTrue();
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PORT_DOWN:a", r.seq())).isFalse();
        r.flushNow();
        r.recovery("PORT_DOWN:c", "PORT_DOWN", "c");
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PORT_DOWN:c", r.seq())).isFalse();
        r.flushNow();
        assertThat(countOf("c")).isEqualTo(1);
    }

    @Test
    @DisplayName("Yazım hatası: sayaç + yalnız BİR WARN / dk, işlemler beklemede kalır, veritabanı dönünce yazılır")
    void failureCountersAndOneWarnPerMinute() {
        schema();
        AtomicBoolean down = new AtomicBoolean();
        DelegatingDataSource flaky = new DelegatingDataSource(mp.dataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                if (down.get()) throw new SQLException("bağlantı yok (test)");
                return super.getConnection();
            }
        };
        EvalStateRecorder r = recorder(new JdbcTemplate(flaky), "pod1");
        src.recovery.put("PING_DOWN:h", new RecoveryValues(2, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        down.set(true);
        assertThat(r.flushNow()).isFalse();
        assertThat(r.flushNow()).isFalse();
        assertThat(r.status().writeFailures()).isEqualTo(2);
        assertThat(r.status().lastFailure()).isNotBlank().doesNotContain("PING_DOWN").doesNotContain("SELECT");
        assertThat(r.status().lastFailureMs()).isEqualTo(clock.get());
        assertThat(r.status().pending()).as("beklemede kalır").isEqualTo(1);
        assertThat(warnCount()).as("dakikada bir WARN").isEqualTo(1);
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow().getFormattedMessage())
                .contains("karar etkilenmez");
        down.set(false);
        assertThat(r.flushNow()).isTrue();
        assertThat(countOf("h")).isEqualTo(2);
        assertThat(r.status().pending()).isZero();
        assertThat(r.status().flushes()).isEqualTo(1);
    }

    @Test
    @DisplayName("Tablolar yok: sessiz (hata sayılmaz, WARN yok), işlemler korunur; yama gelince yazılır")
    void tableMissingSilentRetry() {
        EvalStateRecorder r = recorder(mp.jdbc(), "pod1");   // şema YOK
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.flushNow()).isFalse();
        assertThat(r.status().tableMissing()).isTrue();
        assertThat(r.status().active()).isFalse();
        assertThat(r.status().writeFailures()).isZero();
        assertThat(warnCount()).isZero();
        assertThat(r.status().pending()).isEqualTo(1);
        schema();
        assertThat(r.flushNow()).isTrue();
        assertThat(r.status().tableMissing()).isFalse();
        assertThat(countOf("h")).isEqualTo(1);
    }

    @Test
    @DisplayName("Liderlik: LOST bekleyeni ve kuşağı temizler, takipçi yazmaz; GAINED yeni kuşak + devralınan + ABANDONED")
    void leadershipEvents() {
        EvalStateRecorder r = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(2, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        SweepItem it = item("PING_DOWN", 5, "h", false, "timeout");
        r.chainStart("PING_DOWN:h:", 11, it, new ConfirmState("PING_DOWN", "h", null, 0, 3, null, 0), null, 30_000);
        r.flushNow();
        assertThat(r.generation()).isEqualTo(1);
        assertThat(r.status().inherited()).as("ilk kuşak: devralınan yok").isNotNull()
                .extracting(EvalStateSink.Inherited::previousGeneration).isNull();

        src.recovery.put("PING_DOWN:h", new RecoveryValues(3, 2L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        leader.set(false);
        r.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod1", clock.get(), SweepLeadershipChangedEvent.LOST));
        assertThat(r.status().pending()).isZero();
        assertThat(r.generation()).isZero();
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.status().pending()).as("takipçi kuyruğa almaz").isZero();
        assertThat(r.flushNow()).isFalse();
        assertThat(countOf("h")).as("LOST'tan önceki yazılmamış değer kaybolur (bellek de sıfırlandı)").isEqualTo(2);

        leader.set(true);
        clock.addAndGet(1_000);
        r.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", clock.get(), SweepLeadershipChangedEvent.GAINED));
        assertThat(r.flushNow()).isTrue();
        assertThat(r.generation()).isEqualTo(2);
        EvalStateSink.Inherited inh = r.status().inherited();
        assertThat(inh).isNotNull();
        assertThat(inh.previousGeneration()).isEqualTo(1L);
        assertThat(inh.recoveryCounters()).isEqualTo(1);
        assertThat(inh.abandonedChains()).isEqualTo(1);
        assertThat(mp.jdbc().queryForObject("SELECT state FROM monitor_confirmations", String.class)).isEqualTo("ABANDONED");
        assertThat(r.verificationContext().generationStartMs()).as("kapsam GAINED'de başlar").isEqualTo(clock.get());
    }

    @Test
    @DisplayName("Çit nabzı 0 satır (daha yeni lider kuşak aldı): bekleyen düşer, GAINED'e dek yazma yok")
    void fencingHeartbeatZeroRowsDropsAndStops() {
        EvalStateRecorder a = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isTrue();
        assertThat(new EvalStateSql(mp.jdbc()).acquireGeneration("pod2-00000002", clock.get())).isEqualTo(2);
        src.recovery.put("PING_DOWN:h", new RecoveryValues(5, 1L, null, null));
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isFalse();
        assertThat(a.generation()).isZero();
        assertThat(a.status().pending()).isZero();
        assertThat(countOf("h")).as("eski liderin değeri yazılmadı").isEqualTo(1);
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.status().pending()).as("çit dışı: kuyruğa almaz").isZero();
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("daha yeni bir lider"));
        a.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", clock.get(), SweepLeadershipChangedEvent.GAINED));
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isTrue();
        assertThat(a.generation()).isEqualTo(3);
        assertThat(countOf("h")).isEqualTo(5);
    }

    @Test
    @DisplayName("Kip off: kuyruğa alma yok, yazıcı iş yapmaz; geri açılınca boşluk kuralı (eski anahtarlar doğrulamada atlanır)")
    void modeOffNoWritesAndGap() {
        EvalStateRecorder r = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.flushNow();
        long before = r.seq();
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PING_DOWN:h", before)).isFalse();
        mode.set(EvalStateMode.OFF);
        src.recovery.put("PING_DOWN:h", new RecoveryValues(4, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.status().pending()).isZero();
        assertThat(r.flushNow()).isFalse();
        assertThat(r.status().mode()).isEqualTo("off");
        assertThat(r.status().active()).isFalse();
        assertThat(countOf("h")).isEqualTo(1);
        mode.set(EvalStateMode.VERIFY);
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PING_DOWN:h", r.seq()))
                .as("kip kapalıyken değişmiş olabilir — atlanır").isTrue();
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.flushNow();
        assertThat(countOf("h")).isEqualTo(4);
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PING_DOWN:h", r.seq())).isFalse();
    }

    @Test
    @DisplayName("Zincir yaşam döngüsü: başla → ilerle → son adım → bitir; eski zincirin geç bitişi yenisini silmez")
    void chainLifecycle() {
        EvalStateRecorder r = recorder();
        String key = "DNS_FAILURE:d.example:A";
        SweepItem it = item("DNS_FAILURE", 4, "d.example", false, "SERVFAIL");
        r.chainStart(key, 1, it, new ConfirmState("DNS_FAILURE", "d.example", "A", 0, 3, "2026-10-23T10:00:00", 5_000), null, 30_000);
        r.chainProgress(key, 1, new ConfirmState("DNS_FAILURE", "d.example", "A", 2, 3, "2026-10-23T10:00:00", 9_000),
                List.of(Map.of("n", 1), Map.of("n", 2)));
        r.flushNow();
        var row = r.sql().readConfirmations(r.generation()).get(0).row();
        assertThat(row.chainId()).isEqualTo(1);
        assertThat(row.attempt()).isEqualTo(2);
        assertThat(row.monitorType()).isEqualTo("dns");
        assertThat(row.monitorId()).isEqualTo(4L);
        assertThat(row.firstFailureAt()).isEqualTo("2026-10-23T10:00:00");
        assertThat(row.attemptsJson()).isEqualTo("[{\"n\":1},{\"n\":2}]");
        assertThat(row.ctxJson()).contains("\"monitor_id\":4");
        assertThat(row.delayMs()).isEqualTo(30_000);
        assertThat(row.state()).isEqualTo("RUNNING");
        r.chainFinishing(key, 1, 1);
        r.flushNow();
        assertThat(r.sql().readConfirmations(r.generation()).get(0).row().state()).isEqualTo("FINISHING");

        r.chainStart(key, 2, it, new ConfirmState("DNS_FAILURE", "d.example", "A", 0, 3, "2026-10-23T10:05:00", 5_000), null, 30_000);
        r.chainEnd(key, 1);   // eski zincirin geç bitişi
        r.chainProgress(key, 1, new ConfirmState("DNS_FAILURE", "d.example", "A", 3, 3, "x", 1), List.of());   // eski: yok sayılır
        r.flushNow();
        var rows = r.sql().readConfirmations(r.generation());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).row().chainId()).isEqualTo(2);
        assertThat(rows.get(0).row().attempt()).isZero();
        r.chainEnd(key, 2);
        r.flushNow();
        assertThat(rows("monitor_confirmations")).isZero();
    }

    @Test
    @DisplayName("Tür durumu: bastırma başlangıç anı geçişte, tur damgası 60 sn'de bir, aralık %10 içinde yazılmaz")
    void typeStateThrottles() {
        EvalStateRecorder r = recorder();
        src.types.put("PORT_DOWN", new TypeValues(false, true, 30_000L));
        r.typeState("PORT_DOWN");
        r.flushNow();
        long t0 = clock.get();
        clock.addAndGet(10_000);
        src.types.put("PORT_DOWN", new TypeValues(false, true, 32_000L));
        r.typeState("PORT_DOWN");
        assertThat(r.status().pending()).as("damga 60 sn dolmadı, aralık %10 içinde").isZero();
        src.types.put("PORT_DOWN", new TypeValues(true, true, 32_000L));
        r.typeState("PORT_DOWN");
        r.flushNow();
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT suppression_active, suppression_since_ms, last_scheduled_round_ms, "
                + "round_interval_ms FROM monitor_eval_type_state WHERE alert_type = 'PORT_DOWN'");
        assertThat(row).containsEntry("suppression_active", true).containsEntry("suppression_since_ms", clock.get())
                .containsEntry("last_scheduled_round_ms", t0).containsEntry("round_interval_ms", 30_000L);
        clock.addAndGet(60_000);
        src.types.put("PORT_DOWN", new TypeValues(true, true, 45_000L));
        r.typeState("PORT_DOWN");
        r.flushNow();
        row = mp.jdbc().queryForMap("SELECT suppression_since_ms, last_scheduled_round_ms, round_interval_ms "
                + "FROM monitor_eval_type_state WHERE alert_type = 'PORT_DOWN'");
        assertThat(row).containsEntry("suppression_since_ms", t0 + 10_000).containsEntry("last_scheduled_round_ms", clock.get())
                .containsEntry("round_interval_ms", 45_000L);
        r.typeState(EvalStateKeys.GENERATION_ROW);   // '*' asla tür satırı değildir
        assertThat(r.status().pending()).isZero();
    }

    @Test
    @DisplayName("Doğrulayıcı yazıcı iş parçacığında çağrılır: bağlam, rapor saklanır; fırlatırsa bilinmeyen rapor")
    void verifierIsCalledAndReportStored() {
        schema();
        AtomicReference<EvalStateVerification> v = new AtomicReference<>();
        EvalStateRecorder r = EvalStateRecorder.builder(mp.jdbc()).writer("pod1").leaderHint(leader::get).mode(mode::get)
                .clock(clock::get).verifier(v::get).build();
        r.bindSource(src);
        built.add(r);
        assertThat(r.verifyNow()).as("doğrulayıcı yok").isNull();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.flushNow();
        List<String> seen = new ArrayList<>();
        v.set(ctx -> {
            seen.add(ctx.writer() + "/" + ctx.generation() + "/" + ctx.lastStatusRefreshMs());
            long s0 = ctx.seq();
            assertThat(ctx.awaitFlushed(s0, 1_000)).isTrue();
            assertThat(ctx.changedSince(EvalStateKeys.Table.EVAL_STATE, "PING_DOWN:h", s0)).isFalse();
            return new EvalVerifyReport(ctx.nowMs(), ctx.writer(), ctx.generation(), EvalVerifyReport.STATUS_OK, null,
                    Map.of(), List.of());
        });
        EvalVerifyReport rep = r.verifyNow();
        assertThat(seen).containsExactly("pod1/1/120000");
        assertThat(rep.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(r.status().lastVerify()).isSameAs(rep);
        v.set(ctx -> { throw new IllegalStateException("boom"); });
        EvalVerifyReport unknown = r.verifyNow();
        assertThat(unknown.unknown()).isTrue();
        assertThat(unknown.reason()).isEqualTo(EvalStateRecorder.REASON_VERIFIER_ERROR);
        assertThat(r.status().lastVerify()).isSameAs(unknown);
    }

    @Test
    @DisplayName("awaitFlushed: yazıcı yokken kendisi boşaltır; mevcut seq'ten büyüğü beklemez")
    void awaitFlushedInline() {
        EvalStateRecorder r = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.awaitFlushed(r.seq(), 1_000)).isTrue();
        assertThat(countOf("h")).isEqualTo(1);
    }

    @Test
    @DisplayName("Asla fırlatmaz: kaynak fırlatır, null kalem / tür, bağlanmamış kaynak, çok uzun alan adı (kirli + sayılır)")
    void neverThrowsIntoCaller() {
        EvalStateRecorder r = recorder();
        src.throwing = true;
        assertThatCode(() -> {
            r.recovery("PING_DOWN:h", "PING_DOWN", "h");
            r.typeState("PING_DOWN");
            r.observation("PING_DOWN", item("PING_DOWN", 1, "h", false, "x"), false, 1L);
            r.observation(null, null, false, 1L);
            r.recovery(null, null, null);
            r.chainStart(null, 1, null, null, null, 0);
            r.chainProgress("k", 1, null, null);
            r.chainFinishing("k", 1, 1);
            r.chainEnd("k", 1);
            r.sweepEnd(null);
        }).doesNotThrowAnyException();
        src.throwing = false;
        String longDomain = "x".repeat(EvalStateSql.DOMAIN_MAX + 1);
        r.recovery("PING_DOWN:" + longDomain, "PING_DOWN", longDomain);
        assertThat(r.unrecordableCount()).isEqualTo(1);
        assertThat(r.changedSince(EvalStateKeys.Table.EVAL_STATE, "PING_DOWN:" + longDomain, r.seq())).isTrue();

        EvalStateRecorder unbound = EvalStateRecorder.builder(mp.jdbc()).writer("pod9").build();
        built.add(unbound);
        unbound.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(unbound.status().pending()).as("kaynak bağlanmadan yok sayılır").isZero();
        assertThat(EvalStateSink.NOOP.status()).isNull();
    }

    @Test
    @DisplayName("Durum: kip, etkin, yazan, kuşak, sayaçlar — veritabanına gitmeden")
    void statusFields() {
        EvalStateRecorder r = recorder();
        EvalStateSink.Status s0 = r.status();
        assertThat(s0.mode()).isEqualTo("write");
        assertThat(s0.active()).as("kuşak yok").isFalse();
        assertThat(s0.generation()).isNull();
        assertThat(s0.writer()).isEqualTo("pod1-00000001");
        assertThat(s0.lastFlushMs()).isNull();
        r.flushNow();
        EvalStateSink.Status s1 = r.status();
        assertThat(s1.active()).isTrue();
        assertThat(s1.generation()).isEqualTo(1L);
        assertThat(s1.lastFlushMs()).isEqualTo(clock.get());
        assertThat(s1.flushes()).isEqualTo(1);
        leader.set(false);
        assertThat(r.status().active()).as("lider ipucu false").isFalse();
    }

    @Test
    @DisplayName("B sözleşmesi: active = tam olarak kuşak sahibi olarak yazarken (kip, lider, kuşak, tablo); lastVerify durumu ne olursa kalır")
    void activeExactlyWhileWritingAsGenerationHolder() {
        AtomicReference<EvalStateVerification> v = new AtomicReference<>();
        EvalStateRecorder r = EvalStateRecorder.builder(mp.jdbc()).writer("pod1").leaderHint(leader::get).mode(mode::get)
                .clock(clock::get).verifier(v::get).build();
        r.bindSource(src);
        built.add(r);
        assertThat(r.status().active()).as("tablo yok, kuşak yok").isFalse();
        r.flushNow();
        assertThat(r.status().tableMissing()).isTrue();
        assertThat(r.status().active()).as("tablo yok").isFalse();
        schema();
        r.flushNow();
        assertThat(r.status().active()).as("kip yazar + lider + kuşak + tablo").isTrue();
        mode.set(EvalStateMode.OFF);
        assertThat(r.status().active()).as("kip off (kuşak tutulsa da)").isFalse();
        mode.set(EvalStateMode.VERIFY);
        assertThat(r.status().active()).isTrue();
        leader.set(false);
        assertThat(r.status().active()).as("lider ipucu false").isFalse();
        leader.set(true);
        v.set(ctx -> EvalVerifyReport.unknown(ctx.nowMs(), ctx.writer(), ctx.generation(), EvalVerifyReport.REASON_FLUSH_LAG));
        EvalVerifyReport unknown = r.verifyNow();
        assertThat(r.status().lastVerify()).as("bilinmeyen tur da saklanır").isSameAs(unknown);
        v.set(null);
        assertThat(r.verifyNow()).isNull();
        assertThat(r.status().lastVerify()).as("doğrulayıcı yokken son tur korunur").isSameAs(unknown);
        assertThat(new EvalStateSql(mp.jdbc()).acquireGeneration("pod2", clock.get())).isPositive();
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.flushNow();   // çit nabzı 0 satır
        assertThat(r.status().active()).as("çit dışı").isFalse();
        assertThat(r.status().generation()).isNull();
        assertThat(r.status().lastVerify()).as("çitte de son tur korunur").isSameAs(unknown);
        r.onContextClosed(null);
        r.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", clock.get(), SweepLeadershipChangedEvent.GAINED));
        assertThat(r.flushNow()).as("kapanışta boşaltma yok").isFalse();
        assertThat(r.status().active()).as("kapanışta yazmaz").isFalse();
        assertThat(r.generation()).isZero();
    }

    @Test
    @DisplayName("B sözleşmesi: Spring'te kaydedici EvalStateSink türüyle görünür; olay dinleyicisi bağlı; kapanışta durur")
    void registeredAsEvalStateSinkBean() {
        schema();
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(JdbcTemplate.class, mp::jdbc);
            ctx.register(EvalStateRecorder.class);
            ctx.refresh();
            assertThat(ctx.getBeanNamesForType(EvalStateSink.class)).hasSize(1);
            EvalStateSink sink = ctx.getBeanProvider(EvalStateSink.class).getIfAvailable();
            assertThat(sink).isInstanceOf(EvalStateRecorder.class);
            EvalStateSink.Status s = sink.status();
            assertThat(s).isNotNull();
            assertThat(s.mode()).as("ayar servisi yok → varsayılan").isEqualTo("verify");
            assertThat(s.active()).as("zamanlayıcı yok → lider ipucu false").isFalse();
            ctx.publishEvent(new SweepLeadershipChangedEvent(true, "x", 1L, SweepLeadershipChangedEvent.GAINED));
            assertThat(((EvalStateRecorder) sink).generation()).isZero();
        }
    }

    @Test
    @DisplayName("Kapanış: ContextClosedEvent beklemez / boşaltmaz; yok etme ShutdownDeadline ile sınırlı birleşir")
    void shutdownUsesTheSharedDeadline() throws Exception {
        String srcText = Files.readString(Path.of("src/main/java/com/sitemonitor/service/evalstate/EvalStateRecorder.java"),
                StandardCharsets.UTF_8);
        assertThat(srcText).contains("ShutdownDeadline.waitMs(SHUTDOWN_JOIN_CAP_MS)").contains(".join(budget)");
        int closed = srcText.indexOf("public void onContextClosed(");
        assertThat(closed).isPositive();
        String body = srcText.substring(closed, srcText.indexOf("\n    }", closed));
        assertThat(body).doesNotContain("flush").doesNotContain("join").doesNotContain("stop(");
        assertThat(EvalStateRecorder.SHUTDOWN_JOIN_CAP_MS).isLessThan(com.sitemonitor.config.ShutdownDeadline.DESTROY_BUDGET_MS);

        EvalStateRecorder r = recorder();
        r.start();
        Thread w = r.worker();
        assertThat(w).isNotNull();
        assertThat(w.getName()).isEqualTo(EvalStateRecorder.THREAD_NAME);
        assertThat(w.isDaemon()).isTrue();
        r.onContextClosed(null);
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.status().pending()).as("kapanışta kuyruğa alma yok").isZero();
        long t0 = System.nanoTime();
        r.stop();
        assertThat(System.nanoTime() - t0).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        w.join(2_000);
        assertThat(w.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Kardeş TTL'i MonitoringOutageService ile aynı (paket-özel sabitin aynası)")
    void siblingTtlMirrorsMos() throws Exception {
        var f = MonitoringOutageService.class.getDeclaredField("SIBLING_TTL_MS");
        f.setAccessible(true);
        assertThat(EvalStateRecorder.SIBLING_TTL_MS).isEqualTo(f.getLong(null));
    }

    @Test
    @DisplayName("Kaynak verildiğinde gözlem ağ sınıfını yazar; ham hata metni ASLA yazılmaz")
    void observationStoresClassNotText() {
        EvalStateRecorder r = recorder();
        r.observation("HTTP_DOWN", item("HTTP_DOWN", 2, "w.example", false, "secret-token=abc Connection reset"), false, 1L);
        r.flushNow();
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT * FROM monitor_last_status");
        assertThat(row.values()).noneMatch(v -> v != null && String.valueOf(v).contains("secret-token"));
        assertThat(row).containsEntry("error_class", "NETWORK").containsEntry("monitor_id", 2L).containsEntry("team_id", 3L)
                .containsEntry("standalone", true).containsEntry("item_key", "m:2");
    }

    // ── R3-1 FX-STATE ───────────────────────────────────────────────────────────────────────────────────────────────

    private void lease(String owner) {
        mp.jdbc().update("DELETE FROM monitor_check_schedule WHERE monitor_key = ?", EvalStateSql.LEASE_KEY);
        mp.jdbc().update("INSERT INTO monitor_check_schedule (monitor_key, next_due_at, claimed_by, claimed_at) "
                + "VALUES (?, ?, ?, ?)", EvalStateSql.LEASE_KEY, Long.MAX_VALUE, owner, 0L);
    }

    private EvalStateRecorder leaseBound(String writer, AtomicBoolean hint) {
        EvalStateRecorder r = EvalStateRecorder.builder(mp.jdbc()).writer(writer).leaderHint(hint::get).mode(mode::get)
                .clock(clock::get).flushMs(50).leaseOwner(writer).build();
        r.bindSource(src);
        built.add(r);
        return r;
    }

    private Integer dbCount(long gen, String key) {
        return mp.jdbc().query("SELECT recovery_up_count FROM monitor_eval_state WHERE alert_type || ':' || domain = ? "
                + "AND gen = ?", (rs, i) -> rs.getInt(1), key, gen).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("FX #1: kirayı kaybetmiş eski liderin (ipucu hâlâ true) geç kuşak denemesi kira sahibini çitleyemez")
    void staleFormerLeaderLateAcquire_cannotFenceLeaseHolder() {
        schema();
        lease("podB-0000000b");
        AtomicBoolean hintA = new AtomicBoolean(true);
        EvalStateRecorder a = leaseBound("podA-0000000a", hintA);
        EvalStateRecorder b = leaseBound("podB-0000000b", new AtomicBoolean(true));
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        b.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(b.flushNow()).isTrue();
        assertThat(b.generation()).isEqualTo(1);

        assertThat(a.flushNow()).as("eski lider: kira B'de → kuşak yok").isFalse();
        assertThat(a.generation()).isZero();
        hintA.set(false);
        a.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "podA", clock.get(), SweepLeadershipChangedEvent.LOST));

        for (int i = 0; i < 3; i++) {
            clock.addAndGet(120_000);
            src.recovery.put("PING_DOWN:h", new RecoveryValues(5 + i, 2L + i, null, null));
            b.recovery("PING_DOWN:h", "PING_DOWN", "h");
            assertThat(b.flushNow()).as("gerçek lider yazmaya devam eder").isTrue();
        }
        assertThat(b.status().active()).isTrue();
        assertThat(b.status().fenced()).isFalse();
        assertThat(b.generation()).isEqualTo(1);
        assertThat(countOf("h")).isEqualTo(7);
        assertThat(b.sql().generationRow().writer()).isEqualTo("podB-0000000b");
    }

    @Test
    @DisplayName("FX #1: çitlenen lider, kuşak sahibi 3 nabız sessiz kalınca kendini onarır; sahip nabız atarken onarmaz")
    void fencedLeaderRecoversWhenHolderSilent() {
        EvalStateRecorder a = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isTrue();
        EvalStateSql other = new EvalStateSql(mp.jdbc());
        assertThat(other.acquireGeneration("pod2-00000002", clock.get())).isEqualTo(2);   // ör. kısa süre kira tutup kapanan pod
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isFalse();
        assertThat(a.status().fenced()).isTrue();
        assertThat(a.status().generation()).isNull();

        clock.addAndGet(61_000);
        assertThat(a.flushNow()).as("sahip yeni (61 sn) — onarma yok").isFalse();
        assertThat(other.heartbeat(2, "pod2-00000002", clock.get())).isTrue();
        clock.addAndGet(61_000);
        assertThat(a.flushNow()).as("sahip nabız attı — onarma yok").isFalse();
        assertThat(a.status().fenced()).isTrue();

        clock.addAndGet(200_000);   // sahip 261 sn sessiz (> 3 × 60 sn)
        assertThat(a.flushNow()).as("onarıldı, yeni kuşak").isTrue();
        assertThat(a.status().fenced()).isFalse();
        assertThat(a.generation()).isEqualTo(3);
        src.recovery.put("PING_DOWN:h", new RecoveryValues(4, 9L, null, null));
        a.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(a.flushNow()).isTrue();
        assertThat(dbCount(3, "PING_DOWN:h")).isEqualTo(4);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("çitten çıkıp"));
    }

    @Test
    @DisplayName("FX #2: kip off → on aynı liderlikte kuşağı bırakır; sonraki sıfırlamada bayat satır güncel kuşakta kalmaz")
    void modeOffOnThenReset_noStaleRowCompared() {
        EvalStateRecorder r = recorder();
        String k = "PING_DOWN:h";
        src.recovery.put(k, new RecoveryValues(2, 1L, null, null));
        r.recovery(k, "PING_DOWN", "h");
        assertThat(r.flushNow()).isTrue();
        long gen = r.generation();
        assertThat(dbCount(gen, k)).isEqualTo(2);

        mode.set(EvalStateMode.OFF);
        r.recovery("PING_DOWN:other", "PING_DOWN", "other");
        r.flushNow();
        assertThat(r.status().generation()).as("kip kapalı: kuşak bırakıldı").isNull();
        mode.set(EvalStateMode.VERIFY);

        src.recovery.remove(k);   // MOS sayacı sıfırladı (alarm kapandı / alan adı düştü)
        r.recovery(k, "PING_DOWN", "h");
        assertThat(r.flushNow()).isTrue();
        assertThat(r.generation()).as("geri dönüşte YENİ kuşak").isGreaterThan(gen);
        assertThat(dbCount(r.generation(), k)).as("bayat sayaç güncel kuşakta yok (doğrulayıcı görmez)").isNull();
    }

    @Test
    @DisplayName("FX #2: kuşak tutarken hiç işlem yokken de kip off kuşağı bırakır (boş görülen harita)")
    void modeOffWithoutStateStillDropsGeneration() {
        EvalStateRecorder r = recorder();
        assertThat(r.flushNow()).isTrue();   // kuşak boşaltmayla alındı, hiç işlem yok
        long gen = r.generation();
        mode.set(EvalStateMode.OFF);
        r.flushNow();
        assertThat(r.generation()).isZero();
        mode.set(EvalStateMode.WRITE);
        assertThat(r.flushNow()).isTrue();
        assertThat(r.generation()).isGreaterThan(gen);
    }

    @Test
    @DisplayName("FX #3: eski kuşağın bayat sayacı sonraki liderliklerde devralınmış sayılmaz; 7 günden eskiyse budanır")
    void staleOldGenerationCounterNotInheritedAndPruned() {
        EvalStateRecorder r = recorder();
        src.recovery.put("PING_DOWN:h", new RecoveryValues(2, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        assertThat(r.flushNow()).isTrue();
        for (int i = 0; i < 3; i++) {
            src.recovery.clear();
            r.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", clock.get(), SweepLeadershipChangedEvent.GAINED));
            assertThat(r.flushNow()).isTrue();
            clock.addAndGet(8L * 24 * 60 * 60 * 1000);
        }
        assertThat(r.generation()).isEqualTo(4);
        assertThat(r.status().inherited().recoveryCounters()).as("yalnız kuşak 3'ün satırları").isZero();
        assertThat(r.status().inherited().previousGeneration()).isEqualTo(3L);
        long now = clock.get();
        r.sql().prune(r.generation(), now - 7L * 24 * 3600 * 1000, now - 7L * 24 * 3600 * 1000, now - 3600_000L);
        assertThat(dbCount(1, "PING_DOWN:h")).as("eski kuşağın bayat sayacı budandı").isNull();
    }

    @Test
    @DisplayName("FX #6: üretim kaydedicisinin ifadeleri sorgu zaman aşımlı kendi şablonunda; paylaşılan şablon değişmez")
    void productionRecorderUsesQueryTimeout() {
        EvalStateRecorder r = productionRecorder(null);
        assertThat(r.sql().jdbc().getQueryTimeout()).isEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
        assertThat(mp.jdbc().getQueryTimeout()).isNotEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
        assertThat(EvalStateRecorder.builder(mp.jdbc()).queryTimeoutSeconds(3).build().sql().jdbc().getQueryTimeout())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("FX #6: kaydedicinin HER ifadesi sorgu zaman aşımıyla koşar (kuşak, nabız, toplu yazım, okuma, budama)")
    void everyStatementCarriesTheQueryTimeout() {
        schema();
        List<Integer> timeouts = new CopyOnWriteArrayList<>();
        AtomicInteger statements = new AtomicInteger();
        DelegatingDataSource spying = new DelegatingDataSource(mp.dataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                return timeoutSpy(super.getConnection(), timeouts, statements);
            }
        };
        EvalStateRecorder r = EvalStateRecorder.builder(new JdbcTemplate(spying)).writer("pod1").leaderHint(leader::get)
                .mode(mode::get).clock(clock::get).queryTimeoutSeconds(7).build();
        r.bindSource(src);
        built.add(r);
        src.recovery.put("PING_DOWN:h", new RecoveryValues(1, 1L, null, null));
        r.recovery("PING_DOWN:h", "PING_DOWN", "h");
        r.observation("PORT_DOWN", item("PORT_DOWN", 9, "p.example", false, "Connection timed out"), false, clock.get());
        assertThat(r.flushNow()).isTrue();
        r.sql().prune(r.generation(), 0L, 0L, 0L);
        r.sql().readEvalState(r.generation());
        assertThat(statements.get()).isGreaterThan(5);
        assertThat(timeouts).hasSize(statements.get()).containsOnly(7);
    }

    /** Bağlantı vekili: açılan her ifadeyi sayar ve {@code setQueryTimeout} değerlerini kaydeder. */
    static Connection timeoutSpy(Connection real, List<Integer> timeouts, AtomicInteger statements) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(EvalStateRecorderTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                    Object out;
                    try {
                        out = m.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (!(out instanceof java.sql.Statement st)) return out;
                    statements.incrementAndGet();
                    Class<?> iface = out instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class
                            : java.sql.Statement.class;
                    return java.lang.reflect.Proxy.newProxyInstance(EvalStateRecorderTest.class.getClassLoader(),
                            new Class<?>[] {iface}, (p2, m2, a2) -> {
                                if ("setQueryTimeout".equals(m2.getName())) timeouts.add((Integer) a2[0]);
                                try {
                                    return m2.invoke(st, a2);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                });
    }

    @Test
    @DisplayName("FX F3: aynı durumdaki kalemin alan adı değişince tazeleme süresi beklenmeden yazılır")
    void domainChangeIsAStateChange() {
        EvalStateRecorder r = recorder();
        r.observation("PORT_DOWN", item("PORT_DOWN", 9, "old.example", false, "Connection timed out"), false, clock.get());
        r.flushNow();
        r.observation("PORT_DOWN", item("PORT_DOWN", 9, "renamed.example", false, "Connection timed out"), false,
                clock.get() + 30_000);
        assertThat(r.status().pending()).as("alan adı durumun parçası").isEqualTo(1);
        r.flushNow();
        assertThat(mp.jdbc().queryForObject("SELECT domain FROM monitor_last_status", String.class)).isEqualTo("renamed.example");
    }

    @Test
    @DisplayName("FX F4: kimlikli kalemin satırı seq'ten SONRA bellekten okunur — ters sırada gelen eski DOWN yeni UP'ı ezemez")
    void observationFollowsMemoryAfterSeq() {
        EvalStateRecorder r = recorder();
        src.downSupported = true;
        SweepItem down = item("PORT_DOWN", 9, "p.example", false, "Connection timed out");
        SweepItem up = item("PORT_DOWN", 9, "p.example", true, null);
        // Bellek: T1 DOWN koydu, T2 UP ile kaldırdı → kayıt yok. Kancalar TERS sırada: önce T2'nin UP'ı, sonra T1'in DOWN'u.
        r.observation("PORT_DOWN", up, false, clock.get());
        r.observation("PORT_DOWN", down, false, clock.get());
        r.flushNow();
        assertThat(mp.jdbc().queryForObject("SELECT up FROM monitor_last_status", Boolean.class))
                .as("satır belleği izler (UP)").isTrue();

        // Bellekte DOWN var (alan adı / ekip bellekten), geç gelen UP kancası onu ezemez.
        clock.addAndGet(1_000);
        src.downs.put("PORT_DOWN|9", new EvalStateSource.DownValues(true, "p.example", "443", 7L, false,
                EvalStateSource.ERROR_NETWORK, clock.get()));
        r.observation("PORT_DOWN", down, false, clock.get());
        r.observation("PORT_DOWN", up, false, clock.get());
        r.flushNow();
        Map<String, Object> row = mp.jdbc().queryForMap("SELECT up, network_down, team_id, standalone FROM monitor_last_status");
        assertThat(row).containsEntry("up", false).containsEntry("network_down", true).containsEntry("team_id", 7L)
                .containsEntry("standalone", false);

        // Kimliksiz kalem: çağıranın değerleri (bellek sorulmaz).
        SweepItem noId = new SweepItem("ACCESSIBILITY", "h.example", "443", false, "Connection refused", Map.of(), null);
        r.observation("ACCESSIBILITY", noId, false, clock.get());
        r.flushNow();
        assertThat(mp.jdbc().queryForObject("SELECT up FROM monitor_last_status WHERE item_key = 'd:h.example|443'",
                Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("FX F2: budanan gözlem satırı siler (yalnız bir kez); bellekte yeniden DOWN varsa silmez, DOWN yazar")
    void prunedObservationDeletesRow() {
        EvalStateRecorder r = recorder();
        src.downSupported = true;
        SweepItem down = item("PORT_DOWN", 9, "p.example", false, "Connection timed out");
        src.downs.put("PORT_DOWN|9", new EvalStateSource.DownValues(true, "p.example", "443", 3L, true,
                EvalStateSource.ERROR_NETWORK, clock.get()));
        r.observation("PORT_DOWN", down, false, clock.get());
        r.flushNow();
        assertThat(rows("monitor_last_status")).isEqualTo(1);

        src.downs.clear();   // bellek budadı (duraklatıldı / TTL)
        r.observationPruned("PORT_DOWN", down);
        r.flushNow();
        assertThat(rows("monitor_last_status")).as("budanan kalemin satırı silindi").isZero();
        r.observationPruned("PORT_DOWN", down);
        assertThat(r.status().pending()).as("ikinci budama no-op").isZero();

        src.downs.put("PORT_DOWN|9", new EvalStateSource.DownValues(true, "p.example", "443", 3L, true,
                EvalStateSource.ERROR_NETWORK, clock.get()));
        r.observationPruned("PORT_DOWN", down);   // geç gelen budama kancası — bellekte yeniden DOWN var
        r.flushNow();
        assertThat(mp.jdbc().queryForObject("SELECT up FROM monitor_last_status", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("FX F7 + çit: durum 'fenced' ve kanca istisnası sayısını (MOS kaynağı + kaydedici) taşır; 14'lü kurucu eskisi gibi")
    void statusCarriesFencedAndHookFailures() {
        EvalStateRecorder r = recorder();
        src.hookFailures = 3;
        assertThat(r.status().hookFailures()).isEqualTo(3);
        assertThat(r.status().fenced()).isFalse();
        r.flushNow();
        new EvalStateSql(mp.jdbc()).acquireGeneration("pod2", clock.get());
        r.flushNow();
        assertThat(r.status().fenced()).isTrue();
        assertThat(r.status().active()).isFalse();
        EvalStateSink.Status legacy = new EvalStateSink.Status("verify", true, "w", 1L, 0, 0, 0, 0, null, null, null, false,
                null, null);
        assertThat(legacy.fenced()).isFalse();
        assertThat(legacy.hookFailures()).isZero();
    }

    @Test
    @DisplayName("Kip nit: tanınmayan ayar değeri bir kez WARN (değer değişince yeniden); geçerli / boş değer uyarmaz")
    void unknownModeWarnsOnce() {
        AtomicReference<String> raw = new AtomicReference<>("kapali");
        EvalStateRecorder r = productionRecorder(raw);
        r.status();
        r.status();
        assertThat(warnCount()).isEqualTo(1);
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow().getFormattedMessage())
                .contains("tanınmadı").contains("kapali").contains("verify");
        assertThat(r.status().mode()).isEqualTo("verify");
        raw.set("yazmaa");
        r.status();
        assertThat(warnCount()).isEqualTo(2);
        raw.set("off");
        r.status();
        raw.set(null);
        r.status();
        assertThat(warnCount()).isEqualTo(2);
    }

    /** Spring kurucusuyla (sağlayıcılar sahte) üretim kaydedicisi; {@code mode} null → ayar servisi yok. */
    @SuppressWarnings("unchecked")
    private EvalStateRecorder productionRecorder(AtomicReference<String> modeRaw) {
        org.springframework.beans.factory.ObjectProvider<com.sitemonitor.service.AppSettingsService> settings =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        if (modeRaw != null) {
            com.sitemonitor.service.AppSettingsService s = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
            org.mockito.Mockito.when(s.getString(org.mockito.ArgumentMatchers.eq(EvalStateMode.SETTING_KEY),
                    org.mockito.ArgumentMatchers.any())).thenAnswer(i -> modeRaw.get());
            org.mockito.Mockito.when(settings.getIfAvailable()).thenReturn(s);
        }
        EvalStateRecorder r = new EvalStateRecorder(mp.jdbc(), settings,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class), 1_000, 300_000, 120_000);
        built.add(r);
        return r;
    }

    /** Elle doldurulan sahte bellek. */
    static final class FakeSource implements EvalStateSource {
        final Map<String, RecoveryValues> recovery = new ConcurrentHashMap<>();
        final Map<String, TypeValues> types = new ConcurrentHashMap<>();
        /** "tür|kimlik" → bellekteki DOWN gözlemi (F4); {@link #downSupported} false iken sorgu desteklenmez (null). */
        final Map<String, DownValues> downs = new ConcurrentHashMap<>();
        volatile boolean downSupported;
        volatile long hookFailures;
        volatile Runnable onRecoveryRead = () -> {};
        volatile boolean throwing;

        @Override
        public DownValues downObservation(String alertType, long monitorId) {
            if (!downSupported) return null;
            return downs.getOrDefault(alertType + "|" + monitorId, DownValues.ABSENT);
        }

        @Override
        public long hookFailures() {
            return hookFailures;
        }

        @Override
        public RecoveryValues recoveryValues(String rkey) {
            if (throwing) throw new IllegalStateException("kaynak hatası");
            onRecoveryRead.run();
            return recovery.getOrDefault(rkey, RecoveryValues.NONE);
        }

        @Override
        public TypeValues typeValues(String alertType) {
            if (throwing) throw new IllegalStateException("kaynak hatası");
            return types.getOrDefault(alertType, TypeValues.NONE);
        }

        @Override
        public String errorClass(String error, String domain) {
            if (throwing) throw new IllegalStateException("kaynak hatası");
            if (error == null || error.isBlank()) return null;
            return error.contains("Connection") || error.contains("timed out") ? ERROR_NETWORK : ERROR_OTHER;
        }
    }
}
