package com.sitemonitor.service;

import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Takım sessiz saatleri (2026-10-01, onaylı öneri 15) — "Tanımlanmadıkça bildirim zamanı değişmez. Kritik alarmlar
 * sessiz saatte de gider, ertelenen her bildirim kayda geçer."
 *
 * <p><b>Karar.</b> Bir alarm bildirimi (açılış / seviye artışı / günlük hatırlatma — {@link #DEFERRABLE_TRIGGERS}) bir
 * sahip takımın penceresine düşer ve seviyesi o takım için ertelenebilirse ({@link QuietHours#defers}: varsayılan yalnız
 * UYARI; KRİTİK asla) o takımın alıcıları (takım adresi / bildirim grubu, takım kişileri, üyelerin push'u) bildirimi
 * ŞİMDİ almaz; alarm {@code quiet_digest_items}'a yazılır ve pencere bitince tek özet e-postasıyla gider
 * ({@link QuietDigestService}). Elle yeniden gönderim (MANUAL) bilinçli bir eylemdir — ertelenmez.
 *
 * <p><b>Maliyet.</b> Ayarlar bellek-içi önbellekten okunur: dakikada en çok BİR sorgu ({@code findQuietConfigured}),
 * alarm/alıcı başına sorgu YOK. Tanımlı takım yoksa harita boştur ve gönderim yolu bugünküyle bayt bayt aynıdır. Ayar
 * değişince bu pod'da önbellek hemen düşer ({@link #invalidate}); diğer pod'lar en geç {@link #CACHE_TTL} içinde görür.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamQuietHoursService {

    /** Ertelenebilen e-posta tetikleri. MANUAL (elle yeniden gönderim) ve çözüm ertelenmez. */
    public static final List<String> DEFERRABLE_TRIGGERS = List.of("INITIAL", "ESCALATION", "DAILY_REALERT");
    /** Eskalasyon adımı ertelemesinin kayıt tetiği (adım pencere sonrasına bekletilir). */
    public static final String STEP_TRIGGER = "ESCALATION_STEP";
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TeamRepository teamRepo;
    private final QuietDigestItemRepository itemRepo;

    /** Test kancası (paket-özel). */
    Clock clock = Clock.systemUTC();

    private record Snapshot(Map<Long, QuietHours> byTeam, long loadedAtMillis) {}

    private volatile Snapshot snapshot;

    public Instant now() {
        return Instant.now(clock);
    }

    /** Sessiz saati tanımlı takımlar → pencere (önbellek; bayatsa TEK sorguyla tazelenir). Hata = boş harita. */
    Map<Long, QuietHours> configured() {
        Snapshot s = snapshot;
        long nowMs = clock.millis();
        if (s != null && nowMs - s.loadedAtMillis() < CACHE_TTL.toMillis()) return s.byTeam();
        return reload(nowMs).byTeam();
    }

    private synchronized Snapshot reload(long nowMs) {
        Snapshot s = snapshot;
        if (s != null && nowMs - s.loadedAtMillis() < CACHE_TTL.toMillis()) return s;
        Map<Long, QuietHours> map = new HashMap<>();
        try {
            for (Team t : teamRepo.findQuietConfigured()) {
                if (t == null || t.getId() == null) continue;
                QuietHours q = QuietHours.parse(t.getQuietStart(), t.getQuietEnd(), t.getQuietDays(), t.getQuietMinLevel());
                if (q != null) map.put(t.getId(), q);
            }
        } catch (Exception e) {
            // Kolon henüz yoksa (yama öncesi) ya da DB anlık düştüyse: sessiz saat YOK say — bildirim asla engellenmez.
            log.debug("Sessiz saat ayarları okunamadı (pencere yok sayıldı): {}", e.getMessage());
        }
        Snapshot fresh = new Snapshot(Map.copyOf(map), nowMs);
        snapshot = fresh;
        return fresh;
    }

    /** Takım ayarı değişti — bu pod'un önbelleği hemen düşer. */
    public void invalidate() {
        snapshot = null;
    }

    public boolean anyConfigured() {
        return !configured().isEmpty();
    }

    public boolean isConfigured(Long teamId) {
        return teamId != null && configured().containsKey(teamId);
    }

    /** Takımın {@code now} anındaki pencere oluşumu; pencere dışı / ayar yok → null. */
    public QuietHours.Occurrence activeWindow(Long teamId, Instant now) {
        if (teamId == null) return null;
        QuietHours q = configured().get(teamId);
        return q == null ? null : q.occurrenceAt(now);
    }

    /** Bu seviyedeki bildirim bu takım için ŞİMDİ ertelenir mi? Ertelenirse pencere, değilse null. */
    public QuietHours.Occurrence deferral(Long teamId, String level, Instant now) {
        if (teamId == null) return null;
        QuietHours q = configured().get(teamId);
        if (q == null || !q.defers(level)) return null;
        return q.occurrenceAt(now);
    }

    /**
     * Ertelemeyi kaydeder (yoksa). Aynı (alarm, takım, pencere) için ikinci çağrı yeni satır açmaz.
     *
     * @return kayıt BU çağrıda oluştuysa true
     */
    public boolean recordDeferral(Long alertEventId, Long teamId, QuietHours.Occurrence occ, String level,
                                  String trigger, Instant now) {
        if (alertEventId == null || teamId == null || occ == null) return false;
        QuietDigestItem i = new QuietDigestItem();
        i.setAlertEventId(alertEventId);
        i.setTeamId(teamId);
        i.setWindowKey(occ.key());
        i.setWindowEnd(ISO.format(occ.endInstant()));
        i.setAlertLevel(level);
        i.setFirstTrigger(trigger);
        i.setOpeningDeferred("INITIAL".equals(trigger));
        i.setDeferredAt(ISO.format(now));
        try {
            itemRepo.saveAndFlush(i);
            return true;
        } catch (DataIntegrityViolationException dup) {
            return false;   // bu pencerede zaten ertelenmiş — özet bir kez
        }
    }

    /** Takım(lar) pencere içinde bu alarm için ertelenmeyen bildirim aldı → bekleyen kayıt özette tekrar edilmez. */
    public void supersede(Long alertEventId, Collection<Long> teamIds, Instant now) {
        if (alertEventId == null || teamIds == null || teamIds.isEmpty()) return;
        itemRepo.supersede(alertEventId, teamIds, ISO.format(now));
    }

    /** Alarmın bekleyen (özeti gitmemiş, geçersizleşmemiş) kayıtları. */
    public List<QuietDigestItem> pendingForEvent(Long alertEventId) {
        if (alertEventId == null) return List.of();
        return itemRepo.findPendingByAlertEventId(alertEventId);
    }

    /** Toplu: alarm → bekleyen kaydı olan takımlar (eskalasyon adımı turu; tur başına bir sorgu). */
    public Map<Long, Set<Long>> pendingTeamsByEvent(Collection<Long> alertEventIds) {
        Map<Long, Set<Long>> out = new HashMap<>();
        if (alertEventIds == null || alertEventIds.isEmpty()) return out;
        for (QuietDigestItem i : itemRepo.findPendingByAlertEventIdIn(alertEventIds)) {
            out.computeIfAbsent(i.getAlertEventId(), k -> new HashSet<>()).add(i.getTeamId());
        }
        return out;
    }
}
