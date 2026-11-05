package com.sitemonitor.service.page.scan.deep;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derin analizin (algoritma 3) nesne listesi DIŞINDAKİ girdileri — tarama servisi toplar, analizci yalnız okur.
 *
 * @param html              ana sayfanın HTML olguları (ayrıştırılamadıysa null → HTML'ye dayalı bölümler "bilinmiyor")
 * @param pageHeaders       ana sayfanın son yanıtının güvenlik başlıkları (küçük harfli ad → değerler); null = okunmadı
 * @param pageUrl           ana sayfanın son adresi
 * @param cookies           görülen {@code Set-Cookie}'ler (değersiz; {@code thirdParty} çağıranca hesaplanmış)
 * @param fontFaces         stillerdeki {@code @font-face} blokları
 * @param cssReferenced     stil metinlerinin başvurduğu adresler (parçasız anahtar) — "kullanılmayan önyükleme"
 * @param hostTimings       host → bağlantı ölçümü (ölçülmeyen host yok)
 * @param hostsNotMeasured  sınır yüzünden ölçülmeyen host sayısı
 * @param sourceMaps        harita adresi → açık mı (true / false / null = denetlenemedi)
 * @param sourceMapsNotChecked sınır yüzünden denetlenmeyen harita sayısı
 * @param samplesUnavailable örneği okunamayan betik / stil sayısı (br / kesik)
 */
public record DeepContext(HtmlFacts html, Map<String, List<String>> pageHeaders, String pageUrl,
                          List<CookieSeen> cookies, List<FontFaces.Face> fontFaces, Set<String> cssReferenced,
                          Map<String, HostTiming> hostTimings, int hostsNotMeasured,
                          Map<String, Boolean> sourceMaps, int sourceMapsNotChecked, int samplesUnavailable) {

    /** Boş bağlam: HTML / başlık bilgisi yok (eski çağrılar, testler). */
    public static final DeepContext EMPTY = new DeepContext(null, null, null, List.of(), List.of(), Set.of(), Map.of(), 0,
            Map.of(), 0, 0);

    public DeepContext {
        cookies = cookies == null ? List.of() : cookies;
        fontFaces = fontFaces == null ? List.of() : fontFaces;
        cssReferenced = cssReferenced == null ? Set.of() : cssReferenced;
        hostTimings = hostTimings == null ? Map.of() : hostTimings;
        sourceMaps = sourceMaps == null ? Map.of() : sourceMaps;
    }

    /** Görülen çerez + üçüncü taraf mı (sayfanın sitesine göre). */
    public record CookieSeen(CookieFacts.Cookie cookie, boolean thirdParty) {}

    /**
     * Host bağlantı ölçümü (ms; ölçülemeyen adım null).
     *
     * @param measured adımlar ölçüldü mü; false ise {@code reason}
     * @param reason   {@code proxy | blocked | dns_failed | connect_failed | tls_failed | not_measured | cancelled}
     */
    public record HostTiming(Long dnsMs, Long connectMs, Long tlsMs, boolean measured, String reason) {
        public static HostTiming notMeasured(String reason) {
            return new HostTiming(null, null, null, false, reason);
        }

        /** Kurulum toplamı (ölçülen adımlar); hiçbiri yoksa null. */
        public Long setupMs() {
            if (dnsMs == null && connectMs == null && tlsMs == null) return null;
            return (dnsMs == null ? 0 : dnsMs) + (connectMs == null ? 0 : connectMs) + (tlsMs == null ? 0 : tlsMs);
        }
    }
}
