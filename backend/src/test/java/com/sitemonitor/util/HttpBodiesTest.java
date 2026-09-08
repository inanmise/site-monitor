package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GÖVDE TAVANI SÖZLEŞMESİ.
 *
 * <p>Bu yardımcı, giden isteklerin yanıt gövdelerini tavanla okumak için var: üretim TEK POD
 * çalışıyor, dolayısıyla bir {@code OutOfMemoryError} doğrudan kesinti demek. Hedeflerin bir
 * kısmı yönetici tarafından ayarlanabiliyor (RDAP/WHOIS adresleri), bir kısmı ise izlenen
 * sunucunun sertifikasından okunuyor (OCSP/CRL adresleri) — yani karşı tarafın yazdığı dizeler.
 *
 * <p><b>Bu test neden AYRI dosyada.</b> Tavanı yalnız uçtan uca (CRL indirme) test etmek
 * yeterli DEĞİL: tavansız bırakıldığında dev gövde tamamen okunuyor ama ayrıştırma yine
 * düştüğü için sonuç aynı çıkıyor — test yeşil kalıyor ve hiçbir şey pinlemiyor. Bu dosya
 * tavanı bulunduğu yerde ölçer: tavan kaldırılırsa BURASI kırılır.
 *
 * <p>Kırpma DEĞİL, açık hata: bu gövdeler ayrıştırılıyor; yarıda kesilmiş bir gövde
 * "geçersiz JSON/CRL" gibi görünür ve asıl neden (yanıt çok büyük) teşhiste kaybolurdu.
 */
class HttpBodiesTest {

    private static InputStream stream(int bytes) {
        return new ByteArrayInputStream(new byte[bytes]);
    }

    @Test
    @DisplayName("Tavanin ALTINDA govde aynen doner")
    void underCap_returnsBody() throws IOException {
        byte[] out = HttpBodies.readCapped(stream(100), 1024, "TEST");
        assertThat(out).hasSize(100);
    }

    @Test
    @DisplayName("Tavana TAM ESIT govde kabul edilir (sinir kapsayici)")
    void exactlyAtCap_isAccepted() throws IOException {
        byte[] out = HttpBodies.readCapped(stream(1024), 1024, "TEST");
        assertThat(out).hasSize(1024);
    }

    @Test
    @DisplayName("KAPI: tavani BIR bayt asan govde REDDEDILIR (kirpilmaz)")
    void oneByteOverCap_throws() {
        assertThatThrownBy(() -> HttpBodies.readCapped(stream(1025), 1024, "CRL"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CRL")
                .hasMessageContaining("çok büyük");
    }

    @Test
    @DisplayName("Cok buyuk govde de TAMAMEN okunmaz — tavan+1 bayttan fazlasi alinmaz")
    void hugeBody_readsOnlyUpToCapPlusOne() throws IOException {
        // Akisin ne kadarinin tuketildigini olcuyoruz: tavansiz bir okuma 10 MB'in tamamini
        // heap'e alirdi. Olculen sey tam olarak bu — "OOM'a yol acan davranis yok".
        final int cap = 64 * 1024;
        int[] consumed = {0};
        InputStream counting = new InputStream() {
            @Override public int read() { consumed[0]++; return 0; }
            @Override public int read(byte[] b, int off, int len) {
                // InputStream SOZLESMESI: len == 0 ise 0 donulur, -1 DEGIL. Ilk yazimda -1
                // donuyordu; readNBytes bunu erken EOF sanip tavanin ALTINDA duruyordu ve test
                // "hata bekleniyordu" diye kirildi — sahte akisin kendisi hataliydi.
                if (len == 0) return 0;
                int n = Math.min(len, 10 * 1024 * 1024 - consumed[0]);
                if (n <= 0) return -1;
                consumed[0] += n;
                return n;
            }
        };

        assertThatThrownBy(() -> HttpBodies.readCapped(counting, cap, "CRL"))
                .isInstanceOf(IOException.class);

        assertThat(consumed[0])
                .as("tavandan cok daha fazlasi okundu - bellek korumasi calismiyor")
                .isLessThanOrEqualTo(cap + 1);
    }

    @Test
    @DisplayName("Hata mesaji NE olduğunu soyler (teshis dogru yere gitsin)")
    void message_namesTheSource() {
        assertThatThrownBy(() -> HttpBodies.readCapped(stream(10), 1, "OCSP"))
                .hasMessageContaining("OCSP")
                .hasMessageContaining("1");
    }

    @Test
    @DisplayName("Bos govde hata degildir")
    void emptyBody_isFine() throws IOException {
        assertThat(HttpBodies.readCapped(stream(0), 1024, "TEST")).isEmpty();
    }

    @Test
    @DisplayName("Metin surumu ayni tavani uygular ve UTF-8 cozer")
    void stringVariant_decodesUtf8() throws IOException {
        byte[] tr = "çğıöşü".getBytes(StandardCharsets.UTF_8);
        assertThat(new String(HttpBodies.readCapped(new ByteArrayInputStream(tr), 1024, "TEST"),
                StandardCharsets.UTF_8)).isEqualTo("çğıöşü");
    }
}
