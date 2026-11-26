package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Perms;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nöbet rutini istatistikleri ve dışa aktarım (2026-11-26). Gruplu SQL (görev × durum, kişi başına işaret, sorunlar);
 * sabit sayıda ifade (≤ 6 olağan durumda). Yüzdeler bir ondalığa AŞAĞI yuvarlanır (bir kaçırma varken asla 100); payda 0
 * ise null. Kişi kimliği yalnız {@code user_id} anahtarında (opak kimliğe yanıt kapısı çevirir).
 */
@Service
public class OnCallRoutineStatsService {

    static final int DONE_ROWS_LIMIT = 20_000;
    static final int PROBLEM_ROWS_LIMIT = 10_000;
    static final int EXPORT_PROBLEMS_LIMIT = 5_000;

    private final OnCallRoutineService svc;
    private final OnCallRoutineStore store;

    public OnCallRoutineStatsService(OnCallRoutineService svc, OnCallRoutineStore store) {
        this.svc = svc;
        this.store = store;
    }

    /** Bir ondalığa aşağı yuvarlanmış yüzde; payda 0 → null. */
    static Double pct(long part, long whole) {
        if (whole <= 0) return null;
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.FLOOR).doubleValue();
    }

    static LocalDateTime local(String stamp) {
        if (stamp == null) return null;
        try {
            return LocalDateTime.ofInstant(LocalDateTime.parse(stamp).toInstant(ZoneOffset.UTC), OnCallRoutineCodes.TZ);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Instant instant(String stamp) {
        if (stamp == null) return null;
        try {
            return LocalDateTime.parse(stamp).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Dakika listesinin ortancası (alt orta) → {@code HH:mm}. */
    static String medianTime(List<Integer> minutes) {
        if (minutes == null || minutes.isEmpty()) return null;
        List<Integer> s = new ArrayList<>(minutes);
        Collections.sort(s);
        int m = s.get((s.size() - 1) / 2);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /**
     * Şablon (bugünkü) bu gün zorunlu bir günlük görev planlıyor mu — kapanışın R(D) kuralı ({@code requiredDay}), satırsız.
     * Şablon okunamıyorsa true (bilinmeyeni "görev yoktu" saymaz).
     */
    static boolean requiredDailyPlanned(TeamCtx c, LocalDate d) {
        if (c.template() == null) return true;
        try {
            return !OnCallRoutineService.requiredDay(
                    OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, List.of(), false)).isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public Map<String, Object> stats(TeamCtx c, LocalDate from, LocalDate to) {
        LocalDate today = svc.today();
        Long team = c.teamId();
        String f = from.toString();
        String t = to.toString();
        boolean truncated = false;

        // 1) Gün satırları
        List<DayRow> days = store.days(team, f, t);
        int workdays = 0;
        int dueDays = 0;
        Map<String, DayRow> rowByDay = new HashMap<>();
        for (DayRow r : days) rowByDay.put(r.day(), r);
        int none = 0;
        int completed = 0, partial = 0, incomplete = 0, missed = 0, open = 0, onTime = 0;
        // Gün sayıları ve zamanında oranı AYNI kümeden (bugünkü iş günü kuralına göre aralığın iş günleri — inceleme #10:
        // pay bütün satırlardan, payda yalnız iş günlerinden sayılıyordu; iş günü kuralı değişince oran %100'ü aşabiliyordu).
        // Rutin etkinleşmeden önceki ve duraklatılmış günler rutinin günü değildir (hata avı: "son 30 gün" bir hafta önce
        // başlayan rutinde 22 günü "satır yok" sayıp zamanında oranını düşürüyordu — bilinmeyen, eksik değildir).
        String since = c.row() == null ? null : c.row().enabledSince();
        for (LocalDate d = from; !d.isAfter(to) && !d.isAfter(today); d = d.plusDays(1)) {
            if (!c.workday(d)) continue;
            if (since != null && d.toString().compareTo(since) < 0) continue;
            if (c.paused(d)) continue;
            workdays++;
            DayRow r = rowByDay.get(d.toString());
            if (r == null) {
                none++;
            } else {
                switch (r.status()) {
                    case OnCallRoutineCodes.DAY_COMPLETED -> completed++;
                    case OnCallRoutineCodes.DAY_COMPLETED_PARTIAL -> partial++;
                    case OnCallRoutineCodes.DAY_INCOMPLETE -> incomplete++;
                    case OnCallRoutineCodes.DAY_MISSED -> missed++;
                    default -> open++;
                }
            }
            // Zamanında oranının paydası: geçmiş iş günleri + tamamlanmışsa bugün; pay bu kümenin zamanında tamamlananları.
            // Satırı olmayan geçmiş gün yalnız şablon o gün zorunlu bir günlük görev planlıyorsa sayılır (yalnız haftalık /
            // aylık ya da isteğe bağlı görevli şablonun günü — NOTHING_DUE — paydaya girmez).
            boolean done = r != null && OnCallRoutineCodes.completed(r.status());
            if ((d.isBefore(today) || done) && (r != null || requiredDailyPlanned(c, d))) {
                dueDays++;
                if (done && !r.completedLate()) onTime++;
            }
        }
        Map<Long, int[]> perPerson = new LinkedHashMap<>(); // [responsible, completed, on_time, items_done, problems]
        Map<Long, String> names = new HashMap<>();
        for (DayRow r : days) {
            if (r.responsibleUserId() != null) {
                perPerson.computeIfAbsent(r.responsibleUserId(), k -> new int[5])[0]++;
                if (r.responsibleName() != null) names.putIfAbsent(r.responsibleUserId(), r.responsibleName());
            }
            if (r.completedById() != null && OnCallRoutineCodes.completed(r.status())) {
                int[] a = perPerson.computeIfAbsent(r.completedById(), k -> new int[5]);
                a[1]++;
                if (!r.completedLate()) a[2]++;
                if (r.completedByName() != null) names.putIfAbsent(r.completedById(), r.completedByName());
            }
        }

        // 2) Görev × durum
        Map<String, int[]> byTask = new LinkedHashMap<>(); // [done, skipped, missed, pending]
        for (Object[] r : store.taskStateCounts(team, f, t)) {
            int[] a = byTask.computeIfAbsent((String) r[0], k -> new int[4]);
            int n = (Integer) r[2];
            switch (String.valueOf(r[1])) {
                case OnCallRoutineCodes.DONE -> a[0] += n;
                case OnCallRoutineCodes.SKIPPED -> a[1] += n;
                case OnCallRoutineCodes.MISSED -> a[2] += n;
                default -> a[3] += n;
            }
        }
        // 3) Görev başlıkları
        Map<String, Object[]> titles = new HashMap<>();
        for (Object[] r : store.taskTitles(team, f, t)) titles.put((String) r[0], r);
        // 4) DONE satırları (son işaret + ortanca saat + kişi başına sayım)
        List<Object[]> done = store.doneRows(team, f, t, DONE_ROWS_LIMIT + 1);
        boolean doneCut = done.size() > DONE_ROWS_LIMIT;
        if (doneCut) {
            truncated = true;
            done = done.subList(0, DONE_ROWS_LIMIT);
        }
        Map<String, Object[]> lastDone = new HashMap<>();
        Map<String, List<Integer>> doneMinutes = new HashMap<>();
        Map<Long, Integer> itemsDone = new HashMap<>();
        for (Object[] r : done) {
            String task = (String) r[0];
            lastDone.putIfAbsent(task, r);
            LocalDateTime lt = local((String) r[1]);
            if (lt != null) doneMinutes.computeIfAbsent(task, k -> new ArrayList<>()).add(lt.getHour() * 60 + lt.getMinute());
            if (r[2] instanceof Long uid) {
                itemsDone.merge(uid, 1, Integer::sum);
                if (r[3] != null) names.putIfAbsent(uid, (String) r[3]);
            }
        }
        if (doneCut) {
            // 5) kesildiyse kişi başına sayım SQL'den (tam)
            itemsDone.clear();
            for (Object[] r : store.itemsDoneByPerson(team, f, t)) {
                if (r[0] instanceof Long uid) {
                    itemsDone.put(uid, (Integer) r[2]);
                    if (r[1] != null) names.putIfAbsent(uid, (String) r[1]);
                }
            }
        }
        for (Map.Entry<Long, Integer> e : itemsDone.entrySet()) {
            perPerson.computeIfAbsent(e.getKey(), k -> new int[5])[3] = e.getValue();
        }

        // 6) Sorunlar
        List<ProblemRow> probs = store.problemsOnDays(team, f, t, PROBLEM_ROWS_LIMIT + 1);
        if (probs.size() > PROBLEM_ROWS_LIMIT) {
            truncated = true;
            probs = probs.subList(0, PROBLEM_ROWS_LIMIT);
        }
        Map<String, Integer> probsByTask = new HashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (String s : OnCallRoutineCodes.SEVERITIES) bySeverity.put(s, 0);
        int resolved = 0;
        List<Double> resolveHours = new ArrayList<>();
        for (ProblemRow p : probs) {
            if (p.taskId() != null) probsByTask.merge(p.taskId(), 1, Integer::sum);
            bySeverity.merge(p.severity(), 1, Integer::sum);
            if (p.createdById() != null) perPerson.computeIfAbsent(p.createdById(), k -> new int[5])[4]++;
            if (p.createdById() != null && p.createdByName() != null) names.putIfAbsent(p.createdById(), p.createdByName());
            if (OnCallRoutineCodes.PROBLEM_RESOLVED.equals(p.status())) {
                resolved++;
                Instant a = instant(p.createdAt());
                Instant b = instant(p.resolvedAt());
                if (a != null && b != null && !b.isBefore(a)) resolveHours.add(Duration.between(a, b).toMinutes() / 60.0);
            }
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byTask.entrySet()) {
            int[] a = e.getValue();
            int occ = a[0] + a[1] + a[2] + a[3];
            Object[] tt = titles.get(e.getKey());
            Object[] ld = lastDone.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task_id", e.getKey());
            m.put("title", tt == null ? null : tt[1]);
            m.put("section_title", tt == null ? null : tt[2]);
            m.put("frequency", tt == null ? null : tt[3]);
            m.put("occurrences", occ);
            m.put("done", a[0]);
            m.put("skipped", a[1]);
            m.put("missed", a[2]);
            m.put("pending", a[3]);
            m.put("done_pct", pct(a[0], occ));
            m.put("problems", probsByTask.getOrDefault(e.getKey(), 0));
            m.put("last_done_at", ld == null ? null : OnCallRoutineWire.z((String) ld[1]));
            m.put("last_done_by_name", ld == null ? null : ld[3]);
            m.put("median_done_time", medianTime(doneMinutes.get(e.getKey())));
            tasks.add(m);
        }
        tasks.sort((x, y) -> {
            Double px = (Double) x.get("done_pct");
            Double py = (Double) y.get("done_pct");
            int cmp = Double.compare(px == null ? 101 : px, py == null ? 101 : py);
            return cmp != 0 ? cmp : String.valueOf(x.get("title")).compareTo(String.valueOf(y.get("title")));
        });

        List<Map<String, Object>> people = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : perPerson.entrySet()) {
            int[] a = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", e.getKey());
            m.put("name", names.get(e.getKey()));
            m.put("days_responsible", a[0]);
            m.put("days_completed", a[1]);
            m.put("days_completed_on_time", a[2]);
            m.put("items_done", a[3]);
            m.put("problems_opened", a[4]);
            people.add(m);
        }
        people.sort((x, y) -> Integer.compare((Integer) y.get("items_done"), (Integer) x.get("items_done")));

        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("opened", probs.size());
        pm.put("resolved", resolved);
        pm.put("open_now", store.countProblems(team, OnCallRoutineCodes.PROBLEM_OPEN));
        pm.put("by_severity", bySeverity);
        Double median = null;
        if (!resolveHours.isEmpty()) {
            Collections.sort(resolveHours);
            median = BigDecimal.valueOf(resolveHours.get((resolveHours.size() - 1) / 2)).setScale(1, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        pm.put("median_resolve_hours", median);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f);
        out.put("to", t);
        out.put("workdays", workdays);
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("completed", completed);
        dm.put("partial", partial);
        dm.put("incomplete", incomplete);
        dm.put("missed", missed);
        dm.put("open", open);
        dm.put("none", none);
        out.put("days", dm);
        out.put("on_time_pct", pct(onTime, dueDays));
        out.put("tasks", tasks);
        out.put("people", people);
        out.put("problems", pm);
        out.put("truncated", truncated);
        return out;
    }

    /** Dışa aktarım (§4.1 {@code /export}); 20 000 görev satırı üstü {@code EXPORT_TOO_LARGE}. */
    public Map<String, Object> export(TeamCtx c, Perms p, LocalDate from, LocalDate to) {
        String f = from.toString();
        String t = to.toString();
        int n = store.countItems(c.teamId(), f, t);
        if (n > OnCallRoutineCodes.EXPORT_MAX_ROWS) throw OnCallRoutineErrors.exportTooLarge();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", svc.calendar(c, from, to).get("days"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] r : store.exportItems(c.teamId(), f, t, OnCallRoutineCodes.EXPORT_MAX_ROWS)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r[0]);
            m.put("period_kind", r[1]);
            m.put("period_key", r[2]);
            m.put("section_title", r[3]);
            m.put("task_title", r[4]);
            m.put("state", r[5]);
            m.put("state_by_name", r[6]);
            m.put("state_at", OnCallRoutineWire.z((String) r[7]));
            m.put("skip_reason", r[8]);
            m.put("note", r[9]);
            items.add(m);
        }
        out.put("items", items);
        List<ProblemRow> probs = store.problemsOnDays(c.teamId(), f, t, EXPORT_PROBLEMS_LIMIT);
        List<Map<String, Object>> pw = svc.problemsWire(c, p, probs, svc.today());
        for (Map<String, Object> m : pw) m.remove("images");
        out.put("problems", pw);
        out.put("stats", stats(c, from, to));
        out.put("truncated", probs.size() >= EXPORT_PROBLEMS_LIMIT);
        out.put("max_rows", OnCallRoutineCodes.EXPORT_MAX_ROWS);
        return out;
    }
}
