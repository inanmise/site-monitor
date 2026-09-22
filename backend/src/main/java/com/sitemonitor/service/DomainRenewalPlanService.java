package com.sitemonitor.service;

import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainMonitorRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alan adı yenileme planı (2026-09-22, denetim madde H) — sertifikadaki "planlanan yenileme" (ForecastController) eşi.
 *
 * <p>Operatör "bu tarihte yenileyeceğiz" der (tarih + not + kim). Plan tarihi geçtiği hâlde bitiş ileri gitmemişse
 * kart "gecikmiş" rozeti gösterir. Gerçek yenileme görülünce — kontrolün döndürdüğü bitiş, plan yapıldığı andaki
 * bitişten ({@code renewalPlannedExpiry}) SONRAYSA — plan sunucu tarafında kendiliğinden kapanır ve değişiklik
 * geçmişine "yenileme tespit edildi" satırı düşer. Kıyas önceki kontrol satırına değil plan anındaki bitişe yapılır:
 * araya giren UNKNOWN turları ya da aynı bitişi tekrar eden kontroller planı yanlışlıkla kapatmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainRenewalPlanService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DomainMonitorRepository repo;
    private final MonitorHistoryService monitorHistory;
    private final ActivityLogService activityLog;

    /** Plan koy/güncelle. {@code date} YYYY-MM-DD; {@code currentExpiry} kartın bildiği bitiş (kıyas tabanı, null olabilir). */
    public DomainMonitor plan(DomainMonitor m, String date, String note, String currentExpiry, HttpSession session) {
        if (date == null || !date.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new IllegalArgumentException("date: YYYY-MM-DD expected");
        String n = note == null ? null : note.trim();
        if (n != null && n.length() > 500) n = n.substring(0, 500);
        Map<String, Object> before = snapshot(m);
        m.setRenewalPlannedAt(date);
        m.setRenewalPlannedNote(n == null || n.isBlank() ? null : n);
        m.setRenewalPlannedBy(str(session, "username"));
        String dn = str(session, "fullName");
        m.setRenewalPlannedByName(dn != null ? dn : str(session, "username"));
        m.setRenewalPlannedExpiry(currentExpiry);
        m.setUpdatedAt(ISO.format(Instant.now()));
        DomainMonitor saved = repo.save(m);
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(saved), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                "RENEWAL_PLANNED", str(session, "username"), "plan " + date + (n == null || n.isBlank() ? "" : " · " + n));
        return saved;
    }

    public DomainMonitor unplan(DomainMonitor m, HttpSession session) {
        Map<String, Object> before = snapshot(m);
        clear(m);
        DomainMonitor saved = repo.save(m);
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(saved), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                "RENEWAL_PLAN_CLEARED", str(session, "username"));
        return saved;
    }

    /**
     * Kontrol sonucu: yenileme tespit edilirse (bitiş, plan anındaki bitişten sonra) planı kapatır.
     * Hata sweep'i kırmaz. @return plan kapatıldıysa true
     */
    public boolean onCheckResult(DomainMonitor m, Map<String, Object> result) {
        try {
            if (m == null || m.getId() == null || m.getRenewalPlannedAt() == null) return false;
            String expiry = result != null && result.get("expiry_date") instanceof String s ? s : null;
            if (expiry == null || expiry.isBlank()) return false;
            String base = m.getRenewalPlannedExpiry();
            if (base == null || base.isBlank()) return false;   // kıyas tabanı yok (plan bitiş bilinmeden konmuş) → elle kapatılır
            if (expiry.compareTo(base) <= 0) return false;      // ISO sözlük sırası = zaman sırası (aynı biçim)
            Map<String, Object> before = snapshot(m);
            String plannedAt = m.getRenewalPlannedAt();
            clear(m);
            DomainMonitor saved = repo.save(m);
            monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, before, snapshot(saved),
                    "renewal-detected: bitiş " + base + " → " + expiry + " (plan " + plannedAt + " kapatıldı)", null);
            activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                    "RENEWAL_DETECTED", "scheduler", "bitiş " + base + " → " + expiry + " · plan " + plannedAt + " kapatıldı");
            log.info("Alan adı yenilemesi tespit edildi: {} {} → {} — plan {} kapatıldı", saved.getDomain(), base, expiry, plannedAt);
            return true;
        } catch (Exception e) {
            log.warn("Yenileme planı değerlendirilemedi: {} — {}", m != null ? m.getDomain() : "?", e.getMessage());
            return false;
        }
    }

    private static void clear(DomainMonitor m) {
        m.setRenewalPlannedAt(null); m.setRenewalPlannedBy(null); m.setRenewalPlannedByName(null);
        m.setRenewalPlannedNote(null); m.setRenewalPlannedExpiry(null);
        m.setUpdatedAt(ISO.format(Instant.now()));
    }

    public static Map<String, Object> snapshot(DomainMonitor m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("renewalPlannedAt", m.getRenewalPlannedAt());
        s.put("renewalPlannedNote", m.getRenewalPlannedNote());
        s.put("renewalPlannedBy", m.getRenewalPlannedByName());
        return s;
    }

    private static String str(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v != null ? v.toString() : null;
    }
}
