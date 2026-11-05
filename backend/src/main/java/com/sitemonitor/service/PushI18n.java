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

    // ── Alarm iletimi (2026-11-05) ─────────────────────────────────────────────────────────────────

    /**
     * İletim önekindeki sahip takım adları parçasının tavanı — push mesajı 80–320 karakter; 60+ karakterlik AD takım adı
     * alarm metnini yutmasın. Tavanı aşan parça {@code "..."} ile biter.
     */
    static final int FORWARD_OWNER_MAX = 50;

    /**
     * İletim önekinin sahip takım parçası: adlar kırpılır, denetim karakterleri ve iki nokta boşluğa döner (önek, çözüm
     * push'unda önceki satırdan geri okunur — {@link #forwardOwnerOf}; ad içindeki ":" ayırıcıyla karışmasın), tekil, ", " ile
     * birleşir, {@link #FORWARD_OWNER_MAX} tavanı. Hiç ad yoksa null.
     */
    static String forwardOwnerLabel(List<String> ownerTeamNames) {
        if (ownerTeamNames == null || ownerTeamNames.isEmpty()) return null;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String raw : ownerTeamNames) {
            String n = sanitizeOwner(raw);
            if (n != null) names.add(n);
        }
        if (names.isEmpty()) return null;
        String joined = String.join(", ", names);
        return joined.length() > FORWARD_OWNER_MAX ? joined.substring(0, FORWARD_OWNER_MAX - 3).trim() + "..." : joined;
    }

    private static String sanitizeOwner(String raw) {
        if (raw == null) return null;
        String s = raw.length() > 300 ? raw.substring(0, 300) : raw;
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean blank = Character.isWhitespace(c) || Character.isISOControl(c) || c == ':';
            if (blank) { space = sb.length() > 0; continue; }
            if (space) { sb.append(' '); space = false; }
            sb.append(c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Önekin sahip parçası ve çoğulluğu — çözüm push'unda önceki iletim satırından geri okunan biçim. */
    public record ForwardOwner(String label, boolean plural) { }

    /** İletim push'unun öneki — sahip takım adlarından ({@link #forwardOwnerLabel}). */
    public static String forwardPrefix(List<String> ownerTeamNames, String lang) {
        String label = forwardOwnerLabel(ownerTeamNames);
        long distinct = ownerTeamNames == null ? 0
                : ownerTeamNames.stream().map(PushI18n::sanitizeOwner).filter(java.util.Objects::nonNull).distinct().count();
        return forwardPrefixFor(new ForwardOwner(label, distinct > 1), lang);
    }

    /**
     * İletim öneki: tr {@code "Ödeme Ekibi takımından: "} / çoğul {@code "A, B takımlarından: "}; en {@code "From team X: "} /
     * {@code "From teams A, B: "}. Sahip bilinmiyorsa tr {@code "İletilen alarm: "}, en {@code "Forwarded alarm: "}.
     */
    public static String forwardPrefixFor(ForwardOwner owner, String lang) {
        boolean en = isEn(lang);
        String label = owner == null ? null : owner.label();
        if (label == null || label.isBlank()) return en ? "Forwarded alarm: " : "İletilen alarm: ";
        if (en) return (owner.plural() ? "From teams " : "From team ") + label + ": ";
        return label + (owner.plural() ? " takımlarından: " : " takımından: ");
    }

    /**
     * Önceki iletim satırının mesajından önekin sahip parçası (çözüm push'u aynı öneki kişinin GÜNCEL dilinde yeniden kurar).
     * İletim öneki değilse null. Arama yalnız mesajın başında, tavanlı (ad parçası {@link #FORWARD_OWNER_MAX}).
     */
    public static ForwardOwner forwardOwnerOf(String message) {
        if (message == null || message.isEmpty()) return null;
        if (message.startsWith("İletilen alarm: ") || message.startsWith("Forwarded alarm: ")) return new ForwardOwner(null, false);
        int window = Math.min(message.length(), FORWARD_OWNER_MAX + 20);
        String head = message.substring(0, window);
        for (String[] p : new String[][]{{"From teams ", "t"}, {"From team ", "f"}}) {
            if (!head.startsWith(p[0])) continue;
            int end = head.indexOf(": ", p[0].length());
            if (end <= p[0].length()) return null;
            return new ForwardOwner(head.substring(p[0].length(), end), "t".equals(p[1]));
        }
        // Sahip parçası ":" taşımaz (forwardOwnerLabel boşluğa çevirir) — işaretten önce ":" varsa önek değildir
        int plural = head.indexOf(" takımlarından: ");
        if (plural > 0 && head.lastIndexOf(':', plural) < 0) return new ForwardOwner(head.substring(0, plural), true);
        int single = head.indexOf(" takımından: ");
        if (single > 0 && head.lastIndexOf(':', single) < 0) return new ForwardOwner(head.substring(0, single), false);
        return null;
    }

    /**
     * İletilen fırtınanın toplu push metni: tr {@code "Ödeme Ekibi takımının fırtınası: size iletilen 5 alarm (KRİTİK)."}
     * (günlük tekrar "… fırtınası sürüyor: …", çözüm "… fırtınası sona erdi."); en {@code "Storm of team X: 5 alarms
     * forwarded to you (CRITICAL)."}. Bağlantı YOK (push kurum ağı dışında okunur).
     *
     * @param stormTrigger INITIAL | DAILY_REALERT | RESOLVE
     */
    public static String forwardStormText(String ownerTeamName, int memberCount, String level, String stormTrigger,
                                          String lang) {
        boolean en = isEn(lang);
        String owner = forwardOwnerLabel(ownerTeamName == null ? List.of() : List.of(ownerTeamName));
        int n = Math.max(0, memberCount);
        if ("RESOLVE".equals(stormTrigger)) {
            if (owner == null) return en ? "The forwarded alert storm has ended." : "İletilen alarm fırtınası sona erdi.";
            return en ? "Storm of team " + owner + " has ended." : owner + " takımının fırtınası sona erdi.";
        }
        boolean daily = "DAILY_REALERT".equals(stormTrigger);
        String lvl = levelWord(level, lang);
        if (en) {
            String head = owner == null ? "Alert storm" : "Storm of team " + owner;
            return head + (daily ? " continues: " : ": ") + n + (n == 1 ? " alarm" : " alarms")
                    + " forwarded to you (" + lvl + ").";
        }
        String head = owner == null ? "Alarm fırtınası" : owner + " takımının fırtınası";
        return head + (daily ? " sürüyor: " : ": ") + "size iletilen " + n + " alarm (" + lvl + ").";
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
