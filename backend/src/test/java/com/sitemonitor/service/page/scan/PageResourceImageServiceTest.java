package com.sitemonitor.service.page.scan;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageOutcome;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageResult;
import com.sitemonitor.service.page.scan.PageResourceImageService.Projection;
import com.sitemonitor.service.page.scan.PageResourceImageService.ProjectionOutcome;
import com.sitemonitor.service.page.scan.PageResourceImageService.Replacement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Görsel aracının sunucu tarafı — GERÇEK SQL (H2) + GERÇEK tarama + GERÇEK çekim çekirdeği + yerel HTTP sunucusu:
 * taramanın bulduğu görselin indirilmesi (yalnız listedeki GÖRSEL nesnesi, maskeli adres, yönlendirme → engelli adres, gövde
 * tavanı okunurken, sihirli baytlardan tür, yük sınırları, izin bırakma) ve "not ne olurdu?" projeksiyonu (taban = saklanan
 * analiz, analizin kendi kurallarıyla değişim, ölçülmüş kazanç, bilinmeyen adresler, yazma yok).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceImageServiceTest {

    static final long KB = 1024, MB = 1024 * 1024;
    static final int CAP = PageResourceImageService.MAX_IMAGE_BYTES;

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    ExecutorService serverPool;
    String base;
    PageResourceScanService scans;
    PageResourceImageService images;
    PageCheckerService checker;
    final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Tarama bittikten sonra açılan davranışlar. */
    volatile boolean afterScan;
    final CountDownLatch slowRelease = new CountDownLatch(1);
    final AtomicInteger slowWaiting = new AtomicInteger();
    final AtomicLong growSent = new AtomicLong();

    byte[] hero, photo, gif, bmp, noise, webp, avif;

    static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, bos)).isTrue();
        return bos.toByteArray();
    }

    /** Sıkışmayan (gürültü) opak görsel — PNG'si büyük, JPEG'e yeniden kodlanınca küçülür. */
    static BufferedImage noiseImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rnd.nextInt(0x1000000));
        return img;
    }

    int hitsOf(String path) {
        AtomicInteger a = hits.get(path);
        return a == null ? 0 : a.get();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        hero = encode(new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB), "png");
        photo = encode(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "jpg");
        gif = encode(new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB), "gif");
        bmp = encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "bmp");
        noise = encode(noiseImage(700, 500), "png");
        webp = ImageTypeTest.webp(300, 200);
        avif = ImageTypeTest.isoBmff("avif", new String[]{"mif1", "miaf"}, new int[][]{{640, 480}});
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) { g.write(hero); }
        byte[] heroGz = gz.toByteArray();

        String gallery = """
                <html><head><link rel="stylesheet" href="/s.css"><link rel="icon" href="/fav.png">
                  <script src="/a.js"></script></head><body>
                  <img src="/hero.png" width="300" height="200">
                  <img src="/lazy.png" loading="lazy"> <video src="/v.mp4"></video>
                  <img src="/wide.png">
                  <img src="/photo.jpg"> <img src="/anim.gif"> <img src="/pic.webp"> <img src="/dot.bmp"> <img src="/pic.avif">
                  <img src="/vector.svg"> <img src="/fake.png"> <img src="/flaky.png"> <img src="/redir.png"> <img src="/hop.png">
                  <img src="/grow.png"> <img src="/big.png"> <img src="/gz.png"> <img src="/slow.png">
                  <img src="/secret.png?token=GIZLI123">
                  <img src="/g%C3%B6rsel%20ad%C4%B1.final.png">
                  <a href="/about">about</a>
                </body></html>
                """;
        String proj = """
                <html><head><link rel="stylesheet" href="/s.css"><script src="/a.js"></script></head><body>
                  <img src="/noise.png"> <img src="/hero.png"> <img src="/photo.jpg">
                  <a href="/about">about</a>
                </body></html>
                """;
        byte[] wide = encode(new BufferedImage(2400, 10, BufferedImage.TYPE_INT_ARGB), "png");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body;
            int status = 200;
            String ct;
            switch (path) {
                case "/gallery" -> { body = gallery.getBytes(StandardCharsets.UTF_8); ct = "text/html; charset=utf-8"; }
                case "/proj" -> { body = proj.getBytes(StandardCharsets.UTF_8); ct = "text/html; charset=utf-8"; }
                case "/s.css" -> { body = "body{background:url(/bg.png)}".getBytes(StandardCharsets.UTF_8); ct = "text/css"; }
                case "/a.js" -> { body = "x".repeat(4000).getBytes(StandardCharsets.UTF_8); ct = "application/javascript"; }
                case "/about" -> { body = "<html>about</html>".getBytes(StandardCharsets.UTF_8); ct = "text/html"; }
                case "/hero.png", "/bg.png", "/secret.png", "/fav.png", "/lazy.png", "/görsel adı.final.png" -> {
                    body = hero; ct = "image/png";
                }
                case "/wide.png" -> { body = wide; ct = "image/png"; }
                case "/noise.png" -> { body = noise; ct = "image/png"; }
                // Başlık yalan söyler: tür yalnız sihirli baytlardan
                case "/photo.jpg" -> { body = photo; ct = "text/html"; }
                case "/anim.gif" -> { body = gif; ct = "image/gif"; }
                case "/pic.webp" -> { body = webp; ct = "image/webp"; }
                case "/dot.bmp" -> { body = bmp; ct = "application/octet-stream"; }
                case "/pic.avif" -> { body = avif; ct = "image/avif"; }
                case "/vector.svg" -> {
                    body = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
                    ct = "image/svg+xml";
                }
                case "/fake.png" -> { body = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8); ct = "image/png"; }
                case "/flaky.png" -> { body = hero; ct = "image/png"; if (afterScan) status = 500; }
                case "/redir.png" -> {
                    ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/x.png");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                case "/hop.png" -> {
                    ex.getResponseHeaders().add("Location", "/hero.png");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                case "/gz.png" -> {
                    ex.getResponseHeaders().add("Content-Type", "image/png");
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, heroGz.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(heroGz); }
                    return;
                }
                case "/grow.png" -> {
                    if (afterScan) { stream(ex, 80 * MB, growSent, -1); return; }
                    body = hero; ct = "image/png";
                }
                case "/big.png" -> { stream(ex, 30 * MB, new AtomicLong(), 30 * MB); return; }
                case "/slow.png" -> {
                    if (afterScan) {
                        slowWaiting.incrementAndGet();
                        try { slowRelease.await(20, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    body = bmp; ct = "image/bmp";
                }
                default -> { body = "missing".getBytes(); status = 404; ct = "text/plain"; }
            }
            ex.getResponseHeaders().add("Content-Type", ct);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getInt(anyString(), anyInt())).thenAnswer(a -> a.getArgument(1));
        PageFetchCore core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        checker = mock(PageCheckerService.class);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        PublicSuffixService psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        scans = new PageResourceScanService(jdbc, core, checker, psl, s);
        images = new PageResourceImageService(jdbc, core, checker, scans, clock::get);
    }

    /** PNG imzasıyla başlayan {@code total} baytlık akış; istemci keserse durur ({@code sent} gönderilen baytı sayar). */
    static void stream(HttpExchange ex, long total, AtomicLong sent, long contentLength) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.sendResponseHeaders(200, contentLength < 0 ? 0 : contentLength);
        byte[] chunk = new byte[64 * 1024];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 9, 0, 0, 0, 9, 8, 6};
        System.arraycopy(magic, 0, chunk, 0, magic.length);
        try (OutputStream os = ex.getResponseBody()) {
            long left = total;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                os.write(chunk, 0, n);
                sent.addAndGet(n);
                left -= n;
                if (left < total - chunk.length) java.util.Arrays.fill(chunk, 0, magic.length, (byte) 0);
            }
        } catch (IOException clientClosed) {
            // istemci tavanda kesti
        }
    }

    @AfterEach
    void tearDown() {
        slowRelease.countDown();
        server.stop(0);
        serverPool.shutdownNow();
        scans.shutdown();
    }

    PageMonitor monitor(long id, String path) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setTeamId(7L);
        m.setName("Galeri");
        m.setUrl(base + path);
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    /** Taramayı çalıştırır, DONE olmasını bekler; tarama kimliğini döner. */
    long scanDone(long monitorId, String path) throws InterruptedException {
        PageResourceScanService.Start st = scans.start(monitor(monitorId, path), "u:tarayan", "Ayşe");
        assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        long id = ((Number) st.scan().get("id")).longValue();
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> scan = get(scans.one(id, monitorId), "scan");
            if (!"RUNNING".equals(scan.get("status"))) {
                assertThat(scan.get("status")).isEqualTo("DONE");
                afterScan = true;
                return id;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("tarama bitmedi");
    }

    List<Map<String, Object>> items(long monitorId, long scanId) {
        return get(scans.one(scanId, monitorId), "items");
    }

    Map<String, Object> item(long monitorId, long scanId, String pathPart) {
        return items(monitorId, scanId).stream().filter(i -> ((String) i.get("url")).contains(pathPart)).findFirst().orElseThrow();
    }

    ImageResult download(long monitorId, long scanId, String path) {
        return images.image(monitorId, scanId, base + path, null, "u:1");
    }

    // ── Nesne alanları (yeni tarama) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("yeni taramada görsel satırı: bildirilen boyut HTML'den, önerilen genişlik (declared / max_width); diğer satırlarda alan yok")
    void newScanCarriesDeclaredAndSuggestedWidth() throws Exception {
        long id = scanDone(1, "/gallery");
        Map<String, Object> heroItem = item(1, id, "/hero.png");
        assertThat(heroItem).containsEntry("width", 1200).containsEntry("declared_width", 300).containsEntry("declared_height", 200)
                .containsEntry("suggested_width", 600).containsEntry("suggested_reason", "declared");
        assertThat(item(1, id, "/wide.png")).containsEntry("width", 2400).containsEntry("suggested_width", 1920)
                .containsEntry("suggested_reason", "max_width").doesNotContainKeys("declared_width", "declared_height");
        assertThat(item(1, id, "/photo.jpg")).doesNotContainKeys("declared_width", "suggested_width", "suggested_reason");
        assertThat(item(1, id, "/s.css")).doesNotContainKeys("declared_width", "suggested_width");
        assertThat(items(1, id).get(0)).doesNotContainKeys("declared_width", "suggested_width");
    }

    // ── Özgün görsel ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kabul edilen altı tür: baytlar birebir, tür sihirli baytlardan (yalan Content-Type'a rağmen), boyut başlıktan; gzip'li gövde açılır; izinli yönlendirme izlenir")
    void acceptedTypesReturnOriginalBytes() throws Exception {
        long id = scanDone(1, "/gallery");
        ImageResult png = download(1, id, "/hero.png");
        assertThat(png.outcome()).isEqualTo(ImageOutcome.OK);
        assertThat(png.body()).isEqualTo(hero);
        assertThat(png.type()).isEqualTo("png");
        assertThat(png.width()).isEqualTo(1200);
        assertThat(png.height()).isEqualTo(800);

        ImageResult jpg = download(1, id, "/photo.jpg");
        assertThat(jpg.outcome()).as("Content-Type text/html — tür baytlardan").isEqualTo(ImageOutcome.OK);
        assertThat(jpg.type()).isEqualTo("jpeg");
        assertThat(jpg.body()).isEqualTo(photo);
        assertThat(download(1, id, "/anim.gif").type()).isEqualTo("gif");
        assertThat(download(1, id, "/pic.webp").type()).isEqualTo("webp");
        ImageResult b = download(1, id, "/dot.bmp");
        assertThat(b.type()).isEqualTo("bmp");
        assertThat(b.body()).isEqualTo(bmp);
        ImageResult a = download(1, id, "/pic.avif");
        assertThat(a.type()).isEqualTo("avif");
        assertThat(a.width()).isEqualTo(640);
        assertThat(a.height()).isEqualTo(480);

        ImageResult gz = download(1, id, "/gz.png");
        assertThat(gz.outcome()).isEqualTo(ImageOutcome.OK);
        assertThat(gz.body()).as("Content-Encoding: gzip açılır — özgün PNG").isEqualTo(hero);

        ImageResult hop = download(1, id, "/hop.png");
        assertThat(hop.outcome()).as("302 → aynı sitedeki görsel").isEqualTo(ImageOutcome.OK);
        assertThat(hop.body()).isEqualTo(hero);

        // Yüzde kodlu ad: taramanın döndürdüğü adres BİREBİR gönderilir
        String encoded = (String) item(1, id, ".final.png").get("url");
        ImageResult named = images.image(1, id, encoded, null, "u:2");
        assertThat(named.outcome()).as(encoded).isEqualTo(ImageOutcome.OK);
        assertThat(named.body()).isEqualTo(hero);
    }

    @Test
    @DisplayName("açık vekil değil: listede olmayan adres, görsel olmayan nesne, başka izlemenin taraması, bırakılmış ayrıntı — hiçbiri siteye istek atmaz")
    void onlyImageItemsOfThatScan() throws Exception {
        long id = scanDone(1, "/gallery");
        int aboutBefore = hitsOf("/about");
        int favBefore = hitsOf("/fav.png");
        int cssBefore = hitsOf("/s.css");
        int heroBefore = hitsOf("/hero.png");

        assertThat(download(1, id, "/not-in-scan.png").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/s.css").outcome()).as("stil dosyası görsel değil").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/about").outcome()).as("bağlantı görsel değil").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/gallery").outcome()).as("sayfanın kendisi").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(item(1, id, "/fav.png").get("category")).isEqualTo("FAVICON");
        assertThat(download(1, id, "/fav.png").outcome()).as("site simgesi GÖRSEL kategorisinde değil").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/v.mp4").outcome()).as("medya görsel değil").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/HERO.png").outcome()).as("birebir eşleşme (harf duyarlı)").isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(download(1, id, "/hero.png#x").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(images.image(1, id, "http://169.254.169.254/latest/meta-data/", null, "u:1").outcome())
                .isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(images.image(1, id, "file:///etc/passwd", null, "u:1").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(images.image(1, id, " ", null, "u:1").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(images.image(1, id, "x".repeat(5000), null, "u:1").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);

        assertThat(download(2, id, "/hero.png").outcome()).as("tarama başka izlemenin").isEqualTo(ImageOutcome.SCAN_NOT_FOUND);
        assertThat(download(1, id + 999, "/hero.png").outcome()).isEqualTo(ImageOutcome.SCAN_NOT_FOUND);

        jdbc.update("UPDATE page_resource_scans SET items_json = NULL, items_kept = FALSE WHERE id = ?", id);
        assertThat(download(1, id, "/hero.png").outcome()).isEqualTo(ImageOutcome.ITEMS_GONE);
        jdbc.update("UPDATE page_resource_scans SET items_json = '[{bozuk', items_kept = TRUE WHERE id = ?", id);
        assertThat(download(1, id, "/hero.png").outcome()).as("bozuk liste = saklanmıyor").isEqualTo(ImageOutcome.ITEMS_GONE);

        assertThat(hitsOf("/about")).isEqualTo(aboutBefore);
        assertThat(hitsOf("/fav.png")).isEqualTo(favBefore);
        assertThat(hitsOf("/v.mp4")).as("indirilmeyen medya hiç istenmedi").isZero();
        assertThat(hitsOf("/s.css")).isEqualTo(cssBefore);
        assertThat(hitsOf("/hero.png")).isEqualTo(heroBefore);
        assertThat(hitsOf("/not-in-scan.png")).isZero();
        assertThat(images.imageLimiter().freePodSlots()).isEqualTo(PageResourceImageService.IMAGES_PER_POD);
    }

    @Test
    @DisplayName("maskeli adres: saklanan adreste gizli değer yok (*****) → MASKED, siteye istek atılmaz; gerçek adres listede olmadığı için NOT_FOUND")
    void maskedUrlIsRefused() throws Exception {
        long id = scanDone(1, "/gallery");
        String stored = (String) item(1, id, "/secret.png").get("url");
        assertThat(stored).contains("*****").doesNotContain("GIZLI123");
        int before = hitsOf("/secret.png");
        assertThat(images.image(1, id, stored, null, "u:1").outcome()).isEqualTo(ImageOutcome.MASKED);
        assertThat(download(1, id, "/secret.png?token=GIZLI123").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        assertThat(hitsOf("/secret.png")).isEqualTo(before);
    }

    @Test
    @DisplayName("SSRF her adımda: görsel engelli adrese yönlendirirse BLOCKED; SVG ve görsel başlıklı HTML → UNSUPPORTED; site 500 → FETCH_FAILED + http_status")
    void blockedUnsupportedAndUpstreamFailure() throws Exception {
        long id = scanDone(1, "/gallery");
        ImageResult blocked = download(1, id, "/redir.png");
        assertThat(blocked.outcome()).isEqualTo(ImageOutcome.BLOCKED);
        assertThat(blocked.body()).isNull();

        assertThat(download(1, id, "/vector.svg").outcome()).isEqualTo(ImageOutcome.UNSUPPORTED);
        ImageResult fake = download(1, id, "/fake.png");
        assertThat(fake.outcome()).as("Content-Type image/png ama gövde HTML").isEqualTo(ImageOutcome.UNSUPPORTED);
        assertThat(fake.body()).isNull();

        ImageResult failed = download(1, id, "/flaky.png");
        assertThat(failed.outcome()).isEqualTo(ImageOutcome.FETCH_FAILED);
        assertThat(failed.httpStatus()).isEqualTo(500);
        assertThat(failed.reason()).isNull();

        server.stop(0);
        ImageResult down = download(1, id, "/hero.png");
        assertThat(down.outcome()).isEqualTo(ImageOutcome.FETCH_FAILED);
        assertThat(down.httpStatus()).isNull();
        assertThat(down.reason()).as("ulaşılamama nedeni (CheckFailureReason adı)").isNotBlank().matches("[A-Z_]+");
        assertThat(images.imageLimiter().freePodSlots()).isEqualTo(PageResourceImageService.IMAGES_PER_POD);
    }

    @Test
    @DisplayName("gövde tavanı OKURKEN: 80 MB'lık akışın yalnız tavan kadarı okunur (TOO_LARGE, gövde yok); taramanın ölçtüğü boyut tavanı aşıyorsa hiç çekilmez")
    void bodyCapIsEnforcedWhileReading() throws Exception {
        long id = scanDone(1, "/gallery");
        ImageResult grown = download(1, id, "/grow.png");
        assertThat(grown.outcome()).isEqualTo(ImageOutcome.TOO_LARGE);
        assertThat(grown.body()).isNull();
        assertThat(grown.knownBytes()).as("çekilirken aşıldı — taramanın ölçtüğü boyut küçüktü").isNull();
        assertThat(growSent.get()).as("80 MB'ın tamamı indirilmedi (tavan + ağ tamponu)").isLessThan(60 * MB)
                .isGreaterThan(CAP);

        Map<String, Object> big = item(1, id, "/big.png");
        assertThat(((Number) big.get("bytes")).longValue()).as("tarama boyutu başlıktan biliyor").isEqualTo(30 * MB);
        int before = hitsOf("/big.png");
        ImageResult tooBig = download(1, id, "/big.png");
        assertThat(tooBig.outcome()).isEqualTo(ImageOutcome.TOO_LARGE);
        assertThat(tooBig.knownBytes()).isEqualTo(30 * MB);
        assertThat(hitsOf("/big.png")).as("çekilmeden reddedildi").isEqualTo(before);
        assertThat(images.imageLimiter().freePodSlots()).isEqualTo(PageResourceImageService.IMAGES_PER_POD);
    }

    @Test
    @DisplayName("sınıflandırma: tam tavandaki gövde kabul, bir bayt fazlası TOO_LARGE; çözülemeyen ad BLOCKED değil FETCH_FAILED; toplam süre sınırlı")
    void classifyAndBudget() {
        byte[] atCap = new byte[CAP];
        System.arraycopy(hero, 0, atCap, 0, hero.length);
        ImageResult ok = PageResourceImageService.classify(new PageFetchCore.Fetch(200, 5, 9, CAP, atCap, null, false, false));
        assertThat(ok.outcome()).isEqualTo(ImageOutcome.OK);
        assertThat(ok.type()).isEqualTo("png");
        byte[] over = new byte[CAP + 1];
        System.arraycopy(hero, 0, over, 0, hero.length);
        assertThat(PageResourceImageService.classify(new PageFetchCore.Fetch(200, 5, 9, CAP + 1L, over, null, false, false)).outcome())
                .isEqualTo(ImageOutcome.TOO_LARGE);
        // Tel baytı tavanı aştı ama açılamadığı için ham gövde küçük kaldı: yine TOO_LARGE
        assertThat(PageResourceImageService.classify(new PageFetchCore.Fetch(200, 5, 9, CAP + 1L, hero, null, false, false)).outcome())
                .isEqualTo(ImageOutcome.TOO_LARGE);
        assertThat(PageResourceImageService.classify(new PageFetchCore.Fetch(204, 5, 9, 0, null, null, false, false)).outcome())
                .isEqualTo(ImageOutcome.UNSUPPORTED);
        ImageResult redirect = PageResourceImageService.classify(new PageFetchCore.Fetch(302, 5, 9, 0, null, null, false, false));
        assertThat(redirect.outcome()).isEqualTo(ImageOutcome.FETCH_FAILED);
        assertThat(redirect.httpStatus()).isEqualTo(302);
        ImageResult blocked = PageResourceImageService.classify(
                new PageFetchCore.Fetch(0, 0, 1, 0, null, "Hedef adres engellendi: 10.0.0.1", true, false));
        assertThat(blocked.outcome()).isEqualTo(ImageOutcome.BLOCKED);
        ImageResult transport = PageResourceImageService.classify(new PageFetchCore.Fetch(0, 0, 1, 0, null, "bağlantı koptu", false, false));
        assertThat(transport.outcome()).isEqualTo(ImageOutcome.FETCH_FAILED);
        assertThat(transport.reason()).isEqualTo("TRANSPORT_ERROR");

        assertThat(PageResourceImageService.totalBudgetMs(15_000)).isEqualTo(40_000);
        assertThat(PageResourceImageService.totalBudgetMs(60_000)).isEqualTo(130_000);
        assertThat(PageResourceImageService.totalBudgetMs(1)).isEqualTo(12_000);
        assertThat(PageResourceImageService.totalBudgetMs(Integer.MAX_VALUE)).isEqualTo(PageResourceImageService.TOTAL_BUDGET_MAX_MS);
    }

    @Test
    @DisplayName("yük sınırı: kişi başına aynı anda 2, pod'da 4 → fazlası BUSY (siteye istek yok); bitince yuvalar geri gelir; istisnada izin bırakılır")
    void inFlightLimitsAndPermitRelease() throws Exception {
        long id = scanDone(1, "/gallery");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ImageResult>> running = new ArrayList<>();
            for (String user : List.of("u:a", "u:a", "u:b", "u:b")) {
                running.add(pool.submit(() -> images.image(1, id, base + "/slow.png", null, user)));
            }
            long until = System.currentTimeMillis() + 10_000;
            while (slowWaiting.get() < 4 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertThat(slowWaiting.get()).as("dört indirme uçuşta").isEqualTo(4);
            assertThat(images.imageLimiter().freePodSlots()).isZero();

            int heroBefore = hitsOf("/hero.png");
            ImageResult userBusy = images.image(1, id, base + "/hero.png", null, "u:a");
            assertThat(userBusy.outcome()).isEqualTo(ImageOutcome.BUSY);
            assertThat(userBusy.retryAfterSeconds()).isEqualTo(PageResourceImageService.BUSY_RETRY_SECONDS);
            assertThat(images.image(1, id, base + "/hero.png", null, "u:c").outcome()).as("pod dolu").isEqualTo(ImageOutcome.BUSY);
            assertThat(hitsOf("/hero.png")).as("reddedilen istek siteye gitmez").isEqualTo(heroBefore);

            slowRelease.countDown();
            for (Future<ImageResult> f : running) {
                ImageResult r = f.get(20, TimeUnit.SECONDS);
                assertThat(r.outcome()).isEqualTo(ImageOutcome.OK);
                assertThat(r.type()).isEqualTo("bmp");
            }
            assertThat(images.imageLimiter().freePodSlots()).isEqualTo(PageResourceImageService.IMAGES_PER_POD);
            assertThat(images.image(1, id, base + "/hero.png", null, "u:a").outcome()).isEqualTo(ImageOutcome.OK);
        } finally {
            pool.shutdownNow();
        }

        // İstisna (çekimden önce): izin finally'de bırakılır, sonraki istek çalışır
        when(checker.effectiveUserAgent()).thenThrow(new IllegalStateException("patladı"));
        assertThatThrownBy(() -> images.image(1, id, base + "/hero.png", null, "u:z")).isInstanceOf(IllegalStateException.class);
        assertThat(images.imageLimiter().freePodSlots()).isEqualTo(PageResourceImageService.IMAGES_PER_POD);
        org.mockito.Mockito.reset(checker);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        assertThat(images.image(1, id, base + "/hero.png", null, "u:z").outcome()).isEqualTo(ImageOutcome.OK);
        assertThat(images.image(1, id, base + "/hero.png", null, "u:z").outcome()).as("iki eşzamanlı hak da geri geldi")
                .isEqualTo(ImageOutcome.OK);
    }

    @Test
    @DisplayName("dakikada 60: reddedilen / elenen istekler sayılmaz; 61. indirme RATE + bekleme; pencere geçince yeniden")
    void perMinuteLimit() throws Exception {
        long id = scanDone(1, "/gallery");
        for (int i = 0; i < 20; i++) {
            assertThat(images.image(1, id, base + "/not-in-scan.png", null, "u:9").outcome()).isEqualTo(ImageOutcome.NOT_FOUND);
        }
        // Site hatası veren indirme siteyi çağırdı → sayılır
        assertThat(images.image(1, id, base + "/flaky.png", null, "u:9").outcome()).isEqualTo(ImageOutcome.FETCH_FAILED);
        for (int i = 1; i < PageResourceImageService.IMAGES_PER_MINUTE; i++) {
            assertThat(images.image(1, id, base + "/dot.bmp", null, "u:9").outcome()).as("indirme " + i).isEqualTo(ImageOutcome.OK);
        }
        int before = hitsOf("/dot.bmp");
        ImageResult limited = images.image(1, id, base + "/dot.bmp", null, "u:9");
        assertThat(limited.outcome()).isEqualTo(ImageOutcome.RATE);
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(hitsOf("/dot.bmp")).isEqualTo(before);
        assertThat(images.image(1, id, base + "/dot.bmp", null, "u:10").outcome()).as("başka kişi etkilenmez").isEqualTo(ImageOutcome.OK);
        clock.addAndGet(61_000);
        assertThat(images.image(1, id, base + "/dot.bmp", null, "u:9").outcome()).isEqualTo(ImageOutcome.OK);
    }

    // ── Projeksiyon ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> scoreOf(Map<String, Object> side, String key) {
        return ((Map<String, Map<String, Object>>) side.get("scores")).get(key);
    }

    String rowDump(long id) {
        return jdbc.queryForList("SELECT * FROM page_resource_scans WHERE id = ?", id).toString();
    }

    @Test
    @DisplayName("projeksiyon: taban saklanan analizle BİREBİR (not, toplamlar, her bileşen puanı ve kaybı); değişiklik yoksa after = before; hiçbir şey yazılmaz")
    void projectionBaselineEqualsStoredAnalysis() throws Exception {
        long id = scanDone(5, "/proj");
        String rowBefore = rowDump(id);
        Map<String, Object> one = scans.one(id, 5);
        Map<String, Object> scan = get(one, "scan");
        Map<String, Object> analysis = get(one, "analysis");
        List<Map<String, Object>> storedScores = get(analysis, "scores");

        Projection p = images.project(5, id, false, List.of(), "u:1");
        assertThat(p.outcome()).isEqualTo(ProjectionOutcome.OK);
        Map<String, Object> before = get(p.data(), "before");
        Map<String, Object> after = get(p.data(), "after");
        assertThat(before.get("grade")).isEqualTo(scan.get("grade"));
        assertThat(before.get("letter")).isEqualTo(scan.get("grade_letter"));
        assertThat(((Number) before.get("page_bytes")).longValue()).isEqualTo(((Number) scan.get("page_bytes")).longValue());
        assertThat(((Number) before.get("image_bytes")).longValue()).isEqualTo(((Number) scan.get("image_bytes")).longValue());
        assertThat(((Number) before.get("load_ms")).longValue()).isEqualTo(((Number) scan.get("load_ms")).longValue());
        Map<String, Object> imgSection = get(analysis, "images");
        assertThat(before.get("large_images")).isEqualTo(imgSection.get("large_count"));
        assertThat(storedScores).hasSize(9);
        for (Map<String, Object> s : storedScores) {
            Map<String, Object> mine = scoreOf(before, (String) s.get("key"));
            assertThat(mine).as((String) s.get("key")).isNotNull();
            assertThat(mine.get("score")).as(s.get("key") + " puanı").isEqualTo(s.get("score"));
            assertThat(mine.get("lost")).as(s.get("key") + " kaybı").isEqualTo(s.get("lost"));
        }
        assertThat(after).isEqualTo(before);
        assertThat(p.data()).containsEntry("applied", 0).containsEntry("ignored", 0).containsEntry("capped", false)
                .containsEntry("algorithm_version", PageResourceAnalyzer.ALGORITHM_VERSION)
                .containsEntry("reused_components", List.of("render_blocking"));
        assertThat(rowDump(id)).as("projeksiyon satıra dokunmaz").isEqualTo(rowBefore);

        // Karışık sayfa (engellenen, kırık, yönlendirilen, tembel, indirilmeyen, stilden gelen, başlıktan boyutlu nesneler):
        // taban yine saklanan analizle birebir kurulur
        afterScan = false;
        long messy = scanDone(1, "/gallery");
        Map<String, Object> messyScan = get(scans.one(messy, 1), "scan");
        Projection m = images.project(1, messy, false, List.of(), "u:1");
        assertThat(m.outcome()).as("karışık sayfada taban").isEqualTo(ProjectionOutcome.OK);
        Map<String, Object> messyBefore = get(m.data(), "before");
        assertThat(messyBefore.get("grade")).isEqualTo(messyScan.get("grade"));
        assertThat(((Number) messyBefore.get("load_ms")).longValue()).isEqualTo(((Number) messyScan.get("load_ms")).longValue());
        assertThat(get(m.data(), "after").toString()).isEqualTo(messyBefore.toString());
    }

    @Test
    @DisplayName("projeksiyon: görsel boyutu değişince görseller / sayfa boyutu puanları analizin KENDİ eğrileriyle değişir; bilinmeyen ve görsel olmayan adresler sayılır, yok sayılır")
    void projectionFollowsAnalyzerRules() throws Exception {
        long id = scanDone(5, "/proj");
        String rowBefore = rowDump(id);
        long noiseBytes = noise.length;
        assertThat(noiseBytes).as("büyük görsel (≥ 200 KB)").isGreaterThan(200 * KB);
        long newBytes = 50 * KB;

        Projection p = images.project(5, id, false, List.of(
                new Replacement(base + "/noise.png", newBytes, 350, 250, "webp"),
                new Replacement(base + "/yok.png", 10, null, null, null),
                new Replacement(base + "/s.css", 10, null, null, null),
                new Replacement(base + "/about", 10, null, null, null)), "u:1");
        assertThat(p.outcome()).isEqualTo(ProjectionOutcome.OK);
        assertThat(p.data()).containsEntry("applied", 1).containsEntry("ignored", 3);
        Map<String, Object> before = get(p.data(), "before");
        Map<String, Object> after = get(p.data(), "after");
        long pageBefore = ((Number) before.get("page_bytes")).longValue();
        long imgBefore = ((Number) before.get("image_bytes")).longValue();
        long pageAfter = pageBefore - noiseBytes + newBytes;
        long imgAfter = imgBefore - noiseBytes + newBytes;
        assertThat(((Number) after.get("page_bytes")).longValue()).isEqualTo(pageAfter);
        assertThat(((Number) after.get("image_bytes")).longValue()).isEqualTo(imgAfter);
        assertThat(before.get("large_images")).isEqualTo(1);
        assertThat(after.get("large_images")).isEqualTo(0);
        // Analizin kendi kuralları: sayfa boyutu eğrisi; görseller = min(bayt eğrisi, 100 − 8 × büyük görsel)
        assertThat(scoreOf(after, "page_weight").get("score"))
                .isEqualTo(PageResourceAnalyzer.curve(pageAfter / (double) MB, PageResourceAnalyzer.WEIGHT_CURVE_MB));
        assertThat(scoreOf(after, "images").get("score"))
                .isEqualTo(Math.min(PageResourceAnalyzer.curve(imgAfter / (double) MB, PageResourceAnalyzer.IMAGE_CURVE_MB), 100));
        assertThat(scoreOf(before, "images").get("score"))
                .isEqualTo(Math.min(PageResourceAnalyzer.curve(imgBefore / (double) MB, PageResourceAnalyzer.IMAGE_CURVE_MB), 92));
        assertThat((Integer) after.get("grade")).isGreaterThanOrEqualTo((Integer) before.get("grade"));
        // Boyuta bağlı olmayan bileşenler aynı kalır
        for (String same : List.of("load_time", "requests", "server_response", "render_blocking", "errors", "compression", "caching")) {
            assertThat(scoreOf(after, same)).as(same).isEqualTo(scoreOf(before, same));
        }
        assertThat(after.get("load_ms")).isEqualTo(before.get("load_ms"));
        assertThat(rowDump(id)).isEqualTo(rowBefore);
    }

    @Test
    @DisplayName("projeksiyon use_measured: taramanın ölçtüğü optimized_bytes uygulanır; açık girdi ölçülmüşün üstüne yazar")
    void projectionUsesMeasuredSavings() throws Exception {
        long id = scanDone(5, "/proj");
        Map<String, Object> noiseItem = item(5, id, "/noise.png");
        assertThat(noiseItem.get("optimized_bytes")).as("tarama büyük PNG'yi yeniden kodlayıp ölçtü").isNotNull();
        long optimized = ((Number) noiseItem.get("optimized_bytes")).longValue();
        assertThat(optimized).isLessThan(noise.length);

        Projection measured = images.project(5, id, true, List.of(), "u:1");
        assertThat(measured.outcome()).isEqualTo(ProjectionOutcome.OK);
        Map<String, Object> before = get(measured.data(), "before");
        Map<String, Object> after = get(measured.data(), "after");
        assertThat(((Number) measured.data().get("applied")).intValue()).isGreaterThanOrEqualTo(1);
        // "Ölçülmüş kazanç" = analizin kuralı: yalnız ölçümü özgünden KÜÇÜK çıkan görseller
        java.util.function.Predicate<Map<String, Object>> saves = i -> "IMAGE".equals(i.get("category"))
                && i.get("optimized_bytes") != null
                && ((Number) i.get("optimized_bytes")).longValue() < ((Number) i.get("bytes")).longValue();
        long measuredItems = items(5, id).stream().filter(saves).count();
        assertThat(((Number) measured.data().get("applied")).longValue()).isEqualTo(measuredItems);
        long saving = items(5, id).stream().filter(saves)
                .mapToLong(i -> ((Number) i.get("bytes")).longValue() - ((Number) i.get("optimized_bytes")).longValue()).sum();
        assertThat(((Number) after.get("image_bytes")).longValue())
                .isEqualTo(((Number) before.get("image_bytes")).longValue() - saving);

        Projection explicit = images.project(5, id, true, List.of(new Replacement(base + "/noise.png", 7 * KB, null, null, null)), "u:1");
        Map<String, Object> after2 = get(explicit.data(), "after");
        long othersSaving = saving - (noise.length - optimized);
        assertThat(((Number) after2.get("image_bytes")).longValue()).as("açık girdi kazanır")
                .isEqualTo(((Number) before.get("image_bytes")).longValue() - othersSaving - noise.length + 7 * KB);
        assertThat(explicit.data().get("applied")).isEqualTo(measured.data().get("applied"));

        // Ölçüm özgünden büyük çıkmışsa (kazanç yok) görsel olduğu gibi kalır — boyut BÜYÜTÜLMEZ
        String stored = jdbc.queryForObject("SELECT items_json FROM page_resource_scans WHERE id = ?", String.class, id);
        assertThat(stored).contains("\"optimized_bytes\":" + optimized);
        jdbc.update("UPDATE page_resource_scans SET items_json = ? WHERE id = ?",
                stored.replace("\"optimized_bytes\":" + optimized, "\"optimized_bytes\":" + (noise.length + 5000L)), id);
        Projection noGain = images.project(5, id, true, List.of(), "u:1");
        assertThat(noGain.outcome()).isEqualTo(ProjectionOutcome.OK);
        assertThat(((Number) noGain.data().get("applied")).longValue()).isEqualTo(measuredItems - 1);
        if (measuredItems == 1) assertThat(get(noGain.data(), "after").toString()).isEqualTo(get(noGain.data(), "before").toString());
    }

    @Test
    @DisplayName("projeksiyon reddi: başka izleme / yok → SCAN_NOT_FOUND; bitmemiş → NOT_DONE; ayrıntı bırakılmış → ITEMS_GONE; eski algoritma / yeniden kurulamayan taban → NOT_PROJECTABLE; kırpılmış analiz işaretli")
    void projectionRefusalsAndCappedFlag() throws Exception {
        long id = scanDone(5, "/proj");
        assertThat(images.project(6, id, false, List.of(), "u:1").outcome()).isEqualTo(ProjectionOutcome.SCAN_NOT_FOUND);
        assertThat(images.project(5, id + 999, false, List.of(), "u:1").outcome()).isEqualTo(ProjectionOutcome.SCAN_NOT_FOUND);

        jdbc.update("UPDATE page_resource_scans SET capped = TRUE WHERE id = ?", id);
        Projection capped = images.project(5, id, false, List.of(), "u:1");
        assertThat(capped.outcome()).isEqualTo(ProjectionOutcome.OK);
        assertThat(capped.data()).containsEntry("capped", true);
        jdbc.update("UPDATE page_resource_scans SET capped = FALSE WHERE id = ?", id);

        jdbc.update("UPDATE page_resource_scans SET algorithm_version = 2 WHERE id = ?", id);
        Projection old = images.project(5, id, false, List.of(), "u:1");
        assertThat(old.outcome()).isEqualTo(ProjectionOutcome.NOT_PROJECTABLE);
        assertThat(old.reason()).isEqualTo("algorithm");
        jdbc.update("UPDATE page_resource_scans SET algorithm_version = ? WHERE id = ?", PageResourceAnalyzer.ALGORITHM_VERSION, id);

        // Saklanan özet nesnelerden yeniden kurulamıyor (not oynanmış) → hayali fark göstermek yerine reddedilir
        Integer grade = jdbc.queryForObject("SELECT grade FROM page_resource_scans WHERE id = ?", Integer.class, id);
        jdbc.update("UPDATE page_resource_scans SET grade = ? WHERE id = ?", grade - 7, id);
        Projection tampered = images.project(5, id, false, List.of(), "u:1");
        assertThat(tampered.outcome()).isEqualTo(ProjectionOutcome.NOT_PROJECTABLE);
        assertThat(tampered.reason()).isEqualTo("baseline");
        jdbc.update("UPDATE page_resource_scans SET grade = ? WHERE id = ?", grade, id);
        jdbc.update("UPDATE page_resource_scans SET analysis_json = '{bozuk' WHERE id = ?", id);
        assertThat(images.project(5, id, false, List.of(), "u:1").reason()).isEqualTo("baseline");

        jdbc.update("UPDATE page_resource_scans SET status = 'FAILED' WHERE id = ?", id);
        assertThat(images.project(5, id, false, List.of(), "u:1").outcome()).isEqualTo(ProjectionOutcome.NOT_DONE);
        jdbc.update("UPDATE page_resource_scans SET status = 'RUNNING' WHERE id = ?", id);
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE page_resource_scans SET heartbeat_ms = ? WHERE id = ?", now, id);
        assertThat(images.project(5, id, false, List.of(), "u:1").outcome()).isEqualTo(ProjectionOutcome.NOT_DONE);
        jdbc.update("UPDATE page_resource_scans SET status = 'DONE', items_json = NULL, items_kept = FALSE WHERE id = ?", id);
        assertThat(images.project(5, id, false, List.of(), "u:1").outcome()).isEqualTo(ProjectionOutcome.ITEMS_GONE);
    }

    @Test
    @DisplayName("projeksiyon dakikada 30: reddedilen (tarama yok / bitmemiş) istek sayılmaz; 31. RATE + bekleme; çok sayfalı tarama ANA sayfayı hesaplar")
    void projectionRateLimitAndSiteScan() throws Exception {
        PageResourceScanService.Start st = scans.start(monitor(8, "/proj"), "u:site", "Ayşe", new PageResourceScanService.SiteOptions(2));
        long id = ((Number) st.scan().get("id")).longValue();
        long until = System.currentTimeMillis() + 30_000;
        Map<String, Object> scan = null;
        while (System.currentTimeMillis() < until) {
            scan = get(scans.one(id, 8), "scan");
            if (!"RUNNING".equals(scan.get("status"))) break;
            Thread.sleep(40);
        }
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("scope")).isEqualTo("site");

        for (int i = 0; i < 40; i++) {
            assertThat(images.project(8, id + 999, false, List.of(), "u:7").outcome()).isEqualTo(ProjectionOutcome.SCAN_NOT_FOUND);
        }
        for (int i = 0; i < PageResourceImageService.PROJECTIONS_PER_MINUTE; i++) {
            Projection p = images.project(8, id, false, List.of(), "u:7");
            assertThat(p.outcome()).as("projeksiyon " + i).isEqualTo(ProjectionOutcome.OK);
            Map<String, Object> before = get(p.data(), "before");
            assertThat(before.get("grade")).as("ana sayfanın notu").isEqualTo(scan.get("grade"));
        }
        Projection limited = images.project(8, id, false, List.of(), "u:7");
        assertThat(limited.outcome()).isEqualTo(ProjectionOutcome.RATE);
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(images.project(8, id, false, List.of(), "u:8").outcome()).isEqualTo(ProjectionOutcome.OK);
        clock.addAndGet(61_000);
        assertThat(images.project(8, id, false, List.of(), "u:7").outcome()).isEqualTo(ProjectionOutcome.OK);
    }

    @Test
    @DisplayName("restore: found_in listede o adresi taşıyan İLK nesneye bağlanır; sayfa / bilinmeyen adres HTML'e (0)")
    void restoreLinksParents() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("url", "https://a.com/", "category", "HTML", "page_load", true));
        rows.add(Map.of("url", "https://a.com/s.css", "category", "CSS", "page_load", true, "found_in", "page"));
        rows.add(Map.of("url", "https://a.com/bg.png", "category", "IMAGE", "page_load", true, "found_in", "https://a.com/s.css"));
        rows.add(Map.of("url", "https://a.com/x.png", "category", "IMAGE", "page_load", true, "found_in", "https://a.com/yok.css"));
        List<ScanItem> items = PageResourceImageService.restore(rows);
        assertThat(items).extracting(i -> i.parent).containsExactly(-1, 0, 1, 0);
        assertThat(items).extracting(i -> i.seq).containsExactly(0, 1, 2, 3);
        assertThat(PageResourceImageService.restore(rows).get(2)).as("her çağrıda yeni kopya").isNotSameAs(items.get(2));
    }
}
