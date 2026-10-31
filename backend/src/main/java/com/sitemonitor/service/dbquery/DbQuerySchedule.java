package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbQueryMonitor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saf zamanlama hesabı (sözleşme §4) — Europe/Istanbul. INTERVAL ya da CRON; ikisinde de izinli pencereler, yasak pencereler,
 * tatil takvimi ve ek tarihler uygulanır. Asla fırlatmaz (geçersiz tanım → {@link #validate} hatası; çalışma anında
 * {@link #nextAfter} null = "uygun an yok"). İSKELET — gövde motor paketinde yazılır.
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

    private DbQuerySchedule() {}

    /** Kayıtlı izlemenin tanımı (JSON kolonları gevşek okunur). */
    public static Spec of(DbQueryMonitor m) {
        throw new UnsupportedOperationException("DbQuerySchedule.of");
    }

    /** API gövdesinden (schedule_mode, interval_seconds, cron_expression, windows, blackouts, skip_holidays, skip_dates). */
    public static Spec fromApi(Map<String, Object> body) {
        throw new UnsupportedOperationException("DbQuerySchedule.fromApi");
    }

    /** Kaydı izlemeye yazar (JSON kolonları, kip, aralık / cron) — ETKİN aralığı da {@code intervalSeconds}'a koyar. */
    public static void apply(Spec spec, DbQueryMonitor m, Instant now, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.apply");
    }

    public static List<FieldError> validate(Spec spec, int minIntervalSeconds, Instant now, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.validate");
    }

    /** {@code after}'dan SONRAKİ ilk uygun an; ufuk ({@link #HORIZON_DAYS}) içinde yoksa null. */
    public static Instant nextAfter(Spec spec, Instant after, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.nextAfter");
    }

    public static List<Instant> nextRuns(Spec spec, Instant from, int n, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.nextRuns");
    }

    /** INTERVAL'de aralık; CRON'da sonraki 20 tetiğin medyan aralığı (sn, ≥ 60). */
    public static long effectiveIntervalSeconds(Spec spec, Instant now, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.effectiveIntervalSeconds");
    }

    /** {@code t} anı pencere / yasak / tatil / ek tarih süzgecinden geçiyor mu (kipten bağımsız). */
    public static boolean allowedAt(Spec spec, Instant t, Set<LocalDate> holidays) {
        throw new UnsupportedOperationException("DbQuerySchedule.allowedAt");
    }
}
