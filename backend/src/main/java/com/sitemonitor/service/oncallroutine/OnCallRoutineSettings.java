package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Nöbet rutini takım ayarları (2026-11-26) — SAF. Saklanan belge ESNEK okunur (geçersiz değer → varsayılan); kayıt KATI ve
 * KISMİ uygulanır ({@link #apply}): yalnız gönderilen anahtarlar değişir, iç içe kısmi nesneler kabul edilir, saklananla aynı
 * değer yeniden doğrulanmaz. Alan hataları {@code settings.<yol>}.
 */
public final class OnCallRoutineSettings {

    private OnCallRoutineSettings() {}

    static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_REMINDER_TIMES = 4;
    public static final String DEFAULT_DAY_START = "09:00";

    public record Reminders(boolean enabled, List<String> times, boolean sectionDeadlines, boolean dueMorning) {
        public Reminders {
            times = times == null ? List.of() : List.copyOf(times);
        }
    }

    public record Notice(boolean enabled, boolean mail, boolean push) { }

    public record Settings(boolean enabled, List<Integer> workdays, boolean skipHolidays, String dayStart,
                           Reminders reminders, Notice completionNotice, Notice handoverNotice) {
        public Settings {
            workdays = workdays == null ? List.of() : List.copyOf(workdays);
        }

        public LocalTime dayStartTime() {
            try {
                return LocalTime.parse(dayStart);
            } catch (Exception e) {
                return LocalTime.of(9, 0);
            }
        }
    }

    public static Settings defaults() {
        return new Settings(true, List.of(1, 2, 3, 4, 5), true, DEFAULT_DAY_START,
                new Reminders(true, List.of("16:30", "17:30"), true, true),
                new Notice(true, true, true), new Notice(true, true, true));
    }

    // ── Okuma (esnek) ────────────────────────────────────────────────────────────────────────────

    public static Settings read(String json) {
        if (json == null || json.isBlank()) return defaults();
        try {
            Object raw = JSON.readValue(json, Object.class);
            if (!(raw instanceof Map<?, ?> m)) return defaults();
            return lenient(m);
        } catch (RuntimeException e) {
            return defaults();
        }
    }

    private static Settings lenient(Map<?, ?> m) {
        Settings d = defaults();
        boolean enabled = m.get("enabled") instanceof Boolean b ? b : d.enabled();
        List<Integer> workdays = workdaysOrNull(m.get("workdays"));
        if (workdays == null) workdays = d.workdays();
        boolean skip = m.get("skip_holidays") instanceof Boolean b ? b : d.skipHolidays();
        String dayStart = timeOrNull(m.get("day_start"));
        if (dayStart == null) dayStart = d.dayStart();
        Reminders r = d.reminders();
        if (m.get("reminders") instanceof Map<?, ?> rm) {
            List<String> times = timesOrNull(rm.get("times"));
            r = new Reminders(rm.get("enabled") instanceof Boolean b ? b : r.enabled(),
                    times == null ? r.times() : times,
                    rm.get("section_deadlines") instanceof Boolean b ? b : r.sectionDeadlines(),
                    rm.get("due_morning") instanceof Boolean b ? b : r.dueMorning());
        }
        Notice c = notice(m.get("completion_notice"), d.completionNotice());
        Notice h = notice(m.get("handover_notice"), d.handoverNotice());
        return new Settings(enabled, workdays, skip, dayStart, r, c, h);
    }

    private static Notice notice(Object raw, Notice def) {
        if (!(raw instanceof Map<?, ?> nm)) return def;
        Notice n = new Notice(nm.get("enabled") instanceof Boolean b ? b : def.enabled(),
                nm.get("mail") instanceof Boolean b ? b : def.mail(),
                nm.get("push") instanceof Boolean b ? b : def.push());
        // açık ama kanalsız saklanmış (elle düzenlenmiş satır) → varsayılan kanallar
        return n.enabled() && !n.mail() && !n.push() ? new Notice(true, true, true) : n;
    }

    // ── Kısmi uygulama (katı) ────────────────────────────────────────────────────────────────────

    /**
     * {@code patch} (gönderilen kısmi nesne) saklananın üstüne uygulanır. Yalnız gönderilen anahtarlar değişir; saklananla
     * AYNI değer yeniden doğrulanmaz. Bilinmeyen anahtarlar yok sayılır.
     */
    public static Settings apply(Settings stored, Map<?, ?> patch) {
        Settings s = stored == null ? defaults() : stored;
        if (patch == null || patch.isEmpty()) return s;
        boolean enabled = s.enabled();
        if (patch.containsKey("enabled")) enabled = bool(patch.get("enabled"), "settings.enabled");
        List<Integer> workdays = s.workdays();
        if (patch.containsKey("workdays")) {
            Object raw = patch.get("workdays");
            List<Integer> parsed = workdaysOrNull(raw);
            if (!sameInts(raw, s.workdays())) {
                if (parsed == null || !strictWorkdays(raw)) {
                    throw field("settings.workdays", "İş günleri 1 (Pazartesi) – 7 (Pazar) arasından en az bir gün olmalı ve tekrar etmemeli.",
                            "Workdays must be at least one day between 1 (Monday) and 7 (Sunday), without repeats.");
                }
                workdays = parsed;
            }
        }
        boolean skip = s.skipHolidays();
        if (patch.containsKey("skip_holidays")) skip = bool(patch.get("skip_holidays"), "settings.skip_holidays");
        String dayStart = s.dayStart();
        if (patch.containsKey("day_start")) {
            Object raw = patch.get("day_start");
            if (!(raw instanceof String str && str.trim().equals(s.dayStart()))) {
                String t = timeOrNull(raw);
                if (t == null) throw field("settings.day_start", "Gün başlangıcı SS:dd biçiminde olmalı (ör. 09:00).",
                        "The day start must be HH:mm (e.g. 09:00).");
                dayStart = t;
            }
        }
        Reminders r = s.reminders();
        if (patch.containsKey("reminders")) {
            if (!(patch.get("reminders") instanceof Map<?, ?> rm)) {
                throw field("settings.reminders", "Hatırlatma ayarları bozuk gönderildi.", "The reminder settings were sent broken.");
            }
            boolean en = rm.containsKey("enabled") ? bool(rm.get("enabled"), "settings.reminders.enabled") : r.enabled();
            List<String> times = r.times();
            if (rm.containsKey("times")) {
                Object raw = rm.get("times");
                List<String> parsed = timesOrNull(raw);
                if (!(parsed != null && parsed.equals(r.times()))) {
                    if (parsed == null || !strictTimes(raw)) {
                        throw field("settings.reminders.times", "En çok " + MAX_REMINDER_TIMES + " farklı hatırlatma saati girilebilir (SS:dd).",
                                "You can enter at most " + MAX_REMINDER_TIMES + " different reminder times (HH:mm).");
                    }
                    times = parsed;
                }
            }
            boolean sd = rm.containsKey("section_deadlines")
                    ? bool(rm.get("section_deadlines"), "settings.reminders.section_deadlines") : r.sectionDeadlines();
            boolean dm = rm.containsKey("due_morning")
                    ? bool(rm.get("due_morning"), "settings.reminders.due_morning") : r.dueMorning();
            r = new Reminders(en, times, sd, dm);
        }
        Notice c = applyNotice(s.completionNotice(), patch, "completion_notice");
        Notice h = applyNotice(s.handoverNotice(), patch, "handover_notice");
        return new Settings(enabled, workdays, skip, dayStart, r, c, h);
    }

    private static Notice applyNotice(Notice stored, Map<?, ?> patch, String key) {
        if (!patch.containsKey(key)) return stored;
        if (!(patch.get(key) instanceof Map<?, ?> nm)) {
            throw field("settings." + key, "Bildirim ayarı bozuk gönderildi.", "The notification setting was sent broken.");
        }
        boolean en = nm.containsKey("enabled") ? bool(nm.get("enabled"), "settings." + key + ".enabled") : stored.enabled();
        boolean mail = nm.containsKey("mail") ? bool(nm.get("mail"), "settings." + key + ".mail") : stored.mail();
        boolean push = nm.containsKey("push") ? bool(nm.get("push"), "settings." + key + ".push") : stored.push();
        Notice n = new Notice(en, mail, push);
        if (!n.equals(stored) && n.enabled() && !n.mail() && !n.push()) {
            throw field("settings." + key + ".mail", "Bildirim açıkken en az bir kanal (e-posta ya da push) seçili olmalı. Bir kanal seçin ya da bildirimi kapatın.",
                    "While the notification is on, at least one channel (e-mail or push) must be chosen. Choose a channel or turn the notification off.");
        }
        return n;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────

    private static boolean bool(Object raw, String field) {
        if (raw instanceof Boolean b) return b;
        throw field(field, "Açık / kapalı değeri bekleniyor.", "An on / off value is expected.");
    }

    private static List<Integer> workdaysOrNull(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 7) return null;
        TreeSet<Integer> out = new TreeSet<>();
        for (Object o : list) {
            if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return null;
            int v = n.intValue();
            if (v < 1 || v > 7) return null;
            out.add(v);
        }
        return new ArrayList<>(out);
    }

    private static boolean strictWorkdays(Object raw) {
        List<Integer> p = workdaysOrNull(raw);
        return p != null && raw instanceof List<?> l && p.size() == l.size();
    }

    private static boolean sameInts(Object raw, List<Integer> stored) {
        List<Integer> p = workdaysOrNull(raw);
        return p != null && raw instanceof List<?> l && p.size() == l.size() && p.equals(stored);
    }

    private static String timeOrNull(Object raw) {
        if (!(raw instanceof String s)) return null;
        String t = s.trim();
        return OnCallRoutineTemplate.HHMM.matcher(t).matches() ? t : null;
    }

    private static List<String> timesOrNull(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > MAX_REMINDER_TIMES) return null;
        TreeSet<String> out = new TreeSet<>();
        for (Object o : list) {
            String t = timeOrNull(o);
            if (t == null) return null;
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    private static boolean strictTimes(Object raw) {
        List<String> p = timesOrNull(raw);
        return p != null && raw instanceof List<?> l && p.size() == l.size();
    }

    private static FieldValidationException field(String field, String tr, String en) {
        return new FieldValidationException(field, Msg.t(tr, en));
    }

    // ── Yazma ────────────────────────────────────────────────────────────────────────────────────

    public static Map<String, Object> toWire(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("workdays", new ArrayList<>(s.workdays()));
        m.put("skip_holidays", s.skipHolidays());
        m.put("day_start", s.dayStart());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", s.reminders().enabled());
        r.put("times", new ArrayList<>(s.reminders().times()));
        r.put("section_deadlines", s.reminders().sectionDeadlines());
        r.put("due_morning", s.reminders().dueMorning());
        m.put("reminders", r);
        m.put("completion_notice", noticeWire(s.completionNotice()));
        m.put("handover_notice", noticeWire(s.handoverNotice()));
        return m;
    }

    /** Bildirim ayarının tel biçimi (şablon / ayar yanıtı ve gün sayfası {@code settings.completion_notice} — tek kaynak). */
    static Map<String, Object> noticeWire(Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", n.enabled());
        m.put("mail", n.mail());
        m.put("push", n.push());
        return m;
    }

    public static String toJson(Settings s) {
        return JSON.writeValueAsString(toWire(s));
    }
}
