package com.sitemonitor.service;

import java.net.URI;
import java.util.Locale;

/**
 * Yönlendirme (redirect) takibinin GÜVENLİK POLİTİKASI — taşımadan bağımsız, saf yardımcı.
 *
 * <p><b>Neden var:</b> giden istek atan her servis kendi yönlendirme davranışını kendi yazıyordu.
 * Java {@code HttpClient.Redirect.NORMAL} (ve {@code HttpURLConnection.setInstanceFollowRedirects})
 * zinciri KÜTÜPHANE İÇİNDE takip eder; ara hop'lar uygulamaya hiç görünmez. Sonuç: ilk host
 * {@link SsrfGuard}'tan geçse bile, hedef sunucu bizi {@code 302 Location: http://169.254.169.254/}
 * ile iç ağa yönlendirebiliyordu. İzleme hedefini sıradan bir kullanıcı tanımlayabildiği için bu
 * gerçek bir SSRF yüzeyiydi — özellikle yanıt gövdesini/başlıklarını kullanıcıya döndüren
 * uçlarda (keyword snippet'i, HSTS başlık listesi).
 *
 * <p>Doğru desen {@code PageFetchCore}'da zaten vardı: otomatik takibi KAPAT, hop'ları elle takip
 * et, her hop'ta {@code ssrfGuard.validate(host)} çalıştır. Taşımalar farklı ({@code HttpClient} vs
 * {@code HttpURLConnection}) olduğu için paylaşılan şey taşıma değil <b>politika</b>: hangi
 * {@code Location} takip edilir, kaç hop'a kadar, hangi metotla.
 *
 * <p>Her metot saf ve statiktir — ağsız test edilebilir.
 */
public final class SafeRedirect {

    /** Yönlendirme zinciri üst sınırı. Aşılırsa çağıran hata döndürür (döngüye girmez). */
    public static final int MAX_HOPS = 5;

    private SafeRedirect() {}

    /** 3xx yanıt mı (yönlendirme takibi gerektirir)? */
    public static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    /**
     * Bir sonraki hop'un URI'si — takip edilmemesi gerekiyorsa {@code null}.
     *
     * <p>{@code null} dönmesi HATA değildir: çağıran 3xx yanıtı OLDUĞU GİBİ döndürmelidir
     * (tarayıcı da desteklemediği bir şemaya yönlendirilince orada durur).
     *
     * <p>Reddedilenler:
     * <ul>
     *   <li>boş/ayrıştırılamayan {@code Location}</li>
     *   <li>{@code http}/{@code https} DIŞI şema — {@code file:}, {@code gopher:}, {@code ftp:},
     *       {@code jar:} gibi hedefler yerel dosya okumaya/protokol karıştırmaya açılır</li>
     *   <li>host'suz hedef ({@code file:///etc/passwd} gibi) — doğrulanacak bir host yoksa
     *       {@link SsrfGuard} devreye giremez, yani sessizce korumasız kalırdık</li>
     * </ul>
     */
    public static URI nextHop(URI current, String location) {
        if (location == null || location.isBlank()) return null;
        URI next;
        try {
            next = current.resolve(location.trim());
        } catch (Exception e) {
            return null;   // bozuk Location — takip etme
        }
        String scheme = next.getScheme();
        if (scheme == null) return null;
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(s) && !"https".equals(s)) return null;
        String host = next.getHost();
        if (host == null || host.isBlank()) return null;
        return next;
    }

    /** Dize taşıyan çağıranlar için ({@code PageFetchCore} URL'leri String tutar). */
    public static String nextHop(String current, String location) {
        try {
            URI n = nextHop(URI.create(current), location);
            return n == null ? null : n.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * HTTPS → düz HTTP düşürmesi mi?
     *
     * <p>{@code HttpClient.Redirect.NORMAL} bu hop'u takip ETMEZ; elle takibe geçen çağıranlar
     * aynı davranışı korumak için bunu kullanır. Ayrı metot olmasının sebebi
     * {@link #nextHop}'un politikasının her çağıran için aynı olmaması: {@code PageFetchCore}
     * bugün düşürmeyi takip ediyor ve o davranış korunuyor.
     */
    public static boolean isDowngrade(URI from, URI to) {
        return "https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme());
    }

    /**
     * Yönlendirmeden sonra kullanılacak HTTP metodu.
     *
     * <p>303 See Other her zaman GET'e döner (RFC 9110 §15.4.4) — {@code PageFetchCore}'un
     * bugünkü davranışı da budur ({@code HEAD} + 303 → {@code GET}). Diğer 3xx'lerde metot korunur.
     */
    public static String nextMethod(int status, String method) {
        String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        return status == 303 ? "GET" : m;
    }
}
