package com.sitemonitor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * İstek yolu güvenlik duvarı (BK1, bug regresyon 2026-09-27) — Spring Security'nin
 * {@code StrictHttpFirewall}'ının bu projeye yeten alt kümesi. Projede Spring Security yok;
 * {@code /api/**}'in tek kimlik kapısı {@link AuthInterceptor}, CSRF ikinci katmanı {@link OriginCheckFilter}.
 *
 * <p><b>Neden.</b> İki kapı da kararı HAM {@code getRequestURI()} üzerinden veriyordu. {@code /api;x/certificates}
 * {@code "/api/"} ile BAŞLAMAZ → kapılar isteği "API değil" sayıp geçiriyordu; oysa DispatcherServlet matris
 * içeriğini ({@code ;x}) eşleşmede kırpıp isteği yine {@code /api/certificates}'e yönlendiriyordu → oturumsuz
 * 200 + tam veri (yerelde canlı doğrulandı). Aynı sınıf: {@code /%61pi/...} (yüzde-kodlu harf),
 * {@code //api/...}, kodlu nokta/eğik çizgi.
 *
 * <p><b>Kural</b> (yalnız YOL; sorgu dizesi serbest): ham istek yolunda şunlardan biri varsa istek
 * işleyiciye hiç ulaşmadan <b>400</b> ile reddedilir:
 * <ul>
 *   <li>{@code ;} (matris/yol parametresi) ya da kodlu hâli {@code %3B};</li>
 *   <li>kodlu eğik çizgi {@code %2F}, ters eğik çizgi {@code \} / {@code %5C}, kodlu nokta {@code %2E};</li>
 *   <li>{@code //}, {@code /./}, {@code /../} (ve sondaki {@code /.} / {@code /..});</li>
 *   <li>kontrol karakteri (ham ya da {@code %00}–{@code %1F}, {@code %7F}) ve bozuk yüzde kodlaması.</li>
 * </ul>
 * Uygulamanın kendi ön ucu bu biçimlerin hiçbirini üretmez (alan adı yol değişkenleri
 * {@code encodeURIComponent} ile gelir; nokta kodlanmaz). Tomcat {@code %2F}'yi zaten reddediyor.
 *
 * <p>İkinci savunma katmanı: kapılar artık {@link #lookupPath} ile NORMALİZE yola bakar — bu filtre bir gün
 * gevşetilse bile ham yol ile yönlendirme yolu arasındaki fark bir atlatmaya dönüşmez.
 *
 * <p>Sıra: en yüksek öncelik — CORS/Origin filtreleri ve DispatcherServlet'ten ÖNCE. Kapı:
 * {@code RequestPathFirewallFilterTest} + {@code RequestPathBypassMvcTest}.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestPathFirewallFilter extends OncePerRequestFilter {

    /** Reddin JSON gövdesi — ön uç {@code success:false} bekler. */
    static final String BODY = "{\"success\":false,\"error\":\"Geçersiz istek yolu\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String reason = rejectReason(req.getRequestURI());
        if (reason == null) {
            chain.doFilter(req, res);
            return;
        }
        log.warn("İstek yolu reddedildi ({}): {} {}", reason, req.getMethod(), clip(req.getRequestURI()));
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(BODY);
    }

    /**
     * Ham istek yolu (sorgusuz {@code getRequestURI()}) güvensizse nedeni, değilse {@code null}.
     * Paket görünür: testler doğrudan sınar.
     */
    static String rejectReason(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c < 0x20 || c == 0x7F) return "kontrol karakteri";
            if (c == ';') return "noktalı virgül (matris parametresi)";
            if (c == '\\') return "ters eğik çizgi";
            if (c == '%') {
                if (i + 2 >= rawPath.length() || hex(rawPath.charAt(i + 1)) < 0 || hex(rawPath.charAt(i + 2)) < 0) {
                    return "bozuk yüzde kodlaması";
                }
                int b = hex(rawPath.charAt(i + 1)) * 16 + hex(rawPath.charAt(i + 2));
                if (b < 0x20 || b == 0x7F) return "kodlu kontrol karakteri";
                if (b == ';' || b == '/' || b == '\\' || b == '.') return "kodlu ayraç";
            }
        }
        if (rawPath.contains("//")) return "çift eğik çizgi";
        if (rawPath.contains("/./") || rawPath.endsWith("/.")) return "nokta bölütü";
        if (rawPath.contains("/../") || rawPath.endsWith("/..")) return "üst dizin bölütü";
        return null;
    }

    /**
     * Uygulama içi NORMALİZE yol: yüzde kodu çözülmüş, matris içeriği ({@code ;…}) kırpılmış, {@code //}
     * tekilleşmiş, bağlam yolu atılmış — DispatcherServlet'in işleyici eşlerken gördüğü yolun karşılığı.
     * Kimlik/CSRF kapıları kararı bununla verir. Çözülemezse {@code null}: çağıran isteği KORUMALI saymalı
     * (fail-closed).
     */
    public static String lookupPath(HttpServletRequest req) {
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(req);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        char l = Character.toLowerCase(c);
        if (l >= 'a' && l <= 'f') return 10 + (l - 'a');
        return -1;
    }

    private static String clip(String v) {
        if (v == null) return "-";
        StringBuilder sb = new StringBuilder(Math.min(v.length(), 121));
        for (int i = 0; i < v.length() && sb.length() < 120; i++) {
            char c = v.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '?' : c);   // log enjeksiyonu: kontrol karakteri satır kırmasın
        }
        if (v.length() > 120) sb.append('…');
        return sb.toString();
    }
}
