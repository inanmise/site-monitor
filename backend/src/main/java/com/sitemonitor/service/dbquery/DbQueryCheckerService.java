package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.util.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Bir izlemenin tek çalıştırmasını uçtan uca yürütür (sözleşme §2–§3): bağlantıyı bulur ve parolayı çözer, sorguyu YENİDEN
 * denetler ({@link DbQuerySqlGuard}), eşzamanlılık izinlerini alır (pod / bağlantı), çalıştırır ({@link DbQueryRunner}),
 * kuralları değerlendirir ({@link DbQueryEvaluator}; önceki zamanlanmış çalıştırma {@code DbQueryCheckRepository
 * .findPreviousScheduledOk}), yavaş sorguyu işaretler, mail / alarm satırlarını seçer + maskeler, bağlantı sağlığını yazar.
 * Kontrol satırını YAZMAZ ve alarm DEĞERLENDİRMEZ (zamanlayıcının işi). Asla fırlatmaz.
 *
 * <p>İzinler canlı ayardan okunur ({@code site.monitor.dbquery.max-concurrency}, {@code …per-connection-concurrency}):
 * izin {@value #SCHEDULED_WAIT_MS} ms (elle / deneme {@value #INTERACTIVE_WAIT_MS} ms) içinde alınamazsa çalıştırma
 * ATLANIR ({@code SKIPPED} / {@code DB_BUSY} — satır yazılmaz, alarm yok). Ayrıştırma zaman / izin sınırına takılan güvenlik
 * denetimi de ATLAMA sayılır (yük altında sahte FAIL alarmı açılmasın); gerçek red ise FAIL {@code DB_GUARD_REJECTED}'dir ve
 * sorgu ÇALIŞMAZ.
 */
@Service
public class DbQueryCheckerService {

    private static final Logger log = LoggerFactory.getLogger(DbQueryCheckerService.class);

    public static final String STATUS_OK = "OK", STATUS_BREACH = "BREACH", STATUS_FAIL = "FAIL", STATUS_SKIPPED = "SKIPPED";

    public static final String DB_GUARD_REJECTED = "DB_GUARD_REJECTED", DB_PROFILE_MISSING = "DB_PROFILE_MISSING",
            DB_PROFILE_OTHER_TEAM = "DB_PROFILE_OTHER_TEAM", DB_SECRET_UNREADABLE = "DB_SECRET_UNREADABLE",
            DB_BUSY = "DB_BUSY";

    public static final String KEY_MAX_CONCURRENCY = "site.monitor.dbquery.max-concurrency",
            KEY_PER_CONNECTION = "site.monitor.dbquery.per-connection-concurrency",
            KEY_MAX_TIMEOUT = "site.monitor.dbquery.max-timeout-seconds",
            KEY_MAX_ROWS_CAP = "site.monitor.dbquery.max-rows-cap",
            KEY_MAIL_MAX_ROWS = "site.monitor.dbquery.mail-max-rows",
            KEY_MAIL_MAX_COLUMNS = "site.monitor.dbquery.mail-max-columns",
            KEY_PLAN_MODE = "site.monitor.dbquery.plan-check-mode",
            KEY_MAX_PLAN_COST = "site.monitor.dbquery.max-plan-cost",
            KEY_MAX_PLAN_ROWS = "site.monitor.dbquery.max-plan-rows";

    static final long SCHEDULED_WAIT_MS = 30_000, INTERACTIVE_WAIT_MS = 10_000;
    static final int MAIL_CELL_CHARS = 200;
    public static final String MASK = "••••";
    static final int DEFAULT_TIMEOUT = 30, DEFAULT_MAX_ROWS = 1_000, MIN_TIMEOUT = 5;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param status         OK | BREACH | FAIL | SKIPPED (SKIPPED = pod / bağlantı izni alınamadı; satır yazılmaz, alarm yok)
     * @param level          NONE | WARNING | CRITICAL (BREACH seviyesi)
     * @param columnInfo     sorgunun TÜM kolonları (≤ 100) — ad + tür (satır verisi değil)
     * @param mailColumns    mail / alarm satırlarına seçilen kolonlar (mail_columns ya da ilk N)
     * @param mailRows       mail / alarm satırları: ≤ mail-max-rows × mailColumns, hücre ≤ 200 karakter, maskeli kolonlar
     *                       "••••"; YALNIZ mail_include_rows açıkken dolu — UI'ya (deneme ucu dahil) ASLA dönmez
     * @param maskedColumns  maskelenen kolon adları (mailColumns içinden)
     * @param rowsTruncated  mailRows satır / kolon sınırında kesildi
     * @param failureReason  FAIL kodu (DB_UNREACHABLE, DB_DNS, DB_TIMEOUT, DB_AUTH, DB_SQL_ERROR, DB_DRIVER, DB_BLOCKED,
     *                       DB_GUARD_REJECTED, DB_PROFILE_MISSING, DB_PROFILE_OTHER_TEAM, DB_SECRET_UNREADABLE) ya da
     *                       SKIPPED kodu (DB_BUSY)
     * @param error          maskeli hata metni (≤ 500)
     * @param checkedAt      UTC ISO (yyyy-MM-dd'T'HH:mm:ss)
     */
    public record Result(String status, String level, long durationMs, Integer rowCount, boolean rowCountCapped,
                         List<DbQueryRunner.Column> columnInfo, List<String> mailColumns, List<List<String>> mailRows,
                         List<String> maskedColumns, boolean rowsTruncated, List<DbQueryEvaluator.RuleResult> ruleResults,
                         Double metricValue, boolean slow, String error, String failureReason, String checkedAt,
                         String sqlHash, Long profileId, String serverVersion) {}

    /** @param verdict OK | WARN | BLOCK | UNKNOWN (ayar {@code site.monitor.dbquery.plan-check-mode} + eşikler). */
    public record PlanVerdict(boolean supported, String verdict, Double cost, Double rows, String message) {}

    /** Kayıtlı parola çözülemedi (anahtar değişti / bozuk değer). */
    public static final class SecretUnreadableException extends IllegalStateException {
        public SecretUnreadableException() { super("bağlantı parolası çözülemedi"); }
    }

    private final DbConnectionProfileRepository profiles;
    private final DbQueryCheckRepository checks;
    private final SecretCipher cipher;
    private final DbQueryRunner runner;
    private final AppSettingsService settings;

    private final Permits podPermits = new Permits();
    /** İzin bekleme süreleri (ms) — testler kısaltır. */
    private volatile long scheduledWaitMs = SCHEDULED_WAIT_MS, interactiveWaitMs = INTERACTIVE_WAIT_MS;
    private final Map<Long, Permits> connectionPermits = new ConcurrentHashMap<>();

    public DbQueryCheckerService(DbConnectionProfileRepository profiles, DbQueryCheckRepository checks,
                                 SecretCipher cipher, DbQueryRunner runner, AppSettingsService settings) {
        this.profiles = profiles;
        this.checks = checks;
        this.cipher = cipher;
        this.runner = runner;
        this.settings = settings;
    }

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** Kayıtlı izlemenin çalıştırması; bağlantı sağlığını yazar. */
    public Result check(DbQueryMonitor m, boolean manual) {
        try {
            return execute(m, m == null ? null : m.getId(), true, manual ? interactiveWaitMs : scheduledWaitMs, true);
        } catch (RuntimeException e) {
            log.warn("Veritabanı sorgu izlemesi çalıştırılamadı (#{}): {}", m == null ? null : m.getId(),
                    e.getClass().getSimpleName());
            return fail(m, DbQueryErrors.DB_DRIVER, Msg.t("Sorgu çalıştırılamadı (iç hata) · Beklenmeyen bir hata oluştu · "
                    + "Bir sonraki çalıştırmayı bekleyin; sürerse yöneticinize bildirin.", "The query could not run (internal "
                    + "error) · An unexpected error occurred · Wait for the next run; if it persists, tell your administrator."),
                    null, null);
        }
    }

    /**
     * Kaydetmeden deneme (form "Sorguyu dene"): taslağın bağlantısı + sorgusu + kuralları; değişim kuralı için
     * {@code previousForMonitorId} (düzenlenen izleme) verilirse onun önceki çalıştırması okunur. Bağlantı sağlığına YAZMAZ.
     * Mail satırları hiç seçilmez (deneme ucu satır verisi döndürmez).
     */
    public Result test(DbQueryMonitor draft, Long previousForMonitorId) {
        try {
            return execute(draft, previousForMonitorId, false, interactiveWaitMs, false);
        } catch (RuntimeException e) {
            log.warn("Veritabanı sorgusu denenemedi: {}", e.getClass().getSimpleName());
            return fail(draft, DbQueryErrors.DB_DRIVER, Msg.t("Sorgu denenemedi (iç hata) · Beklenmeyen bir hata oluştu · "
                    + "Biraz sonra yeniden deneyin.", "The query could not be tried (internal error) · An unexpected error "
                    + "occurred · Try again shortly."), null, null);
        }
    }

    /**
     * Bağlantı denemesi. {@code passwordOverride} null değilse kayıtlı parola yerine o kullanılır (formda yeni parola).
     * {@code profile.getId() != null} ve override yoksa sonuç sağlık kolonlarına yazılır.
     */
    public DbQueryRunner.ConnectionTest testConnection(DbConnectionProfile profile, String passwordOverride) {
        if (profile == null) return new DbQueryRunner.ConnectionTest(false, 0, null, msgProfileMissing(), DB_PROFILE_MISSING);
        DbConnectionSpec spec;
        try {
            spec = specOf(profile, passwordOverride);
        } catch (SecretUnreadableException e) {
            return new DbQueryRunner.ConnectionTest(false, 0, null, msgSecretUnreadable(), DB_SECRET_UNREADABLE);
        } catch (IllegalArgumentException e) {
            return new DbQueryRunner.ConnectionTest(false, 0, null, msgUnknownType(), DbQueryErrors.DB_DRIVER);
        }
        Lease lease = acquire(profile.getId(), interactiveWaitMs);
        if (lease == null) return new DbQueryRunner.ConnectionTest(false, 0, null, msgBusy(), DB_BUSY);
        DbQueryRunner.ConnectionTest t;
        try {
            t = runner.test(spec);
        } finally {
            lease.release();
        }
        if (profile.getId() != null && passwordOverride == null) {
            try {
                profiles.recordTest(profile.getId(), nowIso(), t.ok(), (int) Math.min(Integer.MAX_VALUE, t.durationMs()),
                        t.error(), t.serverVersion());
            } catch (RuntimeException e) {
                log.warn("Bağlantı denemesi sonucu yazılamadı (#{}): {}", profile.getId(), e.getClass().getSimpleName());
            }
        }
        return t;
    }

    public PlanVerdict planCheck(DbConnectionProfile profile, String sql) {
        if (profile == null) return new PlanVerdict(false, "UNKNOWN", null, null, msgProfileMissing());
        DbType type = DbType.parse(profile.getDbType());
        if (type == null) return new PlanVerdict(false, "UNKNOWN", null, null, msgUnknownType());
        if (type == DbType.ORACLE)
            return new PlanVerdict(false, "UNKNOWN", null, null, Msg.t(
                    "Oracle'da maliyet ön kontrolü yapılamıyor · Plan okumak için veritabanına plan tablosu yazmak gerekir · "
                            + "Sorguyu \"Sorguyu dene\" ile süresine bakarak sınayın.",
                    "The cost check is not available for Oracle · Reading a plan requires writing to a plan table · "
                            + "Try the query with \"Try query\" and look at its duration."));
        String mode = planMode();
        if ("OFF".equals(mode))
            return new PlanVerdict(true, "OK", null, null, Msg.t("Maliyet ön kontrolü kapalı.", "The cost check is turned off."));
        DbQuerySqlGuard.Result guard = DbQuerySqlGuard.check(sql, type);
        if (!guard.ok())
            return new PlanVerdict(true, "UNKNOWN", null, null, Msg.t(
                    "Sorgu güvenlik denetiminden geçmedi; maliyeti ölçülmedi · Önce güvenlik uyarılarını giderin.",
                    "The query did not pass the safety check, so its cost was not measured · Fix the safety issues first."));
        DbConnectionSpec spec;
        try {
            spec = specOf(profile, null);
        } catch (SecretUnreadableException e) {
            return new PlanVerdict(true, "UNKNOWN", null, null, msgSecretUnreadable());
        }
        Lease lease = acquire(profile.getId(), interactiveWaitMs);
        if (lease == null) return new PlanVerdict(true, "UNKNOWN", null, null, msgBusy());
        DbQueryRunner.PlanResult p;
        try {
            p = runner.plan(spec, sql, Math.min(maxTimeout(), DEFAULT_TIMEOUT));
        } finally {
            lease.release();
        }
        if (!p.supported()) return new PlanVerdict(false, "UNKNOWN", null, null, null);
        if (!p.ok() || p.cost() == null)
            return new PlanVerdict(true, "UNKNOWN", p.cost(), p.rows(), Msg.t(
                    "Sorgu planı okunamadı" + (p.error() == null ? "" : " (" + p.error() + ")") + " · Maliyet bilinmiyor; "
                            + "kayıt engellenmez · Sorguyu \"Sorguyu dene\" ile süresine bakarak sınayın.",
                    "The query plan could not be read" + (p.error() == null ? "" : " (" + p.error() + ")") + " · The cost is "
                            + "unknown; saving is not blocked · Try the query with \"Try query\" and look at its duration."));
        double maxCost = settings.getDouble(KEY_MAX_PLAN_COST, 1_000_000d);
        double maxRows = settings.getDouble(KEY_MAX_PLAN_ROWS, 10_000_000d);
        boolean over = p.cost() > maxCost || (p.rows() != null && p.rows() > maxRows);
        if (!over) return new PlanVerdict(true, "OK", p.cost(), p.rows(), Msg.t(
                "Tahmini maliyet sınırların içinde.", "The estimated cost is within the limits."));
        String verdict = "BLOCK".equals(mode) ? "BLOCK" : "WARN";
        String c = DbQueryEvaluator.fmt(p.cost(), Msg.isEn()), mc = DbQueryEvaluator.fmt(maxCost, Msg.isEn());
        String r = p.rows() == null ? "—" : DbQueryEvaluator.fmt(p.rows(), Msg.isEn());
        String mr = DbQueryEvaluator.fmt(maxRows, Msg.isEn());
        return new PlanVerdict(true, verdict, p.cost(), p.rows(), Msg.t(
                "Sorgunun tahmini maliyeti yüksek (maliyet " + c + ", sınır " + mc + "; satır " + r + ", sınır " + mr + ") · "
                        + "Bu sorgu her çalıştığında veritabanını yorabilir · Süzgeç ekleyin, dizinli kolonlara göre "
                        + "daraltın ya da toplanmış bir görünümü okuyun.",
                "The query's estimated cost is high (cost " + c + ", limit " + mc + "; rows " + r + ", limit " + mr + ") · "
                        + "Each run may load the database · Add filters, narrow on indexed columns, or read an "
                        + "aggregated view."));
    }

    /** Profili çalıştırıcı tanımına çevirir (parola çözülür; override verilirse o). */
    public DbConnectionSpec specOf(DbConnectionProfile profile, String passwordOverride) {
        DbType type = DbType.parse(profile.getDbType());
        if (type == null) throw new IllegalArgumentException("bilinmeyen veritabanı türü");
        String password;
        if (passwordOverride != null) {
            password = passwordOverride;
        } else {
            String enc = profile.getPasswordEnc();
            if (enc == null || enc.isBlank()) {
                password = null;
            } else {
                password = cipher.decrypt(enc);
                if (password == null) throw new SecretUnreadableException();
            }
        }
        int port = profile.getPort() == null ? type.defaultPort() : profile.getPort();
        int connectMs = profile.getConnectTimeoutMs() == null ? 10_000 : profile.getConnectTimeoutMs();
        return new DbConnectionSpec(type, profile.getHost(), port, profile.getDatabaseName(), profile.getOracleMode(),
                profile.getMssqlInstance(), profile.getUsername(), password, profile.getSslMode(),
                Boolean.TRUE.equals(profile.getMssqlEncrypt()), Boolean.TRUE.equals(profile.getMssqlTrustServerCertificate()),
                Math.max(1_000, Math.min(30_000, connectMs)));
    }

    // ── Akış ─────────────────────────────────────────────────────────────────

    private Result execute(DbQueryMonitor m, Long previousForMonitorId, boolean recordHealth, long waitMs,
                           boolean selectMailRows) {
        if (m == null) return fail(null, DB_PROFILE_MISSING, msgProfileMissing(), null, null);
        DbConnectionProfile p = m.getProfileId() == null ? null : profiles.findById(m.getProfileId()).orElse(null);
        if (p == null) return fail(m, DB_PROFILE_MISSING, msgProfileMissing(), null, null);
        if (!Objects.equals(p.getTeamId(), m.getTeamId()))
            return fail(m, DB_PROFILE_OTHER_TEAM, Msg.t(
                    "Bağlantı bu izlemenin takımına ait değil · Bir izleme yalnız kendi takımının bağlantısını kullanabilir · "
                            + "İzlemede kendi takımınızın bir bağlantısını seçin.",
                    "The connection does not belong to this monitor's team · A monitor may only use its own team's "
                            + "connection · Choose a connection of your team in the monitor."), p.getId(), null);
        DbType type = DbType.parse(p.getDbType());
        if (type == null) return fail(m, DbQueryErrors.DB_DRIVER, msgUnknownType(), p.getId(), null);

        DbQuerySqlGuard.Result guard = DbQuerySqlGuard.check(m.getSqlText(), type);
        if (DbQuerySqlGuard.isTimeout(guard)) return skipped(m, p.getId(), msgBusy());
        if (!guard.ok()) {
            List<String> msgs = new ArrayList<>();
            for (DbQuerySqlGuard.Violation v : guard.violations()) {
                if (msgs.size() >= 3) break;
                msgs.add(v.message());
            }
            return fail(m, DB_GUARD_REJECTED, clip(String.join(" | ", msgs), DbQueryErrors.MAX_ERROR_CHARS), p.getId(), null);
        }
        DbConnectionSpec spec;
        try {
            spec = specOf(p, null);
        } catch (SecretUnreadableException e) {
            return fail(m, DB_SECRET_UNREADABLE, msgSecretUnreadable(), p.getId(), null);
        }

        int timeout = clampInt(m.getQueryTimeoutSeconds() == null ? DEFAULT_TIMEOUT : m.getQueryTimeoutSeconds(),
                MIN_TIMEOUT, Math.max(MIN_TIMEOUT, maxTimeout()));
        int maxRows = clampInt(m.getMaxRows() == null ? DEFAULT_MAX_ROWS : m.getMaxRows(), 1,
                Math.max(1, settings.getInt(KEY_MAX_ROWS_CAP, 5_000)));
        List<DbQueryRules.Rule> rules = DbQueryRules.fromJson(m.getRulesJson());
        Set<String> masked = lowerSet(stringList(m.getMaskedColumnsJson()));
        Set<String> discard = new LinkedHashSet<>(masked);
        for (DbQueryRules.Rule r : rules) if (r.column() != null) discard.remove(r.column().trim().toLowerCase(Locale.ROOT));

        Lease lease = acquire(p.getId(), waitMs);
        if (lease == null) return skipped(m, p.getId(), msgBusy());
        DbQueryRunner.QueryResult qr;
        try {
            qr = runner.run(spec, m.getSqlText(), timeout, maxRows, discard);
        } finally {
            lease.release();
        }
        String at = nowIso();
        if (recordHealth) recordUse(p.getId(), at, qr);
        if (!qr.ok())
            return new Result(STATUS_FAIL, DbQueryEvaluator.LEVEL_NONE, qr.durationMs(), null, false, List.of(), List.of(),
                    List.of(), List.of(), false, List.of(), null, false, qr.error(), qr.failureReason(), at,
                    sqlHash(m.getSqlText()), p.getId(), qr.serverVersion());

        Map<String, Double> previous = previousMeasures(previousForMonitorId);
        DbQueryEvaluator.Outcome outcome = DbQueryEvaluator.evaluate(rules, m.getRuleLogic(), qr, previous, "tr");
        boolean slow = m.getSlowQueryMs() != null && m.getSlowQueryMs() > 0 && qr.durationMs() >= m.getSlowQueryMs();

        MailRows mail = selectMailRows && !Boolean.FALSE.equals(m.getMailIncludeRows())
                ? mailRows(m, qr, masked) : mailColumnsOnly(m, qr, masked);
        return new Result(outcome.status(), outcome.level(), qr.durationMs(), qr.rowCount(), qr.rowCountCapped(),
                qr.columns(), mail.columns, mail.rows, mail.masked, mail.truncated, outcome.results(),
                outcome.metricValue(), slow, null, null, at, sqlHash(m.getSqlText()), p.getId(), qr.serverVersion());
    }

    private void recordUse(Long profileId, String at, DbQueryRunner.QueryResult qr) {
        if (profileId == null) return;
        int ms = (int) Math.min(Integer.MAX_VALUE, qr.durationMs());
        try {
            if (qr.ok()) profiles.recordUseOk(profileId, at, ms);
            else profiles.recordUseError(profileId, at, ms, qr.error());
        } catch (RuntimeException e) {
            log.warn("Bağlantı kullanım sağlığı yazılamadı (#{}): {}", profileId, e.getClass().getSimpleName());
        }
    }

    /** Önceki ZAMANLANMIŞ ve sorgusu çalışmış satırın kural ölçüleri (değişim kuralı); okunamazsa boş (ilk çalıştırma). */
    private Map<String, Double> previousMeasures(Long monitorId) {
        if (monitorId == null) return Map.of();
        try {
            return checks.findPreviousScheduledOk(monitorId)
                    .map(DbQueryCheck::getRuleResultsJson)
                    .map(DbQueryEvaluator::measuresFromJson)
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Önceki çalıştırma okunamadı (#{}): {}", monitorId, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    // ── Mail satırları ───────────────────────────────────────────────────────

    private record MailRows(List<String> columns, List<List<String>> rows, List<String> masked, boolean truncated) {}

    private MailRows mailColumnsOnly(DbQueryMonitor m, DbQueryRunner.QueryResult qr, Set<String> masked) {
        int[] idx = selectColumns(m, qr);
        List<String> names = new ArrayList<>(), maskedOut = new ArrayList<>();
        for (int i : idx) {
            String n = qr.columns().get(i).name();
            names.add(n);
            if (masked.contains(n.toLowerCase(Locale.ROOT))) maskedOut.add(n);
        }
        return new MailRows(List.copyOf(names), List.of(), List.copyOf(maskedOut), false);
    }

    private MailRows mailRows(DbQueryMonitor m, DbQueryRunner.QueryResult qr, Set<String> masked) {
        int[] idx = selectColumns(m, qr);
        int maxRows = Math.max(0, settings.getInt(KEY_MAIL_MAX_ROWS, 50));
        List<String> names = new ArrayList<>(), maskedOut = new ArrayList<>();
        boolean[] mask = new boolean[idx.length];
        for (int k = 0; k < idx.length; k++) {
            String n = qr.columns().get(idx[k]).name();
            names.add(n);
            mask[k] = masked.contains(n.toLowerCase(Locale.ROOT));
            if (mask[k]) maskedOut.add(n);
        }
        List<List<String>> rows = new ArrayList<>();
        List<List<Object>> src = qr.rows() == null ? List.of() : qr.rows();
        boolean truncated = false;
        for (List<Object> row : src) {
            if (rows.size() >= maxRows) { truncated = true; break; }
            List<String> out = new ArrayList<>(idx.length);
            for (int k = 0; k < idx.length; k++) {
                if (mask[k]) { out.add(MASK); continue; }
                Object v = idx[k] < row.size() ? row.get(idx[k]) : null;
                String s = DbQueryRunner.cellToText(v);
                if (s != null && s.length() > MAIL_CELL_CHARS) { s = s.substring(0, MAIL_CELL_CHARS - 1) + "…"; truncated = true; }
                out.add(s);
            }
            rows.add(java.util.Collections.unmodifiableList(out));
        }
        if (qr.rowCount() > rows.size() || qr.rowCountCapped() || qr.valuesTruncated()
                || idx.length < qr.columns().size()) truncated = true;
        return new MailRows(List.copyOf(names), List.copyOf(rows), List.copyOf(maskedOut), truncated);
    }

    /**
     * Mail kolonları: {@code mail_columns} doluysa sonuçta bulunanlar (o sırayla, tekil); hiçbiri bulunmazsa ya da boşsa
     * ilk {@code mail-max-columns} kolon.
     */
    private int[] selectColumns(DbQueryMonitor m, DbQueryRunner.QueryResult qr) {
        int max = Math.max(1, settings.getInt(KEY_MAIL_MAX_COLUMNS, 20));
        List<DbQueryRunner.Column> cols = qr.columns() == null ? List.of() : qr.columns();
        List<Integer> picked = new ArrayList<>();
        for (String want : stringList(m.getMailColumnsJson())) {
            if (picked.size() >= max) break;
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).name().equalsIgnoreCase(want.trim()) && !picked.contains(i)) { picked.add(i); break; }
            }
        }
        if (picked.isEmpty()) for (int i = 0; i < cols.size() && picked.size() < max; i++) picked.add(i);
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) out[i] = picked.get(i);
        return out;
    }

    // ── Eşzamanlılık izinleri ────────────────────────────────────────────────

    /** Canlı sınırlı izin sayacı: sınır her alışta ayardan okunur (ayar değişince hemen geçerli). */
    static final class Permits {
        private int inUse;

        synchronized boolean acquire(IntSupplier limit, long waitMs) {
            long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
            while (inUse >= Math.max(1, limit.getAsInt())) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    wait(Math.min(left, 1_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            inUse++;
            return true;
        }

        synchronized void release() {
            if (inUse > 0) inUse--;
            notifyAll();
        }

        synchronized int inUse() { return inUse; }
    }

    /** Pod + bağlantı izni; ikisi de alınamazsa hiçbiri tutulmaz. */
    private final class Lease {
        private final Permits connection;
        private boolean released;

        Lease(Permits connection) { this.connection = connection; }

        void release() {
            if (released) return;
            released = true;
            if (connection != null) connection.release();
            podPermits.release();
        }
    }

    private Lease acquire(Long profileId, long waitMs) {
        long start = System.currentTimeMillis();
        if (!podPermits.acquire(() -> settings.getInt(KEY_MAX_CONCURRENCY, 4), waitMs)) return null;
        Permits conn = null;
        if (profileId != null) {
            conn = connectionPermits.computeIfAbsent(profileId, k -> new Permits());
            long left = waitMs - (System.currentTimeMillis() - start);
            if (!conn.acquire(() -> settings.getInt(KEY_PER_CONNECTION, 2), Math.max(0, left))) {
                podPermits.release();
                return null;
            }
        }
        return new Lease(conn);
    }

    /** Testler: izin bekleme sürelerini kısaltır. */
    void setWaitsForTests(long scheduledMs, long interactiveMs) {
        this.scheduledWaitMs = scheduledMs;
        this.interactiveWaitMs = interactiveMs;
    }

    /** Testler / gözlem: şu an tutulan pod izni. */
    int podInUse() { return podPermits.inUse(); }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private Result fail(DbQueryMonitor m, String code, String error, Long profileId, String version) {
        return new Result(STATUS_FAIL, DbQueryEvaluator.LEVEL_NONE, 0, null, false, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), null, false, clip(error, DbQueryErrors.MAX_ERROR_CHARS), code, nowIso(),
                m == null ? null : sqlHash(m.getSqlText()), profileId, version);
    }

    private Result skipped(DbQueryMonitor m, Long profileId, String error) {
        return new Result(STATUS_SKIPPED, DbQueryEvaluator.LEVEL_NONE, 0, null, false, List.of(), List.of(), List.of(),
                List.of(), false, List.of(), null, false, error, DB_BUSY, nowIso(),
                m == null ? null : sqlHash(m.getSqlText()), profileId, null);
    }

    private int maxTimeout() {
        return settings.getInt(KEY_MAX_TIMEOUT, 60);
    }

    private String planMode() {
        String v = settings.getString(KEY_PLAN_MODE, "WARN");
        String u = v == null ? "WARN" : v.trim().toUpperCase(Locale.ROOT);
        return u.equals("OFF") || u.equals("BLOCK") ? u : "WARN";
    }

    /** Sorgunun SHA-256 öneki (16 hex) — değişiklik izi. */
    public static String sqlHash(String sql) {
        if (sql == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nowIso() {
        return ISO.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            List<String> out = new ArrayList<>();
            if (list != null) for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Set<String> lowerSet(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) out.add(n.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String msgProfileMissing() {
        return Msg.t("Bağlantı bulunamadı · İzlemenin bağlantısı silinmiş ya da seçilmemiş · İzlemede bir bağlantı seçin.",
                "The connection was not found · The monitor's connection was deleted or not chosen · Choose a connection "
                        + "in the monitor.");
    }

    private static String msgSecretUnreadable() {
        return Msg.t("Bağlantı parolası çözülemedi · Şifreleme anahtarı değişmiş ya da kayıt bozulmuş olabilir · Bağlantıda "
                        + "parolayı yeniden girip kaydedin.",
                "The connection password could not be decrypted · The encryption key may have changed or the record is "
                        + "damaged · Enter the password again in the connection and save it.");
    }

    private static String msgUnknownType() {
        return Msg.t("Bağlantının veritabanı türü tanınmıyor · Desteklenen türler: Oracle, MSSQL, PostgreSQL · Bağlantıyı "
                        + "düzeltin.",
                "The connection's database type is not recognised · Supported types: Oracle, MSSQL, PostgreSQL · Fix the "
                        + "connection.");
    }

    private static String msgBusy() {
        return Msg.t("Şu an çok sayıda veritabanı sorgusu çalışıyor; bu çalıştırma atlandı · Sunucu ya da bağlantı için "
                        + "eşzamanlı sorgu sınırı dolu · Bir sonraki çalıştırma kendiliğinden denenecek; elle denediyseniz "
                        + "birkaç saniye sonra yeniden deneyin.",
                "Too many database queries are running right now; this run was skipped · The concurrency limit for the "
                        + "server or the connection is full · The next run will try again by itself; if you ran it by hand, "
                        + "try again in a few seconds.");
    }
}
