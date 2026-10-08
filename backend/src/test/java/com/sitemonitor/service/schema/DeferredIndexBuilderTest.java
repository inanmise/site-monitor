package com.sitemonitor.service.schema;

import com.sitemonitor.service.schema.DeferredIndexBuilder.Result;
import com.sitemonitor.service.schema.DeferredIndexBuilder.Spec;
import com.sitemonitor.service.schema.DeferredIndexBuilder.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filtrelenen alanlara eksik indeksler (2026-10-08) — ertelenmiş kurulumun sözleşmesi. Pinlenen:
 * <ul>
 *   <li>Katalog denetimin bulduğu her filtre yolunu doğru tablo + kolon sırasıyla kapsar; her PostgreSQL DDL'i
 *       {@code CONCURRENTLY IF NOT EXISTS}; ad çakışması yok; PG'ye özgü sözdizimi H2 yedeğine sızmaz.</li>
 *   <li>{@code SchedulerService} bu indeksleri ASLA senkron kurmaz: PostgreSQL'de kurulum readiness ACCEPTING_TRAFFIC
 *       yayımlandıktan SONRA başlar (açılış/readiness beklemez).</li>
 *   <li>Kurucu: kilit altında sırayla kurar, geçerliye dokunmaz, yarıda kalmış INVALID'i düşürüp yeniden kurar, başka
 *       oturumun kurduğuna dokunmaz, tablosu yoksa atlar, bir hata diğerlerini durdurmaz ve geride INVALID bırakmaz,
 *       kilit doluysa sınırlı bekler, başka pod yama koşarken bekler; istisna dışarı sızmaz.</li>
 * </ul>
 */
class DeferredIndexBuilderTest {

    /** Denetimin bulduğu filtre yolları → beklenen indeks (ad → "tablo(kolonlar)" + PG'ye özgü ek). */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("idx_uc_domain_checked", "uptime_checks(domain, checked_at)");
        EXPECTED.put("idx_ps_checked_at", "pagespeed_checks(checked_at)");
        EXPECTED.put("idx_act_type_mon_time", "activity_log(monitor_type, monitor_id, activity_time)");
        EXPECTED.put("idx_ae_domain_type_created", "alert_events(domain, alert_type, created_at) INCLUDE (resolved_at)");
        EXPECTED.put("idx_sc_success_monitor", "scripted_checks(monitor_id) WHERE ok = true");
        EXPECTED.put("idx_audit_lower_actor_type_time", "audit_log(lower(actor), event_type, event_time)");
        EXPECTED.put("idx_push_batch", "user_push_deliveries(batch_id)");
        EXPECTED.put("idx_push_lower_user_created", "user_push_deliveries(lower(username), created_at)");
        EXPECTED.put("idx_mchg_resource_id", "monitor_change_log(resource_id)");
        EXPECTED.put("idx_mcd_day", "monitor_check_daily(day)");
        EXPECTED.put("idx_mch_hour", "monitor_check_hourly(hour_bucket)");
        EXPECTED.put("idx_nl_trigger_sent", "notification_logs(trigger, sent_at)");
        EXPECTED.put("idx_psr_reason_checked", "pagespeed_resources(keep_reason, checked_at)");
        EXPECTED.put("idx_app_users_upper_username", "app_users(UPPER(username))");   // oturum kapısı + ping (2026-10-09)
    }
    /** Fonksiyonel / kısmi — H2 karşılığı yok. */
    private static final Set<String> POSTGRES_ONLY =
            Set.of("idx_sc_success_monitor", "idx_audit_lower_actor_type_time", "idx_push_lower_user_created",
                    "idx_app_users_upper_username");

    private static final Path MAIN = Path.of("src/main/java/com/sitemonitor");

    // ── Katalog ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Katalog denetimin her filtre yolunu doğru tablo + kolon sırasıyla kapsar (sıra = kurulum sırası)")
    void catalog_coversEveryAuditedFilterPath() {
        assertThat(DeferredIndexBuilder.CATALOG).extracting(Spec::name).containsExactlyElementsOf(EXPECTED.keySet());
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            assertThat(s.postgresDdl()).as(s.name())
                    .isEqualTo("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + s.name() + " ON " + EXPECTED.get(s.name()));
        }
    }

    @Test
    @DisplayName("Her DDL: CONCURRENTLY + IF NOT EXISTS, idx_ öneki, ≤ 63 karakter, tekil ad; H2 yedeğinde PG'ye özgü sözdizimi yok")
    void catalog_ddlShape() {
        Set<String> names = new HashSet<>();
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            assertThat(names.add(s.name())).as("tekil ad %s", s.name()).isTrue();
            assertThat(s.name()).startsWith("idx_").hasSizeLessThanOrEqualTo(63).isLowerCase();
            assertThat(s.postgresDdl()).startsWith("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + s.name() + " ON " + s.table() + "(");
            if (POSTGRES_ONLY.contains(s.name())) {
                assertThat(s.fallbackDdl()).as("%s PG'ye özgü — H2 yedeği olmamalı", s.name()).isNull();
                continue;
            }
            assertThat(s.fallbackDdl()).as("%s taşınabilir — H2 yedeği olmalı", s.name())
                    .startsWith("CREATE INDEX IF NOT EXISTS " + s.name() + " ON " + s.table() + "(");
            String lower = s.fallbackDdl().toLowerCase(Locale.ROOT);
            assertThat(lower).as(s.name()).doesNotContain("concurrently").doesNotContain(" include ")
                    .doesNotContain(" where ").doesNotContain("lower(").doesNotContain("text_pattern_ops");
        }
    }

    @Test
    @DisplayName("Spec yalnız CONCURRENTLY IF NOT EXISTS kabul eder (blok eden düz kurulum kataloğa giremez)")
    void spec_rejectsBlockingDdl() {
        assertThatThrownBy(() -> Spec.portable("CREATE INDEX IF NOT EXISTS idx_x ON uptime_checks(domain)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Spec.portable("CREATE INDEX CONCURRENTLY idx_x ON uptime_checks(domain)"))
                .isInstanceOf(IllegalArgumentException.class);
        Spec ok = Spec.portable("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_x ON uptime_checks(domain)");
        assertThat(ok.fallbackDdl()).isEqualTo("CREATE INDEX IF NOT EXISTS idx_x ON uptime_checks(domain)");
    }

    @Test
    @DisplayName("Katalog adları kod tabanındaki başka hiçbir indeksle çakışmaz; tabloları gerçek tablolardır")
    void catalog_namesDoNotCollide_andTablesExist() throws Exception {
        Set<String> otherIndexes = new TreeSet<>();
        Set<String> tables = new TreeSet<>(List.of("monitor_check_daily", "monitor_check_hourly"));   // ham DDL tabloları
        Pattern ddlIndex = Pattern.compile(
                "(?i)CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON");
        Pattern jpaIndex = Pattern.compile("@Index\\s*\\(\\s*name\\s*=\\s*\"(\\w+)\"");
        Pattern jpaTable = Pattern.compile("@Table\\s*\\([^)]*?name\\s*=\\s*\"(\\w+)\"", Pattern.DOTALL);
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("DeferredIndexBuilder.java")) continue;
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (Pattern pat : List.of(ddlIndex, jpaIndex)) {
                    Matcher m = pat.matcher(src);
                    while (m.find()) otherIndexes.add(m.group(1).toLowerCase(Locale.ROOT));
                }
                Matcher t = jpaTable.matcher(src);
                while (t.find()) tables.add(t.group(1).toLowerCase(Locale.ROOT));
            }
        }
        assertThat(otherIndexes).as("kaynak taraması boş — desen ya da yol değişmiş").hasSizeGreaterThan(100);
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            assertThat(otherIndexes).as("%s başka bir yerde de tanımlı (çift kurulum / ad çakışması)", s.name())
                    .doesNotContain(s.name());
            assertThat(tables).as("%s tablosu %s gerçek bir tablo olmalı (yanlış ad sessiz no-op olur)", s.name(), s.table())
                    .contains(s.table());
        }
    }

    @Test
    @DisplayName("SchedulerService katalog indekslerini ASLA senkron kurmaz; PostgreSQL kurulumu readiness HAZIR'dan SONRA başlar")
    void schedulerService_buildsAfterReadiness_neverSynchronously() throws Exception {
        String src = Files.readString(MAIN.resolve("service/SchedulerService.java"), StandardCharsets.UTF_8);
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            assertThat(src).as("%s SchedulerService'te senkron patch() olarak da geçiyor", s.name()).doesNotContain(s.name());
        }
        int applyStart = src.indexOf("private void applySchemaPatches()");
        int fallbacks = src.indexOf("deferredIndexes().applyFallbacks(this::patch);");
        assertThat(applyStart).isPositive();
        assertThat(fallbacks).as("H2 yedekleri applySchemaPatches içinde").isGreaterThan(applyStart);

        int startup = src.indexOf("public void runOnStartup()");
        int ready = src.indexOf("ReadinessState.ACCEPTING_TRAFFIC);", startup);
        int background = src.indexOf("deferredIndexes().startInBackground();", startup);
        assertThat(startup).isPositive();
        assertThat(ready).as("readiness yayını").isGreaterThan(startup);
        assertThat(background).as("arka plan kurulumu readiness ACCEPTING_TRAFFIC'ten SONRA başlamalı").isGreaterThan(ready);
        assertThat(src.split("startInBackground\\(\\)", -1)).as("tek çağrı yeri (senkron yoldan çağrılmamalı)").hasSize(2);
    }

    // ── Kurucu (PostgreSQL yolu, sahte oturum) ────────────────────────────────────────────────────────────────

    /** Sahte PostgreSQL oturumu: indeks durumları + çalışan DDL günlüğü + kilit olayları. */
    static final class FakeSession implements DeferredIndexBuilder.Session {
        final Map<String, State> states = new HashMap<>();
        final Set<String> missingTables = new HashSet<>();
        final List<String> executed = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        int builderLockBusy;
        int patchLockBusy;
        boolean lockThrows;
        Predicate<String> failOn = d -> false;
        boolean closed;

        @Override
        public boolean tryLock(long key) throws SQLException {
            if (lockThrows) throw new SQLException("bağlantı yok");
            if (key == DeferredIndexBuilder.LOCK_KEY) {
                if (builderLockBusy > 0) { builderLockBusy--; return false; }
                events.add("lock");
                return true;
            }
            assertThat(key).isEqualTo(SchemaPatchRunner.LOCK_KEY);
            if (patchLockBusy > 0) { patchLockBusy--; return false; }
            events.add("probe");
            return true;
        }

        @Override
        public void unlock(long key) { events.add(key == DeferredIndexBuilder.LOCK_KEY ? "unlock" : "unprobe"); }

        @Override
        public boolean tableExists(String table) { return !missingTables.contains(table); }

        @Override
        public State state(String index) { return states.getOrDefault(index, State.ABSENT); }

        @Override
        public void execute(String ddl) throws SQLException {
            executed.add(ddl);
            threads.add(Thread.currentThread().getName());
            String name = ddl.replaceFirst("(?i)^.*?(?:EXISTS)\\s+(\\w+).*$", "$1").toLowerCase(Locale.ROOT);
            if (ddl.startsWith("DROP")) { states.remove(name); return; }
            if (failOn.test(ddl)) {
                states.put(name, State.INVALID);   // PostgreSQL: yarıda kalan CONCURRENTLY geride INVALID indeks bırakır
                throw new SQLException("could not create unique index", "23505");
            }
            states.put(name, State.VALID);
        }

        @Override
        public void close() { closed = true; }
    }

    private static DeferredIndexBuilder builder(FakeSession s, boolean postgres, long lockWaitMs, AtomicInteger sleeps) {
        return new DeferredIndexBuilder(DeferredIndexBuilder.CATALOG, () -> postgres, () -> s,
                ms -> sleeps.incrementAndGet(), lockWaitMs, 60_000, 1, 0);
    }

    private static List<String> pgDdls() {
        return DeferredIndexBuilder.CATALOG.stream().map(Spec::postgresDdl).toList();
    }

    @Test
    @DisplayName("Hiçbiri yoksa: kilit altında katalog sırasıyla HEPSİ CONCURRENTLY kurulur, kilit bırakılır, oturum kapanır")
    void run_absent_buildsEverythingConcurrently_underLock() throws Exception {
        FakeSession s = new FakeSession();
        Result r = builder(s, true, 0, new AtomicInteger()).runBlocking();

        assertThat(s.executed).containsExactlyElementsOf(pgDdls());
        assertThat(s.executed).allSatisfy(d -> assertThat(d).contains("CONCURRENTLY IF NOT EXISTS"));
        assertThat(r.built()).isEqualTo(EXPECTED.size());
        assertThat(r.failed()).isZero();
        assertThat(r.locked()).isTrue();
        assertThat(s.events.getFirst()).isEqualTo("lock");
        assertThat(s.events.getLast()).isEqualTo("unlock");
        assertThat(s.closed).isTrue();
    }

    @Test
    @DisplayName("Geçerli indekse dokunulmaz (ikinci açılış = hiçbir DDL)")
    void run_valid_isNoop() throws Exception {
        FakeSession s = new FakeSession();
        DeferredIndexBuilder.CATALOG.forEach(x -> s.states.put(x.name(), State.VALID));
        Result r = builder(s, true, 0, new AtomicInteger()).run(s);
        assertThat(s.executed).isEmpty();
        assertThat(r.valid()).isEqualTo(EXPECTED.size());
        assertThat(r.built() + r.rebuilt() + r.failed()).isZero();
    }

    @Test
    @DisplayName("Yarıda kalmış INVALID indeks: önce DROP INDEX CONCURRENTLY, sonra yeniden kurulur")
    void run_invalid_dropsThenRebuilds() throws Exception {
        FakeSession s = new FakeSession();
        DeferredIndexBuilder.CATALOG.forEach(x -> s.states.put(x.name(), State.VALID));
        s.states.put("idx_uc_domain_checked", State.INVALID);
        Result r = builder(s, true, 0, new AtomicInteger()).run(s);
        assertThat(s.executed).containsExactly(
                "DROP INDEX CONCURRENTLY IF EXISTS idx_uc_domain_checked",
                DeferredIndexBuilder.CATALOG.getFirst().postgresDdl());
        assertThat(r.rebuilt()).isEqualTo(1);
        assertThat(s.states.get("idx_uc_domain_checked")).isEqualTo(State.VALID);
    }

    @Test
    @DisplayName("Başka oturumun ŞU AN kurduğu indekse dokunulmaz; tablosu olmayan indeks atlanır")
    void run_buildingOrMissingTable_isLeftAlone() throws Exception {
        FakeSession s = new FakeSession();
        DeferredIndexBuilder.CATALOG.forEach(x -> s.states.put(x.name(), State.VALID));
        s.states.put("idx_audit_lower_actor_type_time", State.BUILDING);
        s.states.remove("idx_mcd_day");
        s.missingTables.add("monitor_check_daily");
        Result r = builder(s, true, 0, new AtomicInteger()).run(s);
        assertThat(s.executed).isEmpty();
        assertThat(r.skipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("Kalıcı hata: geride INVALID bırakmaz (düşürür), kalanlar yine kurulur, sonda BİR KEZ yeniden denenir, istisna sızmaz")
    void run_failure_isIsolated_retriedOnce_andLeavesNoInvalidIndex() throws Exception {
        FakeSession s = new FakeSession();
        s.failOn = d -> d.contains("idx_act_type_mon_time") && d.startsWith("CREATE");
        Result r = builder(s, true, 0, new AtomicInteger()).run(s);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.built()).isEqualTo(EXPECTED.size() - 1);
        assertThat(s.states).doesNotContainKey("idx_act_type_mon_time");
        String create = DeferredIndexBuilder.CATALOG.get(2).postgresDdl();
        String drop = "DROP INDEX CONCURRENTLY IF EXISTS idx_act_type_mon_time";
        assertThat(s.executed.stream().filter(create::equals)).as("ilk deneme + tek yeniden deneme").hasSize(2);
        assertThat(s.executed.stream().filter(drop::equals)).as("her başarısız denemeden sonra kalıntı düşürülür").hasSize(2);
        int lastCatalog = s.executed.indexOf(DeferredIndexBuilder.CATALOG.getLast().postgresDdl());
        assertThat(s.executed.lastIndexOf(create)).as("yeniden deneme diğerlerinden SONRA").isGreaterThan(lastCatalog);
        assertThat(s.executed.getLast()).isEqualTo(drop);
        assertThat(s.events.getLast()).isEqualTo("unlock");
    }

    @Test
    @DisplayName("Geçici hata (ör. eşzamanlı ANALYZE ile kilitlenme): gecikmeden sonra yeniden denenir ve kurulur")
    void run_transientFailure_isRetriedAfterDelay() throws Exception {
        FakeSession s = new FakeSession();
        AtomicInteger attempts = new AtomicInteger();
        s.failOn = d -> d.startsWith("CREATE") && d.contains("idx_audit_lower_actor_type_time") && attempts.incrementAndGet() == 1;
        List<Long> slept = new ArrayList<>();
        DeferredIndexBuilder b = new DeferredIndexBuilder(DeferredIndexBuilder.CATALOG, () -> true, () -> s,
                slept::add, 0, 0, 1, 60_000);
        Result r = b.run(s);
        assertThat(r.failed()).isZero();
        assertThat(r.built()).isEqualTo(EXPECTED.size());
        assertThat(slept).containsExactly(60_000L);
        assertThat(s.states.get("idx_audit_lower_actor_type_time")).isEqualTo(State.VALID);
    }

    @Test
    @DisplayName("Kurucu kilidi başka podda: sınırlı beklenir, alınamazsa HİÇBİR DDL koşmaz ve kilit bırakılmaya çalışılmaz")
    void run_lockBusy_boundedWait_thenSkips() throws Exception {
        FakeSession s = new FakeSession();
        s.builderLockBusy = Integer.MAX_VALUE;
        Result r = builder(s, true, 0, new AtomicInteger()).run(s);
        assertThat(r.locked()).isFalse();
        assertThat(s.executed).isEmpty();
        assertThat(s.events).doesNotContain("unlock");
    }

    @Test
    @DisplayName("Kilit kısa süre doluysa beklenir ve sonra kurulur")
    void run_lockBusyThenFree_proceeds() throws Exception {
        FakeSession s = new FakeSession();
        s.builderLockBusy = 2;
        AtomicInteger sleeps = new AtomicInteger();
        Result r = builder(s, true, 60_000, sleeps).run(s);
        assertThat(sleeps.get()).isEqualTo(2);
        assertThat(r.locked()).isTrue();
        assertThat(r.built()).isEqualTo(EXPECTED.size());
    }

    @Test
    @DisplayName("Kilit yoklanamazsa (bağlantı hatası) hiçbir şey denenmez, istisna sızmaz")
    void run_lockProbeFails_skipsQuietly() throws Exception {
        FakeSession s = new FakeSession();
        s.lockThrows = true;
        Result r = builder(s, true, 60_000, new AtomicInteger()).run(s);
        assertThat(r.locked()).isFalse();
        assertThat(s.executed).isEmpty();
    }

    @Test
    @DisplayName("Başka bir pod açılış yamalarını koşarken indekse başlanmaz (onun readiness'ı bizim kurulumu beklemesin)")
    void run_yieldsWhileAnotherPodPatches() throws Exception {
        FakeSession s = new FakeSession();
        s.patchLockBusy = 3;
        AtomicInteger sleeps = new AtomicInteger();
        builder(s, true, 0, sleeps).run(s);
        assertThat(sleeps.get()).isEqualTo(3);
        int firstProbe = s.events.indexOf("probe");
        assertThat(firstProbe).isPositive();
        assertThat(s.events.get(firstProbe + 1)).as("yama kilidi yalnız yoklanır, hemen bırakılır").isEqualTo("unprobe");
        assertThat(s.executed).hasSize(EXPECTED.size());
    }

    // ── Açılış entegrasyonu ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PostgreSQL dışı (H2): yedekler senkron yama olarak koşar — PG'ye özgüler hariç, hiçbiri CONCURRENTLY değil")
    void applyFallbacks_nonPostgres_runsPortableOnes() {
        List<String> patched = new ArrayList<>();
        builder(new FakeSession(), false, 0, new AtomicInteger()).applyFallbacks(patched::add);
        assertThat(patched).hasSize(EXPECTED.size() - POSTGRES_ONLY.size());
        assertThat(patched).allSatisfy(d -> assertThat(d).startsWith("CREATE INDEX IF NOT EXISTS ").doesNotContain("CONCURRENTLY"));
        assertThat(patched).noneMatch(d -> POSTGRES_ONLY.stream().anyMatch(d::contains));
    }

    @Test
    @DisplayName("PostgreSQL: açılışta (senkron) HİÇBİR indeks kurulmaz")
    void applyFallbacks_postgres_runsNothing() {
        List<String> patched = new ArrayList<>();
        builder(new FakeSession(), true, 0, new AtomicInteger()).applyFallbacks(patched::add);
        assertThat(patched).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL dışı: arka plan iş parçacığı başlatılmaz")
    void startInBackground_nonPostgres_isNoop() {
        FakeSession s = new FakeSession();
        assertThat(builder(s, false, 0, new AtomicInteger()).startInBackground()).isNull();
        assertThat(s.executed).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: kurulum ayrı (daemon) iş parçacığında koşar ve tamamlanır; lastRun() onu gösterir")
    void startInBackground_postgres_runsOffThread() throws Exception {
        FakeSession s = new FakeSession();
        CompletableFuture<Result> f = builder(s, true, 0, new AtomicInteger()).startInBackground();
        assertThat(DeferredIndexBuilder.lastRun()).isSameAs(f);
        Result r = f.get(10, TimeUnit.SECONDS);
        assertThat(r.built()).isEqualTo(EXPECTED.size());
        assertThat(s.threads).isNotEmpty().allSatisfy(t -> assertThat(t).isEqualTo("schema-deferred-indexes"));
        assertThat(s.closed).isTrue();
    }

    @Test
    @DisplayName("Oturum açılamazsa: çağırana istisna sızmaz, koşu 'başarısız' özetiyle biter")
    void startInBackground_sessionFailure_neverThrows() throws Exception {
        DeferredIndexBuilder b = new DeferredIndexBuilder(DeferredIndexBuilder.CATALOG, () -> true,
                () -> { throw new SQLException("veritabanı yok"); }, ms -> { }, 0, 0, 1, 0);
        Result r = b.startInBackground().get(10, TimeUnit.SECONDS);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.locked()).isFalse();
    }
}
