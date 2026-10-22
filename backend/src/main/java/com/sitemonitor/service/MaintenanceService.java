package com.sitemonitor.service;

import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.repository.MaintenanceWindowRepository;
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
 * Cache @Scheduled ile ~30sn'de bir ve her CRUD yazımında ({@link #refresh()}) tazelenir. Çok pod (2026-10-22, R2-A, MP-30):
 * CRUD yazımı önbellek veri yoluyla ({@link CacheBus}, ad {@link CacheBusNames#MAINTENANCE_WINDOWS}) öteki pod'lara da
 * duyurulur — tarama lideri "şimdi başlat" penceresini 30 sn yerine ~2 sn içinde görür (alarm bastırma) ve sertifika kontrol
 * satırlarının {@code maintenance} damgası o boşlukta yanlış yazılmaz. Zamanlanmış tazeleme yayın YAPMAZ.
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
    @Scheduled(fixedDelayString = "${site.monitor.maintenance.refresh-ms:30000}", initialDelayString = "20000")
    public void scheduledRefresh() {
        try { reload(); } catch (Exception e) { log.warn("Maintenance cache refresh başarısız: {}", e.getMessage()); }
    }

    /**
     * CRUD sonrası (anında etki): bu pod'un önbelleğini tazeler ve öteki pod'lara duyurur (MP-30). Okuma hata verirse yine de
     * duyurulur (öteki pod'lar kendi okumasını dener) ve hata çağırana iletilir (bugünkü davranış).
     */
    public void refresh() {
        try {
            reload();
        } finally {
            CacheBus b = cacheBus;
            if (b != null) b.bump(CacheBusNames.MAINTENANCE_WINDOWS);
        }
    }

    /** Pod'lar arası boşaltma (MP-30) — yoksa yalnız bu pod (30 sn tazeleme). */
    private volatile CacheBus cacheBus;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCacheBus(CacheBus bus) {
        this.cacheBus = bus;
        if (bus != null) bus.register(CacheBusNames.MAINTENANCE_WINDOWS, this::markStale);
    }

    /**
     * Öteki pod'un yayını (veri yolu iş parçacığında, yoklama kilitleri altında): yalnız BAYAT işaretler — veritabanı okuması
     * yok (çok pod R2-A inceleme: yavaş sorgu veri yolunu dondurmasın). Görüntü bir sonraki okumada tazelenir ({@link #freshen}).
     */
    void markStale() {
        invalidations.incrementAndGet();
    }

    /** Bayat işaretlerinin sayısı ve görüntünün yansıttığı değer — farklıysa sonraki okuma bir kez yeniden yükler. */
    private final java.util.concurrent.atomic.AtomicLong invalidations = new java.util.concurrent.atomic.AtomicLong();
    private volatile long loadedInvalidation;

    /** Bayat işaretlendiyse bir kez yeniden yükler; okuma hata verirse işaret bırakılır (30 sn tazeleme dener — bugünkü davranış). */
    private void freshen() {
        if (loadedInvalidation == invalidations.get()) return;
        synchronized (this) {
            long want = invalidations.get();
            if (loadedInvalidation == want) return;
            try {
                reload();
            } catch (Exception e) {
                loadedInvalidation = want;   // her okumada veritabanına gidilmesin
                log.warn("Bakım penceresi önbelleği tazelenemedi (30 sn tazeleme yeniden dener): {}", e.getMessage());
            }
        }
    }

    /** Etkin pencereleri tarayıp şu an aktif olanların hedeflerini birleştirir (yalnız bu pod; yayın yok). */
    synchronized void reload() {
        long gen = invalidations.get();
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
        this.loadedInvalidation = gen;
    }

    /** Bir alarm-anahtarı (host/url/domain) şu an bakımda mı? O(1) (öteki pod yazdıysa bir kez yeniden yükler). */
    public boolean isUnderMaintenance(String target) {
        freshen();
        return allActive || (target != null && activeTargets.contains(target));
    }

    /**
     * Genel Bakış kartı bakım rozeti (2026-09-19): her hedef için {active, until, next_start, name}. Tüm aktif
     * pencereler BİR kez gezilir (alan başına sorgu yok); "all monitors" penceresi {@code "*"} anahtarında.
     * next_start: 24 saat içinde başlayacak en yakın pencere.
     */
    public Map<String, Map<String, Object>> windowInfoByTarget(Instant now) {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        for (MaintenanceWindow w : repo.findByActiveTrue()) {
            Instant end = activeEndAt(w, now);
            String next = end == null ? nextOccurrence(w, now) : null;
            Instant nextAt = next == null ? null : parse(next);
            boolean soon = nextAt != null && !nextAt.isAfter(now.plus(Duration.ofHours(24)));
            if (end == null && !soon) continue;
            List<String> keys = Boolean.TRUE.equals(w.getAllMonitors()) ? List.of("*") : targetsOf(w);
            for (String k : keys) {
                Map<String, Object> m = out.computeIfAbsent(k, x -> new LinkedHashMap<>());
                if (end != null) {   // aktif pencere önceliklidir; birden çoksa en geç biten
                    Object cur = m.get("until");
                    if (!Boolean.TRUE.equals(m.get("active")) || cur == null || ISO.format(end).compareTo(String.valueOf(cur)) > 0) {
                        m.put("active", true); m.put("until", ISO.format(end)); m.put("name", w.getName());
                    }
                } else if (!Boolean.TRUE.equals(m.get("active"))) {
                    Object cur = m.get("next_start");
                    if (cur == null || next.compareTo(String.valueOf(cur)) < 0) { m.put("active", false); m.put("next_start", next); m.put("name", w.getName()); }
                }
            }
        }
        return out;
    }

    /** Pencere şu an aktifse bitiş anı, değilse null — {@link #isActiveAt} ile aynı occurrence kuralı. */
    public Instant activeEndAt(MaintenanceWindow w, Instant now) {
        return activeOccurrenceEnd(w, now);
    }

    /** Süre üst sınırı (dakika) — 30 gün = global yöneticinin rol tavanı ({@code MaintenanceController.MAX_MINUTES_GLOBAL});
     *  kapsamlı yazar 7 günle sınırlıdır ve kapı controller'dadır. Motor yalnız aşırı eski/elle yazılmış değeri kırpar. */
    public static final int MAX_DURATION_MINUTES = 30 * 24 * 60;

    /**
     * TEK occurrence motoru (A5 Y-1, 2026-09-29): {@link #isActiveAt} ve {@link #activeEndAt} aynı kuralı paylaşır. Tekrarlayan
     * pencerede aday günler yalnız "bugün + dün" DEĞİL; süre 24 saati aşıyorsa {@code dur/1440 + 1} gün geriye kadar taranır —
     * eskiden Cuma 18:00'de başlayan 62 saatlik hafta sonu penceresi Pazar 00:00'dan sonra "aktif değil" sayılıyor, sweep
     * bakımdaki hedefe yanlış alarm açıyor ve erişilebilirlik raporu bakımı hariç tutamıyordu.
     * @return aktifse oluşumun bitiş anı, değilse null
     */
    private Instant activeOccurrenceEnd(MaintenanceWindow w, Instant now) {
        if (!Boolean.TRUE.equals(w.getActive())) return null;
        Instant anchor = parse(w.getStartAt());
        if (anchor == null) return null;
        long dur = durMin(w);
        ZoneId zone = zoneOf(w);
        String rec = rec(w);
        if ("NONE".equals(rec)) {
            Instant end = anchor.plus(Duration.ofMinutes(dur));
            return !now.isBefore(anchor) && now.isBefore(end) ? end : null;
        }
        ZonedDateTime anchorZ = anchor.atZone(zone);
        LocalTime tod = anchorZ.toLocalTime();
        LocalDate anchorDate = anchorZ.toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        int back = (int) (dur / 1440) + 1;   // ≤24 sa → dün + bugün (gece yarısını aşan pencere); daha uzun → süre kadar geriye
        for (int i = back; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            if (d.isBefore(anchorDate) || !matchesRecurrence(w, rec, d)) continue;
            Instant start = ZonedDateTime.of(d, tod, zone).toInstant();
            Instant end = start.plus(Duration.ofMinutes(dur));
            if (!now.isBefore(start) && now.isBefore(end)) return end;
        }
        return null;
    }

    /** /active endpoint'i için: {all, targets}. */
    public Map<String, Object> activeInfo() {
        freshen();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("all", allActive);
        m.put("targets", new ArrayList<>(activeTargets));
        return m;
    }

    // ── Occurrence engine (DST-güvenli) ──────────────────────────────────────
    /** Verilen anda pencere aktif mi (paused ise her zaman false) — {@link #activeEndAt} ile AYNI motor (A5 Y-1). */
    public boolean isActiveAt(MaintenanceWindow w, Instant now) {
        return activeOccurrenceEnd(w, now) != null;
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
    public List<String> targetsOf(MaintenanceWindow w) {
        return targetKeys(w.getTargetsJson());
    }

    /**
     * {@code targets_json} → alarm anahtarları — motorun (refresh / isUnderMaintenance) OKUDUĞU liste. Statik:
     * MaintenanceController'ın takım kapsamı kapısı AYNI ayrıştırmayı kullanır; kapının doğruladığı liste ile
     * motorun susturduğu liste ayrışamaz (servis testte mock'lansa bile).
     */
    @SuppressWarnings("unchecked")
    public static List<String> targetKeys(String targetsJson) {
        if (targetsJson == null || targetsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = MAPPER.readValue(targetsJson, List.class);
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
    /** 1..{@link #MAX_DURATION_MINUTES} dakika (kayıtta daha büyük bir değer varsa motor tavanda kırpar — controller da reddeder). */
    private static long durMin(MaintenanceWindow w) {
        return Math.min(MAX_DURATION_MINUTES, Math.max(1, w.getDurationMinutes() == null ? 60 : w.getDurationMinutes()));
    }

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
