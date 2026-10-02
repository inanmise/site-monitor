package com.sitemonitor.controller;

import com.sitemonitor.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Çevrimiçi kullanıcı özeti (2026-10-02) — kenar çubuğu göstergesi 30 sn'de bir okur.
 *
 * <p>Oturum açmış HER kullanıcıya açık (kullanıcı kararı: "bu bilgiyi bütün kullanıcılar görebilsin"); kimlik kapısı
 * {@code AuthInterceptor} ({@code /api/**}). Yanıtta yalnız sayılar ve takım adları var — kişi bilgisi yok.
 * Önbellek sunucuda ({@link PresenceService}); yanıt {@code no-store} (WebConfig, tüm /api).
 */
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;

    @GetMapping("/online")
    public ResponseEntity<Map<String, Object>> online() {
        return ResponseEntity.ok(Map.of("success", true, "data", presenceService.online()));
    }
}
