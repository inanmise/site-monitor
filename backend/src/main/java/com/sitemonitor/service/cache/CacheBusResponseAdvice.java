package com.sitemonitor.service.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Yanıt gövdesi yazılmadan hemen önce bu isteğin biriktirdiği önbellek boşaltmalarını TEK güncellemeyle yayınlar ve yeni
 * genel kuşağı {@value CacheBus#RESPONSE_HEADER} başlığına yazar (2026-10-22, çok pod R2-A, MP-30). İşlemli uçlarda
 * işlem bu noktada işlenmiştir (open-in-view kapalı), yani yayın her zaman işlenmiş veriyi duyurur. İstemci
 * ({@code api/client.js}) başlığı saklar ve sonraki GET'lerde {@value CacheBus#REQUEST_HEADER} olarak gönderir.
 *
 * <p>Gövdeyi DEĞİŞTİRMEZ. Bu istekte boşaltma yoksa iş yoktur (bir istek özniteliği okuması). Veri yolu yoksa (dilim
 * testleri) hiçbir şey yapmaz.
 */
@ControllerAdvice
public class CacheBusResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectProvider<CacheBus> busProvider;
    private volatile CacheBus bus;

    public CacheBusResponseAdvice(ObjectProvider<CacheBus> busProvider) {
        this.busProvider = busProvider;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        CacheBus b = bus();
        if (b == null) return body;
        try {
            long gen = b.flushRequest();
            if (gen > 0) response.getHeaders().set(CacheBus.RESPONSE_HEADER, Long.toString(gen));
        } catch (RuntimeException ignored) {
            // yayın en iyi çabadır — yanıt etkilenmez
        }
        return body;
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
}
