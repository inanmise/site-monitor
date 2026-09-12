package com.sitemonitor.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Haftalık rapor son giriş zamanı (2026-09-12, kullanıcı: "⏰ Deadline: every Friday at 15:00
 * konfigüratif olarak ayarlanmalı"). İki canlı ayar:
 * <ul>
 *   <li>{@code site.monitor.weekly-report.deadline-day}  — MON…SUN (varsayılan FRI)</li>
 *   <li>{@code site.monitor.weekly-report.deadline-time} — HH:mm (varsayılan 15:00)</li>
 * </ul>
 * Bozuk değer sessizce varsayılana düşer (rapor sayfası ve hatırlatma maili hiç kırılmaz);
 * {@link #valid()} ayar ekranı için doğrulama sonucunu söyler.
 */
public record WeeklyReportDeadline(DayOfWeek day, LocalTime time, boolean valid) {

    public static final String KEY_DAY  = "site.monitor.weekly-report.deadline-day";
    public static final String KEY_TIME = "site.monitor.weekly-report.deadline-time";
    public static final DayOfWeek DEFAULT_DAY = DayOfWeek.FRIDAY;
    public static final LocalTime DEFAULT_TIME = LocalTime.of(15, 0);

    private static final String[] TR_DAYS = {"Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"};
    private static final String[] EN_DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    public static WeeklyReportDeadline resolve(AppSettingsService settings) {
        return parse(settings == null ? null : settings.getString(KEY_DAY, null),
                     settings == null ? null : settings.getString(KEY_TIME, null));
    }

    /** Saf ayrıştırma — test edilebilir; boş = varsayılan (geçerli), bozuk = varsayılan ama {@code valid=false}. */
    public static WeeklyReportDeadline parse(String dayRaw, String timeRaw) {
        boolean ok = true;
        DayOfWeek day = DEFAULT_DAY;
        if (dayRaw != null && !dayRaw.isBlank()) {
            DayOfWeek d = dayFromCode(dayRaw.trim());
            if (d == null) ok = false; else day = d;
        }
        LocalTime time = DEFAULT_TIME;
        if (timeRaw != null && !timeRaw.isBlank()) {
            try { time = LocalTime.parse(timeRaw.trim().length() == 4 ? "0" + timeRaw.trim() : timeRaw.trim()); }
            catch (Exception e) { ok = false; }
        }
        return new WeeklyReportDeadline(day, time, ok);
    }

    static DayOfWeek dayFromCode(String code) {
        String c = code.toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(c) || d.name().startsWith(c) && c.length() >= 3) return d;
        }
        return null;
    }

    /** Ayar/API kodu: MON…SUN. */
    public String dayCode() { return day.name().substring(0, 3); }
    public String timeText() { return String.format("%02d:%02d", time.getHour(), time.getMinute()); }
    public String dayNameTr() { return TR_DAYS[day.getValue() - 1]; }
    public String dayNameEn() { return EN_DAYS[day.getValue() - 1]; }
}
