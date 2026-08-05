package com.sitemonitor.service;

import com.sitemonitor.model.PermissionGrant;
import com.sitemonitor.repository.PermissionGrantRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Yetki kontrolü için tek nokta. DB'den okunan grants'i memory cache'te tutar.
 * Permission değişikliğinde {@link #rebuildCache()} çağrılmalı.
 *
 * Fail-closed: cache'te kayıp bir grant varsa default false döner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final PermissionGrantRepository repo;

    /** role → resource_key → action → allowed */
    private volatile Map<String, Map<String, Map<String, Boolean>>> cache = Map.of();

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @PostConstruct
    void load() {
        rebuildCache();
    }

    private Map<String, Map<String, Map<String, Boolean>>> buildCache() {
        Map<String, Map<String, Map<String, Boolean>>> next = new HashMap<>();
        for (PermissionGrant g : repo.findAll()) {
            next.computeIfAbsent(g.getRole(), r -> new HashMap<>())
                .computeIfAbsent(g.getResourceKey(), rk -> new HashMap<>())
                .put(g.getAction(), Boolean.TRUE.equals(g.getAllowed()));
        }
        return next;
    }

    public synchronized void rebuildCache() {
        this.cache = buildCache();
        log.info("Permission cache rebuilt: {} roles", cache.size());
    }

    /** Çok-pod tutarlılığı: başka bir instance grant değiştirdiyse yetki matrisini DB'den tazele.
     *  Aynı pod değişikliğinde no-op (rebuildCache zaten güncelledi). */
    @Scheduled(fixedDelayString = "${site.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    synchronized void refreshFromDb() {
        try {
            Map<String, Map<String, Map<String, Boolean>>> next = buildCache();
            if (!next.equals(cache)) {
                this.cache = next;
                log.info("Permission cache refreshed from DB (updated by another instance): {} roles", next.size());
            }
        } catch (Exception e) {
            log.debug("Permission refresh skipped: {}", e.getMessage());
        }
    }

    public boolean allows(String role, String resourceKey, String action) {
        if (role == null) return false;
        Map<String, Map<String, Boolean>> roleMap = cache.get(role);
        if (roleMap == null) return false;
        Map<String, Boolean> resMap = roleMap.get(resourceKey);
        if (resMap == null) return false;
        return Boolean.TRUE.equals(resMap.get(action));
    }

    public boolean allows(HttpSession session, String resourceKey, String action) {
        return allows((String) session.getAttribute("systemRole"), resourceKey, action);
    }

    /** Yetki kapısı: rolün bu (resource, action) iznine sahip olmaması 403 (SecurityException) üretir.
     *  Takım-scope kontrolleri AYRI yapılır; bu yalnız "rol bu işlemi yapabilir mi" sorusudur. */
    public void require(HttpSession session, String resourceKey, String action) {
        require((String) session.getAttribute("systemRole"), resourceKey, action);
    }

    public void require(String role, String resourceKey, String action) {
        if (!allows(role, resourceKey, action)) {
            throw new SecurityException("Bu işlem için yetkiniz yok: " + resourceKey + "/" + action);
        }
    }

    /** Role bazlı snapshot — frontend için ön-derlenmiş map. */
    public Map<String, Map<String, Boolean>> snapshotForRole(String role) {
        Map<String, Map<String, Boolean>> src = cache.getOrDefault(role, Map.of());
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        src.forEach((k, v) -> copy.put(k, new LinkedHashMap<>(v)));
        return copy;
    }

    /**
     * Bootstrap: ilk başlangıçta tablo boşsa default seed'i uygula.
     * Idempotent — sonradan DB'de değişiklik olursa yeniden seed olmaz.
     */
    @Transactional
    public void seedDefaultsIfEmpty() {
        if (repo.count() > 0) return;
        seedDefaults();
        log.info("Permission table seeded with defaults.");
    }

    /** "Reset to defaults" endpoint'i tarafından çağrılır. */
    @Transactional
    public void seedDefaults() {
        repo.deleteAll();
        String now = ISO.format(Instant.now());
        for (String role : new String[] {"ADMIN", "TEAM_ADMIN", "USER", "AUDIT"}) {
            Map<String, Map<String, Boolean>> defaults = PermissionCatalog.defaultsFor(role);
            defaults.forEach((resourceKey, actions) -> actions.forEach((action, allowed) -> {
                PermissionGrant g = new PermissionGrant();
                g.setRole(role);
                g.setResourceKey(resourceKey);
                g.setAction(action);
                g.setAllowed(allowed);
                g.setUpdatedAt(now);
                g.setUpdatedBy("system");
                repo.save(g);
            }));
        }
        rebuildCache();
    }

    /**
     * Katalogda yeni eklenen (role, resourceKey, action) default'larını DB'ye EKLER —
     * mevcut satırlara DOKUNMAZ (admin özelleştirmeleri korunur). Dolu tablolarda
     * {@link #seedDefaultsIfEmpty()} çalışmadığından yeni modüller (örn. weekly_reports,
     * diagnostics) için gereklidir. Idempotent: eksik yoksa hiçbir şey yapmaz.
     */
    @Transactional
    public void seedMissingDefaults() {
        String now = ISO.format(Instant.now());
        int added = 0;
        for (String role : new String[] {"ADMIN", "TEAM_ADMIN", "USER", "AUDIT"}) {
            Map<String, Map<String, Boolean>> defaults = PermissionCatalog.defaultsFor(role);
            for (Map.Entry<String, Map<String, Boolean>> res : defaults.entrySet()) {
                for (Map.Entry<String, Boolean> act : res.getValue().entrySet()) {
                    if (repo.findByRoleAndResourceKeyAndAction(role, res.getKey(), act.getKey()).isEmpty()) {
                        PermissionGrant g = new PermissionGrant();
                        g.setRole(role);
                        g.setResourceKey(res.getKey());
                        g.setAction(act.getKey());
                        g.setAllowed(act.getValue());
                        g.setUpdatedAt(now);
                        g.setUpdatedBy("system");
                        repo.save(g);
                        added++;
                    }
                }
            }
        }
        if (added > 0) {
            log.info("Permission backfill: {} eksik default grant eklendi (yeni modüller).", added);
            rebuildCache();
        }
    }

    /** Update single grant. ADMIN row'ları her zaman true; bypass yok. */
    @Transactional
    public PermissionGrant upsertGrant(String role, String resourceKey, String action, boolean allowed, String updatedBy) {
        if ("ADMIN".equals(role) && !allowed) {
            throw new SecurityException("ADMIN permissions cannot be revoked");
        }
        PermissionGrant g = repo.findByRoleAndResourceKeyAndAction(role, resourceKey, action)
                                .orElseGet(PermissionGrant::new);
        g.setRole(role);
        g.setResourceKey(resourceKey);
        g.setAction(action);
        g.setAllowed(allowed);
        g.setUpdatedAt(ISO.format(Instant.now()));
        g.setUpdatedBy(updatedBy);
        PermissionGrant saved = repo.save(g);
        rebuildCache();
        return saved;
    }

    public Optional<PermissionGrant> find(String role, String resourceKey, String action) {
        return repo.findByRoleAndResourceKeyAndAction(role, resourceKey, action);
    }
}
