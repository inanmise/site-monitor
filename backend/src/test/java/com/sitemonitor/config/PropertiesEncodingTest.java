package com.sitemonitor.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java .properties dosyaları ISO-8859-1 ile okunur; değer satırına ham UTF-8 Türkçe
 * karakter yazılırsa çalışma zamanında çift kodlanır (örn. "SiteMonitor" → UI'da
 * "Site MonitÃ¶r"). Non-ASCII değerler \\uXXXX escape ile yazılmalı — bu test tüm
 * application*.properties dosyalarının yorum-olmayan satırlarında ham non-ASCII bayt
 * bulunmadığını doğrular (yorum satırları serbest: Spring onları hiç değerlendirmez).
 */
class PropertiesEncodingTest {

    @Test
    void propertyValuesMustBeAsciiOrUnicodeEscaped() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("application") && n.endsWith(".properties");
            }).forEach(p -> {
                try {
                    byte[] raw = Files.readAllBytes(p);
                    String[] lines = new String(raw, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (line.stripLeading().startsWith("#")) continue;
                        for (byte b : line.getBytes(StandardCharsets.UTF_8)) {
                            if ((b & 0xFF) > 0x7F) {
                                violations.add(p.getFileName() + ":" + (i + 1) + " → " + line.strip());
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(violations)
                .withFailMessage("Property değerlerinde ham non-ASCII karakter var — \\uXXXX escape kullanın:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }
}
