package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.dbquery.DbQuerySchedule;
import com.sitemonitor.util.Msg;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sentetik izlemenin zamanlama penceresi / cron'u (2026-10-31, k6r D2) — hesap {@link DbQuerySchedule}'ündür (kopyalanmaz):
 * aynı kolon adları, aynı tanım ({@code INTERVAL} / {@code CRON}, izinli / yasak pencereler, tatil, ek tarihler, Europe/Istanbul),
 * aynı hata KODLARI. Burada yalnız sentetik izlemeye özgü olanlar var: varlık ↔ tanım çevirisi, "plan gerekiyor mu" kuralı,
 * gövde anahtarları ve hata METİNLERİ (dbquery'nin metni "sorgu veritabanını yorar" der — sentetikte yanlış olurdu).
 *
 * <p><b>Bugünkü yol bayt-özdeş.</b> Düz aralık + pencere / yasak / tatil / ek tarih YOKSA ({@link #hasPlan}) zamanlama
 * kolonları null kalır, vade bugünkü gibi aralıktan ({@code checkDue}) gelir, {@code next_run_at} hiç okunmaz / yazılmaz.
 */
public final class ScriptedSchedule {

    private ScriptedSchedule() {}

    /** Zamanlama gövde anahtarları (dbquery ScheduleEditor değeriyle AYNI snake_case). Aralık ayrı: {@code intervalSeconds}. */
    public static final List<String> KEYS =
            List.of("schedule_mode", "cron_expression", "windows", "blackouts", "skip_holidays", "skip_dates");

    /** Zamanlama alanlarının varlık özelliği adları (değişiklik geçmişi / geri yükleme farkı). */
    public static final Set<String> PROPS = Set.of("scheduleMode", "intervalSeconds", "cronExpression", "windowsJson",
            "blackoutJson", "skipHolidays", "skipDatesJson");

    /** Sentetik izlemenin en kısa zamanlamalı aralığı (sn) — zamanlama düzenleyicisinin alt sınırı. */
    public static final int MIN_INTERVAL_SECONDS = DbQuerySchedule.MIN_INTERVAL_SECONDS;

    /** Kodu sabit kalan, alan hatası dışında da kod taşıyan zamanlama hataları (dbquery ile aynı). */
    public static final Set<String> CODED = Set.of(DbQuerySchedule.CODE_CRON_TOO_FREQUENT, DbQuerySchedule.CODE_NEVER_RUNS);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Varlık ↔ tanım ───────────────────────────────────────────────────────

    /**
     * İzlemenin plan gerektirip gerektirmediği — UCUZ (JSON ayrıştırmaz): CRON kipi ya da dolu pencere / yasak / tatil / ek
     * tarih kolonu. false = bugünkü düz aralık yolu.
     */
    public static boolean hasPlan(ScriptedMonitor m) {
        if (m == null) return false;
        return hasPlan(m.getScheduleMode(), m.getWindowsJson(), m.getBlackoutJson(), m.getSkipHolidays(), m.getSkipDatesJson());
    }

    /** {@link #hasPlan(ScriptedMonitor)} — kolon değerleriyle (gönderim izdüşümü satırı). */
    public static boolean hasPlan(Object scheduleMode, Object windowsJson, Object blackoutJson, Object skipHolidays,
                                  Object skipDatesJson) {
        if (scheduleMode != null && DbQuerySchedule.MODE_CRON.equalsIgnoreCase(String.valueOf(scheduleMode).trim())) return true;
        if (Boolean.TRUE.equals(skipHolidays)) return true;
        return filled(windowsJson) || filled(blackoutJson) || filled(skipDatesJson);
    }

    private static boolean filled(Object json) {
        if (json == null) return false;
        String s = String.valueOf(json).trim();
        return !s.isEmpty() && !"[]".equals(s) && !"null".equals(s);
    }

    /** Saklı tanım (gevşek okuma — {@link DbQuerySchedule#ofColumns}). */
    public static DbQuerySchedule.Spec specOf(ScriptedMonitor m) {
        if (m == null) return null;
        return DbQuerySchedule.ofColumns(m.getScheduleMode(), m.getIntervalSeconds(), m.getCronExpression(),
                m.getWindowsJson(), m.getBlackoutJson(), m.getSkipHolidays(), m.getSkipDatesJson());
    }

    /**
     * Gönderim izdüşümü satırından tanım ({@code ScriptedMonitorRepository.findCheckDispatchRows}: [0 id, 1 aralık, 2 kip, 3
     * cron, 4 pencereler, 5 yasaklar, 6 tatil, 7 ek tarihler, 8 sonraki plan]). Satır eskiyse (2 kolon) null.
     */
    public static DbQuerySchedule.Spec specOfRow(Object[] row) {
        if (row == null || row.length < 8) return null;
        Integer interval = row[1] instanceof Number n ? n.intValue() : null;
        return DbQuerySchedule.ofColumns(str(row[2]), interval, str(row[3]), str(row[4]), str(row[5]),
                row[6] instanceof Boolean b ? b : null, str(row[7]));
    }

    /** Gönderim satırının zamanlaması plan gerektiriyor mu (eski 2 kolonlu satır → false, bugünkü yol). */
    public static boolean rowHasPlan(Object[] row) {
        return row != null && row.length >= 8 && hasPlan(row[2], row[4], row[5], row[6], row[7]);
    }

    /** Gönderim satırının saklı planı (9. kolon; yoksa null). */
    public static String rowNextRunAt(Object[] row) {
        return row != null && row.length > 8 ? str(row[8]) : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ── Gövde → tanım ────────────────────────────────────────────────────────

    /** Gövdede zamanlama anahtarı var mı (yoksa güncelleme zamanlamaya dokunmaz — bugünkü yol). */
    public static boolean present(Map<String, Object> body) {
        if (body == null) return false;
        for (String k : KEYS) if (body.containsKey(k)) return true;
        return false;
    }

    /**
     * Gövde (+ güncellemede saklı tanım) → tanım. Gelen anahtar saklı değerin ÜSTÜNE yazılır; aralık gövdedeki
     * {@code intervalSeconds}'tan (zamanlama düzenleyicisinin {@code interval_seconds} adı da kabul), yoksa izlemenin aralığı.
     */
    public static DbQuerySchedule.Spec fromBody(Map<String, Object> body, ScriptedMonitor current) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) merged.putAll(api(current));
        merged.remove("next_run_at");
        merged.remove("effective_interval_seconds");
        if (body != null) {
            for (String k : KEYS) if (body.containsKey(k)) merged.put(k, body.get(k));
            Object iv = body.get("intervalSeconds") != null ? body.get("intervalSeconds") : body.get("interval_seconds");
            if (iv != null) merged.put("interval_seconds", iv);
        }
        if (!merged.containsKey("interval_seconds") || merged.get("interval_seconds") == null)
            merged.put("interval_seconds", current != null && current.getIntervalSeconds() != null ? current.getIntervalSeconds() : 300);
        return DbQuerySchedule.fromApi(merged);
    }

    /**
     * Tanımı izlemeye yazar. Plan gerektirmeyen tanım (düz aralık, filtre yok) → zamanlama kolonları NULL (bugünkü satırla
     * aynı) ve aralık dokunulmaz; aksi halde kolonlar + ETKİN aralık ({@link DbQuerySchedule#columns}).
     */
    public static void apply(DbQuerySchedule.Spec spec, ScriptedMonitor m, Instant now, Set<LocalDate> holidays) {
        if (spec == null || m == null) return;
        if (!DbQuerySchedule.hasPlan(spec)) {
            m.setScheduleMode(null);
            m.setCronExpression(null);
            m.setWindowsJson(null);
            m.setBlackoutJson(null);
            m.setSkipHolidays(null);
            m.setSkipDatesJson(null);
            if (spec.intervalSeconds() != null) m.setIntervalSeconds(spec.intervalSeconds());
            return;
        }
        DbQuerySchedule.Columns c = DbQuerySchedule.columns(spec, now, holidays);
        m.setScheduleMode(c.scheduleMode());
        m.setCronExpression(c.cronExpression());
        m.setIntervalSeconds(c.intervalSeconds());
        m.setWindowsJson(c.windowsJson());
        m.setBlackoutJson(c.blackoutJson());
        m.setSkipHolidays(c.skipHolidays() ? Boolean.TRUE : null);
        m.setSkipDatesJson(c.skipDatesJson());
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    /** Alan hatası: {@code field} (schedule_mode | interval_seconds | cron_expression | windows | blackouts | skip_dates | schedule), dbquery kodu, sentetik metni. */
    public record FieldError(String field, String code, String message) {}

    /**
     * Tanımın doğrulaması — YALNIZ plan gerektiren tanımda (düz aralık bugünkü gibi denetlenmez: eski kayıtların aralığı
     * değişmeden düzenlenebilir kalır). Kodlar dbquery'ninkiyle AYNI; metinler sentetiğe göre.
     */
    public static List<FieldError> validate(DbQuerySchedule.Spec spec, Instant now, Set<LocalDate> holidays) {
        List<FieldError> out = new ArrayList<>();
        if (spec == null) {
            out.add(new FieldError("schedule", DbQuerySchedule.CODE_MODE, msgMode()));
            return out;
        }
        if (!DbQuerySchedule.hasPlan(spec) && isIntervalMode(spec)) return out;
        List<DbQuerySchedule.FieldError> raw = DbQuerySchedule.validate(spec, MIN_INTERVAL_SECONDS, now, holidays);
        for (DbQuerySchedule.FieldError fe : raw == null ? List.<DbQuerySchedule.FieldError>of() : raw)
            out.add(new FieldError(fe.field() == null ? "schedule" : fe.field(), fe.code(), message(fe)));
        return out;
    }

    private static boolean isIntervalMode(DbQuerySchedule.Spec spec) {
        String m = spec.mode() == null ? "" : spec.mode().trim().toUpperCase(Locale.ROOT);
        return m.isEmpty() || DbQuerySchedule.MODE_INTERVAL.equals(m);
    }

    /** dbquery hata koduna karşılık sentetik izleme metni (ne · neden · ne yapmalı); bilinmeyen kod → dbquery metni. */
    static String message(DbQuerySchedule.FieldError fe) {
        String code = fe.code() == null ? "" : fe.code();
        return switch (code) {
            case DbQuerySchedule.CODE_INTERVAL -> Msg.t(
                    "Zamanlamalı senaryonun çalışma aralığı " + MIN_INTERVAL_SECONDS + " saniye ile 7 gün arasında olmalı · Daha sık "
                            + "koşan senaryo hedef sisteme ve k6 havuzuna yük bindirir · Aralığı bu sınırlar içinde seçin.",
                    "A scheduled scenario's run interval must be between " + MIN_INTERVAL_SECONDS + " seconds and 7 days · A "
                            + "scenario that runs more often loads the target system and the k6 pool · Choose an interval within these limits.");
            case DbQuerySchedule.CODE_CRON_TOO_FREQUENT -> Msg.t(
                    "Cron ifadesi çok sık çalışıyor (iki koşum arası en az " + MIN_INTERVAL_SECONDS + " saniye olmalı) · Sık koşan "
                            + "senaryo hedef sisteme ve k6 havuzuna yük bindirir · Daha seyrek bir zamanlama yazın.",
                    "The cron expression runs too often (at least " + MIN_INTERVAL_SECONDS + " seconds between runs) · A frequently "
                            + "running scenario loads the target system and the k6 pool · Write a less frequent schedule.");
            case DbQuerySchedule.CODE_NEVER_RUNS -> Msg.t(
                    "Bu zamanlamayla senaryo önümüzdeki " + DbQuerySchedule.HORIZON_DAYS + " gün içinde hiç koşmaz · İzinli pencereler, "
                            + "yasak pencereler, tatiller ve ek tarihler birlikte tüm zamanları kapatıyor · Pencereleri ya da cron "
                            + "ifadesini gözden geçirin.",
                    "With this schedule the scenario never runs in the next " + DbQuerySchedule.HORIZON_DAYS + " days · The allowed "
                            + "windows, blackouts, holidays and extra dates together cover every time · Review the windows or the cron expression.");
            default -> fe.message();   // cron sözdizimi, pencere, ek tarih, kip: metin türden bağımsız
        };
    }

    private static String msgMode() {
        return Msg.t("Zamanlama kipi geçersiz · Kip 'aralık' (INTERVAL) ya da 'cron' (CRON) olmalı · Bir kip seçin.",
                "The schedule mode is invalid · The mode must be interval (INTERVAL) or cron (CRON) · Choose a mode.");
    }

    // ── API biçimi ───────────────────────────────────────────────────────────

    /**
     * Yanıt satırının zamanlama alanları: {@code schedule_mode}, {@code interval_seconds}, {@code cron_expression},
     * {@code windows}, {@code blackouts}, {@code skip_holidays}, {@code skip_dates}, {@code next_run_at} (yalnız zamanlamalı
     * izlemede; düz aralıkta null), {@code effective_interval_seconds}. Bozuk JSON kolonu boş liste.
     */
    public static Map<String, Object> api(ScriptedMonitor m) {
        Map<String, Object> s = new LinkedHashMap<>();
        DbQuerySchedule.Spec spec = specOf(m);
        boolean planned = hasPlan(m);
        s.put("schedule_mode", spec == null ? DbQuerySchedule.MODE_INTERVAL : spec.mode());
        s.put("interval_seconds", m == null ? null : m.getIntervalSeconds());
        s.put("cron_expression", m == null ? null : m.getCronExpression());
        s.put("windows", spec == null ? List.of() : windowsApi(spec.windows()));
        s.put("blackouts", spec == null ? List.of() : windowsApi(spec.blackouts()));
        s.put("skip_holidays", m != null && Boolean.TRUE.equals(m.getSkipHolidays()));
        s.put("skip_dates", spec == null || spec.skipDates() == null ? List.of() : spec.skipDates());
        s.put("next_run_at", planned && m != null ? m.getNextRunAt() : null);
        s.put("effective_interval_seconds", m == null ? null : m.getIntervalSeconds());
        return s;
    }

    private static List<Map<String, Object>> windowsApi(List<DbQuerySchedule.Window> ws) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ws == null) return out;
        for (DbQuerySchedule.Window w : ws) {
            if (w == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", w.days() == null ? List.of() : w.days());
            m.put("from", w.from());
            m.put("to", w.to());
            out.add(m);
        }
        return out;
    }

    /**
     * Zamanlama önizlemesi (dbquery önizlemesiyle AYNI yanıt: {@code valid}, {@code errors} [{field, code, message}],
     * {@code next} (sonraki 10 koşum, UTC ISO), {@code effective_interval_seconds}, {@code never_runs}). Saf hesap.
     * Düz aralık (filtre yok) da geçerli bir tanımdır: sonraki koşumlar aralıkla gösterilir.
     */
    public static Map<String, Object> preview(Map<String, Object> body, Instant now, Set<LocalDate> holidays) {
        Map<String, Object> src = new LinkedHashMap<>();
        if (body != null) {
            for (String k : KEYS) if (body.containsKey(k)) src.put(k, body.get(k));
            Object iv = body.get("interval_seconds") != null ? body.get("interval_seconds") : body.get("intervalSeconds");
            if (iv != null) src.put("interval_seconds", iv);
        }
        DbQuerySchedule.Spec spec = DbQuerySchedule.fromApi(src);
        // Önizlemede düz aralık da denetlenir (formda alt sınır gösterilsin) — kayıtta ise yalnız plan gerektiren tanım.
        List<FieldError> errors = new ArrayList<>();
        List<DbQuerySchedule.FieldError> raw = DbQuerySchedule.validate(spec, MIN_INTERVAL_SECONDS, now, holidays);
        for (DbQuerySchedule.FieldError fe : raw == null ? List.<DbQuerySchedule.FieldError>of() : raw)
            errors.add(new FieldError(fe.field() == null ? "schedule" : fe.field(), fe.code(), message(fe)));
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> errOut = new ArrayList<>();
        boolean neverRuns = false;
        for (FieldError fe : errors) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("field", fe.field());
            e.put("code", fe.code());
            e.put("message", fe.message());
            errOut.add(e);
            if (DbQuerySchedule.CODE_NEVER_RUNS.equals(fe.code())) neverRuns = true;
        }
        List<String> next = new ArrayList<>();
        Long effective = null;
        if (errOut.isEmpty()) {
            for (Instant t : DbQuerySchedule.nextRuns(spec, now, 10, holidays)) next.add(ISO.format(t));
            effective = DbQuerySchedule.effectiveIntervalSeconds(spec, now, holidays);
            neverRuns = next.isEmpty();
        }
        out.put("valid", errOut.isEmpty());
        out.put("errors", errOut);
        out.put("next", next);
        out.put("effective_interval_seconds", effective);
        out.put("never_runs", neverRuns);
        return out;
    }

    /** İki izlemenin zamanlama alanları aynı mı (aralık dahil; plan sıfırlama kararı). */
    public static boolean sameSchedule(ScriptedMonitor a, ScriptedMonitor b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.getScheduleMode(), b.getScheduleMode())
                && Objects.equals(a.getIntervalSeconds(), b.getIntervalSeconds())
                && Objects.equals(a.getCronExpression(), b.getCronExpression())
                && Objects.equals(a.getWindowsJson(), b.getWindowsJson())
                && Objects.equals(a.getBlackoutJson(), b.getBlackoutJson())
                && Objects.equals(a.getSkipHolidays(), b.getSkipHolidays())
                && Objects.equals(a.getSkipDatesJson(), b.getSkipDatesJson());
    }
}
