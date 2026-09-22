package com.sitemonitor.service;

import com.sitemonitor.model.Platform;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.PlatformRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Platform kataloğu (2026-09-22): Ayarlar → Platformlar'dan yönetilen, envanterin "site nerede koşuyor" sözlüğü.
 *
 * <ul>
 *   <li>İlk açılışta boşsa varsayılanlar ekilir (IIS, OpenShift, Kubernetes, Linux, Windows, Bulut, Diğer) —
 *       kullanıcı yeniden adlandırabilir/pasife alabilir, kod sabit kalır.</li>
 *   <li>Kod üst-harf slug ({@code A-Z0-9_}, 2–20) ve tekildir; envanter satırları bu koda bağlıdır.</li>
 *   <li>Kullanımda olan platform SİLİNMEZ (409 mantığı çağıranda) — pasife alınır; envanterdeki kayıtlar
 *       değerini korur, yalnız seçicide görünmez.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{2,20}$");

    /** Varsayılan katalog — {code, ad, açıklama}. */
    static final String[][] DEFAULTS = {
            {"IIS",        "Internet Information Services (IIS)", "Windows sunucuda IIS üzerinde koşan site — sertifika IIS/Windows sertifika deposuna kurulur"},
            {"OPENSHIFT",  "OpenShift",      "OpenShift kümesinde route/ingress ile yayınlanan uygulama — sertifika route/secret olarak güncellenir"},
            {"KUBERNETES", "Kubernetes",     "Kubernetes kümesinde ingress ile yayınlanan uygulama — TLS secret güncellenir"},
            {"LINUX",      "Linux sunucu",   "Linux üzerinde nginx/Apache/haproxy — sertifika dosya olarak sunucuya kurulur"},
            {"WINDOWS",    "Windows servisi","IIS dışı Windows servisi (özel uygulama, .NET self-host)"},
            {"CLOUD",      "Bulut / CDN",    "Bulut yük dengeleyici veya CDN (sertifika sağlayıcı konsolundan yönetilir)"},
            {"OTHER",      "Diğer",          "Yukarıdakilere girmeyen ortam — ayrıntıyı platform notuna yazın"},
    };

    private final PlatformRepository repo;
    private final CertificateInventoryRepository inventoryRepo;

    @PostConstruct
    void seedDefaults() {
        try {
            if (repo.count() > 0) return;
            int order = 10;
            for (String[] d : DEFAULTS) {
                Platform p = new Platform();
                p.setCode(d[0]); p.setName(d[1]); p.setDescription(d[2]); p.setActive(true); p.setSortOrder(order);
                p.setCreatedAt(ISO.format(Instant.now())); p.setCreatedBy("system");
                repo.save(p);
                order += 10;
            }
            log.info("Platform kataloğu boştu — {} varsayılan platform ekildi", DEFAULTS.length);
        } catch (Exception e) {
            log.warn("Platform kataloğu ekilemedi (tablo henüz yok olabilir): {}", e.getMessage());
        }
    }

    public List<Platform> listAll() { return repo.findAllByOrderBySortOrderAscNameAsc(); }
    public List<Platform> listActive() { return repo.findByActiveTrueOrderBySortOrderAscNameAsc(); }

    /** Envanterde kod başına kullanım sayısı (silinmemiş satırlar). */
    public Map<String, Long> usage() {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : inventoryRepo.platformCounts()) if (row[0] != null) out.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        return out;
    }

    /** Katalog + kullanım sayısı — Ayarlar ekranı için tek yanıt. */
    public List<Map<String, Object>> listWithUsage() {
        Map<String, Long> use = usage();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Platform p : listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("code", p.getCode()); m.put("name", p.getName()); m.put("description", p.getDescription());
            m.put("active", Boolean.TRUE.equals(p.getActive())); m.put("sort_order", p.getSortOrder());
            m.put("usage", use.getOrDefault(p.getCode(), 0L));
            m.put("created_at", p.getCreatedAt()); m.put("updated_at", p.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    /** Envanter/CSV'den gelen değeri kataloğa göre doğrular: kod ya da ad (büyük/küçük harf duyarsız) → kod; bilinmeyen → null. */
    public String normalize(String v) {
        if (v == null || v.isBlank()) return null;
        String u = v.trim().toUpperCase(Locale.ROOT);
        for (Platform p : listAll()) {
            if (p.getCode().equalsIgnoreCase(u) || p.getName().trim().equalsIgnoreCase(v.trim())) return p.getCode();
        }
        return null;
    }

    public Platform create(String code, String name, String description, Integer sortOrder, String actor) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!CODE.matcher(c).matches()) throw new IllegalArgumentException("Kod 2–20 karakter, yalnız A-Z 0-9 _ olmalı");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Ad zorunlu");
        if (repo.existsByCodeIgnoreCase(c)) throw new IllegalArgumentException("Bu kod zaten var: " + c);
        Platform p = new Platform();
        p.setCode(c); p.setName(name.trim()); p.setDescription(blank(description)); p.setActive(true);
        p.setSortOrder(sortOrder == null ? nextOrder() : sortOrder);
        p.setCreatedAt(ISO.format(Instant.now())); p.setCreatedBy(actor);
        return repo.save(p);
    }

    public Platform update(Long id, String name, String description, Boolean active, Integer sortOrder) {
        Platform p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Platform bulunamadı"));
        if (name != null) { if (name.isBlank()) throw new IllegalArgumentException("Ad zorunlu"); p.setName(name.trim()); }
        if (description != null) p.setDescription(blank(description));
        if (active != null) p.setActive(active);
        if (sortOrder != null) p.setSortOrder(sortOrder);
        p.setUpdatedAt(ISO.format(Instant.now()));
        return repo.save(p);
    }

    /** Kullanımda değilse siler; kullanımdaysa false döner (çağıran pasife almayı önerir). */
    public boolean delete(Long id) {
        Platform p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Platform bulunamadı"));
        long inUse = usage().getOrDefault(p.getCode(), 0L);
        if (inUse > 0) return false;
        repo.delete(p);
        return true;
    }

    public Platform get(Long id) { return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Platform bulunamadı")); }

    private int nextOrder() {
        int max = 0;
        for (Platform p : listAll()) if (p.getSortOrder() != null && p.getSortOrder() > max) max = p.getSortOrder();
        return max + 10;
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
