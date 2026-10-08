package com.sitemonitor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = newHandler();

    @SuppressWarnings("unchecked")
    private static GlobalExceptionHandler newHandler() {
        // AuditService opsiyonel (ObjectProvider); mock getIfAvailable() → null → güvenlik olayı atlanır.
        return new GlobalExceptionHandler(Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** İstek bağlamı: istek kimliği (CorrelationIdFilter) + isteğe bağlı X-Lang. */
    private static void request(String requestId, String lang) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/x");
        if (requestId != null) req.setAttribute(CorrelationIdFilter.ATTR, requestId);
        if (lang != null) req.addHeader("X-Lang", lang);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static String error(ResponseEntity<Map<String, Object>> r) {
        return (String) r.getBody().get("error");
    }

    @Test
    @DisplayName("NoSuchElementException → 404 + error mesajı")
    void notFound() {
        ResponseEntity<Map<String, Object>> r = handler.handleNotFound(new NoSuchElementException("yok"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertEquals("yok", r.getBody().get("error"));
        assertEquals("NOT_FOUND", r.getBody().get("code"));
    }

    @Test
    @DisplayName("NoSuchElementException mesajsız / 'No value present' → 404 + açıklayıcı metin (teknik ileti sızmaz)")
    void notFound_genericWhenBlank() {
        for (NoSuchElementException e : List.of(new NoSuchElementException(), new NoSuchElementException("No value present"))) {
            ResponseEntity<Map<String, Object>> r = handler.handleNotFound(e);
            assertEquals(404, r.getStatusCode().value());
            assertTrue(error(r).startsWith("Aradığınız kayıt bulunamadı"), error(r));
            assertTrue(error(r).contains("yenileyip"));
            assertFalse(error(r).contains("No value present"));
        }
    }

    @Test
    @DisplayName("'User not found: 5' → istek dilinde açıklayıcı metin (#5 korunur)")
    void notFound_knownMessageLocalized() {
        assertEquals("Kullanıcı bulunamadı (#5); silinmiş ya da taşınmış olabilir. Listeyi yenileyip tekrar deneyin.",
                error(handler.handleNotFound(new NoSuchElementException("User not found: 5"))));
        request(null, "en");
        assertEquals("User not found (#5); it may have been deleted or moved. Refresh the list and try again.",
                error(handler.handleNotFound(new NoSuchElementException("User not found: 5"))));
    }

    @Test
    @DisplayName("DataIntegrityViolation → 409 + ham DB mesajı SIZMAZ")
    void dataIntegrity_noLeak() {
        ResponseEntity<Map<String, Object>> r = handler.handleDataIntegrityViolation(
                new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"pg_secret_xyz\""));
        assertEquals(409, r.getStatusCode().value());
        assertFalse(error(r).contains("pg_secret_xyz"));
        assertTrue(error(r).contains("çakışma"));
        assertEquals("DUPLICATE_RECORD", r.getBody().get("code"));
    }

    @Test
    @DisplayName("DataIntegrityViolation türleri: FK → 409 REFERENCED_RECORD, not-null / uzunluk / check → 400, özel alan adı kısıtı")
    void dataIntegrity_kinds() {
        record Case(String db, int status, String code, String hint) { }
        for (Case c : List.of(
                new Case("update or delete on table \"teams\" violates foreign key constraint \"fk_x\"", 409, "REFERENCED_RECORD", "bağlı kayıtları"),
                new Case("null value in column \"name\" of relation \"teams\" violates not-null constraint", 400, "MISSING_REQUIRED_FIELD", "zorunlu alanları"),
                new Case("value too long for type character varying(40)", 400, "VALUE_TOO_LONG", "kısaltıp"),
                new Case("new row violates check constraint \"ck_tier\"", 400, "INVALID_VALUE", "aralığın dışında"),
                new Case("duplicate key value violates unique constraint \"uk3jgfhgl0acmsnh3q4t1730tin\"", 409, "DUPLICATE_RECORD", "alan adı envanterde zaten var"))) {
            ResponseEntity<Map<String, Object>> r = handler.handleDataIntegrityViolation(
                    new org.springframework.dao.DataIntegrityViolationException(c.db()));
            assertEquals(c.status(), r.getStatusCode().value(), c.db());
            assertEquals(c.code(), r.getBody().get("code"), c.db());
            assertTrue(error(r).contains(c.hint()), error(r));
            assertFalse(error(r).contains("constraint"), "DB iletisi sızmamalı: " + error(r));
        }
    }

    @Test
    @DisplayName("DomainExistsException (2026-09-28) → 409 + code=DOMAIN_EXISTS + existing (null değerli harita da taşınır)")
    void domainExists_structuredConflict() {
        Map<String, Object> existing = new java.util.LinkedHashMap<>();
        existing.put("domain", "shop.example.com");
        existing.put("team_name", "Takım B");
        existing.put("ug_team_id", null);
        ResponseEntity<Map<String, Object>> r = handler.handleConflict(
                new GlobalExceptionHandler.DomainExistsException("zaten kayıtlı", existing));
        assertEquals(409, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertEquals("zaten kayıtlı", r.getBody().get("error"));
        assertEquals("DOMAIN_EXISTS", r.getBody().get("code"));
        assertSame(existing, r.getBody().get("existing"));
        // Düz IllegalStateException: kararlı CONFLICT kodu, yapısal `existing` YOK
        Map<String, Object> plain = handler.handleConflict(new IllegalStateException("x")).getBody();
        assertEquals("CONFLICT", plain.get("code"));
        assertFalse(plain.containsKey("existing"));
    }

    @Test
    @DisplayName("CodedConflictException → 409 + kendi kodu + ek alanlar (ilk alanları ezmez)")
    void codedConflict() {
        ResponseEntity<Map<String, Object>> r = handler.handleConflict(new GlobalExceptionHandler.CodedConflictException(
                "SAME_CERTIFICATE", "aynı sertifika", Map.of("version_id", 9, "code", "EZME")));
        assertEquals(409, r.getStatusCode().value());
        assertEquals("SAME_CERTIFICATE", r.getBody().get("code"));
        assertEquals(9, r.getBody().get("version_id"));
    }

    @Test
    @DisplayName("IllegalStateException → 409; teknik ileti (kütüphane) → 500 INTERNAL_ERROR, ileti sızmaz")
    void conflict() {
        ResponseEntity<Map<String, Object>> r = handler.handleConflict(new IllegalStateException("çakıştı"));
        assertEquals(409, r.getStatusCode().value());
        assertEquals("çakıştı", error(r));
        ResponseEntity<Map<String, Object>> t = handler.handleConflict(
                new IllegalStateException("Duplicate key 7 (attempted merging values java.lang.Object@1 and java.lang.Object@2)"));
        assertEquals(500, t.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", t.getBody().get("code"));
        assertFalse(error(t).contains("java.lang"));
    }

    @Test
    @DisplayName("IllegalArgumentException → 400, servis iletisi KORUNUR (doğrulama kanalı)")
    void badRequest() {
        ResponseEntity<Map<String, Object>> r = handler.handleBadRequest(new IllegalArgumentException("geçersiz"));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("geçersiz", error(r));
        assertEquals("INVALID_REQUEST", r.getBody().get("code"));
    }

    @Test
    @DisplayName("IllegalArgumentException: NumberFormat / enum / teknik ileti → açıklayıcı metin; kod biçimli ileti korunur")
    void badRequest_technicalReplaced() {
        ResponseEntity<Map<String, Object>> n = handler.handleBadRequest(new NumberFormatException("For input string: \"abc\""));
        assertEquals("INVALID_NUMBER", n.getBody().get("code"));
        assertFalse(error(n).contains("For input string"));
        ResponseEntity<Map<String, Object>> e = handler.handleBadRequest(
                new IllegalArgumentException("No enum constant com.sitemonitor.model.Tier.FIVE"));
        assertEquals("INVALID_OPTION", e.getBody().get("code"));
        assertFalse(error(e).contains("com.sitemonitor"));
        ResponseEntity<Map<String, Object>> t = handler.handleBadRequest(
                new IllegalArgumentException("Name for argument of type [java.lang.Long] not specified"));
        assertEquals(400, t.getStatusCode().value());
        assertFalse(error(t).contains("java.lang"));
        ResponseEntity<Map<String, Object>> c = handler.handleBadRequest(new IllegalArgumentException("DUPLICATE_WEEK"));
        assertEquals("DUPLICATE_WEEK", error(c));
        assertEquals("DUPLICATE_WEEK", c.getBody().get("code"));
    }

    @Test
    @DisplayName("2026-10-08: FieldValidationException → 400 VALIDATION_FAILED + fields{alan:ileti} + field; ileti aynen")
    @SuppressWarnings("unchecked")
    void fieldValidation() {
        request("rid-fv", null);
        ResponseEntity<Map<String, Object>> r = handler.handleBadRequest(
                new GlobalExceptionHandler.FieldValidationException("host", "Host geçersiz: '-f'."));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", r.getBody().get("code"));
        assertEquals("Host geçersiz: '-f'.", error(r));
        assertEquals("host", r.getBody().get("field"));
        assertEquals("Host geçersiz: '-f'.", ((Map<String, String>) r.getBody().get("fields")).get("host"));
        assertEquals("rid-fv", r.getBody().get("request_id"));
    }

    @Test
    @DisplayName("2026-10-08: ClassCastException (yanlış JSON türü) → 500 değil 400 VALIDATION_FAILED; sınıf adı sızmaz, TR/EN")
    void classCast() {
        ClassCastException cce = new ClassCastException(
                "class java.lang.String cannot be cast to class java.lang.Number (java.lang.String and java.lang.Number are in module java.base)");
        ResponseEntity<Map<String, Object>> r = handler.handleClassCast(cce);
        assertEquals(400, r.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", r.getBody().get("code"));
        assertFalse(error(r).contains("java.lang"), error(r));
        assertTrue(error(r).contains("türü yanlış"), error(r));
        request(null, "en");
        assertTrue(error(handler.handleClassCast(cce)).startsWith("A field you sent has the wrong type"));
    }

    @Test
    @DisplayName("SecurityException → 403; bilinen kısa iletiler istek dilinde açıklanır, ön yüzün eşlediği iletiler KORUNUR")
    void forbidden() {
        ResponseEntity<Map<String, Object>> r = handler.handleForbidden(new SecurityException("yetkisiz"), null);
        assertEquals(403, r.getStatusCode().value());
        assertEquals("yetkisiz", error(r));
        assertEquals("FORBIDDEN", r.getBody().get("code"));

        assertTrue(error(handler.handleForbidden(new SecurityException("Admin access required"), null))
                .startsWith("Bu işlem yalnız sistem yöneticilerine açık"));
        ResponseEntity<Map<String, Object>> auth = handler.handleForbidden(new SecurityException("Not authenticated"), null);
        assertEquals("AUTH_REQUIRED", auth.getBody().get("code"));
        assertTrue(error(auth).contains("yeniden giriş"));
        assertTrue(error(handler.handleForbidden(new SecurityException("Bu işlem için yetkiniz yok: alerts.edit"), null))
                .contains("alerts.edit"));
        // PasswordChangeModal / AdminAutoResetModal bu metinle dallanır — dokunulmaz
        assertEquals("Invalid admin password", error(handler.handleForbidden(new SecurityException("Invalid admin password"), null)));
        ResponseEntity<Map<String, Object>> code = handler.handleForbidden(new SecurityException("WEEKLY_REPORTS_DISABLED"), null);
        assertEquals("WEEKLY_REPORTS_DISABLED", error(code));
        assertEquals("WEEKLY_REPORTS_DISABLED", code.getBody().get("code"));

        request(null, "en");
        assertTrue(error(handler.handleForbidden(new SecurityException("Bu takımın izlemesini düzenleyemezsiniz"), null))
                .startsWith("You can’t edit this team’s monitor"));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 VALIDATION_FAILED + fields map + alan iletileri metinde")
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
        assertEquals("VALIDATION_FAILED", r.getBody().get("code"));
        String err = error(r);
        assertTrue(err.contains("domain: boş olamaz"), err);
        assertTrue(err.contains("port"));
        assertTrue(err.contains("düzeltip tekrar deneyin"));
        Map<String, String> fields = (Map<String, String>) r.getBody().get("fields");
        assertEquals("boş olamaz", fields.get("domain"));
        assertEquals("1-65535 arası olmalı", fields.get("port"));
    }

    @Test
    @DisplayName("BindException (form bağlama) → aynı 400 VALIDATION_FAILED sözleşmesi")
    void bindException() {
        BindException be = new BindException(new Object(), "obj");
        be.addError(new FieldError("obj", "tier", "1-4 arası olmalı"));
        ResponseEntity<Map<String, Object>> r = handler.handleBind(be);
        assertEquals(400, r.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", r.getBody().get("code"));
        assertTrue(error(r).contains("tier"));
    }

    @Test
    @DisplayName("HandlerMethodValidationException → 400 VALIDATION_FAILED")
    void methodValidation() {
        ResponseEntity<Map<String, Object>> r = handler.handleMethodValidation(Mockito.mock(HandlerMethodValidationException.class));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", r.getBody().get("code"));
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400 MALFORMED_REQUEST + açıklayıcı metin")
    void malformedJson() {
        ResponseEntity<Map<String, Object>> r = handler.handleMalformedJson(
                new HttpMessageNotReadableException("bozuk json", (org.springframework.http.HttpInputMessage) null));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("MALFORMED_REQUEST", r.getBody().get("code"));
        assertTrue(error(r).startsWith("Geçersiz istek formatı"));
        assertFalse(error(r).contains("bozuk json"));
    }

    @Test
    @DisplayName("MissingServletRequestParameterException → 400 + parametre adı")
    void missingParam() {
        ResponseEntity<Map<String, Object>> r = handler.handleMissingParam(
                new MissingServletRequestParameterException("domain", "String"));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("MISSING_PARAMETER", r.getBody().get("code"));
        assertTrue(error(r).contains("domain"));
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400 INVALID_PARAMETER: ad, değer ve beklenen tür")
    void typeMismatch() throws Exception {
        MethodParameter mp = new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", Long.class), 0);
        ResponseEntity<Map<String, Object>> r = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", mp, new NumberFormatException("x")));
        assertEquals(400, r.getStatusCode().value());
        assertEquals("INVALID_PARAMETER", r.getBody().get("code"));
        assertTrue(error(r).contains("'id'") && error(r).contains("'abc'") && error(r).contains("sayı"), error(r));
    }

    @SuppressWarnings("unused")
    private void sample(Long id) { }

    @Test
    @DisplayName("MissingServletRequestPartException → 400 MISSING_FILE; MultipartException → 400 UPLOAD_FAILED")
    void uploadErrors() {
        ResponseEntity<Map<String, Object>> m = handler.handleMissingPart(new MissingServletRequestPartException("file"));
        assertEquals("MISSING_FILE", m.getBody().get("code"));
        assertTrue(error(m).contains("'file'"));
        ResponseEntity<Map<String, Object>> u = handler.handleMultipart(new MultipartException("Stream ended unexpectedly"));
        assertEquals(400, u.getStatusCode().value());
        assertEquals("UPLOAD_FAILED", u.getBody().get("code"));
        assertFalse(error(u).contains("Stream ended"));
    }

    /** Tomcat'in SizeException'ı gibi izin verilen boyutu taşıyan sahte neden. */
    static class FakeSizeException extends IllegalStateException {
        public long getPermittedSize() { return 5L * 1024 * 1024; }
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException → 413 + sınır MB (istisnadan ya da yapılandırmadan) + max_mb")
    void uploadTooLarge() {
        ResponseEntity<Map<String, Object>> r = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(-1, new FakeSizeException()));
        assertEquals(413, r.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", r.getBody().get("code"));
        assertTrue(error(r).contains("en fazla 5 MB"), error(r));
        assertEquals(5.0, (Double) r.getBody().get("max_mb"), 0.001);

        ReflectionTestUtils.setField(handler, "configuredMaxFileSize", "10MB");
        ResponseEntity<Map<String, Object>> c = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(-1));
        assertTrue(error(c).contains("en fazla 10 MB"), error(c));

        ReflectionTestUtils.setField(handler, "configuredMaxFileSize", null);
        ResponseEntity<Map<String, Object>> none = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(-1));
        assertEquals(413, none.getStatusCode().value());
        assertNull(none.getBody().get("max_mb"));
        assertEquals("1.5", GlobalExceptionHandler.formatMb(1572864));
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException → 415")
    void mediaType() {
        ResponseEntity<Map<String, Object>> r = handler.handleMediaType(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));
        assertEquals(415, r.getStatusCode().value());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", r.getBody().get("code"));
    }

    @Test
    @DisplayName("ResponseStatusException → kendi status'u; gerekçe yoksa duruma göre açıklayıcı metin")
    void responseStatus() {
        ResponseEntity<Map<String, Object>> r = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.GONE, "artık yok"));
        assertEquals(410, r.getStatusCode().value());
        assertEquals("artık yok", error(r));
        assertEquals("HTTP_410", r.getBody().get("code"));
        ResponseEntity<Map<String, Object>> bare = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertTrue(error(bare).startsWith("Bu işlem için yetkiniz yok"), error(bare));
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
    @DisplayName("405 → METHOD_NOT_ALLOWED + yenileme önerisi")
    void methodNotAllowed() {
        ResponseEntity<Map<String, Object>> r = handler.handleMethodNotAllowed(
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST"));
        assertEquals(405, r.getStatusCode().value());
        assertEquals("METHOD_NOT_ALLOWED", r.getBody().get("code"));
        assertTrue(error(r).contains("Ctrl+F5"));
    }

    @Test
    @DisplayName("DataAccessResourceFailureException (DB erişilemez/havuz boş) → 503 geçici")
    void dbUnavailable_returns503() {
        ResponseEntity<Map<String, Object>> r = handler.handleDbUnavailable(
                new org.springframework.dao.DataAccessResourceFailureException("Unable to acquire JDBC Connection"));
        assertEquals(503, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("success"));
        assertEquals("DB_UNAVAILABLE", r.getBody().get("code"));
        assertTrue(error(r).contains("ulaşılamıyor"));
        assertFalse(error(r).contains("JDBC"));
    }

    @Test
    @DisplayName("Kalıcı DataAccessException → 500 DATA_ACCESS_ERROR (ileti sızmaz); iç içe SQLState 23xxx → kısıt yanıtı")
    void dataAccess() {
        ResponseEntity<Map<String, Object>> r = handler.handleDataAccess(
                new org.springframework.dao.InvalidDataAccessApiUsageException("No EntityManager with actual transaction available"));
        assertEquals(500, r.getStatusCode().value());
        assertEquals("DATA_ACCESS_ERROR", r.getBody().get("code"));
        assertFalse(error(r).contains("EntityManager"));

        ResponseEntity<Map<String, Object>> c = handler.handleDataAccess(new jakarta.persistence.PersistenceException("flush",
                new SQLException("duplicate key value violates unique constraint \"uk_x\"", "23505")));
        assertEquals(409, c.getStatusCode().value());
        assertEquals("DUPLICATE_RECORD", c.getBody().get("code"));
    }

    @Test
    @DisplayName("İyimser kilit → 409 CONCURRENT_MODIFICATION; JPA kayıt yok → 404; asenkron zaman aşımı → 503")
    void concurrencyNotFoundTimeout() {
        ResponseEntity<Map<String, Object>> o = handler.handleOptimisticLock(
                new org.springframework.orm.ObjectOptimisticLockingFailureException("Team", 3L));
        assertEquals(409, o.getStatusCode().value());
        assertEquals("CONCURRENT_MODIFICATION", o.getBody().get("code"));
        assertEquals(404, handler.handleEntityNotFound(new jakarta.persistence.EntityNotFoundException("x")).getStatusCode().value());
        ResponseEntity<Map<String, Object>> t = handler.handleAsyncTimeout(new AsyncRequestTimeoutException());
        assertEquals(503, t.getStatusCode().value());
        assertEquals("REQUEST_TIMEOUT", t.getBody().get("code"));
    }

    @Test
    @DisplayName("Beklenmeyen Exception → 500 + açıklayıcı metin (stack trace / iç ileti sızmaz)")
    void generic() {
        ResponseEntity<Map<String, Object>> r = handler.handleGeneric(
                new RuntimeException("internal SecretField=abc123 leak"));
        assertEquals(500, r.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", r.getBody().get("code"));
        assertTrue(error(r).startsWith("Sunucuda beklenmeyen bir hata oluştu"), error(r));
        // Stack trace / internal mesaj kullanıcıya gitmemeli
        assertFalse(error(r).contains("SecretField"));
    }

    @Test
    @DisplayName("Spring'in 4xx ErrorResponse istisnaları (406 …) jenerik yolda 500 değil kendi durumunu alır")
    void generic_errorResponse4xx() {
        ResponseEntity<Map<String, Object>> r = handler.handleGeneric(new HttpMediaTypeNotAcceptableException("x"));
        assertEquals(406, r.getStatusCode().value());
        assertEquals("HTTP_406", r.getBody().get("code"));
    }

    @Test
    @DisplayName("AsyncRequestNotUsableException (istemci koptu) → sessiz (void, ERROR/500 yok)")
    void clientDisconnect_silent() {
        assertDoesNotThrow(() -> handler.handleClientDisconnect(
                new org.springframework.web.context.request.async.AsyncRequestNotUsableException("client gone")));
    }

    @Test
    @DisplayName("isClientAbort: broken pipe / connection reset / ClientAbort/Async → true; normal → false")
    void isClientAbort_detection() {
        assertTrue(GlobalExceptionHandler.isClientAbort(new java.io.IOException("Broken pipe")));
        assertTrue(GlobalExceptionHandler.isClientAbort(
                new RuntimeException(new java.io.IOException("Connection reset by peer"))));
        assertTrue(GlobalExceptionHandler.isClientAbort(
                new org.springframework.web.context.request.async.AsyncRequestNotUsableException("x")));
        assertFalse(GlobalExceptionHandler.isClientAbort(new RuntimeException("normal iş hatası")));
        assertFalse(GlobalExceptionHandler.isClientAbort(new java.io.IOException("disk dolu")));
    }

    @Test
    @DisplayName("handleGeneric: senkron istemci-kopması (broken pipe) → null (ERROR/500 YOK)")
    void generic_clientAbort_returnsNull() {
        ResponseEntity<Map<String, Object>> r = handler.handleGeneric(
                new RuntimeException(new java.io.IOException("Broken pipe")));
        assertNull(r);   // gövde yazılmaz — bağlantı ölü
    }

    @Nested
    @DisplayName("istek kimliği + dil")
    class RequestIdAndLanguage {

        @Test
        @DisplayName("istek bağlamında her hata gövdesi request_id taşır; 500 metni kimliği de söyler")
        void requestIdInEveryBody() {
            request("rid-123", null);
            List<ResponseEntity<Map<String, Object>>> all = List.of(
                    handler.handleNotFound(new NoSuchElementException("yok")),
                    handler.handleConflict(new IllegalStateException("çakıştı")),
                    handler.handleBadRequest(new IllegalArgumentException("geçersiz")),
                    handler.handleForbidden(new SecurityException("yetkisiz"), null),
                    handler.handleMalformedJson(new HttpMessageNotReadableException("x", (org.springframework.http.HttpInputMessage) null)),
                    handler.handleDbUnavailable(new org.springframework.dao.DataAccessResourceFailureException("x")),
                    handler.handleGeneric(new RuntimeException("boom")));
            for (ResponseEntity<Map<String, Object>> r : all) {
                assertEquals("rid-123", r.getBody().get("request_id"), r.getBody().toString());
                assertEquals(false, r.getBody().get("success"));
                assertNotNull(r.getBody().get("code"), r.getBody().toString());
            }
            assertTrue(error(all.get(all.size() - 1)).contains("rid-123"));
        }

        @Test
        @DisplayName("bağlam yoksa request_id alanı yok (birim/zamanlayıcı yolları)")
        void noContext_noRequestId() {
            assertFalse(handler.handleNotFound(new NoSuchElementException("yok")).getBody().containsKey("request_id"));
        }

        @Test
        @DisplayName("X-Lang: en → İngilizce açıklayıcı metinler")
        void english() {
            request("r", "en");
            assertTrue(error(handler.handleGeneric(new RuntimeException("boom"))).startsWith("An unexpected error occurred on the server"));
            assertTrue(error(handler.handleDbUnavailable(new org.springframework.dao.DataAccessResourceFailureException("x")))
                    .startsWith("The database can’t be reached"));
            assertTrue(error(handler.handleNotFound(new NoSuchElementException())).startsWith("The record you asked for"));
        }
    }
}
