package com.sitemonitor.service.oncall;

import com.sitemonitor.model.OnCallSchedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Haftalık nöbet bildirimi — SAF zaman hesabı (2026-11-15). İstanbul'da yaz saati yok (UTC+3 sabit, {@link OnCallRotation#TZ}).
 *
 * <ul>
 *   <li><b>Dilim:</b> bir ISO haftasında (Pazartesi–Pazar, İstanbul) seçilen gün ({@code 1} Pazartesi … {@code 7} Pazar) ve
 *       saat ("HH:mm").</li>
 *   <li><b>Hafta anahtarı:</b> dilimin ISO haftasının PAZARTESİ'si ("yyyy-MM-dd") — takım × hafta en çok bir bildirim (gün
 *       hafta ortasında değişse de).</li>
 *   <li><b>Telafi penceresi:</b> dilim geçtiyse o İstanbul gününün sonuna kadar (gece yarısına yakın dilimde en az
 *       {@link #MIN_CATCH_UP}) gönderilir — 09:00'da yeniden başlayan pod 08:30 dilimini yine gönderir. Pencere de geçmişse
 *       dilim "kaçırıldı" (iz yazılır, gönderilmez).</li>
 *   <li><b>İçerik aralığı:</b> dilimden bir sonraki dilime (7 gün).</li>
 * </ul>
 */
public final class OnCallWeeklyNoticeSlots {

    private OnCallWeeklyNoticeSlots() {}

    public static final int DEFAULT_DAY = 1;
    public static final String DEFAULT_TIME = "08:30";
    /** Gece yarısına yakın dilimin (ör. Pazar 23:55) en kısa telafi süresi — meşgul zamanlayıcı onu kaçırmasın. */
    public static final Duration MIN_CATCH_UP = Duration.ofHours(1);
    /** Bildirimin kapsadığı süre: dilimden sonraki dilime. */
    public static final Duration WEEK = Duration.ofDays(7);

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Okunmuş (varsayılanları uygulanmış) ayar. */
    public record Settings(boolean enabled, boolean mail, boolean push, int day, String time) {

        /** Satırdan: null = kapalı / açık kanal / Pazartesi / 08:30; bozuk gün ya da saat varsayılana döner. */
        public static Settings of(OnCallSchedule s) {
            if (s == null) return new Settings(false, true, true, DEFAULT_DAY, DEFAULT_TIME);
            Integer d = s.getWeeklyNoticeDay();
            String t = s.getWeeklyNoticeTime();
            return new Settings(Boolean.TRUE.equals(s.getWeeklyNoticeEnabled()),
                    !Boolean.FALSE.equals(s.getWeeklyNoticeMail()), !Boolean.FALSE.equals(s.getWeeklyNoticePush()),
                    validDay(d) ? d : DEFAULT_DAY, validTime(t) ? t : DEFAULT_TIME);
        }

        public LocalTime localTime() {
            return LocalTime.parse(time);
        }
    }

    public static boolean validDay(Integer d) {
        return d != null && d >= 1 && d <= 7;
    }

    /** "HH:mm", 00:00–23:59 (uzunluk önce — düzenli ifade yalnız 5 karakterlik dizgide koşar). */
    public static boolean validTime(String t) {
        return t != null && t.length() == 5 && TIME.matcher(t).matches();
    }

    /** İstanbul tarihi. */
    public static LocalDate localDate(Instant at) {
        return LocalDateTime.ofInstant(at, OnCallRotation.TZ).toLocalDate();
    }

    /** Tarihin ISO haftasının Pazartesi'si. */
    public static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** {@code monday} haftasının dilimi (UTC an). */
    public static Instant slotInWeek(LocalDate monday, int day, String time) {
        int dd = validDay(day) ? day : DEFAULT_DAY;
        LocalTime t = LocalTime.parse(validTime(time) ? time : DEFAULT_TIME);
        return monday.plusDays(dd - 1L).atTime(t).atZone(OnCallRotation.TZ).toInstant();
    }

    /** Dilimin hafta anahtarı (ISO haftasının Pazartesi'si, "yyyy-MM-dd"). */
    public static String weekKey(Instant slot) {
        return monday(localDate(slot)).toString();
    }

    /** Telafi penceresinin sonu: dilimin İstanbul gününün sonu, en az {@link #MIN_CATCH_UP} sonrası. */
    public static Instant catchUpEnd(Instant slot) {
        Instant dayEnd = localDate(slot).plusDays(1).atStartOfDay(OnCallRotation.TZ).toInstant();
        Instant min = slot.plus(MIN_CATCH_UP);
        return dayEnd.isAfter(min) ? dayEnd : min;
    }

    /** Tur adayları: önceki haftanın ve bu haftanın dilimi (gece yarısını aşan telafi penceresi önceki haftaya düşebilir). */
    public static List<Instant> candidates(Instant now, int day, String time) {
        LocalDate thisMonday = monday(localDate(now));
        List<Instant> out = new ArrayList<>(2);
        out.add(slotInWeek(thisMonday.minusWeeks(1), day, time));
        out.add(slotInWeek(thisMonday, day, time));
        return out;
    }

    /** Turun baktığı hafta anahtarları (önceki + bu hafta) — bütün takımlar için aynı. */
    public static List<String> candidateWeekKeys(Instant now) {
        LocalDate thisMonday = monday(localDate(now));
        return List.of(thisMonday.minusWeeks(1).toString(), thisMonday.toString());
    }

    /**
     * Sıradaki gönderim anı: henüz gelmemiş ilk dilim, ya da telafi penceresi açık ve haftası henüz gönderilmemiş (
     * {@code weekDone} false) geçmiş dilim — o durumda dönen an geçmiştedir ("az sonra gönderilecek"). Kapalı ayar → null.
     */
    public static Instant next(Settings st, Instant now, Predicate<String> weekDone) {
        if (st == null || !st.enabled()) return null;
        LocalDate thisMonday = monday(localDate(now));
        for (int w = -1; w <= 2; w++) {
            Instant slot = slotInWeek(thisMonday.plusWeeks(w), st.day(), st.time());
            if (slot.isAfter(now)) return slot;
            if (now.isBefore(catchUpEnd(slot)) && (weekDone == null || !weekDone.test(weekKey(slot)))) return slot;
        }
        return null;
    }

    public static String iso(Instant at) {
        return at == null ? null : ISO.format(at);
    }

    public static Instant parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
