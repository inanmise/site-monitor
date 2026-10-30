package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.metrics.MonitoringMetricsModel;
import com.sitemonitor.service.noc.NocMailComposer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Alarm akışı satırlarını kuran SAF sınıf (2026-10-30) — veritabanına dokunmaz; {@link AlarmFeedService} veriyi toplar.
 *
 * <p>Kolonlar merkezi izleme ekibinin istediği sırayla ({@code CREATEDTIME … MAILS}) + bizim ek kolonlarımız ({@code STATUS},
 * {@code RESOLVEDTIME}, {@code ALERTID}, {@code TEAM}, {@code UGTEAM}, {@code LINK}). EVENTKEY gönderilmez (ekip kendisi kurar:
 * {@code kaynak|||RCI|||SEVERITY}) — bu yüzden RCI bir alarmın ömrü boyunca DEĞİŞMEZ (gün sayısı, ileti RCI'ya girmez) ve
 * metinler göreli süre ("N dakikadır") taşımaz: aynı alarm durumu = aynı satır (yanıt gövdeleri özetle tekilleşir).
 *
 * <p>Zamanlar Türkiye saati, {@code yyyy-MM-dd HH:mm:ss} (örnek veri biçimi). Hedef adresi sorgu / parça / kullanıcı bilgisi
 * olmadan yazılır ({@link MonitoringMetricsModel#safeTarget}); ileti içindeki hassas sorgu parametreleri maskelenir.
 */
public final class AlarmFeedRows {

    private AlarmFeedRows() {}

    public static final List<String> COLUMNS = List.of(
            "CREATEDTIME", "SEVERITY", "RCI", "RCITYPE", "CATEGORY", "FILTERSOURCE", "TEXT", "BODY", "MAILS",
            "STATUS", "RESOLVEDTIME", "ALERTID", "TEAM", "UGTEAM", "LINK");

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** Kapanış nedenleri ({@code alarm_feed_severities.closed_reason} ile aynı sözcükler). */
    public static final String REASON_RESOLVED = "RESOLVED";
    public static final String REASON_LEVEL_CHANGE = "LEVEL_CHANGE";
    public static final String REASON_SCOPE = "SCOPE";
    public static final String REASON_GONE = "GONE";

    static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final int RCI_MAX = 250;
    static final int TEXT_MAX = 300;
    static final int BODY_MAX = 2000;
    static final int MESSAGE_MAX = 300;

    /** Bir takımın akışa yazılan bilgisi. */
    public record TeamInfo(Long id, String name, List<String> emails) {}

    /** Bir alarmın akış için toplanmış hali (veritabanı nesnesinden bağımsız). */
    public record Alarm(long id, String alertType, String level, String target, String message, Integer daysRemaining,
                        String createdAt, boolean resolved, String resolvedAt, String resolvedBy, boolean acknowledged,
                        String acknowledgedAt, Long stormId, String monitorName, TeamInfo team, TeamInfo ugTeam) {}

    /**
     * Tek satır.
     *
     * @param level      satırın SiteMonitor seviyesi (kapanmış eski seviye satırında o ESKİ seviye)
     * @param open       true → STATUS=OPEN
     * @param closedAt   kapanış anı (UTC ISO) — RESOLVED satırında
     * @param reason     kapanış nedeni ({@code REASON_*})
     * @param newLevel   LEVEL_CHANGE satırında alarmın YENİ seviyesi (yoksa null)
     */
    public static Map<String, Object> row(Alarm a, AlarmFeedSettings.Config c, String baseUrl, String level, boolean open,
                                          String closedAt, String reason, String newLevel) {
        String target = target(a);
        String family = MonitorTypeCatalog.typeOfAlert(a.alertType());
        String typeLabel = family == null ? "Diğer" : MonitorTypeCatalog.label(family);
        String problem = NocMailComposer.problemLabel(a.alertType(), null);
        String problemNow = NocMailComposer.problemLabel(a.alertType(), open ? a.daysRemaining() : null);
        boolean clearRow = !open && c.clearMode();
        String severity = clearRow ? c.clearText() : c.severityOf(level);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("CREATEDTIME", istanbul(a.createdAt()));
        m.put("SEVERITY", severity);
        m.put("RCI", c.targetOnlyRci() ? clip(target, RCI_MAX) : clip(target + " · " + problem, RCI_MAX));
        m.put("RCITYPE", c.rcitype() == null || c.rcitype().isBlank() ? typeLabel : c.rcitype());
        m.put("CATEGORY", problem);
        m.put("FILTERSOURCE", c.source());
        m.put("TEXT", clip(text(a, target, problem, problemNow, open, reason, c, newLevel), TEXT_MAX));
        m.put("BODY", clip(body(a, c, baseUrl, target, typeLabel, problemNow, severity, open, closedAt, reason, newLevel),
                BODY_MAX));
        m.put("MAILS", mails(a, c));
        m.put("STATUS", open ? STATUS_OPEN : STATUS_RESOLVED);
        m.put("RESOLVEDTIME", open ? null : istanbul(closedAt));
        m.put("ALERTID", a.id());
        m.put("TEAM", a.team() == null ? null : a.team().name());
        m.put("UGTEAM", a.ugTeam() == null ? null : a.ugTeam().name());
        m.put("LINK", link(baseUrl, a.id()));
        return m;
    }

    static String text(Alarm a, String target, String problem, String problemNow, boolean open, String reason,
                       AlarmFeedSettings.Config c, String newLevel) {
        String head = "(" + target + ") ";
        if (open) return head + problemNow;
        return switch (reason == null ? REASON_RESOLVED : reason) {
            case REASON_LEVEL_CHANGE -> head + "Seviye değişti"
                    + (newLevel == null ? "" : " (yeni seviye " + c.severityOf(newLevel) + ")") + ": " + problem;
            case REASON_SCOPE -> head + "Akış kapsamından çıktı: " + problem;
            default -> head + "ÇÖZÜLDÜ: " + problem;
        };
    }

    static String body(Alarm a, AlarmFeedSettings.Config c, String baseUrl, String target, String typeLabel,
                       String problemNow, String severity, boolean open, String closedAt, String reason, String newLevel) {
        List<String> lines = new ArrayList<>();
        String status;
        if (open) {
            status = "Durum: AÇIK";
        } else {
            String when = istanbul(closedAt);
            status = switch (reason == null ? REASON_RESOLVED : reason) {
                case REASON_LEVEL_CHANGE -> "Durum: KAPANDI — seviye değişti"
                        + (newLevel == null ? "" : ", yeni seviye " + c.severityOf(newLevel)) + " (" + when + ")";
                case REASON_SCOPE -> "Durum: KAPANDI — alarm hâlâ açık ama artık bu akışın kapsamında değil (" + when + ")";
                default -> "Durum: ÇÖZÜLDÜ — " + when + closer(a);
            };
        }
        lines.add(status + " · Seviye: " + severity);
        lines.add("Kaynak: " + c.source() + " · " + typeLabel + " izleme");
        String mon = a.monitorName();
        lines.add("Hedef: " + target
                + (mon != null && !mon.isBlank() && !mon.trim().equalsIgnoreCase(target) ? " · İzleme: " + mon.trim() : ""));
        String msg = oneLine(SecretMask.maskUrlQuery(a.message()));
        String what = "Ne oldu: " + problemNow;
        if (msg != null && !msg.isBlank() && !msg.equalsIgnoreCase(problemNow)) what += " — " + clip(msg, MESSAGE_MAX);
        lines.add(what);
        lines.add("Başlangıç: " + nz(istanbul(a.createdAt())) + " (TSİ)");
        lines.add("Takım: " + teamLine(a.team()) + (a.ugTeam() == null ? "" : " · UG: " + teamLine(a.ugTeam())));
        List<String> notes = new ArrayList<>();
        if (a.acknowledged()) {
            String at = istanbul(a.acknowledgedAt());
            notes.add("Onaylandı" + (at == null ? "" : " (" + at + ")"));
        }
        if (a.stormId() != null) notes.add("Alarm fırtınası #" + a.stormId() + " üyesi");
        if (!notes.isEmpty()) lines.add("Not: " + String.join(" · ", notes));
        String link = link(baseUrl, a.id());
        lines.add("Alarm #" + a.id() + (link == null ? "" : " · " + link));
        return String.join("\n", lines);
    }

    /** Kim kapattı: kişi ADI yazılmaz (herkese açık uç) — otomatik / sistem gerekçesi / elle. */
    static String closer(Alarm a) {
        String by = a.resolvedBy();
        if (by == null || by.isBlank() || by.equalsIgnoreCase("system") || by.equals("Sistem (otomatik)")) {
            return " (otomatik)";
        }
        if (by.startsWith("Sistem")) {
            int open = by.indexOf('(');
            int close = by.lastIndexOf(')');
            String why = open >= 0 && close > open ? by.substring(open + 1, close).trim() : "";
            return why.isEmpty() ? " (sistem)" : " (sistem: " + why + ")";
        }
        return " (elle kapatıldı)";
    }

    static String teamLine(TeamInfo t) {
        if (t == null) return "atanmamış";
        String name = t.name() == null || t.name().isBlank() ? ("#" + t.id()) : t.name();
        return t.emails() == null || t.emails().isEmpty() ? name + " (e-posta tanımlı değil)"
                : name + " <" + String.join(", ", t.emails()) + ">";
    }

    static String mails(Alarm a, AlarmFeedSettings.Config c) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (TeamInfo t : new TeamInfo[]{a.team(), a.ugTeam()}) {
            if (t == null || t.emails() == null) continue;
            for (String e : t.emails()) {
                if (e != null && !e.isBlank() && seen.add(e.toLowerCase(Locale.ROOT))) out.add(e.trim());
            }
        }
        return out.isEmpty() ? null : String.join(c.separator(), out);
    }

    static String target(Alarm a) {
        String t = MonitoringMetricsModel.safeTarget(a.target());
        return t == null || t.isBlank() ? "—" : t;
    }

    static String link(String baseUrl, long id) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        return baseUrl + "/?tab=alerthistory&alert=" + id;
    }

    /** UTC ISO metni ({@code yyyy-MM-dd'T'HH:mm:ss}, kesirli / Z'li olabilir) → Türkiye saati; okunamazsa null. */
    public static String istanbul(String utcIso) {
        if (utcIso == null || utcIso.isBlank()) return null;
        String s = utcIso.trim();
        try {
            String local = s.length() > 19 ? s.substring(0, 19) : s;
            LocalDateTime t = LocalDateTime.parse(local.replace(' ', 'T'));
            return t.atOffset(ZoneOffset.UTC).atZoneSameInstant(ISTANBUL).format(OUT);
        } catch (Exception e) {
            return null;
        }
    }

    static String oneLine(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(Math.min(s.length(), 4000));
        boolean space = false;
        int n = Math.min(s.length(), 4000);
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                if (!space && b.length() > 0) b.append(' ');
                space = true;
            } else {
                b.append(ch);
                space = false;
            }
        }
        return b.toString().trim();
    }

    static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
