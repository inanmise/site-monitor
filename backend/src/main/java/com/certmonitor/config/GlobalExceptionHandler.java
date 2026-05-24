package com.certmonitor.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(404)
                .body(Map.of("success", false, "error", e.getMessage()));
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
}
