package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak dosyalarda GÖRÜNMEZ kontrol karakteri olmasın.
 *
 * <p>2026-08-16'da düzenleme sırasında iki Java dosyasına <b>NUL baytı</b> sızdı: ayırıcı olarak
 * yazılan karakter {@code "\0"} olarak dosyaya düştü. Kod derlendi, testler yeşil kaldı ve PDF
 * doğru üretildi — çünkü NUL geçerli bir ayırıcıydı. Fark edilmesinin tek sebebi {@code grep}'in
 * dosyayı "binary" sayması oldu.
 *
 * <p>Böyle bir bayt üç şekilde zarar verir: ekranda ve incelemede GÖRÜNMEZ, git/grep dosyayı ikili
 * sayıp diff'i kapatır, ve herhangi bir editör onu sessizce başka bir şeye çevirebilir. Bilerek
 * kontrol karakteri gerekiyorsa {@code '\\u001F'} gibi KAÇIŞLA yazılmalı — ham bayt olarak değil.
 *
 * <p>Sekme, satırbaşı ve satırsonu (0x09/0x0A/0x0D) normaldir ve muaftır.
 */
class SourceControlCharTest {

    /**
     * Ham kontrol baytı taşıması KABUL EDİLEN dosyalar.
     *
     * <p>Bu liste bilinçli ve gerekçeli olmalı; büyümesi gereken bir liste değildir. Yeni bir
     * girdi eklemeden önce karakteri {@code \\uXXXX} kaçışıyla yazmayı dene — neredeyse her zaman
     * mümkündür.
     */
    private static final Set<String> ALLOWED = Set.of(
            // 0x1F unit separator, sabit olarak tanımlı ve yorumla belgelenmiş kanonik alan ayırıcısı
            "AuditService.java",
            // LDAP filtre kaçışının NUL davranışını sınayan test — girdi olarak NUL'un kendisi gerekiyor
            "LdapDirectoryServiceTest.java");

    @Test
    @DisplayName("Java kaynaklarında ham kontrol karakteri olmamalı (sekme/satırsonu hariç)")
    void javaSourcesHaveNoRawControlCharacters() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : new String[] { "src/main/java", "src/test/java" }) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            if (ALLOWED.contains(name)) return;
                            try {
                                byte[] data = Files.readAllBytes(p);
                                int line = 1;
                                for (byte b : data) {
                                    if (b == '\n') { line++; continue; }
                                    if (b == '\t' || b == '\r') continue;
                                    if (b >= 0 && b < 0x20) {
                                        violations.add(String.format("%s:%d → 0x%02X", p, line, b));
                                        return;   // dosya başına tek bulgu yeter
                                    }
                                }
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            }
        }
        assertThat(violations)
                .as("Ham kontrol karakteri bulundu: %s%n"
                        + "Bu baytlar ekranda GÖRÜNMEZ, git dosyayı ikili sayar ve diff kapanır. "
                        + "Gerçekten gerekiyorsa '\\uXXXX' kaçışıyla yazın; bilinçli bir istisnaysa "
                        + "SourceControlCharTest.ALLOWED listesine gerekçesiyle ekleyin.", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesindeki dosyalar hâlâ var — bayatlamış istisna sessizce kalmasın")
    void allowListEntriesStillExist() throws IOException {
        List<String> missing = new ArrayList<>(ALLOWED);
        for (String root : new String[] { "src/main/java", "src/test/java" }) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                files.map(p -> p.getFileName().toString()).forEach(missing::remove);
            }
        }
        assertThat(missing)
                .as("Muafiyet listesinde artık var olmayan dosyalar: %s — listeden çıkarın", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("i18n paketi de temiz olmalı — çeviri dosyasına sızan NUL tüm arayüzü etkiler")
    void frontendI18nHasNoRawControlCharacters() throws IOException {
        Path i18n = Path.of("../frontend/src/i18n/index.jsx");
        if (!Files.exists(i18n)) return;   // backend tek başına çıkarıldığında atla
        byte[] data = Files.readAllBytes(i18n);
        List<String> violations = new ArrayList<>();
        int line = 1;
        for (byte b : data) {
            if (b == '\n') { line++; continue; }
            if (b == '\t' || b == '\r') continue;
            if (b >= 0 && b < 0x20) violations.add(String.format("i18n/index.jsx:%d → 0x%02X", line, b));
        }
        assertThat(violations).as("i18n paketinde ham kontrol karakteri: %s", violations).isEmpty();
        assertThat(new String(data, StandardCharsets.UTF_8)).isNotEmpty();
    }
}
