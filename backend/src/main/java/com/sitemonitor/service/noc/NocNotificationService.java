package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.mail.MailDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7/24 İzleme Ekibi (NOC) GÖNDERİM kararı ve teslimi — TEK servis (2026-09-27).
 *
 * <p><b>Neden tek servis:</b> takım e-postası alarm ailesinde üç ayrı yerde çözülüyor
 * ({@code EscalationService} hunisi, {@code StormService}, {@code IncidentNotificationService}; bkz.
 * {@code NotificationGroupService} javadoc'u). NOC kuralları her yolda ayrı yazılsaydı zamanla saparlardı. Bu
 * servis bireysel alarm hunisinden ({@link #onAlertDispatched}, {@link #onAlertResolved}) ve fırtına yolundan
 * ({@link #onStormDispatched}, {@link #onStormRecovered}) çağrılır. Olay kaydı bildirimi
 * ({@code IncidentNotificationService}) bir İZLEME alarmı değildir (izlemesi, dolayısıyla {@code noc_notify}'ı
 * yoktur) → NOC'a gitmez (sözleşme "Backend sapmaları").
 *
 * <p><b>Kurallar</b> (sözleşme §Gönderim):
 * <ol>
 *   <li>Tetik: takım e-postasını üreten aynı açılış (INITIAL / ESCALATION / DAILY_REALERT); elle yeniden
 *       bildirim (MANUAL) NOC'a gitmez. Önem ≥ {@code min_level}; erişilebilirlik kesintisi (DOWN) = KRİTİK.</li>
 *   <li>Takımın sessiz saatleri, üye seviye eşikleri, kişisel susturmalar ve izlemenin takım e-posta kanalı
 *       NOC'u ETKİLEMEZ (7/24, ayrı açık onay). Bakım penceresi ve global e-posta kapatma ETKİLER.</li>
 *   <li>Alarm başına en fazla BİR açılış ({@link NocDelivery} tekil anahtarı); çözüldü e-postası yalnız
 *       açılış gittiyse ve {@code send_resolve} açıksa.</li>
 *   <li>Fırtınada TEK toplu e-posta; fırtınaya giren her üye "açılışı gitti" işaretlenir.</li>
 * </ol>
 * Hiçbir hata çağıran yola yayılmaz — NOC arızası takımın alarmını ASLA düşürmez (çağıranlar da try/catch sarar).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocNotificationService {

    public static final String TRIGGER_OPEN = "NOC_OPEN";
    public static final String TRIGGER_RESOLVE = "NOC_RESOLVE";
    public static final String TRIGGER_STORM = "NOC_STORM";
    public static final String TRIGGER_STORM_RESOLVE = "NOC_STORM_RESOLVE";
    public static final String TRIGGER_TEST = "NOC_TEST";
    /** Posta günlüğü kategorisi ({@code notification_logs.recipient_role}). */
    public static final String LOG_ROLE = "NOC";
    public static final String VIA_STORM = "SENT_VIA_STORM";

    public static final String TRIGGER_STORM_UPDATE = "NOC_STORM_UPDATE";

    private static final Set<String> OPEN_TRIGGERS = Set.of("INITIAL", "ESCALATION", "DAILY_REALERT");
    /** "Gönderiliyor" sahipliği bu süreden eskiyse pod ölmüş sayılır; teslim yeniden denenebilir. */
    static final java.time.Duration STALE_SENDING = java.time.Duration.ofMinutes(10);
    /** Fırtına güncellemesi arası en az aralık (fırtına başına). */
    static final java.time.Duration STORM_UPDATE_MIN_GAP = java.time.Duration.ofMinutes(5);
    private static final int MAX_STORM_TEAMS = 10;
    private static final Pattern CTX_MONITOR_ID = Pattern.compile("\"monitor_id\"[ ]*:[ ]*\"?([0-9]+)");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocConfigService config;
    private final NocGroupService groups;
    private final NocCallListService callLists;
    private final NocMonitorDirectory directory;
    private final NocDeliveryRepository deliveries;
    private final EmailNotificationService email;
    private final NotificationLogRepository notificationLogs;
    private final MaintenanceService maintenance;
    private final ActivityLogService activityLog;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String fallbackBaseUrl = "";

    /** Zaman kaynağı — testte ilerletilebilir (bayat "gönderiliyor", güncelleme aralığı). */
    java.time.Clock clock = java.time.Clock.systemUTC();

    private String now() {
        return ISO.format(clock.instant());
    }

    // ── Karar yardımcıları (kapsam ekranı ile AYNI) ──────────────────────────

    /** NOC açısından seviye: erişilebilirlik kesintisi (DOWN ailesi) her zaman KRİTİK. */
    public static String nocLevel(String alertType, String level) {
        return alertType != null && EscalationService.DOWN_ALERT_TYPES.contains(alertType) ? "CRITICAL" : level;
    }

    /** Açılışı "gitmiş" sayılan durum (çözüldü e-postasının ön koşulu). */
    static boolean sent(String status) {
        return status != null && (status.startsWith("SENT") || status.startsWith("QUEUED_RETRY"));
    }

    /** Yeni deneme ENGELLENİR mi — gitmiş ya da şu an gönderiliyor. FAILED/SKIPPED sonraki tetikte yeniden denenir. */
    static boolean blocking(String status) {
        return sent(status) || "SENDING".equals(status);
    }

    /**
     * Satır bazlı karar: gitmişse engeller; "gönderiliyor" yalnız {@link #STALE_SENDING}'den TAZEYSE engeller —
     * sahiplenme ile bitiş arasında pod ölürse satır sonsuza kadar kilitli kalmasın.
     */
    boolean blocking(NocDelivery d) {
        if (d == null) return false;
        if (sent(d.getStatus())) return true;
        if (!"SENDING".equals(d.getStatus())) return false;
        java.time.Instant at = parseIso(d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt());
        return at == null || at.isAfter(clock.instant().minus(STALE_SENDING));
    }

    private static java.time.Instant parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 1) Bireysel alarm açılışı ────────────────────────────────────────────

    /**
     * {@code EscalationService.sendCombinedAlert} başında çağrılır — takım e-postasının atlanıp atlanmayacağından
     * BAĞIMSIZ. {@code teamId} alarmın sahibi takım (SY).
     */
    public void onAlertDispatched(AlertEvent event, Long teamId, String domain, String level, String alertType,
                                  String trigger, Map<String, Object> ctx) {
        try {
            if (event == null || event.getId() == null || !OPEN_TRIGGERS.contains(trigger)) return;
            NocType type = NocType.forAlertType(alertType);
            if (type == null) return;
            NocConfigService.Config cfg = config.get();
            if (!cfg.typeEnabled(type)) return;
            if (!cfg.meetsMinLevel(nocLevel(alertType, level))) return;
            if (domain != null && maintenance.isUnderMaintenance(domain)) return;

            String key = "alert:" + event.getId() + ":" + NocDelivery.OPEN;
            Optional<NocDelivery> prev = deliveries.findByDedupeKey(key);
            if (prev.isPresent() && blocking(prev.get())) return;

            Object monitorId = ctx == null ? null : ctx.get("monitor_id");
            NocMonitorDirectory.Row row = directory.forAlert(type, domain, monitorId);
            if (row == null || !row.nocNotify() || !row.active()) return;
            NocGroupService.Targets targets = groups.resolveTargets(row.nocGroupIds());
            if (!targets.any()) {
                log.warn("7/24 bildirimi gönderilemedi: aktif 7/24 grubu yok (alarm {} [{}])", event.getId(), alertType);
                return;
            }
            Long owner = teamId != null ? teamId : (event.getTeamId() != null ? event.getTeamId() : row.teamId());

            NocDelivery d = prev.orElseGet(NocDelivery::new);
            if (!claim(d, key, event.getId(), null, NocDelivery.OPEN)) return;

            NocMailComposer.TeamBlock team = callLists.forMail(owner);
            String base = baseUrl();
            NocMailComposer.AlarmInfo info = new NocMailComposer.AlarmInfo(
                    nocLevel(alertType, level), typeLabel(type, alertType),
                    NocMailComposer.problemLabel(alertType, event.getDaysRemaining()),
                    row.target(), row.name(), event.getCreatedAt(), errorOf(ctx, event),
                    team, cfg.callInstructions(), monitorUrl(base, type, row, alertType), alertUrl(base, event.getId()),
                    callLogUrl(base, event.getId()), targets.groupNames());
            MailDoc.Mail mail = NocMailComposer.open(info);
            String subject = NocMailComposer.openSubject(nocLevel(alertType, level), row.target(), team.teamName());
            String status = send(targets.emails(), subject, mail,
                    com.sitemonitor.service.BrandMailAssets.variantForLevel(nocLevel(alertType, level)));
            finish(d, status, type, row.id(), owner, targets, subject);
            // Günlüğe MASKELİ kopya: alarmı görebilen takım üyesi arama listesinin numaralarını okuyamasın.
            writeLog(event.getId(), targets, subject, NocMailComposer.open(NocMailComposer.redactForLog(info)).html(),
                    status, TRIGGER_OPEN);
            activityLog.recordLifecycle(activityType(type, alertType), row.id(), row.name(), row.target(), owner,
                    "NOC_NOTIFIED", "system", "7/24 · " + NocMailComposer.levelTr(nocLevel(alertType, level))
                            + " · " + String.join(", ", targets.groupNames()) + " · " + status);
            log.info("7/24 bildirimi: alarm={} tür={} grup={} adres sayısı={} durum={}",
                    event.getId(), type, targets.groupIds(), targets.emails().size(), status);
        } catch (Exception e) {
            log.warn("7/24 bildirimi atlandı (takım alarmı etkilenmedi): alarm={} — {}",
                    event == null ? null : event.getId(), e.toString());
        }
    }

    // ── 2) Bireysel alarm çözümü ─────────────────────────────────────────────

    /** {@code EscalationService.sendResolutionNotification} başında çağrılır. */
    public void onAlertResolved(AlertEvent event) {
        try {
            if (event == null || event.getId() == null) return;
            NocConfigService.Config cfg = config.get();
            if (!cfg.sendResolve()) return;
            NocDelivery open = deliveries.findByDedupeKey("alert:" + event.getId() + ":" + NocDelivery.OPEN).orElse(null);
            if (open == null || !sent(open.getStatus())) return;   // açılış NOC'a gitmediyse çözüm de gitmez
            String key = "alert:" + event.getId() + ":" + NocDelivery.RESOLVE;
            Optional<NocDelivery> prev = deliveries.findByDedupeKey(key);
            if (prev.isPresent() && blocking(prev.get())) return;

            NocGroupService.Targets targets = groups.resolveTargets(open.getGroupIds());
            if (!targets.any()) return;
            NocType type = NocType.parse(open.getMonitorType());
            NocMonitorDirectory.Row row = type == null || open.getMonitorId() == null ? null : directory.find(type, open.getMonitorId());
            String target = row != null ? row.target() : event.getDomain();
            String name = row != null ? row.name() : null;

            NocDelivery d = prev.orElseGet(NocDelivery::new);
            if (!claim(d, key, event.getId(), null, NocDelivery.RESOLVE)) return;
            NocMailComposer.TeamBlock team = callLists.forMail(open.getTeamId());
            String base = baseUrl();
            MailDoc.Mail mail = NocMailComposer.resolved(new NocMailComposer.ResolvedInfo(
                    type == null ? null : typeLabel(type, event.getAlertType()),
                    NocMailComposer.problemLabel(event.getAlertType(), event.getDaysRemaining()),
                    target, name, event.getCreatedAt(), event.getResolvedAt(), event.getResolvedBy(), team,
                    row == null ? null : monitorUrl(base, type, row, event.getAlertType()), alertUrl(base, event.getId())));
            String subject = NocMailComposer.resolvedSubject(target, team.teamName());
            String status = send(targets.emails(), subject, mail);
            finish(d, status, type, open.getMonitorId(), open.getTeamId(), targets, subject);
            writeLog(event.getId(), targets, subject, mail.html(), status, TRIGGER_RESOLVE);
            if (type != null && open.getMonitorId() != null) {
                activityLog.recordLifecycle(activityType(type, event.getAlertType()), open.getMonitorId(), name, target,
                        open.getTeamId(), "NOC_RESOLVED", "system", "7/24 · çözüldü · " + status);
            }
        } catch (Exception e) {
            log.warn("7/24 çözüm bildirimi atlandı: alarm={} — {}", event == null ? null : event.getId(), e.toString());
        }
    }

    // ── 3) Fırtına ───────────────────────────────────────────────────────────

    /** Fırtınaya giren, 7/24 kapsamındaki üye: alarm + izleme satırı + etkin takım. */
    record StormMember(AlertEvent event, NocType type, NocMonitorDirectory.Row row, Long teamId) {}

    /** Tek anlık görüntüyle (fırtına değerlendirmesi başına bir okuma) kapsanan üyeler. */
    List<StormMember> eligibleStormMembers(List<AlertEvent> members, NocConfigService.Config cfg) {
        NocMonitorDirectory.Snapshot snap = directory.snapshot();
        List<StormMember> out = new ArrayList<>();
        for (AlertEvent m : members) {
            if (m == null || m.getId() == null) continue;
            NocType type = NocType.forAlertType(m.getAlertType());
            if (type == null || !cfg.typeEnabled(type)) continue;
            if (!cfg.meetsMinLevel(nocLevel(m.getAlertType(), m.getAlertLevel()))) continue;
            if (m.getDomain() != null && maintenance.isUnderMaintenance(m.getDomain())) continue;
            NocMonitorDirectory.Row row = snap.forAlert(type, m.getDomain(), monitorIdOf(m.getContextJson()));
            if (row == null || !row.nocNotify() || !row.active()) continue;
            out.add(new StormMember(m, type, row, m.getTeamId() != null ? m.getTeamId() : row.teamId()));
        }
        return out;
    }

    /**
     * Fırtına toplu alarmı ({@code StormService.sendStormAlert}). Fırtına başına TEK NOC e-postası; açılışta hiç
     * kapsanan üye yoksa bir sonraki toplu tekrar (DAILY_REALERT) yeniden bakar.
     */
    public void onStormDispatched(AlertStorm storm, List<AlertEvent> downMembers, String scopeLabel, String rootCause) {
        try {
            if (storm == null || storm.getId() == null || downMembers == null || downMembers.isEmpty()) return;
            String key = "storm:" + storm.getId() + ":" + NocDelivery.OPEN;
            Optional<NocDelivery> prev = deliveries.findByDedupeKey(key);
            if (prev.isPresent() && blocking(prev.get())) return;
            NocConfigService.Config cfg = config.get();
            List<StormMember> eligible = eligibleStormMembers(downMembers, cfg);
            if (eligible.isEmpty()) return;

            List<NocNotificationGroup> all = groups.list();
            NocGroupService.Targets targets = union(eligible.stream().map(s -> s.row().nocGroupIds()).toList(), all);
            if (!targets.any()) {
                log.warn("7/24 fırtına bildirimi gönderilemedi: aktif 7/24 grubu yok (fırtına #{})", storm.getId());
                return;
            }
            NocDelivery d = prev.orElseGet(NocDelivery::new);
            if (!claim(d, key, null, storm.getId(), NocDelivery.OPEN)) return;

            String base = baseUrl();
            Map<Long, NocMailComposer.TeamBlock> blocks = teamBlocks(eligible);
            List<NocMailComposer.Member> lines = memberLines(eligible, blocks, base);
            String coverage = base.isEmpty() ? null : base + "/?tab=noc";
            MailDoc.Mail mail = NocMailComposer.storm(downMembers.size(), scopeLabel, rootCause, storm.getCreatedAt(),
                    lines, new ArrayList<>(blocks.values()), cfg.callInstructions(), coverage, targets.groupNames());
            String subject = NocMailComposer.stormSubject(downMembers.size());
            String status = send(targets.emails(), subject, mail, com.sitemonitor.service.BrandMailAssets.variantForLevel("CRITICAL"));
            finish(d, status, null, null, null, targets, subject);
            writeLog(eligible.get(0).event().getId(), targets, subject,
                    NocMailComposer.storm(downMembers.size(), scopeLabel, rootCause, storm.getCreatedAt(), lines,
                            redacted(blocks), cfg.callInstructions(), coverage, targets.groupNames()).html(),
                    status, TRIGGER_STORM);
            if (sent(status)) {
                // Üyelerin açılışı fırtına e-postasıyla GİTTİ: tek başına açılış tekrar gitmez, sonradan tek başına
                // çözülürse NOC kapanışı da alır.
                for (StormMember s : eligible) {
                    markMember(s.event().getId(), NocDelivery.OPEN, s, targets, storm.getId(), subject);
                    activityLog.recordLifecycle(activityType(s.type(), s.event().getAlertType()), s.row().id(), s.row().name(),
                            s.row().target(), s.teamId(), "NOC_NOTIFIED", "system", "7/24 · alarm fırtınası #" + storm.getId());
                }
            }
            log.info("7/24 fırtına bildirimi: fırtına={} kapsanan üye={} adres sayısı={} durum={}",
                    storm.getId(), eligible.size(), targets.emails().size(), status);
        } catch (Exception e) {
            log.warn("7/24 fırtına bildirimi atlandı: fırtına={} — {}", storm == null ? null : storm.getId(), e.toString());
        }
    }

    /**
     * Fırtına yaşam döngüsü tik'i ({@code StormService.lifecycleOne}, fırtına SÜRERKEN). İki iş:
     * <ol>
     *   <li>Fırtınanın NOC açılışı HENÜZ gitmediyse (açılışta kapsanan üye yoktu) ve artık kapsanan bir üye varsa
     *       açılış şimdi gider ({@link #onStormDispatched}; tekil anahtar tekrarı önler).</li>
     *   <li>Açılış gittiyse: fırtınaya SONRADAN katılan (bireysel alarmı fırtına bastırdığı için NOC'a hiç ulaşmamış)
     *       kapsanan üyeler için TOPLU güncelleme — yalnız {@code alert:<id>:OPEN} izi olmayan üyeler; fırtına başına
     *       en fazla {@link #STORM_UPDATE_MIN_GAP}'te bir (anahtar {@code storm:<id>:UPD:<5dk-dilimi>} + son güncelleme
     *       zamanı). Gidenler "fırtınayla gitti" işaretlenir → tek başına çözülürse ÇÖZÜLDÜ alır.</li>
     * </ol>
     */
    public void onStormTick(AlertStorm storm, List<AlertEvent> activeMembers, String scopeLabel, String rootCause) {
        try {
            if (storm == null || storm.getId() == null || activeMembers == null || activeMembers.isEmpty()) return;
            NocDelivery open = deliveries.findByDedupeKey("storm:" + storm.getId() + ":" + NocDelivery.OPEN).orElse(null);
            if (open == null || !sent(open.getStatus())) {
                if (open == null || !blocking(open)) onStormDispatched(storm, activeMembers, scopeLabel, rootCause);
                return;
            }
            java.time.Instant nowI = clock.instant();
            NocDelivery last = deliveries.findTopByStormIdAndPhaseOrderByIdDesc(storm.getId(), NocDelivery.UPDATE).orElse(null);
            java.time.Instant lastAt = last == null ? null : parseIso(last.getCreatedAt());
            if (lastAt != null && lastAt.isAfter(nowI.minus(STORM_UPDATE_MIN_GAP))) return;

            NocConfigService.Config cfg = config.get();
            List<StormMember> eligible = eligibleStormMembers(activeMembers, cfg);
            if (eligible.isEmpty()) return;
            List<String> keys = new ArrayList<>();
            for (StormMember m : eligible) keys.add("alert:" + m.event().getId() + ":" + NocDelivery.OPEN);
            Map<String, NocDelivery> have = new LinkedHashMap<>();
            for (NocDelivery x : deliveries.findByDedupeKeyIn(keys)) have.put(x.getDedupeKey(), x);
            List<StormMember> fresh = new ArrayList<>();
            for (StormMember m : eligible)
                if (!blocking(have.get("alert:" + m.event().getId() + ":" + NocDelivery.OPEN))) fresh.add(m);
            if (fresh.isEmpty()) return;

            NocGroupService.Targets targets = union(fresh.stream().map(x -> x.row().nocGroupIds()).toList(), groups.list());
            if (!targets.any()) return;
            String key = "storm:" + storm.getId() + ":UPD:" + (nowI.getEpochSecond() / STORM_UPDATE_MIN_GAP.getSeconds());
            if (deliveries.findByDedupeKey(key).isPresent()) return;
            NocDelivery d = new NocDelivery();
            if (!claim(d, key, null, storm.getId(), NocDelivery.UPDATE)) return;

            String base = baseUrl();
            Map<Long, NocMailComposer.TeamBlock> blocks = teamBlocks(fresh);
            List<NocMailComposer.Member> lines = memberLines(fresh, blocks, base);
            String coverage = base.isEmpty() ? null : base + "/?tab=noc";
            MailDoc.Mail mail = NocMailComposer.stormUpdate(activeMembers.size(), scopeLabel, storm.getCreatedAt(), lines,
                    new ArrayList<>(blocks.values()), cfg.callInstructions(), coverage, targets.groupNames());
            String subject = NocMailComposer.stormUpdateSubject(fresh.size());
            String status = send(targets.emails(), subject, mail, com.sitemonitor.service.BrandMailAssets.variantForLevel("CRITICAL"));
            finish(d, status, null, null, null, targets, subject);
            writeLog(fresh.get(0).event().getId(), targets, subject,
                    NocMailComposer.stormUpdate(activeMembers.size(), scopeLabel, storm.getCreatedAt(), lines,
                            redacted(blocks), cfg.callInstructions(), coverage, targets.groupNames()).html(),
                    status, TRIGGER_STORM_UPDATE);
            if (sent(status)) {
                for (StormMember m : fresh) {
                    markMember(m.event().getId(), NocDelivery.OPEN, m, targets, storm.getId(), subject);
                    activityLog.recordLifecycle(activityType(m.type(), m.event().getAlertType()), m.row().id(), m.row().name(),
                            m.row().target(), m.teamId(), "NOC_NOTIFIED", "system", "7/24 · fırtına güncellemesi #" + storm.getId());
                }
            }
            log.info("7/24 fırtına güncellemesi: fırtına={} yeni kapsanan üye={} adres sayısı={} durum={}",
                    storm.getId(), fresh.size(), targets.emails().size(), status);
        } catch (Exception e) {
            log.warn("7/24 fırtına güncellemesi atlandı: fırtına={} — {}", storm == null ? null : storm.getId(), e.toString());
        }
    }

    /** Üyelerin takım bölümleri (en çok {@link #MAX_STORM_TEAMS} takım; telefon DAHİL — gönderim içindir). */
    private Map<Long, NocMailComposer.TeamBlock> teamBlocks(List<StormMember> members) {
        Map<Long, NocMailComposer.TeamBlock> blocks = new LinkedHashMap<>();
        for (StormMember s : members) {
            if (s.teamId() == null || blocks.containsKey(s.teamId())) continue;
            if (blocks.size() >= MAX_STORM_TEAMS) break;
            blocks.put(s.teamId(), callLists.forMail(s.teamId()));
        }
        return blocks;
    }

    private static List<NocMailComposer.TeamBlock> redacted(Map<Long, NocMailComposer.TeamBlock> blocks) {
        List<NocMailComposer.TeamBlock> out = new ArrayList<>();
        for (NocMailComposer.TeamBlock b : blocks.values()) out.add(NocMailComposer.redactForLog(b));
        return out;
    }

    private static List<NocMailComposer.Member> memberLines(List<StormMember> members,
                                                            Map<Long, NocMailComposer.TeamBlock> blocks, String base) {
        List<NocMailComposer.Member> lines = new ArrayList<>();
        for (StormMember s : members) {
            NocMailComposer.TeamBlock tb = s.teamId() == null ? null : blocks.get(s.teamId());
            lines.add(new NocMailComposer.Member(s.row().target(), typeLabel(s.type(), s.event().getAlertType()),
                    tb == null ? null : tb.teamName(), monitorUrl(base, s.type(), s.row(), s.event().getAlertType()),
                    callLogUrl(base, s.event().getId())));
        }
        return lines;
    }

    /** Fırtına çözülünce ({@code StormService.sendStormRecovery}) — açılışı NOC'a gitmiş kurtulan üyeler için TEK e-posta. */
    public void onStormRecovered(AlertStorm storm, List<AlertEvent> recovered, List<AlertEvent> stillDown) {
        try {
            if (storm == null || storm.getId() == null || recovered == null || recovered.isEmpty()) return;
            NocConfigService.Config cfg = config.get();
            if (!cfg.sendResolve()) return;
            String key = "storm:" + storm.getId() + ":" + NocDelivery.RESOLVE;
            Optional<NocDelivery> prev = deliveries.findByDedupeKey(key);
            if (prev.isPresent() && blocking(prev.get())) return;

            Map<String, NocDelivery> rows = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>();
            for (AlertEvent e : recovered) if (e.getId() != null) {
                keys.add("alert:" + e.getId() + ":" + NocDelivery.OPEN);
                keys.add("alert:" + e.getId() + ":" + NocDelivery.RESOLVE);
            }
            for (AlertEvent e : stillDown == null ? List.<AlertEvent>of() : stillDown) if (e.getId() != null)
                keys.add("alert:" + e.getId() + ":" + NocDelivery.OPEN);
            if (!keys.isEmpty()) for (NocDelivery d : deliveries.findByDedupeKeyIn(keys)) rows.put(d.getDedupeKey(), d);

            List<AlertEvent> mine = new ArrayList<>();
            List<String> groupCsvs = new ArrayList<>();
            for (AlertEvent e : recovered) {
                NocDelivery open = rows.get("alert:" + e.getId() + ":" + NocDelivery.OPEN);
                NocDelivery res = rows.get("alert:" + e.getId() + ":" + NocDelivery.RESOLVE);
                if (open == null || !sent(open.getStatus()) || (res != null && blocking(res))) continue;
                mine.add(e);
                groupCsvs.add(open.getGroupIds());
            }
            if (mine.isEmpty()) return;
            List<AlertEvent> stillMine = new ArrayList<>();
            for (AlertEvent e : stillDown == null ? List.<AlertEvent>of() : stillDown) {
                NocDelivery open = rows.get("alert:" + e.getId() + ":" + NocDelivery.OPEN);
                if (open != null && sent(open.getStatus())) stillMine.add(e);
            }
            NocGroupService.Targets targets = union(groupCsvs, groups.list());
            if (!targets.any()) return;
            NocDelivery d = prev.orElseGet(NocDelivery::new);
            if (!claim(d, key, null, storm.getId(), NocDelivery.RESOLVE)) return;

            MailDoc.Mail mail = NocMailComposer.stormResolved(members(mine, rows), members(stillMine, rows),
                    storm.getCreatedAt(), storm.getResolvedAt());
            String subject = NocMailComposer.stormResolvedSubject(mine.size());
            String status = send(targets.emails(), subject, mail);
            finish(d, status, null, null, null, targets, subject);
            writeLog(mine.get(0).getId(), targets, subject, mail.html(), status, TRIGGER_STORM_RESOLVE);
            if (sent(status)) {
                for (AlertEvent e : mine) {
                    NocDelivery open = rows.get("alert:" + e.getId() + ":" + NocDelivery.OPEN);
                    markMember(e.getId(), NocDelivery.RESOLVE,
                            new StormMember(e, NocType.parse(open.getMonitorType()), null, open.getTeamId()),
                            targets, storm.getId(), subject);
                }
            }
        } catch (Exception e) {
            log.warn("7/24 fırtına çözüm bildirimi atlandı: fırtına={} — {}", storm == null ? null : storm.getId(), e.toString());
        }
    }

    private List<NocMailComposer.Member> members(List<AlertEvent> list, Map<String, NocDelivery> rows) {
        List<NocMailComposer.Member> out = new ArrayList<>();
        for (AlertEvent e : list) {
            NocDelivery open = rows.get("alert:" + e.getId() + ":" + NocDelivery.OPEN);
            NocType type = open == null ? null : NocType.parse(open.getMonitorType());
            out.add(new NocMailComposer.Member(e.getDomain(), type == null ? null : typeLabel(type, e.getAlertType()), null, null, null));
        }
        return out;
    }

    // ── 4) Test e-postası ────────────────────────────────────────────────────

    /** Bir hata ve adresi (test sonucu). */
    public record Failure(String email, String error) {}

    public record TestResult(int sent, List<Failure> failed) {}

    /** Grubun HER adresine ayrı test e-postası — başarısızlık adres bazında raporlanır. Global e-posta kapalıysa hepsi başarısız. */
    public TestResult sendTest(NocNotificationGroup g) {
        List<String> emails = NocGroupService.emailsOf(g);
        MailDoc.Mail mail = NocMailComposer.test(g.getName(), config.get().callInstructions());
        String subject = NocMailComposer.testSubject(g.getName());
        int ok = 0;
        List<Failure> failed = new ArrayList<>();
        for (String e : emails) {
            String status = send(List.of(e), subject, mail);
            if (sent(status)) ok++;
            else failed.add(new Failure(e, status == null ? "bilinmeyen hata" : status));
            writeLog(0L, new NocGroupService.Targets(List.of(g), List.of(e), "MONITOR"), subject, mail.html(),
                    status, TRIGGER_TEST);
        }
        return new TestResult(ok, failed);
    }

    // ── Teslim altyapısı ─────────────────────────────────────────────────────

    /** Tekil anahtarı "gönderiliyor" durumuyla sahiplenir; yarışı kaybeden (anahtar başkasında) false döner. */
    private boolean claim(NocDelivery d, String key, Long alertId, Long stormId, String phase) {
        String now = now();
        d.setDedupeKey(key);
        d.setAlertEventId(alertId);
        d.setStormId(stormId);
        d.setPhase(phase);
        d.setStatus("SENDING");
        if (d.getCreatedAt() == null) d.setCreatedAt(now);
        d.setUpdatedAt(now);
        try {
            deliveries.saveAndFlush(d);
            return true;
        } catch (DataIntegrityViolationException race) {
            log.debug("7/24 teslimi başka iş parçacığında: {}", key);
            return false;
        }
    }

    private void finish(NocDelivery d, String status, NocType type, Long monitorId, Long teamId,
                        NocGroupService.Targets targets, String subject) {
        d.setStatus(status == null ? "FAILED: bilinmeyen" : status);
        if (type != null) d.setMonitorType(type.name());
        if (monitorId != null) d.setMonitorId(monitorId);
        if (teamId != null) d.setTeamId(teamId);
        d.setGroupIds(NocGroupIds.format(targets.groupIds()));
        d.setGroupNames(clip(String.join(", ", targets.groupNames()), 500));
        d.setRecipientCount(targets.emails().size());
        d.setSubject(subject);
        d.setUpdatedAt(now());
        deliveries.save(d);
    }

    /** Fırtına e-postasına giren üyenin alarm satırı (açılış/çözüm "fırtınayla gitti"). */
    private void markMember(Long alertId, String phase, StormMember s, NocGroupService.Targets targets,
                            Long stormId, String subject) {
        String key = "alert:" + alertId + ":" + phase;
        try {
            NocDelivery d = deliveries.findByDedupeKey(key).orElseGet(NocDelivery::new);
            if (d.getId() != null && blocking(d)) return;
            String now = now();
            d.setDedupeKey(key);
            d.setAlertEventId(alertId);
            d.setStormId(stormId);
            d.setPhase(phase);
            d.setStatus(VIA_STORM);
            if (s.type() != null) d.setMonitorType(s.type().name());
            if (s.row() != null) d.setMonitorId(s.row().id());
            if (s.teamId() != null) d.setTeamId(s.teamId());
            if (d.getGroupIds() == null) d.setGroupIds(NocGroupIds.format(targets.groupIds()));
            d.setGroupNames(clip(String.join(", ", targets.groupNames()), 500));
            d.setRecipientCount(targets.emails().size());
            d.setSubject(subject);
            if (d.getCreatedAt() == null) d.setCreatedAt(now);
            d.setUpdatedAt(now);
            deliveries.save(d);
        } catch (Exception e) {
            log.debug("7/24 fırtına üye işareti yazılamadı ({}): {}", key, e.toString());
        }
    }

    private String send(List<String> to, String subject, MailDoc.Mail mail) {
        return send(to, subject, mail, "ok");
    }

    /**
     * Global e-posta kapatma ({@code SmtpSettings.enabled=false}) BURADA uygulanır ({@code force=false} →
     * {@code SKIPPED_DISABLED}). Logo varyantı önemle eşleşir (kritik → kırmızı yapraklar), alarm e-postasıyla aynı.
     */
    private String send(List<String> to, String subject, MailDoc.Mail mail, String logoVariant) {
        List<EmailNotificationService.InlineImage> inline = new ArrayList<>();
        EmailNotificationService.InlineImage logo = email.brandLogo(logoVariant);
        if (logo != null) inline.add(logo);
        return email.sendHtml(to.toArray(new String[0]), null, subject, mail.html(), mail.text(),
                inline.isEmpty() ? null : inline, false, null);
    }

    /**
     * Posta günlüğü satırı — kategori {@code recipient_role = NOC}; tetik türü ayırır. Satır alarmın kimliğini taşıdığı
     * için alarmı gören HERKES okuyabilir: gövde MASKELİ gelir (çağıran {@code redactForLog} ile kurar; burada
     * {@link NocLogRedaction#scrubHtml} ikinci kez uygulanır), alıcı alanına ADRES değil grup ADLARI + adres SAYISI yazılır.
     */
    private void writeLog(Long alertEventId, NocGroupService.Targets targets, String subject, String html,
                          String status, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(alertEventId == null ? 0L : alertEventId);
            n.setSentAt(now());
            n.setRecipientName("7/24 İzleme Ekibi");
            n.setRecipientEmail(logRecipients(targets));
            n.setRecipientRole(LOG_ROLE);
            n.setSubject(subject);
            n.setMessage(NocLogRedaction.scrubHtml(html));
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(email.getEmailFrom());
            notificationLogs.save(n);
        } catch (Exception e) {
            log.warn("7/24 posta günlüğü yazılamadı: {}", e.getMessage());
        }
    }

    /** "NOC Ana, NOC Yedek (3 adres)" — adres yok. */
    static String logRecipients(NocGroupService.Targets targets) {
        return clip(String.join(", ", targets.groupNames()), 400) + " (" + targets.emails().size() + " adres)";
    }

    // ── Küçük yardımcılar ────────────────────────────────────────────────────

    private static NocGroupService.Targets union(List<String> groupCsvs, List<NocNotificationGroup> all) {
        Map<Long, NocNotificationGroup> picked = new LinkedHashMap<>();
        for (String csv : groupCsvs)
            for (NocNotificationGroup g : NocGroupService.resolveTargets(csv, all).groups()) picked.putIfAbsent(g.getId(), g);
        List<NocNotificationGroup> list = new ArrayList<>(picked.values());
        Set<String> seen = new LinkedHashSet<>();
        List<String> emails = new ArrayList<>();
        for (NocNotificationGroup g : list)
            for (String e : NocGroupService.emailsOf(g)) if (seen.add(e.toLowerCase(java.util.Locale.ROOT))) emails.add(e);
        return new NocGroupService.Targets(list, emails, list.isEmpty() ? "NONE" : "MONITOR");
    }

    static Long monitorIdOf(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return null;
        Matcher m = CTX_MONITOR_ID.matcher(contextJson);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static String typeLabel(NocType type, String alertType) {
        if (type == NocType.SSL && EscalationService.TYPE_ACCESSIBILITY.equals(alertType)) return "Erişilebilirlik (Uptime)";
        return type.labelTr;
    }

    static String activityType(NocType type, String alertType) {
        if (type == NocType.SSL && EscalationService.TYPE_ACCESSIBILITY.equals(alertType)) return ActivityLogService.UPTIME;
        return type.activityType;
    }

    private static String errorOf(Map<String, Object> ctx, AlertEvent event) {
        if (ctx != null) {
            for (String k : new String[]{"error", "detail", "error_message"}) {
                Object v = ctx.get(k);
                if (v != null && !v.toString().isBlank() && !"null".equals(v.toString())) return v.toString();
            }
        }
        return event.getMessage();
    }

    String baseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", fallbackBaseUrl);
        return url == null || url.isBlank() ? "" : url.trim().replaceAll("/+$", "");
    }

    static String monitorUrl(String base, NocType type, NocMonitorDirectory.Row row, String alertType) {
        if (base == null || base.isEmpty() || row == null) return null;
        if (type == NocType.SSL) {
            String tab = EscalationService.TYPE_ACCESSIBILITY.equals(alertType) ? "status" : type.tab;
            return base + "/?tab=" + tab + "&domain=" + URLEncoder.encode(row.name(), StandardCharsets.UTF_8);
        }
        return base + "/?tab=" + type.tab + "&monitor=" + row.id();
    }

    static String alertUrl(String base, Long alertId) {
        if (base == null || base.isEmpty() || alertId == null) return null;
        return base + "/?tab=incidents&incident=" + alertId;
    }

    /**
     * 7/24 arama kaydı derin bağlantısı (sözleşme "Arama kaydı" eki): uyarı geçmişinde o uyarı açılır, arama kaydı
     * formu odakta. Taban adres boşsa null — göreli bağlantı e-postada tıklanamaz (MailCta ile aynı kural).
     */
    static String callLogUrl(String base, Long alertId) {
        if (base == null || base.isEmpty() || alertId == null) return null;
        return base + "/?tab=alerthistory&alert=" + alertId + "&n_call=1";
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
