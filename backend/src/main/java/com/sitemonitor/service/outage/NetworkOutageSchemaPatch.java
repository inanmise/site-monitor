package com.sitemonitor.service.outage;

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
 * {@code network_outage_events} yönetici postası sütunları (2026-10-21, çok pod — MP-01) — açılışta, İDEMPOTENT.
 *
 * <p>Sütunlar varlıktan ({@code NetworkOutageEvent}) ddl-auto ile de doğar; bu yama ona GÜVENMEDEN eksik sütunu NULLABLE
 * ekler (paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunmadan — {@code NocCallLogSchemaPatch} deseni). Her
 * ifade kendi try/catch'inde; ifade kilit beklemesi {@link SchemaDdlGuard} ile sınırlı. Sütun henüz yoksa
 * {@link CertNetworkOutageService} turu güvenli tarafa düşer (posta yok, karar yalnız turun sonucundan). Tekil indeks
 * (kısmi, ifade) buradan DEĞİL — eski çift açık satırlar önce temizlenmeli: {@link NetworkOutageOngoingDedupe}.
 * Kapı: {@code NetworkOutageSchemaPatchTest} (H2 — sütunlar düşürülüp iki kez uygulanır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkOutageSchemaPatch {

    static final String TABLE = "network_outage_events";

    /** Sütun → tür (hepsi NULLABLE). */
    static final Map<String, String> COLUMNS = columns();

    private final JdbcTemplate jdbc;

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alert_mail_status", "VARCHAR(255)");
        m.put("alert_mail_claimed_ms", "BIGINT");
        m.put("alert_mail_attempts", "INTEGER");
        m.put("resolved_mail_status", "VARCHAR(255)");
        m.put("resolved_mail_claimed_ms", "BIGINT");
        m.put("resolved_mail_attempts", "INTEGER");
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Ağ kesintisi olayı şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda EKLENEN sütun sayısı (0 = zaten güncel ya da tablo yok). */
    public int apply() {
        if (!tableExists()) {
            log.warn("Ağ kesintisi olayı tablosu ({}) yok — yama atlandı (varlık tabloyu oluşturmalıydı)", TABLE);
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
                log.warn("Ağ kesintisi olayı şema yaması uygulanamadı: {}.{} — {}", TABLE, c.getKey(), e.getMessage());
            }
        }
        if (added > 0) log.info("Ağ kesintisi olayı şema yaması: {} sütun eklendi", added);
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
