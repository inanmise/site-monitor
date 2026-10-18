package com.sitemonitor.service.grafana;

import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.metrics.MonitoringMetricNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grafana panosu üreticisi (2026-10-18): Grafana 12.x klasik pano sözleşmesi — içe aktarma girdileri, benzersiz panel
 * kimlikleri, çakışmayan ızgara, her panelin veri kaynağı / açıklaması / sorgusu, değişkenlerin tanımlı olması, bağlantıların
 * ayardaki adresi kullanması, bölüm / izleme başına anahtarları ve ayarların JSON'a yansıması.
 *
 * <p>Not: JSON burada CANLI bir Grafana'ya içe aktarılmadı; alan adları Grafana 12 klasik şemasından (panels, templating.list,
 * gridPos, fieldConfig, options, targets[].expr/refId/datasource) denetlenir. Örnek çıktı
 * {@code target/grafana-dashboard-sample.json}'a yazılır (elle inceleme / içe aktarma denemesi için).
 */
class GrafanaDashboardGeneratorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static GrafanaDashboardOptions opts(String lang, List<String> sections, boolean perMonitor, String base, String selector) {
        return new GrafanaDashboardOptions("", "sitemonitor-overview", base, selector, "now-24h", "1m", lang, sections, perMonitor,
                "", 10, "20.157.0");
    }

    static GrafanaDashboardOptions defaults() {
        return opts("tr", GrafanaDashboardGenerator.SECTIONS, true, "https://sitemonitor.example.org", "namespace=\"site-monitor-prod\"");
    }

    static JsonNode tree(GrafanaDashboardOptions o) {
        GrafanaDashboardGenerator.Result r = GrafanaDashboardGenerator.generate(o);
        return JSON.readTree(GrafanaDashboardGenerator.toJson(r.dashboard(), true));
    }

    /** Satırlar dahil bütün paneller (kapalı satırın çocukları da). */
    static List<JsonNode> allPanels(JsonNode d) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode p : d.get("panels")) {
            out.add(p);
            if (p.has("panels")) for (JsonNode c : p.get("panels")) out.add(c);
        }
        return out;
    }

    static List<JsonNode> contentPanels(JsonNode d) {
        return allPanels(d).stream().filter(p -> !"row".equals(p.get("type").asString())).toList();
    }

    @Test
    @DisplayName("içe aktarma sözleşmesi: __inputs DS_PROMETHEUS, __requires grafana ≥ 12 + prometheus + kullanılan paneller, id null, şema sürümü")
    void importContract() throws Exception {
        JsonNode d = tree(defaults());
        assertThat(d.get("__inputs").get(0).get("name").asString()).isEqualTo("DS_PROMETHEUS");
        assertThat(d.get("__inputs").get(0).get("pluginId").asString()).isEqualTo("prometheus");
        assertThat(d.get("__inputs").get(0).get("type").asString()).isEqualTo("datasource");
        Set<String> req = new HashSet<>();
        for (JsonNode r : d.get("__requires")) req.add(r.get("type").asString() + ":" + r.get("id").asString());
        assertThat(req).contains("grafana:grafana", "datasource:prometheus");
        for (JsonNode p : contentPanels(d)) assertThat(req).as("__requires panel " + p.get("type")).contains("panel:" + p.get("type").asString());
        for (JsonNode r : d.get("__requires")) if ("grafana".equals(r.get("type").asString())) assertThat(r.get("version").asString()).startsWith("12.");
        assertThat(d.get("id").isNull()).isTrue();
        assertThat(d.get("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);
        assertThat(d.get("uid").asString()).isEqualTo("sitemonitor-overview");
        assertThat(d.get("title").asString()).isEqualTo("SiteMonitor — Uçtan uca izleme");
        assertThat(d.get("time").get("from").asString()).isEqualTo("now-24h");
        assertThat(d.get("refresh").asString()).isEqualTo("1m");
        assertThat(d.get("version").asInt()).isEqualTo(GrafanaDashboardGenerator.DASHBOARD_REVISION);
        assertThat(d.get("description").asString()).contains("revizyonu " + GrafanaDashboardGenerator.DASHBOARD_REVISION).contains("20.157.0");
        // Örnek çıktı (elle inceleme / içe aktarma denemesi)
        Path out = Path.of("target", "grafana-dashboard-sample.json");
        Files.createDirectories(out.getParent());
        Files.write(out, GrafanaDashboardGenerator.toJson(GrafanaDashboardGenerator.generate(defaults()).dashboard(), true));
    }

    @Test
    @DisplayName("paneller: benzersiz kimlik, boş olmayan başlık + açıklama, her veri paneli ${datasource} ve en az bir sorgu (refId + expr)")
    void panelsAreComplete() {
        JsonNode d = tree(defaults());
        Set<Integer> ids = new HashSet<>();
        for (JsonNode p : allPanels(d)) {
            assertThat(ids.add(p.get("id").asInt())).as("panel kimliği tekrar ediyor: " + p.get("id")).isTrue();
            assertThat(p.get("title").asString()).as("boş başlık").isNotBlank();
        }
        List<JsonNode> content = contentPanels(d);
        assertThat(content.size()).as("panel sayısı").isGreaterThan(60);
        for (JsonNode p : content) {
            String title = p.get("title").asString();
            assertThat(p.path("description").asString()).as("açıklama: " + title).isNotBlank();
            assertThat(p.has("gridPos")).isTrue();
            if ("text".equals(p.get("type").asString())) {
                assertThat(p.get("options").get("content").asString()).as("metin paneli boş: " + title).isNotBlank();
                continue;
            }
            assertThat(p.get("datasource").get("uid").asString()).as("veri kaynağı: " + title).isEqualTo("${datasource}");
            assertThat(p.get("targets").size()).as("sorgu yok: " + title).isGreaterThan(0);
            Set<String> refs = new HashSet<>();
            for (JsonNode t : p.get("targets")) {
                assertThat(t.get("expr").asString()).as("boş ifade: " + title).isNotBlank();
                assertThat(refs.add(t.get("refId").asString())).as("refId tekrarı: " + title).isTrue();
                assertThat(t.get("datasource").get("uid").asString()).isEqualTo("${datasource}");
                assertBalanced(t.get("expr").asString(), title);
            }
        }
    }

    @Test
    @DisplayName("ızgara: paneller 24 sütuna sığar ve hiçbiri diğeriyle çakışmaz (kapalı satırların çocukları dahil)")
    void gridHasNoOverlap() {
        for (GrafanaDashboardOptions o : List.of(defaults(), opts("en", GrafanaDashboardGenerator.SECTIONS, false, "", ""),
                opts("tr", List.of("alerts", "runtime"), true, "", ""))) {
            JsonNode d = tree(o);
            List<int[]> boxes = new ArrayList<>();
            for (JsonNode p : allPanels(d)) {
                JsonNode g = p.get("gridPos");
                int x = g.get("x").asInt(), y = g.get("y").asInt(), w = g.get("w").asInt(), h = g.get("h").asInt();
                assertThat(x).isBetween(0, 23);
                assertThat(x + w).as("taşma: " + p.get("title")).isLessThanOrEqualTo(24);
                assertThat(w).isPositive();
                assertThat(h).isPositive();
                for (int[] b : boxes) {
                    boolean overlap = x < b[0] + b[2] && b[0] < x + w && y < b[1] + b[3] && b[1] < y + h;
                    assertThat(overlap).as("çakışma: " + p.get("title")).isFalse();
                }
                boxes.add(new int[]{x, y, w, h});
            }
        }
    }

    static final Pattern VAR_REF = Pattern.compile("\\$\\{?([a-zA-Z_][a-zA-Z0-9_]*)");
    static final Set<String> BUILTIN_VARS = Set.of("__range", "__interval", "__rate_interval", "__data", "__field", "__value",
            "__all", "__range_s", "__series", "DS_PROMETHEUS");

    @Test
    @DisplayName("değişkenler: datasource / team / type / monitor / level / topn / job / instance tanımlı; ifadelerdeki her $değişken tanımlı")
    void variablesExistAndAreUsed() {
        JsonNode d = tree(defaults());
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode v : d.get("templating").get("list")) names.add(v.get("name").asString());
        assertThat(names).containsExactly("datasource", "team", "type", "monitor", "level", "topn", "job", "instance");
        String all = new String(GrafanaDashboardGenerator.toJson(GrafanaDashboardGenerator.generate(defaults()).dashboard(), false),
                StandardCharsets.UTF_8);
        Matcher m = VAR_REF.matcher(all);
        Set<String> used = new HashSet<>();
        while (m.find()) {
            String v = m.group(1);
            if (v.matches("\\d+")) continue;   // label_replace "$1"
            used.add(v);
            assertThat(names.contains(v) || BUILTIN_VARS.contains(v)).as("tanımsız değişken: $" + v).isTrue();
        }
        assertThat(used).contains("team", "type", "monitor", "level", "topn", "job", "instance", "datasource");
        JsonNode team = byName(d, "team");
        assertThat(team.get("multi").asBoolean()).isTrue();
        assertThat(team.get("includeAll").asBoolean()).isTrue();
        assertThat(team.get("query").asString()).contains("sitemonitor_teams").contains("namespace=\"site-monitor-prod\"");
        JsonNode inst = byName(d, "instance");
        assertThat(inst.get("allValue").isNull()).as("pod süzgeci 'Tümü'nde de yalnız SiteMonitor pod'larını seçer").isTrue();
    }

    @Test
    @DisplayName("tür değişkeni MonitorTypeCatalog'un tüm türlerini kapsar; her türün SiteMonitor sekmesi var")
    void typeVariableCoversCatalog() {
        JsonNode type = byName(tree(defaults()), "type");
        Set<String> values = new HashSet<>();
        for (JsonNode o : type.get("options")) values.add(o.get("value").asString());
        for (String t : MonitorTypeCatalog.ORDER) {
            assertThat(values).as("tür değişkeninde yok: " + t).contains(t);
            assertThat(GrafanaDashboardGenerator.TYPE_TABS).containsKey(t);
        }
        assertThat(type.get("query").asString()).contains("HTTP / Web sitesi : http");
    }

    @Test
    @DisplayName("bağlantılar ayardaki adresi kullanır (izleme ?tab=<tür>&monitor=, alarm ?alert=, sertifika ?domain=…&open=cert); adres yoksa bağlantı yazılmaz")
    void linksUseBaseUrl() {
        String all = json(defaults());
        assertThat(all).contains("https://sitemonitor.example.org/?tab=${__data.fields.type}&monitor=${__data.fields.monitor_id}")
                .contains("https://sitemonitor.example.org/?tab=alerthistory&alert=${__data.fields.alert_id}")
                .contains("https://sitemonitor.example.org/?tab=dashboard&domain=${__data.fields.domain}&open=cert")
                .contains("https://sitemonitor.example.org/?tab=alerthistory&view=open&team=${__data.fields.team_id}");
        JsonNode d = tree(defaults());
        assertThat(d.get("links").size()).isGreaterThan(2);
        String none = json(opts("tr", GrafanaDashboardGenerator.SECTIONS, true, "", ""));
        assertThat(none).doesNotContain("?tab=");
        assertThat(tree(opts("tr", GrafanaDashboardGenerator.SECTIONS, true, "", "")).get("links").size()).isZero();
    }

    @Test
    @DisplayName("bölüm anahtarları: kapalı bölüm satırı ve panelleri yok; izleme başına kapalıyken izleme başına ölçüm ve monitor değişkeni yok")
    void sectionsAndPerMonitor() {
        JsonNode d = tree(opts("tr", List.of("alerts", "health"), true, "", ""));
        List<String> rows = new ArrayList<>();
        for (JsonNode p : d.get("panels")) if ("row".equals(p.get("type").asString())) rows.add(p.get("title").asString());
        assertThat(rows).containsExactly("Alarmlar", "SiteMonitor sağlığı");
        String off = json(opts("tr", GrafanaDashboardGenerator.SECTIONS, false, "", ""));
        for (MonitoringMetricNames.Def def : MonitoringMetricNames.ALL) {
            if (def.scope() == MonitoringMetricNames.Scope.ENTITY) assertThat(off).as("izleme başına ölçüm: " + def.prom()).doesNotContain(def.prom() + "{");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode v : tree(opts("tr", GrafanaDashboardGenerator.SECTIONS, false, "", "")).get("templating").get("list")) names.add(v.get("name").asString());
        assertThat(names).doesNotContain("monitor");
        GrafanaDashboardGenerator.Summary s = GrafanaDashboardGenerator.generate(defaults()).summary();
        assertThat(s.panelsBySection().keySet()).containsExactlyElementsOf(GrafanaDashboardGenerator.SECTIONS);
        assertThat(s.panels()).isEqualTo(contentPanels(tree(defaults())).size());
        assertThat(s.rows()).isEqualTo(GrafanaDashboardGenerator.SECTIONS.size());
        assertThat(s.fileName()).isEqualTo("sitemonitor-grafana-dashboard-20.157.0.json");
    }

    @Test
    @DisplayName("ayarlar JSON'a yansır: başlık, uid, zaman, yenileme, dil, varsayılan takım, İlk N, etiket eşleyicisi her seçicide")
    void settingsAreApplied() {
        GrafanaDashboardOptions o = new GrafanaDashboardOptions("Dijital Kanallar — İzleme", "dk-izleme", "https://sm.example.org",
                "job=\"site-monitor\"", "now-7d", "5m", "en", GrafanaDashboardGenerator.SECTIONS, true, "Dijital Kanallar", 20, "1.2.3");
        JsonNode d = tree(o);
        assertThat(d.get("title").asString()).isEqualTo("Dijital Kanallar — İzleme");
        assertThat(d.get("uid").asString()).isEqualTo("dk-izleme");
        assertThat(d.get("time").get("from").asString()).isEqualTo("now-7d");
        assertThat(d.get("refresh").asString()).isEqualTo("5m");
        assertThat(byName(d, "team").get("current").get("value").asString()).isEqualTo("Dijital Kanallar");
        assertThat(byName(d, "topn").get("current").get("value").asString()).isEqualTo("20");
        assertThat(d.get("panels").get(0).get("title").asString()).isEqualTo("Overview");
        for (JsonNode p : contentPanels(d)) {
            if (!p.has("targets")) continue;
            for (JsonNode t : p.get("targets")) {
                String expr = t.get("expr").asString();
                // Her seçici (ad{...}) eşleyiciyi taşır — vector(0) gibi seçicisizler hariç
                Matcher m = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*\\{([^}]*)\\}").matcher(expr);
                while (m.find()) assertThat(m.group(1)).as("eşleyici eksik: " + p.get("title") + " → " + expr).contains("job=\"site-monitor\"");
            }
        }
    }

    @Test
    @DisplayName("çok pod: kuruluş ölçümleri max by (etiketler) ile tekilleştirilir; takım süzgeci UG sahibini de arar")
    void multiPodAggregationAndUgOwner() {
        String all = json(defaults());
        assertThat(all).contains("max by (type, team, team_id, state) (sitemonitor_monitors{");
        assertThat(all).contains("ug_team=~\\\"$team\\\"");
        assertThat(all).contains("or max by (family, alert_type, team, team_id, ug_team, ug_team_id, level, acknowledged)");
        // "Veri yok" kuralı: boş toplamı 0 yapan kısım kaynak okunabiliyorsa geçerli
        assertThat(all).contains("vector(0) and on() (max(sitemonitor_exporter_source_up{");
    }

    @Test
    @DisplayName("İngilizce pano: satır başlıkları ve değer eşlemeleri İngilizce")
    void englishTexts() {
        String en = json(opts("en", GrafanaDashboardGenerator.SECTIONS, true, "", ""));
        assertThat(en).contains("\"Overview\"").contains("\"Alerts\"").contains("\"Monitor state\"").doesNotContain("\"Genel durum\"");
        assertThat(en).contains("SiteMonitor — End-to-end monitoring");
    }

    @Test
    @DisplayName("anotasyonlar: sürüm dağıtımı + (izleme başına açıkken) kritik / yüksek alarm açılışı")
    void annotations() {
        JsonNode d = tree(defaults());
        List<String> names = new ArrayList<>();
        for (JsonNode a : d.get("annotations").get("list")) names.add(a.get("name").asString());
        assertThat(names).contains("Sürüm dağıtımı", "Kritik / yüksek alarm açıldı");
        JsonNode off = tree(opts("tr", GrafanaDashboardGenerator.SECTIONS, false, "", ""));
        List<String> offNames = new ArrayList<>();
        for (JsonNode a : off.get("annotations").get("list")) offNames.add(a.get("name").asString());
        assertThat(offNames).doesNotContain("Kritik / yüksek alarm açıldı");
    }

    @Test
    @DisplayName("dosya adı: sürüm dosya adına güvenli yazılır")
    void fileName() {
        assertThat(GrafanaDashboardGenerator.fileName("20.157.0")).isEqualTo("sitemonitor-grafana-dashboard-20.157.0.json");
        assertThat(GrafanaDashboardGenerator.fileName("")).isEqualTo("sitemonitor-grafana-dashboard-local.json");
        assertThat(GrafanaDashboardGenerator.fileName("1.0/../x y")).isEqualTo("sitemonitor-grafana-dashboard-1.0-..-x-y.json");
    }

    static String json(GrafanaDashboardOptions o) {
        return new String(GrafanaDashboardGenerator.toJson(GrafanaDashboardGenerator.generate(o).dashboard(), false), StandardCharsets.UTF_8);
    }

    static JsonNode byName(JsonNode d, String name) {
        for (JsonNode v : d.get("templating").get("list")) if (name.equals(v.get("name").asString())) return v;
        throw new AssertionError("değişken yok: " + name);
    }

    /** Parantez / süslü ayraç / tırnak dengesi (bozuk PromQL'in en sık biçimi). */
    static void assertBalanced(String expr, String title) {
        int paren = 0, brace = 0;
        boolean inQuote = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\\' && inQuote) { i++; continue; }
            if (c == '"') { inQuote = !inQuote; continue; }
            if (inQuote) continue;
            if (c == '(') paren++;
            if (c == ')') paren--;
            if (c == '{') brace++;
            if (c == '}') brace--;
            assertThat(paren).as("parantez: " + title + " → " + expr).isGreaterThanOrEqualTo(0);
            assertThat(brace).as("ayraç: " + title + " → " + expr).isGreaterThanOrEqualTo(0);
        }
        assertThat(paren).as("parantez dengesi: " + title + " → " + expr).isZero();
        assertThat(brace).as("ayraç dengesi: " + title + " → " + expr).isZero();
        assertThat(inQuote).as("tırnak: " + title + " → " + expr).isFalse();
    }

    @Test
    @DisplayName("tüm ifadeler yalnız yayılan ya da standart ölçümleri kullanır")
    void expressionsUseKnownMetrics() {
        Set<String> known = new HashSet<>(GrafanaDashboardGenerator.STANDARD_METRICS);
        for (MonitoringMetricNames.Def def : MonitoringMetricNames.ALL) known.add(def.prom());
        String timer = MonitoringMetricNames.promTimer(MonitoringMetricNames.HTTP_DURATION);
        known.addAll(List.of(timer + "_bucket", timer + "_count", timer + "_sum", timer + "_max"));
        GrafanaDashboardGenerator.Summary s = GrafanaDashboardGenerator.generate(defaults()).summary();
        for (String m : s.metrics()) assertThat(known).as("bilinmeyen ölçüm: " + m).contains(m);
        Map<String, Object> dash = GrafanaDashboardGenerator.generate(defaults()).dashboard();
        assertThat(dash).containsKey("panels");
    }
}
