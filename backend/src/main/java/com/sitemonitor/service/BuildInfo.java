package com.sitemonitor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Çalışan örneğin build + dağıtım kimliği — tek doğruluk kaynağı (Sürüm & Dağıtım Geçmişi, 2026-09-10).
 *
 * <p>Kaynaklar: sürüm {@link AppVersion} (env → VERSION dosyası → manifest); commit/build zamanı/imaj
 * sürümü Dockerfile {@code ENV APP_GIT_COMMIT/APP_BUILD_TIME/APP_IMAGE_VERSION}; helm/imaj ref/config
 * checksum Helm deployment env'leri; pod/düğüm Downward API ({@code POD_NAME}/{@code NODE_NAME});
 * instance {@link SchedulerService#instanceId()}. Hepsi BOŞ-toleranslı: compose/yerel koşumda değerler
 * boş kalır, hiçbir yer çökmez, ekran "bilinmiyor" der. Sır taşımaz.
 *
 * <p><b>Ortam adı CANLI (2026-09-29):</b> diğer alanlar süreç ömrü boyunca sabittir, ortam adı ise
 * Ayarlar → Genel Ayarlar'dan ({@link #ENV_KEY}, {@code app_settings}) yeniden başlatmasız değiştirilebilir.
 * Öncelik: DB ayarı (boş değilse) &gt; {@code APP_ENVIRONMENT} &gt; otomatik ({@code POD_NAME} yoksa
 * {@code local}, varsa {@code unknown}) — {@link #resolveEnvironment}. {@link #get()} her çağrıda ayarı
 * okur (bellek haritası, DB'ye gitmez) ve ortam adı değiştiyse aynı anlık görüntünün ortamı değişmiş
 * kopyasını döner; çok pod'da başka pod'un kaydı ayar önbelleği tazelemesiyle (~10 sn) yansır.
 *
 * <p>Statik {@link #resolve(Environment)} bean enjeksiyonu istemeyen yerler için (DB ayarını görmez).
 */
@Component
public class BuildInfo {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** Ortam adı anahtarı — Spring özelliği ({@code ${APP_ENVIRONMENT:}}) ile AYNI ad: DB satırı env'i ezer (log seviyesi deseni). */
    public static final String ENV_KEY = "site.monitor.environment";

    /** Ortam adı biçimi — dağıtım geçmişinin elle kayıt doğrulamasıyla ({@code DeploymentHistoryService}) ve kolon boyuyla (40) aynı. */
    public static final Pattern ENV_NAME = Pattern.compile("^[a-z0-9-]{1,40}$");

    /** Ortam adının nereden geldiği; tel biçimi {@link #wire()} (küçük harf). */
    public enum EnvSource {
        /** Ayarlar → Genel Ayarlar (app_settings) */ SETTING,
        /** Helm {@code config.environmentName} → {@code APP_ENVIRONMENT} */ ENV,
        /** hiçbiri yok: pod'da {@code unknown}, pod dışında {@code local} */ AUTO;

        public String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Etkin ortam adı + kaynağı. */
    public record EnvName(String name, EnvSource source) {}

    /** Anlık görüntü — boş dize = bilinmiyor (null değil; JSON/loglarda tekdüze). */
    public record Snapshot(
            String version, String versionSource,
            String commit, String buildTime, String imageVersion, String imageRef,
            String environment, String helmRelease, Integer helmRevision, String helmChartVersion,
            String configChecksum, String instanceId, String hostname, String podName, String nodeName,
            String javaVersion, String jvmStartedAt, long jvmStartEpochMillis) {

        public String commitShort() { return commit == null || commit.isBlank() ? "" : commit.substring(0, Math.min(8, commit.length())); }

        /** VERSION dosyası ile imaj etiketi ayrışmış mı (yanlış imaj/yanlış dosya uyarısı). */
        public boolean mismatch() {
            return !imageVersion.isBlank() && !version.isBlank() && !imageVersion.equals(version);
        }

        public long uptimeSeconds() { return Math.max(0, (System.currentTimeMillis() - jvmStartEpochMillis) / 1000); }

        /** Aynı anlık görüntü, yalnız ortam adı değişmiş (canlı ortam adı; diğer alanlar süreç ömrünce sabit). */
        public Snapshot withEnvironment(String env) {
            return new Snapshot(version, versionSource, commit, buildTime, imageVersion, imageRef,
                    env, helmRelease, helmRevision, helmChartVersion, configChecksum, instanceId, hostname,
                    podName, nodeName, javaVersion, jvmStartedAt, jvmStartEpochMillis);
        }
    }

    private final String envProperty;
    private final String podName;
    /** Son döndürülen anlık görüntü; ortam adı değişince yenisiyle değişir (aynı adla aynı örnek döner). */
    private volatile Snapshot live;
    /** İsteğe bağlı: yoksa (testler, dilim bağlamları) ortam adı yalnız env/otomatik'ten çözülür. */
    private volatile AppSettingsService settings;

    public BuildInfo(Environment env) {
        this.live = resolve(env);
        this.envProperty = prop(env, ENV_KEY);
        this.podName = live.podName();
    }

    @Autowired(required = false)
    public void setSettings(AppSettingsService settings) { this.settings = settings; }

    /** Anlık görüntü — ortam adı CANLI (bkz. sınıf açıklaması), diğer alanlar açılış değeri. */
    public Snapshot get() {
        String env = environmentName().name();
        Snapshot s = live;
        if (s.environment().equals(env)) return s;
        Snapshot next = s.withEnvironment(env);
        live = next;
        return next;
    }

    /** Etkin ortam adı + kaynağı (ayar → APP_ENVIRONMENT → otomatik). */
    public EnvName environmentName() {
        AppSettingsService s = settings;
        String override = null;
        if (s != null) {
            try { override = s.getOverride(ENV_KEY); } catch (Exception ignored) { /* ayar okunamadı → env/otomatik */ }
        }
        return resolveEnvironment(override, envProperty, podName);
    }

    /**
     * Öncelik: DB ayarı (boş değilse VE biçimi geçerliyse) &gt; {@code APP_ENVIRONMENT} &gt; otomatik. Saf.
     * Biçimi bozuk bir DB değeri (ekran yolunu atlayıp elle yazılmış) yok sayılır — ekran ve metrik
     * geçersiz adı taşımasın; kayıt yolu zaten 400 ile reddeder.
     */
    public static EnvName resolveEnvironment(String setting, String property, String podName) {
        String s = setting == null ? "" : setting.trim();
        if (!s.isEmpty() && ENV_NAME.matcher(s).matches()) return new EnvName(s, EnvSource.SETTING);
        String p = property == null ? "" : property.trim();
        if (!p.isEmpty()) return new EnvName(p, EnvSource.ENV);
        return new EnvName(podName == null || podName.isBlank() ? "local" : "unknown", EnvSource.AUTO);
    }

    /** {@link #resolveEnvironment(String, String, String)} — özellik ve {@code POD_NAME} bu süreçten okunur. */
    public static EnvName resolveEnvironment(String setting, Environment env) {
        return resolveEnvironment(setting, prop(env, ENV_KEY), System.getenv().getOrDefault("POD_NAME", ""));
    }

    public static Snapshot resolve(Environment env) {
        AppVersion.Resolved v = AppVersion.resolveWithSource(env);
        String podName = System.getenv().getOrDefault("POD_NAME", "");
        String environment = resolveEnvironment(null, prop(env, ENV_KEY), podName).name();
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        return new Snapshot(
                v.version(), v.source(),
                prop(env, "site.monitor.build.commit"),
                prop(env, "site.monitor.build.time"),
                prop(env, "site.monitor.build.image-version"),
                prop(env, "site.monitor.image-ref"),
                environment,
                prop(env, "site.monitor.helm.release"),
                parseInt(prop(env, "site.monitor.helm.revision")),
                prop(env, "site.monitor.helm.chart-version"),
                prop(env, "site.monitor.config-checksum"),
                SchedulerService.instanceId(),
                SchedulerService.hostname(),
                podName,
                System.getenv().getOrDefault("NODE_NAME", ""),
                System.getProperty("java.version", ""),
                ISO.format(Instant.ofEpochMilli(start)),
                start);
    }

    private static String prop(Environment env, String key) {
        try {
            String s = env.getProperty(key);
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
