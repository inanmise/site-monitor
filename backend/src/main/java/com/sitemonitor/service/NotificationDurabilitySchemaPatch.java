package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Çok pod bildirim dayanıklılığı kolonları (2026-10-21, R1 paketi C2) — açılışta, İDEMPOTENT. Paylaşılan
 * {@code SchedulerService.applySchemaPatches}'e dokunulmaz (çok pod planı G4); {@code NocCallLogSchemaPatch} deseni.
 *
 * <ul>
 *   <li>{@code alert_events.resolution_notify} / {@code resolution_notify_at} — kalıcı çözüm bildirimi işareti (MP-07).</li>
 *   <li>{@code login_anomaly_incident.last_alert_attempt_at} — giriş anomalisi uyarı denemesi damgası (MP-10).</li>
 * </ul>
 * Kolonlar varlıklarda da tanımlı (ddl-auto ekler); bu yama ddl-auto'nun sessizce atladığı ortamları kapatır. Hepsi
 * NULLABLE, varsayılansız. Her ifade kendi try/catch'inde ({@link SchemaDdlGuard} kilit beklemesi sınırlı); biri düşerse
 * açılış durmaz. Kolon eksikken kod eski yola düşer (işaret yazılamazsa işaretsiz kapanış, deneme damgası okunamazsa
 * cooldown tek başına). Kapı: {@code NotificationDurabilitySchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDurabilitySchemaPatch {

    /** Tablo, kolon, tür (NULLABLE). */
    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("alert_events", "resolution_notify", "VARCHAR(16)"),
            new Col("alert_events", "resolution_notify_at", "VARCHAR(30)"),
            new Col("login_anomaly_incident", "last_alert_attempt_at", "VARCHAR(40)"));

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Bildirim dayanıklılığı şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
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
                log.warn("Bildirim dayanıklılığı şema yaması uygulanamadı: {}.{} — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (added > 0) log.info("Bildirim dayanıklılığı şema yaması: {} kolon eklendi", added);
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
