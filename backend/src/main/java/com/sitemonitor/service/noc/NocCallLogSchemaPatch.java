package com.sitemonitor.service.noc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 7/24 arama kaydı ({@code noc_call_log}) şema yaması (2026-09-27) — açılışta, İDEMPOTENT.
 *
 * <p>Tablo varlığından ({@code NocCallLog}) doğar; bu yama ddl-auto'ya GÜVENMEDEN iki şeyi garanti eder:
 * <ul>
 *   <li><b>Kolonlar</b> — tablo bu sürümden önce daha az kolonla oluştuysa eksik kolon NULLABLE eklenir
 *       (dolu tabloya NOT NULL eklemek ddl-auto'da sessizce düşer; proje tuzağı).</li>
 *   <li><b>İndeksler</b> — uyarı detayı ({@code alert_id}) ve liste özeti tek sorgusu bu indekse dayanır; açıkça
 *       {@code CREATE INDEX IF NOT EXISTS}.</li>
 * </ul>
 * Her ifade kendi try/catch'inde: biri düşerse açılış durmaz, kalanlar uygulanır ve günlüğe yazılır. Kendi
 * {@code ApplicationReadyEvent} dinleyicisi var — paylaşılan {@code SchedulerService.applySchemaPatches}'e
 * dokunulmadı. Kapı: {@code NocCallLogSchemaPatchTest} (H2 — kolon/indeks düşürülüp iki kez uygulanır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocCallLogSchemaPatch {

    static final String TABLE = "noc_call_log";

    /** Kolon → tür (hepsi NULLABLE). {@code id} tabloyla gelir; burada yalnız veri kolonları. */
    static final Map<String, String> COLUMNS = columns();

    static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_noc_call_log_alert ON noc_call_log(alert_id, contacted_at)",
            "CREATE INDEX IF NOT EXISTS idx_noc_call_log_team ON noc_call_log(team_id, contacted_at)");

    private final JdbcTemplate jdbc;

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alert_id", "BIGINT");
        m.put("team_id", "BIGINT");
        m.put("contacted_user_id", "BIGINT");
        m.put("contacted_name", "VARCHAR(200)");
        m.put("contacted_at", "VARCHAR(30)");
        m.put("channel", "VARCHAR(16)");
        m.put("outcome", "VARCHAR(20)");
        m.put("note", "VARCHAR(1000)");
        m.put("created_by", "VARCHAR(100)");
        m.put("created_by_name", "VARCHAR(200)");
        m.put("created_at", "VARCHAR(30)");
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("7/24 arama kaydı şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda EKLENEN kolon sayısı (0 = zaten güncel ya da tablo yok). */
    public int apply() {
        if (!tableExists()) {
            log.warn("7/24 arama kaydı tablosu ({}) yok — yama atlandı (varlık tabloyu oluşturmalıydı)", TABLE);
            return 0;
        }
        int added = 0;
        for (Map.Entry<String, String> c : COLUMNS.entrySet()) added += addColumn(c.getKey(), c.getValue());
        for (String ddl : INDEXES) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                log.warn("7/24 arama kaydı şema yaması (indeks) uygulanamadı: {} — {}", ddl, e.getMessage());
            }
        }
        if (added > 0) log.info("7/24 arama kaydı şema yaması: {} kolon eklendi", added);
        return added;
    }

    private int addColumn(String column, String type) {
        try {
            if (columnExists(column)) return 0;
            jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + type);
            return 1;
        } catch (Exception e) {
            log.warn("7/24 arama kaydı şema yaması uygulanamadı: {}.{} — {}", TABLE, column, e.getMessage());
            return 0;
        }
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
