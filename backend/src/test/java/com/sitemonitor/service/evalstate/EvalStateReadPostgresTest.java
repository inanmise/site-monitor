package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.TypeRow;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-2 A — devirde okuma GERÇEK PostgreSQL'de ({@code mvn -Ppostgres-it test}). Paylaşılan uygulamanın kendi
 * kaydedicisi / kirası ortak {@code '*'} satırını kullandığı için her test KENDİ şemasında koşar ({@code currentSchema}):
 * 3-1 biçimli tablolara üç işaret sütunu eklenir (iki kez, idempotent, eşzamanlı bir 3-1 nabız döngüsü sürerken);
 * {@code acquireGenerationWithPrior} kiraya bağlı, 8 iş parçacığı → her kuşağın tek kazananı, her kazananın öncülü yerine
 * geçtiği satır; {@code SQL_INTERMEDIATE} gerçek {@code monitor_check_schedule} satırlarında; yükleyici okumaları gerçek
 * türlerle (BOOLEAN / BIGINT null'ları); eski sahip bir bağlantıda boşaltırken halef öbüründe CAS + okuma yapar (hata yok,
 * kuşak süzgeci geçerli); devir işaretinden sonra 3-1 biçimli bir nabız işareti geçersiz kılar; gelen kutusu sorgusu
 * (inceleme 2026-10-28, MOS M-1: tablo yok → satır yok, boş → yok, satır → gözlemler).
 */
@PostgresIntegration
class EvalStateReadPostgresTest {

    /** 3-1 (20.168.x) tür tablosu — işaret sütunları yok. */
    static final String DDL_TYPE_STATE_R31 = "CREATE TABLE IF NOT EXISTS monitor_eval_type_state ("
            + "alert_type VARCHAR(40) NOT NULL PRIMARY KEY, suppression_active BOOLEAN, suppression_since_ms BIGINT, "
            + "last_scheduled_round_ms BIGINT, round_interval_ms BIGINT, writer VARCHAR(200) NOT NULL, gen BIGINT NOT NULL, "
            + "seq BIGINT NOT NULL, updated_ms BIGINT NOT NULL, updated_at VARCHAR(19) NOT NULL)";

    String schema;
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;

    @BeforeEach
    void isolatedSchema() {
        schema = "itr32_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PostgresIt.app().jdbc().execute("CREATE SCHEMA " + schema);
        PostgresIt.Config cfg = PostgresIt.config();
        String url = cfg.url() + (cfg.url().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        ds = new DriverManagerDataSource(url, cfg.user(), cfg.password());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(ClusterScheduleService.DDL);
        jdbc.execute(EvalStateSql.DDL_EVAL_STATE);
        jdbc.execute(EvalStateSql.DDL_LAST_STATUS);
        jdbc.execute(DDL_TYPE_STATE_R31);
        jdbc.execute(EvalStateSql.DDL_CONFIRMATIONS);
    }

    @AfterEach
    void dropSchema() {
        PostgresIt.app().jdbc().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    List<String> columns() {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'monitor_eval_type_state'", String.class, schema);
    }

    @Test
    @DisplayName("PostgreSQL: 3-1 tablosuna üç sütun eklenir — eşzamanlı 3-1 nabzı sürerken, iki kez, idempotent; 3-1 satırları korunur")
    void patchAddsColumnsWhileR31HeartbeatRuns() throws Exception {
        EvalStateSql sql = new EvalStateSql(jdbc);
        long g = sql.acquireGeneration("it-old", System.currentTimeMillis());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger beats = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Thread loop = new Thread(() -> {
            while (!stop.get()) {
                try {
                    if (sql.heartbeat(g, "it-old", System.currentTimeMillis())) beats.incrementAndGet();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                }
            }
        });
        loop.start();
        try {
            assertThat(columns()).doesNotContain("state_gen", "flushed_ms", "handover_ms");
            EvalStateSchemaPatch patch = new EvalStateSchemaPatch(jdbc);
            assertThat(patch.addMarkerColumns(new com.sitemonitor.service.schema.SchemaDdlGuard(jdbc))).isEqualTo(3);
            assertThat(patch.addMarkerColumns(new com.sitemonitor.service.schema.SchemaDdlGuard(jdbc)))
                    .as("ikinci koşu").isZero();
            assertThat(patch.apply()).as("tam yama: tablolar / sütunlar var (doluluk ayarı değişebilir)")
                    .isLessThanOrEqualTo(3);
            Thread.sleep(200);
        } finally {
            stop.set(true);
            loop.join(10_000);
        }
        assertThat(columns()).contains("state_gen", "flushed_ms", "handover_ms");
        assertThat(beats.get()).as("3-1 nabzı yama boyunca çalıştı").isPositive();
        assertThat(failures).as("3-1 açık sütun listeleri yamadan etkilenmez").hasValue(0);
        assertThat(sql.generationRow().gen()).isEqualTo(g);
        assertThat(sql.markerColumnsReadable()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL: kiraya bağlı öncül CAS — 8 iş parçacığı, her kuşağın TEK kazananı; kazananın öncülü yerine geçtiği satır")
    void acquireWithPriorLeaseBoundEightThreads() throws Exception {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql sql = new EvalStateSql(jdbc);
        jdbc.update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?,?,?,?)",
                EvalStateSql.LEASE_KEY, Long.MAX_VALUE / 2, "it-holder", 1L);
        List<Callable<List<EvalStateSql.Acquired>>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String writer = "itw-" + i;
            tasks.add(() -> {
                List<EvalStateSql.Acquired> won = new ArrayList<>();
                for (int k = 0; k < 20; k++) {
                    EvalStateSql.Acquired a = sql.acquireGenerationWithPrior(writer, System.currentTimeMillis(), "it-holder",
                            () -> true);
                    if (a.gen() > 0) won.add(a);
                }
                return won;
            });
        }
        List<EvalStateSql.Acquired> all = new ArrayList<>();
        for (List<EvalStateSql.Acquired> l : runTogether(tasks)) all.addAll(l);
        Set<Long> gens = new HashSet<>();
        for (EvalStateSql.Acquired a : all) {
            assertThat(gens.add(a.gen())).as("kuşak %d iki kez kazanıldı", a.gen()).isTrue();
            assertThat(a.prior().gen()).as("öncül = yerine geçilen kuşak").isEqualTo(a.gen() - 1);
        }
        assertThat(all).isNotEmpty();
        long max = gens.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(sql.generationRow().gen()).isEqualTo(max);
        // Kira başkasında → hiç kimse kuşak alamaz
        assertThat(sql.acquireGenerationWithPrior("itw-x", 1L, "not-the-holder", () -> true))
                .isEqualTo(EvalStateSql.Acquired.NONE);
    }

    @Test
    @DisplayName("PostgreSQL: SQL_INTERMEDIATE gerçek satırlarda — yalnız üçüncü pod'un vade / boşaltıcı satırı sayılır")
    void intermediateOnRealRows() {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql sql = new EvalStateSql(jdbc);
        String ins = "INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?,?,?,?)";
        jdbc.update(ins, "port:1", 1L, "old", 1_000L);
        jdbc.update(ins, "port:2", 1L, "me", 1_000L);
        jdbc.update(ins, "round:uptime", 1L, "third", 1_000L);
        jdbc.update(ins, EvalStateSql.LEASE_KEY, 1L, "third", 1_000L);
        jdbc.update(ins, "port:3", 1L, null, 1_000L);
        jdbc.update(ins, "port:4", 1L, "third", 999L);
        assertThat(sql.countIntermediate(1_000L, "old", "me")).isZero();
        jdbc.update(ins, "lease:eval-inbox-drainer", 1L, "third", 1_000L);
        jdbc.update(ins, "keyword:9", 1L, "third", 5_000L);
        assertThat(sql.countIntermediate(1_000L, "old", "me")).isEqualTo(2);
    }

    @Test
    @DisplayName("PostgreSQL: gelen kutusu sorgusu — bu şemada tablo yok → satır yok (BadSqlGrammar); boş → yok; satır → var; "
            + "yükleyici FULL adayı gözlemlere indirir (pending_manual_results)")
    void inboxPendingOnPostgres() {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql sql = new EvalStateSql(jdbc);
        Boolean visible = jdbc.queryForObject("SELECT to_regclass('monitor_eval_inbox') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(visible)) assertThat(sql.inboxHasPendingRows()).as("tablo yok").isFalse();
        // Şema nitelikli: paylaşılan uygulamanın kutusuna asla yazılmaz
        jdbc.execute(ManualEvalInbox.DDL.replace("monitor_eval_inbox", schema + ".monitor_eval_inbox"));
        assertThat(sql.inboxHasPendingRows()).as("boş").isFalse();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO " + schema + ".monitor_eval_inbox(monitor_type, monitor_id, manual, result_json, "
                + "checked_ms, source_pod, created_ms) VALUES (?,?,?,?,?,?,?)", ManualEvalInbox.TYPE_PORT, 7L, true, "{}",
                now, "pod-c", now);
        jdbc.update("INSERT INTO " + schema + ".monitor_eval_inbox(monitor_type, monitor_id, manual, result_json, "
                + "checked_ms, source_pod, created_ms) VALUES (?,?,?,?,?,?,?)", ManualEvalInbox.TYPE_PORT, 8L, true, "{}",
                now, "pod-c", now);
        assertThat(sql.inboxHasPendingRows()).as("satır var (LIMIT 1)").isTrue();
        long t = now - 5_000;
        EvalStateSql.PriorGeneration prior = new EvalStateSql.PriorGeneration(5, "old", t, 5L, t, t);
        EvalStateLoader.Outcome o = EvalStateLoader.read(sql, EvalLoadContinuity.decide(prior, "me", now, false), 1,
                () -> 1L, "me", now, EvalStateLoader.MAX_LOAD_ROWS);
        assertThat(o.load()).as(String.valueOf(o.skipped())).isNotNull();
        assertThat(o.load().meta().partialReason()).isEqualTo(EvalLoadReport.REASON_PENDING_MANUAL_RESULTS);
    }

    @Test
    @DisplayName("PostgreSQL: yükleyici okumaları gerçek türlerle (BOOLEAN / BIGINT null) — FULL yük, kuşak süzgeci")
    void loaderReadsRealTypes() {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql sql = new EvalStateSql(jdbc);
        long now = System.currentTimeMillis();
        sql.writeRecovery(List.of(new RecoveryRow("PORT_DOWN", "db", 2, null, null, null, 1),
                new RecoveryRow("PORT_DOWN", "zero", 0, null, null, null, 2)), "old", 5, now);
        sql.writeRecovery(List.of(new RecoveryRow("PORT_DOWN", "stale", 9, now, null, null, 1)), "older", 4, now);
        sql.writeLastStatus(List.of(new LastStatusRow("PORT_DOWN", "m:7", "db", null, 7L, null, null, false, true, "NETWORK",
                "S", now - 1_000, now - 1_000, 3)), "old", 5, now);
        sql.writeTypeState(List.of(new TypeRow("PORT_DOWN", true, null, null, null, 4),
                new TypeRow("KEYWORD", null, null, now, 60_000L, 5)), "old", 5, now);
        long t = now - 5_000;
        EvalStateSql.PriorGeneration prior = new EvalStateSql.PriorGeneration(5, "old", t, 5L, t, t);
        EvalLoadContinuity.Decision d = EvalLoadContinuity.decide(prior, "me", now, false);
        EvalStateLoader.Outcome o = EvalStateLoader.read(sql, d, 1, () -> 1L, "me", now, EvalStateLoader.MAX_LOAD_ROWS);
        EvalLoad l = o.load();
        assertThat(l).as(String.valueOf(o.skipped())).isNotNull();
        assertThat(l.full()).isTrue();
        assertThat(l.recovery()).containsOnlyKeys("PORT_DOWN:db");
        assertThat(l.recovery().get("PORT_DOWN:db").lastCountedMs()).isNull();
        EvalLoad.Sibling s = l.siblings().get(EvalLoad.siblingKey("PORT_DOWN", 7));
        assertThat(s.teamId()).isNull();
        assertThat(s.standalone()).isNull();
        assertThat(s.detail()).isNull();
        assertThat(l.suppressionActive()).containsExactly("PORT_DOWN");
        assertThat(l.roundSeen()).containsExactly("KEYWORD");
        assertThat(l.intervals()).containsOnlyKeys("KEYWORD");
        assertThat(l.window().get("PORT_DOWN").get("db").networkDown()).isTrue();
        assertThat(MonitoringOutageService.siblingTtlMs()).isPositive();
    }

    @Test
    @DisplayName("PostgreSQL: eski sahip bir bağlantıda boşaltırken halef öbüründe CAS + okur — hata yok, yalnız yerine geçilen kuşak okunur")
    void oldHolderFlushWhileSuccessorCasesAndReads() throws Exception {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql old = new EvalStateSql(jdbc);
        EvalStateSql succ = new EvalStateSql(new JdbcTemplate(new DriverManagerDataSource(ds.getUrl(), ds.getUsername(),
                ds.getPassword())));
        long g = old.acquireGeneration("old", 1L);
        old.writeRecovery(List.of(new RecoveryRow("PORT_DOWN", "pre", 1, 1L, null, null, 1)), "old", g, 1L);
        old.heartbeatMarked(g, "old", 2L, 2L);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger errors = new AtomicInteger();
        Thread flusher = new Thread(() -> {
            int seq = 10;
            while (!stop.get()) {
                try {
                    old.writeRecovery(List.of(new RecoveryRow("PORT_DOWN", "late-" + (seq % 50), 1, 1L, null, null, seq++)),
                            "old", g, 3L);
                    old.heartbeatMarked(g, "old", 3L, 3L);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            }
        });
        flusher.start();
        EvalStateSql.Acquired a;
        List<EvalStateSql.LoadRecoveryRow> rows;
        try {
            Thread.sleep(100);
            a = succ.acquireGenerationWithPrior("succ", 4L, null, () -> true);
            rows = succ.loadRecovery(a.prior().gen(), 1000);
            Thread.sleep(100);
        } finally {
            stop.set(true);
            flusher.join(10_000);
        }
        assertThat(errors).hasValue(0);
        assertThat(a.gen()).isEqualTo(g + 1);
        assertThat(a.prior().gen()).isEqualTo(g);
        assertThat(rows).extracting(EvalStateSql.LoadRecoveryRow::domain).contains("pre");
        assertThat(old.heartbeatMarked(g, "old", 5L, 5L)).as("eski sahip çitli").isFalse();
        Map<String, Object> star = jdbc.queryForMap("SELECT gen, writer FROM monitor_eval_type_state WHERE alert_type = '*'");
        assertThat(star).containsEntry("writer", "succ");
    }

    @Test
    @DisplayName("PostgreSQL: devir işaretinden sonra 3-1 biçimli nabız updated_ms'i kaydırır → handover_ms ≠ updated_ms (temiz devir değil)")
    void r31HeartbeatAfterMarkerInvalidates() {
        new EvalStateSchemaPatch(jdbc).apply();
        EvalStateSql sql = new EvalStateSql(jdbc);
        long g = sql.acquireGeneration("old", 1L);
        assertThat(sql.handoverMark(g, "old", 1_000L)).isTrue();
        EvalStateSql.PriorGeneration p = succPrior(sql);
        assertThat(p.handoverMs()).isEqualTo(p.updatedMs());
        assertThat(sql.heartbeat(g, "old", 1_500L)).isTrue();
        EvalStateSql.PriorGeneration after = succPrior(sql);
        assertThat(after.handoverMs()).isEqualTo(1_000L);
        assertThat(after.updatedMs()).isEqualTo(1_500L);
        EvalLoadContinuity.Decision d = EvalLoadContinuity.decide(after, "succ", 2_000L, false);
        assertThat(d.tier()).isEqualTo(EvalLoadContinuity.Tier.OBSERVATIONS);
        assertThat(d.reason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
    }

    private EvalStateSql.PriorGeneration succPrior(EvalStateSql sql) {
        Map<String, Object> r = jdbc.queryForMap("SELECT gen, writer, updated_ms, state_gen, flushed_ms, handover_ms FROM "
                + "monitor_eval_type_state WHERE alert_type = '*'");
        return new EvalStateSql.PriorGeneration(((Number) r.get("gen")).longValue(), (String) r.get("writer"),
                ((Number) r.get("updated_ms")).longValue(), num(r.get("state_gen")), num(r.get("flushed_ms")),
                num(r.get("handover_ms")));
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return t.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(120, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
