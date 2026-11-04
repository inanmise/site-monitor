package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aktivite akışının (Kayıtlar → Aktivite) SÜZGEÇ modeli — liste, özet ve dışa aktarım uçlarının TEK kuralı (2026-11-05,
 * aktivite zenginleştirmesi E). İstek parametreleri burada doğrulanır ve TEK bir SQL {@code WHERE} parçasına çevrilir;
 * aynı süzgeç üç uçta birebir aynı satırları seçer (ekranda 30 satır, dosyada 31 olamaz).
 *
 * <p><b>Eski parametreler birebir:</b> {@code type} (CSV), {@code monitorId}, {@code status} (tek değer de geçerli CSV),
 * {@code from} / {@code to} ({@code yyyy-MM-ddTHH:mm:ss}, UTC — istemcinin bugünkü biçimi), {@code q} (serbest metin,
 * {@code %} / {@code _} bugünkü gibi joker). <b>Yeni (eklemeli):</b> {@code status} CSV (ör. {@code ERROR,TIMEOUT}),
 * {@code from} / {@code to} serbest ISO (bölgeli / bölgesiz / yalnız tarih — tarih İstanbul günüdür), {@code monitor=TÜR:kimlik},
 * {@code teamId} (kapsamla KESİŞİR, asla genişletmez), {@code actor} (tam eşleşme, büyük/küçük harf duyarsız),
 * {@code group} CSV ({@link #GROUPS}), {@code reason} CSV (hata nedeni kodu; eski satırlar için {@code class:<ERROR_CLASS>}).
 *
 * <p><b>Takım kapsamı</b> çağırandan gelir (oturumdan türetilir) — istemcinin {@code teamId}'si yalnız daraltır.
 *
 * <p><b>Kolon notu:</b> {@code failure_reason} kolonu aktivite satırına 2026-11-05'te eklendi (zenginleştirmenin yazıcı
 * kısmı); daha eski satırlarda boştur, onlar için hata sınıfı ({@code error_class}) {@code class:} önekiyle süzülür.
 * Sorgu, dizin dostu kalsın diye iki biçimi ayrı tutar: yalnız kod verilirse {@code failure_reason IN (…)}.
 */
public record ActivityFilter(
        boolean scoped, List<Long> scope,
        List<String> types, String monitorType, Long monitorId,
        List<String> statuses, String from, String to,
        Long teamId, String actor, List<String> groups,
        List<String> reasons, List<String> reasonClasses, String q) {

    /** Özet sayımlarında "kendi süzgeci hariç" tutulabilen boyutlar. */
    public enum Dim { TYPE, STATUS, GROUP }

    /** Olay grupları (sıra = arayüz çip sırası). Adlar KALICI — ön yüz URL'de taşır. */
    public static final List<String> GROUPS = List.of("check", "alarm", "notification", "change", "other");

    /** Kontrol satırları (zamanlanmış + elle). */
    public static final List<String> CHECK_ACTIONS = List.of("SCHEDULED_CHECK", "MANUAL_CHECK");
    /** Değişiklik satırları ({@code RENEWAL_*} önekiyle birlikte). {@code UPDATED} eski anahtar — yazılmıyor, değişiklik sayılır. */
    public static final List<String> CHANGE_ACTIONS = List.of("CREATED", "DELETED", "CONFIG_CHANGED", "PAUSED", "RESUMED", "UPDATED");
    /** Bildirim satırları ({@code NOC_*} önekiyle birlikte). */
    public static final List<String> NOTIFICATION_ACTIONS = List.of("EXPIRY_REMINDER");
    static final String ALARM_PREFIX = "ALERT_", NOC_PREFIX = "NOC_", RENEWAL_PREFIX = "RENEWAL_";

    /** Bilinen durum kodları (aktivite sözlüğü sabit). Bilinmeyen değer reddedilmez — eskisi gibi hiçbir satırla eşleşmez. */
    public static final List<String> STATUSES = List.of("SUCCESS", "WARNING", "ERROR", "TIMEOUT", "UNKNOWN");

    /** Eski satırlar için hata sınıfı süzgecinin öneki: {@code reason=class:NETWORK}. */
    public static final String CLASS_PREFIX = "class:";

    // ── Sınırlar (istek gövdesine düzenli ifade uygulanmadan ÖNCE uzunluk denetlenir) ──
    static final int MAX_TYPES = 20, MAX_STATUSES = 10, MAX_REASONS = 50, MAX_CSV_CHARS = 2_000;
    static final int MAX_Q = 200, MAX_ACTOR = 100, MAX_TIME_CHARS = 40;

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z_]{0,19}");
    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z_]{0,19}");
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static final Pattern MONITOR = Pattern.compile("([A-Za-z]{1,20}):([0-9]{1,18})");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final ZoneId TZ = ZoneId.of("Europe/Istanbul");

    /** Ham istek parametreleri (hepsi isteğe bağlı). */
    public record Params(String type, Long monitorId, String monitor, String status, String from, String to,
                         Long teamId, String actor, String group, String reason, String q) {

        /** Yalnız eski parametreler (testler / iç çağrılar). */
        public static Params legacy(String type, Long monitorId, String status, String from, String to, String q) {
            return new Params(type, monitorId, null, status, from, to, null, null, null, null, q);
        }
    }

    /**
     * Parametreleri doğrular ve süzgeci kurar. Hatalı değer → 400 {@code VALIDATION_FAILED} + {@code field}
     * ({@link FieldValidationException}). Ters aralık ({@code from > to}) → alan {@code from}.
     */
    public static ActivityFilter parse(Params p, boolean scoped, List<Long> scope) {
        List<String> types = new ArrayList<>(csv(p.type(), "type", MAX_TYPES, TYPE, true));
        String monitorType = null;
        Long monitorId = p.monitorId();
        String mon = nil(p.monitor());
        if (mon != null) {
            if (mon.length() > 40) throw invalidMonitor();
            var m = MONITOR.matcher(mon);
            if (!m.matches()) throw invalidMonitor();
            monitorType = m.group(1).toUpperCase(Locale.ROOT);
            try {
                monitorId = Long.valueOf(m.group(2));
            } catch (NumberFormatException e) {
                throw invalidMonitor();
            }
        }
        List<String> statuses = csv(p.status(), "status", MAX_STATUSES, STATUS, true);
        String from = normalizeTime(p.from(), false, "from");
        String to = normalizeTime(p.to(), true, "to");
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new FieldValidationException("from", Msg.t(
                    "Başlangıç zamanı bitişten sonra olamaz. Aralığı düzeltip yeniden deneyin.",
                    "The start time can't be after the end time. Fix the range and try again."));
        }
        String actor = nil(p.actor());
        if (actor != null) {
            if (actor.length() > MAX_ACTOR) {
                throw new FieldValidationException("actor", Msg.t(
                        "Kişi adı en çok " + MAX_ACTOR + " karakter olabilir.",
                        "The person name can be at most " + MAX_ACTOR + " characters."));
            }
            actor = actor.toLowerCase(Locale.ROOT);
        }
        List<String> groups = csv(p.group(), "group", GROUPS.size(), null, false).stream()
                .map(g -> g.toLowerCase(Locale.ROOT)).distinct().toList();
        for (String g : groups) {
            if (!GROUPS.contains(g)) {
                throw new FieldValidationException("group", Msg.t(
                        "Olay grubu tanınmadı: " + g + ". Geçerli gruplar: " + String.join(", ", GROUPS) + ".",
                        "Unknown event group: " + g + ". Valid groups: " + String.join(", ", GROUPS) + "."));
            }
        }
        List<String> reasons = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        for (String r : csv(p.reason(), "reason", MAX_REASONS, null, false)) {
            boolean cls = r.regionMatches(true, 0, CLASS_PREFIX, 0, CLASS_PREFIX.length());
            String code = cls ? r.substring(CLASS_PREFIX.length()) : r;
            if (!REASON.matcher(code).matches()) {
                throw new FieldValidationException("reason", Msg.t(
                        "Hata nedeni kodu geçersiz: " + abbreviate(r) + ". Listeden bir neden seçin.",
                        "Invalid failure reason code: " + abbreviate(r) + ". Pick a reason from the list."));
            }
            (cls ? classes : reasons).add(code.toUpperCase(Locale.ROOT));
        }
        String q = nil(p.q());
        if (q != null && q.length() > MAX_Q) {
            throw new FieldValidationException("q", Msg.t(
                    "Arama metni en çok " + MAX_Q + " karakter olabilir.",
                    "The search text can be at most " + MAX_Q + " characters."));
        }
        List<Long> sc = new ArrayList<>();
        if (scoped && scope != null) for (Long id : scope) if (id != null) sc.add(id);   // null takım = kapsam dışı
        return new ActivityFilter(scoped, java.util.Collections.unmodifiableList(sc),
                List.copyOf(types), monitorType, monitorId, statuses, from, to, p.teamId(), actor,
                groups, List.copyOf(new LinkedHashSet<>(reasons)), List.copyOf(new LinkedHashSet<>(classes)),
                q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%");
    }

    /** Süzgecin SQL hâli: {@code WHERE …} metni + sırayla bağlanacak değerler. */
    public record Sql(String where, List<Object> args) {
        /** Ek koşul (ör. anahtar kümeli sayfa sınırı, seri başlangıcı) — yeni nesne. */
        public Sql and(String condition, Object... values) {
            List<Object> a = new ArrayList<>(args);
            a.addAll(List.of(values));
            return new Sql(where + " AND " + condition, List.copyOf(a));
        }

        public Object[] array() { return args.toArray(); }
    }

    /** Tüm süzgeçler. */
    public Sql where() { return where(EnumSet.noneOf(Dim.class)); }

    /**
     * {@code exclude}'daki boyutlar HARİÇ tüm süzgeçler. Kapsam, tarih, izleme, takım, kişi, neden ve arama her zaman
     * uygulanır. Sabit eylem listeleri SQL'e düz metin yazılır (kullanıcı girdisi değil); kullanıcı değerleri bağlanır.
     */
    public Sql where(Set<Dim> exclude) {
        List<String> c = new ArrayList<>();
        List<Object> a = new ArrayList<>();
        c.add("1 = 1");
        if (scoped) {
            if (scope.isEmpty()) {
                c.add("1 = 0");                                 // kapsamsız → hiçbir satır (çağıran zaten sorgulamaz)
            } else {
                c.add("team_id IN (" + marks(scope.size()) + ")");
                a.addAll(scope);
            }
        }
        if (teamId != null) {
            c.add("team_id = ?");
            a.add(teamId);
        }
        if (!exclude.contains(Dim.TYPE) && !types.isEmpty()) {
            c.add("monitor_type IN (" + marks(types.size()) + ")");
            a.addAll(types);
        }
        if (monitorType != null) {
            c.add("monitor_type = ?");
            a.add(monitorType);
        }
        if (monitorId != null) {
            c.add("monitor_id = ?");
            a.add(monitorId);
        }
        if (!exclude.contains(Dim.STATUS) && !statuses.isEmpty()) {
            c.add("result_status IN (" + marks(statuses.size()) + ")");
            a.addAll(statuses);
        }
        if (from != null) {
            c.add("activity_time >= ?");
            a.add(from);
        }
        if (to != null) {
            c.add("activity_time <= ?");
            a.add(to);
        }
        if (actor != null) {
            c.add("LOWER(actor) = ?");
            a.add(actor);
        }
        if (!exclude.contains(Dim.GROUP) && !groups.isEmpty()) {
            List<String> or = new ArrayList<>();
            for (String g : groups) or.add(groupSql(g));
            c.add("(" + String.join(" OR ", or) + ")");
        }
        if (!reasons.isEmpty() || !reasonClasses.isEmpty()) {
            List<String> or = new ArrayList<>();
            if (!reasons.isEmpty()) {
                or.add("failure_reason IN (" + marks(reasons.size()) + ")");
                a.addAll(reasons);
            }
            if (!reasonClasses.isEmpty()) {
                or.add("(failure_reason IS NULL AND error_class IN (" + marks(reasonClasses.size()) + "))");
                a.addAll(reasonClasses);
            }
            c.add(or.size() == 1 ? or.get(0) : "(" + String.join(" OR ", or) + ")");
        }
        if (q != null) {
            c.add("(LOWER(target) LIKE ? OR LOWER(monitor_name) LIKE ? OR LOWER(error_message) LIKE ?)");
            a.add(q);
            a.add(q);
            a.add(q);
        }
        return new Sql("WHERE " + String.join(" AND ", c), List.copyOf(a));
    }

    /** Eylem → olay grubu ({@link #groupSql} ile birebir; testte gerçek satırlarla karşılaştırılır). */
    public static String groupOf(String action) {
        if (action == null) return "other";
        if (CHECK_ACTIONS.contains(action)) return "check";
        if (action.startsWith(ALARM_PREFIX)) return "alarm";
        if (action.startsWith(NOC_PREFIX) || NOTIFICATION_ACTIONS.contains(action)) return "notification";
        if (CHANGE_ACTIONS.contains(action) || action.startsWith(RENEWAL_PREFIX)) return "change";
        return "other";
    }

    /** Bir grubun SQL koşulu — yalnız sabitlerden kurulur. {@code !} kaçış karakteri ({@code _} LIKE jokeri değil). */
    static String groupSql(String group) {
        String check = "action IN (" + literals(CHECK_ACTIONS) + ")";
        String alarm = "action LIKE '" + ALARM_PREFIX.replace("_", "!_") + "%' ESCAPE '!'";
        String notification = "(action LIKE '" + NOC_PREFIX.replace("_", "!_") + "%' ESCAPE '!' OR action IN ("
                + literals(NOTIFICATION_ACTIONS) + "))";
        String change = "(action IN (" + literals(CHANGE_ACTIONS) + ") OR action LIKE '"
                + RENEWAL_PREFIX.replace("_", "!_") + "%' ESCAPE '!')";
        return switch (group) {
            case "check" -> check;
            case "alarm" -> alarm;
            case "notification" -> notification;
            case "change" -> change;
            default -> "NOT (" + check + " OR " + alarm + " OR " + notification + " OR " + change + ")";
        };
    }

    /** Herhangi bir süzgeç (kapsam hariç) verildi mi — denetim kaydı ve testler için. */
    public boolean hasFilters() {
        return !types.isEmpty() || monitorType != null || monitorId != null || !statuses.isEmpty() || from != null
                || to != null || teamId != null || actor != null || !groups.isEmpty() || !reasons.isEmpty()
                || !reasonClasses.isEmpty() || q != null;
    }

    // ── Zaman ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Serbest ISO zamanı → {@code yyyy-MM-ddTHH:mm:ss} (UTC, aktivite kolonuyla karşılaştırılabilir metin). Kabul edilen:
     * bölgeli ({@code …Z}, {@code …+03:00}), bölgesiz ({@code 2026-11-05T10:00[:00[.sss]]} — istemcinin bugünkü UTC biçimi)
     * ve yalnız tarih ({@code 2026-11-05} — İSTANBUL günü; {@code from} gün başı, {@code to} gün sonu 23:59:59).
     * Boş → {@code null}; çözülemeyen → 400 ({@code field}).
     */
    public static String normalizeTime(String raw, boolean endOfDay, String field) {
        String v = nil(raw);
        if (v == null) return null;
        if (v.length() > MAX_TIME_CHARS) throw invalidTime(field);
        try {
            if (v.length() == 10) {
                LocalDate d = LocalDate.parse(v);
                Instant at = endOfDay ? d.plusDays(1).atStartOfDay(TZ).toInstant().minusSeconds(1)
                        : d.atStartOfDay(TZ).toInstant();
                return ISO.format(at);
            }
            char last = v.charAt(v.length() - 1);
            boolean zoned = last == 'Z' || last == 'z' || v.lastIndexOf('+') > 10 || v.indexOf('-', 11) > 0;
            Instant at = zoned ? OffsetDateTime.parse(v.toUpperCase(Locale.ROOT)).toInstant()
                    : LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            return ISO.format(at);
        } catch (DateTimeParseException e) {
            throw invalidTime(field);
        }
    }

    /** {@link #normalizeTime} çıktısı → Instant (biçim bilinir). */
    public static Instant instantOf(String iso) {
        return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
    }

    public static String iso(Instant at) { return ISO.format(at); }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────────

    private static List<String> csv(String raw, String field, int max, Pattern shape, boolean upper) {
        String v = nil(raw);
        if (v == null) return List.of();
        if (v.length() > MAX_CSV_CHARS) {
            throw new FieldValidationException(field, Msg.t("Süzgeç değeri çok uzun.", "The filter value is too long."));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : v.split(",", -1)) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (upper) t = t.toUpperCase(Locale.ROOT);
            if (shape != null && !shape.matcher(t).matches()) {
                throw new FieldValidationException(field, Msg.t(
                        "Süzgeç değeri geçersiz: " + abbreviate(t) + ".", "Invalid filter value: " + abbreviate(t) + "."));
            }
            out.add(t);
        }
        if (out.size() > max) {
            throw new FieldValidationException(field, Msg.t(
                    "En çok " + max + " değer seçilebilir.", "At most " + max + " values can be selected."));
        }
        return List.copyOf(out);
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static String literals(List<String> values) {
        List<String> q = new ArrayList<>(values.size());
        for (String v : values) q.add("'" + v + "'");
        return String.join(", ", q);
    }

    private static String nil(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String abbreviate(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    private static FieldValidationException invalidMonitor() {
        return new FieldValidationException("monitor", Msg.t(
                "İzleme biçimi TÜR:kimlik olmalı (ör. HTTP:42).", "The monitor must look like TYPE:id (e.g. HTTP:42)."));
    }

    private static FieldValidationException invalidTime(String field) {
        return new FieldValidationException(field, Msg.t(
                "Zaman çözülemedi. ISO biçiminde girin (ör. 2026-11-05T10:00:00Z ya da 2026-11-05).",
                "The time couldn't be read. Use ISO format (e.g. 2026-11-05T10:00:00Z or 2026-11-05)."));
    }
}
