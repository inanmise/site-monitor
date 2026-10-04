package com.sitemonitor.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kişi push'unun DİL katmanı (2026-10-04, onaylı öneri 5) — saf, durumsuz.
 *
 * <p><b>Sözleşme:</b> Türkçe çıktı BAYT BAYT bugünküdür — {@code tr} dalı her zaman eski yardımcıları
 * ({@link EscalationService#levelWordTr}, {@link UserPushService#expiringWhat}, {@link PushText#compactDuration},
 * {@link PushText#istDate}) çağırır; bu sınıf yalnız İngilizce karşılıkları ekler. Dil kodu bilinmiyorsa Türkçe.
 *
 * <p><b>Sınır:</b> alarmın kendi metni ({@code AlertEvent.message} → {@code {neden}} / {@code {degisen}}) alarm açılırken
 * Türkçe yazılır; İngilizce push'ta o parça Türkçe kalır (alarm kaydı tek dilde saklanıyor). Şablonun geri kalanı, seviye
 * sözcüğü, süre/tarih biçimi ve ölçü adları kişinin dilindedir.
 */
public final class PushI18n {

    private PushI18n() { }

    public static final String TR = "tr";
    public static final String EN = "en";
    public static final List<String> LANGS = List.of(TR, EN);

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** {@code en} → en; diğer her şey (null dahil) → tr (bugünkü davranış). */
    public static String norm(String lang) {
        return lang != null && EN.equalsIgnoreCase(lang.trim()) ? EN : TR;
    }

    public static boolean isEn(String lang) {
        return EN.equals(norm(lang));
    }

    /** Varsayılan İngilizce şablonlar — yer tutucu ADLARI Türkçe şablonlarla aynıdır ({@code {seviye}}, {@code {ad}} …). */
    public static final Map<String, String> DEFAULT_TEMPLATES_EN = Map.of(
            "down",     "{seviye}: {ad} is not responding. Started {baslangic}. {neden}",
            "slow",     "{seviye}: {ad} is slow - {metrik} {deger} (threshold {esik}). Started {baslangic}.",
            "expiry",   "{seviye}: {ad} - {ne} expires in {gun} days ({tarih}).",
            "changed",  "{seviye}: {ad} - {degisen} changed. Started {baslangic}.",
            "cert",     "{seviye}: {ad} certificate is not acceptable (IP {ip}, CN {cn}). {neden}",
            "resolved", "RESOLVED: {ad} is back to normal. Duration {sure} (started {baslangic}, ended {bitis}).",
            "degraded", "{seviye}: {ad} - problem detected (not an outage). {neden} Started {baslangic}.",
            "test",     "Test: SiteMonitor webhook test - {saat}");

    /** Seviye sözcüğü — tr: e-posta/NOC ile tek sözlük ({@link EscalationService#levelWordTr}). */
    public static String levelWord(String level, String lang) {
        if (!isEn(lang)) return EscalationService.levelWordTr(level);
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "INFO", "LOW" -> "INFO";
            default -> "WARNING";
        };
    }

    /** Özet kırılımı için küçük harfli seviye adı ("3 kritik", "3 critical"). */
    static String levelNoun(String level, String lang) {
        String l = level == null ? "" : level.toUpperCase(Locale.ROOT);
        boolean en = isEn(lang);
        return switch (l) {
            case "CRITICAL" -> en ? "critical" : "kritik";
            case "HIGH" -> en ? "high" : "yüksek";
            case "INFO", "LOW" -> en ? "info" : "bilgi";
            default -> en ? "warning" : "uyarı";
        };
    }

    /** {@code {ne}} — süre bitişinde NEYİN dolduğu. */
    public static String expiringWhat(String alertType, String lang) {
        if (!isEn(lang)) return UserPushService.expiringWhat(alertType);
        String t = alertType == null ? "" : alertType;
        return t.contains("DOMAIN") ? "domain registration" : "SSL certificate";
    }

    /** Süre — tr: {@code "45 sn" / "5 dk" / "2 sa 3 dk" / "3 g 4 sa"}; en: {@code "45 s" / "5 min" / "2 h 3 min" / "3 d 4 h"}. */
    public static String compactDuration(Duration d, String lang) {
        if (!isEn(lang)) return PushText.compactDuration(d);
        if (d == null || d.isNegative()) return "-";
        long sec = d.getSeconds();
        if (sec < 60) return sec + " s";
        long min = sec / 60;
        if (min < 60) return min + " min";
        long hr = min / 60;
        if (hr < 24) return hr + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
        long day = hr / 24;
        return day + " d" + (hr % 24 > 0 ? " " + (hr % 24) + " h" : "");
    }

    /** UTC damga → tarih; tr {@code dd.MM.yyyy} (bugünkü), en {@code d MMM yyyy} ("22 Sep 2026" — gün/ay karışmaz). */
    public static String date(String storedUtc, String lang) {
        if (!isEn(lang)) return PushText.istDate(storedUtc);
        Instant i = PushText.parseStoredUtc(storedUtc);
        return i == null ? "-" : EN_DATE.format(i.atZone(IST));
    }

    private static final Map<String, String> METRIC_EN = Map.of(
            "sayfa hızı", "page speed", "koşum", "run", "yanıt", "response", "ping", "ping");

    /** {@link PushText#slowFields} ölçü adını dile çevirir (tr: olduğu gibi). */
    public static String metric(String trName, String lang) {
        if (!isEn(lang) || trName == null) return trName;
        return METRIC_EN.getOrDefault(trName, trName);
    }

    /** Varsayılan ölçü adı (bağlam ölçü taşımıyorsa). */
    public static String defaultMetric(String lang) {
        return isEn(lang) ? "response" : "yanıt";
    }

    // ── Kanalın kendi metinleri ────────────────────────────────────────────────────────────────

    /** Eskalasyon adımı push'unun öneki — e-posta konusunun ({@code [ESKALASYON · N dk onaysız]}) push eşi. */
    public static String escalationStepPrefix(int delayMinutes, String lang) {
        return isEn(lang)
                ? "[ESCALATION · " + delayMinutes + " min unacknowledged] "
                : "[ESKALASYON · " + delayMinutes + " dk onaysız] ";
    }

    /** "Kendime test push'u gönder" metni. */
    public static String selfTest(Instant now, String lang) {
        String clock = HHMM.format((now == null ? Instant.now() : now).atZone(IST));
        return isEn(lang)
                ? "SiteMonitor test notification: your push channel works (" + clock + ")."
                : "SiteMonitor test bildirimi: push kanalınız çalışıyor (" + clock + ").";
    }

    /** Özetin "son bildirim" satırı için girdi. */
    public record OverflowLast(String name, String level, String trigger, String createdAtUtc) { }

    /**
     * Saat tavanı özeti metni — örn. "SiteMonitor: saat tavanı nedeniyle 14 bildirim gönderilmedi (3 kritik, 11 uyarı).
     * Son: site-x - KRİTİK (14:05). Ayrıntılar SiteMonitor'da." Bağlantı YOK (push kurum ağı dışında okunur).
     *
     * @param levels seviye → adet (sıra: kritik, yüksek, uyarı, bilgi; sıfırlar yazılmaz)
     */
    public static String overflowSummary(int total, Map<String, Integer> levels, OverflowLast last, String lang) {
        boolean en = isEn(lang);
        StringBuilder sb = new StringBuilder();
        sb.append(en ? "SiteMonitor: " + total + (total == 1 ? " notification was" : " notifications were")
                        + " held back by the hourly limit"
                     : "SiteMonitor: saat tavanı nedeniyle " + total + " bildirim gönderilmedi");
        String breakdown = breakdown(levels, lang);
        if (!breakdown.isEmpty()) sb.append(" (").append(breakdown).append(")");
        sb.append(".");
        if (last != null) {
            String name = last.name() == null || last.name().isBlank() ? "-" : last.name().trim();
            if (last.trigger() != null && last.trigger().startsWith("STORM"))
                name = en ? "Alert storm" : UserPushService.STORM_MONITOR_NAME;
            if (name.length() > 60) name = name.substring(0, 57) + "...";
            boolean resolved = last.trigger() != null && last.trigger().contains("RESOLVE");
            String state = resolved ? (en ? "RESOLVED" : "DÜZELDİ") : levelWord(last.level(), lang);
            sb.append(en ? " Latest: " : " Son: ").append(name).append(" - ").append(state);
            String clock = PushText.istClock(last.createdAtUtc());
            if (!"-".equals(clock)) sb.append(" (").append(clock).append(")");
            sb.append(".");
        }
        sb.append(en ? " Details in SiteMonitor." : " Ayrıntılar SiteMonitor'da.");
        return sb.toString();
    }

    static String breakdown(Map<String, Integer> levels, String lang) {
        if (levels == null || levels.isEmpty()) return "";
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String k : List.of("CRITICAL", "HIGH", "WARNING", "INFO")) ordered.put(k, 0);
        levels.forEach((k, v) -> {
            String key = k == null ? "WARNING" : k.toUpperCase(Locale.ROOT);
            if ("LOW".equals(key)) key = "INFO";
            if (!ordered.containsKey(key)) key = "WARNING";
            ordered.merge(key, v == null ? 0 : v, Integer::sum);
        });
        StringBuilder sb = new StringBuilder();
        ordered.forEach((k, v) -> {
            if (v <= 0) return;
            if (sb.length() > 0) sb.append(", ");
            sb.append(v).append(' ').append(levelNoun(k, lang));
        });
        return sb.toString();
    }
}
