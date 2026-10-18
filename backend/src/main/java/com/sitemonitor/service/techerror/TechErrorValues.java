package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.util.Msg;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Teknik hata raporunun DEĞERLERİ (2026-10-13): kullanıcının girdiği her şey; 2026-10-16'dan beri otomatik hesaplanan
 * oran alanları hariç ({@link TechErrorCalc} — kaydederken sunucu yazar).
 *
 * <pre>
 * { "fields": { "&lt;alan&gt;": 0.42 | 12000 | true | "metin" },
 *   "issues": { "&lt;bölüm&gt;": [ { id, topic, title, title_auto, description, customers, errors } ] },
 *   "texts":  { "&lt;bölüm&gt;": "serbest metin" },            // metin bölümü ve elle düzenlenmiş yönetici özeti
 *   "tables": { "&lt;bölüm&gt;": { "&lt;satır&gt;": { "&lt;sütun&gt;": 12 | 99.5 | "metin" } } },   // 2026-10-14
 *   "units":  { "&lt;alan&gt;" | "issues.&lt;bölüm&gt;.&lt;konu&gt;.customers" | "tables.&lt;bölüm&gt;.&lt;satır&gt;.&lt;sütun&gt;": 9 } }   // 2026-10-18
 * </pre>
 *
 * Şablonda olmayan kimlikler düşer (şablondan çıkarılan alanın değeri raporda tutulmaz). Boş değer saklanmaz. Sayı
 * türü negatif olamaz; yüzde negatif olabilir (azalış). Bütün listeler ve metinler sınırlı.
 */
public final class TechErrorValues {

    private TechErrorValues() {}

    public static final int MAX_JSON_CHARS = 256 * 1024;
    public static final int MAX_ISSUES = 30;
    public static final int MAX_SHORT_TEXT = 500;
    public static final int MAX_LONG_TEXT = 10_000;
    public static final int MAX_TOPIC = 120;
    public static final int MAX_CELL_TEXT = 200;
    static final double MAX_NUMBER = 1e15;
    static final double MAX_PERCENT = 1_000_000;

    public record Issue(String id, String topic, String title, boolean titleAuto, String description, Number customers,
                        Number errors) {
        /** PDF / önizleme başlığı: elle yazılan başlık; yoksa kalıp + konu; o da yoksa boş. */
        public String displayTitle(String pattern) {
            if (title != null && !title.isBlank() && !titleAuto) return title.trim();
            if (topic != null && !topic.isBlank()) return TechErrorTemplate.resolveIssueTitle(pattern, topic);
            return title == null ? "" : title.trim();
        }
    }

    /**
     * Okunmuş, şablona göre süzülmüş değerler. {@code units} (2026-10-18): kısaltmayla girilmiş sayıların üssü (3 / 6 / 9 /
     * 12) — anahtar sayı alanının kimliği, {@link TechErrorValues#issueUnitKey} ya da {@link TechErrorValues#tableUnitKey};
     * yalnız değeri olan sayı yerleri.
     */
    public record Values(Map<String, Object> fields, Map<String, List<Issue>> issues, Map<String, String> texts,
                         Map<String, Map<String, Map<String, Object>>> tables, Map<String, Object> targets,
                         Map<String, Integer> units) {
        public Values(Map<String, Object> fields, Map<String, List<Issue>> issues, Map<String, String> texts) {
            this(fields, issues, texts, Map.of(), Map.of(), Map.of());
        }

        /** Hedefsiz (2026-10-17 öncesi imza): {@code targets} boş. */
        public Values(Map<String, Object> fields, Map<String, List<Issue>> issues, Map<String, String> texts,
                      Map<String, Map<String, Map<String, Object>>> tables) {
            this(fields, issues, texts, tables, Map.of(), Map.of());
        }

        /** Kısaltmasız (2026-10-18 öncesi imza): {@code units} boş. */
        public Values(Map<String, Object> fields, Map<String, List<Issue>> issues, Map<String, String> texts,
                      Map<String, Map<String, Map<String, Object>>> tables, Map<String, Object> targets) {
            this(fields, issues, texts, tables, targets, Map.of());
        }

        public static Values empty() { return new Values(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()); }

        /**
         * Aynı değerler, başka alan haritasıyla ({@code targets} ve diğerleri korunur). Değeri kalmayan sayı alanının
         * kısaltması düşer (hesap bir alanı boşaltırsa üssü sahipsiz kalmasın).
         */
        public Values withFields(Map<String, Object> f) {
            Map<String, Integer> u = units;
            if (!u.isEmpty()) {
                u = new LinkedHashMap<>();
                for (var e : units.entrySet()) {
                    boolean bare = e.getKey().indexOf('.') < 0;
                    if (!bare || f.get(e.getKey()) instanceof Number) u.put(e.getKey(), e.getValue());
                }
            }
            return new Values(f, issues, texts, tables, targets, u);
        }

        /** Sayı alanının kısaltma üssü (yoksa null). */
        public Integer unit(String fieldId) { return units.get(fieldId); }

        /** Anomali konusu adedinin ({@code customers} / {@code errors}) kısaltma üssü (yoksa null). */
        public Integer issueUnit(String sectionId, String issueId, String part) { return units.get(issueUnitKey(sectionId, issueId, part)); }

        /** Tablo sayı hücresinin kısaltma üssü (yoksa null). */
        public Integer cellUnit(String sectionId, String rowId, String colId) { return units.get(tableUnitKey(sectionId, rowId, colId)); }

        /** Tablo hücresi (yoksa null). */
        public Object cell(String section, String row, String col) {
            Map<String, Map<String, Object>> t = tables.get(section);
            Map<String, Object> r = t == null ? null : t.get(row);
            return r == null ? null : r.get(col);
        }
    }

    // ── Doğrulama (istemciden) ───────────────────────────────────────────────────────────────────────

    public static Values normalize(Object raw, Template t) {
        return parse(raw, t, true);
    }

    /** Saklı değerleri hoşgörülü okur (şablon değiştiyse eşleşmeyenler düşer; okunamayan → boş). */
    public static Values fromStored(String json, Template t) {
        try {
            Object raw = json == null || json.isBlank() ? null : TechErrorTemplate.JSON.readValue(json, Object.class);
            return parse(raw, t, false);
        } catch (RuntimeException e) {
            return Values.empty();
        }
    }

    public static String toJson(Values v) {
        return TechErrorTemplate.JSON.writeValueAsString(toMap(v));
    }

    public static Map<String, Object> toMap(Values v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", new LinkedHashMap<>(v.fields()));
        Map<String, Object> iss = new LinkedHashMap<>();
        for (var e : v.issues().entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Issue i : e.getValue()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("id", i.id());
                im.put("topic", i.topic());
                im.put("title", i.title());
                im.put("title_auto", i.titleAuto());
                im.put("description", i.description());
                im.put("customers", i.customers());
                im.put("errors", i.errors());
                list.add(im);
            }
            iss.put(e.getKey(), list);
        }
        m.put("issues", iss);
        m.put("texts", new LinkedHashMap<>(v.texts()));
        // Tablosuz raporun JSON'u önceki sürümle bayt bayt aynı kalsın (karşılaştırma / içerik sürümü)
        if (!v.tables().isEmpty()) {
            Map<String, Object> tb = new LinkedHashMap<>();
            for (var e : v.tables().entrySet()) {
                Map<String, Object> rows = new LinkedHashMap<>();
                for (var r : e.getValue().entrySet()) rows.put(r.getKey(), new LinkedHashMap<>(r.getValue()));
                tb.put(e.getKey(), rows);
            }
            m.put("tables", tb);
        }
        // Raporun o aya özel hedefleri (2026-10-17, 4b) — yoksa anahtar hiç yazılmaz (eski belgelerle bayt bayt aynı)
        if (!v.targets().isEmpty()) m.put("targets", new LinkedHashMap<>(v.targets()));
        // Kısaltmayla girilmiş sayıların üssü (2026-10-18) — yoksa anahtar hiç yazılmaz (eski belgelerle bayt bayt aynı)
        if (!v.units().isEmpty()) m.put("units", new LinkedHashMap<>(v.units()));
        return m;
    }

    private static Values parse(Object raw, Template t, boolean strict) {
        Map<?, ?> root = raw instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> rf = root.get("fields") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> ri = root.get("issues") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> rt = root.get("texts") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> rtb = root.get("tables") instanceof Map<?, ?> m ? m : Map.of();

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Field> byId = new LinkedHashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        for (Field f : t.allFields()) {
            if (f.skipIfNo() != null) {
                // Sorusu açıkça "Hayır" ise alan muaf: değeri hiç okunmaz / doğrulanmaz (ekranda olmayan bir değer
                // kaydı 400 ile reddettiremez). Soru kendi sırasında katı doğrulanır; burada hoşgörülü okunur.
                Field q = byId.get(f.skipIfNo());
                if (q != null && Boolean.FALSE.equals(field(q, rf.get(q.id()), false))) continue;
            }
            Object v = rf.get(f.id());
            Object n = field(f, v, strict);
            if (n != null) fields.put(f.id(), n);
        }
        // Bağlı olduğu soru "Hayır" olan alan MUAF: değeri saklanmaz (gizlenmiş eski değer PDF'e sızmasın)
        for (Field f : t.allFields()) if (TechErrorTemplate.exempt(f, fields)) fields.remove(f.id());
        // O aya özel hedefler (2026-10-17, 4b): yalnız şablonda hedefi olan yüzde alanları, %0–100; aynı değer (şablonun
        // hedefi) de saklanır — rapor hedefi o ay için sabitler, şablon sonradan değişse de geçmişte görünür
        Map<?, ?> rtg = root.get("targets") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> targets = new LinkedHashMap<>();
        for (Field f : t.allFields()) {
            if (f.target() == null || !"percent".equals(f.type())) continue;
            Object v = rtg.get(f.id());
            if (v == null || (v instanceof String s && s.isBlank())) continue;
            String key = "targets." + f.id();
            Double d = number(v);
            if (d == null) {
                if (strict) throw bad(key, Msg.t("“" + f.label() + "” hedefi bir sayı olmalı.", "The “" + f.label() + "” target must be a number."));
                continue;
            }
            if (d < 0 || d > 100) {
                if (strict) throw bad(key, Msg.t("“" + f.label() + "” hedefi %0 ile %100 arasında olmalı.",
                        "The “" + f.label() + "” target must be between 0% and 100%."));
                continue;
            }
            targets.put(f.id(), compact(d));
        }
        Map<String, List<Issue>> issues = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Section s : t.sections()) {
            if ("issues".equals(s.kind())) {
                List<Issue> list = issues(ri.get(s.id()), s, strict);
                if (!list.isEmpty()) issues.put(s.id(), list);
            } else if ("table".equals(s.kind())) {
                Map<String, Map<String, Object>> tb = table(rtb.get(s.id()), s, strict);
                if (!tb.isEmpty()) tables.put(s.id(), tb);
            } else if ("text".equals(s.kind()) || "summary".equals(s.kind())) {
                // summary: elle düzenlenmiş yönetici özeti (yoksa kalıplardan üretilir — TechErrorSummary)
                Object v = rt.get(s.id());
                if (v != null) {
                    String text = TechErrorTemplate.multiline(v);
                    // Sınır, yer tutucuları çözülmüş varsayılan metinle aynı (12 000): 10 000'lik bir şablon metni
                    // {kanal} ile uzayınca tek harflik düzeltme bile kaydedilemiyordu (3. inceleme turu)
                    if (text.length() > TechErrorTemplate.MAX_RESOLVED_TEXT) {
                        if (strict) throw bad("text." + s.id(), Msg.t("“" + s.title() + "” metni en çok 12 000 karakter olabilir.",
                                "The “" + s.title() + "” text can be at most 12,000 characters."));
                        text = text.substring(0, TechErrorTemplate.MAX_RESOLVED_TEXT);
                    }
                    // Metin bölümünde boş metin saklanır (varsayılanı bilerek silmek = bölüm basılmaz); yönetici
                    // özetinde boş elle metin "otomatik" demektir → saklanmaz
                    if ("summary".equals(s.kind()) && TechErrorRichText.blank(text)) continue;
                    texts.put(s.id(), text);
                }
            }
        }
        Values out = new Values(fields, issues, texts, tables, targets,
                units(root.get("units"), t, fields, issues, tables));
        if (strict && toJson(out).length() > MAX_JSON_CHARS) {
            throw bad("values", Msg.t("Rapor içeriği çok büyük (en çok 256 KB). Uzun açıklamaları kısaltın.",
                    "The report content is too large (at most 256 KB). Shorten the long descriptions."));
        }
        return out;
    }

    private static Object field(Field f, Object v, boolean strict) {
        if (v == null) return null;
        String key = "values." + f.id();
        switch (f.type()) {
            case "number", "percent" -> {
                if (v instanceof String s && s.isBlank()) return null;
                Double d = number(v);
                if (d == null) {
                    if (strict) throw bad(key, Msg.t("“" + f.label() + "” bir sayı olmalı.", "“" + f.label() + "” must be a number."));
                    return null;
                }
                boolean pct = "percent".equals(f.type());
                if (!pct && d < 0) {
                    if (strict) throw bad(key, Msg.t("“" + f.label() + "” negatif olamaz.", "“" + f.label() + "” can't be negative."));
                    return null;
                }
                if (Math.abs(d) > (pct ? MAX_PERCENT : MAX_NUMBER)) {
                    if (strict) throw bad(key, Msg.t("“" + f.label() + "” çok büyük.", "“" + f.label() + "” is too large."));
                    return null;
                }
                return compact(d);
            }
            case "yesno" -> {
                if (v instanceof Boolean b) return b;
                if (strict) throw bad(key, Msg.t("“" + f.label() + "” için Evet ya da Hayır seçin.",
                        "Choose Yes or No for “" + f.label() + "”."));
                return null;
            }
            case "text" -> {
                String s = TechErrorTemplate.line(v);
                if (s.isEmpty()) return null;
                if (s.length() > MAX_SHORT_TEXT) {
                    if (strict) throw bad(key, Msg.t("“" + f.label() + "” en çok 500 karakter olabilir.",
                            "“" + f.label() + "” can be at most 500 characters."));
                    s = s.substring(0, MAX_SHORT_TEXT);
                }
                return s;
            }
            default -> {
                String s = TechErrorTemplate.multiline(v);
                if (s.isEmpty()) return null;
                if (s.length() > MAX_LONG_TEXT) {
                    if (strict) throw bad(key, Msg.t("“" + f.label() + "” en çok 10 000 karakter olabilir.",
                            "“" + f.label() + "” can be at most 10,000 characters."));
                    s = s.substring(0, MAX_LONG_TEXT);
                }
                return s;
            }
        }
    }

    /** Tablo değerleri: yalnız şablondaki satır × sütun; boş hücre saklanmaz. */
    private static Map<String, Map<String, Object>> table(Object raw, Section s, boolean strict) {
        Map<?, ?> rows = raw instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (TechErrorTemplate.TableRow r : s.rows()) {
            Map<?, ?> cells = rows.get(r.id()) instanceof Map<?, ?> m ? m : Map.of();
            Map<String, Object> row = new LinkedHashMap<>();
            for (TechErrorTemplate.Column c : s.columns()) {
                Object n = cell(c, cells.get(c.id()), "tables." + s.id() + "." + r.id() + "." + c.id(),
                        r.label() + " · " + c.label(), strict);
                if (n != null) row.put(c.id(), n);
            }
            if (!row.isEmpty()) out.put(r.id(), row);
        }
        return out;
    }

    private static Object cell(TechErrorTemplate.Column c, Object v, String key, String label, boolean strict) {
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        if ("text".equals(c.type())) {
            String s = TechErrorTemplate.line(v);
            if (s.isEmpty()) return null;
            if (s.length() > MAX_CELL_TEXT) {
                if (strict) throw bad(key, Msg.t("“" + label + "” en çok 200 karakter olabilir.",
                        "“" + label + "” can be at most 200 characters."));
                s = s.substring(0, MAX_CELL_TEXT);
            }
            return s;
        }
        Double d = number(v);
        boolean pct = "percent".equals(c.type());
        if (d == null) {
            if (strict) throw bad(key, Msg.t("“" + label + "” bir sayı olmalı.", "“" + label + "” must be a number."));
            return null;
        }
        if (!pct && d < 0) {
            if (strict) throw bad(key, Msg.t("“" + label + "” negatif olamaz.", "“" + label + "” can't be negative."));
            return null;
        }
        if (Math.abs(d) > (pct ? MAX_PERCENT : MAX_NUMBER)) {
            if (strict) throw bad(key, Msg.t("“" + label + "” çok büyük.", "“" + label + "” is too large."));
            return null;
        }
        return compact(d);
    }

    private static List<Issue> issues(Object raw, Section s, boolean strict) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_ISSUES) {
            if (strict) throw bad("issues." + s.id(), Msg.t("Bir bölümde en çok 30 başlık olabilir.",
                    "A section can have at most 30 topics."));
            list = list.subList(0, MAX_ISSUES);
        }
        List<Issue> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int n = 0;
        for (Object o : list) {
            n++;
            if (!(o instanceof Map<?, ?> m)) continue;
            String key = "issues." + s.id() + "." + n;
            String at = Msg.t(n + ". başlık: ", "Topic " + n + ": ");
            String id = TechErrorTemplate.str(m.get("id"));
            if (id == null || !TechErrorTemplate.ID.matcher(id).matches() || ids.contains(id)) {
                int k = n;
                id = "i-" + k;
                for (int guard = 0; ids.contains(id) && guard < 1000; guard++) id = "i-" + (++k);
            }
            ids.add(id);
            String topic = TechErrorTemplate.line(m.get("topic"));
            String title = TechErrorTemplate.line(m.get("title"));
            boolean auto = Boolean.TRUE.equals(m.get("title_auto"));
            String desc = TechErrorTemplate.multiline(m.get("description"));
            if (topic.length() > MAX_TOPIC) {
                if (strict) throw bad(key, at + Msg.t("konu en çok 120 karakter olabilir.", "the subject can be at most 120 characters."));
                topic = topic.substring(0, MAX_TOPIC);
            }
            if (title.length() > TechErrorTemplate.MAX_LABEL) {
                // Otomatik başlık kalıptan üretilir (kullanıcı yazmadı; gösterimde konudan yeniden çözülür) → kırpılır,
                // reddedilmez (inceleme 2026-10-13, 2. tur: 200 karakterlik kalıp + 120 karakterlik konu)
                if (strict && !auto) throw bad(key, at + Msg.t("başlık en çok 200 karakter olabilir.", "the title can be at most 200 characters."));
                title = title.substring(0, TechErrorTemplate.MAX_LABEL);
            }
            if (desc.length() > MAX_LONG_TEXT) {
                if (strict) throw bad(key, at + Msg.t("açıklama en çok 10 000 karakter olabilir.",
                        "the description can be at most 10,000 characters."));
                desc = desc.substring(0, MAX_LONG_TEXT);
            }
            Number customers = count(m.get("customers"), key, at + s.customersLabel(), strict);
            Number errors = count(m.get("errors"), key, at + s.errorsLabel(), strict);
            out.add(new Issue(id, topic, title, auto, desc, customers, errors));
        }
        return out;
    }

    private static Number count(Object v, String key, String label, boolean strict) {
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        Double d = number(v);
        if (d == null || d < 0 || d > MAX_NUMBER) {
            if (strict) throw bad(key, Msg.t(label + ": sıfır ya da pozitif bir sayı olmalı.", label + ": must be zero or a positive number."));
            return null;
        }
        return compact(d);
    }

    // ── Kısaltma üsleri (2026-10-18) ─────────────────────────────────────────────────────────────────

    /** Anomali konusu adedinin kısaltma anahtarı: {@code issues.<bölüm>.<konu>.<customers|errors>}. */
    public static String issueUnitKey(String sectionId, String issueId, String part) {
        return "issues." + sectionId + "." + issueId + "." + part;
    }

    /** Tablo sayı hücresinin kısaltma anahtarı: {@code tables.<bölüm>.<satır>.<sütun>}. */
    public static String tableUnitKey(String sectionId, String rowId, String colId) {
        return "tables." + sectionId + "." + rowId + "." + colId;
    }

    /**
     * Kısaltmayla girilmiş sayıların üsleri (kullanıcı isteği 2026-10-18: "4.4G gibi bir giriş raporda da aynı şekilde
     * gözüksün"). Sayının KENDİSİ gerçektir; üs yalnız gösterim bilgisidir — bu yüzden katı okumada da geçersiz kayıt
     * (bilinmeyen anahtar, 3 / 6 / 9 / 12 dışı ya da tam sayı olmayan üs, metin, o yerde değer yok, sayı olmayan alan /
     * sütun, otomatik hesaplanan alan) SESSİZCE düşer, 400 olmaz. Şablon sırasıyla dolaşılır: maliyet şablonla sınırlı
     * (istemcinin haritası ne kadar büyük olursa olsun). Değeri taşınmayan yerin üssü de taşınmaz ({@code carry}).
     */
    public static Map<String, Integer> units(Object raw, Template t, Map<String, Object> fields, Map<String, List<Issue>> issues,
                                             Map<String, Map<String, Map<String, Object>>> tables) {
        if (!(raw instanceof Map<?, ?> ru) || ru.isEmpty()) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Field f : t.allFields()) {
            if (!"number".equals(f.type()) || f.calc() != null) continue;
            if (!(fields.get(f.id()) instanceof Number)) continue;
            Integer e = exponent(ru.get(f.id()));
            if (e != null) out.put(f.id(), e);
        }
        for (Section s : t.sections()) {
            if ("issues".equals(s.kind())) {
                for (Issue i : issues.getOrDefault(s.id(), List.of())) {
                    if (i.customers() != null) put(out, ru, issueUnitKey(s.id(), i.id(), "customers"));
                    if (i.errors() != null) put(out, ru, issueUnitKey(s.id(), i.id(), "errors"));
                }
            } else if ("table".equals(s.kind())) {
                Map<String, Map<String, Object>> rows = tables.get(s.id());
                if (rows == null) continue;
                for (TechErrorTemplate.TableRow r : s.rows()) {
                    Map<String, Object> cells = rows.get(r.id());
                    if (cells == null) continue;
                    for (TechErrorTemplate.Column c : s.columns()) {
                        if ("number".equals(c.type()) && cells.get(c.id()) instanceof Number) put(out, ru, tableUnitKey(s.id(), r.id(), c.id()));
                    }
                }
            }
        }
        return out;
    }

    /** {@link #units} — saklı / taşınmış değerlerle (şablon yenileme: üs yalnız değeri taşınan yerde kalır). */
    public static Map<String, Integer> units(Map<String, Integer> raw, Template t, Values v) {
        return units(raw, t, v.fields(), v.issues(), v.tables());
    }

    private static void put(Map<String, Integer> out, Map<?, ?> raw, String key) {
        Integer e = exponent(raw.get(key));
        if (e != null) out.put(key, e);
    }

    /** Geçerli kısaltma üssü: tam sayı 3 / 6 / 9 / 12 (sayı türünde); değilse null. */
    static Integer exponent(Object v) {
        if (!(v instanceof Number n)) return null;
        double d = n.doubleValue();
        if (d != 3 && d != 6 && d != 9 && d != 12) return null;
        return (int) d;
    }

    /** Sayı ya da sayısal metin ("1234.5"; istemci yerel biçimi kendisi çözer) → sonlu double; değilse null. */
    static Double number(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || t.length() > 40) return null;
            try {
                double d = new BigDecimal(t).doubleValue();
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Tam sayıysa Long, değilse en çok 6 ondalığa yuvarlanmış Double (JSON'da "12000.0" değil "12000" görünsün). */
    static Number compact(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 9e15) return (long) d;
        return BigDecimal.valueOf(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    private static FieldValidationException bad(String field, String msg) {
        return new FieldValidationException(field, msg);
    }
}
