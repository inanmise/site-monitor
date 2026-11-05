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
 * Anahtar kelime çoklu kural + içerik kontrolleri kolonları (kw sözleşmesi §3, 2026-11-05) — açılışta, İDEMPOTENT
 * ({@code ReportIntegritySchemaPatch} deseni; paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz).
 *
 * <ul>
 *   <li>{@code keyword_monitors}: {@code rules_json}, {@code rule_logic}, {@code min_bytes}, {@code max_bytes},
 *       {@code change_detect}, {@code change_mask}, {@code change_selector}.</li>
 *   <li>{@code keyword_results}: {@code rule_results_json}, {@code breach_level}, {@code content_fingerprint},
 *       {@code content_changed}, {@code change_summary}, {@code change_note} (kw son inceleme O-3 / D-7).</li>
 * </ul>
 * Kolonlar varlıklarda da tanımlı (ddl-auto ekler); bu yama ddl-auto'nun sessizce atladığı ortamları kapatır. Hepsi NULLABLE,
 * varsayılansız (NULL = eski satır: tek kelime, boyut / değişim kapalı, kural sonucu yok). {@code keyword_results} büyük bir
 * tablodur: NULLABLE + varsayılansız ekleme yalnız katalog değişikliğidir; kilit beklemesi {@link SchemaDdlGuard} ile sınırlı.
 * Her ifade kendi try/catch'inde; biri düşerse açılış durmaz. Yeni tablo {@code keyword_change_baselines} ddl-auto ile doğar.
 * Kapı: {@code KeywordRuleSchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordRuleSchemaPatch {

    /** Tablo, kolon, tür (NULLABLE). */
    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("keyword_monitors", "rules_json", "TEXT"),
            new Col("keyword_monitors", "rule_logic", "VARCHAR(5)"),
            new Col("keyword_monitors", "min_bytes", "INTEGER"),
            new Col("keyword_monitors", "max_bytes", "INTEGER"),
            new Col("keyword_monitors", "change_detect", "BOOLEAN"),
            new Col("keyword_monitors", "change_mask", "VARCHAR(40)"),
            new Col("keyword_monitors", "change_selector", "VARCHAR(300)"),
            new Col("keyword_results", "rule_results_json", "TEXT"),
            new Col("keyword_results", "breach_level", "VARCHAR(10)"),
            new Col("keyword_results", "content_fingerprint", "VARCHAR(64)"),
            new Col("keyword_results", "content_changed", "BOOLEAN"),
            new Col("keyword_results", "change_summary", "TEXT"),
            new Col("keyword_results", "change_note", "VARCHAR(32)"));

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Anahtar kelime kural şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
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
                log.warn("Anahtar kelime kural şema yaması uygulanamadı: {}.{} — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (added > 0) log.info("Anahtar kelime kural şema yaması: {} kolon eklendi", added);
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
