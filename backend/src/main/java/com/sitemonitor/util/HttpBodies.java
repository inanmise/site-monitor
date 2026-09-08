package com.sitemonitor.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Giden HTTP yanıt gövdelerini TAVANLI okumak için ortak yardımcı.
 *
 * <p><b>Neden var.</b> {@code HttpResponse.BodyHandlers.ofString()} tavansızdır: hedef sunucu
 * ne gönderirse tamamı heap'e alınır. Üretim TEK POD çalıştığı için bir {@code OutOfMemoryError}
 * doğrudan kesinti demektir. Projede tavan zaten standarttı — {@code WebhookService} 8 KB,
 * {@code UserPushService} 64 KB okuyor — ama RDAP/WHOIS/GeoIP istemcileri bu desene hiç
 * geçmemişti. Üstelik oradaki hedeflerin bir kısmı YÖNETİCİ TARAFINDAN AYARLANABİLİR
 * ({@code rdap-bootstrap-url}, {@code tr-web-whois-providers}), yani yanlış ya da ele geçmiş
 * tek bir adres tüm uygulamayı düşürebilirdi.
 *
 * <p><b>Neden sessizce kırpmıyor.</b> Webhook gövdesi yalnız günlüğe yazılıyor, orada kırpmak
 * zararsız. Buradaki gövdeler ise AYRIŞTIRILIYOR (RDAP JSON, WHOIS metni): yarıda kesilmiş bir
 * gövde "geçersiz JSON" gibi görünür ve asıl neden (yanıt çok büyük) kaybolur. Tavan aşılırsa
 * bu yüzden açık bir {@link IOException} atılır — çağıranın hata yolu zaten var ve mesaj
 * teşhisi doğru yere götürür.
 */
public final class HttpBodies {

    private HttpBodies() {}

    /**
     * Gövdeyi en fazla {@code maxBytes} bayt olarak okur.
     *
     * @throws IOException gövde tavanı AŞARSA (kısmi içerik döndürülmez) ya da okuma hatasında
     */
    public static String readCapped(HttpResponse<InputStream> response, int maxBytes, String what)
            throws IOException {
        try (InputStream is = response.body()) {
            return new String(readCapped(is, maxBytes, what), StandardCharsets.UTF_8);
        }
    }

    /**
     * Aynı tavan, ham bayt olarak — {@code HttpURLConnection} kullanan ve gövdeyi METİN değil
     * İKİLİ ayrıştıran çağıranlar için (OCSP yanıtı, DER/PEM CRL).
     *
     * <p>Akışı KAPATMAZ: {@code HttpURLConnection} çağıranları bağlantıyı kendi
     * {@code try-with-resources}/{@code disconnect()} akışlarında yönetiyor.
     *
     * @throws IOException gövde tavanı AŞARSA (kısmi içerik döndürülmez) ya da okuma hatasında
     */
    public static byte[] readCapped(InputStream body, int maxBytes, String what) throws IOException {
        // Tavandan BİR fazlasını iste: dönen uzunluk tavanı geçiyorsa gövde kesilmiş demektir.
        byte[] buf = body.readNBytes(maxBytes + 1);
        if (buf.length > maxBytes) {
            throw new IOException(what + " yanıt gövdesi çok büyük (> " + maxBytes
                    + " bayt) — okuma reddedildi");
        }
        return buf;
    }
}
