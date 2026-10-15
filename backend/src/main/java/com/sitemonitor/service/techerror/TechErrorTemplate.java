package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kanal teknik hata raporu ŞABLONU (2026-10-13, kullanıcı isteği: "her ekip kendi teknik hata raporunun şablonunu
 * kendisi oluşturabilmeli; X kanalı şablonunu aç dediğinde onu açmalı"). Şablon kanal başınadır ve sıralı BÖLÜMLERDEN
 * oluşur:
 * <ul>
 *   <li>{@code metrics} — gösterge alanları (sayı, yüzde, evet-hayır, kısa metin, uzun metin);</li>
 *   <li>{@code issues} — tekrarlanan anomali başlıkları ("{konu} Konusu ile yaşanan problem", açıklama, etkilenen
 *       müşteri, hata adedi);</li>
 *   <li>{@code text} — serbest metin (varsayılan metin şablonda, düzenlemesi raporda);</li>
 *   <li>{@code images} — grafik yuvaları; {@code half} iki kolonlu ızgarada bir kolon, {@code full} iki kolon genişliği;</li>
 *   <li>{@code summary} — YÖNETİCİ ÖZETİ (2026-10-14): cümle kalıpları; {@code {f:<alan>}} girilen değerle dolar,
 *       {@code {f:<evet/hayır alanı>?evet metni|hayır metni}} cevaba göre seçer. Değeri boş ya da muaf bir alana
 *       dayanan cümle kendiliğinden düşer ({@link TechErrorSummary}); rapor metni elle de düzenleyebilir;</li>
 *   <li>{@code table} — sütunları (sayı / yüzde / metin) ve satırları (grup başlığı ya da satır) şablonda tanımlı
 *       tablo; değerler raporda girilir. Varsayılan şablonda kullanılmaz (SLA tablosu ekran görüntüsüdür).</li>
 * </ul>
 * Başlıklarda {@code {kanal}} / {@code {ay}} / {@code {yil}} (EN: {@code {channel}} / {@code {month}} / {@code {year}})
 * raporun kanalı ve dönemiyle dolar. Alan ve yuva kimlikleri KALICIDIR: ad değişse de geçmiş raporların değerleri kaybolmaz.
 *
 * <p>İstemciden gelen şablon {@link #normalize(Object)} ile KATI doğrulanır (alan hatası → 400 {@code VALIDATION_FAILED});
 * saklı şablon {@link #fromStored(String)} ile HOŞGÖRÜLÜ okunur (kural sonradan sıkılaşsa da eski kayıt açılır). Bütün
 * döngüler girdi boyutuyla sınırlıdır (bölüm ≤ 20, alan ≤ 30, yuva ≤ 40, JSON ≤ 64 KB).
 */
public final class TechErrorTemplate {

    private TechErrorTemplate() {}

    public static final int MAX_JSON_CHARS = 64 * 1024;
    public static final int MAX_SECTIONS = 20;
    public static final int MAX_FIELDS = 30;
    public static final int MAX_SLOTS = 40;
    public static final int MAX_TITLE = 200;
    public static final int MAX_LABEL = 200;
    public static final int MAX_HINT = 300;
    public static final int MAX_ISSUE_LABEL = 120;
    public static final int MAX_SECTION_TEXT = 10_000;
    /**
     * Yer tutucu çözümünün üst sınırı (inceleme 2026-10-13): 200 karakterlik bir başlık kalıbı {@code {kanal}}'ı onlarca
     * kez tekrarlayıp binlerce karaktere büyüyebiliyordu (PDF kırpması karakter başına ölçer). Başlık / etiket 300,
     * metin bölümü 12 000 karakterde kesilir ("…"). İstemci aynı sınırı uygular ({@code techErrorModel.js}).
     */
    public static final int MAX_RESOLVED = 300;
    public static final int MAX_RESOLVED_TEXT = 12_000;
    public static final int MAX_SUMMARY_LINES = 20;
    public static final int MAX_SUMMARY_LINE = 500;
    public static final int MAX_COLUMNS = 10;
    public static final int MAX_ROWS = 40;
    public static final int MAX_COLUMN_LABEL = 120;

    public static final List<String> KINDS = List.of("metrics", "issues", "text", "images", "summary", "table");
    public static final List<String> COLUMN_TYPES = List.of("number", "percent", "text");
    public static final List<String> FIELD_TYPES = List.of("number", "percent", "yesno", "text", "textarea");
    public static final List<String> WIDTHS = List.of("half", "full");
    public static final List<String> LANGS = List.of("tr", "en");

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Gösterge alanı. {@code skipIfNo} (2026-10-14, kullanıcı isteği: "kesinti yok işareti koyarsa bu alan muaf olsun"):
     * şablondaki bir evet/hayır sorusunun kimliği — o soru "Hayır" işaretlenirse alan MUAF olur: formda sorulmaz,
     * değeri saklanmaz, PDF'e ve e-postaya girmez. Soru boşsa ya da "Evet" ise alan istenir. Zincir yok: bağlanılan
     * soru kendisi başka bir soruya bağlı olamaz.
     */
    public record Field(String id, String label, String type, String hint, String skipIfNo) {
        public Field(String id, String label, String type, String hint) {
            this(id, label, type, hint, null);
        }
    }

    /** Alan bu değerlerle muaf mı (bağlı olduğu evet/hayır sorusu açıkça "Hayır")? */
    public static boolean exempt(Field f, Map<String, Object> values) {
        return f != null && f.skipIfNo() != null && values != null && Boolean.FALSE.equals(values.get(f.skipIfNo()));
    }

    public record Slot(String id, String label, String width) { }

    /** Tablo sütunu: {@code type} sayı / yüzde / metin. */
    public record Column(String id, String label, String type) { }

    /** Tablo satırı: {@code group} = kalın grup başlığı satırı (kendi değerleri olabilir, ör. ara toplam). */
    public record TableRow(String id, String label, boolean group) { }

    public record Section(String id, String kind, String title, List<Field> fields, List<Slot> slots, String itemTitle,
                          String descriptionLabel, String customersLabel, String errorsLabel, String text,
                          List<String> lines, List<Column> columns, List<TableRow> rows) {
        public Section(String id, String kind, String title, List<Field> fields, List<Slot> slots, String itemTitle,
                       String descriptionLabel, String customersLabel, String errorsLabel, String text) {
            this(id, kind, title, fields, slots, itemTitle, descriptionLabel, customersLabel, errorsLabel, text,
                    List.of(), List.of(), List.of());
        }
    }

    public record Template(String lang, String title, List<Section> sections) {

        public List<Field> allFields() {
            List<Field> out = new ArrayList<>();
            for (Section s : sections) if ("metrics".equals(s.kind())) out.addAll(s.fields());
            return out;
        }

        public List<Slot> allSlots() {
            List<Slot> out = new ArrayList<>();
            for (Section s : sections) if ("images".equals(s.kind())) out.addAll(s.slots());
            return out;
        }

        public Slot slot(String id) {
            for (Slot s : allSlots()) if (s.id().equals(id)) return s;
            return null;
        }

        public boolean en() { return "en".equals(lang); }
    }

    // ── Okuma / yazma ────────────────────────────────────────────────────────────────────────────────

    /** Saklı JSON'u HOŞGÖRÜLÜ okur; okunamıyorsa varsayılan şablon (rapor yine açılır, PDF yine üretilir). */
    public static Template fromStored(String json) {
        try {
            Object raw = json == null || json.isBlank() ? null : JSON.readValue(json, Object.class);
            if (raw != null) return parse(raw, false);
        } catch (RuntimeException ignored) { /* aşağıda varsayılan */ }
        return defaults("tr");
    }

    /** İstemci şablonunu KATI doğrular ve normalleştirir. Hata → {@link FieldValidationException} (alan {@code template}). */
    public static Template normalize(Object raw) {
        Template t = parse(raw, true);
        if (toJson(t).length() > MAX_JSON_CHARS) {
            throw invalid(Msg.t("Şablon çok büyük (en çok 64 KB). Bölüm, alan ya da metin sayısını azaltın.",
                    "The template is too large (at most 64 KB). Remove some sections, fields or text."));
        }
        return t;
    }

    public static String toJson(Template t) {
        return JSON.writeValueAsString(toMap(t));
    }

    public static Map<String, Object> toMap(Template t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("lang", t.lang());
        m.put("title", t.title());
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Section s : t.sections()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id());
            sm.put("kind", s.kind());
            sm.put("title", s.title());
            switch (s.kind()) {
                case "metrics" -> {
                    List<Map<String, Object>> fs = new ArrayList<>();
                    for (Field f : s.fields()) {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("id", f.id());
                        fm.put("label", f.label());
                        fm.put("type", f.type());
                        if (f.hint() != null && !f.hint().isEmpty()) fm.put("hint", f.hint());
                        if (f.skipIfNo() != null) fm.put("skip_if_no", f.skipIfNo());
                        fs.add(fm);
                    }
                    sm.put("fields", fs);
                }
                case "images" -> {
                    List<Map<String, Object>> ss = new ArrayList<>();
                    for (Slot sl : s.slots()) {
                        Map<String, Object> slm = new LinkedHashMap<>();
                        slm.put("id", sl.id());
                        slm.put("label", sl.label());
                        slm.put("width", sl.width());
                        ss.add(slm);
                    }
                    sm.put("slots", ss);
                }
                case "issues" -> {
                    sm.put("item_title", s.itemTitle());
                    sm.put("description_label", s.descriptionLabel());
                    sm.put("customers_label", s.customersLabel());
                    sm.put("errors_label", s.errorsLabel());
                }
                case "summary" -> sm.put("lines", new ArrayList<>(s.lines()));
                case "table" -> {
                    List<Map<String, Object>> cs = new ArrayList<>();
                    for (Column c : s.columns()) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("id", c.id());
                        cm.put("label", c.label());
                        cm.put("type", c.type());
                        cs.add(cm);
                    }
                    List<Map<String, Object>> rs = new ArrayList<>();
                    for (TableRow r : s.rows()) {
                        Map<String, Object> rm = new LinkedHashMap<>();
                        rm.put("id", r.id());
                        rm.put("label", r.label());
                        if (r.group()) rm.put("group", true);
                        rs.add(rm);
                    }
                    sm.put("columns", cs);
                    sm.put("rows", rs);
                }
                default -> sm.put("text", s.text() == null ? "" : s.text());
            }
            secs.add(sm);
        }
        m.put("sections", secs);
        return m;
    }

    // ── Ayrıştırma ───────────────────────────────────────────────────────────────────────────────────

    private static Template parse(Object raw, boolean strict) {
        if (!(raw instanceof Map<?, ?> map)) {
            if (strict) throw invalid(Msg.t("Şablon okunamadı: beklenen yapı gelmedi. Sayfayı yenileyip tekrar deneyin.",
                    "The template couldn't be read: the expected structure didn't arrive. Reload the page and try again."));
            return defaults("tr");
        }
        String lang = str(map.get("lang"));
        if (lang == null || !LANGS.contains(lang)) {
            if (strict && lang != null && !lang.isEmpty()) {
                throw invalid(Msg.t("Rapor dili Türkçe ya da İngilizce olmalı.", "The report language must be Turkish or English."));
            }
            lang = "tr";
        }
        String title = line(map.get("title"));
        if (title.isEmpty()) {
            if (strict) throw invalid(Msg.t("Rapor başlığı boş olamaz. Örnek: {kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}",
                    "The report title can't be empty. Example: {channel} Channel Technical Error Messages - {month} {year}"));
            title = defaults(lang).title();
        }
        title = cut(title, MAX_TITLE, strict, Msg.t("Rapor başlığı en çok 200 karakter olabilir.",
                "The report title can be at most 200 characters."));

        Object rs = map.get("sections");
        List<?> rawSections = rs instanceof List<?> l ? l : List.of();
        if (rawSections.isEmpty()) {
            if (strict) throw invalid(Msg.t("Şablonda en az bir bölüm olmalı.", "The template needs at least one section."));
            return new Template(lang, title, defaults(lang).sections());
        }
        if (rawSections.size() > MAX_SECTIONS) {
            if (strict) throw invalid(Msg.t("Şablonda en çok 20 bölüm olabilir.", "A template can have at most 20 sections."));
            rawSections = rawSections.subList(0, MAX_SECTIONS);
        }

        Set<String> sectionIds = new HashSet<>();
        Set<String> fieldIds = new HashSet<>();
        Set<String> slotIds = new HashSet<>();
        int slotCount = 0;
        List<Section> sections = new ArrayList<>();
        int si = 0;
        for (Object o : rawSections) {
            si++;
            if (!(o instanceof Map<?, ?> sm)) {
                if (strict) throw invalid(where(si) + Msg.t("bölüm okunamadı.", "the section couldn't be read."));
                continue;
            }
            String kind = str(sm.get("kind"));
            if (kind == null || !KINDS.contains(kind)) {
                if (strict) throw invalid(where(si) + Msg.t("bölüm türü tanınmadı.", "the section type isn't recognised."));
                continue;
            }
            String id = id(sm.get("id"), "s", si, sectionIds, strict, where(si));
            String stitle = line(sm.get("title"));
            if (stitle.isEmpty()) {
                if (strict) throw invalid(where(si) + Msg.t("bölüm başlığı boş olamaz.", "the section title can't be empty."));
                stitle = "—";
            }
            stitle = cut(stitle, MAX_LABEL, strict, where(si) + Msg.t("bölüm başlığı en çok 200 karakter olabilir.",
                    "the section title can be at most 200 characters."));
            switch (kind) {
                case "metrics" -> {
                    List<Field> fields = fields(sm.get("fields"), si, fieldIds, strict);
                    if (fields.isEmpty()) {
                        if (strict) throw invalid(where(si) + Msg.t("gösterge bölümünde en az bir alan olmalı.",
                                "a figures section needs at least one field."));
                        continue;
                    }
                    sections.add(new Section(id, kind, stitle, fields, List.of(), null, null, null, null, null));
                }
                case "images" -> {
                    List<Slot> slots = slots(sm.get("slots"), si, slotIds, strict, MAX_SLOTS - slotCount);
                    if (slots.isEmpty()) {
                        if (strict) throw invalid(where(si) + Msg.t("görsel bölümünde en az bir yuva olmalı.",
                                "an images section needs at least one slot."));
                        continue;
                    }
                    slotCount += slots.size();
                    sections.add(new Section(id, kind, stitle, List.of(), slots, null, null, null, null, null));
                }
                case "issues" -> {
                    boolean en = "en".equals(lang);
                    String item = labelOr(sm.get("item_title"), en ? "{topic} issue" : "{konu} Konusu ile yaşanan problem",
                            MAX_LABEL, strict, si);
                    String d = labelOr(sm.get("description_label"), en ? "When, where and how did it happen?"
                            : "Ne zaman, nerede, nasıl gerçekleşti?", MAX_ISSUE_LABEL, strict, si);
                    String c = labelOr(sm.get("customers_label"), en ? "Affected customers" : "Etkilenen müşteri sayısı",
                            MAX_ISSUE_LABEL, strict, si);
                    String e = labelOr(sm.get("errors_label"), en ? "Technical errors caused by this issue"
                            : "Bu sorundan kaynaklı teknik hata adedi", MAX_ISSUE_LABEL, strict, si);
                    sections.add(new Section(id, kind, stitle, List.of(), List.of(), item, d, c, e, null));
                }
                case "summary" -> {
                    List<String> lines = summaryLines(sm.get("lines"), si, strict);
                    if (lines.isEmpty()) {
                        if (strict) throw invalid(where(si) + Msg.t("yönetici özetinde en az bir cümle olmalı.",
                                "an executive summary needs at least one sentence."));
                        continue;
                    }
                    sections.add(new Section(id, kind, stitle, List.of(), List.of(), null, null, null, null, null,
                            lines, List.of(), List.of()));
                }
                case "table" -> {
                    List<Column> cols = columns(sm.get("columns"), si, strict);
                    List<TableRow> rows = tableRows(sm.get("rows"), si, strict);
                    if (cols.isEmpty() || rows.isEmpty()) {
                        if (strict) throw invalid(where(si) + Msg.t("tabloda en az bir sütun ve bir satır olmalı.",
                                "a table needs at least one column and one row."));
                        continue;
                    }
                    sections.add(new Section(id, kind, stitle, List.of(), List.of(), null, null, null, null, null,
                            List.of(), cols, rows));
                }
                default -> {
                    String text = multiline(sm.get("text"));
                    text = cut(text, MAX_SECTION_TEXT, strict, where(si) + Msg.t("metin en çok 10 000 karakter olabilir.",
                            "the text can be at most 10,000 characters."));
                    sections.add(new Section(id, kind, stitle, List.of(), List.of(), null, null, null, null, text));
                }
            }
        }
        if (sections.isEmpty()) return new Template(lang, title, defaults(lang).sections());
        List<Section> resolved = resolveSkipRefs(sections, strict);
        if (strict) validateSummaryRefs(resolved);
        return new Template(lang, title, resolved);
    }

    /**
     * Özet cümlelerinin YAZIMI denetlenir (kapanmamış ya da geçersiz kimlikli {@code {f:…}} → 400). Şablondan çıkarılmış
     * bir alana ya da türü evet/hayır olmayan bir alanda seçime dayanan cümle REDDEDİLMEZ: alan silmek özet yüzünden
     * kilitlenmesin; o cümle raporda kendiliğinden düşer ve şablon düzenleyicisi cümlenin altında uyarır
     * ({@code techErrorModel.summaryLineIssues}).
     */
    private static void validateSummaryRefs(List<Section> sections) {
        for (Section s : sections) {
            if (!"summary".equals(s.kind())) continue;
            for (String line : s.lines()) {
                if (TechErrorSummary.malformed(line)) {
                    throw invalid(Msg.t("Yönetici özeti: “" + line + "” cümlesinde okunamayan bir alan başvurusu var. Biçim {f:alan-kimliği} ya da {f:alan-kimliği?evet metni|hayır metni} olmalı.",
                            "Executive summary: the sentence “" + line + "” has a field reference that can't be read. Use {f:field-id} or {f:field-id?yes text|no text}."));
                }
            }
        }
    }

    private static List<String> summaryLines(Object raw, int si, boolean strict) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_SUMMARY_LINES) {
            if (strict) throw invalid(where(si) + Msg.t("yönetici özetinde en çok 20 cümle olabilir.",
                    "an executive summary can have at most 20 sentences."));
            list = list.subList(0, MAX_SUMMARY_LINES);
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String v = line(o);
            if (v.isEmpty()) continue;
            out.add(cut(v, MAX_SUMMARY_LINE, strict, where(si) + Msg.t("bir özet cümlesi en çok 500 karakter olabilir.",
                    "a summary sentence can be at most 500 characters.")));
        }
        return List.copyOf(out);
    }

    private static List<Column> columns(Object raw, int si, boolean strict) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_COLUMNS) {
            if (strict) throw invalid(where(si) + Msg.t("bir tabloda en çok 10 sütun olabilir.", "a table can have at most 10 columns."));
            list = list.subList(0, MAX_COLUMNS);
        }
        Set<String> ids = new HashSet<>();
        List<Column> out = new ArrayList<>();
        int ci = 0;
        for (Object o : list) {
            ci++;
            String at = where(si) + Msg.t(ci + ". sütun: ", "column " + ci + ": ");
            if (!(o instanceof Map<?, ?> m)) {
                if (strict) throw invalid(at + Msg.t("okunamadı.", "couldn't be read."));
                continue;
            }
            String label = line(m.get("label"));
            if (label.isEmpty()) {
                if (strict) throw invalid(at + Msg.t("sütun adı boş olamaz.", "the column name can't be empty."));
                continue;
            }
            label = cut(label, MAX_COLUMN_LABEL, strict, at + Msg.t("sütun adı en çok 120 karakter olabilir.",
                    "the column name can be at most 120 characters."));
            String type = str(m.get("type"));
            if (type == null || !COLUMN_TYPES.contains(type)) {
                if (strict && type != null && !type.isEmpty()) {
                    throw invalid(at + Msg.t("sütun türü sayı, yüzde ya da metin olmalı.", "the column type must be number, percentage or text."));
                }
                type = "number";
            }
            out.add(new Column(id(m.get("id"), "c", ci, ids, strict, at), label, type));
        }
        return List.copyOf(out);
    }

    private static List<TableRow> tableRows(Object raw, int si, boolean strict) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_ROWS) {
            if (strict) throw invalid(where(si) + Msg.t("bir tabloda en çok 40 satır olabilir.", "a table can have at most 40 rows."));
            list = list.subList(0, MAX_ROWS);
        }
        Set<String> ids = new HashSet<>();
        List<TableRow> out = new ArrayList<>();
        int ri = 0;
        for (Object o : list) {
            ri++;
            String at = where(si) + Msg.t(ri + ". satır: ", "row " + ri + ": ");
            if (!(o instanceof Map<?, ?> m)) {
                if (strict) throw invalid(at + Msg.t("okunamadı.", "couldn't be read."));
                continue;
            }
            String label = line(m.get("label"));
            if (label.isEmpty()) {
                if (strict) throw invalid(at + Msg.t("satır adı boş olamaz.", "the row name can't be empty."));
                continue;
            }
            label = cut(label, MAX_LABEL, strict, at + Msg.t("satır adı en çok 200 karakter olabilir.",
                    "the row name can be at most 200 characters."));
            out.add(new TableRow(id(m.get("id"), "r", ri, ids, strict, at), label, Boolean.TRUE.equals(m.get("group"))));
        }
        return List.copyOf(out);
    }

    /**
     * {@code skip_if_no} bağlarını doğrular: hedef aynı şablondaki bir EVET/HAYIR alanı olmalı, alanın kendisi olmamalı
     * ve kendisi başka bir soruya bağlı olmamalı (zincir / döngü yok). Katı: hata; hoşgörülü: bağ düşer.
     */
    private static List<Section> resolveSkipRefs(List<Section> sections, boolean strict) {
        Map<String, Field> byId = new HashMap<>();
        for (Section s : sections) for (Field f : s.fields()) byId.put(f.id(), f);
        List<Section> out = new ArrayList<>(sections.size());
        for (Section s : sections) {
            if (!"metrics".equals(s.kind())) { out.add(s); continue; }
            List<Field> fs = new ArrayList<>(s.fields().size());
            for (Field f : s.fields()) {
                String ref = f.skipIfNo();
                if (ref == null) { fs.add(f); continue; }
                Field target = byId.get(ref);
                boolean ok = target != null && !ref.equals(f.id()) && "yesno".equals(target.type()) && target.skipIfNo() == null;
                if (!ok && strict) {
                    throw invalid(Msg.t("“" + f.label() + "” alanı yalnız şablondaki bir evet/hayır sorusuna bağlanabilir "
                                    + "(kendisine ya da başka bir soruya bağlı bir soruya değil). Bağı kaldırıp yeniden seçin.",
                            "The “" + f.label() + "” field can only be tied to a yes/no question in the template "
                                    + "(not to itself or to a question that is tied to another one). Remove the link and pick again."));
                }
                fs.add(ok ? f : new Field(f.id(), f.label(), f.type(), f.hint(), null));
            }
            out.add(new Section(s.id(), s.kind(), s.title(), List.copyOf(fs), s.slots(), s.itemTitle(), s.descriptionLabel(),
                    s.customersLabel(), s.errorsLabel(), s.text(), s.lines(), s.columns(), s.rows()));
        }
        return List.copyOf(out);
    }

    private static List<Field> fields(Object raw, int si, Set<String> ids, boolean strict) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_FIELDS) {
            if (strict) throw invalid(where(si) + Msg.t("bir bölümde en çok 30 alan olabilir.", "a section can have at most 30 fields."));
            list = list.subList(0, MAX_FIELDS);
        }
        List<Field> out = new ArrayList<>();
        int fi = 0;
        for (Object o : list) {
            fi++;
            String at = where(si) + Msg.t(fi + ". alan: ", "field " + fi + ": ");
            if (!(o instanceof Map<?, ?> fm)) {
                if (strict) throw invalid(at + Msg.t("okunamadı.", "couldn't be read."));
                continue;
            }
            String type = str(fm.get("type"));
            if (type == null || !FIELD_TYPES.contains(type)) {
                if (strict) throw invalid(at + Msg.t("alan türü tanınmadı.", "the field type isn't recognised."));
                continue;
            }
            String label = line(fm.get("label"));
            if (label.isEmpty()) {
                if (strict) throw invalid(at + Msg.t("alan adı boş olamaz.", "the field name can't be empty."));
                continue;
            }
            label = cut(label, MAX_LABEL, strict, at + Msg.t("alan adı en çok 200 karakter olabilir.",
                    "the field name can be at most 200 characters."));
            String hint = cut(line(fm.get("hint")), MAX_HINT, strict, at + Msg.t("açıklama en çok 300 karakter olabilir.",
                    "the description can be at most 300 characters."));
            String id = id(fm.get("id"), "f", ids.size() + 1, ids, strict, at);
            // Bağ burada yalnız biçimce okunur; hedefin varlığı / türü tüm bölümler okununca denetlenir (resolveSkipRefs)
            String skip = str(fm.get("skip_if_no"));
            if (skip != null && (skip.isBlank() || !ID.matcher(skip).matches())) {
                if (strict && !skip.isBlank()) throw invalid(at + Msg.t("bağlı soru kimliği geçersiz.", "the linked question id is invalid."));
                skip = null;
            }
            out.add(new Field(id, label, type, hint.isEmpty() ? null : hint, skip));
        }
        return out;
    }

    private static List<Slot> slots(Object raw, int si, Set<String> ids, boolean strict, int room) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > room) {
            if (strict) throw invalid(where(si) + Msg.t("şablonda toplam en çok 40 görsel yuvası olabilir.",
                    "a template can have at most 40 image slots in total."));
            list = list.subList(0, Math.max(0, room));
        }
        List<Slot> out = new ArrayList<>();
        int i = 0;
        for (Object o : list) {
            i++;
            String at = where(si) + Msg.t(i + ". yuva: ", "slot " + i + ": ");
            if (!(o instanceof Map<?, ?> m)) {
                if (strict) throw invalid(at + Msg.t("okunamadı.", "couldn't be read."));
                continue;
            }
            String label = line(m.get("label"));
            if (label.isEmpty()) {
                if (strict) throw invalid(at + Msg.t("görsel adı boş olamaz.", "the image name can't be empty."));
                continue;
            }
            label = cut(label, MAX_LABEL, strict, at + Msg.t("görsel adı en çok 200 karakter olabilir.",
                    "the image name can be at most 200 characters."));
            String width = str(m.get("width"));
            if (width == null || !WIDTHS.contains(width)) {
                if (strict && width != null && !width.isEmpty()) {
                    throw invalid(at + Msg.t("genişlik yarım ya da tam olmalı.", "the width must be half or full."));
                }
                width = "half";
            }
            String id = id(m.get("id"), "g", ids.size() + 1, ids, strict, at);
            out.add(new Slot(id, label, width));
        }
        return out;
    }

    private static String labelOr(Object raw, String fallback, int max, boolean strict, int si) {
        String v = line(raw);
        if (v.isEmpty()) return fallback;
        return cut(v, max, strict, where(si) + Msg.t("etiket en çok " + max + " karakter olabilir.",
                "a label can be at most " + max + " characters."));
    }

    /** Kimlik: geçerliyse ve tekilse olduğu gibi; boşsa üretilir; geçersiz / tekrarlı → katıda hata, hoşgörülüde üretilir. */
    private static String id(Object raw, String prefix, int n, Set<String> used, boolean strict, String at) {
        String v = str(raw);
        if (v != null && !v.isEmpty()) {
            if (!ID.matcher(v).matches()) {
                if (strict) throw invalid(at + Msg.t("kimlik geçersiz.", "the id is invalid."));
                v = null;
            } else if (used.contains(v)) {
                if (strict) throw invalid(at + Msg.t("aynı kimlik iki kez kullanılmış; sayfayı yenileyip tekrar deneyin.",
                        "the same id is used twice; reload the page and try again."));
                v = null;
            }
        }
        if (v == null || v.isEmpty()) {
            int k = n;
            v = prefix + "-" + k;
            for (int guard = 0; used.contains(v) && guard < 10_000; guard++) v = prefix + "-" + (++k);
        }
        used.add(v);
        return v;
    }

    // ── Yer tutucular ve ay adları ───────────────────────────────────────────────────────────────────

    private static final String[] MONTHS_TR = { "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos",
            "Eylül", "Ekim", "Kasım", "Aralık" };
    private static final String[] MONTHS_EN = { "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December" };

    public static String monthName(int month, String lang) {
        if (month < 1 || month > 12) return String.valueOf(month);
        return ("en".equals(lang) ? MONTHS_EN : MONTHS_TR)[month - 1];
    }

    /** {@code {kanal}}, {@code {ay}}, {@code {yil}} (ve EN karşılıkları) → kanal adı, ay adı, yıl. Diğer metin aynen kalır. */
    public static String resolve(String pattern, String channel, int year, int month, String lang) {
        return capResolved(resolveRaw(pattern, channel, year, month, lang), MAX_RESOLVED);
    }

    /** Metin bölümü için aynı çözüm, daha geniş sınırla ({@link #MAX_RESOLVED_TEXT}). */
    public static String resolveText(String pattern, String channel, int year, int month, String lang) {
        return capResolved(resolveRaw(pattern, channel, year, month, lang), MAX_RESOLVED_TEXT);
    }

    private static String resolveRaw(String pattern, String channel, int year, int month, String lang) {
        if (pattern == null) return "";
        String ch = channel == null ? "" : channel;
        String mo = monthName(month, lang);
        String yr = String.valueOf(year);
        return pattern.replace("{kanal}", ch).replace("{channel}", ch)
                .replace("{ay}", mo).replace("{month}", mo)
                .replace("{yil}", yr).replace("{yıl}", yr).replace("{year}", yr);
    }

    /** {@code max} karakteri aşan çözümü "…" ile keser (vekil çiftini bölmeden). */
    static String capResolved(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    /** Anomali başlığı kalıbı: {@code {konu}} / {@code {topic}} → konu. Konu boşsa yer tutucu ve ardındaki boşluk düşer. */
    public static String resolveIssueTitle(String pattern, String topic) {
        String p = pattern == null ? "" : pattern;
        String t = topic == null ? "" : topic.trim();
        String out = p.replace("{konu}", t).replace("{topic}", t);
        return capResolved(out.replaceAll("\\s{2,}", " ").trim(), MAX_RESOLVED);
    }

    // ── Varsayılan şablon (kullanıcının verdiği rapor düzeni) ────────────────────────────────────────

    public static Template defaults(String lang) {
        return "en".equals(lang) ? defaultsEn() : defaultsTr();
    }

    private static Template defaultsTr() {
        List<Section> s = new ArrayList<>();
        s.add(summary("exec-summary", "Yönetici Özeti", List.of(
                "{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.",
                "Kesintiler hariç teknik hata oranı {f:error-rate-excl} olarak hesaplanmaktadır.",
                "{kanal} Kanalından yapılan toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.",
                "Bir önceki aya göre işlem adedinde {f:tx-increase-pct} {f:tx-increase?artış|azalış} görülmüştür.",
                "Ay içindeki teknik hata adedi {f:error-count} olarak gerçekleşmiştir.",
                "Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.",
                "Kota hatası adedi {f:quota-count} olarak kaydedilmiştir.",
                "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup toplam hataların {f:outage-share} kadarını oluşturmaktadır.",
                "SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} ile {f:sla-above?hedefin üzerinde|hedefin altında} gerçekleşmiştir.")));
        s.add(metrics("summary", "{kanal} Kanalı — {ay} {yil} Özeti", List.of(
                new Field("error-rate", "Teknik hata oranı", "percent", "İlgili ay/yıl verilerine göre"),
                new Field("had-outage", "Dönemde kesinti oldu mu?", "yesno",
                        "“Hayır” işaretlenirse kesinti günleri hariç oran ve kesinti kaynaklı hatalar istenmez, PDF'e girmez"),
                new Field("error-rate-excl", "Kesinti günleri hariç teknik hata oranı", "percent",
                        "Kesintilerin olduğu günlerde alınan hatalar çıkarıldığında", "had-outage"),
                new Field("total-tx", "Toplam işlem adedi", "number", "{kanal} kanalından yapılan toplam işlem"),
                new Field("tx-increase", "Önceki aya göre artış var mı?", "yesno", null),
                new Field("tx-increase-pct", "Önceki aya göre değişim oranı", "percent",
                        "Artış ya da azalış oranını işaretsiz yazın (ör. 3,5); yönü yukarıdaki soru belirler"),
                new Field("sla-rate", "Kritik fonksiyonların SLA oranı", "percent", "SLA taahhüdü kapsamındaki kritik fonksiyonlar"),
                new Field("sla-above", "SLA hedefin üstünde mi?", "yesno", null),
                new Field("error-count", "Ay içindeki teknik hata adedi", "number", null),
                new Field("outage-errors", "Kesinti kaynaklı teknik hata adedi", "number", null, "had-outage"),
                new Field("outage-share", "Kesinti kaynaklı hataların toplam hataya oranı", "percent", null, "had-outage"))));
        s.add(new Section("issues", "issues", "Teknik hataların anomali olarak arttığı konular", List.of(), List.of(),
                "{konu} Konusu ile yaşanan problem", "Ne zaman, nerede, nasıl gerçekleşti?", "Etkilenen müşteri sayısı",
                "Bu sorundan kaynaklı teknik hata adedi", null));
        s.add(metrics("unmapped", "Map Yapılmamış Hata Adedi", List.of(
                new Field("unmapped-count", "Map yapılmamış hata adedi", "number", null),
                new Field("unmapped-note", "Açıklama", "textarea", null))));
        s.add(metrics("quota", "Kota Hatası Adedi", List.of(
                new Field("quota-count", "Kota hatası adedi", "number", null),
                new Field("quota-note", "Açıklama", "textarea", null))));
        // SLA erişilebilirlik tablosu ELLE GİRİLMEZ (kullanıcı kararı 2026-10-15): raporlama aracındaki tablonun ekran
        // görüntüsü tam genişlikte (iki kolon) yüklenir — tek görüntü ya da iki ayrı görüntü (ör. iki alt kanal)
        s.add(new Section("sla-shots", "images", "SLA Erişilebilirlik Tablosu", List.of(), List.of(
                new Slot("sla-shot-1", "{kanal} Kanalı SLA Erişilebilirlik Tablosu", "full"),
                new Slot("sla-shot-2", "{kanal} Kanalı SLA Erişilebilirlik Tablosu (ikinci görüntü)", "full")),
                null, null, null, null, null));
        s.add(new Section("trend-note", "text", "Grafikler hakkında", List.of(), List.of(), null, null, null, null,
                "Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır."));
        // Grafik akışı (kullanıcı kararı 2026-10-15): 12 aylık özetler → ay bazlı (son 12 ay) → raporun ayının birim
        // dağılımı → gün bazlı. Her kırılım (toplam işlem, toplam hata, teknik, business, maplenmemiş, kota) iki tabanda da
        // var; adlar zaman tabanını açıkça söyler. Kimlikler eski varsayılanla aynı anlamda kaldıysa korunur.
        s.add(images("charts", "12 Aylık Özetler",
                new Slot("annual-summary", "Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)", "half"),
                new Slot("annual-tech-rate", "Yıllık Teknik Hata Oranı Trendi (Son 12 Ay)", "half"),
                new Slot("annual-table", "Son 12 Ay Operasyon ve Hata Özet Tablosu", "full")));
        s.add(images("charts-monthly", "Ay Bazlı Hata ve İşlem Adetleri (Son 12 Ay)",
                new Slot("monthly-tx", "Ay Bazlı Toplam İşlem Adetleri (Son 12 Ay)", "half"),
                new Slot("monthly-failed", "Ay Bazlı Toplam Hata (Başarısız İşlem) Adetleri (Son 12 Ay)", "half"),
                new Slot("tech-errors", "Ay Bazlı Teknik Hata Adetleri (Son 12 Ay)", "half"),
                new Slot("business-monthly", "Ay Bazlı Business Hata Adetleri (Son 12 Ay)", "half"),
                new Slot("unmapped-monthly", "Ay Bazlı Maplenmemiş Hata Adetleri (Son 12 Ay)", "half"),
                new Slot("quota-monthly", "Ay Bazlı Kota Hata Adetleri (Son 12 Ay)", "half"),
                new Slot("business-tech-monthly", "Ay Bazlı Business ve Teknik Hata Adetleri (Son 12 Ay)", "full"),
                new Slot("quota-unmapped-monthly", "Ay Bazlı Kota ve Maplenmemiş Hata Adetleri (Son 12 Ay)", "full")));
        s.add(images("charts-distribution", "{ay} {yil} Birim Bazlı Hata Dağılımı",
                new Slot("month-tech-counts", "{ay} {yil} Teknik Hata Dağılımı (Birim Bazlı)", "half"),
                new Slot("month-business-counts", "{ay} {yil} Business Hata Dağılımı (Birim Bazlı)", "half")));
        s.add(images("charts-daily", "Gün Bazlı Hata ve İşlem Adetleri ({ay} {yil})",
                new Slot("daily-tx", "Gün Bazlı Toplam İşlem Adetleri ({ay} {yil})", "half"),
                new Slot("daily-failed", "Gün Bazlı Toplam Hata (Başarısız İşlem) Adetleri ({ay} {yil})", "half"),
                new Slot("daily-errors", "Gün Bazlı Teknik Hata Adetleri ({ay} {yil})", "half"),
                new Slot("daily-business", "Gün Bazlı Business Hata Adetleri ({ay} {yil})", "half"),
                new Slot("month-unmapped-daily", "Gün Bazlı Maplenmemiş Hata Adetleri ({ay} {yil})", "half"),
                new Slot("month-quota-counts", "Gün Bazlı Kota Hata Adetleri ({ay} {yil})", "half"),
                new Slot("daily-tech-business", "Gün Bazlı Business ve Teknik Hata Adetleri ({ay} {yil})", "full"),
                new Slot("tech-ratio", "Gün Bazlı Teknik Hataların Tüm Operasyonlara Oranı ({ay} {yil})", "full"),
                new Slot("business-ratio", "Gün Bazlı Business Hataların Tüm Operasyonlara Oranı ({ay} {yil})", "full")));
        return new Template("tr", "{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}", List.copyOf(s));
    }

    private static Template defaultsEn() {
        List<Section> s = new ArrayList<>();
        s.add(summary("exec-summary", "Executive Summary", List.of(
                "Based on {month} {year} figures, the {channel} channel's technical error rate was {f:error-rate}.",
                "Excluding outage days, the technical error rate is {f:error-rate-excl}.",
                "Total transactions through {channel} were {f:total-tx}.",
                "Compared with the previous month, transactions show a {f:tx-increase-pct} {f:tx-increase?increase|decrease}.",
                "There were {f:error-count} technical errors in the month.",
                "The number of unmapped errors was {f:unmapped-count}.",
                "The number of quota errors was {f:quota-count}.",
                "{f:outage-errors} technical errors were caused by outages, {f:outage-share} of all errors.",
                "Under the SLA commitment the critical functions' SLA rate was {f:sla-rate}, {f:sla-above?above|below} the target.")));
        s.add(metrics("summary", "{channel} Channel — {month} {year} Summary", List.of(
                new Field("error-rate", "Technical error rate", "percent", "Based on this month's figures"),
                new Field("had-outage", "Was there an outage in the period?", "yesno",
                        "If “No”, the rate excluding outage days and the outage-caused errors aren't asked or printed"),
                new Field("error-rate-excl", "Technical error rate excluding outage days", "percent",
                        "With errors from outage days left out", "had-outage"),
                new Field("total-tx", "Total transactions", "number", "All transactions made through {channel}"),
                new Field("tx-increase", "Increase compared with the previous month?", "yesno", null),
                new Field("tx-increase-pct", "Change compared with the previous month", "percent",
                        "Enter the size of the increase or decrease without a sign (e.g. 3.5); the question above sets the direction"),
                new Field("sla-rate", "SLA rate of critical functions", "percent", "Critical functions covered by the SLA commitment"),
                new Field("sla-above", "Above the SLA target?", "yesno", null),
                new Field("error-count", "Technical errors in the month", "number", null),
                new Field("outage-errors", "Technical errors caused by outages", "number", null, "had-outage"),
                new Field("outage-share", "Share of outage-caused errors in all errors", "percent", null, "had-outage"))));
        s.add(new Section("issues", "issues", "Topics where technical errors rose abnormally", List.of(), List.of(),
                "{topic} issue", "When, where and how did it happen?", "Affected customers",
                "Technical errors caused by this issue", null));
        s.add(metrics("unmapped", "Unmapped Error Count", List.of(
                new Field("unmapped-count", "Unmapped errors", "number", null),
                new Field("unmapped-note", "Notes", "textarea", null))));
        s.add(metrics("quota", "Quota Error Count", List.of(
                new Field("quota-count", "Quota errors", "number", null),
                new Field("quota-note", "Notes", "textarea", null))));
        s.add(new Section("sla-shots", "images", "SLA Availability Table", List.of(), List.of(
                new Slot("sla-shot-1", "{channel} Channel SLA Availability Table", "full"),
                new Slot("sla-shot-2", "{channel} Channel SLA Availability Table (second screenshot)", "full")),
                null, null, null, null, null));
        s.add(new Section("trend-note", "text", "About the charts", List.of(), List.of(), null, null, null, null,
                "The yearly trend and detail charts are shared below."));
        s.add(images("charts", "12-Month Summaries",
                new Slot("annual-summary", "Annual Operations and Error Distribution Summary (Last 12 Months)", "half"),
                new Slot("annual-tech-rate", "Annual Technical Error Rate Trend (Last 12 Months)", "half"),
                new Slot("annual-table", "Last 12 Months Operations and Error Summary Table", "full")));
        s.add(images("charts-monthly", "Monthly Errors and Transactions (Last 12 Months)",
                new Slot("monthly-tx", "Monthly Total Transactions (Last 12 Months)", "half"),
                new Slot("monthly-failed", "Monthly Total Errors (Failed Transactions) (Last 12 Months)", "half"),
                new Slot("tech-errors", "Monthly Technical Errors (Last 12 Months)", "half"),
                new Slot("business-monthly", "Monthly Business Errors (Last 12 Months)", "half"),
                new Slot("unmapped-monthly", "Monthly Unmapped Errors (Last 12 Months)", "half"),
                new Slot("quota-monthly", "Monthly Quota Errors (Last 12 Months)", "half"),
                new Slot("business-tech-monthly", "Monthly Business and Technical Errors (Last 12 Months)", "full"),
                new Slot("quota-unmapped-monthly", "Monthly Quota and Unmapped Errors (Last 12 Months)", "full")));
        s.add(images("charts-distribution", "{month} {year} Error Distribution by Unit",
                new Slot("month-tech-counts", "{month} {year} Technical Error Distribution (by Unit)", "half"),
                new Slot("month-business-counts", "{month} {year} Business Error Distribution (by Unit)", "half")));
        s.add(images("charts-daily", "Daily Errors and Transactions ({month} {year})",
                new Slot("daily-tx", "Daily Total Transactions ({month} {year})", "half"),
                new Slot("daily-failed", "Daily Total Errors (Failed Transactions) ({month} {year})", "half"),
                new Slot("daily-errors", "Daily Technical Errors ({month} {year})", "half"),
                new Slot("daily-business", "Daily Business Errors ({month} {year})", "half"),
                new Slot("month-unmapped-daily", "Daily Unmapped Errors ({month} {year})", "half"),
                new Slot("month-quota-counts", "Daily Quota Errors ({month} {year})", "half"),
                new Slot("daily-tech-business", "Daily Business and Technical Errors ({month} {year})", "full"),
                new Slot("tech-ratio", "Daily Technical Errors as a Share of All Operations ({month} {year})", "full"),
                new Slot("business-ratio", "Daily Business Errors as a Share of All Operations ({month} {year})", "full")));
        return new Template("en", "{channel} Channel Technical Error Messages - {month} {year}", List.copyOf(s));
    }

    private static Section images(String id, String title, Slot... slots) {
        return new Section(id, "images", title, List.of(), List.of(slots), null, null, null, null, null);
    }

    private static Section metrics(String id, String title, List<Field> fields) {
        return new Section(id, "metrics", title, fields, List.of(), null, null, null, null, null);
    }

    private static Section summary(String id, String title, List<String> lines) {
        return new Section(id, "summary", title, List.of(), List.of(), null, null, null, null, null, lines, List.of(), List.of());
    }

    // ── Metin yardımcıları ───────────────────────────────────────────────────────────────────────────

    private static String where(int si) {
        return Msg.t(si + ". bölüm: ", "Section " + si + ": ");
    }

    static FieldValidationException invalid(String msg) {
        return new FieldValidationException("template", msg);
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    /** Tek satır: denetim karakterleri ve satır sonları boşluğa döner. */
    static String line(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(Math.min(s.length(), 4096));
        for (int i = 0; i < s.length() && b.length() < 4096; i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().replaceAll(" {2,}", " ").trim();
    }

    /**
     * Çok satır: \n korunur, diğer denetim karakterleri düşer, \r\n → \n. Satır BAŞINDAKİ sekme iki boşluk olur (zengin
     * metinde bir alt düzey — ekrandaki önizleme sekmeyi de iki boşluk sayar; inceleme 2026-10-14: Word / Outlook'tan
     * yapıştırılan "\t- madde" önizlemede alt madde, PDF'te üst madde çıkıyordu); satır içindeki sekme tek boşluk.
     */
    static String multiline(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).replace("\r\n", "\n").replace('\r', '\n');
        // Tarama sınırı en uzun izinli metin (çözülmüş metin bölümü, 12 000) + pay: üstünü çağıran sınır denetimi yakalar
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_RESOLVED_TEXT + 64));
        boolean lineStart = true;
        for (int i = 0; i < s.length() && b.length() <= MAX_RESOLVED_TEXT + 32; i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                b.append(lineStart ? "  " : " ");
                continue;
            }
            if (c == '\n' || !Character.isISOControl(c)) b.append(c);
            lineStart = c == '\n' || (lineStart && c == ' ');
        }
        // Baştaki BOŞ satırlar ve sondaki boşluk düşer; ilk dolu satırın girintisi KALIR ("  - alt madde" ile başlayan
        // açıklama PDF'te de alt madde — eskiden strip() onu üst maddeye çeviriyordu)
        String out = b.toString();
        int from = 0;
        for (int k = 0; k < out.length(); ) {
            int nl = out.indexOf('\n', k);
            int end = nl < 0 ? out.length() : nl;
            if (!TechErrorRichText.blank(out.substring(k, end))) { from = k; break; }
            if (nl < 0) { from = out.length(); break; }
            k = nl + 1;
            from = k;
        }
        return out.substring(from).stripTrailing();
    }

    private static String cut(String s, int max, boolean strict, String msg) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (strict) throw invalid(msg);
        return s.substring(0, max);
    }
}
