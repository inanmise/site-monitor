package com.sitemonitor.service.baseline;

import com.sitemonitor.service.schema.DeferredIndexBuilder;
import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dinamik taban çizgisi örnek deposunun şeması (2026-11-12) — açılışta, İDEMPOTENT ({@code CheckJobSchemaPatch} deseni;
 * paylaşılan {@code applySchemaPatches}'e dokunulmaz). Tek ham tablo ({@link BaselineSampleStore#TABLE}; entity yok) +
 * {@code (monitor_type, monitor_id, checked_ms)} dizini — tablo ile birlikte, boşken. {@code monitor_baseline_config} bir
 * JPA entity'sidir (ddl-auto kurar); burada değildir.
 *
 * <p>Taban çizgisi KAPALIYKEN de koşar (boş bir tablo — davranışın izin verdiği tek fark; tablo okunmaz / yazılmaz). Başarıyla
 * kurulunca depoyu HAZIR bildirir ({@link BaselineSampleStore#markReady}). Kaybedilen {@code CREATE} yarışı (iki pod aynı anda
 * açılırsa) yeniden denetlenerek yutulur. Kapı: {@code BaselineSampleStoreH2Test}.</p>
 */
@Slf4j
@Component
public class BaselineSchemaPatch {

    private final JdbcTemplate jdbc;
    private final BaselineSampleStore store;

    @Autowired
    public BaselineSchemaPatch(JdbcTemplate jdbc, BaselineSampleStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 127)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Taban çizgisi örnek şeması uygulanamadı (sonraki açılışta yeniden denenecek; taban çizgisi "
                    + "kapalıyken etkisiz): {}", e.getMessage());
        }
    }

    /** @return bu koşuda kurulan tablo / dizin sayısı (0 = güncel). */
    public int apply() {
        int changed = createTable();
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        if (tableReadable()) {
            boolean pg = Boolean.TRUE.equals(DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()));
            int idx = bounded(guard, BaselineSampleStore.DDL_INDEX, "idx_mbs_monitor");
            if (pg) changed += idx;   // H2'de IF NOT EXISTS her açılışta etkisiz koşar → yalnız PostgreSQL'de sayılır
            if (store != null) store.markReady(true);
        }
        if (changed > 0) log.info("Taban çizgisi örnek şeması: {} değişiklik uygulandı", changed);
        return changed;
    }

    private int createTable() {
        try {
            jdbc.execute(BaselineSampleStore.DDL_TABLE);
            return 1;
        } catch (Exception e) {
            if (tableReadable()) return 0;   // öteki pod kurmuş — yut
            throw e;
        }
    }

    private boolean tableReadable() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + BaselineSampleStore.TABLE + " WHERE 1 = 0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int bounded(SchemaDdlGuard guard, String ddl, String what) {
        try {
            if (guard.alreadyInPlace(ddl)) return 0;
            guard.executeBounded(ddl, false);
            return 1;
        } catch (Exception e) {
            log.debug("Taban çizgisi dizini kurulamadı ({}): {}", what, e.getMessage());
            return 0;
        }
    }
}
