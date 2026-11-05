package com.sitemonitor.service.keyword;

import com.sitemonitor.service.schema.SchemaDdlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Anahtar kelime izleme zenginleştirmesinin kolonları (kw sözleşmesi §3.1–3.2, 2026-11-05) — açılışta, İDEMPOTENT. Paylaşılan
 * {@code SchedulerService.applySchemaPatches}'e dokunulmaz ({@code ReportIntegritySchemaPatch} deseni).
 *
 * <p>Hepsi NULLABLE, varsayılansız: NULL = bugünkü davranış (ham HTML, GET, varsayılan UA, durum kodu karara girmez, yönlendirme
 * takip edilir, standart posta konusu). Kolonlar varlıklarda da tanımlı (ddl-auto ekler); bu yama ddl-auto'nun sessizce atladığı
 * ortamları kapatır. Her ifade kendi try/catch'inde ({@link SchemaDdlGuard} kilit beklemesi sınırlı); biri düşerse açılış durmaz.
 * Kapı: {@code KeywordSchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSchemaPatch {

    /** Tablo, kolon, tür (NULLABLE). */
    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("keyword_monitors", "match_scope", "VARCHAR(10)"),
            new Col("keyword_monitors", "normalize_ws", "BOOLEAN"),
            new Col("keyword_monitors", "method", "VARCHAR(10)"),
            new Col("keyword_monitors", "request_body", "TEXT"),
            new Col("keyword_monitors", "request_content_type", "VARCHAR(100)"),
            new Col("keyword_monitors", "basic_auth_user", "VARCHAR(255)"),
            new Col("keyword_monitors", "basic_auth_pass_enc", "TEXT"),
            new Col("keyword_monitors", "user_agent_mode", "VARCHAR(10)"),
            new Col("keyword_monitors", "user_agent", "VARCHAR(300)"),
            new Col("keyword_monitors", "expected_status", "VARCHAR(100)"),
            new Col("keyword_monitors", "expected_final_url", "VARCHAR(2000)"),
            new Col("keyword_monitors", "follow_redirects", "BOOLEAN"),
            new Col("keyword_monitors", "mail_subject_template", "VARCHAR(200)"),
            new Col("keyword_results", "decoded_charset", "VARCHAR(60)"),
            new Col("keyword_results", "match_scope", "VARCHAR(10)"));

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Anahtar kelime izleme şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
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
                log.warn("Anahtar kelime izleme şema yaması uygulanamadı: {}.{} — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (added > 0) log.info("Anahtar kelime izleme şema yaması: {} kolon eklendi", added);
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
