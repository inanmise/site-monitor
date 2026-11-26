package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Sertifika yenileme planı (2026-11-26, H paketi sertifika B4 / B5 / B7) — alan adı planının ({@link DomainRenewalPlanService})
 * sertifika eşi. Vade Takvimi ucu ({@code ForecastController}) ve kontrol hattı ({@link CertificateService#saveResult}) bunu
 * kullanır.
 *
 * <ul>
 *   <li><b>B4:</b> plan günü GERÇEK takvim günü olmalı ({@code LocalDate.parse}; "2026-02-31" → 400 alan {@code date}); not
 *       {@value #NOTE_MAX} karakteri aşarsa 400 alan {@code note} — eskiden sessizce kesiliyordu.</li>
 *   <li><b>B7:</b> yazımlar HEDEFLİ (yalnız plan kolonları); kaldırma KOŞULLU (okunan plan hâlâ yerindeyse). Varlığın bütünü
 *       kaydedilmez — plan kolonları entity'de {@code updatable = false}.</li>
 *   <li><b>B5:</b> plan TABANI = plan anında sunulan sertifika (parmak izi + bitiş). "Tamamlandı" = tabandan FARKLI ve DAHA UZUN
 *       geçerli bir sertifika görüldü (plan gününden önceki erken yenileme dahil). Tabanı olmayan eski planda eski kural
 *       (yeni sertifikanın verilme GÜNÜ ≥ plan günü; İstanbul günü). Kontrol tamamlanmış planı kendiliğinden kapatır
 *       (koşullu yazım + değişiklik geçmişi + aktivite). Kart ve Vade Takvimi aynı kuralı ({@link #isDone}) kullanır.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertRenewalPlanService {

    static final int NOTE_MAX = 500;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestRepo;
    private final MonitorHistoryService monitorHistory;
    private final ActivityLogService activityLog;

    /**
     * Plan koy / güncelle. Doğrulama hataları alan adlı 400 ({@link GlobalExceptionHandler.FieldValidationException}); kayıt
     * arada silindiyse {@link NoSuchElementException}. Varlığın bellek kopyası güncellenir (yanıt yeni durumu gösterir).
     */
    public CertificateInventory plan(CertificateInventory inv, Object rawDate, Object rawNote, HttpSession session) {
        String date = rawDate == null ? "" : String.valueOf(rawDate).trim();
        if (!DomainRenewalPlanService.validDay(date)) {
            throw new GlobalExceptionHandler.FieldValidationException("date", Msg.t(
                    "Plan tarihi geçerli bir takvim günü olmalı (YYYY-AA-GG; ör. 2026-12-01).",
                    "The planned date must be a real calendar day (YYYY-MM-DD; e.g. 2026-12-01)."));
        }
        String note = rawNote == null ? null : String.valueOf(rawNote).trim();
        if (note != null && note.length() > NOTE_MAX) {
            throw new GlobalExceptionHandler.FieldValidationException("note", Msg.t(
                    "Not en çok " + NOTE_MAX + " karakter olabilir (şu an " + note.length() + "). Kısaltıp yeniden kaydedin.",
                    "The note can be at most " + NOTE_MAX + " characters (now " + note.length() + "). Shorten it and save again."));
        }
        String nn = note == null || note.isBlank() ? null : note;
        // Plan TABANI: şu an sunulan sertifika. Kontrol yoksa / parmak izi okunamadıysa taban boş → eski kural.
        LatestCheck lc = inv.getDomain() == null ? null : latestRepo.findById(inv.getDomain()).orElse(null);
        String baseFp = lc != null && notBlank(lc.getFingerprint()) ? lc.getFingerprint() : null;
        String baseNotAfter = baseFp != null && notBlank(lc.getNotAfter()) ? lc.getNotAfter() : null;
        Map<String, Object> before = snapshot(inv);
        String by = str(session, "username");
        String dn = str(session, "displayName");
        String byName = dn != null ? dn : by;
        String now = ISO.format(Instant.now());
        if (inventoryRepo.writeRenewalPlan(inv.getId(), date, by, byName, nn, baseFp, baseNotAfter, now) == 0) {
            throw new NoSuchElementException(Msg.t("Envanter kaydı bulunamadı", "Inventory record not found"));
        }
        inv.setRenewalPlannedAt(date);
        inv.setRenewalPlannedBy(by);
        inv.setRenewalPlannedByName(byName);
        inv.setRenewalPlannedNote(nn);
        inv.setRenewalPlannedFingerprint(baseFp);
        inv.setRenewalPlannedNotAfter(baseNotAfter);
        inv.setRenewalPlannedRecordedAt(now);   // bu sürümün planı: tabansızsa eski kural uygulanmaz (taban benimsenir)
        inv.setUpdatedAt(now);
        inv.setUpdatedBy(by);
        inv.setUpdatedByName(byName);
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(inv), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.CERT, inv.getId(), inv.getDomain(), inv.getDomain(), inv.getTeamId(),
                "RENEWAL_PLANNED", by, "plan " + date + (nn == null ? "" : " · " + nn));
        return inv;
    }

    /**
     * Planı kaldır — KOŞULLU: okunan plan hâlâ yerindeyse. Plan yoksa ya da arada değiştiyse hiçbir şey yazılmaz, false döner
     * (çağıran "arada değişti" der; varlık olduğu gibi kalır).
     */
    public boolean unplan(CertificateInventory inv, HttpSession session) {
        if (inv == null || inv.getRenewalPlannedAt() == null) return false;
        Map<String, Object> before = snapshot(inv);
        String was = inv.getRenewalPlannedAt();
        String by = str(session, "username");
        String dn = str(session, "displayName");
        String byName = dn != null ? dn : by;
        String now = ISO.format(Instant.now());
        if (inventoryRepo.clearRenewalPlanIfUnchanged(inv.getId(), was, nz(inv.getRenewalPlannedNote()),
                nz(inv.getRenewalPlannedFingerprint()), now, by, byName) == 0) return false;
        clearInMemory(inv);
        inv.setUpdatedAt(now);
        inv.setUpdatedBy(by);
        inv.setUpdatedByName(byName);
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(inv), "renewal-plan", session);
        activityLog.recordLifecycle(ActivityLogService.CERT, inv.getId(), inv.getDomain(), inv.getDomain(), inv.getTeamId(),
                "RENEWAL_PLAN_CLEARED", by, "plan " + was);
        return true;
    }

    /**
     * Kontrol sonucu: plan tamamlandıysa ({@link #isDone}) planı kendiliğinden kapatır — koşullu yazım (okuma ile tespit
     * arasında plan elle değiştiyse dokunulmaz, iz yazılmaz) + değişiklik geçmişi ("Sistem") + aktivite. Hata taramayı kırmaz.
     *
     * @return plan kapatıldıysa true
     */
    public boolean onCheckResult(CertificateInventory inv, String servedFp, String notBefore, String notAfter) {
        try {
            if (inv == null || inv.getId() == null || inv.getRenewalPlannedAt() == null) return false;
            // Tabansız YENİ plan (bağımsız inceleme, H paketi): ilk görülen sertifika taban olur — plan kapanmaz.
            if (notBlank(inv.getRenewalPlannedRecordedAt()) && !notBlank(inv.getRenewalPlannedFingerprint())) {
                if (notBlank(servedFp) && inventoryRepo.adoptRenewalPlanBase(inv.getId(), inv.getRenewalPlannedAt(),
                        nz(inv.getRenewalPlannedNote()), servedFp, notBlank(notAfter) ? notAfter : null) > 0) {
                    inv.setRenewalPlannedFingerprint(servedFp);
                    inv.setRenewalPlannedNotAfter(notBlank(notAfter) ? notAfter : null);
                    log.info("Sertifika yenileme planı tabanı benimsendi: {} — plan {} (plan anında sertifika okunamamıştı)",
                            inv.getDomain(), inv.getRenewalPlannedAt());
                }
                return false;
            }
            if (!isDone(inv.getRenewalPlannedAt(), inv.getRenewalPlannedRecordedAt(), inv.getRenewalPlannedFingerprint(),
                    inv.getRenewalPlannedNotAfter(), servedFp, notBefore, notAfter)) return false;
            Map<String, Object> before = snapshot(inv);
            String plannedAt = inv.getRenewalPlannedAt();
            String baseNotAfter = inv.getRenewalPlannedNotAfter();
            if (inventoryRepo.closeRenewalPlanIfUnchanged(inv.getId(), plannedAt, nz(inv.getRenewalPlannedNote()),
                    nz(inv.getRenewalPlannedFingerprint())) == 0) return false;
            clearInMemory(inv);
            String what = baseNotAfter != null ? "bitiş " + baseNotAfter + " → " + notAfter : "yeni sertifika (verilme " + notBefore + ")";
            monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                    MonitorHistoryService.UPDATE, before, snapshot(inv),
                    "renewal-detected: " + what + " (plan " + plannedAt + " kapatıldı)", null);
            activityLog.recordLifecycle(ActivityLogService.CERT, inv.getId(), inv.getDomain(), inv.getDomain(), inv.getTeamId(),
                    "RENEWAL_DETECTED", "scheduler", what + " · plan " + plannedAt + " kapatıldı");
            log.info("Sertifika yenilemesi tespit edildi: {} — plan {} kapatıldı ({})", inv.getDomain(), plannedAt, what);
            return true;
        } catch (Exception e) {
            log.warn("Sertifika yenileme planı değerlendirilemedi: {} — {}", inv != null ? inv.getDomain() : "?", e.getMessage());
            return false;
        }
    }

    /**
     * Plan tamamlandı mı — TEK kural (kart, Vade Takvimi, kontrol hattı).
     * <ul>
     *   <li>Taban (parmak izi + bitiş) varsa: sunulan sertifika tabandan FARKLI ve bitişi tabandan SONRA (daha uzun geçerli).
     *       Plan gününden önceki erken yenileme de tamamlanmış sayılır (eskiden plan günü geçince "gecikti" deniyordu).</li>
     *   <li>Taban yoksa (eski plan / plan anında kontrol yoktu): eski kural — sunulan sertifikanın verilme GÜNÜ (İstanbul) ≥ plan
     *       günü. Kart eskiden UTC gününü, takvim İstanbul gününü kullanıyordu; ikisi de artık İstanbul günü.</li>
     * </ul>
     */
    public static boolean isDone(String plannedAt, String baseFp, String baseNotAfter,
                                 String servedFp, String servedNotBefore, String servedNotAfter) {
        return isDone(plannedAt, null, baseFp, baseNotAfter, servedFp, servedNotBefore, servedNotAfter);
    }

    /**
     * @param recordedAt plan bu sürümle yazıldıysa yazılma anı ({@code renewal_planned_recorded_at}); null = eski sürümün
     *                   planı. Bağımsız inceleme (H paketi): eski kural ("verilme günü ≥ plan günü") YALNIZ eski planlara —
     *                   tabansız YENİ plan bu kuralla kapanmaz (geçmiş tarihli yeni plan ilk kontrolde kendiliğinden
     *                   kapanıyordu); taban ilk kontrolde benimsenir ({@link #onCheckResult}). Parmak izi olan ama bitişi
     *                   okunamamış tabanda tabandan FARKLI sertifika yeter.
     */
    public static boolean isDone(String plannedAt, String recordedAt, String baseFp, String baseNotAfter,
                                 String servedFp, String servedNotBefore, String servedNotAfter) {
        if (plannedAt == null || plannedAt.isBlank()) return false;
        if (notBlank(baseFp) && notBlank(baseNotAfter)) {
            if (!notBlank(servedFp) || servedFp.trim().equalsIgnoreCase(baseFp.trim())) return false;
            Instant base = RenewalForecastService.parseUtc(baseNotAfter);
            Instant cur = RenewalForecastService.parseUtc(servedNotAfter);
            return base != null && cur != null && cur.isAfter(base);
        }
        if (notBlank(recordedAt)) {
            if (!notBlank(baseFp)) return false;   // taban bekleniyor: ilk kontrol benimser, kapanış ondan sonraki yenilemede
            return notBlank(servedFp) && !servedFp.trim().equalsIgnoreCase(baseFp.trim());
        }
        String nb = RenewalForecastService.localDay(servedNotBefore, RenewalForecastService.ZONE);
        return nb != null && nb.compareTo(plannedAt) >= 0;
    }

    private static void clearInMemory(CertificateInventory inv) {
        inv.setRenewalPlannedAt(null);
        inv.setRenewalPlannedBy(null);
        inv.setRenewalPlannedByName(null);
        inv.setRenewalPlannedNote(null);
        inv.setRenewalPlannedFingerprint(null);
        inv.setRenewalPlannedNotAfter(null);
        inv.setRenewalPlannedRecordedAt(null);
    }

    public static Map<String, Object> snapshot(CertificateInventory i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("renewalPlannedAt", i.getRenewalPlannedAt());
        m.put("renewalPlannedNote", i.getRenewalPlannedNote());
        return m;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nz(String v) { return v == null ? "" : v; }

    private static String str(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v != null ? v.toString() : null;
    }
}
