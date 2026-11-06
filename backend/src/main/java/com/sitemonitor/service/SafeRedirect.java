package com.sitemonitor.service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
     *
     * <p>{@code Location} önce {@link #browserLocation} ile tarayıcının okuduğu biçime getirilir (ham UTF-8
     * baytları, yüzde kodlaması); dönen URI her zaman yalnız ASCII karakter taşır.
     */
    public static URI nextHop(URI current, String location) {
        if (location == null || location.isBlank()) return null;
        URI next;
        try {
            next = current.resolve(browserLocation(location));
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

    /**
     * {@code Location} başlığını TARAYICININ okuduğu biçime getirir (2026-10-30, akyatirim.com.tr yanlış alarmı).
     *
     * <p><b>Neden:</b> standart {@code Location}'da yalnız ASCII (yüzde kodlanmış) karakter bekler, ama bazı sunucular
     * Türkçe harfleri ham UTF-8 baytlarıyla yazar ({@code yurt-ıci} → {@code C4 B1}). Java'nın HTTP istemcileri
     * ({@code HttpClient}, {@code HttpURLConnection}) ve {@code RawHttpProbe} başlık baytlarını ISO-8859-1 okur: "ı"
     * {@code "Ä±"} olur, istek {@code yurt-%C3%84%C2%B1ci} adresine gider ve 404 döner — tarayıcı (UTF-8 okur) aynı
     * bağlantıyı sorunsuz açarken sayfa bütünlüğü "kırık bağlantı" alarmı açıyordu.
     *
     * <p><b>Kural:</b> ASCII olmayan karakter yoksa değer olduğu gibi (yalnız kırpılmış) döner — bugünkü davranış.
     * Varsa: bütün karakterler 0xFF'in altındaysa (yani başlık baytları birebir karaktere çevrilmişse) baytlar KATI
     * UTF-8 olarak yeniden çözülür; geçerli bir UTF-8 dizisiyse çözülmüş metin kullanılır (tarayıcı davranışı), değilse
     * (gerçekten ISO-8859-1 yazan sunucu, ör. tek {@code E9} = "é") metin olduğu gibi kalır. Sonra ASCII olmayan her
     * karakter UTF-8 baytlarıyla yüzde kodlanır ({@code %C4%B1}) — istek satırı her istemcide aynı ve geçerli olur.
     *
     * <p>Güvenlik değişmez: host yine Java'nın ayrıştırdığı haliyle {@link SsrfGuard}'dan geçer; ASCII olmayan bir host
     * (yüzde kodlanınca ayrıştırılamaz) bugünkü gibi takip EDİLMEZ. Doğrusal tek geçiş — girdi boyuyla sınırlı.
     */
    static String browserLocation(String location) {
        String s = location.trim();
        if (isAscii(s)) return s;
        return percentEncodeNonAscii(repairUtf8(s));
    }

    /**
     * {@code Location} başlığının İNSAN için okunur hâli — uçtan uca tanının yönlendirme satırı (2026-10-30). Ham UTF-8
     * baytları Java'nın Latin-1 okumasından geri çevrilir ({@code "yurt-Ä±ci"} → {@code "yurt-ıci"}, tarayıcının gösterdiği);
     * yüzde kodlama YAPILMAZ. Gidilen adres her zaman {@link #nextHop} ({@code next_url}); bu yalnız gösterimdir.
     */
    public static String readableLocation(String location) {
        if (location == null) return null;
        String s = location.trim();
        return isAscii(s) ? s : repairUtf8(s);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) return false;
        }
        return true;
    }

    /** Baytları ISO-8859-1 okunmuş bir UTF-8 metnini geri çevirir; geçerli UTF-8 değilse metni değiştirmez. */
    private static String repairUtf8(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return s;   // zaten Unicode metin — bayt değil
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1)))
                    .toString();
        } catch (CharacterCodingException e) {
            return s;   // gerçekten ISO-8859-1 (ya da bozuk) — Latin-1 okuması kalır
        }
    }

    /** ASCII olmayan her karakteri UTF-8 baytlarıyla {@code %XX} kodlar; ASCII karakterlere dokunmaz. */
    private static String percentEncodeNonAscii(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c < 0x80) {
                out.append(c);
                i++;
                continue;
            }
            int len = Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1)) ? 2 : 1;
            ByteBuffer bytes;
            try {
                bytes = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .encode(CharBuffer.wrap(s, i, i + len));
            } catch (CharacterCodingException e) {
                bytes = ByteBuffer.wrap("?".getBytes(StandardCharsets.US_ASCII));   // REPLACE ile olmaz; güvenlik ağı
            }
            while (bytes.hasRemaining()) {
                int b = bytes.get() & 0xFF;
                out.append('%').append(HEX[b >> 4]).append(HEX[b & 0x0F]);
            }
            i += len;
        }
        return out.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

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
     * Yönlendirmeden sonra kullanılacak HTTP metodu — tarayıcının (ve JDK {@code HttpClient.Redirect.NORMAL}'ın) kuralı.
     *
     * <ul>
     *   <li>303 See Other: her yöntem {@code GET}'e döner (RFC 9110 §15.4.4) — {@code PageFetchCore}'un bugünkü davranışı da
     *       budur ({@code HEAD} + 303 → {@code GET}).</li>
     *   <li>301 / 302 + {@code POST}: {@code GET}'e döner (RFC 9110 §15.4.2 / §15.4.3 tarihsel davranış; bütün tarayıcılar ve
     *       JDK böyle yapar — 2026-11-06 hata avı H4#1). Eskiden yöntem korunuyordu: giriş formu ({@code POST /login} →
     *       {@code 302 /home}) aynı kaynakta gövde + Authorization ile YENİDEN POST ediliyor, başka kaynakta gövdesiz POST
     *       atılıyordu; sunucu 405 / 400 dönünce izleme sahte alarm açıyordu.</li>
     *   <li>307 / 308: yöntem (ve gövde) KORUNUR — standardın amacı budur.</li>
     *   <li>{@code GET} / {@code HEAD} her 301 / 302 / 307 / 308'de aynı kalır.</li>
     * </ul>
     * Gövdesiz istek: çağıranlar gövdeyi ve {@code Content-Type}'ı yalnız {@code POST}'ta yazar — {@code GET}'e dönen hop
     * ikisini de göndermez.
     */
    public static String nextMethod(int status, String method) {
        String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (status == 303) return "GET";
        if ((status == 301 || status == 302) && "POST".equals(m)) return "GET";
        return m;
    }

    /**
     * İlk isteğin ek parçaları (özel başlıklar, Basic auth, {@code POST} gövdesi) bu yönlendirme hop'una GİDER mi — anahtar
     * kelime kontrolünün ve onun uçtan uca tanısının ({@code RawHttpProbe}) TEK kuralı (2026-11-06 hata avı H4#2 / H4#5).
     *
     * <p>Kural: ilk istekle AYNI KAYNAK (şema + host + port; host harfe duyarsız, varsayılan port düşer) → gider. Tek istisna:
     * aynı host'ta düz {@code http}'nin varsayılan portundan (80) {@code https}'in varsayılan portuna (443) YÜKSELTME de aynı
     * kaynak sayılır — kurumsal sitelerin çoğu {@code http://site/} → {@code https://site/} yönlendirir ve kullanıcının
     * tanımladığı {@code Authorization} / {@code X-Api-Key} ikinci adımda gönderilmeyince sayfa 401 dönüp sahte alarm
     * açıyordu (tarayıcının düz http'de zaten açıkta giden kimliği TLS'e taşımak güvenliği artırır). Başka host, aynı host'un
     * başka portu ya da başka bir şema geçişi (ör. {@code https} → {@code http} düşürmesi, {@code http:8080} →
     * {@code https:443}) → GİTMEZ: kimliği yabancı bir sunucuya / sürece teslim etmek olurdu (tarayıcıların cross-origin
     * yönlendirmede {@code Authorization} düşürmesiyle aynı).
     *
     * <p>Karşılaştırma her zaman İLK isteğe göre yapılır (zincirdeki önceki hop'a göre değil): {@code http://a} →
     * {@code https://b} → {@code https://a} zincirinde sırlar yalnız {@code a}'ya gider. Saf; ağ yok.
     */
    public static boolean extrasMayFollow(URI first, URI hop) {
        if (first == null || hop == null) return false;
        String h1 = first.getHost();
        String h2 = hop.getHost();
        if (h1 == null || h2 == null || h1.isBlank() || !h1.equalsIgnoreCase(h2)) return false;
        String s1 = scheme(first);
        String s2 = scheme(hop);
        int p1 = effectivePort(first, s1);
        int p2 = effectivePort(hop, s2);
        if (s1.equals(s2) && p1 == p2) return true;
        return "http".equals(s1) && p1 == 80 && "https".equals(s2) && p2 == 443;
    }

    private static String scheme(URI u) {
        return u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI u, String scheme) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
    }
}
