package com.sitemonitor.service.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sistem Sağlığı'nın KÜME durumu (2026-10-23, çok pod R2b, MP-34 / N4) — iki küçük tablo, ham SQL (entity yok):
 * <ul>
 *   <li>{@value #SCAN_TABLE}: sertifika taramasının tür başına ({@code hourly} saatlik, {@code full} tam — elle / açılış,
 *       {@code stale} bayat) SON koşusu: hangi pod, ne zaman, ne kadar sürdü, kaç alan, kaç uyarı / hata, son hata. Eskiden
 *       bu bilgiler taramayı koşturan pod'un belleğindeydi; Sistem Sağlığı'nı başka pod yanıtlayınca "tarama 2 saattir yok"
 *       kırmızı bandı çıkıyor, "Şimdi çalıştır" yoklaması diğer pod'un ilk yanıtında bitiyordu.</li>
 *   <li>{@value #OBS_TABLE}: pod belleğinde tutulan bir kaynağın kümedeki EN YENİ gözlemi (bugün yalnız RDAP veri kaynağı,
 *       {@code rdap-source}): takipçi pod RDAP sorgusu koşturmadığı için "boşta" diyor, RDAP kesintisini gizliyordu.</li>
 * </ul>
 * <b>Yazma hiçbir koşulda fırlatmaz</b> (WARN en çok saatte bir) — tarama / heartbeat akışı bozulmaz. <b>Okuma fırlatır</b>:
 * çağıran tablo yokken / okunamazken bu pod'un belleğine düşer ve yanıtı {@code source: "local"} diye işaretler (bilinmeyen
 * asla küme gerçeği gibi gösterilmez). Yazmalar koşulludur: daha eski bir sonuç daha yeni bir satırın üstüne yazılmaz.
 * Tablolar {@link ClusterHealthSchemaPatch} ile kurulur. Her erişim ÇAĞIRANIN işleminin DIŞINDA koşar ({@code NOT_SUPPORTED}):
 * {@code SchedulerService.getSystemHealth} salt okunur bir işlemdir; PostgreSQL'de eksik tablo okuması (açılışın ilk saniyeleri)
 * o işlemi bozup ({@code 25P02}) ekranın kalan sorgularını da düşürürdü. Bean yoksa (elle kurulum / test) çağıranlar aynı sınıfı kendi
 * {@code JdbcTemplate}'leriyle kurar.
 */
@Slf4j
@Component
public class ClusterHealthStore {

    public static final String SCAN_TABLE = "scan_runs";
    public static final String OBS_TABLE = "cluster_observations";

    /** Tür adları — kalıcı (değiştirilmez). */
    public static final String KIND_HOURLY = "hourly";
    public static final String KIND_FULL = "full";
    public static final String KIND_STALE = "stale";

    /** Gözlem adları — kalıcı. */
    public static final String OBS_RDAP = "rdap-source";
    /** Kalıcı değerlendirme durumunun son doğrulama turu — tarama lideri yayımlar (çok pod R3-1, plan 3a; R3-1 P0). */
    public static final String OBS_EVAL_STATE_VERIFY = "eval-state-verify";
    /** Kurum geneli ölçü ailelerini son dışa aktaran pod — aktarıcı yerleşimi (çok pod R3-1, plan 3e; R3-1 P0). */
    public static final String OBS_METRICS_ORG = "metrics-org";

    public static final String SCAN_DDL = "CREATE TABLE IF NOT EXISTS " + SCAN_TABLE + " ("
            + "kind VARCHAR(20) NOT NULL PRIMARY KEY, "
            + "run_id VARCHAR(40), "
            + "instance_id VARCHAR(120), "
            + "last_run_at VARCHAR(30), "
            + "duration_ms BIGINT, "
            + "total INTEGER, "
            + "warnings INTEGER, "
            + "errors INTEGER, "
            + "failure VARCHAR(500), "
            + "failure_at VARCHAR(30), "
            + "failure_by VARCHAR(120), "
            + "updated_at VARCHAR(30))";

    public static final String OBS_DDL = "CREATE TABLE IF NOT EXISTS " + OBS_TABLE + " ("
            + "name VARCHAR(60) NOT NULL PRIMARY KEY, "
            + "instance_id VARCHAR(120), "
            + "observed_ms BIGINT NOT NULL, "
            + "payload VARCHAR(4000), "
            + "updated_at VARCHAR(30))";

    /** Başarılı (sonuçları kaydedilmiş) tur — koşul: satırdaki son koşu bu turdan yeni değil. */
    static final String SQL_SCAN_RUN = "UPDATE " + SCAN_TABLE + " SET run_id = ?, instance_id = ?, last_run_at = ?, duration_ms = ?, "
            + "total = ?, warnings = ?, errors = ?, failure = ?, failure_at = ?, failure_by = ?, updated_at = ? "
            + "WHERE kind = ? AND (last_run_at IS NULL OR last_run_at <= ?)";
    /** Sonuç kaydedilemeden düşen tur — yalnız hata alanları (son başarılı koşunun kimliği, pod'u ve sayıları korunur). */
    static final String SQL_SCAN_FAIL = "UPDATE " + SCAN_TABLE + " SET failure = ?, failure_at = ?, failure_by = ?, updated_at = ? "
            + "WHERE kind = ? AND (failure_at IS NULL OR failure_at <= ?)";
    static final String SQL_SCAN_INSERT = "INSERT INTO " + SCAN_TABLE
            + "(kind, run_id, instance_id, last_run_at, duration_ms, total, warnings, errors, failure, failure_at, failure_by, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String SQL_SCAN_READ = "SELECT kind, run_id, instance_id, last_run_at, duration_ms, total, warnings, errors, "
            + "failure, failure_at, failure_by, updated_at FROM " + SCAN_TABLE;

    static final String SQL_OBS_UPDATE = "UPDATE " + OBS_TABLE + " SET instance_id = ?, observed_ms = ?, payload = ?, updated_at = ? "
            + "WHERE name = ? AND observed_ms < ?";
    static final String SQL_OBS_INSERT = "INSERT INTO " + OBS_TABLE + "(name, instance_id, observed_ms, payload, updated_at) "
            + "VALUES (?, ?, ?, ?, ?)";
    static final String SQL_OBS_READ = "SELECT instance_id, observed_ms, payload, updated_at FROM " + OBS_TABLE + " WHERE name = ?";

    /** Hata metni sütun genişliği. */
    static final int FAILURE_MAX = 500;
    static final int PAYLOAD_MAX = 4000;
    static final int ID_MAX = 120;
    private static final long WARN_EVERY_MS = 3_600_000L;

    /** Bir türün son koşusu ({@code lastRunAt} UTC {@code yyyy-MM-ddTHH:mm:ss}; hiç başarılı koşu yoksa null). */
    public record ScanRun(String kind, String runId, String instanceId, String lastRunAt, Long durationMs,
                          Integer total, Integer warnings, Integer errors, String failure, String failureAt, String failureBy,
                          String updatedAt) {}

    /** Kümedeki en yeni gözlem. */
    public record Observation(String instanceId, long observedMs, String payload, String updatedAt) {}

    private final JdbcTemplate jdbc;
    private final AtomicLong lastWarnAt = new AtomicLong();

    @Autowired
    public ClusterHealthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Tarama koşuları ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Turun sonucu. {@code total == null} → tur sonuç KAYDEDEMEDEN düştü: yalnız hata alanları yazılır. Aksi hâlde sonuçlar
     * kaydedilmiş bir tur (ardından alarm işleme düşmüş olabilir → {@code failure} dolu). Hiç fırlatmaz.
     *
     * @param at  turun bitiş anı (UTC {@code yyyy-MM-ddTHH:mm:ss}) — hem son koşu hem hata damgası
     * @return satır yazıldı mı (false = daha yeni bir satır var ya da tablo yok / DB hatası)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean recordScanRun(String kind, String runId, String instanceId, String at, Long durationMs,
                                 Integer total, Integer warnings, Integer errors, String failure) {
        try {
            String f = clip(failure, FAILURE_MAX);
            String failureAt = f == null ? null : at;
            instanceId = clip(instanceId, ID_MAX);
            String failureBy = f == null ? null : instanceId;
            int n = total != null
                    ? jdbc.update(SQL_SCAN_RUN, runId, instanceId, at, durationMs, total, warnings, errors, f, failureAt, failureBy, at, kind, at)
                    : jdbc.update(SQL_SCAN_FAIL, f, at, failureBy, at, kind, at);
            if (n > 0) return true;
            try {
                jdbc.update(SQL_SCAN_INSERT, kind, total != null ? runId : null, total != null ? instanceId : null,
                        total != null ? at : null, durationMs, total, warnings, errors, f, failureAt, failureBy, at);
                return true;
            } catch (DuplicateKeyException exists) {
                return false;   // satır var ve daha yeni (ya da eşzamanlı başka pod ekledi) — dokunulmaz
            }
        } catch (RuntimeException e) {
            warnRateLimited("Tarama koşusu küme durumuna yazılamadı (" + kind + "): " + e.getMessage());
            return false;
        }
    }

    /** Tüm türlerin son koşuları (tür → satır). Tablo yok / okunamıyor → fırlatır. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, ScanRun> readScanRuns() {
        Map<String, ScanRun> out = new LinkedHashMap<>();
        for (ScanRun r : jdbc.query(SQL_SCAN_READ, (rs, i) -> new ScanRun(
                rs.getString("kind"), rs.getString("run_id"), rs.getString("instance_id"), rs.getString("last_run_at"),
                longOrNull(rs.getObject("duration_ms")), intOrNull(rs.getObject("total")), intOrNull(rs.getObject("warnings")),
                intOrNull(rs.getObject("errors")), rs.getString("failure"), rs.getString("failure_at"), rs.getString("failure_by"),
                rs.getString("updated_at")))) {
            if (r.kind() != null) out.put(r.kind(), r);
        }
        return out;
    }

    // ── Gözlemler ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Yayın sonucu: yazıldı · satır zaten aynı / daha yeni gözlemde · yazılamadı (tablo yok, DB hatası — sonra yeniden). */
    public enum Publish { WRITTEN, NOT_NEWER, FAILED }

    /**
     * Gözlemi yayınla — yalnız satırdakinden YENİ ise yazılır (iki pod aynı anda yazarsa en yeni kalır). Hiç fırlatmaz.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Publish publish(String name, String instanceId, long observedMs, String payload, String at) {
        try {
            String p = clip(payload, PAYLOAD_MAX);
            instanceId = clip(instanceId, ID_MAX);
            if (jdbc.update(SQL_OBS_UPDATE, instanceId, observedMs, p, at, name, observedMs) > 0) return Publish.WRITTEN;
            try {
                jdbc.update(SQL_OBS_INSERT, name, instanceId, observedMs, p, at);
                return Publish.WRITTEN;
            } catch (DuplicateKeyException exists) {
                return Publish.NOT_NEWER;
            }
        } catch (RuntimeException e) {
            warnRateLimited("Küme gözlemi yazılamadı (" + name + "): " + e.getMessage());
            return Publish.FAILED;
        }
    }

    /** Gözlemin kümedeki son hâli; satır yoksa boş. Tablo yok / okunamıyor → fırlatır. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<Observation> read(String name) {
        List<Observation> rows = new ArrayList<>(jdbc.query(SQL_OBS_READ, (rs, i) -> new Observation(
                rs.getString("instance_id"), rs.getLong("observed_ms"), rs.getString("payload"), rs.getString("updated_at")), name));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Örnek kimliğinden pod adı: {@code SchedulerService} kimliği {@code <host>-<8 onaltılık>} biçimindedir; son ek atılır.
     * Biçime uymayan kimlik olduğu gibi döner. {@code null} → {@code null}.
     */
    public static String podOf(String instanceId) {
        if (instanceId == null) return null;
        int dash = instanceId.lastIndexOf('-');
        if (dash <= 0 || instanceId.length() - dash - 1 != 8) return instanceId;
        for (int i = dash + 1; i < instanceId.length(); i++) {
            if (Character.digit(instanceId.charAt(i), 16) < 0) return instanceId;
        }
        return instanceId.substring(0, dash);
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static Long longOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private void warnRateLimited(String msg) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_EVERY_MS && lastWarnAt.compareAndSet(last, now)) log.warn(msg);
        else log.debug(msg);
    }
}
