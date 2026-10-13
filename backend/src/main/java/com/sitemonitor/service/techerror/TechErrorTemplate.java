package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
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
 *   <li>{@code images} — grafik yuvaları; {@code half} iki kolonlu ızgarada bir kolon, {@code full} iki kolon genişliği.</li>
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

    public static final List<String> KINDS = List.of("metrics", "issues", "text", "images");
    public static final List<String> FIELD_TYPES = List.of("number", "percent", "yesno", "text", "textarea");
    public static final List<String> WIDTHS = List.of("half", "full");
    public static final List<String> LANGS = List.of("tr", "en");

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final ObjectMapper JSON = new ObjectMapper();

    public record Field(String id, String label, String type, String hint) { }

    public record Slot(String id, String label, String width) { }

    public record Section(String id, String kind, String title, List<Field> fields, List<Slot> slots, String itemTitle,
                          String descriptionLabel, String customersLabel, String errorsLabel, String text) { }

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
                        if (strict) throw invalid(where(si) + Msg.t("grafik bölümünde en az bir yuva olmalı.",
                                "a charts section needs at least one slot."));
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
                default -> {
                    String text = multiline(sm.get("text"));
                    text = cut(text, MAX_SECTION_TEXT, strict, where(si) + Msg.t("metin en çok 10 000 karakter olabilir.",
                            "the text can be at most 10,000 characters."));
                    sections.add(new Section(id, kind, stitle, List.of(), List.of(), null, null, null, null, text));
                }
            }
        }
        if (sections.isEmpty()) return new Template(lang, title, defaults(lang).sections());
        return new Template(lang, title, List.copyOf(sections));
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
            out.add(new Field(id, label, type, hint.isEmpty() ? null : hint));
        }
        return out;
    }

    private static List<Slot> slots(Object raw, int si, Set<String> ids, boolean strict, int room) {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (list.size() > room) {
            if (strict) throw invalid(where(si) + Msg.t("şablonda toplam en çok 40 grafik yuvası olabilir.",
                    "a template can have at most 40 chart slots in total."));
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
                if (strict) throw invalid(at + Msg.t("grafik adı boş olamaz.", "the chart name can't be empty."));
                continue;
            }
            label = cut(label, MAX_LABEL, strict, at + Msg.t("grafik adı en çok 200 karakter olabilir.",
                    "the chart name can be at most 200 characters."));
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
        s.add(metrics("summary", "{kanal} Kanalı — {ay} {yil} Özeti", List.of(
                new Field("error-rate", "Teknik hata oranı", "percent", "İlgili ay/yıl verilerine göre"),
                new Field("error-rate-excl", "Kesinti günleri hariç teknik hata oranı", "percent",
                        "Kesintilerin olduğu günlerde alınan hatalar çıkarıldığında"),
                new Field("total-tx", "Toplam işlem adedi", "number", "{kanal} kanalından yapılan toplam işlem"),
                new Field("tx-increase", "Önceki aya göre artış var mı?", "yesno", null),
                new Field("tx-increase-pct", "Önceki aya göre artış oranı", "percent", null),
                new Field("sla-rate", "Kritik fonksiyonların SLA oranı", "percent", "SLA taahhüdü kapsamındaki kritik fonksiyonlar"),
                new Field("sla-above", "SLA hedefin üstünde mi?", "yesno", null),
                new Field("error-count", "Ay içindeki teknik hata adedi", "number", null),
                new Field("outage-errors", "Kesinti kaynaklı teknik hata adedi", "number", null),
                new Field("outage-share", "Kesinti kaynaklı hataların toplam hataya oranı", "percent", null))));
        s.add(new Section("issues", "issues", "Teknik hataların anomali olarak arttığı konular", List.of(), List.of(),
                "{konu} Konusu ile yaşanan problem", "Ne zaman, nerede, nasıl gerçekleşti?", "Etkilenen müşteri sayısı",
                "Bu sorundan kaynaklı teknik hata adedi", null));
        s.add(metrics("unmapped", "Map Yapılmamış Hata Adedi", List.of(
                new Field("unmapped-count", "Map yapılmamış hata adedi", "number", null),
                new Field("unmapped-note", "Açıklama", "textarea", null))));
        s.add(metrics("quota", "Kota Hatası Adedi", List.of(
                new Field("quota-count", "Kota hatası adedi", "number", null),
                new Field("quota-note", "Açıklama", "textarea", null))));
        s.add(new Section("trend-note", "text", "Grafikler hakkında", List.of(), List.of(), null, null, null, null,
                "Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır."));
        s.add(new Section("charts", "images", "Grafikler", List.of(), List.of(
                new Slot("monthly-tx", "{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri", "half"),
                new Slot("tech-errors", "{kanal} Kanalı Teknik Hata Grafiği", "half"),
                new Slot("unmapped-monthly", "{kanal} Kanalı Maplenmemiş Hata Grafiği (Aylık)", "half"),
                new Slot("quota-monthly", "Kota Hata Grafiği (Aylık)", "half"),
                new Slot("daily-errors", "{kanal} Kanalı Teknik Hata (Günlük)", "full"),
                new Slot("summary-monthly", "{kanal} Kanalı Özet (Aylık)", "full"),
                new Slot("month-tech-counts", "{ay} {yil} Teknik Hata Adetleri", "full"),
                new Slot("month-business-counts", "{ay} {yil} Business Hata Adetleri", "full"),
                new Slot("quota-unmapped-monthly", "Kota ve Maplenmemiş Hata Adetleri (Ay Bazlı)", "full"),
                new Slot("daily-tech-business", "Gün Bazlı Teknik ve Business Hata Adetleri", "full"),
                new Slot("month-quota-counts", "{ay} {yil} Kota Hata Adetleri", "full"),
                new Slot("month-unmapped-daily", "{ay} {yil} Maplenmemiş Hata Adetleri (Gün Bazlı)", "full"),
                new Slot("tech-ratio", "{ay} {yil} Teknik Hataların Tüm Operasyonlara Oranı", "full"),
                new Slot("business-ratio", "{ay} {yil} Business Hataların Tüm Operasyonlara Oranı", "full")),
                null, null, null, null, null));
        return new Template("tr", "{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}", List.copyOf(s));
    }

    private static Template defaultsEn() {
        List<Section> s = new ArrayList<>();
        s.add(metrics("summary", "{channel} Channel — {month} {year} Summary", List.of(
                new Field("error-rate", "Technical error rate", "percent", "Based on this month's figures"),
                new Field("error-rate-excl", "Technical error rate excluding outage days", "percent",
                        "With errors from outage days left out"),
                new Field("total-tx", "Total transactions", "number", "All transactions made through {channel}"),
                new Field("tx-increase", "Increase compared with the previous month?", "yesno", null),
                new Field("tx-increase-pct", "Increase compared with the previous month", "percent", null),
                new Field("sla-rate", "SLA rate of critical functions", "percent", "Critical functions covered by the SLA commitment"),
                new Field("sla-above", "Above the SLA target?", "yesno", null),
                new Field("error-count", "Technical errors in the month", "number", null),
                new Field("outage-errors", "Technical errors caused by outages", "number", null),
                new Field("outage-share", "Share of outage-caused errors in all errors", "percent", null))));
        s.add(new Section("issues", "issues", "Topics where technical errors rose abnormally", List.of(), List.of(),
                "{topic} issue", "When, where and how did it happen?", "Affected customers",
                "Technical errors caused by this issue", null));
        s.add(metrics("unmapped", "Unmapped Error Count", List.of(
                new Field("unmapped-count", "Unmapped errors", "number", null),
                new Field("unmapped-note", "Notes", "textarea", null))));
        s.add(metrics("quota", "Quota Error Count", List.of(
                new Field("quota-count", "Quota errors", "number", null),
                new Field("quota-note", "Notes", "textarea", null))));
        s.add(new Section("trend-note", "text", "About the charts", List.of(), List.of(), null, null, null, null,
                "The yearly trend and detail charts are shared below."));
        s.add(new Section("charts", "images", "Charts", List.of(), List.of(
                new Slot("monthly-tx", "{channel} Channel Monthly Total Transactions", "half"),
                new Slot("tech-errors", "{channel} Channel Technical Errors", "half"),
                new Slot("unmapped-monthly", "{channel} Channel Unmapped Errors (Monthly)", "half"),
                new Slot("quota-monthly", "Quota Errors (Monthly)", "half"),
                new Slot("daily-errors", "{channel} Channel Technical Errors (Daily)", "full"),
                new Slot("summary-monthly", "{channel} Channel Summary (Monthly)", "full"),
                new Slot("month-tech-counts", "{month} {year} Technical Error Counts", "full"),
                new Slot("month-business-counts", "{month} {year} Business Error Counts", "full"),
                new Slot("quota-unmapped-monthly", "Quota and Unmapped Error Counts (Monthly)", "full"),
                new Slot("daily-tech-business", "Daily Technical and Business Error Counts", "full"),
                new Slot("month-quota-counts", "{month} {year} Quota Error Counts", "full"),
                new Slot("month-unmapped-daily", "{month} {year} Unmapped Error Counts (Daily)", "full"),
                new Slot("tech-ratio", "{month} {year} Technical Errors as a Share of All Operations", "full"),
                new Slot("business-ratio", "{month} {year} Business Errors as a Share of All Operations", "full")),
                null, null, null, null, null));
        return new Template("en", "{channel} Channel Technical Error Messages - {month} {year}", List.copyOf(s));
    }

    private static Section metrics(String id, String title, List<Field> fields) {
        return new Section(id, "metrics", title, fields, List.of(), null, null, null, null, null);
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

    /** Çok satır: \n korunur, diğer denetim karakterleri düşer, \r\n → \n. */
    static String multiline(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).replace("\r\n", "\n").replace('\r', '\n');
        // Tarama sınırı en uzun izinli metin (çözülmüş metin bölümü, 12 000) + pay: üstünü çağıran sınır denetimi yakalar
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_RESOLVED_TEXT + 64));
        for (int i = 0; i < s.length() && b.length() <= MAX_RESOLVED_TEXT + 32; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) b.append(c == '\t' ? ' ' : c);
        }
        return b.toString().strip();
    }

    private static String cut(String s, int max, boolean strict, String msg) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (strict) throw invalid(msg);
        return s.substring(0, max);
    }
}
