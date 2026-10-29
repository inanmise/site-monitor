package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.schema.DeferredIndexBuilder;
import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Parçalı sertifika taraması şeması (2026-10-29, çok pod Sürüm 3-4 paket A; tasarım R3-4 §4.1) — açılışta, İDEMPOTENT (3-3
 * {@code CheckJobSchemaPatch} deseni; paylaşılan {@code applySchemaPatches}'e dokunulmaz):
 * <ul>
 *   <li>İki ham tablo ({@link CertSweepSql#TABLES}; entity yok) — {@code CREATE TABLE IF NOT EXISTS}. İki pod aynı anda
 *       açılırsa kaybedilen {@code CREATE} yarışı tablo yeniden denetlenerek yutulur (öteki pod kurmuştur).</li>
 *   <li>Üç dizin ({@code idx_csr_state}, {@code ux_csc_run_chunk}, {@code idx_csc_state}) — tablolarla birlikte, boşken
 *       (ertelenmiş katalogda DEĞİL); her açılışta {@link SchemaDdlGuard} ile zaten varsa kilitsiz atlanır.</li>
 *   <li>Yalnız PostgreSQL: parça tablosunun otomatik süpürme eşikleri ({@link CertSweepSql#RELOPTIONS_PG}). Tür okunamazsa
 *       atlanır (sonraki açılış).</li>
 * </ul>
 * Sonunda tabloların okunabilirliğini depoya bildirir ({@link JdbcCertSweepStore#markTablesReady}; bellek) — koordinatör /
 * işçi {@link CertSweepStore#tablesReady()} ile sorgusuz okur.
 *
 * <p>Anahtar KAPALIYKEN de koşar — iki boş tablo L-1'in izin verdiği farktır; tablolar okunmaz / yazılmaz. Her ifade kendi
 * try/catch'inde: biri düşerse açılış durmaz; depo tablo yokken boş / 0 döner, koordinatör turu klasik koşar.
 */
@Slf4j
@Component
public class CertSweepSchemaPatch {

    private final JdbcTemplate jdbc;
    private final Supplier<JdbcCertSweepStore> store;

    @Autowired
    public CertSweepSchemaPatch(JdbcTemplate jdbc, ObjectProvider<JdbcCertSweepStore> store) {
        this.jdbc = jdbc;
        this.store = () -> store == null ? null : store.getIfAvailable();
    }

    /** Test / elle kurulum: depo verilirse sonunda tabloların hazır olduğu bildirilir. */
    public CertSweepSchemaPatch(JdbcTemplate jdbc, JdbcCertSweepStore store) {
        this.jdbc = jdbc;
        this.store = () -> store;
    }

    /** Test: depo yok. */
    public CertSweepSchemaPatch(JdbcTemplate jdbc) {
        this(jdbc, (JdbcCertSweepStore) null);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 126)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Parçalı sertifika taraması şeması uygulanamadı (sonraki açılışta yeniden denenecek; anahtar kapalıyken "
                    + "etkisiz): {}", e.getClass().getSimpleName());
        }
    }

    /** @return bu koşuda kurulan tablo / dizin / ayar sayısı (0 = güncel). */
    public int apply() {
        int changed = 0;
        changed += createTable(CertSweepSql.RUNS, CertSweepSql.DDL_RUNS) ? 1 : 0;
        changed += createTable(CertSweepSql.CHUNKS, CertSweepSql.DDL_CHUNKS) ? 1 : 0;
        boolean ready = tableReadable(CertSweepSql.RUNS) && tableReadable(CertSweepSql.CHUNKS);
        signal(ready);
        if (!ready) return changed;
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        boolean pg = Boolean.TRUE.equals(DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()));
        // H2'de "zaten var mı" bilinemez (IF NOT EXISTS her açılışta etkisiz koşar) → yalnız PostgreSQL'de sayılır.
        int idx = bounded(guard, CertSweepSql.DDL_RUNS_STATE_INDEX, "idx_csr_state")
                + bounded(guard, CertSweepSql.DDL_CHUNKS_RUN_UNIQUE, "ux_csc_run_chunk")
                + bounded(guard, CertSweepSql.DDL_CHUNKS_STATE_INDEX, "idx_csc_state");
        if (pg) {
            changed += idx;
            changed += bounded(guard, CertSweepSql.RELOPTIONS_PG, "autovacuum");
        }
        if (changed > 0) log.info("Parçalı sertifika taraması şeması: {} değişiklik uygulandı", changed);
        return changed;
    }

    private void signal(boolean ready) {
        try {
            JdbcCertSweepStore s = store.get();
            if (s != null) s.markTablesReady(ready);
        } catch (RuntimeException e) {
            log.debug("Parçalı tarama deposuna tablo durumu bildirilemedi: {}", e.getClass().getSimpleName());
        }
    }

    /** Zaten varsa kilitsiz atla; yoksa 20 sn kilit sınırıyla çalıştır. Kaybedilen yarış yeniden denetlenerek yutulur. */
    private int bounded(SchemaDdlGuard guard, String ddl, String what) {
        try {
            if (guard.alreadyInPlace(ddl)) return 0;
            guard.executeBounded(ddl, false);
            return 1;
        } catch (Exception e) {
            boolean present;
            try {
                present = guard.alreadyInPlace(ddl);
            } catch (Exception ignore) {
                present = false;
            }
            if (present) {
                log.debug("Parçalı tarama: {} öteki pod tarafından aynı anda kuruldu: {}", what, e.getClass().getSimpleName());
            } else {
                log.warn("Parçalı tarama: {} kurulamadı (sonraki açılışta yeniden denenecek) — {}", what,
                        e.getClass().getSimpleName());
            }
            return 0;
        }
    }

    private boolean createTable(String table, String ddl) {
        if (tableReadable(table)) return false;
        try {
            jdbc.execute(ddl);
            return true;
        } catch (RuntimeException e) {
            if (tableReadable(table)) {
                log.debug("{} tablosu öteki pod tarafından aynı anda kuruldu: {}", table, e.getClass().getSimpleName());
                return false;
            }
            log.warn("Parçalı tarama: {} tablosu kurulamadı — {}", table, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Veri yolunun kendi sorgularıyla aynı şema çözümü — tablo okunabiliyor mu (başka hata → false, açılış sürer). */
    boolean tableReadable(String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        } catch (DataAccessException other) {
            return false;
        }
    }
}
