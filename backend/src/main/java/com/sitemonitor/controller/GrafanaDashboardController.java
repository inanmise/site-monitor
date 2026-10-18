package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.BuildInfo;
import com.sitemonitor.service.grafana.GrafanaDashboardGenerator;
import com.sitemonitor.service.grafana.GrafanaDashboardOptions;
import com.sitemonitor.service.grafana.GrafanaDashboardSettings;
import com.sitemonitor.service.metrics.MetricsExportSettings;
import com.sitemonitor.service.metrics.MonitoringMetricNames;
import com.sitemonitor.service.metrics.MonitoringMetricsExporter;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ayarlar → Platform → Grafana panosu (2026-10-18, kullanıcı isteği: "SiteMonitor'ü Grafana'da ayrı bir panoyla izleyeyim;
 * gereken bilgileri Ayarlar'da sor, İndir'e basınca içe aktarılabilir JSON insin").
 *
 * <ul>
 *   <li>{@code GET /settings} — ADMIN (global + kapsamlı müdür) okur; kapsamlı müdür için {@code read_only}. Değerler,
 *       seçenekler, aktarıcının canlı durumu (bu pod), kayıtlı ayarlarla pano özeti, yayınlanan ölçüm kataloğu.</li>
 *   <li>{@code PUT /settings} — YALNIZ global yönetici (anahtarlar GLOBAL_ONLY). Yalnız gönderilen alanlar yazılır, değişmeyen
 *       değer yazılmaz / yeniden doğrulanmaz; boş değişiklik istek değil 200 + aynı görünüm (denetim yok). Alan hatası 400
 *       {@code VALIDATION_FAILED} + {@code field}. Denetim {@code GRAFANA_SETTINGS_SAVE} + alan farkı.</li>
 *   <li>{@code POST /dashboard/preview} — ADMIN; kaydedilmemiş form değerleriyle ÖN İZLEME özeti (kayıt yok, denetim yok —
 *       {@code AuditCoverageTest} muafiyeti).</li>
 *   <li>{@code GET /dashboard} — YALNIZ global yönetici; KAYITLI ayarlarla pano JSON'u dosya olarak
 *       ({@code Content-Disposition: attachment}, {@code application/json}). Denetim {@code GRAFANA_DASHBOARD_EXPORT}.</li>
 * </ul>
 * Pano JSON'u veri TAŞIMAZ (yalnız sorgular): takım / izleme adları Grafana'da Prometheus'tan okunur. Kaydetme ve indirme
 * global yöneticiye kapılıdır (GLOBAL_ONLY yapılandırma + denetlenen dışa aktarım). Etiket eşleyicisi (ad alanı / iş adı) gizli
 * değildir — kapsamlı müdür onu salt okunur görür (kazıma örneği ve sorun giderme için gerekir); sır taşıyan alan yoktur.
 */
@RestController
@RequestMapping("/api/admin/grafana")
public class GrafanaDashboardController {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Gövde alanı → ayar anahtarı (sıra = form sırası). */
    static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("title", GrafanaDashboardSettings.KEY_TITLE);
        FIELDS.put("uid", GrafanaDashboardSettings.KEY_UID);
        FIELDS.put("link_base_url", GrafanaDashboardSettings.KEY_LINK_BASE_URL);
        FIELDS.put("selector", GrafanaDashboardSettings.KEY_SELECTOR);
        FIELDS.put("time_from", GrafanaDashboardSettings.KEY_TIME_FROM);
        FIELDS.put("refresh", GrafanaDashboardSettings.KEY_REFRESH);
        FIELDS.put("language", GrafanaDashboardSettings.KEY_LANGUAGE);
        FIELDS.put("sections", GrafanaDashboardSettings.KEY_SECTIONS);
        FIELDS.put("per_monitor", GrafanaDashboardSettings.KEY_PER_MONITOR);
        FIELDS.put("default_team", GrafanaDashboardSettings.KEY_DEFAULT_TEAM);
        FIELDS.put("top_n", GrafanaDashboardSettings.KEY_TOP_N);
        FIELDS.put("export_enabled", MetricsExportSettings.KEY_ENABLED);
        FIELDS.put("export_per_monitor", MetricsExportSettings.KEY_PER_MONITOR);
        FIELDS.put("export_max_entities", MetricsExportSettings.KEY_MAX_ENTITIES);
        FIELDS.put("export_refresh_seconds", MetricsExportSettings.KEY_REFRESH_SECONDS);
    }
    static final Set<String> BOOL_FIELDS = Set.of("per_monitor", "export_enabled", "export_per_monitor");

    private final AppSettingsService settings;
    private final AuditService auditService;
    private final TeamRepository teams;

    private MonitoringMetricsExporter exporter;
    private BuildInfo buildInfo;

    public GrafanaDashboardController(AppSettingsService settings, AuditService auditService, TeamRepository teams) {
        this.settings = settings;
        this.auditService = auditService;
        this.teams = teams;
    }

    @Autowired(required = false) public void setExporter(MonitoringMetricsExporter v) { this.exporter = v; }
    @Autowired(required = false) public void setBuildInfo(BuildInfo v) { this.buildInfo = v; }

    // ── Uçlar ──────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        requireAdmin(session);
        return ok(view(session));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body, HttpSession session,
                                                    HttpServletRequest request) {
        requireAdmin(session);
        requireGlobal(session);
        Map<String, String> next = normalize(body == null ? Map.of() : body, true);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : next.entrySet()) {
            String key = FIELDS.get(e.getKey());
            String cur = current(key);
            if (same(e.getKey(), cur, e.getValue())) continue;   // değişmeyen değer yazılmaz
            values.put(key, e.getValue());
            before.put(e.getKey(), cur);
            after.put(e.getKey(), e.getValue());
        }
        if (values.isEmpty()) return ok(view(session));
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("GRAFANA_SETTINGS_SAVE", session, request, "SETTINGS", "grafana",
                AuditDetail.of("fields", new ArrayList<>(after.keySet())), AuditDiff.diff(before, after));
        Map<String, Object> data = view(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        out.put("message", Msg.t("Grafana panosu ayarları kaydedildi (yeniden başlatma gerekmez).",
                "Grafana dashboard settings saved (no restart needed)."));
        return ResponseEntity.ok(out);
    }

    /** Kaydedilmemiş form değerleriyle pano özeti (kayıt yok). */
    @PostMapping("/dashboard/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        Map<String, String> next = normalize(body == null ? Map.of() : body, false);
        GrafanaDashboardOptions o = options(next);
        GrafanaDashboardGenerator.Summary s = GrafanaDashboardGenerator.generate(o).summary();
        Map<String, Object> data = summaryDto(s);
        data.put("link_base_url", o.linkBaseUrl());
        return ok(data);
    }

    /** Kayıtlı ayarlarla pano JSON'u (indirme). */
    @GetMapping("/dashboard")
    public ResponseEntity<byte[]> download(HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requireGlobal(session);
        GrafanaDashboardOptions o = GrafanaDashboardSettings.current(settings, appVersion());
        GrafanaDashboardGenerator.Result r = GrafanaDashboardGenerator.generate(o);
        byte[] json = GrafanaDashboardGenerator.toJson(r.dashboard(), true);
        auditService.recordAction("GRAFANA_DASHBOARD_EXPORT", session, request, "SETTINGS", o.uid(),
                AuditDetail.of("uid", o.uid(), "revision", GrafanaDashboardGenerator.DASHBOARD_REVISION, "panels", r.summary().panels(),
                        "sections", o.sections(), "per_monitor", o.perMonitor(), "language", o.language(), "links", r.summary().links()));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setContentDisposition(ContentDisposition.attachment().filename(r.summary().fileName(), StandardCharsets.UTF_8).build());
        h.setCacheControl("no-store");
        h.setContentLength(json.length);
        return ResponseEntity.ok().headers(h).body(json);
    }

    // ── Görünüm ────────────────────────────────────────────────────────────────────────────────────────

    Map<String, Object> view(HttpSession session) {
        boolean global = SessionScope.isGlobalAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : FIELDS.entrySet()) values.put(e.getKey(), typed(e.getKey(), current(e.getValue())));
        data.put("values", values);
        GrafanaDashboardOptions o = GrafanaDashboardSettings.current(settings, appVersion());
        data.put("app_base_url", nz(settings.getString(GrafanaDashboardSettings.KEY_APP_BASE_URL, "")));
        data.put("effective_link_base_url", o.linkBaseUrl());
        data.put("read_only", !global);

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("time_from", GrafanaDashboardSettings.TIME_FROM_OPTIONS);
        opts.put("refresh", GrafanaDashboardSettings.REFRESH_OPTIONS);
        opts.put("languages", GrafanaDashboardSettings.LANGUAGES);
        opts.put("sections", GrafanaDashboardGenerator.SECTIONS);
        opts.put("top_n", range(GrafanaDashboardSettings.TOP_N_MIN, GrafanaDashboardSettings.TOP_N_MAX, GrafanaDashboardSettings.DEFAULT_TOP_N));
        opts.put("export_max_entities", range(MetricsExportSettings.MAX_ENTITIES_MIN, MetricsExportSettings.MAX_ENTITIES_MAX,
                MetricsExportSettings.MAX_ENTITIES_DEFAULT));
        opts.put("export_refresh_seconds", range(MetricsExportSettings.REFRESH_MIN, MetricsExportSettings.REFRESH_MAX,
                MetricsExportSettings.REFRESH_DEFAULT));
        data.put("options", opts);
        data.put("teams", teamNames(global));
        data.put("exporter", exporterDto());
        data.put("summary", summaryDto(GrafanaDashboardGenerator.generate(o).summary()));
        data.put("revision", GrafanaDashboardGenerator.DASHBOARD_REVISION);
        data.put("schema_version", GrafanaDashboardGenerator.SCHEMA_VERSION);
        data.put("grafana_min_version", GrafanaDashboardGenerator.GRAFANA_MIN_VERSION);
        data.put("app_version", appVersion());
        // İş ölçümleri belirteçli ayrı uçta; kimliksiz /metrics yalnız JVM / HTTP / uygulama sağlığı (2026-10-18)
        data.put("metrics_path", MetricsExportController.PATH);
        data.put("runtime_metrics_path", "/metrics");
        data.put("token_env", com.sitemonitor.service.metrics.MetricsExportToken.ENV);
        data.put("metrics", metricCatalog());
        return data;
    }

    Map<String, Object> exporterDto() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = MetricsExportSettings.enabled(settings);
        MonitoringMetricsExporter.Status s = exporter == null ? null : exporter.lastStatus();
        // Belirteç yoksa aktarıcı hiç koşmaz (uç 404) → "no_token"; yalnız EVET / HAYIR söylenir, değer asla.
        // Açıldıktan sonraki ilk tur gelmeden son durum hâlâ "kapalı" turudur → "bekliyor" (canlı sayılar yok).
        boolean tokenConfigured = exporter != null && exporter.tokenConfigured();
        m.put("token_configured", tokenConfigured);
        m.put("state", !tokenConfigured ? "no_token" : !enabled ? "disabled" : s == null || !s.enabled() ? "waiting" : "running");
        m.put("enabled", enabled);
        m.put("per_monitor", MetricsExportSettings.perMonitor(settings));
        m.put("refresh_seconds", MetricsExportSettings.refreshSeconds(settings));
        m.put("max_entities", MetricsExportSettings.maxEntities(settings));
        m.put("pod", pod());
        m.put("server_now", ISO.format(Instant.now()));
        if (tokenConfigured && s != null && s.enabled()) {
            m.put("refreshed_at", s.refreshedAt());
            m.put("duration_ms", s.durationMs());
            m.put("series_total", s.seriesTotal());
            m.put("entities", s.entities());
            List<Map<String, Object>> sources = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : s.sources().entrySet()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("source", e.getKey());
                r.put("up", e.getValue());
                sources.add(r);
            }
            m.put("sources", sources);
            m.put("capped", s.capped());
        }
        return m;
    }

    static Map<String, Object> summaryDto(GrafanaDashboardGenerator.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("panels", s.panels());
        m.put("rows", s.rows());
        m.put("panels_by_section", s.panelsBySection());
        m.put("variables", s.variables());
        m.put("metrics", s.metrics());
        m.put("file_name", s.fileName());
        m.put("revision", s.revision());
        m.put("links", s.links());
        m.put("title", s.title());
        m.put("uid", s.uid());
        return m;
    }

    static List<Map<String, Object>> metricCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.prom());
            m.put("scope", d.scope().name().toLowerCase(java.util.Locale.ROOT));
            m.put("labels", d.labels());
            m.put("help", d.help());
            out.add(m);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", MonitoringMetricNames.promTimer(MonitoringMetricNames.HTTP_DURATION));
        h.put("scope", "pod");
        h.put("labels", List.of("outcome", "le"));
        h.put("help", "HTTP server request duration histogram without endpoint tag (SLO buckets)");
        out.add(h);
        return out;
    }

    private List<String> teamNames(boolean global) {
        // Kapsamlı müdür salt okunur görür; varsayılan takım değeri zaten "values"ta — takım dizinini yalnız yazan alır.
        if (!global) return List.of();
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            for (Team t : teams.findAll()) if (t.getName() != null && !t.getName().isBlank()) names.add(t.getName());
        } catch (Exception ignored) { /* liste boş — alan serbest metin kalır */ }
        return new ArrayList<>(names);
    }

    private static Map<String, Object> range(int min, int max, int def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", min);
        m.put("max", max);
        m.put("default", def);
        return m;
    }

    // ── Gövde → ayar değerleri ──────────────────────────────────────────────────────────────────────────

    /**
     * Gövdedeki BİLİNEN alanlar → ayar değeri (metin). Tür / biçim hatası alan adıyla 400. {@code skipUnchanged}: kayıtta
     * kayıtlı değere eşit alan yeniden doğrulanmaz (eski, artık geçersiz bir değer başka alanların kaydını kilitlemesin).
     */
    Map<String, String> normalize(Map<String, Object> body, boolean skipUnchanged) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : FIELDS.entrySet()) {
            String field = f.getKey();
            if (!body.containsKey(field)) continue;
            String v = asText(field, body.get(field));
            if (skipUnchanged && same(field, current(f.getValue()), v)) { out.put(field, v); continue; }
            String problem = problem(field, f.getValue(), v);
            if (problem != null) throw new FieldValidationException(field, problem);
            out.put(field, v);
        }
        return out;
    }

    private String problem(String field, String key, String v) {
        if (v.isEmpty()) {
            if ("sections".equals(field)) return Msg.t("En az bir pano bölümü seçin.", "Select at least one dashboard section.");
            return null;   // boş = varsayılana dön
        }
        if ("default_team".equals(field)) {
            String p = GrafanaDashboardSettings.problem(key, v);
            if (p != null) return p;
            Set<String> known = new LinkedHashSet<>();
            try { for (Team t : teams.findAll()) if (t.getName() != null) known.add(t.getName()); } catch (Exception e) { return null; }
            return known.contains(v) ? null : Msg.t("Bu adla bir takım yok; listeden seçin ya da boş bırakın (Tümü).",
                    "No team has this name; pick one from the list or leave it empty (All).");
        }
        if (MetricsExportSettings.KEY_MAX_ENTITIES.equals(key)) {
            return MetricsExportSettings.rangeProblem(v, MetricsExportSettings.MAX_ENTITIES_MIN, MetricsExportSettings.MAX_ENTITIES_MAX);
        }
        if (MetricsExportSettings.KEY_REFRESH_SECONDS.equals(key)) {
            return MetricsExportSettings.rangeProblem(v, MetricsExportSettings.REFRESH_MIN, MetricsExportSettings.REFRESH_MAX);
        }
        return GrafanaDashboardSettings.problem(key, v);
    }

    /** Gövde değeri → metin; yanlış JSON türü alan hatası (sessiz dönüştürme yok). */
    private static String asText(String field, Object raw) {
        if (raw == null) return "";
        if (BOOL_FIELDS.contains(field)) {
            if (raw instanceof Boolean b) return String.valueOf(b);
            throw new FieldValidationException(field, Msg.t("Açık ya da kapalı olmalı.", "Must be on or off."));
        }
        if ("sections".equals(field)) {
            if (!(raw instanceof Collection<?> c)) {
                throw new FieldValidationException(field, Msg.t("Bölüm listesi bir dizi olmalı.", "The section list must be an array."));
            }
            List<String> ids = new ArrayList<>();
            for (Object o : c) {
                if (!(o instanceof String s) || s.isBlank()) {
                    throw new FieldValidationException(field, Msg.t("Bölüm kimliği metin olmalı.", "A section id must be text."));
                }
                ids.add(s.trim());
            }
            return String.join(",", ids);
        }
        if ("top_n".equals(field) || "export_max_entities".equals(field) || "export_refresh_seconds".equals(field)) {
            if (raw instanceof Number n) {
                double d = n.doubleValue();
                if (d != Math.rint(d) || Double.isInfinite(d) || Math.abs(d) > 1_000_000) {
                    throw new FieldValidationException(field, Msg.t("Tam sayı olmalı.", "Must be a whole number."));
                }
                return String.valueOf((long) d);
            }
            if (raw instanceof String s) return s.trim();
            throw new FieldValidationException(field, Msg.t("Tam sayı olmalı.", "Must be a whole number."));
        }
        if (!(raw instanceof String s)) throw new FieldValidationException(field, Msg.t("Metin olmalı.", "Must be text."));
        return s.trim();
    }

    /** Kayıtlı değerler + gövdedeki değişiklikler → üretim girdileri (ön izleme). */
    private GrafanaDashboardOptions options(Map<String, String> changes) {
        GrafanaDashboardOptions saved = GrafanaDashboardSettings.current(settings, appVersion());
        String title = changes.getOrDefault("title", saved.title());
        String uid = blankOr(changes.get("uid"), saved.uid(), GrafanaDashboardSettings.DEFAULT_UID, changes.containsKey("uid"));
        String link = changes.containsKey("link_base_url")
                ? (changes.get("link_base_url").isEmpty() ? nz(settings.getString(GrafanaDashboardSettings.KEY_APP_BASE_URL, ""))
                : changes.get("link_base_url")) : saved.linkBaseUrl();
        if (!link.isEmpty() && GrafanaDashboardSettings.urlProblem(link) != null) link = "";
        String selector = changes.getOrDefault("selector", saved.selector());
        String timeFrom = blankOr(changes.get("time_from"), saved.timeFrom(), GrafanaDashboardSettings.DEFAULT_TIME_FROM, changes.containsKey("time_from"));
        String refresh = blankOr(changes.get("refresh"), saved.refresh(), GrafanaDashboardSettings.DEFAULT_REFRESH, changes.containsKey("refresh"));
        String language = blankOr(changes.get("language"), saved.language(), GrafanaDashboardSettings.DEFAULT_LANGUAGE, changes.containsKey("language"));
        List<String> sections = changes.containsKey("sections") ? GrafanaDashboardSettings.parseSections(changes.get("sections")) : saved.sections();
        boolean perMonitor = changes.containsKey("per_monitor") ? !"false".equals(changes.get("per_monitor")) : saved.perMonitor();
        String team = changes.getOrDefault("default_team", saved.defaultTeam());
        int topN = saved.topN();
        if (changes.containsKey("top_n") && !changes.get("top_n").isEmpty()) topN = Integer.parseInt(changes.get("top_n"));
        else if (changes.containsKey("top_n")) topN = GrafanaDashboardSettings.DEFAULT_TOP_N;
        return new GrafanaDashboardOptions(title, uid, GrafanaDashboardSettings.trimUrl(link), selector, timeFrom, refresh, language,
                sections, perMonitor, team, topN, appVersion());
    }

    private static String blankOr(String v, String saved, String def, boolean present) {
        if (!present) return saved;
        return v == null || v.isEmpty() ? def : v;
    }

    /** Ayarın etkin değeri (kayıt → properties) — boş = boş dize. */
    private String current(String key) {
        String v = settings.getString(key, "");
        return v == null ? "" : v.trim();
    }

    /** Kayıtlı metin → yanıt türü (bool / int / dizi / metin). */
    private Object typed(String field, String v) {
        if (BOOL_FIELDS.contains(field)) return v.isEmpty() || Boolean.parseBoolean(v);
        if ("sections".equals(field)) {
            List<String> s = GrafanaDashboardSettings.parseSections(v);
            return s.isEmpty() ? GrafanaDashboardGenerator.SECTIONS : s;
        }
        if ("top_n".equals(field) || "export_max_entities".equals(field) || "export_refresh_seconds".equals(field)) {
            try { return Integer.parseInt(v); } catch (Exception e) { return null; }
        }
        return v;
    }

    /**
     * Kayıtlı değer ile gelen değer aynı mı — alan türüne göre: bölümler küme olarak (sıra / boşluk farkı değişiklik değil),
     * aç/kapa büyük/küçük harf duyarsız, diğerleri kırpılmış metin olarak BİREBİR (başlık / eşleyici / takım adında büyük-küçük
     * harf farkı gerçek bir değişikliktir).
     */
    static boolean same(String field, String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.equals(y)) return true;
        if ("sections".equals(field)) {
            return GrafanaDashboardSettings.parseSections(x).equals(GrafanaDashboardSettings.parseSections(y));
        }
        if (BOOL_FIELDS.contains(field)) return isBool(x) && x.equalsIgnoreCase(y);
        return false;
    }

    private static boolean isBool(String v) {
        return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
    }

    private String appVersion() {
        try {
            String v = buildInfo == null ? null : buildInfo.get().version();
            return v == null || v.isBlank() ? "local" : v;
        } catch (Exception e) {
            return "local";
        }
    }

    private String pod() {
        try {
            if (buildInfo != null) {
                BuildInfo.Snapshot s = buildInfo.get();
                if (s.podName() != null && !s.podName().isBlank()) return s.podName();
                if (s.hostname() != null && !s.hostname().isBlank()) return s.hostname();
            }
        } catch (Exception ignored) { /* aşağıda */ }
        return MonitoringMetricsExporter.instanceId();
    }

    private static String nz(String v) { return v == null ? "" : v.trim(); }

    // ── Kapılar ────────────────────────────────────────────────────────────────────────────────────────

    private static void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session) && !SessionScope.isScopedAdmin(session)) {
            throw new SecurityException(Msg.t("Bu sayfa yöneticilere açıktır.", "This page is for administrators."));
        }
    }

    private static void requireGlobal(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Grafana panosu ayarlarını yalnız global yöneticiler değiştirebilir ve indirebilir.",
                    "Only global administrators can change and download the Grafana dashboard."));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : String.valueOf(u);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }
}
