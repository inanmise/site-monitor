package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Column;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.TableRow;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Teknik hata raporunun iki değer hâli arasındaki FARK (2026-10-14, değişiklik geçmişi). Saf işlev: şablona göre alan,
 * konu, metin bölümü, yönetici özeti ve tablo hücresi bazında "eski → yeni" satırları üretir. Değerler PDF'teki gibi
 * biçimlenir ({@link TechErrorFormat}, şablonun dili); etiketler yer tutucuları çözülmüş hâlleriyle yazılır — şablon
 * sonradan değişse de geçmiş okunur. Uzun metin düz metne indirilip {@link #PREVIEW} karakterde kesilir (uzunluklar
 * ayrıca yazılır). En çok {@link #MAX_CHANGES} satır; fazlası yalnız sayılır.
 */
public final class TechErrorChangeDiff {

    private TechErrorChangeDiff() {}

    public static final int MAX_CHANGES = 200;
    public static final int PREVIEW = 300;

    /**
     * Tek değişiklik. {@code op}: set (boştan değere) | clear (değerden boşa) | change | add / remove / reorder (konu) | manual
     * (özet elle yazıldı) | auto (özet otomatiğe döndü). {@code before}/{@code after} biçimli metin ya da null (boş).
     */
    public record Change(String section, String label, String type, String op, String before, String after,
                         Integer beforeLength, Integer afterLength) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", section);
            m.put("label", label);
            m.put("type", type);
            m.put("op", op);
            m.put("before", before);
            m.put("after", after);
            if (beforeLength != null) m.put("before_length", beforeLength);
            if (afterLength != null) m.put("after_length", afterLength);
            return m;
        }
    }

    public record Result(List<Change> changes, int more) {
        public boolean isEmpty() { return changes.isEmpty() && more == 0; }
    }

    public static Result diff(Template t, Values before, Values after, String channel, int year, int month) {
        Collector c = new Collector();
        String lang = t.lang();
        boolean en = "en".equals(lang);
        for (Section s : t.sections()) {
            String section = res(s.title(), channel, year, month, lang);
            switch (s.kind()) {
                case "metrics" -> {
                    for (Field f : s.fields()) {
                        Object b = before.fields().get(f.id()), a = after.fields().get(f.id());
                        if (same(b, a)) continue;
                        String label = res(f.label(), channel, year, month, lang);
                        if ("textarea".equals(f.type()) || "text".equals(f.type())) {
                            c.text(section, label, f.type(), str(b), str(a));
                        } else {
                            c.add(new Change(section, label, f.type(), op(b, a),
                                    b == null ? null : TechErrorFormat.value(f, b, lang),
                                    a == null ? null : TechErrorFormat.value(f, a, lang), null, null));
                        }
                    }
                }
                case "issues" -> issues(c, s, section, before.issues().get(s.id()), after.issues().get(s.id()), en, lang,
                        channel, year, month);
                case "text" -> {
                    // Saklı metin yoksa şablonun varsayılan metni GÖRÜNÜR — yer tutucuları çözülmüş hâliyle (düzenleyici ve
                    // PDF gibi); fark görünen metinler arasında (inceleme 2026-10-14: ham {kanal} "önce" diye yazılıyordu)
                    String def = TechErrorTemplate.resolveText(s.text(), channel, year, month, lang);
                    String b = before.texts().containsKey(s.id()) ? before.texts().get(s.id()) : def;
                    String a = after.texts().containsKey(s.id()) ? after.texts().get(s.id()) : def;
                    if (!Objects.equals(nz(b), nz(a))) c.text(section, section, "text", emptyNull(b), emptyNull(a));
                }
                case "summary" -> {
                    String b = before.texts().get(s.id()), a = after.texts().get(s.id());
                    if (Objects.equals(b, a)) continue;
                    if (b == null) c.textOp(section, section, "summary", "manual", null, a);
                    else if (a == null) c.textOp(section, section, "summary", "auto", b, null);
                    else c.text(section, section, "summary", b, a);
                }
                case "table" -> {
                    for (TableRow r : s.rows()) {
                        for (Column col : s.columns()) {
                            Object b = cell(before, s.id(), r.id(), col.id()), a = cell(after, s.id(), r.id(), col.id());
                            if (same(b, a)) continue;
                            String label = res(r.label(), channel, year, month, lang) + " — " + res(col.label(), channel, year, month, lang);
                            c.add(new Change(section, label, "table", op(b, a),
                                    b == null ? null : TechErrorFormat.cell(col, b, lang),
                                    a == null ? null : TechErrorFormat.cell(col, a, lang), null, null));
                        }
                    }
                }
                default -> { /* images: görseller ayrı olaylarla izlenir */ }
            }
        }
        return new Result(c.out, c.more);
    }

    private static void issues(Collector c, Section s, String section, List<Issue> before, List<Issue> after, boolean en,
                               String lang, String channel, int year, int month) {
        Map<String, Issue> b = new LinkedHashMap<>(), a = new LinkedHashMap<>();
        if (before != null) for (Issue i : before) b.put(i.id(), i);
        if (after != null) for (Issue i : after) a.put(i.id(), i);
        String pattern = s.itemTitle();
        // Sıra da rapordur (PDF'teki sıra): ortak konuların sırası değiştiyse tek satır (inceleme 2026-10-14 — yalnız sırası
        // değişen kayıt boş bir olay yazıyordu)
        List<String> commonBefore = new ArrayList<>(), commonAfter = new ArrayList<>();
        for (String id : b.keySet()) if (a.containsKey(id)) commonBefore.add(id);
        for (String id : a.keySet()) if (b.containsKey(id)) commonAfter.add(id);
        if (!commonBefore.equals(commonAfter)) {
            c.add(new Change(section, section, "issue", "reorder", null, null, null, null));
        }
        for (Issue i : b.values()) {
            if (a.containsKey(i.id())) continue;
            String gone = res(title(i, pattern, en), channel, year, month, lang);
            c.add(new Change(section, gone, "issue", "remove", gone, null, null, null));
        }
        for (Issue i : a.values()) {
            Issue old = b.get(i.id());
            String name = res(title(i, pattern, en), channel, year, month, lang);
            if (old == null) {
                c.add(new Change(section, name, "issue", "add", null, name, null, null));
                continue;
            }
            if (!Objects.equals(nz(old.topic()), nz(i.topic()))) {
                c.add(new Change(section, name + " · " + (en ? "Topic" : "Konu"), "text", op(emptyNull(old.topic()), emptyNull(i.topic())),
                        emptyNull(old.topic()), emptyNull(i.topic()), null, null));
            }
            // Otomatik başlık konudan üretilir: yalnız elle yazılan başlığın değişimi ayrı satır
            String ot = old.titleAuto() ? null : emptyNull(old.title()), nt = i.titleAuto() ? null : emptyNull(i.title());
            if (!Objects.equals(ot, nt)) {
                c.add(new Change(section, name + " · " + (en ? "Title" : "Başlık"), "text", op(ot, nt), ot, nt, null, null));
            }
            if (!Objects.equals(nz(old.description()), nz(i.description()))) {
                c.text(section, name + " · " + label(s.descriptionLabel(), en ? "Description" : "Açıklama"), "textarea",
                        emptyNull(old.description()), emptyNull(i.description()));
            }
            if (!same(old.customers(), i.customers())) {
                c.add(new Change(section, name + " · " + label(s.customersLabel(), en ? "Affected customers" : "Etkilenen müşteri"),
                        "number", op(old.customers(), i.customers()), num(old.customers(), lang), num(i.customers(), lang), null, null));
            }
            if (!same(old.errors(), i.errors())) {
                c.add(new Change(section, name + " · " + label(s.errorsLabel(), en ? "Errors" : "Hata adedi"),
                        "number", op(old.errors(), i.errors()), num(old.errors(), lang), num(i.errors(), lang), null, null));
            }
        }
    }

    /** Değişiklik toplayıcı: sınır aşılınca yalnız sayar. */
    private static final class Collector {
        final List<Change> out = new ArrayList<>();
        int more;

        void add(Change ch) {
            if (out.size() < MAX_CHANGES) out.add(ch);
            else more++;
        }

        void text(String section, String label, String type, String b, String a) {
            textOp(section, label, type, op(b, a), b, a);
        }

        void textOp(String section, String label, String type, String op, String b, String a) {
            add(new Change(section, label, type, op, preview(b), preview(a), cutLength(b), cutLength(a)));
        }
    }

    /** Uzun metnin okunur kısa hâli: işaretleme düşer (madde "- " kalır), satırlar boşlukla birleşir, PREVIEW'da kesilir. */
    static String preview(String s) {
        if (s == null) return null;
        String plain = plainOf(s);
        if (plain.length() <= PREVIEW) return plain;
        // Vekil çifti ortadan bölünmez (yalnız yüksek yarı yazılırsa "?" olur — capResolved ile aynı kural)
        int end = Character.isHighSurrogate(plain.charAt(PREVIEW - 1)) ? PREVIEW - 1 : PREVIEW;
        return plain.substring(0, end).stripTrailing() + "…";
    }

    /** Önizleme kesildiyse okunan metnin TAM uzunluğu, kesilmediyse null (işaretleme sayılmaz — "kısaltıldı" sanılmasın). */
    static Integer cutLength(String s) {
        if (s == null) return null;
        String plain = plainOf(s);
        return plain.length() > PREVIEW ? plain.length() : null;
    }

    private static String plainOf(String s) {
        return TechErrorRichText.plain(s).replace('\n', ' ').replaceAll(" {2,}", " ").strip();
    }

    private static String title(Issue i, String pattern, boolean en) {
        String t = i.displayTitle(pattern);
        return t == null || t.isBlank() ? (en ? "Untitled topic" : "Başlıksız konu") : t;
    }

    private static String label(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw;
    }

    private static Object cell(Values v, String sid, String row, String col) {
        Map<String, Map<String, Object>> t = v.tables().get(sid);
        if (t == null) return null;
        Map<String, Object> r = t.get(row);
        return r == null ? null : r.get(col);
    }

    private static String op(Object b, Object a) {
        return b == null ? "set" : a == null ? "clear" : "change";
    }

    /** Sayılar değerle karşılaştırılır (5 ile 5.0 aynı); diğerleri eşitlikle. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        return a.equals(b);
    }

    private static String num(Number n, String lang) {
        return n == null ? null : TechErrorFormat.count(n, lang);
    }

    private static String res(String s, String channel, int year, int month, String lang) {
        return TechErrorTemplate.resolve(s == null ? "" : s, channel, year, month, lang);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
