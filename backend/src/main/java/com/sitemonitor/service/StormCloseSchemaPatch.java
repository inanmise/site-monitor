package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code alert_storms} iki aşamalı kapanış sütunları (2026-10-21, çok pod — inceleme S-1 / F1) — açılışta, İDEMPOTENT.
 *
 * <p>Sütunlar varlıktan ({@code AlertStorm}) ddl-auto ile de doğar; bu yama ona GÜVENMEDEN eksik sütunu NULLABLE ekler
 * (paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunmadan — {@code NocCallLogSchemaPatch} deseni). NULL = açık
 * fırtına (ya da bu sütunlardan önce kapanmış kayıt). Sütun yokken {@code StormService} eski tek aşamalı kapanışa düşer.
 * Kapı: {@code StormCloseSchemaPatchTest} (H2 — eski şemadan iki kez).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StormCloseSchemaPatch {

    static final String TABLE = "alert_storms";

    /** Sütun → tür (hepsi NULLABLE). */
    static final Map<String, String> COLUMNS = columns();

    private final JdbcTemplate jdbc;

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("close_state", "VARCHAR(16)");
        m.put("close_claimed_at", "VARCHAR(30)");
        m.put("close_owner", "VARCHAR(120)");
        m.put("close_attempts", "INTEGER");
        m.put("recovery_state", "VARCHAR(16)");
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Fırtına kapanış şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda EKLENEN sütun sayısı (0 = zaten güncel ya da tablo yok). */
    public int apply() {
        if (!tableExists()) {
            log.warn("Fırtına tablosu ({}) yok — yama atlandı (varlık tabloyu oluşturmalıydı)", TABLE);
            return 0;
        }
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        int added = 0;
        for (Map.Entry<String, String> c : COLUMNS.entrySet()) {
            try {
                if (columnExists(c.getKey())) continue;
                guard.executeBounded("ALTER TABLE " + TABLE + " ADD COLUMN " + c.getKey() + " " + c.getValue(), false);
                added++;
            } catch (Exception e) {
                log.warn("Fırtına kapanış şema yaması uygulanamadı: {}.{} — {}", TABLE, c.getKey(), e.getMessage());
            }
        }
        if (added > 0) log.info("Fırtına kapanış şema yaması: {} sütun eklendi", added);
        return added;
    }

    boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, TABLE);
        return n != null && n > 0;
    }

    boolean columnExists(String col) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
                Integer.class, TABLE, col);
        return n != null && n > 0;
    }
}
