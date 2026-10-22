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
 * Çok pod R2a paketi D1 kolonları (2026-10-22: rapor / bildirim teslim yolları) — açılışta, İDEMPOTENT. Paylaşılan
 * {@code SchedulerService.applySchemaPatches}'e dokunulmaz (çok pod planı G4); {@link NotificationDurabilitySchemaPatch} deseni.
 *
 * <ul>
 *   <li>{@code tech_error_reminders.inflight_at} — hatırlatma talebinin "sürüyor" damgası (MP-41: talep ile karar arasında
 *       ölen pod'un hatırlatması kaybolmasın).</li>
 *   <li>{@code tls_grade_changes.notify_attempts} — TLS notu düşüş bildiriminin yeniden deneme sayacı (MP-43: pod belleğinde
 *       değil).</li>
 *   <li>{@code audit_log.seq_locked} — denetim satırı zincir kilidi altında yazıldı (MP-38: {@code ux_audit_seq} kısmi tekil
 *       indeksinin kapsamı; eski kodun satırları NULL kalır). Büyük tablo: NULLABLE, varsayılansız ekleme yalnız katalog
 *       değişikliğidir; kilit beklemesi {@link SchemaDdlGuard} ile sınırlı.</li>
 *   <li>{@code executive_summary_reports|executive_summary_team_reports.send_started_claim|send_trace_name} — yönetici özeti
 *       postasının "gönderim başladı" işareti ve iz adı (inceleme REP-1: yarıda kalan gönderim en çok bir kez).</li>
 * </ul>
 * Kolonlar varlıklarda da tanımlı (ddl-auto ekler); bu yama ddl-auto'nun sessizce atladığı ortamları kapatır. Hepsi NULLABLE,
 * varsayılansız (NULL = eski satır: damga yok / sayaç 0). Her ifade kendi try/catch'inde ({@link SchemaDdlGuard} kilit
 * beklemesi sınırlı); biri düşerse açılış durmaz. Kapı: {@code ReportIntegritySchemaPatchTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportIntegritySchemaPatch {

    /** Tablo, kolon, tür (NULLABLE). */
    record Col(String table, String column, String type) { }

    static final List<Col> COLUMNS = List.of(
            new Col("tech_error_reminders", "inflight_at", "VARCHAR(32)"),
            new Col("tls_grade_changes", "notify_attempts", "INTEGER"),
            new Col("audit_log", "seq_locked", "BOOLEAN"),
            new Col("executive_summary_reports", "send_started_claim", "VARCHAR(40)"),
            new Col("executive_summary_reports", "send_trace_name", "VARCHAR(200)"),
            new Col("executive_summary_team_reports", "send_started_claim", "VARCHAR(40)"),
            new Col("executive_summary_team_reports", "send_trace_name", "VARCHAR(200)"));

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Rapor teslim yolu şema yaması uygulanamadı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
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
                log.warn("Rapor teslim yolu şema yaması uygulanamadı: {}.{} — {}", c.table(), c.column(), e.getMessage());
            }
        }
        if (added > 0) log.info("Rapor teslim yolu şema yaması: {} kolon eklendi", added);
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
