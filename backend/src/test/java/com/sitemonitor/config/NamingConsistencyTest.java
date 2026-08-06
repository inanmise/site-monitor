package com.sitemonitor.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rename bekçisi (CertMonitor → SiteMonitor): üretim ağacına (src/main) eski marka
 * kimliklerinin sessizce geri sızmasını engeller. scripts/check-brand.(sh|ps1)'in
 * derleme kapısına bağlanmış hali — betik elle koşulur, bu test her verify'da koşar.
 *
 * Bilinçli istisnalar (sınıf A — değiştirmek ortamı/geriye uyumu kırar):
 *  - "geriye-uyum" yorumlu satırlar: eski CERT_MONITOR_* env alias zincirleri ve
 *    app_settings anahtar göçü SQL'i — 2 sürüm sonra kaldırılacak geçiş mekanizması.
 *  - "${CERT_MONITOR_" içeren satırlar: alias fallback'in kendisi (yorum bitişik satırda).
 *  - Yerel/dev PostgreSQL veritabanı adı-kullanıcısı "certmonitor" (":certmonitor}" ve
 *    "/certmonitor" desenleri): DB rename kapsam dışı — mevcut kurulumları kırar.
 */
class NamingConsistencyTest {

    private static final String[] BANNED = { "certmonitor", "cert-monitor", "cert_monitor", "cert.monitor" };

    private static boolean isException(String line) {
        return line.contains("geriye-uyum")
                || line.contains("${CERT_MONITOR_")
                || line.contains(":certmonitor}")
                || line.contains("/certmonitor");
    }

    @Test
    void productionTreeMustNotContainLegacyBrandTokens() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : new String[] { "src/main/java", "src/main/resources" }) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".png") || name.endsWith(".ico") || name.endsWith(".woff2")) return;
                    try {
                        String[] lines = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            String lower = lines[i].toLowerCase(Locale.ROOT);
                            for (String banned : BANNED) {
                                if (lower.contains(banned) && !isException(lines[i])) {
                                    violations.add(p + ":" + (i + 1) + " → " + lines[i].strip());
                                    break;
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        assertThat(violations)
                .withFailMessage("Eski marka kimliği üretim ağacına geri sızmış — site-monitor/site.monitor kullanın:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }
}
