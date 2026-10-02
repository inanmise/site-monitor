package com.sitemonitor.service;

import com.sitemonitor.util.Msg;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sessiz saat penceresi (2026-10-01, onaylı öneri 15) — takım alarm bildirimleri ({@link TeamQuietHoursService}) ve
 * kişisel push ({@link UserPushRecipientResolver}) için TEK kural.
 *
 * <p><b>Saat dilimi:</b> Europe/Istanbul. <b>Gece yarısı:</b> pencere geçebilir (22:00–07:00). <b>Günler:</b> süzgeç
 * pencerenin BAŞLADIĞI güne uygulanır — "Pzt–Cum 22:00–07:00" için Cuma 23:00 ve Cumartesi 03:00 sessizdir (Cuma'nın
 * penceresi), Pazartesi 03:00 sessiz DEĞİLDİR (Pazar'ın penceresi; Pazar seçili değil). Boş gün listesi = her gün.
 *
 * <p><b>Seviye:</b> {@code minLevel} pencerede HEMEN giden en düşük seviyedir. Boş/{@code HIGH} → yalnız UYARI ertelenir
 * (varsayılan); {@code CRITICAL} → UYARI ve YÜKSEK ertelenir. KRİTİK hiçbir ayarla ertelenmez/bastırılmaz.
 *
 * <p>Ayar bozuk ya da eksikse pencere YOKTUR ({@link #parse} null döner): bozuk ayar bildirimi asla engellemez.
 */
public record QuietHours(LocalTime start, LocalTime end, Set<DayOfWeek> days, String minLevel) {

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    /** Pencerede hemen giden en düşük seviye — ayar boşken. */
    public static final String DEFAULT_MIN_LEVEL = "HIGH";
    /** Saklanan gün kodları, sabit sıra (Pzt → Paz). */
    public static final List<String> DAY_CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** Pencerenin somut bir oluşumu (başlangıç günü + saatleri, Europe/Istanbul). */
    public record Occurrence(ZonedDateTime start, ZonedDateTime end) {
        /** Tekillik anahtarı: pencerenin yerel başlangıcı, ör. {@code 2026-10-01T22:00}. */
        public String key() { return start.toLocalDateTime().format(KEY); }
        public Instant endInstant() { return end.toInstant(); }
        /** "22:00–07:00" */
        public String label() { return start.format(HM) + "–" + end.format(HM); }
        /** Bitişin yerel saati, ör. "07:00". */
        public String endLabel() { return end.format(HM); }
    }

    /** Normalize edilmiş, kaydedilmeye hazır ayar. Dört alan da null = sessiz saat YOK. */
    public record Config(String start, String end, String days, String minLevel) {
        public static final Config NONE = new Config(null, null, null, null);
        public boolean isSet() { return start != null && end != null; }
    }

    /**
     * Kayıtlı alanlardan pencere — boş/bozuk/sıfır uzunluklu ayar = {@code null} (pencere yok). Gün listesinde
     * tanınmayan kod yok sayılır; liste dolu ama hiç geçerli gün yoksa pencere YOK sayılır (bozuk ayar susturmayı
     * "her gün"e GENİŞLETMEZ).
     */
    public static QuietHours parse(String start, String end, String days, String minLevel) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) return null;
        try {
            String s = start.trim(), e = end.trim();
            if (!HHMM.matcher(s).matches() || !HHMM.matcher(e).matches()) return null;
            LocalTime ls = LocalTime.parse(s), le = LocalTime.parse(e);
            if (ls.equals(le)) return null;   // sıfır uzunluk: 24 saat susturma OLMASIN
            Set<DayOfWeek> d = parseDays(days);
            if (d.isEmpty() && days != null && !days.isBlank()) return null;
            return new QuietHours(ls, le, d, levelOrDefault(minLevel));
        } catch (Exception ex) {
            return null;
        }
    }

    private static Set<DayOfWeek> parseDays(String csv) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (csv == null || csv.isBlank()) return out;
        for (String raw : csv.split(",")) {
            int idx = DAY_CODES.indexOf(raw.trim().toUpperCase(Locale.ROOT));
            if (idx >= 0) out.add(DayOfWeek.of(idx + 1));
        }
        return out;
    }

    private static String levelOrDefault(String minLevel) {
        String v = minLevel == null ? "" : minLevel.trim().toUpperCase(Locale.ROOT);
        return "CRITICAL".equals(v) ? "CRITICAL" : DEFAULT_MIN_LEVEL;
    }

    /** Pencere içindeyken bu seviye ertelenir/bastırılır mı? KRİTİK asla. */
    public boolean defers(String level) {
        String v = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if ("CRITICAL".equals(v)) return false;
        return levelValue(v) < levelValue(minLevel);
    }

    /** {@code now} anını içeren pencere oluşumu; pencere dışındaysa {@code null}. */
    public Occurrence occurrenceAt(Instant now) {
        if (now == null) return null;
        ZonedDateTime local = now.atZone(ZONE);
        LocalDate today = local.toLocalDate();
        // Bugün başlayan ve (gece yarısını geçen pencere için) dün başlayan oluşum adaydır.
        for (LocalDate startDay : List.of(today, today.minusDays(1))) {
            if (!days.isEmpty() && !days.contains(startDay.getDayOfWeek())) continue;
            ZonedDateTime s = LocalDateTime.of(startDay, start).atZone(ZONE);
            LocalDate endDay = end.isAfter(start) ? startDay : startDay.plusDays(1);
            ZonedDateTime e = LocalDateTime.of(endDay, end).atZone(ZONE);
            if (!local.isBefore(s) && local.isBefore(e)) return new Occurrence(s, e);
        }
        return null;
    }

    public boolean activeAt(Instant now) {
        return occurrenceAt(now) != null;
    }

    static int levelValue(String level) {
        return switch (level == null ? "" : level) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }

    // ── Kayıt doğrulaması (takım + kişi ortak; mesajlar arayüz dilinde — Msg.t) ─────────────────────────────

    /**
     * İstek alanlarını doğrulayıp normalize eder. Başlangıç ve bitiş ikisi de boşsa ayar KALDIRILIR
     * ({@link Config#NONE}). Hata → {@link IllegalArgumentException} (Msg.t ile TR/EN; uç 400 döner).
     *
     * @param days CSV metni ("MON,TUE") ya da liste; boş/null ya da yedi günün tamamı = her gün (null saklanır)
     */
    public static Config normalize(String start, String end, Object days, String minLevel) {
        String s = start == null ? "" : start.trim();
        String e = end == null ? "" : end.trim();
        if (s.isEmpty() && e.isEmpty()) return Config.NONE;
        if (s.isEmpty() || e.isEmpty()) {
            throw new IllegalArgumentException(Msg.t(
                    "Sessiz saat için başlangıç ve bitiş birlikte girilmeli.",
                    "Quiet hours need both a start and an end time."));
        }
        if (!HHMM.matcher(s).matches() || !HHMM.matcher(e).matches()) {
            throw new IllegalArgumentException(Msg.t(
                    "Saat SS:dd biçiminde olmalı (ör. 22:00).",
                    "Times must use the HH:mm format (e.g. 22:00)."));
        }
        if (s.equals(e)) {
            throw new IllegalArgumentException(Msg.t(
                    "Sessiz saat başlangıcı ve bitişi aynı olamaz.",
                    "The quiet hours start and end can't be the same."));
        }
        return new Config(s, e, normalizeDays(days), normalizeMinLevel(minLevel));
    }

    /** Gün listesi → kanonik CSV (Pzt → Paz sırası); boş ya da yedi gün = {@code null} (her gün). */
    static String normalizeDays(Object days) {
        List<String> tokens = new ArrayList<>();
        if (days instanceof Collection<?> c) {
            for (Object o : c) if (o != null) tokens.add(o.toString());
        } else if (days != null) {
            for (String t : days.toString().split(",")) tokens.add(t);
        }
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String raw : tokens) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            int idx = DAY_CODES.indexOf(t);
            if (idx < 0) {
                throw new IllegalArgumentException(Msg.t(
                        "Geçersiz gün: " + raw.trim() + " (MON…SUN olmalı).",
                        "Invalid day: " + raw.trim() + " (use MON…SUN)."));
            }
            set.add(DayOfWeek.of(idx + 1));
        }
        if (set.isEmpty() || set.size() == 7) return null;
        List<String> out = new ArrayList<>();
        for (DayOfWeek d : set) out.add(DAY_CODES.get(d.getValue() - 1));
        return String.join(",", out);
    }

    /** {@code null}/boş → null (varsayılan: yalnız UYARI ertelenir); HIGH / CRITICAL kabul. */
    static String normalizeMinLevel(String minLevel) {
        String v = minLevel == null ? "" : minLevel.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if ("HIGH".equals(v) || "CRITICAL".equals(v)) return v;
        throw new IllegalArgumentException(Msg.t(
                "Sessiz saatte hemen gidecek en düşük seviye HIGH ya da CRITICAL olmalı.",
                "The lowest level that still goes out during quiet hours must be HIGH or CRITICAL."));
    }
}
