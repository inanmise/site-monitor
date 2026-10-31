package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.util.Msg;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Saf zamanlama hesabı (sözleşme §4) — Europe/Istanbul. INTERVAL ya da CRON; ikisinde de izinli pencereler, yasak pencereler,
 * tatil takvimi ve ek tarihler uygulanır. Asla fırlatmaz (geçersiz tanım → {@link #validate} hatası; çalışma anında
 * {@link #nextAfter} null = "uygun an yok").
 *
 * <p>Anlam:
 * <ul>
 *   <li>INTERVAL: {@code nextAfter(after)} = {@code after + aralık}; o an izinli değilse ondan SONRAKİ ilk izinli an.
 *       {@link #firstDue} = şimdi izinliyse şimdi, değilse ilk izinli an.</li>
 *   <li>CRON: 5 alan (başına "0 " eklenir) ya da 6 alan (Spring {@link CronExpression}) ya da @hourly / @daily / @weekly /
 *       @monthly; {@code nextAfter(after)} = after'dan sonraki ilk İZİNLİ tetik.</li>
 *   <li>Pencere: {@code days} = pencerenin BAŞLADIĞI gün (1 Pazartesi … 7 Pazar; boş = her gün); {@code to ≤ from} gece
 *       yarısını aşar (to "24:00" = gün sonu). İzinli pencere varsa an en az birinin içinde olmalı; yasak pencerelerin hiçbirinde
 *       olmamalı. Tatil (skipHolidays) ve ek tarihler ANIN kendi tarihine göre (İstanbul) uygulanır.</li>
 * </ul>
 * Döngüler SINIRLIDIR: izinsiz bir andan sonraki izinli ana dakika dakika değil, bir sonraki pencere / gün sınırına atlanarak
 * gidilir; ufuk {@value #HORIZON_DAYS} gün ve her döngüde ayrıca adım tavanı vardır.
 */
public final class DbQuerySchedule {

    /** @param days 1 = Pazartesi … 7 = Pazar; boş = her gün. {@code from}/{@code to} "HH:mm"; to ≤ from = gece yarısını aşar. */
    public record Window(List<Integer> days, String from, String to) {}

    /**
     * @param mode            INTERVAL | CRON
     * @param intervalSeconds INTERVAL kipinde aralık
     * @param cron            CRON kipinde ifade (5 / 6 alan ya da @hourly / @daily / @weekly / @monthly)
     * @param skipDates       "yyyy-MM-dd" listesi
     */
    public record Spec(String mode, Integer intervalSeconds, String cron, List<Window> windows, List<Window> blackouts,
                       boolean skipHolidays, List<String> skipDates) {}

    /** {@code field}: interval_seconds | cron_expression | windows | blackouts | skip_dates | schedule. */
    public record FieldError(String field, String code, String message) {}

    public static final int MAX_WINDOWS = 20, MAX_SKIP_DATES = 100, HORIZON_DAYS = 366;

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    public static final String MODE_INTERVAL = "INTERVAL", MODE_CRON = "CRON";
    public static final int MIN_INTERVAL_SECONDS = 60, MAX_INTERVAL_SECONDS = 7 * 24 * 3600;
    public static final int MAX_CRON_CHARS = 120;
    /** Cron sıklık denetiminde bakılan tetik sayısı; etkin aralık için medyan örneği. */
    static final int CRON_FREQUENCY_SAMPLES = 50, EFFECTIVE_SAMPLES = 20;
    /** Tek bir arama döngüsünün en çok adımı (ufka ek ikinci sınır). */
    static final int MAX_STEPS = 60_000;

    public static final String CODE_INTERVAL = "DBQUERY_INTERVAL_INVALID", CODE_CRON_INVALID = "DBQUERY_CRON_INVALID",
            CODE_CRON_TOO_FREQUENT = "DBQUERY_CRON_TOO_FREQUENT", CODE_WINDOW = "DBQUERY_WINDOW_INVALID",
            CODE_SKIP_DATE = "DBQUERY_SKIP_DATE_INVALID", CODE_MODE = "DBQUERY_SCHEDULE_MODE_INVALID",
            CODE_NEVER_RUNS = "DBQUERY_SCHEDULE_NEVER_RUNS";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DbQuerySchedule() {}

    // ── Tanım okuma / yazma ──────────────────────────────────────────────────

    /** Kayıtlı izlemenin tanımı (JSON kolonları gevşek okunur). */
    public static Spec of(DbQueryMonitor m) {
        if (m == null) return new Spec(MODE_INTERVAL, 300, null, List.of(), List.of(), false, List.of());
        return ofColumns(m.getScheduleMode(), m.getIntervalSeconds(), m.getCronExpression(), m.getWindowsJson(),
                m.getBlackoutJson(), m.getSkipHolidays(), m.getSkipDatesJson());
    }

    /**
     * Saklı kolonlardan tanım (gevşek okuma) — {@link #of(DbQueryMonitor)} ile AYNI kural; zamanlamayı aynı kolon adlarıyla
     * saklayan öteki türler (sentetik izleme, 2026-10-31) bu kurucuyu kullanır, hesap kopyalanmaz.
     */
    public static Spec ofColumns(String scheduleMode, Integer intervalSeconds, String cronExpression, String windowsJson,
                                 String blackoutJson, Boolean skipHolidays, String skipDatesJson) {
        String mode = normMode(scheduleMode);
        return new Spec(mode == null ? MODE_INTERVAL : mode, intervalSeconds, cronExpression,
                windowsFromJson(windowsJson), windowsFromJson(blackoutJson),
                Boolean.TRUE.equals(skipHolidays), datesFromJson(skipDatesJson));
    }

    /**
     * Kayda yazılacak kolon değerleri ({@link #apply} ile aynı kural — etkin aralık dahil). Öteki türler (sentetik izleme)
     * kendi varlığına bunu yazar.
     */
    public record Columns(String scheduleMode, String cronExpression, int intervalSeconds, String windowsJson,
                          String blackoutJson, boolean skipHolidays, String skipDatesJson) {}

    /** {@code spec} geçersizse de fırlatmaz (doğrulama ayrı); null → null. */
    public static Columns columns(Spec spec, Instant now, Set<LocalDate> holidays) {
        if (spec == null) return null;
        String mode = normMode(spec.mode());
        boolean cron = MODE_CRON.equals(mode);
        return new Columns(cron ? MODE_CRON : MODE_INTERVAL, cron && spec.cron() != null ? spec.cron().trim() : null,
                (int) Math.min(Integer.MAX_VALUE, effectiveIntervalSeconds(spec, now, holidays)),
                windowsToJson(spec.windows()), windowsToJson(spec.blackouts()), spec.skipHolidays(),
                spec.skipDates() == null || spec.skipDates().isEmpty() ? null : toJson(spec.skipDates()));
    }

    /**
     * Tanım bir FİLTRE ya da cron taşıyor mu — CRON kipi, izinli / yasak pencere, tatil atlama ya da ek tarih. false =
     * düz aralık (vade yalnız aralıktan; plan gerekmez).
     */
    public static boolean hasPlan(Spec spec) {
        if (spec == null) return false;
        return MODE_CRON.equals(normMode(spec.mode()))
                || (spec.windows() != null && !spec.windows().isEmpty())
                || (spec.blackouts() != null && !spec.blackouts().isEmpty())
                || spec.skipHolidays()
                || (spec.skipDates() != null && !spec.skipDates().isEmpty());
    }

    /** API gövdesinden (schedule_mode, interval_seconds, cron_expression, windows, blackouts, skip_holidays, skip_dates). */
    public static Spec fromApi(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object rawMode = b.get("schedule_mode");
        String mode = rawMode == null || String.valueOf(rawMode).isBlank() ? MODE_INTERVAL
                : String.valueOf(rawMode).trim().toUpperCase(Locale.ROOT);
        Integer interval = toInt(b.get("interval_seconds"));
        Object cron = b.get("cron_expression");
        Object sh = b.get("skip_holidays");
        boolean skipHolidays = Boolean.TRUE.equals(sh) || (sh instanceof String s && s.trim().equalsIgnoreCase("true"));
        return new Spec(mode, interval, cron == null ? null : String.valueOf(cron), windowsFromApi(b.get("windows")),
                windowsFromApi(b.get("blackouts")), skipHolidays, datesFromApi(b.get("skip_dates")));
    }

    /** Kaydı izlemeye yazar (JSON kolonları, kip, aralık / cron) — ETKİN aralığı da {@code intervalSeconds}'a koyar. */
    public static void apply(Spec spec, DbQueryMonitor m, Instant now, Set<LocalDate> holidays) {
        if (spec == null || m == null) return;
        Columns c = columns(spec, now, holidays);
        m.setScheduleMode(c.scheduleMode());
        m.setCronExpression(c.cronExpression());
        m.setIntervalSeconds(c.intervalSeconds());
        m.setWindowsJson(c.windowsJson());
        m.setBlackoutJson(c.blackoutJson());
        m.setSkipHolidays(c.skipHolidays());
        m.setSkipDatesJson(c.skipDatesJson());
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    public static List<FieldError> validate(Spec spec, int minIntervalSeconds, Instant now, Set<LocalDate> holidays) {
        List<FieldError> errors = new ArrayList<>();
        if (spec == null) {
            errors.add(new FieldError("schedule", CODE_MODE, msgMode()));
            return errors;
        }
        int min = Math.max(MIN_INTERVAL_SECONDS, minIntervalSeconds);
        String mode = normMode(spec.mode());
        if (mode == null) {
            errors.add(new FieldError("schedule", CODE_MODE, msgMode()));
        } else if (MODE_INTERVAL.equals(mode)) {
            Integer s = spec.intervalSeconds();
            if (s == null || s < min || s > MAX_INTERVAL_SECONDS)
                errors.add(new FieldError("interval_seconds", CODE_INTERVAL, Msg.t(
                        "Çalışma aralığı " + min + " saniye ile 7 gün arasında olmalı · Daha sık çalışan bir sorgu "
                                + "veritabanını yorar · Aralığı bu sınırlar içinde seçin.",
                        "The run interval must be between " + min + " seconds and 7 days · A query that runs more often "
                                + "loads the database · Choose an interval within these limits.")));
        } else {
            String raw = spec.cron() == null ? "" : spec.cron().trim();
            CronExpression ce = raw.length() > MAX_CRON_CHARS ? null : parseCron(raw);
            if (ce == null) {
                errors.add(new FieldError("cron_expression", CODE_CRON_INVALID, Msg.t(
                        "Cron ifadesi geçersiz · Beş alan (dakika saat gün ay haftanın-günü), altı alan (saniye ile) ya da "
                                + "@hourly / @daily / @weekly / @monthly kullanılabilir · Örnek: \"0 8 * * 1-5\" "
                                + "(hafta içi her gün 08:00).",
                        "The cron expression is invalid · Use five fields (minute hour day month weekday), six fields "
                                + "(with seconds) or @hourly / @daily / @weekly / @monthly · Example: \"0 8 * * 1-5\" "
                                + "(weekdays at 08:00).")));
            } else {
                Long gap = minCronGapSeconds(ce, now == null ? Instant.now() : now);
                if (gap != null && gap < min)
                    errors.add(new FieldError("cron_expression", CODE_CRON_TOO_FREQUENT, Msg.t(
                            "Cron ifadesi çok sık çalışıyor (iki çalışma arası " + gap + " saniye; en az " + min + ") · "
                                    + "Sık çalışan sorgu veritabanını yorar · Daha seyrek bir zamanlama yazın.",
                            "The cron expression runs too often (" + gap + " seconds between runs; at least " + min
                                    + ") · A frequently running query loads the database · Write a less frequent schedule.")));
            }
        }
        validateWindows(spec.windows(), "windows", errors);
        validateWindows(spec.blackouts(), "blackouts", errors);
        List<String> dates = spec.skipDates() == null ? List.of() : spec.skipDates();
        if (dates.size() > MAX_SKIP_DATES) {
            errors.add(new FieldError("skip_dates", CODE_SKIP_DATE, Msg.t(
                    "En çok " + MAX_SKIP_DATES + " ek tarih girilebilir · Uzun listeler için tatil takvimini kullanın · "
                            + "Listeyi kısaltın.",
                    "At most " + MAX_SKIP_DATES + " extra dates can be entered · Use the holiday calendar for long lists · "
                            + "Shorten the list.")));
        } else {
            for (String d : dates) {
                if (parseDate(d) == null) {
                    errors.add(new FieldError("skip_dates", CODE_SKIP_DATE, Msg.t(
                            "Ek tarih '" + clip(d) + "' geçersiz · Tarih yyyy-AA-gg biçiminde olmalı · Örnek: 2026-12-31.",
                            "The extra date '" + clip(d) + "' is invalid · Dates use the yyyy-MM-dd format · "
                                    + "Example: 2026-12-31.")));
                    break;
                }
            }
        }
        if (errors.isEmpty() && nextAfter(spec, now == null ? Instant.now() : now, holidays) == null) {
            errors.add(new FieldError(MODE_CRON.equals(mode) ? "cron_expression" : "schedule", CODE_NEVER_RUNS, Msg.t(
                    "Bu zamanlamayla sorgu önümüzdeki " + HORIZON_DAYS + " gün içinde hiç çalışmaz · İzinli pencereler, yasak "
                            + "pencereler, tatiller ve ek tarihler birlikte tüm zamanları kapatıyor · Pencereleri ya da "
                            + "cron ifadesini gözden geçirin.",
                    "With this schedule the query never runs in the next " + HORIZON_DAYS + " days · The allowed windows, "
                            + "blackouts, holidays and extra dates together cover every time · Review the windows or the "
                            + "cron expression.")));
        }
        return errors;
    }

    private static void validateWindows(List<Window> ws, String field, List<FieldError> errors) {
        if (ws == null || ws.isEmpty()) return;
        if (ws.size() > MAX_WINDOWS) {
            errors.add(new FieldError(field, CODE_WINDOW, Msg.t(
                    "En çok " + MAX_WINDOWS + " pencere tanımlanabilir · Çok sayıda pencere zamanlamayı anlaşılmaz yapar · "
                            + "Pencereleri birleştirin.",
                    "At most " + MAX_WINDOWS + " windows can be defined · Many windows make the schedule hard to follow · "
                            + "Merge the windows.")));
            return;
        }
        for (int i = 0; i < ws.size(); i++) {
            Window w = ws.get(i);
            boolean ok = w != null && parseTime(w.from(), false) != null && parseTime(w.to(), true) != null;
            if (ok && w.days() != null) for (Integer d : w.days()) if (d == null || d < 1 || d > 7) { ok = false; break; }
            if (!ok) {
                errors.add(new FieldError(field, CODE_WINDOW, Msg.t(
                        "Pencere " + (i + 1) + " geçersiz · Başlangıç ve bitiş SS:dd biçiminde (00:00–23:59; bitiş 24:00 "
                                + "olabilir), günler 1 (Pazartesi) – 7 (Pazar) olmalı · Pencereyi düzeltin.",
                        "Window " + (i + 1) + " is invalid · Start and end use HH:mm (00:00–23:59; the end may be 24:00) "
                                + "and days are 1 (Monday) – 7 (Sunday) · Fix the window.")));
                return;
            }
        }
    }

    // ── Vade hesabı ──────────────────────────────────────────────────────────

    /** {@code after}'dan SONRAKİ ilk uygun an; ufuk ({@link #HORIZON_DAYS}) içinde yoksa null. */
    public static Instant nextAfter(Spec spec, Instant after, Set<LocalDate> holidays) {
        try {
            if (spec == null || after == null) return null;
            Compiled c = compile(spec, holidays);
            if (c == null) return null;
            Instant horizon = after.plus(HORIZON_DAYS, ChronoUnit.DAYS);
            if (c.cron == null) {
                Instant candidate = after.plusSeconds(c.intervalSeconds);
                return firstAllowedAtOrAfter(c, candidate, horizon);
            }
            return nextAllowedTrigger(c, after, horizon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * İlk vade: INTERVAL'de şimdi izinliyse şimdi, değilse ilk izinli an; CRON'da şimdiden sonraki ilk izinli tetik.
     * Ufukta yoksa null.
     */
    public static Instant firstDue(Spec spec, Instant now, Set<LocalDate> holidays) {
        try {
            if (spec == null || now == null) return null;
            Compiled c = compile(spec, holidays);
            if (c == null) return null;
            Instant horizon = now.plus(HORIZON_DAYS, ChronoUnit.DAYS);
            if (c.cron == null) return firstAllowedAtOrAfter(c, now, horizon);
            return nextAllowedTrigger(c, now, horizon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Önizleme: {@code from}'dan itibaren en çok {@code n} (≤ 100) çalışma ({@link #firstDue}, sonra {@link #nextAfter}). */
    public static List<Instant> nextRuns(Spec spec, Instant from, int n, Set<LocalDate> holidays) {
        List<Instant> out = new ArrayList<>();
        int limit = Math.max(0, Math.min(n, 100));
        if (limit == 0) return out;
        Instant t = firstDue(spec, from, holidays);
        while (t != null && out.size() < limit) {
            out.add(t);
            t = nextAfter(spec, t, holidays);
        }
        return out;
    }

    /** INTERVAL'de aralık; CRON'da sonraki 20 tetiğin medyan aralığı (sn, ≥ 60). */
    public static long effectiveIntervalSeconds(Spec spec, Instant now, Set<LocalDate> holidays) {
        if (spec == null) return 300;
        String mode = normMode(spec.mode());
        if (!MODE_CRON.equals(mode)) {
            Integer s = spec.intervalSeconds();
            return s == null || s <= 0 ? 300 : Math.max(MIN_INTERVAL_SECONDS, s);
        }
        Instant base = now == null ? Instant.now() : now;
        List<Instant> runs = nextRuns(spec, base, EFFECTIVE_SAMPLES + 1, holidays);
        Long median = medianGap(runs);
        if (median == null) {
            CronExpression ce = parseCron(spec.cron() == null ? "" : spec.cron().trim());
            if (ce != null) {
                List<Instant> raw = new ArrayList<>();
                ZonedDateTime t = base.atZone(ZONE);
                for (int i = 0; i <= EFFECTIVE_SAMPLES && t != null; i++) {
                    t = ce.next(t);
                    if (t != null) raw.add(t.toInstant());
                }
                median = medianGap(raw);
            }
        }
        if (median == null) return 86_400;
        return Math.max(MIN_INTERVAL_SECONDS, Math.min(median, (long) HORIZON_DAYS * 86_400));
    }

    /** {@code t} anı pencere / yasak / tatil / ek tarih süzgecinden geçiyor mu (kipten bağımsız). */
    public static boolean allowedAt(Spec spec, Instant t, Set<LocalDate> holidays) {
        if (spec == null || t == null) return false;
        Compiled c = compileFilters(spec, holidays);
        return c != null && allowed(c, t.atZone(ZONE));
    }

    // ── İç: derlenmiş tanım ─────────────────────────────────────────────────

    /** @param from/to gün içi dakika; {@code to} 1440 olabilir; days bit maskesi (bit 1..7), 0 = her gün */
    private record Win(int days, int from, int to) {
        boolean crosses() { return to <= from; }
    }

    private static final class Compiled {
        CronExpression cron;
        long intervalSeconds;
        List<Win> windows = List.of();
        List<Win> blackouts = List.of();
        Set<LocalDate> skip = Set.of();
    }

    private static Compiled compile(Spec spec, Set<LocalDate> holidays) {
        Compiled c = compileFilters(spec, holidays);
        if (c == null) return null;
        String mode = normMode(spec.mode());
        if (MODE_CRON.equals(mode)) {
            c.cron = parseCron(spec.cron() == null ? "" : spec.cron().trim());
            if (c.cron == null) return null;
        } else if (MODE_INTERVAL.equals(mode)) {
            Integer s = spec.intervalSeconds();
            if (s == null || s <= 0) return null;
            c.intervalSeconds = s;
        } else {
            return null;
        }
        return c;
    }

    private static Compiled compileFilters(Spec spec, Set<LocalDate> holidays) {
        Compiled c = new Compiled();
        c.windows = compileWindows(spec.windows());
        c.blackouts = compileWindows(spec.blackouts());
        if (c.windows == null || c.blackouts == null) return null;
        Set<LocalDate> skip = new LinkedHashSet<>();
        if (spec.skipHolidays() && holidays != null) skip.addAll(holidays);
        if (spec.skipDates() != null) for (String d : spec.skipDates()) {
            LocalDate ld = parseDate(d);
            if (ld != null) skip.add(ld);
        }
        c.skip = skip;
        return c;
    }

    /** Geçersiz pencere → null (tüm tanım geçersiz sayılır; validate bunu kayıtta yakalar). */
    private static List<Win> compileWindows(List<Window> ws) {
        if (ws == null || ws.isEmpty()) return List.of();
        List<Win> out = new ArrayList<>();
        for (Window w : ws) {
            if (w == null) return null;
            Integer from = parseTime(w.from(), false), to = parseTime(w.to(), true);
            if (from == null || to == null) return null;
            int mask = 0;
            if (w.days() != null) for (Integer d : w.days()) {
                if (d == null || d < 1 || d > 7) return null;
                mask |= 1 << d;
            }
            out.add(new Win(mask, from, to));
        }
        return out;
    }

    private static boolean allowed(Compiled c, ZonedDateTime z) {
        LocalDate date = z.toLocalDate();
        if (c.skip.contains(date)) return false;
        int minute = z.getHour() * 60 + z.getMinute();
        int dow = z.getDayOfWeek().getValue();
        if (!c.windows.isEmpty()) {
            boolean in = false;
            for (Win w : c.windows) if (inside(w, dow, minute)) { in = true; break; }
            if (!in) return false;
        }
        for (Win w : c.blackouts) if (inside(w, dow, minute)) return false;
        return true;
    }

    /** Dakika çözünürlüğü: pencere [from, to) — saniyeler pencere sınırını etkilemez. */
    private static boolean inside(Win w, int dow, int minute) {
        if (!w.crosses()) return dayOk(w, dow) && minute >= w.from() && minute < w.to();
        int prevDow = dow == 1 ? 7 : dow - 1;
        return (dayOk(w, dow) && minute >= w.from()) || (dayOk(w, prevDow) && minute < w.to());
    }

    private static boolean dayOk(Win w, int dow) {
        return w.days() == 0 || (w.days() & (1 << dow)) != 0;
    }

    /**
     * {@code t} anından itibaren (dahil) ilk izinli an. İzinsizse bir sonraki "durum değişebilir" sınırına atlar (gece yarısı,
     * pencere başlangıç / bitişleri) — adım sayısı gün başına pencere sayısıyla sınırlı.
     */
    private static Instant firstAllowedAtOrAfter(Compiled c, Instant t, Instant horizon) {
        ZonedDateTime z = t.atZone(ZONE);
        for (int step = 0; step < MAX_STEPS; step++) {
            if (z.toInstant().isAfter(horizon)) return null;
            if (allowed(c, z)) return z.toInstant();
            ZonedDateTime next = nextBoundary(c, z);
            if (next == null || !next.isAfter(z)) return null;
            z = next;
        }
        return null;
    }

    /** {@code z}'den sonraki en yakın sınır: ertesi gece yarısı ya da bugünün / yarının pencere başı-sonları. */
    private static ZonedDateTime nextBoundary(Compiled c, ZonedDateTime z) {
        LocalDate d = z.toLocalDate();
        ZonedDateTime best = d.plusDays(1).atStartOfDay(ZONE);
        for (int day = 0; day <= 1; day++) {
            LocalDate dd = d.plusDays(day);
            for (List<Win> list : List.of(c.windows, c.blackouts)) {
                for (Win w : list) {
                    for (int minute : new int[] {w.from(), w.to()}) {
                        ZonedDateTime b = minute >= 1440 ? dd.plusDays(1).atStartOfDay(ZONE)
                                : dd.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(ZONE);
                        if (b.isAfter(z) && b.isBefore(best)) best = b;
                    }
                }
            }
        }
        return best;
    }

    /** Cron: {@code after}'dan sonraki ilk İZİNLİ tetik; izinsiz tetikten sonra bir sonraki izinli bölgenin başına atlar. */
    private static Instant nextAllowedTrigger(Compiled c, Instant after, Instant horizon) {
        ZonedDateTime z = after.atZone(ZONE);
        for (int step = 0; step < MAX_STEPS; step++) {
            ZonedDateTime t = c.cron.next(z);
            if (t == null || t.toInstant().isAfter(horizon)) return null;
            if (allowed(c, t)) return t.toInstant();
            Instant region = firstAllowedAtOrAfter(c, t.toInstant(), horizon);
            if (region == null) return null;
            // bölge başlangıcı tam bir tetik anıysa onu da yakalamak için bir nanosaniye geriden ara
            ZonedDateTime from = region.atZone(ZONE).minusNanos(1);
            z = from.isAfter(t) ? from : t;
        }
        return null;
    }

    // ── Cron ─────────────────────────────────────────────────────────────────

    /** Normalleştirilmiş cron ifadesi; geçersizse null. 5 alan → başına "0 "; 6 alan olduğu gibi; dört makro. */
    static CronExpression parseCron(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_CRON_CHARS) return null;
        String norm;
        switch (s.toLowerCase(Locale.ROOT)) {
            case "@hourly" -> norm = "0 0 * * * *";
            case "@daily" -> norm = "0 0 0 * * *";
            case "@weekly" -> norm = "0 0 0 * * SUN";
            case "@monthly" -> norm = "0 0 0 1 * *";
            default -> {
                if (s.startsWith("@")) return null;
                String[] parts = s.split("\\s+");
                if (parts.length == 5) norm = "0 " + String.join(" ", parts);
                else if (parts.length == 6) norm = String.join(" ", parts);
                else return null;
            }
        }
        try {
            return CronExpression.parse(norm);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Sonraki {@value #CRON_FREQUENCY_SAMPLES} ham tetikte ardışık iki tetik arasındaki en kısa süre (sn); tetik yoksa null. */
    static Long minCronGapSeconds(CronExpression ce, Instant now) {
        ZonedDateTime t = ce.next(now.atZone(ZONE));
        if (t == null) return null;
        Long min = null;
        for (int i = 0; i < CRON_FREQUENCY_SAMPLES; i++) {
            ZonedDateTime n = ce.next(t);
            if (n == null) break;
            long gap = ChronoUnit.SECONDS.between(t, n);
            if (min == null || gap < min) min = gap;
            t = n;
        }
        return min;
    }

    private static Long medianGap(List<Instant> runs) {
        if (runs == null || runs.size() < 2) return null;
        long[] gaps = new long[runs.size() - 1];
        for (int i = 1; i < runs.size(); i++) gaps[i - 1] = ChronoUnit.SECONDS.between(runs.get(i - 1), runs.get(i));
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    // ── Ayrıştırma yardımcıları ──────────────────────────────────────────────

    private static String normMode(String mode) {
        if (mode == null || mode.isBlank()) return MODE_INTERVAL;
        String m = mode.trim().toUpperCase(Locale.ROOT);
        return MODE_INTERVAL.equals(m) || MODE_CRON.equals(m) ? m : null;
    }

    /** "HH:mm" → gün içi dakika; {@code allowEnd} ise "24:00" = 1440. */
    static Integer parseTime(String s, boolean allowEnd) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() != 5 || t.charAt(2) != ':') return null;
        if (allowEnd && t.equals("24:00")) return 1440;
        try {
            int h = Integer.parseInt(t.substring(0, 2)), m = Integer.parseInt(t.substring(3));
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.length() != 10) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<Window> windowsFromApi(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Window> out = new ArrayList<>();
        for (Object o : list) {
            if (out.size() > MAX_WINDOWS) { out.add(null); break; }    // tavan aşıldı → validate reddeder
            if (!(o instanceof Map<?, ?> m)) { out.add(null); continue; }
            out.add(new Window(days(m.get("days")), str(m.get("from")), str(m.get("to"))));
        }
        return out;
    }

    private static List<Integer> days(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l)) return java.util.Collections.singletonList(null);
        List<Integer> out = new ArrayList<>();
        for (Object o : l) {
            if (out.size() > 7) break;
            out.add(toInt(o));
        }
        return out;
    }

    private static List<String> datesFromApi(Object raw) {
        if (!(raw instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (out.size() > MAX_SKIP_DATES) break;
            out.add(o == null ? null : String.valueOf(o).trim());
        }
        return out;
    }

    private static List<Window> windowsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            List<Window> out = new ArrayList<>();
            if (list == null) return out;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Window w = new Window(days(m.get("days")), str(m.get("from")), str(m.get("to")));
                    if (compileWindows(List.of(w)) != null) out.add(w);   // bozuk pencere atlanır
                }
                if (out.size() >= MAX_WINDOWS) break;
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> datesFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            List<String> out = new ArrayList<>();
            if (list == null) return out;
            for (Object o : list) {
                if (o != null && parseDate(String.valueOf(o)) != null) out.add(String.valueOf(o));
                if (out.size() >= MAX_SKIP_DATES) break;
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String windowsToJson(List<Window> ws) {
        if (ws == null || ws.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Window w : ws) {
            if (w == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", w.days() == null ? List.of() : w.days());
            m.put("from", w.from());
            m.put("to", w.to());
            out.add(m);
        }
        return out.isEmpty() ? null : toJson(out);
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) return null;
            return (int) d;
        }
        if (o instanceof String s && !s.isBlank() && s.trim().length() <= 12) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String msgMode() {
        return Msg.t("Zamanlama kipi geçersiz · Kip 'aralık' (INTERVAL) ya da 'cron' (CRON) olmalı · Bir kip seçin.",
                "The schedule mode is invalid · The mode must be interval (INTERVAL) or cron (CRON) · Choose a mode.");
    }
}
