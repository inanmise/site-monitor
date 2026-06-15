package com.certmonitor.controller;

import com.certmonitor.model.GuideLink;
import com.certmonitor.repository.GuideLinkRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/guide-links")
@RequiredArgsConstructor
public class GuideLinkController {

    private final GuideLinkRepository repo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpSession session) {
        requireAuth(session);
        List<GuideLink> items = repo.findAllByOrderByCategoryAscSortOrderAscIdAsc();
        return ok(Map.of("data", items));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody GuideLink body, HttpSession session) {
        requireAdmin(session);
        validate(body);
        body.setId(null);
        Instant now = Instant.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        if (body.getSortOrder() == null) body.setSortOrder(0);
        GuideLink saved = repo.save(body);
        log.info("Guide link created id={} category={} title={} by={}",
                saved.getId(), saved.getCategory(), saved.getTitle(), actor(session));
        return ok(Map.of("data", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id, @RequestBody GuideLink body, HttpSession session) {
        requireAdmin(session);
        validate(body);
        GuideLink existing = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Guide link not found: " + id));
        existing.setCategory(body.getCategory());
        existing.setTitle(body.getTitle());
        existing.setUrl(body.getUrl());
        existing.setDescription(body.getDescription());
        if (body.getSortOrder() != null) existing.setSortOrder(body.getSortOrder());
        existing.setUpdatedAt(Instant.now());
        GuideLink saved = repo.save(existing);
        log.info("Guide link updated id={} by={}", saved.getId(), actor(session));
        return ok(Map.of("data", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        if (!repo.existsById(id)) {
            throw new NoSuchElementException("Guide link not found: " + id);
        }
        repo.deleteById(id);
        log.info("Guide link deleted id={} by={}", id, actor(session));
        return ok(Map.of("message", "Deleted"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void validate(GuideLink body) {
        if (body == null) throw new IllegalArgumentException("Body required");
        if (isBlank(body.getCategory())) throw new IllegalArgumentException("Category required");
        if (isBlank(body.getTitle()))    throw new IllegalArgumentException("Title required");
        if (isBlank(body.getUrl()))      throw new IllegalArgumentException("URL required");
        if (body.getCategory().length() > 100) throw new IllegalArgumentException("Category too long");
        if (body.getTitle().length()    > 200) throw new IllegalArgumentException("Title too long");
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private void requireAuth(HttpSession session) {
        if (session.getAttribute("username") == null) {
            throw new SecurityException("Authentication required");
        }
    }

    private void requireAdmin(HttpSession session) {
        requireAuth(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            log.warn("Unauthorized guide-link admin attempt by user={}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
