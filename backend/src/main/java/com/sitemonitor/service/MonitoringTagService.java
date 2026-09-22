package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Takımın kullanımdaki etiket kataloğu (2026-09-22, kullanıcı isteği: "yeni izleme eklerken takımın mevcut
 * etiketlerinden arayıp seçebilsin").
 *
 * <p>Etiketler ayrı bir tabloda tutulmaz: her izleme türü + sertifika envanteri kendi {@code tags} CSV
 * kolonunu taşır. Katalog bu on kaynaktan takım bazında türetilir (aktif kayıtlar; envanterde silinmemiş).
 * Tür bağımsızdır: envanterdeki "pci" etiketi HTTP izlemesinde de önerilir — takım aynı sözlüğü kullansın.
 * Büyük/küçük harf birleştirilir, ilk görülen yazım korunur; sıralama kullanım sayısı ↓, ad ↑.
 *
 * <p>Sızıntı yok: yalnız istenen {@code teamId} okunur, yetki denetimi çağıran uçta (SessionScope).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringTagService {

    /** tablo → takım kolonu; envanter soft-delete süzgeci ayrıca. */
    static final String[] MONITOR_TABLES = {
            "http_monitors", "keyword_monitors", "page_monitors", "pagespeed_monitors", "ping_monitors",
            "port_monitors", "dns_monitors", "domain_monitors", "scripted_monitors"
    };

    private final JdbcTemplate jdbcTemplate;

    public record TagUse(String name, int count) {}

    public List<TagUse> listForTeam(Long teamId) {
        if (teamId == null) return List.of();
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : MONITOR_TABLES) {
            collect(counts, spelling, "SELECT tags FROM " + table + " WHERE team_id = ? AND active = true AND tags IS NOT NULL", teamId);
        }
        collect(counts, spelling, "SELECT tags FROM certificate_inventory WHERE team_id = ? AND active = true AND deleted_at IS NULL AND tags IS NOT NULL", teamId);
        List<TagUse> out = new ArrayList<>();
        for (var e : counts.entrySet()) out.add(new TagUse(spelling.get(e.getKey()), e.getValue()));
        out.sort(Comparator.comparingInt(TagUse::count).reversed().thenComparing(TagUse::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void collect(Map<String, Integer> counts, Map<String, String> spelling, String sql, Long teamId) {
        try {
            for (String csv : jdbcTemplate.queryForList(sql, String.class, teamId)) {
                if (csv == null || csv.isBlank()) continue;
                for (String raw : csv.split(",")) {
                    String tag = raw.trim();
                    if (tag.isEmpty()) continue;
                    String key = tag.toLowerCase(Locale.ROOT);
                    spelling.putIfAbsent(key, tag);
                    counts.merge(key, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            // Bir tablo/kolon henüz yoksa (eski şema) katalog o kaynak olmadan devam eder — form kırılmaz.
            log.debug("Etiket kataloğu kaynağı okunamadı ({}): {}", sql, e.getMessage());
        }
    }
}
