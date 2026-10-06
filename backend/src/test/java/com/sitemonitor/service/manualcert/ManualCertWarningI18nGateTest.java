package com.sitemonitor.service.manualcert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-06): manuel sertifika uyarı kodlarının HER birinin arayüzde TR + EN metni olmalı —
 * {@code mcert.warn.<KOD>} ({@code frontend/src/i18n/tr.js}, {@code en.js}). Arayüz kodu anahtara DİNAMİK çevirdiği için
 * frontend'in kullanılan-anahtar kapısı bunları göremez ({@code HttpDiagFindingsI18nGateTest} deseni).
 * Ayrıca kaynakta üretilen her kod katalogda olmalı (katalog dışı kod ekranda ham anahtar olarak görünürdü).
 */
class ManualCertWarningI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Path SRC = Path.of("src/main/java/com/sitemonitor/service/manualcert");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        return src.substring(start);
    }

    private static boolean hasKey(String dict, String key) {
        return dict.contains("'" + key + "'") || dict.contains("\"" + key + "\"");
    }

    @Test
    @DisplayName("katalog sözleşmedeki 28 kodu taşır, tekrar yok")
    void catalogMatchesContract() {
        assertThat(CertificateFileParser.WARNING_CODES).hasSize(28).doesNotHaveDuplicates()
                .contains("PRIVATE_KEY_IGNORED", "ZIP_LIMIT", "CHAIN_INCOMPLETE", "SAN_CHANGED", "OLDER_THAN_CURRENT");
    }

    @Test
    @DisplayName("SÖZLEŞME: her uyarı kodunun TR ve EN 'mcert.warn.<KOD>' anahtarı var")
    void everyWarningCode_hasTrAndEnKeys() throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        List<String> missing = new ArrayList<>();
        for (String code : CertificateFileParser.WARNING_CODES) {
            String key = "mcert.warn." + code;
            if (!hasKey(tr, key)) missing.add("TR " + key);
            if (!hasKey(en, key)) missing.add("EN " + key);
        }
        assertThat(missing).as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("kaynakta üretilen her uyarı kodu katalogda")
    void everyEmittedCode_isCatalogued() throws IOException {
        Pattern p = Pattern.compile("new Warning\\(\\s*\"([A-Z_]+)\"|\\?\\s*\"(KEY_SAME)\"\\s*:\\s*\"(KEY_CHANGED)\"");
        Set<String> emitted = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = p.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) emitted.add(m.group(g));
                }
            }
        }
        assertThat(emitted).as("tarama hiçbir kod bulamadı — desen bozulmuş olabilir").hasSizeGreaterThan(20);
        assertThat(CertificateFileParser.WARNING_CODES).containsAll(emitted);
    }
}
