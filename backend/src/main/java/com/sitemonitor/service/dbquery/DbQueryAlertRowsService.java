package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbQueryAlertRows;
import com.sitemonitor.repository.DbQueryAlertRowsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Veritabanı sorgu alarmının ALARM ANINDAKİ sonuç satırları (2026-10-31, kullanıcı kararı: "mailde + alarm ayrıntısında").
 *
 * <p><b>Akış.</b> Zamanlayıcı her çalıştırmadan sonra o çalıştırmanın (motorun zaten maskelediği, sınırladığı) mail
 * satırlarını {@link #remember} ile BU POD'UN belleğine koyar — izleme başına tek, en güncel kayıt. Alarm bildirimi
 * gönderilirken (açılış, eskalasyon, günlük yeniden uyarı, elle yeniden gönderim) {@code EscalationService} alarm olayının
 * kimliğiyle {@link #capture} çağırır: bellekteki satırlar {@code dbquery_alert_rows}'a yazılır (alarm başına TEK satır, son
 * yazılan kalır) ve maile aynı satırlar konur. Bellekte yoksa (başka pod, yeniden başlatma) saklanmış son satırlar kullanılır.
 *
 * <p><b>Satır verisi başka hiçbir yere yazılmaz:</b> tarama öğesinin bağlamına KONMAZ (bağlam alarm olayına
 * {@code context_json} olarak, teyit zincirleri {@code monitor_confirmations}'a yazılır), elle değerlendirme gelen kutusuna
 * giden ham sonuca KONMAZ, kontrol satırına, bildirim günlüğüne ({@code notification_logs} gövdesi satırsız kopyadır),
 * denetime ve uygulama günlüğüne yazılmaz.
 *
 * <p>Sınırlar (sözleşme §1): ≤ {@link #MAX_ROWS} satır × {@link #MAX_COLUMNS} kolon, hücre ≤ {@link #MAX_CELL} karakter.
 * Bellek sınırlıdır: izleme başına bir giriş, en çok {@link #MAX_REMEMBERED} giriş.
 */
@Slf4j
@Service
public class DbQueryAlertRowsService {

    /** Gönderim bağlamı anahtarı — YALNIZ gönderilen mailin geçici bağlamı (kalıcılaşmaz, whitelist'te değil). */
    public static final String CTX_ROWS = "dbquery_rows";
    /** Günlük kopyasının bağlamı: maile kaç satır kondu (gövdede "satırlar bu kayıtta saklanmaz" yazılır). */
    public static final String CTX_ROWS_WITHHELD = "dbquery_rows_withheld";

    public static final int MAX_ROWS = 50, MAX_COLUMNS = 20, MAX_CELL = 200, MAX_REMEMBERED = 10_000;
    /** Maskelenen kolonun yazılan değeri. */
    public static final String MASK = "••••";

    /**
     * Alarm anındaki satırlar.
     *
     * @param capturedAt     satırların okunduğu çalıştırmanın zamanı (UTC ISO)
     * @param columns        kolon adları (≤ 20)
     * @param rows           satırlar (her hücre metin ya da null; maskeli kolon "••••")
     * @param masked         maskelenen kolon adları
     * @param rowCount       sorgunun döndürdüğü toplam satır (saklanandan fazla olabilir)
     * @param rowCountCapped satır sayısı okuma sınırında kesildi ("≥ N")
     * @param truncated      saklanan satır / kolon sınırında kesildi
     */
    public record Rows(String capturedAt, List<String> columns, List<List<String>> rows, List<String> masked,
                       Integer rowCount, boolean rowCountCapped, boolean truncated) {
        public boolean isEmpty() { return columns == null || columns.isEmpty(); }
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DbQueryAlertRowsRepository repo;
    private final ConcurrentHashMap<Long, Rows> latest = new ConcurrentHashMap<>();

    public DbQueryAlertRowsService(DbQueryAlertRowsRepository repo) {
        this.repo = repo;
    }

    /**
     * Bir çalıştırmanın mail satırlarını belleğe alır (izleme başına en güncel). Satır yoksa (hata, mail_include_rows
     * kapalı, sonuç boş) izlemenin girişi SİLİNİR — eski çalıştırmanın satırları yeni bir alarma taşınmasın.
     */
    public void remember(Long monitorId, DbQueryCheckerService.Result r) {
        if (monitorId == null) return;
        Rows rows = r == null ? null : of(r);
        if (rows == null || rows.isEmpty()) {
            latest.remove(monitorId);
            return;
        }
        if (latest.size() >= MAX_REMEMBERED && !latest.containsKey(monitorId)) latest.clear();   // savunma: sınırsız büyümesin
        latest.put(monitorId, rows);
    }

    /** İzleme silindi / satırları geçersiz — bellekteki giriş düşer. */
    public void forget(Long monitorId) {
        if (monitorId != null) latest.remove(monitorId);
    }

    /** Bu pod'un bellekteki son satırları (yoksa boş). */
    public Optional<Rows> latest(Long monitorId) {
        return monitorId == null ? Optional.empty() : Optional.ofNullable(latest.get(monitorId));
    }

    /**
     * Alarm bildirimi anında satırları alarmla saklar ve döndürür. Bellekteki satırlar saklanmış olandan YENİYSE yazılır;
     * değilse (ya da bellekte yoksa) saklanmış satırlar döner. İkisi de yoksa boş. Hata fırlatmaz — bildirim satırsız gider.
     */
    public Optional<Rows> capture(Long alertEventId, Long monitorId) {
        if (alertEventId == null) return Optional.empty();
        try {
            Rows mem = monitorId == null ? null : latest.get(monitorId);
            Optional<DbQueryAlertRows> stored = repo.findByAlertEventId(alertEventId);
            if (mem == null) return stored.map(DbQueryAlertRowsService::toRows);
            if (stored.isPresent() && !newer(mem.capturedAt(), stored.get().getCapturedAt())) {
                return stored.map(DbQueryAlertRowsService::toRows);
            }
            write(stored.orElse(null), alertEventId, monitorId, mem);
            return Optional.of(mem);
        } catch (Exception e) {
            log.warn("Sorgu alarmı satırları saklanamadı (olay {}): {}", alertEventId, e.getClass().getSimpleName());
            return monitorId == null ? Optional.empty() : Optional.ofNullable(latest.get(monitorId));
        }
    }

    /** Alarmla saklanmış son satırlar (alarm ayrıntısı ucu). */
    public Optional<Rows> stored(Long alertEventId) {
        if (alertEventId == null) return Optional.empty();
        return repo.findByAlertEventId(alertEventId).map(DbQueryAlertRowsService::toRows);
    }

    private void write(DbQueryAlertRows existing, Long alertEventId, Long monitorId, Rows rows) {
        DbQueryAlertRows e = existing != null ? existing : new DbQueryAlertRows();
        fill(e, alertEventId, monitorId, rows);
        try {
            repo.save(e);
        } catch (DataIntegrityViolationException race) {
            // Aynı alarma iki pod / iki gönderim aynı anda yazdı (uk_dbqar_alert): kazananın satırı güncellenir.
            repo.findByAlertEventId(alertEventId).ifPresent(winner -> {
                fill(winner, alertEventId, monitorId, rows);
                repo.save(winner);
            });
        }
    }

    private static void fill(DbQueryAlertRows e, Long alertEventId, Long monitorId, Rows rows) {
        e.setAlertEventId(alertEventId);
        e.setMonitorId(monitorId);
        e.setCapturedAt(rows.capturedAt() != null ? rows.capturedAt() : ISO.format(Instant.now()));
        e.setColumnsJson(json(rows.columns()));
        e.setRowsJson(json(rows.rows()));
        e.setMaskedJson(json(rows.masked()));
        e.setRowCount(rows.rowCount());
        e.setRowCountCapped(rows.rowCountCapped());
        e.setTruncated(rows.truncated());
        e.setUpdatedAt(ISO.format(Instant.now()));
    }

    /** {@code a} {@code b}'den sonra mı (UTC ISO sözlük sırası = zaman sırası; boş b → evet). */
    static boolean newer(String a, String b) {
        if (b == null || b.isBlank()) return true;
        if (a == null || a.isBlank()) return false;
        return a.compareTo(b) > 0;
    }

    /** Motor sonucundan sınırlı, kopyalanmış satırlar (motor zaten sınırlar ve maskeler — burada savunma amaçlı yeniden). */
    static Rows of(DbQueryCheckerService.Result r) {
        List<String> cols = r.mailColumns() == null ? List.of() : r.mailColumns();
        List<List<String>> src = r.mailRows() == null ? List.of() : r.mailRows();
        if (cols.isEmpty()) return null;
        boolean truncated = r.rowsTruncated() || cols.size() > MAX_COLUMNS || src.size() > MAX_ROWS;
        List<String> outCols = new ArrayList<>();
        for (int i = 0; i < Math.min(cols.size(), MAX_COLUMNS); i++) {
            String name = cell(cols.get(i));
            outCols.add(name == null ? "" : name);
        }
        List<List<String>> outRows = new ArrayList<>();
        for (int i = 0; i < Math.min(src.size(), MAX_ROWS); i++) {
            List<String> row = src.get(i) == null ? List.of() : src.get(i);
            List<String> o = new ArrayList<>(outCols.size());
            for (int c = 0; c < outCols.size(); c++) {
                String v = c < row.size() ? row.get(c) : null;
                if (v != null && v.length() > MAX_CELL) truncated = true;
                o.add(cell(v));
            }
            outRows.add(java.util.Collections.unmodifiableList(o));
        }
        List<String> masked = new ArrayList<>();
        if (r.maskedColumns() != null) for (String m : r.maskedColumns()) if (m != null) masked.add(cell(m));
        return new Rows(r.checkedAt(), List.copyOf(outCols), List.copyOf(outRows), List.copyOf(masked),
                r.rowCount(), r.rowCountCapped(), truncated);
    }

    private static String cell(String v) {
        if (v == null) return null;
        return v.length() > MAX_CELL ? v.substring(0, MAX_CELL - 1) + "…" : v;
    }

    private static Rows toRows(DbQueryAlertRows e) {
        List<String> cols = readList(e.getColumnsJson());
        List<List<String>> rows = readRows(e.getRowsJson());
        return new Rows(e.getCapturedAt(), cols, rows, readList(e.getMaskedJson()), e.getRowCount(),
                Boolean.TRUE.equals(e.getRowCountCapped()), Boolean.TRUE.equals(e.getTruncated()));
    }

    private static String json(Object v) {
        try {
            return v == null ? null : JSON.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> l = JSON.readValue(json, new TypeReference<List<String>>() {});
            return l == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(l));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<List<String>> readRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<List<String>> l = JSON.readValue(json, new TypeReference<List<List<String>>>() {});
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }
}
