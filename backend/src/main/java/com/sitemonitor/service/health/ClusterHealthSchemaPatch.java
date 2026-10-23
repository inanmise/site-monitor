package com.sitemonitor.service.health;

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

import java.util.List;

/**
 * Sistem Sağlığı küme durumu şeması (2026-10-23, çok pod R2b, MP-34 / MP-35 / N4) — açılışta, İDEMPOTENT
 * ({@code CacheBusSchemaPatch} / {@code NotificationDurabilitySchemaPatch} deseni; paylaşılan
 * {@code SchedulerService.applySchemaPatches}'e dokunulmaz, çok pod planı G4):
 * <ul>
 *   <li>{@code scan_runs} ve {@code cluster_observations} tabloları ({@link ClusterHealthStore}; entity yok, ham SQL).</li>
 *   <li>{@code system_heartbeat.instance_id} ({@code VARCHAR(120)}) ve {@code system_heartbeat.shutdown_marker}
 *       ({@code BOOLEAN}) — heartbeat'i hangi pod'un yazdığı ve düzgün kapanış işareti. Kolonlar varlıkta da tanımlı
 *       (ddl-auto ekler); bu yama ddl-auto'nun atladığı ortamları kapatır. Eski satırlar NULL (pod'u bilinmiyor — pod
 *       listesine girmez, küme zaman çizelgesinde sayılır).</li>
 * </ul>
 * İki pod aynı anda açılırsa {@code CREATE TABLE IF NOT EXISTS} yarışında ikincisinin hatası tablo yeniden denetlenerek
 * yutulur (öteki kurmuştur). Her ifade kendi try/catch'inde; düşerse açılış durmaz, kod tablo / kolon yokken bu pod'un
 * belleğine düşer ({@code source: "local"}) ve sonraki açılışta yeniden denenir. Kapı: {@code ClusterHealthSchemaPatchTest}.
 */
@Slf4j
@Component
public class ClusterHealthSchemaPatch {

    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("system_heartbeat", "instance_id", "VARCHAR(120)"),
            new Col("system_heartbeat", "shutdown_marker", "BOOLEAN"));

    private final JdbcTemplate jdbc;

    @Autowired
    public ClusterHealthSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 110)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Sistem sağlığı küme şeması uygulanamadı (sonraki açılışta yeniden denenecek; ekran bu pod'un belleğini "
                    + "gösterir): {}", e.getMessage());
        }
    }

    /** @return bu koşuda kurulan tablo + eklenen kolon sayısı (0 = güncel). */
    public int apply() {
        int changed = 0;
        changed += createTable(ClusterHealthStore.SCAN_TABLE, ClusterHealthStore.SCAN_DDL) ? 1 : 0;
        changed += createTable(ClusterHealthStore.OBS_TABLE, ClusterHealthStore.OBS_DDL) ? 1 : 0;
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        for (Col c : COLUMNS) {
            try {
                if (!tableReadable(c.table())) continue;            // tablo yok (ddl-auto kuracak, kolonla birlikte)
                if (columnReadable(c.table(), c.column())) continue;
                guard.executeBounded("ALTER TABLE " + c.table() + " ADD COLUMN " + c.column() + " " + c.type(), false);
                changed++;
            } catch (Exception e) {
                log.warn("Sistem sağlığı küme şeması: {}.{} eklenemedi — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (changed > 0) log.info("Sistem sağlığı küme şeması: {} değişiklik uygulandı", changed);
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
            log.warn("Sistem sağlığı küme şeması: {} tablosu kurulamadı — {}", table, e.getMessage());
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

    boolean columnReadable(String table, String column) {
        try {
            jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        }
    }
}
