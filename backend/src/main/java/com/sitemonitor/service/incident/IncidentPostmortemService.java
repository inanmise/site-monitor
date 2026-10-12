package com.sitemonitor.service.incident;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AlertCommentRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentActionItemRepository;
import com.sitemonitor.repository.IncidentPostmortemRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;

/**
 * Postmortem şablonu + aksiyon takibi + otomatik zaman çizelgesi (2026-10-11, kullanıcı isteği). Olay kaydının
 * ({@link IncidentRecord}) üstüne eklenir; olay kaydının kendisi değişmez.
 *
 * <ul>
 *   <li><b>Şablon</b> (suçlamasız): özet, etki, kök neden, tetikleyici, tespit, çözüm, iyi gidenler, kötü gidenler,
 *       şans eseri olanlar, öğrenilenler. Henüz rapor yoksa olay kaydının etki / kök neden / çözüm / açıklama alanları
 *       ön doldurma olarak döner (kaydedilmeden hiçbir şey yazılmaz).</li>
 *   <li><b>Aksiyon</b>: başlık, açıklama, tür, öncelik, sahip (aktif kullanıcı), son tarih, durum. Olay başına ≤ 50.</li>
 *   <li><b>Zaman çizelgesi</b>: olay damgaları + bağlı alarmın yaşam döngüsü (açıldı / ilk bildirim / sahiplenildi /
 *       kapandı), alarm yorumları, aksiyon eklendi / tamamlandı, rapor yayımlandı — yalnız mevcut kayıtlardan, ayrı
 *       bir tablo yok. Alarm kısmı yalnız alarmı görebilen kişiye (çağıran karar verir).</li>
 * </ul>
 */
@Slf4j
@Service
public class IncidentPostmortemService {

    public static final List<String> STATUSES = List.of("DRAFT", "IN_REVIEW", "PUBLISHED");
    /** Şablon bölümleri — istek/yanıt anahtarları (SIRA = rapordaki sıra). */
    public static final List<String> SECTIONS = List.of(
            "summary", "impact", "root_cause", "trigger", "detection", "resolution", "went_well", "went_wrong", "lucky", "lessons");
    public static final List<String> ACTION_STATUSES = List.of("OPEN", "IN_PROGRESS", "DONE", "CANCELLED");
    public static final Set<String> OPEN_STATUSES = Set.of("OPEN", "IN_PROGRESS");
    public static final List<String> KINDS = List.of("PREVENT", "DETECT", "MITIGATE", "PROCESS", "OTHER");
    public static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");

    public static final int MAX_SECTION_CHARS = 20_000;
    public static final int MAX_ACTIONS_PER_INCIDENT = 50;
    public static final int MAX_TITLE_CHARS = 255;
    public static final int MAX_DESCRIPTION_CHARS = 4_000;
    static final int COMMENT_EXCERPT = 280;
    static final int MAX_TIMELINE_COMMENTS = 50;

    public static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final IncidentPostmortemRepository pmRepo;
    private final IncidentActionItemRepository actionRepo;
    private final AppUserRepository userRepo;

    @Autowired(required = false) private AlertEventRepository alertRepo;
    @Autowired(required = false) private AlertCommentRepository commentRepo;
    @Autowired(required = false) private NotificationLogRepository logRepo;

    public IncidentPostmortemService(IncidentPostmortemRepository pmRepo, IncidentActionItemRepository actionRepo,
                                     AppUserRepository userRepo) {
        this.pmRepo = pmRepo;
        this.actionRepo = actionRepo;
        this.userRepo = userRepo;
    }

    void setAlertSources(AlertEventRepository alerts, AlertCommentRepository comments, NotificationLogRepository logs) {
        this.alertRepo = alerts;
        this.commentRepo = comments;
        this.logRepo = logs;
    }

    static String now() { return ISO.format(Instant.now()); }

    public static String today() { return LocalDate.now(IST).toString(); }

    // ── Rapor ────────────────────────────────────────────────────────────────────────────────────────────

    /** Rapor görünümü: kayıt varsa o, yoksa olay kaydından ön doldurulmuş boş şablon ({@code exists:false}). */
    public Map<String, Object> postmortemView(IncidentRecord inc) {
        Optional<IncidentPostmortem> pm = pmRepo.findByIncidentId(inc.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (pm.isPresent()) {
            IncidentPostmortem p = pm.get();
            m.put("exists", true);
            m.put("status", p.getStatus());
            m.put("version", p.getVersion());
            for (String k : SECTIONS) m.put(k, section(p, k));
            m.put("created_by", p.getCreatedBy());
            m.put("created_at", p.getCreatedAt());
            m.put("updated_by", p.getUpdatedBy());
            m.put("updated_at", p.getUpdatedAt());
            m.put("published_by", p.getPublishedBy());
            m.put("published_at", p.getPublishedAt());
        } else {
            m.put("exists", false);
            m.put("status", "DRAFT");
            m.put("version", null);
            for (String k : SECTIONS) m.put(k, "");
            m.put("summary", nz(inc.getDescription()));
            m.put("impact", nz(inc.getBusinessImpact()));
            m.put("root_cause", nz(inc.getRcaSummary()));
            m.put("resolution", nz(inc.getResolutionSteps()));
        }
        return m;
    }

    /**
     * Raporu kaydeder. {@code version}: istemcinin okuduğu sürüm — kayıt varken farklıysa (başkası arada kaydetti)
     * {@link OptimisticLockingFailureException} → 409. Gönderilmeyen bölüm DEĞİŞMEZ.
     */
    public IncidentPostmortem save(IncidentRecord inc, Map<String, Object> body, String actor) {
        if (body == null) body = Map.of();
        IncidentPostmortem p = pmRepo.findByIncidentId(inc.getId()).orElse(null);
        boolean created = p == null;
        Long sent = longVal(body.get("version"));
        if (!created && (sent == null || !sent.equals(p.getVersion()))) {
            throw new OptimisticLockingFailureException("postmortem sürümü değişti");
        }
        if (created) {
            p = new IncidentPostmortem();
            p.setIncidentId(inc.getId());
            p.setStatus("DRAFT");
            p.setCreatedBy(actor);
            p.setCreatedAt(now());
        }
        for (String k : SECTIONS) {
            if (!body.containsKey(k)) continue;
            String v = body.get(k) == null ? null : String.valueOf(body.get(k));
            if (v != null && v.length() > MAX_SECTION_CHARS) {
                throw new FieldValidationException(k, Msg.t(
                        "Bu bölüm en çok " + MAX_SECTION_CHARS + " karakter olabilir. Metni kısaltın ya da ayrıntıyı ek belgeye taşıyın.",
                        "This section can be at most " + MAX_SECTION_CHARS + " characters. Shorten it or move the detail to an attached document."));
            }
            setSection(p, k, v == null || v.isBlank() ? null : v);
        }
        if (body.containsKey("status")) {
            String st = String.valueOf(body.get("status")).trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(st)) {
                throw new FieldValidationException("status", Msg.t(
                        "Rapor durumu Taslak, İncelemede ya da Yayımlandı olmalı.",
                        "The report status must be Draft, In review or Published."));
            }
            if ("PUBLISHED".equals(st) && !"PUBLISHED".equals(p.getStatus())) {
                p.setPublishedAt(now());
                p.setPublishedBy(actor);
            } else if (!"PUBLISHED".equals(st)) {
                p.setPublishedAt(null);
                p.setPublishedBy(null);
            }
            p.setStatus(st);
        }
        p.setUpdatedBy(actor);
        p.setUpdatedAt(now());
        return pmRepo.saveAndFlush(p);
    }

    static String section(IncidentPostmortem p, String key) {
        String v = switch (key) {
            case "summary" -> p.getSummary();
            case "impact" -> p.getImpact();
            case "root_cause" -> p.getRootCause();
            case "trigger" -> p.getTriggerText();
            case "detection" -> p.getDetection();
            case "resolution" -> p.getResolution();
            case "went_well" -> p.getWentWell();
            case "went_wrong" -> p.getWentWrong();
            case "lucky" -> p.getLucky();
            case "lessons" -> p.getLessons();
            default -> null;
        };
        return v == null ? "" : v;
    }

    static void setSection(IncidentPostmortem p, String key, String v) {
        switch (key) {
            case "summary" -> p.setSummary(v);
            case "impact" -> p.setImpact(v);
            case "root_cause" -> p.setRootCause(v);
            case "trigger" -> p.setTriggerText(v);
            case "detection" -> p.setDetection(v);
            case "resolution" -> p.setResolution(v);
            case "went_well" -> p.setWentWell(v);
            case "went_wrong" -> p.setWentWrong(v);
            case "lucky" -> p.setLucky(v);
            case "lessons" -> p.setLessons(v);
            default -> { }
        }
    }

    // ── Aksiyonlar ───────────────────────────────────────────────────────────────────────────────────────

    public List<IncidentActionItem> actions(Long incidentId) {
        return actionRepo.findByIncidentIdOrderByIdAsc(incidentId);
    }

    public IncidentActionItem getAction(Long id) {
        return actionRepo.findById(id).orElseThrow(() -> new NoSuchElementException(
                Msg.t("Aksiyon bulunamadı; silinmiş olabilir. Listeyi yenileyin.", "Action item not found; it may have been deleted. Refresh the list.")));
    }

    /** @param ownerId çağıranın çözdüğü sahip kimliği (opak → sayısal); null = sahipsiz */
    public IncidentActionItem createAction(IncidentRecord inc, Map<String, Object> body, Long ownerId, String actor) {
        if (actionRepo.countByIncidentId(inc.getId()) >= MAX_ACTIONS_PER_INCIDENT) {
            throw new FieldValidationException("title", Msg.t(
                    "Bir olaya en çok " + MAX_ACTIONS_PER_INCIDENT + " aksiyon eklenebilir. Biten ya da gereksiz aksiyonları kapatın.",
                    "At most " + MAX_ACTIONS_PER_INCIDENT + " action items can be added to an incident. Close finished or unneeded ones."));
        }
        IncidentActionItem a = new IncidentActionItem();
        a.setIncidentId(inc.getId());
        a.setStatus("OPEN");
        a.setKind("PREVENT");
        a.setPriority("MEDIUM");
        a.setCreatedBy(actor);
        a.setCreatedAt(now());
        apply(a, body == null ? Map.of() : body, ownerId, true);
        a.setUpdatedBy(actor);
        a.setUpdatedAt(a.getCreatedAt());
        return actionRepo.save(a);
    }

    /**
     * Aksiyonu günceller. {@code statusOnly}: sahibi olduğu aksiyonun YALNIZ durumunu değiştiren kişi (olayı düzenleme
     * yetkisi yok) — diğer alanlar yok sayılmaz, reddedilir (sessiz kısmi kayıt yok).
     */
    public IncidentActionItem updateAction(IncidentActionItem a, Map<String, Object> body, Long ownerId, boolean ownerSent,
                                           boolean statusOnly, String actor) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (statusOnly) {
            for (String k : b.keySet()) {
                if (!"status".equals(k)) {
                    throw new SecurityException(Msg.t(
                            "Size atanan aksiyonun yalnız durumunu değiştirebilirsiniz; diğer alanlar için olayı düzenleme yetkisi gerekir.",
                            "You can only change the status of an action assigned to you; other fields need permission to edit the incident."));
                }
            }
        }
        apply(a, b, ownerId, ownerSent);
        a.setUpdatedBy(actor);
        a.setUpdatedAt(now());
        return actionRepo.save(a);
    }

    public void deleteAction(IncidentActionItem a) {
        actionRepo.delete(a);
    }

    private void apply(IncidentActionItem a, Map<String, Object> b, Long ownerId, boolean ownerSent) {
        if (b.containsKey("title") || a.getTitle() == null) {
            String title = b.get("title") == null ? "" : String.valueOf(b.get("title")).trim();
            if (title.isEmpty()) {
                throw new FieldValidationException("title", Msg.t(
                        "Aksiyon başlığı boş olamaz. Yapılacak işi kısa bir cümleyle yazın.",
                        "The action title cannot be empty. Describe the work in a short sentence."));
            }
            if (title.length() > MAX_TITLE_CHARS) {
                throw new FieldValidationException("title", Msg.t(
                        "Aksiyon başlığı en çok " + MAX_TITLE_CHARS + " karakter olabilir; ayrıntıyı açıklamaya yazın.",
                        "The action title can be at most " + MAX_TITLE_CHARS + " characters; put the detail in the description."));
            }
            a.setTitle(title);
        }
        if (b.containsKey("description")) {
            String d = b.get("description") == null ? null : String.valueOf(b.get("description")).trim();
            if (d != null && d.length() > MAX_DESCRIPTION_CHARS) {
                throw new FieldValidationException("description", Msg.t(
                        "Açıklama en çok " + MAX_DESCRIPTION_CHARS + " karakter olabilir.",
                        "The description can be at most " + MAX_DESCRIPTION_CHARS + " characters."));
            }
            a.setDescription(d == null || d.isEmpty() ? null : d);
        }
        if (b.containsKey("kind")) a.setKind(oneOf("kind", b.get("kind"), KINDS, Msg.t(
                "Aksiyon türü Önle, Tespit et, Etkiyi azalt, Süreç ya da Diğer olmalı.",
                "The action type must be Prevent, Detect, Mitigate, Process or Other.")));
        if (b.containsKey("priority")) a.setPriority(oneOf("priority", b.get("priority"), PRIORITIES, Msg.t(
                "Öncelik Yüksek, Orta ya da Düşük olmalı.", "Priority must be High, Medium or Low.")));
        if (b.containsKey("status")) {
            String st = oneOf("status", b.get("status"), ACTION_STATUSES, Msg.t(
                    "Durum Açık, Sürüyor, Tamamlandı ya da İptal olmalı.", "Status must be Open, In progress, Done or Cancelled."));
            boolean nowDone = "DONE".equals(st) || "CANCELLED".equals(st);
            boolean wasDone = "DONE".equals(a.getStatus()) || "CANCELLED".equals(a.getStatus());
            if (nowDone && !wasDone) a.setCompletedAt(now());
            if (!nowDone) a.setCompletedAt(null);
            a.setStatus(st);
        }
        if (b.containsKey("due_date")) {
            Object raw = b.get("due_date");
            String due = raw == null ? "" : String.valueOf(raw).trim();
            if (due.isEmpty()) {
                a.setDueDate(null);
            } else {
                try {
                    LocalDate d = LocalDate.parse(due);
                    String norm = d.toString();
                    if (!norm.equals(a.getDueDate())) a.setRemindedOn(null);   // yeni tarih → hatırlatma baştan
                    a.setDueDate(norm);
                } catch (DateTimeParseException e) {
                    throw new FieldValidationException("due_date", Msg.t(
                            "Son tarih geçerli bir gün olmalı; takvimden seçin.", "The due date must be a valid day; pick it from the calendar."));
                }
            }
        }
        if (ownerSent) {
            if (ownerId == null) {
                a.setOwnerUserId(null);
                a.setOwnerName(null);
            } else {
                AppUser u = userRepo.findById(ownerId).orElse(null);
                if (u == null) {
                    throw new FieldValidationException("owner_user_id", Msg.t(
                            "Seçilen kişi bulunamadı. Listeden yeniden seçin.", "The selected person was not found. Pick them again from the list."));
                }
                if (!Boolean.TRUE.equals(u.getActive())) {
                    throw new FieldValidationException("owner_user_id", Msg.t(
                            "Pasif bir kullanıcıya aksiyon atanamaz. Aktif bir sahip seçin.", "An action cannot be assigned to an inactive user. Pick an active owner."));
                }
                if (!ownerId.equals(a.getOwnerUserId())) a.setRemindedOn(null);
                a.setOwnerUserId(u.getId());
                a.setOwnerName(displayName(u));
            }
        }
    }

    private static String oneOf(String field, Object raw, List<String> allowed, String message) {
        String v = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) throw new FieldValidationException(field, message);
        return v;
    }

    static String displayName(AppUser u) {
        String n = u.getDisplayName();
        return n == null || n.isBlank() ? u.getUsername() : n;
    }

    /** Aksiyon DTO'su — sahip kimliği {@code owner_user_id} (opak kimliğe çevrilen anahtar). */
    public static Map<String, Object> actionDto(IncidentActionItem a, String today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("incident_id", a.getIncidentId());
        m.put("title", a.getTitle());
        m.put("description", a.getDescription());
        m.put("kind", a.getKind());
        m.put("priority", a.getPriority());
        m.put("status", a.getStatus());
        m.put("owner_user_id", a.getOwnerUserId());
        m.put("owner_name", a.getOwnerName());
        m.put("due_date", a.getDueDate());
        m.put("overdue", OPEN_STATUSES.contains(a.getStatus()) && a.getDueDate() != null && a.getDueDate().compareTo(today) < 0);
        m.put("created_by", a.getCreatedBy());
        m.put("created_at", a.getCreatedAt());
        m.put("updated_by", a.getUpdatedBy());
        m.put("updated_at", a.getUpdatedAt());
        m.put("completed_at", a.getCompletedAt());
        return m;
    }

    /** Liste rozetleri — sayfadaki olaylar için iki sorgu: {id → {postmortem_status, actions_open, actions_overdue, actions_done, actions_total}}. */
    public Map<Long, Map<String, Object>> summaries(Collection<Long> incidentIds) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (incidentIds == null || incidentIds.isEmpty()) return out;
        for (Object[] r : pmRepo.statusFor(incidentIds)) {
            if (r == null || r[0] == null) continue;
            out.computeIfAbsent(((Number) r[0]).longValue(), k -> new LinkedHashMap<>()).put("postmortem_status", r[1]);
        }
        for (Object[] r : actionRepo.countsFor(incidentIds, today())) {
            if (r == null || r[0] == null) continue;
            Map<String, Object> m = out.computeIfAbsent(((Number) r[0]).longValue(), k -> new LinkedHashMap<>());
            m.put("actions_open", num(r[1]));
            m.put("actions_overdue", num(r[2]));
            m.put("actions_done", num(r[3]));
            m.put("actions_total", num(r[4]));
        }
        return out;
    }

    /** Olaylar arası aksiyon listesi + sayaçlar. */
    public Map<String, Object> search(boolean scoped, List<Long> scope, Long me, boolean mineOnly, String status,
                                      boolean overdueOnly, String q, int page, int size) {
        List<Long> sc = scope == null || scope.isEmpty() ? List.of(-1L) : scope;
        List<String> statuses = switch (status == null ? "open" : status.toLowerCase(Locale.ROOT)) {
            case "done" -> List.of("DONE", "CANCELLED");
            case "all" -> ACTION_STATUSES;
            default -> List.of("OPEN", "IN_PROGRESS");
        };
        String like = q == null || q.isBlank() ? "" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        if (like.length() > 202) like = like.substring(0, 201) + "%";
        String today = today();
        int sz = Math.max(1, Math.min(size, 100));
        Page<Object[]> res = actionRepo.search(scoped, sc, me, mineOnly && me != null, statuses, overdueOnly, today, like,
                PageRequest.of(Math.max(0, page), sz));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : res.getContent()) {
            Map<String, Object> m = actionDto((IncidentActionItem) r[0], today);
            m.put("incident_title", r[1]);
            m.put("incident_team_name", r[2]);
            m.put("incident_severity", r[3]);
            m.put("mine", me != null && me.equals(((IncidentActionItem) r[0]).getOwnerUserId()));
            rows.add(m);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        List<Object[]> t = actionRepo.totals(scoped, sc, me, today);
        Object[] tr = t.isEmpty() || t.get(0) == null ? new Object[3] : t.get(0);
        totals.put("open", num(tr.length > 0 ? tr[0] : null));
        totals.put("overdue", num(tr.length > 1 ? tr[1] : null));
        totals.put("mine", num(tr.length > 2 ? tr[2] : null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("total", res.getTotalElements());
        out.put("page", res.getNumber());
        out.put("size", sz);
        out.put("totals", totals);
        return out;
    }

    public void deleteForIncident(Long incidentId) {
        actionRepo.deleteForIncident(incidentId);
        pmRepo.deleteForIncident(incidentId);
    }

    // ── Zaman çizelgesi ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Otomatik zaman çizelgesi (eskiden yeniye). {@code alertsVisible}: alarm izni; {@code canSeeAlertTeam}: alarmın
     * takımını görebilir mi (takımsız alarm = olay kaydı onu zaten bağlıyor → görünür).
     */
    public List<Map<String, Object>> timeline(IncidentRecord inc, boolean alertsVisible, Predicate<Long> canSeeAlertTeam) {
        List<Map<String, Object>> out = new ArrayList<>();
        add(out, inc.getOccurredAt(), "occurred", null, null);
        add(out, inc.getDetectedAt(), "detected", null, null);
        add(out, inc.getCreatedAt(), "recorded", inc.getCreatedBy(), null);
        add(out, inc.getResolvedAt(), "resolved", null, null);

        Long alertId = inc.getAlertEventId();
        if (alertsVisible && alertId != null && alertRepo != null) {
            AlertEvent ev = alertRepo.findById(alertId).orElse(null);
            if (ev != null && (ev.getTeamId() == null || canSeeAlertTeam == null || canSeeAlertTeam.test(ev.getTeamId()))) {
                Map<String, Object> alertInfo = new LinkedHashMap<>();
                alertInfo.put("alert_id", ev.getId());
                alertInfo.put("domain", ev.getDomain());
                alertInfo.put("alert_type", ev.getAlertType());
                alertInfo.put("alert_level", ev.getAlertLevel());
                add(out, ev.getCreatedAt(), "alert_opened", null, alertInfo);
                notifications(out, alertId);
                add(out, ev.getAcknowledgedAt(), "alert_acknowledged", ev.getAcknowledgedBy(), null);
                add(out, ev.getResolvedAt(), "alert_resolved", ev.getResolvedBy(), null);
                if (commentRepo != null) {
                    List<AlertComment> comments = commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(alertId);
                    int n = 0;
                    for (AlertComment c : comments) {
                        if (++n > MAX_TIMELINE_COMMENTS) break;
                        Map<String, Object> extra = new LinkedHashMap<>();
                        extra.put("text", excerpt(c.getBody()));
                        String who = c.getAuthorName() == null || c.getAuthorName().isBlank() ? c.getAuthorUsername() : c.getAuthorName();
                        add(out, c.getCreatedAt(), "alert_comment", who, extra);
                    }
                }
            }
        }

        for (IncidentActionItem a : actionRepo.findByIncidentIdOrderByIdAsc(inc.getId())) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("action_id", a.getId());
            extra.put("title", a.getTitle());
            extra.put("owner_name", a.getOwnerName());
            add(out, a.getCreatedAt(), "action_added", a.getCreatedBy(), extra);
            if (a.getCompletedAt() != null) {
                Map<String, Object> done = new LinkedHashMap<>(extra);
                done.put("status", a.getStatus());
                add(out, a.getCompletedAt(), "DONE".equals(a.getStatus()) ? "action_done" : "action_cancelled", a.getUpdatedBy(), done);
            }
        }
        pmRepo.findByIncidentId(inc.getId()).ifPresent(p -> add(out, p.getPublishedAt(), "postmortem_published", p.getPublishedBy(), null));

        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("at"))));
        return out;
    }

    /** İlk başarılı bildirim (e-posta / webhook) + toplam başarılı bildirim sayısı — tek olay. */
    private void notifications(List<Map<String, Object>> out, Long alertId) {
        if (logRepo == null) return;
        List<NotificationLog> logs = logRepo.findByAlertEventIdOrderBySentAtDesc(alertId);
        String first = null;
        int sent = 0;
        for (NotificationLog l : logs) {
            boolean ok = startsSent(l.getEmailStatus()) || startsSent(l.getWebhookStatus());
            if (!ok) continue;
            sent++;
            String at = norm(l.getSentAt());
            if (at != null && (first == null || at.compareTo(first) < 0)) first = at;
        }
        if (first != null) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("count", sent);
            add(out, first, "alert_notified", null, extra);
        }
    }

    private static boolean startsSent(String s) {
        return s != null && (s.startsWith("SENT") || s.startsWith("QUEUED"));
    }

    private static void add(List<Map<String, Object>> out, String at, String kind, String actor, Map<String, Object> extra) {
        String iso = norm(at);
        if (iso == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", iso);
        m.put("kind", kind);
        if (actor != null && !actor.isBlank()) m.put("actor", actor);
        if (extra != null) m.putAll(extra);
        out.add(m);
    }

    /** Proje zaman damgaları (bölgesiz UTC, Z'li, ofsetli, saniyesiz) → {@code yyyy-MM-ddTHH:mm:ss} UTC; bozuk → null. */
    static String norm(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 40) return null;
        String s = raw.trim();
        try {
            if (s.endsWith("Z") || s.endsWith("z") || s.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                return ISO.format(OffsetDateTime.parse(s.endsWith("z") ? s.substring(0, s.length() - 1) + "Z" : s).toInstant());
            }
            return ISO.format(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String excerpt(String body) {
        if (body == null) return "";
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() <= COMMENT_EXCERPT ? s : s.substring(0, COMMENT_EXCERPT - 1) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private static Long longVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return Long.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }
}
