package com.sitemonitor.service.page;

import com.sitemonitor.service.SsrfGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Bir HTTP(S) isteğinin FAZLARINI ayrı ayrı ölçer: DNS → TCP → TLS → sunucu bekleme.
 *
 * <p><b>Neden gerekli:</b> Sayfa Hızı'nın TTFB değeri tek bir rakamdı ve bu rakam DNS + TCP +
 * TLS + istek + ilk baytın TAMAMINI, üstelik yönlendirme zincirinin tümünü içeriyordu. Ölçüm
 * {@code HttpClient} üzerinden yapıldığı için bağlantı havuzu SICAKKEN 41 ms, yeni bağlantı
 * kurulunca ~3 sn okunuyordu: aynı sunucu, aynı sayfa, iki kat farklı sonuç. Kullanıcı bunu
 * "TTFB ara ara sıçrıyor" diye bildirdi ve haklıydı — sıçrayan sunucu değil, bizim bağlantı
 * kurma maliyetimizdi. Eşik o rakama bakınca sunucu hiç yavaşlamamışken alarm üretiyordu.
 *
 * <p><b>Neden AYRI ve TAZE bir bağlantı:</b> fazlar ancak bağlantıyı kendimiz kurarsak
 * ölçülebilir; {@code HttpClient} bunları dışarı vermez. Taze bağlantı ayrıca ölçümü
 * KARŞILAŞTIRILABİLİR yapar — havuzun o anki durumuna göre değişmez. Bedeli, yüzlerce istek
 * yapan bir kontrolde tek bir el sıkışmadır.
 *
 * <p><b>Dürüst sınır:</b> kalıcı bağlantı kullanan gerçek bir tarayıcı oturumu bu fazların
 * bir kısmını ödemez; buradaki rakam "soğuk" durumdur. Bunu gizlemek yerine arayüz fazları
 * ayrı ayrı gösterir, kullanıcı hangisinin büyüdüğünü görür.
 *
 * <p>Sertifika doğrulaması BİLEREK atlanır ({@link PageFetchCore} ile aynı sözleşme): bu prob
 * sertifika geçerliliğini değil ZAMANLAMAYI ölçer; sertifika işi Sertifika izlemesinindir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpPhaseProbe {

    /** Hiçbiri ölçülemedi (bağlantı kurulamadı vb.) — tüm alanlar {@code null}. */
    public static final Phases NONE = new Phases(null, null, null, null, null);

    /**
     * Faz kırılımı. Her alan {@code null} olabilir: ölçülemeyen fazı sıfır saymak, hiç
     * olmayan bir hızı iddia etmek olurdu.
     *
     * @param dnsMs     ad çözümleme
     * @param connectMs TCP el sıkışması
     * @param tlsMs     TLS el sıkışması (düz HTTP'de {@code null})
     * @param serverMs  SON adımda istek gönderildikten sonra İLK YANIT BAYTINA kadar geçen süre —
     *                  "sunucu ne kadar düşündü". Sayfa Hızı TTFB eşiği, kart, geçmiş ve öneri BUNA bakar (2026-11-14,
     *                  inceleme B2); ölçülemediyse null = TTFB bilinmiyor.
     * @param error     ölçüm neden yapılamadı (varsa)
     */
    public record Phases(Integer dnsMs, Integer connectMs, Integer tlsMs, Integer serverMs, String error) {

        /** Fazların toplamı — ölçülemeyenler atlanır; hiçbiri yoksa {@code null}. */
        public Integer totalMs() {
            int sum = 0;
            boolean any = false;
            for (Integer v : new Integer[]{ dnsMs, connectMs, tlsMs, serverMs }) {
                if (v != null) { sum += v; any = true; }
            }
            return any ? sum : null;
        }
    }

    private final SsrfGuard ssrfGuard;
    /** Kurumsal vekil (2026-11-14) — isteğe bağlı: bean yoksa vekil yolunda faz ölçülemez (TTFB bilinmiyor). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.ProxySettings proxySettings;

    /** Test kancası: vekil ayarı (Spring dışı kurulumlar). */
    void setProxySettings(com.sitemonitor.service.ProxySettings p) { this.proxySettings = p; }

    private static final SSLSocketFactory TRUST_ALL = buildTrustAll();

    /** Eski prob (2026-11-14 öncesi) kimliği — yalnız {@link #measure(String, int)} gönderir. */
    static final String LEGACY_USER_AGENT = "SiteMonitor-PhaseProbe";
    /** Durum satırı / başlık satırı tavanı (bayt) ve başlık sayısı tavanı — sınırsız okuma yok. */
    static final int MAX_LINE = 8192;
    static final int MAX_HEADER_LINES = 200;
    /**
     * Ham isteğe ek başlık olarak YAZILMAYAN adlar: çekirdeğin kendi başlıkları ({@link PageFetchCore#isReservedHeader}) +
     * çerçeveyi bozan / vekile ait olanlar (ana istek bunları ya hiç göndermiyor ya kendisi yazıyor).
     */
    static final java.util.Set<String> SKIPPED_HEADERS = java.util.Set.of("accept-encoding", "content-length", "transfer-encoding",
            "expect", "upgrade", "te", "keep-alive", "proxy-authorization", "proxy-connection");

    /**
     * Ölçülecek istek (2026-11-14, Sayfa Hızı zenginleştirme — inceleme B2): izlemenin GERÇEK koşulları. TTFB eşiği bu
     * probun SON adımının sunucu fazına bakar; prob ana istekle AYNI kimliği taşımazsa 401 yanıtının, yönlendirme
     * izlemezse 301 yanıtının süresini ölçerdi (eski prob tam buydu).
     *
     * @param url       izlenen adres
     * @param timeoutMs adım başına bağlantı / okuma zaman aşımı
     * @param userAgent izlemenin User-Agent değeri
     * @param headers   ek başlıklar (DNT, Basic auth, özel başlıklar) — ana istekle AYNI kural: yalnız ilk host'a
     * @param viaProxy  kurumsal vekil üzerinden mi (ana isteğin vekil kararı)
     */
    public record Target(String url, int timeoutMs, String userAgent, java.util.Map<String, String> headers, boolean viaProxy) {}

    /**
     * Adım bekçisi (2026-11-15, zaman aşımı kuralı): soket okuma zaman aşımı yalnız TEK {@code read} çağrısını sınırlar — bir
     * baytı zaman aşımından hemen önce gönderen ("damlatan") bir sunucu TLS el sıkışmasını, durum satırını (8192 bayt) ve
     * başlıkları (200 satır) saatlerce uzatabilirdi. Bekçi adımın kalan süresi dolunca soketi KAPATIR; bloklu okuma / bağlanma
     * / el sıkışma hemen düşer. Tek daemon iş parçacığı, iptal edilen görev kuyruktan silinir.
     */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINE = deadlinePool();

    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlinePool() {
        java.util.concurrent.ScheduledThreadPoolExecutor ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread th = new Thread(r, "page-phase-deadline");
            th.setDaemon(true);
            return th;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** Bir adımın soketini süre dolunca kapatan bekçi tutamağı. */
    static final class Guard {
        private final java.util.concurrent.atomic.AtomicReference<Socket> socket = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean(false);

        /** Bekçi ateşledi: kayıtlı soketi kapat. */
        void fire() {
            fired.set(true);
            closeQuietly(socket.get());
        }

        /** Soketi bekçiye bağlar; bekçi zaten ateşlediyse soketi hemen kapatır (sonraki bağlanma / okuma düşer). */
        void register(Socket s) {
            socket.set(s);
            if (fired.get()) closeQuietly(s);
        }

        boolean fired() { return fired.get(); }

        private static void closeQuietly(Socket s) {
            if (s != null) try { s.close(); } catch (Exception ignored) { /* kapanış */ }
        }
    }

    static final String DEADLINE_ERROR = "faz ölçümü süre sınırını aştı";

    /** Geriye uyum (2026-11-14 öncesi imza): kimliksiz, vekilsiz ölçüm — yönlendirme de izlenir. */
    public Phases measure(String url, int timeoutMs) {
        return measure(new Target(url, timeoutMs, LEGACY_USER_AGENT, java.util.Map.of(), false));
    }

    /**
     * Fazları ölçer — izlemenin koşullarıyla, yönlendirmeleri SON adrese kadar izleyerek; döndürülen fazlar SON adımın
     * fazlarıdır. Her adım KENDİ taze bağlantısını açar ve kapatır: bağlantı havuzu sonucu değiştiremez (inceleme B2 —
     * ana isteğin ilk baytı havuz sıcakken onlarca ms, soğukken saniyeler okunuyordu; zamanlanmış kontrol ile teyit
     * kontrolü aynı eşiğin iki yanına düşüyordu). Asla istisna fırlatmaz: ölçüm yapılamazsa {@link Phases#error} dolu ve
     * {@code serverMs} null döner — çağıran TTFB'yi BİLİNMİYOR sayar (0 ya da "uygun" değil).
     *
     * <p>Kurallar ana istekle ({@code PageFetchCore.fetch}) AYNI: her adımda SSRF kapısı; yönlendirme
     * {@link com.sitemonitor.service.SafeRedirect#nextHop} + {@link com.sitemonitor.service.SafeRedirect#nextMethod} (Sayfa
     * Hızı hep {@code GET}); en çok {@link PageFetchCore#MAX_REDIRECTS} yönlendirme; ek başlıklar (kimlik dahil) yalnız ilk
     * istenen host'a gider; https → http düşürmesi çekirdek gibi izlenir. Vekil yolunda https için {@code CONNECT} tüneli,
     * http için mutlak biçimli istek kullanılır; DNS fazı vekilde olduğu için null kalır ve bağlantı fazı tüneli de içerir.
     */
    public Phases measure(Target t) {
        return measure(t, 0L);
    }

    /**
     * {@link #measure(Target)} + çağıranın KESİN üst sınırı ({@code hardDeadlineMs}, epoch ms; ≤ 0 = yok): Sayfa Hızı
     * kontrolü probu kendi duvar-saati bütçesinin içinde tutar (2026-11-15, zaman aşımı kuralı — prob eskiden bütçenin
     * dışında koşuyordu). Zincirin tavanı {@code min(3 × timeout, hardDeadlineMs)}; her adım bekçiyle bu son tarihte kesilir.
     */
    public Phases measure(Target t, long hardDeadlineMs) {
        if (t == null || t.url() == null) return new Phases(null, null, null, null, "geçersiz URL");
        int to = Math.max(1000, t.timeoutMs());
        // Tüm zincirin mutlak tavanı: her adım kendi zaman aşımıyla sınırlı, ama altı adım art arda sınırsız beklemesin.
        long deadline = System.currentTimeMillis() + to * 3L;
        if (hardDeadlineMs > 0) deadline = Math.min(deadline, hardDeadlineMs);
        String originHost = PageFetchCore.hostOf(t.url());
        String current = t.url();
        String method = "GET";
        try {
            for (int hop = 0; hop <= PageFetchCore.MAX_REDIRECTS; hop++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return new Phases(null, null, null, null, DEADLINE_ERROR);
                boolean extras = originHost != null && originHost.equalsIgnoreCase(PageFetchCore.hostOf(current));
                Guard guard = new Guard();
                java.util.concurrent.ScheduledFuture<?> watch =
                        DEADLINE.schedule(guard::fire, remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                Hop h;
                try {
                    // Okuma zaman aşımı adım başına `to`; adımın TOPLAM süresini bekçi keser (kalan süre dolunca soket kapanır).
                    h = hop(current, method, t, extras, to, guard);
                } finally {
                    watch.cancel(false);
                }
                if (guard.fired()) return new Phases(h.dnsMs, h.connectMs, h.tlsMs, null, DEADLINE_ERROR);
                if (h.error != null) return new Phases(h.dnsMs, h.connectMs, h.tlsMs, null, h.error);
                if (com.sitemonitor.service.SafeRedirect.isRedirect(h.status) && h.location != null) {
                    String next = com.sitemonitor.service.SafeRedirect.nextHop(current, h.location);
                    if (next != null) {
                        method = com.sitemonitor.service.SafeRedirect.nextMethod(h.status, method);
                        current = next;
                        continue;
                    }
                }
                return new Phases(h.dnsMs, h.connectMs, h.tlsMs, h.serverMs, null);
            }
            return new Phases(null, null, null, null, "çok fazla yönlendirme");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Faz ölçümü yapılamadı {}: {}", com.sitemonitor.service.SecretMask.maskUrlQuery(t.url()), msg);
            return new Phases(null, null, null, null, msg);
        }
    }

    /** Tek adımın sonucu: fazlar + durum + Location; {@code error} doluysa adım ölçülemedi. */
    static final class Hop {
        Integer dnsMs, connectMs, tlsMs, serverMs;
        int status;
        String location;
        String error;
    }

    private Hop hop(String url, String method, Target t, boolean extras, int to, Guard guard) {
        Hop h = new Hop();
        Socket plain = null;
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null || host.isBlank()) { h.error = "geçersiz URL"; return h; }
            boolean https = !"http".equalsIgnoreCase(u.getScheme());
            int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            String bareHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            boolean viaProxy = t.viaProxy();

            // SSRF: sayfa çekimiyle AYNI kapı (her adımda). DOĞRULANAN ADRESE bağlanılır — host YENİDEN çözülmez: iki çözüm
            // arasında DNS yanıtı değişirse (rebind) prob muhafızın onaylamadığı bir adrese bağlanırdı.
            long t0 = System.nanoTime();
            java.util.List<java.net.InetAddress> vetted;
            try {
                vetted = ssrfGuard.validate(host);
            } catch (SsrfGuard.BlockedException be) {
                h.error = be.getMessage();
                return h;
            }
            int guardMs = msSince(t0);
            // Ad çözümü (JVM'de kesilemez; işletim sistemi çözücüsünün süresiyle sınırlı) sürerken süre dolduysa bağlanılmaz.
            if (guard.fired()) { h.error = DEADLINE_ERROR; return h; }

            long t1 = System.nanoTime();
            if (viaProxy) {
                if (proxySettings == null || !proxySettings.enabled()) { h.error = "vekil tanımlı değil"; return h; }
                if (https) {
                    // CONNECT host:port (vekil kimliği dahil); soket bağlanmadan bekçiye verilir — tünel yanıtı da kesilebilir
                    plain = proxySettings.openConnectTunnel(host, port, to, guard::register);
                } else {
                    plain = new Socket();
                    guard.register(plain);
                    plain.connect(new InetSocketAddress(proxySettings.host(), proxySettings.port()), to);
                }
            } else {
                h.dnsMs = guardMs;
                if (vetted == null || vetted.isEmpty()) { h.error = "adres çözülemedi"; return h; }
                InetAddress addr = vetted.get(0);
                plain = new Socket();
                guard.register(plain);
                plain.connect(new InetSocketAddress(addr, port), to);
            }
            plain.setSoTimeout(to);
            h.connectMs = msSince(t1);

            Socket sock = plain;
            if (https) {
                long t2 = System.nanoTime();
                SSLSocket ssl = (SSLSocket) TRUST_ALL.createSocket(plain, bareHost, port, true);
                ssl.setSoTimeout(to);
                // SNI: sanal barındırılan / WAF arkasındaki hedefler SNI olmadan farklı (ya da hiç) sertifika sunar.
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                h.tlsMs = msSince(t2);
                sock = ssl;
            }

            String path = (u.getRawPath() == null || u.getRawPath().isBlank()) ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            String hostHeader = host + (u.getPort() > 0 ? ":" + u.getPort() : "");
            // Vekil üzerinden düz HTTP: mutlak biçimli istek satırı + vekil kimliği (HttpClient'ın vekil davranışı).
            String target = viaProxy && !https ? "http://" + hostHeader + path : path;
            StringBuilder req = new StringBuilder()
                    .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n")
                    .append("User-Agent: ").append(clean(t.userAgent() == null ? LEGACY_USER_AGENT : t.userAgent())).append("\r\n")
                    .append("Accept: ").append(PageFetchCore.ACCEPT).append("\r\n")
                    .append("Accept-Language: ").append(PageFetchCore.ACCEPT_LANGUAGE).append("\r\n")
                    .append("Accept-Encoding: ").append(PageFetchCore.ACCEPT_ENCODING).append("\r\n");
            if (viaProxy && !https && proxySettings.proxyAuthorizationHeader() != null) {
                req.append("Proxy-Authorization: ").append(proxySettings.proxyAuthorizationHeader()).append("\r\n");
            }
            if (extras && t.headers() != null) {
                for (java.util.Map.Entry<String, String> e : t.headers().entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null || k.isBlank()) continue;
                    String lk = k.trim().toLowerCase(java.util.Locale.ROOT);
                    if (PageFetchCore.isReservedHeader(lk) || SKIPPED_HEADERS.contains(lk)) continue;
                    if (PageFetchCore.hasControlChars(k) || PageFetchCore.hasControlChars(v)) continue;
                    req.append(k.trim()).append(": ").append(v).append("\r\n");
                }
            }
            req.append("Connection: close\r\n\r\n");

            long t3 = System.nanoTime();
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = sock.getInputStream();
            int first = in.read();                       // İLK BAYT — ölçülmek istenen an tam burası
            if (first < 0) { h.error = "sunucu yanıt vermeden bağlantıyı kapattı"; return h; }
            h.serverMs = msSince(t3);
            // Durum satırı + (yönlendirmede) Location — tavanlı; gövde OKUNMAZ: bu prob ağırlık değil zamanlama ölçer.
            String statusLine = (char) first + nullToEmpty(readLine(in));
            h.status = parseStatus(statusLine);
            if (h.status <= 0) { h.error = "geçersiz HTTP yanıtı"; h.serverMs = null; return h; }
            if (com.sitemonitor.service.SafeRedirect.isRedirect(h.status)) {
                for (int i = 0; i < MAX_HEADER_LINES; i++) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                    int c = line.indexOf(':');
                    if (c > 0 && "location".equalsIgnoreCase(line.substring(0, c).trim())) {
                        h.location = line.substring(c + 1).trim();
                        break;
                    }
                }
            }
            return h;
        } catch (Exception e) {
            h.serverMs = null;
            h.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return h;
        } finally {
            if (plain != null) try { plain.close(); } catch (Exception ignored) { /* kapanış */ }
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** CR / LF ve kontrol karakterleri başlık değerine yazılmaz (başlık enjeksiyonu). */
    private static String clean(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString();
    }

    /** {@code HTTP/1.1 301 Moved} → 301; okunamazsa 0. */
    static int parseStatus(String line) {
        if (line == null || !line.startsWith("HTTP/")) return 0;
        String[] p = line.split(" ", 3);
        if (p.length < 2) return 0;
        try { return Integer.parseInt(p[1].trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Tek satır (CRLF / LF) — önden okumadan, {@link #MAX_LINE} tavanlı. Akış bitti ve satır boşsa null. */
    static String readLine(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (buf.size() >= MAX_LINE) throw new java.io.IOException("yanıt satırı çok uzun");
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        String line = buf.toString(StandardCharsets.ISO_8859_1);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static int msSince(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static SSLSocketFactory buildTrustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            log.warn("Faz probu trust-all SSL kuramadı, varsayılan kullanılacak: {}", e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
