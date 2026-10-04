package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.service.SecretMask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP uçtan uca tanılamasının MASKELEME kuralları (2026-10-02) — tek yer. Sözleşme:
 * <ul>
 *   <li>İstek başlıkları: {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie}, {@code X-Api-Key},
 *       {@code X-Auth-Token} ve izlemenin ŞİFRELİ özel başlıklarının TÜMÜ → ad görünür, değer {@link #MASK},
 *       {@code masked: true}.</li>
 *   <li>Yanıt başlıkları: yalnız {@code Set-Cookie}'de çerez DEĞERİ gizlenir (ad + öznitelikler görünür);
 *       {@code WWW-Authenticate} görünür.</li>
 *   <li>Gövde önizlemesi: {@link RequestLoggingFilter} JSON/form desenleri + bilinen sır değerleri.</li>
 *   <li>Derin süzgeç ({@link #scrubDeep}): sonuçtaki HER metin (transcript, hata zinciri, vekil blokları, önizleme)
 *       bilinen sır DEĞERLERİNDEN ({@link SecretMask#maskValues} — kodlanmış varyantlar dâhil) geçirilir. Basic auth
 *       parolası, vekil parolası ve özel başlık değerleri hiçbir alanda düz görünmez; başlık maskesi kaçsa bile.</li>
 * </ul>
 */
public final class HttpDiagMasker {   // public: keyword uçtan uca tanılaması da (2026-10-04) aynı maskeyi kullanır

    /** Gizlenen başlık değerinin yerine yazılan işaret (sözleşme). */
    public static final String MASK = "••••";

    /** Değeri HER ZAMAN gizlenen istek başlıkları (küçük harf). */
    static final Set<String> SENSITIVE_REQUEST = Set.of(
            "authorization", "proxy-authorization", "cookie", "x-api-key", "x-auth-token");

    private final Set<String> customLower;
    private final List<String> secrets;

    /**
     * @param customHeaderNames izlemenin şifreli özel başlık ADLARI (hepsi maskelenir)
     * @param secretValues      düz sır değerleri (parola, jeton, özel başlık değerleri) — metinden süzülür
     */
    public HttpDiagMasker(Collection<String> customHeaderNames, Collection<String> secretValues) {
        Set<String> c = new LinkedHashSet<>();
        if (customHeaderNames != null) {
            for (String n : customHeaderNames) if (n != null && !n.isBlank()) c.add(n.trim().toLowerCase(Locale.ROOT));
        }
        this.customLower = Set.copyOf(c);
        List<String> s = new ArrayList<>();
        if (secretValues != null) {
            for (String v : secretValues) if (v != null && !v.isBlank()) s.add(v);
        }
        this.secrets = List.copyOf(s);
    }

    /** Bu istek başlığının değeri gizlenir mi? */
    boolean isMaskedRequestHeader(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_REQUEST.contains(n) || customLower.contains(n);
    }

    /** İstek başlığı satırı {@code {name, value, masked}}. */
    Map<String, Object> requestHeader(String name, String value) {
        boolean masked = isMaskedRequestHeader(name);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", name);
        h.put("value", masked ? MASK : value);
        h.put("masked", masked);
        return h;
    }

    /** Transcript / CONNECT satırı biçiminde ("Ad: değer"), maskeli. */
    String requestHeaderLine(String name, String value) {
        return name + ": " + (isMaskedRequestHeader(name) ? MASK : value);
    }

    /** Yanıt başlığı satırı {@code {name, value, masked}} — yalnız Set-Cookie değeri gizlenir. */
    Map<String, Object> responseHeader(String name, String value) {
        boolean cookie = name != null && "set-cookie".equalsIgnoreCase(name.trim());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", name);
        h.put("value", cookie ? maskSetCookie(value) : value);
        h.put("masked", cookie);
        return h;
    }

    String responseHeaderLine(String name, String value) {
        boolean cookie = name != null && "set-cookie".equalsIgnoreCase(name.trim());
        return name + ": " + (cookie ? maskSetCookie(value) : value);
    }

    /** {@code AD=DEĞER; Path=/; HttpOnly} → {@code AD=••••; Path=/; HttpOnly}. Eşittirsiz ilk parça tümüyle gizlenir. */
    static String maskSetCookie(String value) {
        if (value == null) return null;
        int semi = value.indexOf(';');
        String first = semi >= 0 ? value.substring(0, semi) : value;
        String rest = semi >= 0 ? value.substring(semi) : "";
        int eq = first.indexOf('=');
        String maskedFirst = eq >= 0 ? first.substring(0, eq + 1) + MASK : MASK;
        return maskedFirst + rest;
    }

    /** Serbest metinden bilinen sır değerlerini süzer (kodlanmış varyantlar dâhil). */
    public String scrub(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        return SecretMask.maskValues(text, secrets);
    }

    /** Gövde önizlemesi: JSON/form gizli alanları + bilinen sırlar. */
    public String maskBody(String text) {
        if (text == null || text.isEmpty()) return text;
        return scrub(RequestLoggingFilter.redactSensitiveFields(text));
    }

    /** Süzgecin bildiği sır değerleri (keyword tanılamasında gövde çözümlemesinin alıntılarına da uygulanır). */
    public List<String> secretValues() {
        return secrets;
    }

    /** Sonuç ağacındaki her metni (harita değerleri + liste öğeleri) süzer; yapı yerinde güncellenir. */
    @SuppressWarnings("unchecked")
    public Object scrubDeep(Object node) {
        if (secrets.isEmpty() || node == null) return node;
        if (node instanceof String s) return scrub(s);
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) map).entrySet()) {
                Object v = e.getValue();
                Object nv = scrubDeep(v);
                if (nv != v) e.setValue(nv);
            }
            return node;
        }
        if (node instanceof List<?> list) {
            List<Object> l = (List<Object>) list;
            for (int i = 0; i < l.size(); i++) {
                Object v = l.get(i);
                Object nv = scrubDeep(v);
                if (nv != v) l.set(i, nv);
            }
            return node;
        }
        return node;
    }
}
