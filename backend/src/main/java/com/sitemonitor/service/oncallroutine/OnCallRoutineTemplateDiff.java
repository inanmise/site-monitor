package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Section;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Task;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nöbet rutini şablon sürümleri arasındaki özet fark (2026-11-26) — SAF. Görev kimliğiyle eşlenir: {@code added} yeni
 * kimlik, {@code removed} kaybolan kimlik, {@code changed} aynı kimlikte içerik (başlık / açıklama / bağlantı / sinyal /
 * zorunluluk / etkinlik / sıklık / pencere) farkı, {@code moved} aynı kimliğin bölümü ya da bölüm içi sırası değişti.
 * Başlık / adres İÇERMEZ (denetim ayrıntısına da bu özet gider).
 */
public final class OnCallRoutineTemplateDiff {

    private OnCallRoutineTemplateDiff() {}

    public record Summary(int sections, int tasks, int active, int added, int removed, int changed, int moved) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sections", sections);
            m.put("tasks", tasks);
            m.put("active", active);
            m.put("added", added);
            m.put("removed", removed);
            m.put("changed", changed);
            m.put("moved", moved);
            return m;
        }
    }

    private record Pos(String sectionId, int index, Task task) { }

    public static Summary summary(Template before, Template after) {
        Template a = after == null ? Template.empty() : after;
        Map<String, Pos> old = index(before == null ? Template.empty() : before);
        Map<String, Pos> now = index(a);
        int added = 0, removed = 0, changed = 0, moved = 0;
        for (Map.Entry<String, Pos> e : now.entrySet()) {
            Pos o = old.get(e.getKey());
            if (o == null) {
                added++;
                continue;
            }
            Pos n = e.getValue();
            if (!same(o.task(), n.task())) changed++;
            if (!o.sectionId().equals(n.sectionId()) || o.index() != n.index()) moved++;
        }
        for (String id : old.keySet()) if (!now.containsKey(id)) removed++;
        return new Summary(a.sections().size(), a.taskCount(), a.activeCount(), added, removed, changed, moved);
    }

    private static Map<String, Pos> index(Template t) {
        Map<String, Pos> m = new HashMap<>();
        for (Section s : t.sections()) {
            int i = 0;
            for (Task k : s.tasks()) m.put(k.id(), new Pos(s.id(), i++, k));
        }
        return m;
    }

    /** Konum dışındaki her alan aynı mı (kimlik dahil kayıt eşitliği). */
    static boolean same(Task a, Task b) {
        return a.equals(b);
    }
}
