package com.certmonitor.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ham WHOIS metnini domain bilgisine ayrıştıran parser (TLD bazlı takılabilir).
 * Dönen Map RDAP ile aynı şekle sahiptir: {@code {expiry_date?, registration_date?, last_changed?,
 * registrar?, status_codes:List, nameservers:List}} — tarihler ISO (yyyy-MM-dd) normalize edilir
 * (böylece DomainCheckerService.daysUntil çözebilir); ayrıştırılamayan tarih null bırakılır → UNKNOWN.
 */
public interface WhoisParser {

    Map<String, Object> parse(String rawWhois);

    // ── Ortak yardımcılar ──────────────────────────────────────────────────────

    /** "Key: value" / "Key......: value" (anahtar başta, trailing nokta/boşluk atılır) — ilk eşleşen değer. */
    static String firstValue(String raw, String... keys) {
        if (raw == null) return null;
        for (String line : raw.split("\\r?\\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String left = line.substring(0, c).replaceAll("[.\\s]+$", "").trim();
            for (String key : keys) {
                if (left.equalsIgnoreCase(key)) {
                    String v = line.substring(c + 1).trim();
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return null;
    }

    /** Anahtara uyan TÜM satırların değerlerinin ilk token'ı (nameserver/status için). */
    static List<String> allFirstTokens(String raw, String... keys) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String left = line.substring(0, c).replaceAll("[.\\s]+$", "").trim();
            for (String key : keys) {
                if (left.equalsIgnoreCase(key)) {
                    String v = line.substring(c + 1).trim();
                    if (!v.isEmpty()) {
                        String tok = v.split("\\s+")[0].toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
                        if (!tok.isEmpty() && !out.contains(tok)) out.add(tok);
                    }
                }
            }
        }
        return out;
    }

    /** Çeşitli WHOIS tarih formatlarını ISO (yyyy-MM-dd) normalleştirir; olmazsa null. */
    String[] DATE_PATTERNS = {
        "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX",
        "dd-MMM-yyyy", "yyyy-MMM-dd", "dd.MM.yyyy", "yyyy/MM/dd", "dd/MM/yyyy", "MMM dd yyyy", "d MMMM yyyy"
    };

    static String parseDate(String s) {
        if (s == null) return null;
        String v = s.trim().replaceFirst("[.,;]+$", "").trim();
        if (v.isEmpty()) return null;
        // ISO offset/instant
        try { return OffsetDateTime.parse(v).toLocalDate().toString(); } catch (Exception ignore) {}
        for (String p : DATE_PATTERNS) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p, Locale.ENGLISH);
                return LocalDate.parse(v, f).toString();
            } catch (Exception ignore) {}
        }
        // son çare: baştaki 10 karakter yyyy-MM-dd mi
        try { if (v.length() >= 10) return LocalDate.parse(v.substring(0, 10)).toString(); } catch (Exception ignore) {}
        return null;
    }
}
