package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Saat tavanı ÖZETİ (2026-10-04, onaylı öneri 2) — tavana takılan push'lar sessizce kaybolmaz.
 *
 * <p><b>Sorun.</b> Kişi başına saatlik push tavanını ({@code site.monitor.userpush.hourly-cap}) aşan satır
 * {@code RATE_LIMITED} yazılıp hiç gönderilmiyordu; kişi bir şey kaçırdığını bilmiyordu. Bu iş, özetlenmemiş taşmaları
 * kullanıcı başına toplayıp TEK bir özet push'u gönderir: "SiteMonitor: saat tavanı nedeniyle 14 bildirim gönderilmedi
 * (3 kritik, 11 uyarı). Son: site-x - KRİTİK (14:05). Ayrıntılar SiteMonitor'da." (kişinin push dilinde; bağlantı YOK —
 * push kurum ağı dışında okunur).
 *
 * <p><b>Zamanlama.</b> Dakikada bir, {@code scheduler_lock} ({@value #LOCK_NAME}) altında (çok pod güvenli). Özet, ilk
 * özetlenmemiş taşmadan {@code overflow-summary-minutes} (vars. 15, 5–120) sonra gider; kullanıcı başına bu aralıkta en
 * çok BİR özet. Özetin kendisi saat tavanından MUAFTIR (yine de tavan sayımına girer).
 *
 * <p><b>Tekillik (en çok bir kez).</b> Özet satırı önce yazılır (kısa kira damgasıyla — outbox metin kesinleşmeden
 * almasın), sonra satırlar koşullu UPDATE ile sahiplenilir ({@code claimOverflow}: yalnız hâlâ RATE_LIMITED ve özetsiz
 * satır). Sahiplenen yoksa özet satırı silinir. Özetlenen satırların DURUMU {@code RATE_LIMITED} kalır; bağ
 * {@code overflow_summary_id} kolonundadır (günlükte iki yönlü bağ).
 *
 * <p><b>Kişisel kurallar.</b> Opt-out / pasif hesap → özet gönderilmez, satırlar yine bağlanır ve özet satırı nedeniyle
 * yazılır (iz). Sistem bakımı susturması → aynı (karar satırı {@code SKIPPED_SYSTEM_MAINTENANCE}). Global / kişisel sessiz
 * saat ya da susturma → özet ERTELENİR (sahiplenme yok; pencere bitince gider) — özetin en yüksek seviyesi kişinin
 * kurallarından geçiyorsa (ör. susturmada "kritikler yine gelsin" + kritik içerik) hemen gider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPushOverflowService {

    /** {@code scheduler_lock} anahtarı. */
    public static final String LOCK_NAME = "push-overflow";
    /** Özet satırının tetiği (teslimat günlüğü). */
    public static final String TRIGGER = "OVERFLOW_SUMMARY";
    /** Bu kadar eski taşma özetlenmez (özellik uzun süre kapalı kalıp açılınca bayat özet yağmasın; en uzun erteleme ~32 sa). */
    static final Duration LOOKBACK = Duration.ofHours(48);
    /** Tek özetin kapsadığı en çok satır (kalanı sonraki aralıkta). */
    static final int MAX_ROWS = 500;
    /** Özet satırının metin kesinleşene dek outbox'tan gizlendiği kira — pod yarıda ölürse özet bu kadar sonra gider. */
    static final Duration LEASE = Duration.ofMinutes(5);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final UserPushService push;
    private final UserPushDeliveryRepository repo;
    private final AppUserRepository userRepo;
    private final SchedulerService schedulerService;

    /** Test kancası. */
    Clock clock = Clock.systemUTC();

    /** Tur özeti (log + test). */
    public record SweepResult(int summaries, int skipped, int deferred) {
        static final SweepResult EMPTY = new SweepResult(0, 0, 0);
    }

    @Scheduled(fixedDelayString = "${site.monitor.userpush.overflow-sweep-ms:60000}",
               initialDelayString = "${site.monitor.userpush.overflow-sweep-initial-ms:75000}")
    public void scheduledSweep() {
        if (!push.enabled() || !push.overflowSummaryEnabled()) return;   // kapalıyken bugünkü davranış: satırlar günlükte kalır
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            SweepResult r = sweep(Instant.now(clock));
            if (r.summaries() > 0 || r.skipped() > 0) {
                log.info("Push saat tavanı özeti: {} özet, {} karar satırı, {} ertelendi", r.summaries(), r.skipped(), r.deferred());
            }
        });
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    SweepResult sweep(Instant now) {
        String since = ISO.format(now.minus(LOOKBACK));
        List<Object[]> candidates = repo.overflowCandidates(since);
        if (candidates == null || candidates.isEmpty()) return SweepResult.EMPTY;
        Duration window = Duration.ofMinutes(push.overflowSummaryMinutes());
        int summaries = 0, skipped = 0, deferred = 0;
        for (Object[] c : candidates) {
            if (c == null || c.length < 2 || c[0] == null) continue;
            String username = c[0].toString();
            Instant first = PushText.parseStoredUtc(c[1] == null ? null : c[1].toString());
            if (first == null || now.isBefore(first.plus(window))) continue;          // ilk taşmadan beri aralık dolmadı
            Instant last = PushText.parseStoredUtc(repo.lastOverflowSummaryAt(username));
            if (last != null && now.isBefore(last.plus(window))) continue;            // bu aralıkta zaten bir özet gitti
            try {
                switch (summarize(username, now, since)) {
                    case SENT -> summaries++;
                    case SKIPPED -> skipped++;
                    case DEFERRED -> deferred++;
                    default -> { /* sahiplenecek satır kalmadı */ }
                }
            } catch (Exception e) {
                log.warn("Push saat tavanı özeti kurulamadı (kullanıcı başına yalıtım): {}", e.toString());
            }
        }
        return new SweepResult(summaries, skipped, deferred);
    }

    enum Outcome { NONE, SENT, SKIPPED, DEFERRED }

    Outcome summarize(String username, Instant now, String since) {
        List<UserPushDelivery> rows = repo.findUnsummarizedOverflow(username, since, PageRequest.of(0, MAX_ROWS));
        if (rows == null || rows.isEmpty()) return Outcome.NONE;
        AppUser u = userRepo.findByUsername(username).orElse(null);
        String lang = PushI18n.norm(u == null ? null : u.getPushLang());
        String maxLevel = maxLevel(rows);

        String skip = null;
        if (u == null || !Boolean.TRUE.equals(u.getActive())) skip = UserPushRecipientResolver.SKIPPED_USER_INACTIVE;
        else if (Boolean.TRUE.equals(u.getPushOptOut())) skip = "SKIPPED_USER_OPT_OUT";
        else if (push.systemMaintenanceMuted()) skip = SystemMaintenanceService.PUSH_SKIPPED;
        else if (deferred(u, maxLevel, now)) return Outcome.DEFERRED;   // sahiplenme YOK: pencere/susturma bitince gider

        UserPushDelivery s = new UserPushDelivery();
        s.setTrigger(TRIGGER);
        s.setDedupeKey("OVERFLOW:" + rows.get(0).getId());
        s.setUsername(username);
        s.setDisplayName(displayOf(u, username));
        s.setMonitorName(summaryName(rows.size()));
        s.setTeamId(commonTeam(rows));
        s.setAlertLevel(maxLevel);
        s.setTitle(push.titleSetting(lang));
        s.setMessage(message(rows, lang));
        s.setPushLang(lang);
        s.setStatus(skip == null ? "PENDING" : skip);
        s.setCreatedAt(ISO.format(now));
        s.setBatchId("ovf-" + UUID.randomUUID().toString().substring(0, 8));
        if (skip == null) s.setNextAttemptAt(ISO.format(now.plus(LEASE)));   // metin kesinleşene dek outbox almasın
        UserPushDelivery saved = repo.save(s);
        if (saved != null) s = saved;
        if (s.getId() == null) return Outcome.NONE;

        List<Long> ids = rows.stream().map(UserPushDelivery::getId).filter(Objects::nonNull).toList();
        int claimed = ids.isEmpty() ? 0 : repo.claimOverflow(ids, s.getId());
        if (claimed <= 0) {   // başka bir tur/pod aldı → bu özet boş kalır, silinir (iz gereksiz: o satırların özeti var)
            repo.delete(s);
            return Outcome.NONE;
        }
        if (claimed < ids.size()) {   // bir kısmı başka yere gitti → metin GERÇEKTEN bağlanan satırlarla yeniden kurulur
            List<UserPushDelivery> mine = repo.findByOverflowSummaryIdOrderByIdAsc(s.getId());
            if (mine != null && !mine.isEmpty()) {
                s.setMessage(message(mine, lang));
                s.setMonitorName(summaryName(mine.size()));
                s.setAlertLevel(maxLevel(mine));
                s.setTeamId(commonTeam(mine));
            }
        }
        if (skip != null) {
            repo.save(s);
            return Outcome.SKIPPED;
        }
        s.setNextAttemptAt(null);   // kesinleşti → outbox hemen alabilir (tavan sorulmaz: özet muaf)
        repo.save(s);
        push.kickDrain();
        return Outcome.SENT;
    }

    /** Özet bu an ertelenmeli mi — global/kişisel sessiz saat ya da susturma, özetin en yüksek seviyesini bastırıyorsa. */
    boolean deferred(AppUser u, String maxLevel, Instant now) {
        if (push.quietHoursBlock(maxLevel)) return true;
        if (UserPushRecipientResolver.personalQuietBlocks(u, maxLevel, now)) return true;
        if (UserPushRecipientResolver.snoozeActive(u, now)) {
            boolean criticalPasses = !Boolean.FALSE.equals(u.getPushSnoozeCritical()) && "CRITICAL".equals(maxLevel);
            return !criticalPasses;
        }
        return false;
    }

    String message(List<UserPushDelivery> rows, String lang) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (UserPushDelivery d : rows) levels.merge(levelKey(d.getAlertLevel()), 1, Integer::sum);
        UserPushDelivery last = rows.get(rows.size() - 1);
        String text = PushI18n.overflowSummary(rows.size(), levels,
                new PushI18n.OverflowLast(last.getMonitorName(), last.getAlertLevel(), last.getTrigger(), last.getCreatedAt()), lang);
        return PushText.truncate(PushText.pushSafe(text), push.maxMessageChars());
    }

    static String maxLevel(List<UserPushDelivery> rows) {
        int best = 0;
        for (UserPushDelivery d : rows) best = Math.max(best, UserPushRecipientResolver.levelValue(levelKey(d.getAlertLevel())));
        return best >= 3 ? "CRITICAL" : best == 2 ? "HIGH" : "WARNING";
    }

    private static String levelKey(String level) {
        return level == null || level.isBlank() ? "WARNING" : level.trim().toUpperCase(Locale.ROOT);
    }

    /** Satırların hepsi aynı takımdansa o takım (kapsamlı yönetici günlükte görsün); karışıksa null. */
    static Long commonTeam(List<UserPushDelivery> rows) {
        Long team = null;
        for (UserPushDelivery d : rows) {
            if (d.getTeamId() == null) return null;
            if (team == null) team = d.getTeamId();
            else if (!team.equals(d.getTeamId())) return null;
        }
        return team;
    }

    /** Günlükteki ad (yönetici yüzeyi, Türkçe veri). */
    static String summaryName(int n) {
        return "Saat tavanı özeti · " + n + " bildirim";
    }

    private static String displayOf(AppUser u, String username) {
        if (u == null) return username;
        return u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : username;
    }
}
