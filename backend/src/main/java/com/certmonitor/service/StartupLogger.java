package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uygulama hazır olduğunda (ApplicationReadyEvent) iki şey loglar:
 * <ol>
 *   <li><b>Etkin Konfigürasyon</b> — uygulamanın hangi ayarlarla çalıştığını (default / application.properties /
 *       env-JVM override / DB app_settings·smtp·ldap kaynaklı NİHAİ değer) tek okunabilir blok halinde; secret'lar
 *       {@link SecretMask} ile maskeli. Zamanlanmış işlerden ÖNCE bassın diye {@code @Order(HIGHEST_PRECEDENCE)}
 *       ({@code SchedulerService.runOnStartup} da aynı event'te). DB erişilemezse ilgili bölüm "okunamadı" der,
 *       açılış engellenmez.</li>
 *   <li><b>Frontend statik-UI durumu</b> — React (Vite) dist'inin pod içinde sunulabilir olup olmadığı.</li>
 * </ol>
 * {@link ShutdownLogger} ile simetriktir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupLogger {

    private final ConfigurableEnvironment env;
    private final AppSettingsService appSettings;
    private final SmtpSettingsService smtpSettings;
    private final LdapSettingsService ldapSettings;
    private final SecretCipher secretCipher;

    @Value("${spring.web.resources.static-locations:}")
    private String staticLocations;

    @Value("${cert.monitor.startup.config-log.enabled:true}")
    private boolean configLogEnabled;

    @Value("${cert.monitor.startup.config-log.json:false}")
    private boolean configLogJson;

    // ── Etkin konfigürasyon logu ─────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)   // zamanlanmış işler / ağır tarama başlamadan ÖNCE bas
    public void logEffectiveConfig() {
        if (!configLogEnabled) return;
        try {
            log.info("{}", renderConfig());
        } catch (Exception e) {
            // Konfig logu ASLA başlatmayı bozmamalı
            log.warn("[CONFIG] Etkin konfigürasyon logu üretilemedi: {}", e.toString());
        }
    }

    /** Test edilebilir giriş: bölümleri kurar ve metin ya da JSON render eder. İstisna fırlatmaz (bölüm-içi try/catch). */
    String renderConfig() {
        List<Section> sections = buildSections();
        return configLogJson ? renderJson(sections) : renderText(sections);
    }

    private List<Section> buildSections() {
        List<Section> s = new ArrayList<>();
        s.add(appSection());
        s.add(section("Sunucu", SERVER_KEYS));
        s.add(dbSection());
        s.add(section("Bağlantı Havuzu (Hikari)", HIKARI_KEYS));
        s.add(section("JPA / Hibernate", JPA_KEYS));
        s.add(section("Loglama & Actuator", LOG_KEYS));
        s.add(section("Yükleme (Upload)", UPLOAD_KEYS));
        s.add(section("Zamanlayıcı", SCHED_KEYS));
        s.add(section("İzleme (boot)", MONITOR_KEYS));
        s.add(section("Alarm & Bildirim (boot)", ALARM_KEYS));
        s.add(securitySection());
        s.add(section("Proxy & Tanı", PROXY_DIAG_KEYS));
        s.add(smtpSection());
        s.add(ldapSection());
        s.addAll(catalogSections());
        s.add(envSection());
        return s;
    }

    // ── Bölüm kurucular ──────────────────────────────────────────────────────

    private Section appSection() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("version", AppVersion.resolve(env), "file"));
        rows.add(new Row("profiles.active", profiles(), "runtime"));
        rows.add(propRow("spring.application.name"));
        return new Section("Uygulama", rows);
    }

    private Section dbSection() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("spring.datasource.url", SecretMask.maskJdbcUrl(env.getProperty("spring.datasource.url")),
                sourceTag("spring.datasource.url", false)));
        for (String k : new String[]{"spring.datasource.driver-class-name", "spring.datasource.username",
                "spring.datasource.password"}) rows.add(propRow(k));
        return new Section("Veritabanı", rows);
    }

    private Section securitySection() {
        List<Row> rows = new ArrayList<>();
        for (String k : SECURITY_KEYS) rows.add(propRow(k));
        String sk;
        try { sk = secretCipher.isKeyConfigured() ? "configured (secure)" : "not-set (insecure DEV default)"; }
        catch (Exception e) { sk = "bilinmiyor"; }
        rows.add(new Row("cert.monitor.secret-key", sk, "runtime"));   // DEĞER asla — yalnız durum
        return new Section("Güvenlik", rows);
    }

    private Section smtpSection() {
        List<Row> rows = new ArrayList<>();
        try {
            Map<String, Object> m = smtpSettings.toClientMap(smtpSettings.getOrDefaults());   // secret-free (password_set)
            m.forEach((k, v) -> rows.add(new Row("smtp." + k, shortVal(v), "db")));
        } catch (Exception e) {
            rows.add(new Row("smtp", "okunamadı (DB erişilemedi)", "db"));
        }
        return new Section("SMTP", rows);
    }

    private Section ldapSection() {
        List<Row> rows = new ArrayList<>();
        try {
            Map<String, Object> m = ldapSettings.toClientMap(ldapSettings.getOrDefaults());   // secret-free (bind_password_set)
            m.forEach((k, v) -> rows.add(new Row("ldap." + k, shortVal(v), "db")));
        } catch (Exception e) {
            rows.add(new Row("ldap", "okunamadı (DB erişilemedi)", "db"));
        }
        return new Section("LDAP", rows);
    }

    /** DB app_settings kataloğu — grup bazında bölümlenir; her değer efektif (override varsa db, yoksa property). */
    private List<Section> catalogSections() {
        List<Section> out = new ArrayList<>();
        try {
            Map<String, List<Row>> byGroup = new LinkedHashMap<>();
            for (Map<String, Object> e : appSettings.getCatalogForClient()) {
                String key = String.valueOf(e.get("key"));
                String group = String.valueOf(e.get("group"));
                boolean overridden = Boolean.TRUE.equals(e.get("overridden"));
                String value = SecretMask.isSensitive(key) ? SecretMask.MASK : shortVal(e.get("value"));
                byGroup.computeIfAbsent(group, g -> new ArrayList<>())
                        .add(new Row(key, value, overridden ? "db" : sourceTag(key, false)));
            }
            byGroup.forEach((g, rows) -> out.add(new Section("Katalog · " + catalogGroupTr(g), rows)));
        } catch (Exception e) {
            List<Row> r = new ArrayList<>();
            r.add(new Row("app_settings", "okunamadı (DB erişilemedi)", "db"));
            out.add(new Section("Katalog (DB app_settings)", r));
        }
        return out;
    }

    private Section envSection() {
        List<Row> rows = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();
        rows.add(new Row("java.version", System.getProperty("java.version"), "runtime"));
        rows.add(new Row("java.vendor", System.getProperty("java.vendor"), "runtime"));
        rows.add(new Row("jvm.max-heap-mb", String.valueOf(rt.maxMemory() / (1024 * 1024)), "runtime"));
        rows.add(new Row("jvm.available-processors", String.valueOf(rt.availableProcessors()), "runtime"));
        rows.add(new Row("os.name", System.getProperty("os.name"), "runtime"));
        rows.add(new Row("os.arch", System.getProperty("os.arch"), "runtime"));
        rows.add(new Row("os.version", System.getProperty("os.version"), "runtime"));
        rows.add(new Row("timezone.default", TimeZone.getDefault().getID(), "runtime"));
        rows.add(propRow("cert.monitor.log.timezone"));
        rows.add(new Row("user.dir", System.getProperty("user.dir"), "runtime"));
        try { rows.add(new Row("pid", String.valueOf(ProcessHandle.current().pid()), "runtime")); } catch (Exception ignored) { }
        return new Section("Ortam", rows);
    }

    // ── Satır/kaynak yardımcıları ────────────────────────────────────────────

    private Section section(String title, String[] keys) {
        List<Row> rows = new ArrayList<>();
        Set<String> cat = catalogKeys();
        for (String k : keys) {
            if (cat.contains(k)) continue;   // katalog dökümünde görünecek → tekrarlama
            rows.add(propRow(k));
        }
        return new Section(title, rows);
    }

    /** Katalog anahtar kümesi (statik; DB gerektirmez) — curated bölümlerde tekrarı önlemek için. */
    private Set<String> catalogKeys() {
        Set<String> s = new HashSet<>();
        try { for (AppSettingsCatalog.Setting st : AppSettingsCatalog.ALL) s.add(st.key()); } catch (Exception ignored) { }
        return s;
    }

    private Row propRow(String key) {
        String val = env.getProperty(key);
        return new Row(key, SecretMask.mask(key, val), sourceTag(key, false));
    }

    private String profiles() {
        String[] p = env.getActiveProfiles();
        return p.length == 0 ? "(default)" : String.join(",", p);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(?::[^}]*)?\\}");

    /** Efektif değerin kaynağını best-effort belirler: db / env / config / default. Asla fırlatmaz. */
    private String sourceTag(String key, boolean overriddenInDb) {
        if (overriddenInDb) return "db";
        try {
            // 1) Doğrudan -D / ortam değişkeni ile bu tam anahtar override edilmiş mi?
            for (PropertySource<?> ps : env.getPropertySources()) {
                String n = ps.getName();
                if ((n.contains("systemProperties") || n.contains("systemEnvironment")) && ps.containsProperty(key))
                    return "env";
            }
            // 2) Dosya kaynağındaki ham değeri incele (${VAR:def} → VAR set mi?)
            for (PropertySource<?> ps : env.getPropertySources()) {
                String n = ps.getName();
                if (n.contains("systemProperties") || n.contains("systemEnvironment")) continue;
                if (ps.containsProperty(key)) {
                    Object raw = ps.getProperty(key);
                    Matcher mm = PLACEHOLDER.matcher(raw == null ? "" : String.valueOf(raw));
                    if (mm.find()) {
                        String var = mm.group(1);
                        return (System.getenv(var) != null || System.getProperty(var) != null) ? "env" : "default";
                    }
                    return "config";
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return "default";
    }

    private static String shortVal(Object v) { return v == null ? "(ayarsız)" : shortValRaw(v); }
    private static String shortValRaw(Object v) {
        String s = String.valueOf(v);
        if (s.isBlank()) return "(ayarsız)";
        return s.length() > 140 ? s.substring(0, 137) + "… (" + s.length() + " krk)" : s;
    }

    private static String catalogGroupTr(String g) {
        return switch (g == null ? "" : g) {
            case "general" -> "Genel";
            case "outage" -> "Kesinti";
            case "scheduler" -> "Zamanlayıcı";
            case "monitoring" -> "İzleme";
            case "frequency" -> "Kontrol Sıklığı";
            case "weekly" -> "Haftalık Rapor";
            case "storm" -> "Alarm Fırtınası";
            case "security" -> "Güvenlik";
            case "branding" -> "Marka";
            case "logging" -> "Loglama";
            case "login-anomaly" -> "Login Anomali";
            case "retention" -> "Saklama (Retention)";
            default -> g;
        };
    }

    // ── Render: metin (çerçeveli) ────────────────────────────────────────────

    private String renderText(List<Section> sections) {
        String version = AppVersion.resolve(env);
        StringBuilder sb = new StringBuilder("\n");
        String bar = "═".repeat(16);
        sb.append(bar).append(" ETKİN KONFİGÜRASYON — CertMonitor ").append(version).append(' ').append(bar).append('\n');
        sb.append("profiller=").append(profiles())
          .append("   ·   secret değerler maskeli (").append(SecretMask.MASK).append(")")
          .append("   ·   kaynak: [default]/[config]/[env]/[db]/[runtime]/[file]\n");
        for (Section s : sections) {
            if (s.rows().isEmpty()) continue;
            sb.append("\n── ").append(s.title()).append(' ')
              .append("─".repeat(Math.max(3, 70 - s.title().length()))).append('\n');
            int w = 0;
            for (Row r : s.rows()) w = Math.max(w, Math.min(48, r.key().length()));
            for (Row r : s.rows()) {
                sb.append("  ").append(pad(r.key(), w)).append(" = ").append(r.value())
                  .append("  [").append(r.source()).append("]\n");
            }
        }
        return sb.toString();
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) return s;
        return s + " ".repeat(w - s.length());
    }

    // ── Render: JSON (tek satır, log-toplama için) ───────────────────────────

    private String renderJson(List<Section> sections) {
        StringBuilder sb = new StringBuilder("{");
        sb.append(js("_meta")).append(":{").append(js("version")).append(':').append(js(AppVersion.resolve(env)))
          .append(',').append(js("profiles")).append(':').append(js(profiles())).append('}');
        for (Section s : sections) {
            sb.append(',').append(js(s.title())).append(":{");
            boolean first = true;
            for (Row r : s.rows()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(js(r.key())).append(":{").append(js("v")).append(':').append(js(r.value()))
                  .append(',').append(js("src")).append(':').append(js(r.source())).append('}');
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private record Row(String key, String value, String source) {}
    private record Section(String title, List<Row> rows) {}

    // ── Küratörlü anahtar listeleri (katalog-DIŞI @Value/env etkin ayarları) ──

    private static final String[] SERVER_KEYS = {
            "server.port", "server.tomcat.threads.max", "server.servlet.session.timeout",
            "server.servlet.session.cookie.secure", "server.servlet.session.cookie.same-site",
            "server.compression.enabled", "server.compression.min-response-size",
            "server.shutdown", "spring.lifecycle.timeout-per-shutdown-phase" };

    private static final String[] HIKARI_KEYS = {
            "spring.datasource.hikari.pool-name", "spring.datasource.hikari.maximum-pool-size",
            "spring.datasource.hikari.minimum-idle", "spring.datasource.hikari.connection-timeout",
            "spring.datasource.hikari.idle-timeout", "spring.datasource.hikari.max-lifetime",
            "spring.datasource.hikari.leak-detection-threshold", "spring.datasource.hikari.keepalive-time",
            "spring.datasource.hikari.validation-timeout" };

    private static final String[] JPA_KEYS = {
            "spring.jpa.hibernate.ddl-auto", "spring.jpa.show-sql", "spring.jpa.open-in-view",
            "spring.jpa.properties.hibernate.default_batch_fetch_size", "spring.jpa.properties.hibernate.jdbc.batch_size",
            "spring.session.store-type" };

    private static final String[] LOG_KEYS = {
            "logging.level.root", "logging.level.com.certmonitor", "logging.file.path",
            "management.endpoints.web.exposure.include", "management.endpoint.health.show-details",
            "management.health.db.enabled", "management.health.mail.enabled" };

    private static final String[] UPLOAD_KEYS = {
            "spring.servlet.multipart.max-file-size", "spring.servlet.multipart.max-request-size" };

    private static final String[] SCHED_KEYS = {
            "spring.task.scheduling.pool.size", "cert.monitor.scheduler.cron", "cert.monitor.scheduler.stale-minutes",
            "cert.monitor.scheduler.lock-ttl-minutes", "cert.monitor.scheduler.sweep-lock-ttl-minutes",
            "cert.monitor.scheduler.startup-check-delay-ms", "cert.monitor.scheduler.orphan-cleanup-interval-ms",
            "cert.monitor.parallel-workers", "cert.monitor.settings.refresh-ms" };

    private static final String[] MONITOR_KEYS = {
            "cert.monitor.check-timeout-seconds", "cert.monitor.warning-days",
            "cert.monitor.executor.core-size", "cert.monitor.executor.max-size", "cert.monitor.executor.queue-capacity",
            "cert.monitor.network.error-rate-threshold", "cert.monitor.network.min-errors",
            "cert.monitor.check.max-attempts", "cert.monitor.check.retry-delay-ms", "cert.monitor.check.tls-mode" };

    private static final String[] ALARM_KEYS = {
            "cert.monitor.alert.default-warning-days", "cert.monitor.alert.default-high-days",
            "cert.monitor.alert.default-critical-days", "cert.monitor.alert.realert-hours",
            "cert.monitor.uptime.alert-enabled", "cert.monitor.port.alert-enabled", "cert.monitor.dns.alert-enabled",
            "cert.monitor.keyword.alert-enabled", "cert.monitor.ping.alert-enabled",
            "cert.monitor.webhook.timeout-seconds", "cert.monitor.system-admin.email", "cert.monitor.app.base-url",
            "cert.monitor.email.enabled", "cert.monitor.email.from" };

    private static final String[] SECURITY_KEYS = {
            "cert.monitor.username", "cert.monitor.password",
            "cert.monitor.login.max-attempts", "cert.monitor.login.block-seconds",
            "cert.monitor.password.min-length", "cert.monitor.password.max-length", "cert.monitor.password.history-count",
            "cert.monitor.lockout.durations-seconds", "cert.monitor.lockout.failures-needed",
            "cert.monitor.session.active-window-seconds", "cert.monitor.session.supersede-cache-ms",
            "cert.monitor.session.touch-debounce-ms", "cert.monitor.remember.ttl-seconds",
            "cert.monitor.cors.allowed-origins", "cert.monitor.cors.max-age-seconds" };

    private static final String[] PROXY_DIAG_KEYS = {
            "cert.monitor.proxy.host", "cert.monitor.proxy.port", "cert.monitor.proxy.user", "cert.monitor.proxy.pass",
            "cert.monitor.proxy.no-proxy", "cert.monitor.proxy.auto-fallback",
            "cert.monitor.diagnostics.timeout-seconds", "cert.monitor.diagnostics.openssl-bin",
            "cert.monitor.geoip.api-url", "cert.monitor.cache.crl-ttl-hours",
            "cert.monitor.client-ip.headers", "cert.monitor.trust.ca-bundle-pem" };

    // ── Frontend statik-UI durumu (mevcut davranış) ──────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            logFrontendStatus();
        } catch (Exception e) {
            // Log kontrolü asla başlatmayı bozmamalı
            log.warn("[FRONTEND] Statik arayüz durumu kontrol edilemedi: {}", e.getMessage());
        }
    }

    private void logFrontendStatus() {
        String cwd = System.getProperty("user.dir", "?");
        List<String> locs = parseLocations(staticLocations);
        log.info("[FRONTEND] Statik arayüz aranıyor — CWD={}, konumlar={}", cwd, locs);

        // 1) Dosya sistemindeki konumlar (Docker: file:./frontend/dist/)
        Optional<File> fileIndex = findFileIndex(staticLocations);
        if (fileIndex.isPresent()) {
            File index = fileIndex.get();
            int files = countFiles(index.getParentFile().toPath());
            log.info("[FRONTEND] ✅ Statik arayüz HAZIR — index.html bulundu: {} ({} byte), dist'te {} dosya. UI sunulabilir.",
                    safeAbs(index), index.length(), files);
            return;
        }

        // 2) Jar'a gömülü classpath:/static/index.html (yedek dağıtım biçimi)
        if (getClass().getResource("/static/index.html") != null) {
            log.info("[FRONTEND] ✅ Statik arayüz HAZIR — index.html classpath:/static/ içinde (jar'a gömülü). UI sunulabilir.");
            return;
        }

        // 3) Hiçbir yerde yok — UI sunulamaz
        log.warn("[FRONTEND] ⚠ Statik arayüz BULUNAMADI — index.html hiçbir konumda yok "
                + "(CWD={}, konumlar={}). Backend/API çalışır ancak UI istekleri 404 dönebilir. "
                + "Docker imajında frontend/dist kopyalandı mı / build başarılı mı kontrol edin.",
                cwd, locs);
    }

    // ── Test edilebilir saf yardımcılar ─────────────────────────────────────────

    /** Virgülle ayrılmış static-locations değerini temiz listeye çevirir. */
    static List<String> parseLocations(String staticLocations) {
        List<String> locs = new ArrayList<>();
        if (staticLocations == null) return locs;
        for (String raw : staticLocations.split(",")) {
            String loc = raw.trim();
            if (!loc.isEmpty()) locs.add(loc);
        }
        return locs;
    }

    /** İlk {@code file:} konumunda index.html varsa onu döner (classpath konumları atlanır). */
    static Optional<File> findFileIndex(String staticLocations) {
        for (String loc : parseLocations(staticLocations)) {
            if (!loc.startsWith("file:")) continue;
            File index = new File(loc.substring("file:".length()), "index.html");
            if (index.isFile()) return Optional.of(index);
        }
        return Optional.empty();
    }

    private static String safeAbs(File f) {
        try { return f.getAbsolutePath(); } catch (Exception e) { return f.getPath(); }
    }

    /** Dizindeki normal dosya sayısı (özyinelemeli); okunamazsa -1. */
    private static int countFiles(Path dir) {
        try (var s = Files.walk(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return -1;
        }
    }
}
