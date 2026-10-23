package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Kalıcı değerlendirme durumu tablolarının DDL'i ve SQL'i (2026-10-23, çok pod Sürüm 3-1, paket A1; tasarım R3-1 §2.2 /
 * §2.3). Entity YOK (sıcak yol, ddl-auto kayması yok) — dört ham tablo {@link EvalStateSchemaPatch} ile kurulur, yazımlar
 * yalnız {@link EvalStateRecorder}'ın yazıcı iş parçacığında, işlem DIŞINDA (otomatik commit; PostgreSQL 25P02 hiçbir
 * çağıranın işlemini bozamaz) yapılır.
 *
 * <p><b>Tablolar (adlar KALICI — yayından sonra yeniden adlandırılmaz):</b> {@link #EVAL_STATE} (alarm anahtarı başına
 * kurtarma durumu), {@link #LAST_STATUS} (kalem başına son gözlem), {@link #TYPE_STATE} (tür başına bayraklar + kuşak
 * satırı {@link EvalStateKeys#GENERATION_ROW}), {@link #CONFIRMATIONS} (süren teyit zincirleri). Ortak sütunlar:
 * {@code writer} (yazan pod), {@code gen} (lider kuşağı — DB çit belirteci), {@code seq} (pod'a özgü artan sıra),
 * {@code updated_ms}, {@code updated_at} (UTC ISO, insanlar için).
 *
 * <p><b>"Yalnız daha yeni (gen, seq) kazanır":</b> her veri yazımı koşulludur — PostgreSQL'de tek ifadelik
 * {@code INSERT … ON CONFLICT … DO UPDATE … WHERE cur.gen < EXCLUDED.gen OR (cur.gen = EXCLUDED.gen AND cur.seq <
 * EXCLUDED.seq)}; H2 / bilinmeyen türde koşullu UPDATE → {@code INSERT … ON CONFLICT DO NOTHING} → (yarış) UPDATE. Eski
 * bir liderin geç kalan boşaltması yeni liderin satırını ezemez.
 *
 * <p><b>Kuşak:</b> {@link #acquireGeneration} {@code '*'} satırında CAS (taşınabilir: oku → {@code gen = v + 1 WHERE gen =
 * v}); küme kaydı varken CAS AYNI ifadede tarama lideri kirasına bağlıdır ({@code lease:sweep-leader} satırı bu yazanın) —
 * kirayı kaybetmiş eski bir lider asla kuşak alamaz (R3-1 FX-STATE). {@link #heartbeat} her boşaltmanın başında çit
 * denetimi — 0 satır = daha yeni bir lider kuşak aldı. {@code '*'} satırı yoksa (ilk açılış ya da elle silindi) var olan
 * satırların en büyük kuşağıyla tohumlanır: yeni kuşak her eski satırın üstündedir.
 *
 * <p>Değer metinleri asla günlüğe yazılmaz ({@code ctx_json} / {@code attempts_json} bağlamda kontrol çıktısı taşıyabilir).
 */
public final class EvalStateSql {

    // ── Tablo adları (kalıcı) ────────────────────────────────────────────────────────────────────────────────────────

    public static final String EVAL_STATE = EvalStateKeys.Table.EVAL_STATE.tableName();
    public static final String LAST_STATUS = EvalStateKeys.Table.LAST_STATUS.tableName();
    public static final String TYPE_STATE = EvalStateKeys.Table.TYPE_STATE.tableName();
    public static final String CONFIRMATIONS = EvalStateKeys.Table.CONFIRMATIONS.tableName();

    /** Dört tablo, kurulum sırasıyla. */
    public static final List<String> TABLES = List.of(EVAL_STATE, LAST_STATUS, TYPE_STATE, CONFIRMATIONS);

    // ── Sütun sınırları ve sabit değerler ───────────────────────────────────────────────────────────────────────────

    public static final int ALERT_TYPE_MAX = 40;
    public static final int DOMAIN_MAX = 500;
    public static final int ITEM_KEY_MAX = 600;
    public static final int DETAIL_MAX = 255;
    public static final int WRITER_MAX = 200;
    public static final int MONITOR_TYPE_MAX = 20;
    public static final int ERROR_CLASS_MAX = 16;
    public static final int ISO_MAX = 19;
    /** {@code attempts_json} / {@code ctx_json} üst sınırı (UTF-8 bayt); aşan değer yazılmaz (null). */
    public static final int JSON_MAX_BYTES = 16 * 1024;
    /**
     * Kaydedici / doğrulayıcı ifadelerinin sorgu zaman aşımı (sn; R3-1 FX-STATE) — bir kilit beklemesi ya da yarı açık
     * bağlantı tek yazıcı iş parçacığını (ayna + doğrulama) havuzun soket zaman aşımına (30 dk) dek durdurmasın.
     */
    public static final int QUERY_TIMEOUT_SECONDS = 15;
    /** Tarama lideri kirasının {@code monitor_check_schedule} anahtarı ({@code SchedulerService.SWEEP_LEADER}; test eşitler). */
    public static final String LEASE_KEY = ClusterScheduleService.LEASE_PREFIX + "sweep-leader";

    /** {@code monitor_confirmations.state} değerleri (kalıcı). */
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_FINISHING = "FINISHING";
    public static final String STATE_ABANDONED = "ABANDONED";

    /** {@code monitor_last_status.source}: zamanlanmış / elle (kalıcı). */
    public static final String SOURCE_SCHEDULED = "S";
    public static final String SOURCE_MANUAL = "M";

    private static final String COMMON_DDL = "writer VARCHAR(200) NOT NULL, gen BIGINT NOT NULL, seq BIGINT NOT NULL, "
            + "updated_ms BIGINT NOT NULL, updated_at VARCHAR(19) NOT NULL";

    // ── DDL ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    public static final String DDL_EVAL_STATE = "CREATE TABLE IF NOT EXISTS " + EVAL_STATE + " ("
            + "alert_type VARCHAR(40) NOT NULL, "
            + "domain VARCHAR(500) NOT NULL, "
            + "recovery_up_count INT NOT NULL, "
            + "recovery_last_counted_ms BIGINT, "
            + "recovery_chain_gen BIGINT, "
            + "recovery_chain_started_ms BIGINT, "
            + COMMON_DDL + ", "
            + "PRIMARY KEY (alert_type, domain))";

    public static final String DDL_LAST_STATUS = "CREATE TABLE IF NOT EXISTS " + LAST_STATUS + " ("
            + "alert_type VARCHAR(40) NOT NULL, "
            + "item_key VARCHAR(600) NOT NULL, "
            + "domain VARCHAR(500) NOT NULL, "
            + "detail VARCHAR(255), "
            + "monitor_id BIGINT, "
            + "team_id BIGINT, "
            + "standalone BOOLEAN, "
            + "up BOOLEAN NOT NULL, "
            + "network_down BOOLEAN NOT NULL, "
            + "error_class VARCHAR(16), "
            + "source CHAR(1) NOT NULL, "
            + "observed_ms BIGINT NOT NULL, "
            + "changed_ms BIGINT NOT NULL, "
            + COMMON_DDL + ", "
            + "PRIMARY KEY (alert_type, item_key))";

    /** Kardeş / pencere okumaları için (tablo ile birlikte, boşken kurulur — ertelenmiş katalog gerekmez). */
    public static final String DDL_LAST_STATUS_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_mls_type_domain ON " + LAST_STATUS + "(alert_type, domain)";

    public static final String DDL_TYPE_STATE = "CREATE TABLE IF NOT EXISTS " + TYPE_STATE + " ("
            + "alert_type VARCHAR(40) NOT NULL PRIMARY KEY, "
            + "suppression_active BOOLEAN, "
            + "suppression_since_ms BIGINT, "
            + "last_scheduled_round_ms BIGINT, "
            + "round_interval_ms BIGINT, "
            + COMMON_DDL + ")";

    public static final String DDL_CONFIRMATIONS = "CREATE TABLE IF NOT EXISTS " + CONFIRMATIONS + " ("
            + "key_hash VARCHAR(64) NOT NULL PRIMARY KEY, "
            + "confirm_key TEXT NOT NULL, "
            + "chain_id BIGINT NOT NULL, "
            + "alert_type VARCHAR(40) NOT NULL, "
            + "domain VARCHAR(500) NOT NULL, "
            + "detail VARCHAR(255), "
            + "monitor_type VARCHAR(20), "
            + "monitor_id BIGINT, "
            + "attempt INT NOT NULL, "
            + "total_attempts INT NOT NULL, "
            + "delay_ms BIGINT NOT NULL, "
            + "first_failure_at VARCHAR(19) NOT NULL, "
            + "next_attempt_ms BIGINT, "
            + "attempts_json TEXT, "
            + "ctx_json TEXT, "
            + "ctx_truncated BOOLEAN NOT NULL, "
            + "state VARCHAR(16) NOT NULL, "
            + "created_ms BIGINT NOT NULL, "
            + COMMON_DDL + ")";

    /** Tablo → DDL ({@link #TABLES} sırasıyla). */
    public static final List<String> TABLE_DDL = List.of(DDL_EVAL_STATE, DDL_LAST_STATUS, DDL_TYPE_STATE, DDL_CONFIRMATIONS);

    /**
     * Yalnız PostgreSQL: sık güncellenen üç tablo %70 doluluk — güncellemeler HOT kalsın (dizinli sütun değişmez).
     * {@code SchemaDdlGuard} {@code SET (reloptions)} biçimini tanır (zaten uygulandıysa kilitsiz atlar).
     */
    public static final List<String> FILLFACTOR_DDL = List.of(
            "ALTER TABLE " + EVAL_STATE + " SET (fillfactor = 70)",
            "ALTER TABLE " + LAST_STATUS + " SET (fillfactor = 70)",
            "ALTER TABLE " + CONFIRMATIONS + " SET (fillfactor = 70)");

    // ── Yazım tanımları ─────────────────────────────────────────────────────────────────────────────────────────────

    private static final List<String> COMMON = List.of("writer", "gen", "seq", "updated_ms", "updated_at");

    /** Bir tablonun anahtar / değer sütunları ve onlardan üretilen üç ifade. */
    static final class Spec {
        final String table;
        final List<String> keys;
        final List<String> values;
        final String pgUpsert;
        final String update;
        final String insert;

        Spec(String table, List<String> keys, List<String> values) {
            this.table = table;
            this.keys = List.copyOf(keys);
            this.values = List.copyOf(values);
            List<String> all = new ArrayList<>(keys);
            all.addAll(values);
            all.addAll(COMMON);
            String cols = String.join(", ", all);
            String marks = String.join(", ", java.util.Collections.nCopies(all.size(), "?"));
            List<String> settable = new ArrayList<>(values);
            settable.addAll(COMMON);
            StringBuilder excluded = new StringBuilder();
            StringBuilder assign = new StringBuilder();
            for (String c : settable) {
                if (!excluded.isEmpty()) { excluded.append(", "); assign.append(", "); }
                excluded.append(c).append(" = EXCLUDED.").append(c);
                assign.append(c).append(" = ?");
            }
            StringBuilder where = new StringBuilder();
            for (String k : keys) where.append(k).append(" = ? AND ");
            this.pgUpsert = "INSERT INTO " + table + " AS cur (" + cols + ") VALUES (" + marks + ") ON CONFLICT ("
                    + String.join(", ", keys) + ") DO UPDATE SET " + excluded
                    + " WHERE cur.gen < EXCLUDED.gen OR (cur.gen = EXCLUDED.gen AND cur.seq < EXCLUDED.seq)";
            this.update = "UPDATE " + table + " SET " + assign + " WHERE " + where
                    + "(gen < ? OR (gen = ? AND seq < ?))";
            this.insert = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT DO NOTHING";
        }
    }

    static final Spec SPEC_EVAL_STATE = new Spec(EVAL_STATE, List.of("alert_type", "domain"),
            List.of("recovery_up_count", "recovery_last_counted_ms", "recovery_chain_gen", "recovery_chain_started_ms"));
    static final Spec SPEC_LAST_STATUS = new Spec(LAST_STATUS, List.of("alert_type", "item_key"),
            List.of("domain", "detail", "monitor_id", "team_id", "standalone", "up", "network_down", "error_class", "source",
                    "observed_ms", "changed_ms"));
    static final Spec SPEC_TYPE_STATE = new Spec(TYPE_STATE, List.of("alert_type"),
            List.of("suppression_active", "suppression_since_ms", "last_scheduled_round_ms", "round_interval_ms"));
    static final Spec SPEC_CONFIRMATIONS = new Spec(CONFIRMATIONS, List.of("key_hash"),
            List.of("confirm_key", "chain_id", "alert_type", "domain", "detail", "monitor_type", "monitor_id", "attempt",
                    "total_attempts", "delay_ms", "first_failure_at", "next_attempt_ms", "attempts_json", "ctx_json",
                    "ctx_truncated", "state", "created_ms"));

    // ── Kuşak / çit / budama / devralma SQL'i ───────────────────────────────────────────────────────────────────────

    static final String SQL_GEN_READ = "SELECT gen FROM " + TYPE_STATE + " WHERE alert_type = ?";
    static final String SQL_GEN_SEED = "INSERT INTO " + TYPE_STATE + " (alert_type, suppression_active, suppression_since_ms, "
            + "last_scheduled_round_ms, round_interval_ms, writer, gen, seq, updated_ms, updated_at) "
            + "VALUES (?, NULL, NULL, NULL, NULL, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING";
    static final String SQL_GEN_CAS = "UPDATE " + TYPE_STATE + " SET gen = ?, writer = ?, seq = 0, updated_ms = ?, "
            + "updated_at = ? WHERE alert_type = ? AND gen = ?";
    /**
     * Kiraya bağlı CAS (R3-1 FX-STATE, bulgu #1): kuşak yalnız {@code lease:sweep-leader} satırı BU yazanınken alınır —
     * aynı ifadede, ayrı bir okuma değil. Gerçek lider kuşağını kirayı ALDIKTAN sonra CAS'lar; kirayı kaybetmiş pod'un geç
     * CAS'ı ya gerçek liderinkinden önce işlenir (sonra çitlenir) ya da kirayı başkasında görür (0 satır).
     */
    static final String SQL_GEN_CAS_LEASE = SQL_GEN_CAS + " AND EXISTS (SELECT 1 FROM monitor_check_schedule "
            + "WHERE monitor_key = ? AND claimed_by = ?)";
    static final String SQL_HEARTBEAT = "UPDATE " + TYPE_STATE + " SET updated_ms = ?, updated_at = ? "
            + "WHERE alert_type = ? AND gen = ? AND writer = ?";
    static final String SQL_GEN_ROW = "SELECT gen, writer, updated_ms FROM " + TYPE_STATE + " WHERE alert_type = ?";

    static final String SQL_CHAIN_DELETE = "DELETE FROM " + CONFIRMATIONS
            + " WHERE key_hash = ? AND chain_id = ? AND writer = ? AND gen <= ?";
    static final String SQL_MARK_ABANDONED = "UPDATE " + CONFIRMATIONS + " SET state = '" + STATE_ABANDONED
            + "', updated_ms = ?, updated_at = ? WHERE gen < ? AND state <> '" + STATE_ABANDONED + "'";

    /** Kalemin son durum satırını koşullu siler (bellek DOWN gözlemini budadı) — "yalnız daha yeni (gen, seq)" koruması. */
    static final String SQL_LAST_STATUS_DELETE = "DELETE FROM " + LAST_STATUS
            + " WHERE alert_type = ? AND item_key = ? AND (gen < ? OR (gen = ? AND seq < ?))";

    static final String SQL_PRUNE_LAST_STATUS = "DELETE FROM " + LAST_STATUS + " WHERE observed_ms < ?";
    /**
     * Kurtarma budaması: sayaçsız + zincirsiz eski satır, YA DA daha eski bir kuşağın eski satırı (sayıdan bağımsız — bayat
     * sayaç hiç yeniden yazılmazsa sonsuza dek kalır ve "devralınan" görünürdü; R3-1 FX-STATE, bulgu #3).
     */
    static final String SQL_PRUNE_EVAL_STATE = "DELETE FROM " + EVAL_STATE
            + " WHERE updated_ms < ? AND ((recovery_up_count = 0 AND recovery_chain_gen IS NULL) OR gen < ?)";
    static final String SQL_PRUNE_CONFIRMATIONS = "DELETE FROM " + CONFIRMATIONS
            + " WHERE state = '" + STATE_ABANDONED + "' OR updated_ms < ?";
    /** Tür satırları: yalnız daha eski kuşağın eski satırları ({@code '*'} asla). */
    static final String SQL_PRUNE_TYPE_STATE = "DELETE FROM " + TYPE_STATE
            + " WHERE alert_type <> ? AND gen < ? AND updated_ms < ?";

    /** Devralınan sayılar YALNIZ bir önceki kuşağın (bu pod'un CAS'ının yerini aldığı) satırları — bulgu #3. */
    static final String SQL_INHERITED_RECOVERY = "SELECT COUNT(*) FROM " + EVAL_STATE
            + " WHERE gen = ? AND recovery_up_count > 0";
    static final String SQL_INHERITED_SIBLINGS = "SELECT COUNT(*) FROM " + LAST_STATUS
            + " WHERE gen = ? AND up = FALSE AND monitor_id IS NOT NULL AND observed_ms >= ?";
    static final String SQL_INHERITED_SUPPRESSIONS = "SELECT COUNT(*) FROM " + TYPE_STATE
            + " WHERE gen = ? AND alert_type <> ? AND suppression_active = TRUE";

    /** JDBC toplu yazım parça boyu. */
    static final int CHUNK = 200;
    /** Kuşak CAS deneme sayısı. */
    static final int GEN_CAS_TRIES = 5;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Satırlar ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code monitor_eval_state} satırı (bellek: {@code recoveryUpCount} …; sıfırlama = sıfır / null yazılır, silinmez). */
    public record RecoveryRow(String alertType, String domain, int upCount, Long lastCountedMs, Long chainGen,
                              Long chainStartedMs, long seq) {}

    /** {@code monitor_last_status} satırı (kalem başına son gözlem). {@code source}: {@link #SOURCE_SCHEDULED} | {@link #SOURCE_MANUAL}. */
    public record LastStatusRow(String alertType, String itemKey, String domain, String detail, Long monitorId,
                                Long teamId, Boolean standalone, boolean up, boolean networkDown, String errorClass,
                                String source, long observedMs, long changedMs, long seq) {}

    /** {@code monitor_eval_type_state} satırı (tür başına; {@code '*'} kuşak satırı BURADAN yazılmaz). */
    public record TypeRow(String alertType, Boolean suppressionActive, Long suppressionSinceMs, Long lastScheduledRoundMs,
                          Long roundIntervalMs, long seq) {}

    /** {@code monitor_confirmations} satırı (süren teyit zinciri). */
    public record ChainRow(String keyHash, String confirmKey, long chainId, String alertType, String domain, String detail,
                           String monitorType, Long monitorId, int attempt, int totalAttempts, long delayMs,
                           String firstFailureAt, Long nextAttemptMs, String attemptsJson, String ctxJson,
                           boolean ctxTruncated, String state, long createdMs, long seq) {

        /** Aynı zincirin ilerlemiş hâli (tanım alanları korunur; {@code attemptsJson} olduğu gibi yazılır). */
        public ChainRow progressed(int attempt, int totalAttempts, Long nextAttemptMs, String attemptsJson, String state,
                                   long seq) {
            return new ChainRow(keyHash, confirmKey, chainId, alertType, domain, detail, monitorType, monitorId, attempt,
                    totalAttempts, delayMs, firstFailureAt, nextAttemptMs, attemptsJson, ctxJson, ctxTruncated, state,
                    createdMs, seq);
        }

        /** Yalnız durum ve sıra değişir. */
        public ChainRow withState(String state, long seq) {
            return new ChainRow(keyHash, confirmKey, chainId, alertType, domain, detail, monitorType, monitorId, attempt,
                    totalAttempts, delayMs, firstFailureAt, nextAttemptMs, attemptsJson, ctxJson, ctxTruncated, state,
                    createdMs, seq);
        }
    }

    /** Zincir bitti: satırı koşullu sil (anahtar + zincir + yazan + kuşak ≤ benim). */
    public record ChainDelete(String keyHash, long chainId, long seq) {}

    /** Bellek kalemin DOWN gözlemini budadı: son durum satırını koşullu sil ("yalnız daha yeni (gen, seq)"). */
    public record LastStatusDelete(String alertType, String itemKey, long seq) {}

    /** Okunan bir satır + yazım üst verisi. */
    public record Stored<R>(R row, String writer, long gen, long updatedMs) {}

    /** {@code '*'} kuşak / nabız satırı. */
    public record GenerationRow(long gen, String writer, long updatedMs) {}

    // ── Örnek ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private final JdbcTemplate jdbc;
    /** Üç durumlu tür: TRUE PostgreSQL, FALSE başka, null henüz bilinmiyor (bilinmeyen = taşınabilir yol — PG'de de doğru). */
    private volatile Boolean postgres;

    public EvalStateSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Aynı veri kaynağı üzerinde, sorgu zaman aşımı {@code seconds} olan KENDİ şablonu (R3-1 FX-STATE, bulgu #6) — paylaşılan
     * şablonun ayarlarına dokunulmaz. Veri kaynağı yoksa (sahte şablon) ya da {@code seconds ≤ 0} ise şablon olduğu gibi
     * döner. Hata çevirici paylaşılır ({@code BadSqlGrammarException} ayrımı aynı kalır).
     */
    public static JdbcTemplate withQueryTimeout(JdbcTemplate base, int seconds) {
        if (base == null || seconds <= 0 || base.getDataSource() == null) return base;
        JdbcTemplate t = new JdbcTemplate(base.getDataSource());
        t.setExceptionTranslator(base.getExceptionTranslator());
        t.setFetchSize(base.getFetchSize());
        t.setMaxRows(base.getMaxRows());
        t.setQueryTimeout(seconds);
        return t;
    }

    /** Test: türü sabitler (gerçek PostgreSQL'de taşınabilir yolu da sınamak için; null = bilinmiyor → taşınabilir). */
    static EvalStateSql withDialect(JdbcTemplate jdbc, Boolean postgres) {
        EvalStateSql s = new EvalStateSql(jdbc);
        s.postgres = postgres;
        return s;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Veritabanı türü; yalnız BİLİNEN cevap ezberlenir ({@code ManualEvalInbox.postgres()} kuralı). */
    public Boolean postgres() {
        Boolean p = postgres;
        if (p != null) return p;
        p = DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
    }

    /** Dört tablonun hepsi okunabiliyor mu (veri yolunun kendi şema çözümüyle). Okuma hatası = yok sayılmaz, fırlatır. */
    public boolean tablesExist() {
        for (String t : TABLES) {
            try {
                jdbc.queryForList("SELECT 1 FROM " + t + " WHERE 1 = 0");
            } catch (BadSqlGrammarException missing) {
                return false;
            }
        }
        return true;
    }

    public static String iso(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    // ── Kuşak ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Kiraya bağlanmadan kuşak alır (tek pod / küme kaydı yok / testler) — {@link #acquireGeneration(String, long, String, BooleanSupplier)}. */
    public long acquireGeneration(String writer, long nowMs) {
        return acquireGeneration(writer, nowMs, null, () -> true);
    }

    /**
     * {@code '*'} satırında yeni kuşak alır (≤ {@value #GEN_CAS_TRIES} CAS denemesi). Satır yoksa var olan satırların en büyük
     * kuşağıyla (hiç yoksa 0) tohumlanır — yeni kuşak her eski satırın üstünde (bulgu #5).
     *
     * @param leaseOwner  null değilse CAS {@code lease:sweep-leader} satırının bu sahipte olmasına bağlıdır (aynı ifade); kira
     *                    başkasındaysa 0 döner (değer değişmediyse yeniden denenmez)
     * @param stillWanted HER denemeden önce sorulur (lider ipucu + liderlik dönemi hâlâ aynı mı) — false → 0, CAS yapılmaz
     * @return alınan kuşak (> 0) ya da 0 (kira yok / istenmiyor / her CAS kaybedildi)
     */
    public long acquireGeneration(String writer, long nowMs, String leaseOwner, BooleanSupplier stillWanted) {
        String w = clip(writer, WRITER_MAX);
        for (int i = 0; i < GEN_CAS_TRIES; i++) {
            if (stillWanted != null && !stillWanted.getAsBoolean()) return 0L;
            List<Long> rows = jdbc.queryForList(SQL_GEN_READ, Long.class, EvalStateKeys.GENERATION_ROW);
            if (rows.isEmpty() || rows.get(0) == null) {
                try {
                    jdbc.update(SQL_GEN_SEED, EvalStateKeys.GENERATION_ROW, w, maxExistingGeneration(), nowMs, iso(nowMs));
                } catch (DuplicateKeyException race) {
                    // öteki pod aynı anda tohumladı — yeniden oku
                }
                continue;
            }
            long v = rows.get(0);
            if (stillWanted != null && !stillWanted.getAsBoolean()) return 0L;
            int n = leaseOwner == null
                    ? jdbc.update(SQL_GEN_CAS, v + 1, w, nowMs, iso(nowMs), EvalStateKeys.GENERATION_ROW, v)
                    : jdbc.update(SQL_GEN_CAS_LEASE, v + 1, w, nowMs, iso(nowMs), EvalStateKeys.GENERATION_ROW, v,
                            LEASE_KEY, clip(leaseOwner, WRITER_MAX));
            if (n == 1) return v + 1;
            if (leaseOwner != null) {
                // Değer değişmediyse kaybın nedeni kira (başkasında / yok) — yeniden denemek anlamsız.
                List<Long> again = jdbc.queryForList(SQL_GEN_READ, Long.class, EvalStateKeys.GENERATION_ROW);
                if (!again.isEmpty() && again.get(0) != null && again.get(0) == v) return 0L;
            }
        }
        return 0L;
    }

    /** Dört tablodaki en büyük kuşak ({@code '*'} hariç; hiç satır yoksa 0) — {@code '*'} yeniden tohumlanırken taban. */
    long maxExistingGeneration() {
        long max = 0L;
        for (String t : TABLES) {
            Long g = TYPE_STATE.equals(t)
                    ? jdbc.queryForObject("SELECT MAX(gen) FROM " + t + " WHERE alert_type <> ?", Long.class,
                            EvalStateKeys.GENERATION_ROW)
                    : jdbc.queryForObject("SELECT MAX(gen) FROM " + t, Long.class);
            if (g != null && g > max) max = g;
        }
        return max;
    }

    /** Çit nabzı: {@code '*'} satırı hâlâ bu kuşak + bu yazanın mı (1 satır) — değilse daha yeni bir lider kuşak aldı. */
    public boolean heartbeat(long gen, String writer, long nowMs) {
        return jdbc.update(SQL_HEARTBEAT, nowMs, iso(nowMs), EvalStateKeys.GENERATION_ROW, gen, clip(writer, WRITER_MAX)) == 1;
    }

    /** {@code '*'} satırı (yoksa null) — 3-2'nin süreklilik denetimi ve testler için. */
    public GenerationRow generationRow() {
        List<GenerationRow> rows = jdbc.query(SQL_GEN_ROW,
                (rs, i) -> new GenerationRow(rs.getLong(1), rs.getString(2), rs.getLong(3)), EvalStateKeys.GENERATION_ROW);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Yazımlar ────────────────────────────────────────────────────────────────────────────────────────────────────

    public int writeRecovery(List<RecoveryRow> rows, String writer, long gen, long nowMs) {
        return write(SPEC_EVAL_STATE, rows, r -> new Object[] {s(r.alertType()), s(r.domain())},
                r -> new Object[] {i(r.upCount()), l(r.lastCountedMs()), l(r.chainGen()), l(r.chainStartedMs())},
                RecoveryRow::seq, writer, gen, nowMs);
    }

    public int writeLastStatus(List<LastStatusRow> rows, String writer, long gen, long nowMs) {
        return write(SPEC_LAST_STATUS, rows, r -> new Object[] {s(r.alertType()), s(r.itemKey())},
                r -> new Object[] {s(r.domain()), s(r.detail()), l(r.monitorId()), l(r.teamId()), b(r.standalone()),
                        b(r.up()), b(r.networkDown()), s(r.errorClass()), s(r.source()), l(r.observedMs()), l(r.changedMs())},
                LastStatusRow::seq, writer, gen, nowMs);
    }

    public int writeTypeState(List<TypeRow> rows, String writer, long gen, long nowMs) {
        List<TypeRow> safe = rows.stream().filter(r -> !EvalStateKeys.GENERATION_ROW.equals(r.alertType())).toList();
        return write(SPEC_TYPE_STATE, safe, r -> new Object[] {s(r.alertType())},
                r -> new Object[] {b(r.suppressionActive()), l(r.suppressionSinceMs()), l(r.lastScheduledRoundMs()),
                        l(r.roundIntervalMs())},
                TypeRow::seq, writer, gen, nowMs);
    }

    public int writeChains(List<ChainRow> rows, String writer, long gen, long nowMs) {
        return write(SPEC_CONFIRMATIONS, rows, r -> new Object[] {s(r.keyHash())},
                r -> new Object[] {s(r.confirmKey()), l(r.chainId()), s(r.alertType()), s(r.domain()), s(r.detail()),
                        s(r.monitorType()), l(r.monitorId()), i(r.attempt()), i(r.totalAttempts()), l(r.delayMs()),
                        s(r.firstFailureAt()), l(r.nextAttemptMs()), s(r.attemptsJson()), s(r.ctxJson()),
                        b(r.ctxTruncated()), s(r.state()), l(r.createdMs())},
                ChainRow::seq, writer, gen, nowMs);
    }

    /** Budanan kalemlerin son durum satırlarını koşullu siler (daha yeni (gen, seq)'li satıra dokunmaz). */
    public int deleteLastStatus(List<LastStatusDelete> rows, long gen) {
        int n = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Object[]> args = new ArrayList<>();
            for (LastStatusDelete d : rows.subList(from, Math.min(rows.size(), from + CHUNK))) {
                args.add(new Object[] {s(d.alertType()), s(d.itemKey()), l(gen), l(gen), l(d.seq())});
            }
            for (int c : jdbc.batchUpdate(SQL_LAST_STATUS_DELETE, args)) n += Math.max(0, c);
        }
        return n;
    }

    /** Biten zincirlerin satırlarını koşullu siler (başka yazanın / daha yeni kuşağın satırına dokunmaz). */
    public int deleteChains(List<ChainDelete> rows, String writer, long gen) {
        int n = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Object[]> args = new ArrayList<>();
            for (ChainDelete d : rows.subList(from, Math.min(rows.size(), from + CHUNK))) {
                args.add(new Object[] {s(d.keyHash()), l(d.chainId()), s(clip(writer, WRITER_MAX)), l(gen)});
            }
            for (int c : jdbc.batchUpdate(SQL_CHAIN_DELETE, args)) n += Math.max(0, c);
        }
        return n;
    }

    @FunctionalInterface
    interface Params<R> {
        Object[] of(R row);
    }

    @FunctionalInterface
    interface SeqOf<R> {
        long of(R row);
    }

    private <R> int write(Spec spec, List<R> rows, Params<R> keys, Params<R> values, SeqOf<R> seqOf,
                          String writer, long gen, long nowMs) {
        if (rows.isEmpty()) return 0;
        String w = clip(writer, WRITER_MAX);
        String at = iso(nowMs);
        boolean pg = Boolean.TRUE.equals(postgres());
        int total = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<R> chunk = rows.subList(from, Math.min(rows.size(), from + CHUNK));
            if (pg) {
                List<Object[]> args = new ArrayList<>(chunk.size());
                for (R r : chunk) args.add(concat(keys.of(r), values.of(r), common(w, gen, seqOf.of(r), nowMs, at)));
                jdbc.batchUpdate(spec.pgUpsert, args);
                total += chunk.size();
                continue;
            }
            total += writePortable(spec, chunk, keys, values, seqOf, w, gen, nowMs, at);
        }
        return total;
    }

    /** H2 / bilinmeyen tür: koşullu UPDATE → INSERT … ON CONFLICT DO NOTHING → (araya giren ilk satır) UPDATE. */
    private <R> int writePortable(Spec spec, List<R> chunk, Params<R> keys, Params<R> values, SeqOf<R> seqOf,
                                  String w, long gen, long nowMs, String at) {
        List<Object[]> upd = new ArrayList<>(chunk.size());
        for (R r : chunk) upd.add(updateArgs(keys.of(r), values.of(r), w, gen, seqOf.of(r), nowMs, at));
        int[] u = jdbc.batchUpdate(spec.update, upd);
        List<R> missing = new ArrayList<>();
        int applied = 0;
        for (int i = 0; i < chunk.size(); i++) {
            if (i < u.length && u[i] == 0) missing.add(chunk.get(i));
            else applied++;
        }
        if (missing.isEmpty()) return applied;
        List<Object[]> ins = new ArrayList<>(missing.size());
        for (R r : missing) ins.add(concat(keys.of(r), values.of(r), common(w, gen, seqOf.of(r), nowMs, at)));
        int[] c;
        try {
            c = jdbc.batchUpdate(spec.insert, ins);
        } catch (DuplicateKeyException race) {
            c = new int[0];   // ON CONFLICT desteklenmeyen sürücü: tek tek yeniden dene (aşağıda UPDATE)
        }
        for (int i = 0; i < missing.size(); i++) {
            if (i < c.length && c[i] != 0) { applied++; continue; }
            R r = missing.get(i);
            // Satır vardı (daha yeni → 0 satır, atlanır) ya da UPDATE ile INSERT arasında başka yazan ekledi → bir kez daha
            if (jdbc.update(spec.update, updateArgs(keys.of(r), values.of(r), w, gen, seqOf.of(r), nowMs, at)) == 1) applied++;
        }
        return applied;
    }

    private static Object[] updateArgs(Object[] keys, Object[] values, String w, long gen, long seq, long nowMs, String at) {
        Object[] guard = {l(gen), l(gen), l(seq)};
        return concat(values, common(w, gen, seq, nowMs, at), keys, guard);
    }

    private static Object[] common(String w, long gen, long seq, long nowMs, String at) {
        return new Object[] {s(w), l(gen), l(seq), l(nowMs), s(at)};
    }

    private static Object[] concat(Object[]... parts) {
        int n = 0;
        for (Object[] p : parts) n += p.length;
        Object[] out = new Object[n];
        int i = 0;
        for (Object[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    // ── Devralma / terk / budama ────────────────────────────────────────────────────────────────────────────────────

    /** Önceki kuşakların süren zincirleri ABANDONED (devirde zincir düşer — bugünkü davranışın aynası). */
    public int markAbandoned(long myGen, long nowMs) {
        return jdbc.update(SQL_MARK_ABANDONED, nowMs, iso(nowMs), myGen);
    }

    /**
     * Bu pod'un CAS'ının yerini aldığı kuşak: {@code myGen − 1} ({@code '*'} satırı yalnız CAS ile +1 ilerler); ilk kuşakta
     * null. Devralınan sayılar YALNIZ bu kuşağın satırlarıdır (bulgu #3 — daha eski kuşakların bayat satırları değil).
     */
    public Long previousGeneration(long myGen) {
        return myGen > 1 ? Long.valueOf(myGen - 1) : null;
    }

    /** Önceki kuşağın ({@code previousGen}) sayacı > 0 kurtarma satırları. */
    public int countInheritedRecovery(long previousGen) {
        Integer n = jdbc.queryForObject(SQL_INHERITED_RECOVERY, Integer.class, previousGen);
        return n == null ? 0 : n;
    }

    /** Önceki kuşağın TTL içindeki kimlikli DOWN satırları. */
    public int countInheritedSiblings(long previousGen, long observedSinceMs) {
        Integer n = jdbc.queryForObject(SQL_INHERITED_SIBLINGS, Integer.class, previousGen, observedSinceMs);
        return n == null ? 0 : n;
    }

    /** Önceki kuşağın bastırması açık tür satırları. */
    public int countInheritedSuppressions(long previousGen) {
        Integer n = jdbc.queryForObject(SQL_INHERITED_SUPPRESSIONS, Integer.class, previousGen, EvalStateKeys.GENERATION_ROW);
        return n == null ? 0 : n;
    }

    /**
     * Günlük budama (idempotent, kilitsiz): son gözlemi {@code lastStatusBeforeMs}'den eski kalemler;
     * {@code evalStateBeforeMs}'den eski kurtarma satırlarından sayaçsız + zincirsiz olanlar VE {@code myGen}'den eski kuşağın
     * olanları (sayıdan bağımsız); ABANDONED ya da {@code confirmationsBeforeMs}'den eski zincirler; {@code evalStateBeforeMs}'den
     * eski, daha eski kuşağın tür satırları ({@code '*'} asla). @return {son durum, kurtarma, zincir, tür} silinen satır sayıları.
     */
    public int[] prune(long myGen, long lastStatusBeforeMs, long evalStateBeforeMs, long confirmationsBeforeMs) {
        int a = jdbc.update(SQL_PRUNE_LAST_STATUS, lastStatusBeforeMs);
        int b = jdbc.update(SQL_PRUNE_EVAL_STATE, evalStateBeforeMs, myGen);
        int c = jdbc.update(SQL_PRUNE_CONFIRMATIONS, confirmationsBeforeMs);
        int d = jdbc.update(SQL_PRUNE_TYPE_STATE, EvalStateKeys.GENERATION_ROW, myGen, evalStateBeforeMs);
        return new int[] {a, b, c, d};
    }

    // ── Okuyucular (doğrulama ve testler; yalnız verilen kuşağın satırları) ──────────────────────────────────────────

    public List<Stored<RecoveryRow>> readEvalState(long gen) {
        return jdbc.query("SELECT alert_type, domain, recovery_up_count, recovery_last_counted_ms, recovery_chain_gen, "
                + "recovery_chain_started_ms, writer, gen, seq, updated_ms FROM " + EVAL_STATE + " WHERE gen = ?",
                (rs, n) -> new Stored<>(new RecoveryRow(rs.getString(1), rs.getString(2), rs.getInt(3), longOrNull(rs, 4),
                        longOrNull(rs, 5), longOrNull(rs, 6), rs.getLong(9)), rs.getString(7), rs.getLong(8), rs.getLong(10)),
                gen);
    }

    public List<Stored<LastStatusRow>> readLastStatus(long gen) {
        return jdbc.query("SELECT alert_type, item_key, domain, detail, monitor_id, team_id, standalone, up, network_down, "
                + "error_class, source, observed_ms, changed_ms, writer, gen, seq, updated_ms FROM " + LAST_STATUS
                + " WHERE gen = ?",
                (rs, n) -> new Stored<>(new LastStatusRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        longOrNull(rs, 5), longOrNull(rs, 6), boolOrNull(rs, 7), rs.getBoolean(8), rs.getBoolean(9),
                        rs.getString(10), trimOrNull(rs.getString(11)), rs.getLong(12), rs.getLong(13), rs.getLong(16)),
                        rs.getString(14), rs.getLong(15), rs.getLong(17)),
                gen);
    }

    public List<Stored<TypeRow>> readTypeState(long gen) {
        return jdbc.query("SELECT alert_type, suppression_active, suppression_since_ms, last_scheduled_round_ms, "
                + "round_interval_ms, writer, gen, seq, updated_ms FROM " + TYPE_STATE + " WHERE gen = ? AND alert_type <> ?",
                (rs, n) -> new Stored<>(new TypeRow(rs.getString(1), boolOrNull(rs, 2), longOrNull(rs, 3), longOrNull(rs, 4),
                        longOrNull(rs, 5), rs.getLong(8)), rs.getString(6), rs.getLong(7), rs.getLong(9)),
                gen, EvalStateKeys.GENERATION_ROW);
    }

    public List<Stored<ChainRow>> readConfirmations(long gen) {
        return jdbc.query("SELECT key_hash, confirm_key, chain_id, alert_type, domain, detail, monitor_type, monitor_id, "
                + "attempt, total_attempts, delay_ms, first_failure_at, next_attempt_ms, attempts_json, ctx_json, "
                + "ctx_truncated, state, created_ms, writer, gen, seq, updated_ms FROM " + CONFIRMATIONS + " WHERE gen = ?",
                (rs, n) -> new Stored<>(new ChainRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), longOrNull(rs, 8), rs.getInt(9), rs.getInt(10),
                        rs.getLong(11), rs.getString(12), longOrNull(rs, 13), rs.getString(14), rs.getString(15),
                        rs.getBoolean(16), rs.getString(17), rs.getLong(18), rs.getLong(21)),
                        rs.getString(19), rs.getLong(20), rs.getLong(22)),
                gen);
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Metni sütuna sığdırır (null kalır). */
    public static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static SqlParameterValue s(String v) { return new SqlParameterValue(Types.VARCHAR, v); }
    private static SqlParameterValue l(Long v) { return new SqlParameterValue(Types.BIGINT, v); }
    private static SqlParameterValue l(long v) { return new SqlParameterValue(Types.BIGINT, v); }
    private static SqlParameterValue i(int v) { return new SqlParameterValue(Types.INTEGER, v); }
    private static SqlParameterValue b(Boolean v) { return new SqlParameterValue(Types.BOOLEAN, v); }

    private static Long longOrNull(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean boolOrNull(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static String trimOrNull(String s) {
        return s == null ? null : s.trim();
    }
}
