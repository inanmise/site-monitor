package com.sitemonitor.service.evalstate;

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
 * Kalıcı değerlendirme durumu şeması (2026-10-23, çok pod Sürüm 3-1, paket A1; tasarım R3-1 §2.2) — açılışta, İDEMPOTENT
 * ({@code ClusterHealthSchemaPatch} / {@code AlarmStateSchemaPatch} deseni; paylaşılan {@code applySchemaPatches}'e
 * dokunulmaz):
 * <ul>
 *   <li>Dört ham tablo ({@link EvalStateSql#TABLES}; entity yok) — {@code CREATE TABLE IF NOT EXISTS}. İki pod aynı anda
 *       açılırsa kaybedilen {@code CREATE} yarışı tablo yeniden denetlenerek yutulur (öteki pod kurmuştur).</li>
 *   <li>Çok pod Sürüm 3-2 (2026-10-28): {@code monitor_eval_type_state}'e üç null'lanabilir devir işareti sütunu
 *       ({@code state_gen}, {@code flushed_ms}, {@code handover_ms}) — sütun okunamazsa {@code ADD COLUMN IF NOT EXISTS};
 *       kaybedilen yarış yeniden okunarak yutulur ({@link #addMarkerColumns}).</li>
 *   <li>{@code idx_mls_type_domain} — tablo ile birlikte, küçük / boşken; her açılışta {@link SchemaDdlGuard} ile zaten
 *       varsa kilitsiz atlanır.</li>
 *   <li>Yalnız PostgreSQL: üç sık güncellenen tabloda {@code fillfactor = 70} (HOT güncellemeler). Tür okunamazsa atlanır
 *       (sonraki açılış).</li>
 * </ul>
 * Her ifade kendi try/catch'inde: biri düşerse açılış durmaz. Kaydedici tablolar yokken sessizce bekler
 * ({@code table_missing}) ve 60 sn'de bir yeniden dener. Kapı: {@code EvalStateSchemaPatchTest} (iki kez, kayıp yarış),
 * {@code EvalStatePostgresTest} (fillfactor).
 */
@Slf4j
@Component
public class EvalStateSchemaPatch {

    private final JdbcTemplate jdbc;

    @Autowired
    public EvalStateSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 120)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Kalıcı değerlendirme durumu şeması uygulanamadı (sonraki açılışta yeniden denenecek; karar etkilenmez): {}",
                    e.getMessage());
        }
    }

    /** @return bu koşuda kurulan tablo + uygulanan PostgreSQL ayarı sayısı (0 = güncel). */
    public int apply() {
        int changed = 0;
        for (int i = 0; i < EvalStateSql.TABLES.size(); i++) {
            changed += createTable(EvalStateSql.TABLES.get(i), EvalStateSql.TABLE_DDL.get(i)) ? 1 : 0;
        }
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        changed += addMarkerColumns(guard);
        try {
            if (tableReadable(EvalStateSql.LAST_STATUS) && !guard.alreadyInPlace(EvalStateSql.DDL_LAST_STATUS_INDEX)) {
                guard.executeBounded(EvalStateSql.DDL_LAST_STATUS_INDEX, false);
            }
        } catch (Exception e) {
            // İki pod aynı anda açılırsa kaybeden CREATE INDEX katalog yarışında düşebilir (PostgreSQL
            // pg_class_relname_nsp_index) — dizin öteki pod'da kurulduysa sorun yok (R3-1 FX-STATE bulgu #7).
            if (indexPresent(guard)) {
                log.debug("idx_mls_type_domain öteki pod tarafından aynı anda kuruldu: {}", e.getMessage());
            } else {
                log.warn("Kalıcı değerlendirme durumu: idx_mls_type_domain kurulamadı — {}", e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()))) {
            for (String ddl : EvalStateSql.FILLFACTOR_DDL) {
                try {
                    if (guard.alreadyInPlace(ddl)) continue;
                    guard.executeBounded(ddl, false);
                    changed++;
                } catch (Exception e) {
                    log.warn("Kalıcı değerlendirme durumu: doluluk ayarı uygulanamadı ({}) — {}", ddl, e.getMessage());
                }
            }
        }
        if (changed > 0) log.info("Kalıcı değerlendirme durumu şeması: {} değişiklik uygulandı", changed);
        return changed;
    }

    /**
     * 3-2 devir işareti sütunları ({@link EvalStateSql#MARKER_COLUMNS}) — 3-1 biçimli {@code monitor_eval_type_state}'e
     * (yeni kurulumda DDL zaten taşır): sütun okunamazsa {@code ADD COLUMN IF NOT EXISTS … BIGINT} (PostgreSQL'de 20 sn
     * kilit bekleme sınırıyla; küçük tablo, katalogda null'lanabilir ekleme). Kaybedilen yarış (öteki pod aynı anda ekledi)
     * sütun yeniden okunarak yutulur. 20.168.x ifadeleri açık sütun listesi kullanır — etkilenmez. @return eklenen sütun sayısı.
     */
    int addMarkerColumns(SchemaDdlGuard guard) {
        if (!tableReadable(EvalStateSql.TYPE_STATE)) return 0;
        int added = 0;
        for (String column : EvalStateSql.MARKER_COLUMNS) {
            if (columnReadable(column)) continue;
            String ddl = EvalStateSql.addMarkerColumnDdl(column);
            try {
                guard.executeBounded(ddl, false);
                added++;
            } catch (Exception e) {
                if (columnReadable(column)) {
                    log.debug("{}.{} sütunu öteki pod tarafından aynı anda eklendi: {}", EvalStateSql.TYPE_STATE, column,
                            e.getMessage());
                } else {
                    log.warn("Kalıcı değerlendirme durumu: {}.{} sütunu eklenemedi (devirde okuma bu açılışta 'columns_missing'; "
                            + "sonraki açılışta yeniden denenecek) — {}", EvalStateSql.TYPE_STATE, column, e.getMessage());
                }
            }
        }
        return added;
    }

    /** {@code monitor_eval_type_state.<column>} okunabiliyor mu (veri yolunun şema çözümüyle). */
    boolean columnReadable(String column) {
        try {
            jdbc.queryForList("SELECT " + column + " FROM " + EvalStateSql.TYPE_STATE + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
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
            log.warn("Kalıcı değerlendirme durumu: {} tablosu kurulamadı — {}", table, e.getMessage());
            return false;
        }
    }

    /**
     * {@code idx_mls_type_domain} var mı — PostgreSQL'de {@link SchemaDdlGuard#alreadyInPlace} ({@code to_regclass}), diğer
     * türde {@code information_schema.indexes}. Okunamazsa false (WARN yazılır — sessiz yutulmaz).
     */
    boolean indexPresent(SchemaDdlGuard guard) {
        try {
            if (guard.alreadyInPlace(EvalStateSql.DDL_LAST_STATUS_INDEX)) return true;
        } catch (Exception e) {
            // aşağıdaki taşınabilir okuma denenir
        }
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes "
                    + "WHERE lower(index_name) = 'idx_mls_type_domain'", Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
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
