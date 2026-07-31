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
        if (env != null) {
            String p = env.getProperty("cert.monitor.version");
            if (p != null && !p.isBlank()) return p.trim();
        }
        for (String c : CANDIDATES) {
            try {
                Path path = Path.of(c);
                if (Files.isRegularFile(path)) {
                    String v = Files.readString(path).trim();
                    if (!v.isBlank()) return v;
                }
            } catch (Exception ignored) { /* dosya yoksa/okunamıyorsa sıradaki adaya geç */ }
        }
        try {
            String v = AppVersion.class.getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        } catch (Exception ignored) { /* manifest yoksa */ }
        return "unknown";
    }
}
