package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Predicate;

/**
 * Bildirim kutusu (2026-09-12, zenginleştirme #2): dört ayrı sekmeye dağılan "haber"leri tek listede toplar —
 * açık alarmlar, son 24 saatte çözülenler, aktif / 24 saat içinde başlayacak bakım pencereleri, bugün son
 * giriş günüyse eksik haftalık rapor, süresi dolan zayıf-algoritma istisnası. Okundu durumu İSTEMCİDE
 * (localStorage, anahtar = {@code key}); sunucu yalnız kararlı anahtar üretir. Kapsam çağıranın predicate'i.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboxService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int MAX_ITEMS = 60;

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final MaintenanceWindowRepository maintenanceRepo;
    private final MaintenanceService maintenanceService;
    private final WeakAlgorithmExceptionRepository exceptionRepo;
    private final WeeklyReportRepository weeklyReportRepo;
    private final AppSettingsService appSettings;

    public record Item(String key, String kind, String level, String title, String sub, String at, String tab, Map<String, Object> params) {}

    public List<Item> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds) {
        List<Item> out = new ArrayList<>();
        Instant now = Instant.now();
        Set<String> domains = new HashSet<>();
        try { for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) if (canViewTeam.test(i.getTeamId())) domains.add(i.getDomain()); }
        catch (Exception e) { log.debug("inbox: envanter okunamadı: {}", e.toString()); }

        // Açık alarmlar
        try {
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (!visible(e, canViewTeam, domains)) continue;
                out.add(new Item("alert:" + e.getId(), "alert_open", e.getAlertLevel(), e.getDomain() != null ? e.getDomain() : String.valueOf(e.getAlertType()),
                        e.getAlertType(), e.getCreatedAt(), "warnings", Map.of("incident", e.getId())));
            }
        } catch (Exception ex) { log.debug("inbox: açık alarmlar düştü: {}", ex.toString()); }

        // Son 24 saatte çözülenler
        try {
            String since = ISO.format(now.minus(24, ChronoUnit.HOURS));
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(ISO.format(now.minus(30, ChronoUnit.DAYS)))) {
                if (!Boolean.TRUE.equals(e.getResolved()) || e.getResolvedAt() == null || e.getResolvedAt().compareTo(since) < 0) continue;
                if (!visible(e, canViewTeam, domains)) continue;
                out.add(new Item("resolved:" + e.getId() + ":" + e.getResolvedAt(), "alert_resolved", "OK", e.getDomain() != null ? e.getDomain() : String.valueOf(e.getAlertType()),
                        e.getAlertType(), e.getResolvedAt(), "alerthistory", Map.of("incident", e.getId())));
            }
        } catch (Exception ex) { log.debug("inbox: çözülenler düştü: {}", ex.toString()); }

        // Bakım pencereleri: aktif ya da 24 saat içinde başlayacak
        try {
            Instant horizon = now.plus(24, ChronoUnit.HOURS);
            for (MaintenanceWindow w : maintenanceRepo.findByActiveTrue()) {
                if (w.getTeamId() != null && !canViewTeam.test(w.getTeamId())) continue;
                if (maintenanceService.isActiveAt(w, now)) {
                    out.add(new Item("maint:" + w.getId() + ":active", "maintenance_active", "INFO", w.getName(), null, w.getStartAt(), "maintenance", Map.of("window", w.getId())));
                    continue;
                }
                String next = maintenanceService.nextOccurrence(w, now);
                if (next == null) continue;
                Instant n = Instant.parse(next.endsWith("Z") ? next : next + "Z");
                if (n.isBefore(horizon))
                    out.add(new Item("maint:" + w.getId() + ":" + next, "maintenance_soon", "INFO", w.getName(), null, next, "maintenance", Map.of("window", w.getId())));
            }
        } catch (Exception ex) { log.debug("inbox: bakım pencereleri düştü: {}", ex.toString()); }

        // Haftalık rapor: bugün son giriş günü ve kendi takımının raporu eksikse
        try {
            WeeklyReportDeadline d = WeeklyReportDeadline.resolve(appSettings);
            LocalDate today = LocalDate.now(IST);
            if (today.getDayOfWeek() == d.day() && ownTeamIds != null) {
                int year = today.get(WeekFields.ISO.weekBasedYear()), week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
                for (Long tid : ownTeamIds) {
                    if (tid == null) continue;
                    String status = weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(tid, year, week).map(WeeklyReport::getStatus).orElse("MISSING");
                    if ("MISSING".equals(status) || "DRAFT".equals(status) || "REJECTED".equals(status))
                        out.add(new Item("weekly:" + tid + ":" + year + "-" + week, "weekly_due", "WARNING", "W" + week, status + " · " + d.timeText(),
                                today + "T" + d.timeText() + ":00", "weeklyreports", Map.of("team", tid)));
                }
            }
        } catch (Exception ex) { log.debug("inbox: haftalık rapor düştü: {}", ex.toString()); }

        // Süresi dolan istisnalar
        try {
            String today = LocalDate.now(IST).toString();
            for (WeakAlgorithmException ex : exceptionRepo.findAll()) {
                if (ex.getDomain() == null || !domains.contains(ex.getDomain()) || ex.getUntil() == null || ex.getUntil().compareTo(today) >= 0) continue;
                out.add(new Item("exception:" + ex.getDomain() + ":" + ex.getUntil(), "exception_expired", "WARNING", ex.getDomain(), ex.getReason(), ex.getUntil() + "T00:00:00", "weakalgo", Map.of()));
            }
        } catch (Exception ex) { log.debug("inbox: istisnalar düştü: {}", ex.toString()); }

        out.sort((a, b) -> {
            int c = Integer.compare(rank(b), rank(a));
            if (c != 0) return c;
            return String.valueOf(b.at()).compareTo(String.valueOf(a.at()));
        });
        return out.size() > MAX_ITEMS ? out.subList(0, MAX_ITEMS) : out;
    }

    private static int rank(Item i) {
        return switch (i.kind()) { case "alert_open" -> "CRITICAL".equalsIgnoreCase(i.level()) ? 5 : 4; case "weekly_due" -> 3; case "maintenance_active" -> 2; case "exception_expired" -> 2; case "maintenance_soon" -> 1; default -> 0; };
    }

    private static boolean visible(AlertEvent e, Predicate<Long> canViewTeam, Set<String> domains) {
        return (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
    }
}
