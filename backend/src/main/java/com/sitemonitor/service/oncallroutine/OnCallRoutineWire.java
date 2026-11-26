package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Item;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ImageMeta;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nöbet rutinleri tel biçimi (2026-11-26) — görev, sorun, gün ve zaman damgası eşlemeleri TEK yerde. Saklanan UTC damgaları
 * telde {@code Z} ekiyle döner (tarayıcı yerel saat sanmasın). Kullanıcı kimliği yalnız {@code user_id} anahtarında (opak
 * kimliğe yanıt kapısı çevirir); diğer her şey ad + bayrak.
 */
public final class OnCallRoutineWire {

    private OnCallRoutineWire() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** Saklanan UTC damgası → telde {@code …Z}. */
    public static String z(String stored) {
        if (stored == null || stored.isBlank()) return null;
        return stored.endsWith("Z") ? stored : stored + "Z";
    }

    public static Map<String, Object> item(Item i, LocalDate sheetDay, LocalDate today, LocalTime nowLocal, Long me,
                                           Map<Long, Integer> problemsByItem) {
        Map<String, Object> m = new LinkedHashMap<>();
        OnCallRoutinePlan.Row r = i.row();
        m.put("key", i.key());
        m.put("task_id", i.taskId());
        m.put("item_id", r == null ? null : r.id());
        m.put("period_kind", i.periodKind());
        m.put("period_key", i.periodKey());
        m.put("due_day", i.dueDay().toString());
        m.put("title", i.title());
        m.put("description", i.description());
        m.put("links", OnCallRoutineTemplate.linksWire(i.links()));
        m.put("signals", new ArrayList<>(i.signals()));
        m.put("required", i.required());
        m.put("frequency", i.frequency());
        m.put("weekday", i.weekday());
        m.put("monthly_rule", i.monthlyRule());
        m.put("month_day", i.monthDay());
        m.put("nth", i.nth());
        m.put("window_from", i.windowFrom());
        m.put("window_to", i.windowTo());
        m.put("not_before", i.notBefore(sheetDay, today, nowLocal));
        m.put("overdue", i.overdue(today, nowLocal));
        m.put("due_today", i.dueDay().equals(sheetDay));
        m.put("due_later", i.dueDay().isAfter(sheetDay));
        m.put("state", i.state());
        m.put("state_by_name", r == null ? null : r.stateByName());
        m.put("state_by_me", r != null && me != null && me.equals(r.stateById()));
        m.put("state_at", r == null ? null : z(r.stateAt()));
        m.put("skip_reason", r == null ? null : r.skipReason());
        m.put("note", r == null || r.note() == null ? "" : r.note());
        m.put("note_version", r == null ? 0 : r.noteVersion());
        m.put("note_by_name", r == null ? null : r.noteByName());
        m.put("note_at", r == null ? null : z(r.noteAt()));
        m.put("problems_open", r == null || problemsByItem == null ? 0 : problemsByItem.getOrDefault(r.id(), 0));
        m.put("removed", i.removed());
        m.put("snapshot_differs", i.snapshotDiffers());
        m.put("version", r == null ? 0 : r.version());
        return m;
    }

    /** Gün satırının tel özeti (gün sayfasındaki {@code day} bloğu). */
    public static Map<String, Object> day(DayRow d, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("version", d == null ? 0 : d.version());
        m.put("completed_by_name", d == null ? null : d.completedByName());
        m.put("completed_at", d == null ? null : z(d.completedAt()));
        m.put("completion_seq", d == null ? 0 : d.completionSeq());
        m.put("completed_late", d != null && d.completedLate() && OnCallRoutineCodes.completed(d.status()));
        m.put("note", d == null || d.note() == null ? "" : d.note());
        m.put("note_version", d == null ? 0 : d.noteVersion());
        m.put("note_by_name", d == null ? null : d.noteByName());
        m.put("note_at", d == null ? null : z(d.noteAt()));
        return m;
    }

    public static Map<String, Object> progress(OnCallRoutinePlan.Progress p, int problemsOpen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("required", p.required());
        m.put("done", p.done());
        m.put("skipped", p.skipped());
        m.put("pending", p.pending());
        m.put("overdue", p.overdue());
        m.put("optional_pending", p.optionalPending());
        m.put("problems_open", problemsOpen);
        return m;
    }

    /**
     * Sorun. {@code incident}: bağlı olay {@code {id, title, status}}; görülemiyorsa {@code {id, hidden:true}}; bağlı değilse
     * null. {@code canEdit} / {@code canDelete} çağıranın kuralı.
     */
    public static Map<String, Object> problem(ProblemRow p, List<ImageMeta> images, Map<String, Object> incident,
                                              LocalDate today, boolean canEdit, boolean canDelete) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("status", p.status());
        m.put("severity", p.severity());
        m.put("note", p.note());
        m.put("resolution_note", p.resolutionNote());
        m.put("task_id", p.taskId());
        m.put("task_title", p.taskTitle());
        m.put("section_title", p.sectionTitle());
        m.put("day", p.day());
        Integer age = null;
        try {
            age = (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(p.day()), today));
        } catch (RuntimeException ignored) {
            // bozuk gün: yaş yok
        }
        m.put("age_days", age);
        m.put("created_by_name", p.createdByName());
        m.put("created_at", z(p.createdAt()));
        m.put("updated_by_name", p.updatedByName());
        m.put("updated_at", z(p.updatedAt()));
        m.put("resolved_by_name", p.resolvedByName());
        m.put("resolved_at", z(p.resolvedAt()));
        m.put("incident", incident);
        List<Map<String, Object>> imgs = new ArrayList<>();
        if (images != null) for (ImageMeta im : images) imgs.add(image(im));
        m.put("images", imgs);
        m.put("version", p.version());
        m.put("can_edit", canEdit);
        m.put("can_delete", canDelete);
        return m;
    }

    public static Map<String, Object> image(ImageMeta im) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", im.id());
        m.put("width", im.width());
        m.put("height", im.height());
        m.put("size_bytes", im.sizeBytes());
        m.put("content_type", im.contentType());
        return m;
    }

    /** Olay ayrıntısı JSON'u (sınırlı; adres İÇERMEZ). */
    public static String detail(Map<String, ?> m) {
        if (m == null || m.isEmpty()) return null;
        try {
            String s = JSON.writeValueAsString(m);
            return s.length() <= 4000 ? s : s.substring(0, 3990) + "…\"}";
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object o = JSON.readValue(json, Object.class);
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (RuntimeException ignored) {
            // kesilmiş ayrıntı → boş
        }
        return Map.of();
    }

    public static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> readMap(String json) {
        return parseDetail(json);
    }
}
