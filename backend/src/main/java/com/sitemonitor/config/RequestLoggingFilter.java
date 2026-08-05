package com.sitemonitor.config;

import com.sitemonitor.service.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logs all incoming HTTP requests at DEBUG level: timing, method, URI, query,
 * client IP, headers (sensitive redacted), request body, response status,
 * response body, total duration.
 *
 * Skipped paths: /health, static assets — too noisy.
 * Sensitive headers redacted: Authorization, Cookie, etc.
 * Sensitive body fields masked: password, token, etc.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    // required=false: @WebMvcTest filtreyi yükler ama bu @Component'i sağlamaz → null'da fallback.
    @Autowired(required = false)
    private ClientIpResolver clientIpResolver;

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie",
            "x-csrf-token", "x-auth-token", "x-api-key",
            "x-access-token", "x-refresh-token",
            "proxy-authorization"
    );

    /**
     * Sensitive field-name token. Used inside three regex patterns:
     * - JSON body:  "<field>" : "value"        → value masked
     * - Form body:  <field>=value              → value masked
     * - URL query:  ?<field>=value             → value masked
     *
     * Covers EN + TR variations of password, secrets, tokens, keys,
     * service passwords (SMTP/DB/mail/proxy), PIN/OTP, session IDs,
     * private keys, client secrets.
     */
    private static final String SENSITIVE_FIELD_NAMES =
            // Generic passwords (EN + TR)
            "password|passwd|pwd|pass|parola|sifre|" +
            // Password variations
            "old_password|new_password|current_password|confirm_password|password_confirmation|" +
            "oldPassword|newPassword|currentPassword|confirmPassword|passwordConfirmation|" +
            "old_pass|new_pass|currentPass|oldPass|newPass|" +
            // Service passwords
            "smtp_pass|smtpPass|smtp_password|smtpPassword|" +
            "db_password|dbPassword|db_pass|dbPass|" +
            "mail_password|mailPassword|mail_pass|mailPass|" +
            "proxy_password|proxyPassword|proxy_pass|proxyPass|" +
            "ldap_password|ldapPassword|" +
            // Tokens, bearer
            "token|access_token|accessToken|refresh_token|refreshToken|" +
            "auth_token|authToken|bearer|bearer_token|bearerToken|" +
            "csrf|csrf_token|csrfToken|xsrf_token|xsrfToken|" +
            // Secrets, API keys
            "secret|secret_key|secretKey|api_key|apiKey|api_secret|apiSecret|" +
            "client_secret|clientSecret|client_id|clientId|" +
            "private_key|privateKey|encryption_key|encryptionKey|" +
            // Session / cookies (in body)
            "session_id|sessionId|jsessionid|sid|" +
            // PINs / OTPs / verification codes
            "pin|pin_code|pinCode|otp|otp_code|otpCode|verification_code|verificationCode|" +
            "mfa_code|mfaCode|two_factor|twoFactor";

    private static final Pattern SENSITIVE_JSON_FIELDS = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_FIELD_NAMES + ")\"\\s*:\\s*)\"[^\"]*\""
    );

    private static final Pattern SENSITIVE_FORM_FIELDS = Pattern.compile(
            "(?i)((?:^|&)(?:" + SENSITIVE_FIELD_NAMES + ")=)[^&]*"
    );

    private static final Pattern SENSITIVE_QUERY_FIELDS = Pattern.compile(
            "(?i)([?&](?:" + SENSITIVE_FIELD_NAMES + ")=)[^&]*"
    );

    private static final int MAX_BODY_LOG = 2000;
    // Yanıt gövdesinden loglama için yakalanacak azami bayt. truncate() zaten MAX_BODY_LOG
    // karaktere kısar; bu kadar yakalama yeter, gerisi atılır (bellek koruması).
    private static final int CAPTURE_CAP = 8 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        if (!log.isTraceEnabled() || shouldSkip(req)) {
            chain.doFilter(req, resp);
            return;
        }

        // Spring Framework 7: ContentCachingRequestWrapper artık zorunlu bir cache limiti ister.
        // Sadece TRACE log'da çalışırız ve gövdeyi zaten truncate ederiz → 64 KB fazlasıyla yeter.
        ContentCachingRequestWrapper wReq = new ContentCachingRequestWrapper(req, 64 * 1024);
        // Yanıtı TEE'leriz: baytlar gerçek çıkışa doğrudan akar (Tomcat gzip sıkıştırması ve
        // Content-Length aynen çalışır), aynı anda loglama için sınırlı bir tampona kopyalanır.
        // Buffer-and-replay (ContentCachingResponseWrapper.copyBodyToResponse) KULLANMAYIZ; o yöntem
        // sıkıştırılmamış uzunlukla Content-Length yazıp connector gzip'iyle çakışıyor ve TRACE'te
        // yanıtı bozup beyaz ekrana yol açıyordu.
        TeeResponseWrapper wResp = new TeeResponseWrapper(resp, CAPTURE_CAP);
        long start = System.currentTimeMillis();
        String query = req.getQueryString();
        String fullUri = req.getRequestURI() + (query != null ? "?" + sanitizeQuery(query) : "");

        log.trace(">>> {} {} from {} | headers={}",
                req.getMethod(), fullUri, clientIp(req), sanitizedHeaders(req));

        try {
            chain.doFilter(wReq, wResp);
        } finally {
            // Writer buffer'ındaki son baytları tee'ye (gerçek çıkış + capture) it ki loglanan
            // gövde eksik kalmasın. Content-Length'e DOKUNMAYIZ; gövde gerçek yanıta zaten yazıldı.
            try { wResp.flushBuffer(); } catch (Exception ignore) { /* yanıt kapanmış olabilir */ }
            long durationMs = System.currentTimeMillis() - start;
            String reqBody  = redact(bodyOf(wReq.getContentAsByteArray()));
            String respBody = redact(bodyOf(wResp.getCaptured()));
            log.trace("<<< {} {} -> {} ({} ms) | reqBody={} | respBody={}",
                    req.getMethod(), fullUri, wResp.getStatus(), durationMs,
                    truncate(reqBody), truncate(respBody));
        }
    }

    private boolean shouldSkip(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        return uri.equals("/health") || uri.equals("/favicon.ico")
                || uri.startsWith("/assets/") || uri.startsWith("/static/")
                || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".map")
                || uri.endsWith(".png") || uri.endsWith(".svg") || uri.endsWith(".woff2")
                || uri.endsWith(".ico");
    }

    private static final String MASK = "*******";

    private String sanitizedHeaders(HttpServletRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names == null) return "{}";
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = SENSITIVE_HEADERS.contains(name.toLowerCase())
                    ? MASK
                    : req.getHeader(name);
            // Log injection (CWE-117): header değerindeki CR/LF'i temizle (sahte log satırı enjekte edilmesin).
            if (value != null) value = value.replaceAll("[\\r\\n]", " ");
            map.put(name, value);
        }
        return map.toString();
    }

    private String bodyOf(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        return new String(buf, StandardCharsets.UTF_8);
    }

    private String redact(String body) {
        if (body == null || body.isEmpty()) return body;
        // JSON: "field":"value"
        String redacted = SENSITIVE_JSON_FIELDS.matcher(body).replaceAll("$1\"" + MASK + "\"");
        // Form: field=value&...
        redacted = SENSITIVE_FORM_FIELDS.matcher(redacted).replaceAll("$1" + MASK);
        return redacted;
    }

    private String sanitizeQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        return SENSITIVE_QUERY_FIELDS.matcher("?" + query).replaceAll("$1" + MASK).substring(1);
    }

    private String truncate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_BODY_LOG ? s : s.substring(0, MAX_BODY_LOG) + "...[truncated]";
    }

    private String clientIp(HttpServletRequest req) {
        if (clientIpResolver != null) return clientIpResolver.resolve(req);
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /**
     * Yanıtı TEE'leyen wrapper: gerçek çıkışa normal yazar (sıkıştırma/Content-Length aynen çalışır),
     * aynı anda loglama için sınırlı bir tampona kopyalar. getOutputStream/getWriter tek seferlik ve
     * birbirini dışlar (servlet sözleşmesi). copyBodyToResponse YOKtur — replay yapılmaz.
     */
    private static final class TeeResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private final int cap;
        private TeeServletOutputStream tee;
        private PrintWriter writer;

        TeeResponseWrapper(HttpServletResponse response, int cap) {
            super(response);
            this.cap = cap;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() already called on this response");
            }
            if (tee == null) {
                tee = new TeeServletOutputStream(getResponse().getOutputStream(), capture, cap);
            }
            return tee;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (tee != null) {
                throw new IllegalStateException("getOutputStream() already called on this response");
            }
            if (writer == null) {
                String enc = getCharacterEncoding();
                TeeServletOutputStream out =
                        new TeeServletOutputStream(getResponse().getOutputStream(), capture, cap);
                this.tee = out;
                writer = new PrintWriter(new OutputStreamWriter(
                        out, enc != null ? enc : StandardCharsets.UTF_8.name()));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
            if (tee != null) tee.flush();
            super.flushBuffer();
        }

        byte[] getCaptured() {
            return capture.toByteArray();
        }
    }

    /**
     * Tüm yazımları gerçek {@code delegate}'e geçirir; bir yandan {@code capture}'a (cap'e kadar)
     * kopyalar. flush/close yalnız delegate'e gider — capture açık kalır, sonradan okunur.
     */
    private static final class TeeServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final ByteArrayOutputStream capture;
        private final int cap;

        TeeServletOutputStream(ServletOutputStream delegate, ByteArrayOutputStream capture, int cap) {
            this.delegate = delegate;
            this.capture = capture;
            this.cap = cap;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            if (capture.size() < cap) capture.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            int remaining = cap - capture.size();
            if (remaining > 0) capture.write(b, off, Math.min(len, remaining));
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }
    }
}
