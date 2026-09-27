package com.sitemonitor.service.noc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 7/24 İzleme Ekibi (NOC) şema yamaları (2026-09-27) — açılışta {@code SchedulerService.applySchemaPatches}
 * çağırır; her koşu İDEMPOTENT (kolon/indeks varsa hiçbir şey yapılmaz).
 *
 * <p><b>Neden ddl-auto'ya bırakılmıyor:</b> dolu tabloya eklenen kolonlar ve tekil kısıtlar {@code ddl-auto=update}
 * ile SESSİZCE oluşmayabiliyor (proje tuzağı — anti-loop tekil kısıtı prod'da yoktu). Kolonların HEPSİ NULLABLE
 * (null = kapalı / varsayılan gruplar); NOT NULL eklenmez. Yeni tablolar (grup, arama listesi, teslim izi,
 * yapılandırma) varlıklarından doğar; tekil indeksleri burada AÇIKÇA da kurulur — tekilleştirme (alarm başına tek
 * NOC e-postası) ve "bir kişi listede bir kez" garantileri indekse dayanır.
 *
 * <p>Her ifade kendi try/catch'inde: biri düşerse açılış durmaz, kalanlar uygulanır ve günlüğe yazılır.
 * Kapı: {@code NocSchemaPatchesTest} (H2 — kolon düşürülüp iki kez uygulanır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocSchemaPatches {

    /** 7/24 alanlarını taşıyan on tablo (on izleme türü). */
    static final List<String> MONITOR_TABLES = List.of(
            "certificate_inventory", "ping_monitors", "http_monitors", "keyword_monitors", "page_monitors",
            "pagespeed_monitors", "scripted_monitors", "dns_monitors", "port_monitors", "domain_monitors");

    static final List<String> INDEXES = List.of(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_noc_delivery_key ON noc_deliveries(dedupe_key)",
            "CREATE INDEX IF NOT EXISTS idx_noc_delivery_alert ON noc_deliveries(alert_event_id)",
            "CREATE INDEX IF NOT EXISTS idx_noc_delivery_created ON noc_deliveries(created_at)",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_noc_call_team_user ON noc_team_call_list(team_id, user_id)",
            "CREATE INDEX IF NOT EXISTS idx_noc_call_team ON noc_team_call_list(team_id, position)");

    private final JdbcTemplate jdbc;

    /** @return bu koşuda EKLENEN kolon sayısı (0 = zaten güncel). */
    public int apply() {
        int added = 0;
        for (String t : MONITOR_TABLES) {
            added += addColumn(t, "noc_notify", "BOOLEAN");
            added += addColumn(t, "noc_group_ids", "VARCHAR(500)");
        }
        for (String ddl : INDEXES) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                log.warn("7/24 şema yaması (indeks) uygulanamadı: {} — {}", ddl, e.getMessage());
            }
        }
        if (added > 0) log.info("7/24 şema yaması: {} kolon eklendi", added);
        return added;
    }

    private int addColumn(String table, String column, String type) {
        try {
            if (columnExists(table, column)) return 0;
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            return 1;
        } catch (Exception e) {
            log.warn("7/24 şema yaması uygulanamadı: {}.{} — {}", table, column, e.getMessage());
            return 0;
        }
    }

    boolean columnExists(String table, String col) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?) AND lower(column_name)=lower(?)",
                Integer.class, table, col);
        return n != null && n > 0;
    }
}
