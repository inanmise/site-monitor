package com.sitemonitor.controller;

import com.sitemonitor.service.DatabaseHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dış izleme için VERİTABANI sağlık ucu (2026-10-08, kullanıcı isteği) — oturumsuz ({@code AuthInterceptor} PUBLIC).
 *
 * <p>{@code GET /api/public/health/db} (HEAD de çalışır): veritabanı her şeyiyle yolundaysa {@code 200 status=UP},
 * yavaş / havuz dolu / şema yaması başarısız gibi dikkat gerektiren durumda {@code 200 status=DEGRADED}, bağlantı yok /
 * zaman aşımı / salt-okunur ise {@code 503 status=DOWN}. Dış izleyici yalnız HTTP koduna bakabilir. Gövde gizli bilgi
 * içermez (bkz. {@link DatabaseHealthService}); yanıt {@code no-store} — NetScaler bayat kopya tutmasın.
 */
@RestController
@RequiredArgsConstructor
public class PublicHealthController {

    private final DatabaseHealthService databaseHealth;

    @GetMapping("/api/public/health/db")
    public ResponseEntity<Map<String, Object>> database() {
        DatabaseHealthService.Snapshot s = databaseHealth.current();
        int code = DatabaseHealthService.DOWN.equals(s.status()) ? 503 : 200;
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(s.body());
    }
}
