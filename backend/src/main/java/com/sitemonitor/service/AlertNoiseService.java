package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;

/**
 * Alarm gürültü analizi (2026-09-12, zenginleştirme #18): son N günde en çok alarm üreten hedefler,
 * gün × saat ısı haritası (İstanbul), "flap" adayları (çok sayıda kısa alarm → eşik / onay sayısı
 * ayarı önerisi). Kapsam çağıranın predicate'i; tek repo sorgusu (createdAt ≥ cutoff).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertNoiseService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int MAX_DAYS = 90, TOP = 10, FLAP_MIN_ALERTS = 5, FLAP_MAX_AVG_MINUTES = 10;

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;

    public Map<String, Object> build(int days, Predicate<Long> canViewTeam) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        Set<String> domains = new HashSet<>();
        try { for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) if (canViewTeam.test(i.getTeamId())) domains.add(i.getDomain()); }
        catch (Exception e) { log.debug("noise: envanter okunamadı: {}", e.toString()); }

        String since = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        List<AlertEvent> events = new ArrayList<>();
        for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since)) {
            boolean vis = (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
            if (vis) events.add(e);
        }

        // Hedef başına sayım + süre
        Map<String, int[]> perTarget = new LinkedHashMap<>();          // key → [count, resolvedCount]
        Map<String, Double> perTargetMinutes = new HashMap<>();
        Map<String, String> targetType = new HashMap<>();
        int[][] heat = new int[7][24];                                   // [Pzt..Paz][0..23]
        int total = 0, critical = 0;
        for (AlertEvent e : events) {
            total++;
            if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) critical++;
            String key = (e.getDomain() == null ? "?" : e.getDomain()) + "|" + e.getAlertType();
            int[] c = perTarget.computeIfAbsent(key, k -> new int[2]);
            c[0]++;
            targetType.putIfAbsent(key, e.getAlertType());
            Instant created = parse(e.getCreatedAt());
            if (created != null) {
                ZonedDateTime z = created.atZone(IST);
                heat[z.getDayOfWeek().getValue() - 1][z.getHour()]++;
                Instant resolved = parse(e.getResolvedAt());
                if (Boolean.TRUE.equals(e.getResolved()) && resolved != null && resolved.isAfter(created)) {
                    c[1]++;
                    perTargetMinutes.merge(key, (resolved.toEpochMilli() - created.toEpochMilli()) / 60000.0, Double::sum);
                }
            }
        }

        List<Map<String, Object>> top = new ArrayList<>();
        List<Map<String, Object>> flapping = new ArrayList<>();
        for (Map.Entry<String, int[]> en : perTarget.entrySet()) {
            String[] parts = en.getKey().split("\\|", 2);
            int count = en.getValue()[0], resolved = en.getValue()[1];
            Double avg = resolved == 0 ? null : Math.round(perTargetMinutes.getOrDefault(en.getKey(), 0.0) / resolved * 10) / 10.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", parts[0]); m.put("type", parts.length > 1 ? parts[1] : null); m.put("count", count); m.put("resolved", resolved); m.put("avg_minutes", avg);
            m.put("share_pct", total == 0 ? 0 : Math.round(1000.0 * count / total) / 10.0);
            top.add(m);
            if (count >= FLAP_MIN_ALERTS && avg != null && avg <= FLAP_MAX_AVG_MINUTES) {
                Map<String, Object> f = new LinkedHashMap<>(m);
                f.put("suggestion", "raise-confirm");   // onay sayısı / eşik önerisi — metin arayüzde
                flapping.add(f);
            }
        }
        top.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        flapping.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));

        // Isı haritası: en yoğun hücre + en yoğun gün/saat
        int peak = 0, peakDay = -1, peakHour = -1;
        int[] byHour = new int[24], byDay = new int[7];
        for (int dow = 0; dow < 7; dow++) for (int h = 0; h < 24; h++) {
            int v = heat[dow][h]; byHour[h] += v; byDay[dow] += v;
            if (v > peak) { peak = v; peakDay = dow; peakHour = h; }
        }
        List<List<Integer>> heatRows = new ArrayList<>();
        for (int[] row : heat) { List<Integer> r = new ArrayList<>(24); for (int v : row) r.add(v); heatRows.add(r); }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d); out.put("total", total); out.put("critical", critical);
        out.put("distinct_targets", perTarget.size());
        out.put("top", top.subList(0, Math.min(TOP, top.size())));
        out.put("flapping", flapping);
        Map<String, Object> heatMap = new LinkedHashMap<>();
        heatMap.put("rows", heatRows); heatMap.put("peak", peak); heatMap.put("peak_day", peakDay); heatMap.put("peak_hour", peakHour);
        heatMap.put("by_hour", Arrays.stream(byHour).boxed().toList()); heatMap.put("by_day", Arrays.stream(byDay).boxed().toList());
        out.put("heat", heatMap);
        return out;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); } catch (Exception e) { return null; }
    }
}
