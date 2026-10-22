package com.sitemonitor.service.cache;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Yazdığını oku (2026-10-22, çok pod R2-A, MP-30): istemci yazma yanıtında gördüğü genel kuşağı ({@value
 * CacheBus#RESPONSE_HEADER}) sonraki GET / HEAD isteklerinde {@value CacheBus#REQUEST_HEADER} ile gönderir. Bu pod o kuşağı
 * henüz uygulamadıysa ({@code Şimdi kontrol et} pod A'ya, ardından gelen liste yüklemesi pod B'ye düştü) istek işlenmeden
 * önce TEK yoklama istenir ve en çok {@code site.monitor.cache-bus.min-gen-wait-ms} beklenir — B önbelleği boşaltıp taze
 * veriyi sunar. Önbelleği okuyan {@code AuthInterceptor} senkronları (erişim paylaşımı, 7/24 operatörü, sistem bakımı)
 * DispatcherServlet içinde koştuğu için bu süzgeçten SONRA gelir.
 *
 * <p>Yalnız {@code /api/**}, yalnız oturumu olan istek (oturumsuz istemci sahte büyük kuşakla yoklatamasın), yalnız geçerli
 * pozitif sayı. Başlık yok / veri yolu yok / eski pod → bugünkü davranış. Asla atmaz.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class CacheBusRequestFilter extends OncePerRequestFilter {

    /** Kabul edilen en uzun başlık değeri (18 hane — long'a sığar). */
    static final int MAX_HEADER_DIGITS = 18;

    private final ObjectProvider<CacheBus> busProvider;
    private volatile CacheBus bus;

    public CacheBusRequestFilter(ObjectProvider<CacheBus> busProvider) {
        this.busProvider = busProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) return true;
        String m = request.getMethod();
        return !"GET".equals(m) && !"HEAD".equals(m);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long min = parse(request.getHeader(CacheBus.REQUEST_HEADER));
        if (min > 0) {
            CacheBus b = bus();
            try {
                if (b != null && request.getSession(false) != null) b.awaitGeneration(min);
            } catch (RuntimeException ignored) {
                // tazelik en iyi çabadır — istek her koşulda sunulur
            }
        }
        chain.doFilter(request, response);
    }

    @Nullable
    private CacheBus bus() {
        CacheBus b = bus;
        if (b != null) return b;
        try {
            b = busProvider == null ? null : busProvider.getIfAvailable();
        } catch (RuntimeException e) {
            b = null;
        }
        if (b != null) bus = b;
        return b;
    }

    /** Pozitif tam sayı; boş / bozuk / çok uzun → 0 (yok sayılır). */
    static long parse(@Nullable String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_HEADER_DIGITS) return 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
