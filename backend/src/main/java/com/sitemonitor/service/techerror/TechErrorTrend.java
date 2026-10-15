package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Otomatik trend verisi (2026-10-15, Faz C, kullanıcı kararı: "varsayılana ekle"). Raporun ayıyla biten son
 * {@link #MONTHS} ayda, şablondaki trend bölümlerinin alanları için kanalın raporlarına GİRİLMİŞ değerler — sistem
 * hesaplamaz, yalnız dizer. Ay başına aynı kanalın o ayki raporu okunur; alan o raporun KENDİ (kopyalanmış) şablonunda
 * aynı kimlik ve aynı türdeyse değer alınır (kimliği başka alana geçmiş ya da türü değişmiş alan karışmaz). Raporu ya da
 * değeri olmayan ay {@code null}'dur — sıfır değildir; grafik o ayı boş bırakır.
 */
public record TechErrorTrend(List<YearMonth> months, Map<String, List<Double>> values) {

    public static final int MONTHS = 12;
    /** Bundan az değerli ayı olan alan grafiğe dökülmez (tek çubuk "trend" değildir) — yerine kısa not basılır. */
    public static final int MIN_POINTS = 2;

    public static TechErrorTrend empty() {
        return new TechErrorTrend(List.of(), Map.of());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Alanın {@link #MONTHS} ayı (eskiden yeniye; son eleman raporun ayı); veri yoksa boş liste. */
    public List<Double> series(String fieldId) {
        return values.getOrDefault(fieldId, List.of());
    }

    public int points(String fieldId) {
        int n = 0;
        for (Double d : series(fieldId)) if (d != null) n++;
        return n;
    }

    /** Raporun ayıyla biten {@link #MONTHS} ay, eskiden yeniye. */
    public static List<YearMonth> window(int year, int month) {
        YearMonth end = YearMonth.of(year, month);
        List<YearMonth> out = new ArrayList<>(MONTHS);
        for (int i = MONTHS - 1; i >= 0; i--) out.add(end.minusMonths(i));
        return out;
    }

    /** Şablonun trend bölümlerinde başvurulan ve şablonda sayı / yüzde olarak duran alanlar (sıra korunur). */
    public static Set<String> referencedFields(Template t) {
        Map<String, Field> fields = new HashMap<>();
        for (Field f : t.allFields()) fields.put(f.id(), f);
        Set<String> out = new LinkedHashSet<>();
        for (Section s : t.sections()) {
            if (!"trend".equals(s.kind())) continue;
            for (String ref : s.lines()) if (TechErrorTemplate.trendable(fields.get(ref))) out.add(ref);
        }
        return out;
    }

    /**
     * @param t        raporun (kopyalanmış) şablonu — alan türleri buradan
     * @param current  raporun değerleri (raporun ayı)
     * @param others   aynı kanalın diğer raporları (pencere dışındakiler ve raporun ayı yok sayılır)
     */
    public static TechErrorTrend build(Template t, Values current, int year, int month, List<TechErrorReport> others) {
        Set<String> refs = referencedFields(t);
        if (refs.isEmpty()) return empty();
        List<YearMonth> months = window(year, month);
        YearMonth own = months.get(months.size() - 1);
        Map<YearMonth, TechErrorReport> byMonth = new HashMap<>();
        for (TechErrorReport r : others == null ? List.<TechErrorReport>of() : others) {
            YearMonth ym = YearMonth.of(r.getReportYear(), r.getReportMonth());
            if (!ym.equals(own) && !ym.isBefore(months.get(0)) && ym.isBefore(own)) byMonth.put(ym, r);
        }
        Map<String, String> types = new HashMap<>();
        for (Field f : t.allFields()) types.put(f.id(), f.type());
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (String ref : refs) values.put(ref, new ArrayList<>(Collections.nCopies(months.size(), (Double) null)));
        for (int i = 0; i < months.size(); i++) {
            YearMonth ym = months.get(i);
            Template tpl;
            Values vals;
            if (ym.equals(own)) {
                tpl = t;
                vals = current;
            } else {
                TechErrorReport r = byMonth.get(ym);
                if (r == null) continue;
                tpl = TechErrorTemplate.fromStored(r.getTemplateJson());
                vals = TechErrorValues.fromStored(r.getValuesJson(), tpl);
            }
            Map<String, Field> fields = new HashMap<>();
            for (Field f : tpl.allFields()) fields.put(f.id(), f);
            for (String ref : refs) {
                Field f = fields.get(ref);
                if (f == null || !f.type().equals(types.get(ref)) || TechErrorTemplate.exempt(f, vals.fields())) continue;
                if (vals.fields().get(ref) instanceof Number n) values.get(ref).set(i, n.doubleValue());
            }
        }
        Map<String, List<Double>> frozen = new LinkedHashMap<>();
        values.forEach((k, v) -> frozen.put(k, Collections.unmodifiableList(v)));
        return new TechErrorTrend(List.copyOf(months), Collections.unmodifiableMap(frozen));
    }
}
