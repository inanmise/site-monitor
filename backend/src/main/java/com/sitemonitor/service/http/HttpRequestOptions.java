package com.sitemonitor.service.http;

import com.sitemonitor.model.HttpMonitor;

import java.util.function.UnaryOperator;

/**
 * HTTP izlemesinin OPSİYONEL istek/doğrulama eklentileri (2026-10-01, onaylı öneri 9): özel başlıklar, HTTP Basic
 * auth, POST gövdesi + içerik türü, JSON yanıt doğrulaması. Sırlar burada ÇÖZÜLMÜŞ (düz) hâldedir — yalnız istek
 * kurulurken yaşar; loglanmaz, sonuca yazılmaz.
 *
 * <p><b>Geriye uyum sözleşmesi.</b> Alanlarının tümü boş olan izleme {@link #NONE} alır ve çağıranlar o durumda
 * denetleyicinin ESKİ (7 argümanlı) giriş noktasını çağırır: istek bugünkünün birebir aynısıdır — ek başlık yok,
 * POST gövdesiz, gövde okunmaz (yalnız tüketilir), karar yalnız durum koduna göre. {@code HttpRequestBuildTest}
 * bunu kilitler.
 *
 * @param headers       düz "Ad: değer" satırları (yalnız global admin yazabilir); null = yok
 * @param basicAuthUser Basic auth kullanıcı adı; boşsa Authorization eklenmez
 * @param basicAuthPass çözülmüş parola (null olabilir)
 * @param body          POST gövdesi — YALNIZ yöntem POST ve gövde doluyken gönderilir
 * @param contentType   gövdenin içerik türü; boşsa {@link HttpRequestRules#DEFAULT_CONTENT_TYPE}
 * @param jsonPath      JSON doğrulama yolu; boşsa doğrulama YOK (gövde okunmaz)
 * @param jsonExpected  beklenen metin; boşsa "yol var ve null değil"
 */
public record HttpRequestOptions(String headers, String basicAuthUser, String basicAuthPass,
                                 String body, String contentType, String jsonPath, String jsonExpected) {

    /** Hiçbir eklenti yok — bugünkü davranış. */
    public static final HttpRequestOptions NONE = new HttpRequestOptions(null, null, null, null, null, null, null);

    private static boolean has(String s) { return s != null && !s.isBlank(); }

    /** Bu seçenekler isteği ya da kararı herhangi bir şekilde değiştiriyor mu? */
    public boolean isEmpty() {
        return !has(headers) && !has(basicAuthUser) && !has(body) && !has(jsonPath);
    }

    /** JSON doğrulaması tanımlı mı (yalnız o zaman yanıt gövdesi tamponlanır). */
    public boolean hasJsonAssertion() { return has(jsonPath); }

    /** Gövde bu yöntemde gönderilir mi: YALNIZ POST + dolu gövde (diğer durumlarda bugünkü gibi gövdesiz). */
    public boolean sendsBody(String method) { return "POST".equals(method) && has(body); }

    /** Gövdenin içerik türü (boşsa application/json). */
    public String effectiveContentType() {
        return has(contentType) ? contentType.trim() : HttpRequestRules.DEFAULT_CONTENT_TYPE;
    }

    /** JSON doğrulaması OLMAYAN eşi — yavaşlık yeniden ölçümü gövdeyi boşuna tamponlamasın. */
    public HttpRequestOptions withoutJsonAssertion() {
        if (!hasJsonAssertion()) return this;
        HttpRequestOptions o = new HttpRequestOptions(headers, basicAuthUser, basicAuthPass, body, contentType, null, null);
        return o.isEmpty() ? NONE : o;
    }

    /**
     * Kayıtlı izlemeden seçenekler. Şifreli alanlar {@code decrypt} ile çözülür; çözülemeyen sır (anahtar rotasyonu)
     * o kimlik olmadan devam eder — tüm HTTP izlemeleri birden kör olmasın (Sayfa Hızı ile aynı kural).
     *
     * @return eklenti yoksa {@link #NONE} (aynı örnek — çağıran {@code == NONE} ile eski yola gider)
     */
    public static HttpRequestOptions forMonitor(HttpMonitor m, UnaryOperator<String> decrypt) {
        if (m == null) return NONE;
        String headers = has(m.getCustomHeadersEnc()) ? safeDecrypt(decrypt, m.getCustomHeadersEnc()) : null;
        String pass = has(m.getBasicAuthPassEnc()) ? safeDecrypt(decrypt, m.getBasicAuthPassEnc()) : null;
        HttpRequestOptions o = new HttpRequestOptions(headers, m.getBasicAuthUser(), pass,
                m.getRequestBody(), m.getRequestContentType(), m.getJsonPath(), m.getJsonExpected());
        return o.isEmpty() ? NONE : o;
    }

    private static String safeDecrypt(UnaryOperator<String> decrypt, String enc) {
        if (decrypt == null) return null;
        try {
            return decrypt.apply(enc);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sırlar ASLA metne dökülmez (record'un varsayılan toString'i parolayı basardı). */
    @Override
    public String toString() {
        return "HttpRequestOptions[headers=" + (has(headers) ? "set" : "none")
                + ", basicAuthUser=" + (has(basicAuthUser) ? "set" : "none")
                + ", basicAuthPass=" + (basicAuthPass != null && !basicAuthPass.isEmpty() ? "set" : "none")
                + ", body=" + (has(body) ? body.length() + " chars" : "none")
                + ", contentType=" + contentType + ", jsonPath=" + jsonPath + "]";
    }
}
