package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-01): {@link AlertNoiseSuggestion} kataloğundaki HER kodun arayüzde TR + EN metni olmalı —
 * {@code noise.sug.<KOD>.title} ve {@code noise.sug.<KOD>.body} ({@code frontend/src/i18n/tr.js} ve {@code en.js});
 * her eylem ipucunun ({@code open_monitor} …) da {@code noise.sug.action.<eylem>} etiketi. Arayüz kodu i18n anahtarına
 * DİNAMİK çevirir ({@code t('noise.sug.' + code + '.title', …)}), bu yüzden frontend'in used-keys kapısı onları
 * göremez: eksik anahtar arayüzde ham {@code noise.sug.X.title} olarak görünürdü. TR ve EN sözlükleri 2026-10-02'den
 * beri ayrı dosyalarda (performans önerisi 22: EN ayrı/lazy chunk); her sözlük KENDİ dosyasında, tanım satırından
 * itibaren aranır (eskiden tek dosyanın {@code export const EN} öncesi/sonrası yarıları).
 */
class AlertNoiseSuggestionI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Set<String> ACTIONS = Set.of("open_monitor", "open_alerts", "open_settings", "open_maintenance");

    /** Sözlük dosyasını okur ve tanım satırından ({@code export const TR} / {@code EN}) itibaren döner. */
    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        assertThat(src.indexOf(marker, start + 1)).as(file + " tek sözlük içermeli").isEqualTo(-1);
        return src.substring(start);
    }

    @Test
    @DisplayName("SOZLESME: her oneri kodunun TR ve EN title/body anahtari, her eylemin etiketi var")
    void everySuggestionCode_hasTrAndEnKeys() throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");

        List<String> missing = new ArrayList<>();
        for (AlertNoiseSuggestion s : AlertNoiseSuggestion.values()) {
            for (String suffix : List.of(".title", ".body")) {
                String key = "'" + s.titleKey() + suffix + "'";
                if (!tr.contains(key)) missing.add("TR " + key);
                if (!en.contains(key)) missing.add("EN " + key);
            }
            assertThat(ACTIONS).as("eylem ipucu kataloğu: " + s).contains(s.action);
            assertThat(s.severity).isIn("HIGH", "MEDIUM", "INFO");
        }
        for (String a : ACTIONS) {
            String key = "'noise.sug.action." + a + "'";
            if (!tr.contains(key)) missing.add("TR " + key);
            if (!en.contains(key)) missing.add("EN " + key);
        }
        for (String sev : List.of("HIGH", "MEDIUM", "INFO")) {
            String key = "'noise.sug.sev." + sev + "'";
            if (!tr.contains(key)) missing.add("TR " + key);
            if (!en.contains(key)) missing.add("EN " + key);
        }
        assertThat(missing).as("eksik i18n anahtarları").isEmpty();
    }
}
