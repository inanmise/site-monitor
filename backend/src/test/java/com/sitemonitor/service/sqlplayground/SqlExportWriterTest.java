package com.sitemonitor.service.sqlplayground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Dışa aktarım yazıcısı: UTF-8 bayt sınırı (inceleme DÜŞÜK 7) ve açılıştaki kalıntı dosya temizliği. */
class SqlExportWriterTest {

    @Test
    @DisplayName("Sınır UTF-8 BAYT sayar: çok baytlı metin karakter sayısının 3 katı yer tutar, sınırda okuma durur")
    void byteLimitCountsUtf8() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String cell = "ş".repeat(100) + "€".repeat(100) + "😀".repeat(50);   // 200 + 300 + 200 bayt
        try (SqlExportWriter w = new SqlExportWriter("csv", bos)) {
            w.setByteLimit(2_000);
            w.columns(new String[]{"c"});
            int written = 0;
            while (w.row(new Object[]{cell})) written++;
            assertThat(w.byteLimitHit()).isTrue();
            assertThat(written).isEqualTo(2);   // 3. satırda ~2100 bayt > 2000 (karakter sayısıyla ~900 olurdu)
            w.finish(written, true, 10, List.of());
        }
        byte[] bytes = bos.toByteArray();
        assertThat(bytes.length).isBetween(2_000, 2_300);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("şşş");
    }

    @Test
    @DisplayName("utf8Len: ASCII 1, Latin genişletilmiş 2, BMP 3, vekil yarısı 2 (çift 4)")
    void utf8Len() {
        assertThat(SqlExportWriter.CountingWriter.utf8Len('a')).isEqualTo(1);
        assertThat(SqlExportWriter.CountingWriter.utf8Len('ş')).isEqualTo(2);
        assertThat(SqlExportWriter.CountingWriter.utf8Len('€')).isEqualTo(3);
        assertThat(SqlExportWriter.CountingWriter.utf8Len('\uD83D') + SqlExportWriter.CountingWriter.utf8Len('\uDE00'))
                .isEqualTo("😀".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Kalıntı temizliği: yalnız önekli VE eski dosyalar silinir")
    void cleanupStaleTempFiles(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path oldExport = Files.writeString(dir.resolve(SqlExportWriter.TEMP_PREFIX + "1.csv"), "x");
        Path freshExport = Files.writeString(dir.resolve(SqlExportWriter.TEMP_PREFIX + "2.json"), "x");
        Path oldOther = Files.writeString(dir.resolve("other-1.csv"), "x");
        Files.setLastModifiedTime(oldExport, FileTime.fromMillis(now - 2 * 3_600_000L));
        Files.setLastModifiedTime(oldOther, FileTime.fromMillis(now - 2 * 3_600_000L));
        int n = SqlExportWriter.cleanupStaleTempFiles(dir, 3_600_000L, now, 10_000);
        assertThat(n).isEqualTo(1);
        assertThat(oldExport).doesNotExist();
        assertThat(freshExport).exists();
        assertThat(oldOther).exists();
        assertThat(SqlExportWriter.cleanupStaleTempFiles(dir.resolve("yok"), 1, now, 10)).isZero();
    }
}
