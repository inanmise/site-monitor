package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 REST API adresi kuralı.
 *
 * <p>k6 2.0'dan ESKİ sürümler her koşumda {@code localhost:6565} üzerinde bir REST API açar. Senaryo kontrolleri
 * EŞZAMANLI koştuğu için ilk süreç portu alıyor, sonrakiler {@code bind: address already in use} uyarısını "Teknik
 * detay"ın ilk satırına basıyordu → {@code --address 127.0.0.1:0} (efemer port) verilir.
 *
 * <p><b>2026-10-29 (k6 0.49 → 2.3 geçişi):</b> 2.0+ REST API'yi varsayılanda AÇMAZ ve {@code --address} vermek onu
 * AÇAR (gereksiz bir dinleyen port — ölçüldü: 0.49 bayraksız 6565'i dinliyor, 2.3 dinlemiyor). Kural artık "koşacak
 * ikilinin profiline göre": {@link K6Profile#needsApiAddress()}. Bu kapı her k6 komut kurulumunun profili
 * {@code addApiAddress(args, <profil>)} ile verdiğini, sabit {@code --address} eklemediğini denetler.
 */
class K6ApiAddressTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/sitemonitor/service/ScriptedCheckerService.java");

    @Test
    @DisplayName("k6 'run' ve 'archive' komut kurulumlarının HER biri addApiAddress(args, <profil>) çağırır")
    void everyK6InvocationPassesProfile() throws Exception {
        String src = Files.readString(SERVICE);
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (String marker : new String[] { "\"run\", \"--quiet\"", "\"archive\", \"--quiet\"" }) {
            int from = 0;
            while (true) {
                int i = src.indexOf(marker, from);
                if (i < 0) break;
                found++;
                int blockEnd = src.indexOf("args.add(scriptFile", i);
                String block = blockEnd > i ? src.substring(i, blockEnd) : src.substring(i);
                if (!block.matches("(?s).*addApiAddress\\(args, \\w+\\);.*")) missing.add(marker);
                from = i + marker.length();
            }
        }
        assertThat(found).as("k6 komut kurulumu bulunamadı — tarama bozulmuş olabilir").isGreaterThanOrEqualTo(2);
        assertThat(missing).as("addApiAddress(args, profil) çağırmayan k6 komutu").isEmpty();
        assertThat(src).as("sabit --address ekleyen bir yol olmamalı (profil kararını atlar)")
                .doesNotContain("args.add(\"--address\"); args.add(K6_API_ADDRESS)");
    }

    @Test
    @DisplayName("Adres EFEMER porttur (:0) — sabit port 2.0 öncesi sürümlerde çakışmayı geri getirirdi")
    void addressUsesEphemeralPort() throws Exception {
        assertThat(Files.readString(SERVICE)).contains("K6_API_ADDRESS = \"127.0.0.1:0\"");
    }

    @Test
    @DisplayName("Boş ayar = bayrak eklenmez; 2.0+ profilde de eklenmez")
    void blankSettingOrModernProfileSkipsFlag() throws Exception {
        String src = Files.readString(SERVICE);
        int i = src.indexOf("private void addApiAddress(");
        assertThat(i).as("addApiAddress yardımcısı bulunamadı").isGreaterThan(0);
        int end = src.indexOf("private String k6ApiAddress", i);
        String body = src.substring(i, end);
        assertThat(body).contains("isBlank()").contains("k6ApiAddress()").contains("needsApiAddress()");
    }
}
