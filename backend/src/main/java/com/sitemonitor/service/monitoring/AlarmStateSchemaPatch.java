package com.sitemonitor.service.monitoring;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Alarm durumunun liderlik devrinden sağ çıkması (2026-10-21, çok pod R1-B) için şema yaması — açılışta, İDEMPOTENT.
 *
 * <p>Varlıklar ({@code DnsRecord.changeState}, {@code DomainCheck.changeState}, {@code MonitorEvalInbox}) ddl-auto ile
 * kolonu / tabloyu kurar; bu yama ona GÜVENMEDEN aynısını garanti eder ({@code noc.NocCallLogSchemaPatch} deseni):
 * <ul>
 *   <li>{@code dns_records.change_state}, {@code domain_checks.change_state} — VARCHAR(16), NULL'lanabilir (eski satır =
 *       NULL = yok sayılır). Varsayılansız NULL kolon PostgreSQL'de yalnız katalog işidir; kilit beklemesi
 *       {@link SchemaDdlGuard} ile sınırlı.</li>
 *   <li>{@code monitor_eval_inbox} + {@code idx_mei_claim} — küçük kuyruk tablosu.</li>
 * </ul>
 * Her ifade kendi try/catch'inde: biri düşerse açılış durmaz. Kod kolon / tablo yokken de çalışır (ilk saniyeler): okuma
 * hatası eski davranışa düşer. Kapı: {@code AlarmStateSchemaPatchTest} (H2, iki kez uygulanır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmStateSchemaPatch {

    static final List<String> CHANGE_STATE_TABLES = List.of("dns_records", "domain_checks");

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Alarm durumu şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return bu koşuda eklenen kolon sayısı. */
    public int apply() {
        SchemaDdlGuard guard = new SchemaDdlGuard(jdbc);
        int added = 0;
        for (String table : CHANGE_STATE_TABLES) {
            try {
                if (!tableExists(table) || columnExists(table, ChangeStates.COLUMN)) continue;
                guard.executeBounded("ALTER TABLE " + table + " ADD COLUMN " + ChangeStates.COLUMN + " VARCHAR(16)", false);
                added++;
            } catch (Exception e) {
                log.warn("Alarm durumu şema yaması uygulanamadı: {}.{} — {}", table, ChangeStates.COLUMN, e.getMessage());
            }
        }
        try {
            if (!tableExists(ManualEvalInbox.TABLE)) jdbc.execute(ManualEvalInbox.DDL);
        } catch (Exception e) {
            log.warn("Elle kontrol gelen kutusu tablosu kurulamadı: {}", e.getMessage());
        }
        try {
            if (tableExists(ManualEvalInbox.TABLE) && !guard.alreadyInPlace(ManualEvalInbox.INDEX_DDL)) {
                guard.executeBounded(ManualEvalInbox.INDEX_DDL, false);
            }
        } catch (Exception e) {
            log.warn("Elle kontrol gelen kutusu dizini kurulamadı: {}", e.getMessage());
        }
        if (added > 0) log.info("Alarm durumu şema yaması: {} kolon eklendi", added);
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
