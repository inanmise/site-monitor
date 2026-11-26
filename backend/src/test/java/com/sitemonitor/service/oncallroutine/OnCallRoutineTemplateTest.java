package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nöbet rutini şablonu (2026-11-26): KATI kayıt (alan adlı hata), ESNEK okuma (bozuk parça atlanır, asla fırlatmaz), sınırlar,
 * kimlik deseni, bağlantı kuralı (şema, kullanıcı bilgisi, uzunluk URI'den ÖNCE), sıklık parametreleri, pencereler, bilinmeyen
 * anahtarlar ve kanonik eşitlik.
 */
class OnCallRoutineTemplateTest {

    static Map<String, Object> task(String id, String title) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("title", title);
        return t;
    }

    static Map<String, Object> section(String id, String title, List<Map<String, Object>> tasks) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("title", title);
        s.put("tasks", tasks);
        return s;
    }

    static Map<String, Object> tpl(List<Map<String, Object>> sections) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        root.put("sections", sections);
        return root;
    }

    static Map<String, Object> one(Map<String, Object> task) {
        return tpl(List.of(section("s1", "Sabah kontrolleri", List.of(task))));
    }

    static String fieldOf(Throwable e) {
        return ((FieldValidationException) e).getFields().keySet().iterator().next();
    }

    @Test
    @DisplayName("geçerli şablon: varsayılanlar (zorunlu, etkin, DAILY), haftalık gün varsayılanı 5, bilinmeyen anahtar düşer")
    void strictDefaultsAndUnknownKeys() {
        Map<String, Object> t1 = task("t1", "  Kuyrukları kontrol et  ");
        t1.put("foo", "bar");
        Map<String, Object> t2 = task("t2", "Haftalık yedek denemesi");
        t2.put("frequency", "WEEKLY");
        Map<String, Object> root = tpl(List.of(section("s1", "Sabah", List.of(t1, t2))));
        root.put("extra", 1);
        Template t = OnCallRoutineTemplate.parseStrict(root);
        assertThat(t.sections()).hasSize(1);
        var a = t.sections().get(0).tasks().get(0);
        assertThat(a.title()).isEqualTo("Kuyrukları kontrol et");
        assertThat(a.required()).isTrue();
        assertThat(a.active()).isTrue();
        assertThat(a.frequency()).isEqualTo("DAILY");
        assertThat(t.sections().get(0).tasks().get(1).weekday()).isEqualTo(5);
        String json = OnCallRoutineTemplate.toJson(t);
        assertThat(json).doesNotContain("foo").doesNotContain("extra");
        // kanonik eşitlik: aynı belge yeniden ayrıştırılınca aynı JSON
        Template again = OnCallRoutineTemplate.parseStrict(OnCallRoutineTemplate.toWire(t));
        assertThat(OnCallRoutineTemplate.toJson(again)).isEqualTo(json);
    }

    @Test
    @DisplayName("bölüm listesi yok / boş / bozuk → alan 'template'")
    void sectionsRequired() {
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(null)).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(Map.of("v", 1)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of())))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));
    }

    @Test
    @DisplayName("kimlik deseni ve benzersizlik (bölüm + görev kimlikleri aynı ad alanında)")
    void idPatternAndUniqueness() {
        assertThat(OnCallRoutineTemplate.ID.matcher("t-1_a").matches()).isTrue();
        assertThat(OnCallRoutineTemplate.ID.matcher("T1").matches()).isFalse();
        assertThat(OnCallRoutineTemplate.ID.matcher("-x").matches()).isFalse();
        assertThat(OnCallRoutineTemplate.ID.matcher("a".repeat(41)).matches()).isFalse();
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of(section("Bad", "x", List.of())))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));
        // aynı görev kimliği iki bölümde
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of(
                section("s1", "A", List.of(task("t1", "x"))), section("s2", "B", List.of(task("t1", "y")))))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s2.tasks"));
        // görev kimliği bir bölüm kimliğiyle çakışıyor
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of(section("s1", "A", List.of(task("s1", "x")))))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.tasks"));
    }

    @Test
    @DisplayName("başlık / açıklama uzunlukları alan adıyla")
    void lengths() {
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of(section("s1", "x".repeat(121), List.of())))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.title"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(task("t1", "   "))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.title"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(task("t1", "x".repeat(201)))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.title"));
        Map<String, Object> t = task("t1", "x");
        t.put("description", "d".repeat(2001));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.description"));
        // tek satır: CR / LF / sekme boşluğa
        Template ok = OnCallRoutineTemplate.parseStrict(one(task("t1", "a\r\nb\tc")));
        assertThat(ok.sections().get(0).tasks().get(0).title()).isEqualTo("a  b c");
    }

    @Test
    @DisplayName("bağlantı kuralı: yalnız http/https, kullanıcı bilgisi yok, uzunluk URI ayrıştırmasından önce")
    void links() {
        assertThat(OnCallRoutineTemplate.urlProblem("https://wiki.example.com/x?y=1")).isNull();
        assertThat(OnCallRoutineTemplate.urlProblem("http://grafana.example.com:3000/d/abc")).isNull();
        assertThat(OnCallRoutineTemplate.urlProblem("javascript:alert(1)")).isNotNull();
        assertThat(OnCallRoutineTemplate.urlProblem("ftp://files.example.com")).isNotNull();
        assertThat(OnCallRoutineTemplate.urlProblem("/relative/path")).isNotNull();
        assertThat(OnCallRoutineTemplate.urlProblem("https://user:pass@example.com/")).isNotNull();
        assertThat(OnCallRoutineTemplate.urlProblem("https://user@example.com/")).isNotNull();
        assertThat(OnCallRoutineTemplate.urlProblem("")).isNotNull();
        // uzunluk: 2000 sınırı, ayrıştırılamayan (boşluklu) dev adres bile önce uzunluktan reddedilir
        String longBad = "https://example.com/" + "a b".repeat(1000);
        assertThat(OnCallRoutineTemplate.urlProblem(longBad)).contains("2000");
        Map<String, Object> t = task("t1", "x");
        t.put("links", List.of(Map.of("label", "Pano", "url", "https://ok.example.com"),
                Map.of("label", "Gizli", "url", "https://a:b@example.com")));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.links.1.url"));
        // esnek okumada kötü bağlantı atlanır
        Template lenient = OnCallRoutineTemplate.readStored(OnCallRoutineTemplate.JSON.writeValueAsString(one(t))).template();
        assertThat(lenient.sections().get(0).tasks().get(0).links()).hasSize(1);
        // saklanan bağlantı anlık görüntüsü de aynı kuralla süzülür
        assertThat(OnCallRoutineTemplate.readLinks("[{\"label\":\"x\",\"url\":\"https://u:p@h.example.com\"},"
                + "{\"label\":\"y\",\"url\":\"https://h.example.com\"}]")).hasSize(1);
        assertThat(OnCallRoutineTemplate.readLinks("{bozuk")).isEmpty();
    }

    @Test
    @DisplayName("sıklık parametreleri: WEEKLY gün aralığı, MONTHLY kuralı + gün / n. hafta")
    void frequencies() {
        Map<String, Object> w = task("t1", "x");
        w.put("frequency", "WEEKLY");
        w.put("weekday", 8);
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(w)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.weekday"));
        Map<String, Object> m = task("t1", "x");
        m.put("frequency", "MONTHLY");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(m)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.monthly_rule"));
        m.put("monthly_rule", "DAY");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(m)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.month_day"));
        m.put("month_day", 31);
        assertThat(OnCallRoutineTemplate.parseStrict(one(m)).sections().get(0).tasks().get(0).monthDay()).isEqualTo(31);
        Map<String, Object> n = task("t1", "x");
        n.put("frequency", "MONTHLY");
        n.put("monthly_rule", "NTH_WEEKDAY");
        n.put("nth", 6);
        n.put("weekday", 1);
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(n)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.nth"));
        n.put("nth", 5);
        var k = OnCallRoutineTemplate.parseStrict(one(n)).sections().get(0).tasks().get(0);
        assertThat(k.nth()).isEqualTo(5);
        assertThat(k.weekday()).isEqualTo(1);
        Map<String, Object> bad = task("t1", "x");
        bad.put("frequency", "HOURLY");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(bad)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.frequency"));
        Map<String, Object> flag = task("t1", "x");
        flag.put("required", "yes");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(flag)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.required"));
    }

    @Test
    @DisplayName("pencereler: SS:dd, bitiş başlangıçtan sonra")
    void windows() {
        Map<String, Object> t = task("t1", "x");
        t.put("window_from", "10:00");
        t.put("window_to", "09:00");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.window_to"));
        t.put("window_to", "25:00");
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.window_to"));
        t.put("window_to", "10:30");
        var k = OnCallRoutineTemplate.parseStrict(one(t)).sections().get(0).tasks().get(0);
        assertThat(k.windowFrom()).isEqualTo("10:00");
        assertThat(k.windowTo()).isEqualTo("10:30");
    }

    @Test
    @DisplayName("canlı sayaçlar: yalnız bilinen anahtarlar, tekrarsız, en çok 4")
    void signals() {
        Map<String, Object> t = task("t1", "x");
        t.put("signals", List.of("open_alerts", "nope"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.signals"));
        t.put("signals", List.of("open_alerts", "open_alerts"));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t))).isInstanceOf(FieldValidationException.class);
        t.put("signals", OnCallRoutineSignals.KEYS.subList(0, 5));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t))).isInstanceOf(FieldValidationException.class);
        t.put("signals", OnCallRoutineSignals.KEYS.subList(0, 4));
        assertThat(OnCallRoutineTemplate.parseStrict(one(t)).sections().get(0).tasks().get(0).signals()).hasSize(4);
    }

    @Test
    @DisplayName("sınırlar: 31 bölüm, bölümde 101 görev, 201 etkin görev, görevde 21 bağlantı")
    void limits() {
        List<Map<String, Object>> secs = new ArrayList<>();
        for (int i = 0; i < 31; i++) secs.add(section("s" + i, "S" + i, List.of()));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(secs))).isInstanceOf(FieldValidationException.class);

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(task("t" + i, "T" + i));
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(List.of(section("s1", "A", many)))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.tasks"));

        List<Map<String, Object>> three = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            List<Map<String, Object>> ts = new ArrayList<>();
            for (int i = 0; i < 67; i++) ts.add(task("t" + s + "x" + i, "T"));
            three.add(section("s" + s, "S", ts));
        }
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(tpl(three))).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));

        Map<String, Object> t = task("t1", "x");
        List<Map<String, Object>> links = new ArrayList<>();
        for (int i = 0; i < 21; i++) links.add(Map.of("label", "L", "url", "https://h" + i + ".example.com"));
        t.put("links", links);
        assertThatThrownBy(() -> OnCallRoutineTemplate.parseStrict(one(t)))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template.s1.t1.links"));
        assertThat(OnCallRoutineTemplate.limits()).containsEntry("sections", 30).containsEntry("tasks", 300)
                .containsEntry("active_tasks", 200);
    }

    @Test
    @DisplayName("512 KB sınırı: bildirilen uzunluk ya da kanonik boy aşarsa alan 'template'")
    void sizeCap() {
        OnCallRoutineTemplate.checkSize(-1);
        OnCallRoutineTemplate.checkSize(OnCallRoutineTemplate.MAX_JSON_BYTES);
        assertThatThrownBy(() -> OnCallRoutineTemplate.checkSize(OnCallRoutineTemplate.MAX_JSON_BYTES + 1L))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("template"));
    }

    @Test
    @DisplayName("esnek okuma: boş → boş şablon, bozuk JSON → unreadable, bozuk görev atlanır, asla fırlatmaz")
    void lenientRead() {
        assertThat(OnCallRoutineTemplate.readStored(null).template().sections()).isEmpty();
        assertThat(OnCallRoutineTemplate.readStored(null).unreadable()).isFalse();
        assertThat(OnCallRoutineTemplate.readStored("{bozuk").unreadable()).isTrue();
        String json = "{\"sections\":[{\"id\":\"s1\",\"title\":\"A\",\"tasks\":[{\"id\":\"BAD ID\",\"title\":\"x\"},"
                + "{\"id\":\"t2\",\"title\":\"ok\",\"frequency\":\"YEARLY\"},{\"id\":\"t3\",\"title\":\"ok3\"}]}]}";
        var r = OnCallRoutineTemplate.readStored(json);
        assertThat(r.unreadable()).isFalse();
        assertThat(r.template().sections().get(0).tasks()).extracting(OnCallRoutineTemplate.Task::id).containsExactly("t3");
        assertThat(r.template().find("t3")).isNotNull();
        assertThat(r.template().find("missing")).isNull();
    }
}
