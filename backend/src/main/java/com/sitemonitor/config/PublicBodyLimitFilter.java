package com.sitemonitor.config;

import com.sitemonitor.util.Msg;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Oturumsuz (PUBLIC) uçların istek gövdesi sınırı (kapı incelemesi 2026-11-15).
 *
 * <p>{@code AuthInterceptor} oturumsuz isteği gövde okunmadan reddeder — YALNIZ {@code AuthInterceptor.PUBLIC} uçları gövdeyi
 * kimliksiz biri için ayrıştırır. Jackson'ın genel sınırı (32 MiB belge / 16 MiB metin, {@code application.properties})
 * oturumlu büyük gövdeler (envanter içe aktarımı) için geniştir; küçük bir anahtar-değer selinden oluşan 32 MiB'lik gövde
 * {@code Map} olarak yüzlerce MB tutabilir. Bu süzgeç oturumsuz uçlara kendi sınırını koyar:
 * {@code Content-Length} sınırı aşıyorsa gövde OKUNMADAN 413; uzunluk yoksa (chunked) akış SAYILIR ve sınır aşılınca okuma
 * {@link BodyLimitExceededException} ile kesilir (Jackson onu okunamayan gövde olarak sarar → 413).</p>
 *
 * <p>Yalnız gövde taşıyan yöntemler (POST / PUT / PATCH / DELETE); listede olmayan yol dokunulmadan geçer.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PublicBodyLimitFilter extends OncePerRequestFilter {

    /** Giriş / çıkış / kodla giriş / e-posta onay bağlantıları — gövde birkaç yüz bayt. */
    static final long SMALL_BYTES = 64L * 1024;
    /** Giriş sayfası "sorun bildir": en fazla 5 ekran görüntüsü × 2 MiB base64 (LoginHelpController) + metin payı. */
    static final long LOGIN_HELP_BYTES = 12L * 1024 * 1024;
    /** Çökme bildirimi: hata metni ≤ 10 000, bağlam haritası ≤ 10 000 öğe (ClientErrorController) — 2 MiB bol. */
    static final long CLIENT_ERROR_BYTES = 2L * 1024 * 1024;

    private static final Map<String, Long> CAPS = Map.ofEntries(
            Map.entry("/api/login", SMALL_BYTES),
            Map.entry("/api/logout", SMALL_BYTES),
            Map.entry("/api/login/otp/request", SMALL_BYTES),
            Map.entry("/api/login/otp/verify", SMALL_BYTES),
            Map.entry("/api/weekly-reports/approve-link/confirm", SMALL_BYTES),
            Map.entry("/api/weekly-reports/approve-link/reject", SMALL_BYTES),
            Map.entry("/api/tech-error-reports/approve-link/confirm", SMALL_BYTES),
            Map.entry("/api/tech-error-reports/approve-link/reject", SMALL_BYTES),
            Map.entry("/api/login-help", LOGIN_HELP_BYTES),
            Map.entry("/api/client-error-report", CLIENT_ERROR_BYTES));

    /** Yolun gövde sınırı; sınırsız (liste dışı) → -1. */
    static long capFor(String path) {
        if (path == null) return -1;
        Long c = CAPS.get(path);
        return c == null ? -1 : c;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        if (!("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m))) return true;
        return capFor(RequestPathFirewallFilter.lookupPath(request)) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long cap = capFor(RequestPathFirewallFilter.lookupPath(request));
        long declared = request.getContentLengthLong();
        if (cap >= 0 && declared > cap) {
            tooLarge(request, response, cap);
            return;
        }
        chain.doFilter(cap < 0 ? request : new Capped(request, cap), response);
    }

    static void tooLarge(HttpServletRequest request, HttpServletResponse response, long cap) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String kb = String.valueOf(cap / 1024);
        String msg = Msg.t(request, "Gönderilen veri çok büyük (en fazla " + kb + " KB). Formu sadeleştirip tekrar deneyin.",
                "The data sent is too large (at most " + kb + " KB). Simplify the form and try again.");
        String rid = CorrelationIdFilter.current();
        String json = "{\"success\":false,\"code\":\"PAYLOAD_TOO_LARGE\",\"error\":\"" + escape(msg) + "\""
                + (rid == null ? "" : ",\"request_id\":\"" + escape(rid) + "\"") + "}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    /** Okunan bayt sınırı aştı — Jackson / Spring bunu okunamayan gövde olarak sarar ({@code GlobalExceptionHandler} → 413). */
    public static final class BodyLimitExceededException extends IOException {
        private final long limit;

        BodyLimitExceededException(long limit) {
            super("request body exceeds " + limit + " bytes");
            this.limit = limit;
        }

        public long limit() { return limit; }
    }

    /** Akışı sayan sarmalayıcı (Content-Length yok ya da yalan söylüyor). */
    static final class Capped extends HttpServletRequestWrapper {
        private final long cap;
        private ServletInputStream stream;
        private BufferedReader reader;

        Capped(HttpServletRequest request, long cap) {
            super(request);
            this.cap = cap;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CountingStream(super.getInputStream(), cap);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String enc = getCharacterEncoding();
                Charset cs;
                try { cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc); }
                catch (RuntimeException e) { cs = StandardCharsets.UTF_8; }
                reader = new BufferedReader(new InputStreamReader(getInputStream(), cs));
            }
            return reader;
        }
    }

    static final class CountingStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long cap;
        private long count;

        CountingStream(ServletInputStream in, long cap) {
            this.in = in;
            this.cap = cap;
        }

        private void add(long n) throws BodyLimitExceededException {
            if (n <= 0) return;
            count += n;
            if (count > cap) throw new BodyLimitExceededException(cap);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            add(n);
            return n;
        }

        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
        @Override public int available() throws IOException { return in.available(); }
        @Override public void close() throws IOException { in.close(); }
    }
}
