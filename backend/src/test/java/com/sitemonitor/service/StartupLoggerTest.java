package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StartupLoggerTest {

    @Test
    @DisplayName("parseLocations: virgülle ayrılır, boşluk/boş eleman temizlenir")
    void parseLocations_trimsAndDropsEmpty() {
        assertThat(StartupLogger.parseLocations("file:./frontend/dist/, classpath:/static/ ,"))
                .containsExactly("file:./frontend/dist/", "classpath:/static/");
        assertThat(StartupLogger.parseLocations("")).isEmpty();
        assertThat(StartupLogger.parseLocations(null)).isEmpty();
    }

    @Test
    @DisplayName("findFileIndex: file: konumunda index.html varsa döner")
    void findFileIndex_foundInFileLocation(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<html></html>");
        String locations = "file:" + dir + "/,classpath:/static/";

        Optional<File> index = StartupLogger.findFileIndex(locations);

        assertThat(index).isPresent();
        assertThat(index.get().getName()).isEqualTo("index.html");
        assertThat(index.get().isFile()).isTrue();
    }

    @Test
    @DisplayName("findFileIndex: index.html yoksa veya yalnız classpath konumu varsa boş")
    void findFileIndex_missing(@TempDir Path dir) {
        // dist dizini var ama index.html yok
        assertThat(StartupLogger.findFileIndex("file:" + dir + "/")).isEmpty();
        // yalnız classpath konumu — file: taranmaz
        assertThat(StartupLogger.findFileIndex("classpath:/static/")).isEmpty();
        assertThat(StartupLogger.findFileIndex("")).isEmpty();
    }

    @Test
    @DisplayName("findFileIndex: ilk uygun file: konumunu seçer")
    void findFileIndex_picksFirstMatching(@TempDir Path dir) throws Exception {
        Path second = dir.resolve("second");
        Files.createDirectories(second);
        Files.writeString(second.resolve("index.html"), "<html></html>");
        // İlk konumda index yok, ikincide var → ikinci seçilir
        List<String> locs = List.of("file:" + dir + "/", "file:" + second + "/");
        String locations = String.join(",", locs);

        Optional<File> index = StartupLogger.findFileIndex(locations);

        assertThat(index).isPresent();
        assertThat(index.get().getParentFile().getName()).isEqualTo("second");
    }
}
