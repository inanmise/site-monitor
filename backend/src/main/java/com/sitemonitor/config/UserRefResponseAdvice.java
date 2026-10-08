package com.sitemonitor.config;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.service.userref.UserRefWire;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.ObjectMapper;

/**
 * Opak kullanıcı kimliğinin TEK çıkış noktası (2026-10-08, kullanıcı kararı: "bir kullanıcı başka bir kullanıcının
 * id'sini okuyamasın"; global admin hariç herkes — AUDIT ve kapsamlı müdür dahil).
 *
 * <p>JSON yanıtı yazılmadan hemen önce, görüntüleyici GLOBAL admin DEĞİLSE gövde yeni bir ağaca çevrilir ve kullanıcı
 * kimlikleri {@link UserRefWire} kuralıyla opak kimliğe döner. Global admin'in yanıtına dokunulmaz (bayt bayt bugünkü
 * hâli). Uç başına maske çağrısı yerine tek kapı: yeni bir uç aynı alan adlarını kullandıkça kendiliğinden kapsanır.
 *
 * <p>Çeviri başarısız olursa yanıt sayısal kimlikle YAZILMAZ — istisna yükselir (kapalı kal).
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UserRefResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectProvider<ObjectMapper> json;
    /** Dilim testlerinde ({@code @WebMvcTest}) servis yoktur → tavsiye no-op; üretimde her zaman vardır. */
    private final ObjectProvider<UserPublicIds> ids;

    public UserRefResponseAdvice(ObjectProvider<ObjectMapper> json, ObjectProvider<UserPublicIds> ids) {
        this.json = json;
        this.ids = ids;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJacksonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) return null;
        if (!(request instanceof ServletServerHttpRequest servlet)) return body;
        HttpSession session = servlet.getServletRequest().getSession(false);
        if (SessionScope.isGlobalAdmin(session)) return body;
        UserPublicIds svc = ids.getIfAvailable();
        ObjectMapper mapper = json.getIfAvailable();
        if (svc == null || mapper == null) return body;
        try {
            return UserRefWire.toOpaque(body, mapper, svc::publicIdOf);
        } catch (RuntimeException e) {
            log.warn("Opak kullanıcı kimliği çevirisi başarısız ({}): {}", servlet.getServletRequest().getRequestURI(), e.toString());
            throw e;
        }
    }
}
