package com.sitemonitor.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Çalışan örneğin build + dağıtım kimliği — tek doğruluk kaynağı (Sürüm & Dağıtım Geçmişi, 2026-09-10).
 *
 * <p>Kaynaklar: sürüm {@link AppVersion} (env → VERSION dosyası → manifest); commit/build zamanı/imaj
 * sürümü Dockerfile {@code ENV APP_GIT_COMMIT/APP_BUILD_TIME/APP_IMAGE_VERSION}; ortam/helm/imaj ref/
 * config checksum Helm deployment env'leri; pod/düğüm Downward API ({@code POD_NAME}/{@code NODE_NAME});
 * instance {@link SchedulerService#instanceId()}. Hepsi BOŞ-toleranslı: compose/yerel koşumda değerler
 * boş kalır, hiçbir yer çökmez, ekran "bilinmiyor" der. Sır taşımaz.
 *
 * <p>Statik {@link #resolve(Environment)} StartupLogger gibi bean enjeksiyonu istemeyen yerler için;
 * bean örneği aynı anlık görüntüyü açılışta bir kez alır (değerler süreç ömrü boyunca sabittir).
 */
@Component
public class BuildInfo {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

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
    }

    private final Snapshot snapshot;

    public BuildInfo(Environment env) {
        this.snapshot = resolve(env);
    }

    public Snapshot get() { return snapshot; }

    public static Snapshot resolve(Environment env) {
        AppVersion.Resolved v = AppVersion.resolveWithSource(env);
        String podName = System.getenv().getOrDefault("POD_NAME", "");
        String environment = prop(env, "site.monitor.environment");
        if (environment.isBlank()) environment = podName.isBlank() ? "local" : "unknown";
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
