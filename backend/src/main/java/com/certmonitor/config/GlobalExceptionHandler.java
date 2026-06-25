package com.certmonitor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        // Mesaj null/boş ise (kütüphane kaynaklı) jenerik metin — teknik içerik sızmasın
        String msg = (e.getMessage() != null && !e.getMessage().isBlank())
                ? e.getMessage() : "Kayıt bulunamadı";
        return ResponseEntity.status(404)
                .body(Map.of("success", false, "error", msg));
    }

    /** Fired when e.g. trying to re-notify an already-resolved alert. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(409)
                .body(Map.of("success", false, "error", e.getMessage()));
    }

    /** Validation errors from controllers (e.g. blank domain). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400)
                .body(Map.of("success", false, "error", e.getMessage()));
    }

    /** Access denied — non-admin trying to reach admin-only endpoint. */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SecurityException e) {
        return ResponseEntity.status(403)
                .body(Map.of("success", false, "error", e.getMessage()));
    }

    /** DB unique-constraint çakışması (örn. domain rename'de aynı isim). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        String msg = "Veri çakışması: aynı anahtara sahip kayıt zaten var.";
        String em = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage() : e.getMessage();
        if (em != null && em.contains("uk3jgfhgl0acmsnh3q4t1730tin")) {
            msg = "Bu domain envanterde zaten var.";
        }
        return ResponseEntity.status(409)
                .body(Map.of("success", false, "error", msg));
    }

    /** @Valid başarısız oldu — body bind sırasında alan kuralı kırıldı. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "geçersiz");
        }
        return ResponseEntity.status(400).body(Map.of(
                "success", false,
                "error", "Geçersiz alan(lar): " + String.join(", ", fields.keySet()),
                "fields", fields));
    }

    /** Bozuk JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(Map.of("success", false, "error", "Geçersiz istek formatı"));
    }

    /** Query/form parametresi eksik. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(400).body(Map.of(
                "success", false,
                "error", "Eksik parametre: " + e.getParameterName()));
    }

    /** Eksik statik kaynak (favicon.ico, eşleşmeyen statik yol) — sessiz 404.
     *  Generic handler'a düşüp ERROR + tam stack basmasın (gereksiz log gürültüsü). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.debug("Statik kaynak bulunamadı: {}", e.getResourcePath());
        return ResponseEntity.status(404)
                .body(Map.of("success", false, "error", "Kaynak bulunamadı"));
    }

    /** Controller'lardan elle fırlatılmış status hatası — passthrough. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        String msg = e.getReason() != null ? e.getReason() : e.getMessage();
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("success", false, "error", msg));
    }

    /** Veritabanına erişilemiyor (bağlantı kurulamadı / havuz tükendi) ya da geçici DB hatası → 503 (geçici,
     *  tekrar denenebilir). DataAccessResourceFailureException = "Unable to acquire JDBC Connection" (postgres
     *  blip'i / havuz boş); CannotGetJdbcConnectionException onun alt-sınıfı → kapsanır. Bad SQL grammar gibi
     *  KALICI DAO hataları bu handler'a düşmez → jenerik 500'de kalır. WARN (blipte ERROR yığını basmasın). */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    public ResponseEntity<Map<String, Object>> handleDbUnavailable(Exception e) {
        log.warn("Veritabanına erişilemiyor (geçici): {}", e.toString());
        return ResponseEntity.status(503)
                .body(Map.of("success", false,
                        "error", "Veritabanına şu anda ulaşılamıyor, lütfen birazdan tekrar deneyin"));
    }

    /**
     * Son çare: handler eşleşmeyen her şey burada yakalanır.
     * Stack trace UI'ye sızmasın — kullanıcıya jenerik mesaj, log'a tam hata.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Beklenmeyen sunucu hatası: {}", e.toString(), e);
        return ResponseEntity.status(500)
                .body(Map.of("success", false, "error", "Sunucu hatası"));
    }
}
