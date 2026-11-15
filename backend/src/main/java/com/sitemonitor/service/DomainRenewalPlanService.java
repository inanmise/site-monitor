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
 *
 * <p><b>Hedefli yazım (2026-11-15, M2):</b> {@code DomainMonitor}'da {@code @DynamicUpdate} / {@code @Version} yok — varlığın
 * tamamını kaydetmek, okumadan sonra (yavaş RDAP / WHOIS sorgusu sırasında) yapılmış bir düzenlemeyi (eşik, alan adı, grup …)
 * eski değeriyle EZERDİ. Plan yazımları yalnız plan kolonlarına + {@code updated_at}'e dokunur; kaldırma koşulludur (okunan plan
 * hâlâ yerindeyse — tarih, kıyas tabanı ve not aynı). 0 satır = arada değişti → geçmiş / etkinlik / günlük satırı YAZILMAZ.
 * Çağıranlar AYRIK (detached) varlık geçirir (denetleyici ve tur işlem dışında okur); başarılı yazımdan sonra varlığın bellek
 * kopyası güncellenir ki yanıt yeni durumu göstersin.
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

    /**
     * Plan koy/güncelle. {@code date} geçerli bir takvim günü (YYYY-MM-DD; "2026-02-31" reddedilir); {@code currentExpiry} kartın
     * bildiği bitiş (kıyas tabanı, null olabilir). Yalnız plan kolonları yazılır; izleme silinmişse {@link java.util.NoSuchElementException}.
     */
    public DomainMonitor plan(DomainMonitor m, String date, String note, String currentExpiry, HttpSession session) {
        if (!validDay(date)) throw new IllegalArgumentException("date: YYYY-MM-DD expected");
        String n = note == null ? null : note.trim();
        if (n != null && n.length() > 500) n = n.substring(0, 500);
        Map<String, Object> before = snapshot(m);
        String by = str(session, "username");
        String dn = str(session, "displayName");
        String byName = dn != null ? dn : by;
        String nn = n == null || n.isBlank() ? null : n;
        String now = ISO.format(Instant.now());
        if (repo.writeRenewalPlan(m.getId(), date, by, byName, nn, currentExpiry, now) == 0)
            throw new java.util.NoSuchElementException("Domain monitor not found");
        m.setRenewalPlannedAt(date);
        m.setRenewalPlannedNote(nn);
        m.setRenewalPlannedBy(by);
        m.setRenewalPlannedByName(byName);
        m.setRenewalPlannedExpiry(currentExpiry);
        m.setUpdatedAt(now);
        DomainMonitor saved = m;
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(saved), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                "RENEWAL_PLANNED", str(session, "username"), "plan " + date + (n == null || n.isBlank() ? "" : " · " + n));
        return saved;
    }

    /**
     * Planı kaldır — koşullu: okunan plan hâlâ yerindeyse. Plan yoksa ya da arada değiştiyse hiçbir şey yazılmaz ve varlık olduğu gibi
     * döner ({@code renewalPlannedAt} doluysa çağıran "arada değişti" der).
     */
    public DomainMonitor unplan(DomainMonitor m, HttpSession session) {
        if (m.getRenewalPlannedAt() == null) return m;
        Map<String, Object> before = snapshot(m);
        if (!clearIfUnchanged(m)) return m;
        DomainMonitor saved = m;
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(saved), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                "RENEWAL_PLAN_CLEARED", str(session, "username"));
        return saved;
    }

    /**
     * A3 (2026-11-15): izlemenin alan adı değişti — plan ESKİ alan adına konmuştu (kıyas tabanı da onun bitişiydi), yeni alan adı
     * için anlamsız. Elle kaldırma gibi: geçmiş satırı ("alan adı değişti") + etkinlik. Plan yoksa hiçbir şey yazılmaz.
     *
     * @return kaldırılan planın tarihi; plan yoksa {@code null}
     */
    public String clearForDomainChange(DomainMonitor m, String oldDomain, HttpSession session) {
        if (m == null || m.getRenewalPlannedAt() == null) return null;
        String was = m.getRenewalPlannedAt();
        Map<String, Object> before = snapshot(m);
        if (!clearIfUnchanged(m)) return null;   // arada değişti (elle kaldırıldı / yeniden kondu) — iz yazılmaz
        DomainMonitor saved = m;
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(saved),
                "renewal-plan: alan adı değişti (" + oldDomain + " → " + saved.getDomain() + "), plan " + was + " kaldırıldı", session);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(), saved.getDomain(), saved.getTeamId(),
                "RENEWAL_PLAN_CLEARED", str(session, "username"), "alan adı değişti · plan " + was);
        return was;
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
            // Bitiş GÜNÜ karşılaştırılır (2026-11-15): taban WHOIS'ten "2026-12-01", kontrol RDAP'tan "2026-12-01T04:00:00Z"
            // gelince ham metin sözlük sırasında "sonra" sayılıyor, AYNI bitiş "yenileme tespit edildi" diye planı kapatıyordu.
            String day = DomainNames.expiryDay(expiry), baseDay = DomainNames.expiryDay(base);
            if (day.compareTo(baseDay) <= 0) return false;      // ISO gün sözlük sırası = zaman sırası
            Map<String, Object> before = snapshot(m);
            String plannedAt = m.getRenewalPlannedAt();
            // Koşullu: okuma ile tespit arasında (yavaş RDAP / WHOIS) plan elle değiştiyse / kaldırıldıysa dokunulmaz, iz yazılmaz
            if (!clearIfUnchanged(m)) return false;
            DomainMonitor saved = m;
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

    /**
     * Plan kolonlarını koşullu temizler (tarih + kıyas tabanı + not okunduğu gibi) — 1 satır: bellek kopyası da temizlenir.
     * Boş değerler {@code ""} ile karşılaştırılır (sorgu {@code COALESCE}'li; null parametre yok).
     */
    private boolean clearIfUnchanged(DomainMonitor m) {
        String now = ISO.format(Instant.now());
        int n = repo.clearRenewalPlanIfUnchanged(m.getId(), m.getRenewalPlannedAt(),
                nz(m.getRenewalPlannedExpiry()), nz(m.getRenewalPlannedNote()), now);
        if (n == 0) return false;
        m.setRenewalPlannedAt(null); m.setRenewalPlannedBy(null); m.setRenewalPlannedByName(null);
        m.setRenewalPlannedNote(null); m.setRenewalPlannedExpiry(null);
        m.setUpdatedAt(now);
        return true;
    }

    private static String nz(String v) { return v == null ? "" : v; }

    /** Geçerli takvim günü mü (YYYY-MM-DD, {@code LocalDate.parse} — "2026-02-31" geçersiz). Tek kural: tekil + toplu uç. */
    public static boolean validDay(String date) {
        if (date == null || !date.matches("^\\d{4}-\\d{2}-\\d{2}$")) return false;
        try { java.time.LocalDate.parse(date); return true; } catch (java.time.format.DateTimeParseException e) { return false; }
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
