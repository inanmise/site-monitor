package com.sitemonitor.service.page.scan.budget;

import com.sitemonitor.model.PageMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kaynak analizi bütçesinin okuma / yazma ve analizleri işaretleme yeri (2026-11, F1).
 *
 * <p><b>Yazım</b> ({@link #save}): TEK kolonu koşullu günceller — okunan JSON metni hâlâ yerindeyse yazar, değilse yeniden okuyup
 * gönderilen değişiklikleri GÜNCEL bütçeye uygular (en çok {@value #CAS_TRIES} tur). İki kişi aynı anda farklı sınırları
 * değiştirirse ikisi de kalır; izleme düzenlemesi kolonu hiç yazmaz ({@code PageMonitor.resourceBudgetJson}
 * {@code updatable=false}).
 *
 * <p><b>İşaretleme</b> ({@link #annotate}, {@link #evaluateOne}): bütçe tanımlıysa analizlerin ölçülen değerleri TEK sorguyla
 * okunur ({@link #SQL_FACTS}). {@code js_bytes} / {@code security_score} kolonları (algoritma 3) eski bir veritabanında henüz
 * yoksa (ilk açılış saniyeleri, yarım kalmış yama) sorgu bu iki kolon olmadan yinelenir ve o sınırlar "bilinmiyor" kalır —
 * okuma asla çökmez, asla "geçti" demez. Okunamayan durum {@code unknown} + {@code budget_unreadable}.
 */
@Slf4j
@Service
public class ResourceBudgetService {

    static final int CAS_TRIES = 3;
    /** Tek sorgudaki en çok tarama kimliği (liste uç sınırıyla aynı). */
    static final int IDS_PER_QUERY = 200;

    static final String SQL_READ = "SELECT resource_budget_json FROM page_monitors WHERE id = ?";
    static final String SQL_CAS_NULL = "UPDATE page_monitors SET resource_budget_json = ? WHERE id = ? AND resource_budget_json IS NULL";
    static final String SQL_CAS = "UPDATE page_monitors SET resource_budget_json = ? WHERE id = ? AND resource_budget_json = ?";
    static final String FACTS_COLS = "id, status, capped, page_bytes, request_count, js_bytes, image_bytes, load_ms, grade, security_score";
    static final String FACTS_COLS_LEGACY = "id, status, capped, page_bytes, request_count, image_bytes, load_ms, grade";
    static final String SQL_FACTS = "SELECT " + FACTS_COLS + " FROM page_resource_scans WHERE monitor_id = ? AND id IN (%s)";
    static final String SQL_FACTS_LEGACY = "SELECT " + FACTS_COLS_LEGACY + " FROM page_resource_scans WHERE monitor_id = ? AND id IN (%s)";

    /** Kolon eksikliği uyarısının en sık aralığı (yarım kalmış yama görünür olsun, günlük boğulmasın). */
    private static final long WARN_EVERY_MS = 3_600_000L;
    private final AtomicLong lastLegacyWarn = new AtomicLong();

    private final JdbcTemplate jdbc;

    public ResourceBudgetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** İzlemenin bütçesi (varlıktan, ek sorgu yok). */
    public static ResourceBudget budgetOf(PageMonitor m) {
        return m == null ? ResourceBudget.EMPTY : ResourceBudget.parse(m.getResourceBudgetJson());
    }

    // ── Yazım ──────────────────────────────────────────────────────────────────────────────

    /** Kaydetme sonucu: SAVED (yazıldı), UNCHANGED (değişiklik yok — yazılmadı), NOT_FOUND (izleme silindi), CONFLICT. */
    public enum Outcome { SAVED, UNCHANGED, NOT_FOUND, CONFLICT }

    public record Save(Outcome outcome, ResourceBudget before, ResourceBudget after) {}

    /**
     * Gönderilen alanları uygular ve yazar. Doğrulama İLK iş: geçersiz alan hiçbir şey yazmadan 400 olur
     * ({@link ResourceBudget#withChanges}).
     *
     * @param seenJson izlemenin okunduğu andaki kolon değeri (varlıktan)
     */
    public Save save(long monitorId, String seenJson, Map<String, Object> body) {
        ResourceBudget before = ResourceBudget.parse(seenJson);
        ResourceBudget after = before.withChanges(body);   // geçersiz alan → FieldValidationException (yazım yok)
        String seen = seenJson;
        for (int i = 0; i < CAS_TRIES; i++) {
            if (after.equals(before)) return new Save(Outcome.UNCHANGED, before, before);
            int n = seen == null
                    ? jdbc.update(SQL_CAS_NULL, after.toJson(), monitorId)
                    : jdbc.update(SQL_CAS, after.toJson(), monitorId, seen);
            if (n > 0) return new Save(Outcome.SAVED, before, after);
            // Arada biri yazdı: güncel değeri oku, gönderilen değişiklikleri ONA uygula
            List<String> cur = jdbc.query(SQL_READ, (rs, r) -> rs.getString(1), monitorId);
            if (cur.isEmpty()) return new Save(Outcome.NOT_FOUND, before, before);
            seen = cur.get(0);
            before = ResourceBudget.parse(seen);
            after = before.withChanges(body);
        }
        log.warn("Kaynak analizi bütçesi {} denemede yazılamadı (izleme {}) — eş zamanlı yazımlar", CAS_TRIES, monitorId);
        return new Save(Outcome.CONFLICT, before, before);
    }

    // ── İşaretleme ─────────────────────────────────────────────────────────────────────────

    /**
     * Liste satırlarına {@code budget_status} ({@code ok|exceeded|none|unknown}) + {@code budget_exceeded} (aşılan sınır sayısı)
     * yazar. Bütçe yoksa sorgu YAPILMAZ ({@code none}, 0). Okuma hatası satırları {@code unknown} bırakır (sayı null).
     *
     * @return okuma başarılıysa true (hata → false, satırlar unknown)
     */
    public boolean annotate(long monitorId, ResourceBudget budget, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return true;
        if (budget == null || !budget.defined()) {
            for (Map<String, Object> row : rows) {
                row.put("budget_status", ResourceBudgetEvaluator.STATUS_NONE);
                row.put("budget_exceeded", 0);
            }
            return true;
        }
        Map<Long, ResourceBudgetEvaluator.Facts> facts;
        try {
            List<Long> ids = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) if (row.get("id") instanceof Number n) ids.add(n.longValue());
            facts = facts(monitorId, ids);
        } catch (RuntimeException e) {
            log.warn("Kaynak analizi bütçe değerleri okunamadı (izleme {}): {} — satırlar 'bilinmiyor'", monitorId,
                    e.getClass().getSimpleName());
            for (Map<String, Object> row : rows) {
                row.put("budget_status", ResourceBudgetEvaluator.STATUS_UNKNOWN);
                row.put("budget_exceeded", null);
            }
            return false;
        }
        for (Map<String, Object> row : rows) {
            ResourceBudgetEvaluator.Facts f = row.get("id") instanceof Number n ? facts.get(n.longValue()) : null;
            ResourceBudgetEvaluator.Result r = ResourceBudgetEvaluator.evaluate(budget, f);
            row.put("budget_status", r.status());
            row.put("budget_exceeded", r.exceeded());
        }
        return true;
    }

    /** Tek analizin bütçe sonucu ({@code budget} alanı); okunamazsa {@code unknown} + {@code unreadable:true}. */
    public Map<String, Object> evaluateOne(long monitorId, ResourceBudget budget, long scanId) {
        if (budget == null || !budget.defined()) return ResourceBudgetEvaluator.none().toMap();
        try {
            ResourceBudgetEvaluator.Facts f = facts(monitorId, List.of(scanId)).get(scanId);
            return ResourceBudgetEvaluator.evaluate(budget, f).toMap();
        } catch (RuntimeException e) {
            log.warn("Kaynak analizi bütçe değerleri okunamadı (izleme {}, analiz {}): {}", monitorId, scanId,
                    e.getClass().getSimpleName());
            Map<String, Object> m = ResourceBudgetEvaluator.evaluate(budget, null).toMap();
            m.put("status", ResourceBudgetEvaluator.STATUS_UNKNOWN);
            m.put("exceeded", null);
            m.put("unreadable", true);
            return m;
        }
    }

    /** Analizlerin ölçülen değerleri (kimlik → değerler); en çok {@value #IDS_PER_QUERY} kimlik bir sorguda. */
    Map<Long, ResourceBudgetEvaluator.Facts> facts(long monitorId, Collection<Long> ids) {
        Map<Long, ResourceBudgetEvaluator.Facts> out = new HashMap<>();
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int from = 0; from < unique.size(); from += IDS_PER_QUERY) {
            List<Long> chunk = unique.subList(from, Math.min(unique.size(), from + IDS_PER_QUERY));
            StringJoiner marks = new StringJoiner(", ");
            Object[] args = new Object[chunk.size() + 1];
            args[0] = monitorId;
            for (int i = 0; i < chunk.size(); i++) {
                marks.add("?");
                args[i + 1] = chunk.get(i);
            }
            List<Map.Entry<Long, ResourceBudgetEvaluator.Facts>> rows;
            try {
                rows = jdbc.query(String.format(SQL_FACTS, marks),
                        (rs, i) -> Map.entry(rs.getLong("id"), factsRow(rs, true)), args);
            } catch (BadSqlGrammarException e) {
                // js_bytes / security_score henüz yok (eski şema) — o iki sınır "bilinmiyor" kalır
                warnLegacy(e);
                rows = jdbc.query(String.format(SQL_FACTS_LEGACY, marks),
                        (rs, i) -> Map.entry(rs.getLong("id"), factsRow(rs, false)), args);
            }
            for (Map.Entry<Long, ResourceBudgetEvaluator.Facts> e : rows) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private void warnLegacy(BadSqlGrammarException e) {
        long now = System.currentTimeMillis();
        long last = lastLegacyWarn.get();
        if (now - last >= WARN_EVERY_MS && lastLegacyWarn.compareAndSet(last, now)) {
            log.warn("Kaynak analizi özet kolonları (js_bytes / security_score) okunamadı — eski şema ya da yama eksik; "
                    + "bu sınırlar 'bilinmiyor' gösterilir: {}", e.getClass().getSimpleName());
        }
    }

    private static ResourceBudgetEvaluator.Facts factsRow(ResultSet rs, boolean deep) throws SQLException {
        boolean capped = rs.getBoolean("capped");
        if (rs.wasNull()) capped = false;
        return new ResourceBudgetEvaluator.Facts(
                rs.getString("status"), capped,
                longOf(rs, "page_bytes"), longOf(rs, "request_count"),
                deep ? longOf(rs, "js_bytes") : null,
                longOf(rs, "image_bytes"), longOf(rs, "load_ms"), longOf(rs, "grade"),
                deep ? longOf(rs, "security_score") : null);
    }

    /** Sayısal kolon (tamsayı / ondalık türünden bağımsız); NULL → null. */
    static Long longOf(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v instanceof Number n ? n.longValue() : null;
    }
}
