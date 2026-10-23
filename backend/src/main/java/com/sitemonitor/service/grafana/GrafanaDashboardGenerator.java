package com.sitemonitor.service.grafana;

import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.metrics.MonitoringMetricNames;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grafana panosu üreticisi (2026-10-18, kullanıcı isteği: "Grafana'da SiteMonitor'ü ayrı bir panoyla uçtan uca izleyeyim;
 * Ayarlar'dan içe aktarılabilir JSON indireyim"). SAF: ayar girdisi ({@link GrafanaDashboardOptions}) → Grafana 12.x klasik
 * pano JSON'u (iç içe {@code Map}/{@code List}); Bahar, veritabanı yok. Ölçüm adları {@link MonitoringMetricNames}'ten.
 *
 * <p><b>İçe aktarma sözleşmesi.</b> {@code __inputs} → {@code DS_PROMETHEUS} (Grafana içe aktarırken veri kaynağını sorar),
 * {@code __requires} → grafana ≥ {@value #GRAFANA_MIN_VERSION} + prometheus + kullanılan panel türleri; panolar ve
 * değişkenler {@code ${datasource}} değişkenini kullanır (varsayılanı içe aktarılan kaynak). {@code id: null} — Grafana yeni
 * kimlik verir; {@code uid} ayardan (aynı uid ile yeniden içe aktarmak üzerine yazar).
 *
 * <p><b>Çok pod.</b> Her pod kuruluş ölçümlerini aynı değerle yayar: her sorgu önce {@code max by (ölçümün kendi etiketleri)}
 * ile pod'ları tekilleştirir (kazıma yapılandırmasının eklediği {@code instance} / {@code pod} / {@code namespace} gibi
 * etiketler düşer), sonra toplar. Pod'a özgü ölçümler ({@code sitemonitor_sweep_leader}, JVM, HTTP) pod başına okunur.
 *
 * <p><b>Takım süzgeci.</b> Envanter gibi yönlenen alarm ve sertifikanın iki sahibi olabilir (SY + UG): sorgular
 * {@code X{team=~"$team"} or X{ug_team=~"$team"}} ile iki takımı da arar; {@code or} aynı seriyi bir kez tutar.
 *
 * <p><b>Güncel tutma (KALICI KURAL, kullanıcı: "her yeni sürümde panoda değişiklik gerekiyorsa JSON'u doğru güncelle").</b>
 * Ölçüm, izleme türü ya da panoyu etkileyen özellik eklenen / değişen her sürüm bu sınıfı günceller ve
 * {@link #DASHBOARD_REVISION}'ı artırır. Kapılar: {@code GrafanaMetricCoverageGateTest} (kayıtlı her {@code sitemonitor_*}
 * ölçümü panoda ya da gerekçeli izin listesinde; her izleme türü değişkende ve bağlantılarda) ve
 * {@code GrafanaDashboardRevisionGateTest} (içerik özeti değişip revizyon artmadıysa kırmızı).
 */
public final class GrafanaDashboardGenerator {

    private GrafanaDashboardGenerator() {}

    /**
     * Pano içerik revizyonu — içerik (panel, sorgu, değişken) değişen HER sürümde artır ve
     * {@code src/test/resources/grafana/dashboard-content.sha256} dosyasını güncelle (kapı mesajı yeni satırı yazar).
     */
    public static final int DASHBOARD_REVISION = 3;
    /** "Kritik / yüksek alarm açıldı" anotasyonunun penceresi (sn) — açılış zamanı bu kadar yeni olan alarmlar. */
    static final int ALERT_OPENED_WINDOW_S = 900;
    /** Aktarıcı tazelik eşiği (sn) — sunucunun {@code max(180, 3 × refresh-seconds)} kuralının varsayılan değeri. */
    static final int EXPORTER_STALE_S = 180;

    /** Grafana 12.x klasik pano şema sürümü (12.0+ içe aktarır; daha yeniyi kendisi yükseltir). */
    public static final int SCHEMA_VERSION = 41;
    public static final String GRAFANA_MIN_VERSION = "12.0.0";
    public static final String DS_INPUT = "DS_PROMETHEUS";
    public static final String DS_VAR = "${datasource}";

    /** Pano bölümleri — sıra panodaki satır sırasıdır. */
    public static final List<String> SECTIONS =
            List.of("overview", "alerts", "monitors", "certificates", "notifications", "health", "runtime");

    /** İzleme türü → SiteMonitor sekmesi (izleme detayı {@code ?tab=<sekme>&monitor=<id>}). Sertifika Pano'da açılır. */
    public static final Map<String, String> TYPE_TABS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : MonitorTypeCatalog.ORDER) m.put(t, "cert".equals(t) ? "dashboard" : t);
        TYPE_TABS = Collections.unmodifiableMap(m);
    }

    /** Tür değişkeninin seçenekleri (TR, EN etiket) — {@link MonitorTypeCatalog#ORDER} ile aynı küme (kapı denetler). */
    static final Map<String, String[]> TYPE_LABELS = new LinkedHashMap<>();
    static {
        TYPE_LABELS.put("cert", new String[]{"Sertifika", "Certificate"});
        TYPE_LABELS.put("domain", new String[]{"Alan adı kaydı", "Domain registration"});
        TYPE_LABELS.put("http", new String[]{"HTTP / Web sitesi", "HTTP / Website"});
        TYPE_LABELS.put("ping", new String[]{"Ping", "Ping"});
        TYPE_LABELS.put("port", new String[]{"Port", "Port"});
        TYPE_LABELS.put("dns", new String[]{"DNS", "DNS"});
        TYPE_LABELS.put("keyword", new String[]{"İçerik (keyword)", "Keyword"});
        TYPE_LABELS.put("page", new String[]{"Sayfa bütünlüğü", "Page integrity"});
        TYPE_LABELS.put("pagespeed", new String[]{"Sayfa hızı", "Page speed"});
        TYPE_LABELS.put("scripted", new String[]{"Sentetik (k6)", "Synthetic (k6)"});
    }

    /**
     * Panoda kullanılan, SiteMonitor'ün kendi kataloğunda OLMAYAN standart ölçümler (Spring Boot / Micrometer / Prometheus).
     * Kapı her sorgudaki ölçüm adını {@link MonitoringMetricNames} ∪ bu küme ∪ ad kalıplarıyla karşılaştırır.
     */
    public static final Set<String> STANDARD_METRICS = Set.of(
            "up", "sitemonitor_build_info", "sitemonitor_deployment_started_seconds",
            "sitemonitor_schema_patch_failed", "sitemonitor_schema_patch_applied", "sitemonitor_schema_patch_locked",
            "http_server_requests_seconds_count", "http_server_requests_seconds_sum", "http_server_requests_seconds_max",
            "jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_memory_committed_bytes", "jvm_gc_pause_seconds_sum",
            "jvm_gc_pause_seconds_count", "jvm_threads_live_threads", "jvm_threads_peak_threads", "jvm_threads_states_threads",
            "jvm_classes_loaded_classes", "process_cpu_usage", "system_cpu_usage", "process_uptime_seconds",
            "process_start_time_seconds", "hikaricp_connections_active", "hikaricp_connections_idle",
            "hikaricp_connections_pending", "hikaricp_connections_max", "hikaricp_connections_timeout_total",
            "hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_count",
            "executor_active_threads", "executor_queued_tasks", "executor_pool_size_threads", "executor_pool_max_threads",
            "executor_queue_remaining_tasks", "executor_completed_tasks_total", "logback_events_total", "db_table_bytes",
            "db_table_rows", "retention_last_success_epoch", "retention_failed_policies", "retention_last_deleted_rows",
            "scripted_k6_active", "scripted_k6_queued", "scripted_k6_available", "scripted_k6_skipped_total",
            "cache_gets_total", "tomcat_sessions_active_current_sessions", "disk_free_bytes", "disk_total_bytes",
            "tasks_scheduled_execution_seconds_count", "tasks_scheduled_execution_seconds_sum");

    /** Üretim sonucu: pano + ön izleme özeti. */
    public record Result(Map<String, Object> dashboard, Summary summary) {}

    /**
     * Ön izleme özeti (Ayarlar sayfası): panel / satır sayısı, bölüm başına panel, değişkenler, kullanılan ölçümler,
     * dosya adı, revizyon ve bağlantıların eklenip eklenmediği.
     */
    public record Summary(int panels, int rows, Map<String, Integer> panelsBySection, List<String> variables,
                          List<String> metrics, String fileName, int revision, boolean links, String title, String uid) {}

    /** Panoyu üretir. */
    public static Result generate(GrafanaDashboardOptions o) {
        return new Builder(o).build();
    }

    /** İndirilen dosyanın adı: {@code sitemonitor-grafana-dashboard-<sürüm>.json}. */
    public static String fileName(String appVersion) {
        String v = appVersion == null || appVersion.isBlank() ? "local" : appVersion.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && b.length() < 40; i++) {
            char c = v.charAt(i);
            b.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' ? c : '-');
        }
        return "sitemonitor-grafana-dashboard-" + b + ".json";
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Panoyu JSON baytlarına çevirir (UTF-8; {@code pretty} = girintili, indirilen dosya böyle). */
    public static byte[] toJson(Map<String, Object> dashboard, boolean pretty) {
        try {
            return pretty ? JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(dashboard) : JSON.writeValueAsBytes(dashboard);
        } catch (Exception e) {
            throw new IllegalStateException("Grafana panosu JSON'a çevrilemedi", e);
        }
    }

    /** İçerik özeti (SHA-256, sıkıştırılmış JSON) — revizyon kapısı bunu saklı değerle karşılaştırır. */
    public static String contentHash(Map<String, Object> dashboard) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(toJson(dashboard, false));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** PromQL anahtar sözcükleri (ölçüm adı değil). */
    static final Set<String> PROMQL_KEYWORDS = Set.of("and", "or", "unless", "offset", "bool", "by", "without", "on", "ignoring",
            "group_left", "group_right", "inf", "nan", "atan2");
    /** Ardından etiket listesi gelen gruplama sözcükleri ({@code by (a, b)}). */
    static final Set<String> PROMQL_GROUPING = Set.of("by", "without", "on", "ignoring", "group_left", "group_right");

    /**
     * Sorgu metnindeki ölçüm adları — PromQL sözcük çözümlemesiyle TAM adlar (2026-10-18, bağımsız inceleme: eski düzenli ifade
     * yalnız {@code ad{} biçimini görüyor, kapı da düz metin aramasıyla kısa adı uzun adın içinde "buluyordu"). Atlananlar:
     * dize değişmezleri, {@code {…}} etiket eşleyicileri, {@code […]} aralıklar, Grafana değişkenleri ({@code $x}, {@code ${x}}),
     * fonksiyon çağrıları ({@code ad(}), gruplama etiket listeleri ({@code by (…)}), anahtar sözcükler, sayı / süreler.
     * Doğrusal tarama; düzenli ifade yok.
     */
    public static Set<String> metricsIn(String expr) {
        Set<String> out = new TreeSet<>();
        if (expr == null) return out;
        int n = expr.length();
        int i = 0;
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i++;
                while (i < n && expr.charAt(i) != c) i += expr.charAt(i) == '\\' ? 2 : 1;
                i++;
            } else if (c == '{') {
                i = skipBalanced(expr, i, '{', '}');
            } else if (c == '[') {
                i = skipBalanced(expr, i, '[', ']');
            } else if (c == '$') {
                i++;
                if (i < n && expr.charAt(i) == '{') i = skipBalanced(expr, i, '{', '}');
                else while (i < n && identPart(expr.charAt(i))) i++;
            } else if (Character.isDigit(c) || c == '.') {
                while (i < n && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '.' || expr.charAt(i) == '_')) i++;
            } else if (identStart(c)) {
                int start = i;
                while (i < n && identPart(expr.charAt(i))) i++;
                String id = expr.substring(start, i);
                int j = i;
                while (j < n && Character.isWhitespace(expr.charAt(j))) j++;
                boolean call = j < n && expr.charAt(j) == '(';
                String low = id.toLowerCase(java.util.Locale.ROOT);
                if (call && low.equals("label_values")) {
                    // Grafana değişken sorgusu label_values(ifade, etiket): son bağımsız değişken ETİKET adıdır, ölçüm değil
                    int end = skipBalanced(expr, j, '(', ')');
                    String inner = expr.substring(j + 1, Math.max(j + 1, end - 1));
                    int comma = lastTopLevelComma(inner);
                    out.addAll(metricsIn(comma < 0 ? inner : inner.substring(0, comma)));
                    i = end;
                } else if (call && PROMQL_GROUPING.contains(low)) i = skipBalanced(expr, j, '(', ')');
                else if (!call && !PROMQL_KEYWORDS.contains(low) && !aggregatorBeforeGrouping(expr, j)) out.add(id);
            } else {
                i++;
            }
        }
        return out;
    }

    /** Ayraç ve dize dışındaki son virgül; yoksa -1. */
    private static int lastTopLevelComma(String s) {
        int depth = 0;
        int last = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i++;
                while (i < s.length() && s.charAt(i) != c) i += s.charAt(i) == '\\' ? 2 : 1;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                last = i;
            }
        }
        return last;
    }

    /** {@code sum by (…) (…)} / {@code avg without (…)}: tanımlayıcıdan sonra {@code by} / {@code without} geliyorsa işleçtir. */
    private static boolean aggregatorBeforeGrouping(String expr, int at) {
        int k = at;
        while (k < expr.length() && identPart(expr.charAt(k))) k++;
        String next = expr.substring(at, k).toLowerCase(java.util.Locale.ROOT);
        return next.equals("by") || next.equals("without");
    }

    private static boolean identStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':';
    }

    private static boolean identPart(char c) {
        return identStart(c) || (c >= '0' && c <= '9');
    }

    /** {@code open}'daki ayracın eşini atlar (içteki dizeler dahil); eşi yoksa metnin sonu. */
    private static int skipBalanced(String s, int at, char open, char close) {
        int depth = 0;
        int i = at;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i++;
                while (i < s.length() && s.charAt(i) != c) i += s.charAt(i) == '\\' ? 2 : 1;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return s.length();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Kurucu
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static final class Builder {
        private final GrafanaDashboardOptions o;
        private final boolean en;
        private final String base;
        private final List<Map<String, Object>> top = new ArrayList<>();
        private List<Map<String, Object>> rowChildren = null;   // kapalı satırın panelleri
        private final Set<String> panelTypes = new TreeSet<>();
        private final Set<String> metrics = new TreeSet<>();
        private final Map<String, Integer> bySection = new LinkedHashMap<>();
        private String section;
        private int nextId = 1;
        private int x = 0, y = 0, lineH = 0;
        private int panels = 0, rows = 0;

        // Seçici parçaları
        private final String selector;
        static final String TEAM = "team=~\"$team\"";
        static final String UG = "ug_team=~\"$team\"";
        static final String TYPE = "type=~\"$type\"";
        static final String FAMILY = "family=~\"$type\"";
        static final String MON = "monitor=~\"$monitor\"";
        static final String LEVEL = "level=~\"$level\"";
        static final String RT = "job=~\"$job\", instance=~\"$instance\"";
        /** Kazıma (/metrics) ve sağlık yoklaması (/health…) kullanıcı trafiği değildir. */
        static final String NOT_PROBES = "uri!~\"/metrics.*|/health.*\"";

        static final String ML = "type, team, team_id, group, monitor, monitor_id, target";
        static final String MA = "type, team, team_id, state";
        static final String MC = "type, team, team_id";
        static final String AL = "family, alert_type, team, team_id, ug_team, ug_team_id, level, acknowledged";
        static final String AOL = "family, team, team_id, ug_team, ug_team_id, level";
        static final String APL = "alert_id, family, alert_type, target, team, team_id, ug_team, ug_team_id, level, acknowledged";
        static final String CL = "domain, team, team_id, ug_team, ug_team_id, tier, group, source";
        static final String DL = "domain, monitor, monitor_id, team, team_id, group";

        // Ölçüm adları (Prometheus)
        static String p(String micrometer) { return MonitoringMetricNames.prom(micrometer); }

        Builder(GrafanaDashboardOptions o) {
            this.o = o;
            this.en = o.english();
            this.base = o.linkBaseUrl() == null ? "" : o.linkBaseUrl();
            this.selector = o.selector() == null ? "" : o.selector().trim();
        }

        String t(String tr, String enText) { return en ? enText : tr; }

        // ── Seçiciler ve ifadeler ────────────────────────────────────────────────────────────────────────

        /** {@code {<seçici>, m1, m2}} — boş parçalar atlanır; hiç parça yoksa {@code {}}. */
        String sel(String... matchers) {
            List<String> parts = new ArrayList<>();
            if (!selector.isEmpty()) parts.add(selector);
            for (String m : matchers) if (m != null && !m.isBlank()) parts.add(m);
            return "{" + String.join(", ", parts) + "}";
        }

        String metric(String micrometer, String... matchers) { return p(micrometer) + sel(matchers); }

        /** İzleme başına ölçüm, pod'lar tekilleştirilmiş. */
        String mon(String m, String... extra) {
            List<String> mm = new ArrayList<>(List.of(TEAM, TYPE));
            if (o.perMonitor()) mm.add(MON);
            mm.addAll(Arrays.asList(extra));
            return "max by (" + ML + ") (" + metric(m, mm.toArray(String[]::new)) + ")";
        }

        /** Türe / takıma göre toplam (tekilleştirilmiş). */
        String agg(String m, String labels, String... extra) {
            List<String> mm = new ArrayList<>(List.of(TEAM, TYPE));
            mm.addAll(Arrays.asList(extra));
            return "max by (" + labels + ") (" + metric(m, mm.toArray(String[]::new)) + ")";
        }

        /** İki sahipli seri (SY + UG): takım süzgeci ikisini de arar, aynı seri bir kez. */
        String owned(String m, String labels, String... extra) {
            List<String> a = new ArrayList<>(List.of(TEAM));
            a.addAll(Arrays.asList(extra));
            List<String> b = new ArrayList<>(List.of(UG));
            b.addAll(Arrays.asList(extra));
            return "(max by (" + labels + ") (" + metric(m, a.toArray(String[]::new)) + ") or max by (" + labels + ") ("
                    + metric(m, b.toArray(String[]::new)) + "))";
        }

        /**
         * Takım başına sayım: UG sahibi olduğu seriyi kendi adına da sayar (takım tablosu). UG tarafında SY etiketleri
         * ({@code team}, {@code team_id}) ÖNCE {@code agg} ile toplanır ({@code sum} sayımda, {@code min} en eski zamanda):
         * aynı UG takımına iki farklı SY'den gelen seriler label_replace sonrası aynı etiket kümesine düşer ve Prometheus
         * "vector cannot contain metrics with the same labelset" hatası verirdi (panel boş kalırdı).
         */
        String ownedPerTeam(String m, String labels, String agg, String... extra) {
            List<String> a = new ArrayList<>(List.of(TEAM));
            a.addAll(Arrays.asList(extra));
            List<String> b = new ArrayList<>(List.of(UG, "ug_team!=\"\""));
            b.addAll(Arrays.asList(extra));
            return "(max by (" + labels + ") (" + metric(m, a.toArray(String[]::new)) + ") or label_replace(label_replace(" + agg
                    + " without (team, team_id) (max by (" + labels + ") (" + metric(m, b.toArray(String[]::new)) + ")), "
                    + "\"team\", \"$1\", \"ug_team\", \"(.+)\"), \"team_id\", \"$1\", \"ug_team_id\", \"(.+)\"))";
        }

        /**
         * Kaynak okunabiliyorsa boş sonucu 0 yapar, okunamıyorsa "veri yok" bırakır (sahte 0 YOK):
         * {@code (X) or (vector(0) and on() (max(source_up{source}) == 1))}.
         */
        String guard(String expr, String source) {
            return "(" + expr + ") or (vector(0) and on() (max(" + p(MonitoringMetricNames.EXPORTER_SOURCE)
                    + sel("source=\"" + source + "\"") + ") == 1))";
        }

        /**
         * İzleme sayıları: {@link #guard} + süzgece uyan bir tür HİÇBİR pod'da okunamadıysa "veri yok". Okunamayan türün
         * satırları dışa verilmez; toplam yalnız diğer türlerden oluşur ve "tümü" gibi görünürdü (sahte düşük sayı / sahte
         * yeşil). Aktarıcı her tür için {@code source="monitors:<tür>"} yazar (1 okundu, 0 okunamadı); pod'lardan biri okuduysa
         * {@code max} 1'dir (izleme serileri pod'lar arası {@code max by} ile birleşir, o pod'un verisi tamdır).
         */
        String monitorGuard(String expr) {
            return "(" + guard(expr, "overview") + ")" + unlessTypeUnreadable();
        }

        /**
         * Lejantı / çubuğu tür kimliğiyle ({@code {{type}}} / {@code {{family}}}) adlanan panellerde okunur tür adı: değer
         * eşlemesi (mappings) dizi ADINA uygulanmaz, ad başına {@code displayName} geçersiz kılması gerekir.
         */
        void typeNames(Panel pn) {
            for (Map.Entry<String, String[]> e : TYPE_LABELS.entrySet()) pn.override(e.getKey(), "displayName", en ? e.getValue()[1] : e.getValue()[0]);
        }

        String unlessTypeUnreadable() {
            return " unless on() (max by (source) (" + p(MonitoringMetricNames.EXPORTER_SOURCE)
                    + sel("source=~\"monitors:$type\"") + ") == 0)";
        }

        // ── Yerleşim ─────────────────────────────────────────────────────────────────────────────────────

        void row(String sectionId, String title, boolean collapsed) {
            section = sectionId;
            bySection.putIfAbsent(sectionId, 0);
            newLine();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", "row");
            r.put("title", title);
            r.put("collapsed", collapsed);
            r.put("gridPos", grid(24, 1, 0, y));
            r.put("id", nextId++);
            List<Map<String, Object>> children = new ArrayList<>();
            r.put("panels", children);
            top.add(r);
            rows++;
            y += 1;
            rowChildren = collapsed ? children : null;
        }

        void newLine() {
            if (x > 0 || lineH > 0) { y += lineH; x = 0; lineH = 0; }
        }

        Map<String, Object> grid(int w, int h, int gx, int gy) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("h", h);
            g.put("w", w);
            g.put("x", gx);
            g.put("y", gy);
            return g;
        }

        void add(Panel pn) {
            if (x + pn.w > 24) newLine();
            pn.json.put("gridPos", grid(pn.w, pn.h, x, y));
            x += pn.w;
            lineH = Math.max(lineH, pn.h);
            if (x >= 24) newLine();
            pn.json.put("id", nextId++);
            panelTypes.add(String.valueOf(pn.json.get("type")));
            for (Map<String, Object> tg : pn.targets) metrics.addAll(metricsIn(String.valueOf(tg.get("expr"))));
            (rowChildren != null ? rowChildren : top).add(pn.json);
            panels++;
            bySection.merge(section, 1, Integer::sum);
        }

        // ── Bağlantılar ──────────────────────────────────────────────────────────────────────────────────

        boolean links() { return !base.isEmpty(); }

        String url(String query) { return base + "/?" + query; }

        static Map<String, Object> link(String title, String url) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("title", title);
            l.put("url", url);
            l.put("targetBlank", true);
            return l;
        }

        // ══════════════════════════════════════════════════════════════════════════════════════════════════════
        Result build() {
            for (String s : SECTIONS) {
                if (!o.has(s)) continue;
                switch (s) {
                    case "overview" -> overview();
                    case "alerts" -> alerts();
                    case "monitors" -> monitors();
                    case "certificates" -> certificates();
                    case "notifications" -> notifications();
                    case "health" -> health();
                    case "runtime" -> runtime();
                    default -> { }
                }
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("__inputs", List.of(input()));
            d.put("__elements", new LinkedHashMap<>());
            d.put("__requires", requires());
            d.put("annotations", om("list", annotations()));
            d.put("description", description());
            d.put("editable", true);
            d.put("fiscalYearStartMonth", 0);
            d.put("graphTooltip", 1);
            d.put("id", null);
            d.put("links", dashboardLinks());
            d.put("liveNow", false);
            d.put("panels", top);
            d.put("refresh", o.refresh());
            d.put("schemaVersion", SCHEMA_VERSION);
            d.put("tags", List.of("sitemonitor", t("izleme", "monitoring"), "prometheus"));
            List<Map<String, Object>> vars = variables();
            d.put("templating", om("list", vars));
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("from", o.timeFrom());
            time.put("to", "now");
            d.put("time", time);
            d.put("timepicker", om("refresh_intervals", GrafanaDashboardSettings.REFRESH_OPTIONS));
            d.put("timezone", "browser");
            d.put("title", title());
            d.put("uid", o.uid());
            d.put("version", DASHBOARD_REVISION);
            d.put("weekStart", "monday");
            for (Map<String, Object> v : vars) {
                Object q = v.get("query");
                if (q instanceof String s && "query".equals(v.get("type"))) metrics.addAll(metricsIn(s));
            }
            List<String> varNames = new ArrayList<>();
            for (Map<String, Object> v : vars) varNames.add(String.valueOf(v.get("name")));
            Summary sum = new Summary(panels, rows, bySection, varNames, new ArrayList<>(metrics), fileName(o.appVersion()),
                    DASHBOARD_REVISION, links(), title(), o.uid());
            return new Result(d, sum);
        }

        String title() {
            return o.title() == null || o.title().isBlank() ? t("SiteMonitor — Uçtan uca izleme", "SiteMonitor — End-to-end monitoring")
                    : o.title();
        }

        String description() {
            String v = o.appVersion() == null || o.appVersion().isBlank() ? "local" : o.appVersion();
            return t("SiteMonitor izleme durumu, alarmlar, sertifikalar ve sistemin kendi sağlığı — takım ve tür süzgeçli. "
                            + "Pano revizyonu " + DASHBOARD_REVISION + " · SiteMonitor " + v + ".",
                    "SiteMonitor monitor state, alerts, certificates and the system's own health — filter by team and type. "
                            + "Dashboard revision " + DASHBOARD_REVISION + " · SiteMonitor " + v + ".");
        }

        Map<String, Object> input() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", DS_INPUT);
            m.put("label", "Prometheus");
            m.put("description", t("SiteMonitor /metrics ucunu kazıyan Prometheus veri kaynağı",
                    "The Prometheus data source that scrapes SiteMonitor /metrics"));
            m.put("type", "datasource");
            m.put("pluginId", "prometheus");
            m.put("pluginName", "Prometheus");
            return m;
        }

        List<Map<String, Object>> requires() {
            List<Map<String, Object>> l = new ArrayList<>();
            l.add(req("grafana", "grafana", "Grafana", GRAFANA_MIN_VERSION));
            l.add(req("datasource", "prometheus", "Prometheus", "1.0.0"));
            Map<String, String> names = java.util.Map.of("stat", "Stat", "timeseries", "Time series", "table", "Table", "piechart", "Pie chart",
                    "bargauge", "Bar gauge", "barchart", "Bar chart", "state-timeline", "State timeline", "text", "Text");
            for (String pt : panelTypes) l.add(req("panel", pt, names.getOrDefault(pt, pt), ""));
            return l;
        }

        static Map<String, Object> req(String type, String id, String name, String version) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("id", id);
            m.put("name", name);
            m.put("version", version);
            return m;
        }

        static Map<String, Object> ds() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "prometheus");
            m.put("uid", DS_VAR);
            return m;
        }

        List<Map<String, Object>> dashboardLinks() {
            List<Map<String, Object>> l = new ArrayList<>();
            if (!links()) return l;
            l.add(dlink("SiteMonitor", base + "/"));
            l.add(dlink(t("İzleme Panosu", "Monitoring overview"), url("tab=monitoring")));
            l.add(dlink(t("Açık alarmlar", "Open alerts"), url("tab=alerthistory&view=open")));
            l.add(dlink(t("Sertifikalar", "Certificates"), url("tab=all")));
            l.add(dlink(t("Sistem sağlığı", "System health"), url("tab=health")));
            return l;
        }

        Map<String, Object> dlink(String title, String u) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("type", "link");
            m.put("url", u);
            m.put("icon", "external link");
            m.put("tooltip", t("SiteMonitor'da aç (oturum gerekir)", "Open in SiteMonitor (sign-in required)"));
            m.put("targetBlank", true);
            m.put("asDropdown", false);
            m.put("includeVars", false);
            m.put("keepTime", false);
            m.put("tags", List.of());
            return m;
        }

        // ── Anotasyonlar ─────────────────────────────────────────────────────────────────────────────────

        List<Map<String, Object>> annotations() {
            List<Map<String, Object>> l = new ArrayList<>();
            Map<String, Object> builtIn = new LinkedHashMap<>();
            builtIn.put("builtIn", 1);
            builtIn.put("datasource", om("type", "grafana", "uid", "-- Grafana --"));
            builtIn.put("enable", true);
            builtIn.put("hide", true);
            builtIn.put("iconColor", "rgba(0, 211, 255, 1)");
            builtIn.put("name", "Annotations & Alerts");
            builtIn.put("type", "dashboard");
            l.add(builtIn);
            // Yeni sürüm = şu an görülen ama 5 dk önce görülmeyen build etiketi. changes() KULLANILMAZ: sabit 1 olan yeni
            // doğmuş seride sonuç 0'dır (anotasyon hiç düşmezdi). 5 dk pencere uzun aralıkta Grafana'nın büyüttüğü adımdan
            // (30 günde ≈ 4 dk) büyük — dağıtım iki örnek arasında kalıp kaçmaz; count by çoklu pod'u tek anotasyona indirir.
            String deploy = "count by (version, commit, environment) (sitemonitor_build_info" + sel()
                    + ") unless count by (version, commit, environment) (sitemonitor_build_info" + sel() + " offset 5m)";
            metrics.addAll(metricsIn(deploy));
            l.add(annotation(t("Sürüm dağıtımı", "Deployments"), "blue", deploy, "SiteMonitor {{version}}",
                    "{{version}} ({{commit}}) — {{environment}}", "version", true, false));
            if (o.perMonitor()) {
                String a = metric(MonitoringMetricNames.ALERT_OPEN, TEAM, "level=~\"CRITICAL|HIGH\"");
                String b = metric(MonitoringMetricNames.ALERT_OPEN, UG, "level=~\"CRITICAL|HIGH\"");
                // "Açıldı" = alarmın AÇILIŞ ZAMANI (serinin değeri, epoch sn) son 15 dk içinde. Değer zaman olarak kullanılır
                // (useValueForTime, ms): anotasyon tam açılış anına düşer ve aynı alarmın her adımı tek çizgiye birleşir. Önceki
                // biçim ("1 dk önce seri yoktu") pod yeniden başlatma ya da okunamayan bir aktarıcı turundan sonra (≥ 1 dk boşluk)
                // bütün açık alarmları yeniden "açıldı" gösteriyordu (sahte fırtına; 2026-10-18 bağımsız inceleme). Pencere,
                // Grafana'nın uzun aralıkta büyüttüğü adımdan (30 günde ≈ 4 dk) büyük — açılış iki örnek arasında kaçmaz.
                String an = "alert_id, family, alert_type, target, team, level";
                String opened = "((max by (" + an + ") (" + a + ") or max by (" + an + ") (" + b + ")) > (time() - "
                        + ALERT_OPENED_WINDOW_S + ")) * 1000";
                metrics.addAll(metricsIn(opened));
                l.add(annotation(t("Kritik / yüksek alarm açıldı", "Critical / high alert opened"), "red", opened,
                        "{{level}} · {{family}} · {{target}}", t("Takım: {{team}} · tür: {{alert_type}}", "Team: {{team}} · type: {{alert_type}}"),
                        "level,family,team", true, true));
            }
            return l;
        }

        Map<String, Object> annotation(String name, String color, String expr, String title, String text, String tags, boolean enable,
                                       boolean valueIsTime) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("datasource", ds());
            m.put("enable", enable);
            m.put("expr", expr);
            m.put("iconColor", color);
            m.put("name", name);
            m.put("step", "1m");
            m.put("tagKeys", tags);
            m.put("textFormat", text);
            m.put("titleFormat", title);
            m.put("useValueForTime", valueIsTime);
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("expr", expr);
            target.put("interval", "1m");
            target.put("refId", "Anno");
            m.put("target", target);
            return m;
        }

        // ── Değişkenler ──────────────────────────────────────────────────────────────────────────────────

        List<Map<String, Object>> variables() {
            List<Map<String, Object>> l = new ArrayList<>();
            Map<String, Object> dsv = new LinkedHashMap<>();
            dsv.put("current", current("Prometheus", "${" + DS_INPUT + "}", false));
            dsv.put("hide", 0);
            dsv.put("includeAll", false);
            dsv.put("label", t("Veri kaynağı", "Data source"));
            dsv.put("multi", false);
            dsv.put("name", "datasource");
            dsv.put("options", List.of());
            dsv.put("query", "prometheus");
            dsv.put("refresh", 1);
            dsv.put("regex", "");
            dsv.put("skipUrlSync", false);
            dsv.put("type", "datasource");
            l.add(dsv);

            Map<String, Object> team = queryVar("team", t("Takım", "Team"),
                    "label_values(" + p(MonitoringMetricNames.TEAMS) + sel() + ", team)", true, ".*", 1);
            if (o.defaultTeam() != null && !o.defaultTeam().isBlank()) {
                team.put("current", current(o.defaultTeam(), o.defaultTeam(), true));
            }
            team.put("description", t("Bir ya da birkaç takım seçin; alarmlar ve sertifikalar SY ve UG sahibine göre süzülür.",
                    "Pick one or more teams; alerts and certificates match their SY and UG owners."));
            l.add(team);

            List<String[]> typeOpts = new ArrayList<>();
            for (Map.Entry<String, String[]> e : TYPE_LABELS.entrySet()) typeOpts.add(new String[]{en ? e.getValue()[1] : e.getValue()[0], e.getKey()});
            Map<String, Object> type = customVar("type", t("İzleme türü", "Monitor type"), typeOpts, true, ".*", null);
            type.put("description", t("İzleme türü (sertifika alarmları için Sertifika).", "Monitor type (Certificate for certificate alerts)."));
            l.add(type);

            if (o.perMonitor()) {
                Map<String, Object> mon = queryVar("monitor", t("İzleme", "Monitor"),
                        "label_values(" + metric(MonitoringMetricNames.MONITOR_STATE, TEAM, TYPE) + ", monitor)", true, ".*", 2);
                mon.put("description", t("Takım ve tür süzgecine uyan izlemeler.", "Monitors matching the team and type filter."));
                l.add(mon);
            }

            List<String[]> levels = List.of(new String[]{t("Kritik", "Critical"), "CRITICAL"}, new String[]{t("Yüksek", "High"), "HIGH"},
                    new String[]{t("Uyarı", "Warning"), "WARNING"}, new String[]{t("Diğer", "Other"), "OTHER"});
            Map<String, Object> level = customVar("level", t("Alarm seviyesi", "Alert level"), levels, true, ".*", null);
            level.put("description", t("Alarm bölümündeki grafik ve tabloları süzer.", "Filters the charts and tables of the alerts section."));
            l.add(level);

            Set<Integer> tops = new TreeSet<>(List.of(5, 10, 20, 50));
            tops.add(o.topN());
            List<String[]> topOpts = new ArrayList<>();
            for (Integer n : tops) topOpts.add(new String[]{String.valueOf(n), String.valueOf(n)});
            Map<String, Object> topn = customVar("topn", t("İlk N", "Top N"), topOpts, false, null, String.valueOf(o.topN()));
            topn.put("description", t("\"En yavaş / en az kalan\" panellerinin satır sayısı.", "Row count of the \"slowest / soonest\" panels."));
            l.add(topn);

            Map<String, Object> job = queryVar("job", t("Prometheus işi", "Prometheus job"),
                    "label_values(sitemonitor_build_info" + sel() + ", job)", true, null, 2);
            job.put("description", t("SiteMonitor'ü kazıyan iş(ler) — JVM / HTTP panelleri yalnız bunlara bakar.",
                    "The job(s) scraping SiteMonitor — JVM / HTTP panels only read these."));
            l.add(job);
            Map<String, Object> inst = queryVar("instance", t("Pod (örnek)", "Pod (instance)"),
                    "label_values(sitemonitor_build_info" + sel("job=~\"$job\"") + ", instance)", true, null, 2);
            inst.put("description", t("SiteMonitor pod'ları (son kazımada build_info yayanlar).",
                    "SiteMonitor pods (those that exported build_info)."));
            l.add(inst);
            return l;
        }

        Map<String, Object> current(String text, String value, boolean selected) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("selected", selected);
            c.put("text", text);
            c.put("value", value);
            return c;
        }

        Map<String, Object> allCurrent() {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("selected", true);
            c.put("text", List.of("All"));
            c.put("value", List.of("$__all"));
            return c;
        }

        Map<String, Object> queryVar(String name, String label, String query, boolean multi, String allValue, int refresh) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("allValue", allValue);
            v.put("current", allCurrent());
            v.put("datasource", ds());
            v.put("definition", query);
            v.put("hide", 0);
            v.put("includeAll", true);
            v.put("label", label);
            v.put("multi", multi);
            v.put("name", name);
            v.put("options", List.of());
            v.put("query", query);
            v.put("refresh", refresh);
            v.put("regex", "");
            v.put("skipUrlSync", false);
            v.put("sort", 5);
            v.put("type", "query");
            return v;
        }

        Map<String, Object> customVar(String name, String label, List<String[]> opts, boolean all, String allValue, String currentValue) {
            Map<String, Object> v = new LinkedHashMap<>();
            if (all) v.put("allValue", allValue);
            List<String> q = new ArrayList<>();
            List<Map<String, Object>> options = new ArrayList<>();
            if (all) options.add(option("All", "$__all", currentValue == null));
            for (String[] op : opts) {
                q.add(op[0].equals(op[1]) ? op[1] : op[0] + " : " + op[1]);
                options.add(option(op[0], op[1], op[1].equals(currentValue)));
            }
            if (currentValue == null && all) v.put("current", allCurrent());
            else {
                String text = currentValue;
                for (String[] op : opts) if (op[1].equals(currentValue)) text = op[0];
                v.put("current", current(text, currentValue, true));
            }
            v.put("hide", 0);
            v.put("includeAll", all);
            v.put("label", label);
            v.put("multi", all);
            v.put("name", name);
            v.put("options", options);
            v.put("query", String.join(", ", q));
            v.put("skipUrlSync", false);
            v.put("type", "custom");
            return v;
        }

        static Map<String, Object> option(String text, String value, boolean selected) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("selected", selected);
            m.put("text", text);
            m.put("value", value);
            return m;
        }

        // ── Renk ve eşlemeler ────────────────────────────────────────────────────────────────────────────

        static final String GREEN = "green", AMBER = "orange", RED = "red", GREY = "#8E8E8E", BLUE = "blue", PURPLE = "purple",
                YELLOW = "yellow", DARK_RED = "dark-red";

        /** Değer → metin + renk eşlemesi (Grafana {@code type: value}). */
        /** Aralık eşlemesi: {@code from ≤ değer ≤ to} → metin + renk. */
        static Map<String, Object> rangeMap(double from, double to, String text, String color) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("color", color);
            result.put("index", 0);
            result.put("text", text);
            Map<String, Object> opts = new LinkedHashMap<>();
            opts.put("from", from);
            opts.put("result", result);
            opts.put("to", to);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("options", opts);
            m.put("type", "range");
            return m;
        }

        static Map<String, Object> valueMap(Object[][] entries) {
            Map<String, Object> opts = new LinkedHashMap<>();
            int i = 0;
            for (Object[] e : entries) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("color", e[2]);
                r.put("index", i++);
                r.put("text", e[1]);
                opts.put(String.valueOf(e[0]), r);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("options", opts);
            m.put("type", "value");
            return m;
        }

        List<Object> stateMappings() {
            return List.of(valueMap(new Object[][]{
                    {0, t("Sağlıklı", "Up"), GREEN}, {1, t("Gecikmiş", "Stale"), AMBER}, {2, t("Düşük", "Down"), RED},
                    {3, t("Bilinmiyor", "Unknown"), GREY}, {4, t("Duraklatılmış", "Paused"), "#5C6F82"}}));
        }

        List<Object> severityMappings() {
            return List.of(valueMap(new Object[][]{
                    {0, t("Yok", "None"), GREEN}, {1, t("Uyarı", "Warning"), YELLOW}, {2, t("Yüksek", "High"), AMBER},
                    {3, t("Kritik", "Critical"), RED}}));
        }

        List<Object> certMappings() {
            return List.of(valueMap(new Object[][]{
                    {0, t("Geçerli", "Valid"), GREEN}, {1, t("Uyarı", "Warning"), YELLOW}, {2, t("Yüksek", "High"), AMBER},
                    {3, t("Kritik", "Critical"), RED}, {4, t("Süresi doldu", "Expired"), DARK_RED}, {5, t("Kontrol hatası", "Check error"), PURPLE}}));
        }

        List<Object> gradeMappings() {
            return List.of(valueMap(new Object[][]{
                    {6, "A+", GREEN}, {5, "A", "semi-dark-green"}, {4, "B", YELLOW}, {3, "C", AMBER}, {2, "D", RED}, {1, "F", DARK_RED}}));
        }

        List<Object> watchdogMappings() {
            return List.of(valueMap(new Object[][]{
                    {0, t("Sağlıklı", "Up"), GREEN}, {1, t("Sorunlu", "Degraded"), AMBER}, {2, t("Çalışmıyor", "Down"), RED},
                    {3, t("Kapalı", "Disabled"), GREY}, {4, t("Değerlendirilmedi", "Not evaluated"), GREY}}));
        }

        List<Object> checkMappings() {
            return List.of(valueMap(new Object[][]{
                    {0, t("Tamam", "OK"), GREEN}, {1, t("Orta sorun", "Medium problem"), YELLOW}, {2, t("Yüksek sorun", "High problem"), AMBER},
                    {3, t("Kritik sorun", "Critical problem"), RED}, {4, t("Değerlendirilmedi", "Not evaluated"), GREY}}));
        }

        List<Object> typeMappings() {
            Object[][] e = new Object[TYPE_LABELS.size()][];
            int i = 0;
            for (Map.Entry<String, String[]> en2 : TYPE_LABELS.entrySet()) e[i++] = new Object[]{en2.getKey(), en ? en2.getValue()[1] : en2.getValue()[0], "text"};
            return List.of(valueMap(e));
        }

        static Map<String, Object> thresholds(Object... colorValue) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (int i = 0; i < colorValue.length; i += 2) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("color", colorValue[i]);
                s.put("value", colorValue[i + 1]);
                steps.add(s);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", "absolute");
            m.put("steps", steps);
            return m;
        }

        // ══════════════════════════════════════════════════════════════════════════════════════════════════════
        // Bölümler
        // ══════════════════════════════════════════════════════════════════════════════════════════════════════

        void overview() {
            row("overview", t("Genel durum", "Overview"), false);
            Panel help = Panel.text(t("Nasıl okunur", "How to read"), t(
                    "Üstteki **Takım** ve **İzleme türü** süzgeçleri bütün panoyu süzer. Renkler: **yeşil** sağlıklı, **turuncu** "
                            + "gecikmiş (kontrol zamanında koşmadı), **kırmızı** düşük / sorunlu, **gri** duraklatılmış ya da bilinmiyor. "
                            + "\"Veri yok\" = kaynak okunamadı ya da aktarıcı bayat (asla sahte 0 değil; bkz. Veri tazeliği). "
                            + "Pano erişim denetimi DEĞİLDİR: Grafana'da panoyu açabilen herkes bütün kurumun izleme verisini görür. "
                            + "Tür süzgeci izleme ve alarm panellerine uygulanır; "
                            + "bildirimler, SiteMonitor sağlığı ve çalışma zamanı kuruluş genelidir. Ayrıntı için satırdaki bağlantıyla "
                            + "SiteMonitor'a geçin (oturum gerekir).",
                    "The **Team** and **Monitor type** filters above apply to the whole dashboard. Colours: **green** up, **orange** "
                            + "stale (the check did not run on time), **red** down / failing, **grey** paused or unknown. "
                            + "\"No data\" = the source could not be read or the exporter is stale (never a fake 0; see Data freshness). "
                            + "The dashboard is NOT access control: anyone who can open it in Grafana sees the whole organisation's monitoring data. "
                            + "The type filter applies to monitor and alert "
                            + "panels; notifications, SiteMonitor health and runtime are organisation-wide. Use the row links to jump into "
                            + "SiteMonitor (sign-in required)."),
                    t("Panonun okunuşu ve renk anlamları.", "How to read the dashboard and what the colours mean."));
            help.w = 24; help.h = 5;
            add(help);

            // Tazelik şeridi: aktarıcının son turundan beri geçen süre. Sunucu, son tamamlanan tur 3 tazeleme aralığından (en az
            // 3 dk) eskiyse iş değerlerini yayınlamaz → iş panelleri "veri yok"; bu şerit nedenini söyler (takılmış iş parçacığı
            // eski değerleri "güncel" gösteremez).
            String freshExpr = "time() - max(" + metric(MonitoringMetricNames.EXPORTER_LAST_REFRESH) + ")";
            Panel fresh = stat(t("Veri tazeliği", "Data freshness"), t(
                    "İş ölçümleri aktarıcısının son turundan beri geçen süre (her pod). 3 tazeleme aralığından (en az 3 dk) eskiyse "
                            + "/metrics/sitemonitor iş değerlerini yayınlamaz ve paneller \"veri yok\" gösterir — pod günlüğünde "
                            + "\"İzleme ölçümleri tazelenemedi\" arayın. \"Veri yok\": uç kazınmıyor (belirteç / Prometheus işi).",
                    "Time since the metrics exporter's last refresh (any pod). When it is older than 3 refresh intervals (at least 3 min) "
                            + "/metrics/sitemonitor stops serving business values and the panels show \"no data\" — look for "
                            + "\"İzleme ölçümleri tazelenemedi\" in the pod log. \"No data\": the endpoint is not scraped (token / Prometheus job)."),
                    freshExpr, "dtdurations", thresholds(GREEN, null, AMBER, EXPORTER_STALE_S, RED, 600), "background", null).size(24, 3);
            fresh.mappings(List.<Object>of(rangeMap(0, EXPORTER_STALE_S - 0.001, t("Güncel", "Up to date"), GREEN)));
            fresh.defaults.put("noValue", t("Veri yok — /metrics/sitemonitor kazınmıyor", "No data — /metrics/sitemonitor is not scraped"));
            add(fresh);

            String up = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"up\"") + ")";
            String stale = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"stale\"") + ")";
            String down = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"down\"") + ")";
            String unknown = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"unknown\"") + ")";
            String paused = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"paused\"") + ")";
            String active = "sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=~\"up|stale|down|unknown\"") + ")";

            add(stat(t("Etkin izleme", "Active monitors"), t("Süzgece uyan etkin izlemeler (duraklatılmışlar hariç).",
                    "Active monitors matching the filter (paused excluded)."), monitorGuard(active), "short",
                    thresholds(BLUE, null), "value", links() ? url("tab=monitoring") : null));
            add(stat(t("Sağlıklı", "Up"), t("Son kontrolü başarılı ve açık alarmı olmayan izlemeler.",
                    "Monitors whose last check passed and have no open alert."), monitorGuard(up), "short",
                    thresholds(GREEN, null), "value", links() ? url("tab=monitoring&mo_status=up") : null));
            add(stat(t("Düşük", "Down"), t("Son kontrolü başarısız ya da açık alarmı olan izlemeler.",
                    "Monitors whose last check failed or that have an open alert."), monitorGuard(down), "short",
                    thresholds(GREEN, null, RED, 1), "background", links() ? url("tab=monitoring&mo_status=down") : null));
            add(stat(t("Gecikmiş", "Stale"), t("Kontrolü aralığının üç katından uzun süredir koşmamış izlemeler (tarama takılmış olabilir).",
                    "Monitors not checked for more than three intervals (a sweep may be stuck)."), monitorGuard(stale), "short",
                    thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=monitoring&mo_status=stale") : null));
            add(stat(t("Bilinmiyor", "Unknown"), t("Etkin ama hiç kontrol edilmemiş izlemeler.", "Active monitors never checked yet."),
                    monitorGuard(unknown), "short", thresholds(GREEN, null, GREY, 1), "background", null));
            add(stat(t("Duraklatılmış", "Paused"), t("Duraklatılmış izlemeler (envanterden çıkmış türev satırlar dahil).",
                    "Paused monitors (incl. derived rows no longer in the inventory)."), monitorGuard(paused), "short",
                    thresholds(GREY, null), "value", null));

            String checks = "sum(" + agg(MonitoringMetricNames.MONITORS_CHECKS, MC) + ")";
            String failed = "sum(" + agg(MonitoringMetricNames.MONITORS_FAILED, MC) + ")";
            Panel sr = stat(t("24 sa başarı oranı", "24 h success rate"), t("Son 24 saatteki kontrollerin başarılı olan payı (koşum yoksa veri yok).",
                    "Share of checks that passed in the last 24 hours (no checks = no data)."),
                    "(1 - (" + failed + " / (" + checks + " > 0)))" + unlessTypeUnreadable(), "percentunit",
                    thresholds(RED, null, AMBER, 0.95, GREEN, 0.99), "value", null);
            sr.decimals(2);
            add(sr);
            add(stat(t("24 sa kontrol", "Checks (24 h)"), t("Son 24 saatte koşan izleme kontrolleri (sertifika taraması hariç).",
                    "Monitor checks run in the last 24 hours (certificate scans excluded)."), monitorGuard(checks), "short",
                    thresholds(BLUE, null), "value", null));

            add(alertLevelStat("CRITICAL", t("Kritik alarm", "Critical alerts"), RED));
            add(alertLevelStat("HIGH", t("Yüksek alarm", "High alerts"), AMBER));
            add(alertLevelStat("WARNING", t("Uyarı alarmı", "Warning alerts"), YELLOW));
            String unacked = "sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, "acknowledged=\"false\"") + ")";
            add(stat(t("Sahiplenilmemiş alarm", "Unacknowledged alerts"), t("Kimsenin sahiplenmediği açık alarmlar.",
                    "Open alerts nobody has acknowledged."), guard(unacked, "alerts"), "short", thresholds(GREEN, null, AMBER, 1),
                    "background", links() ? url("tab=alerthistory&view=open&ack=unack") : null));

            String exp30 = "sum(" + owned(MonitoringMetricNames.CERTS_EXPIRY, "team, team_id, ug_team, ug_team_id, bucket", "bucket=~\"d7|d30\"") + ")";
            String exp7 = "sum(" + owned(MonitoringMetricNames.CERTS_EXPIRY, "team, team_id, ug_team, ug_team_id, bucket", "bucket=\"d7\"") + ")";
            String expired = "sum(" + owned(MonitoringMetricNames.CERTS_EXPIRY, "team, team_id, ug_team, ug_team_id, bucket", "bucket=\"expired\"") + ")";
            add(stat(t("Sertifika ≤ 30 gün", "Certificates ≤ 30 days"), t("30 gün içinde süresi dolacak (dolmamış) sertifikalar.",
                    "Certificates expiring within 30 days (not yet expired)."), guard(exp30, "certificates"), "short",
                    thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=all&c_win=30") : null));
            add(stat(t("Sertifika ≤ 7 gün", "Certificates ≤ 7 days"), t("7 gün içinde süresi dolacak sertifikalar.",
                    "Certificates expiring within 7 days."), guard(exp7, "certificates"), "short",
                    thresholds(GREEN, null, RED, 1), "background", links() ? url("tab=all&c_win=7") : null));
            add(stat(t("Süresi dolmuş", "Expired"), t("Süresi dolmuş, hâlâ izlenen sertifikalar.", "Expired certificates still being monitored."),
                    guard(expired, "certificates"), "short", thresholds(GREEN, null, DARK_RED, 1), "background",
                    links() ? url("tab=all&c_st=expired") : null));
            String dom30 = "sum(" + "max by (team, team_id, bucket) (" + metric(MonitoringMetricNames.DOMAINS_EXPIRY, TEAM, "bucket=~\"expired|d7|d30\"") + "))";
            add(stat(t("Alan adı ≤ 30 gün", "Domains ≤ 30 days"), t("Kaydı 30 gün içinde bitecek ya da bitmiş alan adları.",
                    "Domain registrations expiring within 30 days or already expired."), guard(dom30, "domains"), "short",
                    thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=domain") : null));
            String storms = "sum(max by (team, team_id) (" + metric(MonitoringMetricNames.STORMS_ACTIVE, TEAM) + "))";
            add(stat(t("Aktif fırtına", "Active storms"), t("Şu an süren alarm fırtınaları (takım kapsamlı).", "Alert storms in progress (team scoped)."),
                    guard(storms, "storms"), "short", thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=storms") : null));
            Panel wd = stat(t("Kendi kendini izleme", "Self-monitoring"), t("SiteMonitor'ün kendi kontrolleri (tarama liderliği, taramalar, bildirim, kuyruk, veritabanı).",
                    "SiteMonitor's own checks (sweep leadership, sweeps, notifications, queue, database)."),
                    "max(" + metric(MonitoringMetricNames.WATCHDOG_STATUS) + ")", "none", thresholds(GREEN, null, AMBER, 1, RED, 2, GREY, 3),
                    "background", links() ? url("tab=health") : null);
            wd.mappings(watchdogMappings());
            add(wd);

            // Dağılımlar
            Panel pie = new Panel("piechart", t("İzleme durumu dağılımı", "Monitor state distribution"),
                    t("Süzgece uyan izlemelerin durumlara dağılımı.", "How the filtered monitors split across states."));
            pie.w = 6; pie.h = 9;
            for (String[] s : stateSeries()) pie.target("sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"" + s[0] + "\"") + ")", s[1], true);
            pie.options(pieOptions());
            pie.fixedColors(stateColors());
            add(pie);

            Panel byType = stateBar(t("Türe göre durum", "State by monitor type"), t("Her izleme türünde kaç izlemenin hangi durumda olduğu.",
                    "How many monitors of each type are in each state."), "type");
            byType.w = 9; byType.h = 9;
            add(byType);
            Panel byTeam = stateBar(t("Takıma göre durum", "State by team"), t("Her takımın izlemelerinin durumu (sorunlu takımlar kırmızı / turuncu ile öne çıkar).",
                    "State of each team's monitors (teams with problems stand out in red / orange)."), "team");
            byTeam.w = 9; byTeam.h = 9;
            add(byTeam);
        }

        Panel alertLevelStat(String level, String title, String color) {
            String expr = "sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, "level=\"" + level + "\"") + ")";
            return stat(title, t("Bu seviyedeki açık alarmlar (takım süzgecine göre; SY ve UG sahibi dahil).",
                    "Open alerts at this level (team filter applies; SY and UG owners included)."), guard(expr, "alerts"), "short",
                    thresholds(GREEN, null, color, 1), "background", links() ? url("tab=alerthistory&view=open&level=" + level) : null);
        }

        List<String[]> stateSeries() {
            return List.of(new String[]{"up", t("Sağlıklı", "Up")}, new String[]{"stale", t("Gecikmiş", "Stale")},
                    new String[]{"down", t("Düşük", "Down")}, new String[]{"unknown", t("Bilinmiyor", "Unknown")},
                    new String[]{"paused", t("Duraklatılmış", "Paused")});
        }

        Map<String, String> stateColors() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(t("Sağlıklı", "Up"), GREEN);
            m.put(t("Gecikmiş", "Stale"), AMBER);
            m.put(t("Düşük", "Down"), RED);
            m.put(t("Bilinmiyor", "Unknown"), GREY);
            m.put(t("Duraklatılmış", "Paused"), "#5C6F82");
            return m;
        }

        /** Durum yığılmış çubuk grafiği (tür ya da takım satırları) — anlık tablo + "gruplamayı matrise çevir". */
        Panel stateBar(String title, String desc, String rowLabel) {
            Panel pn = new Panel("barchart", title, desc);
            pn.targetTable("sum by (" + rowLabel + ", state) (" + agg(MonitoringMetricNames.MONITORS, MA, "state!=\"paused\"") + ")", "A");
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("id", "groupingToMatrix");
            Map<String, Object> to = new LinkedHashMap<>();
            to.put("columnField", "state");
            to.put("emptyValue", "zero");
            to.put("rowField", rowLabel);
            to.put("valueField", "Value");
            tr.put("options", to);
            pn.transformations.add(tr);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("barRadius", 0);
            opt.put("barWidth", 0.85);
            opt.put("fullHighlight", false);
            opt.put("groupWidth", 0.7);
            opt.put("legend", legend("list", "bottom", List.of()));
            opt.put("orientation", "horizontal");
            opt.put("showValue", "auto");
            opt.put("stacking", "normal");
            opt.put("tooltip", tooltip("multi"));
            opt.put("xTickLabelRotation", 0);
            opt.put("xTickLabelSpacing", 0);
            pn.options(opt);
            pn.defaultsCustom(om("fillOpacity", 85, "gradientMode", "none", "lineWidth", 0, "axisPlacement", "auto"));
            String first = rowLabel + "\\state";
            pn.override(first, "displayName", "type".equals(rowLabel) ? t("Tür", "Type") : t("Takım", "Team"));
            if ("type".equals(rowLabel)) pn.override(first, "mappings", typeMappings());
            for (String[] s : stateSeries()) {
                if ("paused".equals(s[0])) continue;
                pn.override(s[0], "displayName", s[1]);
                pn.override(s[0], "color", om("mode", "fixed", "fixedColor", stateColors().get(s[1])));
            }
            return pn;
        }

        // ── Alarmlar ─────────────────────────────────────────────────────────────────────────────────────

        void alerts() {
            row("alerts", t("Alarmlar", "Alerts"), false);
            String all = "sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY) + ")";
            add(stat(t("Açık alarm (süzgeç)", "Open alerts (filtered)"), t("Takım, tür ve seviye süzgecine uyan açık alarmlar.",
                    "Open alerts matching the team, type and level filters."), guard(all, "alerts"), "short",
                    thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=alerthistory&view=open") : null).size(4, 5));
            // Açık alarm yoksa (ve alarm kaynağı okunduysa) 0 = "Yok" (yeşil) — "veri yok" yalnız kaynak okunamadığında.
            List<Object> noneOpen = List.of(valueMap(new Object[][]{{0, t("Yok", "None"), GREEN}}));
            String oldestSevere = guard("time() - min(" + owned(MonitoringMetricNames.ALERTS_OLDEST, AOL, "level=~\"CRITICAL|HIGH\"", FAMILY) + ")", "alerts");
            Panel severe = stat(t("En eski kritik / yüksek alarm", "Oldest critical / high alert"), t("En eski açık kritik ya da yüksek alarmın ne zamandır açık olduğu (yoksa \"Yok\").",
                    "How long the oldest open critical or high alert has been open (\"None\" when there is none)."), oldestSevere, "dtdurations",
                    thresholds(GREEN, null, AMBER, 1, RED, 1800), "background", links() ? url("tab=alerthistory&view=open&sort=opened_asc") : null).size(5, 5);
            severe.mappings(noneOpen);
            add(severe);
            String oldestAny = guard("time() - min(" + owned(MonitoringMetricNames.ALERTS_OLDEST, AOL, LEVEL, FAMILY) + ")", "alerts");
            Panel any = stat(t("En eski açık alarm", "Oldest open alert"), t("Süzgece uyan en eski açık alarmın yaşı (yoksa \"Yok\").",
                    "Age of the oldest open alert matching the filter (\"None\" when there is none)."),
                    oldestAny, "dtdurations", thresholds(GREEN, null, YELLOW, 1, AMBER, 86400), "value", null).size(5, 5);
            any.mappings(noneOpen);
            add(any);
            String acked = "sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY, "acknowledged=\"true\"") + ")";
            Panel ackRatio = stat(t("Sahiplenme oranı", "Acknowledged share"), t("Açık alarmlardan sahiplenilenlerin payı (alarm yoksa veri yok).",
                    "Share of open alerts that are acknowledged (no alerts = no data)."), "(" + acked + " or vector(0)) / (" + all + " > 0)",
                    "percentunit", thresholds(RED, null, AMBER, 0.5, GREEN, 0.9), "value", null).size(5, 5);
            add(ackRatio);
            String stormMembers = "sum(max by (team, team_id) (" + metric(MonitoringMetricNames.STORM_MEMBERS, TEAM) + "))";
            add(stat(t("Fırtınadaki alarm", "Alerts in storms"), t("Aktif fırtınalarda gruplanmış alarm sayısı.", "Alerts grouped into active storms."),
                    guard(stormMembers, "storms"), "short", thresholds(GREEN, null, AMBER, 1), "value", links() ? url("tab=storms") : null).size(5, 5));

            Panel byLevel = new Panel("timeseries", t("Açık alarmlar — seviyeye göre", "Open alerts by level"),
                    t("Zaman içinde açık alarm sayısı, seviyeye göre yığılmış.", "Open alerts over time, stacked by level."));
            byLevel.w = 12; byLevel.h = 8;
            String[][] lv = {{"CRITICAL", t("Kritik", "Critical"), RED}, {"HIGH", t("Yüksek", "High"), AMBER},
                    {"WARNING", t("Uyarı", "Warning"), YELLOW}, {"OTHER", t("Diğer", "Other"), GREY}};
            Map<String, String> colors = new LinkedHashMap<>();
            for (String[] l : lv) {
                byLevel.target("sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, "level=\"" + l[0] + "\"", FAMILY) + ")", l[1], false);
                colors.put(l[1], l[2]);
            }
            byLevel.timeseries("short", true, "bars");
            byLevel.fixedColors(colors);
            add(byLevel);

            Panel byFamily = new Panel("timeseries", t("Açık alarmlar — izleme türüne göre", "Open alerts by monitor type"),
                    t("Zaman içinde açık alarmlar, izleme türüne (alarm ailesi) göre.", "Open alerts over time by monitor family."));
            byFamily.w = 12; byFamily.h = 8;
            byFamily.target("sum by (family) (" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY) + ")", "{{family}}", false);
            byFamily.timeseries("short", true, "line");
            typeNames(byFamily);
            add(byFamily);

            // Takım tablosu
            Panel teams = new Panel("table", t("Takımlara göre açık alarmlar", "Open alerts per team"),
                    t("Her takımın açık alarmları (UG sahibi olduğu alarmlar da sayılır). Takım adı SiteMonitor alarm geçmişini o takımla açar.",
                            "Each team's open alerts (alerts it co-owns as UG are counted too). The team name opens SiteMonitor's alert history for it."));
            teams.w = 14; teams.h = 9;
            String ow = "family, alert_type, team, team_id, ug_team, ug_team_id, level, acknowledged";
            teams.targetTable("sum by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OPEN, ow, "sum", FAMILY) + ")", "A");
            teams.targetTable("sum by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OPEN, ow, "sum", FAMILY, "level=\"CRITICAL\"") + ")", "B");
            teams.targetTable("sum by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OPEN, ow, "sum", FAMILY, "level=\"HIGH\"") + ")", "C");
            teams.targetTable("sum by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OPEN, ow, "sum", FAMILY, "level=\"WARNING\"") + ")", "D");
            teams.targetTable("sum by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OPEN, ow, "sum", FAMILY, "acknowledged=\"false\"") + ")", "E");
            teams.targetTable("time() - min by (team, team_id) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OLDEST,
                    "family, team, team_id, ug_team, ug_team_id, level", "min", FAMILY) + ")", "F");
            teams.mergeTable();
            teams.override("team", "displayName", t("Takım", "Team"));
            if (links()) teams.override("team", "links", List.of(link(t("Alarm geçmişinde aç", "Open in alert history"),
                    url("tab=alerthistory&view=open&team=${__data.fields.team_id}"))));
            teams.override("team_id", "custom.hidden", true);
            teams.valueColumn("A", t("Açık", "Open"), "short", thresholds(GREEN, null, AMBER, 1), "color-text", null);
            teams.valueColumn("B", t("Kritik", "Critical"), "short", thresholds("transparent", null, RED, 1), "color-background", null);
            teams.valueColumn("C", t("Yüksek", "High"), "short", thresholds("transparent", null, AMBER, 1), "color-background", null);
            teams.valueColumn("D", t("Uyarı", "Warning"), "short", thresholds("transparent", null, YELLOW, 1), "color-background", null);
            teams.valueColumn("E", t("Sahiplenilmemiş", "Unacknowledged"), "short", thresholds(GREEN, null, AMBER, 1), "color-text", null);
            teams.valueColumn("F", t("En eski", "Oldest"), "dtdurations", thresholds("text", null), "auto", null);
            teams.sortBy(t("Kritik", "Critical"), true);
            add(teams);

            Panel oldestTeam = new Panel("bargauge", t("Takımların en eski kritik / yüksek alarmı", "Oldest critical / high alert per team"),
                    t("Her takımın en eski açık kritik ya da yüksek alarmının yaşı — uzun bekleyen takım üstte.",
                            "Age of each team's oldest open critical or high alert — the longest waiting team on top."));
            oldestTeam.w = 10; oldestTeam.h = 9;
            oldestTeam.targetInstant("sort_desc(time() - min by (team) (" + ownedPerTeam(MonitoringMetricNames.ALERTS_OLDEST,
                    "family, team, team_id, ug_team, ug_team_id, level", "min", FAMILY, "level=~\"CRITICAL|HIGH\"") + "))", "{{team}}");
            oldestTeam.bargauge("dtdurations", thresholds(AMBER, null, RED, 3600));
            add(oldestTeam);

            if (o.perMonitor()) {
                Panel list = new Panel("table", t("Açık alarm listesi", "Open alert list"),
                        t("Süzgece uyan her açık alarm: hedef, tür, seviye, sahip takımlar, açılış ve süre. Hedef SiteMonitor'da alarmı açar.",
                                "Every open alert matching the filter: target, type, level, owning teams, opened and age. The target opens the alert in SiteMonitor."));
                list.w = 24; list.h = 11;
                String a = "max by (" + APL + ") (" + metric(MonitoringMetricNames.ALERT_OPEN, TEAM, LEVEL, FAMILY) + ")";
                String b = "max by (" + APL + ") (" + metric(MonitoringMetricNames.ALERT_OPEN, UG, LEVEL, FAMILY) + ")";
                list.targetTable("(" + a + " or " + b + ") * 1000", "A");
                list.targetTable("time() - (" + a + " or " + b + ")", "B");
                list.mergeTable();
                list.organize(List.of("target", "alert_type", "family", "level", "team", "ug_team", "acknowledged", "Value #A", "Value #B"),
                        List.of("alert_id", "team_id", "ug_team_id"));
                list.override("target", "displayName", t("Hedef", "Target"));
                if (links()) list.override("target", "links", List.of(link(t("Alarmı SiteMonitor'da aç", "Open the alert in SiteMonitor"),
                        url("tab=alerthistory&alert=${__data.fields.alert_id}"))));
                list.override("alert_type", "displayName", t("Alarm tipi", "Alert type"));
                list.override("family", "displayName", t("Tür", "Type"));
                list.override("family", "mappings", typeMappings());
                list.override("level", "displayName", t("Seviye", "Level"));
                list.override("level", "mappings", List.of(valueMap(new Object[][]{{"CRITICAL", t("Kritik", "Critical"), RED},
                        {"HIGH", t("Yüksek", "High"), AMBER}, {"WARNING", t("Uyarı", "Warning"), YELLOW}, {"OTHER", t("Diğer", "Other"), GREY}})));
                list.override("level", "custom.cellOptions", om("type", "color-background", "mode", "basic"));
                list.override("team", "displayName", t("Takım", "Team"));
                list.override("ug_team", "displayName", t("UG takımı", "UG team"));
                list.override("acknowledged", "displayName", t("Sahiplenildi", "Acknowledged"));
                list.override("acknowledged", "mappings", List.of(valueMap(new Object[][]{{"true", t("Evet", "Yes"), GREEN},
                        {"false", t("Hayır", "No"), AMBER}})));
                list.override("acknowledged", "custom.cellOptions", om("type", "color-text"));
                list.valueColumn("A", t("Açıldı", "Opened"), "dateTimeAsLocal", null, "auto", null);
                list.valueColumn("B", t("Süre", "Age"), "dtdurations", thresholds("text", null, AMBER, 3600, RED, 86400), "color-text", null);
                list.sortBy(t("Süre", "Age"), true);
                add(list);
            }

            Panel ackTs = new Panel("timeseries", t("Sahiplenilen / sahiplenilmeyen alarmlar", "Acknowledged vs unacknowledged"),
                    t("Açık alarmlardan kaçının bir sorumlusu olduğu, zaman içinde.", "How many open alerts have an owner, over time."));
            ackTs.w = 12; ackTs.h = 7;
            ackTs.target("sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY, "acknowledged=\"true\"") + ")", t("Sahiplenildi", "Acknowledged"), false);
            ackTs.target("sum(" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY, "acknowledged=\"false\"") + ")", t("Sahiplenilmedi", "Unacknowledged"), false);
            ackTs.timeseries("short", true, "line");
            Map<String, String> ackColors = new LinkedHashMap<>();
            ackColors.put(t("Sahiplenildi", "Acknowledged"), GREEN);
            ackColors.put(t("Sahiplenilmedi", "Unacknowledged"), AMBER);
            ackTs.fixedColors(ackColors);
            add(ackTs);

            Panel byType = new Panel("bargauge", t("Alarm tiplerine göre (şu an)", "Open alerts by alert type (now)"),
                    t("Şu an açık alarmların tipe göre dağılımı.", "Open alerts right now by alert type."));
            byType.w = 12; byType.h = 7;
            byType.targetInstant("sort_desc(sum by (alert_type) (" + owned(MonitoringMetricNames.ALERTS_OPEN, AL, LEVEL, FAMILY) + "))", "{{alert_type}}");
            byType.bargauge("short", thresholds(AMBER, null));
            add(byType);

            Panel org = new Panel("bargauge", t("Kuruluş geneli açık alarmlar", "Open alerts across the organisation"),
                    t("Takım ve tür süzgecinden bağımsız, her alarm bir kez sayılarak seviyeye göre açık alarmlar.",
                            "Open alerts by level across all teams, each alert counted once, regardless of the filters."));
            org.w = 24; org.h = 5;
            String[][] lvls = {{"CRITICAL", t("Kritik", "Critical"), RED}, {"HIGH", t("Yüksek", "High"), AMBER},
                    {"WARNING", t("Uyarı", "Warning"), YELLOW}, {"OTHER", t("Diğer", "Other"), GREY}};
            Map<String, String> oc = new LinkedHashMap<>();
            for (String[] l : lvls) {
                org.targetInstant("max(" + metric(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL, "level=\"" + l[0] + "\"") + ")", l[1]);
                oc.put(l[1], l[2]);
            }
            org.bargauge("short", thresholds(BLUE, null));
            org.fixedColors(oc);
            org.optionsMap().put("orientation", "vertical");
            if (links()) org.panelLinks(List.of(link(t("Açık alarmları SiteMonitor'da aç", "Open alerts in SiteMonitor"),
                    url("tab=alerthistory&view=open"))));
            add(org);
        }

        // ── İzleme durumu ────────────────────────────────────────────────────────────────────────────────

        void monitors() {
            row("monitors", t("İzleme durumu", "Monitor state"), false);
            if (o.perMonitor()) {
                Panel table = new Panel("table", t("Tüm izlemeler", "All monitors"),
                        t("Süzgece uyan her izleme: durum, açık alarm, son yanıt, 24 sa ortalama ve başarı, seçili aralıktaki erişilebilirlik, son "
                                + "kontrolden beri geçen süre. İzleme adı SiteMonitor'da detayını açar.",
                                "Every monitor matching the filter: state, open alert, last response, 24 h average and success, availability "
                                        + "over the selected range, time since the last check. The monitor name opens it in SiteMonitor."));
                table.w = 24; table.h = 13;
                table.targetTable(mon(MonitoringMetricNames.MONITOR_STATE), "A");
                table.targetTable(mon(MonitoringMetricNames.MONITOR_ALERT_SEVERITY), "B");
                table.targetTable(mon(MonitoringMetricNames.MONITOR_RESPONSE_MS), "C");
                table.targetTable(mon(MonitoringMetricNames.MONITOR_AVG_RESPONSE_MS), "D");
                table.targetTable("1 - (" + mon(MonitoringMetricNames.MONITOR_FAILED) + " / (" + mon(MonitoringMetricNames.MONITOR_CHECKS) + " > 0))", "E");
                table.targetTable("avg by (" + ML + ") (avg_over_time(" + metric(MonitoringMetricNames.MONITOR_UP, TEAM, TYPE, MON) + "[$__range]))", "F");
                table.targetTable("time() - " + mon(MonitoringMetricNames.MONITOR_LAST_CHECK), "G");
                table.targetTable(mon(MonitoringMetricNames.MONITOR_OPEN_ALERTS) + " > 0", "H");
                table.targetTable("time() - " + mon(MonitoringMetricNames.MONITOR_OPEN_SINCE), "I");
                table.targetTable(mon(MonitoringMetricNames.MONITOR_INTERVAL), "J");
                table.mergeTable();
                table.organize(List.of("monitor", "type", "team", "group", "target", "Value #A", "Value #B", "Value #H", "Value #I", "Value #C",
                        "Value #D", "Value #E", "Value #F", "Value #G", "Value #J"), List.of("team_id", "monitor_id"));
                table.override("monitor", "displayName", t("İzleme", "Monitor"));
                if (links()) table.override("monitor", "links", List.of(link(t("İzlemeyi SiteMonitor'da aç", "Open the monitor in SiteMonitor"),
                        url("tab=${__data.fields.type}&monitor=${__data.fields.monitor_id}"))));
                table.override("type", "displayName", t("Tür", "Type"));
                table.override("type", "mappings", typeMappings());
                table.override("team", "displayName", t("Takım", "Team"));
                table.override("group", "displayName", t("Grup", "Group"));
                table.override("target", "displayName", t("Hedef", "Target"));
                table.valueColumn("A", t("Durum", "State"), "none", null, "color-background", stateMappings());
                table.valueColumn("B", t("Açık alarm", "Open alert"), "none", null, "color-text", severityMappings());
                table.valueColumn("C", t("Son yanıt", "Last response"), "ms", thresholds("text", null), "auto", null);
                table.valueColumn("D", t("24 sa ort.", "24 h avg"), "ms", thresholds("text", null), "auto", null);
                Panel e = table;
                e.valueColumn("E", t("24 sa başarı", "24 h success"), "percentunit", thresholds(RED, null, AMBER, 0.95, GREEN, 0.99), "color-text", null);
                e.valueColumn("F", t("Erişilebilirlik (aralık)", "Availability (range)"), "percentunit",
                        thresholds(RED, null, AMBER, 0.95, GREEN, 0.99), "color-text", null);
                e.valueColumn("G", t("Son kontrolden beri", "Since last check"), "dtdurations", thresholds("text", null), "auto", null);
                e.valueColumn("H", t("Alarm sayısı", "Alerts"), "short", thresholds("text", null, RED, 1), "color-text", null);
                e.valueColumn("I", t("Alarm süresi", "Alert age"), "dtdurations", thresholds(AMBER, null, RED, 3600), "color-text", null);
                e.valueColumn("J", t("Aralık", "Interval"), "s", thresholds("text", null), "auto", null);
                table.sortBy(t("Durum", "State"), true);
                add(table);

                Panel tl = new Panel("state-timeline", t("Sorun yaşayan izlemeler — durum zaman çizelgesi", "Monitors with problems — state timeline"),
                        t("Seçili aralıkta en az bir kez düşük ya da gecikmiş olan izlemelerin durumu, zamanla.",
                                "State over time of monitors that were down or stale at least once in the selected range."));
                tl.w = 24; tl.h = 10;
                // Süzgeç ("aralıkta en az bir kez gecikmiş / düşük") @ end() ile TEK kez değerlendirilir: adım başına yeniden
                // hesaplanan [$__range:$__interval] alt sorgusu gerçek filoda query.max-samples sınırını aşıyordu (2026-10-18).
                String problem = "max by (type, monitor_id) (max_over_time(((" + metric(MonitoringMetricNames.MONITOR_STATE, TEAM, TYPE, MON)
                        + " > 0) < 3)[$__range:$__interval] @ end()))";
                tl.target("max by (type, monitor, monitor_id) (" + metric(MonitoringMetricNames.MONITOR_STATE, TEAM, TYPE, MON)
                        + ") and on (type, monitor_id) " + problem, "{{monitor}} · {{type}}", false);
                Map<String, Object> to = new LinkedHashMap<>();
                to.put("alignValue", "left");
                to.put("legend", legend("list", "bottom", List.of()));
                to.put("mergeValues", true);
                to.put("rowHeight", 0.85);
                to.put("showValue", "never");
                to.put("tooltip", tooltip("single"));
                tl.options(to);
                tl.defaultsCustom(om("fillOpacity", 85, "lineWidth", 0));
                tl.colorMode("thresholds");
                tl.thresholds(thresholds(GREEN, null));
                tl.mappings(stateMappings());
                add(tl);

                Panel resp = new Panel("timeseries", t("Yanıt süresi — en yavaş $topn izleme", "Response time — slowest $topn monitors"),
                        t("Son kontrol yanıt süresi en yüksek izlemeler (İlk N değişkeni).", "Monitors with the highest last-check response time (Top N variable)."));
                resp.w = 12; resp.h = 9;
                resp.target("topk($topn, max by (type, monitor, monitor_id) (" + metric(MonitoringMetricNames.MONITOR_RESPONSE_MS, TEAM, TYPE, MON) + "))",
                        "{{monitor}} ({{type}})", false);
                resp.timeseries("ms", false, "line");
                if (links()) resp.fieldLinks(List.of(link(t("İzlemeyi SiteMonitor'da aç", "Open the monitor in SiteMonitor"),
                        url("tab=${__field.labels.type}&monitor=${__field.labels.monitor_id}"))));
                add(resp);

                Panel avail = new Panel("bargauge", t("En düşük erişilebilirlik (seçili aralık)", "Lowest availability (selected range)"),
                        t("Seçili zaman aralığında en düşük erişilebilirliğe sahip izlemeler (sağlıklı örneklerin payı).",
                                "Monitors with the lowest availability in the selected range (share of up samples)."));
                avail.w = 12; avail.h = 9;
                avail.targetInstant("bottomk($topn, avg by (type, monitor, monitor_id) (avg_over_time(" + metric(MonitoringMetricNames.MONITOR_UP, TEAM, TYPE, MON)
                        + "[$__range])))", "{{monitor}} ({{type}})");
                avail.bargauge("percentunit", thresholds(RED, null, AMBER, 0.95, GREEN, 0.99));
                avail.minMax(0, 1);
                if (links()) avail.fieldLinks(List.of(link(t("İzlemeyi SiteMonitor'da aç", "Open the monitor in SiteMonitor"),
                        url("tab=${__field.labels.type}&monitor=${__field.labels.monitor_id}"))));
                add(avail);

                Panel age = new Panel("bargauge", t("Kontrolü en uzun süredir koşmayanlar", "Longest time since last check"),
                        t("Etkin izlemeler arasında son kontrolünden bu yana en çok zaman geçenler — tarama gecikmesini gösterir.",
                                "Active monitors with the most time since their last check — shows sweep delays."));
                age.w = 12; age.h = 9;
                age.targetInstant("topk($topn, (time() - max by (type, monitor, monitor_id) (" + metric(MonitoringMetricNames.MONITOR_LAST_CHECK, TEAM, TYPE, MON)
                        + ")) and on (type, monitor_id) (max by (type, monitor_id) (" + metric(MonitoringMetricNames.MONITOR_STATE, TEAM, TYPE, MON) + ") < 3))",
                        "{{monitor}} ({{type}})");
                age.bargauge("dtdurations", thresholds(GREEN, null, AMBER, 1800, RED, 10800));
                add(age);

                Panel avgType = new Panel("timeseries", t("Ortalama yanıt süresi — türe göre", "Average response time by type"),
                        t("Her izleme türünün son kontrol yanıt sürelerinin ortalaması.", "Average last-check response time per monitor type."));
                avgType.w = 12; avgType.h = 9;
                avgType.target("avg by (type) (max by (type, monitor_id) (" + metric(MonitoringMetricNames.MONITOR_RESPONSE_MS, TEAM, TYPE, MON) + "))",
                        "{{type}}", false);
                avgType.timeseries("ms", false, "line");
                typeNames(avgType);
                add(avgType);
            }

            Panel states = new Panel("timeseries", t("Durum sayıları zaman içinde", "Monitor states over time"),
                    t("Süzgece uyan izlemelerin durumları, zaman içinde yığılmış.", "States of the filtered monitors over time, stacked."));
            states.w = o.perMonitor() ? 12 : 24; states.h = 8;
            Map<String, String> colors = stateColors();
            for (String[] s : stateSeries()) states.target("sum(" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"" + s[0] + "\"") + ")", s[1], false);
            states.timeseries("short", true, "line");
            states.fixedColors(colors);
            add(states);

            Panel success = new Panel("bargauge", t("Türe göre 24 sa başarı oranı", "24 h success rate by type"),
                    t("Her izleme türünde son 24 saatin başarılı kontrol payı.", "Share of passing checks per monitor type over the last 24 hours."));
            success.w = o.perMonitor() ? 12 : 12; success.h = 8;
            success.targetInstant("1 - (sum by (type) (" + agg(MonitoringMetricNames.MONITORS_FAILED, MC) + ") / (sum by (type) ("
                    + agg(MonitoringMetricNames.MONITORS_CHECKS, MC) + ") > 0))", "{{type}}");
            success.bargauge("percentunit", thresholds(RED, null, AMBER, 0.95, GREEN, 0.99));
            success.minMax(0, 1);
            typeNames(success);
            add(success);

            Panel downTeam = new Panel("timeseries", t("Düşük izlemeler — takıma göre", "Down monitors by team"),
                    t("Her takımın düşük izlemelerinin sayısı, zaman içinde.", "Number of down monitors per team over time."));
            downTeam.w = 12; downTeam.h = 8;
            downTeam.target("sum by (team) (" + agg(MonitoringMetricNames.MONITORS, MA, "state=\"down\"") + ") > 0", "{{team}}", false);
            downTeam.timeseries("short", false, "line");
            add(downTeam);

            Panel volume = new Panel("timeseries", t("24 sa kontrol hacmi — türe göre", "24 h check volume by type"),
                    t("Son 24 saatte koşan ve başarısız olan kontroller, türe göre (kayan pencere).",
                            "Checks run and failed in the last 24 hours by type (rolling window)."));
            volume.w = o.perMonitor() ? 24 : 12; volume.h = 8;
            volume.target("sum by (type) (" + agg(MonitoringMetricNames.MONITORS_CHECKS, MC) + ")", t("{{type}} kontrol", "{{type}} checks"), false);
            volume.target("sum by (type) (" + agg(MonitoringMetricNames.MONITORS_FAILED, MC) + ")", t("{{type}} başarısız", "{{type}} failed"), false);
            volume.timeseries("short", false, "line");
            add(volume);
        }

        // ── Sertifikalar ve alan adları ──────────────────────────────────────────────────────────────────

        void certificates() {
            row("certificates", t("Sertifikalar ve alan adları", "Certificates and domains"), true);
            String CA = "team, team_id, ug_team, ug_team_id, status";
            String CE = "team, team_id, ug_team, ug_team_id, bucket";
            String CG = "team, team_id, ug_team, ug_team_id, grade";
            add(stat(t("İzlenen sertifika", "Monitored certificates"), t("Aktif envanterdeki sertifikalar (SY ya da UG takımı süzgece uyan).",
                    "Certificates in the active inventory (SY or UG team matches the filter)."),
                    guard("sum(" + owned(MonitoringMetricNames.CERTS, CA) + ")", "certificates"), "short", thresholds(BLUE, null), "value",
                    links() ? url("tab=all") : null).size(4, 4));
            add(stat(t("Geçerli", "Valid"), t("Sorunsuz sertifikalar.", "Certificates with no issue."),
                    guard("sum(" + owned(MonitoringMetricNames.CERTS, CA, "status=\"valid\"") + ")", "certificates"), "short", thresholds(GREEN, null), "value", null).size(4, 4));
            add(stat(t("Uyarı / yüksek / kritik", "Warning / high / critical"), t("Bitiş eşiğine yaklaşan sertifikalar (katman eşikleriyle).",
                    "Certificates nearing their expiry thresholds (tier thresholds)."),
                    guard("sum(" + owned(MonitoringMetricNames.CERTS, CA, "status=~\"warning|high|critical\"") + ")", "certificates"), "short",
                    thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=warnings") : null).size(4, 4));
            add(stat(t("Kontrol hatası", "Check errors"), t("Son kontrolü hata veren sertifikalar (bağlantı / el sıkışma).",
                    "Certificates whose last check failed (connection / handshake)."),
                    guard("sum(" + owned(MonitoringMetricNames.CERTS, CA, "status=\"error\"") + ")", "certificates"), "short",
                    thresholds(GREEN, null, PURPLE, 1), "background", null).size(4, 4));
            add(stat(t("A / A+ notu", "A / A+ grade"), t("TLS yapılandırma notu A ya da A+ olan sertifikaların notlananlar içindeki payı.",
                    "Share of graded certificates with an A or A+ TLS configuration grade."),
                    "sum(" + owned(MonitoringMetricNames.CERTS_GRADE, CG, "grade=~\"A.*\"") + ") / (sum(" + owned(MonitoringMetricNames.CERTS_GRADE, CG, "grade!=\"none\"") + ") > 0)",
                    "percentunit", thresholds(RED, null, AMBER, 0.7, GREEN, 0.9), "value", null).size(4, 4));
            add(stat(t("Süresi dolmuş", "Expired"), t("Süresi dolmuş sertifikalar.", "Expired certificates."),
                    guard("sum(" + owned(MonitoringMetricNames.CERTS_EXPIRY, CE, "bucket=\"expired\"") + ")", "certificates"), "short",
                    thresholds(GREEN, null, DARK_RED, 1), "background", links() ? url("tab=all&c_st=expired") : null).size(4, 4));

            Panel status = new Panel("piechart", t("Sertifika durumu", "Certificate state"), t("Sertifikaların durumlara dağılımı.", "Certificates by state."));
            status.w = 8; status.h = 9;
            String[][] cs = {{"valid", t("Geçerli", "Valid"), GREEN}, {"warning", t("Uyarı", "Warning"), YELLOW}, {"high", t("Yüksek", "High"), AMBER},
                    {"critical", t("Kritik", "Critical"), RED}, {"expired", t("Süresi doldu", "Expired"), DARK_RED}, {"error", t("Kontrol hatası", "Check error"), PURPLE}};
            Map<String, String> cc = new LinkedHashMap<>();
            for (String[] c : cs) { status.target("sum(" + owned(MonitoringMetricNames.CERTS, CA, "status=\"" + c[0] + "\"") + ")", c[1], true); cc.put(c[1], c[2]); }
            status.options(pieOptions());
            status.fixedColors(cc);
            add(status);

            Panel grade = new Panel("barchart", t("TLS yapılandırma notları", "TLS configuration grades"),
                    t("Sertifika uç noktalarının TLS notu dağılımı (A+ en iyi, F en kötü; notlanmayan ayrı).",
                            "TLS grade distribution of certificate endpoints (A+ best, F worst; ungraded separate)."));
            grade.w = 8; grade.h = 9;
            grade.targetTable("sum by (grade) (" + owned(MonitoringMetricNames.CERTS_GRADE, CG) + ")", "A");
            Map<String, Object> gopt = new LinkedHashMap<>();
            gopt.put("barRadius", 0.1);
            gopt.put("barWidth", 0.8);
            gopt.put("groupWidth", 0.7);
            gopt.put("legend", legend("list", "bottom", List.of()));
            gopt.put("orientation", "vertical");
            gopt.put("showValue", "always");
            gopt.put("stacking", "none");
            gopt.put("tooltip", tooltip("single"));
            gopt.put("xField", "grade");
            grade.options(gopt);
            grade.defaultsCustom(om("fillOpacity", 85, "lineWidth", 0, "gradientMode", "none"));
            grade.override("Value", "displayName", t("Sertifika", "Certificates"));
            grade.override("Value", "color", om("mode", "fixed", "fixedColor", BLUE));
            grade.override("grade", "displayName", t("Not", "Grade"));
            grade.override("grade", "mappings", List.of(valueMap(new Object[][]{{"none", t("Notlanmadı", "Not graded"), GREY}})));
            add(grade);

            Panel buckets = new Panel("timeseries", t("Bitişe kalan süre kovaları", "Expiry buckets over time"),
                    t("Sertifikaların bitişe kalan güne göre kovaları, zaman içinde.", "Certificates by days-to-expiry bucket, over time."));
            buckets.w = 8; buckets.h = 9;
            String[][] eb = {{"expired", t("Doldu", "Expired"), DARK_RED}, {"d7", t("≤ 7 gün", "≤ 7 days"), RED}, {"d30", t("8–30 gün", "8–30 days"), AMBER},
                    {"d90", t("31–90 gün", "31–90 days"), YELLOW}, {"later", t("> 90 gün", "> 90 days"), GREEN}};
            Map<String, String> ec = new LinkedHashMap<>();
            for (String[] e : eb) { buckets.target("sum(" + owned(MonitoringMetricNames.CERTS_EXPIRY, CE, "bucket=\"" + e[0] + "\"") + ")", e[1], false); ec.put(e[1], e[2]); }
            buckets.timeseries("short", true, "line");
            buckets.fixedColors(ec);
            add(buckets);

            if (o.perMonitor()) {
                Panel soon = new Panel("bargauge", t("En yakında dolacak $topn sertifika", "The $topn certificates expiring soonest"),
                        t("Kalan günü en az sertifikalar. Satır SiteMonitor'da sertifika penceresini açar.",
                                "Certificates with the fewest days left. A row opens the certificate window in SiteMonitor."));
                soon.w = 10; soon.h = 10;
                soon.targetInstant("bottomk($topn, min by (domain) (" + owned(MonitoringMetricNames.CERT_DAYS, CL) + "))", "{{domain}}");
                soon.bargauge(daysUnit(), thresholds(RED, null, AMBER, 8, YELLOW, 31, GREEN, 91));
                if (links()) soon.fieldLinks(List.of(link(t("Sertifikayı SiteMonitor'da aç", "Open the certificate in SiteMonitor"),
                        url("tab=dashboard&domain=${__field.labels.domain}&open=cert"))));
                add(soon);

                Panel table = new Panel("table", t("30 gün içinde dolan sertifikalar", "Certificates expiring within 30 days"),
                        t("Bitişine ≤ 30 gün kalan (ya da dolmuş) sertifikalar: durum, TLS notu, katman, sahip takımlar. Alan adı SiteMonitor'da açar.",
                                "Certificates with ≤ 30 days left (or expired): state, TLS grade, tier, owning teams. The domain opens it in SiteMonitor."));
                table.w = 14; table.h = 10;
                String days = owned(MonitoringMetricNames.CERT_DAYS, CL);
                table.targetTable("(" + days + ") <= 30", "A");
                table.targetTable(owned(MonitoringMetricNames.CERT_STATUS, CL) + " and on (domain) ((" + days + ") <= 30)", "B");
                table.targetTable(owned(MonitoringMetricNames.CERT_TLS_GRADE, CL) + " and on (domain) ((" + days + ") <= 30)", "C");
                table.targetTable("(time() - " + owned(MonitoringMetricNames.CERT_LAST_CHECK, CL) + ") and on (domain) ((" + days + ") <= 30)", "D");
                table.mergeTable();
                table.organize(List.of("domain", "Value #A", "Value #B", "Value #C", "Value #D", "team", "ug_team", "tier", "group", "source"),
                        List.of("team_id", "ug_team_id"));
                table.override("domain", "displayName", t("Alan adı", "Domain"));
                if (links()) table.override("domain", "links", List.of(link(t("Sertifikayı SiteMonitor'da aç", "Open the certificate in SiteMonitor"),
                        url("tab=dashboard&domain=${__data.fields.domain}&open=cert"))));
                table.override("team", "displayName", t("SY takımı", "SY team"));
                table.override("ug_team", "displayName", t("UG takımı", "UG team"));
                table.override("tier", "displayName", t("Katman", "Tier"));
                table.override("group", "displayName", t("Grup", "Group"));
                table.override("source", "displayName", t("Kaynak", "Source"));
                table.override("source", "mappings", List.of(valueMap(new Object[][]{{"network", t("Ağ", "Network"), "text"},
                        {"manual", t("Dosya", "Uploaded"), "text"}})));
                table.valueColumn("A", t("Kalan gün", "Days left"), daysUnit(), thresholds(DARK_RED, null, RED, 0, AMBER, 8), "color-background", null);
                table.valueColumn("B", t("Durum", "State"), "none", null, "color-text", certMappings());
                table.valueColumn("C", t("TLS notu", "TLS grade"), "none", null, "color-text", gradeMappings());
                table.valueColumn("D", t("Son kontrolden beri", "Since last check"), "dtdurations",
                        thresholds("text", null, AMBER, 172800), "color-text", null);
                table.sortBy(t("Kalan gün", "Days left"), false);
                add(table);

                Panel dom = new Panel("bargauge", t("Kaydı en yakında bitecek $topn alan adı", "The $topn domain registrations expiring soonest"),
                        t("Alan adı kaydı izlemelerinden bitişe en az gün kalanlar.", "Domain registration monitors with the fewest days left."));
                dom.w = 10; dom.h = 9;
                dom.targetInstant("bottomk($topn, min by (domain, monitor_id) (" + metric(MonitoringMetricNames.DOMAIN_DAYS, TEAM) + "))", "{{domain}}");
                dom.bargauge(daysUnit(), thresholds(RED, null, AMBER, 8, YELLOW, 31, GREEN, 91));
                if (links()) dom.fieldLinks(List.of(link(t("Alan adını SiteMonitor'da aç", "Open the domain in SiteMonitor"),
                        url("tab=domain&monitor=${__field.labels.monitor_id}"))));
                add(dom);

                Panel domTable = new Panel("table", t("60 gün içinde bitecek alan adı kayıtları", "Domain registrations expiring within 60 days"),
                        t("Kaydı 60 gün içinde bitecek alan adları ve sahip takımları.", "Domain registrations ending within 60 days and their teams."));
                domTable.w = 14; domTable.h = 9;
                domTable.targetTable("max by (" + DL + ") (" + metric(MonitoringMetricNames.DOMAIN_DAYS, TEAM) + ") <= 60", "A");
                domTable.mergeTable();
                domTable.organize(List.of("domain", "monitor", "Value", "team", "group"), List.of("monitor_id", "team_id"));
                domTable.override("domain", "displayName", t("Alan adı", "Domain"));
                if (links()) domTable.override("domain", "links", List.of(link(t("Alan adını SiteMonitor'da aç", "Open the domain in SiteMonitor"),
                        url("tab=domain&monitor=${__data.fields.monitor_id}"))));
                domTable.override("monitor", "displayName", t("İzleme", "Monitor"));
                domTable.override("team", "displayName", t("Takım", "Team"));
                domTable.override("group", "displayName", t("Grup", "Group"));
                domTable.override("Value", "displayName", t("Kalan gün", "Days left"));
                domTable.override("Value", "unit", daysUnit());
                domTable.override("Value", "thresholds", thresholds(DARK_RED, null, RED, 0, AMBER, 8, YELLOW, 31));
                domTable.override("Value", "custom.cellOptions", om("type", "color-background", "mode", "basic"));
                domTable.sortBy(t("Kalan gün", "Days left"), false);
                add(domTable);
            }

            Panel domBuckets = new Panel("timeseries", t("Alan adı kaydı kovaları", "Domain registration buckets"),
                    t("Alan adı kayıtlarının bitişe kalan güne göre kovaları, zaman içinde.", "Domain registrations by days-to-expiry bucket, over time."));
            domBuckets.w = 24; domBuckets.h = 8;
            Map<String, String> dc = new LinkedHashMap<>();
            for (String[] e : eb) {
                domBuckets.target("sum(max by (team, team_id, bucket) (" + metric(MonitoringMetricNames.DOMAINS_EXPIRY, TEAM, "bucket=\"" + e[0] + "\"") + "))", e[1], false);
                dc.put(e[1], e[2]);
            }
            domBuckets.timeseries("short", true, "line");
            domBuckets.fixedColors(dc);
            add(domBuckets);
        }

        // ── Bildirimler ve fırtınalar ────────────────────────────────────────────────────────────────────

        void notifications() {
            row("notifications", t("Bildirimler ve fırtınalar", "Notifications and storms"), true);
            String N = "channel, trigger, status";
            add(stat(t("E-posta gönderildi (24 sa)", "E-mails sent (24 h)"), t("Son 24 saatte gönderilen bildirim e-postaları.",
                    "Notification e-mails sent in the last 24 hours."), guard("sum(max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS,
                    "channel=\"email\"", "status=\"sent\"") + "))", "notifications"), "short", thresholds(GREEN, null), "value", null).size(4, 4));
            add(stat(t("E-posta başarısız (24 sa)", "E-mails failed (24 h)"), t("Son 24 saatte gönderilemeyen bildirim e-postaları (SMTP).",
                    "Notification e-mails that failed in the last 24 hours (SMTP)."), guard("sum(max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS,
                    "channel=\"email\"", "status=\"failed\"") + "))", "notifications"), "short", thresholds(GREEN, null, RED, 1), "background", null).size(4, 4));
            add(stat(t("Webhook başarısız (24 sa)", "Webhooks failed (24 h)"), t("Son 24 saatte gönderilemeyen Teams / Slack bildirimleri.",
                    "Teams / Slack notifications that failed in the last 24 hours."), guard("sum(max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS,
                    "channel=\"webhook\"", "status=\"failed\"") + "))", "notifications"), "short", thresholds(GREEN, null, RED, 1), "background", null).size(4, 4));
            add(stat(t("Push başarısız (24 sa)", "Push failed (24 h)"), t("Son 24 saatte teslim edilemeyen kişisel push bildirimleri.",
                    "Personal push notifications not delivered in the last 24 hours."), guard("sum(max by (status) (" + metric(MonitoringMetricNames.PUSH_DELIVERIES,
                    "status=\"failed\"") + "))", "push"), "short", thresholds(GREEN, null, RED, 1), "background", null).size(4, 4));
            add(stat(t("Push kuyruğu", "Push queue"), t("Gönderilmeyi bekleyen push satırları; ikinci değer 10 dk'dan fazla gecikmiş olanlar.",
                    "Push rows waiting to be sent; the second value counts those more than 10 minutes overdue."),
                    "max(" + metric(MonitoringMetricNames.PUSH_PENDING) + ")", "short", thresholds(GREEN, null, AMBER, 50), "value", null)
                    .extraTarget("max(" + metric(MonitoringMetricNames.PUSH_STUCK) + ")", t("Takılı", "Stuck")).size(4, 4));
            Panel circuit = stat(t("Push devre kesici", "Push circuit breaker"), t("Push ağ geçidi art arda hata verince devre kesici açılır (pod başına).",
                    "The circuit breaker opens when the push gateway keeps failing (per pod)."),
                    "max(" + metric(MonitoringMetricNames.PUSH_CIRCUIT) + ")", "none", thresholds(GREEN, null, RED, 1), "background", null).size(4, 4);
            circuit.mappings(List.of(valueMap(new Object[][]{{0, t("Kapalı (normal)", "Closed (normal)"), GREEN}, {1, t("Açık", "Open"), RED}})));
            add(circuit);
            Panel pushOn = stat(t("Push kanalı", "Push channel"), t("Kişisel push bildirim kanalı açık mı (Ayarlar → Push).",
                    "Whether the personal push notification channel is on (Settings → Push)."),
                    "max(" + metric(MonitoringMetricNames.PUSH_ENABLED) + ")", "none", thresholds(GREY, null, GREEN, 1), "value", null).size(4, 4);
            pushOn.mappings(List.of(valueMap(new Object[][]{{0, t("Kapalı", "Off"), GREY}, {1, t("Açık", "On"), GREEN}})));
            add(pushOn);

            Panel email = new Panel("timeseries", t("E-posta bildirimleri (son 24 sa, kayan)", "E-mail notifications (last 24 h, rolling)"),
                    t("Son 24 saatteki e-posta kararları durumlarına göre (gönderildi, başarısız, atlandı, kuyrukta).",
                            "E-mail decisions in the last 24 hours by status (sent, failed, skipped, queued)."));
            email.w = 12; email.h = 8;
            String[][] st = {{"sent", t("Gönderildi", "Sent"), GREEN}, {"failed", t("Başarısız", "Failed"), RED},
                    {"skipped", t("Atlandı", "Skipped"), GREY}, {"queued", t("Kuyrukta", "Queued"), BLUE}};
            Map<String, String> ecol = new LinkedHashMap<>();
            for (String[] s : st) {
                email.target("sum(max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS, "channel=\"email\"", "status=\"" + s[0] + "\"") + "))", s[1], false);
                ecol.put(s[1], s[2]);
            }
            email.timeseries("short", false, "line");
            email.fixedColors(ecol);
            add(email);

            Panel triggers = new Panel("bargauge", t("Tetiğe göre bildirimler (24 sa)", "Notifications by trigger (24 h)"),
                    t("Son 24 saatte hangi tetiğin (açılış, yeniden uyarı, çözüm, fırtına…) kaç e-posta kararı ürettiği.",
                            "How many e-mail decisions each trigger (opening, re-alert, resolution, storm…) produced in the last 24 hours."));
            triggers.w = 12; triggers.h = 8;
            triggers.targetInstant("sort_desc(sum by (trigger) (max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS, "channel=\"email\"") + ")))", "{{trigger}}");
            triggers.bargauge("short", thresholds(BLUE, null));
            add(triggers);

            Panel failures = new Panel("table", t("Başarısız bildirimler — tetik ve kanal", "Failed notifications by trigger and channel"),
                    t("Son 24 saatte başarısız olan bildirimlerin tetik ve kanala göre sayısı.", "Failed notifications in the last 24 hours by trigger and channel."));
            failures.w = 12; failures.h = 8;
            failures.targetTable("sum by (channel, trigger) (max by (" + N + ") (" + metric(MonitoringMetricNames.NOTIFICATIONS, "status=\"failed\"") + ")) > 0", "A");
            failures.mergeTable();
            failures.override("channel", "displayName", t("Kanal", "Channel"));
            failures.override("trigger", "displayName", t("Tetik", "Trigger"));
            failures.override("Value", "displayName", t("Başarısız", "Failed"));
            failures.override("Value", "custom.cellOptions", om("type", "color-text"));
            failures.override("Value", "color", om("mode", "fixed", "fixedColor", RED));
            failures.sortBy(t("Başarısız", "Failed"), true);
            add(failures);

            Panel push = new Panel("timeseries", t("Push teslimleri (son 24 sa, kayan)", "Push deliveries (last 24 h, rolling)"),
                    t("Kişisel push teslimlerinin durumları.", "States of personal push deliveries."));
            push.w = 12; push.h = 8;
            String[][] ps = {{"sent", t("Gönderildi", "Sent"), GREEN}, {"failed", t("Başarısız", "Failed"), RED}, {"pending", t("Bekliyor", "Pending"), BLUE},
                    {"skipped", t("Atlandı", "Skipped"), GREY}, {"rate_limited", t("Sınıra takıldı", "Rate limited"), AMBER}};
            Map<String, String> pcol = new LinkedHashMap<>();
            for (String[] s : ps) {
                push.target("max(" + metric(MonitoringMetricNames.PUSH_DELIVERIES, "status=\"" + s[0] + "\"") + ")", s[1], false);
                pcol.put(s[1], s[2]);
            }
            push.timeseries("short", false, "line");
            push.fixedColors(pcol);
            add(push);

            Panel storms = new Panel("timeseries", t("Aktif fırtınalar ve üyeleri — takıma göre", "Active storms and members by team"),
                    t("Her takımın aktif fırtınaları ve fırtınalarda gruplanan alarm sayısı.", "Each team's active storms and the alerts grouped in them."));
            storms.w = 12; storms.h = 8;
            storms.target("sum by (team) (max by (team, team_id) (" + metric(MonitoringMetricNames.STORMS_ACTIVE, TEAM) + "))", t("{{team}} fırtına", "{{team}} storms"), false);
            storms.target("sum by (team) (max by (team, team_id) (" + metric(MonitoringMetricNames.STORM_MEMBERS, TEAM) + "))", t("{{team}} üye alarm", "{{team}} member alerts"), false);
            storms.timeseries("short", false, "line");
            if (links()) storms.panelLinks(List.of(link(t("Fırtınaları SiteMonitor'da aç", "Open storms in SiteMonitor"), url("tab=storms"))));
            add(storms);

            Panel stormsAll = stat(t("Aktif fırtına (tüm takımlar)", "Active storms (all teams)"), t("Takım süzgecinden bağımsız, kuruluş genelinde aktif fırtınalar.",
                    "Active storms across the organisation, regardless of the team filter."), "max(" + metric(MonitoringMetricNames.STORMS_ACTIVE_ALL) + ")",
                    "short", thresholds(GREEN, null, AMBER, 1), "background", links() ? url("tab=storms") : null).size(12, 4);
            add(stormsAll);
        }

        // ── SiteMonitor sağlığı ──────────────────────────────────────────────────────────────────────────

        void health() {
            row("health", t("SiteMonitor sağlığı", "SiteMonitor health"), true);
            String upq = "sum(up" + sel(RT) + ")";
            Panel pods = stat(t("Ayakta pod", "Pods up"), t("Kazımaya yanıt veren SiteMonitor pod'ları / bilinen pod'lar (son 1 sa build_info yayan).",
                    "SiteMonitor pods answering the scrape / known pods (exported build_info in the last hour)."), upq, "short",
                    thresholds(RED, null, GREEN, 1), "background", links() ? url("tab=health") : null).size(4, 4);
            pods.extraTarget("count(max by (instance) (last_over_time(sitemonitor_build_info" + sel(RT) + "[1h])))", t("Bilinen", "Known"));
            add(pods);
            Panel ver = stat(t("Sürüm", "Version"), t("Koşan sürüm(ler) — birden fazlaysa dağıtım sürüyordur.", "Running version(s) — more than one means a rollout is in progress."),
                    "count by (version) (sitemonitor_build_info" + sel(RT) + ")", "none", thresholds(BLUE, null), "value", null).size(4, 4);
            ver.legendOf(0, "{{version}}");
            ver.textMode("name");
            add(ver);
            add(stat(t("En yeni başlatma", "Latest start"), t("En son başlatılan pod'un ne zamandır ayakta olduğu.", "How long the most recently started pod has been up."),
                    "time() - max(sitemonitor_deployment_started_seconds" + sel(RT) + ")", "dtdurations", thresholds(AMBER, null, GREEN, 600), "value", null).size(4, 4));
            Panel leader = stat(t("Tarama lideri", "Sweep leader"), t("Tarama liderliğini tutan pod sayısı: 1 olmalı (0 = taramalar koşmuyor olabilir).",
                    "Pods holding the sweep leadership: should be 1 (0 = sweeps may not be running)."),
                    "sum(" + metric(MonitoringMetricNames.SWEEP_LEADER, RT) + ")", "short", thresholds(RED, null, GREEN, 1, AMBER, 2), "background", null).size(4, 4);
            add(leader);
            Panel db = stat(t("Veritabanı", "Database"), t("Pod'ların gördüğü en kötü veritabanı sağlığı.", "Worst database health seen by the pods."),
                    "max(" + metric(MonitoringMetricNames.DATABASE_STATUS, RT) + ")", "none", thresholds(GREEN, null, AMBER, 1, RED, 2), "background", null).size(4, 4);
            db.mappings(List.of(valueMap(new Object[][]{{0, t("Sağlıklı", "Up"), GREEN}, {1, t("Yavaş / dolu", "Degraded"), AMBER}, {2, t("Erişilemiyor", "Down"), RED}})));
            add(db);
            Panel maint = stat(t("Sistem bakımı", "System maintenance"), t("SiteMonitor'ün kendi bakım penceresi (duyuru / uyarı / etkin).",
                    "SiteMonitor's own maintenance window (announced / warning / active)."),
                    "max(" + metric(MonitoringMetricNames.SYSTEM_MAINTENANCE) + ")", "none", thresholds(GREEN, null, BLUE, 1, AMBER, 2, RED, 3, GREEN, 4), "background", null).size(4, 4);
            maint.mappings(List.of(valueMap(new Object[][]{{0, t("Yok", "None"), GREEN}, {1, t("Duyuruldu", "Announced"), BLUE},
                    {2, t("Yaklaşıyor", "Starting soon"), AMBER}, {3, t("Bakımda", "In maintenance"), RED}, {4, t("Az önce bitti", "Just ended"), GREEN}})));
            add(maint);

            Panel checks = new Panel("state-timeline", t("Kendi kendini izleme kontrolleri", "Self-monitoring checks"),
                    t("Tarama liderliği, izleme ve sertifika taramaları, bildirim, push kuyruğu, veritabanı ve saat uyumu kontrollerinin durumu, zamanla.",
                            "State of the sweep leadership, monitor and certificate sweeps, notification, push queue, database and clock alignment checks over time."));
            checks.w = 16; checks.h = 8;
            checks.target("max by (check) (" + metric(MonitoringMetricNames.WATCHDOG_CHECK) + ")", "{{check}}", false);
            Map<String, Object> to = new LinkedHashMap<>();
            to.put("alignValue", "left");
            to.put("legend", legend("list", "bottom", List.of()));
            to.put("mergeValues", true);
            to.put("rowHeight", 0.8);
            to.put("showValue", "never");
            to.put("tooltip", tooltip("single"));
            checks.options(to);
            checks.defaultsCustom(om("fillOpacity", 85, "lineWidth", 0));
            checks.colorMode("thresholds");
            checks.thresholds(thresholds(GREEN, null));
            checks.mappings(checkMappings());
            if (links()) checks.panelLinks(List.of(link(t("Sistem sağlığını SiteMonitor'da aç", "Open system health in SiteMonitor"), url("tab=health"))));
            add(checks);
            add(stat(t("Son değerlendirme", "Last evaluation"), t("Kendi kendini izlemenin son değerlendirmesinden bu yana geçen süre (5 dk'yı geçmemeli).",
                    "Time since the last self-monitoring evaluation (should stay under 5 minutes)."),
                    "time() - max(" + metric(MonitoringMetricNames.WATCHDOG_EVALUATED) + ")", "dtdurations",
                    thresholds(GREEN, null, AMBER, 300, RED, 900), "background", null).size(8, 4));
            add(stat(t("Şema yaması hatası", "Schema patch failures"), t("Son açılışta başarısız olan şema yamaları (0 olmalı).",
                    "Schema patches that failed at the last start (should be 0)."),
                    "max(sitemonitor_schema_patch_failed" + sel(RT) + ")", "short", thresholds(GREEN, null, RED, 1), "background", null).size(8, 4));

            Panel lease = new Panel("timeseries", t("Tarama liderliği kirası", "Sweep leader lease"),
                    t("Lider kiranın bitmesine kalan süre (negatif = kimse yenilemiyor) ve lider pod sayısı.",
                            "Time left on the leader lease (negative = nobody renews it) and the number of leader pods."));
            lease.w = 12; lease.h = 7;
            lease.target("max(" + metric(MonitoringMetricNames.SWEEP_LEASE) + ")", t("Kalan süre (sn)", "Seconds left"), false);
            lease.target("sum(" + metric(MonitoringMetricNames.SWEEP_LEADER, RT) + ")", t("Lider pod", "Leader pods"), false);
            lease.timeseries("short", false, "line");
            add(lease);

            Panel locks = new Panel("table", t("Tutulan zamanlayıcı kilitleri", "Held scheduler locks"),
                    t("Şu an tutulan iş kilitleri (tek pod'da koşan işler). Uzun süre tutulan kilit takılmış bir işi gösterebilir.",
                            "Job locks held right now (jobs that run on one pod). A lock held for long may point to a stuck job."));
            locks.w = 12; locks.h = 7;
            locks.targetTable("max by (name) (" + metric(MonitoringMetricNames.SCHEDULER_LOCK) + ")", "A");
            locks.mergeTable();
            locks.override("name", "displayName", t("Kilit", "Lock"));
            locks.override("Value", "displayName", t("Tutulan", "Held"));
            add(locks);

            add(stat(t("Saklama işi", "Retention job"), t("Veri saklama (retention) işinin son başarılı koşusundan bu yana geçen süre.",
                    "Time since the retention job last succeeded."), "time() - max(retention_last_success_epoch" + sel(RT) + ")", "dtdurations",
                    thresholds(GREEN, null, AMBER, 129600, RED, 259200), "value", null).size(6, 4));
            add(stat(t("Saklama hatası", "Retention failures"), t("Son saklama koşusunda başarısız olan politikalar.", "Policies that failed in the last retention run."),
                    "max(retention_failed_policies" + sel(RT) + ")", "short", thresholds(GREEN, null, RED, 1), "background", null).size(6, 4));
            add(stat(t("Aktarıcı tazeliği", "Exporter freshness"), t("Bu panoyu besleyen ölçüm aktarıcısının son turundan bu yana geçen süre (her pod).",
                    "Time since the last refresh of the metrics exporter that feeds this dashboard (per pod)."),
                    "time() - min(" + metric(MonitoringMetricNames.EXPORTER_LAST_REFRESH, RT) + ")", "dtdurations",
                    thresholds(GREEN, null, AMBER, 180, RED, 600), "background", null).size(6, 4));
            add(stat(t("Okunamayan kaynak", "Unreadable sources"), t("Aktarıcının son turda en az bir pod'da okuyamadığı kaynaklar — kaynak başına bir kez sayılır (o kaynağın panelleri \"veri yok\" gösterebilir).",
                    "Sources the exporter could not read in its last refresh on at least one pod — each source counted once (their panels may show \"no data\")."),
                    guard("count(min by (source) (" + metric(MonitoringMetricNames.EXPORTER_SOURCE, RT) + ") == 0)", "overview"), "short",
                    thresholds(GREEN, null, RED, 1), "background", null).size(6, 4));

            Panel growth = new Panel("timeseries", t("En büyük tablolar", "Largest tables"),
                    t("Veritabanının en büyük tabloları (tablo + dizin + toast).", "The largest database tables (table + index + toast)."));
            growth.w = 12; growth.h = 8;
            growth.target("topk(8, max by (table) (db_table_bytes" + sel(RT) + "))", "{{table}}", false);
            growth.timeseries("bytes", false, "line");
            add(growth);

            Panel exp = new Panel("timeseries", t("Ölçüm aktarıcısı", "Metrics exporter"),
                    t("Aktarıcının tur süresi ve yayılan seri sayısı (pod başına).", "Exporter refresh duration and exported series (per pod)."));
            exp.w = 12; exp.h = 8;
            exp.target("max by (instance) (" + metric(MonitoringMetricNames.EXPORTER_DURATION, RT) + ")", t("{{instance}} tur süresi (sn)", "{{instance}} refresh (s)"), false);
            exp.target("sum by (instance) (" + metric(MonitoringMetricNames.EXPORTER_SERIES, RT) + ")", t("{{instance}} seri", "{{instance}} series"), false);
            exp.target("max by (instance, family) (" + metric(MonitoringMetricNames.EXPORTER_CAPPED, RT) + ") > 0", t("{{instance}} tavan: {{family}}", "{{instance}} capped: {{family}}"), false);
            exp.target("max by (instance) (" + metric(MonitoringMetricNames.EXPORTER_ENABLED, RT) + ") == 0", t("{{instance}} kapalı", "{{instance}} disabled"), false);
            exp.target("max by (instance) (" + metric(MonitoringMetricNames.EXPORTER_PER_MONITOR, RT) + ") == 0",
                    t("{{instance}} izleme başına seri kapalı", "{{instance}} per-monitor series off"), false);
            exp.timeseries("short", false, "line");
            add(exp);

            clusterRuntime();
        }

        /**
         * Çok pod çalışma ölçümleri (2026-10-23, R2b): elle kontrol gelen kutusu (küme), saat farkı, kaybedilen kilit, liderlik
         * geçişleri ve push kira tazeleme hataları (pod başına, {@code instance}). Birikimli pod sayaçları {@code increase()}
         * ile okunur — pod yeniden başlayınca sıfırdan sayar, Prometheus bunu sayaç sıfırlaması gibi işler.
         */
        void clusterRuntime() {
            add(stat(t("Elle kontrol gelen kutusu", "Manual check inbox"), t("Takipçi pod'dan lidere iletilip henüz değerlendirilmemiş elle kontrol sonuçları (küme). Lider 5 sn'de bir boşaltır; birikiyorsa lider boşaltmıyordur.",
                            "Manual check results forwarded from a follower pod and not yet evaluated by the leader (cluster). The leader drains every 5 s; a growing number means it does not."),
                    "max(" + metric(MonitoringMetricNames.EVAL_INBOX_DEPTH) + ")", "short", thresholds(GREEN, null, AMBER, 10, RED, 50), "background", null).size(6, 4));
            add(stat(t("En büyük saat farkı", "Largest clock difference"), t("Pod saati ile veritabanı saati arasındaki en büyük fark (mutlak). 5 sn üstü uyarı, 30 sn üstü liderlik kirası ve kilitler için risk.",
                            "Largest difference between a pod clock and the database clock (absolute). Above 5 s is a warning, above 30 s risks the leader lease and job locks."),
                    "max(abs(" + metric(MonitoringMetricNames.CLOCK_SKEW, RT) + "))", "s", thresholds(GREEN, null, AMBER, 5, RED, 30), "background", null).size(6, 4));
            add(stat(t("Kaybedilen kilit (24 sa)", "Lost locks (24 h)"), t("Son 24 saatte pod'ların yenileme sırasında kaybettiği iş kilitleri (başka pod aldı ya da zorla bırakıldı). 0 olmalı.",
                            "Job locks the pods lost during renewal in the last 24 hours (another pod took them or they were force-released). Should be 0."),
                    "sum(increase(" + metric(MonitoringMetricNames.CLUSTER_LOCK_LOST, RT) + "[24h]))", "short", thresholds(GREEN, null, AMBER, 1, RED, 5), "background", null).size(6, 4));
            add(stat(t("Push kira hatası (24 sa)", "Push lease failures (24 h)"), t("Son 24 saatte push kuyruğu kira tazelemesinin düştüğü ya da satırların başka pod'a geçtiği batch'ler. 0 olmalı.",
                            "Push outbox batches in the last 24 hours whose lease renewal failed or whose rows had moved to another pod. Should be 0."),
                    "sum(increase(" + metric(MonitoringMetricNames.PUSH_LEASE_RENEW_FAILED, RT) + "[24h]))", "short", thresholds(GREEN, null, AMBER, 1, RED, 10), "background", null).size(6, 4));

            Panel skew = new Panel("timeseries", t("Saat farkı (pod başına)", "Clock difference (per pod)"),
                    t("Her pod'un saati eksi veritabanı saati (pozitif = pod ileride). Kira ve kilit karşılaştırmaları pod saatiyle yapılır.",
                            "Each pod's clock minus the database clock (positive = pod ahead). Lease and lock comparisons use the pod clock."));
            skew.w = 12; skew.h = 8;
            skew.target("max by (instance) (" + metric(MonitoringMetricNames.CLOCK_SKEW, RT) + ")", "{{instance}}", false);
            skew.timeseries("s", false, "line");
            add(skew);

            Panel events = new Panel("timeseries", t("Küme olayları (pod başına, saatlik)", "Cluster events (per pod, hourly)"),
                    t("Son bir saatte kaybedilen kilit, tarama liderliği geçişleri (GAINED / LOST / RELEASED) ve push kira hataları. Sık liderlik geçişi kira yenilemesinin aksadığını gösterir.",
                            "Lost locks, sweep leadership transitions (GAINED / LOST / RELEASED) and push lease failures in the last hour. Frequent leadership changes mean lease renewal is struggling."));
            events.w = 12; events.h = 8;
            events.target("sum by (instance) (increase(" + metric(MonitoringMetricNames.CLUSTER_LOCK_LOST, RT) + "[1h]))",
                    t("{{instance}} kaybedilen kilit", "{{instance}} lost locks"), false);
            events.target("sum by (instance, reason) (increase(" + metric(MonitoringMetricNames.SWEEP_LEADER_TRANSITIONS, RT) + "[1h]))",
                    t("{{instance}} liderlik {{reason}}", "{{instance}} leadership {{reason}}"), false);
            events.target("sum by (instance, reason) (increase(" + metric(MonitoringMetricNames.PUSH_LEASE_RENEW_FAILED, RT) + "[1h]))",
                    t("{{instance}} push kira {{reason}}", "{{instance}} push lease {{reason}}"), false);
            events.timeseries("short", false, "line");
            add(events);
        }

        // ── Çalışma zamanı ───────────────────────────────────────────────────────────────────────────────

        void runtime() {
            row("runtime", t("Çalışma zamanı (HTTP, JVM, veritabanı)", "Runtime (HTTP, JVM, database)"), true);
            String HB = MonitoringMetricNames.promTimer(MonitoringMetricNames.HTTP_DURATION);
            Panel rate = new Panel("timeseries", t("HTTP istek hızı", "HTTP request rate"),
                    t("SiteMonitor'e gelen isteklerin saniyedeki sayısı, sonuca göre.", "Requests per second to SiteMonitor by outcome."));
            rate.w = 8; rate.h = 8;
            rate.target("sum by (outcome) (rate(http_server_requests_seconds_count" + sel(RT, NOT_PROBES) + "[5m]))", "{{outcome}}", false);
            rate.timeseries("reqps", false, "line");
            add(rate);
            Panel lat = new Panel("timeseries", t("API gecikmesi (p50 / p95 / p99)", "API latency (p50 / p95 / p99)"),
                    t("API isteklerinin gecikme yüzdelikleri (uç etiketsiz histogram).", "Latency percentiles of API requests (endpoint-free histogram)."));
            lat.w = 8; lat.h = 8;
            for (String[] q : new String[][]{{"0.5", "p50"}, {"0.95", "p95"}, {"0.99", "p99"}}) {
                lat.target("histogram_quantile(" + q[0] + ", sum by (le) (rate(" + HB + "_bucket" + sel(RT) + "[5m])))", q[1], false);
            }
            lat.timeseries("s", false, "line");
            add(lat);
            Panel err = stat(t("5xx oranı (5 dk)", "5xx ratio (5 min)"), t("Son 5 dakikada sunucu hatasıyla biten isteklerin payı.",
                    "Share of requests that ended with a server error in the last 5 minutes."),
                    "sum(rate(http_server_requests_seconds_count" + sel(RT, NOT_PROBES, "outcome=\"SERVER_ERROR\"") + "[5m])) / (sum(rate(http_server_requests_seconds_count"
                            + sel(RT, NOT_PROBES) + "[5m])) > 0)", "percentunit", thresholds(GREEN, null, AMBER, 0.01, RED, 0.05), "background", null).size(4, 8);
            err.decimals(2);
            add(err);
            Panel fast = stat(t("≤ 500 ms API isteği", "API requests ≤ 500 ms"), t("Son 5 dakikada 500 ms'de biten API isteklerinin payı.",
                    "Share of API requests that finished within 500 ms in the last 5 minutes."),
                    "sum(rate(" + HB + "_bucket" + sel(RT, "le=\"0.5\"") + "[5m])) / (sum(rate(" + HB + "_count" + sel(RT) + "[5m])) > 0)",
                    "percentunit", thresholds(RED, null, AMBER, 0.9, GREEN, 0.98), "background", null).size(4, 8);
            fast.decimals(1);
            add(fast);

            Panel endpoints = new Panel("timeseries", t("En yoğun uçlar", "Busiest endpoints"),
                    t("İstek hızı en yüksek 10 uç.", "The 10 endpoints with the highest request rate."));
            endpoints.w = 12; endpoints.h = 8;
            endpoints.target("topk(10, sum by (method, uri) (rate(http_server_requests_seconds_count" + sel(RT, NOT_PROBES) + "[5m])))",
                    "{{method}} {{uri}}", false);
            endpoints.timeseries("reqps", false, "line");
            add(endpoints);
            Panel slow = new Panel("timeseries", t("En yavaş uçlar (ortalama)", "Slowest endpoints (average)"),
                    t("Ortalama yanıt süresi en yüksek 10 uç.", "The 10 endpoints with the highest average response time."));
            slow.w = 12; slow.h = 8;
            slow.target("topk(10, sum by (method, uri) (rate(http_server_requests_seconds_sum" + sel(RT) + "[5m])) / (sum by (method, uri) (rate(http_server_requests_seconds_count"
                    + sel(RT) + "[5m])) > 0))", "{{method}} {{uri}}", false);
            slow.timeseries("s", false, "line");
            add(slow);

            Panel heap = new Panel("timeseries", t("JVM bellek (heap)", "JVM memory (heap)"), t("Pod başına kullanılan ve en çok heap.", "Used and max heap per pod."));
            heap.w = 8; heap.h = 8;
            heap.target("sum by (instance) (jvm_memory_used_bytes" + sel(RT, "area=\"heap\"") + ")", t("{{instance}} kullanılan", "{{instance}} used"), false);
            heap.target("sum by (instance) (jvm_memory_max_bytes" + sel(RT, "area=\"heap\"") + ")", t("{{instance}} en çok", "{{instance}} max"), false);
            heap.timeseries("bytes", false, "line");
            add(heap);
            Panel nonheap = new Panel("timeseries", t("JVM bellek (non-heap)", "JVM memory (non-heap)"), t("Pod başına non-heap (metaspace, kod önbelleği).", "Non-heap per pod (metaspace, code cache)."));
            nonheap.w = 8; nonheap.h = 8;
            nonheap.target("sum by (instance) (jvm_memory_used_bytes" + sel(RT, "area=\"nonheap\"") + ")", "{{instance}}", false);
            nonheap.timeseries("bytes", false, "line");
            add(nonheap);
            Panel gc = new Panel("timeseries", t("GC duraklaması", "GC pause time"), t("Saniyede çöp toplayıcı duraklaması (pod başına).", "Garbage collection pause time per second (per pod)."));
            gc.w = 8; gc.h = 8;
            gc.target("sum by (instance) (rate(jvm_gc_pause_seconds_sum" + sel(RT) + "[5m]))", "{{instance}}", false);
            gc.timeseries("s", false, "line");
            add(gc);
            Panel cpu = new Panel("timeseries", t("CPU", "CPU"), t("Süreç ve sistem CPU kullanımı (pod başına).", "Process and system CPU usage (per pod)."));
            cpu.w = 8; cpu.h = 8;
            cpu.target("max by (instance) (process_cpu_usage" + sel(RT) + ")", t("{{instance}} süreç", "{{instance}} process"), false);
            cpu.target("max by (instance) (system_cpu_usage" + sel(RT) + ")", t("{{instance}} sistem", "{{instance}} system"), false);
            cpu.timeseries("percentunit", false, "line");
            add(cpu);
            Panel threads = new Panel("timeseries", t("İş parçacıkları", "Threads"), t("Canlı JVM iş parçacıkları (pod başına).", "Live JVM threads (per pod)."));
            threads.w = 8; threads.h = 8;
            threads.target("max by (instance) (jvm_threads_live_threads" + sel(RT) + ")", "{{instance}}", false);
            threads.timeseries("short", false, "line");
            add(threads);
            Panel logs = new Panel("timeseries", t("Günlük uyarı / hata hızı", "Log warning / error rate"), t("Dakikada WARN ve ERROR günlük satırları.", "WARN and ERROR log lines per minute."));
            logs.w = 8; logs.h = 8;
            logs.target("sum by (instance, level) (rate(logback_events_total" + sel(RT, "level=~\"warn|error\"") + "[5m])) * 60", "{{instance}} {{level}}", false);
            logs.timeseries("short", false, "line");
            add(logs);

            Panel pool = new Panel("timeseries", t("Veritabanı bağlantı havuzu", "Database connection pool"),
                    t("Hikari havuzu: etkin, boşta, bağlantı bekleyen ve en çok bağlantı.", "Hikari pool: active, idle, waiting and max connections."));
            pool.w = 12; pool.h = 8;
            pool.target("sum by (instance) (hikaricp_connections_active" + sel(RT) + ")", t("{{instance}} etkin", "{{instance}} active"), false);
            pool.target("sum by (instance) (hikaricp_connections_idle" + sel(RT) + ")", t("{{instance}} boşta", "{{instance}} idle"), false);
            pool.target("sum by (instance) (hikaricp_connections_pending" + sel(RT) + ")", t("{{instance}} bekleyen", "{{instance}} pending"), false);
            pool.target("sum by (instance) (hikaricp_connections_max" + sel(RT) + ")", t("{{instance}} en çok", "{{instance}} max"), false);
            pool.timeseries("short", false, "line");
            add(pool);
            Panel acquire = new Panel("timeseries", t("Bağlantı alma süresi", "Connection acquire time"),
                    t("Havuzdan bağlantı almanın ortalama süresi ve zaman aşımları.", "Average time to get a pooled connection, and timeouts."));
            acquire.w = 12; acquire.h = 8;
            acquire.target("sum by (instance) (rate(hikaricp_connections_acquire_seconds_sum" + sel(RT) + "[5m])) / (sum by (instance) (rate(hikaricp_connections_acquire_seconds_count"
                    + sel(RT) + "[5m])) > 0)", t("{{instance}} ortalama", "{{instance}} average"), false);
            acquire.target("sum by (instance) (increase(hikaricp_connections_timeout_total" + sel(RT) + "[5m]))", t("{{instance}} zaman aşımı", "{{instance}} timeouts"), false);
            acquire.timeseries("s", false, "line");
            add(acquire);
            Panel exec = new Panel("timeseries", t("Kontrol iş havuzu", "Check thread pool"),
                    t("İzleme kontrollerini koşan certCheckExecutor: etkin iş parçacığı, kuyruk ve havuz boyutu.",
                            "certCheckExecutor running the checks: active threads, queue and pool size."));
            exec.w = 12; exec.h = 8;
            exec.target("max by (instance) (executor_active_threads" + sel(RT, "name=\"certCheckExecutor\"") + ")", t("{{instance}} etkin", "{{instance}} active"), false);
            exec.target("max by (instance) (executor_queued_tasks" + sel(RT, "name=\"certCheckExecutor\"") + ")", t("{{instance}} kuyruk", "{{instance}} queued"), false);
            exec.target("max by (instance) (executor_pool_size_threads" + sel(RT, "name=\"certCheckExecutor\"") + ")", t("{{instance}} havuz", "{{instance}} pool"), false);
            exec.timeseries("short", false, "line");
            add(exec);
            Panel k6 = new Panel("timeseries", t("Sentetik (k6) koşucu", "Synthetic (k6) runner"),
                    t("Koşan ve bekleyen k6 betikleri, k6 ikilisinin varlığı ve atlanan koşular.", "Running and queued k6 scripts, k6 binary availability and skipped runs."));
            k6.w = 12; k6.h = 8;
            k6.target("sum(scripted_k6_active" + sel(RT) + ")", t("Koşan", "Running"), false);
            k6.target("sum(scripted_k6_queued" + sel(RT) + ")", t("Bekleyen", "Queued"), false);
            k6.target("min(scripted_k6_available" + sel(RT) + ")", t("k6 hazır (1)", "k6 available (1)"), false);
            k6.target("sum(increase(scripted_k6_skipped_total" + sel(RT) + "[15m]))", t("Atlanan (15 dk)", "Skipped (15 min)"), false);
            k6.timeseries("short", false, "line");
            add(k6);
            Panel cache = new Panel("timeseries", t("Önbellek isabet oranı", "Cache hit ratio"),
                    t("Uygulama önbelleklerinin isabet oranı (5 dk).", "Hit ratio of the application caches (5 min)."));
            cache.w = 12; cache.h = 8;
            cache.target("sum by (cache) (rate(cache_gets_total" + sel(RT, "result=\"hit\"") + "[5m])) / (sum by (cache) (rate(cache_gets_total" + sel(RT) + "[5m])) > 0)",
                    "{{cache}}", false);
            cache.timeseries("percentunit", false, "line");
            add(cache);
        }

        /** Kalan gün birimi — Grafana'nın "d" birimi haftaya / yıla çevirir ("2.9 week"); gün olarak kalsın. */
        String daysUnit() { return t("suffix: gün", "suffix: days"); }

        // ── Panel yardımcıları ───────────────────────────────────────────────────────────────────────────

        Panel stat(String title, String desc, String expr, String unit, Map<String, Object> thresholds, String colorMode, String linkUrl) {
            Panel pn = new Panel("stat", title, desc);
            pn.w = 4; pn.h = 4;
            pn.target(expr, "", false);
            pn.unit(unit);
            if (thresholds != null) pn.thresholds(thresholds);
            pn.colorMode("thresholds");
            pn.defaults.put("noValue", t("Veri yok", "No data"));
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("colorMode", colorMode);
            opt.put("graphMode", "area");
            opt.put("justifyMode", "auto");
            opt.put("orientation", "auto");
            opt.put("percentChangeColorMode", "standard");
            opt.put("reduceOptions", reduce());
            opt.put("showPercentChange", false);
            opt.put("textMode", "auto");
            opt.put("wideLayout", true);
            pn.options(opt);
            if (linkUrl != null) pn.panelLinks(List.of(link(t("SiteMonitor'da aç", "Open in SiteMonitor"), linkUrl)));
            return pn;
        }

        Map<String, Object> pieOptions() {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("displayLabels", List.of("percent"));
            Map<String, Object> lg = legend("table", "right", List.of());
            lg.put("values", List.of("value", "percent"));
            opt.put("legend", lg);
            opt.put("pieType", "donut");
            opt.put("reduceOptions", reduce());
            opt.put("tooltip", tooltip("single"));
            return opt;
        }

        static Map<String, Object> reduce() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("calcs", List.of("lastNotNull"));
            r.put("fields", "");
            r.put("values", false);
            return r;
        }

        static Map<String, Object> legend(String mode, String placement, List<String> calcs) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("calcs", calcs);
            l.put("displayMode", mode);
            l.put("placement", placement);
            l.put("showLegend", true);
            return l;
        }

        static Map<String, Object> tooltip(String mode) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hideZeros", false);
            m.put("mode", mode);
            m.put("sort", "multi".equals(mode) ? "desc" : "none");
            return m;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Panel
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Tek panelin JSON'u + yerleşim boyutu (genişlik / yükseklik ızgara birimi; x / y kurucu verir). */
    static final class Panel {
        final Map<String, Object> json = new LinkedHashMap<>();
        final Map<String, Object> defaults = new LinkedHashMap<>();
        final Map<String, Object> custom = new LinkedHashMap<>();
        final List<Map<String, Object>> overrides = new ArrayList<>();
        final List<Map<String, Object>> targets = new ArrayList<>();
        final List<Map<String, Object>> transformations = new ArrayList<>();
        int w = 12, h = 8;
        private char nextRef = 'A';

        Panel(String type, String title, String description) {
            json.put("type", type);
            json.put("title", title);
            json.put("description", description);
            if (!"text".equals(type)) json.put("datasource", Builder.ds());
            Map<String, Object> fc = new LinkedHashMap<>();
            fc.put("defaults", defaults);
            fc.put("overrides", overrides);
            if (!"text".equals(type)) json.put("fieldConfig", fc);
            if (!"text".equals(type)) json.put("targets", targets);
            if (!"text".equals(type)) json.put("transformations", transformations);
            json.put("options", new LinkedHashMap<>());
        }

        static Panel text(String title, String markdown, String description) {
            Panel p = new Panel("text", title, description);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("code", om("language", "plaintext", "showLineNumbers", false, "showMiniMap", false));
            opt.put("content", markdown);
            opt.put("mode", "markdown");
            p.json.put("options", opt);
            return p;
        }

        Panel size(int w, int h) { this.w = w; this.h = h; return this; }

        /** Aralık sorgusu (grafik / sparkline); {@code instant=true} → anlık (pasta). */
        Panel target(String expr, String legendFormat, boolean instant) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("datasource", Builder.ds());
            t.put("editorMode", "code");
            t.put("expr", expr);
            t.put("instant", instant);
            t.put("legendFormat", legendFormat == null || legendFormat.isEmpty() ? "__auto" : legendFormat);
            t.put("range", !instant);
            t.put("refId", String.valueOf(nextRef++));
            targets.add(t);
            return this;
        }

        Panel extraTarget(String expr, String legendFormat) {
            return target(expr, legendFormat, false);
        }

        Panel targetInstant(String expr, String legendFormat) {
            return target(expr, legendFormat, true);
        }

        /** Tablo sorgusu (anlık, {@code format: table}). */
        Panel targetTable(String expr, String refId) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("datasource", Builder.ds());
            t.put("editorMode", "code");
            t.put("expr", expr);
            t.put("format", "table");
            t.put("instant", true);
            t.put("legendFormat", "__auto");
            t.put("range", false);
            t.put("refId", refId);
            targets.add(t);
            nextRef = (char) Math.max(nextRef, refId.charAt(0) + 1);
            return this;
        }

        void legendOf(int index, String legend) { targets.get(index).put("legendFormat", legend); }

        void textMode(String mode) {
            @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) json.get("options");
            o.put("textMode", mode);
        }

        void options(Map<String, Object> o) { json.put("options", o); }

        @SuppressWarnings("unchecked")
        Map<String, Object> optionsMap() { return (Map<String, Object>) json.get("options"); }

        void unit(String unit) { defaults.put("unit", unit); }

        void decimals(int d) { defaults.put("decimals", d); }

        void thresholds(Map<String, Object> th) { defaults.put("thresholds", th); }

        void colorMode(String mode) { defaults.put("color", om("mode", mode)); }

        void mappings(List<Object> m) { defaults.put("mappings", m); }

        void minMax(double min, double max) { defaults.put("min", min); defaults.put("max", max); }

        void defaultsCustom(Map<String, Object> c) { custom.putAll(c); defaults.put("custom", custom); }

        void panelLinks(List<Map<String, Object>> links) { json.put("links", links); }

        /** Alan düzeyi veri bağlantıları ({@code ${__field.labels.x}} ya da {@code ${__data.fields.x}}). */
        void fieldLinks(List<Map<String, Object>> links) { defaults.put("links", links); }

        void override(String fieldName, String property, Object value) {
            Map<String, Object> found = null;
            for (Map<String, Object> o : overrides) {
                @SuppressWarnings("unchecked") Map<String, Object> matcher = (Map<String, Object>) o.get("matcher");
                if (fieldName.equals(matcher.get("options"))) { found = o; break; }
            }
            if (found == null) {
                found = new LinkedHashMap<>();
                found.put("matcher", om("id", "byName", "options", fieldName));
                found.put("properties", new ArrayList<Map<String, Object>>());
                overrides.add(found);
            }
            @SuppressWarnings("unchecked") List<Map<String, Object>> props = (List<Map<String, Object>>) found.get("properties");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", property);
            p.put("value", value);
            props.add(p);
        }

        /** Dizi adı → sabit renk (lejant adına göre). */
        void fixedColors(Map<String, String> colors) {
            for (Map.Entry<String, String> e : colors.entrySet()) {
                override(e.getKey(), "color", om("mode", "fixed", "fixedColor", e.getValue()));
            }
        }

        /** Grafik ayarları: birim, yığma, çizim biçimi (çizgi / çubuk). */
        void timeseries(String unit, boolean stacked, String draw) {
            unit(unit);
            colorMode("palette-classic");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("axisPlacement", "auto");
            c.put("barAlignment", 0);
            c.put("drawStyle", draw);
            c.put("fillOpacity", stacked ? 60 : 12);
            c.put("gradientMode", stacked ? "none" : "opacity");
            c.put("lineInterpolation", "smooth");
            c.put("lineWidth", "bars".equals(draw) ? 1 : 2);
            c.put("pointSize", 4);
            c.put("showPoints", "never");
            c.put("spanNulls", false);
            c.put("stacking", om("group", "A", "mode", stacked ? "normal" : "none"));
            c.put("thresholdsStyle", om("mode", "off"));
            defaultsCustom(c);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("legend", Builder.legend("table", "bottom", List.of("lastNotNull", "max")));
            opt.put("tooltip", Builder.tooltip("multi"));
            options(opt);
        }

        /** Çubuk gösterge ayarları (yatay, renk eşiklerle). */
        void bargauge(String unit, Map<String, Object> th) {
            unit(unit);
            thresholds(th);
            colorMode("thresholds");
            defaults.put("noValue", "—");
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("displayMode", "gradient");
            opt.put("maxVizHeight", 300);
            opt.put("minVizHeight", 16);
            opt.put("minVizWidth", 8);
            opt.put("namePlacement", "left");
            opt.put("orientation", "horizontal");
            opt.put("reduceOptions", Builder.reduce());
            opt.put("showUnfilled", true);
            opt.put("sizing", "auto");
            opt.put("valueMode", "color");
            options(opt);
        }

        /** Çok sorgulu tablo: sorgu sonuçları etiket sütunlarında birleşir ({@code merge}). */
        void mergeTable() {
            transformations.add(om("id", "merge", "options", new LinkedHashMap<>()));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("align", "auto");
            c.put("cellOptions", om("type", "auto"));
            c.put("filterable", true);
            c.put("inspect", false);
            defaultsCustom(c);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("cellHeight", "sm");
            opt.put("footer", om("countRows", false, "fields", "", "reducer", List.of("sum"), "show", false));
            opt.put("showHeader", true);
            options(opt);
            Map<String, Object> exclude = new LinkedHashMap<>();
            exclude.put("Time", true);
            Map<String, Object> org = new LinkedHashMap<>();
            org.put("excludeByName", exclude);
            org.put("includeByName", new LinkedHashMap<>());
            org.put("indexByName", new LinkedHashMap<>());
            org.put("renameByName", new LinkedHashMap<>());
            transformations.add(om("id", "organize", "options", org));
        }

        /** Sütun sırası (önde gelenler) ve bağlantı için gerekli ama gizli sütunlar. */
        @SuppressWarnings("unchecked")
        void organize(List<String> order, List<String> hidden) {
            Map<String, Object> org = null;
            for (Map<String, Object> tr : transformations) if ("organize".equals(tr.get("id"))) org = (Map<String, Object>) tr.get("options");
            if (org == null) return;
            Map<String, Object> idx = (Map<String, Object>) org.get("indexByName");
            int i = 0;
            for (String f : order) idx.put(f, i++);
            for (String hdn : hidden) { idx.put(hdn, i++); override(hdn, "custom.hidden", true); }
        }

        /** Değer sütunu ({@code Value #<ref>}): ad, birim, eşik, hücre biçimi, eşlemeler. */
        void valueColumn(String ref, String name, String unit, Map<String, Object> th, String cellType, List<Object> mappings) {
            String f = "Value #" + ref;
            override(f, "displayName", name);
            override(f, "unit", unit);
            if (th != null) override(f, "thresholds", th);
            if (mappings != null) override(f, "mappings", mappings);
            if (!"auto".equals(cellType)) override(f, "custom.cellOptions", om("type", cellType, "mode", "basic"));
        }

        void sortBy(String displayName, boolean desc) {
            @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) json.get("options");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("desc", desc);
            s.put("displayName", displayName);
            o.put("sortBy", List.of(s));
        }
    }

    /** Sıralı harita (Map.of'un yineleme sırası JVM başına rastgeledir — JSON ve içerik özeti kararlı kalsın). */
    static Map<String, Object> om(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Sabit liste kopyası (testler için). */
    static List<String> monitorTypes() {
        return new ArrayList<>(new LinkedHashSet<>(TYPE_LABELS.keySet()));
    }
}
