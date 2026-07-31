package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.AlertStorm;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.AlertStormRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Alarm fırtınası (alert storm) motoru — çok sayıda monitör kısa bir pencerede birden düştüğünde
 * bireysel alarmları TEK toplu bildirime indirger (UptimeRobot "Alert settings" modeli).
 *
 * <p><b>Tasarım — akışlı sayaç + terfi (Design B):</b> Tampon YOK; her doğrulanmış kesinti bugünkü gibi
 * ANINDA gider — ancak scope'u için aktif bir storm varsa (attach) ya da bu kesinti eşiği aşıyorsa (promote)
 * bastırılır/toplu gider. Kalıcı {@link AlertEvent}'ler sayacın kendisidir → doğal crash-safe; bellek-içi
 * sayaç yoktur. Terfi, {@code alert_storms} tablosunda scope-başına-tek-aktif kısmi UNIQUE indeks +
 * {@code INSERT … ON CONFLICT DO NOTHING} ile atomiktir → eşzamanlı worker'larda çift storm/çift alarm YOK.
 *
 * <p><b>Yalnız BİLDİRİMİ gruplar:</b> incident'ler (AlertEvent) per-monitör kaydedilmeye devam eder →
 * geçmiş/uptime% hiç etkilenmez. Bağ {@code AlertEvent.stormId} iledir.
 *
 * <p><b>Katmanlama:</b> {@code EscalationService.processConfirmedOutage} → {@link #evaluate} hunisi, mevcut
 * iki ≥%50 bulk-suppression geçidinin (SchedulerService cert yolu + MonitoringOutageService izleme yolu)
 * ALTINDA çalışır — yalnız onları aşmış (yani &lt;%50 / çok-tipli) selleri toplar; gerçek altyapı kesintisi
 * zaten yukarıda yutulur.
 *
 * <p><b>Feature-flag default AÇIK</b> ({@code cert.monitor.storm.enabled}); admin "Alert Settings"ten kapatabilir.
 * Kapalıyken {@link #evaluate} ilk satırda {@code SEND_INDIVIDUAL} döner → mevcut alarm davranışı bit-bit aynı.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StormService {

    private final AlertStormRepository stormRepo;
    private final AlertEventRepository alertEventRepo;
    private final AppSettingsService appSettings;
    private final EmailNotificationService emailService;
    private final WebhookService webhookService;
    private final TeamRepository teamRepo;
    private final EscalationContactRepository contactRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final JdbcTemplate jdbcTemplate;
    // Toplam-aktif-monitör denominatörü + per-group grup çözümü için monitör repo'ları
    private final HttpMonitorRepository httpRepo;
    private final PortMonitorRepository portRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PingMonitorRepository pingRepo;
    private final DnsMonitorRepository dnsRepo;
    private final DomainMonitorRepository domainRepo;

    /** 9. tür (sayfa-bütünlüğü) — @RequiredArgsConstructor'ı (ve StormServiceTest'in elle çağrısını)
     *  büyütmemek için alan enjeksiyonu (SchedulerService ile aynı desen). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.repository.PageMonitorRepository pageRepo;

    /** 10. tür (senaryo) — alan enjeksiyonu (constructor/test büyütmemek için; pageRepo ile aynı desen). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.repository.ScriptedMonitorRepository scriptedRepo;

    public enum StormAction {
        /** Storm devrede değil / eşik altı → bireysel alarm gönder (bugünkü davranış, sıfır gecikme). */
        SEND_INDIVIDUAL,
        /** Storm üyesi (attach) veya terfi → bireysel bildirim bastırıldı (toplu alarm storm üzerinden). */
        SUPPRESSED
    }

    public static final String KEY_ENABLED   = "cert.monitor.storm.enabled";
    public static final String KEY_UNIT      = "cert.monitor.storm.threshold-unit";     // PERCENT | COUNT
    public static final String KEY_VALUE     = "cert.monitor.storm.threshold-value";
    public static final String KEY_WINDOW    = "cert.monitor.storm.window-minutes";
    public static final String KEY_PER_GROUP = "cert.monitor.storm.per-group";

    static final String SCOPE_ACCOUNT = "ACCOUNT";
    static final String UNGROUPED     = "__UNGROUPED__";
    /** Bir "storm" için gereken minimum monitör tabanı — 1'lik storm anlamsız. */
    static final int MIN_THRESHOLD = 2;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String INSTANCE_ID = resolveHostname() + "-storm";
    private static final int LOCK_TTL_SECONDS = 90;

    // Toplam-aktif-monitör cache (denominatör) — her kesintide saymamak için (~60sn TTL).
    private volatile long cachedTotal = 0;
    private volatile long cachedTotalAt = 0;

    // ── Karar hunisi ────────────────────────────────────────────────────────────

    /**
     * processConfirmedOutage'ın INITIAL dispatch'inden HEMEN ÖNCE çağrılır (event zaten kaydedilmiş).
     * Storm üyesiyse/terfi ediyorsa {@code SUPPRESSED} döner (çağıran bireysel {@code sendCombinedAlert}'i atlar);
     * değilse {@code SEND_INDIVIDUAL}. stormId (+ per-group modda groupName) çağıranın {@code event} nesnesine
     * damgalanır; çağıran zaten event'i tekrar save ettiğinden ayrıca kaydetmeye gerek yok.
     */
    public StormAction evaluate(AlertEvent event, Map<String, Object> ctx) {
        if (!isEnabled()) return StormAction.SEND_INDIVIDUAL;
        if (event == null || event.getAlertType() == null
                || !EscalationService.DOWN_ALERT_TYPES.contains(event.getAlertType())) {
            return StormAction.SEND_INDIVIDUAL;   // yalnız gerçek DOWN tipleri storm'a girer
        }

        boolean perGroup = appSettings.getBoolean(KEY_PER_GROUP, false);
        String scopeKey, scopeType;
        if (perGroup) {
            String group = resolveGroup(event, ctx);   // lazy — yalnız per-group modda repo lookup
            scopeKey  = (group != null && !group.isBlank()) ? group : UNGROUPED;
            scopeType = "GROUP";
            event.setGroupName(scopeKey);               // sayım grup filtresi + toplu recovery için damgala
        } else {
            scopeKey = SCOPE_ACCOUNT;
            scopeType = "ACCOUNT";
        }

        try {
            // 1) Scope'un aktif storm'u var mı → bağla (attach), bireysel gönderme.
            Optional<AlertStorm> active = stormRepo.findByScopeKeyAndResolvedFalse(scopeKey);
            if (active.isPresent()) {
                AlertStorm storm = active.get();
                event.setStormId(storm.getId());
                bumpMemberCount(storm);
                log.info("🌩 Storm üyesi eklendi (bireysel bildirim yok): {} [{}] → storm #{}",
                        event.getDomain(), event.getAlertType(), storm.getId());
                return StormAction.SUPPRESSED;
            }

            // 2) Pencere-içi açık DOWN eş sayısı ≥ eşik mi → terfi; değilse normal bireysel (sıfır gecikme).
            String since = windowSince();
            List<AlertEvent> peers = new ArrayList<>(perGroup
                    ? alertEventRepo.findOpenDownSinceInGroup(EscalationService.DOWN_ALERT_TYPES, since, scopeKey)
                    : alertEventRepo.findOpenDownSince(EscalationService.DOWN_ALERT_TYPES, since));
            // Tetikleyen event, tanımı gereği scope'ta açık bir DOWN'dır; ancak per-group modda group_name'i henüz
            // commit edilmemiş olabileceğinden sorgu onu HARİÇ tutabilir → eşik off-by-one'ı (per-group N+1 gerektirirdi).
            // Sayıma ve üye listesine mutlaka dahil et (M1). linkPeers zaten skipId ile onu atlar (çağıran kaydeder).
            if (event.getId() == null || peers.stream().noneMatch(p -> event.getId().equals(p.getId()))) {
                peers.add(event);
            }
            int threshold = computeThreshold();
            if (peers.size() < threshold) return StormAction.SEND_INDIVIDUAL;

            // 3) Atomik terfi — scope-başına-tek-aktif UNIQUE; kazanan (rows==1) toplu alarm gönderir.
            boolean created = insertStormIfAbsent(scopeKey, scopeType, peers);
            Optional<AlertStorm> stormOpt = stormRepo.findByScopeKeyAndResolvedFalse(scopeKey);
            if (stormOpt.isEmpty()) {
                log.warn("Storm terfi sonrası aktif storm bulunamadı ({}) — bireysel gönderiliyor", scopeKey);
                return StormAction.SEND_INDIVIDUAL;
            }
            AlertStorm storm = stormOpt.get();

            // Penceredeki tüm açık DOWN üyeleri storm'a bağla (mevcut event'i çağıran kaydeder → burada atla).
            event.setStormId(storm.getId());
            linkPeers(peers, storm.getId(), event.getId());

            if (created) {
                sendStormAlert(storm, peers, "INITIAL");   // kazanan → TEK toplu alarm (senkron; worker thread)
                log.warn("🌩🔴 ALARM FIRTINASI başladı — storm #{} [{}] {} monitör (eşik {}) — TEK toplu bildirim gönderildi",
                        storm.getId(), scopeKey, peers.size(), threshold);
            }
            return StormAction.SUPPRESSED;

        } catch (Exception e) {
            // Storm mantığı asla bireysel alarmı düşürmesin — hata olursa güvenli tarafta bireysel gönder.
            log.warn("Storm değerlendirmesi hata verdi ({} [{}]) — bireysel alarma düşülüyor: {}",
                    event.getDomain(), event.getAlertType(), e.getMessage());
            return StormAction.SEND_INDIVIDUAL;
        }
    }

    /** Açık alarm bir storm üyesiyse ve storm hâlâ aktifse true (çağıran bireysel çözüm e-postasını bastırır). */
    public boolean isActive(Long stormId) {
        if (stormId == null) return false;
        try {
            return stormRepo.findById(stormId).map(s -> !Boolean.TRUE.equals(s.getResolved())).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Yaşam döngüsü sweep'i (çözülme / günlük re-alert / toggle-off) ────────────

    @Scheduled(fixedDelayString = "${cert.monitor.storm.sweep-ms:30000}", initialDelay = 45000)
    public void lifecycleSweep() {
        List<AlertStorm> active;
        try {
            active = stormRepo.findByResolvedFalse();
        } catch (Exception e) {
            return;   // tablo henüz yok / DB erişilemez — sessiz
        }
        if (active.isEmpty()) return;

        boolean enabled = isEnabled();
        if (!tryLock()) return;   // HA: yalnız bir pod yönetir
        try {
            for (AlertStorm storm : active) {
                try {
                    if (!enabled) { disband(storm); continue; }   // toggle-off: geri-bağla, sessiz kapat
                    lifecycleOne(storm);
                } catch (Exception e) {
                    log.warn("Storm yaşam döngüsü hatası #{}: {}", storm.getId(), e.getMessage());
                }
            }
        } finally {
            releaseLock();
        }
    }

    private void lifecycleOne(AlertStorm storm) {
        List<AlertEvent> members = alertEventRepo.findByStormId(storm.getId());
        long activeDown = members.stream().filter(m -> !Boolean.TRUE.equals(m.getResolved())).count();
        storm.setMemberCount(members.size());

        int threshold = computeThreshold();
        int resolveFloor = Math.max(MIN_THRESHOLD, (threshold + 1) / 2);   // histerezis: eşiğin altı → flapping'i önler

        if (activeDown < resolveFloor) {
            resolveStorm(storm, members);
            return;
        }
        // Aktif storm sürüyor → günlük toplu re-alert (aynı-UTC-gün kuralı, bireysel re-alert'in aynası)
        String last = storm.getLastReAlertAt() != null ? storm.getLastReAlertAt() : storm.getCreatedAt();
        // Storm günlük toplu re-alert — rolling 24 saat (23:59'da açılıp 00:00'da tekrar alarmlama edge'i, M10 ile tutarlı).
        if (last == null || EscalationService.reAlertDue(last, now(), 24)) {
            List<AlertEvent> stillDown = members.stream()
                    .filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();
            sendStormAlert(storm, stillDown, "DAILY_REALERT");
            log.info("🌩 Storm #{} günlük toplu re-alert gönderildi — {} monitör hâlâ down", storm.getId(), stillDown.size());
        } else {
            stormRepo.save(storm);   // memberCount tazelemesini kalıcılaştır
        }
    }

    /** Çözülme (histerezis tabanının altına inildi): TEK toplu recovery + hâlâ-down üyeleri geri-bağla. */
    private void resolveStorm(AlertStorm storm, List<AlertEvent> members) {
        List<AlertEvent> recovered = members.stream()
                .filter(m -> Boolean.TRUE.equals(m.getResolved())).toList();
        List<AlertEvent> stillDown = members.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();

        // Hâlâ-down üyeleri storm'dan çöz → sonraki sweep BİREYSEL alarmlar (hiçbir şey sessizce kaybolmaz).
        for (AlertEvent e : stillDown) {
            alertEventRepo.unlinkFromStorm(e.getId());   // koşullu — çözülmüş üyeyi diriltmeden bağı kaldır (M6)
        }
        storm.setResolved(true);
        storm.setResolvedAt(now());
        stormRepo.save(storm);

        if (!recovered.isEmpty()) sendStormRecovery(storm, recovered, stillDown);
        log.info("🌩✅ Storm #{} çözüldü — {} kurtuldu, {} hâlâ down (bireysele döndü)",
                storm.getId(), recovered.size(), stillDown.size());
    }

    /** Toggle KAPALI iken aktif storm'u zarifçe dağıt: üyeleri geri-bağla (bireysele dön), sessiz kapat. */
    private void disband(AlertStorm storm) {
        for (AlertEvent e : alertEventRepo.findByStormIdAndResolvedFalse(storm.getId())) {
            alertEventRepo.unlinkFromStorm(e.getId());   // koşullu geri-bağlama (M6); sonraki sweep bireysel re-alert
        }
        storm.setResolved(true);
        storm.setResolvedAt(now());
        stormRepo.save(storm);
        log.info("🌩 Storm #{} kapatıldı (özellik kapatıldı) — üyeler bireysel alarmlamaya döndü", storm.getId());
    }

    // ── Eşik / pencere / denominatör ──────────────────────────────────────────────

    /** Eşik: COUNT → max(2, value); PERCENT → max(2, ceil(value/100 × toplamAktifMonitör)).
     *  Round kuralı: ceil + taban 2 (edge: %10 × 5 monitör = ceil(0.5)=1 → max(2,1)=2). */
    public int computeThreshold() {
        String unit = appSettings.getString(KEY_UNIT, "COUNT");
        int value = appSettings.getInt(KEY_VALUE, 5);
        if ("PERCENT".equalsIgnoreCase(unit)) {
            long total = totalActiveMonitors();
            int t = (int) Math.ceil((value / 100.0) * total);
            return Math.max(MIN_THRESHOLD, t);
        }
        return Math.max(MIN_THRESHOLD, value);
    }

    private String windowSince() {
        int windowMin = clamp(appSettings.getInt(KEY_WINDOW, 5), 1, 15);
        return ISO.format(Instant.now().minusSeconds(windowMin * 60L));
    }

    public long totalActiveMonitors() {
        long nowMs = System.currentTimeMillis();
        if (cachedTotal > 0 && nowMs - cachedTotalAt < 60_000) return cachedTotal;
        long total;
        try {
            total = inventoryRepo.countByActiveTrue()
                    + httpRepo.countByActiveTrue()
                    + keywordRepo.countByActiveTrue()
                    + pingRepo.countByActiveTrue()
                    + domainRepo.countByActiveTrue()
                    + portRepo.countByStandaloneTrueAndActiveTrue()   // cert-türevi satırları çift saymamak için standalone
                    + dnsRepo.countByStandaloneTrueAndActiveTrue()
                    + (pageRepo != null ? pageRepo.countByActiveTrue() : 0);   // 9. tür (field-inject; test'te null → 0)
        } catch (Exception e) {
            return cachedTotal > 0 ? cachedTotal : 0;
        }
        cachedTotal = total;
        cachedTotalAt = nowMs;
        return total;
    }

    // ── Terfi / bağlama yardımcıları ──────────────────────────────────────────────

    /** Scope-başına-tek-aktif kısmi UNIQUE indekse dayalı atomik terfi. rows==1 → biz oluşturduk (kazanan). */
    private boolean insertStormIfAbsent(String scopeKey, String scopeType, List<AlertEvent> peers) {
        String nowIso = now();
        String rootCause = commonRootCause(peers);
        try {
            int rows = jdbcTemplate.update(
                    "INSERT INTO alert_storms(scope_key, scope_type, resolved, member_count, root_cause, created_at, last_re_alert_at) "
                  + "VALUES(?, ?, false, ?, ?, ?, ?) ON CONFLICT (scope_key) WHERE resolved = false DO NOTHING",
                    scopeKey, scopeType, peers.size(), rootCause, nowIso, nowIso);
            return rows == 1;
        } catch (Exception e) {
            log.warn("Storm terfi INSERT hatası ({}): {}", scopeKey, e.getMessage());
            return false;
        }
    }

    /** Penceredeki açık DOWN eşleri storm'a bağla — çağıranın kaydettiği mevcut event ({@code skipId}) hariç. */
    private void linkPeers(List<AlertEvent> peers, Long stormId, Long skipId) {
        for (AlertEvent p : peers) {
            if (Objects.equals(p.getId(), skipId)) continue;                 // mevcut event → çağıran kaydeder
            if (Objects.equals(p.getStormId(), stormId)) continue;           // zaten bağlı
            alertEventRepo.linkToStormIfOpen(p.getId(), stormId);            // koşullu — çözülmüş peer'ı diriltmez (M6)
        }
    }

    private void bumpMemberCount(AlertStorm storm) {
        int c = storm.getMemberCount() != null ? storm.getMemberCount() : 0;
        storm.setMemberCount(c + 1);
        stormRepo.save(storm);
    }

    private String commonRootCause(List<AlertEvent> peers) {
        String first = null;
        for (AlertEvent e : peers) {
            if (e.getAlertType() == null) continue;
            if (first == null) first = e.getAlertType();
            else if (!first.equals(e.getAlertType())) return "MIXED";
        }
        return first != null ? first : "MIXED";
    }

    /** Per-group modda monitörün grubunu lazy çözer (tip → uygun repo). Grupsuz/cert → null. */
    private String resolveGroup(AlertEvent event, Map<String, Object> ctx) {
        try {
            String d = event.getDomain();
            return switch (event.getAlertType()) {
                case EscalationService.TYPE_HTTP_DOWN ->
                        httpRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY ->
                        pageRepo != null ? pageRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_SCRIPTED_FAIL ->
                        scriptedRepo != null ? scriptedRepo.findFirstByNameOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_PING_DOWN ->
                        pingRepo.findFirstByHostOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_DNS_FAILURE ->
                        dnsRepo.findFirstByDomainOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_PORT_DOWN -> {
                    Integer port = intFromCtx(ctx, "port");
                    yield port != null
                            ? portRepo.findFirstByHostAndPortOrderByIdAsc(d, port).map(m -> m.getGroupName()).orElse(null)
                            : null;
                }
                case EscalationService.TYPE_KEYWORD -> {
                    Object kw = ctx != null ? ctx.get("keyword") : null;
                    yield kw != null
                            ? keywordRepo.findFirstByUrlAndKeywordOrderByIdAsc(d, kw.toString()).map(m -> m.getGroupName()).orElse(null)
                            : null;
                }
                default -> null;   // ACCESSIBILITY → cert envanteri (grup yok)
            };
        } catch (Exception e) {
            log.debug("Storm grup çözümü başarısız: {} [{}] — {}", event.getDomain(), event.getAlertType(), e.getMessage());
            return null;
        }
    }

    // ── Toplu bildirim (e-posta + webhook) ────────────────────────────────────────

    private void sendStormAlert(AlertStorm storm, List<AlertEvent> downMembers, String trigger) {
        try {
            Recipients r = resolveRecipients(downMembers);
            String scopeLabel = scopeLabel(storm);
            List<String> targets = sampleTargets(downMembers);
            int extra = Math.max(0, downMembers.size() - targets.size());
            String rootCauseLabel = rootCauseLabel(storm.getRootCause());
            String prefix = "DAILY_REALERT".equals(trigger) ? "[RE-ALERT] " : "";
            String subject = prefix + "[CertMonitor 🌩 ALARM FIRTINASI] "
                    + downMembers.size() + " monitör birden erişilemez"
                    + ("ACCOUNT".equalsIgnoreCase(storm.getScopeType()) ? "" : " — " + scopeLabel);

            String html = emailService.buildStormAlertHtml(
                    downMembers.size(), scopeLabel, rootCauseLabel,
                    storm.getCreatedAt(), targets, extra);

            String[] to = r.emails.toArray(new String[0]);
            if (to.length > 0) {
                emailService.sendHtml(to, null, subject, html, List.of());
            } else {
                log.warn("Storm #{} toplu alarm — alıcı yok, e-posta atlandı", storm.getId());
            }
            // Webhook (Teams/Slack) — birleşik distinct kontak webhook'larına özet
            String whText = downMembers.size() + " monitör birden erişilemez (" + scopeLabel + "). Kök-neden: " + rootCauseLabel + ".";
            for (Map.Entry<String, String> w : r.webhooks.entrySet()) {
                try { webhookService.send(w.getValue(), w.getKey(), subject, whText, "CRITICAL"); }
                catch (Exception ex) { log.debug("Storm webhook hatası ({}): {}", w.getKey(), ex.getMessage()); }
            }

            storm.setNotifiedTeams(String.join(", ", r.teamNames));
            storm.setMemberCount(downMembers.size());
            storm.setLastReAlertAt(now());
            stormRepo.save(storm);
        } catch (Exception e) {
            log.warn("Storm #{} toplu alarm gönderilemedi: {}", storm.getId(), e.getMessage());
        }
    }

    private void sendStormRecovery(AlertStorm storm, List<AlertEvent> recovered, List<AlertEvent> stillDown) {
        try {
            // Recovery alıcıları = tüm etkilenen üyeler (kurtulan + hâlâ-down) → herkes durumu görsün.
            List<AlertEvent> all = new ArrayList<>(recovered);
            all.addAll(stillDown);
            Recipients r = resolveRecipients(all);
            String scopeLabel = scopeLabel(storm);
            List<String> targets = sampleTargets(recovered);
            int extra = Math.max(0, recovered.size() - targets.size());
            String duration = null; // builder createdAt→resolvedAt'ten hesaplar
            String subject = "[CertMonitor ✅ ÇÖZÜLDÜ] Alarm fırtınası sona erdi — "
                    + recovered.size() + " monitör kurtarıldı"
                    + ("ACCOUNT".equalsIgnoreCase(storm.getScopeType()) ? "" : " — " + scopeLabel);

            String html = emailService.buildStormRecoveryHtml(
                    recovered.size(), stillDown.size(), scopeLabel,
                    storm.getCreatedAt(), storm.getResolvedAt(), targets, extra);

            String[] to = r.emails.toArray(new String[0]);
            if (to.length > 0) emailService.sendHtml(to, null, subject, html, List.of());
        } catch (Exception e) {
            log.warn("Storm #{} toplu recovery gönderilemedi: {}", storm.getId(), e.getMessage());
        }
    }

    /** Alıcı çözümü — EscalationService.collectTeamEmails/getContactsForLevel mantığının aynası (huniye
     *  dokunmadan; StormService kendi dispatch'ini yapar). Üyelerin takım/kontaklarını union+dedup eder. */
    private Recipients resolveRecipients(List<AlertEvent> members) {
        Set<String> seenEmail = new LinkedHashSet<>();
        List<String> emails = new ArrayList<>();
        Set<String> teamNames = new LinkedHashSet<>();
        Map<String, String> webhooks = new HashMap<>();   // url -> type (dedup)
        Map<Long, TeamInfo> teamCache = new HashMap<>();

        for (AlertEvent m : members) {
            boolean teamOnly = isTeamOnly(m.getAlertType());
            Long teamId = m.getTeamId();
            Long ugTeamId = null;
            List<EscalationContact> contacts = List.of();
            if (!teamOnly) {
                var inv = inventoryRepo.findByDomain(m.getDomain());
                if (inv.isPresent()) {
                    if (teamId == null) teamId = inv.get().getTeamId();
                    ugTeamId = inv.get().getUgTeamId();
                }
                contacts = contactsForLevel(m.getAlertLevel(), teamId);
            }
            addTeam(teamId, teamCache, seenEmail, emails, teamNames);
            addTeam(ugTeamId, teamCache, seenEmail, emails, teamNames);
            for (EscalationContact c : contacts) {
                if (c.getEmail() != null && !c.getEmail().isBlank()
                        && seenEmail.add(c.getEmail().trim().toLowerCase())) {
                    emails.add(c.getEmail().trim());
                }
                if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) {
                    webhooks.putIfAbsent(c.getWebhookUrl(), c.getWebhookType());
                }
            }
        }
        return new Recipients(emails, new ArrayList<>(teamNames), webhooks);
    }

    private void addTeam(Long teamId, Map<Long, TeamInfo> cache,
                         Set<String> seenEmail, List<String> emails, Set<String> teamNames) {
        if (teamId == null) return;
        TeamInfo info = cache.computeIfAbsent(teamId, id -> teamRepo.findById(id)
                .map(t -> new TeamInfo(t.getEmail(), t.getName())).orElse(TeamInfo.EMPTY));
        if (info.email != null && !info.email.isBlank() && seenEmail.add(info.email.trim().toLowerCase())) {
            emails.add(info.email.trim());
            if (info.name != null && !info.name.isBlank()) teamNames.add(info.name.trim());
        }
    }

    private List<EscalationContact> contactsForLevel(String level, Long teamId) {
        if (teamId != null) {
            List<EscalationContact> teamContacts = switch (level != null ? level : "") {
                case "CRITICAL" -> contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(teamId);
                case "HIGH"     -> contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(teamId, List.of("WARNING", "HIGH"));
                default         -> contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(teamId, "WARNING");
            };
            if (!teamContacts.isEmpty()) return teamContacts;
        }
        return switch (level != null ? level : "") {
            case "CRITICAL" -> contactRepo.findByActiveTrueOrderByRoleAsc();
            case "HIGH"     -> contactRepo.findByMinAlertLevelInAndActiveTrue(List.of("WARNING", "HIGH"));
            default         -> contactRepo.findByMinAlertLevelAndActiveTrue("WARNING");
        };
    }

    /** EscalationService.processConfirmedOutage'daki teamOnly mantığının down-tipleri için aynası. */
    private boolean isTeamOnly(String alertType) {
        return EscalationService.TYPE_KEYWORD.equals(alertType)
                || EscalationService.TYPE_PING_DOWN.equals(alertType)
                || EscalationService.TYPE_HTTP_DOWN.equals(alertType);
    }

    private List<String> sampleTargets(List<AlertEvent> members) {
        final int MAX = 12;
        List<String> out = new ArrayList<>();
        for (AlertEvent m : members) {
            if (out.size() >= MAX) break;
            if (m.getDomain() != null) out.add(m.getDomain());
        }
        return out;
    }

    private String scopeLabel(AlertStorm storm) {
        if ("ACCOUNT".equalsIgnoreCase(storm.getScopeType())) return "Tüm monitörler";
        return UNGROUPED.equals(storm.getScopeKey()) ? "Grupsuz" : storm.getScopeKey();
    }

    private String rootCauseLabel(String rootCause) {
        return switch (rootCause != null ? rootCause : "") {
            case EscalationService.TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
            case EscalationService.TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
            case EscalationService.TYPE_PORT_DOWN     -> "Port Kesintisi";
            case EscalationService.TYPE_PING_DOWN     -> "Ping Yanıtsız";
            case EscalationService.TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
            case EscalationService.TYPE_KEYWORD       -> "İçerik Doğrulama";
            case "MIXED"                              -> "Karışık (çok tipli)";
            default                                   -> "Kesinti";
        };
    }

    private boolean isEnabled() {
        return appSettings.getBoolean(KEY_ENABLED, true);   // default AÇIK
    }

    // ── Küçük yardımcılar ─────────────────────────────────────────────────────────

    private record Recipients(List<String> emails, List<String> teamNames, Map<String, String> webhooks) {}
    private record TeamInfo(String email, String name) { static final TeamInfo EMPTY = new TeamInfo(null, null); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Integer intFromCtx(Map<String, Object> ctx, String key) {
        Object v = ctx != null ? ctx.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        try { return v != null ? Integer.parseInt(v.toString()) : null; } catch (Exception e) { return null; }
    }

    private String now() { return ISO.format(Instant.now()); }

    // ── Dağıtık kilit (StormService lifecycle tek-yazar) — SchedulerService pattern'inin kopyası
    //    (oraya inject etmek döngüsel bağımlılık yaratır: EscalationService→StormService→SchedulerService→EscalationService). ──
    private boolean tryLock() {
        String lockName = "storm-sweep";
        try {
            String nowIso = now();
            String until = ISO.format(Instant.now().plusSeconds(LOCK_TTL_SECONDS));
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", lockName, nowIso);
            jdbcTemplate.update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                    lockName, INSTANCE_ID, until);
            return true;
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("UNIQUE") || e.getMessage().contains("unique")
                    || e.getMessage().contains("duplicate"))) {
                return false;   // başka pod yönetiyor
            }
            log.warn("Storm kilit tablosu erişilemez (HA degraded): {}", e.getMessage());
            return true;        // degrade: tek instance güvenli
        }
    }

    private void releaseLock() {
        try {
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?", "storm-sweep", INSTANCE_ID);
        } catch (Exception e) {
            log.debug("Storm kilidi bırakılamadı: {}", e.getMessage());
        }
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
