package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Eşik etki önizlemesi (2026-09-20): "bu değerlerle BUGÜN kaç alan hangi seviyede olur?"
 *
 * <p>Yönetici eşiği düzenlerken körlemesine kaydetmesin: kritik günü 7'den 14'e çekmek 40 alanı
 * bir gecede KRİTİK'e düşürebilir ve o kadar e-posta/push üretir. Önizleme kalıcı hiçbir şey yazmaz;
 * {@code latest_checks} üzerinden kalan güne bakar. Kapsam, satırın kapsamıyla aynıdır: tier satırı
 * yalnız o tier'daki alanları, varsayılan satır tier'sız alanları + kendi satırı OLMAYAN tier'ları sayar.
 */
@Service
@RequiredArgsConstructor
public class ThresholdPreviewService {

    static final int SAMPLE_LIMIT = 8;

    private final AlertThresholdRepository thresholdRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.cache.CacheManager> cacheManagerProvider;

    /** Eşik değişince eşikten TÜRETİLEN cache'ler boşalır: kart seviyesi, istatistik, sağlık, bugün, kart ekleri. */
    static final List<String> DERIVED_CACHES = List.of(
            "cert-latest", "cert-stats", "cert-warnings", "renewal-advice", "card-extras", "today-monitors");

    /**
     * Eşik kaydedildi/silindi → türetilmiş cache'leri boşalt. Eskiden eşik güncellemesi cache'e dokunmuyordu:
     * Genel Bakış kartları TTL dolana kadar eski seviyeyle kalıyor, yönetici "kaydettim ama değişmedi" diyordu.
     */
    public void afterThresholdChange() {
        org.springframework.cache.CacheManager cm = cacheManagerProvider == null ? null : cacheManagerProvider.getIfAvailable();
        if (cm == null) return;
        for (String name : DERIVED_CACHES) {
            org.springframework.cache.Cache cache = cm.getCache(name);
            if (cache != null) cache.clear();
        }
    }

    /** Yeni değerlerle sayım + mevcut eşikle sayım (karşılaştırma) + seviye başına örnek alanlar. */
    public Map<String, Object> preview(Integer tier, int warning, int high, int critical) {
        ThresholdResolution current = ThresholdResolution.load(thresholdRepo, null);
        List<CertificateInventory> inv = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        List<String> scoped = new ArrayList<>();
        for (CertificateInventory i : inv) {
            if (i.getDomain() == null) continue;
            if (tier != null) {
                if (Objects.equals(i.getTier(), tier)) scoped.add(i.getDomain());
            } else if (i.getTier() == null || !current.hasOverride(i.getTier())) {
                scoped.add(i.getDomain());
            }
        }
        AlertThreshold proposed = new AlertThreshold();
        proposed.setWarningDays(warning);
        proposed.setHighDays(high);
        proposed.setCriticalDays(critical);
        ThresholdResolution next = ThresholdResolution.fixed(proposed);

        Map<String, Integer> tierByDomain = new LinkedHashMap<>();
        for (CertificateInventory i : inv) if (i.getDomain() != null) tierByDomain.put(i.getDomain(), i.getTier());

        Counter now = new Counter(), after = new Counter();
        Map<String, List<String>> samples = new LinkedHashMap<>();
        samples.put("CRITICAL", new ArrayList<>()); samples.put("HIGH", new ArrayList<>()); samples.put("WARNING", new ArrayList<>());
        int unchecked = 0;
        if (!scoped.isEmpty()) {
            Map<String, LatestCheck> lcByDomain = new LinkedHashMap<>();
            for (LatestCheck lc : latestCheckRepo.findByDomainIn(scoped)) lcByDomain.put(lc.getDomain(), lc);
            for (String d : scoped) {
                LatestCheck lc = lcByDomain.get(d);
                Integer days = lc != null ? lc.getDaysRemaining() : null;
                if (days == null) { unchecked++; continue; }
                now.add(current.levelFor(tierByDomain.get(d), days));
                String lv = next.levelFor(null, days);
                after.add(lv);
                if (lv != null) {
                    List<String> s = samples.get(lv);
                    if (s.size() < SAMPLE_LIMIT) s.add(d + " (" + days + "g)");
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", tier);
        out.put("scope_total", scoped.size());
        out.put("unchecked", unchecked);
        out.put("current", now.toMap());
        out.put("proposed", after.toMap());
        out.put("samples", samples);
        return out;
    }

    private static final class Counter {
        int critical, high, warning, ok;
        void add(String level) {
            if (level == null) { ok++; return; }
            switch (level) {
                case "CRITICAL" -> critical++;
                case "HIGH" -> high++;
                case "WARNING" -> warning++;
                default -> ok++;
            }
        }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("critical", critical); m.put("high", high); m.put("warning", warning); m.put("ok", ok);
            return m;
        }
    }
}
