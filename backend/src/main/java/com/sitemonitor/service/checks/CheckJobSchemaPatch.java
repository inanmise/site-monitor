package com.sitemonitor.service.checks;

import com.sitemonitor.service.schema.DeferredIndexBuilder;
import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dağıtık kontrol kuyruğu şeması (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3 §3.1) — açılışta, İDEMPOTENT
 * ({@code EvalStateSchemaPatch} / {@code AlarmStateSchemaPatch} deseni; paylaşılan {@code applySchemaPatches}'e dokunulmaz):
 * <ul>
 *   <li>İki ham tablo ({@link CheckJobSql#TABLES}; entity yok) — {@code CREATE TABLE IF NOT EXISTS}. İki pod aynı anda
 *       açılırsa kaybedilen {@code CREATE} yarışı tablo yeniden denetlenerek yutulur (öteki pod kurmuştur).</li>
 *   <li>{@code idx_mcj_state} — tablo ile birlikte, boşken; her açılışta {@link SchemaDdlGuard} ile zaten varsa kilitsiz
 *       atlanır.</li>
 *   <li>Yalnız PostgreSQL: kısmi tekil {@code ux_mcj_open} (izleme başına en çok bir açık iş) ve otomatik süpürme eşikleri —
 *       ikisi de tablo ile birlikte (boş tablo; ertelenmiş katalogda DEĞİL). Tür okunamazsa atlanır (sonraki açılış); dizin
 *       yokken kuyruk taşınabilir "açık işi yoksa ekle" yolunu kullanır ({@code JdbcCheckJobStore}).</li>
 * </ul>
 * Ana anahtar KAPALIYKEN de koşar — iki boş tablo, K-1'in izin verdiği TEK farktır; tablolar okunmaz / yazılmaz. Her ifade
 * kendi try/catch'inde: biri düşerse açılış durmaz; kuyruk tablo yokken boş / 0 döner. Kapı: {@code CheckJobSqlH2Test}
 * (iki kez, kayıp yarış), {@code CheckJobPostgresTest} (dizin, ayar).
 */
@Slf4j
@Component
public class CheckJobSchemaPatch {

    private final JdbcTemplate jdbc;

    @Autowired
    public CheckJobSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 125)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Dağıtık kontrol kuyruğu şeması uygulanamadı (sonraki açılışta yeniden denenecek; ana anahtar kapalıyken "
                    + "etkisiz): {}", e.getMessage());
        }
    }

    /** @return bu koşuda kurulan tablo / dizin / ayar sayısı (0 = güncel). */
    public int apply() {
        int changed = 0;
        changed += createTable(CheckJobSql.JOBS, CheckJobSql.DDL_JOBS) ? 1 : 0;
        changed += createTable(CheckJobSql.WORKERS, CheckJobSql.DDL_WORKERS) ? 1 : 0;
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        if (!tableReadable(CheckJobSql.JOBS)) return changed;
        boolean pg = Boolean.TRUE.equals(DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()));
        // H2'de "zaten var mı" bilinemez (IF NOT EXISTS her açılışta etkisiz koşar) → yalnız PostgreSQL'de sayılır.
        int idx = bounded(guard, CheckJobSql.DDL_STATE_INDEX, "idx_mcj_state");
        if (pg) {
            changed += idx;
            changed += bounded(guard, CheckJobSql.DDL_OPEN_UNIQUE_PG, CheckJobSql.OPEN_UNIQUE_INDEX);
            changed += bounded(guard, CheckJobSql.RELOPTIONS_PG, "autovacuum");
        }
        if (changed > 0) log.info("Dağıtık kontrol kuyruğu şeması: {} değişiklik uygulandı", changed);
        return changed;
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
                log.debug("Dağıtık kontrol kuyruğu: {} öteki pod tarafından aynı anda kuruldu: {}", what, e.getMessage());
            } else {
                log.warn("Dağıtık kontrol kuyruğu: {} kurulamadı (sonraki açılışta yeniden denenecek) — {}", what, e.getMessage());
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
                log.debug("{} tablosu öteki pod tarafından aynı anda kuruldu: {}", table, e.getMessage());
                return false;
            }
            log.warn("Dağıtık kontrol kuyruğu: {} tablosu kurulamadı — {}", table, e.getMessage());
            return false;
        }
    }

    /** Veri yolunun kendi sorgularıyla aynı şema çözümü — tablo okunabiliyor mu. */
    boolean tableReadable(String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        }
    }
}
