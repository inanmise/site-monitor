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
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.model.Team;
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
    // 2026-09-20 zenginleştirme (kullanıcı bildirimi): takım adı, başlangıç/süre, izlemeye git, geçmiş.
    // Yapıcıya @Lazy/optional eklemek yerine alan enjeksiyonu: mevcut 7-arg yapıcı (test) olduğu gibi kalır.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TeamRepository teamRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MonitorRefResolver monitorRefResolver;
    static final int HISTORY_DAYS = 30;
    static final int HISTORY_SIZE_MAX = 100;

    /**
     * Bildirim satırı. {@code startedAt}/{@code endedAt}: alarmın açıldığı / çözüldüğü an (UTC ISO) — süre arayüzde
     * canlı hesaplanır; {@code teamId}/{@code teamName}: hangi takıma ait; {@code monitorTab}/{@code monitorParams}:
     * "İzlemeye git" derin bağlantısı (alarm sekmesinden ayrı). Eski 8-arg yapıcı korunur (yardımcı alanlar null).
     */
    public record Item(String key, String kind, String level, String title, String sub, String at, String tab, Map<String, Object> params,
                       Long teamId, String teamName, String startedAt, String endedAt, String monitorTab, Map<String, Object> monitorParams, String monitorName) {
        public Item(String key, String kind, String level, String title, String sub, String at, String tab, Map<String, Object> params) {
            this(key, kind, level, title, sub, at, tab, params, null, null, null, null, null, null, null);
        }
        Item withTeam(Long teamId, String teamName) {
            return new Item(key, kind, level, title, sub, at, tab, params, teamId, teamName, startedAt, endedAt, monitorTab, monitorParams, monitorName);
        }
    }

    /** Geçmiş sayfası: çözülmüş alarmlar (son {@value #HISTORY_DAYS} gün), çözülme zamanına göre yeniden eskiye. */
    public record HistoryPage(List<Item> items, long total, int page, int size) {
        public int totalPages() { return size <= 0 ? 0 : (int) Math.ceil(total / (double) size); }
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        if (teamRepo == null) return m;
        try { for (Team t : teamRepo.findAll()) if (t.getId() != null) m.put(t.getId(), t.getName()); } catch (Exception e) { log.debug("inbox: takımlar okunamadı: {}", e.toString()); }
        return m;
    }

    private Map<Long, MonitorRefResolver.Ref> monitorRefs(List<AlertEvent> events) {
        if (monitorRefResolver == null || events.isEmpty()) return Map.of();
        try { return monitorRefResolver.resolve(events); } catch (Exception e) { log.debug("inbox: izleme çözümü düştü: {}", e.toString()); return Map.of(); }
    }

    /** Alarm olayı → satır (açık ya da çözülmüş); takım, başlangıç/bitiş ve izleme bağlantısıyla. */
    private Item alertItem(AlertEvent e, boolean resolved, Map<Long, String> teamNames, Map<Long, MonitorRefResolver.Ref> refs) {
        String title = e.getDomain() != null ? e.getDomain() : String.valueOf(e.getAlertType());
        MonitorRefResolver.Ref ref = refs.get(e.getId());
        String key = resolved ? "resolved:" + e.getId() + ":" + e.getResolvedAt() : "alert:" + e.getId();
        return new Item(key, resolved ? "alert_resolved" : "alert_open", resolved ? "OK" : e.getAlertLevel(), title, e.getAlertType(),
                resolved ? e.getResolvedAt() : e.getCreatedAt(), "alerthistory", alertParams(e, resolved),
                e.getTeamId(), e.getTeamId() != null ? teamNames.get(e.getTeamId()) : null,
                e.getCreatedAt(), resolved ? e.getResolvedAt() : null,
                ref != null ? ref.tab() : null, ref != null ? MonitorRefResolver.paramsFor(ref, e.getDomain()) : null,
                ref != null ? ref.name() : null);
    }

    /**
     * Geçmiş (2026-09-20): son {@value #HISTORY_DAYS} günde çözülen alarmlar, sayfalı — bildirim kutusunda geriye
     * dönük bakış (güncel liste yalnız son 24 saati gösterir).
     */
    public HistoryPage history(Predicate<Long> canViewTeam, int page, int size) {
        int p = Math.max(0, page), s = Math.max(1, Math.min(size, HISTORY_SIZE_MAX));
        Instant now = Instant.now();
        Set<String> domains = new HashSet<>();
        try { for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) if (canViewTeam.test(i.getTeamId())) domains.add(i.getDomain()); }
        catch (Exception e) { log.debug("inbox: envanter okunamadı: {}", e.toString()); }
        List<AlertEvent> resolvedAll = new ArrayList<>();
        try {
            String since = ISO.format(now.minus(HISTORY_DAYS, ChronoUnit.DAYS));
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(ISO.format(now.minus(HISTORY_DAYS + 30L, ChronoUnit.DAYS)))) {
                if (!Boolean.TRUE.equals(e.getResolved()) || e.getResolvedAt() == null || e.getResolvedAt().compareTo(since) < 0) continue;
                if (!visible(e, canViewTeam, domains)) continue;
                resolvedAll.add(e);
            }
        } catch (Exception ex) { log.debug("inbox: geçmiş düştü: {}", ex.toString()); }
        resolvedAll.sort((a, b) -> String.valueOf(b.getResolvedAt()).compareTo(String.valueOf(a.getResolvedAt())));
        int from = Math.min(p * s, resolvedAll.size()), to = Math.min(from + s, resolvedAll.size());
        List<AlertEvent> slice = resolvedAll.subList(from, to);
        Map<Long, String> teamNames = teamNames();
        Map<Long, MonitorRefResolver.Ref> refs = monitorRefs(slice);
        List<Item> items = new ArrayList<>();
        for (AlertEvent e : slice) items.add(alertItem(e, true, teamNames, refs));
        return new HistoryPage(items, resolvedAll.size(), p, s);
    }

    public List<Item> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds) {
        List<Item> out = new ArrayList<>();
        Instant now = Instant.now();
        Set<String> domains = new HashSet<>();
        try { for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) if (canViewTeam.test(i.getTeamId())) domains.add(i.getDomain()); }
        catch (Exception e) { log.debug("inbox: envanter okunamadı: {}", e.toString()); }

        Map<Long, String> teamNames = teamNames();
        List<AlertEvent> alertRows = new ArrayList<>();
        // Açık alarmlar
        try {
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (!visible(e, canViewTeam, domains)) continue;
                // Hedef: Alarm Geçmişi (alerthistory) — "Uyarılar" SERTİFİKA uyarıları sayfasıdır ve
                // alarm olayını tanımaz; bildirim oraya gidince kullanıcı tıkladığı alarmı bulamıyordu
                // (2026-09-16 kullanıcı bildirimi). Artık: açık sekmesi + tipe süzme + olay kimliği
                // (arayüz grubu açar, kartı vurgular ve kaydırır).
                alertRows.add(e);
            }
        } catch (Exception ex) { log.debug("inbox: açık alarmlar düştü: {}", ex.toString()); }
        List<AlertEvent> resolvedRows = new ArrayList<>();
        // Son 24 saatte çözülenler
        try {
            String since = ISO.format(now.minus(24, ChronoUnit.HOURS));
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(ISO.format(now.minus(30, ChronoUnit.DAYS)))) {
                if (!Boolean.TRUE.equals(e.getResolved()) || e.getResolvedAt() == null || e.getResolvedAt().compareTo(since) < 0) continue;
                if (!visible(e, canViewTeam, domains)) continue;
                resolvedRows.add(e);
            }
        } catch (Exception ex) { log.debug("inbox: çözülenler düştü: {}", ex.toString()); }
        // Takım adı + izleme bağlantısı TEK geçişte (2026-09-20)
        List<AlertEvent> all = new ArrayList<>(alertRows); all.addAll(resolvedRows);
        Map<Long, MonitorRefResolver.Ref> refs = monitorRefs(all);
        for (AlertEvent e : alertRows) out.add(alertItem(e, false, teamNames, refs));
        for (AlertEvent e : resolvedRows) out.add(alertItem(e, true, teamNames, refs));

        // Bakım pencereleri: aktif ya da 24 saat içinde başlayacak
        try {
            Instant horizon = now.plus(24, ChronoUnit.HOURS);
            for (MaintenanceWindow w : maintenanceRepo.findByActiveTrue()) {
                if (w.getTeamId() != null && !canViewTeam.test(w.getTeamId())) continue;
                if (maintenanceService.isActiveAt(w, now)) {
                    out.add(new Item("maint:" + w.getId() + ":active", "maintenance_active", "INFO", w.getName(), null, w.getStartAt(), "maintenance", Map.of("window", w.getId()))
                            .withTeam(w.getTeamId(), w.getTeamId() != null ? teamNames.get(w.getTeamId()) : null));
                    continue;
                }
                String next = maintenanceService.nextOccurrence(w, now);
                if (next == null) continue;
                Instant n = Instant.parse(next.endsWith("Z") ? next : next + "Z");
                if (n.isBefore(horizon))
                    out.add(new Item("maint:" + w.getId() + ":" + next, "maintenance_soon", "INFO", w.getName(), null, next, "maintenance", Map.of("window", w.getId()))
                            .withTeam(w.getTeamId(), w.getTeamId() != null ? teamNames.get(w.getTeamId()) : null));
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
                                today + "T" + d.timeText() + ":00", "weeklyreports", Map.of("team", tid, "w_week", week))   // w_week: sayfa o haftaya süzer (2026-09-13)
                                .withTeam(tid, teamNames.get(tid)));
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

    /**
     * Alarm Geçmişi derin bağlantısı (2026-09-16): {@code alert} olay kimliği (kart vurgusu + grubun
     * açılması), {@code type} tip süzgeci, {@code q} alan adı araması, çözülenlerde {@code view=closed}.
     * Alan adı yoksa arama konmaz — boş arama tüm listeyi süzerdi.
     */
    private static Map<String, Object> alertParams(AlertEvent e, boolean resolved) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("alert", e.getId());
        if (e.getAlertType() != null) p.put("type", e.getAlertType());
        if (e.getDomain() != null && !e.getDomain().isBlank()) p.put("q", e.getDomain());
        if (resolved) p.put("view", "closed");
        return p;
    }

    private static int rank(Item i) {
        return switch (i.kind()) { case "alert_open" -> "CRITICAL".equalsIgnoreCase(i.level()) ? 5 : 4; case "weekly_due" -> 3; case "maintenance_active" -> 2; case "exception_expired" -> 2; case "maintenance_soon" -> 1; default -> 0; };
    }

    private static boolean visible(AlertEvent e, Predicate<Long> canViewTeam, Set<String> domains) {
        return (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
    }
}
