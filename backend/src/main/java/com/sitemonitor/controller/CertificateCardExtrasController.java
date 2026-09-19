package com.sitemonitor.controller;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.CertificateCardExtrasService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Genel Bakış sertifika kartı zenginleştirmeleri (2026-09-19) — {@code GET /api/certificates/card-extras}.
 * Takım kapsamı {@code /api/certificates} ile aynı: global görücü hepsi, kapsamlı kullanıcı görüş takımlarının
 * envanter alanları (birincil VEYA UG takımı). Alan adı → blok haritası döner; kart alan adıyla birleştirir.
 */
@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
public class CertificateCardExtrasController {

    private final CertificateCardExtrasService extrasService;
    private final CertificateInventoryRepository inventoryRepo;

    @GetMapping("/card-extras")
    public ResponseEntity<Map<String, Object>> cardExtras(HttpSession session) {
        Set<String> scope = null;   // null = global
        if (!SessionScope.isGlobalViewer(session)) {
            List<Long> teams = SessionScope.viewTeamIds(session);
            scope = teams == null || teams.isEmpty() ? Set.of() : new HashSet<>(inventoryRepo.findDomainsForTeams(teams));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", extrasService.forDomains(scope));
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}
