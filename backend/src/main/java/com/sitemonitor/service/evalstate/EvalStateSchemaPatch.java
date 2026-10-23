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
        try {
            if (tableReadable(EvalStateSql.LAST_STATUS) && !guard.alreadyInPlace(EvalStateSql.DDL_LAST_STATUS_INDEX)) {
                guard.executeBounded(EvalStateSql.DDL_LAST_STATUS_INDEX, false);
            }
        } catch (Exception e) {
            log.warn("Kalıcı değerlendirme durumu: idx_mls_type_domain kurulamadı — {}", e.getMessage());
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
