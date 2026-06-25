package com.certmonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoSuchElementException → 404 + error mesajı")
    void notFound() {
        ResponseEntity<Map<String, Object>> r = handler.handleNotFound(new NoSuchElementException("yok"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertEquals("yok", r.getBody().get("error"));
    }

    @Test
    @DisplayName("NoSuchElementException mesajsız → 404 + jenerik 'Kayıt bulunamadı'")
    void notFound_genericWhenBlank() {
        ResponseEntity<Map<String, Object>> r = handler.handleNotFound(new NoSuchElementException());
        assertEquals(404, r.getStatusCode().value());
        assertEquals("Kayıt bulunamadı", r.getBody().get("error"));
    }

    @Test
    @DisplayName("DataIntegrityViolation → 409 + ham DB mesajı SIZMAZ")
    void dataIntegrity_noLeak() {
        ResponseEntity<Map<String, Object>> r = handler.handleDataIntegrityViolation(
                new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"pg_secret_xyz\""));
        assertEquals(409, r.getStatusCode().value());
        assertFalse(((String) r.getBody().get("error")).contains("pg_secret_xyz"));
        assertTrue(((String) r.getBody().get("error")).contains("çakışma"));
    }

    @Test
    @DisplayName("IllegalStateException → 409")
    void conflict() {
        ResponseEntity<Map<String, Object>> r = handler.handleConflict(new IllegalStateException("çakıştı"));
        assertEquals(409, r.getStatusCode().value());
    }

    @Test
    @DisplayName("IllegalArgumentException → 400")
    void badRequest() {
        ResponseEntity<Map<String, Object>> r = handler.handleBadRequest(new IllegalArgumentException("geçersiz"));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    @DisplayName("SecurityException → 403")
    void forbidden() {
        ResponseEntity<Map<String, Object>> r = handler.handleForbidden(new SecurityException("yetkisiz"));
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 + fields map ile")
    @SuppressWarnings("unchecked")
    void validation() {
        BindingResult br = new BeanPropertyBindingResult(new Object(), "obj");
        br.addError(new FieldError("obj", "domain", "boş olamaz"));
        br.addError(new FieldError("obj", "port",   "1-65535 arası olmalı"));
        MethodArgumentNotValidException ex = Mockito.mock(MethodArgumentNotValidException.class);
        Mockito.when(ex.getBindingResult()).thenReturn(br);

        ResponseEntity<Map<String, Object>> r = handler.handleValidation(ex);

        assertEquals(400, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        String err = (String) r.getBody().get("error");
        assertTrue(err.contains("domain"));
        assertTrue(err.contains("port"));
        Map<String, String> fields = (Map<String, String>) r.getBody().get("fields");
        assertEquals("boş olamaz", fields.get("domain"));
        assertEquals("1-65535 arası olmalı", fields.get("port"));
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400 jenerik mesaj")
    void malformedJson() {
        ResponseEntity<Map<String, Object>> r = handler.handleMalformedJson(
                new HttpMessageNotReadableException("bozuk json", (org.springframework.http.HttpInputMessage) null));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("Geçersiz istek formatı", r.getBody().get("error"));
    }

    @Test
    @DisplayName("MissingServletRequestParameterException → 400 + parametre adı")
    void missingParam() {
        ResponseEntity<Map<String, Object>> r = handler.handleMissingParam(
                new MissingServletRequestParameterException("domain", "String"));
        assertEquals(400, r.getStatusCode().value());
        assertTrue(((String) r.getBody().get("error")).contains("domain"));
    }

    @Test
    @DisplayName("ResponseStatusException → kendi status'unu döndürür")
    void responseStatus() {
        ResponseEntity<Map<String, Object>> r = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.GONE, "artık yok"));
        assertEquals(410, r.getStatusCode().value());
        assertEquals("artık yok", r.getBody().get("error"));
    }

    @Test
    @DisplayName("NoResourceFoundException (favicon vb.) → sessiz 404 (ERROR/stack yok)")
    void noResource() {
        ResponseEntity<Map<String, Object>> r = handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/favicon.ico", "/favicon.ico"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertEquals("Kaynak bulunamadı", r.getBody().get("error"));
    }

    @Test
    @DisplayName("DataAccessResourceFailureException (DB erişilemez/havuz boş) → 503 geçici")
    void dbUnavailable_returns503() {
        ResponseEntity<Map<String, Object>> r = handler.handleDbUnavailable(
                new org.springframework.dao.DataAccessResourceFailureException("Unable to acquire JDBC Connection"));
        assertEquals(503, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertTrue(((String) r.getBody().get("error")).contains("ulaşılamıyor"));
    }

    @Test
    @DisplayName("Beklenmeyen Exception → 500 + jenerik mesaj (stack trace sızmaz)")
    void generic() {
        ResponseEntity<Map<String, Object>> r = handler.handleGeneric(
                new RuntimeException("internal SecretField=abc123 leak"));
        assertEquals(500, r.getStatusCode().value());
        assertEquals("Sunucu hatası", r.getBody().get("error"));
        // Stack trace / internal mesaj kullanıcıya gitmemeli
        assertFalse(((String) r.getBody().get("error")).contains("SecretField"));
    }
}
