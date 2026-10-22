package com.sitemonitor.service.cache;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Yazdığını oku uçtan uca (2026-10-22, çok pod R2-A, MP-30): yazma yanıtı {@value CacheBus#RESPONSE_HEADER} taşır
 * ({@link CacheBusResponseAdvice}); sonraki GET {@value CacheBus#REQUEST_HEADER} ile ÖTEKİ pod'a düşer → istek işlenmeden
 * önce o pod boşaltır ({@link CacheBusRequestFilter}). Başlıksız / oturumsuz / yazma isteği → bekleme yok.
 */
class CacheBusWebTest {

    MultiPodTestSupport mp;
    DataSourceTransactionManager txm;
    CacheBus podA, podB;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(CacheBus.DDL);
        txm = new DataSourceTransactionManager(mp.dataSource());
        podA = new CacheBus(mp.jdbc(), txm);
        podB = new CacheBus(mp.jdbc(), txm);
        podA.pollNow();
        podB.pollNow();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        podA.stop(false);
        podB.stop(false);
        mp.close();
    }

    static ObjectProvider<CacheBus> provider(CacheBus b) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        if (b != null) f.addBean("cacheBus", b);
        return f.getBeanProvider(CacheBus.class);
    }

    @Test
    @DisplayName("Yazma yanıtı X-Cache-Gen taşır → öteki pod'a düşen GET X-Cache-Min-Gen ile önce boşaltır, sonra sunar")
    void writeOnA_thenReadOnB_isFresh() throws Exception {
        AtomicInteger evictedOnB = new AtomicInteger();
        podB.register("cert-latest", evictedOnB::incrementAndGet);
        // Yayın ve zorunlu yoklama veri yolu iş parçacığında (istek iş parçacığı veritabanına dokunmaz); dönemsel yoklama uzak
        for (CacheBus p : new CacheBus[] { podA, podB }) {
            p.pollMs = 60_000;
            p.start();
        }

        // Pod A: "Şimdi kontrol et" (işlemsiz) → istek grubuna; gövde yazılırken yayın + başlık
        MockHttpServletRequest write = new MockHttpServletRequest("GET", "/api/certificates/check/a.example.com");
        MockHttpServletResponse writeRes = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(write, writeRes));
        podA.bump("cert-latest", "cert-stats");
        ServletServerHttpResponse out = new ServletServerHttpResponse(writeRes);
        new CacheBusResponseAdvice(provider(podA)).beforeBodyWrite(java.util.Map.of("success", true), null, null, null,
                new ServletServerHttpRequest(write), out);
        out.flush();
        RequestContextHolder.resetRequestAttributes();
        String gen = writeRes.getHeader(CacheBus.RESPONSE_HEADER);
        assertThat(gen).isEqualTo("1");

        // Pod B: liste yeniden yüklemesi ~100 ms sonra (B'nin 2 sn yoklaması henüz koşmadı)
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/certificates");
        read.setSession(new MockHttpSession());
        read.addHeader(CacheBus.REQUEST_HEADER, gen);
        AtomicInteger servedAfterEvict = new AtomicInteger(-1);
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                servedAfterEvict.set(evictedOnB.get());
            }
        });
        new CacheBusRequestFilter(provider(podB)).doFilter(read, new MockHttpServletResponse(), chain);
        assertThat(servedAfterEvict.get()).as("istek işlenmeden ÖNCE B boşalttı").isEqualTo(1);
    }

    @Test
    @DisplayName("Süzgeç: başlık yok / oturum yok / yazma isteği / /api dışı / bozuk başlık → bekleme yok; veri yolu yoksa geçer")
    void filter_onlyForAuthenticatedApiReadsWithValidHeader() throws Exception {
        CacheBus bus = mock(CacheBus.class);
        CacheBusRequestFilter f = new CacheBusRequestFilter(provider(bus));

        f.doFilter(req("GET", "/api/x", "5", false), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("GET", "/api/x", null, true), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("POST", "/api/x", "5", true), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("GET", "/index.html", "5", true), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("GET", "/api/x", "-1", true), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("GET", "/api/x", "9".repeat(19), true), new MockHttpServletResponse(), new MockFilterChain());
        f.doFilter(req("GET", "/api/x", "1e3", true), new MockHttpServletResponse(), new MockFilterChain());
        verify(bus, never()).awaitGeneration(anyLong());

        f.doFilter(req("HEAD", "/api/x", " 7 ", true), new MockHttpServletResponse(), new MockFilterChain());
        verify(bus).awaitGeneration(7L);

        new CacheBusRequestFilter(provider(null)).doFilter(req("GET", "/api/x", "5", true), new MockHttpServletResponse(),
                new MockFilterChain());
        assertThat(CacheBusRequestFilter.parse("0")).isZero();
        assertThat(CacheBusRequestFilter.parse("42")).isEqualTo(42);
    }

    @Test
    @DisplayName("Yanıt danışmanı: bu istekte boşaltma yoksa başlık yok ve veritabanına dokunulmaz")
    void advice_noBumpNoHeader() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r, res));
        ServletServerHttpResponse out = new ServletServerHttpResponse(res);
        Object body = java.util.List.of(1, 2);
        assertThat(new CacheBusResponseAdvice(provider(podA)).beforeBodyWrite(body, null, null, null,
                new ServletServerHttpRequest(r), out)).isSameAs(body);
        assertThat(out.getHeaders().containsHeader(CacheBus.RESPONSE_HEADER)).isFalse();
        assertThat(new CacheBusResponseAdvice(provider(null)).beforeBodyWrite(body, null, null, null,
                new ServletServerHttpRequest(r), out)).isSameAs(body);
    }

    static MockHttpServletRequest req(String method, String uri, String header, boolean session) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        if (header != null) r.addHeader(CacheBus.REQUEST_HEADER, header);
        if (session) r.setSession(new MockHttpSession());
        return r;
    }
}
