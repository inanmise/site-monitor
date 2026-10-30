package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.metrics.MonitoringMetricsModel;
import com.sitemonitor.service.noc.NocMailComposer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
 * {@code kaynak|||RCI|||SEVERITY}). Bu yüzden:
 * <ul>
 *   <li>RCI bir alarm için kararlıdır (gün sayısı, ileti girmez) ve FARKLI alarm türleri asla aynı RCI'yı almaz: tür etiketi
 *       birden çok türde ortaksa ({@code Yavaş yanıt}) izleme türüyle ayrılır; hedef sorgu / parça / kullanıcı bilgisi atılıp
 *       kısaltıldıysa ham hedefin kısa özeti eklenir ({@code … #a1b2c3}) — iki ayrı URL tek olayda birleşmez;</li>
 *   <li>metinler göreli süre taşımaz (aynı durum = aynı gövde);</li>
 *   <li>kapanış satırı GÖNDERİLMİŞ açık satırın kopyasından kurulur ({@link #closure}) — anahtar değişse de (yeniden
 *       adlandırma, ayar) merkezi sistemdeki eski olay kendi anahtarıyla kapanır.</li>
 * </ul>
 *
 * <p>Zamanlar Türkiye saati, {@code yyyy-MM-dd HH:mm:ss}. Hedef ve iletideki her URL kullanıcı bilgisi ve sorgu olmadan
 * yazılır (uç belirteçsizdir); kişi adı yazılmaz.
 */
public final class AlarmFeedRows {

    private AlarmFeedRows() {}

    public static final List<String> COLUMNS = List.of(
            "CREATEDTIME", "SEVERITY", "RCI", "RCITYPE", "CATEGORY", "FILTERSOURCE", "TEXT", "BODY", "MAILS",
            "STATUS", "RESOLVEDTIME", "ALERTID", "TEAM", "UGTEAM", "LINK");

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** Kapanış nedenleri ({@code alarm_feed_events.closed_reason} ile aynı sözcükler). */
    public static final String REASON_RESOLVED = "RESOLVED";
    public static final String REASON_LEVEL_CHANGE = "LEVEL_CHANGE";
    public static final String REASON_KEY_CHANGE = "KEY_CHANGE";
    public static final String REASON_SCOPE = "SCOPE";
    public static final String REASON_GONE = "GONE";

    /** Kopyadaki iç alanlar (yanıta girmez). */
    static final String SNAP_TARGET = "_target";
    static final String SNAP_PROBLEM = "_problem";

    static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final int RCI_MAX = 250;
    static final int TEXT_MAX = 300;
    static final int BODY_MAX = 2000;
    static final int MESSAGE_MAX = 300;

    /** Bir takımın akışa yazılan bilgisi ({@code emails} = alarm postalarının gittiği adresler). */
    public record TeamInfo(Long id, String name, List<String> emails) {}

    /** Bir alarmın akış için toplanmış hali (veritabanı nesnesinden bağımsız). */
    public record Alarm(long id, String alertType, String level, String target, String message, Integer daysRemaining,
                        String createdAt, boolean resolved, String resolvedAt, String resolvedBy, boolean acknowledged,
                        String acknowledgedAt, Long stormId, String monitorName, TeamInfo team, TeamInfo ugTeam) {}

    /** Birden çok alarm türünün paylaştığı kısa etiketler (bunlar izleme türüyle ayrılır). */
    static final Set<String> SHARED_LABELS = sharedLabels();

    private static Set<String> sharedLabels() {
        Map<String, Integer> n = new HashMap<>();
        for (String t : MonitorTypeCatalog.allAlertTypes()) n.merge(NocMailComposer.problemLabel(t, null), 1, Integer::sum);
        Set<String> out = new LinkedHashSet<>();
        n.forEach((k, v) -> { if (v > 1) out.add(k); });
        return Set.copyOf(out);
    }

    /** Alarm türünün akış etiketi — türler arasında TEKİL ({@code AlarmFeedRowsTest.labelsAreUniquePerAlertType}). */
    public static String typeLabel(String alertType) {
        String p = NocMailComposer.problemLabel(alertType, null);
        if (!SHARED_LABELS.contains(p)) return p;
        String family = MonitorTypeCatalog.typeOfAlert(alertType);
        return p + " (" + (family == null ? "Diğer" : MonitorTypeCatalog.label(family)) + ")";
    }

    /** Açık satır. */
    public static Map<String, Object> openRow(Alarm a, AlarmFeedSettings.Config c, String baseUrl) {
        String target = target(a);
        String label = typeLabel(a.alertType());
        String problemNow = NocMailComposer.problemLabel(a.alertType(), a.daysRemaining());
        String severity = c.severityOf(a.level());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("CREATEDTIME", istanbul(a.createdAt()));
        m.put("SEVERITY", severity);
        m.put("RCI", rci(target, label, c));
        m.put("RCITYPE", rcitype(a, c));
        m.put("CATEGORY", label);
        m.put("FILTERSOURCE", c.source());
        m.put("TEXT", clip("(" + target + ") " + problemNow, TEXT_MAX));
        m.put("BODY", clip(body(a, c, baseUrl, target, problemNow, "Durum: AÇIK", severity), BODY_MAX));
        m.put("MAILS", mails(a, c));
        m.put("STATUS", STATUS_OPEN);
        m.put("RESOLVEDTIME", null);
        m.put("ALERTID", a.id());
        m.put("TEAM", a.team() == null ? null : a.team().name());
        m.put("UGTEAM", a.ugTeam() == null ? null : a.ugTeam().name());
        m.put("LINK", link(baseUrl, a.id()));
        return m;
    }

    /**
     * Hiç açık bildirilmemiş ama pencerede çözülmüş alarmın bilgi satırı (kapanış kopyası yoktur): alarmın güncel
     * verisiyle, STATUS=RESOLVED.
     */
    public static Map<String, Object> resolvedRow(Alarm a, AlarmFeedSettings.Config c, String baseUrl) {
        Map<String, Object> open = openRow(a, c, baseUrl);
        return closure(withSnapshotFields(open, a), c, a.resolvedAt(), REASON_RESOLVED, null, closer(a));
    }

    /** Durum tablosuna yazılacak kopya: açık satır + iç alanlar (hedef, sorun etiketi). */
    public static Map<String, Object> withSnapshotFields(Map<String, Object> openRow, Alarm a) {
        Map<String, Object> m = new LinkedHashMap<>(openRow);
        m.put(SNAP_TARGET, target(a));
        m.put(SNAP_PROBLEM, NocMailComposer.problemLabel(a.alertType(), null));
        return m;
    }

    /**
     * Kapanış satırı — GÖNDERİLMİŞ açık satırın kopyasından. Anahtar parçaları (FILTERSOURCE, RCI, SEVERITY) ve kimlik
     * kolonları kopyadan gelir; yalnız durum, kapanış anı ve metinler değişir. Clear kipinde SEVERITY clear metnidir.
     *
     * @param newSeverity seviye değişiminde YENİ seviyenin metni (yoksa null)
     * @param closer      çözülmede kim kapattı ekiyle (" (otomatik)" …); bilinmiyorsa boş
     */
    public static Map<String, Object> closure(Map<String, Object> snapshot, AlarmFeedSettings.Config c, String closedAtUtc,
                                              String reason, String newSeverity, String closer) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String col : COLUMNS) m.put(col, snapshot.get(col));
        String target = str(snapshot.get(SNAP_TARGET), "—");
        String problem = str(snapshot.get(SNAP_PROBLEM), str(snapshot.get("CATEGORY"), ""));
        String when = istanbul(closedAtUtc);
        String sev = c.clearMode() ? c.clearText() : str(snapshot.get("SEVERITY"), "");
        String head = "(" + target + ") ";
        String text;
        String status;
        switch (reason == null ? REASON_RESOLVED : reason) {
            case REASON_LEVEL_CHANGE -> {
                text = head + "Seviye değişti" + (newSeverity == null ? "" : " (yeni seviye " + newSeverity + ")") + ": " + problem;
                status = "Durum: KAPANDI — seviye değişti" + (newSeverity == null ? "" : ", yeni seviye " + newSeverity)
                        + " (" + nz(when) + ")";
            }
            case REASON_KEY_CHANGE -> {
                text = head + "Kayıt yeni anahtarla sürüyor: " + problem;
                status = "Durum: KAPANDI — alarm hâlâ açık; hedef adı ya da akış biçimi değiştiği için yeni bir satırla "
                        + "sürüyor (" + nz(when) + ")";
            }
            case REASON_SCOPE -> {
                text = head + "Akış kapsamından çıktı: " + problem;
                status = "Durum: KAPANDI — alarm hâlâ açık ama artık bu akışın kapsamında değil (" + nz(when) + ")";
            }
            case REASON_GONE -> {
                text = head + "Alarm kaydı silindi: " + problem;
                status = "Durum: KAPANDI — alarm kaydı SiteMonitor'den silindi (" + nz(when) + ")";
            }
            default -> {
                text = head + "ÇÖZÜLDÜ: " + problem;
                status = "Durum: ÇÖZÜLDÜ — " + nz(when) + (closer == null ? "" : closer);
            }
        }
        m.put("SEVERITY", sev);
        m.put("TEXT", clip(text, TEXT_MAX));
        m.put("BODY", clip(replaceFirstLine(str(snapshot.get("BODY"), ""), status + " · Seviye: " + sev), BODY_MAX));
        m.put("STATUS", STATUS_RESOLVED);
        m.put("RESOLVEDTIME", when);
        return m;
    }

    /** Merkezi sistemin olay anahtarı (bizim tarafta yalnız eşitlik için): FILTERSOURCE|||RCI|||SEVERITY. */
    public static String eventKey(Map<String, Object> row) {
        return str(row.get("FILTERSOURCE"), "") + "|||" + str(row.get("RCI"), "") + "|||" + str(row.get("SEVERITY"), "");
    }

    /** Clear kipinde kapanışın eşleşme anahtarı: FILTERSOURCE|||RCI. */
    public static String rciKey(Map<String, Object> row) {
        return str(row.get("FILTERSOURCE"), "") + "|||" + str(row.get("RCI"), "");
    }

    public static String keyHash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String rci(String target, String label, AlarmFeedSettings.Config c) {
        return c.targetOnlyRci() ? clip(target, RCI_MAX) : clip(target + " · " + label, RCI_MAX);
    }

    static String rcitype(Alarm a, AlarmFeedSettings.Config c) {
        if (c.rcitype() != null && !c.rcitype().isBlank()) return c.rcitype();
        String family = MonitorTypeCatalog.typeOfAlert(a.alertType());
        return family == null ? "Diğer" : MonitorTypeCatalog.label(family);
    }

    static String body(Alarm a, AlarmFeedSettings.Config c, String baseUrl, String target, String problemNow,
                       String status, String severity) {
        List<String> lines = new ArrayList<>();
        lines.add(status + " · Seviye: " + severity);
        lines.add("Kaynak: " + c.source() + " · " + rcitype(a, c) + " izleme");
        String mon = a.monitorName();
        lines.add("Hedef: " + target
                + (mon != null && !mon.isBlank() && !mon.trim().equalsIgnoreCase(target) ? " · İzleme: " + oneLine(mon.trim()) : ""));
        String msg = oneLine(safeText(a.message()));
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
    public static String closer(Alarm a) {
        String by = a == null ? null : a.resolvedBy();
        if (by == null || by.isBlank() || by.equalsIgnoreCase("system") || by.equals("Sistem (otomatik)")) {
            return " (otomatik)";
        }
        if (by.startsWith("Sistem")) {
            int open = by.indexOf('(');
            int close = by.lastIndexOf(')');
            String why = open >= 0 && close > open ? by.substring(open + 1, close).trim() : "";
            return why.isEmpty() ? " (sistem)" : " (sistem: " + clip(oneLine(why), 80) + ")";
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

    /**
     * Gösterilen hedef: şema + host + port + yol (sorgu, parça, kullanıcı bilgisi yok; 160 karakter). Bu kırpma ham hedefi
     * değiştirdiyse ham hedefin SHA-256 özetinin ilk 6 hanesi eklenir — sorgusu farklı iki URL tek RCI'da birleşmesin.
     */
    static String target(Alarm a) {
        String raw = a.target() == null ? "" : a.target().trim();
        // Yalnız URL'ler ayrıştırılır; host / alan adı / k6 izleme adı (içinde '@', '?', '#' olabilir) olduğu gibi kalır.
        String t = raw.contains("://") ? MonitoringMetricsModel.safeTarget(raw) : MonitoringMetricsModel.label(raw);
        if (t == null || t.isBlank()) return "—";
        if (!t.equals(raw)) t = t + " #" + keyHash(raw).substring(0, 6);
        return t;
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

    /**
     * Serbest metindeki her {@code http(s)://} adresinden kullanıcı bilgisi, sorgu ve parça atılır; kalan ad=değer
     * sırları {@link SecretMask#maskUrlQuery} ile maskelenir. Doğrusal tarama (düzenli ifade yok); önce uzunluk sınırı.
     */
    static String safeText(String s) {
        if (s == null) return null;
        String in = s.length() > 4000 ? s.substring(0, 4000) : s;
        StringBuilder out = new StringBuilder(in.length());
        int i = 0;
        int n = in.length();
        while (i < n) {
            int start = urlStart(in, i);
            if (start < 0) {
                out.append(in, i, n);
                break;
            }
            out.append(in, i, start);
            int end = start;
            while (end < n && !urlEnd(in.charAt(end))) end++;
            String url = in.substring(start, end);
            out.append(MonitoringMetricsModel.safeTarget(url));
            i = end;
        }
        return SecretMask.maskUrlQuery(out.toString());
    }

    private static int urlStart(String s, int from) {
        for (int i = from; i + 7 <= s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != 'h' && ch != 'H') continue;
            if (s.regionMatches(true, i, "http://", 0, 7)) return i;
            if (i + 8 <= s.length() && s.regionMatches(true, i, "https://", 0, 8)) return i;
        }
        return -1;
    }

    private static boolean urlEnd(char c) {
        return Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>' || c == ')' || c == ']'
                || c == '}' || c == ',' || Character.isISOControl(c);
    }

    static String replaceFirstLine(String body, String first) {
        int nl = body.indexOf('\n');
        return nl < 0 ? first : first + body.substring(nl);
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

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
