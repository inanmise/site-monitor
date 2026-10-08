package com.sitemonitor.service.schema;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Açılış şema yamaları GERÇEK PostgreSQL'de (2026-10-02, onaylı öneri 28) — {@code mvn -Ppostgres-it test}.
 *
 * <p>H2'de {@link SchemaPatchRunner} PostgreSQL'e özgü her hatayı "beklenen" sayıp yuttuğu için (isPostgres=false)
 * buradaki iddiaların HİÇBİRİ birim testlerinde kanıtlanamaz: boş veritabanında 0 başarısız yama, aynı şemaya ikinci
 * açılışta yine 0 (idempotentlik), advisory lock'un gerçekten tutulup bırakılması, kısmi / {@code text_pattern_ops}
 * indekslerin kurulması, {@code CONCURRENTLY} kurulumun INVALID indeks bırakmaması ve yamalarda adı geçen her indeksin /
 * kolonun gerçekten var olması (42P01/42703 "beklenen" sayıldığı için yanlış yazılmış bir indeks yaması sessizce no-op
 * olur ve {@code failed} sayacı onu GÖRMEZ).
 */
@PostgresIntegration
class SchemaPatchPostgresTest {

    /** Yamaların kaynağı — indeks / kolon adları buradan okunur (surefire çalışma dizini {@code backend/}). */
    private static final List<String> PATCH_SOURCES = List.of(
            "src/main/java/com/sitemonitor/service/SchedulerService.java",
            "src/main/java/com/sitemonitor/service/noc/NocSchemaPatches.java",
            "src/main/java/com/sitemonitor/service/noc/NocCallLogSchemaPatch.java",
            // 2026-10-08: açılışı bekletmeyen (hazır olduktan sonra arka planda kurulan) indeksler.
            "src/main/java/com/sitemonitor/service/schema/DeferredIndexBuilder.java");

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    // ── 1. Boş veritabanı → iki açılış ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Boş PostgreSQL: tam bağlam açılır, applySchemaPatches koşar, 0 BAŞARISIZ yama, advisory lock altında")
    void firstBoot_onEmptyDatabase_hasNoFailedPatch() {
        SchemaPatchRunner.Summary s = PostgresIt.app().firstBoot().patches();
        assertThat(s.failed())
                .as("Boş PostgreSQL'de BAŞARISIZ şema yamaları (%d) — ilkleri: %s", s.failed(), s.failures())
                .isZero();
        assertThat(s.applied() + s.noop()).as("koşan yama sayısı (applySchemaPatches gerçekten çalıştı mı?)")
                .isGreaterThan(200);
        assertThat(s.locked()).as("PostgreSQL'de yamalar advisory lock altında koşmalı").isTrue();
    }

    @Test
    @DisplayName("Aynı şemaya İKİNCİ açılış: yine 0 başarısız yama (idempotent), yine kilitli")
    void secondBoot_onAlreadyPatchedSchema_isIdempotent() {
        SchemaPatchRunner.Summary first = PostgresIt.app().firstBoot().patches();
        SchemaPatchRunner.Summary second = PostgresIt.app().secondBoot().patches();
        assertThat(second).as("ikinci açılış kendi özetini üretmeli").isNotSameAs(first);
        assertThat(second.finishedAtMs()).isGreaterThanOrEqualTo(first.finishedAtMs());
        assertThat(second.failed())
                .as("Yamalanmış şemaya ikinci açılışta BAŞARISIZ yamalar (%d) — ilkleri: %s", second.failed(), second.failures())
                .isZero();
        assertThat(second.locked()).isTrue();
    }

    @Test
    @DisplayName("Tam kurulmuş şemada hiçbir yama 'nesne yok' hatasıyla SESSİZCE no-op olmaz (failed sayacının kör noktası)")
    void noPatchIsSilentlySkippedAsUndefined_onEitherBoot() {
        for (PostgresIt.BootRun run : List.of(PostgresIt.app().firstBoot(), PostgresIt.app().secondBoot())) {
            // Dinleyici gerçekten her no-op yamayı (atlananlar dâhil — onlar da noop sayılır ve DEBUG yazılır) görmeli;
            // yoksa aşağıdaki "boş liste" hiçbir şey kanıtlamaz.
            assertThat(run.patchDebugLines()).as("SchemaPatchRunner DEBUG satırı (dinleyici canlı mı?)")
                    .isGreaterThanOrEqualTo(run.patches().noop());
            List<String> undefined = run.silentlySkippedPatches().stream()
                    .filter(m -> {
                        String l = m.toLowerCase(Locale.ROOT);
                        return l.contains("does not exist") || l.contains("mevcut değil");
                    })
                    .toList();
            assertThat(undefined)
                    .as("SchemaPatchRunner 42P01/42703/42704'ü 'beklenen' sayıp yutar ve failed sayacı görmez — ama ddl-auto"
                      + " + yamalar şemayı TAM kurduktan sonra var olmayan bir nesneye dokunan yama ya ölüdür ya yanlış"
                      + " yazılmıştır (eski veritabanı temizliğiyse IF EXISTS kullanın). Sessizce atlananlar: %s",
                        run.silentlySkippedPatches())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("ddl-auto=update iki açılışta da PostgreSQL'de hatasız DDL üretir (Hibernate şema aracı uyarısı yok)")
    void hibernateSchemaUpdate_hasNoDdlErrors_onBothBoots() {
        assertThat(PostgresIt.app().firstBoot().schemaToolProblems()).as("açılış #1 Hibernate şema sorunları").isEmpty();
        assertThat(PostgresIt.app().secondBoot().schemaToolProblems()).as("açılış #2 Hibernate şema sorunları").isEmpty();
    }

    @Test
    @DisplayName("İki açılışta da ERROR düzeyinde günlük yok (imaj açılış testinin JVM içi eşi)")
    void bothBoots_logNoErrors() {
        assertThat(PostgresIt.app().firstBoot().errorLogs()).as("açılış #1 ERROR günlükleri").isEmpty();
        assertThat(PostgresIt.app().secondBoot().errorLogs()).as("açılış #2 ERROR günlükleri").isEmpty();
    }

    @Test
    @DisplayName("Açılan uygulama PostgreSQL'le /health UP döner, /api/branding açık, oturumsuz /api 401")
    void bootedApp_servesHealthUp_andGatesApi() throws Exception {
        String base = "http://localhost:" + PostgresIt.app().port();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> health = get(http, base + "/health");
        assertThat(health.statusCode()).as("/health → %s", health.body()).isEqualTo(200);
        assertThat(health.body()).contains("\"UP\"").contains("PostgreSQL");

        assertThat(get(http, base + "/api/branding").statusCode()).as("/api/branding (PUBLIC)").isEqualTo(200);
        assertThat(get(http, base + "/api/me").statusCode()).as("/api/me oturumsuz").isEqualTo(401);
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ── 2. Kritik indeksler ve PostgreSQL semantiği ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kritik indeksler kurulu: kısmi UNIQUE (fırtına), text_pattern_ops (push), UNIQUE kilitler, created_at, PK")
    void criticalIndexes_existWithPostgresSemantics() throws Exception {
        awaitDeferredIndexes();
        Map<String, String> defs = indexDefs();

        assertThat(defs).as("alert_events(created_at) — gürültü/pencere sorguları").containsKey("idx_ae_created_at");
        assertThat(defs.get("idx_ae_created_at")).contains("alert_events").contains("(created_at)");

        assertUnique(defs, "ux_asm_storm_event", "alert_storm_members");          // fırtına üyeliği ON CONFLICT DO NOTHING
        assertUnique(defs, "ux_spc_key_event", "storm_push_coverage");           // fırtına push'u ↔ alarm bağı (2026-10-04)
        assertThat(defs).as("storm_push_coverage(alert_event_id) — alarm detayı").containsKey("idx_spc_event");
        assertThat(defs).as("storm_push_coverage(storm_id) — fırtına ayrıntısı + yetim temizliği").containsKey("idx_spc_storm");
        assertUnique(defs, "ux_aes_event_contact_level", "alert_escalation_steps"); // eskalasyon adımı tekilleştirme
        assertUnique(defs, "ux_qdi_event_team_window", "quiet_digest_items");     // sessiz saat özeti tekilleştirme
        assertUnique(defs, "ux_push_event_phase_user", "user_push_deliveries");   // push dedupe son sözü

        // Kapsam başına TEK aktif fırtına: INSERT … ON CONFLICT (scope_key) WHERE resolved = false — kısmi indeks şart.
        assertUnique(defs, "ux_alert_storms_active", "alert_storms");
        assertThat(defs.get("ux_alert_storms_active")).as("kısmi indeks yüklemi").contains("WHERE (resolved = false)");

        // LIKE 'önek%' aramaları C dışı collation'da yalnız text_pattern_ops ile indeks kullanır.
        assertThat(defs).containsKey("idx_push_dedupe");
        assertThat(defs.get("idx_push_dedupe")).contains("text_pattern_ops");

        Integer pk = jdbc().queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('user_preferences') AND contype = 'p'",
                Integer.class);
        assertThat(pk).as("user_preferences birincil anahtarı (kullanıcı başına tek belge)").isEqualTo(1);

        for (String rollup : List.of("monitor_check_daily", "monitor_check_hourly")) {
            Integer rpk = jdbc().queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?) AND contype = 'p'", Integer.class, rollup);
            assertThat(rpk).as("%s birincil anahtarı (rollup ON CONFLICT hedefi)", rollup).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Ertelenmiş indeksler (2026-10-08): hazır olduktan sonra arka planda kurulur — hepsi var, 0 başarısız;"
            + " INCLUDE / kısmi / fonksiyonel tanımlar PostgreSQL'de doğru")
    void deferredIndexes_builtInBackground_withPostgresSemantics() throws Exception {
        int n = DeferredIndexBuilder.CATALOG.size();
        DeferredIndexBuilder.Result first = PostgresIt.app().firstBoot().deferredIndexes();
        assertThat(first.locked()).as("açılış #1 kurucu kilidi").isTrue();
        assertThat(first.failed()).as("açılış #1 ertelenmiş indeks başarısızlığı: %s", first).isZero();
        assertThat(first.built()).as("boş veritabanı: açılış #1 katalogdaki HER indeksi kurmalı (tablosu yok → atlandı?): %s", first)
                .isEqualTo(n);
        DeferredIndexBuilder.Result second = awaitDeferredIndexes();
        assertThat(second.locked()).isTrue();
        assertThat(second.failed()).isZero();
        assertThat(second.valid()).as("açılış #2: hepsi zaten geçerli, hiçbir DDL koşmaz (idempotent): %s", second).isEqualTo(n);

        Map<String, String> defs = indexDefs();
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            assertThat(defs).as("%s ON %s", s.name(), s.table()).containsKey(s.name());
            assertThat(defs.get(s.name())).as(s.name()).doesNotStartWith("CREATE UNIQUE").containsPattern(" ON (\\w+\\.)?" + s.table() + " USING btree ");
        }
        assertThat(defs.get("idx_uc_domain_checked")).contains("(domain, checked_at)");
        assertThat(defs.get("idx_ae_domain_type_created")).contains("(domain, alert_type, created_at) INCLUDE (resolved_at)");
        assertThat(defs.get("idx_sc_success_monitor")).as("kısmi indeks yüklemi").contains("(monitor_id) WHERE (ok = true)");
        assertThat(defs.get("idx_audit_lower_actor_type_time")).as("fonksiyonel: LOWER(a.actor) = :actor")
                .containsPattern("lower\\(\\(?actor\\)?(::text)?\\), event_type, event_time\\)");
        assertThat(defs.get("idx_push_lower_user_created")).as("fonksiyonel: LOWER(d.username) = LOWER(:username)")
                .containsPattern("lower\\(\\(?username\\)?(::text)?\\), created_at\\)");
        assertThat(defs.get("idx_noc_delivery_storm_phase")).as("NOC: fırtına turu sorgusu (NocSchemaPatches)")
                .contains("(storm_id, phase, id)");
    }

    @Test
    @DisplayName("Ertelenmiş kurucu gerçek PostgreSQL'de: başarısız kurulum geride INVALID bırakmaz; yarıda kalmış INVALID"
            + " indeksi düşürüp yeniden kurar; geçerliye dokunmaz")
    void deferredBuilder_cleansAndRepairsInvalidIndexes_onRealPostgres() throws Exception {
        awaitDeferredIndexes();   // açılışın kurucusu kilidi bıraksın
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP TABLE IF EXISTS it_idx_probe");
        jdbc.execute("CREATE TABLE it_idx_probe (v INTEGER)");
        try {
            jdbc.execute("INSERT INTO it_idx_probe VALUES (1), (1)");
            String ddl = "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS it_idx_probe_v ON it_idx_probe(v)";
            DeferredIndexBuilder b = new DeferredIndexBuilder(List.of(DeferredIndexBuilder.Spec.postgresOnly(ddl)),
                    () -> true, () -> DeferredIndexBuilder.JdbcSession.open(jdbc.getDataSource()), Thread::sleep,
                    10_000, 10_000, 100, 0);

            // 1) Tekrarlı veri → CONCURRENTLY düşer (23505; tek yeniden deneme de düşer): geride INVALID kalıntı YOK.
            DeferredIndexBuilder.Result failed = b.runBlocking();
            assertThat(failed.failed()).isEqualTo(1);
            assertThat(indexValidity("it_idx_probe_v")).as("başarısız kurulumdan kalan INVALID indeks").isNull();

            // 2) Pod yarıda öldü senaryosu: elle bırakılmış INVALID indeks → kurucu düşürüp yeniden kurar.
            try { jdbc.execute(ddl); } catch (Exception expected) { /* 23505 → INVALID indeks kalır */ }
            assertThat(indexValidity("it_idx_probe_v")).as("hazırlık: INVALID indeks").isFalse();
            jdbc.execute("DELETE FROM it_idx_probe");
            jdbc.execute("INSERT INTO it_idx_probe VALUES (1)");
            DeferredIndexBuilder.Result repaired = b.runBlocking();
            assertThat(repaired.rebuilt()).as("%s", repaired).isEqualTo(1);
            assertThat(indexValidity("it_idx_probe_v")).isTrue();

            // 3) Sonraki açılış: geçerli indekse dokunulmaz.
            assertThat(b.runBlocking().valid()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS it_idx_probe");
        }
    }

    /**
     * Ertelenmiş indeksler (2026-10-08) pod hazır olduktan SONRA arka planda kurulur; indeks iddialarından önce ikinci
     * açılışın koşusu beklenir (PostgresIt açılışta zaten bekler; burada açık güvence — boş veritabanında milisaniyeler).
     */
    private static DeferredIndexBuilder.Result awaitDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        assertThat(run).as("PostgreSQL açılışı ertelenmiş indeks kurulumunu başlatmalı (SchedulerService.runOnStartup)").isNotNull();
        DeferredIndexBuilder.Result r = run.get(5, TimeUnit.MINUTES);
        assertThat(r).as("son koşu ikinci açılışınki olmalı").isEqualTo(PostgresIt.app().secondBoot().deferredIndexes());
        return r;
    }

    /** İndeksin geçerliliği; yoksa {@code null}. */
    private static Boolean indexValidity(String index) {
        List<Boolean> v = jdbc().queryForList("SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = ?",
                Boolean.class, index);
        return v.isEmpty() ? null : v.getFirst();
    }

    private static void assertUnique(Map<String, String> defs, String index, String table) {
        assertThat(defs).as("%s indeksi/kısıtı", index).containsKey(index);
        assertThat(defs.get(index)).as("%s", index).startsWith("CREATE UNIQUE INDEX")
                .containsPattern(" ON (\\w+\\.)?" + table + " USING ");
    }

    @Test
    @DisplayName("CONCURRENTLY kurulumları INVALID indeks bırakmadı (pg_index.indisvalid)")
    void noInvalidIndexes() throws Exception {
        awaitDeferredIndexes();   // arka planda süren CONCURRENTLY kurulum o an INVALID görünür
        List<String> invalid = jdbc().queryForList(
                "SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
              + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND NOT i.indisvalid",
                String.class);
        assertThat(invalid).as("INVALID indeksler (IF NOT EXISTS bunları bir daha kurmaz)").isEmpty();
    }

    // ── 3. Yamalarda adı geçen her indeks / kısıt / kolon gerçekten var mı ─────────────────────────────────────────

    @Test
    @DisplayName("Yamalarda adı geçen her indeks, tablosu varsa kurulmuş (42P01/42703 sessiz no-op'u yakalanır)")
    void everyIndexNamedInPatches_existsWhereItsTableExists() throws Exception {
        awaitDeferredIndexes();   // DeferredIndexBuilder.CATALOG da PATCH_SOURCES'ta: hazır olduktan sonra kurulur
        String src = patchSource();
        Set<String> dropped = names(src, "DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?(\\w+)", 1);
        Matcher m = Pattern.compile(
                "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(src);
        Map<String, String> declared = new LinkedHashMap<>();
        while (m.find()) declared.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), m.group(2).toLowerCase(Locale.ROOT));
        assertThat(declared).as("kaynakta bulunan indeks yamaları").hasSizeGreaterThan(50);

        Set<String> existing = indexDefs().keySet();
        List<String> missing = new ArrayList<>();
        List<String> tableAbsent = new ArrayList<>();
        declared.forEach((index, table) -> {
            if (dropped.contains(index)) return;
            if (!tableExists(table)) { tableAbsent.add(index + " ON " + table); return; }
            if (!existing.contains(index)) missing.add(index + " ON " + table);
        });
        if (!tableAbsent.isEmpty()) {
            System.out.println("[postgres-it] tablosu boş şemada hiç oluşmayan indeks yamaları (bilgi): " + tableAbsent);
        }
        assertThat(missing).as("tablosu VAR ama indeksi KURULMAMIŞ yamalar").isEmpty();
    }

    @Test
    @DisplayName("CREATE TABLE yamalarındaki adlı UNIQUE / PK kısıtları var (ddl-auto önce kurduysa da)")
    void everyNamedConstraintInPatches_exists() throws IOException {
        Set<String> declared = names(patchSource(), "CONSTRAINT\\s+(\\w+)\\s+(?:UNIQUE|PRIMARY\\s+KEY)", 1);
        assertThat(declared).as("kaynakta adlı kısıt").isNotEmpty();
        List<String> missing = declared.stream()
                .filter(c -> {
                    Integer n = jdbc().queryForObject("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n "
                            + "ON n.oid = c.connamespace WHERE n.nspname = current_schema() AND c.conname = ?", Integer.class, c);
                    return n == null || n == 0;
                })
                .toList();
        assertThat(missing).as("adı yamada geçen ama veritabanında OLMAYAN kısıtlar").isEmpty();
    }

    @Test
    @DisplayName("Yamalarda eklenen her kolon, tablosu varsa mevcut")
    void everyAddColumnPatch_existsWhereItsTableExists() throws IOException {
        String src = patchSource();
        Set<String> droppedCols = names(src, "ALTER\\s+TABLE\\s+(\\w+\\s+DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?\\w+)", 1)
                .stream().map(s -> s.replaceAll("(?i)\\s+DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?", ".")).collect(Collectors.toSet());
        Matcher m = Pattern.compile("ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(src);
        Set<String> declared = new LinkedHashSet<>();
        while (m.find()) declared.add(m.group(1).toLowerCase(Locale.ROOT) + "." + m.group(2).toLowerCase(Locale.ROOT));
        assertThat(declared).as("kaynakta bulunan kolon yamaları").hasSizeGreaterThan(100);

        List<String> missing = new ArrayList<>();
        for (String tc : declared) {
            if (droppedCols.contains(tc)) continue;
            String table = tc.substring(0, tc.indexOf('.'));
            String col = tc.substring(tc.indexOf('.') + 1);
            if (!tableExists(table)) continue;
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?", Integer.class, table, col);
            if (n == null || n == 0) missing.add(tc);
        }
        assertThat(missing).as("tablosu VAR ama kolonu EKLENMEMİŞ yamalar").isEmpty();
    }

    // ── 4. Advisory lock ve gerçek hata sayımı ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runExclusive: gövde koşarken kilit başka oturumca ALINAMAZ, bitince bırakılır; özet locked=true")
    void advisoryLock_isHeldDuringPatches_andReleasedAfter() throws Exception {
        SchemaPatchRunner runner = new SchemaPatchRunner(jdbc());
        AtomicReference<Boolean> otherGotLock = new AtomicReference<>();
        try (Connection other = PostgresIt.app().newSession()) {
            runner.runExclusive(() -> {
                boolean got = tryLock(other);
                otherGotLock.set(got);
                if (got) unlockQuietly(other);
                runner.patch("CREATE INDEX IF NOT EXISTS idx_ae_created_at ON alert_events(created_at)");   // zaten var
                runner.patch("ALTER TABLE alert_events ADD COLUMN team_id BIGINT");                          // zaten var
            });
            assertThat(otherGotLock.get()).as("yamalar koşarken ikinci oturum kilidi alabildi — kilit TUTULMUYOR").isFalse();
            boolean afterwards = tryLock(other);
            if (afterwards) unlockQuietly(other);
            assertThat(afterwards).as("runExclusive bittikten sonra kilit BIRAKILMALI").isTrue();
        }
        SchemaPatchRunner.Summary s = runner.finish();
        assertThat(s.locked()).isTrue();
        assertThat(s.failed()).isZero();
        assertThat(s.noop()).isEqualTo(2);
    }

    @Test
    @DisplayName("Kilit başka oturumdaysa sınırlı bekler, sonra kilitsiz koşar (açılış asla kilitte takılmaz)")
    void advisoryLock_busy_runsWithoutLock_afterBoundedWait() throws Exception {
        try (Connection holder = PostgresIt.app().newSession()) {
            assertThat(tryLock(holder)).as("test oturumu kilidi alabilmeli").isTrue();
            try {
                SchemaPatchRunner runner = new SchemaPatchRunner(jdbc(), 1_500);
                AtomicBoolean ran = new AtomicBoolean();
                long t0 = System.nanoTime();
                runner.runExclusive(() -> ran.set(true));
                long waitedMs = (System.nanoTime() - t0) / 1_000_000;
                assertThat(ran).as("gövde kilitsiz de olsa koşmalı").isTrue();
                assertThat(waitedMs).as("kilit için beklenen süre").isGreaterThanOrEqualTo(1_400);
                assertThat(runner.finish().locked()).isFalse();
            } finally {
                unlockQuietly(holder);
            }
        }
    }

    @Test
    @DisplayName("PostgreSQL'de GERÇEK yama hatası sayılır ve özette listelenir; 'zaten var' türü hata sayılmaz")
    void realPostgresFailure_isCounted_expectedOnesAreNot() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP TABLE IF EXISTS it_patch_probe");
        jdbc.execute("CREATE TABLE it_patch_probe (v VARCHAR(10))");
        try {
            SchemaPatchRunner runner = new SchemaPatchRunner(jdbc);
            runner.patch("CREATE TABLE IF NOT EXISTS it_patch_probe (v VARCHAR(10))");            // tablo var → noop
            runner.patch("ALTER TABLE it_patch_probe ADD COLUMN v VARCHAR(10)");                  // kolon var → noop
            runner.patch("ALTER TABLE it_patch_probe ADD CONSTRAINT it_probe_pk PRIMARY KEY (v)"); // uygulanır
            runner.patch("ALTER TABLE it_patch_probe ADD CONSTRAINT it_probe_pk PRIMARY KEY (v)"); // 42P16/42P07 → beklenen
            runner.patch("ALTER TABLE it_patch_probe ALTER COLUMN v TYPE INTEGER");                // 42804 → GERÇEK hata
            SchemaPatchRunner.Summary s = runner.finish();
            assertThat(s.failed()).as("gerçek hata sayılmalı: %s", s.failures()).isEqualTo(1);
            assertThat(s.failures()).singleElement().asString().contains("it_patch_probe");
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS it_patch_probe");
        }
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    private static boolean tryLock(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, SchemaPatchRunner.LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void unlockQuietly(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, SchemaPatchRunner.LOCK_KEY);
            ps.execute();
        } catch (Exception ignore) { /* oturum kapanınca zaten bırakılır */ }
    }

    /** Geçerli şemadaki indeks adı → tanımı ({@code pg_indexes.indexdef}). */
    private static Map<String, String> indexDefs() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc().queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema()")) {
            out.put(String.valueOf(r.get("indexname")).toLowerCase(Locale.ROOT), String.valueOf(r.get("indexdef")));
        }
        return out;
    }

    private static boolean tableExists(String table) {
        Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    /**
     * Yama kaynakları — yorum satırları atılır, ardışık dize birleştirmeleri ({@code "…" + "…"}) tek dizeye katlanır;
     * böylece iki satıra bölünmüş bir indeks yaması da yakalanır. Değişkenle kurulan adlar (döngüler) kapsam dışıdır.
     */
    private static String patchSource() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String rel : PATCH_SOURCES) {
            Path p = Path.of(rel);
            if (!Files.exists(p)) p = Path.of("backend").resolve(rel);   // IDE'den depo kökünde koşulursa
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                sb.append(line).append('\n');
            }
        }
        return sb.toString().replaceAll("\"\\s*\\+\\s*\"", "");
    }

    private static Set<String> names(String src, String regex, int group) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(src);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(group).toLowerCase(Locale.ROOT));
        return out;
    }
}
