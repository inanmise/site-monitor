package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Nöbet rutini şablonu (2026-11-26) — SAF model + doğrulama. Gönderilen şablon KATI okunur (her kural için alan adlı 400:
 * {@code template.<bölüm>.<görev>.<özellik>}); saklanan şablon ESNEK okunur (bozuk görev / bağlantı atlanır, ayrıştırılamayan
 * belge boş şablon + {@code unreadable}). Bilinmeyen anahtarlar düşer. Kanonik JSON = {@link #toJson} (eşitlik ve sürüm
 * karşılaştırması bununla). Bağlantı adresi URI ile ayrıştırılmadan ÖNCE uzunluk denetlenir.
 */
public final class OnCallRoutineTemplate {

    private OnCallRoutineTemplate() {}

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final Pattern HHMM = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
    static final ObjectMapper JSON = new ObjectMapper();

    // ── Sınırlar (ön yüz bunları okur) ────────────────────────────────────────────────────────────
    public static final int MAX_SECTIONS = 30;
    public static final int MAX_TASKS_PER_SECTION = 100;
    public static final int MAX_TASKS = 300;
    public static final int MAX_ACTIVE_TASKS = 200;
    public static final int MAX_SECTION_TITLE = 120;
    public static final int MAX_TITLE = 200;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_LINKS_PER_TASK = 20;
    public static final int MAX_LINKS = 1000;
    public static final int MAX_URL = 2000;
    public static final int MAX_LABEL = 200;
    public static final int MAX_SIGNALS_PER_TASK = 4;
    /** Ham gövde sınırı (ayrıştırmadan önce). */
    public static final int MAX_JSON_BYTES = 512 * 1024;

    public record Link(String label, String url) { }

    public record Task(String id, String title, String description, List<Link> links, List<String> signals,
                       boolean required, boolean active, String frequency, Integer weekday, String monthlyRule,
                       Integer monthDay, Integer nth, String windowFrom, String windowTo) {
        public Task {
            links = links == null ? List.of() : List.copyOf(links);
            signals = signals == null ? List.of() : List.copyOf(signals);
        }
    }

    public record Section(String id, String title, String windowFrom, String windowTo, List<Task> tasks) {
        public Section {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record Template(List<Section> sections) {
        public Template {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        public static Template empty() {
            return new Template(List.of());
        }

        public int taskCount() {
            int n = 0;
            for (Section s : sections) n += s.tasks().size();
            return n;
        }

        public int activeCount() {
            int n = 0;
            for (Section s : sections) for (Task t : s.tasks()) if (t.active()) n++;
            return n;
        }

        /** Görev + bölümü; yoksa null. */
        public Located find(String taskId) {
            if (taskId == null) return null;
            for (Section s : sections) for (Task t : s.tasks()) if (taskId.equals(t.id())) return new Located(s, t);
            return null;
        }
    }

    public record Located(Section section, Task task) { }

    /** Saklanan şablonun okunuşu: {@code unreadable} = belge vardı ama ayrıştırılamadı (sayfa "şablon okunamadı" der). */
    public record Read(Template template, boolean unreadable) { }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────────

    /** Saklanan şablon — ESNEK; asla fırlatmaz. */
    public static Read readStored(String json) {
        if (json == null || json.isBlank()) return new Read(Template.empty(), false);
        try {
            Object raw = JSON.readValue(json, Object.class);
            return new Read(parse(raw, false), false);
        } catch (RuntimeException e) {
            return new Read(Template.empty(), true);
        }
    }

    /** Gönderilen şablon — KATI; kural ihlalinde alan adlı {@link FieldValidationException}. */
    public static Template parseStrict(Object raw) {
        return parse(raw, true);
    }

    /**
     * 512 KB sınırı: istek gövdesinin bildirilen uzunluğu (biliniyorsa, ayrıştırmadan önce — denetleyici) ve saklanacak kanonik
     * JSON'un UTF-8 boyu (servis). Aşılırsa alan hatası {@code template}. Negatif / bilinmeyen uzunluk denetlenmez.
     */
    public static void checkSize(long bytes) {
        if (bytes > MAX_JSON_BYTES) {
            throw field("template", "Şablon çok büyük (en çok " + (MAX_JSON_BYTES / 1024) + " KB). Açıklamaları kısaltın ya da "
                            + "kullanılmayan görevleri silin.",
                    "The template is too large (at most " + (MAX_JSON_BYTES / 1024) + " KB). Shorten the descriptions or delete "
                            + "tasks you don't use.");
        }
    }

    private static Template parse(Object raw, boolean strict) {
        if (!(raw instanceof Map<?, ?> root)) {
            if (strict) throw field("template", "Şablon gönderilmedi ya da biçimi bozuk. Sayfayı yenileyip yeniden deneyin.",
                    "The template wasn't sent or its format is broken. Reload the page and try again.");
            return Template.empty();
        }
        Object secs = root.get("sections");
        if (!(secs instanceof List<?> list)) {
            if (strict) throw field("template", "Şablonda bölüm listesi yok. En az bir bölüm ekleyip kaydedin.",
                    "The template has no section list. Add at least one section and save.");
            return Template.empty();
        }
        if (strict && list.isEmpty()) {
            throw field("template", "Şablonda en az bir bölüm olmalı. Bir bölüm ekleyip yeniden kaydedin.",
                    "The template needs at least one section. Add a section and save again.");
        }
        if (strict && list.size() > MAX_SECTIONS) {
            throw field("template", "Şablonda en çok " + MAX_SECTIONS + " bölüm olabilir. Bazı bölümleri birleştirip yeniden kaydedin.",
                    "A template can have at most " + MAX_SECTIONS + " sections. Merge some sections and save again.");
        }
        Set<String> ids = new HashSet<>();
        List<Section> sections = new ArrayList<>();
        int total = 0, active = 0, links = 0;
        for (Object o : list) {
            if (sections.size() >= MAX_SECTIONS) break;
            if (!(o instanceof Map<?, ?> sm)) {
                if (strict) throw field("template", "Şablondaki bir bölümün biçimi bozuk. Sayfayı yenileyip yeniden deneyin.",
                        "A section of the template has a broken format. Reload the page and try again.");
                continue;
            }
            String sid = str(sm.get("id"));
            if (sid == null || !ID.matcher(sid).matches() || ids.contains(sid)) {
                if (strict) throw field("template", "Bir bölümün kimliği geçersiz ya da yinelenmiş. Sayfayı yenileyip yeniden deneyin.",
                        "A section has an invalid or repeated id. Reload the page and try again.");
                continue;
            }
            String sp = "template." + sid + ".";
            String title = singleLine(sm.get("title"));
            if (title == null || title.isEmpty() || title.length() > MAX_SECTION_TITLE) {
                if (strict) throw field(sp + "title", "Bölüm adı 1–" + MAX_SECTION_TITLE + " karakter olmalı. Kısa bir ad yazın.",
                        "The section name must be 1–" + MAX_SECTION_TITLE + " characters. Enter a short name.");
                if (title == null || title.isEmpty()) continue;
                title = title.substring(0, MAX_SECTION_TITLE);
            }
            String[] win = window(sm.get("window_from"), sm.get("window_to"), sp, strict);
            Object tl = sm.get("tasks");
            List<Task> tasks = new ArrayList<>();
            ids.add(sid);
            if (tl instanceof List<?> tasksRaw) {
                if (strict && tasksRaw.size() > MAX_TASKS_PER_SECTION) {
                    throw field(sp + "tasks", "Bir bölümde en çok " + MAX_TASKS_PER_SECTION + " görev olabilir. Görevleri başka bölümlere dağıtın.",
                            "A section can have at most " + MAX_TASKS_PER_SECTION + " tasks. Spread the tasks over other sections.");
                }
                for (Object to : tasksRaw) {
                    if (total >= MAX_TASKS) {
                        if (strict) throw field("template", "Şablonda en çok " + MAX_TASKS + " görev olabilir. Kullanılmayan görevleri silin.",
                                "A template can have at most " + MAX_TASKS + " tasks. Delete tasks you don't use.");
                        break;
                    }
                    if (tasks.size() >= MAX_TASKS_PER_SECTION) break;
                    Task t = task(to, sp, ids, strict);
                    if (t == null) continue;
                    if (t.active() && active >= MAX_ACTIVE_TASKS) {
                        if (strict) throw field("template", "Şablonda en çok " + MAX_ACTIVE_TASKS + " etkin görev olabilir. Bazı görevleri pasife alın.",
                                "A template can have at most " + MAX_ACTIVE_TASKS + " active tasks. Deactivate some tasks.");
                        continue;
                    }
                    if (links + t.links().size() > MAX_LINKS) {
                        if (strict) throw field("template", "Şablonda en çok " + MAX_LINKS + " bağlantı olabilir. Bazı bağlantıları kaldırın.",
                                "A template can have at most " + MAX_LINKS + " links. Remove some links.");
                        t = new Task(t.id(), t.title(), t.description(), List.of(), t.signals(), t.required(), t.active(),
                                t.frequency(), t.weekday(), t.monthlyRule(), t.monthDay(), t.nth(), t.windowFrom(), t.windowTo());
                    }
                    ids.add(t.id());
                    tasks.add(t);
                    total++;
                    links += t.links().size();
                    if (t.active()) active++;
                }
            } else if (tl != null && strict) {
                throw field(sp + "tasks", "Bölümün görev listesi bozuk. Sayfayı yenileyip yeniden deneyin.",
                        "The section's task list is broken. Reload the page and try again.");
            }
            sections.add(new Section(sid, title, win[0], win[1], tasks));
        }
        return new Template(sections);
    }

    private static Task task(Object o, String sp, Set<String> ids, boolean strict) {
        if (!(o instanceof Map<?, ?> tm)) {
            if (strict) throw field(sp + "tasks", "Bir görevin biçimi bozuk. Sayfayı yenileyip yeniden deneyin.",
                    "A task has a broken format. Reload the page and try again.");
            return null;
        }
        String tid = str(tm.get("id"));
        if (tid == null || !ID.matcher(tid).matches() || ids.contains(tid)) {
            if (strict) throw field(sp + "tasks", "Bir görevin kimliği geçersiz ya da yinelenmiş. Sayfayı yenileyip yeniden deneyin.",
                    "A task has an invalid or repeated id. Reload the page and try again.");
            return null;
        }
        String p = sp + tid + ".";
        String title = singleLine(tm.get("title"));
        if (title == null || title.isEmpty() || title.length() > MAX_TITLE) {
            if (strict) throw field(p + "title", "Görev adı 1–" + MAX_TITLE + " karakter olmalı. Görevi kısaca adlandırın.",
                    "The task name must be 1–" + MAX_TITLE + " characters. Give the task a short name.");
            if (title == null || title.isEmpty()) return null;
            title = title.substring(0, MAX_TITLE);
        }
        String desc = multiLine(tm.get("description"));
        if (desc != null && desc.length() > MAX_DESCRIPTION) {
            if (strict) throw field(p + "description", "Açıklama en çok " + MAX_DESCRIPTION + " karakter olabilir. Kısaltın.",
                    "The description can be at most " + MAX_DESCRIPTION + " characters. Shorten it.");
            desc = desc.substring(0, MAX_DESCRIPTION);
        }
        if (desc != null && desc.isEmpty()) desc = null;
        List<Link> links = links(tm.get("links"), p, strict);
        List<String> signals = signals(tm.get("signals"), p, strict);
        Boolean required = bool(tm.get("required"), true, p + "required", strict);
        Boolean activeFlag = bool(tm.get("active"), true, p + "active", strict);
        if (required == null || activeFlag == null) return null;
        String freq = str(tm.get("frequency"));
        if (freq == null || freq.isBlank()) freq = "DAILY";
        if (!OnCallRoutineCodes.FREQUENCIES.contains(freq)) {
            if (strict) throw field(p + "frequency", "Sıklık Günlük, Haftalık ya da Aylık olmalı.",
                    "The frequency must be Daily, Weekly or Monthly.");
            return null;
        }
        Integer weekday = null, monthDay = null, nth = null;
        String rule = null;
        if ("WEEKLY".equals(freq)) {
            weekday = intIn(tm.get("weekday"), 1, 7, 5, p + "weekday", strict,
                    "Haftanın günü 1 (Pazartesi) ile 7 (Pazar) arasında olmalı.", "The weekday must be between 1 (Monday) and 7 (Sunday).");
            if (weekday == null) return null;
        } else if ("MONTHLY".equals(freq)) {
            rule = str(tm.get("monthly_rule"));
            if (rule == null || !OnCallRoutineCodes.MONTHLY_RULES.contains(rule)) {
                if (strict) throw field(p + "monthly_rule", "Aylık görev için kural seçin: ilk iş günü, son iş günü, ayın günü ya da ayın n. günü.",
                        "Choose a rule for the monthly task: first workday, last workday, day of month or nth weekday.");
                return null;
            }
            if ("DAY".equals(rule)) {
                monthDay = intIn(tm.get("month_day"), 1, 31, null, p + "month_day", strict,
                        "Ayın günü 1 ile 31 arasında olmalı.", "The day of the month must be between 1 and 31.");
                if (monthDay == null) return null;
            } else if ("NTH_WEEKDAY".equals(rule)) {
                nth = intIn(tm.get("nth"), 1, 5, null, p + "nth", strict,
                        "Kaçıncı hafta 1 ile 5 (son) arasında olmalı.", "The week number must be between 1 and 5 (last).");
                weekday = intIn(tm.get("weekday"), 1, 7, null, p + "weekday", strict,
                        "Haftanın günü 1 (Pazartesi) ile 7 (Pazar) arasında olmalı.", "The weekday must be between 1 (Monday) and 7 (Sunday).");
                if (nth == null || weekday == null) return null;
            }
        }
        String[] win = window(tm.get("window_from"), tm.get("window_to"), p, strict);
        return new Task(tid, title, desc, links, signals, required, activeFlag, freq, weekday, rule, monthDay, nth, win[0], win[1]);
    }

    private static List<Link> links(Object raw, String p, boolean strict) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            if (strict) throw field(p + "links", "Bağlantı listesi bozuk. Sayfayı yenileyip yeniden deneyin.",
                    "The link list is broken. Reload the page and try again.");
            return List.of();
        }
        if (strict && list.size() > MAX_LINKS_PER_TASK) {
            throw field(p + "links", "Bir görevde en çok " + MAX_LINKS_PER_TASK + " bağlantı olabilir. Fazlasını kaldırın.",
                    "A task can have at most " + MAX_LINKS_PER_TASK + " links. Remove the extra ones.");
        }
        List<Link> out = new ArrayList<>();
        int i = 0;
        for (Object o : list) {
            int idx = i++;
            if (out.size() >= MAX_LINKS_PER_TASK) break;
            if (!(o instanceof Map<?, ?> lm)) {
                if (strict) throw field(p + "links." + idx + ".url", "Bağlantının biçimi bozuk.", "The link has a broken format.");
                continue;
            }
            String url = str(lm.get("url"));
            String problem = urlProblem(url);
            if (problem != null) {
                if (strict) throw field(p + "links." + idx + ".url", problem, problem);
                continue;
            }
            String label = singleLine(lm.get("label"));
            if (label != null && label.length() > MAX_LABEL) {
                if (strict) throw field(p + "links." + idx + ".label", "Bağlantı adı en çok " + MAX_LABEL + " karakter olabilir.",
                        "A link name can be at most " + MAX_LABEL + " characters.");
                label = label.substring(0, MAX_LABEL);
            }
            out.add(new Link(label == null ? "" : label, url.trim()));
        }
        return out;
    }

    /**
     * Bağlantı adresi kuralı: yalnız mutlak {@code http}/{@code https}, en çok 2000 karakter (URI ayrıştırmasından ÖNCE),
     * kullanıcı bilgisi ({@code kullanıcı:parola@}) yok. Sorun yoksa null, varsa istek dilinde ileti.
     */
    public static String urlProblem(String raw) {
        if (raw == null || raw.isBlank()) {
            return Msg.t("Bağlantı adresi boş. Adresi yapıştırın ya da satırı silin.", "The link address is empty. Paste the address or remove the row.");
        }
        String url = raw.trim();
        if (url.length() > MAX_URL) {
            return Msg.t("Bağlantı adresi en çok " + MAX_URL + " karakter olabilir.", "A link address can be at most " + MAX_URL + " characters.");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return Msg.t("Bağlantı adresi geçerli değil. http:// ya da https:// ile başlayan tam bir adres yazın.",
                    "The link address isn't valid. Enter a full address starting with http:// or https://.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            return Msg.t("Yalnız http:// ya da https:// ile başlayan tam adresler eklenebilir.",
                    "Only full addresses starting with http:// or https:// can be added.");
        }
        if (uri.getRawUserInfo() != null) {
            return Msg.t("Adreste kullanıcı adı / parola olamaz (kullanıcı:parola@). Kimlik bilgisini adresten çıkarın.",
                    "The address can't contain a user name / password (user:pass@). Remove the credentials from the address.");
        }
        return null;
    }

    private static List<String> signals(Object raw, String p, boolean strict) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            if (strict) throw field(p + "signals", "Canlı sayaç listesi bozuk.", "The live counter list is broken.");
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            String k = str(o);
            if (k == null || !OnCallRoutineSignals.KEYS.contains(k)) {
                if (strict) throw field(p + "signals", "Bilinmeyen canlı sayaç seçilmiş. Sayfayı yenileyip yeniden seçin.",
                        "An unknown live counter was chosen. Reload the page and choose again.");
                continue;
            }
            if (!out.add(k) && strict) {
                throw field(p + "signals", "Aynı canlı sayaç iki kez seçilmiş.", "The same live counter was chosen twice.");
            }
        }
        if (out.size() > MAX_SIGNALS_PER_TASK) {
            if (strict) throw field(p + "signals", "Bir göreve en çok " + MAX_SIGNALS_PER_TASK + " canlı sayaç eklenebilir.",
                    "A task can have at most " + MAX_SIGNALS_PER_TASK + " live counters.");
            return new ArrayList<>(out).subList(0, MAX_SIGNALS_PER_TASK);
        }
        return new ArrayList<>(out);
    }

    private static String[] window(Object fromRaw, Object toRaw, String p, boolean strict) {
        String from = hhmm(fromRaw, p + "window_from", strict);
        String to = hhmm(toRaw, p + "window_to", strict);
        if (from != null && to != null && from.compareTo(to) >= 0) {
            if (strict) throw field(p + "window_to", "Bitiş saati başlangıç saatinden sonra olmalı.",
                    "The end time must be after the start time.");
            to = null;
        }
        return new String[] {from, to};
    }

    private static String hhmm(Object raw, String field, boolean strict) {
        if (raw == null) return null;
        String s = str(raw);
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (!HHMM.matcher(s).matches()) {
            if (strict) throw field(field, "Saat SS:dd biçiminde olmalı (ör. 10:30).", "The time must be HH:mm (e.g. 10:30).");
            return null;
        }
        return s;
    }

    private static Integer intIn(Object raw, int min, int max, Integer def, String field, boolean strict, String tr, String en) {
        if (raw == null) {
            if (def != null) return def;
            if (strict) throw field(field, tr, en);
            return null;
        }
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            long v = n.longValue();
            if (v >= min && v <= max) return (int) v;
        }
        if (strict) throw field(field, tr, en);
        return def;
    }

    private static Boolean bool(Object raw, boolean def, String field, boolean strict) {
        if (raw == null) return def;
        if (raw instanceof Boolean b) return b;
        if (strict) throw field(field, "Açık / kapalı değeri bekleniyor.", "An on / off value is expected.");
        return def;
    }

    static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    /** Tek satır: CR / LF / sekme boşluğa, NUL atılır, kenarlar kırpılır. */
    static String singleLine(Object o) {
        if (!(o instanceof String s)) return null;
        StringBuilder b = new StringBuilder(Math.min(s.length(), 4096));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) continue;
            b.append(c == '\r' || c == '\n' || c == '\t' ? ' ' : c);
        }
        return b.toString().trim();
    }

    /** Çok satır: CRLF / CR → LF, NUL atılır, sondaki boşluk kırpılır. */
    static String multiLine(Object o) {
        if (!(o instanceof String s)) return null;
        String v = s.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        return v.strip();
    }

    static FieldValidationException field(String field, String tr, String en) {
        return new FieldValidationException(field, Msg.t(tr, en));
    }

    // ── Yazma ────────────────────────────────────────────────────────────────────────────────────

    /** Kanonik JSON (sürüm satırı ve eşitlik karşılaştırması). */
    public static String toJson(Template t) {
        return JSON.writeValueAsString(toWire(t));
    }

    /** Tel biçimi ({@code {v, sections:[…]}}). */
    public static Map<String, Object> toWire(Template t) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        List<Object> secs = new ArrayList<>();
        for (Section s : t.sections()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id());
            sm.put("title", s.title());
            sm.put("window_from", s.windowFrom());
            sm.put("window_to", s.windowTo());
            List<Object> tasks = new ArrayList<>();
            for (Task k : s.tasks()) tasks.add(taskWire(k));
            sm.put("tasks", tasks);
            secs.add(sm);
        }
        root.put("sections", secs);
        return root;
    }

    public static Map<String, Object> taskWire(Task k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("title", k.title());
        m.put("description", k.description());
        m.put("links", linksWire(k.links()));
        m.put("signals", new ArrayList<>(k.signals()));
        m.put("required", k.required());
        m.put("active", k.active());
        m.put("frequency", k.frequency());
        m.put("weekday", k.weekday());
        m.put("monthly_rule", k.monthlyRule());
        m.put("month_day", k.monthDay());
        m.put("nth", k.nth());
        m.put("window_from", k.windowFrom());
        m.put("window_to", k.windowTo());
        return m;
    }

    public static List<Object> linksWire(List<Link> links) {
        List<Object> out = new ArrayList<>();
        for (Link l : links) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("label", l.label());
            lm.put("url", l.url());
            out.add(lm);
        }
        return out;
    }

    public static String linksJson(List<Link> links) {
        return JSON.writeValueAsString(linksWire(links));
    }

    /** Saklanan bağlantı anlık görüntüsü (görev satırı) — esnek; bozuksa boş. */
    public static List<Link> readLinks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object raw = JSON.readValue(json, Object.class);
            if (!(raw instanceof List<?> list)) return List.of();
            List<Link> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> lm)) continue;
                String url = str(lm.get("url"));
                if (url == null || urlProblemQuiet(url)) continue;
                String label = str(lm.get("label"));
                out.add(new Link(label == null ? "" : label, url));
                if (out.size() >= MAX_LINKS_PER_TASK) break;
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** {@link #urlProblem} kuralı — ileti üretmeden (istek bağlamı olmayan iş iş parçacığı için). */
    static boolean urlProblemQuiet(String raw) {
        if (raw == null || raw.isBlank()) return true;
        String url = raw.trim();
        if (url.length() > MAX_URL) return true;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null;
        } catch (Exception e) {
            return true;
        }
    }

    /** Ön yüz için sınırlar ({@code /template} yanıtı). */
    public static Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sections", MAX_SECTIONS);
        m.put("tasks", MAX_TASKS);
        m.put("active_tasks", MAX_ACTIVE_TASKS);
        m.put("links_per_task", MAX_LINKS_PER_TASK);
        m.put("title", MAX_TITLE);
        m.put("description", MAX_DESCRIPTION);
        m.put("signals_per_task", MAX_SIGNALS_PER_TASK);
        return m;
    }
}
