package com.sitemonitor.service.dbgrowth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-11-13): {@link DbGrowthAdvisor#CODES} içindeki HER öneri kodunun arayüzde TR + EN başlığı ve açıklaması olmalı
 * ({@code dbg.adv.<KOD>.title|body}); her eylem ({@code dbg.action.<eylem>}) ve önem ({@code dbg.sev.<önem>}) etiketi de.
 * Arayüz kodu anahtara DİNAMİK çevirir — frontend'in kullanılan-anahtar kapısı göremez, eksik anahtar ham görünürdü.
 */
class DbGrowthAdvisorI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "'").isGreaterThanOrEqualTo(0);
        return src.substring(start);
    }

    @Test
    @DisplayName("her öneri kodunun, eylemin ve önemin TR + EN anahtarı var")
    void everyCodeHasKeys() throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        List<String> keys = new ArrayList<>();
        for (String c : DbGrowthAdvisor.CODES) {
            keys.add("dbg.adv." + c + ".title");
            keys.add("dbg.adv." + c + ".body");
        }
        for (String a : DbGrowthAdvisor.ACTIONS) keys.add("dbg.action." + a);
        for (String s : DbGrowthAdvisor.SEVERITIES) keys.add("dbg.sev." + s);
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (!tr.contains("'" + k + "'") && !tr.contains("\"" + k + "\"")) missing.add("TR " + k);
            if (!en.contains("'" + k + "'") && !en.contains("\"" + k + "\"")) missing.add("EN " + k);
        }
        assertThat(missing).as("eksik i18n anahtarları").isEmpty();
    }
}
