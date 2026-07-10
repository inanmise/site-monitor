package com.certmonitor.service;

import com.certmonitor.model.MaintenanceWindow;
import com.certmonitor.repository.MaintenanceWindowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bakım penceresi motoru: occurrence/recurrence hesabı (DST-güvenli, java.time ZonedDateTime) + bellek-içi aktif-hedef
 * cache'i. Sweep/alarm yolu {@link #isUnderMaintenance(String)} ile O(1) sorgular — per-check DB sorgusu YOK.
 * Cache @Scheduled ile ~30sn'de bir ve her CRUD yazımında ({@link #refresh()}) tazelenir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MaintenanceWindowRepository repo;

    /** Şu an bakımda olan hedeflerin (alarm-anahtarı) kümesi + "tüm monitörler" bayrağı — sweep bunları okur. */
    private volatile Set<String> activeTargets = Set.of();
    private volatile boolean allActive = false;

    // ── Bellek cache ─────────────────────────────────────────────────────────
    @Scheduled(fixedDelayString = "${cert.monitor.maintenance.refresh-ms:30000}", initialDelayString = "20000")
    public void scheduledRefresh() {
        try { refresh(); } catch (Exception e) { log.warn("Maintenance cache refresh başarısız: {}", e.getMessage()); }
    }

    /** Etkin pencereleri tarayıp şu an aktif olanların hedeflerini birleştirir. CRUD sonrası da çağrılır (anında etki). */
    public synchronized void refresh() {
        Instant now = Instant.now();
        Set<String> targets = new HashSet<>();
        boolean all = false;
        for (MaintenanceWindow w : repo.findByActiveTrue()) {
            if (!isActiveAt(w, now)) continue;
            if (Boolean.TRUE.equals(w.getAllMonitors())) { all = true; continue; }
            targets.addAll(targetsOf(w));
        }
        this.activeTargets = targets;
        this.allActive = all;
    }

    /** Bir alarm-anahtarı (host/url/domain) şu an bakımda mı? O(1). */
    public boolean isUnderMaintenance(String target) {
        return allActive || (target != null && activeTargets.contains(target));
    }

    /** /active endpoint'i için: {all, targets}. */
    public Map<String, Object> activeInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("all", allActive);
        m.put("targets", new ArrayList<>(activeTargets));
        return m;
    }

    // ── Occurrence engine (DST-güvenli) ──────────────────────────────────────
    /** Verilen anda pencere aktif mi (paused ise her zaman false). */
    public boolean isActiveAt(MaintenanceWindow w, Instant now) {
        if (!Boolean.TRUE.equals(w.getActive())) return false;
        Instant anchor = parse(w.getStartAt());
        if (anchor == null) return false;
        long dur = durMin(w);
        ZoneId zone = zoneOf(w);
        String rec = rec(w);
        if ("NONE".equals(rec)) {
            return !now.isBefore(anchor) && now.isBefore(anchor.plus(Duration.ofMinutes(dur)));
        }
        ZonedDateTime anchorZ = anchor.atZone(zone);
        LocalTime tod = anchorZ.toLocalTime();
        LocalDate anchorDate = anchorZ.toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        // bugün + dün (gece-yarısını aşan pencereler için)
        for (LocalDate d : List.of(today.minusDays(1), today)) {
            if (d.isBefore(anchorDate) || !matchesRecurrence(w, rec, d)) continue;
            Instant start = ZonedDateTime.of(d, tod, zone).toInstant();
            if (!now.isBefore(start) && now.isBefore(start.plus(Duration.ofMinutes(dur)))) return true;
        }
        return false;
    }

    private boolean matchesRecurrence(MaintenanceWindow w, String rec, LocalDate d) {
        return switch (rec) {
            case "DAILY"   -> true;
            case "WEEKLY"  -> daysOfWeekSet(w).contains(d.getDayOfWeek().getValue());   // 1=Pzt..7=Paz
            case "MONTHLY" -> {
                int dom = w.getDayOfMonth() == null ? 1 : w.getDayOfMonth();
                yield d.getDayOfMonth() == Math.min(dom, d.lengthOfMonth());
            }
            default -> false;
        };
    }

    /** now'dan sonraki ilk başlangıç anı (UTC ISO); yoksa null. */
    public String nextOccurrence(MaintenanceWindow w, Instant now) {
        Instant anchor = parse(w.getStartAt());
        if (anchor == null) return null;
        String rec = rec(w);
        if ("NONE".equals(rec)) return anchor.isAfter(now) ? ISO.format(anchor) : null;
        ZoneId zone = zoneOf(w);
        ZonedDateTime anchorZ = anchor.atZone(zone);
        LocalTime tod = anchorZ.toLocalTime();
        LocalDate anchorDate = anchorZ.toLocalDate();
        LocalDate d = now.atZone(zone).toLocalDate().minusDays(1);
        for (int i = 0; i < 400; i++, d = d.plusDays(1)) {
            if (d.isBefore(anchorDate) || !matchesRecurrence(w, rec, d)) continue;
            Instant start = ZonedDateTime.of(d, tod, zone).toInstant();
            if (start.isAfter(now)) return ISO.format(start);
        }
        return null;
    }

    /** paused | active | completed | upcoming */
    public String computeStatus(MaintenanceWindow w, Instant now) {
        if (!Boolean.TRUE.equals(w.getActive())) return "paused";
        if (isActiveAt(w, now)) return "active";
        if ("NONE".equals(rec(w))) {
            Instant anchor = parse(w.getStartAt());
            if (anchor != null && now.isAfter(anchor.plus(Duration.ofMinutes(durMin(w))))) return "completed";
        }
        return "upcoming";
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public List<String> targetsOf(MaintenanceWindow w) {
        if (w.getTargetsJson() == null || w.getTargetsJson().isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = MAPPER.readValue(w.getTargetsJson(), List.class);
            List<String> out = new ArrayList<>();
            for (Map<String, Object> t : list) if (t.get("target") != null) out.add(t.get("target").toString());
            return out;
        } catch (Exception e) { return List.of(); }
    }

    /** Atanan monitör sayısı; -1 = tüm monitörler. */
    public int targetCount(MaintenanceWindow w) {
        return Boolean.TRUE.equals(w.getAllMonitors()) ? -1 : targetsOf(w).size();
    }

    private Set<Integer> daysOfWeekSet(MaintenanceWindow w) {
        Set<Integer> s = new HashSet<>();
        if (w.getDaysOfWeek() != null) for (String p : w.getDaysOfWeek().split(",")) {
            try { s.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignore) { /* atla */ }
        }
        return s;
    }

    private static String rec(MaintenanceWindow w) { return w.getRecurrence() == null ? "NONE" : w.getRecurrence(); }
    private static long durMin(MaintenanceWindow w) { return Math.max(1, w.getDurationMinutes() == null ? 60 : w.getDurationMinutes()); }

    private ZoneId zoneOf(MaintenanceWindow w) {
        try { return ZoneId.of(w.getTimezone() == null ? "Europe/Istanbul" : w.getTimezone()); }
        catch (Exception e) { return ZoneId.of("Europe/Istanbul"); }
    }

    /** UTC ISO ("yyyy-MM-ddTHH:mm:ss", zone'suz) → Instant; offset/Z varsa doğrudan Instant.parse. */
    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.trim();
            if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:?\\d\\d$")) return Instant.parse(s);
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) { return null; }
    }
}
