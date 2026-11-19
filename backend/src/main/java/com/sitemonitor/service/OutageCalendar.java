package com.sitemonitor.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Takım kesinti takvimi (2026-11-19) — SAF hesap: bir ayın her İstanbul takvim günü için takımın kesinti dakikası, o güne
 * düşen kesinti alarmı sayısı ve en kötü seviye. Sorgu atmaz, saat dilimi okumaz; "şimdi" çağırandan gelir.
 *
 * <p><b>Ölçü.</b> Günün kesinti süresi = takımın kesinti ailesi alarmlarının aralıklarının BİRLEŞİMİ (çakışan alarmlar
 * bir kez sayılır), güne kırpılmış, dakika olarak (yukarı yuvarlanır: sıfırdan büyük her kesinti en az 1 dk görünür; bir gün
 * en çok 1440 dk). Aralık = açılış → kapanış; hâlâ açık alarm "şimdi"ye kadar sayılır. Gelecek günler (gün başı ≥ şimdi)
 * {@code null}'dur — 0 DEĞİL. Bugün yalnız şimdiye kadarki kısmıyla sayılır.
 *
 * <p><b>Saat dilimi.</b> Europe/Istanbul 2016'dan beri sabit UTC+3, yaz saati yok — gün sınırı {@link #ISTANBUL} ofsetiyle
 * hesaplanır; podun / JVM'in varsayılan saat dilimi HİÇ kullanılmaz.
 */
public final class OutageCalendar {

    private OutageCalendar() {}

    /** Europe/Istanbul — sabit +03:00 (yaz saati yok). */
    public static final ZoneOffset ISTANBUL = ZoneOffset.ofHours(3);
    static final long DAY_MS = 86_400_000L;
    static final long MINUTE_MS = 60_000L;

    /**
     * Bir kesinti alarmının aralığı (epoch ms). {@code endMs} {@code null} → alarm hâlâ açık ("şimdi"ye kadar). Bitiş
     * başlangıçtan önceyse (bozuk saat) sıfır uzunluklu sayılır.
     */
    public record Interval(long startMs, Long endMs, String level) {}

    /** Bir günün sonucu: {@code outageMinutes} gelecek günde {@code null}; {@code worstLevel} alarm yoksa {@code null}. */
    public record Day(LocalDate day, Integer outageMinutes, int alarms, String worstLevel) {}

    /** Ayın sonucu — {@code totalMinutes} günlerin dakikalarının toplamıdır (hücrelerde görünenle aynı). */
    public record Result(YearMonth month, List<Day> days, long totalMinutes) {}

    /** İstanbul günü başı (epoch ms). */
    public static long dayStartMs(LocalDate day) {
        return day.atStartOfDay().toInstant(ISTANBUL).toEpochMilli();
    }

    /** Ayın [başlangıç, bitiş) penceresi (epoch ms, İstanbul gün sınırları). */
    public static long[] monthWindowMs(YearMonth month) {
        return new long[] { dayStartMs(month.atDay(1)), dayStartMs(month.plusMonths(1).atDay(1)) };
    }

    /**
     * Ayı hesaplar.
     *
     * @param intervals takımın kesinti alarmları (sıra önemsiz; null öğe yok sayılır)
     * @param nowMs     şimdiki an — açık alarmların bitişi ve gelecek günlerin sınırı
     */
    public static Result compute(YearMonth month, List<Interval> intervals, long nowMs) {
        List<Interval> list = intervals == null ? List.of() : intervals;
        List<Day> days = new ArrayList<>(month.lengthOfMonth());
        long total = 0;
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate day = month.atDay(d);
            long dayStart = dayStartMs(day);
            if (dayStart >= nowMs) {               // gelecek gün — değer yok
                days.add(new Day(day, null, 0, null));
                continue;
            }
            long windowEnd = Math.min(dayStart + DAY_MS, nowMs);
            List<long[]> pieces = new ArrayList<>();
            int alarms = 0;
            int worstRank = 0;
            for (Interval iv : list) {
                if (iv == null) continue;
                long s = iv.startMs();
                long e = iv.endMs() == null ? nowMs : Math.max(iv.endMs(), s);
                if (s >= windowEnd) continue;                   // gün (ya da şimdiki an) bittikten sonra açıldı
                // Sıfır uzunluklu alarm (aynı saniye açılıp kapandı) açıldığı günde SAYILIR ama dakika getirmez.
                if (Math.max(e, s + 1) <= dayStart) continue;   // günden önce bitti
                alarms++;
                worstRank = Math.max(worstRank, levelRank(iv.level()));
                long cs = Math.max(s, dayStart);
                long ce = Math.min(e, windowEnd);
                if (ce > cs) pieces.add(new long[] { cs, ce });
            }
            int minutes = (int) Math.min(1440, ceilMinutes(unionMs(pieces)));
            total += minutes;
            days.add(new Day(day, minutes, alarms, levelOf(worstRank)));
        }
        return new Result(month, Collections.unmodifiableList(days), total);
    }

    /** Aralık parçalarının birleşim uzunluğu (ms) — çakışan kısım bir kez. */
    static long unionMs(List<long[]> pieces) {
        if (pieces.isEmpty()) return 0;
        pieces.sort((a, b) -> Long.compare(a[0], b[0]));
        long sum = 0;
        long curS = pieces.get(0)[0];
        long curE = pieces.get(0)[1];
        for (int i = 1; i < pieces.size(); i++) {
            long[] p = pieces.get(i);
            if (p[0] <= curE) {
                curE = Math.max(curE, p[1]);
            } else {
                sum += curE - curS;
                curS = p[0];
                curE = p[1];
            }
        }
        return sum + (curE - curS);
    }

    /** ms → dakika, yukarı yuvarlanır (sıfırdan büyük her süre ≥ 1 dk). */
    static long ceilMinutes(long ms) {
        return ms <= 0 ? 0 : (ms + MINUTE_MS - 1) / MINUTE_MS;
    }

    /** Seviye sırası: CRITICAL 3 &gt; HIGH 2 &gt; WARNING 1; bilinmeyen 0 (en kötü seçimine girmez). */
    static int levelRank(String level) {
        if (level == null) return 0;
        return switch (level.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "WARNING" -> 1;
            default -> 0;
        };
    }

    static String levelOf(int rank) {
        return switch (rank) {
            case 3 -> "CRITICAL";
            case 2 -> "HIGH";
            case 1 -> "WARNING";
            default -> null;
        };
    }
}
