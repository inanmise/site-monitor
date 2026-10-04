package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.util.Msg;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kişisel push bildirim tercihleri (2026-10-04, onaylı öneri 4/5) — doğrulama, normalize ve yanıt biçimi. Saf, durumsuz.
 *
 * <p>Tercihler OPT-IN'dir: kaydedilmiş hiçbir tercih yoksa (hepsi null) gönderim bugünküyle aynıdır. Normalize kuralı
 * "varsayılana eşit değer saklanmaz": en düşük seviye UYARI = hepsi (null), bütün aileler seçili = hepsi (null), dil
 * Türkçe = null. Böylece "tercih yok" ile "varsayılan tercih" aynı satırı üretir (regresyon yüzeyi büyümez).
 */
public final class PushPreferences {

    private PushPreferences() { }

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Susturma hazır seçenekleri. {@code off} = kaldır. */
    public static final List<String> SNOOZE_PRESETS = List.of("1h", "4h", "tomorrow", "off");
    /** Susturmanın en uzun süresi — hazır seçeneklerin en uzunu ("yarın 08:00") ~32 saattir; sınır güvenlik payıdır. */
    public static final Duration MAX_SNOOZE = Duration.ofHours(48);
    /** Kabul edilen en düşük seviye değerleri (WARNING = hepsi → null saklanır). */
    public static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");

    /** Alan adlı doğrulama hatası (uç 400 + {@code field} döner; mesaj arayüz dilinde). */
    public static class FieldException extends IllegalArgumentException {
        private final String field;
        public FieldException(String field, String message) {
            super(message);
            this.field = field;
        }
        public String field() { return field; }
    }

    /** Normalize edilmiş, kaydedilmeye hazır değerler (null = tercih yok). */
    public record Normalized(String minLevel, String families, String lang) { }

    /**
     * İstek alanlarını doğrular. {@code families}: liste ya da CSV; null = hepsi; BOŞ liste reddedilir (hiç push istemeyen
     * kişi "push istemiyorum" anahtarını kullanır — boş liste sessizce her şeyi susturmasın).
     */
    public static Normalized normalize(Object minLevel, Object families, Object lang) {
        String lvl = minLevel == null ? "" : minLevel.toString().trim().toUpperCase(Locale.ROOT);
        if (!lvl.isEmpty() && !LEVELS.contains(lvl)) {
            throw new FieldException("min_level", Msg.t(
                    "En düşük seviye WARNING, HIGH ya da CRITICAL olmalı.",
                    "The lowest level must be WARNING, HIGH or CRITICAL."));
        }
        String storedLevel = lvl.isEmpty() || "WARNING".equals(lvl) ? null : lvl;

        String storedFamilies = null;
        if (families != null) {
            List<String> tokens = new ArrayList<>();
            if (families instanceof Collection<?> c) {
                for (Object o : c) if (o != null) tokens.add(o.toString());
            } else {
                for (String t : families.toString().split(",")) tokens.add(t);
            }
            Set<String> set = new LinkedHashSet<>();
            for (String raw : tokens) {
                String f = raw.trim().toLowerCase(Locale.ROOT);
                if (f.isEmpty()) continue;
                if (!MonitorTypeCatalog.ORDER.contains(f)) {
                    throw new FieldException("families", Msg.t(
                            "Bilinmeyen izleme türü: " + raw.trim() + ".", "Unknown monitor type: " + raw.trim() + "."));
                }
                set.add(f);
            }
            if (set.isEmpty()) {
                throw new FieldException("families", Msg.t(
                        "En az bir izleme türü seçin. Hiç push istemiyorsanız \"push istemiyorum\" anahtarını kullanın.",
                        "Pick at least one monitor type. If you want no push at all, use the opt-out switch."));
            }
            if (!set.containsAll(MonitorTypeCatalog.ORDER)) {
                List<String> ordered = new ArrayList<>();
                for (String f : MonitorTypeCatalog.ORDER) if (set.contains(f)) ordered.add(f);
                storedFamilies = String.join(",", ordered);
            }
        }

        String lg = lang == null ? "" : lang.toString().trim().toLowerCase(Locale.ROOT);
        if (!lg.isEmpty() && !PushI18n.LANGS.contains(lg)) {
            throw new FieldException("lang", Msg.t("Push dili tr ya da en olmalı.", "Push language must be tr or en."));
        }
        String storedLang = PushI18n.EN.equals(lg) ? PushI18n.EN : null;
        return new Normalized(storedLevel, storedFamilies, storedLang);
    }

    /**
     * Hazır susturma seçeneği → bitiş anı ({@code off} → null). "Yarın 08:00" Europe/Istanbul takvimine göredir.
     */
    public static Instant snoozeUntil(String preset, Instant now) {
        String p = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "1h" -> now.plus(Duration.ofHours(1));
            case "4h" -> now.plus(Duration.ofHours(4));
            case "tomorrow" -> {
                LocalDate tomorrow = now.atZone(ZONE).toLocalDate().plusDays(1);
                yield tomorrow.atTime(LocalTime.of(8, 0)).atZone(ZONE).toInstant();
            }
            case "off" -> null;
            default -> throw new FieldException("preset", Msg.t(
                    "Geçersiz susturma seçeneği (1h, 4h, tomorrow ya da off olmalı).",
                    "Invalid snooze option (use 1h, 4h, tomorrow or off)."));
        };
    }

    public static String iso(Instant i) {
        return i == null ? null : ISO.format(i);
    }

    /** {@code GET /api/me/push-preferences} gövdesi (ve kayıt yanıtları) — yalnız kişinin kendi değerleri. */
    public static Map<String, Object> toMap(AppUser u, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min_level", u.getPushMinLevel());
        Set<String> fam = UserPushRecipientResolver.allowedFamilies(u);
        m.put("families", fam == null ? null : new ArrayList<>(fam));
        m.put("lang", PushI18n.norm(u.getPushLang()));
        boolean active = UserPushRecipientResolver.snoozeActive(u, now);
        m.put("snooze_until", active ? u.getPushSnoozeUntil() : null);
        m.put("snooze_active", active);
        m.put("snooze_critical", !Boolean.FALSE.equals(u.getPushSnoozeCritical()));
        m.put("opt_out", Boolean.TRUE.equals(u.getPushOptOut()));
        m.put("available_families", MonitorTypeCatalog.ORDER);
        m.put("server_now", iso(now));
        return m;
    }
}
