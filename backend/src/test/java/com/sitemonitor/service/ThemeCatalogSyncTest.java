package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-05): sunucunun tema listesi ({@link ThemeCatalog#ALL}) ile ön yüzün listesi
 * ({@code frontend/src/theme/themes.js} {@code THEMES}) AYNI kimlik + şema + sıra.
 *
 * <p>Ayrışırsa: yönetici sunucunun tanımadığı bir temayı açamaz (400), ön yüzün tanımadığı bir tema seçicide boş satır
 * olur ya da bir koyu tema açık şemayla boyanır (okunamaz metin). {@code inventory-flags-sync} kapısının deseni; kardeşi
 * ön yüzde {@code src/test/theme-catalog-sync.test.js}.
 */
class ThemeCatalogSyncTest {

    private static final Path THEMES_JS = Path.of("../frontend/src/theme/themes.js");

    /** {@code { id: 'blueprint', scheme: 'dark', … }} — THEMES dizisindeki her girdi. */
    private static final Pattern ENTRY = Pattern.compile("\\{\\s*id:\\s*'([a-z]+)'\\s*,\\s*scheme:\\s*'(light|dark)'");

    @Test
    @DisplayName("ön yüz THEMES ile ThemeCatalog.ALL aynı kimlik, şema ve sırada")
    void frontendThemes_matchCatalog() throws IOException {
        String src = Files.readString(THEMES_JS, StandardCharsets.UTF_8);
        int start = src.indexOf("export const THEMES");
        assertThat(start).as("themes.js içinde THEMES dizisi bulunamadı").isGreaterThanOrEqualTo(0);
        int end = src.indexOf("]", start);
        Matcher m = ENTRY.matcher(src.substring(start, end));
        List<String> front = new ArrayList<>();
        while (m.find()) front.add(m.group(1) + ":" + m.group(2));

        List<String> back = ThemeCatalog.ALL.stream().map(t -> t.id() + ":" + t.scheme()).toList();
        assertThat(front).as("tarayıcı boş döndü — desen ya da dosya bayatlamış").isNotEmpty();
        assertThat(front).isEqualTo(back);
    }
}
