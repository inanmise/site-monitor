package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbQueryAlertRows;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryAlertRowsRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Veritabanı sorgu alarmının ALARM ANINDAKİ sonuç satırları (2026-10-31, kullanıcı kararı: "mailde + alarm ayrıntısında").
 *
 * <p><b>Akış.</b> Zamanlayıcı her çalıştırmadan sonra o çalıştırmanın (motorun zaten maskelediği, sınırladığı) mail
 * satırlarını {@link #remember} ile BU POD'UN belleğine koyar — izleme başına tek, en güncel kayıt; YALNIZ satırları bir
 * alarmda kullanılabilecek çalıştırmalar (kural ihlali ya da yavaş sorgu) tutulur, diğer çalıştırma girişi siler. Alarm bildirimi
 * gönderilirken (açılış, eskalasyon, günlük yeniden uyarı, elle yeniden gönderim) {@code EscalationService} alarm olayının
 * kimliğiyle {@link #capture} çağırır: bellekteki satırlar {@code dbquery_alert_rows}'a yazılır (alarm başına TEK satır, son
 * yazılan kalır) ve maile aynı satırlar konur. Bellekte yoksa (başka pod, yeniden başlatma) saklanmış son satırlar kullanılır.
 *
 * <p><b>Satır verisi başka hiçbir yere yazılmaz:</b> tarama öğesinin bağlamına KONMAZ (bağlam alarm olayına
 * {@code context_json} olarak, teyit zincirleri {@code monitor_confirmations}'a yazılır), elle değerlendirme gelen kutusuna
 * giden ham sonuca KONMAZ, kontrol satırına, bildirim günlüğüne ({@code notification_logs} gövdesi satırsız kopyadır),
 * denetime ve uygulama günlüğüne yazılmaz.
 *
 * <p><b>Güncel ayar gönderim anında (2026-10-31 inceleme D2).</b> Satırlar çalıştırma anının maskesiyle tutulur; alarm
 * postası / ayrıntısı İZLEMENİN O ANKİ ayarını uygular: "satırları maile ekle" kapatılmışsa satır dönmez (yazılmaz), sonradan
 * maskelenen kolonun hücreleri "••••" olur, mail kolonlarından çıkarılan kolon düşer (ikinci tur D-2; saklı kopya da aynı
 * biçimde güncellenir). İzleme okunamazsa satır DÖNMEZ (ayar doğrulanamadan veri gönderilmez); izleme silinmişse saklı
 * satırlar alındıkları maskeyle kalır.
 *
 * <p><b>Belleği yalnız ZAMANLANMIŞ çalıştırma değiştirir (ikinci tur D-3).</b> Zamanlayıcı {@link #remember}'ı yalnız
 * zamanlanmış çalıştırmadan sonra çağırır: elle kontrolün ya da teyit / kurtarma yeniden kontrolünün sağlıklı sonucu, teyit
 * sürerken açılış postasının satırlarını silemez.
 *
 * <p>Sınırlar (sözleşme §1): ≤ {@link #MAX_ROWS} satır × {@link #MAX_COLUMNS} kolon, hücre ≤ {@link #MAX_CELL} karakter.
 * Bellek sınırlıdır (2026-10-31 inceleme D1): izleme başına bir giriş, en çok {@link #MAX_REMEMBERED} giriş ve toplam
 * {@link #MAX_REMEMBERED_CHARS} karakter (aşılınca en eski girişler düşer); giriş {@link #MAX_AGE_MS} ms sonra düşer — silinen
 * izlemenin girişini {@link #forget} çağrılmayan pod'lar da tutmaz.
 */
@Slf4j
@Service
public class DbQueryAlertRowsService {

    /** Gönderim bağlamı anahtarı — YALNIZ gönderilen mailin geçici bağlamı (kalıcılaşmaz, whitelist'te değil). */
    public static final String CTX_ROWS = "dbquery_rows";
    /** Günlük kopyasının bağlamı: maile kaç satır kondu (gövdede "satırlar bu kayıtta saklanmaz" yazılır). */
    public static final String CTX_ROWS_WITHHELD = "dbquery_rows_withheld";

    public static final int MAX_ROWS = 50, MAX_COLUMNS = 20, MAX_CELL = 200, MAX_REMEMBERED = 10_000;
    /** Bellekteki tüm girişlerin toplam karakter bütçesi (≈ 8–16 MB) — aşılınca en eski girişler düşer. */
    public static final long MAX_REMEMBERED_CHARS = 8_000_000L;
    /** Bellek girişinin ömrü: bu süreden eski giriş kullanılmaz ve silinir (saklı satırlar alarmla zaten durur). */
    public static final long MAX_AGE_MS = 6L * 3600 * 1000;
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
    /** İzlemenin güncel ayarı (D2); yoksa (el yapımı nesne / test) ayar uygulanmaz — çalıştırma anının maskesi geçerli. */
    private DbQueryMonitorRepository monitors;

    /** Bellek girişi: satırlar, karakter ağırlığı, belleğe alınma anı. Erişim {@code this} kilidiyle. */
    private record Entry(Rows rows, long chars, long atMs) {}

    /** Ekleme sırası korunur (yeniden ekleme sona taşır) — bütçe aşımında en eski girişler düşer. */
    private final LinkedHashMap<Long, Entry> latest = new LinkedHashMap<>();
    private long totalChars;
    private volatile LongSupplier clock = System::currentTimeMillis;

    public DbQueryAlertRowsService(DbQueryAlertRowsRepository repo) {
        this.repo = repo;
    }

    @Autowired(required = false)
    public void setMonitorRepository(DbQueryMonitorRepository monitors) {
        this.monitors = monitors;
    }

    /** Testler: saat. */
    void setClockForTests(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Bir çalıştırmanın mail satırlarını belleğe alır (izleme başına en güncel). YALNIZ kural ihlali ya da yavaş sorgu
     * çalıştırmasının satırları tutulur (eşik / yavaşlık alarmı satır taşır; sorgu hatası satırsızdır). Başka sonuç ya da
     * satır yoksa (hata, mail_include_rows kapalı, sonuç boş) izlemenin girişi SİLİNİR — eski çalıştırmanın satırları yeni
     * bir alarma taşınmasın.
     */
    public void remember(Long monitorId, DbQueryCheckerService.Result r) {
        if (monitorId == null) return;
        boolean wanted = r != null && (DbQueryCheckerService.STATUS_BREACH.equals(r.status()) || r.slow());
        Rows rows = wanted ? of(r) : null;
        synchronized (this) {
            removeEntry(monitorId);
            if (rows == null || rows.isEmpty()) return;
            long chars = weight(rows);
            if (chars > MAX_REMEMBERED_CHARS) return;   // tek giriş bütçeyi aşamaz (sınırlarla zaten ≤ ~210 bin)
            long now = clock.getAsLong();
            dropExpired(now);
            while (!latest.isEmpty() && (latest.size() >= MAX_REMEMBERED || totalChars + chars > MAX_REMEMBERED_CHARS)) {
                Iterator<Map.Entry<Long, Entry>> it = latest.entrySet().iterator();
                Entry oldest = it.next().getValue();
                it.remove();
                totalChars -= oldest.chars();
            }
            latest.put(monitorId, new Entry(rows, chars, now));
            totalChars += chars;
        }
    }

    /** İzleme silindi / satırları geçersiz — bellekteki giriş düşer. */
    public void forget(Long monitorId) {
        if (monitorId == null) return;
        synchronized (this) { removeEntry(monitorId); }
    }

    /** Bu pod'un bellekteki son satırları (yoksa ya da süresi dolduysa boş). Ayar uygulanmaz — ham bellek görüntüsü. */
    public Optional<Rows> latest(Long monitorId) {
        return monitorId == null ? Optional.empty() : Optional.ofNullable(memory(monitorId));
    }

    /** Testler / gözlem: bellekteki giriş sayısı ve toplam karakter ağırlığı. */
    synchronized int rememberedCount() { return latest.size(); }

    synchronized long rememberedChars() { return totalChars; }

    /**
     * Alarm bildirimi anında satırları alarmla saklar ve döndürür. Bellekteki satırlar saklanmış olandan YENİYSE yazılır;
     * değilse (ya da bellekte yoksa) saklanmış satırlar döner. İkisi de yoksa boş. İzlemenin GÜNCEL ayarı uygulanır (sınıf
     * belgesi, D2): satırlar kapatılmışsa boş döner ve hiçbir şey yazılmaz; yeni maskeler hem dönen hem saklanan kopyaya
     * işlenir. Hata fırlatmaz — bildirim satırsız gider.
     */
    public Optional<Rows> capture(Long alertEventId, Long monitorId) {
        if (alertEventId == null) return Optional.empty();
        Policy policy = policyOf(monitorId);
        if (!policy.include()) return Optional.empty();
        Rows mem = monitorId == null ? null : memory(monitorId);
        try {
            Optional<DbQueryAlertRows> stored = repo.findByAlertEventId(alertEventId);
            if (mem == null || (stored.isPresent() && !newer(mem.capturedAt(), stored.get().getCapturedAt()))) {
                if (stored.isEmpty()) return Optional.empty();
                Rows was = toRows(stored.get());
                Rows now = policy.apply(was);
                // yeni maske / daraltılan kolon listesi saklı kopyaya da işlenir (çıkarılan kolon veritabanında da kalmaz)
                if (now != was) write(stored.get(), alertEventId, stored.get().getMonitorId(), now);
                return present(now);
            }
            Rows out = policy.apply(mem);
            write(stored.orElse(null), alertEventId, monitorId, out);
            return present(out);
        } catch (Exception e) {
            log.warn("Sorgu alarmı satırları saklanamadı (olay {}): {}", alertEventId, e.getClass().getSimpleName());
            return mem == null ? Optional.empty() : present(policy.apply(mem));
        }
    }

    /** Alarmla saklanmış son satırlar (alarm ayrıntısı ucu) — izlemenin GÜNCEL ayarıyla (D2). */
    public Optional<Rows> stored(Long alertEventId) {
        if (alertEventId == null) return Optional.empty();
        Optional<DbQueryAlertRows> row = repo.findByAlertEventId(alertEventId);
        if (row.isEmpty()) return Optional.empty();
        Policy policy = policyOf(row.get().getMonitorId());
        if (!policy.include()) return Optional.empty();
        return present(policy.apply(toRows(row.get())));
    }

    /** Kolonu kalmayan satırlar (ör. mail kolonları tümüyle değişti) "satır yok" sayılır. */
    private static Optional<Rows> present(Rows r) {
        return r == null || r.isEmpty() ? Optional.empty() : Optional.of(r);
    }

    // ── Güncel ayar (D2) ─────────────────────────────────────────────────────

    /**
     * Gönderim anının ayarı: {@code include} = "satırları maile ekle" açık; {@code masked} = maskelenecek kolonlar (küçük
     * harf); {@code columns} = mail kolonları (küçük harf; boş = daraltma yok — kayıtta seçilen ilk N kolon korunur).
     * Uygulanmadığında (izleme deposu yok / izleme silinmiş) {@link #NONE}.
     */
    record Policy(boolean include, Set<String> masked, Set<String> columns) {
        static final Policy NONE = new Policy(true, Set.of(), Set.of());
        static final Policy BLOCKED = new Policy(false, Set.of(), Set.of());

        /** Önce kolon daraltması (2026-10-31 ikinci tur D-2), sonra yeni maskeler; değişiklik yoksa AYNI nesne döner. */
        Rows apply(Rows r) {
            return mask(narrow(r));
        }

        /**
         * Mail kolonlarından çıkarılan kolonlar satırlardan düşer (sıra korunur; maskeli listesi de süzülür). Kolon listesi
         * boşsa ya da hepsi listedeyse AYNI nesne döner.
         */
        private Rows narrow(Rows r) {
            if (r == null || columns.isEmpty() || r.columns() == null) return r;
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < r.columns().size(); i++) {
                String c = r.columns().get(i);
                if (c != null && columns.contains(c.trim().toLowerCase(Locale.ROOT))) keep.add(i);
            }
            if (keep.size() == r.columns().size()) return r;
            List<String> cols = new ArrayList<>();
            for (int i : keep) cols.add(r.columns().get(i));
            List<List<String>> rows = new ArrayList<>();
            for (List<String> row : r.rows() == null ? List.<List<String>>of() : r.rows()) {
                List<String> o = new ArrayList<>(keep.size());
                for (int i : keep) o.add(row != null && i < row.size() ? row.get(i) : null);
                rows.add(java.util.Collections.unmodifiableList(o));
            }
            Set<String> kept = new LinkedHashSet<>();
            for (String c : cols) kept.add(c.toLowerCase(Locale.ROOT));
            List<String> maskedOut = new ArrayList<>();
            if (r.masked() != null) for (String m : r.masked()) if (m != null && kept.contains(m.toLowerCase(Locale.ROOT)))
                maskedOut.add(m);
            return new Rows(r.capturedAt(), List.copyOf(cols), List.copyOf(rows), List.copyOf(maskedOut), r.rowCount(),
                    r.rowCountCapped(), true);
        }

        private Rows mask(Rows r) {
            if (r == null || masked.isEmpty() || r.columns() == null) return r;
            Set<String> already = new LinkedHashSet<>();
            if (r.masked() != null) for (String m : r.masked()) if (m != null) already.add(m.toLowerCase(Locale.ROOT));
            boolean[] mask = new boolean[r.columns().size()];
            boolean any = false;
            List<String> maskedOut = new ArrayList<>(r.masked() == null ? List.of() : r.masked());
            for (int i = 0; i < mask.length; i++) {
                String c = r.columns().get(i);
                String lc = c == null ? "" : c.toLowerCase(Locale.ROOT);
                if (masked.contains(lc) && !already.contains(lc)) {
                    mask[i] = true;
                    any = true;
                    maskedOut.add(c);
                }
            }
            if (!any) return r;
            List<List<String>> rows = new ArrayList<>();
            for (List<String> row : r.rows() == null ? List.<List<String>>of() : r.rows()) {
                List<String> o = new ArrayList<>(row == null ? List.of() : row);
                for (int i = 0; i < mask.length && i < o.size(); i++) if (mask[i]) o.set(i, MASK);
                rows.add(java.util.Collections.unmodifiableList(o));
            }
            return new Rows(r.capturedAt(), r.columns(), List.copyOf(rows), List.copyOf(maskedOut), r.rowCount(),
                    r.rowCountCapped(), r.truncated());
        }
    }

    /** İzlemenin güncel ayarı; izleme deposu yoksa / izleme silinmişse {@link Policy#NONE}, okunamazsa satır YOK. */
    Policy policyOf(Long monitorId) {
        DbQueryMonitorRepository repoM = monitors;
        if (repoM == null || monitorId == null) return Policy.NONE;
        try {
            Optional<DbQueryMonitor> m = repoM.findById(monitorId);
            if (m.isEmpty()) return Policy.NONE;
            if (Boolean.FALSE.equals(m.get().getMailIncludeRows())) return Policy.BLOCKED;
            Set<String> masked = new LinkedHashSet<>();
            for (String c : readList(m.get().getMaskedColumnsJson())) if (c != null && !c.isBlank())
                masked.add(c.trim().toLowerCase(Locale.ROOT));
            Set<String> columns = new LinkedHashSet<>();
            for (String c : readList(m.get().getMailColumnsJson())) if (c != null && !c.isBlank())
                columns.add(c.trim().toLowerCase(Locale.ROOT));
            // r177 (Ö-6): "yalnız seçilen kolonlar" kipinde seçim yoksa hiçbir kolon maile girmez (ilk N kolona düşülmez)
            if (columns.isEmpty() && DbQueryMonitor.MAIL_COLUMNS_SELECTED.equals(m.get().getMailColumnsMode())) return Policy.BLOCKED;
            return new Policy(true, Set.copyOf(masked), Set.copyOf(columns));
        } catch (Exception e) {
            log.warn("Sorgu izlemesinin satır ayarı okunamadı (#{}) — satırlar gönderilmiyor: {}", monitorId,
                    e.getClass().getSimpleName());
            return Policy.BLOCKED;   // ayar doğrulanamadı: maskelenmesi gereken veri gitmesin
        }
    }

    // ── Bellek (D1) ──────────────────────────────────────────────────────────

    private synchronized Rows memory(Long monitorId) {
        Entry e = latest.get(monitorId);
        if (e == null) return null;
        if (clock.getAsLong() - e.atMs() > MAX_AGE_MS) {
            removeEntry(monitorId);
            return null;
        }
        return e.rows();
    }

    /** Kilit altında: girişi siler, bütçeyi düşer. */
    private void removeEntry(Long monitorId) {
        Entry e = latest.remove(monitorId);
        if (e != null) totalChars -= e.chars();
    }

    /** Kilit altında: süresi dolmuş girişler (ekleme sırası = yaş sırası, baştan). */
    private void dropExpired(long now) {
        Iterator<Map.Entry<Long, Entry>> it = latest.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.atMs() <= MAX_AGE_MS) break;
            it.remove();
            totalChars -= e.chars();
        }
    }

    /** Girişin karakter ağırlığı: kolon adları + hücreler (+ satır başına sabit pay). */
    static long weight(Rows r) {
        long n = 0;
        if (r.columns() != null) for (String c : r.columns()) n += c == null ? 1 : c.length() + 1;
        if (r.rows() != null) for (List<String> row : r.rows()) {
            n += 8;
            if (row != null) for (String v : row) n += v == null ? 1 : v.length() + 1;
        }
        return n;
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
