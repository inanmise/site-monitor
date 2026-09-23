package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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

        // Pencere ile kovalar AYNI takvimden: olaylar UTC kayan pencereden çekilirken kovalar
        // LocalDate.now(IST) ile kuruluyordu ve :81 computeIfPresent kullandığı için haritada
        // olmayan gün SESSİZCE düşüyordu. d=7, 13:00 IST: since = 6 gün önce 10:00Z, kovalar
        // bugün dâhil son 7 IST günü → o gün 10:00Z-21:00Z arasında açılan alarmlar total'e
        // giriyor ama series'ten düşüyordu (KPI 120, kıvılcım çizgisinin toplamı 97).
        String since = ISO.format(LocalDate.now(IST).minusDays(d - 1L).atStartOfDay(IST).toInstant());
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
        // Zenginleştirme (2026-09-16): günlük seri (kıvılcım çizgisi), tip/seviye kırılımı, MTTR,
        // çözülme oranı ve mesai-dışı payı. Hepsi AYNI tek geçişten çıkar — ek sorgu yok.
        Map<String, Integer> byDate = new LinkedHashMap<>();
        for (int i = d - 1; i >= 0; i--) byDate.put(LocalDate.now(IST).minusDays(i).toString(), 0);
        Map<String, int[]> byType = new LinkedHashMap<>();                // tip → [toplam, kritik]
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        double resolvedMinutesSum = 0;
        int resolvedTotal = 0, offHours = 0, stillOpen = 0;
        int total = 0, critical = 0;
        for (AlertEvent e : events) {
            total++;
            if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) critical++;
            byLevel.merge(e.getAlertLevel() == null ? "?" : e.getAlertLevel().toUpperCase(Locale.ROOT), 1, Integer::sum);
            int[] tc = byType.computeIfAbsent(e.getAlertType() == null ? "?" : e.getAlertType(), k -> new int[2]);
            tc[0]++;
            if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) tc[1]++;
            if (!Boolean.TRUE.equals(e.getResolved())) stillOpen++;
            String key = (e.getDomain() == null ? "?" : e.getDomain()) + "|" + e.getAlertType();
            int[] c = perTarget.computeIfAbsent(key, k -> new int[2]);
            c[0]++;
            targetType.putIfAbsent(key, e.getAlertType());
            Instant created = parse(e.getCreatedAt());
            if (created != null) {
                ZonedDateTime z = created.atZone(IST);
                heat[z.getDayOfWeek().getValue() - 1][z.getHour()]++;
                byDate.computeIfPresent(z.toLocalDate().toString(), (k, v) -> v + 1);
                // Mesai dışı: hafta sonu ya da 18:00–09:00 arası (İstanbul) — "nöbet yükü" göstergesi.
                boolean weekend = z.getDayOfWeek().getValue() >= 6;
                if (weekend || z.getHour() >= 18 || z.getHour() < 9) offHours++;
                Instant resolved = parse(e.getResolvedAt());
                if (Boolean.TRUE.equals(e.getResolved()) && resolved != null && resolved.isAfter(created)) {
                    c[1]++;
                    resolvedTotal++;
                    resolvedMinutesSum += (resolved.toEpochMilli() - created.toEpochMilli()) / 60000.0;
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
        // ── Zenginleştirme alanları (2026-09-16) ──
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, Integer> en : byDate.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", en.getKey()); p.put("count", en.getValue());
            series.add(p);
        }
        out.put("series", series);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map.Entry<String, int[]> en : byType.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", en.getKey()); p.put("count", en.getValue()[0]); p.put("critical", en.getValue()[1]);
            p.put("share_pct", total == 0 ? 0 : Math.round(1000.0 * en.getValue()[0] / total) / 10.0);
            types.add(p);
        }
        types.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        out.put("by_type", types.subList(0, Math.min(TOP, types.size())));
        out.put("by_level", byLevel);
        out.put("still_open", stillOpen);
        out.put("resolved_total", resolvedTotal);
        out.put("resolved_pct", total == 0 ? 0 : Math.round(1000.0 * resolvedTotal / total) / 10.0);
        out.put("mttr_minutes", resolvedTotal == 0 ? null : Math.round(resolvedMinutesSum / resolvedTotal * 10) / 10.0);
        out.put("off_hours", offHours);
        out.put("off_hours_pct", total == 0 ? 0 : Math.round(1000.0 * offHours / total) / 10.0);
        out.put("per_day_avg", Math.round(10.0 * total / d) / 10.0);
        return out;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); } catch (Exception e) { return null; }
    }
}
