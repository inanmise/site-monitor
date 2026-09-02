package com.sitemonitor.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Denetim {@code detail} alanının tek üreticisi — <b>çıktı DAİMA geçerli bir JSON nesnesidir</b>.
 *
 * <p><b>Neden gerekti.</b> Bugüne kadar {@code detail} üç ayrı şeydi: JSON nesnesi, düz metin
 * ({@code "test → " + email}), ve {@code null}. Üç sonucu birden doğurdu:
 * <ul>
 *   <li><b>Ön yüzde sessiz veri kaybı.</b> Ekran {@code JSON.parse} deniyor, düz metinde patlıyor
 *       ve metni HİÇ göstermiyordu — yazılan ayrıntı kullanıcıya hiç ulaşmıyordu.</li>
 *   <li><b>Bozuk JSON.</b> Elle birleştirme ({@code "{\"name\":\"" + team.getName() + "\"}"})
 *       adında tırnak olan bir takımda geçersiz JSON üretiyor; kaçış kuralı her çağrı yerinde
 *       yeniden icat ediliyordu.</li>
 *   <li><b>Maskeleme sürüklenmesi.</b> Elle yazılan detay {@link SecretMask} kara-listesinden
 *       geçmiyordu; hassas bir alanı düz metin yazmak yalnız dikkate kalmıştı.</li>
 * </ul>
 *
 * <p>Değer kuralları {@link AuditDiff#safeVal} ile <b>birebir aynıdır</b> (maskeleme, ikili
 * daraltma, kimlik temizleme, uzunluk tavanı) — ikinci bir kopya kasıtlı olarak yazılmadı:
 * ayrışan iki kural, er ya da geç sırrı düz metin yazan taraf olurdu.
 *
 * <p><b>null değerler YAZILIR.</b> {@code {"locked_until_before":null}} ile alanın hiç olmaması
 * farklı iki şeydir: birincisi "baktık, boştu", ikincisi "bakmadık". Denetimde bu ayrım önemlidir.
 */
public final class AuditDetail {

    private AuditDetail() {}

    /**
     * Anahtar/değer çiftlerinden JSON nesnesi üretir: {@code of("host", h, "port", p)}.
     *
     * @param kv sırayla anahtar (String) ve değer; çift sayıda olmalı
     * @return JSON nesnesi; argüman yoksa {@code "{}"}
     * @throws IllegalArgumentException tek sayıda argüman ya da String olmayan anahtar (çağrı
     *         yerindeki dizilim hatası sessizce bozuk detay üretmesin — bu bir programlama hatası)
     */
    public static String of(Object... kv) {
        if (kv == null || kv.length == 0) return "{}";
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("AuditDetail.of: anahtar/değer çiftleri bekleniyor, "
                    + kv.length + " argüman verildi");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (!(kv[i] instanceof String k)) {
                throw new IllegalArgumentException("AuditDetail.of: " + i + ". argüman anahtar olmalı (String)");
            }
            m.put(k, kv[i + 1]);
        }
        return ofMap(m);
    }

    /** Hazır bir haritayı JSON nesnesine çevirir (aynı maskeleme/kırpma kuralları). */
    public static String ofMap(Map<String, ?> m) {
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(AuditDiff.jsonStr(e.getKey())).append(":")
              .append(AuditDiff.safeVal(e.getKey(), e.getValue()));
        }
        return sb.append("}").toString();
    }

    /**
     * Ad-hoc test/tanı uçlarında denetime yazılacak HEDEF adresi.
     *
     * <p>Hedef kaydedilir çünkü bu uçlar sunucudan dışarı bağlantı açtırır (SSRF yüzeyi):
     * hedefsiz bir satır — "alice bir HTTP testi koştu" — ucun var olma sebebi olan tek soruyu
     * cevaplayamaz: <i>birisi sunucumuzu 10.0.0.5:22'yi taramak için mi kullandı?</i>
     *
     * <p>Ama <b>query string düşürülür</b>: test edilen URL'ler {@code ?apiKey=}/{@code ?token=}
     * taşıyabiliyor ve denetim kaydı sırların yeni bir kopyası olmamalı. Kalan
     * {@code scheme://host[:port]/path} tanı için yeterli, sızıntı için değil. Gömülü
     * {@code //kullanıcı:parola@} da temizlenir.
     */
    public static String safeTarget(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return s;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s.contains("://") ? SecretMask.maskJdbcUrl(s) : s;
    }

    /**
     * Serbest metni tek alanlı bir nesneye sarar: {@code {"note":"…"}}.
     *
     * <p>Eski düz-metin çağrı yerlerini sözleşmeye taşımanın en ucuz yolu — metin kaybolmaz,
     * ama artık ayrıştırılabilir bir kabuğun içindedir.
     */
    public static String note(String text) {
        return of("note", text);
    }
}
