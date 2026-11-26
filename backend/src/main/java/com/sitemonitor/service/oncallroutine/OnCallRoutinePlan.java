package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Link;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Section;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Task;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Nöbet rutini gün sayfasının planı (2026-11-26) — SAF. Bir gün için görülen görevler: o günün DAY görevleri + haftanın
 * WEEK görevleri + o gün görünen MONTH görevleri. AÇIK görünüm = maddeleşmiş satırlar ∪ GÜNCEL şablondan sanal görevler
 * (eklenen görev hemen görünür; kaldırılan görevin sanal örneği kaybolur, maddeleşmiş örneği "kaldırıldı" ve zorunlu değil;
 * düzenlenen görevin maddeleşmiş örneği anlık görüntüsünü korur). DONMUŞ görünüm (tamamlanmış / kapanmış / pencere dışı) =
 * yalnız satırlar. Zorunlu küme R(D) ve ilerleme buradan hesaplanır.
 */
public final class OnCallRoutinePlan {

    private OnCallRoutinePlan() {}

    /** Maddeleşmiş görev satırı (veritabanı okuması). */
    public record Row(Long id, String taskId, String sectionId, String periodKind, String periodKey, String dueDay,
                      String title, String sectionTitle, String description, String linksJson, String signals,
                      boolean required, String frequency, String windowFrom, String windowTo, String state,
                      Long stateById, String stateByName, String stateAt, String skipReason, String note, int noteVersion,
                      String noteByName, String noteAt, Integer templateVersion, int version) {

        public String key() {
            return itemKey(periodKind, periodKey, taskId);
        }
    }

    /** Sayfadaki bir görev (sanal ya da maddeleşmiş). */
    public record Item(String taskId, String sectionId, String sectionTitle, String periodKind, String periodKey,
                       LocalDate dueDay, String title, String description, List<Link> links, List<String> signals,
                       boolean requiredFlag, boolean required, String frequency, Integer weekday, String monthlyRule,
                       Integer monthDay, Integer nth, String windowFrom, String windowTo, Row row, boolean removed,
                       boolean snapshotDiffers, int sectionOrder, int taskOrder) {

        public String key() {
            return itemKey(periodKind, periodKey, taskId);
        }

        public String state() {
            return row == null ? OnCallRoutineCodes.PENDING : row.state();
        }

        public boolean resolved() {
            return OnCallRoutineCodes.resolved(state());
        }

        /** Gecikmiş: zorunlu, çözülmemiş VE (son gün geçti YA DA bugün son gün ve pencere bitişi geçti). */
        public boolean overdue(LocalDate today, LocalTime nowLocal) {
            if (!required || resolved() || removed) return false;
            if (dueDay.isBefore(today)) return true;
            return dueDay.equals(today) && windowTo != null && nowLocal.isAfter(LocalTime.parse(windowTo));
        }

        /** Henüz zamanı değil: son gün ileride YA DA (sayfa bugünse) pencere başlangıcı gelmedi. */
        public boolean notBefore(LocalDate sheetDay, LocalDate today, LocalTime nowLocal) {
            if (resolved() || removed) return false;
            if (dueDay.isAfter(today)) return true;
            return sheetDay.equals(today) && windowFrom != null && nowLocal.isBefore(LocalTime.parse(windowFrom));
        }
    }

    /** Sayfa: sıralı görevler (önce DAY, sonra WEEK, sonra MONTH; bölüm ve görev sırası şablondaki gibi). */
    public record Sheet(List<Item> items) {
        public Sheet {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public List<Item> required() {
            return items.stream().filter(Item::required).toList();
        }

        public List<Item> pendingRequired() {
            return items.stream().filter(i -> i.required() && !i.resolved()).toList();
        }

        public Item find(String kind, String periodKey, String taskId) {
            String k = itemKey(kind, periodKey, taskId);
            for (Item i : items) if (i.key().equals(k)) return i;
            return null;
        }
    }

    public record Progress(int required, int done, int skipped, int pending, int overdue, int optionalPending) {
        public int resolved() { return done + skipped; }
    }

    public static String itemKey(String kind, String periodKey, String taskId) {
        return kind + ":" + periodKey + ":" + taskId;
    }

    // ── Plan ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Günün sayfası. {@code frozen} → yalnız satırlar (bu güne görünen); aksi halde satırlar ∪ güncel şablon. Satır listesi
     * çağıranın bu güne ait dönemlerden okuduğu satırlardır (fazlası süzülür).
     */
    public static Sheet sheet(Template tpl, OnCallRoutineSettings.Settings s, Set<LocalDate> holidays, LocalDate day,
                              List<Row> rows, boolean frozen) {
        Predicate<LocalDate> workday = OnCallRoutineCalendar.workdayTest(s, holidays);
        Template t = tpl == null ? Template.empty() : tpl;
        Map<String, Row> byKey = new HashMap<>();
        if (rows != null) for (Row r : rows) if (rowVisibleOn(r, day)) byKey.put(r.key(), r);
        Map<String, Integer> sectionOrder = new HashMap<>();
        Map<String, Integer> taskOrder = new HashMap<>();
        int si = 0;
        for (Section sec : t.sections()) {
            sectionOrder.put(sec.id(), si++);
            int ti = 0;
            for (Task k : sec.tasks()) taskOrder.put(k.id(), ti++);
        }
        List<Item> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        if (!frozen && workday.test(day)) {
            for (Section sec : t.sections()) {
                for (Task k : sec.tasks()) {
                    if (!k.active()) continue;
                    for (Planned p : planned(k, day, workday)) {
                        String key = itemKey(p.kind(), p.periodKey(), k.id());
                        Row r = byKey.get(key);
                        if (r != null) used.add(key);
                        out.add(virtualOrRow(sec, k, p, day, r, sectionOrder.get(sec.id()), taskOrder.get(k.id())));
                    }
                }
            }
        }
        // Satır olup şablonda karşılığı olmayan (kaldırılmış / donmuş görünüm) örnekler
        List<Row> rest = new ArrayList<>();
        for (Row r : byKey.values()) if (!used.contains(r.key())) rest.add(r);
        rest.sort(Comparator.comparing(Row::id, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Row r : rest) {
            OnCallRoutineTemplate.Located loc = t.find(r.taskId());
            boolean removed;
            if (frozen) {
                removed = false;
            } else {
                removed = loc == null || !loc.task().active()
                        || !OnCallRoutineCodes.kindOf(loc.task().frequency()).equals(r.periodKind());
            }
            Integer so = loc != null ? sectionOrder.get(loc.section().id())
                    : (r.sectionId() != null && sectionOrder.containsKey(r.sectionId()) ? sectionOrder.get(r.sectionId()) : null);
            Integer to = loc != null ? taskOrder.get(r.taskId()) : null;
            out.add(fromRow(r, loc, day, removed, so, to));
        }
        out.sort(ORDER);
        return new Sheet(out);
    }

    /** Görevin bu günden görülen dönem(ler)i. */
    record Planned(String kind, String periodKey, LocalDate due) { }

    static List<Planned> planned(Task k, LocalDate day, Predicate<LocalDate> workday) {
        List<Planned> out = new ArrayList<>(2);
        if (!workday.test(day)) return out;
        switch (k.frequency()) {
            case "WEEKLY" -> {
                LocalDate mon = OnCallRoutineCalendar.monday(day);
                OnCallRoutineCalendar.weeklyDueDay(mon, k.weekday() == null ? 5 : k.weekday(), workday)
                        .ifPresent(due -> out.add(new Planned(OnCallRoutineCodes.KIND_WEEK, mon.toString(), due)));
            }
            case "MONTHLY" -> {
                YearMonth m = YearMonth.from(day);
                for (YearMonth ym : List.of(m, m.plusMonths(1))) {
                    Optional<LocalDate> due = OnCallRoutineCalendar.monthlyDueDay(ym, k.monthlyRule(), k.monthDay(), k.nth(),
                            k.weekday(), workday);
                    if (due.isEmpty()) continue;
                    if (day.isBefore(OnCallRoutineCalendar.monday(due.get())) || day.isAfter(ym.atEndOfMonth())) continue;
                    out.add(new Planned(OnCallRoutineCodes.KIND_MONTH, ym.toString(), due.get()));
                }
            }
            default -> out.add(new Planned(OnCallRoutineCodes.KIND_DAY, day.toString(), day));
        }
        return out;
    }

    /**
     * Dönemin planlı görevleri (gün / hafta / ay kapanışı): dönemin türündeki etkin görevler ve son günleri. Haftanın /
     * ayın hiç iş günü yoksa boş.
     */
    public static List<Item> periodItems(Template tpl, OnCallRoutineSettings.Settings s, Set<LocalDate> holidays,
                                         String kind, String periodKey) {
        Predicate<LocalDate> workday = OnCallRoutineCalendar.workdayTest(s, holidays);
        List<Item> out = new ArrayList<>();
        if (tpl == null) return out;
        int si = 0;
        for (Section sec : tpl.sections()) {
            int ti = 0;
            for (Task k : sec.tasks()) {
                int order = ti++;
                if (!k.active() || !OnCallRoutineCodes.kindOf(k.frequency()).equals(kind)) continue;
                Optional<LocalDate> due = OnCallRoutineCalendar.dueDay(k, kind, periodKey, workday);
                if (due.isEmpty()) continue;
                out.add(virtualOrRow(sec, k, new Planned(kind, periodKey, due.get()), due.get(), null, si, order));
            }
            si++;
        }
        return out;
    }

    private static Item virtualOrRow(Section sec, Task k, Planned p, LocalDate day, Row r, Integer so, Integer to) {
        String wf = k.windowFrom() != null ? k.windowFrom() : sec.windowFrom();
        String wt = k.windowTo() != null ? k.windowTo() : sec.windowTo();
        if (r == null) {
            boolean required = k.required() && (OnCallRoutineCodes.KIND_DAY.equals(p.kind()) || !day.isBefore(p.due()));
            return new Item(k.id(), sec.id(), sec.title(), p.kind(), p.periodKey(), p.due(), k.title(), k.description(),
                    k.links(), k.signals(), k.required(), required, k.frequency(), k.weekday(), k.monthlyRule(),
                    k.monthDay(), k.nth(), wf, wt, null, false, false, so == null ? 999 : so, to == null ? 999 : to);
        }
        LocalDate due = parseDay(r.dueDay(), p.due());
        boolean required = r.required() && (OnCallRoutineCodes.KIND_DAY.equals(r.periodKind()) || !day.isBefore(due));
        boolean differs = !Objects.equals(r.title(), k.title())
                || !Objects.equals(blankToNull(r.description()), blankToNull(k.description()))
                || !Objects.equals(OnCallRoutineTemplate.readLinks(r.linksJson()), k.links())
                || !Objects.equals(csv(k.signals()), blankToNull(r.signals()))
                || r.required() != k.required()
                || !Objects.equals(r.windowFrom(), wf) || !Objects.equals(r.windowTo(), wt);
        return new Item(r.taskId(), sec.id(), r.sectionTitle() != null ? r.sectionTitle() : sec.title(), r.periodKind(),
                r.periodKey(), due, r.title(), r.description(), OnCallRoutineTemplate.readLinks(r.linksJson()),
                splitCsv(r.signals()), r.required(), required, r.frequency(), k.weekday(), k.monthlyRule(), k.monthDay(),
                k.nth(), r.windowFrom(), r.windowTo(), r, false, differs, so == null ? 999 : so, to == null ? 999 : to);
    }

    private static Item fromRow(Row r, OnCallRoutineTemplate.Located loc, LocalDate day, boolean removed, Integer so, Integer to) {
        LocalDate due = parseDay(r.dueDay(), day);
        boolean required = !removed && r.required()
                && (OnCallRoutineCodes.KIND_DAY.equals(r.periodKind()) || !day.isBefore(due));
        Task k = loc == null ? null : loc.task();
        return new Item(r.taskId(), r.sectionId(), r.sectionTitle(), r.periodKind(), r.periodKey(), due, r.title(),
                r.description(), OnCallRoutineTemplate.readLinks(r.linksJson()), splitCsv(r.signals()), r.required(), required,
                r.frequency(), k == null ? null : k.weekday(), k == null ? null : k.monthlyRule(),
                k == null ? null : k.monthDay(), k == null ? null : k.nth(), r.windowFrom(), r.windowTo(), r, removed, false,
                so == null ? 9_000 : so, to == null ? 9_000 + (int) Math.min(900, r.id() == null ? 0 : r.id() % 900) : to);
    }

    /** Satır bu günden görülür mü (DAY: o gün; WEEK: o hafta; MONTH: son gününün haftasının Pazartesi'sinden ay sonuna). */
    public static boolean rowVisibleOn(Row r, LocalDate day) {
        if (r == null || r.periodKind() == null || r.periodKey() == null) return false;
        switch (r.periodKind()) {
            case OnCallRoutineCodes.KIND_DAY -> {
                return r.periodKey().equals(day.toString());
            }
            case OnCallRoutineCodes.KIND_WEEK -> {
                return r.periodKey().equals(OnCallRoutineCalendar.weekKey(day));
            }
            case OnCallRoutineCodes.KIND_MONTH -> {
                try {
                    YearMonth ym = YearMonth.parse(r.periodKey());
                    LocalDate due = LocalDate.parse(r.dueDay());
                    return !day.isBefore(OnCallRoutineCalendar.monday(due)) && !day.isAfter(ym.atEndOfMonth());
                } catch (RuntimeException e) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
    }

    /** Bu günden görülebilecek dönem anahtarları (okunacak satırlar): DAY gün, WEEK Pazartesi, MONTH bu ay + sonraki ay. */
    public static List<String[]> periodsSeenFrom(LocalDate day) {
        List<String[]> out = new ArrayList<>(4);
        out.add(new String[] {OnCallRoutineCodes.KIND_DAY, day.toString()});
        out.add(new String[] {OnCallRoutineCodes.KIND_WEEK, OnCallRoutineCalendar.weekKey(day)});
        YearMonth m = YearMonth.from(day);
        out.add(new String[] {OnCallRoutineCodes.KIND_MONTH, m.toString()});
        out.add(new String[] {OnCallRoutineCodes.KIND_MONTH, m.plusMonths(1).toString()});
        return out;
    }

    public static Progress progress(Sheet sheet, LocalDate today, LocalTime nowLocal) {
        int req = 0, done = 0, skipped = 0, pending = 0, overdue = 0, optional = 0;
        for (Item i : sheet.items()) {
            if (i.removed()) continue;
            if (i.required()) {
                req++;
                String st = i.state();
                if (OnCallRoutineCodes.DONE.equals(st)) done++;
                else if (OnCallRoutineCodes.SKIPPED.equals(st)) skipped++;
                else {
                    pending++;
                    if (i.overdue(today, nowLocal)) overdue++;
                }
            } else if (!i.resolved()) {
                optional++;
            }
        }
        return new Progress(req, done, skipped, pending, overdue, optional);
    }

    // ── Sıralama ─────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, Integer> KIND_ORDER = new LinkedHashMap<>();
    static {
        KIND_ORDER.put(OnCallRoutineCodes.KIND_DAY, 0);
        KIND_ORDER.put(OnCallRoutineCodes.KIND_WEEK, 1);
        KIND_ORDER.put(OnCallRoutineCodes.KIND_MONTH, 2);
    }

    static final Comparator<Item> ORDER = Comparator
            .comparing((Item i) -> KIND_ORDER.getOrDefault(i.periodKind(), 9))
            .thenComparingInt(Item::sectionOrder)
            .thenComparing(i -> i.sectionId() == null ? "" : i.sectionId())
            .thenComparing(Item::removed)
            .thenComparingInt(Item::taskOrder)
            .thenComparing(Item::periodKey)
            .thenComparing(Item::taskId);

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────

    static LocalDate parseDay(String s, LocalDate fallback) {
        try {
            return s == null ? fallback : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static String csv(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    public static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String v = p.trim();
            if (!v.isEmpty() && OnCallRoutineSignals.KEYS.contains(v) && !out.contains(v)) out.add(v);
            if (out.size() >= OnCallRoutineTemplate.MAX_SIGNALS_PER_TASK) break;
        }
        return out;
    }
}
