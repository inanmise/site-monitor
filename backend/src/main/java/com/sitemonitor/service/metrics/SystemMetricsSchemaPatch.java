package com.sitemonitor.service.metrics;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sistem metrikleri örnek tablosunun kolon yaması (2026-10-23, çok pod R2b, MP-37c) — açılışta, İDEMPOTENT.
 * {@code NotificationDurabilitySchemaPatch} deseni: paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz.
 *
 * <ul>
 *   <li>{@code system_metric_samples.clock_skew_ms} — pod saati − veritabanı saati (ms), {@link SystemMetricSampler}
 *       dakikalık örnekte yazar; kendi kendini izleme {@code clock_skew} kontrolü her canlı pod'un son örneğinden okur.</li>
 * </ul>
 * Kolon varlıkta da tanımlı (ddl-auto ekler); bu yama ddl-auto'nun sessizce atladığı ortamları kapatır. NULLABLE, varsayılansız
 * (eski satırlar ve 20.166.0 pod'larının satırları null = ölçülmedi). Her ifade kendi try/catch'inde ({@link SchemaDdlGuard});
 * düşerse açılış durmaz. Kolon eksikken izleme yoklaması kolonsuz sorguya döner (saat farkı "ölçülmedi").
 * Kapı: {@code SystemMetricsSchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMetricsSchemaPatch {

    /** Tablo, kolon, tür (NULLABLE). */
    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("system_metric_samples", "clock_skew_ms", "BIGINT"));

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Sistem metrikleri şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda EKLENEN kolon sayısı (0 = güncel ya da tablo yok). */
    public int apply() {
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        int added = 0;
        for (Col c : COLUMNS) {
            try {
                if (!tableExists(c.table())) continue;
                if (columnExists(c.table(), c.column())) continue;
                guard.executeBounded("ALTER TABLE " + c.table() + " ADD COLUMN " + c.column() + " " + c.type(), false);
                added++;
            } catch (Exception e) {
                log.warn("Sistem metrikleri şema yaması uygulanamadı: {}.{} — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (added > 0) log.info("Sistem metrikleri şema yaması: {} kolon eklendi", added);
        return added;
    }

    boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, table);
        return n != null && n > 0;
    }

    boolean columnExists(String table, String col) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
                Integer.class, table, col);
        return n != null && n > 0;
    }
}
