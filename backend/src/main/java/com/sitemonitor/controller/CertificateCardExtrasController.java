package com.sitemonitor.controller;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.CertificateCardExtrasService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Paylaşılan sertifika ayrıntısı (2026-09-22, kullanıcı isteği): kart üzerindeki "N alan aynı sertifikayı paylaşıyor"
     * çipi tıklanınca açılan pencerenin verisi. Aynı parmak izini taşıyan TÜM alanlar + karar için gereken bağlam
     * (takım, tier, port, durum, kalan gün, bitiş, veren, son kontrol) ve sertifikanın kendisi (parmak izi, subject, SAN).
     * Görüş kapsamı: kullanıcı yalnız görebildiği takımların alanlarını görür; kapsam dışı eşler SAYIDA kalır ({@code hidden}).
     */
    @GetMapping("/shared")
    public ResponseEntity<Map<String, Object>> shared(@RequestParam String domain, HttpSession session) {
        Set<String> scope = null;
        if (!SessionScope.isGlobalViewer(session)) {
            List<Long> teams = SessionScope.viewTeamIds(session);
            scope = teams == null || teams.isEmpty() ? Set.of() : new HashSet<>(inventoryRepo.findDomainsForTeams(teams));
        }
        if (scope != null && !scope.contains(domain)) {
            Map<String, Object> deny = new LinkedHashMap<>();
            deny.put("success", false); deny.put("error", "Bu alanı görme yetkiniz yok");
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(deny);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", extrasService.sharedDetail(domain, scope));
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

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
