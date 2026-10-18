package com.sitemonitor.service.grafana;

import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.metrics.MonitoringMetricNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI — Grafana panosu ölçümlerle ve izleme türleriyle ayrışamaz (2026-10-18, kullanıcı: "her yeni sürümde panoya eklenmesi
 * gereken bir şey varsa JSON'u doğru güncelle, asla unutma").
 *
 * <ol>
 *   <li>Arka uçta kaydedilen HER {@code sitemonitor.*} Micrometer ölçümü (kaynak taraması: Micrometer kullanan dosyalardaki
 *       {@code "sitemonitor.…"} dize literalleri) üretilen panoda geçer — ya da aşağıdaki {@link #NOT_ON_DASHBOARD} listesinde
 *       GEREKÇESİYLE durur. Yeni ölçüm ekleyen geliştirici ya panoya panel ekler ya gerekçe yazar.</li>
 *   <li>{@link MonitorTypeCatalog#ORDER}'daki her izleme türü panonun tür değişkeninde ve SiteMonitor bağlantı haritasında
 *       ({@link GrafanaDashboardGenerator#TYPE_TABS}) var; her bağlantı sekmesi ön yüzün {@code VALID_TABS} kümesinde.</li>
 *   <li>İzin listesi bayatlamaz: listedeki ad artık kayıtlı değilse ya da panoda kullanılıyorsa kırmızı.</li>
 * </ol>
 */
class GrafanaMetricCoverageGateTest {

    /** Panoda BİLEREK olmayan ölçümler — her biri gerekçesiyle. Liste yalnız küçülür. */
    static final Map<String, String> NOT_ON_DASHBOARD = new LinkedHashMap<>();
    static {
        NOT_ON_DASHBOARD.put("sitemonitor.schema.patch.applied",
                "Açılış özeti (değişiklik yapan yama sayısı) — operasyonel anlamı yok; panoda yalnız başarısız sayısı izlenir, "
                        + "ayrıntı Sistem Sağlığı'nda.");
        NOT_ON_DASHBOARD.put("sitemonitor.schema.patch.noop",
                "Açılış özeti (zaten uygulanmış yamalar) — her açılışta yüzlerce; izlenecek bir durum değil.");
        NOT_ON_DASHBOARD.put("sitemonitor.schema.patch.locked",
                "Yamaların danışma kilidi altında koşup koşmadığı (1/0) — açılış ayrıntısı, alarm konusu değil.");
    }

    static final Path MAIN = Path.of("src", "main", "java", "com", "sitemonitor");
    static final Pattern LITERAL = Pattern.compile("\"(sitemonitor\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+)\"");

    /** Micrometer kullanan kaynaklardaki {@code "sitemonitor.x.y"} literalleri → dosya. */
    static Map<String, String> registeredMeters() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                boolean micrometer = src.contains("io.micrometer") || f.getFileName().toString().equals("MonitoringMetricNames.java");
                if (!micrometer) continue;
                Matcher m = LITERAL.matcher(src);
                while (m.find()) out.putIfAbsent(m.group(1), f.getFileName().toString());
            }
        }
        return out;
    }

    static GrafanaDashboardOptions allOptions() {
        return new GrafanaDashboardOptions("", "gate", "https://sm.example.org", "", "now-24h", "1m", "tr",
                GrafanaDashboardGenerator.SECTIONS, true, "", 10, "0.0.0-gate");
    }

    static String dashboardJson() {
        return new String(GrafanaDashboardGenerator.toJson(GrafanaDashboardGenerator.generate(allOptions()).dashboard(), false),
                StandardCharsets.UTF_8);
    }

    /**
     * Panonun kullandığı TAM ölçüm adları (2026-10-18, bağımsız inceleme): sorgular PromQL sözcük çözümlemesiyle okunur
     * ({@link GrafanaDashboardGenerator#metricsIn}) — düz metin araması ({@code json.contains}) kısa bir adı uzun bir adın
     * içinde ({@code sitemonitor_monitor} ⊂ {@code sitemonitor_monitor_state}) ya da bir açıklamada "buluyordu".
     */
    static Set<String> dashboardMetrics() {
        return new TreeSet<>(GrafanaDashboardGenerator.generate(allOptions()).summary().metrics());
    }

    /** Kayıtlı bir ölçümün panoda karşılanma biçimleri: gauge adı, Timer satırları, sayaç. */
    static boolean onDashboard(String micrometer, Set<String> used) {
        String prom = MonitoringMetricNames.prom(micrometer);
        String timer = MonitoringMetricNames.promTimer(micrometer);
        return used.contains(prom) || used.contains(prom + "_total") || used.contains(timer + "_bucket")
                || used.contains(timer + "_count") || used.contains(timer + "_sum");
    }

    @Test
    @DisplayName("kaydedilen her sitemonitor_* ölçümü panoda ya da gerekçeli izin listesinde")
    void everyRegisteredMeterIsOnTheDashboard() throws IOException {
        Map<String, String> meters = registeredMeters();
        assertThat(meters).as("tarama boş — yol ya da kalıp değişmiş").hasSizeGreaterThan(40);
        assertThat(meters).containsKeys("sitemonitor.build.info", "sitemonitor.monitor.state", "sitemonitor.http.server.duration");
        Set<String> used = dashboardMetrics();
        Map<String, String> missing = new TreeMap<>();
        for (Map.Entry<String, String> e : meters.entrySet()) {
            if (onDashboard(e.getKey(), used)) continue;
            if (NOT_ON_DASHBOARD.containsKey(e.getKey())) continue;
            missing.put(MonitoringMetricNames.prom(e.getKey()), e.getValue());
        }
        assertThat(missing)
                .as("Panoda kullanılmayan ölçümler (ölçüm → kaydeden dosya). GrafanaDashboardGenerator'a panel ekleyin ve "
                        + "DASHBOARD_REVISION'ı artırın — ya da GrafanaMetricCoverageGateTest.NOT_ON_DASHBOARD'a gerekçesiyle yazın.")
                .isEmpty();
    }

    @Test
    @DisplayName("izin listesi bayat değil: her ad hâlâ kayıtlı ve panoda kullanılmıyor")
    void allowListIsNotStale() throws IOException {
        Map<String, String> meters = registeredMeters();
        Set<String> used = dashboardMetrics();
        for (Map.Entry<String, String> e : NOT_ON_DASHBOARD.entrySet()) {
            assertThat(meters).as("izin listesinde ama artık kayıtlı değil: " + e.getKey()).containsKey(e.getKey());
            assertThat(onDashboard(e.getKey(), used)).as("izin listesinde ama panoda kullanılıyor: " + e.getKey()).isFalse();
            assertThat(e.getValue()).as("gerekçe boş: " + e.getKey()).hasSizeGreaterThan(20);
        }
    }

    @Test
    @DisplayName("katalogdaki her ölçüm panoda (izleme başına açıkken)")
    void catalogIsCovered() {
        Set<String> used = dashboardMetrics();
        Set<String> missing = new TreeSet<>();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) if (!used.contains(d.prom())) missing.add(d.prom());
        assertThat(missing).isEmpty();
        // Kapı kendini sınar: kısa ad, uzun adın parçası olarak "kullanılmış" sayılmaz
        assertThat(used).contains("sitemonitor_monitor_state").doesNotContain("sitemonitor_monitor");
        assertThat(onDashboard("sitemonitor.monitor", used)).isFalse();
    }

    @Test
    @DisplayName("her izleme türü tür değişkeninde ve bağlantı haritasında; bağlantı sekmeleri ön yüzde tanımlı")
    void monitorTypesAreCovered() throws IOException {
        String json = dashboardJson();
        String routes = Files.readString(Path.of("..", "frontend", "src", "utils", "appRoutes.js"), StandardCharsets.UTF_8);
        Matcher valid = Pattern.compile("VALID_TABS\\s*=\\s*new Set\\(\\[([\\s\\S]*?)\\]\\)").matcher(routes);
        assertThat(valid.find()).as("appRoutes.js VALID_TABS okunamadı").isTrue();
        Set<String> tabs = new TreeSet<>();
        Matcher q = Pattern.compile("'([^']+)'").matcher(valid.group(1));
        while (q.find()) tabs.add(q.group(1));
        for (String type : MonitorTypeCatalog.ORDER) {
            assertThat(GrafanaDashboardGenerator.TYPE_LABELS).as("tür değişkeninde yok: " + type).containsKey(type);
            assertThat(GrafanaDashboardGenerator.TYPE_TABS).as("bağlantı haritasında yok: " + type).containsKey(type);
            assertThat(tabs).as("ön yüz sekmesi yok: " + GrafanaDashboardGenerator.TYPE_TABS.get(type)).contains(GrafanaDashboardGenerator.TYPE_TABS.get(type));
            assertThat(json).as("tür değişkeni seçeneği yok: " + type).contains(" : " + type);
        }
        assertThat(GrafanaDashboardGenerator.TYPE_LABELS.keySet()).as("katalogda olmayan tür").containsExactlyInAnyOrderElementsOf(MonitorTypeCatalog.ORDER);
        for (String tab : Set.of("monitoring", "alerthistory", "all", "health", "storms", "dashboard", "warnings", "domain")) {
            assertThat(tabs).as("panonun kullandığı sekme ön yüzde yok: " + tab).contains(tab);
        }
    }
}
