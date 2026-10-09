package com.sitemonitor.service.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Açılış şema yamalarının çalıştırıcısı (2026-10-01, onaylı öneri 4: "şema yamalarını görünür ve kilitli yap").
 *
 * <p>{@code SchedulerService.applySchemaPatches()} içindeki ~320 idempotent {@code patch(ddl)} çağrısı buraya delege
 * edilir. Yama DAVRANIŞI birebir aynıdır (kolon/tablo varsa atla, UPDATE/INSERT/DELETE etkilenen satırı say, gerisini
 * çalıştır; hata açılışı DURDURMAZ). İki şey eklenir:
 * <ol>
 *   <li><b>Görünürlük.</b> Eskiden her hata DEBUG'da yutuluyordu: başarısız bir yama (kurulamayan indeks, genişlemeyen
 *       kolon) üretimde iz bırakmıyordu. Şimdi "zaten var / yok" türü BEKLENEN hatalar yine sessizdir; gerçek hata
 *       PostgreSQL'de WARN olarak yazılır, sayılır ve açılış sonunda özetlenir ({@link #finish()}); sayılar
 *       {@code sitemonitor.schema.patch.*} metrikleriyle dışarı verilir ({@link SchemaPatchMetrics}). PostgreSQL dışı
 *       veritabanında (testlerdeki H2) PG'ye özgü yamaların düşmesi beklendiği için DEBUG'da kalır.</li>
 *   <li><b>Tek pod.</b> Birden çok pod aynı anda açılırsa (iki replika, HPA, aynı anda yeniden başlatma) yamalar
 *       PostgreSQL oturum düzeyi advisory lock ile sıraya girer ({@link #runExclusive}). Kilit en fazla
 *       {@link #LOCK_WAIT_MS} beklenir; alınamazsa eskisi gibi kilitsiz devam edilir (açılış asla kilitte takılmaz).</li>
 * </ol>
 */
public final class SchemaPatchRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchRunner.class);

    /** Advisory lock anahtarı — "SMSCHEMA" baytları; uygulamada başka advisory lock kullanılmıyor. */
    public static final long LOCK_KEY = 0x534D_5343_4845_4D41L;
    /** Başka bir pod yama koşarken en fazla bu kadar beklenir (sonra kilitsiz devam). */
    public static final long LOCK_WAIT_MS = 60_000;
    private static final long LOCK_POLL_MS = 1_000;
    /** Özette ve sistem sağlığında tutulan en fazla hata satırı. */
    private static final int MAX_FAILURES = 20;

    private static final Pattern ADD_COL_RE =
            Pattern.compile("(?i)ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)");
    private static final Pattern CREATE_TBL_RE =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");

    /** Son açılışın özeti (metrikler ve testler okur). */
    public record Summary(int applied, int noop, int failed, List<String> failures, boolean locked, long finishedAtMs) {}

    private static final AtomicReference<Summary> LAST = new AtomicReference<>();

    /** Son açılışta koşan yamaların özeti; henüz koşmadıysa null. */
    public static Summary last() { return LAST.get(); }

    private final JdbcTemplate jdbc;
    private final long lockWaitMs;
    private Boolean postgres;
    private int applied;
    private int noop;
    private int failed;
    private final List<String> failures = new ArrayList<>();
    private boolean locked;

    public SchemaPatchRunner(JdbcTemplate jdbc) {
        this(jdbc, LOCK_WAIT_MS);
    }

    /** Test: kilit bekleme süresi kısaltılabilir. */
    SchemaPatchRunner(JdbcTemplate jdbc, long lockWaitMs) {
        this.jdbc = jdbc;
        this.lockWaitMs = lockWaitMs;
    }

    /** Idempotent şema/veri yaması — davranış {@code SchedulerService.patch} ile birebir aynı; hata açılışı durdurmaz. */
    public void patch(String ddl) {
        String shortDdl = ddl.length() > 60 ? ddl.substring(0, 60) + "…" : ddl;
        String head = ddl.trim().toUpperCase(Locale.ROOT);
        try {
            if (head.startsWith("ALTER TABLE") && head.contains(" ADD COLUMN ")) {
                var m = ADD_COL_RE.matcher(ddl);
                if (m.find() && columnExists(m.group(1), m.group(2))) {     // kolon zaten var → hiç çalıştırma
                    noop++;
                    log.debug("Schema patch noop (column exists): {}", shortDdl);
                    return;
                }
                jdbc.execute(ddl);
                applied++;
                log.info("Schema patch applied (column added): {}", shortDdl);
            } else if (head.startsWith("CREATE TABLE")) {
                var m = CREATE_TBL_RE.matcher(ddl);
                if (m.find() && tableExists(m.group(1))) {                  // tablo zaten var → atla
                    noop++;
                    log.debug("Schema patch noop (table exists): {}", shortDdl);
                    return;
                }
                jdbc.execute(ddl);
                applied++;
                log.info("Schema patch applied (table created): {}", shortDdl);
            } else if (head.startsWith("UPDATE") || head.startsWith("INSERT") || head.startsWith("DELETE")) {
                int rows = jdbc.update(ddl);                                // gerçek değişiklik = etkilenen satır > 0
                if (rows > 0) { applied++; log.info("Schema patch applied ({} row(s)): {}", rows, shortDdl); }
                else          { noop++;    log.debug("Schema patch noop (0 rows): {}", shortDdl); }
            } else {
                jdbc.execute(ddl);   // CREATE [UNIQUE] INDEX IF NOT EXISTS, DROP ..., ALTER ... TYPE — idempotent, sessiz
                noop++;
                log.debug("Schema patch ran: {}", shortDdl);
            }
        } catch (Exception e) {
            String msg = rootMessage(e);
            if (isExpected(sqlState(e), msg) || !isPostgres()) {
                noop++;
                log.debug("Schema patch skipped: {}", msg);
                return;
            }
            failed++;
            if (failures.size() < MAX_FAILURES) failures.add(shortDdl + " → " + firstLine(msg));
            log.warn("⚠ Şema yaması BAŞARISIZ (açılış sürüyor): {} — {}", shortDdl, firstLine(msg));
        }
    }

    /**
     * SQLSTATE kodları (sunucu dilinden bağımsız): "zaten var / zaten yok / yinelenen" sınıfı. PostgreSQL mesajları
     * {@code lc_messages}'a göre Türkçe gelebilir; bu yüzden önce kod, sonra İngilizce mesaj denetlenir.
     */
    private static final java.util.Set<String> EXPECTED_STATES = java.util.Set.of(
            "42701",  // duplicate_column
            "42P07",  // duplicate_table (indeks / ilişki dâhil)
            "42710",  // duplicate_object (kısıt)
            "42723",  // duplicate_function
            "42P06",  // duplicate_schema
            "42P16",  // invalid_table_definition (ikinci birincil anahtar)
            "42P01",  // undefined_table (kaldırılmış nesneye DROP / ALTER)
            "42704",  // undefined_object
            "42703",  // undefined_column
            "23505"); // unique_violation (zaten eklenmiş tohum satırı)

    /**
     * "Zaten var / zaten yok / yinelenen" türü hatalar idempotent yamanın olağan sonucudur (ör. IF NOT EXISTS
     * desteklemeyen ifade, ikinci kez eklenen kısıt, kaldırılmış nesneye DROP) — sessiz kalır.
     */
    static boolean isExpected(String sqlState, String msg) {
        if (sqlState != null && EXPECTED_STATES.contains(sqlState)) return true;
        if (msg == null) return true;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("already exists") || m.contains("does not exist") || m.contains("duplicate")
                || m.contains("multiple primary keys");
    }

    private static String sqlState(Throwable e) {
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable t = e; t != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException se && se.getSQLState() != null) return se.getSQLState();
        }
        return null;
    }

    /**
     * {@code body}'yi PostgreSQL advisory lock altında çalıştırır: aynı anda yalnız bir pod yama koşar. Kilit ayrı bir
     * bağlantıda (oturum düzeyi) tutulur ve iş bitince — hata olsa da — bırakılır. PostgreSQL değilse ya da kilit
     * {@link #LOCK_WAIT_MS} içinde alınamazsa {@code body} kilitsiz koşar (eski davranış).
     */
    public void runExclusive(Runnable body) {
        if (!isPostgres()) { body.run(); return; }
        DataSource ds = jdbc.getDataSource();
        if (ds == null) { body.run(); return; }
        Connection conn = null;
        try {
            conn = ds.getConnection();
            locked = acquire(conn);
        } catch (Exception e) {
            log.warn("Şema yaması kilidi alınamadı ({}), kilitsiz devam ediliyor", firstLine(rootMessage(e)));
            locked = false;
        }
        try {
            body.run();
        } finally {
            if (conn != null) {
                if (locked) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, LOCK_KEY);
                        ps.execute();
                    } catch (Exception e) {
                        log.warn("Şema yaması kilidi bırakılamadı: {}", firstLine(rootMessage(e)));
                    }
                }
                try { conn.close(); } catch (Exception ignore) { /* havuza dönüş */ }
            }
        }
    }

    private boolean acquire(Connection conn) throws Exception {
        long deadline = System.currentTimeMillis() + lockWaitMs;
        boolean waitedLogged = false;
        while (true) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        if (waitedLogged) log.info("Şema yaması kilidi alındı (diğer pod bitirdi)");
                        return true;
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("Şema yaması kilidi {} sn içinde alınamadı — başka bir pod hâlâ koşuyor olabilir; kilitsiz devam",
                        lockWaitMs / 1000);
                return false;
            }
            if (!waitedLogged) { log.info("Başka bir pod şema yamalarını koşuyor; bekleniyor…"); waitedLogged = true; }
            Thread.sleep(Math.min(LOCK_POLL_MS, Math.max(1, lockWaitMs / 4)));
        }
    }

    /** Açılış sonu: özeti yazar (başarısız varsa WARN) ve metriklere yayımlar. */
    public Summary finish() {
        Summary s = new Summary(applied, noop, failed, List.copyOf(failures), locked, System.currentTimeMillis());
        LAST.set(s);
        if (failed > 0) {
            log.warn("⚠ Şema yamaları: {} uygulandı, {} zaten uygulanmış, {} BAŞARISIZ{} — ilkleri: {}", applied, noop, failed,
                    locked ? "" : " (kilitsiz)", failures);
        } else {
            log.info("Şema yamaları: {} uygulandı, {} zaten uygulanmış, 0 başarısız{}", applied, noop,
                    isPostgres() ? (locked ? " (kilitli)" : " (kilitsiz)") : "");
        }
        return s;
    }

    private boolean isPostgres() {
        if (postgres == null) {
            try {
                DataSource ds = jdbc.getDataSource();
                if (ds == null) { postgres = false; return false; }
                try (Connection c = ds.getConnection()) {
                    postgres = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
                }
            } catch (Exception e) {
                postgres = false;
            }
        }
        return postgres;
    }

    private boolean columnExists(String table, String col) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
                Integer.class, table, col);
        return n != null && n > 0;
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, table);
        return n != null && n > 0;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        while (t.getCause() != null && t.getCause() != t && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH) t = t.getCause();
        String m = t.getMessage();
        return m != null ? m : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String line = i >= 0 ? s.substring(0, i) : s;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
