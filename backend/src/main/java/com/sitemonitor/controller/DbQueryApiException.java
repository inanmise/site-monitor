package com.sitemonitor.controller;

import com.sitemonitor.config.CorrelationIdFilter;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Veritabanı sorgu izlemesi uçlarının (11. tür, 2026-10-31) yapısal hata yanıtı — projenin hata zarfıyla aynı biçim
 * ({@code success:false, code, error, request_id}) + birden çok alan hatası ({@code fields} + ilk {@code field}) ve türe özgü
 * ek alanlar ({@code violations}, {@code plan}, {@code monitors}, {@code retry_after_seconds}).
 *
 * <p>Neden ayrı bir istisna: {@code GlobalExceptionHandler.FieldValidationException} tek alan taşır; izleme formu ise
 * sorgu, kurallar ve zamanlama hatalarını AYNI ANDA alanlarının altına yazar. Uçlar bunu fırlatır, kendi denetleyicisindeki
 * {@code @ExceptionHandler} {@link #toResponse()} ile yanıtlar (genel işleyicinin davranışı değişmez). İleti her zaman
 * {@code Msg.t} ile istek dilindedir ve teknik metin (istisna sınıfı, SQL, parola) taşımaz.
 */
public class DbQueryApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, String> fields;
    private final Map<String, Object> extra;

    public DbQueryApiException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    public DbQueryApiException(int status, String code, String message, Map<String, String> fields, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.extra = extra == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /** 400 {@code VALIDATION_FAILED} — {@code fields} sıralı (ilk alan {@code field} olur). */
    public static DbQueryApiException validation(String message, Map<String, String> fields) {
        return new DbQueryApiException(400, "VALIDATION_FAILED", message, fields, null);
    }

    /** Tek alanlı 400 — {@code code} null ise {@code VALIDATION_FAILED}. */
    public static DbQueryApiException field(String code, String field, String message) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(field, message);
        return new DbQueryApiException(400, code == null ? "VALIDATION_FAILED" : code, message, f, null);
    }

    /** 409 + kod (+ isteğe bağlı alan). */
    public static DbQueryApiException conflict(String code, String field, String message, Map<String, Object> extra) {
        Map<String, String> f = new LinkedHashMap<>();
        if (field != null) f.put(field, message);
        return new DbQueryApiException(409, code, message, f, extra);
    }

    /** 429 + kod + bekleme süresi (gövdede {@code retry_after_seconds}, başlıkta {@code Retry-After}). */
    public static DbQueryApiException tooMany(String code, String message, long retryAfterSeconds) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("retry_after_seconds", Math.max(1L, retryAfterSeconds));
        return new DbQueryApiException(429, code, message, null, extra);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, String> fields() { return fields; }
    public Map<String, Object> extra() { return extra; }

    /** Proje hata zarfı — {@code request_id} istek kimliği filtresinden. */
    public ResponseEntity<Map<String, Object>> toResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        if (code != null) body.put("code", code);
        body.put("error", getMessage());
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        if (!fields.isEmpty()) {
            body.put("field", fields.keySet().iterator().next());
            body.put("fields", fields);
        }
        body.putAll(extra);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        Object retry = extra.get("retry_after_seconds");
        if (status == 429 && retry != null) b.header("Retry-After", String.valueOf(retry));
        return b.body(body);
    }
}
