package com.sitemonitor.service.retention;

import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.model.RetentionRunItem;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.AppSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RetentionCatalog} listesini çalıştıran servis — gece temizliğinin motoru.
 *
 * <p>Eskiden bu iş {@code SchedulerService.cleanupOldLogs} içinde ~150 satır elle yazılmış DELETE
 * bloğuydu. Davranış birebir korunmuştur (bkz. {@code RetentionSqlIdentityTest}); değişen yalnız
 * biçimdir — ve biçim değiştiği için dry-run, ekran, metrik, doküman ve bekçi testi aynı kaynaktan
 * bedavaya gelir.
 *
 * <p><b>Dry-run kesinlikle silmez:</b> aynı WHERE ile {@code COUNT(*)} çalıştırır. Yanlışlıkla
 * gerçek silme yapan bir dry-run felaket olacağından bu ayrı bir testle kilitlenmiştir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Tek tablonun batch silmesi için duvar-saati üst sınırı (kalanı ertesi geceye kalır). */
    private static final long BATCH_TIME_CAP_MS = 600_000L;

    private final JdbcTemplate jdbcTemplate;
    private final AppSettingsService appSettings;
    private final RetentionRunRepository runRepo;
    private final RetentionRunItemRepository itemRepo;
    private final RetentionMetrics metrics;

    // ── Sonuç tipleri ────────────────────────────────────────────────────────────

    /** Tek politikanın sonucu. */
    public record ItemResult(String policyId, String table, String cutoff, int rows,
                             long elapsedMs, String error, String skipped) {
        public boolean failed() { return error != null; }
    }

    /** Bir çalışmanın tamamı. */
    public record RunResult(Long runId, String startedAt, String finishedAt, boolean dryRun,
                            boolean holdActive, long totalRows, int failedCount,
                            long durationMs, List<ItemResult> items) { }

    // ── Ayar okuma ───────────────────────────────────────────────────────────────

    /** Legal hold açık mı — açıkken hiçbir silme yapılmaz. */
    public boolean holdActive() {
        return appSettings.getBoolean(RetentionCatalog.HOLD_KEY, false);
    }

    /**
     * Politikanın ETKİN gün değeri: ayar varsa o, yoksa varsayılan; her durumda {@code minDays}
     * tabanına kırpılır. {@code zeroMeansNever} kurallarında 0 aynen geçer (opt-in kapalı demek).
     */
    public int effectiveDays(RetentionPolicy p) {
        if (p.settingKey() == null) return p.defaultDays();
        int v = appSettings.getInt(p.settingKey(), p.defaultDays());
        if (p.zeroMeansNever() && v <= 0) return 0;
        return Math.max(p.minDays(), v);
    }

    /** Politikanın kesim tarihi (öksüz temizliğinde null). */
    public String cutoffFor(RetentionPolicy p) {
        if (p.mode() == RetentionPolicy.Mode.ORPHAN_ONLY) return null;
        int days = effectiveDays(p);
        if (p.zeroMeansNever() && days <= 0) return null;
        String iso = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
        return switch (p.timeKind()) {
            case DATE10 -> iso.substring(0, 10);
            case DATE13 -> iso.substring(0, 13);
            default -> iso;
        };
    }

    // ── Çalıştırma ───────────────────────────────────────────────────────────────

    /** Gece işi: gerçek silme. */
    public RunResult runCleanup() {
        return execute(false, null);
    }

    /** Yalnız sayım — hiçbir satır silinmez. */
    public RunResult dryRun() {
        return execute(true, null);
    }

    /**
     * Kataloğu sırayla çalıştırır. Bir politikanın hatası diğerlerini DURDURMAZ (eski
     * {@code safeDelete} davranışı); hata satır bazında kaydedilir.
     *
     * @param dryRun      true → COUNT(*), hiçbir DELETE üretilmez
     * @param triggeredBy elle tetikleyen kullanıcı (gece işinde null)
     */
    public RunResult execute(boolean dryRun, String triggeredBy) {
        long t0 = System.currentTimeMillis();
        String startedAt = ISO.format(Instant.now());
        boolean hold = holdActive();
        List<ItemResult> items = new ArrayList<>();

        if (hold && !dryRun) {
            // Yasal saklama: soruşturma/denetim sürerken hiçbir kayıt silinmemeli.
            log.warn("RETENTION LEGAL HOLD AKTİF — gece temizliği hiçbir satır silmedi ({}={})",
                    RetentionCatalog.HOLD_KEY, true);
            for (RetentionPolicy p : RetentionCatalog.executable()) {
                items.add(new ItemResult(p.id(), p.table(), null, 0, 0, null, "legal-hold"));
            }
            return persist(startedAt, dryRun, true, triggeredBy, items, System.currentTimeMillis() - t0);
        }

        for (RetentionPolicy p : RetentionCatalog.executable()) {
            items.add(runOne(p, dryRun));
        }
        long duration = System.currentTimeMillis() - t0;
        RunResult result = persist(startedAt, dryRun, hold, triggeredBy, items, duration);

        if (!dryRun) {
            metrics.recordRun(result);
            long failed = items.stream().filter(ItemResult::failed).count();
            log.info("Retention: {} politika, {} satır silindi, {} hata, {} ms",
                    items.size(), result.totalRows(), failed, duration);
        }
        return result;
    }

    /** Tek politikayı çalıştırır — asla exception fırlatmaz. */
    ItemResult runOne(RetentionPolicy p, boolean dryRun) {
        long t0 = System.currentTimeMillis();
        String cutoff = cutoffFor(p);

        if (p.zeroMeansNever() && effectiveDays(p) <= 0) {
            return new ItemResult(p.id(), p.table(), null, 0, 0, null, "opt-in-kapali");
        }
        try {
            int rows = dryRun ? count(p, cutoff) : delete(p, cutoff);
            return new ItemResult(p.id(), p.table(), cutoff, rows, System.currentTimeMillis() - t0, null, null);
        } catch (Exception e) {
            log.warn("Retention '{}' ({}) başarısız: {}", p.id(), p.table(), e.getMessage());
            return new ItemResult(p.id(), p.table(), cutoff, 0, System.currentTimeMillis() - t0,
                    String.valueOf(e.getMessage()), null);
        }
    }

    /** Dry-run sayımı — DELETE ÜRETMEZ. */
    private int count(RetentionPolicy p, String cutoff) {
        Long n = p.paramCount() == 0
                ? jdbcTemplate.queryForObject(p.countSql(), Long.class)
                : jdbcTemplate.queryForObject(p.countSql(), Long.class, cutoff);
        return n == null ? 0 : (int) Math.min(Integer.MAX_VALUE, n);
    }

    private int delete(RetentionPolicy p, String cutoff) {
        if (p.batched()) return deleteBatched(p, cutoff);
        // Öksüz temizliğinde parametre YOK → varargs değil düz çağrı (niyet net, mock'ta da tekil).
        return p.paramCount() == 0
                ? jdbcTemplate.update(p.deleteSql())
                : jdbcTemplate.update(p.deleteSql(), cutoff);
    }

    /**
     * Yüksek hacimli tablolarda tek dev DELETE yerine dilimli silme + ANALYZE: tablo şişmesi ve
     * uzun kilit yerine kısa transaction'lar. Tablo bir {@code id} kolonu ister.
     */
    private int deleteBatched(RetentionPolicy p, String cutoff) {
        int batch = Math.max(1000, appSettings.getInt(RetentionCatalog.BATCH_KEY, 10000));
        String sql = "DELETE FROM " + p.table() + " WHERE id IN (SELECT id FROM " + p.table()
                + " WHERE " + p.resolvedWhere() + " LIMIT " + batch + ")";
        Object[] args = p.paramCount() == 0 ? new Object[0] : new Object[]{ cutoff };
        int total = 0;
        long start = System.currentTimeMillis();
        while (true) {
            int n = jdbcTemplate.update(sql, args);
            total += n;
            if (n < batch) break;
            if (System.currentTimeMillis() - start > BATCH_TIME_CAP_MS) {
                log.warn("Batch silme '{}' 10dk'yı aştı, {} satırda durduruldu (kalanı ertesi gece)",
                        p.table(), total);
                break;
            }
        }
        if (total > 0) {
            try { jdbcTemplate.execute("ANALYZE " + p.table()); }
            catch (Exception ignored) { /* H2/izin yoksa sorun değil */ }
        }
        return total;
    }

    // ── Kalıcılık ────────────────────────────────────────────────────────────────

    private RunResult persist(String startedAt, boolean dryRun, boolean hold, String triggeredBy,
                              List<ItemResult> items, long durationMs) {
        long totalRows = items.stream().mapToLong(ItemResult::rows).sum();
        int failed = (int) items.stream().filter(ItemResult::failed).count();
        String finishedAt = ISO.format(Instant.now());
        Long runId = null;
        try {
            RetentionRun run = new RetentionRun();
            run.setStartedAt(startedAt);
            run.setFinishedAt(finishedAt);
            run.setDryRun(dryRun);
            run.setHoldActive(hold);
            run.setTriggeredBy(triggeredBy);
            run.setTotalDeleted(totalRows);
            run.setFailedCount(failed);
            run.setDurationMs(durationMs);
            run.setInstanceId(System.getenv("HOSTNAME"));
            runId = runRepo.save(run).getId();

            List<RetentionRunItem> rows = new ArrayList<>(items.size());
            for (ItemResult it : items) {
                RetentionRunItem e = new RetentionRunItem();
                e.setRunId(runId);
                e.setPolicyId(it.policyId());
                e.setTableName(it.table());
                e.setCutoff(it.cutoff());
                e.setRowsDeleted(it.rows());
                e.setElapsedMs(it.elapsedMs());
                e.setError(it.error());
                e.setSkipped(it.skipped());
                e.setCreatedAt(finishedAt);
                rows.add(e);
            }
            itemRepo.saveAll(rows);
        } catch (Exception e) {
            // Çalışma kaydı yazılamazsa temizliğin kendisi geçersiz olmaz — yalnız geçmiş eksilir.
            log.warn("Retention çalışma kaydı yazılamadı: {}", e.getMessage());
        }
        return new RunResult(runId, startedAt, finishedAt, dryRun, hold, totalRows, failed, durationMs, items);
    }

    // ── Ekran için istatistik ────────────────────────────────────────────────────

    /**
     * Politika başına canlı durum: etkin gün, kesim tarihi, tablo satır/boyut tahmini,
     * en eski ve en yeni kayıt tarihi. Tablo boyutları Postgres istatistiklerinden okunur
     * (tam sayım yapılmaz — büyük tabloları taramak pahalıdır).
     */
    public List<Map<String, Object>> overview(boolean includeEstimate) {
        Map<String, long[]> sizes = tableSizes();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetentionPolicy p : RetentionCatalog.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("table", p.table());
            m.put("time_column", p.timeColumn());
            m.put("mode", p.mode().name());
            m.put("data_class", p.dataClass().name());
            m.put("setting_key", p.settingKey());
            m.put("configurable", p.configurable());
            m.put("deletes", p.deletes());
            m.put("default_days", p.defaultDays());
            m.put("min_days", p.minDays());
            m.put("zero_means_never", p.zeroMeansNever());
            m.put("rationale", p.rationale());
            m.put("rule", p.deletes() ? p.resolvedWhere() : null);
            m.put("batched", p.batched());
            if (p.deletes()) {
                m.put("days", effectiveDays(p));
                m.put("cutoff", cutoffFor(p));
            }
            long[] sz = sizes.get(p.table().toLowerCase());
            m.put("rows", sz != null ? sz[0] : null);
            m.put("bytes", sz != null ? sz[1] : null);
            String[] bounds = bounds(p);
            m.put("oldest_at", bounds[0]);
            m.put("newest_at", bounds[1]);
            if (includeEstimate && p.deletes()) {
                m.put("purgeable", estimate(p));
            }
            out.add(m);
        }
        return out;
    }

    /** Bu politikanın ŞU AN sileceği satır sayısı (dry-run tahmini). Hata → null. */
    public Integer estimate(RetentionPolicy p) {
        if (p.zeroMeansNever() && effectiveDays(p) <= 0) return 0;
        try {
            return count(p, cutoffFor(p));
        } catch (Exception e) {
            log.debug("Retention tahmini '{}' başarısız: {}", p.id(), e.getMessage());
            return null;
        }
    }

    /** Tablodaki en eski ve en yeni kayıt tarihi — kullanıcıya "veri şu tarihten beri" demek için. */
    public String[] bounds(RetentionPolicy p) {
        if (p.timeColumn() == null) return new String[]{ null, null };
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MIN(" + p.timeColumn() + "), MAX(" + p.timeColumn() + ") FROM " + p.table(),
                    (rs, i) -> new String[]{ str(rs.getObject(1)), str(rs.getObject(2)) });
        } catch (Exception e) {
            return new String[]{ null, null };
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** tablo → [satır tahmini, toplam bayt]. Postgres dışında boş döner. */
    public Map<String, long[]> tableSizes() {
        Map<String, long[]> out = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT relname, n_live_tup, pg_total_relation_size(relid) FROM pg_stat_user_tables",
                    rs -> {
                        out.put(rs.getString(1).toLowerCase(),
                                new long[]{ rs.getLong(2), rs.getLong(3) });
                    });
        } catch (Exception e) {
            log.debug("Tablo boyutları okunamadı (Postgres değil?): {}", e.getMessage());
        }
        return out;
    }

    /** Kontrol Geçmişi kırpması için: bu izleme türünün saklama günü (katalogdan, tek kaynak). */
    public int historyRetentionDays(String kind, int fallback) {
        String policyId = RetentionCatalog.policyIdForHistoryKind(kind);
        if (policyId == null) return fallback;
        return RetentionCatalog.byId(policyId).map(this::effectiveDays).orElse(fallback);
    }
}
