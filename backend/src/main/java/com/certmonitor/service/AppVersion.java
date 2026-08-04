package com.certmonitor.service;

import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uygulama sürümünü çözer. Gerçek sürüm kök {@code VERSION} dosyasında tutulur (release CI bump'lar; Docker imajında
 * {@code /app/VERSION}). Çözüm sırası: {@code cert.monitor.version} property (env override kancası) → aday dosyalar →
 * jar manifest {@code Implementation-Version} → {@code "unknown"}. Asla istisna fırlatmaz.
 */
public final class AppVersion {

    private AppVersion() {}

    /** Docker: user.dir=/app → ./VERSION; yerel: backend'ten ../VERSION; mutlak: /app/VERSION. */
    private static final String[] CANDIDATES = { "VERSION", "../VERSION", "/app/VERSION" };

    public static String resolve(Environment env) {
        return resolveWithSource(env).version();
    }

    /** Sürüm + hangi yoldan çözüldüğü (StartupLogger kaynak etiketi: env | file | manifest | none). */
    public static Resolved resolveWithSource(Environment env) {
        if (env != null) {
            String p = env.getProperty("cert.monitor.version");
            if (p != null && !p.isBlank()) return new Resolved(p.trim(), "env");
        }
        for (String c : CANDIDATES) {
            try {
                Path path = Path.of(c);
                if (Files.isRegularFile(path)) {
                    String v = Files.readString(path).trim();
                    if (!v.isBlank()) return new Resolved(v, "file");
                }
            } catch (Exception ignored) { /* dosya yoksa/okunamıyorsa sıradaki adaya geç */ }
        }
        try {
            // Manifest = pom <version>; release CI bunu bump'lamıyor → sürüm BAYAT olabilir.
            String v = AppVersion.class.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) return new Resolved(v, "manifest");
        } catch (Exception ignored) { /* manifest yoksa */ }
        return new Resolved("unknown", "none");
    }

    /** Çözülen sürüm ve kaynağı. */
    public record Resolved(String version, String source) {}
}
