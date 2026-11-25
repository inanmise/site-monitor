package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.Team;
import com.sitemonitor.service.incident.IncidentImageValidator;
import com.sitemonitor.service.mail.TechErrorMailImages;
import com.sitemonitor.service.page.scan.ImageDimensions;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Olay/Hata kaydı oluşturulduğunda veya güncellendiğinde, olayın kayıtlı olduğu
 * takımın e-posta adresine ve takım müdürüne (Team.leaderId) executive bir bildirim
 * gönderir. Best-effort + asenkron: HTTP yanıtını bloklamaz, hata kaydı kaydını
 * etkilemez (mail patlasa bile olay kaydedilmiştir). Controller'dan çağrılır
 * (IncidentService'e bağımlılık eklemez → @DataJpaTest izolasyonu korunur).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentNotificationService {

    private final EmailNotificationService emailService;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final IncidentImageRepository imageRepo;
    private final AppSettingsService appSettings;
    /** Takim mailinin yerine gecebilecek bildirim grubu -- olayin monitoru olmadigi icin damgasiz. */
    private final NotificationGroupService notificationGroups;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "incident-mail");
        t.setDaemon(true);
        return t;
    });

    /**
     * Kapanışta havuzu kapat (2026-08-20 bellek denetimi). Kuyrukta bekleyen her görev bir olay
     * DTO'sunu ve (doNotify içinde) e-posta gövdesini canlı tutar; kapatmadan bırakmak testte ve
     * context-refresh'te thread + nesne sızdırır. Desen EmailNotificationService ile aynı:
     * kısa bir graceful pencere, sonra shutdownNow.
     */
    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        // 2026-10-23 (çok pod R2a · D2): kuyruktakiler ortak kapanış süresi içinde gönderilir; başlayamayan her olay postası
        // için notification_logs satırı (eskiden shutdownNow() onları SESSİZCE düşürüyordu).
        DroppableTask.drain(exec, drainCapMs, DroppableTask.MAX_TRACES, this::recordDroppedMore);
    }

    /** Kapanışta kuyruğun en çok bekleneceği süre (ortak kapanış süresiyle sınırlı). Paket-özel: testte kısaltılır. */
    long drainCapMs = 5_000L;

    /** Başlayamayan olay postasının izi için (yoksa yalnız WARN). Alan enjeksiyonu: kurucu ve elle kurulan testler değişmesin. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.NotificationLogRepository notificationLogRepo;

    void setNotificationLogRepo(com.sitemonitor.repository.NotificationLogRepository r) { this.notificationLogRepo = r; }

    /** Olay postası tetikleyicisi — yalnız gönderilemeyen (başlamayan) görevin izinde yazılır. */
    public static final String TRIGGER_INCIDENT_NOTIFY = "INCIDENT_NOTIFY";
    static final String STATUS_DROPPED =
            "FAILED: sonuç yok — pod kapanırken olay kaydı postası kuyrukta kaldı, gönderilmedi";

    /** dto = controller'ın ürettiği snake_case olay haritası. kind = NEW | UPDATED | RESOLVED. */
    public void notifyIncident(Map<String, Object> dto, String kind) {
        if (dto == null) return;
        DroppableTask.submit(exec, () -> {
            try { doNotify(dto, kind); }
            catch (Exception e) { log.warn("Incident notification failed: {}", e.getMessage()); }
        }, () -> recordDropped(dto, kind));
    }

    /** Başlayamayan olay postasının {@code notification_logs} izi (alıcılar henüz çözülmedi — takım adıyla). */
    void recordDropped(Map<String, Object> dto, String kind) {
        String prefix = "RESOLVED".equals(kind) ? "Olay Çözüldü" : "NEW".equals(kind) ? "Yeni Olay" : "Olay Güncellendi";
        Object teamName = dto.get("team_name");
        log.warn("Olay kaydı postası başlayamadı (pod kapanıyor): '{}' takım={} — gönderilmedi", dto.get("title"), dto.get("team_id"));
        saveTrace(teamName == null ? "-" : teamName.toString(), "[Site Monitor] " + prefix + " — " + dto.get("title"), STATUS_DROPPED);
    }

    /** Görev başı iz sınırını aşan başlamamış postalar için TEK özet satırı. */
    void recordDroppedMore(int more) {
        saveTrace("-", "[Site Monitor] Olay kaydı postaları — " + more + " posta daha",
                "FAILED: sonuç yok — pod kapanırken " + more + " olay kaydı postası daha kuyrukta kaldı, gönderilmedi");
    }

    private void saveTrace(String name, String subject, String status) {
        if (notificationLogRepo == null) return;
        com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
        n.setAlertEventId(0L);
        n.setSentAt(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now()));
        n.setRecipientName(name);
        n.setRecipientEmail("");
        n.setRecipientRole("TEAM");
        n.setSubject(subject);
        n.setEmailStatus(status);
        n.setWebhookStatus("SKIPPED");
        n.setTrigger(TRIGGER_INCIDENT_NOTIFY);
        notificationLogRepo.save(n);
    }

    /** E-posta deep-link'leri için dış base URL — CANLI okunur (Genel Ayarlar'dan değişebilir);
     *  @Value yalnız fallback. Haftalık reminder/approve linkleriyle AYNI kaynak (site.monitor.app.base-url). */
    private String baseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        return (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
    }

    /* package-private (test): senkron çözüm + alıcı/konu doğrulaması için. */
    void doNotify(Map<String, Object> dto, String kind) {
        Object teamIdObj = dto.get("team_id");
        if (!(teamIdObj instanceof Number tid)) {
            log.debug("Incident notify skipped — no team_id on incident '{}'", dto.get("title"));
            return;
        }
        Team team = teamRepo.findById(tid.longValue()).orElse(null);
        if (team == null) { log.warn("Incident notify: team {} not found", teamIdObj); return; }

        // Takim bileseni: varsayilan bildirim grubu varsa takim mailinin YERINE gecer.
        // Damga yok -- olay bir monitore bagli degildir, bu yuzden takim seviyesinde cozulur.
        List<String> recipients = new ArrayList<>();
        NotificationGroupService.Override ov = notificationGroups.overrideFor(team.getId());
        if (ov != null && ov.applies()) {
            recipients.addAll(ov.emails());
        } else if (team.getEmail() != null && !team.getEmail().isBlank()) {
            recipients.add(team.getEmail().trim());
        }

        String managerName = null;
        if (team.getLeaderId() != null) {
            AppUser mgr = userRepo.findById(team.getLeaderId()).orElse(null);
            if (mgr != null && !Boolean.TRUE.equals(mgr.getActive())) {
                // Pasif takım lideri (2026-10-02, kullanıcı kararı): pasif kullanıcıya hiçbir bildirim gitmez — ne alıcı
                // olur ne de e-postada "müdür" olarak adı geçer. İz: WARN (olay postası bildirim günlüğüne yazılmaz).
                log.warn("Incident notify: takım {} lideri pasif kullanıcı ({}) — alıcılardan çıkarıldı", team.getId(), mgr.getUsername());
                mgr = null;
            }
            if (mgr != null) {
                managerName = (mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                        ? mgr.getDisplayName() : mgr.getUsername();
                String me = mgr.getEmail();
                if (me != null && !me.isBlank() && !recipients.contains(me.trim())) recipients.add(me.trim());
            }
        }
        if (recipients.isEmpty()) {
            log.warn("Incident notify: team {} has no group/email and no (active) manager email — skipped", team.getId());
            return;
        }

        String base = baseUrl();
        Object incId = dto.get("id");
        // Spesifik olaya deep-link: frontend ?incident=<id>'yi okuyup detay modalını açar.
        String ctaUrl = base + "/?tab=incident-history" + (incId != null ? "&incident=" + incId : "");
        // Görsel planı (2026-11-20, "Hızlı Giriş"): görseller ≤ 1000 px kopyayla gömülür, toplam bütçeyi aşan / okunamayan
        // görselin yerine kısa bir not gider — önizleme AYNI planı kullanır (gönderilenle aynı görünüm).
        MailImagePlan plan = mailImagePlan(dto, null);
        String html = emailService.buildIncidentNotificationHtml(dto, managerName, kind, ctaUrl, true, plan.embedded());
        String prefix = "RESOLVED".equals(kind) ? "Olay Çözüldü"
                      : "NEW".equals(kind)      ? "Yeni Olay"
                                                : "Olay Güncellendi";
        String subject = "[Site Monitor] " + prefix + " — " + dto.get("title") + " (" + team.getName() + ")";

        List<EmailNotificationService.InlineImage> inline = plan.inline();
        String status = emailService.sendHtml(recipients.toArray(new String[0]), null, subject, html,
                inline.isEmpty() ? null : inline);
        log.info("Incident notification ({}) team={} to={} status={} images={}",
                kind, team.getId(), SecretMask.maskEmails(recipients), status, inline.size());
    }

    /** Önizleme için olay bildirim HTML'i — KAYDETMEZ/GÖNDERMEZ. Görseller /api/incidents/images/{id} URL'siyle
     *  kalır (iframe oturum çerezi ile yükler). team_name'i team_id'den, müdür adını takım leader'ından çözer.
     *  Alıcı/team-mail gerektirmez (yalnız HTML üretir). */
    public String previewHtml(Map<String, Object> dto, String kind) {
        return previewHtml(dto, kind, null);
    }

    /**
     * {@code viewer} (2026-11-20): önizlemeyi isteyen kullanıcı — görsel planına onun bağlanmamış taslak görselleri de girer
     * (kaydedince bu olaya bağlanacak olanlar; gönderimde de gömülürler). Gönderimle AYNI plan: gömülmeyecek görsel önizlemede
     * de not olarak görünür.
     */
    public String previewHtml(Map<String, Object> dto, String kind, String viewer) {
        if (dto == null) return "";
        Map<String, Object> inc = new java.util.LinkedHashMap<>(dto);
        String managerName = null;
        if (dto.get("team_id") instanceof Number tid) {
            Team team = teamRepo.findById(tid.longValue()).orElse(null);
            if (team != null) {
                inc.put("team_name", team.getName());   // form'daki team_id'ye göre güncel takım adı
                if (team.getLeaderId() != null) {
                    AppUser mgr = userRepo.findById(team.getLeaderId()).orElse(null);
                    if (mgr != null) managerName = (mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                            ? mgr.getDisplayName() : mgr.getUsername();
                }
            }
        }
        String base = baseUrl();
        Object incId = dto.get("id");
        String ctaUrl = base + "/?tab=incident-history" + (incId != null ? "&incident=" + incId : "");
        String norm = ("NEW".equals(kind) || "RESOLVED".equals(kind)) ? kind : "UPDATED";
        return emailService.buildIncidentNotificationHtml(inc, managerName, norm, ctaUrl, false,
                mailImagePlan(inc, viewer).embedded());
    }

    private static final Pattern INC_IMG_ID = Pattern.compile("/api/incidents/images/(\\d{1,18})");

    /** Gömülü görsellerin toplam bütçesi (MB) — {@code site.monitor.incident.mail-inline-mb}; 0 = görsel gömülmez. */
    @Value("${site.monitor.incident.mail-inline-mb:6}")
    private int mailInlineMb = 6;

    /** Bir e-postada en çok bakılan görsel referansı (sonrakiler not olarak kalır). */
    static final int MAX_MAIL_IMAGES = 50;
    /** Görsel küçültme aynı anda en çok iki işte (çözülen piksel belleği sınırlı). */
    private static final java.util.concurrent.Semaphore PREP = new java.util.concurrent.Semaphore(2);
    /** Küçültme izni için en uzun bekleme; dolarsa görseller bu e-postaya eklenmez (not olarak kalır). */
    static final long PREP_WAIT_MS = 20_000;

    /** E-postanın görsel planı: gömülecek ekler (CID {@code incimg<id>}) ve gömülen görsel kimlikleri. */
    public record MailImagePlan(List<EmailNotificationService.InlineImage> inline, Set<Long> embedded) {
        static MailImagePlan none() { return new MailImagePlan(List.of(), Set.of()); }
    }

    /**
     * Olayın markdown alanlarındaki {@code /api/incidents/images/{id}} görsellerinden e-posta planı (2026-11-20, "Hızlı
     * Giriş" — tam çözünürlük görsel): YALNIZ bu olayın görselleri (prod kapısı 2026-09-25, Y-1 — başka olayın görseli maille
     * dışarı gitmez) ve önizlemede {@code viewer}'ın bağlanmamış taslakları. PNG / JPEG / BMP {@link TechErrorMailImages} ile
     * ≤ 1000 × 1400 kopyaya küçültülür (sığan PNG / JPEG olduğu gibi); GIF çözülmez — kutuya sığıyorsa olduğu gibi gömülür;
     * WebP ve okunamayan görsel gömülmez. Toplam bayt {@code mail-inline-mb} ile sınırlıdır: sığmayan görsel nota döner
     * ({@link EmailNotificationService#buildIncidentNotificationHtml}). Hiçbir koşulda fırlatmaz (görsel yoksa boş plan).
     */
    MailImagePlan mailImagePlan(Map<String, Object> dto, String viewer) {
        if (dto == null) return MailImagePlan.none();
        Set<Long> ids = new LinkedHashSet<>();
        for (String field : List.of("rca_summary", "description", "business_impact", "resolution_steps")) {
            Object v = dto.get(field);
            if (v == null) continue;
            Matcher m = INC_IMG_ID.matcher(v.toString());
            while (ids.size() < MAX_MAIL_IMAGES && m.find()) {
                try { ids.add(Long.parseLong(m.group(1))); } catch (NumberFormatException ignored) { /* 18 hane sığar */ }
            }
        }
        if (ids.isEmpty()) return MailImagePlan.none();
        long budget = Math.max(0, Math.min(mailInlineMb, 20)) * 1024L * 1024L;
        if (budget == 0) return MailImagePlan.none();
        Long incidentId = dto.get("id") instanceof Number n ? n.longValue() : null;
        boolean acquired = false;
        try {
            acquired = PREP.tryAcquire(PREP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("Olay e-postası görsel planı: küçültme kuyruğu dolu — görseller bu e-postaya eklenmedi");
                return MailImagePlan.none();
            }
            List<EmailNotificationService.InlineImage> inline = new ArrayList<>();
            Set<Long> embedded = new LinkedHashSet<>();
            long left = budget;
            for (Long id : ids) {
                IncidentImage img = imageRepo.findById(id).orElse(null);
                if (img == null || !eligible(img, incidentId, viewer)) continue;
                byte[] bytes = img.getData();
                if (bytes == null || bytes.length == 0) continue;
                String type = ImageDimensions.type(bytes);
                ImageDimensions.Size size = ImageDimensions.read(bytes);
                if (size == null) continue;
                if (ImageDimensions.GIF.equals(type)) {
                    // GIF çözülmez (ilk kare mantıksal ekrandan büyük olabilir): kutuya sığıyorsa olduğu gibi gömülür
                    if (size.width() <= TechErrorMailImages.MAX_WIDTH && size.height() <= TechErrorMailImages.MAX_HEIGHT
                            && bytes.length <= left) {
                        inline.add(new EmailNotificationService.InlineImage("incimg" + id, bytes, "image/gif"));
                        embedded.add(id);
                        left -= bytes.length;
                    }
                    continue;
                }
                boolean decodable = ImageDimensions.PNG.equals(type) || ImageDimensions.JPEG.equals(type)
                        || ImageDimensions.BMP.equals(type);
                if (!decodable || (long) size.width() * size.height() > IncidentImageValidator.MAX_PIXELS) continue;
                String slot = "incimg" + id;
                TechErrorMailImages.Result r = TechErrorMailImages.prepare(
                        Map.of(slot, new TechErrorPdfInput.Image(bytes, size.width(), size.height())), List.of(slot),
                        TechErrorMailImages.MAX_WIDTH, TechErrorMailImages.MAX_HEIGHT, left);
                TechErrorMailImages.Prepared p = r.embedded().get(slot);
                if (p == null) continue;
                inline.add(new EmailNotificationService.InlineImage(slot, p.data(), p.contentType()));
                embedded.add(id);
                left -= p.data().length;
            }
            return new MailImagePlan(List.copyOf(inline), java.util.Collections.unmodifiableSet(embedded));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MailImagePlan.none();
        } catch (RuntimeException e) {
            log.warn("Olay e-postası görsel planı hazırlanamadı — görseller eklenmedi: {}", e.toString());
            return MailImagePlan.none();
        } finally {
            if (acquired) PREP.release();
        }
    }

    /** Bu olayın görseli ya da (önizlemede) isteyenin bağlanmamış taslağı. */
    private static boolean eligible(IncidentImage img, Long incidentId, String viewer) {
        if (img.getIncidentId() != null) return incidentId != null && incidentId.equals(img.getIncidentId());
        return viewer != null && viewer.equalsIgnoreCase(img.getCreatedBy());
    }
}
