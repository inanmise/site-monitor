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
