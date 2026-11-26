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
 * {@link BodyLimitExceededException} ile kesilir (Jackson onu okunamayan gövde olarak sarar → 413). Form ve multipart
 * gövdesini sunucu kendi ham akışından ayrıştırır (sayılan akışı kullanmaz): uzunluğu bildirilmemiş form / multipart
 * gövdesi OKUNMADAN 411 {@code LENGTH_REQUIRED} alır (inceleme 2026-11-17).</p>
 *
 * <p>Yalnız gövde taşıyan yöntemler (POST / PUT / PATCH / DELETE); listede olmayan yol dokunulmadan geçer. E-posta onay
 * bağlantılarının form gönderimleri ({@code …/approve-link/confirm|reject}) tarayıcıda açık bir sayfadır: sınır aşımı
 * onlara JSON değil kısa bir HTML sayfasıyla anlatılır (arka uç inceleme 2026-11-15). Bu tek süzgeç {@code /api/login-help}
 * sınırının da TEK kaynağıdır (eski ayrı {@code WebConfig.loginHelpBodyLimitFilter} kaldırıldı).</p>
 *
 * <p>Oturumlu ama gövdesi küçük kalması gereken uçlar da buradan sınırlanır (2026-11-26, nöbet rutini inceleme #7): nöbet
 * rutini şablonu {@code PUT /api/oncall-routines/teams/{id}/template} 512 KB — Jackson gövdeyi TAMAMEN ayrıştırmadan
 * önce. Bu uçların sınır aşımı 413 değil, formun alanının altında gösterilen alan hatasıdır (400 {@code VALIDATION_FAILED},
 * alan {@code template}); chunked gövde de sayılır ({@link BodyLimitExceededException#field()}).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PublicBodyLimitFilter extends OncePerRequestFilter {

    /** Giriş / çıkış / kodla giriş / e-posta onay bağlantıları — gövde birkaç yüz bayt. */
    static final long SMALL_BYTES = 64L * 1024;
    /**
     * Giriş sayfası "sorun bildir": en fazla 5 ekran görüntüsü × 1 MB (çözülmüş; base64 ≈ 1,37 M karakter) + metin ≤ ~7 KB
     * (LoginHelpController) ≈ 7 MB → 8 MiB rahat tavan. Tek sınır (eski ayrı süzgeç de 8 MiB'ti).
     */
    static final long LOGIN_HELP_BYTES = 8L * 1024 * 1024;
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

    /** Nöbet rutini şablonu (oturumlu) — kanonik şablonun saklama sınırıyla aynı. */
    static final long ONCALL_TEMPLATE_BYTES = com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.MAX_JSON_BYTES;
    private static final String ONCALL_TEMPLATE_PREFIX = "/api/oncall-routines/teams/";
    private static final String ONCALL_TEMPLATE_SUFFIX = "/template";

    /** {@code /api/oncall-routines/teams/<1–18 rakam>/template} mı (düzenli ifade yok — doğrusal). */
    static boolean oncallTemplatePath(String path) {
        if (path == null || !path.startsWith(ONCALL_TEMPLATE_PREFIX) || !path.endsWith(ONCALL_TEMPLATE_SUFFIX)) return false;
        int from = ONCALL_TEMPLATE_PREFIX.length();
        int to = path.length() - ONCALL_TEMPLATE_SUFFIX.length();
        if (to - from < 1 || to - from > 18) return false;
        for (int i = from; i < to; i++) {
            char c = path.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Sınır aşımı alan hatası olarak anlatılan yolun alan adı; yoksa null (413). */
    static String fieldFor(String path) {
        return oncallTemplatePath(path) ? "template" : null;
    }

    /** Alan hatası metni (TR, EN) — {@code OnCallRoutineTemplate.checkSize} ile aynı. */
    public static String[] fieldTexts(String field) {
        long kb = ONCALL_TEMPLATE_BYTES / 1024;
        return new String[] {
                "Şablon çok büyük (en çok " + kb + " KB). Açıklamaları kısaltın ya da kullanılmayan görevleri silin.",
                "The template is too large (at most " + kb + " KB). Shorten the descriptions or delete tasks you don't use."};
    }

    /** Tarayıcı form gönderimi olan onay bağlantısı uçları — sınır aşımı HTML sayfasıyla yanıtlanır. */
    private static final java.util.Set<String> HTML_FORM_PATHS = java.util.Set.of(
            "/api/weekly-reports/approve-link/confirm",
            "/api/weekly-reports/approve-link/reject",
            "/api/tech-error-reports/approve-link/confirm",
            "/api/tech-error-reports/approve-link/reject");

    /** Sınır aşımı HTML sayfasıyla mı anlatılır (onay bağlantısı formu). */
    static boolean htmlFormPath(String path) {
        return path != null && HTML_FORM_PATHS.contains(path);
    }

    /** Yolun gövde sınırı; sınırsız (liste dışı) → -1. */
    static long capFor(String path) {
        if (path == null) return -1;
        Long c = CAPS.get(path);
        if (c != null) return c;
        return oncallTemplatePath(path) ? ONCALL_TEMPLATE_BYTES : -1;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        if (!("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m))) return true;
        return capFor(RequestPathFirewallFilter.lookupPath(request)) < 0;
    }

    /**
     * Gövdeyi sunucunun (Tomcat) KENDİSİ ayrıştırdığı içerik türü mü — form ({@code getParameter}) ve multipart
     * ({@code getParts}). Bu iki tür {@link Capped} sarmalayıcısının sayan akışını KULLANMAZ: Tomcat gövdeyi kendi ham
     * akışından okur (sarmalayıcının {@code getParameter} çağrısı alttaki isteğe iletilir). Uzunluğu bildirilmiş gövdede
     * Tomcat tam o kadar okur (yukarıdaki Content-Length denetimi yeter); uzunluğu bildirilmemiş (chunked) gövdede ise
     * yalnız Tomcat'in genel tavanı (form 2 MB, multipart 12 MB) kalırdı — inceleme 2026-11-17: 64 KiB'lik onay bağlantısı
     * sınırı chunked form gönderimiyle aşılabiliyordu.
     */
    static boolean containerParsedBody(String contentType) {
        if (contentType == null) return false;
        int semi = contentType.indexOf(';');
        String base = (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        return "application/x-www-form-urlencoded".equals(base) || base.startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPathFirewallFilter.lookupPath(request);
        long cap = capFor(path);
        long declared = request.getContentLengthLong();
        String field = fieldFor(path);
        if (cap >= 0 && declared > cap) {
            if (htmlFormPath(path)) tooLargeHtml(request, response);
            else if (field != null) fieldTooLarge(request, response, field);
            else tooLarge(request, response, cap);
            return;
        }
        // Uzunluğu bildirilmemiş form / multipart gövdesi sayılamaz (Tomcat sarmalayıcıyı atlar) → OKUNMADAN 411.
        // Tarayıcılar ve uygulamanın kendi istemcisi bu küçük gövdeleri her zaman Content-Length ile gönderir.
        if (cap >= 0 && declared < 0 && containerParsedBody(request.getContentType())) {
            if (htmlFormPath(path)) lengthRequiredHtml(request, response);
            else lengthRequired(request, response);
            return;
        }
        chain.doFilter(cap < 0 ? request : new Capped(request, cap, field), response);
    }

    /** 400 alan hatası — {@code FieldValidationException} gövdesiyle aynı biçim ({@code fields} + {@code field}). */
    static void fieldTooLarge(HttpServletRequest request, HttpServletResponse response, String field) throws IOException {
        String[] t = fieldTexts(field);
        String msg = Msg.t(request, t[0], t[1]);
        response.setStatus(400);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String rid = CorrelationIdFilter.current();
        String json = "{\"success\":false,\"code\":\"VALIDATION_FAILED\",\"error\":\"" + escape(msg) + "\""
                + (rid == null ? "" : ",\"request_id\":\"" + escape(rid) + "\"")
                + ",\"fields\":{\"" + escape(field) + "\":\"" + escape(msg) + "\"},\"field\":\"" + escape(field) + "\"}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 411 — uzunluğu bildirilmemiş, sunucunun kendisinin ayrıştırdığı (form / multipart) oturumsuz gövde. */
    static void lengthRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(411);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String msg = Msg.t(request,
                "İstek gövdesinin uzunluğu bildirilmedi (Content-Length yok), bu yüzden okunmadı. Sayfayı yenileyip yeniden "
                        + "gönderin; sorun sürerse istemcinin gövdeyi parça parça (chunked) göndermesini kapatın.",
                "The request body length was not declared (no Content-Length), so it was not read. Reload the page and send it "
                        + "again; if it keeps happening, stop the client from sending the body in chunks.");
        String rid = CorrelationIdFilter.current();
        String json = "{\"success\":false,\"code\":\"LENGTH_REQUIRED\",\"error\":\"" + escape(msg) + "\""
                + (rid == null ? "" : ",\"request_id\":\"" + escape(rid) + "\"") + "}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Onay bağlantısı formunun 411'i — tarayıcıda ham JSON yerine kısa bir HTML sayfası. */
    static void lengthRequiredHtml(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String title = Msg.t(request, "Form okunamadı", "The form could not be read");
        String msg = Msg.t(request,
                "Karar kaydedilmedi — form beklenmeyen biçimde (uzunluğu bildirilmeden) gönderildi. E-postadaki bağlantıyı"
                        + " yeniden açıp formu tekrar gönderin.",
                "The decision was not saved — the form was sent in an unexpected way (without its length). Open the link in"
                        + " the e-mail again and send the form once more.");
        htmlPage(request, response, 411, title, msg);
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

    /**
     * Onay bağlantısı formunun sınır aşımı: tarayıcıda ham JSON yerine kısa bir HTML sayfası (form alanları {@code maxlength}
     * taşır — buraya yalnız elle uzatılmış gönderim düşer). Kişisel / teknik bilgi yok; satır içi betik yok (CSP).
     */
    static void tooLargeHtml(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String title = Msg.t(request, "Gönderilen form çok büyük", "The form sent is too large");
        String msg = Msg.t(request,
                "Karar kaydedilmedi — formdaki not izin verilen uzunluğu aşıyor. Tarayıcıda geri dönüp notu kısaltın ve"
                        + " yeniden gönderin.",
                "The decision was not saved — the note in the form is longer than allowed. Go back in the browser, shorten"
                        + " the note and send it again.");
        htmlPage(request, response, 413, title, msg);
    }

    /** Onay bağlantısı formu için kısa hata sayfası (kişisel / teknik bilgi yok, satır içi betik yok — CSP). */
    private static void htmlPage(HttpServletRequest request, HttpServletResponse response, int status, String title,
                                 String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String lang = Msg.t(request, "tr", "en");
        String rid = CorrelationIdFilter.current();
        String html = "<!DOCTYPE html><html lang='" + lang + "'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + htmlEscape(title) + " — Site Monitor</title>"
                + "<style>body{margin:0;background:#eef1f4;font-family:'Segoe UI',-apple-system,Arial,sans-serif;color:#1F2937}"
                + ".card{max-width:480px;margin:56px auto;background:#fff;border-radius:14px;padding:26px 28px;"
                + "border:1px solid #E5E8EC;box-shadow:0 10px 40px rgba(15,27,45,.14)}"
                + "h1{font-size:20px;margin:0 0 10px}p{font-size:14px;line-height:1.7;color:#475569;margin:0}"
                + ".rid{margin-top:14px;font-size:11px;color:#94a3b8}</style></head><body><div class='card'>"
                + "<h1>" + htmlEscape(title) + "</h1><p>" + htmlEscape(msg) + "</p>"
                + (rid == null ? "" : "<p class='rid'>" + htmlEscape(rid) + "</p>")
                + "</div></body></html>";
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
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
        private final String field;

        BodyLimitExceededException(long limit) {
            this(limit, null);
        }

        BodyLimitExceededException(long limit, String field) {
            super("request body exceeds " + limit + " bytes");
            this.limit = limit;
            this.field = field;
        }

        public long limit() { return limit; }

        /** Sınır aşımı alan hatası olarak anlatılacaksa alan adı; yoksa null (413). */
        public String field() { return field; }
    }

    /** Akışı sayan sarmalayıcı (Content-Length yok ya da yalan söylüyor). */
    static final class Capped extends HttpServletRequestWrapper {
        private final long cap;
        private final String field;
        private ServletInputStream stream;
        private BufferedReader reader;

        Capped(HttpServletRequest request, long cap) {
            this(request, cap, null);
        }

        Capped(HttpServletRequest request, long cap, String field) {
            super(request);
            this.cap = cap;
            this.field = field;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CountingStream(super.getInputStream(), cap, field);
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
        private final String field;
        private long count;

        CountingStream(ServletInputStream in, long cap) {
            this(in, cap, null);
        }

        CountingStream(ServletInputStream in, long cap, String field) {
            this.in = in;
            this.cap = cap;
            this.field = field;
        }

        private void add(long n) throws BodyLimitExceededException {
            if (n <= 0) return;
            count += n;
            if (count > cap) throw new BodyLimitExceededException(cap, field);
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
