package com.sitemonitor.controller;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.NotificationGroupHistoryService;
import com.sitemonitor.service.NotificationGroupService;
import com.sitemonitor.service.NotificationGroupUsageService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Takım Bildirim Grupları — alarm e-postalarının gideceği adlandırılmış alıcı listeleri.
 *
 * <h2>İki yetki ilkesi ({@code ScriptedTemplateController} emsali)</h2>
 * <ul>
 *   <li><b>Okuma:</b> {@code SessionScope.canView} — takım görüş kapsamında olmalı. Grubun
 *       {@code teamId}'si ASLA null olamaz, yani "Genel şablon" istisnasının karşılığı yoktur.</li>
 *   <li><b>Yazma:</b> {@code canManage} YETMEZ — USER'ın manage kapsamı boştur, oysa alarmı
 *       kimin alacağı takımın nöbet düzenidir, yönetici işlemi değil (K2). Bu yüzden
 *       {@code isMemberOf} de kabul edilir.</li>
 * </ul>
 *
 * <h2>Durum kodu değişmezi</h2>
 * Okunamayan grup her fiil için <b>404</b> (id'ler küçük ve tahmin edilebilir; başka takımın
 * grubunun VAR OLDUĞU bilgisi bile sızmamalı). Okunabilir ama yazılamazsa <b>403</b>.
 * İkisi de {@code GlobalExceptionHandler} üzerinden güvenlik olayı olarak denetlenir.
 */
@Slf4j
@RestController
@RequestMapping("/api/notification-groups")
@RequiredArgsConstructor
public class NotificationGroupController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String PERM = "notification.groups";
    private static final String NOT_FOUND_MSG = "Bildirim grubu bulunamadı";

    /** Denetim diff'ine giren alanlar — adres listesi DAHİL: "alarmı kim alıyor" sorusunun cevabı. */
    private static final String[] FIELDS = { "name", "emails", "isDefault", "active", "teamId" };

    private final NotificationGroupRepository groupRepo;
    private final NotificationGroupService groupService;
    private final NotificationGroupUsageService usageService;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final NotificationGroupHistoryService historyService;

    /**
     * Alıcı önizlemesi (2026-11-13): grubun hangi adresleri YALNIZ pasif hesaplara ait — gönderimde düşer. İsteğe bağlı
     * (dilim testleri ve elle kurulan bağlamlar bean'siz çalışır → alan {@code null} = bilinmiyor).
     */
    private com.sitemonitor.service.InactiveRecipientGuard inactiveGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInactiveGuard(com.sitemonitor.service.InactiveRecipientGuard guard) {
        this.inactiveGuard = guard;
    }

    /**
     * Açık alarm damgası sayımı (2026-11-13, toplu taşıma + kullanım sayıları) — isteğe bağlı: dilim testleri ve elle
     * kurulan bağlamlar bean'siz çalışır (alan {@code null} = damga bilgisi yok).
     */
    private com.sitemonitor.repository.AlertEventRepository alertRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAlertRepo(com.sitemonitor.repository.AlertEventRepository repo) {
        this.alertRepo = repo;
    }

    /**
     * Grubun pasif-yalnız adresleri — gönderim süzgeci ({@code InactiveRecipientGuard}) bunları düşürür. {@code null}:
     * bilinmiyor (bean yok ya da pasif listesi hiç okunamadı); arayüz o zaman "pasif adres yok" demez.
     */
    private List<String> inactiveEmails(Object emails) {
        com.sitemonitor.service.InactiveRecipientGuard g = inactiveGuard;
        if (g == null) return null;
        if (!(emails instanceof java.util.Collection<?> c)) return List.of();
        List<String> list = new ArrayList<>();
        for (Object o : c) if (o != null) list.add(o.toString());
        try {
            return g.inactiveOnlyEmailsOf(list);
        } catch (RuntimeException e) {
            log.debug("Grup alıcı önizlemesi okunamadı: {}", e.toString());
            return null;
        }
    }

    // ── Yanıt yardımcıları ───────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", NOT_FOUND_MSG));
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String actorName(HttpSession session) {
        Object n = session != null ? session.getAttribute("displayName") : null;
        return n != null && !n.toString().isBlank() ? n.toString() : actor(session);
    }

    // ── Yetki ────────────────────────────────────────────────────────────────

    /** Bu takıma grup yazılabilir mi? Üyelik VEYA yönetim yetkisi yeter (K2). */
    private static boolean canWriteTeam(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        return SessionScope.isGlobalAdmin(session)
                || SessionScope.canManage(session, teamId)
                || SessionScope.isMemberOf(session, teamId);
    }

    /**
     * Kullanıcının grup yazabildiği takımlar — arayüz takım seçicisini BUNDAN doldurur.
     *
     * <p>{@link #canWriteTeam} ile AYNI cevabı vermek zorundadır: global admin orada her takıma
     * yazabiliyor, burada listelenmezse admin'e "yazabileceğin takım yok" denirdi (şablon
     * kütüphanesinde yaşanan kusurun aynısı).
     */
    private List<Long> writableTeamIds(HttpSession session) {
        LinkedHashSet<Long> out = new LinkedHashSet<>(SessionScope.memberTeamIds(session));
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage != null) out.addAll(manage);
        if (SessionScope.isGlobalAdmin(session)) {
            teamRepo.findAll().forEach(t -> out.add(t.getId()));
        }
        return new ArrayList<>(out);
    }

    /** Kapsamlı görüntüleyicinin görebildiği grupların kimlikleri (silinmişler dahil — liste ucunun includeInactive kümesi). */
    private Set<Long> visibleGroupIds(HttpSession session) {
        List<Long> scope = readableTeamIds(session);
        Set<Long> ids = new HashSet<>();
        if (scope.isEmpty()) return ids;
        for (NotificationGroup g : groupRepo.findByTeamIdInOrderByTeamIdAscNameAsc(scope)) if (g.getId() != null) ids.add(g.getId());
        return ids;
    }

    /** Görülebilir takımlar — global görücü hepsini, diğerleri görüş kapsamını. */
    private List<Long> readableTeamIds(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) {
            return teamRepo.findAll().stream().map(Team::getId).toList();
        }
        List<Long> view = SessionScope.viewTeamIds(session);
        return view == null ? List.of() : view;
    }

    // ── Listeleme ────────────────────────────────────────────────────────────

    /**
     * @param teamId yalnız bu takımın grupları (monitör formundaki seçici bunu kullanır)
     * @param includeInactive silinmiş grupları da döndür — kayıtlı seçimi "silinmiş" rozetiyle
     *                        gösterebilmek için; varsayılan {@code false}
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            HttpSession session) {
        permissionService.require(session, PERM, "view");

        List<NotificationGroup> rows;
        if (teamId != null) {
            if (!SessionScope.canView(session, teamId)) return notFound();
            rows = includeInactive
                    ? groupRepo.findByTeamIdOrderByNameAsc(teamId)
                    : groupRepo.findByTeamIdAndActiveTrueOrderByNameAsc(teamId);
        } else {
            // TEK sorgu. Eskiden takım başına dönülüyordu ve yorumda "kapsam küçüktür, kullanıcı
            // birkaç takımın üyesidir" yazıyordu — bu GLOBAL ADMIN/AUDIT için yanlıştı: onların
            // kapsamı TÜM takımlar ve bu ekranı tam olarak onlar açıyor. Tek pod'da takım sayısı
            // kadar sorgu demekti.
            // Boş koleksiyon JPQL "IN ()" üretir (sağlayıcıya bağlı sözdizimi hatası) → korunur.
            List<Long> scope = readableTeamIds(session);
            rows = scope.isEmpty() ? List.of()
                    : (includeInactive
                        ? groupRepo.findByTeamIdInOrderByTeamIdAscNameAsc(scope)
                        : groupRepo.findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(scope));
        }

        // Takım tablosu TEK kez okunur; iki harita aynı geçişte kurulur (eskiden findAll iki kez
        // çağrılıyordu, readableTeamIds'inkiyle birlikte global admin yolunda üç kez).
        Map<Long, String> teamNames = new LinkedHashMap<>();
        Map<Long, String> teamEmails = new LinkedHashMap<>();
        for (Team t : teamRepo.findAll()) {
            teamNames.put(t.getId(), t.getName());
            teamEmails.put(t.getId(), t.getEmail());
        }

        List<Map<String, Object>> items = rows.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>(groupService.toDto(g));
            m.put("team_name", teamNames.get(g.getTeamId()));
            m.put("can_write", canWriteTeam(session, g.getTeamId()));
            // Alıcı önizlemesi: gönderimde düşecek (yalnız pasif hesaba ait) adresler; null = bilinmiyor
            m.put("inactive_emails", inactiveEmails(m.get("emails")));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", items);
        out.put("writable_team_ids", writableTeamIds(session));
        out.put("team_names", teamNames);
        // DÜRÜST BOŞ DURUM için: grup yoksa arayüz "alarmlar şu adrese gidiyor" diyebilsin.
        out.put("team_emails", teamEmails);
        return ok(out);
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        Long teamId = resolveTeamId(body, session);
        if (!canWriteTeam(session, teamId))
            throw new SecurityException("Bu takımda bildirim grubu oluşturamazsınız");

        var in = groupService.validate(str(body.get("name")), emails(body.get("emails")), bool(body.get("is_default")));
        NotificationGroup saved = groupService.create(teamId, in, actor(session), actorName(session));

        // Anlık görüntü YAZILIR: "neyi ekledi" sorusunun cevabı yalnız burada kalır. Daha önce
        // null geçiliyordu; geçmişte oluşturma satırı görünüyor ama İÇERİĞİ (adresler, varsayılan
        // mı) hiçbir yerde okunamıyordu — üstelik grup sonradan silinince kimlik→takım eşlemesi
        // de bu kayıttan kuruluyor.
        auditService.recordAction("NOTIFICATION_GROUP_CREATE", session, "NOTIFICATION_GROUP",
                String.valueOf(saved.getId()), saved.getName(),
                AuditDiff.snapshotJson(AuditDiff.snapshot(saved, FIELDS)));
        return ok(groupService.toDto(saved));
    }

    /** POST'un kapsam çözümü: açıkça verilen takım, yoksa yazılabilir ilk takım. */
    private Long resolveTeamId(Map<String, Object> body, HttpSession session) {
        Long explicit = toLong(body.get("team_id"));
        if (explicit != null) return explicit;
        List<Long> writable = writableTeamIds(session);
        if (writable.isEmpty())
            throw new IllegalArgumentException("Grup oluşturabileceğiniz bir takım yok");
        return writable.get(0);
    }

    // ── Güncelleme ───────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu düzenleme yetkiniz yok");

        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        // Yalnız gönderilen alanlar değişir (2026-11-13 hata taraması): düzenleme formu yalnız DEĞİŞEN alanları gönderir;
        // gönderilmeyen ad / adresler kayıtlı değerinde kalır, gönderilmeyen is_default bayrağa DOKUNMAZ — açık formun bayat
        // "varsayılan" kutusu toplu "varsayılan yap"ı geri almaz (false) ya da eski grubu yeniden varsayılan yapmaz (true).
        var in = groupService.validate(
                body.containsKey("name") ? str(body.get("name")) : g.getName(),
                body.containsKey("emails") ? emails(body.get("emails")) : NotificationGroupService.parseEmails(g.getEmails()),
                bool(body.get("is_default")));
        if (!body.containsKey("is_default")) in = in.keepingDefault();
        NotificationGroup saved = groupService.update(g, in, actor(session), actorName(session));

        auditService.recordAction("NOTIFICATION_GROUP_UPDATE", session, "NOTIFICATION_GROUP",
                String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, FIELDS)));
        return ok(groupService.toDto(saved));
    }

    /** Ayrı uç: "varsayılan yap" tek tıklık bir işlemdir, formun tamamını yeniden göndermez. */
    @PostMapping("/{id}/make-default")
    public ResponseEntity<Map<String, Object>> makeDefault(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu düzenleme yetkiniz yok");
        if (!Boolean.TRUE.equals(g.getActive()))
            throw new IllegalArgumentException("Silinmiş grup varsayılan yapılamaz");

        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        NotificationGroup saved = groupService.makeDefault(g);
        auditService.recordAction("NOTIFICATION_GROUP_DEFAULT", session, "NOTIFICATION_GROUP",
                String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, FIELDS)));
        return ok(groupService.toDto(saved));
    }

    // ── Silme (yumuşak) ──────────────────────────────────────────────────────

    /**
     * Grup NEREDE kullanılıyor — silme onayından önce gösterilen özet.
     *
     * <p>Silme ekranı bunu çağırıp kullanıcıya etkilenecek izlemeleri gösterir; kullanıcı hangi
     * izlemelerin yönlendirmesini değiştireceğini GÖRMEDEN karar vermek zorunda kalmasın.
     */
    @GetMapping("/{id}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, PERM, "view");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        return ok(usageMap(usageService.usage(id)));
    }

    /**
     * TÜM grupların referans sayısı (grup id → adet) — "kullanılmayan grup" tespiti için (2026-11-12). Tek toplu
     * hesap (tür başına GROUP BY); frontend yalnız kendi gördüğü gruplar için kullanır. Haritada olmayan grup = 0.
     */
    @GetMapping("/usage-counts")
    public ResponseEntity<Map<String, Object>> usageCounts(HttpSession session) {
        permissionService.require(session, PERM, "view");
        // Kapsam (2026-11-13 hata taraması): yalnız GÖREBİLDİĞİ takımların grupları — liste ucuyla aynı kural
        // (readableTeamIds). Eskiden tüm takımların grup kimlikleri ve açık alarm sayıları izin sahibi herkese dönüyordu.
        Set<Long> visible = SessionScope.isGlobalViewer(session) ? null : visibleGroupIds(session);
        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        usageService.usageCounts().forEach((id, n) -> { if (visible == null || visible.contains(id)) counts.put(String.valueOf(id), n); });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("counts", counts);
        // Açık alarma damgalı grup sayıları (toplu taşıma önizlemesi "atlanacak" diyebilsin). Okunamazsa ya da sayım
        // bean'i yoksa null — "damga yok" DEĞİL (arayüz o zaman "sunucu denetleyecek" der).
        Map<Long, Integer> st = alertRepo == null ? null : stampedOpenAlerts();
        Map<String, Object> sm = null;
        if (st != null) {
            sm = new LinkedHashMap<>();
            for (Map.Entry<Long, Integer> e : st.entrySet()) {
                if (visible == null || visible.contains(e.getKey())) sm.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        data.put("stamped_open_alerts", sm);
        return ok(data);
    }

    /**
     * Grubun TÜM referanslarını başka bir gruba taşır ({@code target_group_id} null → takım
     * varsayılanı). Silmeden önceki hazırlık adımı: kullanıcı 200 izlemeyi tek tek gezmesin.
     */
    @PostMapping("/{id}/reassign")
    public ResponseEntity<Map<String, Object>> reassign(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup from = groupRepo.findById(id).orElse(null);
        if (from == null || !SessionScope.canView(session, from.getTeamId())) return notFound();
        if (!canWriteTeam(session, from.getTeamId()))
            throw new SecurityException("Bu grubun izlemelerini taşıma yetkiniz yok");

        Long targetId = toLong(body.get("target_group_id"));
        if (targetId != null) {
            NotificationGroup to = groupRepo.findById(targetId).orElse(null);
            // Hedef AYNI takımın AKTİF grubu olmalı: aksi halde toplu taşıma, tek tek yapılması
            // engellenen şeyi (başka takıma yönlendirme) toptan yapmanın yolu olurdu.
            if (to == null || !from.getTeamId().equals(to.getTeamId()))
                throw new IllegalArgumentException("Hedef grup bu takıma ait değil");
            if (!Boolean.TRUE.equals(to.getActive()))
                throw new IllegalArgumentException("Silinmiş bir gruba taşıma yapılamaz");
            if (targetId.equals(id))
                throw new IllegalArgumentException("Hedef grup kaynakla aynı olamaz");
        }

        Map<String, Integer> moved = usageService.reassign(id, targetId);
        int total = moved.values().stream().mapToInt(Integer::intValue).sum();
        auditService.recordAction("NOTIFICATION_GROUP_REASSIGN", session, "NOTIFICATION_GROUP",
                String.valueOf(id), from.getName(),
                "{\"to\":" + targetId + ",\"moved\":" + total + "}");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", total);
        out.put("by_type", moved);
        return ok(out);
    }

    // ── Toplu işlem (2026-11-13) ─────────────────────────────────────────────

    /** Toplu eylemler. Tavan {@link ContactBulkController#MAX_ITEMS} ({@code parseIds} uygular). */
    static final Set<String> BULK_ACTIONS = Set.of("delete", "set_team", "make_default");

    /**
     * Seçili gruplarda toplu işlem (2026-11-13, yönetim zenginleştirmesi (d)): {@code delete} (yalnız KULLANILMAYAN ve
     * varsayılan OLMAYAN gruplar kalıcı silinir), {@code set_team} (kullanılmayan, varsayılan olmayan grup başka takıma
     * taşınır), {@code make_default} (her grup kendi takımının varsayılanı — Takımlar → toplu düzeltme de bunu çağırır).
     *
     * <p><b>Neden etkinleştir / pasifleştir YOK:</b> {@code active=false} eski YUMUŞAK SİLMENİN izidir (silme 2026'dan beri
     * kalıcı); "grubu kapat" anlamı taşımaz ve çözümleme onu yok sayar — toplu "etkinleştir" silinmiş grubu geri getirirdi.
     *
     * <p><b>Neden kullanılan grup taşınmaz:</b> izlemeye damgalı grup YALNIZ kendi takımında uygulanır
     * ({@code NotificationGroupService.overrideFor} sahiplik denetimi) — taşınan grup o izlemeler için sessizce düşer ve
     * alarm takım adresine (o da yoksa kimseye) gider. Varsayılan grup taşınmaz / silinmez: kaynak takım varsayılansız
     * kalırdı (izlemelerin tümü takım adresine düşer), hedefte ikinci varsayılan doğardı.
     *
     * <p>Kapsam satır satır, tekil uçlarla aynı: görülmeyen grup {@code not_found} (varlığı sızmaz), görülüp yazılamayan
     * {@code out_of_scope}. Taşıma hedefi her satır için aynıdır → hedefe yazılamıyorsa istek tümden 403. Her işlenen satır
     * tekil uçla AYNI denetim olayını yazar (grup geçmişi kimliğe göre takımı çözer; "bulk" kimlikli özet satırı orada
     * çözülemeyip gizli sayılırdı — bu yüzden özet satırı YOK).
     */
    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        String action = body.get("action") == null ? "" : body.get("action").toString().trim().toLowerCase(Locale.ROOT);
        if (!BULK_ACTIONS.contains(action)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("action", com.sitemonitor.util.Msg.t(
                    "Geçersiz toplu işlem. Sil, takıma taşı ya da varsayılan yap seçin.",
                    "Invalid bulk action. Choose delete, move to team or make default."));
        }
        List<Long> ids = ContactBulkController.parseIds(body.get("ids"), "ids");

        Long target = null;
        if ("set_team".equals(action)) {
            target = toLong(body.get("team_id"));
            if (target == null) throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_id",
                    com.sitemonitor.util.Msg.t("Grupların taşınacağı takımı seçin.", "Choose the team to move the groups to."));
            if (!teamRepo.existsById(target)) throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_id",
                    com.sitemonitor.util.Msg.t("Seçilen takım bulunamadı — geçerli bir takım seçin.",
                            "The selected team doesn't exist — choose a valid team."));
            if (!canWriteTeam(session, target)) {
                throw new SecurityException(com.sitemonitor.util.Msg.t(
                        "Grupları yalnız grup yazabildiğiniz bir takıma taşıyabilirsiniz.",
                        "You can only move groups to a team whose groups you can edit."));
            }
        }

        Map<Long, String> teamNames = new HashMap<>();
        teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName()));
        // Kullanım ve damgalı açık alarm sayıları TEK SEFER toplu okunur (türe göre 11 + 1 gruplu sorgu); satır başına
        // kullanım sorgusu YOK (200 satırda ~2200 sorgu olurdu). Kalan yarış (döngü süresince bir izlemeye atanan grup)
        // tekil silmedeki "denetim → sil" aralığıyla aynı sınıftadır.
        Map<Long, Integer> counts = "make_default".equals(action) ? Map.of() : usageService.usageCounts();
        Map<Long, Integer> stamped = "set_team".equals(action) ? stampedOpenAlerts() : Map.of();

        List<Map<String, Object>> results = new ArrayList<>();
        Set<Long> defaultTeams = new HashSet<>();
        int done = 0, skipped = 0, failed = 0;
        for (Long id : ids) {
            // Her satır YAZMADAN HEMEN ÖNCE yeniden okunur (kapsam ve kurallar taze satıra göre); yazım hedefli UPDATE'tir.
            NotificationGroup g = groupRepo.findById(id).orElse(null);
            if (g == null || !SessionScope.canView(session, g.getTeamId())) {
                results.add(bulkRow(id, null, null, null, "skipped", "not_found")); skipped++; continue;
            }
            String teamName = teamNames.get(g.getTeamId());
            if (!canWriteTeam(session, g.getTeamId())) {
                results.add(bulkRow(id, g.getName(), g.getTeamId(), teamName, "skipped", "out_of_scope")); skipped++; continue;
            }
            Long teamBefore = g.getTeamId();
            try {
                String reason = switch (action) {
                    case "delete" -> bulkDelete(g, counts, session);
                    case "set_team" -> bulkMove(g, target, counts, stamped, session);
                    default -> bulkMakeDefault(g, defaultTeams, session);
                };
                Long teamNow = "done".equals(reason) && "set_team".equals(action) ? target : teamBefore;
                Map<String, Object> r = bulkRow(id, g.getName(), teamNow, teamNames.get(teamNow),
                        "done".equals(reason) ? "done" : "skipped", reason);
                if ("in_use".equals(reason)) r.put("usage", counts.getOrDefault(id, 0));
                if ("stamped_open_alerts".equals(reason)) r.put("open_alerts", stamped.getOrDefault(id, 0));
                results.add(r);
                if ("done".equals(reason)) done++; else skipped++;
            } catch (RuntimeException e) {
                log.warn("Toplu bildirim grubu işlemi ({}) başarısız id={}: {}", action, id, e.toString());
                Map<String, Object> r = bulkRow(id, g.getName(), teamBefore, teamName, "failed", "error");
                r.put("message", com.sitemonitor.util.ErrorTexts.safeMessage(e, "Grup işlenemedi.", "The group couldn't be processed."));
                results.add(r);
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("done", done);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("results", results);
        return ok(out);
    }

    /** Kullanılmayan, varsayılan olmayan grubu kalıcı siler (tekil silmeyle aynı denetim satırı). {@code g} taze okunmuştur. */
    private String bulkDelete(NotificationGroup g, Map<Long, Integer> counts, HttpSession session) {
        if (Boolean.TRUE.equals(g.getIsDefault()) && Boolean.TRUE.equals(g.getActive())) return "is_default";
        if (counts.getOrDefault(g.getId(), 0) > 0) return "in_use";
        String snapshot = AuditDiff.snapshotJson(AuditDiff.snapshot(g, FIELDS));
        String name = g.getName();
        groupService.deletePermanently(g);
        auditService.recordAction("NOTIFICATION_GROUP_DELETE", session, "NOTIFICATION_GROUP",
                String.valueOf(g.getId()), name, snapshot);
        return "done";
    }

    /**
     * Kullanılmayan, varsayılan olmayan AKTİF grubu hedef takıma taşır (ad hedefte boş olmalı). Açık alarma DAMGALI grup
     * taşınmaz ({@code stamped_open_alerts}): damga yalnız alarmın takımında uygulanır, taşımadan sonra o alarmların
     * yeniden uyarı / çözüm postası sessizce varsayılana ya da takım adresine düşerdi; damga sayısı okunamazsa
     * {@code stamp_unknown} (bilinmeyen = işlem yok). Yazım yalnız takım kolonunu (+ son düzenleyen) okunan takıma koşullu
     * günceller — 0 satır = grup bu arada değişti.
     */
    private String bulkMove(NotificationGroup g, Long target, Map<Long, Integer> counts, Map<Long, Integer> stamped,
                            HttpSession session) {
        if (target.equals(g.getTeamId())) return "unchanged";
        if (!Boolean.TRUE.equals(g.getActive())) return "inactive";
        if (Boolean.TRUE.equals(g.getIsDefault())) return "is_default";
        if (counts.getOrDefault(g.getId(), 0) > 0) return "in_use";
        if (stamped == null) return "stamp_unknown";
        if (stamped.getOrDefault(g.getId(), 0) > 0) return "stamped_open_alerts";
        if (groupRepo.existsByTeamAndName(target, g.getName(), g.getId())) return "name_taken";
        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        String at = ISO.format(Instant.now());
        if (groupRepo.bulkMoveTeam(g.getId(), g.getTeamId(), target, at, actor(session), actorName(session)) == 0) {
            return "changed_meanwhile";
        }
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("teamId", target);
        auditService.recordAction("NOTIFICATION_GROUP_UPDATE", session, "NOTIFICATION_GROUP",
                String.valueOf(g.getId()), g.getName(), AuditDiff.diff(before, after));
        return "done";
    }

    /**
     * Grubu kendi takımının varsayılanı yapar (aynı takımdan ikinci grup aynı istekte atlanır). Etkin alıcısı olmayan grup
     * varsayılan yapılmaz: gönderilebilir adresi yoksa {@code no_emails}, adreslerin TAMAMI pasif hesaplara aitse
     * {@code no_active_emails} (gönderim süzgeci hepsini düşürür — grup "uygulanır" ama posta kimseye gitmez), pasif
     * listesi okunamıyorsa {@code active_unknown} (bilinmeyen = işlem yok). Yazım yalnız bayrakları günceller.
     */
    private String bulkMakeDefault(NotificationGroup g, Set<Long> defaultTeams, HttpSession session) {
        if (!Boolean.TRUE.equals(g.getActive())) return "inactive";
        if (defaultTeams.contains(g.getTeamId())) return "duplicate_team";
        if (Boolean.TRUE.equals(g.getIsDefault())) { defaultTeams.add(g.getTeamId()); return "unchanged"; }
        List<String> deliverable = NotificationGroupService.deliverableEmails(g.getEmails());
        if (deliverable.isEmpty()) return "no_emails";
        List<String> passive = inactiveEmails(deliverable);
        if (passive == null) return "active_unknown";
        if (passive.size() >= deliverable.size()) return "no_active_emails";
        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        if (!groupService.makeDefaultTargeted(g.getId(), g.getTeamId())) return "changed_meanwhile";
        defaultTeams.add(g.getTeamId());
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("isDefault", Boolean.TRUE);
        auditService.recordAction("NOTIFICATION_GROUP_DEFAULT", session, "NOTIFICATION_GROUP",
                String.valueOf(g.getId()), g.getName(), AuditDiff.diff(before, after));
        return "done";
    }

    /**
     * Açık alarm damgası sayıları (grup id → adet). {@code null} = okunamadı (taşıma "bilinmiyor" ile atlanır); depo
     * bean'i yoksa (dilim testleri) boş harita = damga bilgisi yok.
     */
    private Map<Long, Integer> stampedOpenAlerts() {
        com.sitemonitor.repository.AlertEventRepository repo = alertRepo;
        if (repo == null) return Map.of();
        try {
            Map<Long, Integer> out = new HashMap<>();
            for (Object[] r : repo.countOpenByNotificationGroup()) {
                if (r[0] instanceof Number gid && r[1] instanceof Number n) out.put(gid.longValue(), n.intValue());
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Damgalı açık alarm sayıları okunamadı: {}", e.toString());
            return null;
        }
    }

    private static Map<String, Object> bulkRow(Long id, String name, Long teamId, String teamName, String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("team_id", teamId);
        m.put("team_name", teamName);
        m.put("status", status);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> usageMap(NotificationGroupUsageService.Usage u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", u.total());
        m.put("by_type", u.byType());
        m.put("items", u.items().stream().map(r -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("type", r.type());
            x.put("id", r.id());
            x.put("name", r.name());
            return x;
        }).toList());
        m.put("truncated", u.total() > u.items().size());
        return m;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu silme yetkiniz yok");

        // KULLANIMDAYSA SİLİNMEZ. Sessizce silmek, o izlemelerin alarm yönlendirmesini kullanıcı
        // görmeden değiştirirdi. 409 + kullanım özeti döner; arayüz özeti gösterip "başka gruba
        // taşı" adımını sunar. IllegalStateException → 409 (GlobalExceptionHandler).
        var u = usageService.usage(id);
        if (u.inUse()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", "Bu grup " + u.total() + " yerde kullanılıyor — önce başka bir gruba taşıyın");
            body.put("usage", usageMap(u));      // arayüz modali BU özetten besleniyor
            return ResponseEntity.status(409).body(body);
        }

        // Denetim kaydı silmeden ÖNCE: satır gittikten sonra kimin neyi sildiği okunamazdı.
        // Diff yerine son durumun anlık görüntüsü yazılır — "sonra"sı yok, kayıt tamamen gitti.
        // snapshotJson: düz `String.valueOf(Map)` JSON DEĞİLDİR ({name=X, ...}); geçmiş ekranı
        // ve takım çözümü bu alanı ayrıştırmak zorunda, ayrıştırılamayan kayıt kaybolmuş sayılır.
        String snapshot = AuditDiff.snapshotJson(AuditDiff.snapshot(g, FIELDS));
        String name = g.getName();
        groupService.deletePermanently(g);
        auditService.recordAction("NOTIFICATION_GROUP_DELETE", session, "NOTIFICATION_GROUP",
                String.valueOf(id), name, snapshot);
        return ok(Map.of("message", "Grup silindi", "id", id));
    }

    // ── Değişiklik geçmişi ───────────────────────────────────────────────────

    /**
     * "Kim, ne zaman, neyi değiştirdi" — silinmiş gruplar DAHİL.
     *
     * <p>Ekranı açan yetki ({@code notification.groups}) yeterlidir; denetim uçlarının istediği
     * {@code audit_log.read} aranmaz — ama kapsam takıma sıkı sıkıya bağlıdır: kullanıcı yalnız
     * GÖREBİLDİĞİ takımların grup geçmişini alır. Silinmiş grup için takım, denetim kaydındaki
     * anlık görüntüden çözülür ({@link NotificationGroupHistoryService}).
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(required = false) Long groupId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(defaultValue = "25") int limit,
                                                       HttpSession session) {
        permissionService.require(session, PERM, "view");
        // null = sınırsız; global görücü dışında herkes kendi görüş kapsamıyla sınırlı.
        List<Long> scope = SessionScope.isGlobalViewer(session) ? null : readableTeamIds(session);

        // 2026-09-20: sayfalı (page/size); eski `limit` yalnız size verilmediğinde sayfa boyutu olur.
        var h = historyService.page(scope, groupId, page, size != null ? size : limit);
        Map<Long, String> teamNames = new LinkedHashMap<>();
        teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", h.items().stream().map(e -> historyItem(e, teamNames)).toList());
        out.put("total", h.total());
        out.put("page", h.page());
        out.put("size", h.size());
        out.put("total_pages", (int) Math.ceil(h.total() / (double) h.size()));
        out.put("truncated", h.truncated());
        // Takımı çözülemeyen satır sayısı: eksik bir geçmişi tam sanmak, geçmişin kendisinden
        // daha kötüdür.
        out.put("hidden", h.hidden());
        // Eylemi yapanın IP'si kimlik izidir (2026-09-28c): yalnız global admin + AUDIT ve kişinin kendi satırı.
        return ok(IdentityMask.forSession(out, session));
    }

    private static Map<String, Object> historyItem(NotificationGroupHistoryService.Entry e,
                                                   Map<Long, String> teamNames) {
        var r = e.row();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("at", r.getEventTime());
        m.put("actor", r.getActor());
        // Ekran etiketi ile olay türü ayrı kalsın: arayüz kısa eylemi çevirir, ham tür kanıttır.
        m.put("action", r.getEventType() != null
                ? r.getEventType().replace("NOTIFICATION_GROUP_", "") : null);
        m.put("event_type", r.getEventType());
        m.put("group_id", r.getResourceId());
        m.put("group_name", r.getDetail());
        m.put("team_id", e.teamId());
        m.put("team_name", teamNames.get(e.teamId()));
        m.put("changes", r.getChanges());
        m.put("ip", r.getIpAddress());
        m.put("outcome", r.getOutcome());
        return m;
    }

    // ── Gövde ayrıştırma ─────────────────────────────────────────────────────

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Adresler dizi ya da virgüllü tek dize olarak gelebilir; ikisi de kabul edilir. */
    private static List<String> emails(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (o instanceof String s) return List.of(s);
        return List.of();
    }
}
