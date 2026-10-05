package com.sitemonitor.controller;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AlertActionNote;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.ConnectionDiagnosticsService;
import com.sitemonitor.service.EscalationDelay;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.DomainExpiryDiagnosticsService;
import com.sitemonitor.service.DomainExpiryRefreshService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.HstsDiagnosticsService;
import com.sitemonitor.service.MonitoringGroupService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.NetworkDiagnosticsService;
import com.sitemonitor.service.OpensslDiagnosticsService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ProxyCaExportService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    /** 7/24 İzleme Ekibi (NOC) alanları (2026-09-27) — isteğe bağlı: dilimli test bağlamında yokken grup kimliği olduğu gibi kalır. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocMonitorService nocMonitors;

    /**
     * 7/24 arama kaydı (2026-09-27) — isteğe bağlı: dilimli test bağlamında yokken uyarı uçları eskisi gibi (özet yok,
     * 7/24 operatörüne ek görünürlük yok). Varken: uyarı listesi/tekil uyarı/teslimat okuma uçlarında izin sahibi
     * ({@code noc_calls.write}) TÜM takımları görür; yazma eylemlerinin (sahiplen/çöz/tekrar bildir) kapsamı DEĞİŞMEZ.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    /**
     * "7/24'e iletildi" bilgisi (2026-10-04) — isteğe bağlı: dilimli test bağlamında yokken satırlar {@code noc_sent_at}
     * taşımaz; varken liste/tekil uyarı tek toplu sorguyla süslenir ve {@code noc=sent} süzgeci çalışır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocAlertFacts nocAlertFacts;

    private final AuditService auditService;
    private final MonitorHistoryService monitorHistory;

    /**
     * Envanter geçmişinde tutulan alanlar — {@code buildInventoryDiff}'in denetim için ürettiği
     * alan listesiyle AYNI küme. İkisi ayrışırsa geçmişte görünmeyen bir değişiklik olur; bu
     * yüzden yeni bir envanter alanı eklenirken İKİSİ birden güncellenir.
     */
    private static final String[] INVENTORY_FIELDS = {
        "domain", "port", "active", "tier", "description", "owner", "tags", "externalVendor",
        "actionRequired", "openshift", "sslPinning", "internalCert", "jksKeystore", "serverUpdate",
        "netscaler", "wafEnabled", "inUse", "evCertificate", "transferredToSy", "useProxy",
        "tlsMode", "purchasedBy", "platform", "platformDetail", "changeDescription", "expectedFingerprint", "expectedSubject",
        // ugTeamId (2026-09-28): UG takımı aktarımı (transfer-ug) ve PUT'taki temizleme geçmişte görünsün; içe aktarma
        // (InventoryImportService.HISTORY_FIELDS) bu alanı zaten yazıyordu. Envanter geri döndürülemez (findRestorable
        // INVENTORY taşımaz) — alan yalnız fark/anlık görüntüye girer, snapshot'tan geri YAZILMAZ (BO9 kapısı korunur).
        "teamId", "ugTeamId", "groupName", "deletedAt", "notificationGroupId", "nocNotify", "nocGroupIds",
        "svcMgmtContact", "appDevContact", "iisAdminContact", "wafAdminContact",
        "timeoutSeconds", "checkIntervalHours"
    };
    private final CertificateInventoryRepository inventoryRepo;
    private final com.sitemonitor.service.DerivedMonitorTeamSync derivedMonitorTeamSync;
    /** Envantere secilen bildirim grubunun sahipligini dogrulamak icin. */
    private final com.sitemonitor.repository.NotificationGroupRepository inventoryGroupRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final com.sitemonitor.service.ThresholdPreviewService thresholdPreviewService;   // tier eşik önizleme (2026-09-20)
    private final com.sitemonitor.service.AdminHistoryService adminHistoryService;             // sekme değişiklik geçmişi (2026-09-20)
    private final com.sitemonitor.service.UserPushRecipientResolver userPushRecipientResolver;  // "Kim bilgilendirilir?" push ayağı (2026-09-20)
    private final com.sitemonitor.service.WebhookService webhookService;                       // eskalasyon webhook testi (2026-09-20)
    private final com.sitemonitor.service.TeamAdminService teamAdminService;                   // takım sayaçları / etki / taşıma (2026-09-20)
    private final com.sitemonitor.service.AdminOverviewService adminOverviewService;           // özet şeridi (2026-09-20)
    private final EscalationContactRepository contactRepo;
    private final AlertEventRepository alertEventRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final com.sitemonitor.repository.UserPushDeliveryRepository userPushDeliveryRepo;
    private final EscalationService escalationService;
    private final com.sitemonitor.service.UserPushService userPushService;
    private final LatestCheckRepository latestCheckRepo;
    private final CertificateCheckRepository certificateCheckRepo;
    private final CertificateNoteRepository noteRepo;
    private final CertificateNoteRevisionRepository noteRevisionRepo;
    private final UserService userService;
    private final AppUserRepository userRepo;
    private final com.sitemonitor.service.TourStateService tourStateService;   // ürün turu sıfırlama (2026-09-13)
    private final TeamRepository teamRepo;
    private final EmailNotificationService emailNotificationService;
    private final ConnectionDiagnosticsService diagnosticsService;
    private final OpensslDiagnosticsService opensslDiagnosticsService;
    private final SsrfGuard ssrfGuard;
    private final NetworkDiagnosticsService networkDiagnosticsService;
    private final HstsDiagnosticsService hstsDiagnosticsService;
    private final DiagnosticHistoryService diagnosticHistoryService;
    private final DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;
    private final DomainExpiryRefreshService domainExpiryRefreshService;
    private final ProxyCaExportService proxyCaExportService;
    private final PublicSuffixService publicSuffixService;
    private final ClientIpResolver clientIpResolver;

    private final PermissionService permissionService;
    private final MonitoringGroupService monitoringGroupService;
    private final SchedulerService schedulerService;

    /** Takım sessiz saati önbelleği (2026-10-01) — kayıttan sonra bu pod'da hemen düşürülür. İsteğe bağlı (WebMvcTest'te yok). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.TeamQuietHoursService teamQuietHours;

    /** "Uzun süredir açık" eşiği (saat) — bu yaştan eski AÇIK alarm unutulmuş kabul edilir. */
    private static final int ALERT_STALE_HOURS = 24;

    /** Tekrar rozetinin penceresi (gün) — bu süre içindeki aynı (domain, tip) alarmları sayılır. */
    private static final int ALERT_REPEAT_WINDOW_DAYS = 30;
    /** İmza zaman çizelgesi örneklem tavanı — "önceki oluşum" için yeterli, sorgu sınırsız büyümesin. */
    private static final int ALERT_TIMELINE_SAMPLE = 500;

    /** Tekrar sayımı için bileşik anahtar — dize paketleme YOK (domain adlarında boşluk olabiliyor). */
    private record RepeatKey(String domain, String alertType) {}

    /** Sorgu satırındaki Object'i güvenle dizeye çevirir (null → null, anahtar yine tutarlı). */
    private static String str(Object o) { return o == null ? null : o.toString(); }
    /** Map.of null DEGER kabul etmez; denetim ayrintisinda null alan bosa cevrilir. */
    private static String nz(String s) { return s == null ? "" : s; }

    /** CSV dışa aktarım sınırları — tek istekte tüm tabloyu belleğe almamak için. */
    private static final int ALERT_CSV_PAGE = 2_000;
    private static final int ALERT_CSV_MAX_ROWS = 100_000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Alan Adı Tanılama için basit kullanıcı-başı sliding-window (10/dk) — registry'leri dövmemek için.
     *  Tek-pod prod'da bellek-içi yeterli. Key = user id (yoksa client IP). */
    private final Map<String, Deque<Long>> domainDiagRate = new ConcurrentHashMap<>();
    private static final int  DOMAIN_DIAG_MAX_PER_MIN = 10;
    private static final long DOMAIN_DIAG_WINDOW_MS = 60_000L;

    // ── Inventory ─────────────────────────────────────────────────────────────

    /**
     * Org geneli envanter görünürlüğü (2026-09-26). İsteğe bağlı enjeksiyon: @WebMvcTest dilimlerinde bean
     * yoksa {@code scope=all} sessizce {@code mine}'a düşer ve by-domain/not okumaları bugünkü gibi takım
     * kapsamlı kalır (kapı kapalı = eski davranış). Yazma kapıları bu alanı HİÇ okumaz.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.InventoryVisibility inventoryVisibility;

    private boolean wantsAllInventory(HttpSession session, String scope) {
        return inventoryVisibility != null && inventoryVisibility.wantsAll(session, scope);
    }

    private boolean readableOrgWide(HttpSession session, CertificateInventory inv) {
        return inventoryVisibility != null && inventoryVisibility.readableOrgWide(session, inv);
    }

    /**
     * Başka takımın kaydını OKUYANA kişisel veri gitmez: {@code created_ip} kaydı açan kişinin cihaz
     * adresidir (takım arkadaşları için künye, başka takım için kişisel veri). Varlık kopuk (open-in-view
     * kapalı, uç işlem dışı) ve bu yoldan asla kaydedilmez — alan yalnız YANITTAN düşer.
     */
    private static void redactForForeignReader(CertificateInventory inv) {
        inv.setCreatedIp(null);
    }

    /**
     * Bildirim grubu ADLARI (2026-09-28, envanter detayı "Bildirim grubu" satırı): kayıtların grup kimliklerini TEK sorguda
     * çözer — liste 1000+ satır olabilir, satır başına sorgu yok (tek pod). Ad YALNIZ iki koşulda yazılır:
     * <ul>
     *   <li>grup kayıt için gerçekten UYGULANIYOR — aktif ve kaydın takımına ait ({@code NotificationGroupService.overrideFor}
     *       yabancı / pasif grubu yok sayar; adı göstermek "alarmlar bu gruba gidiyor" diye yanlış söylerdi);</li>
     *   <li>çağıran grubu OKUYABİLİYOR — {@code notification.groups/view} + grubun takımı görüş kapsamında
     *       ({@code NotificationGroupController.list} kapısı). Org geneli okumada başka takımın grubu bu yüzden adsız kalır.</li>
     * </ul>
     * Aksi hâlde alan null → yazılmaz; arayüz kimliği "bulunamadı" açıklamasıyla gösterir (grup ucunun 404 deseni).
     */
    private void applyNotificationGroupNames(HttpSession session, List<CertificateInventory> items) {
        if (items == null || items.isEmpty()) return;
        if (!permissionService.allows(session, "notification.groups", "view")) return;
        Set<Long> ids = new HashSet<>();
        for (CertificateInventory it : items) if (it.getNotificationGroupId() != null) ids.add(it.getNotificationGroupId());
        if (ids.isEmpty()) return;   // grup seçilmemiş kayıtlar (takım varsayılanı) → sorgu YOK
        Map<Long, com.sitemonitor.model.NotificationGroup> groups = new HashMap<>();
        for (com.sitemonitor.model.NotificationGroup g : inventoryGroupRepo.findAllById(ids)) {
            if (g != null && g.getId() != null) groups.put(g.getId(), g);
        }
        for (CertificateInventory it : items) {
            com.sitemonitor.model.NotificationGroup g = it.getNotificationGroupId() == null ? null : groups.get(it.getNotificationGroupId());
            if (g == null || !Boolean.TRUE.equals(g.getActive())) continue;
            if (it.getTeamId() == null || !it.getTeamId().equals(g.getTeamId())) continue;
            if (!SessionScope.canView(session, g.getTeamId())) continue;
            it.setNotificationGroupName(g.getName());
        }
    }

    /**
     * Mükerrer alan adı 409'u (2026-09-28, kullanıcı isteği): ileti kaydın HANGİ ekipte olduğunu söyler, yapısal
     * {@code existing} arayüze aktarım / geri yükleme / aktarım talebi yolunu açar. Ekleme ve yeniden adlandırma
     * AYNI yardımcıyı kullanır.
     *
     * <p>{@code existing} BEYAZ LİSTEDİR — kimlik, ad, durum ve ÇAĞIRANIN bu kayıttaki eylem bayrakları:
     * {@code domain, inventory_id, team_id, team_name, ug_team_id, ug_team_name, deleted, deleted_at, same_team,
     * can_view, can_restore, can_transfer}. Sorumlu kişiler, açıklama, notlar, IP, platform ASLA taşınmaz. Takım
     * ADI org geneli görünürlük kapalıyken de söylenir (ürün kuralı 2026-09-26: hangi alan adının hangi takıma
     * kayıtlı olduğunu herkes bilebilir; aksi hâlde kullanıcı kime başvuracağını bilemez).
     *
     * <p>Bayraklar ilgili uçların kapılarının AYNASI — asıl kapı yine her uçta:
     * {@code can_view} = silinmemiş + okunabilir (by-domain kuralı); {@code can_restore} = çöp kutusunda + restore
     * kapısı (takım yönetimi + {@code inventory.crud/edit}); {@code can_transfer} = transfer kapısı (GLOBAL admin +
     * {@code inventory.transfer/execute} — kapsamlı müdür rol dizesinden global sayılmaz).
     */
    private com.sitemonitor.config.GlobalExceptionHandler.DomainExistsException domainExists(
            CertificateInventory clash, Long targetTeamId, HttpSession session) {
        Map<Long, String> names = new HashMap<>();
        for (Team tm : userService.listTeams()) if (tm.getId() != null) names.put(tm.getId(), tm.getName());
        String team = clash.getTeamId() != null ? names.get(clash.getTeamId()) : null;
        boolean deleted = clash.getDeletedAt() != null;
        boolean sameTeam = targetTeamId != null && targetTeamId.equals(clash.getTeamId());
        Map<String, Object> ex = new LinkedHashMap<>();   // null değer taşır (Map.of almaz)
        ex.put("domain", clash.getDomain());
        ex.put("inventory_id", clash.getId());
        ex.put("team_id", clash.getTeamId());
        ex.put("team_name", team);
        ex.put("ug_team_id", clash.getUgTeamId());
        ex.put("ug_team_name", clash.getUgTeamId() != null ? names.get(clash.getUgTeamId()) : null);
        ex.put("deleted", deleted);
        ex.put("deleted_at", clash.getDeletedAt());
        ex.put("same_team", sameTeam);
        ex.put("can_view", !deleted && (inventoryVisibility != null
                ? inventoryVisibility.canRead(session, clash) : SessionScope.canView(session, clash.getTeamId())));
        ex.put("can_restore", deleted && canManageTeamResource(session, clash.getTeamId())
                && permissionService.allows(session, "inventory.crud", "edit"));
        ex.put("can_transfer", isAdmin(session) && permissionService.allows(session, "inventory.transfer", "execute"));
        String msg;
        if (team == null) {
            msg = deleted
                    ? com.sitemonitor.util.Msg.t(
                        "Bu alan adı çöp kutusunda (hiçbir ekibe atanmamış bir kayıt). Mükerrer kayıt oluşturulamaz; kaydın geri yüklenmesi ya da ekibinize aktarılması gerekir.",
                        "This domain is in the bin (a record that isn't assigned to any team). A duplicate record can't be created; the record needs to be restored or transferred to your team.")
                    : com.sitemonitor.util.Msg.t(
                        "Bu alan adı envanterde, hiçbir ekibe atanmamış bir kayıt olarak zaten kayıtlı. Mükerrer kayıt oluşturulamaz; kaydın ekibinize aktarılması (transfer) gerekir.",
                        "This domain is already in the inventory as a record that isn't assigned to any team. A duplicate record can't be created; the record needs to be transferred to your team.");
        } else if (sameTeam) {
            msg = deleted
                    ? com.sitemonitor.util.Msg.t(
                        "Bu alan adı, seçtiğiniz '" + team + "' ekibinin çöp kutusunda. Mükerrer kayıt oluşturulamaz; kaydı çöp kutusundan geri yükleyin.",
                        "This domain is in the bin of the team you selected, '" + team + "'. A duplicate record can't be created; restore the record from the bin instead.")
                    : com.sitemonitor.util.Msg.t(
                        "Bu alan adı, seçtiğiniz '" + team + "' ekibinin envanterinde zaten kayıtlı. Mükerrer kayıt oluşturulamaz; mevcut kaydı açıp düzenleyin.",
                        "This domain is already registered in the inventory of the team you selected, '" + team + "'. A duplicate record can't be created; open the existing record and edit it instead.");
        } else {
            msg = deleted
                    ? com.sitemonitor.util.Msg.t(
                        "Bu alan adı çöp kutusunda ('" + team + "' ekibinin kaydı). Mükerrer kayıt oluşturulamaz; kaydın geri yüklenmesi ya da ekibinize aktarılması gerekir.",
                        "This domain is in the bin (a record belonging to the '" + team + "' team). A duplicate record can't be created; the record needs to be restored or transferred to your team.")
                    : com.sitemonitor.util.Msg.t(
                        "Bu alan adı zaten '" + team + "' ekibinin envanterinde kayıtlı. Mükerrer kayıt oluşturulamaz; alan adının sizin ekibinizde olması gerekiyorsa kaydın ekibinize aktarılması (transfer) gerekir.",
                        "This domain is already registered in the '" + team + "' team's inventory. A duplicate record can't be created; if the domain should belong to your team, the record needs to be transferred to it.");
        }
        return new com.sitemonitor.config.GlobalExceptionHandler.DomainExistsException(msg, ex);
    }

    /**
     * Envanter listesi.
     *
     * <p>{@code scope}: {@code mine} (varsayılan — bugünkü davranış: global görüntüleyici hepsini, diğerleri
     * görüş kapsamındaki takımların kayıtlarını) ya da {@code all} (2026-09-26, org geneli görünürlük: ayar
     * açık ve {@code inventory.list/view} izni varsa silinmemiş TÜM kayıtlar). Ayar kapalıyken {@code all}
     * {@code mine}'a düşer; yanıttaki {@code scope} GERÇEKTE uygulananı söyler. {@code showDeleted} yalnız
     * global görüntüleyicide (admin/AUDIT) etkili — değişmedi.
     *
     * <p>Her satır {@code can_manage} taşır ({@link SessionScope#canWriteInventory}; küme istek başına bir kez
     * kurulur). Takım adları ve son kontrol haritası tek okumayla çözülür — {@code all} N+1 eklemez.
     */
    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> listInventory(
            @RequestParam(defaultValue = "false") boolean showDeleted,
            @RequestParam(defaultValue = "mine") String scope,
            HttpSession session) {
        requirePerm(session, "inventory.list", "view");
        List<CertificateInventory> items;
        boolean orgWide = false;
        if (isAdminOrAudit(session)) {                     // global admin / AUDIT → all
            items = showDeleted
                    ? inventoryRepo.findAllByOrderByDomainAsc()
                    : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
        } else if (wantsAllInventory(session, scope)) {    // org geneli okuma (silinmişler hariç)
            items = inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
            orgWide = true;
        } else {                                           // scoped (müdür/PO/USER)
            List<Long> view = viewScope(session);
            items = (view == null || view.isEmpty())
                    ? List.of()
                    : inventoryRepo.findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(view);
        }
        // SY/UG takım adlarını sunucuda çöz — USER rolü tüm takım listesini çekemediğinden
        // (kendi takımıyla filtreli) liste kolonlarında takım adları boş kalmasın.
        Map<Long, String> teamNames = new HashMap<>();
        for (Team tm : userService.listTeams()) {
            if (tm.getId() != null) teamNames.put(tm.getId(), tm.getName());
        }
        // Canlı sertifika durumu (2026-09-12, envanter #3): satırda geçerli/uyarı/hata, kalan gün, son
        // kontrol — Genel Bakış'a geçmeden okunsun. latest_checks domain başına tek satır; ek sorgu bir.
        Map<String, LatestCheck> latest = new HashMap<>();
        try { for (LatestCheck lc : latestCheckRepo.findAll()) if (lc.getDomain() != null) latest.put(lc.getDomain(), lc); }
        catch (Exception e) { log.debug("Envanter listesi: latest_checks okunamadı: {}", e.toString()); }
        java.util.function.Predicate<Long> writable = SessionScope.inventoryWriteTest(session);
        for (CertificateInventory it : items) {
            if (it.getTeamId() != null)   it.setTeamName(teamNames.get(it.getTeamId()));
            if (it.getUgTeamId() != null) it.setUgTeamName(teamNames.get(it.getUgTeamId()));
            LatestCheck lc = latest.get(it.getDomain());
            if (lc != null) {
                it.setCertStatus(lc.getStatus());
                it.setCertDaysRemaining(lc.getDaysRemaining());
                it.setCertNotAfter(lc.getNotAfter());
                it.setCertCheckedAt(lc.getCheckedAt());
                it.setCertIssuer(lc.getIssuerCn() != null ? lc.getIssuerCn() : lc.getIssuer());
                it.setCertError(lc.getError());
            }
            it.setCanManage(writable.test(it.getTeamId()));
            if (orgWide && !SessionScope.canView(session, it.getTeamId())) redactForForeignReader(it);
        }
        applyNotificationGroupNames(session, items);   // çekmecenin "Bildirim grubu" satırı — TEK sorgu, satır başına değil
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items);
        body.put("scope", orgWide || isAdminOrAudit(session) && "all".equalsIgnoreCase(scope.trim()) ? "all" : "mine");
        body.put("visible_to_all", inventoryVisibility != null && inventoryVisibility.enabledFor(session));
        return ok(body);
    }

    /** Tek domain'in envanter kaydı (kart modalındaki "Envanter Bilgileri" tab'ı için).
     *  listInventory ile AYNI izin + kapsam; domain tekil → en fazla tek kayıt (yoksa data:null).
     *  2026-09-26: org geneli görünürlük açıksa başka takımın SİLİNMEMİŞ kaydı da TAM döner (salt okunur,
     *  {@code can_manage=false}); kendi kapsamı dışındaki silinmiş kayıt yine {@code null}. */
    @GetMapping("/inventory/by-domain")
    public ResponseEntity<Map<String, Object>> getInventoryByDomain(
            @RequestParam String domain,
            HttpSession session) {
        requirePerm(session, "inventory.list", "view");
        CertificateInventory rec = inventoryRepo.findByDomain(domain).orElse(null);
        boolean foreign = false;
        // Kapsam: admin/audit değilse yalnız kendi görüş kapsamındaki takımın kaydı görünür
        // (çapraz-takım sızıntısı olmasın — listInventory'deki viewScope semantiği) — ya da org geneli okuma.
        if (rec != null && !isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            boolean own = scope != null && !scope.isEmpty()
                    && rec.getTeamId() != null && scope.contains(rec.getTeamId());
            if (!own) {
                if (readableOrgWide(session, rec)) foreign = true;
                else rec = null;
            }
        }
        if (rec != null) {
            Map<Long, String> teamNames = new HashMap<>();
            for (Team tm : userService.listTeams()) {
                if (tm.getId() != null) teamNames.put(tm.getId(), tm.getName());
            }
            if (rec.getTeamId() != null)   rec.setTeamName(teamNames.get(rec.getTeamId()));
            if (rec.getUgTeamId() != null) rec.setUgTeamName(teamNames.get(rec.getUgTeamId()));
            rec.setCanManage(SessionScope.canWriteInventory(session, rec.getTeamId()));
            applyNotificationGroupNames(session, List.of(rec));   // grup kimliği varsa tek ek sorgu
            if (foreign) redactForForeignReader(rec);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("data", rec);   // Map.of null değer almaz → HashMap
        return ok(body);
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/inventory")
    public ResponseEntity<Map<String, Object>> addInventory(
            @Valid @RequestBody CertificateInventory item, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "inventory.crud", "edit");
        item.setDomain(validateDomain(item.getDomain()));   // URL yapıştırılırsa host'a normalize edilir
        if (item.getTeamId() == null) {
            throw new IllegalArgumentException("A team must be selected for the certificate");
        }
        requireInventoryGroupAndTags(item);   // grup + etiket zorunlu (2026-09-18)
        item.setPlatform(normalizePlatform(item.getPlatform()));   // bilinmeyen değer → null (2026-09-22)
        item.setPlatformDetail(blankToNull(item.getPlatformDetail()));
        // Seçilen takım çağıranın YAZMA kapsamında olmalı: global admin her takım; yönetici/PO yönettiği
        // takımlar; USER ÜYESİ olduğu takım(lar) (2026-09-18: "Domain Ekle" her kullanıcı seviyesinde).
        requireInventoryWriter(session, item.getTeamId());
        requireExistingTeam(item.getTeamId());   // O3: olmayan takıma yazılan kayıt hiçbir alıcıya ulaşmaz (2026-09-28)
        // Devralma YOK (2026-09-26, org geneli görünürlük): alan adı envanterde zaten varsa — başka takımın
        // kaydı ya da çöp kutusundaki bir kayıt dâhil — "yeniden ekleyerek" sahipliği ele geçirmek mümkün
        // olmamalı. DB UNIQUE kısıtı bunu zaten reddederdi ama harf farkında (Example.com) kısıt kör kalıyor,
        // ileti de kaydın var olduğunu söylemiyordu. Açık 409; mevcut kayda dokunulmaz.
        // 2026-09-28: 409 kaydın SAHİBİ takımı adıyla söyler + yapısal `existing` (aktarım/geri yükleme yolu).
        CertificateInventory clash = inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(item.getDomain()).orElse(null);
        if (clash != null) throw domainExists(clash, item.getTeamId(), session);
        String now = now();
        item.setId(null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        // Sunucunun yönettiği alanlar istek gövdesinden ALINMAZ (prod kapısı 2026-09-25, O-5 — mass assignment):
        // varlık doğrudan gövdeye bağlandığı için ekleme yetkili USER yenileme planını, "güncelleyen" damgasını,
        // alan adı süre bilgisini ya da silinme tarihini sahteleyebiliyordu. Oluşturan damgası stampCreated'tan gelir.
        item.setDeletedAt(null);
        item.setDomainExpiry(null);
        item.setDomainRegistrar(null);
        item.setDomainExpiryCheckedAt(null);
        item.setUpdatedBy(null);
        item.setUpdatedByName(null);
        item.setRenewalPlannedAt(null);
        item.setRenewalPlannedBy(null);
        item.setRenewalPlannedByName(null);
        item.setRenewalPlannedNote(null);
        // UG takımı (BO9): yeni kayıtta yalnız boş ya da kaydın KENDİ takımı — başka takıma açmak admin kapılı
        // transfer-ug işidir (gövdeden gelen değer kaydı başka takımın görünürlüğüne + alarmlarına açıyordu).
        item.setUgTeamId(requireUgUnchangedOrCleared(session, item.getTeamId(), item.getUgTeamId(), item.getDomain()));
        if (item.getPort() == null) item.setPort(443);
        if (item.getPort() < 1 || item.getPort() > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (item.getActive() == null) item.setActive(true);
        item.setTlsMode(normalizeTlsMode(item.getTlsMode()));
        item.setCheckIntervalHours(normalizeInterval(item.getCheckIntervalHours()));
        // Bildirim grubu SAHIPLIK dogrulamasi — updateInventory ile AYNI kural. Olusturma yolunda
        // eksikti: baska takimin grup id'si ile kayit acilabiliyordu. Gonderim aninda ikinci bir
        // kapi daha var (NotificationGroupService yabanci grubu yok sayar) ama gecersiz deger yine
        // de KAYDEDILIR ve arayuzde "alarmlar su gruba gidiyor" diye YANLIS gorunurdu.
        item.setNotificationGroupId(validInventoryGroup(item.getNotificationGroupId(), item.getTeamId()));
        // 7/24 (NOC, 2026-09-27): var olmayan grup kimliği sessizce düşer (izleme formlarıyla aynı kural).
        if (nocMonitors != null) item.setNocGroupIds(nocMonitors.sanitizeGroupIds(item.getNocGroupIds()));
        item.setGroupName(monitoringGroupService.getOrCreate(item.getTeamId(), "cert", item.getGroupName(), actor(session)));
        monitorHistory.stampCreated(item, session);
        CertificateInventory saved = inventoryRepo.save(item);
        auditService.recordAction("DOMAIN_ADD", session, request,
                "CERTIFICATE", saved.getDomain(),
                "{\"port\":" + saved.getPort() + ",\"teamId\":" + saved.getTeamId() + "}");
        // Envanter Uptime/SSL kartlarının kaynağı — geçmişi izleme tipleriyle AYNI hunide toplanır.
        monitorHistory.record(MonitorHistoryService.INVENTORY, saved.getId(), saved.getDomain(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, INVENTORY_FIELDS), null, session);
        // Anında tek-domain kontrol (async): latest_checks satırı hemen oluşsun → Genel Bakış'ta
        // gecikmeden görünür ve kontrollere dahil olur (5-dk stale sweep'i beklemeden).
        schedulerService.checkSingleDomainAsync(saved.getDomain(),
                saved.getPort() != null ? saved.getPort() : 443,
                Boolean.TRUE.equals(saved.getUseProxy()), saved.getTlsMode());
        return ok(Map.of("data", saved, "message", "Domain added to inventory"));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PutMapping("/inventory/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateInventory(
            @PathVariable Long id, @Valid @RequestBody CertificateInventory item,
            HttpSession session, HttpServletRequest request) {
        CertificateInventory existing = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        // 2026-09-18: USER kendi TAKIMININ kaydını düzenler (izleme türleriyle aynı sözleşme); silme/aktarma
        // yönetici kapılarında kalır.
        requireInventoryWriter(session, existing.getTeamId());
        requirePerm(session, "inventory.crud", "edit");
        // Oluşturma yolu (addInventory) host'a normalize ediyor (trim + küçük harf); güncelleme
        // etmiyordu. "Example.COM" gibi harf-farklı düzenleme rename sayılmıyor (equalsIgnoreCase)
        // ama satıra yazılıyordu → latest_checks/geçmiş/notlar domain dizesiyle bağlı olduğundan
        // kayıt geçmişsiz kalıyordu; "Other.com" ise exact-UNIQUE'i geçip ikinci satır oluşturuyordu.
        item.setDomain(validateDomain(item.getDomain()));
        requireInventoryGroupAndTags(item);   // grup + etiket zorunlu (2026-09-18) — düzenlemede de
        // Takım aktarımı bu uçtan YALNIZ kaydın takımını yönetenlere (global admin / yönetim kapsamı):
        // TEAM_ADMIN ve üyelik yoluyla gelen USER için teamId mevcut değere sabitlenir.
        if (isTeamAdmin(session) || !canManageTeamResource(session, existing.getTeamId())) {
            item.setTeamId(existing.getTeamId());
        }
        // HEDEF takım da yönetim kapsamında olmalı (2026-09-25, regresyon R4 incelemesi): yalnız kaynak takım
        // denetleniyordu — kaynağı yöneten kapsamlı müdür kaydı kapsamı DIŞINDAKİ bir takıma taşıyabiliyordu.
        if (item.getTeamId() != null && !java.util.Objects.equals(item.getTeamId(), existing.getTeamId())
                && !canManageTeamResource(session, item.getTeamId())) {
            log.warn("Inventory team move outside management scope by user={} from={} to={}",
                    actor(session), existing.getTeamId(), item.getTeamId());
            throw new SecurityException("Access denied: the target team is outside your management scope");
        }
        // UG takımı (BO9, bug regresyon 2026-09-27 — mass assignment): gövdeden yalnız TEMİZLEME (null;
        // tek takıma yakınsama ürün kararı, ön uç her düzenlemede null gönderir) ya da AYNI değer kabul edilir.
        // Başka bir takım kimliği kaydı o takıma açar (okuma görünürlüğü + alarm e-postası) — bu yalnız admin
        // kapılı /inventory/{id}/transfer-ug ucundan yapılır; takım üyesi USER bu uçla o kapıyı atlıyordu.
        // Yan etkiden (yeniden adlandırma) ÖNCE denetlenir.
        final Long newUgTeamId = requireUgUnchangedOrCleared(session, existing.getUgTeamId(), item.getUgTeamId(),
                existing.getDomain());
        item.setTlsMode(normalizeTlsMode(item.getTlsMode()));

        // Build diff BEFORE applying changes
        String diffJson = buildInventoryDiff(existing, item, true);
        // Geçmiş için AYRI snapshot: buildInventoryDiff elle JSON kuruyor ve yalnız FARKI üretiyor;
        // ürün geçmişi ise "o an tam durum" da tutuyor (AuditDiff ile, aynı maskeleme kurallarıyla).
        Map<String, Object> _histBefore = AuditDiff.snapshot(existing, INVENTORY_FIELDS);

        // ── Domain rename: latest_checks + geçmiş tabloları yeni isme taşı ────
        // Aksi halde SchedulerService.syncLatestChecksToInventory (artık devre
        // dışı ama her ihtimale karşı koruyalım) ya da legacy data kalıntıları
        // eski domain'i boş metadata ile yeniden oluşturuyordu. Plus rename
        // çakışmasını UNIQUE constraint patlamadan önce yakala.
        String oldDomain = existing.getDomain();
        String newDomain = item.getDomain();
        boolean renamed = newDomain != null && oldDomain != null
                && !newDomain.equalsIgnoreCase(oldDomain);
        if (renamed) {
            // Ekleme ucuyla AYNI 409 (2026-09-28): çakışan kaydın sahibi takım + yapısal `existing`. Hedef takım
            // kaydın (düzenlemeyle taşınıyorsa yeni) takımı — "aynı takım" iletisi ona göre seçilir.
            CertificateInventory clash = inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(newDomain).orElse(null);
            if (clash != null && !java.util.Objects.equals(clash.getId(), existing.getId())) {
                throw domainExists(clash, item.getTeamId() != null ? item.getTeamId() : existing.getTeamId(), session);
            }
            int lc = latestCheckRepo.renameDomain(oldDomain, newDomain);
            int cc = certificateCheckRepo.renameDomain(oldDomain, newDomain);
            int ae = alertEventRepo.renameDomain(oldDomain, newDomain);
            int nt = noteRepo.renameDomain(oldDomain, newDomain);
            log.info("Domain renamed: '{}' → '{}' (latest={}, checks={}, alerts={}, notes={})",
                    oldDomain, newDomain, lc, cc, ae, nt);
        }

        existing.setDomain(item.getDomain());
        existing.setPort(item.getPort() != null ? item.getPort() : 443);
        existing.setDescription(item.getDescription());
        existing.setOwner(item.getOwner());
        existing.setTags(item.getTags());
        boolean wasActive = Boolean.TRUE.equals(existing.getActive());
        existing.setActive(item.getActive() != null ? item.getActive() : true);
        existing.setExpectedFingerprint(item.getExpectedFingerprint());
        existing.setExpectedSubject(item.getExpectedSubject());
        if (item.getTeamId() != null) {
            // Takım buradan da değişebiliyor (global admin) — transferInventory ile AYNI senkron.
            boolean teamChanged = !java.util.Objects.equals(existing.getTeamId(), item.getTeamId());
            if (teamChanged) requireExistingTeam(item.getTeamId());   // O3 (2026-09-28): olmayan takıma taşıma 400
            existing.setTeamId(item.getTeamId());
            if (teamChanged) derivedMonitorTeamSync.syncTeam(existing.getDomain(), item.getTeamId());
        }
        existing.setUgTeamId(newUgTeamId);
        // Bildirim grubu: SAHIPLIK dogrulanir -- baska takimin grubu envantere yazilamaz
        // (monitor tarafindaki applyNotificationGroup ile ayni kural).
        existing.setNotificationGroupId(
                validInventoryGroup(item.getNotificationGroupId(), existing.getTeamId()));
        // 7/24 (NOC, 2026-09-27): YALNIZ gövdede gelen alan yazılır — alanı bilmeyen bir istemcinin (eski form)
        // her düzenlemesi 7/24 bildirimini sessizce kapatırdı. Var olmayan grup kimliği düşer.
        if (item.isNocNotifySupplied()) existing.setNocNotify(Boolean.TRUE.equals(item.getNocNotify()));
        if (item.isNocGroupIdsSupplied()) existing.setNocGroupIds(nocMonitors != null
                ? nocMonitors.sanitizeGroupIds(item.getNocGroupIds()) : item.getNocGroupIds());
        // Sorumlu Ekipler — DORT setter da sart. Biri atlanirsa o alan formda kaydedilmis
        // GORUNUR ama sayfa yenilenince kaybolur (CLAUDE.md'deki 1 numarali envanter bug'i).
        existing.setSvcMgmtContact(item.getSvcMgmtContact());
        existing.setAppDevContact(item.getAppDevContact());
        existing.setIisAdminContact(item.getIisAdminContact());
        existing.setWafAdminContact(item.getWafAdminContact());
        existing.setExternalVendor(item.getExternalVendor());
        existing.setActionRequired(item.getActionRequired());
        existing.setOpenshift(item.getOpenshift());
        existing.setSslPinning(item.getSslPinning());
        existing.setInternalCert(item.getInternalCert());
        existing.setJksKeystore(item.getJksKeystore());
        existing.setServerUpdate(item.getServerUpdate());
        existing.setNetscaler(item.getNetscaler());
        existing.setWafEnabled(item.getWafEnabled());
        existing.setInUse(item.getInUse());
        existing.setEvCertificate(item.getEvCertificate());
        existing.setTransferredToSy(item.getTransferredToSy());
        existing.setUseProxy(item.getUseProxy());
        // SETTER UNUTULURSA alan kaydeder GÖRÜNÜR ama yeniden açılışta kaybolur — bu kesimin
        // bir numaralı sessiz hatası; kontrol listesinde ayrıca yazılı.
        existing.setTimeoutSeconds(item.getTimeoutSeconds());
        existing.setTlsMode(item.getTlsMode());
        existing.setCheckIntervalHours(normalizeInterval(item.getCheckIntervalHours()));
        existing.setPurchasedBy(item.getPurchasedBy());
        existing.setPlatform(normalizePlatform(item.getPlatform()));
        existing.setPlatformDetail(blankToNull(item.getPlatformDetail()));
        existing.setChangeDescription(item.getChangeDescription());
        existing.setTier(item.getTier());
        existing.setGroupName(monitoringGroupService.getOrCreate(existing.getTeamId(), "cert", item.getGroupName(), actor(session)));
        existing.setUpdatedAt(now());
        CertificateInventory saved = inventoryRepo.save(existing);

        // Pasife alındıysa (true→false) açık alarmları sessizce kapat — izleme durduğundan
        // aksi halde otomatik çözülemezler (toplu pasif akışıyla aynı semantik).
        int alertsClosed = 0;
        if (wasActive && Boolean.FALSE.equals(saved.getActive()) && saved.getDeletedAt() == null) {
            alertsClosed = escalationService.closeAlertsOnDeactivate(saved.getDomain());
        }

        if (!diffJson.equals("{}")) {
            auditService.recordAction("DOMAIN_EDIT", session, request,
                    "CERTIFICATE", saved.getDomain(), diffJson);
        }
        monitorHistory.record(MonitorHistoryService.INVENTORY, saved.getId(), saved.getDomain(), saved.getTeamId(),
                MonitorHistoryService.UPDATE, _histBefore, AuditDiff.snapshot(saved, INVENTORY_FIELDS), null, session);
        return ok(Map.of("data", saved, "alertsClosed", alertsClosed));
    }

    /**
     * Kalıcı purge'ün domain-anahtarlı çocukları: notlar + revizyonları. Eskiden yalnız
     * certificate_checks/latest_checks siliniyor, notlar kalıyordu; aynı domain haftalar sonra
     * yeniden eklenince eski notlar yeni kaydın altında "diriliyordu" (retention da notları
     * hiç kırpmaz). Çağıran @Transactional — hepsi ya birlikte gider ya hiç.
     */
    private void purgeDomainNotes(String domain) {
        List<com.sitemonitor.model.CertificateNote> notes = noteRepo.findByDomainOrderByCreatedAtDesc(domain);
        if (notes == null || notes.isEmpty()) return;
        List<Long> ids = notes.stream().map(com.sitemonitor.model.CertificateNote::getId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) noteRevisionRepo.deleteByNoteIdIn(ids);
        noteRepo.deleteAll(notes);
    }

    private String buildInventoryDiff(CertificateInventory o, CertificateInventory n, boolean isAdmin) {
        StringBuilder sb = new StringBuilder("{");
        fieldDiff(sb, "domain",             o.getDomain(),               n.getDomain());
        fieldDiff(sb, "port",               o.getPort(),                 n.getPort() != null ? n.getPort() : 443);
        fieldDiff(sb, "active",             o.getActive(),               n.getActive() != null ? n.getActive() : true);
        fieldDiff(sb, "tier",               o.getTier(),                 n.getTier());
        fieldDiff(sb, "description",        o.getDescription(),          n.getDescription());
        fieldDiff(sb, "owner",              o.getOwner(),                n.getOwner());
        fieldDiff(sb, "tags",               o.getTags(),                 n.getTags());
        fieldDiff(sb, "externalVendor",     o.getExternalVendor(),       n.getExternalVendor());
        fieldDiff(sb, "actionRequired",     o.getActionRequired(),       n.getActionRequired());
        fieldDiff(sb, "openshift",          o.getOpenshift(),            n.getOpenshift());
        fieldDiff(sb, "sslPinning",         o.getSslPinning(),           n.getSslPinning());
        fieldDiff(sb, "internalCert",       o.getInternalCert(),         n.getInternalCert());
        fieldDiff(sb, "jksKeystore",        o.getJksKeystore(),          n.getJksKeystore());
        fieldDiff(sb, "serverUpdate",       o.getServerUpdate(),         n.getServerUpdate());
        fieldDiff(sb, "netscaler",          o.getNetscaler(),            n.getNetscaler());
        fieldDiff(sb, "wafEnabled",         o.getWafEnabled(),           n.getWafEnabled());
        fieldDiff(sb, "inUse",              o.getInUse(),                n.getInUse());
        fieldDiff(sb, "evCertificate",      o.getEvCertificate(),        n.getEvCertificate());
        fieldDiff(sb, "transferredToSy",    o.getTransferredToSy(),      n.getTransferredToSy());
        fieldDiff(sb, "useProxy",           o.getUseProxy(),             n.getUseProxy());
        fieldDiff(sb, "timeoutSeconds",     o.getTimeoutSeconds(),       n.getTimeoutSeconds());
        fieldDiff(sb, "tlsMode",            o.getTlsMode(),              n.getTlsMode());
        fieldDiff(sb, "purchasedBy",        o.getPurchasedBy(),          n.getPurchasedBy());
        fieldDiff(sb, "platform",           o.getPlatform(),             n.getPlatform());
        fieldDiff(sb, "platformDetail",     o.getPlatformDetail(),       n.getPlatformDetail());
        fieldDiff(sb, "changeDescription",  o.getChangeDescription(),    n.getChangeDescription());
        fieldDiff(sb, "expectedFingerprint",o.getExpectedFingerprint(),  n.getExpectedFingerprint());
        fieldDiff(sb, "expectedSubject",    o.getExpectedSubject(),      n.getExpectedSubject());
        fieldDiff(sb, "notificationGroupId", o.getNotificationGroupId(), n.getNotificationGroupId());
        if (n.isNocNotifySupplied())   fieldDiff(sb, "nocNotify",   Boolean.TRUE.equals(o.getNocNotify()), Boolean.TRUE.equals(n.getNocNotify()));
        if (n.isNocGroupIdsSupplied()) fieldDiff(sb, "nocGroupIds", o.getNocGroupIds(), n.getNocGroupIds());
        fieldDiff(sb, "svcMgmtContact",  o.getSvcMgmtContact(),  n.getSvcMgmtContact());
        fieldDiff(sb, "appDevContact",   o.getAppDevContact(),   n.getAppDevContact());
        fieldDiff(sb, "iisAdminContact", o.getIisAdminContact(), n.getIisAdminContact());
        fieldDiff(sb, "wafAdminContact", o.getWafAdminContact(), n.getWafAdminContact());
        if (isAdmin && n.getTeamId() != null)
            fieldDiff(sb, "teamId",         o.getTeamId(),               n.getTeamId());
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Envantere yazilabilecek bildirim grubu — grup o takima ait ve aktif degilse null'a duser.
     *
     * <p>Sessiz null'lama bilincli: envanter satiri toplu ice aktarma/transfer yollarindan da
     * guncelleniyor; oralarda 400 firlatmak koca bir aktarimi tek satir yuzunden dusururdu.
     * Yanlis takimin listesine posta gondermektense takim varsayilanina dusmek dogru taraftir.
     */
    private Long validInventoryGroup(Long requested, Long teamId) {
        if (requested == null || teamId == null) return null;
        return inventoryGroupRepo.findById(requested)
                .filter(g -> teamId.equals(g.getTeamId()) && Boolean.TRUE.equals(g.getActive()))
                .map(com.sitemonitor.model.NotificationGroup::getId)
                .orElse(null);
    }

    private void fieldDiff(StringBuilder sb, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            sb.append('"').append(field).append("\":{\"from\":")
              .append(toJsonVal(oldVal)).append(",\"to\":")
              .append(toJsonVal(newVal)).append("},");
        }
    }

    private String toJsonVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        String s = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                               .replace("\n", "\\n").replace("\r", "");
        return '"' + s + '"';
    }

    /** tls_mode: null/blank → null (inherit global); only "browser"/"default" allowed. */
    /** Kontrol sıklığı (saat): izin verilen değerler; başka/boş → null (genel zamanlama). */
    /** Platform kataloğu (2026-09-22): Ayarlar → Platformlar. İsteğe bağlı enjeksiyon (@WebMvcTest bağlamı); yokken yalnız
     *  biçim doğrulanır (üst-harf slug). Bilinmeyen değer → null: sessiz bozuk değer yerine boş. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.PlatformService platformService;
    String normalizePlatform(String v) {
        if (v == null || v.isBlank()) return null;
        if (platformService != null) return platformService.normalize(v);
        String u = v.trim().toUpperCase(java.util.Locale.ROOT);
        return u.matches("^[A-Z0-9_]{2,20}$") ? u : null;
    }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    static Integer normalizeInterval(Integer h) {
        if (h == null) return null;
        return java.util.Set.of(1, 6, 12, 24, 168).contains(h) ? h : null;
    }

    private String normalizeTlsMode(String v) {
        if (v == null || v.isBlank()) return null;
        String m = v.trim().toLowerCase();
        if (!m.equals("browser") && !m.equals("default")) {
            throw new IllegalArgumentException("tls_mode must be 'browser' or 'default'");
        }
        return m;
    }

    /** Tanı: pod'a ulaşan forwarding başlıkları + remoteAddr + çözülen IP. Loglardaki
     *  client IP yanlışsa (proxy IP), gerçek IP'nin hangi başlıkta olduğunu görmek için. */
    @GetMapping("/client-ip-debug")
    public ResponseEntity<Map<String, Object>> clientIpDebug(HttpServletRequest request, HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", clientIpResolver.debugInfo(request)));
    }

    // ── Connection diagnostics ────────────────────────────────────────────────

    @PostMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> runDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = diagnosticsService.diagnose(domain, port);
        auditService.recordAction("DIAGNOSTICS_RUN", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        // Tanılama geçmişi — kim/ne zaman/nereden/sonuç
        boolean ok = data.get("combos") instanceof List<?> combos
                && combos.stream().anyMatch(c -> c instanceof Map<?, ?> m && "ok".equals(m.get("status")));
        diagnosticHistoryService.record(domain, port, "CONNECTION",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, connectionSummary(data), data);
        return ok(Map.of("data", data));
    }

    /** Derin SSL/TLS taraması — openssl s_client çeşitli parametrelerle. */
    @PostMapping("/diagnostics/openssl")
    public ResponseEntity<Map<String, Object>> runOpensslDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = opensslDiagnosticsService.probe(domain, port);
        auditService.recordAction("DIAGNOSTICS_OPENSSL", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        boolean ok = Boolean.TRUE.equals(data.get("available"));
        diagnosticHistoryService.record(domain, port, "OPENSSL",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, opensslSummary(data), data);
        return ok(Map.of("data", data));
    }

    /** Ağ derin analizi — ping/traceroute/dns/tcp/curl/ip. */
    @PostMapping("/diagnostics/network")
    public ResponseEntity<Map<String, Object>> runNetworkDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        Map<String, Object> data = networkDiagnosticsService.analyze(domain, port);
        auditService.recordAction("DIAGNOSTICS_NETWORK", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        Object okC = data.get("ok_count");
        Object total = data.get("total");
        boolean ok = okC instanceof Number n && n.intValue() > 0;
        diagnosticHistoryService.record(domain, port, "NETWORK",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, okC + "/" + total + " kontrol OK", data);
        return ok(Map.of("data", data));
    }

    /** HSTS analizi — Strict-Transport-Security başlığını okur, yönergeleri ayrıştırır,
     *  neyi nasıl kontrol ettiğini ve neyi bulamadığını açıklar. */
    @PostMapping("/diagnostics/hsts")
    public ResponseEntity<Map<String, Object>> runHstsDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateDiagTarget(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        Long portRaw = toLong(body.get("port"));
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        // Envanterdeki vekil tercihi BURADA da geçerli: elle tanılama ile Sağlık sekmesi
        // aynı yoldan çıkmalı, yoksa iki ekran aynı domain için farklı cevap verir.
        boolean forceProxy = inventoryRepo.findByDomain(domain)
                .map(ci -> Boolean.TRUE.equals(ci.getUseProxy()))
                .orElse(true);          // envanterde yoksa eski davranış: vekil varsa kullan
        Map<String, Object> data = hstsDiagnosticsService.diagnose(domain, port, forceProxy);
        auditService.recordAction("DIAGNOSTICS_HSTS", session, request,
                "CERTIFICATE", domain, "{\"port\":" + port + "}");
        String verdict = String.valueOf(data.get("verdict"));
        boolean ok = "ok".equals(data.get("status"));
        diagnosticHistoryService.record(domain, port, "HSTS",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "HSTS: " + verdict, data);
        return ok(Map.of("data", data));
    }

    /** Alan adı (registrar) süre bitişi tanılaması — RDAP/WHOIS zincirini adım adım koşar ve
     *  proxy/PKIX/port-43 sorunlarını görünür kılar. Başarılı sonuç eşleşen envanter satırlarına yazılır. */
    @PostMapping("/diagnostics/domain-expiry")
    public ResponseEntity<Map<String, Object>> runDomainExpiryDiagnostics(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        String domain = body.get("domain") != null ? body.get("domain").toString().trim() : null;
        domain = validateRegistryTarget(domain);
        requireDomainExpiryScope(session, domain);
        requirePerm(session, "diagnostics.run", "execute");
        // Kullanıcı-başı hız sınırı (10/dk) — RDAP/WHOIS registry'lerini dövmemek için.
        Long uid = userIdFromSession(session);
        checkDomainDiagRate(uid != null ? "u" + uid : "ip" + clientIp(request));

        // DEĞİŞTİRİLEBİLİR kopya: aşağıda data.put(...) yapılıyor ve servis bir gün
        // değiştirilemez harita dönerse uç 500 verirdi (sessiz, yalnız o dalda).
        Map<String, Object> data = new LinkedHashMap<>(domainExpiryDiagnosticsService.diagnose(domain));
        String source = String.valueOf(data.get("source"));
        boolean ok = !"FAILED".equals(source) && data.get("expiry_date") != null;

        // Tani SONUCU yaziliyor: ayni degerler zaten diagnosticHistoryService'e gidiyor,
        // denetimde "bir tani kosuldu" demek tek basina hicbir soruyu cevaplamiyordu.
        auditService.recordAction("DIAGNOSTICS_DOMAIN_EXPIRY", session, request,
                "DOMAIN", domain, AuditDetail.of("source", source, "ok", ok,
                        "expiry_date", data.get("expiry_date"), "registrar", data.get("registrar")));
        diagnosticHistoryService.record(domain, null, "DOMAIN_EXPIRY",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "DOMAIN_EXPIRY: " + source, data);

        // Başarılı → taze süre bitişini eşleşen envanter satır(lar)ına yaz (best-effort; hata diagnostic'i bozmaz).
        if (ok) {
            data.put("persisted", persistDomainExpiryToInventory(
                    String.valueOf(data.get("registrable")),
                    (String) data.get("expiry_date"),
                    (String) data.get("registrar")));
        }
        return ok(Map.of("data", data));
    }

    /** Kurumsal SSL-inspection proxy'sinin sunduğu CA zincirini yakalar ve "Güvenilir CA paketi (PEM)"
     *  alanına yapıştırılmaya hazır PEM olarak döner. Pod içinden çalışır → CA'yı UI'dan almayı sağlar. */
    @PostMapping("/diagnostics/proxy-ca-chain")
    public ResponseEntity<Map<String, Object>> captureProxyCaChain(
            @RequestBody(required = false) Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "diagnostics.run", "execute");
        Long uid = userIdFromSession(session);
        checkDomainDiagRate(uid != null ? "u" + uid : "ip" + clientIp(request));

        String host = (body != null && body.get("host") != null && !body.get("host").toString().isBlank())
                ? body.get("host").toString().trim() : "data.iana.org";
        // Kardeş tanılama uçları (/diagnostics, /openssl, /network, /hsts, /domain-expiry) admin
        // olmayanı envanter domain'leriyle sınırlar; bu uç tek başına sınırsızdı (pod egress'inden
        // keyfi host:port'a TLS el sıkışması). Aynı kapı burada da.
        requireAdminOrMonitoredDomain(session, host);
        host = validateDiagTarget(host);
        Long portRaw = body != null ? toLong(body.get("port")) : null;
        int port = portRaw != null ? portRaw.intValue() : 443;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");

        Map<String, Object> data = proxyCaExportService.capture(host, port);
        boolean ok = Boolean.TRUE.equals(data.get("ok"));
        auditService.recordAction("DIAGNOSTICS_PROXY_CA_CHAIN", session, request, "DOMAIN", host, "{\"port\":" + port + "}");
        diagnosticHistoryService.record(host, port, "PROXY_CA_CHAIN",
                actor(session), userIdFromSession(session), teamId(session), clientIp(request),
                ok, "PROXY_CA_CHAIN: " + (ok ? data.get("ca_count") + " CA" : data.get("error_class")), data);
        return ok(Map.of("data", data));
    }

    /** Süre bitişini registrable domain'i eşleşen aktif envanter satırlarına yazar; güncellenen satır sayısını döner.
     *  Tek kaynak {@link DomainExpiryRefreshService#persistToInventory} (zamanlı tazeleme de aynı yolu kullanır). */
    private int persistDomainExpiryToInventory(String registrable, String expiry, String registrar) {
        return domainExpiryRefreshService.persistToInventory(registrable, expiry, registrar);
    }

    /** Sliding-window hız sınırı; aşılırsa 429 TOO_MANY_REQUESTS fırlatır. */
    private void checkDomainDiagRate(String key) {
        long now = System.currentTimeMillis();
        pruneDomainDiagRate(now);
        Deque<Long> dq = domainDiagRate.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > DOMAIN_DIAG_WINDOW_MS) dq.pollFirst();
            if (dq.size() >= DOMAIN_DIAG_MAX_PER_MIN) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Çok fazla tanılama isteği — dakikada en fazla " + DOMAIN_DIAG_MAX_PER_MIN + ". Lütfen bekleyin.");
            }
            dq.addLast(now);
        }
    }

    /** Uzun uptime'da map anahtarları birikmesin: penceresi boşalan anahtarları at
     *  (AuthController.pruneRateLimitState deseni); aşırı durumda sert clear() backstop'u
     *  (CaAutoPinService cap deseni). Yalnız eşik aşıldığında çalışır — sıcak yolda maliyetsiz. */
    private void pruneDomainDiagRate(long now) {
        if (domainDiagRate.size() <= 1_000) return;
        domainDiagRate.entrySet().removeIf(e -> {
            Deque<Long> dq = e.getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && now - dq.peekFirst() > DOMAIN_DIAG_WINDOW_MS) dq.pollFirst();
                return dq.isEmpty();
            }
        });
        if (domainDiagRate.size() > 10_000) domainDiagRate.clear();
    }

    /** Domain tanılama geçmişi listesi (resultJson hariç özet). */
    @GetMapping("/diagnostics/history")
    public ResponseEntity<Map<String, Object>> diagnosticsHistory(
            @RequestParam String domain, HttpSession session) {
        domain = validateDomain(domain);
        requireAdminOrMonitoredDomain(session, domain);
        requirePerm(session, "diagnostics.history", "view");
        List<Map<String, Object>> data = diagnosticHistoryService.history(domain).stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("run_type", d.getRunType());
                    m.put("executed_by", d.getExecutedBy());
                    m.put("executed_at", d.getExecutedAt());
                    m.put("source_ip", d.getSourceIp());
                    m.put("port", d.getPort());
                    m.put("success", d.getSuccess());
                    m.put("summary", d.getSummary());
                    return m;
                }).toList();
        return ok(Map.of("data", data));
    }

    /** Tek geçmiş kaydı — saklanan tam sonucu (resultJson) yeniden gösterim için. */
    @GetMapping("/diagnostics/history/{id}")
    public ResponseEntity<Map<String, Object>> diagnosticsHistoryDetail(
            @PathVariable Long id, HttpSession session) {
        com.sitemonitor.model.DiagnosticRun d = diagnosticHistoryService.get(id);
        // İzleme tanılamaları (HTTP / keyword / ping / port / DNS — anahtar "<tür>-monitor:<id>") kendi uçlarından,
        // kendi takım kapsamıyla okunur; bu (sertifika/alan adı) geçmiş ucu onları kimliğiyle bile AÇMAZ (2026-10-05).
        if (com.sitemonitor.service.diagnose.NetDiagnosticsHistory.isMonitorRun(d)) {
            throw new java.util.NoSuchElementException("Diagnostic run not found: " + id);
        }
        requireAdminOrMonitoredDomain(session, d.getDomain());
        requirePerm(session, "diagnostics.history", "view");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("domain", d.getDomain());
        m.put("port", d.getPort());
        m.put("run_type", d.getRunType());
        m.put("executed_by", d.getExecutedBy());
        m.put("executed_at", d.getExecutedAt());
        m.put("source_ip", d.getSourceIp());
        m.put("success", d.getSuccess());
        m.put("summary", d.getSummary());
        m.put("result_json", d.getResultJson());
        return ok(Map.of("data", m));
    }

    /** "3/4 kombinasyon OK" tarzı kısa CONNECTION özeti. */
    private String connectionSummary(Map<String, Object> data) {
        if (!(data.get("combos") instanceof List<?> combos)) return "—";
        long ok = combos.stream().filter(c -> c instanceof Map<?, ?> m && "ok".equals(m.get("status"))).count();
        return ok + "/" + combos.size() + " kombinasyon OK";
    }

    /** "TLS1.0 açık; 2 bayrak" tarzı kısa OPENSSL özeti. */
    private String opensslSummary(Map<String, Object> data) {
        if (!Boolean.TRUE.equals(data.get("available"))) return "openssl bulunamadı";
        StringBuilder sb = new StringBuilder();
        if (data.get("protocols") instanceof List<?> protos) {
            String weak = protos.stream()
                    .filter(p -> p instanceof Map<?, ?> m
                            && Boolean.TRUE.equals(m.get("supported")) && "HIGH".equals(m.get("risk")))
                    .map(p -> String.valueOf(((Map<?, ?>) p).get("proto")))
                    .reduce((a, b) -> a + ", " + b).orElse(null);
            sb.append(weak != null ? "Zayıf protokol açık: " + weak : "Zayıf protokol yok");
        }
        if (data.get("flags") instanceof List<?> flags && !flags.isEmpty()) {
            sb.append("; ").append(flags.size()).append(" sertifika bayrağı");
        }
        return sb.toString();
    }

    /** İstek IP'si — merkezî, yapılandırılabilir resolver (proxy/LB başlıkları). */
    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @DeleteMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> deleteInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return inventoryRepo.findById(id).map(inv -> {
            requireTeamScopedAdmin(session, inv.getTeamId());
            requirePerm(session, "inventory.crud", "edit");
            Map<String, Object> _histBefore = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
            inv.setDeletedAt(now());
            inv.setActive(false);
            monitorHistory.stampUpdated(inv, session);   // "kim sildi" çöp kutusunda görünsün (envanter #10)
            inventoryRepo.save(inv);
            int alertsClosed = escalationService.closeAlertsOnInventoryDelete(inv.getDomain());
            auditService.recordAction("DOMAIN_SOFT_DELETE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + ",\"alertsClosed\":" + alertsClosed + "}");
            monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                    MonitorHistoryService.DELETE, _histBefore, AuditDiff.snapshot(inv, INVENTORY_FIELDS), null, session);
            return ok(Map.of("message", "Deleted", "alertsClosed", alertsClosed));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toplu envanter işlemi — seçili domain'leri tek istekte aktif/pasif yapar ya da siler.
     * action ∈ {activate, deactivate, delete}. Yetki tekil uçlarla aynı: girişte
     * admin/team-admin şartı, ardından her kayıt için takım kapsamı (yetkisiz/yok olan
     * kayıt atlanır; tek kaydın yetkisizliği partiyi düşürmez). "delete" tekil soft-delete
     * ile birebir (deletedAt+active=false + alarm kapatma). Zaten silinmiş/silinmiş kayıtlar
     * activate/deactivate için atlanır (geri yükleme ayrı akıştır).
     *
     * <p>QA ISSUE-001 (2026-09-13): {@code @CacheEvict} bu javadoc ile metot arasına giren
     * {@code applyBulkContacts} yardımcısına kaymıştı (2026-08-25) — özel metotta proxy çalışmaz,
     * toplu pasifleştirme/silme/kademe sonrası liste 5 dk bayat kalıyordu. Ek, artık uç metodunda.
     */
    /**
     * Toplu "sorumlu ekip ata" — YALNIZ gövdede GÖNDERİLEN alanları yazar.
     *
     * <p>Gönderilmeyen alana DOKUNULMAZ (boş string ile ezilmez): kullanıcı yalnız IISAdmin'i
     * doldurup 200 kayda uygulamak istediğinde diğer üç alanın silinmesi sessiz bir veri kaybı
     * olurdu. Bir alanı KASITLI temizlemek için değeri açıkça boş string gönderilir.
     */
    private void applyBulkContacts(CertificateInventory inv, Map<String, Object> body) {
        if (body.containsKey("svc_mgmt_contact"))  inv.setSvcMgmtContact(trimOrNull(body.get("svc_mgmt_contact")));
        if (body.containsKey("app_dev_contact"))   inv.setAppDevContact(trimOrNull(body.get("app_dev_contact")));
        if (body.containsKey("iis_admin_contact")) inv.setIisAdminContact(trimOrNull(body.get("iis_admin_contact")));
        if (body.containsKey("waf_admin_contact")) inv.setWafAdminContact(trimOrNull(body.get("waf_admin_contact")));
    }

    private static String trimOrNull(Object o) {
        if (o == null) return null;
        String v = o.toString().trim();
        return v.isEmpty() ? null : v;
    }

    /** Toplu envanter işleminin geçmiş notu — NocController'ın "toplu 7/24 işlemi" deseni: satır toplu yoldan geldiğini söyler. */
    private static final Map<String, String> BULK_HISTORY_NOTE = Map.of(
            "activate", "toplu etkinleştirme",
            "deactivate", "toplu pasife alma",
            "delete", "toplu silme",
            "set-contacts", "toplu sorumlu ekip ataması",
            "set-tier", "toplu kademe ataması",
            "set-team", "toplu takım aktarımı");

    /**
     * Toplu envanter işleminin ürün geçmişi satırı (2026-09-28): tekil uçlarla AYNI tür/kimlik/ad/takım/aktör/alan listesi —
     * silme {@code DELETE} ({@link #deleteInventory}), diğer eylemler {@code UPDATE} ({@link #updateInventory}: aktiflik,
     * sorumlular, kademe, takım aynı PUT'tan geçer). Eskiden toplu yol hiç yazmıyordu: toplu silinen kayıt İzleme
     * Değişiklikleri'nde görünmüyor ve "silinmiş" rozetini alamıyordu ({@code findDeletedAmong} = son olay DELETE).
     *
     * <p>Değişmeyen kayıt (zaten aktif olanı etkinleştir, aynı kademe) için satır YAZILMAZ: {@code record} not taşıyan
     * UPDATE'i değişiklik olmasa da yazdığı için bu kontrol burada. Çağıran {@code @Transactional}: satır silmeyle aynı
     * işlemde — geri alınan toplu işlem geçmişte "silindi" izi bırakmaz.
     */
    private void recordBulkInventoryHistory(CertificateInventory inv, String action,
                                            Map<String, Object> before, HttpSession session) {
        Map<String, Object> after = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
        boolean delete = "delete".equals(action);
        if (!delete && AuditDiff.diff(before, after) == null) return;
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                delete ? MonitorHistoryService.DELETE : MonitorHistoryService.UPDATE, before, after,
                BULK_HISTORY_NOTE.get(action), session);
    }

    @PostMapping("/inventory/bulk")
    @Transactional
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    public ResponseEntity<Map<String, Object>> bulkInventoryAction(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "inventory.crud", "edit");
        String action = body.get("action") != null ? body.get("action").toString().trim().toLowerCase() : "";
        if (!Set.of("activate", "deactivate", "delete", "set-contacts", "set-tier", "set-team").contains(action)) {
            throw new IllegalArgumentException("action must be one of: activate, deactivate, delete, set-contacts, set-tier, set-team");
        }
        // Tüm Sertifikalar tablosu (2026-09-13) alan adıyla çalışır, envanter kimliğini bilmez:
        // `domains` listesi kimliğe çözülür (bilinmeyen alan atlanır). `ids` ile birlikte de verilebilir.
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) { Long id = toLong(o); if (id != null) ids.add(id); }
        }
        if (body.get("domains") instanceof List<?> rawDomains) {
            for (Object o : rawDomains) {
                if (o == null) continue;
                inventoryRepo.findByDomain(o.toString().trim().toLowerCase()).map(CertificateInventory::getId).ifPresent(ids::add);
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("No ids provided");
        // set-tier: 1–4 ya da null (kademeyi kaldır). set-team: yalnız GLOBAL admin + transfer izni —
        // tek kayıtlık /transfer ucuyla aynı kapı ve aynı türev-izleme senkronu.
        Integer newTier = null;
        if ("set-tier".equals(action)) {
            Long tv = toLong(body.get("tier"));
            if (tv != null && (tv < 1 || tv > 4)) throw new IllegalArgumentException("tier must be 1..4 or null");
            newTier = tv == null ? null : tv.intValue();
        }
        Long newTeamId = null;
        if ("set-team".equals(action)) {
            requireAdmin(session);
            requirePerm(session, "inventory.transfer", "execute");
            newTeamId = toLong(body.get("team_id"));
            if (newTeamId == null) throw new IllegalArgumentException("team_id is required");
            requireExistingTeam(newTeamId);   // olmayan takıma yazılan kayıt sahipsiz kalırdı (2026-09-28)
        }

        int processed = 0, skipped = 0, alertsClosed = 0;
        String ts = now();
        for (Long id : ids) {
            CertificateInventory inv = inventoryRepo.findById(id).orElse(null);
            if (inv == null || !canManageTeamResource(session, inv.getTeamId())) { skipped++; continue; }
            boolean deleted = inv.getDeletedAt() != null;
            // Ürün geçmişi (İzleme Değişiklikleri) için işlem ÖNCESİ durum — tekil uçlarla aynı alan listesi.
            Map<String, Object> histBefore = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
            int processedBefore = processed;
            switch (action) {
                case "activate" -> {
                    if (deleted) { skipped++; }            // silinmiş kayıt → "geri yükle" akışı kullanılmalı
                    else { inv.setActive(true);  inv.setUpdatedAt(ts); inventoryRepo.save(inv); processed++; }
                }
                case "deactivate" -> {
                    if (deleted) { skipped++; }
                    else {
                        boolean wasActive = Boolean.TRUE.equals(inv.getActive());
                        inv.setActive(false); inv.setUpdatedAt(ts); inventoryRepo.save(inv);
                        // Pasife alınan domain artık taranmaz → açık alarmlarını sessizce kapat
                        if (wasActive) alertsClosed += escalationService.closeAlertsOnDeactivate(inv.getDomain());
                        processed++;
                    }
                }
                case "set-contacts" -> {
                    if (deleted) { skipped++; }            // silinmiş kayda toplu yazma yapılmaz
                    else {
                        applyBulkContacts(inv, body);
                        inv.setUpdatedAt(ts);
                        inventoryRepo.save(inv);
                        processed++;
                    }
                }
                case "set-tier" -> {
                    if (deleted) { skipped++; }
                    else { inv.setTier(newTier); inv.setUpdatedAt(ts); inventoryRepo.save(inv); processed++; }
                }
                case "set-team" -> {
                    if (deleted) { skipped++; }
                    else {
                        boolean changed = !java.util.Objects.equals(inv.getTeamId(), newTeamId);
                        inv.setTeamId(newTeamId); inv.setUpdatedAt(ts); inventoryRepo.save(inv);
                        if (changed) derivedMonitorTeamSync.syncTeam(inv.getDomain(), newTeamId);
                        processed++;
                    }
                }
                case "delete" -> {
                    if (deleted) { skipped++; }            // zaten silinmiş → no-op
                    else {
                        inv.setDeletedAt(ts); inv.setActive(false); inv.setUpdatedAt(ts);
                        monitorHistory.stampUpdated(inv, session);   // "kim sildi" çöp kutusunda görünsün — tekil silmeyle aynı
                        inventoryRepo.save(inv);
                        alertsClosed += escalationService.closeAlertsOnInventoryDelete(inv.getDomain());
                        processed++;
                    }
                }
            }
            // YALNIZ gerçekten işlenen kayıt için (atlanan / kapsam dışı / zaten silinmiş → satır YOK).
            if (processed > processedBefore) recordBulkInventoryHistory(inv, action, histBefore, session);
        }
        // Her eylemin KENDI denetim adi olmali. Eskiden default -> DOMAIN_BULK_DELETE'ti; yeni bir
        // eylem eklenince (set-contacts) denetim kaydi "N domain SILINDI" diye yaziliyordu. Denetim
        // kaydinin yanlis olmasi, hic olmamasindan daha kotudur: kaydi inceleyen kisi olmamis bir
        // silme gorur. Bilinmeyen eylem artik sessizce silme gibi gorunmez.
        String auditAction = switch (action) {
            case "activate"     -> "DOMAIN_BULK_ACTIVATE";
            case "deactivate"   -> "DOMAIN_BULK_DEACTIVATE";
            case "delete"       -> "DOMAIN_BULK_DELETE";
            case "set-contacts" -> "DOMAIN_BULK_SET_CONTACTS";
            case "set-tier"     -> "DOMAIN_BULK_SET_TIER";
            case "set-team"     -> "DOMAIN_BULK_SET_TEAM";
            default             -> "DOMAIN_BULK_" + action.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        };
        auditService.recordAction(auditAction, session, request, "CERTIFICATE",
                processed + " domain",
                "{\"processed\":" + processed + ",\"skipped\":" + skipped + ",\"alertsClosed\":" + alertsClosed + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processed", processed);
        data.put("skipped", skipped);
        data.put("alertsClosed", alertsClosed);
        return ok(Map.of("data", data, "message", "Bulk " + action + " complete"));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @DeleteMapping("/certificates/{domain}")
    public ResponseEntity<Map<String, Object>> deleteCertificateCheck(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        if (isTeamAdmin(session)) {
            inventoryRepo.findByDomain(domain).ifPresentOrElse(
                    inv -> requireTeamScopedAdmin(session, inv.getTeamId()),
                    () -> { throw new SecurityException("Domain not found in your team's inventory"); });
        } else {
            requireAdmin(session);
        }
        requirePerm(session, "inventory.crud", "edit");
        // Silmeden ONCE oku: bu uc bugune kadar KOR siliyordu — hangi durumdaki kayit
        // gitti (gecerli miydi, ne zaman doluyordu) hicbir yerde kalmiyordu.
        String checkBefore = latestCheckRepo.findById(domain)
                .map(c -> AuditDiff.snapshotJson(AuditDiff.snapshot(c,
                        "status", "notAfter", "issuer", "daysRemaining")))
                .orElse(null);
        latestCheckRepo.deleteById(domain);
        auditService.recordAction("DOMAIN_DELETE_CHECK", session, request, "CERTIFICATE", domain,
                checkBefore != null ? checkBefore : AuditDetail.of("existed", false));
        return ok(Map.of("message", "Deleted"));
    }

    // F3 (2026-09-28): geri yükleme de envanteri değiştirir — eksikti; 409 DOMAIN_EXISTS "çöp kutusundan geri yükle"
    // akışından sonra alan adı Genel Bakış'ta cert-latest TTL'i (300 sn) boyunca görünmüyordu.
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/inventory/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return inventoryRepo.findById(id).map(inv -> {
            requireTeamScopedAdmin(session, inv.getTeamId());
            requirePerm(session, "inventory.crud", "edit");
            Map<String, Object> _histBefore = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
            inv.setDeletedAt(null);
            inv.setActive(true);
            inv.setUpdatedAt(now());
            inventoryRepo.save(inv);
            auditService.recordAction("DOMAIN_RESTORE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + "}");
            monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                    MonitorHistoryService.RESTORE, _histBefore, AuditDiff.snapshot(inv, INVENTORY_FIELDS), null, session);
            return ok(Map.of("data", inv, "message", "Restored"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Kalıcı sil (purge) — yalnız SOFT-DELETE edilmiş bir envanter kaydını ve o domain'in
     * tüm kontrol verisini (latest_checks + certificate_checks) GERİ ALINAMAZ şekilde siler.
     * Alarmlar zaten soft-delete sırasında kapandığı için burada ek alarm işlemi yok.
     *
     * <p>İKİ KAPI: {@code inventory.purge} izni VE kaydın takımı üzerinde yönetim yetkisi.
     * İkincisi eskiden yoktu ve bu sessiz bir yetki aşımıydı: {@code requirePerm} yalnız
     * {@code systemRole} dizesine bakar ({@code PermissionService.require}) ve
     * {@code adminDefaults()} ADMIN'e her kaynağı açar. AD üzerinden gelen ADMIN ("müdür") ise
     * global DEĞİLDİR — {@code UserService.computeViewTeamIds} ona kendi + astlarının takımlarını
     * verir, yani {@code SessionScope.isGlobalAdmin} false kalır. Sonuç: müdür, başka takımın
     * kaydını soft-delete EDEMEZKEN ({@code deleteInventory} → {@code requireTeamScopedAdmin})
     * aynı kaydı KALICI silebiliyordu — geri alınamaz olan yol, kapsamsız olan yoldu.
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @DeleteMapping("/inventory/{id}/permanent")
    @Transactional
    public ResponseEntity<Map<String, Object>> purgeInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "inventory.purge", "execute"); // dedike sensitive yetki (varsayılan ADMIN-only, matristen yönetilebilir)
        return inventoryRepo.findById(id).map(inv -> {
            // Kapsam kontrolü deletedAt kontrolünden ÖNCE: yabancı takımın kaydı için 400 ile 403
            // farkı, id numaralandırmasına "bu kayıt var ve silinmemiş" sinyali verirdi.
            requireTeamScopedAdmin(session, inv.getTeamId());
            if (inv.getDeletedAt() == null) {
                throw new IllegalArgumentException("Yalnız önce silinmiş (soft-delete) kayıtlar kalıcı silinebilir");
            }
            String domain = inv.getDomain();
            int checks = certificateCheckRepo.deleteByDomain(domain);
            latestCheckRepo.findById(domain).ifPresent(latestCheckRepo::delete);
            purgeDomainNotes(domain);
            inventoryRepo.delete(inv);
            auditService.recordAction("DOMAIN_PURGE", session, request, "CERTIFICATE", domain,
                    "{\"teamId\":" + inv.getTeamId() + ",\"checksDeleted\":" + checks + "}");
            return ok(Map.of("message", "Purged", "checksDeleted", checks));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toplu kalıcı sil — soft-delete edilmiş envanter kayıtlarını ve ilgili kontrol verisini
     * tek istekte GERİ ALINAMAZ şekilde temizler.
     *
     * <p>KAYIT BAŞINA kapsam uygulanır — {@code bulkInventoryAction} ile AYNI desen. Tekil purge'ün
     * (yukarıda) aksine burada id bilmeye bile gerek yok, yani kapsamsız bırakılması daha ağırdı:
     * tek istek tüm organizasyonun çöp kutusunu silerdi. Atlananlar yanıtta ve denetim kaydında
     * görünür ki kullanıcı "312 vardı, 40 gitti" ile sessizce karşılaşmasın.
     *
     * <p>Silinen domain listesi denetime YAZILIR: geri dönüş yok, forensics'in tek dayanağı bu.
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/inventory/purge-deleted")
    @Transactional
    public ResponseEntity<Map<String, Object>> purgeAllDeleted(
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "inventory.purge", "execute");
        List<CertificateInventory> deleted = inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc();
        int purged = 0, checksDeleted = 0, skipped = 0;
        List<String> purgedDomains = new ArrayList<>();
        for (CertificateInventory inv : deleted) {
            if (!canManageTeamResource(session, inv.getTeamId())) { skipped++; continue; }
            String domain = inv.getDomain();
            checksDeleted += certificateCheckRepo.deleteByDomain(domain);
            latestCheckRepo.findById(domain).ifPresent(latestCheckRepo::delete);
            purgeDomainNotes(domain);
            inventoryRepo.delete(inv);
            purged++;
            purgedDomains.add(domain);
        }
        auditService.recordAction("DOMAIN_BULK_PURGE", session, request, "CERTIFICATE",
                purged + " domain",
                "{\"purged\":" + purged + ",\"skipped\":" + skipped
                        + ",\"checksDeleted\":" + checksDeleted
                        + ",\"domains\":" + toJsonArray(purgedDomains) + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("purged", purged);
        data.put("skipped", skipped);
        data.put("checksDeleted", checksDeleted);
        return ok(Map.of("data", data, "message", "Purge complete"));
    }

    /** Denetim ayrıntısı için minimal JSON dizisi — tırnak ve ters bölü kaçırılır. */
    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Ürün turunu sıfırla (2026-09-13): kullanıcı bir sonraki girişte karşılama kartını yeniden görür.
     * Kapı: takım kapsamlı admin (kendi takımı) ya da global admin. Denetim: USER_TOUR_RESET.
     */
    @PostMapping("/users/{id}/tour-reset")
    public ResponseEntity<Map<String, Object>> resetUserTour(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        AppUser target = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        tourStateService.apply(target, Map.of(), true);
        auditService.recordAction("USER_TOUR_RESET", session, request, "USER", String.valueOf(id),
                "{\"username\":\"" + String.valueOf(target.getUsername()).replace("\"", "") + "\"}");
        return ok(Map.of("message", "Tour reset"));
    }

    // F3 (2026-09-28): SY/UG aktarımı kartların takım adını ve görünürlüğünü değiştirir — eviction eksikti.
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/inventory/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transferInventory(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "inventory.transfer", "execute");
        Long newTeamId = toLong(body.get("team_id"));
        // Takım ZORUNLU (2026-09-28): gövdesiz/boş istek kaydı ve türev Port/DNS izlemelerini takımsız (sahipsiz)
        // bırakıyordu — sahipsiz alarm hiçbir bildirim üretmez. Arayüz boş seçimde istek atmıyor; sunucu da reddeder.
        if (newTeamId == null) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Aktarım için bir takım seçin.", "Choose a team to transfer to."));
        }
        requireExistingTeam(newTeamId);
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        // Çöp kutusundaki kayıt (Ek 3/5, 2026-09-28): DÜZ aktarım 409. Silinmiş kayda yazılan UPDATE satırı ürün geçmişinde
        // onu "canlı"ya çeviriyordu (findDeletedAmong = son olay DELETE; o tabloya yalnız canlı kayda yazım düşer). Mükerrer
        // alan adı bandının "geri yükle + aktar"ı `restore: true` ile TEK adımda gelir: takım + geri yükleme tek kayıt, tek
        // RESTORE satırı (eskiden aktar → ayrı /restore; ikinci adım düşerse kayıt yeni takımın çöpünde kalıyordu).
        // Geri yükleme kapısı /restore ile AYNI (inventory.crud/edit; takım kapsamını global admin zaten geçer).
        boolean deleted = inv.getDeletedAt() != null;
        boolean restore = deleted && Boolean.TRUE.equals(body.get("restore"));
        if (deleted && !restore) {
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Bu kayıt çöp kutusunda — aktarmadan önce geri yükleyin.",
                    "This record is in the bin — restore it before transferring it."));
        }
        if (restore) requirePerm(session, "inventory.crud", "edit");
        Long oldTeamId = inv.getTeamId();
        Map<String, Object> _histBefore = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
        inv.setTeamId(newTeamId);
        if (restore) {
            inv.setDeletedAt(null);
            inv.setActive(true);
        }
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        // Ürün geçmişi (2026-09-28): takım değişikliği düzenleme (PUT) ve toplu set-team yolunda UPDATE olarak yazılıyor,
        // bu uçta yazılmıyordu — aynı olay hangi düğmeden yapıldığına göre İzleme Değişiklikleri'nde var/yok oluyordu.
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                restore ? MonitorHistoryService.RESTORE : MonitorHistoryService.UPDATE,
                _histBefore, AuditDiff.snapshot(inv, INVENTORY_FIELDS), null, session);
        // Türev izlemelerin takımı da tazelenir: aksi hâlde yeni takım kendi kaydını
        // düzenleyemez, ESKİ takım listede göremediği satırı yönetmeye devam eder ve kesinti
        // alarmları eski takıma gider (zamanlayıcı oturumsuz çalışır, sütunu okur).
        int synced = derivedMonitorTeamSync.syncTeam(inv.getDomain(), newTeamId);
        auditService.recordAction("DOMAIN_TRANSFER_SY", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldTeamId + ",\"to\":" + newTeamId + ",\"derivedMonitorsSynced\":" + synced
                        + (restore ? ",\"restored\":true" : "") + "}");
        if (restore) {
            auditService.recordAction("DOMAIN_RESTORE", session, request,
                    "CERTIFICATE", inv.getDomain(), "{\"teamId\":" + newTeamId + "}");
        }
        return ok(Map.of("data", inv, "message", restore ? "Restored and transferred" : "Transferred"));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/inventory/{id}/transfer-ug")
    public ResponseEntity<Map<String, Object>> transferInventoryUg(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "inventory.transfer", "execute");
        Long newUgTeamId = toLong(body.get("ug_team_id"));
        // Ek 3/5 (2026-09-28): olmayan takıma UG aktarımı 400 (SY aktarımıyla aynı kapı; null = UG takımını kaldır, izinli) —
        // uydurma kimlik kaydı hiçbir takımın görmediği bir UG'ye bağlıyordu.
        requireExistingTeam(newUgTeamId);
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        // Çöp kutusundaki kayda UG aktarımı 409 — silinmiş kayda yazılan UPDATE satırı onu ürün geçmişinde "canlı"ya çeviriyordu.
        if (inv.getDeletedAt() != null) {
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Bu kayıt çöp kutusunda — UG takımını değiştirmeden önce geri yükleyin.",
                    "This record is in the bin — restore it before changing its UG team."));
        }
        Long oldUgTeamId = inv.getUgTeamId();
        Map<String, Object> _histBefore = AuditDiff.snapshot(inv, INVENTORY_FIELDS);
        inv.setUgTeamId(newUgTeamId);
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        // Ürün geçmişi (2026-09-28): UG takımı değişikliği İzleme Değişiklikleri'ne hiç düşmüyordu (tekil /transfer ile
        // aynı desen). Aynı değere aktarım fark üretmez → record satır yazmaz.
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                MonitorHistoryService.UPDATE, _histBefore, AuditDiff.snapshot(inv, INVENTORY_FIELDS), null, session);
        auditService.recordAction("DOMAIN_TRANSFER_UG", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldUgTeamId + ",\"to\":" + newUgTeamId + "}");
        return ok(Map.of("data", inv, "message", "UG team transferred"));
    }

    // ── Yönetim Paneli özet şeridi (2026-09-20) ───────────────────────────────────

    /** Sayaçlar + sağlık uyarıları; kapsamlı kullanıcı görüş alanındaki takımlarla sınırlı. */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> adminOverview(HttpSession session) {
        requirePerm(session, "teams.list", "view");
        List<Long> scope = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session);
        return ok(Map.of("data", adminOverviewService.overview(scope)));
    }

    // ── Yönetim Paneli değişiklik geçmişi (2026-09-20) ──────────────────────────

    /**
     * "Kim, ne zaman, neyi değiştirdi" — eşik / eskalasyon kişisi / takım / kullanıcı. Kaynak denetim kaydı.
     * USER ve ALERT_THRESHOLD yalnız global yönetici (kişisel veri / global ayar); TEAM ve ESCALATION_CONTACT
     * görüş kapsamıyla (kapsamlı müdür kendi takımlarını görür).
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> adminHistory(
            @RequestParam String resource,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) List<String> types,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpSession session) {
        if (!com.sitemonitor.service.AdminHistoryService.RESOURCES.contains(resource)) {
            throw new IllegalArgumentException("Bilinmeyen kaynak: " + resource);
        }
        List<Long> scope;
        switch (resource) {
            case "ALERT_THRESHOLD" -> { requireAdmin(session); requirePerm(session, "thresholds.read", "view"); scope = null; }
            case "USER" -> { requireAdmin(session); SessionScope.requireNotScopedAdmin(session, "users.history"); requirePerm(session, "users.list", "view"); scope = null; }
            case "ESCALATION_CONTACT" -> { requirePerm(session, "contacts.list", "view"); scope = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session); }
            default -> { requirePerm(session, "teams.list", "view"); scope = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session); }
        }
        var h = adminHistoryService.history(resource, resourceId, types, scope, page, size);
        Map<Long, String> teamNames = new LinkedHashMap<>();
        teamRepo.findAll().forEach(tm -> teamNames.put(tm.getId(), tm.getName()));
        String prefix = switch (resource) {
            case "ALERT_THRESHOLD" -> "THRESHOLD_";
            case "ESCALATION_CONTACT" -> "CONTACT_";
            case "TEAM" -> "TEAM_";
            default -> "USER_";
        };
        List<Map<String, Object>> items = new ArrayList<>();
        for (var e : h.items()) {
            var r = e.row();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("at", r.getEventTime());
            m.put("actor", r.getActor());
            m.put("action", r.getEventType() != null && r.getEventType().startsWith(prefix)
                    ? r.getEventType().substring(prefix.length()) : r.getEventType());
            m.put("event_type", r.getEventType());
            m.put("resource_id", r.getResourceId());
            m.put("name", historyName(r.getDetail()));
            m.put("team_id", e.teamId());
            m.put("team_name", e.teamId() != null ? teamNames.get(e.teamId()) : null);
            m.put("changes", r.getChanges());
            m.put("ip", r.getIpAddress());
            m.put("outcome", r.getOutcome());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", h.total());
        out.put("page", h.page());
        out.put("size", h.size());
        out.put("total_pages", h.totalPages());
        out.put("truncated", h.truncated());
        out.put("hidden", h.hidden());
        out.put("types", com.sitemonitor.service.AdminHistoryService.eventTypesFor(resource));
        // Eylemi yapanın IP'si kimlik izidir (2026-09-28c): yalnız global admin + AUDIT'e ve kişinin kendi satırında.
        // TEAM / ESCALATION_CONTACT kolları kapsamlı müdüre ve takım yöneticisine açık → alan düşer, satır işaretlenir.
        return ok(IdentityMask.forSession(out, session));
    }

    /**
     * Geçmiş satırının adı: detail çoğu olayda düz ad, bazılarında JSON ({@code AuditDetail.of("name", …)},
     * WEEKLY_REPORT_ACCESS gövdesi). JSON ise name/team/username/domain anahtarı çekilir; yoksa null (takım adı düşer).
     */
    static String historyName(String detail) {
        if (detail == null) return null;
        String d = detail.trim();
        if (!d.startsWith("{")) return d;
        for (String key : new String[]{"name", "team", "username", "domain"}) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(d);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ── Alert Thresholds (ADMIN only) ─────────────────────────────────────────

    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds(HttpSession session) {
        requirePerm(session, "thresholds.read", "view");
        return ok(Map.of("data", thresholdRepo.findAll()));
    }

    @PostMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> createThreshold(
            @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "thresholds.edit", "edit");
        t.setId(null);
        validateThreshold(t.getTier(), t.getWarningDays(), t.getHighDays(), t.getCriticalDays());
        // Tier başına TEK aktif satır; varsayılan (tier'sız) da tek: ikinci satır çözümde sessizce yok sayılırdı.
        List<AlertThreshold> same = t.getTier() == null
                ? thresholdRepo.findByActiveTrueAndTierIsNullOrderByIdAsc()
                : thresholdRepo.findByTierOrderByIdAsc(t.getTier());
        if (!same.isEmpty()) {
            throw new IllegalStateException(t.getTier() == null
                    ? com.sitemonitor.util.Msg.t("Varsayılan eşik zaten var; onu düzenleyin.", "A default threshold already exists; edit it instead.")
                    : com.sitemonitor.util.Msg.t("Bu tier için eşik zaten var; onu düzenleyin.", "A threshold for this tier already exists; edit it instead."));
        }
        // Ad: entity varsayılanı "default" — tier satırı o adla kalmasın (listede ayırt edilemez).
        if (t.getName() == null || t.getName().isBlank() || (t.getTier() != null && "default".equals(t.getName()))) {
            t.setName(t.getTier() == null ? "default" : "tier-" + t.getTier());
        }
        if (t.getActive() == null) t.setActive(true);
        AlertThreshold saved = thresholdRepo.save(t);
        thresholdPreviewService.afterThresholdChange();
        auditService.recordAction("THRESHOLD_CREATE", session, "ALERT_THRESHOLD", String.valueOf(saved.getId()), saved.getName(),
                com.sitemonitor.service.AuditDetail.of("tier", saved.getTier(), "warning_days", saved.getWarningDays(),
                        "high_days", saved.getHighDays(), "critical_days", saved.getCriticalDays()));
        return ok(Map.of("data", saved));
    }

    /**
     * Eşik satırı sil — YALNIZ tier satırları (2026-09-20). Varsayılan satır silinemez: tüm çözüm ona düşer.
     */
    @DeleteMapping("/thresholds/{id}")
    public ResponseEntity<Map<String, Object>> deleteThreshold(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "thresholds.edit", "edit");
        AlertThreshold existing = thresholdRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Threshold not found: " + id));
        if (existing.getTier() == null) {
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Varsayılan eşik silinemez.", "The default threshold cannot be deleted."));
        }
        thresholdRepo.delete(existing);
        thresholdPreviewService.afterThresholdChange();
        auditService.recordAction("THRESHOLD_DELETE", session, "ALERT_THRESHOLD", String.valueOf(id), existing.getName(),
                com.sitemonitor.service.AuditDetail.of("tier", existing.getTier()));
        return ok(Map.of("data", Map.of("deleted", true)));
    }

    /**
     * Eşik etki önizlemesi (2026-09-20): "bu değerlerle bugün kaç alan hangi seviyede olur?" — kalıcı yazma yok.
     * Kapsam satırın kapsamıyla aynı (tier satırı: o tier; varsayılan: tier'sız + kendi satırı olmayan tier'lar).
     */
    @GetMapping("/thresholds/preview")
    public ResponseEntity<Map<String, Object>> previewThreshold(
            @RequestParam(required = false) Integer tier,
            @RequestParam int warning, @RequestParam int high, @RequestParam int critical,
            HttpSession session) {
        requirePerm(session, "thresholds.read", "view");
        validateThreshold(tier, warning, high, critical);
        return ok(Map.of("data", thresholdPreviewService.preview(tier, warning, high, critical)));
    }

    /** Gün sırası kritik ≤ yüksek ≤ uyarı ve hepsi ≥ 0; tier 1..4 ya da boş. Ters sıra bir seviyeyi ulaşılmaz kılar. */
    private static void validateThreshold(Integer tier, Integer warning, Integer high, Integer critical) {
        if (tier != null && (tier < 1 || tier > 4)) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("Tier 1–4 arasında olmalı", "Tier must be between 1 and 4"));
        }
        if (warning == null || high == null || critical == null) return;   // PUT kısmi gövde: null = mevcut değer kalır
        if (warning < 0 || high < 0 || critical < 0) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("Gün değerleri negatif olamaz", "Day values cannot be negative"));
        }
        if (!(critical <= high && high <= warning)) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Sıra bozuk: kritik ≤ yüksek ≤ uyarı olmalı", "Out of order: critical ≤ high ≤ warning is required"));
        }
    }

    @PutMapping("/thresholds/{id}")
    public ResponseEntity<Map<String, Object>> updateThreshold(
            @PathVariable Long id, @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "thresholds.edit", "edit");
        AlertThreshold existing = thresholdRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Threshold not found: " + id));
        java.util.Map<String, Object> _before = AuditDiff.snapshot(existing,
                "name", "warningDays", "highDays", "criticalDays", "reAlertIntervalHours", "active");
        // Birleşik değerlerle sıra denetimi: kısmi gövde mevcut değerlerle tamamlanır (tier değiştirilemez).
        validateThreshold(existing.getTier(),
                t.getWarningDays() != null ? t.getWarningDays() : existing.getWarningDays(),
                t.getHighDays() != null ? t.getHighDays() : existing.getHighDays(),
                t.getCriticalDays() != null ? t.getCriticalDays() : existing.getCriticalDays());
        existing.setName(t.getName() != null ? t.getName() : existing.getName());
        existing.setWarningDays(t.getWarningDays() != null ? t.getWarningDays() : existing.getWarningDays());
        existing.setHighDays(t.getHighDays() != null ? t.getHighDays() : existing.getHighDays());
        existing.setCriticalDays(t.getCriticalDays() != null ? t.getCriticalDays() : existing.getCriticalDays());
        existing.setReAlertIntervalHours(t.getReAlertIntervalHours() != null
                ? t.getReAlertIntervalHours() : existing.getReAlertIntervalHours());
        existing.setActive(t.getActive() != null ? t.getActive() : existing.getActive());
        AlertThreshold saved = thresholdRepo.save(existing);
        thresholdPreviewService.afterThresholdChange();   // kart seviyeleri/istatistik eşikten türer (2026-09-20)
        auditService.recordAction("THRESHOLD_UPDATE", session, "ALERT_THRESHOLD", String.valueOf(id), saved.getName(),
                AuditDiff.diff(_before, AuditDiff.snapshot(saved,
                        "name", "warningDays", "highDays", "criticalDays", "reAlertIntervalHours", "active")));
        return ok(Map.of("data", saved));
    }

    // ── Escalation Contacts ───────────────────────────────────────────────────

    @GetMapping("/contacts")
    public ResponseEntity<Map<String, Object>> listContacts(HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        List<EscalationContact> contacts;
        if (isAdminOrAudit(session)) {
            contacts = contactRepo.findByActiveTrueOrderByRoleAsc();
        } else {
            List<Long> scope = viewScope(session);
            contacts = (scope == null || scope.isEmpty())
                    ? List.of()
                    : contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(scope);
        }
        return ok(Map.of("data", contacts));
    }

    @GetMapping("/contacts/all")
    public ResponseEntity<Map<String, Object>> listAllContacts(HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        List<EscalationContact> contacts;
        if (isAdminOrAudit(session)) {
            contacts = contactRepo.findAll();
        } else {
            List<Long> scope = viewScope(session);
            contacts = (scope == null || scope.isEmpty())
                    ? List.of()
                    : contactRepo.findByTeamIdInOrderByRoleAsc(scope);
        }
        return ok(Map.of("data", contacts));
    }

    /**
     * "Kim bilgilendirilir?" simülatörü (2026-09-20): takım + seviye (+ izleme grubu) → e-posta zinciri, eskalasyon
     * kişileri, kişi webhook'ları ve push alıcıları — gerçek gönderimle aynı kararlar, yazma yok.
     * Kapsam: kapsamlı kullanıcı yalnız görüş alanındaki takımı sorabilir.
     */
    @GetMapping("/recipients/simulate")
    public ResponseEntity<Map<String, Object>> simulateRecipients(
            @RequestParam Long teamId,
            @RequestParam(defaultValue = "HIGH") String level,
            @RequestParam(defaultValue = "CERT") String kind,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long ugTeamId,
            HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        if (!SessionScope.isGlobalViewer(session)) {
            List<Long> scope = SessionScope.viewTeamIds(session);
            if (scope == null || !scope.contains(teamId)) throw new NoSuchElementException("Team not found: " + teamId);
            // UG takımı da görüş kapsamında olmalı: yoksa kapsamlı kullanıcı başka takımın kişilerini görürdü.
            if (ugTeamId != null && !scope.contains(ugTeamId)) throw new NoSuchElementException("Team not found: " + ugTeamId);
        }
        boolean standalone = "MONITOR".equalsIgnoreCase(kind);
        // "Her sahip takım kendi kişisi" (2026-09-28): UG verilirse UG adresi + UG'nin KENDİ kişileri de gösterilir.
        Map<String, Object> out = new LinkedHashMap<>(ugTeamId == null
                ? escalationService.simulateRecipients(teamId, level, standalone, groupId)
                : escalationService.simulateRecipients(teamId, level, standalone, groupId, ugTeamId));
        out.put("team_name", teamRepo.findById(teamId).map(Team::getName).orElse(null));
        // Push ayağı (2026-09-28) — kişi kararları opt-out/org rolü taşır, görünürlük PushDecisionAccess'te: global
        // yönetici her takım, takımı YÖNETEN tüm üyeler, üye yalnız kendi satırı, diğerleri yalnız kanal durumu.
        out.putAll(PushDecisionAccess.pushLeg(session, teamId, String.valueOf(out.get("level")), standalone,
                userPushRecipientResolver, userPushService));
        return ok(Map.of("data", out));
    }

    /**
     * Eskalasyon kişisinin webhook'una TEST mesajı (2026-09-20): yanlış URL ilk kritik alarmda değil kurulumda
     * yakalansın. Sonuç notification_log'a yazılır (trigger WEBHOOK_TEST) → "son teslimat" sütunu güncellenir.
     */
    @PostMapping("/contacts/{id}/webhook-test")
    public ResponseEntity<Map<String, Object>> testContactWebhook(@PathVariable Long id, HttpSession session) {
        requirePerm(session, "contacts.crud", "edit");
        EscalationContact c = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        // F8 (2026-09-28): kardeşleri (kişi güncelle/sil) gibi YÖNETİM kapsamı — görüş kapsamı yetmez. Test gerçek bir
        // webhook'a mesaj atar ve teslimat günlüğüne yazar; kişiyi yalnız GÖREN (USER/AUDIT) bunu tetiklememeli.
        requireTeamScopedAdmin(session, c.getTeamId());
        if (c.getWebhookUrl() == null || c.getWebhookUrl().isBlank()) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t("Bu kişide webhook adresi yok.", "This contact has no webhook URL."));
        }
        String title = com.sitemonitor.util.Msg.t("SiteMonitor webhook testi", "SiteMonitor webhook test");
        String message = com.sitemonitor.util.Msg.t(
                "Bu bir test mesajıdır — eskalasyon kişisi \"" + c.getName() + "\" için webhook doğrulandı. Alarm değildir.",
                "This is a test message — the webhook for escalation contact \"" + c.getName() + "\" is working. Not an alert.");
        String status;
        String error = null;
        try {
            webhookService.send(c.getWebhookType(), c.getWebhookUrl(), title, message, "INFO");
            status = "SENT";
        } catch (Exception e) {
            error = e.getMessage();
            status = "FAILED: " + error;
        }
        try {
            NotificationLog entry = new NotificationLog();
            entry.setAlertEventId(0L);   // sentinel: alarm kaynaklı DEĞİL (kolon NOT NULL — rapor/anomali mailleriyle aynı desen)
            entry.setSentAt(now());
            entry.setRecipientName(c.getName());
            entry.setRecipientEmail(c.getEmail());
            entry.setRecipientRole(c.getRole());
            entry.setSubject(title);
            entry.setMessage(message);
            entry.setEmailStatus("SKIPPED");
            entry.setWebhookStatus(status);
            entry.setTrigger("WEBHOOK_TEST");
            notificationLogRepo.save(entry);
        } catch (RuntimeException e) {
            log.warn("Webhook test logu kaydedilemedi: {}", e.getMessage());
        }
        auditService.recordAction("CONTACT_WEBHOOK_TEST", session, "ESCALATION_CONTACT", String.valueOf(id), c.getName(),
                AuditDetail.of("webhook_type", c.getWebhookType(), "status", status));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "SENT".equals(status) ? "SENT" : "FAILED");
        data.put("error", error);
        data.put("target", com.sitemonitor.service.WebhookService.maskUrl(c.getWebhookUrl()));
        return ok(Map.of("data", data));
    }

    /** Kişi e-postası (küçük harf) → son webhook teslimatı {at, status, trigger} (2026-09-20). */
    @GetMapping("/contacts/webhook-status")
    public ResponseEntity<Map<String, Object>> contactWebhookStatus(HttpSession session) {
        requirePerm(session, "contacts.list", "view");
        // F2 (bug regresyon 2026-09-28): kapsamlı görüntüleyici yalnız GÖRDÜĞÜ kişilerin adreslerini alır — /contacts/all
        // ile aynı kapsam. Eskiden süzgeç yoktu: her USER/TEAM_ADMIN/müdür tüm takımların webhook alıcı e-postalarını,
        // son durumunu ve FAILED ayrıntısını görüyordu. (null = global görüntüleyici, süzgeç yok.)
        java.util.Set<String> visible = null;
        if (!isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            visible = new java.util.HashSet<>();
            if (scope != null && !scope.isEmpty()) {
                for (EscalationContact c : contactRepo.findByTeamIdInOrderByRoleAsc(scope)) {
                    if (c.getEmail() != null) visible.add(c.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (NotificationLog n : notificationLogRepo.findLatestWebhookPerRecipient()) {
            if (n.getRecipientEmail() == null) continue;
            if (visible != null && !visible.contains(n.getRecipientEmail().trim().toLowerCase(java.util.Locale.ROOT))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", n.getSentAt());
            String ws = n.getWebhookStatus() == null ? "" : n.getWebhookStatus();
            m.put("status", ws.startsWith("FAILED") ? "FAILED" : ws);
            m.put("detail", ws.startsWith("FAILED: ") ? ws.substring(8) : null);
            m.put("trigger", n.getTrigger());
            out.put(n.getRecipientEmail().trim().toLowerCase(java.util.Locale.ROOT), m);
        }
        return ok(Map.of("data", out));
    }

    @PostMapping("/contacts")
    public ResponseEntity<Map<String, Object>> addContact(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "contacts.crud", "edit");
        EscalationContact contact = new EscalationContact();
        applyContactFields(contact, body, session);
        contact.setId(null);
        contact.setCreatedAt(now());
        if (contact.getActive() == null) contact.setActive(true);
        if (contact.getMinAlertLevel() == null) contact.setMinAlertLevel("WARNING");
        if (!isAdmin(session)) {
            // F1 (bug regresyon 2026-09-28): global OLMAYAN her yazar — gövdede takım yoksa (TEAM_ADMIN'de hep)
            // birincil takım; hedef YÖNETİM kapsamında olmalı (403). Eskiden dal isTeamAdmin() ile kapılıydı: AD
            // müdürü (rol ADMIN, viewTeamIds dolu) oradan geçmiyor, team_id'si yok sayılıyor ve kişi (e-posta +
            // webhook) alfabetik İLK takıma yazılıyordu — o takımın eskalasyonunu alıyor, müdür göremiyor/silemiyordu.
            if (contact.getTeamId() == null) contact.setTeamId(SessionScope.primaryTeamId(session));
            requireTeamScopedAdmin(session, contact.getTeamId());
        }
        // Takım ZORUNLU (ürün kararı 2026-09-28): eskalasyon kişisi yalnız KENDİ takımının alarmlarını alır; takımsız
        // kişi hiçbir bildirim almaz. Eskiden global yönetici takımsız isteği alfabetik İLK takıma yazıyordu — kişi o
        // takımın alarmlarını alıyordu (yanlış takımın müdürü). Artık 400; takım bilinçli seçilir.
        if (contact.getTeamId() == null) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Eskalasyon kişisi için takım seçin — takımsız kişi hiçbir bildirim almaz.",
                    "Choose a team for the escalation contact — a contact with no team receives no notifications."));
        }
        EscalationContact saved = contactRepo.save(contact);
        auditService.recordAction("CONTACT_CREATE", session, "ESCALATION_CONTACT", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(Map.of("data", saved));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> updateContact(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        requireTeamScopedAdmin(session, existing.getTeamId());
        requirePerm(session, "contacts.crud", "edit");
        String[] cf = {"name", "email", "role", "minAlertLevel", "webhookType", "active", "teamId", "userId", "delayMinutes"};
        java.util.Map<String, Object> _before = AuditDiff.snapshot(existing, cf);
        applyContactFields(existing, body, session);
        EscalationContact saved = contactRepo.save(existing);
        auditService.recordAction("CONTACT_UPDATE", session, "ESCALATION_CONTACT", String.valueOf(id), saved.getName(),
                AuditDiff.diff(_before, AuditDiff.snapshot(saved, cf)));
        return ok(Map.of("data", saved));
    }

    private void applyContactFields(EscalationContact c, Map<String, Object> body, HttpSession session) {
        Long userId = toLong(body.get("user_id"));
        if (userId != null) {
            userRepo.findById(userId).ifPresent(u -> {
                c.setUserId(userId);
                c.setName(u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getUsername());
                c.setEmail(u.getEmail());
            });
        } else {
            String name = (String) body.get("name");
            String email = (String) body.get("email");
            if (name != null) c.setName(name);
            if (email != null) c.setEmail(email);
            c.setUserId(null);
        }
        String role = (String) body.get("role");
        if (role != null) c.setRole(role);
        String minAlertLevel = (String) body.get("min_alert_level");
        c.setMinAlertLevel(minAlertLevel != null ? minAlertLevel : (c.getMinAlertLevel() != null ? c.getMinAlertLevel() : "WARNING"));
        c.setWebhookUrl((String) body.get("webhook_url"));
        c.setWebhookType((String) body.get("webhook_type"));
        Object active = body.get("active");
        c.setActive(active instanceof Boolean ? (Boolean) active : (c.getActive() != null ? c.getActive() : true));
        // Zamana bağlı eskalasyon adımı (2026-10-01, opt-in): anahtar GÖNDERİLMEZSE dokunulmaz (eski istemci / form gecikmesiz
        // bırakıldı → bugünkü davranış). Gönderilirse: boş/null/0 = anlık (gecikmeyi kaldır), 1–1440 = dakika; aksi 400.
        if (body.containsKey("delay_minutes")) c.setDelayMinutes(EscalationDelay.parse(body.get("delay_minutes")));
        // Takım (F1, 2026-09-28): global admin serbest. Kapsamlı müdür kişiyi yalnız YÖNETTİĞİ takıma açar/taşır —
        // kapsam dışı hedef 403 (eskiden team_id'si sessizce yok sayılıyordu). TEAM_ADMIN'in takımı gövdeden
        // değişmez (createUser/updateUser ile aynı: kendi takımı zorlanır).
        Long teamId = toLong(body.get("team_id"));
        if (teamId != null && !teamId.equals(c.getTeamId())) {
            if (isAdmin(session)) {
                requireExistingTeam(teamId);   // olmayan takım → kişi hiçbir alarmı almaz (2026-09-28)
                c.setTeamId(teamId);
            } else if (!isTeamAdmin(session)) {
                requireTeamScopedAdmin(session, teamId);
                c.setTeamId(teamId);
            }
        }
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> deleteContact(
            @PathVariable Long id, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        requireTeamScopedAdmin(session, existing.getTeamId());
        requirePerm(session, "contacts.crud", "edit");
        contactRepo.deleteById(id);
        auditService.recordAction("CONTACT_DELETE", session, "ESCALATION_CONTACT", String.valueOf(id), existing.getName(), null);
        return ok(Map.of("message", "Deleted"));
    }

    // ── Alert Events (any authenticated user) ─────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String resolvedSince,
            @RequestParam(required = false) String resolvedUntil,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) String alertTypes,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) String noc,
            HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        int sz = Math.max(1, Math.min(size, 200));
        // "7/24'e gidenler" (2026-10-04): yalnız 7/24 AÇILIŞ teslimi gitmiş alarmlar — dört sorgunun …Noc ikizi (gövde aynı
        // sabitten, yalnız EXISTS eklenir). Alarmı görebilen HERKES süzebilir (yalnız operatör değil).
        boolean nocOnly = "sent".equalsIgnoreCase(noc) || "1".equals(noc) || "true".equalsIgnoreCase(noc);
        AlertRange win = AlertRange.of(range, since);   // varsayılan "aralıkta açılan"; range=active → aralıkta aktif
        // KAPALI DÜŞER: kapsam, parametrenin VERİLİP VERİLMEDİĞİNE bakar — doğrulamadan kaç tanesinin
        // sağ çıktığına DEĞİL. Aksi halde geçersiz bir tip adı (yeniden adlandırma, yazım hatası)
        // süzgeci sessizce DÜŞÜRÜR ve modal yine kendi üretmediği alarmları gösterir; yani kullanıcının
        // bildirdiği hata geri gelir. Şimdi hiçbir geçerli tip kalmazsa sonuç BOŞ döner (görünür hata).
        boolean typeScoped = alertTypes != null && !alertTypes.isBlank();
        List<String> typeList = parseAlertTypes(alertTypes);
        List<String> typesParam = typeList.isEmpty() ? List.of("-") : typeList;   // IN boş olamaz (scope deseniyle aynı)
        Boolean resolvedEffective = resolved != null ? resolved : (onlyOpen ? Boolean.FALSE : null);
        String alertTypeEffective = (alertType != null && !alertType.isBlank()) ? alertType.trim() : null;
        // Arama: kismi + buyuk/kucuk harf duyarsiz. Joker karakterler SORGUDA degil BURADA
        // uretilir; JPQL tarafinda CONCAT kullanmak lehce farklarina acik ve okunmasi zor.
        // Kullanicinin yazdigi % ve _ KACISLANIR, aksi halde tek bir "%" tum kayitlari getirir
        // ve arama sessizce filtresiz calisir.
        String qEffective = null;
        if (q != null && !q.isBlank()) {
            String esc = q.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            qEffective = "%" + esc + "%";
        }
        String levelEffective = (level != null && !level.isBlank()) ? level.trim().toUpperCase(java.util.Locale.ROOT) : null;
        // Sıralama (2026-10-01): sütun başlığından / URL'den `sort` + `dir`; beyaz liste dışı anahtar varsayılana düşer
        // (açılış, en yeni önce; kapalı görünümde kapanış anı). Seviye METİN saklandığı için CASE ile sıralanır.
        Sort sortSpec = AlertSort.of(sort, dir, resolvedEffective);
        // Takım kapsamı (IDOR engeli): global viewer (admin/AUDIT) tümünü; aksi halde alarmın takımı
        // (keyword/ping: e.teamId; cert: domain→envanter SY/UG) çağıranın görüntüleme kapsamında olmalı.
        // 7/24 operatörü (noc_calls.write) de TÜMÜNÜ görür — yalnız bu listede ve alarm okuma uçlarında (2026-09-27).
        List<Long> scope = seesAllAlerts(session) ? null : SessionScope.viewTeamIds(session);
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty()) {   // kapsamsız kullanıcı → hiçbir alarm
            return ok(Map.of("data", List.of(), "total", 0L, "page", 0, "size", sz,
                    "type_counts", Map.of()));
        }
        List<Long> scopeList = scoped ? scope : List.of(-1L);   // global'de dummy (scoped=false kısa-devre)
        Page<AlertEvent> result = nocOnly
                ? alertEventRepo.findFilteredNoc(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain, alertTypeEffective,
                        typeScoped, typesParam,
                        qEffective, levelEffective, acknowledged, teamId,
                        scoped, scopeList, PageRequest.of(Math.max(0, page), sz, sortSpec))
                : alertEventRepo.findFiltered(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain, alertTypeEffective,
                        typeScoped, typesParam,
                        qEffective, levelEffective, acknowledged, teamId,
                        scoped, scopeList, PageRequest.of(Math.max(0, page), sz, sortSpec));
        enrichAlerts(result.getContent());
        if (nocCallLog != null) nocCallLog.decorate(result.getContent());   // noc_call_count / noc_last_call — tek sorgu
        if (nocAlertFacts != null) nocAlertFacts.decorate(result.getContent());   // noc_sent_at / noc_via_storm — tek sorgu
        // Tip filtre pill'lerinin canlı sayıları — tip filtresinden bağımsız
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        for (Object[] row : nocOnly
                ? alertEventRepo.countFilteredByTypeNoc(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain,
                        typeScoped, typesParam,
                        qEffective, levelEffective, acknowledged, teamId, scoped, scopeList)
                : alertEventRepo.countFilteredByType(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain,
                        typeScoped, typesParam,
                        qEffective, levelEffective, acknowledged, teamId, scoped, scopeList)) {
            typeCounts.put(String.valueOf(row[0]), (Long) row[1]);
        }
        // İstatistik şeridi sayaçları: seviye kırılımı + sahiplenilmemiş toplamı. Kendi
        // boyutları (level/acknowledged) BİLEREK uygulanmaz — aksi halde "Kritik" kartına
        // basınca diğer kartlar sıfırlanır ve kullanıcı seçimden geri dönemez.
        Map<String, Long> levelCounts = new LinkedHashMap<>();
        long unackedTotal = 0L;
        for (Object[] row : nocOnly
                ? alertEventRepo.countFacetsNoc(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain, alertTypeEffective,
                        typeScoped, typesParam,
                        qEffective, teamId, scoped, scopeList)
                : alertEventRepo.countFacets(
                        resolvedEffective, win.openedSince(), until, resolvedSince, resolvedUntil, win.activeFrom(), domain, alertTypeEffective,
                        typeScoped, typesParam,
                        qEffective, teamId, scoped, scopeList)) {
            String lvl = String.valueOf(row[0]);
            long n = (Long) row[2];
            levelCounts.merge(lvl, n, Long::sum);
            if (!Boolean.TRUE.equals(row[1])) unackedTotal += n;
        }
        // "Uzun süredir açık": bu yaştan ESKİ alarmlar unutulmuş sayılır. Karşılaştırma
        // sözlükseldir; created_at sabit genişlikte (19 karakter) ISO-UTC olduğu için güvenli —
        // mevcut since/until yüklemleri de aynı deseni kullanıyor.
        String staleBefore = ISO.format(Instant.now().minus(java.time.Duration.ofHours(ALERT_STALE_HOURS)));
        long staleTotal = nocOnly
                ? alertEventRepo.countStaleNoc(resolvedEffective, staleBefore, domain,
                        alertTypeEffective, typeScoped, typesParam, qEffective, teamId, scoped, scopeList)
                : alertEventRepo.countStale(resolvedEffective, staleBefore, domain,
                        alertTypeEffective, typeScoped, typesParam, qEffective, teamId, scoped, scopeList);

        // Push kanal özeti (2026-09-12, #16): sayfadaki alarmlar için {sent, failed, skipped, other} — "neden hâlâ açık"
        // satırına e-posta alıcılarının yanında push'un da ulaşıp ulaşmadığını koyar. Tek grup sorgusu; düşerse boş.
        Map<String, Map<String, Long>> pushSummary = new LinkedHashMap<>();
        try {
            List<Long> ids = result.getContent().stream().map(AlertEvent::getId).filter(Objects::nonNull).toList();
            if (!ids.isEmpty()) {
                for (Object[] row : userPushDeliveryRepo.countByAlertEventIdInGroupByStatus(ids)) {
                    String id = String.valueOf(row[0]);
                    String st = String.valueOf(row[1]);
                    long n = ((Number) row[2]).longValue();
                    Map<String, Long> m = pushSummary.computeIfAbsent(id, k -> new LinkedHashMap<>(Map.of("sent", 0L, "failed", 0L, "skipped", 0L, "other", 0L)));
                    String key = "SENT".equals(st) ? "sent" : "FAILED".equals(st) ? "failed" : st != null && st.startsWith("SKIPPED") ? "skipped" : "other";
                    m.merge(key, n, Long::sum);
                }
            }
        } catch (Exception e) {
            log.debug("Alarm listesi push özeti alınamadı: {}", e.toString());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data",         result.getContent());
        body.put("push_summary", pushSummary);
        body.put("total",        result.getTotalElements());
        body.put("page",         result.getNumber());
        body.put("size",         result.getSize());
        body.put("type_counts",  typeCounts);
        body.put("level_counts", levelCounts);
        body.put("unacked_total", unackedTotal);
        // Yalnız AÇIK sekmede anlamlı: kapalı sekmede "24 saatten eski" demek olur,
        // "24 saattir açık" değil. Arayüz kartı yalnız açık sekmede gösterir.
        body.put("stale_total", staleTotal);
        body.put("stale_hours", ALERT_STALE_HOURS);
        body.put("noc_filter", nocOnly);
        // Arayüzün "Arama kaydı ekle" kapısı SUNUCUDAN (2026-09-27): matris anlık görüntüsü kapsamlı müdürde ADMIN
        // satırını gösterir; asıl kural (global yönetici evet, kapsamlı müdür hayır) NocCallLogService.canWrite'ta.
        body.put("noc_can_write", nocCallLog != null && nocCallLog.canWrite(session));
        // Sahiplen/çöz/yeniden bildir kapısı (alerts.actions) arayüze SUNUCUDAN (2026-09-28): 7/24 operatörü AUDIT
        // rolünde arama kaydı girer ama bu eylemlerin izni yoktur — düğmeler 403'e giden ölü düğme olarak çizilmesin.
        body.put("can_act", permissionService.allows(session, "alerts.actions", "execute"));
        return ok(body);
    }


    /**
     * Alarm Geçmişi CSV dışa aktarımı — EKRANDAKİ filtrelerin AYNISIYLA.
     *
     * <p>Filtreler listeyle birebir aynı parametreleri alır; kullanıcı ekranda ne görüyorsa onu
     * indirir. Ayrı bir filtre yüzeyi olsaydı "ekranda 12 satır vardı, dosyada 800 çıktı" tipi
     * bir sürpriz kaçınılmaz olurdu.
     *
     * <p>Kapsam (IDOR) listeyle aynı: global görüntüleyici değilse yalnız kendi takımlarının
     * alarmları. CSV, yetki atlatmak için kestirme bir yol OLMAMALI.
     *
     * <p>Yanıt doğrudan servlet çıkışına akıtılır ve {@code null} dönülür — CheckHistoryService'in
     * CSV yolundaki aynı gerekçe (StreamingResponseBody wildcard dönüş tipinde devreye girmiyor).
     */
    @GetMapping("/alerts/export")
    public ResponseEntity<Void> exportAlerts(
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String resolvedSince,
            @RequestParam(required = false) String resolvedUntil,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) String alertTypes,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String noc,
            HttpSession session,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        requirePerm(session, "alerts.read", "view");
        boolean nocOnly = "sent".equalsIgnoreCase(noc) || "1".equals(noc) || "true".equalsIgnoreCase(noc);
        AlertRange win = AlertRange.of(range, since);   // ekranla AYNI tarih kipi (aralıkta açılan / aktif)

        String alertTypeEffective = (alertType != null && !alertType.isBlank()) ? alertType.trim() : null;
        // CSV, ekranla AYNI filtreleri kullanmak zorunda: tip kapsamı burada da uygulanmazsa
        // kullanıcı ekranda 1 alarm görüp dosyada 3 alarm indirirdi.
        // CSV indirmesi ekranla AYNI süzgeci taşımak zorunda (bkz. liste ucundaki gerekçe).
        boolean csvTypeScoped = alertTypes != null && !alertTypes.isBlank();
        List<String> csvTypeList = parseAlertTypes(alertTypes);
        List<String> csvTypesParam = csvTypeList.isEmpty() ? List.of("-") : csvTypeList;
        String qEffective = null;
        if (q != null && !q.isBlank()) {
            String esc = q.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_");
            qEffective = "%" + esc + "%";
        }
        String levelEffective = (level != null && !level.isBlank())
                ? level.trim().toUpperCase(java.util.Locale.ROOT) : null;

        // Kapsam LİSTEYLE aynı (2026-10-04): 7/24 operatörü ekranda tüm takımları görüyorsa dosyada da görür.
        List<Long> scope = seesAllAlerts(session) ? null : SessionScope.viewTeamIds(session);
        boolean scoped = scope != null;
        List<Long> scopeList = scoped ? scope : List.of(-1L);

        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"alarm-gecmisi_" + ISO.format(Instant.now()).substring(0, 10) + ".csv\"");
        java.io.Writer w = new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8);
        w.write(0xFEFF);   // Excel UTF-8'i doğru açsın (mevcut dışa aktarımlarla aynı)

        String[] headers = { "created_at", "resolved_at", "domain", "alert_type", "alert_level",
                "acknowledged", "acknowledged_by", "acknowledged_at", "acknowledged_note",
                "resolved_by", "resolved_note",
                "days_remaining", "sy_team", "ug_team", "cert_tier", "repeat_count", "message" };
        writeCsvRow(w, headers);

        int rows = 0;
        if (!(scoped && scope.isEmpty())) {          // kapsamsız kullanıcı → yalnız başlık satırı
            for (int page = 0; rows < ALERT_CSV_MAX_ROWS; page++) {
                var pageReq = PageRequest.of(page, ALERT_CSV_PAGE, Sort.by(Sort.Direction.DESC, "createdAt"));
                var chunk = (nocOnly
                        ? alertEventRepo.findFilteredNoc(resolved, win.openedSince(), until, resolvedSince, resolvedUntil,
                                win.activeFrom(), domain, alertTypeEffective, csvTypeScoped, csvTypesParam,
                                qEffective, levelEffective, acknowledged, teamId, scoped, scopeList, pageReq)
                        : alertEventRepo.findFiltered(resolved, win.openedSince(), until, resolvedSince, resolvedUntil,
                                win.activeFrom(), domain, alertTypeEffective, csvTypeScoped, csvTypesParam,
                                qEffective, levelEffective, acknowledged, teamId, scoped, scopeList, pageReq))
                        .getContent();
                if (chunk.isEmpty()) break;
                enrichAlerts(chunk);                 // takım adları / tier / tekrar sayısı ekranla aynı
                for (AlertEvent e : chunk) {
                    writeCsvRow(w, new String[]{
                            e.getCreatedAt(), e.getResolvedAt(), e.getDomain(), e.getAlertType(), e.getAlertLevel(),
                            String.valueOf(Boolean.TRUE.equals(e.getAcknowledged())),
                            e.getAcknowledgedBy(), e.getAcknowledgedAt(), e.getAcknowledgedNote(),
                            e.getResolvedBy(), e.getResolvedNote(),
                            e.getDaysRemaining() == null ? "" : String.valueOf(e.getDaysRemaining()),
                            e.getSyTeamName(), e.getUgTeamName(),
                            e.getCertTier() == null ? "" : String.valueOf(e.getCertTier()),
                            e.getRepeatCount() == null ? "" : String.valueOf(e.getRepeatCount()),
                            e.getMessage() });
                    rows++;
                }
                if (chunk.size() < ALERT_CSV_PAGE) break;
            }
        }
        w.flush();
        return null;
    }

    private static void writeCsvRow(java.io.Writer w, String[] cells) throws java.io.IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) w.write(',');
            w.write(csvCell(cells[i]));
        }
        w.write("\r\n");
    }

    /**
     * CSV hücresi — kaçışlama VE formül enjeksiyonu koruması; kural {@link com.sitemonitor.util.Csv}
     * içinde tek yerde durur (denetim dışa aktarımı ve kontrol geçmişi de aynı kuralı kullanır).
     */
    static String csvCell(String s) {
        return com.sitemonitor.util.Csv.cell(s);
    }

    /**
     * Bulk-populates @Transient fields on AlertEvent: SY/UG team names, tier,
     * and per-event email sent/failed counts. Single round-trip per related
     * table — no N+1.
     */
    private void enrichAlerts(List<AlertEvent> events) {
        if (events.isEmpty()) return;

        Set<String> domains = new HashSet<>();
        Set<Long> alertIds  = new HashSet<>();
        for (AlertEvent ev : events) {
            if (ev.getDomain() != null) domains.add(ev.getDomain());
            if (ev.getId() != null)     alertIds.add(ev.getId());
        }

        Map<String, CertificateInventory> invByDomain = new HashMap<>();
        Set<Long> teamIds = new HashSet<>();
        for (AlertEvent ev : events) if (ev.getTeamId() != null) teamIds.add(ev.getTeamId());   // damgalı takım → team_name
        if (!domains.isEmpty()) {
            for (CertificateInventory inv : inventoryRepo.findByDomainIn(domains)) {
                invByDomain.putIfAbsent(inv.getDomain(), inv);
                if (inv.getTeamId()   != null) teamIds.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) teamIds.add(inv.getUgTeamId());
            }
        }

        Map<Long, String> teamNames = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (Team t : teamRepo.findAllById(teamIds)) {
                teamNames.put(t.getId(), t.getName());
            }
        }

        Map<Long, long[]> mailCounts = new HashMap<>();
        if (!alertIds.isEmpty()) {
            for (Object[] row : notificationLogRepo.countByAlertIds(alertIds)) {
                Long alertId = ((Number) row[0]).longValue();
                long sent    = row[1] == null ? 0 : ((Number) row[1]).longValue();
                long failed  = row[2] == null ? 0 : ((Number) row[2]).longValue();
                long webhook = row.length > 3 && row[3] != null ? ((Number) row[3]).longValue() : 0;   // O-A3-6
                mailCounts.put(alertId, new long[]{ sent, failed, webhook });
            }
        }

        // Tekrar sayısı: (domain, tip) → son N gündeki alarm adedi. TEK sorgu — kart başına
        // sorgu N+1 olurdu (50 kayıtlık sayfada 50 sorgu).
        //
        // Anahtar bir KAYIT; iki alan tek dizeye paketlenip ayırıcıyla birleştirilmiyor. Domain
        // adlarında boşluk bulunabildiği için (canlı veride var) boşluk ayırıcısı sessiz çakışma
        // üretirdi: ("a b","C") ile ("a","b C") aynı anahtara düşerdi.
        Map<RepeatKey, Long> repeatCounts = new HashMap<>();
        if (!domains.isEmpty()) {
            String since = ISO.format(Instant.now().minus(java.time.Duration.ofDays(ALERT_REPEAT_WINDOW_DAYS)));
            for (Object[] row : alertEventRepo.countRecentByDomainAndType(domains, since)) {
                repeatCounts.put(new RepeatKey(str(row[0]), str(row[1])), (Long) row[2]);
            }
        }

        // Sertifika alarmlarında güncel bitiş (LatestCheck): eski damgasız satırlara görüntü yedeği,
        // yeni satırlarda "alarm anı ≠ güncel" ise yenilenme rozeti. Tek toplu sorgu.
        Map<String, String> latestNotAfter = new HashMap<>();
        if (!domains.isEmpty()) {
            for (com.sitemonitor.model.LatestCheck lc : latestCheckRepo.findByDomainIn(domains)) {
                if (lc.getNotAfter() != null) latestNotAfter.putIfAbsent(lc.getDomain(), lc.getNotAfter());
            }
        }

        // İmza geçmişi (2026-09-16): "bu alarm daha önce kaç kez açıldı, önceki oluşum ne zaman,
        // en son ne zaman görüldü/kapandı". İKİ toplu sorgu: özet (tüm zamanlar) + son N satırlık
        // zaman çizelgesi (önceki oluşumu bulmak için; tavanlı — tek imza binlerce satır olabilir).
        Map<RepeatKey, long[]> historySummary = new HashMap<>();
        Map<RepeatKey, String[]> historyStamps = new HashMap<>();   // [first, last, lastResolved]
        Map<RepeatKey, List<String>> timeline = new HashMap<>();
        if (!domains.isEmpty()) {
            try {
                for (Object[] row : alertEventRepo.summarizeHistoryByDomainAndType(domains)) {
                    RepeatKey k = new RepeatKey(str(row[0]), str(row[1]));
                    historySummary.put(k, new long[]{ ((Number) row[2]).longValue() });
                    historyStamps.put(k, new String[]{ str(row[3]), str(row[4]), str(row[5]) });
                }
                for (Object[] row : alertEventRepo.findSignatureTimeline(domains,
                        PageRequest.of(0, ALERT_TIMELINE_SAMPLE))) {
                    timeline.computeIfAbsent(new RepeatKey(str(row[0]), str(row[1])), k -> new ArrayList<>())
                            .add(str(row[2]));   // sorgu zaten createdAt DESC
                }
            } catch (Exception e) {
                log.debug("Alarm imza geçmişi alınamadı: {}", e.toString());
            }
        }

        for (AlertEvent ev : events) {
            ev.setRepeatCount(repeatCounts.get(new RepeatKey(ev.getDomain(), ev.getAlertType())));
            RepeatKey sig = new RepeatKey(ev.getDomain(), ev.getAlertType());
            long[] hist = historySummary.get(sig);
            if (hist != null) ev.setHistoryCount(hist[0]);
            String[] stamps = historyStamps.get(sig);
            if (stamps != null) {
                ev.setHistoryFirstAt(stamps[0]);
                ev.setHistoryLastAt(stamps[1]);
                ev.setHistoryLastResolvedAt(stamps[2]);
            }
            List<String> stampsList = timeline.get(sig);
            if (stampsList != null && ev.getCreatedAt() != null) {
                for (String at : stampsList) {            // DESC sıralı: ilk KÜÇÜK olan önceki oluşumdur
                    if (at != null && at.compareTo(ev.getCreatedAt()) < 0) { ev.setHistoryPrevAt(at); break; }
                }
            }
            if (EscalationService.CERT_ALERT_TYPES.contains(ev.getAlertType())) {
                String current = latestNotAfter.get(ev.getDomain());
                ev.setCurrentNotAfter(current);
                if (ev.getNotAfter() == null && current != null) ev.setNotAfter(current);   // yalnız görüntü — kaydedilmez
            }
            if (ev.getTeamId() != null) ev.setTeamName(teamNames.get(ev.getTeamId()));
            CertificateInventory inv = invByDomain.get(ev.getDomain());
            if (inv != null) {
                ev.setSyTeamName(inv.getTeamId()   != null ? teamNames.get(inv.getTeamId())   : null);
                ev.setUgTeamName(inv.getUgTeamId() != null ? teamNames.get(inv.getUgTeamId()) : null);
                ev.setSyTeamId(inv.getTeamId());
                ev.setUgTeamId(inv.getUgTeamId());
                ev.setCertTier(inv.getTier());
            }
            long[] counts = mailCounts.getOrDefault(ev.getId(), new long[]{0, 0, 0});
            ev.setEmailSentCount(counts[0]);
            ev.setEmailFailedCount(counts[1]);
            ev.setWebhookSentCount(counts.length > 2 ? counts[2] : 0L);   // O-A3-6: webhook-tek teslimat "kimseye ulaşmadı" değil
        }
    }

    /**
     * Denetim ayrıntısı — DÜZGÜN JSON.
     *
     * <p>Eskiden {@code "{\"domain\":\"" + domain + "\"}"} diye elle birleştiriliyordu. Gerekçe
     * notu serbest metin ve gerekçe cümlesi yazan kullanıcı TIRNAK kullanır; elle birleştirme
     * ilk tırnakta bozuk JSON üretirdi. Kaçış işini Jackson yapıyor.
     */
    private String auditDetail(Map<String, Object> fields) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields);
        } catch (Exception e) {
            // Denetim kaydı asıl işlemi düşürmemeli; en kötü ihtimalle ayrıntısız kalır.
            log.warn("Denetim ayrıntısı serileştirilemedi: {}", e.getMessage());
            return "{}";
        }
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        // Gerekçe ZORUNLU ve sunucuda doğrulanıyor: kural yalnız arayüzde kalsaydı API'den
        // notsuz geçilebilir, "her manuel onayın gerekçesi vardır" garantisi çökerdi.
        String note = AlertActionNote.require(body == null ? null : str(body.get("note")));
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.acknowledge(id, by, note);
        auditService.recordAction("ALERT_ACKNOWLEDGE", session, request,
                "ALERT_EVENT", id.toString(),
                auditDetail(Map.of("domain", nz(event.getDomain()), "note", note)));
        return ok(Map.of("data", event, "message", "Alert acknowledged"));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        String note = AlertActionNote.require(body == null ? null : str(body.get("note")));
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.resolve(id, by, note);
        auditService.recordAction("ALERT_RESOLVE", session, request,
                "ALERT_EVENT", id.toString(),
                auditDetail(Map.of("domain", nz(event.getDomain()), "note", note)));
        return ok(Map.of("data", event, "message", "Alert resolved"));
    }

    /** "Tekrar Bildir" onay pop-up'ı için alıcı önizlemesi — gönderim/yazma YAPMAZ.
     *  Perm+scope çifti re-notify ile birebir aynı (önizleyebilen = gönderebilen). */
    @GetMapping("/alerts/{id}/re-notify/preview")
    public ResponseEntity<Map<String, Object>> previewReNotifyAlert(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        List<Map<String, Object>> recipients = escalationService.previewReNotify(id).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("email", r.email());
                    m.put("name",  r.name());
                    m.put("role",  r.role());
                    m.put("kind",  r.kind());
                    return m;
                }).toList();
        // Webhook (push) kanali AYRI listelenir: kullanici "kime mail, kime push" gidecegini
        // gonderim ONCESI gorup tek tek cikarabilmeli. Kanal tamamen kapaliysa sebebi de doner
        // (SKIPPED_TEAM_OFF / _NO_RECIPIENTS ...) — sessiz "gitmedi" yerine gorunur bir neden.
        // Fallback takim, gercek gonderimin kullandigiyla AYNI kaynaktan gelir
        // (resolveReNotifyTargets) — onizleme gonderimden sapmasin.
        var push = userPushService.preview(id, escalationService.reNotifyFallbackTeamId(id));
        List<Map<String, Object>> pushRows = push.recipients().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("username",     r.username());
                    m.put("display_name", r.displayName());
                    m.put("status",       r.status());
                    return m;
                }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alert_id",   id);
        data.put("recipients", recipients);          // e-posta kanali (mevcut sozlesme korunur)
        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("channel_enabled", push.channelEnabled());
        webhook.put("block_reason",    push.blockReason());
        webhook.put("recipients",      pushRows);
        data.put("webhook", webhook);
        return ok(Map.of("data", data));
    }

    @PostMapping("/alerts/{id}/re-notify")
    public ResponseEntity<Map<String, Object>> reNotifyAlert(
            @PathVariable Long id, HttpSession session,
            @RequestBody(required = false) Map<String, Object> body) {
        requirePerm(session, "alerts.actions", "execute");
        requireAlertScope(session, id);   // takım kapsamı (IDOR engeli)
        // Onay pop-up'ından gelen opsiyonel hariç-tutma listesi (kullanıcının listeden çıkardıkları).
        Set<String> excludes = new LinkedHashSet<>();
        if (body != null && body.get("excludeEmails") instanceof List<?> raw) {
            for (Object o : raw) if (o != null && !o.toString().isBlank()) excludes.add(o.toString());
        }
        // Webhook alicilari AYRI liste: kullanici bir kisiye mail gitmesin ama push gitsin diyebilir.
        Set<String> excludeUsers = new LinkedHashSet<>();
        if (body != null && body.get("excludeUsernames") instanceof List<?> raw) {
            for (Object o : raw) if (o != null && !o.toString().isBlank()) excludeUsers.add(o.toString());
        }
        Object result = escalationService.reNotify(id, excludes, excludeUsers);

        // Toplu hâli (ALERT_BULK_RENOTIFY) yıllardır denetleniyordu, tekil hâli hiç: aynı yıkıcı
        // olmayan ama DIŞARIYA e-posta/webhook gönderen işlem, tek tıkla iz bırakmadan yapılabiliyordu.
        // Hariç tutulanlar SAYIYLA değil LİSTEYLE yazılır: "neden X'e bildirim gitmedi?" sorusunun
        // tek cevabı, bir insanın onay kutusunda onu listeden çıkarmış olmasıdır — denetlenmesi
        // gereken karar tam olarak budur.
        auditService.recordAction("ALERT_RENOTIFY", session, "ALERT_EVENT", String.valueOf(id),
                AuditDetail.of("excluded_emails", excludes,
                        "excluded_usernames", excludeUsers,
                        "excluded_count", excludes.size() + excludeUsers.size()), null);

        return ok(Map.of("data", result, "message", "Notification triggered"));
    }

    /** Toplu alarm işlemi (Alarm Geçmişi çoklu seçim): acknowledge | resolve | re-notify.
     *  Kapsam-dışı (IDOR) ya da hatalı id'ler atlanır/sayılır, batch durmaz. Bkz. /inventory/bulk deseni.
     *  Not: @Transactional DEĞİL — her escalationService çağrısı kendi tx'ini + yan etkisini (mail) yönetir. */
    @PostMapping("/alerts/bulk")
    public ResponseEntity<Map<String, Object>> bulkAlertAction(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requirePerm(session, "alerts.actions", "execute");
        String action = body.get("action") != null ? body.get("action").toString().trim().toLowerCase() : "";
        if (!Set.of("acknowledge", "resolve", "re-notify").contains(action)) {
            throw new IllegalArgumentException("action must be one of: acknowledge, resolve, re-notify");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) { Long id = toLong(o); if (id != null) ids.add(id); }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("No ids provided");

        // Gerekçe TOPLU işlemde de zorunlu (onayla/çöz). Yalnız tekli uçta istenseydi zorunluluk
        // delinirdi: kullanıcı tek alarmı seçip "toplu onayla" diyerek notsuz geçerdi ve zamanla
        // herkes o yolu kullanırdı. Tek not seçilen bütün alarmlara yazılır.
        boolean needsNote = "acknowledge".equals(action) || "resolve".equals(action);
        String note = needsNote ? AlertActionNote.require(str(body.get("note"))) : null;

        String by = resolveDisplayName(session);
        int processed = 0, skipped = 0, failed = 0;
        for (Long id : ids) {
            if (!isAlertInScope(session, id)) { skipped++; continue; }   // kapsam-dışı → atla (fırlatma yok)
            try {
                switch (action) {
                    case "acknowledge" -> escalationService.acknowledge(id, by, note);
                    case "resolve"     -> escalationService.resolve(id, by, note);
                    case "re-notify"   -> escalationService.reNotify(id);
                }
                processed++;
            } catch (Exception e) {
                failed++;   // bulunamadı / zaten kapalı / bildirim hatası — say ama batch'i durdurma
                log.warn("Bulk alert '{}' failed for id={}: {}", action, id, e.getMessage());
            }
        }
        String auditAction = switch (action) {
            case "acknowledge" -> "ALERT_BULK_ACKNOWLEDGE";
            case "resolve"     -> "ALERT_BULK_RESOLVE";
            default             -> "ALERT_BULK_RENOTIFY";
        };
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("processed", processed);
        detail.put("skipped", skipped);
        detail.put("failed", failed);
        if (note != null) detail.put("note", note);
        auditService.recordAction(auditAction, session, request, "ALERT_EVENT",
                processed + " alert", auditDetail(detail));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processed", processed);
        data.put("skipped", skipped);
        data.put("failed", failed);
        return ok(Map.of("data", data, "message", "Bulk " + action + " complete"));
    }

    /**
     * Tekil uyarı (2026-09-27) — listeyle AYNI zenginleştirme + 7/24 arama özeti. 7/24 e-postasındaki "Arama kaydı ekle"
     * derin bağlantısı uyarı açık listenin ilk sayfasında değilse (fırtına, kapalı uyarı) detayı buradan açar.
     * Kapı listeyle aynı: {@code alerts.read} + takım kapsamı (7/24 operatörü ve global görücü tümü).
     */
    @GetMapping("/alerts/{id}")
    public ResponseEntity<Map<String, Object>> getAlert(@PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        requireAlertReadScope(session, id);
        AlertEvent ev = alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + id));
        List<AlertEvent> one = new ArrayList<>(List.of(ev));
        enrichAlerts(one);
        if (nocCallLog != null) nocCallLog.decorate(one);
        if (nocAlertFacts != null) nocAlertFacts.decorate(one);   // "7/24 ekibine iletildi · 14:05" (2026-10-04)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", ev);
        body.put("noc_can_write", nocCallLog != null && nocCallLog.canWrite(session));
        body.put("can_act", permissionService.allows(session, "alerts.actions", "execute"));
        return ok(body);
    }

    @GetMapping("/alerts/{id}/notifications")
    public ResponseEntity<Map<String, Object>> getAlertNotifications(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        requireAlertReadScope(session, id);   // takım kapsamı (IDOR engeli); 7/24 operatörü tümü (okuma)
        // 7/24 (NOC) satırları alarmı gören HERKESE açık — gövde/alıcı daima MASKELİ biçimde döner (arama listesi
        // telefonları ve 7/24 grup adresleri takım üyesine, kapsamlı yöneticiye, denetçiye sızmasın; 2026-09-27).
        return ok(Map.of("data", notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(id).stream()
                .map(com.sitemonitor.service.noc.NocLogRedaction::forViewer).toList()));
    }

    /**
     * Alarm modalının kanal-ayrımlı "Webhook" bölümü: olayın kişi-push teslimatları.
     * E-posta satırlarıyla (üstteki uç) AYNI yetki kapısı — alerts.read + takım kapsamı.
     */
    @GetMapping("/alerts/{id}/push-deliveries")
    public ResponseEntity<Map<String, Object>> getAlertPushDeliveries(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        requireAlertReadScope(session, id);   // takım kapsamı (IDOR engeli); 7/24 operatörü tümü (okuma)
        return ok(Map.of("data", userPushDeliveryRepo.findByAlertEventIdOrderByIdAsc(id)));
    }

    /**
     * Fırtına push'u ↔ alarm bağı (2026-10-04) — isteğe bağlı: dilimli test bağlamında yokken uç boş bölüm döner.
     * Alan enjeksiyonu: {@code @RequiredArgsConstructor} imzası değişmez.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.StormPushCoverageService stormPushCoverage;

    /**
     * Alarm detayının "Fırtına push'u" bölümü (2026-10-04, kullanıcı isteği): bu alarmı KAPSAYAN toplu fırtına push'ları
     * (açılış / günlük tekrar / çözüm), ne zaman kime iletildiği (alıcı satırları push bölümüyle aynı alanlar), kayıt
     * öncesi bildirimler için tahmin ({@code inferred}) ve fırtınaya devredilmiş ama henüz duyurulmamış açık fırtına
     * ({@code pending}). Kapı push bölümüyle AYNI: {@code alerts.read} + takım kapsamı (7/24 operatörü okur).
     */
    @GetMapping("/alerts/{id}/storm-push")
    public ResponseEntity<Map<String, Object>> getAlertStormPush(@PathVariable Long id, HttpSession session) {
        requirePerm(session, "alerts.read", "view");
        requireAlertReadScope(session, id);   // takım kapsamı (IDOR engeli); 7/24 operatörü tümü (okuma)
        AlertEvent ev = alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + id));
        if (stormPushCoverage == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("alert_id", id); empty.put("push_individual", false); empty.put("handed_over", false);
            empty.put("items", List.of()); empty.put("pending", List.of()); empty.put("storms", List.of());
            return ok(Map.of("data", empty));
        }
        // Olayda takım damgası yoksa (sertifika alarmı) push takımı envanterin SY takımıdır — yalnız tahmin için.
        Long fallbackTeam = ev.getTeamId() != null || ev.getDomain() == null ? null
                : inventoryRepo.findByDomain(ev.getDomain()).map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
        return ok(Map.of("data", stormPushCoverage.alarmDetail(ev, fallbackTeam)));
    }

    // ── Alarm takım kapsamı (IDOR engeli) ─────────────────────────────────────
    /** Bir alarmın ait olabileceği takım id'leri: kendi teamId'si (keyword/ping) + cert alarmında
     *  domain→envanter (SY teamId + UG ugTeamId). cert alarmlarında teamId NULL olduğundan envanter şart. */
    private Set<Long> alertTeamIds(AlertEvent ev) {
        Set<Long> ids = new HashSet<>();
        if (ev.getTeamId() != null) ids.add(ev.getTeamId());
        if (ev.getDomain() != null) {
            inventoryRepo.findByDomain(ev.getDomain()).ifPresent(inv -> {
                if (inv.getTeamId()   != null) ids.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) ids.add(inv.getUgTeamId());
            });
        }
        return ids;
    }

    /** Alarm takıma-gizli: global viewer (admin/AUDIT) tümünü; aksi halde alarmın takım(lar)ından
     *  biri çağıranın görüntüleme kapsamında olmalı (yoksa 403). Global olmayanda alarmı yükler. */
    private void requireAlertScope(HttpSession session, Long id) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> v = SessionScope.viewTeamIds(session);
        AlertEvent ev = alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + id));
        if (v != null) for (Long t : alertTeamIds(ev)) if (v.contains(t)) return;
        throw new SecurityException("Bu alarm sizin takım(lar)ınıza ait değil");
    }

    /**
     * 7/24 arama kaydı (2026-09-27): uyarı listesini/detayını TÜM takımlar için görebilen — global görücü (admin/AUDIT)
     * ya da 7/24 operatörü ({@code noc_calls.write}; kapsamlı müdür değil). Yalnız OKUMA uçları kullanır.
     */
    private boolean seesAllAlerts(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return true;
        return nocCallLog != null && nocCallLog.seesAllAlerts(session);
    }

    /** {@link #requireAlertScope}'un OKUMA hâli — 7/24 operatörü tümünü görür. Yazma eylemleri requireAlertScope'ta kalır. */
    private void requireAlertReadScope(HttpSession session, Long id) {
        if (seesAllAlerts(session)) return;
        requireAlertScope(session, id);
    }

    /** requireAlertScope'un fırlatmayan sürümü — toplu işlemde kapsam-dışı/eksik id'yi atlamak için. */
    private boolean isAlertInScope(HttpSession session, Long id) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v == null) return false;
        AlertEvent ev = alertEventRepo.findById(id).orElse(null);
        if (ev == null) return false;
        for (Long t : alertTeamIds(ev)) if (v.contains(t)) return true;
        return false;
    }

    // ── Teams (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/teams")
    public ResponseEntity<Map<String, Object>> listTeams(HttpSession session) {
        requirePerm(session, "teams.list", "view");
        var all = userService.listTeams();
        if (isAdminOrAudit(session)) {
            return ok(Map.of("data", all));
        }
        List<Long> scope = viewScope(session);
        var filtered = (scope == null || scope.isEmpty())
                ? List.<com.sitemonitor.model.Team>of()
                : all.stream().filter(t -> scope.contains(t.getId())).toList();
        return ok(Map.of("data", filtered));
    }

    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        // Sessiz saat (2026-10-01): gövdede varsa ÖNCE doğrulanır — hatalı pencere takımı yarım oluşturmasın (400).
        com.sitemonitor.service.QuietHours.Config quietCfg = quietConfigFrom(body);
        // Haftalık e-posta anahtarları burada OKUNMAZ: yeni takım her zaman ikisi de kapalı doğar
        // (createTeam açıkça false yazar), açma işi takımın kendi üyelerinde.
        Team team = userService.createTeam(
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                toLong(body.get("leader_id")));
        if (body.containsKey("manager_id")) team = userService.updateTeamManager(team.getId(), toLong(body.get("manager_id")));
        if (quietCfg != null && quietCfg.isSet()) {
            team = userService.updateTeamQuietHours(team.getId(), quietCfg);
            if (teamQuietHours != null) teamQuietHours.invalidate();
        }
        auditService.recordAction("TEAM_CREATE", session, request,
                "TEAM", team.getId().toString(),
                "{\"name\":\"" + team.getName() + "\",\"leaderId\":" + team.getLeaderId() + "}");
        return ok(Map.of("data", team, "message", "Team created"));
    }

    /**
     * Toplu takım işlemi (2026-09-20, kullanıcı bildirimi): {@code ids} + {@code action}
     * (activate | deactivate | set_manager | weekly_reminder_on/off | weekly_availability_on/off). Her takım
     * TEK TEK {@link #applyTeamUpdate} zincirinden geçer (kapsam + izin + TEAM_UPDATE farkı); biri düşerse
     * diğerleri sürer ve sonuç satır satır döner. Ek denetim: bir TEAM_BULK_UPDATE özeti.
     */
    @PostMapping("/teams/bulk")
    public ResponseEntity<Map<String, Object>> bulkTeams(@RequestBody Map<String, Object> body, HttpSession session,
                                                         HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "teams.update", "edit");
        String action = String.valueOf(body.get("action"));
        List<Long> ids = new ArrayList<>();
        if (body.get("ids") instanceof java.util.Collection<?> c) for (Object o : c) { Long v = toLong(o); if (v != null && !ids.contains(v)) ids.add(v); }
        if (ids.isEmpty()) throw new IllegalArgumentException("ids is required");
        if (ids.size() > 200) throw new IllegalArgumentException("En fazla 200 takım");
        Map<String, Object> patch = new LinkedHashMap<>();
        switch (action) {
            case "activate" -> patch.put("active", true);
            case "deactivate" -> patch.put("active", false);
            case "set_manager" -> {
                Long managerId = toLong(body.get("manager_id"));
                if (managerId != null && !userRepo.existsById(managerId)) throw new IllegalArgumentException("Manager user not found: " + managerId);
                patch.put("manager_id", managerId);   // null = AD zincirine geri dön
            }
            case "weekly_reminder_on" -> patch.put("weekly_reminder_enabled", true);
            case "weekly_reminder_off" -> patch.put("weekly_reminder_enabled", false);
            case "weekly_availability_on" -> patch.put("weekly_availability_enabled", true);
            case "weekly_availability_off" -> patch.put("weekly_availability_enabled", false);
            default -> throw new IllegalArgumentException("Bilinmeyen işlem: " + action);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        for (Long id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            try {
                Team tm = applyTeamUpdate(id, patch, session);
                row.put("ok", true); row.put("name", tm.getName());
                okCount++;
            } catch (RuntimeException e) {
                row.put("ok", false); row.put("error", e.getMessage());
            }
            results.add(row);
        }
        auditService.recordAction("TEAM_BULK_UPDATE", session, request, "TEAM", "bulk",
                AuditDetail.of("action", action, "requested", ids.size(), "ok", okCount, "failed", ids.size() - okCount));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", okCount); data.put("failed", ids.size() - okCount); data.put("results", results);
        return ok(Map.of("data", data));
    }

    @PutMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> updateTeam(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        return ok(Map.of("data", applyTeamUpdate(id, body, session)));
    }

    /** PUT /teams/{id} gövdesi — toplu işlem de takım başına BUNU çağırır (aynı kapsam/izin zinciri, aynı denetim). */
    private Team applyTeamUpdate(Long id, Map<String, Object> body, HttpSession session) {
        requireTeamScopedAdmin(session, id);
        requirePerm(session, "teams.update", "edit");
        // ONCEKI durum servis cagrisindan ONCE, ENTITY uzerinden alinir. Eskiden
        // `AuditDiff.diff(null, body)` yaziliyordu: (a) `from` her zaman null oluyordu,
        // (b) entity yerine HAM ISTEK GOVDESI diff'leniyordu — alan adlari snake_case
        // oldugu icin entity alanlariyla eslesmiyor, gonderilmeyen alanlar hic gorunmuyordu.
        Map<String, Object> teamBefore = teamRepo.findById(id)
                .map(t -> AuditDiff.snapshot(t, TEAM_AUDIT_FIELDS))
                .orElseGet(java.util.LinkedHashMap::new);
        // Sessiz saat (2026-10-01): anahtarlar gövdede YOKSA dokunulmaz (toplu işlem, eski istemci). Varsa diğer alanlardan
        // ÖNCE doğrulanır — hatalı pencere 400 döner ve hiçbir alan yarım kaydedilmez. İzin: teams.update (bu uçla aynı).
        com.sitemonitor.service.QuietHours.Config quietCfg = quietConfigFrom(body);
        Team team = userService.updateTeam(id,
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                toLong(body.get("leader_id")),
                bool(body.get("weekly_reminder_enabled")),
                bool(body.get("weekly_availability_enabled")));
        // manager_id: anahtar gövdede VARSA uygulanır (null = temizle). leader_id'den farklı: lider null'da
        // dokunulmaz, müdür ise bilinçli olarak temizlenebilmeli (AD zincirine geri dönmek için).
        if (body.containsKey("manager_id")) team = userService.updateTeamManager(id, toLong(body.get("manager_id")));
        if (quietCfg != null) {
            team = userService.updateTeamQuietHours(id, quietCfg);
            if (teamQuietHours != null) teamQuietHours.invalidate();
        }
        auditService.recordAction("TEAM_UPDATE", session, "TEAM", id.toString(),
                AuditDetail.of("name", team.getName()),
                AuditDiff.diff(teamBefore, AuditDiff.snapshot(team, TEAM_AUDIT_FIELDS)));
        return team;
    }

    /**
     * Haftalık e-posta anahtarları — TAKIM ÜYELERİNE açık DAR uç. Takımın kendi üyeleri (yalnız kendi
     * takımları için) Cuma hatırlatmasını ve Pazartesi erişilebilirlik raporunu açıp kapatabilir.
     * Neden ayrı uç: {@code teams.update} izni ad/e-posta/aktiflik alanlarını da açar — sıradan üyeye
     * verilemez. Burada gövdeden BAŞKA hiçbir alan okunmaz.
     * Üyelik oturumdaki {@code viewTeamIds}'ten DEĞİL, kullanıcının gerçek üyeliklerinden (app_users)
     * doğrulanır: global görüntüleyici/AUDIT tüm takımları görür ama üyesi değildir.
     */
    @PutMapping("/teams/{id}/weekly-notifications")
    public ResponseEntity<Map<String, Object>> updateTeamWeeklyNotifications(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requirePerm(session, "teams.weekly_notifications", "edit");
        if (!isAdmin(session) && !isTeamMember(session, id))
            throw new SecurityException("Bu takımın haftalık e-posta ayarlarını değiştiremezsiniz");
        Map<String, Object> wnBefore = teamRepo.findById(id)
                .map(t -> AuditDiff.snapshot(t, TEAM_AUDIT_FIELDS))
                .orElseGet(java.util.LinkedHashMap::new);
        // Kanal şablonu (2026-09-13): liste gönderildiyse temizlenip JSON dizi olarak saklanır; yoksa dokunulmaz
        String channelsJson = null;
        if (body.get("weekly_channels") instanceof java.util.List<?> raw) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object o : raw) { String v = o == null ? "" : o.toString().trim(); if (!v.isEmpty() && !names.contains(v)) names.add(v.length() > 60 ? v.substring(0, 60) : v); if (names.size() >= 20) break; }
            try { channelsJson = names.isEmpty() ? "" : new tools.jackson.databind.ObjectMapper().writeValueAsString(names); } catch (Exception e) { channelsJson = ""; }
        }
        Team team = userService.updateTeamWeeklyNotifications(id,
                bool(body.get("weekly_reminder_enabled")),
                bool(body.get("weekly_availability_enabled")), channelsJson);
        auditService.recordAction("TEAM_WEEKLY_NOTIFICATIONS", session, "TEAM", id.toString(),
                AuditDetail.of("name", team.getName()),
                AuditDiff.diff(wnBefore, AuditDiff.snapshot(team, TEAM_AUDIT_FIELDS)));
        return ok(Map.of("data", team));
    }

    /** Gövdeden boolean okuma — Boolean değilse null ("bu alana dokunma"). */
    private static Boolean bool(Object raw) {
        return raw instanceof Boolean b ? b : null;
    }

    /** Takım gövdesindeki sessiz saat anahtarları (2026-10-01). */
    static final List<String> TEAM_QUIET_KEYS = List.of("quiet_start", "quiet_end", "quiet_days", "quiet_min_level");

    /**
     * Gövdede sessiz saat anahtarı yoksa {@code null} ("dokunma"); varsa doğrulanmış/normalize ayar
     * ({@link com.sitemonitor.service.QuietHours#normalize} — hata 400, mesaj arayüz dilinde). Başlangıç ve bitiş boş = kaldır.
     */
    static com.sitemonitor.service.QuietHours.Config quietConfigFrom(Map<String, Object> body) {
        if (body == null || TEAM_QUIET_KEYS.stream().noneMatch(body::containsKey)) return null;
        Object s = body.get("quiet_start"), e = body.get("quiet_end"), lvl = body.get("quiet_min_level");
        return com.sitemonitor.service.QuietHours.normalize(s == null ? null : s.toString(), e == null ? null : e.toString(),
                body.get("quiet_days"), lvl == null ? null : lvl.toString());
    }

    /** Oturumdaki kullanıcı bu takımın GERÇEK üyesi mi (birincil takım veya çoklu üyelik)? */
    private boolean isTeamMember(HttpSession session, Long teamId) {
        Object raw = session.getAttribute("userId");
        Long userId = raw instanceof Number n ? n.longValue() : null;
        if (userId == null || teamId == null) return false;
        return userRepo.findById(userId)
                .map(u -> teamId.equals(u.getTeamId())
                        || (u.getTeamIds() != null && u.getTeamIds().contains(teamId)))
                .orElse(false);
    }

    /** A user's AD photo (JPEG) for avatars; 404 when none. Visible to admins/team-admins. */
    @GetMapping("/users/{id}/photo")
    public ResponseEntity<byte[]> userPhoto(@PathVariable Long id, HttpSession session) {
        requireAdminOrTeamAdmin(session);
        return userRepo.findById(id)
                // IDOR: takım yöneticisi id deneyerek HERHANGİ takımdaki kullanıcının fotoğrafını
                // çekebiliyordu. Kapsam kuralı listTeamUsers ile aynı: global viewer her kullanıcıyı,
                // kapsamlı rol yalnız görüş kapsamındaki takımların üyelerini görür; dışı 404
                // (403 "kullanıcı var" bilgisini sızdırırdı).
                .filter(u -> isPhotoViewable(session, u))
                .map(u -> AuthController.photoResponse(u.getPhotoBase64()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Fotoğraf görünürlüğü: global admin/AUDIT → herkes; kapsamlı rol → hedefin herhangi bir
     *  takımı çağıranın {@code viewTeamIds} kapsamındaysa. */
    private boolean isPhotoViewable(HttpSession session, com.sitemonitor.model.AppUser u) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> scope = SessionScope.viewTeamIds(session);
        if (scope == null || scope.isEmpty()) return false;
        if (u.getTeamId() != null && scope.contains(u.getTeamId())) return true;
        return u.getTeamIds() != null && u.getTeamIds().stream().anyMatch(scope::contains);
    }

    @GetMapping("/teams/{id}/users")
    public ResponseEntity<Map<String, Object>> listTeamUsers(
            @PathVariable Long id, HttpSession session) {
        requirePerm(session, "users.list", "view");
        // Read-only visibility: admin/audit see any team; scoped roles only teams in their view scope.
        if (!isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            if (scope == null || !scope.contains(id)) {
                throw new SecurityException("Cannot view another team's members");
            }
        }
        return ok(Map.of("data", userService.listUsers().stream()
                .filter(u -> id.equals(u.getTeamId())
                        || (u.getTeamIds() != null && u.getTeamIds().contains(id))).toList()));
    }

    /** Takim denetiminde izlenen alanlar — silme anlik goruntusu ve guncelleme diff'i AYNI listeyi kullanir
     *  ki "silinen takimda ne vardi" ile "takimda ne degisti" karsilastirilabilir kalsin. */
    private static final String[] TEAM_AUDIT_FIELDS = {
            "name", "email", "description", "active", "leaderId", "managerId",
            "weeklyReminderEnabled", "weeklyAvailabilityEnabled", "weeklyChannels",
            "quietStart", "quietEnd", "quietDays", "quietMinLevel" };

    // ── Takım sayaçları / etki önizleme / taşıma / üyelik (2026-09-20) ────────────

    /** Takım satırı sayaçları: üye · domain · izleme · açık alarm · kişi · grup (tek geçiş). */
    @GetMapping("/teams/stats")
    public ResponseEntity<Map<String, Object>> teamStats(HttpSession session) {
        requirePerm(session, "teams.list", "view");
        Map<Long, Map<String, Object>> all = teamAdminService.stats();
        if (!isAdminOrAudit(session)) {
            List<Long> scope = viewScope(session);
            all.keySet().retainAll(scope == null ? Set.of() : new HashSet<>(scope));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        all.forEach((k, v) -> out.put(String.valueOf(k), v));
        return ok(Map.of("data", out));
    }

    /** Silme/pasifleştirme ETKİ önizlemesi: bağlı domain/izleme/üye/kişi/grup listesi + açık alarm sayısı. */
    @GetMapping("/teams/{id}/impact")
    public ResponseEntity<Map<String, Object>> teamImpact(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        teamRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Team not found: " + id));
        return ok(Map.of("data", teamAdminService.impact(id)));
    }

    /**
     * Bağlı varlıkları hedef takıma taşı (bildirim grubundaki "409 → taşı" deseni). Silme ayrı çağrıdır.
     * F3 (2026-09-28): {@code TeamAdminService.moveAll} envanterin teamId'sini yeniden yazar → sertifika önbellekleri de boşalır.
     */
    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice", "card-extras", "domain-team-names"}, allEntries = true)
    @PostMapping("/teams/{id}/move")
    public ResponseEntity<Map<String, Object>> teamMove(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        Long target = toLong(body.get("target_team_id"));
        if (target == null) throw new IllegalArgumentException("target_team_id is required");
        String fromName = teamRepo.findById(id).map(Team::getName).orElseThrow(() -> new NoSuchElementException("Team not found: " + id));
        String toName = teamRepo.findById(target).map(Team::getName).orElseThrow(() -> new NoSuchElementException("Team not found: " + target));
        Map<String, Integer> moved = teamAdminService.moveAll(id, target);
        auditService.recordAction("TEAM_MOVE_ASSETS", session, request, "TEAM", id.toString(),
                AuditDetail.of("from", fromName, "to", toName, "to_id", target, "domains", moved.get("domains"),
                        "monitors", moved.get("monitors"), "users", moved.get("users"), "contacts", moved.get("contacts"), "groups", moved.get("groups")));
        return ok(Map.of("data", moved));
    }

    /** Üye ekle — kullanıcı zaten üyeyse no-op. Kapsam: global admin ya da o takımı yöneten TEAM_ADMIN. */
    @PostMapping("/teams/{id}/members")
    public ResponseEntity<Map<String, Object>> addTeamMember(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                             HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "users.crud", "edit");
        requireTeamManageScope(session, id);
        Long userId = toLong(body.get("user_id"));
        if (userId == null) throw new IllegalArgumentException("user_id is required");
        AppUser u = userRepo.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(u.getTeamIds() == null ? List.of() : u.getTeamIds());
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        boolean added = ids.add(id);
        if (added) userService.updateUser(userId, null, null, null, null, ids, null, null);
        auditService.recordAction("TEAM_MEMBER_ADD", session, request, "TEAM", id.toString(),
                AuditDetail.of("username", u.getUsername(), "user_id", userId, "changed", added));
        return ok(Map.of("data", Map.of("added", added)));
    }

    /** Üye çıkar — kullanıcının SON takımı çıkarılamaz (ADMIN hariç: takımsız olabilir). */
    @DeleteMapping("/teams/{id}/members/{userId}")
    public ResponseEntity<Map<String, Object>> removeTeamMember(@PathVariable Long id, @PathVariable Long userId,
                                                                HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "users.crud", "edit");
        requireTeamManageScope(session, id);
        AppUser u = userRepo.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(u.getTeamIds() == null ? List.of() : u.getTeamIds());
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        boolean removed = ids.remove(id);
        if (removed && ids.isEmpty() && !"ADMIN".equals(u.getSystemRole())) {
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Kullanıcının tek takımı bu; önce başka bir takıma ekleyin.", "This is the user's only team; add another team first."));
        }
        if (removed) userService.updateUser(userId, null, null, null, null, ids, null, null);
        auditService.recordAction("TEAM_MEMBER_REMOVE", session, request, "TEAM", id.toString(),
                AuditDetail.of("username", u.getUsername(), "user_id", userId, "changed", removed));
        return ok(Map.of("data", Map.of("removed", removed)));
    }

    private void requireTeamManageScope(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return;
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage == null || !manage.contains(teamId)) throw new SecurityException("Cannot manage another team's members");
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> deleteTeam(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "teams.lifecycle", "execute");
        // Silmeden ONCE: takim gittikten sonra adi/lideri/uye sayisi hicbir yerde kalmiyordu.
        Map<String, Object> before = teamRepo.findById(id)
                .map(t -> AuditDiff.snapshot(t, TEAM_AUDIT_FIELDS))
                .orElseGet(java.util.LinkedHashMap::new);
        before.put("member_count", userRepo.findByTeamIdOrderByUsernameAsc(id).size());
        // Bağlı kaydı olan takım SİLİNMEZ (2026-09-28): UserService.deleteTeam yalnız aktif envanter / kullanıcı / aktif
        // kişiye bakıyordu; dokuz izleme türü, pasif kişi ve bildirim grupları silinmiş takımın kimliğini göstermeye
        // devam ediyor, alarmları sahipsiz kalıyordu. Arayüz zaten "önce taşı" diyor (etki önizlemesi boş olmalı);
        // sunucu da aynı ölçütü uygular — doğrudan API çağrısı onu atlayamaz.
        Map<String, Object> impact = teamAdminService.impact(id);
        if (impact != null && !impact.isEmpty() && !Boolean.TRUE.equals(impact.get("empty"))) {
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Bu takıma bağlı alan adı, izleme, kişi ya da bildirim grubu var — önce başka bir takıma taşıyın.",
                    "This team still has domains, monitors, people or notification groups — move them to another team first."));
        }
        userService.deleteTeam(id);
        auditService.recordAction("TEAM_DELETE", session, request, "TEAM", id.toString(),
                AuditDiff.snapshotJson(before));
        return ok(Map.of("message", "Team deleted"));
    }

    // ── Users (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpSession session) {
        requirePerm(session, "users.list", "view");
        var all = userService.listUsers();
        if (isAdminOrAudit(session)) {
            return ok(Map.of("data", all));
        }
        List<Long> scope = viewScope(session);
        var filtered = (scope == null || scope.isEmpty())
                ? List.<AppUser>of()
                : all.stream().filter(u -> inScope(u, scope)).toList();
        return ok(Map.of("data", filtered));
    }

    /** Admin Users ekranı — filtreli + sayfalı liste (q + systemRole/orgRole/teamId).
     *  Düz /users (dropdown/kontak kaynağı) bozulmasın diye AYRI uç. */
    @GetMapping("/users/search")
    public ResponseEntity<Map<String, Object>> searchUsers(
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String q,
            @RequestParam(required = false)    String systemRole,
            @RequestParam(required = false)    String orgRole,
            @RequestParam(required = false)    Long   teamId,
            @RequestParam(required = false)    Integer dormantDays,
            @RequestParam(defaultValue = "false") boolean neverLoggedIn,
            HttpSession session) {
        requirePerm(session, "users.list", "view");
        size = Math.min(Math.max(size, 1), 200);
        // TEAM_ADMIN yalnız kendi takımını görür → client teamId yok sayılır, kendi takımına sabitlenir
        Long effTeamId = isAdminOrAudit(session) ? teamId : teamId(session);
        if (!isAdminOrAudit(session) && effTeamId == null) {
            return ok(Map.of("data", List.of(), "total", 0L, "page", 0, "total_pages", 0,
                    "active_admin_count", userRepo.countBySystemRoleAndActiveTrue("ADMIN")));
        }
        String qParam = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        String roleParam    = (systemRole == null || systemRole.isBlank()) ? null : systemRole;
        String orgRoleParam = (orgRole == null || orgRole.isBlank()) ? null : orgRole;
        // Uyuyan hesap süzgeci (2026-09-20): N gündür girmeyen / hiç girmemiş.
        String dormantBefore = dormantDays != null && dormantDays > 0
                ? ISO.format(Instant.now().minus(java.time.Duration.ofDays(dormantDays))) : null;
        Page<AppUser> p = (dormantBefore == null && !neverLoggedIn)
                ? userRepo.findFiltered(qParam, roleParam, orgRoleParam, effTeamId, PageRequest.of(page, size))
                : userRepo.findFilteredDormant(qParam, roleParam, orgRoleParam, effTeamId, dormantBefore, neverLoggedIn, PageRequest.of(page, size));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", p.getContent());
        resp.put("total", p.getTotalElements());
        resp.put("page", p.getNumber());
        resp.put("total_pages", p.getTotalPages());
        // Sayfalamadan bağımsız "son aktif admin" guard'ı için toplam aktif admin sayısı
        resp.put("active_admin_count", userRepo.countBySystemRoleAndActiveTrue("ADMIN"));
        return ok(resp);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "users.crud", "edit");
        String requestedRole = (String) body.get("system_role");
        List<Long> requestedTeams = teamIdsFromBody(body);
        if (!isAdmin(session)) {
            // GLOBAL OLMAYAN her yazar (TEAM_ADMIN VEYA kapsamlı müdür) yalnız USER/TEAM_ADMIN
            // tohumlar; ADMIN/AUDIT asla. Eskiden bu dal yalnız isTeamAdmin() ile kapılıydı: AD-kaynaklı
            // ADMIN (müdür, viewTeamIds dolu) system.global_admin iznini rol olarak taşıdığından
            // isTeamAdmin() false dönüyor, kısıt atlanıyor ve müdür system_role=ADMIN + team_ids=[]
            // ile SINIRSIZ bir yerel admin yaratabiliyordu (kendi seçtiği parolayla).
            requireAssignableRole(requestedRole);
            if (isTeamAdmin(session)) {
                // TEAM_ADMIN: yeni kullanıcı kendi takımına düşer — payload ezemez.
                Long own = teamId(session);
                requestedTeams = own != null ? List.of(own) : List.of();
            } else {
                // Müdür: hedef takımlar YÖNETİM kapsamında olmalı; boşsa kendi takımına düşer.
                if (requestedTeams == null || requestedTeams.isEmpty()) {
                    Long own = teamId(session);
                    requestedTeams = own != null ? List.of(own) : List.of();
                }
                requireTeamsInManageScope(session, requestedTeams);
            }
        }
        AppUser user = userService.createUser(
                (String) body.get("username"),
                (String) body.get("password"),
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                requestedRole,
                requestedTeams,
                (String) body.get("org_role"));
        // AD-mirrored profil alanları (ad/soyad/ünvan/telefon/departman/seviye/müdürlük/müdür sicili)
        userService.applyProfileFields(user, body);
        user = userRepo.save(user);
        auditService.recordAction("USER_CREATE", session, request,
                "USER", user.getUsername(),
                "{\"role\":\"" + user.getSystemRole() + "\",\"teamId\":" + user.getTeamId() + "}");
        return ok(Map.of("data", user, "message", "User created"));
    }

    /**
     * Toplu kullanıcı işlemi (2026-09-20): {@code ids} + {@code action} (activate | deactivate | assign_team |
     * set_org_role). Her kullanıcı TEK TEK aynı güvenlik zincirinden geçer (kendini pasifleştirme, son aktif
     * admin, takım kapsamı); biri düşerse diğerleri sürer ve sonuç satır satır döner. Denetim: her kullanıcı için
     * USER_UPDATE (fark) + bir USER_BULK_UPDATE özeti.
     */
    @PostMapping("/users/bulk")
    public ResponseEntity<Map<String, Object>> bulkUsers(@RequestBody Map<String, Object> body, HttpSession session,
                                                         HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "users.crud", "edit");
        String action = String.valueOf(body.get("action"));
        List<Long> ids = new ArrayList<>();
        if (body.get("ids") instanceof java.util.Collection<?> c) for (Object o : c) { Long v = toLong(o); if (v != null) ids.add(v); }
        if (ids.isEmpty()) throw new IllegalArgumentException("ids is required");
        if (ids.size() > 200) throw new IllegalArgumentException("En fazla 200 kullanıcı");
        Map<String, Object> patch = new LinkedHashMap<>();
        switch (action) {
            case "activate" -> patch.put("active", true);
            case "deactivate" -> patch.put("active", false);
            case "assign_team" -> {
                Long teamId = toLong(body.get("team_id"));
                if (teamId == null) throw new IllegalArgumentException("team_id is required");
                patch.put("team_ids", List.of(teamId));
            }
            case "set_org_role" -> {
                Object role = body.get("org_role");
                patch.put("org_role", role == null ? "" : String.valueOf(role));
            }
            default -> throw new IllegalArgumentException("Bilinmeyen işlem: " + action);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        for (Long id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            try {
                AppUser u = applyUserUpdate(id, patch, session);
                row.put("ok", true); row.put("username", u.getUsername());
                okCount++;
            } catch (RuntimeException e) {
                row.put("ok", false); row.put("error", e.getMessage());
            }
            results.add(row);
        }
        auditService.recordAction("USER_BULK_UPDATE", session, request, "USER", "bulk",
                AuditDetail.of("action", action, "requested", ids.size(), "ok", okCount, "failed", ids.size() - okCount));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", okCount); data.put("failed", ids.size() - okCount); data.put("results", results);
        return ok(Map.of("data", data));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        return ok(Map.of("data", applyUserUpdate(id, body, session)));
    }

    /** PUT /users/{id} gövdesi — toplu işlem de kullanıcı başına BUNU çağırır (aynı güvenlik zinciri, aynı denetim). */
    private AppUser applyUserUpdate(Long id, Map<String, Object> body, HttpSession session) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        requireCanAdministerTarget(session, target);
        final String[] _uf = {"systemRole", "teamId", "teamIds", "active", "orgRole", "displayName", "email", "employeeId", "managerId"};
        java.util.Map<String, Object> _before = AuditDiff.snapshot(target, _uf);
        String requestedRole = (String) body.get("system_role");
        // null = takımlara dokunma (kısmi güncelleme); team_ids veya team_id verilirse (boş dahil) set et.
        List<Long> requestedTeams =
                (body.containsKey("team_ids") || body.containsKey("team_id")) ? teamIdsFromBody(body) : null;
        Long selfId = userIdFromSession(session);
        if (selfId != null && selfId.equals(id)) {
            if (requestedRole != null && !requestedRole.equals(target.getSystemRole())) {
                throw new SecurityException("You cannot change your own role");
            }
            Object activePayload = body.get("active");
            if (activePayload instanceof Boolean && !((Boolean) activePayload)
                    && Boolean.TRUE.equals(target.getActive())) {
                throw new SecurityException("You cannot deactivate yourself");
            }
        }
        {
            String nextRole = requestedRole != null ? requestedRole : target.getSystemRole();
            boolean currentActive = Boolean.TRUE.equals(target.getActive());
            boolean nextActive = body.get("active") instanceof Boolean
                    ? (Boolean) body.get("active") : currentActive;
            guardLastActiveAdmin(target, "ADMIN".equals(nextRole) && nextActive);
        }
        if (!isAdmin(session)) {
            // createUser ile aynı kural: global olmayan yazar ADMIN/AUDIT veremez (müdür dâhil).
            requireAssignableRole(requestedRole);
            if (isTeamAdmin(session)) {
                // TEAM_ADMIN kullanıcıyı kendi takımı dışına taşıyamaz / çoklu takım atayamaz.
                if (requestedTeams != null) {
                    for (Long t : requestedTeams) {
                        if (!t.equals(target.getTeamId())) {
                            throw new SecurityException("Team admin cannot transfer users to another team");
                        }
                    }
                }
                requestedTeams = null;   // üyeliği değiştirmesine izin verilmez
            } else if (requestedTeams != null) {
                // Müdür: kullanıcıyı ancak yönettiği takımlar arasında taşır.
                requireTeamsInManageScope(session, requestedTeams);
            }
        }
        // Boş takım = takımsız; yalnız ADMIN rollü kullanıcı takımsız kalabilir.
        if (requestedTeams != null && requestedTeams.isEmpty()) {
            String effRole = requestedRole != null ? requestedRole : target.getSystemRole();
            if (!"ADMIN".equals(effRole)) {
                throw new IllegalArgumentException("Team is required");
            }
        }
        AppUser user = userService.updateUser(id,
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                requestedRole,
                requestedTeams,
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                (String) body.get("org_role"));
        // AD-mirrored profil alanları (ad/soyad/ünvan/telefon/departman/seviye/müdürlük/müdür sicili)
        userService.applyProfileFields(user, body);
        user = userRepo.save(user);
        auditService.recordAction("USER_UPDATE", session, "USER", id.toString(), user.getUsername(),
                AuditDiff.diff(_before, AuditDiff.snapshot(user, _uf)));
        return user;
    }

    @PostMapping("/users/{id}/auto-reset-password")
    public ResponseEntity<Map<String, Object>> autoResetPassword(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.actions", "execute");
        requireCanAdministerTarget(session, target);   // ADMIN/AUDIT parolasını yalnız global admin sıfırlar
        String adminPwd = body.get("admin_password");
        if (adminPwd == null || adminPwd.isBlank()) {
            throw new IllegalArgumentException("Admin password required");
        }
        String adminUsername = actor(session);

        String tempPwd = userService.adminAutoResetPassword(id, adminUsername, adminPwd);

        // refresh: the auto-reset call may have updated must_change_password etc.
        target = userRepo.findById(id).orElse(target);
        String emailStatus = emailNotificationService.sendPasswordResetEmail(
                target.getEmail(), target.getUsername(), target.getDisplayName(), tempPwd);

        auditService.recordAction("USER_PASSWORD_AUTO_RESET", session, request,
                "USER", id.toString(),
                "{\"email_status\":\"" + emailStatus.replace("\"", "\\\"") + "\"}");

        return ok(Map.of(
                "message", "Temporary password generated and emailed",
                "email_status", emailStatus));
    }

    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlockUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.actions", "execute");
        // Kilit kalkinca kaybolan gercek: NE KADAR kilitliydi, kac denemeden sonra, kalici miydi.
        String detail = AuditDetail.of("username", target.getUsername(),
                "lockout_until_before", target.getLockoutUntil(),
                "failed_blocks_before", target.getFailedBlockCount(),
                "permanent_lock_before", target.getPermanentLock());
        userService.unlockUser(id);
        auditService.recordAction("USER_UNLOCK", session, request, "USER", id.toString(), detail);
        return ok(Map.of("message", "User unlocked"));
    }

    /** Rol-kilidini kaldır → kullanıcının systemRole'ü tekrar AD (LDAP) yönetimine döner. */
    @PostMapping("/users/{id}/role-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserRole(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        // Kilit kalkinca rol AD yonetimine doner; kaybolan gercek SABITLENMIS roldur.
        String detail = AuditDetail.of("username", target.getUsername(),
                "role_before", target.getSystemRole());
        userService.unlockRole(id);
        auditService.recordAction("USER_ROLE_UNLOCK", session, request, "USER", id.toString(), detail);
        return ok(Map.of("message", "User role unlocked"));
    }

    /** Takım kilidini kaldır → kullanıcının takım üyelikleri tekrar AD (LDAP) yönetimine döner (2026-09-18). */
    @PostMapping("/users/{id}/team-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserTeams(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        // Kilit kalkınca takımlar AD yönetimine döner; kaybolan gerçek SABİTLENMİŞ üyelik kümesidir.
        String detail = AuditDetail.of("username", target.getUsername(),
                "team_ids_before", String.valueOf(target.getTeamIds()));
        userService.unlockTeams(id);
        auditService.recordAction("USER_TEAM_UNLOCK", session, request, "USER", id.toString(), detail);
        return ok(Map.of("message", "User teams unlocked"));
    }

    /**
     * LDAP alan kilidini kaldır (2026-09-30, {@link LdapFieldLocks}): alan bir sonraki LDAP girişinde / "AD'den yeniden
     * eşitle"de AD değerine döner. YALNIZ global yönetici (kapsamlı müdür 403) — kilit, müdürün elle yazdığı değeri AD'ye
     * karşı korur; kaldırmak o değeri kaybettirir. Gövde {@code {"field": "<anahtar>"}}; bilinmeyen anahtar 400.
     * Yanıt: taze kullanıcı satırı ({@code locked_field_keys} güncel) + {@code had_lock}.
     */
    @PostMapping("/users/{id}/field-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserField(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "users.crud", "edit");
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        Object raw = body == null ? null : body.get("field");
        String field = raw == null ? null : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (!LdapFieldLocks.isValid(field)) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Bilinmeyen AD alanı: ", "Unknown AD field: ") + raw);
        }
        boolean had = userService.unlockField(id, field);
        String detail = AuditDetail.of("username", target.getUsername(), "field", field, "had_lock", had);
        auditService.recordAction("USER_FIELD_UNLOCK", session, request, "USER", id.toString(), detail);
        AppUser fresh = userRepo.findById(id).orElse(target);
        return ok(Map.of("data", fresh, "had_lock", had));
    }

    /**
     * Kişinin push susturmasını kaldır (2026-10-04, onaylı öneri 4) — kullanıcı detayı → Bildirimler. Kişi ertelemeyi
     * kendisi kurar; yönetici yalnız etkin susturmayı KALDIRABİLİR (ör. nöbet devrinde susturmayı unutan kişi). Kapı kullanıcı
     * yönetimi kapısı: global yönetici ya da kişinin takımını yöneten kapsamlı yönetici + {@code users.crud/edit}. Diğer
     * tercihler (seviye, aile, dil) kişinindir — burada değiştirilmez. Yanıt: taze kullanıcı satırı + {@code had_snooze}.
     */
    @PostMapping("/users/{id}/push-snooze/clear")
    public ResponseEntity<Map<String, Object>> clearUserPushSnooze(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        boolean had = com.sitemonitor.service.UserPushRecipientResolver.snoozeActive(target, java.time.Instant.now());
        String until = target.getPushSnoozeUntil();
        AppUser fresh = userService.savePushSnooze(target, null, null);
        auditService.recordAction("PUSH_SNOOZE_CLEAR", session, request, "USER", id.toString(),
                AuditDetail.of("username", target.getUsername(), "had_snooze", had, "until_before", until == null ? "-" : until));
        return ok(Map.of("data", fresh, "had_snooze", had));
    }

    /** Org-rol kilidini kaldır → kullanıcının org_role'ü tekrar AD (LDAP) yönetimine döner. */
    @PostMapping("/users/{id}/org-role-unlock")
    public ResponseEntity<Map<String, Object>> unlockUserOrgRole(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        String detail = AuditDetail.of("username", target.getUsername(),
                "org_role_before", target.getOrgRole());
        userService.unlockOrgRole(id);
        auditService.recordAction("USER_ORG_ROLE_UNLOCK", session, request, "USER", id.toString(), detail);
        return ok(Map.of("message", "User org role unlocked"));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        Long selfId = userIdFromSession(session);
        if (selfId != null && selfId.equals(id)) {
            throw new SecurityException("You cannot delete your own account");
        }
        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        requireTeamScopedAdmin(session, target.getTeamId());
        requirePerm(session, "users.crud", "edit");
        requireCanAdministerTarget(session, target);
        guardLastActiveAdmin(target, false);
        // Silmede yok olan durum yazilir: kayit gittikten sonra kimse arayip bulamaz.
        String detail = AuditDiff.snapshotJson(AuditDiff.snapshot(target,
                "username", "displayName", "email", "teamId", "systemRole", "orgRole", "active", "authSource"));
        userService.deleteUser(id);
        auditService.recordAction("USER_DELETE", session, request, "USER", id.toString(), detail);
        return ok(Map.of("message", "User deleted"));
    }

    // ── Certificate Notes ──────────────────────────────────────────────────────

    private static final Set<String> NOTE_CATEGORIES =
            Set.of("NOTE", "DEPLOYMENT", "INCIDENT", "RENEWAL");
    private static final int NOTE_MAX_LENGTH = 5000;
    private static final long NOTE_EDIT_WINDOW_HOURS = 24L;

    /**
     * Notlar. 2026-09-26 (org geneli görünürlük): alan adı envanterde SİLİNMEMİŞ bir kayıtsa ve ayar açıksa,
     * alan adının TÜM silinmemiş notları okunur (yazan takım fark etmeksizin) — kendi takımının silinmiş notları
     * bugünkü gibi görünmeye devam eder. Ayar kapalıyken ya da alan adı envanterde yokken davranış aynıdır.
     */
    @GetMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> getNotes(
            @PathVariable String domain, HttpSession session) {
        requirePerm(session, "notes.read", "view");
        List<CertificateNote> notes;
        if (isAdminOrAudit(session)) {
            notes = noteRepo.findByDomainOrderByCreatedAtDesc(domain);
        } else {
            List<Long> scope = viewScope(session);
            CertificateInventory inv = inventoryVisibility == null ? null : inventoryRepo.findByDomain(domain).orElse(null);
            if (inv != null && readableOrgWide(session, inv)) {
                notes = noteRepo.findByDomainOrderByCreatedAtDesc(domain).stream()
                        .filter(n -> n.getDeletedAt() == null
                                || (scope != null && n.getTeamId() != null && scope.contains(n.getTeamId())))
                        .toList();
            } else {
                notes = (scope == null || scope.isEmpty())
                        ? List.of()
                        : noteRepo.findByDomainAndTeamIdInOrderByCreatedAtDesc(domain, scope);
            }
        }
        return ok(Map.of("data", notes));
    }

    /**
     * Not YAZMA kapısı (2026-09-26): alan adı envanterdeyse kaydın SY takımı çağıranın yönetim kapsamında
     * olmalı. Eskiden yalnız rol soruluyordu (admin / takım yöneticisi): bir takımın yöneticisi BAŞKA takımın
     * alan adına not ekleyebiliyor, düzenleyip silebiliyordu — notlar yalnız kendi takımına göründüğü için bu
     * fark edilmiyordu. Org geneli görünürlükle notlar herkese açıldığından bu artık başka takımın kaydına
     * müdahaledir. Envanterde olmayan alan adı: bugünkü davranış (sahibi olmayan kayıt). Global admin muaf.
     */
    private void requireNoteDomainWritable(HttpSession session, String domain) {
        if (isAdmin(session) || domain == null) return;
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv != null && !canManageTeamResource(session, inv.getTeamId())) {
            log.warn("Cross-team note write attempt by user={} domain={} resourceTeam={}",
                    actor(session), domain, inv.getTeamId());
            throw new SecurityException("Access denied: this domain belongs to another team");
        }
    }

    @PostMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> addNote(
            @PathVariable String domain,
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        requireNoteDomainWritable(session, domain);
        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        String category = body.getOrDefault("category", "NOTE");
        if (!NOTE_CATEGORIES.contains(category))
            throw new IllegalArgumentException("Invalid category: " + category);

        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        String createdAt = now();

        CertificateNote note = new CertificateNote();
        note.setDomain(domain);
        note.setTeamId(isAdmin(session) ? null : teamId(session));
        note.setAuthorUsername(currentUser);
        note.setAuthorName(currentName);
        note.setNote(text.trim());
        note.setCategory(category);
        note.setCreatedAt(createdAt);
        CertificateNote saved = noteRepo.save(note);

        writeRevision(saved.getId(), 0, "CREATE", text.trim(), category,
                createdAt, currentUser, currentName, null);
        auditService.recordAction("CERT_NOTE_ADD", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\",\"category\":\"" + category + "\"}");
        return ok(Map.of("data", saved, "message", "Note added"));
    }

    @PutMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> updateNote(
            @PathVariable String domain, @PathVariable Long noteId,
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            throw new NoSuchElementException("Note has been deleted");
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        requireNoteDomainWritable(session, domain);   // başka takımın alan adındaki not düzenlenmez (2026-09-26)
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("NOT_AUTHOR");

        try {
            Instant created = Instant.from(ISO.parse(note.getCreatedAt()));
            if (Instant.now().minusSeconds(NOTE_EDIT_WINDOW_HOURS * 3600L).isAfter(created))
                throw new IllegalStateException("EDIT_WINDOW_EXPIRED");
        } catch (java.time.format.DateTimeParseException ignored) { /* fallback: allow */ }

        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        // Snapshot the PRIOR body+category into a revision before mutating the note.
        String editedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeq = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        writeRevision(noteId, nextSeq, "EDIT", note.getNote(), note.getCategory(),
                editedAt, currentUser, currentName, null);

        note.setNote(text.trim());
        note.setUpdatedAt(editedAt);
        note.setUpdatedBy(currentUser);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_EDIT", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note updated"));
    }

    @DeleteMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> deleteNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            return ok(Map.of("message", "Note already deleted"));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        requireNoteDomainWritable(session, domain);   // başka takımın alan adındaki not silinmez (2026-09-26)
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!isAdmin(session) && !Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("Only the author or an admin can delete a note");

        String deletedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqDel = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqDel = (maxSeqDel == null ? 0 : maxSeqDel) + 1;
        writeRevision(noteId, nextSeqDel, "DELETE", null, note.getCategory(),
                deletedAt, currentUser, currentName, null);

        note.setDeletedAt(deletedAt);
        note.setDeletedBy(currentUser);
        noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_DELETE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("message", "Note deleted"));
    }

    @GetMapping("/notes/{domain}/{noteId}/revisions")
    public ResponseEntity<Map<String, Object>> getNoteRevisions(
            @PathVariable String domain, @PathVariable Long noteId, HttpSession session) {
        requirePerm(session, "notes.read", "view");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        // Org geneli görünürlük (2026-09-26): notu listede görebilen, revizyonlarını da görür — silinmemiş not +
        // envanterde silinmemiş alan adı. Aksi hâlde bugünkü sahiplik kuralı.
        boolean orgWide = !isAdmin(session) && note.getDeletedAt() == null && inventoryVisibility != null
                && inventoryRepo.findByDomain(domain).map(inv -> readableOrgWide(session, inv)).orElse(false);
        if (!isAdmin(session) && !orgWide) checkOwnership(note.getTeamId(), session);
        return ok(Map.of("data", noteRevisionRepo.findByNoteIdOrderBySequenceNoAsc(noteId)));
    }

    @PostMapping("/notes/{domain}/{noteId}/restore")
    public ResponseEntity<Map<String, Object>> restoreNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requirePerm(session, "notes.crud", "edit");
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (note.getDeletedAt() == null)
            return ok(Map.of("data", note, "message", "Note was not deleted"));

        String restoredAt = now();
        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqRest = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqRest = (maxSeqRest == null ? 0 : maxSeqRest) + 1;
        writeRevision(noteId, nextSeqRest, "RESTORE", null, note.getCategory(),
                restoredAt, currentUser, currentName, null);

        note.setDeletedAt(null);
        note.setDeletedBy(null);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_RESTORE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note restored"));
    }

    private void writeRevision(Long noteId, int sequenceNo, String eventType,
                               String body, String category,
                               String editedAt, String editedBy, String editedByName,
                               String reason) {
        CertificateNoteRevision rev = new CertificateNoteRevision();
        rev.setNoteId(noteId);
        rev.setSequenceNo(sequenceNo);
        rev.setEventType(eventType);
        rev.setBody(body);
        rev.setCategory(category);
        rev.setEditedAt(editedAt);
        rev.setEditedBy(editedBy);
        rev.setEditedByName(editedByName);
        rev.setReason(reason);
        try { noteRevisionRepo.save(rev); }
        catch (Exception e) { log.warn("Failed to persist note revision: {}", e.getMessage()); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Global (unrestricted) admin — the bootstrap/local ADMIN. A scoped müdür is NOT global. */
    private boolean isAdmin(HttpSession session) {
        return SessionScope.isGlobalAdmin(session);
    }

    /** Sees all teams (read): global admin or AUDIT — both have null view scope. */
    private boolean isAdminOrAudit(HttpSession session) {
        return SessionScope.isGlobalViewer(session);
    }

    private boolean isTeamAdmin(HttpSession session) {
        return permissionService.allows(session, "system.team_admin", "execute")
            && !permissionService.allows(session, "system.global_admin", "execute");
    }

    /** Yetki kapısı kısayolu — rolün (resource, action) iznini doğrular (403 fırlatır). Takım-scope AYRI. */

    /**
     * İzleme sayfalarının gömülü "Alarmlar" sekmesi için TİP KAPSAMI.
     *
     * <p>Sekme domain'e göre süzülüyordu; aynı URL'i izleyen HER monitörün alarmı oraya
     * düşüyordu. Sayfa Hızı modalinde HTTP izlemesinin SSL alarmı ve Sayfa Bütünlüğü alarmı
     * görünüyordu — kullanıcı orada onlara müdahale edemez ve sayfa kendi üretmediği alarmları
     * üretmiş gibi görünür. Süzme SUNUCUDA yapılır: istemcide süzmek yalnız açık sayfayı
     * süzer, sayfalama ve tip/seviye sayaçları yanlış kalırdı.
     *
     * <p>Parametrenin HİÇ verilmemesi "tümü" demektir (bağımsız Alarm Geçmişi ekranı böyle çağırır);
     * o durumda {@code typeScoped=false} gider ve sorgu aynen eskisi gibi çalışır. Parametre VERİLİP
     * içinden geçerli tip çıkmazsa sonuç BOŞTUR — süzgeç sessizce düşürülmez (fail-closed).
     */
    private static List<String> parseAlertTypes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim().toUpperCase(java.util.Locale.ROOT);
            // Serbest metin DEĞİL sabit enum adı: harf/rakam/alt çizgi dışı her şey elenir.
            if (!t.isEmpty() && t.matches("[A-Z0-9_]{1,40}") && !out.contains(t)) out.add(t);
            if (out.size() >= 30) break;
        }
        return out;
    }

    /**
     * Alarm listesinin tarih aralığı kipi (2026-09-28, regresyon B3). Varsayılan: aralıkta AÇILANLAR — {@code since}
     * açılış alt sınırıdır (eski davranış, birebir). {@code range=active}: aralıkta AKTİF olanlar — açılış ≤
     * {@code until} VE (hâlâ açık YA DA çözüm ≥ {@code since}); yani önceden açılıp aralığa DEVREDEN alarmlar da
     * girer. Haftalık erişilebilirlik e-postası "Haftanın alarmları" sayısını böyle sayar ve bağlantısı bu kipi açar.
     * Kip yalnız tarih yüklemini değiştirir; takım kapsamı / 7/24 görünürlüğü / diğer süzgeçler aynı yoldan geçer.
     */
    record AlertRange(String openedSince, String activeFrom) {
        static AlertRange of(String range, String since) {
            boolean active = range != null && "active".equalsIgnoreCase(range.trim());
            if (!active) return new AlertRange(since, null);
            return new AlertRange(null, since == null || since.isBlank() ? null : since);
        }
    }

    /**
     * Alarm listesinin sıralaması (2026-10-01, Alarm Geçmişi sütun sıralaması). Anahtarlar BEYAZ LİSTE — bilinmeyen
     * {@code sort} varsayılana düşer, {@code dir} yalnız {@code asc|desc} (varsayılan desc). Varsayılan: açılış anı, en
     * yeni önce; kapalı görünümde ({@code resolved=true}) kapanış anı, en yeni önce (eski davranış birebir).
     * <ul>
     *   <li>{@code opened} → {@code createdAt}; {@code resolved} → {@code resolvedAt}; {@code domain} → {@code domain};
     *       {@code type} → {@code alertType}; {@code team} → takım ADI ({@link #TEAM_NAME}, 2026-10-01: eskiden
     *       {@code teamId} — kullanıcıya rastgele görünüyordu). Sütunun gösterdiği takımla aynı: damgalı takım, yoksa
     *       envanterin SY takımı (sertifika alarmları); takımsız alarm adsız (NULL) sıralanır.</li>
     *   <li>{@code level} → önem METİN saklanır ({@code ORDER BY alertLevel} alfabetik: WARNING &gt; HIGH &gt; CRITICAL) →
     *       açık CASE (CRITICAL 3 &gt; HIGH 2 &gt; WARNING 1 &gt; diğer 0) — {@code findAllOpenOrderBySeverity} ile aynı
     *       ders; {@link org.springframework.data.jpa.domain.JpaSort#unsafe} ifadeyi takma ad öneki olmadan ekler.</li>
     * </ul>
     * Her anahtardan sonra {@code createdAt DESC} eşitlik bozucu (aynı seviye/takım içinde en yeni önce).
     */
    public static final class AlertSort {
        public static final java.util.Set<String> KEYS = java.util.Set.of("opened", "resolved", "level", "team", "domain", "type");
        public static final String LEVEL_RANK =
                "(CASE UPPER(e.alertLevel) WHEN 'CRITICAL' THEN 3 WHEN 'HIGH' THEN 2 WHEN 'WARNING' THEN 1 ELSE 0 END)";
        /**
         * Takım sütununun ADI (2026-10-01) — {@code AlertTeam} (arayüz) ile aynı kaynak: damgalı takım ({@code e.teamId})
         * varsa onun adı, yoksa alan adının envanter SY takımının adı. İlişkisiz varlıklar için ilintili alt sorgu
         * ({@code Sort} JOIN ekleyemez); {@link org.springframework.data.jpa.domain.JpaSort#unsafe} ifadeyi olduğu gibi
         * ekler. Takma adlar ({@code st}/{@code si}/{@code st2}) {@code findFiltered}'in alt sorgularıyla ({@code i},
         * {@code ti}) çakışmaz. {@code AlertEventRepositoryTest} H2'de pinler; Hibernate ORDER BY'da skaler alt sorguyu
         * PostgreSQL'e aynen çevirir.
         */
        public static final String TEAM_NAME =
                "(CASE WHEN e.teamId IS NOT NULL"
                + " THEN (SELECT MIN(st.name) FROM Team st WHERE st.id = e.teamId)"
                + " ELSE (SELECT MIN(st2.name) FROM CertificateInventory si, Team st2"
                + " WHERE si.domain = e.domain AND st2.id = si.teamId) END)";

        /** Beyaz listedeki anahtar ya da null (varsayılan). */
        static String key(String sort) {
            if (sort == null) return null;
            String k = sort.trim().toLowerCase(java.util.Locale.ROOT);
            return KEYS.contains(k) ? k : null;
        }

        static Sort.Direction direction(String dir) {
            return dir != null && "asc".equalsIgnoreCase(dir.trim()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        }

        public static Sort of(String sort, String dir, Boolean resolved) {
            String k = key(sort);
            Sort.Direction d = direction(dir);
            Sort tie = Sort.by(Sort.Direction.DESC, "createdAt");
            if (k == null) {
                return Boolean.TRUE.equals(resolved)
                        ? Sort.by(Sort.Direction.DESC, "resolvedAt").and(tie)
                        : tie;
            }
            return switch (k) {
                case "opened"   -> Sort.by(d, "createdAt");
                case "resolved" -> Sort.by(d, "resolvedAt").and(tie);
                case "domain"   -> Sort.by(d, "domain").and(tie);
                case "type"     -> Sort.by(d, "alertType").and(tie);
                case "team"     -> org.springframework.data.jpa.domain.JpaSort.unsafe(d, TEAM_NAME).and(tie);
                default         -> org.springframework.data.jpa.domain.JpaSort.unsafe(d, LEVEL_RANK).and(tie);   // level
            };
        }
    }

    private void requirePerm(HttpSession session, String key, String action) {
        permissionService.require(session, key, action);
    }

    /** Read scope: null = all teams; else only these (müdür: subordinates'; PO: led; USER: own). */
    private List<Long> viewScope(HttpSession session) {
        return SessionScope.viewTeamIds(session);
    }

    /** Global admin, or a manager-scope (PO) that includes the resource's team. (Müdür → false.) */
    private boolean canManageTeamResource(HttpSession session, Long resourceTeamId) {
        if (isAdmin(session)) return true;                 // global admin
        return SessionScope.canManage(session, resourceTeamId);
    }

    /**
     * Takım kimliği gerçekten var mı (2026-09-28): silinmiş/uydurma takıma yazılan kayıt (envanter aktarımı, toplu
     * takım, kişi) hiçbir alıcıya ulaşmaz — takım adresi de kişisi de yoktur → 400.
     */
    private void requireExistingTeam(Long teamId) {
        if (teamId != null && !teamRepo.existsById(teamId)) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Seçilen takım bulunamadı — geçerli bir takım seçin.", "The selected team doesn't exist — choose a valid team."));
        }
    }

    private void requireTeamScopedAdmin(HttpSession session, Long resourceTeamId) {
        if (!canManageTeamResource(session, resourceTeamId)) {
            log.warn("Cross-team or non-admin write attempt by user={} resourceTeam={}",
                    actor(session), resourceTeamId);
            throw new SecurityException("Access denied: not allowed to modify this team's resource");
        }
    }

    /**
     * Envanter kaydı AÇMA kapısı (2026-09-18): global admin → her takım; yönetim kapsamı (PO/müdür) →
     * yönettiği takımlar; USER → yalnız ÜYESİ olduğu takım (memberTeamIds; eski oturumda birincil).
     * Silme/aktarma/içe aktarma bu kapıyı KULLANMAZ — requireTeamScopedAdmin'de kalır; düzenleme de bu kapıdan
     * geçer (üye kendi takımının kaydını düzenler, takım alanı sabitlenir).
     */
    private void requireInventoryWriter(HttpSession session, Long teamId) {
        // Kural TEK kaynakta (SessionScope.canWriteInventory) — liste satırlarının can_manage bayrağı da onu okur.
        if (SessionScope.canWriteInventory(session, teamId)) return;
        log.warn("Inventory add outside membership by user={} team={}", actor(session), teamId);
        throw new SecurityException("Access denied: you can only add certificates to your own team");
    }

    private void requireAdminOrTeamAdmin(HttpSession session) {
        if (isAdmin(session)) return;
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null && !m.isEmpty()) return;             // PO with managed teams
        log.warn("Non-admin/team-admin write attempt by user={}", actor(session));
        throw new SecurityException("Admin or team-admin required");
    }

    private static final Set<String> TEAM_ADMIN_ASSIGNABLE_ROLES =
            Set.of("USER", "TEAM_ADMIN");

    /** Global olmayan yazar (TEAM_ADMIN ya da kapsamlı müdür) yalnız USER/TEAM_ADMIN atayabilir. */
    private static void requireAssignableRole(String requestedRole) {
        if (requestedRole != null && !TEAM_ADMIN_ASSIGNABLE_ROLES.contains(requestedRole)) {
            throw new SecurityException("Only a global admin can assign role: " + requestedRole);
        }
    }

    /** Hedef takımların HEPSİ çağıranın yönetim kapsamında olmalı (müdür başka takıma kullanıcı yazamaz). */
    private static void requireTeamsInManageScope(HttpSession session, List<Long> teams) {
        List<Long> scope = SessionScope.manageTeamIds(session);
        for (Long t : teams) {
            if (t == null || scope == null || !scope.contains(t)) {
                throw new SecurityException("Cannot assign a team outside your management scope: " + t);
            }
        }
    }

    /**
     * ADMIN/AUDIT hesabına (parola sıfırlama, pasife alma, silme, rol/takım değişimi) yalnız GLOBAL
     * admin dokunabilir. Takım kapsamı tek başına yetmez: müdürün kendi takımına atanmış bir global
     * admin, kapsam kontrolünden geçiyor ve parolası müdürce sıfırlanabiliyordu (hesap devralma).
     */
    private void requireCanAdministerTarget(HttpSession session, AppUser target) {
        if (isAdmin(session)) return;
        if (target != null && !TEAM_ADMIN_ASSIGNABLE_ROLES.contains(target.getSystemRole())) {
            log.warn("Non-global admin attempted to administer {} account user={} target={}",
                    target.getSystemRole(), actor(session), target.getUsername());
            throw new SecurityException("Only a global admin can administer an " + target.getSystemRole() + " account");
        }
    }

    private void requireAdmin(HttpSession session) {
        if (!isAdmin(session)) {
            log.warn("Unauthorized admin access attempt by user={}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    /**
     * BO9 (bug regresyon 2026-09-27) — envanter ekle/düzenle gövdesindeki {@code ug_team_id} kapısı.
     * Kabul: {@code null} (temizle — tek takıma yakınsama) ya da {@code allowed} ile aynı değer (düzenlemede
     * mevcut UG, eklemede kaydın kendi takımı). Başka bir takım 403: UG değişikliği YALNIZ
     * {@code POST /inventory/{id}/transfer-ug} (global admin + {@code inventory.transfer/execute}) ile yapılır;
     * aksi hâlde kaydın kendi takımına yazabilen herhangi bir üye kaydı başka takımın görünürlüğüne ve alarm
     * e-postalarına açabiliyordu.
     */
    private Long requireUgUnchangedOrCleared(HttpSession session, Long allowed, Long requested, String domain) {
        if (requested == null || java.util.Objects.equals(requested, allowed)) return requested;
        log.warn("Inventory UG team change via add/edit body refused: user={} domain={} requested={}",
                actor(session), domain, requested);
        throw new SecurityException(com.sitemonitor.util.Msg.t(
                "UG takımı bu uçtan değiştirilemez; yönetici UG aktarımını kullanın.",
                "The UG team cannot be changed here; use the administrator UG transfer."));
    }

    /** Tanılama (diagnostics) admin'e her domain için, diğer rollere YALNIZ envanterde
     *  kayıtlı (izlenen) domainler için açıktır — rastgele host+port probe'u (SSRF) engellenir.
     *  D13 notu: TAKIM izolasyonu burada BİLİNÇLİ uygulanmıyor — kapının amacı SSRF önlemek,
     *  veri gizlemek değil; tanılama çıktısı hedefin HERKESE açık yüzeyidir (TLS/DNS/HTTP el
     *  sıkışması), takıma özel yapılandırma içermez. İzolasyon istenirse buraya
     *  canView(inv.getTeamId()) eklenmeli. */
    /**
     * Bağımsız alan adı izlemeleri — {@link #requireDomainExpiryScope} için. Alan enjeksiyonu: {@code @RequiredArgsConstructor}
     * imzası değişmez (dilimli test bağlamlarında yoksa yalnız envanter kuralı geçerli kalır).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;

    /**
     * Alan adı süre bitişi tanılamasının kapsamı (2026-10-05, erişim düzeltmesi): envanter kuralı
     * ({@link #requireAdminOrMonitoredDomain}) ARTI bağımsız alan adı izlemesi — kullanıcı o izlemenin takımını
     * İŞLETEBİLİYORSA ({@link SessionScope#canOperateTeam}; liste satırının {@code can_check} / {@code can_diagnose}
     * kuralı). Alan Adı sayfasındaki "Sorun Tanıla" eskiden yalnız ADMIN'e çiziliyordu; {@code diagnostics.run} izni olan
     * takım üyesi kendi izlemesini tanılayabilir. Başka her şey eskisi gibi 403.
     */
    private void requireDomainExpiryScope(HttpSession session, String domain) {
        if (domain != null && domainMonitorRepo != null) {
            String d = domain.trim().toLowerCase(java.util.Locale.ROOT);
            for (com.sitemonitor.model.DomainMonitor m : domainMonitorRepo.findByDomain(d)) {
                if (m != null && SessionScope.canOperateTeam(session, m.getTeamId())) return;
            }
        }
        requireAdminOrMonitoredDomain(session, domain);
    }

    private void requireAdminOrMonitoredDomain(HttpSession session, String domain) {
        if (isAdminOrAudit(session)) return;
        // 2026-09-11: TEAM_ADMIN/USER da diagnostics.run alır → hedef yalnız KENDİ takımının envanter kaydı
        // olabilir (viewScope null = tüm takımlar). Eskiden envanterdeki HER alan adına izin veriyordu; o
        // zaman yetki admin'deydi, şimdi kapsam uçta kesinleşir.
        if (domain != null) {
            var inv = inventoryRepo.findByDomain(domain.trim()).orElse(null);
            if (inv != null) {
                List<Long> scope = viewScope(session);
                // scope null = tüm takımlar (global admin/AUDIT); teamId null = SAHİPSİZ kayıt — başka
                // takımın değil, dolayısıyla reddedilmez (envanter kayıtlarının bir kısmı takımsız doğar).
                if (scope == null || inv.getTeamId() == null || scope.contains(inv.getTeamId())) return;
            }
        }
        log.warn("Diagnostics denied (non-admin, domain='{}' not in team scope) user={}", domain, actor(session));
        throw new SecurityException("Bu domain için tanılama yetkiniz yok");
    }

    private String resolveDisplayName(HttpSession session) {
        String dn = (String) session.getAttribute("displayName");
        return (dn != null && !dn.isBlank()) ? dn : (String) session.getAttribute("username");
    }

    /** Username extracted from session — used in audit log entries. */
    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private Long teamId(HttpSession session) {
        Object raw = session.getAttribute("teamId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    private Long userIdFromSession(HttpSession session) {
        Object raw = session.getAttribute("userId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    /**
     * Refuses to push the system below 1 active ADMIN. Caller passes the post-mutation
     * status of {@code target}; if it's no longer an active admin and there are not
     * enough other active admins to cover, a SecurityException is thrown.
     */
    private void guardLastActiveAdmin(AppUser target, boolean willRemainAdminAndActive) {
        if (willRemainAdminAndActive) return;
        if (!"ADMIN".equals(target.getSystemRole())) return;
        if (!Boolean.TRUE.equals(target.getActive())) return;
        long activeAdmins = userRepo.countBySystemRoleAndActiveTrue("ADMIN");
        if (activeAdmins <= 1) {
            log.warn("Refused to remove last active ADMIN (target user id={})", target.getId());
            throw new SecurityException("Cannot remove the last active ADMIN from the system");
        }
    }

    private void checkOwnership(Long resourceTeamId, HttpSession session) {
        Long userTeamId = teamId(session);
        if (!Objects.equals(resourceTeamId, userTeamId)) {
            throw new SecurityException("Access denied: resource belongs to a different team");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    /** Girdiyi önce normalize eder (URL yapıştırılabilsin: şema/path/port/userinfo soyulur —
     *  https://www.wingscard.com.tr/ → www.wingscard.com.tr), sonra host formatını doğrular.
     *  Subdomain KORUNUR (host-düzeyi diagnostics için); registrable'a indirgeme (PSL) yalnız
     *  domain-expiry akışının kendi içinde yapılır. Normalize edilmiş host döner. */
    /** Kural {@link com.sitemonitor.service.DomainNames#validate} — içe aktarma servisiyle ortak. */
    /**
     * Envanter kaydı da bir izleme: grup ve en az bir etiket zorunlu (2026-09-18, ürün kararı — dokuz
     * izleme türüyle aynı kural, bkz. MonitoringController.requireGroupAndTags). Form alanı eksikken
     * düzenleme mevcut etiketleri SİLİYORDU (existing.setTags(null)); artık boş gönderim reddedilir.
     */
    private static void requireInventoryGroupAndTags(CertificateInventory item) {
        if (item.getGroupName() == null || item.getGroupName().isBlank())
            throw new IllegalArgumentException("Grup seçimi zorunludur; kayıt kaydedilemez.");
        if (item.getTags() == null || item.getTags().isBlank())
            throw new IllegalArgumentException("En az bir etiket zorunludur; kayıt kaydedilemez.");
    }

    private static String validateDomain(String domain) {
        return com.sitemonitor.service.DomainNames.validate(domain);
    }

    /** Tanılama hedefi doğrulaması (bağlanan uçlar için): validateDomain + SSRF (SsrfGuard). Çözülen IP
     *  cloud-metadata/loopback/link-local ise (localhost/tek-etiket dahil) reddedilir; iç/site-local host'lar
     *  allow-internal-targets (vars. açık) ile izinli kalır → iç Akbank host'ları tanılanabilir. */
    private String validateDiagTarget(String domain) {
        String host = validateDomain(domain);
        try {
            ssrfGuard.validate(host);
        } catch (SsrfGuard.UnresolvableHostException ue) {
            // ÇÖZÜLEMEYEN host bir politika reddi DEĞİL. Eskiden ikisi de "İzin verilmeyen
            // tanılama hedefi" diye çıkıyordu ve kullanıcı aracın kendisini engellediğini
            // sanıyordu; oysa çözüm host adını düzeltmek. Apex'in A kaydı olmayıp yalnız
            // "www" yayınlanması çok yaygın olduğu için, varsa doğrudan o önerilir.
            String hint = wwwAlternative(host);
            throw new com.sitemonitor.config.GlobalExceptionHandler.UnresolvableTargetException(
                    "Çözümlenemeyen host: " + host + " — DNS'te A/AAAA kaydı yok."
                            + (hint != null ? " Bunu deneyin: " + hint : ""),
                    host, hint);
        } catch (SsrfGuard.BlockedException be) {
            throw new IllegalArgumentException("İzin verilmeyen tanılama hedefi: " + be.getMessage());
        }
        return host;
    }

    /**
     * KAYIT sorgusu hedefi — biçim doğrulanır, DNS çözümü ARANMAZ.
     *
     * <p>Süre-bitişi tanılaması hedefe BAĞLANMAZ: PSL → IANA bootstrap → registry RDAP →
     * WHOIS zinciriyle REGISTRY sunucularına sorar. Bu yüzden alan adının A/AAAA kaydı
     * olması gerekmez — kaydı olan ama yalnız {@code www} host'u yayınlanmış bir alan adı
     * (kurumsal alan adlarında çok yaygın) tanılanabilmeli. Eskiden {@link #validateDiagTarget}
     * kullanıldığı için bu alan adları "çözümlenemeyen host" diye REDDEDİLİYORDU.
     *
     * <p>Öneri de anlamsızdı: servis girdiyi zaten kayıtlı alan adına indirgiyor
     * ({@code www.x.com} → {@code x.com}), yani "www ile deneyin" AYNI sonucu verirdi.
     *
     * <p>SSRF riski yok: bağlanılan yer kullanıcının host'u değil, TLD'nin registry'si.
     * Yetki kapısı ({@code requireAdminOrMonitoredDomain}) yerinde kalır.
     */
    private String validateRegistryTarget(String domain) {
        return validateDomain(domain);
    }

    /** {@code www.<host>} çözülüyor mu — yalnız HATA yolunda, tek ek sorgu. Çözülmüyorsa null. */
    private String wwwAlternative(String host) {
        if (host == null || host.startsWith("www.")) return null;
        String candidate = "www." + host;
        try {
            ssrfGuard.validate(candidate);
            return candidate;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    /** Body'den çoklu takım listesi: önce `team_ids` (dizi), yoksa tek `team_id` → [id] (geriye-uyum).
     *  Sıra korunur (birincil = ilk). İçerik yoksa boş liste döner. */
    private List<Long> teamIdsFromBody(Map<String, Object> body) {
        List<Long> out = new ArrayList<>();
        if (body.get("team_ids") instanceof List<?> list) {
            for (Object o : list) { Long v = toLong(o); if (v != null && !out.contains(v)) out.add(v); }
            return out;
        }
        Long single = toLong(body.get("team_id"));
        if (single != null) out.add(single);
        return out;
    }

    /** Kullanıcı, verilen görünür-takım scope'undaki herhangi bir takıma üye mi (birincil veya ek). */
    private boolean inScope(AppUser u, List<Long> scope) {
        if (u.getTeamId() != null && scope.contains(u.getTeamId())) return true;
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (scope.contains(t)) return true;
        return false;
    }
}
