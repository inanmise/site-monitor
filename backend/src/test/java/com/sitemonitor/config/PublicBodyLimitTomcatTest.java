package com.sitemonitor.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicBodyLimitFilter} GERÇEK Tomcat'te (2026-11-17, inceleme bulgusu). MockMvc formu kendisi ayrıştırdığı için
 * açığı göremez: Tomcat'te {@code @RequestParam} / {@code getParameter} gövdeyi sunucunun KENDİ ham akışından okur, süzgecin
 * sayan sarmalayıcısını hiç kullanmaz. Content-Length'siz (chunked) bir form gönderimi bu yüzden 64 KiB'lik onay bağlantısı
 * sınırını aşıp Tomcat'in genel 2 MB form tavanına dek ayrıştırılıyordu. Düzeltme: uzunluğu bildirilmemiş form / multipart
 * gövde OKUNMADAN 411.
 *
 * <p>Ham soket istemcisi: gövde arka planda yazılır, yanıt EOF'a dek okunur (Connection: close); her bekleme sınırlı.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PublicBodyLimitTomcatTest {

    private static Tomcat tomcat;
    private static int port;
    private static Path base;
    private static final AtomicInteger SERVLET_CALLS = new AtomicInteger();

    /** Onay formu gibi davranır: form → getParameter("reason"); JSON → gövdeyi sayan akıştan okur. */
    static final class Echo extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            SERVLET_CALLS.incrementAndGet();
            String ct = req.getContentType() == null ? "" : req.getContentType();
            if (ct.startsWith("application/json")) {
                try (InputStream in = req.getInputStream()) {
                    long n = 0;
                    byte[] buf = new byte[8192];
                    for (int r; (r = in.read(buf)) > 0; ) n += r;
                    write(resp, 200, "json=" + n);
                } catch (PublicBodyLimitFilter.BodyLimitExceededException e) {
                    write(resp, 413, "capped");
                }
                return;
            }
            String reason = req.getParameter("reason");
            write(resp, 200, "reason=" + (reason == null ? -1 : reason.length()));
        }

        /** Content-Length'li düz yanıt (chunked yanıt çerçevesi gövdeyi bozmasın). */
        private static void write(HttpServletResponse resp, int status, String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            resp.setStatus(status);
            resp.setContentType("text/plain");
            resp.setContentLength(b.length);
            resp.getOutputStream().write(b);
        }
    }

    @BeforeAll
    static void start() throws LifecycleException, IOException {
        base = Files.createTempDirectory("body-limit-tomcat");
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        Connector connector = tomcat.getConnector();
        connector.setProperty("connectionTimeout", "10000");
        Context ctx = tomcat.addContext("", base.toString());
        FilterDef def = new FilterDef();
        def.setFilterName("publicBodyLimit");
        def.setFilter(new PublicBodyLimitFilter());
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName("publicBodyLimit");
        map.addURLPattern("/*");
        ctx.addFilterMap(map);
        Tomcat.addServlet(ctx, "echo", new Echo());
        ctx.addServletMappingDecoded("/*", "echo");
        tomcat.start();
        port = connector.getLocalPort();
    }

    @AfterAll
    static void stop() throws LifecycleException {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    record Response(int status, String body) { }

    /** Ham HTTP/1.1 POST; chunked = Transfer-Encoding: chunked (Content-Length YOK). */
    private static Response post(String path, String contentType, byte[] body, boolean chunked) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20_000);
            OutputStream out = s.getOutputStream();
            String head = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + contentType
                    + "\r\nConnection: close\r\n"
                    + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + body.length + "\r\n") + "\r\n";
            Thread writer = new Thread(() -> {
                try {
                    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    if (chunked) {
                        for (int off = 0; off < body.length; off += 8192) {
                            int n = Math.min(8192, body.length - off);
                            out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.write(body, off, n);
                            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        }
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    } else {
                        out.write(body);
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // Sunucu yanıtlayıp kapattıysa yazma yarıda kalabilir — yanıt yine okunur.
                }
            }, "body-limit-writer");
            writer.setDaemon(true);
            writer.start();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            try {
                for (int r; (r = in.read(buf)) > 0; ) got.write(buf, 0, r);
            } catch (IOException e) {
                // Bağlantı sıfırlandıysa o ana dek okunan yanıt kullanılır.
            }
            writer.join(20_000);
            String raw = got.toString(StandardCharsets.UTF_8);
            assertThat(raw).as("yanıt yok").startsWith("HTTP/1.1 ");
            int status = Integer.parseInt(raw.substring(9, 12));
            int split = raw.indexOf("\r\n\r\n");
            return new Response(status, split < 0 ? "" : raw.substring(split + 4));
        }
    }

    private static byte[] form(int reasonChars) {
        return ("token=t&reason=" + "a".repeat(reasonChars)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("chunked, sınırı (64 KiB) aşan onay formu → 411, gövde AYRIŞTIRILMAZ (servlet çağrılmaz) — düzeltmeden "
            + "önce Tomcat getParameter ile 100 KB'lık notu okuyordu")
    void chunkedOversizedFormIsRefusedUnparsed() throws Exception {
        SERVLET_CALLS.set(0);
        Response r = post("/api/weekly-reports/approve-link/reject", "application/x-www-form-urlencoded",
                form(100_000), true);
        assertThat(r.status()).as(r.body()).isEqualTo(411);
        assertThat(r.body()).contains("Form okunamadı").doesNotContain("reason=");
        assertThat(SERVLET_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("chunked küçük form da 411 (uzunluk bildirilmeden form kabul edilmez); chunked multipart /api/login → 411 JSON")
    void chunkedSmallFormAndMultipartRefused() throws Exception {
        SERVLET_CALLS.set(0);
        Response r = post("/api/tech-error-reports/approve-link/confirm", "application/x-www-form-urlencoded",
                form(10), true);
        assertThat(r.status()).isEqualTo(411);
        Response m = post("/api/login", "multipart/form-data; boundary=xyz",
                "--xyz\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--xyz--\r\n".getBytes(StandardCharsets.UTF_8), true);
        assertThat(m.status()).isEqualTo(411);
        assertThat(m.body()).contains("\"code\":\"LENGTH_REQUIRED\"");
        assertThat(SERVLET_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("Content-Length'li sınır içi form geçer ve ayrıştırılır; Content-Length'i sınırı aşan form → 413 (servlet yok)")
    void declaredFormBoundedByContentLength() throws Exception {
        SERVLET_CALLS.set(0);
        Response ok = post("/api/weekly-reports/approve-link/reject", "application/x-www-form-urlencoded", form(5), false);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("reason=5");
        assertThat(SERVLET_CALLS.get()).isEqualTo(1);
        Response big = post("/api/weekly-reports/approve-link/reject", "application/x-www-form-urlencoded",
                form(100_000), false);
        assertThat(big.status()).isEqualTo(413);
        assertThat(SERVLET_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("chunked JSON gerçek Tomcat'te SAYILIR: sınır içi okunur, sınırı aşan okuma BodyLimitExceededException ile kesilir")
    void chunkedJsonIsCounted() throws Exception {
        Response small = post("/api/login", "application/json", "{\"username\":\"a\"}".getBytes(StandardCharsets.UTF_8), true);
        assertThat(small.status()).isEqualTo(200);
        assertThat(small.body()).isEqualTo("json=16");
        byte[] big = ("{\"v\":\"" + "a".repeat((int) PublicBodyLimitFilter.SMALL_BYTES + 100) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Response over = post("/api/login", "application/json", big, true);
        assertThat(over.status()).isEqualTo(413);
        assertThat(over.body()).isEqualTo("capped");
    }
}
