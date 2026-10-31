package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bir izlemenin tek çalıştırmasını uçtan uca yürütür (sözleşme §2–§3): bağlantıyı bulur ve parolayı çözer, sorguyu YENİDEN
 * denetler ({@link DbQuerySqlGuard}), eşzamanlılık izinlerini alır (pod / bağlantı), çalıştırır ({@link DbQueryRunner}),
 * kuralları değerlendirir ({@link DbQueryEvaluator}; önceki zamanlanmış çalıştırma {@code DbQueryCheckRepository
 * .findPreviousScheduledOk}), yavaş sorguyu işaretler, mail / alarm satırlarını seçer + maskeler, bağlantı sağlığını yazar.
 * Kontrol satırını YAZMAZ ve alarm DEĞERLENDİRMEZ (zamanlayıcının işi). Asla fırlatmaz.
 * İSKELET — gövde motor paketinde yazılır; imzalar sözleşmedir.
 */
@Service
public class DbQueryCheckerService {

    public static final String STATUS_OK = "OK", STATUS_BREACH = "BREACH", STATUS_FAIL = "FAIL", STATUS_SKIPPED = "SKIPPED";

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

    /** Kayıtlı izlemenin çalıştırması; bağlantı sağlığını yazar. */
    public Result check(DbQueryMonitor m, boolean manual) {
        throw new UnsupportedOperationException("DbQueryCheckerService.check");
    }

    /**
     * Kaydetmeden deneme (form "Sorguyu dene"): taslağın bağlantısı + sorgusu + kuralları; değişim kuralı için
     * {@code previousForMonitorId} (düzenlenen izleme) verilirse onun önceki çalıştırması okunur. Bağlantı sağlığına YAZMAZ.
     */
    public Result test(DbQueryMonitor draft, Long previousForMonitorId) {
        throw new UnsupportedOperationException("DbQueryCheckerService.test");
    }

    /**
     * Bağlantı denemesi. {@code passwordOverride} null değilse kayıtlı parola yerine o kullanılır (formda yeni parola).
     * {@code profile.getId() != null} ve override yoksa sonuç sağlık kolonlarına yazılır.
     */
    public DbQueryRunner.ConnectionTest testConnection(DbConnectionProfile profile, String passwordOverride) {
        throw new UnsupportedOperationException("DbQueryCheckerService.testConnection");
    }

    public PlanVerdict planCheck(DbConnectionProfile profile, String sql) {
        throw new UnsupportedOperationException("DbQueryCheckerService.planCheck");
    }

    /** Profili çalıştırıcı tanımına çevirir (parola çözülür; override verilirse o). */
    public DbConnectionSpec specOf(DbConnectionProfile profile, String passwordOverride) {
        throw new UnsupportedOperationException("DbQueryCheckerService.specOf");
    }
}
