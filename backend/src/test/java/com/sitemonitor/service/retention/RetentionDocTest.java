package com.sitemonitor.service.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doküman-kod senkronu. {@code docs/RETENTION_POLITIKASI.md} kataloğun birebir çıktısı olmalı.
 * Sapma varsa test kırılır ve dosyanın nasıl yeniden üretileceğini söyler — böylece dokümanın
 * koddan ayrı yaşaması (docs/db-scaling.md'nin {@code cert.monitor.*} kalıntısı gibi) imkânsızlaşır.
 */
class RetentionDocTest {

    /** backend/ dizininden çalıştırıldığı için bir üst dizin. */
    private static final Path DOC = Path.of("..", "docs", "RETENTION_POLITIKASI.md");

    @Test
    @DisplayName("docs/RETENTION_POLITIKASI.md katalogla senkron (elle düzenlenmez, üretilir)")
    void docMatchesCatalog() throws IOException {
        String expected = RetentionDocGenerator.generate();
        assertThat(Files.exists(DOC))
                .as("Doküman yok. Üretmek için: RetentionDocGenerator.generate() çıktısını %s dosyasına yazın.", DOC)
                .isTrue();
        String actual = Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("Doküman katalogdan sapmış. RetentionDocGenerator.generate() çıktısıyla değiştirin "
                    + "(elle düzenlemeyin — kaynak RetentionCatalog.ALL).")
                .isEqualTo(expected.replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("Üretilen doküman her politikayı ve legal hold anahtarını içerir")
    void generatedDocCoversEveryPolicy() {
        String doc = RetentionDocGenerator.generate();
        for (RetentionPolicy p : RetentionCatalog.ALL) {
            assertThat(doc).as("dokümanda eksik tablo: %s", p.table()).contains("`" + p.table() + "`");
        }
        assertThat(doc).contains(RetentionCatalog.HOLD_KEY).contains("Uyum onayı");
    }
}
