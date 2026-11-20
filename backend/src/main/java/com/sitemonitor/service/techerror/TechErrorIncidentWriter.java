package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.time.YearMonth;
import java.util.*;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;

/**
 * Eşlenmiş grup değerlerini bir konuya YAZAR (2026-11-20; içe alma ve senkron aynı yolu kullanır). Konu modülün KATI kuralıyla
 * ({@link TechErrorValues#normalize} + raporun dönemi) doğrulanır; kuralın reddettiği grup yazılmaz — konu o grupta eski
 * değerini korur, çağıran {@code value_refused} notu düşer. Doğrulama yalnız bu konuyu içeren bir değer haritasıyla yapılır:
 * raporun başka bir yerindeki (hoşgörülü okunmuş) eski veri senkronu durdurmaz. Saf; veritabanına dokunmaz.
 */
final class TechErrorIncidentWriter {

    private TechErrorIncidentWriter() {}

    /** Yazılan konu (katı normalleştirilmiş), yazılan ve reddedilen gruplar. */
    record Applied(Issue issue, Set<String> written, Set<String> refused) { }

    /** Grup başına bir ret + son çare: yazılacak grup sayısı kadar deneme yeter. */
    private static final int MAX_ATTEMPTS = GROUPS.size() + 2;

    /**
     * {@code current} konuya {@code mapped}'ın {@code groups} gruplarını yazar ve katı doğrular. Reddedilen grup çıkarılıp
     * yeniden denenir (en çok {@link #MAX_ATTEMPTS} kez). Hiç grup kalmazsa konu olduğu gibi döner (doğrulanmaz).
     */
    static Applied apply(Template t, Section s, YearMonth period, Issue current, Issue mapped, Collection<String> groups) {
        LinkedHashSet<String> want = new LinkedHashSet<>();
        for (String g : GROUPS) if (groups.contains(g)) want.add(g);
        LinkedHashSet<String> refused = new LinkedHashSet<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS && !want.isEmpty(); attempt++) {
            Issue cand = current;
            for (String g : want) cand = withGroup(cand, g, mapped, s.itemTitle());
            try {
                return new Applied(strictTopic(t, s, period, cand), Set.copyOf(want), Set.copyOf(refused));
            } catch (FieldValidationException e) {
                String g = culprit(e, s.id(), cand.id(), want);
                if (g == null) {
                    refused.addAll(want);
                    want.clear();
                    break;
                }
                want.remove(g);
                refused.add(g);
            }
        }
        refused.addAll(want);
        return new Applied(current, Set.of(), Set.copyOf(refused));
    }

    /** Reddin sorumlusu olan YAZILAN grup; bulunamazsa null (çağıran hepsini reddeder). */
    static String culprit(FieldValidationException e, String sectionId, String issueId, Set<String> want) {
        String key = e.getFields() == null || e.getFields().isEmpty() ? null : e.getFields().keySet().iterator().next();
        String prefix = "issues." + sectionId + "." + issueId + ".";
        if (key == null || !key.startsWith(prefix)) return null;
        String g = groupOfField(key.substring(prefix.length()));
        if (g == null) return null;
        if (want.contains(g)) return g;
        // Saat aralığı tarihsiz yazılamaz (anahtar "date" gelir): suçlu yazılan saattir; tersi de geçerli
        if (G_DATE.equals(g) && want.contains(G_TIME)) return G_TIME;
        if (G_TIME.equals(g) && want.contains(G_DATE)) return G_DATE;
        return null;
    }

    /** Tek konuluk değer haritasını KATI doğrular; normalleştirilmiş konuyu döner (pay {@link TechErrorCalc} ile sonra hesaplanır). */
    static Issue strictTopic(Template t, Section s, YearMonth period, Issue cand) {
        Map<String, Object> raw = TechErrorValues.toMap(new Values(Map.of(), Map.of(s.id(), List.of(cand)), Map.of()));
        Values v = TechErrorValues.normalize(raw, t, period);
        List<Issue> list = v.issues().get(s.id());
        if (list == null || list.isEmpty()) throw new IllegalStateException("normalize dropped the topic");
        return list.get(0);
    }

    /** Bölümün konularını değiştirir; diğer her şey (alanlar, metinler, tablolar, hedefler, kısaltmalar) aynı kalır. */
    static Values withIssues(Values v, Map<String, List<Issue>> issues, Collection<String> droppedUnitKeys) {
        Map<String, Integer> units = v.units();
        if (droppedUnitKeys != null && !droppedUnitKeys.isEmpty() && !units.isEmpty()) {
            units = new LinkedHashMap<>(units);
            for (String k : droppedUnitKeys) units.remove(k);
        }
        Map<String, List<Issue>> copy = new LinkedHashMap<>();
        for (var e : issues.entrySet()) if (!e.getValue().isEmpty()) copy.put(e.getKey(), List.copyOf(e.getValue()));
        return new Values(v.fields(), copy, v.texts(), v.tables(), v.targets(), units);
    }

    /** Değerlerin konu listelerinin değiştirilebilir kopyası. */
    static Map<String, List<Issue>> mutableIssues(Values v) {
        Map<String, List<Issue>> out = new LinkedHashMap<>();
        for (var e : v.issues().entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /** Konunun yazılan gruplarına göre düşecek kısaltma anahtarları (hata adedi / müşteri sayısı yeniden yazılınca). */
    static List<String> unitKeysFor(String sectionId, String issueId, Set<String> written) {
        List<String> out = new ArrayList<>(2);
        if (written.contains(G_ERRORS)) out.add(TechErrorValues.issueUnitKey(sectionId, issueId, "errors"));
        if (written.contains(G_IMPACT)) out.add(TechErrorValues.issueUnitKey(sectionId, issueId, "customers"));
        return out;
    }

    /** Konuyu değerlerde bulur; yoksa null. */
    static Issue find(Values v, String sectionId, String issueId) {
        for (Issue i : v.issues().getOrDefault(sectionId, List.of())) if (i.id().equals(issueId)) return i;
        return null;
    }
}
