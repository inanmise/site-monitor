package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HER k6 çağrısı kendi EFEMER API portunu almalı.
 *
 * <p>k6 her koşumda {@code localhost:6565} üzerinde bir REST API açar. Senaryo kontrolleri
 * EŞZAMANLI koştuğu için ilk süreç portu alıyor, sonrakiler alamayıp
 * {@code level=warning msg="Error from API server" ... bind: address already in use} yazıyordu.
 * Koşum etkilenmiyordu (checks %100) ama uyarı, kullanıcıya gösterilen "Teknik detay" çıktısının
 * İLK satırında duruyor ve SAĞLIKLI bir koşumu bozuk gösteriyordu — kullanıcı bunu sordu.
 *
 * <p>Bu API'yi projede hiçbir yer kullanmıyor; uyarı kullanmadığımız bir özelliğin
 * açılamamasından geliyordu.
 *
 * <p><b>Neden KAYNAK taraması:</b> {@code ProcessProbe.run} statik ve komut satırı süreç
 * başlatılmadan görünmüyor; onu yakalamak için servisi yeniden yapılandırmak gerekirdi.
 * Kapının işi küçük ve net: bayrak bir k6 çağrısından düşerse fark edilsin. ({@code
 * IdentityLeakGuardTest} de aynı desende kaynak tarıyor.)
 */
class K6ApiAddressTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/sitemonitor/service/ScriptedCheckerService.java");

    @Test
    @DisplayName("k6 'run' ve 'archive' çağrılarının İKİSİ de --address taşır")
    void everyK6InvocationPassesAddress() throws Exception {
        String src = Files.readString(SERVICE);

        // Her k6Bin() çağrısı bir komut kurulumunun başlangıcıdır; o bloğun sonuna (script
        // dosyasının eklendiği satıra) kadar --address geçmeli.
        List<String> missing = new ArrayList<>();
        int from = 0;
        int found = 0;
        while (true) {
            int i = src.indexOf("k6Bin()", from);
            if (i < 0) break;
            // Tanımın kendisi (private String k6Bin()) sayılmaz.
            int lineStart = src.lastIndexOf('\n', i) + 1;
            String line = src.substring(lineStart, src.indexOf('\n', i));
            if (line.contains("private String k6Bin()")) { from = i + 7; continue; }

            found++;
            int blockEnd = src.indexOf("args.add(scriptFile", i);
            String block = blockEnd > i ? src.substring(i, blockEnd) : src.substring(i);
            if (!block.contains("addApiAddress(args)")) {
                missing.add(line.trim());
            }
            from = blockEnd > i ? blockEnd : i + 7;
        }

        assertThat(found).as("k6 komut kurulumu bulunamadı — tarama bozulmuş olabilir")
                .isGreaterThanOrEqualTo(2);
        assertThat(missing).as("addApiAddress(args) çağırmayan k6 komutu: eşzamanlı koşumda 6565 çakışır")
                .isEmpty();
    }

    /**
     * {@code :0} ŞART: işletim sistemi boş bir port seçer, yani çakışma imkânsız olur.
     *
     * <p>Sabit bir port yazmak sorunu geri getirirdi. Ölçüldü (k6 v0.49.0): bağlanamayacak bir
     * adres verilince k6 {@code exit 106} ile ÖLÜMCÜL hata veriyor — yani bu değer yanlışsa
     * senaryo kontrollerinin tamamı düşer, sessizce bozulmaz.
     */
    @Test
    @DisplayName("Adres EFEMER porttur (:0) — sabit port çakışmayı geri getirirdi")
    void addressUsesEphemeralPort() throws Exception {
        String src = Files.readString(SERVICE);

        assertThat(src).contains("K6_API_ADDRESS = \"127.0.0.1:0\"");
    }

    /**
     * Ayar BOŞ verilince bayrak HİÇ eklenmemeli — ortamda 16 senaryo izlemesi var ve bu bayrak
     * hem {@code run} hem {@code archive} yolunda. Beklenmedik bir k6 sürümünde ops tek ayarla
     * eski davranışa dönebilmeli; kapı o çıkışın kapanmasını engeller.
     */
    @Test
    @DisplayName("Boş ayar = bayrak eklenmez (yeniden dağıtımsız geri dönüş)")
    void blankSettingSkipsFlagEntirely() throws Exception {
        String src = Files.readString(SERVICE);

        int i = src.indexOf("private void addApiAddress(");
        assertThat(i).as("addApiAddress yardımcısı bulunamadı").isGreaterThan(0);
        int end = src.indexOf("private String k6ApiAddress", i);
        assertThat(end).as("k6ApiAddress() okuyucusu bulunamadı").isGreaterThan(i);
        String body = src.substring(i, end);

        assertThat(body)
                .as("boş ayar kontrolü yok: geri dönüş kapısı kapanmış olur")
                .contains("isBlank()");
        assertThat(body)
                .as("ayar okunmadan sabit adres kullanılıyor")
                .contains("k6ApiAddress()");
    }
}
